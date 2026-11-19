"""The agent phone's keeper (EMU-DESIGN §7.2). Copied into the phone folder as ``keeper.py`` and run by Hermes' Python, detached.

Two modes, both standalone (this file imports nothing of Beacon's at load, so it runs after Beacon is gone):

``python -I keeper.py spawn <spec.json>``
    The short-lived intermediate (S7): starts ``spec["cmd"]`` detached (Windows: CREATE_BREAKAWAY_FROM_JOB |
    CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW, retried without breakaway when a job forbids it; POSIX: a new session), with
    exactly ``spec["env"]``, writes ``{"pid", "create_time", "mode"}`` to ``spec["out"]`` and exits, so no tree kill of the
    caller reaches the child.

``python -I keeper.py watch <phone folder>``
    Every 60 s: stops the phone and exits when there was no activity for ``idle_minutes`` (default 30), when Beacon's plugin
    folder is gone, or when the plugin's heartbeat is older than ``heartbeat_hours`` (default 24); exits when the phone is gone.
    S7's rules: the caller strips every ``HERMES_*`` variable and credential from its environment (an allow-listed
    environment), names only the phone folder on its command line and as its working folder, and checks it is in no job. It
    owns the emulator only: no secrets kept, no sockets opened, no adb. The stop is the host's own clean stop, loaded from the
    plugin folder named in ``run/keeper.json``; without the plugin it ends the emulator's processes.

Hermes updates (EMU-JOIN fu1): Hermes on Windows stages each update into a new runtime generation
(``<home>/installs/<key>/environments/<generation>/venv``), records it in ``<key>/facts.json`` and then removes superseded
generations nobody holds a lease on (``hermes_cli.runtime_state.collect_generations``). The keeper holds Hermes' own lease on the
generation it runs from, so that cleanup skips it instead of failing part-way on the keeper's open files; each tick it checks
Hermes' record, and when Hermes has moved on it starts itself again on the current runtime, records that keeper and exits (the
phone keeps running). A keeper that ``run/keeper.json`` no longer names exits too.
"""
import json
import os
import subprocess
import sys
import time
import uuid
from pathlib import Path

CREATE_BREAKAWAY_FROM_JOB = 0x01000000
CREATE_NEW_PROCESS_GROUP = 0x00000200
CREATE_NO_WINDOW = 0x08000000
TICK_SECONDS = 60


# -- spawn ---------------------------------------------------------------------------------------------------------------

def _process_birth(pid):
    try:
        import psutil

        return psutil.Process(pid).create_time()
    except Exception:  # health: allow BLE001 -- psutil absent or the child already gone
        return None


def spawn(spec_path):
    spec = json.loads(Path(spec_path).read_text(encoding='utf-8'))
    log = open(spec['log'], 'ab') if spec.get('log') else subprocess.DEVNULL
    result = {}
    common = dict(cwd=spec.get('cwd'), env=spec['env'], stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                  close_fds=True)
    try:
        if os.name == 'nt':
            base = CREATE_NEW_PROCESS_GROUP | CREATE_NO_WINDOW
            for flags, mode in ((base | CREATE_BREAKAWAY_FROM_JOB, 'breakaway'), (base, 'no-breakaway')):
                try:
                    p = subprocess.Popen(spec['cmd'], creationflags=flags, **common)
                    result = {'pid': p.pid, 'mode': mode}
                    break
                except OSError as e:
                    result = {'error': f'{mode}: {getattr(e, "winerror", None) or type(e).__name__}'}
        else:
            p = subprocess.Popen(spec['cmd'], start_new_session=True, **common)
            result = {'pid': p.pid, 'mode': 'new-session'}
    finally:
        if log is not subprocess.DEVNULL:
            log.close()
    if 'pid' in result:
        result['create_time'] = _process_birth(result['pid'])
    out = Path(spec['out'])
    tmp = out.with_name(out.name + '.tmp')
    tmp.write_text(json.dumps(result), encoding='utf-8')
    os.replace(tmp, out)
    print(json.dumps(result), flush=True)
    return 0 if 'pid' in result else 2


# -- watch ---------------------------------------------------------------------------------------------------------------

def _read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


def _mtime(path):
    try:
        return Path(path).stat().st_mtime
    except OSError:
        return 0.0


def _alive(pid, birth):
    if not pid:
        return False
    try:
        import psutil

        return abs(psutil.Process(int(pid)).create_time() - float(birth or 0)) < 1.0
    except Exception:  # health: allow BLE001 -- gone, reused or not ours
        return False


def decide(folder, now, conf, state):
    """Why the keeper should act now: 'gone' (no phone to keep), a stop reason, or None. Pure, for tests."""
    run = Path(folder) / 'run'
    if not state or not state.get('running') or not _alive(state.get('pid'), state.get('create_time')):
        return 'gone'
    plugin = conf.get('plugin_dir')
    if plugin and not Path(plugin).is_dir():
        return 'beacon-removed'
    heartbeat = _mtime(run / 'heartbeat')
    if now - max(heartbeat, float(state.get('started_at_epoch') or 0)) > float(conf.get('heartbeat_hours', 24)) * 3600:
        return 'hermes-silent'
    last = max(_mtime(run / 'activity'), _mtime(run / 'lease.json'), float(state.get('started_at_epoch') or 0))
    if now - last >= float(conf.get('idle_minutes', 30)) * 60:
        return 'idle'
    return None


def _load_host(plugin_dir):
    import importlib.util

    path = Path(plugin_dir) / 'agent_phone_host.py'
    spec = importlib.util.spec_from_file_location('beacon_agent_phone_host', path)
    module = importlib.util.module_from_spec(spec)
    sys.modules['beacon_agent_phone_host'] = module
    spec.loader.exec_module(module)
    return module


def _end_processes(state):
    """Without Beacon: end the emulator and QEMU (birth-checked), as a hard kill does (S7: the next start cold-boots)."""
    try:
        import psutil
    except ImportError:
        return
    victims = []
    for pid_key, birth_key in (('pid', 'create_time'), ('qemu_pid', 'qemu_create_time')):
        pid, birth = state.get(pid_key), state.get(birth_key)
        if _alive(pid, birth):
            try:
                root = psutil.Process(int(pid))
                victims += root.children(recursive=True) + [root]  # QEMU's netsimd and crash handler too
            except Exception:  # health: allow BLE001 -- already gone
                pass
    for proc in victims:
        try:
            proc.kill()
        except Exception:  # health: allow BLE001 -- already gone
            pass


def stop(folder, conf, state, reason):
    plugin = conf.get('plugin_dir')
    if plugin and Path(plugin).is_dir():
        try:
            _load_host(plugin).PhoneHost(folder).stop(reason)
            return 'clean'
        except Exception:  # health: allow BLE001 -- a broken plugin must not keep the phone running
            pass
    _end_processes(state)
    for frame in (Path(folder) / 'run').glob('frames-*.bin'):
        try:
            frame.unlink()  # the frame file; the masks kept for the gateway stay: this hard kill keeps the old Quick Boot snapshot
        except OSError:
            pass
    return 'ended'


def _note(folder, text):
    try:
        with open(Path(folder) / 'run' / 'keeper.log', 'a', encoding='utf-8') as fh:
            fh.write(time.strftime('%Y-%m-%dT%H:%M:%S ') + text + '\n')
    except OSError:
        pass


# -- Hermes' runtime (fu1) -----------------------------------------------------------------------------------------------

def _same(a, b):
    try:
        return os.path.normcase(str(Path(a).resolve())) == os.path.normcase(str(Path(b).resolve()))
    except (OSError, ValueError):
        return False


def venv_of(python):
    """The virtual environment an interpreter belongs to (the folder above it that holds pyvenv.cfg), or None."""
    for up in list(Path(python).parents)[:3]:
        if (up / 'pyvenv.cfg').is_file():
            return up
    return None


def generation_of(venv):
    """Hermes' lease-managed runtime generation holding this venv (``environments/<generation>/venv``), or None."""
    if venv is None:
        return None
    generation = Path(venv).parent
    return generation if generation.parent.name == 'environments' and (generation / '.lease-managed').is_file() else None


def venv_python(venv):
    for p in (Path(venv) / 'Scripts' / 'python.exe', Path(venv) / 'bin' / 'python3', Path(venv) / 'bin' / 'python'):
        if p.is_file():
            return p
    return None


def superseded(python):
    """The interpreter of the runtime Hermes now selects instead of ``python``'s (``facts.json``, written by Hermes' installer
    when an update commits), or None: same runtime, no record, or not a Hermes runtime generation at all."""
    venv = venv_of(python) if python else None
    generation = generation_of(venv)
    if generation is None:
        return None
    try:
        facts = json.loads((generation.parent.parent / 'facts.json').read_text(encoding='utf-8-sig'))
        chosen = facts['packages']['venv']['environment']
    except (OSError, ValueError, TypeError, KeyError):
        return None
    if not isinstance(chosen, str) or not chosen or _same(chosen, venv) or not (Path(chosen) / 'pyvenv.cfg').is_file():
        return None
    return venv_python(chosen)


def _lock_first_byte(fd, seconds=5.0):
    """The lock Hermes' leases use (pm.filesystem.lock_fd): byte 0 with msvcrt on Windows, flock elsewhere."""
    deadline = time.monotonic() + seconds
    while True:
        try:
            if os.name == 'nt':
                import msvcrt

                os.lseek(fd, 0, os.SEEK_SET)
                msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl

                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            if time.monotonic() >= deadline:
                return False
            time.sleep(0.05)


def lease(venv):
    """Hermes' generation lease (hermes_cli.runtime_state.lease_directory): a new file in ``<generation>/.leases`` whose first
    byte stays locked while this keeper runs; Hermes' collector skips a generation with a held lease and removes the file once
    the lock is gone. (fd, path), or None: no lease-managed generation, or it could not be taken (the keeper runs anyway)."""
    generation = generation_of(venv)
    if generation is None:
        return None
    try:
        leases = generation / '.leases'
        leases.mkdir(exist_ok=True)
        for _ in range(3):
            path = leases / uuid.uuid4().hex
            fd = os.open(path, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
            if not _lock_first_byte(fd):
                os.close(fd)
                return None
            if path.exists():  # a collector may lock-and-unlink between create and lock: then take a new one
                return fd, path
            os.close(fd)
    except OSError:
        return None
    return None


def unlease(held):
    if held:
        fd, path = held
        try:
            os.close(fd)
            path.unlink()
        except OSError:
            pass


def _named(conf):
    """False when run/keeper.json names another keeper (a newer one took over): this one exits and leaves the phone running.
    On Windows a venv's python.exe is a launcher, so the recorded pid may be this process's parent."""
    pid = conf.get('pid')
    return not pid or int(pid) in (os.getpid(), os.getppid())


def _imports_psutil(python, env):
    flags = CREATE_NO_WINDOW if os.name == 'nt' else 0
    try:
        return subprocess.run([str(python), '-I', '-c', 'import psutil'], env=env, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                              stderr=subprocess.DEVNULL, timeout=60, creationflags=flags).returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def handover(folder, conf, python):
    """Starts this keeper again on ``python`` (Hermes' current runtime), detached as ``spawn`` does, and records it in
    run/keeper.json. False when that interpreter can't run it (no psutil) or the start failed: then this keeper stays."""
    folder = Path(folder)
    env = {k: v for k, v in os.environ.items() if k != '__PYVENV_LAUNCHER__'}  # the allow-listed environment it was given
    if not _imports_psutil(python, env):
        return False
    run = folder / 'run'
    spec, out = run / 'keeper-next.spec.json', run / 'keeper-next.json'
    spec.write_text(json.dumps({'cmd': [str(python), '-I', str(folder / 'keeper.py'), 'watch', str(folder)], 'env': env,
                                'cwd': str(folder), 'out': str(out)}), encoding='utf-8')
    try:
        spawn(spec)
        res = _read_json(out) or {}
    finally:
        for path in (spec, out):
            try:
                path.unlink()
            except OSError:
                pass
    if not res.get('pid'):
        return False
    conf = dict(conf, pid=res['pid'], create_time=res.get('create_time'), python=str(python), via='keeper')
    tmp = run / 'keeper.json.tmp'
    tmp.write_text(json.dumps(conf), encoding='utf-8')
    os.replace(tmp, run / 'keeper.json')
    return True


def watch(folder, tick=TICK_SECONDS, clock=time.time, sleep=time.sleep, max_ticks=None, python=None):
    """``python`` is this keeper's own interpreter (``main`` passes ``sys.executable``): its Hermes runtime is leased and
    followed. Without it (tests) neither happens."""
    folder = Path(folder)
    held = lease(venv_of(python)) if python else None
    try:
        return _watch(folder, tick, clock, sleep, max_ticks, held, python)
    finally:
        unlease(held)


def _watch(folder, tick, clock, sleep, max_ticks, held, python):
    _note(folder, f'keeper started pid={os.getpid()} lease={"held" if held else "none"}')
    ticks = 0
    while max_ticks is None or ticks < max_ticks:
        ticks += 1
        conf = _read_json(folder / 'run' / 'keeper.json') or {}
        if not _named(conf):
            _note(folder, 'another keeper took over; keeper exits')
            return 'replaced'
        current = superseded(python) if python else None
        if current is not None and handover(folder, conf, current):
            _note(folder, 'Hermes moved to a new runtime; keeper started again there and exits')
            return 'superseded'
        state = _read_json(folder / 'run' / 'state.json')
        why = decide(folder, clock(), conf, state)
        if why == 'gone':
            _note(folder, 'phone not running; keeper exits')
            return 'gone'
        if why:
            how = stop(folder, conf, state, why)
            _note(folder, f'stopped the phone ({why}, {how}); keeper exits')
            return why
        sleep(tick)
    return None


def main(argv):
    if len(argv) >= 2 and argv[0] == 'spawn':
        return spawn(argv[1])
    if len(argv) >= 2 and argv[0] == 'watch':
        watch(argv[1], python=sys.executable)
        return 0
    print('usage: keeper.py spawn <spec.json> | watch <phone folder>', file=sys.stderr)
    return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
