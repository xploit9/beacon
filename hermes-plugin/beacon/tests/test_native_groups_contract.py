"""Read the real Hermes source contract without importing or starting Hermes.

Set HERMES_AGENT_SOURCE to a source checkout when running native compatibility checks.
"""
import ast
import os
from pathlib import Path

import pytest


@pytest.fixture(scope='module')
def native_source():
    location = os.environ.get('HERMES_AGENT_SOURCE')
    if not location:
        pytest.skip('Set HERMES_AGENT_SOURCE for actual-native-contract coverage')
    root = Path(location)
    return {name: ast.parse((root / name).read_text(encoding='utf-8-sig')) for name in (
        'tui_gateway/contracts/common.py', 'tui_gateway/contracts/groups_bot_relay.py',
        'tui_gateway/methods_groups.py', 'tui_gateway/hosted_room_service.py',
        'gateway/hosted_rooms.py', 'gateway/session_group_controls.py')}


def literal(module, name):
    return ast.literal_eval(next(node.value for node in module.body if isinstance(node, ast.Assign)
                                 and any(isinstance(t, ast.Name) and t.id == name for t in node.targets)))


def group_contracts(source):
    common = source['tui_gateway/contracts/common.py']
    module = source['tui_gateway/contracts/groups_bot_relay.py']
    classes = {node.name: node for tree in (common, module) for node in tree.body if isinstance(node, ast.ClassDef)}
    def fields(name):
        if name not in classes:
            return set()
        node = classes[name]
        return {a.target.id for a in node.body if isinstance(a, ast.AnnAssign)} | set().union(
            *(fields(base.id) for base in node.bases if isinstance(base, ast.Name)))
    return {call.args[0].value: fields(next(k.value.id for k in call.keywords if k.arg == 'params'))
            for call in ast.walk(module) if isinstance(call, ast.Call) and isinstance(call.func, ast.Name)
            and call.func.id == 'method' and isinstance(call.args[0], ast.Constant)
            and call.args[0].value.startswith('groups.')}


def validate_group_call(method, fields, methods, contracts):
    assert method in methods, f'Unadvertised native method: {method}'
    assert set(fields) <= contracts[method], f'Unsupported {method} fields: {set(fields) - contracts[method]}'


def test_adapter_group_calls_match_actual_tui_contract_and_reject_prior_inventions(native_source):
    methods = literal(native_source['tui_gateway/methods_groups.py'], '_METHODS')
    contracts = group_contracts(native_source)
    adapter = ast.parse((Path(__file__).parents[1] / 'bot_mode_api.py').read_text(encoding='utf-8'))
    seen = set()
    for call in ast.walk(adapter):
        if not isinstance(call, ast.Call) or not isinstance(call.func, ast.Name) or call.func.id != 'rpc' or len(call.args) < 4:
            continue
        method = call.args[3]
        if isinstance(method, ast.Constant) and isinstance(method.value, str) and method.value.startswith('groups.'):
            assert all(k.arg is not None for k in call.keywords), 'Group fields must be explicit for contract coverage'
            validate_group_call(method.value, {k.arg for k in call.keywords} | {'profile'}, methods, contracts)
            seen.add(method.value)
    assert {'groups.capabilities', 'groups.create', 'groups.state', 'groups.log', 'groups.send', 'groups.rename', 'groups.disband', 'groups.approve'} <= seen
    assert 'groups.retry' not in seen  # TUI pending rows cannot distinguish unknown work from deferred work.
    for method, fields in (('groups.delete', {'room_id'}), ('groups.members.set', {'room_id', 'members'}),
                           ('groups.disband', {'room_id', 'event_id'}), ('groups.disband', {'room_id', 'expected_name'}),
                           ('groups.retry', {'room_id', 'task_id', 'member_id', 'execution_generation'})):
        with pytest.raises(AssertionError):
            validate_group_call(method, fields, methods, contracts)


def test_real_disband_contract_and_transport_are_not_canonical_retry(native_source):
    contracts = group_contracts(native_source)
    assert contracts['groups.disband'] == {'profile', 'room_id', 'cancel_id'}
    assert contracts['groups.retry'] == {'profile', 'room_id', 'task_id'}
    canonical = literal(native_source['gateway/session_group_controls.py'], '_FIELDS')
    assert canonical['groups.retry'] == {'room_id', 'member_id', 'task_id', 'execution_generation'}
    assert 'groups.discard' in canonical and 'groups.discard' not in contracts
    handler = native_source['tui_gateway/methods_groups.py']
    disband = next(node for node in handler.body if isinstance(node, ast.FunctionDef) and any(
        isinstance(d, ast.Call) and any(isinstance(a, ast.Constant) and a.value == 'groups.disband' for a in d.args)
        for d in node.decorator_list))
    calls = [node for node in ast.walk(disband) if isinstance(node, ast.Call)]
    assert any(isinstance(c.func, ast.Attribute) and c.func.attr == 'stop_room'
               and any(k.arg == 'require_acknowledged' and isinstance(k.value, ast.Constant) and k.value.value is True for k in c.keywords) for c in calls)
    assert any(isinstance(c.func, ast.Attribute) and c.func.attr == 'revoke_room_routes' for c in calls)
    storage = native_source['gateway/hosted_rooms.py']
    assert any(isinstance(n, ast.Constant) and n.value == 'system:room-disbanded' for n in ast.walk(storage))
    transport = ast.parse((Path(__file__).parents[1] / 'native.py').read_text(encoding='utf-8'))
    rpc = next(n for n in transport.body if isinstance(n, ast.FunctionDef) and n.name == 'rpc')
    assert any(isinstance(n, ast.Call) and isinstance(n.func, ast.Attribute) and n.func.attr == 'dispatch'
               and isinstance(n.func.value, ast.Name) and n.func.value.id == 'server' for n in ast.walk(rpc))
