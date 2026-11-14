"""POST /admin/profile-plugin/enable: the owner turns Beacon on for ONE profile through Hermes' own enable code.

Hermes' dashboard gates a plugin per profile (`web_server_dashboard._plugin_api_mount_skip_reason`: the profile's own plugins.enabled), the plugin's
`native_policy` runs that gate in the profile's scope, and a profile without it gets 403 "Plugin not authorized" on the gateway ticket. Hermes has no
profile-scoped enable route and its name resolver (`plugins_cmd._resolve_plugin_key`) only sees the active profile's own plugins, so the route resolves
the key where Beacon is installed (the dashboard's profile) and runs `plugins_cmd._activate_key` inside `_config_profile_scope(profile)`: Hermes writes the
selection, the plugin writes nothing. Hermes is faked here (never imported): the fakes record every call, and a name the route was not allowed to use does
not exist on them.
"""
import sys
import threading
from contextlib import contextmanager
from types import ModuleType

import pytest
from fastapi import HTTPException

from test_mobile import harness

URL = '/api/plugins/beacon/admin/profile-plugin/enable'


class FakeHermes:
    """Per-profile `plugins.enabled` sets, the active scope, and a log of every Hermes call the route makes."""

    def __init__(self, monkeypatch, profiles=('default', 'work', 'coder'), key='beacon'):
        self.enabled = {name: set() for name in profiles}
        self.enabled['default'].add(key)
        self.other_keys = {name: {'model': 'm-' + name, 'plugins.disabled': []} for name in profiles}
        self.scope = []
        self.calls = []
        self.key = key
        self.refuse = None
        self.sticky = False  # Hermes answered True but the selection did not change
        fake = self

        def resolve(name):
            fake.calls.append(('resolve', name, fake.scope[-1] if fake.scope else None))
            return fake.key if name == 'beacon' and fake.scope == [] else None  # only the dashboard's own profile has Beacon installed

        def activate(key, *, enable, console=None):
            fake.calls.append(('activate', key, enable, fake.scope[-1] if fake.scope else None))
            if fake.refuse is not None:
                raise fake.refuse
            profile = fake.scope[-1] if fake.scope else 'default'
            if not enable or key in fake.enabled[profile]:
                return False
            if not fake.sticky:
                fake.enabled[profile].add(key)
            return True

        def get_enabled_set():
            fake.calls.append(('read', fake.scope[-1] if fake.scope else None))
            return set(fake.enabled[fake.scope[-1] if fake.scope else 'default'])

        @contextmanager
        def profile_scope(profile):
            fake.calls.append(('scope', profile))
            if profile not in fake.enabled:
                raise HTTPException(404, f"Profile '{profile}' does not exist.")
            fake.scope.append(profile)
            try:
                yield None
            finally:
                fake.scope.pop()

        package = ModuleType('hermes_cli')
        package.__path__ = []
        plugins_cmd = ModuleType('hermes_cli.plugins_cmd')
        plugins_cmd._resolve_plugin_key = resolve
        plugins_cmd._activate_key = activate
        plugins_cmd._get_enabled_set = get_enabled_set
        profiles_module = ModuleType('hermes_cli.web_server_profiles')
        profiles_module._config_profile_scope = profile_scope
        package.plugins_cmd = plugins_cmd
        package.web_server_profiles = profiles_module
        monkeypatch.setitem(sys.modules, 'hermes_cli', package)
        monkeypatch.setitem(sys.modules, 'hermes_cli.plugins_cmd', plugins_cmd)
        monkeypatch.setitem(sys.modules, 'hermes_cli.web_server_profiles', profiles_module)

    def activations(self):
        return [call for call in self.calls if call[0] == 'activate']


@pytest.fixture
def scene(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    cfg['profiles'] = ['default', 'work', 'coder']
    admin = sys.modules['beacon_admin_api']
    monkeypatch.setattr(admin, 'update_in_progress', lambda: False)
    return api, client, cfg, FakeHermes(monkeypatch), admin


def test_the_owner_turns_beacon_on_for_one_profile_through_hermes_own_activation(scene):
    _, client, _, hermes, _ = scene
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 200
    assert response.json() == {'ok': True, 'profile': 'work', 'enabled': True, 'changed': True}
    assert 'beacon' in hermes.enabled['work']
    # The key is resolved where Beacon is installed (no profile scope), the enable runs in the one profile's scope, and the result is read back there.
    assert hermes.calls == [('resolve', 'beacon', None), ('scope', 'work'), ('activate', 'beacon', True, 'work'), ('read', 'work')]
    assert hermes.scope == []


def test_only_that_profiles_enabled_list_changes(scene):
    _, client, _, hermes, _ = scene
    before = {name: set(values) for name, values in hermes.enabled.items()}
    others = {name: dict(values) for name, values in hermes.other_keys.items()}
    client.post(URL, json={'profile': 'work'})
    assert hermes.enabled['default'] == before['default'] and hermes.enabled['coder'] == before['coder']
    assert hermes.enabled['work'] == before['work'] | {'beacon'}
    assert hermes.other_keys == others  # no other key of any profile is touched
    assert [call[0] for call in hermes.calls] == ['resolve', 'scope', 'activate', 'read']  # nothing else of Hermes was used


def test_turning_it_on_twice_is_a_no_op_the_second_time(scene):
    _, client, _, hermes, _ = scene
    assert client.post(URL, json={'profile': 'work'}).json()['changed'] is True
    again = client.post(URL, json={'profile': 'work'})
    assert again.status_code == 200
    assert again.json() == {'ok': True, 'profile': 'work', 'enabled': True, 'changed': False}
    assert hermes.enabled['work'] == {'beacon'}


def test_the_dashboards_own_profile_is_already_on_and_stays_so(scene):
    _, client, _, hermes, _ = scene
    assert client.post(URL, json={'profile': 'default'}).json() == {'ok': True, 'profile': 'default', 'enabled': True, 'changed': False}


def test_a_profile_outside_the_owners_list_is_refused_before_hermes_is_asked(scene):
    _, client, cfg, hermes, _ = scene
    cfg['profiles'] = ['default']
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 403
    assert hermes.calls == [] and 'beacon' not in hermes.enabled['work']


def test_a_profile_hermes_does_not_have_is_refused_and_nothing_changes(scene):
    _, client, cfg, hermes, _ = scene
    cfg['profiles'] = ['default', 'work', 'ghost']
    response = client.post(URL, json={'profile': 'ghost'})
    assert response.status_code == 422 and response.json()['detail'] == 'Unknown profile'  # a 404 can only mean the route is missing
    assert hermes.activations() == []
    assert all(values == ({'beacon'} if name == 'default' else set()) for name, values in hermes.enabled.items())


@pytest.mark.parametrize('payload', [{}, {'profile': ''}, {'profile': 7}, {'profile': ['work']}, {'profile': 'work', 'extra': 1}, {'profile': 'x' * 81},
                                     {'profile': 'work/../default'}, {'profile': 'wo rk'}])
def test_only_exactly_one_plain_profile_name_is_accepted(scene, payload):
    _, client, _, hermes, _ = scene
    assert client.post(URL, json=payload).status_code in (400, 403)
    assert hermes.calls == []


def test_a_huge_body_is_refused(scene):
    _, client, _, hermes, _ = scene
    assert client.post(URL, content=b'{"profile":"' + b'a' * 4000 + b'"}', headers={'content-type': 'application/json'}).status_code in (400, 413)
    assert hermes.calls == []


def test_only_the_owner_may_turn_it_on(scene):
    _, client, _, hermes, _ = scene
    client.headers['x-fixture-owner'] = 'someone-else'
    assert client.post(URL, json={'profile': 'work'}).status_code == 403
    del client.headers['x-fixture-owner']
    assert client.post(URL, json={'profile': 'work'}).status_code == 401
    assert hermes.calls == [] and 'beacon' not in hermes.enabled['work']


def test_a_hermes_without_the_enable_names_says_so_instead_of_failing_blind(scene, monkeypatch):
    _, client, _, hermes, _ = scene
    del sys.modules['hermes_cli.plugins_cmd']._activate_key
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 501
    assert 'Beacon update' in response.json()['detail']
    assert 'beacon' not in hermes.enabled['work']


def test_a_hermes_without_the_scope_helper_says_so_too(scene):
    _, client, _, hermes, _ = scene
    del sys.modules['hermes_cli.web_server_profiles']._config_profile_scope
    assert client.post(URL, json={'profile': 'work'}).status_code == 501


def test_beacon_not_installed_in_the_dashboard_profile_is_a_clear_refusal(scene):
    _, client, _, hermes, _ = scene
    hermes.key = None
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 501 and hermes.activations() == []


def test_a_refusal_from_hermes_is_a_failure_without_its_private_detail(scene):
    _, client, _, hermes, _ = scene
    hermes.refuse = RuntimeError('C:/private/path conflicts with pinned dependencies')
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 500
    assert 'private' not in response.text and 'C:/' not in response.text
    assert 'beacon' not in hermes.enabled['work'] and hermes.scope == []


def test_a_change_hermes_reports_but_did_not_make_is_not_reported_as_done(scene):
    _, client, _, hermes, _ = scene
    hermes.sticky = True
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 500 and 'beacon' not in hermes.enabled['work']


def test_nothing_is_turned_on_while_hermes_is_updating(scene, monkeypatch):
    _, client, _, hermes, admin = scene
    monkeypatch.setattr(admin, 'update_in_progress', lambda: True)
    assert client.post(URL, json={'profile': 'work'}).status_code == 503
    assert hermes.calls == []


def test_a_second_request_while_one_runs_is_told_it_is_busy(scene):
    _, client, _, hermes, admin = scene
    assert admin.PROFILE_PLUGIN_LOCK.acquire(blocking=False)
    try:
        response = client.post(URL, json={'profile': 'work'})
    finally:
        admin.PROFILE_PLUGIN_LOCK.release()
    assert response.status_code == 409 and hermes.calls == []
    assert client.post(URL, json={'profile': 'work'}).status_code == 200  # the lock was not left held


def test_the_route_runs_off_the_event_loop(scene):
    _, client, _, hermes, _ = scene
    seen = []
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key
    sys.modules['hermes_cli.plugins_cmd']._activate_key = lambda key, *, enable, console=None: seen.append(threading.current_thread().name) or original(key, enable=enable)
    assert client.post(URL, json={'profile': 'work'}).status_code == 200
    # asyncio.to_thread runs on the loop's default executor, whose workers are named asyncio_N; a blocking Hermes call on the loop thread would not be.
    assert seen and seen[0].startswith('asyncio_'), seen


@pytest.mark.parametrize('where', ['resolve', 'activate'])
@pytest.mark.parametrize('error', [KeyError('beacon'), IndexError('empty')])
def test_a_lookup_error_from_inside_hermes_is_a_failure_not_a_missing_install(scene, caplog, where, error):
    # KeyError and IndexError are LookupErrors too; only Beacon's own "no such plugin here" may answer "not installed".
    _, client, _, hermes, _ = scene
    commands = sys.modules['hermes_cli.plugins_cmd']
    if where == 'resolve':
        commands._resolve_plugin_key = lambda name: (_ for _ in ()).throw(error)
    else:
        hermes.refuse = error
    with caplog.at_level('WARNING'):
        response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 500, response.text
    assert 'not installed' not in response.text and 'beacon' not in hermes.enabled['work']
    assert any(type(error).__name__ in record.getMessage() for record in caplog.records), 'the failure is logged by its class name'


def test_beacon_missing_from_the_dashboard_profile_is_a_cannot_do_it_answer_not_a_wait(scene):
    _, client, _, hermes, admin = scene
    hermes.key = None
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 501 and response.json()['detail'] == 'Beacon is not installed in the dashboard profile'
    assert not admin.PROFILE_PLUGIN_LOCK.locked(), 'the lock is released after the refusal'


def test_a_turn_on_that_takes_too_long_is_told_to_wait_and_a_retry_is_busy_until_the_worker_ends(scene, monkeypatch):
    from starlette.testclient import TestClient
    import time
    _, client, _, hermes, admin = scene
    monkeypatch.setattr(admin, 'PROFILE_PLUGIN_SECONDS', 0.05)
    release, started, starts = threading.Event(), threading.Event(), []
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key

    def slow(key, *, enable, console=None):
        starts.append(key)
        started.set()
        release.wait(10)
        return original(key, enable=enable)
    sys.modules['hermes_cli.plugins_cmd']._activate_key = slow
    # One client with a persistent event loop: the plain fixture client closes its loop after each request, which waits for the worker to end.
    with TestClient(client.app, headers={'x-fixture-owner': 'owner'}) as live:
        try:
            first = live.post(URL, json={'profile': 'work'})
            assert started.is_set()
            assert first.status_code == 504 and 'taking longer' in first.json()['detail']
            retry = live.post(URL, json={'profile': 'work'})
            assert retry.status_code == 409 and 'Another change' in retry.json()['detail']
            assert starts == ['beacon'], 'the retry did not start a second activation'
        finally:
            release.set()
        deadline = time.monotonic() + 10
        while admin.PROFILE_PLUGIN_LOCK.locked() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert not admin.PROFILE_PLUGIN_LOCK.locked(), 'the lock is released when the worker ends'
        assert live.post(URL, json={'profile': 'work'}).status_code == 200
    assert hermes.enabled['work'] == {'beacon'}


# ---- one status, one meaning --------------------------------------------------------------------------------------------------------
# The app maps 404/501 to "run this on the PC", 409/503/504 to "Hermes is still working, check again" and the rest to a plain failure, so a
# status that meant two different things would leave a person on "still working" with nothing to do.

def _hermes_scope_that_raises(status):
    @contextmanager
    def scope(profile):
        raise HTTPException(status, 'C:/private/path says no')
        yield  # pragma: no cover
    return scope


def _use_scope(status):
    def arrange(scene, monkeypatch):
        sys.modules['hermes_cli.web_server_profiles']._config_profile_scope = _hermes_scope_that_raises(status)
    return arrange


def _owner_list(*names):
    def arrange(scene, monkeypatch):
        scene[2]['profiles'] = list(names)
    return arrange


def _hermes_raises(error):
    def arrange(scene, monkeypatch):
        scene[3].refuse = error
    return arrange


def _lock_held(scene, monkeypatch):
    held = threading.Lock()
    held.acquire()
    monkeypatch.setattr(scene[4], 'PROFILE_PLUGIN_LOCK', held)


def _updating(scene, monkeypatch):
    monkeypatch.setattr(scene[4], 'update_in_progress', lambda: True)


def _too_slow(scene, monkeypatch):
    import time
    monkeypatch.setattr(scene[4], 'PROFILE_PLUGIN_SECONDS', 0.05)
    monkeypatch.setattr(scene[4], 'enable_plugin_for_profile', lambda profile: time.sleep(0.3))


def _no_beacon_here(scene, monkeypatch):
    scene[3].key = None


def _no_enable_names(scene, monkeypatch):
    del sys.modules['hermes_cli.plugins_cmd']._activate_key


def _sticky(scene, monkeypatch):
    scene[3].sticky = True


def _nothing(scene, monkeypatch):
    pass


FAILED = 'Hermes could not turn Beacon on for this profile'
# cause -> (what goes wrong, request body, status, detail)
CAUSES = {
    'no single profile chosen': (_nothing, {}, 400, 'Choose one profile'),
    'profile outside the owner list': (_owner_list('default'), {'profile': 'work'}, 403, 'Profile not authorized'),
    'Hermes does not know the profile (its 404)': (_owner_list('default', 'ghost'), {'profile': 'ghost'}, 422, 'Unknown profile'),
    'Hermes refuses the profile name (its 400)': (_use_scope(400), {'profile': 'work'}, 422, 'Unknown profile'),
    'Hermes fails with another HTTP status': (_use_scope(502), {'profile': 'work'}, 500, FAILED),
    'Hermes raises': (_hermes_raises(RuntimeError('C:/private/path conflicts')), {'profile': 'work'}, 500, FAILED),
    'a lookup error inside Hermes': (_hermes_raises(KeyError('beacon')), {'profile': 'work'}, 500, FAILED),
    'Hermes reports a change it did not make': (_sticky, {'profile': 'work'}, 500, FAILED),
    'Beacon not installed in the dashboard profile': (_no_beacon_here, {'profile': 'work'}, 501, 'Beacon is not installed in the dashboard profile'),
    'Hermes without the enable names': (_no_enable_names, {'profile': 'work'}, 501, 'Turning Beacon on for a profile needs a Beacon update for this Hermes'),
    'another turn-on is running': (_lock_held, {'profile': 'work'}, 409, 'Another change is running. Try again in a moment.'),
    'Hermes is updating': (_updating, {'profile': 'work'}, 503, 'Update in progress'),
    'it takes too long': (_too_slow, {'profile': 'work'}, 504, 'Turning Beacon on is taking longer than expected. Check again in a minute.'),
}


@pytest.mark.parametrize('cause', list(CAUSES))
def test_each_cause_answers_its_own_status_and_text(scene, monkeypatch, cause):
    arrange, payload, status, detail = CAUSES[cause]
    arrange(scene, monkeypatch)
    response = scene[1].post(URL, json=payload)
    assert (response.status_code, response.json()['detail']) == (status, detail)
    assert 'private' not in response.text and 'C:/' not in response.text


def test_409_and_503_each_have_exactly_one_cause_and_404_means_the_route_is_missing():
    by_status = {}
    for cause, (_, _, status, _) in CAUSES.items():
        by_status.setdefault(status, []).append(cause)
    assert by_status[409] == ['another turn-on is running']
    assert by_status[503] == ['Hermes is updating']
    assert 404 not in by_status and 200 not in by_status
    assert sorted(by_status) == [400, 403, 409, 422, 500, 501, 503, 504]


def test_the_route_source_raises_each_status_for_the_causes_above_and_nothing_else(scene):
    import ast
    from collections import Counter
    from pathlib import Path
    admin = scene[4]
    route = next(node for node in ast.walk(ast.parse(Path(admin.__file__).read_text(encoding='utf-8-sig')))
                 if isinstance(node, ast.AsyncFunctionDef) and node.name == 'enable_profile_plugin')
    raised = Counter(call.args[0].value for call in ast.walk(route) if isinstance(call, ast.Call) and getattr(call.func, 'id', '') == 'HTTPException'
                     and call.args and isinstance(call.args[0], ast.Constant))
    assert raised == Counter({400: 1, 403: 1, 409: 1, 422: 1, 500: 2, 501: 2, 503: 1, 504: 1}), raised


def test_a_failure_status_from_hermes_is_logged_without_its_text(scene, caplog):
    sys.modules['hermes_cli.web_server_profiles']._config_profile_scope = _hermes_scope_that_raises(502)
    with caplog.at_level('WARNING'):
        assert scene[1].post(URL, json={'profile': 'work'}).status_code == 500
    said = [record.getMessage() for record in caplog.records if 'Turning Beacon on' in record.getMessage()]
    assert len(said) == 1 and '502' in said[0] and 'private' not in said[0], said


def test_the_readme_names_each_status_the_enable_route_answers():
    from pathlib import Path
    readme = (Path(__file__).resolve().parents[1] / 'README.md').read_text(encoding='utf-8')
    paragraph = next(line for line in readme.splitlines() if line.startswith('POST `/admin/profile-plugin/enable`'))
    for status in ('400', '403', '409', '422', '500', '501', '503', '504'):
        assert status in paragraph, status
