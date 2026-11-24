"""Authenticated mobile task receipts and worker admission. Goals and frames never enter the receipt store."""
import hashlib
import hmac
import json
import re
import sqlite3
import threading
import time
from contextlib import closing, contextmanager
from pathlib import Path

from fastapi import HTTPException


REQUEST = re.compile(r'([0-9]{13})-[A-Za-z0-9_-]{1,64}')
DAY_MS = 86_400_000
MAX_RECEIPTS = 512
FIELDS = ('id', 'state', 'steps', 'message')


class Tasks:
    def __init__(self, folder, owner, provider, profile, approvals):
        self.path = Path(folder) / 'run' / 'phonedriver-mobile.sqlite'
        self.scope = hashlib.sha256(json.dumps([owner, provider, profile]).encode()).digest()
        self.approvals = approvals

    @contextmanager
    def connect(self):
        try:
            with closing(sqlite3.connect(self.path, timeout=2)) as db, db:
                db.row_factory = sqlite3.Row
                yield db
        except sqlite3.Error:
            raise HTTPException(503, 'Phone task receipts are unavailable') from None

    def _get(self, db, task_id=None):
        sql = 'SELECT * FROM tasks WHERE scope=?'
        row = (db.execute(sql + ' AND id=?', (self.scope, task_id)) if task_id else
               db.execute(sql + ' ORDER BY rowid DESC LIMIT 1', (self.scope,))).fetchone()
        if row and row['state'] == 'running' and (row['deadline'] < time.time() or not self.approvals._holder_alive(row['holder'])):
            db.execute("UPDATE tasks SET state='failed',message='The phone task was interrupted.' "
                       "WHERE scope=? AND id=? AND state='running'", (self.scope, row['id']))
            return db.execute(sql + ' AND id=?', (self.scope, row['id'])).fetchone()
        return row

    @staticmethod
    def summary(row):
        return {key: row[key] for key in FIELDS if row[key] is not None} if row else None

    def latest(self):
        if not self.path.is_file():
            return None
        with self.connect() as db:
            if not db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='tasks'").fetchone():
                return None
            return self.summary(self._get(db))

    def update(self, task_id, state, steps=None, message=None):
        with self.connect() as db:
            db.execute("UPDATE tasks SET state=?,steps=coalesce(?,steps),message=? WHERE scope=? AND id=? AND state='running'",
                       (state, steps, message, self.scope, task_id))

    def cancelled(self, task_id):
        with self.connect() as db:
            row = db.execute('SELECT state FROM tasks WHERE scope=? AND id=?', (self.scope, task_id)).fetchone()
            return not row or row['state'] != 'running'

    def cancel(self, task_id):
        if not self.path.is_file():
            raise HTTPException(404, 'Unknown phone task')
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            if not self._get(db, task_id):
                raise HTTPException(404, 'Unknown phone task')
            db.execute("UPDATE tasks SET state='cancelled',message='The phone task was stopped.' "
                       "WHERE scope=? AND id=? AND state='running'", (self.scope, task_id))
            return self.summary(self._get(db, task_id))

    def submit(self, task_id, goal, admit, execute, seconds):
        now = int(time.time() * 1000)
        match = REQUEST.fullmatch(task_id) if isinstance(task_id, str) else None
        if not match or not now - DAY_MS < int(match[1]) <= now + 300_000:
            raise HTTPException(400, 'Invalid or expired phone task request ID')
        self.path.parent.mkdir(exist_ok=True, parents=True)
        with self.connect() as db:
            db.execute('BEGIN IMMEDIATE')
            db.execute('CREATE TABLE IF NOT EXISTS secret (salt BLOB NOT NULL)')
            db.execute('INSERT INTO secret SELECT randomblob(32) WHERE NOT EXISTS (SELECT 1 FROM secret)')
            db.execute('CREATE TABLE IF NOT EXISTS tasks (scope BLOB, id TEXT, digest BLOB, created INTEGER, '
                       'holder TEXT, deadline REAL, state TEXT, steps INTEGER, message TEXT, PRIMARY KEY(scope,id))')
            # A request admitted near its ID's expiry can still own a live worker.
            db.execute("DELETE FROM tasks WHERE created<=? AND (state!='running' OR deadline<?)",
                       (now - DAY_MS, time.time()))
            digest = hmac.digest(db.execute('SELECT salt FROM secret').fetchone()[0], goal.encode(), 'sha256')
            old = self._get(db, task_id)
            if old:
                if not hmac.compare_digest(old['digest'], digest):
                    raise HTTPException(409, 'This request ID was used for another phone task')
                return self.summary(old)
            # Enforce one admitted worker across processes and profiles sharing this phone.
            for row in db.execute("SELECT holder,deadline FROM tasks WHERE state='running'"):
                if row['deadline'] >= time.time() and self.approvals._holder_alive(row['holder']):
                    raise HTTPException(409, 'A phone task is already running')
            if db.execute('SELECT count(*) FROM tasks').fetchone()[0] >= MAX_RECEIPTS:
                raise HTTPException(429, 'Too many phone tasks in the last day')
            epoch = admit()
            db.execute('INSERT INTO tasks VALUES (?,?,?,?,?,?,?,0,NULL)', (self.scope, task_id, digest, int(match[1]),
                       self.approvals.holder_tag(), time.time() + seconds, 'running'))
            initial = self.summary(self._get(db, task_id))
        def work():
            try:
                result = execute(epoch, lambda: self.cancelled(task_id),
                                 lambda count: self.update(task_id, 'running', count))
                state = 'completed' if result.get('verified') else ('cancelled' if result.get('error') == 'cancelled' else 'failed')
                # Only fixed public messages here. A primitive's refusal may describe private screen text.
                message = None if state == 'completed' else {
                    'vision_unavailable': 'Configure a working vision model for this Hermes profile.',
                    'denied': 'The owner declined the action.', 'expired': 'Approval expired.',
                    'changed': 'The phone screen changed.',
                    'busy': "You're using the phone" if result.get('not_started') else 'Control of the phone changed.',
                    'masked': 'The phone screen is protected.', 'timeout': 'The phone task timed out.',
                    'cancelled': 'The phone task was stopped.',
                }.get(result.get('error'), 'The phone task could not be verified.')
                self.update(task_id, state, result.get('steps', 0), message)
            except Exception:
                self.update(task_id, 'failed', message='The phone task was interrupted.')
        try:
            threading.Thread(target=work, name='beacon-mobile-phone-task', daemon=True).start()
        except RuntimeError:
            self.update(task_id, 'failed', message='The phone task could not start.')
            return self.latest()
        return initial
