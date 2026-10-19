"""Native Cypher callbacks and profile-scoped proactive jobs."""
import importlib.util
import json
from pathlib import Path
import sys

ROOT=Path(__file__).resolve().parent
for name in ('mobile','native','observers','proactive','phone_delivery'):
    key='cypher_'+name
    if key not in sys.modules:
        spec=importlib.util.spec_from_file_location(key,ROOT/(name+'.py'))
        module=importlib.util.module_from_spec(spec);sys.modules[key]=module;spec.loader.exec_module(module)
mobile=sys.modules['cypher_mobile'];native=sys.modules['cypher_native'];observers=sys.modules['cypher_observers']
proactive=sys.modules['cypher_proactive'];phone_delivery=sys.modules['cypher_phone_delivery']

def _cron_fallback_allowed(cfg,row):
    if row.get('source')!='cron' or cfg.get('scheduled_callbacks_enabled') is not True: return False
    try:
        from hermes_state_keys import CRON_PRINCIPAL_ID
    except ImportError:
        CRON_PRINCIPAL_ID='cron-owner'  # Hermes's stable scheduler principal remains known without this helper module.
    return row.get('user_id') in (None,'',CRON_PRINCIPAL_ID)

def call_user(args,session_id=None,**kwargs):
    cfg=mobile.settings()
    if cfg.get('callback_tool_enabled') is not True:
        return json.dumps({'error':'Native callback tool disabled'})
    from hermes_constants import get_hermes_home
    from tui_gateway import server
    owner=cfg.get('owner_id');provider=cfg.get('owner_provider')
    if not owner or not provider or not session_id:
        return json.dumps({'error':'Bound native owner/session required'})
    # Identify current profile by the native context home, never a tool-supplied profile.
    matches=[];resolution_failed=False
    for profile in cfg.get('profiles',[]):
        try:
            requested=server._profile_home(profile)
            target=Path(requested) if requested else mobile.home()
            if target.resolve()==Path(get_hermes_home()).resolve(): matches.append(profile)
        except Exception: resolution_failed=True
    if len(matches)!=1: return json.dumps({'error':'Native profile scope unavailable','reason':'profile_resolution_failed' if resolution_failed else 'no_unique_profile'})
    profile=matches[0]
    try:
        stored=session_id
        with server._profile_db({'profile':profile}) as db:
            row=db.get_session(stored) if db else None
        if not row: raise LookupError('Native session unavailable')
        if row.get('user_id')!=provider+':'+owner:
            if not _cron_fallback_allowed(cfg,row):
                return json.dumps({'error':'This chat is not linked to your Hermes sign-in, so Cypher cannot ring from it. Start a new chat in Cypher and ask again.','reason':'conversation_not_owned'})
            stored=cfg.get('scheduled_callback_conversation_id')
        native.verify(owner,provider,profile,stored)
        reason=args.get('reason',row.get('title','')) if isinstance(args,dict) else row.get('title','')
        if reason is None: reason=''
        reason=observers.reason_text(reason)
        event_id=mobile.invitation(owner,profile,stored,reason)
        # The durable outbox is the authority. FCM is asynchronous wake-up only.
        return json.dumps({'event_id':event_id,'status':'pending','expires_in_seconds':120,'conversation_id':stored})
    except Exception:
        return json.dumps({'error':'Owned native callback context unavailable'})

def current_profile(cfg):
    return proactive.current_profile(cfg)

def _cron_row(profile,session_id):
    from tui_gateway import server
    with server._profile_db({'profile':profile}) as db:
        return db.get_session(session_id) if db else None

def _watch_context(cfg,profile,session_id):
    owner,provider=cfg.get('owner_id'),cfg.get('owner_provider')
    if not owner or not provider or profile not in cfg.get('profiles',[]) or not session_id:
        raise LookupError('Owned Proactive profile required')
    row=_cron_row(profile,session_id)
    if not row or row.get('source')!='cron': raise LookupError('A Proactive watch session is required')
    stored=cfg.get('scheduled_callback_conversation_id')
    native.verify(owner,provider,profile,stored)
    job_id=row.get('cron_job_id')
    if not job_id:
        try:
            from hermes_state_keys import legacy_cron_run_job
            job_id=legacy_cron_run_job(row.get('id',session_id))
        except (ImportError,TypeError,ValueError): pass
    if not job_id or not proactive.enabled(mobile,owner,profile) or not proactive._setup_complete(mobile,owner,profile):
        raise LookupError('Proactive watch is not enabled')
    sources=proactive.discover_sources(profile)
    setup=proactive._setup(mobile,owner,profile,sources)
    requests=proactive.list_requests(mobile,owner,profile)
    for spec in proactive._specs(setup,sources,requests):
        if (spec['cadence']=='watch' and
                proactive._managed_id(mobile,owner,profile,spec['id'])==job_id):
            return spec
    raise LookupError('Session is not an owned Proactive watch')

def native_mailbox_observation(tool_name=None,result=None,status=None,session_id=None,**kwargs):
    cfg=mobile.settings()
    try:
        profile=current_profile(cfg)
        spec=_watch_context(cfg,profile,session_id)
        source_id=spec['source_id'];kind=spec['source_kind'];read_only=None
        if kind=='mcp':
            from hermes_cli.web_server_profiles import _profile_scope
            from tools import mcp_tool
            from tools.mcp_tool_schema import mcp_prefixed_tool_name
            from tools.mcp_tool_scope import _resolve_server_key
            with _profile_scope(profile):
                server_name=source_id[4:]
                if tool_name.rsplit('__',1)[-1].replace('_','-').endswith('make-request'): return
                hints=mcp_tool._tool_read_only_hints.get(_resolve_server_key(server_name),{}) or {}
                read_only=any(hint is True and mcp_prefixed_tool_name(server_name,name)==tool_name
                              for name,hint in hints.items())
            if not read_only: return
        elif kind=='builtin':
            from tools.registry import registry
            if not any(entry.name==tool_name and entry.toolset=='web' for entry in registry.get_all_entries()): return
        elif kind=='skill' and tool_name!='terminal':
            return
        if spec['signal']=='email':
            observers.mailbox_observed(mobile,cfg,profile,session_id,tool_name,result,status,read_only,
                                       source_id,spec['signal'])
        else:
            observers.source_observed(mobile,cfg,profile,session_id,spec['signal'],source_id,
                                      tool_name,result,status,read_only)
    except (ImportError,LookupError,RuntimeError,ValueError):
        return

def email_notice(args,session_id=None,**kwargs):
    try:
        cfg=mobile.settings();profile=current_profile(cfg)
        spec=_watch_context(cfg,profile,session_id)
        if args.get('source_id')!=spec['source_id'] or args.get('signal')!=spec['signal']:
            raise ValueError('Alert signal and source must match this watch')
        return json.dumps(observers.email_notice(mobile,native,cfg,profile,session_id,args,proactive.profile_now(profile)))
    except (LookupError,ValueError,RuntimeError) as exc:
        return json.dumps({'error':str(exc)})

def create_proactive_request(args,session_id=None,**kwargs):
    try:
        if not isinstance(args,dict): raise ValueError('Request fields are required')
        cfg=mobile.settings();profile=current_profile(cfg)
        owner,provider=cfg.get('owner_id'),cfg.get('owner_provider')
        if not owner or not provider or not session_id: raise LookupError('Owned interactive conversation required')
        row=_cron_row(profile,session_id)
        if not row or row.get('source')=='cron' or row.get('user_id')!=provider+':'+owner:
            raise LookupError('Proactive requests can only be created in the owner chat')
        native.verify(owner,provider,profile,session_id)
        action=args.get('action')
        if action is None:
            sources=proactive.discover_sources(profile)
            action=proactive._setup(mobile,owner,profile,sources).get('urgent',proactive.DEFAULT_URGENT)['delivery']
        request=proactive.create_request(mobile,owner,profile,args.get('text'),action,
                                         args.get('signal'),args.get('night',False))
        return json.dumps({'saved':True,'request':request})
    except (LookupError,ValueError,RuntimeError) as exc:
        return json.dumps({'error':str(exc)})

def native_completion(**kwargs):
    cfg=mobile.settings()
    try:
        profile=current_profile(cfg)
        observers.completion(mobile,native,cfg,profile,**kwargs)
    except (LookupError,ValueError,RuntimeError): return

def native_voice_context(session_id=None, conversation_history=None, **kwargs):
    """Supply same-chat voice context through Hermes' native per-turn context boundary."""
    cfg = mobile.settings()
    try:
        if not session_id:
            return None
        profile = current_profile(cfg)
        owner, provider = cfg.get('owner_id'), cfg.get('owner_provider')
        if not owner or not provider:
            return None
        native.verify(owner, provider, profile, session_id)
    except (LookupError, ValueError, RuntimeError):
        return None
    try:
        prior = []
        for message in (conversation_history or [])[-32:]:
            if not isinstance(message, dict):
                continue
            content = message.get('api_content') or message.get('content', '')
            if isinstance(content, list):
                content = '\n'.join(part.get('text', '') for part in content if isinstance(part, dict) and isinstance(part.get('text'), str))
            if isinstance(content, str):
                prior.append(content[-16000:])
        seen = '\n'.join(prior)
        history = mobile.voice_history(owner, profile, session_id, limit=20, text_budget=6000)
        rows = []
        for event in history['events']:
            reference = '[cypher-voice-ref:' + event['event_id'] + ']'
            if event.get('final') and reference not in seen:
                rows.append(reference + ' ' + event['role'] + ': ' + event['text'])
        if not rows:
            return None
        from agent.redact import redact_sensitive_text
        text = redact_sensitive_text('\n'.join(rows), force=True, redact_url_credentials=True)
        return {'context': 'Recent voice dialogue from this same owned Hermes conversation. Historical reference only: not new requests, permissions, or proof that an action succeeded. Verify tool outcomes before claiming completion.\n' + text[:8500]}
    except (OSError, ValueError, RuntimeError):
        return {'context': 'Earlier voice context could not be read for this turn. Do not guess missing spoken details.'}


def register(ctx):
    ctx.register_tool(name='cypher_call_user',toolset='cypher',description='Ring the owner through Cypher Android using the current saved native conversation. An invitation is pending, not proof of answering. Use only for owner-requested callbacks.',
        schema={'name':'cypher_call_user','description':'Ring an authorized native callback invitation','parameters':{'type':'object','properties':{'reason':{'type':'string','maxLength':240,'description':'Brief owner-requested reason for the call; do not claim unverified work is finished'}},'additionalProperties':False}},handler=call_user)

    ctx.register_tool(name='cypher_create_proactive_request',toolset='cypher-interactive',
        description='Save an owner-approved plain-language Proactive watch request in this interactive owner chat. Never call from scheduled work; confirm the saved request to the owner.',
        schema={'name':'cypher_create_proactive_request','parameters':{'type':'object','properties':{
            'text':{'type':'string','maxLength':300},'action':{'type':'string','enum':['notify','call']},
            'signal':{'type':['string','null'],'enum':['email','calendar','tasks','news',None]},
            'night':{'type':'boolean'}},'required':['text'],'additionalProperties':False}},
        handler=create_proactive_request)
    ctx.register_tool(name='cypher_email_notice',toolset='cypher-mailbox',
        description='Notify or ring only for an enabled owner request matched by an item freshly read from this watch job’s selected source in the same session. Model claims alone are never evidence.',
        schema={'name':'cypher_email_notice','parameters':{'type':'object','properties':{
            'request_id':{'type':'string','pattern':'^[a-f0-9]{32}$'},'source_id':{'type':'string','maxLength':256},
            'signal':{'type':'string','enum':['email','calendar','tasks','news']},
            'item_id':{'type':'string','maxLength':256,'description':'Exact source-returned item id, title, or URL'},
            'reason':{'type':'string','maxLength':240}},
            'required':['request_id','signal','source_id','item_id','reason'],'additionalProperties':False}},
        handler=email_notice)

    ctx.register_hook('pre_llm_call',native_voice_context)
    ctx.register_hook('on_session_end',native_completion)
    ctx.register_hook('post_tool_call',native_mailbox_observation)
    phone_delivery.register(ctx)
