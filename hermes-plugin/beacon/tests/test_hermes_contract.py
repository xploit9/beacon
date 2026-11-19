"""Everything Beacon uses from Hermes exists in the Hermes under test (HERMES_AGENT_SOURCE), read from source.

The plugin's needs come from its own imports and attribute uses (compat.py); the app's from its native method
names and HTTP addresses. A Hermes release that renames any of them fails here first, by name.

shortcut: addresses are compared by path, not HTTP verb (the app often picks the verb at run time); add verbs if Hermes changes one.
"""
import importlib.util
import os
import re
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
APP = PLUGIN.parents[1] / 'beacon-android' / 'app' / 'src' / 'main' / 'java'


def _compat():
    spec = importlib.util.spec_from_file_location('beacon_compat_under_test', PLUGIN / 'compat.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture(scope='module')
def hermes():
    location = os.environ.get('HERMES_AGENT_SOURCE')
    if not location:
        pytest.skip('Set HERMES_AGENT_SOURCE to check against a Hermes source tree')
    return Path(location)


@pytest.fixture(scope='module')
def app_source():
    if not APP.is_dir():
        pytest.skip('No Beacon app source beside this plugin')
    return '\n'.join(p.read_text(encoding='utf-8') for p in sorted(APP.rglob('*.kt')))


def _routes(files, prefix=''):
    found = set()
    for path in files:
        text = path.read_text(encoding='utf-8-sig', errors='replace')
        found |= {prefix + r for r in re.findall(r'@\w+\.(?:get|post|put|patch|delete|api_route|websocket)\(\s*[\'"]([^\'"]+)[\'"]', text)}
    return found


def _beacon_routes():
    """The plugin's own routes as the dashboard mounts them, with each sub-router's include prefix."""
    mount = (PLUGIN / 'dashboard' / 'plugin_api.py').read_text(encoding='utf-8')
    prefixes = dict(re.findall(r"include_router\(sys\.modules\['beacon_(\w+)'\][^\n]*?prefix='([^']+)'", mount))
    return set().union(*(_routes([p], '/api/plugins/beacon' + prefixes.get(p.stem, ''))
                         for p in PLUGIN.rglob('*.py') if 'tests' not in p.relative_to(PLUGIN).parts))


def _matches(path, routes):
    """An app part built at run time ($x, ${…}) or a route parameter ({x}) matches any segment; an address the app
    extends at run time (a trailing '/' or a built last part) matches the routes below it."""
    parts = path.split('?')[0].rstrip('/').split('/')
    open_ended = path.endswith('/') or '$' in parts[-1]
    for route in routes:
        segments = route.rstrip('/').split('/')
        if len(segments) < len(parts) or len(segments) > len(parts) and not open_ended:
            continue
        if all('$' in p or s.startswith('{') or p == s for p, s in zip(parts, segments)):
            return True
    return False


def test_every_hermes_name_the_plugin_uses_exists(hermes):
    compat = _compat()
    assert compat.missing(compat.needs(), hermes) == []


def test_hermes_still_words_its_live_session_refusal_the_way_the_restart_code_reads_it(hermes):
    # admin_api.restart_error_code tells `live_sessions` from `failed` by this wording; a Hermes that rewords it needs a new match.
    guards = hermes.joinpath('hermes_cli', 'gateway_lifecycle_guards.py').read_text(encoding='utf-8-sig')
    assert 'live session' in guards
    assert "'restart_error'" in hermes.joinpath('hermes_cli', 'web_server_gateway.py').read_text(encoding='utf-8-sig').replace('"', "'")


def test_hermes_still_offers_its_forced_gateway_restart(hermes):
    # admin_api.native_plugin_restart forces the owner's restart through _spawn_gateway_restart(profile, interrupt=True).
    server = hermes.joinpath('hermes_cli', 'web_server.py').read_text(encoding='utf-8-sig')
    assert re.search(r'def _spawn_gateway_restart\(profile[^)]*\*, interrupt: bool = False\)', server)
    assert '"--interrupt"' in server


def _text(hermes, *parts):
    return hermes.joinpath(*parts).read_text(encoding='utf-8-sig')


def test_hermes_still_takes_the_one_install_the_app_sends_for_install_update_and_repair(hermes):
    # app: pluginInstallBody = {identifier, enable:true, force:true, ref:<40-hex release commit>}; the app's policy lets exactly that through.
    models = _text(hermes, 'hermes_cli', 'web_models.py')
    body = re.search(r'class _AgentPluginInstallBody\(BaseModel\):(.*?)\n\S', models, re.S).group(1)
    for field in ('identifier', 'force', 'enable', 'ref'):
        assert re.search(rf'^\s+{field}\s*:', body, re.M), field
    assert re.search(r'@router\.post\("/api/dashboard/agent-plugins/install"\)', _text(hermes, 'hermes_cli', 'web_routers', 'dashboard_ui.py'))
    # ref is a full commit; a forced reinstall of the same source replaces the code and carries the owner's own files; the answer says if a restart is needed
    assert '_EXACT_COMMIT_RE = re.compile(r"^[0-9a-fA-F]{40}$")' in _text(hermes, 'hermes_cli', 'plugins_cmd_git.py')
    install = _text(hermes, 'hermes_cli', 'plugins_cmd_install.py')
    assert '`install --force --ref` is the documented way to move a pin' in install and '_carry_user_files' in install
    assert '"restart_required": not reloaded or bool(missing)' in _text(hermes, 'hermes_cli', 'plugins_activation.py')


def test_hermes_still_refuses_git_pull_updates_of_a_pinned_plugin(hermes):
    # Why the app updates through the pinned install and never through `.../beacon/update` (a pinned plugin cannot be pulled).
    update = _text(hermes, 'hermes_cli', 'plugins_cmd_update.py')
    assert 'install_record.get("pinned") is True' in update and "is pinned to {rec.get('revision')}" in update


def test_hermes_still_never_lets_a_forced_install_pass_a_dangerous_scan(hermes):
    # The app sends force:true on every install, update and repair. That is safe only because of Hermes' own rule that --force accepts a
    # "caution" verdict and never a "dangerous" one, and because a block comes back as an HTTP 400 the app reads as a refusal.
    assert '--force does not override a dangerous verdict.' in _text(hermes, 'tools', 'plugin_guard.py')
    ui = _text(hermes, 'hermes_cli', 'web_routers', 'dashboard_ui.py')
    assert re.search(r'if not result\.get\("ok"\):\s+raise HTTPException\(status_code=400, detail=result\.get\("error"\) or fallback_error\)', ui)
    # The install route lets no flag through as "the client decides": a scan block there is the 400, never a 200 with scan_blocked.
    install_route = re.search(r'async def post_agent_plugin_install\(.*?return result', ui, re.S).group(0)
    assert 'client_decides' not in install_route
    # The 400 says "Security scan blocked ...": the app shows that as its own line instead of "Hermes couldn't do that".
    assert 'f"Security scan blocked plugin install: {reason}' in _text(hermes, 'hermes_cli', 'plugins_cmd.py')
    assert '"error": str(exc), "scan_blocked": True' in _text(hermes, 'hermes_cli', 'plugins_cmd_install.py')


def test_hermes_still_lists_beacon_the_way_the_repair_reads_it(hermes):
    # app: hubPluginOf reads the `beacon` row of GET /api/dashboard/plugins/hub: name, version, runtime_status ("enabled" is on).
    assert re.search(r'@router\.get\("/api/dashboard/plugins/hub"\)', _text(hermes, 'hermes_cli', 'web_routers', 'dashboard_ui.py'))
    dashboard = _text(hermes, 'hermes_cli', 'web_server_dashboard.py')
    row = re.search(r'rows\.append\(\{(.*?)\n        \}\)', dashboard, re.S).group(1)
    for key in ('"name"', '"version"', '"runtime_status"'):
        assert key in row, key
    assert 'payload = {\n        "plugins": rows,' in dashboard
    assert 'runtime_status = "inactive"' in dashboard
    assert re.search(r'def _plugin_status\(.*?return "enabled".*?return "not enabled"', _text(hermes, 'hermes_cli', 'plugins_cmd.py'), re.S)


def test_hermes_still_mounts_a_plugin_that_was_not_mounted_on_its_first_request(hermes):
    # The repair's "missing / off / never loaded" ends without a restart only because of this fallback, and a mounted plugin is never re-mounted.
    dashboard = _text(hermes, 'hermes_cli', 'web_server_dashboard.py')
    assert 'class _PluginApiHotMount' in dashboard and 'def _try_hot_mount_plugin_api(name: str) -> bool:' in dashboard
    assert "if _plugin_api_mounted(app, name):  # a mounted plugin's unknown subpath: no disk rescan" in dashboard


def test_hermes_still_launches_its_gateway_as_gateway_run(hermes):
    # __init__._is_gateway_process records the gateway's plugin version only for a process launched as `gateway run`.
    main = hermes.joinpath('hermes_cli', 'main.py').read_text(encoding='utf-8-sig')
    assert re.search(r'def _argv_is_gateway_run\(.*\n.*"gateway".*"run"', main)


def test_the_check_names_a_renamed_hermes_function(hermes):
    compat = _compat()
    present = (('tui_gateway.server', None, '_sessions', 'native.py', False),)
    renamed = (('tui_gateway.server', None, '_sessions_renamed_in_a_later_hermes', 'native.py', False),)
    alternative = renamed + present
    assert compat.missing([present, alternative], hermes) == []
    assert compat.missing([renamed], hermes) == ['tui_gateway.server._sessions_renamed_in_a_later_hermes (native.py)']


# The Watch, the check-in task and scheduled callbacks reach Hermes' cron by these private spellings: no public Hermes API creates a
# script job (no_agent), changes a job's own fields or lists the paused ones (cron.manage cannot; Lane NR, row 5).
WATCH_CRON_NAMES = {'_cron_profile_home', '_call_cron_for_profile', '_create_cron_job_sync', '_mutate_cron_for_profile'}


def test_the_private_cron_names_the_watch_uses_are_watched_and_exist(hermes):
    compat = _compat()
    groups = [group for group in compat.needs() if any(entry[0] == 'hermes_cli.web_server_cron' for entry in group)]
    watched = {entry[1] for group in groups for entry in group if entry[0] == 'hermes_cli.web_server_cron'}
    assert WATCH_CRON_NAMES <= watched, sorted(WATCH_CRON_NAMES - watched)
    assert compat.missing(groups, hermes) == []


def test_a_renamed_cron_helper_says_which_features_need_a_beacon_update(hermes):
    compat = _compat()
    renamed = (('hermes_cli.web_server_cron', '_mutate_cron_for_profile_renamed_in_a_later_hermes', None, 'proactive.py', False),)
    gone = compat.missing([renamed], hermes)
    assert gone == ['hermes_cli.web_server_cron._mutate_cron_for_profile_renamed_in_a_later_hermes (proactive.py)']
    assert compat.stopped(gone) == ['The Watch, check-in calls and scheduled callbacks need a Beacon update for this Hermes']
    assert compat.stopped(['tui_gateway.server._sessions_renamed (native.py)']) == []


def test_every_native_method_the_app_calls_exists(hermes, app_source):
    methods = set(re.findall(r'\b(?:nativeRpc|rpc)\(\s*"([a-z_]+(?:\.[a-z_]+)+)"', app_source))
    assert len(methods) > 20, 'the app call pattern changed; update this test'
    # Each name must be the first argument of some call (as @method("...") and its helpers register them); any callee counts.
    registered = set()
    for path in hermes.joinpath('tui_gateway').rglob('*.py'):
        registered |= set(re.findall(r'\b\w+\(\s*["\']([a-z_]+(?:\.[a-z_]+)+)["\']', path.read_text(encoding='utf-8-sig', errors='replace')))
    assert sorted(methods - registered) == []


def test_every_http_address_the_app_calls_exists(hermes, app_source):
    called = set(re.findall(r'\b(?:nativeGet|nativeRequest|get|post|request)\(\s*"(/api/[^"(]+)"', app_source))
    # Addresses dispatched by the app's native adapter never reach Hermes over HTTP.
    virtual = set(re.findall(r'"(/api/[^"]+)"(?=(?:\s*,\s*"[^"]+")*\s*->)', app_source))
    native = APP.joinpath('dev/hermes/android/data/NativeOperations.kt').read_text(encoding='utf-8')
    handlers = set(re.findall(r'\bprivate\s+suspend\s+fun\s+(\w+)\s*\(', native))
    # A guarded prefix must return an adapter-local handler; HTTP forwards remain checked.
    prefixes = re.findall(r'\bif\s*\(path\.startsWith\("(/api/[^"]+)"\)\)\s*\{\s*'
                          r'require\(method\s*==\s*"GET"\)\s*\{[^}]*\}\s*return\s+(\w+)\(', native)
    virtual |= {path for path in called for prefix, handler in prefixes if handler in handlers and path.startswith(prefix)}
    hermes_routes = _routes(list(hermes.joinpath('hermes_cli').rglob('*.py')))
    beacon_routes = _beacon_routes()
    gone = []
    for path in sorted(called - virtual):
        bundled = re.match(r'/api/plugins/([a-z_-]+)/', path)
        if path.startswith('/api/plugins/beacon/'):
            routes = beacon_routes
        elif bundled:
            routes = _routes(list(hermes.joinpath('plugins', bundled.group(1)).rglob('*.py')), '/api/plugins/' + bundled.group(1))
        else:
            routes = hermes_routes
        if not _matches(path, routes):
            gone.append(path)
    assert len(called) > 30, 'the app call pattern changed; update this test'
    assert not _matches('/api/cron/jobs-renamed-in-a-later-hermes', hermes_routes)
    assert gone == []


def test_native_prefix_dispatch_does_not_hide_a_missing_http_address(hermes, app_source):
    missing = '/api/mobile/receipts-renamed/$submission'
    with pytest.raises(AssertionError, match=re.escape(missing)):
        test_every_http_address_the_app_calls_exists(hermes, app_source + f'\nc.get("{missing}")')


def _needs_of(source):
    import ast
    compat = _compat()
    found = []
    compat._scan(ast.parse(source).body, {}, 'fixture.py', found)
    return [tuple(e[:3] + (e[4],) for e in group) for group in found]


def test_the_scanner_reads_imports_attributes_and_fallbacks_as_written():
    needs = _needs_of("""
from tui_gateway import server
import hermes_state as state
from beacon_mobile import settings
def uses(row):
    server._sessions
    getattr(server, '_maybe', None)
    getattr(server, '_needed')
def shadowed(server):
    server.anything
try:
    from tui_gateway.session_history import _history_to_messages
except ImportError:
    from tui_gateway.server import _history_to_messages
try:
    import firebase_admin
except ImportError:
    firebase_admin = None
""")
    assert (('tui_gateway', 'server', None, False),) in needs
    assert (('tui_gateway', 'server', '_sessions', False),) in needs
    assert (('tui_gateway', 'server', '_needed', False),) in needs
    assert not any(e[2] in ('_maybe', 'anything') for g in needs for e in g), 'a default or a parameter is not a need'
    assert not any(e[0].startswith('beacon_') for g in needs for e in g), "the plugin's own siblings are not Hermes'"
    assert (('tui_gateway.session_history', '_history_to_messages', None, False),
            ('tui_gateway.server', '_history_to_messages', None, False)) in needs, 'either import serves'
    assert (('firebase_admin', None, None, True),) in needs, 'an ImportError-only guard marks an optional import'


# Turning Beacon on for another profile reaches Hermes' own enable by these private spellings: Hermes has no profile-scoped enable route, its name
# resolver only sees the active profile's own plugins, and the one way it changes plugins.enabled is the PM transaction behind `_activate_key`.
PROFILE_ENABLE_NAMES = {'_resolve_plugin_key', '_activate_key', '_get_enabled_set', '_get_disabled_set'}


def test_the_profile_enable_names_are_watched_and_exist(hermes):
    compat = _compat()
    groups = [group for group in compat.needs() if any(entry[:2] == ('hermes_cli', 'plugins_cmd') or entry[0] == 'hermes_cli.web_server_profiles' for entry in group)]
    watched = {entry[2] for group in groups for entry in group if entry[:2] == ('hermes_cli', 'plugins_cmd') and entry[2]}
    assert PROFILE_ENABLE_NAMES <= watched, sorted(PROFILE_ENABLE_NAMES - watched)
    scope = [entry for group in groups for entry in group if entry[:2] == ('hermes_cli.web_server_profiles', '_config_profile_scope')]
    assert any(entry[3] == 'admin_api.py' for entry in scope), 'the scope helper the enable runs in is watched too'
    assert compat.missing(groups, hermes) == []


def _function(path, name):
    import ast
    tree = ast.parse(path.read_text(encoding='utf-8-sig'))
    return next(node for node in tree.body if isinstance(node, ast.FunctionDef) and node.name == name)


def test_hermes_enable_still_has_the_shape_the_route_relies_on(hermes):
    """Read from source, never imported: the key resolver, the activation that is the PM transaction, the selection read-back and the scope."""
    import ast
    commands = hermes / 'hermes_cli' / 'plugins_cmd.py'
    resolve = _function(commands, '_resolve_plugin_key')
    assert [a.arg for a in resolve.args.args] == ['name']
    activate = _function(commands, '_activate_key')
    assert [a.arg for a in activate.args.args] == ['key'] and [a.arg for a in activate.args.kwonlyargs][:1] == ['enable']
    called = {n.func.id for n in ast.walk(activate) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}
    assert '_set_plugin_enabled' in called, 'activation goes through the PM transaction, which Hermes says every enable surface must use'
    admission = _function(commands, '_set_plugin_enabled')
    assert '_admit_and_save_plugin_sets' in {n.func.id for n in ast.walk(admission) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}
    assert '_get_enabled_set' in commands.read_text(encoding='utf-8-sig')
    # plugins.disabled is the deny-list Hermes' loader obeys over plugins.enabled; the phone's own turn-on and the background pass leave a profile it names alone.
    assert '_get_disabled_set = functools.partial(_config_name_set, "plugins", "disabled")' in commands.read_text(encoding='utf-8-sig')
    scope = _function(hermes / 'hermes_cli' / 'web_server_profiles.py', '_config_profile_scope')
    assert [a.arg for a in scope.args.args] == ['profile']
    assert any((isinstance(d, ast.Name) and d.id == 'contextmanager') for d in scope.decorator_list)
    # The name resolver scans bundled plus the ACTIVE profile's own plugins directory only, which is why the key is resolved in the dashboard's profile.
    discover = _function(commands, '_discover_all_plugins')
    assert '_plugins_dir' in ast.unparse(discover)


def _assert_profile_scope_is_per_request(root):
    """The profile route runs Hermes' own activation in a worker thread for up to 120 s inside `_config_profile_scope(profile)`. That is only safe
    while the scope is a context variable (`asyncio.to_thread` copies the request's context into the thread); a process-global swap would let a
    concurrent request read or write the wrong profile's config. Source reads only."""
    import ast

    def calls(node):
        return {n.func.id for n in ast.walk(node) if isinstance(n, ast.Call) and isinstance(n.func, ast.Name)}

    profiles = root / 'hermes_cli' / 'web_server_profiles.py'
    assert '_hermes_home_scope' in calls(_function(profiles, '_config_profile_scope')), '_config_profile_scope no longer enters the context-local home scope'
    assert 'set_hermes_home_override' in calls(_function(profiles, '_hermes_home_scope')), '_hermes_home_scope no longer sets the context-local override'
    constants = ast.parse((root / 'hermes_constants.py').read_text(encoding='utf-8-sig'))
    held = [n.value for n in constants.body if isinstance(n, (ast.Assign, ast.AnnAssign)) and n.value is not None
            and '_HERMES_HOME_OVERRIDE' in {t.id for t in (n.targets if isinstance(n, ast.Assign) else [n.target]) if isinstance(t, ast.Name)}]
    assert len(held) == 1 and isinstance(held[0], ast.Call) and getattr(held[0].func, 'id', None) == 'ContextVar', '_HERMES_HOME_OVERRIDE is no longer a ContextVar'
    setter = _function(root / 'hermes_constants.py', 'set_hermes_home_override')
    assert any(isinstance(n, ast.Attribute) and n.attr == 'set' and getattr(n.value, 'id', None) == '_HERMES_HOME_OVERRIDE' for n in ast.walk(setter)), \
        'set_hermes_home_override no longer sets the ContextVar'


def test_hermes_profile_scope_is_per_request_not_process_global(hermes):
    _assert_profile_scope_is_per_request(hermes)


@pytest.mark.parametrize('name, file, old, new', [
    ('the override becomes a plain module global', 'hermes_constants.py',
     'ContextVar("_HERMES_HOME_OVERRIDE", default=_UNSET)', '_UNSET'),
    ('the home scope swaps the process environment', 'hermes_cli/web_server_profiles.py',
     'token = set_hermes_home_override(str(path))', 'token = os.environ.__setitem__("HERMES_HOME", str(path))'),
    ('the config scope stops using the home scope', 'hermes_cli/web_server_profiles.py',
     'with (_hermes_home_scope(profile_dir) if profile_dir is not None else nullcontext()):', 'with nullcontext():'),
])
def test_the_scope_check_fails_on_a_hermes_that_scopes_by_global(hermes, tmp_path, name, file, old, new):
    """A mutant in a temporary copy of the two Hermes files (Hermes itself is never touched) must trip the check above."""
    for relative in ('hermes_constants.py', 'hermes_cli/web_server_profiles.py'):
        (tmp_path / relative).parent.mkdir(parents=True, exist_ok=True)
        (tmp_path / relative).write_bytes((hermes / relative).read_bytes())
    _assert_profile_scope_is_per_request(tmp_path)  # the unmutated copy passes, so a failure below is the mutant's
    text = (tmp_path / file).read_text(encoding='utf-8')
    assert old in text, 'Hermes changed this line; update the mutant'
    (tmp_path / file).write_text(text.replace(old, new, 1), encoding='utf-8')
    with pytest.raises(AssertionError):
        _assert_profile_scope_is_per_request(tmp_path)


def test_a_renamed_enable_helper_says_which_feature_needs_a_beacon_update(hermes):
    compat = _compat()
    renamed = (('hermes_cli.plugins_cmd', '_activate_key_renamed_in_a_later_hermes', None, 'admin_api.py', False),)
    gone = compat.missing([renamed], hermes)
    assert gone == ['hermes_cli.plugins_cmd._activate_key_renamed_in_a_later_hermes (admin_api.py)']
    assert compat.stopped(gone) == ['Turning Beacon on for another profile from the phone needs a Beacon update for this Hermes']
    assert compat.stopped(['tui_gateway.server._sessions_renamed (native.py)']) == []


def test_every_route_the_app_asks_the_plugin_for_to_turn_a_profile_on_exists(app_source):
    routes = _beacon_routes()
    assert '/api/plugins/beacon/admin/profile-plugin/enable' in routes
    assert _matches('/api/plugins/beacon/admin/profile-plugin/enable', routes)



def test_every_pm_selection_race_is_read_as_a_race_and_hermes_wraps_it_as_the_pass_expects(hermes):
    """The background pass (admin_api.turn_on_for_profiles) asks again on the next pass, without parking or a warning, when PM refused only because
    the selection changed while it worked. Hermes has no type for that: the worker re-raises a plain ValueError and admission wraps it in a plain
    AdmissionRefused, so the pass reads PM's own messages. Each of them must still read as a race, and the wrapping must still keep the message."""
    spec = importlib.util.spec_from_file_location('beacon_admin_api_contract', PLUGIN / 'admin_api.py')
    admin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(admin)
    messages = set()
    for source in sorted((hermes / 'pm').glob('*.py')):
        messages.update(re.findall(r'raise ValueError\(\s*"([^"]*changed[^"]*retry\.?)"\s*\)', source.read_text(encoding='utf-8-sig')))
    assert 'plugin configuration changed while preparing publication; retry' in messages, sorted(messages)
    assert 'Plugin configuration changed since this selection was read; retry.' in messages, sorted(messages)

    class AdmissionRefused(RuntimeError):
        pass
    for message in sorted(messages):
        error = AdmissionRefused(message)
        error.__cause__ = ValueError(message)
        assert admin._changed_meanwhile(error), message
    admission = _text(hermes, 'hermes_cli', 'plugins_admission.py')
    assert 'raise AdmissionRefused(str(exc)) from exc' in admission
    assert 'raise DependencyConflict(exc.cause, plugin=plugin) from exc' in admission
    assert '"ValueError": ValueError' in _text(hermes, 'pm', 'client.py')

def test_hermes_still_reads_pending_admissions_the_way_the_lost_turn_route_asks(hermes):
    """lost_turns_api reads Hermes' own admission rows on a read-only store (WEDGE-121 follow-up 3). compat names a renamed function;
    this pins what compat cannot see: the keyword, that the read stays a read, that a lost turn is still pending as `unknown`, and
    that SessionDB still opens read-only by keyword."""
    import ast
    runtime = hermes / 'hermes_state_runtime.py'
    source = runtime.read_text(encoding='utf-8-sig')
    read = _function(runtime, 'list_session_admissions')
    assert [a.arg for a in read.args.args] == ['db'] and [a.arg for a in read.args.kwonlyargs] == ['session_id', 'pending_only']
    body = ast.get_source_segment(source, read)
    assert '_read_ctx' in body and '_execute_write' not in body, 'the admission read must stay a read'
    assert "'unknown'" in body and "'queued'" in body, 'a lost turn is pending as unknown, what waits behind it as queued'
    common = (hermes / 'hermes_state_common.py').read_text(encoding='utf-8-sig')
    assert "status IN ('queued','started','unknown','terminal')" in common
    state = ast.parse((hermes / 'hermes_state.py').read_text(encoding='utf-8-sig'))
    session_db = next(n for n in state.body if isinstance(n, ast.ClassDef) and n.name == 'SessionDB')
    init = next(n for n in session_db.body if isinstance(n, ast.FunctionDef) and n.name == '__init__')
    assert [a.arg for a in init.args.args][:3] == ['self', 'db_path', 'read_only']


def test_hermes_still_discards_a_lost_turn_and_finds_a_bot_chat_the_way_follow_up_4_relies_on(hermes):
    """WEDGE-121 follow-up 4. The app lets a lone lost reply go on open with prompt.resolve_unknown, taking exactly the session, the
    admission and the generation the route reads; Hermes must still treat it as Discard (the input is never requeued, only the paused
    queue resumes). The route finds a bot's chat by Hermes' own exact-title lookup and reads each lost admission's generation."""
    import ast
    controls = (hermes / 'gateway' / 'session_controls.py').read_text(encoding='utf-8-sig')
    assert "if set(params) != {'session_id', 'admission_id', 'execution_generation'}:" in controls
    authority = (hermes / 'gateway' / 'session_authority.py').read_text(encoding='utf-8-sig')
    discard = authority[authority.index('async def resolve_unknown(self, actor, ref, admission_id, generation):'):]
    discard = discard[:discard.index('async def resolve_unknown_as_owner')]
    assert 'Never requeues the input' in discard and 'paused FIFO behind' in discard
    assert "self.authorize(actor, ref, 'session:control')" in discard
    common = (hermes / 'hermes_state_common.py').read_text(encoding='utf-8-sig')
    assert 'generation INTEGER' in common[common.index('CREATE TABLE IF NOT EXISTS session_admissions'):]
    titles = ast.parse((hermes / 'hermes_state_titles.py').read_text(encoding='utf-8-sig'))
    lookup = next(n for n in ast.walk(titles) if isinstance(n, ast.FunctionDef) and n.name == 'resolve_session_by_title')
    assert [a.arg for a in lookup.args.args] == ['self', 'title']
    state = (hermes / 'hermes_state.py').read_text(encoding='utf-8-sig')
    assert 'CANONICAL_BOT_CHAT_TITLE = "Bot Chat"' in state


# The agent phone's vault bridge (agent_phone_vault.py) reaches Hermes' vault by these names; EMU-DESIGN §6.2 lists them.
PHONE_VAULT_NAMES = {('agent.vault_store', 'VaultStore'), ('agent.vault_store', 'get_vault_store'), ('agent.vault_store', 'normalize_origin'),
                     ('agent.vault_backends', 'enabled_backends'), ('agent.vault_backends', 'backend_for_handle'),
                     ('agent.redact', 'register_vault_redaction_value'), ('tools.approval_prompt', 'request_elicitation_consent'),
                     ('agent.vault_backends.unlock', 'get_save_login_prompt_callback'), ('agent.vault_backends.unlock', 'can_prompt_here'),
                     ('agent.vault_backends.unlock', 'get_code_prompt_callback'), ('tools.browser_vault_context', 'saved_origins')}


def test_the_agent_phone_vault_names_are_watched_and_exist(hermes):
    compat = _compat()
    groups = [group for group in compat.needs() if any(entry[3] == compat.PHONE_VAULT_FILE for entry in group)]
    watched = {(entry[0], entry[1]) for group in groups for entry in group}
    assert PHONE_VAULT_NAMES <= watched, sorted(PHONE_VAULT_NAMES - watched)
    assert compat.missing(groups, hermes) == []


def test_a_renamed_agent_phone_vault_name_says_which_feature_needs_a_beacon_update(hermes):
    compat = _compat()
    renamed = (('agent.vault_store', 'VaultStore_renamed_in_a_later_hermes', None, 'agent_phone_vault.py', False),)
    gone = compat.missing([renamed], hermes)
    assert gone == ['agent.vault_store.VaultStore_renamed_in_a_later_hermes (agent_phone_vault.py)']
    assert compat.stopped(gone) == ["Saved logins on the assistant's phone need a Beacon update for this Hermes"]


def test_hermes_vault_still_has_the_shapes_the_agent_phone_relies_on(hermes):
    # VaultStore(base_dir=...) is how the dashboard binds the owning profile's vault, never the request's (EMU-DESIGN §6.2).
    store = _text(hermes, 'agent', 'vault_store.py')
    assert 'def __init__(self, base_dir: Optional[Path] = None):' in store
    assert re.search(r'def add_item\(\s*self,\s*kind: str,\s*label: str,\s*secret: dict\[str, Any\],\s*origin: Optional\[str\] = None', store)
    assert '"login items require identifier and password"' in store and 'LOGIN_IDENTIFIER_TYPES = ("email", "phone", "username")' in store
    # The card question is Hermes' own consent with the vault-payment surface, answered "accept" for yes.
    consent = _text(hermes, 'tools', 'approval_prompt.py')
    assert re.search(r'def request_elicitation_consent\(message: str, description: str, \*,.*?surface: str = ', consent, re.S)
    assert 'surface="vault-payment"' in _text(hermes, 'tools', 'browser_vault_tool.py')
    # The save prompt is called as prompt(origin, site) and answers {identifier, password}; the code prompt as prompt(site, hint).
    unlock = _text(hermes, 'agent', 'vault_backends', 'unlock.py')
    assert 'SaveLoginPrompt = Callable[[str, str], Optional[dict[str, str]]]' in unlock and 'CodePrompt = Callable[[str, str], str]' in unlock
    assert 'def saved_origins(meta) -> list[str]:' in _text(hermes, 'tools', 'browser_vault_context.py')
