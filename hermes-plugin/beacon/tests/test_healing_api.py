import importlib.util
import json
from pathlib import Path
import uuid

from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient
import pytest
from test_self_heal import Mobile, load_module, incident


@pytest.fixture
def env(tmp_path):
    path = Path(__file__).resolve().parents[1] / 'healing_api.py'
    spec = importlib.util.spec_from_file_location('beacon_healing_api_test', path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    core = load_module(); mobile = Mobile(tmp_path / 'incidents.sqlite')
    healer = core.Healer(mobile, '1.1.32', lambda *_: {'status': 'unverified'}, lambda *_: {'status': 'deferred'}, lambda *_: False)
    recoveries = []
    healer.recover = lambda scope, value: recoveries.append((scope, value))
    async def identity(request):
        if not request.headers.get('x-owner'): raise HTTPException(401, 'Sign in')
        if request.headers['x-owner'] != 'owner': raise HTTPException(403, 'Owner not authorized')
        profile = request.query_params.get('beacon_profile', 'default')
        if profile not in ('default', 'other'): raise HTTPException(403, 'Profile not authorized')
        return 'owner', 'basic', profile, {}
    async def body(request, limit):
        raw = await request.body()
        if len(raw) > limit: raise HTTPException(413, 'Request too large')
        try: return json.loads(raw)
        except Exception: raise HTTPException(400, 'Invalid request') from None
    app = FastAPI(); app.include_router(module.build_router(identity, body, healer), prefix='/api/plugins/beacon')
    return TestClient(app, headers={'x-owner': 'owner'}), healer, mobile, recoveries


PATH = '/api/plugins/beacon/mobile/incidents'


def test_authenticated_ingress_acknowledges_one_durable_scoped_occurrence(env):
    client, healer, mobile, recovered = env
    payload = {'incident_id': str(uuid.uuid4()), 'incident': incident()}
    for _ in range(2):
        response = client.post(PATH, json=payload)
        assert response.status_code == 200 and response.json() == {'incident_id': payload['incident_id'], 'recorded': True}
        assert response.headers['cache-control'] == 'no-store'
    rows = healer.list(('owner', 'basic', 'default'))
    assert len(rows) == 1 and rows[0]['occurrences'] == 1
    assert recovered and all(scope == ('owner', 'basic', 'default') for scope, _ in recovered)
    assert client.get(PATH + '?beacon_profile=other').json() == {'incidents': []}
    with mobile.connect() as db:
        assert db.execute('SELECT COUNT(*) FROM healing_uploads').fetchone()[0] == 1


def test_auth_scope_body_privacy_and_fixed_schema_are_enforced(env):
    client, healer, mobile, _ = env
    good = {'incident_id': str(uuid.uuid4()), 'incident': incident()}
    assert client.post(PATH, headers={'x-owner': ''}, json=good).status_code == 401
    assert client.post(PATH, headers={'x-owner': 'foreign'}, json=good).status_code == 403
    assert client.post(PATH + '?beacon_profile=foreign', json=good).status_code == 403
    for bad in ({**good, 'owner': 'foreign'}, {**good, 'incident_id': None},
                {**good, 'incident': incident(message='private-canary')}, {**good, 'incident': {'component': 'private-canary'}},
                {**good, 'incident_id': '../private-canary'}):
        response = client.post(PATH, json=bad)
        assert response.status_code == 400 and 'private-canary' not in response.text
    assert client.post(PATH, content='x' * 4097).status_code == 413
    assert healer.list(('owner', 'basic', 'default')) == []
    assert b'private-canary' not in mobile.path.read_bytes()


def test_recovery_failure_cannot_undo_a_durable_ingress_acknowledgement(env):
    client, healer, _, _ = env
    def failed(*_): raise RuntimeError('synthetic-private-exception')
    healer.recover = failed
    response = client.post(PATH, json={'incident_id': str(uuid.uuid4()), 'incident': incident()})
    assert response.status_code == 200 and response.json()['recorded'] is True
    assert 'synthetic-private-exception' not in response.text
    assert client.post(PATH + '/confirm', json={'state': 'fix_published'}).status_code == 404


def test_capability_never_claims_that_a_queued_repair_is_published(env):
    client, _, _, _ = env
    response = client.get('/api/plugins/beacon/mobile/healing/capabilities')
    assert response.status_code == 200 and response.json() == {'supported': True}
    assert client.get('/api/plugins/beacon/mobile/healing/capabilities', headers={'x-owner': ''}).status_code == 401


def test_ingress_distinguishes_bad_input_conflict_capacity_and_corrupt_storage(env, monkeypatch, caplog):
    client, healer, mobile, _ = env
    event_id = str(uuid.uuid4())
    payload = {'incident_id': event_id, 'incident': incident()}
    assert client.post(PATH, json=payload).status_code == 200
    assert client.post(PATH, json={'incident_id':event_id, 'incident':incident(error_code='network')}).status_code == 409
    assert client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident(message='private-canary')}).status_code == 400
    monkeypatch.setitem(type(healer).record.__globals__, 'MAX_UPLOAD_RECEIPTS', 1)
    full = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident(error_code='network')})
    assert full.status_code in (429, 503)
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET metadata='bad-json-private-canary'")
    corrupt = client.post(PATH, json=payload)
    assert corrupt.status_code == 503
    assert 'private-canary' not in corrupt.text and 'private-canary' not in caplog.text


def test_durable_ack_with_failed_dispatch_keeps_pending_for_later_recovery(env, monkeypatch, caplog):
    client, healer, mobile, _ = env
    from test_healing_runtime import load_runtime
    def failed(*_): raise RuntimeError('private-dispatch-canary')
    healer.recover = failed
    response = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident()})
    assert response.status_code == 200 and response.json()['recorded'] is True
    with mobile.LOCK, mobile.connect() as db:
        db.execute('UPDATE healing_incidents SET attempts=1,job_attempts=1,generation=5,next_attempt=0')
    runtime = load_runtime(monkeypatch, mobile)
    runtime.attach(type('Runner', (), {'submit':lambda *_: {'status':'deferred'}, 'drain':lambda *_: None})())
    runtime.tick()
    from test_self_heal import wait_for
    row = wait_for(lambda: (value if (value := runtime.healer.list(('owner','basic','default'))[0])['job_state'] == 'deferred' else None))
    assert row['attempts'] == 2 and row['job_attempts'] == 1 and row['generation'] == 5
    assert 'private-dispatch-canary' not in caplog.text


def test_unexpected_value_error_is_a_private_server_failure_not_client_validation(env, caplog):
    client, healer, _, _ = env
    def fail(*args, **kwargs): raise ValueError('private-server-canary')
    healer.record = fail
    response = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident()})
    assert response.status_code == 503
    assert 'private-server-canary' not in response.text and 'private-server-canary' not in caplog.text


def test_invalid_server_identity_scope_is_not_blame_on_the_incident_payload(env):
    client, healer, _, _ = env
    original = type(healer).record.__get__(healer)
    healer.record = lambda scope, value, **kwargs: original(('invalid\nowner', scope[1], scope[2]), value, **kwargs)
    response = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident()})
    assert response.status_code == 503 and 'invalid' not in response.text.lower()


def test_incident_capacity_is_retryable_without_losing_existing_receipts(env, monkeypatch):
    client, healer, _, _ = env
    event = str(uuid.uuid4()); payload = {'incident_id':event, 'incident':incident()}
    assert client.post(PATH, json=payload).status_code == 200
    monkeypatch.setitem(type(healer).record.__globals__, 'MAX_INCIDENTS', 1)
    rejected = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident(error_code='network')})
    assert rejected.status_code in (429, 503)
    assert client.post(PATH, json=payload).status_code == 200
    assert healer.list(('owner','basic','default'))[0]['occurrences'] == 1


def test_acknowledged_busy_recovery_is_retried_after_worker_capacity_returns(env, monkeypatch):
    client, healer, mobile, _ = env
    from test_healing_runtime import load_runtime
    healer.recover = type(healer).recover.__get__(healer)
    monkeypatch.setitem(type(healer).recover.__globals__, 'MAX_WORKERS', 0)
    response = client.post(PATH, json={'incident_id':str(uuid.uuid4()), 'incident':incident()})
    assert response.status_code == 200 and response.json()['recorded'] is True
    assert healer.list(('owner','basic','default'))[0]['attempts'] == 0
    runtime = load_runtime(monkeypatch, mobile)
    runtime.attach(type('Runner', (), {'submit':lambda *_: {'status':'deferred'}, 'drain':lambda *_: None})())
    runtime.tick()
    from test_self_heal import wait_for
    result = wait_for(lambda: (value if (value := runtime.healer.list(('owner','basic','default'))[0])['job_state'] == 'deferred' else None))
    assert result['attempts'] == 1
