"""Trusted, bounded orchestration for isolated Beacon repair candidates.

No operator bindings, model credentials, commands or release targets are configured here.
"""
from contextlib import contextmanager
import hashlib
import json
import re
import os
import sqlite3
import subprocess
import threading
import time
import uuid
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath


_HEX = re.compile(r'[0-9a-f]{64}\Z')
_COMMIT = re.compile(r'(?:[0-9a-f]{40}|[0-9a-f]{64})\Z')
_ID = re.compile(r'[0-9a-f]{32}\Z')
_VERSION = re.compile(r'(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\Z')
_PLUGIN_VERSION = re.compile(r'v?(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?\Z')
_SEGMENT = re.compile(r'[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z')
_COMPONENTS = frozenset({'voice', 'app', 'gateway', 'plugin', 'sync', 'tools', 'memory', 'network', 'chat', 'settings', 'updates', 'vault'})
_OPERATIONS = frozenset({'connect', 'startup', 'greeting', 'capture', 'playout', 'context', 'discover_tools', 'invoke_tool',
                         'lookup', 'sync', 'request', 'history', 'auth', 'render', 'load', 'save', 'send', 'update', 'fill', 'signup', 'payment'})
_CODES = frozenset({'timeout', 'network', 'dns', 'tls', 'invalid_response', 'provider_error', 'connection_closed',
                    'queue_overflow', 'unavailable', 'hermes_updating', 'admission_rejected', 'memory_lookup_busy',
                    'memory_lookup_timeout', 'memory_lookup_failed', 'tool_http_failure', 'unknown',
                    'outcome_unknown', 'submission_unconfirmed', 'process_crash', 'process_anr', 'process_low_memory', 'process_resource_limit'})
_INCIDENT_KEYS = frozenset({'id', 'generation', 'component', 'operation', 'error_code', 'provider', 'app_version_code',
                            'plugin_version', 'stack_fingerprint', 'call_id', 'request_id', 'fingerprint', 'occurrences',
                            'state', 'attempts', 'next_attempt', 'job_state', 'job_id', 'job_attempts', 'next_submit',
                            'release', 'created', 'updated', 'commit', 'observed_versions', 'job_input_version',
                            'published_repair_input_version'})
_SELECTOR_KEYS = frozenset({'component', 'operation', 'error_code', 'provider', 'app_version_code', 'plugin_version', 'stack_fingerprint'})
_POLICY_KEYS = frozenset({'base_commit', 'repo_identity', 'public_files', 'source_paths', 'test_paths', 'release_paths', 'reproduction_paths', 'expected_reproduction',
                          'expected_regression', 'allowed_skips', 'daily_limit', 'max_files', 'max_file_bytes', 'max_patch_bytes', 'stage_timeout',
                          'job_timeout', 'owner_name', 'owner_email', 'signer_sha256', 'sandbox_sha256', 'public_repo', 'release', 'version_code',
                          'required_build_tasks', 'build_artifacts', 'plugin_manifest', 'package_name', 'release_assets', 'model_provider', 'model_id', 'minimum_tests', 'red_failure_types',
                          'scope_sha256', 'incident_selector'})
_PROTECTED_PARTS = frozenset({'.git', '.github', '.gitlab', '.codex', '.claude', '.unlazy', '.gradle', 'gradle', 'build',
                              'dist', 'release', 'releases', 'install', 'installer', 'signing', 'credentials', 'secrets'})
_PROTECTED_NAMES = frozenset({'agents.md', 'claude.md', 'soul.md', 'dockerfile', 'makefile', 'package.json', 'package-lock.json',
                              'pnpm-lock.yaml', 'yarn.lock', 'pyproject.toml', 'setup.py', 'setup.cfg', 'requirements.txt',
                              'androidmanifest.xml', 'gradlew', 'gradlew.bat', 'grok.txt'})
_REQUIRED_BUILD_TASKS = frozenset({':app:testDebugUnitTest', ':app:assembleDebug', ':app:assembleDebugAndroidTest',
                                    ':app:assembleStable', ':app:lintDebug', ':app:lintStable'})
_BEHAVIOR_FAILURE_TYPES = frozenset({'AssertionError', 'AssertionFailedError', 'ComparisonFailure', 'IllegalArgumentException',
                                     'IllegalStateException', 'NullPointerException', 'ValueError', 'TypeError'})


def _canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True).encode('ascii')


def _hash(value):
    return hashlib.sha256(value).hexdigest()


def _path(value, *, trusted_release=False):
    if not isinstance(value, str) or not value or len(value) > 240 or '\\' in value or ':' in value:
        raise ValueError('Invalid repair policy')
    parts = value.split('/')
    if len(parts) > 12 or any(not _SEGMENT.fullmatch(part) or part in ('.', '..') for part in parts):
        raise ValueError('Invalid repair policy')
    if any(part.endswith('.') or re.fullmatch(r'(?i:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\..*)?', part) for part in parts):
        raise ValueError('Invalid repair policy')
    lowered = [part.lower() for part in parts]
    name = lowered[-1]
    if not trusted_release and (any(part in _PROTECTED_PARTS or part.startswith('.') for part in lowered) or name in _PROTECTED_NAMES or
            name.endswith(('.gradle', '.gradle.kts', '.yml', '.yaml', '.toml', '.properties', '.keystore', '.jks',
                           '.sh', '.ps1', '.cmd', '.bat', '.exe', '.dll', '.so', '.jar', '.zip', '.pyc', '.class')) or
            any(word in name for word in ('release', 'signing', 'installer', 'version'))):
        raise ValueError('Invalid repair policy')
    if PurePosixPath(value).is_absolute(): raise ValueError('Invalid repair policy')
    return value


def _scope(value):
    if not isinstance(value, tuple) or len(value) != 3: raise ValueError('Invalid repair scope')
    for item, limit in zip(value, (256, 256, 80)):
        if not isinstance(item, str) or not item.strip() or len(item) > limit or any(ord(c) < 32 or ord(c) == 127 or 0xD800 <= ord(c) <= 0xDFFF for c in item):
            raise ValueError('Invalid repair scope')
    return value


def _incident(value, key):
    if not isinstance(value, dict) or set(value) - _INCIDENT_KEYS: raise ValueError('Invalid repair incident')
    ident, generation = value.get('id'), value.get('generation')
    if not isinstance(ident, str) or not _ID.fullmatch(ident) or type(generation) is not int or not 0 <= generation <= 1_000_000:
        raise ValueError('Invalid repair incident')
    if not isinstance(key, str) or not _HEX.fullmatch(key) or key != _hash((ident + ':' + str(generation)).encode()):
        raise ValueError('Invalid repair request')
    component, operation, code = value.get('component'), value.get('operation'), value.get('error_code')
    if (not isinstance(component, str) or component not in _COMPONENTS or not isinstance(operation, str) or operation not in _OPERATIONS or
            not isinstance(code, str) or not (code in _CODES or re.fullmatch(r'http_[45][0-9]{2}', code))):
        raise ValueError('Invalid repair incident')
    provider, app, version = value.get('provider'), value.get('app_version_code'), value.get('plugin_version')
    if provider is not None and provider not in ('gemini', 'grok', 'openai', 'native'): raise ValueError('Invalid repair incident')
    if app is not None and (type(app) is not int or not 1 <= app <= 2147483647): raise ValueError('Invalid repair incident')
    if version is not None and (not isinstance(version, str) or len(version) > 64 or not _PLUGIN_VERSION.fullmatch(version)):
        raise ValueError('Invalid repair incident')
    fingerprint = value.get('stack_fingerprint')
    if fingerprint is not None and (not isinstance(fingerprint, str) or not _HEX.fullmatch(fingerprint)): raise ValueError('Invalid repair incident')
    return {'id': ident, 'generation': generation, 'component': component, 'operation': operation, 'error_code': code,
            'provider': provider, 'app_version_code': app, 'plugin_version': version, 'stack_fingerprint': fingerprint}


def _policy(value, expected):
    if not isinstance(value, dict) or set(value) != _POLICY_KEYS or not isinstance(expected, str) or not _HEX.fullmatch(expected):
        raise ValueError('Invalid repair policy')
    try: copied = json.loads(_canonical(value))
    except (TypeError, ValueError): raise ValueError('Invalid repair policy') from None
    if _hash(_canonical(copied)) != expected: raise ValueError('Invalid repair policy')
    if not isinstance(copied['base_commit'], str) or not _COMMIT.fullmatch(copied['base_commit']): raise ValueError('Invalid repair policy')
    for key in ('repo_identity', 'signer_sha256', 'sandbox_sha256', 'scope_sha256'):
        if not isinstance(copied[key], str) or not _HEX.fullmatch(copied[key]): raise ValueError('Invalid repair policy')
    selector = copied['incident_selector']
    if not isinstance(selector, dict) or set(selector) != _SELECTOR_KEYS: raise ValueError('Invalid repair policy')
    sample = {'id': '0' * 32, 'generation': 0, **selector}
    try: normalized = _incident(sample, _hash(('0' * 32 + ':0').encode()))
    except ValueError: raise ValueError('Invalid repair policy') from None
    if {key: normalized[key] for key in _SELECTOR_KEYS} != selector: raise ValueError('Invalid repair policy')
    for key in ('source_paths', 'test_paths', 'release_paths', 'reproduction_paths'):
        items = copied[key]
        if not isinstance(items, list) or not 1 <= len(items) <= 32 or any(not isinstance(item, str) for item in items) or len(set(items)) != len(items): raise ValueError('Invalid repair policy')
        for item in items: _path(item, trusted_release=key == 'release_paths')
    all_paths = copied['source_paths'] + copied['test_paths'] + copied['release_paths'] + copied['reproduction_paths']
    if len({path.casefold() for path in all_paths}) != len(all_paths): raise ValueError('Invalid repair policy')
    public = copied['public_files']
    if not isinstance(public, dict) or not 1 <= len(public) <= 32 or not set(public) <= set(copied['source_paths'] + copied['test_paths'] + copied['reproduction_paths']):
        raise ValueError('Invalid repair policy')
    for path, digest in public.items():
        _path(path)
        if not isinstance(digest, str) or not _HEX.fullmatch(digest): raise ValueError('Invalid repair policy')
    tasks = copied['required_build_tasks']
    if (not isinstance(tasks, list) or not tasks or len(tasks) > 32 or
            any(not isinstance(task, str) or not re.fullmatch(r':[A-Za-z0-9_:.-]{1,200}', task) for task in tasks) or len(set(tasks)) != len(tasks)):
        raise ValueError('Invalid repair policy')
    if not _REQUIRED_BUILD_TASKS <= set(tasks): raise ValueError('Invalid repair policy')
    outputs = copied['build_artifacts']
    if not isinstance(outputs, dict) or set(outputs) != {'stable', 'debug', 'androidtest'}: raise ValueError('Invalid repair policy')
    for path in outputs.values(): _path(path, trusted_release=True)
    if copied['plugin_manifest'] not in copied['release_paths']: raise ValueError('Invalid repair policy')
    if not isinstance(copied['package_name'], str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+', copied['package_name']):
        raise ValueError('Invalid repair policy')
    assets = copied['release_assets']
    if (not isinstance(assets, dict) or set(assets) != {'apk', 'metadata', 'checksums'} or
            any(not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,120}', name) for name in assets.values()) or
            len(set(assets.values())) != 3): raise ValueError('Invalid repair policy')
    if (copied['model_provider'] not in ('grok', 'gemini') or not isinstance(copied['model_id'], str) or
            not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,100}', copied['model_id']) or 'astra' in copied['model_id'].lower()):
        raise ValueError('Invalid repair policy')
    minimum = copied['minimum_tests']
    if (not isinstance(minimum, dict) or set(minimum) != {'final_reproduction', 'final_regression', 'final_unit', 'final_plugin', 'final_build', 'final_device', 'final_live'} or
            any(type(n) is not int or not 1 <= n <= 100000 for n in minimum.values())): raise ValueError('Invalid repair policy')
    failures = copied['red_failure_types']
    if (not isinstance(failures, dict) or set(failures) != {'reproduction', 'regression'} or
            any(not isinstance(items, list) or not items or any(not isinstance(item, str) or item not in _BEHAVIOR_FAILURE_TYPES for item in items)
                or len(items) != len(set(items)) for items in failures.values())): raise ValueError('Invalid repair policy')
    for key in ('expected_reproduction', 'expected_regression'):
        if (not isinstance(copied[key], list) or not 1 <= len(copied[key]) <= 16 or
                any(not isinstance(item, str) or '.' not in item or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,240}', item) for item in copied[key]) or
                len(set(copied[key])) != len(copied[key])):
            raise ValueError('Invalid repair policy')
    skips = copied['allowed_skips']
    if (not isinstance(skips, list) or len(skips) > 32 or
            any(not isinstance(item, str) or not re.fullmatch(r'[A-Za-z0-9_.:/-]{1,240}', item) for item in skips) or len(set(skips)) != len(skips)):
        raise ValueError('Invalid repair policy')
    for key, low, high in (('daily_limit', 1, 20), ('max_files', 2, 32), ('max_file_bytes', 1, 262144),
                           ('max_patch_bytes', 1, 1048576), ('stage_timeout', 1, 3600), ('job_timeout', 1, 86400),
                           ('version_code', 1, 2147483647)):
        if type(copied[key]) is not int or not low <= copied[key] <= high: raise ValueError('Invalid repair policy')
    if (not isinstance(copied['release'], str) or not _VERSION.fullmatch(copied['release']) or
            not isinstance(copied['public_repo'], str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', copied['public_repo']) or
            not isinstance(copied['owner_name'], str) or not copied['owner_name'].strip() or len(copied['owner_name']) > 120 or
            not isinstance(copied['owner_email'], str) or not re.fullmatch(r'[^\s<>@]+@[^\s<>@]+', copied['owner_email'])):
        raise ValueError('Invalid repair policy')
    return copied


def _target(root, relative, *, trusted_release=False):
    _path(relative, trusted_release=trusted_release)
    root = Path(root)
    if root.is_symlink() or not root.is_dir(): raise ValueError('Invalid repair workspace')
    base = root.resolve(strict=True)
    current = base
    for index, part in enumerate(relative.split('/')):
        current = current / part
        if current.is_symlink() or getattr(current, 'is_junction', lambda: False)(): raise ValueError('Invalid repair workspace')
        if current.exists():
            stat = current.stat()
            if index == len(relative.split('/')) - 1:
                if not current.is_file() or stat.st_nlink != 1: raise ValueError('Invalid repair workspace')
            elif not current.is_dir(): raise ValueError('Invalid repair workspace')
    if not current.resolve(strict=False).is_relative_to(base): raise ValueError('Invalid repair workspace')
    return current


def model_input(root, policy, incident):
    ident, generation = incident.get('id'), incident.get('generation')
    if not isinstance(ident, str) or type(generation) is not int: raise ValueError('Invalid repair incident')
    safe = _incident(incident, _hash((ident + ':' + str(generation)).encode()))
    visible = {key: safe[key] for key in ('component', 'operation', 'error_code', 'provider', 'app_version_code', 'plugin_version', 'stack_fingerprint')}
    files = []
    total = 0
    for name, expected in sorted(policy['public_files'].items()):
        path = _target(root, name)
        if not path.is_file() or path.stat().st_size > policy['max_file_bytes']: raise ValueError('Public source unavailable')
        content = path.read_bytes()
        total += len(content)
        if total > policy['max_patch_bytes'] or _hash(content) != expected or b'\0' in content: raise ValueError('Public source unavailable')
        try: text = content.decode('utf-8')
        except UnicodeError: raise ValueError('Public source unavailable') from None
        files.append({'path': name, 'sha256': expected, 'content': text})
    return {'incident': visible, 'files': files}


def validate_proposal(raw, root, policy):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Invalid repair proposal')
            result[key] = value
        return result
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > policy['max_patch_bytes']:
        raise ValueError('Invalid repair proposal')
    try: value = json.loads(raw, object_pairs_hook=unique)
    except (ValueError, TypeError, RecursionError): raise ValueError('Invalid repair proposal') from None
    if not isinstance(value, dict) or set(value) != {'edits'} or not isinstance(value['edits'], list) or not 2 <= len(value['edits']) <= policy['max_files']:
        raise ValueError('Invalid repair proposal')
    allowed = set(policy['source_paths'] + policy['test_paths'])
    edits, seen, total = [], set(), 0
    for edit in value['edits']:
        if not isinstance(edit, dict) or set(edit) != {'path', 'before_sha256', 'content'}: raise ValueError('Invalid repair proposal')
        name = _path(edit['path'])
        if name not in allowed or name.lower() in seen or not isinstance(edit['content'], str): raise ValueError('Invalid repair proposal')
        seen.add(name.lower())
        try: content = edit['content'].encode('utf-8')
        except UnicodeError: raise ValueError('Invalid repair proposal') from None
        total += len(content)
        if not content or b'\0' in content or len(content) > policy['max_file_bytes'] or total > policy['max_patch_bytes']:
            raise ValueError('Invalid repair proposal')
        target = _target(root, name)
        before = edit['before_sha256']
        if target.exists():
            if not isinstance(before, str) or not _HEX.fullmatch(before) or target.stat().st_size > policy['max_file_bytes'] or _hash(target.read_bytes()) != before:
                raise ValueError('Invalid repair proposal')
        elif before is not None: raise ValueError('Invalid repair proposal')
        if before == _hash(content): raise ValueError('Invalid repair proposal')
        edits.append({'path': name, 'before_sha256': before, 'content': content})
    if not seen.intersection(name.lower() for name in policy['source_paths']) or not seen.intersection(name.lower() for name in policy['test_paths']):
        raise ValueError('Invalid repair proposal')
    return edits


def junit_cases(raw, *, allow_all_skipped=False):
    """Validate the report tree and independently reconcile every declared total."""
    if not isinstance(raw, bytes) or len(raw) > 4 * 1024 * 1024 or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
        raise ValueError('Invalid test evidence')
    try: root = ET.fromstring(raw)
    except ET.ParseError: raise ValueError('Invalid test evidence') from None
    cases, seen = [], set()
    def suite(node, depth=0):
        if depth > 16 or node.tag not in ('testsuite', 'testsuites'): raise ValueError('Invalid test evidence')
        totals = dict.fromkeys(('tests', 'failures', 'errors', 'skipped'), 0)
        for child in node:
            if child.tag in ('testsuite', 'testsuites'):
                nested = suite(child, depth + 1)
                for key in totals: totals[key] += nested[key]
            elif child.tag == 'testcase' and node.tag == 'testsuite':
                identity = (child.get('classname', ''), child.get('name', ''))
                if not identity[1] or identity in seen or len(cases) >= 100000: raise ValueError('Invalid test evidence')
                seen.add(identity); cases.append(child); totals['tests'] += 1
                for key, tag in (('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')):
                    count = len(child.findall(tag))
                    if count > 1: raise ValueError('Invalid test evidence')
                    totals[key] += count
                if sum(len(child.findall(tag)) for tag in ('failure', 'error', 'skipped')) > 1:
                    raise ValueError('Invalid test evidence')
                if any(n.tag not in ('failure', 'error', 'skipped', 'system-out', 'system-err', 'properties') for n in child):
                    raise ValueError('Invalid test evidence')
                if any(n.tag in ('testcase', 'testsuite', 'testsuites') for n in child.iter() if n is not child):
                    raise ValueError('Invalid test evidence')
            elif child.tag not in ('properties', 'system-out', 'system-err') or any(
                    n.tag in ('testcase', 'testsuite', 'testsuites', 'failure', 'error', 'skipped') for n in child.iter()):
                raise ValueError('Invalid test evidence')
        for key, actual in totals.items():
            declared = node.get(key)
            if declared is not None and (not re.fullmatch(r'0|[1-9][0-9]*', declared) or int(declared) != actual):
                raise ValueError('Invalid test evidence')
        return totals
    totals = suite(root)
    if not cases or not allow_all_skipped and totals['tests'] == totals['skipped']: raise ValueError('Invalid test evidence')
    return cases


def verify_junit(path, expected, *, red, exit_code, allowed_skips, failure_types=None):
    """Require real test cases and expected assertions, never an empty/compiler failure."""
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError('Invalid test evidence')
    cases = junit_cases(path.read_bytes())
    if not cases or len(cases) > 100000 or type(exit_code) is not int or exit_code != (1 if red else 0):
        raise ValueError('Invalid test evidence')
    found = set()
    for case in cases:
        name, cls = case.get('name', ''), case.get('classname', '')
        names = {name, cls + '.' + name if cls else name}
        failures, errors, skips = case.findall('failure'), case.findall('error'), case.findall('skipped')
        if not name or errors or len(failures) > 1 or len(skips) > 1 or (failures and skips): raise ValueError('Invalid test evidence')
        matches = names.intersection(expected)
        if skips:
            if not names.intersection(allowed_skips) or matches: raise ValueError('Invalid test evidence')
        elif red and matches:
            if not failures: raise ValueError('Invalid test evidence')
            failure = failures[0]
            kind = failure.get('type', '').rsplit('.', 1)[-1] or failure.get('message', '').split(':', 1)[0].strip()
            if (kind.startswith('assert ') and re.search(r'(?m)(?:^|: )AssertionError(?:\s|:|$)', failure.text or '')):
                kind = 'AssertionError'
            if kind not in (failure_types or ('AssertionError', 'AssertionFailedError', 'ComparisonFailure')):
                raise ValueError('Invalid test evidence')
            found.update(matches)
        elif failures:
            raise ValueError('Invalid test evidence')
        elif not red:
            found.update(matches)
    if found != set(expected): raise ValueError('Invalid test evidence')
    return sum(1 for case in cases if not case.findall('skipped'))


def _read_json(path, limit=16 * 1024 * 1024):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > limit or path.stat().st_nlink != 1:
        raise ValueError('Invalid repair evidence')
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result: raise ValueError('Invalid repair evidence')
            result[key] = value
        return result
    try: return json.loads(path.read_bytes(), object_pairs_hook=unique)
    except (ValueError, TypeError, RecursionError, UnicodeError): raise ValueError('Invalid repair evidence') from None


def _read_text(path, limit):
    path = Path(path)
    try:
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > limit:
            raise ValueError('Invalid repair evidence')
        return path.read_text(encoding='utf8')
    except (OSError, UnicodeError): raise ValueError('Invalid repair evidence') from None


def verify_review(path, covered, expected):
    value = _read_json(path)
    if (not isinstance(covered, list) or any(not isinstance(p, str) for p in covered) or
            len(covered) != len(set(covered)) or not expected or set(covered) != set(expected) or
            not isinstance(value, dict) or value.get('status') != 'completed' or
            value.get('llm') != {'provider': 'ocx', 'model': 'command-code/deepseek/deepseek-v4.1-flash'} or
            not isinstance(value.get('summary'), dict) or value['summary'].get('files_reviewed') != len(expected) or
            value['summary'].get('comments') != 0 or not isinstance(value.get('tool_calls'), dict) or
            value['tool_calls'].get('failure') != 0 or value.get('comments') != [] or value.get('warnings') != []):
        raise ValueError('Invalid review evidence')


def verify_receipt(directory, context, stage, expected_files):
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir(): raise ValueError('Invalid repair evidence')
    receipt = _read_json(directory / 'receipt.json', limit=65536)
    if (not isinstance(receipt, dict) or set(receipt) != set(context) | {'stage', 'artifacts'} or
            any(receipt.get(key) != value for key, value in context.items()) or receipt.get('stage') != stage or
            not isinstance(receipt.get('artifacts'), dict) or set(receipt['artifacts']) != set(expected_files)):
        raise ValueError('Invalid repair evidence')
    paths = {}
    for name, digest in receipt['artifacts'].items():
        if (not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_.-]{1,120}', name) or name in ('.', '..', 'receipt.json') or
                not isinstance(digest, str) or not _HEX.fullmatch(digest)):
            raise ValueError('Invalid repair evidence')
        path = directory / name
        maximum = (96 if stage == 'final_build' and name == 'debug.apk' else 64) * 1024 * 1024
        if path.is_symlink() or not path.is_file() or path.stat().st_nlink != 1 or path.stat().st_size > maximum:
            raise ValueError('Invalid repair evidence')
        if _hash(path.read_bytes()) != digest: raise ValueError('Invalid repair evidence')
        paths[name] = path
    return paths


def verify_isolation(proof, policy, canary_path, canary):
    profile = proof.get('applied_profile') if isinstance(proof, dict) else None
    container = isinstance(profile, dict) and _hash(_canonical(profile)) == policy['sandbox_sha256']
    if container:
        mounts = profile.get('mounts')
        expected = [
            {'target': '/workspace', 'type': 'bind', 'writable': False, 'propagation': 'rprivate', 'role': 'public_export'},
            {'target': '/scratch', 'type': 'bind', 'writable': True, 'propagation': 'rprivate', 'role': 'scratch'},
        ]
        container = (profile.get('network') == 'none' and profile.get('readonly_root') is True and
                     profile.get('user') == '65534:65534' and profile.get('cap_drop') == ['ALL'] and
                     profile.get('security_options') == ['no-new-privileges'] and isinstance(mounts, list) and
                     len(mounts) == 2 and all(item in mounts for item in expected))
    if profile is not None and not container: raise ValueError('Invalid sandbox evidence')
    private_errors = ('EACCES', 'EPERM', 'ENOENT') if container else ('EACCES', 'EPERM')
    source_errors = ('EACCES', 'EPERM', 'EROFS') if container else ('EACCES', 'EPERM')
    if (not isinstance(proof, dict) or proof.get('profile_sha256') != policy['sandbox_sha256'] or
            proof.get('read_private_errno') not in private_errors or proof.get('write_private_errno') not in private_errors or
            proof.get('write_source_errno') not in source_errors or proof.get('network_errno') not in ('EACCES', 'EPERM', 'ENETUNREACH') or
            proof.get('host_loopback_errno') not in ('EACCES', 'EPERM', 'ECONNREFUSED') or
            type(proof.get('host_loopback_positive_bytes')) is not int or proof['host_loopback_positive_bytes'] != 1 or
            type(proof.get('host_external_connect_errno')) is not int or proof['host_external_connect_errno'] != 0 or
            type(proof.get('sandbox_loopback_bytes')) is not int or proof['sandbox_loopback_bytes'] != 1 or
            type(proof.get('scratch_write_bytes')) is not int or proof['scratch_write_bytes'] != 1 or
            not isinstance(proof.get('source_path'), str) or proof['source_path'] not in policy['public_files'] or
            proof.get('source_sha256') != policy['public_files'][proof['source_path']] or
            Path(canary_path).is_symlink() or Path(canary_path).read_bytes() != canary):
        raise ValueError('Invalid sandbox evidence')


def verify_publication(release, refs, policy, commit):
    if (not isinstance(release, dict) or type(release.get('id')) is not int or release['id'] <= 0 or
            release.get('tag_name') != 'v' + policy['release'] or release.get('target_commitish') != commit or
            release.get('draft') is not False or release.get('prerelease') is not False or
            not isinstance(release.get('assets'), list) or len(release['assets']) != len(policy['release_assets']) or
            any(not isinstance(item, dict) or not isinstance(item.get('name'), str) for item in release['assets']) or
            {item['name'] for item in release['assets']} != set(policy['release_assets'].values()) or
            not isinstance(refs, dict) or refs.get('refs/heads/main') != commit or refs.get('refs/tags/v' + policy['release']) != commit):
        raise ValueError('Invalid release evidence')


def _git(root, *args):
    allowed = {'PATH', 'SYSTEMROOT', 'WINDIR', 'PATHEXT', 'COMSPEC', 'TEMP', 'TMP', 'LANG', 'LC_ALL'}
    env = {key: value for key, value in os.environ.items() if key.upper() in allowed}
    env.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0', GIT_OPTIONAL_LOCKS='0')
    try:
        result = subprocess.run(['git', '-C', str(root), '-c', 'core.fsmonitor=false', *args], env=env,
                                stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=30, check=False)
    except (OSError, subprocess.TimeoutExpired): raise ValueError('Invalid repair workspace') from None
    if result.returncode or len(result.stdout) > 16 * 1024 * 1024: raise ValueError('Invalid repair workspace')
    return result.stdout


def _common_dir(root):
    try: name = _git(root, 'rev-parse', '--git-common-dir').decode('utf8').strip()
    except UnicodeError: raise ValueError('Invalid repair workspace') from None
    path = Path(name)
    return (path if path.is_absolute() else Path(root) / path).resolve(strict=True)


def repository_identity(repository):
    return _hash(os.path.normcase(str(_common_dir(repository))).encode('utf8'))


def verify_workspace(repository, workspace, base_commit, repo_identity):
    repository, workspace = Path(repository), Path(workspace)
    if not repository.is_dir() or not workspace.is_dir() or workspace.is_symlink() or getattr(workspace, 'is_junction', lambda: False)():
        raise ValueError('Invalid repair workspace')
    owner, worker = repository.resolve(strict=True), workspace.resolve(strict=True)
    if owner == worker or owner in worker.parents or worker in owner.parents: raise ValueError('Invalid repair workspace')
    try: top = Path(_git(worker, 'rev-parse', '--show-toplevel').decode('utf8').strip()).resolve(strict=True)
    except (UnicodeError, OSError): raise ValueError('Invalid repair workspace') from None
    if (top != worker or _common_dir(owner) != _common_dir(worker) or repository_identity(owner) != repo_identity or
            _git(worker, 'rev-parse', 'HEAD').decode('ascii').strip() != base_commit or
            _git(worker, 'status', '--porcelain=v1', '-z', '--untracked-files=all')):
        raise ValueError('Invalid repair workspace')
    return worker


def verify_build_proof(files, policy, commit, tree):
    proof = _read_json(files['build-proof.json'])
    if not isinstance(proof, dict) or not isinstance(proof.get('source'), dict) or proof['source'].get('head') != commit or proof['source'].get('tree') != tree:
        raise ValueError('Invalid build evidence')
    verify_build_execution(proof.get('execution'), policy['sandbox_sha256'])
    tasks = proof.get('task_outcomes')
    if (not isinstance(tasks, list) or any(not isinstance(row, list) or len(row) != 2 or not isinstance(row[0], str) or row[1] != '' for row in tasks) or
            len({row[0] for row in tasks}) != len(tasks) or not set(policy['required_build_tasks']) <= {row[0] for row in tasks}):
        raise ValueError('Invalid build evidence')
    artifacts = proof.get('artifacts')
    if not isinstance(artifacts, dict) or proof.get('plugin_version') != policy['release']:
        raise ValueError('Invalid build evidence')
    for name, path in policy['build_artifacts'].items():
        if artifacts.get(path) != _hash(Path(files[name + '.apk']).read_bytes()): raise ValueError('Invalid build evidence')
    if proof.get('build_log_sha256') != _hash(Path(files['build.log']).read_bytes()): raise ValueError('Invalid build evidence')
    xml = proof.get('xml')
    if not isinstance(xml, dict) or not xml or len(xml) > 2000: raise ValueError('Invalid build evidence')
    totals = {'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}; cases = []
    try:
        with zipfile.ZipFile(files['junit.zip']) as archive:
            entries = archive.infolist()
            if len(entries) != len(xml) or len({entry.filename for entry in entries}) != len(entries) or sum(e.file_size for e in entries) > 64 * 1024 * 1024:
                raise ValueError('Invalid build evidence')
            for entry in entries:
                if (not re.fullmatch(r'TEST-[^/\\]{1,240}\.xml', entry.filename) or entry.file_size > 4 * 1024 * 1024 or
                        entry.filename not in xml or entry.is_dir()): raise ValueError('Invalid build evidence')
                raw = archive.read(entry)
                if _hash(raw) != xml[entry.filename] or b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper():
                    raise ValueError('Invalid build evidence')
                report_cases = junit_cases(raw, allow_all_skipped=True); cases.extend(report_cases)
                for case in report_cases:
                    name, cls = case.get('name', ''), case.get('classname', '')
                    if not name: raise ValueError('Invalid build evidence')
                    totals['tests'] += 1
                    failures, errors, skips = case.findall('failure'), case.findall('error'), case.findall('skipped')
                    if failures or errors or len(skips) > 1: raise ValueError('Invalid build evidence')
                    if skips:
                        if not {name, cls + '.' + name if cls else name}.intersection(policy['allowed_skips']): raise ValueError('Invalid build evidence')
                        totals['skipped'] += 1
    except (OSError, KeyError, ET.ParseError, zipfile.BadZipFile, RuntimeError): raise ValueError('Invalid build evidence') from None
    if totals['tests'] - totals['skipped'] < policy['minimum_tests']['final_build'] or proof.get('totals') != totals: raise ValueError('Invalid build evidence')
    tasks = verify_gradle_events(Path(files['build.log']).read_bytes(), cases)
    if tasks != proof['task_outcomes']: raise ValueError('Invalid build evidence')


def verify_build_execution(value, sandbox_sha256):
    keys = {'started_ns', 'finished_ns', 'exit_code', 'container_id', 'token', 'image', 'sandbox_sha256', 'harness_sha256',
            'init_sha256', 'cache_manifest_sha256', 'command', 'source_sha256', 'inputs'}
    if (not isinstance(value, dict) or set(value) != keys or type(value['exit_code']) is not int or value['exit_code'] != 0 or
            value['sandbox_sha256'] != sandbox_sha256 or any(not isinstance(value[key], str) or not _HEX.fullmatch(value[key])
                for key in ('container_id', 'sandbox_sha256', 'harness_sha256', 'init_sha256', 'cache_manifest_sha256', 'source_sha256')) or
            not isinstance(value['token'], str) or not _ID.fullmatch(value['token']) or not isinstance(value['image'], str) or
            not re.fullmatch(r'sha256:[0-9a-f]{64}', value['image']) or
            value['command'] != ['/usr/local/bin/python', '-I', '/opt/beacon-repair/gradle_harness.py', 'final_build'] or
            any(type(value[key]) is not int or value[key] <= 0 for key in ('started_ns', 'finished_ns')) or
            not 0 <= value['finished_ns'] - value['started_ns'] <= 3600 * 10**9):
        raise ValueError('Invalid build execution')
    inputs = value['inputs']
    if (not isinstance(inputs, dict) or not 1 <= len(inputs) <= 40000 or any(not isinstance(name, str) or len(name) > 480 or
            '\\' in name or ':' in name or any(part in ('', '.', '..') for part in name.split('/')) or
            not isinstance(digest, str) or not _HEX.fullmatch(digest) for name, digest in inputs.items()) or
            len({name.casefold() for name in inputs}) != len(inputs)):
        raise ValueError('Invalid build execution')


def verify_gradle_events(raw, cases):
    """Cross-check XML against the pinned Gradle listener and actual task output."""
    if not isinstance(raw, bytes) or len(raw) > 64 * 1024 * 1024: raise ValueError('Invalid build evidence')
    text = raw.decode('utf8', 'replace')
    if 'BUILD SUCCESSFUL' not in text or 'BUILD FAILED' in text: raise ValueError('Invalid build evidence')
    tasks = [[name, outcome] for name, outcome in re.findall(r'(?m)^> Task (:app:[A-Za-z0-9]+)(?: ([A-Z-]+))?\s*$', text)
             if name in _REQUIRED_BUILD_TASKS]
    if (len(tasks) != len(_REQUIRED_BUILD_TASKS) or {row[0] for row in tasks} != _REQUIRED_BUILD_TASKS or
            any(row[1] for row in tasks)): raise ValueError('Invalid build evidence')
    markers = re.findall(r'(?m)^BEACON_GRADLE_TESTS=(\[[^\r\n]*\])\r?$', text)
    if len(markers) != 1: raise ValueError('Invalid build evidence')
    try: events = json.loads(markers[0])
    except ValueError: raise ValueError('Invalid build evidence') from None
    if (not isinstance(events, list) or not 1 <= len(events) <= 100000 or any(not isinstance(row, list) or len(row) != 3 or
            any(not isinstance(value, str) for value in row) or not row[1] or row[2] not in ('SUCCESS', 'SKIPPED', 'FAILURE') for row in events)):
        raise ValueError('Invalid build evidence')
    actual = [[case.get('classname', ''), case.get('name', ''),
               'FAILURE' if case.findall('failure') or case.findall('error') else 'SKIPPED' if case.findall('skipped') else 'SUCCESS'] for case in cases]
    if (len({tuple(row[:2]) for row in events}) != len(events) or sorted(events) != sorted(actual)):
        raise ValueError('Invalid build evidence')
    return tasks


def _snapshot(workspace, allowed, release_paths=()):
    status = _git(workspace, 'status', '--porcelain=v1', '-z', '--untracked-files=all')
    files = []
    for item in filter(None, status.split(b'\0')):
        if len(item) < 4 or item[:2] not in (b' M', b'??') or item[2:3] != b' ':
            raise ValueError('Invalid repair workspace')
        try: name = item[3:].decode('utf8')
        except UnicodeError: raise ValueError('Invalid repair workspace') from None
        if name not in allowed: raise ValueError('Invalid repair workspace')
        path = _target(workspace, name, trusted_release=name in release_paths)
        if not path.is_file() or path.stat().st_size > 4 * 1024 * 1024: raise ValueError('Invalid repair workspace')
        files.append((name, _hash(path.read_bytes())))
    try:
        head = _git(workspace, 'rev-parse', 'HEAD').decode('ascii').strip()
        tree = _git(workspace, 'rev-parse', 'HEAD^{tree}').decode('ascii').strip()
    except UnicodeError: raise ValueError('Invalid repair workspace') from None
    return _hash(_canonical({'head': head, 'tree': tree, 'files': sorted(files)}))


def _changed(workspace):
    result = set()
    for item in filter(None, _git(workspace, 'status', '--porcelain=v1', '-z', '--untracked-files=all').split(b'\0')):
        if len(item) < 4 or item[:2] not in (b' M', b'??') or item[2:3] != b' ': raise ValueError('Invalid repair workspace')
        try: result.add(item[3:].decode('utf8'))
        except UnicodeError: raise ValueError('Invalid repair workspace') from None
    return result


def verify_commit(workspace, policy, edits, changed):
    if _git(workspace, 'status', '--porcelain=v1', '-z', '--untracked-files=all'): raise ValueError('Invalid commit evidence')
    try:
        head = _git(workspace, 'rev-parse', 'HEAD').decode('ascii').strip()
        tree = _git(workspace, 'rev-parse', 'HEAD^{tree}').decode('ascii').strip()
        fields = _git(workspace, 'show', '-s', '--format=%P%x00%an%x00%ae%x00%cn%x00%ce%x00%B', 'HEAD').decode('utf8').split('\0', 5)
        committed = {p.decode('utf8') for p in _git(workspace, 'diff-tree', '--no-commit-id', '--name-only', '-r', '-z', 'HEAD').split(b'\0') if p}
    except UnicodeError: raise ValueError('Invalid commit evidence') from None
    if (len(fields) != 6 or not _COMMIT.fullmatch(head) or not _COMMIT.fullmatch(tree) or fields[0] != policy['base_commit'] or
            fields[1] != policy['owner_name'] or fields[3] != policy['owner_name'] or
            fields[2].casefold() != policy['owner_email'].casefold() or fields[4].casefold() != policy['owner_email'].casefold() or
            re.search(r'(?im)^\s*(?:co-authored-by|generated-by|assisted-by):', fields[5]) or committed != changed):
        raise ValueError('Invalid commit evidence')
    for item in edits:
        path = _target(workspace, item['path'])
        if not path.is_file() or _hash(path.read_bytes()) != _hash(item['content']): raise ValueError('Invalid commit evidence')
    return head, tree


def verify_lint(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 16 * 1024 * 1024: raise ValueError('Invalid lint evidence')
    raw = path.read_bytes()
    if b'<!DOCTYPE' in raw.upper() or b'<!ENTITY' in raw.upper(): raise ValueError('Invalid lint evidence')
    try: root = ET.fromstring(raw)
    except ET.ParseError: raise ValueError('Invalid lint evidence') from None
    if root.tag != 'issues' or any(issue.get('severity', '').lower() in ('fatal', 'error') for issue in root.iter('issue')):
        raise ValueError('Invalid lint evidence')


def _apk_payload(path):
    try:
        with zipfile.ZipFile(path) as archive:
            entries = archive.infolist()
            if not 2 <= len(entries) <= 20000 or sum(e.file_size for e in entries) > 512 * 1024 * 1024:
                raise ValueError('Invalid APK payload')
            result = {}; names = set()
            for entry in entries:
                name = entry.filename
                if (name in names or entry.flag_bits & 1 or entry.file_size > 100 * 1024 * 1024 or
                        name.startswith('/') or '\\' in name or any(part in ('.', '..') for part in name.split('/')) or
                        (entry.external_attr >> 16) & 0o170000 == 0o120000): raise ValueError('Invalid APK payload')
                names.add(name)
                if entry.is_dir() or re.fullmatch(r'META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))', name, re.IGNORECASE): continue
                result[name] = _hash(archive.read(entry))
            if not {'AndroidManifest.xml', 'classes.dex'} <= result.keys(): raise ValueError('Invalid APK payload')
            return result
    except (OSError, zipfile.BadZipFile, RuntimeError): raise ValueError('Invalid APK payload') from None


def verify_identity(files, policy, workspace, commit, build_apk):
    meta = _read_json(files['metadata.json'], limit=65536)
    try:
        apk = Path(files['stable.apk']).read_bytes()
        aapt = _read_text(files['aapt.txt'], 1024 * 1024)
        signer = _read_text(files['signer.txt'], 1024 * 1024)
        if Path(files['plugin.yaml']).stat().st_size > 262144: raise ValueError('Invalid release identity')
        manifest = Path(files['plugin.yaml']).read_bytes()
        checked_in = _git(workspace, 'show', commit + ':' + policy['plugin_manifest'])
    except (OSError, UnicodeError): raise ValueError('Invalid release identity') from None
    lines = re.findall(r'(?im)^Signer #[0-9]+ certificate SHA-256 digest:\s*([0-9a-f:]+)\s*$', signer)
    packages = re.findall(r"(?m)^package: name='([^']+)' versionCode='([0-9]+)' versionName='([^']+)'", aapt)
    versions = re.findall(rb'(?m)^version:\s*[\"\']?([0-9]+\.[0-9]+\.[0-9]+)[\"\']?\s*$', manifest)
    expected = {'artifact_filename': policy['release_assets']['apk'], 'package_name': policy['package_name'],
                'version_code': policy['version_code'], 'version_name': policy['release'], 'plugin_version': policy['release'],
                'certificate_sha256': policy['signer_sha256'], 'sha256': _hash(apk), 'size_bytes': len(apk)}
    if (not isinstance(meta, dict) or any(meta.get(k) != v for k, v in expected.items()) or _apk_payload(files['stable.apk']) != _apk_payload(build_apk) or
            len(lines) != 1 or lines[0].replace(':', '').lower() != policy['signer_sha256'] or
            packages != [(policy['package_name'], str(policy['version_code']), policy['release'])] or
            manifest != checked_in or versions != [policy['release'].encode('ascii')]):
        raise ValueError('Invalid release identity')
    return meta


class _Unconfirmed(Exception):
    """An invoked operation may still have effects; automatic replay is forbidden."""


class _Rejected(Exception):
    """A completed, unambiguous check rejected a candidate."""


def _bounded(fn, timeout, stop=None, tracker=None):
    if timeout <= 0 or stop is not None and stop.is_set(): raise _Unconfirmed()
    done = threading.Event()
    result = []
    def run():
        try: result.append((True, fn()))
        except BaseException: result.append((False, None))
        finally:
            if tracker is not None:
                with tracker[0]: tracker[1].discard(threading.current_thread())
            done.set()
    thread = threading.Thread(target=run, daemon=True, name='beacon-repair-callback')
    if tracker is not None:
        with tracker[0]: tracker[1].add(thread)
    try: thread.start()
    except BaseException:
        if tracker is not None:
            with tracker[0]: tracker[1].discard(thread)
        raise
    deadline = time.monotonic() + timeout
    while not done.wait(min(.2, max(0, deadline - time.monotonic()))):
        if time.monotonic() >= deadline or stop is not None and stop.is_set(): raise _Unconfirmed()
    if stop is not None and stop.is_set() or not result[0][0]: raise _Unconfirmed()
    return result[0][1]


class RepairRunner:
    def __init__(self, mobile, repository, private_root, policy, policy_sha256, allocator, model, stages, idle_fence, confirm, current):
        self.policy = _policy(policy, policy_sha256)
        if not all(callable(item) for item in (allocator, model, stages, idle_fence, confirm, current)): raise ValueError('Invalid repair bindings')
        self.mobile, self.repository, self.private_root = mobile, Path(repository), Path(private_root)
        self.policy_sha256 = policy_sha256
        self.allocator, self.model, self.stages, self.idle_fence, self.confirm = allocator, model, stages, idle_fence, confirm
        self.current = current
        self._stop = None
        self._watch_lock = threading.Lock()
        self._callbacks_lock = threading.Lock()
        self._callbacks = set()

    def callbacks_running(self):
        with self._callbacks_lock: return len(self._callbacks)

    def _bounded(self, fn, timeout, stop=None):
        return _bounded(fn, timeout, stop, (self._callbacks_lock, self._callbacks))

    @contextmanager
    def _db(self):
        try:
            with self.mobile.LOCK, self.mobile.connect() as db:
                db.execute('PRAGMA synchronous=FULL')
                db.execute('''CREATE TABLE IF NOT EXISTS repair_jobs (
                    request_key TEXT PRIMARY KEY, job_id TEXT UNIQUE NOT NULL, owner TEXT NOT NULL, provider TEXT NOT NULL,
                    profile TEXT NOT NULL, incident TEXT NOT NULL, incident_hash TEXT NOT NULL, policy_hash TEXT NOT NULL,
                    state TEXT NOT NULL, phase TEXT NOT NULL, created REAL NOT NULL, started REAL, updated REAL NOT NULL,
                    release TEXT, commit_sha TEXT)''')
                yield db
        except (sqlite3.Error, OSError):
            raise RuntimeError('Repair storage unavailable') from None

    def submit(self, scope, request_key, incident):
        scope = _scope(scope)
        safe = _incident(incident, request_key)
        if not self._accepts_safe(scope, safe): raise ValueError('Repair request is outside policy')
        digest = _hash(_canonical(safe))
        now = time.time()
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            previous = db.execute('SELECT * FROM repair_jobs WHERE request_key=?', (request_key,)).fetchone()
            if previous is not None:
                if ((previous['owner'], previous['provider'], previous['profile']) != scope or previous['incident_hash'] != digest or
                        previous['policy_hash'] != self.policy_sha256): raise ValueError('Repair request already used')
                return {'status': 'queued', 'job_id': previous['job_id']}
            if db.execute('SELECT count(*) FROM repair_jobs').fetchone()[0] >= 512: raise ValueError('Repair queue is full')
            job = 'job-' + uuid.uuid4().hex
            db.execute('INSERT INTO repair_jobs VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)',
                       (request_key, job, *scope, _canonical(safe).decode('ascii'), digest, self.policy_sha256,
                        'queued', 'queued', now, None, now, None, None))
        return {'status': 'queued', 'job_id': job}

    def _accepts_safe(self, scope, incident):
        return (_hash(_canonical(list(scope))) == self.policy['scope_sha256'] and
                {key: incident[key] for key in _SELECTOR_KEYS} == self.policy['incident_selector'])

    def accepts(self, scope, request_key, incident):
        """Allow a core adapter to defer incidents outside this pinned policy."""
        return self._accepts_safe(_scope(scope), _incident(incident, request_key))

    def _stage(self, row, workspace, jobdir, name, allowed, expected_files, deadline, *, red=False, mutates=False, extra=None):
        key = row['request_key']
        if self._state(key, 'running', name, from_states=('running',)) != 1: raise _Unconfirmed()
        try: source = _snapshot(workspace, allowed, self.policy['release_paths'])
        except ValueError: raise _Rejected() from None
        output = jobdir / name
        try: output.mkdir(mode=0o700)
        except OSError: raise _Rejected() from None
        binding = {'job_id': row['job_id'], 'request_key': key, 'policy_sha256': self.policy_sha256, 'source_sha256': source}
        until = min(deadline, time.monotonic() + self.policy['stage_timeout'])
        context = {**binding, 'workspace': str(workspace), 'output_dir': str(output), 'base_commit': self.policy['base_commit'],
                   'release': self.policy['release'], 'version_code': self.policy['version_code'], 'deadline_monotonic': until,
                   'probe_source': sorted(self.policy['public_files'])[0]}
        if extra: context.update(extra)
        def launch():
            if time.monotonic() >= until or self._stop is not None and self._stop.is_set(): return None
            with self._db() as db:
                state = db.execute('SELECT state FROM repair_jobs WHERE request_key=?', (key,)).fetchone()
            if state is None or state[0] != 'running': return None
            return self.stages(name, context)
        process = None
        try:
            process = self._bounded(launch, until - time.monotonic(), self._stop)
            if not isinstance(process, subprocess.Popen) or process.stdout is not None or process.stderr is not None:
                raise _Unconfirmed()
            while True:
                if self._stop is not None and self._stop.is_set() or time.monotonic() >= until: raise _Unconfirmed()
                try:
                    exit_code = process.wait(timeout=min(.2, until - time.monotonic()))
                    break
                except subprocess.TimeoutExpired: pass
                except ValueError: raise _Unconfirmed() from None
        finally:
            if process is None or isinstance(process, subprocess.Popen) and process.poll() is None:
                cancel = getattr(self.stages, 'cancel', None)
                try:
                    if callable(cancel): self._bounded(lambda: cancel(row['job_id'], time.monotonic() + 10), 10)
                finally:
                    if isinstance(process, subprocess.Popen):
                        if process.poll() is None: process.terminate()
                        try: process.wait(timeout=5)
                        except subprocess.TimeoutExpired:
                            process.kill(); process.wait(timeout=5)
        try:
            after = _snapshot(workspace, allowed, self.policy['release_paths'])
            if not mutates and after != source: raise ValueError('Source changed during check')
            if (exit_code == 0) == red: raise ValueError('Check failed')
            artifacts = verify_receipt(output, binding, name, expected_files)
        except (OSError, ValueError): raise _Rejected() from None
        return exit_code, artifacts

    def _run_job(self, row, scope, deadline):
        """Run a job only through the separately configured trusted adapters."""
        key = row['request_key']
        allocation = self._bounded(lambda: self.allocator({'job_id': row['job_id'], 'base_commit': self.policy['base_commit'],
                                                       'repo_identity': self.policy['repo_identity'], 'deadline_monotonic': deadline}),
                              min(self.policy['stage_timeout'], deadline - time.monotonic()), self._stop)
        try:
            workspace = verify_workspace(self.repository, allocation, self.policy['base_commit'], self.policy['repo_identity'])
            root = self.private_root
            if root.is_symlink(): raise ValueError('Invalid private root')
            root.mkdir(mode=0o700, parents=True, exist_ok=True)
            root = root.resolve(strict=True)
            if root == workspace or workspace in root.parents or root in workspace.parents: raise ValueError('Invalid private root')
            jobdir = root / row['job_id']
            jobdir.mkdir(mode=0o700)
            canary = os.urandom(32)
            (jobdir / 'canary').write_bytes(canary)
        except (OSError, TypeError, ValueError): raise _Rejected() from None
        _, artifacts = self._stage(row, workspace, jobdir, 'isolation', set(), ['isolation.json'], deadline)
        try:
            proof = _read_json(artifacts['isolation.json'], limit=65536)
            verify_isolation(proof, self.policy, jobdir / 'canary', canary)
        except (OSError, ValueError): raise _Rejected() from None
        self._run_candidate(row, scope, workspace, jobdir, deadline)

    def _fenced(self, row, scope, phase, deadline, action):
        if self._state(row['request_key'], 'running', phase, from_states=('running',)) != 1: raise _Unconfirmed()
        lock = threading.Lock()
        state = {'called': False, 'closed': False}
        marker = object()
        result = []
        def once():
            with lock:
                if state['closed'] or state['called'] or time.monotonic() >= deadline or self._stop is not None and self._stop.is_set(): return None
                state['called'] = True
            with self._db() as db:
                current = db.execute('SELECT state FROM repair_jobs WHERE request_key=?', (row['request_key'],)).fetchone()
            if current is None or current[0] != 'running': return None
            result.append(action())
            return marker
        try:
            returned = self._bounded(lambda: self.idle_fence(scope, phase, once), min(self.policy['stage_timeout'], deadline - time.monotonic()), self._stop)
        finally:
            with lock: state['closed'] = True
        if returned is False and not state['called']: raise _Rejected()
        if returned is not marker or len(result) != 1: raise _Unconfirmed()
        return result[0]

    def _scan(self, row, workspace, jobdir, name, allowed, paths, deadline):
        _, artifacts = self._stage(row, workspace, jobdir, name, allowed, ['findings.json', 'positive.json', 'paths.json'], deadline)
        try:
            covered = _read_json(artifacts['paths.json'])
            positive = _read_json(artifacts['positive.json'])
            if (_read_json(artifacts['findings.json']) != [] or not isinstance(positive, list) or not positive or
                    not all(isinstance(item, dict) and item.get('RuleID') for item in positive) or
                    not isinstance(covered, list) or any(not isinstance(item, str) for item in covered) or
                    len(covered) != len(set(covered)) or set(covered) != set(paths)):
                raise ValueError('Invalid scan evidence')
        except (OSError, ValueError): raise _Rejected() from None

    def _test(self, row, workspace, jobdir, name, allowed, expected, deadline, *, red=False):
        code, artifacts = self._stage(row, workspace, jobdir, name, allowed, ['junit.xml'], deadline, red=red)
        try:
            kinds = self.policy['red_failure_types']['reproduction' if name == 'reproduction' else 'regression'] if red else None
            count = verify_junit(artifacts['junit.xml'], expected, red=red, exit_code=code, allowed_skips=self.policy['allowed_skips'], failure_types=kinds)
            if count < self.policy['minimum_tests'].get(name, 1): raise ValueError('Incomplete test evidence')
        except (OSError, ValueError): raise _Rejected() from None

    def _run_candidate(self, row, scope, workspace, jobdir, deadline):
        try:
            for path in self.policy['reproduction_paths']:
                target = _target(workspace, path)
                if not target.is_file() or target.read_bytes() != _git(workspace, 'show', self.policy['base_commit'] + ':' + path):
                    raise ValueError('Baseline reproduction changed')
        except (OSError, ValueError): raise _Rejected() from None
        self._scan(row, workspace, jobdir, 'public_privacy', set(), list(self.policy['public_files']), deadline)
        self._test(row, workspace, jobdir, 'reproduction', set(), self.policy['expected_reproduction'], deadline, red=True)
        event = json.loads(row['incident'])
        try: prompt = model_input(workspace, self.policy, event)
        except (OSError, ValueError): raise _Rejected() from None
        if self._state(row['request_key'], 'running', 'model', from_states=('running',)) != 1: raise _Unconfirmed()
        model_deadline = min(deadline, time.monotonic() + self.policy['stage_timeout'])
        raw = self._bounded(lambda: self.model({'provider': self.policy['model_provider'], 'model_id': self.policy['model_id'],
                                           'deadline_monotonic': model_deadline, 'input': prompt}),
                       model_deadline - time.monotonic(), self._stop)
        try:
            edits = validate_proposal(raw, workspace, self.policy)
            original = {item['path']: (_target(workspace, item['path']).read_bytes() if _target(workspace, item['path']).exists() else None) for item in edits}
        except (OSError, ValueError): raise _Rejected() from None
        allowed = {item['path'] for item in edits}
        source = [item for item in edits if item['path'] in self.policy['source_paths']]
        def write(items, restore=False):
            for item in items:
                path = _target(workspace, item['path'])
                data = original[item['path']] if restore else item['content']
                if restore:
                    if not path.is_file() or _hash(path.read_bytes()) != _hash(item['content']): raise ValueError('Candidate changed')
                    if data is None: path.unlink(); continue
                else:
                    current = _hash(path.read_bytes()) if path.exists() else None
                    if current != item['before_sha256']: raise ValueError('Candidate changed')
                path.parent.mkdir(parents=True, exist_ok=True)
                _target(workspace, item['path']).write_bytes(data)
            return True
        self._fenced(row, scope, 'modify', deadline, lambda: write(edits))
        _, artifacts = self._stage(row, workspace, jobdir, 'candidate_review', allowed, ['ocr.json', 'paths.json'], deadline)
        try: verify_review(artifacts['ocr.json'], _read_json(artifacts['paths.json']), sorted(allowed))
        except (OSError, ValueError): raise _Rejected() from None
        self._scan(row, workspace, jobdir, 'candidate_scan', allowed, sorted(allowed), deadline)
        self._fenced(row, scope, 'modify', deadline, lambda: write(source, restore=True))
        self._test(row, workspace, jobdir, 'regression_red', allowed, self.policy['expected_regression'], deadline, red=True)
        self._fenced(row, scope, 'modify', deadline, lambda: write(source))
        self._test(row, workspace, jobdir, 'regression_green', allowed, self.policy['expected_regression'], deadline)
        self._test(row, workspace, jobdir, 'reproduction_green', allowed, self.policy['expected_reproduction'], deadline)
        self._finish_candidate(row, scope, workspace, jobdir, deadline, edits)

    def _finish_candidate(self, row, scope, workspace, jobdir, deadline, edits):
        model_paths = {item['path'] for item in edits}
        allowed = model_paths | set(self.policy['release_paths'])
        _, preparation = self._fenced(row, scope, 'prepare_release', deadline,
            lambda: self._stage(row, workspace, jobdir, 'prepare_release', allowed, ['version.json'], deadline, mutates=True))
        try:
            version = _read_json(preparation['version.json'])
            changed = _changed(workspace)
            if (not isinstance(version, dict) or version.get('version_name') != self.policy['release'] or
                    version.get('version_code') != self.policy['version_code'] or not model_paths <= changed or
                    not changed <= allowed or self.policy['plugin_manifest'] not in changed):
                raise ValueError('Invalid version preparation')
            for item in edits:
                if _hash(_target(workspace, item['path']).read_bytes()) != _hash(item['content']): raise ValueError('Candidate changed')
        except (OSError, ValueError): raise _Rejected() from None
        self._fenced(row, scope, 'commit', deadline,
            lambda: self._stage(row, workspace, jobdir, 'commit', allowed, ['commit.json'], deadline, mutates=True))
        try:
            commit, tree = verify_commit(workspace, self.policy, edits, changed)
            tracked = [p.decode('utf8') for p in _git(workspace, 'ls-tree', '-r', '--name-only', '-z', commit).split(b'\0') if p]
            if not tracked or len(tracked) > 100000 or len(tracked) != len(set(tracked)): raise ValueError('Invalid source tree')
            with self._db() as db:
                db.execute("UPDATE repair_jobs SET release=?,commit_sha=?,updated=? WHERE request_key=? AND state='running'",
                           (self.policy['release'], commit, time.time(), row['request_key']))
        except (OSError, UnicodeError, ValueError): raise _Rejected() from None
        self._test(row, workspace, jobdir, 'final_reproduction', set(), self.policy['expected_reproduction'], deadline)
        self._test(row, workspace, jobdir, 'final_regression', set(), self.policy['expected_regression'], deadline)
        self._test(row, workspace, jobdir, 'final_unit', set(), [], deadline)
        self._test(row, workspace, jobdir, 'final_plugin', set(), [], deadline)
        _, build = self._stage(row, workspace, jobdir, 'final_build', set(),
            ['build-proof.json', 'junit.zip', 'stable.apk', 'debug.apk', 'androidtest.apk', 'build.log'], deadline)
        try: verify_build_proof(build, self.policy, commit, tree)
        except (OSError, KeyError, ValueError): raise _Rejected() from None
        _, lint = self._stage(row, workspace, jobdir, 'final_lint', set(), ['lint-debug.xml', 'lint-stable.xml'], deadline)
        try:
            verify_lint(lint['lint-debug.xml']); verify_lint(lint['lint-stable.xml'])
        except (OSError, ValueError): raise _Rejected() from None
        for name in ('final_device', 'final_live'):
            self._fenced(row, scope, name, deadline, lambda name=name: self._test(row, workspace, jobdir, name, set(), [], deadline))
        _, review = self._stage(row, workspace, jobdir, 'final_review', set(), ['ocr.json', 'paths.json'], deadline)
        try: verify_review(review['ocr.json'], _read_json(review['paths.json']), sorted(changed))
        except (OSError, ValueError): raise _Rejected() from None
        self._scan(row, workspace, jobdir, 'final_gitleaks', set(), tracked, deadline)
        self._scan(row, workspace, jobdir, 'final_privacy', set(), tracked, deadline)
        _, identity = self._stage(row, workspace, jobdir, 'final_identity', set(),
            ['metadata.json', 'stable.apk', 'aapt.txt', 'signer.txt', 'plugin.yaml'], deadline)
        try: metadata = verify_identity(identity, self.policy, workspace, commit, build['stable.apk'])
        except (OSError, ValueError): raise _Rejected() from None
        _, preflight = self._stage(row, workspace, jobdir, 'release_preflight', set(), ['preflight.json'], deadline)
        try:
            receipt = _read_json(preflight['preflight.json'])
            main = receipt.get('main_sha') if isinstance(receipt, dict) else None
            if (not isinstance(main, str) or not _COMMIT.fullmatch(main) or receipt.get('repository') != self.policy['public_repo'] or
                    receipt.get('tag') != 'v' + self.policy['release'] or receipt.get('http_status') != 404):
                raise ValueError('Invalid release preflight')
            _git(workspace, 'merge-base', '--is-ancestor', main, commit)
        except (OSError, ValueError): raise _Rejected() from None
        def publish_current():
            incident = json.loads(row['incident'])
            version = {'app_version_code': incident['app_version_code'], 'plugin_version': incident['plugin_version']}
            if self.current(scope, incident['id'], row['request_key'], version) is not True: raise _Unconfirmed()
            return self._stage(row, workspace, jobdir, 'publish', set(), ['release.json', 'refs.json'], deadline,
                               extra={'commit': commit, 'tree': tree, 'expected_public_main': main, 'public_repo': self.policy['public_repo']})
        _, publication = self._fenced(row, scope, 'publish', deadline, publish_current)
        try:
            release = _read_json(publication['release.json'], limit=4 * 1024 * 1024); refs = _read_json(publication['refs.json'], limit=65536)
            verify_publication(release, refs, self.policy, commit)
            _, downloads = self._stage(row, workspace, jobdir, 'fresh_downloads', set(),
                ['download.apk', 'download.json', 'checksums.txt', 'release.json'], deadline)
            remote = _read_json(downloads['release.json'], limit=4 * 1024 * 1024)
            downloaded_meta = _read_json(downloads['download.json'], limit=65536)
            apk = Path(downloads['download.apk']).read_bytes()
            text = _read_text(downloads['checksums.txt'], 65536)
            hashes = {}
            for line in text.splitlines():
                match = re.fullmatch(r'([0-9a-f]{64})\s+\*?([A-Za-z0-9_.-]+)', line)
                if not match or match[2] in hashes: raise ValueError('Invalid release checksums')
                hashes[match[2]] = match[1]
            verify_publication(remote, refs, self.policy, commit)
            if (remote.get('id') != release['id'] or downloaded_meta != metadata or _hash(apk) != metadata['sha256'] or len(apk) != metadata['size_bytes'] or
                    hashes.get(self.policy['release_assets']['apk']) != _hash(apk) or
                    hashes.get(self.policy['release_assets']['metadata']) != _hash(Path(downloads['download.json']).read_bytes())):
                raise ValueError('Invalid fresh downloads')
        except (OSError, UnicodeError, ValueError, _Rejected): raise _Unconfirmed() from None
        checks = {name: True for name in ('reproduction', 'regression', 'tests', 'build', 'lint', 'review', 'gitleaks', 'privacy', 'signer', 'versions', 'fresh_downloads', 'release')}
        if self._state(row['request_key'], 'running', 'confirm', from_states=('running',)) != 1: raise _Unconfirmed()
        result = self._bounded(lambda: self.confirm(scope, json.loads(row['incident'])['id'], self.policy['release'], commit, checks,
                                              expected_request_key=row['request_key']), min(self.policy['stage_timeout'], deadline - time.monotonic()), self._stop)
        if not isinstance(result, dict) or result.get('state') != 'fix_published' or result.get('release') != self.policy['release'] or result.get('commit') != commit:
            raise _Unconfirmed()
        if self._state(row['request_key'], 'confirmed', 'confirmed') != 1: raise _Unconfirmed()

    def _state(self, key, state, phase, *, from_states=('admitting', 'running')):
        with self._db() as db:
            marks = ','.join('?' for _ in from_states)
            if phase is None:
                return db.execute('UPDATE repair_jobs SET state=?,updated=? WHERE request_key=? AND state IN (' + marks + ')',
                                  (state, time.time(), key, *from_states)).rowcount
            return db.execute('UPDATE repair_jobs SET state=?,phase=?,updated=? WHERE request_key=? AND state IN (' + marks + ')',
                              (state, phase, time.time(), key, *from_states)).rowcount

    def drain(self):
        """Attempt one queued job. Uncertain operations hold the global slot."""
        now = time.time()
        admission = 'admit-' + uuid.uuid4().hex
        with self._db() as db:
            db.execute('BEGIN IMMEDIATE')
            if db.execute("SELECT 1 FROM repair_jobs WHERE state IN ('admitting','running','unconfirmed') LIMIT 1").fetchone():
                return {'status': 'busy'}
            row = db.execute("SELECT * FROM repair_jobs WHERE state='queued' AND policy_hash=? ORDER BY created,job_id LIMIT 1", (self.policy_sha256,)).fetchone()
            if row is None: return {'status': 'idle'}
            if db.execute('SELECT count(*) FROM repair_jobs WHERE started>=?', (now // 86400 * 86400,)).fetchone()[0] >= self.policy['daily_limit']:
                return {'status': 'daily_limit'}
            key = row['request_key']
            db.execute("UPDATE repair_jobs SET state='admitting',phase=?,updated=? WHERE request_key=? AND state='queued'", (admission, now, key))
        scope = (row['owner'], row['provider'], row['profile'])
        deadline = time.monotonic() + self.policy['job_timeout']
        def admit():
            with self._db() as db:
                db.execute('BEGIN IMMEDIATE')
                changed = db.execute("UPDATE repair_jobs SET state='running',phase='allocate',started=?,updated=? WHERE request_key=? AND state='admitting' AND phase=?",
                                     (time.time(), time.time(), key, admission)).rowcount
                return changed == 1
        try:
            accepted = self._bounded(lambda: self.idle_fence(scope, 'admit', admit), min(self.policy['stage_timeout'], deadline - time.monotonic()), self._stop)
            with self._db() as db:
                state = db.execute('SELECT state FROM repair_jobs WHERE request_key=?', (key,)).fetchone()[0]
            if accepted is False and state == 'admitting':
                self._state(key, 'queued', 'queued', from_states=('admitting',))
                return {'status': 'deferred'}
            if accepted is not True or state != 'running': raise _Unconfirmed()
            self._run_job(row, scope, deadline)
            return {'status': 'fixed'}
        except _Rejected:
            self._state(key, 'failed', None)
            return {'status': 'failed'}
        except BaseException as error:
            self._state(key, 'unconfirmed', None)
            if isinstance(error, (KeyboardInterrupt, SystemExit)): raise
            return {'status': 'unconfirmed'}

    def watch(self, stop, poll_seconds=5):
        """Drain until an operator-supplied event is set; never restart an uncertain job."""
        if not callable(getattr(stop, 'is_set', None)) or not callable(getattr(stop, 'wait', None)) or not 0.1 <= poll_seconds <= 60:
            raise ValueError('Invalid repair watcher')
        if not self._watch_lock.acquire(blocking=False): raise RuntimeError('Repair watcher already active')
        self._stop = stop
        try:
            while not stop.is_set():
                try: self.drain()
                except Exception: pass
                stop.wait(poll_seconds)
        finally:
            self._stop = None
            self._watch_lock.release()
