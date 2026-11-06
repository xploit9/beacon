"""Private, bounded incident metadata and callback-driven recovery for Beacon."""
import contextvars
from contextlib import contextmanager
import hashlib
import json
import logging
import os
import re
import sqlite3
import threading
import time
import uuid

logger = logging.getLogger(__name__)

MAX_INCIDENTS = 512
MAX_UPLOAD_RECEIPTS = 8192
MAX_ATTEMPTS = 3
MAX_JOB_ATTEMPTS = 3
MAX_WORKERS = 4
MAX_OBSERVED_VERSIONS = 8
COOLDOWN_SECONDS = 30
JOB_COOLDOWN_SECONDS = 5
STALE_SECONDS = 300
START_GRACE_SECONDS = 15
COMPONENTS = frozenset({'voice', 'app', 'gateway', 'plugin', 'sync', 'tools', 'memory', 'network', 'chat', 'settings', 'updates', 'vault'})
OPERATIONS = frozenset({'connect', 'startup', 'greeting', 'capture', 'playout', 'context', 'discover_tools', 'invoke_tool',
                        'lookup', 'sync', 'request', 'history', 'auth', 'render', 'load', 'save', 'send', 'update',
                        'fill', 'signup', 'payment'})
ERROR_CODES = frozenset({'timeout', 'network', 'dns', 'tls', 'invalid_response', 'provider_error', 'connection_closed',
                         'queue_overflow', 'unavailable', 'hermes_updating', 'admission_rejected', 'memory_lookup_busy',
                         'memory_lookup_timeout', 'memory_lookup_failed', 'tool_http_failure', 'unknown',
                         'outcome_unknown', 'submission_unconfirmed', 'process_crash', 'process_anr',
                         'process_low_memory', 'process_resource_limit'})
PROVIDERS = frozenset({'gemini', 'grok', 'openai', 'native'})
REQUIRED_CHECKS = frozenset({'reproduction', 'regression', 'tests', 'build', 'lint', 'review', 'gitleaks', 'privacy',
                             'signer', 'versions', 'fresh_downloads', 'release'})
UNCERTAIN = frozenset({'outcome_unknown', 'submission_unconfirmed'})
_FIELDS = frozenset({'component', 'operation', 'error_code', 'app_version_code', 'provider', 'call_id', 'request_id', 'stack_fingerprint'})
_OPAQUE = re.compile(r'(?:[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12})\Z')
_JOB = re.compile(r'(?:job-[0-9]{1,20}|job-[0-9a-f]{32}|[0-9a-f]{32}|[1-9][0-9]{0,19})\Z')
_NUMBER = r'(?:0|[1-9][0-9]*)'
_PRE = r'(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)'
_BUILD = r'[0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*'
_VERSION = re.compile(rf'v?{_NUMBER}\.{_NUMBER}\.{_NUMBER}(?:-{_PRE}(?:\.{_PRE})*)?(?:\+{_BUILD})?\Z')
_COMMIT = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')


class InvalidIncident(ValueError): pass
class IncidentConflict(ValueError): pass
class IncidentCapacity(ValueError): pass


def _scope(value):
    if not isinstance(value, tuple) or len(value) != 3: raise ValueError('Invalid scope')
    for field, limit in zip(value, (256, 256, 80)):
        if (not isinstance(field, str) or not field.strip() or len(field) > limit or
                any(ord(char) < 32 or ord(char) == 127 or 0xD800 <= ord(char) <= 0xDFFF for char in field)):
            raise ValueError('Invalid scope')
    return value


def _metadata(payload, version):
    if not isinstance(payload, dict) or set(payload) - _FIELDS or not {'component', 'operation', 'error_code'} <= set(payload):
        raise ValueError('Invalid incident')
    component, operation, code = payload['component'], payload['operation'], payload['error_code']
    if (not isinstance(component, str) or component not in COMPONENTS or not isinstance(operation, str) or operation not in OPERATIONS or
            not isinstance(code, str) or not (code in ERROR_CODES or re.fullmatch(r'http_[45][0-9]{2}', code))):
        raise ValueError('Invalid incident')
    app_version = payload.get('app_version_code')
    if app_version is not None and (type(app_version) is not int or not 1 <= app_version <= 2147483647):
        raise ValueError('Invalid incident')
    provider = payload.get('provider')
    if provider is not None and (not isinstance(provider, str) or provider not in PROVIDERS): raise ValueError('Invalid incident')
    result = {'component': component, 'operation': operation, 'error_code': code, 'app_version_code': app_version,
              'provider': provider, 'plugin_version': version}
    for name in ('call_id', 'request_id'):
        if name in payload:
            if not isinstance(payload[name], str) or not _OPAQUE.fullmatch(payload[name]): raise ValueError('Invalid incident')
            result[name] = payload[name]
    if 'stack_fingerprint' in payload:
        if not isinstance(payload['stack_fingerprint'], str) or not re.fullmatch(r'[0-9a-f]{64}', payload['stack_fingerprint']):
            raise ValueError('Invalid incident')
        result['stack_fingerprint'] = payload['stack_fingerprint']
    return result


def _fingerprint(value):
    fields = ('component', 'operation', 'error_code', 'stack_fingerprint', 'provider')
    return hashlib.sha256(json.dumps([value.get(name) for name in fields], separators=(',', ':')).encode()).hexdigest()


def _request_key(incident_id, generation):
    return hashlib.sha256((incident_id + ':' + str(generation)).encode()).hexdigest()


def _valid_version(value):
    return isinstance(value, str) and len(value) <= 64 and _VERSION.fullmatch(value) is not None


def _version_pair(value):
    if not isinstance(value, dict): return None
    app, plugin = value.get('app_version_code'), value.get('plugin_version')
    if app is not None and (type(app) is not int or not 1 <= app <= 2147483647): return None
    if plugin is not None and not _valid_version(plugin): return None
    return {'app_version_code': app, 'plugin_version': plugin}


def _new_observation(baseline, value):
    old, new = _version_pair(baseline), _version_pair(value)
    if new is None: return False
    old = old or {'app_version_code': None, 'plugin_version': None}
    return any(new[name] is not None and new[name] != old[name] for name in ('app_version_code', 'plugin_version'))


def _version_history(value, previous=None):
    previous = previous if isinstance(previous, dict) else {}
    entries = previous.get('observed_versions')
    entries = entries if isinstance(entries, list) else []
    history = []
    for entry in entries[-MAX_OBSERVED_VERSIONS:]:
        pair = _version_pair(entry)
        if pair is not None and pair not in history: history.append(pair)
    if not history and previous:
        pair = _version_pair(previous)
        if pair is not None: history.append(pair)
    pair = _version_pair(value)
    if pair in history: history.remove(pair)
    history.append(pair)
    value['observed_versions'] = history[-MAX_OBSERVED_VERSIONS:]
    for name in ('job_input_version', 'published_repair_input_version'):
        if name in previous: value[name] = _version_pair(previous[name])
    if previous.get('_job_version_changed') is True: value['_job_version_changed'] = True
    return value


def _process_dead(pid):
    """Only a process proven absent can release a stale worker's claim."""
    if type(pid) is not int or pid <= 0: return False
    if pid == os.getpid(): return False
    if os.name == 'nt':
        try:
            import ctypes
            from ctypes import wintypes
            kernel = ctypes.WinDLL('kernel32', use_last_error=True)
            kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
            kernel.OpenProcess.restype = wintypes.HANDLE
            kernel.WaitForSingleObject.argtypes = (wintypes.HANDLE, wintypes.DWORD)
            kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
            handle = kernel.OpenProcess(0x00100000, False, pid)
            if not handle: return ctypes.get_last_error() == 87
            try: return kernel.WaitForSingleObject(handle, 0) == 0
            finally: kernel.CloseHandle(handle)
        except Exception: return False
    try: os.kill(pid, 0)
    except ProcessLookupError: return True
    except OSError: return False
    return False


class Healer:
    InvalidIncident = InvalidIncident
    IncidentConflict = IncidentConflict
    IncidentCapacity = IncidentCapacity

    def __init__(self, mobile, version, runtime_repair, submit_fix, can_submit):
        if version is not None and not _valid_version(version): raise ValueError('Invalid version')
        if not all(callable(value) for value in (runtime_repair, submit_fix, can_submit)): raise ValueError('Invalid callbacks')
        self.mobile, self.version = mobile, version
        self.runtime_repair, self.submit_fix, self.can_submit = runtime_repair, submit_fix, can_submit
        self._lifecycle = threading.RLock()
        self._enabled, self._epoch, self._workers = True, 0, set()

    def pause(self):
        with self._lifecycle:
            self._enabled = False
            self._epoch += 1
            return {'callbacks_running': bool(self._workers)}

    def resume(self):
        with self._lifecycle:
            self._enabled = True

    def wait(self, timeout):
        deadline = time.monotonic() + max(0, timeout)
        with self._lifecycle: workers = tuple(self._workers)
        for worker in workers:
            if worker is not threading.current_thread(): worker.join(max(0, deadline - time.monotonic()))
        with self._lifecycle: return not self._workers

    def _allowed(self, epoch=None):
        with self._lifecycle:
            return self._enabled and (epoch is None or epoch == self._epoch)

    def _admission_epoch(self):
        with self._lifecycle: return self._epoch if self._enabled else None

    @contextmanager
    def _db(self):
        try:
            with self.mobile.LOCK, self.mobile.connect() as db:
                db.execute('PRAGMA synchronous=FULL')
                db.execute('''CREATE TABLE IF NOT EXISTS healing_incidents (
                    id TEXT PRIMARY KEY, owner TEXT NOT NULL, auth_provider TEXT NOT NULL, profile TEXT NOT NULL,
                    fingerprint TEXT NOT NULL, metadata TEXT NOT NULL, occurrences INTEGER NOT NULL,
                    state TEXT NOT NULL, attempts INTEGER NOT NULL, next_attempt REAL NOT NULL,
                    job_state TEXT NOT NULL, job_id TEXT, generation INTEGER NOT NULL,
                    release TEXT, commit_sha TEXT, created REAL NOT NULL, updated REAL NOT NULL, worker_id TEXT,
                    job_attempts INTEGER NOT NULL, next_submit REAL NOT NULL, probe_dirty INTEGER NOT NULL,
                    UNIQUE(owner,auth_provider,profile,fingerprint))''')
                db.execute('''CREATE TABLE IF NOT EXISTS healing_workers (
                    owner TEXT NOT NULL, auth_provider TEXT NOT NULL, profile TEXT NOT NULL, worker_id TEXT NOT NULL,
                    pid INTEGER NOT NULL, started REAL NOT NULL, PRIMARY KEY(owner,auth_provider,profile))''')
                db.execute('''CREATE TABLE IF NOT EXISTS healing_uploads (
                    owner TEXT NOT NULL, auth_provider TEXT NOT NULL, profile TEXT NOT NULL, event_id TEXT NOT NULL,
                    payload_hash TEXT NOT NULL, incident_id TEXT NOT NULL,
                    PRIMARY KEY(owner,auth_provider,profile,event_id))''')
                yield db
        except (sqlite3.Error, OSError):
            raise RuntimeError('Incident storage unavailable') from None

    @staticmethod
    def _view(row):
        value = json.loads(row['metadata'])
        value.pop('_job_version_changed', None)
        value.update({name: row[name] for name in ('id', 'fingerprint', 'occurrences', 'state', 'attempts', 'next_attempt',
                                                   'job_state', 'job_id', 'job_attempts', 'next_submit', 'generation', 'release', 'created', 'updated')})
        value['commit'] = row['commit_sha']
        return value

    @staticmethod
    def _find(db, scope, incident_id):
        if not isinstance(incident_id, str) or not re.fullmatch(r'[0-9a-f]{32}', incident_id): raise LookupError('Incident not found')
        row = db.execute('SELECT * FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND id=?',
                         (*scope, incident_id)).fetchone()
        if row is None: raise LookupError('Incident not found')
        return row

    @staticmethod
    def _clear_unstarted(db, scope, token, now):
        removed = db.execute('DELETE FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=? AND pid=0', (*scope, token)).rowcount
        if removed != 1: return
        db.execute('''UPDATE healing_incidents SET
                   state=CASE WHEN state='recovering' AND attempts<? THEN 'open'
                              WHEN state='recovering' THEN 'unverified' ELSE state END,
                   job_state=CASE WHEN state='recovering' AND attempts>=? AND job_state='none' THEN 'deferred'
                                  WHEN job_state='dispatching' THEN 'deferred' ELSE job_state END,
                   worker_id=NULL,probe_dirty=0,updated=? WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?''',
                   (MAX_ATTEMPTS, MAX_ATTEMPTS, now, *scope, token))

    @staticmethod
    def _stale(db, scope, now):
        worker = db.execute('SELECT * FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=?', scope).fetchone()
        if worker is not None and worker['pid'] == 0 and now - worker['started'] >= START_GRACE_SECONDS:
            Healer._clear_unstarted(db, scope, worker['worker_id'], now)
        elif worker is not None and now - worker['started'] >= STALE_SECONDS and _process_dead(worker['pid']):
            db.execute('''UPDATE healing_incidents SET state=CASE WHEN state='recovering' THEN 'unconfirmed' ELSE state END,
                       job_state=CASE WHEN job_state='submitting' THEN 'unconfirmed'
                                      WHEN job_state IN ('none','dispatching') THEN 'deferred' ELSE job_state END,
                       worker_id=NULL,probe_dirty=0,updated=? WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?''',
                       (now, *scope, worker['worker_id']))
            db.execute('DELETE FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?',
                       (*scope, worker['worker_id']))
        db.execute('''UPDATE healing_incidents SET state=CASE WHEN state='recovering' THEN 'unconfirmed' ELSE state END,
                   job_state=CASE WHEN job_state='submitting' THEN 'unconfirmed'
                                  WHEN job_state IN ('none','dispatching') THEN 'deferred' ELSE job_state END,updated=?
                   WHERE owner=? AND auth_provider=? AND profile=? AND worker_id IS NULL AND updated<=?
                   AND (state='recovering' OR job_state IN ('submitting','dispatching'))''', (now, *scope, now - STALE_SECONDS))
        db.execute('''UPDATE healing_incidents SET state=CASE WHEN state='recovering' THEN 'unconfirmed' ELSE state END,
                   job_state=CASE WHEN job_state IN ('submitting','dispatching') THEN 'unconfirmed' ELSE job_state END,updated=?
                   WHERE owner=? AND auth_provider=? AND profile=? AND worker_id IS NOT NULL AND updated<=?
                   AND NOT EXISTS (SELECT 1 FROM healing_workers WHERE healing_workers.owner=healing_incidents.owner
                       AND healing_workers.auth_provider=healing_incidents.auth_provider AND healing_workers.profile=healing_incidents.profile
                       AND healing_workers.worker_id=healing_incidents.worker_id)''', (now, *scope, now - STALE_SECONDS))

    def record(self, scope, payload, *, event_id=None):
        scope = _scope(scope)
        try:
            value = _metadata(payload, self.version)
            if event_id is not None:
                if not isinstance(event_id, str) or str(uuid.UUID(event_id)) != event_id: raise ValueError()
        except (ValueError, AttributeError):
            raise InvalidIncident('Invalid incident') from None
        payload_hash = hashlib.sha256(json.dumps(payload, sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        fingerprint = _fingerprint(value)
        now = time.time()
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            if event_id is not None:
                receipt = db.execute('SELECT payload_hash,incident_id FROM healing_uploads WHERE owner=? AND auth_provider=? AND profile=? AND event_id=?', (*scope, event_id)).fetchone()
                if receipt is not None:
                    if receipt['payload_hash'] != payload_hash: raise IncidentConflict('Incident identity already used')
                    return self._view(self._find(db, scope, receipt['incident_id']))
                if db.execute('SELECT COUNT(*) FROM healing_uploads').fetchone()[0] >= MAX_UPLOAD_RECEIPTS:
                    raise IncidentCapacity('Incident capacity reached')
            current = db.execute('SELECT * FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND fingerprint=?',
                                 (*scope, fingerprint)).fetchone()
            if current is None:
                if db.execute('SELECT COUNT(*) FROM healing_incidents').fetchone()[0] >= MAX_INCIDENTS:
                    db.execute('''DELETE FROM healing_incidents WHERE id=(SELECT id FROM healing_incidents
                               WHERE state='fix_published' AND worker_id IS NULL
                               AND NOT EXISTS (SELECT 1 FROM healing_uploads WHERE incident_id=healing_incidents.id)
                               ORDER BY updated, id LIMIT 1)''')
                    if db.execute('SELECT COUNT(*) FROM healing_incidents').fetchone()[0] >= MAX_INCIDENTS:
                        raise IncidentCapacity('Incident capacity reached')
                incident_id = uuid.uuid4().hex
                value = _version_history(value)
                db.execute('''INSERT INTO healing_incidents VALUES (?,?,?,?,?,?,1,'open',0,0,'none',NULL,0,NULL,NULL,?,?,NULL,0,0,0)''',
                           (incident_id, *scope, fingerprint, json.dumps(value, separators=(',', ':')), now, now))
            else:
                incident_id = current['id']
                published = current['state'] == 'fix_published'
                previous = json.loads(current['metadata'])
                version_changed = _new_observation(previous.get('job_input_version'), value)
                if current['job_state'] in ('submitting', 'queued') and version_changed: previous['_job_version_changed'] = True
                reconsider = current['job_state'] in ('not_needed', 'update_required') and version_changed
                reset = published or reconsider
                value = _version_history(value, previous)
                db.execute('''UPDATE healing_incidents SET occurrences=MIN(occurrences+1,1000000000),updated=?,metadata=?,
                           probe_dirty=CASE WHEN state='recovering' THEN 1 ELSE probe_dirty END,
                           state=CASE WHEN state IN ('recovered','fix_published') THEN 'open' ELSE state END,
                           next_attempt=CASE WHEN ? THEN 0 ELSE next_attempt END,
                           generation=generation+?,job_state=CASE WHEN ? AND job_attempts>=? THEN 'exhausted'
                                                                 WHEN ? THEN 'deferred' ELSE job_state END,
                           job_id=CASE WHEN ? THEN NULL ELSE job_id END,next_submit=CASE WHEN ? THEN 0 ELSE next_submit END WHERE id=?''',
                           (now, json.dumps(value, separators=(',', ':')), published, int(reset), reset, MAX_JOB_ATTEMPTS, reset, reset, reset, incident_id))
            if event_id is not None:
                db.execute('INSERT INTO healing_uploads VALUES (?,?,?,?,?,?)', (*scope, event_id, payload_hash, incident_id))
            return self._view(self._find(db, scope, incident_id))

    def list(self, scope):
        scope = _scope(scope)
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._stale(db, scope, time.time())
            rows = db.execute('SELECT * FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? ORDER BY updated DESC,id', scope).fetchall()
            return [self._view(row) for row in rows]

    def _claim(self, db, scope, incident_id, mode, now):
        stale = db.execute('SELECT owner,auth_provider,profile FROM healing_workers WHERE (pid=0 AND started<=?) OR started<=?',
                           (now - START_GRACE_SECONDS, now - STALE_SECONDS)).fetchall()
        for item in stale: self._stale(db, (item['owner'], item['auth_provider'], item['profile']), now)
        if db.execute("SELECT 1 FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND (state='recovering' OR worker_id IS NOT NULL) LIMIT 1", scope).fetchone(): return None
        if db.execute('SELECT COUNT(*) FROM healing_workers').fetchone()[0] >= MAX_WORKERS: return None
        token = uuid.uuid4().hex
        try:
            # pid=0 means no callback can have started; the worker atomically activates first.
            db.execute('INSERT INTO healing_workers VALUES (?,?,?,?,?,?)', (*scope, token, 0, now))
        except sqlite3.IntegrityError: return None
        if mode == 'recover':
            db.execute("UPDATE healing_incidents SET state='recovering',attempts=attempts+1,next_attempt=?,worker_id=?,probe_dirty=0,updated=? WHERE id=?",
                       (now + COOLDOWN_SECONDS, token, now, incident_id))
        else:
            db.execute("UPDATE healing_incidents SET job_state='dispatching',worker_id=?,updated=? WHERE id=?", (token, now, incident_id))
        return token

    def _start(self, scope, incident_id, token, mode, epoch):
        context = contextvars.copy_context()
        with self._lifecycle:
            def activate():
                with self._db() as db:
                    db.execute('BEGIN IMMEDIATE')
                    active = db.execute('UPDATE healing_workers SET pid=? WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=? AND pid=0',
                                        (os.getpid(), *scope, token)).rowcount
                if active == 1: self._run(scope, incident_id, token, mode, epoch)
            def run():
                try:
                    context.run(activate)
                except BaseException:
                    logger.warning('healing_worker_activation_failed')
                finally:
                    with self._lifecycle: self._workers.discard(threading.current_thread())
            thread = threading.Thread(target=run, name='beacon-healing', daemon=True)
            try:
                if not self._enabled or epoch != self._epoch: raise RuntimeError('Healing paused')
                self._workers.add(thread)
                thread.start()
                return True
            except Exception:
                if thread.ident is not None:
                    logger.warning('healing_worker_start_unconfirmed')
                    return True
                self._workers.discard(thread)
                logger.warning('healing_worker_start_deferred')
        try:
            # A claim that never got a worker remains durable for a later tick.
            with self._db() as db:
                db.execute('BEGIN IMMEDIATE')
                self._clear_unstarted(db, scope, token, time.time())
        except Exception:
            logger.warning('healing_claim_cleanup_failed')
        return False

    def recover(self, scope, incident_id, *, _epoch=None):
        scope = _scope(scope)
        epoch = self._admission_epoch()
        if epoch is None or _epoch is not None and epoch != _epoch: return {'status': 'paused'}
        now = time.time()
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._stale(db, scope, now)
            row = self._find(db, scope, incident_id)
            if row['state'] == 'unconfirmed': return {'status': 'unconfirmed'}
            if row['state'] == 'recovering' or row['worker_id'] is not None: return {'status': 'busy'}
            if json.loads(row['metadata'])['error_code'] in UNCERTAIN:
                db.execute("UPDATE healing_incidents SET state='unconfirmed',job_state=CASE WHEN job_state='none' THEN 'deferred' ELSE job_state END,updated=? WHERE id=?", (now, incident_id))
                return {'status': 'unconfirmed'}
            if row['state'] in ('recovered', 'fix_published'): return {'status': row['state']}
            if row['attempts'] >= MAX_ATTEMPTS: return {'status': 'exhausted'}
            if row['next_attempt'] > now: return {'status': 'cooldown'}
            token = self._claim(db, scope, incident_id, 'recover', now)
            if token is None: return {'status': 'busy'}
        return {'status': 'started' if self._start(scope, incident_id, token, 'recover', epoch) else 'unverified'}

    def drain(self, scope):
        scope = _scope(scope)
        epoch = self._admission_epoch()
        if epoch is None: return {'status': 'paused'}
        now = time.time()
        pending_id = None
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            self._stale(db, scope, now)
            if (db.execute('SELECT 1 FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=?', scope).fetchone() or
                    db.execute("SELECT 1 FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND (state='recovering' OR worker_id IS NOT NULL) LIMIT 1", scope).fetchone()):
                return {'status': 'busy'}
            pending = db.execute("SELECT id FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND state='open' AND job_state='none' AND worker_id IS NULL AND attempts<? AND next_attempt<=? ORDER BY created,id LIMIT 1", (*scope, MAX_ATTEMPTS, now)).fetchone()
            if pending is not None:
                pending_id = pending['id']
            else:
                row = db.execute("SELECT * FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND job_state='deferred' AND state!='recovering' AND worker_id IS NULL AND next_submit<=? ORDER BY created,id LIMIT 1", (*scope, now)).fetchone()
                if row is None:
                    pending = db.execute("SELECT 1 FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND job_state='deferred' AND state!='recovering' AND worker_id IS NULL LIMIT 1", scope).fetchone()
                    return {'status': 'cooldown' if pending else 'idle'}
                token = self._claim(db, scope, row['id'], 'drain', now)
                if token is None: return {'status': 'busy'}
                incident_id = row['id']
        if pending_id is not None: return self.recover(scope, pending_id, _epoch=epoch)
        return {'status': 'started' if self._start(scope, incident_id, token, 'drain', epoch) else 'unverified'}

    def _submit(self, scope, incident_id, token, epoch):
        with self._db() as db:
            row = self._find(db, scope, incident_id)
            if row['worker_id'] != token or row['job_state'] not in ('none', 'deferred', 'dispatching'):
                return row['job_state'], row['job_id'], row['generation'], row['next_submit']
            if row['job_attempts'] >= MAX_JOB_ATTEMPTS: return 'exhausted', None, row['generation'], 0
            if row['job_state'] == 'deferred' and row['next_submit'] > time.time():
                return 'deferred', None, row['generation'], row['next_submit']
            generation = row['generation']
        if not self._allowed(epoch): return 'deferred', None, generation, time.time() + JOB_COOLDOWN_SECONDS
        try: ready = self.can_submit(scope) is True
        except BaseException:
            logger.warning('healing_idle_check_failed')
            ready = False
        if not ready or not self._allowed(epoch): return 'deferred', None, generation, time.time() + JOB_COOLDOWN_SECONDS
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._find(db, scope, incident_id)
            if row['worker_id'] != token or row['job_state'] not in ('none', 'deferred', 'dispatching'):
                return row['job_state'], row['job_id'], row['generation'], row['next_submit']
            if row['job_attempts'] >= MAX_JOB_ATTEMPTS: return 'exhausted', None, row['generation'], 0
            generation = row['generation']
            event = self._view(row)
            event['job_input_version'] = _version_pair(event)
            metadata = json.loads(row['metadata'])
            metadata['job_input_version'] = event['job_input_version']
            metadata['_job_version_changed'] = False
            db.execute("UPDATE healing_incidents SET job_state='submitting',job_attempts=job_attempts+1,metadata=?,updated=? WHERE id=? AND worker_id=?",
                       (json.dumps(metadata, separators=(',', ':')), time.time(), incident_id, token))
            key = _request_key(incident_id, generation)
        if not self._allowed(epoch): return 'deferred', None, generation, time.time() + JOB_COOLDOWN_SECONDS
        try:
            receipt = self.submit_fix(scope, key, event)
            if isinstance(receipt, dict) and receipt == {'status': 'deferred'}:
                return 'deferred', None, generation, time.time() + JOB_COOLDOWN_SECONDS
            if isinstance(receipt, dict) and receipt in ({'status': 'not_needed'}, {'status': 'update_required'}):
                return receipt['status'], None, generation, 0
            if (not isinstance(receipt, dict) or receipt.get('status') != 'queued' or
                    not isinstance(receipt.get('job_id'), str) or not _JOB.fullmatch(receipt['job_id'])):
                logger.warning('healing_submission_unconfirmed')
                return 'unconfirmed', None, generation, 0
            return 'queued', receipt['job_id'], generation, 0
        except BaseException:
            logger.warning('healing_submission_unconfirmed')
            return 'unconfirmed', None, generation, 0

    def _run(self, scope, incident_id, token, mode, epoch):
        visited = set()
        try:
            while incident_id is not None:
                visited.add(incident_id)
                with self._db() as db:
                    row = self._find(db, scope, incident_id)
                    if row['worker_id'] != token: return
                    event, occurrences = self._view(row), row['occurrences']
                state = None
                if mode == 'recover' and self._allowed(epoch):
                    try: result = self.runtime_repair(scope, event)
                    except BaseException:
                        logger.warning('healing_recovery_probe_failed')
                        result = None
                    state = 'recovered' if isinstance(result, dict) and result.get('status') == 'recovered' and result.get('verified') is True else 'unverified'
                elif mode == 'recover':
                    state = 'unverified'
                job_state, job_id, generation, next_submit = self._submit(scope, incident_id, token, epoch)
                with self._db() as db:
                    db.execute('BEGIN IMMEDIATE')
                    current = self._find(db, scope, incident_id)
                    if current['worker_id'] == token:
                        if state == 'recovered' and (current['occurrences'] != occurrences or current['probe_dirty']): state = 'open'
                        advance = 0
                        if current['generation'] != generation: job_state, job_id, next_submit = current['job_state'], current['job_id'], current['next_submit']
                        elif job_state in ('not_needed', 'update_required'):
                            metadata = json.loads(current['metadata'])
                            if metadata.get('_job_version_changed') is True or _new_observation(metadata.get('job_input_version'), metadata):
                                job_state, next_submit, advance = 'deferred', 0, 1
                        if job_state == 'deferred' and current['job_attempts'] >= MAX_JOB_ATTEMPTS: job_state, next_submit = 'exhausted', 0
                        db.execute('''UPDATE healing_incidents SET state=COALESCE(?,state),job_state=?,job_id=?,next_submit=?,generation=generation+?,
                                   worker_id=NULL,probe_dirty=0,updated=? WHERE id=? AND worker_id=?''',
                                   (state, job_state, job_id, next_submit, advance, time.time(), incident_id, token))
                    next_id = None
                    if mode == 'drain' and job_state != 'deferred' and self._allowed(epoch):
                        candidates = db.execute("SELECT id FROM healing_incidents WHERE owner=? AND auth_provider=? AND profile=? AND job_state='deferred' AND state!='recovering' AND worker_id IS NULL AND next_submit<=? ORDER BY created,id", (*scope, time.time())).fetchall()
                        next_id = next((candidate['id'] for candidate in candidates if candidate['id'] not in visited), None)
                        if next_id:
                            db.execute("UPDATE healing_incidents SET job_state='dispatching',worker_id=?,updated=? WHERE id=?", (token, time.time(), next_id))
                    if next_id is None:
                        db.execute('DELETE FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?', (*scope, token))
                    incident_id = next_id
        except BaseException:
            logger.warning('healing_worker_failed')
            try:
                with self._db() as db:
                    db.execute('BEGIN IMMEDIATE')
                    db.execute('''UPDATE healing_incidents SET state=CASE WHEN state='recovering' THEN 'unverified' ELSE state END,
                               job_state=CASE WHEN job_state='submitting' THEN 'unconfirmed'
                                              WHEN job_state IN ('none','dispatching') THEN 'deferred' ELSE job_state END,
                               worker_id=NULL,updated=? WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?''',
                               (time.time(), *scope, token))
                    db.execute('DELETE FROM healing_workers WHERE owner=? AND auth_provider=? AND profile=? AND worker_id=?', (*scope, token))
            except BaseException: logger.warning('healing_worker_cleanup_failed')

    def job_current(self, scope, incident_id, request_key, expected_version):
        scope = _scope(scope)
        version = _version_pair(expected_version)
        if version is None or not isinstance(request_key, str): return False
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            try: row = self._find(db, scope, incident_id)
            except LookupError: return False
            metadata = json.loads(row['metadata'])
            return (row['job_state'] == 'queued' and row['worker_id'] is None and row['state'] != 'fix_published' and
                    request_key == _request_key(incident_id, row['generation']) and
                    metadata.get('_job_version_changed') is not True and not _new_observation(metadata.get('job_input_version'), metadata) and
                    _version_pair(metadata.get('job_input_version')) == version)

    def confirm_fix(self, scope, incident_id, release, commit, checks, *, expected_request_key):
        scope = _scope(scope)
        if (not _valid_version(release) or not isinstance(commit, str) or not _COMMIT.fullmatch(commit) or
                not isinstance(checks, dict) or set(checks) != REQUIRED_CHECKS or any(value is not True for value in checks.values()) or
                not isinstance(expected_request_key, str) or not re.fullmatch(r'[0-9a-f]{64}', expected_request_key)):
            raise ValueError('Invalid fix evidence')
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._find(db, scope, incident_id)
            if expected_request_key != _request_key(incident_id, row['generation']): raise ValueError('Fix is not confirmed')
            if row['state'] == 'fix_published' and row['release'] == release and row['commit_sha'] == commit:
                return self._view(row)
            if row['release'] == release or row['commit_sha'] == commit: raise ValueError('Fix is not confirmed')
            if row['job_state'] != 'queued' or row['worker_id'] is not None or row['state'] == 'fix_published':
                raise ValueError('Fix is not confirmed')
            metadata = json.loads(row['metadata'])
            if metadata.get('_job_version_changed') is True or _new_observation(metadata.get('job_input_version'), metadata):
                raise ValueError('Fix is not confirmed')
            metadata['published_repair_input_version'] = _version_pair(metadata.get('job_input_version'))
            db.execute("UPDATE healing_incidents SET state='fix_published',release=?,commit_sha=?,metadata=?,updated=? WHERE id=?",
                       (release, commit, json.dumps(metadata, separators=(',', ':')), time.time(), incident_id))
            return self._view(self._find(db, scope, incident_id))

    def fail_fix(self, scope, incident_id, *, expected_request_key):
        scope = _scope(scope)
        if not isinstance(expected_request_key, str) or not re.fullmatch(r'[0-9a-f]{64}', expected_request_key):
            raise ValueError('Invalid fix evidence')
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            row = self._find(db, scope, incident_id)
            if (row['state'] == 'fix_published' or row['job_state'] != 'queued' or row['worker_id'] is not None or
                    expected_request_key != _request_key(incident_id, row['generation'])):
                raise ValueError('Fix is not confirmed')
            state = 'exhausted' if row['job_attempts'] >= MAX_JOB_ATTEMPTS else 'deferred'
            now = time.time()
            db.execute('UPDATE healing_incidents SET job_state=?,job_id=NULL,generation=generation+1,next_submit=?,updated=? WHERE id=?',
                       (state, now + JOB_COOLDOWN_SECONDS if state == 'deferred' else 0, now, incident_id))
            return self._view(self._find(db, scope, incident_id))
