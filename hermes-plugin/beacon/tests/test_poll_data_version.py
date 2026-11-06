"""The notification long poll does no repeated work: it re-queries only after the database changed and shares its upkeep."""
from concurrent.futures import ThreadPoolExecutor
import time

from test_mobile import harness

EVENTS = '/api/plugins/beacon/mobile/notifications/events'


def quiet_upkeep(api, monkeypatch):
    """Upkeep stubs that count their calls; the poll's own queries stay real."""
    seen = {'watch': 0, 'history': 0, 'callbacks': 0}
    monkeypatch.setattr(api, 'watch', lambda *args: seen.__setitem__('watch', seen['watch'] + 1))
    monkeypatch.setattr(api.mobile, 'prune_history', lambda owner: seen.__setitem__('history', seen['history'] + 1))
    monkeypatch.setattr(api.sys.modules['beacon_callbacks'], 'prune_expired',
                        lambda *args: seen.__setitem__('callbacks', seen['callbacks'] + 1))
    return seen


def count_queries(api, monkeypatch):
    calls = []
    original = api._notices_after
    monkeypatch.setattr(api, '_notices_after', lambda *args: calls.append(args) or original(*args))
    return calls


def test_long_poll_skips_the_query_when_data_version_is_unchanged(harness, monkeypatch):
    api, client, _, _, _ = harness
    quiet_upkeep(api, monkeypatch)
    queries = count_queries(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.05)

    started = time.monotonic()
    response = client.get(EVENTS, params={'wait': 1.0})

    assert response.status_code == 200 and response.json() == {'events': [], 'next_cursor': 0}
    assert time.monotonic() - started >= 0.9  # it really waited about twenty ticks
    # One query for the snapshot and one right after the version was first read; the quiet ticks ran no query.
    assert len(queries) == 2


def test_long_poll_returns_a_notice_committed_while_it_waits(harness, monkeypatch):
    api, client, _, _, _ = harness
    quiet_upkeep(api, monkeypatch)
    queries = count_queries(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.05)
    started = time.monotonic()
    with ThreadPoolExecutor(max_workers=1) as pool:
        pending = pool.submit(lambda: client.get(EVENTS, params={'wait': 10}))
        time.sleep(0.5)
        quiet_queries = len(queries)
        event_id = api.mobile.invitation('owner', 'default', 'native-stored', 'Please call')
        response = pending.result(timeout=8)

    assert [row['event_id'] for row in response.json()['events']] == [event_id]
    assert time.monotonic() - started < 4
    assert quiet_queries == 2 and len(queries) == 3


def test_long_poll_sees_a_notice_committed_between_its_snapshot_and_its_first_wait(harness, monkeypatch):
    api, client, _, _, _ = harness
    quiet_upkeep(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.05)
    original = api._notices_after
    state = {'first': True, 'event': None}

    def commit_after_the_snapshot(owner, provider, profile, after, principal):
        result = original(owner, provider, profile, after, principal)
        if state['first']:
            state['first'] = False
            state['event'] = api.mobile.invitation('owner', 'default', 'native-stored', 'Raced')
        return result

    monkeypatch.setattr(api, '_notices_after', commit_after_the_snapshot)
    response = client.get(EVENTS, params={'wait': 5})

    assert [row['event_id'] for row in response.json()['events']] == [state['event']]


def test_long_poll_releases_its_connection(harness, monkeypatch):
    api, client, _, _, _ = harness
    quiet_upkeep(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.05)
    opened = []
    original = api.mobile.connect

    def tracked(*args, **kwargs):
        conn = original(*args, **kwargs)
        opened.append(conn)
        return conn

    monkeypatch.setattr(api.mobile, 'connect', tracked)
    client.get(EVENTS, params={'wait': 0.3})

    import sqlite3
    import pytest
    assert opened
    for conn in opened:
        with pytest.raises(sqlite3.ProgrammingError):
            conn.execute('SELECT 1')


def test_long_polls_share_one_upkeep_pass_a_second(harness, monkeypatch):
    api, client, _, _, _ = harness
    seen = quiet_upkeep(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.02)
    for _ in range(4):
        assert client.get(EVENTS, params={'wait': 0.05}).status_code == 200
    assert seen == {'watch': 1, 'history': 1, 'callbacks': 1}


def test_long_poll_upkeep_runs_again_after_the_shared_window(harness, monkeypatch):
    api, client, _, _, _ = harness
    seen = quiet_upkeep(api, monkeypatch)
    monkeypatch.setattr(api, 'POLL_MAINTENANCE_SECONDS', 0.05)
    monkeypatch.setattr(api, 'POLL_INTERVAL_SECONDS', 0.02)
    client.get(EVENTS, params={'wait': 0.05})
    time.sleep(0.1)
    client.get(EVENTS, params={'wait': 0.05})
    assert seen['watch'] == 2


def test_immediate_polls_always_run_the_upkeep(harness, monkeypatch):
    api, client, _, _, _ = harness
    seen = quiet_upkeep(api, monkeypatch)
    for _ in range(3):
        assert client.get(EVENTS).status_code == 200
    assert seen == {'watch': 3, 'history': 3, 'callbacks': 3}
