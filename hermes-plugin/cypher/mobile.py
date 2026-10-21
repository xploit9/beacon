"""Private durable state shared by dashboard and native callback tool."""
import hashlib
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import sqlite3
import subprocess
import threading
import time
import uuid
import weakref
from datetime import datetime, timezone
from urllib.parse import urlencode, urlsplit

LOCK = threading.RLock()
PUSH_LOCK = threading.Lock()
_PROTECTED={}
_OWNER_SID=None
_CALL_LOCKS=weakref.WeakValueDictionary()
_SCHEMA_READY=set()
ID = re.compile(r"[A-Za-z0-9_-]{1,160}\Z")
MAX_MS = 4 * 60 * 60 * 1000
MAX_ACTIVE_CALLS = 8
HISTORY_RETENTION_DAYS = (0,30,90,365)
HISTORY_RETENTION_EVENTS_PER_BATCH = 1000
HISTORY_RETENTION_CALLS_PER_BATCH = 100
AUTO_UPDATE_INTERVAL_SECONDS = 6 * 60 * 60
PAIRING_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789'
PAIRING_CODE_LENGTH = 6
PAIRING_TTL_SECONDS = 600
PAIRING_TICKET_TTL_SECONDS = 900
PAIRING_ATTEMPT_WINDOW_SECONDS = 600
PAIRING_MAX_CLIENT_ATTEMPTS = 5
PAIRING_MAX_GLOBAL_ATTEMPTS = 20
PAIRING_CODE_ERROR = "That code didn't work. Make a new one on your PC."
PAIRING_RATE_LIMIT_ERROR = 'Too many pairing attempts. Try again later.'


class PairingRateLimitError(ValueError):
    pass

def _yaml_settings():
    from copy import deepcopy
    path=home()/'config.yaml'
    try:
        from utils import load_yaml_file_readonly
        if not path.is_file() or path.stat().st_size>1024*1024: return None
        cfg=load_yaml_file_readonly(path)
    except Exception: return None  # Native YAML engines differ; unreadable authority always fails closed.
    if not isinstance(cfg,dict): return None
    plugins=cfg.get('plugins')
    if not isinstance(plugins,dict): return None
    enabled,disabled=plugins.get('enabled',[]),plugins.get('disabled',[])
    if not isinstance(enabled,list) or not isinstance(disabled,list) or 'cypher' not in enabled or 'cypher' in disabled: return None
    entries=plugins.get('entries')
    if 'entries' in plugins and not isinstance(entries,dict): return None
    entry=entries.get('cypher') if isinstance(entries,dict) else None
    if entry is None: return {}
    if not isinstance(entry,dict): return None
    if 'settings' not in entry or entry['settings'] is None: return {}
    value=entry['settings']
    return deepcopy(value) if isinstance(value,dict) else None

def _valid_owner_identity(owner,provider):
    return all(isinstance(value,str) and value.strip() and len(value)<=256 and not any(ord(c)<32 or ord(c)==127 for c in value)
               for value in (owner,provider))

def _owner_claim():
    with LOCK,connect() as conn:
        row=conn.execute("SELECT value FROM meta WHERE key='owner_claim'").fetchone()
    if not row: return {}
    try:
        raw=row['value']
        if not isinstance(raw,(str,bytes)) or len(raw)>32768: return {}
        claim=json.loads(raw.decode('utf-8') if isinstance(raw,bytes) else raw)
        owner,provider=claim.get('owner_id'),claim.get('owner_provider')
        if not _valid_owner_identity(owner,provider): return {}
        result={'owner_id':owner,'owner_provider':provider}
        for key in ('callback_tool_enabled','scheduled_callbacks_enabled'):
            if type(claim.get(key)) is bool: result[key]=claim[key]
        conversations=claim.get('scheduled_callback_conversations')
        if isinstance(conversations,dict) and len(conversations)<=64:
            result['scheduled_callback_conversations']={profile:session for profile,session in conversations.items()
                if isinstance(profile,str) and 1<=len(profile)<=80 and not any(ord(c)<32 or ord(c)==127 for c in profile)
                and isinstance(session,str) and re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}',session)}
        return result
    except Exception:
        return {}

def settings(profile=None):
    result=_yaml_settings()
    if result is None: return {}
    claim=_owner_claim()
    if 'owner_id' not in result and 'owner_provider' not in result:
        result.update({key:claim[key] for key in ('owner_id','owner_provider') if key in claim})
    if result.get('owner_id')==claim.get('owner_id') and result.get('owner_provider')==claim.get('owner_provider'):
        for key in ('callback_tool_enabled','scheduled_callbacks_enabled'):
            if key not in result and key in claim: result[key]=claim[key]
        conversations=claim.get('scheduled_callback_conversations',{})
        if profile and 'scheduled_callback_conversation_id' not in result:
            stored=conversations.get(profile)
            if stored: result['scheduled_callback_conversation_id']=stored
    result.setdefault('release_repo','xploit9/cypher')
    result.setdefault('android_stable_certificate_sha256','')
    return result

def claim_owner(owner,provider):
    if not _valid_owner_identity(owner,provider): raise ValueError('Invalid dashboard owner')
    settings=_yaml_settings()
    if settings is None or 'owner_id' in settings or 'owner_provider' in settings: return False
    value=json.dumps({'owner_id':owner,'owner_provider':provider,'callback_tool_enabled':True,
                      'scheduled_callbacks_enabled':True},separators=(',',':'))
    with LOCK,connect() as conn:
        created=conn.execute('INSERT OR IGNORE INTO meta(key,value) VALUES(?,?)',('owner_claim',value)).rowcount==1
    return created

def register_assistant_conversation(owner,provider,profile,session_id,native_verify):
    if (not _valid_owner_identity(owner,provider) or not isinstance(profile,str) or not 1<=len(profile)<=80
            or any(ord(c)<32 or ord(c)==127 for c in profile)
            or not isinstance(session_id,str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}',session_id)):
        raise ValueError('Invalid assistant conversation registration')
    configured=_yaml_settings()
    if configured is None or ('owner_id' in configured or 'owner_provider' in configured) and (
            configured.get('owner_id')!=owner or configured.get('owner_provider')!=provider):
        return False
    with LOCK,connect() as conn:
        row=conn.execute("SELECT value FROM meta WHERE key='owner_claim'").fetchone()
        try: claim=json.loads(row['value']) if row else {'owner_id':owner,'owner_provider':provider}
        except (TypeError,ValueError): return False
        if not isinstance(claim,dict): return False
        if claim.get('owner_id')!=owner or claim.get('owner_provider')!=provider:
            if configured.get('owner_id')!=owner or configured.get('owner_provider')!=provider: return False
            claim={'owner_id':owner,'owner_provider':provider}
        conversations=claim.get('scheduled_callback_conversations',{})
        if not isinstance(conversations,dict) or len(conversations)>64: return False
        if profile not in conversations and len(conversations)>=64: return False
        stored=conversations.get(profile)
        if isinstance(stored,str) and stored:
            if stored==session_id: return stored
            try: native_verify(owner,provider,profile,stored)
            except LookupError: pass
            else: return stored
        conversations[profile]=session_id
        claim['scheduled_callback_conversations']=conversations
        value=json.dumps(claim,separators=(',',':'))
        if len(value)>32768: return False
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('owner_claim',?)",(value,))
    return session_id

_LAUNCH_HOME=None

def home():
    # Hermes briefly repoints HERMES_HOME while it deletes or renames another profile; the launch home never moves.
    global _LAUNCH_HOME
    if _LAUNCH_HOME is None:
        from hermes_constants import get_process_hermes_home
        _LAUNCH_HOME=Path(get_process_hermes_home())
    return _LAUNCH_HOME

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

def _initialize_schema(conn):
    conn.execute('PRAGMA journal_mode=WAL')
    conn.executescript('''
    CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY,value BLOB);
    CREATE TABLE IF NOT EXISTS calls(id TEXT PRIMARY KEY, owner TEXT, profile TEXT, conversation TEXT, created REAL, ended REAL, closed INTEGER DEFAULT 0, status TEXT DEFAULT 'pending', receipt TEXT DEFAULT '{}');
    CREATE TABLE IF NOT EXISTS call_log(id TEXT PRIMARY KEY, owner TEXT, profile TEXT, conversation TEXT, created REAL, expires REAL, state TEXT DEFAULT 'pending', call_id TEXT, reason TEXT DEFAULT '', email_notice_id TEXT DEFAULT '', feedback_verdict TEXT);
    CREATE TABLE IF NOT EXISTS timeline(call_id TEXT,event_id TEXT,digest TEXT,payload TEXT,PRIMARY KEY(call_id,event_id));
    CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,call_id TEXT,owner TEXT,profile TEXT,stored TEXT,runtime TEXT,request TEXT,title TEXT,callback INTEGER,epoch TEXT,seq INTEGER,status TEXT DEFAULT 'watching',UNIQUE(owner,profile,request));
    CREATE TABLE IF NOT EXISTS targets(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,token TEXT,expires REAL,payload TEXT);
    CREATE TABLE IF NOT EXISTS device_pairings(device_id TEXT PRIMARY KEY,owner TEXT NOT NULL,profile TEXT NOT NULL,name TEXT NOT NULL,dashboard_url TEXT NOT NULL,dashboard_name TEXT NOT NULL,code_hash TEXT NOT NULL,code_expires REAL NOT NULL,ticket_hash TEXT,ticket_expires REAL,redeemed_at REAL,linked_at REAL,created REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS cypher_devices(id TEXT PRIMARY KEY,owner TEXT NOT NULL,provider TEXT NOT NULL,profile TEXT NOT NULL,name TEXT NOT NULL,created REAL NOT NULL,last_seen REAL NOT NULL,removed INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS pairing_attempts(id INTEGER PRIMARY KEY,client_hash TEXT NOT NULL,attempted REAL NOT NULL);
    CREATE TABLE IF NOT EXISTS deferred(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL);
    CREATE TABLE IF NOT EXISTS notices(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,owner TEXT,profile TEXT,kind TEXT,conversation TEXT,created REAL,expires REAL,state TEXT DEFAULT 'pending',call_id TEXT,push TEXT DEFAULT 'pending');
    CREATE TABLE IF NOT EXISTS notice_deliveries(event TEXT REFERENCES notices(id) ON DELETE CASCADE,target TEXT REFERENCES targets(id) ON DELETE CASCADE,accepted REAL DEFAULT 0,attempts INTEGER DEFAULT 0,next_attempt REAL DEFAULT 0,last_error TEXT DEFAULT '',PRIMARY KEY(event,target));
    CREATE TABLE IF NOT EXISTS tool_receipts(owner TEXT,provider TEXT,profile TEXT,call_id TEXT,request_id TEXT,signature TEXT NOT NULL,payload TEXT NOT NULL,updated REAL NOT NULL,PRIMARY KEY(owner,provider,profile,call_id,request_id));
    ''')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_voice_calls ON calls(owner,profile,conversation,created DESC)')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_call_log ON call_log(owner,profile,created DESC)')
    conn.execute('CREATE UNIQUE INDEX IF NOT EXISTS device_pairing_code_hash ON device_pairings(code_hash)')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_cypher_devices ON cypher_devices(owner,profile,removed,last_seen DESC)')
    conn.execute('CREATE INDEX IF NOT EXISTS pairing_attempt_window ON pairing_attempts(attempted,client_hash)')
    conn.execute("CREATE INDEX IF NOT EXISTS transcript_versions ON timeline(call_id,json_extract(payload,'$.type'),json_extract(payload,'$.role'),json_extract(payload,'$.turn_id'),json_extract(payload,'$.final'),json_extract(payload,'$.at_ms'))")
    call_columns={r[1] for r in conn.execute('PRAGMA table_info(calls)')}
    if 'voice_config' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN voice_config TEXT NOT NULL DEFAULT '{}'")
    if 'callback_context' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN callback_context TEXT NOT NULL DEFAULT '{}'")
    if 'ended' not in call_columns: conn.execute('ALTER TABLE calls ADD COLUMN ended REAL')
    if 'seen' not in call_columns: conn.execute('ALTER TABLE calls ADD COLUMN seen REAL')
    columns={r[1] for r in conn.execute('PRAGMA table_info(jobs)')}
    for name in ('result','error'):
        if name not in columns: conn.execute('ALTER TABLE jobs ADD COLUMN '+name+" TEXT DEFAULT ''")
    if 'history_updated' not in columns:
        conn.execute('ALTER TABLE jobs ADD COLUMN history_updated REAL NOT NULL DEFAULT 0')
        # Legacy jobs have no change time; preserve them until their age is known.
        conn.execute('UPDATE jobs SET history_updated=?',(time.time(),))
    notice_columns={r[1] for r in conn.execute('PRAGMA table_info(notices)')}
    call_log_columns={r[1] for r in conn.execute('PRAGMA table_info(call_log)')}
    if 'email_notice_id' not in call_log_columns: conn.execute("ALTER TABLE call_log ADD COLUMN email_notice_id TEXT DEFAULT ''")
    if 'feedback_verdict' not in call_log_columns: conn.execute('ALTER TABLE call_log ADD COLUMN feedback_verdict TEXT')
    if 'release_version' not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN release_version INTEGER NOT NULL DEFAULT 0')
    if 'reason' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN reason TEXT DEFAULT ''")
    if 'job_id' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN job_id TEXT DEFAULT ''")
    for name in ('activity_key','runtime','request_id'):
        if name not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN '+name+" TEXT DEFAULT ''")
    conn.execute('''
    CREATE TRIGGER IF NOT EXISTS cypher_call_log_notice_insert AFTER INSERT ON notices
    WHEN NEW.kind='incoming_call'
    BEGIN
        INSERT OR IGNORE INTO call_log(id,owner,profile,conversation,created,expires,state,call_id,reason)
        VALUES(NEW.id,NEW.owner,NEW.profile,NEW.conversation,NEW.created,NEW.expires,NEW.state,NEW.call_id,NEW.reason);
    END;
    ''')
    conn.execute('''
    CREATE TRIGGER IF NOT EXISTS cypher_call_log_notice_update
    AFTER UPDATE OF owner,profile,conversation,created,expires,state,call_id,reason ON notices
    WHEN NEW.kind='incoming_call'
    BEGIN
        UPDATE call_log SET owner=NEW.owner,profile=NEW.profile,conversation=NEW.conversation,
            created=NEW.created,expires=NEW.expires,state=NEW.state,call_id=NEW.call_id,reason=NEW.reason
        WHERE id=NEW.id;
    END;
    ''')
    if not conn.execute("SELECT 1 FROM meta WHERE key='incoming_call_log_migrated'").fetchone():
        conn.execute("INSERT OR IGNORE INTO call_log(id,owner,profile,conversation,created,expires,state,call_id,reason) SELECT id,owner,profile,conversation,created,expires,state,call_id,reason FROM notices WHERE kind='incoming_call'")
        conn.execute("INSERT INTO meta(key,value) VALUES('incoming_call_log_migrated','1')")
    conn.execute("UPDATE call_log SET email_notice_id=id WHERE email_notice_id='' AND EXISTS "
                 "(SELECT 1 FROM meta WHERE key='proactive-email-notice:'||call_log.id)")
    deferred_columns={r[1] for r in conn.execute('PRAGMA table_info(deferred)')}
    if 'reason' not in deferred_columns: conn.execute("ALTER TABLE deferred ADD COLUMN reason TEXT DEFAULT ''")

def _connect():
    root = home() / 'plugin-data' / 'cypher'
    protect(root)
    database = root / 'mobile.sqlite'
    conn = sqlite3.connect(database, timeout=15)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    try:
        info=database.stat()
        schema_key=(str(database.resolve()),info.st_dev,info.st_ino)
        if schema_key not in _SCHEMA_READY:
            _initialize_schema(conn)
            conn.commit()
            _SCHEMA_READY.add(schema_key)
    except Exception:
        conn.close()
        raise
    return conn

def connect():
    with LOCK: return _connect()


def dashboard_connect_info():
    try:
        from hermes_cli.dashboard_auth.prefix import resolve_public_url

        url = resolve_public_url()
        parsed = urlsplit(url)
        host = (parsed.hostname or '').rstrip('.').lower()
        port = parsed.port
        try: address = ipaddress.ip_address(host)
        except ValueError: address = None
        numeric_host = bool(re.fullmatch(r'(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*', host))
        loopback = (host == 'localhost' or host.endswith('.localhost') or (numeric_host and address is None)
                    or (address is not None and (address.is_loopback or address.is_unspecified
                        or bool(getattr(address, 'ipv4_mapped', None) and address.ipv4_mapped.is_loopback))))
        if (not isinstance(url, str) or parsed.scheme.lower() != 'https' or not parsed.netloc or not host
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment
                or port is not None and not 1 <= port <= 65535 or loopback):
            raise ValueError()
        return url, host[:80]
    except Exception:
        raise ValueError('A configured public HTTPS dashboard URL is required to connect a phone.') from None


def _pairing_name(value, default='Cypher phone'):
    if value is None:
        value = default
    if (not isinstance(value, str) or not (value := value.strip()) or len(value) > 80
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ValueError('Invalid device name')
    return value


def _pairing_code(value):
    if not isinstance(value, str):
        return None
    value = value.strip().upper()
    if len(value) == PAIRING_CODE_LENGTH + 1 and value[3:4] == '-':
        value = value[:3] + value[4:]
    return value if len(value) == PAIRING_CODE_LENGTH and all(char in PAIRING_ALPHABET for char in value) else None


def _pairing_code_hash(value):
    return hashlib.sha256(b'cypher-pairing-v1:' + value.encode('ascii')).hexdigest()


def format_pairing_code(value):
    return value[:3] + '-' + value[3:]


def mint_device_pairing(owner, profile, name, dashboard_url, dashboard_name):
    if (not isinstance(owner, str) or not owner or len(owner) > 256 or any(ord(c) < 32 for c in owner)
            or not isinstance(profile, str) or not 1 <= len(profile) <= 80
            or any(ord(c) < 32 or ord(c) == 127 for c in profile)):
        raise ValueError('An owned profile is required')
    name = _pairing_name(name)
    dashboard_name = _pairing_name(dashboard_name, 'Hermes')
    if not isinstance(dashboard_url, str) or not dashboard_url.startswith('https://'):
        raise ValueError('A configured public HTTPS dashboard URL is required to connect a phone.')
    now = time.time()
    device_id = uuid.uuid4().hex
    expires = now + PAIRING_TTL_SECONDS
    with LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        conn.execute('DELETE FROM device_pairings WHERE linked_at IS NOT NULL OR (code_expires<=? AND (ticket_expires IS NULL OR ticket_expires<=?))', (now, now))
        for _ in range(8):
            code = ''.join(secrets.choice(PAIRING_ALPHABET) for _ in range(PAIRING_CODE_LENGTH))
            code_hash = _pairing_code_hash(code)
            if not conn.execute('SELECT 1 FROM device_pairings WHERE code_hash=?', (code_hash,)).fetchone():
                break
        else:
            raise RuntimeError('Could not create a unique pairing code')
        conn.execute('INSERT INTO device_pairings(device_id,owner,profile,name,dashboard_url,dashboard_name,code_hash,code_expires,created) VALUES(?,?,?,?,?,?,?,?,?)',
                     (device_id, owner, profile, name, dashboard_url, dashboard_name, code_hash, expires, now))
    formatted = format_pairing_code(code)
    return {'device_id': device_id, 'code': formatted,
            'expires_at': datetime.fromtimestamp(expires, timezone.utc).isoformat().replace('+00:00', 'Z'),
            'dashboard_url': dashboard_url,
            'qr_link': 'cypher://connect?' + urlencode({'url': dashboard_url, 'name': dashboard_name, 'code': formatted})}


def redeem_device_pairing(code, device_name, client_host):
    name = _pairing_name(device_name) if device_name is not None else None
    normalized = _pairing_code(code)
    digest = _pairing_code_hash(normalized or 'A' * PAIRING_CODE_LENGTH)
    client_hash = hashlib.sha256(str(client_host or 'unknown').encode('utf-8', 'replace')[:512]).hexdigest()
    now = time.time()
    result = None
    failure = None
    with LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        conn.execute('DELETE FROM pairing_attempts WHERE attempted<=?', (now - PAIRING_ATTEMPT_WINDOW_SECONDS,))
        client_attempts = conn.execute('SELECT COUNT(*) FROM pairing_attempts WHERE client_hash=?', (client_hash,)).fetchone()[0]
        global_attempts = conn.execute('SELECT COUNT(*) FROM pairing_attempts').fetchone()[0]
        row = conn.execute('SELECT * FROM device_pairings WHERE code_hash=?', (digest,)).fetchone()
        stored_hash = row['code_hash'] if row else '0' * 64
        code_matches = bool(normalized) and hmac.compare_digest(digest, stored_hash)
        if client_attempts >= PAIRING_MAX_CLIENT_ATTEMPTS or global_attempts >= PAIRING_MAX_GLOBAL_ATTEMPTS:
            failure = PairingRateLimitError(PAIRING_RATE_LIMIT_ERROR)
        elif not code_matches or row['code_expires'] <= now or row['redeemed_at'] is not None:
            conn.execute('INSERT INTO pairing_attempts(client_hash,attempted) VALUES(?,?)', (client_hash, now))
            failure = ValueError(PAIRING_CODE_ERROR)
        else:
            ticket = secrets.token_urlsafe(32)
            ticket_hash = hashlib.sha256(ticket.encode('ascii')).hexdigest()
            ticket_expires = now + PAIRING_TICKET_TTL_SECONDS
            changed = conn.execute('UPDATE device_pairings SET redeemed_at=?,ticket_hash=?,ticket_expires=? WHERE device_id=? AND redeemed_at IS NULL AND code_expires>?',
                                   (now, ticket_hash, ticket_expires, row['device_id'], now)).rowcount
            if changed != 1:
                conn.execute('INSERT INTO pairing_attempts(client_hash,attempted) VALUES(?,?)', (client_hash, now))
                failure = ValueError(PAIRING_CODE_ERROR)
            else:
                final_name = row['name'] if row['name'] != 'Cypher phone' or name is None else name
                conn.execute('UPDATE device_pairings SET name=? WHERE device_id=?', (final_name, row['device_id']))
                result = {'url': row['dashboard_url'], 'name': row['dashboard_name'],
                          'device_id': row['device_id'], 'link_ticket': ticket}
    if failure:
        raise failure
    return result


def link_paired_device(device_id, ticket, owner, provider, allowed_profiles=None):
    if (not isinstance(device_id, str) or not ID.fullmatch(device_id) or not isinstance(ticket, str)
            or not re.fullmatch(r'[A-Za-z0-9_-]{40,64}', ticket)):
        return None
    now = time.time()
    digest = hashlib.sha256(ticket.encode('ascii')).hexdigest()
    with LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row = conn.execute('SELECT * FROM device_pairings WHERE device_id=? AND owner=?', (device_id, owner)).fetchone()
        if (not row or allowed_profiles is not None and row['profile'] not in allowed_profiles
                or not row['ticket_hash'] or row['ticket_expires'] is None or row['ticket_expires'] <= now):
            return None
        if not hmac.compare_digest(digest, row['ticket_hash']):
            return None
        conn.execute('INSERT INTO cypher_devices(id,owner,provider,profile,name,created,last_seen) VALUES(?,?,?,?,?,?,?)',
                     (device_id, owner, provider, row['profile'], row['name'], now, now))
        conn.execute('UPDATE device_pairings SET ticket_hash=NULL,ticket_expires=NULL,linked_at=? WHERE device_id=?', (now, device_id))
        return {'linked': True, 'device_id': device_id, 'name': row['name'],
                'profile': row['profile'], 'created_at': now}


def list_devices(owner):
    with LOCK, connect() as conn:
        rows = conn.execute('SELECT id,name,profile,created,last_seen FROM cypher_devices WHERE owner=? AND removed=0 ORDER BY last_seen DESC,created DESC', (owner,)).fetchall()
    return [{'device_id': row['id'], 'name': row['name'], 'profile': row['profile'],
             'created_at': row['created'], 'last_seen': row['last_seen']} for row in rows]


def remove_device(owner, device_id):
    now = time.time()
    with LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row = conn.execute('SELECT profile FROM cypher_devices WHERE id=? AND owner=? AND removed=0', (device_id, owner)).fetchone()
        if not row:
            return False
        conn.execute('UPDATE cypher_devices SET removed=1,last_seen=? WHERE id=? AND owner=?', (now, device_id, owner))
        conn.execute('DELETE FROM notice_deliveries WHERE target=?', (device_id,))
        conn.execute('DELETE FROM targets WHERE id=? AND owner=? AND profile=?', (device_id, owner, row['profile']))
    return True


def touch_device(owner, provider, profile, device_id, update_seen=True):
    if not isinstance(device_id, str) or not ID.fullmatch(device_id):
        raise LookupError('device_not_authorized')
    now = time.time()
    with LOCK, connect() as conn:
        row = conn.execute('SELECT owner,provider,profile,removed FROM cypher_devices WHERE id=?', (device_id,)).fetchone()
        if not row or row['owner'] != owner or row['provider'] != provider or profile is not None and row['profile'] != profile:
            raise LookupError('device_not_authorized')
        if row['removed']:
            raise LookupError('device_removed')
        if update_seen: conn.execute('UPDATE cypher_devices SET last_seen=? WHERE id=? AND removed=0', (now, device_id))

def enqueue_task_result(owner,profile,conversation,job_id,reason=''):
    if (not isinstance(owner,str) or not owner or len(owner)>256 or any(ord(c)<32 for c in owner)
            or not isinstance(profile,str) or not profile or len(profile)>80
            or not isinstance(job_id,str) or not ID.fullmatch(job_id)
            or not isinstance(reason,str) or len(reason)>8192 or '\x00' in reason):
        raise ValueError('Invalid task notification')
    conversation=ident(conversation)
    with LOCK,connect() as conn:
        prune_notices(conn)
        existing=conn.execute("SELECT id FROM notices WHERE owner=? AND profile=? AND conversation=? AND job_id=? AND kind='task_result' AND expires>? ORDER BY created DESC LIMIT 1",
                              (owner,profile,conversation,job_id,time.time())).fetchone()
        if existing: return existing['id']
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000:
            raise RuntimeError('Notification outbox capacity reached')
        event=uuid.uuid4().hex
        now=time.time()
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,job_id,reason) VALUES(?,?,?,?,?,?,?,?,?)',
                     (event,owner,profile,'task_result',conversation,now,now+86400,job_id,reason))
    return event

def _history_key(owner):
    if not isinstance(owner,str) or not owner: raise ValueError('Owner required')
    digest=hashlib.sha256(owner.encode('utf-8')).hexdigest()
    return 'history_retention_days:'+digest

def _history_days(conn,owner):
    row=conn.execute('SELECT value FROM meta WHERE key=?',(_history_key(owner),)).fetchone()
    if not row: return 0
    raw=row[0]
    try: days=int(raw.decode() if isinstance(raw,bytes) else raw)
    except (TypeError,ValueError,OverflowError): return 0
    return days if days in HISTORY_RETENTION_DAYS else 0

def history_retention_days(owner):
    with LOCK,connect() as conn: return _history_days(conn,owner)

def _active_history_notice(conn,call_id,owner,profile,now):
    return conn.execute("""SELECT 1 FROM notices n
        WHERE n.owner=? AND n.profile=? AND n.kind='incoming_call' AND n.state='pending' AND n.expires>?
          AND (n.call_id=? OR n.job_id IN (SELECT id FROM jobs WHERE call_id=? AND owner=? AND profile=?))
        LIMIT 1""",(owner,profile,now,call_id,call_id,owner,profile)).fetchone()

def _prune_history(conn,owner,days,now):
    if days<=0: return 0
    cutoff=now-days*86400
    removed_events=removed_calls=0
    removed_logs=conn.execute("""DELETE FROM call_log WHERE rowid IN
        (SELECT rowid FROM call_log WHERE owner=? AND created<? ORDER BY created,id LIMIT ?)""",
        (owner,cutoff,HISTORY_RETENTION_CALLS_PER_BATCH)).rowcount
    while removed_events<HISTORY_RETENTION_EVENTS_PER_BATCH and removed_calls<HISTORY_RETENTION_CALLS_PER_BATCH:
        call=conn.execute("""SELECT c.id,c.profile FROM calls c
            WHERE c.owner=? AND c.closed=1 AND c.created<?
              AND NOT EXISTS (SELECT 1 FROM jobs j WHERE j.call_id=c.id AND j.owner=c.owner AND j.profile=c.profile
                AND (j.status IS NULL OR j.status NOT IN ('completed','callback_pending','failed','cancelled') OR j.history_updated>=?))
              AND NOT EXISTS (SELECT 1 FROM notices n WHERE n.owner=c.owner AND n.profile=c.profile
                AND n.kind='incoming_call' AND n.state='pending' AND n.expires>?
                AND (n.call_id=c.id OR n.job_id IN (SELECT id FROM jobs WHERE call_id=c.id AND owner=c.owner AND profile=c.profile)))
            ORDER BY c.created,c.id LIMIT 1""",(owner,cutoff,cutoff,now)).fetchone()
        if not call: break
        call_id,profile=call['id'],call['profile']
        budget=HISTORY_RETENTION_EVENTS_PER_BATCH-removed_events
        deleted=conn.execute('DELETE FROM timeline WHERE rowid IN (SELECT rowid FROM timeline WHERE call_id=? LIMIT ?)',(call_id,budget)).rowcount
        removed_events+=deleted
        if conn.execute('SELECT 1 FROM timeline WHERE call_id=? LIMIT 1',(call_id,)).fetchone(): break
        conn.execute("DELETE FROM jobs WHERE call_id=? AND owner=? AND profile=? AND history_updated<? AND status IN ('completed','callback_pending','failed','cancelled')",
                     (call_id,owner,profile,cutoff))
        if _active_history_notice(conn,call_id,owner,profile,now): break
        deleted=conn.execute("""DELETE FROM calls WHERE id=? AND owner=? AND profile=? AND closed=1 AND created<?
            AND NOT EXISTS (SELECT 1 FROM jobs WHERE call_id=?)""",(call_id,owner,profile,cutoff,call_id)).rowcount
        if not deleted: break
        conn.execute('DELETE FROM tool_receipts WHERE call_id=? AND owner=? AND profile=?',(call_id,owner,profile))
        removed_calls+=deleted
    return removed_events+removed_calls+removed_logs

def prune_history(owner):
    now=time.time()
    with LOCK,connect() as conn:
        days=_history_days(conn,owner)
        if not days: return 0
        key='history_retention_last_run:'+hashlib.sha256(owner.encode('utf-8')).hexdigest()
        row=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        try: last=float(row[0].decode() if isinstance(row[0],bytes) else row[0]) if row else None
        except (TypeError,ValueError,OverflowError): last=None
        if last is not None and math.isfinite(last) and now-last<3600: return 0
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,str(now).encode()))
        return _prune_history(conn,owner,days,now)

def set_history_retention_days(owner,days):
    if type(days) is not int or days not in HISTORY_RETENTION_DAYS: raise ValueError('Invalid history retention period')
    now=time.time()
    with LOCK,connect() as conn:
        previous=_history_days(conn,owner)
        key=_history_key(owner)
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,str(days).encode()))
        if days and (previous==0 or days<previous):
            _prune_history(conn,owner,days,now)
            last_key='history_retention_last_run:'+hashlib.sha256(owner.encode('utf-8')).hexdigest()
            conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(last_key,str(now).encode()))
    return days

def _auto_update_key(kind,owner):
    if not isinstance(owner,str) or not owner: raise ValueError('Invalid owner')
    return 'auto-update:'+kind+':'+hashlib.sha256(owner.encode('utf-8')).hexdigest()

def auto_update_settings(owner):
    enabled_key=_auto_update_key('plugin-enabled',owner)
    status_key=_auto_update_key('status',owner)
    with LOCK,connect() as conn:
        enabled_row=conn.execute('SELECT value FROM meta WHERE key=?',(enabled_key,)).fetchone()
        status_row=conn.execute('SELECT value FROM meta WHERE key=?',(status_key,)).fetchone()
    stored=enabled_row[0].decode() if enabled_row and isinstance(enabled_row[0],bytes) else str(enabled_row[0]) if enabled_row else None
    result={'checked_at':0,'result':'never','app_version':'','plugin_version':'','message':''}
    if status_row:
        try:
            saved=json.loads(status_row[0].decode() if isinstance(status_row[0],bytes) else status_row[0])
            if isinstance(saved,dict): result.update(saved)
        except Exception: pass
    return {'enabled':stored!='0',**result}

def set_auto_plugin_updates(owner,enabled):
    if type(enabled) is not bool: raise ValueError('Invalid auto-update setting')
    key=_auto_update_key('plugin-enabled',owner)
    with LOCK,connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,b'1' if enabled else b'0'))
    return auto_update_settings(owner)

def record_auto_update_status(owner,checked_at,result,app_version='',plugin_version='',message=''):
    if type(checked_at) not in (int,float) or not math.isfinite(checked_at): raise ValueError('Invalid check time')
    if result not in {'checking','up_to_date','app_staged','plugin_updated','plugin_update_available','waiting_for_idle','restart_required','consent_required','error'}:
        raise ValueError('Invalid auto-update result')
    status={'checked_at':checked_at,'result':result,'app_version':str(app_version)[:64],
            'plugin_version':str(plugin_version)[:64],'message':str(message)[:240]}
    key=_auto_update_key('status',owner)
    with LOCK,connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,json.dumps(status,separators=(',',':')).encode()))
    return status

def call_lock(call_id):
    with LOCK:
        lock=_CALL_LOCKS.get(call_id)
        if lock is None:
            lock=threading.RLock();_CALL_LOCKS[call_id]=lock
        return lock

STALE_CALL_SECONDS=300

def active_calls(conn,owner):
    return conn.execute('SELECT COUNT(*) FROM calls WHERE owner=? AND closed=0 AND COALESCE(seen,created)>=?',(owner,time.time()-STALE_CALL_SECONDS)).fetchone()[0]

def prune_notices(conn):
    now=time.time()
    conn.execute("UPDATE calls SET closed=1,ended=COALESCE(ended,seen,created),status='failed' WHERE closed=0 AND COALESCE(seen,created)<?",(now-STALE_CALL_SECONDS,))
    prune_call_history(conn)
    # Move legacy answered invitation metadata into its owned call before pruning
    # the delivery outbox; call, timeline and job history use separate retention.
    rows=conn.execute("SELECT n.* FROM notices n JOIN calls c ON c.id=n.call_id AND c.owner=n.owner AND c.profile=n.profile WHERE n.state='answered' AND n.expires<? AND c.callback_context='{}'",(now,)).fetchall()
    for row in rows:
        context={key:row[key] for key in ('id','reason','job_id','conversation')}
        conn.execute('UPDATE calls SET callback_context=? WHERE id=?',(json.dumps(context),row['call_id']))
    # Keep expired native-request admission until it settles; polling must not renew an old action.
    conn.execute("DELETE FROM notices WHERE expires<? AND NOT(activity_key!='' AND state='pending' AND kind IN ('working','approval','clarify'))",(now,))


def prune_call_history(conn,now=None):
    now=time.time() if now is None else now
    return conn.execute("UPDATE call_log SET state='missed' WHERE state='pending' AND expires<=?",(now,)).rowcount


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

def reserve(owner, profile, conversation, voice=None):
    call_id = uuid.uuid4().hex
    with LOCK, connect() as conn:
        active=active_calls(conn,owner)
        if active>=MAX_ACTIVE_CALLS: raise ValueError('Active call limit reached')
        config={**(voice or {}),'client_key':True}
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
        ended=time.time()
        conn.execute('UPDATE calls SET status=?,closed=1,ended=COALESCE(ended,?),receipt=? WHERE id=?',(state,ended,json.dumps(receipt),call_id))
        call.update(status=state,closed=1,ended=call['ended'] or ended,receipt=json.dumps(receipt))
        return status(call)

def invitation(owner,profile,conversation,reason=None,*,deduplicate=False):
    nid=uuid.uuid4().hex
    reason=str(reason or '')[:240]
    with LOCK,connect() as conn:
        prune_notices(conn)
        if deduplicate:
            # Same chat only: another chat's ringing call carries another reason.
            existing=conn.execute("SELECT id FROM notices WHERE owner=? AND profile=? AND conversation=? AND kind='incoming_call' AND state='pending' AND expires>? ORDER BY created,id LIMIT 1",
                                  (owner,profile,ident(conversation),time.time())).fetchone()
            if existing: return existing['id']
            existing=conn.execute('SELECT id FROM deferred WHERE owner=? AND profile=? AND conversation=? ORDER BY created,id LIMIT 1',
                                  (owner,profile,ident(conversation))).fetchone()
            if existing: return existing['id']
        active=active_calls(conn,owner)
        if active:
            if conn.execute('SELECT COUNT(*) FROM deferred').fetchone()[0]>=256: raise RuntimeError('Deferred callback capacity reached')
            conn.execute('INSERT INTO deferred(id,owner,profile,conversation,created,reason) VALUES(?,?,?,?,?,?)',(nid,owner,profile,ident(conversation),time.time(),reason))
            return nid
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification outbox capacity reached')
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(nid,owner,profile,'incoming_call',ident(conversation),time.time(),time.time()+120,reason))
    return nid


def reference_key():
    with LOCK,connect() as conn:
        row=conn.execute("SELECT value FROM meta WHERE key='reference_key'").fetchone()
        if row: key=bytes(row[0])
        else:
            key=os.urandom(32);conn.execute("INSERT INTO meta VALUES('reference_key',?)",(key,))

    return key

def refs(owner,profile,conversation=None,key=None):
    if key is None: key=reference_key()
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
            # A turn sits where it began: speech transcription can finalize after the reply has started.
            groups=conn.execute("SELECT json_extract(payload,'$.role') AS role,json_extract(payload,'$.turn_id') AS turn,MIN(json_extract(payload,'$.at_ms')) AS first_at FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='transcript' GROUP BY role,turn ORDER BY first_at DESC,role,turn LIMIT ?",(call['id'],limit+1)).fetchall()
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
                output.append({'call_id':call['id'],'event_id':call['id']+':'+group['role']+':'+str(group['turn'] or 'none'),'session_id':stored,'role':group['role'],'text':text,'final':is_final,'interrupted':interrupted,'at_ms':event['at_ms'],'created_at':call['created']+(group['first_at'] or 0)/1000})
                if remaining<=0: truncated=True;break
            if len(output)>=limit or remaining<=0:
                if call_index+1<len(calls): truncated=True
                break
    output.sort(key=lambda row:(row['created_at'],row['event_id']))
    return {'session_id':stored,'profile':profile,'events':output,'truncated':truncated}
