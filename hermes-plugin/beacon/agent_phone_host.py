"""The agent phone's host runtime (EMU-DESIGN §2.2, §2.4, §7.1): launch, adopt, the ANR check, first-start settings, clean stop.

One phone per phone folder (§2.1; L1a creates the folder, the SDK, the AVD and the phone's key). Any Beacon process (dashboard,
gateway, the keeper) adopts a running phone from ``run/state.json`` (pid + process birth + QEMU pid + ports) plus the gRPC token,
which is read from the emulator's discovery file at the moment of use and kept in memory only.

Measured rules this module follows (S1-S10, the L1b spikes of 17 Nov):
- the emulator gets an allow-listed environment: the phone folder's SDK, homes and temp, no ``HERMES_*``, no credentials, no
  ``LOCALAPPDATA`` (so its discovery file stays in ``home/avd/running/``), ``ANDROID_ADB_SERVER_PORT`` at a port nothing
  listens on (so it never registers with a developer adb server on 5037);
- no adb server at all: commands go straight to the phone's adbd with the phone's key (agent_phone_adb), and never before gRPC
  says the phone has booted (contacting adbd during boot left the key refused);
- after every start or adopt, an "Application Not Responding" window is closed; Quick Boot is never saved with one open (S4);
- a clean stop is gRPC ``setVmState(SHUTDOWN)`` (S7), which leaves the discovery file behind, so the host deletes it.
"""
import contextlib
import hashlib
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
from pathlib import Path

AVD_NAME = 'beacon_agent_phone'
HELPER = 'dev.hermes.agentphone.helper'
AUTOFILL_SERVICE = HELPER + '/.HelperAutofillService'
INPUT_METHOD = HELPER + '/.HelperInputMethod'  # the phone's keyboard without keys (EMU-JOIN fu2)
CODE_LISTENER = HELPER + '/' + HELPER + '.CodeListener'  # the full component: the short form returns 0 and does nothing (S13)
DEAD_ADB_SERVER_PORT = 1  # the emulator notifies "its" adb server here; nothing listens (a port only root can bind on POSIX)
NO_NETSIM = '-WiFiPacketStream,-Uwb,-BluetoothEmulation'  # feature names from the emulator's advancedFeatures.ini / binaries
BOOT_SECONDS = 300.0  # first boot 58.8-67 s, cold 21 s, Quick Boot 2.7 s (S4, L1b)
STOP_SECONDS = 90.0  # clean stop 3.5-21.5 s (S7, L1b)
FIRST_START = 'first-start.json'
# Window titles of Android's own error dialogs (AOSP AppErrorDialog / AppNotRespondingDialog set them), as `dumpsys window`
# names the focused window.
DIALOG_WINDOWS = ('Application Error', 'Application Not Responding')
WIN_ENV_KEEP = ('SYSTEMROOT', 'WINDIR', 'SYSTEMDRIVE', 'COMSPEC', 'PATHEXT', 'NUMBER_OF_PROCESSORS', 'PROCESSOR_ARCHITECTURE',
                'PROCESSOR_IDENTIFIER', 'PROCESSOR_LEVEL', 'PROCESSOR_REVISION', 'OS', 'USERNAME', 'USERDOMAIN', 'COMPUTERNAME')
POSIX_ENV_KEEP = ('PATH', 'LANG', 'LC_ALL', 'LC_CTYPE', 'DISPLAY', 'WAYLAND_DISPLAY', 'XAUTHORITY', 'XDG_RUNTIME_DIR', 'USER',
                  'LOGNAME')


def _sibling(name):
    """A Beacon module beside this one: the plugin's own copy when loaded, else loaded by path (the keeper runs without Hermes)."""
    key = 'beacon_' + name
    if key not in sys.modules:
        path = Path(__file__).resolve().parent / f'{name}.py'
        spec = importlib.util.spec_from_file_location(key, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


class PhoneError(Exception):
    """The phone could not do what was asked. The text is plain and holds no token, path of a secret or command."""

    @property
    def reason(self):
        """The line's code (contract revision 5 §11.1), from PHONE_REASONS; None for a line it doesn't list."""
        return PHONE_REASONS.get(str(self))


class Folder:
    """The phone folder's layout (§2.1)."""

    def __init__(self, root):
        self.root = Path(root)
        self.sdk = self.root / 'sdk'
        self.home = self.root / 'home'
        self.avd_home = self.home / 'avd'
        self.avd_dir = self.avd_home / f'{AVD_NAME}.avd'
        self.run = self.root / 'run'
        self.tmp = self.root / 'tmp'
        self.frames = self.tmp / 'AndroidEmulator'
        self.state = self.run / 'state.json'
        self.keeper_conf = self.run / 'keeper.json'
        self.keeper_py = self.root / 'keeper.py'
        self.discovery_dir = self.avd_home / 'running'

    def emulator(self, os_name=os.name):
        return self.sdk / 'emulator' / ('emulator.exe' if os_name == 'nt' else 'emulator')


# -- small pure helpers (tested without an emulator) ----------------------------------------------------------------------

def phone_env(folder, base=None, os_name=os.name):
    """The environment of every child that touches the phone: allow-listed, never ``HERMES_*`` or a credential (§7.2 rule 1)."""
    base = os.environ if base is None else base
    folder = folder if isinstance(folder, Folder) else Folder(folder)
    if os_name == 'nt':
        env = {k: base[k] for k in WIN_ENV_KEEP if k in base}
        root = base.get('SYSTEMROOT', r'C:\Windows')
        env['PATH'] = f'{root}\\system32;{root};{root}\\System32\\Wbem'
        env['USERPROFILE'] = str(folder.home)
    else:
        env = {k: base[k] for k in POSIX_ENV_KEEP if k in base}
        env.setdefault('PATH', '/usr/bin:/bin')
        env['TMPDIR'] = str(folder.tmp)
    env.update({'ANDROID_HOME': str(folder.sdk), 'ANDROID_SDK_ROOT': str(folder.sdk), 'ANDROID_USER_HOME': str(folder.home),
                'ANDROID_EMULATOR_HOME': str(folder.home), 'ANDROID_AVD_HOME': str(folder.avd_home), 'HOME': str(folder.home),
                'TEMP': str(folder.tmp), 'TMP': str(folder.tmp), 'ANDROID_ADB_SERVER_PORT': str(DEAD_ADB_SERVER_PORT)})
    return env


def guest_ram_mb(total_bytes):
    """4 GB for the phone, 3 GB on a computer below 16 GB (§2.4)."""
    return 3072 if total_bytes < 16 * 2**30 else 4096


def headless_linux(env=None, platform=sys.platform):
    env = os.environ if env is None else env
    return platform.startswith('linux') and not env.get('DISPLAY') and not env.get('WAYLAND_DISPLAY')


def launch_args(folder, ports, *, ram_mb=4096, gpu='auto', cold=False, headless=False, os_name=os.name):
    """The emulator's command line (§2.4). gRPC as ``-grpc <port>`` (measured: it binds 127.0.0.1 with token auth)."""
    folder = folder if isinstance(folder, Folder) else Folder(folder)
    # -feature: no netsim at all (L1b, 17 Nov). -WiFiPacketStream keeps other emulators off the phone's Wi-Fi (B2); with -Uwb
    # and -BluetoothEmulation too, netsimd never starts, so its unauthenticated loopback gRPC port is gone. The phone keeps
    # internet and Play; it has no Bluetooth or UWB, which it does not need.
    args = [str(folder.emulator(os_name)), '-avd', AVD_NAME, '-port', str(int(ports['console'])), '-grpc', str(int(ports['grpc'])),
            '-grpc-use-token', '-no-metrics', '-crash-report-mode', 'never', '-feature', NO_NETSIM,
            '-no-window' if headless else '-qt-hide-window', '-no-boot-anim', '-no-audio', '-netfast', '-gpu', gpu,
            '-memory', str(int(ram_mb)), '-cores', '4']
    if cold:
        args.append('-no-snapshot-load')
    return args


def read_ini(path):
    try:
        text = Path(path).read_text(encoding='utf-8', errors='replace')
    except OSError:
        return {}
    return dict(line.split('=', 1) for line in text.splitlines() if '=' in line)


def discovery_from_log(log_path):
    """The emulator's own "Advertising in: <path>" line: where it put its discovery file this run."""
    try:
        text = Path(log_path).read_text(encoding='utf-8', errors='replace')
    except OSError:
        return None
    found = re.findall(r'Advertising in: (.+?\.ini)\s*$', text, re.M)
    return Path(found[-1].strip()) if found else None


def _reader_roots(env, os_name, platform, uid):
    """The places Google's own discovery reader looks in for one environment, before ``avd/running`` is added (AOSP aemu-grpc
    ``emulator_discovery.py``, ``get_discovery_directories()``): the OS place, then the user folders."""
    roots = []
    if os_name == 'nt':
        if env.get('LOCALAPPDATA'):
            roots.append(Path(env['LOCALAPPDATA']) / 'Temp')
    else:
        linux, mac = str(platform).startswith('linux'), platform == 'darwin'
        if not (linux or mac):
            linux = mac = True  # a system this table doesn't know: both places; is_ours() says which file is this phone's
        if linux:
            # The reader takes $XDG_RUNTIME_DIR, and /run/user/<uid> when that is unset or missing. Both are looked in here: the
            # emulator that wrote the file may have chosen the other one, and is_ours() says which file is this phone's.
            if env.get('XDG_RUNTIME_DIR'):
                roots.append(Path(env['XDG_RUNTIME_DIR']))
            if uid is not None:
                roots.append(Path('/') / 'run' / 'user' / str(uid))
        if mac and env.get('HOME'):
            roots.append(Path(env['HOME']) / 'Library' / 'Caches' / 'TemporaryItems')
    if env.get('ANDROID_EMULATOR_HOME'):
        roots.append(Path(env['ANDROID_EMULATOR_HOME']))
    if env.get('ANDROID_SDK_HOME'):
        roots.append(Path(env['ANDROID_SDK_HOME']) / '.android')
    if env.get('ANDROID_AVD_HOME'):
        roots.append(Path(env['ANDROID_AVD_HOME']))
    home = env.get('HOME') or env.get('USERPROFILE')  # Path.home(): HOME on POSIX, USERPROFILE on Windows
    if home:
        roots.append(Path(home) / '.android')
    return roots


def discovery_dirs(folder, env=None, os_name=os.name, platform=sys.platform, uid=None):
    """Every place a discovery file of this phone can be: the list Google's own reader scans, for the emulator's allow-listed
    environment (``phone_env``: HOME is the phone's home) and for ``env`` (the caller's own, default this process's: a phone
    started with an inherited environment, the spikes' S2), each place with ``avd/running`` added. The phone's own
    ``home/avd/running`` comes first. A place holds other emulators' files too; ``is_ours`` says which file is this phone's."""
    folder = folder if isinstance(folder, Folder) else Folder(folder)
    base = os.environ if env is None else env
    if uid is None and hasattr(os, 'getuid'):
        uid = os.getuid()
    dirs = [folder.discovery_dir]
    for seen in (phone_env(folder, base=base, os_name=os_name), base):
        dirs.extend(root / 'avd' / 'running' for root in _reader_roots(seen, os_name, platform, uid))
    return list(dict.fromkeys(dirs))


def is_ours(ini, folder):
    folder = folder if isinstance(folder, Folder) else Folder(folder)
    avd_dir = read_ini(ini).get('avd.dir', '')
    try:
        return bool(avd_dir) and os.path.normcase(os.path.abspath(avd_dir)) == os.path.normcase(os.path.abspath(folder.avd_dir))
    except (TypeError, ValueError):
        return False


def parse_focus(text):
    """(focus line, package of an "Application Not Responding" window or None) from ``dumpsys window`` focus lines."""
    lines = [line.strip() for line in text.splitlines() if 'mCurrentFocus' in line or 'mFocusedWindow' in line]
    focus = lines[0] if lines else ''
    m = re.search(r'Application Not Responding: ([A-Za-z0-9_.]+)', ' '.join(lines))
    if m:
        return focus, m.group(1)
    if re.search(r"isn't responding|Not Responding", ' '.join(lines)):
        return focus, ''
    return focus, None


def find_close_button(xml):
    """Centre of the ANR dialog's "Close app" (``android:id/aerr_close``, AOSP app_anr_dialog.xml), or None."""
    for node in re.findall(r'<node\b[^>]*>', xml or ''):
        rid = re.search(r'resource-id="([^"]*)"', node)
        text = re.search(r'\stext="([^"]*)"', node)
        if (rid and rid.group(1).endswith(':id/aerr_close')) or (text and text.group(1).strip().lower() == 'close app'):
            b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
            if b:
                x1, y1, x2, y2 = map(int, b.groups())
                return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def port_free(port, host='127.0.0.1'):
    s = socket.socket()
    try:
        if hasattr(socket, 'SO_EXCLUSIVEADDRUSE'):
            s.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
        s.bind((host, int(port)))
        return True
    except OSError:
        return False
    finally:
        s.close()


def ports_free(ports):
    return all(port_free(p) for p in (ports['console'], ports['console'] + 1, ports['grpc']))


def write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(data, indent=1), encoding='utf-8')
    os.replace(tmp, path)


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


# -- processes -----------------------------------------------------------------------------------------------------------

def _psutil():
    import psutil

    return psutil


def alive(pid, birth):
    if not pid:
        return False
    try:
        return abs(_psutil().Process(int(pid)).create_time() - float(birth or 0)) < 1.0
    except Exception:  # health: allow BLE001 -- gone, reused or not ours
        return False


def in_job(pid):
    """IsProcessInJob(pid, any job), with 64-bit handle types (S7: Hermes' own attach lacked them). None off Windows."""
    if os.name != 'nt':
        return None
    import ctypes
    from ctypes import wintypes

    k32 = ctypes.WinDLL('kernel32', use_last_error=True)
    k32.OpenProcess.restype = ctypes.c_void_p
    k32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
    k32.IsProcessInJob.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(wintypes.BOOL)]
    k32.CloseHandle.argtypes = [ctypes.c_void_p]
    handle = k32.OpenProcess(0x1000, False, int(pid))  # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        return None
    try:
        result = wintypes.BOOL()
        if not k32.IsProcessInJob(handle, None, ctypes.byref(result)):
            return None
        return bool(result.value)
    finally:
        k32.CloseHandle(handle)


def end_tree(pid, birth):
    """Kills a birth-checked process and its descendants (a hard kill: no Quick Boot save, S7)."""
    if not alive(pid, birth):
        return
    psutil = _psutil()
    try:
        root = psutil.Process(int(pid))
        procs = root.children(recursive=True) + [root]
    except Exception:  # health: allow BLE001 -- gone meanwhile
        return
    for proc in procs:
        try:
            proc.kill()
        except Exception:  # health: allow BLE001 -- gone meanwhile
            pass
    psutil.wait_procs(procs, timeout=10)


def _exe(pid):
    try:
        return _psutil().Process(int(pid)).exe() or None
    except Exception:  # health: allow BLE001 -- gone, or not ours to read
        return None


def birth(pid):
    """A process's creation time read from here (psutil is Hermes' own dependency in this process), or None when it is gone."""
    try:
        return _psutil().Process(int(pid)).create_time()
    except Exception:  # health: allow BLE001 -- gone, or not ours to read
        return None


_KEEPER_PYTHONS = {}


def _venv_pythons():
    """The interpreters of the virtual environment psutil is installed in (its pyvenv.cfg is a few folders above it)."""
    try:
        here = Path(_psutil().__file__).resolve().parent
    except Exception:  # health: allow BLE001 -- psutil not importable here either
        return []
    for up in list(here.parents)[:4]:
        if (up / 'pyvenv.cfg').is_file():
            return [p for p in (up / 'Scripts' / 'python.exe', up / 'bin' / 'python3', up / 'bin' / 'python') if p.is_file()]
    return []


def keeper_python(env, candidates=None, run=subprocess.run):
    """The interpreter for ``keeper.py`` (spawn and watch): it runs as ``python -I`` with the phone's allow-listed environment
    and needs psutil. Hermes' dashboard runs on its bare base interpreter with its packages on a path its own launcher sets up,
    so ``sys.executable -I`` there has no psutil (EMU-JOIN, demo Hermes, 17 Nov: every spawn lost its create time and a booted
    phone was taken for dead and left running). The first candidate that imports psutil that way: this process's interpreter,
    then the one of the virtual environment that holds psutil. Checked once per process. None when none can: then nothing
    starts (``NO_KEEPER``), since a keeper that can't see the phone exits at once and its stop would report a running phone
    as stopped."""
    flags = 0x08000000 if os.name == 'nt' else 0  # CREATE_NO_WINDOW
    for python in dict.fromkeys(str(c) for c in (candidates if candidates is not None else [sys.executable, *_venv_pythons()])):
        if python not in _KEEPER_PYTHONS:
            try:
                _KEEPER_PYTHONS[python] = run([python, '-I', '-c', 'import psutil'], env=env, stdin=subprocess.DEVNULL,
                                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60,
                                              creationflags=flags).returncode == 0
            except (OSError, subprocess.SubprocessError):
                _KEEPER_PYTHONS[python] = False
        if _KEEPER_PYTHONS[python]:
            return python
    return None


NO_KEEPER = "The phone can't start on this Hermes. Update Beacon."
# Every line a PhoneError can carry and its code (contract revision 5 §11.1); a test reads each raise in this module against it.
PHONE_REASONS = {
    "The phone didn't start.": 'phone.not_started',
    NO_KEEPER: 'phone.no_keeper',
    "The phone's process could not start.": 'phone.process',
    "The phone's process could not start outside Hermes' job.": 'phone.process_job',
    "The phone's control line is not ready.": 'phone.control',
    "The phone's helper file is damaged.": 'phone.helper_damaged',
    "The phone's ports are in use.": 'phone.ports',
    'The phone is busy. Try again in a minute.': 'phone.busy',
    'The phone is not set up yet.': 'phone.not_set_up',
}


class Spawner:
    """Starts a process detached through ``keeper.py spawn`` (S7), checks IsProcessInJob, and falls back to WMI
    ``Win32_Process.Create`` when a job that forbids breakaway still holds it (rule 4). The spec file holds only the allow-listed
    environment (paths, no secrets) and is deleted at once. ``python`` defaults to ``keeper_python``.

    Linux under Hermes' systemd service (REVIEW-PHONE-121 P2-5, Root's decision 18 Nov): a Hermes restart kills the service's whole
    cgroup (``KillMode=mixed``), the phone and its keeper with it; the next use starts the phone again (a hard kill, so a cold
    boot). Hermes' ``restart_safe_gateway_child_argv`` would move them out of that cgroup, but its transient scope caps memory at
    4 GiB and a phone's QEMU runs past that, so it is not used here until a Linux host can measure a scope sized for the phone."""

    def __init__(self, folder, python=None, wmi=None, check_job=in_job):
        self.folder = folder if isinstance(folder, Folder) else Folder(folder)
        self.python = python
        self.wmi = wmi or self._wmi_create
        self.check_job = check_job

    def _python(self, env):
        python = self.python or keeper_python(env)
        if python is None:
            raise PhoneError(NO_KEEPER)
        return python

    def _spec(self, name, cmd, env, log, cwd):
        spec = self.folder.run / f'spawn-{name}.json'
        out = self.folder.run / f'spawn-{name}.out.json'
        for p in (out,):
            try:
                p.unlink()
            except OSError:
                pass
        write_json(spec, {'cmd': [str(c) for c in cmd], 'env': env, 'log': str(log) if log else None, 'cwd': str(cwd), 'out': str(out)})
        return spec, out

    def _intermediate(self, spec, env):
        flags = 0x08000000 if os.name == 'nt' else 0  # CREATE_NO_WINDOW
        subprocess.run([self._python(env), '-I', str(self.folder.keeper_py), 'spawn', str(spec)], env=env, cwd=str(self.folder.root),
                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60, creationflags=flags)

    def _wmi_create(self, spec, env):
        """WMI starts the intermediate under WmiPrvSE, outside our job chain, with the environment passed explicitly."""
        req = self.folder.run / 'spawn-wmi.json'
        write_json(req, {'cmd': f'"{self._python(env)}" -I "{self.folder.keeper_py}" spawn "{spec}"', 'cwd': str(self.folder.root),
                         'env': [f'{k}={v}' for k, v in env.items()]})
        # -Command folds trailing arguments into the script text, so the request's path travels in the environment
        script = ("$j = Get-Content -Raw -LiteralPath $env:BEACON_WMI_REQUEST | ConvertFrom-Json; "
                  "$s = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ShowWindow=[uint16]0; "
                  "EnvironmentVariables=[string[]]$j.env}; "
                  "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine=$j.cmd; "
                  "CurrentDirectory=$j.cwd; ProcessStartupInformation=$s}; exit [int]$r.ReturnValue")
        try:
            subprocess.run(['powershell', '-NoProfile', '-NonInteractive', '-Command', script],
                           env={**env, 'BEACON_WMI_REQUEST': str(req)}, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, timeout=60, creationflags=0x08000000)
        finally:
            try:
                req.unlink()
            except OSError:
                pass

    @staticmethod
    def _wait_out(out, seconds=30.0):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            data = read_json(out)
            if data:
                return data
            time.sleep(0.1)
        return None

    def spawn(self, name, cmd, env, log=None, cwd=None):
        spec, out = self._spec(name, cmd, env, log, cwd or self.folder.root)
        try:
            self._intermediate(spec, env)
            res = self._wait_out(out, 5.0) or {}
            if 'pid' not in res:
                raise PhoneError("The phone's process could not start.")
            res['create_time'] = res.get('create_time') or birth(res['pid'])  # read here when the intermediate couldn't
            res['via'] = 'intermediate'
            if self.check_job(res['pid']):
                end_tree(res['pid'], res.get('create_time'))
                try:
                    out.unlink()
                except OSError:
                    pass
                self.wmi(spec, env)
                res = self._wait_out(out) or {}
                if 'pid' not in res:
                    raise PhoneError("The phone's process could not start outside Hermes' job.")
                res['create_time'] = res.get('create_time') or birth(res['pid'])
                res['via'] = 'wmi'
                res['in_job_after_wmi'] = self.check_job(res['pid'])
            return res
        finally:
            for p in (spec, out):
                try:
                    p.unlink()
                except OSError:
                    pass


# -- the host ------------------------------------------------------------------------------------------------------------

class PhoneHost:
    """The phone's lifecycle for one phone folder.

    ``pick_ports(folder) -> {'console': even port in 5640-5680, 'grpc': port}`` is L1a's locked pick; it is called only when the
    recorded ports are taken. ``helper_apk``/``helper_sha256`` pin the Beacon helper installed on first start, and again when
    a newer one is pinned; ``helper_fetch()`` returns the helper's path when ``helper_apk`` isn't there yet (L1a fetches it).
    ``plugin_dir`` is where the keeper finds this module for its stop (defaults to this file's folder). ``view_open()`` says
    whether a setup view may be using the frame file (L2's lease)."""

    def __init__(self, folder, *, pick_ports=None, helper_apk=None, helper_sha256=None, helper_fetch=None, plugin_dir=None,
                 python=None, view_open=None, spawner=None, idle_minutes=30, heartbeat_hours=24):
        self.folder = folder if isinstance(folder, Folder) else Folder(folder)
        self.pick_ports = pick_ports
        self.helper_apk, self.helper_sha256, self.helper_fetch = helper_apk, helper_sha256, helper_fetch
        self.plugin_dir = Path(plugin_dir) if plugin_dir else Path(__file__).resolve().parent
        self.python = python  # None: keeper_python() at spawn time
        self.view_open = view_open
        self.spawner = spawner or Spawner(self.folder, python=self.python)
        self.idle_minutes, self.heartbeat_hours = idle_minutes, heartbeat_hours

    # -- state, token, clients --
    def state(self):
        return read_json(self.folder.state)

    def running(self, state=None):
        state = state if state is not None else self.state()
        return bool(state and state.get('running') and alive(state.get('pid'), state.get('create_time')))

    def _token(self, state):
        path = state.get('discovery') and Path(state['discovery'])
        if not path or not path.exists():
            path = self._find_discovery(state)
        kv = read_ini(path) if path else {}
        return kv.get('grpc.token'), int(kv.get('grpc.port') or state['ports']['grpc'])

    def grpc(self, state=None, timeout=15.0):
        state = state or self.state()
        token, port = self._token(state)
        if not token:
            raise PhoneError("The phone's control line is not ready.")
        return _sibling('agent_phone_grpc').EmulatorGrpc(port, token, timeout=timeout)

    def adb(self, state=None):
        state = state or self.state()
        return _sibling('agent_phone_adb').PhoneAdb(self.folder.home, int(state['ports']['console']) + 1)

    def status(self, state=None, timeout=5.0):
        with self.grpc(state, timeout=timeout) as g:
            return g.status(timeout=timeout)

    # -- files --
    def note_activity(self, agent=False):
        """Every agent call and setup input calls this; the keeper stops the phone after ``idle_minutes`` without it. An agent
        call (``agent=True``) also marks ``run/agent-activity``, the phone page's "{name} is using it" (Root, 17 Nov: only the
        agent's own actions; setup input and Turn on never set it)."""
        self._touch(self.folder.run / 'activity')
        if agent:
            self._touch(self.folder.run / 'agent-activity')

    def helper_installed(self, sha256):
        """Records a helper installed from the setup side (L2's helper update), as first start records its own."""
        marker = self.folder.run / FIRST_START
        kept = read_json(marker) or {}
        write_json(marker, {**kept, 'done': sorted(set(kept.get('done', [])) | {'helper', 'autofill', 'code_listener'}),
                            'helper_sha256': str(sha256).lower()})

    def write_heartbeat(self):
        """Written each minute while Hermes runs (by the gateway); older than ``heartbeat_hours`` → the keeper stops the phone."""
        self._touch(self.folder.run / 'heartbeat')

    @staticmethod
    def _touch(path):
        """Never brings back a removed phone: ``run/`` is made only inside a phone folder that still exists."""
        try:
            path.parent.mkdir(exist_ok=True)
            with open(path, 'a', encoding='utf-8'):
                pass
            os.utime(path, None)
        except FileNotFoundError:
            pass

    def cleanup_frames(self, force=False):
        """Deletes leftover live-view frame files (§4.5). On adopt, only when no view may hold one (POSIX would unlink an open
        mapping; Windows refuses to delete a mapped file, which this tolerates)."""
        if not force and self.view_open is None and os.name != 'nt':
            return 0
        # Snapshot before checking the lease so a new view's unique file cannot enter this sweep.
        candidates = list(self.folder.run.glob('frames-*.bin'))
        if self.folder.frames.is_dir():
            # A partial installation may lack the shared selector. Leave the emulator's own directory alone in that case.
            with contextlib.suppress(ImportError, OSError, AttributeError):
                candidates.extend(_sibling('agent_phone_keeper').mmap_frame_files(self.folder.root, self.state()))
        if not force and self.view_open is not None and self.view_open():
            return 0
        removed = 0
        for frame in candidates:
            try:
                frame.unlink()
                removed += 1
            except OSError:
                pass
        return removed

    def cleanup_discovery(self, keep=None):
        """Deletes this phone's discovery files whose emulator is gone (S2/L1b: a clean stop leaves ours behind)."""
        removed = 0
        psutil = _psutil()
        dirs = discovery_dirs(self.folder)
        recorded = (self.state() or {}).get('discovery')  # wherever this phone's file was found (the log's line can name any place)
        if isinstance(recorded, str) and recorded and Path(recorded).parent not in dirs:
            dirs.append(Path(recorded).parent)
        for d in dirs:
            for ini in d.glob('pid_*.ini') if d.is_dir() else ():
                if keep and os.path.normcase(str(ini)) == os.path.normcase(str(keep)):
                    continue
                m = re.fullmatch(r'pid_(\d+)\.ini', ini.name)
                if not m or not is_ours(ini, self.folder) or psutil.pid_exists(int(m.group(1))):
                    continue
                try:
                    ini.unlink()
                    removed += 1
                except OSError:
                    continue
                side = d / m.group(1)  # the emulator's jwks folder beside a discovery file (S2)
                if side.is_dir() and side.name.isdigit():
                    shutil.rmtree(side, ignore_errors=True)
        return removed

    def cleanup_masks(self):
        """The masks kept for the gateway (``agent_phone_redact.MaskSet``'s store: digests, never values) are also the record that a
        fill happened. A stop while that record exists saves no snapshot and drops the old one, which deletes them
        (``_drop_quick_boot``), and every start that boots cold deletes them; a hard kill that keeps an old snapshot leaves them (that
        snapshot was saved with no fill on record). Removing the phone deletes its folder."""
        for name in ('masks.json', 'masks.json.tmp'):
            try:
                (self.folder.run / name).unlink()
            except OSError:
                pass

    def new_session(self):
        """Every start begins a new phone session: a new id in ``run/session``. Every ``MaskSet`` of this phone compares it, and a process
        that kept sets for the last session (code apps, watches, fill records) forgets them (REVIEW-FIX-PHONE-121 R6-3)."""
        path = self.folder.run / 'session'
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name('session.tmp')
        tmp.write_text(os.urandom(16).hex(), encoding='utf-8')
        os.replace(tmp, path)

    # -- the ANR check --
    def anr_check(self, adb=None):
        """'clear', 'closed' (an ANR window was closed) or 'stuck'. Tree first; when the tree has no "Close app", the app is
        crashed from the shell (a screenshot cannot name a button); a recurring ANR is the caller's cold boot (§2.4)."""
        adb = adb or self.adb()
        with adb.session() as s:
            focus, pkg = parse_focus(s.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow'", timeout=20).stdout)
            if pkg is None:
                return 'clear'
            for attempt in range(2):
                xml = s.exec_out(['uiautomator', 'dump', '/dev/tty'], timeout=30).decode('utf-8', 'replace')
                spot = find_close_button(xml)
                if spot:
                    s.shell(['input', 'tap', str(spot[0]), str(spot[1])], timeout=10)
                elif pkg:
                    s.shell(['am', 'crash', pkg], timeout=10)
                time.sleep(1.0)
                focus, pkg_now = parse_focus(s.shell("dumpsys window | grep -E 'mCurrentFocus|mFocusedWindow'", timeout=20).stdout)
                if pkg_now is None:
                    return 'closed'
                pkg = pkg_now or pkg
            return 'stuck'

    # -- first start --
    def first_start(self, adb=None):
        """Once per phone (§2.4): stylus handwriting off (S5), the helper installed from its pinned file and enabled as the
        autofill service and notification listener (S8, S13), the phone's keyboard (fu2: re-checked every start), screen-off timeout at its maximum, Show passwords off, and no
        crash dialogs. Steps already done are kept in ``run/first-start.json``; a step that fails is retried at the next start.
        Every start, after the steps, a system dialog still showing is closed and the start waits until none is (EMU-JOIN fu2,
        A4): the result's ``dialogs`` is 'none' | 'closed' | 'left'."""
        marker = self.folder.run / FIRST_START
        kept = read_json(marker) or {}
        done = set(kept.get('done', []))
        on_phone = kept.get('helper_sha256')
        if self.helper_sha256 and on_phone and on_phone != self.helper_sha256.lower():
            done.discard('helper')  # a Beacon update pinned a new helper: it installs over the old one
        adb = adb or self.adb()
        # First: no crash or not-responding dialog may reach the owner's live view or the agent (Root, fu2 A4). Without
        # Bluetooth emulation the phone's Bluetooth stack has nothing to talk to (AOSP goldfish init.ranchu.rc: the HCI forwarder
        # needs the emulator's Bluetooth port) and aborts at a fresh phone's first cold boot, which raised "Bluetooth keeps
        # stopping" about 40 s after the phone was on (fu1 live). No AVD property, emulator flag or boot property switches the
        # stack off before the first boot (AOSP BluetoothManagerService: it starts unless the stored setting says off), so this
        # is the first thing done. Settings.Global hide_error_dialogs is observed by ActivityTaskManagerService and applies at
        # once: dialogs are not shown, their default action is taken.
        steps = [('error_dialogs_off', ['settings', 'put', 'global', 'hide_error_dialogs', '1']),
                 ('stylus', ['settings', 'put', 'secure', 'stylus_handwriting_enabled', '0']),
                 ('screen_timeout', ['settings', 'put', 'system', 'screen_off_timeout', '2147483647']),
                 # Show passwords off: no last-character flash while the agent types into a password field (fu1).
                 ('show_password_off', ['settings', 'put', 'system', 'show_password', '0']),
                 # No Bluetooth emulation (NO_NETSIM): with Bluetooth on, the stack aborted at every cold boot ("Can't start
                 # stack ... starting HciHal", 5 crashes in 20 s), so it stays off (L1b, 17 Nov).
                 ('bluetooth_off', 'cmd bluetooth_manager disable; settings put global bluetooth_on 0'),
                 ('helper', None),
                 ('autofill', ['settings', 'put', 'secure', 'autofill_service', AUTOFILL_SERVICE]),
                 # The helper is the phone's keyboard (Root after L5-fu1): Gboard covered the lower part of the owner's picture
                 # though the phone has a hardware keyboard, and again after any restart of it. The helper's input method has an
                 # empty window, so nothing covers the app, while Android still reports a served field (§11.2). Android's own
                 # commands; without the helper Android falls back to its own keyboard (measured).
                 ('keyboard', f'ime enable {INPUT_METHOD} && ime set {INPUT_METHOD}'),
                 ('code_listener', ['cmd', 'notification', 'allow_listener', CODE_LISTENER])]
        result = {}
        with adb.session() as s:
            for name, args in steps:
                if name == 'keyboard' and name in done:
                    # Every start, as autofill: put back if something else became the phone's keyboard.
                    current = (s.shell(['settings', 'get', 'secure', 'default_input_method'], timeout=30).stdout or '').strip()
                    if current == INPUT_METHOD:
                        result[name] = 'done'
                        continue
                    done.discard(name)
                if name == 'autofill' and name in done:
                    # Every start (Root, 17 Nov): the owner may have picked another autofill service; Turn on makes the
                    # helper Android's autofill service again (the phone page reads 'off' meanwhile).
                    current = (s.shell(['settings', 'get', 'secure', 'autofill_service'], timeout=30).stdout or '').strip()
                    if current.split('/', 1)[0] == HELPER and current.endswith('.HelperAutofillService'):
                        result[name] = 'done'
                        continue
                    done.discard(name)
                if name in done:
                    result[name] = 'done'
                    continue
                if name == 'helper':
                    ok = self._install_helper(s)
                    if ok:
                        on_phone = self.helper_sha256.lower()
                elif name in ('autofill', 'code_listener', 'keyboard') and 'helper' not in done:
                    result[name] = 'waits-for-helper'
                    continue
                else:
                    ok = s.shell(args, timeout=30).returncode == 0
                result[name] = 'ok' if ok else 'failed'
                if ok:
                    done.add(name)
            if 'ok' in result.values():
                # Settings reach the disk a moment later; a cold boot reads the disk, not Quick Boot's RAM, so a stop soon
                # after first start lost them (measured: bluetooth_on back to 1) unless synced.
                s.shell('sleep 3; sync', timeout=30)
            result['dialogs'] = self.close_dialogs(s)
        write_json(marker, {'done': sorted(done), 'helper_sha256': on_phone})
        return result

    def close_dialogs(self, session, tries=3, gap=1.0):
        """A system dialog left from before ``hide_error_dialogs`` took effect (a crash raised during the boot) is closed:
        AOSP's BaseErrorDialog closes on ACTION_CLOSE_SYSTEM_DIALOGS. Then the focus is read until no such dialog has it.
        'none' (there was none), 'closed', or 'left' (still there after ``tries``)."""
        def showing():
            focus = session.shell("dumpsys window | grep -m 1 -E 'mCurrentFocus='", timeout=20).stdout or ''
            return any(word in focus for word in DIALOG_WINDOWS)
        if not showing():
            return 'none'
        for _ in range(tries):
            session.shell(['am', 'broadcast', '-a', 'android.intent.action.CLOSE_SYSTEM_DIALOGS'], timeout=20)
            time.sleep(gap)
            if not showing():
                return 'closed'
        return 'left'

    def _install_helper(self, session):
        if not self.helper_sha256:
            return False  # this Beacon pins no helper
        if not self.helper_apk and self.helper_fetch is not None:
            try:
                self.helper_apk = self.helper_fetch()
            except Exception:  # health: allow BLE001 -- offline: the phone starts without it; the next start tries again
                return False
        if not self.helper_apk:
            return False
        apk = Path(self.helper_apk)
        digest = hashlib.sha256()
        with apk.open('rb') as fh:
            for chunk in iter(lambda: fh.read(1 << 20), b''):
                digest.update(chunk)
        if digest.hexdigest() != self.helper_sha256.lower():
            raise PhoneError("The phone's helper file is damaged.")
        return session.install(apk).returncode == 0

    # -- adopt, start, stop --
    def adopt(self, check_anr=True):
        """The running phone's state, or None. After adopt: the ANR check and the frame-file cleanup (§2.4, §4.5)."""
        state = self.state()
        if not self.running(state):
            return None
        try:
            st = self.status(state)
        except Exception:  # health: allow BLE001 -- not answering = not adoptable
            return None
        if not st.get('booted'):
            return None
        self.cleanup_frames()
        if check_anr:
            state['anr'] = self._anr_or_none(state)
        conf = read_json(self.folder.keeper_conf) or {}
        if not alive(conf.get('pid'), conf.get('create_time')):  # a Beacon process died before it spawned the keeper
            try:
                self.install_keeper()
                state['keeper'] = self._spawn_keeper()
            except Exception as e:  # health: allow BLE001 -- the phone still works; the next adopt tries again
                state['keeper'] = f'unspawned:{type(e).__name__}'
        return state

    def _anr_or_none(self, state):
        try:
            return self.anr_check(self.adb(state))
        except Exception as e:  # health: allow BLE001 -- a phone whose adb fails is reported, not crashed on
            return f'unchecked:{type(e).__name__}'

    def _ports(self, state):
        ports = (state or {}).get('ports')
        if ports and ports_free(ports):
            return ports
        if self.pick_ports is None:
            raise PhoneError("The phone's ports are in use.")
        ports = dict(self.pick_ports(self.folder.root))
        if not ports_free(ports):
            raise PhoneError("The phone's ports are in use.")
        return ports

    def no_adb_in_sdk(self):
        """The SDK keeps an empty ``platform-tools/``: the emulator refuses an SDK root without it ("Broken AVD system path"),
        and with an adb binary in it, it starts an adb server of its own (B1, L1b): one that scans 5555-5585 and carries the
        developer's key. An adb left by an older install is renamed aside."""
        tools = self.folder.sdk / 'platform-tools'
        tools.mkdir(parents=True, exist_ok=True)
        moved = []
        for name in ('adb.exe', 'adb'):
            binary = tools / name
            if binary.exists():
                binary.replace(tools / (name + '.disabled'))
                moved.append(name)
        return moved

    def install_keeper(self):
        self.folder.root.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(Path(__file__).resolve().parent / 'agent_phone_keeper.py', self.folder.keeper_py)

    @contextlib.contextmanager
    def lock(self, seconds=BOOT_SECONDS + 2 * STOP_SECONDS):
        """One start or stop at a time across every Beacon process and the keeper (``run/host.lock``, an OS lock the system
        releases when its holder dies)."""
        self.folder.run.mkdir(parents=True, exist_ok=True)
        fh = open(self.folder.run / 'host.lock', 'a+b')
        deadline = time.monotonic() + seconds
        try:
            while True:
                try:
                    if os.name == 'nt':
                        import msvcrt

                        fh.seek(0)
                        msvcrt.locking(fh.fileno(), msvcrt.LK_NBLCK, 1)
                    else:
                        import fcntl

                        fcntl.flock(fh.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise PhoneError('The phone is busy. Try again in a minute.') from None
                    time.sleep(0.2)
            yield
        finally:
            fh.close()  # closing the handle releases the lock on both systems

    def start(self, cold=False, gpu='auto'):
        """Starts the phone (or adopts it) and returns its state. Raises PhoneError with a plain reason."""
        with self.lock():
            return self._start(cold, gpu, False)

    def _start(self, cold, gpu, _retried):
        adopted = self.adopt()
        if adopted:
            return adopted
        if not (self.folder.avd_dir / 'config.ini').exists():
            raise PhoneError('The phone is not set up yet.')
        adbm = _sibling('agent_phone_adb')
        adbm.generate_phone_key(self.folder.home)  # never start without the phone's own key (S2); no-op when present
        for d in (self.folder.run, self.folder.tmp):
            d.mkdir(parents=True, exist_ok=True)
        previous = self.state() or {}
        if self.running(previous):
            # Alive but not adoptable: still booting, or gRPC slow. Never kill a live phone for that: wait the boot window,
            # then adopt; only a phone that stays silent all that time is replaced.
            try:
                self._wait_booted(previous, time.time())
            except PhoneError:
                pass
            adopted = self.adopt()
            if adopted:
                return adopted
            self._end(previous, save=False)
        elif previous.get('running'):  # it died: whatever it left behind goes now
            self._end(previous, save=False)
        if cold or not (self.folder.avd_dir / 'snapshots' / 'default_boot').is_dir():
            self.cleanup_masks()  # a cold boot brings back no screen: the masks that hid one are stale
        self.new_session()
        self.cleanup_frames(force=True)
        self.cleanup_discovery()
        self.no_adb_in_sdk()
        self.install_keeper()
        ports = self._ports(previous)
        env = phone_env(self.folder)
        if self.python is None and keeper_python(env) is None:
            raise PhoneError(NO_KEEPER)  # before the emulator exists: nothing could look after it
        psutil = _psutil()
        args = launch_args(self.folder, ports, ram_mb=guest_ram_mb(psutil.virtual_memory().total), gpu=gpu, cold=cold,
                           headless=headless_linux())
        log = self.folder.run / f'emulator-{time.strftime("%Y%m%dT%H%M%S")}.log'
        self._rotate_logs()
        t0 = time.time()
        spawned = self.spawner.spawn('emulator', args, env, log=log, cwd=self.folder.run)
        state = {'running': True, 'pid': spawned['pid'], 'create_time': spawned.get('create_time'), 'ports': ports,
                 'log': str(log), 'gpu': gpu, 'cold': cold, 'spawn': spawned.get('via'), 'started_at': time.strftime('%Y-%m-%dT%H:%M:%S'),
                 'started_at_epoch': t0}
        write_json(self.folder.state, state)
        try:
            self._wait_booted(state, t0)
        except PhoneError:
            self._end(state, save=False)
            self._mark_stopped(state)
            if gpu == 'auto' and not _retried:  # §2.4: retry once with the software renderer
                return self._start(cold, 'software', True)
            raise
        state['boot_seconds'] = round(time.time() - t0, 1)
        write_json(self.folder.state, state)
        # adb only now: contacting adbd during boot left the phone key refused (L1b, 17 Nov)
        if not self._adb_ready(state):
            self._end(state, save=False)
            self._mark_stopped(state)
            if not _retried:
                return self._start(True, gpu, True)
            raise PhoneError("The phone didn't start.")
        anr = self._anr_or_none(state)
        if anr == 'stuck':
            self._end(state, save=False)
            self._mark_stopped(state)
            self._drop_quick_boot()
            if not _retried:
                return self._start(True, gpu, True)
            raise PhoneError("The phone didn't start.")
        state['anr'] = anr
        state['first_start'] = self.first_start(self.adb(state))
        state['keeper'] = self._spawn_keeper()
        write_json(self.folder.state, state)
        self.write_heartbeat()
        self.note_activity()
        return state

    def _wait_booted(self, state, t0):
        psutil = _psutil()
        deadline = time.monotonic() + BOOT_SECONDS
        while time.monotonic() < deadline:
            if not alive(state['pid'], state.get('create_time')):
                raise PhoneError("The phone didn't start.")
            if not state.get('qemu_pid'):
                try:
                    kids = [c for c in psutil.Process(state['pid']).children(recursive=True) if c.name().lower().startswith('qemu')]
                    if kids:
                        state['qemu_pid'], state['qemu_create_time'] = kids[0].pid, kids[0].create_time()
                except Exception:  # health: allow BLE001 -- the emulator may be between processes
                    pass
            if not state.get('discovery'):
                path = self._find_discovery(state)
                if path:
                    state['discovery'] = str(path)
                    write_json(self.folder.state, state)
            if state.get('discovery'):
                try:
                    if self.status(state, timeout=3.0).get('booted'):
                        return
                except Exception:  # health: allow BLE001 -- gRPC not up yet
                    pass
            time.sleep(1.0)
        raise PhoneError("The phone didn't start.")

    def _find_discovery(self, state):
        """``pid_<qemu pid>.ini`` in the discovery folders; the log's "Advertising in" line only as a fallback, because the
        emulator's redirected output is buffered and a short (cold or Quick Boot) start can hold that line for minutes (L1b B1)."""
        if state.get('qemu_pid'):
            for d in discovery_dirs(self.folder):
                candidate = d / f"pid_{state['qemu_pid']}.ini"
                if candidate.exists() and is_ours(candidate, self.folder):
                    return candidate
        path = discovery_from_log(state.get('log', ''))
        return path if path and path.exists() else None

    def _adb_ready(self, state, seconds=60.0):
        adbm = _sibling('agent_phone_adb')
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                if self.adb(state).shell(['getprop', 'sys.boot_completed'], timeout=10).stdout.strip() == '1':
                    return True
            except adbm.KeyRefused:
                return False
            except adbm.AdbError:
                pass
            time.sleep(1.0)
        return False

    def _spawn_keeper(self, python=None, replace=False):
        conf = read_json(self.folder.keeper_conf) or {}
        if not replace and alive(conf.get('pid'), conf.get('create_time')):
            return {'pid': conf['pid'], 'reused': True}
        conf = {'plugin_dir': str(self.plugin_dir), 'idle_minutes': self.idle_minutes, 'heartbeat_hours': self.heartbeat_hours}
        write_json(self.folder.keeper_conf, conf)
        env = phone_env(self.folder)  # allow-listed: no HERMES_*, no credentials (S7 rule 1)
        python = python or self.python or keeper_python(env)  # the keeper needs psutil under -I (keeper_python)
        if python is None:
            raise PhoneError(NO_KEEPER)
        res = self.spawner.spawn('keeper', [python, '-I', str(self.folder.keeper_py), 'watch', str(self.folder.root)], env,
                                 log=None, cwd=self.folder.root)
        conf.update(pid=res['pid'], create_time=res.get('create_time'), via=res.get('via'), python=str(python))
        write_json(self.folder.keeper_conf, conf)
        return {'pid': res['pid'], 'via': res.get('via'), 'in_job_after_wmi': res.get('in_job_after_wmi')}

    def follow_runtime(self):
        """After Beacon loads (EMU-JOIN fu1): a keeper still running on a Hermes runtime that Hermes has replaced since (an update
        staged a new runtime generation and recorded it) is started again on the current runtime and the old one ended; the phone
        keeps running. Hermes' record decides, never this process's own interpreter, so the dashboard and the gateway can't pull
        the keeper back and forth. 'no keeper', 'current', 'moved', or 'kept' (the current runtime can't run the keeper: the
        old one stays, holding Hermes' lease on its runtime)."""
        keeper = _sibling('agent_phone_keeper')
        conf = read_json(self.folder.keeper_conf) or {}
        pid, born = conf.get('pid'), conf.get('create_time')
        if not alive(pid, born):
            return 'no keeper'
        python = conf.get('python') or _exe(pid)  # keepers started before fu1 have no record of it
        current = keeper.superseded(python) if python else None
        if current is None:
            return 'current'
        with self.lock():
            conf = read_json(self.folder.keeper_conf) or {}
            if conf.get('pid') != pid or not alive(pid, born):
                return 'current'  # moved or stopped meanwhile
            if keeper_python(phone_env(self.folder), candidates=[str(current)]) is None:
                return 'kept'
            self.install_keeper()
            try:
                self._spawn_keeper(python=str(current), replace=True)
            except Exception:  # health: allow BLE001 -- the old keeper keeps watching; the next load tries again
                write_json(self.folder.keeper_conf, conf)  # it stays the recorded keeper, so no adopt starts a second one
                return 'kept'
            end_tree(pid, born)  # a keeper from before fu1 doesn't step aside by itself
        return 'moved'

    def _rotate_logs(self, keep=5):
        logs = sorted(self.folder.run.glob('emulator-*.log'))
        for old in logs[:-keep] if len(logs) > keep else []:
            try:
                old.unlink()
            except OSError:
                pass

    def fill_on_record(self):
        """True while any fill is on record: the kept masks file exists, whatever it holds (a record that is empty, cut or unreadable is
        still a fill the stop must not forget), or its temporary file does."""
        return any((self.folder.run / name).exists() for name in ('masks.json', 'masks.json.tmp'))

    def _drop_quick_boot(self):
        """Deletes the Quick Boot snapshot and, with it, the masks that were hiding what its screen shows."""
        snap = self.folder.avd_dir / 'snapshots' / 'default_boot'
        if snap.is_dir():
            shutil.rmtree(snap, ignore_errors=True)
        self.cleanup_masks()

    def _end(self, state, save):
        """Stops the emulator: gRPC SHUTDOWN when ``save`` (Quick Boot saved), else a hard kill; then the leftovers."""
        if alive(state.get('pid'), state.get('create_time')):
            if save:
                try:
                    with self.grpc(state, timeout=10.0) as g:
                        g.shutdown()
                except Exception:  # health: allow BLE001 -- fall through to the hard kill
                    pass
                deadline = time.monotonic() + STOP_SECONDS
                while time.monotonic() < deadline and alive(state.get('pid'), state.get('create_time')):
                    time.sleep(0.5)
            end_tree(state.get('pid'), state.get('create_time'))
            end_tree(state.get('qemu_pid'), state.get('qemu_create_time'))
        self.cleanup_discovery()  # also the recorded file, once it is ours and its QEMU is gone (never another emulator's)
        self.cleanup_frames(force=True)

    def _mark_stopped(self, state):
        state['running'] = False
        write_json(self.folder.state, state)

    def stop(self, reason='requested'):
        """A clean stop (§7.1): the ANR check first; a window that can't be closed, or a check that could not run because adb
        did not answer, means no Quick Boot save and its image deleted, so the next start cold-boots (21 s) instead of
        restoring a stuck or stalled phone (S4)."""
        with self.lock():
            return self._stop(reason)

    def _stop(self, reason):
        state = self.state()
        if not state:
            return {'stopped': False, 'why': 'not set up'}
        if not self.running(state):
            self._end(state, save=False)
            state.update(running=False)
            write_json(self.folder.state, state)
            return {'stopped': False, 'why': 'not running'}
        anr = self._anr_or_none(state)
        # The check ran and found nothing stuck, and no fill is on record: a snapshot saved from a session with a fill could bring a filled
        # screen back that nothing masks. 'stuck' and any 'unchecked:*' (adb not answering) save nothing either.
        filled = self.fill_on_record()
        save = anr in ('clear', 'closed') and not filled
        try:  # flush the guest's writes: the next start may be a cold boot, which reads the disk
            self.adb(state).shell('sync', timeout=20)
        except Exception:  # health: allow BLE001 -- a phone that can't sync still stops
            pass
        if save and self.fill_on_record():  # a fill landed while the stop was deciding or syncing
            save, filled = False, True
        t0 = time.time()
        self._end(state, save=save)
        if save and self.fill_on_record():  # ... or while the snapshot was being written: it may hold the filled screen
            save, filled = False, True
        if not save:
            self._drop_quick_boot()
        state.update(running=False, stopped_at=time.strftime('%Y-%m-%dT%H:%M:%S'),
                     stop={'reason': reason, 'anr': anr, 'saved': save, 'filled': filled, 'seconds': round(time.time() - t0, 1)})
        write_json(self.folder.state, state)
        return {'stopped': True, **state['stop']}
