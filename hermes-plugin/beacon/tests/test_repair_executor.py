"""Temporary-workspace and synthetic-policy checks for the repair executor."""
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import threading
import time
import types
import xml.etree.ElementTree as ET
import zipfile

import pytest

SOURCE = Path(__file__).resolve().parents[1] / 'repair_executor.py'
HARNESS = SOURCE.parent / 'repair-tools' / 'pytest_harness.py'
GRADLE_HARNESS = SOURCE.parent / 'repair-tools' / 'gradle_harness.py'


class FixtureProcessOwner:
    def __init__(self, process): self.process = process
    def kill(self): self.process.kill()
    def close(self): pass


def integration_process_owner():
    if os.environ.get('BEACON_REPAIR_REAL_OWNER') != '1': return FixtureProcessOwner
    path = SOURCE.parent / 'repair_operator.py'
    spec = importlib.util.spec_from_file_location('beacon_repair_operator_owner_test', path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module._OwnedProcess


@pytest.fixture
def executor():
    return load_executor


def load_executor():
    assert SOURCE.is_file(), 'The bounded repair executor has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_repair_executor_test', SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def container_fixture(tmp_path):
    source, scratch = tmp_path / 'export', tmp_path / 'scratch'
    source.mkdir(); scratch.mkdir()
    expected = {'image': 'sha256:' + 'a' * 64, 'user': '65534:65534', 'env': ['PATH=/usr/bin', 'LANG=C.UTF-8'],
                'mounts': {'/workspace': {'source': str(source), 'writable': False}, '/scratch': {'source': str(scratch), 'writable': True}},
                'memory': 268435456, 'nano_cpus': 1000000000, 'pids': 64, 'tmpfs': {'/tmp': 'rw,noexec,nosuid,size=32m'},
                'name': 'beacon-repair-fixture', 'token': 'b' * 32, 'job_id': 'job-' + 'c' * 32, 'stage': 'isolation',
                'entrypoint': ['/usr/local/bin/python'], 'cmd': ['-I', '-S', '-c', 'pass']}
    inspect = {'Id': 'd' * 64, 'Name': '/' + expected['name'], 'Image': expected['image'],
               'Config': {'User': expected['user'], 'Env': expected['env'][:], 'WorkingDir': '/workspace',
                          'Entrypoint': expected['entrypoint'][:], 'Cmd': expected['cmd'][:],
                          'Labels': {'beacon.repair.token': expected['token'], 'beacon.repair.job': expected['job_id'], 'beacon.repair.stage': expected['stage']}},
               'HostConfig': {'NetworkMode': 'none', 'ReadonlyRootfs': True, 'CapDrop': ['ALL'], 'CapAdd': None,
                              'SecurityOpt': ['no-new-privileges'], 'PidsLimit': 64, 'Memory': 268435456, 'MemorySwap': 268435456,
                              'NanoCpus': 1000000000, 'Tmpfs': expected['tmpfs'].copy(), 'Privileged': False, 'PidMode': '',
                              'IpcMode': 'private', 'Devices': [], 'DeviceRequests': None, 'Binds': None, 'VolumesFrom': None,
                              'LogConfig': {'Type': 'none', 'Config': {}}},
               'Mounts': [{'Type': 'bind', 'Source': str(source), 'Destination': '/workspace', 'RW': False, 'Propagation': 'rprivate'},
                          {'Type': 'bind', 'Source': str(scratch), 'Destination': '/scratch', 'RW': True, 'Propagation': 'rprivate'}]}
    return expected, inspect


def executor_config_fixture(tmp_path):
    private = tmp_path / 'private'; private.mkdir()
    for name in ('bindings', 'receipts', 'docker-config'): (private / name).mkdir()
    coordinator = tmp_path / 'coordinator'; coordinator.mkdir()
    binary = tmp_path / 'docker.exe'; binary.write_bytes(b'docker-fixture')
    python_binary = Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True)
    config = {'schema': 1, 'private_root': str(private), 'bindings_root': str(private / 'bindings'),
              'receipt_root': str(private / 'receipts'), 'coordinator_root': str(coordinator), 'repo_identity': 'b' * 64,
              'base_commit': 'c' * 40, 'policy_sha256': 'd' * 64, 'runner_sha256': hashlib.sha256((SOURCE.parent / 'repair_runner.py').read_bytes()).hexdigest(),
              'executor_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(), 'docker_binary': str(binary),
              'python_binary': str(python_binary), 'python_sha256': hashlib.sha256(python_binary.read_bytes()).hexdigest(),
              'docker_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'docker_host': 'npipe:////./pipe/dockerDesktopLinuxEngine',
              'docker_config_dir': str(private / 'docker-config'), 'image': 'sha256:' + 'e' * 64,
              'image_env': ['PATH=/usr/bin', 'LANG=C.UTF-8'], 'sandbox_sha256': 'f' * 64,
              'harness_sha256': hashlib.sha256(HARNESS.read_bytes()).hexdigest(), 'memory': 268435456, 'nano_cpus': 1000000000,
              'pids': 64, 'stage_timeout': 120, 'public_manifest': {'src/public.py': '1' * 64, 'tests/existing.py': '2' * 64, 'tests/new.py': None},
              'editable_paths': ['src/public.py', 'tests/new.py'], 'release_paths': [], 'baseline_paths': ['tests/existing.py'],
              'test_nodes': {'reproduction': ['tests/existing.py::test_x'], 'regression_red': ['tests/new.py::test_x']},
              'native_root': None, 'native_commit': None, 'native_manifest': {}, 'gradle_harness_sha256': None,
              'gradle_init_sha256': None, 'gradle_cache_manifest_sha256': None, 'build_artifacts': None, 'plugin_manifest': None, 'gradle_slot_root': None}
    path = private / 'config.json'; path.write_text(json.dumps(config, sort_keys=True), encoding='utf8')
    return config, path, hashlib.sha256(path.read_bytes()).hexdigest()


def test_profile_hash_is_derived_from_verified_actual_inspect(executor, tmp_path):
    executor = executor()
    expected, actual = container_fixture(tmp_path)
    profile = executor.inspect_profile(actual, expected)
    assert profile['network'] == 'none'
    assert profile['image'] == expected['image']
    assert {mount['target']: mount['role'] for mount in profile['mounts']} == {'/workspace': 'public_export', '/scratch': 'scratch'}
    assert executor.profile_sha256(profile) != '0' * 64
    for mutate in (
        lambda c: c['HostConfig'].__setitem__('NetworkMode', 'bridge'),
        lambda c: c['HostConfig'].__setitem__('ReadonlyRootfs', False),
        lambda c: c['HostConfig'].__setitem__('CapAdd', ['SYS_ADMIN']),
        lambda c: c['HostConfig'].__setitem__('Privileged', True),
        lambda c: c['Config']['Env'].append('OWNER_SECRET=not-allowed'),
        lambda c: c['Mounts'][0].__setitem__('RW', True),
        lambda c: c['Mounts'][1].__setitem__('Source', str(tmp_path)),
        lambda c: c['Config']['Labels'].__setitem__('beacon.repair.token', '0' * 32),
    ):
        bad = copy.deepcopy(actual); mutate(bad)
        with pytest.raises(ValueError): executor.inspect_profile(bad, expected)


def test_profile_hash_changes_when_the_applied_resource_limit_changes(executor, tmp_path):
    executor = executor()
    expected, actual = container_fixture(tmp_path)
    first = executor.profile_sha256(executor.inspect_profile(actual, expected))
    expected['nano_cpus'] = 2000000000; actual['HostConfig']['NanoCpus'] = 2000000000
    assert executor.profile_sha256(executor.inspect_profile(actual, expected)) != first


def test_container_ownership_requires_exact_id_name_and_all_job_labels(executor, tmp_path):
    executor = executor(); expected, actual = container_fixture(tmp_path)
    assert executor.verify_owned(actual, actual['Id'], expected) is True
    for change in (
        lambda c: c.__setitem__('Id', '0' * 64),
        lambda c: c.__setitem__('Name', '/some-other-container'),
        lambda c: c['Config']['Labels'].__setitem__('beacon.repair.token', '0' * 32),
        lambda c: c['Config']['Labels'].__setitem__('beacon.repair.job', 'job-' + '0' * 32),
        lambda c: c['Config']['Labels'].__setitem__('beacon.repair.stage', 'other'),
    ):
        bad = copy.deepcopy(actual); change(bad)
        with pytest.raises(ValueError): executor.verify_owned(bad, actual['Id'], expected)
    with pytest.raises(ValueError): executor.verify_owned(actual, actual['Id'][:12], expected)


def test_stop_checks_ownership_before_kill_and_verifies_container_exit(executor, tmp_path):
    executor = executor(); expected, actual = container_fixture(tmp_path)
    actual['State'] = {'Running': True, 'Status': 'running'}
    class Daemon:
        def __init__(self, inspect, exits): self.value, self.exits, self.calls = inspect, exits, []
        def inspect(self, cid, _deadline): self.calls.append(('inspect', cid)); return copy.deepcopy(self.value)
        def kill(self, cid, _deadline): self.calls.append(('kill', cid))
        def wait(self, cid, _deadline):
            self.calls.append(('wait', cid))
            if self.exits: self.value['State'] = {'Running': False, 'Status': 'exited'}
            return 137
    ok = Daemon(copy.deepcopy(actual), True)
    executor.stop_owned(ok, actual['Id'], expected, time.monotonic() + 5)
    assert [name for name, _ in ok.calls] == ['inspect', 'kill', 'wait', 'inspect']
    foreign = copy.deepcopy(actual); foreign['Config']['Labels']['beacon.repair.token'] = '0' * 32
    bad = Daemon(foreign, True)
    with pytest.raises(ValueError): executor.stop_owned(bad, actual['Id'], expected, time.monotonic() + 5)
    assert [name for name, _ in bad.calls] == ['inspect']
    stuck = Daemon(copy.deepcopy(actual), False)
    with pytest.raises(RuntimeError): executor.stop_owned(stuck, actual['Id'], expected, time.monotonic() + 5)


def test_docker_client_is_local_pinned_and_does_not_inherit_owner_environment(executor, tmp_path, monkeypatch):
    executor = executor(); private = tmp_path / 'private'; private.mkdir(); config = private / 'docker-config'; config.mkdir()
    binary = tmp_path / 'docker.exe'; binary.write_bytes(b'docker-fixture')
    digest = hashlib.sha256(binary.read_bytes()).hexdigest()
    client = executor.DockerClient(binary, digest, 'npipe:////./pipe/dockerDesktopLinuxEngine', config, private)
    monkeypatch.setenv('BEACON_PRIVATE_FIXTURE', 'do-not-inherit')
    calls = []
    def run(args, **kwargs):
        if 'BEACON_PRIVATE_FIXTURE' in kwargs['env']: pytest.fail('Unexpected inherited environment', pytrace=False)
        assert kwargs['env']['DOCKER_CONFIG'] == str(config.resolve())
        calls.append(args)
        return types.SimpleNamespace(returncode=0, stdout=json.dumps([{'Id': 'a' * 64}]).encode())
    monkeypatch.setattr(executor.subprocess, 'run', run)
    assert client.inspect('a' * 64, time.monotonic() + 5) == {'Id': 'a' * 64}
    assert calls[0][-2:] == ['inspect', 'a' * 64]
    with pytest.raises(ValueError): executor.DockerClient(binary, digest, 'tcp://example.test:2375', config, private)
    with pytest.raises(ValueError): executor.DockerClient(binary, '0' * 64, 'npipe:////./pipe/dockerDesktopLinuxEngine', config, private)


def test_create_uses_fixed_isolation_flags_and_checks_applied_profile(executor, tmp_path, monkeypatch):
    executor = executor(); private = tmp_path / 'private'; private.mkdir(); config = private / 'docker-config'; config.mkdir()
    expected, actual = container_fixture(private)
    expected['name'] = 'beacon-repair-' + expected['token']; actual['Name'] = '/' + expected['name']
    actual['State'] = {'Running': False, 'Status': 'created'}
    binary = tmp_path / 'docker.exe'; binary.write_bytes(b'docker-fixture')
    client = executor.DockerClient(binary, hashlib.sha256(binary.read_bytes()).hexdigest(),
                                   'npipe:////./pipe/dockerDesktopLinuxEngine', config, private)
    cidfile = private / 'job.cid'; calls = []
    def run(args, _deadline):
        calls.append(args); cidfile.write_text(actual['Id'], encoding='ascii'); return actual['Id'].encode()
    monkeypatch.setattr(client, '_run', run)
    monkeypatch.setattr(client, 'inspect', lambda *_: copy.deepcopy(actual))
    expected_hash = executor.profile_sha256(executor.inspect_profile(actual, expected))
    cid, applied = client.create(expected, cidfile, time.monotonic() + 5, expected_hash)
    assert cid == actual['Id'] and executor.profile_sha256(applied) == expected_hash
    argv = calls[0]
    assert argv[0] == 'create' and '--network=none' in argv and '--read-only' in argv and '--cap-drop=ALL' in argv
    assert '--security-opt=no-new-privileges' in argv and '--memory-swap=268435456' in argv and '--log-driver=none' in argv
    assert '--pull=never' in argv and '--user=65534:65534' in argv and not any('docker.sock' in value for value in argv)


def test_only_fixed_noop_calibration_can_measure_an_unpinned_profile(executor, tmp_path, monkeypatch):
    executor = executor(); private = tmp_path / 'private'; private.mkdir(); config = private / 'docker-config'; config.mkdir()
    expected, actual = container_fixture(private)
    expected.update(name='beacon-repair-' + expected['token'], stage='calibration')
    actual['Name'] = '/' + expected['name']; actual['Config']['Labels']['beacon.repair.stage'] = 'calibration'
    actual['State'] = {'Running': False, 'Status': 'created'}
    binary = tmp_path / 'docker.exe'; binary.write_bytes(b'docker-fixture')
    client = executor.DockerClient(binary, hashlib.sha256(binary.read_bytes()).hexdigest(),
                                   'npipe:////./pipe/dockerDesktopLinuxEngine', config, private)
    cidfile = private / 'job.cid'
    def run(_args, _deadline): cidfile.write_text(actual['Id'], encoding='ascii'); return actual['Id'].encode()
    monkeypatch.setattr(client, '_run', run); monkeypatch.setattr(client, 'inspect', lambda *_: copy.deepcopy(actual))
    assert client.create(expected, cidfile, time.monotonic() + 5, None)[0] == actual['Id']
    bad = copy.deepcopy(expected); bad['cmd'] = ['-I', '-S', '-c', 'print(1)']
    with pytest.raises(ValueError): client.create(bad, private / 'other.cid', time.monotonic() + 5, None)


def test_rejected_created_container_is_removed_only_after_ownership_verification(executor, tmp_path, monkeypatch):
    executor = executor(); private = tmp_path/'private'; private.mkdir(); config = private/'docker-config'; config.mkdir()
    expected, actual = container_fixture(private)
    expected['name'] = 'beacon-repair-' + expected['token']; actual['Name'] = '/' + expected['name']
    actual['State'] = {'Running': False, 'Status': 'created'}
    binary = tmp_path/'docker.exe'; binary.write_bytes(b'docker-fixture')
    client = executor.DockerClient(binary, hashlib.sha256(binary.read_bytes()).hexdigest(),
                                   'npipe:////./pipe/dockerDesktopLinuxEngine', config, private)
    cidfile = private/'job.cid'; calls = []; removed = []
    def run(args, _deadline):
        calls.append(args[0])
        if args[0] == 'create': cidfile.write_text(actual['Id'], encoding='ascii'); return actual['Id'].encode()
        if args[0] == 'rm': removed.append(actual['Id']); return actual['Id'].encode()
        raise AssertionError('Unexpected command')
    monkeypatch.setattr(client, '_run', run); monkeypatch.setattr(client, 'inspect', lambda *_: copy.deepcopy(actual))
    monkeypatch.setattr(client, 'absent', lambda *_: bool(removed))
    with pytest.raises(RuntimeError): client.create(expected, cidfile, time.monotonic() + 5, '0' * 64)
    assert calls == ['create', 'rm'] and removed == [actual['Id']]


def test_timeout_stops_verified_owned_container_and_confirms_exit(executor, tmp_path):
    executor = executor(); expected, actual = container_fixture(tmp_path)
    actual['State'] = {'Running': False, 'Status': 'created', 'ExitCode': 0}
    profile = executor.profile_sha256(executor.inspect_profile(actual, expected))
    class Daemon:
        def __init__(self): self.value = copy.deepcopy(actual); self.calls = []
        def inspect(self, cid, deadline): self.calls.append('inspect'); return copy.deepcopy(self.value)
        def start(self, cid, deadline): self.calls.append('start'); self.value['State'] = {'Running': True, 'Status': 'running', 'ExitCode': 0}
        def wait_process(self, cid):
            self.calls.append('wait_process'); owner = self
            class Process:
                def communicate(self, timeout):
                    if owner.value['State']['Running']:
                        time.sleep(.02); raise subprocess.TimeoutExpired('fixture', timeout)
                    return b'137\n', b''
            return Process()
        def kill(self, cid, deadline): self.calls.append('kill')
        def wait(self, cid, deadline): self.calls.append('wait'); self.value['State'] = {'Running': False, 'Status': 'exited', 'ExitCode': 137}; return 137
    client = Daemon()
    with pytest.raises(TimeoutError): executor.run_owned(client, actual['Id'], expected, profile, time.monotonic() + 5.5)
    assert client.calls == ['inspect', 'start', 'wait_process', 'inspect', 'kill', 'wait', 'inspect']


def test_docker_cli_exit_alone_does_not_leave_container_running(executor, tmp_path):
    executor = executor(); expected, actual = container_fixture(tmp_path)
    actual['State'] = {'Running': False, 'Status': 'created', 'ExitCode': 0}
    profile = executor.profile_sha256(executor.inspect_profile(actual, expected))
    class Daemon:
        def __init__(self): self.value = copy.deepcopy(actual); self.calls = []
        def inspect(self, cid, deadline): self.calls.append('inspect'); return copy.deepcopy(self.value)
        def start(self, cid, deadline): self.calls.append('start'); self.value['State'] = {'Running': True, 'Status': 'running', 'ExitCode': 0}
        def wait_process(self, cid): self.calls.append('wait_process'); return types.SimpleNamespace(communicate=lambda **_kwargs: (b'125\n', b''), returncode=0)
        def kill(self, cid, deadline): self.calls.append('kill')
        def wait(self, cid, deadline): self.calls.append('wait'); self.value['State'] = {'Running': False, 'Status': 'exited', 'ExitCode': 137}; return 137
    client = Daemon()
    with pytest.raises(RuntimeError): executor.run_owned(client, actual['Id'], expected, profile, time.monotonic() + 15)
    assert client.calls == ['inspect', 'start', 'wait_process', 'inspect', 'inspect', 'kill', 'wait', 'inspect']


def test_logged_build_waits_for_the_real_owned_container_status(executor, tmp_path):
    executor = executor(); expected, actual = container_fixture(tmp_path)
    actual['State'] = {'Running': False, 'Status': 'created', 'ExitCode': 0}
    profile = executor.profile_sha256(executor.inspect_profile(actual, expected)); logfile = tmp_path/'host-only.log'
    class Daemon:
        def __init__(self): self.value = copy.deepcopy(actual); self.calls = []
        def inspect(self, cid, deadline): self.calls.append('inspect'); return copy.deepcopy(self.value)
        def start_logged(self, cid, path):
            self.calls.append('start'); self.value['State'] = {'Running': True, 'Status': 'running', 'ExitCode': 0}; owner = self
            stream = path.open('xb'); stream.write(b'trusted log\n'); stream.flush()
            class Process:
                def poll(self): return None
                def wait(self, timeout): owner.value['State'] = {'Running': False, 'Status': 'exited', 'ExitCode': 0}; return 0
            return Process(), stream
    client = Daemon()
    assert executor.run_owned_logged(client, actual['Id'], expected, profile, time.monotonic() + 15, logfile) == 0
    assert logfile.read_bytes() == b'trusted log\n'
    assert client.calls == ['inspect', 'start', 'inspect', 'inspect']


def test_stage_router_has_only_fixed_names_and_requires_real_process_handles(executor):
    executor = executor(); calls = []
    def stage(name, context):
        calls.append((name, context))
        return subprocess.Popen([sys.executable, '-c', 'pass'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    router = executor.StageRouter({'reproduction': stage})
    job = 'job-' + 'a' * 32
    process = router('reproduction', {'job_id': job})
    try: assert process.wait(timeout=10) == 0
    finally:
        if process.poll() is None: process.kill(); process.wait(timeout=10)
    assert calls == [('reproduction', {'job_id': job})]
    with pytest.raises(ValueError): router('publish', {})
    with pytest.raises(ValueError): executor.StageRouter({'model_shell': stage})
    with pytest.raises(ValueError): executor.StageRouter({'publish': lambda *_: True})('publish', {'job_id': job})


def test_router_routes_cancellation_even_while_a_handler_is_starting(executor):
    executor = executor(); entered, release = threading.Event(), threading.Event(); calls = []; result = []
    job = 'job-' + 'a' * 32
    class Handler:
        def __call__(self, stage, context):
            entered.set(); release.wait(5)
            return subprocess.Popen([sys.executable, '-c', 'pass'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        def cancel(self, job_id, deadline): calls.append(job_id); release.set(); return {'status': 'recorded'}
    router = executor.StageRouter({'reproduction': Handler()})
    thread = threading.Thread(target=lambda: result.append(router('reproduction', {'job_id': job})), daemon=True)
    try:
        thread.start(); assert entered.wait(3)
        assert router.cancel(job, time.monotonic() + 5) == {'status': 'recorded'}
    finally:
        release.set(); thread.join(5)
        for process in result:
            try: process.wait(timeout=5)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    assert calls == [job]


def test_pytest_stage_accepts_only_fixed_harness_and_safe_test_nodes(executor):
    executor = executor()
    command = executor.pytest_command('regression_red', ['tests/test_regression.py::test_behavior'])
    assert command == ['/usr/local/bin/python', '-I', '/opt/beacon-repair/pytest_harness.py',
                       'regression_red', 'tests/test_regression.py::test_behavior']
    for stage, nodes in (('publish', ['tests/test_safe.py']), ('regression_red', ['../../private']),
                         ('regression_red', ['--override-ini=log_cli=true']), ('regression_red', ['tests/test_x.py;rm']),
                         ('regression_red', ['/absolute/test_x.py']), ('regression_red', [])):
        with pytest.raises(ValueError): executor.pytest_command(stage, nodes)


def test_gradle_stage_uses_only_the_fixed_image_harness(executor):
    executor = executor()
    assert executor.gradle_command('final_build') == ['/usr/local/bin/python', '-I', '/opt/beacon-repair/gradle_harness.py', 'final_build']
    with pytest.raises(ValueError): executor.gradle_command('publish')


def test_gradle_slot_remains_held_until_the_exact_container_is_absent(executor, tmp_path):
    executor = executor(); root = tmp_path/'slots'; root.mkdir(); cidfile = tmp_path/'container.cid'
    cid = 'a' * 64; cidfile.write_text(cid, encoding='ascii'); job = 'job-' + 'b' * 32; token = 'c' * 32
    slot, owner = executor.acquire_gradle_slot(root, job, token, cidfile, time.monotonic() + 5)
    assert owner['pid'] == os.getpid() and owner['external_job'] is True and (slot/'owner.json').is_file()
    class Client:
        def __init__(self): self.removed = False
        def absent(self, identity, _deadline): assert identity == cid; return self.removed
    client = Client()
    with pytest.raises(RuntimeError): executor.release_gradle_slot(client, slot, owner, time.monotonic() + 5)
    assert slot.is_dir()
    client.removed = True; executor.release_gradle_slot(client, slot, owner, time.monotonic() + 5)
    assert not slot.exists()


def test_gradle_collector_binds_real_xml_artifacts_tasks_and_unsigned_apk(executor, tmp_path):
    executor = executor(); repo = tmp_path/'repo'; repo.mkdir()
    def git(*args):
        return subprocess.run(['git', '-C', str(repo), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode().strip()
    git('init'); git('config', 'user.name', 'Fixture Owner'); git('config', 'user.email', 'owner' + chr(64) + 'example.test')
    (repo/'plugin.yaml').write_bytes(b'version: 1.2.3\n'); git('add', '.'); git('commit', '-m', 'fixture')
    runner_spec = importlib.util.spec_from_file_location('beacon_repair_runner_collector', SOURCE.parent/'repair_runner.py')
    runner = importlib.util.module_from_spec(runner_spec); runner_spec.loader.exec_module(runner)
    scratch = tmp_path/'scratch'; scratch.mkdir(); work = tmp_path/'work'; work.mkdir(); output = tmp_path/'output'; output.mkdir()
    build = scratch/'build'/'app'; xml_dir = build/'test-results'/'testDebugUnitTest'; xml_dir.mkdir(parents=True)
    (xml_dir/'TEST-fixture.xml').write_bytes(b'<testsuite><testcase classname="fixture" name="test_ok"/></testsuite>')
    reports = build/'reports'; reports.mkdir()
    for kind in ('debug', 'stable'): (reports/('lint-results-' + kind + '.xml')).write_bytes(b'<issues format="6"/>')
    names = {'stable': 'outputs/apk/stable/app-stable-unsigned.apk', 'debug': 'outputs/apk/debug/app-debug.apk',
             'androidtest': 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
    for name in names.values():
        path = build/name; path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive: archive.writestr('classes.dex', b'fixture')
    tasks = [':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:assembleStable', ':app:lintDebug', ':app:lintStable']
    log = work/'build.log'; log.write_text(''.join('> Task ' + task + '\n' for task in tasks) +
        'BEACON_GRADLE_TESTS=[["fixture","test_ok","SUCCESS"]]\nBUILD SUCCESSFUL\n', encoding='utf8')
    config = {'build_artifacts': {'stable': 'app/build/stable.apk', 'debug': 'app/build/debug.apk', 'androidtest': 'app/build/androidtest.apk'}, 'plugin_manifest': 'plugin.yaml'}
    config.update(image='sha256:' + 'a' * 64, sandbox_sha256='b' * 64, gradle_harness_sha256='c' * 64,
                  gradle_init_sha256='d' * 64, gradle_cache_manifest_sha256='e' * 64)
    context = {'release': '1.2.3', 'base_commit': '0' * 40, 'source_sha256': 'f' * 64}
    (work / 'execution.json').write_text(json.dumps({'started_ns': time.time_ns() - 10**9, 'finished_ns': time.time_ns(),
        'exit_code': 0, 'container_id': '1' * 64, 'token': '2' * 32, 'image': config['image'], 'sandbox_sha256': config['sandbox_sha256'],
        'harness_sha256': config['gradle_harness_sha256'], 'init_sha256': config['gradle_init_sha256'],
        'cache_manifest_sha256': config['gradle_cache_manifest_sha256'], 'command': executor.gradle_command('final_build'),
        'source_sha256': context['source_sha256'], 'inputs': {'plugin.yaml': hashlib.sha256((repo / 'plugin.yaml').read_bytes()).hexdigest()}}))
    lint_hashes = executor.collect_gradle_build(repo, scratch, log, output, work, config, context, runner)
    policy = {'release': '1.2.3', 'build_artifacts': config['build_artifacts'], 'required_build_tasks': tasks,
              'allowed_skips': [], 'minimum_tests': {'final_build': 1}, 'sandbox_sha256': config['sandbox_sha256']}
    files = {name: output/name for name in ('build-proof.json', 'junit.zip', 'stable.apk', 'debug.apk', 'androidtest.apk', 'build.log')}
    runner.verify_build_proof(files, policy, git('rev-parse', 'HEAD'), git('rev-parse', 'HEAD^{tree}'))
    binding = {'job_id': 'job-' + 'a' * 32, 'request_key': 'b' * 64, 'policy_sha256': 'c' * 64, 'source_sha256': 'd' * 64}
    (output/'provenance.json').write_text(json.dumps({'lint_sha256': lint_hashes}), encoding='utf8')
    executor.write_receipt(output, 'final_build', binding, list(files))
    lint_output = tmp_path/'lint-output'; lint_output.mkdir()
    executor.collect_gradle_lint(output, work, lint_output, binding, runner)
    assert (lint_output/'lint-debug.xml').is_file() and (lint_output/'lint-stable.xml').is_file()
    (work/'lint-debug.xml').write_bytes(b'tampered')
    lint_bad = tmp_path/'lint-bad'; lint_bad.mkdir()
    with pytest.raises(ValueError): executor.collect_gradle_lint(output, work, lint_bad, binding, runner)
    with zipfile.ZipFile(build/names['stable'], 'a') as archive: archive.writestr('META-INF/SIGNER.RSA', b'signature')
    other, work2 = tmp_path/'other', tmp_path/'work2'; other.mkdir(); work2.mkdir()
    with pytest.raises(ValueError): executor.collect_gradle_build(repo, scratch, log, other, work2, config, context, runner)


@pytest.mark.parametrize('stale', ['junit', 'stable', 'lint'])
def test_gradle_collector_rejects_stale_outputs_even_with_a_success_log(executor, tmp_path, stale):
    ex = executor(); repo = tmp_path / 'source'; repo.mkdir()
    (repo / 'plugin.yaml').write_bytes(b'version: 1.2.3\n')
    spec = importlib.util.spec_from_file_location('collector_checks', SOURCE.parent / 'repair_runner.py')
    runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
    runner._git = lambda _root, *args: b'' if args[0] == 'status' else (b'1' * 40 if args[-1] == 'HEAD' else b'2' * 40)
    scratch, work, output = [tmp_path / name for name in ('scratch', 'work', 'output')]
    for path in (scratch, work, output): path.mkdir()
    build = scratch / 'build/app'
    xml = build / 'test-results/testDebugUnitTest/TEST-fixture.xml'; xml.parent.mkdir(parents=True)
    xml.write_bytes(b'<testsuite tests="1"><testcase classname="fixture" name="test_ok"/></testsuite>')
    apks = {'stable': 'outputs/apk/stable/app-stable-unsigned.apk', 'debug': 'outputs/apk/debug/app-debug.apk',
            'androidtest': 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
    for name in apks.values():
        path = build / name; path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, 'w') as archive: archive.writestr('classes.dex', b'fixture')
    reports = build / 'reports'; reports.mkdir()
    for name in ('debug', 'stable'): (reports / ('lint-results-' + name + '.xml')).write_bytes(b'<issues format="6"/>')
    log = work / 'build.log'
    log.write_text(''.join('> Task :app:' + task + '\n' for task in ex._GRADLE_TASKS) +
                   'BEACON_GRADLE_TESTS=[["fixture","test_ok","SUCCESS"]]\nBUILD SUCCESSFUL\n')
    config = {'build_artifacts': apks, 'plugin_manifest': 'plugin.yaml', 'image': 'sha256:' + 'a' * 64,
              'sandbox_sha256': 'b' * 64, 'gradle_harness_sha256': 'c' * 64, 'gradle_init_sha256': 'd' * 64,
              'gradle_cache_manifest_sha256': 'e' * 64}
    context = {'release': '1.2.3', 'base_commit': '1' * 40, 'source_sha256': 'f' * 64}
    execution = {'started_ns': time.time_ns() - 1000000000, 'finished_ns': time.time_ns(), 'exit_code': 0,
                 'container_id': '1' * 64, 'token': '2' * 32, 'image': config['image'], 'sandbox_sha256': config['sandbox_sha256'],
                 'harness_sha256': config['gradle_harness_sha256'], 'init_sha256': config['gradle_init_sha256'],
                 'cache_manifest_sha256': config['gradle_cache_manifest_sha256'], 'command': ex.gradle_command('final_build'),
                 'source_sha256': context['source_sha256'], 'inputs': {'plugin.yaml': hashlib.sha256((repo / 'plugin.yaml').read_bytes()).hexdigest()}}
    (work / 'execution.json').write_text(json.dumps(execution))
    control = tmp_path / 'positive-output'; control.mkdir()
    ex.collect_gradle_build(repo, scratch, log, control, work, config, context, runner)
    assert (control / 'build-proof.json').is_file()
    path = {'junit': xml, 'stable': build / apks['stable'], 'lint': reports / 'lint-results-debug.xml'}[stale]
    os.utime(path, (946684800, 946684800))
    with pytest.raises(ValueError): ex.collect_gradle_build(repo, scratch, log, output, work, config, context, runner)
    assert not any(output.iterdir())


def test_executor_receipt_binds_actual_files_to_the_runner_contract(executor, tmp_path):
    executor = executor(); output = tmp_path / 'stage'; output.mkdir()
    (output / 'junit.xml').write_bytes(b'<testsuite/>')
    context = {'job_id': 'job-' + 'a' * 32, 'request_key': 'b' * 64, 'policy_sha256': 'c' * 64, 'source_sha256': 'd' * 64}
    executor.write_receipt(output, 'regression_red', context, ['junit.xml'])
    runner_source = SOURCE.parent / 'repair_runner.py'
    spec = importlib.util.spec_from_file_location('beacon_repair_runner_receipt_test', runner_source)
    runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
    assert runner.verify_receipt(output, context, 'regression_red', ['junit.xml']) == {'junit.xml': output / 'junit.xml'}
    with pytest.raises(ValueError): executor.write_receipt(output, 'regression_red', context, ['junit.xml'])
    other = tmp_path / 'other'; other.mkdir()
    with pytest.raises(ValueError): executor.write_receipt(other, 'regression_red', context, ['../escape'])
    with pytest.raises(ValueError): executor.write_receipt(other, 'regression_red', context, ['junit.xml'])


@pytest.mark.parametrize('stage,name,size,accepted', [
    ('final_build', 'debug.apk', 96 * 1024 * 1024, True),
    ('final_build', 'debug.apk', 96 * 1024 * 1024 + 1, False),
    ('final_build', 'stable.apk', 64 * 1024 * 1024, True),
    ('final_build', 'stable.apk', 64 * 1024 * 1024 + 1, False),
    ('final_build', 'androidtest.apk', 64 * 1024 * 1024, True),
    ('final_build', 'androidtest.apk', 64 * 1024 * 1024 + 1, False),
    ('final_identity', 'stable.apk', 64 * 1024 * 1024 + 1, False),
    ('public_privacy', 'findings.json', 64 * 1024 * 1024 + 1, False),
])
def test_real_file_receipt_limits_are_exact_and_debug_stage_specific(executor, tmp_path, stage, name, size, accepted):
    ex = executor(); output = tmp_path / 'synthetic-size-fixture'; output.mkdir()
    names = sorted(ex._ARTIFACTS[stage])
    for artifact in names: (output / artifact).write_bytes(b'synthetic size control')
    with (output / name).open('r+b') as stream: stream.truncate(size)
    assert (output / name).stat().st_size == size
    context = {'job_id': 'job-' + 'a' * 32, 'request_key': 'b' * 64, 'policy_sha256': 'c' * 64, 'source_sha256': 'd' * 64}
    spec = importlib.util.spec_from_file_location('real_size_receipt_runner', SOURCE.parent / 'repair_runner.py')
    runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
    if accepted:
        ex.write_receipt(output, stage, context, names)
        assert set(runner.verify_receipt(output, context, stage, names)) == set(names)
    else:
        with pytest.raises(ValueError): ex.write_receipt(output, stage, context, names)
        assert not (output / 'receipt.json').exists()
        hashes = {}
        for artifact in names:
            with (output / artifact).open('rb') as stream:
                hashes[artifact] = hashlib.file_digest(stream, 'sha256').hexdigest()
        (output / 'receipt.json').write_text(json.dumps({**context, 'stage': stage, 'artifacts': hashes}))
        with pytest.raises(ValueError): runner.verify_receipt(output, context, stage, names)


def test_stage_context_must_match_the_registered_job_workspace_and_receipt_root(executor, tmp_path):
    executor = executor(); private = tmp_path / 'private'; private.mkdir()
    bindings, receipts, workspace = private / 'bindings', private / 'receipts', tmp_path / 'worker'
    bindings.mkdir(); receipts.mkdir(); workspace.mkdir(); job = 'job-' + 'a' * 32
    output = receipts / job / 'regression_red'; output.mkdir(parents=True)
    config = {'private_root': str(private), 'bindings_root': str(bindings), 'receipt_root': str(receipts),
              'repo_identity': 'b' * 64, 'base_commit': 'c' * 40, 'policy_sha256': 'd' * 64,
              'public_manifest': {'src/public.py': 'e' * 64}, 'test_nodes': {'regression_red': ['tests/test_x.py::test_x']},
              'stage_timeout': 120}
    binding = {'job_id': job, 'workspace': str(workspace), 'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}
    (bindings / (job + '.json')).write_text(json.dumps(binding), encoding='utf8')
    context = {'job_id': job, 'request_key': 'f' * 64, 'policy_sha256': config['policy_sha256'], 'source_sha256': '1' * 64,
               'workspace': str(workspace), 'output_dir': str(output), 'base_commit': config['base_commit'],
               'deadline_monotonic': time.monotonic() + 30, 'probe_source': 'src/public.py'}
    assert executor.validate_context(config, 'regression_red', context)['job_id'] == job
    for change in ({'workspace': str(tmp_path)}, {'output_dir': str(tmp_path)}, {'policy_sha256': '0' * 64},
                   {'probe_source': 'src/private.py'}, {'deadline_monotonic': time.monotonic() - 1}):
        with pytest.raises(ValueError): executor.validate_context(config, 'regression_red', {**context, **change})
    with pytest.raises(ValueError): executor.validate_context(config, 'publish', context)


def test_private_executor_config_is_content_pinned_and_rejects_mutation(executor, tmp_path):
    executor = executor(); path = tmp_path / 'config.json'
    config = {'schema': 1, 'mode': 'fixture'}
    path.write_text(json.dumps(config), encoding='utf8')
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    assert executor.read_pinned_json(path, digest) == config
    path.write_text(json.dumps({**config, 'owner_secret': 'must-not-appear'}), encoding='utf8')
    with pytest.raises(ValueError) as error: executor.read_pinned_json(path, digest)
    assert 'must-not-appear' not in str(error.value)


def test_executor_configuration_is_exact_and_does_not_allow_protected_or_unknown_stages(executor, tmp_path):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path)
    assert executor.load_config(path, digest)['policy_sha256'] == config['policy_sha256']
    for changed in ({**config, 'unexpected': True}, {**config, 'test_nodes': {'publish': ['tests/new.py']}},
                    {**config, 'baseline_paths': ['tests/new.py']}, {**config, 'editable_paths': ['tests/conftest.py']},
                    {**config, 'gradle_harness_sha256': '0' * 64}):
        path.write_text(json.dumps(changed, sort_keys=True), encoding='utf8')
        with pytest.raises(ValueError): executor.load_config(path, hashlib.sha256(path.read_bytes()).hexdigest())
    changed = copy.deepcopy(config); changed['public_manifest']['app/build.gradle.kts'] = '0' * 64
    changed['editable_paths'].append('app/build.gradle.kts')
    path.write_text(json.dumps(changed, sort_keys=True), encoding='utf8')
    with pytest.raises(ValueError): executor.load_config(path, hashlib.sha256(path.read_bytes()).hexdigest())


def test_runner_adapter_spawns_a_private_bounded_worker_without_forwarding_secrets(executor, tmp_path, monkeypatch):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path)
    workspace = tmp_path / 'worker'; workspace.mkdir(); job = 'job-' + 'a' * 32
    output = Path(config['receipt_root']) / job / 'regression_red'; output.mkdir(parents=True)
    binding = {'job_id': job, 'workspace': str(workspace), 'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}
    (Path(config['bindings_root']) / (job + '.json')).write_text(json.dumps(binding), encoding='utf8')
    context = {'job_id': job, 'request_key': '1' * 64, 'policy_sha256': config['policy_sha256'], 'source_sha256': '2' * 64,
               'workspace': str(workspace), 'output_dir': str(output), 'base_commit': config['base_commit'],
               'deadline_monotonic': time.monotonic() + 30, 'probe_source': 'src/public.py', 'owner_secret': 'do-not-persist'}
    monkeypatch.setenv('BEACON_PRIVATE_FIXTURE', 'do-not-inherit')
    actual_popen = subprocess.Popen; observed = []
    def spawn(args, **kwargs):
        if 'BEACON_PRIVATE_FIXTURE' in kwargs['env']: pytest.fail('Unexpected inherited environment', pytrace=False)
        assert kwargs['stdout'] == subprocess.DEVNULL and kwargs['stderr'] == subprocess.DEVNULL
        observed.append(args)
        return actual_popen([sys.executable, '-c', 'pass'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    monkeypatch.setattr(executor.subprocess, 'Popen', spawn)
    adapter = executor.DockerStageExecutor(path, digest, FixtureProcessOwner)
    process = adapter('regression_red', context)
    try: assert process.wait(timeout=10) == 0
    finally:
        if process.poll() is None: process.kill(); process.wait(timeout=10)
    args = observed[0]; assert '--worker' in args and args[0] == config['python_binary']
    request = Path(args[-2]); assert request.is_file() and 'do-not-persist' not in request.read_text(encoding='utf8')
    with pytest.raises(ValueError): adapter('publish', context)


def test_cancel_before_launch_is_durable_and_prevents_any_worker_start(executor, tmp_path, monkeypatch):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path)
    workspace = tmp_path / 'worker'; workspace.mkdir(); job = 'job-' + 'a' * 32
    output = Path(config['receipt_root']) / job / 'regression_red'; output.mkdir(parents=True)
    binding = {'job_id': job, 'workspace': str(workspace), 'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}
    (Path(config['bindings_root']) / (job + '.json')).write_text(json.dumps(binding), encoding='utf8')
    context = {'job_id': job, 'request_key': '1' * 64, 'policy_sha256': config['policy_sha256'], 'source_sha256': '2' * 64,
               'workspace': str(workspace), 'output_dir': str(output), 'base_commit': config['base_commit'],
               'deadline_monotonic': time.monotonic() + 30, 'probe_source': 'src/public.py'}
    adapter = executor.DockerStageExecutor(path, digest, FixtureProcessOwner)
    assert adapter.cancel(job, time.monotonic() + 5) == {'status': 'recorded'}
    monkeypatch.setattr(executor.subprocess, 'Popen', lambda *_a, **_kw: pytest.fail('Cancelled job started a worker'))
    with pytest.raises(ValueError): adapter('regression_red', context)


def test_cancellation_remains_available_after_private_config_changes(executor, tmp_path):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path); job = 'job-' + 'a' * 32
    workspace = tmp_path / 'worker'; workspace.mkdir()
    (Path(config['bindings_root']) / (job + '.json')).write_text(json.dumps({'job_id': job, 'workspace': str(workspace),
        'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}), encoding='utf8')
    adapter = executor.DockerStageExecutor(path, digest, FixtureProcessOwner)
    path.write_text('{"changed":true}', encoding='utf8')
    assert adapter.cancel(job, time.monotonic() + 5) == {'status': 'recorded'}


def test_active_job_cancellation_does_not_depend_on_a_later_binding_file(executor, tmp_path, monkeypatch):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path)
    workspace = tmp_path/'worker'; workspace.mkdir(); job = 'job-' + 'a' * 32
    output = Path(config['receipt_root'])/job/'regression_red'; output.mkdir(parents=True)
    binding = Path(config['bindings_root'])/(job + '.json')
    binding.write_text(json.dumps({'job_id': job, 'workspace': str(workspace), 'repo_identity': config['repo_identity'],
                                   'base_commit': config['base_commit']}), encoding='utf8')
    context = {'job_id': job, 'request_key': '1' * 64, 'policy_sha256': config['policy_sha256'], 'source_sha256': '2' * 64,
               'workspace': str(workspace), 'output_dir': str(output), 'base_commit': config['base_commit'],
               'deadline_monotonic': time.monotonic() + 30, 'probe_source': 'src/public.py'}
    actual_popen = subprocess.Popen
    monkeypatch.setattr(executor.subprocess, 'Popen', lambda *_a, **_kw: actual_popen([sys.executable, '-c', 'pass'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL))
    adapter = executor.DockerStageExecutor(path, digest, FixtureProcessOwner); process = adapter('regression_red', context)
    try:
        process.wait(timeout=5); binding.unlink()
        assert adapter.cancel(job, time.monotonic() + 5) == {'status': 'pending'}
    finally:
        if process.poll() is None: process.kill(); process.wait(timeout=5)


def test_failed_process_monitor_setup_terminates_the_owned_worker(executor, tmp_path, monkeypatch):
    executor = executor(); config, path, digest = executor_config_fixture(tmp_path)
    workspace = tmp_path / 'worker'; workspace.mkdir(); job = 'job-' + 'a' * 32
    output = Path(config['receipt_root']) / job / 'regression_red'; output.mkdir(parents=True)
    (Path(config['bindings_root']) / (job + '.json')).write_text(json.dumps({'job_id': job, 'workspace': str(workspace),
        'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}), encoding='utf8')
    context = {'job_id': job, 'request_key': '1' * 64, 'policy_sha256': config['policy_sha256'], 'source_sha256': '2' * 64,
               'workspace': str(workspace), 'output_dir': str(output), 'base_commit': config['base_commit'],
               'deadline_monotonic': time.monotonic() + 30, 'probe_source': 'src/public.py'}
    actual_popen = subprocess.Popen; started = []
    def spawn(*_args, **_kwargs):
        process = actual_popen([sys.executable, '-c', 'import time; time.sleep(60)'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        started.append(process); return process
    class BrokenThread:
        def __init__(self, *_args, **_kwargs): pass
        def start(self): raise RuntimeError('fixture')
    monkeypatch.setattr(executor.subprocess, 'Popen', spawn); monkeypatch.setattr(executor.threading, 'Thread', BrokenThread)
    adapter = executor.DockerStageExecutor(path, digest, FixtureProcessOwner)
    try:
        with pytest.raises((ValueError, RuntimeError)): adapter('regression_red', context)
        assert started and started[0].poll() is not None
    finally:
        for process in started:
            if process.poll() is None: process.kill(); process.wait(timeout=5)


def test_protected_harness_runs_real_red_and_green_with_a_clean_environment(tmp_path, monkeypatch):
    assert HARNESS.is_file(), 'The fixed pytest harness has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_pytest_harness_test', HARNESS)
    harness = importlib.util.module_from_spec(spec); spec.loader.exec_module(harness)
    root = tmp_path / 'workspace'; root.mkdir(); test = root / 'test_case.py'
    config = tmp_path / 'pytest.ini'; config.write_text('[pytest]\n', encoding='utf8')
    monkeypatch.setenv('BEACON_SECRET_FIXTURE', 'must-not-reach-test')
    test.write_text('import os\ndef test_case():\n    assert "BEACON_SECRET_FIXTURE" not in os.environ\n    assert False\n', encoding='utf8')
    red = tmp_path / 'red'; red.mkdir()
    assert harness.run('regression_red', ['test_case.py::test_case'], root, red, config) == 1
    assert (red / 'junit.xml').is_file()
    assert next(ET.parse(red / 'junit.xml').getroot().iter('testcase')).get('classname') == 'test_case'
    test.write_text('import os\ndef test_case():\n    assert "BEACON_SECRET_FIXTURE" not in os.environ\n', encoding='utf8')
    green = tmp_path / 'green'; green.mkdir()
    assert harness.run('regression_green', ['test_case.py::test_case'], root, green, config) == 0
    assert (green / 'junit.xml').is_file()


def test_gradle_harness_uses_only_the_fixed_offline_six_task_command():
    assert GRADLE_HARNESS.is_file(), 'The fixed Gradle harness has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_gradle_harness_test', GRADLE_HARNESS)
    harness = importlib.util.module_from_spec(spec); spec.loader.exec_module(harness)
    command = harness.command()
    assert '--offline' in command and '--rerun-tasks' in command and '--max-workers=2' in command
    assert '-Pkotlin.compiler.execution.strategy=in-process' in command
    assert '-Pkotlin.project.persistent.dir=/scratch/kotlin' in command
    assert command[-6:] == ['testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'assembleStable', 'lintDebug', 'lintStable']
    assert '/opt/beacon-repair/gradle-init.gradle' in command


def test_gradle_harness_rejects_unsupported_environment_before_build(monkeypatch):
    spec = importlib.util.spec_from_file_location('unsupported_gradle_environment', GRADLE_HARNESS)
    harness = importlib.util.module_from_spec(spec); spec.loader.exec_module(harness)
    monkeypatch.setattr(harness.sys, 'platform', 'unsupported-platform')
    monkeypatch.setattr(harness.subprocess, 'run', lambda *_a, **_k: pytest.fail('Unsupported environment launched a build'))
    with pytest.raises(ValueError, match='Unsupported build environment'): harness.check_environment()


def test_gradle_cache_copy_verifies_every_public_dependency_hash(tmp_path):
    spec = importlib.util.spec_from_file_location('beacon_gradle_cache_test', GRADLE_HARNESS)
    harness = importlib.util.module_from_spec(spec); spec.loader.exec_module(harness)
    cache = tmp_path/'cache'; (cache/'files').mkdir(parents=True); (cache/'files'/'artifact.jar').write_bytes(b'public-artifact')
    manifest = tmp_path/'manifest.json'; manifest.write_text(json.dumps({'files/artifact.jar': hashlib.sha256(b'public-artifact').hexdigest()}), encoding='utf8')
    scratch = tmp_path/'scratch'; scratch.mkdir()
    harness._prepare_cache(cache, manifest, scratch)
    assert (scratch/'gradle-home/caches/modules-2/files/artifact.jar').read_bytes() == b'public-artifact'
    (cache/'files'/'artifact.jar').write_bytes(b'tampered')
    other = tmp_path/'other'; other.mkdir()
    with pytest.raises(ValueError): harness._prepare_cache(cache, manifest, other)


@pytest.mark.skipif(os.environ.get('BEACON_REPAIR_TEST_DOCKER') != '1', reason='requires an explicitly enabled local Docker daemon')
def test_real_combined_container_probe_and_owned_cleanup(executor, tmp_path):
    executor = executor(); private = tmp_path / 'private'; private.mkdir(); config = private / 'docker-config'; config.mkdir()
    source, scratch = private / 'export', private / 'scratch'; source.mkdir(); scratch.mkdir()
    (source / 'probe.txt').write_bytes(b'public-fixture\n')
    canary = private / 'canary'; canary.write_bytes(b'private-fixture')
    binary = Path(shutil.which('docker')).resolve(strict=True)
    host = 'npipe:////./pipe/dockerDesktopLinuxEngine' if os.name == 'nt' else 'unix:///var/run/docker.sock'
    client = executor.DockerClient(binary, hashlib.sha256(binary.read_bytes()).hexdigest(), host, config, private)
    token = secrets.token_hex(16)
    image = os.environ.get('BEACON_REPAIR_TEST_IMAGE', 'sha256:90744cff8f32887f075c47d747a173ff333e9e98801667af93c357fa9f5e28ff')
    inspected_image = json.loads(client._run(['image', 'inspect', image], time.monotonic() + 20))[0]
    assert inspected_image['Id'] == image
    expected = {'image': image, 'user': '65534:65534', 'env': inspected_image['Config']['Env'],
                'mounts': {'/workspace': {'source': str(source), 'writable': False}, '/scratch': {'source': str(scratch), 'writable': True}},
                'memory': 268435456, 'nano_cpus': 1000000000, 'pids': 64, 'tmpfs': {'/tmp': 'rw,noexec,nosuid,size=32m'},
                'name': 'beacon-repair-' + token, 'token': token, 'job_id': 'job-' + 'c' * 32, 'stage': 'calibration',
                'entrypoint': ['/usr/local/bin/python'], 'cmd': ['-I', '-S', '-c', 'pass']}
    cidfile = private / 'calibration.cid'; deadline = time.monotonic() + 90; cid = None
    try:
        cid, profile = client.create(expected, cidfile, deadline, None)
        digest = executor.profile_sha256(profile)
        assert executor.run_owned(client, cid, expected, digest, deadline) == 0
    finally:
        if cid:
            executor.stop_owned(client, cid, expected, time.monotonic() + 20)
            client.remove_owned(cid, expected, time.monotonic() + 20)
    token = secrets.token_hex(16); expected.update(name='beacon-repair-' + token, token=token, stage='isolation')
    harness_hash = hashlib.sha256(HARNESS.read_bytes()).hexdigest() if os.environ.get('BEACON_REPAIR_TEST_HARNESS') == '1' else None
    proof = executor.isolation_probe(client, expected, private / 'probe.cid', digest, 'probe.txt', canary, time.monotonic() + 90, harness_sha256=harness_hash)
    assert proof['read_private_errno'] == 'ENOENT' and proof['write_source_errno'] == 'EROFS'
    assert proof['network_errno'] == 'ENETUNREACH' and proof['host_loopback_errno'] == 'ECONNREFUSED'
    assert proof['scratch_write_bytes'] == proof['sandbox_loopback_bytes'] == proof['host_loopback_positive_bytes'] == 1
    assert proof['profile_sha256'] == digest and canary.read_bytes() == b'private-fixture'
    assert executor.profile_sha256(proof['applied_profile']) == digest
    if harness_hash: assert proof['harness_sha256'] == harness_hash
    assert client.absent((private / 'probe.cid').read_text(encoding='ascii').strip(), time.monotonic() + 10)
    if harness_hash:
        test_file = source / 'test_case.py'
        service = source / 'service.py'; service.write_bytes(b'def value():\n    return 1\n')
        test_file.write_bytes(b'from service import value\ndef test_case():\n    assert value() == 2\n')
        spec = importlib.util.spec_from_file_location('protected_model_control', SOURCE.parent / 'repair_runner.py')
        runner = importlib.util.module_from_spec(spec); spec.loader.exec_module(runner)
        candidate = json.dumps({'edits': [
            {'path': 'service.py', 'before_sha256': hashlib.sha256(service.read_bytes()).hexdigest(), 'content': 'def value():\n    return 2\n'},
            {'path': 'test_new.py', 'before_sha256': None, 'content': test_file.read_text(encoding='utf8')}]})
        edits = runner.validate_proposal(candidate, source, {'max_files': 2, 'max_patch_bytes': 8192, 'max_file_bytes': 4096,
            'source_paths': ['service.py'], 'test_paths': ['test_new.py']})
        (source / 'test_new.py').write_bytes(edits[1]['content'])
        for stage, node, wanted in (('reproduction', 'test_case.py::test_case', 1), ('regression_red', 'test_new.py::test_case', 1),
                                     ('regression_green', 'test_new.py::test_case', 0), ('reproduction_green', 'test_case.py::test_case', 0)):
            if stage == 'regression_green': service.write_bytes(edits[0]['content'])
            stage_scratch = private / (stage + '-scratch'); stage_scratch.mkdir()
            token = secrets.token_hex(16); command = executor.pytest_command(stage, [node])
            expected.update(name='beacon-repair-' + token, token=token, stage=stage,
                            entrypoint=command[:1], cmd=command[1:])
            expected['mounts']['/scratch']['source'] = str(stage_scratch)
            stage_cid = None; stage_file = private / (stage + '.cid')
            try:
                stage_cid, _ = client.create(expected, stage_file, time.monotonic() + 90, digest)
                assert executor.run_owned(client, stage_cid, expected, digest, time.monotonic() + 90) == wanted
                report = stage_scratch / 'junit.xml'
                assert report.is_file()
                suite = ET.parse(report).getroot()
                assert len(list(suite.iter('testcase'))) == 1 and not list(suite.iter('error'))
                assert len(list(suite.iter('failure'))) == wanted
                runner.verify_junit(report, [node.split('::')[0][:-3] + '.test_case'], red=wanted == 1, exit_code=wanted, allowed_skips=[])
                # These are receipts for actual sandbox XML, scoped only to the synthetic control.
                output = private / (stage + '-evidence'); output.mkdir()
                (output / 'junit.xml').write_bytes(report.read_bytes())
                context = {'job_id': expected['job_id'], 'request_key': 'a' * 64, 'policy_sha256': 'b' * 64,
                           'source_sha256': hashlib.sha256(service.read_bytes() + test_file.read_bytes()).hexdigest()}
                executor.write_receipt(output, stage, context, ['junit.xml'])
                runner.verify_receipt(output, context, stage, ['junit.xml'])
            finally:
                if stage_cid:
                    executor.stop_owned(client, stage_cid, expected, time.monotonic() + 20)
                    client.remove_owned(stage_cid, expected, time.monotonic() + 20)


@pytest.mark.skipif(os.environ.get('BEACON_REPAIR_TEST_DOCKER') != '1' or os.environ.get('BEACON_REPAIR_TEST_HARNESS') != '1',
                    reason='requires the explicitly enabled local trusted toolchain image')
def test_real_runner_adapter_isolation_and_pytest_stage_receipts(executor, tmp_path):
    executor = executor(); private = tmp_path / 'private'; private.mkdir()
    operator_path = SOURCE.parent/'repair_operator.py'
    operator_hash = hashlib.sha256(operator_path.read_bytes()).hexdigest() if os.environ.get('BEACON_REPAIR_REAL_OWNER') == '1' else None
    for name in ('bindings', 'receipts', 'docker-config', 'cal-source', 'cal-scratch'): (private / name).mkdir()
    repo, workspace = tmp_path / 'repo', tmp_path / 'worker'; repo.mkdir()
    def git(*args):
        return subprocess.run(['git', '-C', str(repo), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode().strip()
    git('init'); git('config', 'user.name', 'Fixture Owner'); git('config', 'user.email', 'owner' + chr(64) + 'example.test'); git('config', 'core.autocrlf', 'false')
    (repo / 'src').mkdir(); (repo / 'tests').mkdir()
    (repo / 'src/public.py').write_bytes(b'public\n')
    (repo / 'tests/test_existing.py').write_bytes(b'def test_case():\n    assert 1 == 2\n')
    (repo / 'tests/test_slow.py').write_bytes(b'import time\ndef test_slow():\n    time.sleep(60)\n')
    git('add', '.'); git('commit', '-m', 'baseline'); base = git('rev-parse', 'HEAD'); git('worktree', 'add', '--detach', str(workspace), 'HEAD')
    runner_spec = importlib.util.spec_from_file_location('beacon_repair_runner_integration', SOURCE.parent / 'repair_runner.py')
    runner = importlib.util.module_from_spec(runner_spec); runner_spec.loader.exec_module(runner)
    binary = Path(shutil.which('docker')).resolve(strict=True)
    host = 'npipe:////./pipe/dockerDesktopLinuxEngine' if os.name == 'nt' else 'unix:///var/run/docker.sock'
    client = executor.DockerClient(binary, hashlib.sha256(binary.read_bytes()).hexdigest(), host, private / 'docker-config', private)
    image = os.environ['BEACON_REPAIR_TEST_IMAGE']; image_info = json.loads(client._run(['image', 'inspect', image], time.monotonic() + 20))[0]
    token = secrets.token_hex(16); job = 'job-' + 'a' * 32
    calibration = {'image': image, 'user': '65534:65534', 'env': image_info['Config']['Env'],
                   'mounts': {'/workspace': {'source': str(private / 'cal-source'), 'writable': False},
                              '/scratch': {'source': str(private / 'cal-scratch'), 'writable': True}},
                   'memory': 268435456, 'nano_cpus': 1000000000, 'pids': 64, 'tmpfs': {'/tmp': 'rw,noexec,nosuid,size=32m'},
                   'name': 'beacon-repair-' + token, 'token': token, 'job_id': job, 'stage': 'calibration',
                   'entrypoint': ['/usr/local/bin/python'], 'cmd': ['-I', '-S', '-c', 'pass']}
    cid = None
    try:
        cid, profile = client.create(calibration, private / 'cal.cid', time.monotonic() + 90, None)
        sandbox_hash = executor.profile_sha256(profile)
        assert executor.run_owned(client, cid, calibration, sandbox_hash, time.monotonic() + 90) == 0
    finally:
        if cid:
            executor.stop_owned(client, cid, calibration, time.monotonic() + 20)
            client.remove_owned(cid, calibration, time.monotonic() + 20)
    identity = runner.repository_identity(repo); policy_hash = 'd' * 64
    manifest = {'src/public.py': hashlib.sha256(b'public\n').hexdigest(),
                'tests/test_existing.py': hashlib.sha256(b'def test_case():\n    assert 1 == 2\n').hexdigest(),
                'tests/test_slow.py': hashlib.sha256(b'import time\ndef test_slow():\n    time.sleep(60)\n').hexdigest(), 'tests/test_new.py': None}
    config = {'schema': 1, 'private_root': str(private), 'bindings_root': str(private/'bindings'), 'receipt_root': str(private/'receipts'),
              'coordinator_root': str(repo), 'repo_identity': identity, 'base_commit': base, 'policy_sha256': policy_hash,
              'runner_sha256': hashlib.sha256((SOURCE.parent/'repair_runner.py').read_bytes()).hexdigest(), 'executor_sha256': hashlib.sha256(SOURCE.read_bytes()).hexdigest(),
              'python_binary': str(Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True)),
              'python_sha256': hashlib.sha256(Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True).read_bytes()).hexdigest(),
              'docker_binary': str(binary), 'docker_sha256': hashlib.sha256(binary.read_bytes()).hexdigest(), 'docker_host': host,
              'docker_config_dir': str(private/'docker-config'), 'image': image, 'image_env': image_info['Config']['Env'],
              'sandbox_sha256': sandbox_hash, 'harness_sha256': hashlib.sha256(HARNESS.read_bytes()).hexdigest(),
              'memory': 268435456, 'nano_cpus': 1000000000, 'pids': 64, 'stage_timeout': 120, 'public_manifest': manifest,
              'editable_paths': ['src/public.py', 'tests/test_new.py'], 'release_paths': [], 'baseline_paths': ['tests/test_existing.py'],
              'test_nodes': {'reproduction': ['tests/test_existing.py::test_case'], 'regression_green': ['tests/test_new.py::test_case'],
                             'final_unit': ['tests/test_slow.py::test_slow']},
              'native_root': None, 'native_commit': None, 'native_manifest': {}, 'gradle_harness_sha256': None,
              'gradle_init_sha256': None, 'gradle_cache_manifest_sha256': None, 'build_artifacts': None, 'plugin_manifest': None, 'gradle_slot_root': None}
    config_path = private/'config.json'; config_path.write_text(json.dumps(config, sort_keys=True), encoding='utf8')
    config_sha = hashlib.sha256(config_path.read_bytes()).hexdigest()
    (private/'bindings'/(job + '.json')).write_text(json.dumps({'job_id': job, 'workspace': str(workspace), 'repo_identity': identity, 'base_commit': base}), encoding='utf8')
    runner_job = private/'receipts'/job; runner_job.mkdir()
    runner_canary = runner_job/'canary'; runner_canary.write_bytes(b'runner-private-fixture')
    adapter = executor.DockerStageExecutor(config_path, config_sha, integration_process_owner())
    def stage(name, wanted):
        output = private/'receipts'/job/name; output.mkdir(parents=True)
        context = {'job_id': job, 'request_key': 'e' * 64, 'policy_sha256': policy_hash,
                   'source_sha256': runner._snapshot(workspace, set(config['editable_paths']), ()), 'workspace': str(workspace),
                   'output_dir': str(output), 'base_commit': base, 'deadline_monotonic': time.monotonic() + 115, 'probe_source': 'src/public.py'}
        process = adapter(name, context)
        assert process.stdout is None and process.stderr is None
        assert process.wait(timeout=110) == wanted
        artifacts = ['isolation.json'] if name == 'isolation' else ['junit.xml']
        binding = {key: context[key] for key in ('job_id', 'request_key', 'policy_sha256', 'source_sha256')}
        runner.verify_receipt(output, binding, name, artifacts)
        return output
    isolation = stage('isolation', 0)
    isolation_proof = json.loads((isolation/'isolation.json').read_text(encoding='utf8'))
    assert isolation_proof['profile_sha256'] == sandbox_hash
    runner.verify_isolation(isolation_proof, {'sandbox_sha256': sandbox_hash, 'public_files': {'src/public.py': manifest['src/public.py']}},
                            runner_canary, b'runner-private-fixture')
    repro = stage('reproduction', 1)
    runner.verify_junit(repro/'junit.xml', ['tests.test_existing.test_case'], red=True, exit_code=1, allowed_skips=[])
    (workspace/'tests/test_new.py').write_bytes(b'def test_case():\n    assert 1 == 1\n')
    green = stage('regression_green', 0)
    runner.verify_junit(green/'junit.xml', ['tests.test_new.test_case'], red=False, exit_code=0, allowed_skips=[])
    output = private/'receipts'/job/'final_unit'; output.mkdir(parents=True)
    context = {'job_id': job, 'request_key': 'e' * 64, 'policy_sha256': policy_hash,
               'source_sha256': runner._snapshot(workspace, set(config['editable_paths']), ()), 'workspace': str(workspace),
               'output_dir': str(output), 'base_commit': base, 'deadline_monotonic': time.monotonic() + 115, 'probe_source': 'src/public.py'}
    process = adapter('final_unit', context); active_file = private/'executor-jobs'/job/'active.json'; cid = None
    try:
        deadline = time.monotonic() + 35
        while time.monotonic() < deadline:
            active = json.loads(active_file.read_text(encoding='utf8'))
            cidfile = Path(active['cidfile'])
            if cidfile.is_file():
                cid = cidfile.read_text(encoding='ascii').strip()
                if len(cid) == 64 and all(char in '0123456789abcdef' for char in cid) and client.inspect(cid, time.monotonic() + 10)['State']['Running']: break
            if process.poll() is not None: pytest.fail('Slow stage ended before cancellation')
            time.sleep(.1)
        else: pytest.fail('Slow stage did not start')
        assert adapter.cancel(job, time.monotonic() + 30) == {'status': 'stopped'}
        assert process.wait(timeout=30) != 0
        assert not (output/'receipt.json').exists()
        assert client.absent(cid, time.monotonic() + 10)
    finally:
        if process.poll() is None:
            adapter.cancel(job, time.monotonic() + 30)
            try: process.wait(timeout=30)
            except subprocess.TimeoutExpired: process.terminate(); process.wait(timeout=10)
    if operator_hash: assert hashlib.sha256(operator_path.read_bytes()).hexdigest() == operator_hash


def test_only_pinned_public_files_are_exported_and_immutable_drift_is_rejected(executor, tmp_path):
    executor = executor(); workspace = tmp_path / 'workspace'; (workspace / 'src').mkdir(parents=True)
    (workspace / 'src/public.py').write_bytes(b'public\n')
    (workspace / 'src/private.env').write_bytes(b'private-canary\n')
    manifest = {'src/public.py': hashlib.sha256(b'public\n').hexdigest()}
    output = tmp_path / 'export'
    result = executor.export_public_source(workspace, output, manifest, editable=set())
    assert (output / 'src/public.py').read_bytes() == b'public\n'
    assert not (output / 'src/private.env').exists()
    assert result == manifest
    (workspace / 'src/public.py').write_bytes(b'changed\n')
    with pytest.raises(ValueError): executor.export_public_source(workspace, tmp_path / 'other', manifest, editable=set())
    editable = executor.export_public_source(workspace, tmp_path / 'editable', manifest, editable={'src/public.py'})
    assert editable['src/public.py'] == hashlib.sha256(b'changed\n').hexdigest()


def test_export_refuses_paths_escape_private_or_hardlinked_files(executor, tmp_path):
    executor = executor(); workspace = tmp_path / 'workspace'; (workspace / 'src').mkdir(parents=True)
    private = tmp_path / 'private'; private.write_bytes(b'outside\n')
    (workspace / 'src/public.py').hardlink_to(private)
    digest = hashlib.sha256(b'outside\n').hexdigest()
    for name in ('../private', 'Grok.txt', '.git/config', 'src/public.py', 'src/x.py.'):
        with pytest.raises(ValueError): executor.export_public_source(workspace, tmp_path / ('out-' + str(len(name))), {name: digest}, editable=set())
    assert private.read_bytes() == b'outside\n'


def test_model_scope_cannot_edit_pytest_harness_configuration(executor, tmp_path):
    executor = executor(); workspace = tmp_path / 'workspace'; (workspace / 'tests').mkdir(parents=True)
    (workspace / 'tests/conftest.py').write_bytes(b'baseline\n')
    manifest = {'tests/conftest.py': hashlib.sha256(b'baseline\n').hexdigest()}
    with pytest.raises(ValueError):
        executor.export_public_source(workspace, tmp_path / 'export', manifest, editable={'tests/conftest.py'})


def test_source_export_stops_before_reading_when_cancelled_or_expired(executor, tmp_path):
    executor = executor(); workspace = tmp_path / 'workspace'; workspace.mkdir()
    (workspace / 'public.py').write_bytes(b'public\n'); manifest = {'public.py': hashlib.sha256(b'public\n').hexdigest()}
    with pytest.raises(ValueError): executor.export_public_source(workspace, tmp_path / 'cancelled', manifest, editable=set(), cancelled=lambda: True)
    with pytest.raises(ValueError): executor.export_public_source(workspace, tmp_path / 'expired', manifest, editable=set(), deadline=time.monotonic() - 1)
