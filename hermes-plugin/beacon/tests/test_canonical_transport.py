"""Canonical transport tests use only synthetic identities, homes and transports."""
import sys

from test_mobile import harness


def test_admission_gateway_ticket_contract_exists_for_authenticated_profile(harness, monkeypatch):
    api, client, _, tmp, app = harness
    from hermes_cli.dashboard_auth.base import Session
    session = Session(user_id='owner', provider='basic', issuer='', email='', display_name='', org_id='',
                      expires_at=int(time.time()) + 120, access_token='synthetic-token', refresh_token='')
    del client.headers['x-fixture-owner']
    @app.middleware('http')
    async def authenticated(request, call_next):
        request.state.session = session
        return await call_next(request)
    transport = sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: False)
    endpoint = SimpleNamespace(profile_id=str(tmp), instance_id='instance', authority_epoch=7,
                               runtime_protocol=1, api_origin='http://127.0.0.1:1234')
    monkeypatch.setattr(transport, 'native_policy', lambda profile: tmp)
    monkeypatch.setattr(transport, 'discover', lambda home: endpoint)
    def exchange(endpoint, profile, access_token, nonce, stored=None, runtime=None, authority_only=False,timeout=5):
        assert access_token == 'synthetic-token'
        return {'ticket': 'upstream-only', 'profile': profile, 'home': transport.home_digest(tmp),
                'instance': 'instance', 'epoch': 7, 'gateway_epoch':7, 'nonce': nonce,
                'subject': transport.subject_digest(session)}
    monkeypatch.setattr(transport, 'exchange', exchange)
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={})
    assert response.status_code == 200, 'Authenticated canonical admission route is missing'
    value = response.json()
    assert set(value) == {'ticket', 'path', 'profile', 'canonical', 'expires_in'}
    assert value['path'] == '/api/plugins/beacon/gateway/ws'
    assert value['profile'] == 'default' and value['canonical'] is True
    assert isinstance(value['ticket'], str) and value['ticket'] and value['ticket'] != 'upstream-only'
    assert 0 < value['expires_in'] <= 25
    assert response.headers['cache-control'] == 'no-store'

import asyncio
import hashlib
import importlib.util
import json
import time
from pathlib import Path
from types import SimpleNamespace

import pytest


@pytest.fixture
def transport():
    path = Path(__file__).resolve().parents[1] / 'canonical_transport.py'
    spec = importlib.util.spec_from_file_location('test_canonical_transport_impl', path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def test_admission_subject_includes_provider_issuer_and_user(transport):
    from gateway.session_identity import authenticated_subject
    original = SimpleNamespace(provider='oidc', issuer='https://issuer.example', user_id='person')
    digest = transport.subject_digest(original)
    expected = hashlib.sha256(authenticated_subject({'provider': 'oidc', 'issuer': 'https://issuer.example', 'user_id': 'person'}).encode()).hexdigest()
    assert digest == expected
    for altered in (
        SimpleNamespace(provider='other', issuer=original.issuer, user_id=original.user_id),
        SimpleNamespace(provider=original.provider, issuer='https://elsewhere.example', user_id=original.user_id),
        SimpleNamespace(provider=original.provider, issuer=original.issuer, user_id='other'),
    ):
        assert transport.subject_digest(altered) != digest
    with pytest.raises(ValueError):
        transport.subject_digest(SimpleNamespace(provider='oidc', user_id='person'))


def test_admission_endpoint_rejects_other_home_and_non_loopback(transport, tmp_path):
    endpoint = SimpleNamespace(profile_id=str(tmp_path), instance_id='abc', authority_epoch=2,
                               runtime_protocol=1, api_origin='http://127.0.0.1:1234')
    discovery = SimpleNamespace(state='ready', endpoint=endpoint)
    assert transport.validate_endpoint(discovery, tmp_path) is endpoint
    for origin in ('http://localhost:1234', 'http://192.0.2.1:1234', 'http://127.0.0.1:1234/x',
                   'http://user@127.0.0.1:1234', 'http://127.0.0.1:1234?x=y'):
        endpoint.api_origin = origin
        with pytest.raises(ValueError):
            transport.validate_endpoint(discovery, tmp_path)
    endpoint.api_origin = 'http://127.0.0.1:1234'
    with pytest.raises(ValueError):
        transport.validate_endpoint(discovery, tmp_path / 'other')
    discovery.state = 'draining'
    with pytest.raises(ValueError):
        transport.validate_endpoint(discovery, tmp_path)


def test_relay_ticket_is_one_use_expires_and_is_capacity_bounded(transport):
    now = [100.0]
    cache = transport.TicketCache(capacity=1, ttl=20, clock=lambda: now[0])
    ticket = cache.issue(('owner', 'upstream'))
    assert cache.consume(ticket) == ('owner', 'upstream')
    assert cache.consume(ticket) is None
    old = cache.issue(('owner', 'upstream'))
    with pytest.raises(ValueError):
        cache.issue(('owner', 'other'))
    now[0] += 21
    assert cache.consume(old) is None
    assert cache.consume(cache.issue(('owner', 'new'))) == ('owner', 'new')


def test_relay_validates_requests_but_preserves_responses(transport):
    accepted = '{"jsonrpc":"2.0","id":1,"method":"session.info","params":{"profile":"work","x":4}}'
    response = '{"jsonrpc":"2.0","id":"approval","result":{"choice":"allow"}}'
    capability = '{"jsonrpc":"2.0","id":2,"method":"client.capabilities","params":{"server_requests":true}}'
    assert transport.validate_frame(accepted, 'work', False) is False
    assert transport.validate_frame(response, 'work', False) is False
    assert transport.validate_frame('{"id":null,"error":{"code":-32600}}', 'work', False) is False
    assert transport.validate_frame(capability, 'work', False) is True
    for bad in (
        '{"id":1,"method":"session.info","params":{"profile":"other"}}',
        '{"id":1,"method":"session.info","params":{}}',
        '{"id":1,"method":"session.info","params":{"profile":"work","profile":"other"}}',
        '{"id":1,"method":"session.info","params":{"profile":"work","x":NaN}}',
        '[{"id":1,"method":"session.info","params":{"profile":"work"}}]',
    ):
        with pytest.raises(ValueError):
            transport.validate_frame(bad, 'work', False)
    with pytest.raises(ValueError):
        transport.validate_frame(capability, 'work', True)


def test_limits_cancelled_worker_keeps_its_permit_until_finished(transport):
    import threading
    started, release = threading.Event(), threading.Event()
    pool = transport.BoundedWorkers(1)
    async def exercise():
        def work():
            started.set()
            release.wait(2)
            return 'done'
        task = asyncio.create_task(pool.run(work))
        while not started.is_set():
            await asyncio.sleep(0.001)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        with pytest.raises(ValueError):
            await pool.run(lambda: 'must not start')
        release.set()
        for _ in range(100):
            try:
                return await pool.run(lambda: 'available')
            except ValueError:
                await asyncio.sleep(0.01)
        raise AssertionError('worker permit was not released')
    try:
        assert asyncio.run(exercise()) == 'available'
    finally:
        release.set()
        pool.close()


def test_admission_requires_existing_owner_and_device_scope(transport):
    from fastapi import HTTPException
    class Query(dict):
        def getlist(self, key):
            return [self[key]] if key in self else []
    class Mobile:
        cfg = {'owner_id': 'owner', 'owner_provider': 'oidc', 'profiles': ['work']}
        def settings(self, profile=None):
            return self.cfg
        def claim_owner(self, *args):
            raise AssertionError('admission must never claim an owner')
    device_calls = []
    def device(request, owner, provider, profile, update_seen=True):
        device_calls.append((owner, provider, profile))
        if request.headers.get('x-beacon-device-id') == 'revoked':
            raise HTTPException(403, 'device refused')
    request = SimpleNamespace(query_params=Query(beacon_profile='work'), headers={'x-beacon-device-id': 'linked'},
                              state=SimpleNamespace(session=SimpleNamespace(provider='oidc', issuer='', user_id='owner', access_token='provider-token')))
    mobile = Mobile()
    identity = lambda request: ('owner', 'oidc')
    assert transport.authorize_request(request, mobile, identity, lambda request: None, lambda: ['work'], device) == ('owner', 'oidc', 'work')
    assert device_calls == [('owner', 'oidc', 'work')]
    request.headers['x-beacon-device-id'] = 'revoked'
    with pytest.raises(HTTPException):
        transport.authorize_request(request, mobile, identity, lambda request: None, lambda: ['work'], device)
    mobile.cfg = {'profiles': ['work']}
    with pytest.raises(HTTPException):
        transport.authorize_request(request, mobile, identity, lambda request: None, lambda: ['work'], device)
    mobile.cfg = {'owner_id': 'owner', 'owner_provider': 'oidc', 'profiles': ['work']}
    request.query_params = Query(beacon_profile='other')
    with pytest.raises(HTTPException):
        transport.authorize_request(request, mobile, identity, lambda request: None, lambda: ['work'], device)
    request.state.hosted_http_authenticated = True
    with pytest.raises(HTTPException) as refused:
        transport.authorize_request(request, mobile, identity, lambda request: None, lambda: ['work'], device)
    assert refused.value.status_code == 501


def test_admission_companion_uses_native_ticket_and_matches_every_binding(transport, tmp_path, monkeypatch):
    from fastapi import HTTPException
    from hermes_cli.dashboard_auth.ws_tickets import TicketInvalid, consume_ticket
    session = SimpleNamespace(user_id='owner', provider='oidc', issuer='https://issuer.example')
    authority = SimpleNamespace(profile_id=str(tmp_path), instance_id='instance', epoch=7, retiring=False)
    runner = SimpleNamespace(session_authority=authority, session_authorities=SimpleNamespace(for_home=lambda home:authority), _draining=False,
                             session_runtime_descriptor={'state': 'ready', 'instance_id': 'instance', 'authority_epoch': 7})
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=runner))
    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner, session_authority=authority)),
                              state=SimpleNamespace(session=session))
    nonce = 'a' * 64
    proof = transport.canonical_proof(request, 'work', tmp_path, nonce)
    expected = {'profile': 'work', 'home': transport.home_digest(tmp_path), 'instance': 'instance', 'epoch': 7, 'gateway_epoch':7,
                'nonce': nonce, 'subject': transport.subject_digest(session)}
    ticket = transport.validate_proof(proof, expected)
    native = consume_ticket(ticket)
    assert (native['provider'], native['issuer'], native['user_id']) == ('oidc', 'https://issuer.example', 'owner')
    with pytest.raises(TicketInvalid):
        consume_ticket(ticket)
    for field in expected:
        altered = dict(proof, **{field: 'wrong'})
        with pytest.raises(ValueError):
            transport.validate_proof(altered, expected)
    runner._draining = True
    with pytest.raises(HTTPException):
        transport.canonical_proof(request, 'work', tmp_path, nonce)


def test_relay_forwards_raw_requests_responses_events_and_joins_pumps(transport):
    class Client:
        def __init__(self):
            self.incoming = asyncio.Queue()
            self.outgoing = asyncio.Queue()
        async def receive(self):
            return await self.incoming.get()
        async def send_text(self, value):
            await self.outgoing.put(value)
    class Upstream:
        def __init__(self):
            self.incoming = asyncio.Queue()
            self.outgoing = asyncio.Queue()
            self.stopped = False
        async def send(self, value):
            await self.outgoing.put(value)
        async def recv(self):
            try:
                return await self.incoming.get()
            finally:
                self.stopped = True
    async def exercise():
        client, upstream = Client(), Upstream()
        task = asyncio.create_task(transport.relay_sockets(client, upstream, 'work'))
        request = '{"id":1,"method":"prompt.submit","params":{"profile":"work","text":"hello"}}'
        response = '{"id":"approval","result":{"choice":"yes"}}'
        event = '{"method":"event","params":{"type":"request.cancel","payload":{"request_id":"abc"}}}'
        await client.incoming.put({'type': 'websocket.receive', 'text': request})
        assert await asyncio.wait_for(upstream.outgoing.get(), 1) == request
        await client.incoming.put({'type': 'websocket.receive', 'text': response})
        assert await asyncio.wait_for(upstream.outgoing.get(), 1) == response
        await upstream.incoming.put(event)
        assert await asyncio.wait_for(client.outgoing.get(), 1) == event
        await client.incoming.put({'type': 'websocket.disconnect'})
        await asyncio.wait_for(task, 1)
        assert upstream.stopped
        assert upstream.outgoing.empty()
    asyncio.run(exercise())


@pytest.fixture
def admission_harness(harness, monkeypatch):
    import threading
    from hermes_cli.dashboard_auth.base import Session
    api, client, cfg, tmp, app = harness
    session = Session(user_id='owner', provider='basic', issuer='', email='', display_name='', org_id='',
                      expires_at=int(time.time()) + 120, access_token='synthetic-token', refresh_token='')
    del client.headers['x-fixture-owner']
    @app.middleware('http')
    async def authenticated(request, call_next):
        request.state.session = session
        return await call_next(request)
    transport = sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: False)
    endpoint = SimpleNamespace(profile_id=str(tmp), instance_id='instance', authority_epoch=7,
                               runtime_protocol=1, api_origin='http://127.0.0.1:1234')
    monkeypatch.setattr(transport, 'native_policy', lambda profile: tmp)
    monkeypatch.setattr(transport, 'discover', lambda home: endpoint)
    def exchange(endpoint, profile, access_token, nonce, stored=None, runtime=None, authority_only=False,timeout=5):
        proof={'profile': profile, 'home': transport.home_digest(tmp),
               'instance': 'instance', 'epoch': 7, 'gateway_epoch':7, 'nonce': nonce, 'subject': transport.subject_digest(session)}
        if not authority_only: proof['ticket']='upstream-only'
        return proof
    monkeypatch.setattr(transport, 'exchange', exchange)
    class Upstream:
        subprotocol = 'hermes-gateway-v1'
        ready = '{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}'
        def __init__(self):
            self.sent = []
            self.sent_event = threading.Event()
            self.finished = threading.Event()
            self.reads = 0
        def __await__(self):
            async def open_socket():
                return self
            return open_socket().__await__()
        async def close(self):
            self.finished.set()
        async def recv(self):
            self.reads += 1
            if self.reads == 1:
                return self.ready
            await asyncio.Event().wait()
        async def send(self, raw):
            self.sent.append(raw)
            self.sent_event.set()
    created = []
    def connect(url, **kwargs):
        assert url == 'ws://127.0.0.1:1234/api/ws'
        assert kwargs['proxy'] is None
        assert kwargs['subprotocols'] == ['hermes-gateway-v1', 'hermes-gateway-ticket.upstream-only']
        value = Upstream()
        created.append(value)
        return value
    monkeypatch.setattr(transport, 'no_redirect_connect', connect)
    device = api.mobile.link_device('owner', 'basic', 'Synthetic phone')['device_id']
    return api, client, cfg, transport, endpoint, device, created


def _admit(client, device):
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={},
                           headers={'x-beacon-device-id': device})
    assert response.status_code == 200, response.text
    return response.json()['ticket']


def _protocols(ticket):
    return ['beacon-canonical-v1', 'beacon-ticket.' + ticket]


def test_relay_socket_ready_forwarding_and_one_use(admission_harness):
    from starlette.websockets import WebSocketDisconnect
    _, client, _, _, _, device, created = admission_harness
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        assert socket.accepted_subprotocol == 'beacon-canonical-v1'
        assert socket.receive_json()['params']['payload']['session_authority'] is True
        raw = '{"id":1,"method":"session.info","params":{"profile":"default"}}'
        socket.send_text(raw)
        assert created[0].sent_event.wait(2)
        assert created[0].sent == [raw]
    assert created[0].finished.wait(2)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass


def test_relay_rejects_revoked_device_and_profile_changes(admission_harness):
    from starlette.websockets import WebSocketDisconnect
    api, client, cfg, _, _, device, created = admission_harness
    ticket = _admit(client, device)
    assert api.mobile.remove_device('owner', device)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass
    assert not created
    replacement = api.mobile.link_device('owner', 'basic', 'Replacement')['device_id']
    ticket = _admit(client, replacement)
    cfg['profiles'] = ['other']
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': replacement}):
            pass
    assert not created


def test_relay_closes_before_forwarding_cross_profile_request(admission_harness):
    from starlette.websockets import WebSocketDisconnect
    _, client, _, _, _, device, created = admission_harness
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        socket.receive_json()
        socket.send_json({'id': 1, 'method': 'prompt.submit', 'params': {'profile': 'other', 'text': 'bad'}})
        with pytest.raises(WebSocketDisconnect):
            socket.receive_text()
    assert created[0].sent == []


def test_admission_rejects_issuer_proof_mismatch_and_stale_owner(admission_harness, monkeypatch):
    _, client, cfg, transport, _, device, _ = admission_harness
    original = transport.exchange
    def wrong_issuer(*args):
        result = original(*args)
        result['subject'] = transport.subject_digest(SimpleNamespace(provider='basic', issuer='other', user_id='owner'))
        return result
    monkeypatch.setattr(transport, 'exchange', wrong_issuer)
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={},
                           headers={'x-beacon-device-id': device})
    assert response.status_code == 503
    assert 'ticket' not in response.json()
    cfg['owner_id'] = 'other'
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={})
    assert response.status_code == 403


def test_admission_http_exchange_forwards_only_native_access_token_and_refuses_redirect(transport, monkeypatch):
    import http.client
    requests = []
    class Response:
        status = 200
        def read(self, size):
            assert size == 16385
            return b'{"ticket":"opaque"}'
    class Connection:
        def __init__(self, host, port, timeout):
            assert (host, port, timeout) == ('127.0.0.1', 1234, 5)
        def set_debuglevel(self, level):
            assert level == 0
        def connect(self):
            pass
        def request(self, method, path, body, headers):
            requests.append((method, path, json.loads(body), headers))
        def getresponse(self):
            return response
        def close(self):
            pass
    response = Response()
    monkeypatch.setattr(http.client, 'HTTPConnection', Connection)
    endpoint = SimpleNamespace(api_origin='http://127.0.0.1:1234')
    assert transport.exchange(endpoint, 'work', 'synthetic-provider-token', 'a' * 64) == {'ticket': 'opaque'}
    method, path, payload, headers = requests[0]
    assert method == 'POST' and path == '/api/plugins/beacon/mobile/canonical-admission?beacon_profile=work'
    assert payload == {'nonce': 'a' * 64}
    assert headers == {'Authorization': 'Bearer synthetic-provider-token', 'Content-Type': 'application/json'}
    response.status = 302
    with pytest.raises(ValueError):
        transport.exchange(endpoint, 'work', 'synthetic-provider-token', 'a' * 64)
    assert len(requests) == 2


def test_relay_refuses_noncanonical_ready_before_accept(admission_harness, monkeypatch):
    import threading
    from starlette.websockets import WebSocketDisconnect
    _, client, _, transport, _, device, _ = admission_harness
    finished = threading.Event()
    class BadUpstream:
        subprotocol = 'hermes-gateway-v1'
        def __await__(self):
            async def open_socket():
                return self
            return open_socket().__await__()
        async def close(self):
            finished.set()
        async def recv(self):
            return '{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":false}}}'
    monkeypatch.setattr(transport, 'no_redirect_connect', lambda *args, **kwargs: BadUpstream())
    ticket = _admit(client, device)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass
    assert finished.wait(2)


def test_relay_refuses_changed_gateway_and_conflicting_profile_query(admission_harness, monkeypatch):
    from starlette.websockets import WebSocketDisconnect
    _, client, _, transport, endpoint, device, created = admission_harness
    ticket = _admit(client, device)
    changed = SimpleNamespace(**vars(endpoint))
    changed.instance_id = 'restarted'
    monkeypatch.setattr(transport, 'discover', lambda home: changed)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass
    assert not created
    monkeypatch.setattr(transport, 'discover', lambda home: endpoint)
    ticket = _admit(client, device)
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws?profile=other', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass
    assert not created


def test_limits_relay_cancellation_awaits_both_pumps(transport):
    class Client:
        def __init__(self):
            self.started = asyncio.Event()
            self.stopped = asyncio.Event()
        async def receive(self):
            self.started.set()
            try:
                await asyncio.Event().wait()
            finally:
                self.stopped.set()
        async def send_text(self, raw):
            raise AssertionError('nothing should be forwarded')
    class Upstream:
        def __init__(self):
            self.started = asyncio.Event()
            self.stopped = asyncio.Event()
        async def recv(self):
            self.started.set()
            try:
                await asyncio.Event().wait()
            finally:
                self.stopped.set()
        async def send(self, raw):
            raise AssertionError('nothing should be forwarded')
    async def exercise():
        client, upstream = Client(), Upstream()
        task = asyncio.create_task(transport.relay_sockets(client, upstream, 'work'))
        await asyncio.wait_for(client.started.wait(), 1)
        await asyncio.wait_for(upstream.started.wait(), 1)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 1)
        assert client.stopped.is_set() and upstream.stopped.is_set()
    asyncio.run(exercise())


def test_limits_frames_are_bounded_and_ready_requires_real_authority(transport):
    ready = '{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}'
    assert transport._ready(ready)
    assert not transport._ready(ready.replace('true', 'false'))
    assert not transport._ready('{"method":"event","params":{"type":"gateway.ready","payload":{}}}')
    assert not transport._ready('x' * (transport.MAX_FRAME + 1))
    with pytest.raises(ValueError):
        transport.validate_frame('x' * (transport.MAX_FRAME + 1), 'work', False)


def test_admission_upstream_websocket_refuses_redirects(transport):
    from websockets.asyncio.client import connect
    from websockets.datastructures import Headers
    from websockets.exceptions import InvalidStatus
    from websockets.http11 import Response
    response = Response(302, 'Found', Headers([('Location', 'ws://192.0.2.7:4321/other')]), b'')
    error = InvalidStatus(response)
    normal = connect('ws://127.0.0.1:1234/api/ws')
    assert normal.process_redirect(error) == 'ws://192.0.2.7:4321/other'
    guarded = transport.no_redirect_connect('ws://127.0.0.1:1234/api/ws', proxy=None)
    assert guarded.process_redirect(error) is error


def test_limits_expired_tickets_are_removed_without_another_request(transport):
    async def exercise():
        cache = transport.TicketCache(capacity=2, ttl=0.03)
        ticket = cache.issue(('binding', 'upstream'), loop=asyncio.get_running_loop())
        assert ticket in cache.items
        await asyncio.sleep(0.1)
        assert ticket not in cache.items
        assert cache.consume(ticket) is None
    asyncio.run(exercise())


def test_admission_refuses_hosted_isolation_and_updates(admission_harness, monkeypatch):
    from fastapi import HTTPException
    api, client, _, transport, _, device, _ = admission_harness
    def hosted(profile):
        raise HTTPException(501, 'Plugin socket unavailable for this profile')
    monkeypatch.setattr(transport, 'native_policy', hosted)
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={},
                           headers={'x-beacon-device-id': device})
    assert response.status_code == 501
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: True)
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={},
                           headers={'x-beacon-device-id': device})
    assert response.status_code == 503


def test_admission_rejects_caller_target_url(admission_harness):
    _, client, _, _, _, device, _ = admission_harness
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default&url=http://192.0.2.1',
                           json={}, headers={'x-beacon-device-id': device})
    assert response.status_code == 403
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default',
                           json={'url': 'http://192.0.2.1'}, headers={'x-beacon-device-id': device})
    assert response.status_code == 400


def test_admission_companion_refuses_wrong_authority_home_epoch_and_nonce(transport, tmp_path, monkeypatch):
    from fastapi import HTTPException
    from hermes_cli.dashboard_auth import ws_tickets
    session = SimpleNamespace(user_id='owner', provider='basic', issuer='')
    authority = SimpleNamespace(profile_id=str(tmp_path), instance_id='instance', epoch=7, retiring=False)
    runner = SimpleNamespace(session_authority=authority, session_authorities=SimpleNamespace(for_home=lambda home:authority), _draining=False,
                             session_runtime_descriptor={'state': 'ready', 'instance_id': 'instance', 'authority_epoch': 7})
    module = SimpleNamespace(_ACTIVE_RUNNER=runner)
    monkeypatch.setitem(sys.modules, 'gateway.run_api', module)
    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner, session_authority=authority)),
                              state=SimpleNamespace(session=session))
    before = len(ws_tickets._tickets)
    with pytest.raises(HTTPException):
        transport.canonical_proof(request, 'work', tmp_path / 'wrong-home', 'a' * 64)
    authority.epoch = 8
    with pytest.raises(HTTPException):
        transport.canonical_proof(request, 'work', tmp_path, 'a' * 64)
    authority.epoch = 7
    module._ACTIVE_RUNNER = object()
    with pytest.raises(HTTPException):
        transport.canonical_proof(request, 'work', tmp_path, 'a' * 64)
    module._ACTIVE_RUNNER = runner
    with pytest.raises(HTTPException):
        transport.canonical_proof(request, 'work', tmp_path, 'not-a-valid-nonce')
    assert len(ws_tickets._tickets) == before


def test_admission_consults_native_consent_and_isolation(transport, monkeypatch, tmp_path):
    from contextlib import nullcontext
    from fastapi import HTTPException
    from hermes_cli import web_server, web_server_dashboard, web_server_plugin_profiles, web_server_profiles
    monkeypatch.setattr(web_server_profiles, '_config_profile_scope', lambda profile: nullcontext())
    monkeypatch.setattr(web_server_profiles, '_resolve_profile_dir', lambda profile: tmp_path)
    monkeypatch.setattr(web_server, '_get_dashboard_plugins', lambda: [{'name': 'beacon', 'source': 'user'}])
    monkeypatch.setattr(web_server_plugin_profiles, 'in_process_router_refused', lambda name: False)
    monkeypatch.setattr(web_server_dashboard, '_plugin_enable_sets_here', lambda: ({'beacon'}, set()))
    assert transport.native_policy('work') == tmp_path
    monkeypatch.setattr(web_server_dashboard, '_plugin_enable_sets_here', lambda: ({'beacon'}, {'beacon'}))
    with pytest.raises(HTTPException) as refused:
        transport.native_policy('work')
    assert refused.value.status_code == 403
    monkeypatch.setattr(web_server_plugin_profiles, 'in_process_router_refused', lambda name: True)
    with pytest.raises(HTTPException) as refused:
        transport.native_policy('work')
    assert refused.value.status_code == 501


def test_admission_companion_route_mints_ticket_in_active_authority(admission_harness, monkeypatch):
    from hermes_cli.dashboard_auth.ws_tickets import consume_ticket
    _, client, _, transport, _, _, _ = admission_harness
    tmp = transport.native_policy('default')
    authority = SimpleNamespace(profile_id=str(tmp), instance_id='instance', epoch=7, retiring=False)
    runner = SimpleNamespace(session_authority=authority, session_authorities=SimpleNamespace(for_home=lambda home:authority), _draining=False,
                             session_runtime_descriptor={'state': 'ready', 'instance_id': 'instance', 'authority_epoch': 7})
    client.app.state.gateway_runner = runner
    client.app.state.session_authority = authority
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=runner))
    nonce = 'c' * 64
    path = '/api/plugins/beacon/mobile/canonical-admission?profile=default&beacon_profile=default'
    response = client.post(path, json={'nonce': nonce})
    assert response.status_code == 200, response.text
    proof = response.json()
    assert proof['profile'] == 'default' and proof['nonce'] == nonce
    native = consume_ticket(proof['ticket'])
    assert (native['user_id'], native['provider']) == ('owner', 'basic')
    runner._draining = True
    assert client.post(path, json={'nonce': nonce}).status_code == 503


def test_limits_cancelled_socket_cleanup_waits_before_releasing_ownership(transport):
    async def exercise():
        started, release, finished = asyncio.Event(), asyncio.Event(), asyncio.Event()
        async def close_socket():
            started.set()
            await release.wait()
            finished.set()
        task = asyncio.create_task(transport.settle(close_socket()))
        await asyncio.wait_for(started.wait(), 1)
        task.cancel()
        await asyncio.sleep(0)
        assert not task.done() and not finished.is_set()
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await asyncio.wait_for(task, 1)
        assert finished.is_set()
    asyncio.run(exercise())


def test_limits_socket_capacity_is_bounded_and_released(admission_harness):
    from contextlib import ExitStack
    from starlette.websockets import WebSocketDisconnect
    _, client, _, _, _, device, created = admission_harness
    with ExitStack() as stack:
        for _ in range(8):
            ticket = _admit(client, device)
            socket = stack.enter_context(client.websocket_connect('/api/plugins/beacon/gateway/ws',
                                         subprotocols=_protocols(ticket), headers={'x-beacon-device-id': device}))
            assert socket.receive_json()['params']['payload']['session_authority'] is True
        assert len(created) == 8
        ticket = _admit(client, device)
        with pytest.raises(WebSocketDisconnect):
            with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                          headers={'x-beacon-device-id': device}):
                pass
        assert len(created) == 8
    assert all(upstream.finished.wait(2) for upstream in created)
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        assert socket.receive_json()['params']['payload']['session_authority'] is True
    assert len(created) == 9


def test_profiles_host_scope_can_admit_consented_qa_but_actual_secondary_is_refused(transport, monkeypatch, tmp_path):
    from contextlib import contextmanager
    from fastapi import HTTPException
    from hermes_cli import web_server,web_server_dashboard,web_server_plugin_profiles,web_server_profiles
    host=tmp_path/'host'; qa=host/'profiles'/'qa';qa.mkdir(parents=True)
    current=['host']
    @contextmanager
    def scope(profile):
        previous=current[0];current[0]=profile
        try: yield
        finally: current[0]=previous
    monkeypatch.setattr(web_server_profiles,'_config_profile_scope',scope)
    monkeypatch.setattr(web_server_profiles,'_resolve_profile_dir',lambda profile:qa if profile=='qa' else host)
    # The host serves this request; a clean logical QA profile has no local plugin copy.
    monkeypatch.setattr(web_server,'_get_dashboard_plugins',lambda:[{'name':'beacon','source':'user'}] if current[0]=='host' else [])
    monkeypatch.setattr(web_server_plugin_profiles,'secondary_profile_home',lambda:qa if current[0]=='qa' else None)
    monkeypatch.setattr(web_server_plugin_profiles,'secondary_plugin',lambda name:None)
    monkeypatch.setattr(web_server_plugin_profiles,'in_process_router_refused',lambda name:current[0]=='qa')
    monkeypatch.setattr(web_server_dashboard,'_plugin_enable_sets_here',lambda:({'beacon'},set()))
    assert transport.native_policy('qa')==qa
    monkeypatch.setattr(web_server_dashboard,'_plugin_enable_sets_here',lambda:({'beacon'},{'beacon'} if current[0]=='qa' else set()))
    with pytest.raises(HTTPException) as disabled:
        transport.native_policy('qa')
    assert disabled.value.status_code==403
    monkeypatch.setattr(web_server_dashboard,'_plugin_enable_sets_here',lambda:({'beacon'} if current[0]=='host' else set(),set()))
    with pytest.raises(HTTPException) as missing_consent:
        transport.native_policy('qa')
    assert missing_consent.value.status_code==403
    current[0]='qa'
    with pytest.raises(HTTPException) as secondary:
        transport.native_policy('qa')
    assert secondary.value.status_code==501


def test_profiles_canonical_binding_uses_selected_served_authority(transport, monkeypatch, tmp_path):
    from fastapi import HTTPException
    from gateway.session_authorities import SessionAuthorities
    host=tmp_path/'host';qa=host/'profiles'/'qa';qa.mkdir(parents=True)
    launch=SimpleNamespace(profile_id=str(host),instance_id='instance',epoch=2,retiring=False)
    selected=SimpleNamespace(profile_id=str(qa),instance_id='instance',epoch=9,retiring=False)
    registry=SessionAuthorities(host,multiplexed=True)
    registry.add(host,launch);registry.add(qa,selected)
    runner=SimpleNamespace(session_authority=launch,session_authorities=registry,_draining=False,
                           session_runtime_descriptor={'state':'ready','instance_id':'instance','authority_epoch':2})
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    session=SimpleNamespace(provider='basic',issuer='issuer',user_id='owner')
    request=SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner,session_authority=launch)),
                            state=SimpleNamespace(session=session))
    proof=transport.canonical_binding(request,'qa',qa,'a'*64)
    assert proof['epoch']==9 and proof['gateway_epoch']==2 and proof['home']==transport.home_digest(qa)
    selected.retiring=True
    with pytest.raises(HTTPException):
        transport.canonical_binding(request,'qa',qa,'a'*64)


def test_profiles_runtime_describe_cannot_select_a_sibling():
    from gateway.session_controls import _PROFILE_IMPLICIT
    from tui_gateway.contracts.registry import CANONICAL_METHODS,canonical_param_problems
    contract=CANONICAL_METHODS['runtime.describe']
    assert 'runtime.describe' not in _PROFILE_IMPLICIT
    assert canonical_param_problems(contract,{})==[]
    assert canonical_param_problems(contract,{'profile':'qa'})==['profile']


def test_profiles_host_relay_routes_served_qa_and_rechecks_selected_epoch(admission_harness,monkeypatch,tmp_path):
    from starlette.websockets import WebSocketDisconnect
    _,client,cfg,transport,endpoint,device,created=admission_harness
    cfg['profiles']=['default','qa']
    qa=tmp_path/'qa';qa.mkdir()
    endpoint.profile_id=str(qa)
    endpoint.control_home=str(tmp_path)
    monkeypatch.setattr(transport,'native_policy',lambda profile:qa if profile=='qa' else tmp_path)
    session=SimpleNamespace(provider='basic',issuer='',user_id='owner')
    epoch=[9]
    calls=[]
    def exchange(endpoint,profile,token,nonce,stored=None,runtime=None,authority_only=False,timeout=5):
        calls.append(authority_only)
        value={'profile':profile,'home':transport.home_digest(qa),'instance':endpoint.instance_id,
               'epoch':epoch[0],'gateway_epoch':endpoint.authority_epoch,'nonce':nonce,
               'subject':transport.subject_digest(session)}
        if not authority_only:value['ticket']='upstream-only'
        return value
    monkeypatch.setattr(transport,'exchange',exchange)
    path='/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=qa'
    response=client.post(path,json={},headers={'x-beacon-device-id':device})
    assert response.status_code==200,response.text
    ticket=response.json()['ticket']
    with client.websocket_connect('/api/plugins/beacon/gateway/ws',subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id':device}) as socket:
        assert socket.receive_json()['params']['payload']['session_authority'] is True
        raw='{"id":1,"method":"session.list","params":{"profile":"qa"}}'
        socket.send_text(raw)
        assert created[0].sent_event.wait(2) and created[0].sent==[raw]
    assert calls==[False,True]
    response=client.post(path,json={},headers={'x-beacon-device-id':device})
    assert response.status_code==200
    epoch[0]=10
    with pytest.raises(WebSocketDisconnect):
        with client.websocket_connect('/api/plugins/beacon/gateway/ws',subprotocols=_protocols(response.json()['ticket']),
                                      headers={'x-beacon-device-id':device}):
            pass
    assert len(created)==1


def test_profiles_native_sibling_replacement_preserves_actor_and_native_operator_policy(monkeypatch,tmp_path):
    from gateway.config import Platform
    from gateway.session_controls import AuthorityConnection
    from gateway.session_authority import SessionAuthority
    from gateway.session_contract import Principal,SessionRef
    from gateway.session_identity import authenticated_subject
    from hermes_cli import profiles
    from hermes_state_runtime import RuntimeStoreError
    host=tmp_path/'host';qa=host/'profiles'/'qa';qa.mkdir(parents=True)
    runner=SimpleNamespace(session_authorities=[])
    def authority(home,epoch):
        return SimpleNamespace(profile_id=str(home),instance_id='instance',epoch=epoch,events={},runner=runner,
                               retired=False,sessions={})
    launch=authority(host,2);first=authority(qa,9)
    runner.session_authorities=[launch,first]
    monkeypatch.setattr(profiles,'profile_matches_home',lambda name,home: name==('qa' if str(home)==str(qa) else 'default'))
    identity={'provider':'basic','issuer':'issuer-one','user_id':'owner','profile_scope':'host'}
    connection=AuthorityConnection(launch,object(),identity,operator=True)
    sibling=connection._sibling_for('qa')
    assert sibling.authority is first and sibling.actor.subject==authenticated_subject(identity)
    second=authority(qa,10)
    first.retired=True
    runner.session_authorities=[launch,second]
    replacement=connection._sibling_for('qa')
    assert replacement is not sibling and replacement.authority is second
    assert replacement.actor.subject==sibling.actor.subject and replacement.actor.profile_id==str(qa)
    with pytest.raises(RuntimeStoreError) as stale:
        SessionAuthority.authorize(first,sibling.actor,SessionRef(str(qa),'session'),'session:read')
    assert stale.value.reason=='runtime_retired'
    source=SimpleNamespace(platform=Platform.LOCAL,user_id=authenticated_subject(identity))
    second.sessions['session']=SimpleNamespace(source=source,superseded=False)
    foreign=Principal('other-subject',str(qa),frozenset({'session:read'}),'foreign')
    with pytest.raises(RuntimeStoreError) as refused:
        SessionAuthority.authorize(second,foreign,SessionRef(str(qa),'session'),'session:read')
    assert refused.value.reason=='permission_denied'
    operator=Principal('other-subject',str(qa),frozenset({'session:read','session:operator'}),'foreign')
    assert SessionAuthority.authorize(second,operator,SessionRef(str(qa),'session'),'session:read') is None
    unscoped=AuthorityConnection(launch,object(),dict(identity,profile_scope='profile'),operator=False)
    with pytest.raises(RuntimeStoreError):
        unscoped._sibling_for('qa')


def test_profiles_native_discovery_requires_same_multiplexer_endpoint(transport,monkeypatch,tmp_path):
    from hermes_cli import config,gateway_runtime
    host=tmp_path/'host';qa=host/'profiles'/'qa';qa.mkdir(parents=True)
    payload={'runtime_protocol':1,'state':'ready','capabilities':['session-authority-v1'],
             'served_profiles':[{'home':str(host),'profile_id':str(host)},{'home':str(qa),'profile_id':str(qa)}],
             'authority_epoch':2,'instance_id':'instance','api_origin':'http://127.0.0.1:1234','supervisor':'none'}
    host_result=gateway_runtime._endpoint(payload,host)
    qa_result=gateway_runtime._endpoint(payload,qa,control_home=host)
    monkeypatch.setattr(config,'get_process_hermes_home',lambda:host)
    monkeypatch.setattr(gateway_runtime,'discover_gateway_endpoint',lambda home,timeout:qa_result if home==qa else host_result)
    assert transport.discover(qa)==qa_result.endpoint
    other=dict(payload,instance_id='different')
    mismatch=gateway_runtime._endpoint(other,qa,control_home=host)
    monkeypatch.setattr(gateway_runtime,'discover_gateway_endpoint',lambda home,timeout:mismatch if home==qa else host_result)
    with pytest.raises(ValueError):transport.discover(qa)
    standalone=gateway_runtime._endpoint(payload,qa)
    monkeypatch.setattr(gateway_runtime,'discover_gateway_endpoint',lambda home,timeout:standalone if home==qa else host_result)
    with pytest.raises(ValueError):transport.discover(qa)


def test_profiles_native_secondary_user_plugin_remains_hosted_and_refuses_socket(monkeypatch):
    from hermes_cli import web_server_chat,web_server_plugin_profiles as routing
    monkeypatch.setattr(routing,'secondary_profile_home',lambda:Path('secondary'))
    monkeypatch.setattr(routing,'secondary_plugin',lambda name:{'name':'beacon','source':'user'})
    assert routing.in_process_router_refused('beacon') is True
    monkeypatch.setattr(web_server_chat,'_ws_auth_ok',lambda socket:True)
    sent=[]
    scope={'type':'websocket','path':'/api/plugins/beacon/gateway/ws','headers':[],
           'query_string':b'profile=qa','client':('127.0.0.1',1),
           'extensions':{'websocket.http.response':{}}}
    async def receive():return {'type':'websocket.connect'}
    async def send(message):sent.append(message)
    assert asyncio.run(routing.refuse_secondary_plugin_socket(scope,receive,send,'beacon')) is True
    assert sent[0]['type']=='websocket.http.response.start' and sent[0]['status']==501
    monkeypatch.setattr(routing,'secondary_profile_home',lambda:None)
    assert routing.in_process_router_refused('beacon') is False


def test_profiles_host_to_qa_uses_native_policy_discovery_and_ordinary_ticket(admission_harness,transport,monkeypatch,tmp_path):
    from contextlib import contextmanager
    from gateway.session_authorities import SessionAuthorities
    from hermes_cli import config,gateway_runtime,web_server,web_server_dashboard,web_server_plugin_profiles,web_server_profiles
    from hermes_cli.dashboard_auth.ws_tickets import consume_ticket
    _,client,cfg,host_module,_,device,_=admission_harness
    cfg['profiles']=['default','qa']
    host=tmp_path/'host';qa=host/'profiles'/'qa';qa.mkdir(parents=True)
    current=['host']
    @contextmanager
    def scope(profile):
        previous=current[0];current[0]=profile
        try:yield
        finally:current[0]=previous
    monkeypatch.setattr(web_server_profiles,'_config_profile_scope',scope)
    monkeypatch.setattr(web_server_profiles,'_resolve_profile_dir',lambda profile:qa if profile=='qa' else host)
    monkeypatch.setattr(web_server,'_get_dashboard_plugins',lambda:[{'name':'beacon','source':'user'}])
    monkeypatch.setattr(web_server_plugin_profiles,'secondary_profile_home',lambda:qa if current[0]=='qa' else None)
    monkeypatch.setattr(web_server_plugin_profiles,'secondary_plugin',lambda name:{'name':'beacon','source':'user'})
    monkeypatch.setattr(web_server_plugin_profiles,'in_process_router_refused',lambda name:current[0]=='qa')
    monkeypatch.setattr(web_server_dashboard,'_plugin_enable_sets_here',lambda:({'beacon'},set()))
    monkeypatch.setattr(host_module,'native_policy',transport.native_policy)
    payload={'runtime_protocol':1,'state':'ready','capabilities':['session-authority-v1'],
             'served_profiles':[{'home':str(host),'profile_id':str(host)},{'home':str(qa),'profile_id':str(qa)}],
             'authority_epoch':2,'instance_id':'instance','api_origin':'http://127.0.0.1:1234','supervisor':'none'}
    host_result=gateway_runtime._endpoint(payload,host)
    qa_result=gateway_runtime._endpoint(payload,qa,control_home=host)
    monkeypatch.setattr(config,'get_process_hermes_home',lambda:host)
    monkeypatch.setattr(gateway_runtime,'discover_gateway_endpoint',lambda home,timeout:qa_result if home==qa else host_result)
    monkeypatch.setattr(host_module,'discover',transport.discover)
    launch=SimpleNamespace(profile_id=str(host),instance_id='instance',epoch=2,retiring=False)
    selected=SimpleNamespace(profile_id=str(qa),instance_id='instance',epoch=9,retiring=False)
    registry=SessionAuthorities(host,multiplexed=True);registry.add(host,launch);registry.add(qa,selected)
    runner=SimpleNamespace(session_authority=launch,session_authorities=registry,_draining=False,
                           session_runtime_descriptor={'state':'ready','instance_id':'instance','authority_epoch':2})
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    session=SimpleNamespace(provider='basic',issuer='',user_id='owner')
    canonical_request=SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner,session_authority=launch)),
                                      state=SimpleNamespace(session=session))
    def exchange(endpoint,profile,token,nonce,stored=None,runtime=None,authority_only=False,timeout=5):
        assert token=='synthetic-token' and profile=='qa'
        return (transport.canonical_binding(canonical_request,profile,qa,nonce) if authority_only else
                transport.canonical_proof(canonical_request,profile,qa,nonce))
    monkeypatch.setattr(host_module,'exchange',exchange)
    class Socket:
        subprotocol='hermes-gateway-v1'
        def __init__(self,protocols):self.protocols=protocols;self.reads=0
        def __await__(self):
            async def open_socket():
                ticket=self.protocols[1].removeprefix('hermes-gateway-ticket.')
                info=consume_ticket(ticket)
                assert (info['provider'],info['user_id'])==('basic','owner')
                return self
            return open_socket().__await__()
        async def recv(self):
            self.reads+=1
            if self.reads==1:return '{"method":"event","params":{"type":"gateway.ready","payload":{"session_authority":true}}}'
            await asyncio.Event().wait()
        async def send(self,raw):pass
        async def close(self):pass
    monkeypatch.setattr(host_module,'no_redirect_connect',lambda url,**kwargs:Socket(kwargs['subprotocols']))
    response=client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=qa',json={},
                         headers={'x-beacon-device-id':device})
    assert response.status_code==200,response.text
    with client.websocket_connect('/api/plugins/beacon/gateway/ws',subprotocols=_protocols(response.json()['ticket']),
                                  headers={'x-beacon-device-id':device}) as socket:
        assert socket.receive_json()['params']['payload']['session_authority'] is True


def test_limits_http_proof_deadline_closes_stalled_socket(transport,monkeypatch):
    import http.client
    import threading
    stopped=threading.Event()
    class Socket:
        def shutdown(self,*args):stopped.set()
    class Connection:
        def __init__(self,*args,**kwargs):self.sock=Socket()
        def set_debuglevel(self,value):pass
        def connect(self):pass
        def request(self,*args,**kwargs):pass
        def getresponse(self):
            assert stopped.wait(1)
            raise OSError('synthetic closed')
        def close(self):pass
    monkeypatch.setattr(http.client,'HTTPConnection',Connection)
    endpoint=SimpleNamespace(api_origin='http://127.0.0.1:1234')
    with pytest.raises(ValueError):
        transport.exchange(endpoint,'qa','synthetic-token','a'*64,timeout=0.05)
    assert stopped.is_set()
