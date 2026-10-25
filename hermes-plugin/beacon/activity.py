"""Owned native work/request notifications. Never a second agent or prompt loop."""
import hashlib
import time


def request_actions(frame):
    params = frame.get('params', {})
    choices = params.get('choices')
    if frame.get('method') == 'approval':
        return [{'value': c, 'label': {'once': 'Allow once', 'session': 'Allow session', 'always': 'Always allow', 'deny': 'Deny'}[c]}
                for c in choices or [] if c in ('once', 'session', 'always', 'deny')]
    if frame.get('method') == 'clarify' and not params.get('questions') and not params.get('multi_select'):
        return [{'value': c, 'label': c[:80]} for c in choices or [] if isinstance(c, str) and 0 < len(c) <= 1024]
    return []


def request_preview(frame):
    from agent.redact import redact_sensitive_text
    params = frame.get('params', {})
    if frame.get('method') == 'approval':
        title = 'Beacon needs approval'
        parts = [params.get('description'), params.get('command')]
    else:
        title = 'Beacon asks'
        parts = [params.get('question')]
    text = '\n'.join(part.strip() for part in parts if isinstance(part, str) and part.strip())
    redacted=redact_sensitive_text(text[:4000],force=True,redact_url_credentials=True)
    def units(value): return len(value.encode('utf-16-le','surrogatepass'))//2
    complete=bool(text) and units(text)<=640 and redacted==text
    if frame.get('method')=='approval': complete=complete and isinstance(params.get('command'),str) and bool(params['command'].strip())
    else:
        choices=params.get('choices') or []
        complete=complete and not params.get('questions') and not params.get('multi_select') and isinstance(choices,list) and len(choices)<=3 and all(isinstance(c,str) and units(c)<=80 for c in choices)
    body=redacted if complete else (redacted[:560]+'\nOpen Beacon to review the full request.').strip()
    return {'title': title, 'body': body, 'complete':bool(complete)}


def snapshot(native, owner, provider, profile):
    from tui_gateway import server, server_requests
    with server._sessions_lock:
        records = [(sid, dict(r)) for sid, r in server._sessions.items()
                   if server._session_auth_user_id(r) == provider + ':' + owner]
    result = []
    for sid, record in records[:512]:
        stored = record.get('session_key')
        if not stored: continue
        try: native.verify(owner, provider, profile, stored, sid, False)
        except LookupError: continue
        requests = [r for r in server_requests.open_requests(sid) if r.get('method') in ('approval', 'clarify')]
        turn = (record.get('_submit_user_row') or {}).get('_row_id')
        if not record.get('running') and not requests: continue
        key = hashlib.sha256((owner + '\0' + profile + '\0' + sid + '\0' + str(turn)).encode()).hexdigest()
        result.append({'key': key, 'stored': stored, 'runtime': sid, 'requests': requests})
    return result


def sync(mobile, native, owner, provider, profile):
    live = snapshot(native, owner, provider, profile)
    now = time.time()
    with mobile.LOCK, mobile.connect() as conn:
        mobile.prune_notices(conn)
        active = set()
        remaining = max(0, 10000 - conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0])
        for work in live:
            frames = [None] + work['requests']
            for frame in frames:
                event = 'native-' + (frame['id'] if frame else work['key'])
                active.add(event)
                if conn.execute('SELECT 1 FROM notices WHERE id=?', (event,)).fetchone(): continue
                if remaining == 0: continue
                remaining -= 1
                kind = frame['method'] if frame else 'working'
                conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,activity_key,runtime,request_id) VALUES(?,?,?,?,?,?,?,?,?,?)',
                             (event, owner, profile, kind, work['stored'], now, now + (300 if frame else 14400), (work['key'] + '-' + frame['id']) if frame else work['key'], work['runtime'], frame['id'] if frame else ''))
        previous = conn.execute("SELECT * FROM notices WHERE owner=? AND profile=? AND kind IN ('working','approval','clarify') AND activity_key!='' AND state='pending'", (owner, profile)).fetchall()
        for row in previous:
            if row['id'] not in active:
                conn.execute("UPDATE notices SET state='settled' WHERE id=?", (row['id'],))
                # A new outbox row makes cancellation observable to cursor polling as well as FCM.
                conn.execute('INSERT OR IGNORE INTO notices(id,owner,profile,kind,conversation,created,expires,activity_key) VALUES(?,?,?,?,?,?,?,?)',
                             ('settled-' + row['id'], owner, profile, 'activity_end', row['conversation'], now, now + 300, row['activity_key']))


def pending(native, owner, provider, profile, row):
    if row['state'] != 'pending' or row['expires'] <= time.time() or not row.get('request_id'):
        raise ValueError('Native request expired or settled')
    native.verify(owner, provider, profile, row['conversation'], row['runtime'], False)
    from tui_gateway import server_requests
    frame = next((r for r in server_requests.open_requests(row['runtime']) if r['id'] == row['request_id']), None)
    if not frame or frame.get('method') != row['kind']: raise ValueError('Native request no longer open')
    return frame


def answer(mobile, native, owner, provider, profile, event, value):
    if not isinstance(value, str) or not 0 < len(value) <= 1024: raise ValueError('Bounded answer required')
    with mobile.call_lock('notice:'+event):
        with mobile.LOCK, mobile.connect() as conn:
            found = conn.execute('SELECT * FROM notices WHERE id=? AND owner=? AND profile=?', (event, owner, profile)).fetchone()
            if not found: raise LookupError('Owned notification unavailable')
            row = dict(found)
        frame = pending(native, owner, provider, profile, row)
        if not request_preview(frame)['complete'] and not(frame['method']=='approval' and value=='deny'):
            raise ValueError('Open Beacon to review the full request')
        params = frame.get('params', {})
        choices = request_actions(frame)
        if params.get('choices') and value not in [c['value'] for c in choices]: raise ValueError('Answer was not offered')
        if frame['method'] == 'approval' and not choices: raise ValueError('No approval choice offered')
        if frame['method'] == 'clarify' and (params.get('questions') or params.get('multi_select')):
            raise ValueError('Review this question in the app')
        if frame['method'] == 'approval':
            method = 'approval.respond'
            arguments = {'session_id': row['runtime'], 'request_id': mobile.ident(params.get('request_id')), 'choice': value}
        else:
            method = 'request.answer'
            arguments = {'id': frame['id'], 'result': {'answer': value}}
        # A lost or unrecognized receipt must not send the same mutation again.
        with mobile.LOCK, mobile.connect() as conn:
            conn.execute("UPDATE notices SET state='settled' WHERE id=? AND owner=? AND profile=?", (event,owner,profile))
        receipt = native.rpc(owner, provider, method, arguments)
        if frame['method'] == 'approval':
            accepted = isinstance(receipt,dict) and type(receipt.get('resolved')) is int and receipt['resolved'] > 0
        else:
            accepted = isinstance(receipt,dict) and receipt.get('status') == 'ok'
        if not accepted: raise ValueError('Native request already settled')
        with mobile.LOCK, mobile.connect() as conn:
            conn.execute("UPDATE notices SET state='answered' WHERE id=? AND owner=? AND profile=?", (event,owner,profile))
        return {'status': 'answered', 'event_id': event}
