import importlib.util
from pathlib import Path
import sys
import asyncio
from types import SimpleNamespace
import pytest
from fastapi import HTTPException
from test_mobile import harness


def test_plugin_gateway_restart_uses_safe_native_helper_and_selected_profile(harness, monkeypatch):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
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
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
    async def status(): return {'pid': 123, 'running': False, 'exit_code': 0}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    monkeypatch.setattr(api, 'native_plugin_restart', lambda profile: {'restart_started': True, 'restart_pid': 123}, raising=False)
    response = client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={})
    assert response.status_code == 200
    assert response.json()['restart_started'] is False


LIVE_SESSIONS_REFUSAL = ('Refusing to restart the gateway: 2 live session(s) would be interrupted.\n'
                         '  Let them finish, or interrupt them deliberately:\n'
                         '    POST /api/gateway/restart?interrupt=true   # interrupts 2 live session(s)')


def _restart(harness, monkeypatch, native, before=None):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
    async def status(): return before or {'pid': None, 'running': False, 'exit_code': None}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    monkeypatch.setattr(api, 'native_plugin_restart', native, raising=False)
    return client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={})


def test_a_restart_hermes_refuses_for_live_sessions_says_so_and_keeps_its_text(harness, monkeypatch):
    response = _restart(harness, monkeypatch, lambda profile: {'restart_started': False, 'restart_error': LIVE_SESSIONS_REFUSAL})
    assert response.status_code == 200
    assert response.json() == {'restart_started': False, 'restart_error': LIVE_SESSIONS_REFUSAL, 'restart_error_code': 'live_sessions'}


@pytest.mark.parametrize('result', [{'restart_started': False, 'restart_error': 'spawn failed'},
                                    {'restart_started': False, 'restart_error': ''},
                                    {'restart_started': False}])
def test_any_other_refused_restart_is_a_failed_restart(harness, monkeypatch, result):
    response = _restart(harness, monkeypatch, lambda profile: dict(result))
    assert response.status_code == 200
    assert response.json() == {**result, 'restart_error_code': 'failed'}


def test_a_restart_that_is_only_an_earlier_one_is_stale_not_started(harness, monkeypatch):
    response = _restart(harness, monkeypatch, lambda profile: {'restart_started': True, 'restart_pid': 123, 'restart_action': 'gateway-restart'},
                        before={'pid': 123, 'running': False, 'exit_code': 0})
    assert response.status_code == 200
    assert response.json() == {'restart_started': False, 'restart_pid': 123, 'restart_action': 'gateway-restart', 'restart_error_code': 'stale'}


def test_a_started_restart_carries_no_error_code(harness, monkeypatch):
    response = _restart(harness, monkeypatch, lambda profile: {'restart_started': True, 'restart_pid': 124, 'restart_action': 'gateway-restart'},
                        before={'pid': 123, 'running': False, 'exit_code': 0})
    assert response.json() == {'restart_started': True, 'restart_pid': 124, 'restart_action': 'gateway-restart'}


def test_a_restart_hermes_cannot_start_still_answers_503_without_a_code(harness, monkeypatch):
    def broken(profile): raise RuntimeError('private detail')
    response = _restart(harness, monkeypatch, broken)
    assert response.status_code == 503
    assert response.json() == {'detail': 'Hermes could not start the gateway restart'}


def test_native_plugin_restart_is_hermes_own_forced_restart(monkeypatch):
    # Owner, 3 Nov: a restart asked for from the phone happens even while Hermes is busy. Hermes' own force path
    # (`hermes gateway restart --interrupt`, the one POST /api/gateway/restart?interrupt=true uses) does it; nothing native is patched.
    from types import SimpleNamespace
    calls = []

    def spawn(profile=None, *, interrupt=False):
        calls.append((profile, interrupt))
        return SimpleNamespace(pid=4321), False
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', SimpleNamespace(_spawn_gateway_restart=spawn))
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_gateway', SimpleNamespace(
        _restart_gateway_after=lambda profile, **kwargs: pytest.fail('The forced path is used when Hermes has it')))
    assert api.native_plugin_restart('work') == {'restart_started': True, 'restart_action': 'gateway-restart', 'restart_pid': 4321}
    assert calls == [('work', True)]


def test_native_plugin_restart_reports_a_failed_forced_spawn(monkeypatch):
    from types import SimpleNamespace

    def spawn(profile=None, *, interrupt=False):
        raise RuntimeError('gateway restart already in progress for another profile')
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', SimpleNamespace(_spawn_gateway_restart=spawn))
    assert api.native_plugin_restart('work') == {'restart_started': False, 'restart_error': 'gateway restart already in progress for another profile'}


def test_native_plugin_restart_on_a_hermes_without_the_force_path_restarts_as_before(monkeypatch):
    from types import SimpleNamespace
    calls = []
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', SimpleNamespace(_spawn_gateway_restart=lambda profile=None: pytest.fail('No interrupt here')))
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_gateway', SimpleNamespace(
        _restart_gateway_after=lambda profile, **kwargs: calls.append((profile, kwargs)) or {'restart_started': False}))
    assert api.native_plugin_restart('work') == {'restart_started': False}
    assert calls == [('work', {'what': 'Beacon plugin update', 'label': 'Beacon plugin update'})]


def test_plugin_gateway_restart_refuses_an_active_call_or_foreign_owner(harness, monkeypatch):
    from test_mobile import reserve
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
    monkeypatch.setattr(api, 'native_plugin_restart', lambda profile: pytest.fail('No restart before admission'), raising=False)
    call_id = reserve(client)
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 409
    with host.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 403
    client.headers.pop('x-fixture-owner')
    assert client.post('/api/plugins/beacon-restart-fixture/admin/plugin/restart', json={}).status_code == 401


def test_dashboard_restart_refuses_an_active_call_and_never_reaches_the_restarter(harness, monkeypatch):
    from test_mobile import reserve
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
    begun = []

    class Restarter:
        def begin(self, *args):
            begun.append(args)
            return {'restart_started': True}, None
    monkeypatch.setattr(api, 'dashboard_restarter', Restarter, raising=False)
    call_id = reserve(client)
    refused = client.post('/api/plugins/beacon-restart-fixture/admin/dashboard/restart', json={})
    assert refused.status_code == 409
    assert refused.json()['detail'] == 'End your call before restarting Hermes'
    assert begun == [], 'a restart with a call open would end the call'
    # The same request goes through once the call is over, so the refusal above is the guard and not a broken route.
    with host.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    allowed = client.post('/api/plugins/beacon-restart-fixture/admin/dashboard/restart', json={})
    assert allowed.status_code == 200 and allowed.json() == {'restart_started': True}
    assert len(begun) == 1


@pytest.mark.parametrize('pid,running,exit_code,completed', [(123, False, 0, True), (124, False, 0, False), (123, True, None, False), (123, False, 1, False), (None, False, None, False)])
def test_plugin_restart_completion_is_bound_to_the_actual_native_action(harness, monkeypatch, pid, running, exit_code, completed):
    host, client, _, _, app = harness
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-restart-fixture')
    async def status(): return {'pid': pid, 'running': running, 'exit_code': exit_code, 'lines': ['not exposed']}
    monkeypatch.setattr(api, 'native_plugin_restart_status', status, raising=False)
    response = client.get('/api/plugins/beacon-restart-fixture/admin/plugin/restart/status', params={'pid': 123})
    assert response.status_code == 200
    assert response.json()['completed'] is completed
    assert 'lines' not in response.json()

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
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-fixture')
    url = '/api/plugins/beacon-fixture/admin/model-capabilities'
    assert client.get(url, params={'model': 'fixture-model'}).status_code == 200
    assert seen == [('default', '', 'fixture-model')]
    assert client.get(url, params={'model': 'fixture-model', 'provider': 'bad provider'}).status_code == 400
    assert client.get(url, params={'model': 'fixture-model', 'profile': 'foreign'}).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url, params={'model': 'fixture-model'}).status_code == 401


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
    app.include_router(api.build_router(host.identity), prefix='/api/plugins/beacon-list-fixture')
    url = '/api/plugins/beacon-list-fixture/admin/bots'
    assert client.get(url).json() == {'bots': [{'profile': 'default', 'title': 'Owner bot', 'purpose': 'Personal tasks'}]}
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(url).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.get(url).status_code == 401


def test_update_in_progress_asks_hermes_for_its_marker_and_checkout_lock(monkeypatch):
    seen = []
    monkeypatch.setattr(api, '_native_update_in_progress', lambda: seen.append('asked') or True)
    assert api.update_in_progress() is True and seen == ['asked']
    monkeypatch.setattr(api, '_native_update_in_progress', None)
    assert api.update_in_progress() is False
