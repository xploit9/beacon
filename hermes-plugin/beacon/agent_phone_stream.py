"""The agent phone's setup stream, protocol ``beacon-agent-phone-v1`` (EMU-DESIGN §4.2-§4.5).

Frames (S3): the emulator draws 720x1600 frames into one owner-only file in the phone's ``tmp/AndroidEmulator/`` through gRPC
``streamScreenshot`` with the MMAP transport (only a ``file:///`` handle works on Windows); the server encodes the newest one as JPEG
q70 in memory and sends it as a binary ``0x01`` message. One frame is in flight: the next goes only after the viewer's ``ack``, and
frames in between are dropped to the newest. Sends are at least ``1 / MAX_FPS`` apart (a strict minimum interval, measured from send
to send, so the first frame after a still screen is never held back). The file is deleted when the view closes. A keeper without
confirmed mapped-file cleanup uses authenticated inline RGB for the whole capture.

Input (S4, S5): touches and keys go to gRPC through one FIFO worker thread, so nothing interleaves; touch moves are coalesced (S4 saw
the SystemUI ANR under fast touch streams). Text is routed per message, never per character: a message of printable ASCII is typed as
paced key events; any other message, and every paste, goes whole through the Beacon helper over L9's channel: ``clip_set`` (the clip
marked sensitive by the helper), Ctrl+V through gRPC, then ``clip_clear``. gRPC ``text`` and ``setClipboard`` are never used.

Nothing is recorded: no frame bytes, typed text or pasted text reach a log, a file or an error; logs carry counts and reason words.

The module imports nothing of L1b's or L9's: the API hands it factories (``open_frames``, ``open_control``, ``open_channel``,
``keyboard_probe``), so it runs, and is tested, with fakes.

Binary frame message (server -> app), big-endian:
    u8 type = 0x01 | u32 seq | u16 width | u16 height | u8 rotation (quarter turns, 0-3) | JPEG bytes
Text messages are JSON objects with a ``t`` field; see ``parse_client`` (app -> server) and ``ViewSession`` (server -> app).
"""
import asyncio
import collections
import contextlib
import io
import json
import logging
import math
import mmap
import os
import secrets
import struct
import threading
import time
from pathlib import Path

SUBPROTOCOL = 'beacon-agent-phone-v1'
FRAME_WIDTH, FRAME_HEIGHT = 720, 1600  # both, or the emulator ignores the size (S3)
DEVICE_SIZE = (1080, 2400)  # the phone's screen (§2.4); touches are sent in its pixels
RGB888 = 2  # ImageFormat.RGB888, emulator_controller.proto
QUALITY = {'normal': 70, 'low': 50}
MAX_FPS = 14.0  # §4.2: the computed cap that keeps a scroll's peak <= 12 Mbps; gate B6 measures it
ACK_WAIT_SECONDS = 5.0
PRECISE_SECONDS = 0.02  # the last part of a cap's wait is slept on a thread (see ViewSession._wait_until)  # a viewer that never acks does not freeze the view for good
FRAME = struct.Struct('>BIHHB')
FRAME_TYPE = 0x01
TEXT_MAX, PASTE_MAX = 500, 10000
MESSAGE_MAX_BYTES = 128 * 1024  # a 10 000-character paste as escaped JSON (\ud83d\ude00 = 12 bytes)
KEY_PACE_SECONDS = 0.04  # paced per-character keys (S5 proved per-character keys; S4 saw ANRs under fast input)
CLIP_TTL_MS = 10000
CLIP_CLEAR_SECONDS = 0.5  # the app has read the clipboard by then; the helper's own ttl clears it anyway
HELPER_WAIT_SECONDS = 8.0
REVALIDATE_SECONDS = 5.0
LEASE_CHECK_SECONDS = 1.0
ACTIVITY_NOTE_SECONDS = 5.0  # every setup input notes the keeper's activity (L1b's note_activity), written at most this often
KEYBOARD_PROBE_DELAY = 0.6
KEYBOARD_PROBE_GAP = 1.0
GUARD_SECONDS = 1.0  # the debugging-prompt check while a view is open (Root, 17 Nov) ...
GUARD_AFTER_INPUT = 0.6  # ... and this long after each input
QUEUE_MAX = 1024  # above the largest compliant burst after a stall (touch: 240 + 240 x CATCH_UP_SECONDS)
PRINTABLE = frozenset(chr(c) for c in range(0x20, 0x7F))
KEYS = {'back': 'GoBack', 'home': 'GoHome', 'recents': 'AppSwitch', 'enter': 'Enter', 'backspace': 'Backspace', 'tab': 'Tab',
        'left': 'ArrowLeft', 'right': 'ArrowRight', 'up': 'ArrowUp', 'down': 'ArrowDown'}
KEYDOWN, KEYUP, KEYPRESS = 0, 1, 2
PHASES = ('down', 'move', 'up')
# close codes (RFC 6455): 1000 done, 1008 policy (bad message, rate, refused binding), 1011 the phone failed, 1013 try again
CLOSE_DONE, CLOSE_POLICY, CLOSE_FAILED, CLOSE_AGAIN = 1000, 1008, 1011, 1013
BYE_CODES = {'done': CLOSE_DONE, 'replaced': CLOSE_DONE, 'ended': CLOSE_DONE, 'stopped': CLOSE_DONE, 'removed': CLOSE_POLICY,
             'update': CLOSE_AGAIN, 'error': CLOSE_FAILED}
# per message class: (tokens per second, burst), each at or above the app's own bucket (ViewSocket.kt `Lane`: touch 200/s burst
# 200, key 16/s 16, text 16/s 32, paste 1.5/s 3, ack 50/s 50, control 8/s 8)
# text: the app may send each keystroke of a fast typist as its own message; paste: each one is a helper round trip
RATES = {'touch': (240.0, 240), 'key': (20.0, 20), 'text': (20.0, 40), 'paste': (2.0, 4), 'ack': (60.0, 60), 'control': (10.0, 10)}
# A mobile stall (1.5-2 s measured by L5) holds back what the phone paced correctly and then delivers it at once. Each bucket
# can therefore save up this many seconds of its rate on top of its burst while the phone sends less than that. A real flood
# still ends at burst + rate x CATCH_UP_SECONDS (the hard ceiling) and closes 1008.
CATCH_UP_SECONDS = 2.5
CLASS = {'touch': 'touch', 'key': 'key', 'text': 'text', 'paste': 'paste', 'ack': 'ack', 'quality': 'control', 'refresh': 'control',
         'background': 'control', 'foreground': 'control', 'done': 'control'}

log = logging.getLogger('beacon.agent_phone.stream')


class ProtocolError(Exception):
    """The app broke the protocol or its limits: the socket closes 1008. The text is a fixed word, never message content."""


# -- pure pieces -----------------------------------------------------------------------------------------------------------

def frame_message(seq, width, height, rotation, jpeg):
    return FRAME.pack(FRAME_TYPE, seq & 0xFFFFFFFF, width, height, rotation & 3) + jpeg


def read_frame_message(data):
    """(seq, width, height, rotation, jpeg) of a 0x01 message (the viewer's side, used by tests and the B6 viewer)."""
    kind, seq, width, height, rotation = FRAME.unpack_from(data)
    if kind != FRAME_TYPE:
        raise ValueError('not a frame')
    return seq, width, height, rotation, bytes(data[FRAME.size:])


def rotation_of(value):
    """The quarter turns in an Image's ``format.rotation``: a ``Rotation`` message (field 1, SkinRotation) as bytes, or an int."""
    if isinstance(value, int):
        return value & 3
    data = bytes(value or b'')
    i = 0
    while i < len(data):
        key = data[i]
        i += 1
        if key == 0x08:  # field 1, varint
            n = shift = 0
            while i < len(data):
                b = data[i]
                i += 1
                n |= (b & 0x7F) << shift
                shift += 7
                if not b & 0x80:
                    break
            return n & 3
        wire = key & 7
        if wire == 1:
            i += 8
        elif wire == 5:
            i += 4
        elif wire == 0:
            while i < len(data) and data[i] & 0x80:
                i += 1
            i += 1
        else:
            return 0
    return 0


def is_printable_ascii(text):
    return bool(text) and all(c in PRINTABLE for c in text)


def route_text(text):
    """'keys' for a message of printable ASCII (typed as paced keys), else 'helper' (the whole message through the channel)."""
    return 'keys' if is_printable_ascii(text) else 'helper'


def to_device(x, y, rotation=0, size=DEVICE_SIZE):
    """A point given as 0-1 of the frame as shown into the phone's own pixels (its natural portrait orientation).

    Rotation 0 is the measured case (S3, S5). Rotations 1-3 follow the frame being the natural screen turned by that many quarter
    turns; not measured on a phone yet."""
    w, h = size
    x, y = min(max(float(x), 0.0), 1.0), min(max(float(y), 0.0), 1.0)
    rotation &= 3
    if rotation == 1:
        x, y = 1.0 - y, x
    elif rotation == 2:
        x, y = 1.0 - x, 1.0 - y
    elif rotation == 3:
        x, y = y, 1.0 - x
    return min(w - 1, max(0, round(x * w))), min(h - 1, max(0, round(y * h)))


def _no_duplicates(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise ProtocolError('message')
        out[key] = value
    return out


def _unit(value):
    return type(value) in (int, float) and math.isfinite(value) and 0.0 <= value <= 1.0


def _text(value, limit):
    return isinstance(value, str) and 1 <= len(value) <= limit and '\x00' not in value


FIELDS = {'ack': {'seq'}, 'touch': {'points'}, 'key': {'key'}, 'text': {'text'}, 'paste': {'text'}, 'quality': {'level'},
          'refresh': set(), 'background': set(), 'foreground': set(), 'done': set()}


def parse_client(raw):
    """One app -> server message, checked exactly; ProtocolError for anything else."""
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > MESSAGE_MAX_BYTES:
        raise ProtocolError('message')
    try:
        message = json.loads(raw, object_pairs_hook=_no_duplicates, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, RecursionError):
        raise ProtocolError('message') from None
    if not isinstance(message, dict) or message.get('t') not in FIELDS or set(message) != FIELDS[message['t']] | {'t'}:
        raise ProtocolError('message')
    t = message['t']
    if t == 'ack' and not (type(message['seq']) is int and 0 <= message['seq'] <= 0xFFFFFFFF):
        raise ProtocolError('message')
    if t == 'touch':
        points = message['points']
        if not isinstance(points, list) or not 1 <= len(points) <= 10:
            raise ProtocolError('message')
        ids = set()
        for p in points:
            if (not isinstance(p, dict) or set(p) != {'id', 'x', 'y', 'phase'} or type(p['id']) is not int or not 0 <= p['id'] <= 9
                    or p['id'] in ids or not _unit(p['x']) or not _unit(p['y']) or p['phase'] not in PHASES):
                raise ProtocolError('message')
            ids.add(p['id'])
    if t == 'key' and message['key'] not in KEYS:
        raise ProtocolError('message')
    if t == 'text' and not _text(message['text'], TEXT_MAX):
        raise ProtocolError('message')
    if t == 'paste' and not _text(message['text'], PASTE_MAX):
        raise ProtocolError('message')
    if t == 'quality' and message['level'] not in QUALITY:
        raise ProtocolError('message')
    return message


class TokenBucket:
    def __init__(self, rate, burst, clock=time.monotonic, catch_up=CATCH_UP_SECONDS):
        self.rate, self.burst, self.clock = rate, burst, clock
        self.capacity = burst + rate * catch_up  # the hard ceiling: the most a burst after a stall may hold
        self.tokens, self.at = float(self.capacity), clock()

    def take(self):
        now = self.clock()
        self.tokens = min(self.capacity, self.tokens + (now - self.at) * self.rate)
        self.at = now
        if self.tokens < 1.0:
            return False
        self.tokens -= 1.0
        return True


class Limits:
    def __init__(self, clock=time.monotonic, rates=None):
        self.buckets = {name: TokenBucket(rate, burst, clock) for name, (rate, burst) in (rates or RATES).items()}

    def admit(self, t):
        if not self.buckets[CLASS[t]].take():
            raise ProtocolError('rate')


def encode_jpeg(pixels, width, height, quality):
    """RGB888 pixels -> JPEG bytes, in memory only (Pillow is a Hermes core dependency)."""
    from PIL import Image

    image = Image.frombuffer('RGB', (width, height), pixels, 'raw', 'RGB', 0, 1)
    out = io.BytesIO()
    image.save(out, 'JPEG', quality=quality, optimize=False)
    return out.getvalue()


_warmed = threading.Event()


def warm():
    """Pillow's JPEG encoder costs ~1.5 s on its first use in a process (measured on the B6 phone's host); the API runs this when it
    issues a view ticket, so the first frame of the first view after a dashboard start is not late."""
    if not _warmed.is_set():
        encode_jpeg(bytes(3 * 64), 8, 8, QUALITY['normal'])
        _warmed.set()


# -- frames through the MMAP transport ------------------------------------------------------------------------------------

class Latest:
    __slots__ = ('seq', 'width', 'height', 'rotation', 'image')

    def __init__(self, seq, width, height, rotation, image=b''):
        self.seq, self.width, self.height, self.rotation = seq, width, height, rotation
        self.image = image


def _abort(client):
    """Breaks a gRPC stream that sits in ``recv`` on another thread (L1b's client holds its lock for the stream's life)."""
    client.cancel()


def sweep_frame_files(run_dir):
    """Deletes leftover live-view files (a crash's, §4.5). A file still mapped by a live view can't be deleted on Windows and is
    left to it; on POSIX the live view keeps its mapping."""
    removed = 0
    for path in Path(run_dir).glob('frames-*.bin'):
        try:
            path.unlink()
            removed += 1
        except OSError:
            pass
    return removed


def frame_directory_for_view(folder):
    """Use mapped pixels only when the registered keeper's live worker can clean them without the plugin."""
    try:
        import psutil

        proof = json.loads((folder.run / 'mmap-cleaner-v1.json').read_text(encoding='utf-8'))
        if Path(proof['folder']) != folder.root.resolve():
            return None
        worker = psutil.Process(proof['worker'][0])
        if proof['worker'] != [worker.pid, worker.create_time()] or worker.status() in (psutil.STATUS_ZOMBIE, psutil.STATUS_DEAD):
            return None
        launcher = worker
        if worker.pid != proof['launcher'][0]:
            launcher = worker.parent() if os.name == 'nt' else None
        if launcher and [launcher.pid, launcher.create_time()] == proof['launcher']:
            conf = json.loads(folder.keeper_conf.read_text(encoding='utf-8'))
            if [conf['pid'], conf['create_time']] == proof['launcher']:
                return folder.frames
    except Exception:  # health: allow BLE001 -- missing, unreadable or superseded identity selects inline pixels
        pass
    return None


class MmapFrames:
    """The emulator's frames for one view. ``open_client()`` returns a connected gRPC client of its own (L1b's EmulatorGrpc), used
    only for this stream. A supplied directory enables an owner-only MMAP file; None requests inline RGB without creating a file.
    ``latest()`` names the newest frame; ``pixels(latest)`` copies it out; ``stop()`` ends the stream and deletes the file."""

    def __init__(self, open_client, run_dir, tag, width=FRAME_WIDTH, height=FRAME_HEIGHT, on_frame=None):
        self.open_client, self.run_dir, self.tag = open_client, Path(run_dir) if run_dir is not None else None, str(tag)
        self.width, self.height = width, height
        self.on_frame = on_frame
        self.path = None
        self.failed = None
        self._mm = self._fd = self._client = self._thread = None
        self._latest = None
        self._lock = threading.Lock()
        self._stopping = threading.Event()
        self.frames = 0

    def start(self):
        if self.run_dir is not None:
            self.run_dir.mkdir(mode=0o700, parents=True, exist_ok=True)
            self.path = self.run_dir / f'frames-{self.tag}-{secrets.token_hex(4)}.bin'
            size = self.width * self.height * 4  # room for RGBA, as the spike sized it (S3)
            flags = os.O_RDWR | os.O_CREAT | os.O_EXCL | getattr(os, 'O_BINARY', 0)
            self._fd = os.open(self.path, flags, 0o600)
            try:
                os.ftruncate(self._fd, size)
                self._mm = mmap.mmap(self._fd, size)
            except BaseException:
                self._release_file()
                raise
        self._thread = threading.Thread(target=self._read, name='beacon-agent-phone-frames', daemon=True)
        self._thread.start()
        return self

    def _images(self, client):
        args = dict(width=self.width, height=self.height)
        if self.path is not None:
            try:
                yield from client.screenshots(RGB888, **args, mmap_path=self.path)
                return
            except Exception as exc:
                status = getattr(exc, 'status', None)
                if self.frames or self._stopping.is_set() or status not in (3, 9):
                    raise
                log.info('agent phone stream: mapped capture refused (grpc=%d), trying inline pixels', status)
        for image in client.screenshots(RGB888, **args):
            if image.get('width') and image.get('height') and len(image.get('image', b'')) != self._pixel_size(image['width'], image['height']):
                raise ValueError('Invalid inline frame')
            yield image

    def _pixel_size(self, width, height):
        if type(width) is not int or type(height) is not int or not (0 < width <= 65535 and 0 < height <= 65535):
            raise ValueError('Invalid frame size')
        size = width * height * 3
        if size > self.width * self.height * 4:
            raise ValueError('Invalid frame size')
        return size

    def _read(self):
        client = None
        try:
            client = self._client = self.open_client()
            if self._stopping.is_set():
                return
            for image in self._images(client):
                if self._stopping.is_set():
                    break
                if not image.get('width') or not image.get('height'):
                    continue
                self._pixel_size(image['width'], image['height'])
                with self._lock:
                    self.frames += 1
                    self._latest = Latest(self.frames, int(image['width']), int(image['height']), rotation_of(image.get('rotation')), image.get('image', b''))
                if self.on_frame is not None:
                    self.on_frame()
        except Exception as exc:  # health: allow BLE001 -- the view reports "frames stopped"; nothing of the error is shown
            if not self._stopping.is_set():
                self.failed = type(exc).__name__
                status = getattr(exc, 'status', None)
                log.info('agent phone stream: frames stopped (%s, grpc=%s)', self.failed, status if type(status) is int else '-')
                if self.on_frame is not None:
                    self.on_frame()
        finally:
            if client is not None:
                with contextlib.suppress(Exception):  # health: allow BLE001 -- closing a broken connection
                    client.close()

    def latest(self):
        with self._lock:
            return self._latest

    def pixels(self, latest):
        n = self._pixel_size(latest.width, latest.height)
        if latest.image:
            if len(latest.image) != n:
                raise ValueError('Invalid inline frame')
            return latest.image
        if self._mm is None or n > len(self._mm):
            raise ValueError('frame larger than the map')
        return self._mm[:n]

    def _release_file(self):
        if self._mm is not None:
            with contextlib.suppress(Exception):  # health: allow BLE001 -- already closed
                self._mm.close()
            self._mm = None
        if self._fd is not None:
            with contextlib.suppress(OSError):
                os.close(self._fd)
            self._fd = None

    def stop(self, wait=5.0, delete_seconds=3.0):
        """Ends the stream, closes the mapping and deletes the file; True when the file is gone."""
        self._stopping.set()
        if self._client is not None:
            _abort(self._client)
        if self._thread is not None:
            self._thread.join(wait)
        self._release_file()
        if self.path is None:
            return True
        deadline = time.monotonic() + delete_seconds
        while True:
            try:
                self.path.unlink()
                return True
            except FileNotFoundError:
                return True
            except OSError:  # Windows: the emulator may still map it for a moment after the stream ended
                if time.monotonic() >= deadline:
                    log.info('agent phone stream: frame file still in use; the next start removes it')
                    return False
                time.sleep(0.05)


# -- input ---------------------------------------------------------------------------------------------------------------

class InputWorker:
    """One thread runs every input in order against the phone's gRPC control client (its own connection, not the stream's).

    ``open_control()`` returns a connected client; ``helper(wait)`` returns the linked channel session or None; ``refused(what,
    why)`` tells the view that a text or paste could not be delivered (called on this thread)."""

    def __init__(self, open_control, helper=None, refused=None, size=DEVICE_SIZE, pace=KEY_PACE_SECONDS, sleep=time.sleep):
        self.open_control, self.helper, self.refused = open_control, helper, refused
        self.size, self.pace, self.sleep = size, pace, sleep
        self._ops = collections.deque()
        self._cond = threading.Condition()
        self._closed = False
        self._client = None
        self._thread = threading.Thread(target=self._run, name='beacon-agent-phone-input', daemon=True)
        self.done = 0

    def start(self):
        self._thread.start()
        return self

    def put(self, op):
        """Queues an op; a touch move replaces a queued move of the same pointers (coalescing). False when the queue is full."""
        with self._cond:
            if self._closed:
                return True
            if (op[0] == 'touch' and _all_moves(op[1]) and self._ops and self._ops[-1][0] == 'touch'
                    and _all_moves(self._ops[-1][1]) and _ids(self._ops[-1][1]) == _ids(op[1])):
                self._ops[-1] = op
                return True
            if len(self._ops) >= QUEUE_MAX:
                return False
            self._ops.append(op)
            self._cond.notify()
            return True

    def pending(self):
        with self._cond:
            return len(self._ops)

    def stop(self, wait=5.0):
        with self._cond:
            self._closed = True
            self._ops.clear()
            self._cond.notify()
        if self._thread.is_alive() and threading.current_thread() is not self._thread:
            self._thread.join(wait)
        if self._client is not None:
            with contextlib.suppress(Exception):  # health: allow BLE001 -- closing a broken connection
                self._client.close()
            self._client = None

    def _run(self):
        while True:
            with self._cond:
                while not self._ops and not self._closed:
                    self._cond.wait()
                if self._closed:
                    return
                op = self._ops.popleft()
            try:
                self._do(op)
            except Exception as exc:  # health: allow BLE001 -- one failed input never ends the view; reconnect for the next
                log.info('agent phone stream: an input failed (%s)', type(exc).__name__)
                client, self._client = self._client, None
                if client is not None:
                    with contextlib.suppress(Exception):  # health: allow BLE001 -- closing a broken connection
                        client.close()
            self.done += 1

    def _control(self):
        if self._client is None:
            self._client = self.open_control()
        return self._client

    def _do(self, op):
        kind = op[0]
        if kind == 'touch':
            rotation = op[2] if len(op) > 2 else 0
            touches = []
            for p in op[1]:
                x, y = to_device(p['x'], p['y'], rotation, self.size)
                touches.append((x, y, p['id'], 0 if p['phase'] == 'up' else 1024))
            self._control().touch(touches)
        elif kind == 'key':
            self._control().key(key=KEYS[op[1]])
        elif kind == 'keys':
            control = self._control()
            for i, ch in enumerate(op[1]):
                if self._closed:
                    return
                if i:
                    self.sleep(self.pace)
                control.key(key=ch)
        elif kind == 'helper':
            self._through_helper(op[1], op[2])

    def _through_helper(self, text, what):
        session = self.helper(HELPER_WAIT_SECONDS) if self.helper else None
        if session is None:
            self._refuse(what, 'helper')
            return
        placed = False
        try:
            reply = session.request('clip_set', text=text, ttl_ms=CLIP_TTL_MS)
            if reply.get('t') != 'ack':
                self._refuse(what, 'clipboard')
                return
            placed = True
            control = self._control()
            control.key(key='Control', event_type=KEYDOWN)
            control.key(key='v', event_type=KEYPRESS)
            control.key(key='Control', event_type=KEYUP)
            self.sleep(CLIP_CLEAR_SECONDS)
        except Exception as exc:  # health: allow BLE001 -- a broken link or control call refuses this message only
            log.info('agent phone stream: text through the helper failed (%s)', type(exc).__name__)
            self._refuse(what, 'helper')
        finally:
            if placed:
                with contextlib.suppress(Exception):  # health: allow BLE001 -- the helper's own ttl clears it then
                    session.request('clip_clear')

    def _refuse(self, what, why):
        if self.refused is not None:
            self.refused(what, why)


def _all_moves(points):
    return all(p['phase'] == 'move' for p in points)


def _ids(points):
    return tuple(sorted(p['id'] for p in points))


# -- the view session ----------------------------------------------------------------------------------------------------

class _NoFrames:
    """Stands in while the frames are paused."""
    failed = None

    def latest(self):
        return None

    def pixels(self, latest):
        raise ValueError('paused')

    def stop(self):
        return True


class Probes:
    """The view's one thread for checks that run a command on the phone, so they never overlap or pile up:
    - Android's "Allow USB debugging?" / wireless debugging prompt (Root's rule, 17 Nov): every ``GUARD_SECONDS`` and
      ``GUARD_AFTER_INPUT`` after each input (``guard()`` -> 'usb' | 'wifi' | None, results to ``on_guard``);
    - the keyboard: ``KEYBOARD_PROBE_DELAY`` after a tap or a key, at most every ``KEYBOARD_PROBE_GAP`` (results to ``on_keyboard``).
    A check that raises is skipped; the next one runs on time."""

    def __init__(self, keyboard=None, on_keyboard=None, guard=None, on_guard=None, clock=time.monotonic):
        self.keyboard, self.on_keyboard, self.guard, self.on_guard, self.clock = keyboard, on_keyboard, guard, on_guard, clock
        self._cond = threading.Condition()
        self._stopping = False
        self._keyboard_due = self._keyboard_last = self._guard_due = None
        self._thread = None
        self.guard_runs = self.keyboard_runs = 0

    def start(self):
        if self.keyboard is None and self.guard is None:
            return self
        if self.guard is not None:
            self._guard_due = self.clock() + GUARD_SECONDS
        self._thread = threading.Thread(target=self._run, name='beacon-agent-phone-probes', daemon=True)
        self._thread.start()
        return self

    def keyboard_wanted(self):
        if self.keyboard is None:
            return
        with self._cond:
            due = self.clock() + KEYBOARD_PROBE_DELAY
            if self._keyboard_last is not None:
                due = max(due, self._keyboard_last + KEYBOARD_PROBE_GAP)
            if self._keyboard_due is None:
                self._keyboard_due = due
                self._cond.notify()

    def input_seen(self):
        if self.guard is None:
            return
        with self._cond:
            due = self.clock() + GUARD_AFTER_INPUT
            if self._guard_due is None or due < self._guard_due:
                self._guard_due = due
                self._cond.notify()

    def stop(self, wait=3.0):
        with self._cond:
            self._stopping = True
            self._cond.notify()
        if self._thread is not None and threading.current_thread() is not self._thread:
            self._thread.join(wait)

    def _run(self):
        while True:
            with self._cond:
                while not self._stopping:
                    dues = [d for d in (self._keyboard_due, self._guard_due) if d is not None]
                    wait = min(dues) - self.clock() if dues else None
                    if wait is not None and wait <= 0:
                        break
                    self._cond.wait(wait)
                if self._stopping:
                    return
                now = self.clock()
                run_guard = self._guard_due is not None and self._guard_due <= now
                run_keyboard = self._keyboard_due is not None and self._keyboard_due <= now
                if run_guard:
                    self._guard_due = now + GUARD_SECONDS
                if run_keyboard:
                    self._keyboard_due, self._keyboard_last = None, now
            if run_guard:
                self.guard_runs += 1
                self._call(self.guard, self.on_guard)
            if run_keyboard:
                self.keyboard_runs += 1
                self._call(self.keyboard, self.on_keyboard)

    @staticmethod
    def _call(check, deliver):
        try:
            found = check()
        except Exception as exc:  # health: allow BLE001 -- a check that failed this time; the next one runs on time
            log.info('agent phone stream: a phone check failed (%s)', type(exc).__name__)
            return
        if deliver is not None:
            deliver(found)


class ViewSession:
    """One open setup view: frames out, input in, the lease held, the helper channel open for its whole length (§4.3).

    ``socket``: ``send_bytes``, ``send_text``, ``receive`` (ASGI message dicts) and ``close(code)``, as Starlette's WebSocket.
    ``lease``: agent_phone_lease.PhoneLease. ``viewer``: the lease's viewer id. ``open_frames(on_frame)`` returns an unstarted
    frame source (``MmapFrames``). ``open_control()`` returns a connected gRPC client. ``open_channel()`` returns a linked channel
    session or None (it runs on its own thread). ``keyboard_probe()`` returns True/False/None. ``revalidate()`` raises when the
    ticket's binding no longer holds (device removed, owner or profile changed). ``note_activity()`` keeps the keeper from
    stopping the phone (L1b). ``phone_running()`` False ends the view with ``stopped``, and so does ``phone_stopping()`` True: a
    stop under way ends the view as stopped, not as the error its frames run into first (L5 live, Root msg_53a63d3021ad). ``debug_prompt()`` returns 'usb' |
    'wifi' | None and ``deny_prompt()`` answers it No (L3's agent_phone_guard); while one shows, the state says ``prompt:
    usb_debugging`` and every input but Back is dropped.
    """

    def __init__(self, socket, lease, viewer, *, epoch, watching=False, open_frames, open_control, open_channel=None, keyboard_probe=None,
                 revalidate=None, note_activity=None, phone_running=None, update_in_progress=None, size=DEVICE_SIZE,
                 max_fps=MAX_FPS, clock=time.perf_counter, limits=None, revalidate_seconds=None, lease_check_seconds=None,
                 pace=KEY_PACE_SECONDS, debug_prompt=None, deny_prompt=None, phone_stopping=None):
        self.socket, self.lease, self.viewer = socket, lease, viewer
        self.epoch, self.watching = epoch, watching
        self.open_frames, self.open_control, self.open_channel = open_frames, open_control, open_channel
        self.keyboard_probe, self.revalidate, self.note_activity = keyboard_probe, revalidate, note_activity
        self.debug_prompt, self.deny_prompt = debug_prompt, deny_prompt
        self.probes = None
        self._prompt = None
        self.denied = 0
        self.phone_running, self.update_in_progress = phone_running, update_in_progress
        self.phone_stopping = phone_stopping
        self.size, self.clock = size, clock
        self.min_interval = 1.0 / max_fps if max_fps else 0.0
        self.limits = limits or Limits(clock)
        self.revalidate_seconds = REVALIDATE_SECONDS if revalidate_seconds is None else revalidate_seconds
        self.lease_check_seconds = LEASE_CHECK_SECONDS if lease_check_seconds is None else lease_check_seconds
        self.pace = pace
        self.quality = QUALITY['normal']
        self.frames = None
        self.worker = None
        self.channel = None
        self._channel_ready = threading.Event()
        self._frame_ready = asyncio.Event()
        self._acked = asyncio.Event()
        self._in_flight = None
        self._seq = 0
        self._refresh = False
        self._paused = False
        self._state = {}
        self._noted = None
        self._activity_at = None
        self._moved = set()  # pointer ids that moved since their down: an up after a move is a swipe, not a tap
        self._down = {}  # pointer id -> its last point, while it is down on the phone (lifted when a debugging prompt blocks input)
        self._down_lock = threading.Lock()
        self._loop = None
        self._closing = self._stopped = False
        self._parts_lock = threading.Lock()
        self.reason = None
        self.sent = self.sent_bytes = self.received = 0
        self.send_times = []  # send instants on ``clock`` (bounded), for the cap's tests and B6
        # perf_counter, not monotonic: Windows' monotonic clock ticks every ~15.6 ms, too coarse for a strict send interval

    # -- lifecycle --
    async def run(self):
        """Run a controlling view with its lease, or an observer bound to the original agent epoch."""
        self._loop = asyncio.get_running_loop()
        tasks = []
        code = CLOSE_DONE
        try:
            try:
                await asyncio.to_thread(self._start_parts)
            except Exception as exc:  # health: allow BLE001 -- the phone's frames or input could not start: the view says so
                log.info('agent phone stream: the view could not start (%s)', type(exc).__name__)
                self.reason = 'error'
                return self.reason
            if self.open_channel is not None:
                threading.Thread(target=self._open_channel, name='beacon-agent-phone-helper-open', daemon=True).start()
            else:
                self._channel_ready.set()
            await self._send_state(helper='starting' if self.open_channel else 'off')
            tasks = [asyncio.create_task(self._receive()), asyncio.create_task(self._send_frames()),
                     asyncio.create_task(self._watch())]
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                try:
                    self.reason = task.result() or self.reason or 'gone'
                except ProtocolError as exc:
                    self.reason, code = 'violation', CLOSE_POLICY
                    log.info('agent phone stream: closed for %s', exc)
                except Exception as exc:  # health: allow BLE001 -- a send to a closed socket or a failed part
                    self.reason = self.reason or 'gone'
                    log.info('agent phone stream: ended (%s)', type(exc).__name__)
        finally:
            self._closing = True
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            await asyncio.to_thread(self._stop_parts)
            await asyncio.to_thread(self._settle_lease)
            if self.reason in BYE_CODES:
                code = BYE_CODES[self.reason]
                with contextlib.suppress(Exception):  # health: allow BLE001 -- the socket may already be gone
                    await self.socket.send_text(json.dumps({'t': 'bye', 'reason': self.reason}))
            with contextlib.suppress(Exception):  # health: allow BLE001 -- the socket may already be gone
                await self.socket.close(code=code)
        log.info('agent phone stream: view closed (%s; %d frames, %d inputs)', self.reason, self.sent, self.received)
        return self.reason

    def _stopping(self):
        if self.phone_stopping is None:
            return False
        try:
            return bool(self.phone_stopping())
        except Exception:  # health: allow BLE001 -- unknown means not stopping: the frames' failure stays an error
            return False

    def _start_parts(self):
        if not self.watching:
            self.worker = InputWorker(self.open_control, helper=self._helper, refused=self._refused, size=self.size,
                                      pace=self.pace).start()
            self.probes = Probes(keyboard=self.keyboard_probe, on_keyboard=self._keyboard_seen, guard=self.debug_prompt,
                                 on_guard=self._guard_seen).start()
        self.frames = self.open_frames(self._on_frame).start()

    def _stop_parts(self):
        if self.probes is not None:
            self.probes.stop()
        if self.worker is not None:
            self.worker.stop()
        if self.frames is not None:
            self.frames.stop()
        with self._parts_lock:
            self._stopped = True
            channel, self.channel = self.channel, None
        if channel is not None:
            with contextlib.suppress(Exception):  # health: allow BLE001 -- the link may already be closed
                channel.close('done')

    def _settle_lease(self):
        if self.watching:
            return
        try:
            if self.reason in ('done', 'removed', 'stopped'):
                self.lease.release(self.viewer, self.epoch)
            elif self.reason not in ('replaced', 'ended'):
                self.lease.went_away(self.viewer, self.epoch)  # dropped, backgrounded or broken: kept 5 min (§4.3)
        except OSError:
            log.info('agent phone stream: the lease could not be updated')

    def _open_channel(self):
        channel = None
        try:
            channel = self.open_channel()
        except Exception as exc:  # health: allow BLE001 -- no helper: text and paste are refused plainly, the view goes on
            log.info('agent phone stream: helper channel unavailable (%s)', type(exc).__name__)
        with self._parts_lock:
            if self._stopped and channel is not None:  # the view ended while the helper was starting: its session ends now
                with contextlib.suppress(Exception):  # health: allow BLE001 -- the link may already be closed
                    channel.close('done')
                channel = None
            self.channel = channel
        self._channel_ready.set()
        self._from_thread(self._send_state(helper='ready' if channel else 'off'))

    def _from_thread(self, coroutine):
        """Runs a send on the view's loop from a worker thread; dropped once the view is closing."""
        if self._loop is None or self._closing:
            coroutine.close()
            return
        future = asyncio.run_coroutine_threadsafe(coroutine, self._loop)
        future.add_done_callback(lambda f: f.cancelled() or f.exception())

    def _helper(self, wait):
        self._channel_ready.wait(wait)
        channel = self.channel
        return channel if channel is not None and getattr(channel, 'ready', None) is not None and channel.ready.is_set() else None

    def _refused(self, what, why):
        self._from_thread(self._send_json({'t': 'refused', 'what': what, 'reason': why}))
        if why == 'helper' and self._state.get('helper') != 'off':
            self._from_thread(self._send_state(helper='off'))  # the link ended (bye, idle, error): the app stops offering Paste

    def notice(self, kind, line, reason=None, args=None):
        """A plain line for the owner from the vault during setup ("Saved: Kanto Foods · k@example.com"), from any thread, with
        its code and the names in it (contract revision 5 §11.1): the app shows its own words for a known code."""
        message = {'t': 'notice', 'kind': kind, 'line': line[:200], 'reason': reason}
        if args:
            message['args'] = {k: str(v)[:120] for k, v in args.items()}
        self._from_thread(self._send_json(message))

    def _on_frame(self):
        if self._loop is not None:
            self._loop.call_soon_threadsafe(self._frame_ready.set)

    async def _send_json(self, value):
        await self.socket.send_text(json.dumps(value, separators=(',', ':')))

    async def _send_state(self, **changes):
        state = dict(self._state)
        state.update(changes)
        state.setdefault('keyboard', False)
        state.setdefault('rotation', 0)
        state.setdefault('helper', 'off')
        state.setdefault('prompt', None)
        state['lease'] = 'watch' if self.watching else 'you'
        if not self.watching:
            state['epoch'] = self.epoch
        if state != self._state:
            self._state = state
            await self._send_json({'t': 'state', **state})

    # -- frames out --
    async def _send_frames(self):
        last_send = None
        sent_seq = None
        while True:
            await self._frame_ready.wait()
            self._frame_ready.clear()
            if self.frames.failed:
                # The owner's stop takes the emulator down before the lease goes back, so the frames fail first.
                self.reason = 'stopped' if self._stopping() else 'error'
                return self.reason
            if self._paused:
                continue
            if self._in_flight is not None:
                try:
                    await asyncio.wait_for(self._acked.wait(), ACK_WAIT_SECONDS)
                except asyncio.TimeoutError:
                    log.info('agent phone stream: no ack in time; sending the next frame')
                self._in_flight = None
            self._acked.clear()
            if last_send is not None and self.min_interval:
                await self._wait_until(last_send + self.min_interval)
            frames = self.frames
            latest = frames.latest()
            if latest is None or (latest.seq == sent_seq and not self._refresh) or self._paused:
                continue
            self._refresh = False
            try:
                jpeg = await asyncio.to_thread(self._encode, frames, latest)
            except (ValueError, OSError):  # the frames were paused or stopped meanwhile
                continue
            if last_send is not None and self.min_interval:
                await self._wait_until(last_send + self.min_interval)
            if self.watching:
                current = await asyncio.to_thread(self.lease.get)
                if current.holder != 'agent' or current.epoch != self.epoch:
                    return 'replaced'
            self._seq += 1
            self._in_flight = self._seq
            await self.socket.send_bytes(frame_message(self._seq, latest.width, latest.height, latest.rotation, jpeg))
            last_send = self.clock()
            sent_seq = latest.seq
            self.sent += 1
            self.sent_bytes += FRAME.size + len(jpeg)
            if len(self.send_times) < 100000:
                self.send_times.append(last_send)
            if latest.rotation != self._state.get('rotation', 0):
                await self._send_state(rotation=latest.rotation)

    async def _wait_until(self, deadline):
        """Waits until ``clock()`` reaches ``deadline``. asyncio runs a timer up to one clock tick early (its ``clock_resolution``,
        ~15.6 ms on Windows), so the last stretch is a precise thread sleep: without it a 14 fps cap lets ~17 fps through."""
        while True:
            left = deadline - self.clock()
            if left <= 0:
                return
            if left > PRECISE_SECONDS:
                await asyncio.sleep(left - PRECISE_SECONDS)
            else:
                await asyncio.to_thread(time.sleep, left)

    def _encode(self, frames, latest):
        return encode_jpeg(frames.pixels(latest), latest.width, latest.height, self.quality)

    # -- input in --
    async def _receive(self):
        while True:
            message = await self.socket.receive()
            if message.get('type') == 'websocket.disconnect':
                return 'gone'
            if message.get('bytes') is not None or message.get('text') is None:
                raise ProtocolError('message')
            value = parse_client(message['text'])
            self.limits.admit(value['t'])
            self.received += 1
            reason = await self._handle(value)
            if reason:
                return reason

    async def _handle(self, value):
        t = value['t']
        if t == 'ack':
            if self._in_flight is None or value['seq'] > self._in_flight:
                if value['seq'] > self._seq:
                    raise ProtocolError('ack')
                return None  # a late ack of a frame already given up on
            if value['seq'] == self._in_flight:
                self._acked.set()
            return None
        if t == 'done':
            return 'done'
        if t == 'refresh':
            self._refresh = True
            self._frame_ready.set()
            return None
        if t == 'quality':
            self.quality = QUALITY[value['level']]
            self._refresh = True
            self._frame_ready.set()
            return None
        if t == 'background':
            if not self._paused:
                self._paused = True
                await asyncio.to_thread(self._pause_frames)
                if not self.watching:
                    await asyncio.to_thread(self.lease.went_away, self.viewer, self.epoch)
            return None
        if t == 'foreground':
            if not self._paused:
                return None
            if not self.watching:
                holds, _ = await asyncio.to_thread(self.lease.note_input, self.viewer, epoch=self.epoch)
                if not holds:
                    return 'replaced'
            await asyncio.to_thread(self._resume_frames)
            self._paused = False
            self._refresh = True
            self._frame_ready.set()
            return None
        if self.watching:
            await self._send_json({'t': 'refused', 'what': t, 'reason': 'read_only'})
            return None
        if self._paused:
            return None  # input from a backgrounded view is dropped
        if self._prompt is not None and not (t == 'key' and value['key'] == 'back' or t == 'touch' and self._all_up(value['points'])):
            return None  # a debugging prompt is up: only Back, and fingers lifting, reach the phone until it is gone (Root, 17 Nov)
        if not await self._noted_input():
            return 'replaced'
        if t == 'touch':
            # Under the pointer lock, as the block's lift is: a touch is either queued before the lift (and lifted by it) or sees
            # the block and is dropped; never a down queued after the lift's up, which would leave a finger pressed.
            with self._down_lock:
                points = value['points']
                if self._prompt is not None:  # the block started meanwhile: only lifts of fingers still down get through
                    points = [p for p in points if p['phase'] == 'up' and p['id'] in self._down] if self._all_up(points) else []
                for p in points:
                    if p['phase'] == 'up':
                        self._down.pop(p['id'], None)
                    else:
                        self._down[p['id']] = p
                if not points:
                    return None
                if not self.worker.put(('touch', points, self._state.get('rotation', 0))):
                    raise ProtocolError('queue')
            op = None
        elif t == 'key':
            op = ('key', value['key'])
        elif t == 'text' and route_text(value['text']) == 'keys':
            op = ('keys', value['text'])
        else:
            op = ('helper', value['text'], t)  # the whole message, never split (S5)
        if op is not None and not self.worker.put(op):
            raise ProtocolError('queue')
        self.probes.input_seen()
        if t == 'key' or t == 'touch' and self._tapped(value['points']):
            self.probes.keyboard_wanted()  # a tap can open the keyboard and a key can close it; a swipe does neither
        return None

    def _tapped(self, points):
        """True when a pointer went up without having moved since its down."""
        tap = False
        for p in points:
            if p['phase'] == 'down':
                self._moved.discard(p['id'])
            elif p['phase'] == 'move':
                self._moved.add(p['id'])
            elif p['id'] in self._moved:
                self._moved.discard(p['id'])
            else:
                tap = True
        return tap

    def _pause_frames(self):
        """Frames stop while the app is in the background (§4.3), and the frame file goes with them."""
        frames, self.frames = self.frames, _NoFrames()
        if frames is not None:
            frames.stop()

    def _resume_frames(self):
        self.frames = self.open_frames(self._on_frame).start()

    async def _noted_input(self):
        """Keeps the lease's input time (at most every 10 s) and the keeper's activity file (every 5 s); False when the lease is
        no longer this viewer's."""
        now = self.clock()
        if self._noted is None or now - self._noted >= 10.0:
            holds, _ = await asyncio.to_thread(self.lease.note_input, self.viewer, epoch=self.epoch)
            if not holds:
                return False
            self._noted = now
        if self.note_activity is not None and (self._activity_at is None or now - self._activity_at >= ACTIVITY_NOTE_SECONDS):
            self._activity_at = now
            with contextlib.suppress(Exception):  # health: allow BLE001 -- the keeper's activity file is best effort
                await asyncio.to_thread(self.note_activity)
        return True

    @staticmethod
    def _all_up(points):
        return all(p['phase'] == 'up' for p in points)

    def _block(self):
        """The prompt starts blocking input: under the pointer lock, the block is set and every finger still down on the phone is
        lifted where it last was, so none stays pressed while the owner's touches are dropped (L5, via Root). True when this call
        started the block."""
        with self._down_lock:
            if self._prompt is not None:
                return False
            self._prompt = 'usb_debugging'
            down, self._down = list(self._down.values()), {}
            if down and self.worker is not None:
                self.worker.put(('touch', [dict(p, phase='up') for p in down], self._state.get('rotation', 0)))
        return True

    def _keyboard_seen(self, shown):
        """The probe's answer: False, True, or 'password' (the field the keyboard serves is a password field, read from Android's
        own field info, never from text; contract revision 5 §11.2). None changes nothing."""
        if shown is not None:
            self._from_thread(self._send_state(keyboard='password' if shown == 'password' else bool(shown)))

    def _guard_seen(self, kind):
        """On the probe thread: a debugging prompt is answered No at once and the view says so until it is gone."""
        if kind in ('usb', 'wifi'):
            if self._block():  # first block input and tell the app, then answer (L3's deny() takes a second or two)
                self._from_thread(self._send_state(prompt='usb_debugging'))
            if self.deny_prompt is not None:
                try:
                    self.deny_prompt()
                except Exception as exc:  # health: allow BLE001 -- the next probe, a second later, tries again
                    log.info('agent phone stream: the debugging prompt could not be answered (%s)', type(exc).__name__)
            self.denied += 1
            log.info('agent phone stream: a debugging prompt was answered No')
        elif kind is None and self._prompt is not None:
            with self._down_lock:
                self._prompt = None
            self._from_thread(self._send_state(prompt=None))

    # -- the binding, the lease, the phone --
    async def _watch(self):
        next_check = self.clock() + self.revalidate_seconds
        while True:
            await asyncio.sleep(self.lease_check_seconds)
            if self._stopping():
                return 'stopped'
            lease = await asyncio.to_thread(self.lease.get)
            if self.watching:
                if lease.holder != 'agent' or lease.epoch != self.epoch:
                    return 'replaced'
            elif lease.holder != 'human' or lease.viewer_id != self.viewer or lease.epoch != self.epoch:
                return 'replaced' if lease.holder == 'human' else 'ended'
            if self.clock() >= next_check:
                next_check = self.clock() + self.revalidate_seconds
                if self.update_in_progress is not None and await asyncio.to_thread(self.update_in_progress):
                    return 'update'
                if self.revalidate is not None:
                    try:
                        await asyncio.to_thread(self.revalidate)
                    except Exception:  # health: allow BLE001 -- any refusal ends the view; nothing of it is shown
                        return 'removed'
                if self.phone_running is not None and not await asyncio.to_thread(self.phone_running):
                    return 'stopped'
