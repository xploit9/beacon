"""Synthetic native requests only: no model or mailbox action."""
import sys
import time
from types import SimpleNamespace
import pytest
from test_mobile import harness

@pytest.fixture
def activity_harness(harness,monkeypatch):
    api,client,cfg,tmp,app=harness
    frames=[{'id':'srq-fixture','method':'approval','params':{'session_id':'runtime','command':'echo fixture','choices':['once','deny']}}]
    work=[{'key':'activity-key','stored':'native-stored','runtime':'runtime','requests':frames}]
    monkeypatch.setattr(api.activity,'snapshot',lambda *args:work)
    monkeypatch.setitem(sys.modules,'tui_gateway',SimpleNamespace(server_requests=SimpleNamespace(open_requests=lambda sid:frames)))
    answers=[]
    monkeypatch.setattr(api.native,'rpc',lambda *args:answers.append(args) or {'ok':True})
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
    assert answers[0][-2:] == ('approval.respond',{'session_id':'runtime','request_id':'srq-fixture','choice':'once'})
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
    assert answers[0][-2:] == ('approval.respond',{'session_id':'runtime','request_id':'srq-fixture','choice':'deny'})

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
    server=SimpleNamespace(_sessions_lock=threading.RLock(),_sessions=records,_session_auth_user_id=lambda r:r['user_id'])
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
