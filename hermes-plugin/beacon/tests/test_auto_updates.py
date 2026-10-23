import asyncio
import importlib.util
import sys
import threading
import uuid
from types import SimpleNamespace

import pytest
from test_mobile import harness


def _release(plugin_version='1.0.0', available=False):
    return {
        'version_code': 100,
        'version_name': '1.0.0',
        'plugin_version': plugin_version,
        'available': available,
    }


def _auto(api, monkeypatch, *, plugin_version='1.0.0', busy=False):
    checked=[]
    updated=[]
    installed=['1.0.0']
    def check(cfg,before_apk=None):
        checked.append(True)
        release=_release(plugin_version)
        if before_apk: before_apk(release['version_name'],release['plugin_version'])
        return release
    monkeypatch.setattr(api, '_check_release_and_announce', check)
    monkeypatch.setattr(api, '_installed_plugin_version', lambda: installed[0])
    monkeypatch.setattr(api, '_hermes_busy', lambda owner: busy)
    def update():
        updated.append('beacon');installed[0]=plugin_version
        # Hermes' git-install shape (plugins_cmd_update.py): no restart_required key.
        return {'ok': True, 'name': 'beacon', 'output': 'Updated beacon.', 'unchanged': False}
    monkeypatch.setattr(api, '_update_beacon_plugin', update)
    return checked, updated


def _restarted_api(api):
    name='beacon_auto_update_restart_'+uuid.uuid4().hex
    spec=importlib.util.spec_from_file_location(name,api.ROOT/'dashboard'/'plugin_api.py')
    restarted=importlib.util.module_from_spec(spec)
    spec.loader.exec_module(restarted)
    return restarted


def test_plugin_update_setting_defaults_on_and_is_owner_scoped(harness):
    _,client,_,_,_=harness
    path='/api/plugins/beacon/mobile/update-settings'

    assert client.get(path).json()['enabled'] is True
    assert client.put(path,json={'enabled':False}).json()['enabled'] is False
    assert client.get(path).json()['enabled'] is False
    assert client.put(path,json={'enabled':0}).status_code==400
    client.headers['x-fixture-owner']='foreign'
    assert client.get(path).status_code==403
    assert client.put(path,json={'enabled':True}).status_code==403


def test_auto_check_is_six_hour_throttled_across_module_restart(harness,monkeypatch):
    api,_,cfg,_,_=harness
    checked,_=_auto(api,monkeypatch)
    now=2_000_000

    api.auto_update_check(cfg,now=now)
    restarted=_restarted_api(api)
    monkeypatch.setattr(restarted,'_check_release_and_announce',lambda cfg,before_apk=None: checked.append(True) or _release())
    monkeypatch.setattr(restarted,'_installed_plugin_version',lambda:'1.0.0')
    monkeypatch.setattr(restarted,'_hermes_busy',lambda owner:False)
    assert restarted.auto_update_check(cfg,now=now+6*60*60-1)['result']=='throttled'
    assert len(checked)==1

    restarted.auto_update_check(cfg,now=now+6*60*60)
    assert len(checked)==2


def test_newer_plugin_uses_hermes_native_update_path_when_enabled(harness,monkeypatch):
    api,client,cfg,_,_=harness
    checked,updated=_auto(api,monkeypatch,plugin_version='1.2.0')

    result=api.auto_update_check(cfg,now=2_000_000)

    assert len(checked)==1 and updated==['beacon']
    # This process still runs 1.0.0 code, so the update finishes only after a restart.
    assert result['result']=='restart_required'
    assert result['message']=='Restart Hermes to finish the plugin update.'
    assert client.get('/api/plugins/beacon/mobile/update-settings').json()['plugin_version']=='1.2.0'


def test_plugin_update_is_complete_when_running_code_matches_installed(harness,monkeypatch):
    api,_,cfg,_,_=harness
    _,updated=_auto(api,monkeypatch,plugin_version='1.2.0')
    monkeypatch.setattr(api,'_RUNNING_PLUGIN_VERSION','1.2.0')

    result=api.auto_update_check(cfg,now=2_000_000)

    assert updated==['beacon'] and result['result']=='plugin_updated'


def test_plugin_update_runs_inside_hermes_profile_secret_scope(harness,monkeypatch):
    api,_,_,_,_=harness
    scope=[]
    class Scope:
        def __init__(self,profile): self.profile=profile
        def __enter__(self): scope.append(self.profile)
        def __exit__(self,*_): scope.pop()
    def update(name):
        # Mirrors agent/secret_scope.py: a multi-profile Hermes refuses unscoped secret reads.
        if scope!=[None]: raise RuntimeError('UnscopedSecretError')
        return {'ok':True,'name':name,'output':'Already up to date.','unchanged':True}
    monkeypatch.setitem(sys.modules,'hermes_cli.web_server_profiles',SimpleNamespace(_config_profile_scope=Scope))
    monkeypatch.setitem(sys.modules,'hermes_cli.plugins_cmd',SimpleNamespace(dashboard_update_user_plugin=update))

    assert api._update_beacon_plugin()['ok'] is True
    assert scope==[]


def test_disabled_plugin_auto_update_does_not_call_hermes_updater(harness,monkeypatch):
    api,client,cfg,_,_=harness
    client.put('/api/plugins/beacon/mobile/update-settings',json={'enabled':False})
    checked,updated=_auto(api,monkeypatch,plugin_version='1.2.0')

    api.auto_update_check(cfg,now=2_000_000)

    assert len(checked)==1 and updated==[]


def test_active_hermes_work_defers_reload_and_records_restart_hint(harness,monkeypatch):
    api,client,cfg,_,_=harness
    checked,updated=_auto(api,monkeypatch,plugin_version='1.2.0',busy=True)

    result=api.auto_update_check(cfg,now=2_000_000)

    assert len(checked)==1 and updated==[]
    assert result['result']=='waiting_for_idle'
    assert result['message']=='Plugin update waits until Hermes is idle.'
    assert client.get('/api/plugins/beacon/mobile/update-settings').json()['message']==result['message']


def test_deferred_plugin_update_retries_when_hermes_becomes_idle_without_another_release_check(harness,monkeypatch):
    api,_,cfg,_,_=harness
    checked,updated=_auto(api,monkeypatch,plugin_version='1.2.0',busy=True)
    first=api.auto_update_check(cfg,now=2_000_000)
    assert first['result']=='waiting_for_idle' and updated==[]
    monkeypatch.setattr(api,'_hermes_busy',lambda owner:False)

    result=api.auto_update_check(cfg,now=2_000_060)

    assert result['result']=='restart_required'
    assert len(checked)==1 and updated==['beacon']


def test_auto_checks_serialize_one_in_flight_release_check(harness,monkeypatch):
    api,_,cfg,_,_=harness
    entered=threading.Event();release=threading.Event();calls=[]
    def slow_check(cfg,before_apk=None):
        calls.append(True);entered.set();release.wait(2);return _release()
    monkeypatch.setattr(api,'_check_release_and_announce',slow_check)
    monkeypatch.setattr(api,'_installed_plugin_version',lambda:'1.0.0')
    monkeypatch.setattr(api,'_hermes_busy',lambda owner:False)
    first=threading.Thread(target=lambda:api.auto_update_check(cfg,now=2_000_000))
    first.start()
    assert entered.wait(2)
    second=api.auto_update_check(cfg,now=2_000_000)
    release.set();first.join(2)

    assert not first.is_alive()
    assert len(calls)==1
    assert second['result'] in {'in_progress','throttled'}


def test_failed_release_check_is_recorded_without_stopping_watcher(harness,monkeypatch):
    api,client,cfg,_,_=harness
    def fail(_cfg,before_apk=None): raise OSError('offline')
    monkeypatch.setattr(api,'_check_release_and_announce',fail)

    result=api.auto_update_check(cfg,now=2_000_000)

    assert result['result']=='error'
    saved=client.get('/api/plugins/beacon/mobile/update-settings').json()
    assert saved['checked_at']==2_000_000 and saved['result']=='error'


def test_idle_guard_detects_active_hermes_work_and_phone_calls(harness,monkeypatch):
    api,_,_,_,_=harness
    status={'active_agents':1,'active_work':['chat'],'gateway_state':'running'}
    pid=[123];ledger=['']
    monkeypatch.setitem(sys.modules,'gateway.status',SimpleNamespace(
        get_running_pid=lambda:pid[0],read_runtime_status=lambda:status))
    monkeypatch.setitem(sys.modules,'hermes_cli.web_server_idle_exit',SimpleNamespace(busy_ledger=lambda:ledger[0]))

    assert api._hermes_busy('owner')
    status.update(active_agents=0,active_work=[],gateway_state='running')
    assert not api._hermes_busy('owner')
    # A stopped gateway leaves a stale state file; it must not block the update forever.
    pid[0]=None;status.update(active_agents=2,gateway_state='stopped')
    assert not api._hermes_busy('owner')
    for busy in ('session:dashboard-chat',None):
        ledger[0]=busy
        assert api._hermes_busy('owner')
    ledger[0]=''
    api.mobile.reserve('owner','default','active-call-session')
    assert api._hermes_busy('owner')


@pytest.mark.asyncio
async def test_watcher_keeps_call_and_notice_delivery_moving_during_auto_check(harness,monkeypatch):
    api,_,_,_,_=harness
    entered=threading.Event();release=threading.Event();delivered=[]
    def slow_check(_cfg): entered.set();release.wait(2)
    monkeypatch.setattr(api,'auto_update_check',slow_check)
    monkeypatch.setattr(api,'announce_release',lambda _cfg:None)
    monkeypatch.setattr(api,'_warm_profile',lambda *_args:None)
    monkeypatch.setattr(api,'watch',lambda *_args:delivered.append('watch'))
    monkeypatch.setattr(api,'push',lambda *_args:delivered.append('push'))
    api._jobs_warming.clear()
    await api.start_watcher()
    try:
        assert await asyncio.to_thread(entered.wait,1)
        await asyncio.sleep(0.05)
        assert 'watch' in delivered and 'push' in delivered
    finally:
        release.set()
        await api.stop_watcher()


@pytest.mark.asyncio
async def test_watcher_pushes_later_profiles_after_one_profile_raises(harness,monkeypatch):
    api,_,cfg,_,_=harness
    cfg['profiles']=['first','second']
    pushed=[];second_push=threading.Event()
    monkeypatch.setattr(api.mobile,'settings',lambda profile=None:cfg)
    monkeypatch.setattr(api,'auto_update_check',lambda _cfg:None)
    monkeypatch.setattr(api,'announce_release',lambda _cfg:None)
    monkeypatch.setattr(api,'_warm_profile',lambda *_args:None)
    def watch(owner,provider,profile):
        if profile=='first': raise RuntimeError('first profile failed')
    def push(owner,profile):
        pushed.append(profile)
        if profile=='second': second_push.set()
    monkeypatch.setattr(api,'watch',watch)
    monkeypatch.setattr(api,'push',push)
    api._jobs_warming.clear()
    await api.start_watcher()
    try:
        assert await asyncio.to_thread(second_push.wait,1)
    finally:
        await api.stop_watcher()

    assert pushed==['first','second']


def test_failed_proactive_rebuild_waits_ten_minutes_before_retry(harness,monkeypatch):
    api=harness[0]
    profile=('owner','default')
    api._jobs_retry_after={}
    api._jobs_warming.add(profile)
    attempts=[]
    def fail_rebuild(*args):
        attempts.append(True)
        raise RuntimeError('rebuild failed')
    monkeypatch.setattr(api.proactive,'upgrade_jobs',fail_rebuild)

    api._warm_profile(*profile)
    api._warm_profile(*profile)

    assert profile not in api._jobs_warming
    assert len(attempts)==1
    assert api._jobs_retry_after[profile]-api.time.monotonic()>590


@pytest.mark.asyncio
async def test_live_watcher_resumes_jobs_after_native_disable_reenable(harness,monkeypatch):
    from test_mobile import load_private
    from test_proactive import _fake_cron
    api,_,cfg,home,_=harness
    jobs,_=_fake_cron(api,monkeypatch,home)
    cron=sys.modules['hermes_cli.web_server_cron'];mutate=cron._mutate_cron_for_profile
    def change(profile,action,job_id,changes=None):
        if action=='pause_job':
            next(job for job in jobs if job['id']==job_id)['enabled']=False
            return True
        return mutate(profile,action,job_id,changes)
    monkeypatch.setattr(cron,'_mutate_cron_for_profile',change)
    monkeypatch.setitem(sys.modules,'cron.notepad',SimpleNamespace(get_note=lambda *args:None,set_note=lambda *args:None))
    for name in ('enabled','setup_complete'):
        api.proactive._meta_set(api.mobile,api.proactive._key('owner','default',name),'1')
    plugin=load_private('__init__')
    ticks=asyncio.Queue();steps=asyncio.Queue();warmed=threading.Event()
    async def tick(_delay):
        ticks.put_nowait(None)
        await steps.get()
    monkeypatch.setattr(api,'asyncio',SimpleNamespace(create_task=asyncio.create_task,to_thread=asyncio.to_thread,
        sleep=tick,CancelledError=asyncio.CancelledError))
    for name in ('auto_update_check','announce_release','watch','push'):
        monkeypatch.setattr(api,name,lambda *args:None)
    warm=api._warm_profile
    def warm_profile(*args):
        warm(*args)
        warmed.set()
    monkeypatch.setattr(api,'_warm_profile',warm_profile)
    await api.start_watcher()
    try:
        await asyncio.wait_for(ticks.get(),2)
        assert await asyncio.to_thread(warmed.wait,2)
        assert len(jobs)==2 and all(job['enabled'] for job in jobs)
        saved=dict(cfg);cfg.clear()
        plugin.pause_proactive_jobs()
        assert all(not job['enabled'] for job in jobs)
        assert api.proactive._meta_get(api.mobile,api.proactive._key('owner','default','jobs-version')) is None
        steps.put_nowait(None)
        await asyncio.wait_for(ticks.get(),2)
        assert all(not job['enabled'] for job in jobs)
        warmed.clear();cfg.update(saved)
        steps.put_nowait(None)
        await asyncio.wait_for(ticks.get(),2)
        assert await asyncio.to_thread(warmed.wait,2)
        assert len(jobs)==2 and all(job['enabled'] for job in jobs)
    finally:
        await api.stop_watcher()


@pytest.mark.asyncio
async def test_live_watcher_keeps_one_warm_in_flight_while_version_is_invalid(harness,monkeypatch):
    api=harness[0]
    ticks=asyncio.Queue();steps=asyncio.Queue();entered=threading.Event();release=threading.Event();finished=threading.Event()
    attempts=[]
    def upgrade(*args):
        attempts.append(True);entered.set()
        try:
            if not release.wait(3): raise TimeoutError('fixture warm release timeout')
        finally:
            finished.set()
    async def tick(_delay):
        ticks.put_nowait(None)
        await steps.get()
    monkeypatch.setattr(api,'asyncio',SimpleNamespace(create_task=asyncio.create_task,to_thread=asyncio.to_thread,
        sleep=tick,CancelledError=asyncio.CancelledError))
    monkeypatch.setattr(api.proactive,'upgrade_jobs',upgrade)
    for name in ('auto_update_check','announce_release','watch','push'):
        monkeypatch.setattr(api,name,lambda *args:None)
    await api.start_watcher()
    try:
        await asyncio.wait_for(ticks.get(),2)
        assert await asyncio.to_thread(entered.wait,1)
        for _ in range(2):
            steps.put_nowait(None)
            await asyncio.wait_for(ticks.get(),2)
        assert attempts==[True]
    finally:
        release.set()
        await asyncio.to_thread(finished.wait,2)
        await api.stop_watcher()


def test_restart_hint_survives_a_later_up_to_date_check(harness,monkeypatch):
    api,_,cfg,_,_=harness
    checked,updated=_auto(api,monkeypatch,plugin_version='1.0.0')
    # 1.1.0 is installed on disk but this process still runs 1.0.0, and the release page has nothing newer.
    monkeypatch.setattr(api,'_installed_plugin_version',lambda:'1.2.0')
    monkeypatch.setattr(api,'_RUNNING_PLUGIN_VERSION','1.0.0')

    result=api.auto_update_check(cfg,now=2_000_000)

    assert updated==[] and result['result']=='restart_required'
    assert result['message']=='Restart Hermes to finish the plugin update.'


def test_restart_hint_clears_when_process_runs_the_installed_version(harness,monkeypatch):
    api,_,cfg,_,_=harness
    owner=cfg['owner_id']
    api.mobile.record_auto_update_status(owner,2_000_000,'restart_required','1.0.0','1.2.0','Restart Hermes to finish the plugin update.')
    monkeypatch.setattr(api,'_RUNNING_PLUGIN_VERSION','1.2.0')
    monkeypatch.setattr(api,'_installed_plugin_version',lambda:'1.2.0')
    monkeypatch.setattr(api,'_check_release_and_announce',lambda cfg,before_apk=None:pytest.fail('throttled check should not fetch releases'))

    result=api.auto_update_check(cfg,now=2_000_001)

    assert result['result']=='plugin_updated'
    assert result['message']=='Beacon plugin updated.'
