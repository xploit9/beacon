"""Call tool discovery starts from Hermes' own persisted MCP schema cache, so it is complete the moment the dashboard starts.

The tests run Hermes' real MCP registration, lazy-start and cache code from its source against a temporary home; the only fakes are the MCP
servers themselves (a connect that takes twenty seconds). Synthetic names only; no address-shaped text.
"""
import ast
import asyncio
import importlib.util
import json
import shutil
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

# "Did not wait": well under the fake 20 s connect and the 2 s first-scan wait, with room for a loaded test machine.
INSTANT = 1.0
import yaml

from test_gateway_tools import Mobile, _hermes_agent_source

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('beacon_mcp_cache_test', ROOT / 'gateway_tools.py')
g = importlib.util.module_from_spec(spec)
spec.loader.exec_module(g)
SCOPE = ('owner', 'password', 'profile', 'call')
FAKE_KEY = 'sk-' + 'abcdefghijklmnopqrstuvwxyz' + '0123456789'  # built here so the file holds no key-shaped text
SERVERS = 8
TOOLS_PER_SERVER = 41  # eight servers, 328 tools: the owner's setup


def tool_entries(prefix, count=TOOLS_PER_SERVER):
    """A server's cached tool list: odd tools are annotated read-only and named as reads, even ones write."""
    return [{'name': f'{"search" if index % 2 else "delete"}_{prefix}_{index}', 'description': f'Tool {index} of {prefix}',
             'inputSchema': {'type': 'object', 'properties': {'q': {'type': 'string'}}, 'required': ['q']},
             'annotations': {'readOnlyHint': bool(index % 2)}} for index in range(count)]


def server_config(name, **extra):
    return {'command': 'example-server', 'args': ['--name', name], **extra}


class Connections:
    """The fake MCP servers: what Hermes asked to connect, and when each connect may finish."""
    def __init__(self):
        self.log = []
        self.release = threading.Event()
        self.delay = 20.0
        self.tools = {}

    async def connect(self, name, config):
        from tools import mcp_tool as core
        from tools import mcp_tool_discovery as discovery
        from tools import mcp_tool_registration as registration
        from tools.mcp_tool_common import _resolve_tool_timeout
        from tools.mcp_tool_scope import _server_key
        self.log.append(name)
        waited = 0.0
        while not self.release.is_set() and waited < self.delay:
            await asyncio.sleep(0.01)
            waited += 0.01
        if not self.release.is_set(): raise TimeoutError('the fake server never answered')
        server = SimpleNamespace(name=name, _config=config, _tools=[SimpleNamespace(**item) for item in self.tools.get(name, [])],
                                 tool_timeout=_resolve_tool_timeout(config), session=object(), initialize_result=None, _registered_tool_names=[],
                                 _list_cache_meta={}, _task=None, _ever_connected=True, _park_reason=None,
                                 _is_recycled_stdio=lambda: False)
        with core._lock:
            core._server_connecting.discard(_server_key(name))
        discovery._adopt_server(name, server)
        server._registered_tool_names = list(registration._register_server_tools(name, server, config))
        return server._registered_tool_names


PRIMED = []
LEDGERS = ('_servers', '_lazy_server_configs', '_lazy_server_fingerprints', '_lazy_server_tool_names', '_server_trust_levels',
           '_tool_read_only_hints', '_mcp_tool_server_names', '_server_cost_policies', '_server_tool_scopes', '_server_scope_keys',
           '_server_connecting', '_server_connect_errors', '_server_connect_retry_after', '_server_connect_failures')


@pytest.fixture(scope='module')
def shared_home(tmp_path_factory):
    """One Hermes home for the whole module, as a running dashboard has: Hermes builds its per-home plugin manager once, not per test."""
    return tmp_path_factory.mktemp('hermes-home')


@pytest.fixture
def hermes(shared_home, tmp_path, monkeypatch):
    """Hermes' real MCP modules over a temporary home with eight configured servers and a cache entry for each."""
    source = _hermes_agent_source()
    monkeypatch.syspath_prepend(str(source))
    monkeypatch.setenv('HERMES_HOME', str(shared_home))
    shutil.rmtree(shared_home / 'cache', ignore_errors=True)
    from tools import mcp_tool as core
    from tools import mcp_tool_discovery as discovery
    from tools.registry import registry
    # The dashboard has all of these imported long before a call; the first import in a process is not what is being timed.
    import agent.redact, hermes_cli.config, tools.mcp_schema_cache, tools.mcp_tool_common, tools.mcp_tool_config  # noqa: E401,F401
    import tools.mcp_tool_handlers, tools.mcp_tool_registration, tools.mcp_tool_scope  # noqa: E401,F401
    g.safe('warm', 10)
    for module in (g.MCP_REGISTERED, g.MCP_CACHE_FILES):
        module.clear()
    g.MCP_REFRESHED.clear()
    g.MCP_SCANNING.clear()
    g.CATALOG_CACHE.clear()
    g.CATALOG_SETTLED.clear()
    monkeypatch.setattr(core, '_MCP_DISCOVERY_LOCK_PATH', None)
    monkeypatch.setattr(g, 'native_catalog', lambda: [])
    saved = {name: (dict(value) if isinstance(value, dict) else set(value)) for name in LEDGERS
             for value in [getattr(core, name, None)] if value is not None}
    connections = Connections()
    monkeypatch.setattr(discovery, '_connect_server', None)
    monkeypatch.setattr(discovery, '_discover_and_register_server', connections.connect)
    environment = SimpleNamespace(home=shared_home, private=tmp_path, connections=connections, source=source)
    environment.servers = {f'server{index}': server_config(f'server{index}') for index in range(SERVERS)}
    environment.write_config = lambda servers=None: (shared_home / 'config.yaml').write_text(
        yaml.safe_dump({'mcp_servers': environment.servers if servers is None else servers}), encoding='utf-8')
    environment.write_config()
    from tools.mcp_schema_cache import config_fingerprint, write_cache_entry
    environment.cache = lambda name, tools=None, config=None, **extra: write_cache_entry(
        name, config_fingerprint(config or environment.servers[name]), tools=tool_entries(name) if tools is None else tools, **extra)
    for name in environment.servers:
        environment.cache(name)
        connections.tools[name] = tool_entries(name)

    def reset():
        for entry in list(registry.get_all_entries()):
            if entry.toolset.startswith('mcp-'): registry.deregister(entry.name)
        for name, value in saved.items():
            current = getattr(core, name)
            current.clear()
            current.update(value)
        for name in LEDGERS:
            if name not in saved: getattr(core, name, {}).clear()
        # Rows built by one test must not be read by the next (the profile key is per temporary home, but the cache is global).
        for state in (g.MCP_REGISTERED, g.MCP_CACHE_FILES, g.CATALOG_CACHE):
            state.clear()
        for state in (g.MCP_REFRESHED, g.MCP_SCANNING, g.CATALOG_SETTLED):
            state.clear()

    if not PRIMED:
        # One pass whose only purpose is the lazy imports and pattern compilations a process pays once; a dashboard has long paid them.
        PRIMED.append(True)
        priming = g.Gateway(Mobile(tmp_path / 'priming.sqlite'), lambda name, fn, **kwargs: fn(shared_home))
        priming.discover('priming', 'search')
        reset()
    yield environment
    connections.release.set()
    reset()


@pytest.fixture
def gateway(hermes, monkeypatch):
    monkeypatch.setattr(g, 'FIRST_SCAN_WAIT', 0.3)
    profile = 'cache-' + str(time.time_ns())
    api = g.Gateway(Mobile(hermes.private / 'private.sqlite'), lambda name, fn, **kwargs: fn(hermes.home))
    api.profile = profile
    yield api
    first = g.CATALOG_FIRST_WARM.pop(profile, None)
    if first is not None and first is not threading.current_thread(): first.join(5)


def settled_receipt(api, request):
    for _ in range(300):
        receipt = api.status(SCOPE, request)
        if receipt['status'] != 'running': return receipt
        time.sleep(0.01)
    pytest.fail('receipt never settled')


def wait_for(condition, seconds=5):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if condition(): return True
        time.sleep(0.01)
    return False


def tool_named(api, profile, name):
    return next(tool for tool in api.catalog(profile, discover=False) if tool['_raw'] == name)


# --- the cache makes a cold process complete and instant ---------------------------------------------------------

def test_a_cold_process_lists_every_cached_tool_at_once_and_starts_nothing(gateway, hermes):
    from tools import mcp_tool as core
    assert not core._servers and not core._lazy_server_configs, 'a cold process: nothing registered yet'

    begin = time.perf_counter()
    answer = gateway.discover(gateway.profile, 'search')
    elapsed = time.perf_counter() - begin

    assert elapsed < INSTANT, f'a 20 s connect is not waited for: answered in {elapsed * 1000:.0f} ms'
    assert answer['total_matches'] == SERVERS * (TOOLS_PER_SERVER // 2), 'every server contributes its read tools'
    assert 'still_starting' not in answer
    for index in range(SERVERS): assert f'server{index}' in answer['sources']
    assert hermes.connections.log == [] and not core._servers, 'no MCP server was started'
    assert len(core._lazy_server_configs) == SERVERS
    assert len(gateway.catalog(gateway.profile, discover=False)) == SERVERS * TOOLS_PER_SERVER


def test_a_cold_process_gives_the_call_its_overview_without_a_scan(gateway, hermes):
    begin = time.perf_counter()
    overview = gateway.compact_catalogue(gateway.profile)
    elapsed = time.perf_counter() - begin
    assert elapsed < INSTANT and hermes.connections.log == []
    assert overview.count('\n') + 1 == SERVERS and overview.startswith('server0:')


def test_invoking_a_cached_tool_connects_only_its_own_server(gateway, hermes):
    from tools import mcp_tool as core
    gateway.discover(gateway.profile, 'search')
    tool = tool_named(gateway, gateway.profile, 'search_server3_1')
    assert tool['read_only'] and tool['_server'] == 'server3'
    hermes.connections.delay = 0.05
    hermes.connections.release.set()

    receipt = gateway.invoke(SCOPE, {'request_id': 'r1', 'tool_id': tool['tool_id'], 'arguments': {'q': 'weather'}})
    assert receipt['status'] in ('running', 'completed', 'failed')
    settled_receipt(gateway, 'r1')

    assert hermes.connections.log == ['server3'], 'Hermes started the one server the tool belongs to, and no other'
    assert set(core._servers) == {'server3'}
    assert set(core._lazy_server_configs) == {f'server{index}' for index in range(SERVERS) if index != 3}, 'the rest stay unstarted'


def test_a_server_with_no_cached_list_is_scanned_in_the_background_and_named_meanwhile(gateway, hermes):
    hermes.servers['newcomer'] = server_config('newcomer')
    hermes.write_config()
    hermes.connections.tools['newcomer'] = tool_entries('newcomer', 4)

    begin = time.perf_counter()
    answer = gateway.discover(gateway.profile, 'search')
    elapsed = time.perf_counter() - begin

    assert elapsed < g.FIRST_SCAN_WAIT + 0.5, 'it waits for the one scan no longer than a moment'
    assert answer['still_starting'] == ['newcomer'], 'only the server being scanned is named'
    assert answer['total_matches'] == SERVERS * (TOOLS_PER_SERVER // 2), 'the cached servers are all there meanwhile'
    assert wait_for(lambda: hermes.connections.log), 'the scan started'
    assert hermes.connections.log == ['newcomer'], 'and it is the only server connected'

    hermes.connections.release.set()
    assert wait_for(lambda: any(tool.get('_server') == 'newcomer' for tool in gateway.catalog(gateway.profile, discover=False)))
    found = gateway.discover(gateway.profile, 'search newcomer')
    assert 'still_starting' not in found and 'newcomer' in found['sources']
    assert hermes.connections.log == ['newcomer']
    from tools.mcp_schema_cache import get_cached_entry, config_fingerprint
    assert get_cached_entry('newcomer', config_fingerprint(hermes.servers['newcomer'])), 'Hermes wrote the new list for next time'


def test_discover_does_not_wait_for_a_scan_when_the_cache_covers_every_server(tmp_path):
    started = threading.Event()
    release = threading.Event()
    ready = {'name': 'read_file', '_toolset': 'file', 'description': 'Read a local file', 'read_only': True, 'tool_id': 'f' * 64,
             'parameters': {'type': 'object', 'properties': {}}}

    class Backend:
        def cache_key(self): return 'covered'
        def pending_servers(self): return []
        def configured_servers(self): return ['acme_browser']
        def catalog(self, discover=True):
            if discover: started.set(); release.wait(10)
            return [ready]

    api = g.Gateway(Mobile(tmp_path / 'covered.sqlite'), lambda name, fn, **kwargs: fn(tmp_path), Backend())
    profile = 'covered-' + str(time.time_ns())
    worker = threading.Thread(target=api.prefetch, args=(profile,))
    worker.start()
    try:
        assert started.wait(1)
        begin = time.perf_counter()
        answer = api.discover(profile, 'read a file')
        assert time.perf_counter() - begin < INSTANT, 'the first scan is not waited for: the cache covers every server'
        assert answer['tools'][0]['name'] == 'read_file' and 'still_starting' not in answer
    finally:
        release.set()
        worker.join(2)


def test_only_servers_that_need_a_live_scan_are_named_as_starting(tmp_path):
    started = threading.Event()
    release = threading.Event()

    class Backend:
        def cache_key(self): return 'named'
        def pending_servers(self): return ['acme_browser']
        def configured_servers(self): return ['acme_browser', 'acme_notes']  # acme_notes has a cached list but no tools matched
        def catalog(self, discover=True):
            if discover: started.set(); release.wait(10)
            return []

    api = g.Gateway(Mobile(tmp_path / 'named.sqlite'), lambda name, fn, **kwargs: fn(tmp_path), Backend())
    profile = 'named-' + str(time.time_ns())
    worker = threading.Thread(target=api.prefetch, args=(profile,))
    worker.start()
    try:
        assert started.wait(1)
        assert api.discover(profile, 'open')['still_starting'] == ['acme browser']
    finally:
        release.set()
        worker.join(2)


# --- freshness without polling ----------------------------------------------------------------------------------

def test_a_changed_config_is_not_served_from_the_old_cache_entry(gateway, hermes):
    from tools import mcp_tool as core
    from tools.mcp_tool_scope import _resolve_server_key
    native = g.NativeMcp()
    assert native.pending_servers() == []
    assert any(row['_server'] == 'server2' for row in native.catalog(discover=False))

    hermes.servers['server2'] = server_config('server2', args=['--name', 'server2', '--other-build'])
    hermes.write_config()

    assert native.pending_servers() == ['server2'], 'the entry was written for another config: this server needs a live scan'
    assert _resolve_server_key('server2') not in core._lazy_server_configs, 'and the tools registered from the old entry are gone'
    assert not any(row['_server'] == 'server2' for row in native.catalog(discover=False))
    assert hermes.connections.log == []


def test_a_cache_file_change_is_picked_up_without_a_restart(gateway, hermes):
    first_key = g.NativeMcp().cache_key()
    gateway.discover(gateway.profile, 'search')
    before = {row['_raw'] for row in gateway.catalog(gateway.profile, discover=False) if row['_server'] == 'server1'}
    assert 'search_server1_1' in before and 'search_server1_extra' not in before

    # Hermes rewrites the file itself when a server's tool list changes.
    changed = [item for item in tool_entries('server1') if item['name'] != 'search_server1_1']
    changed.append({'name': 'search_server1_extra', 'description': 'Added later', 'inputSchema': {'type': 'object', 'properties': {}},
                    'annotations': {'readOnlyHint': True}})
    hermes.cache('server1', tools=changed)

    assert g.NativeMcp().cache_key() != first_key, 'the list is keyed to the cache file'
    after = {row['_raw'] for row in gateway.catalog(gateway.profile, discover=False) if row['_server'] == 'server1'}
    assert 'search_server1_extra' in after and 'search_server1_1' not in after
    assert hermes.connections.log == []


def test_registry_generation_changes_do_not_invalidate_a_complete_list(gateway, hermes):
    from tools.registry import registry
    calls = []
    backend = gateway.backend
    original = backend.catalog

    def counted(discover=True):
        calls.append(discover)
        return original(discover)

    backend.catalog = counted
    gateway.prefetch(gateway.profile)
    key = gateway._catalog_key(gateway.profile)
    rows = g.CATALOG_CACHE[key][1]
    assert key in g.CATALOG_SETTLED and calls == [True]

    registry._generation += 5  # what any background MCP registration does
    assert gateway._catalog_key(gateway.profile) == key, 'the key does not follow the registry generation'
    # Older than the 15 s a list with servers still to scan lives: a complete list is not rescanned for it.
    with g.CATALOG_LOCK:
        created, held = g.CATALOG_CACHE[key]
        g.CATALOG_CACHE[key] = (created - g.CATALOG_TTL - 1, held)
    served = gateway.catalog(gateway.profile, discover=False)
    assert [tool['tool_id'] for tool in served] == [tool['tool_id'] for tool in rows]
    time.sleep(0.1)
    assert calls == [True], 'no rescan, no re-discovery'
    assert hermes.connections.log == []


def test_a_list_that_still_has_a_server_to_scan_is_rescanned_after_the_short_ttl(gateway, hermes):
    hermes.servers['newcomer'] = server_config('newcomer')
    hermes.write_config()
    gateway.warm_catalogue(gateway.profile)
    assert wait_for(lambda: hermes.connections.log)
    key = gateway._catalog_key(gateway.profile)
    assert key not in g.CATALOG_SETTLED
    assert gateway._fresh_for(key) == g.CATALOG_TTL
    hermes.connections.release.set()
    # Once the scan has written the new server's list, the key follows the cache file and the complete list is the long-lived one.
    assert wait_for(lambda: gateway._catalog_key(gateway.profile) in g.CATALOG_SETTLED)
    assert gateway._fresh_for(gateway._catalog_key(gateway.profile)) == g.CATALOG_STALE_TTL


def test_only_a_list_the_backend_itself_built_complete_is_stored_as_settled(gateway, hermes):
    native = gateway.backend
    assert native.settled() is False, 'nothing was built yet'
    native.catalog(discover=False)
    assert native.settled() is True and native.settled() is False, 'read once, from the call that built the list'
    hermes.servers['newcomer'] = server_config('newcomer')
    hermes.write_config()
    native.catalog(discover=False)
    assert native.settled() is False, 'a server with no cached list leaves a scan to do'
    # A backend whose catalog is replaced (as other tests, or a wrapper, do) never leaves a settled list in the cache.
    gateway.backend.catalog = lambda discover=True: [{'name': 'read_file', '_toolset': 'file', 'description': 'Read', 'read_only': True}]
    g.CATALOG_CACHE.clear()
    assert [tool['name'] for tool in gateway.catalog(gateway.profile, discover=False)] == ['read_file']
    assert not g.CATALOG_CACHE and not g.CATALOG_SETTLED


def test_a_profile_served_through_a_home_override_keeps_its_cached_servers_through_a_live_scan(hermes, tmp_path):
    """Hermes drops a routed profile's lazily registered tools when a scoped discovery runs; the next look puts them back."""
    from hermes_constants import reset_hermes_home_override, set_hermes_home_override
    from tools import mcp_tool as core
    from tools.mcp_schema_cache import config_fingerprint, write_cache_entry
    from tools.mcp_tool_config import _load_mcp_config
    routed = tmp_path / 'routed-profile'
    routed.mkdir()
    servers = {name: server_config(name) for name in ('alpha', 'bravo', 'fresh')}
    (routed / 'config.yaml').write_text(yaml.safe_dump({'mcp_servers': servers}), encoding='utf-8')
    hermes.connections.tools['fresh'] = tool_entries('fresh', 2)
    hermes.connections.release.set()
    token = set_hermes_home_override(str(routed))
    try:
        assert core._mcp_registry_scope() is not None, 'this is a routed profile: its registrations live in its own scope'
        for name in ('alpha', 'bravo'):
            write_cache_entry(name, config_fingerprint(_load_mcp_config()[name]), tools=tool_entries(name, 3))
        native = g.NativeMcp()
        assert native.pending_servers() == ['fresh']
        assert {row['_server'] for row in native.catalog(discover=False) if row.get('_server')} == {'alpha', 'bravo'}

        rows = native.catalog()  # the live scan of the one uncached server
        assert {row['_server'] for row in rows if row.get('_server')} == {'alpha', 'bravo', 'fresh'}
        assert hermes.connections.log == ['fresh']
        assert native.settled() is True and native.pending_servers() == []
    finally:
        from tools.registry import registry
        for entry in list(registry.get_all_entries()):
            if entry.toolset.startswith('mcp-'): registry.deregister(entry.name, scope=registry.current_scope_key())
        reset_hermes_home_override(token)


def test_an_entry_past_its_ttl_is_still_listed_at_once_and_refreshed_in_the_background(gateway, hermes, monkeypatch):
    hermes.cache('server4', ttl_ms=1000)
    path = hermes.home / 'cache' / 'mcp_schema_cache.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['server4']['written_at'] = time.time() - 3600  # an hour old with a one second ttl
    path.write_text(json.dumps(data), encoding='utf-8')
    refreshed = []
    from tools import mcp_tool_discovery as discovery
    monkeypatch.setattr(discovery, '_ensure_lazy_server_connected', lambda name: refreshed.append(name) or True)

    begin = time.perf_counter()
    answer = gateway.discover(gateway.profile, 'search server4')
    assert time.perf_counter() - begin < INSTANT and answer['total_matches'] and 'server4' in answer['sources'], 'listed at once'
    assert wait_for(lambda: refreshed), 'and refreshed behind it'
    gateway.prefetch(gateway.profile)
    gateway.prefetch(gateway.profile)
    time.sleep(0.1)
    assert refreshed == ['server4'], 'once for this entry, not again on every look'
    assert hermes.connections.log == []


# --- the same rules for a cached tool as for a live one -------------------------------------------------------------

def test_cached_tools_are_classified_exactly_as_live_ones(gateway, hermes):
    """The same tool list is registered live (a fake connected server) for one server and lazily from the cache for another;
    every field a call reads comes out the same."""
    risky = [
        {'name': 'search_mail', 'description': 'Search mail', 'inputSchema': {'type': 'object', 'properties': {'q': {'type': 'string'}}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'delete_mail', 'description': 'A server claiming a delete reads', 'inputSchema': {'type': 'object', 'properties': {}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'update_note', 'description': 'No annotation at all', 'inputSchema': {'type': 'object', 'properties': {}}},
        {'name': 'http_make_request', 'description': 'Generic request', 'annotations': {'readOnlyHint': True},
         'inputSchema': {'type': 'object', 'properties': {'url': {'type': 'string'}, 'method': {'type': 'string', 'enum': ['GET', 'POST']}}}},
        {'name': 'manage_connections', 'description': 'Hermes connector management', 'inputSchema': {'type': 'object', 'properties': {}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'get_summary', 'description': 'Bad \x1b[31mcolours\x1b[0m and a zero\u200bwidth key ' + FAKE_KEY,
         'inputSchema': {'type': 'object', 'properties': {'q': {'type': 'string', 'description': 'line one\nline two\u202e'}}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'skipped_tool', 'description': 'Excluded by the config filter', 'inputSchema': {'type': 'object', 'properties': {}},
         'annotations': {'readOnlyHint': True}},
    ]
    for trust in ('full', 'untrusted'):
        live, lazy = f'live{trust}', f'lazy{trust}'
        shared = {'trust': trust, 'tools': {'exclude': ['skipped_*']}}
        hermes.servers[live] = server_config('twin', **shared)
        hermes.servers[lazy] = server_config('twin', **shared)
        hermes.write_config()
        hermes.cache(lazy, tools=risky, config=hermes.servers[lazy])
        hermes.connections.tools[live] = risky
    native = g.NativeMcp()
    # The live twin connects through Hermes' own discovery and writes its list through to the cache; the lazy twin never connects.
    for trust in ('full', 'untrusted'):
        hermes.connections.release.set()
        from tools.mcp_tool_discovery import discover_mcp_tools
        discover_mcp_tools(allowed_mcp_names=[f'live{trust}'])
    assert native.pending_servers() == []
    rows = native.catalog(discover=False)

    def by_tool(server):
        return {row['_raw']: row for row in rows if row['_server'] == server}

    for trust in ('full', 'untrusted'):
        live, lazy = by_tool(f'live{trust}'), by_tool(f'lazy{trust}')
        assert set(live) == set(lazy) and 'skipped_tool' not in lazy, 'the config filter holds for a cached tool'
        for raw in live:
            for field in ('description', 'parameters', 'read_only', '_trust', '_timeout'):
                assert live[raw][field] == lazy[raw][field], (trust, raw, field)
        assert not lazy['delete_mail']['read_only'] and not lazy['update_note']['read_only'], 'a write verb or no hint is a write'
        assert lazy['search_mail']['read_only'] is True
        assert not lazy['http_make_request']['read_only']
        assert lazy['http_make_request']['parameters']['properties']['method']['enum'] == ['GET'], 'a generic request tool reads only'
        assert lazy['get_summary']['read_only'] is True
        assert '_connect_timeout' in lazy['get_summary'] and '_connect_timeout' not in live['get_summary']
    assert by_tool('lazyuntrusted')['search_mail']['_trust'] == 'untrusted'
    # What the call sees: Hermes' connector tools are never offered, and nothing a server wrote can reach the model unplain.
    offered = {tool['_raw'] for tool in gateway.catalog(gateway.profile, discover=False) if tool['_server'].startswith('lazy')}
    assert 'manage_connections' not in offered and 'search_mail' in offered
    answer = gateway.discover(gateway.profile, 'get summary')
    text = json.dumps(answer)
    assert FAKE_KEY[:20] not in text and '\x1b' not in text and '\u200b' not in text and '\u202e' not in text
    report = next(tool for tool in answer['tools'] if tool['name'].endswith('get_summary') and '__lazy' in tool['name'])
    assert report['parameters']['properties']['q']['description'] == 'line one line two'
    assert 'colours' in report['description'] and 'abcdefghijklmnop' not in report['description'], 'the key is masked'


def test_a_cached_tool_is_a_write_to_approve_unless_it_and_its_name_say_it_reads(gateway, hermes):
    hermes.servers['wire'] = server_config('wire', trust='untrusted')
    hermes.write_config()
    hermes.cache('wire', tools=[
        {'name': 'get_status', 'description': 'Reads', 'inputSchema': {'type': 'object', 'properties': {}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'notify_team', 'description': 'Writes', 'inputSchema': {'type': 'object', 'properties': {}},
         'annotations': {'readOnlyHint': True}},
        {'name': 'fetch_page', 'description': 'No hint', 'inputSchema': {'type': 'object', 'properties': {}}}])
    rows = {row['_raw']: row for row in g.NativeMcp().catalog(discover=False) if row['_server'] == 'wire'}
    assert rows['get_status']['read_only'] is True
    assert rows['notify_team']['read_only'] is False and rows['fetch_page']['read_only'] is False
    receipt = gateway.invoke(SCOPE, {'request_id': 'w1', 'tool_id': rows['notify_team']['tool_id'], 'arguments': {}})
    assert receipt['status'] == 'needs_approval' and hermes.connections.log == []


# --- never fewer tools than before ----------------------------------------------------------------------------------

def test_without_hermes_schema_cache_functions_discovery_is_live_as_it_was(gateway, hermes, monkeypatch):
    import tools.mcp_schema_cache as cache
    monkeypatch.delattr(cache, '_cache_path')
    discovered = []
    from tools import mcp_tool_discovery as discovery
    monkeypatch.setattr(discovery, 'discover_mcp_tools', lambda *args, **kwargs: discovered.append((args, kwargs)) or [])
    native = g.NativeMcp()

    assert g.hermes_schema_cache() is None and native.pending_servers() is None and native.settled() is False
    assert native.cache_key()[-1][0] == 'registry', 'the list follows the tool registry as before'
    native.catalog()
    assert discovered == [((), {})], 'every server is discovered live, exactly as before'
    native.catalog(discover=False)
    assert discovered == [((), {})]


@pytest.mark.parametrize('breakage', ['registration', 'garbage', 'oversized', 'wrong_shape'])
def test_a_broken_cache_never_crashes_and_leaves_servers_to_the_live_scan(gateway, hermes, monkeypatch, breakage):
    path = hermes.home / 'cache' / 'mcp_schema_cache.json'
    if breakage == 'registration':
        from tools import mcp_tool_registration as registration
        real = registration._register_from_cache_sync

        def flaky(name, config, entry):
            if name == 'server5': raise RuntimeError('this Hermes cannot register from its cache')
            return real(name, config, entry)
        monkeypatch.setattr(registration, '_register_from_cache_sync', flaky)
        expected_pending = ['server5']
    elif breakage == 'garbage':
        path.write_text('{ not json', encoding='utf-8')
        expected_pending = sorted(hermes.servers)
    elif breakage == 'oversized':
        monkeypatch.setattr(g, 'MAX_SCHEMA_CACHE_BYTES', 10)
        expected_pending = sorted(hermes.servers)
    else:
        path.write_text(json.dumps({name: {'fingerprint': 'x', 'tools': 'many'} for name in hermes.servers}), encoding='utf-8')
        expected_pending = sorted(hermes.servers)

    native = g.NativeMcp()
    assert sorted(native.pending_servers()) == expected_pending
    rows = native.catalog(discover=False)
    assert len(rows) == (SERVERS - len(expected_pending)) * TOOLS_PER_SERVER
    assert hermes.connections.log == []


def test_discovery_cannot_unregister_servers_listed_from_the_cache(gateway, hermes):
    """A live scan of the one new server must leave the rest as they were, even when Hermes' scope bookkeeping would drop them."""
    hermes.servers['newcomer'] = server_config('newcomer')
    hermes.write_config()
    hermes.connections.tools['newcomer'] = tool_entries('newcomer', 2)
    hermes.connections.release.set()
    native = g.NativeMcp()
    native.catalog()
    rows = native.catalog(discover=False)
    assert {row['_server'] for row in rows} == set(hermes.servers)
    assert hermes.connections.log == ['newcomer']


# --- the Hermes names this depends on, read from Hermes' source ---------------------------------------------------

def parsed(source, relative):
    return ast.parse((source / relative).read_text(encoding='utf-8-sig'))


def functions(tree):
    return {node.name: node for node in tree.body if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef))}


def schema_cache_contract(source):
    """Every way this Hermes' schema cache and lazy registration differ from what gateway_tools reads; empty when they match."""
    problems = []
    cache = functions(parsed(source, 'tools/mcp_schema_cache.py'))
    text = (source / 'tools/mcp_schema_cache.py').read_text(encoding='utf-8-sig')
    for name in ('_cache_path', 'config_fingerprint', 'write_cache_entry'):
        if name not in cache: problems.append(f'mcp_schema_cache.{name} is missing')
    if '_CACHE_FILENAME = "mcp_schema_cache.json"' not in text and "_CACHE_FILENAME = 'mcp_schema_cache.json'" not in text:
        problems.append('the cache file is no longer named mcp_schema_cache.json')
    path_function = ast.unparse(cache['_cache_path']) if '_cache_path' in cache else ''
    if 'get_hermes_home()' not in path_function or "'cache'" not in path_function: problems.append('the cache file is no longer under the home\'s cache directory')
    if '_cache_path' in cache and cache['_cache_path'].args.args: problems.append('_cache_path takes arguments')
    if 'config_fingerprint' in cache and [a.arg for a in cache['config_fingerprint'].args.args] != ['config']:
        problems.append('config_fingerprint no longer takes just the config')
    writer = ast.unparse(cache['write_cache_entry']) if 'write_cache_entry' in cache else ''
    for field in ("'fingerprint'", "'tools'", "'utility_tools'", "'ttl_ms'", "'written_at'"):
        if field not in writer: problems.append(f'a cache entry no longer has {field}')
    registration = functions(parsed(source, 'tools/mcp_tool_registration.py'))
    if '_register_from_cache_sync' not in registration:
        problems.append('mcp_tool_registration._register_from_cache_sync is missing')
    else:
        node = registration['_register_from_cache_sync']
        if [a.arg for a in node.args.args] != ['name', 'config', 'entry']: problems.append('_register_from_cache_sync(name, config, entry) changed')
        body = ast.unparse(node)
        for ledger in ('_lazy_server_configs', '_lazy_server_fingerprints', '_lazy_server_tool_names'):
            if ledger not in body: problems.append(f'_register_from_cache_sync no longer fills {ledger}')
        if '_record_tool_trust_metadata' not in body: problems.append('a cached registration no longer records the same trust metadata')
    discovery = functions(parsed(source, 'tools/mcp_tool_discovery.py'))
    for name, params in (('_forget_lazy_server', ['key']), ('_ensure_lazy_server_connected', ['server_name'])):
        if name not in discovery: problems.append(f'mcp_tool_discovery.{name} is missing')
        elif [a.arg for a in discovery[name].args.args] != params: problems.append(f'{name}({", ".join(params)}) changed')
    if 'discover_mcp_tools' not in discovery or 'allowed_mcp_names' not in [a.arg for a in discovery['discover_mcp_tools'].args.args]:
        problems.append('discover_mcp_tools(allowed_mcp_names) is missing')
    if '_select_new_servers' not in discovery or 'not in _core._lazy_server_configs' not in ast.unparse(discovery['_select_new_servers']):
        problems.append('discovery no longer skips a lazily registered server')
    if '_discover_and_register_server' not in ast.unparse(discovery.get('_ensure_lazy_server_connected', ast.Pass())):
        problems.append('the first call to a lazy server no longer connects just that server')
    return problems


def test_hermes_still_has_the_schema_cache_and_lazy_registration_the_tool_list_reads():
    assert schema_cache_contract(_hermes_agent_source()) == []


def test_the_contract_check_names_what_a_later_hermes_changes(tmp_path):
    source = _hermes_agent_source()
    for relative in ('tools/mcp_schema_cache.py', 'tools/mcp_tool_registration.py', 'tools/mcp_tool_discovery.py'):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text((source / relative).read_text(encoding='utf-8-sig'), encoding='utf-8')
    assert schema_cache_contract(tmp_path) == []
    cache = tmp_path / 'tools/mcp_schema_cache.py'
    cache.write_text(cache.read_text(encoding='utf-8').replace('def config_fingerprint(', 'def config_fingerprint_renamed('), encoding='utf-8')
    registration = tmp_path / 'tools/mcp_tool_registration.py'
    registration.write_text(registration.read_text(encoding='utf-8').replace(
        'def _register_from_cache_sync(name: str, config: dict, entry: dict)', 'def _register_from_cache_sync(name: str, config: dict)'), encoding='utf-8')
    problems = schema_cache_contract(tmp_path)
    assert 'mcp_schema_cache.config_fingerprint is missing' in problems
    assert '_register_from_cache_sync(name, config, entry) changed' in problems
