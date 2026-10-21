import sys
import threading
from types import SimpleNamespace

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
