"""The agent phone's vault channel, host end: the plugin side of docs/agent-phone/channel-v1.md.

The Beacon helper app on the agent phone dials a port Beacon opens for one session; both ends prove the session token before
anything else, and every later message is sealed with AES-256-GCM under a key per direction. This module holds the codec (pure
functions the test vectors pin) and the session (the listener, the handshake, the one authenticated link and its messages).
Nothing here writes the token, a key, a plaintext or a value to a log, a file or a result.
"""
import base64
import hashlib
import hmac
import json
import logging
import re
import secrets
import socket
import struct
import threading
import time

MAGIC = b'BAPC'
HANDSHAKE_VERSION = 1
PROTOCOL_VERSION = 1
LABEL = b'beacon-agent-phone-channel/1 '
INFO_AUTH = LABEL + b'auth'
INFO_H2C = LABEL + b'host->helper'
INFO_C2H = LABEL + b'helper->host'
HOST_PROOF = LABEL + b'host proof'
HELPER_PROOF = LABEL + b'helper proof'
H2C, C2H = 1, 2
TOKEN_BYTES = NONCE_BYTES = 32
CH_LEN, SH_LEN, CP_LEN = 37, 69, 32
MIN_SEALED, MAX_FRAME, MAX_PLAINTEXT = 25, 262144, 262000
HELPER = 'dev.hermes.agentphone.helper'
SERVICE = HELPER + '/.ChannelService'
GUEST_HOST = '10.0.2.2'


class Closed(Exception):
    """The link must close now, with nothing sent back. The text is a fixed reason word, never data from the wire."""


# -- crypto ----------------------------------------------------------------------------------------------------------

def hkdf_sha256(ikm, salt, info, length=32):
    """RFC 5869. An empty salt is HashLen zero bytes, as the RFC says."""
    prk = hmac.new(salt or b'\0' * 32, ikm, hashlib.sha256).digest()
    out, block, counter = b'', b'', 1
    while len(out) < length:
        block = hmac.new(prk, block + info + bytes([counter]), hashlib.sha256).digest()
        out += block
        counter += 1
    return out[:length]


def auth_key(token):
    return hkdf_sha256(token, b'', INFO_AUTH)


def link_keys(token, nc, ns):
    """(K_h2c, K_c2h) for one link."""
    salt = nc + ns
    return hkdf_sha256(token, salt, INFO_H2C), hkdf_sha256(token, salt, INFO_C2H)


def client_hello(nc):
    return MAGIC + bytes([HANDSHAKE_VERSION]) + nc


def host_proof(ka, ch, sh0):
    return hmac.new(ka, HOST_PROOF + ch + sh0, hashlib.sha256).digest()


def helper_proof(ka, ch, sh0, ps):
    return hmac.new(ka, HELPER_PROOF + ch + sh0 + ps, hashlib.sha256).digest()


def server_hello(ka, ch, ns):
    sh0 = MAGIC + bytes([HANDSHAKE_VERSION]) + ns
    return sh0 + host_proof(ka, ch, sh0)


def read_client_hello(body):
    """The helper's nonce from a CH body; Closed when it is not one."""
    if len(body) != CH_LEN or body[:4] != MAGIC or body[4] != HANDSHAKE_VERSION:
        raise Closed('hello')
    return body[5:]


def check_server_hello(ka, ch, body):
    """Helper side (used by tests and the fake helper): the host's nonce when its proof holds."""
    if len(body) != SH_LEN or body[:4] != MAGIC or body[4] != HANDSHAKE_VERSION:
        raise Closed('hello')
    sh0, ps = body[:37], body[37:]
    if not hmac.compare_digest(ps, host_proof(ka, ch, sh0)):
        raise Closed('proof')
    return body[5:37]


def check_helper_proof(ka, ch, sh, body):
    if len(body) != CP_LEN or not hmac.compare_digest(body, helper_proof(ka, ch, sh[:37], sh[37:])):
        raise Closed('proof')


def nonce_for(seq):
    return b'\0\0\0\0' + struct.pack('>Q', seq)


def aad_for(direction, seq):
    return MAGIC + bytes([HANDSHAKE_VERSION, direction]) + struct.pack('>Q', seq)


def seal(key, direction, seq, plaintext):
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    return struct.pack('>Q', seq) + AESGCM(key).encrypt(nonce_for(seq), plaintext, aad_for(direction, seq))


def open_sealed(key, direction, expected_seq, body):
    """The plaintext of a sealed body that is exactly the next one expected; Closed for anything else."""
    from cryptography.exceptions import InvalidTag
    from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    if not MIN_SEALED <= len(body) <= MAX_FRAME:
        raise Closed('length')
    seq = struct.unpack('>Q', body[:8])[0]
    if seq != expected_seq:
        raise Closed('sequence')
    try:
        return AESGCM(key).decrypt(nonce_for(seq), body[8:], aad_for(direction, seq))
    except InvalidTag:
        raise Closed('tag') from None


def frame(body):
    return struct.pack('>I', len(body)) + body


def frame_length(header, expected=None):
    """The body length a 4-byte header announces; Closed when it is not the fixed handshake length or out of the sealed range."""
    if len(header) != 4:
        raise Closed('length')
    length = struct.unpack('>I', header)[0]
    if expected is not None and length != expected or expected is None and not MIN_SEALED <= length <= MAX_FRAME:
        raise Closed('length')
    return length


def token_text(token):
    return base64.urlsafe_b64encode(token).rstrip(b'=').decode('ascii')


def token_from_text(text):
    """The 32 bytes a start intent's token stands for, or None (the helper's check, also used by tests)."""
    if not isinstance(text, str) or not re.fullmatch(r'[A-Za-z0-9_-]{43}', text):
        return None
    raw = base64.urlsafe_b64decode(text + '=')
    return raw if len(raw) == TOKEN_BYTES else None


def start_command(port, token):
    """The adb shell arguments that start the helper's channel service for one session (contract §1.3). Never logged."""
    return ['am', 'start-foreground-service', '-n', SERVICE, '--ei', 'port', str(int(port)), '--es', 'token', token_text(token)]


# -- messages --------------------------------------------------------------------------------------------------------

ID_RE = re.compile(r'[A-Za-z0-9_-]{16,64}')
KINDS = {'login': ('username', 'password', 'otp'), 'new_login': ('username', 'new_password'),
         'payment': ('card_number', 'cardholder_name', 'exp_date', 'exp_month', 'exp_year', 'cvc', 'billing_postal_code'),
         'address': ('address_line1', 'address_line2', 'city', 'state', 'postal_code', 'country'), 'otp': ('otp',)}
ROLES = frozenset(role for roles in KINDS.values() for role in roles)
ERROR_CODES = frozenset({'bad_request', 'not_armed', 'busy', 'no_clipboard', 'unavailable'})
BYE_REASONS = frozenset({'done', 'version', 'idle', 'error'})
FAIL_REASONS = frozenset({'denied', 'timeout', 'cancelled'})
SAVE_VALUES = frozenset({'username', 'password', 'new_password'})
SOURCES = frozenset({'system', 'play', 'other'})


def _int(low, high):
    return lambda v: type(v) is int and low <= v <= high


def _text(low, high):
    return lambda v: isinstance(v, str) and low <= len(v) <= high


def _nullable(check):
    return lambda v: v is None or check(v)


def _ident(v):
    return isinstance(v, str) and ID_RE.fullmatch(v) is not None


def _names(v):
    return isinstance(v, list) and len(v) <= 16 and all(isinstance(n, str) and 1 <= len(n) <= 253 for n in v)


def _senders(v):
    return isinstance(v, dict) and all(_names(v.get(k)) for k in ('packages', 'hosts', 'mail_packages'))


def _roles(v):
    return isinstance(v, list) and 1 <= len(v) <= 16 and all(r in ROLES for r in v)


def _items(v):
    if not isinstance(v, list) or not 1 <= len(v) <= 16:
        return False
    ids = [i.get('item_id') if isinstance(i, dict) else None for i in v]
    return (len(set(ids)) == len(ids) and all(_ident(i) for i in ids)
            and all(_text(1, 120)(i.get('label')) and _roles(i.get('roles')) for i in v))


def _values(allowed):
    return lambda v: isinstance(v, dict) and all(k in allowed and isinstance(x, str) and len(x) <= 4096 for k, x in v.items())


def _context(v):
    if not isinstance(v, dict) or not isinstance(v.get('package'), str) or not v['package']:
        return False
    certs, fields = v.get('certs'), v.get('fields')
    if not isinstance(certs, list) or not all(isinstance(c, str) and re.fullmatch(r'[0-9a-f]{64}', c) for c in certs):
        return False
    if not all(_nullable(_text(1, 253))(v.get(k)) for k in ('web_domain', 'web_scheme')) or v.get('source') not in SOURCES:
        return False
    return isinstance(fields, list) and all(isinstance(f, dict) and isinstance(f.get('id'), str) and f.get('role') in ROLES
                                            and isinstance(f.get('focused'), bool) for f in fields)


def _box(v):
    return isinstance(v, list) and len(v) == 4 and all(_int(-100000, 100000)(x) for x in v)


def _targets(v):
    """code_filled's filled fields (§6.4): the AutofillId as the context gives it, the field's view id and its screen bounds.
    Nothing else, so never a value."""
    return isinstance(v, list) and len(v) <= 16 and all(
        isinstance(t, dict) and set(t) <= {'id', 'view_id', 'bounds'} and _text(1, 128)(t.get('id'))
        and _nullable(_text(1, 255))(t.get('view_id'))
        and _nullable(_box)(t.get('bounds')) for t in v)


ANY = lambda v: True  # noqa: E731
REQ_ID = _int(1, 2 ** 31 - 1)
# t -> (direction, kind, {field: check}). kind: request (has id, gets one reply), reply (has re), event, answer (host's answer to
# a release_request, by req), control (hello/hello_ok/bye). The contract's tables (docs/agent-phone/channel-v1.md §5) list the same.
MESSAGES = {
    'hello_ok': (H2C, 'control', {'proto': _int(1, 65535)}),
    'ping': (H2C, 'request', {}),
    'context': (H2C, 'request', {}),
    'arm': (H2C, 'request', {'arm_id': _ident, 'kind': lambda v: v in KINDS, 'package': _text(1, 255),
                             'web_domain': _nullable(_text(1, 253)), 'items': _items, 'ttl_ms': _int(1, 20000)}),
    'disarm': (H2C, 'request', {'arm_id': _nullable(_ident)}),
    'release': (H2C, 'answer', {'req': _ident, 'values': _values(ROLES)}),
    'deny': (H2C, 'answer', {'req': _ident}),
    'arm_code': (H2C, 'request', {'arm_id': _ident, 'package': _text(1, 255), 'label': _text(1, 120), 'senders': _senders,
                                  'max_age_ms': _int(1, 600000), 'ttl_ms': _int(1, 20000)}),
    'note_password': (H2C, 'request', {'senders': _senders, 'max_age_ms': _int(1, 600000)}),
    'note_link': (H2C, 'request', {'senders': _senders, 'max_age_ms': _int(1, 600000)}),
    'open_link': (H2C, 'request', {'senders': _senders, 'max_age_ms': _int(1, 600000)}),
    'clip_set': (H2C, 'request', {'text': _text(1, 10000), 'ttl_ms': _int(1, 10000)}),
    'clip_clear': (H2C, 'request', {}),
    'bye': (None, 'control', {'reason': lambda v: v in BYE_REASONS}),
    'hello': (C2H, 'control', {'proto_min': _int(1, 65535), 'proto_max': _int(1, 65535), 'helper_version': _text(1, 64),
                               'version_code': _int(0, 2 ** 31 - 1)}),
    'ack': (C2H, 'reply', {}),
    'error': (C2H, 'reply', {'code': lambda v: v in ERROR_CODES}),
    'pong': (C2H, 'reply', {}),
    'context_result': (C2H, 'reply', {'context': _nullable(_context)}),
    'code_result': (C2H, 'reply', {'found': lambda v: isinstance(v, bool)}),
    'note_password_result': (C2H, 'reply', {'found': lambda v: isinstance(v, bool), 'value': ANY}),
    'note_link_result': (C2H, 'reply', {'found': lambda v: isinstance(v, bool), 'url': ANY}),
    'fill_context': (C2H, 'event', {'context': _context}),
    'release_request': (C2H, 'event', {'req': _ident, 'arm_id': _ident, 'item_id': _ident, 'roles': _roles, 'context': _context}),
    'filled': (C2H, 'event', {'req': _ident, 'arm_id': _ident, 'fields': _int(0, 64)}),
    'fill_failed': (C2H, 'event', {'req': _ident, 'arm_id': _ident, 'reason': lambda v: v in FAIL_REASONS}),
    'code_filled': (C2H, 'event', {'arm_id': _ident, 'fields': _int(0, 64), 'targets': _nullable(_targets)}),
    'save': (C2H, 'event', {'save_id': _ident, 'context': _context, 'values': _values(SAVE_VALUES)}),
}
# Which request each reply type answers (an error or ack may answer any request whose contract allows it).
REPLIES = {'ping': {'pong'}, 'context': {'context_result'}, 'arm': {'ack', 'error'}, 'disarm': {'ack', 'error'},
           'arm_code': {'code_result', 'error'}, 'note_password': {'note_password_result', 'error'},
           'note_link': {'note_link_result', 'error'}, 'open_link': {'error'}, 'clip_set': {'ack', 'error'}, 'clip_clear': {'ack', 'error'}}


def encode(message):
    """JSON for a message this side may send (a host message, or a helper one in the fake helper); ValueError if malformed."""
    data = json.dumps(message, ensure_ascii=False, separators=(',', ':')).encode('utf-8')
    if len(data) > MAX_PLAINTEXT:
        raise ValueError('message too large')
    return data


def decode(plaintext, direction):
    """The message object a sealed plaintext holds, checked against the contract for the direction it came from; Closed if not."""
    if len(plaintext) > MAX_PLAINTEXT or plaintext[:3] == b'\xef\xbb\xbf':
        raise Closed('message')
    try:
        message = json.loads(plaintext.decode('utf-8'))
    except (UnicodeDecodeError, ValueError):
        raise Closed('message') from None
    if not isinstance(message, dict) or not isinstance(message.get('t'), str):
        raise Closed('message')
    spec = MESSAGES.get(message['t'])
    if spec is None or spec[0] not in (None, direction):
        raise Closed('message')
    _, kind, fields = spec
    if kind == 'request' and not REQ_ID(message.get('id')) or kind == 'reply' and not REQ_ID(message.get('re')):
        raise Closed('message')
    for name, check in fields.items():
        if not check(message.get(name)):
            raise Closed('message')
    if message['t'] == 'arm' and not all(set(i['roles']) <= set(KINDS[message['kind']]) for i in message['items']):
        raise Closed('message')
    if message['t'] == 'hello' and message['proto_min'] > message['proto_max']:
        raise Closed('message')
    if message['t'] == 'note_password_result' and message['found'] is not (isinstance(message.get('value'), str) and 1 <= len(message['value']) <= 4096):
        raise Closed('message')
    if message['t'] == 'note_link_result' and message['found'] is not (
            isinstance(message.get('url'), str) and message['url'].startswith('https://') and len(message['url']) <= 2048):
        raise Closed('message')
    return message


def new_token():
    return secrets.token_bytes(TOKEN_BYTES)


def new_nonce():
    return secrets.token_bytes(NONCE_BYTES)


def new_id():
    return secrets.token_urlsafe(18)


# -- the session (host end) ------------------------------------------------------------------------------------------

log = logging.getLogger('beacon.agent_phone.channel')
HANDSHAKE_SECONDS = 5.0
REPLY_SECONDS = 10.0
IDLE_SECONDS = 20 * 60
MAX_PENDING = 64
NOT_LINKED = "The phone's helper is not connected."


class ChannelError(Exception):
    """A request could not be answered: the link is closed or gone. The text is a fixed sentence, never data from the wire."""


def _read_exact(sock, count, deadline):
    data = b''
    while len(data) < count:
        left = deadline - time.monotonic()
        if left <= 0:
            raise Closed('timeout')
        sock.settimeout(left)
        try:
            chunk = sock.recv(count - len(data))
        except socket.timeout:
            raise Closed('timeout') from None
        except OSError:
            raise Closed('socket') from None
        if not chunk:
            raise Closed('eof')
        data += chunk
    return data


def _close_quietly(sock):
    if sock is not None:
        try:
            sock.close()
        except OSError:
            pass


class Session:
    """One channel session: open() a listener, start() the helper through the private adb wrapper, then request()/answer().

    Events from the helper (fill_context, release_request, filled, fill_failed, code_filled, save) go to on_event(message) on the
    reader thread; on_event may call answer() at once. The token and both keys exist only while the session is open.
    """

    def __init__(self, on_event=None, *, handshake_seconds=HANDSHAKE_SECONDS, reply_seconds=REPLY_SECONDS,
                 idle_seconds=IDLE_SECONDS, keepalive_seconds=None, max_pending=MAX_PENDING):
        self.on_event = on_event
        self.handshake_seconds, self.reply_seconds, self.idle_seconds = handshake_seconds, reply_seconds, idle_seconds
        self.keepalive_seconds, self.max_pending = keepalive_seconds, max_pending
        self._lock = threading.RLock()
        self._send_lock = threading.Lock()
        self._token = self._keys = self._listener = self._link = None
        self._pending = 0
        self._next_id = 1
        self._waiting = {}  # request id -> [request type, Event, reply]
        self._send_seq = self._recv_seq = 0
        self._last_frame = time.monotonic()
        self.port = None
        self.linked = threading.Event()
        self.ready = threading.Event()  # the helper's hello was answered with hello_ok
        self.done = threading.Event()
        self.helper = None  # the helper's hello: version fields only
        self.version_mismatch = False
        self.rejected = 0  # connections that failed the handshake (a count, nothing else)

    # -- opening ---------------------------------------------------------------------------------------------------
    def open(self):
        with self._lock:
            if self._listener is not None or self._token is not None or self.done.is_set():
                raise ChannelError('This channel session is already open or over.')
            self._token = new_token()
            listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):  # Windows: no other process may bind this port beside us
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
            listener.bind(('127.0.0.1', 0))
            listener.listen(MAX_PENDING)
            listener.settimeout(0.2)
            self._listener, self.port = listener, listener.getsockname()[1]
        threading.Thread(target=self._accept_loop, name='beacon-agent-phone-accept', daemon=True).start()
        log.info('agent phone channel: session opened')
        return self.port

    def start_args(self):
        with self._lock:
            if self._token is None or self.port is None:
                raise ChannelError('Open the channel session first.')
            return start_command(self.port, self._token)

    def start(self, shell, timeout=None):
        """Starts the helper's channel service with shell(args), the private adb wrapper's `adb shell` (L1b), which must not
        log these arguments. Waits for the link and the helper's hello; False (and the session closed) when they did not come."""
        try:
            result = shell(self.start_args())
        except Exception:  # health: allow BLE001 -- the wrapper's failure ends the session; its text may hold the arguments
            log.info('agent phone channel: the helper start command failed')
            self.close('error')
            return False
        code = getattr(result, 'returncode', 0)
        said = '%s %s' % (getattr(result, 'stdout', '') or '', getattr(result, 'stderr', '') or '')
        if code not in (0, None) or 'Error:' in said:  # am exits 0 with "Error: Not found; no service started."
            log.info('agent phone channel: the helper did not start (exit %s)', code)
            self.close('error')
            return False
        if not self.ready.wait(timeout or (self.handshake_seconds + self.reply_seconds)):
            log.info('agent phone channel: no helper link')
            self.close('error')
            return False
        return True

    def _accept_loop(self):
        while True:
            with self._lock:
                listener = self._listener
            if listener is None:
                return
            try:
                conn, peer = listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self._lock:
                admit = self._link is None and self._pending < self.max_pending and not self.done.is_set() and peer[0] == '127.0.0.1'
                if admit:
                    self._pending += 1
            if not admit:
                _close_quietly(conn)
                continue
            threading.Thread(target=self._handshake, args=(conn,), name='beacon-agent-phone-hello', daemon=True).start()

    def _handshake(self, conn):
        deadline = time.monotonic() + self.handshake_seconds
        try:
            with self._lock:
                token = self._token
            if token is None:
                raise Closed('over')
            ch = _read_exact(conn, frame_length(_read_exact(conn, 4, deadline), CH_LEN), deadline)
            nc = read_client_hello(ch)
            ka = auth_key(token)
            sh = server_hello(ka, ch, new_nonce())
            conn.settimeout(max(0.01, deadline - time.monotonic()))
            conn.sendall(frame(sh))
            cp = _read_exact(conn, frame_length(_read_exact(conn, 4, deadline), CP_LEN), deadline)
            check_helper_proof(ka, ch, sh, cp)
            with self._lock:
                if self._link is not None or self._token is None:
                    raise Closed('taken')
                self._link, self._keys = conn, link_keys(token, nc, sh[5:37])
                self._send_seq = self._recv_seq = 0
                self._last_frame = time.monotonic()
                listener, self._listener = self._listener, None
            _close_quietly(listener)  # one link per session: nobody else may connect now
        except Exception:  # health: allow BLE001 -- a failed handshake only ever closes that connection
            with self._lock:
                self.rejected += 1
                self._pending -= 1
            _close_quietly(conn)
            return
        with self._lock:
            self._pending -= 1
        self.linked.set()
        log.info('agent phone channel: helper linked')
        if self.keepalive_seconds:
            threading.Thread(target=self._keepalive, name='beacon-agent-phone-ping', daemon=True).start()
        self._read_loop(conn)

    # -- the link --------------------------------------------------------------------------------------------------
    def _read_loop(self, conn):
        reason = 'error'
        try:
            while not self.done.is_set():
                conn.settimeout(1.0)
                try:
                    first = conn.recv(1)
                except socket.timeout:
                    if time.monotonic() - self._last_frame > self.idle_seconds:
                        reason = 'idle'
                        break
                    continue
                except OSError:
                    raise Closed('socket') from None
                if not first:
                    raise Closed('eof')
                deadline = time.monotonic() + self.reply_seconds
                body = _read_exact(conn, frame_length(first + _read_exact(conn, 3, deadline)), deadline)
                with self._lock:
                    keys, seq = self._keys, self._recv_seq
                if keys is None:
                    raise Closed('over')
                message = decode(open_sealed(keys[1], C2H, seq, body), C2H)
                with self._lock:
                    self._recv_seq += 1
                    self._last_frame = time.monotonic()
                if message['t'] == 'bye':
                    reason = None
                    break
                self._dispatch(message)
        except Closed:
            pass
        except Exception:  # health: allow BLE001 -- anything unexpected closes the link; nothing is echoed
            log.info('agent phone channel: link error')
        self.close(reason)

    def _dispatch(self, message):
        t = message['t']
        if not self.ready.is_set():
            if t != 'hello':
                raise Closed('order')
            self.helper = {k: message[k] for k in ('proto_min', 'proto_max', 'helper_version', 'version_code')}
            if not message['proto_min'] <= PROTOCOL_VERSION <= message['proto_max']:
                self.version_mismatch = True
                log.info('agent phone channel: the helper speaks another version')
                self._send({'t': 'bye', 'reason': 'version'})
                raise Closed('version')
            self._send({'t': 'hello_ok', 'proto': PROTOCOL_VERSION})
            self.ready.set()
            return
        if t == 'hello':
            raise Closed('order')
        if MESSAGES[t][1] == 'reply':
            with self._lock:
                waiting = self._waiting.get(message['re'])
            if waiting is None or t not in REPLIES[waiting[0]]:
                raise Closed('reply')
            waiting[2] = message
            waiting[1].set()
            return
        if self.on_event is not None:
            try:
                self.on_event(message)
            except Exception:  # health: allow BLE001 -- a handler's failure must not tear the link or echo data
                log.info('agent phone channel: the handler for %s failed', t)

    def _send(self, message):
        data = encode(message)
        with self._send_lock:
            with self._lock:
                link, keys, seq = self._link, self._keys, self._send_seq
                if link is None or keys is None:
                    raise ChannelError(NOT_LINKED)
                self._send_seq += 1
                self._last_frame = time.monotonic()
            try:
                link.sendall(frame(seal(keys[0], H2C, seq, data)))
            except OSError:
                raise ChannelError(NOT_LINKED) from None

    def _keepalive(self):
        while not self.done.wait(self.keepalive_seconds):
            if self.ready.is_set():
                try:
                    self.request('ping')
                except ChannelError:
                    return

    # -- what callers use ------------------------------------------------------------------------------------------
    def request(self, t, timeout=None, **fields):
        """Sends a host request and returns the helper's reply. No reply in time closes the link (contract §8)."""
        if MESSAGES.get(t, (None, None))[1] != 'request':
            raise ValueError('not a request type')
        if not self.ready.is_set():
            raise ChannelError(NOT_LINKED)
        event = threading.Event()
        with self._lock:
            request_id, self._next_id = self._next_id, self._next_id + 1
            self._waiting[request_id] = [t, event, None]
        try:
            self._send(dict(fields, t=t, id=request_id))
            if not event.wait(timeout or self.reply_seconds):
                log.info('agent phone channel: no reply to %s', t)
                self.close('error')
                raise ChannelError("The phone's helper stopped answering.")
            reply = self._waiting[request_id][2]
            if reply is None:
                raise ChannelError(NOT_LINKED)
            return reply
        finally:
            with self._lock:
                self._waiting.pop(request_id, None)

    def answer(self, t, **fields):
        """release or deny, for a release_request."""
        if t not in ('release', 'deny'):
            raise ValueError('not an answer type')
        self._send(dict(fields, t=t))

    def close(self, reason='done'):
        """Ends the session: bye when a link is up (reason None skips it), then the token, both keys and the sockets are gone."""
        with self._lock:
            if self.done.is_set():
                return
            ready = self.ready.is_set()
        if reason in BYE_REASONS and ready:
            try:
                self._send({'t': 'bye', 'reason': reason})
            except (ChannelError, ValueError):
                pass
        with self._lock:
            self.done.set()
            self.ready.clear()
            listener, link = self._listener, self._link
            self._listener = self._link = self._token = self._keys = None
            waiting = list(self._waiting.values())
        for entry in waiting:
            entry[1].set()  # their reply stays None: the caller gets ChannelError
        _close_quietly(listener)
        _close_quietly(link)
        log.info('agent phone channel: session closed')

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()


def connect(adb, events=None, timeout=None, **options):
    """One channel session on the live phone, or None when the helper did not link.

    adb is the phone's adb as a callable shell: agent_phone_adb.PhoneAdb (L1b; no adb server) or its .shell. It runs the one
    start command, quoting each argument for the phone's shell, and never logs it. events(session) returns the session's
    on_event handler, e.g. ``lambda s: vault.handler(s, owner=True)`` for a setup session the owner holds."""
    session = Session(**options)
    if events is not None:
        session.on_event = events(session)
    session.open()
    return session if session.start(adb, timeout) else None
