"""Native one-shot callback scheduling; the scheduler and durable outbox own delivery."""
import importlib.util
from datetime import datetime, timedelta, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import sys
import tempfile

CALLBACK_WINDOW_SECONDS=120
CALLBACK_EARLY_TOLERANCE_SECONDS=1
CALLBACK_MARKER_RETENTION_SECONDS=CALLBACK_WINDOW_SECONDS+7*24*60*60
CALLBACK_MARKER_PRUNE_BATCH=256
_CALLBACK_EVENT=re.compile(r'[0-9a-f]{64}\Z')
_OBSERVER_KEY='beacon_observers'
observers=sys.modules.get(_OBSERVER_KEY)
if observers is None:
    spec=importlib.util.spec_from_file_location(_OBSERVER_KEY,Path(__file__).with_name('observers.py'))
    observers=importlib.util.module_from_spec(spec);sys.modules[_OBSERVER_KEY]=observers;spec.loader.exec_module(observers)

def _profile_now(profile):
    key='beacon_proactive'
    if key not in sys.modules:
        spec=importlib.util.spec_from_file_location(key,Path(__file__).with_name('proactive.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)
    return sys.modules[key].profile_now(profile)

def _prune_expired(conn,now):
    cutoff=now-CALLBACK_MARKER_RETENTION_SECONDS-1
    # A deferred notice still needs its scheduling marker to reject late activation.
    keys=conn.execute("""SELECT key,value FROM meta WHERE key LIKE 'scheduled-callback:%'
        AND CASE WHEN json_valid(CAST(value AS TEXT)) THEN
          CASE WHEN json_type(CAST(value AS TEXT),'$.scheduled_at')='text' THEN
            CAST(strftime('%s',json_extract(CAST(value AS TEXT),'$.scheduled_at')) AS REAL)<=?
            AND NOT EXISTS (SELECT 1 FROM deferred WHERE id=json_extract(CAST(meta.value AS TEXT),'$.delivered'))
          END
        END ORDER BY rowid LIMIT ?""",(cutoff,CALLBACK_MARKER_PRUNE_BATCH)).fetchall()
    conn.executemany('DELETE FROM meta WHERE key=?',
                     [(key,) for row in keys for key in (row[0],'missed-callback:'+row[0].split(':',1)[1],
                         'missed-callback:'+(json.loads(row[1]).get('delivered') or row[0].split(':',1)[1]))])


def _delivered_record(conn,event):
    return conn.execute("""SELECT value FROM meta WHERE key=? OR
        CASE WHEN key LIKE 'scheduled-callback:%' AND json_valid(CAST(value AS TEXT)) THEN
          json_extract(CAST(value AS TEXT),'$.delivered')=?
        END LIMIT 1""",('scheduled-callback:'+event,event)).fetchone()

def _authority(cfg,row):
    return (cfg.get('scheduled_callbacks_enabled') is True and cfg.get('owner_id')==row['owner']
            and cfg.get('owner_provider')==row['provider']
            and ('profiles' not in cfg or isinstance(cfg['profiles'],list) and row['profile'] in cfg['profiles']))

def _missed(mobile,native,conn,key,row,now):
    event=key.split(':',1)[1]
    if (not _CALLBACK_EVENT.fullmatch(event) or row.get('delivered')
            or row.get('status') not in ('scheduled','creating')): return False
    try:
        target=datetime.fromisoformat(row['scheduled_at'])
        if (now-target).total_seconds()<CALLBACK_WINDOW_SECONDS or not _authority(mobile.settings(row['profile']),row): return False
        native.verify(row['owner'],row['provider'],row['profile'],row['conversation'])
        reason=observers.reason_text(row['reason'])
    except (KeyError,TypeError,ValueError,LookupError,RuntimeError): return False  # an unopenable store: try again on the next pass
    observers._notice(mobile,row['owner'],row['profile'],row['conversation'],
                      'Missed scheduled callback: '+reason,'missed-callback:'+event,now,conn=conn)
    row['status']='missed'
    conn.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(row).encode(),key))
    return True

def prune_expired(mobile,native,owner,now=None):
    now=now if now is not None else datetime.now(timezone.utc).timestamp()
    instant=datetime.fromtimestamp(now,timezone.utc)
    key='scheduled-callback-prune:'+hashlib.sha256(owner.encode('utf-8')).hexdigest()
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        candidates=conn.execute("""SELECT key,value FROM meta WHERE key LIKE 'scheduled-callback:%'
            AND CASE WHEN json_valid(CAST(value AS TEXT)) THEN
              json_extract(CAST(value AS TEXT),'$.owner')=?
              AND json_extract(CAST(value AS TEXT),'$.status') IN ('scheduled','creating')
              AND json_extract(CAST(value AS TEXT),'$.delivered') IS NULL
              AND CAST(strftime('%s',json_extract(CAST(value AS TEXT),'$.scheduled_at')) AS REAL)<=?
            END ORDER BY rowid LIMIT ?""",(owner,now-CALLBACK_WINDOW_SECONDS,CALLBACK_MARKER_PRUNE_BATCH)).fetchall()
        missed=sum(_missed(mobile,native,conn,row[0],json.loads(row[1]),instant) for row in candidates)
        row=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        try: last=float(row[0].decode() if isinstance(row[0],bytes) else row[0]) if row else None
        except (TypeError,ValueError,OverflowError): last=None
        if last is not None and math.isfinite(last) and now-last<3600: return missed
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,str(now).encode()))
        _prune_expired(conn,now)
        return missed

def reasons(mobile,owner,provider,profile,limit=100):
    """job id -> what the owner asked to be called about, for the owner's own scheduled callbacks on this profile."""
    with mobile.LOCK,mobile.connect() as conn:
        rows=conn.execute("""SELECT value FROM meta WHERE key LIKE 'scheduled-callback:%' AND json_valid(CAST(value AS TEXT))
            AND json_extract(CAST(value AS TEXT),'$.owner')=? AND json_extract(CAST(value AS TEXT),'$.provider')=?
            AND json_extract(CAST(value AS TEXT),'$.profile')=? ORDER BY rowid DESC LIMIT ?""",(owner,provider,profile,limit)).fetchall()
    found={}
    for (value,) in rows:
        record=json.loads(value)
        if isinstance(record.get('job_id'),str) and isinstance(record.get('reason'),str):
            found[record['job_id']]={'reason':record['reason'][:120]}
    return found

def target_time(value, now):
    if set(value) - {'request_id','reason','delay_seconds','at'}: raise ValueError('Unknown callback field')
    if ('delay_seconds' in value) == ('at' in value): raise ValueError('Choose delay_seconds or at')
    if 'delay_seconds' in value:
        delay=value['delay_seconds']
        if type(delay) is not int or not 1<=delay<=366*86400: raise ValueError('Invalid callback delay')
        target=now+timedelta(seconds=delay)
    else:
        if not isinstance(value['at'],str): raise ValueError('Expected timezone-aware ISO callback time')
        target=datetime.fromisoformat(value['at'].replace('Z','+00:00'))
        if target.tzinfo is None or target.utcoffset() is None: raise ValueError('Callback time needs a timezone')
    target=target.astimezone(now.tzinfo)
    if not now<target<=now+timedelta(days=366): raise ValueError('Callback must be within the next year')
    return target

def script_file(home, name, source):
    home=Path(home).resolve(); folder=home/'scripts'
    folder.mkdir(exist_ok=True)
    if folder.is_symlink() or getattr(folder,'is_junction',lambda:False)() or folder.resolve().parent!=home: raise ValueError('Unsafe scripts directory')
    target=folder/name
    if target.is_symlink() or getattr(target,'is_junction',lambda:False)(): raise ValueError('Unsafe callback script')
    if target.exists() and target.read_text('utf-8')!=source: raise ValueError('Callback script differs; inspect on host')
    if not target.exists():
        temporary=None
        try:
            with tempfile.NamedTemporaryFile('w',encoding='utf-8',dir=folder,prefix='.'+name+'.',suffix='.tmp',delete=False) as stream:
                temporary=Path(stream.name)
                stream.write(source)
            if target.is_symlink() or getattr(target,'is_junction',lambda:False)(): raise ValueError('Unsafe callback script')
            if target.exists():
                if target.read_text('utf-8')!=source: raise ValueError('Callback script differs; inspect on host')
            else:
                os.replace(temporary,target)
        finally:
            if temporary: temporary.unlink(missing_ok=True)
    return name

def schedule(mobile,native,owner,provider,row,cfg,value,now=None):
    from hermes_cli.web_server_cron import _cron_profile_home,_call_cron_for_profile,_create_cron_job_sync
    from hermes_cli.web_models import CronJobCreate
    profile=row['profile'];stored=row['conversation']
    profile_clock=_profile_now(profile)
    if profile_clock.tzinfo is None or profile_clock.utcoffset() is None: raise ValueError('Callback time needs the profile timezone')
    zone=profile_clock.tzinfo
    now=now or profile_clock
    if now.tzinfo is None or now.utcoffset() is None: raise ValueError('Callback time needs the profile timezone')
    now=now.astimezone(zone)
    timezone_name=getattr(now.tzinfo,'key',None) or now.tzname()
    if cfg.get('scheduled_callbacks_enabled') is not True: raise ValueError('Scheduled callbacks are disabled on the host')
    if (owner!=cfg.get('owner_id') or provider!=cfg.get('owner_provider')
            or 'profiles' in cfg and (not isinstance(cfg['profiles'],list) or profile not in cfg['profiles'])): raise LookupError('Callback owner/profile unavailable')
    native.verify(owner,provider,profile,stored)
    request=value.get('request_id');reason=value.get('reason')
    if not isinstance(request,str) or not mobile.ID.fullmatch(request): raise ValueError('Stable callback request_id required')
    reason=observers.reason_text(reason)
    if not reason.strip(): raise ValueError('Brief callback reason required')
    fingerprint=hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    key='scheduled-callback:'+hashlib.sha256((owner+'\0'+profile+'\0'+request).encode()).hexdigest()
    with mobile.LOCK:
        with mobile.connect() as conn:
            conn.execute('BEGIN IMMEDIATE')
            _prune_expired(conn,now.timestamp())
            existing=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
            if existing:
                record=json.loads(existing[0])
                if (now-datetime.fromisoformat(record['scheduled_at'])).total_seconds()>=CALLBACK_WINDOW_SECONDS:
                    raise ValueError('Callback request expired')
                if record['fingerprint']!=fingerprint or record['conversation']!=stored: raise ValueError('Callback request_id already used for another request')
            else:
                try: current=mobile.owned(conn,row['id'],owner,profile,True)
                except (KeyError,LookupError,ValueError): raise ValueError('Call ended before callback request was accepted') from None
                if current['conversation']!=stored: raise ValueError('Call ended before callback request was accepted')
                target=target_time(value,now)
                record={'fingerprint':fingerprint,'conversation':stored,'owner':owner,'provider':provider,'profile':profile,'reason':reason,'scheduled_at':target.isoformat(),'request_id':request,'status':'preparing'}
                conn.execute('INSERT INTO meta VALUES(?,?)',(key,json.dumps(record).encode()))
        if record['status']=='scheduled':
            return {k:record[k] for k in ('status','request_id','job_id','scheduled_at')} | {'timezone':timezone_name,'conversation_id':stored}
    # Persist intent before native creation. A crash/timeout can be reconciled by its stable name.
    name='Beacon callback '+key.split(':')[1]
    matches=[j for j in _call_cron_for_profile(profile,'list_jobs',True) if j.get('name')==name]
    if len(matches)>1: raise RuntimeError('Multiple native callback jobs require host inspection')
    if not matches and record['status']=='creating': return {'status':'uncertain','request_id':request,'message':'Native schedule save may have completed. Inspect Tasks; do not resubmit.'}
    if matches:
        job=matches[0]
        if job.get('script')!='beacon-callback-'+key.split(':')[1]+'.py' or job.get('no_agent') is not True: raise RuntimeError('Native callback job changed; inspect Tasks')
    else:
        _,home=_cron_profile_home(profile)
        source="import os\nos.environ['HERMES_HOME']="+repr(str(mobile.home()))+"\nimport importlib.util\nfrom pathlib import Path\nspec=importlib.util.spec_from_file_location('beacon_callbacks',"+repr(str(Path(__file__).resolve()))+")\nmodule=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(module)\nmodule.run("+repr(key)+")\n"
        script=script_file(home,'beacon-callback-'+key.split(':')[1]+'.py',source)
        old=json.dumps(record).encode();record['status']='creating'
        with mobile.LOCK:
            with mobile.connect() as conn:
                claimed=conn.execute('UPDATE meta SET value=? WHERE key=? AND value=?',(json.dumps(record).encode(),key,old)).rowcount
        if not claimed: return {'status':'uncertain','request_id':request,'message':'Another save owns this request. Read its receipt; do not resubmit.'}
        job=_create_cron_job_sync(CronJobCreate(name=name,schedule=record['scheduled_at'],prompt='',deliver='local',script=script,no_agent=True),profile)
    if not isinstance(job,dict) or not job.get('id'): raise RuntimeError('Native callback receipt unavailable')
    record.update(status='scheduled',job_id=job['id'])
    with mobile.LOCK:
        with mobile.connect() as conn:
            conn.execute('BEGIN IMMEDIATE')
            current=json.loads(conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
            if current.get('delivered'): record['delivered']=current['delivered']
            if current.get('status')=='missed': record['status']='missed'
            conn.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(record).encode(),key))
    return {k:record[k] for k in ('status','request_id','job_id','scheduled_at')} | {'timezone':timezone_name,'conversation_id':stored}

def deliver(mobile,native,key,now):
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        _prune_expired(conn,now.timestamp())
        found=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        if not found: raise ValueError('Callback intent unavailable')
        row=json.loads(found[0]);cfg=mobile.settings(row['profile']);target=datetime.fromisoformat(row['scheduled_at'])
        age=(now-target).total_seconds()
        if age>=CALLBACK_WINDOW_SECONDS:
            _missed(mobile,native,conn,key,row,now)
            return {'status':'skipped_expired'}
        if age < -CALLBACK_EARLY_TOLERANCE_SECONDS or row.get('status')=='missed': return {'status':'skipped_expired'}
        if row.get('delivered'): return {'status':'duplicate'}
        if not _authority(cfg,row): raise ValueError('Callback authority changed')
        native.verify(row['owner'],row['provider'],row['profile'],row['conversation'])
        # Exact deterministic notice id makes retry atomic with its delivery marker.
        event=key.split(':')[1][:32]
        if mobile.active_calls(conn,row['owner']):
            if conn.execute('SELECT COUNT(*) FROM deferred').fetchone()[0]>=256: raise RuntimeError('Deferred callback capacity reached')
            conn.execute('INSERT INTO deferred(id,owner,profile,conversation,created,reason) VALUES(?,?,?,?,?,?)',(event,row['owner'],row['profile'],row['conversation'],now.timestamp(),row['reason']))
        else:
            if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification capacity reached')
            conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(event,row['owner'],row['profile'],'incoming_call',row['conversation'],now.timestamp(),now.timestamp()+CALLBACK_WINDOW_SECONDS,row['reason']))
        row['delivered']=event
        conn.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(row).encode(),key))
    return {'status':'pending','event_id':event}

def deferred_allowed(mobile,event,now,owner=None):
    # None denotes another producer; deferred scheduled notices retain their marker.
    with mobile.LOCK,mobile.connect() as conn:
        if isinstance(owner,str) and owner: _prune_expired(conn,now.timestamp())
        found=_delivered_record(conn,event)
    if not found: return False if isinstance(event,str) and _CALLBACK_EVENT.fullmatch(event) else None
    try:
        row=json.loads(found[0]);target=datetime.fromisoformat(row['scheduled_at'])
    except (TypeError,ValueError,KeyError): return False
    return row.get('delivered')==event and 0<=(now-target).total_seconds()<CALLBACK_WINDOW_SECONDS

def missed_reason(mobile,event,now,owner,profile):
    if not isinstance(event,str) or not re.fullmatch(r'[0-9a-f]{32}|[0-9a-f]{64}',event): return None
    with mobile.LOCK,mobile.connect() as conn:
        found=_delivered_record(conn,event)
    if not found: return None
    try:
        row=json.loads(found[0]);target=datetime.fromisoformat(row['scheduled_at'])
    except (TypeError,ValueError,KeyError): return None
    if not isinstance(row,dict) or target.tzinfo is None or target.utcoffset() is None: return None
    if (row.get('delivered')!=event or row.get('owner')!=owner or row.get('profile')!=profile
            or (now-target).total_seconds()<CALLBACK_WINDOW_SECONDS): return None
    try: return observers.reason_text(row.get('reason'))
    except ValueError: return None

def run(key):
    import importlib.util,sys
    for name in ('mobile','native'):
        module_key='beacon_'+name
        if module_key not in sys.modules:
            spec=importlib.util.spec_from_file_location(module_key,Path(__file__).with_name(name+'.py'))
            module=importlib.util.module_from_spec(spec);sys.modules[module_key]=module;spec.loader.exec_module(module)
    with sys.modules['beacon_mobile'].connect() as conn:
        found=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
    if not found: raise ValueError('Callback intent unavailable')
    profile=json.loads(found[0])['profile']
    deliver(sys.modules['beacon_mobile'],sys.modules['beacon_native'],key,
            datetime.now(timezone.utc))
