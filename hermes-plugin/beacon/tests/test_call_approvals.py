"""Call approval policy and grants use private homes and fake tool handlers only."""
import json
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from test_gateway_tools import Backend, Mobile, SCOPE, _hermes_agent_source, g
from test_mobile import harness


@pytest.fixture
def phone_tool_pool(harness, monkeypatch):
    # Join every fake handler before its patched native boundary and temporary home are restored.
    with ThreadPoolExecutor(max_workers=1) as pool:
        monkeypatch.setattr(sys.modules['beacon_gateway_tools'], 'POOL', pool)
        yield


@pytest.fixture
def approval_gateway(tmp_path, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    monkeypatch.syspath_prepend(str(_hermes_agent_source()))
    from tools import approval_smart
    state = {'row': {'user_id': 'password:owner', 'model_config': '{}'},
             'config': {'approvals': {'mode': 'manual'}}, 'verdict': 'escalate', 'checks': []}

    def read_store(path, *, read_only):
        assert path == tmp_path / 'state.db' and read_only is True
        return SimpleNamespace(get_session=lambda session: state['row'] if session == 'chat' else None, close=lambda: None)

    monkeypatch.setattr('hermes_cli.web_server_sessions._open_session_db_at_path', read_store)
    monkeypatch.setattr('hermes_cli.config.load_config_readonly', lambda: state['config'])

    def verdict(*args):
        state['checks'].append(args)
        return state['verdict']

    monkeypatch.setattr(approval_smart, '_smart_verdict', verdict)
    mobile = Mobile(tmp_path / 'receipts.sqlite')
    with mobile.connect() as conn:
        conn.execute('CREATE TABLE calls(id TEXT PRIMARY KEY,owner TEXT,profile TEXT,conversation TEXT,created REAL,closed INTEGER,status TEXT)')
        for call in ('call', 'other-call'):
            conn.execute('INSERT INTO calls VALUES(?,?,?,?,?,?,?)', (call, 'owner', 'profile', 'chat', time.time(), 0, 'pending'))

    def owned(conn, call, owner, profile, writable):
        row = conn.execute('SELECT * FROM calls WHERE id=? AND owner=? AND profile=?', (call, owner, profile)).fetchone()
        if row is None or writable and row['closed']:
            raise LookupError('Call unavailable')
        return dict(row)

    mobile.owned = owned
    backend = Backend()
    backend.read_only = False
    backend.approval_source = lambda tool, arguments, scope, session, root: g.NativeMcp().approval_source(tool, arguments, scope, session, root)
    original = backend.catalog
    backend.catalog = lambda discover=True: original(discover) + [{**original(discover)[0], 'tool_id': 'b' * 64, 'name': 'different_tool'}]
    api = g.Gateway(mobile, lambda profile, fn: fn(tmp_path), backend, lambda value, limit: str(value)[:limit])
    return api, backend, mobile, state


def invoke(api, request='r1', tool='a', scope=SCOPE):
    return api.invoke(scope, {'request_id': request, 'tool_id': tool * 64, 'arguments': {'query': 'weather'}}, session_id='chat')


def use_terminal(backend):
    terminal = {**backend.catalog()[0], '_kind': 'hermes', '_raw': 'terminal', 'name': 'terminal',
                'parameters': {'type': 'object', 'properties': {'command': {'type': 'string'}}, 'required': ['command']}}
    backend.catalog = lambda discover=True: [terminal]


def invoke_terminal(api, request='r1', command='echo safe'):
    return api.invoke(SCOPE, {'request_id': request, 'tool_id': 'a' * 64, 'arguments': {'command': command}},
                      session_id='chat')


def settled(api, request='r1', scope=SCOPE):
    deadline = time.monotonic() + 3
    while time.monotonic() < deadline:
        value = api.status(scope, request)
        if value['status'] not in ('ready', 'running'):
            return value
        time.sleep(.01)
    pytest.fail('Call tool did not settle')


@pytest.mark.parametrize('mode,yolo,expected', [
    ('manual', True, 'chat_yolo'), ('off', False, 'profile_off'),
    (False, False, 'profile_off'), ('manual', False, None), ('unknown', False, None),
])
def test_call_follows_verified_chat_and_profile(approval_gateway, mode, yolo, expected):
    api, backend, _, state = approval_gateway
    state['row']['model_config'] = json.dumps({'yolo_mode': yolo})
    state['config']['approvals']['mode'] = mode
    receipt = invoke(api)
    if expected:
        receipt = settled(api)
        assert receipt['status'] == 'completed'
        assert backend.calls[0][2:] == (True, 'chat')
        assert 'proposal' not in receipt
    else:
        assert receipt['status'] == 'needs_approval' and backend.calls == []
    assert receipt['approved_by'] == expected


@pytest.mark.parametrize('inline', ['memory', 'provider'])
def test_memory_tools_run_without_a_phone_prompt_like_in_hermes(approval_gateway, inline):
    api, backend, _, state = approval_gateway
    memory = {**backend.catalog()[0], '_kind': 'hermes', '_raw': 'memory', '_inline': inline}
    other = {**backend.catalog()[0], 'tool_id': 'b' * 64, '_kind': 'hermes', '_raw': 'write_file', 'name': 'write_file'}
    backend.catalog = lambda discover=True: [memory, other]
    receipt = invoke(api)
    assert receipt['approved_by'] == 'memory' and receipt['status'] in ('ready', 'running', 'completed')
    assert invoke(api, 'r2', 'b')['status'] == 'needs_approval'
    state['row'] = {'user_id': 'other:owner', 'model_config': '{}'}
    assert invoke(api, 'r3')['status'] == 'needs_approval'


@pytest.mark.parametrize('row', [None, {'user_id': 'other:owner', 'model_config': '{"yolo_mode":true}'},
    {'user_id': 'password:owner', 'model_config': 'broken'},
    {'user_id': 'password:owner', 'model_config': '[]'},
    {'user_id': 'password:owner', 'model_config': '{"yolo_mode":"false"}'}])
def test_unknown_or_foreign_chat_never_bypasses(approval_gateway, row):
    api, backend, _, state = approval_gateway
    state['row'] = row
    assert invoke(api)['status'] == 'needs_approval'
    assert backend.calls == []


def test_failed_policy_read_asks(approval_gateway, monkeypatch):
    api, backend, _, state = approval_gateway
    def unavailable(): raise OSError('private config unavailable')
    monkeypatch.setattr('hermes_cli.config.load_config_readonly', unavailable)
    receipt = invoke(api)
    assert receipt['status'] == 'needs_approval' and receipt['approved_by'] is None
    assert backend.calls == [] and 'private' not in json.dumps(receipt)


@pytest.mark.parametrize('verdict,expected', [('approve', 'smart'), ('deny', None), ('escalate', None)])
def test_native_smart_terminal_verdict(approval_gateway, verdict, expected):
    api, backend, _, state = approval_gateway
    state['config']['approvals']['mode'] = 'smart'
    state['verdict'] = verdict
    terminal = {**backend.catalog()[0], '_kind': 'hermes', '_raw': 'terminal', 'name': 'terminal',
                'parameters': {'type': 'object', 'properties': {'command': {'type': 'string'}}, 'required': ['command']}}
    backend.catalog = lambda discover=True: [terminal]
    receipt = api.invoke(SCOPE, {'request_id': 'r1', 'tool_id': 'a' * 64,
                                'arguments': {'command': 'echo safe'}}, session_id='chat')
    assert state['checks'] and state['checks'][0][0] == 'echo safe'
    if expected:
        receipt = settled(api)
        assert receipt['status'] == 'completed'
    else:
        assert receipt['status'] == 'needs_approval' and not backend.calls
    assert receipt['approved_by'] == expected


def test_smart_never_assesses_arbitrary_mcp_arguments_as_shell(approval_gateway):
    api, backend, _, state = approval_gateway
    state['config']['approvals']['mode'] = 'smart'
    state['verdict'] = 'approve'
    assert invoke(api)['status'] == 'needs_approval'
    assert state['checks'] == [] and backend.calls == []


def test_call_allowance_is_scoped_and_binds_to_the_approved_definition(approval_gateway):
    api, backend, _, _ = approval_gateway
    proposal = invoke(api)
    api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve_call'})
    assert settled(api)['approved_by'] == 'owner'
    invoke(api, 'r2')
    assert settled(api, 'r2')['approved_by'] == 'call_allowance'
    assert invoke(api, 'different', 'b')['status'] == 'needs_approval'
    other = (*SCOPE[:3], 'other-call')
    assert invoke(api, 'other', scope=other)['status'] == 'needs_approval'
    # Strix afd1b07 S10: the server republished the tool under the same name with another definition.
    backend.version = 'c'
    changed = invoke(api, 'changed-schema', 'c')
    assert changed['status'] == 'needs_approval' and changed['approved_by'] is None and 'proposal' in changed
    backend.version = 'a'
    invoke(api, 'unchanged-again')
    assert settled(api, 'unchanged-again')['approved_by'] == 'call_allowance'
    for scope in [('foreign', *SCOPE[1:]), (SCOPE[0], 'other-provider', *SCOPE[2:]),
                  (*SCOPE[:2], 'other-profile', SCOPE[3])]:
        assert invoke(api, 'isolated', 'a', scope)['status'] == 'needs_approval'
    assert len(backend.calls) == 3


def test_approve_once_does_not_grant_and_proposals_and_signatures_stay_exact(approval_gateway):
    api, backend, _, _ = approval_gateway
    proposal = invoke(api)
    for decision in ('approve', 'approve_call'):
        with pytest.raises(HTTPException, match='Approval proposal mismatch'):
            api.decide(SCOPE, 'r1', {'proposal_id': 'wrong', 'decision': decision})
    assert backend.calls == []
    api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
    assert settled(api)['approved_by'] == 'owner'
    assert invoke(api, 'r2')['status'] == 'needs_approval'
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE, {'request_id': 'r1', 'tool_id': 'a' * 64, 'arguments': {'query': 'changed'}}, session_id='chat')
    assert error.value.status_code == 409


def test_hermes_tool_cannot_be_allowed_for_the_call_and_the_next_call_asks_again(approval_gateway):
    api, backend, mobile, _ = approval_gateway
    use_terminal(backend)
    proposal = invoke_terminal(api)
    assert proposal['status'] == 'needs_approval' and proposal['proposal']['allow_call'] is False
    with pytest.raises(HTTPException) as error:
        api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve_call'})
    assert error.value.status_code == 409 and backend.calls == []
    assert api.status(SCOPE, 'r1')['status'] == 'needs_approval'
    with mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM call_tool_allowances').fetchone()[0] == 0
    # The owner can still approve this one command; it grants nothing further.
    api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
    assert settled(api)['approved_by'] == 'owner'
    next_call = invoke_terminal(api, 'r2')
    assert next_call['status'] == 'needs_approval' and next_call['approved_by'] is None
    assert len(backend.calls) == 1


def test_a_stored_allowance_never_approves_a_hermes_tool(approval_gateway):
    api, backend, _, _ = approval_gateway
    use_terminal(backend)
    with api.db() as conn:  # an allowance row written before Hermes tools were refused
        conn.execute('INSERT INTO call_tool_allowances VALUES(?,?,?,?,?,?,?)', (*SCOPE, 'a' * 64, 'terminal', time.time()))
    receipt = invoke_terminal(api, command='rm -rf build')
    assert receipt['status'] == 'needs_approval' and receipt['approved_by'] is None
    assert backend.calls == []  # execute() and its dangerous-command answer are never reached


def test_allowances_are_bounded_and_expired_proposals_cannot_grant(approval_gateway, monkeypatch):
    api, backend, mobile, _ = approval_gateway
    monkeypatch.setattr(g, 'MAX_CALL_ALLOWANCES', 1)
    proposal = invoke(api)
    api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve_call'})
    assert settled(api)['status'] == 'completed'
    other = invoke(api, 'r2', 'b')
    with pytest.raises(HTTPException) as error:
        api.decide(SCOPE, 'r2', {'proposal_id': other['proposal']['id'], 'decision': 'approve_call'})
    assert error.value.status_code == 429 and len(backend.calls) == 1
    with api.db() as conn:
        value = json.loads(api._row(conn, SCOPE, 'r2')['payload'])
        value['_expires'] = 0
        api._save(conn, SCOPE, 'r2', value)
    assert api.decide(SCOPE, 'r2', {'proposal_id': other['proposal']['id'], 'decision': 'approve_call'})['status'] == 'expired'
    with mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM call_tool_allowances').fetchone()[0] == 1


def test_a_call_closed_during_the_decision_cannot_receive_an_allowance(approval_gateway):
    api, backend, mobile, _ = approval_gateway
    proposal = invoke(api)
    with mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (SCOPE[3],))
    with pytest.raises(HTTPException) as error:
        api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve_call'})
    assert error.value.status_code == 409
    assert error.value.detail == 'Call is closed or unavailable; no allowance was granted'
    with mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM call_tool_allowances').fetchone()[0] == 0
    assert backend.calls == []


def test_real_mobile_removes_allowances_on_close_expiry_and_history_cleanup(harness):
    host, _, _, _, _ = harness
    mobile = host.mobile
    for mode in ('finalize', 'stale', 'delete'):
        call = mobile.reserve('owner', 'default', 'chat')['call_id']
        with mobile.connect() as conn:
            conn.execute('INSERT INTO call_tool_allowances VALUES(?,?,?,?,?,?,?)',
                         ('owner', 'basic', 'default', call, 'a' * 64, 'write_file', time.time()))
        if mode == 'finalize':
            mobile.finalize(call, 'owner', {})
        else:
            with mobile.connect() as conn:
                if mode == 'stale':
                    conn.execute('UPDATE calls SET created=0,seen=0 WHERE id=?', (call,))
                    mobile.prune_notices(conn)
                else:
                    conn.execute('DELETE FROM calls WHERE id=?', (call,))
        with mobile.connect() as conn:
            assert conn.execute('SELECT COUNT(*) FROM call_tool_allowances WHERE call_id=?', (call,)).fetchone()[0] == 0


def test_phone_wire_includes_reason_accepts_call_grant_and_rejects_client_authorization(harness, monkeypatch):
    from fastapi import FastAPI, Request
    from fastapi.testclient import TestClient
    host, _, _, root, _ = harness
    backend = Backend()
    backend.read_only = False
    monkeypatch.setattr(sys.modules['beacon_gateway_tools'], 'NativeMcp', lambda: backend)
    app = FastAPI()
    app.state.auth_required = True

    @app.middleware('http')
    async def authenticated(request: Request, call_next):
        request.state.session = SimpleNamespace(user_id='owner', provider='basic')
        return await call_next(request)

    app.include_router(sys.modules['beacon_gateway_api'].build_router(host.call, host.body,
                       lambda profile, fn: fn(root), host.mobile), prefix='/api/plugins/beacon')
    call = host.mobile.reserve('owner', 'default', 'native-stored')['call_id']
    path = f'/api/plugins/beacon/mobile/calls/{call}/tools'
    value = {'request_id': 'wire', 'tool_id': 'a' * 64, 'arguments': {'query': 'weather'}}
    with TestClient(app) as client:
        proposal = client.post(path + '/invoke', json=value).json()
        assert proposal['status'] == 'needs_approval' and proposal['approved_by'] is None
        decision = path + '/requests/wire/decision'
        bad = client.post(decision, json={'proposal_id': 'wrong', 'decision': 'approve_call'})
        assert bad.status_code == 409 and bad.json()['detail'] == 'Approval proposal mismatch'
        response = client.post(decision, json={'proposal_id': proposal['proposal']['id'], 'decision': 'approve_call'})
        assert response.status_code == 200 and response.json()['approved_by'] == 'owner'
        next_call = client.post(path + '/invoke', json={**value, 'request_id': 'next'})
        assert next_call.status_code == 200 and next_call.json()['approved_by'] == 'call_allowance'
        assert client.get(path + '/requests/next').json()['approved_by'] == 'call_allowance'
        forged = client.post(path + '/invoke', json={**value, 'request_id': 'forged', 'approved_by': 'chat_yolo'})
        assert forged.status_code == 400
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline and client.get(path + '/requests/next').json()['status'] == 'running':
            time.sleep(.01)
        assert client.get(path + '/requests/next').json()['status'] == 'completed'
        assert all(call[3] == 'native-stored' for call in backend.calls)


@pytest.mark.parametrize('yolo,hermes', [(False, False), (True, False), (False, True)])
def test_phone_bearer_wire_uses_native_policy_scope_and_persists_call_allowance(harness, monkeypatch, yolo, hermes, phone_tool_pool):
    from contextlib import nullcontext
    from test_mobile import reserve
    host, client, _, root, _ = harness
    native = sys.modules['beacon_gateway_tools'].NativeMcp
    server = sys.modules['tui_gateway'].server
    server._profile_home = lambda profile: None
    server._hermes_home = root
    server._session_profile_runtime_scope = lambda *args, **kwargs: nullcontext()
    tool = {'tool_id': 'f' * 64, 'description': 'Write a file', 'read_only': False, '_raw': 'write_file',
            'parameters': {'type': 'object', 'properties': {'path': {'type': 'string'}, 'content': {'type': 'string'}},
                           'required': ['path', 'content'], 'additionalProperties': False},
            **({'name': 'write_file', '_kind': 'hermes'} if hermes else {'name': 'mcp__files__write_file', '_server': 'files'})}
    monkeypatch.setattr(native, 'catalog', lambda self, discover=True: [tool])
    monkeypatch.setattr(native, 'cache_key', lambda self: 'phone-wire-policy')
    monkeypatch.setattr(native, 'execute', lambda *args, **kwargs: json.dumps({'ok': True}))
    store = SimpleNamespace(get_session=lambda session: {
        'user_id': 'basic:owner', 'model_config': json.dumps({'yolo_mode': yolo})}
        if session == 'native-stored' else None, close=lambda: None)
    def open_store(path, **kwargs):
        assert path == root / 'state.db' and kwargs == {'read_only': True}
        return store
    monkeypatch.setattr('hermes_cli.web_server_sessions._open_session_db_at_path', open_store)
    monkeypatch.setattr('hermes_cli.config.load_config_readonly', lambda: {'approvals': {'mode': 'manual'}})
    call = reserve(client)
    path = f'/api/plugins/beacon/mobile/calls/{call}/tools'
    headers = {'content-type': 'application/json; charset=utf-8', 'accept': 'application/json',
               'authorization': 'Bearer synthetic-fixture', 'x-fixture-bearer': 'owner',
               'x-beacon-device-id': 'paired-phone-01'}
    value = {'request_id': 'phone-request', 'tool_id': 'f' * 64, 'arguments': {'path': 'notes.txt', 'content': 'fixture'}}
    receipt = client.post(path + '/invoke?beacon_profile=default', json=value, headers=headers)
    assert receipt.status_code == 200, receipt.text
    receipt = receipt.json()
    def final(request_id):
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            response = client.get(path + '/requests/' + request_id + '?beacon_profile=default', headers=headers)
            assert response.status_code == 200, response.text
            result = response.json()
            if result['status'] not in ('ready', 'running'): return result
            time.sleep(0.01)
        pytest.fail('Fake phone tool did not settle')
    if yolo:
        assert receipt['approved_by'] == 'chat_yolo' and receipt['status'] != 'needs_approval'
        assert final('phone-request')['status'] == 'completed'
        return
    assert receipt['status'] == 'needs_approval'
    decide = path + '/requests/phone-request/decision?beacon_profile=default'
    if hermes:
        assert receipt['proposal']['allow_call'] is False
        refused = client.post(decide, json={'proposal_id': receipt['proposal']['id'], 'decision': 'approve_call'},
                              headers=headers)
        assert refused.status_code == 409, refused.text
    decision = client.post(decide, json={'proposal_id': receipt['proposal']['id'],
                                         'decision': 'approve' if hermes else 'approve_call'}, headers=headers)
    assert decision.status_code == 200, decision.text
    assert decision.json()['approved_by'] == 'owner' and decision.json()['status'] != 'needs_approval'
    assert final('phone-request')['status'] == 'completed'
    with host.mobile.connect() as conn:
        row = conn.execute('SELECT owner,provider,profile,call_id,tool_name FROM call_tool_allowances').fetchone()
    if hermes:
        assert row is None
        again = client.post(path + '/invoke?beacon_profile=default', json={**value, 'request_id': 'next-request'}, headers=headers)
        assert again.status_code == 200 and again.json()['status'] == 'needs_approval'
        return
    assert tuple(row) == ('owner', 'basic', 'default', call, 'mcp__files__write_file')
    again = client.post(path + '/invoke?beacon_profile=default', json={**value, 'request_id': 'next-request'}, headers=headers)
    assert again.status_code == 200 and again.json()['approved_by'] == 'call_allowance'
    assert final('next-request')['status'] == 'completed'
