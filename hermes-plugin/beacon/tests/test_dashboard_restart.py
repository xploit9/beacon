"""The dashboard restart helper, against a fake machine: processes, ports, a supervisor and a clock that only moves when it sleeps."""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec_ = importlib.util.spec_from_file_location('beacon_dashboard_restart_under_test', ROOT / 'dashboard_restart.py')
dr = importlib.util.module_from_spec(spec_)
sys.modules[spec_.name] = dr
spec_.loader.exec_module(dr)

HOME = '/data/hermes'
OTHER_HOME = '/data/hermes-demo'
PORT = 9119
COMMAND = ['C:/hermes/venv/python.exe', '-m', 'hermes_cli.main', '--profile', 'default', 'dashboard', '--no-open', '--port', str(PORT)]


def bootstrap(command):
    """How Hermes starts the process that serves the dashboard (seen on a real machine): an inline bootstrap that carries the arguments in its source."""
    argv = ['C:\\hermes\\hermes_cli\\main.py', *command[3:]]
    return ['C:/hermes/uv/python.exe', '-I', '-c',
            f"import sys, runpy; sys.path.insert(0, 'C:\\\\hermes'); sys.argv = {argv!r}; runpy.run_module('hermes_cli.main', run_name='__main__', alter_sys=True)"]


class FakeHost:
    """Process rows, listening ports and timed events. `sleep` is the only thing that moves the clock."""

    def __init__(self, tmp_path):
        self.clock = 0.0
        self.rows = {}
        self.listening = {}          # port -> pid
        self.events = []             # ('stop', pid) / ('spawn', command, cwd)
        self.timed = []              # (time, callable)
        self.next_pid = 5000
        self.is_busy = lambda: False
        self.answers = lambda: True  # does Beacon's manifest answer, for a dashboard that listens
        self.supervisor_after = None  # seconds after the old listener is gone; None: nothing supervises
        self.supervisor_boot = 3      # seconds from the supervisor's launch to its listener
        self.spawn_boot = 2
        self.spawn_dies = 0           # the next N spawns exit before they listen
        self.immortal = set()
        self.status = tmp_path / 'dashboard-restart.json'

    # -- building the scene
    def add(self, name, command, home=HOME, ppid=0, cwd='C:/hermes', listens=None, pid=None):
        pid = pid or self.next_pid
        self.next_pid = max(self.next_pid, pid) + 1
        self.rows[pid] = {'pid': pid, 'ppid': ppid, 'name': name, 'command': list(command), 'home': home, 'cwd': cwd, 'created': 1000.0 + pid}
        if listens:
            self.listening[listens] = pid
        return pid

    def chain(self, port=PORT, home=HOME, command=None):
        """The Windows launch chain: a launcher, a uv python, the python that listens."""
        command = command or COMMAND
        launcher = self.add('python.exe', command, home)
        middle = self.add('python.exe', command, home, ppid=launcher)
        return launcher, middle, self.add('python.exe', bootstrap(command), home, ppid=middle, listens=port)

    def after(self, seconds, action):
        self.timed.append((self.clock + seconds, action))

    # -- the Host contract
    def now(self):
        return self.clock

    def sleep(self, seconds):
        self.clock += seconds
        due = [t for t in self.timed if t[0] <= self.clock]
        self.timed = [t for t in self.timed if t[0] > self.clock]
        for _, action in sorted(due, key=lambda t: t[0]):
            action()

    def info(self, pid):
        row = self.rows.get(pid)
        return dict(row) if row else None

    def processes(self):
        return [dict(row) for row in self.rows.values()]

    def children(self, pid):
        return [row['pid'] for row in self.rows.values() if row['ppid'] == pid]

    def listeners(self, port):
        pid = self.listening.get(port)
        return [pid] if pid in self.rows else []

    def alive(self, pid, created=None):
        return pid in self.rows and (created is None or abs(self.rows[pid]['created'] - created) < 1)

    def stop(self, pid, created=None):
        row = self.rows.get(pid)
        if row is None or created is not None and abs(row['created'] - created) >= 1:
            return True
        self.events.append(('stop', pid))
        if pid in self.immortal:
            return False
        listeners = set(self.listening.values())
        del self.rows[pid]
        for port, listener in list(self.listening.items()):
            if listener == pid:
                del self.listening[port]
        if pid in listeners and self.supervisor_after is not None:
            self.supervisor_after, delay = None, self.supervisor_after
            self.after(delay, self._supervise)
        return True

    def _supervise(self):
        launcher = self.add('python.exe', COMMAND, HOME)
        self.after(self.supervisor_boot, lambda: self._listen(launcher))

    def _listen(self, launcher):
        if launcher in self.rows:
            self.listening[PORT] = self.add('python.exe', bootstrap(COMMAND), HOME, ppid=launcher)

    def spawn(self, command, cwd, home):
        self.events.append(('spawn', list(command), cwd))
        launcher = self.add('python.exe', command, home, cwd=cwd)
        if self.spawn_dies:
            self.spawn_dies -= 1
            self.after(1, lambda: self.rows.pop(launcher, None))
        else:
            self.after(self.spawn_boot, lambda: self._listen(launcher))
        return launcher

    def probe(self, port, profile):
        return bool(self.listeners(port)) and self.answers()

    def busy(self, port):
        return self.is_busy()


@pytest.fixture
def scene(tmp_path):
    host = FakeHost(tmp_path)
    launcher, middle, listener = host.chain()
    # The things around the dashboard that must never be touched: its supervisor, other homes, other ports, other tools.
    host.add('wscript.exe', ['wscript', 'hermes-dashboard.vbs'], ppid=0, pid=900)
    host.rows[launcher]['ppid'] = host.add('powershell.exe', ['powershell', '-File', 'supervise.ps1', 'hermes', 'dashboard'], pid=901)
    demo = host.add('python.exe', [*COMMAND[:-1], '9139'], OTHER_HOME, listens=9139)
    same_home_other_port = host.add('python.exe', [*COMMAND[:-1], '9200'], HOME, listens=9200)
    unrelated = host.add('python.exe', ['python', 'worker.py'], HOME)
    spec = {'id': 'a' * 32, 'pid': listener, 'created': host.rows[listener]['created'], 'home': HOME, 'port': PORT,
            'profile': 'default', 'status': str(host.status)}
    return host, spec, {'chain': {launcher, middle, listener}, 'bystanders': {900, 901, demo, same_home_other_port, unrelated}}


def stopped(host):
    return [e[1] for e in host.events if e[0] == 'stop']


def spawned(host):
    return [e for e in host.events if e[0] == 'spawn']


def finish(host, spec):
    result = dr.run(spec, host)
    assert dr.read_status(spec['status'])['state'] == result['state']
    return result


def test_supervised_restart_waits_for_the_supervisor_and_launches_nothing(scene):
    host, spec, who = scene
    host.supervisor_after = 4
    result = finish(host, spec)
    assert result['state'] == 'completed' and result['mode'] == 'supervised' and result['error'] == ''
    assert set(stopped(host)) == who['chain'] and not spawned(host)
    assert result['pid'] == host.listening[PORT] and result['pid'] not in who['chain']


def test_a_supervisor_that_is_still_loading_is_waited_for_not_raced(scene):
    host, spec, who = scene
    host.supervisor_after, host.supervisor_boot = 5, 40  # its listener comes after the first 20 s have passed
    result = finish(host, spec)
    assert (result['state'], result['mode']) == ('completed', 'supervised')
    assert not spawned(host)


def test_unsupervised_dashboard_is_relaunched_with_its_own_command_after_the_supervisor_wait(scene):
    host, spec, who = scene
    result = finish(host, spec)
    assert (result['state'], result['mode']) == ('completed', 'relaunched')
    [(_, command, cwd)] = spawned(host)
    assert command == COMMAND and cwd == 'C:/hermes'
    assert host.clock >= dr.SUPERVISOR_WAIT
    assert set(stopped(host)) == who['chain']


def test_a_launch_that_does_not_verify_is_retried_then_answers(scene):
    host, spec, _ = scene
    first = {}
    host.answers = lambda: len(spawned(host)) >= 2  # Beacon only answers for the second launch
    result = finish(host, spec)
    assert (result['state'], result['mode'], result['attempts']) == ('completed', 'relaunched', 2)
    assert len(spawned(host)) == 2
    assert host.rows[result['pid']]['pid'] == host.listening[PORT]


def test_failed_verification_every_time_is_recorded_and_a_dashboard_is_still_running(scene):
    host, spec, _ = scene
    host.answers = lambda: False
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'not_back')
    assert len(spawned(host)) == dr.ATTEMPTS
    assert host.listeners(PORT), 'the dashboard must never be left down'
    assert host.listeners(PORT)[0] == result['pid']


def test_a_launch_that_dies_is_retried_at_once(scene):
    host, spec, _ = scene
    host.spawn_dies = 1
    result = finish(host, spec)
    assert (result['state'], result['attempts']) == ('completed', 2)
    assert host.clock < dr.SUPERVISOR_WAIT + dr.VERIFY_WAIT, 'a dead launch must not be waited on for the whole verification'


def test_a_launch_that_cannot_start_is_retried_and_then_recorded(scene, monkeypatch):
    host, spec, _ = scene
    monkeypatch.setattr(host, 'spawn', lambda command, cwd, home: (_ for _ in ()).throw(OSError('no such file')))
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'not_back')


def test_only_the_dashboard_of_this_home_and_port_is_stopped(scene):
    host, spec, who = scene
    finish(host, spec)
    assert not set(stopped(host)) & who['bystanders']
    # The other home's dashboard and the same home's other port still listen.
    assert host.listening[9139] and host.listening[9200]


def test_a_pid_that_is_no_longer_the_dashboard_stops_nothing(scene):
    host, spec, _ = scene
    spec['created'] += 50  # the pid was reused by another process
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'foreign')
    assert not host.events


def test_a_dashboard_that_does_not_hold_the_port_stops_nothing(scene):
    host, spec, _ = scene
    spec['port'] = 9200  # this dashboard's command line says 9119; the port named is someone else's
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'foreign')
    assert not host.events


def test_a_launcher_of_another_home_or_with_an_unreadable_home_is_left_alone(scene):
    host, spec, who = scene
    launcher = min(who['chain'])
    host.rows[launcher]['home'] = OTHER_HOME
    middle = sorted(who['chain'])[1]
    host.rows[middle]['home'] = ''
    result = finish(host, spec)
    assert result['state'] == 'completed'
    assert launcher not in stopped(host) and middle not in stopped(host)
    # What it cannot prove is ours is not stopped, so the relaunch uses the command of the part that is: the serving process's own.
    [(_, command, _)] = spawned(host)
    assert command == bootstrap(COMMAND) and dr.is_dashboard_command(command) and dr.command_port(command) == PORT


def test_a_profile_folder_counts_as_its_home():
    assert dr.same_home('/data/hermes/profiles/work', '/data/hermes')
    assert not dr.same_home('/data/hermes/profiles/work', '/data/hermes-demo')
    assert not dr.same_home('', '/data/hermes') and not dr.same_home('/data/hermes', '')


def test_a_dashboard_that_will_not_stop_is_reported_and_nothing_is_launched(scene):
    host, spec, who = scene
    host.immortal = {spec['pid']}
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'stop_failed')
    assert not spawned(host) and host.listeners(PORT) == [spec['pid']]


def test_a_hermes_that_stays_busy_is_restarted_anyway(scene):
    # Owner, 3 Nov: the restart the owner asked for happens; a Hermes that stays busy (or a stuck agent count) never holds it back.
    host, spec, _ = scene
    host.is_busy = lambda: True
    result = finish(host, spec)
    assert result['state'] == 'completed'
    assert host.events[0][0] == 'stop'
    # It never asked whether Hermes was busy: nothing waits on Hermes' work.
    assert not hasattr(dr, 'BUSY_WAIT') and not hasattr(dr, 'BUSY_GRACE')


def test_the_helper_never_raises(scene, monkeypatch):
    host, spec, _ = scene
    monkeypatch.setattr(host, 'info', lambda pid: 1 / 0)
    result = finish(host, spec)
    assert (result['state'], result['error']) == ('failed', 'failed')


def test_launch_comes_from_the_outermost_launcher(scene):
    host, spec, who = scene
    chain = dr.find_chain(host, spec)
    assert {row['pid'] for row in chain} == who['chain']
    command, cwd = dr.launch_of(chain)
    assert command == COMMAND and cwd == 'C:/hermes'


def test_the_process_that_serves_the_dashboard_is_recognised_by_the_arguments_in_its_bootstrap():
    served = bootstrap(COMMAND)
    assert '-c' in served and 'dashboard' not in served, 'the serving process names the dashboard only inside its source'
    assert dr.effective_argv(served)[1:] == COMMAND[3:]
    assert dr.is_dashboard_command(served) and dr.command_port(served) == PORT
    assert dr.command_port(bootstrap([*COMMAND[:-1], '9139'])) == 9139
    # Other inline code, or a bootstrap for another command, is not a dashboard.
    assert not dr.is_dashboard_command(['python', '-c', "print('dashboard')"])
    assert not dr.is_dashboard_command(['python', '-I', '-c', "import sys; sys.argv = ['main.py', 'gateway', 'run']"])
    assert not dr.is_dashboard_command(['python', '-c'])
    assert dr.effective_argv(['python', '-c', "sys.argv = [1, 2]"]) == ['python', '-c', "sys.argv = [1, 2]"]


def test_command_port_and_dashboard_words():
    assert dr.command_port(['hermes', 'dashboard']) == 9119
    assert dr.command_port(['hermes', 'dashboard', '--port', '9139']) == 9139
    assert dr.command_port(['hermes', 'dashboard', '--port=9140']) == 9140
    assert dr.is_dashboard_command(['hermes', 'dashboard']) and dr.is_dashboard_command(['hermes', 'serve'])
    assert not dr.is_dashboard_command(['powershell', '-Command', 'hermes dashboard --port 9119'])


# ---- the status file and the hand-off ----------------------------------------------------------------------------

def test_status_is_merged_and_a_reader_sees_whole_documents(tmp_path):
    path = tmp_path / 'plugin-data' / 'beacon' / dr.STATUS_NAME
    dr.write_status(path, id='x', state='running', step='waiting')
    dr.write_status(path, step='stopping')
    status = dr.read_status(path)
    assert (status['id'], status['state'], status['step']) == ('x', 'running', 'stopping')
    assert not list(path.parent.glob('*.tmp'))
    path.write_text('{broken', encoding='utf-8')
    assert dr.read_status(path) == {}


def test_public_status_names_only_what_is_known(scene):
    host, spec, _ = scene
    status = {'id': 'abc', 'state': 'running', 'step': 'starting', 'updated': __import__('time').time(), 'helper': 0}
    assert dr.public_status(status, 'abc')['state'] == 'running'
    assert dr.public_status(status, 'other') == {'id': 'other', 'state': 'unknown'}
    assert dr.public_status({}, 'abc')['state'] == 'unknown'
    # A helper that died mid-restart, or one nobody has heard from for ten minutes, is a failure, not a restart in flight.
    assert dr.public_status({**status, 'helper': 4242}, 'abc', host)['state'] == 'failed'
    assert dr.public_status({**status, 'helper': 4242}, 'abc', host)['reason'] == 'lost'
    assert dr.public_status({**status, 'updated': 1}, 'abc')['state'] == 'failed'
    done = {**status, 'state': 'completed', 'mode': 'supervised', 'pid': 77}
    assert dr.public_status(done, 'abc')['pid'] == 77


def test_begin_records_the_restart_but_stops_nothing(scene, tmp_path):
    host, spec, _ = scene
    host.add('python.exe', COMMAND, pid=os.getpid(), listens=None)
    handed = []
    payload, start = dr.begin(tmp_path, PORT, 'default', host=host, helper=handed.append)
    assert payload['restart_started'] is True and len(payload['restart_id']) == 32
    assert not handed and not host.events, 'the answer goes out before anything is handed off'
    assert dr.read_status(dr.status_path(tmp_path))['state'] == 'running'
    start()
    [given] = handed
    assert (given['pid'], given['port'], given['profile']) == (os.getpid(), PORT, 'default')
    assert given['created'] == host.rows[os.getpid()]['created']


def test_begin_starts_while_hermes_is_busy_and_refuses_only_while_a_restart_runs(scene, tmp_path):
    host, _, _ = scene
    host.add('python.exe', COMMAND, pid=os.getpid())
    host.is_busy = lambda: True
    payload, _ = dr.begin(tmp_path, PORT, 'default', host=host)
    assert payload['restart_started'] is True
    again, start = dr.begin(tmp_path, PORT, 'default', host=host)
    assert start is None and again == {'restart_started': False, 'restart_error_code': 'in_progress', 'restart_id': payload['restart_id']}
    dr.write_status(dr.status_path(tmp_path), state='failed')
    assert dr.begin(tmp_path, PORT, 'default', host=host)[0]['restart_started'] is True


def test_a_failed_hand_off_is_recorded(scene, tmp_path):
    host, _, _ = scene
    host.add('python.exe', COMMAND, pid=os.getpid())
    payload, start = dr.begin(tmp_path, PORT, 'default', host=host, helper=lambda spec: (_ for _ in ()).throw(OSError('no process')))
    start()
    status = dr.read_status(dr.status_path(tmp_path))
    assert (status['id'], status['state'], status['error']) == (payload['restart_id'], 'failed', 'failed')


def test_a_machine_that_cannot_list_processes_is_unsupported(tmp_path, monkeypatch):
    class Unavailable:
        def __init__(self): raise ImportError('psutil')
    monkeypatch.setattr(dr, 'Host', Unavailable)
    assert dr.begin(tmp_path, PORT, 'default')[0] == {'restart_started': False, 'restart_error_code': 'unsupported'}


def test_the_helper_runs_under_a_command_line_no_dashboard_scan_can_mistake_for_a_dashboard(tmp_path, monkeypatch):
    started = []
    installer = dr._installer()
    monkeypatch.setattr(installer, '_detached', lambda command, home, **kwargs: started.append((command, home, kwargs)))
    dr.spawn_helper({'status': str(tmp_path / dr.STATUS_NAME), 'home': str(tmp_path), 'id': 'x'})
    [(command, home, kwargs)] = started
    assert command[0] == sys.executable and not dr.is_dashboard_command(command)
    assert json.loads(Path(command[2]).read_text(encoding='utf-8'))['id'] == 'x'
    assert kwargs == {'log_name': 'beacon-dashboard-restart.log'}


# ---- the real process table, once -----------------------------------------------------------------------------------

def _free_port():
    import socket
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def test_the_real_process_table_finds_stops_and_isolates_a_listening_process(tmp_path):
    pytest.importorskip('psutil')
    port, other_port = _free_port(), _free_port()
    code = "import socket,sys,time\ns=socket.socket();s.bind(('127.0.0.1',int(sys.argv[3])));s.listen();print('up',flush=True);time.sleep(120)"
    def listener(own_port, home):
        return subprocess.Popen([sys.executable, '-c', code, 'dashboard', '--port', str(own_port)], stdout=subprocess.PIPE, text=True,
                                env={**os.environ, 'HERMES_HOME': str(home)})
    ours_, foreign = listener(port, tmp_path), listener(other_port, tmp_path / 'other-home')
    try:
        assert ours_.stdout.readline().strip() == 'up' and foreign.stdout.readline().strip() == 'up'
        host = dr.Host()
        # Under a virtual environment the process started is a launcher and the listener is its child: the chain covers both.
        [pid] = host.listeners(port)
        row = host.info(pid)
        spec = {'pid': pid, 'created': row['created'], 'home': str(tmp_path), 'port': port}
        chain = dr.find_chain(host, spec)
        members = {r['pid'] for r in chain}
        assert pid in members and members <= {pid, ours_.pid}
        assert foreign.pid not in members and host.listeners(other_port)[0] not in members
        assert not dr.find_chain(host, {**spec, 'port': other_port}), 'the port belongs to another process'
        assert not dr.find_chain(host, {**spec, 'created': row['created'] + 9}), 'a reused pid is not the dashboard'
        for member in chain:
            assert host.stop(member['pid'], member['created']) is True
        assert not host.alive(pid) and host.listeners(port) == []
        assert host.alive(foreign.pid) and host.listeners(other_port), 'a dashboard of another home keeps running'
    finally:
        for process in (ours_, foreign):
            process.kill()
            process.wait(10)


# ---- from the gateway, after a plugin update ---------------------------------------------------------------------------

def test_the_gateway_hands_a_dashboard_older_than_the_plugin_files_to_the_helper(scene):
    host, _, parts = scene
    handed = []
    listener = max(parts['chain'])
    written = host.rows[listener]['created'] + 60  # the files were written after this dashboard started
    started = dr.restart_stale(HOME, written, host, helper=handed.append, path=host.status)
    assert len(started) == 1 and len(handed) == 1
    spec = handed[0]
    assert (spec['pid'], spec['port'], spec['profile'], spec['home']) == (listener, PORT, 'default', HOME)
    assert spec['delay'] == dr.STALE_DELAY and spec['id'] == started[0]
    assert dr.read_status(host.status)['state'] == 'running'
    assert not stopped(host), 'the gateway only hands over; the helper stops, after its delay'


def test_a_dashboard_started_after_the_files_is_left_alone(scene):
    host, _, parts = scene
    handed = []
    written = min(row['created'] for row in host.rows.values()) - 60
    assert dr.restart_stale(HOME, written, host, helper=handed.append, path=host.status) == [] and handed == []


def test_no_gateway_led_restart_during_a_call_or_beside_one_in_flight(scene):
    host, _, parts = scene
    handed = []
    written = max(row['created'] for row in host.rows.values()) + 60
    assert dr.restart_stale(HOME, written, host, helper=handed.append, call_open=lambda: True, path=host.status) == []
    dr.write_status(host.status, id='b' * 32, state='running', step='stopping', helper=0, started=__import__('time').time())
    assert dr.restart_stale(HOME, written, host, helper=handed.append, path=host.status) == [] and handed == []


def test_another_homes_stale_dashboard_is_never_handed_over(scene):
    host, _, _ = scene
    handed = []
    written = max(row['created'] for row in host.rows.values()) + 60
    dr.restart_stale(OTHER_HOME + '-elsewhere', written, host, helper=handed.append, path=host.status)
    assert handed == []
    dr.restart_stale(HOME, written, host, helper=handed.append, path=host.status)
    assert [spec['port'] for spec in handed] == [PORT], "Hermes' own port is chosen over the same home's other dashboard"


def test_the_helper_waits_out_the_gateway_delay_before_it_stops_anything(scene):
    host, spec, parts = scene
    host.supervisor_after = 2
    spec = {**spec, 'delay': dr.STALE_DELAY}
    first_stop = []
    original = host.stop
    host.stop = lambda pid, created=None: (first_stop.append(host.clock), original(pid, created))[1]
    assert finish(host, spec)['state'] == 'completed'
    assert first_stop and first_stop[0] >= dr.STALE_DELAY + dr.FLUSH_GRACE
