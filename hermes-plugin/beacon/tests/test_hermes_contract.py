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
PROFILE_ENABLE_NAMES = {'_resolve_plugin_key', '_activate_key', '_get_enabled_set'}


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
