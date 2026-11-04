"""A native cron script gate: no timer, agent loop, or catch-up callbacks."""
import hashlib
import random
import time
import uuid
import importlib.util
from pathlib import Path
import sys


def _profile_now(profile):
    key='beacon_proactive'
    if key not in sys.modules:
        spec=importlib.util.spec_from_file_location(key,Path(__file__).with_name('proactive.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)
    return sys.modules[key].profile_now(profile)


# How often Beacon calls to check in (the app's How often): on the hour, or random times on each day of the owner's.
CADENCES=('hourly','every_2h','every_3h','random','daily')
CADENCE_NAMES={'hourly':'hourly','every_2h':'every 2 hours','every_3h':'every 3 hours','random':'3–4 daily','daily':'once daily'}
# The native ticker runs a one-minute job about every two minutes: a check-in still counts this long after its time, once.
DUE_MINUTES=10


def cadence(mobile,owner,profile):
    with mobile.LOCK,mobile.connect() as conn:
        row=conn.execute('SELECT value FROM meta WHERE key=?',('checkup-cadence:'+owner+':'+profile,)).fetchone()
    value=(row[0].decode() if isinstance(row[0],bytes) else str(row[0])) if row else 'random'
    return value if value in CADENCES else 'random'


def set_cadence(mobile,owner,profile,value):
    if value not in CADENCES: raise ValueError('Choose how often Beacon checks in')
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta VALUES(?,?)',('checkup-cadence:'+owner+':'+profile,value.encode()))


def slots(owner,profile,day,every='random'):
    # 10:00 to 22:00 (exclusive), the profile's local day.
    step={'hourly':60,'every_2h':120,'every_3h':180}.get(every)
    if step: return list(range(600,1320,step))
    seed=int.from_bytes(hashlib.sha256((owner+'\0'+profile+'\0'+day).encode()).digest(),'big')
    rng=random.Random(seed)
    count=1 if every=='daily' else rng.choice((3,4))
    # One opportunity per distinct window segment; 22:00 is the exclusive quiet boundary.
    width=720//count
    return sorted(600+i*width+rng.randrange(width) for i in range(count))


def tick(mobile,native,cfg,profile,now):
    owner=cfg.get('owner_id');provider=cfg.get('owner_provider')
    stored=cfg.get('scheduled_callback_conversation_id')
    if (cfg.get('scheduled_callbacks_enabled') is not True or not owner or not provider or not stored
            or 'profiles' in cfg and (not isinstance(cfg['profiles'],list) or profile not in cfg['profiles'])):
        raise ValueError('Configure an owned callback conversation before enabling check-ups')
    minute=now.hour*60+now.minute
    due=[slot for slot in slots(owner,profile,now.date().isoformat(),cadence(mobile,owner,profile)) if 0<=minute-slot<DUE_MINUTES]
    if not due: return {'state':'no_opportunity'}
    slot=due[-1]
    profile_clock=_profile_now(profile)
    if profile_clock.tzinfo is None or profile_clock.utcoffset() is None:
        raise ValueError('Check-up time must match the profile timezone')
    profile_timezone=getattr(profile_clock.tzinfo,'key',None) or profile_clock.tzname()
    if (now.tzinfo is None or now.utcoffset() is None
            or (getattr(now.tzinfo,'key',None) or now.tzname())!=profile_timezone):
        raise ValueError('Check-up time must match the profile timezone')
    native.verify(owner,provider,profile,stored)
    # Keyed by the check-in's own time: a later tick inside its window finds it considered.
    key='checkup:'+owner+':'+profile+':'+now.date().isoformat()+':'+str(slot)
    with mobile.LOCK,mobile.connect() as conn:
        if conn.execute('SELECT 1 FROM meta WHERE key=?',(key,)).fetchone(): return {'state':'already_considered'}
        # Consumed even if busy; later ticks must not catch up missed opportunities.
        conn.execute('INSERT INTO meta VALUES(?,?)',(key,b'considered'))
        conn.execute("DELETE FROM meta WHERE key LIKE ? AND key<?",('checkup:'+owner+':'+profile+':%', 'checkup:'+owner+':'+profile+':'+now.date().isoformat()))
        busy=mobile.active_calls(conn,owner)
        pending=conn.execute("SELECT 1 FROM notices WHERE owner=? AND profile=? AND kind='incoming_call' AND state='pending' AND expires>?",(owner,profile,time.time())).fetchone()
        if busy or pending: return {'state':'skipped_busy'}
        event=uuid.uuid4().hex
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise ValueError('Notification capacity reached')
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(event,owner,profile,'incoming_call',stored,time.time(),time.time()+120,'Owner-requested check-up'))
    return {'state':'pending','event_id':event}


def run(plugin_root,profile,timezone_name=None,now=None):
    if timezone_name is None and now is None:
        import hermes_time
        now=hermes_time.now()
    elif timezone_name is None or now is None:
        raise ValueError('Check-up clock must include the target profile timezone and time')
    root=Path(plugin_root)
    for name in ('mobile','native'):
        key='beacon_'+name
        if key not in sys.modules:
            spec=importlib.util.spec_from_file_location(key,root/(name+'.py'))
            module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)
    mobile=sys.modules['beacon_mobile']
    tick(mobile,sys.modules['beacon_native'],mobile.settings(profile),profile,now)
    # Empty stdout makes no_agent native runs silent. The owned durable outbox wakes the phone.
