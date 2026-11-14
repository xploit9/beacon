from test_callbacks import load,env
from datetime import datetime
from zoneinfo import ZoneInfo
from types import SimpleNamespace
import json
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


def test_unresolvable_stored_conversation_is_skipped_with_one_incident(env, monkeypatch, caplog):
    """Owner, 7 Nov 2026: Hermes ran the check-up job every two minutes and it raised LookupError each time after the stored chat stopped resolving."""
    _,m,_,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='gone'
    zone=ZoneInfo('America/Los_Angeles')
    monkeypatch.setattr(ch,'_profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=zone),raising=False)
    def not_owned(*args): raise LookupError('Native stored conversation not owned')
    native=SimpleNamespace(verify=not_owned)
    slot=ch.slots('owner','default','2026-10-16')[0]
    first=datetime(2026,10,16,slot//60,slot%60,tzinfo=zone)
    later=datetime(2026,10,16,(slot+2)//60,(slot+2)%60,tzinfo=zone)
    with caplog.at_level('WARNING'):
        assert ch.tick(m,native,cfg,'default',first)=={'state':'stale_conversation'}
        assert ch.tick(m,native,cfg,'default',later)=={'state':'stale_conversation'}
    assert sum('checkup_stored_conversation_unresolved' in record.getMessage() for record in caplog.records)==1
    with m.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0]==0
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE owner='owner'").fetchone()[0]==2
    assert cfg['scheduled_callback_conversation_id']=='gone'


def test_other_verification_failures_still_surface(env, monkeypatch):
    _,m,_,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    zone=ZoneInfo('America/Los_Angeles')
    monkeypatch.setattr(ch,'_profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=zone),raising=False)
    def store_down(*args): raise RuntimeError('Native conversation store unavailable')
    slot=ch.slots('owner','default','2026-10-16')[0]
    due=datetime(2026,10,16,slot//60,slot%60,tzinfo=zone)
    with pytest.raises(RuntimeError): ch.tick(m,SimpleNamespace(verify=store_down),cfg,'default',due)


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


def _write_claim(m,conversations,drafts=None):
    claim={'owner_id':'owner','owner_provider':'basic','scheduled_callback_conversations':conversations}
    if drafts is not None: claim['provisional_conversations']=drafts
    with m.LOCK,m.connect() as conn:
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES('owner_claim',?)",(json.dumps(claim),))


def _read_claim(m):
    with m.LOCK,m.connect() as conn:
        row=conn.execute("SELECT value FROM meta WHERE key='owner_claim'").fetchone()
    return json.loads(row[0])


def _stale_setup(env,monkeypatch):
    _,m,_,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='gone'
    zone=ZoneInfo('America/Los_Angeles')
    monkeypatch.setattr(ch,'_profile_now',lambda profile:datetime(2026,10,16,12,tzinfo=zone),raising=False)
    _write_claim(m,{'default':'gone'})
    def not_owned(*args): raise LookupError('Native stored conversation not owned')
    slot=ch.slots('owner','default','2026-10-16')[0]
    due=datetime(2026,10,16,slot//60,slot%60,tzinfo=zone)
    later=datetime(2026,10,16,(slot+2)//60,(slot+2)%60,tzinfo=zone)
    return ch,m,cfg,SimpleNamespace(verify=not_owned),due,later


def test_stale_binding_is_cleared_once_and_the_next_run_is_quiet(env,monkeypatch,caplog):
    ch,m,cfg,native,due,later=_stale_setup(env,monkeypatch)
    with caplog.at_level('WARNING'):
        assert ch.tick(m,native,cfg,'default',due)=={'state':'stale_conversation_cleared'}
        assert 'default' not in _read_claim(m)['scheduled_callback_conversations']
        cfg.pop('scheduled_callback_conversation_id')  # what settings() reports once the binding is gone
        assert ch.tick(m,native,cfg,'default',later)=={'state':'no_conversation'}
    assert sum('checkup_stored_conversation_unresolved' in record.getMessage() for record in caplog.records)==1
    with m.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE owner='owner'").fetchone()[0]==2


def test_cleared_binding_recovers_when_a_chat_is_saved_again(env,monkeypatch):
    ch,m,cfg,native,due,_=_stale_setup(env,monkeypatch)
    assert ch.tick(m,native,cfg,'default',due)=={'state':'stale_conversation_cleared'}
    cfg['scheduled_callback_conversation_id']='fresh';_write_claim(m,{'default':'fresh'})
    assert ch.tick(m,SimpleNamespace(verify=lambda *args: {}),cfg,'default',due)['state']=='pending'


def test_clear_only_forgets_the_matching_chat(env):
    _,m,_,_,_,_=env
    _write_claim(m,{'default':'current','work':'other'},drafts={'default':'current'})
    assert m.clear_scheduled_callback_conversation('owner','basic','default','stale') is False
    assert m.clear_scheduled_callback_conversation('stranger','basic','default','current') is False
    assert m.clear_scheduled_callback_conversation('owner','basic','default','current') is True
    claim=_read_claim(m)
    assert claim['scheduled_callback_conversations']=={'work':'other'} and 'provisional_conversations' not in claim
