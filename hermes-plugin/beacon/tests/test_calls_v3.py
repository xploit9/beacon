"""Call contract v3 (CALLS-CONTRACT.md, lane A): declarations, one-request tools, the updates channel, the one-round-trip
hand-over, call-start diagnostics, budgets and 1.2.0-app skew. Synthetic data only; no owner Hermes, no network."""
import asyncio
import hashlib
import json
import sqlite3
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import HTTPException

from test_gateway_tools import Backend, Mobile, SCOPE
from test_gateway_api import api, invoke_api  # noqa: F401 -- the gateway route fixture
from test_mobile import harness, reserve  # noqa: F401 -- the plugin route fixture
from test_canonical_same_chat import canonical_call, subject as canonical_subject  # noqa: F401 -- Hermes' own authority

FIXTURES = Path(__file__).parent / 'fixtures' / 'calls_v3'
BASE = '/api/plugins/beacon/mobile'
V3 = {'x-beacon-call-contract': '3'}


@pytest.fixture(autouse=True)
def tool_lists_are_left_as_found():
    """The tool-list caches are process-wide; a list one test builds must not reach another test's call context."""
    g = tools()
    with g.CATALOG_LOCK: saved = (dict(g.CATALOG_CACHE), dict(g.LAST_CATALOG))
    yield
    with g.CATALOG_LOCK:
        g.CATALOG_CACHE.clear(); g.CATALOG_CACHE.update(saved[0])
        g.LAST_CATALOG.clear(); g.LAST_CATALOG.update(saved[1])


def fixture(name):
    return json.loads((FIXTURES / name).read_text(encoding='utf-8'))


def tools():
    return sys.modules['beacon_gateway_tools']


def kinds(value):
    """A value's JSON shape: key sets and value types, recursively for objects (lists by their first item)."""
    if isinstance(value, dict): return {key: kinds(item) for key, item in value.items()}
    if isinstance(value, list): return [kinds(value[0])] if value else []
    if value is None: return None
    return 'number' if type(value) in (int, float) else type(value).__name__


# ---------------------------------------------------------------- fixtures

def test_every_contract_fixture_is_valid_json_with_synthetic_ids_only():
    names = sorted(path.name for path in FIXTURES.glob('*.json'))
    assert len(names) >= 25
    for name in names:
        text = (FIXTURES / name).read_text(encoding='utf-8')
        json.loads(text)
        assert '@' not in text, name
    context = fixture('context_v3.json')
    assert context['tool_declarations_digest'] == hashlib.sha256(
        tools().encoded(context['tool_declarations']).encode()).hexdigest()[:16]
    assert set(context['call_capabilities']) == {'tool_declarations', 'invoke_wait', 'call_updates', 'handover', 'call_diagnostics', 'tool_receipt_cause'}
    for item in context['tool_declarations']:
        assert tools().DECLARED_NAME.fullmatch(item['name']) and len(item['tool_id']) == 64
        assert tools().portable_schema(item['parameters']) == item['parameters'], 'fixture declarations are already portable'


# ---------------------------------------------------------------- section 1: names

def test_declared_names_are_provider_safe_reserved_free_and_deterministic():
    g = tools()
    rows = [{'tool_id': c * 64, 'name': name} for c, name in zip('abcdefgh', [
        'session_search', 'mcp.notes:search', '1password_get', 'hermes_work', 'phone_call', 'hermes_notes',
        'x' * 70, 'y' * 70])]
    names = g.declared_names(rows)
    assert names['a' * 64] == 'session_search'
    assert names['b' * 64] == 'mcp_notes_search'
    assert names['c' * 64] == 't_1password_get'
    assert names['d' * 64] == 't_hermes_work' and names['e' * 64] == 't_phone_call' and names['f' * 64] == 't_hermes_notes'
    assert names['g' * 64] == 'x' * 55 + '_' + hashlib.sha256(('x' * 70).encode()).hexdigest()[:8]
    assert all(g.DECLARED_NAME.fullmatch(name) and len(name) <= 64 for name in names.values())
    assert g.declared_names(list(reversed(rows))) == names


def test_colliding_names_all_take_a_hash_suffix():
    g = tools()
    names = g.declared_names([{'tool_id': 'a' * 64, 'name': 'notes.search'}, {'tool_id': 'b' * 64, 'name': 'notes:search'},
                              {'tool_id': 'c' * 64, 'name': 'other'}])
    assert names['a' * 64] == 'notes_search_' + hashlib.sha256(b'notes.search').hexdigest()[:8]
    assert names['b' * 64] == 'notes_search_' + hashlib.sha256(b'notes:search').hexdigest()[:8]
    assert names['c' * 64] == 'other'


# ---------------------------------------------------------------- section 1: portable schemas

def test_portable_schema_inlines_refs_merges_allof_and_rewrites_unions():
    g = tools()
    schema = {'type': 'object', '$defs': {'Item': {'type': 'object', 'properties': {'id': {'type': 'string'}}, 'required': ['id', 'gone']}},
              'properties': {
                  'item': {'$ref': '#/$defs/Item'},
                  'choice': {'oneOf': [{'type': 'string'}, {'type': 'integer'}]},
                  'both': {'allOf': [{'properties': {'a': {'type': 'string'}}, 'required': ['a']},
                                     {'properties': {'b': {'type': 'number'}}}]},
                  'maybe': {'anyOf': [{'type': 'string'}, {'type': 'null'}], 'description': 'Optional.'},
                  'nullable': {'type': 'integer', 'nullable': True},
                  'kind': {'const': 'fixed'}, 'level': {'const': 3, 'type': 'integer'},
                  'size': {'type': 'integer', 'enum': [1, 2, 3]},
                  'free': {}, 'list': {'items': {'type': 'string'}},
                  'strict': {'type': 'object', 'additionalProperties': False, 'patternProperties': {'x': {}}, 'properties': {}}},
              'required': ['item', 'missing']}
    out = g.portable_schema(schema)
    assert out['required'] == ['item']
    assert out['properties']['item'] == {'type': 'object', 'properties': {'id': {'type': 'string'}}, 'required': ['id']}
    assert out['properties']['choice'] == {'anyOf': [{'type': 'string'}, {'type': 'integer'}]}
    assert out['properties']['both'] == {'type': 'object', 'properties': {'a': {'type': 'string'}, 'b': {'type': 'number'}}, 'required': ['a']}
    assert out['properties']['maybe'] == {'type': ['string', 'null'], 'description': 'Optional.'}
    assert out['properties']['nullable'] == {'type': ['integer', 'null']}
    assert out['properties']['kind'] == {'type': 'string', 'enum': ['fixed']}
    assert out['properties']['level'] == {'type': 'integer', 'description': 'Must be 3.'}
    assert out['properties']['size'] == {'type': 'integer', 'description': 'Allowed values: 1, 2, 3.'}
    assert out['properties']['free'] == {'type': 'string'}
    assert out['properties']['list'] == {'type': 'array', 'items': {'type': 'string'}}
    assert out['properties']['strict'] == {'type': 'object', 'properties': {}}
    allowed = {'type', 'description', 'enum', 'properties', 'required', 'items', 'anyOf', 'minItems', 'maxItems', 'minLength',
               'maxLength', 'pattern', 'minimum', 'maximum', 'format', 'default', 'title'}
    def walk(node, depth=1):
        assert set(node) <= allowed and depth <= 6
        for child in node.get('properties', {}).values(): walk(child, depth + 1)
        if 'items' in node: walk(node['items'], depth + 1)
        for member in node.get('anyOf', []): walk(member, depth + 1)
    walk(out)


@pytest.mark.parametrize('schema', [
    {'type': 'object', 'properties': {'a': {'$ref': '#/$defs/A'}}, '$defs': {'A': {'type': 'object', 'properties': {'b': {'$ref': '#/$defs/A'}}}}},
    {'type': 'object', 'properties': {'a': {'$ref': 'https://example.com/schema'}}},
    {'type': 'object', 'properties': {'a': {'allOf': [{'type': 'string'}, {'type': 'integer'}]}}},
    {'type': 'object', 'properties': {'a': {'type': 'object', 'properties': {'b': {'type': 'object', 'properties': {'c': {'type': 'object',
        'properties': {'d': {'type': 'object', 'properties': {'e': {'type': 'object', 'properties': {'f': {'type': 'string'}}}}}}}}}}}}},
    {'type': 'object', 'properties': {str(i): {'type': 'string'} for i in range(65)}},
    {'type': 'string'},
])
def test_a_schema_that_cannot_be_portable_omits_its_tool(schema):
    assert tools().portable_schema(schema) is None


def test_every_declared_array_has_items_so_gemini_accepts_the_session():
    # Lane C's Gemini Live probe: one array without items refuses the whole session setup.
    def arrays(node):
        if not isinstance(node, dict): return
        kind = node.get('type')
        if kind == 'array' or isinstance(kind, list) and 'array' in kind: yield node
        for child in list(node.get('properties', {}).values()) + node.get('anyOf', []) + ([node['items']] if 'items' in node else []):
            yield from arrays(child)
    example = fixture('declaration_array_without_items.json')
    portable = tools().portable_schema(example['source'])
    assert portable == example['portable'] and len(list(arrays(portable))) == 5
    assert all(node.get('items') for node in arrays(portable))
    block = tools().tool_declarations([row('mcp_tags_search', server='tags', parameters=example['source'])])
    assert block['tool_declarations_complete'] is True and block['tool_declarations'][0]['parameters'] == example['portable']


# ---------------------------------------------------------------- section 1: the declarations block

def row(name, read_only=True, kind=None, server=None, order=0, toolset='file', parameters=None, description='Synthetic tool.'):
    tool = {'tool_id': hashlib.sha256(name.encode()).hexdigest(), 'name': name, 'description': description, 'read_only': read_only,
            'parameters': parameters or {'type': 'object', 'properties': {'query': {'type': 'string'}}, 'required': ['query']}}
    if kind == 'hermes': tool.update(_kind='hermes', _toolset=toolset, _raw=name)
    else: tool.update(_server=server, _raw=name.split('_', 1)[-1], _server_order=order)
    return tool


def test_declarations_order_native_first_then_servers_in_config_order_reads_first():
    g = tools()
    rows = [row('mcp_zeta_write', False, server='zeta', order=0), row('mcp_alpha_read', True, server='alpha', order=1),
            row('patch', False, 'hermes', toolset='file'), row('read_file', True, 'hermes', toolset='file'),
            row('mcp_zeta_read', True, server='zeta', order=0), row('memory', False, 'hermes', toolset='memory')]
    block = g.tool_declarations(rows)
    assert [item['name'] for item in block['tool_declarations']] == [
        'read_file', 'patch', 'memory', 'mcp_zeta_read', 'mcp_zeta_write', 'mcp_alpha_read']
    assert block['tool_declarations_complete'] is True and block['tool_declarations_omitted'] == 0
    assert block['tool_declarations_digest'] == hashlib.sha256(g.encoded(block['tool_declarations']).encode()).hexdigest()[:16]
    item = block['tool_declarations'][0]
    assert set(item) == {'name', 'description', 'parameters', 'read_only', 'tool_id'}


def test_declarations_stop_at_the_budget_and_count_what_they_leave_out():
    g = tools()
    long = 'Synthetic description. ' * 25
    rows = [row(f'mcp_bulk_tool_{i:03d}', server='bulk', description=long) for i in range(200)]
    rows.append(row('mcp_bad_tool', server='bulk', parameters={'type': 'object', 'properties': {'a': {'$ref': '#/nope'}}}))
    rows.append(row('mcp_huge_tool', server='bulk', parameters={'type': 'object', 'properties': {
        f'p{i}': {'type': 'string', 'description': 'x' * 290} for i in range(20)}}))
    block = g.tool_declarations(rows)
    size = len(g.encoded(block['tool_declarations']).encode())
    assert size <= g.DECLARATIONS_BUDGET
    assert block['tool_declarations_complete'] is False
    assert block['tool_declarations_omitted'] == len(rows) - len(block['tool_declarations'])
    assert all(len(g.encoded(item).encode()) <= g.DECLARATION_LIMIT for item in block['tool_declarations'])


# ---------------------------------------------------------------- section 2: invoke by name, tool-level refusals as receipts

@pytest.fixture
def gateway(tmp_path):
    backend = Backend(); mobile = Mobile(tmp_path / 'v3.sqlite')
    instance = tools().Gateway(mobile, lambda profile, fn: fn(tmp_path), backend, lambda value, limit: str(value)[:limit])
    yield instance, backend, mobile


def settled_v3(gateway, request):
    for _ in range(200):
        receipt = gateway.status_v3(SCOPE, request)
        if receipt['status'] != 'running': return receipt
        time.sleep(.01)
    pytest.fail('receipt never settled')


@pytest.mark.parametrize('handle', ['configured_search', 'a' * 64])
def test_v3_invoke_resolves_a_declared_name_or_tool_id(gateway, handle):
    api, backend, _ = gateway
    first = api.invoke(SCOPE, {'request_id': 'r1', 'tool': handle, 'arguments': {'query': 'weather'}})
    assert first['tool'] == 'configured_search' and first['status'] in ('running', 'completed')
    done = settled_v3(api, 'r1')
    assert done['status'] == 'completed' and done['ran_on_hermes'] is True and done['result'] == {'result': 'real search result'}
    assert set(done['server_ms']) == {'queue', 'authority', 'catalogue', 'profile', 'execute', 'total'}
    assert set(done) == set(fixture('invoke_completed.json'))
    assert len(backend.calls) == 1


def test_v3_unknown_tool_and_bad_arguments_are_failed_receipts_not_http_errors(gateway):
    api, backend, mobile = gateway
    unknown = api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'no_such_tool', 'arguments': {}})
    assert unknown['status'] == 'failed' and unknown['error_code'] == 'tool_unknown' and unknown['ran_on_hermes'] is False
    assert kinds(unknown).keys() == kinds(fixture('invoke_failed_tool_unknown.json')).keys()
    bad = api.invoke(SCOPE, {'request_id': 'r2', 'tool': 'configured_search', 'arguments': {'query': 7}})
    assert bad['status'] == 'failed' and bad['error_code'] == 'invalid_arguments' and bad['reason'].startswith('query:')
    missing = api.invoke(SCOPE, {'request_id': 'r3', 'tool': 'configured_search', 'arguments': {}})
    assert 'query' in missing['reason']
    assert set(bad) == set(fixture('invoke_failed_invalid_arguments.json'))
    assert backend.calls == []
    with mobile.connect() as conn: assert conn.execute('SELECT COUNT(*) FROM tool_receipts').fetchone()[0] == 0


def test_v3_same_request_id_with_other_arguments_is_a_conflict_and_never_runs_twice(gateway):
    api, backend, _ = gateway
    api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'one'}})
    settled_v3(api, 'r1')
    again = api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'one'}})
    assert again['status'] == 'completed'
    with pytest.raises(HTTPException) as error:
        api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'two'}})
    assert error.value.status_code == 409
    assert len(backend.calls) == 1
    with pytest.raises(HTTPException) as both:
        api.invoke(SCOPE, {'request_id': 'r9', 'tool': 'configured_search', 'tool_id': 'a' * 64, 'arguments': {'query': 'x'}})
    assert both.value.status_code == 400


@pytest.mark.parametrize('status,name', [('needs_approval', 'invoke_needs_approval.json'), ('denied', 'invoke_denied.json'),
                                         ('expired', 'invoke_expired.json'), ('cancelled', 'invoke_cancelled.json')])
def test_v3_receipt_shapes_match_the_contract_fixtures(gateway, status, name):
    api, backend, mobile = gateway
    backend.read_only = False
    proposal = api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    assert proposal['status'] == 'needs_approval' and proposal['ran_on_hermes'] is False and proposal['expires_at'] > time.time()
    if status == 'denied': api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'deny'})
    if status == 'cancelled': api.cancel(SCOPE, 'r1')
    if status == 'expired':
        with mobile.connect() as conn:
            payload = json.loads(conn.execute('SELECT payload FROM tool_receipts').fetchone()[0]); payload['_expires'] = time.time() - 1
            conn.execute('UPDATE tool_receipts SET payload=?', (json.dumps(payload),)); conn.commit()
    receipt = api.status_v3(SCOPE, 'r1')
    assert receipt['status'] == status
    expected = fixture(name)
    assert set(receipt) == set(expected)
    shape = lambda value: {key: set(item) if key == 'proposal' else kinds(item) for key, item in value.items() if key != 'server_ms'}
    assert shape(receipt) == shape(expected)


def test_v3_failure_codes_name_what_happened(gateway):
    api, backend, _ = gateway
    backend.result = {'error': 'synthetic failure'}
    api.invoke(SCOPE, {'request_id': 'read', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    failed = settled_v3(api, 'read')
    assert (failed['status'], failed['error_code'], failed['ran_on_hermes']) == ('failed', 'tool_error', True)
    backend.read_only = False
    proposal = api.invoke(SCOPE, {'request_id': 'write', 'tool': 'configured_search', 'arguments': {'query': 'x'}, 'approval': 'owner'})
    assert proposal['status'] in ('running', 'uncertain')
    uncertain = settled_v3(api, 'write')
    assert (uncertain['status'], uncertain['error_code']) == ('uncertain', 'outcome_unknown')
    assert set(uncertain) == set(fixture('invoke_uncertain.json'))
    v2 = api.status(SCOPE, 'write')
    assert 'error_code' not in v2 and not any(key.startswith('_') for key in v2), 'a 1.2.0 app sees its own fields only'


def test_v3_execution_capacity_is_a_failed_receipt(gateway, monkeypatch):
    api, backend, _ = gateway
    g = tools()
    for _ in range(4): assert g.SLOTS.acquire(blocking=False)
    try:
        receipt = api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    finally:
        for _ in range(4): g.SLOTS.release()
    assert (receipt['status'], receipt['error_code'], receipt['ran_on_hermes']) == ('failed', 'capacity', False)
    assert backend.calls == []


# ---------------------------------------------------------------- section 2: the route waits for the result

def invoke_v3(client, request='request', wait_ms=8000, headers=V3, **extra):
    return client.post('/mobile/calls/call/tools/invoke', json={'request_id': request, 'tool': 'configured_search',
                       'arguments': {'query': 'synthetic'}, 'wait_ms': wait_ms, **extra}, headers=headers)


def test_invoke_waits_for_the_result_in_one_request(api):
    client, dispatcher, backend, _ = api
    backend.wait = threading.Event()
    threading.Timer(0.3, backend.wait.set).start()
    started = time.perf_counter()
    response = invoke_v3(client)
    elapsed = time.perf_counter() - started
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['status'] == 'completed' and body['result'] == {'result': 'real search result'}
    # Tool result end to end: execute time plus at most 500 ms (CALLS-CONTRACT.md section 9).
    assert elapsed <= body['server_ms']['execute'] / 1000 + 0.5


def test_invoke_answers_running_when_the_wait_ends_and_needs_approval_at_once(api):
    client, dispatcher, backend, _ = api
    backend.wait = threading.Event()
    try:
        started = time.perf_counter()
        running = invoke_v3(client, wait_ms=200).json()
        assert running['status'] == 'running' and running['ran_on_hermes'] is True and 0.15 < time.perf_counter() - started < 2
        assert set(running) == set(fixture('invoke_running.json'))
    finally:
        backend.wait.set()
    backend.read_only = False
    started = time.perf_counter()
    card = invoke_v3(client, request='approval', approval='ask').json()
    assert card['status'] == 'needs_approval' and time.perf_counter() - started < 1
    decided = client.post('/mobile/calls/call/tools/requests/approval/decision',
                          json={'proposal_id': card['proposal']['id'], 'decision': 'approve', 'wait_ms': 8000}, headers=V3)
    assert decided.status_code == 200 and decided.json()['status'] == 'completed'


def test_invoke_overhead_stays_within_budget(api):
    client, _, _, _ = api
    body = invoke_v3(client).json()
    assert body['status'] == 'completed'
    overhead = sum(body['server_ms'][name] for name in ('queue', 'authority', 'catalogue'))
    assert overhead <= 100, body['server_ms']


def test_v3_request_errors_carry_an_error_code(api):
    client, _, _, mobile = api
    invoke_v3(client, request='same')
    conflict = client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'same', 'tool': 'configured_search',
                           'arguments': {'query': 'other'}}, headers=V3)
    assert conflict.status_code == 409 and conflict.json()['error_code'] == 'request_conflict'
    bad = client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'x', 'tool': 'configured_search',
                      'arguments': {}, 'wait_ms': 9000}, headers=V3)
    assert bad.status_code == 400 and bad.json()['error_code'] == 'invalid_request'
    unknown = client.get('/mobile/calls/other/tools/requests/x', headers=V3)
    assert unknown.status_code == 404 and unknown.json()['error_code'] == 'call_unknown'
    mobile.closed = True
    closed = invoke_v3(client, request='late')
    assert closed.status_code == 409 and closed.json()['error_code'] == 'call_closed'
    assert set(closed.json()) == set(fixture('request_error_call_closed.json'))


# ---------------------------------------------------------------- section 8: a 1.2.0 app keeps today's answers

V2_RECEIPT = {'request_id', 'tool_id', 'tool_name', 'status', 'approved_by'}


def test_a_v2_invoke_answers_exactly_as_before(api):
    client, dispatcher, backend, _ = api
    first = invoke_api(client)
    assert first.status_code == 200
    assert V2_RECEIPT <= set(first.json()) and not {'tool', 'ran_on_hermes', 'server_ms', 'read_only'} & set(first.json())
    status = client.get('/mobile/calls/call/tools/requests/request').json()
    assert not {'tool', 'ran_on_hermes', 'server_ms', 'error_code'} & set(status)
    unknown = client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'gone', 'tool_id': 'f' * 64, 'arguments': {}})
    assert unknown.status_code == 409 and 'error_code' not in unknown.json()
    bad = client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'bad', 'tool_id': 'a' * 64, 'arguments': {'query': 7}})
    assert bad.status_code == 400 and set(bad.json()) == {'detail'}


def test_a_v3_app_may_still_name_a_tool_by_its_tool_id(api):
    client, _, _, _ = api
    response = client.post('/mobile/calls/call/tools/invoke', json={'request_id': 'r', 'tool_id': 'a' * 64,
                           'arguments': {'query': 'x'}, 'wait_ms': 8000}, headers=V3)
    assert response.status_code == 200 and response.json()['status'] == 'completed' and response.json()['tool'] == 'configured_search'


# ---------------------------------------------------------------- section 3: the updates channel (unit)

def update_store(tmp_path):
    updates = tools().call_updates()
    conn = sqlite3.connect(tmp_path / 'updates.sqlite'); conn.row_factory = sqlite3.Row
    conn.execute(updates.DDL)
    conn.execute('CREATE TABLE calls(id TEXT,closed INTEGER,ended REAL,created REAL)')
    return updates, conn


def test_updates_are_contiguous_deduplicated_and_reset_outside_the_window(tmp_path):
    updates, conn = update_store(tmp_path)
    assert updates.append(conn, 'c', 'receipt', 'r1', {'status': 'running'}, 'running') == 1
    assert updates.append(conn, 'c', 'receipt', 'r1', {'status': 'running'}, 'running') is None
    assert updates.append(conn, 'c', 'approval', 'r2', {'request_id': 'r2'}, 'needs_approval') == 2
    assert updates.append(conn, 'c', 'receipt', 'r2', {'status': 'completed'}, 'completed') == 3
    page = updates.page(conn, 'c', 0, False)
    assert [e['seq'] for e in page['events']] == [1, 2, 3] and page['next_after'] == 3 and not page['reset']
    assert updates.page(conn, 'c', 3, False)['events'] == []
    reset = updates.page(conn, 'c', 9, False)
    assert reset['reset'] and [e['seq'] for e in reset['events']] == [1, 3] and reset['next_after'] == 3


def test_updates_keep_the_newest_window_and_the_last_event_of_every_key(tmp_path):
    updates, conn = update_store(tmp_path)
    updates.append(conn, 'c', 'job', 'old-job', {'status': 'working'}, 'working')
    for i in range(updates.RETAIN + 40): updates.append(conn, 'c', 'receipt', f'r{i % 10}', {'step': i}, f'state-{i}')
    seqs = [r[0] for r in conn.execute('SELECT seq FROM call_updates WHERE call_id=? ORDER BY seq', ('c',))]
    newest = seqs[-1]
    assert 1 in seqs, 'the only event of a key stays for the snapshot'
    assert seqs[1:] == list(range(newest - updates.RETAIN + 1, newest + 1))
    behind = updates.page(conn, 'c', 5, False)
    assert behind['reset'] and behind['events'][0]['seq'] == 1
    assert len(updates.page(conn, 'c', newest - 100, False)['events']) == 64


def test_a_closed_call_says_so_only_on_its_last_page(tmp_path):
    updates, conn = update_store(tmp_path)
    for i in range(70): updates.append(conn, 'c', 'receipt', f'r{i}', {'status': 'completed'}, 'completed')
    updates.record_closed(conn, 'c')
    first = updates.page(conn, 'c', 0, True)
    assert len(first['events']) == 64 and first['closed'] is False
    last = updates.page(conn, 'c', first['next_after'], True)
    assert last['closed'] is True and last['events'][-1] == {**last['events'][-1], 'type': 'call', 'status': 'closed'}


def test_receipt_changes_are_recorded_in_the_same_transaction(gateway):
    api, backend, mobile = gateway
    backend.read_only = False
    proposal = api.invoke(SCOPE, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    api.decide(SCOPE, 'r1', {'proposal_id': proposal['proposal']['id'], 'decision': 'approve'})
    settled_v3(api, 'r1')
    with mobile.connect() as conn:
        events = [(r['type'], r['state']) for r in conn.execute('SELECT type,state FROM call_updates ORDER BY seq')]
    assert events == [('approval', 'needs_approval'), ('receipt', 'running'), ('receipt', 'uncertain')] or \
        events == [('approval', 'needs_approval'), ('receipt', 'running'), ('receipt', 'completed')]


# ---------------------------------------------------------------- section 3: the updates route

def plugin_tools(api, root, read_only=True):
    backend = Backend(); backend.read_only = read_only
    gateway = sys.modules['beacon_gateway_tools'].Gateway(api.mobile, lambda profile, fn: fn(root), backend)
    return gateway, backend


def test_the_updates_route_delivers_receipts_jobs_and_the_close(harness):
    api, client, _, root, _ = harness
    call_id = reserve(client)
    gateway, _ = plugin_tools(api, root, read_only=False)
    scope = ('owner', 'basic', 'default', call_id)
    gateway.invoke(scope, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute("INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status,history_updated,"
                     "result,callback_consent) VALUES(?,?,?,?,?,?,?,?,?,?,NULL,?,?,?,?)",
                     ('d4' * 16, call_id, 'owner', 'default', 'native-stored', 'native-stored', 'handover-1', 'Synthetic', 1,
                      'canonical', 'completed', time.time(), 'Synthetic summary.', 'requested'))
    page = client.get(f'{BASE}/calls/{call_id}/updates', params={'after': 0, 'wait_ms': 0}, headers=V3).json()
    assert [e['type'] for e in page['events']] == ['approval', 'job']
    approval, job = page['events']
    assert set(approval) == set(fixture('updates_page.json')['events'][1])
    assert set(job) - {'tool_request_id'} <= set(fixture('updates_page.json')['events'][3]) | {'error', 'error_code'}
    assert job['status'] == 'done' and job['result'] == 'Synthetic summary.' and job['callback_consent'] == 'requested'
    assert client.get(f'{BASE}/calls/{call_id}/updates', params={'after': page['next_after'], 'wait_ms': 0}).json()['events'] == []
    client.request('DELETE', f'{BASE}/calls/{call_id}', json={})
    closed = client.get(f'{BASE}/calls/{call_id}/updates', params={'after': page['next_after'], 'wait_ms': 0}).json()
    assert closed['closed'] is True and closed['events'][-1]['type'] == 'call'
    assert set(closed) == set(fixture('updates_page.json'))


def test_updates_wake_within_half_a_second_of_a_stored_change(harness):
    api, client, _, root, _ = harness
    call_id = reserve(client)
    gateway, backend = plugin_tools(api, root)
    scope = ('owner', 'basic', 'default', call_id)
    after = client.get(f'{BASE}/calls/{call_id}/updates', params={'wait_ms': 0}).json()['next_after']
    backend.wait = threading.Event()
    stored = {}
    def change():
        time.sleep(0.4)
        gateway.invoke(scope, {'request_id': 'r1', 'tool': 'configured_search', 'arguments': {'query': 'x'}})
        stored['at'] = time.perf_counter()
    worker = threading.Thread(target=change); worker.start()
    try:
        page = client.get(f'{BASE}/calls/{call_id}/updates', params={'after': after, 'wait_ms': 10000}).json()
        answered = time.perf_counter()
    finally:
        backend.wait.set(); worker.join(5)
    assert page['events'] and page['events'][0]['request_id'] == 'r1'
    assert answered - stored['at'] <= 0.5


def test_updates_refuse_bad_cursors_with_an_error_code(harness):
    _, client, _, _, _ = harness
    call_id = reserve(client)
    response = client.get(f'{BASE}/calls/{call_id}/updates', params={'after': -1})
    assert response.status_code == 400 and response.json()['error_code'] == 'invalid_request'
    assert client.get(f'{BASE}/calls/unknown/updates').json()['error_code'] == 'call_unknown'


# ---------------------------------------------------------------- section 4: the hand-over

class FakeUpstream:
    """Hermes' canonical socket as the native GatewayClient sees it; answers come from `answer(method, params)`."""
    subprotocol = 'hermes-gateway-v1'

    def __init__(self, answer):
        from websockets.protocol import State
        self.State, self.state = State, State.OPEN
        self.answer, self.sent, self.inbox = answer, [], asyncio.Queue()

    async def recv(self):
        return json.dumps({'jsonrpc': '2.0', 'method': 'event', 'params': {'type': 'gateway.ready', 'payload': {'session_authority': True}}})

    async def send(self, raw):
        frame = json.loads(raw); self.sent.append(frame)
        reply = self.answer(frame['method'], frame['params'])
        if asyncio.iscoroutine(reply): reply = await reply
        if reply == 'drop':
            await self.inbox.put(None)
            return
        await self.inbox.put(json.dumps({'jsonrpc': '2.0', 'id': frame['id'], **reply}))

    def __aiter__(self): return self

    async def __anext__(self):
        raw = await self.inbox.get()
        if raw is None:
            self.state = self.State.CLOSED
            raise StopAsyncIteration
        return raw

    async def close(self):
        self.state = self.State.CLOSED
        await self.inbox.put(None)


def admitted(method, params):
    if method == 'session.resume': return {'result': {'session_id': params['session_id'], 'subscription_id': 'sub-1'}}
    if method == 'image.attach_bytes': return {'result': {'attached': True, 'path': 'media/voice-frame.jpg', 'mime': 'image/jpeg'}}
    if method == 'prompt.receipt':
        return {'result': {'admission_id': params['admission_id'], 'ref': {'profile_id': 'home', 'session_id': params['session_id']},
                           'sequence': 1, 'status': 'started', 'outcome': None, 'authority_epoch': 7, 'execution_generation': 1}}
    if method == 'prompt.submit':
        return {'result': {'admission_id': 'e5' * 16, 'ref': {'profile_id': 'home', 'session_id': params['session_id']}, 'sequence': 1,
                           'status': 'queued', 'outcome': None, 'authority_epoch': 7, 'execution_generation': None}}
    return {'result': {'detached': True}}


@pytest.fixture
def handover(harness, monkeypatch):
    api, client, cfg, root, app = harness
    issuer = 'https://issuer.example'
    del client.headers['x-fixture-owner']
    @app.middleware('http')
    async def signed_in(request, call_next):
        request.state.session = SimpleNamespace(user_id='owner', provider='basic', issuer=issuer, display_name='Maya Example',
                                                access_token='synthetic-token')
        return await call_next(request)
    from gateway.session_identity import authenticated_subject
    principal = authenticated_subject({'user_id': 'owner', 'provider': 'basic', 'issuer': issuer})
    call_id = api.mobile.reserve('owner', 'default', 'native-stored', None, principal)['call_id']
    transport = sys.modules['beacon_canonical_transport']
    state = SimpleNamespace(answer=admitted, sockets=[], confirmed=[], principal=principal, call_id=call_id, api=api, client=client)
    endpoint = SimpleNamespace(instance_id='instance', authority_epoch=7, api_origin='http://127.0.0.1:9', profile_id=str(root))
    monkeypatch.setattr(transport, 'native_policy', lambda profile: root)
    monkeypatch.setattr(transport, 'discover', lambda home: endpoint)
    def exchange(endpoint, profile, token, nonce, *args, **kwargs):
        assert token == 'synthetic-token', 'the hand-over uses the phone\'s own sign-in'
        return {'profile': profile, 'home': transport.home_digest(root), 'instance': 'instance', 'epoch': 7, 'gateway_epoch': 7,
                'nonce': nonce, 'subject': transport.subject_digest(SimpleNamespace(provider='basic', issuer=issuer, user_id='owner')),
                'ticket': 'synthetic-ticket'}
    monkeypatch.setattr(transport, 'exchange', exchange)
    async def connect(url, **kwargs):
        socket = FakeUpstream(state.answer); state.sockets.append(socket); return socket
    monkeypatch.setattr(transport, 'no_redirect_connect', connect)
    monkeypatch.setattr(transport, 'verify_live_session', lambda request, profile, stored, runtime, timeout=5: {'stored': stored})
    monkeypatch.setattr(api.native, 'profile_fingerprint', lambda profile: 'f' * 64)
    def confirm(mobile, pending, receipt, media=False):
        state.confirmed.append((pending, receipt, media))
        with mobile.LOCK, mobile.connect() as conn:
            conn.execute('UPDATE jobs SET admission=? WHERE request=?', (receipt['admission_id'], pending['request']))
        return True
    monkeypatch.setattr(api.native, 'confirm_remote_receipt', confirm)
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: False)
    yield state


def post_handover(state, **body):
    value = {'request_id': 'handover-1', 'text': 'Find three synthetic flights and put them in the chat.', **body}
    return state.client.post(f'{BASE}/calls/{state.call_id}/handover', json=value, headers=V3)


def test_handover_is_one_canonical_admission_in_a_single_round_trip(handover):
    # The first admission of a process also imports Hermes' gateway client; the budget is for a running dashboard.
    assert post_handover(handover, request_id='warm-up').json()['status'] == 'queued'
    handover.sockets.clear(); handover.confirmed.clear()
    started = time.perf_counter()
    response = post_handover(handover, callback=True)
    elapsed = time.perf_counter() - started
    assert response.status_code == 200, response.text
    body = response.json()
    assert body['status'] == 'queued' and body['admission_id'] == 'e5' * 16 and body['callback_consent'] == 'requested'
    assert set(body) == set(fixture('handover_queued.json')) and body['server_ms']['total'] <= 500 and elapsed < 2
    methods = [frame['method'] for frame in handover.sockets[0].sent]
    assert methods == ['session.resume', 'prompt.submit', 'session.detach']
    submit = handover.sockets[0].sent[1]['params']
    assert submit == {'profile': 'default', 'session_id': 'native-stored', 'text': 'Find three synthetic flights and put them in the chat.',
                      'submission_id': 'handover-1', 'surface': 'voice-live', 'queued': True}
    pending, receipt, media = handover.confirmed[0]
    assert pending['principal'] == handover.principal and pending['request'] == 'handover-1' and media is False
    with handover.api.mobile.connect() as conn:
        job = dict(conn.execute('SELECT * FROM jobs WHERE request=?', ('handover-1',)).fetchone())
    assert job['id'] == body['job_id'] and job['callback_consent'] == 'requested' and job['intent_callback'] == 1
    assert job['work_mode'] == 'background' and job['epoch'] == 'canonical' and job['principal'] == handover.principal


def test_handover_resend_of_an_admitted_request_never_submits_again(handover):
    first = post_handover(handover).json()
    again = post_handover(handover).json()
    assert again['job_id'] == first['job_id'] and again['admission_id'] == first['admission_id'] and again['status'] == 'started'
    assert [f['method'] for f in handover.sockets[1].sent] == ['session.resume', 'prompt.receipt', 'session.detach']
    assert first['callback_consent'] == 'unset'


def test_handover_refusal_is_not_sent_and_a_lost_answer_is_uncertain(handover):
    handover.answer = lambda method, params: ({'error': {'code': 4001, 'message': 'queue_full'}} if method == 'prompt.submit'
                                              else admitted(method, params))
    refused = post_handover(handover).json()
    assert refused['status'] == 'not_sent' and refused['error_code'] == 'queue_full' and refused['job_id'] is None
    assert set(refused) == set(fixture('handover_not_sent.json'))
    with handover.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE request=?', ('handover-1',)).fetchone()[0] == 0, 'nothing admitted, no job'
    handover.answer = lambda method, params: 'drop' if method == 'prompt.submit' else admitted(method, params)
    lost = post_handover(handover, request_id='handover-2').json()
    assert lost['status'] == 'uncertain' and lost['error_code'] == 'admission_unconfirmed' and lost['job_id']
    assert set(lost) == set(fixture('handover_uncertain.json'))
    handover.answer = admitted
    resent = post_handover(handover, request_id='handover-2').json()
    assert resent['status'] == 'queued' and resent['job_id'] == lost['job_id']
    assert handover.sockets[-1].sent[1]['params']['submission_id'] == 'handover-2'


def test_handover_attaches_the_shared_frame_on_the_same_socket(handover):
    import base64
    body = post_handover(handover, frame_jpeg=base64.b64encode(b'synthetic-jpeg').decode(), mode='inline').json()
    assert body['status'] == 'queued'
    methods = [frame['method'] for frame in handover.sockets[0].sent]
    assert methods == ['session.resume', 'image.attach_bytes', 'prompt.submit', 'session.detach']
    assert handover.sockets[0].sent[2]['params']['attachments'] == [{'path': 'media/voice-frame.jpg', 'mime': 'image/jpeg'}]
    assert handover.confirmed[0][2] is True


def test_handover_refuses_before_hermes_when_nothing_can_be_sent(handover, monkeypatch):
    transport = sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(transport, 'discover', lambda home: (_ for _ in ()).throw(ValueError('Gateway unavailable')))
    assert post_handover(handover).json()['error_code'] == 'transport_unavailable'
    bad = handover.client.post(f'{BASE}/calls/{handover.call_id}/handover', json={'request_id': 'x', 'text': ''}, headers=V3)
    assert bad.status_code == 400 and bad.json()['error_code'] == 'invalid_request'
    handover.client.request('DELETE', f'{BASE}/calls/{handover.call_id}', json={})
    assert post_handover(handover).json()['error_code'] == 'call_closed'


def test_handover_needs_the_calls_own_verified_principal(harness):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    response = client.post(f'{BASE}/calls/{call_id}/handover', json={'request_id': 'h', 'text': 'Synthetic request.'})
    assert response.status_code == 200 and response.json()['error_code'] == 'session_unavailable'


# ---------------------------------------------------------------- section 5: call-start diagnostics

def diagnostics_row(event='6f1c2a5e-0b7d-4c3a-9e2f-1a2b3c4d5e6f', **extra):
    return {**fixture('diagnostics_request.json')['diagnostics'][0], 'client_event_id': event, **extra}


def test_diagnostics_are_stored_once_within_limits(harness):
    api, client, _, _, _ = harness
    response = client.post(f'{BASE}/call-diagnostics', json=fixture('diagnostics_request.json'))
    assert response.status_code == 200 and response.json() == fixture('diagnostics_response.json')
    assert client.post(f'{BASE}/call-diagnostics', json=fixture('diagnostics_request.json')).json() == {'stored': 1}
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM call_diagnostics').fetchone()[0] == 1
    for bad in ({'diagnostics': []}, {'diagnostics': [diagnostics_row()] * 21}, {'diagnostics': [diagnostics_row(text='hello')]},
                {'diagnostics': [diagnostics_row(stages=[{'stage': 'grant_ok', 'elapsed_ms': 1}] * 33)]},
                {'diagnostics': [diagnostics_row(shown='free text')]}, {'diagnostics': [diagnostics_row(provider='other')]}):
        refused = client.post(f'{BASE}/call-diagnostics', json=bad)
        assert refused.status_code in (400, 413) and refused.json()['error_code'] == 'invalid_request', bad
    assert client.post(f'{BASE}/call-diagnostics', json={'diagnostics': [diagnostics_row(stages=[{'stage': 'reach_failed', 'elapsed_ms': 3000,
        'error_code': 'timeout', 'cause_class': 'socket_timeout'}])]}).status_code == 200


def test_diagnostics_keep_the_newest_rows_per_owner(harness, monkeypatch):
    api, _, _, _, _ = harness
    monkeypatch.setattr(api.mobile, 'CALL_DIAGNOSTICS_PER_OWNER', 3)
    for i in range(5):
        api.mobile.store_call_diagnostics('owner', '', {'diagnostics': [diagnostics_row(f'{i:08d}-0000-0000-0000-000000000000')]})
    with api.mobile.connect() as conn:
        kept = [r[0] for r in conn.execute('SELECT client_event_id FROM call_diagnostics ORDER BY received')]
    assert len(kept) == 3 and kept[-1].startswith('00000004')


# ---------------------------------------------------------------- sections 0, 1, 7: capabilities and contexts

def warm(api, profile, rows):
    g = sys.modules['beacon_gateway_tools']
    with g.CATALOG_LOCK:
        g.CATALOG_CACHE[(profile, 'synthetic-key')] = (time.monotonic(), rows)


def test_bootstrap_and_both_contexts_advertise_the_same_capabilities(harness, monkeypatch):
    api, client, _, root, _ = harness
    voice = api.voice_context
    monkeypatch.setattr(voice, 'scoped', lambda profile, fn, **kwargs: fn(root))
    monkeypatch.setattr(voice, 'personality', lambda root: 'Warm and brief.')
    monkeypatch.setattr(voice, 'builtin_memory', lambda root, cfg: '')
    monkeypatch.setattr(voice, 'history', lambda *args: [])
    # The pre-call route asks for a background warm-up; a real one would build Hermes' tool registry inside this test's scope.
    warmed = []
    monkeypatch.setattr(voice, 'prefetch_tools', lambda profile: warmed.append(profile) or True)
    warm(api, 'default', [row('read_file', True, 'hermes'), row('mcp_notes_search', True, server='notes')])
    try:
        boot = client.get(f'{BASE}/bootstrap').json()
        call_id = reserve(client)
        context = client.get(f'{BASE}/calls/{call_id}/context').json()
        before = client.get(f'{BASE}/voice/context', params={'session': 'native-stored'}).json()
    finally:
        sys.modules['beacon_gateway_tools'].CATALOG_CACHE.pop(('default', 'synthetic-key'), None)
    assert warmed == ['default']
    for value in (boot, context, before):
        assert value['call_contract'] == 3 and value['call_capabilities'] == boot['call_capabilities']
    assert boot['call_capabilities']['handover'] is False, 'no verified principal, no canonical relay: the app keeps v2 hand-over'
    for value in (context, before):
        assert [item['name'] for item in value['tool_declarations']] == ['read_file', 'mcp_notes_search']
        assert value['tool_declarations_complete'] is True and value['persona'] == 'Warm and brief.'
        assert {'preferred_language', 'local_time', 'system_instruction', 'greeting', 'tool_catalogue'} <= set(value)
        assert 'greeting_plan' not in value
        assert set(fixture('context_v3.json')) - {'owner_display_name'} <= set(value)


def test_a_cold_tool_list_declares_nothing_and_never_builds_one_in_the_context(harness, monkeypatch):
    api, client, _, root, _ = harness
    voice = api.voice_context
    monkeypatch.setattr(voice, 'scoped', lambda profile, fn, **kwargs: fn(root))
    monkeypatch.setattr(voice, 'history', lambda *args: [])
    monkeypatch.setattr(sys.modules['beacon_gateway_tools'].Gateway, 'catalog', lambda *args, **kwargs: pytest.fail('context built a list'))
    sys.modules['beacon_gateway_tools'].LAST_CATALOG.pop('default', None)
    context = client.get(f'{BASE}/calls/{reserve(client)}/context').json()
    assert context['tool_declarations'] == [] and context['tool_declarations_complete'] is False


def test_context_declarations_budget(harness, monkeypatch):
    api, client, _, root, _ = harness
    voice = api.voice_context
    monkeypatch.setattr(voice, 'scoped', lambda profile, fn, **kwargs: fn(root))
    monkeypatch.setattr(voice, 'history', lambda *args: [])
    rows = [row(f'mcp_server{i % 7}_tool_{i:03d}', i % 3 == 0, server=f'server{i % 7}', order=i % 7,
                description='Synthetic tool that does one thing. ' * 6) for i in range(300)]
    warm(api, 'default', rows)
    try:
        call_id = reserve(client)
        client.get(f'{BASE}/calls/{call_id}/context')
        started = time.perf_counter()
        response = client.get(f'{BASE}/calls/{call_id}/context')
        elapsed = time.perf_counter() - started
    finally:
        sys.modules['beacon_gateway_tools'].CATALOG_CACHE.pop(('default', 'synthetic-key'), None)
    assert response.status_code == 200 and elapsed <= 0.5
    assert len(json.dumps(response.json()['tool_declarations']).encode()) <= 64 * 1024
    assert response.json()['tool_declarations_complete'] is False and response.json()['tool_declarations_omitted'] > 0


# ---------------------------------------------------------------- section 9: grant and authorisation budgets

def test_grant_budget(harness):
    _, client, _, _, _ = harness
    provider = client.get(f'{BASE}/voice-settings?profile=default').json()['selected_provider']
    seconds = []
    for _ in range(20):
        started = time.perf_counter()
        response = client.post(f'{BASE}/calls', json={'profile': 'default', 'voice_provider': provider, 'client_key': True})
        seconds.append(time.perf_counter() - started)
        assert response.status_code == 200, response.text
        assert {'call_id', 'conversation_id', 'transport', 'profile', 'provider'} <= set(response.json())
        assert 'greeting' not in response.json()
        client.request('DELETE', f"{BASE}/calls/{response.json()['call_id']}", json={})
    assert sorted(seconds)[18] <= 0.3, seconds


def test_authority_cached(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    api._call_authority.clear()
    slow = []
    original = api.reject_unowned_conversation
    def reject(*args, **kwargs):
        slow.append('ownership'); time.sleep(0.3); return original(*args, **kwargs)
    monkeypatch.setattr(api, 'reject_unowned_conversation', reject)
    request = SimpleNamespace(headers={}, query_params={}, state=SimpleNamespace(session=SimpleNamespace(user_id='owner', provider='basic')),
                              app=SimpleNamespace(state=SimpleNamespace(auth_required=True)), method='GET')
    started = time.perf_counter(); asyncio.run(api.call(request, call_id)); first = time.perf_counter() - started
    started = time.perf_counter(); asyncio.run(api.call(request, call_id)); second = time.perf_counter() - started
    assert first >= 0.3 and second <= 0.05 and slow == ['ownership']


def test_every_request_still_checks_the_call_is_the_owners(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    assert client.get(f'{BASE}/calls/{call_id}').status_code == 200
    monkeypatch.setattr(api.native, 'verify', lambda *args: (_ for _ in ()).throw(LookupError('not owned')))
    assert client.get(f'{BASE}/calls/{call_id}').status_code == 404


def test_the_call_contract_header_is_counted_per_phone(harness):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    device = client.post('/api/plugins/beacon/devices/link', json={'device_name': 'Pixel'}).json()['device_id']
    client.get(f'{BASE}/calls/{call_id}', headers={'x-beacon-device-id': device})
    client.get(f'{BASE}/calls/{call_id}', headers={'x-beacon-device-id': device, **V3})
    with api.mobile.connect() as conn:
        keys = {r[0] for r in conn.execute("SELECT key FROM meta WHERE key LIKE 'call-contract-seen:%'")}
    assert {'call-contract-seen:2:' + device, 'call-contract-seen:3:' + device} <= keys


def test_the_timeline_accepts_v3_receipt_codes(harness):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    response = client.post(f'{BASE}/calls/{call_id}/events', json={'events': [
        {'event_id': 'e1', 'type': 'tool_receipt', 'at_ms': 10, 'request_id': 'r1', 'tool_name': 'notes_search', 'status': 'failed',
         'error_code': 'tool_unknown'}]})
    assert response.status_code == 200, response.text


# ---------------------------------------------------------------- review fixes (REVIEW-CALLS-A.md)

def test_no_update_row_outlives_its_call_and_closed_calls_lose_them_without_any_poll(harness):
    """Finding 1: the events go with the call row (any deletion path) and 15 minutes after the close, whether or not a phone
    ever reads /updates (a 1.2.0 app never does)."""
    api, client, _, root, _ = harness
    mobile = api.mobile
    gateway, _ = plugin_tools(api, root, read_only=False)
    calls = {name: reserve(client) for name in ('open', 'recent', 'old', 'retained')}
    for name, call_id in calls.items():
        gateway.invoke(('owner', 'basic', 'default', call_id), {'request_id': 'r-' + name, 'tool': 'configured_search', 'arguments': {'query': 'x'}})
    now = time.time()
    with mobile.LOCK, mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1,ended=? WHERE id=?', (now - 60, calls['recent']))
        conn.execute('UPDATE calls SET closed=1,ended=? WHERE id IN (?,?)', (now - 40 * 86400, calls['old'], calls['retained']))
        conn.execute("INSERT INTO call_updates(call_id,seq,type,key,payload,at,state) VALUES('no-call-row',1,'job','j','{}',0,'s')")
        # History retention deletes the call row (mobile._prune_history): its events go in the same statement.
        conn.execute('DELETE FROM calls WHERE id=?', (calls['retained'],))
        assert conn.execute('SELECT COUNT(*) FROM call_updates WHERE call_id=?', (calls['retained'],)).fetchone()[0] == 0
        mobile._call_updates_pruned[0] = 0
        mobile.prune_notices(conn)
        left = {r[0] for r in conn.execute('SELECT DISTINCT call_id FROM call_updates')}
    assert left == {calls['open'], calls['recent']}


def test_a_resend_whose_earlier_try_hermes_admitted_is_reported_as_admitted(handover, monkeypatch):
    """Finding 2: the first try could not attach the frame and its answer was lost; the resend attaches it, so Hermes answers
    admission_conflict. That admission is this hand-over: same job, reported admitted, never discarded or sent again."""
    import base64
    from contextlib import contextmanager
    transport = sys.modules['beacon_canonical_transport']
    frame = base64.b64encode(b'synthetic-jpeg').decode()
    text = 'Find three synthetic flights and put them in the chat.'
    handover.answer = lambda method, params: ({'error': {'code': 4001, 'message': 'internal'}} if method == 'image.attach_bytes'
                                              else 'drop' if method == 'prompt.submit' else admitted(method, params))
    lost = post_handover(handover, frame_jpeg=frame).json()
    assert lost['status'] == 'uncertain' and lost['job_id']
    assert handover.sockets[0].sent[2]['params']['text'] == text + transport.FRAME_NOT_ATTACHED
    held = {'admission_id': 'e5' * 16, 'payload': {'text': text + transport.FRAME_NOT_ATTACHED}}
    server = sys.modules['tui_gateway'].server
    @contextmanager
    def profile_db(params, **kwargs): yield object()
    monkeypatch.setattr(server, '_profile_db', profile_db, raising=False)
    monkeypatch.setattr(handover.api.native, 'find_admission', lambda db, owner, provider, stored, principal, request: held)
    handover.answer = lambda method, params: ({'error': {'code': 4001, 'message': 'admission_conflict'}} if method == 'prompt.submit'
                                              else admitted(method, params))
    again = post_handover(handover, frame_jpeg=frame).json()
    assert again['status'] == 'started' and again['admission_id'] == 'e5' * 16 and again['job_id'] == lost['job_id']
    methods = [f['method'] for f in handover.sockets[-1].sent]
    assert methods == ['session.resume', 'image.attach_bytes', 'prompt.submit', 'prompt.receipt', 'session.detach']
    pending, receipt, media = handover.confirmed[-1]
    assert media is False and pending['payload_digest'] == handover.api.native._payload_fingerprint(
        handover.api.native.remote_payload({'session_id': 'native-stored', 'text': text + transport.FRAME_NOT_ATTACHED,
                                            'submission_id': 'handover-1', 'surface': 'voice-live', 'queued': True}))
    with handover.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE request=?', ('handover-1',)).fetchone()[0] == 1
    # Hermes holds another request under an id (other words): a conflict, nothing resent, and that id's job stays.
    held['payload'] = {'text': 'Something else entirely.'}
    handover.answer = lambda method, params: 'drop' if method == 'prompt.submit' else admitted(method, params)
    assert post_handover(handover, request_id='handover-9').json()['status'] == 'uncertain'
    handover.answer = lambda method, params: ({'error': {'code': 4001, 'message': 'admission_conflict'}} if method == 'prompt.submit'
                                              else admitted(method, params))
    conflict = post_handover(handover, request_id='handover-9')
    assert conflict.status_code == 409 and conflict.json()['error_code'] == 'request_conflict'
    with handover.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE request=?', ('handover-9',)).fetchone()[0] == 1


def test_a_refused_resend_never_deletes_the_earlier_tries_job(handover):
    handover.answer = lambda method, params: 'drop' if method == 'prompt.submit' else admitted(method, params)
    first = post_handover(handover).json()
    assert first['status'] == 'uncertain'
    handover.answer = lambda method, params: ({'error': {'code': 4001, 'message': 'queue_full'}} if method == 'prompt.submit'
                                              else admitted(method, params))
    assert post_handover(handover).json()['error_code'] == 'queue_full'
    with handover.api.mobile.connect() as conn:
        assert conn.execute('SELECT id FROM jobs WHERE request=?', ('handover-1',)).fetchone()[0] == first['job_id']


def test_a_slow_hermes_answers_within_the_handover_deadline(handover, monkeypatch):
    """Review finding 8: one deadline for the whole hand-over; nothing was submitted, so not_sent, and no job is left."""
    transport = sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(transport, 'HANDOVER_SECONDS', 0.6)
    async def slow(method, params):
        if method == 'session.resume': await asyncio.sleep(0.4)
        return admitted(method, params)
    handover.answer = slow
    monkeypatch.setattr(transport, 'verify_live_session', lambda *args: time.sleep(0.3) or {})
    started = time.perf_counter()
    body = post_handover(handover).json()
    assert body['status'] == 'not_sent' and body['error_code'] == 'transport_unavailable' and time.perf_counter() - started < 2
    assert 'prompt.submit' not in [f['method'] for f in handover.sockets[0].sent]
    with handover.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE request=?', ('handover-1',)).fetchone()[0] == 0


@pytest.mark.parametrize('failure,status,code', [(RuntimeError('Native conversation store unavailable'), 200, 'transport_unavailable'),
                                                 (ValueError('Canonical admission unavailable'), 200, 'transport_unavailable'),
                                                 (HTTPException(403, 'Plugin not authorized'), 403, 'not_owned')])
def test_handover_failures_answer_inside_the_contract(handover, monkeypatch, failure, status, code):
    transport = sys.modules['beacon_canonical_transport']
    def fail(*args, **kwargs): raise failure
    monkeypatch.setattr(transport, 'verify_live_session', fail)
    response = post_handover(handover)
    assert response.status_code == status and response.json()['error_code'] == code


@pytest.mark.parametrize('with_frame', [False, True])
def test_handover_provenance_runs_through_hermes_own_admission(canonical_call, monkeypatch, tmp_path, with_frame):
    """Finding 3, as far as it runs without a live gateway: Hermes' own AuthorityConnection admits the exact params the plugin
    sends (frame staged and captured by Hermes for the media case), the plugin's real live-session proof, payload digest and
    confirm_remote_receipt accept it, and the native watch settles the job onto /updates. Stand-ins: the loopback HTTP leg
    (discover, the token exchange) and the WebSocket transport around Hermes' connection."""
    import base64
    from contextlib import contextmanager
    from hermes_state import SessionDB
    from hermes_state_runtime import begin_runtime_epoch, claim_session_input, settle_session_input
    from gateway.config import Platform
    from gateway.platforms.base import get_image_cache_dir
    from gateway.session import SessionSource
    from gateway.session_authority import SessionAuthority, LiveSession
    from gateway.session_contract import SessionRef
    from gateway.session_controls import AuthorityConnection
    api, client, _, sessions, _, _, server = canonical_call
    transport = sys.modules['beacon_canonical_transport']
    db = SessionDB(tmp_path / 'native.sqlite')
    try:
        db.create_session('same-chat', 'local', user_id=canonical_subject())
        epoch = begin_runtime_epoch(db, instance_id='synthetic-instance')
        runner = SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False), _draining=False,
                                 _voice_key_for_source=lambda source: 'synthetic-route',
                                 session_runtime_descriptor={'state': 'ready', 'instance_id': 'synthetic-instance', 'authority_epoch': epoch})
        authority = SessionAuthority(runner, profile_id=str(tmp_path), instance_id='synthetic-instance', db=db, epoch=epoch)
        runner.session_authority = authority
        live = LiveSession(SessionSource(platform=Platform.LOCAL, chat_id='same-chat', user_id=canonical_subject(), chat_type='dm'),
                           'synthetic-route')
        authority.sessions['same-chat'] = live
        identity = {'provider': 'basic', 'issuer': sessions[0].issuer, 'user_id': 'owner'}
        connection = AuthorityConnection(authority, SimpleNamespace(auth_identity=identity), identity, operator=True)
        monkeypatch.setattr(authority, '_schedule_admitted', lambda *args: None)  # admit, never run an agent
        monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=runner))
        monkeypatch.setattr(server, '_profile_home', lambda profile: tmp_path)
        @contextmanager
        def profile_db(params, **kwargs): yield db
        monkeypatch.setattr(server, '_profile_db', profile_db)
        monkeypatch.setattr(api, 'activate', lambda *args: None)
        gateway_side = SimpleNamespace(app=SimpleNamespace(state=SimpleNamespace(gateway_runner=runner, session_authority=authority)),
                                       state=SimpleNamespace(session=sessions[0]))
        monkeypatch.setattr(transport, 'verify_live_session', lambda request, profile, stored, runtime, timeout=5:
                            transport.canonical_live_proof(gateway_side, profile, tmp_path, stored, runtime, 'a' * 64))
        endpoint = SimpleNamespace(instance_id='synthetic-instance', authority_epoch=epoch, api_origin='http://127.0.0.1:9',
                                   profile_id=str(tmp_path))
        monkeypatch.setattr(transport, 'native_policy', lambda profile: tmp_path)
        monkeypatch.setattr(transport, 'discover', lambda home: endpoint)
        monkeypatch.setattr(transport, 'exchange', lambda endpoint, profile, token, nonce, *args, **kwargs: {
            'profile': profile, 'home': transport.home_digest(tmp_path), 'instance': 'synthetic-instance', 'epoch': epoch,
            'gateway_epoch': epoch, 'nonce': nonce, 'subject': transport.subject_digest(sessions[0]), 'ticket': 'synthetic-ticket'})
        jpeg = b'\xff\xd8\xff\xe0\x00\x10JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00\xff\xd9'
        async def answer(method, params):
            if method == 'session.resume':
                # What Hermes' resume does for the plugin's socket: it becomes a subscribed viewer of the chat.
                connection.subscriptions['same-chat'] = 'subscription'
                live.subscribers['subscription'] = connection.actor
                return {'result': {'session_id': 'same-chat', 'subscription_id': 'subscription'}}
            if method == 'image.attach_bytes':
                staged = Path(get_image_cache_dir()) / 'voice-frame-synthetic.jpg'
                staged.parent.mkdir(parents=True, exist_ok=True)
                staged.write_bytes(base64.b64decode(params['content_base64']))
                return {'result': {'attached': True, 'path': str(staged.resolve()), 'mime': 'image/jpeg'}}
            if method == 'prompt.submit':
                receipt = await connection.submit(SessionRef(str(tmp_path), params['session_id']),
                                                  {k: v for k, v in params.items() if k != 'profile'})
                return {'result': receipt}
            return {'result': {'detached': True}}
        monkeypatch.setattr(transport, 'no_redirect_connect', lambda url, **kwargs: _connected(FakeUpstream(answer)))
        created = client.post(f'{BASE}/calls', json={'profile': 'default', 'voice_provider': 'gemini', 'client_key': True,
                                                      'conversation_id': 'same-chat'})
        assert created.status_code == 200, created.text
        call_id = created.json()['call_id']
        request = {'request_id': 'handover-real', 'text': 'Synthetic real-path request.', 'callback': False}
        if with_frame: request['frame_jpeg'] = base64.b64encode(jpeg).decode()
        answered = client.post(f'{BASE}/calls/{call_id}/handover', json=request, headers=V3)
        assert answered.status_code == 200 and answered.json()['status'] == 'queued', answered.text
        admission = db_admission(db, answered.json()['admission_id'])
        assert ('attachments_v1' in admission['payload']) is with_frame
        assert admission['payload']['text'] == 'Synthetic real-path request.'
        with api.mobile.connect() as conn:
            job = dict(conn.execute('SELECT * FROM jobs WHERE request=?', ('handover-real',)).fetchone())
        assert job['admission'] == answered.json()['admission_id'], 'the real provenance check accepted the admitted row'
        assert json.loads(job['remote_authorization'])['principal'] == canonical_subject()
        claimed = claim_session_input(db, epoch=epoch, session_id='same-chat')
        settle_session_input(db, epoch=epoch, admission_id=claimed['admission_id'], generation=claimed['generation'], outcome='completed',
                             result={'result': {'completed': True, 'final_response': 'Synthetic committed result'}, 'usage': {}})
        api.watch('owner', 'basic', 'default')
        page = client.get(f'{BASE}/calls/{call_id}/updates', params={'wait_ms': 0}).json()
        done = [event for event in page['events'] if event['type'] == 'job'][-1]
        assert done['status'] == 'done' and done['result'] == 'Synthetic committed result' and done['callback_consent'] == 'declined'
    finally:
        db.close()


async def _connected(socket):
    return socket


def db_admission(db, admission_id):
    from hermes_state_runtime import get_session_admission
    return get_session_admission(db, admission_id=admission_id)


def test_only_string_formats_gemini_gives_meaning_reach_a_declaration():
    """Finding 4: date-time and enum stay; uri, email, date and any other format become words in the description."""
    example = fixture('declaration_string_formats.json')
    assert tools().portable_schema(example['source']) == example['portable']
    def formats(node):
        if isinstance(node, dict):
            if 'format' in node: yield node['format']
            for child in list(node.get('properties', {}).values()) + node.get('anyOf', []) + ([node['items']] if 'items' in node else []):
                yield from formats(child)
    assert set(formats(example['portable'])) <= {'date-time', 'enum'}


def test_handover_capability_is_a_flag_not_a_probe(harness, monkeypatch):
    """Finding 5: no gateway round trip at bootstrap or context time; a principal plus this dashboard's relay is enough."""
    api = harness[0]
    monkeypatch.setitem(sys.modules, 'hermes_cli.web_server', sys.modules.get('hermes_cli.web_server') or SimpleNamespace())
    transport = sys.modules['beacon_canonical_transport']
    assert not hasattr(transport, 'handover_available')
    assert api.call_capabilities('auth:v1:synthetic')['handover'] is True
    assert api.call_capabilities(None)['handover'] is False


def test_a_v3_decision_that_cannot_start_keeps_the_card_and_says_capacity(api):
    """Finding 7 (checked): approving while every execution slot is busy starts nothing and leaves the card needs_approval,
    so the answer is the request-level 429 capacity and the owner can approve again; approve_call refused is a bad request."""
    client, dispatcher, backend, _ = api
    backend.read_only = False
    card = invoke_v3(client, request='card', approval='ask').json()
    g = tools()
    for _ in range(4): assert g.SLOTS.acquire(blocking=False)
    try:
        decided = client.post('/mobile/calls/call/tools/requests/card/decision',
                              json={'proposal_id': card['proposal']['id'], 'decision': 'approve', 'wait_ms': 1000}, headers=V3)
    finally:
        for _ in range(4): g.SLOTS.release()
    assert decided.status_code == 429 and decided.json()['error_code'] == 'capacity'
    assert client.get('/mobile/calls/call/tools/requests/card', headers=V3).json()['status'] == 'needs_approval'
    import importlib.util
    spec = importlib.util.spec_from_file_location('calls_v3_gateway_api', Path(__file__).resolve().parents[1] / 'gateway_api.py')
    gateway_api = importlib.util.module_from_spec(spec); spec.loader.exec_module(gateway_api)
    assert gateway_api.error_code(HTTPException(409, 'This tool needs approval each time')) == 'invalid_request'


def test_waiting_invokes_hold_no_route_permit(api):
    """Finding 10: four invokes waiting for their results leave the four route permits free for the next request."""
    client, dispatcher, backend, _ = api
    backend.wait = threading.Event()
    answers = []
    workers = [threading.Thread(target=lambda i=i: answers.append(invoke_v3(client, request=f'wait-{i}', wait_ms=3000).json()))
               for i in range(4)]
    for worker in workers: worker.start()
    try:
        deadline = time.monotonic() + 5
        while sum(dispatcher.status(SCOPE, f'wait-{i}')['status'] == 'running' for i in range(4) if _has(dispatcher, f'wait-{i}')) < 4:
            assert time.monotonic() < deadline; time.sleep(0.02)
        assert client.get('/mobile/calls/call/tools').status_code == 200
    finally:
        backend.wait.set()
        for worker in workers: worker.join(10)
    assert sorted(answer['status'] for answer in answers) == ['completed'] * 4


def _has(dispatcher, request):
    try: dispatcher.status(SCOPE, request); return True
    except HTTPException: return False


def test_a_newer_poll_takes_over_and_a_disconnect_ends_a_poll(harness, monkeypatch):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    row = {'id': call_id, 'profile': 'default', 'closed': 0}
    async def authorised(request, call, writable=False): return 'owner', 'basic', row, {}
    async def not_updating(): return None
    monkeypatch.setattr(api, 'call', authorised)
    monkeypatch.setattr(api, 'refuse_while_updating', not_updating)
    class Poll:
        def __init__(self, disconnected=False): self.query_params = {'after': '0', 'wait_ms': '5000'}; self.gone = disconnected
        async def is_disconnected(self): return self.gone
    async def two_polls():
        first = asyncio.ensure_future(api.call_updates_feed(call_id, Poll()))
        await asyncio.sleep(0.3)
        second = asyncio.ensure_future(api.call_updates_feed(call_id, Poll()))
        started = time.perf_counter(); taken_over = await first; took = time.perf_counter() - started
        second.cancel()
        return taken_over, took
    taken_over, took = asyncio.run(two_polls())
    assert taken_over['events'] == [] and took < 1, 'the older poll answers at once when a newer one arrives'
    started = time.perf_counter()
    gone = asyncio.run(api.call_updates_feed(call_id, Poll(disconnected=True)))
    assert gone['events'] == [] and time.perf_counter() - started < 1


def test_another_sign_in_cannot_read_or_hand_over_on_a_call(handover):
    """Finding 10: /updates and /handover go through the call's own principal check."""
    api = handover.api
    other = api.mobile.reserve('owner', 'default', 'native-stored', None, 'auth:v1:another-sign-in')['call_id']
    assert handover.client.get(f'{BASE}/calls/{other}/updates', params={'wait_ms': 0}).json()['error_code'] == 'not_owned'
    refused = handover.client.post(f'{BASE}/calls/{other}/handover', json={'request_id': 'h', 'text': 'Synthetic.'}, headers=V3)
    assert refused.status_code == 403 and refused.json()['error_code'] == 'not_owned'


def test_declarations_are_built_once_per_tool_list(harness, monkeypatch):
    """Finding 9: the same stored list gives the same declarations without converting it again."""
    api = harness[0]
    module = tools()
    built = []
    real = module.tool_declarations
    monkeypatch.setattr(module, 'tool_declarations', lambda rows: built.append(len(rows)) or real(rows))
    rows = [row('read_file', True, 'hermes')]
    warm(api, 'default', rows)
    first = api.call_contract_fields('default', None)
    second = api.call_contract_fields('default', None)
    assert first['tool_declarations'] == second['tool_declarations'] and built == [1]
    warm(api, 'default', [row('read_file', True, 'hermes'), row('patch', False, 'hermes')])
    assert len(api.call_contract_fields('default', None)['tool_declarations']) == 2 and built == [1, 2]
