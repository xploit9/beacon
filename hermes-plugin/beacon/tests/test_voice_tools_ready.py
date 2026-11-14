"""A call finds its tools ready (owner trial, 7 Nov 2026).

The owner's Hermes serves about 280 MCP tools; the first tool lookup after the catalog went cold built it on the request path and
missed the phone's hard 15 s cap, so the call said "Discover tools failed". These tests use an owner-sized synthetic catalogue whose
build is slow. Synthetic names only.
"""
import asyncio
import importlib.util
import logging
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from test_gateway_tools import ROOT, Mobile, g
from test_mobile import harness  # noqa: F401  (the dashboard module, as the watcher tests build it)

OWNER_TOOLS = 280


def owner_rows(count=OWNER_TOOLS):
    rows = []
    for index in range(count):
        server = f'server{index % 5}'
        verb = 'search' if index % 2 else 'delete'
        rows.append({'tool_id': f'{index:064x}', 'name': f'mcp__{server}__{verb}_mail_{index}', '_raw': f'{verb}_mail_{index}',
                     '_server': server, 'description': f'{verb} mail number {index} on {server}',
                     'parameters': {'type': 'object', 'properties': {'q': {'type': 'string'}}}, 'read_only': bool(index % 2)})
    return rows


class SlowHermes:
    """get_tool_definitions on the owner's Hermes: a cold build that outlasts the phone's patience."""
    def __init__(self, build_seconds):
        self.build_seconds = build_seconds
        self.builds = 0
        self.rows = owner_rows()

    def cache_key(self): return 'owner-sized'
    def settled(self): return True

    def catalog(self, discover=True):
        time.sleep(self.build_seconds)
        self.builds += 1
        return self.rows


def gateway(tmp_path, build_seconds):
    backend = SlowHermes(build_seconds)
    return g.Gateway(Mobile(tmp_path / 'ready.sqlite'), lambda profile, fn: fn(tmp_path), backend), backend


def wait_for(check, limit=5):
    end = time.monotonic() + limit
    while time.monotonic() < end:
        if check(): return True
        time.sleep(0.01)
    return False


def test_a_cold_discover_answers_still_starting_inside_the_plugin_deadline_then_warm(tmp_path, monkeypatch):
    monkeypatch.setattr(g, 'DISCOVER_LIST_WAIT', 0.3)
    api, backend = gateway(tmp_path, 1.0)
    profile = 'cold-' + str(time.time_ns())

    begin = time.monotonic()
    answer = api.discover(profile, 'search mail')
    assert time.monotonic() - begin < 0.3 + 0.6, 'the plugin answers inside its own deadline'
    assert answer['status'] == 'starting' and answer['tools'] == [] and answer['total_matches'] == 0, 'no error, no invented list'

    assert wait_for(lambda: backend.builds == 1), 'the build goes on behind the answer'
    g.DISCOVER_LOOKUPS[profile][0].join(2)
    begin = time.monotonic()
    warm = api.discover(profile, 'search mail')
    assert time.monotonic() - begin < 0.3 and 'status' not in warm
    assert warm['tools'] and backend.builds == 1, 'the next look is warm and builds nothing again'


def test_the_plugin_deadline_is_clearly_below_the_phones_fifteen_seconds():
    assert g.DISCOVER_LIST_WAIT <= 5.0


def test_a_settled_list_past_its_refresh_age_is_served_at_once_and_rebuilt_behind(tmp_path):
    api, backend = gateway(tmp_path, 0.4)
    profile = 'refresh-' + str(time.time_ns())
    api.prefetch(profile)
    key = api._catalog_key(profile)
    assert key in g.CATALOG_SETTLED and backend.builds == 1
    # Past the age at which a complete list is rebuilt, but far inside the age at which it is dropped.
    with g.CATALOG_LOCK:
        created, held = g.CATALOG_CACHE[key]
        g.CATALOG_CACHE[key] = (created - g.CATALOG_REFRESH - 5, held)
    begin = time.perf_counter()
    rows = api.catalog(profile, discover=False)
    assert time.perf_counter() - begin < 0.2 and len(rows) == OWNER_TOOLS, 'a call never waits for the rebuild'
    assert wait_for(lambda: backend.builds == 2), 'and the list is rebuilt behind it, before it can go stale'
    assert wait_for(lambda: time.monotonic() - g.CATALOG_CACHE[key][0] < g.CATALOG_REFRESH)


def test_the_background_refresh_runs_before_the_list_can_be_dropped(harness):
    api = harness[0]
    assert g.CATALOG_REFRESH < g.CATALOG_STALE_TTL
    assert api.TOOLS_REFRESH_SECONDS + g.CATALOG_REFRESH < g.CATALOG_STALE_TTL


@pytest.mark.asyncio
async def test_the_dashboard_warms_the_call_tools_first_and_never_waits_for_them(harness, monkeypatch):
    """After a restart the call catalogue is built in the background at once, before the watcher's slower first pass."""
    api, _, cfg, _, _ = harness
    cfg['profiles'] = ['first', 'second']
    monkeypatch.setattr(api.mobile, 'settings', lambda profile=None: cfg)
    monkeypatch.setattr(api, '_warm_profile', lambda *_args: None)
    monkeypatch.setattr(api, 'push', lambda *_args: None)
    events = []
    release = threading.Event()

    def watch(owner, provider, profile):
        events.append(('watch', profile))
        release.wait(2)  # the first pass of the watcher is slow on a big state database

    built = []

    def slow_prefetch(profile):
        time.sleep(1.0)  # an owner-sized cold build
        built.append(profile)
        return 'Hermes: read file'

    gateway = SimpleNamespace(Gateway=lambda mobile, scoped: SimpleNamespace(prefetch=slow_prefetch))
    monkeypatch.setitem(sys.modules, 'beacon_gateway_tools', gateway)
    real = api.voice_context.prefetch_tools
    monkeypatch.setattr(api.voice_context, 'prefetch_tools', lambda profile: events.append(('prefetch', profile)) or real(profile))
    monkeypatch.setattr(api, 'watch', watch)
    api._tools_next.clear()
    api.voice_context.TOOLS_WARMING.clear()
    begin = time.monotonic()
    await api.start_watcher()
    try:
        assert time.monotonic() - begin < 0.5, 'starting the dashboard never waits for the warm-up'
        assert await asyncio.to_thread(wait_for, lambda: ('watch', 'first') in events, 3)
        assert events[:2] == [('prefetch', 'first'), ('prefetch', 'second')], f'both profiles are warmed before any watch runs: {events}'
        assert await asyncio.to_thread(wait_for, lambda: sorted(built) == ['first', 'second'], 4), 'and both builds finish in the background'
    finally:
        release.set()
        await api.stop_watcher()


def test_a_cache_entry_with_ttl_zero_is_never_marked_stale():
    old = time.time() - 3600
    assert g.schema_entry_expired({'ttl_ms': 0, 'written_at': old}) is False, 'a server that gave no lifetime is not always stale'
    assert g.schema_entry_expired({'ttl_ms': -5, 'written_at': old}) is False
    assert g.schema_entry_expired({'ttl_ms': 1000, 'written_at': old}) is True
    assert g.schema_entry_expired({'ttl_ms': 1000, 'written_at': time.time()}) is False
    assert g.schema_entry_expired({'written_at': old}) is False


@pytest.fixture
def route(tmp_path, monkeypatch):
    spec = importlib.util.spec_from_file_location('voice_ready_gateway_api', ROOT / 'gateway_api.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    core = sys.modules['beacon_gateway_tools']
    backend = SlowHermes(1.0)
    dispatcher = core.Gateway(Mobile(tmp_path / 'route.sqlite'), lambda profile, fn: fn(tmp_path), backend)
    monkeypatch.setattr(core, 'Gateway', lambda *a, **k: dispatcher)
    pace = {'authorise': 0.0, 'profile': None}

    async def call(request, call_id, writable=False):
        await asyncio.sleep(pace['authorise'])
        return 'owner', 'password', {'profile': pace['profile'] or 'route-' + str(time.time_ns()), 'conversation': 'stored'}, {}

    async def body(request, limit): return await request.json()
    app = FastAPI()
    app.include_router(module.build_router(call, body, lambda p, fn: fn(tmp_path), Mobile(tmp_path / 'route.sqlite')))
    yield TestClient(app), core, backend, pace
    wait_for(lambda: not any(t.is_alive() for t, _ in list(core.DISCOVER_LOOKUPS.values())), 5)


def test_the_discover_route_answers_a_cold_catalogue_with_still_starting_not_an_error(route, monkeypatch):
    client, core, backend, pace = route
    monkeypatch.setattr(core, 'DISCOVER_LIST_WAIT', 0.3)
    begin = time.monotonic()
    response = client.get('/mobile/calls/call/tools', params={'query': 'search mail'})
    assert response.status_code == 200, response.text
    assert response.json()['status'] == 'starting' and response.json()['tools'] == []
    assert time.monotonic() - begin < 1.0


def test_the_deadline_is_counted_from_the_start_of_the_request_not_from_the_lookup(route, monkeypatch):
    client, core, backend, pace = route
    monkeypatch.setattr(core, 'DISCOVER_LIST_WAIT', 1.0)
    pace['authorise'] = 0.8  # a slow authorisation already spent most of the budget
    begin = time.monotonic()
    response = client.get('/mobile/calls/call/tools', params={'query': 'search mail'})
    elapsed = time.monotonic() - begin
    assert response.status_code == 200 and response.json()['status'] == 'starting'
    assert elapsed < 1.45, f'the whole request stays near its one deadline, took {elapsed:.2f}s'


def test_each_discover_logs_content_free_stage_timers(route, monkeypatch, caplog):
    client, core, backend, pace = route
    monkeypatch.setattr(core, 'DISCOVER_LIST_WAIT', 0.3)
    caplog.set_level(logging.INFO)
    assert client.get('/mobile/calls/call/tools', params={'query': 'private-query-marker mail'}).status_code == 200
    lines = [r.getMessage() for r in caplog.records if 'discover stages' in r.getMessage()]
    assert len(lines) == 1, caplog.text
    for stage in ('authorise=', 'scope=', 'catalog=', 'response=', 'total='):
        assert stage in lines[0], lines[0]
    # The test client's own request line (httpx) names the URL; what the plugin logs must not.
    ours = ' '.join(r.getMessage() for r in caplog.records if not r.name.startswith(('httpx', 'httpcore')))
    assert 'private-query-marker' not in ours and 'mail_' not in ours and 'route-' not in ours, 'timers carry no query, tool or profile text'


def test_a_slow_call_authorisation_logs_which_step_took_the_time(harness, monkeypatch, caplog):
    """Owner trial, 7 Nov 2026: about 11 s went missing before the tool lookup began; nothing said which step held them."""
    api = harness[0]
    monkeypatch.setattr(api, 'SLOW_AUTHORISE_SECONDS', 0.05)

    class Request:
        query_params = {}

    class Connection:
        def __enter__(self): return self
        def __exit__(self, *args): return False
        def execute(self, *args): return None

    monkeypatch.setattr(api, 'identity', lambda request, profile=None, owner_only=False: ('owner', 'password', 'profile', {}))
    monkeypatch.setattr(api, 'voice_principal', lambda *args: '')
    monkeypatch.setattr(api.mobile, 'connect', lambda: Connection())
    monkeypatch.setattr(api.mobile, 'owned', lambda *args: {'profile': 'profile', 'conversation': 'stored', 'closed': True, 'principal': ''})
    monkeypatch.setattr(api, 'reject_unowned_conversation', lambda *args: time.sleep(0.2))
    monkeypatch.setattr(api.native, 'verify', lambda *args, **kwargs: None)
    caplog.set_level(logging.WARNING)
    asyncio.run(api.call(Request(), 'private-call-marker'))
    lines = [r.getMessage() for r in caplog.records if 'authorisation was slow' in r.getMessage()]
    assert len(lines) == 1, caplog.text
    for step in ('identity=', 'call_row=', 'profile=', 'ownership=', 'verify='):
        assert step in lines[0], lines[0]
    import re
    assert float(re.search(r'ownership=([0-9.]+)s', lines[0]).group(1)) >= 0.19, 'the slow step is the one named'
    assert 'private-call-marker' not in caplog.text


# --- Ponytail review, 7 Nov 2026 -----------------------------------------------------------------------------------------------

def test_a_tool_list_that_never_finishes_stops_being_called_still_starting_and_fails_so_the_model_hands_off(tmp_path, monkeypatch):
    """Finding 7: a hung MCP server must not leave every search answering 'starting' for ever; after its age limit the answer is the
    gateway timeout the phone turns into 'call hermes_work now', and a build that finishes later still ends in a warm list."""
    from fastapi import HTTPException
    monkeypatch.setattr(g, 'DISCOVER_LIST_WAIT', 0.1)
    monkeypatch.setattr(g, 'DISCOVER_STARTING_LIMIT', 0.5)
    api, backend = gateway(tmp_path, 1.5)
    profile = 'stuck-' + str(time.time_ns())
    assert api.discover(profile, 'search mail')['status'] == 'starting', 'inside its age limit a cold list is still starting'
    time.sleep(0.6)
    begin = time.monotonic()
    with pytest.raises(HTTPException) as error:
        api.discover(profile, 'search mail')
    assert error.value.status_code == 504
    assert time.monotonic() - begin < 0.3, 'an aged-out lookup is answered at once, not waited for again'
    g.DISCOVER_LOOKUPS[profile][0].join(5)
    warm = api.discover(profile, 'search mail')
    assert warm['tools'] and 'status' not in warm, 'the build that finished behind the failure leaves a warm list'


def test_an_aged_out_lookup_with_a_last_good_list_serves_that_list_at_once(tmp_path, monkeypatch):
    monkeypatch.setattr(g, 'DISCOVER_LIST_WAIT', 1.0)
    monkeypatch.setattr(g, 'DISCOVER_STARTING_LIMIT', 0.3)
    api, backend = gateway(tmp_path, 2.0)
    profile = 'aged-' + str(time.time_ns())
    with g.CATALOG_LOCK: g.LAST_CATALOG[profile] = owner_rows(5)
    assert api.discover(profile, 'search mail')['tools'], 'a lookup inside its limit still gets the normal wait, then the last list'
    time.sleep(0.4)
    begin = time.monotonic()
    again = api.discover(profile, 'search mail')
    assert again['tools'] and 'status' not in again
    assert time.monotonic() - begin < 0.4, 'a stuck lookup is not waited for on every search'
    g.DISCOVER_LOOKUPS[profile][0].join(5)


def test_the_route_answers_an_aged_out_lookup_with_a_gateway_timeout(route, monkeypatch):
    client, core, backend, pace = route
    monkeypatch.setattr(core, 'DISCOVER_LIST_WAIT', 0.1)
    monkeypatch.setattr(core, 'DISCOVER_STARTING_LIMIT', 0.4)
    pace['profile'] = 'route-aged-' + str(time.time_ns())
    first = client.get('/mobile/calls/call/tools', params={'query': 'search mail'})
    assert first.status_code == 200 and first.json()['status'] == 'starting'
    time.sleep(0.5)
    second = client.get('/mobile/calls/call/tools', params={'query': 'search mail'})
    assert second.status_code == 504, second.text


# What the phone waits for each call route (DirectVoice.kt): the plugin must answer, or log its own timeout, clearly sooner.
PHONE_CAPS = {'discover': 15, 'invoke': 20, 'decision': 20, 'status': 8, 'cancel': 8}


def test_every_gateway_route_has_a_plugin_deadline_clearly_below_what_the_phone_waits(route, monkeypatch):
    """Finding 10: a status or cancel read waited 15 s in the plugin while the phone gives up at 8 s, so the plugin's own timeout
    (and its log line) never arrived. Search 10 s (phone 15), invoke and decision 15 s (phone 20), receipts 5 s (phone 8)."""
    client, core, backend, pace = route
    dispatcher = core.Gateway()
    for name in ('invoke', 'status', 'decide', 'cancel'):
        monkeypatch.setattr(dispatcher, name, lambda *a, **k: {'status': 'ok'})
    monkeypatch.setattr(dispatcher, 'discover', lambda *a, **k: {'tools': []})
    seen = []
    original = asyncio.wait_for

    async def recording(task, timeout):
        seen.append(timeout)
        return await original(task, timeout)
    monkeypatch.setattr(asyncio, 'wait_for', recording)
    proposal = 'p' * 40
    requests = {
        'discover': lambda: client.get('/mobile/calls/call/tools'),
        'invoke': lambda: client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'r1', 'tool_id': 'a' * 64, 'arguments': {}}),
        'decision': lambda: client.post('/mobile/calls/call/tools/requests/r1/decision', json={'proposal_id': proposal, 'decision': 'deny'}),
        'status': lambda: client.get('/mobile/calls/call/tools/requests/r1'),
        'cancel': lambda: client.post('/mobile/calls/call/tools/requests/r1/cancel', json={}),
    }
    for route_name, send in requests.items():
        seen.clear()
        response = send()
        assert response.status_code == 200, (route_name, response.text)
        limit = [t for t in seen if isinstance(t, (int, float)) and t in range(1, 30)]
        assert limit, (route_name, seen)
        assert limit[0] <= PHONE_CAPS[route_name] - 3, f'{route_name}: the plugin waits {limit[0]} s, the phone {PHONE_CAPS[route_name]} s'
