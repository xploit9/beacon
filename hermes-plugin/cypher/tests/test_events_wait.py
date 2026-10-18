from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
from types import ModuleType, SimpleNamespace

from test_mobile import harness


EVENTS = '/api/plugins/cypher/mobile/notifications/events'
ROOT = Path(__file__).resolve().parents[1]


def call_user_tool(api, cfg, tmp_path, monkeypatch):
    cfg['callback_tool_enabled'] = True
    db = SimpleNamespace(get_session=lambda session_id: {
        'id': session_id, 'user_id': 'basic:owner', 'title': 'Owner requested callback',
    })
    server = SimpleNamespace(
        _profile_home=lambda profile: tmp_path,
        _profile_db=lambda options: nullcontext(db),
        _sessions_lock=threading.RLock(),
        _sessions={},
    )
    tui_gateway = ModuleType('tui_gateway')
    tui_gateway.server = server
    tui_gateway.server_requests = SimpleNamespace(open_requests=lambda session_id: [])
    constants = ModuleType('hermes_constants')
    constants.get_hermes_home = lambda: tmp_path
    monkeypatch.setitem(sys.modules, 'tui_gateway', tui_gateway)
    monkeypatch.setitem(sys.modules, 'hermes_constants', constants)
    spec = importlib.util.spec_from_file_location('cypher_test_call_user', ROOT / '__init__.py')
    plugin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plugin)
    return plugin.call_user


def test_event_wait_returns_early_when_cypher_call_user_creates_invitation(harness, monkeypatch):
    api, client, cfg, tmp_path, _ = harness
    call_user = call_user_tool(api, cfg, tmp_path, monkeypatch)
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(lambda: client.get(EVENTS, params={'wait': 3}))
        time.sleep(0.2)
        invitation = json.loads(call_user({'reason': 'Please call me'}, session_id='native-stored'))
        response = pending.result(timeout=2)

    assert response.status_code == 200
    assert any(row['event_id'] == invitation['event_id'] for row in response.json()['events'])
    assert time.monotonic() - started < 3


def test_deferred_call_user_invitation_appears_after_active_call_closes(harness, monkeypatch):
    api, client, cfg, tmp_path, _ = harness
    call_user = call_user_tool(api, cfg, tmp_path, monkeypatch)
    active_call = client.post('/api/plugins/cypher/mobile/calls', json={'profile': 'default'}).json()['call_id']
    invitation = json.loads(call_user({'reason': 'Call after this session'}, session_id='native-stored'))
    assert client.get(EVENTS).json()['events'] == []

    assert client.delete(f'/api/plugins/cypher/mobile/calls/{active_call}').status_code == 200
    response = client.get(EVENTS)

    assert any(row['event_id'] == invitation['event_id'] and row['kind'] == 'incoming_call'
               for row in response.json()['events'])


def test_event_wait_times_out_empty_and_absent_wait_stays_immediate(harness):
    _, client, _, _, _ = harness
    started = time.monotonic()
    waited = client.get(EVENTS, params={'wait': 0.15})
    waited_for = time.monotonic() - started
    immediate = client.get(EVENTS)

    assert waited.status_code == 200
    assert waited.json() == {'events': [], 'next_cursor': 0}
    assert 0.1 <= waited_for < 1
    assert immediate.status_code == 200 and immediate.json() == {'events': [], 'next_cursor': 0}


def test_event_wait_is_clamped_to_zero_through_twenty_five_seconds(harness, monkeypatch):
    api, _, _, _, _ = harness
    assert api._bounded_event_wait(-1) == 0
    assert api._bounded_event_wait(0) == 0
    assert api._bounded_event_wait(30) == 25
    assert api._bounded_event_wait(float('nan')) == 0
