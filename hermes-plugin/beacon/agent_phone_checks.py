"""The agent phone's host checks (EMU-DESIGN §3 step 2, §2.3 rules 2-3): every check before any download, two before every start.

Each check returns one row ``{'id', 'status', 'line', 'fix', 'how', 'needs_continue'}``:
- ``ready``; ``warn`` (amber; ``needs_continue`` when the owner must press Continue, as for the developer tools row);
- ``fail`` (blocks; ``line`` plus one ``fix`` line);
- ``unknown`` (could not be read; does not block, because the test download's ``-accel-check`` decides for virtualisation and,
  as Bot Desktop says, "a host we cannot read is not a host we know to be small" for memory).

The one exception is Hermes sign-in, which fails closed: only a JSON ``auth_required`` that is exactly ``true`` passes. A Hermes
whose dashboard has no sign-in hands its session token to any loopback peer, and a phone app is one (S10; ``gateway/run_api.py:84``).

Lines are short and plain: no SDK/AVD/adb/emulator/container/KVM word outside a fix line, never a path, port or token. ``{name}``
is the assistant's chosen name; the app fills it in (``words(row, name)`` does the same on the plugin side).
"""
import json
import math
import os
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

READY, WARN, FAIL, UNKNOWN = 'ready', 'warn', 'fail', 'unknown'
GB = 1 << 30
MIN_AVAILABLE_MEMORY = 6 * GB  # §3: to start with the host GPU (QEMU 1.3-2.3 GB private, up to 5.1 GB working set, S1/S4)
MIN_AVAILABLE_MEMORY_HEADLESS = 8 * GB  # headless Linux runs the software renderer (QEMU 5.3-5.8 GB, S1)
LOW_TOTAL_MEMORY = 16 * GB  # Google: the emulator wants 16 GB RAM
MIN_FREE_DISK = 16 * GB  # Google: 16 GB disk
LOW_FREE_DISK = 25 * GB
DEVELOPER_ADB_PORT = 5037  # adb's default server port (developer.android.com/tools/adb)
NETWORK_URL = 'https://dl.google.com/android/repository/repository2-3.xml'
SIGN_IN_HOW = 'https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard#authentication-gated-mode'
CONTAINER_MARKERS = ('docker', 'podman', '/lxc/', 'kubepods', 'containerd', 'crio')  # hermes_platform/host/runtime.py:53-63
ROOT_MOUNT_MARKERS = ('kubepods', 'containerd', 'crio')

LINES = {
    'cannot_run': "This computer can't run a virtual phone.",
    'unsupported': "There is no phone software for this kind of computer.",
    'container': "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.",
    'virt_windows': "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.",
    'virt_linux': "Turn on virtualisation (KVM) on this computer.",
    'virt_mac': "This Mac can't run it. Use another computer for Hermes.",
    'virt_unknown': "Checked again with the test download.",
    'memory_low': "The computer is low on memory. Close something there and try again.",
    'memory_small': "This computer has less than 16 GB memory, so the phone may be slow.",
    'memory_unknown': "Couldn't read the computer's memory.",
    'disk_low': "Not enough free space. Free up {n} GB on the Hermes computer.",
    'disk_tight': "Space is tight: about {n} GB free.",
    'network': "The Hermes computer can't reach Google's download site.",
    'network_fix': "Check the Hermes computer's internet.",
    'ports': "Close other virtual phones on this computer, then try again.",
    'sign_in_off': "Turn on Hermes sign-in first.",
    'sign_in_fix': "Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes.",
    'sign_in_unread': "Couldn't check Hermes sign-in.",
    'sign_in_unread_fix': "Make sure Hermes' dashboard is on, then check again.",
    'dashboard_off': "{name}'s phone can't start while Hermes' dashboard is off.",
    'developer_tools': "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
}


def reason_of(line):
    """The revision 5 code (``check.<key>``) of a line of this module's own table, with ``{name}`` unfilled; None for any other."""
    return next((f'check.{key}' for key, value in LINES.items() if line is not None and value == line), None)


def row(check_id, status, line=None, fix=None, how=None, needs_continue=False, reason=None, args=None):
    """One check's row. ``reason``/``fix_reason`` are the lines' codes (contract revision 5 §11.1): ``reason`` is given with
    ``args`` for a line with a number in it, else read from the table the line came from."""
    return {'id': check_id, 'status': status, 'line': line, 'reason': reason or reason_of(line), 'args': args, 'fix': fix,
            'fix_reason': reason_of(fix), 'how': how, 'needs_continue': bool(needs_continue)}


def words(item, name):
    """A row (or a line) with ``{name}`` filled in."""
    if isinstance(item, str):
        return item.replace('{name}', name)
    return {k: (v.replace('{name}', name) if isinstance(v, str) else v) for k, v in item.items()}


def _host():
    import importlib.util
    key = 'beacon_agent_phone_install'
    if key not in sys.modules:
        path = Path(__file__).resolve().parent / 'agent_phone_install.py'
        spec = importlib.util.spec_from_file_location(key, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


# -- the computer ----------------------------------------------------------------------------------------------------

def check_platform(host):
    if host['supported']:
        return row('platform', READY)
    return row('platform', FAIL, LINES['cannot_run'], LINES['unsupported'])


def _read(path):
    try:
        return Path(path).read_text(encoding='utf-8', errors='replace')
    except OSError:
        return ''


def in_container(root='/', env=None):
    """Hermes' own markers (``hermes_platform/host/runtime.py:53-63``), read under ``root`` so tests can fake a filesystem."""
    env = os.environ if env is None else env
    root = Path(root)
    if (root / '.dockerenv').exists() or (root / 'run' / '.containerenv').exists() or env.get('KUBERNETES_SERVICE_HOST'):
        return True
    if any(marker in _read(root / 'proc' / '1' / 'cgroup') for marker in CONTAINER_MARKERS):
        return True
    root_lines = [line for line in _read(root / 'proc' / 'self' / 'mountinfo').splitlines()
                  if len(fields := line.split()) >= 5 and fields[4] == '/']
    return any(marker in line for line in root_lines for marker in ROOT_MOUNT_MARKERS)


def kvm_usable(root='/'):
    path = Path(root) / 'dev' / 'kvm'
    return path.exists() and os.access(path, os.R_OK | os.W_OK)


def check_container(host, root='/', env=None):
    """A container without a read/write ``/dev/kvm`` can't run a VM-accelerated phone (Google's container scripts need it)."""
    if host['os'] != 'linux' or not in_container(root, env):
        return row('container', READY)
    if kvm_usable(root):
        return row('container', READY)
    return row('container', FAIL, LINES['cannot_run'], LINES['container'])


def _windows_hypervisor_platform(timeout=30):
    """``Win32_OptionalFeature`` ``HypervisorPlatform`` InstallState (1 enabled, 2 disabled, 3 absent), read unelevated; never the
    ``Win32_Processor`` virtualisation flags, which read False on a PC where WHPX works (§1)."""
    root = os.environ.get('SYSTEMROOT', r'C:\Windows')
    exe = Path(root) / 'System32' / 'WindowsPowerShell' / 'v1.0' / 'powershell.exe'
    keep = ('SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'TEMP', 'TMP', 'USERPROFILE', 'LOCALAPPDATA', 'APPDATA', 'PROGRAMDATA',
            'PROGRAMFILES', 'PROCESSOR_ARCHITECTURE', 'NUMBER_OF_PROCESSORS', 'COMPUTERNAME', 'USERNAME', 'USERDOMAIN', 'OS')
    env = {k: os.environ[k] for k in keep if k in os.environ}
    env['PATH'] = f'{root}\\System32;{root};{root}\\System32\\Wbem;{root}\\System32\\WindowsPowerShell\\v1.0'
    command = "(Get-CimInstance -ClassName Win32_OptionalFeature -Filter \"Name='HypervisorPlatform'\").InstallState"
    done = subprocess.run([str(exe), '-NoProfile', '-NonInteractive', '-Command', command], capture_output=True, text=True,
                          timeout=timeout, env=env, stdin=subprocess.DEVNULL,
                          creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    value = done.stdout.strip()
    return int(value) if value.isdigit() else None


def _mac_hv_support(timeout=10):
    done = subprocess.run(['/usr/sbin/sysctl', '-n', 'kern.hv_support'], capture_output=True, text=True, timeout=timeout)
    value = done.stdout.strip()
    return int(value) if value.isdigit() else None


def check_virtualisation(host, *, query=None, root='/'):
    """Windows Hypervisor Platform; KVM on Linux; Hypervisor.framework on macOS. Unreadable → unknown (the test download's
    ``-accel-check`` is the decisive check, §3 step 4)."""
    os_name = host['os']
    if os_name == 'linux':
        return row('virtualisation', READY) if kvm_usable(root) else row('virtualisation', FAIL, LINES['cannot_run'], LINES['virt_linux'])
    try:
        if os_name == 'windows':
            state = (query or _windows_hypervisor_platform)()
            if state == 1:
                return row('virtualisation', READY)
            if state in (2, 3):
                return row('virtualisation', FAIL, LINES['cannot_run'], LINES['virt_windows'])
        elif os_name == 'macosx':
            state = (query or _mac_hv_support)()
            if state == 1:
                return row('virtualisation', READY)
            if state == 0:
                return row('virtualisation', FAIL, LINES['cannot_run'], LINES['virt_mac'])
    except (OSError, subprocess.SubprocessError, ValueError):
        pass
    return row('virtualisation', UNKNOWN, LINES['virt_unknown'])


def _int_file(path):
    text = _read(path).strip()
    return int(text) if text.isdigit() else None  # "max" → None


def _stat_value(path, key):
    for line in _read(path).splitlines():
        name, _, rest = line.partition(' ')
        if name == key and rest.strip().isdigit():
            return int(rest.strip())
    return None


def linux_memory(root='/'):
    """``(available, total)`` in bytes as Bot Desktop reads them (``tools/bot_desktop/resources.py``): the tighter of the cgroup's
    headroom (v2 ``memory.max - (memory.current - inactive_file)``, v1 likewise) and the machine's ``MemAvailable``; the total is the
    cgroup limit when there is one. A limit of 8 GB on a 4 GB machine is not 8 GB."""
    root = Path(root)
    info = {}
    for line in _read(root / 'proc' / 'meminfo').splitlines():
        key, _, rest = line.partition(':')
        parts = rest.split()
        if parts and parts[0].isdigit():
            info[key] = int(parts[0]) * 1024
    v2, v1 = root / 'sys' / 'fs' / 'cgroup', root / 'sys' / 'fs' / 'cgroup' / 'memory'
    limit, usage = _int_file(v2 / 'memory.max'), _int_file(v2 / 'memory.current')
    cache = _stat_value(v2 / 'memory.stat', 'inactive_file')
    if limit is None and usage is None:
        limit, usage = _int_file(v1 / 'memory.limit_in_bytes'), _int_file(v1 / 'memory.usage_in_bytes')
        cache = _stat_value(v1 / 'memory.stat', 'total_inactive_file')
        if limit is not None and limit >= 1 << 60:  # v1 "unlimited"
            limit = None
    if usage is not None and cache:
        usage = max(usage - cache, 0)
    candidates = [x for x in (info.get('MemAvailable'), (limit - usage) if limit is not None and usage is not None else None)
                  if x is not None]
    available = max(0, min(candidates)) if candidates else None
    total = limit if limit is not None else info.get('MemTotal')
    return available, total


def host_memory(host, root='/'):
    if host['os'] == 'linux':
        return linux_memory(root)
    try:
        import psutil
        memory = psutil.virtual_memory()
        return memory.available, memory.total
    except Exception:  # health: allow BLE001 -- unreadable memory is "unknown", never a crash
        return None, None


def headless(host, env=None):
    env = os.environ if env is None else env
    return host['os'] == 'linux' and not env.get('DISPLAY') and not env.get('WAYLAND_DISPLAY')


def check_memory(host, *, memory=None, root='/', env=None):
    """≥ 6 GB available (≥ 8 GB on headless Linux) to start; a warning below 16 GB in total; unknown doesn't block."""
    available, total = memory if memory is not None else host_memory(host, root)
    if available is None:
        return row('memory', UNKNOWN, LINES['memory_unknown'])
    need = MIN_AVAILABLE_MEMORY_HEADLESS if headless(host, env) else MIN_AVAILABLE_MEMORY
    if available < need:
        return row('memory', FAIL, LINES['memory_low'])
    if total is not None and total < LOW_TOTAL_MEMORY:
        return row('memory', WARN, LINES['memory_small'])
    return row('memory', READY)


def check_disk(folder, *, free=None):
    """≥ 16 GB free on the phone folder's drive; a warning below 25 GB."""
    if free is None:
        path = Path(folder)
        while not path.exists() and path.parent != path:
            path = path.parent
        free = shutil.disk_usage(path).free
    if free < MIN_FREE_DISK:
        n = max(1, math.ceil((MIN_FREE_DISK - free) / GB))
        return row('disk', FAIL, LINES['disk_low'].format(n=n), reason='check.disk_low', args={'n': str(n)})
    if free < LOW_FREE_DISK:
        return row('disk', WARN, LINES['disk_tight'].format(n=int(free // GB)), reason='check.disk_tight', args={'n': str(int(free // GB))})
    return row('disk', READY)


def check_network(url=NETWORK_URL, *, opener=None, timeout=10):
    """Google's download site answers (any HTTP status counts as reachable). Uses the computer's proxy, as the download does."""
    opener = opener or urllib.request.build_opener()
    request = urllib.request.Request(url, method='HEAD', headers={'User-Agent': 'Beacon-agent-phone/1'})
    try:
        with opener.open(request, timeout=timeout):
            pass
    except urllib.error.HTTPError:
        pass
    except (urllib.error.URLError, OSError, ValueError):
        return row('network', FAIL, LINES['network'], LINES['network_fix'])
    return row('network', READY)


def check_ports(*, console_ports=None, free=None):
    """At least one even console port in 5640-5680 with its adb port free (the pick itself runs at the first start)."""
    install = _host()
    console_ports = install.CONSOLE_PORTS if console_ports is None else console_ports
    free = free or install._host_module().port_free
    if any(p % 2 == 0 and free(p) and free(p + 1) for p in console_ports):
        return row('ports', READY)
    return row('ports', FAIL, LINES['cannot_run'], LINES['ports'])


# -- Hermes and the developer's tools (also before every start) -------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def read_sign_in(dashboard, *, timeout=5.0, opener=None):
    """``auth_required`` from Hermes' own ``GET /api/status`` (public route, ``web_routers/status.py:530``) at ``dashboard`` (the
    dashboard's address, e.g. ``http://127.0.0.1:9119``; no default). No proxy, no redirects. True, False, or None (unreadable)."""
    if not dashboard:
        return None
    parsed = urllib.parse.urlsplit(str(dashboard))
    if parsed.scheme not in ('http', 'https') or not parsed.hostname:
        return None
    url = urllib.parse.urlunsplit((parsed.scheme, parsed.netloc, parsed.path.rstrip('/') + '/api/status', '', ''))
    opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}), _NoRedirect())
    try:
        with opener.open(urllib.request.Request(url, headers={'Accept': 'application/json'}), timeout=timeout) as response:
            if response.status != 200:
                return None
            value = json.loads(response.read(1024 * 1024)).get('auth_required')
    except (urllib.error.URLError, OSError, ValueError, AttributeError):
        return None
    return value if isinstance(value, bool) else None


def check_sign_in(dashboard, *, timeout=5.0, opener=None):
    """Hermes sign-in must be on (Root's decision 1). Off fails with the fix; unreadable fails too (it never passes unread)."""
    value = read_sign_in(dashboard, timeout=timeout, opener=opener)
    if value is True:
        return row('sign_in', READY)
    if value is False:
        return row('sign_in', FAIL, LINES['sign_in_off'], LINES['sign_in_fix'], SIGN_IN_HOW)
    return row('sign_in', FAIL, LINES['sign_in_unread'], LINES['sign_in_unread_fix'])


def check_developer_tools(*, port=DEVELOPER_ADB_PORT, host='127.0.0.1', timeout=1.0):
    """A developer adb server on 5037, found by a raw TCP connect (never an ``adb`` call, which would start one there). Open →
    an amber row with [Check again] [Continue]: the owner's PC and every test host run one."""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            pass
    except OSError:
        return row('developer_tools', READY)
    return row('developer_tools', WARN, LINES['developer_tools'], needs_continue=True)


# -- together ----------------------------------------------------------------------------------------------------------

def summarize(rows, **extra):
    return {'rows': rows, 'ok': not any(r['status'] == FAIL for r in rows),
            'needs_continue': any(r['needs_continue'] for r in rows), 'continued': False, 'checked_at': time.time(), **extra}


def run_checks(folder, dashboard, *, host=None, root='/', env=None, memory=None, free_disk=None, virtualisation=None,
               network_url=NETWORK_URL, network_opener=None, console_ports=None, port_free=None,
               developer_port=DEVELOPER_ADB_PORT, sign_in_opener=None):
    """Every check before any download, in the order the owner reads them. ``run_install`` refuses unless this passed."""
    host = host or _host().host_platform()
    rows = [check_platform(host), check_container(host, root, env), check_virtualisation(host, query=virtualisation, root=root),
            check_memory(host, memory=memory, root=root, env=env), check_disk(folder, free=free_disk),
            check_network(network_url, opener=network_opener), check_ports(console_ports=console_ports, free=port_free),
            check_sign_in(dashboard, opener=sign_in_opener), check_developer_tools(port=developer_port)]
    return summarize(rows)


def continue_warnings(result):
    """The owner pressed Continue on the amber rows."""
    return {**result, 'continued': True}


def start_checks(dashboard, *, developer_port=DEVELOPER_ADB_PORT, sign_in_opener=None):
    """Before every phone start, from whichever process starts it (§3): Hermes sign-in, and the developer tools line again."""
    return summarize([check_sign_in(dashboard, opener=sign_in_opener), check_developer_tools(port=developer_port)])


def start_blocker(result, name):
    """The one plain line the agent (or the phone page) gets when a start must not happen, else None. A developer adb server
    does not block a start; its line stays on the phone page."""
    for item in result['rows']:
        if item['id'] == 'sign_in' and item['status'] != READY:
            return words(LINES['sign_in_off'] if item['line'] == LINES['sign_in_off'] else LINES['dashboard_off'], name)
    return None


def start_gate(hermes_home=None, *, name='Beacon', developer_port=DEVELOPER_ADB_PORT, sign_in_opener=None):
    """``(ok, line)`` before starting a phone that is off (L3's tool, the setup view, the keeper's caller): the sign-in check at
    the dashboard address the pointer records (``plugin-data/beacon/agent-phone.json``). ``line`` is None when ok. No pointer or
    no recorded address counts as a dashboard that can't be read: the phone doesn't start. ``hermes_home`` defaults to the
    running process's own home (``get_process_hermes_home``), which is the home a tool call or a route serves."""
    if hermes_home is None:
        from hermes_constants import get_process_hermes_home
        hermes_home = get_process_hermes_home()
    pointer = _host().read_pointer(hermes_home) or {}
    result = start_checks(pointer.get('dashboard'), developer_port=developer_port, sign_in_opener=sign_in_opener)
    line = start_blocker(result, name)
    return line is None, line
