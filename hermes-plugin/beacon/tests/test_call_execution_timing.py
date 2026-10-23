"""Bounded timing probes; no network, agent build or live Hermes writes."""
from contextlib import contextmanager
import json
import logging
import sys
import time
from types import SimpleNamespace

from test_gateway_tools import Backend, Mobile, SCOPE, g, settled


def test_native_metadata_scope_skips_external_secret_hydration(tmp_path, monkeypatch):
    scopes = []

    @contextmanager
    def runtime_scope(session, *, hydrate_secrets=True):
        scopes.append((session, hydrate_secrets))
        yield

    server = SimpleNamespace(_profile_home=lambda profile: tmp_path,
                             _session_profile_runtime_scope=runtime_scope)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    assert g.NativeMcp().scoped_metadata('profile', lambda root: root / 'state.db') == tmp_path / 'state.db'
    assert scopes == [({'profile_home': str(tmp_path)}, False)]
    server._profile_home = lambda profile: None
    server._hermes_home = str(tmp_path)
    assert g.NativeMcp().scoped_metadata('default', lambda root: root) == tmp_path
    assert scopes[-1] == ({'profile_home': None}, False)


def test_execution_environment_is_reused_only_within_the_call_and_approval_keys_are_unique(monkeypatch):
    sessions, approvals, dispatched = [], [], []
    context = SimpleNamespace(set_session_vars=lambda **values: sessions.append(values), clear_session_vars=lambda tokens: None)
    approval = SimpleNamespace(register_gateway_notify=lambda key, fn: approvals.append(key), unregister_gateway_notify=lambda key: None)
    approval_context = SimpleNamespace(set_current_session_key=lambda key: key, reset_current_session_key=lambda token: None)
    monkeypatch.setitem(sys.modules, 'gateway', SimpleNamespace(session_context=context))
    monkeypatch.setitem(sys.modules, 'tools', SimpleNamespace(approval=approval, approval_context=approval_context))
    monkeypatch.setitem(sys.modules, 'model_tools', SimpleNamespace(handle_function_call=lambda *a, **kw: dispatched.append(kw) or '{}'))
    backend = g.NativeMcp()
    terminal = {'name': 'terminal', '_raw': 'terminal', '_kind': 'hermes', 'read_only': False}
    scopes = [SCOPE, SCOPE, (*SCOPE[:3], 'other-call'), (SCOPE[0], 'other-provider', *SCOPE[2:]),
              (*SCOPE[:2], 'other-profile', SCOPE[3]), ('other-owner', *SCOPE[1:])]
    for scope in scopes:
        backend.execute(terminal, {'command': 'echo safe'}, scope, True, session_id='chat')
    keys = [session['session_key'] for session in sessions]
    assert keys[0] == keys[1] and len(set(keys)) == len(scopes) - 1
    assert len(set(approvals)) == len(scopes) and not set(approvals) & set(keys)
    assert all(call['session_id'] == 'chat' for call in dispatched)
    assert all(session['profile'] == scope[2] for session, scope in zip(sessions, scopes))


def test_catalogue_uses_metadata_scope_and_execution_keeps_credential_scope(tmp_path, caplog):
    events = []
    backend = Backend()
    backend.read_only = False

    def metadata(profile, fn):
        events.append('metadata')
        return fn(tmp_path)

    def credential_scope(profile, fn):
        events.append('credentials')
        time.sleep(.03)
        return fn(tmp_path)

    backend.scoped_metadata = metadata
    execute = backend.execute

    def slow_execute(*args, **kwargs):
        time.sleep(.06)
        return execute(*args, **kwargs)

    backend.execute = slow_execute
    mobile = Mobile(tmp_path / 'receipts.sqlite')
    api = g.Gateway(mobile, credential_scope, backend)
    api.scoped = metadata
    with caplog.at_level(logging.INFO):
        proposal = api.invoke(SCOPE, {'request_id': 'slow', 'tool_id': 'a' * 64, 'arguments': {'query': 'weather'}})
        assert events == ['metadata']
        api.decide(SCOPE, 'slow', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
        result = settled(api, 'slow')
    assert result['status'] == 'completed'
    assert events.count('credentials') == 1 and events.count('metadata') >= 2
    with api.db() as conn:
        timing = json.loads(api._row(conn, SCOPE, 'slow')['payload'])['_timings_ms']
    assert timing['execute'] >= 40 and timing['profile'] >= 20
    assert timing['total'] >= timing['execute'] + timing['profile']
    assert {'queue', 'authority', 'catalogue', 'profile', 'execute', 'total'} <= timing.keys()
    assert all(value >= 0 for value in timing.values())
    assert '_timings_ms' not in result
    records = [record.getMessage() for record in caplog.records if 'Beacon call tool timing' in record.getMessage()]
    assert len(records) == 1 and 'weather' not in records[0] and 'owner' not in records[0]
