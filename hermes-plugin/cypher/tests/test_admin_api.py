import importlib.util
from pathlib import Path
import sys
import pytest
from fastapi import HTTPException
from test_mobile import harness

spec = importlib.util.spec_from_file_location('test_admin_api_module', Path(__file__).resolve().parents[1] / 'admin_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def test_reasoning_choices_use_published_metadata_and_no_invented_levels():
    assert api.reasoning_options({'reasoning_efforts': [{'value': 'low'}, {'value': 'max'}, {'value': 'future'}]}) == ['low', 'max']
    assert api.reasoning_options({'capabilities': {'reasoning_effort': ['high', 'low']}}) == ['low', 'high']
    assert api.reasoning_options({'supports_reasoning_effort': False, 'reasoning_efforts': ['high']}) == []
    assert api.reasoning_options({}) is None
    assert api.reasoning_options({'capabilities': None, 'reasoning_efforts': ['low', 'max']}) == ['low', 'max']


def test_reasoning_for_lazy_sessions_uses_native_profile_provider_resolution(monkeypatch):
    from contextlib import nullcontext
    from types import SimpleNamespace
    seen = []
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_profiles', SimpleNamespace(_config_profile_scope=lambda profile: nullcontext()))
    monkeypatch.setitem(sys.modules, 'hermes_cli.runtime_provider', SimpleNamespace(resolve_runtime_provider=lambda **values: seen.append(values) or {'provider': 'fixture', 'base_url': ''}))
    monkeypatch.setitem(sys.modules, 'providers', SimpleNamespace(get_provider_profile=lambda provider: SimpleNamespace(supported_reasoning_efforts=lambda model: ['low', 'high']) if provider == 'fixture' else None))
    result = api.model_capabilities('default', '', 'fixture-model')
    assert seen == [{'requested': None, 'target_model': 'fixture-model'}]
    assert result == {'model': 'fixture-model', 'provider': 'fixture', 'reasoning_options': ['low', 'high'], 'reasoning_known': True}


def test_reasoning_default_provider_keeps_auth_and_query_validation(harness, monkeypatch):
    host, client, _, _, app = harness
    seen = []
    monkeypatch.setattr(api, 'model_capabilities', lambda profile, provider, model: seen.append((profile, provider, model)) or {'reasoning_options': ['low'], 'reasoning_known': True})
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/cypher-fixture')
    url = '/api/plugins/cypher-fixture/admin/model-capabilities'
    assert client.get(url, params={'model': 'fixture-model'}).status_code == 200
    assert seen == [('default', '', 'fixture-model')]
    assert client.get(url, params={'model': 'fixture-model', 'provider': 'bad provider'}).status_code == 400
    assert client.get(url, params={'model': 'fixture-model', 'profile': 'foreign'}).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url, params={'model': 'fixture-model'}).status_code == 401


def test_update_without_native_action_cannot_confirm_a_receipt():
    assert api.current_receipt({'action': None, 'receipt': {'receipt': {'started_at': '2026-10-15T12:00:00+00:00'}}}, '2026-10-15T11:00:00+00:00', 'expected') is False


def test_router_enforces_owner_and_profile_before_source_access(harness, monkeypatch):
    host, client, _, _, app = harness
    monkeypatch.setattr(api, 'snapshot', lambda profile: {'profile': profile, 'model': 'fixture', 'revision': 'a' * 64})
    monkeypatch.setattr(api, 'save_model', lambda profile, body: {'ok': True})
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/cypher-fixture')
    url = '/api/plugins/cypher-fixture/admin/bot'
    assert client.get(url).json()['profile'] == 'default'
    assert client.get(url + '?profile=foreign').status_code == 403
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.put(url, json={}).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url).status_code == 401


def test_model_revision_rejects_stale_write_and_preserves_unrelated_config(tmp_path, monkeypatch):
    from contextlib import nullcontext
    from types import ModuleType
    import threading
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    config = {'model': {'provider': 'custom', 'default': 'concurrent-model'}, 'voice': {'enabled': True}}
    writes = []
    def write_model(profile_dir, provider, model):
        writes.append((profile_dir, provider, model))
        config['model'] = {'provider': provider, 'default': model}
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    routers = ModuleType('hermes_cli.web_routers'); routers.__path__ = []
    config_api = ModuleType('hermes_cli.config')
    config_api.load_config = lambda: config
    config_api.require_readable_config_before_write = lambda: None
    scopes = ModuleType('hermes_cli.web_server_profiles')
    scopes._config_profile_scope = lambda profile: nullcontext()
    scopes._resolve_profile_dir = lambda profile: tmp_path
    common = ModuleType('hermes_cli.web_routers._common')
    common._CONFIG_MUTATION_LOCK = threading.RLock()
    profiles = ModuleType('hermes_cli.web_routers.profiles')
    profiles._write_profile_model = write_model
    for name, module in (('hermes_cli', hermes_cli), ('hermes_cli.web_routers', routers),
                         ('hermes_cli.config', config_api), ('hermes_cli.web_server_profiles', scopes),
                         ('hermes_cli.web_routers._common', common), ('hermes_cli.web_routers.profiles', profiles)):
        monkeypatch.setitem(sys.modules, name, module)
    monkeypatch.setattr(api, 'snapshot', lambda profile: {'profile': profile})
    with pytest.raises(HTTPException) as error:
        api.save_model('default', {'provider': 'custom', 'model': 'phone-model', 'revision': '0' * 64})
    assert error.value.status_code == 409
    assert writes == [] and config['model']['default'] == 'concurrent-model'
    current = api.revision(config['model'])
    assert api.save_model('default', {'provider': 'custom', 'model': 'phone-model', 'revision': current})['ok']
    assert writes == [(tmp_path, 'custom', 'phone-model')]
    assert config['model']['default'] == 'phone-model' and config['voice']['enabled'] is True


def test_bot_list_only_exposes_configured_profiles(harness, monkeypatch):
    host, client, cfg, _, app = harness
    from types import ModuleType, SimpleNamespace
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    native_profiles = ModuleType('hermes_cli.profiles')
    native_profiles.list_profiles = lambda **kwargs: [
        SimpleNamespace(name='default', display_name='Owner bot', description='Personal tasks'),
        SimpleNamespace(name='foreign', display_name='Foreign bot', description='Private purpose'),
    ]
    routes = ModuleType('hermes_cli.web_routers'); routes.__path__ = []
    profile_routes = ModuleType('hermes_cli.web_routers.profiles')
    profile_routes._profile_to_dict = lambda info: {'name': info.name, 'display_name': info.display_name, 'description': info.description}
    hermes_cli.profiles = native_profiles
    monkeypatch.setitem(sys.modules, 'hermes_cli', hermes_cli)
    monkeypatch.setitem(sys.modules, 'hermes_cli.profiles', native_profiles)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_routers', routes)
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_routers.profiles', profile_routes)
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/cypher-list-fixture')
    url = '/api/plugins/cypher-list-fixture/admin/bots'
    assert client.get(url).json() == {'bots': [{'profile': 'default', 'title': 'Owner bot', 'purpose': 'Personal tasks'}]}
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(url).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url).status_code == 401


def test_update_helper_server_clock_scope_and_old_receipts(harness, monkeypatch):
    host, client, _, _, app = harness
    calls = []
    async def start():
        calls.append('start')
        return {'ok': True, 'name': 'hermes-update', 'action_id': 'new-action', 'pid': 123}
    async def status():
        return {'action': {'running': False, 'action_id': 'new-action'},
                'receipt': {'receipt': {'started_at': '2020-01-01T00:00:00Z', 'finished_at': '2020-01-01T00:01:00Z', 'outcome': 'success'}}}
    monkeypatch.setattr(api, 'native_update', start)
    monkeypatch.setattr(api, 'native_update_status', status)
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/cypher-update-fixture')
    url = '/api/plugins/cypher-update-fixture/admin/update'
    result = client.post(url).json()
    assert result['ok'] and result['accepted']
    from datetime import datetime, timezone
    assert abs((datetime.now(timezone.utc) - datetime.fromisoformat(result['requested_at'])).total_seconds()) < 5
    assert result['action_id'] == 'new-action'
    assert client.get(url + '/status', params={'requested_at': result['requested_at'], 'action_id': 'new-action'}).json()['receipt_current'] is False
    assert client.get(url + '/status?requested_at=bad').status_code == 400
    async def already():
        return {'ok': True, 'already_running': True, 'action_id': 'existing-action'}
    monkeypatch.setattr(api, 'native_update', already)
    duplicate = client.post(url).json()
    assert duplicate['ok'] is False and duplicate['accepted'] is False
    assert duplicate['requested_at'] is None and duplicate['error'] == 'update_already_running'
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.post(url).status_code == 403
    assert client.get(url + '/status').status_code == 403
    assert calls == ['start']
    del client.headers['x-fixture-owner']
    assert client.post(url).status_code == 401


def test_current_receipt_requires_matching_time_and_action():
    state = {'action': {'action_id': 'current'}, 'receipt': {'receipt': {'started_at': '2026-10-14T10:00:01Z'}}}
    assert api.current_receipt(state, '2026-10-14T10:00:00Z', 'current')
    assert not api.current_receipt(state, '2026-10-14T10:00:00Z', 'different')
    assert not api.current_receipt(state, '', 'current')
