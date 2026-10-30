import sys
import threading
from types import SimpleNamespace

import pytest

from test_mobile import harness, reserve


def add_job(api, client, monkeypatch, *, callback=False, history=None):
    call_id = reserve(client)
    history = history or {'messages': [{'role': 'user', 'row_id': 10, 'text': 'prompt'}]}
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: history)
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'request', 'title': 'Fixture job', 'user_row_id': 10, 'callback': callback,
    })
    assert response.status_code == 200, response.text
    return call_id, response.json()['job_id']


def install_replay(monkeypatch, events):
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {}})
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: events)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))


def job_row(api, job_id):
    with api.mobile.connect() as conn:
        return dict(conn.execute('SELECT * FROM jobs WHERE id=?', (job_id,)).fetchone())


def test_watch_checks_liveness_without_reading_full_history(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch)
    install_replay(monkeypatch, [])
    calls = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: calls.append(args) or {'user_id': 'basic:owner'})

    api.watch('owner', 'basic', 'default')

    assert calls == [('owner', 'basic', 'default', 'native-stored', 'runtime', False)]
    assert job_row(api, job_id)['status'] == 'watching'


def test_watch_accepts_native_row_id_fallback_for_final_assistant(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch, history={
        'messages': [{'role': 'user', '_row_id': 10, 'content': 'prompt'}],
    })
    events = [{'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 10, 'complete': True, 'final_assistant_row_id': 11},
    }}]
    install_replay(monkeypatch, events)
    def verify(*args):
        return {'user_id': 'basic:owner'} if args[-1] is False else {
            'messages': [{'role': 'assistant', '_row_id': 11, 'content': 'row id fallback result'}],
        }
    monkeypatch.setattr(api.native, 'verify', verify)

    api.watch('owner', 'basic', 'default')

    row = job_row(api, job_id)
    assert row['status'] == 'completed' and row['result'] == 'row id fallback result'


def test_watch_does_not_downgrade_a_concurrent_job_promotion(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch, callback=True)
    install_replay(monkeypatch, [])
    def verify(*args):
        with api.mobile.LOCK, api.mobile.connect() as conn:
            conn.execute("UPDATE jobs SET status='callback_pending' WHERE id=?", (job_id,))
        return {'user_id': 'basic:owner'}
    monkeypatch.setattr(api.native, 'verify', verify)

    api.watch('owner', 'basic', 'default')

    assert job_row(api, job_id)['status'] == 'callback_pending'


def test_watch_does_not_duplicate_callback_notice_after_concurrent_completion(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, job_id = add_job(api, client, monkeypatch, callback=True)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    events = [{'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 10, 'complete': True, 'final_assistant_row_id': 11},
    }}]
    install_replay(monkeypatch, events)
    def verify(*args):
        with api.mobile.LOCK, api.mobile.connect() as conn:
            conn.execute("UPDATE jobs SET status='callback_pending' WHERE id=?", (job_id,))
            conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,job_id,reason) VALUES(?,?,?,?,?,?,?,?,?)',
                         ('already-promoted', 'owner', 'default', 'incoming_call', 'native-stored', 1, 9999999999, job_id, 'Fixture job'))
        return {'user_id': 'basic:owner'} if args[-1] is False else {
            'messages': [{'role': 'assistant', 'row_id': 11, 'content': 'done'}],
        }
    monkeypatch.setattr(api.native, 'verify', verify)

    api.watch('owner', 'basic', 'default')

    assert job_row(api, job_id)['status'] == 'callback_pending'
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE job_id=? AND kind='incoming_call'", (job_id,)).fetchone()[0] == 1


def test_missing_native_session_settles_the_watched_job(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch)
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(LookupError('missing')))

    api.watch('owner', 'basic', 'default')

    row = job_row(api, job_id)
    assert row['status'] == 'failed' and 'no longer exists' in row['error']


def test_watch_refusal_logs_only_known_stage_and_reason(harness, monkeypatch, caplog):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch)
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(LookupError('private fixture detail')))
    with caplog.at_level('WARNING'):
        api.watch('owner', 'basic', 'default')
    assert 'Beacon native watch verification refused: stage=binding reason=unclassified' in caplog.text
    assert 'private' not in caplog.text
    assert job_row(api, job_id)['status'] == 'failed'


def test_watch_refusal_retains_bounded_diagnostic_without_changing_public_error(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch)
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(LookupError('private fixture detail')))
    api.watch('owner', 'basic', 'default')
    row = dict(job_row(api, job_id))
    assert row['error'] == api._MISSING_SESSION_ERROR + '\nbinding:unclassified'
    result = api.job_status(row)
    assert result['status'] == 'uncertain' and result['error_code'] == 'native_turn_unconfirmed'
    assert result['error'] == api._MISSING_SESSION_ERROR
    assert 'private' not in str(result)


def test_fast_followup_links_after_native_completion_without_poll(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    history = {'messages': [
        {'role': 'user', 'row_id': 10}, {'role': 'assistant', 'row_id': 11, 'content': 'first result'},
        {'role': 'user', 'row_id': 12}, {'role': 'assistant', 'row_id': 13, 'content': 'second result'},
    ]}
    monkeypatch.setattr(api.native, 'verify', lambda *args: history)
    events = [{'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 10, 'complete': True, 'final_assistant_row_id': 11},
    }}]
    install_replay(monkeypatch, events)
    url = f'/api/plugins/beacon/mobile/calls/{call_id}'
    linked = client.post(url + '/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'second', 'user_row_id': 12,
    })
    assert linked.status_code == 200, linked.text
    second = linked.json()['job_id']
    events.append({'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 12, 'complete': True, 'final_assistant_row_id': 13},
    }})
    jobs = {item['job_id']: item for item in client.get(url).json()['jobs']}
    assert (jobs[first]['status'], jobs[first]['result']) == ('done', 'first result')
    assert (jobs[second]['status'], jobs[second]['result']) == ('done', 'second result')


def test_lost_replay_is_unconfirmed_and_cannot_trigger_success_callback(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch, callback=True)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-two', events_since=lambda *_: [],
                             is_truncated=lambda *_: False)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {'running': False, 'history_lock': threading.RLock()}})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    before = job_row(api, first)
    api.watch('owner', 'basic', 'default')
    row = job_row(api, first)
    assert row == before
    assert not row['result']
    visible = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert visible['status'] == 'uncertain' and visible['error_code'] == 'native_turn_unconfirmed'
    assert 'result' not in visible
    assert not visible.get('will_continue', False)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id=?', (first,)).fetchone()[0] == 0


def test_truncated_replay_unblocks_next_job_without_fabricating_result(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch, callback=True)
    history = {'messages': [{'role': 'user', 'row_id': 10}, {'role': 'user', 'row_id': 12}]}
    monkeypatch.setattr(api.native, 'verify', lambda *args: history)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [],
                             is_truncated=lambda *_: True)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {'running': False, 'history_lock': threading.RLock()}})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'second', 'user_row_id': 12,
    })
    assert response.status_code == 200, response.text
    row = job_row(api, first)
    assert row['status'] == 'failed' and 'unconfirmed' in row['error'].lower()
    assert not row['result']
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id=?', (first,)).fetchone()[0] == 0


def test_truncated_replay_does_not_end_active_matching_turn(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [],
                             is_truncated=lambda *_: True)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': True, 'history_lock': threading.RLock(), '_submit_user_row': {'_row_id': 10},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_hermes_job_must_belong_to_call_conversation_even_if_native_owned(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute("UPDATE calls SET voice_config=? WHERE id=?", ('{"backend":"hermes"}', call_id))
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': [{'role': 'user', 'row_id': 10}]})
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'another-owned-conversation', 'runtime_session_id': 'runtime',
        'request_id': 'other', 'user_row_id': 10,
    })
    assert response.status_code == 409
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE call_id=?', (call_id,)).fetchone()[0] == 0


def test_missing_terminal_on_idle_native_session_is_unconfirmed(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [],
                             is_truncated=lambda *_: False)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(),
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    row = job_row(api, first)
    assert row['status'] == 'failed' and 'unconfirmed' in row['error'].lower()


def test_followup_link_survives_previous_unresolved_watch_while_runtime_busy(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    history = {'messages': [{'role': 'user', 'row_id': 10}, {'role': 'user', 'row_id': 12}]}
    monkeypatch.setattr(api.native, 'verify', lambda *args: history)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [],
                             is_truncated=lambda *_: True)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': True, 'history_lock': threading.RLock(), '_submit_user_row': {'_row_id': 12},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'second', 'user_row_id': 12,
    })
    assert response.status_code == 200, response.text
    assert job_row(api, first)['status'] == 'watching'
    assert job_row(api, response.json()['job_id'])['seq'] == 12


def test_job_request_id_cannot_be_reused_for_another_user_row(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, _ = add_job(api, client, monkeypatch)
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': [{'role': 'user', 'row_id': 12}]})
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'request', 'user_row_id': 12,
    })
    assert response.status_code == 409


def test_idle_transition_with_native_queue_does_not_settle_unconfirmed(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [],
                             is_truncated=lambda *_: True)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(), 'queued_prompt': {'text': 'queued'},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_unavailable_final_row_is_unconfirmed_after_idle(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch, callback=True)
    events = [{'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 10, 'final_assistant_row_id': 11},
    }}]
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: events,
                             is_truncated=lambda *_: False)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(),
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    row = job_row(api, first)
    assert row['status'] == 'failed' and 'unconfirmed' in row['error'].lower()
    assert not row['result']


def test_legacy_direct_job_can_link_another_owned_conversation(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': [{'role': 'user', 'row_id': 10}]})
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'another-owned-conversation', 'runtime_session_id': 'runtime',
        'request_id': 'other', 'user_row_id': 10,
    })
    assert response.status_code == 200, response.text


def test_job_rechecks_backend_binding_after_native_verification(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    install_replay(monkeypatch, [])
    def verify(*args):
        with api.mobile.LOCK, api.mobile.connect() as conn:
            conn.execute("UPDATE calls SET voice_config=? WHERE id=?", ('{"backend":"hermes"}', call_id))
        return {'messages': [{'role': 'user', 'row_id': 10}]}
    monkeypatch.setattr(api.native, 'verify', verify)
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'another-owned-conversation', 'runtime_session_id': 'runtime',
        'request_id': 'other', 'user_row_id': 10,
    })
    assert response.status_code == 409


def test_reconciliation_failure_does_not_orphan_accepted_native_job(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': [{'role': 'user', 'row_id': 10}]})
    monkeypatch.setattr(api, 'watch', lambda *args: (_ for _ in ()).throw(RuntimeError('unavailable')))
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'request', 'user_row_id': 10,
    })
    assert response.status_code == 200, response.text


def test_proven_native_failure_remains_failed_not_uncertain(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    events = [{'type': 'message.complete', 'payload': {
        'status': 'error', 'persisted_turn': {'user_row_id': 10},
    }}]
    install_replay(monkeypatch, events)
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    job = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert job['job_id'] == first and job['status'] == 'failed'
    assert 'error_code' not in job and 'unconfirmed' not in job['error'].lower()


def test_lazy_child_watch_with_active_parent_is_not_unconfirmed(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    checks = []
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'lazy': True, 'session_key': 'native-stored', 'profile_home': 'profile-home',
        'history_lock': threading.RLock(), 'inflight_turn': None,
    }}, _child_run_active=lambda key, home: checks.append((key, home)) or True)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_lazy_child_watch_without_liveness_helper_stays_unconfirmed_pending(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'lazy': True, 'session_key': 'native-stored', 'history_lock': threading.RLock(),
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_stale_lazy_child_liveness_is_not_idle_proof(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'lazy': True, 'session_key': 'native-stored', 'history_lock': threading.RLock(),
    }}, _child_run_active=lambda key, home: False)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_native_streaming_inflight_is_not_unconfirmed_when_running_flag_is_false(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(), 'inflight_turn': {'streaming': True},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_native_terminal_inflight_error_does_not_block_unconfirmed_recovery(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(),
        'inflight_turn': {'streaming': False, 'status': 'error'},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    row = job_row(api, first)
    assert row['status'] == 'failed' and 'unconfirmed' in row['error'].lower()


def test_unknown_inflight_shape_does_not_prove_idle(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, first = add_job(api, client, monkeypatch)
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: [])
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'history_lock': threading.RLock(), 'inflight_turn': {'streaming': False},
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    api.watch('owner', 'basic', 'default')
    assert job_row(api, first)['status'] == 'watching'


def test_lazy_unconfirmed_monitoring_recovers_to_matching_native_result(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch, callback=True)
    events = []
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: events)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'lazy': True, 'session_key': 'native-stored', 'history_lock': threading.RLock(),
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {
        'messages': [{'role': 'assistant', 'row_id': 11, 'content': 'verified result'}],
    })
    url = f'/api/plugins/beacon/mobile/calls/{call_id}'
    waiting = client.get(url).json()['jobs'][0]
    assert waiting['status'] == 'uncertain'
    assert waiting['error_code'] == 'native_turn_pending_confirmation' and waiting['will_continue'] is True
    assert 'result' not in waiting and job_row(api, first)['status'] == 'watching'
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id=?', (first,)).fetchone()[0] == 0
    events.append({'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 10, 'final_assistant_row_id': 11},
    }})
    complete = client.get(url).json()['jobs'][0]
    assert complete['status'] == 'done' and complete['result'] == 'verified result'
    assert 'error_code' not in complete and 'error' not in complete


def test_pending_confirmation_recovers_to_real_native_failure(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    events = []
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: events)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {
        'running': False, 'lazy': True, 'session_key': 'native-stored', 'history_lock': threading.RLock(),
    }})
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': []})
    url = f'/api/plugins/beacon/mobile/calls/{call_id}'
    assert client.get(url).json()['jobs'][0]['error_code'] == 'native_turn_pending_confirmation'
    events.append({'type': 'message.complete', 'payload': {
        'status': 'error', 'persisted_turn': {'user_row_id': 10},
    }})
    result = client.get(url).json()['jobs'][0]
    assert result['job_id'] == first and result['status'] == 'failed'
    assert 'error_code' not in result and 'unconfirmed' not in result['error'].lower()


def test_unavailable_native_proof_reports_pending_uncertainty(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(RuntimeError('store unavailable')))
    job = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert job['job_id'] == first and job['status'] == 'uncertain'
    assert job['error_code'] == 'native_turn_pending_confirmation' and job['will_continue'] is True
    assert job_row(api, first)['status'] == 'watching'


def test_missing_native_session_does_not_claim_native_work_failed(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, first = add_job(api, client, monkeypatch)
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(LookupError('missing')))
    job = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert job['job_id'] == first and job['status'] == 'uncertain'
    assert job['error_code'] == 'native_turn_unconfirmed' and not job.get('will_continue', False)


def test_call_status_advertises_bound_row_job_linkage(harness):
    _, client, _, _, _ = harness
    call_id = reserve(client)
    status = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}')
    assert status.status_code == 200
    assert status.json()['job_linkage'] == 'row_bound'


@pytest.mark.parametrize('local_record', [False, True])
def test_foreign_epoch_watcher_never_verifies_or_mutates_pending_job(harness, monkeypatch, local_record):
    api, client, _, _, _ = harness
    _, job_id = add_job(api, client, monkeypatch)
    before = job_row(api, job_id)
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={'runtime': {}} if local_record else {})
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-two', events_since=lambda *_: [])
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.activity, 'sync', lambda *args: None)
    calls = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: calls.append(args) or (_ for _ in ()).throw(LookupError('missing')))

    api.watch('owner', 'basic', 'default')

    assert calls == []
    assert job_row(api, job_id) == before


@pytest.mark.parametrize('status', ['watching', 'needs_input'])
@pytest.mark.parametrize('epoch_state', ['foreign', 'unknown'])
def test_foreign_or_unknown_epoch_public_status_is_uncertain_without_mutation(harness, monkeypatch, status, epoch_state):
    api, client, _, _, _ = harness
    call_id, job_id = add_job(api, client, monkeypatch)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute('UPDATE jobs SET status=? WHERE id=?', (status, job_id))
    before = job_row(api, job_id)
    def epoch():
        if epoch_state == 'unknown':
            raise RuntimeError('replay unavailable')
        return 'epoch-after-restart'
    server = SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={})
    replay = SimpleNamespace(replay_epoch=epoch, events_since=lambda *_: [])
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    monkeypatch.setattr(api.activity, 'sync', lambda *args: None)
    calls = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: calls.append(args))

    response = client.get(f'/api/plugins/beacon/mobile/calls/{call_id}')

    assert response.status_code == 200, response.text
    job = response.json()['jobs'][0]
    assert job['status'] == 'uncertain' and job['error_code'] == 'native_turn_unconfirmed'
    assert not job.get('will_continue', False)
    assert job_row(api, job_id) == before and calls == []


def test_foreign_pending_rows_do_not_starve_same_epoch_watch_or_callback(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id, seed = add_job(api, client, monkeypatch)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute("UPDATE jobs SET epoch='foreign' WHERE id=?", (seed,))
        for n in range(512):
            conn.execute("""INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated)
                SELECT ?,call_id,owner,profile,stored,runtime,?,title,0,'foreign',?,status,history_updated FROM jobs WHERE id=?""",
                (f'foreign-{n}', f'foreign-request-{n}', 1000+n, seed))
        conn.execute("""INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated)
            SELECT 'own',call_id,owner,profile,stored,runtime,'own-request',title,0,'epoch-one',12,'watching',history_updated FROM jobs WHERE id=?""", (seed,))
        conn.execute("""INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated)
            SELECT 'done',call_id,owner,profile,stored,runtime,'done-request',title,1,'foreign',13,'completed',history_updated FROM jobs WHERE id=?""", (seed,))
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))
    events = [{'type': 'message.complete', 'payload': {
        'status': 'complete', 'persisted_turn': {'user_row_id': 12, 'final_assistant_row_id': 14},
    }}]
    install_replay(monkeypatch, events)
    monkeypatch.setattr(api.activity, 'sync', lambda *args: None)
    calls = []
    def verify(*args):
        calls.append(args)
        return {'messages': [{'role': 'assistant', 'row_id': 14, 'text': 'verified'}]}
    monkeypatch.setattr(api.native, 'verify', verify)

    api.watch('owner', 'basic', 'default')

    assert len(calls) == 2
    assert job_row(api, 'own')['status'] == 'completed' and job_row(api, 'own')['result'] == 'verified'
    assert job_row(api, seed)['status'] == 'watching'
    assert job_row(api, 'done')['status'] == 'callback_pending'
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE job_id='done' AND kind='incoming_call'").fetchone()[0] == 1


def test_foreign_pending_rows_do_not_consume_current_epoch_capacity(harness, monkeypatch):
    api, client, _, _, _ = harness
    _, seed = add_job(api, client, monkeypatch)
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute("UPDATE jobs SET epoch='foreign' WHERE id=?", (seed,))
        for n in range(511):
            conn.execute("""INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated)
                SELECT ?,call_id,owner,profile,stored,runtime,?,title,0,'foreign',?,status,history_updated FROM jobs WHERE id=?""",
                (f'foreign-{n}', f'foreign-request-{n}', 1000+n, seed))
    call_id = reserve(client)
    monkeypatch.setattr(api.activity, 'sync', lambda *args: None)
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'messages': [{'role': 'user', 'row_id': 12}]})

    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'new-request', 'user_row_id': 12,
    })

    assert response.status_code == 200, response.text
    assert job_row(api, response.json()['job_id'])['epoch'] == 'epoch-one'
