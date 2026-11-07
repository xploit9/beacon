"""Durable linkage to Hermes' own admission ledger; never submits or replays a prompt."""
import hashlib
import time
import uuid
import json
import sys
import threading
from contextvars import ContextVar
from contextlib import contextmanager
from pathlib import Path

_dispatch=ContextVar('beacon_original_receipt_dispatch',default=None)
_gateway_sha=getattr(sys.modules.get('beacon_gateway_tools'),'LOADED_SHA',None)
_loaded_sha=hashlib.sha256(Path(__file__).read_bytes()+str(_gateway_sha).encode()).hexdigest()
_registered={}
_helper_schema={'name':'beacon_continue_call_work','parameters':{'type':'object','properties':{'job_id':{'type':'string'}},
                'required':['job_id'],'additionalProperties':False}}
_helper_schema_sha=hashlib.sha256(json.dumps(_helper_schema,sort_keys=True).encode()).hexdigest()
_owner_path='/mobile/voice-owner/preflight'
PARK_AFTER_SECONDS=300
MAX_PARKED_PREPARATIONS=2048


def digest(text):
    return hashlib.sha256(text.encode('utf-8')).hexdigest()


def matches_input(text,signature):
    # NativeGateway may append this fixed notice after image staging; no owner instruction changes.
    suffix='\n[The current shared image could not be attached.]'
    return isinstance(text,str) and (digest(text)==signature or
        text.endswith(suffix) and digest(text[:-len(suffix)])==signature)


def existing_authority(profile):
    runner=getattr(sys.modules.get('gateway.run_api'),'_ACTIVE_RUNNER',None)
    if runner is None: return None
    from gateway.session_authorities import authority_for_home
    from gateway.session_authority import SessionAuthority
    from hermes_constants import get_hermes_home
    from tui_gateway import server
    home=server._profile_home(profile) if callable(getattr(server,'_profile_home',None)) else None
    authority=authority_for_home(runner,home or getattr(server,'_hermes_home',get_hermes_home())) if runner else None
    return authority if isinstance(authority,SessionAuthority) else None


def transfer_available(profile,stored,principal):
    authority=existing_authority(profile)
    live=authority.sessions.get(stored) if authority else None
    if live is None: return False
    from gateway.session_identity import parse_authenticated_subject
    parts=parse_authenticated_subject(principal)
    if parts is None: return False
    native=sys.modules['beacon_native']
    if not native.owns_row(parts[2],parts[0],profile,stored,authority.db.get_session(stored),principal): return False
    execution=native.execution_subject(parts[2],parts[0],principal)
    from gateway.config import Platform
    from gateway.session_contract import SessionRef
    from gateway.session_managed_worker import managed_policy
    if live.source.platform!=Platform.LOCAL or managed_policy(authority,SessionRef(authority.profile_id,stored)) is not None:
        return False  # This bounded handler requires the real owner process; never retire an unsupported proposal.
    with live.event_stream.lock:
        return any(actor.subject==execution and actor.profile_id==authority.profile_id and 'session:submit' in actor.capabilities
                   for actor in live.subscribers.values())


def registered_owner(authority):
    from tools.registry import registry
    from hermes_constants import hermes_home_key
    scope=hermes_home_key(authority.profile_id)
    entry=registry.get_entry('beacon_continue_call_work',scope=scope)
    saved=_registered.get(scope)
    return (isinstance(_gateway_sha,str) and getattr(sys.modules.get('beacon_gateway_tools'),'LOADED_SHA',None)==_gateway_sha and
            _compiled_sha()==_compiled_at_load and
            entry is not None and saved is not None and entry is saved[0] and entry.handler is saved[1] and
            digest(json.dumps(entry.schema,sort_keys=True))==_helper_schema_sha)


def receipt_binding(mobile,scope,stored,principal,request,conn):
    cfg=mobile.settings()
    if (cfg.get('owner_id')!=scope[0] or cfg.get('owner_provider')!=scope[1] or
            'profiles' in cfg and scope[2] not in cfg['profiles']): raise ValueError('Continuation owner changed')
    call=mobile.owned(conn,scope[3],scope[0],scope[2])
    if call['conversation']!=stored: raise ValueError('Original conversation changed')
    if call.get('principal') and call['principal']!=principal: raise ValueError('Original call principal changed')
    row=conn.execute('SELECT * FROM tool_receipts WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                     (*scope,request)).fetchone()
    if not row: raise ValueError('Original receipt unavailable')
    value=json.loads(row['payload'])
    if value.get('_principal')!=principal or value.get('_session_id')!=stored: raise ValueError('Original principal changed')
    binding=digest(json.dumps({'scope':scope,'stored':stored,'principal':principal,'request':request,
        'signature':row['signature'],'tool_id':value['tool_id'],'proposal':value.get('proposal'),
        'arguments':value.get('_arguments'),'expires':value.get('_expires'),
        'settings':{k:cfg.get(k) for k in ('owner_id','owner_provider','profiles')}},sort_keys=True,separators=(',',':')))
    return value,binding


def local_preflight(mobile,value,principal):
    if (set(value)!={'owner','provider','profile','call_id','stored','request','binding','nonce','implementation'} or
            value['implementation']!=_loaded_sha): raise ValueError('Continuation implementation unavailable')
    for key in ('call_id','stored','request','nonce'): mobile.ident(value[key])
    profile=value['profile'];stored=value['stored'];authority=existing_authority(profile)
    if (authority is None or not registered_owner(authority) or
            not transfer_available(profile,stored,principal)): raise ValueError('Native continuation unavailable')
    authority._require_admission_open()
    from gateway.session_contract import SessionRef
    live=authority.sessions[stored]
    execution=sys.modules['beacon_native'].execution_subject(value['owner'],value['provider'],principal)
    with live.event_stream.lock:
        actor=next(a for a in live.subscribers.values() if a.subject==execution and a.profile_id==authority.profile_id
                   and 'session:submit' in a.capabilities)
        authority.authorize(actor,SessionRef(authority.profile_id,stored),'session:submit')
    scope=(value['owner'],value['provider'],profile,value['call_id'])
    with mobile.connect() as conn: receipt,binding=receipt_binding(mobile,scope,stored,principal,value['request'],conn)
    proposal=receipt.get('proposal',{})
    if (receipt['status']!='needs_approval' or time.time()>=receipt.get('_expires',0) or value['binding']!=binding or
            not isinstance(proposal.get('id'),str) or proposal.get('tool_name')!=receipt.get('tool_name') or
            proposal.get('arguments')!=receipt.get('_arguments')): raise ValueError('Original proposal changed')
    dispatcher=gateway(mobile)
    tool=next((t for t in dispatcher.catalog(profile,discover=False) if t['tool_id']==receipt['tool_id']),None)
    if tool is None: raise ValueError('Original tool schema changed')
    sys.modules['beacon_gateway_tools'].validate(tool['parameters'],receipt['_arguments'])
    return {'implementation':_loaded_sha,'instance_id':authority.instance_id,'profile_id':authority.profile_id,
            'epoch':authority.epoch,'binding':binding,'nonce':value['nonce'],
            'execution':execution,
            'valid_until':min(receipt['_expires'],time.time()+5)}


def remote_preflight(profile,principal,value):
    # The existing private issuer and explicit discovery never start an owner or expose a ticket to the model.
    from hermes_cli.gateway_runtime import discover_gateway_endpoint
    from hermes_cli.gateway_client import _session_ticket
    from hermes_cli.web_server_cron import _cron_profile_home
    from urllib.parse import urlsplit
    from dataclasses import replace
    import httpx
    deadline=time.monotonic()+6.5
    home=Path(_cron_profile_home(profile)[1])
    endpoint=getattr(discover_gateway_endpoint(home,timeout=.5),'endpoint',None)
    if endpoint is None: raise ValueError('Native owner unavailable')
    origin=urlsplit(endpoint.api_origin)
    if (origin.scheme not in ('http','https') or origin.hostname not in ('127.0.0.1','::1') or
            origin.username or origin.password or origin.query or origin.fragment or origin.path not in ('','/')):
        raise ValueError('Native owner endpoint unavailable')
    if not getattr(endpoint,'control_home',True): endpoint=replace(endpoint,control_home=str(home.resolve()))
    ticket=_session_ticket(home,endpoint,purpose='native-http',subject=principal)
    remaining=deadline-time.monotonic()
    if remaining<=0: raise ValueError('Native support unconfirmed')
    with httpx.Client(timeout=remaining,trust_env=False,follow_redirects=False) as client:
        with client.stream('POST',endpoint.api_origin.rstrip('/')+'/api/plugins/beacon'+_owner_path,
                           headers={'x-hermes-gateway-ticket':ticket},json=value) as response:
            if response.status_code!=200 or response.headers.get('cache-control')!='no-store': raise ValueError('Native support unconfirmed')
            body=bytearray()
            for chunk in response.iter_bytes():
                body.extend(chunk)
                if len(body)>4096 or time.monotonic()>=deadline: raise ValueError('Native response exceeded bounds')
            proof=json.loads(body)
    if (not isinstance(proof,dict) or set(proof)!={'implementation','instance_id','profile_id','epoch','binding','nonce','valid_until','execution'} or
            proof['instance_id']!=endpoint.instance_id or proof['profile_id']!=endpoint.profile_id):
        raise ValueError('Native owner changed')
    return proof


def transfer_preflight(mobile,scope,stored,principal,request):
    try:
        with mobile.connect() as conn: _,binding=receipt_binding(mobile,scope,stored,principal,request,conn)
        value=dict(zip(('owner','provider','profile','call_id'),scope),stored=stored,request=request,binding=binding,
                   nonce=uuid.uuid4().hex,implementation=_loaded_sha)
        proof=(local_preflight(mobile,value,principal) if existing_authority(scope[2]) is not None else
               remote_preflight(scope[2],principal,value))
        if (proof['implementation']!=_loaded_sha or proof['nonce']!=value['nonce'] or proof['binding']!=binding or
                proof.get('execution')!=sys.modules['beacon_native'].execution_subject(scope[0],scope[1],principal) or
                type(proof['epoch']) is not int or proof['epoch']<=0 or time.time()>=proof['valid_until']): return None
        return proof
    except Exception:
        return None  # A refused, stale or ambiguous read-only probe never retires the original proposal.


def build_owner_router(mobile,native):
    from fastapi import APIRouter,Request
    from starlette.responses import JSONResponse
    import asyncio
    router=APIRouter()
    @router.post(_owner_path)
    async def preflight(request:Request):
        headers={'cache-control':'no-store'}
        grant=getattr(request.state,'native_http_principal',None)
        runner=getattr(request.app.state,'gateway_runner',None)
        if (not isinstance(grant,dict) or grant.get('purpose')!='native-http' or grant.get('scope')!='profile' or
                grant.get('capabilities')!=frozenset({'http:owner'}) or
                any(h in request.headers for h in ('origin','authorization','cookie'))):
            return JSONResponse({'detail':'Native owner grant required'},status_code=401,headers=headers)
        try:
            if request.headers.get('content-type','').split(';',1)[0]!='application/json': raise ValueError('JSON required')
            body=bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body)>4096: raise ValueError('Request exceeded bounds')
            value=json.loads(body)
            if not isinstance(value,dict): raise ValueError('Invalid request')
            authority=existing_authority(value.get('profile'))
            if (authority is None or authority.runner is not runner or grant.get('instance_id')!=authority.instance_id or
                    grant.get('profile_id')!=authority.profile_id or not isinstance(grant.get('subject'),str)):
                raise ValueError('Native owner changed')
            proof=await asyncio.to_thread(local_preflight,mobile,value,grant['subject'])
            return JSONResponse(proof,headers=headers)
        except Exception:
            return JSONResponse({'detail':'Exact native continuation support unavailable'},status_code=409,headers=headers)
    return router


def verify_stored(native,owner,provider,profile,stored,principal=None):
    if principal:
        return native.verify(owner,provider,profile,stored,None,True,principal)
    return native.verify(owner,provider,profile,stored)


def verify_runtime(native,owner,provider,principal,profile,stored,runtime,verify_canonical=None):
    row=verify_stored(native,owner,provider,profile,stored,principal)
    if principal and (row.get('user_id')==principal or runtime==stored):
        if runtime!=stored or verify_canonical is None:
            raise LookupError('Canonical runtime proof unavailable')
        return verify_canonical(profile,stored,runtime)
    return native.verify(owner,provider,profile,stored,runtime,False,principal)


def prepare(mobile, native, owner, provider, principal, call, value, verify_canonical=None):
    if 'tool_request_id' in value: return follow(mobile,native,owner,provider,principal,call,value,verify_canonical)
    if set(value) - {'stored_session_id','runtime_session_id','request_id','text','callback'}:
        raise ValueError('Unknown work field')
    for field in ('stored_session_id','runtime_session_id','request_id'): mobile.ident(value.get(field))
    text=value.get('text')
    if not isinstance(text,str) or not text.strip() or len(text)>16000: raise ValueError('Work text required')
    if type(value.get('callback',False)) is not bool: raise ValueError('Invalid callback choice')
    if value['stored_session_id']!=call['conversation']: raise ValueError('Work belongs to another conversation')
    bound=call.get('principal') or ''
    if bound and bound!=principal: raise LookupError('Call principal mismatch')
    verify_stored(native,owner,provider,call['profile'],call['conversation'],bound or None)
    signature=digest(text)
    with mobile.connect() as conn:
        replay=conn.execute('SELECT 1 FROM jobs WHERE owner=? AND profile=? AND request=?',
                            (owner,call['profile'],value['request_id'])).fetchone()
    # A new submission proves both identities; a durable replay never depends on a retired runtime.
    if not replay: verify_runtime(native,owner,provider,bound or None,call['profile'],call['conversation'],value['runtime_session_id'],verify_canonical)
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        current=mobile.owned(conn,call['id'],owner,call['profile'])
        old=conn.execute('SELECT * FROM jobs WHERE owner=? AND profile=? AND request=?',
                         (owner,call['profile'],value['request_id'])).fetchone()
        if old:
            if (old['call_id']!=call['id'] or old['stored']!=value['stored_session_id']
                    or old['runtime']!=value['runtime_session_id'] or old['provider']!=provider
                    or old['principal']!=principal or old['submission_digest']!=signature
                    or bool(old['intent_callback'])!=value.get('callback',False)):
                raise ValueError('Conflicting work identity')
            status={'awaiting_admission':'submission_unconfirmed','watching':'working','completed':'done',
                    'callback_pending':'done','unknown':'submission_unconfirmed'}.get(old['status'],old['status'])
            result={'job_id':old['id'],'request_id':old['request'],'status':status,'continuation':True,
                    'submit':False,'stored_session_id':old['stored'],'runtime_session_id':old['runtime']}
            if old['admission']: result['admission_id']=old['admission']
            if old['callback'] and old['status'] in ('queued','watching','needs_input') and (old['admission'] or old['seq'] is not None):
                result['callback']='scheduled'
            if old['result']: result['result']=old['result']
            if old['error']: result['error']=old['error']
            result['callback_consent']=old['callback_consent'] or ('requested' if old['callback'] else 'unset')
            return result
        mobile.owned(conn,call['id'],owner,call['profile'],True)
        if replay: raise ValueError('Work receipt changed before replay; no new submission was authorized')
        if current['conversation']!=value['stored_session_id']: raise ValueError('Work belongs to another conversation')
        if conn.execute("SELECT COUNT(*) FROM jobs WHERE call_id=? AND submission_digest<>''",(call['id'],)).fetchone()[0]>=64:
            raise ValueError('Job linkage capacity reached')
        if conn.execute('SELECT COUNT(*) FROM jobs WHERE owner=? AND profile=? AND parked=1',(owner,call['profile'])).fetchone()[0]>=MAX_PARKED_PREPARATIONS:
            raise ValueError('Unconfirmed work archive capacity reached; resolve existing work before starting more')
        if conn.execute("""SELECT COUNT(*) FROM jobs WHERE owner=? AND profile=?
            AND status IN ('awaiting_admission','queued','watching','needs_input','unknown')
            AND NOT (parked=1 AND status='awaiting_admission' AND admission='' AND seq IS NULL)""",(owner,call['profile'])).fetchone()[0]>=512:
            raise ValueError('Native watch capacity reached')
        job=uuid.uuid4().hex
        conn.execute('''INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,
                       status,history_updated,submission_digest,provider,principal,intent_callback) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,?)''',
                     (job,call['id'],owner,call['profile'],call['conversation'],value['runtime_session_id'],
                      value['request_id'],text[:100],int(value.get('callback',False)),'canonical','awaiting_admission',
                      time.time(),signature,provider,principal,int(value.get('callback',False))))
        consent='requested' if value.get('callback') else 'declined' if 'callback' in value else 'unset'
        conn.execute('UPDATE jobs SET callback_consent=? WHERE id=?',(consent,job))
    return {'job_id':job,'request_id':value['request_id'],'status':'prepared','continuation':True,'submit':True,'callback_consent':consent}


def owner_transfer_admission(mobile,job,row,require_claim=True):
    """A protected native-owner transfer attests its exact original receipt, separately from remote input."""
    if not job.get('transfer') or not job.get('tool_request'): return False
    scope=(job['owner'],job['provider'],job['profile'],job['call_id'])
    with mobile.connect() as conn:
        value,binding=receipt_binding(mobile,scope,job['stored'],job['principal'],job['tool_request'],conn)
    proof=value.get('_continuation_owner',{})
    native=sys.modules['beacon_native']
    if (proof.get('implementation')!=_loaded_sha or proof.get('binding')!=binding or
            not isinstance(proof.get('instance_id'),str) or not proof['instance_id'] or
            proof.get('execution')!=native.execution_subject(job['owner'],job['provider'],job['principal']) or
            proof.get('execution')!=row['principal_id'] or proof.get('epoch')!=row['owner_epoch'] or
            native.home_fingerprint(proof.get('profile_id'))!=native.profile_fingerprint(job['profile']) or
            row['target_session_id']!=job['stored'] or row['request_id']!=job['request'] or
            row.get('payload',{}).get('text')!=transfer_prompt(job['id'])): return False
    if require_claim and (type(row.get('generation')) is not int or row['generation']<=0 or
            value.get('_native_claim')!=row['admission_id']+':'+str(row['generation'])): return False
    return True


def native_receipt(job,remote_binding=None):
    """Read only the exact principal/profile/conversation/submission; no transcript-text matching."""
    from tui_gateway import server
    from hermes_state_runtime import find_session_admission,get_session_admission
    from gateway.session_results import admission_result
    with server._profile_db({'profile':job['profile']}) as db:
        if db is None: raise RuntimeError('Native admission store unavailable')
        stored=db.get_session(job['stored'])
        mobile=sys.modules['beacon_mobile']
        native=sys.modules['beacon_native']
        if not native.owns_row(job['owner'],job['provider'],job['profile'],job['stored'],stored,job.get('principal') or None,db):
            raise LookupError('Native conversation not owned')
        durable=json.loads(job['remote_authorization']) if job.get('remote_authorization') else None
        row=(get_session_admission(db,admission_id=job['admission']) if job['admission'] else
             native.find_admission(db,job['owner'],job['provider'],job['stored'],job['principal'],job['request']))
        if row is None: return None,None
        transferred=owner_transfer_admission(mobile,job,row) if job.get('transfer') else False
        canonical=(job.get('runtime')==job['stored'] or stored.get('user_id')!=job['provider']+':'+job['owner']
                   or row['principal_id']!=job['principal']) and not transferred
        if canonical and not durable and remote_binding is None:
            raise LookupError('Remote admission provenance unavailable')
        execution=native.execution_subject(job['owner'],job['provider'],job['principal'])
        if (row['principal_id'] not in (job['principal'],execution) or row['target_session_id']!=job['stored']
                or row['request_id']!=job['request']): raise LookupError('Native admission identity changed')
        text=row.get('payload',{}).get('text')
        signature=digest(transfer_prompt(job['id'])) if job.get('transfer') else job['submission_digest']
        if not matches_input(text,signature):
            raise LookupError('Native admission input changed')
        if canonical:
            proof=native.confirmed_admission(mobile,row,job['owner'],job['provider'],job['profile'],job['stored'],remote_binding or durable)
            with mobile.LOCK,mobile.connect() as conn:
                updated=conn.execute('''UPDATE jobs SET admission=?,remote_authorization=? WHERE id=?
                    AND owner=? AND provider=? AND profile=? AND stored=? AND request=? AND principal=?
                    AND (admission='' OR admission=?)''',
                    (row['admission_id'],json.dumps(proof),job['id'],job['owner'],job['provider'],job['profile'],
                     job['stored'],job['request'],job['principal'],row['admission_id']))
                if updated.rowcount!=1: raise LookupError('Original job authorization changed')
        saved=admission_result(db,row['admission_id']) if row['status']=='terminal' else None
        return row,saved


def confirmed_completion(mobile,job):
    if job.get('tool_request') and not job.get('transfer'):
        with mobile.connect() as conn: source_row(conn,job)
        receipt=gateway(mobile).status((job['owner'],job['provider'],job['profile'],job['call_id']),job['tool_request'])
        return receipt.get('status')=='completed' and receipt.get('result') is not None
    receipt,saved=native_receipt(job)
    result=saved.get('result') if isinstance(saved,dict) else None
    return (receipt is not None and receipt.get('status')=='terminal' and receipt.get('outcome')=='completed'
            and isinstance(result,dict) and result.get('completed') is not False
            and not any(result.get(key) for key in ('failed','error','interrupted'))
            and isinstance(result.get('final_response'),str))


def watch_batch(mobile,owner,provider,profile,tools=False):
    condition=("tool_request<>'' AND status NOT IN ('completed','callback_pending','failed','cancelled')" if tools else
        "epoch='canonical' AND tool_request='' AND submission_digest<>'' AND status IN ('awaiting_admission','queued','watching','needs_input','unknown')")
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        rows=[dict(r) for r in conn.execute('SELECT * FROM jobs WHERE owner=? AND provider=? AND profile=? AND '+condition+
            ' ORDER BY native_probe,rowid LIMIT 512',(owner,provider,profile))]
        if rows:
            probe=conn.execute('SELECT COALESCE(MAX(native_probe),0)+1 FROM jobs WHERE owner=? AND provider=? AND profile=?',
                (owner,provider,profile)).fetchone()[0]
            conn.executemany('UPDATE jobs SET native_probe=? WHERE id=?',[(probe,r['id']) for r in rows])
    return rows


def park_preparation(mobile,job):
    if job['parked'] or job['status']!='awaiting_admission' or job['admission'] or job['seq'] is not None: return
    cutoff=time.time()-PARK_AFTER_SECONDS
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        row=conn.execute("""SELECT j.id FROM jobs j JOIN calls c ON c.id=j.call_id AND c.owner=j.owner AND c.profile=j.profile
            WHERE j.id=? AND j.owner=? AND j.provider=? AND j.profile=? AND j.parked=0
              AND j.status='awaiting_admission' AND j.epoch='canonical' AND j.tool_request=''
              AND j.admission='' AND j.seq IS NULL AND j.history_updated<=?
              AND c.closed=1 AND COALESCE(c.ended,c.created)<=?""",
            (job['id'],job['owner'],job['provider'],job['profile'],cutoff,cutoff)).fetchone()
        if row and conn.execute('SELECT COUNT(*) FROM jobs WHERE owner=? AND profile=? AND parked=1',
            (job['owner'],job['profile'])).fetchone()[0]<MAX_PARKED_PREPARATIONS:
            conn.execute('UPDATE jobs SET parked=1 WHERE id=?',(job['id'],))


def sync(mobile,native,owner,provider,profile):
    sync_tools(mobile,native,owner,provider,profile)
    jobs=watch_batch(mobile,owner,provider,profile)
    for job in jobs:
        try:
            verify_stored(native,owner,provider,profile,job['stored'],job.get('principal') or None)
            receipt,saved=native_receipt(job)
            if receipt is None:
                park_preparation(mobile,job)
                continue
            state=receipt['status'];error='';result=''
            if state=='queued': status='queued'
            elif state=='started': status='watching'
            elif state=='unknown': status='unknown';error='Native Hermes outcome is unconfirmed. Do not retry automatically.'
            elif state=='terminal':
                outcome=receipt.get('outcome')
                status={'completed':'completed','failed':'failed','rejected':'failed','interrupted':'cancelled','cancelled':'cancelled'}.get(outcome,'unknown')
                if status=='completed':
                    value=saved.get('result') if isinstance(saved,dict) else None
                    if (not isinstance(value,dict) or value.get('failed') or value.get('error')
                            or value.get('interrupted') or value.get('completed') is False
                            or not isinstance(value.get('final_response'),str)):
                        status='unknown';error='Native completion result is unconfirmed. Review the conversation.'
                    else: result=value['final_response'][:65536]
                elif status=='failed': error='Native Hermes turn failed; review the native session.'
            else: continue
            with mobile.LOCK,mobile.connect() as conn:
                conn.execute("UPDATE jobs SET admission=?,status=?,result=?,error=?,history_updated=? WHERE id=? AND status IN ('awaiting_admission','queued','watching','needs_input','unknown')",
                             (receipt['admission_id'],status,result,error,time.time(),job['id']))
        except (LookupError,RuntimeError,ValueError,ImportError,AttributeError):
            # Unavailable ownership/store evidence cannot settle work or cause a resend.
            continue


def transfer_prompt(job_id):
    return ('Continue the existing call operation by calling beacon_continue_call_work with job_id "'+job_id+'" once. '
            'Wait for its native approval and report only its exact receipt. Do not call another tool or repeat the original operation.')


def gateway(mobile):
    # status() and the internal continuation reuse the existing dispatcher and durable receipts.
    instance=sys.modules['beacon_gateway_tools'].Gateway(mobile,sys.modules['beacon_voice_context'].scoped)
    instance.scoped=instance.backend.scoped_metadata
    return instance


def source_row(conn,job):
    row=conn.execute('SELECT * FROM tool_receipts WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                     (job['owner'],job['provider'],job['profile'],job['call_id'],job['tool_request'])).fetchone()
    if not row or row['signature']!=job['submission_digest']: raise ValueError('Original tool receipt identity changed')
    value=json.loads(row['payload'])
    if value.get('_session_id')!=job['stored'] or value.get('_principal')!=job['principal']:
        raise ValueError('Original tool conversation or principal changed')
    return value


def follow(mobile,native,owner,provider,principal,call,value,verify_canonical=None):
    if set(value)-{'stored_session_id','runtime_session_id','request_id','tool_request_id','callback'}: raise ValueError('Unknown follow field')
    for key in ('stored_session_id','runtime_session_id','request_id','tool_request_id'): mobile.ident(value.get(key))
    if type(value.get('callback',False)) is not bool: raise ValueError('Invalid callback choice')
    if value['stored_session_id']!=call['conversation']: raise ValueError('Work belongs to another conversation')
    bound=call.get('principal') or ''
    if bound and bound!=principal: raise LookupError('Call principal mismatch')
    verify_stored(native,owner,provider,call['profile'],call['conversation'],bound or None)
    with mobile.connect() as conn:
        old=conn.execute('SELECT * FROM jobs WHERE owner=? AND provider=? AND profile=? AND call_id=? AND tool_request=?',
                         (owner,provider,call['profile'],call['id'],value['tool_request_id'])).fetchone()
    if old:
        if old['principal']!=principal or old['stored']!=value['stored_session_id']: raise ValueError('Follow principal or conversation changed')
        return {'job_id':old['id'],'status':{'completed':'done','callback_pending':'done','unknown':'submission_unconfirmed',
                'awaiting_admission':'submission_unconfirmed'}.get(old['status'],old['status']),
                'submit':False,'continuation':True,'tool_request_id':old['tool_request'],'request_id':old['request'],
                'callback_consent':old['callback_consent'] or ('requested' if old['callback'] else 'unset')}
    verify_runtime(native,owner,provider,bound or None,call['profile'],call['conversation'],value['runtime_session_id'],verify_canonical)
    scope=(owner,provider,call['profile'],call['id'])
    current=gateway(mobile).status(scope,value['tool_request_id'])
    proof=transfer_preflight(mobile,scope,call['conversation'],principal,value['tool_request_id']) if current['status']=='needs_approval' else None
    with mobile.LOCK,mobile.connect() as conn:
        conn.execute('BEGIN IMMEDIATE')
        # An exact accepted receipt outlives audio; this path cannot submit a new owner instruction.
        current_call=mobile.owned(conn,call['id'],owner,call['profile'])
        if time.time()-current_call['created']>4*3600: raise ValueError('Call admission expired')
        source=conn.execute('SELECT * FROM tool_receipts WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                            (*scope,value['tool_request_id'])).fetchone()
        if not source: raise LookupError('Original tool receipt unavailable')
        receipt=json.loads(source['payload'])
        if receipt.get('_session_id')!=call['conversation'] or receipt.get('_principal')!=principal:
            raise ValueError('Original tool conversation or principal unavailable')
        if receipt.get('_follow_job'):
            old=conn.execute('SELECT * FROM jobs WHERE id=?',(receipt['_follow_job'],)).fetchone()
            if not old or old['principal']!=principal: raise ValueError('Original follow identity changed')
            return {'job_id':old['id'],'status':'following','submit':False,'continuation':True,'request_id':old['request']}
        if receipt.get('status') not in ('ready','running','needs_approval','completed','failed','uncertain','denied','expired','cancelled'):
            raise ValueError('Original tool state unavailable')
        transfer=receipt['status']=='needs_approval'
        if transfer and (time.time()>=receipt.get('_expires',0) or not receipt.get('proposal')):
            raise ValueError('Original approval expired')
        if transfer:
            _,binding=receipt_binding(mobile,scope,call['conversation'],principal,value['tool_request_id'],conn)
            if not proof or proof['binding']!=binding or time.time()>=proof['valid_until']:
                raise ValueError('Native continuation approval is unavailable; original proposal was not transferred')
        if conn.execute('SELECT COUNT(*) FROM jobs WHERE call_id=?',(call['id'],)).fetchone()[0]>=64: raise ValueError('Call follow capacity reached')
        job=uuid.uuid4().hex
        conn.execute('''INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,
                        history_updated,submission_digest,provider,principal,intent_callback,tool_request,transfer)
                        VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,?,?,?,?,?,?,?)''',
                     (job,call['id'],owner,call['profile'],call['conversation'],value['runtime_session_id'],value['request_id'],
                      str(receipt.get('tool_name') or 'Call work')[:100],int(value.get('callback',False)),
                      'canonical' if transfer else 'tool_receipt','awaiting_admission' if transfer else 'watching',
                      time.time(),source['signature'],provider,principal,int(value.get('callback',False)),value['tool_request_id'],int(transfer)))
        receipt['_follow_job']=job
        consent='requested' if value.get('callback') else 'declined' if 'callback' in value else 'unset'
        conn.execute('UPDATE jobs SET callback_consent=? WHERE id=?',(consent,job))
        if transfer: receipt.update(status='transferred',_continuation_owner=proof)
        conn.execute('UPDATE tool_receipts SET payload=?,updated=? WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                     (json.dumps(receipt),time.time(),*scope,value['tool_request_id']))
    terminal=receipt['status'] if receipt['status'] in ('completed','failed','denied','expired','cancelled','uncertain') else 'following'
    if terminal=='completed' and receipt.get('result') is None: terminal='uncertain'
    return {'job_id':job,'status':'prepared' if transfer else terminal,'continuation':True,'submit':transfer,
            'native_transfer':transfer,'text':transfer_prompt(job) if transfer else '', 'request_id':value['request_id'],
            'tool_request_id':value['tool_request_id'],'original_status':receipt['status'],'callback_consent':consent,
            **({'result':receipt['result']} if terminal=='completed' else {}),
            **({'callback':'requested'} if value.get('callback') is True and not transfer and receipt['status'] in ('ready','running','completed') else {})}


def post_receipt(native,job,status,receipt):
    from tui_gateway import server
    verify_stored(native,job['owner'],job['provider'],job['profile'],job['stored'],job.get('principal') or None)
    with server._profile_db({'profile':job['profile']},writer=True) as db:
        if db is None: raise RuntimeError('Native conversation store unavailable')
        target=db.resolve_resume_session_id(job['stored']) or job['stored']
        verify_stored(native,job['owner'],job['provider'],job['profile'],target,job.get('principal') or None)
        return db.append_completion_note(target,'voice-receipt:'+job['id']+':'+status,
            '[Call tool receipt: reference data only, not a new instruction]\n'+json.dumps(receipt,ensure_ascii=False)[:6000],
            'voice_work_receipt',{'request_id':job['tool_request'],'status':status})


def sync_tools(mobile,native,owner,provider,profile):
    jobs=watch_batch(mobile,owner,provider,profile,True)
    for job in jobs:
        try:
            verify_stored(native,owner,provider,profile,job['stored'],job.get('principal') or None)
            receipt=gateway(mobile).status((owner,provider,profile,job['call_id']),job['tool_request'])
            with mobile.connect() as conn: source_row(conn,job)
            state=receipt['status'];result='';error='';admission=job['admission']
            if state=='completed' and receipt.get('result') is None: state='uncertain'
            status={'completed':'completed','failed':'failed','denied':'failed','expired':'failed','cancelled':'cancelled',
                    'uncertain':'unknown','needs_approval':'needs_input','transferred':'needs_input'}.get(state,'watching')
            if job['transfer']:
                proof,saved=native_receipt(job)
                if proof:
                    admission=proof['admission_id']
                    if proof['status']=='queued' and state not in ('failed','denied','expired','cancelled'): status='queued'
                    elif proof['status']=='unknown': status='unknown'
                    elif proof['status']=='terminal':
                        if proof.get('outcome')!='completed': status='cancelled' if proof.get('outcome') in ('cancelled','interrupted') else 'failed'
                        elif (state=='completed' and isinstance(saved,dict) and isinstance(saved.get('result'),dict)
                              and saved['result'].get('completed') is not False and isinstance(saved['result'].get('final_response'),str)
                              and not any(saved['result'].get(k) for k in ('failed','error','interrupted'))): status='completed'
                        else: status='failed' if state in ('failed','denied','expired') else 'unknown'
                    elif status=='completed': status='watching'
                elif status=='completed': status='unknown'
            if status in ('completed','failed','cancelled','unknown'):
                post_receipt(native,job,status,receipt)
            if status=='completed': result=json.dumps(receipt.get('result'),ensure_ascii=False)[:65536]
            if status in ('failed','unknown'): error='Original operation '+state+'; inspect its exact receipt before retrying.'
            with mobile.LOCK,mobile.connect() as conn:
                conn.execute("UPDATE jobs SET status=?,result=?,error=?,admission=?,history_updated=? WHERE id=? AND status NOT IN ('completed','callback_pending','failed','cancelled')",
                             (status,result,error,admission,time.time(),job['id']))
                if status in ('completed','failed','cancelled'):
                    stored=source_row(conn,job);stored.pop('_follow_job',None)
                    conn.execute('UPDATE tool_receipts SET payload=? WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                                 (json.dumps(stored),owner,provider,profile,job['call_id'],job['tool_request']))
        except (ValueError,LookupError):
            with mobile.LOCK,mobile.connect() as conn:
                conn.execute("UPDATE jobs SET status='unknown',error=?,history_updated=? WHERE id=? AND status NOT IN ('completed','callback_pending','failed','cancelled')",
                             ('Original receipt or conversation binding is unconfirmed; do not repeat the action.',time.time(),job['id']))
        except Exception:
            # Missing binding/store/receipt evidence cannot authorize a dispatch or a callback.
            continue


def native_binding(mobile,job_id,session_id=None,for_guard=False):
    """Only the actual executing canonical viewer claim may settle a transferred proposal."""
    from gateway.session_ingress import viewer_turn,executing_admission
    from gateway.session_operator import check_local_input,is_viewer_input
    from gateway.session_contract import SessionRef
    from hermes_constants import get_hermes_home
    from hermes_state_runtime import find_session_admission
    from gateway.config import Platform
    from tools.interrupt import is_interrupted
    with mobile.connect() as conn:
        row=conn.execute('SELECT * FROM jobs WHERE id=? AND transfer=1',(job_id,)).fetchone()
        if not row: raise ValueError('Native continuation unavailable')
        job=dict(row)
        if not for_guard: source_row(conn,job)
    authority=existing_authority(job['profile'])
    if authority is None: raise ValueError('Existing native session owner required')
    if not for_guard:
        with mobile.connect() as conn:
            value,binding=receipt_binding(mobile,(job['owner'],job['provider'],job['profile'],job['call_id']),
                                          job['stored'],job['principal'],job['tool_request'],conn)
        proof=value.get('_continuation_owner',{})
        if (not registered_owner(authority) or proof.get('implementation')!=_loaded_sha or proof.get('binding')!=binding or
                proof.get('profile_id')!=authority.profile_id or proof.get('instance_id')!=authority.instance_id or
                proof.get('epoch')!=authority.epoch): raise ValueError('Native continuation owner or proposal changed')
    if Path(authority.profile_id).resolve()!=get_hermes_home().resolve(): raise ValueError('Native profile context changed')
    live=authority.sessions.get(job['stored'])
    if (live is None or live.source.platform!=Platform.LOCAL or session_id not in (None,job['stored']) or
            not for_guard and (not executing_admission.get() or viewer_turn.get() is not live or is_interrupted())):
        raise ValueError('Bound interactive native turn required')
    native=sys.modules['beacon_native']
    admission=native.find_admission(authority.db,job['owner'],job['provider'],job['stored'],job['principal'],job['request'])
    if (not admission or admission['status']!='started' or admission['owner_epoch']!=authority.epoch
            or admission['payload'].get('text')!=transfer_prompt(job['id']) or not is_viewer_input(admission)):
        raise ValueError('Exact native continuation admission required')
    stored=authority.db.get_session(job['stored'])
    if not stored or stored.get('source')=='cron': raise ValueError('Scheduled work cannot settle this request')
    if not native.owns_row(job['owner'],job['provider'],job['profile'],job['stored'],stored,job['principal'],authority.db):
        raise ValueError('Original conversation principal changed')
    if (job.get('runtime')==job['stored'] or stored.get('user_id')!=job['provider']+':'+job['owner']
            or admission['principal_id']!=job['principal']) and not owner_transfer_admission(mobile,job,admission,False):
        sys.modules['beacon_native'].confirmed_admission(mobile,admission,job['owner'],job['provider'],job['profile'],
                                                       job['stored'],json.loads(job['remote_authorization']) if job.get('remote_authorization') else None)
    check_local_input(authority,SessionRef(authority.profile_id,job['stored']),admission)
    authority.check_approval_generation(job['stored'],admission['generation'])
    if authority.executing.get(job['stored'])!=admission['generation']: raise ValueError('Native continuation generation changed')
    if not for_guard and authority.pending_stops.get(job['stored'])==admission['generation']:
        raise ValueError('Native continuation was cancelled')
    return job,authority,live,admission


def current_voice_job(mobile,session_id):
    from gateway.session_ingress import viewer_turn,executing_admission
    from hermes_constants import get_hermes_home
    if not executing_admission.get() or viewer_turn.get() is None or not session_id: return None
    with mobile.connect() as conn:
        jobs=[dict(r) for r in conn.execute("SELECT * FROM jobs WHERE stored=? AND epoch='canonical' AND transfer=0",(session_id,))]
    for job in jobs:
        authority=existing_authority(job['profile'])
        if (authority is None or Path(authority.profile_id).resolve()!=get_hermes_home().resolve()
                or authority.sessions.get(session_id) is not viewer_turn.get()): continue
        proof,_=native_receipt(job)
        if proof and proof['status']=='started' and authority.executing.get(session_id)==proof['generation']: return job
    return None


def callback_context(mobile,session_id=None,**kwargs):
    try:
        job=current_voice_job(mobile,session_id)
        if job:
            consent=job['callback_consent'] or ('requested' if job['callback'] else 'unset')
            return {'context':'This exact voice task has callback consent '+consent+'. Finish the requested work with normal tools and approvals. '
                    'Its completion callback is owned by the existing verified receipt watcher after committed completion. '
                    'Do not ring the owner or independently schedule another completion callback from this turn.'}
    except Exception: return None


def continue_tool(mobile,native,args,session_id=None,**kwargs):
    owned_claim=False
    try:
        if not isinstance(args,dict) or set(args)!={'job_id'}: raise ValueError('Exact job identity required')
        job,authority,live,admission=native_binding(mobile,mobile.ident(args['job_id']),session_id)
        scope=(job['owner'],job['provider'],job['profile'],job['call_id'])
        dispatcher=gateway(mobile)
        with mobile.LOCK,mobile.connect() as conn:
            conn.execute('BEGIN IMMEDIATE')
            value=source_row(conn,job)
            if value['status']!='transferred': return json.dumps({k:v for k,v in value.items() if not k.startswith('_')})
            if value.get('_native_claim'): raise ValueError('This continuation already owns its single decision')
            if time.time()>=value.get('_expires',0): raise ValueError('Original approval expired')
            proposal=value.get('proposal',{})
            if not isinstance(proposal.get('id'),str) or proposal.get('tool_name')!=value.get('tool_name'):
                raise ValueError('Original proposal identity changed')
            claim=admission['admission_id']+':'+str(admission['generation'])
            value['_native_claim']=claim
            dispatcher._save(conn,scope,job['tool_request'],value)
            owned_claim=True
        tool=next((t for t in dispatcher.catalog(job['profile'],discover=False) if t['tool_id']==value['tool_id']),None)
        if not tool: raise ValueError('Original tool schema changed')
        choice=[];answered=threading.Event();prompt=proposal['id'];generation=admission['generation']
        def respond(kind,prompt_id,selected):
            if (kind!='approval' or prompt_id!=prompt or selected not in ('once','deny') or time.time()>=value['_expires']):
                from hermes_state_runtime import RuntimeStoreError
                raise RuntimeStoreError('not_found')
            if not answered.is_set(): choice.append(selected);answered.set()
        try:
            with live.event_stream.lock:
                if prompt in live.controls.pending: raise ValueError('Original approval is already pending')
                live.controls.remote_responders[prompt]=respond
                authority.register_approval(job['stored'],generation,live.route,
                    {'request_id':prompt,'command':proposal['tool_name']+' '+json.dumps(proposal['arguments'],ensure_ascii=False),
                     'description':'Continue the exact call operation; the original proposal cannot execute separately.',
                     'allow_session':False,'allow_permanent':False},timeout_s=max(0,value['_expires']-time.time()))
            while not answered.wait(min(.25,max(0,value['_expires']-time.time()))):
                native_binding(mobile,job['id'],session_id)
                if time.time()>=value['_expires']: break
        finally:
            with live.event_stream.lock:
                live.controls.remote_responders.pop(prompt,None)
                saved=live.controls.pending.pop(prompt,None)
                if saved: live.event_stream.publish(job['stored'],{'prompt_id':prompt,'execution_generation':generation},event_type='approval.settled')
        native_binding(mobile,job['id'],session_id)
        with mobile.LOCK,mobile.connect() as conn:
            current=source_row(conn,job)
            if current.get('_native_claim')!=claim or current['status']!='transferred': raise ValueError('Original operation already settled')
            if time.time()>=current['_expires'] or not choice:
                current.update(status='expired',reason='Original approval expired')
            elif choice[0]=='deny': current.update(status='denied',reason='Owner denied the exact operation')
            dispatcher._save(conn,scope,job['tool_request'],current)
        if choice and choice[0]=='once' and current['status']=='transferred':
            dispatcher._start(scope,job['tool_request'],tool,True,transfer_job=job['id'])
            while True:
                native_binding(mobile,job['id'],session_id)
                result=dispatcher.status(scope,job['tool_request'])
                if result['status']!='running': return json.dumps(result)
                time.sleep(.1)
        return json.dumps(dispatcher.status(scope,job['tool_request']))
    except Exception:
        if owned_claim:
            try:
                with mobile.LOCK,mobile.connect() as conn:
                    current=source_row(conn,job)
                    if current.get('_native_claim')==claim and current['status']=='transferred':
                        current.update(status='failed',reason='Native continuation could not obtain a valid approval; operation was not started')
                        dispatcher._save(conn,scope,job['tool_request'],current)
            except Exception: pass  # Changed/missing receipt evidence cannot authorize even a cleanup write.
        # No native claim/approval/schema proof means no dispatch, including after a restart.
        return json.dumps({'status':'failed','error':'Exact native continuation or approval is unavailable; no action was replayed.'})


def guard(mobile,tool_name=None,args=None,session_id=None,**kwargs):
    from gateway.session_ingress import viewer_turn,executing_admission
    if not session_id:
        return {'block':'Internal continuation requires its exact native viewer turn.'} if tool_name=='beacon_continue_call_work' else None
    try:
        if tool_name=='beacon_call_user' and current_voice_job(mobile,session_id):
            return {'block':'The completion callback is managed after this exact task has a verified committed result.'}
        with mobile.connect() as conn:
            jobs=conn.execute('SELECT id FROM jobs WHERE stored=? AND transfer=1',(session_id,)).fetchall()
        for row in jobs:
            try: job,authority,_,_=native_binding(mobile,row['id'],session_id,for_guard=True)
            except ValueError: continue
            if tool_name=='beacon_continue_call_work' and args=={'job_id':job['id']}:
                return None if registered_owner(authority) and executing_admission.get() and viewer_turn.get() is not None else {'block':'Bound native execution context is unavailable.'}
            authorized=_dispatch.get()
            if (authorized and not authorized['used'] and authorized['job']==job['id'] and
                    authorized['name']==tool_name and authorized['arguments']==args):
                authorized['used']=True
                return None
            return {'block':'This native continuation may only settle its exact original call receipt; no other tool is authorized.'}
        return {'block':'No matching native continuation claim.'} if tool_name=='beacon_continue_call_work' else None
    except Exception:
        return {'block':'Continuation authority could not be verified.'}


@contextmanager
def original_dispatch(mobile,job_id,tool,arguments):
    job,_,_,admission=native_binding(mobile,job_id)
    with mobile.connect() as conn: value=source_row(conn,job)
    if (value['status']!='running' or value.get('approved_by')!='owner' or tool['tool_id']!=value['tool_id']
            or value.get('_native_claim')!=admission['admission_id']+':'+str(admission['generation'])
            or value['_arguments']!=arguments): raise ValueError('Original dispatch proof changed')
    token=_dispatch.set({'job':job['id'],'name':tool.get('_raw') if tool.get('_kind')=='hermes' else tool['name'],
                         'arguments':arguments,'used':False})
    try: yield
    finally: _dispatch.reset(token)


def register(ctx,mobile,native):
    handler=lambda args,session_id=None,**kw:continue_tool(mobile,native,args,session_id,**kw)
    ctx.register_tool(name='beacon_continue_call_work',toolset='beacon',
        description='Internal: settle only an atomically transferred call proposal under its exact native admission and normal once/deny approval.',
        schema=_helper_schema,handler=handler)
    from tools.registry import registry
    from hermes_constants import hermes_home_key
    scope=hermes_home_key()
    entry=registry.get_entry('beacon_continue_call_work',scope=scope)
    if entry is not None and entry.handler is handler: _registered[scope]=(entry,handler)
    ctx.register_hook('pre_tool_call',lambda **kw:guard(mobile,**kw))
    ctx.register_hook('pre_llm_call',lambda **kw:callback_context(mobile,**kw))


def _compiled_sha():
    # Import-time disk identity also binds the actual loaded execution/guard code, including nested functions.
    import inspect
    import marshal
    from types import CodeType
    def clean(code):
        return code.replace(co_filename='',co_firstlineno=0,
            co_consts=tuple(clean(c) if isinstance(c,CodeType) else c for c in code.co_consts))
    dispatcher=sys.modules['beacon_gateway_tools']
    functions=(_compiled_sha,registered_owner,local_preflight,receipt_binding,owner_transfer_admission,native_binding,continue_tool,guard,original_dispatch,
               dispatcher.Gateway._start,dispatcher.Gateway._run,dispatcher.Gateway.status,
               dispatcher.NativeMcp.execute,dispatcher.NativeMcp._execute,dispatcher.producer_identity,dispatcher.producer_running)
    return hashlib.sha256(b''.join(marshal.dumps(clean(inspect.unwrap(fn).__code__)) for fn in functions)).hexdigest()


_compiled_at_load=_compiled_sha() if isinstance(_gateway_sha,str) else None
_loaded_sha=digest(_loaded_sha+str(_compiled_at_load))
