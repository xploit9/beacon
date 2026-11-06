"""Gateway-owned Vault readiness and one-shot owner signup admission."""
import ast
from contextlib import contextmanager
from contextvars import ContextVar
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import sys
import threading
import time
import types
import uuid

ROOT = Path(__file__).resolve().parent
_SCOPE = ContextVar('beacon_vault_scope', default=None)
_READY = {'secure_prompts': False, 'otp': False, 'signup': False}
_RECEIPT_TTL = 12.0
_INTENT_TTL = 600.0
_ITEM = re.compile(r'vault_[0-9a-f]{12}\Z')
_DASHBOARD = None
_DASHBOARD_LOCK = threading.Lock()
_GATEWAY = None
_GATEWAY_USERS = 0
_GATEWAY_LOCK = threading.Lock()
_FENCED_TURNS = set()
_FENCE_UNKNOWN = False
_CONTEXTMANAGER_CODE = contextmanager(lambda: None).__code__
_EXTRA_NATIVE = {
    'gateway/status.py': 'e3763e9f6e93b37e57ee1f198a4a4de9f3fd0e8fbbdccb690742ba8def345496',
    'gateway/session_authorities.py': '36302d37fdccde583c0ad21dbd9b4eeaf904450d8872e0d129c8ca41582523b7',
    'tools/approval_prompt.py': 'f1f26fec96e37b17270ecf716bbbea330b997a02d3ab8052a1b74cb5e441cfb3',
}
_NATIVE_CODE = {
    'gateway.run_turn_runner': ('TurnRunner._run_conversation_with_approval',),
    'gateway.session_server_requests': ('ask_viewer',),
    'gateway.session_identity': ('parse_authenticated_subject',),
    'gateway.session_authorities': ('serving_authorities',),
    'gateway.status': ('get_process_start_time', '_get_process_start_time', 'read_runtime_status', '_read_json_file',
                       'get_runtime_status_running_pid', '_live_pid_from_record', '_record_matches_live_gateway_pid',
                       '_pid_record_belongs_to_current_profile', 'runtime_status_is_stale', '_marker_is_stale'),
    'tools.thread_context': ('propagate_context_to_thread',),
    'tools.browser_supervisor_capture': ('CapturedCDP.call',),
    'tools.approval_prompt': ('request_elicitation_consent',),
    'tui_gateway.ws': ('handle_ws',),
    'agent.vault_login_classifier': ('build_fill_js',),
    'agent.vault_store': ('VaultStore._write_all', 'VaultStore._meta', 'VaultStore._locked',
                          'VaultStore._fernet', 'get_vault_store', 'totp_now'),
    'agent.redact': ('redact_sensitive_text',),
}


def home_hash(home):
    path = Path(home).resolve(strict=True)
    if not path.is_dir():
        raise ValueError('Vault unavailable')
    return hashlib.sha256(os.path.normcase(str(path)).encode('utf-8')).hexdigest()


@contextmanager
def scope(owner, provider, issuer, home):
    token = _SCOPE.set((owner, provider, issuer, home))
    try: yield
    finally: _SCOPE.reset(token)


def _schema(conn):
    conn.execute('CREATE TABLE IF NOT EXISTS vault_gateway_receipt (id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL)')
    conn.execute('''CREATE TABLE IF NOT EXISTS vault_signup_intents (
        owner TEXT NOT NULL, provider TEXT NOT NULL, issuer TEXT NOT NULL, home_hash TEXT NOT NULL,
        handle TEXT NOT NULL, origin TEXT NOT NULL, expires REAL NOT NULL, consumed INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY(owner,provider,issuer,home_hash,handle))''')


def _bound(conn, bound):
    owner, provider, issuer, home = bound
    row = conn.execute('SELECT owner,provider FROM vault_profile_owners WHERE home_hash=?', (home,)).fetchone()
    issuer_row = conn.execute('SELECT owner,provider,issuer FROM vault_profile_issuers WHERE home_hash=?', (home,)).fetchone()
    return bool(row and tuple(row) == (owner, provider) and issuer_row and tuple(issuer_row) == (owner, provider, issuer))


def _live_pid(status):
    record = status.read_runtime_status() or {}
    return None if status.runtime_status_is_stale(record) else status.get_runtime_status_running_pid(record)


def record_intent(mobile, bound, handle, origin, *, now=None):
    if (not isinstance(bound, tuple) or len(bound) != 4 or not isinstance(handle, str) or not _ITEM.fullmatch(handle)
            or not isinstance(origin, str) or not origin.startswith('https://') or len(origin) > 2048):
        raise ValueError('Vault unavailable')
    now = time.time() if now is None else now
    if not isinstance(now, (int, float)) or not math.isfinite(now):
        raise ValueError('Vault unavailable')
    with mobile.LOCK, mobile.connect() as conn:
        _schema(conn)
        conn.execute('BEGIN IMMEDIATE')
        if not _bound(conn, bound):
            raise ValueError('Vault unavailable')
        conn.execute('DELETE FROM vault_signup_intents WHERE expires<?', (now,))
        conn.execute('INSERT INTO vault_signup_intents VALUES (?,?,?,?,?,?,?,0)', (*bound, handle, origin, now + _INTENT_TTL))


def invalidate_intent(mobile, bound, handle):
    with mobile.LOCK, mobile.connect() as conn:
        _schema(conn); conn.execute('BEGIN IMMEDIATE')
        if not _bound(conn, bound): raise ValueError('Vault unavailable')
        conn.execute('UPDATE vault_signup_intents SET consumed=1 WHERE owner=? AND provider=? AND issuer=? AND home_hash=? AND handle=?', (*bound, handle))


def _codes(code):
    yield code
    for item in code.co_consts:
        if isinstance(item, types.CodeType):
            yield from _codes(item)


def _compiled_functions(path):
    compiled = compile(path.read_bytes(), str(path), 'exec', dont_inherit=True)
    result = {}
    for code in _codes(compiled):
        result.setdefault(code.co_qualname, []).append(code)
    return result


def _code_matches(fn, compiled):
    if getattr(fn, '__code__', None) == _CONTEXTMANAGER_CODE:
        fn = getattr(fn, '__wrapped__', fn)
    code = getattr(fn, '__code__', None)
    return isinstance(code, types.CodeType) and any(code == candidate for candidate in compiled.get(code.co_qualname, ()))


def _code_data(value):
    if isinstance(value, types.CodeType):
        return {'name':value.co_qualname, 'args':[value.co_argcount, value.co_posonlyargcount, value.co_kwonlyargcount],
                'flags':value.co_flags, 'body':value.co_code.hex(), 'names':value.co_names,
                'variables':value.co_varnames, 'free':value.co_freevars, 'cells':value.co_cellvars,
                'constants':[_code_data(item) for item in value.co_consts],
                'exceptions':value.co_exceptiontable.hex()}
    if isinstance(value, tuple): return ['tuple', [_code_data(item) for item in value]]
    if isinstance(value, frozenset):
        return ['frozenset', sorted((_code_data(item) for item in value), key=lambda item:json.dumps(item, sort_keys=True))]
    if isinstance(value, bytes): return ['bytes', value.hex()]
    if value is None or type(value) in (bool, int, float, complex, str): return [type(value).__name__, repr(value)]
    if value is Ellipsis: return ['ellipsis']
    raise ValueError('Vault unavailable')


class Integrity:
    def __init__(self): self._cache = {}; self._code_cache = None

    def _native(self):
        spec = importlib.util.find_spec('agent')
        locations = list(spec.submodule_search_locations or ()) if spec else []
        if len(locations) != 1: raise ValueError('Vault unavailable')
        return Path(locations[0]).resolve().parent

    def _compiled(self, path):
        raw = path.read_bytes(); digest = hashlib.sha256(raw).hexdigest()
        cached = self._cache.get(str(path))
        if cached is None or cached[0] != digest:
            cached = digest, _compiled_functions(path)
            self._cache[str(path)] = cached
        return cached[1]

    def source(self):
        bridge_path = ROOT / 'vault_bridge.py'
        parsed = ast.parse(bridge_path.read_text(encoding='utf-8-sig'))
        assignment = next(node for node in parsed.body if isinstance(node, ast.Assign) and
                          any(isinstance(target, ast.Name) and target.id == 'NATIVE_HASHES' for target in node.targets))
        native = ast.literal_eval(assignment.value)
        if not isinstance(native, dict) or not native or any(k in native and native[k] != v for k, v in _EXTRA_NATIVE.items()):
            raise ValueError('Vault unavailable')
        native = {**native, **_EXTRA_NATIVE}
        base = self._native()
        for path, expected in native.items():
            if (not isinstance(path, str) or not re.fullmatch(r'[a-zA-Z0-9_./-]+\.py', path) or '..' in path or
                    hashlib.sha256((base / path).read_bytes()).hexdigest() != expected):
                raise ValueError('Vault unavailable')
        files = ('__init__.py', 'dashboard/plugin_api.py', 'vault_bridge.py', 'vault_runtime.py', 'vault_api.py', 'vault_attestation.py', 'plugin.yaml')
        digests = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in files}
        # Compare the code executing this check, not only the file that happens to be on disk.
        own = self._compiled(ROOT / 'vault_attestation.py')
        if not all(_code_matches(fn, own) for fn in (scope, record_intent, invalidate_intent, _bound, _live_pid,
                _codes, _compiled_functions, _code_matches, _code_data,
                _gateway_authorize, _gateway_signup, acquire_gateway, release_gateway,
                Coordinator._subject, Coordinator._owns_bridge, Coordinator.authorize_owner,
                Coordinator._confirm_signup, Coordinator.admit_signup, Coordinator._publish,
                Coordinator.publish, Coordinator.readiness, Coordinator.guard, Coordinator._run,
                Integrity._native, Integrity._compiled, Integrity.source, Integrity.code, Integrity.loaded,
                Integrity.entrypoints, Integrity.status_loaded)):
            raise ValueError('Vault unavailable')
        return hashlib.sha256(json.dumps({'files':digests, 'native':native}, sort_keys=True, separators=(',', ':')).encode()).hexdigest()

    def code(self, signature):
        if self._code_cache and self._code_cache[0] == signature:
            return self._code_cache[1]
        base = self._native()
        values = []
        bridge = self._compiled(ROOT / 'vault_bridge.py')
        for name in sorted(bridge):
            values.extend(('bridge', name, _code_data(item)) for item in bridge[name])
        for name, attrs in sorted(_NATIVE_CODE.items()):
            compiled = self._compiled(base / (name.replace('.', '/') + '.py'))
            for attr in attrs:
                candidates = compiled.get(attr, ())
                if len(candidates) != 1: raise ValueError('Vault unavailable')
                values.append((name, attr, _code_data(candidates[0])))
        value = hashlib.sha256(json.dumps({'python':sys.implementation.cache_tag, 'code':values},
                                          sort_keys=True, separators=(',', ':')).encode()).hexdigest()
        self._code_cache = signature, value
        return value

    def entrypoints(self, entries):
        try:
            allowed = {'__init__.py', 'dashboard/plugin_api.py', 'vault_api.py', 'vault_runtime.py'}
            return bool(entries) and all(path in allowed and _code_matches(fn, self._compiled(ROOT / path)) for path, fn in entries)
        except Exception:
            return False

    def status_loaded(self, module):
        try:
            path = self._native() / 'gateway/status.py'
            if module is not sys.modules.get('gateway.status') or Path(module.__file__).resolve() != path.resolve():
                return False
            compiled = self._compiled(path)
            return all(_code_matches(getattr(module, name), compiled) for name in _NATIVE_CODE['gateway.status'])
        except Exception:
            return False

    def loaded(self, bridge):
        try:
            if Path(bridge.__file__).resolve() != (ROOT / 'vault_bridge.py').resolve() or not bridge.attested():
                return False
            path = ROOT / 'vault_bridge.py'
            compiled = self._compiled(path)
            tree = ast.parse(path.read_text(encoding='utf-8-sig'))
            functions = []
            for node in tree.body:
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                    functions.append(getattr(bridge, node.name))
                elif isinstance(node, ast.ClassDef):
                    cls = getattr(bridge, node.name)
                    functions.extend(getattr(cls, child.name) for child in node.body
                                     if isinstance(child, (ast.FunctionDef, ast.AsyncFunctionDef)))
            for _, _, value in bridge._STATE['patches']:
                if callable(value):
                    if not isinstance(value, types.FunctionType): return False
                    functions.append(value)
            if not all(_code_matches(fn, compiled) for fn in functions):
                return False
            base = self._native()
            for name, attrs in _NATIVE_CODE.items():
                module = sys.modules.get(name)
                path = base / (name.replace('.', '/') + '.py')
                if module is None or Path(module.__file__).resolve() != path.resolve():
                    return False
                compiled = self._compiled(path)
                for attr in attrs:
                    value = module
                    for part in attr.split('.'): value = getattr(value, part)
                    if not _code_matches(value, compiled): return False
            runtime = bridge._runtime()
            compiled = self._compiled(ROOT / 'vault_runtime.py')
            return all(_code_matches(getattr(runtime, name), compiled)
                       for name in ('capabilities', '_input', '_strict_read', '_store', '_origin'))
        except Exception:
            return False


class Coordinator:
    def __init__(self, mobile, version, *, bridge=None, status=None, integrity=None, now=None, pid=None, confirm=None):
        self.mobile, self.version = mobile, version
        self.bridge, self.status = bridge, status
        self.integrity = integrity or Integrity()
        self.now, self.pid, self.confirm = now or time.time, pid or os.getpid, confirm
        self.instance = uuid.uuid4().hex
        self.entrypoints = []
        self.shared = False
        self._stop = threading.Event()
        self._thread = None
        # Bound methods have stable identities for bridge.install().
        self.authorize_callback, self.signup_callback = self.authorize_owner, self.admit_signup
        self.readiness_callback = self.readiness

    def _status(self):
        return self.status or sys.modules.get('gateway.status')

    def _bridge(self):
        if self.bridge is not None: return self.bridge
        return sys.modules.get('beacon_vault_bridge')

    def _owns_bridge(self, bridge):
        return bool(not self._stop.is_set() and not (self.shared and _FENCE_UNKNOWN) and bridge and bridge.attested() and bridge._STATE.get('authorize') is self.authorize_callback and
                    bridge._STATE.get('admit_signup') is self.signup_callback)

    def _authorities(self):
        bridge = self._bridge()
        runner = bridge._STATE['runner']
        registry = getattr(runner, 'session_authorities', None)
        if registry is None:
            one = getattr(runner, 'session_authority', None)
            return [one] if one is not None else []
        return list(registry)

    def _subject(self, authority, session_id):
        bridge = self._bridge()
        if not self._owns_bridge(bridge) or not any(item is authority for item in self._authorities()): return None
        if getattr(authority, 'retiring', False) or getattr(authority, 'retired', False): return None
        value = bridge.request_owner(authority, session_id)
        if not isinstance(value, tuple) or len(value) != 4: return None
        owner, provider, issuer, home = value
        if not all(isinstance(v, str) for v in value) or not owner or not provider: return None
        if home_hash(home) != home_hash(authority.profile_id): return None
        configured = self.mobile.settings()
        if (configured.get('owner_id'), configured.get('owner_provider')) != (owner, provider): return None
        generation = authority.executing.get(session_id)
        if type(generation) is not int: return None
        if self.shared and (_FENCE_UNKNOWN or (id(authority), session_id, generation) in _FENCED_TURNS): return None
        bound = owner, provider, issuer, home_hash(home)
        with self.mobile.LOCK, self.mobile.connect() as conn:
            if not _bound(conn, bound): return None
        return bound, generation

    def authorize_owner(self, authority, session_id):
        try: return self._subject(authority, session_id) is not None
        except Exception: return False

    def _confirm_signup(self, authority, session_id, handle, origin):
        from tools.approval_prompt import request_elicitation_consent
        bridge = self._bridge()
        def ask(label, site):
            return request_elicitation_consent('Create an account on ' + site,
                'Allow this one signup attempt using the generated login? Account creation is not yet verified.',
                surface='vault-signup', title='Confirm signup?') == 'accept'
        return bridge._guard_payment_confirmation(ask)('signup', origin)

    def admit_signup(self, authority, session_id, handle, origin):
        try:
            if not isinstance(handle, str) or not _ITEM.fullmatch(handle) or not isinstance(origin, str): return False
            subject = self._subject(authority, session_id)
            bridge = self._bridge(); binding = bridge._CTX.get()
            if (subject is None or binding is None or binding.authority is not authority or
                    binding.session_id != session_id or binding.generation != subject[1] or not bridge._authorized(binding)):
                return False
            bound, generation = subject
            with self.mobile.LOCK, self.mobile.connect() as conn:
                _schema(conn)
                row = conn.execute('SELECT expires FROM vault_signup_intents WHERE owner=? AND provider=? AND issuer=? AND home_hash=? AND handle=? AND origin=? AND consumed=0', (*bound, handle, origin)).fetchone()
            if not row or not self.now() <= row[0] <= self.now() + _INTENT_TTL: return False
            approve = self.confirm or self._confirm_signup
            if approve(authority, session_id, handle, origin) is not True: return False
            if self._subject(authority, session_id) != (bound, generation) or not bridge._authorized(binding): return False
            with self.mobile.LOCK, self.mobile.connect() as conn:
                _schema(conn); conn.execute('BEGIN IMMEDIATE')
                if not _bound(conn, bound): return False
                updated = conn.execute('UPDATE vault_signup_intents SET consumed=1 WHERE owner=? AND provider=? AND issuer=? AND home_hash=? AND handle=? AND origin=? AND consumed=0 AND expires>=? AND expires<=?', (*bound, handle, origin, self.now(), self.now() + _INTENT_TTL)).rowcount
            return updated == 1
        except Exception:
            return False

    def publish(self):
        ready = self._publish()
        if not ready: self._withdraw()
        return ready

    def _publish(self):
        try:
            bridge, status = self._bridge(), self._status()
            if self._stop.is_set() or status is None or not self._owns_bridge(bridge): return False
            signature = self.integrity.source()
            if not self.integrity.loaded(bridge) or not self.integrity.entrypoints(self.entrypoints): return False
            pid = self.pid(); birth = status.get_process_start_time(pid)
            if type(pid) is not int or type(birth) is not int or _live_pid(status) != pid: return False
            if self.mobile.read_plugin_version(ROOT / 'plugin.yaml') != self.version or not self.version: return False
            homes = sorted({home_hash(a.profile_id) for a in self._authorities()
                            if a is not None and not getattr(a, 'retired', False) and not getattr(a, 'retiring', False)})
            if not homes or len(homes) > 64: return False
            value = {'pid':pid, 'birth':birth, 'instance':self.instance, 'at':self.now(), 'version':self.version, 'source':signature,
                     'code':self.integrity.code(signature),
                     'homes':homes, 'protocol':'vault-rpc-v1', 'signup':bool(bridge.signup_ready())}
            with self.mobile.LOCK, self.mobile.connect() as conn:
                _schema(conn)
                if self._stop.is_set(): return False
                conn.execute('INSERT OR REPLACE INTO vault_gateway_receipt VALUES (1,?)', (json.dumps(value, separators=(',', ':')),))
            return True
        except Exception:
            return False

    def readiness(self):
        try:
            bound = _SCOPE.get(); status = self._status()
            if (bound is None or status is None or not self.integrity.entrypoints(self.entrypoints) or
                    not self.integrity.status_loaded(status)): return dict(_READY)
            with self.mobile.LOCK, self.mobile.connect() as conn:
                if not _bound(conn, bound): return dict(_READY)
                row = conn.execute('SELECT value FROM vault_gateway_receipt WHERE id=1').fetchone()
            value = json.loads(row[0]) if row else None
            if not isinstance(value, dict) or set(value) != {'pid','birth','instance','at','version','source','code','homes','protocol','signup'}: return dict(_READY)
            stamp = value['at']; now = self.now()
            if (type(stamp) not in (int, float) or not math.isfinite(stamp) or stamp > now or now - stamp > _RECEIPT_TTL or
                    type(value['pid']) is not int or type(value['birth']) is not int or
                    not isinstance(value['instance'], str) or not re.fullmatch(r'[0-9a-f]{32}', value['instance']) or
                    value['version'] != self.version or self.mobile.read_plugin_version(ROOT / 'plugin.yaml') != self.version or
                    value['source'] != self.integrity.source() or value['code'] != self.integrity.code(value['source']) or
                    value['protocol'] != 'vault-rpc-v1' or
                    not isinstance(value['homes'], list) or bound[3] not in value['homes'] or type(value['signup']) is not bool):
                return dict(_READY)
            if _live_pid(status) != value['pid'] or status.get_process_start_time(value['pid']) != value['birth']:
                return dict(_READY)
            return {'secure_prompts':True, 'otp':True, 'signup':value['signup']}
        except Exception:
            return dict(_READY)

    def guard(self, tool_name=None, **kwargs):
        if tool_name != 'browser_vault': return None
        try:
            bridge = self._bridge()
            if self._owns_bridge(bridge): return None
        except Exception:
            pass
        return {'action':'block', 'message':'Secure Vault is unavailable in this session.'}

    def _load_bridge(self):
        if self._bridge() is not None: return self._bridge()
        if 'hermes_bootstrap' not in sys.modules or 'gateway.run_api' not in sys.modules: return None
        name = 'beacon_vault_bridge'; path = ROOT / 'vault_bridge.py'
        spec = importlib.util.spec_from_file_location(name, path)
        module = importlib.util.module_from_spec(spec); sys.modules[name] = module
        try: spec.loader.exec_module(module)
        except Exception:
            sys.modules.pop(name, None)
            return None
        self.bridge = module
        return module

    def _run(self):
        while not self._stop.is_set():
            try:
                bridge = self._load_bridge()
                if bridge and bridge.install(self.authorize_callback, self.signup_callback):
                    if 'tools.approval_prompt' not in sys.modules:
                        importlib.import_module('tools.approval_prompt')
                    self.publish()
                else: self._withdraw()
            except Exception:
                self._withdraw()
            self._stop.wait(3)

    def start(self):
        if self._thread is not None or self._stop.is_set(): return
        self._thread = threading.Thread(target=self._run, name='beacon-vault-attestation', daemon=True)
        self._thread.start()

    def _withdraw(self):
        try:
            with self.mobile.LOCK, self.mobile.connect() as conn:
                _schema(conn)
                row = conn.execute('SELECT value FROM vault_gateway_receipt WHERE id=1').fetchone()
                value = json.loads(row[0]) if row else {}
                status = self._status()
                if (status and value.get('instance') == self.instance and value.get('pid') == self.pid() and
                        value.get('birth') == status.get_process_start_time(self.pid())):
                    conn.execute('DELETE FROM vault_gateway_receipt WHERE id=1')
        except Exception:
            pass

    def close(self):
        self._stop.set()
        self._withdraw()

    def stop(self):
        self._stop.set()
        threading.Thread(target=self.close, name='beacon-vault-stop', daemon=True).start()


def configure_dashboard(runtime, mobile, version, entrypoints=None):
    global _DASHBOARD
    with _DASHBOARD_LOCK:
        if _DASHBOARD is None:
            _DASHBOARD = Coordinator(mobile, version)
            _DASHBOARD.entrypoints = entrypoints or []
        if _DASHBOARD.mobile is not mobile or _DASHBOARD.version != version:
            return False
        return runtime.configure_gateway_attestor(_DASHBOARD.readiness_callback)


def _gateway_authorize(authority, session_id):
    coordinator = _GATEWAY
    return bool(coordinator and coordinator.authorize_owner(authority, session_id))


def _gateway_signup(authority, session_id, handle, origin):
    coordinator = _GATEWAY
    return bool(coordinator and coordinator.admit_signup(authority, session_id, handle, origin))


def acquire_gateway(mobile, entrypoint=None):
    global _GATEWAY, _GATEWAY_USERS
    with _GATEWAY_LOCK:
        if _GATEWAY is None:
            _GATEWAY = Coordinator(mobile, '')
            _GATEWAY.entrypoints = [('__init__.py', entrypoint)] if entrypoint else []
            _GATEWAY.shared = True
            _GATEWAY.authorize_callback, _GATEWAY.signup_callback = _gateway_authorize, _gateway_signup
        if _GATEWAY.mobile is not mobile: raise RuntimeError('Vault unavailable')
        _GATEWAY_USERS += 1
        return _GATEWAY


def release_gateway(coordinator):
    global _GATEWAY, _GATEWAY_USERS, _FENCE_UNKNOWN
    with _GATEWAY_LOCK:
        if _GATEWAY is not coordinator or not _GATEWAY_USERS: return
        _GATEWAY_USERS -= 1
        if _GATEWAY_USERS: return
        stop = getattr(coordinator, '_stop', None)
        if stop is not None: stop.set()
        try:
            bridge = coordinator._bridge()
            state = getattr(bridge, '_STATE', None) if bridge is not None else None
            if isinstance(state, dict) and state.get('runner') is not None:
                for authority in coordinator._authorities():
                    if authority is not None:
                        _FENCED_TURNS.update((id(authority), sid, generation) for sid, generation in list(authority.executing.items()))
                if len(_FENCED_TURNS) > 4096: _FENCE_UNKNOWN = True
        except Exception:
            _FENCE_UNKNOWN = True
        _GATEWAY = None
    coordinator.stop()
