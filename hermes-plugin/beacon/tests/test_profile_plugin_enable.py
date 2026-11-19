"""Beacon on for every profile through Hermes' own enable code: POST /admin/profile-plugin/enable for ONE profile (the owner's Turn on, or the phone by itself
with `auto`), and admin_api.turn_on_for_profiles, the dashboard watcher's background pass over the owner's profiles.

Hermes' dashboard gates a plugin per profile (`web_server_dashboard._plugin_api_mount_skip_reason`: the profile's own plugins.enabled), the plugin's
`native_policy` runs that gate in the profile's scope, and a profile without it gets 403 "Plugin not authorized" on the gateway ticket. Hermes has no
profile-scoped enable route and its name resolver (`plugins_cmd._resolve_plugin_key`) only sees the active profile's own plugins, so the route resolves
the key where Beacon is installed (the dashboard's profile) and runs `plugins_cmd._activate_key` inside `_config_profile_scope(profile)`: Hermes writes the
selection, the plugin writes nothing. Hermes is faked here (never imported): the fakes record every call, and a name the route was not allowed to use does
not exist on them.
"""
import os
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path
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
        self.disabled = {name: set() for name in profiles}  # plugins.disabled, which Hermes' loader obeys over plugins.enabled
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
            if not enable or key in fake.enabled[profile] and key not in fake.disabled[profile]:
                return False
            if not fake.sticky:
                fake.enabled[profile].add(key)
                fake.disabled[profile].discard(key)  # Hermes' activation purges the key from the other list
            return True

        def get_enabled_set():
            fake.calls.append(('read', fake.scope[-1] if fake.scope else None))
            return set(fake.enabled[fake.scope[-1] if fake.scope else 'default'])

        def get_disabled_set():
            fake.calls.append(('read-disabled', fake.scope[-1] if fake.scope else None))
            return set(fake.disabled[fake.scope[-1] if fake.scope else 'default'])

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
        plugins_cmd._get_disabled_set = get_disabled_set
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
    monkeypatch.setattr(admin, 'PROFILES_REFUSED', {})  # a dashboard that just started
    monkeypatch.setattr(admin, 'PROFILES_RACED', {})
    monkeypatch.setattr(admin, '_profile_pass_fd', None)  # a process that has not run the pass yet
    yield api, client, cfg, FakeHermes(monkeypatch), admin
    if admin._profile_pass_fd is not None:  # the file lock this test's pass took
        os.close(admin._profile_pass_fd)


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
                                     {'profile': 'work/../default'}, {'profile': 'wo rk'}, {'auto': True}, {'profile': 'work', 'auto': 'yes'},
                                     {'profile': 'work', 'auto': 1}, {'profile': 'work', 'auto': None}, {'profile': 'work', 'auto': True, 'extra': 1}])
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


def test_a_second_request_while_one_runs_is_told_it_is_busy(scene, monkeypatch):
    _, client, _, hermes, admin = scene
    monkeypatch.setattr(admin, 'PROFILE_PLUGIN_WAIT', 0.05)
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
    monkeypatch.setattr(admin, 'PROFILE_PLUGIN_WAIT', 0)
    monkeypatch.setattr(admin, 'turn_on_for_profiles', lambda profiles: [])  # the live client starts the dashboard's watcher, whose own pass would hold the lock
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
    monkeypatch.setattr(scene[4], 'PROFILE_PLUGIN_WAIT', 0.05)


def _updating(scene, monkeypatch):
    monkeypatch.setattr(scene[4], 'update_in_progress', lambda: True)


def _too_slow(scene, monkeypatch):
    import time
    monkeypatch.setattr(scene[4], 'PROFILE_PLUGIN_SECONDS', 0.05)
    monkeypatch.setattr(scene[4], 'enable_plugin_for_profile', lambda *args: time.sleep(0.3))


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


# ---- the phone's own turn-on (auto) and an owner's off -------------------------------------------------------------------------------
# The app asks with `auto` by itself when Hermes refused Beacon for a profile, then runs the refused request again. A profile whose own config
# turned Beacon off (plugins.disabled) is the owner's choice: only the owner's own Turn on (no `auto`) changes it.

def test_the_phone_turns_beacon_on_by_itself_for_a_profile_without_it(scene):
    _, client, _, hermes, _ = scene
    response = client.post(URL, json={'profile': 'work', 'auto': True})
    assert response.status_code == 200
    assert response.json() == {'ok': True, 'profile': 'work', 'enabled': True, 'changed': True}
    assert 'beacon' in hermes.enabled['work']


def test_a_profile_that_turned_beacon_off_stays_off_when_the_phone_asks_by_itself(scene):
    _, client, _, hermes, _ = scene
    hermes.disabled['work'].add('beacon')
    response = client.post(URL, json={'profile': 'work', 'auto': True})
    assert response.status_code == 200
    assert response.json() == {'ok': True, 'profile': 'work', 'enabled': False, 'changed': False}
    assert hermes.activations() == [] and hermes.disabled['work'] == {'beacon'} and 'beacon' not in hermes.enabled['work']


def test_the_owners_own_turn_on_undoes_that_profiles_off(scene):
    _, client, _, hermes, _ = scene
    hermes.disabled['work'].add('beacon')
    response = client.post(URL, json={'profile': 'work'})
    assert response.json() == {'ok': True, 'profile': 'work', 'enabled': True, 'changed': True}
    assert 'beacon' in hermes.enabled['work'] and 'beacon' not in hermes.disabled['work']
    assert not any(call[0] == 'read-disabled' for call in hermes.calls), 'an explicit turn-on does not ask whether the profile turned it off'


def test_a_request_waits_for_a_turn_on_already_running_instead_of_answering_busy(scene):
    import time
    _, client, _, hermes, admin = scene
    assert admin.PROFILE_PLUGIN_LOCK.acquire(blocking=False)
    threading.Timer(0.2, admin.PROFILE_PLUGIN_LOCK.release).start()  # the background pass ends
    begin = time.monotonic()
    response = client.post(URL, json={'profile': 'work', 'auto': True})
    assert response.status_code == 200 and response.json()['changed'] is True
    assert time.monotonic() - begin >= 0.15
    assert not admin.PROFILE_PLUGIN_LOCK.locked()


# ---- every profile, without being asked ----------------------------------------------------------------------------------------------
# The dashboard's watcher runs admin_api.turn_on_for_profiles for the owner's profiles: at start (so after an install or an update) and once a
# minute after (a profile created later).

def test_the_background_pass_turns_beacon_on_for_every_authorised_profile_without_it(scene):
    _, _, _, hermes, admin = scene
    assert admin.turn_on_for_profiles(['default', 'work', 'coder']) == ['work', 'coder']
    assert all('beacon' in hermes.enabled[name] for name in ('default', 'work', 'coder'))
    assert [call[3] for call in hermes.activations()] == ['work', 'coder'], 'the profile that has Beacon is not touched'
    assert hermes.scope == [] and not admin.PROFILE_PLUGIN_LOCK.locked()


def test_a_pass_where_every_profile_has_beacon_resolves_and_writes_nothing(scene):
    _, _, _, hermes, admin = scene
    admin.turn_on_for_profiles(['default', 'work', 'coder'])
    hermes.calls.clear()
    assert admin.turn_on_for_profiles(['default', 'work', 'coder']) == []
    assert {call[0] for call in hermes.calls} <= {'scope', 'read', 'read-disabled'}, hermes.calls  # one cheap read per profile: no resolve, no activation


def test_the_background_pass_leaves_a_profile_that_turned_beacon_off(scene):
    _, _, _, hermes, admin = scene
    hermes.disabled['coder'].add('beacon')
    assert admin.turn_on_for_profiles(['default', 'work', 'coder']) == ['work']
    assert hermes.disabled['coder'] == {'beacon'} and 'beacon' not in hermes.enabled['coder']
    assert [call[3] for call in hermes.activations()] == ['work']


def test_the_background_pass_only_touches_the_profiles_it_is_given(scene):
    _, _, _, hermes, admin = scene
    assert admin.turn_on_for_profiles(['default', 'coder']) == ['coder']
    assert 'beacon' not in hermes.enabled['work']


def test_a_profile_created_later_is_turned_on_by_the_next_pass(scene):
    _, _, _, hermes, admin = scene
    admin.turn_on_for_profiles(['default', 'work', 'coder'])
    hermes.enabled['research'], hermes.disabled['research'] = set(), set()  # `hermes profile create research` since the last pass
    assert admin.turn_on_for_profiles(['default', 'work', 'coder', 'research']) == ['research']
    assert 'beacon' in hermes.enabled['research']


def test_one_profile_failing_never_stops_the_others_and_never_raises(scene, caplog):
    _, _, _, hermes, admin = scene
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key

    def activate(key, *, enable, console=None):
        if hermes.scope[-1] == 'work':
            raise RuntimeError('C:/private/path conflicts')
        return original(key, enable=enable)
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('WARNING'):
        assert admin.turn_on_for_profiles(['work', 'coder']) == ['coder']
    said = [record.getMessage() for record in caplog.records if 'Turning Beacon on' in record.getMessage()]
    assert len(said) == 1 and 'RuntimeError' in said[0] and 'private' not in said[0], said
    assert not admin.PROFILE_PLUGIN_LOCK.locked() and hermes.scope == []


def test_a_profile_hermes_no_longer_has_is_skipped_quietly(scene, caplog):
    _, _, _, hermes, admin = scene
    with caplog.at_level('WARNING'):
        assert admin.turn_on_for_profiles(['ghost', 'work']) == ['work']
    assert not [record for record in caplog.records if 'ghost' in record.getMessage()], 'a stale profile is no warning every minute'
    assert admin.PROFILES_REFUSED == {}, 'a profile Hermes no longer has is not a refusal'


def test_a_profile_hermes_refuses_is_parked_and_a_restart_clears_it(scene, caplog):
    """Review 121, P2: each try is a PM admission under PM's install lock, so a profile Hermes will not admit Beacon to is not asked again every minute."""
    _, _, _, hermes, admin = scene
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key
    asked = []

    def activate(key, *, enable, console=None):
        asked.append(hermes.scope[-1])
        if hermes.scope[-1] == 'work':
            raise RuntimeError('dependency conflict')
        return original(key, enable=enable)
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('WARNING'):
        assert admin.turn_on_for_profiles(['work', 'coder']) == ['coder']
        assert admin.turn_on_for_profiles(['work', 'coder']) == []
        assert admin.turn_on_for_profiles(['work', 'coder']) == []
    assert asked == ['work', 'coder'], 'work was asked once over three passes'
    said = [record.getMessage() for record in caplog.records if 'Turning Beacon on' in record.getMessage()]
    assert len(said) == 1 and 'work' in said[0] and 'an hour' in said[0], said
    # The dashboard restarts (every install and update does): the profile is tried again.
    admin.PROFILES_REFUSED.clear()
    sys.modules['hermes_cli.plugins_cmd']._activate_key = original
    assert admin.turn_on_for_profiles(['work', 'coder']) == ['work']


def test_a_busy_lock_is_not_a_refusal_and_the_next_pass_tries_again(scene, monkeypatch):
    _, _, _, hermes, admin = scene
    monkeypatch.setattr(admin, 'PROFILE_PLUGIN_SECONDS', 0.05)
    assert admin.PROFILE_PLUGIN_LOCK.acquire(blocking=False)
    try:
        assert admin.turn_on_for_profiles(['work']) == []
    finally:
        admin.PROFILE_PLUGIN_LOCK.release()
    assert admin.PROFILES_REFUSED == {}
    assert admin.turn_on_for_profiles(['work']) == ['work']


def test_a_parked_profile_is_tried_again_at_most_once_an_hour(scene, monkeypatch, caplog):
    """Review 121 round 2, P3: a passing failure (a PM worker or lock timeout) must not park a profile until the dashboard restarts. A parked profile is
    tried once more after an hour; a failure parks it again with one more log line; a success forgets it. (A race with another turn-on, PM's
    "configuration changed ... retry", is not parked at all: test_a_selection_that_changed_meanwhile_is_asked_again_by_the_next_pass_not_parked.)"""
    from types import SimpleNamespace
    _, _, _, hermes, admin = scene
    now = [1000.0]
    monkeypatch.setattr(admin, 'time', SimpleNamespace(monotonic=lambda: now[0]))
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key
    asked, refusing = [], [True]

    def activate(key, *, enable, console=None):
        asked.append(hermes.scope[-1])
        if refusing[0]:
            raise RuntimeError('worker exited without a result')  # a PM worker failure (pm/client.py raises InstallError for it)
        return original(key, enable=enable)
    monkeypatch.setattr(sys.modules['hermes_cli.plugins_cmd'], '_activate_key', activate)

    def warnings():
        return [record.getMessage() for record in caplog.records if 'Turning Beacon on' in record.getMessage()]
    with caplog.at_level('WARNING'):
        assert admin.turn_on_for_profiles(['work']) == [] and asked == ['work'] and len(warnings()) == 1
        # Within the hour: a pass a minute, none asks.
        for minutes in range(1, 60):
            now[0] = 1000.0 + minutes * 60
            assert admin.turn_on_for_profiles(['work']) == []
        assert asked == ['work'] and len(warnings()) == 1
        # An hour later: one more try. It fails again: parked again, one more line, and the next pass does not ask.
        now[0] = 1000.0 + admin.PROFILE_RETRY_SECONDS
        assert admin.turn_on_for_profiles(['work']) == [] and asked == ['work', 'work'] and len(warnings()) == 2
        now[0] += 60
        assert admin.turn_on_for_profiles(['work']) == [] and asked == ['work', 'work']
        # Another hour: Hermes admits it now. Turned on, and no longer parked.
        refusing[0] = False
        now[0] = 1000.0 + 2 * admin.PROFILE_RETRY_SECONDS + 60
        assert admin.turn_on_for_profiles(['work']) == ['work'] and asked == ['work', 'work', 'work']
    assert admin.PROFILES_REFUSED == {} and len(warnings()) == 2


def test_the_background_pass_does_nothing_while_hermes_updates(scene, monkeypatch):
    _, _, _, hermes, admin = scene
    monkeypatch.setattr(admin, 'update_in_progress', lambda: True)
    assert admin.turn_on_for_profiles(['work']) == [] and hermes.calls == []


def test_the_background_pass_on_a_hermes_without_the_names_does_nothing_and_never_raises(scene):
    _, _, _, hermes, admin = scene
    del sys.modules['hermes_cli.plugins_cmd']._get_disabled_set
    assert admin.turn_on_for_profiles(['work']) == [] and 'beacon' not in hermes.enabled['work']


def test_the_background_pass_waits_for_a_turn_on_already_running(scene):
    _, _, _, hermes, admin = scene
    assert admin.PROFILE_PLUGIN_LOCK.acquire(blocking=False)
    threading.Timer(0.2, admin.PROFILE_PLUGIN_LOCK.release).start()
    assert admin.turn_on_for_profiles(['work']) == ['work']
    assert not admin.PROFILE_PLUGIN_LOCK.locked()


@pytest.mark.asyncio
async def test_the_dashboard_watcher_runs_the_pass_at_start_off_the_event_loop_for_the_owners_profiles(scene, monkeypatch):
    import asyncio
    import time
    api, _, cfg, _, admin = scene
    cfg['owner_id'], cfg['owner_provider'] = 'owner', 'password'
    monkeypatch.setattr(api.mobile, 'settings', lambda profile=None: cfg)
    for name in ('watch', 'push', 'sweep_unended_calls', 'flush_held_posts', '_warm_profile'):
        monkeypatch.setattr(api, name, lambda *args: None)
    monkeypatch.setattr(api.voice_context, 'prefetch_tools', lambda profile: None)
    monkeypatch.setattr(api.mobile, 'check_release', lambda *args: None)
    passes, threads = [], []
    release = threading.Event()

    def turn_on(profiles):
        passes.append(list(profiles))
        threads.append(threading.current_thread() is threading.main_thread())
        release.wait(10)  # a slow pass never holds the watcher up, and a second one never starts beside it
        return []
    monkeypatch.setattr(admin, 'turn_on_for_profiles', turn_on)
    monkeypatch.setattr(admin, 'PROFILES_ON_SECONDS', 0)
    monkeypatch.setattr(api, '_profiles_on_next', 0.0)
    monkeypatch.setattr(api, '_profiles_on', None)
    await api.start_watcher()
    try:
        deadline = time.monotonic() + 3
        while not passes and time.monotonic() < deadline:
            await asyncio.sleep(0.02)
        await asyncio.sleep(3.3)  # one more watcher round while the first pass is still running
        assert passes == [['default', 'work', 'coder']], passes
        assert threads == [False], 'the pass runs in a worker thread'
        release.set()
        deadline = time.monotonic() + 5
        while len(passes) < 2 and time.monotonic() < deadline:
            await asyncio.sleep(0.05)
        assert len(passes) == 2, 'once the pass ends the next round runs another (a profile created later)'
    finally:
        release.set()
        await api.stop_watcher()


def test_register_never_turns_profiles_on():
    """Hermes drops a plugin whose import and register() take over 10 s, and register() runs in every Hermes process (the gateway too, where
    entering another profile's scope would switch it to multi-profile hosting): the pass belongs to the dashboard's watcher only."""
    from pathlib import Path
    root = Path(__file__).resolve().parents[1]
    init = (root / '__init__.py').read_text(encoding='utf-8-sig')
    assert 'turn_on_for_profiles' not in init and 'enable_plugin_for_profile' not in init
    watcher = (root / 'dashboard' / 'plugin_api.py').read_text(encoding='utf-8-sig')
    assert 'asyncio.to_thread(admin.turn_on_for_profiles' in watcher


# ---- the race seen on the device round (calls-121 DEVICE-ROUND-1 item 6) ------------------------------------------------------------
# The pass started PM's sync for a new profile; the phone's own turn-on wrote that profile's config meanwhile; PM refused the pass's stale
# selection ("plugin configuration changed while preparing publication; retry", wrapped by Hermes in a plain AdmissionRefused) and the pass
# parked the profile for an hour with a warning, although Beacon was on.

class AdmissionRefused(RuntimeError):
    """Hermes' plugins_admission.AdmissionRefused, as the pass sees it: the PM worker's ValueError is its cause."""


def _refused(message):
    error = AdmissionRefused(message)
    error.__cause__ = ValueError(message)
    return error


def test_a_turn_on_that_lost_the_race_to_another_turn_on_is_done_without_a_warning(scene, caplog):
    _, _, _, hermes, admin = scene
    asked = []

    def activate(key, *, enable, console=None):
        asked.append(hermes.scope[-1])
        hermes.enabled['work'].add('beacon')  # the phone's turn-on wrote the config while PM worked on the pass's selection
        raise _refused('plugin configuration changed while preparing publication; retry')
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('INFO'):
        assert admin.turn_on_for_profiles(['work']) == []
    assert admin.PROFILES_REFUSED == {}, 'Beacon is on there: nothing to park'
    assert not [r for r in caplog.records if r.levelname == 'WARNING'], [r.getMessage() for r in caplog.records]
    # The next pass finds it on and asks nothing.
    assert admin.turn_on_for_profiles(['work']) == [] and asked == ['work']


def test_a_selection_that_changed_meanwhile_is_asked_again_by_the_next_pass_not_parked(scene, caplog):
    _, _, _, hermes, admin = scene
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key
    asked, racing = [], [True]

    def activate(key, *, enable, console=None):
        asked.append(hermes.scope[-1])
        if racing[0]:  # another write of that profile's config, not Beacon's: still off afterwards
            racing[0] = False
            raise _refused('Plugin configuration changed since this selection was read; retry.')
        return original(key, enable=enable)
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('INFO'):
        assert admin.turn_on_for_profiles(['work']) == []
        assert admin.PROFILES_REFUSED == {}
        assert not [r for r in caplog.records if r.levelname == 'WARNING']
        # The very next pass (no hour waited) asks again, and Hermes admits it.
        assert admin.turn_on_for_profiles(['work']) == ['work']
    assert asked == ['work', 'work'] and 'beacon' in hermes.enabled['work']
    said = [r.getMessage() for r in caplog.records if 'concurrent change' in r.getMessage()]
    assert len(said) == 1 and 'work' in said[0], said


@pytest.mark.parametrize('error', [
    _refused("Plugin 'beacon' conflicts with the dependencies pinned by Hermes core or an enabled plugin, so it was not admitted. Resolver: x"),
    RuntimeError('Hermes did not record the selection'),
    AdmissionRefused('enable refused: manifest requires a newer Hermes'),
])
def test_a_real_refusal_is_still_parked_for_an_hour_with_one_warning(scene, caplog, error):
    _, _, _, hermes, admin = scene

    def activate(key, *, enable, console=None):
        raise error
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('WARNING'):
        assert admin.turn_on_for_profiles(['work']) == []
    assert 'work' in admin.PROFILES_REFUSED
    said = [r.getMessage() for r in caplog.records if 'Turning Beacon on' in r.getMessage()]
    assert len(said) == 1 and 'an hour' in said[0], said


def test_a_dependency_conflict_is_a_refusal_whatever_its_resolver_text_says(scene):
    _, _, _, hermes, admin = scene

    class DependencyConflict(AdmissionRefused):
        pass
    error = DependencyConflict('conflict')
    error.__cause__ = ValueError('lock changed upstream; retry')
    assert admin._changed_meanwhile(error) is False
    assert admin._changed_meanwhile(_refused('plugin configuration changed while preparing publication; retry')) is True
    assert admin._changed_meanwhile(RuntimeError('retry')) is False


# ---- REVIEW-E3 finding 8: a race that keeps recurring -----------------------------------------------------------------------------------

def test_a_race_that_recurs_three_passes_in_a_row_is_parked(scene, caplog):
    """A recognised race is asked again on the next pass, but another writer touching that profile's config every minute would hold PM's install
    lock ~18 s every minute without end: after three races in a row the profile is parked like a refusal, with one warning."""
    _, _, _, hermes, admin = scene
    asked = []

    def activate(key, *, enable, console=None):
        asked.append(hermes.scope[-1])
        raise _refused('Plugin configuration changed since this selection was read; retry.')
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    with caplog.at_level('INFO'):
        for _ in range(4):
            assert admin.turn_on_for_profiles(['work']) == []
    assert asked == ['work', 'work', 'work'], 'asked on three passes, then parked'
    assert 'work' in admin.PROFILES_REFUSED and admin.PROFILES_RACED == {}
    warned = [r.getMessage() for r in caplog.records if r.levelname == 'WARNING']
    assert len(warned) == 1 and 'work' in warned[0] and '3 times in a row' in warned[0], warned


def test_a_race_between_successes_does_not_add_up(scene):
    _, _, _, hermes, admin = scene
    original = sys.modules['hermes_cli.plugins_cmd']._activate_key
    racing = [True, True]

    def activate(key, *, enable, console=None):
        if racing and racing.pop():
            raise _refused('plugin configuration changed while preparing publication; retry')
        return original(key, enable=enable)
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    assert admin.turn_on_for_profiles(['work']) == [] and admin.PROFILES_RACED == {'work': 1}
    assert admin.turn_on_for_profiles(['work']) == [] and admin.PROFILES_RACED == {'work': 2}
    assert admin.turn_on_for_profiles(['work']) == ['work'], 'the third try got through'
    assert admin.PROFILES_RACED == {} and admin.PROFILES_REFUSED == {}


# ---- REVIEW-E3 finding 7: two processes serve Beacon on one Hermes home -------------------------------------------------------------------
# The dashboard and the desktop GUI backend each mount Beacon's API and start its watcher, each with its own in-process PROFILE_PLUGIN_LOCK. Only
# the process holding the pass's file lock (PM's own byte lock, next to the plugin's database) runs the pass; the OS releases it when that process
# ends. The other process is a real second Python process here.

HOLDER = """import sys, time
from pathlib import Path
from pm.filesystem import lock_path
fd = lock_path(Path(sys.argv[1]), timeout=10)
print('held' if fd is not None else 'none', flush=True)
time.sleep(120)
"""


def _hold_pass_lock(admin):
    path = Path(sys.modules['beacon_mobile']._protected_root()) / admin.PROFILE_PASS_LOCK
    holder = subprocess.Popen([sys.executable, '-c', HOLDER, str(path)], stdout=subprocess.PIPE, text=True)
    assert holder.stdout.readline().strip() == 'held'
    return holder


def test_only_the_process_that_holds_the_pass_lock_runs_the_pass_and_its_end_hands_it_over(scene):
    _, _, _, hermes, admin = scene
    holder = _hold_pass_lock(admin)  # the other process (the GUI backend) runs the pass
    try:
        assert admin.turn_on_for_profiles(['default', 'work']) == []
        assert hermes.calls == [], 'this process asked Hermes nothing: no second PM sync'
        assert admin._profile_pass_fd is None
    finally:
        holder.kill()
        holder.wait(timeout=30)
    # That process ended: the OS released its lock and this process's next pass takes it over.
    deadline = time.monotonic() + 10
    while admin.turn_on_for_profiles(['default', 'work']) != ['work'] and time.monotonic() < deadline:
        time.sleep(0.2)
    assert 'beacon' in hermes.enabled['work'] and admin._profile_pass_fd is not None


def test_the_process_that_runs_the_pass_keeps_its_lock_and_the_other_one_never_runs(scene):
    _, _, _, hermes, admin = scene
    assert admin.turn_on_for_profiles(['default', 'work']) == ['work']
    fd = admin._profile_pass_fd
    assert fd is not None
    # A second process on the same home cannot take it while this one lives.
    path = Path(sys.modules['beacon_mobile']._protected_root()) / admin.PROFILE_PASS_LOCK
    probe = subprocess.run([sys.executable, '-c', 'import sys; from pathlib import Path; from pm.filesystem import lock_path; '
                            'print("held" if lock_path(Path(sys.argv[1]), timeout=0) is not None else "none")', str(path)],
                           capture_output=True, text=True, timeout=60)
    assert probe.stdout.strip() == 'none', probe.stdout + probe.stderr
    assert admin.turn_on_for_profiles(['default', 'work', 'coder']) == ['coder'] and admin._profile_pass_fd == fd


# ---- REVIEW-E3-2: the pass never raises when Beacon's data folder is unusable; the route answers as done when it lost the race --------

def test_a_data_folder_the_pass_lock_cannot_use_runs_the_pass_as_before(scene, monkeypatch):
    """protect() refuses a data folder with links (RuntimeError) or fails (OSError): the pass runs without its file lock, it never raises."""
    _, _, _, hermes, admin = scene
    for error in (RuntimeError('Private path cannot contain links'), OSError('synthetic')):
        def broken():
            raise error
        monkeypatch.setattr(sys.modules['beacon_mobile'], '_protected_root', broken)
        hermes.enabled['work'].discard('beacon')
        assert admin.turn_on_for_profiles(['work']) == ['work']
        assert admin._profile_pass_fd is None


def test_the_route_answers_done_when_another_turn_on_won_the_race(scene):
    """PM refused the route's selection because another turn-on (the other process's pass, a second tap) changed it first, and Beacon is on now:
    the owner's turn-on is done, not a failure."""
    _, client, _, hermes, admin = scene

    def activate(key, *, enable, console=None):
        hermes.enabled['work'].add('beacon')  # the other turn-on published first
        raise _refused('plugin configuration changed while preparing publication; retry')
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    response = client.post(URL, json={'profile': 'work'})
    assert response.status_code == 200, response.text
    assert response.json() == {'ok': True, 'profile': 'work', 'enabled': True, 'changed': False}


def test_the_route_still_answers_500_when_beacon_is_not_on_afterwards(scene):
    _, client, _, hermes, admin = scene

    def activate(key, *, enable, console=None):
        raise _refused('Plugin configuration changed since this selection was read; retry.')
    sys.modules['hermes_cli.plugins_cmd']._activate_key = activate
    assert client.post(URL, json={'profile': 'work'}).status_code == 500
