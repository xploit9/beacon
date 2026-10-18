"""Private durable state shared by dashboard and native callback tool."""
import hashlib
import hmac
import json
import math
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import threading
import time
import uuid
import weakref

LOCK = threading.RLock()
PUSH_LOCK = threading.Lock()
_PROTECTED={}
_OWNER_SID=None
_CALL_LOCKS=weakref.WeakValueDictionary()
ID = re.compile(r"[A-Za-z0-9_-]{1,160}\Z")
MAX_MS = 4 * 60 * 60 * 1000
MAX_ACTIVE_CALLS = 8

def settings():
    from copy import deepcopy
    path=home()/'config.yaml'
    try:
        from utils import load_yaml_file_readonly
        if not path.is_file() or path.stat().st_size>1024*1024: return {}
        cfg=load_yaml_file_readonly(path)
    except Exception: return {}  # Native YAML engines differ; unreadable authority always fails closed.
    if not isinstance(cfg,dict): return {}
    plugins=cfg.get('plugins')
    if not isinstance(plugins,dict): return {}
    enabled,disabled=plugins.get('enabled',[]),plugins.get('disabled',[])
    if not isinstance(enabled,list) or not isinstance(disabled,list) or 'cypher' not in enabled or 'cypher' in disabled: return {}
    entries=plugins.get('entries');entry=entries.get('cypher') if isinstance(entries,dict) else None
    value=entry.get('settings') if isinstance(entry,dict) else None
    return deepcopy(value) if isinstance(value,dict) else {}

def home():
    from hermes_constants import get_process_hermes_home
    return Path(get_process_hermes_home())

def protect(path):
    global _OWNER_SID
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    for parent in (path, *path.parents):
        if parent.is_symlink() or getattr(parent, 'is_junction', lambda: False)():
            raise RuntimeError('Private path cannot contain links')
    identity=(path.stat().st_dev,path.stat().st_ino)
    if _PROTECTED.get(str(path.resolve()))==identity: return
    if os.name != 'nt':
        path.chmod(0o700)
        _PROTECTED[str(path.resolve())]=identity
        return
    # Preserve the established owner/SYSTEM-only archive DACL; never use icacls /reset.
    import ctypes
    from ctypes import wintypes
    if _OWNER_SID is None:
        _OWNER_SID = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'], text=True,
                                  creationflags=subprocess.CREATE_NO_WINDOW).strip().split(',')[-1].strip('"')
    sid=_OWNER_SID
    if not re.fullmatch(r'S-1-[0-9-]+', sid):
        raise RuntimeError('Private owner unavailable')
    security = ctypes.WinDLL('advapi32', use_last_error=True)
    descriptor = ctypes.c_void_p()
    security.ConvertStringSecurityDescriptorToSecurityDescriptorW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
    security.SetFileSecurityW.argtypes = [wintypes.LPCWSTR, wintypes.DWORD, ctypes.c_void_p]
    if not security.ConvertStringSecurityDescriptorToSecurityDescriptorW('D:P(A;OICI;FA;;;'+sid+')(A;OICI;FA;;;SY)', 1, ctypes.byref(descriptor), None):
        raise ctypes.WinError(ctypes.get_last_error())
    try:
        if not security.SetFileSecurityW(str(path), 0x80000004, descriptor):
            raise ctypes.WinError(ctypes.get_last_error())
    finally:
        kernel = ctypes.WinDLL('kernel32')
        kernel.LocalFree.argtypes = [ctypes.c_void_p]
        kernel.LocalFree(descriptor)
    _PROTECTED[str(path.resolve())]=identity

def _connect():
    root = home() / 'plugin-data' / 'cypher'
    protect(root)
    conn = sqlite3.connect(root / 'mobile.sqlite', timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    conn.execute('PRAGMA journal_mode=WAL')
    conn.executescript('''
    CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value BLOB);
    CREATE TABLE IF NOT EXISTS calls(id TEXT PRIMARY KEY, owner TEXT, profile TEXT, conversation TEXT, created REAL, closed INTEGER DEFAULT 0, status TEXT DEFAULT 'pending', receipt TEXT DEFAULT '{}');
    CREATE TABLE IF NOT EXISTS timeline(call_id TEXT,event_id TEXT,digest TEXT,payload TEXT,PRIMARY KEY(call_id,event_id));
    CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,call_id TEXT,owner TEXT,profile TEXT,stored TEXT,runtime TEXT,request TEXT,title TEXT,callback INTEGER,epoch TEXT,seq INTEGER,status TEXT DEFAULT 'watching',UNIQUE(owner,profile,request));
    CREATE TABLE IF NOT EXISTS targets(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,token TEXT,expires REAL,payload TEXT);
    CREATE TABLE IF NOT EXISTS deferred(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL);
    CREATE TABLE IF NOT EXISTS notices(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,owner TEXT,profile TEXT,kind TEXT,conversation TEXT,created REAL,expires REAL,state TEXT DEFAULT 'pending',call_id TEXT,push TEXT DEFAULT 'pending');
    CREATE TABLE IF NOT EXISTS notice_deliveries(event TEXT REFERENCES notices(id) ON DELETE CASCADE,target TEXT REFERENCES targets(id) ON DELETE CASCADE,accepted REAL DEFAULT 0,attempts INTEGER DEFAULT 0,next_attempt REAL DEFAULT 0,last_error TEXT DEFAULT '',PRIMARY KEY(event,target));
    ''')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_voice_calls ON calls(owner,profile,conversation,created DESC)')
    conn.execute("CREATE INDEX IF NOT EXISTS transcript_versions ON timeline(call_id,json_extract(payload,'$.type'),json_extract(payload,'$.role'),json_extract(payload,'$.turn_id'),json_extract(payload,'$.final'),json_extract(payload,'$.at_ms'))")
    call_columns={r[1] for r in conn.execute('PRAGMA table_info(calls)')}
    if 'voice_config' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN voice_config TEXT NOT NULL DEFAULT '{}'")
    if 'callback_context' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN callback_context TEXT NOT NULL DEFAULT '{}'")
    columns={r[1] for r in conn.execute('PRAGMA table_info(jobs)')}
    for name in ('result','error'):
        if name not in columns: conn.execute('ALTER TABLE jobs ADD COLUMN '+name+" TEXT DEFAULT ''")
    notice_columns={r[1] for r in conn.execute('PRAGMA table_info(notices)')}
    if 'release_version' not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN release_version INTEGER NOT NULL DEFAULT 0')
    if 'reason' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN reason TEXT DEFAULT ''")
    if 'job_id' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN job_id TEXT DEFAULT ''")
    for name in ('activity_key','runtime','request_id'):
        if name not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN '+name+" TEXT DEFAULT ''")
    deferred_columns={r[1] for r in conn.execute('PRAGMA table_info(deferred)')}
    if 'reason' not in deferred_columns: conn.execute("ALTER TABLE deferred ADD COLUMN reason TEXT DEFAULT ''")
    return conn

def connect():
    with LOCK: return _connect()

def call_lock(call_id):
    with LOCK:
        lock=_CALL_LOCKS.get(call_id)
        if lock is None:
            lock=threading.RLock();_CALL_LOCKS[call_id]=lock
        return lock

def prune_notices(conn):
    # Move legacy answered invitation metadata into its owned call before pruning
    # the delivery outbox. Text transcripts and call/job history are preserved.
    rows=conn.execute("SELECT n.* FROM notices n JOIN calls c ON c.id=n.call_id AND c.owner=n.owner AND c.profile=n.profile WHERE n.state='answered' AND n.expires<? AND c.callback_context='{}'",(time.time(),)).fetchall()
    for row in rows:
        context={key:row[key] for key in ('id','reason','job_id','conversation')}
        conn.execute('UPDATE calls SET callback_context=? WHERE id=?',(json.dumps(context),row['call_id']))
    # Keep expired native-request admission until it settles; polling must not renew an old action.
    conn.execute("DELETE FROM notices WHERE expires<? AND NOT(activity_key!='' AND state='pending' AND kind IN ('working','approval','clarify'))",(time.time(),))


def ident(value):
    if not isinstance(value, str) or not ID.fullmatch(value):
        raise ValueError('Invalid identity')
    return value

def finite(value):
    if type(value) not in (int,float) or not math.isfinite(value) or not 0 <= value <= MAX_MS:
        raise ValueError('Invalid relative call time')
    return value

def owned(conn, call_id, owner, profile=None, writable=False):
    row = conn.execute('SELECT * FROM calls WHERE id=? AND owner=?', (ident(call_id),owner)).fetchone()
    if not row or (profile and row['profile'] != profile):
        raise LookupError('Call not found')
    if writable and time.time()-row['created']>4*3600: raise ValueError('Call admission expired')
    if writable and (row['closed'] or row['status'] in ('saved','partial','failed')):
        raise ValueError('Call is closed')
    return dict(row)

def reserve(owner, profile, conversation, voice=None, client_key=False):
    call_id = uuid.uuid4().hex
    with LOCK, connect() as conn:
        active=conn.execute('SELECT COUNT(*) FROM calls WHERE owner=? AND closed=0 AND created>?',(owner,time.time()-4*3600)).fetchone()[0]
        if active>=MAX_ACTIVE_CALLS: raise ValueError('Active call limit reached')
        config={**(voice or {}),**({'client_key':True} if client_key else {})}
        conn.execute('INSERT INTO calls(id,owner,profile,conversation,created,voice_config) VALUES(?,?,?,?,?,?)', (call_id,owner,profile,ident(conversation),time.time(),json.dumps(config)))
    return {'call_id':call_id,'conversation_id':conversation,'profile':profile,'transport':'gemini_live_direct','recording_status':'disabled','transcript_status':'pending',**(voice or {})}

def status(row):
    return {'call_id':row['id'],'conversation_id':row['conversation'],'profile':row['profile'],'closed':bool(row['closed']),'recording_status':'disabled','transcript_status':row['status'],**json.loads(row['receipt']),'audio_available':False}

def chunk(call_id, owner, body):
    # Reject old installed clients as well as new callers; PCM never enters storage.
    with connect() as conn: owned(conn,call_id,owner,writable=True)
    raise ValueError('Call audio storage has been removed')

EVENT_TYPES = {'transcript','interruption','capture_started','capture_stopped','playout_started','playout_stopped','upload_gap','state','media_diagnostic','job_link','visual_state','tool_receipt'}
def events(call_id,owner,body):
    rows=body.get('events')
    if not isinstance(rows,list) or not 1<=len(rows)<=64: raise ValueError('Invalid event batch')
    clean=[]
    for item in rows:
        if not isinstance(item,dict) or item.get('type') not in EVENT_TYPES: raise ValueError('Unsupported event')
        row={k:item[k] for k in ('event_id','turn_id','type','role','text','final','interrupted','at_ms','stream','sequence','state','stage','elapsed_ms','count','total_corrected_ms','source','frames_sent','request_id','tool_name','status','error_code','provider','attempt','close_code','cause_class') if k in item}
        ident(row.get('event_id')); ident(row.get('turn_id','none')); finite(row.get('at_ms'))
        if any(k in row for k in ('provider','attempt','close_code','cause_class')):
            if row['type']!='media_diagnostic': raise ValueError('Unexpected transport diagnostic')
            if 'provider' in row and row['provider'] not in ('gemini','grok','openai'): raise ValueError('Invalid diagnostic provider')
            if 'attempt' in row and (type(row['attempt']) is not int or not 0<=row['attempt']<=10): raise ValueError('Invalid diagnostic attempt')
            if 'close_code' in row and (type(row['close_code']) is not int or not 0<=row['close_code']<=4999): raise ValueError('Invalid diagnostic close code')
            if 'cause_class' in row and row['cause_class'] not in ('ssl_handshake','ssl_peer','ssl_protocol','ssl_transport','socket_timeout','dns','connect','eof','io','other'): raise ValueError('Invalid diagnostic cause')
        if 'role' in row and row['role'] not in ('user','assistant'): raise ValueError('Invalid role')
        if 'text' in row and (row['type']!='transcript' or not isinstance(row['text'],str) or len(row['text'])>8192): raise ValueError('Invalid transcript')
        for k in ('final','interrupted'):
            if k in row and type(row[k]) is not bool: raise ValueError('Invalid event flag')
        if row['type']=='transcript' and ('role' not in row or 'text' not in row): raise ValueError('Transcript needs role and text')
        if 'stream' in row and row['stream'] not in ('user','assistant'): raise ValueError('Invalid stream')
        if 'sequence' in row and (type(row['sequence']) is not int or not 0<=row['sequence']<72000): raise ValueError('Invalid sequence')
        if row['type']=='visual_state':
            if row.get('source') not in ('camera','screen') or row.get('state') not in ('off','starting','sharing','failed'): raise ValueError('Invalid visual state')
            if type(row.get('frames_sent')) is not int or not 0<=row['frames_sent']<=72000: raise ValueError('Invalid visual frame count')
        elif any(k in row for k in ('source','frames_sent')): raise ValueError('Unexpected visual evidence')
        elif 'state' in row and row['state'] not in ('speaking','listening','thinking','away','muted','held'): raise ValueError('Invalid state')
        if row['type']=='tool_receipt':
            ident(row.get('request_id'))
            if not isinstance(row.get('tool_name'),str) or not 1<=len(row['tool_name'])<=200: raise ValueError('Invalid tool name')
            if row.get('status') not in ('ready','running','needs_approval','completed','failed','denied','expired','uncertain','cancelled','unavailable','not_sent','submission_unconfirmed'): raise ValueError('Invalid tool receipt status')
        elif any(k in row for k in ('request_id','tool_name','status')): raise ValueError('Unexpected tool evidence')
        if 'stage' in row and row['stage'] not in ('capture_started','capture_stopped','playout_started','playout_stopped','capture_error','playout_error','media_timeout','timestamp_overlap_corrected','voice_connecting','context_ready','token_ready','token_error','provider_key_ready','provider_ready','provider_error','voice_error'): raise ValueError('Invalid stage')
        if 'error_code' in row:
            codes=r'http_[45][0-9]{2}|timeout|dns|tls|network|invalid_response'
            if row['type']=='tool_receipt': codes+='|tool_http_failure|admission_rejected'
            if row['type'] not in ('tool_receipt','media_diagnostic') or not isinstance(row['error_code'],str) or not re.fullmatch(codes,row['error_code']): raise ValueError('Invalid diagnostic code')
        if 'elapsed_ms' in row: finite(row['elapsed_ms'])
        for field,limit in (('count',1000000),('total_corrected_ms',9007199254740991)):
            if field in row and (row['type']!='media_diagnostic' or row.get('stage')!='timestamp_overlap_corrected' or type(row[field]) is not int or not 0<=row[field]<=limit): raise ValueError('Invalid clock diagnostic aggregate')
        raw=json.dumps(row,ensure_ascii=False,sort_keys=True,separators=(',',':'))
        clean.append((row['event_id'],hashlib.sha256(raw.encode()).hexdigest(),raw))
    with LOCK,connect() as conn:
        owned(conn,call_id,owner,writable=True)
        existing_ids={r[0] for r in conn.execute('SELECT event_id FROM timeline WHERE call_id=?',(call_id,))}
        if len(existing_ids|{r[0] for r in clean})>20000: raise ValueError('Timeline limit exceeded')
        for eid,digest,raw in clean:
            old=conn.execute('SELECT digest FROM timeline WHERE call_id=? AND event_id=?',(call_id,eid)).fetchone()
            if old and old[0]!=digest: raise ValueError('Conflicting duplicate event')
            conn.execute('INSERT OR IGNORE INTO timeline VALUES(?,?,?,?)',(call_id,eid,digest,raw))
    return {'stored':True,'count':len(clean),'recording_status':'disabled','transcript_status':'syncing'}

def finalize(call_id,owner,body):
    incomplete=body.get('incomplete',False)
    if type(incomplete) is not bool: raise ValueError('Invalid transcript completeness flag')
    with call_lock(call_id), LOCK, connect() as conn:
        call=owned(conn,call_id,owner)
        prior=json.loads(call['receipt'])
        if call['closed'] and prior.get('transcript_available') and (not incomplete or prior.get('incomplete')):
            return status(call)
        rows=[json.loads(r[0]) for r in conn.execute('SELECT payload FROM timeline WHERE call_id=?',(call_id,))]
        groups={}
        for row in rows:
            if row['type']=='transcript': groups.setdefault((row.get('turn_id','none'),row['role']),[]).append(row)
        receipt={'audio_available':False,'transcript_available':True,
                 'transcript_events':sum(r['type']=='transcript' and bool(r.get('final')) for r in rows),
                 'unfinalized_transcripts':sum(not any(r.get('final') for r in group) for group in groups.values())}
        receipt['incomplete']=incomplete or any(r['type']=='upload_gap' for r in rows)
        receipt['complete']=not receipt['unfinalized_transcripts'] and not receipt['incomplete']
        state='saved' if receipt['complete'] else 'partial'
        conn.execute('UPDATE calls SET status=?,closed=1,receipt=? WHERE id=?',(state,json.dumps(receipt),call_id))
        call.update(status=state,closed=1,receipt=json.dumps(receipt))
        return status(call)

def invitation(owner,profile,conversation,reason=None):
    nid=uuid.uuid4().hex
    reason=str(reason or '')[:240]
    with LOCK,connect() as conn:
        prune_notices(conn)
        active=conn.execute('SELECT COUNT(*) FROM calls WHERE owner=? AND closed=0 AND created>?',(owner,time.time()-4*3600)).fetchone()[0]
        if active:
            if conn.execute('SELECT COUNT(*) FROM deferred').fetchone()[0]>=256: raise RuntimeError('Deferred callback capacity reached')
            conn.execute('INSERT INTO deferred(id,owner,profile,conversation,created,reason) VALUES(?,?,?,?,?,?)',(nid,owner,profile,ident(conversation),time.time(),reason))
            return nid
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification outbox capacity reached')
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(nid,owner,profile,'incoming_call',ident(conversation),time.time(),time.time()+120,reason))
    return nid


def refs(owner,profile,conversation=None):
    with LOCK,connect() as conn:
        row=conn.execute("SELECT value FROM meta WHERE key='reference_key'").fetchone()
        if row: key=bytes(row[0])
        else:
            key=os.urandom(32);conn.execute("INSERT INTO meta VALUES('reference_key',?)",(key,))
    def digest(value): return hmac.new(key,value.encode(),hashlib.sha256).hexdigest()
    result={'source_ref':digest('source:'+owner+':'+profile)}
    if conversation: result['conversation_ref']=digest('conversation:'+owner+':'+profile+':'+conversation)
    return result


def voice_history(owner,profile,stored,limit=200,text_budget=1024*1024):
    limit=max(1,min(int(limit),200));remaining=max(1,min(int(text_budget),1024*1024))
    output=[];truncated=False
    with connect() as conn:
        calls=conn.execute("SELECT id,created FROM calls c WHERE owner=? AND profile=? AND conversation=? AND EXISTS(SELECT 1 FROM timeline t WHERE t.call_id=c.id AND json_extract(t.payload,'$.type')='transcript') ORDER BY created DESC,id DESC LIMIT ?",(owner,profile,stored,limit+1)).fetchall()
        for call_index,call in enumerate(calls):
            groups=conn.execute("SELECT json_extract(payload,'$.role') AS role,json_extract(payload,'$.turn_id') AS turn,MAX(json_extract(payload,'$.at_ms')) AS last_at FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='transcript' GROUP BY role,turn ORDER BY last_at DESC,role,turn LIMIT ?",(call['id'],limit+1)).fetchall()
            for group in groups:
                if len(output)>=limit: truncated=True;break
                params=(call['id'],group['role'],group['turn'])
                final=conn.execute("SELECT payload FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='transcript' AND json_extract(payload,'$.role')=? AND json_extract(payload,'$.turn_id') IS ? AND json_extract(payload,'$.final')=1 ORDER BY json_extract(payload,'$.at_ms') DESC,rowid DESC LIMIT 1",params).fetchone()
                if final:
                    event=json.loads(final[0]);text=event['text'];is_final=True
                else:
                    versions=conn.execute("SELECT payload FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='transcript' AND json_extract(payload,'$.role')=? AND json_extract(payload,'$.turn_id') IS ? ORDER BY json_extract(payload,'$.at_ms') DESC,rowid DESC LIMIT 1",params).fetchall()
                    event=json.loads(versions[0][0]);text=event['text']+' [unfinalized transcript]';is_final=False
                encoded=text.encode('utf-8')
                if len(encoded)>remaining:
                    truncated=True
                    marker=' [unfinalized transcript]' if not is_final else ''
                    marker_bytes=marker.encode('utf-8')
                    if remaining<len(marker_bytes): break
                    text=encoded[:remaining-len(marker_bytes)].decode('utf-8',errors='ignore')+marker
                if not text and remaining<=0: truncated=True;break
                remaining-=len(text.encode('utf-8'))
                interrupted=bool(event.get('interrupted'))
                if group['role']=='assistant' and not interrupted:
                    interrupted=bool(conn.execute("SELECT 1 FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='interruption' AND json_extract(payload,'$.turn_id') IS ? LIMIT 1",(call['id'],group['turn'])).fetchone())
                output.append({'call_id':call['id'],'event_id':call['id']+':'+group['role']+':'+str(group['turn'] or 'none'),'session_id':stored,'role':group['role'],'text':text,'final':is_final,'interrupted':interrupted,'at_ms':event['at_ms'],'created_at':call['created']+event['at_ms']/1000})
                if remaining<=0: truncated=True;break
            if len(output)>=limit or remaining<=0:
                if call_index+1<len(calls): truncated=True
                break
    output.sort(key=lambda row:(row['created_at'],row['event_id']))
    return {'session_id':stored,'profile':profile,'events':output,'truncated':truncated}
