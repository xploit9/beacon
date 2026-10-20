import json
from pathlib import Path
import sys
from types import SimpleNamespace

from test_mobile import harness, load_private
from test_connect_info import set_public_url


def _write_config(home, settings):
    setting_lines = ['      settings:']
    if not settings:
        setting_lines = ['      settings: {}']
    else:
        for key, value in settings.items():
            if isinstance(value, list):
                setting_lines.append(f'        {key}:')
                setting_lines.extend(f'          - {json.dumps(item)}' for item in value)
            else:
                setting_lines.append(f'        {key}: {json.dumps(value)}')
    (home / 'config.yaml').write_text(
        'plugins:\n  enabled:\n    - cypher\n  disabled: []\n  entries:\n    cypher:\n'
        + '\n'.join(setting_lines)
        + '\n',
        encoding='utf-8',
    )


def _use_real_settings(api, monkeypatch):
    monkeypatch.syspath_prepend(str(Path(sys.prefix).parent))
    monkeypatch.setattr(api.mobile, 'settings', api._cypher_original_mobile_settings)


def _native_profiles(monkeypatch, names):
    monkeypatch.syspath_prepend(str(Path(sys.prefix).parent))
    from hermes_cli import profiles

    calls = []

    def list_profiles(*, lazy_skill_count=False):
        calls.append(lazy_skill_count)
        return [SimpleNamespace(name=name) for name in names]

    monkeypatch.setattr(profiles, 'list_profiles', list_profiles)
    return calls


def test_unowned_connect_info_offers_claim_only_to_dashboard_browser(harness, monkeypatch):
    _, client, cfg, _, app = harness
    cfg.clear()

    response = client.get('/api/plugins/cypher/connect-info')
    assert response.status_code == 200
    assert response.json() == {'claim_required': True}
    assert response.headers['cache-control'] == 'no-store'

    del client.headers['x-fixture-owner']
    client.headers['authorization'] = 'Bearer phone-access-token'
    client.headers['x-fixture-bearer'] = 'owner'
    bearer = client.get('/api/plugins/cypher/connect-info')
    assert bearer.status_code == 403
    assert bearer.json() == {'detail': 'Owner not authorized'}

    del client.headers['authorization']
    del client.headers['x-fixture-bearer']
    app.state.auth_required = False
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', SimpleNamespace(_has_valid_session_token=lambda request: False))
    assert client.get('/api/plugins/cypher/connect-info').status_code == 401


def test_claim_refuses_bearer_cross_site_and_loopback_requests(harness):
    _, client, cfg, _, app = harness
    cfg.clear()
    path = '/api/plugins/cypher/owner/claim'

    assert client.post(path, headers={'sec-fetch-site': 'cross-site'}).status_code == 403

    del client.headers['x-fixture-owner']
    client.headers['authorization'] = 'Bearer phone-access-token'
    client.headers['x-fixture-bearer'] = 'owner'
    assert client.post(path).status_code == 401

    del client.headers['authorization']
    del client.headers['x-fixture-bearer']
    app.state.auth_required = False
    assert client.post(path).status_code == 401


def test_claim_persists_owner_and_authorizes_native_profiles_including_new_ones(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    names = ['default', 'work']
    calls = _native_profiles(monkeypatch, names)

    claimed = client.post('/api/plugins/cypher/owner/claim')
    assert claimed.status_code == 200, claimed.text
    assert claimed.json() == {'claimed': True}
    assert calls[0] is True

    settings = api.mobile.settings()
    assert settings.get('owner_id') == 'owner'
    assert settings.get('owner_provider') == 'basic'
    assert settings.get('callback_tool_enabled') is True
    assert settings.get('scheduled_callbacks_enabled') is True
    assert 'profiles' not in settings
    assert client.get('/api/plugins/cypher/mobile/bootstrap?profile=work').json()['capabilities']['callbacks'] is True

    set_public_url(monkeypatch, 'https://hermes.example.test/cypher')
    qr = client.get('/api/plugins/cypher/connect-info')
    assert qr.status_code == 200
    assert qr.json() == {'url': 'https://hermes.example.test/cypher', 'name': 'hermes.example.test'}

    path = '/api/plugins/cypher/mobile/history-retention'
    assert client.get(path + '?profile=default').status_code == 200
    assert client.get(path + '?profile=work').status_code == 200
    names.append('created-after-claim')
    assert client.get(path + '?profile=created-after-claim').status_code == 200

    assert client.post('/api/plugins/cypher/owner/claim').status_code == 409
    _write_config(home, {'callback_tool_enabled': False, 'scheduled_callbacks_enabled': False})
    assert api.mobile.settings(profile='work')['callback_tool_enabled'] is False
    assert api.mobile.settings(profile='work')['scheduled_callbacks_enabled'] is False
    assert client.get('/api/plugins/cypher/mobile/bootstrap?profile=work').json()['capabilities']['callbacks'] is False
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(path + '?profile=default').status_code == 403


def test_explicit_yaml_owner_settings_win_over_stored_claim(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {
        'owner_id': 'yaml-owner',
        'owner_provider': 'yaml-provider',
        'profiles': ['yaml-profile'],
        'scheduled_callback_conversation_id': 'yaml-assistant',
    })
    _use_real_settings(api, monkeypatch)
    with api.mobile.connect() as conn:
        conn.execute(
            'INSERT INTO meta(key,value) VALUES(?,?)',
            ('owner_claim', json.dumps({'owner_id': 'stored-owner', 'owner_provider': 'stored-provider',
                'scheduled_callback_conversations': {'yaml-profile': 'stored-assistant'}})),
        )

    settings = api.mobile.settings()
    assert settings.get('owner_id') == 'yaml-owner'
    assert settings.get('owner_provider') == 'yaml-provider'
    assert settings.get('profiles') == ['yaml-profile']
    assert api.mobile.settings(profile='yaml-profile').get('scheduled_callback_conversation_id') == 'yaml-assistant'
    assert client.post('/api/plugins/cypher/owner/claim').status_code == 409


def test_assistant_conversation_registration_verifies_and_persists_per_profile(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default', 'work'])
    verified = []

    def verify(*args):
        verified.append(args)
        return {'id': args[3]}

    monkeypatch.setattr(api.native, 'verify', verify)
    assert client.post('/api/plugins/cypher/owner/claim').status_code == 200

    path = '/api/plugins/cypher/mobile/assistant-conversation'
    session = '20261020_123456_abcd'
    response = client.put(path, json={'profile': 'work', 'session_id': session})
    assert response.status_code == 200, response.text
    assert response.json() == {'registered': True}
    assert verified[-1] == ('owner', 'basic', 'work', session)
    assert api.mobile.settings(profile='work').get('scheduled_callback_conversation_id') == session
    assert 'scheduled_callback_conversation_id' not in api.mobile.settings(profile='default')

    enabled = []
    monkeypatch.setattr(api.proactive, 'set_enabled', lambda mobile, owner, profile, value: enabled.append((owner, profile, value)) or {'enabled': value})
    proactive = client.put('/api/plugins/cypher/mobile/proactive?profile=work', json={'enabled': True})
    assert proactive.status_code == 200, proactive.text
    assert enabled == [('owner', 'work', True)]

    def reject(*args):
        raise LookupError('not owned')

    monkeypatch.setattr(api.native, 'verify', reject)
    rejected = client.put(path, json={'profile': 'work', 'session_id': 'foreign-session'})
    assert rejected.status_code == 404
    assert api.mobile.settings(profile='work').get('scheduled_callback_conversation_id') == session

    client.headers['x-fixture-owner'] = 'foreign'
    assert client.put(path, json={'profile': 'work', 'session_id': session}).status_code == 403


def test_claimed_profile_call_delivery_uses_registered_assistant_conversation(harness, monkeypatch):
    from datetime import datetime, timezone

    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default'])
    assert client.post('/api/plugins/cypher/owner/claim').status_code == 200
    session = '20261020_123456_abcd'
    assert client.put('/api/plugins/cypher/mobile/assistant-conversation',
                      json={'profile': 'default', 'session_id': session}).status_code == 200

    cfg = api.mobile.settings(profile='default')
    setup = api.proactive.default_setup()
    setup['delivery'] = 'call'
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'setup'), json.dumps(setup))
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'enabled'), '1')
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'setup_complete'), '1')
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    plugin = load_private('__init__')
    monkeypatch.setattr(plugin, 'current_profile', lambda cfg: 'default')
    monkeypatch.setattr(plugin, '_watch_context', lambda cfg, profile, session: {'id':'watch_email','job_id':'watch-job'})
    monkeypatch.setattr(plugin.proactive, 'profile_now', lambda profile: now)
    observers = plugin.observers
    now = datetime(2026, 10, 20, 12, tzinfo=timezone.utc)
    monkeypatch.setattr(observers, 'time', SimpleNamespace(time=lambda: now.timestamp()))
    assert observers.source_observed(api.mobile, cfg, 'default', 'watch-run', 'mcp__mail__read_email',
                                     {'id': 'claimed-call'}, 'ok')

    result = json.loads(plugin.email_notice(
        {'item_id': 'claimed-call', 'reason': 'Important email'}, session_id='watch-run'))

    assert result['status'] == 'pending'
    assert result['conversation_id'] == session
