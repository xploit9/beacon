"""Synthetic tests for trusted repair operator bindings; no live service calls."""
import hashlib
from contextlib import contextmanager
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from types import SimpleNamespace

import pytest

ROOT = Path(__file__).resolve().parents[1]


def trusted_driver():
    spec = importlib.util.spec_from_file_location('trusted_stage_fixture', ROOT / 'repair-tools/trusted_stage.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def test_trusted_privacy_rules_use_positive_controls_and_reject_new_literal_values():
    driver = trusted_driver()
    address = ('fixture' + chr(64) + 'example.test').encode()
    label = ('this' + chr(64) + 'Fixture.member').encode()
    assert {row['RuleID'] for row in driver.privacy_findings(address + b'\n' + label)} == set(driver.RULES)
    assert driver.privacy_findings(b'public source\n') == []
    assert driver.privacy_findings(address, address) == []
    assert driver.privacy_findings(address + b'\n' + address, address)


@pytest.mark.parametrize('stage', ['prepare_release', 'commit', 'final_device', 'final_live', 'final_identity', 'release_preflight', 'publish', 'fresh_downloads'])
def test_all_risky_trusted_stages_refuse_unbound_owner_helper_before_any_process(stage, tmp_path, monkeypatch):
    driver = trusted_driver(); output = tmp_path / 'output'; output.mkdir()
    monkeypatch.setattr(driver.subprocess, 'run', lambda *_a, **_k: pytest.fail('Unbound live stage spawned a process'))
    with pytest.raises(ValueError, match='unbound'):
        driver.execute(stage, {'output_dir': str(output), 'workspace': str(tmp_path)}, {}, None, None, SimpleNamespace(owner_helper=None))
    assert not any(output.iterdir())


def test_bound_owner_helper_protocol_has_a_real_safe_process_positive_control(tmp_path):
    driver = trusted_driver(); output = tmp_path / 'output'; output.mkdir()
    helper = tmp_path / 'helper.py'
    helper.write_text('import hashlib,json,pathlib,sys\n'
        'a=sys.argv;p=pathlib.Path(a[a.index("--request")+1]);raw=p.read_bytes()\n'
        'assert hashlib.sha256(raw).hexdigest()==a[a.index("--request-sha256")+1]\n'
        'out=pathlib.Path(json.loads(raw)["context"]["output_dir"])\n'
        '(out/"junit.xml").write_text("<testsuite tests=\\\"1\\\"><testcase name=\\\"synthetic_helper_control\\\"/></testsuite>")\n')
    request = tmp_path / 'request.json'; request.write_text(json.dumps({'context': {'output_dir': str(output)}}))
    config = {'python_binary': sys._base_executable, 'repository': str(tmp_path / 'repo'), 'worktrees_root': str(tmp_path / 'trees'),
              'stages': {'final_device': {'files': {str(helper): hashlib.sha256(helper.read_bytes()).hexdigest()}}}}
    args = SimpleNamespace(owner_helper=str(helper), request=str(request), request_sha256=hashlib.sha256(request.read_bytes()).hexdigest(),
                           operator_config=str(tmp_path / 'unused-config'), operator_config_sha256='a' * 64)
    driver.execute('final_device', {'output_dir': str(output), 'workspace': str(tmp_path), 'deadline_monotonic': time.monotonic() + 10},
                   config, SimpleNamespace(_ARTIFACTS={'final_device': {'junit.xml'}}), None, args)
    assert 'synthetic_helper_control' in (output / 'junit.xml').read_text()
    helper.write_text('raise RuntimeError("changed")')
    with pytest.raises(ValueError): driver._tool(config, 'final_device', str(helper))


@pytest.mark.parametrize('change', ['partial', 'coverage', 'comments', 'warnings', 'tool_failure'])
def test_ocr_driver_uses_only_pinned_provider_and_refuses_incomplete_report(operator, tmp_path, monkeypatch, change):
    driver = trusted_driver(); source = tmp_path / 'source'; source.mkdir(); work = tmp_path / 'work'; work.mkdir()
    output = tmp_path / 'output'; output.mkdir(); binary = tmp_path / 'ocr.exe'; binary.write_bytes(b'fixture-ocr')
    background = tmp_path / 'context.md'; background.write_text('Synthetic requirements; no provider request.')
    paths = ['source.py']; calls = []
    config = {'repository': str(tmp_path / 'repo'), 'worktrees_root': str(tmp_path / 'trees'),
              'stages': {'candidate_review': {'files': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (binary, background)}}}}
    def run(argv, *_a, **_k):
        calls.append(argv)
        assert argv[argv.index('--provider') + 1] == 'ocx'
        assert argv[argv.index('--model') + 1] == 'command-code/deepseek/deepseek-v4.1-flash'
        value = {'status': 'completed', 'llm': {'provider': 'ocx', 'model': 'command-code/deepseek/deepseek-v4.1-flash'},
                 'summary': {'files_reviewed': 1, 'comments': 0}, 'tool_calls': {'failure': 0}, 'comments': [], 'warnings': []}
        if change == 'partial': value['status'] = 'completed_with_errors'
        if change == 'coverage': value['summary']['files_reviewed'] = 0
        if change == 'comments': value['comments'] = [{'path': 'source.py', 'severity': 'high'}]
        if change == 'warnings': value['warnings'] = ['skipped source']
        if change == 'tool_failure': value['tool_calls']['failure'] = 1
        Path(argv[argv.index('--output') + 1]).write_text(json.dumps(value))
    monkeypatch.setattr(driver, '_run', run)
    with pytest.raises(ValueError): driver._review(source, paths, work, output, config, 'candidate_review',
        SimpleNamespace(ocr=str(binary), background_file=str(background)), operator._runner(), time.monotonic() + 10)
    assert len(calls) == 1 and not any(output.iterdir())


@pytest.fixture
def operator(monkeypatch, tmp_path):
    monkeypatch.setenv('HERMES_HOME', str(tmp_path))
    path = ROOT / 'repair_operator.py'
    assert path.is_file(), 'Repair operator is not implemented'
    spec = importlib.util.spec_from_file_location('beacon_repair_operator_test', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class FakeSocket:
    def __init__(self): self.timeouts = []
    def settimeout(self, timeout): self.timeouts.append(timeout)


class FakeResponse:
    status = 200
    def __init__(self, raw): self.raw = raw
    def read1(self, size):
        part, self.raw = self.raw[:size], self.raw[size:]
        return part


class FakeConnection:
    def __init__(self, raw): self.sock = FakeSocket(); self.response = FakeResponse(raw); self.calls = []; self.closed = False
    def request(self, method, path, body, headers): self.calls.append((method, path, body, headers))
    def getresponse(self): return self.response
    def close(self): self.closed = True


def public_request():
    content = 'def public():\n    return 1\n'
    visible = {'component':'plugin', 'operation':'lookup', 'error_code':'timeout', 'provider':'grok',
               'app_version_code':141, 'plugin_version':'1.1.32', 'stack_fingerprint':None}
    return {'provider':'grok', 'model_id':'grok-4.7', 'deadline_monotonic':time.monotonic()+10,
            'input':{'incident':visible,'files':[{'path':'src/public.py','content':content,
                                                  'sha256':hashlib.sha256(content.encode()).hexdigest()}]}}


def proposal():
    return {'edits':[{'path':'src/public.py','before_sha256':'a'*64,'content':'fixed\n'},
                     {'path':'tests/test_public.py','before_sha256':None,'content':'def test_public(): pass\n'}]}


def response_for(value):
    return json.dumps({'choices':[{'finish_reason':'stop','message':{'content':json.dumps(value)}}]}).encode()


def test_grok_transport_sends_only_public_input_to_fixed_endpoint(operator):
    fake = FakeConnection(response_for(proposal())); loaded = []
    model = operator.GrokRepairModel(lambda: loaded.append(True) or 'synthetic-key', connection_factory=lambda timeout:fake)
    request = public_request(); answer = model(request)
    method, path, body, headers = fake.calls[0]; payload = json.loads(body)
    assert method == 'POST' and path == '/v1/chat/completions'
    assert payload['model'] == 'grok-4.7' and payload['stream'] is False
    assert json.loads(payload['messages'][1]['content']) == request['input']
    assert 'synthetic-key' not in body.decode() and headers['Authorization'] == 'Bearer synthetic-key'
    assert json.loads(answer) == proposal() and fake.closed and loaded == [True]


@pytest.mark.parametrize('change', [
    {'provider':'gemini'}, {'model_id':'other'}, {'owner':'private'},
    {'input':{'incident':{}, 'files':[], 'scope':'private'}}, {'deadline_monotonic':0},
])
def test_grok_rejects_nonpublic_or_unpinned_requests_before_loading_key(operator, change):
    calls = []; model = operator.GrokRepairModel(lambda: calls.append(True) or 'synthetic-key')
    request = {**public_request(), **change}
    with pytest.raises(ValueError): model(request)
    assert calls == []


@pytest.mark.parametrize('raw', [b'not-json', b'{"choices":[]}',
                                 b'{"choices":[{"finish_reason":"length","message":{"content":"{}"}}]}',
                                 json.dumps({'choices':[{'finish_reason':'stop','message':{'content':'{"edits":[],"extra":1}'}}]}).encode()])
def test_grok_rejects_invalid_or_truncated_response_without_echoing_secrets(operator, raw):
    fake = FakeConnection(raw)
    model = operator.GrokRepairModel(lambda:'synthetic-secret', connection_factory=lambda timeout:fake)
    with pytest.raises((ValueError, RuntimeError)) as error: model(public_request())
    assert str(error.value) == 'Repair model unavailable' and fake.closed and len(fake.calls) == 1


def test_grok_refuses_oversized_response_without_retry(operator):
    fake = FakeConnection(b'x' * 2049)
    model = operator.GrokRepairModel(lambda:'synthetic-secret', max_response_bytes=2048, connection_factory=lambda timeout:fake)
    with pytest.raises(RuntimeError): model(public_request())
    assert len(fake.calls) == 1 and fake.closed


def test_grok_refuses_redirect_without_retry_or_exposing_key(operator):
    fake = FakeConnection(b''); fake.response.status = 302
    model = operator.GrokRepairModel(lambda:'synthetic-secret',connection_factory=lambda timeout:fake)
    with pytest.raises(RuntimeError) as error: model(public_request())
    assert len(fake.calls) == 1 and fake.closed and 'synthetic-secret' not in str(error.value)


def test_grok_deadline_bounds_a_stalled_ram_key_loader_without_late_request(operator):
    entered, release = threading.Event(), threading.Event(); calls=[]
    def load():
        entered.set(); release.wait(3); return 'synthetic-key'
    model = operator.GrokRepairModel(load, connection_factory=lambda timeout:calls.append(True))
    request = public_request(); request['deadline_monotonic'] = time.monotonic()+.2
    start=time.monotonic()
    try:
        with pytest.raises(RuntimeError): model(request)
        assert entered.is_set() and time.monotonic()-start < 1 and not calls
    finally:
        release.set()
    time.sleep(.1)
    assert not calls


def test_grok_does_not_load_key_if_deadline_expires_during_public_validation(operator, monkeypatch):
    original=operator._public_input; loaded=[]
    def slow(value):
        time.sleep(.15)
        return original(value)
    monkeypatch.setattr(operator,'_public_input',slow)
    model=operator.GrokRepairModel(lambda:loaded.append(True) or 'synthetic-key')
    request=public_request(); request['deadline_monotonic']=time.monotonic()+.05
    with pytest.raises(RuntimeError): model(request)
    assert not loaded


def test_grok_rejects_changed_pinned_runner_before_loading_key(operator):
    loaded=[]
    model=operator.GrokRepairModel(lambda:loaded.append(True) or 'synthetic-key',runner_sha256='0'*64)
    with pytest.raises(ValueError): model(public_request())
    assert not loaded


def test_grok_rechecks_runner_pin_before_accepting_model_output(operator, monkeypatch):
    fake=FakeConnection(response_for(proposal())); digest='a'*64; calls=[]
    monkeypatch.setattr(operator,'_file_hash',lambda path: calls.append(True) or (digest if len(calls)==1 else 'b'*64))
    model=operator.GrokRepairModel(lambda:'synthetic-key',runner_sha256=digest,connection_factory=lambda timeout:fake)
    with pytest.raises(RuntimeError): model(public_request())
    assert len(fake.calls)==1 and len(calls)>=2


def git(repo, *args):
    env = {key:value for key,value in os.environ.items() if key.upper() in {'PATH','SYSTEMROOT','WINDIR','PATHEXT','COMSPEC','TEMP','TMP'}}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0')
    return subprocess.run(['git','-C',str(repo),'-c','core.fsmonitor=false',*args], env=env, check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode().strip()


@pytest.fixture
def allocation(operator, tmp_path, monkeypatch):
    repo = tmp_path / 'coordinator'; repo.mkdir()
    git(repo,'init','-q')
    (repo / 'source.txt').write_text('synthetic\n',encoding='utf-8')
    git(repo,'add','source.txt')
    git(repo,'-c','user.name=Synthetic','-c','user.email=qa' + chr(64) + 'example.test','commit','-qm','fixture')
    base = git(repo,'rev-parse','HEAD'); identity = operator._runner().repository_identity(repo)
    private = tmp_path / 'private'; private.mkdir(); bindings = private / 'bindings'; bindings.mkdir()
    worktrees = tmp_path / 'worktrees'; worktrees.mkdir()
    resources=tmp_path/'orca-resources'; resources.mkdir(); (resources/'bin').mkdir()
    orca = resources/'bin'/'orca.exe'; orca.write_bytes(b'synthetic-orca')
    (resources/'app.asar').write_bytes(b'synthetic-archive')
    cli_dir=resources/'app.asar.unpacked'/'out'/'cli'; cli_dir.mkdir(parents=True)
    (cli_dir/'worktree.js').write_text('synthetic',encoding='utf-8')
    monkeypatch.setattr(operator, 'ORCA_BINARY', orca)
    config = {'private_root':str(private),'repository':str(repo),'bindings_root':str(bindings),
              'worktrees_root':str(worktrees),'base_commit':base,'repo_identity':identity,
              'stage_timeout':30,
              'orca_binary':str(orca),'orca_sha256':hashlib.sha256(orca.read_bytes()).hexdigest(),
              'orca_source_sha256':operator.orca_source_digest(),
              'orca_repo_id':'repo-fixture','orca_parent_id':'repo-fixture::' + str(repo)}
    calls = []
    def cli(argv, *, cwd, timeout, env):
        calls.append(argv)
        if argv[1:3] == ['worktree','create']:
            branch = argv[argv.index('--branch')+1]
            worker = worktrees / 'child'
            git(repo,'worktree','add','-q','-b',branch,str(worker),base)
            return {'result':{'worktree':{'id':'repo-fixture::'+str(worker),'repoId':'repo-fixture'},
                              'lineage':{'parentWorktreeId':config['orca_parent_id']}}}
        selector = argv[argv.index('--worktree')+1]
        return {'result':{'worktree':{'id':selector.removeprefix('id:'),'repoId':'repo-fixture'}}}
    job = 'job-'+'a'*32
    return config, calls, cli, repo, worktrees, job


def test_orca_allocator_creates_verified_child_and_private_binding(operator, allocation):
    config, calls, cli, repo, worktrees, job = allocation
    allocate = operator.OrcaAllocator(config, cli_run=cli)
    request = {'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
               'deadline_monotonic':time.monotonic()+20}
    child = Path(allocate(request))
    assert child == (worktrees/'child').resolve() and child != repo.resolve()
    assert git(child,'branch','--show-current') == 'beacon-repair/'+job
    create = next(args for args in calls if args[1:3] == ['worktree','create'])
    assert create[create.index('--base-branch')+1] == config['base_commit']
    assert create[create.index('--parent-worktree')+1] == 'id:'+config['orca_parent_id']
    assert create[create.index('--setup')+1] == 'skip' and '--agent' not in create
    binding = json.loads((Path(config['bindings_root'])/(job+'.json')).read_text())
    assert binding == {'job_id':job,'workspace':str(child),'repo_identity':config['repo_identity'],'base_commit':config['base_commit']}
    with pytest.raises(ValueError): allocate(request)
    assert sum(args[1:3] == ['worktree','create'] for args in calls) == 1


def test_orca_allocator_rejects_unregistered_or_dirty_child_without_deleting(operator, allocation):
    config, calls, cli, repo, worktrees, job = allocation
    def dirty(argv, **kwargs):
        result = cli(argv, **kwargs)
        if argv[1:3] == ['worktree','create']:
            (worktrees/'child'/'untracked.txt').write_text('do not delete',encoding='utf-8')
        return result
    allocate = operator.OrcaAllocator(config, cli_run=dirty)
    request = {'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
               'deadline_monotonic':time.monotonic()+20}
    with pytest.raises(ValueError): allocate(request)
    assert (worktrees/'child'/'untracked.txt').read_text() == 'do not delete'
    assert not any('rm' in args for args in calls)


def test_orca_allocator_requires_the_requested_registered_parent(operator, allocation):
    config, calls, cli, repo, worktrees, job = allocation
    def wrong_parent(argv, **kwargs):
        result = cli(argv, **kwargs)
        if argv[1:3] == ['worktree','create']: result['result']['lineage']['parentWorktreeId'] = 'foreign'
        return result
    request = {'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
               'deadline_monotonic':time.monotonic()+20}
    with pytest.raises(ValueError): operator.OrcaAllocator(config,cli_run=wrong_parent)(request)
    assert (worktrees/'child').is_dir() and not (Path(config['bindings_root'])/(job+'.json')).exists()


def test_orca_allocator_refuses_ignored_data_without_deleting_it(operator, allocation):
    config, calls, cli, repo, worktrees, job = allocation
    (repo/'.git'/'info'/'exclude').write_text('private.bin\n',encoding='utf-8')
    def ignored(argv, **kwargs):
        result = cli(argv, **kwargs)
        if argv[1:3] == ['worktree','create']:
            (worktrees/'child'/'private.bin').write_text('synthetic data',encoding='utf-8')
        return result
    request = {'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
               'deadline_monotonic':time.monotonic()+20}
    with pytest.raises(ValueError): operator.OrcaAllocator(config,cli_run=ignored)(request)
    assert (worktrees/'child'/'private.bin').read_text() == 'synthetic data'


def test_orca_allocator_refuses_changed_cli_source_before_invocation(operator, allocation):
    config, calls, cli, repo, worktrees, job = allocation
    (Path(config['orca_binary']).parent.parent/'app.asar.unpacked'/'out'/'cli'/'worktree.js').write_text('changed')
    request={'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
             'deadline_monotonic':time.monotonic()+20}
    with pytest.raises(ValueError): operator.OrcaAllocator(config,cli_run=cli)(request)
    assert not calls


@pytest.fixture
def stage_config(operator, allocation, tmp_path):
    config, calls, cli, repo, trees, job = allocation
    allocate = operator.OrcaAllocator(config, cli_run=cli)
    workspace = Path(allocate({'job_id':job,'base_commit':config['base_commit'],'repo_identity':config['repo_identity'],
                               'deadline_monotonic':time.monotonic()+20}))
    private = Path(config['private_root']); receipts = private/'receipts'; receipts.mkdir()
    docker_dir = private/'docker-config'; docker_dir.mkdir()
    trusted = private/'trusted'; trusted.mkdir()
    docker = private/'docker.exe'; docker.write_bytes(b'synthetic-docker')
    script = trusted/'driver.py'
    script.write_text('import hashlib,json,os,pathlib,sys\n'
        'assert "BEACON_PRIVATE_FIXTURE" not in os.environ\n'
        'argv=sys.argv; p=pathlib.Path(argv[argv.index("--request")+1]); raw=p.read_bytes()\n'
        'assert hashlib.sha256(raw).hexdigest()==argv[argv.index("--request-sha256")+1]\n'
        'context=json.loads(raw)["context"]; output=pathlib.Path(context["output_dir"])\n'
        'for name in ("findings.json","positive.json","paths.json"): (output/name).write_text("{}",encoding="utf-8")\n',encoding='utf-8')
    executor = operator._executor(); sha=lambda path:hashlib.sha256(Path(path).read_bytes()).hexdigest()
    exconfig = {'schema':1,'private_root':str(private),'bindings_root':config['bindings_root'],'receipt_root':str(receipts),
        'coordinator_root':str(repo),'repo_identity':config['repo_identity'],'base_commit':config['base_commit'],
        'policy_sha256':'d'*64,'runner_sha256':sha(ROOT/'repair_runner.py'),'executor_sha256':sha(ROOT/'repair_executor.py'),
        'python_binary':sys._base_executable,'python_sha256':sha(sys._base_executable),
        'docker_binary':str(docker),'docker_sha256':sha(docker),'docker_host':'npipe:////./pipe/dockerDesktopLinuxEngine',
        'docker_config_dir':str(docker_dir),'image':'sha256:'+'e'*64,'image_env':['PATH=/usr/bin'],
        'sandbox_sha256':'f'*64,'harness_sha256':'a'*64,'memory':268435456,'nano_cpus':1000000000,'pids':64,
        'stage_timeout':30,'public_manifest':{'source.txt':sha(repo/'source.txt')},'editable_paths':[],
        'release_paths':[],'baseline_paths':['source.txt'],'test_nodes':{'reproduction':['source.txt::test_x']},
        'native_root':None,'native_commit':None,'native_manifest':{},
        'gradle_harness_sha256':None,'gradle_init_sha256':None,'gradle_cache_manifest_sha256':None,
        'build_artifacts':None,'plugin_manifest':None,'gradle_slot_root':None}
    exfile = private/'executor.json'; exfile.write_text(json.dumps(exconfig),encoding='utf-8')
    orca = operator.ORCA_BINARY
    # The fake allocator is not invoked from the trusted-stage worker; pin the installed CLI path.
    if orca == Path(config['orca_binary']):
        orca = operator._standard_orca_binary()
        operator.ORCA_BINARY = orca
    command = {'argv':[sys.executable,'-I','-B',str(script)],'files':{sys.executable:sha(sys.executable),str(script):sha(script)},
               'cwd':str(trusted),'env':{}}
    opconfig = {'schema':1,'executor_config':str(exfile),'executor_config_sha256':sha(exfile),
        'operator_sha256':sha(ROOT/'repair_operator.py'),'python_binary':sys._base_executable,'python_sha256':sha(sys._base_executable),
        'orca_binary':str(orca),'orca_sha256':sha(orca),'orca_repo_id':config['orca_repo_id'],
        'orca_source_sha256':operator.orca_source_digest(),
        'orca_parent_id':config['orca_parent_id'],'worktrees_root':str(trees),'trusted_root':str(trusted),
        'stages':{'candidate_scan':command},'model_timeout_seconds':60,'model_max_response_bytes':1048576}
    path=private/'operator.json'; path.write_text(json.dumps(opconfig),encoding='utf-8')
    output=receipts/job/'candidate_scan'; output.mkdir(parents=True)
    runner=operator._runner(); source=runner._snapshot(workspace,set(),())
    context={'job_id':job,'request_key':'b'*64,'policy_sha256':'d'*64,'source_sha256':source,
             'workspace':str(workspace),'output_dir':str(output),'base_commit':config['base_commit'],
             'release':'1.2.3','version_code':1,'deadline_monotonic':time.monotonic()+20,'probe_source':'source.txt'}
    return path, sha(path), opconfig, context, script, output


def test_fixed_stage_runs_pinned_driver_and_hashes_actual_artifacts(operator, stage_config, monkeypatch):
    path, digest, config, context, script, output = stage_config
    monkeypatch.setenv('BEACON_PRIVATE_FIXTURE','synthetic-secret')
    handler = operator.TrustedStageExecutor(path,digest)
    process = handler('candidate_scan',context)
    assert Path(process.args[0]).resolve() == Path(sys._base_executable).resolve()
    assert process.wait(timeout=15) == 0
    runner = operator._runner()
    binding = {key:context[key] for key in ('job_id','request_key','policy_sha256','source_sha256')}
    found = runner.verify_receipt(output,binding,'candidate_scan',['findings.json','positive.json','paths.json'])
    assert all(path.read_text(encoding='utf-8') == '{}' for path in found.values())


def test_fixed_stage_refuses_changed_driver_and_foreign_context(operator, stage_config):
    path, digest, config, context, script, output = stage_config
    handler = operator.TrustedStageExecutor(path,digest)
    with pytest.raises(ValueError): handler('candidate_scan',{**context,'owner_secret':'synthetic'})
    script.write_text('raise SystemExit(0)',encoding='utf-8')
    with pytest.raises(ValueError): handler('candidate_scan',context)
    assert not list(output.iterdir())


def test_stage_cannot_rebind_its_request_after_producing_artifacts(operator, stage_config):
    path, _, config, context, script, output = stage_config
    script.write_text('import json,pathlib,sys\n'
        'p=pathlib.Path(sys.argv[sys.argv.index("--request")+1]);o=pathlib.Path(json.loads(p.read_text())["context"]["output_dir"])\n'
        '[(o/n).write_text("{}") for n in ("findings.json","positive.json","paths.json")]\n'
        'p.write_text(p.read_text()+" ")\n',encoding='utf-8')
    config['stages']['candidate_scan']['files'][str(script)]=hashlib.sha256(script.read_bytes()).hexdigest()
    path.write_text(json.dumps(config),encoding='utf-8'); digest=hashlib.sha256(path.read_bytes()).hexdigest()
    handler=operator.TrustedStageExecutor(path,digest); process=handler('candidate_scan',context)
    assert process.wait(timeout=15) != 0 and not (output/'receipt.json').exists()


def test_failed_process_ownership_never_releases_start_barrier(operator, stage_config, monkeypatch):
    path, digest, config, context, script, output = stage_config
    handler=operator.TrustedStageExecutor(path,digest)
    def refuse(process): raise OSError('synthetic')
    monkeypatch.setattr(operator,'_OwnedProcess',refuse)
    with pytest.raises(ValueError): handler('candidate_scan',context)
    assert not list(output.iterdir())
    assert not (path.parent/'operator-jobs'/context['job_id']/'candidate_scan.ready.json').exists()


def test_cancel_stops_only_owned_stage_tree_and_prevents_receipt(operator, stage_config):
    import psutil
    path, _, config, context, script, output = stage_config
    script.write_text('import json,pathlib,subprocess,sys,time\n'
        'p=pathlib.Path(sys.argv[sys.argv.index("--request")+1]);o=pathlib.Path(json.loads(p.read_text())["context"]["output_dir"])\n'
        'child=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"])\n'
        '(o/"child.pid").write_text(str(child.pid))\n'
        'time.sleep(30)\n',encoding='utf-8')
    config['stages']['candidate_scan']['files'][str(script)] = hashlib.sha256(script.read_bytes()).hexdigest()
    path.write_text(json.dumps(config),encoding='utf-8')
    digest=hashlib.sha256(path.read_bytes()).hexdigest()
    handler=operator.TrustedStageExecutor(path,digest)
    unrelated=subprocess.Popen([sys._base_executable,'-c','import time; time.sleep(30)'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    router=operator._executor().StageRouter({'candidate_scan':handler})
    try:
        process=router('candidate_scan',context)
        marker=output/'child.pid'; until=time.monotonic()+8
        while not marker.exists() and time.monotonic()<until: time.sleep(.05)
        assert marker.exists()
        child=int(marker.read_text())
        path.write_text('synthetic configuration changed',encoding='utf-8')
        assert router.cancel(context['job_id'],time.monotonic()+5)['status'] == 'stopped'
        assert process.wait(timeout=5) != 0
        until=time.monotonic()+5
        while psutil.pid_exists(child) and time.monotonic()<until: time.sleep(.05)
        assert not psutil.pid_exists(child) and unrelated.poll() is None and not (output/'receipt.json').exists()
        with pytest.raises(ValueError): router('candidate_scan',context)
    finally:
        if 'process' in locals() and process.poll() is None:
            handler._active[context['job_id']]['owned'].kill(); process.wait(timeout=5)
        if unrelated.poll() is None: unrelated.kill(); unrelated.wait(timeout=5)


def test_cancellation_before_start_is_durable_and_refuses_spawn(operator, stage_config, monkeypatch):
    path, digest, config, context, script, output = stage_config
    handler=operator.TrustedStageExecutor(path,digest)
    assert handler.cancel(context['job_id'],time.monotonic()+5)['status'] == 'recorded'
    original = operator.subprocess.Popen
    def prevent(argv,*args,**kwargs):
        if Path(argv[0]).name.lower() == Path(sys._base_executable).name.lower(): pytest.fail('Cancelled work started')
        return original(argv,*args,**kwargs)
    monkeypatch.setattr(operator.subprocess,'Popen',prevent)
    with pytest.raises(ValueError): handler('candidate_scan',context)
    assert not list(output.iterdir())


def test_cancel_rejects_a_job_without_an_allocator_binding(operator, stage_config):
    path, digest, config, context, script, output = stage_config
    handler=operator.TrustedStageExecutor(path,digest); foreign='job-'+'b'*32
    with pytest.raises(RuntimeError): handler.cancel(foreign,time.monotonic()+5)
    assert not (path.parent/'operator-jobs'/foreign).exists()


def test_owned_process_is_stopped_even_if_cancel_marker_cannot_be_written(operator, stage_config, monkeypatch):
    path, _, config, context, script, output = stage_config
    script.write_text('import pathlib,sys,time\n'
        'pathlib.Path(sys.argv[sys.argv.index("--request")+1]).with_name("started").write_text("yes")\n'
        'time.sleep(30)\n',encoding='utf-8')
    config['stages']['candidate_scan']['files'][str(script)] = hashlib.sha256(script.read_bytes()).hexdigest()
    path.write_text(json.dumps(config),encoding='utf-8'); digest=hashlib.sha256(path.read_bytes()).hexdigest()
    handler=operator.TrustedStageExecutor(path,digest); process=handler('candidate_scan',context)
    marker=path.parent/'operator-jobs'/context['job_id']/'started'; deadline=time.monotonic()+8
    try:
        while not marker.exists() and time.monotonic()<deadline: time.sleep(.05)
        assert marker.exists()
        @contextmanager
        def unavailable(*args): raise OSError('synthetic'); yield
        monkeypatch.setattr(handler._executor,'_job_lock',unavailable)
        with pytest.raises(RuntimeError): handler.cancel(context['job_id'],time.monotonic()+5)
        assert process.wait(timeout=5) != 0 and not (output/'receipt.json').exists()
    finally:
        if process.poll() is None:
            handler._active[context['job_id']]['owned'].kill(); process.wait(timeout=5)


@pytest.fixture
def launch_control(operator, tmp_path, monkeypatch):
    private = tmp_path / 'controlled-launch'; private.mkdir()
    bindings = private / 'bindings'; bindings.mkdir()
    output = private / 'output'; output.mkdir()
    workspace = private / 'synthetic-workspace'; workspace.mkdir()
    job = 'job-' + 'a' * 32
    config = {'private_root': str(private), 'bindings_root': str(bindings), 'python_binary': sys._base_executable,
              'repo_identity': 'b' * 64, 'base_commit': 'c' * 40}
    path = private / 'operator.json'; path.write_text('{}'); digest = hashlib.sha256(path.read_bytes()).hexdigest()
    context = {'job_id': job, 'deadline_monotonic': time.monotonic() + 20, 'output_dir': str(output),
               'workspace': str(workspace), 'request_key': 'd' * 64, 'policy_sha256': 'e' * 64, 'source_sha256': 'f' * 64}
    (bindings / (job + '.json')).write_text(json.dumps({'job_id': job, 'workspace': str(workspace),
        'repo_identity': config['repo_identity'], 'base_commit': config['base_commit']}))
    monkeypatch.setattr(operator, 'load_config', lambda *_: config)
    monkeypatch.setattr(operator, '_stage_context', lambda _cfg, _stage, value: dict(value))
    waiter = private / 'waiter.py'
    waiter.write_text('import json,os,pathlib,sys,time\n'
        'p=pathlib.Path(sys.argv[1]);v=json.loads(p.read_text());r=p.with_name("candidate_scan.ready.json")\n'
        'until=time.monotonic()+20\n'
        'while not r.exists() and time.monotonic()<until: time.sleep(.01)\n'
        'if r.exists():\n'
        ' m=json.loads(r.read_text());assert m["pid"]==os.getpid() and m["nonce"]==v["nonce"]\n'
        ' p.with_name("user-code.started").write_text("synthetic stage entered")\n'
        ' time.sleep(20)\n')
    processes = []; real_popen = subprocess.Popen
    def spawn(argv, **kwargs):
        process = real_popen([sys._base_executable, '-I', '-B', str(waiter), argv[-2]], **kwargs)
        processes.append(process)
        return process
    monkeypatch.setattr(operator, 'subprocess', SimpleNamespace(Popen=spawn, DEVNULL=subprocess.DEVNULL,
        CREATE_NO_WINDOW=getattr(subprocess, 'CREATE_NO_WINDOW', 0),
        CREATE_NEW_PROCESS_GROUP=getattr(subprocess, 'CREATE_NEW_PROCESS_GROUP', 0), TimeoutExpired=subprocess.TimeoutExpired))
    handler = operator.TrustedStageExecutor(path, digest)
    folder = private / 'operator-jobs' / job
    value = SimpleNamespace(operator=operator, handler=handler, context=context, job=job, folder=folder,
                            output=output, processes=processes)
    try: yield value
    finally:
        for process in processes:
            if process.poll() is None:
                entry = handler._active.get(job)
                if entry is not None and entry['process'] is process: entry['owned'].kill()
                else: process.kill()
                process.wait(timeout=5)


def test_expired_startup_refuses_before_spawning_a_worker(launch_control, monkeypatch):
    case = launch_control; clock = [time.monotonic()]
    monkeypatch.setattr(case.operator, 'time', SimpleNamespace(monotonic=lambda: clock[0], sleep=time.sleep))
    original = case.operator._operator_job
    def expire(*args):
        folder = original(*args); clock[0] = case.context['deadline_monotonic'] + 1
        return folder
    monkeypatch.setattr(case.operator, '_operator_job', expire)
    with pytest.raises(ValueError, match='startup deadline'): case.handler('candidate_scan', case.context)
    assert case.processes == [] and case.job not in case.handler._active
    assert not (case.folder / 'candidate_scan.ready.json').exists()


def test_deadline_expiring_during_ownership_reaps_failed_registration(launch_control, monkeypatch):
    case = launch_control; clock = [time.monotonic()]; original = case.operator._OwnedProcess
    monkeypatch.setattr(case.operator, 'time', SimpleNamespace(monotonic=lambda: clock[0], sleep=time.sleep))
    def expire(process):
        owner = original(process); clock[0] = case.context['deadline_monotonic'] + 1
        return owner
    monkeypatch.setattr(case.operator, '_OwnedProcess', expire)
    with pytest.raises(ValueError): case.handler('candidate_scan', case.context)
    assert len(case.processes) == 1 and case.processes[0].poll() is not None
    assert case.job not in case.handler._active
    assert not (case.folder / 'candidate_scan.ready.json').exists() and not (case.folder / 'user-code.started').exists()
    assert not (case.output / 'receipt.json').exists()


def test_monitor_start_failure_keeps_barrier_closed_and_reaps_registration(launch_control, monkeypatch):
    case = launch_control
    class UnavailableMonitor:
        def __init__(self, *args, **kwargs): pass
        def start(self): raise RuntimeError('synthetic monitor unavailable')
    monkeypatch.setattr(case.operator, 'threading', SimpleNamespace(Lock=threading.Lock, Thread=UnavailableMonitor))
    with pytest.raises(ValueError): case.handler('candidate_scan', case.context)
    assert len(case.processes) == 1 and case.processes[0].poll() is not None
    assert not (case.folder / 'candidate_scan.ready.json').exists() and not (case.folder / 'user-code.started').exists()
    assert case.job not in case.handler._active and not (case.output / 'receipt.json').exists()


def test_barrier_write_failure_reaps_only_its_registered_entry(launch_control, monkeypatch):
    case = launch_control; other_job = 'job-' + 'b' * 32; other = object()
    case.handler._active[other_job] = other
    original = case.operator._write_once
    def refuse(path, data):
        if path.name == 'candidate_scan.ready.json': raise OSError('synthetic barrier write failure')
        return original(path, data)
    monkeypatch.setattr(case.operator, '_write_once', refuse)
    with pytest.raises(ValueError): case.handler('candidate_scan', case.context)
    assert case.processes[0].poll() is not None and case.job not in case.handler._active
    assert case.handler._active[other_job] is other
    assert not (case.folder / 'user-code.started').exists() and not (case.output / 'receipt.json').exists()


def test_admitted_worker_deadline_stops_owned_process_without_late_receipt(launch_control, monkeypatch):
    case = launch_control; offset = [0.0]
    monkeypatch.setattr(case.operator, 'time', SimpleNamespace(monotonic=lambda: time.monotonic()+offset[0], sleep=time.sleep))
    process = case.handler('candidate_scan', case.context)
    started = case.folder / 'user-code.started'; until = time.monotonic()+5
    while not started.exists() and time.monotonic()<until: time.sleep(.01)
    assert started.exists() and process.poll() is None
    offset[0] = case.context['deadline_monotonic']-time.monotonic()+1
    assert process.wait(timeout=8) != 0
    assert (case.folder / 'cancel.request').exists() and not (case.output / 'receipt.json').exists()


def test_unconfirmed_launch_cleanup_remains_in_live_accounting(launch_control, monkeypatch):
    case = launch_control
    class UnconfirmedProcess:
        pid = 12345  # Synthetic identifier; no operating-system signal is sent.
        def poll(self): return None
        def wait(self, *, timeout): raise subprocess.TimeoutExpired('synthetic unconfirmed launch', timeout)
    process = UnconfirmedProcess()
    def unavailable(*args, **kwargs): raise OSError('synthetic cleanup unavailable')
    monkeypatch.setattr(case.operator.subprocess, 'Popen', lambda *_a, **_k: process)
    monkeypatch.setattr(case.operator, '_OwnedProcess', lambda _p: SimpleNamespace(kill=unavailable, close=lambda: None))
    monkeypatch.setattr(case.operator, 'threading', SimpleNamespace(Lock=threading.Lock, Thread=unavailable))
    with pytest.raises(ValueError): case.handler('candidate_scan', case.context)
    assert case.handler._active[case.job]['process'] is process
    assert not (case.folder / 'candidate_scan.ready.json').exists() and not (case.output / 'receipt.json').exists()


def test_stage_deadline_stops_worker_and_does_not_publish_receipt(operator, stage_config, monkeypatch):
    path, _, config, context, script, output = stage_config
    script.write_text('import pathlib,sys,time\n'
        'pathlib.Path(sys.argv[sys.argv.index("--request")+1]).with_name("deadline-driver.started").write_text("started")\n'
        'time.sleep(30)\n',encoding='utf-8')
    config['stages']['candidate_scan']['files'][str(script)] = hashlib.sha256(script.read_bytes()).hexdigest()
    path.write_text(json.dumps(config),encoding='utf-8'); digest=hashlib.sha256(path.read_bytes()).hexdigest()
    handler=operator.TrustedStageExecutor(path,digest)
    offset = [0.0]
    monkeypatch.setattr(operator, 'time', SimpleNamespace(monotonic=lambda: time.monotonic()+offset[0], sleep=time.sleep))
    context['deadline_monotonic']=time.monotonic()+20
    process=handler('candidate_scan',context)
    try:
        started = path.parent/'operator-jobs'/context['job_id']/'deadline-driver.started'
        until = context['deadline_monotonic']
        while not started.exists() and time.monotonic()<until: time.sleep(.02)
        assert started.exists() and process.poll() is None, 'The deadline control must reach the live owned driver before expiry'
        print('OPERATOR_DRIVER_STARTED_BEFORE_FORCED_EXPIRY')
        offset[0] = context['deadline_monotonic']-time.monotonic()+1
        assert process.wait(timeout=8) != 0
        assert not (output/'receipt.json').exists()
        assert (path.parent/'operator-jobs'/context['job_id']/'cancel.request').exists()
    finally:
        if process.poll() is None:
            handler._active[context['job_id']]['owned'].kill(); process.wait(timeout=5)


@pytest.mark.skipif(os.name != 'nt', reason='Windows Job Object process ownership')
def test_windows_job_closes_owned_child_when_coordinator_dies(operator, tmp_path):
    import psutil
    marker=tmp_path/'owned-child.pid'
    code='''
import importlib.util,pathlib,subprocess,sys,time
spec=importlib.util.spec_from_file_location("owned",sys.argv[1]);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
p=subprocess.Popen([sys.executable,"-c","import time; time.sleep(30)"],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
owner=m._OwnedProcess(p);pathlib.Path(sys.argv[2]).write_text(str(p.pid));time.sleep(30)
'''
    parent=subprocess.Popen([sys._base_executable,'-c',code,str(ROOT/'repair_operator.py'),str(marker)],
                            stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    child=None; child_birth=None
    try:
        until=time.monotonic()+8
        while not marker.exists() and time.monotonic()<until: time.sleep(.05)
        assert marker.exists(); child=int(marker.read_text()); child_birth=psutil.Process(child).create_time()
        parent.kill(); parent.wait(timeout=5)
        until=time.monotonic()+5
        while psutil.pid_exists(child) and time.monotonic()<until: time.sleep(.05)
        assert not psutil.pid_exists(child)
    finally:
        if parent.poll() is None: parent.kill(); parent.wait(timeout=5)
        # Only the synthetic child spawned above may be cleaned up.
        if child and child_birth is not None and psutil.pid_exists(child):
            candidate=psutil.Process(child)
            if candidate.create_time() == child_birth: candidate.kill()


def test_operator_config_rejects_unpinned_or_secret_environment(operator, stage_config):
    path, _, config, context, script, output = stage_config
    config['stages']['candidate_scan']['env']['XAI_API_KEY']='synthetic-secret'
    path.write_text(json.dumps(config),encoding='utf-8')
    with pytest.raises(ValueError): operator.load_config(path,hashlib.sha256(path.read_bytes()).hexdigest())


def test_operator_config_rejects_model_workspace_on_command_path(operator, stage_config):
    path, _, config, context, script, output = stage_config
    config['stages']['candidate_scan']['env']['PATH']=context['workspace']
    path.write_text(json.dumps(config),encoding='utf-8')
    with pytest.raises(ValueError): operator.load_config(path,hashlib.sha256(path.read_bytes()).hexdigest())


def test_factory_requires_complete_routes_and_uses_existing_stage_router(operator, stage_config):
    path, digest, config, context, script, output = stage_config
    with pytest.raises(ValueError): operator.build_bindings(path,digest,lambda:'synthetic-key')
    executor=operator._executor(); exfile=Path(config['executor_config']); native=json.loads(exfile.read_text())
    native['public_manifest']['tests/new.py']=None
    native['editable_paths']=['tests/new.py']
    native['test_nodes']={stage:['tests/new.py::test_x'] if 'regression' in stage else ['source.txt::test_x']
                          for stage in executor._PYTEST_STAGES}
    native.update(gradle_harness_sha256='1'*64,gradle_init_sha256='2'*64,gradle_cache_manifest_sha256='3'*64,
                  build_artifacts={'stable':'app/stable.apk','debug':'app/debug.apk','androidtest':'app/test.apk'},
                  plugin_manifest='source.txt',gradle_slot_root=str(path.parent/'gradle-slot'))
    (path.parent/'gradle-slot').mkdir()
    exfile.write_text(json.dumps(native),encoding='utf-8')
    config['executor_config_sha256']=hashlib.sha256(exfile.read_bytes()).hexdigest()
    trusted_stages=executor._STAGES-executor.SANDBOX_STAGES
    config['stages']={stage:config['stages']['candidate_scan'] for stage in trusted_stages}
    path.write_text(json.dumps(config),encoding='utf-8'); digest=hashlib.sha256(path.read_bytes()).hexdigest()
    allocator,model,router=operator.build_bindings(path,digest,lambda:'synthetic-key')
    assert isinstance(allocator,operator.OrcaAllocator) and isinstance(model,operator.GrokRepairModel)
    assert set(router.handlers) == executor._STAGES
    assert router.handlers['isolation'].process_owner_factory is operator._OwnedProcess
    assert callable(router.cancel)
