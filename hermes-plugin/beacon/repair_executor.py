"""Fixed-command, operator-bound execution for isolated repair jobs.

This module does not configure a model, public repository, credentials or publisher.
"""
from contextlib import contextmanager
import hashlib
import importlib.util
import json
import math
import os
import re
import socket
import subprocess
import sys
import secrets
import threading
import time
import xml.etree.ElementTree as ET
import zipfile
from decimal import Decimal
from pathlib import Path


_DIGEST = re.compile(r'sha256:[0-9a-f]{64}\Z')
_CID = re.compile(r'[0-9a-f]{64}\Z')
_TOKEN = re.compile(r'[0-9a-f]{32}\Z')
_JOB = re.compile(r'job-[0-9a-f]{32}\Z')
_ENV = frozenset({'PATH', 'LANG', 'GPG_KEY', 'PYTHON_VERSION', 'PYTHON_SHA256', 'HOME', 'TMPDIR',
                  'PYTHONDONTWRITEBYTECODE', 'PYTEST_DISABLE_PLUGIN_AUTOLOAD', 'JAVA_HOME', 'ANDROID_HOME',
                  'ANDROID_SDK_ROOT', 'ANDROID_USER_HOME', 'GRADLE_USER_HOME', 'GRADLE_HOME', 'HERMES_HOME'})
_PATH_PART = re.compile(r'[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}\Z')
_SHA256 = re.compile(r'[0-9a-f]{64}\Z')
_DENIED_PARTS = frozenset({'venv', '.venv', '.git', '.gradle', '.codex', '.claude', '.unlazy', 'node_modules',
                           'build', 'dist', 'credentials', 'secrets', 'signing'})
_STAGES = frozenset({'isolation', 'public_privacy', 'reproduction', 'candidate_review', 'candidate_scan', 'regression_red',
                     'regression_green', 'reproduction_green', 'prepare_release', 'commit', 'final_reproduction', 'final_regression',
                     'final_unit', 'final_plugin', 'final_build', 'final_lint', 'final_device', 'final_live', 'final_review',
                     'final_gitleaks', 'final_privacy', 'final_identity', 'release_preflight', 'publish', 'fresh_downloads'})
_PYTEST_STAGES = frozenset({'reproduction', 'regression_red', 'regression_green', 'reproduction_green',
                            'final_reproduction', 'final_regression', 'final_unit', 'final_plugin'})
SANDBOX_STAGES = _PYTEST_STAGES | {'isolation', 'final_build', 'final_lint'}
_ARTIFACTS = {stage: frozenset({'junit.xml'}) for stage in _PYTEST_STAGES}
_ARTIFACTS.update({'isolation': frozenset({'isolation.json'}),
                   'public_privacy': frozenset({'findings.json', 'positive.json', 'paths.json'}),
                   'candidate_scan': frozenset({'findings.json', 'positive.json', 'paths.json'}),
                   'candidate_review': frozenset({'ocr.json', 'paths.json'}),
                   'prepare_release': frozenset({'version.json'}), 'commit': frozenset({'commit.json'}),
                   'final_build': frozenset({'build-proof.json', 'junit.zip', 'stable.apk', 'debug.apk', 'androidtest.apk', 'build.log'}),
                   'final_lint': frozenset({'lint-debug.xml', 'lint-stable.xml'}),
                   'final_device': frozenset({'junit.xml'}), 'final_live': frozenset({'junit.xml'}),
                   'final_review': frozenset({'ocr.json', 'paths.json'}),
                   'final_gitleaks': frozenset({'findings.json', 'positive.json', 'paths.json'}),
                   'final_privacy': frozenset({'findings.json', 'positive.json', 'paths.json'}),
                   'final_identity': frozenset({'metadata.json', 'stable.apk', 'aapt.txt', 'signer.txt', 'plugin.yaml'}),
                   'release_preflight': frozenset({'preflight.json'}), 'publish': frozenset({'release.json', 'refs.json'}),
                   'fresh_downloads': frozenset({'download.apk', 'download.json', 'checksums.txt', 'release.json'})})
_CONFIG_KEYS = frozenset({'schema', 'private_root', 'bindings_root', 'receipt_root', 'coordinator_root', 'repo_identity', 'base_commit',
                          'policy_sha256', 'runner_sha256', 'executor_sha256', 'python_binary', 'python_sha256', 'docker_binary', 'docker_sha256', 'docker_host',
                          'docker_config_dir', 'image', 'image_env', 'sandbox_sha256', 'harness_sha256', 'memory', 'nano_cpus', 'pids',
                          'stage_timeout', 'public_manifest', 'editable_paths', 'release_paths', 'baseline_paths', 'test_nodes',
                          'native_root', 'native_commit', 'native_manifest', 'gradle_harness_sha256', 'gradle_init_sha256',
                          'gradle_cache_manifest_sha256', 'build_artifacts', 'plugin_manifest', 'gradle_slot_root'})
_COMMIT = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')
_GRADLE_TASKS = ('testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest', 'assembleStable', 'lintDebug', 'lintStable')
_PROBE_SCRIPT = r'''import errno, hashlib, json, pathlib, socket, sys
out = {}
def file_probe(path, mode, key):
    try:
        with open(path, mode) as handle:
            if "w" in mode: handle.write(b"X")
            else: handle.read(1)
        out[key] = "ALLOWED"
    except OSError as error: out[key] = errno.errorcode.get(error.errno, str(error.errno))
file_probe("/__beacon_private__/canary", "rb", "read_private_errno")
file_probe("/__beacon_private__/canary", "wb", "write_private_errno")
source = pathlib.Path("/workspace").joinpath(*sys.argv[1].split("/"))
file_probe(str(source), "wb", "write_source_errno")
out["source_sha256"] = hashlib.sha256(source.read_bytes()).hexdigest()
if sys.argv[5] == "harness":
    out["harness_sha256"] = hashlib.sha256(pathlib.Path("/opt/beacon-repair/pytest_harness.py").read_bytes()).hexdigest()
if sys.argv[6] == "gradle":
    for key, name in (("gradle_harness_sha256", "gradle_harness.py"), ("gradle_init_sha256", "gradle-init.gradle"),
                      ("gradle_cache_manifest_sha256", "gradle-cache-manifest.json")):
        out[key] = hashlib.sha256(pathlib.Path("/opt/beacon-repair", name).read_bytes()).hexdigest()
try: out["scratch_write_bytes"] = pathlib.Path("/scratch/probe.bin").write_bytes(b"X")
except OSError as error: out["scratch_errno"] = errno.errorcode.get(error.errno, str(error.errno))
def connect(host, port, key):
    try:
        with socket.create_connection((host, int(port)), timeout=2): out[key] = "ALLOWED"
    except OSError as error: out[key] = errno.errorcode.get(error.errno, str(error.errno))
connect("127.0.0.1", sys.argv[2], "host_loopback_errno")
connect(sys.argv[3], sys.argv[4], "network_errno")
server = socket.socket(); server.bind(("127.0.0.1", 0)); server.listen(1)
client = socket.create_connection(server.getsockname(), timeout=2); peer, _ = server.accept()
client.sendall(b"X"); out["sandbox_loopback_bytes"] = len(peer.recv(1))
peer.close(); client.close(); server.close()
pathlib.Path("/scratch/probe.json").write_text(json.dumps(out), encoding="utf8")
'''


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def profile_sha256(profile):
    return hashlib.sha256(_canonical(profile)).hexdigest()


def _job_directory(config, job_id):
    if not isinstance(job_id, str) or not _JOB.fullmatch(job_id): raise ValueError('Invalid job')
    root = Path(config['private_root']).resolve(strict=True)
    jobs = root / 'executor-jobs'; jobs.mkdir(mode=0o700, exist_ok=True)
    if jobs.is_symlink() or getattr(jobs, 'is_junction', lambda: False)() or not jobs.resolve(strict=True).is_relative_to(root):
        raise ValueError('Invalid job')
    job = jobs / job_id; job.mkdir(mode=0o700, exist_ok=True)
    if job.is_symlink() or getattr(job, 'is_junction', lambda: False)() or not job.resolve(strict=True).is_relative_to(jobs.resolve(strict=True)):
        raise ValueError('Invalid job')
    return job


@contextmanager
def _job_lock(job, deadline):
    path = Path(job) / 'coord.lock'
    if path.is_symlink(): raise ValueError('Invalid job lock')
    with path.open('a+b') as stream:
        if path.stat().st_nlink != 1: raise ValueError('Invalid job lock')
        if path.stat().st_size == 0: stream.write(b'0'); stream.flush()
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline: raise TimeoutError('Job lock unavailable') from None
                time.sleep(.05)
        try: yield
        finally:
            if os.name == 'nt':
                stream.seek(0); msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else: fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


class StageRouter:
    """Dispatch only configured, trusted stage handlers; no command fallback."""
    def __init__(self, handlers):
        if not isinstance(handlers, dict) or not handlers or not set(handlers) <= _STAGES or not all(callable(v) for v in handlers.values()):
            raise ValueError('Invalid stage bindings')
        self.handlers = dict(handlers)
        self._lock = threading.Lock()
        self._active = {}
        self._cancelled = set()

    def __call__(self, stage, context):
        if stage not in self.handlers: raise ValueError('Stage is not configured')
        job = context.get('job_id') if isinstance(context, dict) else None
        if not isinstance(job, str) or not _JOB.fullmatch(job): raise ValueError('Invalid stage context')
        handler = self.handlers[stage]
        with self._lock:
            previous = self._active.get(job)
            if job in self._cancelled or previous is not None and (previous['process'] is None or previous['process'].poll() is None):
                raise ValueError('Stage is not available')
            entry = {'handler': handler, 'process': None}
            self._active[job] = entry
        process = handler(stage, context)
        if not isinstance(process, subprocess.Popen) or process.stdout is not None or process.stderr is not None:
            raise ValueError('Stage process is unconfirmed')
        with self._lock: entry['process'] = process
        return process

    def cancel(self, job_id, deadline_monotonic):
        if (not isinstance(job_id, str) or not _JOB.fullmatch(job_id) or isinstance(deadline_monotonic, bool) or
                not isinstance(deadline_monotonic, (int, float)) or not math.isfinite(deadline_monotonic) or deadline_monotonic <= time.monotonic()):
            raise ValueError('Invalid cancellation')
        with self._lock:
            self._cancelled.add(job_id)
            entry = self._active.get(job_id)
        if entry is None: return {'status': 'recorded'}
        cancel = getattr(entry['handler'], 'cancel', None)
        if not callable(cancel): raise RuntimeError('Stage cancellation unconfirmed')
        return cancel(job_id, deadline_monotonic)


class DockerStageExecutor:
    """Runner adapter: one private, content-bound worker process per fixed stage."""
    def __init__(self, config_path, config_sha256, process_owner_factory):
        if Path(config_path).is_symlink(): raise ValueError('Invalid private executor configuration')
        if not callable(process_owner_factory): raise ValueError('Process ownership unavailable')
        self.config_path = Path(config_path).resolve(strict=True)
        self.config_sha256 = config_sha256
        self.process_owner_factory = process_owner_factory
        self._owners = {}
        self._bindings = {}
        self._owners_lock = threading.Lock()
        self._config = load_config(self.config_path, self.config_sha256)

    def __call__(self, stage, context):
        config = load_config(self.config_path, self.config_sha256)
        safe = validate_context(config, stage, context)
        try:
            job = _job_directory(config, safe['job_id'])
            binding = {'job_id': safe['job_id'], 'workspace': safe['workspace'], 'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}
            with self._owners_lock:
                previous = self._bindings.get(safe['job_id'])
                if previous is not None and previous != binding: raise ValueError()
                self._bindings[safe['job_id']] = binding
            with _job_lock(job, safe['deadline_monotonic']):
                if (job / 'cancel.request').exists() or (job / 'active.json').exists(): raise ValueError()
                token = secrets.token_hex(16)
                request = job / (stage + '.request.json')
                raw = _canonical({'stage': stage, 'context': safe, 'token': token}) + b'\n'
                _write_new(request, raw)
                active = {'job_id': safe['job_id'], 'stage': stage, 'name': 'beacon-repair-' + token, 'token': token,
                          'cidfile': str(job / stage / 'container.cid')}
                _write_new(job / 'active.json', _canonical(active) + b'\n')
                request_hash = hashlib.sha256(raw).hexdigest()
                allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'PATHEXT', 'COMSPEC', 'TEMP', 'TMP', 'LANG', 'LC_ALL'}
                env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
                flags = (getattr(subprocess, 'CREATE_NO_WINDOW', 0) | getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0)) if os.name == 'nt' else 0
                process = subprocess.Popen([config['python_binary'], '-I', '-B', str(Path(__file__).resolve()), '--worker', str(self.config_path),
                                            self.config_sha256, str(request), request_hash], cwd=config['private_root'], env=env, stdin=subprocess.DEVNULL,
                                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True, creationflags=flags, start_new_session=os.name != 'nt')
                try:
                    owner = self.process_owner_factory(process)
                    if not callable(getattr(owner, 'kill', None)) or not callable(getattr(owner, 'close', None)): raise ValueError()
                    _write_new(job / (stage + '.ready.json'), _canonical({'token': token, 'pid': process.pid}) + b'\n')
                except BaseException:
                    process.kill(); process.wait(timeout=5)
                    raise
                with self._owners_lock: self._owners[safe['job_id']] = (process, owner)
                def reap():
                    try: process.wait()
                    finally:
                        owner.close()
                        with self._owners_lock:
                            if self._owners.get(safe['job_id']) == (process, owner): self._owners.pop(safe['job_id'], None)
                try: threading.Thread(target=reap, name='beacon-repair-process-owner', daemon=True).start()
                except BaseException:
                    try: owner.kill(); process.wait(timeout=5)
                    finally:
                        owner.close()
                        with self._owners_lock: self._owners.pop(safe['job_id'], None)
                    raise
                return process
        except (OSError, ValueError, TypeError):
            raise ValueError('Repair worker could not be started') from None

    def cancel(self, job_id, deadline_monotonic):
        config = self._config
        if (not isinstance(job_id, str) or not _JOB.fullmatch(job_id) or isinstance(deadline_monotonic, bool) or
                not isinstance(deadline_monotonic, (int, float)) or not math.isfinite(deadline_monotonic) or deadline_monotonic <= time.monotonic()):
            raise ValueError('Invalid cancellation')
        with self._owners_lock: binding = self._bindings.get(job_id)
        if binding is None: binding = _private_json(Path(config['bindings_root']) / (job_id + '.json'))
        if not isinstance(binding, dict) or binding.get('job_id') != job_id or binding.get('repo_identity') != config['repo_identity'] or binding.get('base_commit') != config['base_commit']:
            raise ValueError('Invalid job binding')
        job = _job_directory(config, job_id)
        with _job_lock(job, deadline_monotonic):
            marker = job / 'cancel.request'
            if not marker.exists(): _write_new(marker, b'cancel\n')
            active_path = job / 'active.json'
            if not active_path.is_file(): return {'status': 'recorded'}
            active = _private_json(active_path)
            if (not isinstance(active, dict) or set(active) != {'job_id', 'stage', 'name', 'token', 'cidfile'} or
                    active['job_id'] != job_id or active['stage'] not in SANDBOX_STAGES or
                    not isinstance(active['token'], str) or not _TOKEN.fullmatch(active['token']) or
                    active['name'] != 'beacon-repair-' + active['token'] or
                    Path(active['cidfile']).resolve(strict=False) != (job / active['stage'] / 'container.cid').resolve(strict=False)):
                raise RuntimeError('Container ownership unconfirmed')
            cidfile = Path(active['cidfile'])
            if not cidfile.is_file(): return {'status': 'pending'}
            if cidfile.is_symlink() or cidfile.stat().st_nlink != 1 or cidfile.stat().st_size > 128:
                raise RuntimeError('Container ownership unconfirmed')
            cid = cidfile.read_text(encoding='ascii').strip()
            if not _CID.fullmatch(cid): raise RuntimeError('Container ownership unconfirmed')
        client = DockerClient(config['docker_binary'], config['docker_sha256'], config['docker_host'], config['docker_config_dir'], config['private_root'])
        if not client.absent(cid, deadline_monotonic):
            try: stop_owned(client, cid, active, deadline_monotonic)
            except RuntimeError:
                if not client.absent(cid, deadline_monotonic): raise
        return {'status': 'stopped'}


def pytest_command(stage, nodes):
    """Only the image's protected harness and operator-pinned test nodes are executable."""
    if stage not in _PYTEST_STAGES or not isinstance(nodes, list) or not 1 <= len(nodes) <= 128:
        raise ValueError('Invalid test stage')
    for node in nodes:
        if (not isinstance(node, str) or len(node) > 400 or not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9_.:/\[\]-]*', node) or
                node.startswith('-') or any(part in ('', '.', '..') for part in node.split('::')[0].split('/'))):
            raise ValueError('Invalid test stage')
    return ['/usr/local/bin/python', '-I', '/opt/beacon-repair/pytest_harness.py', stage, *nodes]


def gradle_command(stage):
    if stage != 'final_build': raise ValueError('Invalid build stage')
    return ['/usr/local/bin/python', '-I', '/opt/beacon-repair/gradle_harness.py', stage]


def acquire_gradle_slot(root, job_id, token, cidfile, deadline, *, cancelled=None):
    root, cidfile = Path(root), Path(cidfile)
    if (not root.is_absolute() or root.is_symlink() or not root.is_dir() or not isinstance(job_id, str) or not _JOB.fullmatch(job_id) or
            not isinstance(token, str) or not _TOKEN.fullmatch(token) or not cidfile.is_absolute()):
        raise ValueError('Invalid Gradle slot')
    root = root.resolve(strict=True); slot = root / 'slot-1'
    while True:
        if time.monotonic() >= deadline or cancelled is not None and cancelled(): raise TimeoutError('Gradle slot unavailable')
        try: slot.mkdir(); break
        except FileExistsError: time.sleep(.1)
    owner = {'pid': os.getpid(), 'external_job': True, 'job_id': job_id, 'token': token,
             'cidfile': str(cidfile), 'since': time.time(), 'tasks': ['testDebugUnitTest', 'assembleDebug', 'assembleDebugAndroidTest',
                                                                  'assembleStable', 'lintDebug', 'lintStable']}
    _write_new(slot / 'owner.json', _canonical(owner) + b'\n')
    return slot, owner


def release_gradle_slot(client, slot, owner, deadline):
    slot = Path(slot)
    if (slot.is_symlink() or getattr(slot, 'is_junction', lambda: False)() or not slot.is_dir() or
            _private_json(slot / 'owner.json') != owner or set(slot.iterdir()) != {slot / 'owner.json'}):
        raise RuntimeError('Gradle slot ownership unconfirmed')
    cidfile = Path(owner['cidfile'])
    if cidfile.is_symlink() or not cidfile.is_file() or cidfile.stat().st_nlink != 1 or cidfile.stat().st_size > 128:
        raise RuntimeError('Gradle container identity unconfirmed')
    cid = cidfile.read_text(encoding='ascii').strip()
    if not _CID.fullmatch(cid) or not client.absent(cid, deadline): raise RuntimeError('Gradle container exit unconfirmed')
    (slot / 'owner.json').unlink()
    slot.rmdir()


def write_receipt(output, stage, context, artifacts):
    try:
        output = Path(output)
        if (output.is_symlink() or not output.is_dir() or stage not in _ARTIFACTS or not isinstance(artifacts, list) or
                len(artifacts) != len(set(artifacts)) or set(artifacts) != _ARTIFACTS[stage] or not isinstance(context, dict) or
                not isinstance(context.get('job_id'), str) or not _JOB.fullmatch(context['job_id']) or
                any(not isinstance(context.get(k), str) or not _SHA256.fullmatch(context[k]) for k in ('request_key', 'policy_sha256', 'source_sha256'))):
            raise ValueError()
        hashes = {}
        for name in artifacts:
            path = output / name
            maximum = (96 if stage == 'final_build' and name == 'debug.apk' else 64) * 1024 * 1024
            if (not re.fullmatch(r'[A-Za-z0-9_.-]{1,120}', name) or path.is_symlink() or not path.is_file() or
                    path.stat().st_nlink != 1 or path.stat().st_size > maximum):
                raise ValueError()
            hashes[name] = _file_hash(path)
        binding = {key: context[key] for key in ('job_id', 'request_key', 'policy_sha256', 'source_sha256')}
        receipt = _canonical({**binding, 'stage': stage, 'artifacts': hashes}) + b'\n'
        with (output / 'receipt.json').open('xb') as stream:
            stream.write(receipt); stream.flush(); os.fsync(stream.fileno())
    except (OSError, KeyError, ValueError, TypeError):
        raise ValueError('Invalid stage evidence') from None


def _parse_private_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Invalid private configuration')
            result[key] = value
        return result
    try: return json.loads(raw, object_pairs_hook=unique)
    except (ValueError, TypeError, UnicodeError, RecursionError):
        raise ValueError('Invalid private configuration') from None


def _private_json(path, limit=65536):
    path = Path(path)
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > limit: raise ValueError()
        raw = path.read_bytes()
        if len(raw) > limit: raise ValueError()
        return _parse_private_json(raw)
    except (OSError, ValueError): raise ValueError('Invalid private configuration') from None


def read_pinned_json(path, expected_sha256, limit=16 * 1024 * 1024):
    try:
        if not isinstance(expected_sha256, str) or not _SHA256.fullmatch(expected_sha256): raise ValueError()
        path = Path(path)
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > limit: raise ValueError()
        raw = path.read_bytes()
        if len(raw) > limit or hashlib.sha256(raw).hexdigest() != expected_sha256: raise ValueError()
        return _parse_private_json(raw)
    except (OSError, ValueError, TypeError):
        raise ValueError('Invalid private configuration') from None


def validate_context(config, stage, context):
    """Match an invocation to a private allocator binding and runner-owned output directory."""
    try:
        if stage not in SANDBOX_STAGES or not isinstance(config, dict) or not isinstance(context, dict): raise ValueError()
        job = context['job_id']
        if (not isinstance(job, str) or not _JOB.fullmatch(job) or
                any(not isinstance(context.get(k), str) or not _SHA256.fullmatch(context[k]) for k in ('request_key', 'policy_sha256', 'source_sha256')) or
                context['policy_sha256'] != config['policy_sha256'] or context['base_commit'] != config['base_commit'] or
                not isinstance(context['deadline_monotonic'], (int, float)) or isinstance(context['deadline_monotonic'], bool) or
                not math.isfinite(context['deadline_monotonic']) or not 0 < context['deadline_monotonic'] - time.monotonic() <= config['stage_timeout'] + 5 or
                _public_path(context['probe_source']) not in config['public_manifest']):
            raise ValueError()
        if stage in _PYTEST_STAGES: pytest_command(stage, config['test_nodes'][stage])
        if stage in ('final_build', 'final_lint'):
            if (config.get('gradle_harness_sha256') is None or not isinstance(context.get('release'), str) or
                    not re.fullmatch(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)', context['release']) or
                    type(context.get('version_code')) is not int or not 1 <= context['version_code'] <= 2147483647): raise ValueError()
        private, bindings, receipts = (Path(config[key]) for key in ('private_root', 'bindings_root', 'receipt_root'))
        for path in (private, bindings, receipts):
            if not path.is_absolute() or path.is_symlink() or not path.is_dir(): raise ValueError()
        private = private.resolve(strict=True); bindings = bindings.resolve(strict=True); receipts = receipts.resolve(strict=True)
        if not bindings.is_relative_to(private) or not receipts.is_relative_to(private): raise ValueError()
        workspace, output = Path(context['workspace']), Path(context['output_dir'])
        if not workspace.is_absolute() or workspace.is_symlink() or not workspace.is_dir() or not output.is_absolute() or output.is_symlink() or not output.is_dir():
            raise ValueError()
        workspace_real, output_real = workspace.resolve(strict=True), output.resolve(strict=True)
        jobdir = receipts / job
        if (jobdir.is_symlink() or getattr(jobdir, 'is_junction', lambda: False)() or not output_real.is_relative_to(receipts) or
                output_real != (jobdir / stage).resolve(strict=True) or workspace_real == private or
                workspace_real in private.parents or private in workspace_real.parents or any(output.iterdir())):
            raise ValueError()
        binding = _private_json(bindings / (job + '.json'))
        if (not isinstance(binding, dict) or set(binding) != {'job_id', 'workspace', 'repo_identity', 'base_commit'} or
                binding['job_id'] != job or binding['repo_identity'] != config['repo_identity'] or
                binding['base_commit'] != config['base_commit'] or not _same_path(binding['workspace'], workspace)):
            raise ValueError()
        keys = ('job_id', 'request_key', 'policy_sha256', 'source_sha256', 'workspace', 'output_dir', 'base_commit', 'deadline_monotonic', 'probe_source')
        result = {key: context[key] for key in keys}
        if stage in ('final_build', 'final_lint'): result.update(release=context['release'], version_code=context['version_code'])
        return result
    except (OSError, KeyError, TypeError, ValueError, AttributeError):
        raise ValueError('Invalid stage context') from None


def load_config(path, expected_sha256):
    """Validate one exact private, content-pinned operator configuration."""
    try:
        config = read_pinned_json(path, expected_sha256)
        if not isinstance(config, dict) or set(config) != _CONFIG_KEYS or config['schema'] != 1: raise ValueError()
        for key in ('repo_identity', 'policy_sha256', 'runner_sha256', 'executor_sha256', 'python_sha256', 'docker_sha256', 'sandbox_sha256', 'harness_sha256'):
            if not isinstance(config[key], str) or not _SHA256.fullmatch(config[key]): raise ValueError()
        if (not isinstance(config['base_commit'], str) or not _COMMIT.fullmatch(config['base_commit']) or
                not isinstance(config['image'], str) or not _DIGEST.fullmatch(config['image']) or
                _file_hash(Path(__file__).resolve()) != config['executor_sha256'] or
                _file_hash(Path(__file__).resolve().with_name('repair_runner.py')) != config['runner_sha256']):
            raise ValueError()
        python = Path(config['python_binary'])
        base_python = Path(getattr(sys, '_base_executable', sys.executable)).resolve(strict=True)
        if (not python.is_absolute() or python.is_symlink() or not python.is_file() or python.resolve(strict=True) != base_python or
                _file_hash(python) != config['python_sha256']): raise ValueError()
        private = Path(config['private_root'])
        if (not private.is_absolute() or private.is_symlink() or not private.is_dir() or
                not Path(path).resolve(strict=True).is_relative_to(private.resolve(strict=True))): raise ValueError()
        for key in ('bindings_root', 'receipt_root', 'docker_config_dir'):
            candidate = Path(config[key])
            if not candidate.is_absolute() or candidate.is_symlink() or not candidate.is_dir() or not candidate.resolve(strict=True).is_relative_to(private.resolve(strict=True)):
                raise ValueError()
        coordinator = Path(config['coordinator_root'])
        if not coordinator.is_absolute() or coordinator.is_symlink() or not coordinator.is_dir(): raise ValueError()
        DockerClient(config['docker_binary'], config['docker_sha256'], config['docker_host'], config['docker_config_dir'], private)
        for key, low, high in (('memory', 64 * 1024 * 1024, 6 * 1024**3), ('nano_cpus', 1, 2000000000), ('pids', 1, 512), ('stage_timeout', 30, 3600)):
            if type(config[key]) is not int or not low <= config[key] <= high: raise ValueError()
        env = config['image_env']
        if (not isinstance(env, list) or not env or len(env) > 32 or any(not isinstance(item, str) or '=' not in item or '\0' in item or
                len(item) > 1024 or item.split('=', 1)[0] not in _ENV for item in env) or len({item.split('=', 1)[0] for item in env}) != len(env)):
            raise ValueError()
        manifest = config['public_manifest']
        if not isinstance(manifest, dict) or not 1 <= len(manifest) <= 20000: raise ValueError()
        names = [_public_path(name) for name in manifest]
        if len({name.casefold() for name in names}) != len(names): raise ValueError()
        groups = {}
        for key in ('editable_paths', 'release_paths', 'baseline_paths'):
            values = config[key]
            if not isinstance(values, list) or any(not isinstance(v, str) for v in values) or len(values) != len(set(values)) or not set(values) <= set(manifest): raise ValueError()
            groups[key] = set(values)
        if (not groups['baseline_paths'] or groups['baseline_paths'] & (groups['editable_paths'] | groups['release_paths']) or
                groups['editable_paths'] & groups['release_paths'] or
                any(name.rsplit('/', 1)[-1].lower() in {'conftest.py', 'pytest.ini', 'tox.ini', 'pyproject.toml', 'setup.cfg', 'pytest_harness.py'} for name in groups['editable_paths'])):
            raise ValueError()
        runner = _runner_module(config)
        for name in groups['editable_paths']: runner._path(name)
        for name, digest in manifest.items():
            if digest is None:
                if name not in groups['editable_paths']: raise ValueError()
            elif not isinstance(digest, str) or not _SHA256.fullmatch(digest): raise ValueError()
        tests = config['test_nodes']
        if not isinstance(tests, dict) or not tests or not set(tests) <= _PYTEST_STAGES: raise ValueError()
        for stage, nodes in tests.items():
            pytest_command(stage, nodes)
            paths = {node.split('::')[0] for node in nodes}
            if not paths <= set(manifest): raise ValueError()
            if stage in ('reproduction', 'reproduction_green', 'final_reproduction') and not paths <= groups['baseline_paths']: raise ValueError()
            if stage in ('regression_red', 'regression_green', 'final_regression') and not paths <= groups['editable_paths']: raise ValueError()
        gradle_keys = ('gradle_harness_sha256', 'gradle_init_sha256', 'gradle_cache_manifest_sha256', 'build_artifacts', 'plugin_manifest', 'gradle_slot_root')
        if any(config[key] is not None for key in gradle_keys):
            if any(config[key] is None for key in gradle_keys): raise ValueError()
            for key in gradle_keys[:3]:
                if not isinstance(config[key], str) or not _SHA256.fullmatch(config[key]): raise ValueError()
            build = config['build_artifacts']
            if not isinstance(build, dict) or set(build) != {'stable', 'debug', 'androidtest'}: raise ValueError()
            for name in build.values():
                if (not isinstance(name, str) or not name or len(name) > 480 or '\\' in name or ':' in name or
                        any(not _PATH_PART.fullmatch(part) or part.endswith('.') for part in name.split('/'))): raise ValueError()
            if config['plugin_manifest'] not in manifest or config['plugin_manifest'] in groups['editable_paths']: raise ValueError()
            slot_root = Path(config['gradle_slot_root'])
            if not slot_root.is_absolute() or slot_root.is_symlink() or getattr(slot_root, 'is_junction', lambda: False)() or not slot_root.is_dir(): raise ValueError()
        native = config['native_manifest']
        if not isinstance(native, dict) or len(native) > 20000: raise ValueError()
        if config['native_root'] is None:
            if config['native_commit'] is not None or native: raise ValueError()
        else:
            root = Path(config['native_root'])
            if (not root.is_absolute() or root.is_symlink() or not root.is_dir() or not isinstance(config['native_commit'], str) or
                    not _COMMIT.fullmatch(config['native_commit']) or not native): raise ValueError()
            for name, digest in native.items():
                _public_path(name)
                if not isinstance(digest, str) or not _SHA256.fullmatch(digest): raise ValueError()
            if any(name.split('/')[0].casefold() == 'public-native' for name in manifest): raise ValueError()
        return config
    except (OSError, ValueError, TypeError, KeyError, AttributeError):
        raise ValueError('Invalid private executor configuration') from None


def _public_path(value):
    if not isinstance(value, str) or not value or len(value) > 480 or '\\' in value or ':' in value:
        raise ValueError('Invalid public source')
    parts = value.split('/')
    if (len(parts) > 20 or any(not _PATH_PART.fullmatch(part) or part.endswith('.') or part.lower() in _DENIED_PARTS or
            re.fullmatch(r'(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?', part) for part in parts)):
        raise ValueError('Invalid public source')
    name = parts[-1].lower()
    if (name == 'grok.txt' or name == 'local.properties' or name.endswith(('.env', '.pem', '.key', '.p12', '.pfx', '.jks', '.keystore', '.db', '.sqlite', '.sqlite3'))):
        raise ValueError('Invalid public source')
    return value


def _safe_file(root, relative):
    current = Path(root).resolve(strict=True)
    for index, part in enumerate(relative.split('/')):
        current = current / part
        if current.is_symlink() or getattr(current, 'is_junction', lambda: False)(): raise ValueError('Invalid public source')
        if current.exists():
            stat = current.stat()
            if index == len(relative.split('/')) - 1:
                if not current.is_file() or stat.st_nlink != 1: raise ValueError('Invalid public source')
            elif not current.is_dir(): raise ValueError('Invalid public source')
    return current


def _safe_directory(root, relative):
    current = Path(root).resolve(strict=True)
    for part in relative.split('/'):
        current = current / part
        if current.is_symlink() or getattr(current, 'is_junction', lambda: False)() or not current.is_dir():
            raise ValueError('Invalid evidence directory')
    return current


def export_public_source(workspace, output, manifest, *, editable, max_file_bytes=16 * 1024 * 1024, max_total_bytes=512 * 1024 * 1024,
                         deadline=None, cancelled=None):
    """Copy only an operator-pinned public manifest into a new job-owned tree."""
    try:
        def check():
            if deadline is not None and time.monotonic() >= deadline or cancelled is not None and cancelled(): raise ValueError()
        check()
        root, output = Path(workspace), Path(output)
        if root.is_symlink() or not root.is_dir() or output.exists() or output.is_symlink(): raise ValueError()
        root = root.resolve(strict=True)
        if output.resolve(strict=False) == root or root in output.resolve(strict=False).parents or output.resolve(strict=False) in root.parents:
            raise ValueError()
        if (not isinstance(manifest, dict) or not 1 <= len(manifest) <= 20000 or not isinstance(editable, set) or not editable <= set(manifest) or
                type(max_file_bytes) is not int or not 1 <= max_file_bytes <= 64 * 1024 * 1024 or
                type(max_total_bytes) is not int or not 1 <= max_total_bytes <= 1024 * 1024 * 1024): raise ValueError()
        names = [_public_path(name) for name in manifest]
        if len({name.casefold() for name in names}) != len(names): raise ValueError()
        if any(name.rsplit('/', 1)[-1].lower() in {'conftest.py', 'pytest.ini', 'tox.ini', 'pyproject.toml', 'setup.cfg', 'pytest_harness.py'} for name in editable):
            raise ValueError()
        for name, digest in manifest.items():
            if digest is None:
                if name not in editable: raise ValueError()
            elif not isinstance(digest, str) or not _SHA256.fullmatch(digest): raise ValueError()
        output.mkdir(parents=True, mode=0o700)
        total, result = 0, {}
        for name in sorted(names):
            check()
            source = _safe_file(root, name)
            if not source.exists():
                if manifest[name] is None and name in editable: continue
                raise ValueError()
            size = source.stat().st_size
            total += size
            if size > max_file_bytes or total > max_total_bytes: raise ValueError()
            target = output.joinpath(*name.split('/'))
            target.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha256()
            with source.open('rb') as incoming, target.open('xb') as outgoing:
                while True:
                    check()
                    block = incoming.read(1024 * 1024)
                    if not block: break
                    digest.update(block); outgoing.write(block)
            actual = digest.hexdigest()
            if source.stat().st_size != size or (actual != manifest[name] and name not in editable): raise ValueError()
            target.chmod(0o444)
            result[name] = actual
        return result
    except (OSError, ValueError, TypeError, RuntimeError):
        raise ValueError('Invalid public source') from None


def _same_path(left, right):
    try:
        return os.path.normcase(str(Path(left).resolve(strict=True))) == os.path.normcase(str(Path(right).resolve(strict=True)))
    except (OSError, RuntimeError, TypeError, ValueError):
        return False


def verify_owned(inspect, container_id, expected):
    try:
        labels = inspect['Config']['Labels']
        if (not isinstance(container_id, str) or not _CID.fullmatch(container_id) or inspect['Id'] != container_id or
                not _TOKEN.fullmatch(expected['token']) or not _JOB.fullmatch(expected['job_id']) or
                inspect['Name'] != '/' + expected['name'] or not isinstance(labels, dict) or
                labels.get('beacon.repair.token') != expected['token'] or labels.get('beacon.repair.job') != expected['job_id'] or
                labels.get('beacon.repair.stage') != expected['stage']):
            raise ValueError()
        return True
    except (KeyError, TypeError, AttributeError, ValueError):
        raise ValueError('Container ownership not verified') from None


def stop_owned(client, container_id, expected, deadline):
    """Signal only an exact job-owned container, then verify it really exited."""
    first = client.inspect(container_id, deadline)
    verify_owned(first, container_id, expected)
    state = first.get('State')
    if not isinstance(state, dict) or type(state.get('Running')) is not bool:
        raise RuntimeError('Container state unconfirmed')
    if state['Running']:
        try:
            client.kill(container_id, deadline)
            client.wait(container_id, deadline)
        except (RuntimeError, TimeoutError):
            last = client.inspect(container_id, deadline)
            verify_owned(last, container_id, expected)
            state = last.get('State')
            if isinstance(state, dict) and state.get('Running') is False and state.get('Status') in ('created', 'exited', 'dead'):
                return True
            raise
        last = client.inspect(container_id, deadline)
        verify_owned(last, container_id, expected)
        state = last.get('State')
    if not isinstance(state, dict) or state.get('Running') is not False or state.get('Status') not in ('created', 'exited', 'dead'):
        raise RuntimeError('Container exit unconfirmed')
    return True


def _file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


class DockerClient:
    """Local daemon only, with a pinned CLI and an empty private Docker config."""
    def __init__(self, binary, binary_sha256, host, config_dir, private_root):
        try:
            binary, config_dir, private_root = Path(binary), Path(config_dir), Path(private_root)
            local_pipe = isinstance(host, str) and re.fullmatch(r'npipe:////\./pipe/(?:dockerDesktopLinuxEngine|docker_engine_linux|docker_engine)', host, re.I)
            local_unix = isinstance(host, str) and host == 'unix:///var/run/docker.sock'
            if (not binary.is_absolute() or binary.is_symlink() or not binary.is_file() or binary.stat().st_size > 256 * 1024 * 1024 or
                    not isinstance(binary_sha256, str) or not _SHA256.fullmatch(binary_sha256) or _file_hash(binary) != binary_sha256 or
                    not (local_pipe or local_unix) or private_root.is_symlink() or not private_root.is_dir() or
                    config_dir.is_symlink() or not config_dir.is_dir() or
                    not config_dir.resolve(strict=True).is_relative_to(private_root.resolve(strict=True)) or
                    config_dir.resolve(strict=True) == private_root.resolve(strict=True) or any(config_dir.iterdir())):
                raise ValueError()
            self.binary, self.binary_sha256, self.host = binary.resolve(strict=True), binary_sha256, host
            self.config_dir = config_dir.resolve(strict=True)
            self.private_root = private_root.resolve(strict=True)
        except (OSError, ValueError, TypeError):
            raise ValueError('Invalid local container binding') from None

    def _run(self, args, deadline):
        timeout = min(60.0, deadline - time.monotonic())
        if timeout <= 0: raise TimeoutError('Container operation timed out')
        env = self._environment()
        try:
            result = subprocess.run([str(self.binary), '--host', self.host, *args], env=env, stdin=subprocess.DEVNULL,
                                    stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, check=False)
        except subprocess.TimeoutExpired: raise TimeoutError('Container operation timed out') from None
        except OSError: raise RuntimeError('Container operation unavailable') from None
        if result.returncode != 0 or len(result.stdout) > 2 * 1024 * 1024: raise RuntimeError('Container operation failed')
        return result.stdout

    def _environment(self):
        if any(self.config_dir.iterdir()) or _file_hash(self.binary) != self.binary_sha256:
            raise RuntimeError('Container binding changed')
        allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'PATHEXT', 'COMSPEC', 'TEMP', 'TMP', 'LANG', 'LC_ALL'}
        env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
        env['DOCKER_CONFIG'] = str(self.config_dir)
        return env

    def start(self, container_id, deadline):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        self._run(['start', container_id], deadline)

    def wait_process(self, container_id):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        try:
            return subprocess.Popen([str(self.binary), '--host', self.host, 'wait', container_id], env=self._environment(),
                                    stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, close_fds=True)
        except OSError: raise RuntimeError('Container operation unavailable') from None

    def start_logged(self, container_id, log_path):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        path = Path(log_path)
        if not path.is_absolute() or path.exists() or not path.parent.resolve(strict=True).is_relative_to(self.private_root):
            raise ValueError('Invalid private log')
        stream = path.open('xb')
        try:
            process = subprocess.Popen([str(self.binary), '--host', self.host, 'start', '--attach', container_id], env=self._environment(),
                                       stdin=subprocess.DEVNULL, stdout=stream, stderr=subprocess.STDOUT, close_fds=True)
            return process, stream
        except BaseException:
            stream.close()
            raise

    def inspect(self, container_id, deadline):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        try: result = json.loads(self._run(['inspect', container_id], deadline))
        except (TypeError, ValueError, UnicodeError): raise RuntimeError('Container inspection unavailable') from None
        if not isinstance(result, list) or len(result) != 1 or not isinstance(result[0], dict):
            raise RuntimeError('Container inspection unavailable')
        return result[0]

    def kill(self, container_id, deadline):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        self._run(['kill', container_id], deadline)

    def wait(self, container_id, deadline):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        try: value = int(self._run(['wait', container_id], deadline).strip())
        except ValueError: raise RuntimeError('Container exit unconfirmed') from None
        if not 0 <= value <= 255: raise RuntimeError('Container exit unconfirmed')
        return value

    def absent(self, container_id, deadline):
        if not isinstance(container_id, str) or not _CID.fullmatch(container_id): raise ValueError('Invalid container identity')
        result = self._run(['container', 'ls', '--all', '--no-trunc', '--filter', 'id=' + container_id, '--format', '{{.ID}}'], deadline)
        return not result.strip()

    def remove_owned(self, container_id, expected, deadline):
        info = self.inspect(container_id, deadline)
        verify_owned(info, container_id, expected)
        state = info.get('State')
        if not isinstance(state, dict) or state.get('Running') is not False or state.get('Status') not in ('created', 'exited', 'dead'):
            raise RuntimeError('Container exit unconfirmed')
        self._run(['rm', container_id], deadline)
        if not self.absent(container_id, deadline): raise RuntimeError('Container removal unconfirmed')
        return True

    def create(self, expected, cidfile, deadline, expected_profile):
        """Create, inspect and pin the exact policy before any container code starts."""
        try:
            cidfile = Path(cidfile)
            calibration = (expected_profile is None and expected.get('stage') == 'calibration' and
                           expected.get('entrypoint') == ['/usr/local/bin/python'] and expected.get('cmd') == ['-I', '-S', '-c', 'pass'])
            if (not isinstance(expected, dict) or not _TOKEN.fullmatch(expected['token']) or
                    expected['name'] != 'beacon-repair-' + expected['token'] or not _JOB.fullmatch(expected['job_id']) or
                    not isinstance(expected['stage'], str) or not re.fullmatch(r'[a-z][a-z0-9_]{0,47}', expected['stage']) or
                    not _DIGEST.fullmatch(expected['image']) or not (calibration or isinstance(expected_profile, str) and _SHA256.fullmatch(expected_profile)) or
                    expected['user'] != '65534:65534' or expected['tmpfs'] != {'/tmp': 'rw,noexec,nosuid,size=32m'} or
                    type(expected['memory']) is not int or not 64 * 1024 * 1024 <= expected['memory'] <= 6 * 1024**3 or
                    type(expected['nano_cpus']) is not int or not 1 <= expected['nano_cpus'] <= 2000000000 or
                    type(expected['pids']) is not int or not 1 <= expected['pids'] <= 512 or
                    not cidfile.is_absolute() or cidfile.exists() or cidfile.is_symlink() or
                    not cidfile.parent.resolve(strict=True).is_relative_to(self.private_root)):
                raise ValueError()
            mounts = expected['mounts']
            if not isinstance(mounts, dict) or set(mounts) != {'/workspace', '/scratch'} or mounts['/workspace']['writable'] is not False or mounts['/scratch']['writable'] is not True:
                raise ValueError()
            paths = []
            for target in ('/workspace', '/scratch'):
                path = Path(mounts[target]['source'])
                if (not path.is_absolute() or path.is_symlink() or not path.is_dir() or ',' in str(path) or
                        not path.resolve(strict=True).is_relative_to(self.private_root)):
                    raise ValueError()
                paths.append(path.resolve(strict=True))
            if paths[0] == paths[1] or paths[0] in paths[1].parents or paths[1] in paths[0].parents: raise ValueError()
            entry, cmd, env = expected['entrypoint'], expected['cmd'], expected['env']
            if (not isinstance(entry, list) or len(entry) != 1 or not isinstance(entry[0], str) or not entry[0].startswith('/') or
                    not isinstance(cmd, list) or len(cmd) > 256 or any(not isinstance(a, str) or len(a) > 16384 or '\0' in a for a in entry + cmd) or
                    not isinstance(env, list) or len(env) > 32 or any(not isinstance(item, str) or '=' not in item or len(item) > 1024 or '\0' in item or item.split('=', 1)[0] not in _ENV for item in env) or
                    len({item.split('=', 1)[0] for item in env}) != len(env)):
                raise ValueError()
            args = ['create', '--name', expected['name'], '--label', 'beacon.repair.token=' + expected['token'],
                    '--label', 'beacon.repair.job=' + expected['job_id'], '--label', 'beacon.repair.stage=' + expected['stage'],
                    '--cidfile', str(cidfile), '--pull=never', '--network=none', '--read-only', '--cap-drop=ALL',
                    '--security-opt=no-new-privileges', '--pids-limit=' + str(expected['pids']),
                    '--memory=' + str(expected['memory']), '--memory-swap=' + str(expected['memory']),
                    '--cpus=' + str(Decimal(expected['nano_cpus']) / Decimal(1000000000)), '--user=' + expected['user'],
                    '--log-driver=none', '--tmpfs', '/tmp:' + expected['tmpfs']['/tmp'],
                    '--mount', 'type=bind,source=' + str(paths[0]) + ',target=/workspace,readonly',
                    '--mount', 'type=bind,source=' + str(paths[1]) + ',target=/scratch', '--workdir=/workspace']
            for item in env: args.extend(['--env', item])
            args.extend(['--entrypoint', entry[0], expected['image'], *cmd])
        except (OSError, KeyError, TypeError, ValueError, AttributeError):
            raise ValueError('Invalid container policy') from None
        try:
            created = self._run(args, deadline).decode('ascii', 'strict').strip()
            cid = cidfile.read_text(encoding='ascii').strip()
            if not _CID.fullmatch(cid) or created != cid: raise ValueError()
            info = self.inspect(cid, deadline)
            applied = inspect_profile(info, expected)
            state = info.get('State')
            if (not isinstance(state, dict) or state.get('Running') is not False or state.get('Status') != 'created' or
                    not calibration and profile_sha256(applied) != expected_profile): raise ValueError()
            return cid, applied
        except BaseException as error:
            try:
                if cidfile.is_file() and not cidfile.is_symlink() and cidfile.stat().st_nlink == 1 and cidfile.stat().st_size <= 128:
                    candidate = cidfile.read_text(encoding='ascii').strip()
                    if not _CID.fullmatch(candidate): raise RuntimeError('Container identity unconfirmed')
                    cleanup_deadline = max(deadline, time.monotonic() + 5)
                    if not self.absent(candidate, cleanup_deadline):
                        stop_owned(self, candidate, expected, cleanup_deadline)
                        self.remove_owned(candidate, expected, cleanup_deadline)
            except BaseException:
                raise RuntimeError('Created container cleanup unconfirmed') from None
            if isinstance(error, (KeyboardInterrupt, SystemExit)): raise
            raise RuntimeError('Created container policy unconfirmed') from None


def run_owned(client, container_id, expected, expected_profile, deadline, *, start_guard=None, cancelled=None):
    """Run an inspected container, reserving time to stop and verify it on timeout."""
    info = client.inspect(container_id, deadline)
    applied = inspect_profile(info, expected)
    state = info.get('State')
    if (profile_sha256(applied) != expected_profile or not isinstance(state, dict) or
            state.get('Running') is not False or state.get('Status') != 'created' or deadline - time.monotonic() <= 5):
        raise RuntimeError('Container admission unconfirmed')
    action = lambda: client.start(container_id, deadline)
    if start_guard is None: action()
    else: start_guard(action)
    try: process = client.wait_process(container_id)
    except BaseException:
        stop_owned(client, container_id, expected, deadline)
        raise
    try:
        while True:
            remaining = deadline - time.monotonic() - 5
            if remaining <= 0: raise TimeoutError('Container work timed out')
            if cancelled is not None and cancelled(): raise RuntimeError('Container work cancelled')
            try:
                stdout, _ = process.communicate(timeout=min(.25, remaining))
                break
            except subprocess.TimeoutExpired: pass
    except BaseException as error:
        stop_owned(client, container_id, expected, deadline)
        try: process.communicate(timeout=max(.1, deadline - time.monotonic()))
        except subprocess.TimeoutExpired:
            process.kill()
            process.communicate(timeout=2)
        raise
    try: exit_code = int(stdout.strip())
    except (ValueError, AttributeError): raise RuntimeError('Container exit unconfirmed') from None
    last = client.inspect(container_id, deadline)
    verify_owned(last, container_id, expected)
    state = last.get('State')
    if isinstance(state, dict) and state.get('Running') is True:
        stop_owned(client, container_id, expected, deadline)
        raise RuntimeError('Container exit unconfirmed')
    if (not isinstance(state, dict) or state.get('Running') is not False or state.get('Status') != 'exited' or
            getattr(process, 'returncode', None) != 0 or type(state.get('ExitCode')) is not int or
            not 0 <= state['ExitCode'] <= 255 or exit_code != state['ExitCode']):
        raise RuntimeError('Container exit unconfirmed')
    return state['ExitCode']


def run_owned_logged(client, container_id, expected, expected_profile, deadline, log_path, *, start_guard=None, cancelled=None):
    """Capture bounded logs outside the container and verify its actual exit."""
    info = client.inspect(container_id, deadline)
    state = info.get('State')
    if (profile_sha256(inspect_profile(info, expected)) != expected_profile or not isinstance(state, dict) or
            state.get('Running') is not False or state.get('Status') != 'created' or deadline - time.monotonic() <= 5):
        raise RuntimeError('Container admission unconfirmed')
    def start():
        process, stream = client.start_logged(container_id, Path(log_path))
        try:
            while True:
                info = client.inspect(container_id, deadline); verify_owned(info, container_id, expected)
                state = info.get('State')
                if isinstance(state, dict) and (state.get('Running') is True or state.get('Status') == 'exited'): break
                if process.poll() is not None or time.monotonic() >= deadline - 5: raise RuntimeError('Container start unconfirmed')
                time.sleep(.05)
            return process, stream
        except BaseException:
            stop_owned(client, container_id, expected, deadline)
            if process.poll() is None: process.kill(); process.wait(timeout=5)
            stream.close()
            raise
    process, stream = start() if start_guard is None else start_guard(start)
    try:
        while True:
            remaining = deadline - time.monotonic() - 5
            if remaining <= 0: raise TimeoutError('Container work timed out')
            if cancelled is not None and cancelled(): raise RuntimeError('Container work cancelled')
            if Path(log_path).stat().st_size > 64 * 1024 * 1024: raise RuntimeError('Container log limit exceeded')
            try: cli_exit = process.wait(timeout=min(.25, remaining)); break
            except subprocess.TimeoutExpired: pass
        info = client.inspect(container_id, deadline); verify_owned(info, container_id, expected)
        state = info.get('State')
        if isinstance(state, dict) and state.get('Running') is True:
            stop_owned(client, container_id, expected, deadline)
            raise RuntimeError('Container exit unconfirmed')
        if (not isinstance(state, dict) or state.get('Running') is not False or state.get('Status') != 'exited' or
                type(state.get('ExitCode')) is not int or not 0 <= state['ExitCode'] <= 255 or cli_exit != state['ExitCode']):
            raise RuntimeError('Container exit unconfirmed')
        return cli_exit
    except BaseException:
        stop_owned(client, container_id, expected, deadline)
        if process.poll() is None:
            try: process.wait(timeout=max(.1, deadline - time.monotonic()))
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
        raise
    finally: stream.close()


def isolation_probe(client, expected, cidfile, expected_profile, probe_source, canary, deadline, *, harness_sha256=None,
                    gradle_hashes=None, create_guard=None, start_guard=None, cancelled=None):
    """Exercise the actual configured container, including host and local network controls."""
    try:
        probe_source = _public_path(probe_source)
        expected = json.loads(_canonical(expected))
        if expected['stage'] != 'isolation' or harness_sha256 is not None and (not isinstance(harness_sha256, str) or not _SHA256.fullmatch(harness_sha256)):
            raise ValueError()
        if gradle_hashes is not None and (not isinstance(gradle_hashes, dict) or
                set(gradle_hashes) != {'gradle_harness_sha256', 'gradle_init_sha256', 'gradle_cache_manifest_sha256'} or
                any(not isinstance(value, str) or not _SHA256.fullmatch(value) for value in gradle_hashes.values())):
            raise ValueError()
        source = _safe_file(expected['mounts']['/workspace']['source'], probe_source)
        scratch = Path(expected['mounts']['/scratch']['source'])
        canary = Path(canary)
        if (not source.is_file() or source.stat().st_size > 16 * 1024 * 1024 or not scratch.is_dir() or any(scratch.iterdir()) or
                canary.is_symlink() or not canary.is_file() or canary.stat().st_nlink != 1 or canary.stat().st_size > 4096 or
                not canary.resolve(strict=True).is_relative_to(client.private_root) or
                canary.resolve(strict=True).is_relative_to(Path(expected['mounts']['/workspace']['source']).resolve(strict=True)) or
                canary.resolve(strict=True).is_relative_to(scratch.resolve(strict=True))):
            raise ValueError()
        source_hash, canary_bytes = _file_hash(source), canary.read_bytes()
    except (OSError, KeyError, TypeError, ValueError):
        raise ValueError('Invalid isolation probe') from None
    loop = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    outside = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    cid = None
    try:
        route = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        try:
            route.connect(('192.0.2.1', 9))
            host_address = route.getsockname()[0]
        finally: route.close()
        if host_address.startswith('127.') or host_address == '0.0.0.0': raise RuntimeError('Host network control unavailable')
        loop.bind(('127.0.0.1', 0)); loop.listen(2)
        outside.bind((host_address, 0)); outside.listen(2)
        def positive(server):
            with socket.create_connection(server.getsockname(), timeout=3) as peer:
                accepted, _ = server.accept()
                with accepted:
                    peer.sendall(b'X')
                    return len(accepted.recv(1))
        if positive(loop) != 1 or positive(outside) != 1: raise RuntimeError('Host network control unavailable')
        expected['entrypoint'] = ['/usr/local/bin/python']
        expected['cmd'] = ['-I', '-S', '-c', _PROBE_SCRIPT, probe_source, str(loop.getsockname()[1]), host_address,
                           str(outside.getsockname()[1]), 'harness' if harness_sha256 else 'none', 'gradle' if gradle_hashes else 'none']
        create = lambda: client.create(expected, cidfile, deadline, expected_profile)
        cid, applied = create() if create_guard is None else create_guard(create)
        if run_owned(client, cid, expected, expected_profile, deadline - 5, start_guard=start_guard, cancelled=cancelled) != 0:
            raise RuntimeError('Isolation probe failed')
        report = _safe_file(scratch, 'probe.json')
        if not report.is_file() or report.stat().st_size > 65536: raise RuntimeError('Isolation probe failed')
        observed = json.loads(report.read_bytes())
        if (not isinstance(observed, dict) or observed.get('source_sha256') != source_hash or
                harness_sha256 is not None and observed.get('harness_sha256') != harness_sha256 or
                gradle_hashes is not None and any(observed.get(key) != value for key, value in gradle_hashes.items()) or
                observed.get('read_private_errno') not in ('ENOENT', 'EACCES', 'EPERM') or
                observed.get('write_private_errno') not in ('ENOENT', 'EACCES', 'EPERM') or
                observed.get('write_source_errno') not in ('EROFS', 'EACCES', 'EPERM') or
                observed.get('network_errno') not in ('ENETUNREACH', 'EACCES', 'EPERM') or
                observed.get('host_loopback_errno') not in ('ECONNREFUSED', 'EACCES', 'EPERM') or
                type(observed.get('scratch_write_bytes')) is not int or observed['scratch_write_bytes'] != 1 or
                type(observed.get('sandbox_loopback_bytes')) is not int or observed['sandbox_loopback_bytes'] != 1 or
                canary.read_bytes() != canary_bytes):
            raise RuntimeError('Isolation probe failed')
        return {**observed, 'source_path': probe_source, 'profile_sha256': profile_sha256(applied), 'applied_profile': applied,
                'host_loopback_positive_bytes': 1, 'host_external_connect_errno': 0}
    except (OSError, ValueError, json.JSONDecodeError):
        raise RuntimeError('Isolation probe failed') from None
    finally:
        loop.close(); outside.close()
        if cid is None and Path(cidfile).is_file():
            try:
                candidate = Path(cidfile).read_text(encoding='ascii').strip()
                if _CID.fullmatch(candidate): cid = candidate
            except (OSError, UnicodeError): pass
        if cid is not None:
            stop_owned(client, cid, expected, deadline)
            client.remove_owned(cid, expected, deadline)


def inspect_profile(inspect, expected):
    """Verify the daemon's actual config, then hashable-normalize only its applied policy."""
    try:
        verify_owned(inspect, inspect['Id'], expected)
        if not _DIGEST.fullmatch(expected['image']) or inspect['Image'] != expected['image']:
            raise ValueError()
        config, host = inspect['Config'], inspect['HostConfig']
        if (config['User'] != expected['user'] or expected['user'] != '65534:65534' or config['WorkingDir'] != '/workspace' or
                config['Entrypoint'] != expected['entrypoint'] or config['Cmd'] != expected['cmd']):
            raise ValueError()
        env = config['Env']
        if (not isinstance(env, list) or any(not isinstance(item, str) or '=' not in item for item in env) or
                len({item.split('=', 1)[0] for item in env}) != len(env) or sorted(env) != sorted(expected['env']) or
                any(item.split('=', 1)[0] not in _ENV for item in env)):
            raise ValueError()
        if (host['NetworkMode'] != 'none' or host['ReadonlyRootfs'] is not True or host['Privileged'] is not False or
                set(host['CapDrop'] or []) != {'ALL'} or host.get('CapAdd') not in (None, []) or
                set(host['SecurityOpt'] or []) != {'no-new-privileges'} or host['PidMode'] != '' or host['IpcMode'] != 'private' or
                host.get('Devices') not in (None, []) or host.get('DeviceRequests') not in (None, []) or
                host.get('Binds') not in (None, []) or host.get('VolumesFrom') not in (None, []) or
                host['LogConfig']['Type'] != 'none' or host['Tmpfs'] != expected['tmpfs']):
            raise ValueError()
        if (type(expected['memory']) is not int or not 64 * 1024 * 1024 <= expected['memory'] <= 6 * 1024**3 or
                type(expected['nano_cpus']) is not int or not 1 <= expected['nano_cpus'] <= 2000000000 or
                type(expected['pids']) is not int or not 1 <= expected['pids'] <= 512 or
                host['Memory'] != expected['memory'] or host['MemorySwap'] != expected['memory'] or
                host['NanoCpus'] != expected['nano_cpus'] or host['PidsLimit'] != expected['pids']):
            raise ValueError()
        wanted = expected['mounts']
        mounts = inspect['Mounts']
        if (not isinstance(wanted, dict) or set(wanted) != {'/workspace', '/scratch'} or
                not isinstance(mounts, list) or len(mounts) != len(wanted)):
            raise ValueError()
        seen = {}
        for mount in mounts:
            target = mount['Destination']
            if (target in seen or target not in wanted or mount['Type'] != 'bind' or
                    mount['RW'] is not wanted[target]['writable'] or mount['Propagation'] != 'rprivate' or
                    not _same_path(mount['Source'], wanted[target]['source'])):
                raise ValueError()
            seen[target] = {'target': target, 'type': mount['Type'], 'writable': mount['RW'], 'propagation': mount['Propagation'],
                            'role': 'public_export' if target == '/workspace' else 'scratch'}
        if seen['/workspace']['writable'] or not seen['/scratch']['writable']:
            raise ValueError()
        return {'image': inspect['Image'], 'user': config['User'], 'env': sorted(env), 'network': host['NetworkMode'],
                'readonly_root': host['ReadonlyRootfs'], 'cap_drop': sorted(host['CapDrop']),
                'security_options': sorted(host['SecurityOpt']), 'pids': host['PidsLimit'], 'memory': host['Memory'],
                'memory_swap': host['MemorySwap'], 'nano_cpus': host['NanoCpus'], 'tmpfs': host['Tmpfs'],
                'mounts': [seen[key] for key in sorted(seen)]}
    except (KeyError, TypeError, ValueError, AttributeError):
        raise ValueError('Invalid container policy') from None


def _runner_module(config):
    path = Path(__file__).resolve().with_name('repair_runner.py')
    if _file_hash(path) != config['runner_sha256']: raise ValueError('Runner source changed')
    spec = importlib.util.spec_from_file_location('beacon_repair_runner_executor', path)
    if spec is None or spec.loader is None: raise ValueError('Runner source unavailable')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _check_workspace(config, context, runner):
    workspace = Path(context['workspace']).resolve(strict=True)
    coordinator = Path(config['coordinator_root']).resolve(strict=True)
    if workspace == coordinator or workspace in coordinator.parents or coordinator in workspace.parents: raise ValueError('Invalid worker')
    try: top = Path(runner._git(workspace, 'rev-parse', '--show-toplevel').decode('utf8').strip()).resolve(strict=True)
    except (UnicodeError, OSError): raise ValueError('Invalid worker') from None
    if (top != workspace or runner.repository_identity(workspace) != config['repo_identity'] or
            runner.repository_identity(coordinator) != config['repo_identity'] or
            runner._snapshot(workspace, set(config['editable_paths'] + config['release_paths']), config['release_paths']) != context['source_sha256']):
        raise ValueError('Worker source changed')
    return workspace


def _write_new(path, data):
    with Path(path).open('xb') as stream:
        stream.write(data); stream.flush(); os.fsync(stream.fileno())


def _verify_prior_isolation(config, context, runner):
    directory = Path(config['receipt_root']) / context['job_id'] / 'isolation'
    receipt = _private_json(directory / 'receipt.json')
    if not isinstance(receipt, dict) or not isinstance(receipt.get('source_sha256'), str) or not _SHA256.fullmatch(receipt['source_sha256']):
        raise ValueError('Isolation evidence unavailable')
    binding = {'job_id': context['job_id'], 'request_key': context['request_key'], 'policy_sha256': context['policy_sha256'],
               'source_sha256': receipt['source_sha256']}
    runner.verify_receipt(directory, binding, 'isolation', ['isolation.json'])
    proof = _private_json(directory / 'isolation.json')
    if (not isinstance(proof, dict) or proof.get('profile_sha256') != config['sandbox_sha256'] or
            proof.get('harness_sha256') != config['harness_sha256'] or
            any(config[key] is not None and proof.get(key) != config[key] for key in
                ('gradle_harness_sha256', 'gradle_init_sha256', 'gradle_cache_manifest_sha256')) or
            proof.get('read_private_errno') not in ('ENOENT', 'EACCES', 'EPERM') or
            proof.get('write_private_errno') not in ('ENOENT', 'EACCES', 'EPERM') or
            proof.get('write_source_errno') not in ('EROFS', 'EACCES', 'EPERM') or
            proof.get('network_errno') not in ('ENETUNREACH', 'EACCES', 'EPERM') or
            proof.get('host_loopback_errno') not in ('ECONNREFUSED', 'EACCES', 'EPERM') or
            proof.get('scratch_write_bytes') != 1 or proof.get('sandbox_loopback_bytes') != 1 or
            proof.get('host_loopback_positive_bytes') != 1 or proof.get('host_external_connect_errno') != 0):
        raise ValueError('Isolation evidence unavailable')


def _collect_junit(path, code, runner):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError('Invalid test evidence')
    raw = path.read_bytes()
    cases = runner.junit_cases(raw)
    if any(case.findall('error') for case in cases):
        raise ValueError('Invalid test evidence')
    failures = sum(len(case.findall('failure')) for case in cases)
    if code not in (0, 1) or code == 0 and failures or code == 1 and not failures:
        raise ValueError('Invalid test evidence')
    return raw


def _unsigned_apk(path):
    try:
        with zipfile.ZipFile(path) as archive:
            names = archive.namelist()
            if not names or len(names) != len(set(names)) or any(name.startswith('/') or '..' in name.split('/') for name in names): raise ValueError()
            if any(re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.I) for name in names): raise ValueError()
            offset = archive.start_dir
        if offset >= 16:
            with Path(path).open('rb') as stream:
                stream.seek(offset - 16)
                if stream.read(16) == b'APK Sig Block 42': raise ValueError()
    except (OSError, ValueError, zipfile.BadZipFile):
        raise ValueError('Stable APK is not verified unsigned') from None


def collect_gradle_build(workspace, scratch, log, output, work, config, context, runner):
    """Collect real Gradle outputs only after the sandbox process exits successfully."""
    try:
        workspace, scratch, output, work, log = map(Path, (workspace, scratch, output, work, log))
        if (not output.is_dir() or any(output.iterdir()) or not work.is_dir() or log.is_symlink() or not log.is_file() or
                log.stat().st_nlink != 1 or log.stat().st_size > 64 * 1024 * 1024 or
                runner._git(workspace, 'status', '--porcelain=v1', '-z', '--untracked-files=all')):
            raise ValueError()
        head = runner._git(workspace, 'rev-parse', 'HEAD').decode('ascii').strip()
        tree = runner._git(workspace, 'rev-parse', 'HEAD^{tree}').decode('ascii').strip()
        if not _COMMIT.fullmatch(head) or not _COMMIT.fullmatch(tree): raise ValueError()
        execution = _private_json(work / 'execution.json', limit=32 * 1024 * 1024)
        runner.verify_build_execution(execution, config['sandbox_sha256'])
        if (execution['source_sha256'] != context['source_sha256'] or execution['image'] != config['image'] or
                any(execution[key] != config[cfg] for key, cfg in (('harness_sha256', 'gradle_harness_sha256'),
                    ('init_sha256', 'gradle_init_sha256'), ('cache_manifest_sha256', 'gradle_cache_manifest_sha256')))):
            raise ValueError()
        def fresh(path):
            if not execution['started_ns'] - 2 * 10**9 <= path.stat().st_mtime_ns <= execution['finished_ns'] + 2 * 10**9:
                raise ValueError()
        fresh(log)
        text = log.read_bytes().decode('utf8', 'replace')
        if 'BUILD SUCCESSFUL' not in text or 'BUILD FAILED' in text: raise ValueError()
        wanted = {':app:' + name for name in _GRADLE_TASKS}
        rows = re.findall(r'(?m)^> Task (:app:[A-Za-z0-9]+)(?: ([A-Z-]+))?\s*$', text)
        tasks = [[name, outcome] for name, outcome in rows if name in wanted]
        if len(tasks) != len(wanted) or {row[0] for row in tasks} != wanted or any(row[1] for row in tasks): raise ValueError()
        version_file = _safe_file(workspace, config['plugin_manifest'])
        if not version_file.is_file() or version_file.stat().st_size > 262144: raise ValueError()
        versions = re.findall(rb'(?m)^version:\s*["\']?([0-9]+\.[0-9]+\.[0-9]+)["\']?\s*$', version_file.read_bytes())
        if versions != [context['release'].encode('ascii')]: raise ValueError()
        build = _safe_directory(scratch, 'build/app')
        paths = {'stable': 'outputs/apk/stable/app-stable-unsigned.apk', 'debug': 'outputs/apk/debug/app-debug.apk',
                 'androidtest': 'outputs/apk/androidTest/debug/app-debug-androidTest.apk'}
        apks = {name: _safe_file(build, relative) for name, relative in paths.items()}
        if any(not path.is_file() or path.stat().st_size > (96 if name == 'debug' else 64) * 1024 * 1024
               for name, path in apks.items()): raise ValueError()
        for path in apks.values(): fresh(path)
        _unsigned_apk(apks['stable'])
        xml_dir = _safe_directory(build, 'test-results/testDebugUnitTest')
        xml_files = sorted(xml_dir.glob('TEST-*.xml'))
        if not 1 <= len(xml_files) <= 2000: raise ValueError()
        xml = {}; totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}; size = 0; cases = []
        for path in xml_files:
            if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > 4 * 1024 * 1024: raise ValueError()
            fresh(path)
            raw = path.read_bytes(); size += len(raw)
            if size > 64 * 1024 * 1024 or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper(): raise ValueError()
            report_cases = runner.junit_cases(raw, allow_all_skipped=True); cases.extend(report_cases)
            for case in report_cases:
                if not case.get('name') or case.findall('failure') or case.findall('error') or len(case.findall('skipped')) > 1: raise ValueError()
                totals['tests'] += 1
                if case.findall('skipped'): totals['skipped'] += 1
            xml[path.name] = raw
        if totals['tests'] == totals['skipped']: raise ValueError()
        if runner.verify_gradle_events(log.read_bytes(), cases) != tasks: raise ValueError()
        lint = {}
        for variant in ('debug', 'stable'):
            path = _safe_file(build, 'reports/lint-results-' + variant + '.xml')
            fresh(path)
            runner.verify_lint(path)
            lint[variant] = path.read_bytes()
        for name, path in apks.items(): _write_new(output / (name + '.apk'), path.read_bytes())
        _write_new(output / 'build.log', log.read_bytes())
        with zipfile.ZipFile(output / 'junit.zip', 'x', compression=zipfile.ZIP_DEFLATED) as archive:
            for name, raw in sorted(xml.items()): archive.writestr(name, raw)
        for variant, raw in lint.items(): _write_new(work / ('lint-' + variant + '.xml'), raw)
        proof = {'source': {'head': head, 'tree': tree}, 'execution': execution, 'totals': totals,
                 'xml': {name: hashlib.sha256(raw).hexdigest() for name, raw in xml.items()}, 'task_outcomes': tasks,
                 'artifacts': {config['build_artifacts'][name]: _file_hash(path) for name, path in apks.items()},
                 'build_log_sha256': _file_hash(log), 'plugin_version': context['release'],
                 'lint_sha256': {name: hashlib.sha256(raw).hexdigest() for name, raw in lint.items()}}
        _write_new(output / 'build-proof.json', _canonical(proof) + b'\n')
        return {name: hashlib.sha256(raw).hexdigest() for name, raw in lint.items()}
    except (OSError, ValueError, TypeError, KeyError, UnicodeError, ET.ParseError, zipfile.BadZipFile):
        raise ValueError('Invalid Gradle build evidence') from None


def collect_gradle_lint(build_output, build_work, output, context, runner):
    try:
        build_output, build_work, output = map(Path, (build_output, build_work, output))
        if not output.is_dir() or any(output.iterdir()): raise ValueError()
        binding = {key: context[key] for key in ('job_id', 'request_key', 'policy_sha256', 'source_sha256')}
        runner.verify_receipt(build_output, binding, 'final_build', sorted(_ARTIFACTS['final_build']))
        proof = _private_json(build_output / 'build-proof.json', limit=16 * 1024 * 1024)
        if not isinstance(proof, dict) or not isinstance(proof.get('lint_sha256'), dict) or set(proof['lint_sha256']) != {'debug', 'stable'}:
            raise ValueError()
        values = {}
        for variant in ('debug', 'stable'):
            path = _safe_file(build_work, 'lint-' + variant + '.xml')
            runner.verify_lint(path)
            data = path.read_bytes()
            if hashlib.sha256(data).hexdigest() != proof['lint_sha256'][variant]: raise ValueError()
            values[variant] = data
        for variant, raw in values.items(): _write_new(output / ('lint-' + variant + '.xml'), raw)
    except (OSError, ValueError, TypeError, KeyError):
        raise ValueError('Invalid Gradle lint evidence') from None


def _run_worker(config_path, config_sha256, request_path, request_sha256):
    config = load_config(config_path, config_sha256)
    request = read_pinned_json(request_path, request_sha256, limit=65536)
    if (not isinstance(request, dict) or set(request) != {'stage', 'context', 'token'} or
            not isinstance(request['token'], str) or not _TOKEN.fullmatch(request['token'])): raise ValueError('Invalid worker request')
    stage = request['stage']
    context = validate_context(config, stage, request['context'])
    jobroot = Path(config['private_root']) / 'executor-jobs' / context['job_id']
    if Path(request_path).resolve(strict=True) != (jobroot / (stage + '.request.json')).resolve(strict=True):
        raise ValueError('Invalid worker request')
    token = request['token']
    active = jobroot / 'active.json'
    active_value = {'job_id': context['job_id'], 'stage': stage, 'name': 'beacon-repair-' + token, 'token': token,
                    'cidfile': str(jobroot / stage / 'container.cid')}
    ready = jobroot / (stage + '.ready.json')
    while not ready.is_file():
        if (jobroot / 'cancel.request').exists() or time.monotonic() >= context['deadline_monotonic']:
            raise RuntimeError('Worker cancelled or unconfirmed')
        time.sleep(.02)
    if _private_json(ready) != {'token': token, 'pid': os.getpid()}: raise RuntimeError('Worker ownership unconfirmed')
    if _private_json(active) != active_value or (jobroot / 'cancel.request').exists(): raise RuntimeError('Worker cancelled or unconfirmed')
    cancelled = lambda: (jobroot / 'cancel.request').exists()
    runner = _runner_module(config)
    workspace = _check_workspace(config, context, runner)
    if stage != 'isolation': _verify_prior_isolation(config, context, runner)
    if stage == 'final_lint':
        with _job_lock(jobroot, context['deadline_monotonic']):
            if (jobroot / 'cancel.request').exists() or _private_json(active) != active_value: raise RuntimeError('Worker cancelled')
            output_dir = Path(context['output_dir'])
            collect_gradle_lint(Path(config['receipt_root']) / context['job_id'] / 'final_build', jobroot / 'final_build', output_dir, context, runner)
            write_receipt(output_dir, stage, context, sorted(_ARTIFACTS['final_lint']))
            active.unlink()
        return 0
    work = jobroot / stage
    work.mkdir(mode=0o700)
    export, scratch = work / 'export', work / 'scratch'
    copied = export_public_source(workspace, export, config['public_manifest'],
                                  editable=set(config['editable_paths'] + config['release_paths']),
                                  deadline=context['deadline_monotonic'], cancelled=cancelled)
    if config['native_root'] is not None:
        native = Path(config['native_root']).resolve(strict=True)
        private = Path(config['private_root']).resolve(strict=True)
        if native == workspace or native == private or native in private.parents or private in native.parents: raise ValueError('Invalid public native source')
        if runner._git(native, 'rev-parse', 'HEAD').decode('ascii').strip() != config['native_commit']: raise ValueError('Native source changed')
        tracked = {item.decode('utf8') for item in runner._git(native, 'ls-tree', '-r', '--name-only', '-z', config['native_commit']).split(b'\0') if item}
        if not set(config['native_manifest']) <= tracked: raise ValueError('Native source is not tracked')
        native_copied = export_public_source(native, export / 'public-native', config['native_manifest'], editable=set(),
                                             deadline=context['deadline_monotonic'], cancelled=cancelled)
        copied.update({'public-native/' + key: value for key, value in native_copied.items()})
    _check_workspace(config, context, runner)
    scratch.mkdir(mode=0o700)
    expected = {'image': config['image'], 'user': '65534:65534', 'env': config['image_env'],
                'mounts': {'/workspace': {'source': str(export), 'writable': False}, '/scratch': {'source': str(scratch), 'writable': True}},
                'memory': config['memory'], 'nano_cpus': config['nano_cpus'], 'pids': config['pids'],
                'tmpfs': {'/tmp': 'rw,noexec,nosuid,size=32m'}, 'name': 'beacon-repair-' + token, 'token': token,
                'job_id': context['job_id'], 'stage': stage, 'entrypoint': ['/usr/local/bin/python'], 'cmd': []}
    client = DockerClient(config['docker_binary'], config['docker_sha256'], config['docker_host'], config['docker_config_dir'], config['private_root'])
    cidfile = work / 'container.cid'
    attempted = False
    slot = None; slot_owner = None
    def guarded_create(action):
        nonlocal attempted
        with _job_lock(jobroot, context['deadline_monotonic']):
            if (jobroot / 'cancel.request').exists() or _private_json(active) != active_value: raise RuntimeError('Worker cancelled')
            attempted = True
            return action()
    def guarded_start(action):
        with _job_lock(jobroot, context['deadline_monotonic']):
            if (jobroot / 'cancel.request').exists() or _private_json(active) != active_value: raise RuntimeError('Worker cancelled')
            return action()
    cid = None
    try:
        if stage == 'isolation':
            canary = work / 'private-canary'; _write_new(canary, secrets.token_bytes(32))
            gradle_hashes = ({key: config[key] for key in ('gradle_harness_sha256', 'gradle_init_sha256', 'gradle_cache_manifest_sha256')}
                             if config['gradle_harness_sha256'] is not None else None)
            proof = isolation_probe(client, expected, cidfile, config['sandbox_sha256'], context['probe_source'], canary,
                                    context['deadline_monotonic'], harness_sha256=config['harness_sha256'],
                                    gradle_hashes=gradle_hashes, create_guard=guarded_create, start_guard=guarded_start, cancelled=cancelled)
            code = 0
            output = _canonical(proof) + b'\n'
            artifact_name = 'isolation.json'
        elif stage == 'final_build':
            slot, slot_owner = acquire_gradle_slot(config['gradle_slot_root'], context['job_id'], token, cidfile,
                                                    context['deadline_monotonic'], cancelled=cancelled)
            command = gradle_command(stage)
            expected['entrypoint'], expected['cmd'] = command[:1], command[1:]
            started_ns = time.time_ns()
            cid, _ = guarded_create(lambda: client.create(expected, cidfile, context['deadline_monotonic'], config['sandbox_sha256']))
            code = run_owned_logged(client, cid, expected, config['sandbox_sha256'], context['deadline_monotonic'] - 5,
                                    work / 'build.log', start_guard=guarded_start, cancelled=cancelled)
            if code != 0: raise RuntimeError('Gradle build failed')
            _write_new(work / 'execution.json', _canonical({'started_ns': started_ns, 'finished_ns': time.time_ns(), 'exit_code': code,
                'container_id': cid, 'token': token, 'image': config['image'], 'sandbox_sha256': config['sandbox_sha256'],
                'harness_sha256': config['gradle_harness_sha256'], 'init_sha256': config['gradle_init_sha256'],
                'cache_manifest_sha256': config['gradle_cache_manifest_sha256'], 'command': command,
                'source_sha256': context['source_sha256'], 'inputs': copied}) + b'\n')
            artifact_name = None; output = None
        else:
            command = pytest_command(stage, config['test_nodes'][stage])
            expected['entrypoint'], expected['cmd'] = command[:1], command[1:]
            cid, _ = guarded_create(lambda: client.create(expected, cidfile, context['deadline_monotonic'], config['sandbox_sha256']))
            code = run_owned(client, cid, expected, config['sandbox_sha256'], context['deadline_monotonic'] - 5,
                             start_guard=guarded_start, cancelled=cancelled)
            output = _collect_junit(scratch / 'junit.xml', code, runner)
            artifact_name = 'junit.xml'
        _check_workspace(config, context, runner)
    finally:
        if cid is None and cidfile.is_file():
            candidate = cidfile.read_text(encoding='ascii').strip()
            if _CID.fullmatch(candidate): cid = candidate
        if cid is None and attempted:
            raise RuntimeError('Container creation unconfirmed')
        if cid is not None and not client.absent(cid, context['deadline_monotonic']):
            stop_owned(client, cid, expected, context['deadline_monotonic'])
            client.remove_owned(cid, expected, context['deadline_monotonic'])
        if cid is not None and not client.absent(cid, context['deadline_monotonic']): raise RuntimeError('Container cleanup unconfirmed')
        if slot is not None and cid is not None: release_gradle_slot(client, slot, slot_owner, context['deadline_monotonic'])
        if _private_json(active) != active_value: raise RuntimeError('Container ownership changed')
    with _job_lock(jobroot, context['deadline_monotonic']):
        if (jobroot / 'cancel.request').exists() or _private_json(active) != active_value: raise RuntimeError('Worker cancelled')
        output_dir = Path(context['output_dir'])
        if stage == 'final_build':
            lint_hashes = collect_gradle_build(workspace, scratch, work / 'build.log', output_dir, work, config, context, runner)
            artifacts = sorted(_ARTIFACTS['final_build'])
        else:
            _write_new(output_dir / artifact_name, output)
            lint_hashes = None
            artifacts = [artifact_name]
        provenance = {'job_id': context['job_id'], 'stage': stage, 'source_sha256': context['source_sha256'],
                      'policy_sha256': context['policy_sha256'], 'image': config['image'], 'sandbox_sha256': config['sandbox_sha256'],
                      'harness_sha256': config['harness_sha256'], 'public_manifest_sha256': hashlib.sha256(_canonical(copied)).hexdigest(),
                      'executor_config_sha256': config_sha256, 'request_sha256': request_sha256, 'public_inputs': copied,
                      'native_commit': config['native_commit'], 'container_id': cid, 'nonce': token,
                      'command': expected['entrypoint'] + expected['cmd'] if stage != 'isolation' else None,
                      'baseline': {name: copied[name] for name in config['baseline_paths']}, 'container_exit': code,
                      'artifacts': {name: _file_hash(output_dir / name) for name in artifacts}, 'lint_sha256': lint_hashes}
        _write_new(output_dir / 'provenance.json', _canonical(provenance) + b'\n')
        write_receipt(output_dir, stage, context, artifacts)
        active.unlink()
    return code


def _main():
    if len(sys.argv) != 6 or sys.argv[1] != '--worker': return 2
    try: return _run_worker(*sys.argv[2:])
    except BaseException: return 2


if __name__ == '__main__':
    raise SystemExit(_main())
