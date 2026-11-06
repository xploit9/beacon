"""Synthetic, isolated tests for the trusted permanent repair runner."""
import base64
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
import threading
import time
import types
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
SCOPE = ('fixture-owner', 'fixture-provider', 'default')


class Connection(sqlite3.Connection):
    def __exit__(self, *args):
        try:
            return super().__exit__(*args)
        finally:
            self.close()


class Mobile:
    def __init__(self, path):
        self.path = path
        self.LOCK = threading.RLock()

    def connect(self):
        db = sqlite3.connect(self.path, timeout=5, factory=Connection)
        db.row_factory = sqlite3.Row
        return db


def load_module():
    source = ROOT / 'repair_runner.py'
    assert source.exists(), 'The trusted repair runner has not been implemented'
    spec = importlib.util.spec_from_file_location('beacon_repair_runner_test', source)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def module():
    return load_module


@pytest.fixture
def mobile(tmp_path):
    return Mobile(tmp_path / 'queue.sqlite')


def incident(**extra):
    return {'id': 'a' * 32, 'generation': 1, 'component': 'voice', 'operation': 'discover_tools',
            'error_code': 'timeout', 'provider': 'grok', 'app_version_code': 141, 'plugin_version': '1.1.31', **extra}


def request_key(event):
    return hashlib.sha256((event['id'] + ':' + str(event['generation'])).encode()).hexdigest()


def policy():
    return {'base_commit': 'b' * 40, 'repo_identity': 'c' * 64, 'public_files': {'src/service.py': 'd' * 64},
            'source_paths': ['src/service.py'], 'test_paths': ['tests/test_regression.py'], 'release_paths': ['app/build.gradle.kts', 'app/plugin.yaml'],
            'reproduction_paths': ['tests/existing.py'],
            'expected_reproduction': ['fixture.existing.test_existing_failure'], 'expected_regression': ['fixture.regression.test_regression'],
            'allowed_skips': [],
            'daily_limit': 2, 'max_files': 4, 'max_file_bytes': 65536, 'max_patch_bytes': 131072,
            'stage_timeout': 5, 'job_timeout': 60, 'owner_name': 'Fixture Owner', 'owner_email': 'owner' + chr(64) + 'example.test',
            'signer_sha256': 'e' * 64, 'sandbox_sha256': 'f' * 64, 'public_repo': 'fixture/beacon', 'release': '1.2.3', 'version_code': 123,
            'required_build_tasks': [':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
                                     ':app:assembleStable', ':app:lintDebug', ':app:lintStable'],
            'build_artifacts': {'stable': 'app/build/stable.apk', 'debug': 'app/build/debug.apk', 'androidtest': 'app/build/androidtest.apk'},
            'plugin_manifest': 'app/plugin.yaml', 'package_name': 'dev.example.app',
            'release_assets': {'apk': 'Beacon-1.2.3.apk', 'metadata': 'Beacon-1.2.3.json', 'checksums': 'SHA256SUMS.txt'},
            'model_provider': 'grok', 'model_id': 'grok-fixture',
            'red_failure_types': {'reproduction': ['AssertionError'], 'regression': ['AssertionError']},
            'scope_sha256': hashlib.sha256(json.dumps(list(SCOPE), sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest(),
            'incident_selector': {'component': 'voice', 'operation': 'discover_tools', 'error_code': 'timeout', 'provider': 'grok',
                                  'app_version_code': 141, 'plugin_version': '1.1.31', 'stack_fingerprint': None},
            'minimum_tests': {'final_reproduction': 1, 'final_regression': 1, 'final_unit': 1, 'final_plugin': 1,
                              'final_build': 1, 'final_device': 1, 'final_live': 1}}


def policy_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode()).hexdigest()


def make_runner(module, mobile, tmp_path, *, config=None, allocator=None, model=None, stages=None, idle=None, confirm=None, current=None):
    config = config or policy()
    return module.RepairRunner(mobile, tmp_path / 'owner-repo', tmp_path / 'private-evidence', config, policy_hash(config),
        allocator or (lambda *_: None), model or (lambda *_: None), stages or (lambda *_: None),
        idle or (lambda _scope, _phase, action: action()), confirm or (lambda *_args, **_kwargs: None), current or (lambda *_: True))


def test_import_and_construction_start_no_threads_or_storage(module, tmp_path, monkeypatch):
    module = module()
    class NoIO:
        @property
        def LOCK(self):
            raise AssertionError('Construction must not open storage')
    monkeypatch.setattr(threading.Thread, 'start', lambda *_: pytest.fail('Construction started a thread'))
    make_runner(module, NoIO(), tmp_path)


def test_submission_is_durable_and_idempotent_without_starting_a_model(module, mobile, tmp_path):
    module = module()
    calls = []
    api = make_runner(module, mobile, tmp_path, model=lambda *_: calls.append('model'))
    event = incident(); key = request_key(event)
    first = api.submit(SCOPE, key, event)
    again = make_runner(module, mobile, tmp_path, model=lambda *_: calls.append('model')).submit(SCOPE, key, event)
    assert first == again and first['status'] == 'queued'
    assert first['job_id'].startswith('job-')
    assert calls == []
    with sqlite3.connect(mobile.path) as db:
        assert db.execute('SELECT count(*) FROM repair_jobs').fetchone()[0] == 1


def test_submission_rejects_untrusted_content_and_reused_key_across_scope(module, mobile, tmp_path):
    module = module()
    api = make_runner(module, mobile, tmp_path)
    event = incident(); key = request_key(event)
    for bad in (incident(message='private-canary'), incident(error_code='private-canary'), incident(provider='astra')):
        with pytest.raises(ValueError) as failure:
            api.submit(SCOPE, key, bad)
        assert 'private-canary' not in str(failure.value)
    api.submit(SCOPE, key, event)
    with pytest.raises(ValueError):
        api.submit(('another-owner', 'fixture-provider', 'default'), key, event)
    assert b'private-canary' not in mobile.path.read_bytes()


def test_only_the_pinned_scope_and_incident_can_enter_the_queue(module, mobile, tmp_path):
    module = module(); api = make_runner(module, mobile, tmp_path)
    matching = incident(); other = incident(id='b' * 32, operation='invoke_tool')
    assert api.accepts(SCOPE, request_key(matching), matching) is True
    assert api.accepts(SCOPE, request_key(other), other) is False
    assert api.accepts(('another-owner', 'fixture-provider', 'default'), request_key(matching), matching) is False
    api.submit(SCOPE, request_key(matching), matching)
    with pytest.raises(ValueError): api.submit(SCOPE, request_key(other), other)
    with sqlite3.connect(mobile.path) as db:
        assert db.execute('SELECT count(*) FROM repair_jobs').fetchone()[0] == 1


def test_policy_is_hash_pinned_and_refuses_protected_or_escaping_paths(module, mobile, tmp_path):
    module = module()
    config = policy()
    with pytest.raises(ValueError):
        module.RepairRunner(mobile, tmp_path / 'repo', tmp_path / 'private', config, '0' * 64,
                            lambda *_: None, lambda *_: None, lambda *_: None, lambda *_: None, lambda *_: None, lambda *_: True)
    for unsafe in ('../secret.py', '/absolute.py', 'src/../../escape.py', '.github/workflows/release.yml', 'app/build.gradle.kts', 'src/AGENTS.md', 'Grok.txt', 'src/CON.py', 'src/name.py.'):
        bad = policy(); bad['source_paths'] = [unsafe]
        with pytest.raises(ValueError):
            make_runner(module, mobile, tmp_path, config=bad)
    for field, value in (('model_provider', 'astra'), ('model_id', 'astra-routed'), ('required_build_tasks', [':app:assembleStable']),
                         ('red_failure_types', {'reproduction': ['CompilerError'], 'regression': ['AssertionError']}),
                         ('reproduction_paths', ['tests/test_regression.py']), ('expected_reproduction', ['ambiguous_name'])):
        bad = policy(); bad[field] = value
        with pytest.raises(ValueError): make_runner(module, mobile, tmp_path, config=bad)


def test_proposal_accepts_only_bounded_file_edits_with_exact_preimages(module, tmp_path):
    module = module()
    root = tmp_path / 'work'; (root / 'src').mkdir(parents=True); (root / 'tests').mkdir()
    (root / 'src/service.py').write_bytes(b'before\n')
    config = policy()
    before = hashlib.sha256(b'before\n').hexdigest()
    valid = {'edits': [{'path': 'src/service.py', 'before_sha256': before, 'content': 'after\n'},
                       {'path': 'tests/test_regression.py', 'before_sha256': None, 'content': 'assert False\n'}]}
    assert len(module.validate_proposal(json.dumps(valid), root, config)) == 2
    for change in (
        {'commands': ['delete everything']},
        {'edits': valid['edits'], 'shell': 'not allowed'},
        {'edits': [dict(valid['edits'][0], path='../escape.py'), valid['edits'][1]]},
        {'edits': [dict(valid['edits'][0], before_sha256='0' * 64), valid['edits'][1]]},
        {'edits': [dict(valid['edits'][0], content='x' * 65537), valid['edits'][1]]},
    ):
        with pytest.raises(ValueError):
            module.validate_proposal(json.dumps(change), root, config)


def test_model_edit_cannot_follow_a_hardlink_out_of_its_worktree(module, tmp_path):
    module = module(); root = tmp_path / 'work'; (root / 'src').mkdir(parents=True); (root / 'tests').mkdir()
    external = tmp_path / 'private-fixture'; external.write_bytes(b'outside\n')
    os.link(external, root / 'src/service.py')
    proposal = {'edits': [{'path': 'src/service.py', 'before_sha256': hashlib.sha256(b'outside\n').hexdigest(), 'content': 'change\n'},
                          {'path': 'tests/test_regression.py', 'before_sha256': None, 'content': 'assert True\n'}]}
    with pytest.raises(ValueError): module.validate_proposal(json.dumps(proposal), root, policy())
    assert external.read_bytes() == b'outside\n'


def test_model_input_is_public_pinned_and_excludes_scope_and_private_fields(module, tmp_path):
    module = module()
    root = tmp_path / 'work'; (root / 'src').mkdir(parents=True)
    (root / 'src/service.py').write_bytes(b'public\n')
    config = policy(); config['public_files'] = {'src/service.py': hashlib.sha256(b'public\n').hexdigest()}
    safe = module.model_input(root, config, incident(call_id='a' * 32, request_id='b' * 32))
    assert safe['files'] == [{'path': 'src/service.py', 'sha256': config['public_files']['src/service.py'], 'content': 'public\n'}]
    assert 'call_id' not in json.dumps(safe) and 'request_id' not in json.dumps(safe) and 'fixture-owner' not in json.dumps(safe)
    (root / 'src/service.py').write_bytes(b'private-canary\n')
    with pytest.raises(ValueError):
        module.model_input(root, config, incident())


def test_junit_requires_expected_behavioral_red_and_real_green(module, tmp_path):
    module = module(); report = tmp_path / 'result.xml'
    report.write_bytes(b'<testsuite><testcase classname="fixture" name="test_regression"><failure type="AssertionError" message="expected"/></testcase></testsuite>')
    module.verify_junit(report, ['test_regression'], red=True, exit_code=1, allowed_skips=[])
    for code in (2, -1):
        with pytest.raises(ValueError): module.verify_junit(report, ['test_regression'], red=True, exit_code=code, allowed_skips=[])
    for invalid in (
        b'<testsuite><testcase name="test_regression"><error type="CompilerError"/></testcase></testsuite>',
        b'<testsuite><testcase name="test_regression"><failure type="CompilerError"/></testcase></testsuite>',
        b'<testsuite><testcase name="other"><failure type="AssertionError"/></testcase></testsuite>',
        b'<testsuite><testcase name="test_regression"><skipped/></testcase></testsuite>',
        b'<testsuite/>',
    ):
        report.write_bytes(invalid)
        with pytest.raises(ValueError): module.verify_junit(report, ['test_regression'], red=True, exit_code=1, allowed_skips=[])
    report.write_bytes(b'<testsuite><testcase classname="fixture" name="test_regression"/></testsuite>')
    module.verify_junit(report, ['test_regression'], red=False, exit_code=0, allowed_skips=[])
    with pytest.raises(ValueError): module.verify_junit(report, ['test_regression'], red=False, exit_code=1, allowed_skips=[])
    report.write_bytes(b'<testsuite><testcase name="test_regression"><failure type="java.lang.IllegalArgumentException"/></testcase></testsuite>')
    module.verify_junit(report, ['test_regression'], red=True, exit_code=1, allowed_skips=[], failure_types=['IllegalArgumentException'])
    with pytest.raises(ValueError): module.verify_junit(report, ['test_regression'], red=True, exit_code=1, allowed_skips=[])
    report.write_bytes(b'<testsuite><testcase name="test_regression"><failure message="AssertionError: expected"/></testcase></testsuite>')
    module.verify_junit(report, ['test_regression'], red=True, exit_code=1, allowed_skips=[])


@pytest.mark.parametrize('bad', [
    '<testsuite tests="8" failures="0" errors="0" skipped="0"><testcase classname="proof" name="test_ok"/></testsuite>',
    '<testsuite tests="1" failures="1"><testcase classname="proof" name="test_ok"/></testsuite>',
    '<testsuite><testcase classname="proof" name="test_ok"/><testcase classname="proof" name="test_ok"/></testsuite>',
    '<testsuite><testcase classname="proof" name="test_ok"><failure type="AssertionError"/><skipped/></testcase></testsuite>',
    '<testsuite><testcase classname="proof" name="test_ok"/><error message="unsupported platform"/></testsuite>',
    '<testsuite><wrapper><testcase classname="proof" name="test_ok"/></wrapper></testsuite>',
])
def test_junit_rejects_fabricated_aggregates_duplicate_cases_and_hidden_errors(module, tmp_path, bad):
    runner = module(); report = tmp_path / 'junit.xml'
    report.write_text('<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="proof" name="test_ok"/></testsuite>')
    assert runner.verify_junit(report, ['proof.test_ok'], red=False, exit_code=0, allowed_skips=[]) == 1
    report.write_text(bad)
    with pytest.raises(ValueError):
        runner.verify_junit(report, ['proof.test_ok'], red=False, exit_code=0, allowed_skips=[])


def test_junit_refuses_all_skipped_or_hardlinked_evidence(module, tmp_path):
    runner = module(); report = tmp_path / 'junit.xml'
    report.write_text('<testsuite><testcase classname="proof" name="test_skip"><skipped/></testcase></testsuite>')
    with pytest.raises(ValueError):
        runner.verify_junit(report, [], red=False, exit_code=0, allowed_skips=['proof.test_skip'])
    report.write_text('<testsuite><testcase classname="proof" name="test_ok"/></testsuite>')
    link = tmp_path / 'linked.xml'; os.link(report, link)
    with pytest.raises(ValueError):
        runner.verify_junit(report, ['proof.test_ok'], red=False, exit_code=0, allowed_skips=[])


def test_gradle_can_collect_an_ignored_class_without_counting_it_as_executed(module):
    runner = module(); raw = b'<testsuite tests="1" skipped="1"><testcase classname="fixture" name="owner_live"><skipped/></testcase></testsuite>'
    with pytest.raises(ValueError): runner.junit_cases(raw)
    cases = runner.junit_cases(raw, allow_all_skipped=True)
    assert len(cases) == 1 and len(cases[0].findall('skipped')) == 1


def test_real_pytest_red_green_and_collection_error_are_distinguished(module, tmp_path):
    module = module(); source = tmp_path / 'test_behavior.py'
    env = {name: os.environ[name] for name in ('PATH', 'SYSTEMROOT', 'WINDIR', 'PATHEXT', 'COMSPEC') if name in os.environ}
    env.update(PYTHONDONTWRITEBYTECODE='1', PYTEST_DISABLE_PLUGIN_AUTOLOAD='1', TEMP=str(tmp_path), TMP=str(tmp_path))
    config = tmp_path / 'pytest.ini'; config.write_text('[pytest]\n', encoding='utf8')
    def run(label, text):
        source.write_text(text, encoding='utf8')
        report = tmp_path / (label + '.xml')
        result = subprocess.run([sys.executable, '-m', 'pytest', '-q', '-p', 'no:cacheprovider', '--noconftest', '-c', str(config), '--junitxml=' + str(report), source.name],
                                cwd=tmp_path, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20)
        return report, result.returncode
    red, code = run('red', 'def test_repro():\n    assert 1 == 2\n')
    module.verify_junit(red, ['test_behavior.test_repro'], red=True, exit_code=code, allowed_skips=[])
    green, code = run('green', 'def test_repro():\n    assert 1 == 1\n')
    module.verify_junit(green, ['test_behavior.test_repro'], red=False, exit_code=code, allowed_skips=[])
    broken, code = run('broken', 'def test_repro(:\n    pass\n')
    with pytest.raises(ValueError): module.verify_junit(broken, ['test_behavior.test_repro'], red=True, exit_code=code, allowed_skips=[])


def test_git_checks_do_not_inherit_unrelated_or_git_override_environment(module, tmp_path, monkeypatch):
    module = module()
    monkeypatch.setenv('BEACON_SECRET_FIXTURE', 'must-not-reach-git')
    monkeypatch.setenv('GIT_DIR', 'must-not-reach-git')
    def run(_args, **kwargs):
        if 'BEACON_SECRET_FIXTURE' in kwargs['env'] or 'GIT_DIR' in kwargs['env']:
            pytest.fail('Unrelated environment was inherited', pytrace=False)
        return types.SimpleNamespace(returncode=0, stdout=b'head\n')
    monkeypatch.setattr(module.subprocess, 'run', run)
    assert module._git(tmp_path, 'rev-parse', 'HEAD') == b'head\n'


def test_idle_fence_can_defer_a_job_without_starting_or_charging_it(module, mobile, tmp_path):
    module = module(); calls = []
    api = make_runner(module, mobile, tmp_path, idle=lambda *_: False, allocator=lambda *_: calls.append('allocator'))
    event = incident(); api.submit(SCOPE, request_key(event), event)
    assert api.drain() == {'status': 'deferred'}
    with sqlite3.connect(mobile.path) as db:
        assert db.execute('SELECT state, started FROM repair_jobs').fetchone() == ('queued', None)
    assert calls == []


def test_timeout_during_admission_keeps_global_slot_unconfirmed(module, mobile, tmp_path):
    module = module(); entered = threading.Event(); release = threading.Event(); calls = []
    def fence(_scope, _phase, action):
        entered.set()
        release.wait(5)
        return action()
    config = policy(); config.update(stage_timeout=1, job_timeout=3)
    api = make_runner(module, mobile, tmp_path, config=config, idle=fence, allocator=lambda *_: calls.append('allocator'))
    first = incident(); second = incident(id='b' * 32)
    api.submit(SCOPE, request_key(first), first); api.submit(SCOPE, request_key(second), second)
    try:
        assert api.drain() == {'status': 'unconfirmed'}
        assert entered.is_set()
        assert api.drain() == {'status': 'busy'}
    finally:
        release.set()
    time.sleep(.1)
    with sqlite3.connect(mobile.path) as db:
        assert db.execute('SELECT state FROM repair_jobs WHERE request_key=?', (request_key(first),)).fetchone()[0] == 'unconfirmed'
    assert calls == []


def test_delayed_deferred_admission_cannot_claim_a_later_reservation(module, mobile, tmp_path):
    module = module(); delayed = []
    def fence(_scope, _phase, action):
        if not delayed:
            delayed.append(action)
            return False
        assert delayed[0]() is False
        return action()
    api = make_runner(module, mobile, tmp_path, idle=fence)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    assert api.drain() == {'status': 'deferred'}
    assert api.drain() == {'status': 'failed'}  # No workspace adapter is configured.


def test_global_claim_and_daily_cap_are_durable_across_runner_instances(module, mobile, tmp_path):
    module = module(); entered, release = threading.Event(), threading.Event(); answer = []
    config = policy(); config['daily_limit'] = 1
    def fence(_scope, _phase, action):
        entered.set(); release.wait(5); return action()
    first = make_runner(module, mobile, tmp_path, config=config, idle=fence)
    second = make_runner(module, Mobile(mobile.path), tmp_path, config=config)
    one, two = incident(), incident(id='b' * 32)
    first.submit(SCOPE, request_key(one), one); second.submit(SCOPE, request_key(two), two)
    thread = threading.Thread(target=lambda: answer.append(first.drain()), daemon=True)
    try:
        thread.start(); assert entered.wait(3)
        assert second.drain() == {'status': 'busy'}
    finally:
        release.set(); thread.join(5)
    assert answer == [{'status': 'failed'}]
    assert second.drain() == {'status': 'daily_limit'}


def test_stopping_watcher_during_callback_keeps_the_job_unconfirmed(module, mobile, tmp_path):
    module = module(); entered, release, stop = threading.Event(), threading.Event(), threading.Event()
    def allocate(_context): entered.set(); release.wait(5); return None
    api = make_runner(module, mobile, tmp_path, allocator=allocate)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    watcher = threading.Thread(target=lambda: api.watch(stop, .1), daemon=True)
    try:
        watcher.start(); assert entered.wait(3); stop.set(); watcher.join(3)
        assert not watcher.is_alive()
        with sqlite3.connect(mobile.path) as db:
            assert db.execute('SELECT state FROM repair_jobs').fetchone()[0] == 'unconfirmed'
        assert api.drain() == {'status': 'busy'}
    finally:
        stop.set(); release.set(); watcher.join(5)


def test_keyboard_interrupt_propagates_after_recording_uncertain_job(module, mobile, tmp_path):
    module = module(); api = make_runner(module, mobile, tmp_path)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    def interrupted(*_args): raise KeyboardInterrupt()
    api._run_job = interrupted
    with pytest.raises(KeyboardInterrupt): api.drain()
    with sqlite3.connect(mobile.path) as db:
        assert db.execute('SELECT state FROM repair_jobs').fetchone()[0] == 'unconfirmed'
    assert api.drain() == {'status': 'busy'}


def test_review_requires_the_pinned_provider_complete_coverage_and_no_comments(module, tmp_path):
    module = module(); report = tmp_path / 'ocr.json'
    review = {'status': 'completed', 'llm': {'provider': 'ocx', 'model': 'command-code/deepseek/deepseek-v4.1-flash'},
              'summary': {'files_reviewed': 2, 'comments': 0}, 'tool_calls': {'failure': 0}, 'comments': [], 'warnings': []}
    report.write_text(json.dumps(review), encoding='utf8')
    module.verify_review(report, ['src/service.py', 'tests/test_regression.py'], ['tests/test_regression.py', 'src/service.py'])
    changes = [{'status': 'completed_with_errors'}, {'warnings': ['partial']}, {'comments': [{'path': 'src/service.py'}]},
               {'llm': {'provider': 'ocx', 'model': 'different'}}, {'summary': {'files_reviewed': 1, 'comments': 0}},
               {'tool_calls': {'failure': 1}}]
    for change in changes:
        report.write_text(json.dumps({**review, **change}), encoding='utf8')
        with pytest.raises(ValueError): module.verify_review(report, ['src/service.py', 'tests/test_regression.py'], ['src/service.py', 'tests/test_regression.py'])
    report.write_text(json.dumps(review), encoding='utf8')
    with pytest.raises(ValueError): module.verify_review(report, ['src/service.py'], ['src/service.py', 'tests/test_regression.py'])


def test_stage_evidence_must_be_bound_to_job_source_and_real_file_bytes(module, tmp_path):
    module = module(); output = tmp_path / 'stage'; output.mkdir()
    (output / 'junit.xml').write_bytes(b'<testsuite/>')
    context = {'job_id': 'job-' + 'c' * 32, 'request_key': 'd' * 64, 'policy_sha256': 'e' * 64, 'source_sha256': 'f' * 64}
    receipt = {**context, 'stage': 'regression_green', 'artifacts': {'junit.xml': hashlib.sha256(b'<testsuite/>').hexdigest()}}
    (output / 'receipt.json').write_text(json.dumps(receipt), encoding='utf8')
    assert module.verify_receipt(output, context, 'regression_green', ['junit.xml']) == {'junit.xml': output / 'junit.xml'}
    for bad in ({**receipt, 'job_id': 'job-' + '0' * 32}, {**receipt, 'source_sha256': '0' * 64},
                {**receipt, 'artifacts': {'../escape': '0' * 64}}, {**receipt, 'stage': 'release'}):
        (output / 'receipt.json').write_text(json.dumps(bad), encoding='utf8')
        with pytest.raises(ValueError): module.verify_receipt(output, context, 'regression_green', ['junit.xml'])
    (output / 'receipt.json').write_text(json.dumps(receipt), encoding='utf8')
    (output / 'junit.xml').write_bytes(b'tampered')
    with pytest.raises(ValueError): module.verify_receipt(output, context, 'regression_green', ['junit.xml'])


def test_text_evidence_is_bounded_before_decoding(module, tmp_path):
    module = module(); path = tmp_path / 'evidence.txt'
    path.write_bytes(b'0123456789')
    assert module._read_text(path, 10) == '0123456789'
    with pytest.raises(ValueError): module._read_text(path, 9)
    path.write_bytes(b'\xff')
    with pytest.raises(ValueError): module._read_text(path, 10)


def test_isolation_requires_read_only_source_private_denial_and_writable_scratch(module, tmp_path):
    module = module(); config = policy(); canary = tmp_path / 'canary'; canary.write_bytes(b'private')
    proof = {'profile_sha256': config['sandbox_sha256'], 'read_private_errno': 'EACCES', 'write_private_errno': 'EACCES',
             'network_errno': 'ENETUNREACH', 'write_source_errno': 'EACCES', 'scratch_write_bytes': 1,
             'host_loopback_errno': 'ECONNREFUSED', 'host_loopback_positive_bytes': 1, 'host_external_connect_errno': 0, 'sandbox_loopback_bytes': 1,
             'source_path': 'src/service.py', 'source_sha256': config['public_files']['src/service.py']}
    module.verify_isolation(proof, config, canary, b'private')
    for bad in ({**proof, 'write_source_errno': None}, {**proof, 'network_errno': None}, {**proof, 'host_loopback_errno': None},
                    {**proof, 'sandbox_loopback_bytes': 0}, {**proof, 'host_loopback_positive_bytes': 0},
                    {**proof, 'host_external_connect_errno': 13}, {**proof, 'scratch_write_bytes': 0},
                {**proof, 'source_sha256': '0' * 64}, {**proof, 'profile_sha256': '0' * 64}):
        with pytest.raises(ValueError): module.verify_isolation(bad, config, canary, b'private')
    canary.write_bytes(b'changed')
    with pytest.raises(ValueError): module.verify_isolation(proof, config, canary, b'private')


def test_trusted_signing_may_add_signatures_but_cannot_change_the_built_apk_payload(module, tmp_path, monkeypatch):
    loaded = module(); config = policy(); commit = 'a' * 40
    manifest = b'version: 1.2.3\n'; monkeypatch.setattr(loaded, '_git', lambda *_: manifest)
    unsigned = tmp_path / 'unsigned.apk'; signed = tmp_path / 'stable.apk'
    def archive(path, entries):
        with zipfile.ZipFile(path, 'w') as output:
            for name, value in entries.items(): output.writestr(name, value)
    payload = {'AndroidManifest.xml': b'manifest', 'classes.dex': b'bytecode', 'META-INF/services/provider': b'provider'}
    archive(unsigned, payload)
    files = {name: tmp_path / name for name in ('metadata.json', 'stable.apk', 'aapt.txt', 'signer.txt', 'plugin.yaml')}
    files['aapt.txt'].write_text("package: name='dev.example.app' versionCode='123' versionName='1.2.3'\n")
    files['signer.txt'].write_text('Signer #1 certificate SHA-256 digest: ' + config['signer_sha256'] + '\n')
    files['plugin.yaml'].write_bytes(manifest)
    for change, accepted in (({}, True), ({'classes.dex': b'changed'}, False),
                             ({'META-INF/services/provider': b'changed'}, False)):
        archive(signed, {**payload, 'META-INF/MANIFEST.MF': b'signature manifest', 'META-INF/CERT.RSA': b'certificate', **change})
        meta = {'artifact_filename': config['release_assets']['apk'], 'package_name': config['package_name'],
                'version_code': config['version_code'], 'version_name': config['release'], 'plugin_version': config['release'],
                'certificate_sha256': config['signer_sha256'], 'sha256': hashlib.sha256(signed.read_bytes()).hexdigest(),
                'size_bytes': signed.stat().st_size}
        files['metadata.json'].write_text(json.dumps(meta))
        if accepted: assert loaded.verify_identity(files, config, tmp_path, commit, unsigned) == meta
        else:
            with pytest.raises(ValueError): loaded.verify_identity(files, config, tmp_path, commit, unsigned)


def test_timed_out_callbacks_remain_visible_until_they_really_stop(module, mobile, tmp_path):
    loaded = module(); api = make_runner(loaded, mobile, tmp_path); release = threading.Event()
    try:
        with pytest.raises(loaded._Unconfirmed): api._bounded(lambda: release.wait(10), .05)
        assert api.callbacks_running() == 1
        release.set()
        deadline = time.monotonic() + 2
        while api.callbacks_running() and time.monotonic() < deadline: time.sleep(.01)
        assert api.callbacks_running() == 0
        assert api._bounded(lambda: 7, 1) == 7
    finally: release.set()


def test_cancelled_stage_stops_its_adapter_and_reaps_the_owned_process(module, mobile, tmp_path, monkeypatch):
    api_module = module(); stopped = []; processes = []
    def launch(_stage, _context):
        process = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(30)'], stdin=subprocess.DEVNULL,
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        processes.append(process)
        return process
    launch.cancel = lambda job_id, deadline_monotonic: stopped.append(job_id)
    api = make_runner(api_module, mobile, tmp_path, stages=launch)
    event = incident(); key = request_key(event); queued = api.submit(SCOPE, key, event)
    api._state(key, 'running', 'test', from_states=('queued',))
    with api._db() as db: row = dict(db.execute('SELECT * FROM repair_jobs').fetchone())
    monkeypatch.setattr(api_module, '_snapshot', lambda *_: 'a' * 64)
    jobdir = tmp_path / 'job'; jobdir.mkdir()
    try:
        with pytest.raises(api_module._Unconfirmed):
            api._stage(row, tmp_path, jobdir, 'reproduction', set(), ['junit.xml'], time.monotonic() + .5)
        assert stopped == [queued['job_id']]
        assert len(processes) == 1 and processes[0].poll() is not None
    finally:
        for process in processes:
            if process.poll() is None: process.kill()
            process.wait(timeout=5)


def test_a_stage_launcher_that_times_out_is_cancelled_before_it_can_start_later(module, mobile, tmp_path, monkeypatch):
    loaded = module(); released = threading.Event(); cancelled = []
    def launch(_stage, _context):
        assert released.wait(5)
        assert cancelled
        return None
    def cancel(job_id, _deadline):
        cancelled.append(job_id); released.set()
    launch.cancel = cancel
    api = make_runner(loaded, mobile, tmp_path, stages=launch)
    event = incident(); key = request_key(event); queued = api.submit(SCOPE, key, event)
    api._state(key, 'running', 'test', from_states=('queued',))
    with api._db() as db: row = dict(db.execute('SELECT * FROM repair_jobs').fetchone())
    monkeypatch.setattr(loaded, '_snapshot', lambda *_: 'a' * 64)
    jobdir = tmp_path / 'job'; jobdir.mkdir()
    try:
        with pytest.raises(loaded._Unconfirmed):
            api._stage(row, tmp_path, jobdir, 'reproduction', set(), ['junit.xml'], time.monotonic() + .1)
        assert cancelled == [queued['job_id']]
        deadline = time.monotonic() + 2
        while api.callbacks_running() and time.monotonic() < deadline: time.sleep(.01)
        assert api.callbacks_running() == 0
    finally: released.set()


def test_container_errno_evidence_requires_the_exact_pinned_applied_mount_policy(module, tmp_path):
    api = module(); config = policy(); canary = tmp_path / 'canary'; canary.write_bytes(b'private')
    profile = {'image': 'sha256:' + 'a' * 64, 'user': '65534:65534', 'network': 'none',
               'readonly_root': True, 'cap_drop': ['ALL'], 'security_options': ['no-new-privileges'],
               'mounts': [{'target': '/workspace', 'type': 'bind', 'writable': False, 'propagation': 'rprivate', 'role': 'public_export'},
                          {'target': '/scratch', 'type': 'bind', 'writable': True, 'propagation': 'rprivate', 'role': 'scratch'}]}
    config['sandbox_sha256'] = policy_hash(profile)
    proof = {'profile_sha256': config['sandbox_sha256'], 'applied_profile': profile,
             'read_private_errno': 'ENOENT', 'write_private_errno': 'ENOENT', 'write_source_errno': 'EROFS',
             'network_errno': 'ENETUNREACH', 'scratch_write_bytes': 1, 'host_loopback_errno': 'ECONNREFUSED',
             'host_loopback_positive_bytes': 1, 'host_external_connect_errno': 0, 'sandbox_loopback_bytes': 1,
             'source_path': 'src/service.py', 'source_sha256': config['public_files']['src/service.py']}
    api.verify_isolation(proof, config, canary, b'private')
    for changed in ({'network': 'host'}, {'readonly_root': False}, {'user': '0:0'}, {'cap_drop': []},
                    {'security_options': []}, {'mounts': profile['mounts'] + [{'target': '/private'}]},
                    {'mounts': [{**profile['mounts'][0], 'writable': True}, profile['mounts'][1]]},
                    {'mounts': [{**profile['mounts'][0], 'role': 'host_repository'}, profile['mounts'][1]]}):
        unsafe = {**profile, **changed}; digest = policy_hash(unsafe)
        with pytest.raises(ValueError):
            api.verify_isolation({**proof, 'applied_profile': unsafe, 'profile_sha256': digest},
                                 {**config, 'sandbox_sha256': digest}, canary, b'private')
    for changed in ({'applied_profile': None}, {'profile_sha256': '0' * 64}):
        with pytest.raises(ValueError): api.verify_isolation({**proof, **changed}, config, canary, b'private')


def test_workspace_must_be_clean_separate_worktree_of_pinned_repository(module, tmp_path):
    module = module()
    repo, worker, foreign = tmp_path / 'repo', tmp_path / 'worker', tmp_path / 'foreign'
    repo.mkdir(); foreign.mkdir()
    def git(where, *args):
        return subprocess.run(['git', '-C', str(where), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
    git(repo, 'init'); git(repo, 'config', 'user.name', 'Fixture Owner'); git(repo, 'config', 'user.email', 'owner' + chr(64) + 'example.test'); git(repo, 'config', 'core.autocrlf', 'false')
    (repo / 'source.py').write_bytes(b'baseline\n'); git(repo, 'add', 'source.py'); git(repo, 'commit', '-m', 'baseline')
    git(repo, 'worktree', 'add', '--detach', str(worker), 'HEAD')
    base = git(repo, 'rev-parse', 'HEAD')
    identity = module.repository_identity(repo)
    module.verify_workspace(repo, worker, base, identity)
    for bad in (repo, foreign):
        with pytest.raises(ValueError): module.verify_workspace(repo, bad, base, identity)
    with pytest.raises(ValueError): module.verify_workspace(repo, worker, '0' * 40, identity)
    with pytest.raises(ValueError): module.verify_workspace(repo, worker, base, '0' * 64)
    (worker / 'source.py').write_bytes(b'dirty\n')
    with pytest.raises(ValueError): module.verify_workspace(repo, worker, base, identity)
    assert (repo / 'source.py').read_bytes() == b'baseline\n'


def test_missing_stage_evidence_cannot_call_model_or_publish(module, mobile, tmp_path):
    module = module(); repo, worker = tmp_path / 'repo', tmp_path / 'worker'; repo.mkdir()
    def git(*args):
        return subprocess.run(['git', '-C', str(repo), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
    git('init'); git('config', 'user.name', 'Fixture Owner'); git('config', 'user.email', 'owner' + chr(64) + 'example.test'); git('config', 'core.autocrlf', 'false')
    (repo / 'src').mkdir(); (repo / 'tests').mkdir(); (repo / 'src/service.py').write_bytes(b'baseline\n')
    (repo / 'tests/existing.py').write_bytes(b'baseline test\n'); git('add', '.'); git('commit', '-m', 'baseline')
    git('worktree', 'add', '--detach', str(worker), 'HEAD')
    config = policy(); config.update(base_commit=git('rev-parse', 'HEAD'), repo_identity=module.repository_identity(repo),
                                     public_files={'src/service.py': hashlib.sha256(b'baseline\n').hexdigest()})
    calls = []
    def stage(name, context):
        calls.append(name)
        return subprocess.Popen([sys.executable, '-c', 'pass'], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    api = module.RepairRunner(mobile, repo, tmp_path / 'evidence', config, policy_hash(config), lambda *_: worker,
                              lambda *_: calls.append('MODEL'), stage, lambda _scope, _phase, action: action(),
                              lambda *_a, **_k: calls.append('CONFIRM'), lambda *_: True)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    assert api.drain() == {'status': 'failed'}
    assert calls == ['isolation']
    assert (repo / 'src/service.py').read_bytes() == b'baseline\n'


def test_unreviewed_candidate_never_executes_its_tests_or_publishes(module, mobile, tmp_path):
    module = module(); repo, worker = tmp_path / 'repo', tmp_path / 'worker'; repo.mkdir()
    def git(*args):
        return subprocess.run(['git', '-C', str(repo), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
    git('init'); git('config', 'user.name', 'Fixture Owner'); git('config', 'user.email', 'owner' + chr(64) + 'example.test'); git('config', 'core.autocrlf', 'false')
    (repo / 'src').mkdir(); (repo / 'tests').mkdir(); (repo / 'src/service.py').write_bytes(b'baseline\n')
    (repo / 'tests/existing.py').write_bytes(b'baseline test\n'); git('add', '.'); git('commit', '-m', 'baseline')
    git('worktree', 'add', '--detach', str(worker), 'HEAD')
    config = policy(); config.update(base_commit=git('rev-parse', 'HEAD'), repo_identity=module.repository_identity(repo),
                                     public_files={'src/service.py': hashlib.sha256(b'baseline\n').hexdigest()})
    calls = []
    def stage(name, context):
        calls.append(name); output = Path(context['output_dir'])
        values = {
            'isolation': {'isolation.json': {'profile_sha256': config['sandbox_sha256'], 'read_private_errno': 'EACCES',
                                            'write_private_errno': 'EACCES', 'network_errno': 'ENETUNREACH', 'write_source_errno': 'EACCES',
                                            'host_loopback_errno': 'ECONNREFUSED', 'host_loopback_positive_bytes': 1, 'host_external_connect_errno': 0,
                                            'sandbox_loopback_bytes': 1, 'scratch_write_bytes': 1,
                                            'source_path': 'src/service.py', 'source_sha256': config['public_files']['src/service.py']}},
            'public_privacy': {'findings.json': [], 'positive.json': [{'RuleID': 'fixture'}], 'paths.json': ['src/service.py']},
            'reproduction': {'junit.xml': '<testsuite><testcase classname="fixture.existing" name="test_existing_failure"><failure type="AssertionError"/></testcase></testsuite>'},
            'candidate_review': {'ocr.json': {'status': 'completed_with_errors', 'llm': {'provider': 'ocx', 'model': 'command-code/deepseek/deepseek-v4.1-flash'},
                                              'summary': {'files_reviewed': 2, 'comments': 0}, 'tool_calls': {'failure': 0}, 'comments': [], 'warnings': ['partial']},
                                 'paths.json': ['src/service.py', 'tests/test_regression.py']}}
        artifacts = values[name]; hashes = {}
        for path, value in artifacts.items():
            data = value.encode() if isinstance(value, str) else json.dumps(value).encode()
            (output / path).write_bytes(data); hashes[path] = hashlib.sha256(data).hexdigest()
        binding = {key: context[key] for key in ('job_id', 'request_key', 'policy_sha256', 'source_sha256')}
        (output / 'receipt.json').write_text(json.dumps({**binding, 'stage': name, 'artifacts': hashes}), encoding='utf8')
        return subprocess.Popen([sys.executable, '-c', 'import sys; sys.exit(1)' if name == 'reproduction' else 'pass'],
                                stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def model(_payload):
        calls.append('MODEL')
        return json.dumps({'edits': [{'path': 'src/service.py', 'before_sha256': config['public_files']['src/service.py'], 'content': 'fixed\n'},
                                     {'path': 'tests/test_regression.py', 'before_sha256': None, 'content': 'raise RuntimeError()\n'}]})
    api = module.RepairRunner(mobile, repo, tmp_path / 'evidence', config, policy_hash(config), lambda *_: worker,
                              model, stage, lambda _scope, _phase, action: action(), lambda *_a, **_k: calls.append('CONFIRM'), lambda *_: True)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    assert api.drain() == {'status': 'failed'}
    assert calls == ['isolation', 'public_privacy', 'reproduction', 'MODEL', 'candidate_review']
    assert (repo / 'src/service.py').read_bytes() == b'baseline\n'


def test_build_proof_requires_actual_test_xml_artifacts_and_matching_commit(module, tmp_path):
    module = module(); config = policy(); commit, tree = '1' * 40, '2' * 40
    xml = b'<testsuite><testcase name="test_regression"/></testsuite>'
    archive = tmp_path / 'junit.zip'
    with zipfile.ZipFile(archive, 'w') as out: out.writestr('TEST-fixture.xml', xml)
    files = {'junit.zip': archive}
    for name in ('stable.apk', 'debug.apk', 'androidtest.apk', 'build.log'):
        files[name] = tmp_path / name; files[name].write_bytes(name.encode())
    build_log = (''.join('> Task ' + name + '\n' for name in config['required_build_tasks']) +
                 'BEACON_GRADLE_TESTS=[["","test_regression","SUCCESS"]]\nBUILD SUCCESSFUL\n').encode()
    files['build.log'].write_bytes(build_log)
    execution = {'started_ns': time.time_ns() - 10**9, 'finished_ns': time.time_ns(), 'exit_code': 0,
                 'container_id': '1' * 64, 'token': '2' * 32, 'image': 'sha256:' + 'a' * 64,
                 'sandbox_sha256': config['sandbox_sha256'], 'harness_sha256': 'c' * 64, 'init_sha256': 'd' * 64,
                 'cache_manifest_sha256': 'e' * 64, 'source_sha256': 'f' * 64, 'inputs': {'source.py': '0' * 64},
                 'command': ['/usr/local/bin/python', '-I', '/opt/beacon-repair/gradle_harness.py', 'final_build']}
    proof = {'source': {'head': commit, 'tree': tree}, 'execution': execution, 'totals': {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0},
             'xml': {'TEST-fixture.xml': hashlib.sha256(xml).hexdigest()},
             'task_outcomes': [[name, ''] for name in config['required_build_tasks']],
             'artifacts': {config['build_artifacts'][name]: hashlib.sha256(files[name + '.apk'].read_bytes()).hexdigest() for name in ('stable', 'debug', 'androidtest')},
             'build_log_sha256': hashlib.sha256(build_log).hexdigest(), 'plugin_version': config['release']}
    files['build-proof.json'] = tmp_path / 'build-proof.json'
    files['build-proof.json'].write_text(json.dumps(proof), encoding='utf8')
    module.verify_build_proof(files, config, commit, tree)
    fabricated = b'<testsuite><testcase name="fabricated_case"/></testsuite>'
    with zipfile.ZipFile(archive, 'w') as out: out.writestr('TEST-fixture.xml', fabricated)
    files['build-proof.json'].write_text(json.dumps({**proof, 'xml': {'TEST-fixture.xml': hashlib.sha256(fabricated).hexdigest()}}))
    with pytest.raises(ValueError): module.verify_build_proof(files, config, commit, tree)
    with zipfile.ZipFile(archive, 'w') as out: out.writestr('TEST-fixture.xml', xml)
    for bad in ({**proof, 'source': {'head': '0' * 40, 'tree': tree}},
                {**proof, 'totals': {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}},
                {**proof, 'task_outcomes': [[config['required_build_tasks'][0], 'SKIPPED']]},
                {**proof, 'xml': {'TEST-fixture.xml': '0' * 64}}):
        files['build-proof.json'].write_text(json.dumps(bad), encoding='utf8')
        with pytest.raises(ValueError): module.verify_build_proof(files, config, commit, tree)
    files['build-proof.json'].write_text(json.dumps(proof), encoding='utf8')
    files['stable.apk'].write_bytes(b'tampered')
    with pytest.raises(ValueError): module.verify_build_proof(files, config, commit, tree)
    files['stable.apk'].write_bytes(b'stable.apk')
    config['minimum_tests']['final_build'] = 2
    with pytest.raises(ValueError): module.verify_build_proof(files, config, commit, tree)


def test_publication_requires_exact_commit_tag_and_immutable_asset_set(module):
    module = module(); config = policy(); commit = '1' * 40
    release = {'id': 7, 'tag_name': 'v1.2.3', 'target_commitish': commit, 'draft': False, 'prerelease': False,
               'assets': [{'name': name} for name in config['release_assets'].values()]}
    refs = {'refs/heads/main': commit, 'refs/tags/v1.2.3': commit}
    module.verify_publication(release, refs, config, commit)
    for bad, bad_refs in (({**release, 'draft': True}, refs), ({**release, 'target_commitish': '2' * 40}, refs),
                          ({**release, 'assets': release['assets'] + release['assets'][:1]}, refs),
                          (release, {**refs, 'refs/tags/v1.2.3': '2' * 40})):
        with pytest.raises(ValueError): module.verify_publication(bad, bad_refs, config, commit)


@pytest.mark.parametrize('failure,expected', [('', 'fixed'), ('candidate_scan', 'failed'), ('reproduction_green', 'failed'),
                                              ('final_reproduction', 'failed'), ('final_unit', 'failed'), ('final_lint', 'failed'),
                                              ('final_gitleaks', 'failed'), ('final_identity', 'failed'), ('release_preflight', 'failed'),
                                              ('stale_incident', 'unconfirmed'), ('fresh_downloads', 'unconfirmed')])
def test_synthetic_pipeline_requires_all_evidence_before_confirmation(module, mobile, tmp_path, failure, expected):
    """Real fixture files, processes and Git objects; no real model, device or publication."""
    module = module(); repo, worker = tmp_path / 'repo', tmp_path / 'worker'; repo.mkdir()
    def git(where, *args):
        return subprocess.run(['git', '-C', str(where), *args], check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout.decode().strip()
    git(repo, 'init'); git(repo, 'config', 'user.name', 'Fixture Owner'); git(repo, 'config', 'user.email', 'owner' + chr(64) + 'example.test'); git(repo, 'config', 'core.autocrlf', 'false')
    for directory in ('src', 'tests', 'app'): (repo / directory).mkdir()
    (repo / 'src/service.py').write_bytes(b'baseline\n'); (repo / 'tests/existing.py').write_bytes(b'old test\n')
    (repo / 'app/plugin.yaml').write_bytes(b'version: 1.2.2\n'); (repo / 'app/build.gradle.kts').write_bytes(b'old version\n')
    git(repo, 'add', '.'); git(repo, 'commit', '-m', 'baseline'); base = git(repo, 'rev-parse', 'HEAD')
    git(repo, 'worktree', 'add', '--detach', str(worker), 'HEAD')
    config = policy(); config.update(base_commit=base, repo_identity=module.repository_identity(repo),
                                     public_files={'src/service.py': hashlib.sha256(b'baseline\n').hexdigest()}, stage_timeout=20, job_timeout=240)
    if failure == 'final_unit': config['minimum_tests']['final_unit'] = 2
    proposed = [{'path': 'src/service.py', 'before_sha256': config['public_files']['src/service.py'], 'content': 'fixed\n# unique-model-marker\n'},
                {'path': 'tests/test_regression.py', 'before_sha256': None, 'content': 'assert True  # unique-regression-marker\n'}]
    calls, fences = [], []
    apk_stream = io.BytesIO()
    with zipfile.ZipFile(apk_stream, 'w') as archive:
        archive.writestr('AndroidManifest.xml', b'fixture manifest')
        archive.writestr('classes.dex', b'fixture bytecode')
    apk = apk_stream.getvalue(); apk_hash = hashlib.sha256(apk).hexdigest()
    meta = {'artifact_filename': config['release_assets']['apk'], 'package_name': config['package_name'], 'version_code': 123,
            'version_name': '1.2.3', 'plugin_version': '1.2.3', 'certificate_sha256': config['signer_sha256'], 'sha256': apk_hash, 'size_bytes': len(apk)}
    meta_bytes = json.dumps(meta).encode()
    release = {'id': 7, 'tag_name': 'v1.2.3', 'draft': False, 'prerelease': False, 'assets': [{'name': n} for n in config['release_assets'].values()]}
    changed = sorted(['src/service.py', 'tests/test_regression.py', 'app/plugin.yaml', 'app/build.gradle.kts'])
    script = '''import base64, hashlib, json, pathlib, subprocess, sys
p=json.loads(sys.argv[1]); root=pathlib.Path(p["workspace"]); out=pathlib.Path(p["output"])
if p["action"] == "prepare":
    (root/"app/plugin.yaml").write_bytes(b"version: 1.2.3\\n")
    (root/"app/build.gradle.kts").write_bytes(b"version 123\\n")
if p["action"] == "commit":
    subprocess.run(["git","-C",str(root),"add","--",*p["changed"]],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    subprocess.run(["git","-C",str(root),"commit","-m","Repair fixture"],check=True,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
hashes={}
for name, value in p["artifacts"].items():
    data=base64.b64decode(value); (out/name).write_bytes(data); hashes[name]=hashlib.sha256(data).hexdigest()
(out/"receipt.json").write_text(json.dumps({**p["binding"],"stage":p["stage"],"artifacts":hashes}),encoding="utf8")
sys.exit(p["exit"])
'''
    def stage(name, context):
        calls.append(name); action = ''; code = 0
        binding = {key: context[key] for key in ('job_id', 'request_key', 'policy_sha256', 'source_sha256')}
        red = '<testsuite><testcase classname="%s" name="%s"><failure type="AssertionError"/></testcase></testsuite>'
        green = '<testsuite><testcase classname="%s" name="%s"/></testsuite>'
        if name == 'isolation':
            output = {'isolation.json': {'profile_sha256': config['sandbox_sha256'], 'read_private_errno': 'EACCES',
                                        'write_private_errno': 'EACCES', 'network_errno': 'ENETUNREACH', 'write_source_errno': 'EACCES',
                                        'host_loopback_errno': 'ECONNREFUSED', 'host_loopback_positive_bytes': 1, 'host_external_connect_errno': 0,
                                        'sandbox_loopback_bytes': 1, 'scratch_write_bytes': 1,
                                        'source_path': 'src/service.py', 'source_sha256': config['public_files']['src/service.py']}}
        elif name in ('public_privacy', 'candidate_scan', 'final_gitleaks', 'final_privacy'):
            if name == 'public_privacy': paths = list(config['public_files'])
            elif name == 'candidate_scan': paths = [edit['path'] for edit in proposed]
            else: paths = git(worker, 'ls-tree', '-r', '--name-only', 'HEAD').splitlines()
            output = {'findings.json': [{'RuleID': 'synthetic'}] if failure == name else [],
                      'positive.json': [{'RuleID': 'synthetic'}], 'paths.json': paths}
        elif name in ('reproduction', 'regression_red'):
            code = 1; output = {'junit.xml': red % (('fixture.existing', 'test_existing_failure') if name == 'reproduction' else ('fixture.regression', 'test_regression'))}
        elif name in ('regression_green', 'reproduction_green', 'final_reproduction', 'final_regression', 'final_unit', 'final_plugin', 'final_device', 'final_live'):
            test_name = 'test_regression' if 'regression' in name else 'test_existing_failure' if 'reproduction' in name else 'test_smoke'
            class_name = 'fixture.regression' if 'regression' in name else 'fixture.existing' if 'reproduction' in name else 'fixture.smoke'
            if failure == name: code = 1; output = {'junit.xml': red % (class_name, test_name)}
            else: output = {'junit.xml': green % (class_name, test_name)}
        elif name in ('candidate_review', 'final_review'):
            paths = [edit['path'] for edit in proposed] if name == 'candidate_review' else changed
            output = {'paths.json': paths, 'ocr.json': {'status': 'completed', 'llm': {'provider': 'ocx', 'model': 'command-code/deepseek/deepseek-v4.1-flash'},
                      'summary': {'files_reviewed': len(paths), 'comments': 0}, 'tool_calls': {'failure': 0}, 'comments': [], 'warnings': []}}
        elif name == 'prepare_release': action = 'prepare'; output = {'version.json': {'version_name': '1.2.3', 'version_code': 123}}
        elif name == 'commit': action = 'commit'; output = {'commit.json': {'driver': 'synthetic'}}
        elif name == 'final_build':
            xml = (green % ('fixture.smoke', 'test_smoke')).encode(); stream = io.BytesIO()
            with zipfile.ZipFile(stream, 'w') as archive: archive.writestr('TEST-fixture.xml', xml)
            binaries = {'stable': apk, 'debug': b'debug', 'androidtest': b'androidtest'}
            log = ('Synthetic pipeline fixture; no Gradle build\n' +
                   ''.join('> Task ' + task + '\n' for task in config['required_build_tasks']) +
                   'BEACON_GRADLE_TESTS=[["fixture.smoke","test_smoke","SUCCESS"]]\nBUILD SUCCESSFUL\n').encode()
            execution = {'started_ns': time.time_ns() - 10**9, 'finished_ns': time.time_ns(), 'exit_code': 0,
                         'container_id': '1' * 64, 'token': '2' * 32, 'image': 'sha256:' + 'a' * 64,
                         'sandbox_sha256': config['sandbox_sha256'], 'harness_sha256': 'c' * 64,
                         'init_sha256': 'd' * 64, 'cache_manifest_sha256': 'e' * 64,
                         'source_sha256': context['source_sha256'],
                         'inputs': {path: hashlib.sha256((worker / path).read_bytes()).hexdigest() for path in changed},
                         'command': ['/usr/local/bin/python', '-I', '/opt/beacon-repair/gradle_harness.py', 'final_build']}
            proof = {'source': {'head': git(worker, 'rev-parse', 'HEAD'), 'tree': git(worker, 'rev-parse', 'HEAD^{tree}')},
                     'execution': execution,
                     'totals': {'tests': 1, 'failures': 0, 'errors': 0, 'skipped': 0}, 'xml': {'TEST-fixture.xml': hashlib.sha256(xml).hexdigest()},
                     'task_outcomes': [[task, ''] for task in config['required_build_tasks']],
                     'artifacts': {config['build_artifacts'][key]: hashlib.sha256(value).hexdigest() for key, value in binaries.items()},
                     'build_log_sha256': hashlib.sha256(log).hexdigest(), 'plugin_version': '1.2.3'}
            output = {'build-proof.json': proof, 'junit.zip': stream.getvalue(), 'stable.apk': apk, 'debug.apk': binaries['debug'],
                      'androidtest.apk': binaries['androidtest'], 'build.log': log}
        elif name == 'final_lint':
            lint = '<issues><issue severity="Fatal"/></issues>' if failure == name else '<issues format="6"/>'
            output = {'lint-debug.xml': lint, 'lint-stable.xml': '<issues format="6"/>'}
        elif name == 'final_identity':
            output = {'metadata.json': meta_bytes, 'stable.apk': apk, 'plugin.yaml': b'version: 1.2.3\n',
                      'aapt.txt': "package: name='dev.example.app' versionCode='123' versionName='1.2.3'\n",
                      'signer.txt': 'Signer #1 certificate SHA-256 digest: ' + ('0' * 64 if failure == name else config['signer_sha256']) + '\n'}
        elif name == 'release_preflight': output = {'preflight.json': {'main_sha': base, 'repository': config['public_repo'], 'tag': 'v1.2.3', 'http_status': 200 if failure == name else 404}}
        elif name == 'publish':
            head = git(worker, 'rev-parse', 'HEAD'); release['target_commitish'] = head
            output = {'release.json': release, 'refs.json': {'refs/heads/main': head, 'refs/tags/v1.2.3': head}}
        elif name == 'fresh_downloads':
            sums = apk_hash + '  ' + config['release_assets']['apk'] + '\n' + hashlib.sha256(meta_bytes).hexdigest() + '  ' + config['release_assets']['metadata'] + '\n'
            output = {'download.apk': b'wrong-apk' if failure == name else apk, 'download.json': meta_bytes, 'checksums.txt': sums, 'release.json': release}
        else: raise AssertionError(name)
        encoded = {name: base64.b64encode(value if isinstance(value, bytes) else value.encode() if isinstance(value, str) else json.dumps(value).encode()).decode() for name, value in output.items()}
        payload = {'workspace': str(worker), 'output': context['output_dir'], 'action': action, 'changed': changed,
                   'binding': binding, 'stage': name, 'artifacts': encoded, 'exit': code}
        return subprocess.Popen([sys.executable, '-c', script, json.dumps(payload)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    def confirm(scope, ident, version, commit, checks, *, expected_request_key):
        calls.append('CONFIRM')
        assert scope == SCOPE and ident == 'a' * 32 and version == '1.2.3' and expected_request_key == request_key(incident())
        assert len(checks) == 12 and all(checks.values())
        return {'state': 'fix_published', 'release': version, 'commit': commit}
    def fence(_scope, phase, action): fences.append(phase); return action()
    def current(scope, ident, key, version):
        assert scope == SCOPE and ident == 'a' * 32 and key == request_key(incident())
        assert version == {'app_version_code': 141, 'plugin_version': '1.1.31'} and fences[-1] == 'publish'
        return failure != 'stale_incident'
    api = module.RepairRunner(mobile, repo, tmp_path / 'evidence', config, policy_hash(config), lambda *_: worker,
                              lambda _payload: json.dumps({'edits': proposed}), stage, fence, confirm, current)
    event = incident(); api.submit(SCOPE, request_key(event), event)
    assert api.drain() == {'status': expected}
    if failure == 'candidate_scan': assert calls.index('candidate_review') < calls.index('candidate_scan') and 'regression_red' not in calls
    else:
        assert calls.index('candidate_review') < calls.index('regression_red') < calls.index('regression_green') < calls.index('reproduction_green')
        if failure != 'reproduction_green': assert calls.index('reproduction_green') < calls.index('prepare_release')
    if not failure:
        assert calls.index('final_build') < calls.index('publish') < calls.index('fresh_downloads') < calls.index('CONFIRM')
        assert {'admit', 'modify', 'prepare_release', 'commit', 'final_device', 'final_live', 'publish'} <= set(fences)
    elif failure == 'stale_incident':
        assert 'publish' not in calls and 'CONFIRM' not in calls and api.drain() == {'status': 'busy'}
    elif failure != 'fresh_downloads':
        assert 'publish' not in calls and 'CONFIRM' not in calls
    else:
        assert 'publish' in calls and 'fresh_downloads' in calls and 'CONFIRM' not in calls
        assert api.drain() == {'status': 'busy'}
        with sqlite3.connect(mobile.path) as db:
            assert db.execute('SELECT state,phase FROM repair_jobs').fetchone() == ('unconfirmed', 'fresh_downloads')
    assert (repo / 'src/service.py').read_bytes() == b'baseline\n'
    assert b'unique-model-marker' not in mobile.path.read_bytes() and b'unique-regression-marker' not in mobile.path.read_bytes()
