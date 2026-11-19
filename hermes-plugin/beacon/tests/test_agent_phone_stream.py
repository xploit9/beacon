"""The agent phone's setup stream (EMU-DESIGN §4.2-§4.5): frames out with acks and a cap, input in through one FIFO worker,
text routed per message, nothing recorded. Everything of L1b's and L9's is faked here."""
import asyncio
import importlib.util
import json
import logging
import os
import struct
import threading
import time
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def _load(name):
    spec = importlib.util.spec_from_file_location(f'beacon_{name}_under_test', ROOT / f'{name}.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


stream = _load('agent_phone_stream')
lease_mod = _load('agent_phone_lease')
VIEWER = 'v-test-viewer'


# -- fakes -----------------------------------------------------------------------------------------------------------------

class FakeSocket:
    def __init__(self):
        self.incoming = asyncio.Queue()
        self.frames, self.texts, self.closed = [], [], None
        self.frame_times = []

    async def send_bytes(self, data):
        self.frames.append(bytes(data))
        self.frame_times.append(time.perf_counter())

    async def send_text(self, text):
        self.texts.append(json.loads(text))

    async def receive(self):
        return await self.incoming.get()

    async def close(self, code=1000):
        self.closed = code

    def push(self, value):
        raw = value if isinstance(value, str) else json.dumps(value)
        self.incoming.put_nowait({'type': 'websocket.receive', 'text': raw})

    def push_bytes(self, data):
        self.incoming.put_nowait({'type': 'websocket.receive', 'bytes': data})

    def disconnect(self):
        self.incoming.put_nowait({'type': 'websocket.disconnect'})

    def of(self, t):
        return [m for m in self.texts if m.get('t') == t]


class FakeFrames:
    instances = []

    def __init__(self, on_frame, width=72, height=160):
        self.on_frame, self.width, self.height = on_frame, width, height
        self.failed = None
        self._latest = None
        self.n = 0
        self.started = self.stopped = False
        FakeFrames.instances.append(self)

    def start(self):
        self.started = True
        return self

    def push(self, rotation=0, color=(10, 200, 30)):
        self.n += 1
        self.color = color
        self._latest = stream.Latest(self.n, self.width, self.height, rotation)
        self.on_frame()

    def latest(self):
        return self._latest

    def pixels(self, latest):
        if self.stopped:
            raise ValueError('stopped')
        return bytes(self.color) * (latest.width * latest.height)

    def stop(self):
        self.stopped = True
        return True


class FakeControl:
    entered = threading.Event()

    def __init__(self, log, gate=None):
        self.log, self.gate = log, gate
        self.closed = False

    def touch(self, touches, display=0):
        if self.gate is not None:
            FakeControl.entered.set()
            self.gate.wait(5)
        self.log.append(('touch', tuple(touches), time.perf_counter()))

    def key(self, key=None, text=None, keycode=None, code_type=1, event_type=2):
        assert text is None  # gRPC text is never used (S5)
        self.log.append(('key', key, event_type, time.perf_counter()))

    def close(self):
        self.closed = True


class FakeChannel:
    def __init__(self, log, ready=True, reply='ack'):
        self.log, self.reply = log, reply
        self.ready = threading.Event()
        if ready:
            self.ready.set()
        self.closed = None

    def request(self, t, timeout=None, **fields):
        self.log.append(('channel', t, fields.get('text'), time.perf_counter()))
        return {'t': self.reply if t == 'clip_set' else 'ack', 're': 1}

    def close(self, reason='done'):
        self.closed = reason


async def until(cond, timeout=5.0):
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError('condition not met in time')
        await asyncio.sleep(0.005)


def make(tmp_path, socket, log, *, channel='ready', max_fps=0, gate=None, **extra):
    lease = lease_mod.PhoneLease(tmp_path)
    lease.acquire(VIEWER)
    holder = {}

    def open_frames(on_frame):
        holder['frames'] = FakeFrames(on_frame)
        return holder['frames']

    def open_channel():
        if channel is None:
            raise OSError('no helper')
        holder['channel'] = FakeChannel(log, ready=channel == 'ready')
        return holder['channel']

    session = stream.ViewSession(socket, lease, VIEWER, open_frames=open_frames, open_control=lambda: FakeControl(log, gate),
                                 open_channel=open_channel, max_fps=max_fps, pace=0.005, lease_check_seconds=0.02,
                                 revalidate_seconds=0.05, **extra)
    return session, lease, holder


def run(coro):
    return asyncio.run(asyncio.wait_for(coro, 30))


# -- pure pieces ------------------------------------------------------------------------------------------------------------

def test_frame_message_layout_is_pinned():
    data = stream.frame_message(0x01020304, 720, 1600, 1, b'\xff\xd8JPEG')
    assert stream.FRAME.size == 10
    assert data[:10] == struct.pack('>BIHHB', 0x01, 0x01020304, 720, 1600, 1)
    assert stream.read_frame_message(data) == (0x01020304, 720, 1600, 1, b'\xff\xd8JPEG')
    assert stream.frame_message(2 ** 32 + 5, 1, 1, 7, b'')[1:5] == struct.pack('>I', 5)  # seq wraps, rotation is 0-3


def test_rotation_reads_the_nested_rotation_message():
    assert stream.rotation_of(b'') == 0
    assert stream.rotation_of(None) == 0
    assert stream.rotation_of(b'\x08\x01') == 1
    assert stream.rotation_of(b'\x11' + b'\0' * 8 + b'\x08\x03') == 3  # a double xAxis first, then rotation
    assert stream.rotation_of(2) == 2


@pytest.mark.parametrize('text,route', [('Hello World 123!', 'keys'), ('P@ss w0rd ~`{}|"\\', 'keys'), ('café naïve', 'helper'),
                                        ('مرحبا', 'helper'), ('😀', 'helper'), ('line\nbreak', 'helper'), ('tab\there', 'helper'),
                                        ('Hi\x7f', 'helper')])
def test_text_routes_per_message_on_printable_ascii(text, route):
    assert stream.route_text(text) == route


def test_touch_maps_to_the_phones_pixels_and_clamps():
    assert stream.to_device(0.5, 0.5) == (540, 1200)
    assert stream.to_device(0, 0) == (0, 0)
    assert stream.to_device(1, 1) == (1079, 2399)
    assert stream.to_device(0.25, 0.75, rotation=2) == (810, 600)
    assert stream.to_device(0.5, 0.5, rotation=1) == (540, 1200)


@pytest.mark.parametrize('raw', ['not json', '[]', '{"t":"unknown"}', '{"t":"key","key":"back","x":1}', '{"t":"key","key":"power"}',
                                 '{"t":"touch","points":[{"id":0,"x":1.5,"y":0.2,"phase":"down"}]}',
                                 '{"t":"touch","points":[{"id":0,"x":0.1,"y":0.2,"phase":"down"},{"id":0,"x":0.1,"y":0.2,"phase":"up"}]}',
                                 '{"t":"touch","points":[]}', '{"t":"touch","points":[{"id":0,"x":NaN,"y":0.2,"phase":"down"}]}',
                                 '{"t":"text","text":""}', '{"t":"text","text":"a\\u0000b"}', '{"t":"ack","seq":-1}',
                                 '{"t":"ack","seq":true}', '{"t":"t","t":"done"}', '{"t":"quality","level":"ultra"}',
                                 json.dumps({'t': 'text', 'text': 'x' * 501}), json.dumps({'t': 'paste', 'text': 'x' * 10001})])
def test_protocol_rejects_anything_out_of_contract(raw):
    with pytest.raises(stream.ProtocolError):
        stream.parse_client(raw)


def test_protocol_accepts_each_message_of_the_contract():
    for value in ({'t': 'ack', 'seq': 3}, {'t': 'touch', 'points': [{'id': 1, 'x': 0.2, 'y': 1, 'phase': 'move'}]},
                  {'t': 'key', 'key': 'recents'}, {'t': 'text', 'text': 'x' * 500}, {'t': 'paste', 'text': 'é' * 10000},
                  {'t': 'quality', 'level': 'low'}, {'t': 'refresh'}, {'t': 'background'}, {'t': 'foreground'}, {'t': 'done'}):
        assert stream.parse_client(json.dumps(value)) == value


def test_rate_limits_by_message_class():
    now = [0.0]
    limits = stream.Limits(clock=lambda: now[0])
    rate, burst = stream.RATES['key']
    for _ in range(int(burst + rate * stream.CATCH_UP_SECONDS)):  # the hard ceiling: burst plus the stall catch-up (fu2)
        limits.admit('key')
    with pytest.raises(stream.ProtocolError):
        limits.admit('key')
    limits.admit('touch')  # another class has its own bucket
    now[0] += 1.0
    limits.admit('key')


# -- frames through the MMAP transport -----------------------------------------------------------------------------------------

class FakeStreamClient:
    """L1b's EmulatorGrpc as far as MmapFrames uses it: ``screenshots`` writes into the mapped file, as the emulator does."""

    def __init__(self, frames_to_send=2):
        self.calls, self.closed = [], False
        self.frames_to_send = frames_to_send
        self.blocked = threading.Event()

    def cancel(self):
        self.blocked.set()

    def screenshots(self, fmt, width=0, height=0, mmap_path=None):
        self.calls.append({'fmt': fmt, 'width': width, 'height': height, 'mmap_path': str(mmap_path)})
        for i in range(self.frames_to_send):
            with open(mmap_path, 'r+b') as fh:
                fh.write(bytes([i + 1, 2, 3]) * (width * height))
            yield {'width': width, 'height': height, 'rotation': b'', 'image': b'', 'seq': i, 'timestamp_us': 0}
        self.blocked.wait(10)  # a still screen: the stream sits in recv until it is aborted

    def close(self):
        self.closed = True


def test_mmap_frames_request_720x1600_in_an_owner_only_file_that_is_gone_after(tmp_path):
    client = FakeStreamClient()
    seen = threading.Event()
    frames = stream.MmapFrames(lambda: client, tmp_path / 'run', 5640, width=72, height=160,
                               on_frame=lambda: seen.set() if frames.latest() and frames.latest().seq == 2 else None)
    frames.start()
    assert seen.wait(5)
    path = frames.path
    assert path.parent == tmp_path / 'run' and path.match('frames-*.bin') and path.name.startswith('frames-5640-')
    if os.name != 'nt':
        assert path.stat().st_mode & 0o077 == 0
    call = client.calls[0]
    assert call['fmt'] == stream.RGB888 and call['width'] == 72 and call['height'] == 160 and call['mmap_path'] == str(path)
    latest = frames.latest()
    assert (latest.width, latest.height, latest.rotation) == (72, 160, 0)
    assert frames.pixels(latest)[:3] == bytes([2, 2, 3])
    started = time.monotonic()
    assert frames.stop() is True
    assert time.monotonic() - started < 3  # the still stream was aborted, not waited out
    assert client.closed and not path.exists()
    assert list((tmp_path / 'run').glob('frames-*.bin')) == []


def test_default_frame_request_is_720x1600():
    client = FakeStreamClient(frames_to_send=0)
    frames = stream.MmapFrames(lambda: client, Path(os.environ.get('TMP', '.')) / f'l2-frames-{os.getpid()}', 'x')
    assert (frames.width, frames.height) == (720, 1600)


def test_sweep_removes_leftover_frame_files(tmp_path):
    run_dir = tmp_path / 'run'
    run_dir.mkdir()
    (run_dir / 'frames-5640-dead.bin').write_bytes(b'x')
    (run_dir / 'state.json').write_text('{}')
    assert stream.sweep_frame_files(run_dir) == 1
    assert [p.name for p in run_dir.iterdir()] == ['state.json']


# -- the view session ---------------------------------------------------------------------------------------------------------

def test_one_frame_in_flight_and_drop_to_latest(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        frames = holder['frames']
        frames.push(color=(1, 1, 1))
        await until(lambda: len(socket.frames) == 1)
        frames.push(color=(2, 2, 2))
        frames.push(color=(3, 3, 3))
        await asyncio.sleep(0.2)
        assert len(socket.frames) == 1  # nothing before the ack
        socket.push({'t': 'ack', 'seq': 1})
        await until(lambda: len(socket.frames) == 2)
        await asyncio.sleep(0.1)
        assert len(socket.frames) == 2  # frame 2 was dropped: only the newest went
        seq, w, h, rot, jpeg = stream.read_frame_message(socket.frames[1])
        assert (seq, w, h, rot) == (2, 72, 160, 0) and jpeg[:2] == b'\xff\xd8'
        from PIL import Image
        import io
        assert Image.open(io.BytesIO(jpeg)).getpixel((5, 5))[0] in range(0, 8)  # (3,3,3), not (2,2,2) after JPEG
        socket.push({'t': 'done'})
        assert await task == 'done'
    run(main())


def test_send_interval_is_strict_and_the_first_frame_after_idle_is_not_held(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, holder = make(tmp_path, socket, log, max_fps=10)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        frames = holder['frames']
        acked = 0
        end = time.perf_counter() + 0.7
        i = 0
        while time.perf_counter() < end:  # a scroll: new frames every 10 ms, each sent frame acked at once
            i += 1
            frames.push(color=(i % 250, 0, 0))
            await asyncio.sleep(0.01)
            if len(socket.frames) > acked:
                acked = len(socket.frames)
                socket.push({'t': 'ack', 'seq': acked})
        await asyncio.sleep(0.15)  # the last frame of the scroll goes out
        socket.push({'t': 'ack', 'seq': len(socket.frames)})
        assert 6 <= len(socket.frames) <= 9  # 10 fps over 0.7 s, plus the last one
        await asyncio.sleep(0.6)  # idle
        before = len(socket.frames)
        pushed = time.perf_counter()
        frames.push(color=(99, 99, 99))
        await until(lambda: len(socket.frames) == before + 1)
        assert socket.frame_times[-1] - pushed < 0.08
        gaps = [b - a for a, b in zip(session.send_times, session.send_times[1:])]
        assert gaps and min(gaps) >= 0.1 - 0.002
        socket.push({'t': 'done'})
        await task
    run(main())


def test_ascii_text_is_typed_as_paced_keys_in_order(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        socket.push({'t': 'text', 'text': 'Hi! @#'})
        await until(lambda: len([e for e in log if e[0] == 'key']) == 6)
        keys = [e for e in log if e[0] == 'key']
        assert [k[1] for k in keys] == list('Hi! @#') and all(k[2] == stream.KEYPRESS for k in keys)
        assert min(b[3] - a[3] for a, b in zip(keys, keys[1:])) >= 0.0045
        assert not [e for e in log if e[0] == 'channel']
        socket.push({'t': 'done'})
        await task
    run(main())


@pytest.mark.parametrize('kind,text', [('text', 'مرحبا 😀 café naïve'), ('paste', 'plain ASCII paste'), ('paste', 'x' * 10000)])
def test_other_text_and_every_paste_go_whole_through_the_helper(tmp_path, kind, text):
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        socket.push({'t': kind, 'text': text})
        await until(lambda: any(e[:2] == ('channel', 'clip_clear') for e in log))
        steps = [e[:3] if e[0] == 'key' else e[:2] for e in log]
        assert steps == [('channel', 'clip_set'), ('key', 'Control', stream.KEYDOWN), ('key', 'v', stream.KEYPRESS),
                         ('key', 'Control', stream.KEYUP), ('channel', 'clip_clear')]
        assert log[0][2] == text  # the whole message, once
        socket.push({'t': 'done'})
        await task
    run(main())


def test_mixed_messages_never_interleave(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        for value in ({'t': 'text', 'text': 'ab'}, {'t': 'text', 'text': 'ي'}, {'t': 'text', 'text': 'cd'}):
            socket.push(value)
        await until(lambda: len([e for e in log if e[0] == 'key' and e[1] in 'abcd']) == 4)
        order = [e[1] for e in log if e[0] == 'key' and e[1] in ('a', 'b', 'c', 'd', 'v')] + []
        assert order == ['a', 'b', 'v', 'c', 'd']
        socket.push({'t': 'done'})
        await task
    run(main())


def test_no_helper_link_refuses_the_text_plainly_and_the_view_goes_on(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'HELPER_WAIT_SECONDS', 0.2)

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, channel=None)
        task = asyncio.create_task(session.run())
        socket.push({'t': 'paste', 'text': 'مرحبا'})
        await until(lambda: socket.of('refused'))
        assert socket.of('refused')[0] == {'t': 'refused', 'what': 'paste', 'reason': 'helper'}
        socket.push({'t': 'key', 'key': 'home'})
        await until(lambda: any(e[:2] == ('key', 'GoHome') for e in log))
        assert any(s.get('helper') == 'off' for s in socket.of('state'))
        socket.push({'t': 'done'})
        assert await task == 'done'
    run(main())


def test_a_helper_link_that_ends_turns_the_helper_state_off(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'HELPER_WAIT_SECONDS', 0.2)

    async def main():
        socket, log = FakeSocket(), []
        session, _, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: any(s.get('helper') == 'ready' for s in socket.of('state')))
        holder['channel'].ready.clear()  # the helper said bye (idle, error) or the link broke
        socket.push({'t': 'text', 'text': 'café'})
        await until(lambda: socket.of('refused') and socket.of('state')[-1].get('helper') == 'off')
        assert socket.of('refused') == [{'t': 'refused', 'what': 'text', 'reason': 'helper'}]
        assert not [e for e in log if e[0] == 'channel']
        socket.push({'t': 'done'})
        await task
    run(main())


def test_touch_moves_are_coalesced_but_downs_and_ups_kept(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        gate = threading.Event()
        session, _, _ = make(tmp_path, socket, log, gate=gate)
        task = asyncio.create_task(session.run())

        def touch(phase, x):
            socket.push({'t': 'touch', 'points': [{'id': 0, 'x': x, 'y': 0.5, 'phase': phase}]})
        FakeControl.entered.clear()
        touch('down', 0.1)
        await until(FakeControl.entered.is_set)  # the worker is inside 'down', held by the gate
        for i in range(20):
            touch('move', 0.1 + i * 0.01)
        touch('up', 0.5)
        await until(lambda: session.received == 22)
        assert session.worker.pending() == 2  # one coalesced move + the up
        gate.set()
        await until(lambda: len([e for e in log if e[0] == 'touch']) == 3)
        touches = [e[1][0] for e in log if e[0] == 'touch']
        assert [t[3] for t in touches] == [1024, 1024, 0]
        assert touches[1][0] == stream.to_device(0.29, 0.5)[0]  # the newest move survived
        socket.push({'t': 'done'})
        await task
    run(main())


@pytest.mark.parametrize('bad', ['{"t":"key","key":"power"}', 'garbage', b'\x00\x01'])
def test_a_violation_closes_1008_and_keeps_the_lease_for_a_retry(tmp_path, bad):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        if isinstance(bad, bytes):
            socket.push_bytes(bad)
        else:
            socket.push(bad)
        assert await task == 'violation'
        assert socket.closed == 1008 and not socket.of('bye')
        assert lease.get().away is not None and lease.holds(VIEWER)
    run(main())


def test_flooding_keys_closes_1008(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        rate, burst = stream.RATES['key']
        for _ in range(int(burst + rate * stream.CATCH_UP_SECONDS) + 5):  # past the hard ceiling (fu2)
            socket.push({'t': 'key', 'key': 'tab'})
        assert await task == 'violation'
        assert socket.closed == 1008
    run(main())


def test_an_ack_for_a_frame_never_sent_closes_1008(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        socket.push({'t': 'ack', 'seq': 7})
        assert await task == 'violation'
    run(main())


def test_done_hands_the_phone_back_and_stops_everything(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: 'channel' in holder)
        socket.push({'t': 'done'})
        assert await task == 'done'
        assert socket.of('bye') == [{'t': 'bye', 'reason': 'done'}] and socket.closed == 1000
        assert lease.get().holder == 'agent'
        assert holder['frames'].stopped and holder['channel'].closed == 'done'
    run(main())


def test_a_dropped_socket_keeps_the_lease_five_minutes(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        socket.disconnect()
        assert await task == 'gone'
        current = lease.get()
        assert current.holder == 'human' and current.viewer_id == VIEWER and current.away is not None
        assert holder['frames'].stopped
    run(main())


def test_another_viewer_taking_the_phone_ends_this_view(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await asyncio.sleep(0.05)
        await asyncio.to_thread(lease.acquire, 'v-other-phone')
        assert await task == 'replaced'
        assert socket.of('bye') == [{'t': 'bye', 'reason': 'replaced'}]
        assert lease.get().viewer_id == 'v-other-phone'  # not released by the evicted view
    run(main())


def test_a_removed_device_closes_the_view_1008_and_frees_the_phone(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        removed = threading.Event()

        def revalidate():
            if removed.is_set():
                raise PermissionError('device removed')
        session, lease, _ = make(tmp_path, socket, log, revalidate=revalidate)
        task = asyncio.create_task(session.run())
        await asyncio.sleep(0.1)
        assert not task.done()
        removed.set()
        assert await task == 'removed'
        assert socket.of('bye') == [{'t': 'bye', 'reason': 'removed'}] and socket.closed == 1008
        assert lease.get().holder == 'agent'
    run(main())


def test_an_update_or_a_stopped_phone_ends_the_view(tmp_path):
    async def main():
        for extra, reason, code in (({'update_in_progress': lambda: True}, 'update', 1013),
                                    ({'phone_running': lambda: False}, 'stopped', 1000)):
            socket, log = FakeSocket(), []
            session, _, _ = make(tmp_path / reason, socket, log, **extra)
            assert await session.run() == reason
            assert socket.closed == code
    run(main())


def test_background_stops_frames_and_foreground_brings_them_back(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        first = holder['frames']
        first.push()
        await until(lambda: len(socket.frames) == 1)
        socket.push({'t': 'ack', 'seq': 1})
        socket.push({'t': 'background'})
        await until(lambda: first.stopped and lease.get().away is not None)
        assert lease.holds(VIEWER)
        socket.push({'t': 'key', 'key': 'home'})  # dropped while in the background
        socket.push({'t': 'foreground'})
        await until(lambda: holder['frames'] is not first)
        holder['frames'].push()
        await until(lambda: len(socket.frames) == 2)
        assert lease.get().away is None
        assert not any(e[:2] == ('key', 'GoHome') for e in log)
        socket.push({'t': 'done'})
        await task
    run(main())


def test_keyboard_and_helper_state_reach_the_app(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'KEYBOARD_PROBE_DELAY', 0.01)

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, keyboard_probe=lambda: True)
        task = asyncio.create_task(session.run())
        await until(lambda: any(s.get('helper') == 'ready' for s in socket.of('state')))
        assert socket.of('state')[0] == {'t': 'state', 'keyboard': False, 'rotation': 0, 'helper': 'starting', 'prompt': None, 'lease': 'you'}
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'down'}]})
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'up'}]})
        await until(lambda: any(s.get('keyboard') for s in socket.of('state')))
        socket.push({'t': 'done'})
        await task
    run(main())


def test_the_keyboard_is_probed_after_taps_and_keys_not_swipes_at_most_once_a_second(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'KEYBOARD_PROBE_DELAY', 0.01)
    monkeypatch.setattr(stream, 'KEYBOARD_PROBE_GAP', 0.4)
    probes = []

    def probe():
        probes.append(time.perf_counter())
        return False

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, keyboard_probe=probe)
        task = asyncio.create_task(session.run())

        def touch(phase, y=0.5):
            socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': y, 'phase': phase}]})
        touch('down', 0.8)
        for y in (0.7, 0.6, 0.5):
            touch('move', y)
        touch('up', 0.5)  # a swipe: no probe
        await asyncio.sleep(0.3)
        assert probes == []
        for _ in range(8):  # rapid taps: probes collapse to one per gap
            touch('down')
            touch('up')
            await asyncio.sleep(0.05)
        await asyncio.sleep(1.0)
        assert 1 <= len(probes) <= 3
        assert all(b - a >= 0.4 - 0.02 for a, b in zip(probes, probes[1:]))
        before = len(probes)
        socket.push({'t': 'key', 'key': 'back'})  # a key can close the keyboard
        await until(lambda: len(probes) == before + 1)
        socket.push({'t': 'done'})
        await task
    run(main())


class FakeGuard:
    """L3's agent_phone_guard as the view uses it: debug_prompt() -> 'usb' | 'wifi' | None, deny() answers No (Back)."""

    def __init__(self):
        self.showing = None
        self.checks, self.denials = [], []

    def debug_prompt(self):
        self.checks.append(time.perf_counter())
        return self.showing

    def deny(self):
        self.denials.append(time.perf_counter())
        return True


def test_a_debugging_prompt_is_answered_no_at_once_and_only_back_passes_while_it_shows(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'GUARD_SECONDS', 0.1)
    monkeypatch.setattr(stream, 'GUARD_AFTER_INPUT', 0.05)
    guard = FakeGuard()

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, debug_prompt=guard.debug_prompt, deny_prompt=guard.deny)
        task = asyncio.create_task(session.run())
        await until(lambda: guard.checks)
        guard.showing = 'wifi'  # an app on the phone offered its key to adbd (wireless debugging)
        await until(lambda: any(s.get('prompt') == 'usb_debugging' for s in socket.of('state')))
        await until(lambda: guard.denials)  # answered No at once, right after the app was told and input blocked
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.6, 'phase': 'down'}]})
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.6, 'phase': 'up'}]})
        socket.push({'t': 'key', 'key': 'enter'})
        socket.push({'t': 'text', 'text': 'yes'})
        socket.push({'t': 'key', 'key': 'back'})
        await until(lambda: any(e[:2] == ('key', 'GoBack') for e in log))
        await asyncio.sleep(0.2)
        assert [e for e in log if e[0] == 'touch'] == [] and not any(e[1] in ('Enter', 'y') for e in log if e[0] == 'key')
        guard.showing = None  # Back took it away
        await until(lambda: socket.of('state')[-1].get('prompt') is None)
        socket.push({'t': 'key', 'key': 'enter'})
        await until(lambda: any(e[:2] == ('key', 'Enter') for e in log))
        socket.push({'t': 'done'})
        await task
    run(main())


def test_fingers_down_when_the_prompt_starts_are_lifted_and_lifts_still_pass(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'GUARD_SECONDS', 0.1)
    monkeypatch.setattr(stream, 'GUARD_AFTER_INPUT', 0.05)
    guard = FakeGuard()

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, debug_prompt=guard.debug_prompt, deny_prompt=guard.deny)
        task = asyncio.create_task(session.run())
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.3, 'y': 0.4, 'phase': 'down'}]})
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.35, 'y': 0.45, 'phase': 'move'},
                                              {'id': 1, 'x': 0.7, 'y': 0.8, 'phase': 'down'}]})
        await until(lambda: len([e for e in log if e[0] == 'touch']) == 2)
        guard.showing = 'usb'  # two fingers are on the phone when the prompt comes up
        await until(lambda: any(s.get('prompt') == 'usb_debugging' for s in socket.of('state')))
        await until(lambda: len([e for e in log if e[0] == 'touch']) == 3)
        lifted = [e for e in log if e[0] == 'touch'][2][1]
        assert sorted(lifted) == sorted([(*stream.to_device(0.35, 0.45), 0, 0), (*stream.to_device(0.7, 0.8), 1, 0)])
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.36, 'y': 0.46, 'phase': 'move'}]})  # dropped
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.36, 'y': 0.46, 'phase': 'up'},  # passes the block, but finger 0
                                              {'id': 1, 'x': 0.7, 'y': 0.8, 'phase': 'up'}]})  # and 1 are lifted already
        socket.push({'t': 'touch', 'points': [{'id': 2, 'x': 0.5, 'y': 0.5, 'phase': 'down'}]})  # dropped
        socket.push({'t': 'key', 'key': 'back'})
        await until(lambda: any(e[:2] == ('key', 'GoBack') for e in log))
        await asyncio.sleep(0.2)
        assert len([e for e in log if e[0] == 'touch']) == 3  # nothing pressed, nothing lifted twice
        socket.push({'t': 'done'})
        await task
    run(main())


def test_touches_and_the_blocks_lift_are_queued_under_one_lock(tmp_path, monkeypatch):
    """A touch is queued before the block's lift (and lifted by it) or sees the block: never a down after the lift's up."""
    monkeypatch.setattr(stream, 'GUARD_SECONDS', 0.1)
    guard = FakeGuard()

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, debug_prompt=guard.debug_prompt, deny_prompt=guard.deny)
        task = asyncio.create_task(session.run())
        await until(lambda: session.worker is not None)
        queued = []
        real_put = session.worker.put

        def put(op):
            if op[0] == 'touch':
                queued.append((op[1][0]['phase'], session._down_lock.locked()))
            return real_put(op)
        session.worker.put = put
        socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.3, 'y': 0.3, 'phase': 'down'}]})
        await until(lambda: queued)
        guard.showing = 'usb'
        await until(lambda: len(queued) == 2)  # the block's lift
        socket.push({'t': 'touch', 'points': [{'id': 1, 'x': 0.4, 'y': 0.4, 'phase': 'down'}]})  # dropped by the block
        await asyncio.sleep(0.2)
        assert queued == [('down', True), ('up', True)]
        socket.push({'t': 'done'})
        await task
    run(main())


def test_a_lift_already_in_flight_when_the_prompt_starts_still_reaches_the_phone(tmp_path, monkeypatch):
    """The block lifts what it knows is down; an up the app sent for a finger still down goes through the block."""
    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        socket.push({'t': 'touch', 'points': [{'id': 3, 'x': 0.2, 'y': 0.2, 'phase': 'down'}]})
        await until(lambda: [e for e in log if e[0] == 'touch'])
        session._prompt = 'usb_debugging'  # the block holds, but this finger was not lifted by it (the race L5 named)
        socket.push({'t': 'touch', 'points': [{'id': 3, 'x': 0.2, 'y': 0.2, 'phase': 'up'}]})
        await until(lambda: len([e for e in log if e[0] == 'touch']) == 2)
        assert [e for e in log if e[0] == 'touch'][1][1][0][3] == 0
        socket.push({'t': 'touch', 'points': [{'id': 4, 'x': 0.2, 'y': 0.2, 'phase': 'down'}]})  # a new finger: dropped
        await asyncio.sleep(0.2)
        assert len([e for e in log if e[0] == 'touch']) == 2
        socket.push({'t': 'done'})
        await task
    run(main())


def test_the_debugging_check_runs_every_second_and_soon_after_each_input(tmp_path, monkeypatch):
    monkeypatch.setattr(stream, 'GUARD_SECONDS', 0.4)
    monkeypatch.setattr(stream, 'GUARD_AFTER_INPUT', 0.1)
    guard = FakeGuard()

    async def main():
        socket, log = FakeSocket(), []
        session, _, _ = make(tmp_path, socket, log, debug_prompt=guard.debug_prompt, deny_prompt=guard.deny)
        started = time.perf_counter()
        task = asyncio.create_task(session.run())
        await asyncio.sleep(1.0)
        idle = list(guard.checks)
        assert 2 <= len(idle) <= 3 and all(b - a >= 0.38 for a, b in zip(idle, idle[1:]))
        assert idle[0] - started >= 0.35  # the first check waits its period
        sent = time.perf_counter()
        socket.push({'t': 'key', 'key': 'tab'})
        await until(lambda: any(t > sent for t in guard.checks))
        assert min(t for t in guard.checks if t > sent) - sent < 0.3  # soon after the input, not at the next period
        assert guard.denials == []  # nothing to answer while no prompt shows
        socket.push({'t': 'done'})
        await task
        assert session.probes.guard_runs >= 3
    run(main())


def test_a_helper_link_that_arrives_after_the_view_closed_is_closed(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        release = threading.Event()
        made = []
        lease = lease_mod.PhoneLease(tmp_path)
        lease.acquire(VIEWER)

        def slow_channel():
            release.wait(5)
            made.append(FakeChannel(log))
            return made[-1]
        session = stream.ViewSession(socket, lease, VIEWER, open_frames=lambda cb: FakeFrames(cb), open_control=lambda: FakeControl(log),
                                     open_channel=slow_channel, lease_check_seconds=0.02)
        task = asyncio.create_task(session.run())
        await asyncio.sleep(0.05)
        socket.push({'t': 'done'})
        await task
        release.set()
        await until(lambda: made and made[0].closed == 'done')
    run(main())


def test_frames_that_fail_end_the_view_with_error(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        session, lease, holder = make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        holder['frames'].failed = 'GrpcError'
        holder['frames'].on_frame()
        assert await task == 'error'
        assert socket.of('bye') == [{'t': 'bye', 'reason': 'error'}] and socket.closed == 1011
        assert lease.holds(VIEWER)  # kept for a retry
    run(main())


def test_a_view_that_cannot_start_says_error_and_closes(tmp_path):
    async def main():
        socket, log = FakeSocket(), []
        lease = lease_mod.PhoneLease(tmp_path)
        lease.acquire(VIEWER)

        def broken(on_frame):
            raise OSError('no file')
        session = stream.ViewSession(socket, lease, VIEWER, open_frames=broken, open_control=lambda: FakeControl(log))
        assert await session.run() == 'error'
        assert socket.closed == 1011
    run(main())


def test_nothing_typed_or_drawn_reaches_a_log(tmp_path, caplog):
    secret_text, secret_paste = 'SyntheticTyped-4417', 'سر-SyntheticPaste-9921'

    async def main():
        socket, log = FakeSocket(), []
        session, _, holder = make(tmp_path, socket, log, channel=None)
        task = asyncio.create_task(session.run())
        await until(lambda: 'frames' in holder)
        holder['frames'].push(color=(0x41, 0x42, 0x43))
        await until(lambda: socket.frames)
        socket.push({'t': 'ack', 'seq': 1})
        socket.push({'t': 'text', 'text': secret_text})
        socket.push({'t': 'paste', 'text': secret_paste})
        await until(lambda: socket.of('refused'), timeout=15)
        socket.push('{"t":"text","text":"' + secret_text + '","extra":1}')  # a violation carrying the text
        await task
        return socket
    with caplog.at_level(logging.DEBUG, logger='beacon.agent_phone.stream'):
        socket = run(main())
    records = [r.getMessage() for r in caplog.records if r.name == 'beacon.agent_phone.stream']
    assert any('view closed' in r for r in records)  # positive control: this capture does see the stream's records
    joined = '\n'.join(records)
    assert secret_text not in joined and 'SyntheticPaste' not in joined and 'ABC' not in joined
    assert secret_text not in json.dumps(socket.texts)
