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
        'plugins:\n  enabled:\n    - beacon\n  disabled: []\n  entries:\n    beacon:\n'
        + '\n'.join(setting_lines)
        + '\n',
        encoding='utf-8',
    )


def _use_real_settings(api, monkeypatch):
    monkeypatch.syspath_prepend(str(Path(sys.prefix).parent))
    monkeypatch.setattr(api.mobile, 'settings', api._beacon_original_mobile_settings)


def _native_profiles(monkeypatch, names):
    monkeypatch.syspath_prepend(str(Path(sys.prefix).parent))
    from hermes_cli import profiles

    calls = []

    def list_profiles(*, lazy_skill_count=False):
        calls.append(lazy_skill_count)
        return [SimpleNamespace(name=name) for name in names]

    monkeypatch.setattr(profiles, 'list_profiles', list_profiles)
    return calls


def test_fresh_dashboard_open_binds_verified_owner_and_returns_connect_info(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default', 'work'])
    set_public_url(monkeypatch, 'https://hermes.example.test/beacon')

    response = client.get('/api/plugins/beacon/connect-info')

    assert response.status_code == 200, response.text
    assert response.json() == {'url': 'https://hermes.example.test/beacon', 'name': 'hermes.example.test'}
    assert response.headers['cache-control'] == 'no-store'
    assert api.mobile.settings()['owner_id'] == 'owner'
    assert api.mobile.settings()['owner_provider'] == 'basic'


def test_first_verified_phone_request_binds_idempotently_and_refuses_second_identity(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default'])
    path = '/api/plugins/beacon/mobile/history-retention?profile=default'

    assert client.get(path).status_code == 200
    assert client.get(path).status_code == 200
    client.headers['x-fixture-owner'] = 'foreign'
    refused = client.get(path)

    assert refused.status_code == 403
    assert refused.json() == {'detail': 'Owner not authorized'}
    assert api.mobile.settings()['owner_id'] == 'owner'


def test_unauthenticated_first_plugin_request_never_binds_owner(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    del client.headers['x-fixture-owner']

    response = client.get('/api/plugins/beacon/mobile/bootstrap?profile=default')

    assert response.status_code == 401
    assert api.mobile.settings().get('owner_id') is None
    assert api.mobile.settings().get('owner_provider') is None
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT 1 FROM meta WHERE key='owner_claim'").fetchone() is None


def test_two_first_bindings_atomically_choose_one_owner(harness, monkeypatch):
    from concurrent.futures import ThreadPoolExecutor

    api, _, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)

    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(api.mobile.claim_owner, owner, 'basic') for owner in ('owner-a', 'owner-b')]
        created = [future.result() for future in futures]

    assert sorted(created) == [False, True]
    assert api.mobile.settings()['owner_id'] in {'owner-a', 'owner-b'}


def test_first_request_persists_owner_and_authorizes_native_profiles_including_new_ones(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    names = ['default', 'work']
    calls = _native_profiles(monkeypatch, names)

    path = '/api/plugins/beacon/mobile/history-retention'
    assert client.get(path + '?profile=default').status_code == 200
    assert calls[0] is True

    settings = api.mobile.settings()
    assert settings.get('owner_id') == 'owner'
    assert settings.get('owner_provider') == 'basic'
    assert settings.get('callback_tool_enabled') is True
    assert settings.get('scheduled_callbacks_enabled') is True
    assert 'profiles' not in settings
    assert client.get('/api/plugins/beacon/mobile/bootstrap?profile=work').json()['capabilities']['callbacks'] is True

    set_public_url(monkeypatch, 'https://hermes.example.test/beacon')
    info = client.get('/api/plugins/beacon/connect-info')
    assert info.status_code == 200
    assert info.json() == {'url': 'https://hermes.example.test/beacon', 'name': 'hermes.example.test'}

    assert client.get(path + '?profile=work').status_code == 200
    names.append('created-after-claim')
    assert client.get(path + '?profile=created-after-claim').status_code == 200

    _write_config(home, {'callback_tool_enabled': False, 'scheduled_callbacks_enabled': False})
    assert api.mobile.settings(profile='work')['callback_tool_enabled'] is False
    assert api.mobile.settings(profile='work')['scheduled_callbacks_enabled'] is False
    assert client.get('/api/plugins/beacon/mobile/bootstrap?profile=work').json()['capabilities']['callbacks'] is False
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(path + '?profile=default').status_code == 403


def test_fresh_install_bot_mode_uses_native_profiles_without_yaml_profiles(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default'])
    monkeypatch.setattr(api.native, 'rpc', lambda owner, provider, method, params: {
        'profiles.list': {'profiles': [{'name': 'default', 'display_name': 'Default'}]},
        'groups.list': {'rooms': [], 'next_offset': None},
    }[method])

    assert api.mobile.claim_owner('owner', 'basic')
    response = client.get('/api/plugins/beacon/mobile/bot-mode')

    assert response.status_code == 200, response.text
    assert [row['profile'] for row in response.json()['bots']] == ['default']


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
    assert client.get('/api/plugins/beacon/mobile/history-retention?profile=yaml-profile').status_code == 403


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
    assert api.mobile.claim_owner('owner', 'basic')

    path = '/api/plugins/beacon/mobile/assistant-conversation'
    session = '20261020_123456_abcd'
    response = client.put(path, json={'profile': 'work', 'session_id': session})
    assert response.status_code == 200, response.text
    assert response.json() == {'registered': True, 'conversation_id': session}
    assert verified[-1] == ('owner', 'basic', 'work', session)
    assert api.mobile.settings(profile='work').get('scheduled_callback_conversation_id') == session
    assert 'scheduled_callback_conversation_id' not in api.mobile.settings(profile='default')

    second = client.put(path, json={'profile': 'work', 'session_id': '20261020_123457_abcd'})
    assert second.status_code == 200, second.text
    assert second.json() == {'registered': True, 'conversation_id': session}
    assert api.mobile.settings(profile='work').get('scheduled_callback_conversation_id') == session

    enabled = []
    monkeypatch.setattr(api.proactive, 'set_enabled', lambda mobile, owner, profile, value: enabled.append((owner, profile, value)) or {'enabled': value})
    proactive = client.put('/api/plugins/beacon/mobile/proactive?profile=work', json={'enabled': True})
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


def test_assistant_conversation_registration_replaces_a_gone_stored_chat(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default'])
    assert api.mobile.claim_owner('owner', 'basic')
    gone_checks = 0

    def verify(owner, provider, profile, session_id, **kwargs):
        nonlocal gone_checks
        if session_id == 'gone-chat':
            gone_checks += 1
            if gone_checks > 1:
                raise LookupError('conversation no longer exists')
        return {'id': session_id}

    monkeypatch.setattr(api.native, 'verify', verify)
    path = '/api/plugins/beacon/mobile/assistant-conversation'
    first = client.put(path, json={'profile': 'default', 'session_id': 'gone-chat'})
    assert first.status_code == 200, first.text
    replacement = client.put(path, json={'profile': 'default', 'session_id': 'replacement-chat'})

    assert replacement.status_code == 200, replacement.text
    assert replacement.json() == {'registered': True, 'conversation_id': 'replacement-chat'}
    assert api.mobile.settings(profile='default').get('scheduled_callback_conversation_id') == 'replacement-chat'


def test_claimed_profile_call_delivery_uses_registered_assistant_conversation(harness, monkeypatch):
    from datetime import datetime, timezone

    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default'])
    assert api.mobile.claim_owner('owner', 'basic')
    session = '20261020_123456_abcd'
    assert client.put('/api/plugins/beacon/mobile/assistant-conversation',
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
