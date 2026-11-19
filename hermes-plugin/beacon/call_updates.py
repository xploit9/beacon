"""Call contract v3 updates channel (CALLS-CONTRACT.md section 3): one call's receipt, approval, job and close events in order.

Receipt and approval events are written by the tool gateway in the same transaction as the receipt change itself. Job events
are taken when the channel is read: a job's state is compared with the last event of that job, so a change made anywhere
(the native watch, a callback choice, a hand-over) reaches the call. Events carry no transcript text beyond what the receipt
or job already holds. Retention lives with the call row (mobile.py): a trigger deletes them with it, and every watch pass
removes those of calls closed 15 minutes ago."""
import hashlib
import json
import time

DDL = '''CREATE TABLE IF NOT EXISTS call_updates(call_id TEXT NOT NULL,seq INTEGER NOT NULL,type TEXT NOT NULL,key TEXT NOT NULL,
    payload TEXT NOT NULL,at REAL NOT NULL,state TEXT NOT NULL DEFAULT '',PRIMARY KEY(call_id,seq))'''
PAGE = 64
RETAIN = 512
_GROUP = "CASE type WHEN 'approval' THEN 'receipt' ELSE type END"


def _encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def append(conn, call_id, kind, key, payload, state):
    """Adds one event unless the key's last event already holds this state; the caller's transaction makes it atomic."""
    group = 'receipt' if kind == 'approval' else kind
    last = conn.execute('SELECT state FROM call_updates WHERE call_id=? AND key=? AND ' + _GROUP + '=? ORDER BY seq DESC LIMIT 1',
                        (call_id, key, group)).fetchone()
    if last is not None and last[0] == state: return None
    seq = conn.execute('SELECT COALESCE(MAX(seq),0)+1 FROM call_updates WHERE call_id=?', (call_id,)).fetchone()[0]
    conn.execute('INSERT INTO call_updates(call_id,seq,type,key,payload,at,state) VALUES(?,?,?,?,?,?,?)',
                 (call_id, seq, kind, key, _encoded(payload), time.time(), state))
    if seq > RETAIN:
        # The newest RETAIN events stay, and the last event of every key, so a reset snapshot is always whole.
        conn.execute('DELETE FROM call_updates WHERE call_id=? AND seq<=? AND seq NOT IN '
                     '(SELECT MAX(seq) FROM call_updates WHERE call_id=? GROUP BY ' + _GROUP + ',key)',
                     (call_id, seq - RETAIN, call_id))
    return seq


def receipt_event(receipt):
    """(type, payload, state) for a v3 receipt: an approval card for needs_approval, the receipt itself otherwise."""
    if receipt['status'] == 'needs_approval':
        return 'approval', {key: receipt.get(key) for key in ('request_id', 'tool', 'tool_name', 'proposal', 'expires_at')}, 'needs_approval'
    return 'receipt', receipt, receipt['status']


def record_jobs(conn, call_id, jobs):
    """Job events for every job of the call whose state changed since its last event; jobs is [job_status(), ...]."""
    for job in jobs:
        append(conn, call_id, 'job', job['job_id'], job, hashlib.sha256(_encoded(job).encode('utf-8')).hexdigest()[:32])


def record_closed(conn, call_id):
    append(conn, call_id, 'call', 'call', {'status': 'closed'}, 'closed')


def _event(row):
    return {'seq': row['seq'], 'type': row['type'], 'at': row['at'], **json.loads(row['payload'])}


def page(conn, call_id, after, closed):
    """Events after `after`, contiguous, at most PAGE; a cursor outside the retained window gets a reset snapshot instead."""
    newest = conn.execute('SELECT MAX(seq) FROM call_updates WHERE call_id=?', (call_id,)).fetchone()[0] or 0
    floor = max(1, newest - RETAIN + 1)
    result = {'call_id': call_id, 'events': [], 'next_after': after, 'reset': False, 'closed': bool(closed)}
    if after > newest or (newest and after < floor - 1):
        rows = conn.execute('SELECT * FROM call_updates WHERE call_id=? AND seq IN (SELECT MAX(seq) FROM call_updates '
                            'WHERE call_id=? GROUP BY ' + _GROUP + ',key) ORDER BY seq', (call_id, call_id)).fetchall()
        result.update(events=[_event(row) for row in rows], next_after=newest, reset=True)
        return result
    rows = conn.execute('SELECT * FROM call_updates WHERE call_id=? AND seq>? ORDER BY seq LIMIT ?', (call_id, after, PAGE)).fetchall()
    if rows: result.update(events=[_event(row) for row in rows], next_after=rows[-1]['seq'])
    # A closed call says so only on the page that reaches its last event, so the app never stops before reading it.
    result['closed'] = bool(closed) and result['next_after'] >= newest
    return result
