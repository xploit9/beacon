"""The owner's routes that restart the Hermes dashboard and report each step of it."""
import sys
import pytest
from test_mobile import harness, reserve

BASE = '/api/plugins/beacon/admin/dashboard/restart'


@pytest.fixture
def restarter(harness, monkeypatch):
    """The dashboard-restart module as the routes load it, with its process table and hand-off replaced."""
    module = sys.modules['beacon_admin_api'].dashboard_restarter()
    seen = {'begin': [], 'handed': []}
    real_begin = module.begin

    class Machine:
        def __init__(self): self.helper_alive = True
        def info(self, pid): return {'pid': pid, 'created': 1.0, 'command': ['python', 'dashboard', '--port', '9119']}
        def alive(self, pid, created=None): return self.helper_alive
        def busy(self, port): return seen.get('busy', False)

    machine = Machine()
    monkeypatch.setattr(module, 'Host', lambda: machine)
    monkeypatch.setattr(module, 'spawn_helper', lambda spec: seen['handed'].append(spec))
    def begin(*args, **kwargs):
        seen['begin'].append(args)
        return real_begin(*args, **kwargs)
    monkeypatch.setattr(module, 'begin', begin)
    return module, seen, machine


def test_the_route_answers_with_a_restart_id_then_hands_off_to_the_helper(harness, restarter):
    api, client, _, home, _ = harness
    module, seen, _ = restarter
    response = client.post(BASE, json={})
    assert response.status_code == 200
    body = response.json()
    assert body['restart_started'] is True and len(body['restart_id']) == 32
    assert seen['begin'][0][0] == home and seen['begin'][0][2] == 'default'
    [spec] = seen['handed']
    assert spec['id'] == body['restart_id'] and spec['home'] == str(home)


def test_a_call_in_progress_refuses_the_restart_before_anything_is_recorded(harness, restarter):
    _, client, _, home, _ = harness
    module, seen, _ = restarter
    reserve(client)
    response = client.post(BASE, json={})
    assert response.status_code == 409 and 'call' in response.json()['detail']
    assert not seen['begin'] and not seen['handed'] and not module.status_path(home).exists()


def test_a_hermes_that_is_working_is_restarted_anyway_when_the_owner_asks(harness, restarter):
    _, client, _, _, _ = harness
    _, seen, _ = restarter
    seen['busy'] = True
    assert client.post(BASE, json={}).json()['restart_started'] is True


def test_a_hermes_that_is_updating_itself_is_not_restarted_under_its_own_update(harness, restarter, monkeypatch):
    _, client, _, _, _ = harness
    _, seen, _ = restarter
    monkeypatch.setattr(sys.modules['beacon_admin_api'], 'update_in_progress', lambda: True)
    assert client.post(BASE, json={}).json() == {'restart_started': False, 'restart_error_code': 'live_sessions'}
    assert not seen['begin'] and not seen['handed']


def test_a_second_tap_joins_the_restart_that_is_running(harness, restarter):
    _, client, _, _, _ = harness
    _, seen, _ = restarter
    first = client.post(BASE, json={}).json()
    second = client.post(BASE, json={}).json()
    assert second == {'restart_started': False, 'restart_error_code': 'in_progress', 'restart_id': first['restart_id']}
    assert len(seen['handed']) == 1


def test_only_the_owner_may_restart_and_read_the_steps(harness, restarter):
    _, client, _, _, _ = harness
    stranger = {'x-fixture-owner': 'someone-else'}
    assert client.post(BASE, json={}, headers=stranger).status_code == 403
    assert client.get(BASE + '/status?id=' + 'a' * 32, headers=stranger).status_code == 403


def test_the_status_route_reports_the_step_and_survives_a_restart(harness, restarter):
    _, client, _, home, _ = harness
    module, _, machine = restarter
    request_id = client.post(BASE, json={}).json()['restart_id']
    assert client.get(BASE + '/status?id=' + request_id).json() == {'id': request_id, 'state': 'running', 'step': 'waiting', 'mode': '', 'reason': '', 'pid': 0}
    # The helper records its steps in a file; the dashboard that starts afterwards reads the same file.
    module.write_status(module.status_path(home), id=request_id, state='completed', step='done', mode='supervised', pid=4321)
    assert client.get(BASE + '/status?id=' + request_id).json() == {'id': request_id, 'state': 'completed', 'step': 'done', 'mode': 'supervised', 'reason': '', 'pid': 4321}
    assert client.get(BASE + '/status?id=' + 'b' * 32).json() == {'id': 'b' * 32, 'state': 'unknown'}


def test_a_helper_that_vanished_reads_as_a_failure_not_a_restart_in_flight(harness, restarter):
    _, client, _, _, _ = harness
    _, _, machine = restarter
    request_id = client.post(BASE, json={}).json()['restart_id']
    sys.modules['beacon_dashboard_restart'].write_status(sys.modules['beacon_dashboard_restart'].status_path(harness[3]), helper=999)
    machine.helper_alive = False
    status = client.get(BASE + '/status?id=' + request_id).json()
    assert (status['state'], status['reason']) == ('failed', 'lost')


@pytest.mark.parametrize('bad', ['', 'x', 'g' * 32, 'a' * 31, 'a' * 33, '../' + 'a' * 29])
def test_a_restart_identity_is_exactly_what_the_route_issued(harness, restarter, bad):
    _, client, _, _, _ = harness
    assert client.get(BASE + '/status', params={'id': bad}).status_code in (400, 422)


def test_a_machine_without_a_process_table_says_unsupported(harness, restarter, monkeypatch):
    _, client, _, _, _ = harness
    module, seen, _ = restarter
    def unavailable(): raise ImportError('psutil')
    monkeypatch.setattr(module, 'Host', unavailable)
    assert client.post(BASE, json={}).json() == {'restart_started': False, 'restart_error_code': 'unsupported'}
    assert not seen['handed']
