from test_callbacks import load,env
from datetime import datetime
from zoneinfo import ZoneInfo
from types import SimpleNamespace
import threading
import sys
import pytest

def test_random_slots_fixed_day_window_and_exact_minute(env):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    slots=ch.slots('owner','default','2026-10-16')
    assert 3<=len(slots)<=4 and len(set(slots))==len(slots) and all(600<=v<1320 for v in slots)
    assert slots==ch.slots('owner','default','2026-10-16')
    minute=slots[0];due=datetime(2026,10,16,minute//60,minute%60,tzinfo=ZoneInfo('Asia/Dubai'))
    assert ch.tick(m,n,cfg,'default',due)['state']=='pending'
    assert ch.tick(m,n,cfg,'default',due)['state']=='already_considered'


def test_run_uses_clock_captured_for_target_profile(monkeypatch,tmp_path):
    ch=load('checkups');captured=datetime(2026,10,16,12,tzinfo=ZoneInfo('Asia/Dubai'));seen={}
    mobile=SimpleNamespace(settings=lambda:{},LOCK=threading.RLock())
    monkeypatch.setitem(sys.modules,'cypher_mobile',mobile)
    monkeypatch.setitem(sys.modules,'cypher_native',SimpleNamespace())
    monkeypatch.setattr(ch,'tick',lambda m,n,cfg,profile,now:seen.update(now=now))
    ch.run(tmp_path,'default','Asia/Dubai',captured)
    assert seen['now'] is captured
    with pytest.raises(ValueError,match='Asia/Dubai'):
        ch.run(tmp_path,'default','UTC',datetime(2026,10,16,8,tzinfo=ZoneInfo('UTC')))
