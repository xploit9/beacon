import json
from types import SimpleNamespace

import pytest

from test_mobile import harness


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

    context = client.get(f"/api/plugins/cypher/mobile/calls/{call['call_id']}/context")
    assert context.status_code == 200 and context.json()['context_summary'] == 'owned native context'


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
