"""Native one-shot callback scheduling; the scheduler and durable outbox own delivery."""
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo
import hashlib
import json
from pathlib import Path

ZONE = 'Asia/Dubai'

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
    target=target.astimezone(ZoneInfo(ZONE))
    if not now<target<=now+timedelta(days=366): raise ValueError('Callback must be within the next year')
    return target

def script_file(home, name, source):
    home=Path(home).resolve(); folder=home/'scripts'
    folder.mkdir(exist_ok=True)
    if folder.is_symlink() or getattr(folder,'is_junction',lambda:False)() or folder.resolve().parent!=home: raise ValueError('Unsafe scripts directory')
    target=folder/name
    if target.is_symlink() or getattr(target,'is_junction',lambda:False)(): raise ValueError('Unsafe callback script')
    if target.exists() and target.read_text('utf-8')!=source: raise ValueError('Callback script differs; inspect on host')
    if not target.exists(): target.write_text(source,encoding='utf-8')
    return name

def schedule(mobile,native,owner,provider,row,cfg,value,now=None):
    from hermes_cli.web_server_cron import _cron_profile_home,_call_cron_for_profile,_create_cron_job_sync
    from hermes_cli.web_models import CronJobCreate
    now=now or datetime.now(ZoneInfo(ZONE))
    profile=row['profile'];stored=row['conversation']
    if cfg.get('scheduled_callbacks_enabled') is not True: raise ValueError('Scheduled callbacks are disabled on the host')
    if owner!=cfg.get('owner_id') or provider!=cfg.get('owner_provider') or profile not in cfg.get('profiles',[]): raise LookupError('Callback owner/profile unavailable')
    native.verify(owner,provider,profile,stored)
    request=value.get('request_id');reason=value.get('reason')
    if not isinstance(request,str) or not mobile.ID.fullmatch(request): raise ValueError('Stable callback request_id required')
    if not isinstance(reason,str) or not reason.strip() or len(reason)>240: raise ValueError('Brief callback reason required')
    fingerprint=hashlib.sha256(json.dumps(value,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    key='scheduled-callback:'+hashlib.sha256((owner+'\0'+profile+'\0'+request).encode()).hexdigest()
    with mobile.LOCK:
        with mobile.connect() as conn: existing=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        if existing:
            record=json.loads(existing[0])
            if record['fingerprint']!=fingerprint or record['conversation']!=stored: raise ValueError('Callback request_id already used for another request')
        else:
            if row.get('closed'): raise ValueError('Call ended before callback request was accepted')
            target=target_time(value,now)
            record={'fingerprint':fingerprint,'conversation':stored,'owner':owner,'provider':provider,'profile':profile,'reason':reason,'scheduled_at':target.isoformat(),'request_id':request,'status':'preparing'}
            with mobile.connect() as conn:
                conn.execute('INSERT OR IGNORE INTO meta VALUES(?,?)',(key,json.dumps(record).encode()))
                record=json.loads(conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
            if record['fingerprint']!=fingerprint or record['conversation']!=stored: raise ValueError('Callback request_id already used for another request')
        if record['status']=='scheduled':
            return {k:record[k] for k in ('status','request_id','job_id','scheduled_at')} | {'timezone':ZONE,'conversation_id':stored}
        # Persist intent before native creation. A crash/timeout can be reconciled by its stable name.
        name='Cypher callback '+key.split(':')[1]
        matches=[j for j in _call_cron_for_profile(profile,'list_jobs',True) if j.get('name')==name]
        if len(matches)>1: raise RuntimeError('Multiple native callback jobs require host inspection')
        if not matches and record['status']=='creating': return {'status':'uncertain','request_id':request,'message':'Native schedule save may have completed. Inspect Tasks; do not resubmit.'}
        if matches:
            job=matches[0]
            if job.get('script')!='cypher-callback-'+key.split(':')[1]+'.py' or job.get('no_agent') is not True: raise RuntimeError('Native callback job changed; inspect Tasks')
        else:
            _,home=_cron_profile_home(profile)
            source="import os\nos.environ['HERMES_HOME']="+repr(str(mobile.home()))+"\nimport importlib.util\nfrom pathlib import Path\nspec=importlib.util.spec_from_file_location('cypher_callbacks',"+repr(str(Path(__file__).resolve()))+")\nmodule=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(module)\nmodule.run("+repr(key)+")\n"
            script=script_file(home,'cypher-callback-'+key.split(':')[1]+'.py',source)
            old=json.dumps(record).encode();record['status']='creating'
            with mobile.connect() as conn:
                claimed=conn.execute('UPDATE meta SET value=? WHERE key=? AND value=?',(json.dumps(record).encode(),key,old)).rowcount
            if not claimed: return {'status':'uncertain','request_id':request,'message':'Another save owns this request. Read its receipt; do not resubmit.'}
            job=_create_cron_job_sync(CronJobCreate(name=name,schedule=record['scheduled_at'],prompt='',deliver='local',script=script,no_agent=True),profile)
        if not isinstance(job,dict) or not job.get('id'): raise RuntimeError('Native callback receipt unavailable')
        record.update(status='scheduled',job_id=job['id'])
        with mobile.connect() as conn:
            current=json.loads(conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
            if current.get('delivered'): record['delivered']=current['delivered']
            conn.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(record).encode(),key))
        return {k:record[k] for k in ('status','request_id','job_id','scheduled_at')} | {'timezone':ZONE,'conversation_id':stored}

def deliver(mobile,native,key,now):
    cfg=mobile.settings()
    with mobile.LOCK,mobile.connect() as conn:
        found=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
        if not found: raise ValueError('Callback intent unavailable')
        row=json.loads(found[0]);target=datetime.fromisoformat(row['scheduled_at'])
        if row.get('delivered'): return {'status':'duplicate'}
        if cfg.get('scheduled_callbacks_enabled') is not True or cfg.get('owner_id')!=row['owner'] or cfg.get('owner_provider')!=row['provider'] or row['profile'] not in cfg.get('profiles',[]): raise ValueError('Callback authority changed')
        if not 0<=(now-target).total_seconds()<120: return {'status':'skipped_expired'}
        native.verify(row['owner'],row['provider'],row['profile'],row['conversation'])
        # Exact deterministic notice id makes retry atomic with its delivery marker.
        event=key.split(':')[1]
        busy=conn.execute('SELECT 1 FROM calls WHERE owner=? AND closed=0 AND created>?',(row['owner'],now.timestamp()-4*3600)).fetchone()
        if busy:
            if conn.execute('SELECT COUNT(*) FROM deferred').fetchone()[0]>=256: raise RuntimeError('Deferred callback capacity reached')
            conn.execute('INSERT INTO deferred(id,owner,profile,conversation,created,reason) VALUES(?,?,?,?,?,?)',(event,row['owner'],row['profile'],row['conversation'],now.timestamp(),row['reason']))
        else:
            if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification capacity reached')
            conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(event,row['owner'],row['profile'],'incoming_call',row['conversation'],now.timestamp(),now.timestamp()+120,row['reason']))
        row['delivered']=event
        conn.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(row).encode(),key))
    return {'status':'pending','event_id':event}

def deferred_allowed(mobile,event,now):
    # None denotes a different producer; an explicit timed request expires at due+120s.
    key='scheduled-callback:'+event
    with mobile.connect() as conn: found=conn.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()
    if not found: return None
    row=json.loads(found[0]);target=datetime.fromisoformat(row['scheduled_at'])
    return row.get('delivered')==event and 0<=(now-target).total_seconds()<120

def run(key):
    import importlib.util,sys
    for name in ('mobile','native'):
        module_key='cypher_'+name
        if module_key not in sys.modules:
            spec=importlib.util.spec_from_file_location(module_key,Path(__file__).with_name(name+'.py'))
            module=importlib.util.module_from_spec(spec);sys.modules[module_key]=module;spec.loader.exec_module(module)
    deliver(sys.modules['cypher_mobile'],sys.modules['cypher_native'],key,datetime.now(ZoneInfo(ZONE)))
