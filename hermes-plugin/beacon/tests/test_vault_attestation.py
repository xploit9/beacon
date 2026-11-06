"""Isolated gateway readiness and generated-login consent tests."""
import importlib.util
from functools import wraps
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from test_vault_bridge import (NATIVE, installed, native, attach_sink, fake_browser, seeded_login,
                               password_control, invoke_in_worker)
from test_mobile import harness as dashboard_harness, load_private

ROOT = Path(__file__).resolve().parents[1]


class Connection(sqlite3.Connection):
    def __exit__(self, *exc):
        try: return super().__exit__(*exc)
        finally: self.close()


class Mobile:
    def __init__(self, path): self.path, self.LOCK = path, threading.RLock()
    def connect(self): return sqlite3.connect(self.path, timeout=2, factory=Connection)
    def settings(self): return {'owner_id':'owner', 'owner_provider':'basic'}
    def read_plugin_version(self, path): return '1.1.32'


class Status:
    def __init__(self): self.pid, self.birth, self.running, self.stale = 101, 123456, True, False
    def get_running_pid(self, *, cleanup_stale):
        raise AssertionError('A readiness probe must not open or repair gateway lock files')
    def read_runtime_status(self): return {'state':'synthetic'}
    def get_runtime_status_running_pid(self, record): return self.pid if self.running and record else None
    def runtime_status_is_stale(self, record): return self.stale
    def get_process_start_time(self, pid): return self.birth if pid == self.pid else None


class Bridge:
    def __init__(self, authorities):
        self.runner = SimpleNamespace(session_authorities=authorities)
        self._STATE = {'runner':self.runner}
        self.local = threading.local()
        self._CTX = SimpleNamespace(get=lambda: getattr(self.local, 'binding', self.binding))
        self.binding = None
        self.ready = True
    def attested(self): return self.ready
    def signup_ready(self): return self.ready
    def request_owner(self, authority, sid): return authority.subjects.get(sid)
    def _authorized(self, binding): return binding is not None and binding.active


class Integrity:
    def __init__(self): self.signature = 'a' * 64; self.loaded_ok = True; self.entry_ok = True; self.status_ok = True
    def source(self): return self.signature
    def code(self, signature): return 'c' * 64
    def loaded(self, bridge): return self.loaded_ok
    def entrypoints(self, entries): return self.entry_ok
    def status_loaded(self, status): return self.status_ok


def load(monkeypatch):
    spec = importlib.util.spec_from_file_location('beacon_vault_attestation', ROOT / 'vault_attestation.py')
    module = importlib.util.module_from_spec(spec)
    monkeypatch.setitem(sys.modules, spec.name, module)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    module = load(monkeypatch)
    homes = [tmp_path / 'one', tmp_path / 'two']
    for home in homes: home.mkdir()
    authorities = [SimpleNamespace(profile_id=str(home), subjects={}, executing={}, retiring=False, retired=False) for home in homes]
    bridge, status, mobile, integrity = Bridge(authorities), Status(), Mobile(tmp_path / 'beacon.sqlite'), Integrity()
    clock = [1000.0]
    gateway = module.Coordinator(mobile, '1.1.32', bridge=bridge, status=status, integrity=integrity,
                                 now=lambda:clock[0], pid=lambda:101, confirm=lambda *args:True)
    bridge._STATE.update(authorize=gateway.authorize_callback, admit_signup=gateway.signup_callback)
    dashboard = module.Coordinator(mobile, '1.1.32', status=status, integrity=integrity, now=lambda:clock[0])
    with mobile.connect() as db:
        db.execute('CREATE TABLE vault_profile_owners(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT)')
        db.execute('CREATE TABLE vault_profile_issuers(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT, issuer TEXT)')
        for home in homes:
            key = module.home_hash(home)
            db.execute('INSERT INTO vault_profile_owners VALUES(?,?,?)', (key,'owner','basic'))
            db.execute('INSERT INTO vault_profile_issuers VALUES(?,?,?,?)', (key,'owner','basic',''))
    return module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock


def test_gateway_receipt_is_read_only_and_scoped_to_actual_served_home(env):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    assert gateway.publish() is True
    with module.scope('owner','basic','',module.home_hash(homes[0] / '..' / 'one')):
        assert dashboard.readiness() == {'secure_prompts':True,'otp':True,'signup':True}
    assert dashboard.readiness() == {'secure_prompts':False,'otp':False,'signup':False}
    foreign = homes[0].parent / 'unserved'; foreign.mkdir()
    with module.scope('owner','basic','',module.home_hash(foreign)):
        assert not dashboard.readiness()['secure_prompts']
    assert all(not (home / 'vault').exists() for home in homes)


def test_receipt_is_read_in_a_separate_process_with_real_process_birth(env, tmp_path):
    module, gateway, _, mobile, status, integrity, bridge, authorities, homes, clock = env
    native = NATIVE
    sys.path.insert(0, str(native))
    try:
        from gateway.status import get_process_start_time
        status.pid = os.getpid(); status.birth = get_process_start_time(status.pid)
        assert isinstance(status.birth, int)
        gateway.pid = os.getpid
        assert gateway.publish()
        child_home = tmp_path / 'child-home'; child_home.mkdir()
        code = '''
import importlib.util, json, sqlite3, sys, threading
sys.path.insert(0, sys.argv[1])
from gateway.status import get_process_start_time
spec=importlib.util.spec_from_file_location("child_vault_attestation", sys.argv[2])
m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
class Mobile:
 def __init__(self): self.LOCK=threading.RLock()
 def connect(self): return sqlite3.connect(sys.argv[3])
 def read_plugin_version(self, path): return "1.1.32"
class Status:
 def read_runtime_status(self): return {"fixture":True}
 def runtime_status_is_stale(self, record): return False
 def get_runtime_status_running_pid(self, record): return int(sys.argv[4])
 def get_process_start_time(self, pid): return get_process_start_time(pid)
class Integrity:
 def source(self): return "a"*64
 def code(self, signature): return "c"*64
 def entrypoints(self, entries): return True
 def status_loaded(self, status): return True
gate=m.Coordinator(Mobile(), "1.1.32", status=Status(), integrity=Integrity(), now=lambda:1000)
with m.scope("owner","basic","",sys.argv[5]): print(json.dumps(gate.readiness()))
'''
        child_env = os.environ.copy()
        child_env.update(HERMES_HOME=str(child_home), PYTHONDONTWRITEBYTECODE='1', HERMES_DISABLE_LAZY_INSTALLS='1')
        result = subprocess.run([sys.executable, '-c', code, str(native), str(ROOT / 'vault_attestation.py'),
                                 str(mobile.path), str(status.pid), module.home_hash(homes[0])],
                                env=child_env, capture_output=True, text=True, timeout=15, check=True)
        assert json.loads(result.stdout)['secure_prompts'] is True
    finally:
        sys.path.remove(str(native))


def test_compiled_code_fingerprint_matches_across_processes(native, monkeypatch, tmp_path):
    module = load(monkeypatch); integrity = module.Integrity()
    signature = integrity.source(); expected = integrity.code(signature)
    child_home = tmp_path / 'fingerprint-home'; child_home.mkdir()
    script = '''
import importlib.util,json,sys
sys.path.insert(0,sys.argv[1])
spec=importlib.util.spec_from_file_location("child_attestation",sys.argv[2]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
i=m.Integrity();s=i.source();print(json.dumps([s,i.code(s)]))
'''
    env = os.environ.copy()
    env.update(HERMES_HOME=str(child_home), PYTHONDONTWRITEBYTECODE='1', HERMES_DISABLE_LAZY_INSTALLS='1')
    path = NATIVE
    child = subprocess.run([sys.executable,'-c',script,str(path),str(ROOT / 'vault_attestation.py')],
                           env=env, capture_output=True, text=True, timeout=15, check=True)
    assert json.loads(child.stdout) == [signature, expected]


@pytest.mark.parametrize('fault', ['stale','future','pid','pid_reuse','dead','runtime_stale','version','source','code','home','issuer','unloaded'])
def test_readiness_fails_closed_for_stale_or_mismatched_evidence(env, fault):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    assert gateway.publish() is True
    owner, issuer, home = 'owner', '', module.home_hash(homes[0])
    if fault == 'stale': clock[0] += 30
    if fault == 'future': clock[0] -= 10
    if fault == 'pid': status.pid = 102
    if fault == 'pid_reuse': status.birth += 1
    if fault == 'dead': status.running = False
    if fault == 'runtime_stale': status.stale = True
    if fault == 'version': dashboard.version = 'other'
    if fault == 'source': integrity.signature = 'b' * 64
    if fault == 'code':
        with mobile.connect() as db:
            row = db.execute('SELECT value FROM vault_gateway_receipt WHERE id=1').fetchone()
            value = json.loads(row[0]); assert value['code'] == 'c' * 64
            value['code'] = 'd' * 64
            db.execute('UPDATE vault_gateway_receipt SET value=? WHERE id=1', (json.dumps(value),))
    if fault == 'home': home = 'f' * 64
    if fault == 'issuer': issuer = 'foreign-issuer'
    if fault == 'unloaded': gateway.close()
    with module.scope(owner,'basic',issuer,home):
        assert dashboard.readiness() == {'secure_prompts':False,'otp':False,'signup':False}


def test_native_owner_must_match_existing_owner_provider_issuer_and_served_home(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0]))
    authority.executing['s'] = 7
    assert gateway.authorize_owner(authority,'s') is True
    for subject in [('other','basic','',str(homes[0])), ('owner','other','',str(homes[0])),
                    ('owner','basic','foreign',str(homes[0])), ('owner','basic','',str(homes[1]))]:
        authority.subjects['s'] = subject
        assert gateway.authorize_owner(authority,'s') is False
    authority.subjects['s'] = None
    assert gateway.authorize_owner(authority,'s') is False


def test_federated_issuer_must_match_the_verified_binding(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; key = module.home_hash(homes[0])
    mobile.settings = lambda: {'owner_id':'owner', 'owner_provider':'oidc'}
    with mobile.connect() as db:
        db.execute('UPDATE vault_profile_owners SET provider=? WHERE home_hash=?', ('oidc',key))
        db.execute('UPDATE vault_profile_issuers SET provider=?,issuer=? WHERE home_hash=?', ('oidc','https://issuer.example',key))
    authority.subjects['s'] = ('owner','oidc','https://issuer.example',str(homes[0])); authority.executing['s'] = 7
    assert gateway.authorize_owner(authority,'s') is True
    authority.subjects['s'] = ('owner','oidc','https://other.example',str(homes[0]))
    assert gateway.authorize_owner(authority,'s') is False


def test_generated_intent_needs_confirmation_and_is_consumed_once(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    bridge.binding = SimpleNamespace(authority=authority, session_id='s', generation=7, active=True)
    bound = ('owner','basic','',module.home_hash(homes[0]))
    handle, origin = 'vault_012345abcdef', 'https://example.test'
    module.record_intent(mobile, bound, handle, origin, now=1000)
    assert gateway.admit_signup(authority,'s',handle,'https://wrong.test') is False
    gateway.confirm = lambda *args: False
    assert gateway.admit_signup(authority,'s',handle,origin) is False
    gateway.confirm = lambda *args: True
    assert gateway.admit_signup(authority,'s',handle,origin) is True
    assert gateway.admit_signup(authority,'s',handle,origin) is False


def test_generated_intent_expiry_and_turn_change_refuse_without_consuming(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, clock = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    bridge.binding = SimpleNamespace(authority=authority, session_id='s', generation=7, active=True)
    bound = ('owner','basic','',module.home_hash(homes[0])); handle = 'vault_012345abcdef'; origin = 'https://example.test'
    module.record_intent(mobile, bound, handle, origin, now=1000)
    gateway.confirm = lambda *args: authority.executing.update({'s':8}) or True
    assert gateway.admit_signup(authority,'s',handle,origin) is False
    authority.executing['s'] = 7; gateway.confirm = lambda *args: True; clock[0] += 601
    assert gateway.admit_signup(authority,'s',handle,origin) is False


def test_concurrent_approved_sessions_cannot_consume_one_intent_twice(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]
    for sid in ('s1','s2'):
        authority.subjects[sid] = ('owner','basic','',str(homes[0])); authority.executing[sid] = 7
    handle, origin = 'vault_012345abcdef', 'https://example.test'
    module.record_intent(mobile, ('owner','basic','',module.home_hash(homes[0])), handle, origin, now=1000)
    barrier = threading.Barrier(2); results = []
    gateway.confirm = lambda *args: barrier.wait(timeout=3) in (0, 1)
    def run(sid):
        bridge.local.binding = SimpleNamespace(authority=authority, session_id=sid, generation=7, active=True)
        results.append(gateway.admit_signup(authority,sid,handle,origin))
    threads = [threading.Thread(target=run, args=(sid,)) for sid in ('s1','s2')]
    for thread in threads: thread.start()
    for thread in threads: thread.join(5)
    assert sorted(results) == [False, True]


def test_edit_during_approval_revokes_intent_without_holding_database_lock(env):
    module, gateway, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    bridge.binding = SimpleNamespace(authority=authority, session_id='s', generation=7, active=True)
    bound = ('owner','basic','',module.home_hash(homes[0])); handle = 'vault_012345abcdef'; origin = 'https://example.test'
    module.record_intent(mobile, bound, handle, origin, now=1000)
    changed = threading.Event()
    def approve(*args):
        worker = threading.Thread(target=lambda: (module.invalidate_intent(mobile, bound, handle), changed.set()))
        worker.start(); worker.join(3)
        assert changed.is_set()
        return True
    gateway.confirm = approve
    assert not gateway.admit_signup(authority,'s',handle,origin)
    assert changed.is_set()


def test_guard_failure_withdraws_the_live_receipt(env):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    assert gateway.publish() is True
    bridge.ready = False
    assert gateway.publish() is False
    with module.scope('owner','basic','',module.home_hash(homes[0])):
        assert not dashboard.readiness()['secure_prompts']


def test_replaced_authority_or_signup_callback_cannot_attest(env):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    assert gateway.publish() is True
    bridge._STATE['authorize'] = lambda *_: True
    assert gateway.guard('browser_vault')['action'] == 'block'
    assert not gateway.publish()
    with module.scope('owner','basic','',module.home_hash(homes[0])):
        assert not dashboard.readiness()['secure_prompts']


def test_loaded_plugin_code_mismatch_prevents_gateway_and_dashboard_attestation(env):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    gateway.entrypoints = [('__init__.py', lambda:None)]
    dashboard.entrypoints = [('dashboard/plugin_api.py', lambda:None)]
    assert gateway.publish() is True
    integrity.entry_ok = False
    with module.scope('owner','basic','',module.home_hash(homes[0])):
        assert not dashboard.readiness()['secure_prompts']
    assert not gateway.publish()


def test_dashboard_fails_closed_if_its_loaded_status_helper_changed(env):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    assert gateway.publish() is True
    integrity.status_ok = False
    with module.scope('owner','basic','',module.home_hash(homes[0])):
        assert not dashboard.readiness()['secure_prompts']


def test_old_instance_cannot_withdraw_newer_receipt_with_same_pid(env):
    module, old, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    new = module.Coordinator(mobile, '1.1.32', bridge=bridge, status=status, integrity=integrity,
                             now=lambda:clock[0], pid=lambda:101, confirm=lambda *a:True)
    assert old.publish()
    bridge._STATE.update(authorize=new.authorize_callback, admit_signup=new.signup_callback)
    assert new.publish()
    old.close()
    with module.scope('owner','basic','',module.home_hash(homes[0])):
        assert dashboard.readiness()['secure_prompts'] is True


def test_real_pinned_loaded_native_code_matches_sources(installed, monkeypatch):
    bridge, *_ = installed
    from gateway import status, session_authorities
    from tools import approval_prompt
    module = load(monkeypatch)
    integrity = module.Integrity()
    assert len(integrity.source()) == 64
    assert integrity.loaded(bridge) is True
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', lambda *a, **kw: 'accept')
    assert integrity.loaded(bridge) is False


def test_loaded_code_check_rejects_a_wrapped_foreign_function(installed, monkeypatch):
    bridge, *_ = installed
    from gateway import status, session_authorities
    from tools import approval_prompt
    module = load(monkeypatch); integrity = module.Integrity()
    assert integrity.loaded(bridge)
    original = bridge.request_owner
    @wraps(original)
    def changed(*args, **kwargs): return original(*args, **kwargs)
    monkeypatch.setattr(bridge, 'request_owner', changed)
    assert not integrity.loaded(bridge)


def test_loaded_code_check_rejects_a_replaced_bridge_dependency(installed, monkeypatch):
    bridge, *_ = installed
    from gateway import status, session_authorities
    from tools import approval_prompt
    module = load(monkeypatch); integrity = module.Integrity()
    assert integrity.loaded(bridge)
    monkeypatch.setattr(bridge, '_known_source', lambda: True)
    assert not integrity.loaded(bridge)


def test_real_loaded_bridge_publishes_a_separately_read_gateway_receipt(installed, monkeypatch, tmp_path):
    bridge, authority, live, actor, runner, turn, agent = installed
    from gateway import status as native_status, session_authorities
    from tools import approval_prompt
    module = load(monkeypatch); plugin = load_private('__init__')
    mobile, status = Mobile(tmp_path / 'loaded-receipt.sqlite'), Status()
    home = Path(authority.profile_id); key = module.home_hash(home)
    with mobile.connect() as db:
        db.execute('CREATE TABLE vault_profile_owners(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT)')
        db.execute('CREATE TABLE vault_profile_issuers(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT, issuer TEXT)')
        db.execute('INSERT INTO vault_profile_owners VALUES(?,?,?)', (key,'owner-a','basic'))
        db.execute('INSERT INTO vault_profile_issuers VALUES(?,?,?,?)', (key,'owner-a','basic',''))
    gateway = module.Coordinator(mobile, '1.1.32', bridge=bridge, status=status, now=lambda:1000, pid=lambda:101)
    bridge._STATE['authorize'] = gateway.authorize_callback
    bridge._STATE['admit_signup'] = gateway.signup_callback
    dashboard = module.Coordinator(mobile, '1.1.32', status=status, now=lambda:1000)
    monkeypatch.setattr(dashboard.integrity, 'status_loaded', lambda status: True)
    gateway.entrypoints = [('__init__.py',plugin.register)]
    dashboard.entrypoints = [('__init__.py',plugin.register)]
    assert gateway.publish() is True
    with module.scope('owner-a','basic','',key):
        assert dashboard.readiness()['secure_prompts'] is True
    monkeypatch.setattr(bridge, 'request_owner', lambda *args: None)
    assert gateway.publish() is False
    with module.scope('owner-a','basic','',key):
        assert dashboard.readiness()['secure_prompts'] is False


def test_signup_uses_owner_only_native_approval_then_fills(installed, monkeypatch, tmp_path):
    import asyncio
    from gateway import session_sudo_prompt
    from gateway.session_contract import Principal, SessionRef
    from hermes_state_runtime import RuntimeStoreError
    from tools import approval_prompt
    from tools.registry import registry
    bridge, authority, live, actor, runner, turn, agent = installed
    attach_sink(authority, live, actor)
    module = load(monkeypatch); mobile = Mobile(tmp_path / 'signup-approval.sqlite')
    mobile.settings = lambda: {'owner_id':'owner-a', 'owner_provider':'basic'}
    home = Path(authority.profile_id); key = module.home_hash(home)
    with mobile.connect() as db:
        db.execute('CREATE TABLE vault_profile_owners(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT)')
        db.execute('CREATE TABLE vault_profile_issuers(home_hash TEXT PRIMARY KEY, owner TEXT, provider TEXT, issuer TEXT)')
        db.execute('INSERT INTO vault_profile_owners VALUES(?,?,?)', (key,'owner-a','basic'))
        db.execute('INSERT INTO vault_profile_issuers VALUES(?,?,?,?)', (key,'owner-a','basic',''))
    gate = module.Coordinator(mobile, '1.1.32', bridge=bridge, now=lambda:1000)
    bridge._STATE['authorize'] = gate.authorize_callback
    bridge._STATE['admit_signup'] = gate.signup_callback
    handle = seeded_login(home)
    module.record_intent(mobile, ('owner-a','basic','',key), handle, 'https://example.test', now=1000)
    fake = fake_browser(bridge, home, monkeypatch, controls=[password_control(0,'new-password','New password')])
    asked = threading.Event(); answered = threading.Event()
    monkeypatch.setattr(approval_prompt, 'request_elicitation_consent', lambda *a, **kw: asked.set() or ('accept' if answered.wait(3) else 'decline'))
    monkeypatch.setattr(authority, 'authorize', lambda *args: None)
    monkeypatch.setattr(authority, 'check_approval_generation', lambda *args: None)
    monkeypatch.setattr(live.controls, 'respond', lambda *a, **kw: answered.set() or 'accepted')
    foreign = Principal('foreign-subject', authority.profile_id, frozenset({'session:approve','session:operator'}), 'foreign')
    live.subscribers['foreign'] = foreign
    with session_sudo_prompt.sudo_prompt_scope(turn, agent):
        pool, future = invoke_in_worker(lambda: registry.dispatch('browser_vault', {'action':'signup_fill','handle':handle},
                                                                   task_id='session-a', session_id='session-a'))
        try:
            assert asked.wait(3)
            ref = SessionRef(authority.profile_id, 'session-a')
            with pytest.raises(RuntimeStoreError): asyncio.run(authority.respond(foreign, ref, 7, 'approval', 'once'))
            assert not answered.is_set() and not fake.writes
            assert asyncio.run(authority.respond(actor, ref, 7, 'approval', 'once')) == 'accepted'
            result = json.loads(future.result(timeout=3))
        finally:
            answered.set(); pool.shutdown(wait=True)
    assert result['success'] is True and result['status'] == 'needs_human'
    assert len(fake.writes) == 1 and 'synthetic-signup-pass' not in json.dumps(result)


def test_gateway_retry_uses_stable_callbacks_off_caller_thread(env, monkeypatch):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    calls = []; event = threading.Event()
    def install(authorize, signup):
        calls.append((authorize, signup, threading.get_ident()))
        if len(calls) >= 2: event.set()
        return False
    bridge.install = install
    monkeypatch.setattr(gateway._stop, 'wait', lambda delay: not (len(calls) < 2))
    caller = threading.get_ident(); gateway.start()
    assert event.wait(3)
    gateway._thread.join(3)
    assert calls[0][0] is calls[1][0] and calls[0][1] is calls[1][1]
    assert all(call[2] != caller for call in calls)
    gateway.close()


def test_gateway_loads_only_nonbootstrap_approval_support_after_install(env, monkeypatch):
    module, gateway, dashboard, mobile, status, integrity, bridge, authorities, homes, clock = env
    monkeypatch.delitem(sys.modules, 'tools.approval_prompt', raising=False)
    loaded = []
    original = module.importlib.import_module
    def observe(name, *args, **kwargs):
        if name == 'tools.approval_prompt': loaded.append(name); return SimpleNamespace()
        return original(name, *args, **kwargs)
    monkeypatch.setattr(module.importlib, 'import_module', observe)
    bridge.install = lambda *args: True
    monkeypatch.setattr(gateway, 'publish', lambda: True)
    monkeypatch.setattr(gateway._stop, 'wait', lambda delay: gateway._stop.set() or True)
    gateway._run()
    assert loaded == ['tools.approval_prompt']


def test_pretool_guard_blocks_without_attested_bridge(env):
    _, gateway, _, _, _, _, bridge, *_ = env
    bridge.ready = False
    assert gateway.guard('browser_vault')['action'] == 'block'
    assert gateway.guard('browser_navigate') is None
    bridge.ready = True
    assert gateway.guard('browser_vault') is None


def test_stop_immediately_disables_native_guard_before_receipt_cleanup(env, monkeypatch):
    _, gateway, _, _, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    assert gateway.authorize_owner(authority,'s') is True
    monkeypatch.setattr(gateway, '_withdraw', lambda:None)
    gateway.stop()
    assert gateway.guard('browser_vault')['action'] == 'block'
    assert gateway.authorize_owner(authority,'s') is False


def test_multiple_plugin_registrations_share_one_gateway_and_stop_on_last_release(env, monkeypatch):
    module, _, _, mobile, *_ = env
    first = module.acquire_gateway(mobile)
    second = module.acquire_gateway(mobile)
    assert first is second
    stopped = []
    monkeypatch.setattr(first, 'stop', lambda: stopped.append(True))
    module.release_gateway(first)
    assert stopped == []
    module.release_gateway(second)
    assert stopped == [True]


def test_gateway_reload_keeps_callback_identity_but_fences_old_turn(env):
    module, _, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    first = module.acquire_gateway(mobile); first.bridge = bridge
    bridge._STATE.update(authorize=first.authorize_callback, admit_signup=first.signup_callback)
    assert first.authorize_owner(authority,'s')
    module.release_gateway(first)
    second = module.acquire_gateway(mobile); second.bridge = bridge
    assert second.authorize_callback is first.authorize_callback and second.signup_callback is first.signup_callback
    assert not second.authorize_owner(authority,'s')
    authority.executing['s'] = 8
    assert second.authorize_owner(authority,'s')
    module.release_gateway(second)


def test_unload_fences_in_memory_without_hashing_or_disk_wait(env, monkeypatch):
    module, _, _, mobile, _, _, bridge, authorities, homes, _ = env
    authority = authorities[0]; authority.subjects['s'] = ('owner','basic','',str(homes[0])); authority.executing['s'] = 7
    coordinator = module.acquire_gateway(mobile); coordinator.bridge = bridge
    checked = []
    monkeypatch.setattr(bridge, 'attested', lambda: checked.append(True) or True)
    monkeypatch.setattr(coordinator, 'stop', lambda: None)
    module.release_gateway(coordinator)
    assert not checked and (id(authority),'s',7) in module._FENCED_TURNS


def test_unknown_reload_fence_refuses_bridge_readiness(env, monkeypatch):
    module, gateway, _, _, _, _, bridge, *_ = env
    gateway.shared = True
    monkeypatch.setattr(module, '_FENCE_UNKNOWN', True)
    assert gateway.guard('browser_vault')['action'] == 'block'
    assert not gateway.publish()


def test_dashboard_installs_one_stable_profile_scoped_readiness_reader(dashboard_harness):
    host, *_ = dashboard_harness
    runtime = sys.modules['beacon_vault_runtime']
    assert callable(runtime._gateway_attestor)
    assert host.vault_attestation.configure_dashboard(runtime, host.mobile, host._RUNNING_PLUGIN_VERSION) is True
    assert runtime._gateway_attestor is host.vault_attestation._DASHBOARD.readiness_callback


def test_gateway_plugin_registers_guard_and_background_start(dashboard_harness, monkeypatch):
    host, _, _, tmp, _ = dashboard_harness
    plugin = load_private('__init__')
    monkeypatch.setattr(sys, 'argv', ['hermes','gateway','run'])
    hooks, unloads, started = {}, [], []
    class FakeCoordinator:
        def __init__(self, mobile, version): self.mobile, self.version = mobile, version
        def guard(self, tool_name=None, **kw): return {'action':'block'} if tool_name == 'browser_vault' else None
        def start(self): started.append(threading.get_ident())
        def close(self): pass
        def stop(self): pass
    monkeypatch.setattr(plugin.vault_attestation, 'Coordinator', FakeCoordinator)
    monkeypatch.setattr(plugin.vault_attestation, '_GATEWAY', None, raising=False)
    monkeypatch.setattr(plugin.vault_attestation, '_GATEWAY_USERS', 0, raising=False)
    monkeypatch.setattr(plugin, '_record_gateway_plugin_version', lambda: None)
    monkeypatch.setattr(plugin, '_restart_stale_dashboard', lambda: None)
    ctx = SimpleNamespace(register_tool=lambda **kw: None, register_hook=lambda name, fn: hooks.setdefault(name, []).append(fn),
                          on_unload=lambda fn: unloads.append(fn))
    caller = threading.get_ident(); plugin.register(ctx)
    plugin._after_load_thread.join(3)
    guards = [fn for fn in hooks['pre_tool_call'] if getattr(fn, '__self__', None) is plugin._vault_coordinator]
    assert len(guards) == 1 and guards[0](tool_name='browser_vault')['action'] == 'block'
    assert started and started[0] != caller and unloads
    owner = plugin.vault_attestation; released = []
    monkeypatch.setattr(owner, 'release_gateway', lambda coordinator: released.append(coordinator))
    monkeypatch.setattr(plugin, 'vault_attestation', SimpleNamespace(release_gateway=lambda coordinator: None))
    unloads[-1]()
    assert released == [plugin._vault_coordinator]
