import importlib.util,json,re,sys
from pathlib import Path
from datetime import datetime,timedelta,timezone
import threading
import sqlite3
from zoneinfo import ZoneInfo
from types import SimpleNamespace
from concurrent.futures import ThreadPoolExecutor
import pytest
ROOT=Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location('fixture_'+name,ROOT/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
@pytest.fixture
def env(tmp_path,monkeypatch):
    m=load('mobile');monkeypatch.setattr(m,'home',lambda:tmp_path)
    monkeypatch.setattr(m,'time',SimpleNamespace(time=lambda:NOW.timestamp()))
    cfg={'owner_id':'owner','owner_provider':'basic','profiles':['default'],'scheduled_callbacks_enabled':True}
    monkeypatch.setattr(m,'settings',lambda profile=None:cfg)
    jobs=[]
    def create(model,profile):
        job={**vars(model),'id':'native'+str(len(jobs))};jobs.append(job);return job
    monkeypatch.setitem(sys.modules,'hermes_cli.web_server_cron',SimpleNamespace(_cron_profile_home=lambda p:(p,tmp_path),_call_cron_for_profile=lambda *a:jobs,_create_cron_job_sync=create))
    monkeypatch.setitem(sys.modules,'hermes_cli.web_models',SimpleNamespace(CronJobCreate=lambda **kw:SimpleNamespace(**kw)))
    native=SimpleNamespace(verify=lambda *a: {})
    cb=load('callbacks');proactive=load('proactive')
    monkeypatch.setattr(proactive,'_profile_timezone',lambda profile:'America/Los_Angeles')
    monkeypatch.setattr(proactive,'profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=ZoneInfo('America/Los_Angeles')))
    monkeypatch.setitem(sys.modules,'beacon_proactive',proactive)
    return cb,m,native,cfg,jobs,tmp_path
NOW=datetime(2026,10,16,19,tzinfo=ZoneInfo('UTC'))
ROW={'profile':'default','conversation':'owned','closed':0}
VALUE={'request_id':'stable','reason':'Requested reminder','delay_seconds':60}
def test_schedule_native_one_shot_replay_after_call_end(env):
    cb,m,n,cfg,jobs,tmp=env
    first=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with pytest.raises(ValueError,match='expired'):
        cb.schedule(m,n,'owner','basic',{**ROW,'closed':1},cfg,VALUE,NOW+timedelta(hours=1))
    assert first['scheduled_at']=='2026-10-16T12:01:00-07:00'
    assert first['timezone']=='America/Los_Angeles'
    assert len(jobs)==1 and jobs[0]['no_agent'] is True and jobs[0]['schedule']==first['scheduled_at']
    assert (tmp/'scripts'/jobs[0]['script']).exists()
    with pytest.raises(ValueError): cb.schedule(m,n,'owner','basic',ROW,cfg,{**VALUE,'reason':'different'},NOW)
@pytest.mark.parametrize('value',[{**VALUE,'at':'2026-10-17T10:00:00+04:00'}, {**VALUE,'delay_seconds':True},{'request_id':'r','reason':'x','at':'2026-10-17T10:00:00'}, {**VALUE,'owner':'foreign'},{**VALUE,'delay_seconds':0}])
def test_invalid_request_never_creates(env,value):
    cb,m,n,cfg,jobs,tmp=env
    with pytest.raises(ValueError): cb.schedule(m,n,'owner','basic',ROW,cfg,value,NOW)
    assert jobs==[]
def test_owner_and_native_authority_fail_closed(env):
    cb,m,n,cfg,jobs,tmp=env
    with pytest.raises(LookupError): cb.schedule(m,n,'foreign','basic',ROW,cfg,VALUE,NOW)
    n.verify=lambda *a: (_ for _ in ()).throw(LookupError())
    with pytest.raises(LookupError): cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert jobs==[]


def test_callback_script_is_not_published_when_atomic_replace_fails(env,monkeypatch):
    cb,m,n,cfg,jobs,tmp=env
    target=tmp/'scripts'/'beacon-callback-test.py'
    monkeypatch.setattr(cb,'os',SimpleNamespace(replace=lambda *args:(_ for _ in ()).throw(OSError('fixture replace failure'))),raising=False)

    with pytest.raises(OSError):cb.script_file(tmp,target.name,'callback source')

    assert not target.exists()
    assert list(target.parent.glob('.'+target.name+'.*.tmp'))==[]
def test_deliver_once_and_expired_deferred(env):
    cb,m,n,cfg,jobs,tmp=env;cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c: key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    due=NOW+timedelta(seconds=60)
    delivered=cb.deliver(m,n,key,due)
    assert delivered['status']=='pending'
    assert cb.deliver(m,n,key,due)['status']=='duplicate'
    with m.connect() as c: assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==1
    assert cb.deferred_allowed(m,delivered['event_id'],due) is True
    assert cb.deferred_allowed(m,delivered['event_id'],due+timedelta(seconds=120)) is False
    assert cb.deferred_allowed(m,'other',due) is None

@pytest.mark.parametrize('busy',[False,True])
def test_scheduled_notice_id_is_android_compatible_and_delivery_retry_is_atomic(env,monkeypatch,busy):
    cb,m,n,cfg,jobs,_=env
    scheduled=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)==scheduled
    assert len(jobs)==1
    monkeypatch.setattr(m,'active_calls',lambda conn,owner:busy)
    with m.connect() as c:
        key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
        c.execute("""CREATE TRIGGER fail_callback_marker BEFORE UPDATE ON meta
            WHEN NEW.key LIKE 'scheduled-callback:%' AND json_extract(CAST(NEW.value AS TEXT),'$.delivered') IS NOT NULL
            BEGIN SELECT RAISE(ABORT,'fixture delivery marker failure'); END""")
    due=datetime.fromisoformat(scheduled['scheduled_at'])
    with pytest.raises(sqlite3.IntegrityError,match='fixture delivery marker failure'):
        cb.deliver(m,n,key,due)
    with m.connect() as c:
        assert not json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0]).get('delivered')
        for table in ('notices','deferred','call_log'):
            assert c.execute('SELECT COUNT(*) FROM '+table).fetchone()[0]==0
        c.execute('DROP TRIGGER fail_callback_marker')
    delivered=cb.deliver(m,n,key,due)
    event=delivered['event_id']
    assert delivered['status']=='pending' and re.fullmatch(r'[0-9a-f]{32}',event)
    assert event==key.split(':')[1][:32] and len(key.split(':')[1])==64
    assert cb.deliver(m,n,key,due+timedelta(seconds=1))['status']=='duplicate'
    assert cb.deferred_allowed(m,event,due) is True
    with m.connect() as c:
        assert json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])['delivered']==event
        table='deferred' if busy else 'notices'
        assert [row['id'] for row in c.execute('SELECT id FROM '+table)]==[event]
        assert [row['id'] for row in c.execute('SELECT id FROM call_log')]==([] if busy else [event])

def test_deferred_scheduled_notice_keeps_its_expiry_marker_until_settled(env,monkeypatch):
    cb,m,n,cfg,_,_=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    monkeypatch.setattr(m,'active_calls',lambda conn,owner:True)
    due=NOW+timedelta(seconds=60)
    event=cb.deliver(m,n,key,due)['event_id']
    assert re.fullmatch(r'[0-9a-f]{32}',event)
    expired=due+timedelta(seconds=cb.CALLBACK_MARKER_RETENTION_SECONDS+2)
    cb.prune_expired(m,n,'owner',expired.timestamp())
    with m.connect() as c:
        assert c.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone()
        assert c.execute('SELECT 1 FROM deferred WHERE id=?',(event,)).fetchone()
    assert cb.deferred_allowed(m,event,expired,'owner') is False
    assert cb.missed_reason(m,event,expired,'owner','default')==VALUE['reason']
    assert cb.missed_reason(m,event,expired,'foreign','default') is None
    assert cb.missed_reason(m,event,expired,'owner','foreign') is None
    assert cb.deferred_allowed(m,'f'*32,expired) is None
    with m.connect() as c:
        c.execute('DELETE FROM deferred WHERE id=?',(event,))
        c.execute('INSERT INTO meta(key,value) VALUES(?,?)',('missed-callback:'+event,b'0'))
    cb.prune_expired(m,n,'owner',(expired+timedelta(hours=1)).timestamp())
    with m.connect() as c:
        assert c.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone() is None
        assert c.execute('SELECT 1 FROM meta WHERE key=?',('missed-callback:'+event,)).fetchone() is None

def test_stale_or_changed_authority_does_not_ring(env):
    cb,m,n,cfg,jobs,tmp=env;cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    assert cb.deliver(m,n,key,NOW+timedelta(seconds=180))['status']=='skipped_expired'
    cb.schedule(m,n,'owner','basic',ROW,cfg,{**VALUE,'request_id':'changed-authority'},NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%' AND key!=?",(key,)).fetchone()[0]
    cfg['scheduled_callbacks_enabled']=False
    with pytest.raises(ValueError):cb.deliver(m,n,key,NOW+timedelta(seconds=60))
    with m.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0]==0

def test_expired_callback_replay_is_rejected_before_and_after_marker_pruning(env):
    cb,m,n,cfg,jobs,tmp=env
    first=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    event=key.split(':')[1]
    expiry=datetime.fromisoformat(first['scheduled_at'])+timedelta(seconds=120)
    with m.connect() as c:
        record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
        record['delivered']=event
        c.execute('UPDATE meta SET value=? WHERE key=?',(json.dumps(record).encode(),key))
    assert cb.deliver(m,n,key,expiry)['status']=='skipped_expired'
    assert cb.deferred_allowed(m,event,expiry) is False
    assert cb.missed_reason(m,event,expiry,'owner','default')==VALUE['reason']
    with pytest.raises(ValueError):cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,expiry)
    margin=expiry+timedelta(days=7)
    cb.schedule(m,n,'owner','basic',ROW,cfg,{**VALUE,'request_id':'before-prune'},margin-timedelta(seconds=1))
    with m.connect() as c:assert c.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone()
    cb.schedule(m,n,'owner','basic',ROW,cfg,{**VALUE,'request_id':'after-prune'},margin+timedelta(seconds=1))
    with m.connect() as c:assert c.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone() is None
    assert cb.deferred_allowed(m,event,margin+timedelta(seconds=1)) is False
    with pytest.raises(ValueError):cb.deliver(m,n,key,margin+timedelta(seconds=1))

def test_native_timeout_reconciles_exact_job_without_replay(env,monkeypatch):
    cb,m,n,cfg,jobs,tmp=env;module=sys.modules['hermes_cli.web_server_cron'];original=module._create_cron_job_sync
    def uncertain(model,profile):original(model,profile);raise TimeoutError('save acknowledgement lost')
    module._create_cron_job_sync=uncertain
    with pytest.raises(TimeoutError): cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    result=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert result['status']=='scheduled' and len(jobs)==1
    jobs.clear()
    assert cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)==result
    assert jobs==[]

def test_uncertain_absent_native_save_is_never_replayed(env):
    cb,m,n,cfg,jobs,tmp=env;module=sys.modules['hermes_cli.web_server_cron']
    module._create_cron_job_sync=lambda *a:(_ for _ in ()).throw(TimeoutError())
    with pytest.raises(TimeoutError):cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)['status']=='uncertain'
    assert jobs==[]

def test_schedule_uses_the_profile_timezone_for_callback_time(env):
    cb,m,n,cfg,jobs,tmp=env
    result=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert result['scheduled_at']=='2026-10-16T12:01:00-07:00'
    assert result['timezone']=='America/Los_Angeles'


@pytest.mark.parametrize('configured_timezone',['','Unknown/Timezone'])
def test_schedule_uses_hermes_clock_fallback_for_empty_or_unknown_profile_timezone(env,monkeypatch,configured_timezone):
    cb,m,n,cfg,jobs,tmp=env
    fallback=datetime(2026,10,16,19,tzinfo=timezone.utc).astimezone()
    proactive=load('proactive')
    monkeypatch.setattr(proactive,'profile_now',lambda profile:fallback)
    monkeypatch.setattr(proactive,'_profile_timezone',lambda profile:configured_timezone)
    monkeypatch.setitem(sys.modules,'beacon_proactive',proactive)

    result=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)

    expected=(NOW+timedelta(seconds=60)).astimezone(fallback.tzinfo)
    assert result['status']=='scheduled'
    assert result['scheduled_at']==expected.isoformat()
    assert result['timezone']==fallback.tzname()

def test_deliver_tolerates_subsecond_early_trigger(env):
    cb,m,n,cfg,jobs,tmp=env
    first=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW.replace(microsecond=600000))
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    due=datetime.fromisoformat(first['scheduled_at'])
    assert cb.deliver(m,n,key,due-timedelta(milliseconds=250))['status']=='pending'
    assert cb.deliver(m,n,key,due-timedelta(seconds=2))['status']=='skipped_expired'

def test_schedule_does_not_hold_mobile_lock_during_native_creation(env):
    cb,m,n,cfg,jobs,tmp=env;module=sys.modules['hermes_cli.web_server_cron']
    original=module._create_cron_job_sync;entered=threading.Event();release=threading.Event();outcome={}
    def create(model,profile):
        entered.set()
        if not release.wait(3): raise TimeoutError('fixture release timeout')
        return original(model,profile)
    module._create_cron_job_sync=create
    def schedule():
        try:outcome['result']=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
        except Exception as exc:outcome['error']=exc
    worker=threading.Thread(target=schedule);worker.start();acquired=False;second=None
    try:
        assert entered.wait(1)
        acquired=m.LOCK.acquire(timeout=1)
        if acquired:
            m.LOCK.release()
            second=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    finally:
        release.set();worker.join(3)
    assert acquired
    assert second['status']=='uncertain'
    assert not worker.is_alive() and 'error' not in outcome
    assert outcome['result']['status']=='scheduled' and len(jobs)==1

def test_prune_expired_uses_age_for_foreign_markers_and_keeps_unreadable_rows(env):
    cb,m,n,cfg,jobs,tmp=env;now=NOW.timestamp()
    expired=(NOW-timedelta(seconds=cb.CALLBACK_MARKER_RETENTION_SECONDS+5)).isoformat()
    recent=(NOW-timedelta(seconds=cb.CALLBACK_MARKER_RETENTION_SECONDS-5)).isoformat()
    rows={
        'scheduled-callback:'+('a'*64):json.dumps({'owner':'foreign','scheduled_at':expired}).encode(),
        'scheduled-callback:'+('b'*64):json.dumps({'scheduled_at':expired}).encode(),
        'scheduled-callback:'+('c'*64):json.dumps({'owner':'foreign','scheduled_at':recent}).encode(),
        'scheduled-callback:'+('d'*64):b'not-json',
    }
    with m.connect() as c:c.executemany('INSERT INTO meta(key,value) VALUES(?,?)',rows.items())
    cb.prune_expired(m,n,'owner',now)
    with m.connect() as c:
        remaining={key for (key,) in c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchall()}
    assert set(rows)-remaining==set(list(rows)[:2])
    assert set(list(rows)[2:])<=remaining


@pytest.mark.parametrize('profile',['default','work'])
def test_late_script_records_one_owned_missed_callback_without_ringing(env,profile):
    cb,m,n,cfg,_,_=env
    cfg['profiles']=['default','work']
    cb.schedule(m,n,'owner','basic',{**ROW,'profile':profile},cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    late=NOW+timedelta(seconds=180)
    assert cb.deliver(m,n,key,late)['status']=='skipped_expired'
    assert cb.deliver(m,n,key,late)['status']=='skipped_expired'
    with m.connect() as c:
        rows=[dict(row) for row in c.execute('SELECT * FROM notices')]
        record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
    assert len(rows)==1 and rows[0]['kind']=='task_result'
    assert (rows[0]['owner'],rows[0]['profile'],rows[0]['conversation'])==('owner',profile,'owned')
    assert rows[0]['reason']=='Missed scheduled callback: Requested reminder'
    assert record['status']=='missed' and not record.get('delivered')
    assert cb.deliver(m,n,key,NOW+timedelta(seconds=60))['status']=='skipped_expired'


def test_a_late_callback_waits_while_the_store_cannot_be_opened(env,monkeypatch):
    cb,m,n,cfg,_,_=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    def unavailable(*args,**kwargs): raise RuntimeError('Native conversation store unavailable')
    monkeypatch.setattr(n,'verify',unavailable)
    assert cb.deliver(m,n,key,NOW+timedelta(seconds=180))['status']=='skipped_expired'
    with m.connect() as c:
        record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
        assert record['status']!='missed' and c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==0


@pytest.mark.parametrize('elapsed,status,kind',[(60,'scheduled','incoming_call'),(180,'missed','task_result')])
def test_native_save_acknowledgment_preserves_an_already_settled_callback(env,elapsed,status,kind):
    cb,m,n,cfg,_,_=env
    cron=sys.modules['hermes_cli.web_server_cron'];create=cron._create_cron_job_sync
    def late_save(model,profile):
        with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
        assert cb.deliver(m,n,key,NOW+timedelta(seconds=elapsed))['status']==('pending' if elapsed==60 else 'skipped_expired')
        return create(model,profile)
    cron._create_cron_job_sync=late_save
    result=cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    assert result['status']==status
    with m.connect() as c:
        assert [row['kind'] for row in c.execute('SELECT kind FROM notices')]==[kind]
        record=json.loads(c.execute("SELECT value FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0])
    assert bool(record.get('delivered'))==(elapsed==60)


@pytest.mark.parametrize('elapsed,kind',[(60,'incoming_call'),(180,'task_result')])
def test_native_save_acknowledgment_excludes_a_concurrent_cron_writer(env,monkeypatch,elapsed,kind):
    cb,m,n,cfg,jobs,home=env
    other=load('mobile')
    monkeypatch.setattr(other,'home',lambda:home)
    monkeypatch.setattr(other,'time',SimpleNamespace(time=lambda:NOW.timestamp()))
    monkeypatch.setattr(other,'settings',lambda profile=None:cfg)
    with m.connect():pass
    with other.connect():pass
    assert other.LOCK is not m.LOCK
    cron=sys.modules['hermes_cli.web_server_cron'];create=cron._create_cron_job_sync
    saved=threading.Event();updating=threading.Event();release=threading.Event();timed_out=threading.Event()
    def save(model,profile):
        job=create(model,profile);saved.set();return job
    cron._create_cron_job_sync=save
    def trace(statement):
        if saved.is_set() and not updating.is_set() and statement.startswith('UPDATE meta SET value='):
            # SQLite tracing pauses the actual acknowledgment after its read, before its update.
            updating.set()
            if not release.wait(5):timed_out.set()
    connect=m.connect;other_connect=other.connect
    def receipt_connection():
        conn=connect();conn.set_trace_callback(trace);return conn
    def cron_connection():
        conn=other_connect();conn.execute('PRAGMA busy_timeout=0');return conn
    monkeypatch.setattr(m,'connect',receipt_connection)
    monkeypatch.setattr(other,'connect',cron_connection)
    blocked=None;concurrent=None
    with ThreadPoolExecutor(max_workers=1) as pool:
        receipt=pool.submit(cb.schedule,m,n,'owner','basic',ROW,cfg,VALUE,NOW)
        try:
            assert updating.wait(5)
            with other.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
            try:concurrent=cb.deliver(other,n,key,NOW+timedelta(seconds=elapsed))
            except sqlite3.OperationalError as exc:blocked=exc
        finally:release.set()
        result=receipt.result(timeout=5)
    assert not timed_out.is_set()
    with other.connect() as c:record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
    assert blocked is not None and blocked.sqlite_errorcode==sqlite3.SQLITE_BUSY,(concurrent,record)
    assert result['status']=='scheduled' and result['job_id']==jobs[0]['id'] and len(jobs)==1
    assert cb.deliver(other,n,key,NOW+timedelta(seconds=elapsed))['status']==('pending' if elapsed==60 else 'skipped_expired')
    assert cb.deliver(other,n,key,NOW+timedelta(seconds=elapsed))['status']==('duplicate' if elapsed==60 else 'skipped_expired')
    assert cb.prune_expired(m,n,'owner',(NOW+timedelta(seconds=180)).timestamp())==0
    with other.connect() as c:
        record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
        notices=[dict(row) for row in c.execute('SELECT * FROM notices')]
    assert record['status']==('scheduled' if elapsed==60 else 'missed')
    assert record.get('delivered')==(key.split(':')[1][:32] if elapsed==60 else None)
    assert [(row['kind'],row['owner'],row['profile'],row['conversation']) for row in notices]==[(kind,'owner','default','owned')]


def test_poll_cleanup_recovers_skipped_native_oneshot_inside_the_prune_throttle(env):
    cb,m,n,cfg,_,_=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    cb.prune_expired(m,n,'owner',NOW.timestamp())
    late=NOW+timedelta(seconds=180)
    cb.prune_expired(m,n,'foreign',late.timestamp())
    with m.connect() as c:assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==0
    cb.prune_expired(m,n,'owner',late.timestamp())
    cb.prune_expired(m,n,'owner',late.timestamp())
    with m.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0]==0
        assert c.execute("SELECT COUNT(*) FROM notices WHERE kind='task_result'").fetchone()[0]==1


@pytest.mark.parametrize('changed',['owner','provider','profile','disabled','conversation'])
def test_overdue_callback_cleanup_preserves_authority(env,changed):
    cb,m,n,cfg,_,_=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    if changed=='owner':cfg['owner_id']='foreign'
    if changed=='provider':cfg['owner_provider']='foreign'
    if changed=='profile':cfg['profiles']=['other']
    if changed=='disabled':cfg['scheduled_callbacks_enabled']=False
    if changed=='conversation':n.verify=lambda *args:(_ for _ in ()).throw(LookupError('conversation no longer owned'))
    late=NOW+timedelta(seconds=180)
    cb.prune_expired(m,n,'owner',late.timestamp())
    assert cb.deliver(m,n,key,late)['status']=='skipped_expired'
    with m.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==0
        record=json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])
    assert record['status']=='scheduled' and not record.get('delivered')


def test_concurrent_late_delivery_and_poll_cleanup_share_one_terminal_notice(env,monkeypatch):
    cb,m,n,cfg,_,home=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    other=load('mobile')
    monkeypatch.setattr(other,'home',lambda:home)
    monkeypatch.setattr(other,'time',SimpleNamespace(time=lambda:NOW.timestamp()))
    monkeypatch.setattr(other,'settings',lambda profile=None:cfg)
    with other.connect():pass
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    late=NOW+timedelta(seconds=180);start=threading.Barrier(4)
    def settle(index):
        start.wait(timeout=3)
        mobile=m if index%2 else other
        if index<2:return cb.deliver(mobile,n,key,late)
        return cb.prune_expired(mobile,n,'owner',late.timestamp())
    with ThreadPoolExecutor(max_workers=4) as pool:
        list(pool.map(settle,range(4)))
    with m.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==1
        assert c.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'missed-callback:%'").fetchone()[0]==1
        assert json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])['status']=='missed'


def test_missed_notice_and_terminal_marker_roll_back_together(env,monkeypatch):
    cb,m,n,cfg,_,_=env
    cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    notice=cb.observers._notice
    def fail_after_notice(*args,**kwargs):
        notice(*args,**kwargs)
        raise RuntimeError('fixture transaction failure after notice insertion')
    monkeypatch.setattr(cb.observers,'_notice',fail_after_notice)
    late=NOW+timedelta(seconds=180)
    with pytest.raises(RuntimeError,match='fixture transaction failure'):
        cb.deliver(m,n,key,late)
    with m.connect() as c:
        assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==0
        assert c.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'missed-callback:%'").fetchone()[0]==0
        assert json.loads(c.execute('SELECT value FROM meta WHERE key=?',(key,)).fetchone()[0])['status']=='scheduled'
    monkeypatch.setattr(cb.observers,'_notice',notice)
    assert cb.deliver(m,n,key,late)['status']=='skipped_expired'
    with m.connect() as c:assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==1


def test_poll_callback_recovery_is_bounded_and_retains_marker_lifetime(env):
    cb,m,n,cfg,_,_=env
    target=NOW+timedelta(seconds=60)
    records=[('scheduled-callback:'+f'{index:064x}',json.dumps({
        'owner':'owner','provider':'basic','profile':'default','conversation':'owned',
        'scheduled_at':target.isoformat(),'reason':'Requested reminder','status':'scheduled',
    }).encode()) for index in range(cb.CALLBACK_MARKER_PRUNE_BATCH+1)]
    with m.connect() as c:c.executemany('INSERT INTO meta(key,value) VALUES(?,?)',records)
    late=target+timedelta(seconds=120)
    cb.prune_expired(m,n,'owner',late.timestamp())
    with m.connect() as c:assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==cb.CALLBACK_MARKER_PRUNE_BATCH
    cb.prune_expired(m,n,'owner',late.timestamp())
    with m.connect() as c:assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==len(records)
    expiry=target+timedelta(seconds=cb.CALLBACK_MARKER_RETENTION_SECONDS+2)
    cb.prune_expired(m,n,'owner',expiry.timestamp())
    with m.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]==1
        assert c.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'missed-callback:%'").fetchone()[0]==1
