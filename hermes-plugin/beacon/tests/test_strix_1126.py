"""Strix scan of 1.1.26 (d01ef52): one MCP tool with fields of an unexpected type leaves only itself out of the call tool list."""
import sys
from types import SimpleNamespace

import pytest
from test_strix_1125 import g, mcp_environment


def odd_entry(name, schema, toolset='mcp-mail', raw=None, timeout=10):
    """A registry entry as Hermes builds it: the plugin reads server_name, tool_name and tool_timeout from the handler's closure."""
    def handler(): return name
    server_name, tool_name, tool_timeout = 'mail', (name if raw is None else raw), timeout

    def registered(*args): return handler(), server_name, tool_name, tool_timeout
    return SimpleNamespace(name='mcp__mail__' + str(name), toolset=toolset, handler=registered, schema=schema)


@pytest.mark.parametrize('entry', [
    odd_entry('list_schema', []),
    odd_entry('text_schema', 'not a schema'),
    odd_entry('null_schema', None),
    odd_entry('list_parameters', {'description': 'Odd', 'parameters': []}),
    odd_entry('text_parameters', {'description': 'Odd', 'parameters': 'x'}),
    odd_entry('null_parameters', {'description': 'Odd', 'parameters': None}),
    odd_entry('number_description', {'description': 5, 'parameters': {'type': 'object', 'properties': {}}}),
    odd_entry('null_toolset', {'description': 'Odd', 'parameters': {'type': 'object'}}, toolset=None),
    odd_entry('number_name', {'description': 'Odd', 'parameters': {'type': 'object'}}, raw=5),
], ids=lambda entry: entry.name)
def test_one_malformed_tool_leaves_the_rest_of_the_call_tools_alone(monkeypatch, entry):
    mcp_environment(monkeypatch, 'mail', 'Reads mail')
    registry = sys.modules['tools.registry'].registry
    good = registry.get_all_entries()[0]
    registry.get_all_entries = lambda: [entry, good]
    rows = g.NativeMcp().catalog()
    assert 'read_mail' in {row['_raw'] for row in rows}
    assert all(isinstance(row.get('parameters'), dict) for row in rows if row.get('_server'))


def test_a_timeout_that_is_not_a_number_falls_back_to_the_default(monkeypatch):
    mcp_environment(monkeypatch, 'mail', 'Reads mail')
    registry = sys.modules['tools.registry'].registry
    good = registry.get_all_entries()[0]
    slow = odd_entry('odd_timeout', {'description': 'Odd', 'parameters': {'type': 'object', 'properties': {}}}, timeout='soon')
    registry.get_all_entries = lambda: [slow, good]
    rows = {row['_raw']: row for row in g.NativeMcp().catalog() if row.get('_server')}
    assert 'read_mail' in rows
    if 'odd_timeout' in rows: assert rows['odd_timeout']['_timeout'] == 60.0


# ---- one server's huge name list does not hide the other sources ----------------------------------------------------------

def test_a_server_with_a_huge_name_list_does_not_hide_the_other_sources():
    flood = [{'name': f'w{i}_{verb}', '_raw': f'w{i}_{verb}', '_server': 'flood'} for i in range(600) for verb in ('list', 'read')]
    others = [{'name': 'notes_search', '_raw': 'notes_search', '_server': 'notes'},
              {'name': 'notes_read', '_raw': 'notes_read', '_server': 'notes'},
              {'name': 'weather_now', '_raw': 'weather_now', '_server': 'weather'},
              {'name': 'read_file', '_raw': 'read_file', '_toolset': 'file'}]
    for budget in (g.MAX_CATALOGUE_BYTES, g.MAX_SOURCES_BYTES):
        text = g.fitted_sources(flood + others, budget)
        assert len(text.encode('utf-8')) <= budget
        lines = text.split('\n')
        for label in ('Hermes', 'flood', 'notes', 'weather'):
            assert any(line.startswith(label + ':') for line in lines), (budget, label, text[:300])


# ---- an email rule never silences a calendar, contacts or files read on a mail-named server ---------------------------------

from test_mobile import harness, load_private  # noqa: E402,F401  (fixtures)


@pytest.mark.parametrize('tool, mail', [
    ('mcp__outlook__list_events', False),       # a calendar tool on a mail provider's server is the calendar
    ('mcp__outlook__get_contacts', False),
    ('mcp__workspace__gmail_calendar_list', False),
    ('mcp__work__calendar_list', False),
    ('mcp__outlook__search_mail', True),        # genuine mail tools keep the mailbox aliases
    ('mcp__outlook__list_messages', True),      # a mail-only server's message tool is mail
    ('mcp__gmail__search_threads', True),
    ('mcp__workspace__search_gmail_messages', True),
])
def test_only_mail_tools_carry_the_mailbox_labels(harness, tool, mail):
    observers = load_private('observers')
    proactive = sys.modules['beacon_proactive']
    labels = set(observers._tool_sources(tool))
    assert (proactive.source_name('email') in labels) is mail, (tool, labels)


# ---- a push target id that is not a plain identity is refused, not a server error ---------------------------------------------

@pytest.mark.parametrize('target', [['a'], {'id': 'a'}, 5, '', 'not a valid id!'])
def test_removing_a_push_target_with_a_malformed_id_is_a_validation_error(harness, target):
    _, client, _, _, _ = harness
    response = client.request('DELETE', '/api/plugins/beacon/mobile/notifications/targets', json={'id': target})
    assert response.status_code == 400, response.text


def test_removing_a_push_target_by_its_id_still_works(harness):
    _, client, _, _, _ = harness
    assert client.request('DELETE', '/api/plugins/beacon/mobile/notifications/targets', json={'id': 'device1'}).json() == {'revoked': True}
