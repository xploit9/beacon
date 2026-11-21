"""The agent phone's keeper (agent_phone_keeper.py, copied as keeper.py): the stop rules on a fake clock, the detached spawn
with exactly the environment it is given, the stop through the plugin's host and without it, and its independence from Beacon.
"""
import importlib.util
import json
import os
import subprocess
import sys
import time
from pathlib import Path

import psutil
import pytest

PLUGIN = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('beacon_agent_phone_keeper_under_test', PLUGIN / 'agent_phone_keeper.py')
keeper = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(keeper)
NOW = 2_000_000_000.0


def _sleeper(env=None):
    p = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(60)'], env=env)
    return p, psutil.Process(p.pid).create_time()


@pytest.fixture
def phone(tmp_path, monkeypatch):
    # The standalone keeper registers the host it imports, including a partially loaded broken host.
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_host', None)
    (tmp_path / 'run').mkdir()
    p, birth = _sleeper()
    state = {'running': True, 'pid': p.pid, 'create_time': birth, 'started_at_epoch': NOW - 3600}
    (tmp_path / 'run' / 'state.json').write_text(json.dumps(state))
    plugin = tmp_path / 'plugin'
    plugin.mkdir()
    yield tmp_path, state, plugin
    p.kill()


def _age(path, seconds):
    path.write_text('')
    os.utime(path, (NOW - seconds, NOW - seconds))


def test_decide_follows_the_design_rules(phone):
    folder, state, plugin = phone
    conf = {'plugin_dir': str(plugin), 'idle_minutes': 30, 'heartbeat_hours': 24}
    run = folder / 'run'
    _age(run / 'heartbeat', 60)
    _age(run / 'activity', 60)
    assert keeper.decide(folder, NOW, conf, state) is None
    _age(run / 'activity', 31 * 60)
    assert keeper.decide(folder, NOW, conf, state) == 'idle'
    _age(run / 'lease.json', 5 * 60)  # setup activity counts too
    assert keeper.decide(folder, NOW, conf, state) is None
    _age(run / 'heartbeat', 25 * 3600)
    assert keeper.decide(folder, NOW, {**conf, 'heartbeat_hours': 24}, {**state, 'started_at_epoch': NOW - 30 * 3600}) == 'hermes-silent'
    assert keeper.decide(folder, NOW, {**conf, 'plugin_dir': str(folder / 'gone')}, state) == 'beacon-removed'
    assert keeper.decide(folder, NOW, conf, {**state, 'running': False}) == 'gone'
    assert keeper.decide(folder, NOW, conf, {**state, 'create_time': state['create_time'] + 50}) == 'gone'  # pid reused
    assert keeper.decide(folder, NOW, conf, None) == 'gone'


def test_watch_stops_once_through_the_plugin_host_and_exits(phone, monkeypatch):
    folder, state, plugin = phone
    (plugin / 'agent_phone_host.py').write_text(
        'import json\nfrom pathlib import Path\nclass PhoneHost:\n'
        '    def __init__(self, folder):\n        self.folder = Path(folder)\n'
        '    def stop(self, reason):\n        (self.folder / "run" / "stopped-by-host.json").write_text(json.dumps(reason))\n')
    (folder / 'run' / 'keeper.json').write_text(json.dumps({'plugin_dir': str(plugin), 'idle_minutes': 30}))
    _age(folder / 'run' / 'heartbeat', 10)
    _age(folder / 'run' / 'activity', 45 * 60)
    sleeps = []
    assert keeper.watch(folder, clock=lambda: NOW, sleep=sleeps.append, max_ticks=3) == 'idle'
    assert json.loads((folder / 'run' / 'stopped-by-host.json').read_text()) == 'idle' and sleeps == []
    assert 'stopped the phone (idle, clean)' in (folder / 'run' / 'keeper.log').read_text()


def test_without_beacon_it_ends_the_emulator_tree_and_the_frame_file(phone):
    folder, state, plugin = phone
    child, child_birth = _sleeper()
    state.update(qemu_pid=child.pid, qemu_create_time=child_birth)
    frames = [folder / 'run' / 'frames-5555.bin', folder / 'tmp' / 'AndroidEmulator' / 'frames-phone-deadbeef.bin']
    for path in frames:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'x')
        (path.parent / 'keep.bin').touch()
    assert keeper.stop(folder, {'plugin_dir': str(folder / 'gone')}, state, 'beacon-removed') == 'ended'
    assert child.wait(timeout=10) is not None
    deadline = time.time() + 10
    while keeper._alive(state['pid'], state['create_time']) and time.time() < deadline:
        time.sleep(0.1)
    assert not keeper._alive(state['pid'], state['create_time'])
    assert all(not p.exists() and (p.parent / 'keep.bin').is_file() for p in frames)


def test_a_broken_plugin_never_keeps_the_phone_running(phone):
    folder, state, plugin = phone
    (plugin / 'agent_phone_host.py').write_text('raise RuntimeError("broken")\n')
    assert keeper.stop(folder, {'plugin_dir': str(plugin)}, state, 'idle') == 'ended'


def test_spawn_gives_the_child_exactly_its_environment(tmp_path, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'hermes-home'))
    monkeypatch.setenv('BEACON_TEST_SECRET', 'not-for-the-child')
    env = {k: os.environ[k] for k in ('SYSTEMROOT', 'PATH', 'TEMP') if k in os.environ}
    env['PHONE_ONLY'] = '1'
    out = tmp_path / 'out.json'
    spec = tmp_path / 'spec.json'
    spec.write_text(json.dumps({'cmd': [sys.executable, '-I', '-c', 'import time; time.sleep(30)'], 'env': env, 'cwd': str(tmp_path),
                                'log': None, 'out': str(out)}))
    r = subprocess.run([sys.executable, '-I', str(PLUGIN / 'agent_phone_keeper.py'), 'spawn', str(spec)], capture_output=True,
                       text=True, timeout=60)
    res = json.loads(out.read_text())
    try:
        assert r.returncode == 0 and res['mode'] in ('breakaway', 'no-breakaway', 'new-session') and res['create_time']
        child = psutil.Process(res['pid'])
        names = set(child.environ())
        assert 'PHONE_ONLY' in names and 'HERMES_HOME' not in names and 'BEACON_TEST_SECRET' not in names
        assert child.ppid() != os.getpid()  # the intermediate exited; no tree of ours holds it
    finally:
        psutil.Process(res['pid']).kill()


def test_the_keeper_imports_nothing_of_beacon_at_load():
    src = (PLUGIN / 'agent_phone_keeper.py').read_text(encoding='utf-8')
    top = [line for line in src.splitlines() if line.startswith(('import ', 'from '))]
    assert all(line.split()[1] in ('json', 'os', 'subprocess', 'sys', 'time', 'uuid', 'pathlib') for line in top)
    code = src.split('"""', 2)[2]  # past the module docstring
    assert 'import socket' not in code and 'socket.' not in code and 'agent_phone_adb' not in code  # §7.2 rule 6


def test_f2_without_beacon_the_keeper_leaves_the_kept_masks_with_the_old_snapshot(phone):
    """A hard kill keeps the previous Quick Boot snapshot, so the next start can restore its screen: the masks stay for it."""
    folder, state, plugin = phone
    (folder / 'run' / 'masks.json').write_text('{"v": 1}')
    assert keeper.stop(folder, {'plugin_dir': str(folder / 'gone')}, state, 'beacon-removed') == 'ended'
    assert (folder / 'run' / 'masks.json').exists()
