import asyncio
import sys
import threading
from types import SimpleNamespace

import pytest
from test_mobile import harness


@pytest.mark.asyncio
async def test_watcher_pushes_later_profiles_after_one_profile_raises(harness,monkeypatch):
    api,_,cfg,_,_=harness
    cfg['profiles']=['first','second']
    pushed=[];second_push=threading.Event()
    monkeypatch.setattr(api.mobile,'settings',lambda profile=None:cfg)
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
    for name in ('watch','push'):
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
    for name in ('watch','push'):
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
