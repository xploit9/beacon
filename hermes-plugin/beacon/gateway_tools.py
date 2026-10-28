"""Call-scoped receipts around Hermes' MCP and native registry handlers; no model or proxy."""
import concurrent.futures
import contextvars
import hashlib
import inspect
import json
import logging
from pathlib import Path
import re
import secrets
import sys
import threading
import time
import uuid
from contextlib import closing, contextmanager

from fastapi import HTTPException

MAX_ARGUMENTS = 16384
MAX_RESULT = 24576
MAX_TOOLS = 12
MAX_CATALOGUE_BYTES = 3072
# Eight active calls at 128 receipts each; old closed or expired receipts make room.
MAX_RECEIPTS = 1024
MAX_CALL_ALLOWANCES = 32
MAX_ALLOWANCES = 1024
DEADLINE = 90
CATALOG_TTL = 15
BOOT = uuid.uuid4().hex
POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix='beacon-mcp')
SLOTS = threading.BoundedSemaphore(4)
CATALOG_LOCK = threading.RLock()
CATALOG_CACHE = {}
CATALOG_WARMING = set()
CATALOG_FIRST_WARM = {}  # profile -> this process's first warm-up, which runs MCP discovery
NATIVE_READ_ONLY = frozenset({'read_file','search_files','web_search','skills_list','skill_view','session_search'})
NATIVE_READ_ONLY_ACTIONS = {'cronjob_manage': frozenset({'list'}), 'process_manage': frozenset({'list'})}
# Hermes runs these in its agent loop, where their state lives; voice_context gives a call the same state.
INLINE_TOOLS = frozenset({'memory','session_search'})
CALL_EXCLUDED_TOOLS = frozenset({'manage_connections','manage_catalog','setup_mcp',
                                 'connector_search','connector_describe'})
NATIVE_EXCLUDED_TOOLS = frozenset({'clarify','delegate_task','todo_list',
                                   'beacon_create_proactive_request','beacon_event_notice','beacon_events_checked',
                                   'beacon_email_notice','beacon_email_checked'}) | CALL_EXCLUDED_TOOLS
NATIVE_EXCLUDED_TOOLSETS = frozenset({'browser','connectors','beacon-interactive','beacon-mailbox'})
QUERY_FILLER = set('a an the my me please could can you i would like want get read list find look pull show to for of in on and from up most latest recent current last today now one two three four five six ten tool tools'.split())
QUERY_ALIASES = {
    'email': {'email', 'mail', 'gmail', 'inbox', 'outlook'},
    'mail': {'email', 'mail', 'gmail', 'inbox', 'outlook'},
    'check': {'search', 'list'},
    'pull': {'search', 'list'},
    'web': {'web', 'internet', 'website', 'browser', 'scrapling'},
    'search': {'search', 'find', 'lookup', 'query'},
    'get': {'get', 'read', 'retrieve', 'fetch', 'list'},
    'remember': {'remember', 'memory', 'save', 'note'},
}
logger = logging.getLogger(__name__)


def query_words(text):
    words = set(re.findall(r'[^\W_]+', text.casefold()))
    expanded = set(words)
    for word in words:
        if len(word)>3 and word.endswith('s') and word not in {'news','status','access'}:
            expanded.add(word[:-1])
            if word.endswith('es'): expanded.add(word[:-2])
    return expanded


def discovery_rank(tool, words, query):
    name = tool['name'] + ' ' + tool.get('_raw', '')
    named, described = query_words(name), query_words(tool['description'])
    coverage = weight = 0
    for word in words:
        variants = query_words(word)
        aliases = variants | set().union(*(QUERY_ALIASES.get(term, set()) for term in variants))
        if aliases & (named | described): coverage += 1
        name_weight = 4 if variants & {'search','get','list','read','fetch','send','create','update','delete','draft'} else 8
        weight += name_weight * bool(aliases & named) + bool(aliases & described)
    if not coverage: return (0, 0, 0)
    intent = query_words(query)
    writing = bool(intent & {'send','draft','create','delete','remove','modify','update','write'})
    if not writing and tool['read_only']:
        weight += 2
        if intent & {'email','mail','gmail','inbox'}:
            if named & {'search','list'}: weight += 2
            if named & {'message','thread','inbox'}: weight += 1
    return (int(query.casefold() in (tool['name'].casefold(), tool.get('_raw', '').casefold())), coverage, weight)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def outcome(value, read_only, depth=0, own=False):
    """Recognize native errors and JSON-encoded MCP envelopes before truncation."""
    if depth>4: return 'completed'
    if isinstance(value,str):
        try: value=json.loads(value)
        except ValueError: return 'completed'
    if not isinstance(value,dict): return 'completed'
    if value.get('outcome_uncertain') is True: return 'uncertain'
    # A call's own refusal (own=True) happened before anything ran, so it is a certain failure even for a write.
    if own and depth==0 and value.get('not_started') is True: return 'failed'
    if value.get('error') not in (None,False,'',{},[]) or value.get('isError') is True or value.get('is_error') is True:
        return 'failed' if read_only else 'uncertain'
    for key in ('status', 'status_code', 'http_status'):
        code=value.get(key)
        if (type(code) is int or isinstance(code,str) and re.fullmatch(r'[45][0-9]{2}',code)) and 400<=int(code)<=599:
            return 'failed' if read_only else 'uncertain'
    for key in ('result','structuredContent','structured_content'):
        if key in value:
            state=outcome(value[key],read_only,depth+1)
            if state!='completed': return state
    for block in value.get('content',[]) if isinstance(value.get('content'),list) else []:
        if isinstance(block,dict) and block.get('type')=='text':
            state=outcome(block.get('text',''),read_only,depth+1)
            if state!='completed': return state
    return 'completed'


def safe(value, limit):
    from agent.redact import redact_sensitive_text
    return redact_sensitive_text(str(value), force=True, redact_url_credentials=True)[:limit]


def coerce(schema, value):
    """Realtime voice models often send numbers and booleans as strings ("2", "true"); match the declared scalar type
    before strict validation. Anything ambiguous is left as sent, so validation still decides."""
    if not isinstance(schema, dict): return value
    kind = schema.get('type')
    kinds = set(kind) if isinstance(kind, list) else {kind}
    if isinstance(value, dict) and isinstance(schema.get('properties'), dict):
        return {key: coerce(schema['properties'].get(key), item) for key, item in value.items()}
    if isinstance(value, list) and isinstance(schema.get('items'), dict):
        return [coerce(schema['items'], item) for item in value]
    if isinstance(value, str) and 'string' not in kinds:
        text = value.strip()
        if 'integer' in kinds and re.fullmatch(r'[+-]?\d{1,15}', text): return int(text)
        if 'number' in kinds and re.fullmatch(r'[+-]?(\d{1,15}(\.\d{0,12})?|\.\d{1,12})', text): return float(text)
        if 'boolean' in kinds and text.casefold() in ('true', 'false'): return text.casefold() == 'true'
    if isinstance(value, float) and 'integer' in kinds and 'number' not in kinds and value.is_integer(): return int(value)
    return value


def validate(schema, arguments):
    from jsonschema import validators
    if not isinstance(arguments, dict):
        raise HTTPException(400, 'Tool arguments must be an object')
    try:
        if len(encoded(arguments).encode()) > MAX_ARGUMENTS:
            raise HTTPException(413, 'Tool arguments exceed the limit')
        nodes=[arguments]
        while nodes:
            node=nodes.pop()
            if isinstance(node,dict):
                if any(node.get(key) is not None for key in ('cdp_url','executable_path','proxy','proxy_auth')):
                    raise HTTPException(400,'Connection overrides are unavailable in voice tools')
                nodes.extend(node.values())
            elif isinstance(node,list): nodes.extend(node)
        schema=json.loads(encoded(schema))
        # Remote schemas cannot cause validator network retrieval.
        def refs(node):
            if isinstance(node, dict):
                if any(key in node and not str(node[key]).startswith('#') for key in ('$ref','$dynamicRef','$recursiveRef')):
                    raise HTTPException(409, 'Tool schema has an external reference')
                if node.get('nullable') is True and isinstance(node.get('type'),str):
                    node['type']=[node['type'],'null']
                for value in node.values(): refs(value)
            elif isinstance(node, list):
                for value in node: refs(value)
        refs(schema)
        checker = validators.validator_for(schema)
        checker.check_schema(schema)
        checker(schema).validate(arguments)
    except HTTPException:
        raise
    except Exception:
        raise HTTPException(400, 'Arguments do not match the configured tool schema') from None


def readonly_methods(schema):
    properties=schema.get('properties',{})
    method=properties.get('method') if isinstance(properties,dict) else None
    methods=method.get('enum') if isinstance(method,dict) else None
    return tuple(methods) if isinstance(methods,list) and methods and all(value in ('GET','HEAD') for value in methods) else ()


REQUEST_METHOD_FIELDS=('method','http_method')
REQUEST_BODY_FIELDS=('body','data','payload','json')
READ_METHODS={'GET','HEAD'}


def reads_by_method(args,schema):
    """A tool that takes an HTTP method or a request body reads only as GET/HEAD, whatever its server annotates.

    Shared by the call gateway and the Proactive read-only guard; schema is the tool's {'parameters': ...}."""
    given=[(str(key).casefold(),value) for key,value in args.items()]
    if any(key in REQUEST_BODY_FIELDS and value not in (None,'',{},[]) for key,value in given): return False
    methods=[value for key,value in given if key in REQUEST_METHOD_FIELDS and value is not None]
    if methods: return all(isinstance(method,str) and method.strip().upper() in READ_METHODS for method in methods)
    parameters=schema.get('parameters') if isinstance(schema,dict) else None
    properties=parameters.get('properties') if isinstance(parameters,dict) else None
    for field in REQUEST_METHOD_FIELDS:
        spec=properties.get(field) if isinstance(properties,dict) else None
        if spec is None: continue
        allowed=spec.get('enum') if isinstance(spec,dict) else None
        # An omitted method falls back to the server's default, which may write.
        if not (isinstance(allowed,list) and allowed and all(isinstance(item,str) and item.upper() in READ_METHODS for item in allowed)): return False
    return True


# The one write/read vocabulary of the call gateway and the Proactive read-only guard (the agent loads this file by path,
# as for reads_by_method).
WRITE_WORDS = frozenset({
    'send','create','delete','remove','modify','update','manage','draft','reply','forward',
    'move','archive','trash','label','post','write','upload','accept','decline','invite',
    'share','set','add','patch','put','mark','star','snooze','mute','block',
    'unsubscribe','replace','rename','merge','approve','submit','cancel','execute','run',
    'import','restore','copy','insert','append','apply','push','commit','pay','transfer',
    'toggle','save','store','notify','acknowledge','ack','seen','flag','unflag','pin','unpin',
    'unstar','download','export','attach','respond','rsvp','confirm','dismiss','clear','reset',
    'assign','subscribe','react','sync','publish','change','hide','unhide','purge','tag','untag',
    'categorize','report','unmark','untrash','unarchive','unlabel','unmute','unsnooze','unblock',
    'empty','reschedule','revoke','trigger','install','edit','enable','disable','pause','resume','schedule',
    'message','ping','alert','comment','escalate','triage','process','truncate','rotate','suspend','terminate',
    'drop','rebuild','reindex','kill','restart','reboot','rollback','revert','resend','ban'})
# 'and', 'or' and 'then' chain a second action; they are not WRITE_WORDS because a read action may say "list or search".
UNSAFE_NAME_WORDS = WRITE_WORDS | frozenset({
    'exec','sql','call','invoke','eval','shell','command','script','http','request',
    'and','or','then','book','reserve','purchase','buy','checkout','complete','finish',
    'resolve','deliver','dispatch','erase','discard','destroy','wipe','grant','refund',
    'redeem','settle','enrol','enroll','register','unregister','upgrade','downgrade',
    'unfollow','unlink','unlock','detach','disconnect','connect','topup','claim','vote',
    'join','leave','close','switch','begin','boost'})
# query, fetch and check also name SQL runners and URL fetchers, so they do not count as reads by name alone.
READ_NAME_WORDS = frozenset({'get','list','search','read','find','view','show','describe','lookup'})
# Words of the lists above that are also plain nouns: after a leading read verb they name a thing, not an action
# (get_pull_request, get_commit, get_workflow_run, get_message). The added verbs ping, escalate, triage, truncate, rotate,
# suspend and terminate are not nouns, so they write even after a read verb.
NOUN_NAME_WORDS = frozenset({'request','call','commit','run','label','report','draft','tag','change','schedule',
                             'message','alert','comment','process'})
ACTION_NAME_WORDS = UNSAFE_NAME_WORDS - NOUN_NAME_WORDS


def name_words(text):
    return re.findall(r'[a-z0-9]+', re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', text or '').casefold())


def name_writes(raw):
    """A tool name that carries a write. After a leading read verb only a real action counts: get_pull_request reads,
    get_and_delete_x and get_or_call_user write ('and', 'or' and 'then' chain a second action)."""
    words=name_words(raw)
    return bool(set(words)&(ACTION_NAME_WORDS if words and words[0] in READ_NAME_WORDS else UNSAFE_NAME_WORDS))


def mcp_reads_by_name(raw, trust):
    """A read-only annotation is the server's own claim: it releases the owner's approval only for a name with no write
    action, and on an untrusted server only for a name that also says it reads, with every noun counted as a write."""
    if trust!='untrusted': return not name_writes(raw)
    words=set(name_words(raw))
    return not words&UNSAFE_NAME_WORDS and bool(words&READ_NAME_WORDS)


def writes_by_action(arguments):
    """An action or operation argument that names a write makes the call a write, whatever the tool is annotated."""
    given={str(key).casefold():value for key,value in arguments.items()}
    return any(isinstance(given.get(field),str) and bool(set(name_words(given[field]))&WRITE_WORDS) for field in ('action','operation'))


def _hermes_read_only(name):
    return name in NATIVE_READ_ONLY


def native_catalog():
    """Build enabled non-MCP tools from Hermes' profile-filtered registry definitions."""
    from agent.skill_utils import parse_config_string_list
    from hermes_cli.config import load_config_readonly
    from hermes_cli.tools_config import _get_platform_tools
    from model_tools import get_tool_definitions
    from tools.registry import registry

    config=load_config_readonly()
    agent_config=config.get('agent') if isinstance(config.get('agent'),dict) else {}
    disabled=parse_config_string_list(agent_config.get('disabled_toolsets')) or None
    enabled=sorted(_get_platform_tools(config,'api_server'))
    config_digest=digest(config)
    output=[]
    definitions=get_tool_definitions(enabled_toolsets=enabled,disabled_toolsets=disabled,quiet_mode=True,
                                     skip_tool_search_assembly=True)
    enabled_tools=[item['function']['name'] for item in definitions if isinstance(item.get('function'),dict)
                   and isinstance(item['function'].get('name'),str)]
    for definition in definitions:
        function=definition.get('function') if isinstance(definition,dict) else None
        name=function.get('name') if isinstance(function,dict) else None
        entry=registry.get_entry(name) if isinstance(name,str) else None
        if not entry: continue
        toolset=entry.toolset
        if (toolset.startswith('mcp-') or toolset in NATIVE_EXCLUDED_TOOLSETS or
                name in NATIVE_EXCLUDED_TOOLS or name.startswith('browser_')):
            continue
        schema=json.loads(encoded(function.get('parameters',{})))
        # A call searches its own profile's history only, like the app's History.
        if name=='session_search' and isinstance(schema.get('properties'),dict): schema['properties'].pop('profile',None)
        read_only=_hermes_read_only(name)
        row={'tool_id':digest(['hermes-native',registry.current_scope_key(),registry._generation,
                               config_digest,name,toolset,schema,read_only]),
             'name':name,'description':safe(function.get('description',''),1200),
             'parameters':schema,'read_only':read_only,'_kind':'hermes','_raw':name,
             '_toolset':toolset,'_enabled_tools':enabled_tools}
        if name in INLINE_TOOLS: row['_inline']=name
        output.append(row)
    # The memory provider's own tools reach only agent turns in Hermes; a call gets them under the same memory gate.
    voice=sys.modules.get('beacon_voice_context')
    if voice is not None and 'memory' in enabled and 'memory' not in (disabled or ()):
        try: offered=voice.provider_tool_schemas(config)
        except Exception: offered=[]  # The provider's tools are an extra; the rest of the catalogue stands.
        taken={row['name'] for row in output}
        for provided in offered:
            name=provided['name']
            if name in taken: continue
            parameters=json.loads(encoded(provided.get('parameters') or {'type':'object','properties':{}}))
            output.append({'tool_id':digest(['hermes-memory-provider',registry.current_scope_key(),registry._generation,
                                             config_digest,name,parameters]),
                           'name':name,'description':safe(provided.get('description',''),1200),
                           'parameters':parameters,'read_only':False,'_kind':'hermes','_raw':name,
                           '_toolset':'memory','_inline':'provider','_enabled_tools':enabled_tools})
    return output


def inline_tool(tool, arguments, scope, session_id):
    """Run a memory or session tool through Hermes' own handler with the call's chat state; scope is the call's authority."""
    voice=sys.modules.get('beacon_voice_context')
    if voice is None: raise RuntimeError('Call memory is unavailable')
    from hermes_constants import get_hermes_home
    return voice.call_inline_tool(tool['_inline'],tool['_raw'],arguments,scope,session_id,get_hermes_home())


def tool_read_only(tool, arguments):
    if tool.get('read_only'): return reads_by_method(arguments,{'parameters':tool.get('parameters')}) and not writes_by_action(arguments)
    if tool.get('_kind')!='hermes': return False
    name=tool.get('_raw')
    return arguments.get('action') in NATIVE_READ_ONLY_ACTIONS.get(name,())


class NativeMcp:
    """Direct MCP and Hermes registry tool catalog and dispatch for the active profile."""
    def scoped_metadata(self, profile, fn):
        from tui_gateway import server
        root=server._profile_home(profile)
        # Metadata needs the profile binding, not external secret commands and their global lock.
        with server._session_profile_runtime_scope({'profile_home':str(root) if root else None}, hydrate_secrets=False):
            return fn(Path(root) if root else Path(server._hermes_home))

    def approval_source(self, tool, arguments, scope, session_id, root):
        from hermes_cli.config import load_config_readonly
        from hermes_cli.web_server_sessions import _open_session_db_at_path
        from tools.approval_context import _normalize_approval_mode
        with closing(_open_session_db_at_path(Path(root)/'state.db',read_only=True)) as db:
            row=db.get_session(session_id)
        if not row or row.get('user_id')!=scope[1]+':'+scope[0]: return None
        # Hermes runs its memory tool and the memory provider's tools without asking in every approval mode; a call does too.
        if tool.get('_kind')=='hermes' and tool.get('_inline') in ('memory','provider'): return 'memory'
        config=row.get('model_config') or {}
        if isinstance(config,str): config=json.loads(config)
        if not isinstance(config,dict): return None
        yolo=config.get('yolo_mode',False)
        if type(yolo) is not bool: return None
        if yolo: return 'chat_yolo'
        approvals=load_config_readonly().get('approvals') or {}
        if not isinstance(approvals,dict): return None
        mode=_normalize_approval_mode(approvals.get('mode','manual'))
        if mode=='off': return 'profile_off'
        # Hermes' guardian evaluates shell commands; arbitrary MCP/file arguments are not shell input.
        if mode=='smart' and tool.get('_kind')=='hermes' and tool.get('_raw')=='terminal':
            command=arguments.get('command')
            if not isinstance(command,str): return None
            from gateway import session_context
            from tools.approval_smart import _smart_verdict
            key='beacon-call/'+digest(scope)
            tokens=session_context.set_session_vars(platform='api_server',source='beacon',
                user_id=scope[1]+':'+scope[0],profile=scope[2],session_key=key,session_id=session_id)
            try:
                pattern='beacon-call:terminal'
                if _smart_verdict(command,'Call terminal action',pattern,[pattern],key)=='approve': return 'smart'
            finally: session_context.clear_session_vars(tokens)
        return None

    def cache_key(self):
        try:
            from hermes_cli.config import load_config_readonly
            from tools.mcp_tool_config import _load_mcp_config
            from tools.registry import registry
            return (registry.current_scope_key(),registry._generation,
                    digest(load_config_readonly()),digest(_load_mcp_config()))
        except Exception:
            return None

    def catalog(self, discover=True):
        from tools import mcp_tool as core
        from tools.mcp_tool_config import _load_mcp_config
        from tools.mcp_tool_common import mcp_server_enabled
        from tools.mcp_tool_discovery import discover_mcp_tools
        from tools.mcp_tool_registration import _same_server_route, _make_tool_filter, _normalize_server_trust
        from tools.mcp_tool_scope import _resolve_server_key
        from tools.mcp_tool_handlers import _tool_is_read_only
        from tools.registry import registry
        # Native discovery loads only operator config; the caller supplies no connection address.
        if discover: discover_mcp_tools()
        config = _load_mcp_config()
        output = []
        for entry in registry.get_all_entries():
            if not entry.toolset.startswith('mcp-'):
                continue
            closure = inspect.getclosurevars(entry.handler).nonlocals
            server, raw = closure.get('server_name'), closure.get('tool_name')
            cfg = config.get(server)
            if not server or not raw or not cfg or not mcp_server_enabled(cfg):
                continue  # generated resources/prompts are not native tools/call handles
            if not _make_tool_filter(server, cfg)(raw):
                continue
            key = _resolve_server_key(server)
            live = core._servers.get(key)
            cached = core._lazy_server_configs.get(key)
            if live is not None:
                if not _same_server_route(live, cfg, cross_profile=False): continue
            elif cached != cfg:
                continue
            schema = json.loads(encoded(entry.schema.get('parameters', {})))
            generic_http=raw.replace('-', '_').endswith('make_request')
            properties=schema.get('properties',{})
            method=properties.get('method') if isinstance(properties,dict) else None
            method_read_only=bool(readonly_methods(schema))
            if generic_http:
                # Native recovery trusts the provider's erroneous read-only hint; prohibit writes.
                if not isinstance(method,dict): continue
                method['enum']=['GET']
                method['default']='GET'
                method_read_only=True
            # Generic HTTP request tools can POST despite a provider's read-only annotation.
            has_method=isinstance(properties,dict) and 'method' in properties
            trust=_normalize_server_trust(cfg.get('trust'))
            readonly = (_tool_is_read_only(server, raw) and mcp_reads_by_name(raw, trust) and not generic_http
                        and not (has_method and not method_read_only))
            tool_timeout=float(closure.get('tool_timeout') or 60)
            version = digest([registry.current_scope_key(), server, raw, cfg, schema, readonly])
            output.append({'tool_id':version, 'name':entry.name,
                           'description':safe(entry.schema.get('description', ''), 1200),
                           'parameters':schema, 'read_only':readonly,
                           '_timeout':tool_timeout,
                           '_server':server, '_raw':raw,
                           '_trust':trust})
        output.extend(native_catalog())
        return output

    def execute(self, tool, arguments, scope, approved, session_id=None):
        from gateway import session_context
        session_id=session_id or scope[3]
        key = 'beacon-tool/' + uuid.uuid4().hex
        # Reuse Hermes' environment within this call; approval callbacks still get a unique one-shot key.
        session_key='beacon-call/'+digest(scope)
        session_tokens = session_context.set_session_vars(platform='api_server', source='beacon',
            user_id=scope[1]+':'+scope[0], profile=scope[2], session_key=session_key, session_id=session_id)
        if tool.get('_kind')=='hermes':
            terminal=tool.get('_raw')=='terminal'
            registered=False
            token=None
            try:
                if not tool.get('read_only') and not approved:
                    raise HTTPException(403,'Hermes tool requires owner approval')
                if tool.get('_inline'): return inline_tool(tool,arguments,scope,session_id)
                if terminal:
                    # Only the exact authorized command can satisfy Hermes' one-shot guard.
                    from tools import approval, approval_context
                    token=approval_context.set_current_session_key(key)
                    used=False
                    once_lock=threading.Lock()
                    def notify(data):
                        nonlocal used
                        request=data.get('request_id') if isinstance(data,dict) else None
                        if not isinstance(request,str) or not re.fullmatch(r'[a-f0-9]{32}',request):
                            raise ValueError('Invalid native approval request identity')
                        with once_lock:
                            accept=(approved and not used and bool(data.get('pattern_key')) and
                                    data.get('command')==arguments.get('command'))
                            used=True
                        approval.resolve_gateway_approval(key,'once' if accept else 'deny',request_id=request)
                    approval.register_gateway_notify(key,notify);registered=True
                from model_tools import handle_function_call
                # Keep Hermes hooks, middleware, and terminal safety guards in the call path.
                return handle_function_call(tool['_raw'],arguments,task_id=scope[3],session_id=session_id,
                                            enabled_tools=tool.get('_enabled_tools'))
            finally:
                if registered: approval.unregister_gateway_notify(key)
                if token is not None: approval_context.reset_current_session_key(token)
                session_context.clear_session_vars(session_tokens)

        from tools import approval, approval_context
        token = approval_context.set_current_session_key(key)
        # This native gate represents the SAME approved operation, not another grant.
        expected = (f"MCP tool '{tool['_raw']}' on UNTRUSTED server '{tool['_server']}' wants to run. "
                    'This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.')
        used = False
        native_consent_required = False
        once_lock=threading.Lock()
        def notify(data):
            nonlocal used, native_consent_required
            request=data.get('request_id') if isinstance(data,dict) else None
            if not isinstance(request,str) or not re.fullmatch(r'[a-f0-9]{32}',request):
                # Raising makes the native notify path fail closed immediately, without a wait.
                raise ValueError('Invalid native approval request identity')
            with once_lock:
                accept = (approved and not used and tool['_trust']=='untrusted'
                          and data.get('pattern_key')=='mcp_elicitation' and data.get('command')==expected)
                if approved and data.get('pattern_key')=='mcp_elicitation' and data.get('command')!=expected:
                    native_consent_required = True
                used = True
            approval.resolve_gateway_approval(key, 'once' if accept else 'deny', request_id=request)
        approval.register_gateway_notify(key, notify)
        try:
            from model_tools import handle_function_call
            result = handle_function_call(tool['name'],arguments,task_id=scope[3],session_id=session_id,
                                          enabled_tools=tool.get('_enabled_tools'))
            if native_consent_required:
                return encoded({'error':'Hermes needs this approval. Approve the action in Hermes.'})
            return result
        finally:
            approval.unregister_gateway_notify(key)
            approval_context.reset_current_session_key(token)
            session_context.clear_session_vars(session_tokens)


class Gateway:
    def __init__(self, mobile, scoped, backend=None, sanitizer=safe):
        self.mobile = mobile
        self.backend = backend or NativeMcp()
        self.scoped = scoped
        self.execution_scoped = scoped
        self.sanitize = sanitizer

    @contextmanager
    def db(self):
        with self.mobile.LOCK, self.mobile.connect() as conn:
            conn.execute('''CREATE TABLE IF NOT EXISTS tool_receipts (
                owner TEXT, provider TEXT, profile TEXT, call_id TEXT, request_id TEXT,
                signature TEXT NOT NULL, payload TEXT NOT NULL, updated REAL NOT NULL,
                PRIMARY KEY(owner,provider,profile,call_id,request_id))''')
            conn.execute('''CREATE TABLE IF NOT EXISTS call_tool_allowances (
                owner TEXT, provider TEXT, profile TEXT, call_id TEXT, tool_id TEXT, tool_name TEXT,
                created REAL NOT NULL, PRIMARY KEY(owner,provider,profile,call_id,tool_name))''')
            yield conn

    def _catalog_key(self, profile):
        key_fn=getattr(self.backend,'cache_key',None)
        if not callable(key_fn): return None
        try: value=self.scoped(profile,lambda _:key_fn())
        except Exception: return None
        return (profile,value) if value is not None else None

    def _cached_catalog(self, profile):
        key=self._catalog_key(profile)
        if key is None: return None
        now=time.monotonic()
        with CATALOG_LOCK:
            cached=CATALOG_CACHE.get(key)
            if cached and now-cached[0]<CATALOG_TTL: return cached[1]
            if cached: CATALOG_CACHE.pop(key,None)
        return None

    def _store_catalog(self, profile, rows):
        key=self._catalog_key(profile)
        if key is None: return
        now=time.monotonic()
        with CATALOG_LOCK:
            for old,(created,_) in list(CATALOG_CACHE.items()):
                if now-created>=CATALOG_TTL: CATALOG_CACHE.pop(old,None)
            if key not in CATALOG_CACHE and len(CATALOG_CACHE)>=16:
                CATALOG_CACHE.pop(next(iter(CATALOG_CACHE)))
            CATALOG_CACHE[key]=(now,rows)

    @staticmethod
    def _call_tools(rows):
        return [tool for tool in rows if isinstance(tool,dict)
                and tool.get('name') not in CALL_EXCLUDED_TOOLS
                and tool.get('_raw') not in CALL_EXCLUDED_TOOLS]

    def catalog(self, profile, discover=True):
        cached=self._cached_catalog(profile)
        if cached is not None: return self._call_tools(cached)
        rows=self.scoped(profile,lambda _:self.backend.catalog() if discover else self.backend.catalog(discover=False))
        rows=self._call_tools(rows)
        if discover: self._store_catalog(profile,rows)
        return rows

    def warm_catalogue(self, profile):
        key=self._catalog_key(profile)
        if key is None: return False
        now=time.monotonic()
        with CATALOG_LOCK:
            cached=CATALOG_CACHE.get(key)
            if cached and now-cached[0]<CATALOG_TTL: return False
            CATALOG_CACHE.pop(key,None)
            if key in CATALOG_WARMING: return False
            CATALOG_WARMING.add(key)

        def refresh():
            try: self.catalog(profile)
            except Exception: pass
            finally:
                with CATALOG_LOCK: CATALOG_WARMING.discard(key)

        thread=threading.Thread(target=refresh,name='beacon-catalog-warm',daemon=True)
        try: thread.start()
        except Exception:
            with CATALOG_LOCK: CATALOG_WARMING.discard(key)
            return False
        with CATALOG_LOCK: CATALOG_FIRST_WARM.setdefault(profile,thread)
        return True

    def compact_catalogue(self, profile):
        groups={}
        for tool in sorted(self.catalog(profile,discover=False),key=lambda item:str(item.get('name',''))):
            if not tool.get('read_only'): continue
            source=tool.get('_server') or tool.get('_toolset') or 'Hermes'
            source=' '.join(str(source).split())[:80]
            corpus=' '.join(str(tool.get(key) or '') for key in ('name','_raw','description')).casefold()
            if 'google_workspace' in corpus or 'google workspace' in corpus: label='Google Workspace'
            elif 'gmail' in corpus or 'email' in corpus or 'mail' in corpus: label='Gmail'
            else: label=re.sub(r'[_-]+',' ',source).title()
            description=safe(tool.get('description',''),1200)
            description=re.sub(r'\s+',' ',''.join(c for c in description if c.isprintable())).strip()
            if not description:
                description=re.sub(r'[_-]+',' ',str(tool.get('_raw') or tool.get('name') or '')).strip()
            rows=groups.setdefault(source,[label,[]])[1]
            if description and description not in rows and len(rows)<3:
                rows.append(description[:72])
        lines=[];size=0
        for label,rows in sorted(groups.values(),key=lambda value:value[0].casefold()):
            if not rows: continue
            line=label+': '+'; '.join(rows)
            line_bytes=len(line.encode('utf-8'))+1
            if size+line_bytes>MAX_CATALOGUE_BYTES: break
            lines.append(line);size+=line_bytes
        return '\n'.join(lines)

    def discover(self, profile, query=''):
        if not isinstance(query, str) or len(query)>240: raise HTTPException(400, 'Invalid tool query')
        self.warm_catalogue(profile)
        # Right after a restart MCP tools are still being discovered; wait once (bounded) so mail tools are not missed.
        first=CATALOG_FIRST_WARM.get(profile)
        if first is not None and first is not threading.current_thread(): first.join(30)
        words = set(re.findall(r'[^\W_]+', query.casefold())) - QUERY_FILLER
        words = {word for word in words if not word.isdecimal()}
        rows = self.catalog(profile,discover=False)
        # Task details ("latest five emails") need not occur verbatim in a tool's schema.
        ranked = [(discovery_rank(tool, words, query), tool) for tool in rows] if words else []
        selected = [tool for score,tool in sorted(ranked, key=lambda row:row[0], reverse=True) if score[1]] if words else rows
        result, size = [], 0
        for tool in selected:
            public = {k:v for k,v in tool.items() if not k.startswith('_')}
            # Schema output is also untrusted and bounded; never leak connection config.
            text = self.sanitize(encoded(public), MAX_RESULT)
            if text != encoded(public):
                try: public = json.loads(text)
                except ValueError: continue
            count = len(encoded(public).encode())
            if count>MAX_RESULT or size+count>MAX_RESULT: continue
            result.append(public); size += count
            if len(result)==MAX_TOOLS: break
        return {'tools':result, 'total_matches':len(selected), 'truncated':len(result)<len(selected)}

    def _row(self, conn, scope, request):
        return conn.execute('SELECT * FROM tool_receipts WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?', (*scope,request)).fetchone()

    def _save(self, conn, scope, request, value):
        conn.execute('UPDATE tool_receipts SET payload=?,updated=? WHERE owner=? AND provider=? AND profile=? AND call_id=? AND request_id=?',
                     (encoded(value),time.time(),*scope,request))

    def status(self, scope, request):
        try: self.mobile.ident(request)
        except ValueError: raise HTTPException(400,'Invalid tool request identity') from None
        with self.db() as conn:
            row = self._row(conn,scope,request)
            if not row: raise HTTPException(404, 'Tool request unavailable')
            value = json.loads(row['payload'])
            value.setdefault('approved_by',None)
            if value['status']=='running' and (value.get('_boot')!=BOOT or
                    time.time()-value['_started']>value.get('_deadline_seconds',DEADLINE)):
                value.update(status='uncertain', reason='Execution may have completed; do not retry automatically')
                self._save(conn,scope,request,value)
            if value['status']=='needs_approval' and time.time()>value['_expires']:
                value.update(status='expired', reason='Approval proposal expired')
                self._save(conn,scope,request,value)
            if value['status']=='ready' and time.time()-row['updated']>DEADLINE:
                value.update(status='failed',reason='Admission interrupted before execution; operation was not started')
                self._save(conn,scope,request,value)
        return {k:v for k,v in value.items() if not k.startswith('_')}

    def _approval_source(self, scope, tool, arguments, session_id):
        # A call allowance never covers Hermes' own tools: it would also answer their dangerous-command prompts.
        # It binds to the approved definition's id: a republished definition under the same name is shown for approval again.
        if tool.get('_kind')!='hermes':
            with self.db() as conn:
                if conn.execute('''SELECT 1 FROM call_tool_allowances
                    WHERE owner=? AND provider=? AND profile=? AND call_id=? AND tool_id=?''',
                    (*scope,tool['tool_id'])).fetchone(): return 'call_allowance'
        policy=getattr(self.backend,'approval_source',None)
        if not callable(policy): return None
        try:
            result=self.scoped(scope[2],lambda root:policy(tool,arguments,scope,session_id,root))
            return result if result in ('chat_yolo','profile_off','smart','memory') else None
        except Exception:
            return None  # Missing/malformed state or a failed guardian always asks the phone.

    def invoke(self, scope, value, session_id=None):
        if set(value)!={'request_id','tool_id','arguments'}: raise HTTPException(400, 'Invalid tool request fields')
        try: request=self.mobile.ident(value['request_id'])
        except ValueError: raise HTTPException(400,'Invalid tool request identity') from None
        if not isinstance(value['tool_id'],str) or len(value['tool_id'])!=64: raise HTTPException(400, 'Invalid tool handle')
        try: signature=digest(value)
        except (ValueError,TypeError): raise HTTPException(400,'Invalid tool request') from None
        with self.db() as conn:
            previous=self._row(conn,scope,request)
            if previous:
                if previous['signature']!=signature: raise HTTPException(409, 'Request identity conflicts with its original arguments')
                return self.status(scope,request)
        tools=self.catalog(scope[2],discover=False);tool=next((t for t in tools if t['tool_id']==value['tool_id']),None)
        if not tool: raise HTTPException(409, 'Tool changed or is no longer configured; rediscover it')
        arguments=coerce(tool['parameters'], value['arguments'])
        validate(tool['parameters'], arguments)
        methods=readonly_methods(tool['parameters'])
        if 'method' not in arguments and methods:
            arguments={**arguments,'method':'GET' if 'GET' in methods else 'HEAD'}
            validate(tool['parameters'],arguments)
        read_only=tool_read_only(tool,arguments)
        tool={**tool,'read_only':read_only}
        session_id=session_id or scope[3]
        approved_by=None if read_only else self._approval_source(scope,tool,arguments,session_id)
        needs_approval=not read_only and approved_by is None
        result={'request_id':request,'tool_id':tool['tool_id'],'tool_name':tool['name'],
                'status':'needs_approval' if needs_approval else 'ready','approved_by':approved_by,
                '_arguments':arguments,'_read_only':read_only,'_session_id':session_id,
                '_expires':time.time()+120}
        if '_timeout' in tool:
            result['_deadline_seconds']=max(DEADLINE,float(tool['_timeout'])+30)
        if needs_approval:
            serialized=encoded(arguments)
            try:
                displayed=json.loads(self.sanitize(serialized,MAX_ARGUMENTS))
                if encoded(displayed)!=serialized:
                    raise ValueError('Approval arguments were changed by redaction')
            except (ValueError,TypeError):
                raise HTTPException(400,'These arguments cannot be shown accurately for approval; remove sensitive values') from None
            result['proposal']={'id':secrets.token_urlsafe(32),'tool_name':tool['name'],'arguments':displayed,
                                **({'allow_call':False} if tool.get('_kind')=='hermes' else {})}
        with self.db() as conn:
            previous=self._row(conn,scope,request)
            if previous:
                if previous['signature']!=signature: raise HTTPException(409,'Request identity conflicts')
                return self.status(scope,request)
            if conn.execute('SELECT COUNT(*) FROM tool_receipts WHERE owner=? AND profile=? AND call_id=?', (scope[0],scope[2],scope[3])).fetchone()[0]>=128:
                raise HTTPException(429,'Call tool request capacity reached')
            total=conn.execute('SELECT COUNT(*) FROM tool_receipts').fetchone()[0]
            if total>=MAX_RECEIPTS:
                expired=conn.execute('''SELECT r.rowid FROM tool_receipts r
                    LEFT JOIN calls c ON c.id=r.call_id AND c.owner=r.owner AND c.profile=r.profile
                    WHERE (c.id IS NULL OR c.closed=1 OR c.created<?)
                    AND json_extract(r.payload,'$.status')!='running'
                    ORDER BY r.updated LIMIT ?''',(time.time()-4*3600,total-MAX_RECEIPTS+1)).fetchall()
                conn.executemany('DELETE FROM tool_receipts WHERE rowid=?',expired)
                remaining=conn.execute('SELECT COUNT(*) FROM tool_receipts').fetchone()[0]
                if remaining>=MAX_RECEIPTS:
                    if expired: conn.commit()
                    raise HTTPException(429,'Tool receipt capacity reached')
            conn.execute('INSERT INTO tool_receipts VALUES(?,?,?,?,?,?,?,?)',(*scope,request,signature,encoded(result),time.time()))
        if not needs_approval: self._start(scope,request,tool,False)
        return self.status(scope,request)

    def decide(self, scope, request, value):
        if set(value)!={'proposal_id','decision'} or value['decision'] not in ('approve','approve_call','deny'):
            raise HTTPException(400,'Invalid approval decision')
        current=self.status(scope,request)
        if not current.get('proposal') or not isinstance(value['proposal_id'],str) or not value['proposal_id'].isascii() or not secrets.compare_digest(current['proposal']['id'],value['proposal_id']):
            raise HTTPException(409,'Approval proposal mismatch')
        if current['status']!='needs_approval': return current
        if value['decision']=='deny':
            with self.db() as conn:
                row=self._row(conn,scope,request);stored=json.loads(row['payload'])
                if stored['status']=='needs_approval':
                    stored.update(status='denied',reason='Owner denied this operation');self._save(conn,scope,request,stored)
            return self.status(scope,request)
        tool=next((t for t in self.catalog(scope[2],discover=False) if t['tool_id']==current['tool_id']),None)
        if not tool:
            with self.db() as conn:
                stored=json.loads(self._row(conn,scope,request)['payload'])
                if stored['status']=='needs_approval':
                    stored.update(status='failed',reason='Tool changed; rediscover before a new proposal');self._save(conn,scope,request,stored)
            return self.status(scope,request)
        if value['decision']=='approve_call' and tool.get('_kind')=='hermes':
            raise HTTPException(409,'This tool needs approval each time')
        self._start(scope,request,tool,True,allow_call=value['decision']=='approve_call')
        return self.status(scope,request)

    def cancel(self, scope, request):
        self.status(scope,request)
        with self.db() as conn:
            value=json.loads(self._row(conn,scope,request)['payload'])
            if value['status'] in ('ready','needs_approval'):
                value.update(status='cancelled',reason='Cancelled before execution')
            elif value['status']=='running':
                value.update(status='uncertain',reason='Cancelled after execution started; inspect external state before retrying')
            self._save(conn,scope,request,value)
        return self.status(scope,request)

    def _start(self, scope, request, tool, approved, allow_call=False):
        if not SLOTS.acquire(blocking=False):
            with self.db() as conn:
                value=json.loads(self._row(conn,scope,request)['payload'])
                if value['status']=='ready':
                    value.update(status='failed',reason='Execution capacity reached; operation was not started')
                    self._save(conn,scope,request,value)
            raise HTTPException(429,'Tool execution capacity reached')
        submitted = False
        try:
            with self.db() as conn:
                value=json.loads(self._row(conn,scope,request)['payload'])
                expected='needs_approval' if approved else 'ready'
                if value['status']!=expected: return
                if approved and time.time()>value['_expires']:
                    value['status']='expired';self._save(conn,scope,request,value);return
                if allow_call:
                    try: self.mobile.owned(conn,scope[3],scope[0],scope[2],True)
                    except (LookupError,ValueError):
                        raise HTTPException(409,'Call is closed or unavailable; no allowance was granted') from None
                    conn.execute('''DELETE FROM call_tool_allowances WHERE NOT EXISTS
                        (SELECT 1 FROM calls c WHERE c.id=call_id AND c.owner=call_tool_allowances.owner
                         AND c.profile=call_tool_allowances.profile AND c.closed=0 AND c.created>?)''',
                        (time.time()-4*3600,))
                    existing=conn.execute('''SELECT 1 FROM call_tool_allowances
                        WHERE owner=? AND provider=? AND profile=? AND call_id=? AND tool_name=?''',(*scope,tool['name'])).fetchone()
                    if not existing:
                        count=conn.execute('SELECT COUNT(*) FROM call_tool_allowances WHERE owner=? AND provider=? AND profile=? AND call_id=?',scope).fetchone()[0]
                        if count>=MAX_CALL_ALLOWANCES: raise HTTPException(429,'Call tool allowance capacity reached')
                        if conn.execute('SELECT COUNT(*) FROM call_tool_allowances').fetchone()[0]>=MAX_ALLOWANCES:
                            raise HTTPException(429,'Tool allowance capacity reached')
                    conn.execute('INSERT OR REPLACE INTO call_tool_allowances VALUES(?,?,?,?,?,?,?)',
                        (*scope,tool['tool_id'],tool['name'],time.time()))
                if approved: value['approved_by']='owner'
                value.update(status='running',_started=time.time(),_boot=BOOT)
                self._save(conn,scope,request,value)
            context=contextvars.copy_context()
            try:
                operation={**tool,'read_only':value.get('_read_only',tool.get('read_only',False))}
                POOL.submit(context.run,self._run,scope,request,operation,value['_arguments'],bool(value.get('approved_by')),
                            value.get('_session_id',scope[3]),time.perf_counter())
            except Exception:
                with self.db() as conn:
                    row=self._row(conn,scope,request)
                    if row:
                        value=json.loads(row['payload'])
                        if value['status']=='running':
                            value.update(status='failed',reason='Tool execution could not be started')
                            self._save(conn,scope,request,value)
                raise HTTPException(503,'Tool execution could not be started') from None
            submitted = True
        finally:
            if not submitted: SLOTS.release()

    def _run(self, scope, request, tool, arguments, approved, session_id, queued_at=None):
        # monotonic ticks at ~15.6 ms on Windows, so durations use perf_counter.
        started=time.perf_counter()
        queued_at=started if queued_at is None else queued_at
        timing={'queue':(started-queued_at)*1000,'authority':0,'catalogue':0,'profile':0,'execute':0}
        dispatched=False
        try:
            # Recheck authority and config at the last boundary before the RPC.
            with self.mobile.connect() as conn:
                self.mobile.owned(conn,scope[3],scope[0],scope[2],True)
            timing['authority']=(time.perf_counter()-started)*1000
            # Catalogue discovery releases its profile scope before the store lock is needed below.
            phase=time.perf_counter()
            try: current=next((t for t in self.catalog(scope[2],discover=False) if t['tool_id']==tool['tool_id']),None)
            finally: timing['catalogue']=(time.perf_counter()-phase)*1000
            if not current: raise HTTPException(409,'Tool configuration changed')
            current_read_only=tool_read_only(current,arguments)
            if current_read_only!=tool['read_only']: raise HTTPException(409,'Tool access classification changed')
            current={**current,'read_only':current_read_only}
            phase=time.perf_counter()
            # Check the store before entering the profile scope to keep one global lock order.
            if self.status(scope,request)['status']!='running':
                raise HTTPException(409,'Tool admission cancelled or expired')
            cfg=self.mobile.settings()
            if (cfg.get('owner_id')!=scope[0] or cfg.get('owner_provider')!=scope[1]
                    or 'profiles' in cfg and scope[2] not in cfg['profiles']):
                raise HTTPException(403,'Owner authority changed')
            timing['authority']+=(time.perf_counter()-phase)*1000
            phase=time.perf_counter()
            def execute(_):
                nonlocal dispatched
                timing['profile']=(time.perf_counter()-phase)*1000
                dispatched=True
                execution=time.perf_counter()
                try: return self.backend.execute(current,arguments,scope,approved,session_id=session_id)
                finally: timing['execute']=(time.perf_counter()-execution)*1000
            try: raw=self.execution_scoped(scope[2],execute)
            finally:
                if not dispatched: timing['profile']=(time.perf_counter()-phase)*1000
            if not isinstance(raw,str): raw=encoded(raw)
            # Hermes' memory tool refuses before it writes, so its error is a clean failure, not an unknown effect.
            state=outcome(raw,tool['read_only'] or tool.get('_inline')=='memory',own=bool(tool.get('_inline')))
            text=self.sanitize(raw,MAX_RESULT)
            try: result=json.loads(text)
            except ValueError: result={'text':text,'truncated':True}
            oversized=len(encoded(result).encode())>MAX_RESULT
            if oversized: result={'text':text.encode()[:MAX_RESULT//8].decode('utf-8',errors='ignore'),'truncated':True}
            update={'status':state,'result':result,'truncated':len(raw)>MAX_RESULT or oversized}
        except HTTPException as error:
            update={'status':'uncertain' if dispatched and not tool['read_only'] else 'failed',
                    'reason':'Tool returned an HTTP failure after dispatch' if dispatched else 'Call authority or tool configuration changed before execution',
                    'error_code':'tool_http_failure' if dispatched else 'admission_rejected','http_status':error.status_code}
        except Exception:
            uncertain=dispatched and not tool['read_only']
            update={'status':'uncertain' if uncertain else 'failed',
                    'reason':'Tool outcome unavailable; inspect external state before retrying' if uncertain else
                            'Read-only tool failed' if dispatched else 'Tool failed before execution'}
        finally:
            timing['total']=(time.perf_counter()-queued_at)*1000
            timing={name:round(value,3) for name,value in timing.items()}
            logger.info('Beacon call tool timing queue_ms=%s authority_ms=%s catalogue_ms=%s profile_ms=%s execute_ms=%s total_ms=%s',
                *(timing[name] for name in ('queue','authority','catalogue','profile','execute','total')))
            try:
                with self.db() as conn:
                    value=json.loads(self._row(conn,scope,request)['payload'])
                    # A timeout is terminal: a late return cannot manufacture certainty.
                    if value['status']=='running': value.update(update,_timings_ms=timing);self._save(conn,scope,request,value)
            finally: SLOTS.release()
