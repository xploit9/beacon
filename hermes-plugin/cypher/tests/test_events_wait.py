from datetime import datetime, timedelta
from concurrent.futures import ThreadPoolExecutor
from contextlib import nullcontext
import importlib.util
import json
from pathlib import Path
import sys
import threading
import time
from types import ModuleType, SimpleNamespace
from zoneinfo import ZoneInfo

from test_mobile import harness, reserve


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
    active_call = reserve(client)
    invitation = json.loads(call_user({'reason': 'Call after this session'}, session_id='native-stored'))
    assert client.get(EVENTS).json()['events'] == []

    assert client.delete(f'/api/plugins/cypher/mobile/calls/{active_call}').status_code == 200
    response = client.get(EVENTS)

    assert any(row['event_id'] == invitation['event_id'] and row['kind'] == 'incoming_call'
               for row in response.json()['events'])


REQUEST_ID = 'a' * 32


def _replay_deferred_proactive_call(api, client, cfg, monkeypatch, night):
    monkeypatch.setattr(api, 'watch', lambda *args: None)
    cfg.update(callback_tool_enabled=True, scheduled_callbacks_enabled=True,
               scheduled_callback_conversation_id='native-stored')
    setup = api.proactive.default_setup()
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'setup'), json.dumps(setup))
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'enabled'), '1')
    api.proactive._meta_set(api.mobile, api.proactive._key('owner', 'default', 'setup_complete'), '1')
    rule = {'id': REQUEST_ID, 'text': 'Client review moved', 'action': 'call', 'night': night,
            'enabled': True, 'signal': 'email'}
    monkeypatch.setattr(api.proactive, 'list_requests', lambda *args: [rule])
    active_call = reserve(client)
    now = datetime(2026, 10, 20, 12, 0, tzinfo=ZoneInfo('America/Los_Angeles'))
    observers = api.sys.modules['cypher_observers']
    monkeypatch.setattr(observers, 'time', SimpleNamespace(time=lambda: now.timestamp()))
    assert observers.source_observed(api.mobile, cfg, 'default', 'watch-run', 'mcp__mail__read_email',
                                     {'id': 'message-1'}, 'ok')
    alert = observers.email_notice(api.mobile, api.native, cfg, 'default', 'watch-run', {
        'request_id': REQUEST_ID, 'item_id': 'message-1', 'reason': 'An urgent update',
    }, now)
    invitation = alert['event_id']
    with api.mobile.connect() as conn:
        reason = conn.execute('SELECT reason FROM deferred WHERE id=?', (invitation,)).fetchone()['reason']
    quiet_now = datetime(2026, 10, 20, 23, 0, tzinfo=ZoneInfo('America/Los_Angeles'))
    monkeypatch.setattr(api.proactive, '_setup', lambda *args: {'quiet_start': '22:00', 'quiet_end': '07:00'})
    monkeypatch.setattr(api.proactive, 'profile_now', lambda profile: quiet_now)
    assert client.delete(f'/api/plugins/cypher/mobile/calls/{active_call}').status_code == 200
    api.activate('owner', 'default')
    return invitation, reason


def test_deferred_proactive_call_replays_as_notice_inside_quiet_hours(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    invitation, reason = _replay_deferred_proactive_call(api, client, cfg, monkeypatch, night=False)

    with api.mobile.connect() as conn:
        notice = conn.execute('SELECT kind,reason FROM notices WHERE id!=? AND conversation=? ORDER BY seq DESC LIMIT 1',
                              (invitation, 'native-stored')).fetchone()
        assert notice['kind'] == 'task_result' and notice['reason'] == reason
        assert conn.execute('SELECT 1 FROM deferred WHERE id=?', (invitation,)).fetchone() is None


def test_deferred_night_request_still_rings_inside_quiet_hours(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    invitation, _ = _replay_deferred_proactive_call(api, client, cfg, monkeypatch, night=True)

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT kind FROM notices WHERE id=?", (invitation,)).fetchone()['kind'] == 'incoming_call'


def test_missed_scheduled_callback_posts_notice_before_delete(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    cfg['scheduled_callbacks_enabled'] = True
    target = datetime(2026, 10, 20, 12, 0, tzinfo=ZoneInfo('America/Los_Angeles'))
    event = 'c' * 64
    key = 'scheduled-callback:' + event
    record = {
        'owner': 'owner', 'provider': 'basic', 'profile': 'default', 'conversation': 'native-stored',
        'scheduled_at': target.isoformat(), 'reason': 'Client moved the review',
        'request_id': 'request', 'status': 'scheduled',
    }
    with api.mobile.connect() as conn:
        conn.execute('INSERT INTO meta(key,value) VALUES(?,?)', (key, json.dumps(record).encode()))
    active_call = reserve(client)
    callbacks = api.sys.modules['cypher_callbacks']
    assert callbacks.deliver(api.mobile, api.native, key, target)['status'] == 'pending'
    activate = api.activate
    monkeypatch.setattr(api, 'activate', lambda *args: None)
    assert client.delete(f'/api/plugins/cypher/mobile/calls/{active_call}').status_code == 200
    missed_at = target + timedelta(seconds=callbacks.CALLBACK_WINDOW_SECONDS + 1)
    monkeypatch.setattr(api, 'datetime', SimpleNamespace(now=lambda tz: missed_at))
    notices = []
    observers = callbacks.observers
    create_notice = observers._notice

    def observe_notice(*args, **kwargs):
        with api.mobile.connect() as conn:
            assert conn.execute('SELECT 1 FROM deferred WHERE id=?', (event,)).fetchone()
        notices.append(args[4])
        return create_notice(*args, **kwargs)

    monkeypatch.setattr(observers, '_notice', observe_notice)

    activate('owner', 'default')

    assert notices == ['Missed scheduled callback: Client moved the review']
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT 1 FROM deferred WHERE id=?', (event,)).fetchone() is None
        notice = conn.execute('SELECT kind,reason FROM notices WHERE reason LIKE ?',
                              ('Missed scheduled callback:%',)).fetchone()
        assert notice['kind'] == 'task_result'
        assert notice['reason'] == notices[0]


def test_event_long_poll_does_not_rescan_native_activity(harness, monkeypatch):
    api, client, _, _, _ = harness
    watch_calls, history_prunes, callback_prunes, after_calls, disconnect_calls = [], [], [], [], []
    monkeypatch.setattr(api, 'watch', lambda *args: watch_calls.append(args))
    monkeypatch.setattr(api.mobile, 'prune_history', lambda owner: history_prunes.append(owner))
    callbacks = api.sys.modules['cypher_callbacks']
    monkeypatch.setattr(callbacks, 'prune_expired', lambda *args: callback_prunes.append(args))
    original_after = api._notices_after
    monkeypatch.setattr(api, '_notices_after', lambda *args: after_calls.append(args) or original_after(*args))
    original_sleep = api.asyncio.sleep

    async def quick_sleep(delay):
        await original_sleep(min(delay, 0.01))

    async def disconnected(request):
        disconnect_calls.append(True)
        return True

    monkeypatch.setattr(api.asyncio, 'sleep', quick_sleep)
    monkeypatch.setattr(api.Request, 'is_disconnected', disconnected)

    response = client.get(EVENTS, params={'wait': 0.08})

    assert response.status_code == 200 and response.json()['events'] == []
    assert len(watch_calls) == len(history_prunes) == len(callback_prunes) == 1
    assert len(after_calls) == 2 and len(disconnect_calls) == 1


def test_event_page_reads_reference_key_once(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api, 'watch', lambda *args: None)
    monkeypatch.setattr(api.mobile, 'prune_history', lambda owner: 0)
    monkeypatch.setattr(api.sys.modules['cypher_callbacks'], 'prune_expired', lambda *args: 0)
    rows = [
        {'id': f'event-{index}', 'owner': 'owner', 'profile': 'default', 'kind': 'incoming_call',
         'conversation': f'chat-{index}', 'created': time.time(), 'expires': time.time() + 120,
         'state': 'pending', 'reason': '', 'seq': index}
        for index in (1, 2)
    ]
    monkeypatch.setattr(api, '_notices_after', lambda *args: (rows, False, 2))
    original_connect = api.mobile.connect
    connections = []

    def counted_connect(*args, **kwargs):
        connections.append(True)
        return original_connect(*args, **kwargs)

    monkeypatch.setattr(api.mobile, 'connect', counted_connect)

    response = client.get(EVENTS)

    assert response.status_code == 200
    events = response.json()['events']
    assert len(events) == 2 and events[0]['source_ref'] == events[1]['source_ref']
    assert events[0]['conversation_ref'] != events[1]['conversation_ref']
    assert len(connections) == 1


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


def test_outbox_sequence_reset_replays_events_from_zero(harness):
    api, client, _, _, _ = harness
    now = time.time()
    with api.mobile.connect() as conn:
        conn.execute(
            "INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)",
            ('reset-task-result', 'owner', 'default', 'task_result', 'native-stored', now, now + 300, 'fixture result'),
        )
        seq = conn.execute("SELECT seq FROM notices WHERE id='reset-task-result'").fetchone()[0]

    response = client.get(EVENTS, params={'after': seq + 10})

    assert response.status_code == 200
    page = response.json()
    assert page['reset'] is True
    assert [event['event_id'] for event in page['events']] == ['reset-task-result']
    assert page['next_cursor'] == seq


def test_event_wait_is_clamped_to_zero_through_twenty_five_seconds(harness, monkeypatch):
    api, _, _, _, _ = harness
    assert api._bounded_event_wait(-1) == 0
    assert api._bounded_event_wait(0) == 0
    assert api._bounded_event_wait(30) == 25
    assert api._bounded_event_wait(float('nan')) == 0
