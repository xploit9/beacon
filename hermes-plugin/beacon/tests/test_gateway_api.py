"""Synthetic authenticated invoke provenance; no owner runtime or external tools."""
import importlib.util
import asyncio
import contextvars
import json
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
import httpx
from fastapi import FastAPI,HTTPException
from fastapi.testclient import TestClient

from test_gateway_tools import Backend,Mobile,SCOPE,settled


@pytest.fixture
def api(tmp_path,monkeypatch):
    root=Path(__file__).resolve().parents[1]
    spec=importlib.util.spec_from_file_location('adaptive_gateway_api_test',root/'gateway_api.py')
    route=importlib.util.module_from_spec(spec);spec.loader.exec_module(route)
    core=sys.modules['beacon_gateway_tools']
    mobile=Mobile(tmp_path/'gateway-api.sqlite');backend=Backend()
    dispatcher=core.Gateway(mobile,lambda profile,fn:fn(tmp_path),backend)
    with dispatcher.db(): pass
    monkeypatch.setattr(core,'Gateway',lambda *a,**k:dispatcher)
    async def call(request,call_id,writable=False):
        if call_id!='call': raise HTTPException(404)
        if writable and mobile.closed: raise HTTPException(409,'Call closed')
        request.state.session=SimpleNamespace(user_id='owner',provider='password',issuer=request.headers.get('x-fixture-issuer','original'))
        return 'owner','password',{'profile':'profile','conversation':'stored'},{}
    async def body(request,limit): return await request.json()
    app=FastAPI();app.include_router(route.build_router(call,body,lambda p,fn:fn(tmp_path),mobile))
    yield TestClient(app),dispatcher,backend,mobile
    deadline=time.monotonic()+5
    while time.monotonic()<deadline:
        with mobile.connect() as conn: requests=[r[0] for r in conn.execute('SELECT request_id FROM tool_receipts')]
        if all(dispatcher.status(SCOPE,r)['status'] not in ('ready','running') for r in requests): break
        time.sleep(.01)
    else: pytest.fail('Synthetic gateway dispatch did not settle before fixture teardown')


def invoke_api(client,request='request',issuer='original',**extra):
    return client.post('/mobile/calls/call/tools/invoke',json={
        'request_id':request,'tool_id':'a'*64,'arguments':{'query':'synthetic'},**extra},
        headers={'x-fixture-issuer':issuer})


def test_adaptive_principal_same_issuer_replays_and_foreign_issuer_cannot(api):
    client,dispatcher,backend,mobile=api
    first=invoke_api(client)
    assert first.status_code==200
    assert invoke_api(client).status_code==200
    conflict=invoke_api(client,issuer='different-issuer')
    assert conflict.status_code==409,conflict.text
    assert len(backend.calls)<=1
    with mobile.connect() as conn:
        payload=json.loads(conn.execute('SELECT payload FROM tool_receipts').fetchone()[0])
    assert payload['_principal'].startswith('auth:v1:')
    assert '_principal' not in first.json() and 'auth:v1:' not in first.text


def test_adaptive_principal_missing_historical_identity_is_not_assigned(api):
    client,dispatcher,backend,mobile=api
    assert invoke_api(client).status_code==200
    assert settled(dispatcher,'request')['status']=='completed'
    with mobile.LOCK,mobile.connect() as conn:
        payload=json.loads(conn.execute('SELECT payload FROM tool_receipts').fetchone()[0]);payload.pop('_principal',None)
        conn.execute('UPDATE tool_receipts SET payload=?',(json.dumps(payload),))
    assert invoke_api(client).status_code==409
    with mobile.connect() as conn:
        assert '_principal' not in json.loads(conn.execute('SELECT payload FROM tool_receipts').fetchone()[0])


def test_adaptive_principal_public_schema_and_closed_decision_remain_unchanged(api):
    client,dispatcher,backend,mobile=api
    assert invoke_api(client,principal='invented').status_code==400
    assert invoke_api(client,request='normal').status_code==200
    assert client.get('/mobile/calls/call/tools/requests/normal',headers={'x-fixture-issuer':'different-issuer'}).status_code==200
    mobile.closed=True
    assert client.post('/mobile/calls/call/tools/requests/normal/decision',json={'proposal_id':'synthetic','decision':'approve'}).status_code==409


@pytest.mark.parametrize('kind', ['discovery', 'status'])
def test_api_followup_authenticated_gets_are_no_store_without_schema_changes(api, kind):
    client, dispatcher, backend, _ = api
    backend.read_only = False
    proposal = invoke_api(client).json()
    assert proposal['status'] == 'needs_approval'
    if kind == 'discovery':
        response = client.get('/mobile/calls/call/tools')
        expected = dispatcher.discover('profile')
    else:
        response = client.get('/mobile/calls/call/tools/requests/request')
        expected = dispatcher.status(SCOPE, 'request')
        assert expected['proposal']['arguments'] == {'query': 'synthetic'}
    assert response.status_code == 200 and response.json() == expected
    assert response.headers.get('cache-control') == 'no-store'


@pytest.mark.parametrize('end', ['timeout', 'cancel'])
def test_api_followup_receipt_controls_survive_expensive_timeouts_and_cancellation(api, monkeypatch, end):
    client, dispatcher, backend, _ = api
    release = threading.Event()
    backend_release = threading.Event()
    backend.wait = backend_release
    assert invoke_api(client).status_code == 200
    assert backend.started.wait(1)
    marker = contextvars.ContextVar('api-followup-context')
    original_wait = asyncio.wait_for

    async def short_wait(task, timeout):
        return await original_wait(task, .03 if timeout == 15 else timeout)
    monkeypatch.setattr(asyncio, 'wait_for', short_wait)

    async def exercise():
        loop = asyncio.get_running_loop()
        entered = asyncio.Event()
        lock = threading.Lock()
        workers, submitted = [], []
        original_submit = loop.run_in_executor
        def track(executor, fn, *args):
            future = original_submit(executor, fn, *args)
            submitted.append(future)
            return future
        monkeypatch.setattr(loop, 'run_in_executor', track)
        def held_discovery(*args):
            assert marker.get() == 'synthetic-profile-context'
            with lock:
                workers.append(threading.current_thread())
                if len(workers) == 4: loop.call_soon_threadsafe(entered.set)
            assert release.wait(5), 'The test must explicitly release every metadata worker'
            return {'tools': []}
        monkeypatch.setattr(dispatcher, 'discover', held_discovery)
        token = marker.set('synthetic-profile-context')
        requests = []
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://fixture') as http:
                requests = [asyncio.create_task(http.get('/mobile/calls/call/tools')) for _ in range(4)]
                await original_wait(entered.wait(), 2)
                if end == 'cancel':
                    for request in requests: request.cancel()
                    results = await asyncio.gather(*requests, return_exceptions=True)
                    assert all(isinstance(result, asyncio.CancelledError) for result in results)
                else:
                    results = await asyncio.gather(*requests)
                    assert all(result.status_code == 504 for result in results)
                assert len(workers) == 4 and not release.is_set()
                assert (await http.get('/mobile/calls/call/tools')).status_code == 429
                assert (await http.post('/mobile/calls/call/tools/invoke', json={
                    'request_id': 'another', 'tool_id': 'a' * 64, 'arguments': {'query': 'synthetic'}})).status_code == 429
                assert (await http.post('/mobile/calls/call/tools/requests/request/decision', json={
                    'proposal_id': 'synthetic', 'decision': 'approve'})).status_code == 429
                status = await http.get('/mobile/calls/call/tools/requests/request')
                assert status.status_code == 200, 'Receipt inspection must remain available while metadata workers hold all expensive slots'
                assert status.json()['status'] == 'running'
                cancelled = await http.post('/mobile/calls/call/tools/requests/request/cancel')
                assert cancelled.status_code == 200 and cancelled.json()['status'] == 'uncertain'
                # Controls must not release an expensive worker permit or replay the mutation.
                assert (await http.get('/mobile/calls/call/tools')).status_code == 429
                assert len(backend.calls) == 1
        finally:
            release.set()
            backend_release.set()
            await asyncio.gather(*requests, return_exceptions=True)
            await original_wait(asyncio.gather(*submitted, return_exceptions=True), 2)
            marker.reset(token)
        monkeypatch.setattr(dispatcher, 'discover', lambda *args: {'tools': []})
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://fixture') as http:
            assert (await http.get('/mobile/calls/call/tools')).status_code == 200
            assert (await http.get('/mobile/calls/call/tools/requests/request')).json()['status'] == 'uncertain'
        assert len(backend.calls) == 1

    asyncio.run(exercise())
    assert invoke_api(client).json()['status'] == 'uncertain' and len(backend.calls) == 1


def test_api_followup_receipt_control_capacity_is_bounded_and_does_not_take_expensive_slots(api, monkeypatch):
    client, dispatcher, _, _ = api
    release = threading.Event()

    async def exercise():
        loop = asyncio.get_running_loop()
        entered = asyncio.Event()
        workers = []
        lock = threading.Lock()
        def held_status(*args):
            with lock:
                workers.append(threading.current_thread())
                if len(workers) == 4: loop.call_soon_threadsafe(entered.set)
            assert release.wait(5), 'The test must explicitly release every receipt worker'
            return {'request_id': 'synthetic', 'status': 'completed'}
        with monkeypatch.context() as patch:
            patch.setattr(dispatcher, 'status', held_status)
            patch.setattr(dispatcher, 'discover', lambda *args: {'tools': []})
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://fixture') as http:
                requests = [asyncio.create_task(http.get('/mobile/calls/call/tools/requests/synthetic')) for _ in range(4)]
                try:
                    await asyncio.wait_for(entered.wait(), 2)
                    assert (await http.get('/mobile/calls/call/tools/requests/synthetic')).status_code == 429
                    assert (await http.post('/mobile/calls/call/tools/requests/synthetic/cancel')).status_code == 429
                    assert (await http.get('/mobile/calls/call/tools')).status_code == 200
                    assert len(workers) == 4
                finally:
                    release.set()
                    results = await asyncio.gather(*requests)
                assert all(result.status_code == 200 for result in results)
                assert (await http.get('/mobile/calls/call/tools/requests/synthetic')).status_code == 200
    asyncio.run(exercise())


@pytest.mark.parametrize('path', ['/mobile/calls/call/tools', '/mobile/calls/call/tools/requests/synthetic'])
def test_api_followup_executor_scheduling_failure_does_not_leak_permits(api, monkeypatch, path):
    client, dispatcher, _, _ = api
    calls = []
    monkeypatch.setattr(dispatcher, 'discover', lambda *args: {'tools': []})
    monkeypatch.setattr(dispatcher, 'status', lambda *args: {'request_id': 'synthetic', 'status': 'completed'})
    async def exercise():
        loop = asyncio.get_running_loop()
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=client.app), base_url='http://fixture') as http:
            def fail_submit(*args):
                calls.append(True)
                raise RuntimeError('Synthetic executor submission refused')
            with monkeypatch.context() as patch:
                patch.setattr(loop, 'run_in_executor', fail_submit)
                for _ in range(6):
                    with pytest.raises(RuntimeError, match='Synthetic executor submission refused'):
                        await http.get(path)
            assert len(calls) == 6
            assert (await http.get(path)).status_code == 200
    asyncio.run(exercise())
