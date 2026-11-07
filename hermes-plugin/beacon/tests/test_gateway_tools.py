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
spec=importlib.util.spec_from_file_location('beacon_gateway_test',ROOT/'gateway_tools.py')
g=importlib.util.module_from_spec(spec);spec.loader.exec_module(g)
SCOPE=('owner','password','profile','call')


DEFAULT_HERMES_AGENT_SOURCE=Path(os.environ.get('HERMES_AGENT_SOURCE') or Path.home()/'AppData'/'Local'/'hermes'/'hermes-agent')


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
        return {'conversation':getattr(self,'conversation',call),'principal':'','native_home':''}


class Backend:
    def __init__(self):
        self.read_only=True;self.version='a';self.calls=[];self.wait=None;self.started=threading.Event();self.result={'result':'real search result'}
    def catalog(self,discover=True):
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


def _owner_followup_receipt_producer():
    """A real second process performs one bounded synthetic operation against a shared receipt DB."""
    root=Path(sys.argv[2]);release=root/'release'
    class Producer(Backend):
        def execute(self,*args,**kwargs):
            with (root/'executions').open('a',encoding='utf-8') as log:log.write(str(os.getpid())+'\n')
            (root/'started').write_text(str(os.getpid()),encoding='utf-8')
            deadline=time.monotonic()+20
            while not release.exists() and time.monotonic()<deadline:time.sleep(.01)
            return json.dumps({'result':'synthetic producer result'})
    api=g.Gateway(Mobile(root/'producer.sqlite'),lambda profile,fn:fn(root),Producer())
    invoke(api)
    (root/('viewer-'+str(os.getpid()))).write_text('observing',encoding='utf-8')
    deadline=time.monotonic()+25
    while api.status(SCOPE,'r1')['status']=='running' and time.monotonic()<deadline:time.sleep(.01)


@pytest.fixture
def owner_followup_producer(tmp_path):
    import subprocess
    script="import sys;sys.path.insert(0,sys.argv[1]);from test_gateway_tools import _owner_followup_receipt_producer;_owner_followup_receipt_producer()"
    process=subprocess.Popen([sys.executable,'-X','utf8','-c',script,str(Path(__file__).parent),str(tmp_path)],
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        deadline=time.monotonic()+30
        while not (tmp_path/'started').exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.01)
        assert (tmp_path/'started').exists(),process.communicate(timeout=10)
        # Windows' venv launcher owns another PID than the interpreter running the operation.
        process.producer_pid=int((tmp_path/'started').read_text())
        assert process.producer_pid!=os.getpid()
        api=g.Gateway(Mobile(tmp_path/'producer.sqlite'),lambda profile,fn:fn(tmp_path),Backend())
        yield api,process,tmp_path
    finally:
        (tmp_path/'release').write_text('release',encoding='utf-8')
        try:process.communicate(timeout=10)
        except subprocess.TimeoutExpired:process.kill();process.communicate(timeout=5)


def test_owner_followup_red_foreign_running_producer_is_observed_without_poisoning(owner_followup_producer):
    api,process,root=owner_followup_producer
    observed=api.status(SCOPE,'r1')
    assert observed['status']=='running',observed
    assert 'result' not in observed
    (root/'release').write_text('release',encoding='utf-8')
    process.wait(timeout=10)
    assert api.status(SCOPE,'r1')['status']=='completed'
    assert api.status(SCOPE,'r1')['result']=={'result':'synthetic producer result'}


@pytest.mark.parametrize('boundary',['created','pid','boot','missing'])
def test_owner_followup_replaced_or_unknown_producer_stays_uncertain(owner_followup_producer,boundary):
    api,process,root=owner_followup_producer
    with api.db() as conn:
        value=json.loads(api._row(conn,SCOPE,'r1')['payload'])
        if boundary=='missing':value.pop('_producer')
        elif boundary=='created':value['_producer']['created']+=1
        elif boundary=='pid':value['_producer']['pid']=os.getpid()
        else:value['_producer']['boot']='different-generation'
        api._save(conn,SCOPE,'r1',value)
    assert api.status(SCOPE,'r1')['status']=='uncertain'
    (root/'release').write_text('release',encoding='utf-8');process.wait(timeout=10)
    assert api.status(SCOPE,'r1')['status']=='uncertain'
    assert 'result' not in api.status(SCOPE,'r1')
    assert invoke(api)['status']=='uncertain'
    assert len((root/'executions').read_text().splitlines())==1


def test_owner_followup_dead_producer_cannot_be_replayed(owner_followup_producer):
    import psutil
    api,process,root=owner_followup_producer
    with api.db() as conn:value=json.loads(api._row(conn,SCOPE,'r1')['payload'])
    producer=psutil.Process(process.producer_pid)
    assert producer.pid==value['_producer']['pid'] and producer.create_time()==value['_producer']['created']
    producer.terminate();producer.wait(timeout=5);process.wait(timeout=5)
    assert api.status(SCOPE,'r1')['status']=='uncertain'
    assert invoke(api)['status']=='uncertain'
    assert len((root/'executions').read_text().splitlines())==1


def test_owner_followup_second_process_observes_same_receipt_without_dispatch(owner_followup_producer):
    import subprocess
    api,_,root=owner_followup_producer
    script="import sys;sys.path.insert(0,sys.argv[1]);from test_gateway_tools import _owner_followup_receipt_producer;_owner_followup_receipt_producer()"
    second=subprocess.Popen([sys.executable,'-X','utf8','-c',script,str(Path(__file__).parent),str(root)],
        stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    try:
        deadline=time.monotonic()+6
        while len(list(root.glob('viewer-*')))<2 and second.poll() is None and time.monotonic()<deadline:time.sleep(.01)
        assert len(list(root.glob('viewer-*')))==2, (
            'Synthetic second viewer did not become ready',
            second.communicate(timeout=3) if second.poll() is not None else 'viewer process still starting')
        assert api.status(SCOPE,'r1')['status']=='running'
        assert len((root/'executions').read_text().splitlines())==1
        (root/'release').write_text('release',encoding='utf-8');second.communicate(timeout=10)
        assert api.status(SCOPE,'r1')['status']=='completed'
    finally:
        (root/'release').write_text('release',encoding='utf-8')
        try:second.communicate(timeout=10)
        except subprocess.TimeoutExpired:second.kill();second.communicate(timeout=5)


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


def test_a_read_only_tool_sending_a_body_or_a_write_method_still_needs_approval(gateway):
    api,backend,_=gateway
    backend.catalog=lambda discover=True:[{'tool_id':'a'*64,'name':'configured_fetch','description':'fetch a page',
        'parameters':{'type':'object','properties':{'query':{'type':'string'},'body':{'type':'string'},
                      'http_method':{'type':'string','enum':['GET','HEAD','DELETE']}},'additionalProperties':False},
        'read_only':True}]
    def call(request,**extra):
        return api.invoke(SCOPE,{'request_id':request,'tool_id':'a'*64,'arguments':{'query':'weather',**extra}})
    call('r1',http_method='GET')
    assert settled(api,'r1')['status']=='completed'
    assert call('r2',http_method='GET',body='x')['status']=='needs_approval'
    assert call('r3',http_method='DELETE')['status']=='needs_approval'
    assert len(backend.calls)==1


# Strix 1c4f37e 0012: an annotation or a read-sounding tool name never vouches for a write the arguments carry.
def test_a_read_only_tool_naming_a_write_in_its_action_still_needs_approval(gateway):
    api,backend,_=gateway
    backend.catalog=lambda discover=True:[{'tool_id':'a'*64,'name':'configured_manage','description':'read or change items',
        'parameters':{'type':'object','properties':{'query':{'type':'string'},'action':{'type':'string'},
                      'Operation':{'type':'string'}},'additionalProperties':False},
        'read_only':True}]
    def call(request,**extra):
        return api.invoke(SCOPE,{'request_id':request,'tool_id':'a'*64,'arguments':{'query':'weather',**extra}})
    call('r1',action='list')
    assert settled(api,'r1')['status']=='completed'
    # Strix 76b2ce6 vuln-0002: any word of the write vocabulary counts, not only the narrower list the arguments used to check.
    for index,extra in enumerate(({'action':'delete'},{'action':'sendEmail'},{'Operation':'purge_all'},
                                  {'action':'list','Operation':'update'},{'action':'deploy'},{'action':'destroy'},{'Operation':'charge'},
                                  {'action':'grant'},{'action':'deleteall'})):
        assert call(f'w{index}',**extra)['status']=='needs_approval'
    assert len(backend.calls)==1


@pytest.mark.parametrize('raw,trust,annotated,expected',[
    ('search_messages','full',True,True),
    ('search_messages','untrusted',True,True),
    ('GMAIL_LIST_THREADS','untrusted',True,True),
    ('search_messages','full',False,False),
    ('purge_all','full',True,False),
    ('purge_all','untrusted',True,False),
    ('deleteFile','full',True,False),
    ('cleanup','full',True,True),
    ('cleanup','untrusted',True,False),
    # A leading read verb makes a noun after it a thing (get_pull_request); a real write action anywhere still asks.
    ('get_pull_request','full',True,True),
    ('get_commit','full',True,True),
    ('list_workflow_runs','full',True,True),
    ('get_workflow_run','full',True,True),
    ('search_issues','full',True,True),
    ('get_and_delete_x','full',True,False),
    ('create_pull_request','full',True,False),
    ('merge_pull_request','full',True,False),
    ('send_report','full',True,False),
    ('run_workflow','full',True,False),
    ('get_pull_request','untrusted',True,False),
    # Strix 70c9aa7 #2: 'or' chains a second action, the added write verbs ask, and an added noun after a read verb still reads.
    ('get_or_call_user','full',True,False),
    ('list_or_run','full',True,False),
    ('message_user','full',True,False),
    ('rotate_secret','full',True,False),
    ('get_message','full',True,True),
    ('get_process','full',True,True),
    ('get_message','untrusted',True,False),
    # Strix 76b2ce6 vuln-0002: a write verb glued to another word, or one the lists lacked, no longer reads as a read; real reads stay.
    ('search_wipedb','full',True,False),
    ('search_wipedb','untrusted',True,False),
    ('list_deleteall','full',True,False),
    ('getaccountsresetpassword','full',True,False),
    ('search_charge_card','full',True,False),
    ('search_charge_card','untrusted',True,False),
    ('get_attachment','full',True,True),
    ('list_connections','full',True,True),
    ('list_deployments','full',True,True),
    ('get_transcript','untrusted',True,True),
],ids=lambda value:str(value))
def test_a_servers_read_only_annotation_needs_a_name_that_agrees(gateway,monkeypatch,raw,trust,annotated,expected):
    api,backend,_=gateway
    monkeypatch.setattr(g,'native_catalog',lambda:[])
    def handler(): return raw
    server_name='files';tool_name=raw;tool_timeout=10
    def registered(*args): return handler(),server_name,tool_name,tool_timeout
    entry=SimpleNamespace(name=raw,toolset='mcp-files',handler=registered,schema={'description':raw,'parameters':{
        'type':'object','properties':{'prefix':{'type':'string'}},'required':['prefix'],'additionalProperties':False}})
    cfg={'url':'https://example.invalid','trust':trust}
    modules={
        'tools':SimpleNamespace(mcp_tool=SimpleNamespace(_servers={},_lazy_server_configs={'files':cfg})),
        'tools.mcp_tool_config':SimpleNamespace(_load_mcp_config=lambda:{'files':cfg}),
        'tools.mcp_tool_common':SimpleNamespace(mcp_server_enabled=lambda cfg:True),
        'tools.mcp_tool_discovery':SimpleNamespace(discover_mcp_tools=lambda:None),
        'tools.mcp_tool_registration':SimpleNamespace(_same_server_route=lambda *a,**k:True,
            _make_tool_filter=lambda *a:lambda raw:True,_normalize_server_trust=lambda value:value),
        'tools.mcp_tool_scope':SimpleNamespace(_resolve_server_key=lambda server:server),
        'tools.mcp_tool_handlers':SimpleNamespace(_tool_is_read_only=lambda *args:annotated),
        'tools.registry':SimpleNamespace(registry=SimpleNamespace(current_scope_key=lambda:'scope',get_all_entries=lambda:[entry])),
    }
    for name,module in modules.items(): monkeypatch.setitem(sys.modules,name,module)

    tool=next(row for row in g.NativeMcp().catalog() if row['_raw']==raw)
    assert tool['read_only'] is expected and tool['_trust']==trust

    backend.catalog=lambda discover=True:[tool]
    receipt=api.invoke(SCOPE,{'request_id':'r1','tool_id':tool['tool_id'],'arguments':{'prefix':'all'}})
    assert (receipt['status']=='needs_approval')==(not expected)
    if expected: assert settled(api,'r1')['status']=='completed'
    else: assert not backend.calls


ADDED_WRITE_VERBS=('message','ping','alert','comment','escalate','triage','process','truncate','rotate','suspend','terminate',
                   'drop','rebuild','reindex','kill','restart','reboot','rollback','revert','resend','ban')
ADDED_NOUNS=('message','alert','comment','process')


# Strix 70c9aa7 #2: these verbs were missing from the lists, so a name or an action built from them read as a read.
@pytest.mark.parametrize('verb',ADDED_WRITE_VERBS)
def test_each_added_write_verb_writes_as_a_tool_name_and_as_an_action(verb):
    assert g.name_writes(f'{verb}_target') and not g.mcp_reads_by_name(f'{verb}_target','full')
    assert g.writes_by_action({'action':verb}) and g.writes_by_action({'Operation':verb.upper()})
    assert g.name_writes(f'get_and_{verb}') and g.name_writes(f'search_or_{verb}')


@pytest.mark.parametrize('verb',[word for word in ADDED_WRITE_VERBS if word not in ADDED_NOUNS])
def test_an_added_verb_that_is_not_a_noun_still_writes_after_a_leading_read_verb(verb):
    assert g.name_writes(f'get_{verb}') and g.name_writes(f'list_{verb}_status')


@pytest.mark.parametrize('name',['get_message','getMessage','get_comment','list_comments','get_process','get_alert',
                                 'read_message_thread','search_messages'])
def test_an_added_noun_after_a_leading_read_verb_is_a_thing_not_an_action(name):
    assert not g.name_writes(name) and g.mcp_reads_by_name(name,'full')


def test_an_untrusted_server_counts_an_added_noun_as_a_write_like_every_other_noun():
    assert not g.mcp_reads_by_name('get_message','untrusted') and not g.mcp_reads_by_name('get_comment','untrusted')
    assert g.mcp_reads_by_name('list_comments','untrusted')


# 'get_x_or_delete_y' already wrote through 'delete'; the others read before 'or' chained, because call and run are nouns.
@pytest.mark.parametrize('name',['get_or_call_user','get_or_run_pipeline','list_or_run','getOrCallUser','get_x_or_delete_y',
                                 'search_or_message_user'])
def test_or_chains_a_second_action_like_and_and_then(name):
    assert g.name_writes(name) and not g.mcp_reads_by_name(name,'full') and not g.mcp_reads_by_name(name,'untrusted')


def test_or_in_an_action_argument_is_not_a_write_by_itself():
    assert not g.writes_by_action({'action':'list or search'}) and g.writes_by_action({'action':'list or delete'})


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
        'tools.mcp_tool_handlers':SimpleNamespace(_tool_is_read_only=lambda *args:True),
        'tools.registry':SimpleNamespace(registry=registry),
    }
    for name,module in modules.items(): monkeypatch.setitem(sys.modules,name,module)

    tools={tool['_raw']:tool for tool in g.NativeMcp().catalog()}
    assert tools['http_fetch']['read_only'] is False
    assert tools['safe_fetch']['read_only'] is True
    assert tools['http_fetch']['_timeout']==10 and '_handler' not in tools['http_fetch']
    assert tools['make_request']['parameters']['properties']['method']['enum']==['GET']

    backend.catalog=lambda discover=True:[tools['http_fetch']]
    proposal=api.invoke(SCOPE,{'request_id':'post','tool_id':tools['http_fetch']['tool_id'],
        'arguments':{'url':'https://example.invalid','method':'POST'}})
    assert proposal['status']=='needs_approval' and not backend.calls


def test_get_only_method_default_is_receipted_and_executed(gateway):
    api,backend,_=gateway
    tool={'tool_id':'c'*64,'name':'make_request','description':'HTTP request',
        'parameters':{'type':'object','properties':{'url':{'type':'string'},
            'method':{'type':'string','enum':['GET'],'default':'GET'}},
            'required':['url'],'additionalProperties':False},'read_only':False}
    backend.catalog=lambda discover=True:[tool]
    proposal=api.invoke(SCOPE,{'request_id':'get-default','tool_id':tool['tool_id'],
        'arguments':{'url':'https://example.invalid'}})
    assert proposal['status']=='needs_approval'
    assert proposal['proposal']['arguments']=={'url':'https://example.invalid','method':'GET'}
    api.decide(SCOPE,'get-default',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    assert settled(api,'get-default')['status']=='completed'
    assert backend.calls[0][0]['method']=='GET'


def test_receipt_deadline_covers_native_mcp_tool_timeout(gateway,monkeypatch):
    api,backend,mobile=gateway
    tool={'tool_id':'a'*64,'name':'configured_search','description':'search web',
          'parameters':{'type':'object','properties':{'query':{'type':'string'}},
                        'required':['query'],'additionalProperties':False},
          'read_only':True,'_timeout':300}
    backend.catalog=lambda discover=True:[tool]
    class Slots:
        def acquire(self,blocking=False): return True
        def release(self): pass
    monkeypatch.setattr(g,'SLOTS',Slots())
    monkeypatch.setattr(g.POOL,'submit',lambda *args,**kwargs:None)

    receipt=invoke(api,'slow-mcp')

    assert receipt['status']=='running'
    with mobile.connect() as conn:
        payload=json.loads(conn.execute("SELECT payload FROM tool_receipts WHERE request_id='slow-mcp'").fetchone()[0])
    assert payload['_deadline_seconds']>=330


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
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda name,args,**kwargs:handler(args,**kwargs)))
    tool={'name':'mcp__isolated__write','_server':'isolated','_raw':'write','_trust':'untrusted'}
    g.NativeMcp().execute(tool,{},SCOPE,True)
    assert outcomes[0] is None and outcomes[1]=='decline' and outcomes[2] is not None
    outcomes.clear();g.NativeMcp().execute(tool,{},SCOPE,False)
    assert outcomes[0] is not None


@pytest.mark.parametrize('prompt_kind',['arguments','budget','trusted_budget','changed_text'])
@pytest.mark.parametrize('approved',[True,False])
def test_changed_native_mcp_consent_stays_denied_and_explains_native_approval(tmp_path,monkeypatch,prompt_kind,approved):
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    from tools import approval
    pending={};choices=[];calls=[]
    monkeypatch.setattr(approval,'register_gateway_notify',lambda key,callback:pending.update(callback=callback))
    monkeypatch.setattr(approval,'unregister_gateway_notify',lambda key:pending.clear())
    monkeypatch.setattr(approval,'resolve_gateway_approval',lambda key,choice,**kwargs:choices.append((choice,kwargs['request_id'])))
    expected="MCP tool 'write' on UNTRUSTED server 'isolated' wants to run. This tool is write-capable (no readOnlyHint=true annotation) and may modify external state."
    prompts={
        'arguments':expected+'\n\nArguments:\n{"message": "fixture"}',
        'budget':"MCP tool 'write' on UNTRUSTED server 'isolated' wants to run. This call goes past the chat's spend limit. This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.",
        'trusted_budget':"MCP tool 'write' on server 'isolated' would take this chat past its spend limit.",
        'changed_text':expected+' ',
    }
    def handler(name,args,**kwargs):
        calls.append((name,args))
        pending['callback']({'request_id':'b'*32,'pattern_key':'mcp_elicitation','command':prompts[prompt_kind]})
        return '{"error":"Native approval denied"}'
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(handle_function_call=handler))
    tool={'name':'mcp__isolated__write','_server':'isolated','_raw':'write',
          '_trust':'full' if prompt_kind=='trusted_budget' else 'untrusted'}

    result=json.loads(g.NativeMcp().execute(tool,{'message':'fixture'},SCOPE,approved))

    assert choices==[('deny','b'*32)] and calls==[(tool['name'],{'message':'fixture'})]
    assert result['error']==('Hermes needs this approval. Approve the action in Hermes.' if approved else 'Native approval denied')
    assert not pending


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
    def checked_catalog(discover=True):
        assert not lock._is_owned();calls.append(discover);return catalog(discover)
    def checked_execute(*args,**kwargs):
        assert not lock._is_owned();return execute(*args,**kwargs)
    backend.catalog=checked_catalog;backend.execute=checked_execute
    invoke(api);assert settled(api)['status']=='completed' and len(calls)==2


def test_gateway_lock_order_keeps_mobile_lock_outside_profile_scope(gateway):
    api,backend,mobile=gateway
    active=threading.local();inversions=[]

    class MobileLock:
        def __init__(self): self.lock=threading.RLock()
        def __enter__(self):
            if getattr(active,'profile',False): inversions.append('profile->mobile')
            self.lock.acquire();return self
        def __exit__(self,*_): self.lock.release()

    mobile.LOCK=MobileLock()
    def settings(profile=None):
        with mobile.LOCK:
            return {'owner_id':'owner','owner_provider':'password','profiles':['profile']}
    mobile.settings=settings

    def scoped(profile,fn):
        prior=getattr(active,'profile',False);active.profile=True
        try: return fn(mobile.path.parent)
        finally: active.profile=prior

    api.scoped=scoped
    invoke(api,'lock-order')
    result=settled(api,'lock-order')

    assert result['status']=='completed' and backend.calls
    assert inversions==[]


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
    def fail_worker_catalog(discover=True):
        nonlocal calls
        calls+=1
        if calls>1: raise RuntimeError('Private catalog failure')
        return original(discover)
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
        monkeypatcher.setitem(sys.modules,'beacon_gateway_tools',SimpleNamespace(Gateway=ExplodingGateway))
        module_spec=importlib.util.spec_from_file_location('beacon_gateway_api_cancel_test',ROOT/'gateway_api.py')
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
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(
        handle_function_call=lambda name,args,**kwargs:handler(args,**kwargs)))
    tool={'name':'mcp__isolated__write','_server':'isolated','_raw':'write','_trust':'untrusted'}
    g.NativeMcp().execute(tool, {},SCOPE,True)
    assert choices.count('once')==1 and choices.count('deny')==11


def test_router_reuses_native_owner_profile_and_closed_call_authority(tmp_path,monkeypatch):
    from fastapi import FastAPI
    from fastapi.testclient import TestClient
    from test_mobile import harness, reserve
    # Borrow the real dashboard's authentication/call checks with a private fixture DB.
    host,client,cfg,root,app=next(harness.__wrapped__(tmp_path,monkeypatch))
    spec=importlib.util.spec_from_file_location('beacon_gateway_api_test',ROOT/'gateway_api.py')
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    native_module=sys.modules['beacon_gateway_tools']
    backend=Backend()
    monkeypatch.setattr(native_module,'NativeMcp',lambda:backend)
    monkeypatch.setattr(native_module.Gateway,'catalog',lambda self,profile,discover=True:backend.catalog(discover=discover))
    app.include_router(api.build_router(host.call,host.body,lambda p,fn:fn(tmp_path),host.mobile),prefix='/api/plugins/beacon')
    call=reserve(client)
    path=f'/api/plugins/beacon/mobile/calls/{call}/tools'
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
    host,_,_,_,_=next(harness.__wrapped__(tmp_path,monkeypatch))
    spec=importlib.util.spec_from_file_location('beacon_gateway_api_session_test',ROOT/'gateway_api.py')
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    backend=Backend();native_module=sys.modules['beacon_gateway_tools']
    monkeypatch.setattr(native_module,'NativeMcp',lambda:backend)
    app=FastAPI();app.state.auth_required=True
    @app.middleware('http')
    async def authenticated(request:Request,call_next):
        request.state.session=SimpleNamespace(user_id='owner',provider='basic')
        return await call_next(request)
    app.include_router(api.build_router(host.call,host.body,lambda p,fn:fn(tmp_path),host.mobile),
                       prefix='/api/plugins/beacon')
    call=host.mobile.reserve('owner','default','native-stored')['call_id']
    path=f'/api/plugins/beacon/mobile/calls/{call}/tools'
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
    def tool(name,description,read_only=True,**extra):
        return {'tool_id':name.encode().hex().ljust(64,'0')[:64], 'name':name,
                'description':description, 'parameters':{'type':'object','properties':{}}, 'read_only':read_only,**extra}
    backend.catalog=lambda discover=True:[
        tool('search_contacts','Search contact names and email addresses'),
        tool('manage_connections','Manage connected apps including Gmail',False,_kind='hermes',_toolset='connectors',_raw='manage_connections'),
        tool('send_gmail_message','Sends an email using the user Gmail account',False),
        tool('mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1',
             'Searches messages in a Gmail account based on a query',_raw='google_workspace_search_gmail',_server='tool_gateway_meeting'),
        tool('mcp__tool_gateway_meeting__google_workspace_get_gmail_t_3ae9e308',
             'Gets a Gmail message thread by id',_raw='google_workspace_get_gmail_thread',_server='tool_gateway_meeting'),
        tool('web_search','Read current web information from a search query'),
    ]
    found=api.discover('profile','Could you pull up my latest five emails?')['tools']
    assert found and found[0]['name']=='mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1'
    assert api.discover('profile','check my emails')['tools'][0]['name']=='mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1'
    assert 'manage_connections' not in {tool['name'] for tool in found}
    assert api.discover('profile','email')['tools'][0]['name']=='mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1'
    assert api.discover('profile','send email')['tools'][0]['name']=='send_gmail_message'
    assert api.discover('profile','search the web for current news')['tools'][0]['name']=='web_search'
    assert api.discover('profile','neutron triangulation flux')['tools']==[]


def test_discovery_does_not_cache_away_new_mcp_tools(gateway):
    api,backend,_=gateway
    assert api.discover('profile','weather')['tools']==[]
    original=backend.catalog
    backend.catalog=lambda discover=True:original(discover)+[{'tool_id':'b'*64,'name':'new_mcp_weather',
        'description':'Current weather forecasts','parameters':{'type':'object'},'read_only':True}]
    assert api.discover('profile','Please get the latest weather forecast')['tools'][0]['name']=='new_mcp_weather'


def test_public_web_discovery_ranks_web_tools_above_mail_web_links(gateway):
    api,backend,_=gateway
    def tool(name,description):
        return {'tool_id':name.encode().hex().ljust(64,'0')[:64],'name':name,
                'description':description,'parameters':{'type':'object'},'read_only':True}
    backend.catalog=lambda discover=True:[
        tool('search_gmail_messages','Search Gmail messages and return web interface links'),
        tool('search_contacts','Search contacts with web profile links'),
        tool('fetch_web_page','Fetch a public web page with search engine referer support'),
    ]
    assert api.discover('profile','web search')['tools'][0]['name']=='fetch_web_page'


def test_discovery_preserves_inflected_description_matches(gateway):
    api,backend,_=gateway
    backend.catalog=lambda discover=True:[
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
        ('beacon_create_proactive_request','beacon-interactive'),('beacon_email_notice','beacon-mailbox'),
        ('beacon_event_notice','beacon-mailbox'),('beacon_events_checked','beacon-mailbox'),
        ('hindsight_recall','memory'),('hindsight_reflect','memory'),
        ('hindsight_search_knowledge_pages','memory'),('hindsight_read_knowledge_page','memory'),
        ('hindsight_retain','memory'),
        ('manage_connections','connectors'),('manage_catalog','connectors'),('setup_mcp','connectors'),
        ('mcp-weather','mcp-weather'),('metadata_read','custom'),
        ('phone_calendars','beacon-phone'),('phone_calendar_list','beacon-phone'),('phone_contacts_search','beacon-phone'),
        ('phone_calendar_create','beacon-phone'),('phone_calendar_update','beacon-phone'),('phone_calendar_delete','beacon-phone'),
        ('phone_reminder_create','beacon-phone'),('phone_alarm_set','beacon-phone'),('phone_timer_set','beacon-phone'),
        ('phone_call','beacon-phone'),('phone_message_draft','beacon-phone'),
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
             'beacon-interactive','beacon-mailbox','connectors','mcp-weather','custom','beacon-phone'}
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
    memory_tools={'hindsight_recall','hindsight_reflect','hindsight_search_knowledge_pages','hindsight_read_knowledge_page',
                  'hindsight_retain'}
    assert memory_tools <= set(catalog)
    # A provider tool is not read-only because of its name; only the tool itself can say so.
    assert not any(catalog[name]['read_only'] for name in memory_tools)
    # Hermes' memory and session tools reach a call through their own handlers; the rest stay out, each for a stated reason.
    assert catalog['session_search']['read_only'] and catalog['session_search']['_inline']=='session_search'
    assert not catalog['memory']['read_only'] and catalog['memory']['_inline']=='memory'
    assert not any('_inline' in catalog[name] for name in ('read_file','write_file','web_search'))
    assert not {'todo_list','clarify','delegate_task','browser_click','beacon_create_proactive_request',
                'beacon_email_notice','beacon_event_notice','beacon_events_checked',
                'mcp-weather','video_analyze','manage_connections','manage_catalog','setup_mcp'} & set(catalog)
    # The phone tools reach a call through the app, which shows the owner each card; Hermes' copies never appear in the call's catalogue.
    assert not {name for name,_ in names if name.startswith('phone_')} & set(catalog)
    assert catalog['read_file']['read_only'] and catalog['web_search']['read_only']
    assert not catalog['web_extract']['read_only'] and not catalog['vision_analyze']['read_only']
    assert not catalog['metadata_read']['read_only']
    assert not catalog['write_file']['read_only']
    assert seen['enabled_toolsets']==sorted(enabled)
    assert seen['disabled_toolsets']==['video']
    assert seen['skip_tool_search_assembly'] is True

    backend=SimpleNamespace(catalog=lambda discover=True:native_catalog)
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


def test_warmed_catalogue_is_off_request_path_and_summarizes_each_source_once(tmp_path):
    started=threading.Event();release=threading.Event();finished=threading.Event();calls=[]
    profile='warm-'+str(time.time_ns())
    full=[
        {'name':'mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1',
         '_raw':'google_workspace_search_gmail','_server':'tool_gateway_meeting',
         'description':'Search Gmail messages by query','read_only':True},
        {'name':'mcp__tool_gateway_meeting__google_workspace_get_gmail_t_3ae9e308',
         '_raw':'google_workspace_get_gmail_thread','_server':'tool_gateway_meeting',
         'description':'Get Gmail message threads by id','read_only':True},
        {'name':'hindsight_recall','_raw':'hindsight_recall','_toolset':'memory',
         'description':'Recall relevant Hindsight memories','read_only':True},
    ]
    partial=[{'name':'read_file','_toolset':'file','description':'Read a local file','read_only':True}]

    class Backend:
        def cache_key(self): return 'warm-cache'
        def catalog(self,discover=True):
            calls.append(discover)
            if discover:
                started.set()
                release.wait(2)
                finished.set()
                return full
            return partial

    api=g.Gateway(Mobile(tmp_path/'warm.sqlite'),lambda name,fn:fn(tmp_path),Backend())
    begin=time.perf_counter()
    assert api.warm_catalogue(profile) is True
    assert (time.perf_counter()-begin)<0.1
    assert started.wait(1)
    try:
        begin=time.perf_counter()
        while_warming=api.compact_catalogue(profile)
        assert (time.perf_counter()-begin)<0.5
        assert 'read file' in while_warming
    finally:
        release.set()
    assert finished.wait(1)

    summary=api.compact_catalogue(profile)
    # One line per source, each named by the source itself (Hermes, then each MCP server), never by a guessed service.
    assert summary.splitlines()==['Hermes: hindsight recall',
                                  'tool gateway meeting: google workspace (2): get gmail thread, search gmail']
    assert calls==[True,False]


def test_plugin_registration_never_starts_mcp_discovery(monkeypatch):
    # register() runs in every Hermes process (CLI, cron, gateway); only the dashboard, which serves calls, warms.
    from test_mobile import load_private

    warmed=[]
    monkeypatch.setitem(sys.modules,'beacon_gateway_tools',SimpleNamespace(
        Gateway=lambda mobile,scoped:SimpleNamespace(warm_catalogue=lambda profile:warmed.append(profile))))
    monkeypatch.setitem(sys.modules,'beacon_voice_context',SimpleNamespace(scoped=lambda profile,fn:fn(None)))
    plugin=load_private('__init__')
    monkeypatch.setattr(plugin,'current_profile',lambda cfg:'work')
    context=SimpleNamespace(register_tool=lambda **kwargs:None,register_hook=lambda *args:None)

    plugin.register(context)

    assert warmed==[]


def test_first_discover_after_a_restart_waits_for_mcp_tools(tmp_path):
    profile='cold-'+str(time.time_ns());discovered=threading.Event()
    mail={'name':'mcp__tool_gateway_meeting__google_workspace_search_gmai_f6f8eca1','_raw':'google_workspace_search_gmail',
          '_server':'tool_gateway_meeting','description':'Search Gmail messages by query','read_only':True,
          'tool_id':'mail','parameters':{'type':'object','properties':{}}}
    local={'name':'read_file','_toolset':'file','description':'Read a local file','read_only':True,
           'tool_id':'file','parameters':{'type':'object','properties':{}}}

    class Backend:
        def cache_key(self): return 'cold-cache'
        def catalog(self,discover=True):
            if discover:
                time.sleep(0.3);discovered.set()
            return [local,mail] if discovered.is_set() else [local]

    api=g.Gateway(Mobile(tmp_path/'cold.sqlite'),lambda name,fn:fn(tmp_path),Backend())
    assert api.discover(profile,'check my emails')['tools'][0]['name']==mail['name']


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
    native.catalog=lambda discover=True:[
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
    mobile.owned=lambda conn,call,owner,profile,writable:{'conversation':'native-stored','principal':'','native_home':''}
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
    # Each run has its own tool_call_id: Hermes keys concurrent hook runs by it, and without one a second tool is blocked.
    assert handled[0][2]['tool_call_id'].startswith('beacon-tool/')

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
    monkeypatch.setattr(type(registry),'dispatch',lambda self,*args,**kwargs:dispatched.append((args,kwargs)) or {'ok':True})
    session_context=SimpleNamespace(set_session_vars=lambda **kwargs:session.update(kwargs) or kwargs,
                                    clear_session_vars=lambda _:session.clear())
    monkeypatch.setitem(sys.modules,'gateway',SimpleNamespace(session_context=session_context))

    if kind=='hermes':
        tool={'name':'read_file','_kind':'hermes','_raw':'read_file','_enabled_tools':['read_file'],'read_only':True}
    else:
        tool={'name':'mcp__mail__search_messages','_kind':'mcp','_raw':'search_messages','_server':'mail',
              '_trust':'trusted','read_only':True}
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
            return [{'name':f'tool_{i:03}','description':'A useful tool that reads data safely. '*5,
                     'read_only':True,'_toolset':'file'}
                    for i in range(100)]
    backend=Backend();api=g.Gateway(Mobile(tmp_path/'catalog.sqlite'),lambda profile,fn:fn(tmp_path),backend)
    first=api.compact_catalogue('profile')
    again=api.compact_catalogue('profile')
    assert first==again and len(first.encode('utf-8'))<=g.MAX_CATALOGUE_BYTES
    assert first.startswith('Hermes: file (100): tool 000') and backend.calls==2 and backend.discover==[False,False]
    api.compact_catalogue('profile')
    api.compact_catalogue('other-profile')
    assert backend.calls==4 and backend.discover==[False]*4


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
        _tool_is_read_only=lambda *args:False))
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


def native_environment(monkeypatch,names,enabled,disabled=(),parameters=None):
    """Hermes' registry and config as native_catalog reads them, for the API platform."""
    entries={name:SimpleNamespace(name=name,toolset=toolset,schema={'description':name}) for name,toolset in names}
    definitions=[{'type':'function','function':{'name':name,'description':name,
                  'parameters':(parameters or {}).get(name,{'type':'object','properties':{}})}} for name,_ in names]
    config={'agent':{'disabled_toolsets':list(disabled)},'memory':{'provider':'mnemo'}}
    registry=SimpleNamespace(current_scope_key=lambda:'scope',_generation=1,get_entry=lambda name:entries.get(name))
    monkeypatch.setitem(sys.modules,'agent.skill_utils',SimpleNamespace(parse_config_string_list=lambda value:list(value or [])))
    monkeypatch.setitem(sys.modules,'hermes_cli.config',SimpleNamespace(load_config_readonly=lambda:config))
    monkeypatch.setitem(sys.modules,'hermes_cli.tools_config',SimpleNamespace(_get_platform_tools=lambda cfg,platform:set(enabled)))
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(get_tool_definitions=lambda **kwargs:definitions))
    monkeypatch.setitem(sys.modules,'tools.registry',SimpleNamespace(registry=registry))
    return config


def test_memory_provider_tools_join_the_catalogue_under_the_owners_memory_gate(monkeypatch):
    provided=[{'name':'mnemo_search','description':'Search saved memory','parameters':{
                  'type':'object','properties':{'query':{'type':'string'}},'required':['query']}},
              {'name':'read_file','description':'named like a Hermes tool','parameters':{'type':'object'}}]
    asked=[]
    monkeypatch.setitem(sys.modules,'beacon_voice_context',SimpleNamespace(
        provider_tool_schemas=lambda cfg:asked.append(cfg['memory']['provider']) or provided))
    native_environment(monkeypatch,[('read_file','file')],['file','memory'])

    catalog={row['name']:row for row in g.native_catalog()}

    assert set(catalog)=={'read_file','mnemo_search'} and asked==['mnemo']
    tool=catalog['mnemo_search']
    assert tool['read_only'] is False and tool['_kind']=='hermes' and tool['_inline']=='provider'
    assert tool['_raw']=='mnemo_search' and tool['parameters']['required']==['query'] and len(tool['tool_id'])==64
    for enabled,disabled in ((['file'],[]),(['file','memory'],['memory'])):
        asked.clear()
        native_environment(monkeypatch,[('read_file','file')],enabled,disabled)
        assert {row['name'] for row in g.native_catalog()}=={'read_file'} and asked==[]


def test_a_call_searches_its_own_profile_only(monkeypatch):
    parameters={'session_search':{'type':'object','properties':{'query':{'type':'string'},'profile':{'type':'string'}}}}
    native_environment(monkeypatch,[('session_search','session_search')],['session_search'],parameters=parameters)
    monkeypatch.delitem(sys.modules,'beacon_voice_context',raising=False)

    tool=g.native_catalog()[0]

    assert set(tool['parameters']['properties'])=={'query'}


def test_asking_to_remember_something_finds_the_memory_tool(gateway):
    api,backend,_=gateway
    backend.catalog=lambda discover=True:[
        {'tool_id':'a'*64,'name':'read_file','description':'Read a local file','parameters':{'type':'object'},'read_only':True},
        {'tool_id':'b'*64,'name':'memory','description':'Save durable facts to persistent memory that survive across sessions',
         'parameters':{'type':'object'},'read_only':False}]
    assert [tool['name'] for tool in api.discover('profile','remember that my favorite color is green')['tools']]==['memory']


def test_a_refusal_before_anything_ran_is_a_certain_failure_even_for_a_write():
    assert g.outcome({'error':'No provider','not_started':True},False,own=True)=='failed'
    # A connected server's own payload cannot claim that nothing ran.
    assert g.outcome({'error':'Server said so','not_started':True},False)=='uncertain'
    assert g.outcome({'error':'Provider crashed'},False,own=True)=='uncertain'
    assert g.outcome({'result':'saved'},False)=='completed'


def test_a_call_leaves_out_the_tools_that_need_an_agent_turn():
    assert {'clarify','delegate_task','todo_list'}<=g.NATIVE_EXCLUDED_TOOLS and 'browser' in g.NATIVE_EXCLUDED_TOOLSETS
    assert not {'memory','session_search'}&g.NATIVE_EXCLUDED_TOOLS


def test_a_call_leaves_out_every_phone_tool_hermes_has():
    phone_spec=importlib.util.spec_from_file_location('beacon_phone_tools_test',ROOT/'phone_tools.py')
    phone_tools=importlib.util.module_from_spec(phone_spec);phone_spec.loader.exec_module(phone_tools)
    assert len(phone_tools.NAMES)==11 and set(phone_tools.NAMES)<=g.NATIVE_EXCLUDED_TOOLS
    assert phone_tools.TOOLSET in g.NATIVE_EXCLUDED_TOOLSETS


def test_inline_tools_run_through_the_call_memory_with_the_calls_authority(tmp_path,monkeypatch):
    seen=[];session={}
    monkeypatch.setitem(sys.modules,'gateway',SimpleNamespace(session_context=SimpleNamespace(
        set_session_vars=lambda **kwargs:session.update(kwargs) or kwargs,clear_session_vars=lambda _:session.clear())))
    monkeypatch.setitem(sys.modules,'hermes_constants',SimpleNamespace(get_hermes_home=lambda:tmp_path))
    monkeypatch.setitem(sys.modules,'beacon_voice_context',SimpleNamespace(
        call_inline_tool=lambda *args:seen.append(args) or '{"success": true}'))
    memory={'_kind':'hermes','_raw':'memory','_inline':'memory','read_only':False}
    search={'_kind':'hermes','_raw':'session_search','_inline':'session_search','read_only':True}

    with pytest.raises(HTTPException) as error: g.NativeMcp().execute(memory,{'target':'user'},SCOPE,False,session_id='native-stored')
    assert error.value.status_code==403 and seen==[]
    assert g.NativeMcp().execute(search,{'query':'tea'},SCOPE,False,session_id='native-stored')=='{"success": true}'
    g.NativeMcp().execute(memory,{'target':'user'},SCOPE,True,session_id='native-stored')

    assert seen==[('session_search','session_search',{'query':'tea'},SCOPE,'native-stored',tmp_path),
                  ('memory','memory',{'target':'user'},SCOPE,'native-stored',tmp_path)]


@pytest.mark.parametrize('inline,name,result',[
    ('memory','memory',{'success':False,'error':'Memory is full'}),
    ('provider','mnemo_save',{'error':'The memory provider is not available for this chat.','not_started':True}),
])
def test_memory_write_asks_each_time_and_a_refusal_is_a_clean_failure(gateway,inline,name,result):
    api,backend,_=gateway
    tool={'tool_id':'e'*64,'name':name,'description':'Save durable facts','read_only':False,
          'parameters':{'type':'object','properties':{'action':{'type':'string'},'target':{'type':'string'},
                        'content':{'type':'string'}},'required':['target']},
          '_kind':'hermes','_raw':name,'_inline':inline}
    backend.catalog=lambda discover=True:[tool]
    backend.result=result

    proposal=api.invoke(SCOPE,{'request_id':'save','tool_id':tool['tool_id'],
                               'arguments':{'action':'add','target':'user','content':'Likes tea'}})

    assert proposal['status']=='needs_approval' and proposal['proposal']['allow_call'] is False
    with pytest.raises(HTTPException) as error:
        api.decide(SCOPE,'save',{'proposal_id':proposal['proposal']['id'],'decision':'approve_call'})
    assert error.value.status_code==409 and not backend.calls
    api.decide(SCOPE,'save',{'proposal_id':proposal['proposal']['id'],'decision':'approve'})
    receipt=settled(api,'save')
    assert receipt['status']=='failed' and receipt['result']['error']==result['error']


def test_a_stale_tool_list_is_served_at_once_and_refreshed_behind_it(tmp_path,monkeypatch):
    # Generic rows: whatever a profile has configured, a call never waits for a scan once a list exists.
    rows=[{'name':'read_file','_toolset':'file','description':'Read a local file','read_only':True}]
    scans=[];release=threading.Event()

    class Backend:
        def cache_key(self): return 'stale-cache'
        def catalog(self,discover=True):
            scans.append(discover)
            if discover and len(scans)>1: release.wait(2)
            return rows

    api=g.Gateway(Mobile(tmp_path/'stale.sqlite'),lambda name,fn,**kwargs:fn(tmp_path),Backend())
    assert api.prefetch('stale-profile')=='Hermes: read file'
    assert scans==[True]
    key=('stale-profile','stale-cache')
    with g.CATALOG_LOCK: created,cached=g.CATALOG_CACHE[key]; g.CATALOG_CACHE[key]=(created-g.CATALOG_TTL-1,cached)
    begin=time.perf_counter()
    assert api.catalog('stale-profile',discover=False)==rows, 'the older list is served'
    assert (time.perf_counter()-begin)<0.1, 'without waiting for the refresh'
    release.set()
    deadline=time.monotonic()+2
    while len(scans)<2 and time.monotonic()<deadline: time.sleep(0.01)
    assert scans==[True,True], 'and refreshed behind it'
    with g.CATALOG_LOCK: g.CATALOG_CACHE[key]=(time.monotonic()-g.CATALOG_STALE_TTL-1,rows)
    assert api._cached_catalog('stale-profile') is None, 'a list too old to trust is scanned again'


def test_prefetch_is_the_first_scan_discover_waits_for(tmp_path):
    started=threading.Event();release=threading.Event()

    class Backend:
        def cache_key(self): return 'first-scan'
        def catalog(self,discover=True):
            if discover: started.set();release.wait(2)
            return [{'name':'search_files','_toolset':'file','description':'Search files','read_only':True}]

    api=g.Gateway(Mobile(tmp_path/'first.sqlite'),lambda name,fn,**kwargs:fn(tmp_path),Backend())
    worker=threading.Thread(target=api.prefetch,args=('first-profile',));worker.start()
    assert started.wait(1)
    assert g.CATALOG_FIRST_WARM.get('first-profile') is worker
    assert api.warm_catalogue('first-profile') is False, 'a running prefetch is not scanned twice'
    release.set();worker.join(2)
    assert not worker.is_alive()


def test_call_overview_names_every_source_and_its_write_tools(tmp_path):
    rows=[{'name':'mcp__acme_browser__page_navigate','_raw':'page-navigate','_server':'acme_browser','description':'Open a page','read_only':False},
          {'name':'mcp__acme_browser__page_click','_raw':'page-click','_server':'acme_browser','description':'Click on a page','read_only':False},
          {'name':'mcp__acme_suite__notes_search','_raw':'notes_search','_server':'acme_suite','description':'Search notes','read_only':True},
          {'name':'read_file','_toolset':'file','_kind':'hermes','description':'Read a file','read_only':True}]

    class Backend:
        def cache_key(self): return 'overview'
        def catalog(self,discover=True): return rows

    api=g.Gateway(Mobile(tmp_path/'overview.sqlite'),lambda name,fn,**kwargs:fn(tmp_path),Backend())
    # Write tools are listed too (the browser's click and navigate), each source under its own name.
    assert api.compact_catalogue('overview-'+str(time.time_ns())).splitlines()==[
        'Hermes: read file', 'acme browser: page (2): click, navigate', 'acme suite: notes search']


def test_a_call_search_never_waits_out_a_slow_first_scan(tmp_path):
    profile='slow-'+str(time.time_ns());started=threading.Event();release=threading.Event()
    ready={'name':'read_file','_toolset':'file','_kind':'hermes','description':'Read a local file','read_only':True,
           'tool_id':'f'*64,'parameters':{'type':'object','properties':{}}}
    late={'name':'mcp__acme_browser__page_navigate','_raw':'page-navigate','_server':'acme_browser',
          'description':'Open a web page','read_only':False,'tool_id':'b'*64,'parameters':{'type':'object','properties':{}}}

    class Backend:
        def cache_key(self): return 'slow-scan'
        def configured_servers(self): return ['acme_browser']
        def catalog(self,discover=True):
            if discover: started.set();release.wait(10)
            return [ready,late] if release.is_set() else [ready]

    api=g.Gateway(Mobile(tmp_path/'slow.sqlite'),lambda name,fn,**kwargs:fn(tmp_path),Backend())
    worker=threading.Thread(target=api.prefetch,args=(profile,));worker.start()
    try:
        assert started.wait(1)
        begin=time.perf_counter()
        answer=api.discover(profile,'open a web page')
        assert time.perf_counter()-begin<g.FIRST_SCAN_WAIT+1, 'answers well inside the app deadline'
        assert answer['tools']==[]
        assert answer['still_starting']==['acme browser'], 'the server still starting is named, not hidden'
        assert answer['sources']=='Hermes: read file', 'and the map of what is ready comes with it'
    finally:
        release.set();worker.join(2)
    found=api.discover(profile,'open a web page')
    assert found['tools'][0]['name']==late['name'] and 'still_starting' not in found
    assert 'acme browser: page navigate' in found['sources']


def test_full_access_runs_a_change_at_once_approved_by_the_owner(gateway):
    """Owner, 4 Nov 2026: with Full access no approval card shows, not even for a moment."""
    api,backend,_=gateway;backend.read_only=False
    receipt=api.invoke(SCOPE,{'request_id':'r1','tool_id':'a'*64,'arguments':{'query':'weather'},'approval':'owner'})
    assert receipt['status'] in ('running','completed') and 'proposal' not in receipt and receipt['approved_by']=='owner'
    done=settled(api)
    assert done['status']=='completed' and len(backend.calls)==1 and backend.calls[0][2] is True


def test_auto_asks_only_for_changes_and_manual_asks_for_reads_too(gateway):
    api,backend,_=gateway
    read=api.invoke(SCOPE,{'request_id':'auto-read','tool_id':'a'*64,'arguments':{'query':'weather'},'approval':'changes'})
    assert read['status']!='needs_approval'
    assert settled(api,'auto-read')['status']=='completed'
    manual=api.invoke(SCOPE,{'request_id':'manual-read','tool_id':'a'*64,'arguments':{'query':'weather'},'approval':'ask'})
    assert manual['status']=='needs_approval' and len(backend.calls)==1
    api.decide(SCOPE,'manual-read',{'proposal_id':manual['proposal']['id'],'decision':'approve'})
    assert settled(api,'manual-read')['status']=='completed' and len(backend.calls)==2
    backend.read_only=False
    change=api.invoke(SCOPE,{'request_id':'auto-change','tool_id':'a'*64,'arguments':{'query':'weather'},'approval':'changes'})
    assert change['status']=='needs_approval'
    # An older app sends no choice: changes still ask, as before.
    assert invoke(api,'no-choice')['status']=='needs_approval'


def test_an_unknown_approval_choice_is_refused(gateway):
    api,backend,_=gateway;backend.read_only=False
    for bad in ('never','yes',True):
        with pytest.raises(HTTPException) as error:
            api.invoke(SCOPE,{'request_id':'r9','tool_id':'a'*64,'arguments':{'query':'weather'},'approval':bad})
        assert error.value.status_code==400
    assert backend.calls==[]


@pytest.mark.parametrize('blocked_part',['pending','key','catalog'])
def test_discover_uses_last_profile_list_when_hermes_metadata_stalls(tmp_path,monkeypatch,caplog,blocked_part):
    profile='stalled-'+blocked_part+'-'+str(time.time_ns())
    backend=Backend();api=g.Gateway(Mobile(tmp_path/'stalled.sqlite'),lambda p,fn:fn(tmp_path),backend)
    api.catalog(profile,discover=False)
    release=threading.Event();entered=threading.Event();lookups=[];starting=[]
    def blocked(*args,**kwargs):
        lookups.append(threading.current_thread());entered.set();release.wait(2)
        return backend_rows
    backend_rows=backend.catalog()
    if blocked_part=='pending': monkeypatch.setattr(backend,'pending_servers',blocked,raising=False)
    elif blocked_part=='key': monkeypatch.setattr(backend,'cache_key',blocked,raising=False)
    else: monkeypatch.setattr(backend,'catalog',blocked)
    monkeypatch.setattr(api,'_starting',lambda *args:starting.append(True) or [])
    monkeypatch.setattr(g,'DISCOVER_LIST_WAIT',.04,raising=False)
    try:
        begin=time.monotonic()
        answer=api.discover(profile,'search private-query-marker')
        assert time.monotonic()-begin<.4, 'a Hermes metadata lock must not hold a tool search until its deadline'
        assert entered.is_set() and answer['tools'][0]['name']=='configured_search'
        assert not starting and 'still_starting' not in answer
        again=api.discover(profile,'search')
        assert again['tools']==answer['tools'] and len(lookups)==1, 'reuse the blocked lookup instead of adding threads'
        assert lookups[0].daemon
        assert 'worker_stack=' in caplog.text and 'private-query-marker' not in caplog.text
    finally:
        release.set()
        for worker in lookups:
            if worker is not threading.current_thread(): worker.join(2)


def test_discover_lookup_preserves_context_and_never_borrows_another_profiles_rows(tmp_path,monkeypatch):
    import contextvars
    current=contextvars.ContextVar('lookup-profile')
    release=threading.Event();workers=[]
    class ScopedBackend(Backend):
        def catalog(self,discover=True):
            rows=super().catalog(discover)
            rows[0]['name']=current.get()+'_search'
            return rows
    backend=ScopedBackend();api=g.Gateway(Mobile(tmp_path/'profiles.sqlite'),lambda p,fn:fn(tmp_path),backend)
    profiles=['first-'+str(time.time_ns()),'second-'+str(time.time_ns())]
    for profile in profiles:
        token=current.set(profile)
        try: assert api.discover(profile,'search')['tools'][0]['name']==profile+'_search'
        finally: current.reset(token)
    def blocked(profile):
        workers.append(threading.current_thread());release.wait(2);return []
    monkeypatch.setattr(api,'_pending',blocked)
    monkeypatch.setattr(g,'DISCOVER_LIST_WAIT',.04,raising=False)
    token=current.set(profiles[0])
    try:
        assert api.discover(profiles[0],'search')['tools'][0]['name']==profiles[0]+'_search'
    finally:
        release.set();current.reset(token)
        for worker in workers:
            if worker is not threading.current_thread(): worker.join(2)


def test_cold_discovery_has_a_deadline_and_does_not_invent_a_tool_list(tmp_path,monkeypatch):
    profile='cold-wait-'+str(time.time_ns());release=threading.Event();entered=threading.Event();finished=threading.Event()
    api=g.Gateway(Mobile(tmp_path/'cold.sqlite'),lambda p,fn:fn(tmp_path),Backend())
    def blocked(profile): entered.set();release.wait(2);return []
    monkeypatch.setattr(api,'_pending',blocked)
    monkeypatch.setattr(g,'DISCOVER_LIST_WAIT',.04,raising=False)
    result=[];errors=[]
    def discover():
        try: result.append(api.discover(profile,'search'))
        except HTTPException as error: errors.append(error)
        finally: finished.set()
    worker=threading.Thread(target=discover);worker.start()
    try:
        assert entered.wait(1) and finished.wait(.5), 'a cold metadata lookup must release its request admission at the deadline'
        assert not result and len(errors)==1 and errors[0].status_code==504
    finally:
        release.set();worker.join(2)
        g.DISCOVER_LOOKUPS[profile][0].join(2)
    assert api.discover(profile,'search')['tools'][0]['name']=='configured_search'
    def failed(*args,**kwargs): raise HTTPException(409,'Synthetic lookup failure')
    monkeypatch.setattr(api,'catalog',failed)
    with pytest.raises(HTTPException) as error: api.discover(profile,'search')
    assert error.value.status_code==409


def test_worker_stack_has_only_bounded_frame_locations_without_private_values():
    release=threading.Event();entered=threading.Event()
    namespace={'release':release,'entered':entered}
    exec(compile('def hold():\n private_value="frame-secret-marker"\n entered.set()\n release.wait(2)\n',
                 '/private-directory-marker/lookup_fixture.py','exec'),namespace)
    worker=threading.Thread(target=namespace['hold'],daemon=True);worker.start()
    try:
        assert entered.wait(1)
        stack=g.worker_stack(worker)
        assert 'lookup_fixture.py:4 hold' in stack and len(stack.split(' <- '))<=12
        assert 'frame-secret-marker' not in stack and 'private-directory-marker' not in stack
    finally: release.set();worker.join(2)


def test_router_timeout_logs_worker_locations_and_holds_admission_until_worker_finishes(tmp_path,monkeypatch,caplog):
    import asyncio
    release=threading.Event()
    class StalledGateway:
        def __init__(self,*args): pass
        def discover(self,*args):
            private_value='request-secret-marker'
            release.wait(2)
            return {'tools':[]}
    monkeypatch.setitem(sys.modules,'beacon_gateway_tools',SimpleNamespace(Gateway=StalledGateway,worker_stack=getattr(g,'worker_stack',lambda thread:'missing')))
    spec=importlib.util.spec_from_file_location('beacon_gateway_timeout_test',ROOT/'gateway_api.py')
    api=importlib.util.module_from_spec(spec);spec.loader.exec_module(api)
    original=asyncio.wait_for
    async def short_wait(task,timeout): return await original(task,.04)
    monkeypatch.setattr(api.asyncio,'wait_for',short_wait)
    async def call(*args,**kwargs): return 'owner','password',{'profile':'profile','conversation':'synthetic'},{}
    router=api.build_router(call,lambda *args:{},lambda p,fn:fn(tmp_path),Mobile(tmp_path/'timeout.sqlite'))
    endpoint=next(route.endpoint for route in router.routes if route.path.endswith('/tools'))
    async def exercise():
        try:
            errors=await asyncio.gather(*(endpoint('synthetic',None,'request-secret-marker') for _ in range(4)),return_exceptions=True)
            assert all(isinstance(error,HTTPException) and error.status_code==504 for error in errors)
            with pytest.raises(HTTPException) as error: await endpoint('synthetic',None,'')
            assert error.value.status_code==429, 'a timeout does not release a still-running worker slot'
            assert 'discover' in caplog.text and 'worker_stack=' in caplog.text
            assert 'request-secret-marker' not in caplog.text
        finally: release.set()
        await asyncio.sleep(.08)
        assert await endpoint('synthetic',None,'')=={'tools':[]}
    asyncio.run(exercise())


@pytest.fixture
def current_future_native(tmp_path, monkeypatch):
    """Real Hermes registration, refresh, dispatch and approvals; only the MCP session is synthetic."""
    import asyncio
    import contextvars
    import copy
    import yaml
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    monkeypatch.setenv('HERMES_DISABLE_LAZY_INSTALLS', '1')
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    from hermes_constants import get_hermes_home, set_hermes_home_override, reset_hermes_home_override
    token = set_hermes_home_override(str(tmp_path))
    from tools import mcp_tool as core, mcp_tool_registration as registration, mcp_tool_discovery as discovery
    from tools.registry import registry
    import model_tools  # public source, warmed before measuring the call catalogue
    assert get_hermes_home() == tmp_path
    monkeypatch.setattr(registry, '_scoped_tools', {})
    for name in ('CATALOG_CACHE', 'CATALOG_FIRST_WARM', 'LAST_CATALOG', 'DISCOVER_LOOKUPS',
                 'MCP_CACHE_FILES', 'MCP_REGISTERED'):
        monkeypatch.setattr(g, name, {})
    for name in ('CATALOG_SETTLED', 'CATALOG_WARMING', 'MCP_REFRESHED', 'MCP_SCANNING'):
        monkeypatch.setattr(g, name, set())
    # Any accidental connection attempt fails before a process or network transport exists.
    async def no_connect(*args, **kwargs):
        raise AssertionError('Synthetic session must never open a transport')
    monkeypatch.setattr(discovery, '_connect_server', no_connect)
    loop = asyncio.new_event_loop()
    worker = threading.Thread(target=contextvars.copy_context().run, args=(loop.run_forever,), daemon=True)
    monkeypatch.setattr(core, '_mcp_loop', loop)
    worker.start()
    server_name = 'synthetic_catalogue'
    cfg = {'command': 'synthetic-in-process-only', 'trust': 'untrusted'}
    config = {'platform_toolsets': {'api_server': ['file', 'mcp-' + server_name]}, 'mcp_servers': {server_name: cfg}}
    def write_config():
        (tmp_path / 'config.yaml').write_text(yaml.safe_dump(config), encoding='utf-8')
    write_config()
    def tool(name, fields, read_only=True):
        return SimpleNamespace(name=name, description=name.replace('_', ' '),
            inputSchema={'type': 'object', 'properties': {field: {'type': kind} for field, kind in fields.items()},
                         'required': list(fields), 'additionalProperties': False},
            annotations=SimpleNamespace(readOnlyHint=read_only))
    class Session:
        def __init__(self):
            self.tools = [tool('search_products', {'query': 'string'}), tool('purchase', {'sku': 'string'}, False),
                          tool('contact_seller', {'message': 'string'}, False)]
            self.calls = []
            self.results = {'search_products': {'products': [{'sku': 'fixture-42', 'price': 24, 'currency': 'AED'}]},
                            'add_cart_item': {'cart': [{'sku': 'fixture-42', 'quantity': 2}], 'purchased': False},
                            'get_browser_product': {'sku': 'fixture-42', 'available': True}}
        async def list_tools(self):
            return SimpleNamespace(tools=self.tools, nextCursor=None)
        async def call_tool(self, name, arguments, **kwargs):
            from gateway.session_context import get_session_env
            self.calls.append((name, arguments.copy(), get_session_env('HERMES_SESSION_ID'),
                               get_session_env('HERMES_SESSION_PROFILE'), get_session_env('HERMES_SESSION_USER_ID')))
            assert name in self.results, 'Purchase and external contact are never authorized by these tests'
            return SimpleNamespace(content=[], structuredContent=copy.deepcopy(self.results[name]), isError=False)
    session = Session()
    server = core.MCPServerTask(server_name)
    server._config = cfg
    server.session = session
    server._ever_connected = True
    server._tools = session.tools
    from tools.mcp_tool_scope import _server_key
    key = _server_key(server_name)
    monkeypatch.setitem(core._servers, key, server)
    server._registered_tool_names = registration._register_server_tools(server_name, server, cfg)
    profile_homes = {'profile': tmp_path}
    def scoped(profile, fn):
        bound = set_hermes_home_override(str(profile_homes[profile]))
        try: return fn(profile_homes[profile])
        finally: reset_hermes_home_override(bound)
    mobile=Mobile(tmp_path/'synthetic-receipts.sqlite');mobile.conversation='native-conversation'
    api = g.Gateway(mobile, scoped)
    def refresh():
        asyncio.run_coroutine_threadsafe(server._refresh_tools(), loop).result(timeout=5)
    try:
        yield SimpleNamespace(api=api, registry=registry, home=tmp_path, config=config, write_config=write_config,
                              server=server, session=session, tool=tool, refresh=refresh, profile_homes=profile_homes)
    finally:
        for thread in list(g.DISCOVER_LOOKUPS.values()): thread[0].join(5)
        loop.call_soon_threadsafe(loop.stop)
        worker.join(5)
        assert not worker.is_alive()
        loop.close()
        reset_hermes_home_override(token)


def test_current_future_native_registry_changes_invalidate_a_settled_cache(current_future_native):
    env = current_future_native
    api, registry = env.api, env.registry
    api.prefetch('profile')
    key = api._catalog_key('profile')
    assert key in g.CATALOG_SETTLED
    original = next(row for row in api.catalog('profile', discover=False) if row['name'] == 'read_file')
    # A generation-only bump is not a changed catalogue and keeps the settled cache.
    registry._generation += 1
    assert api._catalog_key('profile') == key
    name = 'search_future_inventory'
    schema = {'name': name, 'description': 'Search future inventory', 'parameters':
              {'type': 'object', 'properties': {'query': {'type': 'string'}}, 'required': ['query']}}
    registry.register(name, 'file', schema, lambda args, **kwargs: json.dumps({'items': ['fixture-42']}), scope=str(env.home))
    found = api.discover('profile', name)['tools']
    assert any(row['name'] == name for row in found), 'a real new registry tool must appear during the settled call'
    added = next(row for row in found if row['name'] == name)
    assert api._catalog_key('profile') != key
    assert next(row for row in api.catalog('profile', discover=False) if row['name'] == 'read_file')['tool_id'] == original['tool_id']
    schema = {**schema, 'parameters': {'type': 'object', 'properties': {'category': {'type': 'string'}}, 'required': ['category']}}
    registry.register(name, 'file', schema, lambda args, **kwargs: '{}', scope=str(env.home))
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE, {'request_id': 'old-schema', 'tool_id': added['tool_id'], 'arguments': {'query': 'desk'}})
    assert error.value.status_code == 409
    current = next(row for row in api.discover('profile', name)['tools'] if row['name'] == name)
    assert current['tool_id'] != added['tool_id'] and 'category' in current['parameters']['required']
    registry.deregister(name, scope=str(env.home))
    assert all(row['name'] != name for row in api.discover('profile', name)['tools'])
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE, {'request_id': 'removed', 'tool_id': current['tool_id'], 'arguments': {'category': 'desk'}})
    assert error.value.status_code == 409 and not env.session.calls


def test_current_future_mcp_shopping_refresh_receipts_and_write_approval(current_future_native):
    env = current_future_native
    api = env.api
    initial = api.discover('profile', 'search_products')['tools'][0]
    assert api._catalog_key('profile') in g.CATALOG_SETTLED
    def invoke_tool(row, request, arguments):
        return api.invoke(SCOPE, {'request_id': request, 'tool_id': row['tool_id'], 'arguments': arguments}, session_id='native-conversation')
    invoke_tool(initial, 'products', {'query': 'desk lamp'})
    result = settled(api, 'products')
    assert result['status'] == 'completed' and result['result'] == {'result': env.session.results['search_products']}
    # Hermes' real list-changed refresh rewrites both registry and schema file while this same call remains warm.
    env.session.tools += [env.tool('get_browser_product', {'sku': 'string'}),
                          env.tool('add_cart_item', {'sku': 'string', 'quantity': 'integer'}, False)]
    env.refresh()
    browser = api.discover('profile', 'get_browser_product')['tools'][0]
    cart = api.discover('profile', 'add_cart_item')['tools'][0]
    assert browser['name'].endswith('get_browser_product') and cart['read_only'] is False
    assert api.discover('profile', 'search_products')['tools'][0]['tool_id'] == initial['tool_id']
    invoke_tool(browser, 'inspect', {'sku': 'fixture-42'})
    assert settled(api, 'inspect')['result'] == {'result': env.session.results['get_browser_product']}
    proposal = invoke_tool(cart, 'cart', {'sku': 'fixture-42', 'quantity': '2'})
    assert proposal['status'] == 'needs_approval' and len(env.session.calls) == 2
    assert proposal['proposal']['arguments'] == {'sku': 'fixture-42', 'quantity': 2}
    api.decide(SCOPE, 'cart', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
    cart_result = settled(api, 'cart')
    assert cart_result['status'] == 'completed' and cart_result['result'] == {'result': env.session.results['add_cart_item']}
    assert invoke_tool(cart, 'cart', {'sku': 'fixture-42', 'quantity': '2'}) == cart_result
    for raw in ('purchase', 'contact_seller'):
        row = api.discover('profile', raw)['tools'][0]
        arguments = {'sku': 'fixture-42'} if raw == 'purchase' else {'message': 'synthetic inquiry'}
        refused = invoke_tool(row, raw, arguments)
        assert refused['status'] == 'needs_approval'
        assert api.decide(SCOPE, raw, {'proposal_id': refused['proposal']['id'], 'decision': 'deny'})['status'] == 'denied'
    assert [call[0] for call in env.session.calls] == ['search_products', 'get_browser_product', 'add_cart_item']
    assert all(call[2:] == ('native-conversation', 'profile', 'password:owner') for call in env.session.calls)


@pytest.mark.parametrize('change', ['removed', 'disabled', 'filtered', 'schema'])
def test_current_future_warm_mcp_changes_reject_stale_handles_and_proposals(current_future_native, change):
    env = current_future_native
    env.session.tools.append(env.tool('add_cart_item', {'sku': 'string'}, False))
    env.refresh()
    api = env.api
    tool = api.discover('profile', 'add_cart_item')['tools'][0]
    key = api._catalog_key('profile')
    assert key in g.CATALOG_SETTLED
    proposal = api.invoke(SCOPE, {'request_id': 'pending-cart', 'tool_id': tool['tool_id'], 'arguments': {'sku': 'fixture-42'}})
    assert proposal['status'] == 'needs_approval'
    if change == 'removed':
        env.session.tools = [row for row in env.session.tools if row.name != 'add_cart_item']
        env.refresh()
    elif change in ('disabled', 'filtered'):
        config = env.config['mcp_servers'][env.server.name]
        config.update({'enabled': False} if change == 'disabled' else {'tools': {'exclude': ['add_cart_item']}})
        env.write_config()
    else:
        env.session.tools = [env.tool('add_cart_item', {'sku': 'string', 'quantity': 'integer'}, False)
                             if row.name == 'add_cart_item' else row for row in env.session.tools]
        env.refresh()
    assert api._catalog_key('profile') != key
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE, {'request_id': 'stale-cart', 'tool_id': tool['tool_id'], 'arguments': {'sku': 'fixture-42'}})
    assert error.value.status_code == 409
    assert api.decide(SCOPE, 'pending-cart', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})['status'] == 'failed'
    assert not env.session.calls
    if change == 'schema':
        current = api.discover('profile', 'add_cart_item')['tools'][0]
        assert current['tool_id'] != tool['tool_id'] and current['parameters']['required'] == ['sku', 'quantity']
        with pytest.raises(HTTPException) as error:
            api.invoke(SCOPE, {'request_id': 'missing-field', 'tool_id': current['tool_id'], 'arguments': {'sku': 'fixture-42'}})
        assert error.value.status_code == 400


def test_current_future_native_browser_and_agent_state_stays_out_of_direct_dispatch(current_future_native):
    env = current_future_native
    for name, toolset in [('browser_fixture', 'browser'), ('phone_fixture', 'beacon-phone'), ('todo_list', 'todo')]:
        env.registry.register(name, toolset, {'name': name, 'description': 'Canonical agent state',
                              'parameters': {'type': 'object'}}, lambda args, **kwargs: pytest.fail('needs native agent'),
                              scope=str(env.home))
    env.config['platform_toolsets']['api_server'] += ['browser', 'beacon-phone', 'todo']
    env.write_config()
    names = {row['name'] for row in env.api.catalog('profile', discover=False)}
    assert 'read_file' in names
    assert not names & {'browser_fixture', 'phone_fixture', 'todo_list'}


def test_current_future_catalogues_and_admission_keep_profile_and_owner_authority(current_future_native, monkeypatch):
    import yaml
    env = current_future_native
    name = 'search_private_inventory'
    env.registry.register(name, 'file', {'name': name, 'description': 'Private inventory',
                          'parameters': {'type': 'object', 'properties': {}}},
                          lambda args, **kwargs: pytest.fail('Revoked owner must not dispatch'), scope=str(env.home))
    own = next(row for row in env.api.discover('profile', name)['tools'] if row['name'] == name)
    other = env.home / 'other-profile'
    other.mkdir()
    (other / 'config.yaml').write_text(yaml.safe_dump({'platform_toolsets': {'api_server': ['file']}}), encoding='utf-8')
    env.profile_homes['other'] = other
    rows = env.api.catalog('other', discover=False)
    assert all(row['name'] != name for row in rows), 'real native registry overlays belong to their profile home'
    foreign_handle = next(row['tool_id'] for row in rows if row['name'] == 'read_file')
    with pytest.raises(HTTPException) as error:
        env.api.invoke(SCOPE, {'request_id': 'foreign-handle', 'tool_id': foreign_handle, 'arguments': {}})
    assert error.value.status_code == 409
    proposal = env.api.invoke(SCOPE, {'request_id': 'owner-revoked', 'tool_id': own['tool_id'], 'arguments': {}})
    assert proposal['status'] == 'needs_approval'
    monkeypatch.setattr(env.api.mobile, 'settings', lambda: {'owner_id': 'replacement', 'owner_provider': 'password'})
    env.api.decide(SCOPE, 'owner-revoked', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
    result = settled(env.api, 'owner-revoked')
    assert result['status'] == 'failed' and result['error_code'] == 'admission_rejected' and result['http_status'] == 403
    assert not env.session.calls


@pytest.mark.parametrize('prompt_kind',['exact','header_only','changed_arguments','empty_exact'])
def test_native_mcp_consent_binds_the_exact_argument_renderer(tmp_path,monkeypatch,prompt_kind):
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    from tools import approval
    from tools.mcp_tool_handlers import _format_trust_gate_arguments
    pending={};choices=[]
    monkeypatch.setattr(approval,'register_gateway_notify',lambda key,callback:pending.update(callback=callback))
    monkeypatch.setattr(approval,'unregister_gateway_notify',lambda key:pending.clear())
    monkeypatch.setattr(approval,'resolve_gateway_approval',lambda key,choice,**kwargs:choices.append((choice,kwargs['request_id'])))
    head=("MCP tool 'write' on UNTRUSTED server 'isolated' wants to run. "
          'This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.')
    arguments={} if prompt_kind=='empty_exact' else {'message':'fixture'}
    exact=head if not arguments else head+'\n\nArguments:\n'+_format_trust_gate_arguments(arguments)
    prompts={'exact':exact,
             'header_only':head,
             'changed_arguments':head+'\n\nArguments:\n'+_format_trust_gate_arguments({'message':'other'}),
             'empty_exact':head}
    def handler(name,args,**kwargs):
        pending['callback']({'request_id':'b'*32,'pattern_key':'mcp_elicitation','command':prompts[prompt_kind]})
        return '{"error":"Native approval denied"}'
    monkeypatch.setitem(sys.modules,'model_tools',SimpleNamespace(handle_function_call=handler))
    tool={'name':'mcp__isolated__write','_server':'isolated','_raw':'write','_trust':'untrusted'}
    result=json.loads(g.NativeMcp().execute(tool,arguments,SCOPE,True))
    accepted=prompt_kind in ('exact','empty_exact')
    assert choices==[('once' if accepted else 'deny','b'*32)]
    assert result['error']==('Native approval denied' if accepted else 'Hermes needs this approval. Approve the action in Hermes.')
