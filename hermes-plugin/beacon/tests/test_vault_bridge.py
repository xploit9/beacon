"""Session-bound native Vault bridge tests. All homes and credentials are synthetic."""
import asyncio
import contextlib
import contextvars
import importlib.util
import json
import os
from pathlib import Path
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]
NATIVE = Path(os.environ['HERMES_AGENT_SOURCE']) if 'HERMES_AGENT_SOURCE' in os.environ else Path.home() / 'AppData/Local/hermes/hermes-agent'
TEST_EMAIL = 'qa' + chr(64) + 'example.test'


def load_bridge(monkeypatch):
    path = ROOT / 'vault_bridge.py'
    assert path.is_file(), 'Vault bridge has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_vault_bridge', path)
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


def test_runtime_loader_removes_failed_module_and_allows_a_fresh_load(monkeypatch):
    bridge = load_bridge(monkeypatch)
    monkeypatch.setitem(sys.modules, 'beacon_vault_runtime', None)
    monkeypatch.delitem(sys.modules, 'beacon_vault_runtime', raising=False)
    attempts = []
    class Loader:
        def create_module(self, spec): return None
        def exec_module(self, module):
            attempts.append(module)
            if len(attempts) == 1: raise OSError('synthetic import failure')
            module.ready = True
    spec = importlib.util.spec_from_loader('beacon_vault_runtime', Loader())
    monkeypatch.setattr(importlib.util, 'spec_from_file_location', lambda *a, **kw: spec)
    with pytest.raises(OSError): bridge._runtime()
    assert 'beacon_vault_runtime' not in sys.modules
    loaded = bridge._runtime()
    assert loaded.ready and bridge._runtime() is loaded and len(attempts) == 2


def test_runtime_loader_never_publishes_a_partial_module_to_another_caller(monkeypatch):
    bridge = load_bridge(monkeypatch)
    monkeypatch.setitem(sys.modules, 'beacon_vault_runtime', None)
    monkeypatch.delitem(sys.modules, 'beacon_vault_runtime', raising=False)
    entered, release, second_entered, second_done = (threading.Event() for _ in range(4))
    loads = []
    class Loader:
        def create_module(self, spec): return None
        def exec_module(self, module):
            loads.append(module); entered.set()
            assert release.wait(3)
            module.ready = True
    spec = importlib.util.spec_from_loader('beacon_vault_runtime', Loader())
    monkeypatch.setattr(importlib.util, 'spec_from_file_location', lambda *a, **kw: spec)
    def other():
        second_entered.set()
        try: return bridge._runtime()
        finally: second_done.set()
    with ThreadPoolExecutor(max_workers=2) as pool:
        first = pool.submit(bridge._runtime)
        assert entered.wait(3)
        second = pool.submit(other)
        try:
            assert second_entered.wait(3)
            assert not second_done.wait(.1)
        finally: release.set()
        assert first.result(timeout=3) is second.result(timeout=3)
        assert first.result().ready and len(loads) == 1


@pytest.fixture
def native(tmp_path, monkeypatch):
    assert (NATIVE / 'agent/vault_store.py').is_file()
    monkeypatch.syspath_prepend(str(NATIVE))
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    return tmp_path, monkeypatch


def authority_for(home, sid='session-a', owner='owner-a', provider='basic'):
    from gateway.config import Platform
    from gateway.session import SessionSource
    from gateway.session_identity import authenticated_subject
    from gateway.session_authority import SessionAuthority, LiveSession
    from gateway.session_contract import Principal
    subject = authenticated_subject({'provider': provider, 'user_id': owner})
    source = SessionSource(platform=Platform.LOCAL, chat_id=sid, user_id=subject, chat_type='dm')
    runner = SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False), session_authorities=None)
    authority = SessionAuthority(runner, profile_id=str(home), instance_id='test-instance', db=None, epoch=1)
    live = LiveSession(source, 'test-route')
    authority.sessions[sid] = live
    authority.executing[sid] = 7
    actor = Principal(subject, str(home), frozenset({'session:read', 'session:respond'}), 'transport-a')
    live.subscribers['subscription-a'] = actor
    runner.session_authority = authority
    turn = SimpleNamespace(_approval_owner=(authority, sid, 7), _ctx=SimpleNamespace(source=source, session_id=sid))
    agent = SimpleNamespace(session_id=sid, _interrupt_requested=False)
    return authority, live, actor, runner, turn, agent


class Sink:
    def __init__(self): self.frames = []; self.changed = threading.Event()
    def write(self, frame): self.frames.append(frame); self.changed.set(); return True
    def close(self): pass


@pytest.fixture
def installed(native, monkeypatch):
    home, _ = native
    bridge = load_bridge(monkeypatch)
    monkeypatch.setitem(sys.modules, 'hermes_bootstrap', SimpleNamespace(_happy_eyeballs_create_connection=lambda *a, **k: None))
    authority, live, actor, runner, turn, agent = authority_for(home)
    from gateway import (run_api, session_sudo_prompt, session_server_requests, session_authority,
                         run_turn_runner, run_turn_progress, session_identity, session_contract,
                         session_controls, session, config)
    from agent import vault_store
    from agent import redact
    from agent.vault_backends import local
    import agent.vault_backends as backends
    from tools import browser_vault_tool, registry
    from tui_gateway import ws
    monkeypatch.setattr(run_api, '_ACTIVE_RUNNER', runner)
    # Register original values for pytest teardown before install patches native symbols.
    for obj, names in ((session_sudo_prompt, ['sudo_prompt_scope']),
                       (session_server_requests, ['resolve', 'may_settle']),
                       (session_authority.SessionAuthority, ['respond']),
                       (vault_store, ['normalize_otp_secret', 'scrub_secret_from_text']),
                       (redact, ['register_vault_redaction_value', 'clear_vault_redaction_values']),
                       (vault_store.VaultStore, ['_read_all', 'has_items', 'add_item']),
                       (local.LocalLoginBackend, ['get_meta', 'resolve_password', 'resolve_otp', 'resolve_secret']),
                       (backends, ['backend_for_handle']),
                       (browser_vault_tool, ['browser_vault_list', 'browser_vault_fill', 'browser_vault_save_login',
                                             'browser_vault_enter_code', '_eval_js', '_eval_js_secret', '_focus_bound_origin', '_current_page_origin', '_confirm_payment_fill']),
                       (registry.ToolRegistry, ['dispatch'])):
        for name in names: monkeypatch.setattr(obj, name, getattr(obj, name))
    monkeypatch.setattr(ws._log, 'filters', list(ws._log.filters))
    monkeypatch.setattr(redact, '_VAULT_REDACTION_VALUES', {})
    monkeypatch.setattr(browser_vault_tool, 'BROWSER_VAULT_SCHEMA', json.loads(json.dumps(browser_vault_tool.BROWSER_VAULT_SCHEMA)))
    entry = registry.registry.get_entry('browser_vault')
    monkeypatch.setattr(entry, 'schema', json.loads(json.dumps(entry.schema)))
    monkeypatch.setattr(registry.registry, '_generation', registry.registry._generation)
    assert bridge._known_source(), 'Pinned source mismatch'
    assert all(name in sys.modules for name in ('gateway.run_api', 'gateway.session_sudo_prompt', 'gateway.session_server_requests', 'tools.registry', 'tui_gateway.ws'))
    assert bridge.install(lambda auth, session: auth is authority and session == 'session-a',
                          lambda auth, session, handle, origin: False)
    return bridge, authority, live, actor, runner, turn, agent


def attach_sink(authority, live, actor):
    sink = Sink(); authority.events[actor.transport_id] = sink; live.event_stream.fanout.attach(sink)
    return sink


def test_request_owner_uses_native_authenticated_session_only(native, monkeypatch):
    home, _ = native; bridge = load_bridge(monkeypatch)
    authority, live, _, _, _, _ = authority_for(home)
    assert bridge.request_owner(authority, 'session-a') == ('owner-a', 'basic', '', str(home))
    live.source.user_id = 'local-bootstrap-id'
    assert bridge.request_owner(authority, 'session-a') is None
    from gateway.config import Platform
    live.source.platform = Platform.TELEGRAM
    assert bridge.request_owner(authority, 'session-a') is None
    assert bridge.request_owner(authority, 'missing') is None


def test_install_fails_closed_on_unknown_native_source(native, monkeypatch):
    bridge = load_bridge(monkeypatch)
    monkeypatch.setitem(bridge.NATIVE_HASHES, 'agent/vault_store.py', '0' * 64)
    assert bridge.install(lambda *_: True) is False
    assert bridge.attested() is False


def test_install_does_not_import_a_missing_gateway_module(native, monkeypatch):
    bridge = load_bridge(monkeypatch)
    monkeypatch.setitem(sys.modules, 'hermes_bootstrap', SimpleNamespace())
    monkeypatch.delitem(sys.modules, 'gateway.run_api', raising=False)
    assert bridge.install(lambda *_: True) is False
    assert 'gateway.run_api' not in sys.modules


def test_install_does_not_import_a_missing_gateway_dependency(installed, monkeypatch):
    bridge, *_ = installed
    second = load_bridge(monkeypatch)
    missing = 'gateway.session_controls'
    monkeypatch.delitem(sys.modules, missing, raising=False)
    original = second.importlib.import_module
    attempted = []
    def observed(name, *args, **kwargs):
        if name == missing:
            attempted.append(name)
            raise ImportError('synthetic missing module')
        return original(name, *args, **kwargs)
    monkeypatch.setattr(second.importlib, 'import_module', observed)
    assert second.install(lambda *_: True) is False
    assert not attempted and missing not in sys.modules


def test_real_session_context_reaches_native_thread_worker_and_expires(installed):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from gateway import session_sudo_prompt
    from tools.thread_context import propagate_context_to_thread
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        work = propagate_context_to_thread(lambda: registry.dispatch('browser_vault', {'action': 'list'},
                                                                        task_id='session-a', session_id='session-a'))
        with ThreadPoolExecutor(max_workers=1) as pool:
            result = json.loads(pool.submit(work).result(timeout=5))
        assert result['success'] is True and result['items'] == []
    assert json.loads(work())['success'] is False


def test_distinct_sessions_do_not_borrow_owner_or_context(installed):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        answer = json.loads(registry.dispatch('browser_vault', {'action': 'list'}, task_id='foreign', session_id='foreign'))
        assert answer['success'] is False
        authority.executing['session-a'] = 8
        assert json.loads(registry.dispatch('browser_vault', {'action': 'list'}, task_id='session-a', session_id='session-a'))['success'] is False


def test_attestation_rejects_an_instance_dispatch_override(installed, monkeypatch):
    bridge, *_ = installed
    from tools.registry import registry
    assert bridge.attested()
    monkeypatch.setitem(vars(registry), 'dispatch', lambda *args, **kwargs: '{"success":true}')
    assert not bridge.attested()


def test_vault_dispatch_respects_the_native_scoped_registry(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    original = registry.get_entry
    monkeypatch.setattr(registry, 'get_entry', lambda name, *, scope=None: None if scope == 'restricted' else original(name, scope=scope))
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'list'}, scope='restricted', task_id='session-a', session_id='session-a'))
    assert result['success'] is False


def test_parallel_native_prompts_cannot_cross_sessions(installed, tmp_path):
    bridge, authority_a, live_a, actor_a, runner, turn_a, agent_a = installed
    authority_b, live_b, actor_b, _, turn_b, agent_b = authority_for(tmp_path / 'other-profile', sid='session-b', owner='owner-b')
    sink_a = attach_sink(authority_a, live_a, actor_a)
    sink_b = attach_sink(authority_b, live_b, actor_b)
    bridge._STATE['authorize'] = lambda auth, sid: (auth, sid) in ((authority_a, 'session-a'), (authority_b, 'session-b'))
    from gateway import session_sudo_prompt, session_server_requests as requests
    def run(turn, agent):
        with session_sudo_prompt.sudo_prompt_scope(turn, agent):
            return bridge._ask(bridge._CTX.get(), 'vault.code', {'site':'fixture', 'hint':''}, timeout=5)
    with ThreadPoolExecutor(max_workers=2) as pool:
        future_a, future_b = pool.submit(run, turn_a, agent_a), pool.submit(run, turn_b, agent_b)
        assert sink_a.changed.wait(3) and sink_b.changed.wait(3)
        frame_a = next(x for x in sink_a.frames if x.get('method') == 'vault.code')
        frame_b = next(x for x in sink_b.frames if x.get('method') == 'vault.code')
        response_a = {'id':frame_a['id'], 'result':{'value':'111222'}}
        response_b = {'id':frame_b['id'], 'result':{'value':'333444'}}
        assert not requests.resolve(response_a, session_ok=lambda sid, events: requests.may_settle(authority_b, actor_b, sid, events))
        assert not requests.resolve(response_b, session_ok=lambda sid, events: requests.may_settle(authority_a, actor_a, sid, events))
        assert requests.resolve(response_a, session_ok=lambda sid, events: requests.may_settle(authority_a, actor_a, sid, events))
        assert requests.resolve(response_b, session_ok=lambda sid, events: requests.may_settle(authority_b, actor_b, sid, events))
        assert future_a.result(timeout=3) == '111222' and future_b.result(timeout=3) == '333444'


def test_malformed_websocket_payload_is_not_logged(installed, caplog):
    from tui_gateway import ws
    marker = 'synthetic-password-that-must-not-be-logged'
    ws._log.warning('ws parse error peer=%s index=%d error=%s payload=%r', 'fixture', 1, ValueError('bad JSON'), marker)
    assert marker not in caplog.text


def test_actual_websocket_rejects_malformed_secret_without_logging_it(installed, monkeypatch, caplog):
    from tui_gateway import ws
    marker = 'synthetic-secret-in-invalid-json'
    monkeypatch.setattr(ws, '_note_dashboard_client_activity', lambda **kw: None)
    monkeypatch.setattr(ws, '_authority_connection', lambda *a: None)
    monkeypatch.setattr(ws, '_start_backend_liveness', lambda: None)
    for name in ('_ensure_skin_watcher', '_ensure_lease_watcher', 'register_live_transport',
                 'unregister_live_transport', '_release_wake_for_transport'):
        monkeypatch.setattr(ws.server, name, lambda *a, **kw: None)
    monkeypatch.setattr(ws.server, 'resolve_skin', lambda: {})
    monkeypatch.setattr(ws.server, '_close_sessions_for_transport', lambda *a, **kw: (0, 0))
    class Socket:
        scope = {}
        client = None
        def __init__(self): self.frames = []; self.sent = False
        async def accept(self): pass
        async def close(self, **kw): pass
        async def send_text(self, text): self.frames.append(json.loads(text))
        async def receive_text(self):
            if not self.sent:
                self.sent = True
                return '{"result":{"value":"' + marker
            raise ws._WebSocketDisconnect(code=1000)
    socket = Socket()
    asyncio.run(ws.handle_ws(socket))
    assert any(frame.get('error', {}).get('code') == -32700 for frame in socket.frames)
    assert marker not in caplog.text


def test_redaction_retains_earlier_secrets_and_refuses_capacity(installed, monkeypatch):
    bridge, *_ = installed
    from agent import redact
    from tools.browser_cdp_tool import _redact_cdp_output
    monkeypatch.setattr(bridge, '_REDACTION_LIMIT', 70, raising=False)
    values = [f'synthetic-secret-{i:03d}-value' for i in range(70)]
    for value in values:
        redact.register_vault_redaction_value(value)
    redact.clear_vault_redaction_values()
    output = json.dumps(_redact_cdp_output({'result': values[0]}))
    assert values[0] not in output
    with pytest.raises(Exception):
        redact.register_vault_redaction_value('one-too-many-synthetic-secret')
    assert values[-1] not in redact.redact_sensitive_text(values[-1], force=True)


def test_capabilities_follow_attested_guards_without_opening_vault(installed, monkeypatch):
    bridge, authority, *_ = installed
    runtime = bridge._runtime()
    assert runtime.capabilities() == {'supported': True, 'local': True, 'signup': True, 'otp': True, 'secure_prompts': True}
    assert not (Path(authority.profile_id) / 'vault').exists()
    from tui_gateway import ws
    ws._log.removeFilter(bridge._STATE['filter'])
    assert bridge.attested() is False
    assert runtime.capabilities()['secure_prompts'] is False
    assert runtime.capabilities()['signup'] is False and runtime.capabilities()['otp'] is False


@pytest.mark.parametrize('remote', [False, True])
def test_browser_toolset_disabled_keeps_storage_but_disables_secure_browser_capabilities(installed, monkeypatch, remote):
    bridge, authority, *_ = installed
    runtime = bridge._runtime()
    from hermes_cli import config, tools_config
    monkeypatch.setattr(config, 'load_config_readonly', lambda: {'agent':{'disabled_toolsets':['browser']}})
    monkeypatch.setattr(tools_config, '_get_platform_tools', lambda *a, **kw: set())
    if remote:
        monkeypatch.setattr(runtime, '_gateway_attestor', lambda: {'secure_prompts':True, 'otp':True, 'signup':True})
    assert runtime.capabilities() == {'supported':True, 'local':True, 'signup':False, 'otp':False, 'secure_prompts':False}
    assert not (Path(authority.profile_id) / 'vault').exists()


def test_otp_input_requires_attestation_and_preserves_untouched_seed(installed):
    bridge, authority, *_ = installed
    runtime = bridge._runtime(); home = Path(authority.profile_id)
    secret = {'identifier_type':'email', 'identifier':TEST_EMAIL, 'password':'synthetic', 'otp_secret':'JBSWY3DPEHPK3PXP'}
    item = runtime.add(home, {'kind':'login', 'label':'OTP', 'origin':'https://example.test', 'secret':secret})
    assert item['has_otp'] is True and 'JBSWY3DPEHPK3PXP' not in json.dumps(item)
    runtime.replace(home, item['id'], {'label':'OTP', 'origin':'https://example.test', 'secret':{'password':'changed'}})
    assert runtime.items(home)[0]['has_otp'] is True
    runtime.replace(home, item['id'], {'label':'OTP', 'origin':'https://example.test', 'secret':{'otp_secret':''}})
    assert not runtime.items(home)[0].get('has_otp', False)
    secret['otp_secret'] = 'A'
    with pytest.raises(runtime.InvalidInput):
        runtime.add(home, {'kind':'login', 'label':'Bad', 'origin':'https://example.test', 'secret':secret})


def test_nondefault_totp_parameters_survive_write_and_password_patch(installed):
    bridge, authority, *_ = installed
    runtime = bridge._runtime(); home = Path(authority.profile_id)
    seed_uri = 'otpauth://totp/fixture?secret=JBSWY3DPEHPK3PXP&digits=8&period=60&algorithm=SHA256'
    secret = {'identifier_type':'email', 'identifier':TEST_EMAIL, 'password':'synthetic', 'otp_secret':seed_uri}
    item = runtime.add(home, {'kind':'login', 'label':'OTP', 'origin':'https://example.test', 'secret':secret})
    runtime.replace(home, item['id'], {'label':'OTP', 'origin':'https://example.test', 'secret':{'password':'changed'}})
    with runtime._store(home) as (_, _, rows):
        assert rows[0]['secret']['otp_secret'] == 'JBSWY3DPEHPK3PXP|8|60|SHA256'


class FakeCapture:
    def __init__(self, origin='https://example.test', controls=None):
        self.origin = origin
        self.controls = controls or []
        self.page_session_id = 'page-a'
        self._supervisor = object()
        self.writes = []
        self.before_write = None
    def call(self, method, params, *, session_id, timeout, before_send):
        assert method == 'Runtime.evaluate' and session_id == self.page_session_id
        before_send()
        expression = params['expression']
        if expression == 'window.location.origin': value = self.origin
        elif expression == 'window.location.href': value = self.origin + '/login'
        elif 'const elements = Array.from' in expression: value = json.dumps(self.controls)
        elif 'const fills =' in expression:
            if self.before_write: self.before_write()
            expected = json.JSONDecoder().raw_decode(expression.split('const expectedOrigin = ', 1)[1])[0]
            fills = json.JSONDecoder().raw_decode(expression.split('const fills = ', 1)[1])[0]
            if self.origin != expected:
                value = json.dumps({'refused': 'origin_changed', 'found': self.origin})
            else:
                self.writes.extend(fills)
                value = json.dumps({'filled': len(fills)})
        else: raise AssertionError('Unexpected browser evaluation')
        return {'result': {'result': {'value': value}}}


def fake_browser(bridge, binding_home, monkeypatch, *, origin='https://example.test', controls=None):
    fake = FakeCapture(origin, controls)
    from hermes_constants import hermes_home_key
    registry = SimpleNamespace(_lock=threading.RLock(), _by_task={'session-a': fake._supervisor},
                               _home_by_task={'session-a': hermes_home_key(binding_home)})
    monkeypatch.setattr(bridge, '_capture', lambda task, binding: (fake, registry, hermes_home_key(binding_home)))
    return fake


def password_control(index=0, autocomplete='current-password', label='Password', form=0):
    return {'autocomplete': autocomplete, 'formIndex': form, 'index': index, 'label': label,
            'name': label, 'type': 'password', 'maxLength': None}


def otp_control(index=0):
    return {'autocomplete': 'one-time-code', 'formIndex': 0, 'index': index, 'label': 'Verification code',
            'name': 'otp', 'type': 'text', 'maxLength': None}


def invoke_in_worker(call):
    from tools.thread_context import propagate_context_to_thread
    pool = ThreadPoolExecutor(max_workers=1)
    future = pool.submit(propagate_context_to_thread(call))
    return pool, future


def test_native_store_guards_corruption_short_errors_and_bad_totp(installed, tmp_path):
    bridge, *_ = installed
    from agent.vault_store import VaultStore, VaultError, normalize_otp_secret, scrub_secret_from_text
    store = VaultStore(tmp_path / 'guarded-vault')
    store._base.mkdir(); store._vault_path.write_bytes(b'')
    with pytest.raises(VaultError): store.has_items()
    with pytest.raises(VaultError): store.add_item('login', 'Example', {'identifier_type': 'email', 'identifier': TEST_EMAIL, 'password': 'p'}, origin='https://example.test')
    assert store._vault_path.read_bytes() == b'' and not store._key_path.exists()
    assert '7' not in scrub_secret_from_text('secret=7', {'password': '7'})
    with pytest.raises(VaultError): normalize_otp_secret('A')


def test_capture_rejects_a_supervisor_owned_by_another_profile(installed, monkeypatch, tmp_path):
    bridge, authority, live, actor, runner, turn, agent = installed
    from tools import browser_supervisor
    from hermes_constants import hermes_home_key
    fake = FakeCapture()
    registry = SimpleNamespace(_lock=threading.RLock(), _by_task={'session-a': fake._supervisor},
                               _home_by_task={'session-a': hermes_home_key(tmp_path / 'foreign')},
                               capture=lambda task: fake)
    monkeypatch.setattr(browser_supervisor, 'SUPERVISOR_REGISTRY', registry)
    binding = bridge._Binding(turn, agent)
    with pytest.raises(RuntimeError): bridge._capture('session-a', binding)


def test_native_owner_prompt_cannot_be_answered_by_a_foreign_operator(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    sink = attach_sink(authority, live, actor)
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[password_control()])
    from gateway import session_sudo_prompt, session_server_requests as requests
    from gateway.session_contract import Principal
    from tools.registry import registry
    monkeypatch.setenv('HERMES_SESSION_PLATFORM', '')
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        pool, future = invoke_in_worker(lambda: registry.dispatch('browser_vault', {'action': 'save_login'}, task_id='session-a', session_id='session-a'))
        try:
            assert sink.changed.wait(5)
            frame = next(row for row in sink.frames if row.get('method') == 'vault.save_login')
            foreign = Principal('different-owner', authority.profile_id, frozenset({'session:respond','session:operator'}), 'foreign')
            live.subscribers['foreign'] = foreign
            response = {'jsonrpc':'2.0', 'id':frame['id'], 'result':{'value':json.dumps({'identifier':TEST_EMAIL,'password':'synthetic-login-pass'})}}
            assert not requests.resolve(response, session_ok=lambda sid, events: requests.may_settle(authority, foreign, sid, events))
            del live.subscribers['foreign']
            assert not requests.resolve(response, session_ok=lambda sid, events: True)
            from gateway.session_controls import AuthorityConnection
            connection = AuthorityConnection.__new__(AuthorityConnection)
            connection.authority, connection.actor = authority, actor
            connection._resolve_response(response)
            assert not requests.resolve(response, session_ok=lambda sid, events: True)
            answer = json.loads(future.result(timeout=5))
            assert answer['success'] is True and answer['fill']['success'] is True
            assert 'synthetic-login-pass' not in json.dumps(answer)
            assert len(fake.writes) == 1 and fake.writes[0]['value'] == 'synthetic-login-pass'
        finally: pool.shutdown(wait=True, cancel_futures=True)


def test_cancelled_turn_cannot_consume_a_late_secret_response(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    sink = attach_sink(authority, live, actor)
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[password_control()])
    from gateway import session_sudo_prompt, session_server_requests as requests
    from tools.registry import registry
    monkeypatch.setenv('HERMES_SESSION_PLATFORM', '')
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        pool, future = invoke_in_worker(lambda: registry.dispatch('browser_vault', {'action':'save_login'}, task_id='session-a', session_id='session-a'))
        try:
            assert sink.changed.wait(5)
            frame = next(row for row in sink.frames if row.get('method') == 'vault.save_login')
            agent._interrupt_requested = True
            response = {'jsonrpc':'2.0','id':frame['id'],'result':{'value':json.dumps({'identifier':TEST_EMAIL,'password':'synthetic-pass'})}}
            assert not requests.resolve(response, session_ok=lambda sid, events: requests.may_settle(authority, actor, sid, events))
            answer = json.loads(future.result(timeout=5))
            assert answer['success'] is False and not fake.writes
        finally: pool.shutdown(wait=True, cancel_futures=True)


def test_payment_approval_requires_the_session_owner(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from gateway import session_sudo_prompt
    from gateway.session_contract import Principal, SessionRef
    from hermes_state_runtime import RuntimeStoreError
    foreign = Principal('another-subject', authority.profile_id, frozenset({'session:approve', 'session:operator'}), 'foreign')
    live.subscribers['foreign'] = foreign
    monkeypatch.setattr(authority, 'authorize', lambda *args: None)
    monkeypatch.setattr(authority, 'check_approval_generation', lambda *args: None)
    monkeypatch.setattr(live.controls, 'respond', lambda *args, **kw: 'accepted')
    started = threading.Event(); release = threading.Event()
    def ask(*args):
        started.set(); return release.wait(3)
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        token = bridge._CAPTURE.set(object())
        pool, future = invoke_in_worker(lambda: bridge._guard_payment_confirmation(ask)('Card', 'https://example.test'))
        try:
            assert started.wait(3)
            ref = SessionRef(authority.profile_id, 'session-a')
            with pytest.raises(RuntimeStoreError):
                asyncio.run(authority.respond(foreign, ref, 7, 'prompt-a', 'once'))
            assert asyncio.run(authority.respond(actor, ref, 7, 'prompt-a', 'once')) == 'accepted'
        finally:
            release.set(); assert future.result(timeout=3) is True; pool.shutdown(wait=True)
            bridge._CAPTURE.reset(token)


def test_native_payment_fill_requires_approval_and_hides_card(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    runtime = bridge._runtime(); home = Path(authority.profile_id)
    card = {'card_number':'4111111111111111', 'exp_month':'12', 'exp_year':'2030', 'cvc':'987'}
    item = runtime.add(home, {'kind':'payment', 'label':'Card', 'origin':'https://example.test', 'secret':card})
    controls = [{'autocomplete':'cc-number', 'formIndex':0, 'index':0, 'label':'Card number', 'name':'card', 'type':'text', 'maxLength':None}]
    fake = fake_browser(bridge, home, monkeypatch, controls=controls)
    from tools import approval_prompt
    from tools.registry import registry
    from gateway import session_sudo_prompt
    choices = iter(('decline', 'accept'))
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', lambda *a, **kw: next(choices))
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        args = {'action':'fill', 'handle':item['id']}
        denied = json.loads(registry.dispatch('browser_vault', args, task_id='session-a', session_id='session-a'))
        assert denied['success'] is False and not fake.writes
        accepted = json.loads(registry.dispatch('browser_vault', args, task_id='session-a', session_id='session-a'))
    assert accepted['success'] is True and card['card_number'] not in json.dumps(accepted)
    assert len(fake.writes) == 1 and fake.writes[0]['value'] == card['card_number']


def test_save_acknowledges_saved_but_failed_fill_without_claiming_success(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    sink = attach_sink(authority, live, actor)
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[password_control()])
    fake.before_write = lambda: setattr(fake, 'origin', 'https://different.test')
    from gateway import session_sudo_prompt, session_server_requests as requests
    from tools.registry import registry
    monkeypatch.setenv('HERMES_SESSION_PLATFORM', '')
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        pool, future = invoke_in_worker(lambda: registry.dispatch('browser_vault', {'action':'save_login'}, task_id='session-a', session_id='session-a'))
        try:
            assert sink.changed.wait(5)
            frame = next(row for row in sink.frames if row.get('method') == 'vault.save_login')
            response = {'jsonrpc':'2.0','id':frame['id'],'result':{'value':json.dumps({'identifier':TEST_EMAIL,'password':'synthetic-failed-fill'})}}
            assert requests.resolve(response, session_ok=lambda sid, events: requests.may_settle(authority, actor, sid, events))
            result = json.loads(future.result(timeout=5))
        finally: pool.shutdown(wait=True, cancel_futures=True)
    assert result['success'] is False and result['saved'] is True and result['error_type'] == 'fill_unconfirmed'
    assert not fake.writes and 'synthetic-failed-fill' not in json.dumps(result)


def test_save_refuses_insecure_origin_before_prompt_or_storage(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    sink = attach_sink(authority, live, actor)
    fake_browser(bridge, Path(authority.profile_id), monkeypatch, origin='http://example.test', controls=[password_control()])
    prompted = []
    monkeypatch.setattr(bridge, '_ask', lambda *a, **kw: prompted.append(True) or '')
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'save_login'}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not prompted and not sink.frames
    assert not (Path(authority.profile_id) / 'vault').exists()


def test_supervisor_send_fails_fast_if_event_lock_is_busy(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    fake_browser(bridge, Path(authority.profile_id), monkeypatch)
    from gateway import session_sudo_prompt
    entered = threading.Event(); release = threading.Event()
    def hold():
        with live.event_stream.lock:
            entered.set(); release.wait(3)
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        binding = bridge._CTX.get(); captured = bridge._capture('session-a', binding)
        capture = (*captured, captured[0].page_session_id)
        holder = threading.Thread(target=hold); holder.start(); assert entered.wait(3)
        pool = ThreadPoolExecutor(max_workers=1)
        try:
            pending = pool.submit(bridge._before_send, binding, 'session-a', capture)
            with pytest.raises(RuntimeError): pending.result(timeout=0.5)
        finally:
            release.set(); holder.join(); pool.shutdown(wait=True)


def seeded_login(home, *, otp=None):
    from agent.vault_store import VaultStore
    secret = {'identifier_type':'email', 'identifier':TEST_EMAIL, 'password':'synthetic-signup-pass'}
    if otp: secret['otp_secret'] = otp
    return VaultStore(home / 'vault').add_item('login', 'Example', secret, origin='https://example.test').id


def test_saved_totp_never_resolves_for_a_different_origin(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id), otp='JBSWY3DPEHPK3PXP')
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, origin='https://wrong.test', controls=[otp_control()])
    from agent import vault_store
    original = vault_store.totp_now; calls=[]
    monkeypatch.setattr(vault_store, 'totp_now', lambda *a, **kw: calls.append(True) or original(*a, **kw))
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'enter_code','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and result['error_type'] == 'origin_mismatch'
    assert not calls and not fake.writes


def test_legacy_http_login_cannot_be_filled(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from agent.vault_store import VaultStore
    handle = VaultStore(Path(authority.profile_id) / 'vault').add_item(
        'login', 'Legacy', {'identifier_type':'email', 'identifier':TEST_EMAIL, 'password':'synthetic-legacy'},
        origin='http://example.test').id
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, origin='http://example.test', controls=[password_control()])
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not fake.writes


def test_saved_totp_does_not_write_after_origin_changes(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id), otp='JBSWY3DPEHPK3PXP')
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[otp_control()])
    fake.before_write = lambda: setattr(fake, 'origin', 'https://wrong.test')
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'enter_code','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and result['error_type'] == 'origin_changed'
    assert not fake.writes and 'JBSWY3DPEHPK3PXP' not in json.dumps(result)


def test_saved_totp_same_origin_fills_without_disclosing_code(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id), otp='JBSWY3DPEHPK3PXP')
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[otp_control()])
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'enter_code','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and result['status'] == 'needs_human'
    assert len(fake.writes) == 1 and fake.writes[0]['value'] not in json.dumps(result)


def test_signup_requires_one_shot_owner_admission_and_never_returns_password(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id))
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch,
                        controls=[password_control(0, 'new-password', 'New password'), password_control(1, 'new-password', 'Confirm password')])
    used=[]
    def admit(auth, sid, item, origin):
        assert auth is authority and sid == 'session-a' and item == handle and origin == 'https://example.test'
        if used: return False
        used.append(True); return True
    bridge._STATE['admit_signup'] = admit
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle}, task_id='session-a', session_id='session-a'))
        duplicate = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and result['status'] == 'needs_human'
    assert duplicate['success'] is False and duplicate['error_type'] == 'approval_required'
    assert len(fake.writes) == 2 and all(x['value'] == 'synthetic-signup-pass' for x in fake.writes)
    assert 'synthetic-signup-pass' not in json.dumps(result)


def test_signup_refuses_mixed_old_and_new_password_forms(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id))
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch,
                        controls=[password_control(0, 'current-password', 'Current password'), password_control(1, 'new-password', 'New password')])
    admitted=[]; bridge._STATE['admit_signup'] = lambda *args: admitted.append(True) or True
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and result['error_type'] == 'form_unavailable'
    assert not admitted and not fake.writes


def test_signup_origin_change_after_admission_never_fills(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id))
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    fake.before_write = lambda: setattr(fake, 'origin', 'https://wrong.test')
    bridge._STATE['admit_signup'] = lambda *args: True
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and result['error_type'] == 'origin_changed' and not fake.writes


@pytest.mark.parametrize('edit_point', ['before_approval', 'during_approval', 'after_approval'])
def test_action_snapshot_signup_does_not_mix_an_edited_secret_with_the_approved_origin(installed, monkeypatch, edit_point):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id)
    handle = seeded_login(home)
    runtime = bridge._runtime()
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    edit_done = threading.Event()
    def edit():
        runtime.replace(home, handle, {'label':'Changed', 'origin':'https://changed.test',
                                      'secret':{'identifier':'changed' + chr(64) + 'example.test', 'password':'synthetic-new-password'}})
        edit_done.set()
    admitted = []
    original_controls, original_focus = bridge._controls, bridge._focus
    def controls(*args):
        result = original_controls(*args)
        if edit_point == 'before_approval': edit()
        return result
    def focus(*args):
        if edit_point == 'after_approval' and admitted and not edit_done.is_set(): edit()
        return original_focus(*args)
    def admit(auth, sid, item, origin):
        assert (auth, sid, item, origin) == (authority, 'session-a', handle, 'https://example.test')
        if edit_point == 'during_approval':
            # The editor must not wait on a Vault lock held across the approval callback.
            worker = threading.Thread(target=edit, daemon=True)
            worker.start()
            assert edit_done.wait(3)
            worker.join(timeout=1)
        admitted.append(True)
        return True
    monkeypatch.setattr(bridge, '_controls', controls)
    monkeypatch.setattr(bridge, '_focus', focus)
    bridge._STATE['admit_signup'] = admit
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and admitted and edit_done.is_set()
    assert [row['value'] for row in fake.writes] == ['synthetic-signup-pass']
    assert all(value not in json.dumps(result) for value in ('synthetic-signup-pass', 'synthetic-new-password'))
    updated = runtime.items(home)[0]
    assert updated['origin'] == 'https://changed.test' and updated['identifier'] == 'changed' + chr(64) + 'example.test'


def test_action_snapshot_login_uses_the_preinspection_item_after_a_concurrent_edit(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    runtime = bridge._runtime(); fake = fake_browser(bridge, home, monkeypatch, controls=[password_control()])
    original_call = fake.call; edited = []
    def call(method, params, **kwargs):
        if 'const elements = Array.from' in params['expression'] and not edited:
            runtime.replace(home, handle, {'label':'Changed', 'origin':'https://changed.test',
                                          'secret':{'identifier':'changed' + chr(64) + 'example.test', 'password':'synthetic-new-password'}})
            edited.append(True)
        return original_call(method, params, **kwargs)
    monkeypatch.setattr(fake, 'call', call)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
        fake.origin = 'https://changed.test'
        next_result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and next_result['success'] is True and edited
    assert [row['value'] for row in fake.writes] == ['synthetic-signup-pass', 'synthetic-new-password']
    assert 'synthetic-new-password' not in json.dumps(result)


def test_action_snapshot_payment_consent_does_not_use_a_replacement_card(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); runtime = bridge._runtime()
    old = {'card_number':'4111111111111111', 'exp_month':'12', 'exp_year':'2030', 'cvc':'987'}
    item = runtime.add(home, {'kind':'payment', 'label':'Card', 'origin':'https://example.test', 'secret':old})
    fake = fake_browser(bridge, home, monkeypatch, controls=[{'autocomplete':'cc-number', 'formIndex':0, 'index':0,
                        'label':'Card number', 'name':'card', 'type':'text', 'maxLength':None}])
    from tools import approval_prompt
    def approve(*args, **kwargs):
        runtime.replace(home, item['id'], {'label':'Changed', 'origin':'https://changed.test',
                                          'secret':{'card_number':'5555555555554444', 'cvc':'123'}})
        return 'accept'
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', approve)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':item['id']}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True
    assert [row['value'] for row in fake.writes] == [old['card_number']]
    assert old['card_number'] not in json.dumps(result) and '5555555555554444' not in json.dumps(result)


def test_action_snapshot_otp_uses_the_seed_bound_to_the_original_origin(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); runtime = bridge._runtime()
    handle = seeded_login(home, otp='JBSWY3DPEHPK3PXP')
    fake = fake_browser(bridge, home, monkeypatch, controls=[otp_control()])
    original_controls = bridge._controls; edited = []
    def controls(*args):
        result = original_controls(*args)
        runtime.replace(home, handle, {'label':'Changed', 'origin':'https://changed.test',
                                      'secret':{'otp_secret':'KRSXG5DSNFXGOIDB'}})
        edited.append(True)
        return result
    monkeypatch.setattr(bridge, '_controls', controls)
    from agent import vault_store
    monkeypatch.setattr(vault_store, 'totp_now', lambda seed, **kwargs: '135790' if seed == 'JBSWY3DPEHPK3PXP' else '246802')
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'enter_code', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and edited
    assert [row['value'] for row in fake.writes] == ['135790']
    assert '135790' not in json.dumps(result) and '246802' not in json.dumps(result)


def test_action_snapshot_is_immutable_and_never_represented_with_secret_values(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake_browser(bridge, home, monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    original = bridge._controls; observed = {}
    def inspect(*args):
        snapshot = bridge._SNAPSHOT.get()
        observed['repr'] = repr(snapshot)
        try:
            snapshot.meta = snapshot.meta
            observed['meta_mutable'] = True
        except (AttributeError, TypeError):
            observed['meta_mutable'] = False
        try:
            snapshot.secret['password'] = 'other'
            observed['secret_mutable'] = True
        except (AttributeError, TypeError):
            observed['secret_mutable'] = False
        return original(*args)
    monkeypatch.setattr(bridge, '_controls', inspect)
    bridge._STATE['admit_signup'] = lambda *args: True
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True
    assert observed['meta_mutable'] is False and observed['secret_mutable'] is False
    assert 'synthetic-signup-pass' not in observed['repr']


def test_action_snapshot_save_login_never_fills_a_concurrent_replacement(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    sink = attach_sink(authority, live, actor)
    home = Path(authority.profile_id); runtime = bridge._runtime()
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control()])
    from agent.vault_store import VaultStore
    original_meta = VaultStore._meta; edited = []; save_reprs = []; saved_contexts = []
    def meta(record):
        result = original_meta(record)
        if not edited and record.get('kind') == 'login':
            save_reprs.append(repr(bridge._SAVE.get()))
            saved_contexts.append(contextvars.copy_context())
            edited.append(True)
            runtime.replace(home, result.id, {'label':'Changed', 'origin':'https://example.test',
                                             'secret':{'identifier':'changed' + chr(64) + 'example.test', 'password':'synthetic-replacement'}})
        return result
    monkeypatch.setattr(VaultStore, '_meta', staticmethod(meta))
    from gateway import session_sudo_prompt, session_server_requests as requests
    from tools.registry import registry
    monkeypatch.setenv('HERMES_SESSION_PLATFORM', '')
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        pool, future = invoke_in_worker(lambda: registry.dispatch('browser_vault', {'action':'save_login'}, task_id='session-a', session_id='session-a'))
        try:
            assert sink.changed.wait(5)
            frame = next(row for row in sink.frames if row.get('method') == 'vault.save_login')
            response = {'id':frame['id'], 'result':{'value':json.dumps({'identifier':TEST_EMAIL, 'password':'synthetic-owner-answer'})}}
            assert requests.resolve(response, session_ok=lambda sid, events: requests.may_settle(authority, actor, sid, events))
            result = json.loads(future.result(timeout=5))
        finally:
            pool.shutdown(wait=True, cancel_futures=True)
    assert edited
    assert all(row['value'] == 'synthetic-owner-answer' for row in fake.writes)
    assert 'synthetic-replacement' not in json.dumps(result)
    assert all('synthetic-owner-answer' not in value for value in save_reprs)
    assert saved_contexts[0].get(bridge._SAVE).expected is None


@pytest.mark.parametrize('stop', ['denied', 'interrupt', 'generation', 'disconnect'])
def test_action_snapshot_signup_still_refuses_revoked_or_denied_approval(installed, monkeypatch, stop):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    def admit(*args):
        if stop == 'denied':
            bridge._runtime().replace(home, handle, {'label':'Changed', 'origin':'https://changed.test',
                                                    'secret':{'password':'synthetic-invalidated'}})
        elif stop == 'interrupt': agent._interrupt_requested = True
        elif stop == 'generation': authority.executing['session-a'] = 8
        elif stop == 'disconnect': live.subscribers.clear()
        return stop != 'denied'
    bridge._STATE['admit_signup'] = admit
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not fake.writes


def test_action_snapshot_copied_context_expires_and_rejects_a_different_handle(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake_browser(bridge, home, monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    copied = []; denied = []; original = bridge._controls
    def controls(*args):
        from agent.vault_backends.local import LocalLoginBackend
        from agent.vault_store import VaultError
        backend = LocalLoginBackend()
        for read in (backend.get_meta, backend.resolve_password, backend.resolve_secret, backend.resolve_otp):
            try: read('vault_000000000000')
            except VaultError: denied.append(True)
        copied.append(contextvars.copy_context())
        return original(*args)
    monkeypatch.setattr(bridge, '_controls', controls)
    bridge._STATE['admit_signup'] = lambda *args: True
    from gateway import session_sudo_prompt
    from tools.registry import registry
    from agent.vault_backends.local import LocalLoginBackend
    from agent.vault_store import VaultError
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
        assert result['success'] is True and len(denied) == 4 and len(copied) == 1
        assert not copied[0].get(bridge._SNAPSHOT).secret
        for read in (LocalLoginBackend().get_meta, LocalLoginBackend().resolve_password,
                     LocalLoginBackend().resolve_secret, LocalLoginBackend().resolve_otp):
            with pytest.raises(VaultError): copied[0].run(read, handle)
        assert LocalLoginBackend().get_meta(handle).id == handle
        with pytest.raises(VaultError): LocalLoginBackend().resolve_password(handle)


def test_action_snapshot_parallel_profile_sessions_cannot_cross_items(installed, tmp_path, monkeypatch):
    bridge, first, live_a, actor_a, runner, turn_a, agent_a = installed
    second_home = tmp_path / 'second-profile'; second_home.mkdir()
    second, live_b, actor_b, _, turn_b, agent_b = authority_for(second_home, sid='session-b', owner='owner-b')
    attach_sink(first, live_a, actor_a); attach_sink(second, live_b, actor_b)
    home_a = Path(first.profile_id); handle_a = seeded_login(home_a)
    runtime = bridge._runtime()
    handle_b = runtime.add(second_home, {'kind':'login', 'label':'Other', 'origin':'https://example.test',
                          'secret':{'identifier_type':'email', 'identifier':'other' + chr(64) + 'example.test', 'password':'synthetic-profile-b'}})['id']
    # The same opaque handle may independently exist in two physical homes.
    with runtime._store(second_home) as (_, store, rows):
        rows[0]['id'] = handle_a
        runtime._write(store, rows)
    handle_b = handle_a
    fake_a = FakeCapture(controls=[password_control()]); fake_b = FakeCapture(controls=[password_control()])
    from hermes_constants import hermes_home_key, set_hermes_home_override, reset_hermes_home_override
    registry_capture = SimpleNamespace(_lock=threading.RLock(),
        _by_task={'session-a':fake_a._supervisor, 'session-b':fake_b._supervisor},
        _home_by_task={'session-a':hermes_home_key(home_a), 'session-b':hermes_home_key(second_home)})
    monkeypatch.setattr(bridge, '_capture', lambda task, binding: (
        {'session-a':fake_a, 'session-b':fake_b}[task], registry_capture, hermes_home_key(binding.owner[3])))
    bridge._STATE['authorize'] = lambda auth, sid: (auth, sid) in ((first, 'session-a'), (second, 'session-b'))
    barrier = threading.Barrier(2)
    for fake in (fake_a, fake_b):
        original_call = fake.call
        def call(method, params, *, session_id, timeout, before_send, original_call=original_call):
            if 'const elements = Array.from' in params['expression']: barrier.wait(timeout=5)
            return original_call(method, params, session_id=session_id, timeout=timeout, before_send=before_send)
        monkeypatch.setattr(fake, 'call', call)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    def run(home, turn, agent, handle, sid):
        token = set_hermes_home_override(home)
        try:
            with session_sudo_prompt.sudo_prompt_scope(turn, agent):
                return json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':handle}, task_id=sid, session_id=sid))
        finally:
            reset_hermes_home_override(token)
    with ThreadPoolExecutor(max_workers=2) as pool:
        a = pool.submit(run, home_a, turn_a, agent_a, handle_a, 'session-a')
        b = pool.submit(run, second_home, turn_b, agent_b, handle_b, 'session-b')
        answer_a, answer_b = a.result(timeout=8), b.result(timeout=8)
    assert answer_a['success'] is True and answer_b['success'] is True
    assert [item['value'] for item in fake_a.writes] == ['synthetic-signup-pass']
    assert [item['value'] for item in fake_b.writes] == ['synthetic-profile-b']


def test_action_snapshot_missing_snapshot_fails_closed_with_an_active_capture(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake_browser(bridge, home, monkeypatch, controls=[password_control()])
    from agent.vault_backends.local import LocalLoginBackend
    from agent.vault_store import VaultError
    from gateway import session_sudo_prompt
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        captured = bridge._capture('session-a', bridge._CTX.get())
        token = bridge._CAPTURE.set(captured)
        try:
            assert bridge._SNAPSHOT.get() is None
            backend = LocalLoginBackend()
            for read in (backend.get_meta, backend.resolve_password, backend.resolve_secret, backend.resolve_otp):
                with pytest.raises(VaultError): read(handle)
        finally:
            bridge._CAPTURE.reset(token)


def test_action_snapshot_redaction_capacity_refuses_before_payment_approval(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); runtime = bridge._runtime()
    item = runtime.add(home, {'kind':'payment', 'label':'Card', 'origin':'https://example.test',
                       'secret':{'card_number':'4111111111111111', 'exp_month':'12', 'exp_year':'2030', 'cvc':'987'}})
    fake = fake_browser(bridge, home, monkeypatch, controls=[{'autocomplete':'cc-number', 'formIndex':0, 'index':0,
                        'label':'Card number', 'name':'card', 'type':'text', 'maxLength':None}])
    monkeypatch.setattr(bridge, '_REDACTION_LIMIT', 1)
    from tools import approval_prompt
    asked = []
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', lambda *args, **kwargs: asked.append(True) or 'accept')
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':item['id']}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not asked and not fake.writes


def test_action_snapshot_captured_page_cannot_be_retargeted_after_approval(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control(0, 'new-password', 'New password')])
    original_call = fake.call; accepted_on_replaced_page = []
    def call(method, params, *, session_id, timeout, before_send):
        def before():
            before_send()
            if fake.page_session_id != 'page-a': accepted_on_replaced_page.append(True)
        return original_call(method, params, session_id=session_id, timeout=timeout, before_send=before)
    monkeypatch.setattr(fake, 'call', call)
    def admit(*args):
        fake.page_session_id = 'page-b'
        return True
    bridge._STATE['admit_signup'] = admit
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not fake.writes
    assert not accepted_on_replaced_page


def test_action_snapshot_page_cannot_change_during_the_locked_vault_read(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); handle = seeded_login(home)
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control()])
    runtime = bridge._runtime(); original_store = runtime._store
    @contextlib.contextmanager
    def moved(root):
        with original_store(root) as opened:
            fake.page_session_id = 'page-b'
            yield opened
    monkeypatch.setattr(runtime, '_store', moved)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and not fake.writes


def test_action_snapshot_redacts_all_secret_values_before_payment_approval(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    home = Path(authority.profile_id); runtime = bridge._runtime()
    card = {'card_number':'4111111111111111', 'exp_month':'12', 'exp_year':'2030', 'cvc':'987'}
    item = runtime.add(home, {'kind':'payment', 'label':'Card', 'origin':'https://example.test', 'secret':card})
    fake_browser(bridge, home, monkeypatch, controls=[{'autocomplete':'cc-number', 'formIndex':0, 'index':0,
                 'label':'Card number', 'name':'card', 'type':'text', 'maxLength':None}])
    from agent import redact
    from tools import approval_prompt
    seen = []
    def approve(*args, **kwargs):
        seen.extend(redact.redact_registered_vault_values(value) != value for value in card.values())
        return 'accept'
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', approve)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'fill', 'handle':item['id']}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and len(seen) == len(card) and all(seen)


def test_advertised_vault_schema_has_only_supported_actions_and_backend(installed):
    from tools.registry import registry
    properties = registry.get_entry('browser_vault').schema['parameters']['properties']
    assert 'unlock' not in properties['action']['enum']
    assert 'signup_fill' in properties['action']['enum']
    assert 'backend' not in properties


def test_empty_vault_preserves_the_first_login_save_hint(installed):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'list'}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and result['items'] == []
    assert 'save_login' in result.get('hint', '')


@pytest.mark.parametrize('label', ['Create a password', 'Choose a password'])
def test_signup_article_labels_fill_both_new_password_fields(installed, monkeypatch, label):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id))
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch,
                        controls=[password_control(0, '', label), password_control(1, '', 'Confirm password')])
    bridge._STATE['admit_signup'] = lambda *args: True
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is True and result['status'] == 'needs_human'
    assert len(fake.writes) == 2 and all(row['value'] == 'synthetic-signup-pass' for row in fake.writes)
    assert 'synthetic-signup-pass' not in json.dumps(result)


def test_revoked_otp_prompt_reports_authority_loss_instead_of_user_decline(installed, monkeypatch):
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    handle = seeded_login(Path(authority.profile_id))
    fake = fake_browser(bridge, Path(authority.profile_id), monkeypatch, controls=[otp_control()])
    def revoke(*args, **kwargs):
        agent._interrupt_requested = True
        return '123456'
    monkeypatch.setattr(bridge, '_ask', revoke)
    from gateway import session_sudo_prompt
    from tools.registry import registry
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        result = json.loads(registry.dispatch('browser_vault', {'action':'enter_code','handle':handle}, task_id='session-a', session_id='session-a'))
    assert result['success'] is False and result['error_type'] == 'unavailable'
    assert not fake.writes and '123456' not in json.dumps(result)
