from test_callbacks import load,env
from datetime import datetime
from zoneinfo import ZoneInfo
from types import SimpleNamespace
import threading
import sys
import pytest

def test_random_slots_fixed_day_window_and_exact_minute(env,monkeypatch):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    monkeypatch.setattr(ch,'_profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=ZoneInfo('America/Los_Angeles')),raising=False)
    slots=ch.slots('owner','default','2026-10-16')
    assert 3<=len(slots)<=4 and len(set(slots))==len(slots) and all(600<=v<1320 for v in slots)
    assert slots==ch.slots('owner','default','2026-10-16')
    minute=slots[0];due=datetime(2026,10,16,minute//60,minute%60,tzinfo=ZoneInfo('America/Los_Angeles'))
    assert ch.tick(m,n,cfg,'default',due)['state']=='pending'
    assert ch.tick(m,n,cfg,'default',due)['state']=='already_considered'


def test_run_uses_clock_captured_for_target_profile_timezone(monkeypatch,tmp_path):
    ch=load('checkups');captured=datetime(2026,10,16,12,tzinfo=ZoneInfo('America/Los_Angeles'));seen={}
    mobile=SimpleNamespace(settings=lambda profile=None:{'profile':profile},LOCK=threading.RLock())
    monkeypatch.setitem(sys.modules,'cypher_mobile',mobile)
    monkeypatch.setitem(sys.modules,'cypher_native',SimpleNamespace())
    monkeypatch.setattr(ch,'_profile_timezone',lambda profile:pytest.fail('run must defer the profile-clock check'),raising=False)
    monkeypatch.setattr(ch,'tick',lambda m,n,cfg,profile,now:seen.update(cfg=cfg,now=now))
    ch.run(tmp_path,'default','Unknown/Timezone',captured)
    assert seen['now'] is captured and seen['cfg']=={'profile':'default'}


@pytest.mark.parametrize('configured_timezone',['','Unknown/Timezone'])
def test_checkup_tick_uses_hermes_clock_fallback_for_empty_or_unknown_profile_timezone(env,monkeypatch,configured_timezone):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    now=datetime(2026,10,16,12,tzinfo=ZoneInfo('America/Los_Angeles'))
    monkeypatch.setattr(ch,'slots',lambda *args:[now.hour*60+now.minute])
    proactive=sys.modules['cypher_proactive']
    monkeypatch.setattr(proactive,'profile_now',lambda profile:now)
    monkeypatch.setattr(proactive,'_profile_timezone',lambda profile:configured_timezone)

    result=ch.tick(m,n,cfg,'default',now)

    assert result['state']=='pending'


def test_checkup_tick_skips_profile_clock_import_when_no_slot_is_due(env,monkeypatch):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    now=datetime(2026,10,16,11,tzinfo=ZoneInfo('America/Los_Angeles'))
    monkeypatch.setattr(ch,'slots',lambda *args:[600])
    monkeypatch.setattr(ch,'_profile_timezone',lambda profile:pytest.fail('non-slot tick must not load profile clock'),raising=False)
    monkeypatch.setattr(ch,'_profile_now',lambda profile:pytest.fail('non-slot tick must not load profile clock'),raising=False)

    assert ch.tick(m,n,cfg,'default',now)=={'state':'no_opportunity'}
