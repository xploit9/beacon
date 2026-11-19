"""CHAT-121 follow-up 6, REVIEW-121B "Follow-up 2: chat-121-5": the relay's large file frames (F2-P3-1) and its confirmations' workers (F2-P3-2).
The reviewer's proofs are inverted here. Synthetic data only."""
import asyncio
import hashlib
import json
import threading
import time
import tracemalloc

import pytest
from fastapi import HTTPException

from test_mobile import harness  # noqa: F401
from test_canonical_transport import transport, admission_harness, _admit, _protocols, _phone_file  # noqa: F401

MIB = 1024 * 1024


_END = object()


class Phone:
    """The phone's side of one relayed socket: it sends [messages] in order (bytes are binary messages, str text ones, an asyncio.Event makes it
    wait, a float pauses that many seconds, a callable is built when it is sent), then leaves ([leave]) or stays. What the relay sends it is in
    [got] (text) and [acks] (each PIECE_ACK as its request id and count; [on_ack] is called with each). Like a real socket, a receive the relay
    gives up on (a timeout) loses nothing: the next one waits again where it was."""

    def __init__(self, messages, leave=True, on_ack=None):
        self.source, self.leave, self.got, self.sent_all, self.pending = iter(messages), leave, [], asyncio.Event(), None
        self.acks, self.ack_bytes, self.on_ack = [], [], on_ack

    async def receive(self):
        while True:
            if self.pending is None:
                self.pending = next(self.source, _END)
            item = self.pending
            if item is _END:
                break
            if isinstance(item, asyncio.Event):
                await item.wait()
                self.pending = None
                continue
            if isinstance(item, float):
                await asyncio.sleep(item)
                self.pending = None
                continue
            self.pending = None
            item = item() if callable(item) else item
            return {'type': 'websocket.receive', 'text': item} if isinstance(item, str) else {'type': 'websocket.receive', 'bytes': item}
        self.sent_all.set()
        if self.leave:
            return {'type': 'websocket.disconnect'}
        await asyncio.Event().wait()

    async def send_text(self, raw):
        self.got.append(raw)

    async def send_bytes(self, raw):
        assert raw[0] == 4 and len(raw) > 9, raw[:16]
        self.ack_bytes.append(bytes(raw))
        self.acks.append((raw[9:].decode('utf-8'), int.from_bytes(raw[1:9], 'big')))
        if self.on_ack:
            self.on_ack(self.acks[-1][1])


class Hermes:
    """Hermes' side: it never speaks first, and takes what the relay sends; a fragmented message is joined (or only counted, [keep] False)."""

    def __init__(self, keep=True, fail_after=None):
        self.keep, self.fail_after, self.sent, self.fragments, self.sizes, self.digests = keep, fail_after, [], [], [], []

    async def recv(self):
        await asyncio.Event().wait()

    async def send(self, raw, text=None):
        if not hasattr(raw, '__aiter__'):
            self.sent.append(raw)
            return
        count, size, digest, parts = 0, 0, hashlib.sha256(), []
        async for part in raw:
            count += 1
            if self.fail_after is not None and count > self.fail_after:
                raise ConnectionError('Hermes closed the socket mid-message')
            size += len(part)
            digest.update(part)
            if self.keep:
                parts.append(bytes(part))
        assert text is True
        self.fragments.append(count)
        self.sizes.append(size)
        self.digests.append(digest.hexdigest())
        self.sent.append(b''.join(parts).decode('utf-8') if self.keep else None)


def relay(transport, phone, hermes, budget=None, key='phone-a', timeout=20):
    extra = {} if budget is None else {'budget': budget, 'phone': key}  # without a budget: the relay's own (and chat-121-6's signature)
    return asyncio.wait_for(transport.relay_sockets(phone, hermes, 'default', **extra), timeout)


def header(transport, head, tail, body):
    return bytes([transport.PIECE_HEAD]) + json.dumps({'head': head, 'tail': tail, 'body': body}).encode('utf-8')


HEAD = '{"jsonrpc":"2.0","id":"a1","method":"file.attach","params":{"profile":"default","session_id":"s","name":"x.pdf","data_url":"data:application/pdf;base64,'


def test_a_binary_message_on_a_chat_socket_is_an_invalid_frame(transport):
    # JOINBFIX-121 (REVIEW-JOINB-121 P3-4): a file goes in pieces on a socket of its own (form 4); the chat socket takes text frames only. The superseded
    # form 3 (the pieces on the chat's socket, which no released phone sends: 1.2.0 sends one text frame) is gone, so a binary message there ends the relay at
    # once like any invalid frame, and nothing of it is held or reaches Hermes (REVIEW-121B F2-P3-1: a piece with no header ended it already).
    _, pieces = _phone_file(transport, 3 * 1024, piece=1024)
    cases = {
        'a whole file in pieces': pieces,
        'a header alone': pieces[:1],
        'a piece with no header': [bytes([transport.PIECE_MORE]) + b'{"jsonrpc":"2.0","id":2,"method":"session.info","params":{"profile":"default"}'],
        'a drop (form 3 marker 2)': [bytes([2])],
        'an unknown marker': [bytes([7]) + b'QUJD'],
        'an empty message': [b''],
    }
    for name, messages in cases.items():
        hermes, budget = Hermes(), transport.AttachBudget(4 * MIB)

        async def scenario():
            with pytest.raises(ValueError):
                await relay(transport, Phone(messages, leave=False), hermes, budget, timeout=2)
        asyncio.run(scenario())
        assert (hermes.sent, budget.used, budget.phones) == ([], 0, set()), name


def test_a_file_in_one_text_frame_from_a_1_2_0_phone_still_goes_to_hermes(transport):
    # A guard, not a change: a 1.2.0 phone sends a file as one text frame on the chat's socket (up to MAX_FRAME), and it still reaches Hermes as sent.
    frame = ('{"jsonrpc":"2.0","id":"a1","method":"file.attach","params":{"profile":"default","session_id":"s","name":"x.pdf",'
             '"data_url":"data:application/pdf;base64,' + 'QUJD' * 1024 + '"}}')
    hermes = Hermes()
    asyncio.run(relay(transport, Phone([frame]), hermes))
    assert hermes.sent == [frame]


def test_a_file_frame_is_checked_whole_before_any_of_it_is_held(transport, monkeypatch):
    # The header carries everything but the file: it is checked as a frame (a file method, the profile), against Hermes' limits (the frame, and
    # the file for its method), and so that the file can only go at the end of that method's own field, before the budget is taken or a buffer made.
    made = []
    real = transport.HeldFile

    class Spy(real):
        def __init__(self, *args):
            made.append(args[0])
            super().__init__(*args)
    monkeypatch.setattr(transport, 'HeldFile', Spy)
    caps = {'files': None, 'images': 1000}  # Hermes' caps on one file (image bytes scaled down here)
    monkeypatch.setattr(transport, 'attach_file_limits', lambda: (caps['files'], caps['images']))
    limit = transport.attach_frame_limit()
    tail = '"}}'
    image_head = HEAD.replace('file.attach', 'image.attach_bytes').replace('"data_url":"data:application/pdf;base64,', '"content_base64":"')
    refused_headers = {
        'over the frame limit': header(transport, HEAD, tail, limit - len(HEAD) - len(tail) + 1),
        'over the image cap': header(transport, image_head, tail, 1336 + 4),
        'not a file method': header(transport, HEAD.replace('file.attach', 'session.info'), tail, 8),
        'another profile': header(transport, HEAD.replace('"default"', '"other"'), tail, 8),
        'head ends in a backslash': header(transport, HEAD + '\\', tail, 8),
        'tail starts mid-escape': header(transport, HEAD + '\\', 'n' + tail, 8),
        'tail starts a unicode escape': header(transport, HEAD + '\\u00', '41' + tail, 8),
        'the file in another field': header(transport, HEAD.replace('"name":"x.pdf",', '') + 'QQ==","name":"x', '.pdf' + tail, 8),
        'the file in the wrong field': header(transport, image_head.replace('content_base64', 'data_url'), tail, 8),
        'no body': header(transport, HEAD, tail, 0),
        'a key too many': bytes([transport.PIECE_HEAD]) + json.dumps({'head': HEAD, 'tail': tail, 'body': 8, 'x': 1}).encode(),
        'not json': bytes([transport.PIECE_HEAD]) + b'{"head":',
        'too long': bytes([transport.PIECE_HEAD]) + json.dumps({'head': HEAD + 'A' * transport.HEAD_MAX, 'tail': tail, 'body': 8}).encode(),
    }
    for name, message in refused_headers.items():
        hermes, budget = Hermes(), transport.AttachBudget(64 * MIB)

        async def scenario():
            with pytest.raises(ValueError):
                await file_relay(transport, Phone([CAPS, RESUME, message, bytes([transport.PIECE_LAST]) + b'QUJDRA=='], leave=False), hermes, budget, timeout=2)
        asyncio.run(scenario())
        assert (hermes.sent, made, budget.used, budget.phones) == ([CAPS, RESUME], [], 0, set()), name
    # Accepted as it stands: the image cap allows it, and a file frame at the frame limit exactly.
    assert transport.file_header(header(transport, image_head, tail, 1336), 'default', True, limit)[3] == 1336
    assert transport.file_header(header(transport, HEAD, tail, limit - len(HEAD) - len(tail)), 'default', True, limit)[3] == limit - len(HEAD) - len(tail)
    # Hermes' cap on any one file (256 MiB, scaled down here) is checked on the decoded size the base64 declares.
    caps['files'] = 2000
    assert transport.file_header(header(transport, HEAD, tail, 2668), 'default', True, limit)[3] == 2668
    with pytest.raises(ValueError):
        transport.file_header(header(transport, HEAD, tail, 2672), 'default', True, limit)
    caps['files'] = None

    # The pieces: base64 only, never more than declared, the last one completing it, one file at a time, each piece at most PIECE_MAX.
    frame, pieces = _phone_file(transport, 64)
    over = bytes([transport.PIECE_MORE]) + b'A' * (transport.PIECE_MAX + 1)
    refused_pieces = {
        'a quote in the base64': [pieces[0], bytes([transport.PIECE_LAST]) + b'QUJD"}}x' + b'A' * 56],
        'a backslash in the base64': [pieces[0], bytes([transport.PIECE_LAST]) + b'QUJD\\' + b'A' * 59],
        'more than declared': [pieces[0], bytes([transport.PIECE_LAST]) + b'A' * 65],
        'short of declared': [pieces[0], bytes([transport.PIECE_LAST]) + b'A' * 63],
        'a second header': [pieces[0], pieces[0]],
        'a piece too large': [pieces[0], over],
    }
    for name, messages in refused_pieces.items():
        made.clear()
        hermes, budget = Hermes(), transport.AttachBudget(64 * MIB)

        async def scenario():
            with pytest.raises(ValueError):
                await file_relay(transport, Phone([CAPS, RESUME, *messages], leave=False), hermes, budget, timeout=2)
        asyncio.run(scenario())
        assert (hermes.sent, budget.used, budget.phones) == ([CAPS, RESUME], 0, set()), name
    # The same file whole goes as one text message.
    hermes, budget = Hermes(), transport.AttachBudget(64 * MIB)
    asyncio.run(file_relay(transport, Phone([CAPS, RESUME, *pieces]), hermes, budget))
    assert hermes.sent == [CAPS, RESUME, frame] and budget.used == 0


def test_a_text_frame_over_the_frame_limit_is_refused_unparsed(transport, monkeypatch):
    # Only a file frame in pieces may be larger than MAX_FRAME; the phone sends text frames up to 8 MiB. A larger text frame is refused before it
    # is parsed (since chat-121-5 one up to 384 MiB was parsed whole first).
    parsed = []
    real = json.loads
    monkeypatch.setattr(transport.json, 'loads', lambda raw, **kw: parsed.append(len(raw)) or real(raw, **kw))
    big = '{"jsonrpc":"2.0","id":1,"method":"file.attach","params":{"profile":"default","data_url":"' + 'A' * transport.MAX_FRAME + '"}}'
    hermes = Hermes()

    async def scenario():
        with pytest.raises(ValueError):
            await relay(transport, Phone([big], leave=False), hermes, timeout=2)
    asyncio.run(scenario())
    assert hermes.sent == [] and all(size <= transport.MAX_FRAME for size in parsed)


def test_one_file_at_a_time_per_phone_and_a_budget_across_phones(transport):
    # A file the budget cannot take now (this phone has one in flight, or all phones together are at the budget) is answered "busy" for its own
    # request id, at once, and its file socket ends (the phone's next try opens a new one). Once the file in flight is sent, the budget takes the next one.
    budget = transport.AttachBudget(3 * MIB)
    frame_a, pieces_a = _phone_file(transport, 2 * MIB, ident='a1')
    _, pieces_a2 = _phone_file(transport, 1024, ident='a2')
    frame_b, pieces_b = _phone_file(transport, 2 * MIB, ident='b1')
    busy = lambda ident: json.loads(json.dumps({'jsonrpc': '2.0', 'id': ident, 'error': {'code': 503, 'message': 'Canonical gateway busy', 'data': {'source': 'beacon'}}}))

    async def scenario():
        hold = asyncio.Event()
        hermes_a, hermes_a2, hermes_b, hermes_b2 = Hermes(), Hermes(), Hermes(), Hermes()
        phone_a = Phone([CAPS, RESUME, pieces_a[0], pieces_a[1], hold] + pieces_a[2:])
        first = asyncio.ensure_future(file_relay(transport, phone_a, hermes_a, budget, key='phone-a'))
        while budget.used == 0:
            await asyncio.sleep(0.01)
        # The same phone, on a second socket: busy, its pieces never taken, the socket ends.
        phone_a2 = Phone([CAPS, RESUME, *pieces_a2], leave=False)
        await file_relay(transport, phone_a2, hermes_a2, budget, key='phone-a', timeout=5)
        # Another phone whose file the budget cannot take with phone-a's in flight: busy too.
        phone_b = Phone([CAPS, RESUME, *pieces_b], leave=False)
        await file_relay(transport, phone_b, hermes_b, budget, key='phone-b', timeout=5)
        hold.set()
        await first
        # Phone-a's file is sent and its share of the budget is back: phone-b's next try goes.
        await file_relay(transport, Phone([CAPS, RESUME, *pieces_b]), hermes_b2, budget, key='phone-b')
        return phone_a2, hermes_a, hermes_a2, phone_b, hermes_b, hermes_b2
    phone_a2, hermes_a, hermes_a2, phone_b, hermes_b, hermes_b2 = asyncio.run(scenario())
    assert [json.loads(raw) for raw in phone_a2.got] == [busy('a2')] and hermes_a2.sent == [CAPS, RESUME]
    assert [json.loads(raw) for raw in phone_b.got] == [busy('b1')] and hermes_b.sent == [CAPS, RESUME]
    assert hermes_a.sent == [CAPS, RESUME, frame_a] and hermes_b2.sent == [CAPS, RESUME, frame_b]
    assert (budget.used, budget.phones) == (0, set())


@pytest.mark.parametrize('ending', ['short', 'hermes-fails-mid-message', 'phone-leaves', 'cancelled-mid-piece'])
def test_the_budget_comes_back_however_a_file_ends(transport, monkeypatch, ending):
    # The budget is shared by every socket: one share left behind would refuse every large file until a restart. After each way a file can end,
    # the whole budget is back and a full file goes.
    frame, pieces = _phone_file(transport, 3 * MIB)
    budget = transport.AttachBudget(4 * MIB)
    entered, release = threading.Event(), threading.Event()
    if ending == 'cancelled-mid-piece':
        real = transport.HeldFile.take

        def slow(self, data):
            entered.set()
            release.wait(5)
            return real(self, data)
        monkeypatch.setattr(transport.HeldFile, 'take', slow)

    async def scenario():
        if ending == 'short':
            with pytest.raises(ValueError):
                await file_relay(transport, Phone([CAPS, RESUME] + pieces[:2] + [bytes([transport.PIECE_LAST]) + pieces[2][1:]]), Hermes(), budget)
        elif ending == 'hermes-fails-mid-message':
            with pytest.raises(ConnectionError):
                await file_relay(transport, Phone([CAPS, RESUME, *pieces]), Hermes(fail_after=2), budget)
        elif ending == 'phone-leaves':
            await file_relay(transport, Phone([CAPS, RESUME] + pieces[:2]), Hermes(), budget)
        else:
            task = asyncio.ensure_future(file_relay(transport, Phone([CAPS, RESUME, *pieces]), Hermes(), budget))
            while not entered.is_set():
                await asyncio.sleep(0.01)
            task.cancel()
            with pytest.raises(asyncio.CancelledError):
                await task
            release.set()
        assert (budget.used, budget.phones) == (0, set()), ending
        monkeypatch.undo()
        hermes = Hermes()
        await file_relay(transport, Phone([CAPS, RESUME, *_phone_file(transport, 3 * MIB)[1]]), hermes, budget)
        return hermes
    hermes = asyncio.run(scenario())
    assert hermes.sent == [CAPS, RESUME, frame] and budget.used == 0


STALLED = {'code': 408, 'message': 'File upload stalled', 'data': {'source': 'beacon'}}


def test_a_file_whose_pieces_stop_coming_gives_its_buffer_back(transport, monkeypatch):
    # CHAT-121 follow-up 7: a header whose pieces stopped coming (a phone that went quiet with its socket open, an upload stalled on a dead link) held
    # its reserved buffer, and its share of the budget, until the socket ended. After ATTACH_QUIET_SECONDS with no piece (60 s; scaled down here)
    # both go back and the phone is told for its request id. A file socket carries one file (form 4), so it ends with it, and the file sent again from
    # its header on a new socket goes whole. Follow-up 8 keeps the 60 s for a phone that truly stopped (a slow one sizes its pieces to come well within it).
    assert transport.ATTACH_QUIET_SECONDS == 60
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 1.0, raising=False)
    budget = transport.AttachBudget(4 * MIB)
    frame, pieces = _phone_file(transport, 3 * MIB, ident='a1')

    async def scenario():
        hermes, again = Hermes(), Hermes()
        # Two pieces, then nothing (the phone stays): the relay answers stalled and ends by itself.
        phone = Phone([CAPS, RESUME] + pieces[:2], leave=False)
        began = time.monotonic()
        await file_relay(transport, phone, hermes, budget, timeout=6)
        stalled = (budget.used, set(budget.phones), [json.loads(raw) for raw in phone.got], time.monotonic() - began >= 0.9)
        await file_relay(transport, Phone([CAPS, RESUME] + pieces), again, budget)
        return stalled, hermes, again
    stalled, hermes, again = asyncio.run(scenario())
    assert stalled == (0, set(), [{'jsonrpc': '2.0', 'id': 'a1', 'error': STALLED}], True), stalled
    assert hermes.sent == [CAPS, RESUME] and again.sent == [CAPS, RESUME, frame] and (budget.used, budget.phones) == (0, set())


def test_only_a_piece_keeps_a_file_alive(transport, monkeypatch):
    # A slow file whose pieces keep coming within the quiet time goes whole however long it takes: the quiet time counts from its last piece.
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 1.0, raising=False)
    frame, pieces = _phone_file(transport, 3 * MIB)
    slow, budget = Hermes(), transport.AttachBudget(4 * MIB)
    asyncio.run(file_relay(transport, Phone([CAPS, RESUME, pieces[0], 0.3, pieces[1], 0.3, pieces[2], 0.3, pieces[3]]), slow, budget))
    assert slow.sent == [CAPS, RESUME, frame] and (budget.used, budget.phones) == (0, set())


class SlowHermes(Hermes):
    """Hermes slow to take a text frame (a busy gateway's socket): each one takes 1.5 s to send."""

    async def send(self, raw, text=None):
        if isinstance(raw, str):
            await asyncio.sleep(1.5)
        await super().send(raw, text)


def test_the_relays_own_slowness_never_stalls_a_file(transport, monkeypatch):
    # Only time spent waiting for the phone counts. A capabilities frame and a resume that Hermes takes longer than the quiet time each to accept leave
    # the file's header and pieces waiting on the socket; they are taken and the file goes whole, not answered stalled.
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 1.0, raising=False)
    frame, pieces = _phone_file(transport, 3 * MIB)
    hermes, budget, phone = SlowHermes(), transport.AttachBudget(4 * MIB), Phone([CAPS, RESUME] + pieces)
    asyncio.run(file_relay(transport, phone, hermes, budget))
    assert phone.got == [] and hermes.sent == [CAPS, RESUME, frame] and (budget.used, budget.phones) == (0, set())


def test_a_stalled_file_ends_its_own_socket_on_a_real_server(transport, monkeypatch):
    # The quiet time gives up on a pending receive from the phone's socket. On the server Hermes' dashboard runs (uvicorn with its default websocket
    # implementation, Starlette's WebSocket) a file socket whose file stalls after one piece, around its deadline, answers stalled once and the relay
    # ends by itself, in each of 8 rounds (a file socket carries one file, so each round is a new socket); nothing but its capabilities and resume
    # reaches Hermes, and the budget is whole after each. (Form 3's version of this ran 20 rounds on one chat socket, with a burst of text frames around
    # the deadline; no frame but its own may come on a file socket now.)
    import uvicorn
    from starlette.applications import Starlette
    from starlette.routing import WebSocketRoute
    from websockets.asyncio.client import connect
    from hermes_cli.web_server_boundary import _DESKTOP_ATTACHMENT_WS_MAX_BYTES
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 0.1, raising=False)
    budget, hermes, done = transport.AttachBudget(8 * MIB), [], threading.Semaphore(0)

    async def endpoint(socket):
        await socket.accept()
        upstream = Hermes()
        hermes.append(upstream)
        try:
            await transport.relay_sockets(socket, upstream, 'default', budget=budget, phone='phone-a', file_socket=True)
        finally:
            done.release()
    server = uvicorn.Server(uvicorn.Config(Starlette(routes=[WebSocketRoute('/phone', endpoint)]), host='127.0.0.1', port=0, log_level='warning',
                                           lifespan='off', ws_max_size=_DESKTOP_ATTACHMENT_WS_MAX_BYTES))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        until = time.monotonic() + 10
        while not server.started and time.monotonic() < until:
            time.sleep(0.02)
        port = server.servers[0].sockets[0].getsockname()[1]

        async def scenario():
            answers = []
            for round_ in range(8):
                _, pieces = _phone_file(transport, 2 * MIB, ident=f'r{round_}')
                async with connect(f'ws://127.0.0.1:{port}/phone', compression=None, max_size=None, open_timeout=5) as phone:
                    await phone.send(CAPS)
                    await phone.send(RESUME)
                    await phone.send(pieces[0])
                    await phone.send(pieces[1])
                    await asyncio.sleep(0.07 + round_ * 0.004)
                    while True:
                        message = await asyncio.wait_for(phone.recv(), 5)
                        if isinstance(message, str):
                            answers.append(json.loads(message))
                            break
                assert await asyncio.to_thread(done.acquire, True, 10)
            return answers
        answers = asyncio.run(scenario())
    finally:
        server.should_exit = True
        thread.join(10)
    assert answers == [{'jsonrpc': '2.0', 'id': f'r{n}', 'error': STALLED} for n in range(8)]
    assert len(hermes) == 8 and all(upstream.sent == [CAPS, RESUME] for upstream in hermes)
    assert (budget.used, budget.phones) == (0, set())


def test_each_piece_is_acknowledged_with_the_base64_held_and_its_request_id(transport):
    # CHAT-121 follow-up 8 (form 3's acknowledgments, on the file socket of form 4): after each piece the relay tells the phone how much of the file's
    # base64 it holds, for that frame's request id, the last piece included, before Hermes gets the frame. The phone sizes its pieces and its sends from
    # these. The bytes are the ones the phone's AttachmentPiecesTest reads (04, the count as 8 bytes big-endian, the id in UTF-8).
    assert transport.ATTACH_PIECES == 4 and transport.PIECE_ACK == 4
    frame, pieces = _phone_file(transport, 3 * MIB)

    async def scenario():
        held = [asyncio.Event() for _ in range(3)]
        hermes = Hermes()
        # Each next piece waits for the last one's acknowledgment, as the phone on a slow link does.
        phone = Phone([CAPS, RESUME, pieces[0], pieces[1], held[0], pieces[2], held[1], pieces[3], held[2]], on_ack=lambda count: held[count // MIB - 1].set())
        await file_relay(transport, phone, hermes, transport.AttachBudget(4 * MIB))
        return phone, hermes
    phone, hermes = asyncio.run(scenario())
    assert phone.acks == [('a1', MIB), ('a1', 2 * MIB), ('a1', 3 * MIB)] and hermes.sent == [CAPS, RESUME, frame] and phone.got == []
    small, pieces = _phone_file(transport, 16 * 1024, ident='f1', piece=16 * 1024)
    phone, hermes = Phone([CAPS, RESUME, *pieces]), Hermes()
    asyncio.run(file_relay(transport, phone, hermes))
    assert phone.ack_bytes == [bytes.fromhex('0400000000000040006631')] and hermes.sent == [CAPS, RESUME, small]


class BusyPhone(Phone):
    """A phone whose side of the socket is held by a frame from Hermes it is slow to take (a long reply on a weak link)."""

    def __init__(self, messages, freed):
        super().__init__(messages, leave=False)
        self.freed = freed

    async def send_text(self, raw):
        await self.freed.wait()
        await super().send_text(raw)


class SpeakingHermes(Hermes):
    """Hermes sends the phone one frame first."""

    def __init__(self, frame):
        super().__init__()
        self.frame = frame

    async def recv(self):
        if self.frame is not None:
            frame, self.frame = self.frame, None
            return frame
        await asyncio.Event().wait()


def test_a_phone_busy_with_hermes_frames_never_holds_the_relays_receive(transport, monkeypatch):
    # The acknowledgments and the answers wait for the phone's side of the socket, never the relay's receive: while an answer from Hermes (the resume's,
    # on a file socket: its events and questions are not sent on it) holds that side, the file's pieces are still taken and a file whose pieces stopped
    # still gives its buffer back at the quiet time.
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 1.0, raising=False)
    _, pieces = _phone_file(transport, 3 * MIB)
    event = '{"jsonrpc":"2.0","id":"r1","result":{"session_id":"s","stored_session_id":"s"}}'

    async def scenario():
        freed, budget = asyncio.Event(), transport.AttachBudget(4 * MIB)
        phone = BusyPhone([CAPS, RESUME] + pieces[:3], freed)
        task = asyncio.ensure_future(file_relay(transport, phone, SpeakingHermes(event), budget))
        await asyncio.sleep(0.4)
        taking = (budget.used > 0, list(phone.acks), list(phone.got))
        until = time.monotonic() + 6
        while budget.used and time.monotonic() < until:
            await asyncio.sleep(0.05)
        released = (budget.used, set(budget.phones), task.done())
        freed.set()
        until = time.monotonic() + 3
        while len(phone.got) < 2 and time.monotonic() < until:
            await asyncio.sleep(0.05)
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
        return taking, released, phone
    taking, released, phone = asyncio.run(scenario())
    assert taking == (True, [], []), taking
    assert released == (0, set(), False), released
    assert phone.got[0] == event and json.loads(phone.got[1]) == {'jsonrpc': '2.0', 'id': 'a1', 'error': STALLED}


def test_a_large_file_is_held_once_and_never_blocks_the_event_loop(transport):
    # REVIEW-121B's measure_pieces.py, inverted, through the relay itself: a 256 MiB file held about 705 MiB (2x its 347 MiB frame) and blocked
    # the event loop for 1.1 s after the last piece (decode, parse twice, encode). Now the file's base64 is held once and copied piece by piece on a
    # worker thread, and Hermes gets it in fragments from that buffer: no decode, no parse of the frame. Measured on a 64 MiB base64 body.
    size = 64 * MIB
    frame, first = _phone_file(transport, 1024)
    head = json.loads(first[0][1:])['head']
    chunk = b'QUJDRA+/' * (MIB // 8)

    def messages():
        yield CAPS
        yield RESUME
        yield header(transport, head, '"}}', size)
        for at in range(0, size, MIB):
            yield (lambda last=(at + MIB >= size): bytes([transport.PIECE_LAST if last else transport.PIECE_MORE]) + chunk)
    expected = hashlib.sha256(head.encode() + chunk * (size // MIB) + b'"}}').hexdigest()

    async def with_heartbeat():
        gaps, stop = [], asyncio.Event()

        async def beat():
            last = time.perf_counter()
            while not stop.is_set():
                await asyncio.sleep(0.002)
                now = time.perf_counter()
                gaps.append(now - last)
                last = now
        beating = asyncio.ensure_future(beat())
        hermes = Hermes(keep=False)
        await file_relay(transport, Phone(messages()), hermes, transport.AttachBudget(transport.attach_budget_bytes()), timeout=60)
        stop.set()
        await beating
        return hermes, max(gaps)
    hermes, longest = asyncio.run(with_heartbeat())
    assert hermes.digests == [expected] and hermes.sizes == [len(head) + size + 3]
    tracemalloc.start()
    try:
        hermes = Hermes(keep=False)
        asyncio.run(file_relay(transport, Phone(messages()), hermes, transport.AttachBudget(transport.attach_budget_bytes()), timeout=60))
        peak = tracemalloc.get_traced_memory()[1]
    finally:
        tracemalloc.stop()
    print(f"\n[F2-P3-1 inverted] {size // MIB} MiB base64 body: python peak {peak / MIB:.0f} MiB ({peak / size:.2f}x), longest event-loop gap "
          f"{longest * 1000:.0f} ms, {hermes.fragments[0]} fragments")
    assert hermes.digests == [expected]
    assert peak <= size * 1.1 + 8 * MIB, peak
    assert longest < 0.25, longest


def test_a_file_frame_reaches_a_real_websocket_server_whole(transport):
    # The fragments are joined by the server, as Hermes' dashboard serves its socket: uvicorn with its default websocket implementation and
    # ws_max_size = _DESKTOP_ATTACHMENT_WS_MAX_BYTES (hermes_cli/web_server.py), the websockets client the relay uses (no compression, as the
    # socket route opens it). Through a file socket's relay the server gets the capabilities and resume frames, then one text message, equal to the frame.
    import uvicorn
    from starlette.applications import Starlette
    from starlette.routing import WebSocketRoute
    from websockets.asyncio.client import connect
    from hermes_cli.web_server_boundary import _DESKTOP_ATTACHMENT_WS_MAX_BYTES
    received, got = [], threading.Event()

    async def endpoint(socket):
        await socket.accept()
        while len(received) < 3:
            received.append(await socket.receive_text())
        got.set()
        while (await socket.receive())['type'] != 'websocket.disconnect':
            pass
    server = uvicorn.Server(uvicorn.Config(Starlette(routes=[WebSocketRoute('/ws', endpoint)]), host='127.0.0.1', port=0, log_level='warning',
                                           lifespan='off', ws_max_size=_DESKTOP_ATTACHMENT_WS_MAX_BYTES))
    thread = threading.Thread(target=server.run, daemon=True)
    thread.start()
    try:
        until = time.monotonic() + 10
        while not server.started and time.monotonic() < until:
            time.sleep(0.02)
        port = server.servers[0].sockets[0].getsockname()[1]
        frame, pieces = _phone_file(transport, 32 * MIB)

        async def scenario():
            upstream = await connect(f'ws://127.0.0.1:{port}/ws', compression=None, max_size=transport.MAX_FRAME, open_timeout=5)
            try:
                await transport.relay_sockets(Phone([CAPS, RESUME, *pieces]), upstream, 'default', file_socket=True)
                assert await asyncio.to_thread(got.wait, 10)
            finally:
                await upstream.close()
        asyncio.run(scenario())
    finally:
        server.should_exit = True
        thread.join(10)
    assert received == [CAPS, RESUME, frame]


def test_a_confirmation_that_times_out_gives_its_worker_back(transport):
    # REVIEW-121B F2-P3-2 (its proof, inverted): a confirmation that timed out kept its worker until its thread ended, so the socket's next one was
    # refused busy (skipped). With a spare thread it gives the worker back: the next call runs while the first thread is still stuck.
    workers = transport.BoundedWorkers(1, spare=1)
    stuck = threading.Event()

    def locked_store():  # a confirmation stuck behind a locked mobile store (busy timeout 15 s)
        stuck.wait(5)
        return True

    async def scenario():
        with pytest.raises(HTTPException) as first:
            await transport.bounded_call(workers, locked_store, timeout=0.2)
        began = time.monotonic()
        nxt = await transport.bounded_call(workers, lambda: True, wait=0.3)
        took = time.monotonic() - began
        still = not stuck.is_set()
        # Past the spare threads, a timed-out call counts until its thread ends, as before: the pool never runs more threads than it has.
        with pytest.raises(HTTPException):
            await transport.bounded_call(workers, locked_store, timeout=0.2)
        with pytest.raises(HTTPException) as over:
            await transport.bounded_call(workers, lambda: True, wait=0.3)
        stuck.set()
        await asyncio.sleep(0.2)
        after = await transport.bounded_call(workers, lambda: True, wait=0.3)
        return first.value, nxt, took, still, over.value, after
    try:
        first, nxt, took, still, over, after = asyncio.run(scenario())
    finally:
        stuck.set()
        workers.close()
    assert first.detail == transport.CANONICAL_UNAVAILABLE
    assert nxt is True and still and took < 0.1
    assert over.detail == transport.CANONICAL_BUSY and after is True
    # The admissions' pool has no spare threads and is unchanged (REVIEW-121B's proof holds there).
    assert transport.BoundedWorkers(4).spare == 0


def test_the_socket_route_confirms_on_after_every_socket_timed_out_one(admission_harness, monkeypatch):
    # The route's own pool: every socket's confirmation stuck past its time (a mobile store locked past 6 s, scaled down here), and the next
    # confirmation still runs at once, while those threads are still stuck. Before, it waited 2 s for a worker and was skipped busy.
    _, client, _, transport, _, device, created = admission_harness
    monkeypatch.setattr(transport, 'BOUNDED_SECONDS', 0.3)
    stuck, outcome = threading.Event(), {}

    async def relay(socket, upstream, profile, mobile=None, binding=None, run=None, confirm=None, **_):
        def locked_store():
            stuck.wait(10)
            return True
        try:
            for _ in range(transport.MAX_SOCKETS):
                try:
                    await confirm(locked_store)
                except HTTPException as error:
                    outcome.setdefault('timed_out', []).append(error.detail)
            began = time.monotonic()
            outcome['next'] = await confirm(lambda: True)
            outcome['took'] = time.monotonic() - began
            outcome['still_stuck'] = not stuck.is_set()
        except Exception as error:
            outcome['error'] = repr(error)
        finally:
            stuck.set()
    monkeypatch.setattr(transport, 'relay_sockets', relay)
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        socket.receive_json()
        until = time.monotonic() + 10
        while 'next' not in outcome and 'error' not in outcome and time.monotonic() < until:
            time.sleep(0.02)
    assert outcome.get('timed_out') == [transport.CANONICAL_UNAVAILABLE] * transport.MAX_SOCKETS, outcome
    assert outcome.get('next') is True and outcome['still_stuck'] and outcome['took'] < 0.5, outcome


# CHAT-121 follow-up 9 (form 4): a file goes on a socket of its own, so the chat's socket never carries a file's pieces.
CAPS = '{"jsonrpc":"2.0","id":"c1","method":"client.capabilities","params":{"server_requests":false}}'
RESUME = ('{"jsonrpc":"2.0","id":"r1","method":"session.resume","params":{"profile":"default","session_id":"s","omit_messages":true,'
          '"source":"android"}}')


class Answering(Hermes):
    """Hermes as its gateway answers a file socket's frames: each answer comes after one of the session's events and one of its questions (an approval),
    which reach the phone on the chat's socket only."""

    def __init__(self):
        super().__init__()
        self.out = asyncio.Queue()

    async def recv(self):
        return await self.out.get()

    async def send(self, raw, text=None):
        await super().send(raw, text)
        frame = json.loads(self.sent[-1])
        if frame.get('method') == 'client.capabilities':
            return
        self.out.put_nowait(json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'message.delta', 'session_id': 's', 'payload': {'text': 'x'}}}))
        self.out.put_nowait(json.dumps({'jsonrpc': '2.0', 'id': 'srq-1', 'method': 'approval', 'params': {'session_id': 's'}}))
        result = {'session_id': 's', 'stored_session_id': 's'} if frame.get('method') == 'session.resume' else {'attached': True}
        self.out.put_nowait(json.dumps({'jsonrpc': '2.0', 'id': frame['id'], 'result': result}))


def file_relay(transport, phone, hermes, budget=None, ended=None, timeout=20, key='phone-a'):
    return asyncio.wait_for(transport.relay_sockets(phone, hermes, 'default', budget=budget or transport.AttachBudget(4 * MIB), phone=key,
                                                    file_socket=True, ended=ended), timeout)


def test_a_file_socket_takes_one_resume_and_one_file_and_ends_once_hermes_has_answered_it(transport):
    # The phone asks a socket of the file's own (its ticket says "purpose": "file"), attaches it to the chat's session (Hermes stages a file only for
    # a session the connection is attached to) and sends the file in acknowledged pieces. Only Hermes' answers come back on it: the session's events
    # and questions reach the phone on the chat's socket. Once Hermes has answered the file the relay ends the socket by itself (the phone stays here).
    assert transport.ATTACH_PIECES == 4
    frame, pieces = _phone_file(transport, 3 * 1024, piece=1024)
    hermes = Answering()
    phone = Phone([CAPS, RESUME, *pieces], leave=False)
    asyncio.run(file_relay(transport, phone, hermes))
    assert hermes.sent == [CAPS, RESUME, frame]
    got = [json.loads(raw) for raw in phone.got]
    assert [answer['id'] for answer in got] == ['r1', 'a1'] and all('method' not in answer for answer in got), got
    assert phone.acks[-1] == ('a1', 3 * 1024)


def test_a_file_socket_takes_nothing_but_its_session_and_its_one_file(transport):
    # Anything else on a file socket ends it like any invalid frame, and none of it reaches Hermes: a turn, a file before the socket is attached to a
    # session, a second resume, a file for another session, a frame between its pieces, a second file.
    frame, pieces = _phone_file(transport, 2048, piece=1024)
    other = _phone_file(transport, 1024, head=HEAD.replace('"session_id":"s"', '"session_id":"other"'))[1]
    submit = '{"jsonrpc":"2.0","id":"p1","method":"prompt.submit","params":{"profile":"default","session_id":"s","text":"hi","submission_id":"x"}}'
    cases = {
        'a turn': ([CAPS, RESUME, submit], [CAPS, RESUME]),
        'a file before its resume': ([CAPS, *pieces], [CAPS]),
        'a second resume': ([CAPS, RESUME, RESUME], [CAPS, RESUME]),
        'a file for another session': ([CAPS, RESUME, *other], [CAPS, RESUME]),
        'a frame between its pieces': ([CAPS, RESUME, pieces[0], CAPS, *pieces[1:]], [CAPS, RESUME]),
        'a second file': ([CAPS, RESUME, *pieces, *pieces], [CAPS, RESUME, frame]),
    }
    for name, (messages, reached) in cases.items():
        hermes = Hermes()

        async def scenario():
            with pytest.raises(ValueError):
                await file_relay(transport, Phone(messages, leave=False), hermes, timeout=5)
        asyncio.run(scenario())
        assert hermes.sent == reached, name


def test_a_file_socket_frees_itself_when_nothing_comes_when_its_file_stalls_and_when_the_phone_opens_a_newer_one(transport, monkeypatch):
    budget = transport.AttachBudget(4 * MIB)
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 0.3)
    # Opened, and nothing came: it ends after the quiet time.
    asyncio.run(file_relay(transport, Phone([], leave=False), Hermes(), budget=budget, timeout=5))
    # Its pieces stopped: answered "stalled" (the phone's next try opens a new socket), its buffer given back, and it ends.
    _, pieces = _phone_file(transport, 4096, piece=1024)
    phone = Phone([CAPS, RESUME, pieces[0], pieces[1]], leave=False)
    asyncio.run(file_relay(transport, phone, Hermes(), budget=budget, timeout=5))
    assert json.loads(phone.got[-1])['error']['message'] == transport.ATTACH_STALLED
    assert (budget.used, budget.phones) == (0, set())
    # The phone opened a newer file socket (its try after this one failed on its side): the route ends this one at once, and its file's budget goes back.
    monkeypatch.setattr(transport, 'ATTACH_QUIET_SECONDS', 60)

    async def newer():
        ended = asyncio.Event()
        task = asyncio.ensure_future(file_relay(transport, Phone([CAPS, RESUME, pieces[0], pieces[1]], leave=False), Hermes(), budget=budget, ended=ended,
                                                timeout=5))
        while budget.used == 0:
            await asyncio.sleep(0.02)
        ended.set()
        await task
    asyncio.run(newer())
    assert (budget.used, budget.phones) == (0, set())
