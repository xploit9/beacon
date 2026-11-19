"""The agent phone's gRPC client (agent_phone_grpc.py) against a fake EmulatorController on the vendored h2's server side.

Covers: the token on every call and a refusal without leaking it, the hand-encoded messages (the MMAP request carries width,
height and a file:/// handle), large frames through flow control, a stream closed early, a SHUTDOWN that never answers, the
proto contract against emulator 37.2.13's shipped proto, and the vendored h2 never reaching sys.path.
"""
import importlib.util
import socket
import struct
import sys
import threading
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('beacon_agent_phone_grpc_under_test', PLUGIN / 'agent_phone_grpc.py')
grpc = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(grpc)
PROTO = (PLUGIN / 'tests' / 'fixtures' / 'emulator_controller_37_2_13_excerpt.proto').read_text(encoding='utf-8')
TOKEN = 'test-token-' + 'x' * 30


class FakeEmulator:
    def __init__(self, frames=(), status=(b'37.2.13', 1234, True)):
        self.h2 = grpc._vendor()
        self.frames, self.status_fields = list(frames), status
        self.requests = []  # (method, payload)
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(4)
        self.port = self.listener.getsockname()[1]
        threading.Thread(target=self.serve, daemon=True).start()

    def close(self):
        self.listener.close()

    def serve(self):
        while True:
            try:
                sock, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.conn, args=(sock,), daemon=True).start()

    def conn(self, sock):
        h2, ev = self.h2, self.h2['events']
        conn = h2['connection'].H2Connection(config=h2['config'].H2Configuration(client_side=False, header_encoding='utf-8'))
        conn.initiate_connection()
        sock.sendall(conn.data_to_send())
        headers, bodies = {}, {}
        self.sock, self.conn_ = sock, conn
        try:
            while True:
                data = sock.recv(1 << 20)
                if not data:
                    return
                for e in conn.receive_data(data):
                    if isinstance(e, ev.RequestReceived):
                        headers[e.stream_id] = dict(e.headers)
                        bodies[e.stream_id] = b''
                    elif isinstance(e, ev.DataReceived):
                        bodies[e.stream_id] += e.data
                        conn.acknowledge_received_data(e.flow_controlled_length, e.stream_id)
                    elif isinstance(e, ev.StreamEnded):
                        if self.answer(conn, sock, e.stream_id, headers[e.stream_id], bodies[e.stream_id]) == 'drop':
                            sock.close()
                            return
                sock.sendall(conn.data_to_send())
        except OSError:
            return

    def _send(self, conn, sock, sid, payload):
        ev = self.h2['events']
        data = b'\x00' + struct.pack('>I', len(payload)) + payload
        while data:
            room = min(conn.local_flow_control_window(sid), conn.max_outbound_frame_size, len(data))
            if room <= 0:
                sock.sendall(conn.data_to_send())
                for e in conn.receive_data(sock.recv(1 << 16)):
                    if isinstance(e, ev.DataReceived):
                        conn.acknowledge_received_data(e.flow_controlled_length, e.stream_id)
                continue
            conn.send_data(sid, data[:room])
            data = data[room:]
            sock.sendall(conn.data_to_send())

    def answer(self, conn, sock, sid, headers, body):
        method = headers[':path'].rsplit('/', 1)[1]
        payload = body[5:]
        self.requests.append((method, payload, headers.get('authorization')))
        conn.send_headers(sid, [(':status', '200'), ('content-type', 'application/grpc')])
        if headers.get('authorization') != 'Bearer ' + TOKEN:
            conn.send_headers(sid, [('grpc-status', '16'), ('grpc-message', 'unauthenticated')], end_stream=True)
            return None
        if method == 'setVmState':
            return 'drop'  # the emulator goes away without answering (the proto allows it)
        if method == 'getStatus':
            v, up, booted = self.status_fields
            msg = grpc._f_bytes(1, v) + grpc._f_varint(2, up) + grpc._f_varint(3, int(booted)) + grpc._f_varint(6, 7)
            self._send(conn, sock, sid, msg)
        elif method in ('getScreenshot', 'streamScreenshot'):
            fmt = grpc.parse(payload)
            for i, image in enumerate(self.frames or [b'P' * 10]):
                f = grpc._f_varint(1, grpc._one(fmt, 1, 0)) + grpc._f_varint(3, grpc._one(fmt, 3, 0)) + grpc._f_varint(4, grpc._one(fmt, 4, 0))
                self._send(conn, sock, sid, grpc._f_msg(1, f) + grpc._f_bytes(4, image) + grpc._f_varint(5, i + 1))
                if method == 'getScreenshot':
                    break
        conn.send_headers(sid, [('grpc-status', '0')], end_stream=True)
        sock.sendall(conn.data_to_send())
        return None


@pytest.fixture
def emu():
    e = FakeEmulator()
    yield e
    e.close()


def test_status_carries_the_token_and_decodes(emu):
    with grpc.EmulatorGrpc(emu.port, TOKEN) as g:
        st = g.status()
    assert st == {'version': '37.2.13', 'uptime_ms': 1234, 'booted': True, 'heartbeat': 7}
    assert emu.requests[0][0] == 'getStatus' and emu.requests[0][2] == 'Bearer ' + TOKEN


def test_a_wrong_token_is_status_16_and_never_echoed(emu):
    with grpc.EmulatorGrpc(emu.port, 'wrong-' + 'y' * 30) as g:
        with pytest.raises(grpc.GrpcError) as err:
            g.status()
    assert err.value.status == 16 and 'y' * 30 not in str(err.value) and TOKEN not in str(err.value)


def test_the_mmap_request_sets_width_height_and_a_file_handle(emu, tmp_path):
    frame = tmp_path / 'run' / 'frames-1.bin'
    with grpc.EmulatorGrpc(emu.port, TOKEN) as g:
        first = next(g.screenshots(grpc.RGB888, 720, 1600, mmap_path=frame))
    assert (first['width'], first['height'], first['seq']) == (720, 1600, 1)
    sent = grpc.parse(emu.requests[-1][1])
    assert grpc._one(sent, 1) == grpc.RGB888 and grpc._one(sent, 3) == 720 and grpc._one(sent, 4) == 1600
    transport = grpc.parse(grpc._one(sent, 6))
    assert grpc._one(transport, 1) == 1  # MMAP
    handle = grpc._one(transport, 2).decode()
    assert handle.startswith('file:///') and '\\' not in handle and handle.endswith('run/frames-1.bin')


def test_large_frames_cross_flow_control_and_a_stream_can_stop_early(tmp_path):
    big = [bytes([i]) * (3 * 1024 * 1024) for i in range(4)]  # 12 MB, far past h2's 64 KB default window
    emu = FakeEmulator(frames=big)
    try:
        with grpc.EmulatorGrpc(emu.port, TOKEN) as g:
            got = []
            for image in g.screenshots(grpc.RGB888, 1080, 2400):
                got.append(image['image'])
                if len(got) == 3:
                    break  # the viewer left: the generator resets the stream
            assert got == big[:3]
            assert g.status()['booted'] is True  # the connection is still good after the reset
    finally:
        emu.close()


def test_shutdown_tolerates_an_emulator_that_never_answers(emu):
    with grpc.EmulatorGrpc(emu.port, TOKEN, timeout=5) as g:
        g.shutdown()
    assert emu.requests[-1][0] == 'setVmState' and grpc.parse(emu.requests[-1][1]) == {1: [grpc.SHUTDOWN]}


def test_touch_and_key_messages():
    assert grpc.parse(grpc.touch_event([(10, 20, 1, 1024)])) == {1: [grpc._f_varint(1, 10) + grpc._f_varint(2, 20)
                                                                     + grpc._f_varint(3, 1) + grpc._f_varint(4, 1024)]}
    k = grpc.parse(grpc.keyboard_event(key='Enter'))
    assert k[1] == [grpc.EVDEV] and k[2] == [grpc.KEYPRESS] and k[4] == [b'Enter']


def test_an_unreachable_phone_is_status_14():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    with pytest.raises(grpc.GrpcError) as err:
        grpc.EmulatorGrpc(port, TOKEN, timeout=1).connect()
    assert err.value.status == 14


def test_the_client_fits_emulator_37_2_13s_proto_and_notices_a_change():
    assert grpc.check_proto(PROTO) == []
    changed = PROTO.replace('bytes image = 4;', 'bytes image = 9;')
    assert grpc.check_proto(changed) == ['Image.image = 4']
    assert 'rpc sendKey' in grpc.check_proto(PROTO.replace('rpc sendKey(', 'rpc sendKeys('))


def test_vendored_h2_is_private():
    grpc._vendor()
    assert 'h2' not in sys.modules or not str(getattr(sys.modules['h2'], '__file__', '')).startswith(str(PLUGIN))
    assert not any(str(PLUGIN / 'vendor') in p for p in sys.path)
    assert 'beacon_agent_phone_vendor.h2.connection' in sys.modules
