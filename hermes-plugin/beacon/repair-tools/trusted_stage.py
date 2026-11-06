"""Fixed trusted stage drivers. Live effects require a separately pinned owner helper."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SCANS = frozenset({'public_privacy', 'candidate_scan', 'final_gitleaks', 'final_privacy'})
REVIEWS = frozenset({'candidate_review', 'final_review'})
OWNER = frozenset({'prepare_release', 'commit', 'final_device', 'final_live', 'final_identity',
                   'release_preflight', 'publish', 'fresh_downloads'})
RULES = {'private-address': re.compile(rb'[A-Za-z0-9_.+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+'),
         'kotlin-qualified-this': re.compile(rb'\bthis@[A-Za-z_][A-Za-z0-9_]*\.')}


def privacy_findings(raw, before=b''):
    findings = []
    for rule, pattern in RULES.items():
        excess = Counter(pattern.findall(raw)) - Counter(pattern.findall(before))
        if excess: findings.append({'RuleID': rule, 'occurrences': sum(excess.values())})
    return findings


def _json(path, value):
    with Path(path).open('xb') as stream:
        stream.write(json.dumps(value, sort_keys=True, separators=(',', ':')).encode('utf8') + b'\n')
        stream.flush(); os.fsync(stream.fileno())


def _tool(config, stage, path):
    if path is None: raise ValueError('Required owner tool is unbound')
    candidate = Path(path).resolve(strict=True)
    files = config['stages'][stage]['files']
    if not any(Path(name).resolve(strict=True) == candidate and hashlib.sha256(candidate.read_bytes()).hexdigest() == digest
               for name, digest in files.items()): raise ValueError('Unpinned trusted input')
    if candidate.is_relative_to(Path(config['repository'])) or candidate.is_relative_to(Path(config['worktrees_root'])):
        raise ValueError('Untrusted executable input')
    return candidate


def _run(argv, cwd, deadline, *, accepted=(0,)):
    remaining = deadline - time.monotonic()
    if remaining <= 0: raise TimeoutError('Stage expired')
    # The operator owns this whole descendant tree through its process barrier/Job Object.
    env = {key: value for key, value in os.environ.items() if key.upper() in {'SYSTEMROOT', 'WINDIR', 'PATH', 'TEMP', 'TMP', 'HOME'}}
    result = subprocess.run([str(x) for x in argv], cwd=cwd, env=env, stdin=subprocess.DEVNULL,
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=remaining, check=False)
    if result.returncode not in accepted: raise ValueError('Trusted tool failed')
    return result.returncode


def _paths(stage, workspace, config, policy, runner):
    if stage == 'public_privacy': return sorted(policy['public_files'])
    if stage in ('final_gitleaks', 'final_privacy'):
        paths = {p.decode('utf8') for p in runner._git(workspace, 'ls-tree', '-r', '--name-only', '-z', 'HEAD').split(b'\0') if p}
    else:
        paths = runner._changed(workspace)
        if stage == 'final_review':
            paths |= {p.decode('utf8') for p in runner._git(workspace, 'diff', '--name-only', '-z', policy['base_commit'], 'HEAD').split(b'\0') if p}
    if not paths or not paths <= set(config['public_manifest']): raise ValueError('Incomplete public coverage')
    if stage in ('candidate_scan', 'candidate_review') and not paths <= set(policy['source_paths'] + policy['test_paths']):
        raise ValueError('Candidate exceeds model scope')
    return sorted(paths)


def _privacy(source, paths, workspace, config, runner):
    findings = []
    for name in paths:
        raw = (source / name).read_bytes(); expected = config['public_manifest'][name]
        if hashlib.sha256(raw).hexdigest() == expected: continue
        before = runner._git(workspace, 'show', config['base_commit'] + ':' + name) if expected is not None else b''
        if expected is not None and hashlib.sha256(before).hexdigest() != expected:
            raise ValueError('Privacy baseline is not the pinned public source')
        findings += [{**row, 'path': name} for row in privacy_findings(raw, before)]
    positive = privacy_findings(('fixture' + chr(64) + 'example.test\nthis' + chr(64) + 'Fixture.member').encode())
    if {p['RuleID'] for p in positive} != set(RULES): raise ValueError('Privacy positive control failed')
    return findings, positive


def _gitleaks(source, work, config, stage, args, runner, deadline):
    binary = _tool(config, stage, args.gitleaks); rules = _tool(config, stage, args.gitleaks_config)
    control = work / 'positive'; control.mkdir()
    (control / 'control.txt').write_text('token = "ghp_' + hashlib.sha256(b'public-positive-control').hexdigest()[:36] + '"\n')
    reports = []
    for directory, name, code in ((control, 'gitleaks-positive.json', 1), (source, 'gitleaks.json', 0)):
        report = work / name
        actual = _run([binary, 'dir', directory, '--config', rules, '--no-banner', '--redact', '--report-format', 'json',
                       '--report-path', report, '--exit-code', '1'], work, deadline, accepted=(code,))
        value = runner._read_json(report)
        if not isinstance(value, list) or actual != code or code == 1 and (not value or any(not row.get('RuleID') for row in value)) or code == 0 and value:
            raise ValueError('Gitleaks positive or negative control failed')
        reports.append(value)
    return reports[1], [{'RuleID': 'gitleaks:' + row['RuleID']} for row in reports[0]]


def _review(source, paths, work, output, config, stage, args, runner, deadline):
    binary = _tool(config, stage, args.ocr); background = _tool(config, stage, args.background_file)
    report = work / 'ocr.json'
    _run([binary, 'scan', '--repo', source, '--path', ','.join(paths), '--provider', 'ocx',
          '--model', 'command-code/deepseek/deepseek-v4.1-flash', '--audience', 'agent', '--format', 'json',
          '--output', report, '--background', background.read_text(encoding='utf8')], work, deadline)
    runner.verify_review(report, paths, paths)
    with (output / 'ocr.json').open('xb') as stream: stream.write(report.read_bytes())
    _json(output / 'paths.json', paths)


def execute(stage, context, config, executor, runner, args):
    output, workspace = Path(context['output_dir']), Path(context['workspace'])
    if stage in OWNER:
        # Root alone supplies and authorizes owner-idle fencing, credentials and final execution.
        helper = _tool(config, stage, args.owner_helper)
        if helper.suffix.casefold() != '.py': raise ValueError('Owner helper must be a fixed Python driver')
        _run([config['python_binary'], '-I', '-B', helper, '--stage', stage,
              '--request', args.request, '--request-sha256', args.request_sha256,
              '--operator-config', args.operator_config, '--operator-config-sha256', args.operator_config_sha256],
             helper.parent, context['deadline_monotonic'])
        if set(p.name for p in output.iterdir()) != executor._ARTIFACTS[stage]: raise ValueError('Incomplete owner evidence')
        return
    if stage not in SCANS | REVIEWS: raise ValueError('Unknown trusted stage')
    policy_path = _tool(config, stage, args.policy)
    policy = runner._policy(runner._read_json(policy_path), config['policy_sha256'])
    ex = executor.load_config(config['executor_config'], config['executor_config_sha256'])
    paths = _paths(stage, workspace, ex, policy, runner)
    work = Path(config['private_root']) / 'trusted-tools' / context['job_id'] / stage
    work.mkdir(parents=True, exist_ok=False)
    source = work / 'source'
    manifest = {name: ex['public_manifest'][name] for name in paths}
    executor.export_public_source(workspace, source, manifest, editable=set(paths).intersection(ex['editable_paths'] + ex['release_paths']),
                                  deadline=context['deadline_monotonic'])
    if stage in REVIEWS:
        _review(source, paths, work, output, config, stage, args, runner, context['deadline_monotonic'])
    else:
        findings, positive = [], []
        if stage != 'final_gitleaks':
            found, control = _privacy(source, paths, workspace, ex, runner); findings += found; positive += control
        if stage in ('candidate_scan', 'final_gitleaks'):
            found, control = _gitleaks(source, work, config, stage, args, runner, context['deadline_monotonic']); findings += found; positive += control
        if findings or not positive: raise ValueError('Public source scan failed')
        for name, value in (('findings.json', findings), ('positive.json', positive), ('paths.json', paths)): _json(output / name, value)


def main():
    parser = argparse.ArgumentParser()
    for name in ('request', 'request-sha256', 'operator-config', 'operator-config-sha256'):
        parser.add_argument('--' + name, required=True)
    for name in ('policy', 'gitleaks', 'gitleaks-config', 'ocr', 'background-file', 'owner-helper'):
        parser.add_argument('--' + name)
    args = parser.parse_args()
    try:
        raw = Path(args.operator_config).read_bytes()
        if len(raw) > 16 * 1024 * 1024 or hashlib.sha256(raw).hexdigest() != args.operator_config_sha256: raise ValueError()
        known = json.loads(raw)
        path = ROOT / 'repair_operator.py'
        if hashlib.sha256(path.read_bytes()).hexdigest() != known['operator_sha256']: raise ValueError()
        spec = importlib.util.spec_from_file_location('trusted_operator', path)
        operator = importlib.util.module_from_spec(spec); spec.loader.exec_module(operator)
        config = operator.load_config(args.operator_config, args.operator_config_sha256)
        executor, runner = operator._executor(), operator._runner()
        request = executor.read_pinned_json(args.request, args.request_sha256, limit=65536)
        if not isinstance(request, dict) or set(request) != {'stage', 'context', 'nonce'}: raise ValueError()
        stage = request['stage']; context = operator._stage_context(config, stage, request['context'])
        execute(stage, context, config, executor, runner, args)
        if hashlib.sha256(Path(args.request).read_bytes()).hexdigest() != args.request_sha256: raise ValueError()
        operator.load_config(args.operator_config, args.operator_config_sha256)
        return 0
    except (OSError, ValueError, TypeError, KeyError, subprocess.SubprocessError, TimeoutError):
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
