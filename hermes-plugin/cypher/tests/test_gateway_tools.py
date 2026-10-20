"""Isolated receipts and native contracts; never sends real email or callbacks."""
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import sys
import threading
import time
from types import ModuleType, SimpleNamespace
import pytest
from fastapi import HTTPException

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('cypher_gateway_test',ROOT/'gateway_tools.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
SCOPE=('owner','password','profile','call')


DEFAULT_HERMES_AGENT_SOURCE=Path('C:/Users/XPLoiT999/AppData/Local/hermes/hermes-agent')


def _hermes_agent_source():
    source=Path(os.environ.get('HERMES_AGENT_SOURCE',str(DEFAULT_HERMES_AGENT_SOURCE)))
    if not source.is_dir(): pytest.skip('Hermes source required; set HERMES_AGENT_SOURCE')
    return source


def test_hermes_source_override_and_missing_source(tmp_path,monkeypatch):
    monkeypatch.delenv('HERMES_AGENT_SOURCE',raising=False)
    if DEFAULT_HERMES_AGENT_SOURCE.is_dir():
        assert _hermes_agent_source()==DEFAULT_HERMES_AGENT_SOURCE
    else:
        with pytest.raises(pytest.skip.Exception):_hermes_agent_source()
    override=tmp_path/'hermes-agent';override.mkdir()
    monkeypatch.setenv('HERMES_AGENT_SOURCE',str(override))
    assert _hermes_agent_source()==override
    monkeypatch.setenv('HERMES_AGENT_SOURCE',str(tmp_path/'missing'))
    with pytest.raises(pytest.skip.Exception):_hermes_agent_source()


@pytest.mark.parametrize('arguments',[{'proxy':'dummy'},{'options':{'proxy':'dummy'}},{'options':[{'connection':{'cdp_url':'dummy'}}]}])
def test_connection_override_rejected_at_every_object_depth(arguments):
    with pytest.raises(HTTPException) as error: g.validate({'type':'object'},arguments)
    assert error.value.status_code==400
    g.validate({'type':'object'},{'options':[{'query':'allowed text','proxy':None}]})


def test_non_ascii_proposal_is_rejected_without_dispatch(gateway):
    api,backend,_=gateway;backend.read_only=False
    receipt=invoke(api)
    assert receipt['status']=='needs_approval'
    with pytest.raises(HTTPException) as error: api.decide(SCOPE,'r1',{'proposal_id':'é'*32,'decision':'approve'})
    assert error.value.status_code==409 and backend.calls==[]


class Mobile:
    LOCK=threading.RLock()
    def __init__(self,path): self.path=path;self.closed=False
    def connect(self):
        conn=sqlite3.connect(self.path);conn.row_factory=sqlite3.Row;return conn
    def ident(self,value):
        import re
        if not isinstance(value,str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,160}',value): raise ValueError('Invalid identity')
        return value
    def settings(self): return {'owner_id':'owner','owner_provider':'password','profiles':['profile']}
    def owned(self,conn,call,owner,profile,writable):
        if self.closed: raise LookupError('closed')
        assert (owner,profile,call)==('owner','profile','call')


class Backend:
    def __init__(self):
        self.read_only=True;self.version='a';self.calls=[];self.wait=None;self.started=threading.Event();self.result={'result':'real search result'}
    def catalog(self):
        return [{'tool_id':self.version*64,'name':'configured_search','description':'search web',
                 'parameters':{'type':'object','properties':{'query':{'type':'string'}},'required':['query'],'additionalProperties':False},
                 'read_only':self.read_only}]
    def execute(self,tool,args,scope,approved,session_id=None):
        self.calls.append((args,scope,approved,session_id))
        self.started.set()
        if self.wait: self.wait.wait(2)
        return json.dumps(self.result)


@pytest.fixture
def gateway(tmp_path):
    backend=Backend();mobile=Mobile(tmp_path/'private.sqlite')
    gateway=g.Gateway(mobile,lambda profile,fn:fn(tmp_path),backend,lambda value,limit:str(value)[:limit])
    yield gateway,backend,mobile


def invoke(gateway,request='r1'):
    return gateway.invoke(SCOPE,{'request_id':request,'tool_id':'a'*64,'arguments':{'query':'weather'}})


def settled(gateway,request='r1'):
    for _ in range(100):
        receipt=gateway.status(SCOPE,request)
        if receipt['status']!='running': return receipt
        time.sleep(.01)
    pytest.fail('receipt never settled')


def test_direct_read_and_exact_idempotency(gateway):
    api,backend,_=gateway
    invoke(api)
    result=settled(api)
    assert result['status']=='completed' and result['result']['result']=='real search result'
    assert invoke(api)==result and len(backend.calls)==1
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE,{'request_id':'r1','tool_id':'a'*64,'arguments':{'query':'changed'}})
    assert error.value.status_code==409


def test_gateway_executes_native_tool_when_owner_settings_omit_profiles(tmp_path):
    mobile=Mobile(tmp_path/'native-profiles.sqlite')
    mobile.settings=lambda:{'owner_id':'owner','owner_provider':'password'}
    api=g.Gateway(mobile,lambda profile,fn:fn(tmp_path),Backend())

    invoke(api)

    assert settled(api)['status']=='completed'


def test_unknown_effect_requires_server_proposal_and_explicit_decision(gateway):
    api,backend,_=gateway;backend.read_only=False
    proposal=invoke(api)
    assert proposal['status']=='needs_approval' and not backend.calls
    assert proposal['proposal']['arguments']=={'query':'weather'}
    with pytest.raises(HTTPException):
        api.invoke(SCOPE,{'request_id':'r2','tool_id':'a'*64,'arguments':{'query':'weather'},'approved':True})
    with pytest.raises(HTTPException): api.decide(SCOPE,'r1',{'proposal_id':'forged','decision':'approve'})
    decision={'proposal_id':proposal['proposal']['id'],'decision':'approve'}
    api.decide(SCOPE,'r1',decision);api.decide(SCOPE,'r1',decision)
    assert settled(api)['status']=='completed' and len(backend.calls)==1 and backend.calls[0][2] is True


def test_deny_expiry_and_reconfiguration_never_execute(gateway):
    api,backend,_=gateway;backend.read_only=False
    proposal=invoke(api)
    api.decide(SCOPE,'r1',{'proposal_id':proposal['proposal']['id'],'decision':'deny'})
    assert invoke(api)['status']=='denied' and not backend.calls
    proposal=invoke(api,'r2');backend.version='b'
    assert api.decide(SCOPE,'r2',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})['status']=='failed'
    backend.version='a';proposal=invoke(api,'r3')
    with api.db() as conn:
        value=json.loads(api._row(conn,SCOPE,'r3')['payload']);value['_expires']=0;api._save(conn,SCOPE,'r3',value)
    assert api.decide(SCOPE,'r3',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})['status']=='expired'
    assert not backend.calls


def test_scope_isolation_and_argument_negative_controls(gateway):
    api,backend,_=gateway;invoke(api);settled(api)
    for changed in [('other',*SCOPE[1:]),(SCOPE[0],'other',*SCOPE[2:]),(*SCOPE[:2],'other',SCOPE[3]),(*SCOPE[:3],'other')]:
        with pytest.raises(HTTPException) as error: api.status(changed,'r1')
        assert error.value.status_code==404
    for arguments in ({},{'query':3},{'query':'x','url':'http://evil'},{'query':'x'*17000},{'query':float('nan')}, {'query':'x','proxy':'http://evil'}, {'query':'x','method':'POST'}):
        with pytest.raises(HTTPException):
            api.invoke(SCOPE,{'request_id':'bad','tool_id':'a'*64,'arguments':arguments})
    assert len(backend.calls)==1
    with pytest.raises(HTTPException): g.validate({'$ref':'https://evil/schema'}, {})
    with pytest.raises(HTTPException): g.validate({'$dynamicRef':'https://evil/schema'}, {})
    g.validate({'type':'object','properties':{'optional':{'type':'string','nullable':True}}},{'optional':None})


def test_method_schema_requiring_post_is_write_capable(gateway,monkeypatch):
    api,backend,_=gateway
    monkeypatch.setattr(g,'native_catalog',lambda:[])
    def entry(name,method):
        def handler(): return name
        # Capture the values NativeMcp reads from the actual registered handler.
        server_name='http';tool_name=name;tool_timeout=10
        def registered(*args): return handler(),server_name,tool_name,tool_timeout
        return SimpleNamespace(name=name,toolset='mcp-http',handler=registered,schema={
            'description':name,'parameters':{'type':'object','properties':{
                'url':{'type':'string'},'method':method},'required':['url'],'additionalProperties':False}})
    cfg={'url':'https://example.invalid'}
    registry=SimpleNamespace(current_scope_key=lambda:'scope',get_all_entries=lambda:[
        entry('http_fetch',{'type':'string','enum':['GET','POST']}),
        entry('make_request',{'type':'string','enum':['GET','POST']}),
        entry('safe_fetch',{'type':'string','enum':['GET','HEAD']}),
    ])
    modules={
        'tools':SimpleNamespace(mcp_tool=SimpleNamespace(_servers={},_lazy_server_configs={'http':cfg})),
        'tools.mcp_tool_config':SimpleNamespace(_load_mcp_config=lambda:{'http':cfg}),
        'tools.mcp_tool_common':SimpleNamespace(mcp_server_enabled=lambda cfg:True),
        'tools.mcp_tool_discovery':SimpleNamespace(discover_mcp_tools=lambda:None),
        'tools.mcp_tool_registration':SimpleNamespace(_same_server_route=lambda *a,**k:True,
            _make_tool_filter=lambda *a:lambda raw:True,_normalize_server_trust=lambda trust:'trusted'),
        'tools.mcp_tool_scope':SimpleNamespace(_resolve_server_key=lambda server:server),
        'tools.mcp_tool_handlers':SimpleNamespace(_tool_is_read_only=lambda *args:True,
            _make_tool_handler=lambda *args:lambda arguments,**kwargs:arguments),
        'tools.registry':SimpleNamespace(registry=registry),
    }
    for name,module in modules.items(): monkeypatch.setitem(sys.modules,name,module)

    tools={tool['_raw']:tool for tool in g.NativeMcp().catalog()}
    assert tools['http_fetch']['read_only'] is False
    assert tools['safe_fetch']['read_only'] is True
    assert tools['make_request']['parameters']['properties']['method']['enum']==['GET']

    backend.catalog=lambda:[tools['http_fetch']]
    proposal=api.invoke(SCOPE,{'request_id':'post','tool_id':tools['http_fetch']['tool_id'],
        'arguments':{'url':'https://example.invalid','method':'POST'}})
    assert proposal['status']=='needs_approval' and not backend.calls


def test_get_only_method_default_is_receipted_and_executed(gateway):
    api,backend,_=gateway
    tool={'tool_id':'c'*64,'name':'make_request','description':'HTTP request',
        'parameters':{'type':'object','properties':{'url':{'type':'string'},
            'method':{'type':'string','enum':['GET'],'default':'GET'}},
            'required':['url'],'additionalProperties':False},'read_only':False}
    backend.catalog=lambda:[tool]
    proposal=api.invoke(SCOPE,{'request_id':'get-default','tool_id':tool['tool_id'],
        'arguments':{'url':'https://example.invalid'}})
    assert proposal['status']=='needs_approval'
    assert proposal['proposal']['arguments']=={'url':'https://example.invalid','method':'GET'}
    api.decide(SCOPE,'get-default',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    assert settled(api,'get-default')['status']=='completed'
    assert backend.calls[0][0]['method']=='GET'


def test_timeout_restart_and_late_return_are_uncertain_without_replay(gateway,monkeypatch):
    api,backend,_=gateway;backend.read_only=False;backend.wait=threading.Event()
    proposal=invoke(api);api.decide(SCOPE,'r1',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    assert backend.started.wait(1)
    monkeypatch.setattr(g,'DEADLINE',-1)
    assert api.status(SCOPE,'r1')['status']=='uncertain'
    backend.wait.set();time.sleep(.04)
    assert invoke(api)['status']=='uncertain' and len(backend.calls)==1
    backend.read_only=True;backend.wait=threading.Event();monkeypatch.setattr(g,'DEADLINE',90)
    invoke(api,'r2');monkeypatch.setattr(g,'BOOT','new-process')
    assert api.status(SCOPE,'r2')['status']=='uncertain'
    backend.wait.set()


def test_result_and_catalog_bounds(gateway):
    api,backend,_=gateway;backend.result={'result':'x'*50000}
    invoke(api);result=settled(api)
    assert result['truncated'] and len(g.encoded(result['result']))<g.MAX_RESULT+100
    assert api.discover('profile','email')['tools']==[]
    assert api.discover('profile','search')['tools'][0]['name']=='configured_search'
    backend.version='b';assert api.discover('profile')['tools'][0]['tool_id']=='b'*64
    backend.version='a';backend.result={'result':'\u0000😀'*30000}
    invoke(api,'unicode');receipt=settled(api,'unicode')
    assert len(g.encoded(receipt['result']).encode())<=g.MAX_RESULT


def test_capacity_never_replays_and_releases_slot(gateway,monkeypatch):
    api,backend,_=gateway
    semaphore=threading.BoundedSemaphore(1);monkeypatch.setattr(g,'SLOTS',semaphore)
    semaphore.acquire()
    with pytest.raises(HTTPException) as error: invoke(api)
    assert error.value.status_code==429 and invoke(api)['status']=='failed' and not backend.calls
    semaphore.release();invoke(api,'r2');settled(api,'r2')
    assert semaphore.acquire(blocking=False);semaphore.release()


def test_cancel_before_approval_and_after_dispatch_never_replays(gateway):
    api,backend,_=gateway;backend.read_only=False
    proposal=invoke(api)
    assert api.cancel(SCOPE,'r1')['status']=='cancelled'
    assert api.decide(SCOPE,'r1',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})['status']=='cancelled'
    assert not backend.calls
    backend.read_only=True;backend.wait=threading.Event();invoke(api,'r2')
    assert backend.started.wait(1)
    assert api.cancel(SCOPE,'r2')['status']=='uncertain'
    backend.wait.set();time.sleep(.03)
    assert invoke(api,'r2')['status']=='uncertain' and len(backend.calls)==1


def test_native_trust_gate_uses_receipt_once_and_denies_server_elicitation(tmp_path,monkeypatch):
    native=_hermes_agent_source()
    monkeypatch.syspath_prepend(str(native));monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    from tools.approval_prompt import request_elicitation_consent
    from tools.mcp_tool_handlers import _trust_gate_check
    from tools.mcp_tool_scope import _server_key
    from tools import mcp_tool
    from tools.mcp_tool_registration import _record_tool_trust_metadata
    _record_tool_trust_metadata('isolated',{'trust':'untrusted'},[SimpleNamespace(name='write',annotations={'readOnlyHint':False})])
    outcomes=[]
    def handler(arguments,**kwargs):
        outcomes.append(_trust_gate_check('isolated','write'))
        outcomes.append(request_elicitation_consent('Additional server confirmation','Cannot reuse the approval'))
        outcomes.append(_trust_gate_check('isolated','write'))
        return '{"result":"done"}'
    tool={'name':'mcp__isolated__write','_handler':handler,'_server':'isolated','_raw':'write','_trust':'untrusted'}
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda name,args,**kwargs:tool['_handler'](args,session_id=kwargs.get('session_id'))))
    g.NativeMcp().execute(tool,{},SCOPE,True)
    assert outcomes[0] is None and outcomes[1]=='decline' and outcomes[2] is not None
    outcomes.clear();g.NativeMcp().execute(tool,{},SCOPE,False)
    assert outcomes[0] is not None


def test_native_error_aliases_nested_content_and_truncation_are_not_completed(gateway,tmp_path,monkeypatch):
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    from tools.mcp_tool_handlers import _render_call_tool_result
    api,backend,_=gateway
    for index,field in enumerate(('is_error','isError')):
        native=SimpleNamespace(content=[SimpleNamespace(text='Native RPC rejected')],**{field:True})
        rendered=json.loads(_render_call_tool_result(native,'isolated'))
        assert 'error' in rendered and g.outcome(rendered,True)=='failed'
        backend.result=rendered;invoke(api,f'native-{index}')
        assert settled(api,f'native-{index}')['status']=='failed'
    controls=[{'result':json.dumps({'isError':True,'content':[{'type':'text','text':'Rejected'}]})},
              {'result':{'content':[{'type':'text','text':json.dumps({'error':'Denied'})}]}},
              {'result':'x'*50000,'error':'Error beyond the truncated output boundary'}]
    for index,payload in enumerate(controls):
        backend.result=payload;invoke(api,f'nested-{index}')
        assert settled(api,f'nested-{index}')['status']=='failed'
        assert g.outcome(payload,False)=='uncertain'
    success=SimpleNamespace(is_error=False,content=[SimpleNamespace(text=json.dumps({'status':200,'content':['Real result mentioning error handling']}))])
    backend.result=json.loads(_render_call_tool_result(success,'isolated'))
    invoke(api,'success');assert settled(api,'success')['status']=='completed'


def test_empty_error_field_does_not_change_success(gateway):
    api,backend,_=gateway
    for index,error in enumerate((None,False,'',{},[])):
        backend.result={'error':error,'result':'successful native result'}
        invoke(api,f'empty-{index}');assert settled(api,f'empty-{index}')['status']=='completed'
        assert g.outcome(backend.result,False)=='completed'
    assert g.outcome({'error':None,'is_error':True},True)=='failed'


def test_worker_catalog_is_locked_but_rpc_is_not(gateway,monkeypatch):
    api,backend,_=gateway
    lock=threading.RLock();monkeypatch.setattr(g,'CATALOG_LOCK',lock)
    catalog,execute=backend.catalog,backend.execute;calls=[]
    def checked_catalog():
        assert lock._is_owned();calls.append('catalog');return catalog()
    def checked_execute(*args,**kwargs):
        assert not lock._is_owned();return execute(*args,**kwargs)
    backend.catalog=checked_catalog;backend.execute=checked_execute
    invoke(api);assert settled(api)['status']=='completed' and len(calls)==2


def test_post_dispatch_http_exception_preserves_uncertainty_and_code(gateway):
    api,backend,_=gateway;backend.read_only=False
    def execute(*args,**kwargs): raise HTTPException(502,'Secret account and transport detail must not escape')
    backend.execute=execute
    proposal=invoke(api);api.decide(SCOPE,'r1',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    result=settled(api)
    assert result['status']=='uncertain' and result['http_status']==502 and result['error_code']=='tool_http_failure'
    assert 'Secret account' not in json.dumps(result) and 'before execution' not in result['reason']


def test_generic_pre_dispatch_failure_is_failed_not_uncertain(gateway):
    api,backend,_=gateway;original=backend.catalog;calls=0
    def fail_worker_catalog():
        nonlocal calls
        calls+=1
        if calls>1: raise RuntimeError('Private catalog failure')
        return original()
    backend.catalog=fail_worker_catalog
    invoke(api)
    result=settled(api)
    assert result['status']=='failed' and 'Private catalog failure' not in json.dumps(result)


def test_generic_read_only_failure_is_failed_not_uncertain(gateway):
    api,backend,_=gateway
    def fail(*args,**kwargs): raise RuntimeError('Private read failure')
    backend.execute=fail
    invoke(api)
    result=settled(api)
    assert result['status']=='failed' and 'Private read failure' not in json.dumps(result)


def test_executor_submission_failure_settles_receipt_and_releases_slot(gateway,monkeypatch):
    api,_,_=gateway
    def fail_submit(*args,**kwargs): raise RuntimeError('Private executor failure')
    monkeypatch.setattr(g.POOL,'submit',fail_submit)
    with pytest.raises(HTTPException) as error: invoke(api)
    assert error.value.status_code==503
    receipt=api.status(SCOPE,'r1')
    assert receipt['status']=='failed' and 'Private executor failure' not in json.dumps(receipt)
    assert g.SLOTS.acquire(blocking=False)
    g.SLOTS.release()


def test_global_receipt_cap_prunes_closed_call_history(gateway,monkeypatch):
    api,_,mobile=gateway
    monkeypatch.setattr(g,'MAX_RECEIPTS',1,raising=False)
    now=time.time()
    with mobile.connect() as conn:
        conn.execute('CREATE TABLE calls(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,created REAL,closed INTEGER)')
        conn.execute('INSERT INTO calls VALUES(?,?,?,?,?)',('old-call','owner','profile',now-10,1))
        conn.execute('INSERT INTO calls VALUES(?,?,?,?,?)',('call','owner','profile',now,0))
        conn.execute('''CREATE TABLE IF NOT EXISTS tool_receipts (
            owner TEXT,provider TEXT,profile TEXT,call_id TEXT,request_id TEXT,
            signature TEXT NOT NULL,payload TEXT NOT NULL,updated REAL NOT NULL,
            PRIMARY KEY(owner,provider,profile,call_id,request_id))''')
        conn.execute('INSERT INTO tool_receipts VALUES(?,?,?,?,?,?,?,?)',
                     ('owner','password','profile','old-call','old','sig',json.dumps({'status':'completed'}),now-10))
    invoke(api)
    assert settled(api)['status']=='completed'
    with mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM tool_receipts').fetchone()[0]==1
        assert conn.execute("SELECT COUNT(*) FROM tool_receipts WHERE call_id='old-call'").fetchone()[0]==0


def test_global_receipt_cap_rejects_when_only_live_receipts_remain(gateway,monkeypatch):
    api,backend,mobile=gateway
    monkeypatch.setattr(g,'MAX_RECEIPTS',1)
    now=time.time()
    with mobile.connect() as conn:
        conn.execute('CREATE TABLE calls(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,created REAL,closed INTEGER)')
        conn.execute('INSERT INTO calls VALUES(?,?,?,?,?)',('call','owner','profile',now,0))
        conn.execute('''CREATE TABLE IF NOT EXISTS tool_receipts (
            owner TEXT,provider TEXT,profile TEXT,call_id TEXT,request_id TEXT,
            signature TEXT NOT NULL,payload TEXT NOT NULL,updated REAL NOT NULL,
            PRIMARY KEY(owner,provider,profile,call_id,request_id))''')
        conn.execute('INSERT INTO tool_receipts VALUES(?,?,?,?,?,?,?,?)',
                     ('owner','password','profile','call','running','sig',json.dumps({'status':'running'}),now))
    with pytest.raises(HTTPException) as error: invoke(api)
    assert error.value.status_code==429 and backend.calls==[]
    with mobile.connect() as conn: assert conn.execute('SELECT COUNT(*) FROM tool_receipts').fetchone()[0]==1


def test_cancelled_lookup_retrieves_worker_exception(tmp_path):
    import asyncio
    import gc
    from types import SimpleNamespace
    started=threading.Event();release=threading.Event();finished=threading.Event()
    class ExplodingGateway:
        def __init__(self,*args): pass
        def discover(self,*args):
            started.set();release.wait(2);finished.set()
            raise RuntimeError('Private detached lookup failure')
    monkeypatcher=pytest.MonkeyPatch()
    try:
        monkeypatcher.setitem(sys.modules,'cypher_gateway_tools',SimpleNamespace(Gateway=ExplodingGateway))
        module_spec=importlib.util.spec_from_file_location('cypher_gateway_api_cancel_test',ROOT/'gateway_api.py')
        api=importlib.util.module_from_spec(module_spec);module_spec.loader.exec_module(api)
        async def call(*args,**kwargs): return 'owner','password',{'profile':'profile','conversation':'native-stored'},{}
        router=api.build_router(call,lambda *args,**kwargs:{},lambda profile,fn:fn(tmp_path),Mobile(tmp_path/'api.sqlite'))
        endpoint=next(route.endpoint for route in router.routes if route.path.endswith('/tools'))
        async def exercise():
            loop=asyncio.get_running_loop();errors=[]
            loop.set_exception_handler(lambda _,context:errors.append(context))
            request=asyncio.create_task(endpoint('call',None,''))
            assert await asyncio.to_thread(started.wait,1)
            request.cancel()
            with pytest.raises(asyncio.CancelledError): await request
            release.set()
            assert await asyncio.to_thread(finished.wait,1)
            await asyncio.sleep(.05);gc.collect();await asyncio.sleep(0)
            assert not any(context.get('message')=='Task exception was never retrieved' for context in errors)
        asyncio.run(exercise())
    finally:
        release.set();monkeypatcher.undo()


def test_http_failure_inside_scraper_payload_is_not_success(gateway):
    api,backend,_=gateway
    for code in (401,403,429,500,'502'):
        raw={'result':json.dumps({'status':code,'content':['Upstream rejected the request']})}
        assert g.outcome(raw,True)=='failed'
        assert g.outcome(raw,False)=='uncertain'
    assert g.outcome({'result':json.dumps({'status':200,'content':['Verified result']})},True)=='completed'
    assert g.outcome({'status':'completed','result':{'status':42}},True)=='completed'
    backend.result={'result':json.dumps({'status':429,'content':['Rate limited']})}
    invoke(api)
    assert settled(api)['status']=='failed'


def test_malformed_or_changed_approval_display_is_rejected_before_receipt(gateway):
    api,backend,_=gateway;backend.read_only=False
    for sanitized in ('{"query":', '{"query":"a different action"}'):
        api.sanitize=lambda *args:sanitized
        with pytest.raises(HTTPException) as error: invoke(api)
        assert error.value.status_code==400
        with pytest.raises(HTTPException) as error: api.status(SCOPE,'r1')
        assert error.value.status_code==404 and not backend.calls


def test_native_approval_atomic_once_and_invalid_identity_fail_closed(tmp_path,monkeypatch):
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    from tools import approval
    captured={};choices=[]
    monkeypatch.setattr(approval,'register_gateway_notify',lambda key,callback:captured.update(callback=callback))
    monkeypatch.setattr(approval,'unregister_gateway_notify',lambda key:None)
    monkeypatch.setattr(approval,'resolve_gateway_approval',lambda key,choice,**kwargs:choices.append(choice))
    def handler(*args,**kwargs):
        notify=captured['callback']
        with pytest.raises(ValueError): notify({'request_id':None})
        expected="MCP tool 'write' on UNTRUSTED server 'isolated' wants to run. This tool is write-capable (no readOnlyHint=true annotation) and may modify external state."
        barrier=threading.Barrier(12)
        def ask(index):
            barrier.wait();notify({'request_id':f'{index:032x}','pattern_key':'mcp_elicitation','command':expected})
        with g.concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            list(pool.map(ask,range(12)))
        return '{"result":"success"}'
    tool={'name':'mcp__isolated__write','_handler':handler,'_server':'isolated','_raw':'write','_trust':'untrusted'}
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda name,args,**kwargs:tool['_handler'](args,session_id=kwargs.get('session_id'))))
    g.NativeMcp().execute(tool, {},SCOPE,True)
    assert choices.count('once')==1 and choices.count('deny')==11


def test_router_reuses_native_owner_profile_and_closed_call_authority(tmp_path,monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from test_mobile import harness, reserve
    # Borrow the real dashboard's authentication/call checks with a private fixture DB.
    host,client,cfg,root,app=harness.__wrapped__(tmp_path,monkeypatch)
    spec=importlib.util.spec_from_file_location('cypher_gateway_api_test',ROOT/'gateway_api.py')
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    native_module=sys.modules['cypher_gateway_tools']
    backend=Backend()
    monkeypatch.setattr(native_module,'NativeMcp',lambda:backend)
    monkeypatch.setattr(native_module.Gateway,'catalog',lambda self,profile:backend.catalog())
    app.include_router(api.build_router(host.call,host.body,lambda p,fn:fn(tmp_path),host.mobile),prefix='/api/plugins/cypher')
    call=reserve(client)
    path=f'/api/plugins/cypher/mobile/calls/{call}/tools'
    assert client.get(path).status_code==200
    assert client.get(path+'?profile=foreign').status_code==403
    client.headers['x-fixture-owner']='foreign';assert client.get(path).status_code==403
    del client.headers['x-fixture-owner'];assert client.get(path).status_code==401
    client.headers['x-fixture-owner']='owner';app.state.auth_required=False
    assert client.get(path).status_code==401
    app.state.auth_required=True
    assert client.post(path+'/invoke',json={'request_id':'r','tool_id':'a'*64,'arguments':{'query':3}}).status_code==400
    with host.mobile.connect() as conn: conn.execute('UPDATE calls SET closed=1 WHERE id=?',(call,))
    assert client.get(path).status_code==409
    assert not backend.calls


def test_gateway_router_passes_the_call_conversation_to_native_dispatch(tmp_path,monkeypatch):
    from fastapi import FastAPI,Request
    from fastapi.testclient import TestClient
    from test_mobile import harness
    host,_,_,_,_=harness.__wrapped__(tmp_path,monkeypatch)
    spec=importlib.util.spec_from_file_location('cypher_gateway_api_session_test',ROOT/'gateway_api.py')
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    backend=Backend();native_module=sys.modules['cypher_gateway_tools']
    monkeypatch.setattr(native_module,'NativeMcp',lambda:backend)
    app=FastAPI();app.state.auth_required=True
    @app.middleware('http')
    async def authenticated(request:Request,call_next):
        request.state.session=SimpleNamespace(user_id='owner',provider='basic')
        return await call_next(request)
    app.include_router(api.build_router(host.call,host.body,lambda p,fn:fn(tmp_path),host.mobile),
                       prefix='/api/plugins/cypher')
    call=host.mobile.reserve('owner','default','native-stored')['call_id']
    path=f'/api/plugins/cypher/mobile/calls/{call}/tools'
    with TestClient(app) as client:
        response=client.post(path+'/invoke',json={'request_id':'session-bound','tool_id':'a'*64,
                                                   'arguments':{'query':'weather'}})
        assert response.status_code==200
        for _ in range(100):
            receipt=client.get(path+'/requests/session-bound').json()
            if receipt['status']!='running': break
            time.sleep(.01)
    assert receipt['status']=='completed' and backend.calls[0][3]=='native-stored'


def test_natural_tool_discovery_finds_mail_and_preserves_write_intent(gateway):
    api,backend,_=gateway
    def tool(name,description,read_only=True):
        return {'tool_id':name.encode().hex().ljust(64,'0')[:64], 'name':name,
                'description':description, 'parameters':{'type':'object','properties':{}}, 'read_only':read_only}
    backend.catalog=lambda:[
        tool('search_contacts','Search contact names and email addresses'),
        tool('send_gmail_message','Sends an email using the user Gmail account',False),
        tool('search_gmail_messages','Searches messages in a Gmail account based on a query'),
        tool('web_search','Read current web information from a search query'),
    ]
    found=api.discover('profile','Could you pull up my latest five emails?')['tools']
    assert found and found[0]['name']=='search_gmail_messages'
    assert api.discover('profile','email')['tools'][0]['name']=='search_gmail_messages'
    assert api.discover('profile','send email')['tools'][0]['name']=='send_gmail_message'
    assert api.discover('profile','search the web for current news')['tools'][0]['name']=='web_search'
    assert api.discover('profile','neutron triangulation flux')['tools']==[]


def test_discovery_does_not_cache_away_new_mcp_tools(gateway):
    api,backend,_=gateway
    assert api.discover('profile','weather')['tools']==[]
    original=backend.catalog
    backend.catalog=lambda:original()+[{'tool_id':'b'*64,'name':'new_mcp_weather',
        'description':'Current weather forecasts','parameters':{'type':'object'},'read_only':True}]
    assert api.discover('profile','Please get the latest weather forecast')['tools'][0]['name']=='new_mcp_weather'


def test_public_web_discovery_ranks_web_tools_above_mail_web_links(gateway):
    api,backend,_=gateway
    def tool(name,description):
        return {'tool_id':name.encode().hex().ljust(64,'0')[:64],'name':name,
                'description':description,'parameters':{'type':'object'},'read_only':True}
    backend.catalog=lambda:[
        tool('search_gmail_messages','Search Gmail messages and return web interface links'),
        tool('search_contacts','Search contacts with web profile links'),
        tool('scrapling_fetch','Fetch a public web page with search engine referer support'),
    ]
    assert api.discover('profile','web search')['tools'][0]['name']=='scrapling_fetch'


def test_discovery_preserves_inflected_description_matches(gateway):
    api,backend,_=gateway
    backend.catalog=lambda:[
        {'tool_id':'a'*64,'name':'inbox_reader','description':'Searches your inbox','parameters':{'type':'object'},'read_only':True},
        {'tool_id':'b'*64,'name':'page_reader','description':'Fetches public pages','parameters':{'type':'object'},'read_only':True},
        {'tool_id':'c'*64,'name':'people','description':'Lists contact addresses','parameters':{'type':'object'},'read_only':True},
    ]
    for query,name in [('search','inbox_reader'),('fetch','page_reader'),('address','people')]:
        assert api.discover('profile',query)['tools'][0]['name']==name


def test_native_tools_are_discovered_for_the_api_profile_and_exclusions_are_applied(monkeypatch,tmp_path):
    names=[
        ('read_file','file'),('write_file','file'),('web_search','web'),('web_extract','web'),('vision_analyze','vision'),
        ('session_search','session_search'),('memory','memory'),('todo_list','todo'),('video_analyze','video'),
        ('clarify','clarify'),('delegate_task','delegation'),('browser_click','browser'),
        ('cypher_create_proactive_request','cypher-interactive'),('cypher_email_notice','cypher-mailbox'),
        ('mcp-weather','mcp-weather'),('metadata_read','custom'),
    ]
    entries={name:SimpleNamespace(name=name,toolset=toolset,schema={'description':f'{name} purpose'},
                                 read_only=True if name=='metadata_read' else None)
             for name,toolset in names}
    definitions=[{'type':'function','function':{'name':name,'description':f'{name} purpose',
                 'parameters':({'type':'object','properties':{'url':{'type':'string'}},'required':['url'],'additionalProperties':False}
                               if name in {'web_extract','vision_analyze'} else
                               {'type':'object','properties':{},'additionalProperties':False})}} for name,_ in names]
    config={'agent':{'disabled_toolsets':['video']}}
    seen={}
    enabled={'file','web','vision','session_search','memory','todo','video','clarify','delegation','browser',
             'cypher-interactive','cypher-mailbox','mcp-weather','custom'}
    def platform_tools(received,platform):
        assert received is config and platform=='api_server'
        return enabled
    def get_definitions(**kwargs):
        seen.update(kwargs)
        disabled=set(kwargs['disabled_toolsets'] or [])
        active=set(kwargs['enabled_toolsets'])-disabled
        return [item for item in definitions if entries[item['function']['name']].toolset in active]
    registry=SimpleNamespace(current_scope_key=lambda:'profile-scope',_generation=7,
                              get_entry=lambda name:entries.get(name))
    monkeypatch.setitem(sys.modules,'hermes_cli.config',SimpleNamespace(load_config_readonly=lambda:config))
    monkeypatch.setitem(sys.modules,'hermes_cli.tools_config',SimpleNamespace(_get_platform_tools=platform_tools))
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(get_tool_definitions=get_definitions))
    monkeypatch.setitem(sys.modules,'tools.registry',SimpleNamespace(registry=registry))

    native_catalog=getattr(g,'native_catalog',lambda:[])()

    catalog={item['name']:item for item in native_catalog}
    assert {'read_file','write_file','web_search','web_extract','vision_analyze','metadata_read'} <= set(catalog)
    assert 'session_search' not in catalog
    assert not {'memory','todo_list','clarify','delegate_task','browser_click','cypher_create_proactive_request',
                'cypher_email_notice','mcp-weather','video_analyze'} & set(catalog)
    assert catalog['read_file']['read_only'] and catalog['web_search']['read_only']
    assert not catalog['web_extract']['read_only'] and not catalog['vision_analyze']['read_only']
    assert not catalog['metadata_read']['read_only']
    assert not catalog['write_file']['read_only']
    assert seen['enabled_toolsets']==sorted(enabled)
    assert seen['disabled_toolsets']==['video']
    assert seen['skip_tool_search_assembly'] is True

    backend=SimpleNamespace(catalog=lambda:native_catalog)
    api=g.Gateway(Mobile(tmp_path/'discover.sqlite'),lambda profile,fn:fn(tmp_path),backend,
                  lambda value,limit:str(value)[:limit])
    discovered=api.discover('profile','read_file')['tools']
    assert discovered[0]['name']=='read_file' and discovered[0]['read_only'] is True
    for index,name in enumerate(('web_extract','vision_analyze')):
        url=f'https://mail.example/messages/{index}'
        proposal=api.invoke(SCOPE,{'request_id':f'fetch-{index}','tool_id':catalog[name]['tool_id'],
                                   'arguments':{'url':url}})
        assert proposal['status']=='needs_approval'
        assert proposal['proposal']['arguments']=={'url':url}


def test_native_tool_receipts_dispatch_in_profile_conversation_and_gate_mutations(tmp_path,monkeypatch):
    dispatches=[];handled=[];session={}
    def dispatch(name,args,**kwargs):
        dispatches.append((name,args,kwargs,dict(session)))
        return {'result':'x'*40000} if name=='read_file' else {'ok':True}
    registry=SimpleNamespace(dispatch=dispatch)
    monkeypatch.setitem(sys.modules,'tools.registry',SimpleNamespace(registry=registry))
    def handle_function_call(name,args,**kwargs):
        handled.append((name,args,kwargs))
        return json.dumps(dispatch(name,args,task_id=kwargs.get('task_id'),session_id=kwargs.get('session_id')))
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(handle_function_call=handle_function_call))
    session_context=SimpleNamespace(
        set_session_vars=lambda **kwargs:session.update(kwargs) or kwargs,
        clear_session_vars=lambda _:session.clear())
    monkeypatch.setitem(sys.modules,'gateway',SimpleNamespace(session_context=session_context))

    native=g.NativeMcp()
    native.cache_key=lambda:None
    native.catalog=lambda:[
        {'tool_id':'a'*64,'name':'read_file','description':'Read a file',
             'parameters':{'type':'object','properties':{'path':{'type':'string'}},'required':['path'],'additionalProperties':False},
             'read_only':True,'_kind':'hermes','_raw':'read_file','_toolset':'file','_enabled_tools':['read_file','write_file']},
        {'tool_id':'b'*64,'name':'write_file','description':'Write a file',
         'parameters':{'type':'object','properties':{'path':{'type':'string'},'content':{'type':'string'}},
                       'required':['path','content'],'additionalProperties':False},
         'read_only':False,'_kind':'hermes','_raw':'write_file','_toolset':'file','_enabled_tools':['read_file','write_file']},
        {'tool_id':'c'*64,'name':'cronjob_manage','description':'Manage scheduled jobs',
         'parameters':{'type':'object','properties':{'action':{'type':'string','enum':['list','run']}},
                       'required':['action'],'additionalProperties':False},
         'read_only':False,'_kind':'hermes','_raw':'cronjob_manage','_toolset':'cronjob'},
        {'tool_id':'d'*64,'name':'process_manage','description':'Manage background processes',
         'parameters':{'type':'object','properties':{'action':{'type':'string','enum':['list','write']}},
                       'required':['action'],'additionalProperties':False},
         'read_only':False,'_kind':'hermes','_raw':'process_manage','_toolset':'terminal'},
    ]
    mobile=Mobile(tmp_path/'native.sqlite')
    api=g.Gateway(mobile,lambda profile,fn:fn(tmp_path),native,lambda value,limit:str(value)[:limit])

    read=api.invoke(SCOPE,{'request_id':'native-read','tool_id':'a'*64,'arguments':{'path':'notes.txt'}},
                    session_id='native-stored')
    read=settled(api,'native-read')
    assert read['status']=='completed' and read['truncated'] is True, (read,dispatches,session)
    assert len(g.encoded(read['result']).encode())<=g.MAX_RESULT
    assert handled and handled[0][0]=='read_file'
    assert handled[0][2]['task_id']=='call' and handled[0][2]['session_id']=='native-stored'
    assert handled[0][2]['enabled_tools']==['read_file','write_file']
    assert dispatches[0][0]=='read_file' and dispatches[0][2]['session_id']=='native-stored'
    assert dispatches[0][2]['task_id']=='call'
    assert dispatches[0][3]['profile']=='profile' and dispatches[0][3]['session_id']=='native-stored'

    proposal=api.invoke(SCOPE,{'request_id':'native-write','tool_id':'b'*64,
                               'arguments':{'path':'notes.txt','content':'safe text'}},
                        session_id='native-stored')
    assert proposal['status']=='needs_approval' and len(dispatches)==1
    api.decide(SCOPE,'native-write',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    result=settled(api,'native-write')
    assert result['status']=='completed' and len(dispatches)==2
    assert dispatches[1][0]=='write_file' and dispatches[1][2]['session_id']=='native-stored'

    for request,name,tool_id in [('cron-list','cronjob_manage','c'*64),('process-list','process_manage','d'*64)]:
        receipt=api.invoke(SCOPE,{'request_id':request,'tool_id':tool_id,'arguments':{'action':'list'}},
                           session_id='native-stored')
        assert receipt['status'] in ('running','completed')
        assert settled(api,request)['status']=='completed'
        assert dispatches[-1][0]==name
    for request,name,tool_id,action in [('cron-run','cronjob_manage','c'*64,'run'),
                                        ('process-write','process_manage','d'*64,'write')]:
        before=len(dispatches)
        receipt=api.invoke(SCOPE,{'request_id':request,'tool_id':tool_id,'arguments':{'action':action}},
                           session_id='native-stored')
        assert receipt['status']=='needs_approval' and len(dispatches)==before


def test_native_mixed_tools_are_read_only_only_for_explicit_read_actions():
    read_only=getattr(g,'tool_read_only',lambda tool,args:bool(tool.get('read_only')))
    cron={'_kind':'hermes','_raw':'cronjob_manage','read_only':False}
    process={'_kind':'hermes','_raw':'process_manage','read_only':False}
    memory={'_kind':'hermes','_raw':'memory','read_only':False}
    todo={'_kind':'hermes','_raw':'todo_list','read_only':False}
    assert not read_only(todo,{})
    assert not read_only(memory,{'action':'add'})
    assert read_only(cron,{'action':'list'}) and not read_only(cron,{'action':'run'})
    assert read_only(process,{'action':'list'}) and not read_only(process,{'action':'write'})


@pytest.mark.parametrize('kind',['hermes','mcp'])
def test_read_only_gateway_tools_are_stopped_by_hermes_pre_tool_hook(tmp_path,monkeypatch,kind):
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    import model_tools
    from hermes_cli import plugins
    from tools.registry import registry

    seen=[];dispatched=[];session={}
    original=model_tools.handle_function_call
    def handle(name,args,**kwargs):
        seen.append((name,args,kwargs))
        return original(name,args,**kwargs)
    monkeypatch.setattr(model_tools,'handle_function_call',handle)
    monkeypatch.setattr(plugins,'_dispatch_pre_tool_call_hooks',lambda *args,**kwargs:('Blocked by owner policy',None))
    monkeypatch.setattr(registry,'dispatch',lambda *args,**kwargs:dispatched.append((args,kwargs)) or {'ok':True})
    session_context=SimpleNamespace(set_session_vars=lambda **kwargs:session.update(kwargs) or kwargs,
                                    clear_session_vars=lambda _:session.clear())
    monkeypatch.setitem(sys.modules,'gateway',SimpleNamespace(session_context=session_context))

    if kind=='hermes':
        tool={'name':'read_file','_kind':'hermes','_raw':'read_file','_enabled_tools':['read_file'],'read_only':True}
    else:
        tool={'name':'mcp__mail__search_messages','_kind':'mcp','_raw':'search_messages','_server':'mail',
              '_trust':'trusted','_handler':lambda *args,**kwargs:{'ok':True},'read_only':True}
    result=g.NativeMcp().execute(tool,{'path':'private.txt'},SCOPE,False,session_id='native-stored')
    result=json.loads(result) if isinstance(result,str) else result

    assert seen and seen[0][0]==tool['name']
    assert seen[0][2]['task_id']=='call' and seen[0][2]['session_id']=='native-stored'
    if kind=='hermes': assert seen[0][2]['enabled_tools']==['read_file']
    assert result.get('error')=='Blocked by owner policy'
    assert dispatched==[]


def test_terminal_native_dispatch_resolves_only_its_exact_approved_hermes_guard(monkeypatch):
    pending={};choices=[];session={};registered_keys=[]
    approval=SimpleNamespace(
        register_gateway_notify=lambda key,callback:(registered_keys.append(key),pending.update(key=key,callback=callback)),
        unregister_gateway_notify=lambda key:pending.clear(),
        resolve_gateway_approval=lambda key,choice,**kwargs:choices.append((key,choice,kwargs['request_id'])))
    approval_context=SimpleNamespace(set_current_session_key=lambda key:key,
                                     reset_current_session_key=lambda token:None)
    session_context=SimpleNamespace(set_session_vars=lambda **kwargs:session.update(kwargs) or kwargs,
                                    clear_session_vars=lambda _:session.clear())
    tools_package=ModuleType('tools');tools_package.__path__=[]
    tools_package.approval=approval;tools_package.approval_context=approval_context
    registry=SimpleNamespace(dispatch=lambda name,args,**kwargs:pending['callback'](
        {'request_id':'a'*32,'pattern_key':'dangerous_path','command':args['command']}) or {'ok':True})
    tools_package.registry=SimpleNamespace(registry=registry)
    monkeypatch.setitem(sys.modules,'tools',tools_package)
    monkeypatch.setitem(sys.modules,'tools.approval',approval)
    monkeypatch.setitem(sys.modules,'tools.approval_context',approval_context)
    monkeypatch.setitem(sys.modules,'tools.registry',tools_package.registry)
    monkeypatch.setitem(sys.modules,'gateway',SimpleNamespace(session_context=session_context))
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda name,args,**kwargs:registry.dispatch(
            name,args,task_id=kwargs.get('task_id'),session_id=kwargs.get('session_id'))))
    command='remove a generated build directory'
    tool={'_kind':'hermes','_raw':'terminal','read_only':False}

    result=g.NativeMcp().execute(tool,{'command':command},SCOPE,True,session_id='native-stored')

    assert result=={'ok':True} and choices==[(registered_keys[-1], 'once', 'a'*32)]

    choices.clear()
    registry.dispatch=lambda name,args,**kwargs:pending['callback'](
        {'request_id':'b'*32,'pattern_key':'dangerous_path','command':'different command'}) or {'ok':True}
    g.NativeMcp().execute(tool,{'command':command},SCOPE,True,session_id='native-stored')
    assert choices==[(registered_keys[-1], 'deny', 'b'*32)]


def test_voice_tool_catalogue_is_bounded_live_and_uncached(tmp_path):
    class Backend:
        def __init__(self): self.calls=0;self.discover=[]
        def catalog(self,discover=True):
            self.calls+=1;self.discover.append(discover)
            return [{'name':f'tool_{i:03}','description':'A useful tool that reads data safely. '*5}
                    for i in range(100)]
    backend=Backend();api=g.Gateway(Mobile(tmp_path/'catalog.sqlite'),lambda profile,fn:fn(tmp_path),backend)
    started=time.perf_counter();first=api.compact_catalogue('profile')
    first_ms=(time.perf_counter()-started)*1000
    started=time.perf_counter();again=api.compact_catalogue('profile')
    repeated_ms=(time.perf_counter()-started)*1000
    assert first==again and len(first.encode('utf-8'))<=3072
    assert 'tool_000:' in first and backend.calls==2 and backend.discover==[False,False]
    api.compact_catalogue('profile')
    api.compact_catalogue('other-profile')
    assert backend.calls==4 and backend.discover==[False]*4
    print(f'voice_catalogue_latency_ms: first={first_ms:.3f}, repeated={repeated_ms:.3f}')


def test_native_mcp_catalog_can_skip_discovery_for_call_context(monkeypatch):
    discoveries=[]
    core=SimpleNamespace(_servers={},_lazy_server_configs={})
    monkeypatch.setitem(sys.modules,'tools',SimpleNamespace(mcp_tool=core))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_config',SimpleNamespace(_load_mcp_config=lambda:{}))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_common',SimpleNamespace(mcp_server_enabled=lambda cfg:True))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_discovery',SimpleNamespace(discover_mcp_tools=lambda:discoveries.append(True)))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_registration',SimpleNamespace(
        _same_server_route=lambda *args,**kwargs:True,_make_tool_filter=lambda *args:lambda raw:True,
        _normalize_server_trust=lambda value:'trusted'))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_scope',SimpleNamespace(_resolve_server_key=lambda server:server))
    monkeypatch.setitem(sys.modules,'tools.mcp_tool_handlers',SimpleNamespace(
        _tool_is_read_only=lambda *args:False,_make_tool_handler=lambda *args:None))
    monkeypatch.setitem(sys.modules,'tools.registry',SimpleNamespace(registry=SimpleNamespace(
        get_all_entries=lambda:[],current_scope_key=lambda:'profile',_generation=0)))
    monkeypatch.setattr(g,'native_catalog',lambda:[])

    assert g.NativeMcp().catalog(discover=False)==[]
    assert discoveries==[]


def test_numbers_and_booleans_sent_as_strings_match_the_schema():
    schema = {'type': 'object', 'properties': {'days': {'type': 'integer'}, 'ratio': {'type': 'number'},
              'unread': {'type': 'boolean'}, 'query': {'type': 'string'}, 'ids': {'type': 'array', 'items': {'type': 'integer'}}}}
    sent = {'days': '2', 'ratio': '0.5', 'unread': 'True', 'query': '42', 'ids': ['1', 2.0]}
    assert g.coerce(schema, sent) == {'days': 2, 'ratio': 0.5, 'unread': True, 'query': '42', 'ids': [1, 2]}
    g.validate(schema, g.coerce(schema, sent))
    # Ambiguous values stay as sent and still fail validation.
    with pytest.raises(HTTPException):
        g.validate(schema, g.coerce(schema, {'days': 'tomorrow'}))

