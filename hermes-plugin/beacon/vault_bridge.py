"""Session-bound guard for the pinned native Hermes Vault implementation."""
import base64
from contextlib import contextmanager
from contextvars import ContextVar
from copy import deepcopy
from dataclasses import dataclass
import hashlib
import importlib
import json
import logging
from pathlib import Path
import re
import secrets
import sys
import threading
from types import MappingProxyType

NATIVE_HASHES = {
    'agent/vault_store.py': 'f0d5b89056c597627281f649d87476a08310866abd2c770592975a4a18314fc6',
    'agent/vault_login_classifier.py': 'd31712360320927993ea9c33b9633c04c03af66e5e21076ab31d1c60ab20f852',
    'agent/vault_backends/local.py': '27a9aa1cc7edcb84cf65681e092b222cb4e71c1f77b4f941cb9be634db3d8786',
    'agent/vault_backends/unlock.py': '7c489488ddfd0b0e43b0eb1efc3d355896357d799ef305c6ee436f981c3923df',
    'agent/redact.py': 'cea25df8139675b600eca448f70756bfa9ad22f537abf4f123d01bcbfc71e563',
    'tools/browser_vault_tool.py': 'ea1ac6458b5cf542a203c51168ae6fb4c6e9c7b1eae3854cc28852d57b0fc651',
    'tools/thread_context.py': '3d9e921fed5fedf8f6cccbd7e8ef9b12659b4c8f6872fcda27ddf81632ee1d12',
    'tools/registry.py': '48c3875138ac9e1875349ed2900d6940f51dfff050d655d6fb17a1dafe30f5e7',
    'gateway/run_turn_runner.py': '04f76f4857a8ba251b014d615b814f3920fef8212ad24e69b28fbbc4fdf2df39',
    'gateway/run_turn_progress.py': '66dfcdb8696a3240a2a811cb6b05df3bacfc1bc563067dfff3d55704fd561d81',
    'gateway/session_sudo_prompt.py': '3e2047c0cc2991007e4561a08a3e7ed5ad6737a241be66cd5fd3b057025773a1',
    'gateway/session_server_requests.py': '9aeb9332278f0aaff6c2fbd6b58da0b447775235e7721c3be1e456eb6cd04358',
    'gateway/session_identity.py': 'db364ea5be645451cf88e5822271447c8fc0337e9ec55b8f94640a0c669882cb',
    'tui_gateway/ws.py': 'd798ccd9328709c8c010820deb71a1b2adc28fbf5f5bfd6031cf1545e0ebd8e3',
    'tui_gateway/transport.py': '4fee8994e514cbc6760a0c1912cb3fb906036048a7ce41e281e57532716384d3',
    'agent/vault_backends/__init__.py': '06b7755bb20ba07f25d2b94a3ef6af4e6cb9460192e729cfcd605ab88a448ef5',
    'agent/vault_backends/base.py': 'e3ecdf5b0134d6e97a3cdc8a62c99ffa53cc33a304aa8758ebddfae9c853bf7f',
    'tools/browser_supervisor.py': '59949166786a2d11f1bf377d543ad387bc7cfca767fdf67b46761e20b77b7a34',
    'tools/browser_supervisor_capture.py': '1df0da36c767784d47c3fab17de2e58a589a528d4c5c4b590e66731ebccd0b36',
    'tools/browser_tool.py': 'b8a5929440b7efcebc187212ce47a7ce14c148f4c734e09e14bf9b0855cc39b4',
    'tools/browser_tool_session.py': '11e06f5aae8599036f72d46cd8d0a6c76f6a9c74fab07b4dda54690cb176f021',
    'tools/browser_tool_snapshot.py': 'f1dbec2bd922a365142bdb12b713b3af74f237a781d11768891ab097f0ea58a5',
    'tools/browser_cdp_tool.py': '896325546d3575605b82d86725ee8762fdcf606122dc49a19754a32b27ccb79a',
    'tools/browser_use_cli.py': 'c4f0db5d7c5a30953548df0e8d443aad915fd9b15ddc1d60cb3ad14502324845',
    'tools/approval_context.py': '9bcac985edbcd610392ac40c84c47ca38f169976f42b7e4acade5eb82709dd97',
    'gateway/session_authority.py': 'f6eba4d4e17f35e9bcaaf664d1dece93bca8ceccddba3d6d2ad99aa7983bb15f',
    'gateway/session_contract.py': 'a4ed0c9183a4a0ab6ccf67b9fb77be29098764aa9db6d0792a8e06616f10fbea',
    'gateway/session_controls.py': '0def0802a475405223ac88f864ac29d5d8049dc870aa4d247787bf9f5cd680af',
    'gateway/session.py': 'ed560fb1b416e1bc111ed9c1e20945238fc2632c60121852a428b4ec02b60d5c',
    'gateway/config.py': '81eafc6a1f56d0185685bc4683fd859b23aa4d4a759e4fabbf018feab9bd1f2f',
    'gateway/run_api.py':
        '77628713200032a963bf8c7882edcf0945e3c1343787f608050d5bcbc24735af',
    'hermes_constants.py': '870ad9935a62968636d6a14d160c0269dfbefd8091ab3cb71be78b30bef6360f',
    'hermes_cli/config.py':
        '1aebd87e71338c17f63bdf13ecd62c59aa053bc0b1270398efaa327642a1142b',
    'hermes_cli/tools_config.py':
        '35012cc12fc709a5a95c3f547ebb76a1938f231f0012e7e6fa843bf4be52a2fd',
    'agent/skill_utils.py':
        'cb1996a2ef7f52f075f1a45fa64eee4ea9f332b9910da4ecc123ce0ead79fb46',
    'model_tools.py': 'da5b7db1b05de615658e56c4172426ed82099a093c5b586e48e65ef930b778c8',
    'toolsets.py': '7674a11eeebcc06b7c83c816fca0bc6982107870f5615375d2ffc5dbe6044eaa',
}

_CTX = ContextVar('beacon_vault_turn', default=None)
_CAPTURE = ContextVar('beacon_vault_browser', default=None)
_SNAPSHOT = ContextVar('beacon_vault_item_snapshot', default=None)
_SAVE = ContextVar('beacon_vault_save_input', default=None)
_RESPONSE = ContextVar('beacon_vault_response', default=None)
_LOCK = threading.RLock()
_BINDINGS = {}
_PAYMENTS = {}
_STATE = None
_REDACTION_LIMIT = 4096


def request_owner(authority, session_id):
    """Return the authenticated LOCAL session subject, never infer an owner from model arguments."""
    try:
        from gateway.config import Platform
        from gateway.session_identity import parse_authenticated_subject
        live = authority.sessions.get(session_id)
        source = live.source if live else None
        if source is None or source.platform != Platform.LOCAL:
            return None
        identity = parse_authenticated_subject(source.user_id)
        home = str(authority.profile_id)
        if not identity or not identity[0] or not identity[2] or not Path(home).is_absolute():
            return None
        return identity[2], identity[0], identity[1], home
    except Exception:
        return None


def _runtime():
    with _LOCK:
        module = sys.modules.get('beacon_vault_runtime')
        if module is None:
            import importlib.util
            path = Path(__file__).with_name('vault_runtime.py')
            spec = importlib.util.spec_from_file_location('beacon_vault_runtime', path)
            module = importlib.util.module_from_spec(spec)
            sys.modules[spec.name] = module
            try:
                spec.loader.exec_module(module)
            except BaseException:
                if sys.modules.get(spec.name) is module:
                    del sys.modules[spec.name]
                raise
        return module


def _source_root():
    path = _runtime()._source_path()
    return path.parent.parent if path else None


def _known_source():
    try:
        root = _source_root()
        return bool(root and all(hashlib.sha256((root / path).read_bytes()).hexdigest() == digest
                                 for path, digest in NATIVE_HASHES.items()))
    except Exception:
        return False


def _live(binding, *, nonblocking=False):
    if not binding or not binding.active or getattr(binding.agent, '_interrupt_requested', False):
        return False
    authority, sid = binding.authority, binding.session_id
    if getattr(authority, 'retired', False) or getattr(authority, 'retiring', False) or authority.executing.get(sid) != binding.generation:
        return False
    live = authority.sessions.get(sid)
    if live is not binding.live or live.event_stream is not binding.events or request_owner(authority, sid) != binding.owner:
        return False
    try:
        lock = live.event_stream.lock
        if not lock.acquire(blocking=not nonblocking):
            return False
        try:
            members = [member for member in live.subscribers.values() if 'session:respond' in member.capabilities]
            if not members or any(member.subject != live.source.user_id or member.profile_id != authority.profile_id for member in members):
                return False
            fanout = live.event_stream.fanout
            if not fanout._lock.acquire(blocking=not nonblocking):
                return False
            try:
                return any(peer.attached and peer.transport is authority.events.get(member.transport_id)
                           for peer in fanout._peers for member in members)
            finally:
                fanout._lock.release()
        finally:
            lock.release()
    except Exception:
        return False


def _authorized(binding):
    if not _live(binding) or _STATE is None:
        return False
    try:
        return _STATE['authorize'](binding.authority, binding.session_id) is True and _live(binding)
    except Exception:
        return False


class _Binding:
    def __init__(self, turn, agent):
        authority, sid, generation = turn._approval_owner
        self.authority, self.session_id, self.generation, self.agent = authority, sid, generation, agent
        self.live = authority.sessions.get(sid)
        self.events = self.live.event_stream if self.live else None
        self.owner = request_owner(authority, sid)
        self.task_id = str(getattr(turn._ctx, 'session_id', '') or '')
        self.active = True


def _turn_scope(original):
    @contextmanager
    def wrapped(turn, agent):
        from gateway.run_turn_progress import local_viewer_turn
        with original(turn, agent):
            binding = _Binding(turn, agent) if local_viewer_turn(turn) else None
            if binding and (not binding.owner or not binding.task_id or not _authorized(binding)):
                binding = None
            token = _CTX.set(binding)
            key = (id(binding.events), binding.session_id) if binding else None
            if binding:
                with _LOCK:
                    prior = _BINDINGS.get(key)
                    if prior and prior.active:
                        binding.active = False
                    else:
                        _BINDINGS[key] = binding
            try:
                yield
            finally:
                if binding:
                    binding.active = False
                    with _LOCK:
                        if _BINDINGS.get(key) is binding:
                            _BINDINGS.pop(key, None)
                _CTX.reset(token)
    return wrapped


def _ask(binding, method, params, timeout=120):
    if not _authorized(binding):
        return ''
    from gateway.session_server_requests import ask_viewer
    value = ask_viewer(binding.events, binding.session_id, method, params,
                       cancelled=lambda: not _live(binding), timeout=timeout)
    return value if isinstance(value, str) and _authorized(binding) else ''


def _guard_resolve(original):
    def resolve(frame, *, session_ok=lambda sid, events: True):
        from gateway import session_server_requests as requests
        with requests._lock:
            pending = requests._open.get(frame.get('id'))
        if pending is None or pending.method not in ('vault.save_login', 'vault.code', 'vault.unlock_prompt'):
            return original(frame, session_ok=session_ok)
        with _LOCK:
            binding = _BINDINGS.get((id(pending.events), pending.session_id))
        if not _live(binding):
            return False
        decision = {'binding': binding, 'authorized': False}
        token = _RESPONSE.set(decision)
        try:
            def entitled(session_id, events):
                decision['authorized'] = False
                return bool(session_ok(session_id, events) and decision['authorized'])
            return original(frame, session_ok=entitled)
        finally:
            _RESPONSE.reset(token)
    return resolve


def _guard_may_settle(original):
    def may_settle(authority, actor, session_id, events):
        decision = _RESPONSE.get()
        if decision is None:
            return original(authority, actor, session_id, events)
        binding = decision['binding']
        if (not _live(binding) or binding.authority is not authority or binding.session_id != session_id or
                binding.events is not events or actor.subject != binding.live.source.user_id or
                actor.profile_id != authority.profile_id):
            return False
        decision['authorized'] = bool(original(authority, actor, session_id, events))
        return decision['authorized']
    return may_settle


def _guard_payment_confirmation(original):
    def confirm(label, origin):
        binding = _CTX.get()
        if not _authorized(binding) or _CAPTURE.get() is None:
            return False
        key = (id(binding.authority), binding.session_id, binding.generation)
        with _LOCK:
            if key in _PAYMENTS:
                return False
            _PAYMENTS[key] = binding
        try:
            return original(label, origin) is True and _authorized(binding)
        finally:
            with _LOCK:
                if _PAYMENTS.get(key) is binding:
                    _PAYMENTS.pop(key, None)
    return confirm


def _guard_payment_response(original):
    async def respond(authority, actor, ref, generation, prompt_id, response, *, kind='approval'):
        from hermes_state_runtime import RuntimeStoreError
        if kind != 'approval' or type(generation) is not int:
            return await original(authority, actor, ref, generation, prompt_id, response, kind=kind)
        if not _LOCK.acquire(blocking=False):
            raise RuntimeStoreError('permission_denied')
        try:
            binding = _PAYMENTS.get((id(authority), ref.session_id, generation))
        finally:
            _LOCK.release()
        if binding is not None and (not _live(binding, nonblocking=True) or
                                    actor.subject != binding.live.source.user_id or
                                    actor.profile_id != authority.profile_id):
            raise RuntimeStoreError('permission_denied')
        return await original(authority, actor, ref, generation, prompt_id, response, kind=kind)
    return respond


class _FrameFilter(logging.Filter):
    def filter(self, record):
        if record.msg == 'ws parse error peer=%s index=%d error=%s payload=%r':
            record.msg = 'ws parse error (payload withheld)'
            record.args = ()
        return True


def _failure(code='unavailable'):
    return json.dumps({'success': False, 'error_type': code, 'error': 'Vault action unavailable'})


def _capture(task_id, binding):
    from hermes_constants import hermes_home_key
    from tools.browser_supervisor import SUPERVISOR_REGISTRY
    expected = hermes_home_key(binding.owner[3])
    if hermes_home_key() != expected:
        raise RuntimeError('Browser unavailable')
    registry = SUPERVISOR_REGISTRY
    with registry._lock:
        supervisor = registry._by_task.get(task_id)
        if supervisor is None or registry._home_by_task.get(task_id) != expected:
            raise RuntimeError('Browser unavailable')
    captured = registry.capture(task_id)
    with registry._lock:
        if registry._by_task.get(task_id) is not supervisor or registry._home_by_task.get(task_id) != expected:
            raise RuntimeError('Browser unavailable')
    if captured._supervisor is not supervisor or not captured.page_session_id:
        raise RuntimeError('Browser unavailable')
    return captured, registry, expected


def _before_send(binding, task_id, captured, snapshot=None):
    if not _live(binding, nonblocking=True):
        raise RuntimeError('Session unavailable')
    if len(captured) != 4:
        raise RuntimeError('Browser unavailable')
    cap, registry, expected, page = captured
    if not page or cap.page_session_id != page:
        raise RuntimeError('Browser unavailable')
    if snapshot is not None and (not isinstance(snapshot, _ItemSnapshot) or not snapshot.active.is_set() or
                                 snapshot.binding is not binding or snapshot.captured is not captured or
                                 snapshot.owner != binding.owner or snapshot.generation != binding.generation or
                                 snapshot.page != cap.page_session_id):
        raise RuntimeError('Browser unavailable')
    if not registry._lock.acquire(blocking=False):
        raise RuntimeError('Browser unavailable')
    try:
        if registry._by_task.get(task_id) is not cap._supervisor or registry._home_by_task.get(task_id) != expected:
            raise RuntimeError('Browser unavailable')
    finally:
        registry._lock.release()
    # The native _fenced_page_op performs its desktop lease checks on the worker.
    # Its lease reader does file I/O and cannot run on the supervisor loop.


def _evaluate(task_id, expression):
    binding, captured = _CTX.get(), _CAPTURE.get()
    if not _authorized(binding) or not captured or task_id != binding.task_id:
        return {'success': False, 'error': 'Browser unavailable'}
    try:
        cap = captured[0]
        snapshot = _SNAPSHOT.get()
        reply = cap.call('Runtime.evaluate', {'expression': expression, 'returnByValue': True, 'awaitPromise': True},
                         session_id=cap.page_session_id, timeout=10,
                         before_send=lambda: _before_send(binding, task_id, captured, snapshot))
        result = reply.get('result') or {}
        if result.get('exceptionDetails'):
            return {'success': False, 'error': 'Browser unavailable'}
        return {'success': True, 'result': (result.get('result') or {}).get('value')}
    except Exception:
        return {'success': False, 'error': 'Browser unavailable'}


def _focus(task_id, origin, kind):
    result = _evaluate(task_id, 'window.location.origin')
    value = result.get('result') if result.get('success') else None
    if not isinstance(value, str) or not value or origin and value != origin:
        return None
    try:
        return value if _runtime()._origin(value) == value else None
    except Exception:
        return None


def _current_origin(task_id):
    origin = _focus(task_id, None, 'login')
    try:
        return origin if _runtime()._origin(origin) == origin else None
    except Exception:
        return None


def _local_backend(handle):
    if not isinstance(handle, str) or not re.fullmatch(r'vault_[0-9a-f]{12}', handle) or not _authorized(_CTX.get()):
        return None
    from agent.vault_backends.local import LocalLoginBackend
    return LocalLoginBackend()


@dataclass(frozen=True, repr=False)
class _ItemSnapshot:
    """One action's immutable item; never include its contents in a representation."""
    binding: object
    owner: tuple
    generation: object
    captured: object
    page: str
    home: Path
    handle: str
    meta: object
    secret: object
    active: threading.Event

    def __repr__(self):
        return '<vault item snapshot>'


class _SaveInput:
    __slots__ = ('expected',)

    def __init__(self):
        self.expected = None

    def __repr__(self):
        return '<vault save input>'


def _snapshot_home(binding, captured):
    from hermes_constants import get_hermes_home
    if not binding or not captured or len(captured) != 4 or not binding.owner or not _authorized(binding):
        raise RuntimeError('Vault unavailable')
    root = Path(binding.owner[3]).resolve(strict=True)
    if (not root.is_dir() or Path(get_hermes_home()).resolve(strict=True) != root or
            Path(captured[2]).resolve(strict=True) != root or not captured[3] or captured[0].page_session_id != captured[3]):
        raise RuntimeError('Vault unavailable')
    return root


def _snapshot_access(handle):
    from agent.vault_store import VaultError
    snapshot, binding, captured = _SNAPSHOT.get(), _CTX.get(), _CAPTURE.get()
    try:
        if (not isinstance(snapshot, _ItemSnapshot) or not snapshot.active.is_set() or snapshot.binding is not binding or
                snapshot.captured is not captured or snapshot.handle != handle or binding.owner != snapshot.owner or
                binding.generation != snapshot.generation or captured[0].page_session_id != snapshot.page or
                _snapshot_home(binding, captured) != snapshot.home):
            raise ValueError()
        return snapshot
    except Exception:
        raise VaultError('Vault unavailable') from None


@contextmanager
def _item_scope(handle):
    existing = _SNAPSHOT.get()
    if existing is not None:
        _snapshot_access(handle)
        yield
        return
    binding, captured = _CTX.get(), _CAPTURE.get()
    if _local_backend(handle) is None:
        raise RuntimeError('Vault unavailable')
    home = _snapshot_home(binding, captured)
    # Copy metadata and secret under the native cross-process lock, then release it
    # before registering values, displaying approval or waiting on the browser.
    with _runtime()._store(home) as (_, store, rows):
        record = next((row for row in rows if row['id'] == handle), None)
        if record is None:
            raise RuntimeError('Vault unavailable')
        meta, secret = store._meta(record), dict(record['secret'])
    if not meta.origin or _runtime()._origin(meta.origin) != meta.origin:
        raise RuntimeError('Vault unavailable')
    saved = _SAVE.get()
    if saved is not None:
        expected = saved.expected
        if (not expected or meta.kind != 'login' or meta.origin != expected[0] or
                meta.identifier != expected[1] or secret.get('password') != expected[2]):
            raise RuntimeError('Vault unavailable')
    from agent.redact import register_vault_redaction_value
    for value in secret.values():
        if value:
            register_vault_redaction_value(value)
    active = threading.Event()
    active.set()
    snapshot = _ItemSnapshot(binding, binding.owner, binding.generation, captured, captured[3],
                             home, handle, meta, MappingProxyType(secret), active)
    token = _SNAPSHOT.set(snapshot)
    try:
        _snapshot_access(handle)
        yield
    finally:
        snapshot.active.clear()
        secret.clear()
        _SNAPSHOT.reset(token)


def _meta(handle):
    backend = _local_backend(handle)
    if backend is None:
        return None, None
    meta = backend.get_meta(handle)
    if meta is None or not meta.origin:
        return None, None
    try:
        if _runtime()._origin(meta.origin) != meta.origin:
            return None, None
    except Exception:
        return None, None
    return backend, meta


def _list():
    binding = _CTX.get()
    if not _authorized(binding):
        return _failure()
    try:
        rows = _runtime().items(Path(binding.owner[3]))
        items = []
        for row in rows:
            item = {'handle': row['id'], 'backend': 'local', 'kind': row['kind'], 'label': row['label'],
                    'origin': row.get('origin'), 'available': bool(row.get('origin'))}
            for key in ('identifier', 'identifier_type'):
                if row.get(key): item[key] = row[key]
            if row.get('has_otp'): item['two_factor'] = 'automatic'
            items.append(item)
        result = {'success': True, 'items': items}
        if not items:
            result['hint'] = 'On the selected login page, offer action=save_login to save the first login through a masked owner prompt.'
        return json.dumps(result)
    except Exception:
        return _failure()


def _fill(original, handle, task_id=None):
    if not _authorized(_CTX.get()) or _CAPTURE.get() is None or _local_backend(handle) is None:
        return _failure()
    try:
        # save_login creates its handle during this action, so its immediate fill
        # takes a fresh snapshot here; ordinary fills already have one at dispatch.
        with _item_scope(handle):
            return original(handle, task_id=task_id)
    except Exception:
        return _failure()


def _save_login(original, label='', task_id=None):
    binding = _CTX.get()
    if not _authorized(binding) or _CAPTURE.get() is None:
        return _failure()
    from agent.vault_backends import unlock
    previous = (unlock.get_unlock_prompt_callback(), unlock.get_save_login_prompt_callback(), unlock.get_code_prompt_callback())
    saved = _SaveInput()
    token = _SAVE.set(saved)
    def ask_login(origin, site):
        raw = _ask(binding, 'vault.save_login', {'origin': origin, 'site': site}, timeout=180)
        try:
            answer = json.loads(raw) if raw else None
            if (not isinstance(answer, dict) or set(answer) != {'identifier', 'password'} or
                    not all(isinstance(answer[k], str) and 0 < len(answer[k]) <= 4096 for k in answer)):
                return None
            from agent.redact import register_vault_redaction_value
            register_vault_redaction_value(answer['password'])
            saved.expected = (origin, answer['identifier'].strip(), answer['password'])
            return answer
        except Exception:
            return None
    try:
        unlock.set_unlock_prompt_callback(lambda *_: '')
        unlock.set_save_login_prompt_callback(ask_login)
        unlock.set_code_prompt_callback(lambda site, hint: _ask(binding, 'vault.code', {'site': site, 'hint': hint}, timeout=180))
        raw = original(label=label, task_id=task_id)
        result = json.loads(raw)
        if result.get('success') is True and not (isinstance(result.get('fill'), dict) and result['fill'].get('success') is True):
            handle = result.get('handle')
            if not isinstance(handle, str) or not re.fullmatch(r'vault_[0-9a-f]{12}', handle):
                return _failure('unconfirmed')
            return json.dumps({'success': False, 'saved': True, 'handle': handle,
                               'error_type': 'fill_unconfirmed', 'error': 'Login saved; fill was not confirmed'})
        return raw
    except Exception:
        return _failure()
    finally:
        saved.expected = None
        _SAVE.reset(token)
        unlock.set_unlock_prompt_callback(previous[0])
        unlock.set_save_login_prompt_callback(previous[1])
        unlock.set_code_prompt_callback(previous[2])


def _controls(task_id, nonce):
    from agent.vault_login_classifier import LoginControl, build_inspection_js
    result = _evaluate(task_id, build_inspection_js(nonce))
    if not result.get('success'):
        return []
    try:
        values = result.get('result')
        if isinstance(values, str): values = json.loads(values)
        if not isinstance(values, list) or len(values) > 512:
            return []
        return [LoginControl.from_dict(row) for row in values if isinstance(row, dict)]
    except Exception:
        return []


def _finish_fill(task_id, origin, nonce, fills):
    from agent.vault_login_classifier import build_fill_js
    result = _evaluate(task_id, build_fill_js(fills, expected_origin=origin, nonce=nonce))
    if not result.get('success'):
        return _failure('unconfirmed')
    try:
        value = result.get('result')
        if isinstance(value, str): value = json.loads(value)
        if not isinstance(value, dict) or value.get('refused') == 'origin_changed':
            return _failure('origin_changed')
        count = value.get('filled')
        if type(count) is not int or count != len(fills):
            return _failure('unconfirmed')
        return json.dumps({'success': True, 'filled_fields': count, 'status': 'needs_human'})
    except Exception:
        return _failure('unconfirmed')


def _otp(handle, task_id=None):
    binding = _CTX.get()
    if not _authorized(binding) or _CAPTURE.get() is None:
        return _failure()
    try:
        from agent.redact import register_vault_redaction_value
        from agent.vault_login_classifier import build_otp_fills, classify_otp_controls
        backend, meta = _meta(handle)
        if backend is None or meta.kind != 'login' or _focus(task_id, meta.origin, 'otp') != meta.origin:
            return _failure('origin_mismatch')
        nonce = secrets.token_hex(8)
        controls = classify_otp_controls(_controls(task_id, nonce))
        if not controls or _focus(task_id, meta.origin, 'otp') != meta.origin or not _authorized(binding):
            return _failure('origin_changed')
        code = backend.resolve_otp(handle)
        if not code:
            code = _ask(binding, 'vault.code', {'site': meta.origin.split('://', 1)[-1], 'hint': ''}, timeout=180)
            code = code.strip().replace(' ', '').replace('-', '')
        if not _authorized(binding):
            return _failure()
        if not isinstance(code, str) or not code or len(code) > 128:
            return _failure('code_declined')
        if _focus(task_id, meta.origin, 'otp') != meta.origin:
            return _failure('origin_changed')
        register_vault_redaction_value(code)
        return _finish_fill(task_id, meta.origin, nonce, build_otp_fills(controls, code))
    except Exception:
        return _failure()


def _signup(handle, task_id=None):
    binding = _CTX.get()
    if not _authorized(binding) or _CAPTURE.get() is None or not signup_ready():
        return _failure()
    try:
        from agent.redact import register_vault_redaction_value
        backend, meta = _meta(handle)
        if backend is None or meta.kind != 'login' or _focus(task_id, meta.origin, 'login') != meta.origin:
            return _failure('origin_mismatch')
        nonce = secrets.token_hex(8)
        controls = _controls(task_id, nonce)
        password_controls = [c for c in controls if c.type.lower() == 'password']
        def intended(control):
            words = ' '.join((control.name, control.label)).lower()
            return 'new-password' in control.autocomplete.lower().split() or bool(re.search(r'\b(new|create|choose|confirm|repeat)\W*(?:a\W+)?password\b', words))
        chosen = [c for c in password_controls if intended(c)]
        if (not 1 <= len(chosen) <= 2 or len(chosen) != len(password_controls) or
                chosen[0].form_index is None or len({c.form_index for c in chosen}) != 1 or
                len({c.index for c in chosen}) != len(chosen)):
            return _failure('form_unavailable')
        if _focus(task_id, meta.origin, 'login') != meta.origin or not _authorized(binding):
            return _failure('origin_changed')
        # The injected owner-controlled admission owns durable one-shot consent; no model args grant it.
        if _STATE['admit_signup'](binding.authority, binding.session_id, handle, meta.origin) is not True:
            return _failure('approval_required')
        if not _authorized(binding) or _focus(task_id, meta.origin, 'login') != meta.origin:
            return _failure('origin_changed')
        password = backend.resolve_password(handle)
        if not isinstance(password, str) or not password:
            return _failure()
        register_vault_redaction_value(password)
        # current-password token forces native JS to recheck type=password at the write.
        fills = [{'index': c.index, 'token': 'current-password', 'value': password} for c in chosen]
        return _finish_fill(task_id, meta.origin, nonce, fills)
    except Exception:
        return _failure()


def _strict_store_read(store):
    try:
        return _runtime()._strict_read(store)
    except Exception:
        from agent.vault_store import VaultError
        raise VaultError('Vault unavailable') from None


def _strict_has_items(store):
    with store._locked():
        return bool(_strict_store_read(store))


def _strict_add(original):
    def add(store, kind, label, secret, origin=None):
        from agent.vault_store import VaultError
        if not isinstance(secret, dict) or any(not isinstance(key, str) or not isinstance(value, str) for key, value in secret.items()):
            raise VaultError('Invalid vault input')
        if kind == 'login' and (not secret.get('password') or not isinstance(secret.get('password'), str)):
            raise VaultError('Invalid vault input')
        try:
            return original(store, kind, label, secret, origin=origin)
        except Exception:
            raise VaultError('Vault operation failed') from None
    return add


def _strict_otp(original):
    def normalize(value):
        from agent.vault_store import VaultError
        try:
            if not isinstance(value, str): raise ValueError()
            result = original(value)
            if result:
                seed = result.split('|', 1)[0]
                if not base64.b32decode(seed + '=' * (-len(seed) % 8), casefold=True): raise ValueError()
            return result
        except Exception:
            raise VaultError('Invalid authenticator key') from None
    return normalize


def _safe_scrub(text, secret):
    return 'Vault operation failed'


def _retain_redaction(value):
    """Keep native per-profile redactions until process exit; refuse rather than evict."""
    if not isinstance(value, str) or not value:
        return
    if len(value) > 4096:
        raise RuntimeError('Vault unavailable')
    from agent import redact
    values = {v for v in (value, value.replace('\r', '').replace('\n', '')) if v}
    with redact._VAULT_REDACTION_LOCK:
        bucket = redact._VAULT_REDACTION_VALUES.setdefault(redact._vault_scope(), {})
        if len(bucket) + len(values.difference(bucket)) > _REDACTION_LIMIT:
            raise RuntimeError('Vault unavailable')
        bucket.update(dict.fromkeys(values))


def _retain_until_exit():
    # Filled pages can outlive a native lock/clear; discarding registrations would leak them.
    return None


def _snapshot_meta(original):
    def get_meta(backend, handle):
        # Other native consumers may still read non-secret metadata normally.
        if _CAPTURE.get() is None and _SNAPSHOT.get() is None:
            return original(backend, handle)
        return _snapshot_access(handle).meta
    return get_meta


def _resolve_secret(kind):
    def resolve(backend, handle):
        from agent.vault_store import VaultError
        try:
            secret = _snapshot_access(handle).secret
            if kind == 'password':
                value = secret.get('password', '')
            elif kind == 'otp':
                from agent.vault_store import totp_now
                seed = secret.get('otp_secret', '')
                value = totp_now(seed) if seed else None
            else:
                value = dict(secret)
            # Stored values were registered at capture; an OTP is newly derived here.
            if kind == 'otp' and value:
                from agent.redact import register_vault_redaction_value
                register_vault_redaction_value(value)
            return value
        except Exception:
            raise VaultError('Vault unavailable') from None
    return resolve


def _dispatch(original, native_handler):
    def dispatch(registry, name, args, *, scope=None, **kwargs):
        if name != 'browser_vault':
            return original(registry, name, args, scope=scope, **kwargs)
        try:
            binding = _CTX.get()
            if (not attested() or registry.get_entry(name, scope=scope) is not _STATE['entry'] or
                    not isinstance(args, dict) or not _authorized(binding) or
                    kwargs.get('task_id') != binding.task_id or
                    kwargs.get('session_id') != getattr(binding.agent, 'session_id', None)):
                return _failure()
            if set(args) - {'action', 'handle', 'label', 'backend'}:
                return _failure()
            action = args.get('action')
            if action == 'list':
                return _list()
            if action not in ('fill', 'save_login', 'enter_code', 'signup_fill'):
                return _failure()
            captured = _capture(binding.task_id, binding)
            if len(captured) != 3 or not captured[0].page_session_id:
                return _failure()
            captured = (*captured, captured[0].page_session_id)
            token = _CAPTURE.set(captured)
            try:
                if action == 'save_login':
                    return native_handler(args, task_id=binding.task_id)
                with _item_scope(args.get('handle')):
                    if action == 'signup_fill':
                        browser = _STATE['browser']
                        return browser._fenced_page_op(binding.task_id, lambda: _signup(args.get('handle'), binding.task_id))
                    return native_handler(args, task_id=binding.task_id)
            finally:
                _CAPTURE.reset(token)
        except Exception:
            return _failure()
    return dispatch


def _patches(state):
    dispatch = state['registry'].dispatch
    return (all(getattr(obj, name, None) is value for obj, name, value in state['patches']) and
            getattr(dispatch, '__self__', None) is state['registry'] and
            getattr(dispatch, '__func__', None) is type(state['registry']).dispatch and
            state['filter'] in state['logger'].filters and
            state['registry'].get_entry('browser_vault') is state['entry'] and
            state['entry'].schema == state['schema'])


def attested():
    try:
        state = _STATE
        if state is None or not _known_source() or not _patches(state):
            return False
        from gateway import run_api
        return run_api._ACTIVE_RUNNER is state['runner'] and state['runner'] is not None
    except Exception:
        return False


def signup_ready():
    return attested() and callable(_STATE.get('admit_signup'))


def install(authorize_owner, admit_signup=None):
    """Install pinned, process-wide guards once; per-turn authority stays in a ContextVar."""
    global _STATE
    if ('hermes_bootstrap' not in sys.modules or not callable(authorize_owner) or
            admit_signup is not None and not callable(admit_signup) or not _known_source()):
        return False
    with _LOCK:
        if _STATE is not None:
            return (_STATE['authorize'] is authorize_owner and _STATE['admit_signup'] is admit_signup and attested())
        try:
            # The owner gateway and its WS/registry must already be running; never bootstrap a runtime here.
            names = ('gateway.run_api', 'gateway.session_sudo_prompt', 'gateway.session_server_requests', 'tools.registry', 'tui_gateway.ws')
            loaded = [sys.modules.get(name) for name in names]
            if any(module is None for module in loaded):
                return False
            run_api, session_sudo_prompt, session_server_requests, registry_module, ws = loaded
            existing_names = ('gateway.run_turn_runner', 'gateway.run_turn_progress', 'gateway.session_identity',
                              'gateway.session_authority', 'gateway.session_contract', 'gateway.session_controls',
                              'gateway.session', 'gateway.config', 'tui_gateway.transport', 'hermes_constants')
            if any(sys.modules.get(name) is None for name in existing_names):
                return False
            vault_store = importlib.import_module('agent.vault_store')
            local = importlib.import_module('agent.vault_backends.local')
            backends = importlib.import_module('agent.vault_backends')
            browser = importlib.import_module('tools.browser_vault_tool')
            # The existing gateway is bootstrapped already; verify every module this bridge invokes.
            checked = {name: importlib.import_module(name) for name in (
                'agent.vault_login_classifier', 'agent.vault_backends.unlock', 'agent.vault_backends.base', 'agent.redact',
                'tools.thread_context', 'tools.browser_supervisor', 'tools.browser_supervisor_capture', 'tools.browser_tool',
                'tools.browser_tool_session', 'tools.approval_context', 'hermes_cli.config', 'hermes_cli.tools_config',
                'agent.skill_utils', 'model_tools', 'toolsets')}
            checked.update({name: sys.modules[name] for name in existing_names})
            root = _source_root().resolve()
            redact = checked['agent.redact']
            for name, module in checked.items():
                relative = name.replace('.', '/') + '.py'
                if Path(module.__file__).resolve() != (root / relative).resolve():
                    return False
            # Check optional browser egress modules if the native process already loaded them.
            for name in ('tools.browser_tool_snapshot', 'tools.browser_cdp_tool', 'tools.browser_use_cli'):
                module = sys.modules.get(name)
                if module is not None and Path(module.__file__).resolve() != (root / (name.replace('.', '/') + '.py')).resolve():
                    return False
            for module, relative in ((run_api, 'gateway/run_api.py'), (session_sudo_prompt, 'gateway/session_sudo_prompt.py'),
                                     (session_server_requests, 'gateway/session_server_requests.py'), (registry_module, 'tools/registry.py'),
                                     (ws, 'tui_gateway/ws.py'), (vault_store, 'agent/vault_store.py'),
                                     (local, 'agent/vault_backends/local.py'), (backends, 'agent/vault_backends/__init__.py'),
                                     (browser, 'tools/browser_vault_tool.py')):
                if Path(module.__file__).resolve() != (root / relative).resolve():
                    return False
            runner = run_api._ACTIVE_RUNNER
            if runner is None:
                return False
            original_scope = session_sudo_prompt.sudo_prompt_scope
            original_resolve = session_server_requests.resolve
            original_may_settle = session_server_requests.may_settle
            original_add = vault_store.VaultStore.add_item
            original_normalize = vault_store.normalize_otp_secret
            original_fill = browser.browser_vault_fill
            original_save = browser.browser_vault_save_login
            original_payment = browser._confirm_payment_fill
            authority_class = checked['gateway.session_authority'].SessionAuthority
            original_dispatch = registry_module.ToolRegistry.dispatch
            native_handler = browser._handle_vault
            registry = registry_module.registry
            entry = registry.get_entry('browser_vault')
            if entry is None or entry.toolset != 'browser':
                return False
            schema = deepcopy(entry.schema)
            actions = schema['parameters']['properties']['action']['enum']
            actions.remove('unlock')
            if 'signup_fill' not in actions: actions.append('signup_fill')
            schema['parameters']['properties'].pop('backend', None)
            schema['description'] = (
                'Use saved local logins, cards and addresses on the selected browser page without secrets entering the conversation. '
                'list returns safe handles and login identifiers; fill uses a matching-origin handle and requires native consent for cards; '
                'save_login asks the owner through a masked prompt; enter_code uses a saved authenticator or secure owner prompt; '
                'signup_fill uses an already approved generated login and reports an unverified result. '
                'Select the matching form tab before invoking: each action stays on its pinned page. External password-manager unlock is unavailable. '
                'Never type, ask for or repeat a password, card number, CVC or code. Stop on save_declined; never retry payment_declined.'
            )
            patches = [
                (session_sudo_prompt, 'sudo_prompt_scope', _turn_scope(original_scope)),
                (session_server_requests, 'resolve', _guard_resolve(original_resolve)),
                (session_server_requests, 'may_settle', _guard_may_settle(original_may_settle)),
                (authority_class, 'respond', _guard_payment_response(authority_class.respond)),
                (vault_store.VaultStore, '_read_all', _strict_store_read),
                (vault_store.VaultStore, 'has_items', _strict_has_items),
                (vault_store.VaultStore, 'add_item', _strict_add(original_add)),
                (vault_store, 'normalize_otp_secret', _strict_otp(original_normalize)),
                (vault_store, 'scrub_secret_from_text', _safe_scrub),
                (redact, 'register_vault_redaction_value', _retain_redaction),
                (redact, 'clear_vault_redaction_values', _retain_until_exit),
                (local.LocalLoginBackend, 'get_meta', _snapshot_meta(local.LocalLoginBackend.get_meta)),
                (local.LocalLoginBackend, 'resolve_password', _resolve_secret('password')),
                (local.LocalLoginBackend, 'resolve_otp', _resolve_secret('otp')),
                (local.LocalLoginBackend, 'resolve_secret', _resolve_secret('secret')),
                (backends, 'backend_for_handle', _local_backend),
                (browser, 'browser_vault_list', _list),
                (browser, 'browser_vault_fill', lambda handle, task_id=None: _fill(original_fill, handle, task_id)),
                (browser, 'browser_vault_save_login', lambda label='', task_id=None: _save_login(original_save, label, task_id)),
                (browser, 'browser_vault_enter_code', _otp),
                (browser, '_eval_js', _evaluate),
                (browser, '_eval_js_secret', _evaluate),
                (browser, '_focus_bound_origin', _focus),
                (browser, '_current_page_origin', _current_origin),
                (browser, '_confirm_payment_fill', _guard_payment_confirmation(original_payment)),
                (registry_module.ToolRegistry, 'dispatch', _dispatch(original_dispatch, native_handler)),
                (entry, 'schema', schema),
            ]
            originals = [(obj, name, getattr(obj, name)) for obj, name, _ in patches]
            guard = _FrameFilter()
            try:
                ws._log.addFilter(guard)
                for obj, name, value in patches:
                    setattr(obj, name, value)
                with registry._lock: registry._generation += 1
                _STATE = {'authorize': authorize_owner, 'admit_signup': admit_signup, 'runner': runner,
                          'patches': patches, 'filter': guard, 'logger': ws._log, 'browser': browser,
                          'registry': registry, 'entry': entry, 'schema': deepcopy(schema)}
                if not attested():
                    raise RuntimeError('Vault unavailable')
                return True
            except Exception:
                for obj, name, old in reversed(originals): setattr(obj, name, old)
                ws._log.removeFilter(guard)
                _STATE = None
                return False
        except Exception:
            return False
