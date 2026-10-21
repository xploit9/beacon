import json
import sys
from contextlib import nullcontext
from types import SimpleNamespace

import pytest

from test_mobile import harness


def bind_conversation_rows(monkeypatch, rows):
    db = SimpleNamespace(get_session=rows.get)
    server = SimpleNamespace(_profile_db=lambda scope: nullcontext(db))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))


@pytest.mark.parametrize('provider', ['gemini', 'grok', 'openai'])
def test_phone_key_calls_record_provider_and_keep_context_without_server_secrets(harness, monkeypatch, provider):
    api, client, _, _, _ = harness
    created = []
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
    assert json.loads(row['voice_config'])['provider'] == provider
    assert provider in row['voice_config'] and 'fixture-secret' not in row['voice_config']
    assert json.loads(row['voice_config'])['client_key'] is True

    context = client.get(f"/api/plugins/cypher/mobile/calls/{call['call_id']}/context")
    assert context.status_code == 200 and context.json()['context_summary'] == 'owned native context'


def test_unowned_existing_chat_call_error_is_actionable_but_other_lookup_errors_stay_generic(harness, monkeypatch):
    api, client, _, _, _ = harness
    rows = {'phone-unowned': {'id': 'phone-unowned', 'user_id': None, 'source': 'android'}}
    bind_conversation_rows(monkeypatch, rows)
    monkeypatch.setattr(api.native, 'reserve_conversation', lambda *args: (_ for _ in ()).throw(LookupError('not owned')))

    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'conversation_id': 'phone-unowned', 'voice_provider': 'gemini', 'client_key': True,
    })

    assert response.status_code == 404
    assert response.json()['detail'] == 'This chat is not linked to your Hermes sign-in, so Beacon cannot start a call from it. Start a new chat in Beacon.'
    rows.clear()
    missing = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'conversation_id': 'missing', 'voice_provider': 'gemini', 'client_key': True,
    })
    assert missing.status_code == 404 and missing.json()['detail'] == 'Owned resource unavailable'


def test_unowned_existing_callback_answer_keeps_404_and_gives_actionable_error(harness, monkeypatch):
    api, client, _, _, _ = harness
    rows = {'phone-unowned': {'id': 'phone-unowned', 'user_id': None, 'source': 'android'}}
    bind_conversation_rows(monkeypatch, rows)
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))
    invitation = api.mobile.invitation('owner', 'default', 'phone-unowned')

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={
        'voice_provider': 'gemini', 'client_key': True,
    })

    assert response.status_code == 404
    assert response.json()['detail'] == 'This chat is not linked to your Hermes sign-in, so Beacon cannot start a call from it. Start a new chat in Beacon.'
    assert verified == []
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT state FROM notices WHERE id=?', (invitation,)).fetchone()['state'] == 'pending'


@pytest.mark.parametrize('field', ['api_key', 'key', 'token'])
def test_client_key_request_rejects_any_provider_key_and_never_stores_it(harness, field):
    api, client, _, _, _ = harness
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'openai', 'client_key': True,
        field: 'fixture-secret-value',
    })
    assert response.status_code == 400
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE voice_config LIKE '%fixture-secret-value%'").fetchone()[0] == 0


def test_client_key_request_rejects_nested_provider_keys(harness):
    api, client, _, _, _ = harness
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'openai', 'client_key': True,
        'provider_options': {'openai_api_key': 'fixture-nested-secret'},
    })
    assert response.status_code == 400
    assert 'fixture-nested-secret' not in response.text
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE voice_config LIKE '%fixture-nested-secret%'").fetchone()[0] == 0


def test_call_reservation_rejects_server_key_mode(harness):
    _, client, _, _, _ = harness
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini', 'client_key': False,
    })

    assert response.status_code == 400
    assert response.json()['detail'] == 'Update Beacon: provider keys stay on the phone.'


def test_callback_answer_rejects_server_key_mode(harness):
    api, client, _, _, _ = harness
    invitation = api.mobile.invitation('owner', 'default', 'native-stored')

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={
        'voice_provider': 'gemini', 'client_key': False,
    })

    assert response.status_code == 400
    assert response.json()['detail'] == 'Update Beacon: provider keys stay on the phone.'
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT state FROM notices WHERE id=?', (invitation,)).fetchone()['state'] == 'pending'


def test_server_token_route_is_removed(harness):
    _, client, _, _, _ = harness
    call = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini', 'client_key': True,
    })
    assert call.status_code == 200, call.text

    response = client.post(f"/api/plugins/cypher/mobile/calls/{call.json()['call_id']}/token", json={})

    assert response.status_code == 404


@pytest.mark.parametrize('stage', ['provider_ready', 'provider_key_ready'])
def test_openai_media_diagnostic_is_admitted(harness,stage):
    api, client, _, _, _ = harness
    call_id = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'openai', 'client_key': True,
    }).json()['call_id']
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

    result = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini', 'client_key': True,
    })

    assert result.status_code == 409
    assert created == []


@pytest.mark.parametrize('provider', ['gemini', 'grok', 'openai'])
def test_callback_answer_accepts_phone_key_provider_without_server_keys(harness, provider):
    api, client, _, _, _ = harness
    invitation = api.mobile.invitation('owner', 'default', 'native-stored')

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={
        'voice_provider': provider, 'client_key': True,
    })

    assert response.status_code == 200, response.text
    call = response.json()
    assert call['provider'] == provider
    assert 'context_summary' not in call
    assert 'token' not in call
    with api.mobile.connect() as conn:
        row = api.mobile.owned(conn, call['call_id'], 'owner')
    assert json.loads(row['voice_config'])['provider'] == provider
    assert json.loads(row['voice_config'])['client_key'] is True


def test_callback_answer_does_not_build_context_again(harness, monkeypatch):
    api, client, _, _, _ = harness
    invitation = api.mobile.invitation('owner', 'default', 'native-stored')
    contexts = []
    monkeypatch.setattr(api.voice_context, 'context', lambda *args: contexts.append(args) or {'context_summary': 'duplicate'})

    response = client.post(f'/api/plugins/cypher/mobile/callbacks/{invitation}/answer', json={
        'voice_provider': 'openai', 'client_key': True,
    })

    assert response.status_code == 200, response.text
    assert contexts == []
    assert 'context_summary' not in response.json()


def test_call_reservation_rejects_missing_client_key(harness):
    _, client, _, _, _ = harness
    response = client.post('/api/plugins/cypher/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini',
    })

    assert response.status_code == 400
    assert response.json()['detail'] == 'Update Beacon: provider keys stay on the phone.'
