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
    # attach_frame_limit since CHAT-121 follow-up 4: the phone reads only the keys it knows, so a 1.2.0 app is unaffected.
    assert set(value) == {'ticket', 'path', 'profile', 'canonical', 'expires_in', 'attach_frame_limit', 'attach_pieces', 'attach_file_limit', 'attach_image_limit'}
    assert value['attach_frame_limit'] == transport.attach_frame_limit() and value['attach_pieces'] == transport.ATTACH_PIECES == 4
    assert (value['attach_file_limit'], value['attach_image_limit']) == transport.attach_file_limits()
    assert value['path'] == '/api/plugins/beacon/gateway/ws'
    assert value['profile'] == 'default' and value['canonical'] is True
    assert isinstance(value['ticket'], str) and value['ticket'] and value['ticket'] != 'upstream-only'
    assert 0 < value['expires_in'] <= 25
    assert response.headers['cache-control'] == 'no-store'


def test_a_ticket_whose_exchange_found_no_worker_answers_busy_not_unavailable(harness, monkeypatch):
    # CHAT-121: a step that waited for a worker in vain answers busy, so a phone does not ask again on top of the wait; the exchange
    # step sits inside the ticket's own catch-all, which turned every failure into "unavailable".
    from fastapi import HTTPException
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
    def exchange(*args, **kwargs):
        raise AssertionError('The exchange must not run without a worker')
    monkeypatch.setattr(transport, 'exchange', exchange)
    real = getattr(transport, 'bounded_call', None)
    async def no_worker_for_the_exchange(workers, fn, *args, **kwargs):
        if fn is exchange: raise HTTPException(503, 'Canonical gateway busy')
        return await real(workers, fn, *args, **kwargs)
    monkeypatch.setattr(transport, 'bounded_call', no_worker_for_the_exchange, raising=False)
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={})
    assert response.status_code == 503 and response.json()['detail'] == 'Canonical gateway busy', response.text

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


def test_limits_cancelled_worker_keeps_its_permit_until_finished(transport, monkeypatch):
    import threading
    # A full pool now waits for a worker before refusing; a short wait keeps this case about the running worker's permit.
    monkeypatch.setattr(transport, 'WORKER_WAIT_SECONDS', 0.05, raising=False)
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


def _hold(threading):
    """Takes every worker of [pool] until the returned event is set; returns (release, started_count)."""
    release, started = threading.Event(), []
    def work():
        started.append(1)
        release.wait(10)
        return 'held'
    return release, started, work


def test_a_burst_over_capacity_waits_for_workers_and_all_succeed(transport, monkeypatch):
    # CHAT-121: the pool refused a call over capacity at once (503), so a phone's startup burst failed a new chat.
    import threading
    monkeypatch.setattr(transport, 'WORKER_WAIT_SECONDS', 2.0, raising=False)
    pool = transport.BoundedWorkers(4)
    running, peak, lock = [0], [0], threading.Lock()
    def work(n):
        with lock:
            running[0] += 1; peak[0] = max(peak[0], running[0])
        time.sleep(0.05)
        with lock:
            running[0] -= 1
        return n
    async def burst():
        return await asyncio.gather(*(pool.run(work, n) for n in range(16)))
    try:
        assert asyncio.run(burst()) == list(range(16))
        assert peak[0] <= 4, 'Never more than the pool runs at once'
    finally:
        pool.close()


def test_a_pool_held_past_the_wait_answers_busy_within_the_bound(transport, monkeypatch):
    import threading
    from fastapi import HTTPException
    monkeypatch.setattr(transport, 'WORKER_WAIT_SECONDS', 0.3, raising=False)
    pool = transport.BoundedWorkers(1)
    release, started, work = _hold(threading)
    async def exercise():
        holder = asyncio.create_task(pool.run(work))
        while not started:
            await asyncio.sleep(0.001)
        begun = time.monotonic()
        with pytest.raises(HTTPException) as refused:
            await transport.bounded_call(pool, lambda: 'must not run')
        waited = time.monotonic() - begun
        release.set()
        assert await holder == 'held'
        return refused.value, waited
    try:
        refused, waited = asyncio.run(exercise())
        assert refused.status_code == 503 and refused.detail == 'Canonical gateway busy'
        assert refused.detail != 'Canonical gateway unavailable', 'Busy is not Hermes being down'
        assert 0.25 <= waited < 1.5, waited
    finally:
        release.set()
        pool.close()


def test_a_cancelled_waiter_leaves_the_permits_intact(transport, monkeypatch):
    import threading
    monkeypatch.setattr(transport, 'WORKER_WAIT_SECONDS', 2.0, raising=False)
    pool = transport.BoundedWorkers(1)
    release, started, work = _hold(threading)
    async def exercise():
        holder = asyncio.create_task(pool.run(work))
        while not started:
            await asyncio.sleep(0.001)
        waiter = asyncio.create_task(pool.run(lambda: 'cancelled waiter must not run'))
        await asyncio.sleep(0.1)
        assert not waiter.done(), 'A call over capacity waits for a worker'
        waiter.cancel()
        with pytest.raises(asyncio.CancelledError):
            await waiter
        release.set()
        assert await holder == 'held'
        # Neither stolen nor leaked: one call runs at once, then the pool is exactly full again.
        assert await asyncio.wait_for(pool.run(lambda: 'next'), timeout=1) == 'next'
        for _ in range(100):
            if pool.permits._value == 1:
                break
            await asyncio.sleep(0.01)
        return pool.permits._value
    try:
        assert asyncio.run(exercise()) == 1
        with pytest.raises(ValueError):
            pool.permits.release()  # one more release than taken would mean a leaked permit
    finally:
        release.set()
        pool.close()


def test_the_event_loop_keeps_serving_while_a_call_waits_for_a_worker(transport, monkeypatch):
    import threading
    monkeypatch.setattr(transport, 'WORKER_WAIT_SECONDS', 2.0, raising=False)
    pool = transport.BoundedWorkers(1)
    release, started, work = _hold(threading)
    async def exercise():
        holder = asyncio.create_task(pool.run(work))
        while not started:
            await asyncio.sleep(0.001)
        waiter = asyncio.create_task(pool.run(lambda: 'after the holder'))
        served = 0
        begun = time.monotonic()
        while time.monotonic() - begun < 0.3:  # another request on the same loop, answered while the call waits
            await asyncio.sleep(0.01)
            served += 1
        assert not waiter.done()
        release.set()
        return served, await holder, await asyncio.wait_for(waiter, timeout=2)
    try:
        served, held, after = asyncio.run(exercise())
        assert served >= 10 and held == 'held' and after == 'after the holder'
    finally:
        release.set()
        pool.close()


def test_a_later_call_never_takes_a_worker_from_an_earlier_waiter(transport, monkeypatch):
    # CHAT-121 P3-5 (REVIEW-121B's proof, inverted): a worker freed while the earlier call waited between its looks went to whichever
    # call asked first, and a call arriving in that gap took it; the earlier one then answered busy, which the phone does not ask again.
    for _ in range(20):
        pool = transport.BoundedWorkers(1, wait=0.3)
        async def trial():
            assert pool.permits.acquire(blocking=False)  # a worker is busy
            order = []
            earlier = asyncio.create_task(transport.bounded_call(pool, lambda: order.append('earlier'), timeout=5))
            await asyncio.sleep(0.105)  # the earlier call is waiting now
            pool.permits.release()      # the worker finishes, and a new call arrives before the waiter looks again
            later = asyncio.ensure_future(transport.bounded_call(pool, lambda: (order.append('later'), time.sleep(0.3)), timeout=5))
            outcomes = await asyncio.gather(earlier, later, return_exceptions=True)
            return order, outcomes
        try:
            order, outcomes = asyncio.run(trial())
        finally:
            pool.close()
        assert order[0] == 'earlier', order
        assert getattr(outcomes[0], 'detail', None) is None, outcomes[0]
        assert not pool.waiting


def test_a_call_that_does_not_wait_never_takes_a_worker_from_a_waiter(transport, monkeypatch):
    # The relay's confirmations do not wait (wait=0): with a call waiting, they answer busy at once instead of taking its worker.
    pool = transport.BoundedWorkers(1, wait=1.0)
    async def exercise():
        assert pool.permits.acquire(blocking=False)
        order = []
        waiter = asyncio.create_task(transport.bounded_call(pool, lambda: order.append('waiter'), timeout=5))
        await asyncio.sleep(0.05)
        pool.permits.release()
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as refused:
            await transport.bounded_call(pool, lambda: order.append('no-wait'), wait=0)
        await waiter
        return refused.value.detail, order
    try:
        detail, order = asyncio.run(exercise())
        assert detail == 'Canonical gateway busy' and order == ['waiter']
    finally:
        pool.close()


def test_relay_confirmations_never_hold_back_hermes_frames_to_the_phone(transport, monkeypatch):
    # CHAT-121 P3-6 (REVIEW-121B's proof, inverted): confirm_remote_start ran on the pool before each frame was forwarded; with the
    # pool full and waiting, every later Hermes frame to that phone (tokens, approvals) waited behind it. Confirmations run on workers of
    # their own (REVIEW-121B FU-P2-1), so with every admission worker busy they still run, and the frames are not held.
    import json, threading, types
    ran = []
    native = types.SimpleNamespace(confirm_remote_start=lambda *args: ran.append(args))
    monkeypatch.setitem(sys.modules, 'beacon_native', native)
    frames = [json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'message.start', 'session_id': 's', 'admission_id': 'a'}}),
              json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'message.delta', 'session_id': 's', 'payload': {'text': 'hi'}}})]
    pool = transport.BoundedWorkers(1, wait=0.5)
    confirmations = transport.BoundedWorkers(transport.MAX_SOCKETS)
    release, started, work = _hold(threading)

    class Phone:
        def __init__(self):
            self.got, self.stop = [], asyncio.Event()
        async def receive(self):
            await self.stop.wait()
            return {'type': 'websocket.disconnect'}
        async def send_text(self, raw):
            self.got.append(time.monotonic())
            if len(self.got) == len(frames):
                self.stop.set()

    class Hermes:
        def __init__(self):
            self.queue = list(frames)
        async def recv(self):
            if self.queue:
                return self.queue.pop(0)
            await asyncio.Event().wait()
        async def send(self, raw):
            pass

    async def exercise():
        holder = asyncio.create_task(pool.run(work))
        while not started:
            await asyncio.sleep(0.001)
        phone = Phone()
        begun = time.monotonic()
        # As the socket route wires it: the relay's own requests use the admission workers (run), its confirmations their own (confirm).
        await asyncio.wait_for(transport.relay_sockets(phone, Hermes(), 'default', mobile=object(), binding={'owner': 'o'},
                                                       run=lambda fn, *args: transport.bounded_call(pool, fn, *args),
                                                       confirm=lambda fn, *args: transport.bounded_call(confirmations, fn, *args)), timeout=10)
        release.set()
        await holder
        return [t - begun for t in phone.got]
    try:
        reached = asyncio.run(exercise())
        assert max(reached) < 0.2, reached
        assert len(ran) == 1  # the confirmation ran, it was not skipped
    finally:
        release.set()
        pool.close()
        confirmations.close()


def test_the_socket_route_confirms_on_workers_of_its_own_while_admissions_queue(admission_harness, monkeypatch):
    # REVIEW-121B FU-P2-1 (its proof, inverted): the route gave the relay's confirmations the admissions' workers with no wait, so while
    # admissions queued (a startup burst) a confirmation found none and was skipped; a skipped confirm_created left the new chat's claim
    # pending, and Beacon's phone tools, hooks, email drafts and approvals were refused in that chat. With every admission worker busy and
    # an admission waiting, the route's confirmation now runs, at once, on workers of its own.
    _, client, _, transport, _, device, created = admission_harness
    pools, outcome = {}, {}
    real = transport.bounded_call
    async def spy(workers, fn, *args, **kwargs):
        pools.setdefault(getattr(fn, '__name__', ''), workers)
        return await real(workers, fn, *args, **kwargs)
    monkeypatch.setattr(transport, 'bounded_call', spy)
    async def relay(socket, upstream, profile, mobile=None, binding=None, run=None, confirm=None, **_):
        admissions, held = pools['exchange'], 0
        while admissions.permits.acquire(blocking=False):
            held += 1
        waiter = asyncio.ensure_future(admissions.run(lambda: 'admission', wait=1.0))
        await asyncio.sleep(0.05)
        try:
            def confirmed(): return True
            begun = time.monotonic()
            outcome['ran'] = await confirm(confirmed)
            outcome['took'] = time.monotonic() - begun
            outcome['own'] = pools['confirmed'] is not admissions
        except Exception as error:
            outcome['error'] = repr(error)
        finally:
            for _ in range(held):
                admissions.permits.release()
            await waiter
    monkeypatch.setattr(transport, 'relay_sockets', relay)
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        socket.receive_json()
        until = time.monotonic() + 3
        while not outcome and time.monotonic() < until:
            time.sleep(0.01)
    assert outcome.get('ran') is True and outcome['own'] and outcome['took'] < 0.1, outcome


def test_a_socket_that_finds_no_worker_within_its_one_wait_is_refused_with_429(admission_harness, monkeypatch):
    # CHAT-121 P3-4: each of the socket's five steps before accept could wait 2 s, up to 10 s against the phone's 15 s ready limit,
    # and a refusal after that wait was the same 403 the phone asks again on. The steps now share one wait, and a socket that found
    # no worker in it is refused with 429 (Starlette's denial response; the dashboard's uvicorn websockets protocol supports it).
    from fastapi import HTTPException
    from starlette.testclient import WebSocketDenialResponse
    _, client, _, transport, _, device, created = admission_harness
    monkeypatch.setattr(transport, 'SOCKET_WAIT_SECONDS', 0.5)
    waits, socket_phase = [], []
    real = transport.bounded_call
    async def full_pool(workers, fn, *args, timeout=None, wait=None, keep_place=False):
        if not socket_phase:  # the ticket's steps
            return await real(workers, fn, *args, timeout=timeout, wait=wait, keep_place=keep_place)
        waits.append(wait)
        if len(waits) < 5:  # each step before the exchange gets its worker after 0.1 s
            await asyncio.sleep(0.1)
            return await real(workers, fn, *args, timeout=timeout, wait=wait, keep_place=keep_place)
        await asyncio.sleep(wait)  # the exchange finds none in what is left of the wait
        raise HTTPException(503, 'Canonical gateway busy')
    monkeypatch.setattr(transport, 'bounded_call', full_pool)
    ticket = _admit(client, device)
    socket_phase.append(True)
    begun = time.monotonic()
    with pytest.raises(WebSocketDenialResponse) as refused:
        with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                      headers={'x-beacon-device-id': device}):
            pass
    took = time.monotonic() - begun
    assert refused.value.status_code == 429
    assert len(waits) == 5 and all(a > b for a, b in zip(waits, waits[1:])) and waits[0] <= 0.5, waits
    assert took < 0.5 + 0.4, took
    assert not created


def test_the_ticket_steps_share_one_wait_and_keep_their_place(admission_harness, monkeypatch):
    # REVIEW-121B FU-P3-3: the ticket gave each of its five steps its own 2 s wait for a worker, about 10 s in all on a pool that frees now
    # and then, and each step went back to the end of the line behind newer requests. Its steps now share one wait, as the socket's do, and
    # every step after the first keeps the request's place.
    _, client, _, transport, _, device, created = admission_harness
    monkeypatch.setattr(transport, 'SOCKET_WAIT_SECONDS', 0.5)
    steps = []
    real = transport.bounded_call
    async def spy(workers, fn, *args, timeout=None, wait=None, keep_place=False):
        steps.append((wait, keep_place))
        await asyncio.sleep(0.02)
        return await real(workers, fn, *args, timeout=timeout, wait=wait, **({'keep_place': True} if keep_place else {}))
    monkeypatch.setattr(transport, 'bounded_call', spy)
    _admit(client, device)
    waits = [wait for wait, _ in steps]
    assert len(steps) == 5, steps
    assert all(wait is not None for wait in waits) and waits[0] <= 0.5 and all(a > b for a, b in zip(waits, waits[1:])), waits
    assert [keep for _, keep in steps] == [False, True, True, True, True], steps


def test_a_started_request_keeps_its_place_ahead_of_newer_ones(transport):
    # REVIEW-121B FU-P3-3: a later step of a request that already ran one is served before requests that have not started, even ones that
    # were in line first; within each group the order stays as it came.
    pool = transport.BoundedWorkers(1, wait=1.0)
    async def exercise():
        assert pool.permits.acquire(blocking=False)
        order = []
        newer = [asyncio.ensure_future(pool.run(lambda n=n: order.append(f'new-{n}'))) for n in range(2)]
        await asyncio.sleep(0.03)
        started = [asyncio.ensure_future(pool.run(lambda n=n: order.append(f'started-{n}'), keep_place=True)) for n in range(2)]
        await asyncio.sleep(0.03)
        assert [place.started for place in pool.waiting] == [True, True, False, False]
        pool.permits.release()
        await asyncio.wait_for(asyncio.gather(*newer, *started), timeout=3)
        return order
    try:
        assert asyncio.run(exercise()) == ['started-0', 'started-1', 'new-0', 'new-1']
    finally:
        pool.close()


def _attach_frame(size, method='file.attach'):
    return json.dumps({'jsonrpc': '2.0', 'id': 'a1', 'method': method, 'params': {'profile': 'default', 'session_id': 's', 'name': 'x.pdf',
                       'data_url': 'data:application/pdf;base64,' + 'A' * size}})


def test_file_frames_pass_up_to_hermes_own_limit_and_no_other_frame_grows(transport, monkeypatch):
    # CHAT-121 follow-up 4: every phone frame was capped at 8 MiB, so a document over about 5.9 MB could not reach Hermes, whose own socket
    # takes file.attach frames up to _DESKTOP_ATTACHMENT_WS_MAX_BYTES (384 MiB). Only file frames grow; every other check stays.
    limit = transport.attach_frame_limit()
    from hermes_cli.web_server_boundary import _DESKTOP_ATTACHMENT_WS_MAX_BYTES
    assert limit == _DESKTOP_ATTACHMENT_WS_MAX_BYTES > transport.MAX_FRAME
    big = _attach_frame(transport.MAX_FRAME + 4096)
    assert transport.validate_frame(big, 'default', True, limit) is True
    assert transport.validate_frame(big, 'default', True, limit, binary=True) is True
    assert transport.validate_frame(_attach_frame(64, 'image.attach_bytes'), 'default', True, limit, binary=True) is True
    refused = [
        (big, False, transport.MAX_FRAME),                                                  # the 1.2.0 limit still applies when asked for
        (_attach_frame(transport.MAX_FRAME + 4096, 'session.info'), False, limit),           # only file frames grow
        (_attach_frame(64, 'session.info'), True, limit),                                    # only file frames may come as bytes
        (big.replace('"default"', '"other"'), False, limit),                                 # the profile check stays
        (json.dumps({'id': 'a1', 'result': {'x': 'A' * (transport.MAX_FRAME + 1)}}), False, limit),  # a response never grows
        (big, False, len(big) - 1),                                                          # past Hermes' own limit
    ]
    for frame, binary, cap in refused:
        with pytest.raises(ValueError):
            transport.validate_frame(frame, 'default', True, cap, binary=binary)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_boundary', None)  # a Hermes without it keeps 8 MiB
    assert transport.attach_frame_limit() == transport.MAX_FRAME
    # Hermes' own caps on one file, read from tui_gateway.prompt_attachments (the test harness stands in for that package, so the names are
    # checked against Hermes' source and read through a stand-in with the same values).
    import hermes_cli
    from types import SimpleNamespace
    source = (Path(hermes_cli.__file__).resolve().parents[1] / 'tui_gateway' / 'prompt_attachments.py').read_text(encoding='utf-8')
    assert '_ATTACHMENT_MAX_BYTES = 256 * 1024 * 1024' in source and '_ATTACH_BYTES_MAX_BYTES = 25 * 1024 * 1024' in source
    monkeypatch.setitem(sys.modules, 'tui_gateway.prompt_attachments',
                        SimpleNamespace(_ATTACHMENT_MAX_BYTES=256 * 1024 * 1024, _ATTACH_BYTES_MAX_BYTES=25 * 1024 * 1024))
    assert transport.attach_file_limits() == (256 * 1024 * 1024, 25 * 1024 * 1024)
    monkeypatch.setitem(sys.modules, 'tui_gateway.prompt_attachments', None)  # without them the phone goes by the frame limit
    assert transport.attach_file_limits() == (None, None)


def _phone_file(transport, body_len, method='file.attach', ident='a1', piece=1024 * 1024, profile='default', head=None, tail='"}}'):
    """A file frame as the phone sends it (CHAT-121 follow-up 6): PIECE_HEAD with the frame's text around the file's base64, then the base64 alone in
    pieces. Returns (the whole frame Hermes must get, the binary messages)."""
    field = transport.ATTACH_FIELDS.get(method, 'data_url')
    if head is None:
        head = json.dumps({'jsonrpc': '2.0', 'id': ident, 'method': method, 'params': {'profile': profile, 'session_id': 's', 'name': 'x.pdf'}},
                          separators=(',', ':'))[:-2] + ',"' + field + '":"' + ('data:application/pdf;base64,' if field == 'data_url' else '')
    body = (b'QUJDRA+/' * (body_len // 8 + 1))[:body_len]
    header = bytes([transport.PIECE_HEAD]) + json.dumps({'head': head, 'tail': tail, 'body': body_len}).encode('utf-8')
    pieces = [bytes([transport.PIECE_MORE]) + body[at:at + piece] for at in range(0, body_len, piece)]
    pieces[-1] = bytes([transport.PIECE_LAST]) + pieces[-1][1:]
    return head + body.decode('ascii') + tail, [header] + pieces


def test_the_ticket_tells_the_phone_its_file_frame_limit_and_a_file_socket_takes_a_large_file_frame_in_pieces(admission_harness):
    # CHAT-121 follow-ups 4, 6 and 9: the phone learns the plugin's limit and the form of its pieces from the ticket (a 1.2.0 plugin sends neither,
    # so the phone keeps 8 MiB), and streams a large file frame on a socket of the file's own in binary pieces (OkHttp sends at most 16 MiB in one
    # message): a header, then the file's base64. Hermes gets the whole frame as one text message, in fragments. JOINBFIX-121 (REVIEW-JOINB-121 P3-4):
    # the chat's socket takes no pieces any more (the superseded form 3): a binary message there ends it like any invalid frame, and a piece with no
    # header, or an unknown piece marker, ends a file socket.
    _, client, _, transport, _, device, created = admission_harness
    response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={}, headers={'x-beacon-device-id': device})
    assert response.status_code == 200 and response.json()['attach_frame_limit'] == transport.attach_frame_limit() > transport.MAX_FRAME
    assert response.json()['attach_pieces'] == 4 and 'purpose' not in response.json()
    big, pieces = _phone_file(transport, transport.MAX_FRAME + 4096, piece=(transport.MAX_FRAME + 4096) // 3 + 1)
    assert len(pieces) == 4
    caps = '{"jsonrpc":"2.0","id":"c1","method":"client.capabilities","params":{"server_requests":false}}'
    resume = '{"jsonrpc":"2.0","id":"r1","method":"session.resume","params":{"profile":"default","session_id":"s","omit_messages":true,"source":"android"}}'
    path = '/api/plugins/beacon/gateway/ws'
    headers = lambda: {'x-beacon-device-id': device}
    file_ticket = lambda: client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={'purpose': 'file'}, headers=headers()).json()['ticket']
    with client.websocket_connect(path, subprotocols=_protocols(file_ticket()), headers=headers()) as socket:
        socket.receive_json()
        socket.send_text(caps)
        socket.send_text(resume)
        for piece in pieces:
            socket.send_bytes(piece)
        until = time.monotonic() + 5
        while len(created[0].sent) < 3 and time.monotonic() < until:
            time.sleep(0.01)
        assert created[0].sent == [caps, resume, big] and isinstance(created[0].sent[2], str)
        assert created[0].fragments == [2 + (transport.MAX_FRAME + 4096 + transport.FRAGMENT_BYTES - 1) // transport.FRAGMENT_BYTES]
    # A binary message on the chat's socket (a whole file's pieces, a piece with no header, a drop, an unknown marker) ends it; nothing of it reaches Hermes.
    for refused in (pieces[0], pieces[1], bytes([2]), bytes([7]) + big.encode('utf-8')[:64]):
        ticket = _admit(client, device)
        with client.websocket_connect(path, subprotocols=_protocols(ticket), headers=headers()) as socket:
            socket.receive_json()
            socket.send_bytes(refused)
            assert created[-1].finished.wait(5)
        assert created[-1].sent == []
    # A piece with no header, or an unknown marker, ends a file socket as well.
    for refused in (pieces[1], bytes([7]) + big.encode('utf-8')[:64]):
        with client.websocket_connect(path, subprotocols=_protocols(file_ticket()), headers=headers()) as socket:
            socket.receive_json()
            socket.send_text(caps)
            socket.send_text(resume)
            socket.send_bytes(refused)
            assert created[-1].finished.wait(5)
        assert created[-1].sent == [caps, resume]


def test_a_file_socket_has_a_ticket_of_its_own_and_a_phones_newer_one_ends_its_older_one(admission_harness):
    # CHAT-121 follow-up 9 (form 4): the phone asks a file's socket with {"purpose": "file"}, and the answer says so; any other body is refused. A phone's
    # newer file socket (its next try after one failed on its side) ends its older one at once, with a normal close, so a socket the phone gave up on
    # never holds its file's budget until its quiet time. The chat's socket is not a file socket and is left alone.
    from starlette.websockets import WebSocketDisconnect
    _, client, _, transport, _, device, created = admission_harness

    def ticket(body):
        return client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json=body, headers={'x-beacon-device-id': device})
    assert ticket({'purpose': 'chat'}).status_code == 400 and ticket({'purpose': 'file', 'x': 1}).status_code == 400
    answer = ticket({'purpose': 'file'})
    assert answer.status_code == 200 and answer.json()['purpose'] == 'file' and answer.json()['attach_pieces'] == 4
    path = '/api/plugins/beacon/gateway/ws'
    headers = lambda: {'x-beacon-device-id': device}  # the test client writes each socket's protocols into its headers
    with client.websocket_connect(path, subprotocols=_protocols(_admit(client, device)), headers=headers()) as chat:
        chat.receive_json()
        with client.websocket_connect(path, subprotocols=_protocols(ticket({'purpose': 'file'}).json()['ticket']), headers=headers()) as older:
            older.receive_json()
            with client.websocket_connect(path, subprotocols=_protocols(ticket({'purpose': 'file'}).json()['ticket']), headers=headers()) as newer:
                newer.receive_json()
                with pytest.raises(WebSocketDisconnect) as closed:
                    older.receive_json()
                assert closed.value.code == 1000
        raw = '{"id":1,"method":"session.info","params":{"profile":"default"}}'
        chat.send_text(raw)
        assert created[0].sent_event.wait(2) and created[0].sent == [raw]


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
            self.fragments = []  # how many fragments each fragmented message came in
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
        async def send(self, raw, text=None):
            if hasattr(raw, '__aiter__'):  # a fragmented message, as websockets sends an async iterable (a file frame: follow-up 6)
                parts = [bytes(part) async for part in raw]
                self.fragments.append(len(parts))
                raw = b''.join(parts).decode('utf-8') if text else b''.join(parts)
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
    from gateway import session_contract
    from gateway.session_contract import SessionRef
    Principal=getattr(session_contract,'SessionActor',None) or session_contract.Principal
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
    native=sys.modules['beacon_native']
    assert sibling.authority is first and sibling.actor.subject==native.execution_subject('owner','basic',authenticated_subject(identity))
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


# ---- the relay vouches for a card payment prompt ---------------------------------------------------------------------------------------
# Hermes' canonical prompts (gateway/session_pending_controls.py) carry no `surface`. The plugin recorded which prompt ids are Hermes' own
# card consent (any_site_card.surface_for); the relay adds that one field to those prompts, in live events, replayed events and the
# snapshot's prompts, and strips it from every other prompt so the phone can trust it.

PROMPT_ID = '0123456789abcdef0123456789abcdef'
OTHER_ID = 'fedcba9876543210fedcba9876543210'
CARD_LINE = "Fill payment card 'Test card' on http://127.0.0.1:8793"


def prompt(prompt_id=PROMPT_ID, kind='approval', **extra):
    value = {'kind': kind, 'prompt_id': prompt_id, 'execution_generation': 7, 'command': CARD_LINE, 'choices': ['once', 'deny'], 'timeout_s': 300,
             'description': 'The agent wants to enter your saved card details into this checkout page.'}
    value.update(extra)
    return value


def published(payload, event_type='approval.request', seq=5):
    """session_events.publish's frame, as the socket carries it."""
    return json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': event_type, 'session_id': 'local-1234', 'payload': payload,
                                                                        'replay_epoch': 'epoch-1', 'seq': seq}})


@pytest.fixture
def marked(monkeypatch):
    ids = {}
    monkeypatch.setitem(sys.modules, 'beacon_any_site_card',
                        SimpleNamespace(surface_for=lambda prompt_id: ids.get(prompt_id), has_surfaces=lambda: bool(ids)))
    ids[PROMPT_ID] = 'vault-payment'
    return ids


def test_relay_marks_the_card_payment_prompt_in_a_live_event(transport, marked):
    raw = published(prompt())
    out = json.loads(transport.annotate(raw))
    assert out['params']['payload'] == {**prompt(), 'surface': 'vault-payment'}
    assert {key: value for key, value in out['params'].items() if key != 'payload'} == {key: value for key, value in json.loads(raw)['params'].items() if key != 'payload'}
    assert out['method'] == 'event' and out['jsonrpc'] == '2.0'


def test_relay_marks_the_prompt_in_the_snapshot_and_in_replayed_events(transport, marked):
    snapshot = json.dumps({'jsonrpc': '2.0', 'id': 'r1', 'result': {'session_id': 'local-1234', 'running': True, 'execution_generation': 7,
                                                                      'prompts': [prompt(kind='clarify', prompt_id=PROMPT_ID, question='Which?'), prompt(), prompt(OTHER_ID)]}})
    out = json.loads(transport.annotate(snapshot))['result']['prompts']
    assert out[1] == {**prompt(), 'surface': 'vault-payment'}
    assert 'surface' not in out[0], 'only an approval is vouched for'
    assert out[2] == prompt(OTHER_ID)
    replay = json.dumps({'jsonrpc': '2.0', 'id': 'r2', 'result': {'epoch': 'epoch-1', 'latest_seq': 6, 'events': [
        {'type': 'message.delta', 'session_id': 'local-1234', 'seq': 4, 'payload': {'text': 'hi'}},
        {'type': 'approval.request', 'session_id': 'local-1234', 'seq': 5, 'payload': prompt()},
        {'type': 'approval.settled', 'session_id': 'local-1234', 'seq': 6, 'payload': {'prompt_id': PROMPT_ID, 'execution_generation': 7}}]}})
    events = json.loads(transport.annotate(replay))['result']['events']
    assert events[1]['payload']['surface'] == 'vault-payment'
    assert events[0]['payload'] == {'text': 'hi'} and events[2]['payload'] == {'prompt_id': PROMPT_ID, 'execution_generation': 7}


def test_relay_never_vouches_for_a_prompt_the_plugin_did_not_mark_and_strips_a_claimed_surface(transport, marked):
    smuggled = published(prompt(OTHER_ID, surface='vault-payment'))
    assert 'surface' not in json.loads(transport.annotate(smuggled))['params']['payload']
    snapshot = json.dumps({'id': 'r1', 'result': {'prompts': [prompt(OTHER_ID, surface='vault-payment'), prompt(PROMPT_ID)]}})
    prompts = json.loads(transport.annotate(snapshot))['result']['prompts']
    assert 'surface' not in prompts[0] and prompts[1]['surface'] == 'vault-payment'


def test_relay_leaves_every_frame_it_has_nothing_to_say_about_exactly_as_it_came(transport, marked):
    frames = [published(prompt(OTHER_ID)), published({'text': 'hello'}, 'message.delta'),
              '{"id":"approval","result":{"choice":"yes"}}', '{"method":"event","params":{"type":"request.cancel","payload":{"request_id":"abc"}}}',
              '{"id": 3, "result": {"prompts": []}}', 'not json but prompt_id and approval', '{"a": 1, "a": 2, "prompt_id": "x", "kind": "approval"}']
    for raw in frames:
        assert transport.annotate(raw) is raw, raw


def test_relay_does_no_work_when_no_card_prompt_is_waiting(transport, monkeypatch):
    raw = published(prompt())
    monkeypatch.setitem(sys.modules, 'beacon_any_site_card', SimpleNamespace(surface_for=lambda prompt_id: None, has_surfaces=lambda: False))
    assert transport.annotate(raw) is raw
    monkeypatch.delitem(sys.modules, 'beacon_any_site_card')
    assert transport.annotate(raw) is raw
    monkeypatch.setitem(sys.modules, 'beacon_any_site_card', SimpleNamespace())
    assert transport.annotate(raw) is raw


def test_relay_still_forwards_when_the_marker_lookup_fails(transport, monkeypatch):
    def broken(prompt_id):
        raise RuntimeError('lookup failed')
    monkeypatch.setitem(sys.modules, 'beacon_any_site_card', SimpleNamespace(surface_for=broken, has_surfaces=lambda: True))
    raw = published(prompt())
    assert transport.annotate(raw) is raw


def test_relay_delivers_the_marked_prompt_to_the_phone_and_changes_nothing_else(transport, marked):
    class Client:
        def __init__(self):
            self.incoming, self.outgoing = asyncio.Queue(), asyncio.Queue()
        async def receive(self):
            return await self.incoming.get()
        async def send_text(self, value):
            await self.outgoing.put(value)
    class Upstream:
        def __init__(self):
            self.incoming, self.outgoing = asyncio.Queue(), asyncio.Queue()
        async def send(self, value):
            await self.outgoing.put(value)
        async def recv(self):
            return await self.incoming.get()
    async def exercise():
        client, upstream = Client(), Upstream()
        task = asyncio.create_task(transport.relay_sockets(client, upstream, 'work'))
        delta = published({'text': 'hi'}, 'message.delta', 4)
        await upstream.incoming.put(delta)
        assert await asyncio.wait_for(client.outgoing.get(), 1) == delta
        await upstream.incoming.put(published(prompt()))
        arrived = json.loads(await asyncio.wait_for(client.outgoing.get(), 1))
        assert arrived['params']['payload']['surface'] == 'vault-payment' and arrived['params']['payload']['prompt_id'] == PROMPT_ID
        request = '{"id":9,"method":"approval.respond","params":{"profile":"work","prompt_id":"%s","choice":"once"}}' % PROMPT_ID
        await client.incoming.put({'type': 'websocket.receive', 'text': request})
        assert await asyncio.wait_for(upstream.outgoing.get(), 1) == request, 'what the phone sends is never rewritten'
        await client.incoming.put({'type': 'websocket.disconnect'})
        await asyncio.wait_for(task, 1)
    asyncio.run(exercise())


def _relay_pair(frames, hermes_sent, stop_after):
    """A phone that sends [frames] in order, then waits; a Hermes that records what reached it. The phone leaves once Hermes holds
    [stop_after] frames, or once a frame of its own was answered by the relay (both are recorded)."""
    class Phone:
        def __init__(self):
            self.inbox, self.got, self.leave = list(frames), [], asyncio.Event()
        async def receive(self):
            if self.inbox:
                return {'type': 'websocket.receive', 'text': self.inbox.pop(0)}
            await self.leave.wait()
            return {'type': 'websocket.disconnect'}
        async def send_text(self, raw):
            self.got.append(json.loads(raw))
        async def send_bytes(self, raw):
            pass

    phone = Phone()

    class Hermes:
        async def recv(self):
            await asyncio.Event().wait()
        async def send(self, raw, text=None):
            hermes_sent.append(json.loads(raw))
            if len(hermes_sent) >= stop_after:
                phone.leave.set()
    return phone, Hermes()


def test_a_relayed_create_that_finds_the_pool_busy_is_answered_and_the_socket_keeps_working(transport, monkeypatch):
    # Backlog 67 (CHAT-121 follow-up 11): a session.create the relay prepares on the admission workers (prepare_created) found no worker
    # in time; the busy error ended the relay, so the socket route closed the whole socket (1013) and every other request on it with it.
    # Now that one frame is answered with the busy error under its own id (Hermes never saw it, so the phone may ask again under the
    # create's retry identity), and the socket's next frame still reaches Hermes.
    import threading, types
    prepared = []
    native = types.SimpleNamespace(prepare_created=lambda *args: prepared.append(args) or {'kind': 'create'})
    monkeypatch.setitem(sys.modules, 'beacon_native', native)
    pool = transport.BoundedWorkers(1, wait=0.2)
    release, started, work = _hold(threading)
    create = json.dumps({'jsonrpc': '2.0', 'id': 'create-1', 'method': 'session.create', 'params': {'profile': 'default', 'request_id': 'r' * 64}})
    later = json.dumps({'jsonrpc': '2.0', 'id': 'list-1', 'method': 'session.list', 'params': {'profile': 'default'}})
    reached = []
    phone, hermes = _relay_pair([create, later], reached, stop_after=1)

    async def exercise():
        holder = asyncio.create_task(pool.run(work))
        while not started:
            await asyncio.sleep(0.001)
        try:
            await asyncio.wait_for(transport.relay_sockets(phone, hermes, 'default', mobile=object(), binding={'owner': 'o'},
                                                           run=lambda fn, *args: transport.bounded_call(pool, fn, *args)), timeout=10)
        finally:
            release.set()
            await holder
    try:
        asyncio.run(exercise())
    finally:
        release.set()
        pool.close()
    assert not prepared, 'The create was not prepared: no worker took it'
    assert phone.got == [{'jsonrpc': '2.0', 'id': 'create-1', 'error': {'code': 503, 'message': 'Canonical gateway busy', 'data': {'source': 'beacon'}}}], phone.got
    assert [frame['id'] for frame in reached] == ['list-1'], 'The busy create never reaches Hermes; the next frame on the same socket does'


def test_a_relayed_create_whose_preparation_fails_is_answered_unavailable_and_never_forwarded(transport, monkeypatch):
    # The same for a preparation that failed (a locked store, say): its own answer, "unavailable", under its id; the create is never forwarded
    # without its proof, and the socket is not ended for it.
    import types
    def broken(*args):
        raise RuntimeError('synthetic store failure')
    monkeypatch.setitem(sys.modules, 'beacon_native', types.SimpleNamespace(prepare_created=broken))
    pool = transport.BoundedWorkers(1, wait=0.2)
    create = json.dumps({'jsonrpc': '2.0', 'id': 'create-2', 'method': 'session.create', 'params': {'profile': 'default', 'request_id': 'q' * 64}})
    reached = []
    phone, hermes = _relay_pair([create], reached, stop_after=1)

    async def exercise():
        task = asyncio.create_task(transport.relay_sockets(phone, hermes, 'default', mobile=object(), binding={'owner': 'o'},
                                                           run=lambda fn, *args: transport.bounded_call(pool, fn, *args)))
        for _ in range(500):
            if phone.got:
                break
            await asyncio.sleep(0.01)
        assert not task.done(), 'The relay goes on'
        phone.leave.set()
        await asyncio.wait_for(task, timeout=5)
    try:
        asyncio.run(exercise())
    finally:
        pool.close()
    assert phone.got == [{'jsonrpc': '2.0', 'id': 'create-2', 'error': {'code': 503, 'message': 'Canonical gateway unavailable', 'data': {'source': 'beacon'}}}], phone.got
    assert not reached


def test_an_unavailable_step_is_logged_with_its_name_and_error_class_only(transport, caplog):
    # Backlog 45: every non-HTTP failure of a bounded step became the same unlogged 503 "unavailable", so a live 503 could not be named.
    # The log says which step and the exception's class; never its message (which can carry a token, a path or a URL).
    import logging
    pool = transport.BoundedWorkers(1)
    def discover_step(home):
        raise ConnectionResetError('synthetic-secret-token-7f3a')
    async def exercise():
        from fastapi import HTTPException
        with pytest.raises(HTTPException) as refused:
            await transport.bounded_call(pool, discover_step, 'home')
        return refused.value
    try:
        with caplog.at_level(logging.WARNING):
            refused = asyncio.run(exercise())
    finally:
        pool.close()
    assert refused.status_code == 503 and refused.detail == 'Canonical gateway unavailable'
    text = caplog.text
    assert 'discover_step' in text and 'ConnectionResetError' in text, text
    assert 'synthetic-secret-token-7f3a' not in text


def test_the_exchange_logs_the_step_it_failed_at_and_never_the_token(transport, caplog, monkeypatch):
    # Backlog 45: the exchange (the inner hop to Hermes' own admission route) turned every network or HTTP failure into one ValueError.
    # Its log names the step (connect, request, response with its status) and the error class, never the bearer token or the nonce.
    import http.client, logging
    endpoint = SimpleNamespace(api_origin='http://127.0.0.1:9')

    class Refused(http.client.HTTPConnection):
        def connect(self):
            raise ConnectionRefusedError('synthetic refusal')

    class Answers403(http.client.HTTPConnection):
        def connect(self):
            self.sock = None
        def request(self, *args, **kwargs):
            pass
        def getresponse(self):
            return SimpleNamespace(status=403, read=lambda size: b'{}')

    token, nonce = 'synthetic-bearer-' + 'x' * 24, 'n' * 64
    for connection, step, error in ((Refused, 'connect', 'ConnectionRefusedError'), (Answers403, 'response 403', 'ValueError')):
        caplog.clear()
        monkeypatch.setattr(http.client, 'HTTPConnection', connection)
        with caplog.at_level(logging.WARNING):
            with pytest.raises(ValueError):
                transport.exchange(endpoint, 'default', token, nonce, timeout=1)
        text = caplog.text
        assert 'exchange' in text and step in text and error in text, text
        assert token not in text and nonce not in text


def test_a_ticket_that_fails_after_its_steps_logs_where_and_the_error_class(harness, monkeypatch, caplog):
    # Backlog 45, the third site: the ticket handler's own catch-all (the proof's checks, the binding, the ticket's expiry) answered the same
    # unlogged 503 "unavailable". It now logs the stage it reached and the error class, never the session's token.
    import logging
    api, client, _, tmp, app = harness
    from hermes_cli.dashboard_auth.base import Session
    session = Session(user_id='owner', provider='basic', issuer='', email='', display_name='', org_id='',
                      expires_at=int(time.time()) + 120, access_token='synthetic-token-45', refresh_token='')
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
    # Hermes' proof carries no epoch: the handler refuses it after the exchange step returned.
    monkeypatch.setattr(transport, 'exchange', lambda *args, **kwargs: {'nonce': 'synthetic'})
    with caplog.at_level(logging.WARNING):
        response = client.post('/api/plugins/beacon/mobile/gateway-ticket?beacon_profile=default', json={})
    assert response.status_code == 503 and response.json()['detail'] == 'Canonical gateway unavailable', response.text
    text = caplog.text
    assert 'gateway-ticket' in text and 'proof' in text and 'ValueError' in text, text
    assert 'synthetic-token-45' not in text


def test_through_the_socket_route_a_busy_create_is_answered_and_the_socket_carries_the_next_frame(admission_harness, monkeypatch):
    # Backlog 67 at the socket route itself: before, the busy create ended the relay and the route closed the socket with 1013, with every other
    # request on it (another chat's on the same phone socket). Now the phone reads the busy answer under the create's id, and its next frame
    # on the same socket still reaches Hermes.
    from fastapi import HTTPException
    _, client, _, transport, _, device, created = admission_harness
    real = transport.bounded_call
    async def no_worker_for_the_create(workers, fn, *args, **kwargs):
        if getattr(fn, '__name__', '') == 'prepare_created':
            raise HTTPException(503, 'Canonical gateway busy')
        return await real(workers, fn, *args, **kwargs)
    monkeypatch.setattr(transport, 'bounded_call', no_worker_for_the_create)
    ticket = _admit(client, device)
    with client.websocket_connect('/api/plugins/beacon/gateway/ws', subprotocols=_protocols(ticket),
                                  headers={'x-beacon-device-id': device}) as socket:
        socket.receive_json()  # gateway.ready
        socket.send_text(json.dumps({'jsonrpc': '2.0', 'id': 'capabilities-1', 'method': 'client.capabilities', 'params': {'server_requests': True}}))
        socket.send_text(json.dumps({'jsonrpc': '2.0', 'id': 'create-1', 'method': 'session.create', 'params': {'profile': 'default', 'request_id': 'c' * 64}}))
        answer = socket.receive_json()
        assert answer == {'jsonrpc': '2.0', 'id': 'create-1', 'error': {'code': 503, 'message': 'Canonical gateway busy', 'data': {'source': 'beacon'}}}, answer
        socket.send_text(json.dumps({'jsonrpc': '2.0', 'id': 'list-1', 'method': 'session.list', 'params': {'profile': 'default'}}))
        upstream = created[-1]
        until = time.monotonic() + 3
        while time.monotonic() < until and not any('"list-1"' in str(raw) for raw in upstream.sent):
            time.sleep(0.01)
    sent = [str(raw) for raw in upstream.sent]
    assert any('"list-1"' in raw for raw in sent), sent
    assert not any('"create-1"' in raw for raw in sent), 'Hermes never saw the busy create'
