"""The code-writing repair pipeline is gone: its routes answer 404, nothing records or queues an incident, and the kept recovery steps still answer."""
import sys
import threading
from pathlib import Path

import pytest

from test_mobile import ROOT, harness

BASE = '/api/plugins/beacon/mobile'
INCIDENT = {'incident_id': '11111111-2222-4333-8444-555555555555',
            'incident': {'component': 'network', 'operation': 'request', 'error_code': 'timeout'}}


def test_the_incident_and_healing_routes_are_gone(harness):
    _, client, _, _, _ = harness
    assert client.get(BASE + '/healing/capabilities').status_code == 404
    assert client.post(BASE + '/incidents', json=INCIDENT).status_code == 404
    assert client.get(BASE + '/incidents').status_code == 404
    # Gone for everyone, not hidden behind sign-in.
    assert client.get(BASE + '/healing/capabilities', headers={'x-fixture-owner': ''}).status_code == 404


def test_an_old_phone_uploading_an_incident_loses_nothing_else(harness):
    """An app from before this change asks for capabilities, then uploads; the 404 ends its drain and every other call still works."""
    _, client, _, _, _ = harness
    assert client.get(BASE + '/healing/capabilities').status_code == 404
    assert client.post(BASE + '/incidents', json=INCIDENT).status_code == 404
    version = client.get(BASE + '/plugin/version')
    assert version.status_code == 200 and set(version.json()) == {'running', 'installed', 'gateway'}
    assert client.get('/api/plugins/beacon/admin/dashboard/restart/status?id=' + '0' * 32).json()['state'] == 'unknown'


def test_no_incident_is_recorded_or_queued_for_repair(harness):
    api, client, _, home, _ = harness
    client.post(BASE + '/incidents', json=INCIDENT)
    removed = {'self_heal', 'healing_api', 'healing_runtime'}
    # Only this plugin's own folder counts: another test may have loaded an older Beacon installed elsewhere on this PC.
    assert not removed & set(api._MODULE_NAMES)
    assert not [name for name, module in sys.modules.items() if name.startswith('beacon_') and name[len('beacon_'):] in removed
                and Path(getattr(module, '__file__', '') or '').resolve().parent == ROOT]
    assert not hasattr(api, 'healing')
    assert not [thread.name for thread in threading.enumerate() if thread.name in {'beacon-healing', 'beacon-repair-operator', 'beacon-repair-restart'}]
    with api.mobile.connect() as db:
        tables = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    assert not {name for name in tables if name.startswith('healing_')}


@pytest.mark.parametrize('name', ['self_heal', 'healing_runtime', 'healing_api', 'repair_runner', 'repair_executor', 'repair_operator'])
def test_the_repair_modules_are_not_shipped(name):
    assert not (ROOT / (name + '.py')).exists()
    assert not (ROOT / 'repair-tools').exists()
