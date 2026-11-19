"""The agent phone's gRPC client: the emulator's EmulatorController over h2c on loopback, bearer token required.

No grpcio or protobuf in Hermes (EMU-DESIGN §0.5): the vendored pure-Python h2 (``vendor/``, MIT) carries about eight
hand-encoded messages. Ported from EMU-SPIKES ``tools/grpc_h2.py`` and ``tools/pb.py`` (S1, S3, S7 ran on them). Field numbers
are those of emulator 37.2.13's shipped ``emulator_controller.proto``; ``check_proto(text)`` re-checks them against any proto,
so a newer emulator is used only when its proto still matches (the 404 fallback, §3).

Blocking and thread-safe for unary calls (one lock per connection). A screenshot stream holds its connection: open a second
client for control calls while one streams. The token lives only in the client object: never logged, never in an error.
"""
import importlib
import importlib.util
import socket
import struct
import sys
import threading
import time
from pathlib import Path

SERVICE = '/android.emulation.control.EmulatorController/'
WINDOW = 1 << 26  # 64 MB stream and connection windows: a full 1080x2400 RGB888 frame is 7.8 MB (S1)
_VENDOR = 'beacon_agent_phone_vendor'
_vendor_lock = threading.Lock()


def _vendor():
    """The vendored h2 as one private package: imported by file location, never through sys.path."""
    with _vendor_lock:
        if _VENDOR not in sys.modules:
            root = Path(__file__).resolve().parent / 'vendor'
            spec = importlib.util.spec_from_file_location(_VENDOR, root / '__init__.py', submodule_search_locations=[str(root)])
            module = importlib.util.module_from_spec(spec)
            sys.modules[_VENDOR] = module
            spec.loader.exec_module(module)
        mods = {name: importlib.import_module(f'{_VENDOR}.h2.{name}') for name in ('config', 'connection', 'events', 'settings')}
    return mods


class GrpcError(Exception):
    """A failed call. ``status`` is the gRPC status (16 = the token was refused, 14 = the phone is not reachable)."""

    def __init__(self, status, message):
        super().__init__(f'grpc-status {status}: {message}')
        self.status = status


# -- protobuf, by hand ---------------------------------------------------------------------------------------------------

def _varint(n):
    if n < 0:
        n += 1 << 64
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _f_varint(num, value):
    return _varint(num << 3) + _varint(int(value)) if value else b''


def _f_bytes(num, data):
    if isinstance(data, str):
        data = data.encode('utf-8')
    return _varint((num << 3) | 2) + _varint(len(data)) + data if data else b''


def _f_msg(num, data):
    return _varint((num << 3) | 2) + _varint(len(data)) + data


def _rv(buf, i):
    shift = result = 0
    while True:
        b = buf[i]
        i += 1
        result |= (b & 0x7F) << shift
        if not b & 0x80:
            return result, i
        shift += 7


def parse(buf):
    """{field: [value, …]}: ints for varint/fixed fields, bytes for length-delimited ones."""
    out, i, n = {}, 0, len(buf)
    while i < n:
        key, i = _rv(buf, i)
        num, wt = key >> 3, key & 7
        if wt == 0:
            v, i = _rv(buf, i)
        elif wt == 2:
            ln, i = _rv(buf, i)
            v = bytes(buf[i:i + ln])
            i += ln
        elif wt == 1:
            v = struct.unpack_from('<Q', buf, i)[0]
            i += 8
        elif wt == 5:
            v = struct.unpack_from('<I', buf, i)[0]
            i += 4
        else:
            raise ValueError(f'wire type {wt}')
        out.setdefault(num, []).append(v)
    return out


def _one(d, num, default=None):
    return d.get(num, [default])[0]


PNG, RGBA8888, RGB888 = 0, 1, 2
KEYDOWN, KEYUP, KEYPRESS = 0, 1, 2
USB, EVDEV, XKB, WIN, MAC = 0, 1, 2, 3, 4
SHUTDOWN, TERMINATE = 5, 7

# (message, field, number) used by this module; check_proto() compares them with a shipped proto.
FIELDS = (
    ('EmulatorStatus', 'version', 1), ('EmulatorStatus', 'uptime', 2), ('EmulatorStatus', 'booted', 3),
    ('EmulatorStatus', 'heartbeat', 6),
    ('ImageFormat', 'format', 1), ('ImageFormat', 'rotation', 2), ('ImageFormat', 'width', 3), ('ImageFormat', 'height', 4),
    ('ImageFormat', 'display', 5), ('ImageFormat', 'transport', 6),
    ('ImageTransport', 'channel', 1), ('ImageTransport', 'handle', 2), ('ImageTransport', 'MMAP', 1),
    ('Image', 'format', 1), ('Image', 'image', 4), ('Image', 'seq', 5), ('Image', 'timestampUs', 6),
    ('Touch', 'x', 1), ('Touch', 'y', 2), ('Touch', 'identifier', 3), ('Touch', 'pressure', 4),
    ('TouchEvent', 'touches', 1), ('TouchEvent', 'display', 2),
    ('KeyboardEvent', 'codeType', 1), ('KeyboardEvent', 'eventType', 2), ('KeyboardEvent', 'keyCode', 3),
    ('KeyboardEvent', 'key', 4), ('KeyboardEvent', 'text', 5), ('KeyboardEvent', 'Evdev', 1), ('KeyboardEvent', 'keypress', 2),
    ('VmRunState', 'state', 1), ('VmRunState', 'SHUTDOWN', 5),
    ('ImageFormat', 'PNG', 0), ('ImageFormat', 'RGB888', 2),
)
RPCS = ('getStatus', 'setVmState', 'getScreenshot', 'streamScreenshot', 'sendTouch', 'sendKey')


def check_proto(text):
    """Mismatches between FIELDS/RPCS and an ``emulator_controller.proto`` text; empty when this client fits it."""
    import re

    def block(name):
        m = re.search(r'\n\s*(message|enum) ' + name + r'\s*\{', text)
        if not m:
            return ''
        i, depth = m.end(), 1
        while depth and i < len(text):
            depth += {'{': 1, '}': -1}.get(text[i], 0)
            i += 1
        return text[m.start():i]

    problems = []
    for message, field, number in FIELDS:
        if not re.search(r'\b' + re.escape(field) + r'\s*=\s*' + str(number) + r'\s*[;\[]', block(message)):
            problems.append(f'{message}.{field} = {number}')
    for rpc in RPCS:
        if not re.search(r'\brpc\s+' + rpc + r'\s*\(', text):
            problems.append(f'rpc {rpc}')
    return problems


def mmap_handle(path):
    """The MMAP transport's handle: only a ``file:///`` path works on Windows (S3); forward slashes as the spike used."""
    return 'file:///' + str(path).replace('\\', '/').lstrip('/')


def image_format(fmt=RGB888, width=0, height=0, display=0, mmap_path=None):
    """Width and height together, or the emulator ignores the size (S3)."""
    body = _f_varint(1, fmt) + _f_varint(3, width) + _f_varint(4, height) + _f_varint(5, display)
    if mmap_path:
        body += _f_msg(6, _f_varint(1, 1) + _f_bytes(2, mmap_handle(mmap_path)))
    return body


def decode_image(buf):
    d = parse(buf)
    fmt = parse(_one(d, 1, b''))
    return {'format': _one(fmt, 1, 0), 'rotation': _one(fmt, 2, 0), 'width': _one(fmt, 3, 0), 'height': _one(fmt, 4, 0),
            'image': _one(d, 4, b''), 'seq': _one(d, 5, 0), 'timestamp_us': _one(d, 6, 0)}


def touch_event(touches, display=0):
    """touches: [(x, y, identifier, pressure)]; pressure 0 lifts that identifier."""
    body = b''
    for x, y, ident, pressure in touches:
        body += _f_msg(1, _f_varint(1, x) + _f_varint(2, y) + _f_varint(3, ident) + _f_varint(4, pressure))
    return body + _f_varint(2, display)


def keyboard_event(key=None, text=None, keycode=None, code_type=EVDEV, event_type=KEYPRESS):
    return (_f_varint(1, code_type) + _f_varint(2, event_type) + _f_varint(3, keycode or 0)
            + _f_bytes(4, key or '') + _f_bytes(5, text or ''))


def vm_run_state(state):
    return _f_varint(1, state)


def decode_status(buf):
    d = parse(buf)
    return {'version': _one(d, 1, b'').decode('utf-8', 'replace'), 'uptime_ms': _one(d, 2, 0), 'booted': bool(_one(d, 3, 0)),
            'heartbeat': _one(d, 6, 0)}


# -- the client ----------------------------------------------------------------------------------------------------------

class _Call:
    def __init__(self):
        self.buf = bytearray()
        self.messages = []
        self.status = None
        self.message = ''
        self.ended = False


class EmulatorGrpc:
    """``EmulatorGrpc(port, token)``: the gRPC port and token from the discovery file (§2.2). Use as a context manager."""

    def __init__(self, port, token, host='127.0.0.1', timeout=15.0):
        self.port, self.host, self.timeout = int(port), host, float(timeout)
        self._token = token
        self._lock = threading.RLock()
        self._sock = self._conn = None
        self._calls = {}
        self._h2 = _vendor()

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.close()

    def connect(self):
        h2 = self._h2
        try:
            self._sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        except OSError:
            raise GrpcError(14, 'The phone is not reachable.') from None
        self._sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        cfg = h2['config'].H2Configuration(client_side=True, header_encoding='utf-8')
        self._conn = h2['connection'].H2Connection(config=cfg)
        self._conn.initiate_connection()
        codes = h2['settings'].SettingCodes
        # Pending settings: h2 raises its inbound frame limit only once the peer acknowledges them (the spike's lesson).
        self._conn.update_settings({codes.INITIAL_WINDOW_SIZE: WINDOW, codes.MAX_FRAME_SIZE: (1 << 24) - 1, codes.ENABLE_PUSH: 0})
        self._conn.increment_flow_control_window(WINDOW - 65535)
        self._flush()
        return self

    def close(self):
        with self._lock:
            if self._conn is not None:
                try:
                    self._conn.close_connection()
                    self._flush()
                except Exception:  # health: allow BLE001 -- closing a broken connection
                    pass
            if self._sock is not None:
                try:
                    self._sock.close()
                except OSError:
                    pass
            self._sock = self._conn = None

    def cancel(self):
        """Breaks a call that sits in ``recv`` on another thread: a still screen's ``screenshots`` stream holds the lock for its
        whole life, so ``close()`` from another thread would wait for it. Safe from any thread; ``close()`` the client after."""
        sock = self._sock
        if sock is not None:
            try:
                sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    def _flush(self):
        data = self._conn.data_to_send()
        if data:
            try:
                self._sock.sendall(data)
            except OSError:
                raise GrpcError(14, 'The connection to the phone broke.') from None

    def _pump(self, deadline):
        """Reads once from the socket and dispatches the events; False on a timeout."""
        events = self._h2['events']
        self._sock.settimeout(max(0.05, deadline - time.monotonic()))
        try:
            data = self._sock.recv(1 << 20)
        except socket.timeout:
            return False
        except OSError:
            data = b''
        if not data:
            for call in self._calls.values():
                if not call.ended:
                    call.status, call.message, call.ended = 14, 'connection closed', True
            return True
        for ev in self._conn.receive_data(data):
            call = self._calls.get(getattr(ev, 'stream_id', None))
            if isinstance(ev, events.DataReceived):
                self._conn.acknowledge_received_data(ev.flow_controlled_length, ev.stream_id)
                if call is None:
                    continue
                call.buf += ev.data
                while len(call.buf) >= 5:
                    ln = struct.unpack_from('>I', call.buf, 1)[0]
                    if len(call.buf) < 5 + ln:
                        break
                    call.messages.append(bytes(call.buf[5:5 + ln]))
                    del call.buf[:5 + ln]
            elif isinstance(ev, (events.ResponseReceived, events.TrailersReceived)) and call is not None:
                headers = dict(ev.headers)
                if 'grpc-status' in headers:
                    call.status, call.message = int(headers['grpc-status']), headers.get('grpc-message', '')
                if headers.get(':status') not in (None, '200'):
                    call.status = 16 if headers[':status'] == '401' else 2
                    call.message = f"http {headers[':status']}"
            elif isinstance(ev, events.StreamEnded) and call is not None:
                call.ended = True
                if call.status is None:
                    call.status = 0
            elif isinstance(ev, events.StreamReset) and call is not None:
                call.ended = True
                if call.status is None:
                    call.status, call.message = 1, 'reset'
            elif isinstance(ev, events.ConnectionTerminated):
                for c in self._calls.values():
                    if not c.ended:
                        c.status, c.message, c.ended = 14, 'connection terminated', True
        self._flush()
        return True

    def _start(self, method, payload):
        if self._conn is None:
            self.connect()
        sid = self._conn.get_next_available_stream_id()
        headers = [(':method', 'POST'), (':scheme', 'http'), (':path', SERVICE + method), (':authority', f'localhost:{self.port}'),
                   ('content-type', 'application/grpc'), ('te', 'trailers')]
        if self._token:
            headers.append(('authorization', 'Bearer ' + self._token))
        call = self._calls[sid] = _Call()
        self._conn.send_headers(sid, headers)
        self._conn.send_data(sid, b'\x00' + struct.pack('>I', len(payload)) + payload, end_stream=True)
        self._flush()
        return sid, call

    def unary(self, method, payload=b'', timeout=None):
        with self._lock:
            sid, call = self._start(method, payload)
            deadline = time.monotonic() + (timeout or self.timeout)
            try:
                while not call.ended:
                    if time.monotonic() >= deadline:
                        raise GrpcError(4, 'The phone did not answer in time.')
                    self._pump(deadline)
            finally:
                self._calls.pop(sid, None)
            if call.status:
                raise GrpcError(call.status, call.message)
            return call.messages[0] if call.messages else b''

    def stream(self, method, payload=b''):
        """Yields each message of a server stream; holds this connection until the generator is closed."""
        with self._lock:
            sid, call = self._start(method, payload)
            try:
                while True:
                    while call.messages:
                        yield call.messages.pop(0)
                    if call.ended:
                        if call.status:
                            raise GrpcError(call.status, call.message)
                        return
                    self._pump(time.monotonic() + self.timeout)
            finally:
                if not call.ended and self._conn is not None:
                    try:
                        self._conn.reset_stream(sid)
                        self._flush()
                    except Exception:  # health: allow BLE001 -- the stream may already be gone
                        pass
                self._calls.pop(sid, None)

    # -- EmulatorController ----------------------------------------------------------------------------------------------
    def status(self, timeout=None):
        return decode_status(self.unary('getStatus', timeout=timeout))

    def screenshot(self, fmt=RGB888, width=0, height=0, display=0, timeout=30.0):
        return decode_image(self.unary('getScreenshot', image_format(fmt, width, height, display), timeout=timeout))

    def screenshots(self, fmt=RGB888, width=0, height=0, mmap_path=None):
        for raw in self.stream('streamScreenshot', image_format(fmt, width, height, mmap_path=mmap_path)):
            yield decode_image(raw)

    def touch(self, touches, display=0):
        self.unary('sendTouch', touch_event(touches, display))

    def tap(self, x, y, hold=0.05, ident=1):
        self.touch([(x, y, ident, 1024)])
        time.sleep(hold)
        self.touch([(x, y, ident, 0)])

    def key(self, key=None, text=None, keycode=None, code_type=EVDEV, event_type=KEYPRESS):
        self.unary('sendKey', keyboard_event(key, text, keycode, code_type, event_type))

    def shutdown(self):
        """``setVmState(SHUTDOWN)``: a clean stop that saves Quick Boot (S7). The call may never answer (the proto says so)."""
        try:
            self.unary('setVmState', vm_run_state(SHUTDOWN), timeout=5.0)
        except GrpcError as e:
            if e.status not in (4, 14):
                raise
