"""Issuer-bound same-chat voice checks use synthetic stores and no network."""
import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness
from gateway.session_identity import authenticated_subject
from gateway.session_authority import SessionAuthority,LiveSession
from gateway.session_controls import AuthorityConnection


def subject(issuer='https://issuer.example'):
    return authenticated_subject({'provider': 'basic', 'issuer': issuer, 'user_id': 'owner'})


def test_lifecycle_new_call_persists_exact_principal_and_never_assigns_legacy(harness):
    api, _, _, _, _ = harness
    principal = subject()
    created = api.mobile.reserve('owner', 'default', 'chat-one', {'backend': 'hermes'}, principal)
    legacy = api.mobile.reserve('owner', 'default', 'chat-old', {'backend': 'hermes'})
    with api.mobile.connect() as conn:
        assert api.mobile.owned(conn, created['call_id'], 'owner')['principal'] == principal
        assert api.mobile.owned(conn, legacy['call_id'], 'owner')['principal'] == ''
    with api.mobile.connect() as conn:
        assert api.mobile.owned(conn, created['call_id'], 'owner')['principal'] == principal
        assert api.mobile.owned(conn, legacy['call_id'], 'owner')['principal'] == ''


@pytest.fixture
def canonical_call(harness, monkeypatch):
    import importlib.util
    import threading
    import time
    from contextlib import contextmanager
    from pathlib import Path
    from hermes_cli.dashboard_auth.base import Session
    api, client, cfg, _, app = harness
    def make_session(issuer):
        return Session(user_id='owner', provider='basic', issuer=issuer, email='', display_name='', org_id='',
                       expires_at=int(time.time()) + 120, access_token='synthetic-token', refresh_token='')
    sessions = [make_session('https://issuer.example')]
    del client.headers['x-fixture-owner']
    @app.middleware('http')
    async def authenticated(request, call_next):
        request.state.session = sessions[0]
        return await call_next(request)
    rows = {'same-chat': {'id': 'same-chat', 'user_id': subject(), 'source': 'local'}}
    @contextmanager
    def profile_db(params, writer=False):
        assert params['profile'] == 'default'
        yield SimpleNamespace(get_session=rows.get)
    server = SimpleNamespace(_profile_db=profile_db, _profile_home=lambda profile: None,
                             _sessions_lock=threading.RLock(), _sessions={},
                             dispatch=lambda *args, **kwargs: pytest.fail('read guard dispatched a session'))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, server_requests=SimpleNamespace(open_requests=lambda sid: [])))
    spec = importlib.util.spec_from_file_location('canonical_real_native', Path(api.native.__file__))
    real = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(real)
    monkeypatch.setattr(api.native, 'verify', real.verify)
    monkeypatch.setattr(api.native, 'reserve_conversation', real.reserve_conversation)
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: False)
    return api, client, cfg, sessions, make_session, rows, server


def test_integration_same_chat_call_binds_verified_issuer(canonical_call):
    api, client, _, sessions, make_session, rows, _ = canonical_call
    payload = {'profile': 'default', 'voice_provider': 'gemini', 'client_key': True, 'conversation_id': 'same-chat'}
    response = client.post('/api/plugins/beacon/mobile/calls', json=payload)
    assert response.status_code == 200, response.text
    call_id = response.json()['call_id']
    with api.mobile.connect() as conn:
        assert api.mobile.owned(conn, call_id, 'owner')['principal'] == subject()
    assert client.get('/api/plugins/beacon/mobile/calls/' + call_id).status_code == 200
    sessions[0] = make_session('https://other.example')
    assert client.get('/api/plugins/beacon/mobile/calls/' + call_id).status_code == 403
    assert client.post('/api/plugins/beacon/mobile/calls', json=payload).status_code in (403, 404)
    sessions[0] = make_session('https://issuer.example')
    rows['same-chat']['user_id'] = subject('https://other.example')
    assert client.get('/api/plugins/beacon/mobile/calls/' + call_id).status_code in (403, 404)
    rows['same-chat']['user_id'] = subject()
    legacy = api.mobile.reserve('owner', 'default', 'same-chat')
    assert client.get('/api/plugins/beacon/mobile/calls/' + legacy['call_id']).status_code in (403, 404)
    with api.mobile.connect() as conn:
        assert api.mobile.owned(conn, legacy['call_id'], 'owner')['principal'] == ''


def test_integration_reserve_saved_canonical_chat_keeps_issuer_boundary(canonical_call):
    _, client, _, sessions, make_session, _, _ = canonical_call
    path = '/api/plugins/beacon/mobile/sessions/same-chat/reserve?profile=default'
    response = client.post(path, json={})
    assert response.status_code == 200, response.text
    assert response.json()['stored_session_id'] == 'same-chat'
    sessions[0] = make_session('https://other.example')
    assert client.post(path, json={}).status_code in (403, 404)


def test_integration_canonical_call_refuses_unavailable_ownership_store(canonical_call):
    _, client, _, _, _, _, server = canonical_call
    response = client.post('/api/plugins/beacon/mobile/calls', json={
        'profile': 'default', 'voice_provider': 'gemini', 'client_key': True, 'conversation_id': 'same-chat'})
    assert response.status_code == 200
    def unavailable(*args, **kwargs):
        raise RuntimeError('synthetic unavailable')
    server._profile_db = unavailable
    assert client.get('/api/plugins/beacon/mobile/calls/' + response.json()['call_id']).status_code == 503


def test_integration_live_authority_proof_requires_exact_active_subject(canonical_call, monkeypatch):
    api, _, _, _, _, rows, _ = canonical_call
    transport = sys.modules['beacon_canonical_transport']
    issuer = 'https://issuer.example'
    execution=api.native.execution_subject('owner','basic',subject(issuer))
    actor = SimpleNamespace(subject=execution, profile_id='profile-home', capabilities={'session:read'},transport_id='transport')
    live = SimpleNamespace(subscribers={'transport': actor})
    authority = SimpleNamespace(profile_id='profile-home', instance_id='instance', epoch=7, retiring=False,
                                sessions={'same-chat': live}, db=SimpleNamespace(get_session=rows.get),
                                events={'transport':SimpleNamespace(auth_identity={'provider':'basic','issuer':issuer,'user_id':'owner'})})
    runner = SimpleNamespace(session_authority=authority, session_authorities=SimpleNamespace(for_home=lambda home: authority), _draining=False,
                             session_runtime_descriptor={'state': 'ready', 'instance_id': 'instance', 'authority_epoch': 7})
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=runner))
    session = SimpleNamespace(provider='basic', issuer=issuer, user_id='owner')
    request = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner, session_authority=authority)),
                              state=SimpleNamespace(session=session))
    proof = transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'same-chat', 'a' * 64)
    assert proof['subject'] == transport.subject_digest(session)
    assert proof['stored'] == proof['runtime'] == 'same-chat'
    assert 'ticket' not in proof
    actor.subject = subject('https://other.example')
    with pytest.raises(Exception):
        transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'same-chat', 'a' * 64)
    actor.subject = execution
    authority.events['transport'].auth_identity={'provider':'basic','issuer':'https://other.example','user_id':'owner'}
    with pytest.raises(Exception):
        transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'same-chat', 'a' * 64)
    authority.events['transport'].auth_identity={'provider':'local','user_id':subject(issuer),'native_bootstrap':True}
    with pytest.raises(Exception):
        transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'same-chat', 'a' * 64)
    authority.events['transport'].auth_identity={'provider':'basic','issuer':issuer,'user_id':'owner'}
    with pytest.raises(Exception):
        transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'other', 'a' * 64)
    runner._draining = True
    with pytest.raises(Exception):
        transport.canonical_live_proof(request, 'default', 'profile-home', 'same-chat', 'same-chat', 'a' * 64)


def test_integration_voice_work_requires_live_canonical_proof_and_persists_subject(canonical_call, monkeypatch):
    api, client, _, sessions, make_session, _, _ = canonical_call
    response = client.post('/api/plugins/beacon/mobile/calls', json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code == 200
    call_id = response.json()['call_id']
    transport = sys.modules['beacon_canonical_transport']
    seen = []
    def proof(request, profile, stored, runtime):
        seen.append((request.state.session.issuer, profile, stored, runtime))
        return {'verified': True}
    monkeypatch.setattr(transport, 'verify_live_session', proof)
    payload = {'stored_session_id':'same-chat','runtime_session_id':'same-chat','request_id':'request-one',
               'text':'synthetic task','callback':True}
    result = client.post('/api/plugins/beacon/mobile/calls/' + call_id + '/work', json=payload)
    assert result.status_code == 200, result.text
    assert result.json()['submit'] is True
    assert seen == [('https://issuer.example','default','same-chat','same-chat')]
    with api.mobile.connect() as conn:
        row = conn.execute('SELECT principal,provider,stored,runtime FROM jobs WHERE id=?',(result.json()['job_id'],)).fetchone()
        assert tuple(row) == (subject(),'basic','same-chat','same-chat')
    sessions[0] = make_session('https://other.example')
    assert client.post('/api/plugins/beacon/mobile/calls/' + call_id + '/work',json=payload).status_code == 403


def test_integration_call_context_reads_only_exact_subject_without_dispatch(canonical_call, monkeypatch, tmp_path):
    api, client, _, _, _, _, _ = canonical_call
    monkeypatch.setattr(api.voice_context, 'scoped', lambda profile, run, **kwargs: run(tmp_path))
    monkeypatch.setattr(api.voice_context, 'cached_config', lambda root: {})
    monkeypatch.setattr(api.voice_context, 'profile_clock', lambda *args, **kwargs: ('', ''))
    monkeypatch.setattr(api.voice_context, 'cached_owner_name', lambda *args: '')
    response = client.post('/api/plugins/beacon/mobile/calls', json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code == 200
    call_id = response.json()['call_id']
    direct = api.voice_context.context('owner','basic','default','same-chat',call_id,'','',True,None,False,subject())
    assert direct['selected_session_id'] == 'same-chat'
    context = client.get('/api/plugins/beacon/mobile/calls/' + call_id + '/context?backend=hermes')
    assert context.status_code == 200, context.text
    assert context.json()['selected_session_id'] == 'same-chat'


def test_lifecycle_background_callback_requires_original_job_and_call_principal(canonical_call, monkeypatch):
    api, client, _, _, _, _, _ = canonical_call
    response = client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code == 200
    call_id=response.json()['call_id']
    transport=sys.modules['beacon_canonical_transport']
    continuation=sys.modules['beacon_voice_continuation']
    monkeypatch.setattr(transport,'verify_live_session',lambda *args:{'verified':True})
    payload={'stored_session_id':'same-chat','runtime_session_id':'same-chat','request_id':'request-callback',
             'text':'synthetic task','callback':True}
    prepared=client.post('/api/plugins/beacon/mobile/calls/'+call_id+'/work',json=payload)
    assert prepared.status_code == 200,prepared.text
    job_id=prepared.json()['job_id']
    with api.mobile.connect() as conn:
        conn.execute("UPDATE calls SET closed=1,status='saved' WHERE id=?",(call_id,))
    monkeypatch.setattr(continuation,'native_receipt',lambda job:(
        {'status':'terminal','outcome':'completed','admission_id':'receipt'},
        {'result':{'completed':True,'final_response':'synthetic completion'}}))
    monkeypatch.setattr(api,'activate',lambda *args:None)
    monkeypatch.setattr(api,'native_replay_epoch',lambda:'different-epoch')
    api.watch('owner','basic','default')
    with api.mobile.connect() as conn:
        job=conn.execute('SELECT principal,status FROM jobs WHERE id=?',(job_id,)).fetchone()
        notice=conn.execute('SELECT job_id,conversation,principal FROM notices WHERE job_id=?',(job_id,)).fetchone()
        assert tuple(job)==(subject(),'callback_pending')
        assert tuple(notice)==(job_id,'same-chat',subject())


def test_lifecycle_callback_refuses_unknown_or_changed_issuer(canonical_call, monkeypatch):
    api, client, _, _, _, _, _ = canonical_call
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code==200
    call_id=response.json()['call_id']
    with api.mobile.connect() as conn:
        conn.execute("UPDATE calls SET closed=1,status='saved' WHERE id=?",(call_id,))
        for job,principal in (('unknown',''),('other',subject('https://other.example'))):
            conn.execute("INSERT INTO jobs(id,call_id,owner,provider,profile,stored,runtime,request,title,callback,epoch,seq,status,principal) VALUES(?,?,?,?,?,?,?,?,?,1,'canonical',NULL,'completed',?)",
                         (job,call_id,'owner','basic','default','same-chat','same-chat',job,'callback',principal))
    monkeypatch.setattr(api,'activate',lambda *args:None)
    monkeypatch.setattr(api,'native_replay_epoch',lambda:'different-epoch')
    api.watch('owner','basic','default')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE job_id IN ('unknown','other')").fetchone()[0]==0
        assert {row[0] for row in conn.execute("SELECT status FROM jobs WHERE id IN ('unknown','other')")} == {'completed'}


def test_lifecycle_invitations_keep_exact_principal_across_defer_and_log(harness):
    api, _, _, _, _ = harness
    first=subject()
    other=subject('https://other.example')
    first_id=api.mobile.invitation('owner','default','chat-one','one',deduplicate=True,principal=first)
    other_id=api.mobile.invitation('owner','default','chat-one','two',deduplicate=True,principal=other)
    assert first_id!=other_id
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT principal FROM notices WHERE id=?',(first_id,)).fetchone()[0]==first
        assert conn.execute('SELECT principal FROM call_log WHERE id=?',(first_id,)).fetchone()[0]==first
    active=api.mobile.reserve('owner','default','chat-one',principal=first)
    deferred=api.mobile.invitation('owner','default','chat-one','later',principal=first)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT principal FROM deferred WHERE id=?',(deferred,)).fetchone()[0]==first
        conn.execute("UPDATE calls SET closed=1,status='saved' WHERE id=?",(active['call_id'],))
    api.activate('owner','default')
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT principal FROM notices WHERE id=?',(deferred,)).fetchone()[0]==first
        assert conn.execute('SELECT principal FROM call_log WHERE id=?',(deferred,)).fetchone()[0]==first


def test_integration_callback_answer_keeps_subject_and_rejects_other_issuer(canonical_call):
    api, client, _, sessions, make_session, _, _ = canonical_call
    invitation=api.mobile.invitation('owner','default','same-chat','completed',principal=subject())
    path='/api/plugins/beacon/mobile/callbacks/'+invitation+'/answer'
    payload={'voice_provider':'gemini','client_key':True}
    sessions[0]=make_session('https://other.example')
    assert client.post(path,json=payload).status_code in (403,404)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT state,principal FROM notices WHERE id=?',(invitation,)).fetchone()[0]=='pending'
    sessions[0]=make_session('https://issuer.example')
    response=client.post(path,json=payload)
    assert response.status_code==200,response.text
    with api.mobile.connect() as conn:
        assert api.mobile.owned(conn,response.json()['call_id'],'owner')['principal']==subject()


def test_integration_notification_visibility_is_issuer_bound(canonical_call, monkeypatch):
    api,client,_,sessions,make_session,_,_=canonical_call
    invitation=api.mobile.invitation('owner','default','same-chat','private reason',principal=subject())
    monkeypatch.setattr(api,'watch',lambda *args:None)
    monkeypatch.setattr(api.mobile,'prune_history',lambda *args:None)
    monkeypatch.setattr(sys.modules['beacon_callbacks'],'prune_expired',lambda *args:None)
    path='/api/plugins/beacon/mobile/notifications/events'
    sessions[0]=make_session('https://other.example')
    assert all(row['id']!=invitation for row in client.get(path).json()['events'])
    assert client.get(path+'/'+invitation).status_code==404
    sessions[0]=make_session('https://issuer.example')
    events=client.get(path).json()['events']
    found=next(row for row in events if row['id']==invitation)
    assert 'principal' not in found
    assert client.get(path+'/'+invitation).status_code==200


def test_lifecycle_bound_callback_push_targets_only_matching_issuer(canonical_call, monkeypatch):
    api,client,cfg,sessions,make_session,_,_=canonical_call
    device=api.mobile.link_device('owner','basic','Synthetic phone')['device_id']
    path='/api/plugins/beacon/mobile/notifications/targets'
    headers={'x-beacon-device-id':device}
    first='first-synthetic-target-1234567890'
    other='other-synthetic-target-1234567890'
    assert client.post(path,json={'token':first},headers=headers).status_code==200
    sessions[0]=make_session('https://other.example')
    assert client.post(path,json={'token':other},headers=headers).status_code==200
    api.mobile.invitation('owner','default','same-chat','completed',principal=subject())
    cfg['firebase_credentials']='synthetic'
    sent=[]
    messaging=SimpleNamespace(Message=lambda **kwargs:kwargs,AndroidConfig=lambda **kwargs:kwargs,
                              send=lambda message,app=None:sent.append(message))
    monkeypatch.setitem(sys.modules,'firebase_admin',SimpleNamespace(get_app=lambda name:object(),credentials=SimpleNamespace(),messaging=messaging))
    monkeypatch.setattr(api,'activate',lambda *args:None)
    api.push('owner','default')
    assert [message['token'] for message in sent]==[first]
    sent.clear()
    api.mobile.invitation('owner','default','same-chat','unknown binding')
    api.push('owner','default')
    assert sent==[]


def test_lifecycle_push_target_cannot_be_listed_or_removed_by_another_issuer(canonical_call):
    api,client,_,sessions,make_session,_,_=canonical_call
    device=api.mobile.link_device('owner','basic','Synthetic phone')['device_id']
    path='/api/plugins/beacon/mobile/notifications/targets'
    response=client.post(path,json={'token':'synthetic-target-1234567890'},headers={'x-beacon-device-id':device})
    assert response.status_code==200
    target=response.json()['target_id']
    sessions[0]=make_session('https://other.example')
    assert all(row['target_id']!=target for row in client.get(path).json()['targets'])
    client.delete(path+'/'+target)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT 1 FROM targets WHERE id=?',(target,)).fetchone()


def test_integration_call_history_filters_other_or_unknown_canonical_subject(canonical_call):
    api,client,_,sessions,make_session,_,_=canonical_call
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code==200
    own=response.json()['call_id']
    other=api.mobile.reserve('owner','default','same-chat',principal=subject('https://other.example'))['call_id']
    unknown=api.mobile.reserve('owner','default','same-chat')['call_id']
    rows=client.get('/api/plugins/beacon/mobile/calls?profile=default').json()['calls']
    assert {row['id'] for row in rows}=={own}
    assert other not in {row['id'] for row in rows} and unknown not in {row['id'] for row in rows}
    sessions[0]=make_session('https://other.example')
    assert client.get('/api/plugins/beacon/mobile/calls?profile=default').json()['calls']==[]


def test_lifecycle_activity_never_settles_an_unverified_canonical_notice(canonical_call):
    import time
    api,_,_,_,_,rows,_=canonical_call
    rows['legacy-chat']={'id':'legacy-chat','user_id':'basic:owner','source':'android'}
    now=time.time()
    with api.mobile.connect() as conn:
        for event,conversation in (('canonical-work','same-chat'),('legacy-work','legacy-chat')):
            conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,activity_key) VALUES(?,?,?,'working',?,?,?,'marker')",
                         (event,'owner','default',conversation,now,now+300))
    api.activity.sync(api.mobile,api.native,'owner','basic','default')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT state FROM notices WHERE id='canonical-work'").fetchone()[0]=='pending'
        assert conn.execute("SELECT state FROM notices WHERE id='legacy-work'").fetchone()[0]=='settled'


def test_lifecycle_completion_without_remote_provenance_remains_unverified(canonical_call):
    api,_,cfg,_,_,rows,_=canonical_call
    observers=sys.modules['beacon_observers']
    with pytest.raises(LookupError):
        observers.completion(api.mobile,api.native,cfg,'default','same-chat',turn_id='turn-one',completed=True)
    rows['legacy-chat']={'id':'legacy-chat','user_id':'basic:owner','source':'android'}
    observers.completion(api.mobile,api.native,cfg,'default','legacy-chat',turn_id='turn-two',completed=True)
    with api.mobile.connect() as conn:
        assert [row[0] for row in conn.execute("SELECT conversation FROM notices WHERE kind='completion'")]==['legacy-chat']


def test_integration_inline_session_search_uses_bound_principal(canonical_call,monkeypatch,tmp_path):
    api,client,_,_,_,rows,server=canonical_call
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code==200
    call_id=response.json()['call_id']
    rows['legacy-other']={'id':'legacy-other','user_id':'basic:owner'}
    rows['foreign-canonical']={'id':'foreign-canonical','user_id':subject('https://other.example')}
    def search(db,**kwargs):
        return {'own':bool(db.get_session('same-chat')),'legacy':bool(db.get_session('legacy-other')),
                'foreign':bool(db.get_session('foreign-canonical'))}
    monkeypatch.setitem(sys.modules,'tools.session_search_tool',SimpleNamespace(session_search=search))
    result=api.voice_context.call_inline_tool('session_search','session_search',{'query':'synthetic'},
                                             ('owner','basic','default',call_id),'same-chat',tmp_path)
    assert result=={'own':True,'legacy':False,'foreign':False}


def test_lifecycle_existing_database_migrates_without_assigning_unknown_principals(tmp_path,monkeypatch):
    import importlib.util
    import sqlite3
    from pathlib import Path
    folder=tmp_path/'plugin-data'/'beacon';folder.mkdir(parents=True)
    db=folder/'mobile.sqlite'
    with sqlite3.connect(db) as conn:
        conn.executescript('''
            CREATE TABLE meta(key TEXT PRIMARY KEY,value BLOB);
            CREATE TABLE calls(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL,ended REAL,closed INTEGER DEFAULT 0,status TEXT DEFAULT 'pending',receipt TEXT DEFAULT '{}');
            CREATE TABLE notices(seq INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT UNIQUE,owner TEXT,profile TEXT,kind TEXT,conversation TEXT,created REAL,expires REAL,state TEXT DEFAULT 'pending',call_id TEXT,push TEXT DEFAULT 'pending');
            CREATE TABLE call_log(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL,expires REAL,state TEXT DEFAULT 'pending',call_id TEXT,reason TEXT DEFAULT '');
            INSERT INTO calls(id,owner,profile,conversation,created) VALUES('old-call','owner','default','old-chat',1);
            INSERT INTO call_log(id,owner,profile,conversation,created,expires) VALUES('old-log','owner','default','old-chat',1,2);
        ''')
    path=Path(__file__).resolve().parents[1]/'mobile.py'
    spec=importlib.util.spec_from_file_location('migration_mobile',path)
    mobile=importlib.util.module_from_spec(spec);spec.loader.exec_module(mobile)
    monkeypatch.setattr(mobile,'home',lambda:tmp_path)
    monkeypatch.setattr(mobile,'protect',lambda path:None)  # Existing private synthetic directory; no owner ACL calls.
    with mobile.connect() as conn:
        assert conn.execute("SELECT principal FROM calls WHERE id='old-call'").fetchone()[0]==''
        assert conn.execute("SELECT principal FROM call_log WHERE id='old-log'").fetchone()[0]==''
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,principal) VALUES('old-log','owner','default','incoming_call','old-chat',3,4,?)",(subject(),))
        assert conn.execute("SELECT principal FROM call_log WHERE id='old-log'").fetchone()[0]==''
    new=mobile.invitation('owner','default','new-chat','proof',principal=subject())
    with mobile.connect() as conn:
        assert conn.execute('SELECT principal FROM notices WHERE id=?',(new,)).fetchone()[0]==subject()
        assert conn.execute('SELECT principal FROM call_log WHERE id=?',(new,)).fetchone()[0]==subject()
        assert conn.execute("SELECT principal FROM call_log WHERE id='old-log'").fetchone()[0]==''


def test_integration_voice_history_requires_exact_subject(canonical_call):
    api,client,_,sessions,make_session,_,_=canonical_call
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert response.status_code==200
    call_id=response.json()['call_id']
    with api.mobile.connect() as conn:
        import json
        conn.execute('INSERT INTO timeline(call_id,event_id,digest,payload) VALUES(?,?,?,?)',
                     (call_id,'event','digest',json.dumps({'type':'transcript','role':'user','text':'synthetic','final':True,'turn_id':'1','at_ms':1})))
    path='/api/plugins/beacon/mobile/sessions/same-chat/voice-history?profile=default'
    own=client.get(path)
    assert own.status_code==200,own.text
    assert any(event['text']=='synthetic' for event in own.json()['events'])
    sessions[0]=make_session('https://other.example')
    assert client.get(path).status_code in (403,404)


def test_integration_wrong_issuer_cannot_fall_back_to_legacy_draft(canonical_call):
    api,client,_,sessions,make_session,_,server=canonical_call
    server._sessions['stale']={'session_key':'same-chat','user_id':'basic:owner','profile_home':None}
    server._session_auth_user_id=lambda record:record['user_id']
    sessions[0]=make_session('https://other.example')
    with pytest.raises(LookupError):
        api.voice_context._verify_saved_or_draft('owner','basic','default','same-chat',subject('https://other.example'))
    response=client.get('/api/plugins/beacon/mobile/voice/context?profile=default&session=same-chat')
    assert response.status_code in (403,404)


def test_lifecycle_global_update_notice_does_not_require_chat_ownership(canonical_call,monkeypatch):
    import time
    api,client,_,_,_,_,_=canonical_call
    monkeypatch.setattr(api,'watch',lambda *args:None)
    monkeypatch.setattr(api.mobile,'prune_history',lambda *args:None)
    monkeypatch.setattr(sys.modules['beacon_callbacks'],'prune_expired',lambda *args:None)
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES('update','owner','default','app_update','release-1',?,?)",(time.time(),time.time()+300))
    assert any(row['id']=='update' for row in client.get('/api/plugins/beacon/mobile/notifications/events').json()['events'])


@pytest.mark.parametrize('prior_confirmation',[False,True])
def test_lifecycle_unproven_canonical_admission_cannot_settle_job(canonical_call,monkeypatch,prior_confirmation):
    api,_,_,_,_,_,_=canonical_call
    continuation=sys.modules['beacon_voice_continuation']
    job={'owner':'owner','provider':'basic','profile':'default','stored':'same-chat','runtime':'same-chat',
         'principal':subject(),'request':'synthetic-request','admission':'','submission_digest':'digest'}
    if prior_confirmation:
        # A previous remote input does not attach a subsequently prepared job by receipt lookup.
        assert api.mobile.confirm_remote_admission({'admission_id':'old-admission','owner':'owner','provider':'basic',
            'profile':'default','home':api.native.profile_fingerprint('default'),'session':'same-chat',
            'request':'synthetic-request','principal':subject(),'epoch':1,'wire_digest':'a'*64,'payload_digest':'b'*64})
    import hermes_state_runtime
    monkeypatch.setattr(hermes_state_runtime,'find_session_admission',lambda *a,**kw:{'admission_id':'unproven-admission'})
    with pytest.raises(LookupError,match='provenance'):
        continuation.native_receipt(job)


def test_lifecycle_unsubmitted_canonical_preparation_can_leave_active_capacity(canonical_call,monkeypatch):
    import time
    import hermes_state_runtime
    api, _, _, _, _, _, _ = canonical_call
    continuation = sys.modules['beacon_voice_continuation']
    call = api.mobile.reserve('owner', 'default', 'same-chat', principal=subject())['call_id']
    before = time.time() - continuation.PARK_AFTER_SECONDS - 10
    with api.mobile.connect() as conn:
        conn.execute("UPDATE calls SET closed=1,status='saved',created=?,ended=? WHERE id=?", (before, before, call))
        conn.execute('''INSERT INTO jobs(id,call_id,owner,provider,profile,stored,runtime,request,principal,
            submission_digest,epoch,status,history_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)''',
            ('unsubmitted-job',call,'owner','basic','default','same-chat','same-chat','never-submitted',subject(),
             'synthetic-digest','canonical','awaiting_admission',before))
    reads = []
    def absent(*args, **kwargs):
        reads.append(kwargs)
        return None
    monkeypatch.setattr(hermes_state_runtime, 'find_session_admission', absent)
    continuation.sync(api.mobile, api.native, 'owner', 'basic', 'default')
    with api.mobile.connect() as conn:
        job = dict(conn.execute('SELECT * FROM jobs WHERE id=?', ('unsubmitted-job',)).fetchone())
        assert job['parked'] == 1 and job['status'] == 'awaiting_admission'
        assert not job['remote_authorization'] and not job['admission']
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id=?', (job['id'],)).fetchone()[0] == 0
    execution=api.native.execution_subject('owner','basic',subject())
    expected=[execution] if execution==subject() else [execution,subject()]
    assert reads == [{'principal_id': p, 'session_id': 'same-chat', 'request_id': 'never-submitted'} for p in expected]


@pytest.mark.parametrize('owner',[subject('https://other.example'),'',None])
def test_lifecycle_foreign_or_unknown_job_owner_refuses_before_admission_read(canonical_call,monkeypatch,owner):
    import hermes_state_runtime
    api,_,_,_,_,rows,_=canonical_call
    rows['same-chat']['user_id']=owner
    monkeypatch.setattr(hermes_state_runtime,'find_session_admission',lambda *a,**kw:pytest.fail('Unowned admission was read'))
    with pytest.raises(LookupError,match='owned'):
        sys.modules['beacon_voice_continuation'].native_receipt({'owner':'owner','provider':'basic','profile':'default',
            'stored':'same-chat','principal':subject(),'request':'request','admission':''})


def test_lifecycle_failed_call_memory_sweep_preserves_original_principal(canonical_call,monkeypatch):
    import time
    api,_,_,_,_,_,_=canonical_call
    call=api.mobile.reserve('owner','default','same-chat',principal=subject())['call_id']
    with api.mobile.connect() as conn:
        conn.execute("UPDATE calls SET closed=1,status='failed',ended=? WHERE id=?",(time.time(),call))
        conn.execute('INSERT INTO timeline(call_id,event_id,digest,payload) VALUES(?,?,?,?)',
                     (call,'transcript','digest','{"type":"transcript"}'))
    seen=[]
    monkeypatch.setattr(api,'hand_over',lambda owner,provider,row:seen.append(row))
    api.sweep_unended_calls('owner','basic','default')
    assert [row['principal'] for row in seen]==[subject()]


def test_lifecycle_receipt_binding_requires_exact_native_acceptance(canonical_call,monkeypatch,tmp_path):
    import hashlib
    from hermes_state_runtime import find_session_admission
    import hermes_state_runtime
    api,_,_,_,_,rows,server=canonical_call
    monkeypatch.setattr(server,'_profile_home',lambda profile:tmp_path)
    admission={'admission_id':'admission-one','principal_id':subject(),'target_session_id':'same-chat',
               'request_id':'request-one','owner_epoch':7,'generation':None,'status':'queued',
               'payload':{'text':'synthetic task'}}
    monkeypatch.setattr(hermes_state_runtime,'find_session_admission',lambda *args,**kwargs:admission)
    home=api.native.home_fingerprint(tmp_path)
    pending={'owner':'owner','provider':'basic','profile':'default','principal':subject(),'home':home,
             'session':'same-chat','request':'request-one','payload_digest':api.native._payload_fingerprint(admission['payload']),
             'wire_digest':hashlib.sha256(b'synthetic wire').hexdigest()}
    receipt={'admission_id':'admission-one','ref':{'profile_id':str(tmp_path),'session_id':'same-chat'},
             'authority_epoch':7,'execution_generation':None,'status':'queued'}
    assert api.native.confirm_remote_receipt(api.mobile,pending,receipt)
    with api.mobile.connect() as conn:
        saved=dict(conn.execute("SELECT * FROM remote_admissions WHERE admission_id='admission-one'").fetchone())
    assert saved['principal']==subject() and saved['generation']==0
    assert 'synthetic task' not in repr(saved) and 'synthetic wire' not in repr(saved)
    bad=dict(pending,request='different')
    assert not api.native.confirm_remote_receipt(api.mobile,bad,receipt)
    admission['payload']['surface_v1']={'surface':'voice-live','voice_context':'different context'}
    assert not api.native.confirm_remote_receipt(api.mobile,pending,receipt)
    admission['payload'].pop('surface_v1')
    assert api.native.confirm_remote_receipt(api.mobile,dict(pending,wire_digest='b'*64),receipt)
    admission.update(status='started',generation=3)
    assert api.native.confirm_remote_start(api.mobile,'admission-one','default','same-chat',7,3)
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT generation FROM remote_admissions WHERE admission_id='admission-one'").fetchone()[0]==3
    admission['generation']=4
    assert not api.native.confirm_remote_start(api.mobile,'admission-one','default','same-chat',7,4)


@pytest.mark.parametrize('remote_response',[True,False])
@pytest.mark.parametrize('row_stamp',['login','legacy','bound_host'])
def test_integration_relay_native_admission_and_durable_same_chat_callback(canonical_call,monkeypatch,tmp_path,remote_response,row_stamp):
    import asyncio
    from contextlib import contextmanager
    import json
    from hermes_state import SessionDB
    from hermes_state_runtime import begin_runtime_epoch,claim_session_input,settle_session_input
    from gateway.session_contract import SessionRef
    from gateway.session import SessionSource
    from gateway.config import Platform
    api,client,_,sessions,_,_,server=canonical_call
    transport=sys.modules['beacon_canonical_transport']
    continuation=sys.modules['beacon_voice_continuation']
    db=SessionDB(tmp_path/'native.sqlite')
    execution=api.native.execution_subject('owner','basic',subject())
    row_owner={'login':subject(),'legacy':'basic:owner','bound_host':execution}[row_stamp]
    db.create_session('same-chat','local',user_id=row_owner)
    epoch=begin_runtime_epoch(db,instance_id='synthetic-instance')
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),_draining=False,_voice_key_for_source=lambda source:'synthetic-route',
                           session_runtime_descriptor={'state':'ready','instance_id':'synthetic-instance','authority_epoch':epoch})
    authority=SessionAuthority(runner,profile_id=str(tmp_path),instance_id='synthetic-instance',db=db,epoch=epoch)
    runner.session_authority=authority
    live=LiveSession(SessionSource(platform=Platform.LOCAL,chat_id='same-chat',user_id=execution if row_stamp=='bound_host' else subject(),chat_type='dm'),'synthetic-route')
    authority.sessions['same-chat']=live
    identity={'provider':'basic','issuer':sessions[0].issuer,'user_id':'owner'}
    peer=SimpleNamespace(auth_identity=identity)
    connection=AuthorityConnection(authority,peer,identity,operator=True)
    connection.subscriptions['same-chat']='subscription'
    live.subscribers['subscription']=connection.actor
    # Exercise native authorization, normalization and admission; prevent any agent execution.
    monkeypatch.setattr(authority,'_schedule_admitted',lambda *args:None)
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    monkeypatch.setattr(server,'_profile_home',lambda profile:tmp_path)
    @contextmanager
    def profile_db(params,**kwargs):
        assert params['profile']=='default'
        yield db
    monkeypatch.setattr(server,'_profile_db',profile_db)
    if row_stamp=='bound_host':
        assert api.native.bind_conversation(api.mobile,'owner','basic','default','same-chat',subject(),execution,'canonical','create-same-chat')
    request=SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner,session_authority=authority)),
                            state=SimpleNamespace(session=sessions[0]))
    monkeypatch.setattr(transport,'verify_live_session',lambda req,p,s,r:transport.canonical_live_proof(request,p,tmp_path,s,r,'a'*64))
    monkeypatch.setattr(api,'activate',lambda *args:None)
    created=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'same-chat'})
    assert created.status_code==200,created.text
    call_id=created.json()['call_id']
    work=client.post('/api/plugins/beacon/mobile/calls/'+call_id+'/work',json={
        'stored_session_id':'same-chat','runtime_session_id':'same-chat','request_id':'exact-request',
        'text':'Synthetic same-chat task','callback':True})
    assert work.status_code==200,work.text
    job_id=work.json()['job_id']
    wire={'jsonrpc':'2.0','id':11,'method':'prompt.submit','params':{'profile':'default','session_id':'same-chat',
          'submission_id':'exact-request','text':'Synthetic same-chat task','surface':'voice-live','voice_context':'Synthetic context'}}
    raw=json.dumps(wire,indent=1)
    binding={'owner':'owner','provider':'basic','principal':subject(),'home':transport.home_digest(tmp_path)}
    async def exercise():
        outgoing=asyncio.Queue();incoming=asyncio.Queue();received=asyncio.Queue()
        class Client:
            async def receive(self): return await outgoing.get()
            async def send_text(self,value): await received.put(value)
        class Upstream:
            async def recv(self): return await incoming.get()
            async def send(self,value):
                assert value==raw
                params=dict(json.loads(value)['params']);params.pop('profile')
                receipt=await connection.submit(SessionRef(str(tmp_path),'same-chat'),params)
                response={'jsonrpc':'2.0','id':11,'result':receipt}
                if not remote_response:
                    # A receipt lookup response carries no original remote submit authorization.
                    response['id']=99
                await incoming.put(json.dumps(response))
        task=asyncio.create_task(transport.relay_sockets(Client(),Upstream(),'default',api.mobile,binding))
        try:
            await outgoing.put({'type':'websocket.receive','text':raw})
            response=json.loads(await asyncio.wait_for(received.get(),5))
            claimed=claim_session_input(db,epoch=epoch,session_id='same-chat')
            authority.executing['same-chat']=claimed['generation']
            start={'method':'event','params':{'type':'message.start','session_id':'same-chat','admission_id':claimed['admission_id'],
                   'authority_epoch':epoch,'execution_generation':claimed['generation'],'payload':{}}}
            await incoming.put(json.dumps(start))
            assert json.loads(await asyncio.wait_for(received.get(),5))==start
            return response,claimed
        finally:
            task.cancel()
            results=await asyncio.gather(task,return_exceptions=True)
            if isinstance(results[0],Exception): raise results[0]
    try:
        response,claimed=asyncio.run(exercise())
        assert response['result']['ref']['session_id']=='same-chat'
        import time
        with api.mobile.connect() as conn:
            conn.execute("UPDATE calls SET closed=1,status='saved',ended=? WHERE id=?",(time.time(),call_id))
        from gateway.session_ingress import viewer_turn,executing_admission
        import hermes_constants
        from hermes_cli import profiles
        monkeypatch.setattr(hermes_constants,'get_hermes_home',lambda:tmp_path)
        monkeypatch.setattr(profiles,'profile_matches_home',lambda profile,home:profile=='default' and home==tmp_path)
        # Eighteen hours after hang-up: a matching active native turn still holds its proof.
        future=time.time()+18*3600
        clock=SimpleNamespace(time=lambda:future,time_ns=time.time_ns,monotonic=time.monotonic,sleep=time.sleep)
        for module in (api,api.mobile,continuation): monkeypatch.setattr(module,'time',clock)
        active=executing_admission.set(True);viewer=viewer_turn.set(live)
        try:
            assert api.native.current_principal('owner','basic','default','same-chat')==(subject() if remote_response else None)
        finally:
            viewer_turn.reset(viewer);executing_admission.reset(active)
        continuation.sync(api.mobile,api.native,'owner','basic','default')
        with api.mobile.connect() as conn:
            job=dict(conn.execute('SELECT * FROM jobs WHERE id=?',(job_id,)).fetchone())
            assert bool(job['remote_authorization']) is remote_response
            # Restart/expiry: only the job's durable exact authorization remains.
            conn.execute('DELETE FROM remote_admissions')
        settle_session_input(db,epoch=epoch,admission_id=claimed['admission_id'],generation=claimed['generation'],outcome='completed',
                             result={'result':{'completed':True,'final_response':'Synthetic committed result'},'usage':{}})
        api.watch('owner','basic','default')
        api.watch('owner','basic','default')
        with api.mobile.connect() as conn:
            job=dict(conn.execute('SELECT * FROM jobs WHERE id=?',(job_id,)).fetchone())
            notices=conn.execute('SELECT principal,conversation FROM notices WHERE job_id=?',(job_id,)).fetchall()
        if remote_response:
            assert job['status']=='callback_pending' and job['result']=='Synthetic committed result'
            assert [tuple(row) for row in notices]==[(subject(),'same-chat')]
            assert json.loads(job['remote_authorization'])['principal']==subject()
        else:
            assert job['status']=='awaiting_admission' and not notices
    finally:
        db.close()
