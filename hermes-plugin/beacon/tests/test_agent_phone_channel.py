"""The agent phone's vault channel (docs/agent-phone/channel-v1.md): the test vectors, the codec and the host end.

The vectors file is what the helper app (L8) builds against too. The first test recomputes every vector from the contract's
formulas with hmac, hashlib and AES-GCM alone, without the plugin's codec, so the doc, the file and the code cannot drift apart.
"""
import hashlib
import hmac
import importlib.util
import json
import logging
import re
import socket
import struct
import subprocess
import threading
import time
import unicodedata
from pathlib import Path
from types import SimpleNamespace
from urllib.parse import urlsplit

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF

PLUGIN = Path(__file__).resolve().parents[1]
DOC = PLUGIN.parents[1] / 'docs' / 'agent-phone' / 'channel-v1.md'
VECTORS = json.loads((PLUGIN / 'tests' / 'fixtures' / 'agent_phone_channel_v1_vectors.json').read_text(encoding='utf-8'))
LABEL = b'beacon-agent-phone-channel/1 '


def load_channel():
    spec = importlib.util.spec_from_file_location('fixture_agent_phone_channel', PLUGIN / 'agent_phone_channel.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def b(text):
    return bytes.fromhex(text)


# -- 1. the vectors, recomputed from the contract alone --------------------------------------------------------------

def _hkdf(ikm, salt, info):
    prk = hmac.new(salt if salt else bytes(32), ikm, hashlib.sha256).digest()
    return hmac.new(prk, info + b'\x01', hashlib.sha256).digest()  # L = 32 is one block


def test_independent_recomputation_matches_every_vector():
    h = VECTORS['handshake']
    t, nc, ns = b(h['T']), b(h['Nc']), b(h['Ns'])
    ka = _hkdf(t, b'', LABEL + b'auth')
    assert ka == HKDF(hashes.SHA256(), 32, None, LABEL + b'auth').derive(t)  # a second HKDF agrees with the hand-rolled one
    assert ka.hex() == h['Ka']
    ch = b'BAPC' + b'\x01' + nc
    sh0 = b'BAPC' + b'\x01' + ns
    ps = hmac.new(ka, LABEL + b'host proof' + ch + sh0, hashlib.sha256).digest()
    pc = hmac.new(ka, LABEL + b'helper proof' + ch + sh0 + ps, hashlib.sha256).digest()
    assert (ch.hex(), sh0.hex(), ps.hex(), (sh0 + ps).hex(), pc.hex(), h['CP']) == (h['CH'], h['SH0'], h['Ps'], h['SH'], h['Pc'], pc.hex())
    for name, body in (('CH', ch), ('SH', sh0 + ps), ('CP', pc)):
        assert h['frames'][name] == (struct.pack('>I', len(body)) + body).hex()
    assert (len(ch), len(sh0 + ps), len(pc)) == (37, 69, 32)
    salt = nc + ns
    keys = {'host->helper': _hkdf(t, salt, LABEL + b'host->helper'), 'helper->host': _hkdf(t, salt, LABEL + b'helper->host')}
    assert (salt.hex(), keys['host->helper'].hex(), keys['helper->host'].hex()) == (h['salt'], h['K_h2c'], h['K_c2h'])
    import base64
    assert base64.urlsafe_b64encode(t).rstrip(b'=').decode() == h['T_b64url']
    seen = {'host->helper': -1, 'helper->host': -1}
    for v in VECTORS['sealed']:
        direction = 1 if v['dir'] == 'host->helper' else 2
        assert v['seq'] == seen[v['dir']] + 1  # each direction counts from 0 with no gap
        seen[v['dir']] = v['seq']
        nonce = bytes(4) + struct.pack('>Q', v['seq'])
        aad = b'BAPC' + bytes([1, direction]) + struct.pack('>Q', v['seq'])
        plaintext = v['plaintext_text'].encode('utf-8')
        assert plaintext.hex() == v['plaintext_hex'] and json.loads(plaintext)['t']
        body = struct.pack('>Q', v['seq']) + AESGCM(keys[v['dir']]).encrypt(nonce, plaintext, aad)
        assert (nonce.hex(), aad.hex(), body.hex()) == (v['nonce'], v['aad'], v['body'])
        assert v['frame'] == (struct.pack('>I', len(body)) + body).hex()


def test_independent_check_rejects_every_negative_crypto_vector():
    h = VECTORS['handshake']
    t, ch = b(h['T']), b(h['CH'])
    ka = _hkdf(t, b'', LABEL + b'auth')
    keys = {'host->helper': b(h['K_h2c']), 'helper->host': b(h['K_c2h'])}
    checked = 0
    for v in VECTORS['negative']:
        if v['check'] == 'helper_checks_SH':
            sh = b(v['SH'])
            assert sh[37:] != hmac.new(ka, LABEL + b'host proof' + ch + sh[:37], hashlib.sha256).digest(), v['name']
        elif v['check'] == 'host_checks_CP':
            assert b(v['CP']) != b(h['Pc']), v['name']
        elif v['check'] == 'open':
            body, direction = b(v['body']), 1 if v['dir'] == 'host->helper' else 2
            seq = struct.unpack('>Q', body[:8])[0]
            if seq == v['expected_seq']:
                with pytest.raises(InvalidTag):
                    AESGCM(keys[v['dir']]).decrypt(bytes(4) + body[:8], body[8:], b'BAPC' + bytes([1, direction]) + body[:8])
        else:
            continue
        checked += 1
    assert checked >= 8


# -- 2. the plugin's codec against the vectors -----------------------------------------------------------------------

def test_vector_codec_reproduces_every_positive_vector():
    c = load_channel()
    h = VECTORS['handshake']
    t, nc, ns = b(h['T']), b(h['Nc']), b(h['Ns'])
    ka = c.auth_key(t)
    ch = c.client_hello(nc)
    sh = c.server_hello(ka, ch, ns)
    assert (ka.hex(), ch.hex(), sh.hex()) == (h['Ka'], h['CH'], h['SH'])
    assert c.read_client_hello(ch) == nc and c.check_server_hello(ka, ch, sh) == ns
    pc = c.helper_proof(ka, ch, sh[:37], sh[37:])
    assert pc.hex() == h['Pc']
    c.check_helper_proof(ka, ch, sh, pc)
    k_h2c, k_c2h = c.link_keys(t, nc, ns)
    assert (k_h2c.hex(), k_c2h.hex()) == (h['K_h2c'], h['K_c2h'])
    assert c.token_text(t) == h['T_b64url'] and c.token_from_text(h['T_b64url']) == t
    template = [c.token_text(t) if a == '{T_b64url}' else a for a in h['start_command']]
    assert c.start_command(h['start_command_port'], t) == template
    keys = {'host->helper': (k_h2c, c.H2C), 'helper->host': (k_c2h, c.C2H)}
    for v in VECTORS['sealed']:
        key, direction = keys[v['dir']]
        plaintext = b(v['plaintext_hex'])
        message = c.decode(plaintext, direction)
        assert c.encode(message) == plaintext
        assert c.seal(key, direction, v['seq'], plaintext).hex() == v['body']
        assert c.open_sealed(key, direction, v['seq'], b(v['body'])) == plaintext
        assert c.frame(b(v['body'])).hex() == v['frame']
        assert c.frame_length(b(v['frame'])[:4]) == len(b(v['body']))


def test_vector_codec_closes_on_every_negative_vector():
    c = load_channel()
    h = VECTORS['handshake']
    t, ch, sh = b(h['T']), b(h['CH']), b(h['SH'])
    ka = c.auth_key(t)
    keys = {'host->helper': (b(h['K_h2c']), c.H2C), 'helper->host': (b(h['K_c2h']), c.C2H)}
    names = []
    for v in VECTORS['negative']:
        with pytest.raises(c.Closed):
            if v['check'] == 'helper_checks_SH':
                c.check_server_hello(ka, ch, b(v['SH']))
            elif v['check'] == 'host_checks_CP':
                c.check_helper_proof(ka, ch, sh, b(v['CP']))
            elif v['check'] == 'host_reads_CH':
                c.read_client_hello(b(v['CH']))
            elif v['check'] == 'handshake_length':
                c.frame_length(b(v['header']), v['expected'])
            elif v['check'] == 'sealed_length':
                c.frame_length(b(v['header']))
            elif v['check'] == 'open':
                key, direction = keys[v['dir']]
                c.open_sealed(key, direction, v['expected_seq'], b(v['body']))
            elif v['check'] == 'decode':
                c.decode(b(v['plaintext_hex']), keys[v['dir']][1])
            else:
                pytest.fail('unknown negative check ' + v['check'])
        names.append(v['name'])
    for needed in ('wrong token', 'replayed', 'skipped', 'other direction key', 'flipped tag', 'bad magic', 'over 262144',
                   'item role outside', 'one item_id', 'without item_id', 'without source', 'without a url', 'plain http url'):
        assert any(needed in n for n in names), needed


def test_vector_positive_controls_open_where_the_negatives_close():
    # The negatives close because of the one thing each changes: the same check passes on the matching good input.
    c = load_channel()
    h = VECTORS['handshake']
    k_c2h = b(h['K_c2h'])
    hello = next(v for v in VECTORS['sealed'] if v['dir'] == 'helper->host' and v['seq'] == 0)
    assert c.open_sealed(k_c2h, c.C2H, 0, b(hello['body']))
    assert c.frame_length(struct.pack('>I', 262144)) == 262144 and c.frame_length(struct.pack('>I', 25)) == 25
    assert c.frame_length(struct.pack('>I', 37), 37) == 37
    assert c.decode(b'{"t":"pong","re":1}', c.C2H)['re'] == 1


# -- 3. the doc and the code list the same messages ------------------------------------------------------------------

def _doc_types(section):
    text = DOC.read_text(encoding='utf-8')
    part = text.split(section, 1)[1].split('\n### ', 1)[0].split('\n## ', 1)[0]
    return {m for m in re.findall(r'^\| `([a-z_]+)` \|', part, re.M)} - {'t'}  # 't' is the header cell


def test_doc_and_code_list_the_same_message_types_in_each_direction():
    c = load_channel()
    host, helper = _doc_types('### 5.1 Host to helper'), _doc_types('### 5.2 Helper to host')
    assert host == {t for t, spec in c.MESSAGES.items() if spec[0] in (c.H2C, None)}
    assert VECTORS['revision'] == 5 and 'Contract revision 5' in DOC.read_text(encoding='utf-8')
    assert helper == {t for t, spec in c.MESSAGES.items() if spec[0] in (c.C2H, None)}
    assert set(c.REPLIES) == {t for t, spec in c.MESSAGES.items() if spec[1] == 'request'}


def test_doc_role_table_matches_the_code_and_hermes_field_names():
    c = load_channel()
    text = DOC.read_text(encoding='utf-8').split('### 6.2 Roles and arms', 1)[1].split('\n### ', 1)[0]
    doc_roles = set()
    for cell in re.findall(r'^\| ((?:`[a-z_0-9]+`(?:, )?)+) \|', text, re.M):
        doc_roles |= set(re.findall(r'`([a-z_0-9]+)`', cell))
    assert doc_roles == set(c.ROLES)
    # The card and address roles are Hermes' own vault field names (agent/vault_store.py PAYMENT_FIELDS / ADDRESS_FIELDS).
    hermes = pytest.importorskip('agent.vault_store')
    assert set(hermes.PAYMENT_FIELDS) <= set(c.KINDS['payment'])
    assert set(hermes.ADDRESS_FIELDS) == set(c.KINDS['address'])


# -- 4. the notification rules (§6.4-6.6), with a reference reading of the contract ---------------------------------

ADDRESS = re.compile(r'[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+\.?)')
CODE_WORD = re.compile(r'(?i)code|otp|verification|verify|passcode|رمز')
CODE = re.compile(r'(?<![0-9])[0-9]{4,8}(?![0-9])')
PASSWORD_WORD = re.compile(r'(?i)temporary password|password|كلمة المرور|كلمة السر')
LINK = re.compile(r'https://[^\s<>"\')]+')


def _host_ok(name, hosts):
    return any(name == h or name.endswith('.' + h) for h in hosts)


def _sender_ok(note, senders):
    if note['package'] in senders['packages']:
        return True
    if note['package'] not in senders['mail_packages']:
        return False
    found = ADDRESS.search(note['title'] + ' ' + note['text'])
    if not found:
        return False
    domain = found.group(1).lower()
    return _host_ok(domain[:-1] if domain.endswith('.') else domain, senders['hosts'])


def _matching(notes, senders, now, max_age):
    live = [n for n in notes if now - n['posted_ms'] <= max_age and _sender_ok(n, senders)]
    return sorted(live, key=lambda n: n['posted_ms'], reverse=True)


def _expand(notes):
    return [dict(n, title=n['title'].replace('{at}', '@'), text=n['text'].replace('{at}', '@')) for n in notes]


def test_notification_vectors_follow_the_contract_rules():
    v = VECTORS['notifications']
    now, senders = v['now_ms'], v['senders']
    for case in v['codes']:
        got = None
        for note in _matching(_expand(case['notes']), senders, now, case['max_age_ms']):
            text = note['title'] + ' ' + note['text']
            code = CODE.search(text)
            if CODE_WORD.search(text) and code:
                got = code.group(0)
                break
        assert got == case['code'], case['name']
    for case in v['passwords']:
        got = None
        for note in _matching(_expand(case['notes']), senders, now, case['max_age_ms'])[:1]:
            text = note['title'] + ' ' + note['text']
            word = PASSWORD_WORD.search(text)
            if word:
                rest = re.match(r'\s*(?::|is(?=\s))?\s*(\S+)', text[word.end():])
                value = rest.group(1) if rest else ''
                value = value[:-1] if value[-1:] in ('.', ',', ';') else value
                got = value if 6 <= len(value) <= 64 else None
        assert got == case['value'], case['name']
    for case in v['links']:
        notes = _matching(_expand(case['notes']), senders, now, case['max_age_ms'])
        if not notes:
            result = {'found': False}
        else:
            urls = [u for u in LINK.findall(notes[0]['title'] + ' ' + notes[0]['text'])
                    if _host_ok((urlsplit(u).hostname or '').lower(), senders['hosts'])]
            result = {'found': True, 'url': urls[0]} if urls else {'found': False}
        assert result == case['result'], case['name']


def test_notification_vectors_cover_the_look_alikes_b8_needs():
    names = ' '.join(case['name'] for case in VECTORS['notifications']['codes'])
    for needed in ('another app', 'longer domain', 'look-alike', 'Cyrillic', 'not in mail_packages', 'first address'):
        assert needed in names, needed
    # The Cyrillic case really holds a non-Latin letter; the others are ASCII.
    cyrillic = next(c for c in VECTORS['notifications']['codes'] if 'Cyrillic' in c['name'])
    assert any(unicodedata.name(ch).startswith('CYRILLIC') for ch in cyrillic['notes'][0]['text'])


# -- 5. the host end over real loopback sockets, with a fake helper --------------------------------------------------

class FakeHelper:
    """The helper end of the contract, enough to drive the host: dial, prove, hello, answer requests, send events."""

    def __init__(self, c, port, token, proto=(1, 1), context=None):
        self.c, self.port, self.token, self.proto, self.context = c, port, token, proto, context
        self.sock = self.keys = None
        self.got = []  # host messages received
        self.send_seq = self.recv_seq = 0
        self.closed = threading.Event()
        self.reply_to_requests = True

    def dial(self):
        c = self.c
        self.sock = socket.create_connection(('127.0.0.1', self.port), timeout=5)
        nc = c.new_nonce()
        ch = c.client_hello(nc)
        self.sock.sendall(c.frame(ch))
        ka = c.auth_key(self.token)
        sh = self._read(c.SH_LEN)
        ns = c.check_server_hello(ka, ch, sh)
        self.sock.sendall(c.frame(c.helper_proof(ka, ch, sh[:37], sh[37:])))
        self.keys = c.link_keys(self.token, nc, ns)
        self.sock.settimeout(None)
        self.send({'t': 'hello', 'proto_min': self.proto[0], 'proto_max': self.proto[1], 'helper_version': '1.2.1', 'version_code': 121})
        threading.Thread(target=self._loop, daemon=True).start()
        return self

    def _read(self, expected=None):
        length = struct.unpack('>I', self._exact(4))[0]
        if expected is not None and length != expected:
            raise EOFError
        return self._exact(length)

    def _exact(self, n):
        data = b''
        while len(data) < n:
            chunk = self.sock.recv(n - len(data))
            if not chunk:
                raise EOFError
            data += chunk
        return data

    def send(self, message):
        body = self.c.seal(self.keys[1], self.c.C2H, self.send_seq, json.dumps(message).encode())
        self.send_seq += 1
        self.sock.sendall(self.c.frame(body))

    def _loop(self):
        try:
            while True:
                message = json.loads(self.c.open_sealed(self.keys[0], self.c.H2C, self.recv_seq, self._read()))
                self.recv_seq += 1
                self.got.append(message)
                if message['t'] == 'bye':
                    break
                if 'id' in message and self.reply_to_requests:
                    self.reply(message)
        except Exception:
            pass
        try:
            self.sock.close()
        except OSError:
            pass
        self.closed.set()

    def reply(self, m):
        kinds = {'context': {'t': 'context_result', 'context': self.context}, 'ping': {'t': 'pong'},
                 'arm_code': {'t': 'code_result', 'found': False}}
        self.send(dict(kinds.get(m['t'], {'t': 'ack'}), re=m['id']))

    def wait_for(self, t, timeout=5):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            found = [m for m in self.got if m['t'] == t]
            if found:
                return found[-1]
            time.sleep(0.01)
        raise AssertionError('no ' + t)


CONTEXT = {'package': 'dev.beacon.fixture.shop', 'certs': ['ab' * 32], 'web_domain': None, 'web_scheme': None,
           'fields': [{'id': 'af-1', 'role': 'password', 'focused': True}], 'at_ms': 1, 'source': 'play'}
REQ, ARM, ITEM = 'req_BBBBBBBBBBBBBBBB', 'arm_AAAAAAAAAAAAAAAA', 'item_DDDDDDDDDDDDDDD'


def _session(c, **kw):
    events = []
    s = c.Session(on_event=events.append, **kw)
    s.open()
    return s, events


def _linked(c, **kw):
    s, events = _session(c, **kw)
    helper = FakeHelper(c, s.port, s._token, context=CONTEXT).dial()
    assert s.ready.wait(5)
    return s, events, helper


def _wait(condition, timeout=5):
    end = time.monotonic() + timeout
    while not condition() and time.monotonic() < end:
        time.sleep(0.01)
    return condition()


def test_session_links_after_both_proofs_and_answers_requests():
    c = load_channel()
    s, events, helper = _linked(c)
    try:
        assert helper.wait_for('hello_ok')['proto'] == 1
        assert s.helper == {'proto_min': 1, 'proto_max': 1, 'helper_version': '1.2.1', 'version_code': 121}
        assert s.request('context')['context'] == CONTEXT
        assert s.request('ping')['t'] == 'pong'
        helper.send({'t': 'release_request', 'req': REQ, 'arm_id': ARM, 'item_id': ITEM, 'roles': ['password'], 'context': CONTEXT})
        assert _wait(lambda: events) and events[0]['t'] == 'release_request'
        s.answer('release', req=REQ, values={'password': 'Test-Pass-1111'})
        assert helper.wait_for('release')['values'] == {'password': 'Test-Pass-1111'}
    finally:
        s.close()
    assert helper.wait_for('bye')['reason'] == 'done'


def test_a_wrong_token_never_links_and_the_real_helper_still_does():
    c = load_channel()
    s, _ = _session(c)
    try:
        with pytest.raises(c.Closed):
            FakeHelper(c, s.port, bytes(32)).dial()  # the host's proof fails on the helper's side; nothing links
        assert not s.linked.is_set()
        FakeHelper(c, s.port, s._token, context=CONTEXT).dial()
        assert s.ready.wait(5)
    finally:
        s.close()


def test_a_helper_that_cannot_prove_the_token_is_closed_without_touching_the_link():
    c = load_channel()
    s, _ = _session(c)
    try:
        sock = socket.create_connection(('127.0.0.1', s.port), timeout=5)
        sock.sendall(c.frame(c.client_hello(c.new_nonce())))
        got = b''
        while len(got) < 73:
            got += sock.recv(73 - len(got))
        sock.sendall(c.frame(bytes(32)))  # a guessed helper proof
        assert sock.recv(1) == b''  # closed
        assert _wait(lambda: s.rejected == 1)
        assert not s.linked.is_set()
        FakeHelper(c, s.port, s._token).dial()  # the real helper still gets in
        assert s.ready.wait(5)
    finally:
        s.close()


def test_a_junk_connection_held_open_does_not_delay_the_real_helper():
    c = load_channel()
    s, _ = _session(c, handshake_seconds=5)
    junk = [socket.create_connection(('127.0.0.1', s.port)) for _ in range(5)]
    junk[0].sendall(b'GET / HTTP/1.1\r\n\r\n')
    try:
        started = time.monotonic()
        FakeHelper(c, s.port, s._token).dial()
        assert s.ready.wait(5)
        assert time.monotonic() - started < 1.5  # far under the 5 s each junk connection may keep its slot
    finally:
        for sock in junk:
            sock.close()
        s.close()


def test_the_listener_closes_once_linked_so_no_second_connection_gets_in():
    c = load_channel()
    s, _, helper = _linked(c)
    try:
        try:
            with socket.create_connection(('127.0.0.1', s.port), timeout=2) as late:
                late.settimeout(2)
                assert late.recv(1) == b''  # if the connect is accepted by a lingering backlog, it is closed at once
        except OSError:
            pass  # refused: the listener is gone
        assert s.request('ping')['t'] == 'pong'  # the link is untouched
    finally:
        s.close()


def test_pending_handshakes_are_capped():
    c = load_channel()
    s, _ = _session(c, max_pending=3)
    held = [socket.create_connection(('127.0.0.1', s.port)) for _ in range(3)]
    try:
        assert _wait(lambda: s._pending == 3)
        extra = socket.create_connection(('127.0.0.1', s.port), timeout=2)
        extra.settimeout(2)
        assert extra.recv(1) == b''  # over the cap: closed on accept
        extra.close()
    finally:
        for sock in held:
            sock.close()
        s.close()


def test_a_replayed_frame_closes_the_link():
    c = load_channel()
    s, _, helper = _linked(c)
    helper.sock.sendall(c.frame(c.seal(helper.keys[1], c.C2H, 0, json.dumps({'t': 'pong', 're': 1}).encode())))  # seq 0 again
    assert s.done.wait(5)
    with pytest.raises(c.ChannelError):
        s.request('ping')


@pytest.mark.parametrize('message', [{'t': 'surprise'}, {'t': 'ack', 're': 99},
                                     {'t': 'hello', 'proto_min': 1, 'proto_max': 1, 'helper_version': 'x', 'version_code': 1}])
def test_unknown_types_stray_replies_and_a_second_hello_close_the_link(message):
    c = load_channel()
    s, _, helper = _linked(c)
    helper.send(message)
    assert s.done.wait(5)


def test_another_protocol_version_ends_with_bye_version():
    c = load_channel()
    s, _ = _session(c)
    helper = FakeHelper(c, s.port, s._token, proto=(2, 3)).dial()
    assert helper.wait_for('bye')['reason'] == 'version'
    assert s.done.wait(5) and s.version_mismatch and not s.ready.is_set()


def test_no_reply_in_time_closes_the_link():
    c = load_channel()
    s, _, helper = _linked(c, reply_seconds=0.5)
    helper.reply_to_requests = False
    with pytest.raises(c.ChannelError):
        s.request('context')
    assert s.done.is_set()


def test_each_session_has_its_own_token_and_port_and_forgets_them_at_the_end():
    c = load_channel()
    first, _ = _session(c)
    second, _ = _session(c)
    try:
        assert first._token != second._token and first.port != second.port
        assert first.start_args()[-1] != second.start_args()[-1]
    finally:
        first.close()
        second.close()
    assert first._token is None and first._keys is None and second._token is None
    with pytest.raises(c.ChannelError):
        first.start_args()
    with pytest.raises(c.ChannelError):
        first.open()  # a session is never reopened with a new token: a new session is made instead


def test_bye_from_the_helper_ends_the_session_and_drops_the_keys():
    c = load_channel()
    s, _, helper = _linked(c)
    helper.send({'t': 'bye', 'reason': 'done'})
    assert s.done.wait(5)
    assert s._keys is None and s._token is None


def test_start_runs_the_one_shell_command_and_waits_for_the_helper(caplog):
    c = load_channel()
    caplog.set_level(logging.DEBUG)
    s, _ = _session(c)
    ran = []

    def shell(args):
        ran.append(list(args))
        token = c.token_from_text(args[-1])
        threading.Thread(target=lambda: FakeHelper(c, int(args[6]), token).dial(), daemon=True).start()
        return SimpleNamespace(returncode=0)

    try:
        assert s.start(shell, timeout=5)
        assert ran[0][:6] == ['am', 'start-foreground-service', '-n', 'dev.hermes.agentphone.helper/.ChannelService', '--ei', 'port']
        token_text = ran[0][-1]
        assert s.request('ping')['t'] == 'pong'
    finally:
        s.close()
    assert 'helper linked' in caplog.text  # the log is there (positive control) and holds no token in either form
    assert token_text not in caplog.text and c.token_from_text(token_text).hex() not in caplog.text


def test_a_failed_start_closes_the_session():
    c = load_channel()
    s, _ = _session(c)
    assert not s.start(lambda args: SimpleNamespace(returncode=1))
    assert s.done.is_set() and s._token is None


def test_a_start_command_that_raises_closes_the_session():
    c = load_channel()
    s, _ = _session(c)

    def shell(args):
        raise RuntimeError('adb failed')
    assert not s.start(shell)
    assert s.done.is_set() and s._token is None and s._listener is None


def test_values_never_reach_the_log(caplog):
    c = load_channel()
    caplog.set_level(logging.DEBUG)
    s, events, helper = _linked(c)
    canary = 'Canary-Value-9137'
    helper.send({'t': 'save', 'save_id': 'save_CCCCCCCCCCCCCCC', 'context': CONTEXT, 'values': {'password': canary}})
    assert _wait(lambda: events) and events[0]['values']['password'] == canary  # positive control: the value did arrive
    s.close()
    assert canary not in caplog.text and 'session closed' in caplog.text


# -- 6. the live path through L1b's adb (agent_phone_adb.PhoneAdb: a callable shell, no adb server) -----------------------

class PhoneAdbShape:
    """PhoneAdb's surface as L1b ships it: callable as shell(args), quotes each argument for the phone's sh, returns a
    CompletedProcess whose args are hidden, raises AdbError (fixed text) when the phone can't be reached."""

    class AdbError(Exception):
        pass

    def __init__(self, c, reachable=True, output='Starting service: Intent { cmp=dev.hermes.agentphone.helper/.ChannelService (has extras) }'):
        self.c, self.reachable, self.output, self.lines = c, reachable, output, []

    def __call__(self, args):
        if not self.reachable:
            raise self.AdbError('The phone is not reachable.')
        self.lines.append(' '.join("'" + str(a).replace("'", "'\\''") + "'" for a in args))  # L1b's quote()
        if self.output.startswith('Starting'):
            port, token = int(args[args.index('port') + 1]), self.c.token_from_text(args[args.index('token') + 1])
            threading.Thread(target=lambda: FakeHelper(self.c, port, token).dial(), daemon=True).start()
        return subprocess.CompletedProcess('<agent phone command>', 0, self.output, '')


def test_connect_starts_the_helper_through_the_phones_adb_and_links():
    c = load_channel()
    adb, seen = PhoneAdbShape(c), []
    session = c.connect(adb, events=lambda s: (seen.append(s), seen.append)[1], timeout=5)
    try:
        assert session is not None and session.ready.is_set() and seen[0] is session
        assert session.request('ping')['t'] == 'pong'
        # Every start argument is plain (no shell metacharacters), so L1b's per-argument quoting passes it through unchanged.
        assert all(re.fullmatch(r'[A-Za-z0-9_./-]+', a) for a in session.start_args())
        assert adb.lines[0].startswith("'am' 'start-foreground-service' '-n' 'dev.hermes.agentphone.helper/.ChannelService'")
    finally:
        session.close()


def test_connect_gives_none_when_the_phone_or_the_helper_is_not_there():
    c = load_channel()
    assert c.connect(PhoneAdbShape(c, reachable=False), timeout=5) is None
    started = time.monotonic()
    assert c.connect(PhoneAdbShape(c, output='Error: Not found; no service started.'), timeout=5) is None
    assert time.monotonic() - started < 2  # am's own error ends it at once, not after the link timeout
