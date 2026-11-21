"""The agent phone's host runtime (agent_phone_host.py) without an emulator: the child environment, the launch line, the
discovery and frame-file cleanup, the ANR check on fake dumpsys output and trees, the first-start settings, the Quick Boot rule
on stop, the port re-check, and the detached spawn's job check with its WMI fallback.
"""
import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

import psutil
import pytest

PLUGIN = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('beacon_agent_phone_host_under_test', PLUGIN / 'agent_phone_host.py')
host = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(host)

ANR_FOCUS = '  mCurrentFocus=Window{1a2b u0 Application Not Responding: com.android.systemui}\n'
OK_FOCUS = '  mCurrentFocus=Window{3c4d u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}\n'
TREE = ('<?xml version="1.0"?><hierarchy rotation="0"><node index="0" text="" resource-id="android:id/aerr_wait" '
        'bounds="[100,1500][980,1640]" /><node index="1" text="Close app" resource-id="android:id/aerr_close" '
        'bounds="[100,1300][980,1440]" /></hierarchy>UI hierchary dumped to: /dev/tty')


class Result:
    def __init__(self, stdout='', returncode=0):
        self.stdout, self.returncode = stdout, returncode


class FakeSession:
    def __init__(self, focus_seq, tree=TREE, fail=()):
        self.focus_seq, self.tree, self.fail = list(focus_seq), tree, set(fail)
        self.calls, self.installed = [], []

    def shell(self, args, timeout=60, **_):
        self.calls.append(args if isinstance(args, str) else tuple(args))
        if isinstance(args, str) and 'dumpsys window' in args:
            return Result(self.focus_seq.pop(0) if len(self.focus_seq) > 1 else self.focus_seq[0])
        return Result(returncode=1 if (tuple(args)[:3] in self.fail) else 0)

    def exec_out(self, args, timeout=60):
        self.calls.append(tuple(args))
        return self.tree.encode()

    def install(self, apk):
        self.installed.append(Path(apk).name)
        return Result('Success', 0)


class FakeAdb:
    def __init__(self, session):
        self.s = session

    def session(self):
        import contextlib
        return contextlib.nullcontext(self.s)


@pytest.fixture
def folder(tmp_path):
    f = host.Folder(tmp_path / 'phone')
    for d in (f.run, f.tmp, f.avd_dir, f.discovery_dir):
        d.mkdir(parents=True)
    return f


def test_phone_env_is_allow_listed_and_points_into_the_folder(folder):
    base = {'SYSTEMROOT': r'C:\Windows', 'USERNAME': 'u', 'HERMES_HOME': r'C:\h', 'HERMES_PROFILE': 'p',
            'OPENAI_API_KEY': 'sk-test', 'LOCALAPPDATA': r'C:\Users\u\AppData\Local', 'ANDROID_HOME': r'C:\dev\Sdk',
            'PATH': r'C:\dev\Sdk\platform-tools;C:\Windows', 'DISPLAY': ':0'}
    env = host.phone_env(folder, base=base, os_name='nt')
    assert not [k for k in env if k.startswith('HERMES')] and 'OPENAI_API_KEY' not in env and 'LOCALAPPDATA' not in env
    assert env['ANDROID_HOME'] == str(folder.sdk) and env['USERPROFILE'] == env['HOME'] == str(folder.home)
    assert env['ANDROID_AVD_HOME'] == str(folder.avd_home) and env['TEMP'] == str(folder.tmp)
    assert env['ANDROID_ADB_SERVER_PORT'] == '1' and 'platform-tools' not in env['PATH']
    posix = host.phone_env(folder, base={**base, 'PATH': '/usr/bin', 'XDG_RUNTIME_DIR': '/run/user/1'}, os_name='posix')
    assert posix['TMPDIR'] == str(folder.tmp) and posix['DISPLAY'] == ':0' and 'HERMES_HOME' not in posix


def test_launch_line_follows_the_design(folder):
    args = host.launch_args(folder, {'console': 5640, 'grpc': 8600}, ram_mb=4096, os_name='nt')
    joined = ' '.join(args)
    assert args[0].endswith('emulator.exe') and '-avd beacon_agent_phone' in joined and '-port 5640' in joined
    assert '-feature -WiFiPacketStream,-Uwb,-BluetoothEmulation' in joined  # no netsimd at all (B2, Root msg_cd44774222de)
    assert '-qt-hide-window' in args and '-no-window' not in args
    assert '-grpc 8600 -grpc-use-token' in joined and '-no-metrics' in args and '-crash-report-mode never' in joined
    assert '-memory 4096' in joined and '-gpu auto' in joined and '-no-snapshot-load' not in args
    headless = host.launch_args(folder, {'console': 5640, 'grpc': 8600}, headless=True, cold=True, gpu='software', os_name='posix')
    assert '-no-window' in headless and '-qt-hide-window' not in headless and '-no-snapshot-load' in headless
    assert headless[0].endswith('/emulator') or headless[0].endswith('\\emulator')
    assert host.guest_ram_mb(15 * 2**30) == 3072 and host.guest_ram_mb(16 * 2**30) == 4096
    assert host.headless_linux({}, 'linux') and not host.headless_linux({'DISPLAY': ':0'}, 'linux') and not host.headless_linux({}, 'win32')


def test_discovery_path_comes_from_the_emulators_own_log(folder):
    log = folder.run / 'emulator-1.log'
    ini = folder.discovery_dir / 'pid_4242.ini'
    log.write_text(f'INFO | Something\nINFO         | Advertising in: {ini}\nINFO | more\n', encoding='utf-8')
    assert host.discovery_from_log(log) == ini
    assert host.discovery_from_log(folder.run / 'missing.log') is None


def test_the_discovery_file_is_found_by_qemu_pid_before_the_log_says_so(folder):
    ini = folder.discovery_dir / 'pid_4321.ini'
    ini.write_text(f'avd.dir={folder.avd_dir}\ngrpc.port=9\n')
    log = folder.run / 'emulator-1.log'
    log.write_text('INFO | still buffered, no Advertising line yet\n', encoding='utf-8')
    h = host.PhoneHost(folder)
    assert h._find_discovery({'qemu_pid': 4321, 'log': str(log)}) == ini
    assert h._find_discovery({'qemu_pid': 9999, 'log': str(log)}) is None


def test_cleanup_deletes_only_this_phones_dead_discovery_files(folder, tmp_path):
    dead = folder.discovery_dir / 'pid_999999.ini'
    dead.write_text(f'avd.dir={folder.avd_dir}\ngrpc.port=1\n')
    (folder.discovery_dir / '999999').mkdir()
    live = folder.discovery_dir / f'pid_{os.getpid()}.ini'
    live.write_text(f'avd.dir={folder.avd_dir}\n')
    other = folder.discovery_dir / 'pid_999998.ini'
    other.write_text(f'avd.dir={tmp_path / "someone-else.avd"}\n')
    assert host.PhoneHost(folder).cleanup_discovery() == 1
    assert not dead.exists() and not (folder.discovery_dir / '999999').exists() and live.exists() and other.exists()


def test_frame_files_go_on_start_and_on_adopt_only_without_a_view(folder):
    paths = [folder.run / 'frames-5641.bin', folder.frames / 'frames-5640-0123abcd.bin']
    other = folder.root.parent / 'another-phone' / 'tmp' / 'AndroidEmulator' / paths[0].name
    other.parent.mkdir(parents=True)
    other.touch()
    for path in paths:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'frame')
        (path.parent / 'keep.bin').touch()
    assert host.PhoneHost(folder, view_open=lambda: True).cleanup_frames() == 0 and all(p.exists() for p in paths)
    if os.name != 'nt':
        assert host.PhoneHost(folder).cleanup_frames() == 0 and all(p.exists() for p in paths)
    assert host.PhoneHost(folder, view_open=lambda: False).cleanup_frames() == 2 and not any(p.exists() for p in paths)
    for path in paths:
        path.touch()
    assert host.PhoneHost(folder, view_open=lambda: True).cleanup_frames(force=True) == 2
    assert other.exists() and all((p.parent / 'keep.bin').exists() for p in paths)


def test_parse_focus_and_the_close_button():
    assert host.parse_focus(OK_FOCUS) == (OK_FOCUS.strip(), None)
    assert host.parse_focus(ANR_FOCUS)[1] == 'com.android.systemui'
    assert host.find_close_button(TREE) == (540, 1370)
    assert host.find_close_button('<hierarchy></hierarchy>') is None
    assert host.find_close_button('<node text="Close app" bounds="[0,0][10,20]" />') == (5, 10)


def test_anr_check_clear_closed_by_tree_by_crash_and_stuck(folder):
    h = host.PhoneHost(folder)
    s = FakeSession([OK_FOCUS])
    assert h.anr_check(FakeAdb(s)) == 'clear' and len(s.calls) == 1
    s = FakeSession([ANR_FOCUS, OK_FOCUS])
    assert h.anr_check(FakeAdb(s)) == 'closed' and ('input', 'tap', '540', '1370') in s.calls
    s = FakeSession([ANR_FOCUS, OK_FOCUS], tree='<hierarchy/>')
    assert h.anr_check(FakeAdb(s)) == 'closed' and ('am', 'crash', 'com.android.systemui') in s.calls
    s = FakeSession([ANR_FOCUS, ANR_FOCUS, ANR_FOCUS])
    assert h.anr_check(FakeAdb(s)) == 'stuck'
    assert ('uiautomator', 'dump', '/dev/tty') in s.calls and not any('sdcard' in str(c) for c in s.calls)  # S6/S12


def test_stop_with_a_stuck_anr_saves_no_quick_boot(folder, monkeypatch):
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    (snap / 'ram.bin').write_bytes(b'x')
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        h = host.PhoneHost(folder)
        ended = []
        monkeypatch.setattr(h, '_anr_or_none', lambda state: 'stuck')
        monkeypatch.setattr(h, '_end', lambda state, save: ended.append(save))
        r = h.stop('test')
        assert r['saved'] is False and ended == [False] and not snap.exists()
        assert host.read_json(folder.state)['running'] is False
        snap.mkdir(parents=True)
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        monkeypatch.setattr(h, '_anr_or_none', lambda state: 'closed')
        assert h.stop('test')['saved'] is True and ended[-1] is True and snap.exists()
    finally:
        p.kill()


def test_first_start_runs_once_pins_the_helper_and_retries_failures(folder, tmp_path):
    apk = tmp_path / 'helper.apk'
    apk.write_bytes(b'helper-bytes')
    import hashlib
    pin = hashlib.sha256(b'helper-bytes').hexdigest()
    s = FakeSession([OK_FOCUS], fail={('cmd', 'notification', 'allow_listener')})
    r = host.PhoneHost(folder, helper_apk=apk, helper_sha256=pin).first_start(FakeAdb(s))
    assert r == {'error_dialogs_off': 'ok', 'stylus': 'ok', 'screen_timeout': 'ok', 'show_password_off': 'ok', 'bluetooth_off': 'ok',
                 'helper': 'ok', 'autofill': 'ok', 'code_listener': 'failed', 'keyboard': 'ok', 'dialogs': 'none'}
    assert 'cmd bluetooth_manager disable; settings put global bluetooth_on 0' in s.calls
    assert s.calls[-2] == 'sleep 3; sync'  # flushed before any stop can follow; then the dialog check (fu2 A4)
    assert ('settings', 'put', 'secure', 'stylus_handwriting_enabled', '0') in s.calls and s.installed == ['helper.apk']
    assert ('settings', 'put', 'secure', 'autofill_service', 'dev.hermes.agentphone.helper/.HelperAutofillService') in s.calls
    assert ('cmd', 'notification', 'allow_listener', 'dev.hermes.agentphone.helper/dev.hermes.agentphone.helper.CodeListener') in s.calls
    s2 = FakeSession([OK_FOCUS])
    r2 = host.PhoneHost(folder, helper_apk=apk, helper_sha256=pin).first_start(FakeAdb(s2))
    assert r2['code_listener'] == 'ok' and r2['helper'] == 'done' and s2.installed == []
    with pytest.raises(host.PhoneError):
        host.PhoneHost(host.Folder(tmp_path / 'p2'), helper_apk=apk, helper_sha256='0' * 64).first_start(FakeAdb(FakeSession([OK_FOCUS])))
    (tmp_path / 'p3' / 'run').mkdir(parents=True)
    r3 = host.PhoneHost(host.Folder(tmp_path / 'p3')).first_start(FakeAdb(FakeSession([OK_FOCUS])))
    assert r3['helper'] == 'failed' and r3['autofill'] == 'waits-for-helper'


def test_recorded_ports_are_rechecked_and_l1as_pick_is_used_when_taken(folder):
    import socket
    held = socket.socket()
    held.bind(('127.0.0.1', 0))
    held.listen(1)
    taken = held.getsockname()[1]
    try:
        # a free even/odd pair found now (another emulator on this computer may hold any fixed one)
        console = next(p for p in range(45000, 46000, 2) if host.port_free(p) and host.port_free(p + 1))
        free = {'console': console, 'grpc': 0}
        s = socket.socket(); s.bind(('127.0.0.1', 0)); free['grpc'] = s.getsockname()[1]; s.close()
        assert host.PhoneHost(folder)._ports({'ports': free}) == free
        picked = []
        h = host.PhoneHost(folder, pick_ports=lambda root: picked.append(root) or free)
        assert h._ports({'ports': {'console': console, 'grpc': taken}}) == free and picked == [folder.root]
        with pytest.raises(host.PhoneError):
            host.PhoneHost(folder)._ports({'ports': {'console': console, 'grpc': taken}})
    finally:
        held.close()


def test_spawner_falls_back_to_wmi_when_a_job_holds_the_child(folder):
    host.PhoneHost(folder).install_keeper()
    calls = []

    def fake_wmi(spec, env):
        calls.append(json.loads(Path(spec).read_text())['cmd'])
        out = json.loads(Path(spec).read_text())['out']
        p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(30)'])
        Path(out).write_text(json.dumps({'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time(), 'mode': 'breakaway'}))

    jobs = iter([True, False])
    sp = host.Spawner(folder, wmi=fake_wmi, check_job=lambda pid: next(jobs))
    env = {k: os.environ[k] for k in ('SYSTEMROOT', 'PATH', 'TEMP') if k in os.environ}
    res = sp.spawn('dummy', [sys.executable, '-I', '-c', 'import time; time.sleep(30)'], env)
    try:
        assert res['via'] == 'wmi' and res['in_job_after_wmi'] is False and len(calls) == 1
        assert not list(folder.run.glob('spawn-*'))  # the spec (environment) is gone at once
    finally:
        psutil.Process(res['pid']).kill()


def test_the_keeper_is_spawned_without_hermes_variables(folder, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(folder.root / 'hermes-home'))
    seen = {}

    class Recorder:
        def spawn(self, name, cmd, env, log=None, cwd=None):
            seen.update(name=name, cmd=cmd, env=env, cwd=cwd)
            return {'pid': os.getpid(), 'create_time': psutil.Process().create_time(), 'via': 'intermediate'}

    h = host.PhoneHost(folder, spawner=Recorder(), plugin_dir=folder.root / 'plugin')
    h.install_keeper()
    h._spawn_keeper()
    assert not [k for k in seen['env'] if k.upper().startswith('HERMES')]
    assert seen['cmd'][-2:] == ['watch', str(folder.root)] and 'hermes-home' not in ' '.join(seen['cmd']) and seen['cwd'] == folder.root
    conf = host.read_json(folder.keeper_conf)
    assert conf['plugin_dir'] == str(folder.root / 'plugin') and conf['idle_minutes'] == 30 and conf['heartbeat_hours'] == 24
    assert (folder.root / 'keeper.py').read_bytes() == (PLUGIN / 'agent_phone_keeper.py').read_bytes()


def test_the_sdk_keeps_an_empty_platform_tools_with_no_adb(folder):
    h = host.PhoneHost(folder)
    assert h.no_adb_in_sdk() == [] and (folder.sdk / 'platform-tools').is_dir()
    (folder.sdk / 'platform-tools' / 'adb.exe').write_bytes(b'MZ')
    assert h.no_adb_in_sdk() == ['adb.exe']
    assert not (folder.sdk / 'platform-tools' / 'adb.exe').exists() and (folder.sdk / 'platform-tools' / 'adb.exe.disabled').exists()


def test_start_never_kills_a_live_phone_that_is_still_booting(folder, monkeypatch):
    (folder.avd_dir / 'config.ini').write_text('AvdId=beacon_agent_phone\n')
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        booting = {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time(), 'ports': {'console': 5640, 'grpc': 1}}
        host.write_json(folder.state, booting)
        h = host.PhoneHost(folder)
        answers = iter([None, {**booting, 'adopted': True}])  # not adoptable yet, then booted
        waited, ended = [], []
        monkeypatch.setattr(h, 'adopt', lambda check_anr=True: next(answers))
        monkeypatch.setattr(h, '_wait_booted', lambda state, t0: waited.append(state['pid']))
        monkeypatch.setattr(h, '_end', lambda state, save: ended.append(save))
        assert h.start()['adopted'] is True
        assert waited == [p.pid] and ended == [] and p.poll() is None
    finally:
        p.kill()


def test_one_start_or_stop_at_a_time(folder):
    import threading
    h = host.PhoneHost(folder)
    held, release = threading.Event(), threading.Event()

    def holder():
        with h.lock():
            held.set()
            release.wait(10)

    t = threading.Thread(target=holder)
    t.start()
    held.wait(10)
    try:
        with pytest.raises(host.PhoneError):
            with host.PhoneHost(folder).lock(seconds=0.5):
                pass
    finally:
        release.set()
        t.join(10)
    with host.PhoneHost(folder).lock(seconds=2):
        pass


def test_in_job_reads_this_process():
    if os.name != 'nt':
        assert host.in_job(os.getpid()) is None
    else:
        assert host.in_job(os.getpid()) in (True, False)


# ---- PHONEFIX-121 (REVIEW-PHONE-121): each test below failed on tree dbc7ba9c -----------------------------------------------

def test_p2_4_posix_discovery_dirs_cover_the_emulators_own_places(tmp_path):
    folder = host.Folder(tmp_path / 'phone')
    xdg = tmp_path / 'run-user-1000'
    env = host.phone_env(folder, base={'PATH': '/usr/bin', 'XDG_RUNTIME_DIR': str(xdg)}, os_name='posix')
    assert env['XDG_RUNTIME_DIR'] == str(xdg)  # the emulator child keeps it (POSIX_ENV_KEEP)
    dirs = host.discovery_dirs(folder, env=env, os_name='posix')
    # Google's emulator: Linux $XDG_RUNTIME_DIR/avd/running; macOS $HOME/Library/Caches/TemporaryItems/avd/running.
    assert xdg / 'avd' / 'running' in dirs
    assert folder.home / 'Library' / 'Caches' / 'TemporaryItems' / 'avd' / 'running' in dirs


def test_p2_4_discovery_dirs_are_the_list_googles_own_reader_scans(folder, tmp_path):
    """AOSP aemu-grpc emulator_discovery.py get_discovery_directories(): the OS place, then the user folders, each + avd/running."""
    xdg = tmp_path / 'run-user-1000'
    xdg.mkdir()
    posix = host.phone_env(folder, base={'PATH': '/usr/bin', 'XDG_RUNTIME_DIR': str(xdg)}, os_name='posix')
    user_dirs = [folder.home / 'avd' / 'running',  # ANDROID_EMULATOR_HOME
                 folder.avd_home / 'avd' / 'running',  # ANDROID_AVD_HOME
                 folder.home / '.android' / 'avd' / 'running']  # ~/.android, HOME being the phone's home
    linux = host.discovery_dirs(folder, env=posix, os_name='posix', platform='linux', uid=1000)
    assert xdg / 'avd' / 'running' in linux and all(d in linux for d in user_dirs)
    assert Path('/') / 'run' / 'user' / '1000' / 'avd' / 'running' in linux  # the reader's fallback is looked in too: the writer may use either
    assert folder.home / 'Library' / 'Caches' / 'TemporaryItems' / 'avd' / 'running' not in linux
    # XDG_RUNTIME_DIR set to a folder that isn't there, or not set at all: /run/user/<uid>
    for base in ({'PATH': '/usr/bin', 'XDG_RUNTIME_DIR': str(tmp_path / 'gone')}, {'PATH': '/usr/bin'}):
        env = host.phone_env(folder, base=base, os_name='posix')
        assert Path('/') / 'run' / 'user' / '1000' / 'avd' / 'running' in host.discovery_dirs(folder, env=env, os_name='posix',
                                                                                              platform='linux', uid=1000)
    mac = host.discovery_dirs(folder, env=posix, os_name='posix', platform='darwin', uid=501)
    assert folder.home / 'Library' / 'Caches' / 'TemporaryItems' / 'avd' / 'running' in mac and all(d in mac for d in user_dirs)
    assert xdg / 'avd' / 'running' not in mac
    windows = host.discovery_dirs(folder, env={'LOCALAPPDATA': r'C:\Users\u\AppData\Local'}, os_name='nt', platform='win32')
    assert Path(r'C:\Users\u\AppData\Local') / 'Temp' / 'avd' / 'running' in windows and folder.discovery_dir in windows


def _posix_place(folder, tmp_path, monkeypatch, platform='linux'):
    xdg = tmp_path / 'xdg'
    place = xdg / 'avd' / 'running'
    place.mkdir(parents=True)
    env = host.phone_env(folder, base={'PATH': '/usr/bin', 'XDG_RUNTIME_DIR': str(xdg)}, os_name='posix')
    real = host.discovery_dirs
    monkeypatch.setattr(host, 'discovery_dirs', lambda f, **kw: real(f, env=env, os_name='posix', platform=platform, uid=1000))
    return place


def test_p2_4_the_discovery_file_written_in_a_posix_place_is_found_and_only_ours(folder, tmp_path, monkeypatch):
    place = _posix_place(folder, tmp_path, monkeypatch)
    ours, stranger = place / 'pid_3999991.ini', place / 'pid_3999992.ini'
    ours.write_text(f'avd.dir={folder.avd_dir}\ngrpc.port=9\ngrpc.token=t\n')
    stranger.write_text(f'avd.dir={tmp_path / "someone-else.avd"}\ngrpc.port=9\n')
    h = host.PhoneHost(folder)
    assert h._find_discovery({'qemu_pid': 3999991, 'log': ''}) == ours
    assert h._find_discovery({'qemu_pid': 3999992, 'log': ''}) is None  # another AVD's file is never ours


def test_p2_4_cleanup_clears_this_phones_dead_file_in_a_posix_place_and_leaves_a_strangers(folder, tmp_path, monkeypatch):
    place = _posix_place(folder, tmp_path, monkeypatch)
    dead, stranger = place / 'pid_3999993.ini', place / 'pid_3999994.ini'
    dead.write_text(f'avd.dir={folder.avd_dir}\n')
    stranger.write_text(f'avd.dir={tmp_path / "someone-else.avd"}\n')
    assert host.PhoneHost(folder).cleanup_discovery() == 1
    assert not dead.exists() and stranger.exists()


def test_p2_4_cleanup_also_clears_the_file_the_state_recorded_wherever_it_was(folder, tmp_path):
    """The state names where this phone's file was found (the log's "Advertising in" line can name any place)."""
    elsewhere = tmp_path / 'elsewhere' / 'avd' / 'running'
    elsewhere.mkdir(parents=True)
    ours, stranger = elsewhere / 'pid_3999995.ini', elsewhere / 'pid_3999996.ini'
    ours.write_text(f'avd.dir={folder.avd_dir}\n')
    stranger.write_text(f'avd.dir={tmp_path / "someone-else.avd"}\n')
    (elsewhere / '3999995').mkdir()
    host.write_json(folder.state, {'running': False, 'discovery': str(ours)})
    assert host.PhoneHost(folder).cleanup_discovery() == 1
    assert not ours.exists() and not (elsewhere / '3999995').exists() and stranger.exists()


def test_p2_5_a_phone_whose_emulator_and_keeper_vanished_starts_cleanly_and_once(folder, monkeypatch):
    """Root's decision (18 Nov): on Linux a Hermes restart under systemd takes the phone's cgroup with it (a hard kill, the keeper
    too) and the phone starts again when it is next needed. That next start must clear what the kill left and start one phone."""
    (folder.avd_dir / 'config.ini').write_text('AvdId=beacon_agent_phone\n')
    gone = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(30)'])
    birth = psutil.Process(gone.pid).create_time()
    gone.kill()
    gone.wait(10)
    ini = folder.discovery_dir / 'pid_3999997.ini'
    ini.write_text(f'avd.dir={folder.avd_dir}\ngrpc.port=9\ngrpc.token=old\n')
    frame = folder.run / 'frames-41230.bin'
    frame.write_bytes(b'\0' * 16)
    host.write_json(folder.state, {'running': True, 'pid': gone.pid, 'create_time': birth, 'qemu_pid': 3999997, 'qemu_create_time': birth,
                                   'ports': {'console': 41230, 'grpc': 41232}, 'discovery': str(ini)})
    host.write_json(folder.keeper_conf, {'pid': 3999998, 'create_time': birth, 'python': sys.executable})
    spawned = []

    class Recorder:
        def spawn(self, name, cmd, env, log=None, cwd=None):
            spawned.append(name)
            return {'pid': os.getpid(), 'create_time': psutil.Process().create_time(), 'via': 'intermediate'}

    h = host.PhoneHost(folder, python=sys.executable, spawner=Recorder(), pick_ports=lambda f: {'console': 41230, 'grpc': 41232})
    monkeypatch.setattr(h, '_wait_booted', lambda state, t0: None)
    monkeypatch.setattr(h, '_adb_ready', lambda state, seconds=60.0: True)
    monkeypatch.setattr(h, '_anr_or_none', lambda state: 'clear')
    monkeypatch.setattr(h, 'first_start', lambda adb=None: {'done': []})
    monkeypatch.setattr(h, 'adb', lambda state=None: None)
    monkeypatch.setattr(host, '_sibling', lambda name: type('Adb', (), {'generate_phone_key': staticmethod(lambda home: None)}))
    state = h.start()
    assert spawned == ['emulator', 'keeper']  # one phone and one keeper, not two
    assert state['running'] is True and state['pid'] == os.getpid() and state['pid'] != gone.pid
    assert not ini.exists() and not frame.exists()  # what the hard kill left is cleared
    kept = host.read_json(folder.state)
    assert kept['pid'] == os.getpid() and kept['ports'] == {'console': 41230, 'grpc': 41232} and not kept.get('discovery')
    assert host.read_json(folder.keeper_conf)['pid'] == os.getpid()  # the keeper of this start, not the vanished one


# ---- PHONEFIX-121 follow-up 3: a stop saves the Quick Boot snapshot only when the ANR check ran and found nothing stuck ----------

def _stop_a_running_fake(folder, monkeypatch, anr):
    """A running phone with a saved snapshot, stopped while its ANR check reports `anr`. Returns (stop result, what _end was told, snap)."""
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    (snap / 'ram.bin').write_bytes(b'x')
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        h = host.PhoneHost(folder)
        ended = []
        monkeypatch.setattr(h, '_anr_or_none', lambda state: anr)
        monkeypatch.setattr(h, '_end', lambda state, save: ended.append(save))
        return h.stop('test'), ended, snap
    finally:
        p.kill()


@pytest.mark.parametrize('anr', ['unchecked:AdbError', 'unchecked:TimeoutError', 'unchecked:OSError'])
def test_fu3_a_stop_whose_anr_check_could_not_run_saves_no_quick_boot_and_drops_the_snapshot(folder, monkeypatch, anr):
    """The first live block of follow-up 2: adb stopped answering, the stop said `unchecked:AdbError`, saved the snapshot, and the
    next start restored the same stalled phone. Whatever the check could not tell, nothing is saved: the next start cold-boots."""
    result, ended, snap = _stop_a_running_fake(folder, monkeypatch, anr)
    assert result['saved'] is False and result['anr'] == anr and ended == [False] and not snap.exists()
    assert host.read_json(folder.state)['running'] is False


def test_fu3_a_phone_whose_adb_does_not_answer_is_stopped_without_a_save_end_to_end(folder, monkeypatch):
    """The real ANR check against an adb that raises, as the stalled phone's did."""
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        h = host.PhoneHost(folder)
        ended = []

        def stalled(state=None):
            raise host._sibling('agent_phone_adb').AdbError('The phone did not answer in time.')
        monkeypatch.setattr(h, 'adb', stalled)
        monkeypatch.setattr(h, '_end', lambda state, save: ended.append(save))
        result = h.stop('test')
        assert result['anr'] == 'unchecked:AdbError' and result['saved'] is False and ended == [False] and not snap.exists()
    finally:
        p.kill()


@pytest.mark.parametrize('anr', ['clear', 'closed'])
def test_fu3_a_stop_whose_check_ran_and_found_nothing_stuck_still_saves(folder, monkeypatch, anr):
    result, ended, snap = _stop_a_running_fake(folder, monkeypatch, anr)
    assert result['saved'] is True and ended == [True] and snap.exists()


# ---- PHONEFIX-121 follow-up 4: the masks live as long as the snapshot that can bring the screen back (REVIEW-FIX-PHONE-121 F2) -----

NAME = 'Ada Q Example'  # cardholder_name is one of the vault's SECRET_ROLES: only the registered rule masks it


def _checkout_tree():
    from test_agent_phone_redact import node as rnode, tree as rtree
    return rtree(rnode(NAME, hint='Name on card'), rnode('Kanto Foods checkout', hint='Notes', bounds='[63,470][1017,596]'))


def _stop_with(folder, monkeypatch, anr, adb=None):
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    (snap / 'ram.bin').write_bytes(b'x')
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        h = host.PhoneHost(folder)
        monkeypatch.setattr(host, 'STOP_SECONDS', 0.5)
        monkeypatch.setattr(h, '_anr_or_none', lambda state: anr)
        if adb is not None:
            monkeypatch.setattr(h, 'adb', lambda state=None: adb)
        return h.stop('idle'), snap
    finally:
        p.kill()


def test_f2_a_stop_that_drops_the_snapshot_drops_the_masks_with_it(folder, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    redact = load_redact()
    redact.MaskSet(store=folder.run / 'masks.json').register(NAME, 'dev.beacon.fixture.shop')
    (folder.run / 'masks.json.tmp').write_text('{}')
    result, snap = _stop_with(folder, monkeypatch, 'stuck')  # a stuck phone: nothing saved, the next start cold-boots
    assert result['saved'] is False and not snap.exists()
    assert not (folder.run / 'masks.json').exists() and not (folder.run / 'masks.json.tmp').exists()


def test_f2_ending_the_emulator_alone_keeps_the_masks(folder):
    keep = folder.run / 'masks.json'
    keep.write_text('{"v": 1}')
    host.PhoneHost(folder)._end({'running': False}, save=False)  # the snapshot (if any) is still on disk: so are its masks
    assert keep.exists()


def test_p3_2_dropping_the_snapshot_clears_the_masks_kept_for_the_gateway(folder):
    keep, tmp = folder.run / 'masks.json', folder.run / 'masks.json.tmp'
    keep.write_text('{"v": 1}')
    tmp.write_text('{"v": 1}')
    host.PhoneHost(folder)._drop_quick_boot()
    assert not keep.exists() and not tmp.exists()


def test_f2_before_the_change_an_in_memory_set_kept_masking_after_a_stop(folder):
    """The base tree's behaviour (MaskSet in memory, never cleared at a stop), for comparison."""
    from test_agent_phone_redact import load as load_redact
    redact = load_redact()
    gateway = redact.MaskSet()
    gateway.register(NAME, 'dev.beacon.fixture.shop')
    host.PhoneHost(folder)._end({'running': False}, save=True)
    assert NAME not in redact.mask_tree(_checkout_tree(), gateway, 'dev.beacon.fixture.shop')[0]


# ---- F7 (P3): _end never deletes a discovery file that is not the phone's own ----------------------------------------------------

def test_f7_end_never_deletes_another_emulators_discovery_file(folder, tmp_path):
    shared = tmp_path / 'run-user-1000' / 'avd' / 'running'  # /run/user/<uid>/avd/running: every emulator of this user
    shared.mkdir(parents=True)
    other = shared / f'pid_{os.getpid()}.ini'  # a live emulator of the owner's own (a PID the phone's old QEMU once had)
    other.write_text('avd.dir=' + str(tmp_path / 'android-studio' / 'Pixel.avd') + '\ngrpc.port=8554\n', encoding='utf-8')
    host.PhoneHost(folder)._end({'running': False, 'discovery': str(other)}, save=False)
    assert other.exists(), "the stale state's path deleted another emulator's discovery file"


# ---- PHONEFIX-121 follow-up 5: the masks are bounded to what the snapshot can bring back (REVIEW-FIX-PHONE-121-R2 R2-4) --------------

class RecordingAdb:
    """The phone's adb as the stop sees it: every shell call recorded; the packages in `fail` do not answer."""

    def __init__(self, fail=()):
        self.calls, self.fail = [], tuple(fail)

    def shell(self, args, timeout=30, **kw):
        self.calls.append(args)
        if isinstance(args, list) and args[:2] == ['am', 'force-stop'] and args[2] in self.fail:
            raise RuntimeError('The phone did not answer in time.')
        return type('Done', (), {'returncode': 0, 'stdout': '', 'stderr': ''})()

    def force_stops(self):
        return [c[2] for c in self.calls if isinstance(c, list) and c[:2] == ['am', 'force-stop']]


def _start_harness(folder, monkeypatch):
    (folder.avd_dir / 'config.ini').write_text('AvdId=beacon_agent_phone\n')

    class Recorder:
        def spawn(self, name, cmd, env, log=None, cwd=None):
            return {'pid': os.getpid(), 'create_time': psutil.Process().create_time(), 'via': 'intermediate'}

    h = host.PhoneHost(folder, python=sys.executable, spawner=Recorder(), pick_ports=lambda f: {'console': 41230, 'grpc': 41232})
    monkeypatch.setattr(h, '_wait_booted', lambda state, t0: None)
    monkeypatch.setattr(h, '_adb_ready', lambda state, seconds=60.0: True)
    monkeypatch.setattr(h, '_anr_or_none', lambda state: 'clear')
    monkeypatch.setattr(h, 'first_start', lambda adb=None: {'done': []})
    monkeypatch.setattr(h, 'adb', lambda state=None: None)
    monkeypatch.setattr(host, '_sibling', lambda name: type('Adb', (), {'generate_phone_key': staticmethod(lambda home: None)}))
    return h


def test_r2_4_a_cold_start_deletes_the_masks(folder, monkeypatch):
    (folder.run / 'masks.json').write_text('{"v": 1}')
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    _start_harness(folder, monkeypatch).start(cold=True)  # the snapshot exists but is not loaded: no screen comes back
    assert not (folder.run / 'masks.json').exists()


def test_r2_4_a_start_with_no_snapshot_deletes_the_masks(folder, monkeypatch):
    (folder.run / 'masks.json').write_text('{"v": 1}')
    _start_harness(folder, monkeypatch).start()  # nothing to restore: it boots cold anyway
    assert not (folder.run / 'masks.json').exists()


def test_r2_4_a_quick_boot_start_keeps_the_masks(folder, monkeypatch):
    (folder.run / 'masks.json').write_text('{"v": 1}')
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    (snap / 'ram.bin').write_bytes(b'x')
    _start_harness(folder, monkeypatch).start()  # the snapshot is loaded: its screen may be a filled one
    assert (folder.run / 'masks.json').exists()


# ---- PHONEFIX-121 follow-up 6: a stop while a fill is on record saves no snapshot; the force-stop is gone ---------------------------------

def test_fu6_a_stop_while_a_fill_is_on_record_saves_no_snapshot_and_deletes_the_masks(folder, monkeypatch):
    """I1: nothing a Quick Boot snapshot can restore is shown to the model unmasked. A snapshot saved from a session with a fill could bring
    a filled screen back (in any app: the filled one, a welcome mail that repeats the password) with nothing to mask it."""
    from test_agent_phone_redact import load as load_redact
    redact = load_redact()
    redact.MaskSet(store=folder.run / 'masks.json').register('Test-Pass-4711', 'dev.beacon.fixture.shop')
    adb = RecordingAdb()
    result, snap = _stop_with(folder, monkeypatch, 'clear', adb)  # a healthy idle stop
    assert result['saved'] is False and result['filled'] is True and not snap.exists() and not (folder.run / 'masks.json').exists()
    assert adb.force_stops() == []  # nothing is force-stopped any more


def test_fu6_a_stop_with_no_fill_saves_the_snapshot_as_today(folder, monkeypatch):
    adb = RecordingAdb()
    result, snap = _stop_with(folder, monkeypatch, 'clear', adb)
    assert result['saved'] is True and result['filled'] is False and snap.exists() and adb.force_stops() == []


@pytest.mark.parametrize('content', ['', '{', '{"v": 1}', '[]', '{"v": 1, "packages": ["a.b"], "digests": []}'])
def test_fu6_any_masks_file_is_a_fill_on_record_even_empty_or_unreadable(folder, monkeypatch, content):
    (folder.run / 'masks.json').write_text(content)
    assert host.PhoneHost(folder).fill_on_record() is True
    result, snap = _stop_with(folder, monkeypatch, 'clear', RecordingAdb())
    assert result['saved'] is False and not snap.exists()


def test_fu6_a_temporary_masks_file_is_a_fill_on_record(folder, monkeypatch):
    (folder.run / 'masks.json.tmp').write_text('{}')
    assert host.PhoneHost(folder).fill_on_record() is True
    result, snap = _stop_with(folder, monkeypatch, 'closed', RecordingAdb())
    assert result['saved'] is False and not snap.exists() and not (folder.run / 'masks.json.tmp').exists()


def test_fu6_a_fill_that_lands_while_the_stop_syncs_is_seen_before_the_shutdown(folder, monkeypatch):
    class Adb(RecordingAdb):
        def shell(self, args, timeout=30, **kw):
            if args == 'sync':
                (folder.run / 'masks.json').write_text('{"v": 1}')  # the agent's fill record lands now
            return super().shell(args, timeout, **kw)
    result, snap = _stop_with(folder, monkeypatch, 'clear', Adb())
    assert result['saved'] is False and not snap.exists() and not (folder.run / 'masks.json').exists()


def test_fu6_a_fill_that_lands_while_the_snapshot_is_written_drops_it_again(folder, monkeypatch):
    snap = folder.avd_dir / 'snapshots' / 'default_boot'
    snap.mkdir(parents=True)
    (snap / 'ram.bin').write_bytes(b'x')
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'])
    try:
        host.write_json(folder.state, {'running': True, 'pid': p.pid, 'create_time': psutil.Process(p.pid).create_time()})
        h = host.PhoneHost(folder)
        monkeypatch.setattr(h, '_anr_or_none', lambda state: 'clear')
        monkeypatch.setattr(h, 'adb', lambda state=None: RecordingAdb())
        ended = []

        def end(state, save):
            ended.append(save)
            (folder.run / 'masks.json').write_text('{"v": 1}')  # a fill lands during the shutdown
        monkeypatch.setattr(h, '_end', end)
        result = h.stop('idle')
    finally:
        p.kill()
    assert ended == [True] and result['saved'] is False and result['filled'] is True
    assert not snap.exists() and not (folder.run / 'masks.json').exists()


def test_fu6_the_force_stop_code_is_gone():
    assert not hasattr(host.PhoneHost, '_force_stop_filled') and not hasattr(host, 'PACKAGE_NAME')


def test_r3_3_a_filled_password_shown_in_another_app_cannot_come_back_from_a_snapshot(folder, monkeypatch):
    """v5 force-stopped the apps the masks file names and deleted every digest, so a welcome mail repeating the new password came back from
    the snapshot unmasked. Now a stop while a fill is on record saves no snapshot at all: there is no screen to come back (adapted: the
    proof's own assertion that the stop saves is the premise that changed)."""
    from test_agent_phone_redact import load as load_redact, node as rnode, tree
    redact = load_redact()
    mail = 'com.google.android.gm'
    welcome = tree(rnode('Welcome to Kanto Foods', package=mail, bounds='[63,344][1017,470]'),
                   rnode('Your new password is Test-Pass-4711', package=mail, bounds='[63,470][1017,596]'))
    gateway = redact.MaskSet(store=folder.run / 'masks.json')
    gateway.register('Test-Pass-4711', 'dev.beacon.fixture.shop')  # the vault filled a new login into the shop's sign-up form
    assert 'Test-Pass-4711' not in redact.mask_tree(welcome, gateway, mail)[0]  # control: masked in the mail app, this session
    adb = RecordingAdb()
    result, snap = _stop_with(folder, monkeypatch, 'clear', adb)
    assert result['saved'] is False and not snap.exists()  # the mail app's screen is in no snapshot
    assert adb.force_stops() == []


@pytest.mark.parametrize('how', ['cold', 'no snapshot', 'quick boot'])
def test_fu11_every_start_writes_a_new_session_id(folder, monkeypatch, how):
    """R6-3: a cold start, a start with no snapshot and a quick-boot start each begin a new phone session; the masks file may stay (quick
    boot) but every process learns that the sets it kept belong to the last one."""
    (folder.run / 'session').write_text('the-last-session', encoding='utf-8')
    if how != 'no snapshot':
        snap = folder.avd_dir / 'snapshots' / 'default_boot'
        snap.mkdir(parents=True)
        (snap / 'ram.bin').write_bytes(b'x')
    _start_harness(folder, monkeypatch).start(cold=(how == 'cold'))
    now = (folder.run / 'session').read_text(encoding='utf-8').strip()
    assert now != 'the-last-session' and len(now) == 32 and not (folder.run / 'session.tmp').exists()


def test_fu11_new_session_replaces_the_id_atomically(folder):
    h = host.PhoneHost(folder)
    h.new_session()
    first = (folder.run / 'session').read_text(encoding='utf-8')
    h.new_session()
    assert (folder.run / 'session').read_text(encoding='utf-8') != first and not (folder.run / 'session.tmp').exists()

@pytest.mark.parametrize('standalone', [False, True], ids=['host', 'installed-keeper'])
def test_shared_temp_cleanup_accepts_only_production_writer_names(folder, monkeypatch, standalone):
    # Exercise actual mapped-file creation and release, then replay those names as synthetic crash leftovers.
    stream = host._sibling('agent_phone_stream')
    install = host._sibling('agent_phone_install')
    custom_port = 41230
    state = {'ports': {'console': custom_port}}
    host.write_json(folder.state, state)
    tags = [*install.CONSOLE_PORTS, custom_port, 'phone']
    monkeypatch.setattr(stream.secrets, 'token_hex', lambda size: (b'\x93' * size).hex())
    names = set()

    class IdleClient:
        def screenshots(self, *args, **kwargs):
            return iter(())
        def close(self):
            pass
        cancel = close

    for tag in tags:
        frames = stream.MmapFrames(IdleClient, folder.frames, tag, width=2, height=2).start()
        try:
            names.add(frames.path.name)
        finally:
            assert frames.stop()
    assert len(names) == len(tags)
    for name in names:
        (folder.frames / name).touch()
    (folder.run / 'frames-legacy.bin').touch()
    other = folder.root.parent / 'neighbor' / 'tmp' / 'AndroidEmulator'
    other.mkdir(parents=True)
    (other / next(iter(names))).touch()
    keep = ['frames-camera-0123abcd.bin', 'frames-5640-0123abc.bin', 'frames-5640-0123abcde.bin',
            'frames-5640-0123ABCD.bin', 'frames-5640-0123abcg.bin', 'frames-05640-0123abcd.bin',
            'frames-5641-0123abcd.bin', 'frames-99999-0123abcd.bin', 'frames-41232-0123abcd.bin',
            'Frames-5640-abcdef12.bin', 'frames-5640-abcdef12.BIN', 'frames-5640-abcdef12.bin.bak']
    for name in keep:
        (folder.frames / name).write_bytes(b'keep')
    (folder.frames / 'frames-5640-12121212.bin').mkdir()  # same spelling, but not a file made by the writer

    # Record cleanup selection without deleting any fixture. Verify both deployment paths against the real writer.
    selected = []
    monkeypatch.setattr(Path, 'unlink', lambda path, *args, **kwargs: selected.append(path))
    phone = host.PhoneHost(folder, view_open=lambda: False)
    if standalone:
        phone.install_keeper()
        spec = importlib.util.spec_from_file_location('frame_test_keeper', folder.keeper_py)
        installed = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(installed)
        monkeypatch.setattr(installed, '_end_processes', lambda _: None)
        installed.stop(folder.root, {}, state, 'test')
    else:
        phone.cleanup_frames()
    assert set(selected) == {folder.run / 'frames-legacy.bin', *(folder.frames / name for name in names)}


def test_shared_frame_cleanup_skips_a_redirected_emulator_directory(folder, monkeypatch):
    keeper = host._sibling('agent_phone_keeper')
    folder.frames.mkdir(parents=True)
    (folder.frames / 'frames-5640-0123abcd.bin').touch()
    resolve = Path.resolve
    neighbor = folder.root.parent / 'neighbor'
    monkeypatch.setattr(Path, 'resolve', lambda path: neighbor if path == folder.frames else resolve(path))
    assert list(keeper.mmap_frame_files(folder.root, {})) == []
