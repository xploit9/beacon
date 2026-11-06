"""Safe lookup recovery and an idle-fenced bridge to the configured repair operator."""
from datetime import datetime, timedelta, timezone
import logging
from pathlib import Path
import sys
import threading
import time

logger = logging.getLogger(__name__)
_BATCH_SIZE = 64


class Runtime:
    def __init__(self, mobile, scoped, version):
        self.mobile, self.scoped = mobile, scoped
        self.runner = None
        self.stop = threading.Event()
        self.thread = None
        self._lifecycle = threading.RLock()
        self._restart_pending = False
        self._restart_waiter = None
        self._restart_wake = None
        self._runner_invoked = False
        self._runner_inflight = 0
        self._runner_state_warned = False
        self._cursor = None
        self.healer = sys.modules['beacon_self_heal'].Healer(mobile, version, self.recover, self.submit, self.idle)

    def recover(self, scope, incident):
        # Cached server discovery cannot establish recovery of a phone/provider path.
        return {'status': 'unverified'}

    def idle(self, scope):
        if self.runner is None: return False
        admin = sys.modules.get('beacon_admin_api')
        native = sys.modules.get('gateway.run_api')
        runner = getattr(native, '_ACTIVE_RUNNER', None)
        if admin is None: return False
        try:
            if admin.update_in_progress() is not False: return False
            if runner is not None:
                active = runner._active_work_count()
                if (getattr(runner, '_draining', None) is not False or
                        getattr(runner, '_external_drain_active', False) is not False or type(active) is not int or active != 0):
                    return False
            from gateway import status
            from hermes_cli.web_server_idle_proof import idle_proof
            root = self.scoped(scope[2], lambda home: Path(home), hydrate_secrets=False)
            record = status.read_runtime_status(root / 'gateway_state.json')
            pid = status.get_runtime_status_running_pid(record, expected_home=root) if isinstance(record, dict) else None
            if pid is None:
                shared = status.multiplexer_liveness_for_profile(root)
                if not isinstance(shared, tuple) or len(shared) != 2: return False
                pid, record = shared
            if (not isinstance(record, dict) or type(pid) is not int or pid <= 0 or type(record.get('pid')) is not int or record['pid'] != pid or
                    status.runtime_status_is_stale(record) is not False or record.get('gateway_state') != 'running' or
                    type(record.get('active_agents')) is not int or record['active_agents'] != 0 or
                    record.get('restart_requested') is not False):
                return False
            stamp = datetime.fromisoformat(record['updated_at'].replace('Z', '+00:00'))
            if stamp.tzinfo is None or stamp > datetime.now(timezone.utc) + timedelta(seconds=5): return False
            if idle_proof().get('idle') is not True: return False
            with self.mobile.LOCK, self.mobile.connect() as db:
                owners = [row[0] for row in db.execute('SELECT DISTINCT owner FROM calls WHERE closed=0')]
                return not any(self.mobile.active_calls(db, owner) for owner in owners)
        except BaseException:
            return False

    def submit(self, scope, request_key, incident):
        if incident['error_code'] in {'http_400', 'http_401', 'http_403', 'http_404', 'http_409', 'http_422', 'http_429'}:
            return {'status': 'not_needed'}
        # The operator must repeat this check atomically at its own admission boundary.
        if not self.idle(scope): return {'status': 'deferred'}
        with self._lifecycle:
            if self.stop.is_set() or self.runner is None: return {'status': 'deferred'}
            runner = self.runner
            self._runner_invoked = True
            self._runner_inflight += 1
        try: return runner.submit(scope, request_key, incident)
        finally:
            with self._lifecycle: self._runner_inflight -= 1

    def attach(self, runner):
        if not callable(getattr(runner, 'submit', None)) or not callable(getattr(runner, 'drain', None)):
            raise ValueError('Invalid repair operator')
        with self._lifecycle:
            if self.runner is not None and self.runner is not runner:
                if self._runner_inflight or self._runner_pending(): raise ValueError('Repair operator is active')
                self._runner_invoked = False
                self._runner_state_warned = False
            self.runner = runner

    def _runner_pending(self):
        runner = self.runner
        if runner is None: return False
        probe = getattr(runner, 'callbacks_running', None)
        if not callable(probe): return self._runner_invoked
        try:
            count = probe()
            if type(count) is not int or count < 0: raise ValueError()
            self._runner_state_warned = False
            return count != 0
        except BaseException:
            if not self._runner_state_warned:
                logger.warning('healing_runner_state_unknown')
                self._runner_state_warned = True
            return True

    def tick(self, stop_event=None):
        stop = stop_event or self.stop
        if stop.is_set(): return
        scopes = []
        try:
            with self.mobile.LOCK, self.mobile.connect() as db:
                exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='healing_incidents'").fetchone()
                if exists:
                    now = time.time()
                    where = "((job_state='deferred' AND state!='recovering' AND worker_id IS NULL AND next_submit<=?) OR (state='open' AND job_state='none' AND worker_id IS NULL AND next_attempt<=?) OR (worker_id IS NOT NULL AND (updated<=? OR EXISTS (SELECT 1 FROM healing_workers w WHERE w.owner=healing_incidents.owner AND w.auth_provider=healing_incidents.auth_provider AND w.profile=healing_incidents.profile AND w.worker_id=healing_incidents.worker_id AND w.pid=0 AND w.started<=?))))"
                    base = 'SELECT DISTINCT owner,auth_provider,profile FROM healing_incidents WHERE ' + where
                    order = ' ORDER BY owner,auth_provider,profile LIMIT ?'
                    core = sys.modules['beacon_self_heal']
                    args = (now, now, now - core.STALE_SECONDS, now - core.START_GRACE_SECONDS)
                    if self._cursor is None:
                        scopes = db.execute(base + order, (*args, _BATCH_SIZE)).fetchall()
                    else:
                        scopes = db.execute(base + ' AND (owner,auth_provider,profile)>(?,?,?)' + order,
                                            (*args, *self._cursor, _BATCH_SIZE)).fetchall()
                        if len(scopes) < _BATCH_SIZE:
                            scopes += db.execute(base + ' AND (owner,auth_provider,profile)<=(?,?,?)' + order,
                                                 (*args, *self._cursor, _BATCH_SIZE - len(scopes))).fetchall()
                    if scopes: self._cursor = tuple(scopes[-1])
        except Exception:
            logger.warning('healing_scope_scan_failed')
        for row in scopes:
            if stop.is_set(): return
            try: self.healer.drain(tuple(row))
            except BaseException: logger.warning('healing_scope_dispatch_failed')
        if stop.is_set(): return
        with self._lifecycle:
            if stop.is_set(): return
            runner = self.runner
            if runner is not None:
                self._runner_invoked = True
                self._runner_inflight += 1
        if runner is not None:
            try:
                try: runner.drain()
                except BaseException: logger.warning('healing_runner_drain_failed')
            finally:
                with self._lifecycle: self._runner_inflight -= 1

    def _start_locked(self):
        self.healer.resume()
        self.stop = threading.Event()
        stop = self.stop
        def watch():
            try:
                while not stop.is_set():
                    try: self.tick(stop)
                    except BaseException: logger.warning('healing_watch_tick_failed')
                    stop.wait(5)
            finally:
                with self._lifecycle:
                    if self.thread is threading.current_thread():
                        self.thread = None
        self.thread = threading.Thread(target=watch, name='beacon-repair-operator', daemon=True)
        try:
            self.thread.start()
            return True
        except Exception:
            if self.thread.ident is None: self.thread = None
            stop.set()
            self.healer.pause()
            logger.warning('healing_watcher_start_failed')
            return False

    def _wait_restart_locked(self):
        if self._restart_waiter is not None and self._restart_waiter.is_alive(): return True
        wake = threading.Event()
        self._restart_wake = wake
        def wait_for_quiescence():
            while not wake.wait(.1):
                with self._lifecycle:
                    if not self._restart_pending or self._restart_wake is not wake: return
                    if self.thread is not None and self.thread.is_alive(): continue
                    if not self.healer.wait(0) or self._runner_inflight or self._runner_pending(): continue
                    self._restart_pending = False
                    self._restart_waiter = None
                    self._start_locked()
                    return
        waiter = threading.Thread(target=wait_for_quiescence, name='beacon-repair-restart', daemon=True)
        self._restart_waiter = waiter
        try:
            waiter.start()
            return True
        except Exception:
            self._restart_waiter = None
            self._restart_pending = False
            logger.warning('healing_restart_waiter_failed')
            return False

    def start(self):
        with self._lifecycle:
            if self.thread is not None and self.thread.is_alive():
                if not self.stop.is_set(): return {'started': False, 'restart_pending': False}
            if (self.thread is not None and self.thread.is_alive()) or not self.healer.wait(0) or self._runner_inflight or self._runner_pending():
                self._restart_pending = True
                return {'started': False, 'restart_pending': self._wait_restart_locked()}
            self._restart_pending = False
            started = self._start_locked()
            return {'started': started, 'restart_pending': False}

    def close(self, timeout=.25):
        timeout = max(0, min(float(timeout), 1.0))
        deadline = time.monotonic() + timeout
        with self._lifecycle:
            self._restart_pending = False
            if self._restart_wake is not None: self._restart_wake.set()
            self.stop.set()
            self.healer.pause()
            thread = self.thread
            waiter = self._restart_waiter
        if thread is not None and thread is not threading.current_thread():
            thread.join(max(0, deadline - time.monotonic()))
        if waiter is not None and waiter is not threading.current_thread():
            waiter.join(max(0, deadline - time.monotonic()))
        workers_done = self.healer.wait(max(0, deadline - time.monotonic()))
        with self._lifecycle:
            alive = self.thread is not None and self.thread.is_alive()
            pending = self._runner_inflight > 0 or self._runner_pending()
        running = alive or not workers_done or pending
        if running: logger.warning('healing_callbacks_still_running')
        return {'stopped': not running, 'callbacks_running': running}
