import json
import sys
from contextlib import nullcontext
from types import ModuleType, SimpleNamespace

import pytest

from test_mobile import harness


def bind_conversation_rows(monkeypatch, rows):
    db = SimpleNamespace(get_session=rows.get)
    server = SimpleNamespace(_profile_db=lambda scope: nullcontext(db))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))


def stub_google_token(monkeypatch):
    google = ModuleType('google')
    google.genai = SimpleNamespace(Client=lambda **kwargs: SimpleNamespace(
        auth_tokens=SimpleNamespace(create=lambda **kwargs: SimpleNamespace(name='server-token')),
        close=lambda: None))
    monkeypatch.setitem(sys.modules, 'google', google)


@pytest.mark.parametrize('provider', ['gemini', 'grok', 'openai'])
def test_phone_key_calls_record_provider_and_keep_context_without_server_secrets(harness, monkeypatch, provider):
    api, client, cfg, _, _ = harness
    created = []
    monkeypatch.setattr(api, 'secret', lambda *_: pytest.fail('Phone-key admission read a Hermes server key'))
    monkeypatch.setattr(api.voice_models, 'xai_secret', lambda *_: pytest.fail('Phone-key admission read an xAI server key'))
    monkeypatch.setattr(api.voice_models, 'mint_xai', lambda *_: pytest.fail('Phone-key admission minted a server token'))
    monkeypatch.setattr(api.native, 'create', lambda *args: created.append(args) or 'native-stored')
    monkeypatch.setattr(api.voice_context, 'context', lambda *args: {'context_summary': 'owned native context'})

    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': provider, 'client_key': True,
    })

    assert response.status_code == 200, response.text
    call = response.json()
    assert call['provider'] == provider
    assert created == [('owner', 'basic', 'default')]
    assert 'token' not in call
    assert 'fixture-private-key' not in response.text
    with api.mobile.connect() as conn:
        row = api.mobile.owned(conn, call['call_id'], 'owner')
    assert api.voice_models.bound(row, cfg)['provider'] == provider
    assert provider in row['voice_config'] and 'fixture-secret' not in row['voice_config']
    assert json.loads(row['voice_config'])['client_key'] is True

    context = client.get(f"/api/plugins/cypher/mobile/calls/{call['call_id']}/context")
    assert context.status_code == 200 and context.json()['context_summary'] == 'owned native context'


def test_unowned_existing_chat_call_error_is_actionable_but_other_lookup_errors_stay_generic(harness, monkeypatch):
    api, client, _, _, _ = harness
    rows = {'phone-unowned': {'id': 'phone-unowned', 'user_id': None, 'source': 'android'}}
    bind_conversation_rows(monkeypatch, rows)
    monkeypatch.setattr(api.native, 'reserve_conversation', lambda *args: (_ for _ in ()).throw(LookupError('not owned')))

    response = client.post('/api/plugins/cypher/mobile/calls', json={'profile': 'default', 'conversation_id': 'phone-unowned'})

    assert response.status_code == 404
    assert response.json()['detail'] == 'This chat is not linked to your Hermes sign-in, so Cypher cannot start a call from it. Start a new chat in Cypher.'
    rows.clear()
    missing = client.post('/api/plugins/cypher/mobile/calls', json={'profile': 'default', 'conversation_id': 'missing'})
    assert missing.status_code == 404 and missing.json()['detail'] == 'Owned resource unavailable'


def test_unowned_existing_callback_answer_keeps_404_and_gives_actionable_error(harness, monkeypatch):
    api, client, _, _, _ = harness
    rows = {'phone-unowned': {'id': 'phone-unowned', 'user_id': None, 'source': 'android'}}
    bind_conversation_rows(monkeypatch, rows)
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))
    invitation = api.mobile.invitation('owner', 'default', 'phone-unowned')

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={})

    assert response.status_code == 404
    assert response.json()['detail'] == 'This chat is not linked to your Hermes sign-in, so Cypher cannot start a call from it. Start a new chat in Cypher.'
    assert verified == []
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT state FROM notices WHERE id=?', (invitation,)).fetchone()['state'] == 'pending'


@pytest.mark.parametrize('field', ['api_key', 'key', 'token'])
def test_client_key_request_rejects_any_provider_key_and_never_stores_it(harness, monkeypatch, field):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api, 'secret', lambda *_: None)
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'openai', 'client_key': True,
        field: 'fixture-secret-value',
    })
    assert response.status_code == 400
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE voice_config LIKE '%fixture-secret-value%'").fetchone()[0] == 0


def test_client_key_request_rejects_nested_provider_keys(harness, monkeypatch):
    api, client, _, _, _ = harness
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'openai', 'client_key': True,
        'provider_options': {'openai_api_key': 'fixture-nested-secret'},
    })
    assert response.status_code == 400
    assert 'fixture-nested-secret' not in response.text
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE voice_config LIKE '%fixture-nested-secret%'").fetchone()[0] == 0


@pytest.mark.parametrize('provider', ['gemini', 'grok', 'openai'])
def test_server_token_endpoint_rejects_phone_key_calls(harness, monkeypatch, provider):
    api, client, _, _, _ = harness
    stub_google_token(monkeypatch)
    monkeypatch.setattr(api.voice_models, 'xai_secret', lambda *_: 'xai-server-fixture')
    monkeypatch.setattr(api.voice_models, 'mint_xai', lambda *_: {'token': 'server-token'})
    call = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': provider, 'client_key': True,
    }).json()

    result = client.post(f"/api/plugins/cypher/mobile/calls/{call['call_id']}/token", json={})

    assert result.status_code == 409
    assert 'phone-held key' in result.text


def test_openai_token_endpoint_never_mints_a_google_token(harness, monkeypatch):
    api, client, _, _, _ = harness
    stub_google_token(monkeypatch)
    call = api.mobile.reserve('owner', 'default', 'native-stored', {
        'provider': 'openai', 'model': 'gpt-realtime', 'voice': 'alloy',
        'personality': 'balanced', 'personality_instruction': 'Speak naturally.',
    })

    result = client.post(f"/api/plugins/cypher/mobile/calls/{call['call_id']}/token", json={})

    assert result.status_code == 409
    assert 'phone-held key' in result.text


@pytest.mark.parametrize('stage', ['provider_ready', 'provider_key_ready'])
def test_openai_media_diagnostic_is_admitted(harness,stage):
    api, client, _, _, _ = harness
    call_id = client.post('/api/plugins/cypher/mobile/calls', json={'profile': 'default'}).json()['call_id']
    result = client.post(f'/api/plugins/cypher/mobile/calls/{call_id}/events', json={'events': [{
        'event_id': 'openai-ready', 'turn_id': 'connection', 'type': 'media_diagnostic',
        'at_ms': 100, 'provider': 'openai', 'stage': stage,
    }]})

    assert result.status_code == 200
    with api.mobile.connect() as conn:
        assert json.loads(conn.execute('SELECT payload FROM timeline WHERE call_id=?', (call_id,)).fetchone()[0])['provider'] == 'openai'


def test_call_capacity_is_checked_before_creating_native_session(harness, monkeypatch):
    api, client, _, _, _ = harness
    created = []
    monkeypatch.setattr(api.native, 'create', lambda *args: created.append(args) or 'orphan')
    for index in range(8):
        api.mobile.reserve('owner', 'default', f'native-{index}')

    result = client.post('/api/plugins/cypher/mobile/calls', json={'profile': 'default'})

    assert result.status_code == 409
    assert created == []


@pytest.mark.parametrize('provider', ['gemini', 'grok', 'openai'])
def test_callback_answer_accepts_phone_key_provider_without_server_keys(harness, monkeypatch, provider):
    api, client, cfg, _, _ = harness
    monkeypatch.setattr(api, 'secret', lambda *_: pytest.fail('Callback phone-key admission read a Hermes server key'))
    monkeypatch.setattr(api.voice_models, 'xai_secret', lambda *_: pytest.fail('Callback phone-key admission read an xAI server key'))
    monkeypatch.setattr(api.voice_context, 'context', lambda *args: {'context_summary': 'callback context'})
    invitation = api.mobile.invitation('owner', 'default', 'native-stored')

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={
        'voice_provider': provider, 'client_key': True,
    })

    assert response.status_code == 200, response.text
    call = response.json()
    assert call['provider'] == provider and call['context_summary'] == 'callback context'
    assert 'token' not in call
    with api.mobile.connect() as conn:
        row = api.mobile.owned(conn, call['call_id'], 'owner')
    assert json.loads(row['voice_config'])['provider'] == provider
    assert json.loads(row['voice_config'])['client_key'] is True


def test_legacy_call_without_client_key_still_uses_server_key_admission(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api, 'secret', lambda cfg: 'fixture-server-key')
    monkeypatch.setattr(api.voice_models, 'xai_secret', lambda cfg: None)
    monkeypatch.setattr(api.voice_models.importlib.util, 'find_spec', lambda _: SimpleNamespace())

    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini',
    })

    assert response.status_code == 200
    assert response.json()['provider'] == 'gemini'
    assert 'client_key' not in response.json()
