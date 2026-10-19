"""Call-scoped receipts around Hermes' configured MCP handlers; no model or proxy."""
import concurrent.futures
import contextvars
import hashlib
import inspect
import json
import re
import secrets
import threading
import time
import uuid
from contextlib import contextmanager

from fastapi import HTTPException

MAX_ARGUMENTS = 16384
MAX_RESULT = 24576
MAX_TOOLS = 12
# Eight active calls at 128 receipts each; old closed or expired receipts make room.
MAX_RECEIPTS = 1024
DEADLINE = 90
BOOT = uuid.uuid4().hex
POOL = concurrent.futures.ThreadPoolExecutor(max_workers=4, thread_name_prefix='cypher-mcp')
SLOTS = threading.BoundedSemaphore(4)
CATALOG_LOCK = threading.RLock()
QUERY_FILLER = set('a an the my me please could can you i would like want get read list find look pull show to for of in on and from up most latest recent current last today now one two three four five six ten tool tools'.split())
QUERY_ALIASES = {
    'email': {'email', 'mail', 'gmail', 'inbox', 'outlook'},
    'mail': {'email', 'mail', 'gmail', 'inbox', 'outlook'},
    'web': {'web', 'internet', 'website', 'browser', 'scrapling'},
    'search': {'search', 'find', 'lookup', 'query'},
    'get': {'get', 'read', 'retrieve', 'fetch', 'list'},
}


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
            if named & {'message','thread','inbox'}: weight += 2
            if named & {'search','list'}: weight += 1
    return (int(query.casefold() in (tool['name'].casefold(), tool.get('_raw', '').casefold())), coverage, weight)


def encoded(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)


def digest(value):
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def outcome(value, read_only, depth=0):
    """Recognize native errors and JSON-encoded MCP envelopes before truncation."""
    if depth>4: return 'completed'
    if isinstance(value,str):
        try: value=json.loads(value)
        except ValueError: return 'completed'
    if not isinstance(value,dict): return 'completed'
    if value.get('outcome_uncertain') is True: return 'uncertain'
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


class NativeMcp:
    """Only native registrations whose exact connection still matches this profile's config."""
    def catalog(self):
        from tools import mcp_tool as core
        from tools.mcp_tool_config import _load_mcp_config
        from tools.mcp_tool_common import mcp_server_enabled
        from tools.mcp_tool_discovery import discover_mcp_tools
        from tools.mcp_tool_registration import _same_server_route, _make_tool_filter, _normalize_server_trust
        from tools.mcp_tool_scope import _resolve_server_key
        from tools.mcp_tool_handlers import _tool_is_read_only, _make_tool_handler
        from tools.registry import registry
        # Native discovery loads only operator config; the caller supplies no connection address.
        discover_mcp_tools()
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
            readonly = (_tool_is_read_only(server, raw) and not generic_http
                        and not (has_method and not method_read_only))
            version = digest([registry.current_scope_key(), server, raw, cfg, schema, readonly])
            output.append({'tool_id':version, 'name':entry.name,
                           'description':safe(entry.schema.get('description', ''), 1200),
                           'parameters':schema, 'read_only':readonly,
                           '_handler':_make_tool_handler(server,raw,min(60,float(closure.get('tool_timeout') or 60))),
                           '_server':server, '_raw':raw,
                           '_trust':_normalize_server_trust(cfg.get('trust'))})
        return output

    def execute(self, tool, arguments, scope, approved):
        from tools import approval, approval_context
        from gateway import session_context
        key = 'cypher-tool/' + uuid.uuid4().hex
        session_tokens = session_context.set_session_vars(platform='api_server', source='cypher',
            user_id=scope[1]+':'+scope[0], profile=scope[2], session_key=key, session_id=scope[3])
        token = approval_context.set_current_session_key(key)
        # This native gate represents the SAME approved operation, not another grant.
        expected = (f"MCP tool '{tool['_raw']}' on UNTRUSTED server '{tool['_server']}' wants to run. "
                    'This tool is write-capable (no readOnlyHint=true annotation) and may modify external state.')
        used = False
        once_lock=threading.Lock()
        def notify(data):
            nonlocal used
            request=data.get('request_id') if isinstance(data,dict) else None
            if not isinstance(request,str) or not re.fullmatch(r'[a-f0-9]{32}',request):
                # Raising makes the native notify path fail closed immediately, without a wait.
                raise ValueError('Invalid native approval request identity')
            with once_lock:
                accept = (approved and not used and tool['_trust']=='untrusted'
                          and data.get('pattern_key')=='mcp_elicitation' and data.get('command')==expected)
                used = True
            approval.resolve_gateway_approval(key, 'once' if accept else 'deny', request_id=request)
        approval.register_gateway_notify(key, notify)
        try:
            return tool['_handler'](arguments, session_id=scope[3])
        finally:
            approval.unregister_gateway_notify(key)
            approval_context.reset_current_session_key(token)
            session_context.clear_session_vars(session_tokens)


class Gateway:
    def __init__(self, mobile, scoped, backend=None, sanitizer=safe):
        self.mobile, self.scoped = mobile, scoped
        self.backend = backend or NativeMcp()
        self.sanitize = sanitizer

    @contextmanager
    def db(self):
        with self.mobile.LOCK, self.mobile.connect() as conn:
            conn.execute('''CREATE TABLE IF NOT EXISTS tool_receipts (
                owner TEXT, provider TEXT, profile TEXT, call_id TEXT, request_id TEXT,
                signature TEXT NOT NULL, payload TEXT NOT NULL, updated REAL NOT NULL,
                PRIMARY KEY(owner,provider,profile,call_id,request_id))''')
            yield conn

    def catalog(self, profile):
        with CATALOG_LOCK:
            return self.scoped(profile, lambda _: self.backend.catalog())

    def discover(self, profile, query=''):
        if not isinstance(query, str) or len(query)>240: raise HTTPException(400, 'Invalid tool query')
        words = set(re.findall(r'[^\W_]+', query.casefold())) - QUERY_FILLER
        words = {word for word in words if not word.isdecimal()}
        rows = self.catalog(profile)
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
            if value['status']=='running' and (value.get('_boot')!=BOOT or time.time()-value['_started']>DEADLINE):
                value.update(status='uncertain', reason='Execution may have completed; do not retry automatically')
                self._save(conn,scope,request,value)
            if value['status']=='needs_approval' and time.time()>value['_expires']:
                value.update(status='expired', reason='Approval proposal expired')
                self._save(conn,scope,request,value)
            if value['status']=='ready' and time.time()-row['updated']>DEADLINE:
                value.update(status='failed',reason='Admission interrupted before execution; operation was not started')
                self._save(conn,scope,request,value)
        return {k:v for k,v in value.items() if not k.startswith('_')}

    def invoke(self, scope, value):
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
        tools=self.catalog(scope[2]);tool=next((t for t in tools if t['tool_id']==value['tool_id']),None)
        if not tool: raise HTTPException(409, 'Tool changed or is no longer configured; rediscover it')
        arguments=value['arguments']
        validate(tool['parameters'], arguments)
        methods=readonly_methods(tool['parameters'])
        if 'method' not in arguments and methods:
            arguments={**arguments,'method':'GET' if 'GET' in methods else 'HEAD'}
            validate(tool['parameters'],arguments)
        result={'request_id':request,'tool_id':tool['tool_id'],'tool_name':tool['name'],
                'status':'ready' if tool['read_only'] else 'needs_approval',
                '_arguments':arguments, '_expires':time.time()+120}
        if not tool['read_only']:
            serialized=encoded(arguments)
            try:
                displayed=json.loads(self.sanitize(serialized,MAX_ARGUMENTS))
                if encoded(displayed)!=serialized:
                    raise ValueError('Approval arguments were changed by redaction')
            except (ValueError,TypeError):
                raise HTTPException(400,'These arguments cannot be shown accurately for approval; remove sensitive values') from None
            result['proposal']={'id':secrets.token_urlsafe(32),'tool_name':tool['name'],
                                'arguments':displayed}
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
        if tool['read_only']: self._start(scope,request,tool,False)
        return self.status(scope,request)

    def decide(self, scope, request, value):
        if set(value)!={'proposal_id','decision'} or value['decision'] not in ('approve','deny'):
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
        tool=next((t for t in self.catalog(scope[2]) if t['tool_id']==current['tool_id']),None)
        if not tool:
            with self.db() as conn:
                stored=json.loads(self._row(conn,scope,request)['payload'])
                if stored['status']=='needs_approval':
                    stored.update(status='failed',reason='Tool changed; rediscover before a new proposal');self._save(conn,scope,request,stored)
            return self.status(scope,request)
        self._start(scope,request,tool,True)
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

    def _start(self, scope, request, tool, approved):
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
                value.update(status='running',_started=time.time(),_boot=BOOT)
                self._save(conn,scope,request,value)
            context=contextvars.copy_context()
            try:
                POOL.submit(context.run,self._run,scope,request,tool,value['_arguments'],approved)
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

    def _run(self, scope, request, tool, arguments, approved):
        dispatched=False
        try:
            # Recheck authority and config at the last boundary before the RPC.
            with self.mobile.connect() as conn:
                self.mobile.owned(conn,scope[3],scope[0],scope[2],True)
            # Use the same lock->profile order as discover/invoke; never hold it across RPC.
            current=next((t for t in self.catalog(scope[2]) if t['tool_id']==tool['tool_id']),None)
            if not current: raise HTTPException(409,'Tool configuration changed')
            def execute(_):
                nonlocal dispatched
                if self.status(scope,request)['status']!='running':
                    raise HTTPException(409,'Tool admission cancelled or expired')
                cfg=self.mobile.settings()
                if cfg.get('owner_id')!=scope[0] or cfg.get('owner_provider')!=scope[1] or scope[2] not in cfg.get('profiles',[]):
                    raise HTTPException(403,'Owner authority changed')
                dispatched=True
                return self.backend.execute(current,arguments,scope,approved)
            raw=self.scoped(scope[2],execute)
            if not isinstance(raw,str): raw=encoded(raw)
            state=outcome(raw,tool['read_only'])
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
            try:
                with self.db() as conn:
                    value=json.loads(self._row(conn,scope,request)['payload'])
                    # A timeout is terminal: a late return cannot manufacture certainty.
                    if value['status']=='running': value.update(update);self._save(conn,scope,request,value)
            finally: SLOTS.release()
