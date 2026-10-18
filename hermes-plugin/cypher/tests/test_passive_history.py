"""Passive history must never create, attach or prewarm an agent."""
import contextlib
import importlib.util
from pathlib import Path
import sys
import threading
from types import SimpleNamespace

import pytest
from test_mobile import harness


@pytest.fixture
def reader(monkeypatch):
    spec = importlib.util.spec_from_file_location('passive_native', Path(__file__).parents[1] / 'native.py')
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    row = {'id': 'owned', 'user_id': 'basic:owner', 'title': 'Saved conversation', 'model': 'model', 'cwd': '/workspace'}
    calls = []
    def messages(stored, **kwargs):
        calls.append((stored, kwargs))
        return [{'_row_id': 42, 'role': 'user', 'content': 'Saved message'}]
    db = SimpleNamespace(get_session=lambda stored: row if stored == 'owned' else None, get_messages_as_conversation=messages)
    server = SimpleNamespace(_open_requests=lambda sid: [], _pending_approval_request_payload=lambda sid: None,
                             _profile_home=lambda profile: '/profiles/' + profile, _profile_db=lambda params: contextlib.nullcontext(db),
                             _sessions_lock=threading.RLock(), _sessions={}, _session_auth_user_id=lambda live: live.get('auth_user_id'))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    monkeypatch.setitem(sys.modules, 'tui_gateway.session_history', SimpleNamespace(_history_to_messages=lambda history, **kwargs: history))
    native.rpc = lambda *args: pytest.fail('A passive read invoked native RPC')
    return native, server, row, calls


def test_saved_history_keeps_identity_and_never_creates_a_runtime(reader):
    native, server, row, calls = reader
    for _ in range(10):
        result = native.conversation_snapshot('owner', 'basic', 'default', 'owned')
        assert result['session_id'] == 'owned' and result['profile'] == 'default'
        assert result['messages'][0]['_row_id'] == 42
        assert result['active_stream_id'] == '' and result['passive_read'] is True
    assert server._sessions == {} and len(calls) == 10


def test_foreign_owner_is_denied_before_reading_history(reader):
    native, server, row, calls = reader
    with pytest.raises(LookupError):
        native.conversation_snapshot('another-owner', 'basic', 'default', 'owned')
    assert calls == []


def test_recent_list_never_offers_foreign_or_unopenable_profile_rows(reader):
    native, server, row, calls = reader
    native.rpc = lambda *args: {'sessions': [{'id': 'foreign', 'title': 'private'}, {'id': 'owned'}, {'id': 'owned'}]}
    result = native.conversation_list('owner', 'basic', 'default')
    assert [item['id'] for item in result['sessions']] == ['owned']
    assert calls == []


def test_only_exact_owned_live_draft_can_be_read_without_persisting(reader):
    native, server, row, calls = reader
    live = {'session_key': 'draft', 'profile_home': '/profiles/default', 'auth_user_id': 'basic:owner', 'history': [], 'running': False}
    server._sessions['runtime'] = live
    assert native.conversation_snapshot('owner', 'basic', 'default', 'draft')['runtime_session_id'] == 'runtime'
    for owner, profile in [('other', 'default'), ('owner', 'coder')]:
        with pytest.raises(LookupError): native.conversation_snapshot(owner, 'basic', profile, 'draft')
    assert server._sessions == {'runtime': live} and calls == []


def test_idle_history_does_not_attach_open_requests_to_saved_transcript(reader):
    native, server, _, _ = reader
    live = {'session_key': 'owned', 'profile_home': '/profiles/default', 'auth_user_id': 'basic:owner', 'running': False}
    server._sessions['runtime'] = live
    server._open_requests = lambda sid: [{'id': 'question', 'method': 'clarify', 'params': {'question': 'Which option?'}}]
    server._pending_approval_request_payload = lambda sid: {'request_id': 'approval', 'choices': ['once', 'deny']}
    value = native.conversation_snapshot('owner', 'basic', 'default', 'owned')
    assert value['active_stream_id'] == ''
    assert value['messages'][0]['_row_id'] == 42
    assert 'pending_clarification' not in value and 'pending_approval' not in value
    assert server._sessions == {'runtime': live}


def test_passive_endpoint_requires_owner_and_exact_profile(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    seen = []
    monkeypatch.setattr(api.native, 'conversation_snapshot', lambda *args: seen.append(args) or {'session_id': args[-1], 'profile': args[-2]})
    assert client.get('/api/plugins/cypher/mobile/sessions/owned?profile=default').status_code == 200
    assert seen == [('owner', 'basic', 'default', 'owned')]
    assert client.get('/api/plugins/cypher/mobile/sessions/owned?profile=foreign').status_code == 403
    client.headers['x-fixture-owner'] = 'other'
    assert client.get('/api/plugins/cypher/mobile/sessions/owned?profile=default').status_code == 403
    assert len(seen) == 1
