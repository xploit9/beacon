"""The agent phone's host checks (agent_phone_checks.py): every check before any download, and the two re-run at every start.

Hermes' ``/api/status`` is a local fake server; the developer adb server is a fake listener on a free port (never 5037, which a
developer PC runs); the container, KVM, cgroup and meminfo files are a fake filesystem under ``tmp_path``.
"""
import http.server
import importlib.util
import json
import socket
import subprocess
import sys
import threading
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]


def _load(name):
    key = 'beacon_' + name
    if key not in sys.modules:
        spec = importlib.util.spec_from_file_location(key, PLUGIN / f'{name}.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


checks = _load('agent_phone_checks')
inst = _load('agent_phone_install')
WIN = {'os': 'windows', 'arch': 'x64', 'abi': 'x86_64', 'cpu': 'x86_64', 'supported': True}
LINUX = {'os': 'linux', 'arch': 'x64', 'abi': 'x86_64', 'cpu': 'x86_64', 'supported': True}
MAC = {'os': 'macosx', 'arch': 'aarch64', 'abi': 'arm64-v8a', 'cpu': 'arm64', 'supported': True}
GB = 1 << 30


class Status:
    """A fake Hermes dashboard: ``GET /api/status`` answers ``body`` with ``code``; ``redirect`` sends a 302 instead."""

    def __init__(self, body=None, code=200, redirect=None):
        self.body, self.code, self.redirect, self.paths = body, code, redirect, []
        status = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                status.paths.append(self.path)
                if status.redirect:
                    self.send_response(302)
                    self.send_header('Location', status.redirect)
                    self.end_headers()
                    return
                data = status.body if isinstance(status.body, bytes) else json.dumps(status.body).encode()
                self.send_response(status.code)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            do_HEAD = do_GET

        self.httpd = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.url = f'http://127.0.0.1:{self.httpd.server_address[1]}'
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def dashboard():
    made = []

    def make(*args, **kwargs):
        made.append(Status(*args, **kwargs))
        return made[-1]
    yield make
    for s in made:
        s.close()


def closed_port():
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    port = s.getsockname()[1]
    s.close()
    return port


# -- Hermes sign-in (Root's decision 1) ------------------------------------------------------------------------------

def test_sign_in_off_fails_with_the_fix_line_and_on_passes(dashboard):
    off = dashboard({'version': 'x', 'auth_required': False})
    row = checks.check_sign_in(off.url)
    assert row['status'] == 'fail' and row['line'] == 'Turn on Hermes sign-in first.'
    assert row['fix'] == 'Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes.'
    assert row['how'].endswith('/web-dashboard#authentication-gated-mode')
    assert off.paths == ['/api/status']
    on = dashboard({'version': 'x', 'auth_required': True})
    assert checks.check_sign_in(on.url)['status'] == 'ready'
    assert checks.check_sign_in(on.url + '/')['status'] == 'ready'


@pytest.mark.parametrize('body,code', [({'auth_required': 'true'}, 200), ({'auth_required': 1}, 200), ({}, 200),
                                       (b'not json', 200), ({'auth_required': True}, 503), (b'[true]', 200)])
def test_sign_in_passes_only_on_a_json_true(dashboard, body, code):
    row = checks.check_sign_in(dashboard(body, code).url)
    assert row['status'] == 'fail' and row['line'] == "Couldn't check Hermes sign-in."


def test_sign_in_follows_no_redirect_and_no_proxy(dashboard, monkeypatch):
    on = dashboard({'auth_required': True})
    hop = dashboard(redirect=on.url + '/api/status')
    assert checks.check_sign_in(hop.url)['status'] == 'fail' and on.paths == []
    for name in ('HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy'):
        monkeypatch.setenv(name, f'http://127.0.0.1:{closed_port()}')
    monkeypatch.delenv('NO_PROXY', raising=False)
    monkeypatch.delenv('no_proxy', raising=False)
    assert checks.check_sign_in(on.url)['status'] == 'ready'


def test_sign_in_without_an_address_or_with_the_dashboard_down_fails():
    for address in (None, '', 'file:///etc/passwd', 'ftp://127.0.0.1/', f'http://127.0.0.1:{closed_port()}'):
        assert checks.check_sign_in(address)['status'] == 'fail'


def test_every_start_refuses_without_sign_in_with_the_agents_line(dashboard):
    off, on = dashboard({'auth_required': False}), dashboard({'auth_required': True})
    dev = closed_port()
    assert checks.start_blocker(checks.start_checks(off.url, developer_port=dev), 'Maya') == 'Turn on Hermes sign-in first.'
    down = checks.start_checks(f'http://127.0.0.1:{closed_port()}', developer_port=dev)
    assert checks.start_blocker(down, 'Maya') == "Maya's phone can't start while Hermes' dashboard is off."
    assert checks.start_blocker(checks.start_checks(on.url, developer_port=dev), 'Maya') is None


def test_start_gate_reads_the_dashboard_address_from_the_pointer(tmp_path, dashboard, monkeypatch):
    dev = closed_port()
    home = tmp_path / 'hermes-home'
    home.mkdir()
    assert checks.start_gate(home, name='Maya', developer_port=dev) == (
        False, "Maya's phone can't start while Hermes' dashboard is off.")  # no pointer yet
    on, off = dashboard({'auth_required': True}), dashboard({'auth_required': False})
    inst.write_pointer(home, tmp_path / 'phone', profile='default', dashboard=on.url)
    assert checks.start_gate(home, name='Maya', developer_port=dev) == (True, None)
    inst.write_pointer(home, tmp_path / 'phone', profile='default', dashboard=off.url)
    assert checks.start_gate(home, name='Maya', developer_port=dev) == (False, 'Turn on Hermes sign-in first.')
    inst.write_pointer(home, tmp_path / 'phone', profile='default', dashboard=None)
    assert checks.start_gate(home, developer_port=dev) == (False, "Beacon's phone can't start while Hermes' dashboard is off.")
    # without a home: the running process's own (HERMES_HOME here)
    monkeypatch.setenv('HERMES_HOME', str(home))
    inst.write_pointer(home, tmp_path / 'phone', profile='default', dashboard=on.url)
    assert checks.start_gate(developer_port=dev) == (True, None)
    # a developer adb server never blocks a start
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(1)
    try:
        assert checks.start_gate(home, developer_port=listener.getsockname()[1]) == (True, None)
    finally:
        listener.close()


START_ELSEWHERE = r'''
import importlib.util, json, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('beacon_agent_phone_checks', Path(sys.argv[1]) / 'agent_phone_checks.py')
checks = importlib.util.module_from_spec(spec); spec.loader.exec_module(checks)
result = checks.start_checks(sys.argv[2], developer_port=int(sys.argv[3]))
print(json.dumps({'blocker': checks.start_blocker(result, 'Maya'), 'hermes': [m for m in sys.modules if m.startswith(('hermes', 'gateway'))]}))
'''


def test_the_start_check_works_from_any_process_without_hermes(tmp_path, dashboard):
    """The gateway (an agent call) and the keeper start the phone too: the check needs nothing of the dashboard process."""
    script = tmp_path / 'start.py'
    script.write_text(START_ELSEWHERE)
    for body, want in (({'auth_required': False}, 'Turn on Hermes sign-in first.'), ({'auth_required': True}, None)):
        server = dashboard(body)
        done = subprocess.run([sys.executable, '-I', str(script), str(PLUGIN), server.url, str(closed_port())],
                              capture_output=True, text=True, timeout=60)
        assert done.returncode == 0, done.stderr
        out = json.loads(done.stdout.strip().splitlines()[-1])
        assert out == {'blocker': want, 'hermes': []}


# -- Android developer tools (5037) --------------------------------------------------------------------------------

def test_a_developer_adb_server_warns_with_continue_and_no_binary_runs(monkeypatch):
    def no_binaries(*args, **kwargs):
        raise AssertionError('the developer tools check never runs a program')
    monkeypatch.setattr(subprocess, 'run', no_binaries)
    monkeypatch.setattr(subprocess, 'Popen', no_binaries)
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(4)
    try:
        port = listener.getsockname()[1]
        row = checks.check_developer_tools(port=port)
        assert row['status'] == 'warn' and row['needs_continue']
        assert row['line'] == ("Close Android developer tools on this computer, or apps on {name}'s phone can control the "
                               "devices they connect to.")
        assert checks.words(row, 'Maya')['line'].startswith("Close Android developer tools on this computer, or apps on Maya's")
    finally:
        listener.close()
    assert checks.check_developer_tools(port=port) == checks.row('developer_tools', 'ready')


def test_the_developer_tools_warning_needs_continue_before_the_download(tmp_path, dashboard):
    listener = socket.socket()
    listener.bind(('127.0.0.1', 0))
    listener.listen(4)
    try:
        result = checks.run_checks(tmp_path / 'phone', dashboard({'auth_required': True}).url, host=WIN, memory=(32 * GB, 64 * GB),
                                   free_disk=100 * GB, virtualisation=lambda: 1, network_url=dashboard({}).url,
                                   console_ports=range(41000, 41004, 2), port_free=lambda p: True,
                                   developer_port=listener.getsockname()[1])
    finally:
        listener.close()
    assert result['ok'] and result['needs_continue'] and not inst.checks_passed(result)
    assert inst.checks_passed(checks.continue_warnings(result))
    with pytest.raises(inst.InstallError) as refused:
        inst.run_install(tmp_path / 'phone', checks=result, host=WIN, base_url=f'http://127.0.0.1:{closed_port()}/')
    assert refused.value.code == 'checks' and not (tmp_path / 'phone').exists()


# -- the computer --------------------------------------------------------------------------------------------------

def fake_root(tmp_path, files):
    root = tmp_path / 'fs'
    for rel, text in files.items():
        path = root / rel
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    root.mkdir(parents=True, exist_ok=True)
    return root


def test_a_container_without_kvm_cant_run_the_phone(tmp_path):
    for files in ({'.dockerenv': ''}, {'run/.containerenv': ''}, {'proc/1/cgroup': '0::/kubepods/besteffort/pod1\n'},
                  {'proc/self/mountinfo': '1 0 0:1 / / rw - overlay containerd rw\n'}):
        root = fake_root(tmp_path / str(len(files)) / next(iter(files)).replace('/', '_'), files)
        assert checks.in_container(root, env={})
        row = checks.check_container(LINUX, root, env={})
        assert row['status'] == 'fail' and row['line'] == "This computer can't run a virtual phone."
        assert 'container' in row['fix'].lower()
    plain = fake_root(tmp_path / 'host', {'proc/1/cgroup': '0::/init.scope\n',
                                          'proc/self/mountinfo': '1 0 0:1 /home /home rw - ext4 /dev/sda rw\n'})
    assert not checks.in_container(plain, env={}) and checks.check_container(LINUX, plain, env={})['status'] == 'ready'
    assert checks.in_container(plain, env={'KUBERNETES_SERVICE_HOST': '10.0.0.1'})
    with_kvm = fake_root(tmp_path / 'kvm', {'.dockerenv': '', 'dev/kvm': ''})
    assert checks.check_container(LINUX, with_kvm, env={})['status'] == 'ready'
    assert checks.check_container(WIN, fake_root(tmp_path / 'w', {'.dockerenv': ''}), env={})['status'] == 'ready'


def test_virtualisation_per_os(tmp_path):
    windows = {state: checks.check_virtualisation(WIN, query=lambda s=state: s) for state in (1, 2, 3, None)}
    assert windows[1]['status'] == 'ready'
    assert windows[2]['status'] == windows[3]['status'] == 'fail'
    assert windows[2]['fix'] == 'Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.'
    assert windows[None]['status'] == 'unknown'

    def broken():
        raise subprocess.TimeoutExpired('powershell', 30)
    assert checks.check_virtualisation(WIN, query=broken)['status'] == 'unknown'
    no_kvm = checks.check_virtualisation(LINUX, root=fake_root(tmp_path / 'a', {}))
    assert no_kvm['status'] == 'fail' and no_kvm['fix'] == 'Turn on virtualisation (KVM) on this computer.'
    assert checks.check_virtualisation(LINUX, root=fake_root(tmp_path / 'b', {'dev/kvm': ''}))['status'] == 'ready'
    assert checks.check_virtualisation(MAC, query=lambda: 1)['status'] == 'ready'
    assert checks.check_virtualisation(MAC, query=lambda: 0)['status'] == 'fail'


@pytest.mark.skipif(sys.platform != 'win32', reason='reads Windows Features')
def test_windows_hypervisor_platform_is_read_without_elevation():
    assert checks._windows_hypervisor_platform() in (1, 2, 3)


def meminfo(total_gb, available_gb):
    return f'MemTotal: {total_gb * 1024 * 1024} kB\nMemFree: 1 kB\nMemAvailable: {available_gb * 1024 * 1024} kB\n'


def test_memory_is_the_tighter_of_the_cgroup_and_the_machine(tmp_path):
    # a 2 GB container limit on a 64 GB machine with 60 GB free: 2 GB, not 60
    limited = fake_root(tmp_path / 'v2', {'proc/meminfo': meminfo(64, 60), 'sys/fs/cgroup/memory.max': str(2 * GB),
                                          'sys/fs/cgroup/memory.current': str(GB // 2),
                                          'sys/fs/cgroup/memory.stat': f'anon 1\ninactive_file {GB // 4}\n'})
    available, total = checks.linux_memory(limited)
    assert available == 2 * GB - (GB // 2 - GB // 4) and total == 2 * GB
    row = checks.check_memory(LINUX, root=limited, env={'DISPLAY': ':0'})
    assert row['status'] == 'fail' and row['line'] == 'The computer is low on memory. Close something there and try again.'
    unlimited_v1 = fake_root(tmp_path / 'v1', {'proc/meminfo': meminfo(64, 30),
                                               'sys/fs/cgroup/memory/memory.limit_in_bytes': str(1 << 62),
                                               'sys/fs/cgroup/memory/memory.usage_in_bytes': str(GB)})
    assert checks.linux_memory(unlimited_v1) == (30 * GB, 64 * GB)
    v2_max = fake_root(tmp_path / 'max', {'proc/meminfo': meminfo(64, 30), 'sys/fs/cgroup/memory.max': 'max',
                                          'sys/fs/cgroup/memory.current': str(GB)})
    assert checks.linux_memory(v2_max) == (30 * GB, 64 * GB)


def test_memory_thresholds_and_headless_linux():
    assert checks.check_memory(WIN, memory=(5 * GB, 64 * GB))['status'] == 'fail'
    assert checks.check_memory(WIN, memory=(6 * GB, 64 * GB))['status'] == 'ready'
    small = checks.check_memory(WIN, memory=(7 * GB, 12 * GB))
    assert small['status'] == 'warn' and not small['needs_continue']
    # headless Linux runs the software renderer: 8 GB
    assert checks.check_memory(LINUX, memory=(7 * GB, 64 * GB), env={})['status'] == 'fail'
    assert checks.check_memory(LINUX, memory=(7 * GB, 64 * GB), env={'DISPLAY': ':0'})['status'] == 'ready'
    assert checks.check_memory(LINUX, memory=(8 * GB, 64 * GB), env={})['status'] == 'ready'
    unknown = checks.check_memory(WIN, memory=(None, None))
    assert unknown['status'] == 'unknown' and checks.summarize([unknown])['ok']


def test_disk_space(tmp_path):
    low = checks.check_disk(tmp_path, free=10 * GB)
    assert low['status'] == 'fail' and low['line'] == 'Not enough free space. Free up 6 GB on the Hermes computer.'
    tight = checks.check_disk(tmp_path, free=20 * GB)
    assert tight['status'] == 'warn' and not tight['needs_continue'] and tight['line'] == 'Space is tight: about 20 GB free.'
    assert checks.check_disk(tmp_path, free=30 * GB)['status'] == 'ready'
    assert checks.check_disk(tmp_path / 'not' / 'made' / 'yet')['status'] in ('ready', 'warn', 'fail')  # nearest existing folder


def test_network_to_googles_site(dashboard):
    assert checks.check_network(dashboard({}).url + '/repository2-3.xml')['status'] == 'ready'
    assert checks.check_network(dashboard({}, code=404).url)['status'] == 'ready'  # an answer is an answer
    down = checks.check_network(f'http://127.0.0.1:{closed_port()}/x', timeout=2)
    assert down['status'] == 'fail' and down['fix'] == "Check the Hermes computer's internet."


def test_the_port_block():
    assert checks.check_ports(console_ports=range(41000, 41006, 2), free=lambda p: True)['status'] == 'ready'
    assert checks.check_ports(console_ports=range(41000, 41006, 2), free=lambda p: p != 41001)['status'] == 'ready'
    none = checks.check_ports(console_ports=range(41000, 41006, 2), free=lambda p: p % 2 == 0)
    assert none['status'] == 'fail' and none['fix'] == 'Close other virtual phones on this computer, then try again.'


def test_platform():
    assert checks.check_platform(WIN)['status'] == 'ready'
    bad = checks.check_platform({**WIN, 'arch': 'aarch64', 'supported': False})
    assert bad['status'] == 'fail' and bad['fix'] == 'There is no phone software for this kind of computer.'


# -- all together ----------------------------------------------------------------------------------------------------

def test_run_checks_in_order_and_one_failure_blocks(tmp_path, dashboard):
    kw = dict(host=WIN, memory=(32 * GB, 64 * GB), free_disk=100 * GB, virtualisation=lambda: 1,
              network_url=dashboard({}).url, console_ports=range(41000, 41004, 2), port_free=lambda p: True,
              developer_port=closed_port())
    good = checks.run_checks(tmp_path / 'phone', dashboard({'auth_required': True}).url, **kw)
    assert [r['id'] for r in good['rows']] == ['platform', 'container', 'virtualisation', 'memory', 'disk', 'network', 'ports',
                                               'sign_in', 'developer_tools']
    assert good['ok'] and not good['needs_continue'] and inst.checks_passed(good)
    assert all(r['status'] == 'ready' for r in good['rows'])
    off = checks.run_checks(tmp_path / 'phone', dashboard({'auth_required': False}).url, **kw)
    assert not off['ok'] and not inst.checks_passed(checks.continue_warnings(off))


JARGON = ('sdk', 'avd', 'adb', 'emulator', 'container', 'kvm', 'grpc', 'qemu', 'cgroup')


def test_lines_outside_fix_lines_are_plain():
    fix_only = {'container', 'virt_windows', 'virt_linux', 'virt_mac', 'unsupported', 'ports', 'network_fix', 'sign_in_fix',
                'sign_in_unread_fix'}
    for key, line in checks.LINES.items():
        assert '\\' not in line and not any(ch.isdigit() for ch in line.replace('{n}', '').replace('16 GB', '')), line
        if key not in fix_only:
            assert not any(word in line.lower() for word in JARGON), line
            assert '/' not in line, line
