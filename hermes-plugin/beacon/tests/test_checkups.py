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
    monkeypatch.setitem(sys.modules,'beacon_mobile',mobile)
    monkeypatch.setitem(sys.modules,'beacon_native',SimpleNamespace())
    monkeypatch.setattr(ch,'_profile_timezone',lambda profile:pytest.fail('run must defer the profile-clock check'),raising=False)
    monkeypatch.setattr(ch,'tick',lambda m,n,cfg,profile,now:seen.update(cfg=cfg,now=now))
    ch.run(tmp_path,'default','Unknown/Timezone',captured)
    assert seen['now'] is captured and seen['cfg']=={'profile':'default'}


@pytest.mark.parametrize('configured_timezone',['','Unknown/Timezone'])
def test_checkup_tick_uses_hermes_clock_fallback_for_empty_or_unknown_profile_timezone(env,monkeypatch,configured_timezone):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    now=datetime(2026,10,16,12,tzinfo=ZoneInfo('America/Los_Angeles'))
    monkeypatch.setattr(ch,'slots',lambda *args:[now.hour*60+now.minute])
    proactive=sys.modules['beacon_proactive']
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


def test_a_check_in_still_counts_minutes_after_its_time_but_only_once(env,monkeypatch):
    """Owner, 4 Nov 2026: no check-in ever came. Hermes runs the one-minute job about every two minutes, so an exact minute was missed."""
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    zone=ZoneInfo('America/Los_Angeles')
    monkeypatch.setattr(ch,'_profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=zone),raising=False)
    slot=ch.slots('owner','default','2026-10-16')[0]
    late=datetime(2026,10,16,(slot+3)//60,(slot+3)%60,tzinfo=zone)
    assert ch.tick(m,n,cfg,'default',late)['state']=='pending'
    later=datetime(2026,10,16,(slot+5)//60,(slot+5)%60,tzinfo=zone)
    assert ch.tick(m,n,cfg,'default',later)['state']=='already_considered'
    too_late=datetime(2026,10,16,(slot+ch.DUE_MINUTES)//60,(slot+ch.DUE_MINUTES)%60,tzinfo=zone)
    assert ch.tick(m,n,cfg,'default',too_late)['state'] in ('no_opportunity','already_considered')


def test_each_frequency_has_its_times_inside_the_day_window(env):
    _,m,_,_,_,_=env;ch=load('checkups')
    assert ch.slots('owner','default','2026-10-16','hourly')==list(range(600,1320,60))
    assert ch.slots('owner','default','2026-10-16','every_2h')==[600,720,840,960,1080,1200]
    assert ch.slots('owner','default','2026-10-16','every_3h')==[600,780,960,1140]
    once=ch.slots('owner','default','2026-10-16','daily')
    assert len(once)==1 and 600<=once[0]<1320
    # The few-times-a-day choice keeps the times it always had.
    assert ch.slots('owner','default','2026-10-16','random')==ch.slots('owner','default','2026-10-16')
    assert ch.cadence(m,'owner','default')=='random'
    ch.set_cadence(m,'owner','default','every_3h')
    assert ch.cadence(m,'owner','default')=='every_3h'
    with pytest.raises(ValueError): ch.set_cadence(m,'owner','default','minutely')
