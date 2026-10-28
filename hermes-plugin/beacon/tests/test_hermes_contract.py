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


def test_the_check_names_a_renamed_hermes_function(hermes):
    compat = _compat()
    present = (('tui_gateway.server', None, '_sessions', 'native.py', False),)
    renamed = (('tui_gateway.server', None, '_sessions_renamed_in_a_later_hermes', 'native.py', False),)
    alternative = renamed + present
    assert compat.missing([present, alternative], hermes) == []
    assert compat.missing([renamed], hermes) == ['tui_gateway.server._sessions_renamed_in_a_later_hermes (native.py)']


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
    # Addresses the app dispatches to native methods itself (a `when` branch) never reach Hermes over HTTP.
    virtual = set(re.findall(r'"(/api/[^"]+)"(?=(?:\s*,\s*"[^"]+")*\s*->)', app_source))
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
