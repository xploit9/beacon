"""Restart this Hermes home's dashboard after a plugin update, from a helper that outlives it.

Hermes mounts plugin routes once, when the dashboard starts, so an updated Beacon only loads after the dashboard restarts. The
dashboard cannot restart itself, so `begin` hands off to a detached helper (this file run as a script). The helper stops only the
dashboard of this home on this port, waits for a supervisor (scheduled task, systemd, launchd) to bring a new one up, otherwise
relaunches the old command line, verifies Beacon's manifest answers, and records every step in a status file the next dashboard reads.
It never leaves the dashboard down: a launch that does not verify is retried, then recorded as a failure.
"""
from __future__ import annotations

import ast
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parent
STATUS_NAME = 'dashboard-restart.json'
FLUSH_GRACE = 0.75       # the response that started the restart is on its way out
STOP_GRACE = 10          # polite stop before a forced one
SUPERVISOR_WAIT = 20     # a supervisor gets this long to bring a new listener up
VERIFY_WAIT = 60         # a new dashboard gets this long to answer with Beacon
ATTEMPTS = 3             # launches before the restart is recorded as failed
FRESH = 10 * 60          # a status not touched for this long is history, not a restart in flight
STALE_DELAY = 20         # a gateway-led restart lets the phone confirm the gateway restart before the dashboard goes
PROCESS_NAME = re.compile(r'(?:python|pythonw|hermes)(?:\d+(?:\.\d+)*)?(?:\.exe)?', re.I)


def _installer():
    """The installer's dashboard helpers (detached start, manifest probe, home roots); loaded by path like the dashboard loads its siblings."""
    module = sys.modules.get('beacon_install')
    if module is None:
        spec = importlib.util.spec_from_file_location('beacon_install', ROOT / 'install' / 'beacon_install.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules['beacon_install'] = module
        spec.loader.exec_module(module)
    return module


def same_home(left, right) -> bool:
    """Both name one Hermes home (a profile's folder counts as its home); an unknown home is never the same."""
    try:
        root = _installer()._home_root
        return bool(left and right) and os.path.normcase(str(root(left))) == os.path.normcase(str(root(right)))
    except (OSError, ValueError):
        return False


def default_home(env) -> str:
    """Where Hermes keeps its home for a process whose environment does not say, read from that process's own environment."""
    suffix = env.get('HERMES_DATA_DIR_SUFFIX', '')
    if sys.platform == 'win32':
        base = env.get('LOCALAPPDATA', '').strip() or str(Path(env.get('USERPROFILE') or Path.home()) / 'AppData' / 'Local')
        return str(Path(base) / ('hermes' + suffix))
    return str(Path(env.get('HOME') or Path.home()) / ('.hermes' + suffix))


def effective_argv(command):
    """The arguments a process really runs. Hermes starts the dashboard it serves from a bootstrap,
    `python -I -c "…sys.argv = ['main.py', 'dashboard', '--port', '9119']; runpy.run_module(…)"`, so for that form the list in the source is the argv."""
    if '-c' in command[:-1]:
        match = re.search(r'sys\.argv\s*=\s*(\[[^\]]*\])', command[command.index('-c') + 1])
        try:
            argv = ast.literal_eval(match.group(1)) if match else None
        except (ValueError, SyntaxError):
            argv = None
        if isinstance(argv, list) and all(isinstance(token, str) for token in argv):
            return argv
    return command


def command_port(command) -> int:
    """The port a dashboard command line binds: its `--port`, else Hermes' default."""
    command = effective_argv(command)
    for index, token in enumerate(command):
        if token == '--port' and index + 1 < len(command) and command[index + 1].isdigit():
            return int(command[index + 1])
        if token.startswith('--port=') and token[7:].isdigit():
            return int(token[7:])
    return 9119


def is_dashboard_command(command) -> bool:
    """A real `dashboard`/`serve` argument, never a substring: a shell or a supervisor script only mentions the words."""
    command = effective_argv(command)
    return 'dashboard' in command or 'serve' in command


def ours(row, spec) -> bool:
    """[row] is a dashboard process of this home on this port."""
    return (bool(row) and bool(PROCESS_NAME.fullmatch(row['name'])) and is_dashboard_command(row['command'])
            and command_port(row['command']) == spec['port'] and same_home(row['home'], spec['home']))


# ---- status file -------------------------------------------------------------------------------------------------

def status_path(home) -> Path:
    return Path(home) / 'plugin-data' / 'beacon' / STATUS_NAME


def read_status(path) -> dict:
    try:
        value = json.loads(Path(path).read_text(encoding='utf-8'))
        return value if isinstance(value, dict) else {}
    except (OSError, ValueError):
        return {}


def write_status(path, **fields) -> dict:
    """Merge [fields] into the status file, whole or not at all (a reader never sees half of it)."""
    path = Path(path)
    value = {**read_status(path), **fields, 'updated': time.time()}
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex[:8] + '.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    os.replace(temporary, path)
    return value


def in_flight(status, host=None, now=None) -> bool:
    """A restart that is running, was heard from lately, and whose helper is still there."""
    now = time.time() if now is None else now
    if status.get('state') != 'running' or not 0 <= now - float(status.get('updated') or 0) <= FRESH:
        return False
    helper = int(status.get('helper') or 0)
    return not (helper and host is not None and not host.alive(helper))


def public_status(status, request_id, host=None) -> dict:
    """What the phone is told about restart [request_id]; an id nothing was recorded for reads as unknown.

    The why is `reason`, not `error`: the phone's client treats any answer with an `error` field as a failed request.
    """
    if not status or status.get('id') != request_id:
        return {'id': request_id, 'state': 'unknown'}
    lost = status.get('state') == 'running' and not in_flight(status, host)
    return {'id': request_id, 'state': 'failed' if lost else status.get('state') or 'unknown', 'step': status.get('step') or '',
            'mode': status.get('mode') or '', 'reason': 'lost' if lost else status.get('error') or '', 'pid': int(status.get('pid') or 0)}


# ---- the real process table ----------------------------------------------------------------------------------------

class Host:
    """The machine as the helper sees it. Tests substitute a fake with these methods."""

    def __init__(self):
        import psutil
        self.ps = psutil

    def now(self):
        return time.monotonic()

    def sleep(self, seconds):
        time.sleep(seconds)

    def info(self, pid):
        """Who [pid] is, or None when it is gone or cannot be read."""
        try:
            process = self.ps.Process(pid)
            command = process.cmdline()
            try:
                env = process.environ()
                home = env.get('HERMES_HOME', '').strip() or default_home(env)
            except (self.ps.Error, OSError):
                home = ''  # unreadable: never treated as ours
            try:
                cwd = process.cwd()
            except (self.ps.Error, OSError):
                cwd = ''
            return {'pid': pid, 'ppid': process.ppid(), 'name': process.name(), 'command': command, 'home': home,
                    'cwd': cwd, 'created': process.create_time()}
        except (self.ps.Error, OSError):
            return None

    def processes(self):
        return [row for row in (self.info(pid) for pid in self.ps.pids()) if row]

    def children(self, pid):
        try:
            return [child.pid for child in self.ps.Process(pid).children()]
        except (self.ps.Error, OSError):
            return []

    def listeners(self, port):
        try:
            return sorted({c.pid for c in self.ps.net_connections(kind='inet') if c.status == 'LISTEN' and c.laddr and c.laddr.port == port and c.pid})
        except (self.ps.Error, OSError):
            return []

    def alive(self, pid, created=None):
        try:
            process = self.ps.Process(pid)
            return process.is_running() and process.status() != self.ps.STATUS_ZOMBIE and (created is None or abs(process.create_time() - created) < 1)
        except (self.ps.Error, OSError):
            return False

    def stop(self, pid, created=None):
        """Ask politely, then force; True once [pid] is gone."""
        try:
            process = self.ps.Process(pid)
            if created is not None and abs(process.create_time() - created) >= 1:
                return True  # that pid now belongs to someone else: the process we meant is gone
            process.terminate()
            try:
                process.wait(STOP_GRACE)
            except self.ps.TimeoutExpired:
                process.kill()
                process.wait(5)
            return True
        except self.ps.NoSuchProcess:
            return True
        except (self.ps.Error, OSError):
            return not self.alive(pid)

    def spawn(self, command, cwd, home):
        """Start [command] detached so it outlives this helper; returns its pid."""
        return _installer()._detached(command, Path(home), cwd=cwd or None, log_name='beacon-dashboard-restart.log').pid

    def probe(self, port, profile):
        """Beacon's manifest answers 200 on the loopback dashboard."""
        installer = _installer()
        return installer._route_status(f'http://127.0.0.1:{port}{installer.PROBE_ROUTE}?profile={profile}') == 200



# ---- the restart ---------------------------------------------------------------------------------------------------

def find_chain(host, spec):
    """The processes that are this dashboard: it and the same-home, same-port launchers around it. Empty when that cannot be proven."""
    me = host.info(spec['pid'])
    # Identity is the pid with its start time; the dashboard is the one process that listens on the port, anything else there is someone else's.
    if (not me or abs(me['created'] - spec['created']) >= 1 or not is_dashboard_command(me['command'])
            or me['pid'] not in host.listeners(spec['port'])):
        return []
    chain = {me['pid']: me}
    row = me
    while row['ppid']:
        parent = host.info(row['ppid'])
        if not ours(parent, spec) or parent['pid'] in chain:
            break
        chain[parent['pid']] = row = parent
    pending = [me['pid']]
    while pending:
        for child in host.children(pending.pop()):
            row = host.info(child)
            if ours(row, spec) and row['pid'] not in chain:
                chain[row['pid']] = row
                pending.append(row['pid'])
    return list(chain.values())


def launch_of(chain):
    """How the dashboard was started: the outermost launcher's command line and directory."""
    ids = {row['pid'] for row in chain}
    root = next((row for row in chain if row['ppid'] not in ids), chain[0])
    return list(root['command']), root['cwd']


def wait_for(host, test, seconds):
    """The first truthy answer of [test], asked once a second; None when [seconds] pass."""
    deadline = host.now() + seconds
    while True:
        value = test()
        if value:
            return value
        if host.now() >= deadline:
            return None
        host.sleep(1)


def stop_tree(host, pid):
    """Stop [pid] and what it started, children first."""
    pending, tree = [pid], []
    while pending:
        current = pending.pop()
        tree.append(current)
        pending.extend(host.children(current))
    for current in reversed(tree):
        host.stop(current)


def run(spec, host=None):
    """Restart the dashboard in [spec]; returns the final status and also records it. Never raises."""
    host = host or Host()
    path = spec['status']
    note = lambda **fields: write_status(path, id=spec['id'], **fields)
    try:
        return _run(spec, host, note)
    except Exception as error:  # the dashboard may still be up; say what stopped us
        return note(state='failed', error='failed', detail=type(error).__name__, finished=time.time())


def _run(spec, host, note):
    note(state='running', step='waiting', helper=os.getpid())
    host.sleep(FLUSH_GRACE + float(spec.get('delay', 0)))
    # Owner, 3 Nov: the owner asked for this restart, so it goes on at once, whatever Hermes is doing.
    chain = find_chain(host, spec)
    if not chain:
        return note(state='failed', error='foreign', finished=time.time())
    command, cwd = launch_of(chain)
    old = {row['pid'] for row in chain}
    note(step='stopping')
    for row in sorted(chain, key=lambda item: item['pid'] != spec['pid']):  # the dashboard first, its launchers after
        host.stop(row['pid'], row['created'])
    if old & set(host.listeners(spec['port'])):
        return note(state='failed', error='stop_failed', finished=time.time())
    note(step='starting')
    fresh = lambda: next((pid for pid in host.listeners(spec['port']) if pid not in old), 0)
    booting = lambda: any(ours(row, spec) and row['pid'] not in old for row in host.processes())
    new = wait_for(host, fresh, SUPERVISOR_WAIT)
    # A supervisor that has started a dashboard which is still loading is waited for, never raced with a second one.
    if not new and booting():
        new = wait_for(host, fresh, VERIFY_WAIT)
    mode = 'supervised' if new or booting() else 'relaunched'
    launched = 0
    for attempt in range(1, ATTEMPTS + 1):
        if mode == 'relaunched':
            try:
                launched = host.spawn(command, cwd, spec['home'])
            except OSError:
                launched = 0
        note(step='verifying', mode=mode, attempts=attempt)

        def verdict():
            if fresh():
                return 'back' if host.probe(spec['port'], spec['profile']) else None
            return None if mode == 'supervised' or launched and host.alive(launched) else 'gone'
        # A launch that died before it listened is retried at once instead of being waited on.
        result = wait_for(host, verdict, VERIFY_WAIT)
        if result == 'back':
            return note(state='completed', step='done', mode=mode, pid=fresh(), error='', finished=time.time())
        if mode == 'supervised' or attempt == ATTEMPTS:
            break
        for pid in (launched, fresh()):
            if pid:
                stop_tree(host, pid)
    return note(state='failed', error='not_back', mode=mode, pid=fresh(), finished=time.time())


# ---- from the dashboard --------------------------------------------------------------------------------------------

def begin(home, port, profile, host=None, helper=None):
    """Prepare a restart of the dashboard serving this request; returns (payload for the phone, start). Calling start() hands off to the helper.

    Nothing is stopped here: the phone gets its answer first, and [start] runs once that answer is on its way.
    """
    path = status_path(home)
    try:
        host = host or Host()
        me = host.info(os.getpid())
    except ImportError:
        return {'restart_started': False, 'restart_error_code': 'unsupported'}, None
    if not me:
        return {'restart_started': False, 'restart_error_code': 'unsupported'}, None
    current = read_status(path)
    if in_flight(current, host):
        return {'restart_started': False, 'restart_error_code': 'in_progress', 'restart_id': current.get('id', '')}, None
    port = int(port or command_port(me['command']))
    spec = {'id': uuid.uuid4().hex, 'pid': me['pid'], 'created': me['created'], 'home': str(home), 'port': port,
            'profile': profile, 'status': str(path)}
    write_status(path, id=spec['id'], state='running', step='waiting', mode='', error='', pid=0, helper=0, started=time.time(), finished=0)

    def start():
        try:
            (helper or spawn_helper)(spec)
        except OSError:
            write_status(path, id=spec['id'], state='failed', error='failed', finished=time.time())
    return {'restart_started': True, 'restart_id': spec['id']}, start


def command_profile(command) -> str:
    """The profile a dashboard command line serves: its `-p`/`--profile`, else the default profile."""
    command = effective_argv(command)
    for index, token in enumerate(command):
        if token in ('-p', '--profile') and index + 1 < len(command):
            return command[index + 1]
        if token.startswith('--profile='):
            return token[10:]
    return 'default'


def stale_dashboards(host, home, written):
    """This home's dashboards (each the listener on its own port) that started before the plugin files were [written]: they run old code."""
    found = []
    for row in host.processes():
        if not (PROCESS_NAME.fullmatch(row['name']) and is_dashboard_command(row['command']) and same_home(row['home'], home)):
            continue
        port = command_port(row['command'])
        if row['created'] < written and row['pid'] in host.listeners(port):
            found.append((row, port))
    return found


def restart_stale(home, written, host=None, helper=None, call_open=lambda: False, path=None):
    """From the gateway as it starts after a plugin update: a dashboard still running the old plugin is restarted by the same helper.

    An update restarts the gateway with the new plugin, but the dashboard keeps the plugin it imported until it restarts, and its old
    code cannot restart itself. The helper waits [STALE_DELAY] first, so the phone's own check of that gateway restart lands before the
    dashboard goes away. Never during a call or beside a restart already in flight. Returns the ids of the restarts started.
    """
    try:
        host = host or Host()
    except ImportError:
        return []
    path = path or status_path(home)
    if call_open() or in_flight(read_status(path), host):
        return []
    started = []
    # One dashboard per home serves the phone: Hermes' own port first, then the lowest.
    for row, port in sorted(stale_dashboards(host, home, written), key=lambda found: (found[1] != 9119, found[1]))[:1]:
        spec = {'id': uuid.uuid4().hex, 'pid': row['pid'], 'created': row['created'], 'home': str(home), 'port': port,
                'profile': command_profile(row['command']), 'status': str(path), 'delay': STALE_DELAY}
        write_status(path, id=spec['id'], state='running', step='waiting', mode='', error='', pid=0, helper=0, started=time.time(), finished=0)
        try:
            (helper or spawn_helper)(spec)
        except OSError:
            write_status(path, id=spec['id'], state='failed', error='failed', finished=time.time())
            continue
        started.append(spec['id'])
    return started


def spawn_helper(spec):
    """Run this file detached with [spec]; its command line names no dashboard, so no scan for dashboards can mistake it for one."""
    specification = Path(spec['status']).with_name('dashboard-restart-spec.json')
    specification.write_text(json.dumps(spec), encoding='utf-8')
    _installer()._detached([sys.executable, str(Path(__file__).resolve()), str(specification)], Path(spec['home']), log_name='beacon-dashboard-restart.log')


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    spec = json.loads(Path(argv[0]).read_text(encoding='utf-8'))
    return 0 if run(spec).get('state') == 'completed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
