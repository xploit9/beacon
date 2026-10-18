from test_callbacks import load,env
from datetime import datetime
from zoneinfo import ZoneInfo

def test_random_slots_fixed_day_window_and_exact_minute(env):
    _,m,n,cfg,_,_=env;ch=load('checkups');cfg['scheduled_callback_conversation_id']='owned'
    slots=ch.slots('owner','default','2026-10-16')
    assert 3<=len(slots)<=4 and len(set(slots))==len(slots) and all(600<=v<1320 for v in slots)
    assert slots==ch.slots('owner','default','2026-10-16')
    minute=slots[0];due=datetime(2026,10,16,minute//60,minute%60,tzinfo=ZoneInfo('Asia/Dubai'))
    assert ch.tick(m,n,cfg,'default',due)['state']=='pending'
    assert ch.tick(m,n,cfg,'default',due)['state']=='already_considered'
