"""Synthetic runtime dispatch, lifecycle and idle-status regressions."""
import importlib.util
import os
from datetime import datetime, timedelta, timezone
from pathlib import Path
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from test_self_heal import Mobile, load_module, incident, wait_for

ROOT = Path(__file__).resolve().parents[1]
NATIVE = Path(os.environ['HERMES_AGENT_SOURCE']) if 'HERMES_AGENT_SOURCE' in os.environ else Path.home() / 'AppData/Local/hermes/hermes-agent'
SCOPE = ('owner', 'password', 'default')


def load_runtime(monkeypatch, mobile, scoped=None):
    core = load_module()
    monkeypatch.setitem(sys.modules, 'beacon_self_heal', core)
    spec = importlib.util.spec_from_file_location('beacon_healing_runtime_test', ROOT / 'healing_runtime.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module.Runtime(mobile, scoped or (lambda profile, fn, **kw: fn(mobile.path.parent)), '1.1.31')


@pytest.fixture
def env(tmp_path, monkeypatch):
    mobile = Mobile(tmp_path / 'synthetic.sqlite')
    with mobile.connect() as db: db.execute('CREATE TABLE calls (owner TEXT, closed INTEGER)')
    mobile.active_calls = lambda db, owner: []
    return load_runtime(monkeypatch, mobile), mobile


class Runner:
    def __init__(self): self.drains = 0
    def submit(self, *args): return {'status':'deferred'}
    def drain(self): self.drains += 1


def test_cached_tool_catalogue_never_claims_verified_recovery(env, monkeypatch):
    runtime, _ = env
    class Gateway:
        def __init__(self, *args): self.backend = SimpleNamespace(scoped_metadata=lambda *args: None)
        def discover(self, *args): return {'tools':[{'tool_id':'t', 'name':'n'}], 'still_starting':True}
    monkeypatch.setitem(sys.modules, 'beacon_gateway_tools', SimpleNamespace(Gateway=Gateway))
    result = runtime.recover(SCOPE, {'component':'tools', 'operation':'discover_tools'})
    assert result != {'status':'recovered', 'verified':True}
    assert result.get('verified') is not True


def test_detached_dashboard_uses_fresh_native_idle_evidence_and_fails_closed(env, monkeypatch):
    runtime, mobile = env; runtime.attach(Runner())
    native_source = NATIVE
    monkeypatch.syspath_prepend(str(native_source))
    from gateway import status
    from hermes_cli import web_server_idle_proof
    actual_staleness = status.runtime_status_is_stale
    record = {'gateway_state':'running', 'active_agents':0, 'restart_requested':False, 'pid':123,
              'updated_at':datetime.now(timezone.utc).isoformat()}
    monkeypatch.setattr(status, 'read_runtime_status', lambda *args: record)
    monkeypatch.setattr(status, 'runtime_status_is_stale', lambda value: False)
    monkeypatch.setattr(status, 'get_runtime_status_running_pid', lambda value, **kwargs: 123)
    monkeypatch.setattr(status, 'multiplexer_liveness_for_profile', lambda home: None)
    monkeypatch.setattr(web_server_idle_proof, 'idle_proof', lambda: {'idle':True})
    monkeypatch.setitem(sys.modules, 'beacon_admin_api', SimpleNamespace(update_in_progress=lambda:False))
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=None))
    monkeypatch.setitem(sys.modules, 'tui_gateway.server', SimpleNamespace(_sessions_lock=threading.RLock(), _sessions={}))
    assert runtime.idle(SCOPE) is True
    for invalid in (None, True, -1, '0', 1):
        record['active_agents'] = invalid
        assert runtime.idle(SCOPE) is False
    record['active_agents'] = 0
    record['gateway_state'] = 'draining'
    assert runtime.idle(SCOPE) is False
    record['gateway_state'] = 'running'
    monkeypatch.setattr(status, 'runtime_status_is_stale', lambda value: True)
    assert runtime.idle(SCOPE) is False
    monkeypatch.setattr(status, 'runtime_status_is_stale', actual_staleness)
    record['updated_at'] = (datetime.now(timezone.utc) - timedelta(seconds=121)).isoformat()
    assert runtime.idle(SCOPE) is False
    record['updated_at'] = (datetime.now(timezone.utc) + timedelta(minutes=5)).isoformat()
    assert runtime.idle(SCOPE) is False
    record['updated_at'] = datetime.now(timezone.utc).isoformat()
    for invalid in (None, True, 0, -1, '123'):
        record['pid'] = invalid
        assert runtime.idle(SCOPE) is False
    record['pid'] = 123
    for invalid in (None, True, 0):
        record['restart_requested'] = invalid
        assert runtime.idle(SCOPE) is False
    record['restart_requested'] = False
    monkeypatch.setattr(web_server_idle_proof, 'idle_proof', lambda: {'idle':None})
    assert runtime.idle(SCOPE) is False
    monkeypatch.setattr(web_server_idle_proof, 'idle_proof', lambda: {'idle':True})
    with mobile.LOCK, mobile.connect() as db: db.execute("INSERT INTO calls VALUES ('owner',0)")
    mobile.active_calls = lambda db, owner: 1
    assert runtime.idle(SCOPE) is False


def test_fair_scope_rotation_reaches_beyond_first_window(env):
    runtime, mobile = env; runtime.attach(Runner())
    for index in range(130):
        runtime.healer.record((f'owner-{index:03d}', 'password', 'default'), incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=0")
    seen = []
    runtime.healer.drain = lambda scope: seen.append(scope)
    runtime.tick(); runtime.tick(); runtime.tick()
    assert len({scope[0] for scope in seen}) == 130


def test_cooldown_scopes_do_not_hide_ready_scopes_beyond_the_batch(env):
    runtime, mobile = env; runtime.attach(Runner())
    for index in range(70):
        runtime.healer.record((f'owner-{index:03d}', 'password', 'default'), incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=? WHERE owner<?", (time.time()+3600, 'owner-064'))
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=0 WHERE owner>=?", ('owner-064',))
    seen = []
    runtime.healer.drain = lambda scope: seen.append(scope[0])
    runtime.tick()
    assert seen == [f'owner-{index:03d}' for index in range(64,70)]


def test_multiplexed_gateway_status_is_scoped_fresh_and_identity_checked(env, monkeypatch):
    runtime, mobile = env; runtime.attach(Runner())
    monkeypatch.syspath_prepend(str(NATIVE))
    from gateway import status
    from hermes_cli import web_server_idle_proof
    record = {'gateway_state':'running', 'active_agents':0, 'restart_requested':False, 'pid':123,
              'updated_at':datetime.now(timezone.utc).isoformat()}
    seen = []
    def scoped(profile, fn, *, hydrate_secrets):
        seen.append((profile, hydrate_secrets))
        return fn(mobile.path.parent)
    runtime.scoped = scoped
    monkeypatch.setattr(status, 'read_runtime_status', lambda *args: None)
    monkeypatch.setattr(status, 'multiplexer_liveness_for_profile', lambda home: (123,record))
    monkeypatch.setattr(web_server_idle_proof, 'idle_proof', lambda: {'idle':True})
    monkeypatch.setitem(sys.modules, 'beacon_admin_api', SimpleNamespace(update_in_progress=lambda:False))
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=None))
    assert runtime.idle(SCOPE) is True and seen == [('default',False)]
    record['pid'] = 124
    assert runtime.idle(SCOPE) is False


def test_watcher_reclaims_never_activated_claim_after_restart(env, monkeypatch):
    runtime, mobile = env; runtime.attach(Runner())
    core = sys.modules['beacon_self_heal']
    monkeypatch.setattr(core, 'START_GRACE_SECONDS', 0)
    monkeypatch.setattr(core, 'COOLDOWN_SECONDS', 0)
    item = runtime.healer.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET state='recovering',attempts=1,next_attempt=0,worker_id='unstarted',updated=0 WHERE id=?", (item['id'],))
        db.execute('INSERT INTO healing_workers VALUES (?,?,?,?,?,?)', (*SCOPE, 'unstarted', 0, 0))
    runtime.tick()
    value = wait_for(lambda: (row if (row := runtime.healer.list(SCOPE)[0])['job_state'] == 'deferred' else None))
    assert value['attempts'] == 2 and value['state'] == 'unverified'


def test_one_bad_scope_does_not_stop_later_scopes_or_runner(env, caplog):
    runtime, mobile = env; runner = Runner(); runtime.attach(runner)
    for owner in ('a', 'b'):
        runtime.healer.record((owner, 'password', 'default'), incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=0")
    seen = []
    def drain(scope):
        seen.append(scope[0])
        if scope[0] == 'a': raise RuntimeError('private-scope-canary')
    runtime.healer.drain = drain
    runtime.tick()
    assert seen == ['a','b'] and runner.drains == 1
    assert 'private-scope-canary' not in caplog.text


def test_runner_failure_is_isolated_and_logged_without_its_message(env, caplog):
    runtime, _ = env
    class Broken(Runner):
        def drain(self): raise RuntimeError('private-runner-canary')
    runtime.attach(Broken())
    runtime.tick()
    assert 'healing_runner_drain_failed' in caplog.text and 'private-runner-canary' not in caplog.text


def test_nonstandard_callback_failures_do_not_kill_the_tick_or_leak(env, caplog):
    runtime, mobile = env
    class Abort(BaseException): pass
    class Broken(Runner):
        def drain(self): raise Abort('private-runner-abort')
    runtime.attach(Broken())
    for owner in ('a', 'b'): runtime.healer.record((owner, 'password', 'default'), incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=0")
    seen = []
    def drain(scope):
        seen.append(scope[0])
        if scope[0] == 'a': raise Abort('private-scope-abort')
    runtime.healer.drain = drain
    runtime.tick()
    assert seen == ['a','b'] and 'healing_runner_drain_failed' in caplog.text
    assert 'private-scope-abort' not in caplog.text and 'private-runner-abort' not in caplog.text


def test_worker_start_failure_is_reported_and_does_not_leave_a_false_running_watcher(env, monkeypatch):
    runtime, _ = env; runtime.attach(Runner())
    def fail(*args): raise RuntimeError('private-start-canary')
    monkeypatch.setattr(threading.Thread, 'start', fail)
    assert runtime.start() == {'started':False, 'restart_pending':False}
    assert runtime.thread is None


def test_start_exception_after_thread_started_does_not_hide_live_callback(env, monkeypatch):
    runtime, _ = env; runtime.attach(Runner())
    entered = threading.Event(); release = threading.Event(); original = threading.Thread.start
    def tick(*args): entered.set(); release.wait(3)
    runtime.tick = tick
    def started_then_failed(thread):
        original(thread)
        assert entered.wait(2)
        raise RuntimeError('private-start-canary')
    monkeypatch.setattr(threading.Thread, 'start', started_then_failed)
    try:
        assert runtime.start() == {'started':False, 'restart_pending':False}
        assert entered.wait(2)
        assert runtime.close(timeout=.02) == {'stopped':False, 'callbacks_running':True}
    finally: release.set()


def test_stop_is_checked_after_last_scope_before_runner(env):
    runtime, mobile = env; runner = Runner(); runtime.attach(runner)
    runtime.healer.record(SCOPE, incident())
    with mobile.LOCK, mobile.connect() as db:
        db.execute("UPDATE healing_incidents SET job_state='deferred',next_submit=0")
    runtime.healer.drain = lambda scope: runtime.stop.set()
    runtime.tick()
    assert runner.drains == 0


def test_close_reports_inflight_and_restart_waits_for_prior_generation(env):
    runtime, _ = env; runtime.attach(Runner())
    entered = threading.Event(); release = threading.Event(); restarted = threading.Event()
    calls = []
    def tick(*args):
        calls.append(threading.current_thread())
        if len(calls) == 1:
            entered.set(); release.wait(3)
        else: restarted.set()
    runtime.tick = tick
    runtime.start(); assert entered.wait(2)
    try:
        status = runtime.close(timeout=.02)
        assert status == {'stopped':False, 'callbacks_running':True}
        assert runtime.start() == {'started':False, 'restart_pending':True}
    finally: release.set()
    assert restarted.wait(2) and calls[0] is not calls[1]
    assert runtime.close(timeout=1) == {'stopped':True, 'callbacks_running':False}


def test_restart_waits_for_tracked_runner_callbacks_to_finish(env):
    runtime, _ = env
    class Tracked(Runner):
        running = 0
        def callbacks_running(self): return self.running
    runner = Tracked(); runtime.attach(runner)
    first = threading.Event(); second = threading.Event(); calls = []
    def tick(*args):
        calls.append(threading.current_thread())
        (first if len(calls) == 1 else second).set()
    runtime.tick = tick
    runtime.start(); assert first.wait(2)
    runner.running = 1
    assert runtime.close(timeout=.05) == {'stopped':False, 'callbacks_running':True}
    assert runtime.start() == {'started':False, 'restart_pending':True}
    assert not second.wait(.2)
    runner.running = 0
    assert second.wait(2) and calls[0] is not calls[1]
    assert runtime.close(timeout=1) == {'stopped':True, 'callbacks_running':False}


def test_unknown_runner_quiescence_is_not_reported_as_stopped(env):
    runtime, _ = env; runtime.attach(Runner())
    runtime.tick()
    assert runtime.close(timeout=.02) == {'stopped':False, 'callbacks_running':True}


def test_unknown_runner_state_does_not_spam_private_callback_errors(env, caplog):
    runtime, _ = env
    class Broken(Runner):
        def callbacks_running(self): raise RuntimeError('private-probe-canary')
    runtime.attach(Broken())
    try:
        assert runtime.start() == {'started':False, 'restart_pending':True}
        time.sleep(.35)
        assert caplog.text.count('healing_runner_state_unknown') == 1
        assert 'private-probe-canary' not in caplog.text
    finally: runtime.close(timeout=.2)


def test_operator_can_change_only_after_its_work_is_quiescent(env):
    runtime, _ = env
    class Tracked(Runner):
        running = 0
        def callbacks_running(self): return self.running
    first = Tracked(); second = Tracked(); third = Tracked()
    runtime.attach(first)
    runtime.attach(second)
    assert runtime.runner is second
    second.running = 1
    with pytest.raises(ValueError): runtime.attach(third)
    second.running = 0
    runtime.attach(third)
    assert runtime.runner is third


def test_close_reports_a_direct_runner_stage_until_it_actually_returns(env):
    runtime, _ = env; entered = threading.Event(); release = threading.Event()
    class Blocking(Runner):
        def callbacks_running(self): return 0
        def drain(self): entered.set(); release.wait(3)
    runtime.attach(Blocking())
    worker = threading.Thread(target=runtime.tick)
    worker.start(); assert entered.wait(2)
    try:
        assert runtime.close(timeout=.02) == {'stopped':False, 'callbacks_running':True}
    finally:
        release.set(); worker.join(2)
