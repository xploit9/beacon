"""Authenticated, profile-confined access to the existing gateway transport."""
import asyncio
import contextvars
from collections import deque
from contextlib import asynccontextmanager
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import importlib.util
import ipaddress
import json
import logging
import os
from pathlib import Path
import secrets
import socket
import sys
import threading
import time
from urllib.parse import quote, urlsplit
import anyio
from fastapi import APIRouter, HTTPException, Request, WebSocket
from fastapi.responses import JSONResponse, Response

MAX_FRAME = 8 * 1024 * 1024
# A file frame from the phone (file.attach, image.attach_bytes) carries the whole file as one base64 data URL, as Hermes' own Desktop sends it, and
# Hermes takes such frames up to its own WebSocket limit (CHAT-121 follow-up 4). Every other frame, and every frame from Hermes, keeps MAX_FRAME.
ATTACH_METHODS = frozenset({'file.attach', 'image.attach_bytes'})
# OkHttp, the phone's socket client, sends at most 16 MiB in one message (okhttp3.internal.ws.RealWebSocket.MAX_QUEUE_SIZE: a larger one closes
# the socket). So a file frame comes in pieces, binary messages whose first byte says what they are (the ticket's attach_pieces, 4), on a socket
# of the file's own (form 4, below); a binary message on a chat socket is an invalid frame (the pieces are not taken there):
# - PIECE_HEAD first: the frame's text before and after the file's base64 ("head", "tail") and the base64's length ("body"), as JSON. Everything
#   is checked on it before one byte of the file is held (file_header): the frame without the file, its size against Hermes' limits, one file at a
#   time per phone and a memory budget across phones (AttachBudget). A file the budget cannot take now is answered "busy" and its pieces dropped;
# - then the base64 alone, in PIECE_MORE pieces and a PIECE_LAST, each checked and copied on a worker thread into the one buffer the file gets;
# One file at a time per socket, and nothing may come between its pieces.
# Hermes gets the whole frame as one text message, sent in fragments from that buffer (never decoded or parsed whole: REVIEW-121B F2-P3-1).
# Acknowledgments (CHAT-121 follow-up 8): after each piece the relay tells the phone how much of the file's base64 it holds (PIECE_ACK, a binary message:
# the byte 4, that count as 8 bytes big-endian, then the frame's request id in UTF-8). A phone on a slow link sizes its pieces from those (a few
# seconds of its measured speed each, 16 KiB at least) and keeps about that much at most on the way: Hermes' dashboard pings the socket off
# loopback (20 s + 20 s), and a phone's pong waits behind everything it already sent, so a 1 MiB piece at 2 KB/s had the socket closed at 40 s
# (fu8_measure_link.py). The relay's receive sees only whole messages (ASGI), so it cannot count a piece's bytes as they come.
# Form 4 (CHAT-121 follow-up 9): a file goes on a socket of its own, never on the chat's, so the chat socket's pongs never wait behind a file's data
# and a file that cannot get through ends only its own socket. The phone asks a ticket with {"purpose": "file"}; that socket carries
# client.capabilities, one session.resume (Hermes stages a file only for a session the connection is attached to: gateway/session_ancillary.py
# attach_file, session_images.py attach_bytes), then one file frame in pieces for that session, and it ends once Hermes has answered the file, the
# file stalled or was refused, or nothing came in ATTACH_QUIET_SECONDS. Hermes' events and questions are not sent on it (the chat socket has them).
# File sockets have their own bound (MAX_FILE_SOCKETS) beside MAX_SOCKETS, and a phone's newer file socket ends its older one (the phone sends one file at
# a time: NativeGateway.attachFile). Form 3, the same pieces on the chat's socket, is gone (JOINBFIX-121): no released phone sent it (1.2.0 sends one text
# frame), and its marker 2 (PIECE_DROP) with it.
PIECE_MORE, PIECE_LAST, PIECE_HEAD, PIECE_ACK = 0, 1, 3, 4
ATTACH_PIECES = 4
# The largest piece of a file's base64 (the phone sends 1 MiB), and of a header (the frame's text without the file).
PIECE_MAX = 4 * 1024 * 1024
HEAD_MAX = 64 * 1024
# The fragments a file frame goes to Hermes in.
FRAGMENT_BYTES = 1024 * 1024
# The memory every phone's file frames may hold at once (one file per phone): 512 MiB, or one frame at Hermes' own limit when that is larger.
ATTACH_BUDGET_FLOOR = 512 * 1024 * 1024
# The params field each file method carries its base64 in.
ATTACH_FIELDS = {'file.attach': 'data_url', 'image.attach_bytes': 'content_base64'}
_BASE64 = b'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='


def attach_frame_limit():
    """The largest file frame Hermes' own socket takes: hermes_cli.web_server_boundary._DESKTOP_ATTACHMENT_WS_MAX_BYTES (384 MiB, "Desktop
    file.attach sends a whole base64 data URL in one JSON-RPC frame"). A Hermes without it keeps MAX_FRAME."""
    try:
        from hermes_cli.web_server_boundary import _DESKTOP_ATTACHMENT_WS_MAX_BYTES as limit
    except ImportError:
        return MAX_FRAME
    return limit if type(limit) is int and limit > MAX_FRAME else MAX_FRAME


def attach_file_limits():
    """Hermes' own caps on one attached file, (any file, image bytes): tui_gateway.prompt_attachments._ATTACHMENT_MAX_BYTES (256 MiB, file.attach)
    and _ATTACH_BYTES_MAX_BYTES (25 MiB, image.attach_bytes). None where this Hermes has none; the phone then goes by the frame limit."""
    try:
        from tui_gateway.prompt_attachments import _ATTACHMENT_MAX_BYTES as files, _ATTACH_BYTES_MAX_BYTES as images
    except ImportError:
        return None, None
    return tuple(value if type(value) is int and value > 0 else None for value in (files, images))


def attach_budget_bytes():
    return max(ATTACH_BUDGET_FLOOR, attach_frame_limit())


class AttachBudget:
    """The file frames held at once: at most [limit] bytes across every phone, and one file per phone ([take] says False when either is full)."""

    def __init__(self, limit):
        self.limit, self.used, self.phones, self.lock = limit, 0, set(), threading.Lock()

    def take(self, phone, size):
        with self.lock:
            if phone in self.phones or self.used + size > self.limit:
                return False
            self.phones.add(phone)
            self.used += size
            return True

    def give_back(self, phone, size):
        with self.lock:
            self.phones.discard(phone)
            self.used -= size


def file_header(data, profile, capabilities_seen, limit):
    """The checked header (PIECE_HEAD) of a file frame: (the frame's id, head bytes, tail bytes, the base64's length). It is the frame with the
    file's base64 taken out, and is checked as every frame is (closed JSON, the profile, a file method), against Hermes' limits for the whole
    frame and for the file, and so that the base64 goes exactly at the end of that method's own field (a marker put there must land there and
    change nothing else, so a header cannot end inside an escape or put the file anywhere else). Anything else is an invalid frame."""
    if len(data) - 1 > HEAD_MAX:
        raise ValueError('Invalid frame')
    try:
        header = json.loads(bytes(data[1:]).decode('utf-8'), object_pairs_hook=_pairs)
    except (ValueError, RecursionError):
        raise ValueError('Invalid frame') from None
    if not isinstance(header, dict) or set(header) != {'head', 'tail', 'body'}:
        raise ValueError('Invalid frame')
    head, tail, body = header['head'], header['tail'], header['body']
    if not isinstance(head, str) or not isinstance(tail, str) or type(body) is not int or body <= 0:
        raise ValueError('Invalid frame')
    head_bytes, tail_bytes = head.encode('utf-8'), tail.encode('utf-8')
    if len(head_bytes) + body + len(tail_bytes) > limit:
        raise ValueError('Invalid frame')
    validate_frame(head + tail, profile, capabilities_seen, MAX_FRAME, binary=True)
    try:
        empty = json.loads(head + tail, object_pairs_hook=_pairs)
        marked = json.loads(head + 'QUJD' + tail, object_pairs_hook=_pairs)
    except (ValueError, RecursionError):
        raise ValueError('Invalid frame') from None
    method = empty['method']
    field = ATTACH_FIELDS[method]
    value = empty['params'].get(field)
    if not isinstance(value, str) or not isinstance(marked, dict) or not isinstance(marked.get('params'), dict) or \
            marked['params'].get(field) != value + 'QUJD':
        raise ValueError('Invalid frame')
    marked['params'][field] = value
    if marked != empty:
        raise ValueError('Invalid frame')
    files, images = attach_file_limits()
    cap = files if method == 'file.attach' else images
    if cap is not None and body > (cap + 2) // 3 * 4:
        raise ValueError('Invalid frame')
    return empty.get('id'), head_bytes, tail_bytes, body


# A file frame the budget cannot take is answered "busy" and its file socket ends; the phone's next try opens a new one.
# A held file whose next piece has not come in this long (a phone gone quiet, an upload stalled on a dead link) gives its buffer and its share of
# the budget back, and is answered ATTACH_STALLED so the phone stops and sends the file again from its header on a new socket. Only time spent waiting for the
# phone counts: the relay's own work between its receives (an admission's preparation, a frame Hermes is slow to take) never stalls a file. Hermes'
# own uploads have no such timeout to match (its staged upload route takes a whole spooled request; its socket pings, 20 s + 20 s off loopback,
# find a dead peer only).
ATTACH_QUIET_SECONDS = 60
ATTACH_STALLED = 'File upload stalled'


class HeldFile:
    """One file frame being received: its header's head and tail, and one buffer of exactly the declared length for the base64."""

    def __init__(self, ident, head, tail, size):
        self.ident, self.head, self.tail = ident, head, tail
        self.body, self.filled = bytearray(size), 0
        self.total = len(head) + size + len(tail)
        self.quiet = 0.0  # seconds spent waiting for the phone since its header or its last piece (ATTACH_QUIET_SECONDS)

    def take(self, data):
        """Copies one piece's base64 into place (run on a worker thread). Anything but base64, or more than the header declared, is invalid."""
        size = len(data) - 1
        if self.filled + size > len(self.body) or len(data.translate(None, _BASE64)) != 1:
            raise ValueError('Invalid frame')
        self.body[self.filled:self.filled + size] = memoryview(data)[1:]
        self.filled += size

    async def fragments(self):
        """The whole frame for Hermes, as one text message in fragments straight from the buffer (head, base64, tail)."""
        yield self.head
        view = memoryview(self.body)
        for at in range(0, len(view), FRAGMENT_BYTES):
            await asyncio.sleep(0)
            yield view[at:at + FRAGMENT_BYTES]
        yield self.tail


def subject_digest(session):
    from gateway.session_identity import authenticated_subject
    values = [getattr(session, name, None) for name in ('provider', 'issuer', 'user_id')]
    if not all(isinstance(value, str) for value in values) or not values[0] or not values[2]:
        raise ValueError('Verified identity required')
    subject = authenticated_subject(dict(zip(('provider', 'issuer', 'user_id'), values)))
    return hashlib.sha256(subject.encode('utf-8')).hexdigest()


def home_digest(home):
    return hashlib.sha256(os.path.normcase(str(Path(home).resolve())).encode('utf-8')).hexdigest()


def validate_endpoint(discovery, home):
    endpoint = discovery.endpoint
    if discovery.state != 'ready' or endpoint is None or home_digest(endpoint.profile_id) != home_digest(home):
        raise ValueError('Gateway unavailable')
    if (not isinstance(endpoint.instance_id, str) or not endpoint.instance_id or
            type(endpoint.authority_epoch) is not int or endpoint.authority_epoch <= 0 or
            endpoint.runtime_protocol != 1):
        raise ValueError('Gateway unavailable')
    try:
        origin = urlsplit(endpoint.api_origin)
        if (origin.scheme not in ('http', 'https') or not origin.hostname or not origin.port or
                origin.username is not None or origin.password is not None or origin.path or origin.query or
                origin.fragment or not ipaddress.ip_address(origin.hostname).is_loopback):
            raise ValueError()
    except (TypeError, ValueError):
        raise ValueError('Gateway unavailable') from None
    return endpoint


def authorize_request(request, mobile, dashboard_identity, enforce, profile_names, check_device):
    enforce(request)
    if (getattr(request.state, 'hosted_http_authenticated', False) is True or
            getattr(request.state,'native_http_principal',None) is not None):
        raise HTTPException(501, 'Plugin socket unavailable for this profile')
    owner, provider = dashboard_identity(request)
    session = getattr(request.state, 'session', None)
    if (getattr(session, 'user_id', None) != owner or getattr(session, 'provider', None) != provider):
        raise HTTPException(401, 'Verified native session required')
    try:
        subject_digest(session)
    except ValueError:
        raise HTTPException(401, 'Verified native session required') from None
    cfg = mobile.settings()
    if not owner or not provider or cfg.get('owner_id') != owner or cfg.get('owner_provider') != provider:
        raise HTTPException(403, 'Owner not authorized')
    profiles = cfg['profiles'] if 'profiles' in cfg else profile_names()
    if (not isinstance(profiles, list) or not 1 <= len(profiles) <= 64 or
            any(not isinstance(p, str) or not p.strip() or len(p) > 80 for p in profiles) or
            len(set(profiles)) != len(profiles)):
        raise HTTPException(403, 'Profile configuration invalid')
    selected = request.query_params.getlist('beacon_profile')
    other = request.query_params.getlist('profile')
    if (set(request.query_params) - {'profile', 'beacon_profile'} or len(selected) != 1 or
            len(other) > 1 or (other and other[0] != selected[0]) or selected[0] not in profiles):
        raise HTTPException(403, 'Profile not authorized')
    profile = selected[0]
    check_device(request, owner, provider, profile)
    return owner, provider, profile


class TicketCache:
    def __init__(self, capacity=128, ttl=20, clock=time.monotonic):
        self.capacity, self.ttl, self.clock = capacity, ttl, clock
        self.lock, self.items = threading.Lock(), {}

    def issue(self, value, loop=None):
        with self.lock:
            now = self.clock()
            for key in [key for key, entry in self.items.items() if entry[0] <= now]:
                _, _, timer = self.items.pop(key)
                if timer is not None:
                    timer.cancel()
            if len(self.items) >= self.capacity:
                raise ValueError('Gateway busy')
            ticket = secrets.token_urlsafe(32)
            timer = loop.call_later(self.ttl, self.consume, ticket) if loop is not None else None
            self.items[ticket] = (now + self.ttl, value, timer)
            return ticket

    def consume(self, ticket):
        with self.lock:
            entry = self.items.pop(ticket, None)
            if entry and entry[2] is not None:
                entry[2].cancel()
            return entry[1] if entry and entry[0] > self.clock() else None

    def replace(self, ticket, value):
        with self.lock:
            entry = self.items.get(ticket)
            if not entry or entry[0] <= self.clock():
                self.items.pop(ticket, None)
                if entry and entry[2] is not None:
                    entry[2].cancel()
                return False
            self.items[ticket] = (entry[0], value, entry[2])
            return True

    def remaining(self, ticket):
        with self.lock:
            entry = self.items.get(ticket)
            return int(entry[0] - self.clock()) if entry else 0

    def deadline(self,ticket):
        with self.lock:
            entry=self.items.get(ticket)
            return entry[0] if entry else 0

    def clear(self):
        with self.lock:
            for _, _, timer in self.items.values():
                if timer is not None:
                    timer.cancel()
            self.items.clear()


def _pairs(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Ambiguous frame')
        result[key] = value
    return result


def validate_frame(raw, profile, capabilities_seen, attach_limit=MAX_FRAME, binary=False):
    """[attach_limit]: the largest file frame taken (attach_frame_limit()); [binary]: the frame came as UTF-8 bytes, which only a file frame
    may (the phone builds a large one once, as bytes). Everything else is checked as before."""
    if not isinstance(raw, str) or len(raw) > max(MAX_FRAME, attach_limit):  # never fewer bytes than characters: refused without encoding it
        raise ValueError('Invalid frame')
    size = len(raw) if raw.isascii() else len(raw.encode('utf-8'))
    if size > max(MAX_FRAME, attach_limit):
        raise ValueError('Invalid frame')
    try:
        frame = json.loads(raw, object_pairs_hook=_pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
    except (ValueError, RecursionError):
        raise ValueError('Invalid frame') from None
    if not isinstance(frame, dict):
        raise ValueError('Invalid frame')
    method = frame.get('method')
    if (size > MAX_FRAME or binary) and not (isinstance(method, str) and method in ATTACH_METHODS):
        raise ValueError('Invalid frame')
    if 'method' not in frame:
        if 'id' not in frame or ('result' in frame) == ('error' in frame) or 'params' in frame:
            raise ValueError('Invalid response')
        return capabilities_seen
    if not isinstance(frame['method'], str) or not frame['method'] or 'result' in frame or 'error' in frame:
        raise ValueError('Invalid request')
    params = frame.get('params')
    if not isinstance(params, dict):
        raise ValueError('Invalid request')
    if frame['method'] == 'client.capabilities' and not capabilities_seen and 'profile' not in params:
        if set(params) != {'server_requests'} or type(params['server_requests']) is not bool:
            raise ValueError('Invalid capabilities')
        return True
    if params.get('profile') != profile:
        raise ValueError('Profile mismatch')
    return capabilities_seen


# A call over capacity waits this long for a worker, then is refused as busy (CHAT-121: refused at once, a phone's startup burst
# failed its new chat). It stays well inside bounded()'s 6 s, so a call that waited still has its own time to run.
WORKER_WAIT_SECONDS = 2.0
WORKER_POLL_SECONDS = 0.01


class GatewayBusy(ValueError):
    """No worker came free within the wait."""


class _Place:
    """A call waiting for a worker; [started]: its request already ran a step, and keeps its place ahead of newer requests."""
    __slots__ = ('started',)

    def __init__(self, started):
        self.started = started


class BoundedWorkers:
    def __init__(self, capacity=4, wait=None, spare=0):
        """[spare]: threads beyond [capacity] for calls whose caller stopped waiting (a timeout) while their thread still runs. Such a call gives its
        worker back at once and no longer counts, up to [spare] of them at a time; past that it counts until its thread ends, as without spares."""
        self.permits = threading.BoundedSemaphore(capacity)
        self.wait = WORKER_WAIT_SECONDS if wait is None else wait
        self.waiting = deque()  # the calls waiting for a worker, oldest first; touched only on the event loop
        self.spare, self.stragglers, self.lock = spare, 0, threading.Lock()
        self.executor = ThreadPoolExecutor(max_workers=capacity + spare, thread_name_prefix='beacon-canonical')

    async def _permit(self, wait=None, keep_place=False):
        # Workers go out in order: a call takes a free one at once only when nobody waits, and only the oldest waiter takes the next one
        # (a later call never takes it from an earlier waiter, which would then answer busy). Permits come back from executor threads,
        # so the oldest waiter looks again every few milliseconds and the loop never blocks. A permit is only ever taken by a
        # non-blocking acquire with no await before the submit, so a cancelled wait holds nothing and leaves the line.
        # [keep_place]: a later step of a request that already ran one goes ahead of every request that has not started yet (they stay
        # behind it in the line), so a request is not sent to the back at each of its steps (REVIEW-121B FU-P3-3).
        started_ahead = sum(1 for place in self.waiting if place.started)
        if (started_ahead == 0 if keep_place else not self.waiting) and self.permits.acquire(blocking=False):
            return
        wait = self.wait if wait is None else wait
        if wait <= 0:
            raise GatewayBusy('Gateway busy')
        place = _Place(keep_place)
        if keep_place:
            self.waiting.insert(started_ahead, place)  # the started ones are always at the front
        else:
            self.waiting.append(place)
        deadline = time.monotonic() + wait
        try:
            while True:
                if self.waiting[0] is place and self.permits.acquire(blocking=False):
                    return
                left = deadline - time.monotonic()
                if left <= 0:
                    raise GatewayBusy('Gateway busy')
                await asyncio.sleep(min(WORKER_POLL_SECONDS, left))
        finally:
            self.waiting.remove(place)

    async def run(self, fn, *args, wait=None, keep_place=False):
        """Runs fn on a worker; [wait] overrides how long to wait for one (0: a free worker now or busy, as before 1.2.1); [keep_place]:
        a later step of a request that has started (see _permit)."""
        await self._permit(wait, keep_place)
        try:
            future = self.executor.submit(contextvars.copy_context().run, fn, *args)
        except BaseException:
            self.permits.release()
            raise
        held = [True]  # this call still holds its permit (changed only under self.lock)

        def ended(_):
            with self.lock:
                if held[0]:
                    held[0] = False
                    self.permits.release()
                else:
                    self.stragglers -= 1
        future.add_done_callback(ended)
        wrapped = asyncio.wrap_future(future)
        wrapped.add_done_callback(lambda done: None if done.cancelled() else done.exception())
        try:
            return await asyncio.shield(wrapped)
        except asyncio.CancelledError:
            # The caller stopped waiting (bounded_call's timeout, or its socket ended) and the thread runs on. With a spare thread free, the worker
            # goes back now, so a socket's next confirmation finds one and is not skipped as busy (REVIEW-121B F2-P3-2).
            with self.lock:
                if held[0] and not future.done() and self.stragglers < self.spare:
                    held[0] = False
                    self.stragglers += 1
                    self.permits.release()
            raise

    def close(self):
        self.executor.shutdown(wait=False, cancel_futures=True)


BOUNDED_SECONDS = 6
# An admission's steps (the gateway ticket's, and the canonical socket's before accept) share one wait for workers, at most this long in all
# (CHAT-121 P3-4; the ticket since REVIEW-121B FU-P3-3).
SOCKET_WAIT_SECONDS = 2.0
# Canonical sockets open at once; each relays Hermes' frames and confirms Hermes' answers first, one confirmation at a time (incoming() awaits
# it). So confirmations get one worker per socket, apart from admissions: they never wait for a worker, never for an admission, and are never
# skipped for want of one (REVIEW-121B FU-P2-1). They are short local proofs (state.db reads, one mobile-store write).
MAX_SOCKETS = 8
# File sockets (form 4) open at once, beside MAX_SOCKETS so a file never keeps a chat out; one per phone (its newer one ends its older one).
MAX_FILE_SOCKETS = 4


def admission_steps(workers):
    """The step runner for one admission: every step shares one SOCKET_WAIT_SECONDS wait for workers, and each step after the first keeps
    the request's place ahead of newer ones (REVIEW-121B FU-P3-3)."""
    waits_until = time.monotonic() + SOCKET_WAIT_SECONDS
    started = False

    async def step(fn, *args, timeout=None):
        nonlocal started
        keep_place = started
        started = True
        return await bounded_call(workers, fn, *args, timeout=timeout, wait=max(0.0, waits_until - time.monotonic()), keep_place=keep_place)
    return step
CANONICAL_BUSY = 'Canonical gateway busy'
CANONICAL_UNAVAILABLE = 'Canonical gateway unavailable'
# Backlog 45 (CHAT-121 follow-up 11): each answer "unavailable" names the step and the error's class in the dashboard's log, never the
# error's message (it can carry a token, a path or a URL), so a live 503 can be told apart.
log = logging.getLogger('beacon.canonical')


async def bounded_call(workers, fn, *args, timeout=None, wait=None, keep_place=False):
    """One canonical step on [workers] within BOUNDED_SECONDS, the wait for a worker included ([wait] overrides that wait; [keep_place]: a
    later step of a started request). No worker in time answers busy (the request may simply be asked again); anything else that fails here
    answers unavailable, as before."""
    try:
        return await asyncio.wait_for(workers.run(fn, *args, wait=wait, keep_place=keep_place), timeout=BOUNDED_SECONDS if timeout is None else timeout)
    except HTTPException:
        raise
    except GatewayBusy:
        raise HTTPException(503, CANONICAL_BUSY) from None
    except Exception as error:
        log.warning('Beacon canonical step %s failed: %s', getattr(fn, '__name__', 'step'), type(error).__name__)
        raise HTTPException(503, CANONICAL_UNAVAILABLE) from None


def native_policy(profile):
    from hermes_cli.web_server import _get_dashboard_plugins
    from hermes_cli.web_server_dashboard import _plugin_consent_withheld
    from hermes_cli.web_server_plugin_profiles import in_process_router_refused
    from hermes_cli.web_server_profiles import _config_profile_scope, _resolve_profile_dir
    if in_process_router_refused('beacon'):
        raise HTTPException(501, 'Plugin socket unavailable for this profile')
    plugin = next((p for p in _get_dashboard_plugins() if p.get('name') == 'beacon'), None)
    if not plugin or _plugin_consent_withheld(plugin):
        raise HTTPException(403, 'Plugin not authorized')
    with _config_profile_scope(profile):
        if _plugin_consent_withheld(plugin):
            raise HTTPException(403, 'Plugin not authorized')
        return _resolve_profile_dir(profile)


def discover(home):
    from hermes_cli.gateway_runtime import discover_gateway_endpoint
    from hermes_cli.config import get_process_hermes_home
    selected = validate_endpoint(discover_gateway_endpoint(home, timeout=2), home)
    host_home = get_process_hermes_home()
    if home_digest(home)!=home_digest(host_home):
        host = validate_endpoint(discover_gateway_endpoint(host_home,timeout=2),host_home)
        if (not getattr(selected,'control_home',None) or home_digest(selected.control_home)!=home_digest(host_home)
                or selected.api_origin!=host.api_origin or selected.instance_id!=host.instance_id
                or selected.authority_epoch!=host.authority_epoch):
            raise ValueError('Gateway unavailable')
    return selected


def canonical_binding(request, profile, home, nonce):
    runner = getattr(request.app.state, 'gateway_runner', None)
    module = sys.modules.get('gateway.run_api')
    launch = getattr(request.app.state, 'session_authority', None)
    descriptor = getattr(runner, 'session_runtime_descriptor', None)
    if (runner is None or module is None or getattr(module, '_ACTIVE_RUNNER', None) is not runner or
            launch is None or getattr(runner, 'session_authority', None) is not launch or
            not isinstance(descriptor, dict) or descriptor.get('state') != 'ready' or
            not isinstance(descriptor.get('instance_id'), str) or not descriptor['instance_id'] or
            type(descriptor.get('authority_epoch')) is not int or descriptor['authority_epoch'] <= 0 or
            getattr(runner, '_draining', True) or getattr(launch,'epoch',None)!=descriptor.get('authority_epoch') or
            not isinstance(nonce, str) or len(nonce) != 64 or any(c not in '0123456789abcdef' for c in nonce)):
        raise HTTPException(503, 'Canonical admission unavailable')
    from gateway.session_authorities import authority_for_home
    authority=authority_for_home(runner,home)
    if (authority is None or getattr(authority,'retiring',False) or getattr(authority,'retired',False) or
            not isinstance(getattr(authority,'profile_id',None),str) or not authority.profile_id or
            home_digest(authority.profile_id)!=home_digest(home) or
            getattr(authority,'instance_id',None)!=descriptor['instance_id'] or
            type(getattr(authority,'epoch',None)) is not int or authority.epoch<=0):
        raise HTTPException(503,'Canonical admission unavailable')
    session = getattr(request.state, 'session', None)
    try:
        subject = subject_digest(session)
    except ValueError:
        raise HTTPException(401, 'Verified native session required') from None
    return {'profile': profile, 'home': home_digest(home), 'instance': descriptor['instance_id'],
            'epoch': authority.epoch, 'gateway_epoch':descriptor['authority_epoch'], 'nonce': nonce, 'subject': subject}


def canonical_proof(request, profile, home, nonce):
    proof = canonical_binding(request, profile, home, nonce)
    session = request.state.session
    from hermes_cli.dashboard_auth.ws_tickets import mint_ticket
    proof['ticket'] = mint_ticket(user_id=session.user_id, provider=session.provider, issuer=session.issuer)
    return proof


def canonical_live_proof(request, profile, home, stored, runtime, nonce):
    proof = canonical_binding(request, profile, home, nonce)
    if (not isinstance(stored,str) or not stored or len(stored)>256 or runtime!=stored):
        raise HTTPException(403, 'Canonical conversation unavailable')
    from gateway.session_identity import authenticated_subject
    session = request.state.session
    principal = authenticated_subject({'user_id':session.user_id,'provider':session.provider,'issuer':session.issuer})
    native=sys.modules['beacon_native']
    execution=native.execution_subject(session.user_id,session.provider,principal)
    from gateway.session_authorities import authority_for_home
    authority = authority_for_home(request.app.state.gateway_runner,home)
    row = authority.db.get_session(stored)
    live = authority.sessions.get(runtime)
    def remote_viewer(actor):
        transport=authority.events.get(getattr(actor,'transport_id',None))
        identity=getattr(transport,'auth_identity',None)
        return (actor.subject==execution and actor.profile_id==authority.profile_id and
                'session:read' in actor.capabilities and isinstance(identity,dict) and
                identity.get('native_bootstrap') is not True and identity.get('provider')==session.provider and
                identity.get('user_id')==session.user_id and identity.get('issuer','')==session.issuer)
    if (not native.owns_row(session.user_id,session.provider,profile,stored,row,principal,authority.db) or live is None or
            not any(remote_viewer(actor) for actor in tuple(live.subscribers.values()))):
        raise HTTPException(403, 'Canonical conversation unavailable')
    return {**proof,'stored':stored,'runtime':runtime}


def validate_proof(proof, expected):
    if (not isinstance(proof, dict) or set(proof) != set(expected) | {'ticket'} or
            any(proof.get(key) != value or type(proof.get(key)) is not type(value) for key, value in expected.items())):
        raise ValueError('Canonical admission unavailable')
    ticket = proof['ticket']
    if not isinstance(ticket, str) or not 1 <= len(ticket) <= 256:
        raise ValueError('Canonical admission unavailable')
    return ticket


def exchange(endpoint, profile, access_token, nonce, stored=None, runtime=None, authority_only=False, timeout=5):
    origin = urlsplit(endpoint.api_origin)
    if (not isinstance(access_token, str) or not access_token or len(access_token) > 32768 or
            '\r' in access_token or '\n' in access_token):
        raise ValueError('Verified native session required')
    connection_type = http.client.HTTPSConnection if origin.scheme == 'https' else http.client.HTTPConnection
    if not isinstance(timeout,(int,float)) or not 0<timeout<=5:
        raise ValueError('Canonical admission unavailable')
    connection = connection_type(origin.hostname, origin.port, timeout=timeout)
    connection.set_debuglevel(0)
    deadline=time.monotonic()+timeout
    held=[None]
    def abort():
        current=held[0] or getattr(connection,'sock',None)
        if current is not None:
            try: current.shutdown(socket.SHUT_RDWR)
            except Exception: pass
        try: connection.close()
        except Exception: pass
    timer=threading.Timer(timeout,abort)
    timer.daemon=True
    route = 'canonical-authority' if authority_only else 'canonical-admission' if stored is None else 'canonical-session'
    path = '/api/plugins/beacon/mobile/' + route + '?beacon_profile=' + quote(profile, safe='')
    payload = {'nonce':nonce} if stored is None else {'nonce':nonce,'stored':stored,'runtime':runtime}
    step = 'connect'
    try:
        timer.start()
        connection.connect()
        held[0]=getattr(connection,'sock',None)
        if time.monotonic()>=deadline: raise ValueError('Canonical admission unavailable')
        step = 'request'
        connection.request('POST', path, body=json.dumps(payload),
                           headers={'Authorization': 'Bearer ' + access_token, 'Content-Type': 'application/json'})
        step = 'response'
        response = connection.getresponse()
        data = response.read(16385)
        if response.status != 200 or len(data) > 16384:
            step = 'response %d' % response.status if type(response.status) is int else 'response'
            raise ValueError('Canonical admission unavailable')
        step = 'answer'
        result = json.loads(data)
        if not isinstance(result, dict):
            raise ValueError('Canonical admission unavailable')
        return result
    except (OSError, ValueError, http.client.HTTPException) as error:
        log.warning('Beacon canonical exchange (%s) failed at %s: %s', route, step, type(error).__name__)
        raise ValueError('Canonical admission unavailable') from None
    finally:
        timer.cancel()
        connection.close()


def _same_endpoint(left, right):
    return all(getattr(left, field) == getattr(right, field) for field in
               ('profile_id', 'instance_id', 'authority_epoch', 'runtime_protocol', 'api_origin'))


def verify_live_session(request, profile, stored, runtime, timeout=5):
    home = native_policy(profile)
    endpoint = discover(home)
    session = getattr(request.state,'session',None)
    nonce = secrets.token_hex(32)
    result = exchange(endpoint,profile,getattr(session,'access_token',None),nonce,stored,runtime,timeout=timeout)
    epoch=result.get('epoch') if isinstance(result,dict) else None
    if type(epoch) is not int or epoch<=0: raise LookupError('Canonical runtime proof unavailable')
    expected = {'profile':profile,'home':home_digest(home),'instance':endpoint.instance_id,
                'epoch':epoch,'gateway_epoch':endpoint.authority_epoch,'nonce':nonce,'subject':subject_digest(session),
                'stored':stored,'runtime':runtime}
    if (not isinstance(result,dict) or set(result)!=set(expected) or
            any(result.get(key)!=value or type(result.get(key)) is not type(value) for key,value in expected.items())):
        raise LookupError('Canonical runtime proof unavailable')
    return result


def _ready(raw):
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_FRAME:
        return False
    try:
        frame = json.loads(raw, object_pairs_hook=_pairs)
        params = frame.get('params') if isinstance(frame, dict) else None
        payload = params.get('payload') if isinstance(params, dict) else None
        return (frame.get('method') == 'event' and params.get('type') == 'gateway.ready' and
                isinstance(payload, dict) and payload.get('session_authority') is True)
    except (ValueError, AttributeError, RecursionError):
        return False


def _approval_prompts(frame):
    """Every approval prompt in a frame Hermes sent: a live event's payload, the snapshot's prompts and a replay's events."""
    params, result, found = frame.get('params'), frame.get('result'), []
    if isinstance(params, dict) and params.get('type') == 'approval.request' and isinstance(params.get('payload'), dict):
        found.append(params['payload'])
    if isinstance(result, dict):
        prompts, events = result.get('prompts'), result.get('events')
        for prompt in prompts if isinstance(prompts, list) else ():
            if isinstance(prompt, dict) and prompt.get('kind') == 'approval':
                found.append(prompt)
        for event in events if isinstance(events, list) else ():
            if isinstance(event, dict) and event.get('type') == 'approval.request' and isinstance(event.get('payload'), dict):
                found.append(event['payload'])
    return found


def _card_markers():
    """The saved-card tool's marker reader. The agent that marks a prompt and this relay are different processes, and this one may never
    have registered the tool, so the module is loaded here (under the name the plugin gives it) when it is not loaded yet."""
    module = sys.modules.get('beacon_any_site_card')
    if module is None:
        path = Path(__file__).with_name('any_site_card.py')
        spec = importlib.util.spec_from_file_location('beacon_any_site_card', path)
        module = importlib.util.module_from_spec(spec)
        module._beacon_mtime = path.stat().st_mtime_ns
        sys.modules['beacon_any_site_card'] = module
        try:
            spec.loader.exec_module(module)
        except BaseException:
            sys.modules.pop('beacon_any_site_card', None)
            raise
    return module


def annotate(raw):
    """Add the plugin's own `surface` to the approval prompts that are Hermes' card consent.

    Hermes' canonical prompts carry no surface, so the phone cannot tell a card payment from any other approval. The plugin records which
    prompt ids are Hermes' card consent (any_site_card.surface_for, from the pre_approval_request hook); only those get `surface`, and any
    `surface` already on another approval prompt is removed, so the phone can trust the field. Anything else is forwarded untouched."""
    try:
        if '"prompt_id"' not in raw:
            return raw  # the common frame (a token, a tool step) costs one search
        card = _card_markers()
        if not card.has_surfaces():
            return raw
        frame = json.loads(raw, object_pairs_hook=_pairs, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
        changed = False
        for prompt in _approval_prompts(frame) if isinstance(frame, dict) else ():
            surface = card.surface_for(prompt.get('prompt_id'))
            if surface and prompt.get('surface') != surface:
                prompt['surface'], changed = surface, True
            elif not surface and 'surface' in prompt:
                del prompt['surface']
                changed = True
        return json.dumps(frame, ensure_ascii=False, separators=(',', ':')) if changed else raw
    except Exception:  # health: allow BLE001 -- a lookup or parse failure must never cost the phone a frame; it gets Hermes' frame as sent
        return raw


def no_redirect_connect(url, **kwargs):
    from websockets.asyncio.client import connect
    class RefuseRedirect(connect):
        def process_redirect(self, exc):
            return exc
    return RefuseRedirect(url, **kwargs)


async def settle(awaitable):
    task = asyncio.ensure_future(awaitable)
    cancelled = None
    with anyio.CancelScope(shield=True):
        while not task.done():
            try:
                await asyncio.shield(task)
            except asyncio.CancelledError as exc:
                cancelled = exc
        if cancelled is None:
            return task.result()
        try:
            task.result()
        except Exception:
            pass
    raise cancelled


async def relay_sockets(client, upstream, profile, mobile=None, binding=None, run=asyncio.to_thread, confirm=None, budget=None, phone=None,
                        file_socket=False, ended=None):
    # [confirm] runs the confirmations on the path that forwards Hermes' frames to the phone, before each frame: they must never wait for
    # an admission (CHAT-121 P3-6) and never be skipped for want of a worker (REVIEW-121B FU-P2-1), so the socket route gives them their
    # own workers, one per socket. [budget] and [phone]: the file frames every socket holds at once (AttachBudget) and this socket's phone.
    # [file_socket]: a socket of one file (form 4, see PIECE_* above), the only one that takes pieces; [ended], an asyncio.Event, ends it from outside
    # (the phone's newer file socket).
    confirm = confirm or run
    budget = budget or AttachBudget(attach_budget_bytes())
    phone = phone if phone is not None else id(client)
    to_phone = asyncio.Lock()  # Hermes' frames and the relay's own "busy" answer go to the phone one at a time
    # The latest PIECE_ACK owed to the phone (its request id, the base64 held), sent by acks() (a file socket's) so the receive never waits to send one.
    ack, ack_due = {}, asyncio.Event()
    native=sys.modules.get('beacon_native')
    pending={}

    async def submission(raw):
        if mobile is None or binding is None or native is None: return raw
        frame=json.loads(raw,object_pairs_hook=_pairs)
        rid=frame.get('id')
        if type(rid) not in (str,int): return raw
        if type(rid) is str and len(rid)>256 or type(rid) is int and rid.bit_length()>64: return raw
        key=(type(rid),rid)
        if key in pending:
            pending[key]=(time.monotonic(),None)
            return raw  # Ambiguous in-flight response ids cannot authorize either input.
        for old in [k for k,v in pending.items() if time.monotonic()-v[0]>=30]: pending.pop(old)
        method=frame.get('method')
        if method not in ('prompt.submit','session.create') or len(pending)>=128: return raw
        params=frame.get('params',{})
        if method=='session.create':
            request=params.get('request_id')
            if request is None:
                request=params['request_id']=secrets.token_hex(32)
                raw=json.dumps(frame,ensure_ascii=False,separators=(',',':'))
            if not isinstance(request,str) or not 1<=len(request)<=256: return raw
            try:
                value=await run(native.prepare_created,mobile,binding,profile,request)
            except HTTPException as error:
                # Backlog 67 (CHAT-121 follow-up 11): a preparation that found no worker in time (busy), or failed (unavailable), is this frame's
                # answer, not the socket's end (1013 closed every other request on it). Hermes never saw the create, so the phone may ask again
                # under its retry identity (request_id).
                async with to_phone:
                    await client.send_text(json.dumps({'jsonrpc':'2.0','id':rid,'error':{
                        'code':error.status_code,'message':str(error.detail),'data':{'source':'beacon'}}},separators=(',',':')))
                return None
            pending[key]=(time.monotonic(),value)
            return raw
        session=params.get('session_id');request=params.get('submission_id') or params.get('input_id')
        if (not isinstance(session,str) or not isinstance(request,str) or
                not 1<=len(session)<=256 or not 1<=len(request)<=256): return raw
        try:
            payload=native.remote_payload(params)
            value={name:binding[name] for name in ('owner','provider','principal','home')}
            value.update(profile=profile,session=session,request=request,
                         payload_digest=native._payload_fingerprint(payload),
                         wire_digest=hashlib.sha256(raw.encode('utf-8')).hexdigest())
            pending[key]=(time.monotonic(),value)
        except Exception:
            pass  # Forward the original input; unsupported payloads acquire no authority.
        return raw

    async def confirmation(raw):
        if mobile is None or binding is None or native is None: return
        try:
            frame=json.loads(raw,object_pairs_hook=_pairs)
            if 'method' not in frame and type(frame.get('id')) in (str,int):
                saved=pending.pop((type(frame['id']),frame['id']),None)
                if (saved and saved[1] is not None and time.monotonic()-saved[0]<30 and
                        'error' not in frame and isinstance(frame.get('result'),dict)):
                    if saved[1].get('kind')=='create':
                        # The native row and its creation receipt can become visible a moment after
                        # the response; retry only that same exact proof, never a weaker one. The
                        # final wait is followed by one last exact check.
                        for delay in (0,0.05,0.1,0.2,0.4):
                            if delay: await asyncio.sleep(delay)
                            if await confirm(native.confirm_created,mobile,saved[1],frame['result']): break
                    else: await confirm(native.confirm_remote_receipt,mobile,saved[1],frame['result'])
            elif frame.get('method')=='event':
                params=frame.get('params',{})
                if params.get('type')=='message.start':
                    await confirm(native.confirm_remote_start,mobile,params.get('admission_id'),profile,
                              params.get('session_id'),params.get('authority_epoch'),params.get('execution_generation'))
        except Exception:
            return  # Uncertain proof never settles work; the native wire remains authoritative.

    attach_limit = attach_frame_limit()
    # A file socket: the session its one session.resume named (its file must be for it), whether its file's header came, and the file frame's id once it
    # went to Hermes (Hermes' answer to it ends the socket).
    resumed, file_answer = [None, False], [None]

    async def outgoing():
        capabilities_seen = False
        held = None  # the file frame this file socket is receiving (HeldFile), or None
        try:
            while True:
                if isinstance(held, HeldFile):
                    waited = time.monotonic()
                    try:
                        message = await asyncio.wait_for(client.receive(), max(0.0, ATTACH_QUIET_SECONDS - held.quiet))
                    except asyncio.TimeoutError:
                        # Its pieces stopped coming: its buffer and its share of the budget go back now, not when the socket ends. The phone is told, so it
                        # stops, and a later try sends the file again from its header on a new socket; this one carries one file and ends with it.
                        budget.give_back(phone, held.total)
                        ident, held = held.ident, None
                        if type(ident) in (str, int):
                            async with to_phone:
                                await client.send_text(json.dumps({'jsonrpc': '2.0', 'id': ident, 'error': {
                                    'code': 408, 'message': ATTACH_STALLED, 'data': {'source': 'beacon'}}}, separators=(',', ':')))
                        return
                    held.quiet += time.monotonic() - waited
                elif file_socket and file_answer[0] is None:
                    # Nothing on a file socket waits longer than a file's piece may: a phone that opened one and went quiet frees it.
                    try:
                        message = await asyncio.wait_for(client.receive(), ATTACH_QUIET_SECONDS)
                    except asyncio.TimeoutError:
                        return
                else:
                    message = await client.receive()
                if message['type'] == 'websocket.disconnect':
                    return
                raw, data = message.get('text'), message.get('bytes')
                if raw is None and isinstance(data, (bytes, bytearray)):
                    # A file's pieces (PIECE_*, see above) come on a file socket only: a binary message on a chat socket is an invalid frame. On a file
                    # socket anything but its file's header and then its pieces in order (an unknown first byte, a piece with no header, a header that does
                    # not check out, a second header, a piece that is not base64 or more than declared, a frame short of its length) ends the relay like any
                    # invalid frame, and nothing of that frame reaches Hermes.
                    if not file_socket or not data or len(data) - 1 > PIECE_MAX:
                        raise ValueError('Invalid frame')
                    kind = data[0]
                    if file_answer[0] is not None or kind != PIECE_HEAD and held is None:
                        raise ValueError('Invalid frame')
                    if kind == PIECE_HEAD:
                        if held is not None or resumed[0] is None or resumed[1]:
                            raise ValueError('Invalid frame')
                        ident, head, tail, size = file_header(data, profile, capabilities_seen, attach_limit)
                        # Its one file, for the session it resumed (file_header has checked the frame's text whole).
                        resumed[1] = True
                        if json.loads(head + tail)['params'].get('session_id') != resumed[0]:
                            raise ValueError('Invalid frame')
                        if budget.take(phone, len(head) + size + len(tail)):
                            try:
                                held = await asyncio.to_thread(HeldFile, ident, head, tail, size)  # the buffer is made off the loop too
                            except BaseException:
                                budget.give_back(phone, len(head) + size + len(tail))
                                raise
                            continue
                        if type(ident) in (str, int):
                            async with to_phone:
                                await client.send_text(json.dumps({'jsonrpc': '2.0', 'id': ident, 'error': {
                                    'code': 503, 'message': CANONICAL_BUSY, 'data': {'source': 'beacon'}}}, separators=(',', ':')))
                        return  # a file the budget cannot take now: answered busy, and the socket ends (the phone's next try opens a new one)
                    if kind not in (PIECE_MORE, PIECE_LAST):
                        raise ValueError('Invalid frame')
                    # Checked and copied on a worker thread of its own, never on the admissions' or the confirmations' workers.
                    await asyncio.to_thread(held.take, data)
                    held.quiet = 0.0
                    if type(held.ident) in (str, int):
                        ack['latest'] = (held.ident, held.filled)
                        ack_due.set()
                    if kind == PIECE_MORE:
                        continue
                    done, held = held, None
                    try:
                        if done.filled != len(done.body):
                            raise ValueError('Invalid frame')
                        file_answer[0] = done.ident  # set first: Hermes may answer before this send returns
                        await upstream.send(done.fragments(), text=True)
                    finally:
                        budget.give_back(phone, done.total)
                        done.body = None
                    continue
                # A text frame: at most MAX_FRAME, checked before it is parsed (only a file frame in pieces may be larger).
                capabilities_seen = validate_frame(raw, profile, capabilities_seen)
                if file_socket:
                    # A file socket takes client.capabilities and one session.resume before its file, nothing else.
                    frame = json.loads(raw, object_pairs_hook=_pairs)
                    method, params = frame.get('method'), frame.get('params')
                    session = params.get('session_id') if isinstance(params, dict) else None
                    if (file_answer[0] is not None or resumed[1] or method not in ('client.capabilities', 'session.resume') or
                            method == 'session.resume' and (resumed[0] is not None or not isinstance(session, str) or not 1 <= len(session) <= 256)):
                        raise ValueError('Invalid frame')
                    if method == 'session.resume':
                        resumed[0] = session
                    await upstream.send(raw)
                    continue
                raw=await submission(raw)
                if raw is not None:
                    await upstream.send(raw)
        finally:
            if isinstance(held, HeldFile):
                budget.give_back(phone, held.total)

    async def incoming():
        while True:
            raw = await upstream.recv()
            if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_FRAME:
                raise ValueError('Invalid frame')
            if file_socket:
                # Only Hermes' answers go to a file socket: its events and questions reach the phone on the chat socket, attached to the same session.
                try:
                    frame = json.loads(raw, object_pairs_hook=_pairs)
                except (ValueError, RecursionError):
                    continue
                if not isinstance(frame, dict) or 'method' in frame:
                    continue
                async with to_phone:
                    await client.send_text(raw)
                if file_answer[0] is not None and frame.get('id') == file_answer[0]:
                    return  # the file's answer: the socket's work is done
                continue
            raw = annotate(raw)
            await confirmation(raw)
            async with to_phone:
                await client.send_text(raw)

    async def acks():
        # Only the latest count goes: one sent while Hermes' frames hold the phone's side carries every piece taken since.
        while True:
            await ack_due.wait()
            ack_due.clear()
            ident, filled = ack['latest']
            async with to_phone:
                await client.send_bytes(bytes([PIECE_ACK]) + filled.to_bytes(8, 'big') + str(ident).encode('utf-8'))

    tasks = [asyncio.create_task(outgoing()), asyncio.create_task(incoming())]
    if file_socket:
        tasks.append(asyncio.create_task(acks()))  # the acknowledgments are a file socket's: the chat's socket takes no pieces
    if ended is not None:
        tasks.append(asyncio.create_task(ended.wait()))
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in tasks:
            task.cancel()
        await settle(asyncio.gather(*tasks, return_exceptions=True))


# Call contract v3 hand-over (CALLS-CONTRACT.md section 4): the plugin admits a call's hand-over itself, on the same canonical
# socket the phone's chat uses, with the phone's own sign-in, so Hermes' admission, FIFO turn order and approvals are exactly
# those of a chat message. Nothing here changes Hermes; it is one more client of its canonical socket.
HANDOVER_RPC_SECONDS = 5
# The whole hand-over answers within this: past it before prompt.submit is sent the answer is not_sent, after it uncertain.
HANDOVER_SECONDS = 8
FRAME_NOT_ATTACHED = '\n[The current shared image could not be attached.]'


class HandoverNotSent(Exception):
    """Nothing reached Hermes' admission ledger: the same request may be sent again."""
    def __init__(self, code, reason=None):
        super().__init__(code)
        self.code, self.reason = code, reason or code


class HandoverUnconfirmed(Exception):
    """prompt.submit left this process and its answer was not read: only the same request id may be sent again."""


class HandoverConflict(Exception):
    """Hermes holds this request id for a request that is not this one: nothing of it may be reused or sent again."""


def _refusal_code(reason):
    if reason == 'queue_full': return 'queue_full'
    if reason in ('not_found', 'permission_denied', 'profile_mismatch'): return 'session_unavailable'
    return 'admission_refused'


async def canonical_handover(request, profile, stored, text, request_id, frame, prepare, confirm, discard, adopt):
    """One canonical admission of the owner's request into the call's own chat. `prepare(seconds)` stores the job (once per
    request id) and returns it; `confirm(params, receipt, media)` records the admission's provenance; `discard(job)` removes a
    job Hermes did not admit; `adopt(job)` returns the admission Hermes already holds under this request id as
    (admission_id, params, media) when it is this request in one of its two forms (frame attached or not), else None.
    Returns (job, receipt). Raises HandoverNotSent, HandoverUnconfirmed or HandoverConflict."""
    from hermes_cli.gateway_client import GatewayClient, GatewayClientError, GatewayRPCError, NOT_SENT, gateway_ws_target
    session = getattr(request.state, 'session', None)
    deadline = time.monotonic() + HANDOVER_SECONDS
    def left(limit):
        remaining = deadline - time.monotonic()
        if remaining <= 0.05: raise HandoverNotSent('transport_unavailable')
        return min(limit, remaining)
    try:
        home = await asyncio.to_thread(native_policy, profile)
        endpoint = await asyncio.to_thread(discover, home)
        nonce = secrets.token_hex(32)
        proof = await asyncio.to_thread(exchange, endpoint, profile, getattr(session, 'access_token', None), nonce,
                                        None, None, False, left(5))
        epoch = proof.get('epoch') if isinstance(proof, dict) else None
        if type(epoch) is not int or epoch <= 0: raise ValueError('Canonical admission unavailable')
        ticket = validate_proof(proof, {'profile': profile, 'home': home_digest(home), 'instance': endpoint.instance_id,
                                        'epoch': epoch, 'gateway_epoch': endpoint.authority_epoch, 'nonce': nonce,
                                        'subject': subject_digest(session)})
        url, protocols = gateway_ws_target(endpoint, ticket)
        upstream = await no_redirect_connect(url, subprotocols=protocols, proxy=None, compression=None, open_timeout=left(5),
                                             close_timeout=2, max_size=MAX_FRAME, logger=_QUIET)
    except Exception:
        raise HandoverNotSent('transport_unavailable') from None
    try:
        try: ready = await asyncio.wait_for(upstream.recv(), timeout=left(6))
        except Exception: raise HandoverNotSent('transport_unavailable') from None
        if upstream.subprotocol != 'hermes-gateway-v1' or not _ready(ready): raise HandoverNotSent('transport_unavailable')
        async with GatewayClient(upstream) as client:
            async def rpc(method, **params):
                try: return await client.rpc(method, profile=profile, _timeout=left(HANDOVER_RPC_SECONDS), **params)
                except GatewayRPCError as error: raise HandoverNotSent(_refusal_code(str(error)), str(error)[:80]) from None
                except (GatewayClientError, TimeoutError, asyncio.TimeoutError): raise HandoverNotSent('transport_unavailable') from None
            attached = await rpc('session.resume', session_id=stored, omit_messages=True)
            try:
                job = await asyncio.to_thread(prepare, left(5))
                if not job.get('submit') and job.get('admission_id'):
                    # Already admitted: Hermes' own receipt says where it is now; nothing is submitted again.
                    try: return job, await rpc('prompt.receipt', session_id=stored, admission_id=job['admission_id'])
                    except HandoverNotSent: return job, None
                params = {'session_id': stored, 'text': text, 'submission_id': request_id, 'surface': 'voice-live', 'queued': True}
                media = False
                if frame:
                    try:
                        staged = await rpc('image.attach_bytes', session_id=stored, content_base64=frame, filename='voice-frame.jpg')
                        if staged.get('attached') and isinstance(staged.get('path'), str) and staged.get('mime') == 'image/jpeg':
                            params['attachments'] = [{'path': staged['path'], 'mime': 'image/jpeg'}]
                            media = True
                    except HandoverNotSent: pass
                    if not media: params['text'] = text + FRAME_NOT_ATTACHED
                try: budget = left(HANDOVER_RPC_SECONDS)
                except HandoverNotSent:
                    await asyncio.to_thread(discard, job)
                    raise
                try:
                    receipt = await client.rpc('prompt.submit', profile=profile, _timeout=budget, **params)
                except GatewayRPCError as error:
                    if str(error) == 'admission_conflict':
                        # Hermes already admitted this request id with the other form of it (an earlier try whose answer was
                        # lost attached the frame, or could not): that admission is this hand-over; report it, never resend.
                        try: adopted = await asyncio.to_thread(adopt, job)
                        except Exception: raise HandoverUnconfirmed() from None  # admitted, but not readable now
                        if adopted is None: raise HandoverConflict() from None
                        admission_id, params, media = adopted
                        try: receipt = await rpc('prompt.receipt', session_id=stored, admission_id=admission_id)
                        except HandoverNotSent: raise HandoverUnconfirmed() from None
                    else:
                        # Hermes refused this request id outright: nothing was admitted under it, so its job goes too.
                        await asyncio.to_thread(discard, job)
                        raise HandoverNotSent(_refusal_code(str(error)), str(error)[:80]) from None
                except GatewayClientError as error:
                    if str(error) == NOT_SENT:
                        await asyncio.to_thread(discard, job)
                        raise HandoverNotSent('transport_unavailable') from None
                    raise HandoverUnconfirmed() from None
                except (TimeoutError, asyncio.TimeoutError):
                    raise HandoverUnconfirmed() from None
                if not isinstance(receipt, dict) or not isinstance(receipt.get('admission_id'), str): raise HandoverUnconfirmed()
                await asyncio.to_thread(confirm, params, receipt, media)
                return job, receipt
            finally:
                subscription = attached.get('subscription_id') if isinstance(attached, dict) else None
                if isinstance(subscription, str) and subscription:
                    try: await client.rpc('session.detach', profile=profile, session_id=stored, subscription_id=subscription, _timeout=2)
                    except Exception: pass  # the socket closes next; Hermes detaches a closed viewer itself
    finally:
        await settle(upstream.close())


_QUIET = logging.Logger('beacon.canonical.handover', logging.WARNING)
_QUIET.addHandler(logging.NullHandler())


def revalidate_binding(socket, binding, mobile, profile_names, check_device):
    cfg = mobile.settings()
    owner, provider, profile = binding['owner'], binding['provider'], binding['profile']
    if (cfg.get('owner_id') != owner or cfg.get('owner_provider') != provider or
            socket.headers.get('x-beacon-device-id') != binding['device'] or
            binding['expires'] <= time.time()):
        raise HTTPException(403, 'Admission expired')
    if set(socket.query_params) - {'profile', 'beacon_profile'}:
        raise HTTPException(403, 'Profile not authorized')
    for key in ('profile', 'beacon_profile'):
        values = socket.query_params.getlist(key)
        if len(values) > 1 or (values and values[0] != profile):
            raise HTTPException(403, 'Profile not authorized')
    profiles = cfg['profiles'] if 'profiles' in cfg else profile_names()
    if (not isinstance(profiles, list) or profile not in profiles or
            not 1 <= len(profiles) <= 64 or len(set(profiles)) != len(profiles)):
        raise HTTPException(403, 'Profile not authorized')
    check_device(socket, owner, provider, profile)


def build_router(mobile, dashboard_identity, enforce, profile_names, check_device, body, update_in_progress):
    cache, workers, sockets = TicketCache(), BoundedWorkers(), threading.BoundedSemaphore(MAX_SOCKETS)
    # Form 4: the file sockets open now (their own bound), and each phone's newest one, which a newer one ends (its Event).
    file_sockets, file_phones = threading.BoundedSemaphore(MAX_FILE_SOCKETS), {}
    # One worker per socket, and as many spare threads for confirmations whose 6 s ran out while their thread still runs (a mobile store locked
    # past it): those give their worker back, so a socket's next confirmation is not skipped as busy (REVIEW-121B F2-P3-2). At most 16 threads.
    confirmations = BoundedWorkers(MAX_SOCKETS, spare=MAX_SOCKETS)
    # The file frames every phone's sockets hold at once (CHAT-121 follow-up 6).
    files = AttachBudget(attach_budget_bytes())
    @asynccontextmanager
    async def lifespan(app):
        try:
            yield
        finally:
            cache.clear()
            workers.close()
            confirmations.close()
    router = APIRouter(lifespan=lifespan)
    quiet_logger = logging.Logger('beacon.canonical.websocket', logging.WARNING)
    quiet_logger.addHandler(logging.NullHandler())

    async def bounded(fn, *args):
        return await bounded_call(workers, fn, *args)

    async def confirmed(fn, *args):
        # The relay's confirmations, which run before Hermes' frames reach the phone: on their own workers, one per socket (MAX_SOCKETS).
        return await bounded_call(confirmations, fn, *args)

    async def check_update(step=bounded):
        if await step(update_in_progress):
            raise HTTPException(503, 'Update in progress')

    async def authenticate(request, step=bounded):
        return await step(authorize_request, request, mobile, dashboard_identity, enforce, profile_names, check_device)

    @router.post('/mobile/canonical-admission')
    async def companion(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request, 1024)
        if set(value) != {'nonce'}:
            raise HTTPException(400, 'Invalid admission')
        home = await bounded(native_policy, profile)
        proof = await bounded(canonical_proof, request, profile, home, value['nonce'])
        return JSONResponse(proof, headers={'Cache-Control': 'no-store'})

    @router.post('/mobile/canonical-session')
    async def companion_session(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request, 2048)
        if set(value)!={'nonce','stored','runtime'}:
            raise HTTPException(400, 'Invalid admission')
        home = await bounded(native_policy, profile)
        proof = await bounded(canonical_live_proof,request,profile,home,value['stored'],value['runtime'],value['nonce'])
        return JSONResponse(proof,headers={'Cache-Control':'no-store'})

    @router.post('/mobile/canonical-authority')
    async def companion_authority(request: Request):
        await check_update()
        _, _, profile = await authenticate(request)
        value = await body(request,1024)
        if set(value)!={'nonce'}: raise HTTPException(400,'Invalid admission')
        home = await bounded(native_policy,profile)
        proof = await bounded(canonical_binding,request,profile,home,value['nonce'])
        return JSONResponse(proof,headers={'Cache-Control':'no-store'})

    @router.post('/mobile/gateway-ticket')
    async def admission(request: Request):
        step = admission_steps(workers)
        await check_update(step)
        owner, provider, profile = await authenticate(request, step)
        value = await body(request, 1024)
        # {} for the chat's socket; {"purpose": "file"} for a socket of one file (form 4).
        if value not in ({}, {'purpose': 'file'}):
            raise HTTPException(400, 'Invalid admission')
        purpose = value.get('purpose', 'chat')
        session = request.state.session
        expires = getattr(session, 'expires_at', None)
        if type(expires) is not int or expires <= time.time():
            raise HTTPException(401, 'Verified native session required')
        home = await step(native_policy, profile)
        endpoint = await step(discover, home)
        try:
            ticket = cache.issue(None, loop=asyncio.get_running_loop())
        except ValueError:
            raise HTTPException(503, 'Canonical gateway busy') from None
        stage = 'exchange'
        try:
            nonce = secrets.token_hex(32)
            deadline=cache.deadline(ticket)
            proof = await step(exchange, endpoint, profile, getattr(session, 'access_token', None), nonce,
                               None,None,False,min(5,deadline-time.monotonic()))
            stage = 'proof'
            epoch=proof.get('epoch') if isinstance(proof,dict) else None
            if type(epoch) is not int or epoch<=0: raise ValueError('Canonical admission unavailable')
            expected = {'profile': profile, 'home': home_digest(home), 'instance': endpoint.instance_id,
                        'epoch': epoch, 'gateway_epoch':endpoint.authority_epoch, 'nonce': nonce, 'subject': subject_digest(session)}
            upstream = validate_proof(proof, expected)
            stage = 'binding'
            binding = {'owner': owner, 'provider': provider, 'profile': profile,
                       'device': request.headers.get('x-beacon-device-id'), 'expires': expires,
                       'home': home_digest(home), 'endpoint': endpoint, 'epoch':epoch, 'upstream': upstream,
                       'subject':subject_digest(session),'access_token':session.access_token,'deadline':deadline,'purpose':purpose}
            binding['principal']=sys.modules['beacon_gateway_tools'].request_principal(request,owner,provider)
            stage = 'ticket'
            if not cache.replace(ticket, binding):
                raise ValueError('Admission expired')
            expires_in = min(cache.remaining(ticket), int(expires - time.time()))
            if expires_in < 1:
                raise ValueError('Admission expired')
        except asyncio.CancelledError:
            cache.consume(ticket)
            raise
        except Exception as error:
            cache.consume(ticket)
            # The exchange that found no worker in time stays busy (bounded_call), so the phone does not ask again on top of the wait.
            if isinstance(error, HTTPException) and error.status_code == 503 and error.detail == CANONICAL_BUSY: raise
            log.warning('Beacon canonical gateway-ticket failed at %s: %s', stage, type(error).__name__)
            raise HTTPException(503, 'Canonical gateway unavailable') from None
        # attach_frame_limit: the largest file frame a file socket takes, in pieces (PIECE_*), in the form attach_pieces names (4: a header
        # first, each piece acknowledged, on a socket of the file's own); attach_file_limit and attach_image_limit: Hermes' own caps on one file
        # (CHAT-121 follow-ups 4, 6, 8 and 9). A phone that sees none of them (a 1.2.0 plugin), or another form, keeps MAX_FRAME and text frames;
        # the phone checks only the keys it knows. A file socket's ticket also says so ("purpose").
        file_cap, image_cap = attach_file_limits()
        return JSONResponse({'ticket': ticket, 'path': '/api/plugins/beacon/gateway/ws',
                             'profile': profile, 'canonical': True, 'expires_in': expires_in,
                             'attach_frame_limit': attach_frame_limit(), 'attach_pieces': ATTACH_PIECES,
                             'attach_file_limit': file_cap, 'attach_image_limit': image_cap,
                             **({'purpose': 'file'} if purpose == 'file' else {})},
                            headers={'Cache-Control': 'no-store'})

    @router.websocket('/gateway/ws')
    async def socket_route(socket: WebSocket):
        from hermes_cli.web_server_boundary import ws_client_reason, ws_host_origin_reason
        protocols = [part.strip() for value in socket.headers.getlist('sec-websocket-protocol')
                     for part in value.split(',') if part.strip()]
        tickets = [value[len('beacon-ticket.'):] for value in protocols if value.startswith('beacon-ticket.')]
        if (protocols.count('beacon-canonical-v1') != 1 or len(tickets) != 1 or len(protocols) != 2 or
                ws_client_reason(socket, socket.app.state) is not None or
                ws_host_origin_reason(socket, socket.app.state) is not None):
            await socket.close(code=1008)
            return
        binding = cache.consume(tickets[0])
        file_socket = binding is not None and binding.get('purpose') == 'file'
        slots = file_sockets if file_socket else sockets
        if binding is None or not slots.acquire(blocking=False):
            await socket.close(code=1008)
            return
        # The steps before accept share one wait for workers, SOCKET_WAIT_SECONDS in all, so a full pool cannot hold the upgrade near the
        # phone's 15 s ready limit. A socket that found no worker in that time is refused with 429, which the phone does not ask again (it
        # already waited); the at-once refusals above stay 403, which it does.
        step = admission_steps(workers)
        accepted = False
        try:
            if await step(update_in_progress):
                raise HTTPException(503, 'Update in progress')
            await step(revalidate_binding, socket, binding, mobile, profile_names, check_device)
            home = await step(native_policy, binding['profile'])
            endpoint = await step(discover, home)
            if (home_digest(home) != binding['home'] or not _same_endpoint(endpoint, binding['endpoint'])):
                raise ValueError('Gateway changed')
            nonce=secrets.token_hex(32)
            timeout=min(5,binding['deadline']-time.monotonic())
            if timeout<=0: raise ValueError('Admission expired')
            proof=await step(exchange,endpoint,binding['profile'],binding.pop('access_token'),nonce,None,None,True,timeout)
            expected={'profile':binding['profile'],'home':binding['home'],'instance':endpoint.instance_id,
                      'epoch':binding['epoch'],'gateway_epoch':endpoint.authority_epoch,'nonce':nonce,'subject':binding['subject']}
            if not isinstance(proof,dict) or set(proof)!=set(expected) or any(
                    proof.get(key)!=value or type(proof.get(key)) is not type(value) for key,value in expected.items()):
                raise ValueError('Gateway authority changed')
            from hermes_cli.gateway_client import gateway_ws_target
            url, upstream_protocols = gateway_ws_target(endpoint, binding['upstream'])
            connection = no_redirect_connect(url, subprotocols=upstream_protocols, proxy=None, compression=None,
                                             open_timeout=5, close_timeout=2, max_size=MAX_FRAME, max_queue=1,
                                             logger=quiet_logger)
            upstream = await connection
            try:
                if upstream.subprotocol != 'hermes-gateway-v1':
                    raise ValueError('Gateway protocol unavailable')
                ready = await asyncio.wait_for(upstream.recv(), timeout=6)
                if not _ready(ready):
                    raise ValueError('Gateway not ready')
                await socket.accept(subprotocol='beacon-canonical-v1')
                accepted = True
                await socket.send_text(ready)
                # A phone is its device id; a phone that sent none counts as one with every other such socket of the same account.
                phone = binding.get('device') or ('account', binding['owner'], binding['subject'])
                if not file_socket:
                    await relay_sockets(socket, upstream, binding['profile'],mobile,binding,bounded,confirm=confirmed,budget=files,phone=phone)
                else:
                    # One file socket per phone: a newer one (the phone's next try after this one failed on its side) ends this one, so a socket
                    # the phone has given up on never holds its file's budget until its quiet time.
                    ended = asyncio.Event()
                    earlier, file_phones[phone] = file_phones.get(phone), ended
                    if earlier is not None:
                        earlier.set()
                    try:
                        await relay_sockets(socket, upstream, binding['profile'], budget=files, phone=phone, file_socket=True, ended=ended)
                    finally:
                        if file_phones.get(phone) is ended:
                            del file_phones[phone]
                    await socket.close(code=1000)
            finally:
                await settle(upstream.close())
        except Exception as error:
            busy = (not accepted and isinstance(error, HTTPException) and error.detail == CANONICAL_BUSY and
                    'websocket.http.response' in socket.scope.get('extensions', {}))
            try:
                if busy:
                    await socket.send_denial_response(Response(status_code=429))
                else:
                    await socket.close(code=1013)
            except Exception:
                pass
        finally:
            slots.release()

    return router
