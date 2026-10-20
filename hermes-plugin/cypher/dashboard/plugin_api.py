"""Supported Hermes dashboard API; no archive/WebUI runtime dependencies."""
import asyncio
import contextvars
from concurrent.futures import ThreadPoolExecutor
import ipaddress
import math
import threading
from contextlib import asynccontextmanager
from datetime import datetime,timedelta,timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import subprocess
import time
import uuid
from urllib.parse import urlsplit
from fastapi import APIRouter,HTTPException,Request
from fastapi.responses import JSONResponse
from fastapi.responses import StreamingResponse
# Hermes loads this file by path rather than as a package. Load private sibling modules
# under stable plugin-specific names so the native tool and dashboard share durable state.
ROOT=Path(__file__).resolve().parent.parent
_RELEASE_CACHE_LOCK=threading.RLock()
_RELEASE_CACHE_KEY=None
_RELEASE_CACHE_VALUE=None
for name in ('mobile','native','voice_context','voice_models','activity','memory_api','skills_api','admin_api','files_api','gateway_api','bot_mode_api','callbacks','callback_api','call_history_api','proactive'):
    key='cypher_'+name
    if key not in sys.modules:
        spec=importlib.util.spec_from_file_location(key,ROOT/(name+'.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)
mobile=sys.modules['cypher_mobile'];native=sys.modules['cypher_native'];voice_context=sys.modules['cypher_voice_context']
activity=sys.modules['cypher_activity']
voice_models=sys.modules['cypher_voice_models']
proactive=sys.modules['cypher_proactive']
@asynccontextmanager
async def lifespan(app):
    await start_watcher()
    try: yield
    finally: await stop_watcher()
router=APIRouter(lifespan=lifespan)
def enforce_request_policy(request):
    site=request.headers.get('sec-fetch-site')
    if site is not None and site.strip().casefold() not in ('same-origin','none'):
        raise HTTPException(403,'Cross-site requests are not allowed')
    method=request.method.upper()
    if method=='GET': return
    content_type=request.headers.get('content-type')
    has_body=(request.headers.get('content-length') not in (None,'0')
              or request.headers.get('transfer-encoding') is not None)
    if not has_body: return
    if not content_type or content_type.split(';',1)[0].strip().casefold()!='application/json':
        raise HTTPException(415,'JSON content type required')
def identity(request,profile=None,owner_only=False,allow_loopback=False):
    enforce_request_policy(request)
    auth_required=bool(getattr(request.app.state,'auth_required',False))
    session=getattr(request.state,'session',None)
    cfg=mobile.settings()
    if not auth_required and allow_loopback:
        from hermes_cli.web_server import _has_valid_session_token
        if not _has_valid_session_token(request): raise HTTPException(401,'Verified dashboard session required')
        owner,provider='local','loopback'
    else:
        if not auth_required or session is None: raise HTTPException(401,'Verified native session required')
        owner=str(getattr(session,'user_id','') or '')
        provider=str(getattr(session,'provider','') or '')
    if not owner or not provider or not cfg.get('owner_id') or owner!=cfg['owner_id'] or provider!=cfg.get('owner_provider'):
        raise HTTPException(403,'Owner not authorized')
    profiles=cfg.get('profiles',[])
    if not isinstance(profiles,list) or not 1<=len(profiles)<=64 or any(not isinstance(p,str) or not p.strip() or len(p)>80 for p in profiles) or len(set(profiles))!=len(profiles):
        raise HTTPException(403,'Profile configuration invalid')
    if owner_only: return owner,provider,None,cfg
    if profile is not None and not isinstance(profile,str): raise HTTPException(403,'Profile not authorized')
    profile=profile or request.query_params.get('cypher_profile') or request.query_params.get('profile') or (profiles[0] if len(profiles)==1 else None)
    if not isinstance(profiles,list) or not profile or profile not in profiles:
        raise HTTPException(403,'Profile not authorized')
    return owner,provider,profile,cfg

def connect_info_url():
    try:
        from hermes_cli.dashboard_auth.prefix import resolve_public_url
        url=resolve_public_url()
        parsed=urlsplit(url)
        host=(parsed.hostname or '').rstrip('.').lower()
        port=parsed.port
        address=None
        try: address=ipaddress.ip_address(host)
        except ValueError: pass
        numeric_host=bool(re.fullmatch(r'(?:0x[0-9a-f]+|[0-9]+)(?:\.(?:0x[0-9a-f]+|[0-9]+))*',host))
        loopback=(host=='localhost' or host.endswith('.localhost') or
                  (numeric_host and address is None) or
                  (address is not None and (address.is_loopback or address.is_unspecified or
                   bool(getattr(address,'ipv4_mapped',None) and address.ipv4_mapped.is_loopback))))
        if (not isinstance(url,str) or parsed.scheme.lower()!='https' or not parsed.netloc or not host
                or parsed.username is not None or parsed.password is not None or parsed.query or parsed.fragment
                or port is not None and not 1<=port<=65535 or loopback):
            raise ValueError()
        return url,host[:80]
    except Exception:
        raise HTTPException(409,'A configured public HTTPS dashboard URL is required to connect a phone.') from None

def _contains_provider_key(value):
    if isinstance(value,dict):
        for name,item in value.items():
            normalized=str(name).casefold().replace('-','_')
            if normalized!='client_key' and any(part in normalized for part in ('key','token','secret','credential','authorization')):
                return True
            if _contains_provider_key(item): return True
    elif isinstance(value,list):
        return any(_contains_provider_key(item) for item in value)
    return False

def _client_key_mode(value):
    if _contains_provider_key(value):
        raise HTTPException(400,'Provider keys must remain on the phone')
    client_key=value.get('client_key',False)
    if type(client_key) is not bool:
        raise HTTPException(400,'Invalid client_key flag')
    if client_key and 'voice_provider' not in value:
        raise HTTPException(400,'Choose a voice provider for a phone-key call')
    return client_key

def _bounded_event_wait(value):
    try: value=float(value)
    except (TypeError,ValueError): return 0.0
    if not math.isfinite(value): return 0.0
    return min(25.0,max(0.0,value))

@router.get('/connect-info')
async def connect_info(request:Request):
    loopback=not bool(getattr(request.app.state,'auth_required',False))
    identity(request,owner_only=True,allow_loopback=loopback)
    url,name=connect_info_url()
    result={'url':url,'name':name}
    if loopback:
        from hermes_cli.web_server import _SESSION_TOKEN
        result['session']=_SESSION_TOKEN
    return JSONResponse(result,headers={'Cache-Control':'no-store'})

async def body(request,limit=65536):
    raw=bytearray()
    async for part in request.stream():
        raw.extend(part)
        if len(raw)>limit: raise HTTPException(413,'Request too large')
    try: value=json.loads(raw or b'{}')
    except Exception: raise HTTPException(400,'Invalid JSON') from None
    if not isinstance(value,dict): raise HTTPException(400,'Expected object')
    return value

def skill_scope(profile,fn):
    # Skills use import-bound directories; use the native router's shared profile lock.
    from hermes_cli.web_server_profiles import _profile_scope
    from hermes_constants import get_hermes_home
    with _profile_scope(profile):
        return fn(get_hermes_home())

router.include_router(sys.modules['cypher_memory_api'].build_router(identity,body,voice_context.scoped))
router.include_router(sys.modules['cypher_skills_api'].build_router(identity,body,skill_scope),prefix='/mobile')
router.include_router(sys.modules['cypher_admin_api'].build_router(identity,body))
router.include_router(sys.modules['cypher_files_api'].build_router(identity,body,native))
router.include_router(sys.modules['cypher_bot_mode_api'].build_router(identity,body,native))
def safely(fn,*args):
    try: return fn(*args)
    except KeyError: raise HTTPException(503,'Configured resource unavailable') from None
    except LookupError: raise HTTPException(404,'Owned resource unavailable') from None
    except ValueError as exc: raise HTTPException(409,str(exc)) from None
    except (OSError,TypeError): raise HTTPException(503,'Configured resource unavailable') from None
    except RuntimeError: raise HTTPException(503,'Native service unavailable') from None
_NOT_OWNED_CONVERSATION='This chat is not linked to your Hermes sign-in, so Cypher cannot start a call from it. Start a new chat in Cypher.'
def reject_unowned_conversation(owner,provider,profile,stored):
    try:
        from tui_gateway import server
        with server._profile_db({'profile':profile}) as db: row=db.get_session(stored) if db else None
    except Exception:
        return
    if row is not None and row.get('user_id')!=provider+':'+owner:
        raise HTTPException(404,_NOT_OWNED_CONVERSATION) from None
async def call(request,call_id,writable=False):
    def authorize():
        # First authorize owner, then select the call's recorded profile rather than trust input.
        owner,provider,profile,cfg=identity(request,owner_only=True)
        with mobile.connect() as conn:
            row=safely(mobile.owned,conn,call_id,owner,None,writable)
            if not row['closed']:
                conn.execute('UPDATE calls SET seen=? WHERE id=? AND owner=? AND profile=? AND closed=0',(time.time(),call_id,owner,row['profile']))
        selected=request.query_params.get('cypher_profile') or request.query_params.get('profile')
        if selected and selected!=row['profile']: raise HTTPException(403,'Call profile mismatch')
        identity(request,row['profile'])
        reject_unowned_conversation(owner,provider,row['profile'],row['conversation'])
        return owner,provider,row,cfg
    return await asyncio.to_thread(authorize)
router.include_router(sys.modules['cypher_gateway_api'].build_router(call,body,voice_context.scoped,mobile))

def secret(cfg):
    from agent.secret_scope import get_secret
    key=cfg.get('google_api_key_env','GEMINI_API_KEY')
    configured=cfg.get('google_credentials_file')
    if configured:
        from dotenv import dotenv_values
        path=Path(configured).resolve(strict=True)
        if not path.is_file() or path.stat().st_size>16384: raise RuntimeError('Invalid private credential file')
        return dotenv_values(path).get(key)
    return get_secret(key)
@router.get('/mobile/bootstrap')
async def bootstrap(request:Request):
    owner,provider,profile,cfg=identity(request)
    selection=voice_models.selected(mobile,owner,profile,cfg)
    choice=next(item for item in voice_models.choices(cfg,secret) if item['id']==selection['provider'])
    try: release_info=await asyncio.to_thread(safely,release,cfg,True)
    except HTTPException: release_info={'available':False,'reason':'release_unavailable'}
    return {'contract_version':2,'backend':'hermes-dashboard','owner_id':owner,'profile':profile,
            'voice':{**selection,'available':choice['available'],'configured':choice['configured'],'availability_scope':'local_configuration'},
            'capabilities':{'native_jobs':True,'pc_recording':False,'callbacks':bool(cfg.get('callback_tool_enabled',False)),'push':bool(cfg.get('firebase_credentials')),'permanent_recording_retention':False},
            'release':release_info}
@router.get('/mobile/voice-settings')
async def voice_settings(request:Request):
    owner,_,profile,cfg=identity(request)
    return {'selected_provider':voice_models.selected(mobile,owner,profile,cfg)['provider'],
            'providers':voice_models.choices(cfg,secret,mobile,owner,profile),'applies_to':'next_call'}
@router.get('/mobile/history-retention')
async def history_retention(request:Request):
    owner,_,_,_=identity(request)
    days=await asyncio.to_thread(mobile.history_retention_days,owner)
    return JSONResponse({'history_retention_days':days},headers={'Cache-Control':'no-store'})
@router.put('/mobile/history-retention')
async def save_history_retention(request:Request):
    owner,_,_,_=identity(request)
    value=await body(request,1024)
    days=value.get('history_retention_days')
    if set(value)!={'history_retention_days'} or type(days) is not int or days not in mobile.HISTORY_RETENTION_DAYS:
        raise HTTPException(400,'Choose Forever, 1 year, 90 days, or 30 days')
    days=await asyncio.to_thread(mobile.set_history_retention_days,owner,days)
    return JSONResponse({'history_retention_days':days},headers={'Cache-Control':'no-store'})
@router.put('/mobile/voice-settings')
async def save_voice_settings(request:Request):
    owner,_,profile,cfg=identity(request);value=await body(request,1024)
    if 'provider' not in value or set(value)-{'provider','voice','personality'} or value['provider'] not in voice_models.PROVIDERS:
        raise HTTPException(400,'Choose an available voice model')
    choice=next(item for item in voice_models.choices(cfg,secret) if item['id']==value['provider'])
    if not choice['available']: raise HTTPException(409,'This voice model needs server setup')
    options=safely(voice_models.validate_options,value['provider'],value)
    current=voice_models.provider_selection(mobile,owner,profile,cfg,value['provider'])
    options={k:options.get(k,current[k]) for k in ('voice','personality')}
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(voice_models.preference_key(owner,profile),value['provider']))
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(voice_models.options_key(owner,profile,value['provider']),json.dumps(options)))
    return await voice_settings(request)
@router.post('/mobile/calls')
async def calls(request:Request):
    value=await body(request);owner,provider,profile,cfg=identity(request,value.get('profile'))
    client_key=_client_key_mode(value)
    selection=voice_models.selected(mobile,owner,profile,cfg)
    conversation=value.get('conversation_id')
    if 'voice_provider' in value:
        requested=value['voice_provider']
        if requested not in voice_models.PROVIDERS: raise HTTPException(400,'Unknown voice model')
        if client_key:
            selection=voice_models.provider_selection(mobile,owner,profile,cfg,requested)
        else:
            if requested=='openai': raise HTTPException(409,'OpenAI Realtime requires a phone-held key')
            if not next(item for item in voice_models.choices(cfg,secret) if item['id']==requested)['available']:
                raise HTTPException(409,'Requested voice model needs server setup')
            selection=voice_models.provider_selection(mobile,owner,profile,cfg,requested)
    elif client_key:
        raise HTTPException(400,'Choose a voice provider for a phone-key call')
    elif selection['provider']=='openai':
        raise HTTPException(409,'OpenAI Realtime requires a phone-held key')
    if conversation:
        await asyncio.to_thread(reject_unowned_conversation,owner,provider,profile,conversation)
        await asyncio.to_thread(safely,native.reserve_conversation,owner,provider,profile,conversation)
    else:
        with mobile.connect() as conn:
            if mobile.active_calls(conn,owner)>=mobile.MAX_ACTIVE_CALLS:
                raise HTTPException(409,'Active call limit reached')
        conversation=await asyncio.to_thread(safely,native.create,owner,provider,profile)
    return safely(mobile.reserve,owner,profile,conversation,selection,client_key)
@router.get('/mobile/calls/{call_id}')
async def get_call(call_id:str,request:Request):
    owner,provider,row,cfg=await call(request,call_id)
    await asyncio.to_thread(watch,owner,provider,row['profile'])
    return call_status(row,owner)
@router.delete('/mobile/calls/{call_id}')
async def close_call(call_id:str,request:Request):
    owner,provider,row,cfg=await call(request,call_id)
    ended=time.time()
    with mobile.LOCK,mobile.connect() as conn: conn.execute('UPDATE calls SET closed=1,ended=COALESCE(ended,?) WHERE id=? AND owner=? AND profile=?',(ended,call_id,owner,row['profile']))
    await asyncio.to_thread(watch,owner,provider,row['profile'])
    row['closed']=1
    clear_lookup_counts(call_id)
    return mobile.status(row)
@router.post('/mobile/calls/{call_id}/token')
async def token(call_id:str,request:Request):
    await body(request,1024);owner,provider,row,cfg=await call(request,call_id,True)
    selection=safely(voice_models.bound,row,cfg)
    if selection['provider']=='openai' or json.loads(row.get('voice_config') or '{}').get('client_key') is True:
        raise HTTPException(409,'This voice call requires a phone-held key')
    if selection['provider']=='grok':
        try: return JSONResponse(await asyncio.to_thread(voice_models.mint_xai,cfg,selection),headers={'Cache-Control':'no-store'})
        except Exception: raise HTTPException(503,'Grok voice could not connect. Check the server key and account credit.') from None
    def mint():
        from google import genai
        key=secret(cfg)
        if not key: raise RuntimeError('Google credential unavailable')
        now=datetime.now(timezone.utc); expiry=now+timedelta(minutes=30);admission=now+timedelta(minutes=1)
        model=selection['model'];voice=selection['voice']
        client=genai.Client(api_key=key,http_options={'api_version':'v1alpha','timeout':15000})
        try:
            minted=client.auth_tokens.create(config={'uses':1,'expire_time':expiry,'new_session_expire_time':admission,
                'lock_additional_fields':[],'live_connect_constraints':{'model':model,'config':{'response_modalities':['AUDIO'],'speech_config':{'voice_config':{'prebuilt_voice_config':{'voice_name':voice}}}}}})
        finally: client.close()
        return {**selection,'token':minted.name,'websocket_url':'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained','model':model,'voice':voice,'expires_at':expiry.isoformat(),'new_session_expires_at':admission.isoformat()}
    try: return JSONResponse(await asyncio.to_thread(mint),headers={'Cache-Control':'no-store'})
    except Exception: raise HTTPException(503,'Google ephemeral token unavailable') from None
@router.post('/mobile/calls/{call_id}/audio')
async def audio(call_id:str,request:Request):
    value=await body(request,32768);owner,_,_,_=await call(request,call_id,True)
    return await asyncio.to_thread(safely,mobile.chunk,call_id,owner,value)
@router.post('/mobile/calls/{call_id}/events')
async def events(call_id:str,request:Request):
    value=await body(request);owner,_,_,_=await call(request,call_id,True)
    return await asyncio.to_thread(safely,mobile.events,call_id,owner,value)
@router.post('/mobile/calls/{call_id}/finalize')
async def finalize(call_id:str,request:Request):
    value=await body(request,1024);owner,provider,row,_=await call(request,call_id)
    result=await asyncio.to_thread(safely,mobile.finalize,call_id,owner,value)
    clear_lookup_counts(call_id)
    await asyncio.to_thread(watch,owner,provider,row['profile'])
    return result
@router.post('/mobile/calls/{call_id}/jobs')
async def jobs(call_id:str,request:Request):
    value=await body(request,4096);owner,provider,row,cfg=await call(request,call_id,True)
    for name in ('stored_session_id','runtime_session_id','request_id'): safely(mobile.ident,value.get(name))
    history=await asyncio.to_thread(safely,native.verify,owner,provider,row['profile'],value['stored_session_id'],value['runtime_session_id'])
    user_row_id=value.get('user_row_id')
    if type(user_row_id) is not int or not any(m.get('role')=='user' and m.get('row_id',m.get('_row_id'))==user_row_id for m in history.get('messages',[])):
        raise HTTPException(409,'Actual prompt.submit user_row_id required')
    from tui_gateway import event_replay
    jid=uuid.uuid4().hex
    title=value.get('title','Hermes work')
    if not isinstance(title,str) or len(title)>120: raise HTTPException(400,'Invalid title')
    with mobile.LOCK,mobile.connect() as conn:
        existing=conn.execute('SELECT * FROM jobs WHERE owner=? AND profile=? AND request=?',(owner,row['profile'],value['request_id'])).fetchone()
        if existing:
            if existing['runtime']!=value['runtime_session_id'] or existing['stored']!=value['stored_session_id'] or existing['call_id']!=call_id: raise HTTPException(409,'Conflicting job registration')
            return job_status(dict(existing))
        if conn.execute("SELECT COUNT(*) FROM jobs WHERE owner=? AND profile=? AND status IN ('watching','needs_input')",(owner,row['profile'])).fetchone()[0]>=512: raise HTTPException(409,'Native watch capacity reached')
        if conn.execute('SELECT COUNT(*) FROM jobs WHERE call_id=?',(call_id,)).fetchone()[0]>=64: raise HTTPException(409,'Job linkage capacity reached')
        if conn.execute("SELECT 1 FROM jobs WHERE (runtime=? AND status IN ('watching','needs_input')) OR (owner=? AND profile=? AND stored=? AND seq=?)",(value['runtime_session_id'],owner,row['profile'],value['stored_session_id'],user_row_id)).fetchone(): raise HTTPException(409,'One watched prompt per runtime session')
        conn.execute('INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)',
            (jid,call_id,owner,row['profile'],value['stored_session_id'],value['runtime_session_id'],value['request_id'],title,int(value.get('callback') is True),event_replay.replay_epoch(),user_row_id,'watching',time.time()))
    return {'job_id':jid,'status':'working','user_row_id':user_row_id,'request_id':value['request_id'],'stored_session_id':value['stored_session_id'],'runtime_session_id':value['runtime_session_id']}
@router.post('/mobile/calls/{call_id}/callback')
async def callback(call_id:str,request:Request):
    value=await body(request,1024);owner,provider,row,_=await call(request,call_id)
    if value.get('scope','latest') not in ('latest','all'): raise HTTPException(400,'Invalid callback scope')
    with mobile.LOCK,mobile.connect() as conn:
        rows=conn.execute('SELECT * FROM jobs WHERE call_id=? AND owner=? ORDER BY rowid DESC',(call_id,owner)).fetchall()
        if value.get('job_id'): rows=[r for r in rows if r['id']==value['job_id']]
        elif value.get('scope','latest')=='latest': rows=rows[:1]
        if not rows: raise HTTPException(404,'Owned native job unavailable')
        for r in rows: conn.execute('UPDATE jobs SET callback=1,history_updated=? WHERE id=?',(time.time(),r['id']))
    await asyncio.to_thread(watch,owner,provider,row['profile'])
    return {'subscribed':True,'job_ids':[r['id'] for r in rows]}
def job_status(job):
    mapped={'watching':'working','completed':'done','callback_pending':'done','failed':'failed','cancelled':'cancelled','needs_input':'needs_input'}
    result={'job_id':job['id'],'request_id':job['request'],'stored_session_id':job['stored'],'runtime_session_id':job['runtime'],'user_row_id':job['seq'],'status':mapped.get(job['status'],'working')}
    if job.get('result'): result['result']=job['result']
    if job.get('error'): result['error']=job['error']
    return result
def call_status(row,owner):
    with mobile.connect() as conn:
        jobs=[job_status(dict(j)) for j in conn.execute('SELECT * FROM jobs WHERE call_id=? AND owner=?',(row['id'],owner))]
    return {**mobile.status(row),'status':'closed' if row['closed'] else 'active','jobs':jobs}
def watch(owner,provider,profile):
    try: activity.sync(mobile,native,owner,provider,profile)
    except (ImportError,AttributeError): pass  # Native runtime unavailable: never invent or settle activity.
    with mobile.connect() as conn: rows=[dict(r) for r in conn.execute("SELECT * FROM jobs WHERE owner=? AND profile=? AND (status IN ('watching','needs_input') OR (status='completed' AND callback=1)) ORDER BY rowid LIMIT 512",(owner,profile))]
    for job in rows:
        if job['status'] in ('watching','needs_input'):
            try:
                native.verify(owner,provider,profile,job['stored'],job['runtime'],False)
                from tui_gateway import server,event_replay
                with server._sessions_lock:
                    record=server._sessions.get(job['runtime'],{})
                # Native completion includes a persisted_turn receipt anchored to the
                # actual user row. Older completion and unrelated prompts never match.
                proof=None
                if event_replay.replay_epoch()==job['epoch']:
                    for event in event_replay.events_since(job['runtime'],0):
                        payload=event.get('payload',{})
                        if event.get('type')=='message.complete' and payload.get('persisted_turn',{}).get('user_row_id')==job['seq']:
                            proof=payload
                if not proof:
                    try:
                        from tui_gateway import server_requests
                        staged=record.get('_submit_user_row') or {}
                        pending=server_requests.open_requests(job['runtime']) if staged.get('_row_id')==job['seq'] else []
                    except (ImportError,AttributeError): pending=[]
                    with mobile.LOCK,mobile.connect() as conn:
                        conn.execute("UPDATE jobs SET status=?,history_updated=? WHERE id=? AND status IN ('watching','needs_input')",('needs_input' if pending else 'watching',time.time(),job['id']))
                        mobile.prune_notices(conn)
                    continue
                terminal={'complete':'completed','error':'failed','interrupted':'cancelled','cancelled':'cancelled'}.get(proof.get('status'))
                if not terminal: continue
                receipt=proof.get('persisted_turn',{})
                if terminal=='completed':
                    history=native.verify(owner,provider,profile,job['stored'],job['runtime'],True)
                    final_id=receipt.get('final_assistant_row_id')
                    final=next((m for m in history.get('messages',[]) if m.get('row_id',m.get('_row_id'))==final_id and m.get('role')=='assistant'),None)
                    if type(final_id) is not int or final_id<=0 or not final: continue
                    text=final.get('text',final.get('content',''))
                else: text=''
                with mobile.LOCK,mobile.connect() as conn:
                    updated=conn.execute("UPDATE jobs SET status=?,result=?,error=?,history_updated=? WHERE id=? AND status IN ('watching','needs_input')",(terminal,text[:65536],('Native Hermes turn failed; review the native session.' if terminal=='failed' else ''),time.time(),job['id']))
                if not updated.rowcount: continue
            except LookupError:
                with mobile.LOCK,mobile.connect() as conn:
                    conn.execute("UPDATE jobs SET status='failed',error='Native Hermes session no longer exists',history_updated=? WHERE id=? AND status IN ('watching','needs_input')",(time.time(),job['id']))
                continue
            except Exception: continue
        with mobile.LOCK,mobile.connect() as conn:
            current=conn.execute('SELECT * FROM jobs WHERE id=?',(job['id'],)).fetchone()
            ended=conn.execute('SELECT closed FROM calls WHERE id=?',(job['call_id'],)).fetchone()
            mobile.prune_notices(conn)
            active=mobile.active_calls(conn,owner)
            capacity=conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]<10000
            if current['status']=='completed' and current['callback'] and ended and ended[0] and not active and capacity:
                nid=uuid.uuid4().hex
                conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,job_id,reason) VALUES(?,?,?,?,?,?,?,?,?)',(nid,owner,profile,'incoming_call',job['stored'],time.time(),time.time()+120,job['id'],job['title']))
                conn.execute("UPDATE jobs SET status='callback_pending',history_updated=? WHERE id=?",(time.time(),job['id']))
    activate(owner,profile)
def task_clock(profile):
    return proactive._profile_timezone(profile)
@router.get('/mobile/tasks/capabilities')
async def task_capabilities(request:Request):
    owner,provider,profile,cfg=identity(request)
    zone=await feature(task_clock,profile,timeout=8)
    ready=cfg.get('scheduled_callbacks_enabled') is True and bool(cfg.get('scheduled_callback_conversation_id')) and zone=='Asia/Dubai'
    return {'timezone':zone,'checkups_ready':ready,'checkups_preview':'3–4 random opportunities daily, 10:00 inclusive to 22:00 exclusive, Asia/Dubai; busy and missed opportunities are skipped.'}
@router.post('/mobile/tasks/checkups')
async def create_checkups(request:Request):
    value=await body(request,1024)
    if value: raise HTTPException(400,'Check-up cadence is fixed to the reviewed 3–4 daily Dubai window')
    owner,provider,profile,cfg=identity(request)
    if cfg.get('scheduled_callbacks_enabled') is not True: raise HTTPException(409,'Scheduled callbacks are disabled on the host')
    stored=cfg.get('scheduled_callback_conversation_id')
    await feature(native.verify,owner,provider,profile,stored,timeout=8)
    if await feature(task_clock,profile,timeout=8)!='Asia/Dubai': raise HTTPException(409,'The native profile clock must resolve to Asia/Dubai')
    return await task_install(install_checkup_task,profile)
async def task_install(fn,*args):
    try: return await feature(fn,*args,timeout=8)
    except HTTPException as exc:
        if exc.status_code==504: raise HTTPException(504,'Task creation status is unknown. Refresh Tasks before retrying; the native save may still finish.') from None
        raise

def install_checkup_task(profile):
    with mobile.LOCK: return _install_checkup_task(profile)

def _install_checkup_task(profile):
    from hermes_cli.web_server_cron import _cron_profile_home,_call_cron_for_profile,_create_cron_job_sync
    from hermes_cli.web_models import CronJobCreate
    _,home=_cron_profile_home(profile)
    name='Cypher check-ups · 3–4 daily · Dubai 10–22'
    existing=_call_cron_for_profile(profile,'list_jobs',True)
    matches=[job for job in existing if job.get('name')==name and job.get('script')=='cypher-checkups.py']
    scripts=Path(home)/'scripts'
    scripts.mkdir(parents=True,exist_ok=True)
    if scripts.is_symlink() or getattr(scripts,'is_junction',lambda:False)() or scripts.resolve().parent!=Path(home).resolve(): raise ValueError('Unsafe scripts directory')
    target=scripts/'cypher-checkups.py'
    if target.is_symlink() or getattr(target,'is_junction',lambda:False)(): raise ValueError('Unsafe check-up script')
    source=("import hermes_time\nhermes_timezone=hermes_time.get_timezone_name()\nhermes_now=hermes_time.now()\nimport os\nos.environ['HERMES_HOME']="+repr(str(mobile.home()))+
            "\nimport importlib.util\nfrom pathlib import Path\nspec=importlib.util.spec_from_file_location('cypher_checkups',"+repr(str(ROOT/'checkups.py'))+")\nmodule=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(module)\nmodule.run("+repr(str(ROOT))+","+repr(profile)+",hermes_timezone,hermes_now)\n")
    legacy=("import importlib.util\nfrom pathlib import Path\nspec=importlib.util.spec_from_file_location('cypher_checkups',"+repr(str(ROOT/'checkups.py'))+")\nmodule=importlib.util.module_from_spec(spec)\nspec.loader.exec_module(module)\nmodule.run("+repr(str(ROOT))+","+repr(profile)+")\n")
    if target.exists() and target.read_text(encoding='utf-8') not in (source,legacy): raise ValueError('Existing check-up script differs; review it on the host')
    target.write_text(source,encoding='utf-8')
    if matches: return {'job':matches[0],'existing':True}
    return {'job':_create_cron_job_sync(CronJobCreate(name=name,schedule='every 1m',prompt='Owner-requested check-ups: 3–4 random opportunities in Asia/Dubai, 10:00–22:00; missed/busy opportunities skipped.',deliver='local',script='cypher-checkups.py',no_agent=True),profile),'existing':False}
@router.get('/mobile/notifications/targets')
async def targets(request:Request):
    owner,_,profile,_=identity(request)
    with mobile.connect() as conn: rows=[dict(r) for r in conn.execute('SELECT id,profile,expires FROM targets WHERE owner=? AND profile=?',(owner,profile))]
    return {'targets':[{**r,'target_id':r['id'],'kind':'fcm','revoked':r['expires']<=time.time(),**mobile.refs(owner,profile)} for r in rows]}
def firebase_sender_configured(cfg):
    credentials=cfg.get('firebase_credentials')
    if isinstance(credentials,dict): configured=credentials.get('type')=='service_account' and all(credentials.get(key) for key in ('project_id','client_email','private_key'))
    elif isinstance(credentials,str):
        try: configured=Path(credentials).is_file()
        except OSError: configured=False
    else: configured=False
    try: sdk=importlib.util.find_spec('firebase_admin') is not None
    except (ImportError,ValueError): sdk=False
    return bool(configured and sdk)

@router.get('/mobile/notifications/status')
async def notification_status(request:Request):
    owner,_,profile,cfg=identity(request)
    result=await targets(request)
    with mobile.connect() as conn:
        pending=conn.execute("SELECT COUNT(*) FROM notices WHERE owner=? AND profile=? AND push='pending' AND state='pending' AND expires>?",(owner,profile,time.time())).fetchone()[0]
        accepted=conn.execute("SELECT COUNT(*) FROM notices WHERE owner=? AND profile=? AND push='sent'",(owner,profile)).fetchone()[0]
        failed=conn.execute("SELECT COUNT(*) FROM notice_deliveries d JOIN notices n ON n.id=d.event WHERE n.owner=? AND n.profile=? AND n.state='pending' AND n.expires>? AND d.accepted=0 AND d.last_error!=''",(owner,profile,time.time())).fetchone()[0]
    return {**result,'android_configured':firebase_sender_configured(cfg),'delivery':{'pending':pending,'sender_accepted':accepted,'device_delivery_confirmed':False,'last_error':'Some device deliveries need a retry.' if failed else ''},'producers':{'calls':True,'approvals':True,'messages':True,'tasks':True},'delivery_note':'Sender acceptance is not confirmation of device arrival.'}
@router.post('/mobile/notifications/test')
async def notification_test(request:Request):
    value=await body(request,1024)
    owner,provider,profile,cfg=identity(request)
    if set(value)-{'kind','conversation_id'} or value.get('kind','fcm')!='fcm': raise HTTPException(400,'Only a synthetic FCM test is supported')
    with mobile.connect() as conn:
        if not conn.execute('SELECT 1 FROM targets WHERE owner=? AND profile=? AND expires>?',(owner,profile,time.time())).fetchone(): raise HTTPException(409,'Register this phone before testing')
    # Test has its own opaque reference and never creates a chat/model run or callback.
    event_id=uuid.uuid4().hex
    with mobile.LOCK,mobile.connect() as conn:
        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise HTTPException(409,'Notification capacity reached')
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)',(event_id,owner,profile,'test','notification-test',time.time(),time.time()+300))
    return {'event_id':event_id,'status':'queued','device_delivery_confirmed':False,**mobile.refs(owner,profile,'notification-test')}
@router.post('/mobile/notifications/targets')
async def target(request:Request):
    value=await body(request,8192);owner,_,profile,_=identity(request,value.get('profile'))
    tok=value.get('token') or value.get('fcm_token')
    tid=value.get('id') or value.get('device_id') or hashlib.sha256((owner+':'+profile+':'+str(tok)).encode()).hexdigest()
    safely(mobile.ident,tid)
    if not isinstance(tok,str) or not 20<=len(tok)<=4096 or any(ord(c)<32 for c in tok): raise HTTPException(400,'Invalid FCM target')
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('DELETE FROM targets WHERE expires<=?',(time.time(),))
        if conn.execute('SELECT COUNT(*) FROM targets').fetchone()[0]>=32 and not conn.execute('SELECT 1 FROM targets WHERE id=?',(tid,)).fetchone(): raise HTTPException(409,'Target capacity reached')
        old=conn.execute('SELECT owner,profile,token FROM targets WHERE id=?',(tid,)).fetchone()
        if old and (old['owner']!=owner or old['profile']!=profile): raise HTTPException(409,'Target already owned')
        if old and old['token']!=tok: conn.execute('DELETE FROM notice_deliveries WHERE target=?',(tid,))
        conn.execute('INSERT INTO targets VALUES(?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET token=excluded.token,expires=excluded.expires',(tid,owner,profile,tok,time.time()+30*86400,'{}'))
    return {'id':tid,'target_id':tid,'registered':True,'target':{'id':tid,'target_id':tid,**mobile.refs(owner,profile)},**mobile.refs(owner,profile)}
@router.delete('/mobile/notifications/targets')
async def remove_target(request:Request):
    value=await body(request,1024);owner,_,profile,_=identity(request)
    with mobile.LOCK,mobile.connect() as conn: conn.execute('DELETE FROM targets WHERE id=? AND owner=? AND profile=?',(value.get('id',value.get('device_id')),owner,profile))
    return {'revoked':True}
@router.delete('/mobile/notifications/targets/{target_id}')
async def remove_target_id(target_id:str,request:Request):
    owner,_,profile,_=identity(request)
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('DELETE FROM targets WHERE id=? AND owner=? AND profile=?',(target_id,owner,profile))
    return {'revoked':True,'target_id':target_id}
@router.get('/mobile/notifications/events')
async def notices(request:Request,after:int=0,wait:float=0):
    owner,provider,profile,_=identity(request)
    if after<0: raise HTTPException(400,'Invalid cursor')
    duration=_bounded_event_wait(wait)
    async def snapshot():
        await asyncio.to_thread(mobile.prune_history,owner)
        await asyncio.to_thread(sys.modules['cypher_callbacks'].prune_expired,mobile,owner)
        await asyncio.to_thread(watch,owner,provider,profile)
        return await asyncio.to_thread(_notices_after,owner,profile,after)
    rows,reset,cursor=await snapshot()
    if rows or reset or duration<=0:
        key=mobile.reference_key() if rows else None
        result={'events':[notice_wire(r,owner,key) for r in rows],'next_cursor':rows[-1]['seq'] if rows else cursor}
        if reset: result['reset']=True
        return result
    loop=asyncio.get_running_loop();deadline=loop.time()+duration
    while True:
        remaining=deadline-loop.time()
        if remaining<=0: break
        await asyncio.sleep(min(1.0,remaining))
        rows,reset,cursor=await asyncio.to_thread(_notices_after,owner,profile,after)
        if rows or reset: break
        if await request.is_disconnected(): break
    key=mobile.reference_key() if rows else None
    result={'events':[notice_wire(r,owner,key) for r in rows],'next_cursor':rows[-1]['seq'] if rows else cursor}
    if reset: result['reset']=True
    return result

def _notices_after(owner,profile,after):
    with mobile.connect() as conn:
        sequence=conn.execute("SELECT seq FROM sqlite_sequence WHERE name='notices'").fetchone()
        max_seq=sequence[0] if sequence else 0
        reset=after>max_seq
        rows=conn.execute('SELECT * FROM notices WHERE owner=? AND profile=? AND seq>? ORDER BY seq LIMIT 100',(owner,profile,0 if reset else after))
        return [dict(r) for r in rows],reset,max_seq if reset else after
def notice_preview(kind):
    if kind=='release': return 'Cypher update available','Open Cypher to install the latest verified update.'
    if kind=='test': return 'Cypher notification test','Synthetic test. Seeing this notification confirms arrival on this phone.'
    if kind=='approval': return 'Approval needed','Open Cypher to review the native request.'
    if kind=='clarify': return 'Question for you','Answer the native question or open Cypher to review it.'
    if kind=='working': return 'Cypher working','Your native Hermes session is working.'
    if kind=='activity_end': return 'Activity ended','Open Cypher to review your session.'
    if kind=='task_result': return 'Task finished','A task finished.'
    if kind=='failure': return 'Cypher work failed','Open Cypher to review the native session error.'
    if kind=='completion': return 'Reply ready','A reply is ready.'
    return 'Cypher calling','Open Cypher to hear the saved result.'
def notice_wire(row,owner,key=None):
    state='expired' if row['state']=='pending' and row['expires']<=time.time() else row['state']
    title,body=notice_preview(row['kind'])
    if row['kind']=='task_result' and row.get('reason'):
        body=row['reason'][:240]+('…' if len(row['reason'])>240 else '')
    return {**row,'event_id':row['id'],'callback_id':row['id'],'owner_id':owner,'conversation_id':row['conversation'],'session_id':row['conversation'] if row['kind']!='test' else '', 'expires_at':int(row['expires']),'state':state,'title':title,'body':body,'activity_ref':row.get('activity_key',''),'preview':{'title':title,'body':body},**mobile.refs(owner,row['profile'],row['conversation'],key)}
@router.get('/mobile/notifications/events/{event_id}')
async def notice_detail(event_id:str,request:Request):
    owner,provider,profile,_=identity(request)
    with mobile.connect() as conn:
        row=conn.execute('SELECT * FROM notices WHERE id=? AND owner=? AND profile=?',(event_id,owner,profile)).fetchone()
    if not row: raise HTTPException(404,'Owned notification unavailable')
    result=notice_wire(dict(row),owner)
    if row['request_id'] and result['state']=='pending':
        frame=await asyncio.to_thread(safely,activity.pending,native,owner,provider,profile,dict(row))
        result['actions']=activity.request_actions(frame)
        result['preview']=activity.request_preview(frame)
        if not result['preview']['complete']: result['actions']=[a for a in result['actions'] if frame['method']=='approval' and a['value']=='deny']
        params=frame.get('params',{})
        result['text_reply']=result['preview']['complete'] and frame['method']=='clarify' and not params.get('choices') and not params.get('questions') and not params.get('multi_select')
    return result

@router.post('/mobile/notifications/events/{event_id}/answer')
async def activity_answer(event_id:str,request:Request):
    value=await body(request,4096);owner,provider,profile,_=identity(request)
    return await asyncio.to_thread(safely,activity.answer,mobile,native,owner,provider,profile,event_id,value.get('answer'))

@router.post('/mobile/callbacks/{invitation_id}/{action}')
async def answer(invitation_id:str,action:str,request:Request):
    value=await body(request,1024);owner,provider,profile,cfg=identity(request)
    if action not in ('answer','decline'): raise HTTPException(404,'Unknown callback action')
    client_key=_client_key_mode(value) if action=='answer' else False
    def peek():
        with mobile.connect() as conn:
            row=conn.execute('SELECT * FROM notices WHERE id=? AND owner=? AND profile=?',(invitation_id,owner,profile)).fetchone()
        if not row or row['kind']!='incoming_call': raise HTTPException(404,'Owned invitation unavailable')
        if row['state']!='pending' or row['expires']<=time.time(): raise HTTPException(409,'Invitation consumed or expired')
        return dict(row)
    initial=await asyncio.to_thread(peek)
    if action=='answer':
        await asyncio.to_thread(reject_unowned_conversation,owner,provider,profile,initial['conversation'])
        await asyncio.to_thread(safely,native.verify,owner,provider,profile,initial['conversation'])
    source=await asyncio.to_thread(mobile.refs,owner,profile)
    selection=voice_models.selected(mobile,owner,profile,cfg)
    if action=='answer' and 'voice_provider' in value:
        requested=value['voice_provider']
        if requested not in voice_models.PROVIDERS: raise HTTPException(400,'Unknown voice model')
        if client_key:
            selection=voice_models.provider_selection(mobile,owner,profile,cfg,requested)
        else:
            if requested=='openai': raise HTTPException(409,'OpenAI Realtime requires a phone-held key')
            if not next(item for item in voice_models.choices(cfg,secret) if item['id']==requested)['available']:
                raise HTTPException(409,'Requested voice model needs server setup')
            selection=voice_models.provider_selection(mobile,owner,profile,cfg,requested)
    elif action=='answer' and client_key:
        raise HTTPException(400,'Choose a voice provider for a phone-key call')
    elif action=='answer' and selection['provider']=='openai':
        raise HTTPException(409,'OpenAI Realtime requires a phone-held key')
    def consume():
        with mobile.LOCK,mobile.connect() as conn:
            row=conn.execute('SELECT * FROM notices WHERE id=? AND owner=? AND profile=?',(invitation_id,owner,profile)).fetchone()
            if not row or row['kind']!='incoming_call': raise HTTPException(404,'Owned invitation unavailable')
            if row['state']!='pending' or row['expires']<=time.time() or row['conversation']!=initial['conversation']: raise HTTPException(409,'Invitation consumed or expired')
            if action=='decline':
                conn.execute("UPDATE notices SET state='declined' WHERE id=?",(invitation_id,))
                return {'declined':True,'state':'declined','callback_id':invitation_id,'kind':'incoming_call','profile':profile,**source}
            if mobile.active_calls(conn,owner)>=mobile.MAX_ACTIVE_CALLS: raise HTTPException(409,'Active call limit reached')
            cid=uuid.uuid4().hex
            context={key:row[key] for key in ('id','reason','job_id','conversation')}
            voice_config={**selection,**({'client_key':True} if client_key else {})}
            conn.execute('INSERT INTO calls(id,owner,profile,conversation,created,callback_context,voice_config) VALUES(?,?,?,?,?,?,?)',(cid,owner,profile,row['conversation'],time.time(),json.dumps(context),json.dumps(voice_config)))
            conn.execute("UPDATE notices SET state='answered',call_id=? WHERE id=?",(cid,invitation_id))
        return {'call_id':cid,'conversation_id':row['conversation'],'profile':profile,'recording_status':'disabled','transcript_status':'pending','callback_id':invitation_id,'kind':'incoming_call','state':'answered',**selection,**mobile.refs(owner,profile,row['conversation'])}
    result=await asyncio.to_thread(consume)
    return result
def activate(owner,profile):
    with mobile.LOCK:
        with mobile.connect() as conn:
            mobile.prune_notices(conn)
            if mobile.active_calls(conn,owner): return
            deferred=[dict(row) for row in conn.execute('SELECT * FROM deferred WHERE owner=? AND profile=? ORDER BY created LIMIT 32',(owner,profile)).fetchall()]
        observers=sys.modules['cypher_callbacks'].observers
        quiet=None;quiet_now=None
        for row in deferred:
            now=datetime.now(timezone.utc)
            explicit=sys.modules['cypher_callbacks'].deferred_allowed(mobile,row['id'],now,owner)
            if explicit is False:
                with mobile.connect() as conn: conn.execute('DELETE FROM deferred WHERE id=?',(row['id'],))
                continue
            request_id=re.match(r'Request ([0-9a-f]{32}): ',row['reason'] or '')
            request=request_id and next((r for r in proactive.list_requests(mobile,owner,profile) if r.get('id')==request_id.group(1)),None)
            if explicit is None and request and request.get('night') is not True:
                if quiet is None:
                    setup=proactive._setup(mobile,owner,profile,{name:[] for name in proactive.SIGNALS})
                    quiet_now=proactive.profile_now(profile)
                    quiet=observers._inside_quiet(quiet_now,setup)
                if quiet:
                    with mobile.connect() as conn:
                        if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: break
                    observers._notice(mobile,owner,profile,row['conversation'],row['reason'],
                                      'proactive-alert:deferred:'+row['id'],quiet_now)
                    with mobile.connect() as conn: conn.execute('DELETE FROM deferred WHERE id=?',(row['id'],))
                    continue
            with mobile.connect() as conn:
                if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: break
                conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)',(row['id'],owner,profile,'incoming_call',row['conversation'],time.time(),time.time()+120,row['reason']))
                conn.execute('DELETE FROM deferred WHERE id=?',(row['id'],))
def push(owner,profile):
    # A successful second device must not consume the first device's failed delivery.
    if not mobile.PUSH_LOCK.acquire(blocking=False): return
    try: _push_devices(owner,profile)
    finally: mobile.PUSH_LOCK.release()

def _push_devices(owner,profile):
    activate(owner,profile)
    cfg=mobile.settings();credentials=cfg.get('firebase_credentials')
    if not credentials: return
    with mobile.connect() as conn:
        rows=[dict(r) for r in conn.execute("SELECT * FROM notices WHERE owner=? AND profile=? AND push='pending' AND state='pending' AND expires>? ORDER BY seq LIMIT 64",(owner,profile,time.time()))]
        targets=[dict(r) for r in conn.execute('SELECT id,token FROM targets WHERE owner=? AND profile=? AND expires>?',(owner,profile,time.time()))]
    if not targets: return
    try:
        import firebase_admin
        from firebase_admin import credentials as fb_credentials,messaging
        try: app=firebase_admin.get_app('cypher')
        except ValueError: app=firebase_admin.initialize_app(fb_credentials.Certificate(credentials),options={'httpTimeout':10},name='cypher')
        for row in rows:
            for target in targets:
                now=time.time()
                with mobile.LOCK,mobile.connect() as conn:
                    current=conn.execute("SELECT 1 FROM notices WHERE id=? AND state='pending' AND expires>?",(row['id'],now)).fetchone()
                    device=conn.execute('SELECT 1 FROM targets WHERE id=? AND token=? AND expires>?',(target['id'],target['token'],now)).fetchone()
                    receipt=conn.execute('SELECT * FROM notice_deliveries WHERE event=? AND target=?',(row['id'],target['id'])).fetchone()
                if not current or not device or receipt and (receipt['accepted'] or receipt['next_attempt']>now): continue
                attempts=(receipt['attempts'] if receipt else 0)+1
                accepted=0;error=''
                try:
                    data={'type':row['kind'],'kind':row['kind'],'event_id':row['id'],'profile':profile,'owner_id':owner,'expires_at':str(int(row['expires'])),'activity_ref':row.get('activity_key',''),'title':notice_preview(row['kind'])[0],'body':notice_preview(row['kind'])[1],**mobile.refs(owner,profile,row['conversation'])}
                    if row['kind']=='release': data['release_version']=str(row['release_version'])
                    messaging.send(messaging.Message(token=target['token'],data=data,android=messaging.AndroidConfig(priority='high',ttl=timedelta(seconds=max(1,row['expires']-time.time())))),app=app)
                    accepted=time.time()
                except Exception as exc:
                    error=type(exc).__name__[:64]
                with mobile.LOCK,mobile.connect() as conn:
                    # A revoked/replaced device may disappear while the provider request is in flight.
                    if conn.execute('SELECT 1 FROM targets WHERE id=? AND token=?',(target['id'],target['token'])).fetchone() and conn.execute('SELECT 1 FROM notices WHERE id=?',(row['id'],)).fetchone():
                        conn.execute('INSERT OR REPLACE INTO notice_deliveries VALUES(?,?,?,?,?,?)',(row['id'],target['id'],accepted,attempts,0 if accepted else time.time()+min(60,3*2**min(attempts-1,5)),error))
                        if error=='UnregisteredError': conn.execute('UPDATE targets SET expires=0 WHERE id=?',(target['id'],))
            with mobile.LOCK,mobile.connect() as conn:
                remaining=conn.execute('SELECT COUNT(*) FROM targets t WHERE t.owner=? AND t.profile=? AND t.expires>? AND NOT EXISTS(SELECT 1 FROM notice_deliveries d WHERE d.event=? AND d.target=t.id AND d.accepted>0)',(owner,profile,time.time(),row['id'])).fetchone()[0]
                accepted=conn.execute('SELECT COUNT(*) FROM notice_deliveries WHERE event=? AND accepted>0',(row['id'],)).fetchone()[0]
                if not remaining and accepted: conn.execute("UPDATE notices SET push='sent' WHERE id=?",(row['id'],))
    except Exception: return

_release_failures={}
def announce_release(cfg):
    """A changed verified feed creates durable hints; clients still verify before installing."""
    owner=cfg.get('owner_id');profiles=cfg.get('profiles',[]);root=cfg.get('release_root')
    if not owner or not cfg.get('owner_provider') or not root or not profiles: return
    manifest=Path(root)/'latest.json'
    if not manifest.is_file(): return
    with manifest.open('rb') as stream: raw=stream.read(16385)
    if len(raw)>16384: return
    cache_key=str(manifest.resolve());fingerprint=hashlib.sha256(raw).hexdigest()
    if _release_failures.get(cache_key)==fingerprint: return
    candidate=json.loads(raw.decode('utf-8-sig')).get('version_code')
    if type(candidate) is not int or not 20<candidate<=2147483647: return
    def key(profile): return 'release-announced:'+hashlib.sha256((owner+'\0'+profile).encode()).hexdigest()
    with mobile.connect() as conn:
        wanted=[profile for profile in profiles if conn.execute('SELECT 1 FROM targets WHERE owner=? AND profile=? AND expires>?',(owner,profile,time.time())).fetchone()
                and int((conn.execute('SELECT value FROM meta WHERE key=?',(key(profile),)).fetchone() or [0])[0])<candidate]
    if not wanted: return
    try: verified=release(cfg)
    except Exception:
        _release_failures[cache_key]=fingerprint
        raise
    _release_failures.pop(cache_key,None)
    version=verified['version_code']
    with mobile.LOCK,mobile.connect() as conn:
        for profile in wanted:
            previous=conn.execute('SELECT value FROM meta WHERE key=?',(key(profile),)).fetchone()
            if previous and int(previous[0])>=version: continue
            if not conn.execute('SELECT 1 FROM targets WHERE owner=? AND profile=? AND expires>?',(owner,profile,time.time())).fetchone(): continue
            mobile.prune_notices(conn)
            if conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]>=10000: raise RuntimeError('Notification capacity reached')
            event=hashlib.sha256((owner+'\0'+profile+'\0release:'+str(version)+':'+verified['sha256']).encode()).hexdigest()
            conn.execute('INSERT OR IGNORE INTO notices(id,owner,profile,kind,conversation,created,expires,release_version) VALUES(?,?,?,?,?,?,?,?)',
                         (event,owner,profile,'release','release-'+str(version),time.time(),time.time()+7*86400,version))
            conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key(profile),version))
def release(cfg,optional=False,digest=None):
    with _RELEASE_CACHE_LOCK:
        return _release(cfg,optional,digest)

def _release(cfg,optional=False,digest=None):
    global _RELEASE_CACHE_KEY,_RELEASE_CACHE_VALUE
    configured=cfg.get('release_root')
    if not configured:
        if optional: return {'available':False}
        raise LookupError('Release unavailable')
    root=Path(configured).resolve(strict=True)
    if digest is not None and not re.fullmatch(r'[a-f0-9]{64}',digest): raise LookupError('Invalid immutable release')
    manifest=root/((digest+'.json') if digest else 'latest.json')
    if manifest.is_symlink() or getattr(manifest,'is_junction',lambda:False)(): raise ValueError('Unsafe release manifest')
    manifest_stat=manifest.stat()
    if manifest_stat.st_size>16384: raise ValueError('Oversized release manifest')
    data=json.loads(manifest.read_text(encoding='utf-8-sig'))
    filename=data.get('artifact_filename','')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+\.apk',filename): raise ValueError('Invalid artifact name')
    path=root/filename
    if path.is_symlink() or getattr(path,'is_junction',lambda:False)() or path.resolve().parent!=root: raise ValueError('Unsafe artifact')
    if data.get('package_name')!='dev.hermes.android' or type(data.get('version_code')) is not int or data['version_code']<=20 or not re.fullmatch(r'[a-fA-F0-9]{64}',data.get('certificate_sha256','')): raise ValueError('Invalid release identity')
    artifact_stat=path.stat()
    if not 1<=artifact_stat.st_size<=256*1024*1024: raise ValueError('APK size outside bounds')
    if artifact_stat.st_size!=data.get('size_bytes') or not re.fullmatch(r'[a-fA-F0-9]{64}',data.get('sha256','')): raise ValueError('Release artifact changed')
    expected=cfg.get('android_certificate_sha256')
    if not expected or expected.lower()!=data['certificate_sha256'].lower(): raise ValueError('Unexpected signing identity')
    aapt=cfg.get('aapt');signer=cfg.get('apksigner')
    if not aapt or not signer or not Path(aapt).is_file() or not Path(signer).is_file(): raise RuntimeError('APK verification tools unavailable')
    tools=[]
    for tool in (aapt,signer):
        tool_path=Path(tool);tool_stat=tool_path.stat()
        tools.append((str(tool_path.resolve()),tool_stat.st_size,tool_stat.st_mtime_ns))
    cache_key=(str(manifest.resolve()),manifest_stat.st_size,manifest_stat.st_mtime_ns,
               str(path.resolve()),artifact_stat.st_size,artifact_stat.st_mtime_ns,
               digest,expected.lower(),tuple(tools))
    if cache_key==_RELEASE_CACHE_KEY:
        return dict(_RELEASE_CACHE_VALUE)
    with path.open('rb') as stream: computed=hashlib.file_digest(stream,'sha256').hexdigest()
    if digest is not None and digest!=computed: raise ValueError('Immutable manifest hash mismatch')
    digest=computed
    if digest!=data.get('sha256'): raise ValueError('Release artifact changed')
    flags=subprocess.CREATE_NO_WINDOW if sys.platform=='win32' else 0
    try:
        badging=subprocess.run([aapt,'dump','badging',str(path)],capture_output=True,text=True,check=True,timeout=20,creationflags=flags).stdout
        certificates=subprocess.run([signer,'verify','--print-certs',str(path)],capture_output=True,text=True,check=True,timeout=20,creationflags=flags).stdout
    except (OSError,subprocess.SubprocessError): raise RuntimeError('APK verification failed') from None
    package=re.search(r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'",badging)
    digests=re.findall(r'Signer #[0-9]+ certificate SHA-256 digest: ([a-fA-F0-9]{64})',certificates)
    if not package or package[1]!=data['package_name'] or int(package[2])!=data['version_code'] or package[3]!=data['version_name'] or len(digests)!=1 or digests[0].lower()!=expected.lower(): raise ValueError('APK metadata/signature mismatch')
    result={k:data[k] for k in ('version_code','version_name','package_name','certificate_sha256','sha256','size_bytes')} | {'available':True,'download_url':'/api/plugins/cypher/mobile/releases/'+digest+'/download','artifact_path':'/api/plugins/cypher/mobile/releases/'+digest+'/download','min_contract_version':2,'max_contract_version':2}
    _RELEASE_CACHE_KEY=cache_key;_RELEASE_CACHE_VALUE=dict(result)
    return result
@router.get('/mobile/releases/latest')
async def latest(request:Request):
    _,_,_,cfg=identity(request);return await asyncio.to_thread(safely,release,cfg)
@router.get('/mobile/releases/{digest}/download')
async def download(digest:str,request:Request):
    _,_,_,cfg=identity(request);metadata=await asyncio.to_thread(safely,release,cfg,False,digest)
    if digest!=metadata['sha256']: raise HTTPException(404,'Immutable artifact unavailable')
    root=Path(cfg['release_root']).resolve();data=json.loads((root/(digest+'.json')).read_text(encoding='utf-8-sig'));path=root/data['artifact_filename']
    # Snapshot immutable bytes before any response headers; a concurrent replacement
    # cannot turn validated metadata into a different delivered package.
    content=await asyncio.to_thread(path.read_bytes)
    if hashlib.sha256(content).hexdigest()!=digest: raise HTTPException(409,'Artifact changed')
    return StreamingResponse(iter(content[i:i+65536] for i in range(0,len(content),65536)),media_type='application/vnd.android.package-archive',headers={'Content-Length':str(len(content)),'ETag':'"'+digest+'"','Cache-Control':'private, immutable'})
_watch_task=None
_jobs_upgraded=set()
async def start_watcher():
    global _watch_task
    async def loop():
        while True:
            try:
                cfg=mobile.settings()
                if cfg.get('owner_id') and cfg.get('owner_provider'):
                    try: await asyncio.to_thread(announce_release,cfg)
                    except Exception: pass  # A missing/invalid release must not stop call/approval delivery.
                    for profile in cfg.get('profiles',[]):
                        if (cfg['owner_id'],profile) not in _jobs_upgraded:
                            _jobs_upgraded.add((cfg['owner_id'],profile))  # once per process; a failure retries after restart
                            try: await asyncio.to_thread(proactive.upgrade_jobs,mobile,cfg['owner_id'],profile)
                            except Exception: pass
                        await asyncio.to_thread(watch,cfg['owner_id'],cfg['owner_provider'],profile)
                        await asyncio.to_thread(push,cfg['owner_id'],profile)
            except Exception:
                pass
            await asyncio.sleep(3)
    _watch_task=asyncio.create_task(loop())
async def stop_watcher():
    global _watch_task
    if _watch_task:
        _watch_task.cancel()
        try: await _watch_task
        except asyncio.CancelledError: pass
        _watch_task=None
_feature_pool=ThreadPoolExecutor(max_workers=4,thread_name_prefix='cypher-native-lookup')
_feature_slots=threading.BoundedSemaphore(4)
_feature_counts={}
def clear_lookup_counts(call_id):
    with mobile.LOCK:
        for key in list(_feature_counts):
            if key[0]==call_id: _feature_counts.pop(key,None)
async def feature(fn,*args,timeout=12):
    if not _feature_slots.acquire(blocking=False): raise HTTPException(429,'Native lookup capacity busy')
    context=contextvars.copy_context()
    def run():
        try: return context.run(fn,*args)
        finally: _feature_slots.release()
    try: future=_feature_pool.submit(run)
    except Exception:
        _feature_slots.release();raise HTTPException(503,'Native lookup unavailable') from None
    # A queued future cancelled before execution has no wrapper finally.
    future.add_done_callback(lambda done: _feature_slots.release() if done.cancelled() else None)
    try: return await asyncio.wait_for(asyncio.wrap_future(future),timeout=timeout)
    except asyncio.TimeoutError: raise HTTPException(504,'Native lookup timed out') from None
    except HTTPException: raise
    except LookupError: raise HTTPException(404,'Owned native context unavailable') from None
    except ValueError as exc: raise HTTPException(409,str(exc)) from None
    except Exception: raise HTTPException(503,'Native lookup unavailable') from None
def lookup_budget(call_id,kind):
    key=(call_id,kind)
    with mobile.LOCK:
        count=_feature_counts.get(key,0)
        if count>=60: raise HTTPException(429,'Call lookup limit reached')
        if len(_feature_counts)>=1024 and key not in _feature_counts: raise HTTPException(429,'Lookup state capacity reached')
        _feature_counts[key]=count+1

async def query_body(request,call_id,kind):
    value=await body(request,4096);owner,provider,row,cfg=await call(request,call_id,True)
    query=value.get('query')
    if not isinstance(query,str) or len(query)>1024 or not query.strip(): raise HTTPException(400,'A bounded query is required')
    query=' '.join(query.split())
    lookup_budget(call_id,kind)
    # The call's native stored row is reverified before any profile lookup.
    await feature(native.verify,owner,provider,row['profile'],row['conversation'],timeout=6)
    return owner,provider,row,query
@router.get('/mobile/calls/{call_id}/context')
async def get_context(call_id:str,request:Request):
    owner,provider,row,cfg=await call(request,call_id)
    result=await feature(voice_context.context,owner,provider,row['profile'],row['conversation'],call_id,timeout=8)
    callback=result.get('callback')
    reason=callback.get('reason','') if isinstance(callback,dict) else ''
    result['reason']=reason if isinstance(reason,str) else ''
    result['greeting']="I'm calling about your saved Hermes update." if callback else "I'm here."
    display=getattr(request.state.session,'display_name',None)
    if isinstance(display,str) and display: result['owner_display_name']=display[:120]
    return result
@router.post('/mobile/calls/{call_id}/search')
async def quick_search(call_id:str,request:Request):
    owner,provider,row,query=await query_body(request,call_id,'search')
    return await feature(voice_context.search,row['profile'],query,timeout=12)
@router.post('/mobile/calls/{call_id}/memory')
async def quick_memory(call_id:str,request:Request):
    owner,provider,row,query=await query_body(request,call_id,'memory')
    return await feature(voice_context.recall,owner,provider,row['profile'],row['conversation'],query,timeout=12)
@router.post('/mobile/calls/{call_id}/skills')
async def voice_skills(call_id:str,request:Request):
    value=await body(request,4096)
    owner,provider,row,cfg=await call(request,call_id,True)
    query,name=value.get('query',''),value.get('name','')
    if (set(value)-{'query','name'} or not isinstance(query,str) or len(query)>1024
            or not isinstance(name,str) or len(name)>160 or any(ord(c)<32 for c in name)):
        raise HTTPException(400,'A bounded skill name or query is required')
    lookup_budget(call_id,'skills')
    await feature(native.verify,owner,provider,row['profile'],row['conversation'],timeout=6)
    return await feature(voice_context.skills,row['profile'],query,name,skill_scope,timeout=12)
@router.post('/mobile/calls/{call_id}/profile-context')
async def current_voice_profile(call_id:str,request:Request):
    owner,provider,row,query=await query_body(request,call_id,'profile-context')
    return await feature(voice_context.profile_context,owner,provider,row['profile'],row['conversation'],call_id,timeout=8)
@router.post('/mobile/projects')
async def create_mobile_project(request:Request):
    value=await body(request,16384)
    owner,provider,profile,cfg=identity(request)
    if set(value)-{'request_id','name','description'}:
        raise HTTPException(400,'Unexpected project fields')
    return await feature(native.create_project,owner,provider,profile,value.get('request_id'),value.get('name'),value.get('description',''),timeout=8)
@router.post('/mobile/sessions/{stored_id}/reserve')
async def reserve_chat(stored_id:str,request:Request):
    value=await body(request,1024)
    owner,provider,profile,cfg=identity(request)
    if value or not 1<=len(stored_id)<=256:
        raise HTTPException(400,'Choose an existing conversation')
    await feature(native.reserve_conversation,owner,provider,profile,stored_id,timeout=6)
    return {'reserved':True,'stored_session_id':stored_id,'profile':profile}
@router.get('/mobile/sessions')
async def list_mobile_sessions(request:Request):
    owner,provider,profile,cfg=identity(request)
    hidden=request.query_params.get('include_archived')=='1'
    result=await feature(native.conversation_list,owner,provider,profile,hidden,timeout=8)
    return JSONResponse(result,headers={'Cache-Control':'no-store'})
@router.get('/mobile/sessions/{stored_id}')
async def read_mobile_session(stored_id:str,request:Request):
    owner,provider,profile,cfg=identity(request)
    if not re.fullmatch(r'[A-Za-z0-9_.:-]{1,256}',stored_id): raise HTTPException(400,'Choose an existing conversation')
    result=await feature(native.conversation_snapshot,owner,provider,profile,stored_id,timeout=8)
    return JSONResponse(result,headers={'Cache-Control':'no-store'})
@router.get('/mobile/sessions/{stored_id}/voice-history')
async def get_voice_history(stored_id:str,request:Request):
    owner,provider,profile,cfg=identity(request)
    await feature(native.verify,owner,provider,profile,stored_id,timeout=6)
    result=await feature(mobile.voice_history,owner,profile,stored_id,timeout=8)
    return JSONResponse(result,headers={'Cache-Control':'no-store'})

@router.get('/mobile/proactive')
async def proactive_status(request:Request):
    owner,_,profile,_=identity(request)
    return await feature(proactive.status,mobile,owner,profile,timeout=30)

@router.put('/mobile/proactive')
async def proactive_enabled(request:Request):
    value=await body(request,4096)
    toggle=set(value)=={'enabled'}
    if toggle:
        if type(value['enabled']) is not bool: raise HTTPException(400,'enabled must be a boolean')
    else:
        allowed={'enabled','signals','schedule','level','urgent','model','provider'}
        if set(value)-allowed or type(value.get('enabled')) is not bool:
            raise HTTPException(400,'Invalid Proactive setup fields')
    owner,provider,profile,cfg=identity(request)
    sources=None
    if not toggle:
        sources=await feature(proactive.discover_sources,profile,timeout=30)
        try: proactive.validate_setup(value,sources)
        except proactive.SourceUnavailable as exc: raise HTTPException(409,str(exc)) from None
        except ValueError as exc: raise HTTPException(400,str(exc)) from None
    if value.get('enabled') is True:
        stored=cfg.get('scheduled_callback_conversation_id')
        if not isinstance(stored,str) or not stored.strip():
            raise HTTPException(409,'Choose an owned assistant conversation before enabling Proactive')
        try: await feature(native.verify,owner,provider,profile,stored,timeout=8)
        except HTTPException as exc:
            if exc.status_code==404:
                raise HTTPException(409,'Choose an owned assistant conversation before enabling Proactive') from None
            raise
    function=proactive.set_enabled if toggle else proactive.configure
    try:
        args=(mobile,owner,profile,value['enabled']) if toggle else (mobile,owner,profile,value,sources)
        return await feature(function,*args,
                             timeout=30)
    except proactive.SourceUnavailable as exc:
        raise HTTPException(409,str(exc)) from None
    except proactive.SetupIncomplete as exc:
        raise HTTPException(409,str(exc)) from None
    except ValueError as exc:
        raise HTTPException(400,str(exc)) from None
    except RuntimeError:
        raise HTTPException(503,'Proactive setup could not be saved') from None

@router.post('/mobile/proactive/preview')
async def proactive_preview(request:Request):
    value=await body(request,1024)
    if value: raise HTTPException(400,'Preview does not accept fields')
    owner,provider,profile,cfg=identity(request)
    stored=cfg.get('scheduled_callback_conversation_id')
    if not isinstance(stored,str) or not stored.strip():
        raise HTTPException(409,'Choose an owned assistant conversation before previewing Proactive')
    try: await feature(native.verify,owner,provider,profile,stored,timeout=8)
    except HTTPException as exc:
        if exc.status_code==404:
            raise HTTPException(409,'Choose an owned assistant conversation before previewing Proactive') from None
        raise
    try: return await feature(proactive.preview,mobile,owner,profile,timeout=30)
    except proactive.SetupIncomplete as exc: raise HTTPException(409,str(exc)) from None
    except proactive.SourceUnavailable as exc: raise HTTPException(409,str(exc)) from None
    except RuntimeError: raise HTTPException(503,'Proactive preview could not be queued') from None

@router.post('/mobile/proactive/feedback')
async def proactive_feedback(request:Request):
    value=await body(request,1024)
    if set(value)!={'verdict','topic'}: raise HTTPException(400,'Expected verdict and topic')
    if value['verdict'] not in ('more','less') or not isinstance(value['topic'],str) or len(value['topic'])>200:
        raise HTTPException(400,'Feedback must be more/less with a topic up to 200 characters')
    owner,_,profile,_=identity(request)
    try: return await feature(proactive.add_feedback,mobile,owner,profile,value['verdict'],value['topic'],timeout=8)
    except ValueError as exc: raise HTTPException(400,str(exc)) from None
    except RuntimeError: raise HTTPException(503,'Proactive preferences could not be saved') from None

@router.post('/mobile/proactive/requests')
async def proactive_request_create(request:Request):
    value=await body(request,2048)
    if set(value)-{'text','action','signal','night'} or not {'text','action'}<=set(value):
        raise HTTPException(400,'Expected text and action, with optional signal and night')
    text=value.get('text');action=value.get('action');signal=value.get('signal');night=value.get('night',False)
    if not isinstance(text,str) or len(text)>300 or action not in ('notify','call'):
        raise HTTPException(400,'Request text and action are invalid')
    if signal is not None and (not isinstance(signal,str) or signal not in proactive.SIGNALS):
        raise HTTPException(400,'Unknown request signal')
    if type(night) is not bool: raise HTTPException(400,'Request night must be a boolean')
    owner,_,profile,_=identity(request)
    try: return await feature(proactive.create_request,mobile,owner,profile,text,action,signal,night,timeout=30)
    except ValueError as exc: raise HTTPException(400,str(exc)) from None
    except RuntimeError: raise HTTPException(503,'Proactive request could not be saved') from None

@router.patch('/mobile/proactive/requests/{request_id}')
async def proactive_request_patch(request_id:str,request:Request):
    value=await body(request,2048)
    if not value or set(value)-{'enabled','action','night'}: raise HTTPException(400,'Invalid Proactive request fields')
    owner,_,profile,_=identity(request)
    try: return await feature(proactive.patch_request,mobile,owner,profile,request_id,value,timeout=30)
    except LookupError as exc: raise HTTPException(404,str(exc)) from None
    except ValueError as exc: raise HTTPException(400,str(exc)) from None
    except RuntimeError: raise HTTPException(503,'Proactive request could not be updated') from None

@router.delete('/mobile/proactive/requests/{request_id}')
async def proactive_request_delete(request_id:str,request:Request):
    owner,_,profile,_=identity(request)
    try: return await feature(proactive.delete_request,mobile,owner,profile,request_id,timeout=30)
    except LookupError as exc: raise HTTPException(404,str(exc)) from None
    except RuntimeError: raise HTTPException(503,'Proactive request could not be deleted') from None

router.include_router(sys.modules['cypher_callback_api'].build_router(call,body,feature,mobile,native,sys.modules['cypher_callbacks']))
router.include_router(sys.modules['cypher_call_history_api'].build_router(identity,mobile))

