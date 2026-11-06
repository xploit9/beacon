"""Beacon owner Vault API tests with isolated native encrypted homes and SQLite."""
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import sys
import threading
from types import SimpleNamespace
import uuid

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

ROOT = Path(__file__).resolve().parents[1]
NATIVE = Path(os.environ['HERMES_AGENT_SOURCE']) if 'HERMES_AGENT_SOURCE' in os.environ else Path.home() / 'AppData/Local/hermes/hermes-agent'
PREFIX = '/api/plugins/beacon/mobile/vault'


class Connection(sqlite3.Connection):
    def __exit__(self, *exc):
        try: return super().__exit__(*exc)
        finally: self.close()


class Mobile:
    def __init__(self, path):
        self.path = path
        self.LOCK = threading.RLock()
    def connect(self):
        db = sqlite3.connect(self.path, timeout=3, factory=Connection)
        db.row_factory = sqlite3.Row
        return db


def load_api():
    path = ROOT / 'vault_api.py'
    assert path.exists(), 'Vault API has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_vault_api_test', path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def uid(): return str(uuid.uuid4())


def login(request_id=None):
    return {'request_id': request_id or uid(), 'kind': 'login', 'label': 'Example', 'origin': 'https://example.test',
            'secret': {'identifier_type': 'email', 'identifier': 'qa' + chr(64) + 'example.test', 'password': 'synthetic-private-pass'}}


@pytest.fixture
def env(tmp_path, monkeypatch):
    assert (NATIVE / 'agent/vault_store.py').is_file()
    monkeypatch.syspath_prepend(str(NATIVE))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    def build():
        module = load_api()
        homes = {name: tmp_path / name for name in ('default', 'other')}
        for home in homes.values(): home.mkdir()
        mobile = Mobile(tmp_path / 'receipts.sqlite')
        mobile.authorized_owner = 'owner'
        mobile.authorized_provider = 'basic'
        mobile.authorized_issuer = ''
        async def identity(request):
            owner = request.headers.get('x-owner')
            if not owner: raise HTTPException(401, 'Sign in')
            if owner != mobile.authorized_owner: raise HTTPException(403, 'Owner not authorized')
            request.state.session = SimpleNamespace(user_id=owner, provider=mobile.authorized_provider, issuer=mobile.authorized_issuer)
            profile = request.query_params.get('beacon_profile', 'default')
            if profile not in homes: raise HTTPException(403, 'Profile not authorized')
            return owner, mobile.authorized_provider, profile, {}
        async def body(request, limit=65536):
            raw = await request.body()
            if len(raw) > limit: raise HTTPException(413, 'Request too large')
            try: value = json.loads(raw or b'{}')
            except Exception: raise HTTPException(400, 'Invalid request') from None
            if not isinstance(value, dict): raise HTTPException(400, 'Invalid request')
            return value
        def scoped(profile, fn, *, hydrate_secrets=True):
            assert hydrate_secrets is False
            return fn(homes[profile])
        app = FastAPI()
        app.include_router(module.build_router(identity, body, scoped, mobile), prefix='/api/plugins/beacon')
        return module, TestClient(app, headers={'x-owner': 'owner'}), homes, mobile, app
    return build


def test_capability_probe_never_creates_or_reads_vault(env):
    _, client, homes, _, _ = env()
    result = client.get(PREFIX + '/capabilities')
    assert result.status_code == 200
    assert result.json()['capabilities']['secure_prompts'] is False and result.json()['capabilities']['otp'] is False
    assert all(not (home / 'vault').exists() for home in homes.values())


def test_api_does_not_advertise_or_accept_otp_from_local_bridge_without_gateway_receipt(env, monkeypatch):
    module, client, homes, mobile, _ = env()
    monkeypatch.setattr(module.attestation, '_DASHBOARD', None)
    monkeypatch.setattr(module.runtime, 'capabilities', lambda: {'supported':True,'local':True,'secure_prompts':True,'otp':True,'signup':True})
    caps = client.get(PREFIX + '/capabilities').json()['capabilities']
    assert caps['supported'] is True and caps['secure_prompts'] is False and caps['otp'] is False and caps['signup'] is False
    payload = login(); payload['secret']['otp_secret'] = 'JBSWY3DPEHPK3PXP'
    assert client.post(PREFIX + '/items', json=payload).status_code == 400
    assert not (homes['default'] / 'vault').exists()


def test_api_allows_otp_when_scoped_gateway_receipt_is_current(env, monkeypatch):
    module, client, homes, mobile, _ = env()
    ready = {'secure_prompts':True,'otp':True,'signup':True}
    monkeypatch.setattr(module.attestation, '_DASHBOARD', SimpleNamespace(mobile=mobile, readiness=lambda:ready))
    monkeypatch.setattr(module.runtime, 'capabilities', lambda: {'supported':True,'local':True,**ready})
    assert client.get(PREFIX + '/capabilities').json()['capabilities']['otp'] is True
    payload = login(); payload['secret']['otp_secret'] = 'JBSWY3DPEHPK3PXP'
    result = client.post(PREFIX + '/items', json=payload)
    assert result.status_code == 200 and result.json()['item']['has_otp'] is True
    assert 'JBSWY3DPEHPK3PXP' not in result.text


def test_verified_issuer_is_bound_to_physical_home_and_change_fails_closed(env):
    _, client, homes, mobile, _ = env()
    assert client.get(PREFIX + '/capabilities').status_code == 200
    mobile.authorized_issuer = 'changed-issuer'
    assert client.get(PREFIX + '/capabilities').status_code == 403
    assert not (homes['default'] / 'vault').exists()


def test_generated_login_records_expiring_intent_without_granting_signup(env):
    _, client, homes, mobile, _ = env()
    payload = {'request_id':uid(), 'label':'Signup', 'origin':'https://example.test',
               'identifier_type':'email', 'identifier':'qa' + chr(64) + 'example.test'}
    result = client.post(PREFIX + '/generate-login', json=payload)
    assert result.status_code == 200
    with mobile.connect() as db:
        row = db.execute('SELECT handle,origin,consumed,expires FROM vault_signup_intents').fetchone()
    assert row[0] == result.json()['item']['id'] and row[1] == payload['origin'] and row[2] == 0 and row[3] > 0
    assert 'password' not in result.text and 'secret' not in result.text


@pytest.mark.parametrize('operation', ['replace', 'remove'])
def test_edit_or_remove_revokes_generated_signup_intent(env, operation):
    _, client, homes, mobile, _ = env()
    payload = {'request_id':uid(), 'label':'Signup', 'origin':'https://example.test',
               'identifier_type':'email', 'identifier':'qa' + chr(64) + 'example.test'}
    item = client.post(PREFIX + '/generate-login', json=payload).json()['item']
    path = PREFIX + '/items/' + item['id']
    if operation == 'replace':
        result = client.put(path, json={'request_id':uid(), 'label':'Changed', 'origin':'https://example.test'})
    else:
        result = client.request('DELETE', path, json={'request_id':uid()})
    assert result.status_code == 200
    with mobile.connect() as db:
        row = db.execute('SELECT consumed FROM vault_signup_intents WHERE handle=?', (item['id'],)).fetchone()
    assert row is None or row[0] == 1


def test_capabilities_receive_verified_scope_and_physical_alias(env, monkeypatch):
    module, client, homes, mobile, _ = env()
    monkeypatch.setattr(module.runtime, 'capabilities', lambda: {'bound':module.attestation._SCOPE.get()})
    homes['alias'] = homes['default'] / '..' / 'default'
    first = client.get(PREFIX + '/capabilities?beacon_profile=default').json()['capabilities']['bound']
    alias = client.get(PREFIX + '/capabilities?beacon_profile=alias').json()['capabilities']['bound']
    other = client.get(PREFIX + '/capabilities?beacon_profile=other').json()['capabilities']['bound']
    assert first == alias and first[:3] == ['owner','basic',''] and first[3] != other[3]


def test_owner_profile_and_unknown_request_body_are_rejected(env):
    _, client, homes, _, _ = env()
    assert client.get(PREFIX, headers={'x-owner': ''}).status_code == 401
    assert client.get(PREFIX, headers={'x-owner': 'foreign'}).status_code == 403
    assert client.get(PREFIX + '?beacon_profile=unknown').status_code == 403
    response = client.post(PREFIX + '/items', json={**login(), 'profile': 'other'})
    assert response.status_code == 400
    assert all(not (home / 'vault' / 'vault.json.enc').exists() for home in homes.values())


def test_add_list_remove_and_profile_isolation_hide_secret(env):
    _, client, homes, _, _ = env()
    sent = login()
    added = client.post(PREFIX + '/items', json=sent)
    assert added.status_code == 200, added.text
    meta = added.json()['item']; item_id = meta['id']
    assert added.json()['request_id'] == sent['request_id']
    assert 'synthetic-private-pass' not in added.text
    listed = client.get(PREFIX)
    assert listed.status_code == 200 and [x['id'] for x in listed.json()['items']] == [item_id]
    assert 'synthetic-private-pass' not in listed.text
    assert client.get(PREFIX + '?beacon_profile=other').json()['items'] == []
    assert client.request('DELETE', PREFIX + '/items/' + item_id, json={'request_id': uid()}).json()['removed'] is True
    assert client.get(PREFIX).json()['items'] == []


def test_add_duplicate_request_is_rejected_without_a_second_record(env):
    _, client, _, _, _ = env()
    payload = login()
    assert client.post(PREFIX + '/items', json=payload).status_code == 200
    replay = client.post(PREFIX + '/items', json=payload)
    assert replay.status_code == 409 and 'synthetic-private-pass' not in replay.text
    assert len(client.get(PREFIX).json()['items']) == 1


def test_uuid_case_cannot_replay_a_request(env):
    _, client, _, _, _ = env()
    payload = login()
    assert client.post(PREFIX + '/items', json=payload).status_code == 200
    payload['request_id'] = payload['request_id'].upper()
    assert client.post(PREFIX + '/items', json=payload).status_code == 409
    assert len(client.get(PREFIX).json()['items']) == 1


@pytest.mark.parametrize('bad', ['', 'not-a-uuid', 'x' * 400, None, True, 17])
def test_invalid_request_ids_are_generic_and_do_not_mutate(env, bad):
    _, client, _, _, _ = env()
    payload = login(); payload['request_id'] = bad
    response = client.post(PREFIX + '/items', json=payload)
    assert response.status_code == 400 and 'synthetic-private-pass' not in response.text
    assert client.get(PREFIX).json()['items'] == []


def test_concurrent_duplicate_is_never_admitted_twice(env, monkeypatch):
    module, client, _, _, app = env()
    original = module.runtime.add
    started, release = threading.Event(), threading.Event()
    def held(*args):
        started.set(); assert release.wait(3)
        return original(*args)
    monkeypatch.setattr(module.runtime, 'add', held)
    payload = login(); outcomes = []
    def submit():
        with TestClient(app, headers={'x-owner': 'owner'}) as c:
            outcomes.append(c.post(PREFIX + '/items', json=payload).status_code)
    thread = threading.Thread(target=submit); thread.start()
    try:
        assert started.wait(3)
        assert client.post(PREFIX + '/items', json=payload).status_code == 409
    finally:
        release.set(); thread.join(5)
    assert outcomes == [200]
    assert len(client.get(PREFIX).json()['items']) == 1


def test_failure_after_admission_is_never_replayed(env, monkeypatch):
    module, client, _, _, _ = env()
    original = module.runtime.add
    def uncertain(*args):
        original(*args)
        raise OSError('synthetic-private-pass')
    monkeypatch.setattr(module.runtime, 'add', uncertain)
    payload = login()
    first = client.post(PREFIX + '/items', json=payload)
    assert first.status_code in (500, 503) and 'synthetic-private-pass' not in first.text
    assert client.post(PREFIX + '/items', json=payload).status_code == 409
    assert len(client.get(PREFIX).json()['items']) == 1


def test_identity_is_scoped_and_never_claims_verified(env):
    _, client, _, _, _ = env()
    assert client.get(PREFIX + '/identity').json()['status'] == 'not_configured'
    request_id = uid()
    result = client.put(PREFIX + '/identity', json={'request_id': request_id, 'name': 'QA Assistant', 'email': 'qa' + chr(64) + 'example.test'})
    assert result.status_code == 200 and result.json()['status'] == 'unverified' and result.json()['request_id'] == request_id
    assert client.get(PREFIX + '/identity?beacon_profile=other').json()['status'] == 'not_configured'
    refused = client.put(PREFIX + '/identity', json={'request_id': uid(), 'name': 'x', 'email': 'qa' + chr(64) + 'example.test', 'status': 'ready'})
    assert refused.status_code == 400


def test_generate_returns_metadata_only(env):
    module, client, homes, _, _ = env()
    payload = {'request_id': uid(), 'label': 'Generated', 'origin': 'https://example.test',
               'identifier_type': 'email', 'identifier': 'qa' + chr(64) + 'example.test'}
    response = client.post(PREFIX + '/generate-login', json=payload)
    assert response.status_code == 200, response.text
    item = response.json()['item']
    from agent.vault_store import VaultStore
    password = VaultStore(homes['default'] / 'vault').resolve_secret(item['id'])['password']
    assert password not in response.text and len(password) >= 24
    assert client.post(PREFIX + '/generate-login', json=payload).status_code == 409


def test_request_admission_uses_fully_durable_commit(env, monkeypatch):
    _, client, _, mobile, _ = env()
    modes = []
    class Traced(Connection):
        def execute(self, sql, parameters=()):
            if sql.startswith('INSERT INTO vault_requests'):
                modes.append(super().execute('PRAGMA synchronous').fetchone()[0])
            return super().execute(sql, parameters)
    def connect():
        db = sqlite3.connect(mobile.path, timeout=3, factory=Traced)
        db.execute('PRAGMA synchronous=NORMAL')
        return db
    monkeypatch.setattr(mobile, 'connect', connect)
    assert client.post(PREFIX + '/items', json=login()).status_code == 200
    assert modes == [2]


def test_capabilities_never_reads_existing_vault_or_secrets(env, monkeypatch):
    module, client, _, _, _ = env()
    def forbidden(*args, **kwargs): raise AssertionError('Vault must not be opened')
    monkeypatch.setattr(module.runtime, '_native', forbidden)
    monkeypatch.setattr(module.runtime, '_strict_read', forbidden)
    result = client.get(PREFIX + '/capabilities')
    assert result.status_code == 200 and result.json()['capabilities']['secure_prompts'] is False


def test_delete_response_confirms_canonical_request_id(env):
    _, client, _, _, _ = env()
    item = client.post(PREFIX + '/items', json=login()).json()['item']
    operation_id = uid()
    result = client.request('DELETE', PREFIX + '/items/' + item['id'], json={'request_id': operation_id.upper()})
    assert result.status_code == 200
    assert result.json() == {'removed': True, 'request_id': operation_id}


def test_same_request_id_in_another_profile_does_not_replay_original_scope(env):
    _, client, _, _, _ = env()
    payload = login()
    first = client.post(PREFIX + '/items', json=payload)
    second = client.post(PREFIX + '/items?beacon_profile=other', json=payload)
    assert first.status_code == second.status_code == 200
    assert first.json()['item']['id'] != second.json()['item']['id']
    assert len(client.get(PREFIX).json()['items']) == 1
    assert len(client.get(PREFIX + '?beacon_profile=other').json()['items']) == 1


def test_partial_replace_preserves_remaining_secret_and_rejects_replay(env):
    _, client, homes, _, _ = env()
    added = client.post(PREFIX + '/items', json=login()).json()['item']
    request_id = uid()
    update = {'request_id': request_id, 'label': 'Renamed', 'origin': 'https://example.test', 'secret': {'password': 'updated-qa-pass'}}
    result = client.put(PREFIX + '/items/' + added['id'], json=update)
    assert result.status_code == 200 and result.json()['request_id'] == request_id
    assert 'updated-qa-pass' not in result.text
    from agent.vault_store import VaultStore
    saved = VaultStore(homes['default'] / 'vault').resolve_secret(added['id'])
    assert saved['password'] == 'updated-qa-pass'
    assert result.json()['item']['identifier'] == 'qa' + chr(64) + 'example.test'
    assert client.put(PREFIX + '/items/' + added['id'], json=update).status_code == 409


def test_admission_storage_failure_cannot_touch_native_vault(env, monkeypatch):
    _, client, homes, mobile, _ = env()
    def broken(): raise sqlite3.OperationalError('synthetic-private-pass')
    monkeypatch.setattr(mobile, 'connect', broken)
    result = client.post(PREFIX + '/items', json=login())
    assert result.status_code == 503 and 'synthetic-private-pass' not in result.text
    assert not (homes['default'] / 'vault').exists()


def test_duplicate_admission_across_distinct_process_locks(env):
    module, _, _, mobile, _ = env()
    others = [Mobile(mobile.path), Mobile(mobile.path)]
    barrier = threading.Barrier(2); results = []
    request_id = uid()
    def attempt(other):
        barrier.wait()
        try:
            module._admit(other, ('owner', 'basic', 'default'), request_id)
            results.append('admitted')
        except HTTPException as exc:
            results.append(exc.status_code)
    threads = [threading.Thread(target=attempt, args=(other,)) for other in others]
    for thread in threads: thread.start()
    for thread in threads: thread.join(5)
    assert len(results) == 2 and sorted(results, key=str) == [409, 'admitted']
    with pytest.raises(HTTPException) as exc: module._admit(Mobile(mobile.path), ('owner', 'basic', 'default'), request_id)
    assert exc.value.status_code == 409


from test_mobile import harness as dashboard_harness


def test_real_dashboard_identity_denies_foreign_owner_and_profile(dashboard_harness, monkeypatch):
    host, client, config, root, app = dashboard_harness
    monkeypatch.syspath_prepend(str(NATIVE))
    module = load_api()
    config['profiles'] = ['default', 'work']
    homes = {name: root / ('vault-profile-' + name) for name in config['profiles']}
    for home in homes.values(): home.mkdir()
    def scoped(profile, fn, *, hydrate_secrets=True):
        assert hydrate_secrets is False
        return fn(homes[profile])
    async def verified_fixture_identity(request):
        result = await host.identity_async(request)
        request.state.session.issuer = ''  # Native Session has this field; the shared fixture omits it.
        return result
    app.include_router(module.build_router(verified_fixture_identity, host.body, scoped, host.mobile), prefix='/api/plugins/beacon-fixture')
    url = '/api/plugins/beacon-fixture/mobile/vault'
    assert client.get(url + '?beacon_profile=default').status_code == 200
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(url + '?beacon_profile=default').status_code == 403
    assert client.post(url + '/items?beacon_profile=default', json=login()).status_code == 403
    client.headers['x-fixture-owner'] = 'owner'
    assert client.get(url + '?beacon_profile=foreign').status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url + '?beacon_profile=default').status_code == 401
    assert all(not (home / 'vault' / 'vault.json.enc').exists() for home in homes.values())


def test_owner_change_cannot_inherit_profile_vault_or_identity(env):
    _, client, homes, mobile, _ = env()
    item = client.post(PREFIX + '/items', json=login()).json()['item']
    assert client.get(PREFIX).json()['items'][0]['id'] == item['id']
    mobile.authorized_owner = 'new-owner'
    client.headers['x-owner'] = 'new-owner'
    for path in ('', '/identity', '/capabilities'):
        denied = client.get(PREFIX + path)
        assert denied.status_code == 403 and 'Vault needs owner migration' in denied.text
    denied = client.post(PREFIX + '/items', json=login())
    assert denied.status_code == 403
    assert str(homes['default']) not in denied.text
    with sqlite3.connect(mobile.path) as db:
        assert str(homes['default']).encode() not in '\n'.join(db.iterdump()).encode()


def test_same_physical_home_cannot_be_rebound_under_another_profile_alias(env):
    _, client, homes, mobile, _ = env()
    assert client.get(PREFIX).status_code == 200
    homes['other'] = homes['default']
    mobile.authorized_owner = 'new-owner'; client.headers['x-owner'] = 'new-owner'
    assert client.get(PREFIX + '?beacon_profile=other').status_code == 403


def test_same_physical_home_alias_cannot_replay_a_mutation_id(env):
    _, client, homes, _, _ = env()
    payload = login()
    assert client.post(PREFIX + '/items', json=payload).status_code == 200
    homes['other'] = homes['default']
    assert client.post(PREFIX + '/items?beacon_profile=other', json=payload).status_code == 409
    assert len(client.get(PREFIX).json()['items']) == 1


def test_existing_corrupt_vault_read_returns_unavailable_not_empty(env):
    _, client, homes, _, _ = env()
    directory = homes['default'] / 'vault'; directory.mkdir()
    path = directory / 'vault.json.enc'; path.write_bytes(b'')
    result = client.get(PREFIX)
    assert result.status_code == 503 and result.json() == {'detail': 'Vault unavailable'}
    assert path.read_bytes() == b'' and not (directory / 'vault.key').exists()


def test_foreign_profile_cannot_replace_or_remove_an_owned_item(env):
    _, client, _, _, _ = env()
    item = client.post(PREFIX + '/items', json=login()).json()['item']
    update = {'request_id': uid(), 'label': 'Wrong', 'origin': 'https://example.test'}
    assert client.put(PREFIX + '/items/' + item['id'] + '?beacon_profile=other', json=update).status_code == 404
    assert client.request('DELETE', PREFIX + '/items/' + item['id'] + '?beacon_profile=other', json={'request_id': uid()}).json()['removed'] is False
    assert client.get(PREFIX).json()['items'][0]['label'] == 'Example'


def test_identity_write_fails_closed_when_native_source_is_unknown(env, monkeypatch):
    module, client, _, _, _ = env()
    monkeypatch.setattr(module.runtime, 'PINNED_SOURCE_SHA256', '0' * 64)
    response = client.put(PREFIX + '/identity', json={'request_id': uid(), 'name': 'QA', 'email': 'qa' + chr(64) + 'example.test'})
    assert response.status_code == 503
    caps = client.get(PREFIX + '/capabilities').json()['capabilities']
    assert caps['supported'] is False and caps['secure_prompts'] is False


def test_secret_exception_is_not_returned_or_logged(env, monkeypatch, caplog):
    module, client, _, _, _ = env()
    secret = 'synthetic-private-pass'
    def fail(*args): raise RuntimeError(secret)
    monkeypatch.setattr(module.runtime, 'add', fail)
    response = client.post(PREFIX + '/items', json=login())
    assert response.status_code == 503 and response.json() == {'detail': 'Vault unavailable'}
    assert secret not in caplog.text


def test_same_request_id_cannot_be_reused_for_a_different_mutation(env):
    _, client, _, _, _ = env()
    original = login()
    item = client.post(PREFIX + '/items', json=original).json()['item']
    response = client.request('DELETE', PREFIX + '/items/' + item['id'], json={'request_id': original['request_id']})
    assert response.status_code == 409
    assert len(client.get(PREFIX).json()['items']) == 1


def test_provider_change_cannot_inherit_same_physical_vault(env):
    _, client, _, mobile, _ = env()
    assert client.post(PREFIX + '/items', json=login()).status_code == 200
    mobile.authorized_provider = 'oauth'
    denied = client.get(PREFIX)
    assert denied.status_code == 403 and denied.json() == {'detail': 'Vault needs owner migration'}
