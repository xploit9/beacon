"""Beacon provenance against real native identities and isolated SQLite stores."""
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import sys
import threading
from types import SimpleNamespace

import pytest
from test_mobile import harness
import tui_gateway as native_tui
from gateway.session_authority import SessionAuthority
from tui_gateway.session_transports import _transport_auth_user_id


@pytest.fixture
def bound(harness,monkeypatch,tmp_path):
    from gateway.session_identity import authenticated_subject
    from hermes_state import SessionDB
    api,client,cfg,_,app=harness
    spec=importlib.util.spec_from_file_location('compat_native',Path(api.native.__file__))
    native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
    monkeypatch.setitem(sys.modules,'beacon_native',native)
    monkeypatch.setattr(api,'native',native)
    monkeypatch.setattr(api.voice_context,'native',native)
    db=SessionDB(tmp_path/'state.db')
    @contextmanager
    def profile_db(params,**kwargs):
        assert params['profile']=='default'
        yield db
    server=SimpleNamespace(_profile_db=profile_db,_profile_home=lambda p:tmp_path,
        _sessions_lock=threading.RLock(),_sessions={},_session_auth_user_id=lambda r:r.get('auth_user_id'))
    monkeypatch.setitem(sys.modules,'tui_gateway',native_tui)
    monkeypatch.setattr(native_tui,'server',server,raising=False)
    login=authenticated_subject({'provider':'basic','issuer':'https://one.synthetic','user_id':'owner'})
    execution=native.execution_subject('owner','basic',login)
    yield SimpleNamespace(api=api,client=client,cfg=cfg,app=app,native=native,db=db,server=server,home=tmp_path,login=login,execution=execution,monkeypatch=monkeypatch)
    db.close()


def test_host_stamp_without_beacon_provenance_is_unresolved(bound):
    b=bound;b.db.create_session('historical','local',user_id=b.execution)
    # On old native the execution subject itself still proves the exact login.
    if b.execution==b.login:
        assert b.native.verify('owner','basic','default','historical',principal=b.login)
    else:
        with pytest.raises(LookupError): b.native.verify('owner','basic','default','historical',principal=b.login)
    with pytest.raises(LookupError): b.native.verify('owner','basic','default','historical')


def test_native_binding_keeps_login_and_execution_separate_and_immutable(bound):
    from gateway.session_identity import authenticated_subject
    b=bound;b.db.create_session('created','local',user_id=b.execution)
    assert b.native.bind_conversation(b.api.mobile,'owner','basic','default','created',b.login,b.execution,'canonical','create-one')
    assert b.native.verify('owner','basic','default','created',principal=b.login)['user_id']==b.execution
    foreign=authenticated_subject({'provider':'basic','issuer':'https://two.synthetic','user_id':'owner'})
    assert not b.native.bind_conversation(b.api.mobile,'owner','basic','default','created',foreign,
        b.native.execution_subject('owner','basic',foreign),'canonical','create-two')
    with pytest.raises(LookupError): b.native.verify('owner','basic','default','created',principal=foreign)
    saved=b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),'created',b.login)
    assert saved['principal']==b.login and saved['execution']==b.execution and saved['request']=='create-one'
    assert b.api.mobile.native_conversation('owner','basic','other',saved['home'],'created',b.login) is None
    assert b.api.mobile.native_conversation('owner','basic','default','a'*64,'created',b.login) is None


def test_verified_issuer_reaches_legacy_transport(bound):
    b=bound
    transport=b.native.ReplyTransport('owner','basic',b.login)
    assert transport.auth_identity=={'provider':'basic','issuer':'https://one.synthetic','user_id':'owner'}
    for foreign in (b.execution,'sid:SUPPLIED',None):
        if foreign is None or foreign==b.login: continue
        with pytest.raises(LookupError): b.native.ReplyTransport('owner','basic',foreign)


def test_ownership_lookup_never_creates_or_migrates_mobile_storage(bound):
    import sqlite3
    b=bound;folder=b.home/'plugin-data/beacon';database=folder/'mobile.sqlite'
    assert not database.exists()
    assert b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),'missing',b.login) is None
    assert not folder.exists()
    folder.mkdir(parents=True)
    with sqlite3.connect(database) as conn: conn.execute('CREATE TABLE untouched(value TEXT)')
    before=database.read_bytes()
    assert b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),'missing',b.login) is None
    assert database.read_bytes()==before
    with sqlite3.connect(database) as conn:
        assert [r[0] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")]==['untouched']


def native_creation(b,request='create-exact',beacon=True):
    # Trusted policies bypass model resolution; keep importing the turn runner out of this contract.
    b.monkeypatch.setitem(sys.modules,'gateway.run',SimpleNamespace(
        _resolve_gateway_model=lambda config:pytest.fail('Creation unexpectedly resolved a model')))
    from gateway.session_authority import SessionAuthority
    from gateway.session_controls import AuthorityConnection
    from gateway.session_local import stage_local_session
    from gateway.session_policy import LocalSessionPolicy
    from hermes_state_local import commit_local_session
    from hermes_state_runtime import begin_runtime_epoch
    epoch=begin_runtime_epoch(b.db,instance_id='synthetic-instance')
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),_draining=False,
        session_store=SimpleNamespace(_generate_session_key=lambda source:'local:'+source.chat_id),
        session_runtime_descriptor={'state':'ready','instance_id':'synthetic-instance','authority_epoch':epoch})
    authority=SessionAuthority(runner,profile_id=str(b.home),instance_id='synthetic-instance',db=b.db,epoch=epoch)
    runner.session_authority=authority
    identity=b.native.login_identity('owner','basic',b.login)
    peer=SimpleNamespace(auth_identity=identity)
    connection=AuthorityConnection(authority,peer,identity,operator=True)
    policy=LocalSessionPolicy(source='desktop',platform='local',cwd=str(b.home),model='gpt-6-luna',
        toolsets=(),config_json='{}',request_json='{}',terminal_json='{}')
    binding={'owner':'owner','provider':'basic','principal':b.login,'home':b.native.home_fingerprint(b.home),'epoch':epoch}
    pending=b.native.prepare_created(b.api.mobile,binding,'default',request) if beacon else None
    if beacon: assert pending is not None
    staged=stage_local_session(authority,connection.actor,{'request_id':request},trusted_policy=policy)
    saved=commit_local_session(b.db,epoch=epoch,receipt=staged)
    response={'session_id':saved['session_id'],'stored_session_id':saved['session_id'],'authority_epoch':epoch}
    return authority,connection,pending,response


def test_real_native_creation_binds_only_the_exact_response_and_retries_do_not_rebind(bound):
    b=bound;authority,connection,pending,response=native_creation(b)
    sid=response['session_id']
    assert b.db.get_session(sid)['user_id']==connection.actor.subject==b.execution
    assert not b.native.confirm_created(b.api.mobile,dict(pending,request='different'),response)
    assert not b.native.confirm_created(b.api.mobile,dict(pending,home='a'*64),response)
    assert not b.native.confirm_created(b.api.mobile,pending,dict(response,authority_epoch=authority.epoch+1))
    assert b.native.confirm_created(b.api.mobile,pending,response)
    assert b.native.confirm_created(b.api.mobile,pending,response)
    assert b.native.verify('owner','basic','default',sid,principal=b.login)['id']==sid
    with b.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM native_conversations').fetchone()[0]==1
        assert conn.execute('SELECT COUNT(*) FROM remote_admissions').fetchone()[0]==0


@pytest.mark.parametrize('failure',['none','reservation','replacement','foreign-transport'])
def test_legacy_create_uses_real_transport_stamp_and_never_replays(bound,failure):
    from tui_gateway.session_transports import _transport_auth_user_id
    b=bound;events=[]
    b.server._transport_auth_user_id=_transport_auth_user_id
    b.server._session_transport_contains=lambda record,transport:record.get('transport') is transport
    def dispatch(frame,transport):
        events.append(frame['method'])
        if frame['method']=='session.close': return {'id':frame['id'],'result':{}}
        assert transport.auth_identity==b.native.login_identity('owner','basic',b.login)
        record={'session_key':'legacy-stored','profile_home':str(b.home),
            'auth_user_id':_transport_auth_user_id(transport),'transport':transport}
        if failure=='foreign-transport': record['transport']=object()
        b.server._sessions['legacy-runtime']=record
        return {'id':frame['id'],'result':{'session_id':'legacy-runtime','stored_session_id':'legacy-stored'}}
    def persist(record):
        if failure=='reservation': return False
        b.db.create_session('legacy-stored','android',user_id=record['auth_user_id'])
        if failure=='replacement': b.server._sessions['legacy-runtime']=dict(record)
        return True
    b.server.dispatch=dispatch;b.server._ensure_session_db_row=persist
    if failure=='none':
        assert b.native.create('owner','basic','default',b.login)=='legacy-stored'
        assert b.native.verify('owner','basic','default','legacy-stored','legacy-runtime',False,b.login)
        saved=b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),'legacy-stored',b.login)
        assert saved['kind']=='legacy' and saved['runtime']=='legacy-runtime' and saved['principal']==b.login
    else:
        with pytest.raises(RuntimeError): b.native.create('owner','basic','default',b.login)
        assert b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),'legacy-stored',b.login) is None
    assert events==(['session.create','session.close'] if failure=='reservation' else ['session.create'])


@pytest.mark.parametrize('mode',['matched','lookup','error','resume'])
def test_relay_creation_requires_original_create_response(bound,mode):
    import asyncio
    import json
    b=bound;relay=sys.modules['beacon_canonical_transport'];seen=[];response_holder=[]
    async def exercise():
        outgoing=asyncio.Queue();incoming=asyncio.Queue();received=asyncio.Queue()
        class Client:
            async def receive(self): return await outgoing.get()
            async def send_text(self,value): await received.put(value)
        class Upstream:
            async def recv(self): return await incoming.get()
            async def send(self,raw):
                frame=json.loads(raw);seen.append(frame)
                _,_,pending,response=native_creation(b,frame['params'].get('request_id','unrelated'))
                response_holder.append(response)
                wire={'jsonrpc':'2.0','id':99 if mode=='lookup' else frame['id'],'result':response}
                if mode=='error': wire={'jsonrpc':'2.0','id':frame['id'],'error':{'code':4001}}
                await incoming.put(json.dumps(wire))
        binding={'owner':'owner','provider':'basic','principal':b.login,'home':b.native.home_fingerprint(b.home)}
        task=asyncio.create_task(relay.relay_sockets(Client(),Upstream(),'default',b.api.mobile,binding))
        try:
            params={'profile':'default'}
            if mode=='resume': params['session_id']='historical'
            await outgoing.put({'type':'websocket.receive','text':json.dumps({'jsonrpc':'2.0','id':1,
                'method':'session.resume' if mode=='resume' else 'session.create','params':params})})
            await asyncio.wait_for(received.get(),3)
            await outgoing.put({'type':'websocket.disconnect'})
            await asyncio.wait_for(task,3)
        finally:
            task.cancel();await asyncio.gather(task,return_exceptions=True)
    asyncio.run(exercise())
    assert len(seen)==1
    if mode!='resume': assert len(seen[0]['params']['request_id'])==64
    sid=response_holder[0]['session_id']
    saved=b.api.mobile.native_conversation('owner','basic','default',b.native.home_fingerprint(b.home),sid,b.login)
    assert bool(saved)==(mode=='matched')
    if saved: assert saved['request']==seen[0]['params']['request_id'] and saved['kind']=='canonical'


def test_host_context_history_and_search_share_passive_provenance(bound):
    b=bound
    for sid,subject in (('owned',b.execution),('unbound',b.execution),('legacy','basic:owner'),('foreign','basic:other')):
        b.db.create_session(sid,'local',user_id=subject)
    assert b.native.bind_conversation(b.api.mobile,'owner','basic','default','owned',b.login,b.execution,'canonical','create-exact')
    def forbidden(*args,**kwargs): pytest.fail('Passive read attempted an effect')
    b.monkeypatch.setattr(b.native,'rpc',forbidden)
    b.monkeypatch.setattr(b.api.mobile,'bind_native_conversation',forbidden)
    b.server._ensure_session_db_row=forbidden
    b.server._history_to_messages=lambda history,**kwargs:history
    assert b.native.conversation_snapshot('owner','basic','default','owned',b.login)['passive_read'] is True
    assert b.api.voice_context.history('owner','basic','default','owned',b.login)==[]
    from gateway.session_identity import authenticated_subject
    other=authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'})
    with pytest.raises(LookupError): b.api.voice_context.history('owner','basic','default','owned',other)
    check=lambda sid,row:b.native.owns_row('owner','basic','default',sid,row,b.login)
    sessions=b.api.voice_context.OwnedSessions(b.db,b.login,check)
    assert sessions.get_session('owned')
    assert sessions.get_session('foreign') is None
    if b.execution!=b.login:
        assert sessions.get_session('unbound') is None
        with pytest.raises(LookupError): b.native.conversation_snapshot('owner','basic','default','unbound',b.login)


def test_native_tool_context_uses_execution_owner_and_restores_login_binding(bound):
    from gateway import session_context
    from gateway.session_identity import authenticated_subject
    b=bound;b.db.create_session('owned','local',user_id=b.execution,model_config={'yolo_mode':True})
    b.native.bind_conversation(b.api.mobile,'owner','basic','default','owned',b.login,b.execution,'canonical','create-tool')
    backend=sys.modules['beacon_gateway_tools'].NativeMcp();seen=[]
    b.monkeypatch.setattr(b.api.proactive,'current_profile',lambda cfg:'default')
    b.api.mobile.settings()['callback_tool_enabled']=True
    spec=importlib.util.spec_from_file_location('compat_callback',Path(b.api.__file__).parents[1]/'__init__.py')
    callback=importlib.util.module_from_spec(spec);spec.loader.exec_module(callback)
    def handler(name,args,**kwargs):
        import json
        seen.append((name,session_context.get_session_env('HERMES_SESSION_USER_ID'),
            b.native.current_principal('owner','basic','default','owned')))
        assert b.api.phone_tools.authorize('owned')==('owner','default')
        assert sys.modules['beacon_email_drafts'].authorize('owned')==('owner','default')
        assert json.loads(callback.call_user({'reason':'Synthetic requested callback'},session_id='owned'))['status']=='pending'
        return '{}'
    b.monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(handle_function_call=handler))
    tool={'_kind':'hermes','_raw':'read_file','read_only':True}
    scope=('owner','basic','default','call-exact')
    assert backend.approval_source(tool,{},scope,'owned',b.home,b.login)=='chat_yolo'
    backend.execute(tool,{},scope,False,session_id='owned',principal=b.login)
    assert seen==[('read_file',b.execution,b.login)] and b.native.call_principal.get() is None
    other=authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'})
    with pytest.raises(LookupError): backend.execute(tool,{},scope,False,session_id='owned',principal=other)
    assert len(seen)==1 and b.native.call_principal.get() is None
    with b.api.mobile.connect() as conn:
        assert [r[0] for r in conn.execute("SELECT principal FROM notices WHERE kind='incoming_call'")]==[b.login]
    with pytest.raises(b.api.phone_tools.Refusal): b.api.phone_tools.authorize('owned')


@pytest.mark.parametrize('origin',['call','admission','historical-create'])
def test_old_proven_login_binding_survives_execution_subject_update(bound,origin):
    b=bound;b.db.create_session('existing','local',user_id=b.execution)
    home=b.native.home_fingerprint(b.home)
    if origin=='call':
        b.api.mobile.reserve('owner','default','existing',principal=b.login,native_home=home,execution=b.login)
    elif origin=='admission':
        assert b.api.mobile.confirm_remote_admission({'admission_id':'original','owner':'owner','provider':'basic',
            'profile':'default','home':home,'session':'existing','request':'original-submit','principal':b.login,
            'epoch':1,'wire_digest':'a'*64,'payload_digest':'b'*64})
    else:
        assert b.api.mobile.bind_native_conversation({'owner':'owner','provider':'basic','profile':'default','home':home,
            'session':'existing','principal':b.login,'execution':b.login,'kind':'canonical','request':'original-create','runtime':''})
    before=(b.home/'plugin-data/beacon/mobile.sqlite').read_bytes()
    assert b.native.verify('owner','basic','default','existing',principal=b.login)['id']=='existing'
    assert (b.home/'plugin-data/beacon/mobile.sqlite').read_bytes()==before
    from gateway.session_identity import authenticated_subject
    other=authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'})
    with pytest.raises(LookupError): b.native.verify('owner','basic','default','existing',principal=other)


def test_native_tool_receipt_retains_login_and_never_replays_on_execution_owner(bound):
    import json
    import time
    from fastapi import HTTPException
    from gateway.session_identity import authenticated_subject
    b=bound;b.db.create_session('owned','local',user_id=b.execution)
    b.native.bind_conversation(b.api.mobile,'owner','basic','default','owned',b.login,b.execution,'canonical','create-once')
    call=b.api.mobile.reserve('owner','default','owned',principal=b.login,
        native_home=b.native.home_fingerprint(b.home),execution=b.execution)['call_id']
    module=sys.modules['beacon_gateway_tools'];backend=module.NativeMcp();executed=[]
    tool={'tool_id':'c'*64,'name':'read_file','description':'Read a synthetic file','_kind':'hermes','_raw':'read_file',
          'read_only':True,'parameters':{'type':'object','properties':{'path':{'type':'string'}},'required':['path']}}
    backend.cache_key=lambda:None;backend.catalog=lambda discover=True:[tool]
    b.monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda *args,**kwargs:executed.append(args) or '{}'))
    gateway=module.Gateway(b.api.mobile,lambda profile,fn:fn(b.home),backend,sanitizer=lambda value,limit:str(value)[:limit])
    scope=('owner','basic','default',call);request={'request_id':'one','tool_id':tool['tool_id'],'arguments':{'path':'synthetic'}}
    gateway.invoke(scope,request,'owned',b.login)
    deadline=time.monotonic()+3
    while gateway.status(scope,'one')['status']=='running' and time.monotonic()<deadline: time.sleep(.01)
    assert gateway.status(scope,'one')['status']=='completed'
    assert gateway.invoke(scope,request,'owned',b.login)['status']=='completed'
    for principal in (authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'}),b.execution):
        if principal==b.login: continue
        with pytest.raises(HTTPException): gateway.invoke(scope,request,'owned',principal)
    with pytest.raises(HTTPException): gateway.invoke(scope,{**request,'arguments':{'path':'changed'}},'owned',b.login)
    with b.api.mobile.connect() as conn:
        receipt=json.loads(conn.execute('SELECT payload FROM tool_receipts WHERE request_id=?',('one',)).fetchone()[0])
        assert receipt['_principal']==b.login
    assert len(executed)==1


def test_failed_native_setup_does_not_leak_identity_to_the_next_tool(bound):
    from gateway import session_context
    from tools import approval
    b=bound;b.db.create_session('owned','local',user_id=b.execution)
    b.native.bind_conversation(b.api.mobile,'owner','basic','default','owned',b.login,b.execution,'canonical','create-context')
    before=session_context.get_session_env('HERMES_SESSION_USER_ID')
    def unavailable(*args,**kwargs): raise RuntimeError('synthetic native setup failure')
    b.monkeypatch.setattr(approval,'register_gateway_notify',unavailable)
    tool={'name':'synthetic_mcp','_raw':'synthetic_mcp','_server':'synthetic','_trust':'trusted','read_only':True}
    with pytest.raises(RuntimeError,match='synthetic native setup failure'):
        sys.modules['beacon_gateway_tools'].NativeMcp().execute(tool,{},('owner','basic','default','call'),False,'owned',b.login)
    assert b.native.call_principal.get() is None
    assert session_context.get_session_env('HERMES_SESSION_USER_ID')==before


@pytest.mark.parametrize('change',['none','foreign-segment','replacement'])
def test_bound_legacy_compression_uses_real_lineage_and_rechecks_record(bound,change):
    b=bound;stamp=_transport_auth_user_id(b.native.ReplyTransport('owner','basic',b.login))
    b.db.create_session('root','android',user_id=stamp)
    b.db.end_session('root','compression')
    b.db.create_session('tip','android',user_id='basic:other' if change=='foreign-segment' else stamp,parent_session_id='root')
    record={'session_key':'tip','profile_home':str(b.home),'auth_user_id':stamp}
    b.server._sessions['runtime']=record
    b.native.bind_conversation(b.api.mobile,'owner','basic','default','root',b.login,stamp,'legacy','create-root','runtime')
    assert b.db.get_compression_chain('root')==['root','tip']
    if change=='replacement':
        original=b.db.get_compression_chain
        def replaced(sid):
            result=original(sid);b.server._sessions['runtime']=dict(record);return result
        b.monkeypatch.setattr(b.db,'get_compression_chain',replaced)
    if change=='none':
        assert b.native.verify('owner','basic','default','root','runtime',False,b.login)['id']=='root'
    else:
        with pytest.raises(LookupError if change=='foreign-segment' else RuntimeError):
            b.native.verify('owner','basic','default','root','runtime',False,b.login)


def test_callback_hook_does_not_reuse_ownership_after_a_row_changes(bound):
    b=bound;b.db.create_session('changing','local',user_id='basic:owner')
    spec=importlib.util.spec_from_file_location('compat_changing_callback',Path(b.api.__file__).parents[1]/'__init__.py')
    callback=importlib.util.module_from_spec(spec);spec.loader.exec_module(callback)
    b.monkeypatch.setattr(b.native,'current_principal',lambda *args:None)
    callback._verify_owned('owner','basic','default','changing')
    b.db._execute_write(lambda conn:conn.execute("UPDATE sessions SET user_id='basic:other' WHERE id='changing'"))
    with pytest.raises(LookupError): callback._verify_owned('owner','basic','default','changing')


def test_historical_native_create_retry_never_acquires_a_beacon_binding(bound):
    b=bound;_,_,_,response=native_creation(b,'historical-request',beacon=False)
    binding={'owner':'owner','provider':'basic','principal':b.login,'home':b.native.home_fingerprint(b.home)}
    before=(b.home/'plugin-data/beacon/mobile.sqlite').read_bytes() if (b.home/'plugin-data/beacon/mobile.sqlite').exists() else None
    pending=b.native.prepare_created(b.api.mobile,binding,'default','historical-request')
    if b.execution!=b.login: assert pending is None
    assert b.api.mobile.native_conversation('owner','basic','default',binding['home'],response['session_id'],b.login) is None
    if pending is not None: assert not b.native.confirm_created(b.api.mobile,pending,response)
    after=(b.home/'plugin-data/beacon/mobile.sqlite').read_bytes() if (b.home/'plugin-data/beacon/mobile.sqlite').exists() else None
    assert before==after


def test_creation_intent_is_non_authorizing_and_cannot_be_claimed_by_another_login(bound):
    from gateway.session_identity import authenticated_subject
    b=bound;home=b.native.home_fingerprint(b.home)
    binding={'owner':'owner','provider':'basic','principal':b.login,'home':home}
    pending=b.native.prepare_created(b.api.mobile,binding,'default','same-create-request')
    assert pending and b.api.mobile.native_conversation('owner','basic','default',home,pending['session'],b.login) is None
    assert b.api.mobile.native_conversation('owner','basic','default',home,pending['session'],b.login,True)['kind']=='pending'
    with pytest.raises(LookupError): b.native.verify('owner','basic','default',pending['session'],principal=b.login)
    other=authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'})
    foreign=b.native.prepare_created(b.api.mobile,{**binding,'principal':other},'default','same-create-request')
    if b.execution==b.native.execution_subject('owner','basic',other): assert foreign is None
    else: assert foreign and foreign['session']!=pending['session']
    _,_,original,response=native_creation(b,'same-create-request')
    assert b.native.confirm_created(b.api.mobile,original,response)
    assert not b.native.confirm_created(b.api.mobile,{**original,'principal':other},response)
    assert b.api.mobile.native_conversation('owner','basic','default',home,pending['session'],b.login)['kind']=='canonical'


def test_legacy_search_uses_exact_call_provenance_without_assigning_an_issuer_to_other_rows(bound):
    b=bound
    for sid in ('selected-legacy','unbound-legacy'): b.db.create_session(sid,'android',user_id='basic:owner')
    b.api.mobile.reserve('owner','default','selected-legacy',principal=b.login,
        native_home=b.native.home_fingerprint(b.home),execution=b.execution)
    b.monkeypatch.setitem(sys.modules,'tools.session_search_tool',SimpleNamespace(
        session_search=lambda db,**kwargs:{sid:bool(db.get_session(sid)) for sid in ('selected-legacy','unbound-legacy')}))
    assert b.api.voice_context.session_search_call('owner','basic','default',{'query':'synthetic'},b.login)=={
        'selected-legacy':True,'unbound-legacy':False}


@pytest.fixture
def old_called_adoption(bound):
    """1.1.32 calls have a login/profile/session but no native-home/execution fields."""
    from dataclasses import asdict
    from datetime import datetime
    import json
    import sqlite3
    import hermes_constants
    import hermes_state_local_migration as migration
    from gateway.config import Platform
    from gateway.session import SessionEntry,SessionSource
    from gateway.session_policy import LocalSessionPolicy
    from hermes_state_local import commit_local_session
    from hermes_state_runtime import begin_runtime_epoch
    b=bound
    b.monkeypatch.setattr(hermes_constants,'get_process_hermes_home',lambda:b.home)
    b.monkeypatch.setattr(hermes_constants,'get_default_hermes_root',lambda:b.home)
    b.server._hermes_home=b.home
    # Older native adoption retained the exact login subject; current native stamps the host.
    label='android' if hasattr(migration,'client_history_row') else 'desktop'
    b.db.create_session('old-beacon-chat',label,user_id=b.login if b.execution==b.login else 'basic:owner')
    epoch=begin_runtime_epoch(b.db,instance_id='synthetic-old-call-migration')
    source=SessionSource(platform=Platform.LOCAL,chat_id='old-beacon-chat',user_id=b.execution,chat_type='dm')
    route='local:'+source.chat_id
    now=datetime.now()
    entry=SessionEntry(route,source.chat_id,now,now,origin=source,platform=Platform.LOCAL)
    policy=LocalSessionPolicy(source='gui',platform='local',cwd=str(b.home),model='gpt-6-luna',
        toolsets=(),config_json='{}',request_json='{}',terminal_json='{}')
    receipt={'profile_id':str(b.home),'principal_id':b.execution,'request_id':'legacy:'+source.chat_id,
        'session_id':source.chat_id,'route':route,'entry':entry.to_dict(),'policy':asdict(policy),
        'legacy_session_id':source.chat_id,'lineage':[source.chat_id]}
    commit_local_session(b.db,epoch=epoch,receipt=receipt)
    folder=b.home/'plugin-data/beacon';folder.mkdir(parents=True)
    database=folder/'mobile.sqlite'
    with sqlite3.connect(database) as conn:
        conn.executescript('''
            CREATE TABLE meta(key TEXT PRIMARY KEY,value BLOB);
            CREATE TABLE calls(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL,ended REAL,
                closed INTEGER DEFAULT 0,status TEXT DEFAULT 'pending',receipt TEXT DEFAULT '{}',
                voice_config TEXT NOT NULL DEFAULT '{}',callback_context TEXT NOT NULL DEFAULT '{}',principal TEXT NOT NULL DEFAULT '');
            CREATE TABLE remote_admissions(admission_id TEXT PRIMARY KEY,owner TEXT,provider TEXT,profile TEXT,home TEXT,
                session TEXT,request TEXT,principal TEXT,epoch INTEGER,generation INTEGER DEFAULT 0,wire_digest TEXT,
                payload_digest TEXT,confirmed REAL,expires REAL);
        ''')
        for number in range(3):
            created=b.db.get_session(source.chat_id)['started_at']+(number+1)*.001
            conn.execute('INSERT INTO calls(id,owner,profile,conversation,created,ended,closed,status,principal) VALUES(?,?,?,?,?,?,1,?,?)',
                ('prior-'+str(number),'owner','default',source.chat_id,created,created+.001,'saved',b.login))
        assert not {'native_home','native_execution'}&{r[1] for r in conn.execute('PRAGMA table_info(calls)')}
        assert conn.execute('SELECT COUNT(*) FROM remote_admissions').fetchone()[0]==0
    b.old_mobile=database;b.adoption=receipt
    return b


def test_old_schema_called_adopted_row_is_passively_owned(old_called_adoption):
    import sqlite3
    b=old_called_adoption;before=b.old_mobile.read_bytes()
    assert b.db.get_session('old-beacon-chat')['source']=='desktop'
    assert b.native.verify('owner','basic','default','old-beacon-chat',principal=b.login)['id']=='old-beacon-chat'
    assert b.old_mobile.read_bytes()==before
    with sqlite3.connect(b.old_mobile) as conn:
        assert not {'native_home','native_execution'}&{r[1] for r in conn.execute('PRAGMA table_info(calls)')}
        assert conn.execute('SELECT COUNT(*) FROM calls').fetchone()[0]==3
        assert conn.execute('SELECT COUNT(*) FROM remote_admissions').fetchone()[0]==0


def migration_state(b):
    import hashlib
    return {str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in
        (b.old_mobile,b.db.db_path,Path(str(b.db.db_path)+'-wal')) if p.is_file()}


def test_old_schema_adoption_links_only_the_previously_authorized_login(old_called_adoption):
    from gateway.session_identity import authenticated_subject
    b=old_called_adoption;before=migration_state(b)
    proof=b.native.prior_call_binding(b.api.mobile,'owner','basic','default','old-beacon-chat',b.login,b.db)
    assert proof and proof['kind']=='prior_call' and proof['request']=='prior-2'
    for owner,provider,profile,session,principal in (
        ('other','basic','default','old-beacon-chat',b.login),
        ('owner','other','default','old-beacon-chat',b.login),
        ('owner','basic','other','old-beacon-chat',b.login),
        ('owner','basic','default','different-session',b.login),
        ('owner','basic','default','old-beacon-chat',authenticated_subject({'provider':'basic','issuer':'https://other.synthetic','user_id':'owner'})),
        ('owner','basic','default','old-beacon-chat','sid:SUPPLIED'),
    ):
        assert b.native.prior_call_binding(b.api.mobile,owner,provider,profile,session,principal,b.db) is None
    assert migration_state(b)==before


@pytest.mark.parametrize('missing',['call','principal','valid-call-time','policy','legacy-marker','route'])
def test_old_call_without_matching_native_proof_stays_unresolved(old_called_adoption,missing):
    import sqlite3
    from hermes_state_local import POLICY_PREFIX
    from hermes_state_local_migration import LEGACY_PREFIX
    b=old_called_adoption
    if missing in ('call','principal','valid-call-time'):
        with sqlite3.connect(b.old_mobile) as conn:
            if missing=='valid-call-time': conn.execute('UPDATE calls SET created=?',(float('inf'),))
            else: conn.execute('DELETE FROM calls' if missing=='call' else "UPDATE calls SET principal=''")
    elif missing in ('policy','legacy-marker'):
        prefix=POLICY_PREFIX if missing=='policy' else LEGACY_PREFIX
        b.db._execute_write(lambda conn:conn.execute('DELETE FROM state_meta WHERE key=?',(prefix+'old-beacon-chat',)))
    else: b.db._execute_write(lambda conn:conn.execute('DELETE FROM gateway_routing'))
    before=migration_state(b)
    assert b.native.prior_call_binding(b.api.mobile,'owner','basic','default','old-beacon-chat',b.login,b.db) is None
    if b.execution!=b.login:
        with pytest.raises(LookupError): b.native.verify('owner','basic','default','old-beacon-chat',principal=b.login)
    assert migration_state(b)==before


@pytest.mark.parametrize('changed',['receipt-home','receipt-owner','receipt-session','receipt-request','entry-origin',
                                   'stored-route','stored-owner','stored-source','newer-native-row','routing-entry','legacy-marker'])
def test_old_call_rejects_inconsistent_adoption_records(old_called_adoption,changed):
    import json
    from hermes_state_local import POLICY_PREFIX
    from hermes_state_local_migration import LEGACY_PREFIX
    b=old_called_adoption
    if changed.startswith('receipt-') or changed=='entry-origin':
        value=json.loads(json.dumps(b.adoption))
        if changed=='receipt-home': value['profile_id']=str(b.home/'foreign')
        elif changed=='receipt-owner': value['principal_id']='sid:FOREIGN'
        elif changed=='receipt-session': value['session_id']='foreign-session'
        elif changed=='receipt-request': value['request_id']='other-request'
        else: value['entry']['origin']['profile']='foreign-profile'
        b.db._execute_write(lambda conn:conn.execute('UPDATE state_meta SET value=? WHERE key=?',
            (json.dumps(value),POLICY_PREFIX+'old-beacon-chat')))
    elif changed.startswith('stored-'):
        field={'stored-route':'session_key','stored-owner':'user_id','stored-source':'source'}[changed]
        value={'stored-route':'foreign-route','stored-owner':'sid:FOREIGN','stored-source':'cron'}[changed]
        b.db._execute_write(lambda conn:conn.execute('UPDATE sessions SET '+field+'=? WHERE id=?',(value,'old-beacon-chat')))
    elif changed=='newer-native-row':
        b.db._execute_write(lambda conn:conn.execute("UPDATE sessions SET started_at=started_at+3600 WHERE id='old-beacon-chat'"))
    elif changed=='routing-entry':
        entry=json.loads(json.dumps(b.adoption['entry']));entry['session_id']='foreign-session'
        b.db._execute_write(lambda conn:conn.execute('UPDATE gateway_routing SET entry_json=?',(json.dumps(entry),)))
    else:
        b.db._execute_write(lambda conn:conn.execute('UPDATE state_meta SET value=? WHERE key=?',
            (json.dumps({'session_id':'foreign'}),LEGACY_PREFIX+'old-beacon-chat')))
    before=migration_state(b)
    assert b.native.prior_call_binding(b.api.mobile,'owner','basic','default','old-beacon-chat',b.login,b.db) is None
    assert migration_state(b)==before


@pytest.mark.parametrize('changed',['mobile-container','native-launch-home','native-profile','caller-db','explicit-wrong-home'])
def test_old_call_rejects_foreign_storage_binding(old_called_adoption,changed):
    import sqlite3
    b=old_called_adoption;db=b.db
    if changed=='mobile-container': b.monkeypatch.setattr(b.api.mobile,'home',lambda:b.home/'foreign-container')
    elif changed=='native-launch-home': b.server._hermes_home=b.home/'foreign'
    elif changed=='native-profile': b.server._profile_home=lambda profile:b.home/'foreign-profile'
    elif changed=='caller-db': db=SimpleNamespace(db_path=b.home/'foreign-profile/state.db')
    else:
        with sqlite3.connect(b.old_mobile) as conn:
            conn.execute("ALTER TABLE calls ADD COLUMN native_home TEXT NOT NULL DEFAULT ''")
            conn.execute("ALTER TABLE calls ADD COLUMN native_execution TEXT NOT NULL DEFAULT ''")
            conn.execute('UPDATE calls SET native_home=?,native_execution=?',('a'*64,b.execution))
    before=migration_state(b)
    assert b.native.prior_call_binding(b.api.mobile,'owner','basic','default','old-beacon-chat',b.login,db) is None
    assert migration_state(b)==before


def migration_client(b):
    import time
    from hermes_cli.dashboard_auth.base import Session
    verified=Session(user_id='owner',provider='basic',issuer='https://one.synthetic',email='',display_name='',org_id='',
        expires_at=int(time.time())+120,access_token='synthetic-token',refresh_token='')
    del b.client.headers['x-fixture-owner']
    @b.app.middleware('http')
    async def signed_in(request,call_next):
        request.state.session=verified
        return await call_next(request)
    async def no_greeting(*args): return None
    b.monkeypatch.setattr(b.api,'grant_greeting',no_greeting)
    return b.client


def test_new_call_records_home_without_rewriting_old_call_provenance(old_called_adoption):
    import sqlite3
    b=old_called_adoption;client=migration_client(b)
    assert b.native.verify('owner','basic','default','old-beacon-chat',principal=b.login)
    with sqlite3.connect(b.old_mobile) as conn:
        assert 'native_home' not in {r[1] for r in conn.execute('PRAGMA table_info(calls)')}
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'old-beacon-chat'})
    assert response.status_code==200,response.text
    with sqlite3.connect(b.old_mobile) as conn:
        previous=conn.execute("SELECT principal,native_home,native_execution FROM calls WHERE id LIKE 'prior-%' ORDER BY id").fetchall()
        current=conn.execute('SELECT principal,native_home,native_execution FROM calls WHERE id=?',(response.json()['call_id'],)).fetchone()
        assert previous==[(b.login,'','')]*3
        assert current==(b.login,b.native.home_fingerprint(b.home),b.execution)


def test_migrated_call_cannot_record_a_home_changed_after_verification(old_called_adoption):
    import sqlite3
    b=old_called_adoption;client=migration_client(b)
    reserve=b.native.reserve_conversation
    def changed(*args,**kwargs):
        row=reserve(*args,**kwargs)
        b.server._profile_home=lambda profile:b.home/'other-profile'
        return row
    b.monkeypatch.setattr(b.native,'reserve_conversation',changed)
    response=client.post('/api/plugins/beacon/mobile/calls',json={
        'profile':'default','voice_provider':'gemini','client_key':True,'conversation_id':'old-beacon-chat'})
    assert response.status_code==409,response.text
    with sqlite3.connect(b.old_mobile) as conn:
        assert conn.execute('SELECT COUNT(*) FROM calls').fetchone()[0]==3
        assert conn.execute("SELECT COUNT(*) FROM calls WHERE native_home!='' OR native_execution!=''").fetchone()[0]==0


def test_old_call_binding_serves_passive_context_search_and_policy(old_called_adoption):
    b=old_called_adoption
    b.db.create_session('unbound-host','desktop',user_id=b.execution)
    b.server._history_to_messages=lambda history,**kwargs:history
    before=migration_state(b)
    assert b.native.conversation_snapshot('owner','basic','default','old-beacon-chat',b.login)['passive_read']
    assert b.api.voice_context.history('owner','basic','default','old-beacon-chat',b.login)==[]
    b.monkeypatch.setitem(sys.modules,'tools.session_search_tool',SimpleNamespace(
        session_search=lambda db,**kwargs:{sid:bool(db.get_session(sid)) for sid in ('old-beacon-chat','unbound-host')}))
    result=b.api.voice_context.session_search_call('owner','basic','default',{'query':'synthetic'},b.login)
    assert result['old-beacon-chat'] is True
    if b.execution!=b.login: assert result['unbound-host'] is False
    backend=sys.modules['beacon_gateway_tools'].NativeMcp()
    assert backend.approval_source({'_kind':'hermes','_inline':'memory'},{},
        ('owner','basic','default','prior-2'),'old-beacon-chat',b.home,b.login)=='memory'
    assert migration_state(b)==before


def test_old_call_live_proof_still_requires_exact_current_transport(old_called_adoption):
    from gateway.session_authority import LiveSession
    from gateway.session_controls import AuthorityConnection
    from gateway.session import SessionSource
    b=old_called_adoption
    epoch=b.db._conn.execute('SELECT epoch FROM runtime_epoch WHERE singleton=1').fetchone()[0]
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),_draining=False,
        session_runtime_descriptor={'state':'ready','instance_id':'synthetic-live','authority_epoch':epoch})
    authority=SessionAuthority(runner,profile_id=str(b.home),instance_id='synthetic-live',db=b.db,epoch=epoch)
    runner.session_authority=authority
    live=LiveSession(SessionSource.from_dict(b.adoption['entry']['origin']),b.adoption['route'])
    authority.sessions['old-beacon-chat']=live
    peer=SimpleNamespace(auth_identity=b.native.login_identity('owner','basic',b.login))
    connection=AuthorityConnection(authority,peer,peer.auth_identity,operator=True)
    live.subscribers['viewer']=connection.actor
    b.monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    session=SimpleNamespace(user_id='owner',provider='basic',issuer='https://one.synthetic')
    request=SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner,session_authority=authority)),
                            state=SimpleNamespace(session=session))
    transport=sys.modules['beacon_canonical_transport'];before=migration_state(b)
    assert transport.canonical_live_proof(request,'default',b.home,'old-beacon-chat','old-beacon-chat','a'*64)['stored']=='old-beacon-chat'
    peer.auth_identity={**peer.auth_identity,'issuer':'https://foreign.synthetic'}
    with pytest.raises(Exception): transport.canonical_live_proof(request,'default',b.home,'old-beacon-chat','old-beacon-chat','a'*64)
    assert migration_state(b)==before


def test_prior_call_can_use_a_checked_native_creation_receipt(bound):
    import json
    import sqlite3
    import hermes_constants
    from hermes_state_local import POLICY_PREFIX,local_receipt
    b=bound
    b.monkeypatch.setattr(hermes_constants,'get_process_hermes_home',lambda:b.home)
    b.monkeypatch.setattr(hermes_constants,'get_default_hermes_root',lambda:b.home)
    b.server._hermes_home=b.home
    _,_,_,response=native_creation(b,'prior-canonical-create',beacon=False)
    sid=response['session_id'];born=b.db.get_session(sid)['started_at']
    folder=b.home/'plugin-data/beacon';folder.mkdir(parents=True)
    b.old_mobile=folder/'mobile.sqlite'
    with sqlite3.connect(b.old_mobile) as conn:
        conn.execute('CREATE TABLE calls(id TEXT,owner TEXT,profile TEXT,conversation TEXT,principal TEXT,created REAL)')
        conn.execute('INSERT INTO calls VALUES(?,?,?,?,?,?)',('prior','owner','default',sid,b.login,born+.001))
    before=migration_state(b)
    assert b.native.prior_call_binding(b.api.mobile,'owner','basic','default',sid,b.login,b.db)
    assert b.native.verify('owner','basic','default',sid,principal=b.login)['id']==sid
    assert migration_state(b)==before
    receipt=local_receipt(b.db,sid);receipt['request_id']='different-request'
    b.db._execute_write(lambda conn:conn.execute('UPDATE state_meta SET value=? WHERE key=?',
        (json.dumps(receipt),POLICY_PREFIX+sid)))
    assert b.native.prior_call_binding(b.api.mobile,'owner','basic','default',sid,b.login,b.db) is None

def test_stranded_create_is_reconciled_only_from_its_exact_native_receipt(bound):
    from gateway.session_identity import authenticated_subject
    b=bound
    authority,connection,pending,response=native_creation(b,request='stranded-create')
    b.monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=authority.runner))
    stored=response['session_id'];home=b.native.home_fingerprint(b.home)
    foreign=authenticated_subject({'provider':'basic','issuer':'https://two.synthetic','user_id':'owner'})
    with b.api.mobile.connect() as conn:
        assert conn.execute('SELECT kind FROM native_conversations WHERE session=?',(stored,)).fetchone()[0]=='pending'
    # A foreign login, a different session or no verified principal never retires the claim.
    assert not b.native.reconcile_created(b.api.mobile,'owner','basic','default',stored,foreign)
    assert not b.native.reconcile_created(b.api.mobile,'owner','basic','default','different',b.login)
    assert not b.native.reconcile_created(b.api.mobile,'owner','basic','default',stored,None)
    with b.api.mobile.connect() as conn:
        assert conn.execute('SELECT kind FROM native_conversations WHERE session=?',(stored,)).fetchone()[0]=='pending'
    # The exact native receipt retires it, once; the binding stays login-scoped and no call row appears.
    assert b.native.reconcile_created(b.api.mobile,'owner','basic','default',stored,b.login)
    saved=b.api.mobile.native_conversation('owner','basic','default',home,stored,b.login)
    assert saved['kind']=='canonical' and saved['principal']==b.login and saved['execution']==b.execution
    assert b.native.verify('owner','basic','default',stored,principal=b.login)['id']==stored
    assert not b.native.reconcile_created(b.api.mobile,'owner','basic','default',stored,b.login)
    with b.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM calls').fetchone()[0]==0


def test_stranded_create_with_a_changed_native_receipt_stays_pending(bound,monkeypatch):
    b=bound
    authority,connection,pending,response=native_creation(b,request='changed-receipt')
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=authority.runner))
    stored=response['session_id']
    import hermes_state_local
    real=hermes_state_local.local_receipt
    monkeypatch.setattr(hermes_state_local,'local_receipt',
        lambda db,sid:dict(real(db,sid),request_id='different-request'))
    assert not b.native.reconcile_created(b.api.mobile,'owner','basic','default',stored,b.login)
    with b.api.mobile.connect() as conn:
        assert conn.execute('SELECT kind FROM native_conversations WHERE session=?',(stored,)).fetchone()[0]=='pending'


@pytest.mark.parametrize('gap',['exact','transient','changed','delayed'])
def test_create_response_confirmation_retries_only_the_exact_receipt(bound,monkeypatch,gap):
    import asyncio
    import json
    import time
    b=bound
    authority,connection,pending,response=native_creation(b,request='relay-create')
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=authority.runner))
    transport=sys.modules['beacon_canonical_transport']
    binding={'owner':'owner','provider':'basic','principal':b.login,
             'home':b.native.home_fingerprint(b.home),'epoch':authority.epoch}
    frame=json.dumps({'jsonrpc':'2.0','id':31,'method':'session.create',
        'params':{'profile':'default','request_id':'relay-create','title':'Beacon voice call'}})
    import hermes_state_local
    real=hermes_state_local.local_receipt;calls=[0];first=[None]
    def receipt(db,sid):
        calls[0]+=1
        if gap=='delayed':
            if first[0] is None: first[0]=time.monotonic()
            if time.monotonic()-first[0]<0.6: raise hermes_state_local.RuntimeStoreError('storage_unavailable')
        if gap=='transient' and calls[0]==1: raise hermes_state_local.RuntimeStoreError('storage_unavailable')
        if gap=='changed': return dict(real(db,sid),request_id='different-request')
        return real(db,sid)
    monkeypatch.setattr(hermes_state_local,'local_receipt',receipt)
    class Client:
        def __init__(self):
            self.outgoing=asyncio.Queue();self.received=asyncio.Queue()
        async def receive(self): return await self.outgoing.get()
        async def send_text(self,value): await self.received.put(value)
    class Upstream:
        def __init__(self):
            self.forwarded=asyncio.Queue();self.responses=asyncio.Queue()
        async def send(self,value): await self.forwarded.put(value)
        async def recv(self): return await self.responses.get()
    async def exercise():
        client,upstream=Client(),Upstream()
        task=asyncio.create_task(transport.relay_sockets(client,upstream,'default',b.api.mobile,binding))
        try:
            await client.outgoing.put({'type':'websocket.receive','text':frame})
            await asyncio.sleep(.15)
            if task.done():
                raise AssertionError('relay stopped: '+repr(task.exception()))
            assert json.loads(await asyncio.wait_for(upstream.forwarded.get(),5))==json.loads(frame)
            await upstream.responses.put(json.dumps({'jsonrpc':'2.0','id':31,'result':response}))
            answer=json.loads(await asyncio.wait_for(client.received.get(),5))
            assert answer=={'jsonrpc':'2.0','id':31,'result':response}
            await client.outgoing.put({'type':'websocket.disconnect'})
            await asyncio.wait_for(task,5)
        finally:
            if not task.done(): task.cancel()
            await asyncio.gather(task,return_exceptions=True)
    asyncio.run(exercise())
    saved=b.api.mobile.native_conversation('owner','basic','default',binding['home'],response['session_id'],b.login,
                                           include_pending=True)
    if gap=='changed':
        assert saved['kind']=='pending' and calls[0]>=2
    else:
        assert saved['kind']=='canonical'
        if gap=='transient': assert calls[0]>=2
        if gap=='delayed': assert calls[0]>=5

def test_exact_create_retry_after_native_materialization_keeps_its_pending_claim(bound):
    b=bound
    authority,connection,pending,response=native_creation(b,request='retry-create')
    binding={'owner':'owner','provider':'basic','principal':b.login,'home':b.native.home_fingerprint(b.home),'epoch':authority.epoch}
    # The client resends the identical create after the native row exists but before confirmation.
    again=b.native.prepare_created(b.api.mobile,binding,'default','retry-create')
    assert again is not None and again['session']==response['session_id'] and again['request']=='retry-create'
    assert b.native.confirm_created(b.api.mobile,again,response)
    saved=b.api.mobile.native_conversation('owner','basic','default',binding['home'],response['session_id'],b.login)
    assert saved['kind']=='canonical'
    # A different request can never inherit that row or claim.
    other=b.native.prepare_created(b.api.mobile,binding,'default','another-request')
    assert other is not None and other['session']!=response['session_id']

def test_current_epoch_requires_a_live_local_authority_and_never_bypasses_it(bound,monkeypatch):
    b=bound;epoch=7
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),_draining=False,
        session_runtime_descriptor={'state':'ready','instance_id':'epoch-owner','authority_epoch':epoch})
    authority=SessionAuthority(runner,profile_id=str(b.home),instance_id='epoch-owner',db=b.db,epoch=epoch)
    runner.session_authority=authority
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    monkeypatch.setattr(b.server,'_profile_home',lambda profile:b.home)
    monkeypatch.setattr(b.server,'_hermes_home',b.home,raising=False)
    transport=sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(transport,'discover',
        lambda home:(_ for _ in ()).throw(AssertionError('remote discovery must not bypass a known local owner')))
    assert b.native.current_epoch('default')==epoch
    for name in ('retired','retiring'):
        monkeypatch.setattr(authority,name,True,raising=False)
        assert b.native.current_epoch('default') is None
        monkeypatch.setattr(authority,name,False,raising=False)
    monkeypatch.setattr(runner,'_draining',True)
    assert b.native.current_epoch('default') is None
    monkeypatch.setattr(runner,'_draining',False)
    for descriptor in ({'state':'draining','instance_id':'epoch-owner','authority_epoch':epoch},
                       {'state':'ready','instance_id':'epoch-owner','authority_epoch':epoch+1},
                       {'state':'ready','instance_id':'another-owner','authority_epoch':epoch}):
        monkeypatch.setattr(runner,'session_runtime_descriptor',descriptor)
        assert b.native.current_epoch('default') is None
    monkeypatch.setattr(runner,'session_runtime_descriptor',
        {'state':'ready','instance_id':'epoch-owner','authority_epoch':epoch})
    monkeypatch.setattr(authority,'epoch',0)
    assert b.native.current_epoch('default') is None
    monkeypatch.setattr(authority,'epoch',epoch)
    assert b.native.current_epoch('default')==epoch
