"""Smaller speed changes: batched writes and reads, one prune per watch pass, hooks, event-loop hygiene, lookup slots, timeouts."""
import asyncio
import subprocess
import threading
import time
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from test_installer import installer
from test_job_watch import install_replay
from test_mobile import harness, load_private


def trace(api, monkeypatch):
    """Every statement any plugin connection runs, in order."""
    statements = []
    original = api.mobile.connect

    def traced(*args, **kwargs):
        conn = original(*args, **kwargs)
        conn.set_trace_callback(statements.append)
        return conn

    monkeypatch.setattr(api.mobile, 'connect', traced)
    return statements


def transcript(index, role, text='words', turn=None):
    return {'event_id': f'{role[0]}{index}', 'turn_id': str(turn or index), 'type': 'transcript', 'role': role,
            'text': text, 'final': True, 'at_ms': index * 10}


# --- device last_seen -------------------------------------------------------------------------------------------

def test_device_last_seen_is_written_at_most_once_a_minute(harness):
    mobile = harness[0].mobile
    device = mobile.link_device('owner', 'basic', 'Pixel')['device_id']

    def seen():
        with mobile.connect() as conn:
            return conn.execute('SELECT last_seen FROM beacon_devices WHERE id=?', (device,)).fetchone()[0]

    linked = seen()
    mobile.touch_device('owner', 'basic', 'default', device)
    assert seen() == linked  # seen a moment ago: nothing written
    with mobile.connect() as conn:
        conn.execute('UPDATE beacon_devices SET last_seen=? WHERE id=?', (time.time() - 120, device))
    mobile.touch_device('owner', 'basic', 'default', device)
    refreshed = seen()
    assert refreshed > time.time() - 5
    mobile.touch_device('owner', 'basic', 'default', device)
    assert seen() == refreshed
    mobile.remove_device('owner', device)
    with pytest.raises(LookupError, match='device_removed'):
        mobile.touch_device('owner', 'basic', 'default', device)


# --- event batches ----------------------------------------------------------------------------------------------

def new_call(api):
    return api.mobile.reserve('owner', 'default', 'conv-1', {'voice_provider': 'gemini'})['call_id']


def test_event_batches_look_up_their_ids_once_instead_of_once_per_event(harness, monkeypatch):
    api = harness[0]
    call = new_call(api)
    statements = trace(api, monkeypatch)
    rows = [transcript(index, 'user') for index in range(1, 65)]
    assert api.mobile.events(call, 'owner', {'events': rows})['count'] == 64
    timeline_reads = [s for s in statements if s.lstrip().upper().startswith('SELECT') and 'timeline' in s]
    assert len(timeline_reads) <= 2
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM timeline WHERE call_id=?', (call,)).fetchone()[0] == 64


def test_event_batches_stay_idempotent_and_refuse_conflicting_duplicates(harness):
    api = harness[0]
    call = new_call(api)
    rows = [transcript(1, 'user'), transcript(2, 'assistant')]
    api.mobile.events(call, 'owner', {'events': rows})
    api.mobile.events(call, 'owner', {'events': rows})  # a replayed batch changes nothing
    with pytest.raises(ValueError, match='Conflicting duplicate event'):
        api.mobile.events(call, 'owner', {'events': [transcript(3, 'user'), transcript(2, 'assistant', text='other words')]})
    with pytest.raises(ValueError, match='Conflicting duplicate event'):
        api.mobile.events(call, 'owner', {'events': [transcript(4, 'user'), transcript(4, 'user', text='other words')]})
    with api.mobile.connect() as conn:
        ids = {row[0] for row in conn.execute('SELECT event_id FROM timeline WHERE call_id=?', (call,))}
    assert ids == {'u1', 'a2'}  # a refused batch stores none of its events


def test_event_batches_enforce_the_timeline_cap_from_the_stored_count(harness):
    api = harness[0]
    call = new_call(api)
    with api.mobile.connect() as conn:
        conn.executemany('INSERT INTO timeline VALUES(?,?,?,?)', [(call, f'bulk{n}', 'digest', '{}') for n in range(19999)])
    with pytest.raises(ValueError, match='Timeline limit exceeded'):
        api.mobile.events(call, 'owner', {'events': [transcript(1, 'user'), transcript(2, 'assistant')]})
    assert api.mobile.events(call, 'owner', {'events': [transcript(1, 'user')]})['count'] == 1
    with pytest.raises(ValueError, match='Timeline limit exceeded'):
        api.mobile.events(call, 'owner', {'events': [transcript(5, 'user')]})
    api.mobile.events(call, 'owner', {'events': [transcript(1, 'user')]})  # an event already stored is not new


# --- voice history ----------------------------------------------------------------------------------------------

def test_voice_history_reads_payloads_in_batches_and_interruptions_once(harness, monkeypatch):
    api = harness[0]
    call = new_call(api)
    rows = []
    for index in range(1, 41):
        rows += [transcript(index, 'user', turn=index), transcript(index, 'assistant', turn=index)]
    rows.append({'event_id': 'cut', 'turn_id': '5', 'type': 'interruption', 'at_ms': 999})
    for start in range(0, len(rows), 64): api.mobile.events(call, 'owner', {'events': rows[start:start + 64]})
    statements = trace(api, monkeypatch)

    history = api.mobile.voice_history('owner', 'default', 'conv-1', limit=200)

    assert len(history['events']) == 80 and not history['truncated']
    assistant = {row['turn_id']: row['interrupted'] for row in history['events'] if row['role'] == 'assistant'}
    assert assistant['5'] is True and [turn for turn, cut in assistant.items() if cut] == ['5']
    assert not any(row['interrupted'] for row in history['events'] if row['role'] == 'user')
    assert len([s for s in statements if "'interruption'" in s]) == 1
    assert len([s for s in statements if 'FROM timeline WHERE rowid IN' in s]) == 3  # 80 payloads, 32 at a time


# --- watch pass -------------------------------------------------------------------------------------------------

def count_prunes(api, monkeypatch):
    prunes = []
    original = api.mobile.prune_notices
    monkeypatch.setattr(api.mobile, 'prune_notices', lambda conn: prunes.append(True) or original(conn))
    return prunes


def add_rows(api, jobs):
    now = time.time()
    with api.mobile.connect() as conn:
        for index, (status, callback) in enumerate(jobs):
            conn.execute('INSERT INTO calls(id,owner,profile,conversation,created,closed) VALUES(?,?,?,?,?,1)',
                         (f'call-{index}', 'owner', 'default', 'native-stored', now))
            conn.execute('INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated)'
                         ' VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
                         (f'job-{index}', f'call-{index}', 'owner', 'default', 'native-stored', 'runtime', f'request-{index}',
                          f'Job {index}', callback, 'epoch-one', index + 1, status, now))


def test_watch_pass_prunes_once_however_many_jobs_are_waiting(harness, monkeypatch):
    api = harness[0]
    install_replay(monkeypatch, [])
    monkeypatch.setattr(api.native, 'verify', lambda *args: {'user_id': 'basic:owner'})
    add_rows(api, [('watching', 0)] * 6)
    prunes = count_prunes(api, monkeypatch)

    api.watch('owner', 'basic', 'default')

    assert len(prunes) == 1  # the closing activate() pass; six waiting jobs add none
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM jobs WHERE status='watching'").fetchone()[0] == 6


def test_watch_pass_hands_every_finished_callback_job_over_in_one_pass(harness, monkeypatch):
    api = harness[0]
    install_replay(monkeypatch, [])
    add_rows(api, [('completed', 1), ('completed', 1), ('completed', 0)])
    prunes = count_prunes(api, monkeypatch)

    api.watch('owner', 'basic', 'default')

    with api.mobile.connect() as conn:
        statuses = {row['id']: row['status'] for row in conn.execute('SELECT id,status FROM jobs')}
        rings = conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0]
    assert statuses == {'job-0': 'callback_pending', 'job-1': 'callback_pending', 'job-2': 'completed'}
    assert rings == 2
    assert len(prunes) == 2  # one for the finished jobs, one closing pass


def test_watch_pass_leaves_a_finished_callback_alone_while_a_call_is_open(harness, monkeypatch):
    api = harness[0]
    install_replay(monkeypatch, [])
    add_rows(api, [('completed', 1)])
    with api.mobile.connect() as conn:
        conn.execute('INSERT INTO calls(id,owner,profile,conversation,created) VALUES(?,?,?,?,?)',
                     ('open-call', 'owner', 'default', 'native-stored', time.time()))

    api.watch('owner', 'basic', 'default')

    with api.mobile.connect() as conn:
        assert conn.execute("SELECT status FROM jobs WHERE id='job-0'").fetchone()[0] == 'completed'
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0] == 0


# --- hooks ------------------------------------------------------------------------------------------------------

def test_pre_llm_hooks_read_settings_once_each_and_verify_a_chat_once_a_turn(harness, monkeypatch):
    api, _, cfg, _, _ = harness
    plugin = load_private('__init__')
    monkeypatch.setattr(plugin, 'current_profile', lambda config: 'default')
    reads, verified = [], []
    monkeypatch.setattr(plugin.mobile, 'settings', lambda profile=None: reads.append(profile) or cfg)
    monkeypatch.setattr(plugin.native, 'verify', lambda *args: verified.append(args))
    monkeypatch.setattr(plugin.mobile, 'voice_history', lambda *args, **kwargs: {'events': []})
    monkeypatch.setattr(plugin.live_context, 'snapshot', lambda *args, **kwargs: None)

    plugin.native_voice_context('chat', [])
    plugin.native_live_context('chat')

    assert reads == [None, None]
    assert verified == [('owner', 'basic', 'default', 'chat')]


def test_a_refused_chat_is_verified_again_and_a_passed_one_expires(harness, monkeypatch):
    api, _, cfg, _, _ = harness
    plugin = load_private('__init__')
    monkeypatch.setattr(plugin, 'current_profile', lambda config: 'default')
    calls = []

    def verify(*args):
        calls.append(args)
        if args[-1] == 'foreign': raise LookupError('not owned')

    monkeypatch.setattr(plugin.native, 'verify', verify)
    monkeypatch.setattr(plugin.mobile, 'voice_history', lambda *args, **kwargs: {'events': []})
    monkeypatch.setattr(plugin, '_VERIFY_REUSE_SECONDS', 0.05)

    assert plugin.native_voice_context('foreign', []) is None
    assert plugin.native_voice_context('foreign', []) is None
    assert len(calls) == 2  # a refusal is never reused
    plugin.native_voice_context('mine', [])
    plugin.native_voice_context('mine', [])
    assert len(calls) == 3
    time.sleep(0.1)
    plugin.native_voice_context('mine', [])
    assert len(calls) == 4


# --- event loop hygiene -----------------------------------------------------------------------------------------

def test_identity_and_voice_reads_run_off_the_event_loop(harness, monkeypatch):
    api = harness[0]
    threads = {}
    monkeypatch.setattr(api, 'identity', lambda request, *args, **kwargs: threads.setdefault('identity', threading.get_ident()) and ('owner', 'basic', 'default', {'profiles': ['default']}))
    monkeypatch.setattr(api.voice_models, 'selected', lambda *args: threads.setdefault('selected', threading.get_ident()) and {'provider': 'gemini'})
    loop_thread = []

    async def run():
        loop_thread.append(threading.get_ident())
        return await api.bootstrap(SimpleNamespace())

    result = asyncio.run(run())

    assert result['owner_id'] == 'owner' and result['profile'] == 'default'
    assert threads['identity'] != loop_thread[0] and threads['selected'] != loop_thread[0]


def test_notification_and_device_handlers_do_their_database_work_off_the_event_loop(harness, monkeypatch):
    api, client, _, _, app = harness
    loop_threads, opened = set(), set()

    @app.middleware('http')
    async def spy(request, call_next):
        loop_threads.add(threading.get_ident())
        return await call_next(request)

    original = api.mobile.connect
    monkeypatch.setattr(api.mobile, 'connect', lambda *args, **kwargs: opened.add(threading.get_ident()) or original(*args, **kwargs))
    for path in ('/devices', '/mobile/notifications/targets', '/mobile/notifications/status', '/mobile/plugin/version'):
        assert client.get('/api/plugins/beacon' + path).status_code == 200, path

    assert loop_threads and opened
    assert not opened & loop_threads  # every connection was opened by a worker thread, never by the loop's own


# --- lookup slots -----------------------------------------------------------------------------------------------

def test_timed_out_lookups_free_their_waiting_place_and_stay_bounded(harness):
    api = harness[0]
    release = threading.Event()
    hung = lambda: release.wait(15)

    async def wait_until_served():
        deadline = time.monotonic() + 5
        while True:
            try: return await api.feature(lambda: 'ok')
            except HTTPException as error:
                assert error.status_code == 429 and time.monotonic() < deadline
                await asyncio.sleep(0.02)

    async def run():
        for _ in range(api._FEATURE_WAITING):
            with pytest.raises(HTTPException) as error: await api.feature(hung, timeout=0.02)
            assert error.value.status_code == 504
        # Four lookups timed out and still run; the next one is answered all the same.
        assert await api.feature(lambda: 'ok') == 'ok'
        for _ in range(api._FEATURE_ABANDONED):
            with pytest.raises(HTTPException) as error: await api.feature(hung, timeout=0.02)
            assert error.value.status_code == 504
        # Every worker is now stuck: further lookups are refused rather than queued without limit.
        with pytest.raises(HTTPException) as error: await api.feature(lambda: 'ok')
        assert error.value.status_code == 429
        release.set()
        assert await wait_until_served() == 'ok'  # the stuck workers return and the late results are dropped

    try: asyncio.run(run())
    finally: release.set()


def test_lookup_waiting_places_do_not_leak_across_success_failure_and_timeout(harness):
    api = harness[0]

    def boom(): raise RuntimeError('lookup failed')

    async def run():
        for _ in range(30):
            assert await api.feature(lambda: 1) == 1
            with pytest.raises(HTTPException): await api.feature(boom)
            with pytest.raises(HTTPException): await api.feature(lambda: (_ for _ in ()).throw(LookupError('missing')))
        assert api._feature_live.acquire(blocking=False) and api._feature_slots.acquire(blocking=False)
        api._feature_live.release(); api._feature_slots.release()

    asyncio.run(run())


# --- independent lookups run together ---------------------------------------------------------------------------

def test_proactive_status_and_source_labels_are_fetched_together(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api.proactive, 'status', lambda *args: time.sleep(0.4) or {'enabled': False})
    monkeypatch.setattr(api.voice_context, 'tool_catalogue', lambda *args: time.sleep(0.4) or 'mail: reads\nnews: reads')

    started = time.monotonic()
    response = client.get('/api/plugins/beacon/mobile/proactive')
    elapsed = time.monotonic() - started

    assert response.status_code == 200, response.text
    assert response.json() == {'enabled': False, 'sources': ['mail', 'news'], 'sources_all': True,
                               'source_choices': [{'name': 'mail', 'watched': True}, {'name': 'news', 'watched': True}]}
    assert elapsed < 0.7  # one after the other would take 0.8 s


def test_health_snapshot_reads_files_and_the_profile_together(harness, monkeypatch):
    api = harness[0]
    health = api.sys.modules['beacon_health_api']
    monkeypatch.setattr(health, '_observe', lambda cfg, owner, profile: time.sleep(0.4) or {'observed_at': 1})

    async def slow_calls(cfg, owner, profile):
        await asyncio.sleep(0.4)
        return {'ready': True}

    monkeypatch.setattr(health, '_calls', slow_calls)
    started = time.monotonic()
    value = asyncio.run(health._snapshot({}, 'owner', 'default'))

    assert value == {'observed_at': 1, 'calls': {'ready': True}}
    assert time.monotonic() - started < 0.7


# --- install timeouts -------------------------------------------------------------------------------------------

def test_scheduler_and_service_commands_have_a_deadline(installer, monkeypatch):
    seen = []

    def run(argv, **kwargs):
        seen.append((argv, kwargs))
        if argv[0] == 'systemctl': raise subprocess.TimeoutExpired(argv, kwargs['timeout'])
        return subprocess.CompletedProcess(argv, 0)

    monkeypatch.setattr(installer.subprocess, 'run', run)
    assert installer._service_command(['schtasks', '/run', '/tn', 'Hermes Dashboard'], capture=True) == 0
    assert installer._service_command(['systemctl', '--user', 'restart', 'hermes-dashboard']) == 1  # a hang counts as failed
    assert [kwargs['timeout'] for _, kwargs in seen] == [60, 60]
    assert seen[0][1]['capture_output'] is True and seen[1][1]['capture_output'] is False


def test_a_hung_service_restart_is_a_clear_installer_failure(installer, tmp_path, monkeypatch):
    monkeypatch.setattr(installer, '_dashboard_pids', lambda *args: [])
    monkeypatch.setattr(installer, '_windows_dashboard_task', lambda *args: None)
    monkeypatch.setattr(installer, '_systemd_dashboard_unit', lambda *args: 'hermes-dashboard')

    def run(argv, **kwargs): raise subprocess.TimeoutExpired(argv, kwargs['timeout'])

    monkeypatch.setattr(installer.subprocess, 'run', run)
    with pytest.raises(installer.InstallError, match='systemctl could not restart'):
        installer._restart_dashboard('hermes', tmp_path, 'default', 9119, None)
