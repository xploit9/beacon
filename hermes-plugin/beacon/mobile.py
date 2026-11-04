"""Private durable state shared by dashboard and native callback tool."""
import hashlib
import hmac
import ipaddress
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
from urllib.parse import urlsplit

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
MAX_DEVICES = 32
DEVICE_SEEN_SECONDS = 60
DEVICE_ID = re.compile(r"[A-Za-z0-9_-]{1,128}\Z")


class DeviceLimitError(ValueError):
    pass

# config path -> (file signature, plugin settings). The YAML is parsed again only when the file changed.
_YAML_CACHE={}
# A write can share the timestamp of the one before it; a file touched this recently is never trusted to the cache.
_YAML_RACY_NS=250_000_000

def _plugin_settings(cfg):
    if not isinstance(cfg,dict): return None
    plugins=cfg.get('plugins')
    if not isinstance(plugins,dict): return None
    enabled,disabled=plugins.get('enabled',[]),plugins.get('disabled',[])
    if not isinstance(enabled,list) or not isinstance(disabled,list) or 'beacon' not in enabled or 'beacon' in disabled: return None
    entries=plugins.get('entries')
    if 'entries' in plugins and not isinstance(entries,dict): return None
    entry=entries.get('beacon') if isinstance(entries,dict) else None
    if entry is None: return {}
    if not isinstance(entry,dict): return None
    if 'settings' not in entry or entry['settings'] is None: return {}
    value=entry['settings']
    return value if isinstance(value,dict) else None

def _yaml_settings():
    from copy import deepcopy
    path=home()/'config.yaml'
    try:
        from utils import load_yaml_file_readonly
        if not path.is_file(): return None
        info=path.stat()
        if info.st_size>1024*1024: return None
        signature=(info.st_mtime_ns,info.st_size,info.st_ino)
        held=_YAML_CACHE.get(str(path))
        if held and held[0]==signature: return deepcopy(held[1])
        cfg=load_yaml_file_readonly(path)
    except Exception: return None  # Native YAML engines differ; unreadable authority always fails closed.
    value=_plugin_settings(cfg)
    if time.time_ns()-info.st_mtime_ns>_YAML_RACY_NS:
        if len(_YAML_CACHE)>=8: _YAML_CACHE.clear()
        _YAML_CACHE[str(path)]=(signature,deepcopy(value))
    return deepcopy(value)

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

def register_assistant_conversation(owner,provider,profile,session_id,native_verify,provisional=False):
    if (not _valid_owner_identity(owner,provider) or not isinstance(profile,str) or not 1<=len(profile)<=80
            or any(ord(c)<32 or ord(c)==127 for c in profile)
            or not isinstance(session_id,str) or not re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}',session_id)):
        raise ValueError('Invalid assistant conversation registration')
    configured=_yaml_settings()
    if configured is None or ('owner_id' in configured or 'owner_provider' in configured) and (
            configured.get('owner_id')!=owner or configured.get('owner_provider')!=provider):
        return False
    with LOCK,connect() as conn:
        # The cron process saves a delivery chat here too; read and write under one write lock so neither loses the other's.
        conn.execute('BEGIN IMMEDIATE')
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
        drafts=claim.get('provisional_conversations')
        if not isinstance(drafts,dict): drafts={}
        # A chat made only to hold scheduled results gives way to the owner's own saved Beacon chat.
        if isinstance(stored,str) and stored and (provisional or drafts.get(profile)!=stored):
            if stored==session_id: return stored
            try: native_verify(owner,provider,profile,stored)
            except LookupError: pass
            else: return stored
        conversations[profile]=session_id
        claim['scheduled_callback_conversations']=conversations
        if provisional: drafts[profile]=session_id
        else: drafts.pop(profile,None)
        if drafts: claim['provisional_conversations']=drafts
        else: claim.pop('provisional_conversations',None)
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
        _OWNER_SID = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'], text=True, timeout=15,
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
    CREATE TABLE IF NOT EXISTS beacon_devices(id TEXT PRIMARY KEY,owner TEXT NOT NULL,provider TEXT NOT NULL,profile TEXT NOT NULL,name TEXT NOT NULL,created REAL NOT NULL,last_seen REAL NOT NULL,removed INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS deferred(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL);
    CREATE TABLE IF NOT EXISTS held_posts(key TEXT PRIMARY KEY,owner TEXT,provider TEXT,profile TEXT,conversation TEXT,name TEXT,text TEXT,created REAL);
    CREATE TABLE IF NOT EXISTS notices(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,owner TEXT,profile TEXT,kind TEXT,conversation TEXT,created REAL,expires REAL,state TEXT DEFAULT 'pending',call_id TEXT,push TEXT DEFAULT 'pending');
    CREATE TABLE IF NOT EXISTS notice_deliveries(event TEXT REFERENCES notices(id) ON DELETE CASCADE,target TEXT REFERENCES targets(id) ON DELETE CASCADE,accepted REAL DEFAULT 0,attempts INTEGER DEFAULT 0,next_attempt REAL DEFAULT 0,last_error TEXT DEFAULT '',PRIMARY KEY(event,target));
    CREATE TABLE IF NOT EXISTS tool_receipts(owner TEXT,provider TEXT,profile TEXT,call_id TEXT,request_id TEXT,signature TEXT NOT NULL,payload TEXT NOT NULL,updated REAL NOT NULL,PRIMARY KEY(owner,provider,profile,call_id,request_id));
    CREATE TABLE IF NOT EXISTS call_tool_allowances(owner TEXT,provider TEXT,profile TEXT,call_id TEXT,tool_id TEXT,tool_name TEXT,created REAL NOT NULL,PRIMARY KEY(owner,provider,profile,call_id,tool_name));
    CREATE TABLE IF NOT EXISTS phone_requests(id TEXT PRIMARY KEY,owner TEXT NOT NULL,profile TEXT NOT NULL,session TEXT NOT NULL DEFAULT '',tool TEXT NOT NULL,args TEXT NOT NULL,created REAL NOT NULL,expires REAL NOT NULL,status TEXT NOT NULL DEFAULT 'pending',result TEXT NOT NULL DEFAULT '',device TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS phone_requests_owner ON phone_requests(owner,profile,status,expires);
    CREATE TABLE IF NOT EXISTS phone_state(owner TEXT NOT NULL,device TEXT NOT NULL,updated REAL NOT NULL,state TEXT NOT NULL,PRIMARY KEY(owner,device));
    CREATE TRIGGER IF NOT EXISTS close_call_tool_allowances AFTER UPDATE OF closed,status ON calls
      WHEN NEW.closed=1 OR NEW.status IN ('saved','partial','failed')
      BEGIN DELETE FROM call_tool_allowances WHERE call_id=NEW.id AND owner=NEW.owner AND profile=NEW.profile; END;
    CREATE TRIGGER IF NOT EXISTS delete_call_tool_allowances AFTER DELETE ON calls
      BEGIN DELETE FROM call_tool_allowances WHERE call_id=OLD.id AND owner=OLD.owner AND profile=OLD.profile; END;
    ''')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_voice_calls ON calls(owner,profile,conversation,created DESC)')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_call_log ON call_log(owner,profile,created DESC)')
    # Backing indexes for the hot filters below; they upgrade an existing database in place.
    conn.execute('CREATE INDEX IF NOT EXISTS notices_owner_profile ON notices(owner,profile,seq)')
    conn.execute('CREATE INDEX IF NOT EXISTS notices_expires ON notices(expires)')
    conn.execute('CREATE INDEX IF NOT EXISTS notices_call_id ON notices(call_id)')
    conn.execute('CREATE INDEX IF NOT EXISTS jobs_call_id ON jobs(call_id)')
    conn.execute('CREATE INDEX IF NOT EXISTS jobs_owner_profile_status ON jobs(owner,profile,status)')
    conn.execute('CREATE INDEX IF NOT EXISTS call_log_call_id ON call_log(call_id)')
    conn.execute('CREATE INDEX IF NOT EXISTS owned_beacon_devices ON beacon_devices(owner,profile,removed,last_seen DESC)')
    conn.execute("CREATE INDEX IF NOT EXISTS transcript_versions ON timeline(call_id,json_extract(payload,'$.type'),json_extract(payload,'$.role'),json_extract(payload,'$.turn_id'),json_extract(payload,'$.final'),json_extract(payload,'$.at_ms'))")
    call_columns={r[1] for r in conn.execute('PRAGMA table_info(calls)')}
    if 'voice_config' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN voice_config TEXT NOT NULL DEFAULT '{}'")
    if 'callback_context' not in call_columns: conn.execute("ALTER TABLE calls ADD COLUMN callback_context TEXT NOT NULL DEFAULT '{}'")
    if 'ended' not in call_columns: conn.execute('ALTER TABLE calls ADD COLUMN ended REAL')
    if 'seen' not in call_columns: conn.execute('ALTER TABLE calls ADD COLUMN seen REAL')
    # Only unfinished calls are ever counted or swept, so the index holds just those rows.
    conn.execute('CREATE INDEX IF NOT EXISTS open_calls ON calls(owner,COALESCE(seen,created)) WHERE closed=0')
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
    conn.execute('CREATE INDEX IF NOT EXISTS call_log_email_notice ON call_log(email_notice_id)')
    if 'release_version' not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN release_version INTEGER NOT NULL DEFAULT 0')
    if 'reason' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN reason TEXT DEFAULT ''")
    if 'job_id' not in notice_columns: conn.execute("ALTER TABLE notices ADD COLUMN job_id TEXT DEFAULT ''")
    for name in ('activity_key','runtime','request_id'):
        if name not in notice_columns: conn.execute('ALTER TABLE notices ADD COLUMN '+name+" TEXT DEFAULT ''")
    conn.execute('''
    CREATE TRIGGER IF NOT EXISTS beacon_call_log_notice_insert AFTER INSERT ON notices
    WHEN NEW.kind='incoming_call'
    BEGIN
        INSERT OR IGNORE INTO call_log(id,owner,profile,conversation,created,expires,state,call_id,reason)
        VALUES(NEW.id,NEW.owner,NEW.profile,NEW.conversation,NEW.created,NEW.expires,NEW.state,NEW.call_id,NEW.reason);
    END;
    ''')
    conn.execute('''
    CREATE TRIGGER IF NOT EXISTS beacon_call_log_notice_update
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

class _Connection(sqlite3.Connection):
    """sqlite3's own `with` only commits; closing here as well keeps `with connect() as conn` from leaking the handle."""
    def __exit__(self, *exc):
        try: return super().__exit__(*exc)
        finally: self.close()

_ROOT_READY={}

def _protected_root():
    root = home() / 'plugin-data' / 'beacon'
    # protect() walks the path and its parents; once it passed, one stat tells whether the folder is still the same one.
    try:
        info = root.stat()
        if _ROOT_READY.get(str(root)) == (info.st_dev, info.st_ino): return root
    except OSError: pass
    protect(root)
    info = root.stat()
    _ROOT_READY[str(root)] = (info.st_dev, info.st_ino)
    return root

def _connect(thread_safe=False):
    root = _protected_root()
    database = root / 'mobile.sqlite'
    conn = sqlite3.connect(database, timeout=15, factory=_Connection, check_same_thread=not thread_safe)
    conn.row_factory = sqlite3.Row
    conn.execute('PRAGMA foreign_keys=ON')
    # WAL is on, so NORMAL only drops the per-commit fsync; a crash can lose the last commit but never corrupts.
    conn.execute('PRAGMA synchronous=NORMAL')
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

def connect(thread_safe=False):
    """A new connection; `thread_safe` lets a caller that never overlaps its own calls (a long poll) use it from several worker threads."""
    with LOCK: return _connect(thread_safe)


def read_plugin_version(path):
    """The version a plugin.yaml names, or '' when it is missing or unreadable."""
    try:
        import yaml
        value=yaml.safe_load(Path(path).read_text(encoding='utf-8-sig'))
        version=value.get('version') if isinstance(value,dict) else None
        return version.strip() if isinstance(version,str) else ''
    except Exception: return ''


# ---- Beacon releases ---------------------------------------------------------------------------------------------
# Owner, 3 Nov: a published release reaches the owner's phone as a notice, every time. Hermes is always on and has one stable
# address, so it asks GitHub (an unchanged answer is a free 304) and tells the phones over Keep connected; each phone then checks the
# release itself (metadata, size, checksum, signer and version, all from GitHub) and posts the update notice.
RELEASE_LATEST='https://api.github.com/repos/xploit9/beacon/releases/latest'
RELEASE_CHECK_SECONDS=300
RELEASE_DOCUMENT_LIMIT=262144
RELEASE_NOTICE_SECONDS=7*86400

def _fetch_latest_release(etag):
    """GitHub's latest Beacon release: (status, etag, body); a 304 when nothing was published since [etag]."""
    from urllib.error import HTTPError
    from urllib.request import Request, urlopen
    headers={'Accept':'application/vnd.github+json','X-GitHub-Api-Version':'2022-11-28','User-Agent':'Beacon-Hermes-plugin'}
    if etag: headers['If-None-Match']=etag
    try:
        with urlopen(Request(RELEASE_LATEST,headers=headers),timeout=10) as response:
            return response.status,response.headers.get('ETag'),response.read(RELEASE_DOCUMENT_LIMIT+1)
    except HTTPError as error:
        return error.code,None,b''

def _meta_text(conn,key):
    row=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
    value=row[0] if row else None
    return value.decode('utf-8','replace') if isinstance(value,bytes) else value

def check_release(owner,profile,fetch=None):
    """Asks GitHub for Beacon's latest release; one it has not announced yet becomes one app_update notice for [owner]'s phones.
    Returns the version announced, or '' (nothing new, a draft, an unreadable answer or no network). Never raises."""
    try:
        with connect() as conn: etag=_meta_text(conn,'release-etag')
        status,fresh,body=(fetch or _fetch_latest_release)(etag)
        if status!=200 or not body or len(body)>RELEASE_DOCUMENT_LIMIT: return ''
        document=json.loads(body)
        if not isinstance(document,dict): return ''
        tag=document.get('tag_name')
        if document.get('draft') or document.get('prerelease') or not isinstance(tag,str) or not re.fullmatch(r'v?\d{1,4}(\.\d{1,4}){1,3}',tag): return ''
        version=tag.removeprefix('v');now=time.time()
        with LOCK,connect() as conn:
            conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',('release-etag',str(fresh or '')[:200]))
            conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',('release-document',body))
            if _meta_text(conn,'release-announced')==version: return ''
            conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',('release-announced',version))
            # Keep connected carries it; the Firebase path has nothing to add for an update.
            conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,push) VALUES(?,?,?,?,?,?,?,'none')",
                         (uuid.uuid4().hex,owner,profile,'app_update','release-'+version.replace('.','-'),now,now+RELEASE_NOTICE_SECONDS))
        return version
    except (OSError,ValueError,TypeError,sqlite3.Error):
        return ''

def latest_release_document():
    """GitHub's own latest-release document as Hermes last read it (bytes), or None before the first good answer."""
    with connect() as conn:
        row=conn.execute('SELECT value FROM meta WHERE key=?',('release-document',)).fetchone()
    return bytes(row[0]) if row and row[0] else None


GATEWAY_VERSION_KEY='gateway_plugin_version'

def record_gateway_plugin_version(version,pid=None,at=None):
    """The gateway process notes which plugin version it loaded; the dashboard has its own, so the phone reads both."""
    value=json.dumps({'version':version,'pid':os.getpid() if pid is None else pid,'at':int(time.time() if at is None else at)})
    with LOCK,connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(GATEWAY_VERSION_KEY,value))


def gateway_plugin_version():
    """The version the gateway recorded at its last plugin load, '' when none or unreadable."""
    try:
        with connect() as conn:
            row=conn.execute('SELECT value FROM meta WHERE key=?',(GATEWAY_VERSION_KEY,)).fetchone()
        version=json.loads(row[0]).get('version') if row else ''
    except Exception: return ''
    return version.strip() if isinstance(version,str) else ''


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


def _device_name(value, default='Beacon phone'):
    if value is None:
        value = default
    if (not isinstance(value, str) or not (value := value.strip()) or len(value) > 80
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise ValueError('Invalid device name')
    return value


def link_device(owner, provider, name, device_id=None):
    """Creates the owner's phone record, or refreshes the named one when it is theirs and not removed. The caller's
    Hermes sign-in is the only credential; a removed or foreign ID is never reused."""
    name = _device_name(name)
    if device_id is not None and (not isinstance(device_id, str) or not DEVICE_ID.fullmatch(device_id)):
        raise ValueError('Invalid device id')
    now = time.time()
    with LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row = conn.execute('SELECT owner,provider,removed FROM beacon_devices WHERE id=?', (device_id,)).fetchone() if device_id else None
        if row and row['owner'] == owner and row['provider'] == provider and not row['removed']:
            conn.execute('UPDATE beacon_devices SET name=?,last_seen=? WHERE id=?', (name, now, device_id))
            return {'device_id': device_id, 'name': name}
        if conn.execute('SELECT COUNT(*) FROM beacon_devices WHERE owner=? AND removed=0', (owner,)).fetchone()[0] >= MAX_DEVICES:
            raise DeviceLimitError('Too many phones. Remove one in Devices.')
        device_id = uuid.uuid4().hex
        # The phone works across all of the owner's profiles, so the record carries no profile.
        conn.execute('INSERT INTO beacon_devices(id,owner,provider,profile,name,created,last_seen) VALUES(?,?,?,?,?,?,?)',
                     (device_id, owner, provider, '', name, now, now))
    return {'device_id': device_id, 'name': name}


def list_devices(owner):
    with LOCK, connect() as conn:
        rows = conn.execute('SELECT id,name,profile,created,last_seen FROM beacon_devices WHERE owner=? AND removed=0 ORDER BY last_seen DESC,created DESC', (owner,)).fetchall()
    return [{'device_id': row['id'], 'name': row['name'], 'profile': row['profile'],
             'created_at': row['created'], 'last_seen': row['last_seen']} for row in rows]


# A push target is live only while its payload binds a linked, non-removed phone of its owner; older unbound rows are skipped.
ACTIVE_TARGET = ("EXISTS (SELECT 1 FROM beacon_devices d WHERE d.owner=targets.owner AND d.removed=0 AND d.id="
                 "(CASE WHEN json_valid(targets.payload) THEN json_extract(targets.payload,'$.device_id') END))")


def remove_device(owner, device_id):
    now = time.time()
    with PUSH_LOCK, LOCK, connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row = conn.execute('SELECT removed FROM beacon_devices WHERE id=? AND owner=?', (device_id, owner)).fetchone()
        if not row:
            return False
        conn.execute('UPDATE beacon_devices SET removed=1,last_seen=? WHERE id=? AND owner=? AND removed=0', (now, device_id, owner))
        # Removing a phone again still revokes its tokens, including any it re-registered without its device ID.
        # Old registrations have no device binding, and an unreadable payload has none either; revoke those too
        # rather than leave a lost phone reachable (or abort on one malformed row).
        bound = "(CASE WHEN json_valid(payload) THEN json_extract(payload,'$.device_id') END)"
        conn.execute("DELETE FROM targets WHERE owner=? AND (id=? OR " + bound + "=? OR " + bound + " IS NULL OR token IN "
                     "(SELECT token FROM targets WHERE owner=? AND " + bound + "=?))",
                     (owner, device_id, device_id, owner, device_id))
    return True


def touch_device(owner, provider, profile, device_id, update_seen=True, require=False):
    """Marks the owner's phone as seen and refuses a phone the owner removed. The Hermes sign-in authorizes the
    request; the device ID is not a credential, so an unknown ID (or another owner's) changes nothing, except where
    `require` is set (push registration): there only a linked phone of the owner passes, else `device_required`."""
    if not isinstance(device_id, str) or not ID.fullmatch(device_id):
        if require: raise LookupError('device_required')
        return
    now = time.time()
    with LOCK, connect() as conn:
        row = conn.execute('SELECT owner,provider,removed,last_seen FROM beacon_devices WHERE id=?', (device_id,)).fetchone()
        if not row or row['owner'] != owner or row['provider'] != provider:
            if require: raise LookupError('device_required')
            return
        if row['removed']:
            raise LookupError('device_removed')
        # "Last seen" shows minutes, so a phone polling every second writes it once a minute, not on every request.
        if update_seen and not 0 <= now - row['last_seen'] < DEVICE_SEEN_SECONDS:
            conn.execute('UPDATE beacon_devices SET last_seen=? WHERE id=? AND removed=0', (now, device_id))

HELD_POST_SECONDS=86400
MAX_HELD_POSTS=200

def hold_post(owner,provider,profile,conversation,key,text,name):
    """A scheduled post its thread could not take yet (its own turn was running); the dashboard watcher adds it once the turn ends."""
    if not isinstance(key,str) or not ID.fullmatch(key) or not isinstance(text,str) or '\x00' in text: raise ValueError('Invalid scheduled post')
    conversation=ident(conversation)
    with LOCK,connect() as conn:
        conn.execute('DELETE FROM held_posts WHERE created<?',(time.time()-HELD_POST_SECONDS,))
        if conn.execute('SELECT COUNT(*) FROM held_posts').fetchone()[0]>=MAX_HELD_POSTS: raise RuntimeError('Scheduled post queue is full')
        conn.execute('INSERT OR IGNORE INTO held_posts VALUES(?,?,?,?,?,?,?,?)',(key,owner,provider,profile,conversation,str(name)[:120],text[:8192],time.time()))

def held_posts(owner,profile,limit=10):
    with LOCK,connect() as conn:
        # A post that never landed in a day (its thread was deleted, say) leaves the host with its text.
        conn.execute('DELETE FROM held_posts WHERE created<?',(time.time()-HELD_POST_SECONDS,))
        return [dict(row) for row in conn.execute('SELECT * FROM held_posts WHERE owner=? AND profile=? ORDER BY created LIMIT ?',(owner,profile,limit))]

def drop_held_post(owner,profile,key):
    with LOCK,connect() as conn: conn.execute('DELETE FROM held_posts WHERE owner=? AND profile=? AND key=?',(owner,profile,key))

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
        conn.execute('DELETE FROM call_tool_allowances WHERE call_id=? AND owner=? AND profile=?',(call_id,owner,profile))
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

def call_lock(call_id):
    with LOCK:
        lock=_CALL_LOCKS.get(call_id)
        if lock is None:
            lock=threading.RLock();_CALL_LOCKS[call_id]=lock
        return lock

STALE_CALL_SECONDS=300

def active_calls(conn,owner):
    return conn.execute('SELECT COUNT(*) FROM calls WHERE owner=? AND closed=0 AND COALESCE(seen,created)>=?',(owner,time.time()-STALE_CALL_SECONDS)).fetchone()[0]

def call_open_on(conn,owner,profile,conversation):
    """A live call (not stale) on this conversation: its replies are heard and shown in the call, so they raise no alert."""
    return conn.execute('SELECT 1 FROM calls WHERE owner=? AND closed=0 AND profile=? AND conversation=? AND COALESCE(seen,created)>=? LIMIT 1',
                        (owner,profile,conversation,time.time()-STALE_CALL_SECONDS)).fetchone() is not None

def prune_notices(conn):
    now=time.time()
    conn.execute("UPDATE calls SET closed=1,ended=COALESCE(ended,seen,created),status='failed' WHERE closed=0 AND COALESCE(seen,created)<?",(now-STALE_CALL_SECONDS,))
    conn.execute('''DELETE FROM call_tool_allowances WHERE NOT EXISTS
        (SELECT 1 FROM calls c WHERE c.id=call_id AND c.owner=call_tool_allowances.owner
         AND c.profile=call_tool_allowances.profile AND c.closed=0 AND c.created>?)''',(now-4*3600,))
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

# The call-start stages the phone reports, in order: a failed call names the one that did not finish.
CALL_STAGES = ('grant_sent','grant_ok','context_sent','context_ok','provider_open','provider_setup_ok','mic_streaming','first_model_audio')
DIAGNOSTIC_STAGES = ('capture_started','capture_stopped','playout_started','playout_stopped','capture_error','playout_error','media_timeout','timestamp_overlap_corrected','transport_started','transport_open','audio_ready','transport_timeout','voice_connecting','context_ready','token_ready','token_error','provider_key_ready','provider_ready','provider_error','voice_error')+CALL_STAGES
EVENT_TYPES = {'transcript','interruption','capture_started','capture_stopped','playout_started','playout_stopped','upload_gap','state','media_diagnostic','job_link','visual_state','tool_receipt'}
def events(call_id,owner,body):
    rows=body.get('events')
    if not isinstance(rows,list) or not 1<=len(rows)<=64: raise ValueError('Invalid event batch')
    clean=[]
    for item in rows:
        if not isinstance(item,dict) or item.get('type') not in EVENT_TYPES: raise ValueError('Unsupported event')
        row={k:item[k] for k in ('event_id','display_event_id','turn_id','type','role','text','final','interrupted','at_ms','stream','sequence','state','stage','elapsed_ms','count','total_corrected_ms','source','frames_sent','request_id','tool_name','status','error_code','provider','attempt','close_code','cause_class','failed_stage','warm') if k in item}
        ident(row.get('event_id')); ident(row.get('turn_id','none')); finite(row.get('at_ms'))
        if any(k in row for k in ('provider','attempt','close_code','cause_class','failed_stage','warm')):
            if row['type']!='media_diagnostic': raise ValueError('Unexpected transport diagnostic')
            if 'provider' in row and row['provider'] not in ('gemini','grok','openai'): raise ValueError('Invalid diagnostic provider')
            if 'attempt' in row and (type(row['attempt']) is not int or not 0<=row['attempt']<=10): raise ValueError('Invalid diagnostic attempt')
            if 'close_code' in row and (type(row['close_code']) is not int or not 0<=row['close_code']<=4999): raise ValueError('Invalid diagnostic close code')
            if 'cause_class' in row and row['cause_class'] not in ('ssl_handshake','ssl_peer','ssl_protocol','ssl_transport','socket_timeout','dns','connect','eof','io','other'): raise ValueError('Invalid diagnostic cause')
            if 'failed_stage' in row and row['failed_stage'] not in CALL_STAGES: raise ValueError('Invalid diagnostic stage')
            if 'warm' in row and type(row['warm']) is not bool: raise ValueError('Invalid diagnostic flag')
        if 'role' in row and row['role'] not in ('user','assistant'): raise ValueError('Invalid role')
        if 'text' in row and (row['type']!='transcript' or not isinstance(row['text'],str) or len(row['text'])>8192): raise ValueError('Invalid transcript')
        for k in ('final','interrupted'):
            if k in row and type(row[k]) is not bool: raise ValueError('Invalid event flag')
        if row['type']=='transcript' and ('role' not in row or 'text' not in row): raise ValueError('Transcript needs role and text')
        if 'display_event_id' in row:
            display_id=row['display_event_id'];prefix=call_id+':'+row.get('role','')+':'
            if row['type']!='transcript' or not isinstance(display_id,str) or len(display_id)>256 or not display_id.startswith(prefix):
                raise ValueError('Invalid transcript display identity')
            ident(display_id[len(prefix):])
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
        if 'stage' in row and row['stage'] not in DIAGNOSTIC_STAGES: raise ValueError('Invalid stage')
        if 'error_code' in row:
            codes=r'http_[45][0-9]{2}|timeout|dns|tls|network|invalid_response'
            if row['type']=='tool_receipt': codes+='|tool_http_failure|admission_rejected|memory_lookup_busy|memory_lookup_timeout|memory_lookup_failed'
            if row['type'] not in ('tool_receipt','media_diagnostic') or not isinstance(row['error_code'],str) or not re.fullmatch(codes,row['error_code']): raise ValueError('Invalid diagnostic code')
        if 'elapsed_ms' in row: finite(row['elapsed_ms'])
        for field,limit in (('count',1000000),('total_corrected_ms',9007199254740991)):
            if field in row and (row['type']!='media_diagnostic' or row.get('stage')!='timestamp_overlap_corrected' or type(row[field]) is not int or not 0<=row[field]<=limit): raise ValueError('Invalid clock diagnostic aggregate')
        raw=json.dumps(row,ensure_ascii=False,sort_keys=True,separators=(',',':'))
        clean.append((row['event_id'],hashlib.sha256(raw.encode()).hexdigest(),raw))
    with LOCK,connect() as conn:
        owned(conn,call_id,owner,writable=True)
        # One lookup for the batch's own ids (not one per event); the cap needs only the stored count.
        known={r[0]:r[1] for r in conn.execute('SELECT event_id,digest FROM timeline WHERE call_id=? AND event_id IN (%s)'%','.join('?'*len(clean)),(call_id,*(r[0] for r in clean)))}
        stored=conn.execute('SELECT COUNT(*) FROM timeline WHERE call_id=?',(call_id,)).fetchone()[0]
        if stored+len({r[0] for r in clean}-known.keys())>20000: raise ValueError('Timeline limit exceeded')
        for eid,digest,raw in clean:
            if known.setdefault(eid,digest)!=digest: raise ValueError('Conflicting duplicate event')
        conn.executemany('INSERT OR IGNORE INTO timeline VALUES(?,?,?,?)',[(call_id,eid,digest,raw) for eid,digest,raw in clean])
    return {'stored':True,'count':len(clean),'recording_status':'disabled','transcript_status':'syncing'}

def finalize(call_id,owner,body):
    incomplete=body.get('incomplete',False)
    if type(incomplete) is not bool: raise ValueError('Invalid transcript completeness flag')
    failed=body.get('failed',False)
    if type(failed) is not bool: raise ValueError('Invalid call failure flag')
    with call_lock(call_id), LOCK, connect() as conn:
        call=owned(conn,call_id,owner)
        prior=json.loads(call['receipt'])
        failed=failed or prior.get('failed') is True or call['status']=='failed'
        incomplete=incomplete or prior.get('incomplete') is True
        # A saved receipt is terminal: a replayed or retried finalize reports it and never rewrites its verdict.
        if call['closed'] and prior.get('transcript_available'):
            return status(call)
        rows=[json.loads(r[0]) for r in conn.execute('SELECT payload FROM timeline WHERE call_id=?',(call_id,))]
        groups={}
        for row in rows:
            if row['type']=='transcript':
                display_id=row.get('display_event_id') or call_id+':'+row['role']+':'+str(row.get('turn_id') or 'none')
                groups.setdefault(display_id,[]).append(row)
        receipt={'audio_available':False,'transcript_available':True,
                 'transcript_events':sum(r['type']=='transcript' and bool(r.get('final')) for r in rows),
                 'unfinalized_transcripts':sum(not any(r.get('final') for r in group) for group in groups.values())}
        receipt['incomplete']=incomplete or any(r['type']=='upload_gap' for r in rows)
        receipt['complete']=not receipt['unfinalized_transcripts'] and not receipt['incomplete']
        receipt['failed']=failed
        state='failed' if call['status']=='failed' else 'saved' if receipt['complete'] else 'partial'
        ended=time.time()
        conn.execute('UPDATE calls SET status=?,closed=1,ended=COALESCE(ended,?),receipt=? WHERE id=?',(state,ended,json.dumps(receipt),call_id))
        call.update(status=state,closed=1,ended=call['ended'] or ended,receipt=json.dumps(receipt))
        return status(call)

def invitation(owner,profile,conversation,reason=None,*,deduplicate=False,conn=None):
    from contextlib import nullcontext
    nid=uuid.uuid4().hex
    reason=str(reason or '')[:240]
    # Producer markers and enqueue must share the supplied connection's transaction.
    with LOCK,(connect() if conn is None else nullcontext(conn)) as conn:
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


# Phone access. A Hermes tool call becomes a request the owner's phone approves; the phone claims it once, runs it and posts the result.
PHONE_REQUEST_SECONDS=120
PHONE_OPEN_LIMIT=8
PHONE_PER_MINUTE=20
PHONE_KEEP_SECONDS=3600
# Past this age no tool is still waiting for a request's answer, so whatever text the request held is dropped.
PHONE_SETTLE_SECONDS=300
PHONE_RESULT_BYTES=16384
PHONE_STATE_BYTES=2048

def phone_state_put(owner,device,state):
    """A linked phone's own report of what it lets Hermes use; only that phone's latest report counts."""
    if not isinstance(owner,str) or not owner or not isinstance(device,str) or not DEVICE_ID.fullmatch(device) or not isinstance(state,dict):
        raise ValueError('Invalid phone state')
    raw=json.dumps(state,separators=(',',':'),sort_keys=True)
    if len(raw)>PHONE_STATE_BYTES: raise ValueError('Invalid phone state')
    with LOCK,connect() as conn:
        conn.execute('INSERT OR REPLACE INTO phone_state(owner,device,updated,state) VALUES(?,?,?,?)',(owner,device,time.time(),raw))
        # A removed phone's report leaves with it.
        conn.execute('DELETE FROM phone_state WHERE owner=? AND device NOT IN (SELECT id FROM beacon_devices WHERE owner=? AND removed=0)',(owner,owner))

def phone_state(owner):
    """What the owner's linked phones allow Hermes to use: any phone with access on counts; reads ask first unless every such phone says not to."""
    with connect() as conn:
        rows=conn.execute('SELECT s.state FROM phone_state s JOIN beacon_devices d ON d.id=s.device AND d.owner=s.owner AND d.removed=0 WHERE s.owner=?',(owner,)).fetchall()
    access=False;ask=False;tools=set()
    for row in rows:
        try: value=json.loads(row[0])
        except (TypeError,ValueError): continue
        if not isinstance(value,dict) or value.get('hermes_access') is not True: continue
        access=True;ask=ask or value.get('ask_reads') is not False
        if isinstance(value.get('tools'),list): tools.update(name for name in value['tools'] if isinstance(name,str))
    return {'hermes_access':access,'ask_reads':ask if access else True,'tools':tools if access else set()}

def _phone_expire(conn,now):
    """Settles what nobody can answer any more and drops its private text (calendar notes, message bodies, contact answers), so the
    text never outlives the request even when the tool that waited for it has gone."""
    conn.execute("UPDATE phone_requests SET status='expired',args='{}',result='' WHERE (status='pending' AND expires<=?) OR (status='approved' AND created<?)",
                 (now,now-PHONE_SETTLE_SECONDS))
    conn.execute("UPDATE phone_requests SET args='{}',result='' WHERE status IN ('done','denied','expired') AND created<? AND (args!='{}' OR result!='')",
                 (now-PHONE_SETTLE_SECONDS,))
    conn.execute('DELETE FROM phone_requests WHERE created<?',(now-PHONE_KEEP_SECONDS,))

def phone_requests_sweep():
    """The dashboard watcher's pass, so a request is cleaned up even when no phone asks about the queue."""
    with LOCK,connect() as conn: _phone_expire(conn,time.time())

def phone_request_create(owner,profile,session,tool,args):
    """Queues one tool call for the phone and raises its notice (same id), which reaches the phone like any other. Returns the id."""
    raw=json.dumps(args,separators=(',',':'),sort_keys=True)
    if not isinstance(tool,str) or not ID.fullmatch(tool) or len(raw)>8192: raise ValueError('Invalid phone request')
    request_id=uuid.uuid4().hex;now=time.time()
    with LOCK,connect() as conn:
        _phone_expire(conn,now)
        if conn.execute("SELECT COUNT(*) FROM phone_requests WHERE owner=? AND profile=? AND status IN ('pending','approved') AND expires>?",(owner,profile,now)).fetchone()[0]>=PHONE_OPEN_LIMIT:
            raise RuntimeError('Too many phone requests are waiting')
        # A runaway agent cannot bury the owner in cards: denied and expired requests count too.
        if conn.execute('SELECT COUNT(*) FROM phone_requests WHERE owner=? AND profile=? AND created>?',(owner,profile,now-60)).fetchone()[0]>=PHONE_PER_MINUTE:
            raise RuntimeError('Too many phone requests')
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification outbox capacity reached')
        conn.execute('INSERT INTO phone_requests(id,owner,profile,session,tool,args,created,expires) VALUES(?,?,?,?,?,?,?,?)',
                     (request_id,owner,profile,str(session or '')[:256],tool,raw,now,now+PHONE_REQUEST_SECONDS))
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)',
                     (request_id,owner,profile,'phone_request','phone-'+request_id,now,now+PHONE_REQUEST_SECONDS))
    return request_id

def _phone_allows(conn,owner,device):
    """The tools this one phone reported it lets Hermes use; none for a phone that reported access off or nothing."""
    row=conn.execute('SELECT s.state FROM phone_state s JOIN beacon_devices d ON d.id=s.device AND d.owner=s.owner AND d.removed=0 WHERE s.owner=? AND s.device=?',(owner,device)).fetchone()
    try: value=json.loads(row[0]) if row else {}
    except (TypeError,ValueError): value={}
    if not isinstance(value,dict) or value.get('hermes_access') is not True or not isinstance(value.get('tools'),list): return set()
    return {name for name in value['tools'] if isinstance(name,str)}

def phone_requests_open(owner,profile,device):
    """The requests this phone may still decide on this profile: only those for tools this phone reported it allows, so a phone
    that has them off never answers (or takes) a request meant for the owner's other phone."""
    now=time.time()
    with LOCK,connect() as conn:
        _phone_expire(conn,now)
        allowed=_phone_allows(conn,owner,device)
        rows=conn.execute("SELECT id,tool,args,created,expires FROM phone_requests WHERE owner=? AND profile=? AND status='pending' AND expires>? ORDER BY created LIMIT ?",
                          (owner,profile,now,PHONE_OPEN_LIMIT)).fetchall()
        shown=[]
        for r in rows:
            if r['tool'] not in allowed: continue
            try:
                args=json.loads(r['args'])
                json.dumps(args,ensure_ascii=False).encode('utf-8')  # what the reply will do: half an emoji cannot be sent
            except (ValueError,RecursionError):
                # A request that cannot be shown is over: one bad row never fails the list for the others.
                _phone_withdraw(conn,owner,profile,r['id'])
                continue
            shown.append({'id':r['id'],'tool':r['tool'],'args':args,'created_at':int(r['created']),'expires_at':int(r['expires']),
                          'expires_in_seconds':max(0,int(r['expires']-now))})
    return shown

def phone_request_decide(owner,profile,request_id,approved,device):
    """The phone's tap: approving claims the request for that phone (once), denying ends it. An expired, settled or foreign request changes nothing."""
    now=time.time()
    with LOCK,connect() as conn:
        _phone_expire(conn,now)
        row=conn.execute('SELECT status,tool FROM phone_requests WHERE id=? AND owner=? AND profile=?',(ident(request_id),owner,profile)).fetchone()
        # A phone that has not reported the tool as allowed never sees the request, so it cannot take it either.
        if not row or row['tool'] not in _phone_allows(conn,owner,device): raise LookupError('Phone request not found')
        if row['status']=='expired': raise ValueError('expired')
        if approved: changed=conn.execute("UPDATE phone_requests SET status='approved',device=? WHERE id=? AND status='pending'",(device,request_id)).rowcount
        else: changed=conn.execute("UPDATE phone_requests SET status='denied',device=?,args='{}' WHERE id=? AND status='pending'",(device,request_id)).rowcount
        if not changed: raise ValueError('settled')
        conn.execute("UPDATE notices SET state='answered' WHERE id=? AND owner=? AND profile=?",(request_id,owner,profile))

def phone_request_finish(owner,profile,request_id,device,result):
    """The result of a request this phone claimed; recorded once, even when it lands after the tool stopped waiting."""
    raw=json.dumps(result,separators=(',',':'))
    if not isinstance(result,dict) or len(raw)>PHONE_RESULT_BYTES: raise ValueError('Invalid phone result')
    with LOCK,connect() as conn:
        changed=conn.execute("UPDATE phone_requests SET status='done',result=?,args='{}' WHERE id=? AND owner=? AND profile=? AND status='approved' AND device=?",
                             (raw,ident(request_id),owner,profile,device)).rowcount
        if not changed:
            if not conn.execute('SELECT 1 FROM phone_requests WHERE id=? AND owner=? AND profile=?',(request_id,owner,profile)).fetchone():
                raise LookupError('Phone request not found')
            raise ValueError('settled')

def phone_request_get(owner,profile,request_id):
    """{'status','result'} of one request; a pending one past its time reads as expired. None when it is not this owner's."""
    with connect() as conn:
        row=conn.execute('SELECT status,result,expires FROM phone_requests WHERE id=? AND owner=? AND profile=?',(ident(request_id),owner,profile)).fetchone()
    if not row: return None
    status='expired' if row['status']=='pending' and row['expires']<=time.time() else row['status']
    return {'status':status,'result':json.loads(row['result']) if row['result'] else None}

def _phone_withdraw(conn,owner,profile,request_id):
    row=conn.execute('SELECT status FROM phone_requests WHERE id=? AND owner=? AND profile=?',(request_id,owner,profile)).fetchone()
    if not row: return None
    if row['status'] in ('pending','approved'):
        conn.execute("UPDATE phone_requests SET status='expired',args='{}',result='' WHERE id=?",(request_id,))
        conn.execute("UPDATE notices SET state='expired' WHERE id=? AND owner=? AND profile=? AND state='pending'",(request_id,owner,profile))
    return row['status']

def phone_request_withdraw(owner,profile,request_id):
    """The tool stopped waiting. A request still open ends, so the phone never shows, runs or reports it late; what its status was
    is returned ('pending', 'approved', 'done', 'denied', 'expired', or None when it is not this owner's)."""
    with LOCK,connect() as conn: return _phone_withdraw(conn,owner,profile,ident(request_id))

def phone_request_scrub(owner,profile,request_id):
    """The tool has what it needed or gave up: the arguments and the phone's answer (calendar text, contact numbers) are not kept,
    and a request still open ends here, so a late tap cannot run it."""
    with LOCK,connect() as conn:
        _phone_withdraw(conn,owner,profile,ident(request_id))
        conn.execute("UPDATE phone_requests SET args='{}',result='' WHERE id=? AND owner=? AND profile=?",(request_id,owner,profile))


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


def voice_history(owner,profile,stored,limit=200,text_budget=1024*1024,call_id=None):
    # One call's own transcript (the memory hand-over) may be read up to 2000 events; a call is admitted for four hours at most.
    limit=max(1,min(int(limit),2000 if call_id else 200));remaining=max(1,min(int(text_budget),1024*1024))
    output=[];truncated=False
    identity="COALESCE(json_extract(payload,'$.display_event_id'),call_id||':'||json_extract(payload,'$.role')||':'||COALESCE(json_extract(payload,'$.turn_id'),'none'))"
    with connect() as conn:
        # Keep metadata and rowid payload reads in one WAL snapshot while retention can delete/reuse rows.
        conn.execute('BEGIN')
        calls=conn.execute("SELECT id,created FROM calls c WHERE owner=? AND profile=? AND conversation=? AND (? IS NULL OR id=?) AND EXISTS(SELECT 1 FROM timeline t WHERE t.call_id=c.id AND json_extract(t.payload,'$.type')='transcript') ORDER BY created DESC,id DESC LIMIT ?",(owner,profile,stored,call_id,call_id,limit+1)).fetchall()
        for call_index,call in enumerate(calls):
            # Item updates retain their original turn/time, even when a late correction arrives in a new turn.
            # Rank metadata only; fetch bounded selected payloads by rowid instead of rescanning every item.
            groups=conn.execute(f"""WITH versions AS (
                SELECT rowid AS version_row,json_extract(payload,'$.role') AS role,{identity} AS display_id,
                    FIRST_VALUE(json_extract(payload,'$.turn_id')) OVER original AS turn,
                    FIRST_VALUE(json_extract(payload,'$.at_ms')) OVER original AS first_at,
                    FIRST_VALUE(rowid) OVER original AS first_ordinal,
                    ROW_NUMBER() OVER (PARTITION BY {identity} ORDER BY
                        CASE WHEN json_extract(payload,'$.final')=1 THEN 1 ELSE 0 END DESC,
                        json_extract(payload,'$.at_ms') DESC,rowid DESC) AS latest
                FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='transcript'
                WINDOW original AS (PARTITION BY {identity} ORDER BY json_extract(payload,'$.at_ms'),rowid)
            ) SELECT role,turn,display_id,first_at,first_ordinal,version_row FROM versions WHERE latest=1
                ORDER BY CASE WHEN CAST(turn AS TEXT)!='' AND CAST(turn AS TEXT) NOT GLOB '*[^0-9]*'
                    THEN CAST(turn AS INTEGER) ELSE first_at END DESC,
                    CASE role WHEN 'user' THEN 0 ELSE 1 END,first_at DESC,first_ordinal DESC,display_id DESC LIMIT ?""",(call['id'],limit+1)).fetchall()
            numeric_turns=bool(groups) and all(str(group['turn'] or '').isdigit() for group in groups)
            role_order=lambda group: 0 if group['role']=='user' else 1
            # Payloads come 32 at a time and the interrupted turns once per call, not one query per group.
            payloads={};interrupted_turns=None
            for position,group in enumerate(groups):
                if len(output)>=limit: truncated=True;break
                if group['version_row'] not in payloads:
                    chunk=[g['version_row'] for g in groups[position:position+32]]
                    payloads.update(conn.execute('SELECT rowid,payload FROM timeline WHERE rowid IN (%s)'%','.join('?'*len(chunk)),chunk).fetchall())
                event=json.loads(payloads.pop(group['version_row']))
                is_final=bool(event.get('final'));text=event['text']+('' if is_final else ' [unfinalized transcript]')
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
                    if interrupted_turns is None:
                        interrupted_turns={r[0] for r in conn.execute("SELECT DISTINCT json_extract(payload,'$.turn_id') FROM timeline WHERE call_id=? AND json_extract(payload,'$.type')='interruption'",(call['id'],))}
                    interrupted=group['turn'] in interrupted_turns
                turn_order=int(group['turn']) if numeric_turns else group['first_at'] or 0
                output.append({'call_id':call['id'],'event_id':group['display_id'],'session_id':stored,'turn_id':str(group['turn'] or ''),'role':group['role'],'text':text,'final':is_final,'interrupted':interrupted,'at_ms':event['at_ms'],'created_at':call['created']+(group['first_at'] or 0)/1000,'_order':(call['created'],0 if numeric_turns else 1,turn_order,role_order(group),group['first_at'] or 0,group['first_ordinal'],group['display_id'],call['id'])})
                if remaining<=0: truncated=True;break
            if len(output)>=limit or remaining<=0:
                if call_index+1<len(calls): truncated=True
                break
    output.sort(key=lambda row:row['_order'])
    for row in output: row.pop('_order',None)
    return {'session_id':stored,'profile':profile,'events':output,'truncated':truncated}
