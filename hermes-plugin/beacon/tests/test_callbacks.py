import importlib.util,json,sys
from pathlib import Path
from datetime import datetime,timedelta,timezone
import threading
from zoneinfo import ZoneInfo
from types import SimpleNamespace
import pytest
ROOT=Path(__file__).resolve().parents[1]
def load(name):
    spec=importlib.util.spec_from_file_location('fixture_'+name,ROOT/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
@pytest.fixture
def env(tmp_path,monkeypatch):
    m=load('mobile');monkeypatch.setattr(m,'home',lambda:tmp_path)
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
    assert cb.deliver(m,n,key,due)['status']=='pending'
    assert cb.deliver(m,n,key,due)['status']=='duplicate'
    with m.connect() as c: assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==1
    assert cb.deferred_allowed(m,key.split(':')[1],due) is True
    assert cb.deferred_allowed(m,key.split(':')[1],due+timedelta(seconds=120)) is False
    assert cb.deferred_allowed(m,'other',due) is None
def test_stale_or_changed_authority_does_not_ring(env):
    cb,m,n,cfg,jobs,tmp=env;cb.schedule(m,n,'owner','basic',ROW,cfg,VALUE,NOW)
    with m.connect() as c:key=c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchone()[0]
    assert cb.deliver(m,n,key,NOW+timedelta(seconds=180))['status']=='skipped_expired'
    cfg['scheduled_callbacks_enabled']=False
    with pytest.raises(ValueError):cb.deliver(m,n,key,NOW+timedelta(seconds=60))
    with m.connect() as c:assert c.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==0

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
    cb.prune_expired(m,'owner',now)
    with m.connect() as c:
        remaining={key for (key,) in c.execute("SELECT key FROM meta WHERE key LIKE 'scheduled-callback:%'").fetchall()}
    assert set(rows)-remaining==set(list(rows)[:2])
    assert set(list(rows)[2:])<=remaining
