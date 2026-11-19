"""LINK-121: a call's lost link names its cause in Hermes.

The phone records what lost the call's link to Hermes (the request's failure, as a CAUSE_CLASSES category) on the tool receipt of
every function the loss reached. The call timeline keeps it there, under the same closed set and check as a media diagnostic's
cause; every other transport field stays a media diagnostic's, and no other event may carry a cause. The plugin says so in the call
contract (call_capabilities.tool_receipt_cause), so an app never sends a cause to a plugin that would refuse the whole batch."""
import json

import pytest

from test_mobile import harness, reserve  # noqa: F401  (a fixture)

LOST = {'event_id': 'lost-1', 'turn_id': 'tools', 'type': 'tool_receipt', 'at_ms': 61000, 'request_id': 'handover-1',
        'tool_name': 'hermes_work', 'status': 'unavailable', 'error_code': 'network'}


def post(client, call_id, *events):
    return client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/events', json={'events': list(events)})


def timeline(api, call_id):
    with api.mobile.connect() as conn:
        return [json.loads(row[0]) for row in conn.execute('SELECT payload FROM timeline WHERE call_id=? ORDER BY rowid', (call_id,))]


@pytest.mark.parametrize('cause', ['io', 'socket_timeout', 'connect', 'dns', 'eof', 'ssl_handshake', 'other'])
def test_a_tool_receipt_keeps_what_lost_the_link(harness, cause):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    response = post(client, call_id, {**LOST, 'cause_class': cause})
    assert response.status_code == 200, response.text
    assert timeline(api, call_id) == [{**LOST, 'cause_class': cause}]


@pytest.mark.parametrize('cause', ['StreamResetException', 'IO', 'io ', '', 'ssl', 7, None])
def test_an_unknown_cause_on_a_tool_receipt_refuses_the_batch(harness, cause):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    words = {'event_id': 'words', 'turn_id': '1', 'type': 'transcript', 'role': 'user', 'text': 'Synthetic words.', 'final': True, 'at_ms': 1}
    response = post(client, call_id, words, {**LOST, 'cause_class': cause})
    assert response.status_code == 409 and response.json()['detail'] == 'Invalid diagnostic cause', response.text
    assert timeline(api, call_id) == [], 'a refused batch stores nothing'


@pytest.mark.parametrize('field,value', [('provider', 'gemini'), ('attempt', 0), ('close_code', 1000), ('failed_stage', 'grant_ok'), ('warm', True)])
def test_a_tool_receipt_still_takes_no_other_transport_field(harness, field, value):
    _, client, _, _, _ = harness
    response = post(client, reserve(client), {**LOST, 'cause_class': 'io', field: value})
    assert response.status_code == 409 and response.json()['detail'] == 'Unexpected transport diagnostic', response.text


@pytest.mark.parametrize('event', [
    {'event_id': 'words', 'turn_id': '1', 'type': 'transcript', 'role': 'user', 'text': 'Synthetic words.', 'final': True, 'at_ms': 1},
    {'event_id': 'state', 'turn_id': 'state', 'type': 'state', 'state': 'listening', 'at_ms': 1},
    {'event_id': 'visual', 'turn_id': 'visual', 'type': 'visual_state', 'source': 'camera', 'state': 'off', 'frames_sent': 0, 'at_ms': 1},
])
def test_no_other_event_takes_a_cause(harness, event):
    _, client, _, _, _ = harness
    response = post(client, reserve(client), {**event, 'cause_class': 'io'})
    assert response.status_code == 409 and response.json()['detail'] == 'Unexpected transport diagnostic', response.text


def test_a_media_diagnostic_keeps_its_cause_as_before(harness):
    api, client, _, _, _ = harness
    call_id = reserve(client)
    diagnostic = {'event_id': 'provider-error', 'turn_id': 'connection', 'type': 'media_diagnostic', 'at_ms': 5, 'stage': 'provider_error',
                  'provider': 'gemini', 'attempt': 1, 'error_code': 'network', 'cause_class': 'socket_timeout'}
    assert post(client, call_id, diagnostic).status_code == 200
    assert timeline(api, call_id) == [diagnostic]
    refused = post(client, call_id, {**diagnostic, 'event_id': 'unknown-cause', 'cause_class': 'SocketTimeoutException'})
    assert refused.status_code == 409 and refused.json()['detail'] == 'Invalid diagnostic cause'


def test_the_call_contract_says_a_tool_receipt_may_carry_its_cause(harness):
    api, client, _, _, _ = harness
    for principal in ('synthetic-principal', None):
        assert api.call_capabilities(principal)['tool_receipt_cause'] is True
    assert client.get('/api/plugins/beacon/mobile/bootstrap').json()['call_capabilities']['tool_receipt_cause'] is True
