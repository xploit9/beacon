"""Synthetic tests for the private incident journal and bounded recovery dispatch."""
import contextvars
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import sqlite3
import threading
import time
import uuid

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ('owner', 'password', 'default')
CHECKS = {name: True for name in ('reproduction', 'regression', 'tests', 'build', 'lint', 'review',
                                  'gitleaks', 'privacy', 'signer', 'versions', 'fresh_downloads', 'release')}


class Connection(sqlite3.Connection):
    def __exit__(self, *exc):
        try: return super().__exit__(*exc)
        finally: self.close()


class Mobile:
    def __init__(self, path):
        self.path = path
        self.LOCK = threading.RLock()

    def connect(self):
        db = sqlite3.connect(self.path, timeout=2, factory=Connection)
        db.row_factory = sqlite3.Row
        return db


def load_module():
    source = ROOT / 'self_heal.py'
    assert source.exists(), 'The incident journal has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_self_heal_test', source)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


@pytest.fixture
def module():
    return load_module()


@pytest.fixture
def mobile(tmp_path):
    return Mobile(tmp_path / 'synthetic.sqlite')


def incident(**extra):
    return {'component': 'voice', 'operation': 'discover_tools', 'error_code': 'timeout', **extra}


def healer(module, mobile, runtime=None, submit=None, can_submit=None, version='1.1.31'):
    return module.Healer(mobile, version, runtime or (lambda scope, row: {'status': 'recovered', 'verified': True}),
                         submit or (lambda scope, key, row: {'status': 'queued', 'job_id': 'job-123'}),
                         can_submit or (lambda scope: True))


def wait_for(predicate, seconds=3):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        value = predicate()
        if value: return value
        time.sleep(.01)
    pytest.fail('Synthetic worker did not settle within its deadline')


def row(api, scope=SCOPE):
    values = api.list(scope)
    assert len(values) == 1
    return values[0]


def test_suspended_healer_never_starts_a_later_submission_stage(module, mobile):
    entered = threading.Event(); release = threading.Event(); submitted = []
    def repair(*_):
        entered.set(); release.wait(3)
        return {'status':'unverified'}
    api = healer(module, mobile, runtime=repair, submit=lambda *args: submitted.append(True) or {'status':'queued', 'job_id':'job-123'})
    item = api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started' and entered.wait(2)
    try:
        assert api.pause()['callbacks_running'] is True
        api.resume()
    finally: release.set()
    wait_for(lambda: row(api)['job_state'] == 'deferred' and row(api)['state'] == 'unverified')
    with mobile.LOCK, mobile.connect() as db:
        assert db.execute('SELECT worker_id FROM healing_incidents WHERE id=?', (item['id'],)).fetchone()[0] is None
    assert submitted == []


def test_worker_activation_preserves_the_admitting_context(module, mobile, monkeypatch):
    marker = contextvars.ContextVar('healing-test-context', default='missing')
    token = marker.set('synthetic-scope')
    original = mobile.connect; observed = []
    def connect():
        if threading.current_thread().name == 'beacon-healing':
            observed.append(marker.get())
            if marker.get() != 'synthetic-scope': raise RuntimeError('context missing')
        return original()
    monkeypatch.setattr(mobile, 'connect', connect)
    try:
        api = healer(module, mobile)
        item = api.record(SCOPE, incident())
        assert api.recover(SCOPE, item['id'])['status'] == 'started'
        wait_for(lambda: row(api)['job_state'] == 'queued')
        assert observed and all(value == 'synthetic-scope' for value in observed)
    finally: marker.reset(token)


def test_suspension_during_idle_probe_never_starts_submission(module, mobile):
    entered = threading.Event(); release = threading.Event(); submitted = []
    def idle(*_):
        entered.set(); release.wait(3)
        return True
    api = healer(module, mobile, can_submit=idle, submit=lambda *args: submitted.append(True) or {'status':'queued', 'job_id':'job-123'})
    item = api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started' and entered.wait(2)
    try: assert api.pause()['callbacks_running'] is True
    finally: release.set()
    wait_for(lambda: row(api)['job_state'] == 'deferred')
    assert submitted == [] and row(api)['job_attempts'] == 0


def test_unstarted_claim_survives_cleanup_failure_and_retries_without_resetting_budget(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'START_GRACE_SECONDS', 0, raising=False)
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 0)
    api = healer(module, mobile)
    item = api.record(SCOPE, incident())
    original_db = api._db; original_start = threading.Thread.start; calls = []
    @contextmanager
    def flaky_db():
        calls.append(True)
        if len(calls) == 2: raise RuntimeError('private-cleanup-canary')
        with original_db() as db: yield db
    def failed_start(*_): raise RuntimeError('private-start-canary')
    monkeypatch.setattr(api, '_db', flaky_db)
    monkeypatch.setattr(threading.Thread, 'start', failed_start)
    assert api.recover(SCOPE, item['id'])['status'] == 'unverified'
    monkeypatch.setattr(api, '_db', original_db)
    monkeypatch.setattr(threading.Thread, 'start', original_start)
    restarted = healer(module, mobile)
    assert restarted.drain(SCOPE)['status'] == 'started'
    result = wait_for(lambda: (value if (value := row(restarted))['job_state'] == 'queued' else None))
    assert result['attempts'] == 2 and result['job_attempts'] == 1


def test_construction_and_import_do_not_open_storage_or_start_workers(monkeypatch):
    def refused(*args, **kwargs): raise AssertionError('No startup work is permitted')
    monkeypatch.setattr(threading.Thread, 'start', refused)
    class NoIO:
        @property
        def LOCK(self): return refused()
        connect = refused
    healer(load_module(), NoIO())


def test_record_rejects_private_or_unknown_fields_without_echoing_them(module, mobile):
    api = healer(module, mobile)
    for bad in ({'message': 'private-token-marker'}, {'body': 'private-token-marker'},
                {'error_code': 'private-token-marker'}, {'provider': 'private-token-marker'},
                {'call_id': '../private-token-marker'}, {'stack_fingerprint': 'private-token-marker'},
                {'error_code': 'cancelled'}, {'state': 'fix_published'}):
        with pytest.raises(ValueError) as failure:
            api.record(SCOPE, incident(**bad))
        assert 'private-token-marker' not in str(failure.value)
    assert api.list(SCOPE) == []
    assert b'private-token-marker' not in mobile.path.read_bytes()


@pytest.mark.parametrize('value', [True, False, 0, -1, 1.5, '141'])
def test_app_version_requires_a_positive_non_boolean_integer(module, mobile, value):
    api = healer(module, mobile)
    with pytest.raises(ValueError): api.record(SCOPE, incident(app_version_code=value))
    assert api.list(SCOPE) == []


@pytest.mark.parametrize('code', ['process_crash', 'process_anr', 'process_low_memory', 'process_resource_limit'])
def test_fixed_android_exit_categories_need_no_raw_process_log(module, mobile, code):
    result = healer(module, mobile).record(SCOPE, {'component': 'app', 'operation': 'startup', 'error_code': code})
    assert result['error_code'] == code
    assert result['occurrences'] == 1


def test_offline_upload_receipts_deduplicate_retries_and_reject_changed_metadata(module, mobile):
    api = healer(module, mobile); event_id = str(uuid.uuid4())
    first = api.record(SCOPE, incident(), event_id=event_id)
    assert api.record(SCOPE, incident(), event_id=event_id)['occurrences'] == 1
    assert healer(module, mobile, version='1.1.32').record(SCOPE, incident(), event_id=event_id)['id'] == first['id']
    with pytest.raises(ValueError): api.record(SCOPE, incident(error_code='network'), event_id=event_id)
    assert api.record(SCOPE, incident(), event_id=str(uuid.uuid4()))['occurrences'] == 2
    assert api.record(('other', 'password', 'default'), incident(), event_id=event_id)['id'] != first['id']


def test_concurrent_offline_uploads_commit_one_incident_occurrence(module, mobile):
    api = healer(module, mobile); event_id = str(uuid.uuid4()); barrier = threading.Barrier(8)
    results, errors = [], []
    def upload():
        try:
            barrier.wait(); results.append(api.record(SCOPE, incident(), event_id=event_id))
        except Exception as error: errors.append(type(error).__name__)
    threads = [threading.Thread(target=upload) for _ in range(8)]
    for thread in threads: thread.start()
    for thread in threads: thread.join(5)
    assert not errors and len(results) == 8 and not any(thread.is_alive() for thread in threads)
    assert row(api)['occurrences'] == 1


@pytest.mark.parametrize('event_id', [True, '', '../private-canary', 'not-a-uuid'])
def test_upload_identity_cannot_be_arbitrary_text(module, mobile, event_id):
    with pytest.raises(ValueError): healer(module, mobile).record(SCOPE, incident(), event_id=event_id)


@pytest.mark.parametrize('return_to_old', [False, True])
def test_a_queued_repair_cannot_confirm_after_a_new_version_observation(module, mobile, return_to_old):
    keys = []
    api = healer(module, mobile, submit=lambda scope, key, event: keys.append(key) or {'status': 'queued', 'job_id': 'job-1'})
    item = api.record(SCOPE, incident(app_version_code=141))
    api.recover(SCOPE, item['id']); wait_for(lambda: row(api)['job_state'] == 'queued')
    version = {'app_version_code': 141, 'plugin_version': '1.1.31'}
    assert api.job_current(SCOPE, item['id'], keys[0], version)
    api.record(SCOPE, incident(app_version_code=142))
    if return_to_old: api.record(SCOPE, incident(app_version_code=141))
    with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    assert not api.job_current(SCOPE, item['id'], keys[0], version)
    assert row(api)['state'] != 'fix_published'


def test_same_version_repeats_and_missing_version_do_not_stale_a_queued_repair(module, mobile):
    keys = []
    api = healer(module, mobile, submit=lambda scope, key, event: keys.append(key) or {'status': 'queued', 'job_id': 'job-1'})
    item = api.record(SCOPE, incident(app_version_code=141))
    api.recover(SCOPE, item['id']); wait_for(lambda: row(api)['job_state'] == 'queued')
    api.record(SCOPE, incident(app_version_code=141)); api.record(SCOPE, incident())
    assert api.job_current(SCOPE, item['id'], keys[0], {'app_version_code': 141, 'plugin_version': '1.1.31'})
    assert not api.job_current(('foreign', 'password', 'default'), item['id'], keys[0], {'app_version_code': 141, 'plugin_version': '1.1.31'})
    assert api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])['state'] == 'fix_published'


def test_unknown_and_updated_versions_share_one_incident_and_keep_observations(mobile):
    module = load_module()
    api = healer(module, mobile)
    unknown = api.record(SCOPE, incident(provider='grok'))
    known = api.record(SCOPE, incident(provider='grok', app_version_code=141))
    newer = healer(module, mobile, version='1.1.32').record(SCOPE, incident(provider='grok', app_version_code=141))
    assert unknown['app_version_code'] is None
    assert len({unknown['id'], known['id'], newer['id']}) == 1
    assert len(api.list(SCOPE)) == 1 and newer['occurrences'] == 3
    assert newer['observed_versions'] == [
        {'app_version_code': None, 'plugin_version': '1.1.31'},
        {'app_version_code': 141, 'plugin_version': '1.1.31'},
        {'app_version_code': 141, 'plugin_version': '1.1.32'},
    ]


def test_version_observations_are_bounded_without_resetting_the_incident(module, mobile):
    api = healer(module, mobile)
    ids = [api.record(SCOPE, incident(app_version_code=code))['id'] for code in range(1, 15)]
    actual = row(api)
    assert len(set(ids)) == 1 and actual['occurrences'] == 14
    assert len(actual['observed_versions']) <= 8
    assert actual['observed_versions'][-1] == {'app_version_code': 14, 'plugin_version': '1.1.31'}


def test_published_fix_retains_original_job_versions_across_older_and_newer_recurrence(module, mobile):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-123'}
    old = healer(module, mobile, submit=submit, version='1.1.31')
    initial = old.record(SCOPE, incident(provider='grok', app_version_code=141))
    old.recover(SCOPE, initial['id']);wait_for(lambda: row(old)['job_state'] == 'queued')
    fixed = old.confirm_fix(SCOPE, initial['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    newer = healer(module, mobile, submit=submit, version='1.1.32')
    queued = newer.record(SCOPE, incident(provider='grok', app_version_code=142))
    assert queued['id'] == initial['id'] and queued['job_state'] == 'deferred' and queued['state'] == 'open' and queued['attempts'] == 1
    assert len(keys) == 1
    assert fixed['published_repair_input_version'] == {'app_version_code': 141, 'plugin_version': '1.1.31'}
    old_client = old.record(SCOPE, incident(provider='grok', app_version_code=140))
    assert old_client['id'] == initial['id'] and old_client['state'] == 'open'
    assert old_client['release'] == '1.1.32' and old_client['commit'] == 'a' * 40 and old_client['attempts'] == 1
    assert old_client['published_repair_input_version'] == fixed['published_repair_input_version']
    current = healer(module, mobile, submit=submit, version='1.1.33').record(SCOPE, incident(provider='grok', app_version_code=143))
    assert current['id'] == initial['id'] and current['occurrences'] == 4 and current['attempts'] == 1
    assert current['app_version_code'] == 143 and current['plugin_version'] == '1.1.33'
    assert current['observed_versions'][-1] == {'app_version_code': 143, 'plugin_version': '1.1.33'}
    assert current['published_repair_input_version'] == fixed['published_repair_input_version'] and len(keys) == 1


def test_duplicate_incident_keeps_latest_opaque_context_without_splitting_fingerprint(module, mobile):
    api = healer(module, mobile)
    first = api.record(SCOPE, incident(provider='grok', app_version_code=141, call_id='a' * 32,
                                       request_id='11111111-2222-3333-4444-555555555555', stack_fingerprint='c' * 64))
    second = api.record(SCOPE, incident(provider='grok', app_version_code=141, call_id='b' * 32,
                                        request_id='66666666-7777-8888-9999-aaaaaaaaaaaa', stack_fingerprint='c' * 64))
    assert first['id'] == second['id'] and second['occurrences'] == 2
    assert second['call_id'] == 'b' * 32 and second['request_id'] == '66666666-7777-8888-9999-aaaaaaaaaaaa'


def test_auth_scope_is_isolated_from_voice_provider_metadata(module, mobile):
    api = healer(module, mobile)
    first = api.record(SCOPE, incident(provider='grok'))
    other = ('owner', 'oauth', 'default')
    second = api.record(other, incident(provider='grok'))
    assert first['id'] != second['id']
    assert [item['id'] for item in api.list(SCOPE)] == [first['id']]
    assert [item['id'] for item in api.list(other)] == [second['id']]
    with pytest.raises(LookupError): api.recover(other, first['id'])
    with pytest.raises(LookupError): api.confirm_fix(other, first['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key='0' * 64)


def test_concurrent_duplicate_incidents_are_one_scoped_record(module, mobile):
    api = healer(module, mobile)
    barrier = threading.Barrier(12)
    values, errors = [], []
    def record():
        try:
            barrier.wait(timeout=2)
            values.append(api.record(SCOPE, incident(provider='grok'))['id'])
        except Exception as error: errors.append(error)
    workers = [threading.Thread(target=record) for _ in range(12)]
    for worker in workers: worker.start()
    for worker in workers: worker.join(5)
    assert all(not worker.is_alive() for worker in workers)
    assert errors == [] and len(values) == 12 and len(set(values)) == 1
    assert row(api)['occurrences'] == 12


def test_recovery_is_one_context_preserving_daemon_per_scope_without_db_lock(module, mobile):
    entered, release = threading.Event(), threading.Event()
    marker = contextvars.ContextVar('synthetic-context')
    observed = []
    def runtime(scope, event):
        observed.append((marker.get(), threading.current_thread().daemon, mobile.LOCK._is_owned(), scope, event['id']))
        entered.set()
        assert release.wait(3)
        return {'status': 'recovered', 'verified': True}
    api = healer(module, mobile, runtime=runtime)
    first = api.record(SCOPE, incident())
    second = api.record(SCOPE, incident(error_code='network'))
    marker.set('context-ok')
    try:
        assert api.recover(SCOPE, first['id'])['status'] == 'started'
        assert entered.wait(2)
        assert api.recover(SCOPE, second['id'])['status'] == 'busy'
        assert healer(module, mobile).recover(SCOPE, second['id'])['status'] == 'busy'
        assert api.record(('owner', 'password', 'other'), incident())['state'] == 'open'
        assert observed == [('context-ok', True, False, SCOPE, first['id'])]
    finally:
        release.set()
    wait_for(lambda: next((x for x in api.list(SCOPE) if x['id'] == first['id'] and x['job_state'] == 'queued'), None))


def test_global_worker_limit_is_bounded_across_scopes(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_WORKERS', 2)
    release = threading.Event();entered = []
    def runtime(scope, event):
        entered.append(scope)
        assert release.wait(3)
        return {'status': 'recovered', 'verified': True}
    api = healer(module, mobile, runtime=runtime)
    scopes = [('owner', 'password', name) for name in ('one', 'two', 'three')]
    ids = [api.record(scope, incident())['id'] for scope in scopes]
    try:
        assert api.recover(scopes[0], ids[0])['status'] == 'started'
        assert api.recover(scopes[1], ids[1])['status'] == 'started'
        wait_for(lambda: len(entered) == 2)
        assert api.recover(scopes[2], ids[2])['status'] == 'busy'
    finally:
        release.set()
    for scope in scopes[:2]: wait_for(lambda: row(api, scope)['job_state'] == 'queued')
    assert api.recover(scopes[2], ids[2])['status'] == 'started'
    wait_for(lambda: row(api, scopes[2])['job_state'] == 'queued')


def test_occurrence_during_probe_cannot_be_marked_recovered(module, mobile):
    entered, release = threading.Event(), threading.Event()
    def runtime(scope, event):
        entered.set();assert release.wait(3)
        return {'status': 'recovered', 'verified': True}
    api = healer(module, mobile, runtime=runtime)
    item = api.record(SCOPE, incident())
    try:
        assert api.recover(SCOPE, item['id'])['status'] == 'started'
        assert entered.wait(2)
        api.record(SCOPE, incident())
    finally:
        release.set()
    actual = wait_for(lambda: (value if (value := row(api))['job_state'] == 'queued' else None))
    assert actual['state'] == 'open' and actual['occurrences'] == 2


def test_occurrence_at_the_counter_cap_still_invalidates_a_running_probe(module, mobile):
    entered, release = threading.Event(), threading.Event()
    def runtime(scope, event):
        entered.set();assert release.wait(3)
        return {'status': 'recovered', 'verified': True}
    api = healer(module, mobile, runtime=runtime)
    item = api.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute('UPDATE healing_incidents SET occurrences=1000000000 WHERE id=?', (item['id'],))
    try:
        assert api.recover(SCOPE, item['id'])['status'] == 'started'
        assert entered.wait(2)
        api.record(SCOPE, incident())
    finally:
        release.set()
    actual = wait_for(lambda: (value if (value := row(api))['job_state'] == 'queued' else None))
    assert actual['state'] == 'open' and actual['occurrences'] == 1000000000


@pytest.mark.parametrize('result', [None, {}, {'status': 'recovered'}, {'status': 'recovered', 'verified': 1},
                                    {'status': 'failed', 'verified': True}, {'status': 'unknown', 'verified': True}])
def test_only_explicit_verified_recovery_is_marked_recovered(module, mobile, result):
    api = healer(module, mobile, runtime=lambda scope, event: result)
    item = api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started'
    actual = wait_for(lambda: (value if (value := row(api))['state'] != 'recovering' else None))
    assert actual['state'] == 'unverified'


def test_callback_details_never_enter_the_journal(module, mobile):
    def runtime(scope, event): return {'status': 'recovered', 'verified': True, 'detail': 'private-runtime-marker'}
    def submit(scope, key, event): return {'status': 'queued', 'job_id': 'job-123', 'debug': 'private-submit-marker'}
    api = healer(module, mobile, runtime=runtime, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    assert 'private-runtime-marker' not in str(row(api)) and 'private-submit-marker' not in str(row(api))
    assert b'private-runtime-marker' not in mobile.path.read_bytes() and b'private-submit-marker' not in mobile.path.read_bytes()


def test_attempts_are_capped_and_failures_never_store_exception_text(module, mobile, monkeypatch, caplog):
    monkeypatch.setattr(module, 'MAX_ATTEMPTS', 2)
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 0)
    attempts = []
    def broken(scope, event):
        attempts.append(event['id'])
        raise RuntimeError('private-exception-marker')
    api = healer(module, mobile, runtime=broken)
    item = api.record(SCOPE, incident())
    for expected in (1, 2):
        assert api.recover(SCOPE, item['id'])['status'] == 'started'
        wait_for(lambda: row(api)['state'] == 'unverified' and row(api)['attempts'] == expected and row(api)['job_state'] == 'queued')
    assert api.recover(SCOPE, item['id'])['status'] == 'exhausted'
    assert len(attempts) == 2 and b'private-exception-marker' not in mobile.path.read_bytes()
    assert 'healing_recovery_probe_failed' in caplog.text and 'private-exception-marker' not in caplog.text


def test_failed_recovery_respects_cooldown(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 60)
    api = healer(module, mobile, runtime=lambda scope, event: {'status': 'unknown'})
    item = api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started'
    wait_for(lambda: row(api)['state'] == 'unverified' and row(api)['job_state'] == 'queued')
    assert api.recover(SCOPE, item['id'])['status'] == 'cooldown'


def test_code_submission_waits_for_idle_and_uses_one_stable_request_key(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    idle = threading.Event()
    submitted = []
    def submit(scope, key, event):
        assert not mobile.LOCK._is_owned()
        submitted.append((scope, key, event['id']))
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit, can_submit=lambda scope: idle.is_set())
    item = api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'deferred')
    assert submitted == [] and row(api)['state'] == 'recovered'
    idle.set()
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert len(submitted) == 1 and submitted[0][0] == SCOPE and submitted[0][2] == item['id']
    assert len(submitted[0][1]) == 64
    assert api.drain(SCOPE)['status'] == 'idle' and len(submitted) == 1


def test_one_drain_submits_every_deferred_incident_in_the_scope(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    idle = threading.Event()
    submitted = []
    def submit(scope, key, event):
        submitted.append(event['id'])
        return {'status': 'queued', 'job_id': 'job-' + str(len(submitted))}
    api = healer(module, mobile, submit=submit, can_submit=lambda scope: idle.is_set())
    ids = [api.record(SCOPE, incident(error_code=code))['id'] for code in ('timeout', 'network')]
    for incident_id in ids:
        assert api.recover(SCOPE, incident_id)['status'] == 'started'
        wait_for(lambda: next((x for x in api.list(SCOPE) if x['id'] == incident_id and x['job_state'] == 'deferred'), None))
    idle.set()
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: all(x['job_state'] == 'queued' for x in api.list(SCOPE)))
    assert set(submitted) == set(ids) and len(submitted) == 2


def test_queued_job_is_not_downgraded_or_resubmitted_when_busy(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 0)
    idle = threading.Event();idle.set()
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit, can_submit=lambda scope: idle.is_set())
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    idle.clear();api.record(SCOPE, incident())
    assert api.recover(SCOPE, item['id'])['status'] == 'started'
    wait_for(lambda: row(api)['state'] == 'recovered')
    assert row(api)['job_state'] == 'queued' and len(keys) == 1


def test_atomic_busy_refusal_defers_without_a_false_submission(module, mobile):
    def can_submit(scope):
        assert not mobile.LOCK._is_owned()
        return True
    api = healer(module, mobile, submit=lambda scope, key, event: {'status': 'deferred'}, can_submit=can_submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id'])
    actual = wait_for(lambda: (value if (value := row(api))['job_state'] not in ('none', 'submitting') else None))
    assert actual['job_state'] == 'deferred' and actual['job_id'] is None


def test_deferred_submission_respects_cooldown_until_a_later_drain(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 60)
    idle = threading.Event()
    api = healer(module, mobile, can_submit=lambda scope: idle.is_set())
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'deferred')
    idle.set()
    assert api.drain(SCOPE)['status'] == 'cooldown'
    with mobile.LOCK, mobile.connect() as db:
        db.execute('UPDATE healing_incidents SET next_submit=0 WHERE id=?', (item['id'],))
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')


def test_atomic_refusals_have_a_persistent_admission_attempt_limit(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_JOB_ATTEMPTS', 2)
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    calls = []
    def refused(scope, key, event):
        calls.append(key)
        return {'status': 'deferred'}
    api = healer(module, mobile, submit=refused)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'deferred')
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'exhausted')
    restarted = healer(module, mobile, submit=refused)
    assert row(restarted)['job_attempts'] == 2 and restarted.drain(SCOPE)['status'] == 'idle'
    assert len(calls) == 2 and calls[0] == calls[1]


def test_unconfirmed_submission_is_never_replayed_or_reported_fixed(module, mobile):
    calls = []
    def submit(scope, key, event):
        calls.append(key)
        raise RuntimeError('private-submission-marker')
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id'])
    wait_for(lambda: row(api)['job_state'] == 'unconfirmed')
    assert api.drain(SCOPE)['status'] == 'idle' and len(calls) == 1
    with pytest.raises(ValueError): api.fail_fix(SCOPE, item['id'], expected_request_key=calls[0])
    assert row(api)['state'] != 'fix_published'
    assert b'private-submission-marker' not in mobile.path.read_bytes()


def test_submission_callback_cannot_claim_a_fix_was_published(module, mobile):
    api = healer(module, mobile, submit=lambda scope, key, event: {'status': 'fix_published', 'job_id': 'job-123'})
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'unconfirmed')
    assert row(api)['state'] != 'fix_published' and row(api)['job_id'] is None


@pytest.mark.parametrize('decision', ['not_needed', 'update_required'])
def test_policy_receipts_are_honest_terminal_states_and_new_versions_can_be_rechecked(module, mobile, decision):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': decision} if len(keys) == 1 else {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit, version='1.1.31')
    item = api.record(SCOPE, incident(app_version_code=141))
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == decision)
    assert row(api)['state'] == 'recovered' and row(api)['job_id'] is None
    with pytest.raises(ValueError):
        api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    repeated = api.record(SCOPE, incident(app_version_code=141))
    assert repeated['job_state'] == decision and repeated['occurrences'] == 2
    assert api.drain(SCOPE)['status'] == 'idle' and len(keys) == 1
    newer = healer(module, mobile, submit=submit, version='1.1.32')
    changed = newer.record(SCOPE, incident(app_version_code=142))
    assert changed['id'] == item['id'] and changed['job_state'] == 'deferred'
    assert changed['attempts'] == 1 and changed['job_attempts'] == 1
    assert newer.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(newer)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]


def test_policy_receipt_with_extra_fields_is_not_accepted_or_persisted(module, mobile):
    api = healer(module, mobile, submit=lambda scope, key, event: {'status': 'not_needed', 'detail': 'private-policy-marker'})
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'unconfirmed')
    assert row(api)['state'] != 'fix_published' and b'private-policy-marker' not in mobile.path.read_bytes()


def test_missing_version_is_unknown_and_does_not_retrigger_the_same_policy(module, mobile):
    calls = []
    def submit(scope, key, event):
        calls.append(key)
        return {'status': 'update_required'}
    api = healer(module, mobile, submit=submit, version='1.1.31')
    item = api.record(SCOPE, incident(app_version_code=141))
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'update_required')
    unknown = api.record(SCOPE, incident())
    assert unknown['app_version_code'] is None and unknown['job_state'] == 'update_required'
    known = api.record(SCOPE, incident(app_version_code=141))
    assert known['job_state'] == 'update_required' and known['generation'] == 0
    assert api.drain(SCOPE)['status'] == 'idle' and len(calls) == 1


def test_newly_known_version_reopens_an_unknown_version_policy(module, mobile):
    api = healer(module, mobile, submit=lambda scope, key, event: {'status': 'update_required'}, version='1.1.31')
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'update_required')
    known = api.record(SCOPE, incident(app_version_code=141))
    assert known['id'] == item['id'] and known['job_state'] == 'deferred' and known['generation'] == 1


def test_new_version_during_policy_decision_rechecks_the_new_observation(module, mobile):
    entered, release = threading.Event(), threading.Event()
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        if len(keys) == 1:
            entered.set();assert release.wait(3)
            return {'status': 'update_required'}
        return {'status': 'queued', 'job_id': 'job-123'}
    old = healer(module, mobile, submit=submit, version='1.1.31')
    item = old.record(SCOPE, incident(app_version_code=141))
    try:
        assert old.recover(SCOPE, item['id'])['status'] == 'started'
        assert entered.wait(2)
        newer = healer(module, mobile, submit=submit, version='1.1.32')
        assert newer.record(SCOPE, incident(app_version_code=142))['id'] == item['id']
    finally:
        release.set()
    changed = wait_for(lambda: (value if (value := row(newer))['job_state'] != 'submitting' else None))
    assert changed['job_state'] == 'deferred' and changed['state'] == 'open'
    assert newer.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(newer)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]


def test_transient_new_version_during_policy_is_not_hidden_by_a_later_old_observation(module, mobile):
    entered, release = threading.Event(), threading.Event()
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        if len(keys) == 1:
            entered.set();assert release.wait(3)
            return {'status': 'not_needed'}
        return {'status': 'queued', 'job_id': 'job-123'}
    old = healer(module, mobile, submit=submit, version='1.1.31')
    item = old.record(SCOPE, incident(app_version_code=141))
    try:
        assert old.recover(SCOPE, item['id'])['status'] == 'started'
        assert entered.wait(2)
        newer = healer(module, mobile, submit=submit, version='1.1.32')
        newer.record(SCOPE, incident(app_version_code=142))
        old.record(SCOPE, incident(app_version_code=141))
    finally:
        release.set()
    actual = wait_for(lambda: (value if (value := row(old))['job_state'] != 'submitting' else None))
    assert actual['job_state'] == 'deferred' and actual['state'] == 'open'
    assert old.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(old)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]


def test_fix_confirmation_requires_queued_job_all_checks_commit_and_release(module, mobile):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key='0' * 64)
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    for release, commit, checks in [('bad release', 'a' * 40, CHECKS), ('1.1.32', 'bad', CHECKS),
                                    ('1.1.32', 'a' * 40, {**CHECKS, 'tests': False}),
                                    ('1.1.32', 'a' * 40, {**CHECKS, 'tests': 1}),
                                    ('1.1.32', 'a' * 40, {k: v for k, v in CHECKS.items() if k != 'review'})]:
        with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], release, commit, checks, expected_request_key=keys[0])
    assert row(api)['state'] != 'fix_published'
    confirmed = api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    assert confirmed['state'] == 'fix_published' and confirmed['release'] == '1.1.32' and confirmed['commit'] == 'a' * 40


def test_release_tags_are_bounded_semver_without_empty_identifiers(module, mobile):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    for release in ('1.2.3-..', 'v1.2.3-rc..1', '1.2.3-01', 'v01.2.3', '1.2.3+build..1'):
        with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], release, 'a' * 40, CHECKS, expected_request_key=keys[0])
    assert api.confirm_fix(SCOPE, item['id'], 'v1.2.3-rc.1+build.1', 'a' * 40, CHECKS, expected_request_key=keys[0])['state'] == 'fix_published'


def test_recurrence_reopens_a_published_incident_with_a_new_idempotency_key(module, mobile):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-' + str(len(keys))}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    reopened = api.record(SCOPE, incident())
    assert reopened['id'] == item['id'] and reopened['state'] == 'open' and reopened['occurrences'] == 2
    assert reopened['release'] == '1.1.32' and reopened['commit'] == 'a' * 40
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]
    with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], '1.1.33', 'b' * 40, CHECKS, expected_request_key=keys[0])
    with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[1])
    assert row(api)['state'] != 'fix_published'
    assert api.confirm_fix(SCOPE, item['id'], '1.1.33', 'b' * 40, CHECKS, expected_request_key=keys[1])['state'] == 'fix_published'


def test_duplicate_occurrences_while_job_is_queued_do_not_starve_confirmation(module, mobile):
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-' + str(len(keys))}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    for _ in range(5):
        reopened = api.record(SCOPE, incident())
    assert reopened['state'] == 'open' and reopened['job_state'] == 'queued' and reopened['occurrences'] == 6
    assert len(keys) == 1 and api.drain(SCOPE)['status'] == 'idle'
    assert api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])['state'] == 'fix_published'


def test_occurrence_during_submission_coalesces_without_a_false_runtime_recovery(module, mobile):
    entered, release = threading.Event(), threading.Event()
    keys = []
    def submit(scope, key, event):
        keys.append(key);entered.set();assert release.wait(3)
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    try:
        assert api.recover(SCOPE, item['id'])['status'] == 'started'
        assert entered.wait(2)
        assert api.drain(SCOPE)['status'] == 'busy'
        api.record(SCOPE, incident())
    finally:
        release.set()
    actual = wait_for(lambda: (value if (value := row(api))['job_state'] == 'queued' else None))
    assert actual['state'] == 'open' and actual['occurrences'] == 2 and len(keys) == 1
    assert api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])['state'] == 'fix_published'


def test_known_failed_job_requires_its_current_key_before_a_new_admission(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-' + str(len(keys))}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    with pytest.raises(ValueError): api.fail_fix(SCOPE, item['id'], expected_request_key='0' * 64)
    assert row(api)['job_state'] == 'queued'
    assert api.fail_fix(SCOPE, item['id'], expected_request_key=keys[0])['job_state'] == 'deferred'
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]
    with pytest.raises(ValueError): api.fail_fix(SCOPE, item['id'], expected_request_key=keys[0])
    with pytest.raises(ValueError): api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    assert api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[1])['state'] == 'fix_published'


def test_known_failed_jobs_stop_at_the_persisted_admission_budget(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_JOB_ATTEMPTS', 2)
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-' + str(len(keys))}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    api.fail_fix(SCOPE, item['id'], expected_request_key=keys[0])
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert api.fail_fix(SCOPE, item['id'], expected_request_key=keys[1])['job_state'] == 'exhausted'
    restarted = healer(module, mobile, submit=submit)
    assert restarted.drain(SCOPE)['status'] == 'idle' and row(restarted)['job_attempts'] == 2 and len(keys) == 2


def test_attempt_budgets_persist_across_restart_and_published_recurrence(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_ATTEMPTS', 1)
    monkeypatch.setattr(module, 'MAX_JOB_ATTEMPTS', 1)
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 0)
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-123'}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    restarted = healer(module, mobile, submit=submit)
    reopened = restarted.record(SCOPE, incident())
    assert reopened['state'] == 'open' and reopened['attempts'] == 1 and reopened['job_attempts'] == 1
    assert reopened['job_state'] == 'exhausted'
    assert restarted.recover(SCOPE, item['id'])['status'] == 'exhausted'
    assert restarted.drain(SCOPE)['status'] == 'idle' and len(keys) == 1


def test_new_repair_can_be_deferred_even_when_runtime_attempts_are_exhausted(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_ATTEMPTS', 1)
    monkeypatch.setattr(module, 'MAX_JOB_ATTEMPTS', 2)
    monkeypatch.setattr(module, 'JOB_COOLDOWN_SECONDS', 0)
    keys = []
    def submit(scope, key, event):
        keys.append(key)
        return {'status': 'queued', 'job_id': 'job-' + str(len(keys))}
    api = healer(module, mobile, submit=submit)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    api.confirm_fix(SCOPE, item['id'], '1.1.32', 'a' * 40, CHECKS, expected_request_key=keys[0])
    reopened = api.record(SCOPE, incident())
    assert reopened['state'] == 'open' and reopened['job_state'] == 'deferred'
    assert api.recover(SCOPE, item['id'])['status'] == 'exhausted'
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert len(keys) == 2 and keys[0] != keys[1]


def test_failed_worker_start_never_forgets_an_already_queued_job(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'COOLDOWN_SECONDS', 0)
    api = healer(module, mobile)
    item = api.record(SCOPE, incident())
    api.recover(SCOPE, item['id']);wait_for(lambda: row(api)['job_state'] == 'queued')
    api.record(SCOPE, incident())
    def refused(*args, **kwargs): raise RuntimeError('private-start-marker')
    monkeypatch.setattr(threading.Thread, 'start', refused)
    assert api.recover(SCOPE, item['id'])['status'] == 'unverified'
    assert row(api)['job_state'] == 'queued' and b'private-start-marker' not in mobile.path.read_bytes()


def test_bounded_journal_never_discards_unresolved_incidents(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_INCIDENTS', 3)
    api = healer(module, mobile)
    for code in ('timeout', 'network', 'dns'): api.record(SCOPE, incident(error_code=code))
    with pytest.raises(ValueError): api.record(SCOPE, incident(error_code='tls'))
    assert len(api.list(SCOPE)) == 3


def test_stale_worker_or_submission_is_unconfirmed_and_not_replayed(module, mobile):
    called = []
    api = healer(module, mobile, runtime=lambda scope, event: called.append('runtime'),
                 submit=lambda scope, key, event: called.append('submit'))
    item = api.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering', job_state='submitting', updated=0 WHERE id=?", (item['id'],))
    restarted = healer(module, mobile, runtime=lambda scope, event: called.append('runtime'),
                       submit=lambda scope, key, event: called.append('submit'))
    actual = row(restarted)
    assert actual['state'] == 'unconfirmed' and actual['job_state'] == 'unconfirmed'
    assert restarted.recover(SCOPE, item['id'])['status'] == 'unconfirmed'
    assert restarted.drain(SCOPE)['status'] == 'idle' and called == []


def test_orphaned_recovery_never_starts_a_second_worker(module, mobile):
    called = []
    api = healer(module, mobile, runtime=lambda scope, event: called.append('runtime'))
    item = api.record(SCOPE, incident())
    second = api.record(SCOPE, incident(error_code='network'))
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',job_state='deferred',updated=? WHERE id=?", (time.time(), item['id']))
    assert api.recover(SCOPE, item['id'])['status'] == 'busy'
    assert api.recover(SCOPE, second['id'])['status'] == 'busy'
    assert api.drain(SCOPE)['status'] == 'busy' and called == []


def test_stale_orphan_with_unknown_worker_stays_unconfirmed_and_blocks_overlap(module, mobile):
    called = []
    api = healer(module, mobile, runtime=lambda scope, event: called.append('runtime'))
    first = api.record(SCOPE, incident())
    second = api.record(SCOPE, incident(error_code='network'))
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',job_state='submitting',worker_id='unknown-worker',updated=0 WHERE id=?", (first['id'],))
    actual = next(item for item in api.list(SCOPE) if item['id'] == first['id'])
    assert actual['state'] == 'unconfirmed' and actual['job_state'] == 'unconfirmed'
    assert api.recover(SCOPE, first['id'])['status'] == 'unconfirmed'
    assert api.recover(SCOPE, second['id'])['status'] == 'busy' and called == []


def test_stale_worker_is_released_only_when_its_process_is_known_dead(module, mobile, monkeypatch):
    api = healer(module, mobile)
    first = api.record(SCOPE, incident())
    second = api.record(SCOPE, incident(error_code='network'))
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',job_state='submitting',worker_id='worker',updated=0 WHERE id=?", (first['id'],))
        db.execute('INSERT INTO healing_workers VALUES (?,?,?,?,?,?)', (*SCOPE, 'worker', 123456, 0))
    monkeypatch.setattr(module, '_process_dead', lambda pid: False)
    assert api.recover(SCOPE, second['id'])['status'] == 'busy'
    assert next(item for item in api.list(SCOPE) if item['id'] == first['id'])['state'] == 'recovering'
    monkeypatch.setattr(module, '_process_dead', lambda pid: True)
    stale = next(item for item in api.list(SCOPE) if item['id'] == first['id'])
    assert stale['state'] == 'unconfirmed' and stale['job_state'] == 'unconfirmed'
    assert api.recover(SCOPE, first['id'])['status'] == 'unconfirmed'
    assert api.recover(SCOPE, second['id'])['status'] == 'started'
    wait_for(lambda: next((item for item in api.list(SCOPE) if item['id'] == second['id'] and item['job_state'] == 'queued'), None))


def test_dead_worker_before_submission_can_defer_a_repair_without_replaying_runtime(module, mobile, monkeypatch):
    called = []
    api = healer(module, mobile, runtime=lambda scope, event: called.append('runtime'))
    item = api.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',job_state='none',worker_id='worker',updated=0 WHERE id=?", (item['id'],))
        db.execute('INSERT INTO healing_workers VALUES (?,?,?,?,?,?)', (*SCOPE, 'worker', 123456, 0))
    monkeypatch.setattr(module, '_process_dead', lambda pid: True)
    actual = row(api)
    assert actual['state'] == 'unconfirmed' and actual['job_state'] == 'deferred'
    assert api.drain(SCOPE)['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert called == []


def test_global_limit_releases_a_proven_dead_worker_from_another_scope(module, mobile, monkeypatch):
    monkeypatch.setattr(module, 'MAX_WORKERS', 1)
    monkeypatch.setattr(module, '_process_dead', lambda pid: True)
    api = healer(module, mobile)
    old = ('owner', 'password', 'old')
    stale = api.record(old, incident())
    current = api.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',job_state='submitting',worker_id='worker',updated=0 WHERE id=?", (stale['id'],))
        db.execute('INSERT INTO healing_workers VALUES (?,?,?,?,?,?)', (*old, 'worker', 123456, 0))
    assert api.recover(SCOPE, current['id'])['status'] == 'started'
    wait_for(lambda: row(api)['job_state'] == 'queued')
    assert row(api, old)['state'] == 'unconfirmed' and row(api, old)['job_state'] == 'unconfirmed'


@pytest.mark.parametrize('code', ['outcome_unknown', 'submission_unconfirmed'])
@pytest.mark.parametrize('operation', ['send', 'payment', 'invoke_tool'])
def test_uncertain_mutations_are_never_replayed(module, mobile, code, operation):
    attempted = []
    api = healer(module, mobile, runtime=lambda scope, event: attempted.append(event['operation']))
    item = api.record(SCOPE, incident(component='app', operation=operation, error_code=code))
    assert api.recover(SCOPE, item['id'])['status'] == 'unconfirmed'
    assert attempted == [] and row(api)['state'] == 'unconfirmed'


@pytest.mark.parametrize('code', ['outcome_unknown', 'submission_unconfirmed'])
def test_pending_drain_never_replays_uncertain_mutations(module, mobile, code):
    attempted = []
    api = healer(module, mobile, runtime=lambda scope, event: attempted.append(True))
    api.record(SCOPE, incident(component='app', operation='send', error_code=code))
    assert api.drain(SCOPE)['status'] == 'unconfirmed'
    assert attempted == [] and row(api)['state'] == 'unconfirmed'
