"""Synthetic native requests only: no model or mailbox action."""
import sys
import time
from types import SimpleNamespace
import pytest
from test_mobile import harness

@pytest.fixture
def activity_harness(harness,monkeypatch):
    api,client,cfg,tmp,app=harness
    frames=[{'id':'srq-fixture','method':'approval','params':{'session_id':'runtime','request_id':'a'*32,'command':'echo fixture','choices':['once','deny']}}]
    work=[{'key':'activity-key','stored':'native-stored','runtime':'runtime','requests':frames}]
    monkeypatch.setattr(api.activity,'snapshot',lambda *args:work)
    monkeypatch.setitem(sys.modules,'tui_gateway',SimpleNamespace(server_requests=SimpleNamespace(open_requests=lambda sid:frames)))
    answers=[]
    monkeypatch.setattr(api.native,'rpc',lambda *args:answers.append(args) or (
        {'resolved':1} if args[-2]=='approval.respond' else {'status':'ok'}))
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    return api,client,frames,work,answers

BASE='/api/plugins/beacon/mobile/notifications/events'

def test_authenticated_offered_action_and_replay(activity_harness):
    api,c,frames,work,answers=activity_harness
    route=c.get(BASE+'/native-srq-fixture').json()
    assert route['activity_ref']=='activity-key-srq-fixture' and [a['value'] for a in route['actions']]==['once','deny']
    assert 'command' not in route['preview']
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'always'}).status_code==409
    assert not answers
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==200
    assert answers[0][-2:] == ('approval.respond',{'session_id':'runtime','request_id':'a'*32,'choice':'once'})
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert len(answers)==1


def test_private_hydration_explains_what_the_owner_is_answering(activity_harness):
    api,c,frames,work,answers=activity_harness
    frames[0]['params'].update(description='Inspect the workspace', command='ls -la')
    result=c.get(BASE+'/native-srq-fixture').json()
    assert 'Inspect the workspace' in result['preview']['body'] and 'ls -la' in result['preview']['body']
    with api.mobile.connect() as conn: row=dict(conn.execute("SELECT * FROM notices WHERE id='native-srq-fixture'").fetchone())
    assert 'ls -la' not in api.notice_wire(row,'owner')['preview']['body']

@pytest.mark.parametrize('change',['long','missing','redacted'])
def test_incomplete_permission_preview_cannot_authorize_a_hidden_command(activity_harness,monkeypatch,change):
    api,c,frames,work,answers=activity_harness
    if change=='long': frames[0]['params']['command']='echo '+('fixture '*100)+'; echo hidden-tail'
    elif change=='missing': frames[0]['params'].pop('command');frames[0]['params']['description']='Action summary only'
    else: monkeypatch.setitem(sys.modules,'agent.redact',SimpleNamespace(redact_sensitive_text=lambda text,**kwargs:'[redacted]'))
    result=c.get(BASE+'/native-srq-fixture').json()
    assert result['preview']['complete'] is False
    assert [row['value'] for row in result['actions']]==['deny']
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert not answers
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'deny'}).status_code==200
    assert answers[0][-2:] == ('approval.respond',{'session_id':'runtime','request_id':'a'*32,'choice':'deny'})


@pytest.mark.parametrize('request_id',[None,'',False,42,[],{},'bad id','x'*161])
def test_approval_requires_a_valid_native_queue_id(activity_harness,request_id):
    api,c,frames,work,answers=activity_harness
    frames[0]['params']['request_id']=request_id

    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert not answers


@pytest.mark.parametrize('receipt',[{'resolved':0},{'resolved':-1},{'resolved':True},
    {'resolved':1.0},{'resolved':'1'},{'ok':True},None])
def test_unconfirmed_approval_receipt_never_repeats_the_mutation(activity_harness,monkeypatch,receipt):
    api,c,frames,work,answers=activity_harness
    monkeypatch.setattr(api.native,'rpc',lambda *args:answers.append(args) or receipt)

    for _ in range(2):
        assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert len(answers)==1
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT state FROM notices WHERE id='native-srq-fixture'").fetchone()[0]=='settled'


def test_positive_native_resolved_count_is_accepted(activity_harness,monkeypatch):
    api,c,frames,work,answers=activity_harness
    monkeypatch.setattr(api.native,'rpc',lambda *args:answers.append(args) or {'resolved':2})

    response=c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'})

    assert response.status_code==200 and response.json()['status']=='answered'
    assert len(answers)==1


def test_lost_native_receipt_never_repeats_the_mutation(activity_harness,monkeypatch):
    api,c,frames,work,answers=activity_harness
    def fail(*args):
        answers.append(args)
        raise ValueError('Receipt unavailable')
    monkeypatch.setattr(api.native,'rpc',fail)

    for _ in range(2):
        assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert len(answers)==1

def test_redaction_is_forced_and_android_utf16_preview_limit_is_respected(activity_harness,monkeypatch):
    api,c,frames,work,answers=activity_harness
    options=[]
    def redact(text,**kwargs): options.append(kwargs);return text
    monkeypatch.setitem(sys.modules,'agent.redact',SimpleNamespace(redact_sensitive_text=redact))
    frames[0]['params']['command']='echo '+chr(0x1f600)*320
    result=c.get(BASE+'/native-srq-fixture').json()
    assert result['preview']['complete'] is False
    assert options[-1]=={'force':True,'redact_url_credentials':True}
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409

def test_expired_end_markers_are_pruned(activity_harness):
    api,c,frames,work,answers=activity_harness
    frames.clear();work.clear()
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='activity_end'").fetchone()[0]>0
        conn.execute('UPDATE notices SET expires=?',(time.time()-1,))
        api.mobile.prune_notices(conn)
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='activity_end'").fetchone()[0]==0

def test_foreign_expired_cancelled_and_native_ownership(activity_harness,monkeypatch):
    api,c,frames,work,answers=activity_harness
    c.headers['x-fixture-owner']='foreign'
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==403
    c.headers['x-fixture-owner']='owner'
    monkeypatch.setattr(api.native,'verify',lambda *args:(_ for _ in ()).throw(LookupError('foreign native')))
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==404
    monkeypatch.setattr(api.native,'verify',lambda *args:{})
    frames.clear()
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    with api.mobile.connect() as conn: conn.execute('UPDATE notices SET expires=?',(time.time()-1,))
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert not answers

def test_single_question_text_and_complex_question_open_app(activity_harness):
    api,c,frames,work,answers=activity_harness
    frames[0]['method']='clarify';frames[0]['params']={'question':'Fixture question','choices':['yes','no']}
    with api.mobile.connect() as conn:conn.execute("UPDATE notices SET kind='clarify'")
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'unoffered'}).status_code==409
    frames[0]['params']={'question':'Fixture question'}
    assert c.get(BASE+'/native-srq-fixture').json()['text_reply'] is True
    frames[0]['params']['questions']=[{'qid':'q','question':'batch'}]
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'text'}).status_code==409
    frames[0]['params']={'question':'Fixture question'}
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'text'}).status_code==200
    assert answers[-1][-1]['result']=={'answer':'text'}


def test_empty_clarify_choices_allow_free_text(activity_harness):
    api,c,frames,work,answers=activity_harness
    frames[0]['method']='clarify';frames[0]['params']={'question':'Fixture question','choices':[]}
    with api.mobile.connect() as conn:conn.execute("UPDATE notices SET kind='clarify'")

    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'free text'}).status_code==200
    assert answers[-1][-2:] == ('request.answer',{'id':'srq-fixture','result':{'answer':'free text'}})


@pytest.mark.parametrize('receipt',[{'status':'expired'},{'status':'unknown'},{'ok':True},None])
def test_expired_or_unconfirmed_answer_is_settled_without_resending(activity_harness,monkeypatch,receipt):
    api,c,frames,work,answers=activity_harness
    frames[0]['method']='clarify';frames[0]['params']={'question':'Fixture question','choices':[]}
    with api.mobile.connect() as conn: conn.execute("UPDATE notices SET kind='clarify'")
    monkeypatch.setattr(api.native,'rpc',lambda *args:answers.append(args) or receipt)

    for _ in range(2):
        assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'text'}).status_code==409
    assert len(answers)==1

def test_work_and_end_cursor_outbox(activity_harness):
    api,c,frames,work,answers=activity_harness
    frames.clear()
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    with api.mobile.connect() as conn:
        rows=[dict(r) for r in conn.execute('SELECT * FROM notices')]
    assert any(r['kind']=='working' and r['state']=='pending' for r in rows)
    work.clear()
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    with api.mobile.connect() as conn:
        rows=[dict(r) for r in conn.execute('SELECT * FROM notices ORDER BY seq')]
    assert any(r['kind']=='activity_end' for r in rows)
    assert not any(r['state']=='pending' and r['kind']=='working' for r in rows)
    assert rows[-1]['seq']>rows[0]['seq']


def test_expired_native_request_is_not_renewed_by_polling(activity_harness):
    api,c,frames,work,answers=activity_harness
    with api.mobile.connect() as conn:
        conn.execute("UPDATE notices SET expires=? WHERE id='native-srq-fixture'",(time.time()-1,))
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    assert c.post(BASE+'/native-srq-fixture/answer',json={'answer':'once'}).status_code==409
    assert not answers
    frames.clear()
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    with api.mobile.connect() as conn:
        api.mobile.prune_notices(conn)
        assert conn.execute("SELECT 1 FROM notices WHERE id='native-srq-fixture'").fetchone() is None


def test_snapshot_filters_native_owner_profile_and_request_kind(harness,monkeypatch):
    import threading
    api,c,cfg,tmp,app=harness
    records={'ours':{'user_id':'basic:owner','session_key':'stored','running':True,'_submit_user_row':{'_row_id':7}},
             'foreign':{'user_id':'basic:other','session_key':'secret','running':True},
             'wrong-profile':{'user_id':'basic:owner','session_key':'elsewhere','running':True}}
    server=SimpleNamespace(_sessions_lock=threading.RLock(),_sessions=records,_session_auth_user_id=lambda r:r['user_id'],
                           _profile_home=lambda profile:tmp)
    requests=SimpleNamespace(open_requests=lambda sid:[{'id':'sudo-secret','method':'sudo','params':{}},{'id':'clarify-ours','method':'clarify','params':{'choices':['yes']}}])
    monkeypatch.setitem(sys.modules,'tui_gateway',SimpleNamespace(server=server,server_requests=requests))
    verified=[]
    def verify(owner,provider,profile,stored,runtime,history=True):
        verified.append(runtime)
        if runtime=='wrong-profile':raise LookupError('profile mismatch')
    monkeypatch.setattr(api.native,'verify',verify)
    live=api.activity.snapshot(api.native,'owner','basic','default')
    assert len(live)==1 and live[0]['runtime']=='ours'
    assert [r['method'] for r in live[0]['requests']]==['clarify']
    assert 'foreign' not in verified


def test_failure_is_standard_private_notification(harness):
    from test_mobile import load_private
    api,c,cfg,tmp,app=harness
    observer=load_private('observers')
    observer.completion(api.mobile,api.native,cfg,'default','owned-chat','failed-turn',failed=True)
    with api.mobile.connect() as conn: row=dict(conn.execute('SELECT * FROM notices').fetchone())
    assert row['kind']=='failure'
    route=api.notice_wire(row,'owner')
    assert route['session_id']=='owned-chat' and route['preview']['title']=='Beacon work failed'
    assert 'actions' not in route and route['activity_ref']==''


def test_result_notification_copy_is_plain_and_titles_stay_same(harness):
    api, *_ = harness
    assert api.notice_preview('completion') == ('Reply ready', 'A reply is ready.')
    assert api.notice_preview('task_result') == ('Task finished', 'A task finished.')


def test_capacity_does_not_settle_existing_requests_after_a_new_request(activity_harness):
    api, _, frames, work, _ = activity_harness
    now = time.time()
    with api.mobile.connect() as conn:
        count = conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]
        conn.executemany('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)',
            [(f'capacity-{i}', 'owner', 'default', 'working', 'native-stored', now, now + 3600) for i in range(10000 - count)])
    frames.insert(0, {'id': 'new-request', 'method': 'approval', 'params': {}})
    api.activity.sync(api.mobile, api.native, 'owner', 'basic', 'default')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT state FROM notices WHERE id='native-srq-fixture'").fetchone()[0] == 'pending'
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE id='native-new-request'").fetchone()[0] == 0


def test_a_failed_activity_pass_is_logged_once_by_kind_never_with_its_text(harness, monkeypatch, caplog):
    import logging
    api, *_ = harness
    def fail(*args): raise AttributeError('echo private-fixture-command')
    monkeypatch.setattr(api.activity, 'sync', fail)
    monkeypatch.setattr(api, '_activity_logged', set(), raising=False)
    with caplog.at_level(logging.WARNING):
        api.watch('owner', 'basic', 'default')
        api.watch('owner', 'basic', 'default')
    said = [r.getMessage() for r in caplog.records if 'live Hermes work' in r.getMessage()]
    assert said == ['Beacon could not read live Hermes work for phone alerts: AttributeError']
    assert 'private-fixture-command' not in caplog.text


def test_a_failed_question_alert_is_logged_once_by_kind_and_a_foreign_chat_is_not_a_failure(harness, monkeypatch, caplog):
    import logging
    from test_mobile import load_private
    plugin = load_private('__init__')
    monkeypatch.setattr(plugin, 'current_profile', lambda cfg: 'default')
    def foreign(*args, **kwargs): raise LookupError('not this owner')
    def broken(*args, **kwargs): raise RuntimeError('private-fixture-question')
    with caplog.at_level(logging.WARNING):
        monkeypatch.setattr(plugin.observers, 'human_input', foreign)
        plugin.native_human_input(kind='clarify', request_id='a' * 32, session_id='chat', prompt='private-fixture-question')
        monkeypatch.setattr(plugin.observers, 'human_input', broken)
        for _ in range(2):
            plugin.native_human_input(kind='clarify', request_id='a' * 32, session_id='chat', prompt='private-fixture-question')
    said = [r.getMessage() for r in caplog.records if 'question or approval' in r.getMessage()]
    assert said == ['Beacon could not alert the phone to a Hermes question or approval: RuntimeError']
    assert 'private-fixture-question' not in caplog.text


@pytest.mark.parametrize('kind,expected', [('canonical', 1), ('legacy', 0)])
def test_a_question_alert_is_made_once_and_never_for_a_runtime_the_dashboard_alerts_for(harness, monkeypatch, kind, expected):
    api, *_ = harness
    observers = sys.modules['beacon_observers']
    principal = 'auth:v1:["basic","https://issuer.example","owner"]'
    monkeypatch.setattr(api.native, 'current_principal', lambda *args, **kwargs: principal)
    monkeypatch.setattr(api.native, 'profile_fingerprint', lambda profile: 'f' * 64)
    monkeypatch.setattr(api.mobile, 'native_conversation', lambda *args, **kwargs: {'kind': kind, 'principal': principal})
    cfg = {'owner_id': 'owner', 'owner_provider': 'basic', 'profiles': ['default']}
    for _ in range(2):  # Hermes fires once per wait; a repeat still makes one notice
        observers.human_input(api.mobile, api.native, cfg, 'default', kind='clarify', request_id='b' * 32, session_id='chat')
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='sudo', request_id='c' * 32, session_id='chat')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] == expected


def test_a_wait_inside_a_calls_own_tool_run_makes_no_notice(harness, monkeypatch):
    # gateway_tools answers Hermes' gate there itself, from the owner's yes on the call screen: nobody waits on the phone.
    api, *_ = harness
    observers = sys.modules['beacon_observers']
    principal = 'auth:v1:["basic","https://issuer.example","owner"]'
    monkeypatch.setattr(api.native, 'current_principal', lambda *args, **kwargs: principal)
    monkeypatch.setattr(api.native, 'profile_fingerprint', lambda profile: 'f' * 64)
    monkeypatch.setattr(api.mobile, 'native_conversation', lambda *args, **kwargs: {'kind': 'canonical', 'principal': principal})
    cfg = {'owner_id': 'owner', 'owner_provider': 'basic', 'profiles': ['default']}
    token = api.native.call_principal.set(('owner', 'basic', 'default', 'chat', 'f' * 64, principal))
    try:
        observers.human_input(api.mobile, api.native, cfg, 'default', kind='approval', request_id='d' * 32, session_id='chat')
    finally:
        api.native.call_principal.reset(token)
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] == 0


PRINCIPAL = 'auth:v1:["basic","https://issuer.example","owner"]'


def bound_chat(api, monkeypatch, waits=None):
    """A canonical chat the owner holds, and Hermes' own wait settings (clarify, approval) when given."""
    monkeypatch.setattr(api.native, 'current_principal', lambda *args, **kwargs: PRINCIPAL)
    monkeypatch.setattr(api.native, 'profile_fingerprint', lambda profile: 'f' * 64)
    monkeypatch.setattr(api.mobile, 'native_conversation', lambda *args, **kwargs: {'kind': 'canonical', 'principal': PRINCIPAL})
    if waits:
        monkeypatch.setitem(sys.modules, 'tools.clarify_gateway', SimpleNamespace(get_clarify_timeout=lambda: waits[0]))
        monkeypatch.setitem(sys.modules, 'tools.approval_context', SimpleNamespace(_get_approval_timeout=lambda: waits[1]))
    return {'owner_id': 'owner', 'owner_provider': 'basic', 'profiles': ['default']}


def notice(api, event):
    with api.mobile.connect() as conn:
        found = conn.execute('SELECT * FROM notices WHERE id=?', (event,)).fetchone()
    return dict(found) if found else None


@pytest.mark.parametrize('waits,clarify,approval', [((7200, 600), 7200, 600), ((0, 0), 14400, 14400), ((3600, 300), 3600, 300)])
def test_a_question_or_approval_alert_lasts_as_long_as_hermes_waits(harness, monkeypatch, waits, clarify, approval):
    api, *_ = harness
    observers = sys.modules['beacon_observers']
    cfg = bound_chat(api, monkeypatch, waits)
    now = time.time()
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='clarify', request_id='1' * 32, session_id='chat')
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='approval', request_id='2' * 32, session_id='chat')
    assert abs(notice(api, 'human-' + '1' * 32)['expires'] - now - clarify) < 5
    assert abs(notice(api, 'human-' + '2' * 32)['expires'] - now - approval) < 5
    # Answered: the phone's "it ended" lives as long as the alert it ends, so a phone offline for a while still takes it down.
    observers.human_input_resolved(api.mobile, cfg, 'default', request_id='1' * 32)
    assert notice(api, 'settled-human-' + '1' * 32)['expires'] == notice(api, 'human-' + '1' * 32)['expires']


def test_an_unreadable_hermes_wait_keeps_the_default_hour(harness, monkeypatch):
    api, *_ = harness
    observers = sys.modules['beacon_observers']
    cfg = bound_chat(api, monkeypatch)
    def broken(): raise RuntimeError('config unreadable')
    monkeypatch.setitem(sys.modules, 'tools.clarify_gateway', SimpleNamespace(get_clarify_timeout=broken))
    now = time.time()
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='clarify', request_id='3' * 32, session_id='chat')
    assert abs(notice(api, 'human-' + '3' * 32)['expires'] - now - 3600) < 5


def test_a_wait_whose_end_never_came_is_settled_once_it_expires(harness, monkeypatch):
    # Hermes stopped mid-wait (no resolved hook): the row is not kept pending for ever.
    api, *_ = harness
    observers = sys.modules['beacon_observers']
    cfg = bound_chat(api, monkeypatch, (3600, 300))
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='clarify', request_id='4' * 32, session_id='chat')
    with api.mobile.connect() as conn:
        conn.execute('UPDATE notices SET expires=? WHERE id=?', (time.time() - 1, 'human-' + '4' * 32))
    api.watch('owner', 'basic', 'default')
    # Settled (then pruned like any settled, expired notice) and the phone is told it ended.
    assert (notice(api, 'human-' + '4' * 32) or {'state': 'settled'})['state'] == 'settled'
    assert notice(api, 'settled-human-' + '4' * 32)['kind'] == 'activity_end'


def test_a_legacy_request_alert_follows_hermes_wait_even_in_the_chat_of_an_open_call(harness, monkeypatch):
    api, *_ = harness
    bound_chat(api, monkeypatch, (7200, 600))
    frames = [{'id': 'srq-a', 'method': 'approval', 'params': {}}, {'id': 'srq-c', 'method': 'clarify', 'params': {}}]
    work = [{'key': 'k1', 'stored': 'chat', 'runtime': 'runtime', 'requests': frames[:1]},
            {'key': 'k2', 'stored': 'call-chat', 'runtime': 'runtime-2', 'requests': frames[1:]}]
    monkeypatch.setattr(api.activity, 'snapshot', lambda *args: work)
    api.mobile.reserve('owner', 'default', 'call-chat')
    now = time.time()
    api.activity.sync(api.mobile, api.native, 'owner', 'basic', 'default')
    assert abs(notice(api, 'native-srq-a')['expires'] - now - 600) < 5 and notice(api, 'native-srq-a')['push'] == 'pending'
    assert abs(notice(api, 'native-srq-c')['expires'] - now - 7200) < 5 and notice(api, 'native-srq-c')['push'] == 'pending'


def test_a_question_in_the_chat_of_an_open_call_pushes_once_like_any_other(harness, monkeypatch):
    # The call screen has no card for a Hermes question, so the phone is told at once; it posts it quietly during the call.
    from test_mobile import phone
    api, client, cfg_api, tmp, app = harness
    observers = sys.modules['beacon_observers']
    cfg = bound_chat(api, monkeypatch, (3600, 300))
    cfg_api['firebase_credentials'] = str(tmp / 'synthetic-service-account.json')
    route = '/api/plugins/beacon/mobile/notifications/targets'
    assert client.post(route, json={'id': 'p', 'token': 'synthetic-token-' + 'x' * 20}, headers=phone(client)).status_code == 200
    with api.mobile.connect() as conn:
        conn.execute("UPDATE targets SET payload=json_set(payload,'$.principal',?)", (PRINCIPAL,))
    sent = []
    messaging = SimpleNamespace(Message=lambda **kwargs: kwargs, AndroidConfig=lambda **kwargs: kwargs, send=lambda message, app=None: sent.append(message))
    monkeypatch.setitem(sys.modules, 'firebase_admin', SimpleNamespace(get_app=lambda name: object(), credentials=SimpleNamespace(), messaging=messaging))
    api.mobile.reserve('owner', 'default', 'call-chat', principal=PRINCIPAL)
    in_call, elsewhere = 'human-' + 'a' * 32, 'human-' + 'b' * 32
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='clarify', request_id='a' * 32, session_id='call-chat')
    observers.human_input(api.mobile, api.native, cfg, 'default', kind='approval', request_id='b' * 32, session_id='other-chat')
    for _ in range(2):  # two dashboard passes while the call stays open
        api.watch('owner', 'basic', 'default')
        api.push('owner', 'default')
    assert [m['data']['event_id'] for m in sent] == [in_call, elsewhere]
    assert [row['id'] for row in api._notices_after('owner', 'basic', 'default', 0, PRINCIPAL)[0]] == [in_call, elsewhere]
