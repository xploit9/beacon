import importlib.util
from pathlib import Path
import sys
import pytest
from fastapi import HTTPException
from test_mobile import harness


def test_plugin_gateway_restart_uses_safe_native_helper_and_selected_profile(harness, monkeypatch):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-restart-fixture')
    calls = []
    async def status(): return {'pid': None, 'running': False, 'exit_code': None}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    monkeypatch.setattr(api, 'native_plugin_restart', lambda profile: calls.append(profile) or {'restart_started': True, 'restart_pid': 123}, raising=False)
    response = client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={})
    assert response.status_code == 200
    assert response.json() == {'restart_started': True, 'restart_pid': 123}
    assert calls == ['default']


def test_plugin_restart_does_not_credit_a_recent_completed_restart(harness, monkeypatch):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-restart-fixture')
    async def status(): return {'pid': 123, 'running': False, 'exit_code': 0}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    monkeypatch.setattr(api, 'native_plugin_restart', lambda profile: {'restart_started': True, 'restart_pid': 123}, raising=False)
    response = client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={})
    assert response.status_code == 200
    assert response.json()['restart_started'] is False


def test_native_plugin_restart_delegates_without_forcing_or_patching_native(monkeypatch):
    from types import SimpleNamespace
    calls = []
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_gateway', SimpleNamespace(
        _restart_gateway_after=lambda profile, **kwargs: calls.append((profile, kwargs)) or {'restart_started': False}))
    assert api.native_plugin_restart('work') == {'restart_started': False}
    assert calls == [('work', {'what': 'Beacon plugin update', 'label': 'Beacon plugin update'})]


def test_plugin_gateway_restart_refuses_an_active_call_or_foreign_owner(harness, monkeypatch):
    from test_mobile import reserve
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-restart-fixture')
    monkeypatch.setattr(api, 'native_plugin_restart', lambda profile: pytest.fail('No restart before admission'), raising=False)
    call_id = reserve(client)
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 409
    with host.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 403
    client.headers.pop('x-fixture-owner')
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 401


@pytest.mark.parametrize('pid,running,exit_code,completed', [(123, False, 0, True), (124, False, 0, False), (123, True, None, False), (123, False, 1, False), (None, False, None, False)])
def test_plugin_restart_completion_is_bound_to_the_actual_native_action(harness, monkeypatch, pid, running, exit_code, completed):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-restart-fixture')
    async def status(): return {'pid': pid, 'running': running, 'exit_code': exit_code, 'lines': ['not exposed']}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    response = client.get('/api/plugins/beacon-restart-fixture/admin/plugin/restart/status', params={'pid': 123})
    assert response.status_code == 200
    assert response.json()['completed'] is completed
    assert 'lines' not in response.json()

spec = importlib.util.spec_from_file_location('test_admin_api_module', Path(__file__).resolve().parents[1] / 'admin_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def install_snapshot_sources(tmp_path, monkeypatch):
    from contextlib import nullcontext
    from types import ModuleType, SimpleNamespace
    import threading
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    profiles_api = ModuleType('hermes_cli.profiles')
    profiles_api.list_profiles = lambda **kwargs: [SimpleNamespace(name='default')]
    hermes_cli.profiles = profiles_api
    routers = ModuleType('hermes_cli.web_routers'); routers.__path__ = []
    config_api = ModuleType('hermes_cli.config')
    config_api.load_config = lambda: {'model': {}}
    scopes = ModuleType('hermes_cli.web_server_profiles')
    scopes._config_profile_scope = lambda profile: nullcontext()
    scopes._resolve_profile_dir = lambda profile: tmp_path
    common = ModuleType('hermes_cli.web_routers._common')
    common._CONFIG_MUTATION_LOCK = threading.RLock()
    profile_api = ModuleType('hermes_cli.web_routers.profiles')
    profile_api._profile_to_dict = lambda info: {'description': '', 'provider': '', 'model': ''}
    for name, module in (('hermes_cli', hermes_cli), ('hermes_cli.profiles', profiles_api),
                         ('hermes_cli.web_routers', routers), ('hermes_cli.config', config_api),
                         ('hermes_cli.web_server_profiles', scopes), ('hermes_cli.web_routers._common', common),
                         ('hermes_cli.web_routers.profiles', profile_api)):
        monkeypatch.setitem(sys.modules, name, module)


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
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-fixture')
    url = '/api/plugins/beacon-fixture/admin/model-capabilities'
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
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-fixture')
    url = '/api/plugins/beacon-fixture/admin/bot'
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
    common._CONFIG_MUTATION_LOCK = threading.Lock()
    profiles = ModuleType('hermes_cli.web_routers.profiles')
    profiles._write_profile_model = write_model
    for name, module in (('hermes_cli', hermes_cli), ('hermes_cli.web_routers', routers),
                         ('hermes_cli.config', config_api), ('hermes_cli.web_server_profiles', scopes),
                         ('hermes_cli.web_routers._common', common), ('hermes_cli.web_routers.profiles', profiles)):
        monkeypatch.setitem(sys.modules, name, module)
    monkeypatch.setattr(api, 'snapshot', lambda profile: pytest.fail('save_model re-entered snapshot under the native config lock'))
    monkeypatch.setattr(api, '_snapshot_locked', lambda profile: {'profile': profile}, raising=False)
    with pytest.raises(HTTPException) as error:
        api.save_model('default', {'provider': 'custom', 'model': 'phone-model', 'revision': '0' * 64})
    assert error.value.status_code == 409
    assert writes == [] and config['model']['default'] == 'concurrent-model'
    current = api.revision(config['model'])
    assert api.save_model('default', {'provider': 'custom', 'model': 'phone-model', 'revision': current})['ok']
    assert writes == [(tmp_path, 'custom', 'phone-model')]
    assert config['model']['default'] == 'phone-model' and config['voice']['enabled'] is True


@pytest.mark.parametrize(('kind', 'status', 'detail'), [('invalid', 409, 'encoding'), ('directory', 503, 'read')])
def test_bot_instruction_read_errors_are_actionable(tmp_path, monkeypatch, kind, status, detail):
    soul = tmp_path / 'SOUL.md'
    if kind == 'invalid': soul.write_bytes(b'\xff')
    else: soul.mkdir()
    install_snapshot_sources(tmp_path, monkeypatch)

    with pytest.raises(HTTPException) as error:
        api.snapshot('default')

    assert error.value.status_code == status
    assert detail in error.value.detail.lower()


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
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-list-fixture')
    url = '/api/plugins/beacon-list-fixture/admin/bots'
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
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-update-fixture')
    url = '/api/plugins/beacon-update-fixture/admin/update'
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
    assert calls == ['start'], 'a foreign caller cannot start an update'
    del client.headers['x-fixture-owner']
    assert client.post(url).status_code == 401


def test_current_receipt_requires_matching_time_and_action():
    state = {'action': {'action_id': 'current'}, 'receipt': {'receipt': {'started_at': '2026-10-14T10:00:01Z'}}}
    assert api.current_receipt(state, '2026-10-14T10:00:00Z', 'current')
    assert not api.current_receipt(state, '2026-10-14T10:00:00Z', 'different')
    assert not api.current_receipt(state, '', 'current')



def _gateway_modules(monkeypatch, tmp_path, live_pids, lock_pid=6500):
    from types import ModuleType, SimpleNamespace
    import json as _json
    profiles = []
    for name in ('default', 'cybercb', 'coder'):
        home = tmp_path / name
        home.mkdir()
        profiles.append(SimpleNamespace(name=name, path=home))
    status = ModuleType('gateway.status')

    def strict(pid_path):
        # Hermes' rule: a PID file beside a held lock must name the lock holder.
        if pid_path.exists() and _json.loads(pid_path.read_text())['pid'] != lock_pid:
            raise RuntimeError('gateway PID and lock identities disagree')
        return (lock_pid, 1.0)
    status.get_running_pid_identity_strict = strict
    status._read_pid_record = lambda pid_path: _json.loads(Path(pid_path).read_text())
    # Hermes' rule: alive and with the recorded start time (a reused PID has another one).
    status._live_pid_from_record = lambda record: record['pid'] if record.get('pid') in live_pids and record.get('start_time', 1.0) == 1.0 else None
    gateway = ModuleType('gateway'); gateway.status = status
    hermes_cli = ModuleType('hermes_cli'); hermes_cli.__path__ = []
    profiles_api = ModuleType('hermes_cli.profiles'); profiles_api.list_profiles = lambda **kwargs: profiles
    hermes_cli.profiles = profiles_api
    for name, module in (('gateway', gateway), ('gateway.status', status), ('hermes_cli', hermes_cli), ('hermes_cli.profiles', profiles_api)):
        monkeypatch.setitem(sys.modules, name, module)
    return {p.name: p.path for p in profiles}


def test_update_leaves_gateway_pid_for_native_preflight(harness, monkeypatch, tmp_path):
    import json as _json
    host, client, _, _, app = harness
    homes = _gateway_modules(monkeypatch, tmp_path, live_pids={6500})
    pid_path = homes['cybercb'] / 'gateway.pid'
    original = _json.dumps({'pid': 67256})
    pid_path.write_text(original)
    async def native_preflight():
        return {'ok': False, 'error': 'Could not inspect gateway PID' if pid_path.exists() else 'unexpected removal'}
    monkeypatch.setattr(api, 'native_update', native_preflight)
    monkeypatch.setattr(api, 'update_in_progress', lambda: False)
    app.include_router(api.build_router(host.identity, host.body), prefix='/api/plugins/beacon-pid-fixture')
    result = client.post('/api/plugins/beacon-pid-fixture/admin/update').json()
    assert result['error'] == 'Could not inspect gateway PID'
    assert pid_path.read_text() == original
    assert not list(pid_path.parent.glob('gateway.pid.stale-*'))


def test_update_in_progress_asks_hermes_for_its_marker_and_checkout_lock(monkeypatch):
    seen = []
    monkeypatch.setattr(api, '_native_update_in_progress', lambda: seen.append('asked') or True)
    assert api.update_in_progress() is True and seen == ['asked']
    monkeypatch.setattr(api, '_native_update_in_progress', None)
    assert api.update_in_progress() is False
