import importlib.machinery
import importlib.util
import json
import sys
import threading
import time
from types import ModuleType, SimpleNamespace

import pytest

from test_mobile import ROOT, harness, phone

PATH = '/api/plugins/beacon/mobile/health'


@pytest.fixture
def health(harness, monkeypatch, tmp_path):
    """The health router plugin_api mounts on the real harness app, with the real identity check and a synthetic plugin folder."""
    api, client, cfg, _, app = harness
    module = sys.modules['beacon_health_api']
    plugin = tmp_path / 'plugin'
    plugin.mkdir()
    (plugin / 'plugin.yaml').write_text('name: beacon\nversion: 1.1.23\n', encoding='utf-8')
    monkeypatch.setattr(module, 'ROOT', plugin)
    monkeypatch.setattr(module, 'RUNNING_VERSION', '1.1.23')
    monkeypatch.setattr(module, '_started_at', lambda pid: 1_700_000_000 if pid else None)
    reads = []
    monkeypatch.setattr(module, '_context_read', lambda profile: reads.append(profile) or 12)
    gateway = ModuleType('gateway')
    status = ModuleType('gateway.status')
    status.read_runtime_status = lambda: {'gateway_state': 'running', 'active_agents': 0}
    status.resolve_gateway_liveness = lambda runtime=None: SimpleNamespace(running=True, pid=4242)
    status.derive_gateway_busy = lambda *, gateway_running, gateway_state, active_agents: bool(gateway_running and active_agents)
    gateway.status = status
    monkeypatch.setitem(sys.modules, 'gateway', gateway)
    monkeypatch.setitem(sys.modules, 'gateway.status', status)
    api.mobile.record_gateway_plugin_version('1.1.23', pid=4242)
    return SimpleNamespace(api=api, client=client, cfg=cfg, module=module, plugin=plugin, reads=reads, status=status)


def read(h, query=''):
    response = h.client.get(PATH + ('?fresh=1&' + query if query else '?fresh=1'))
    assert response.status_code == 200, response.text
    return response.json()


def test_health_is_for_the_owner_only(health):
    h = health
    assert h.client.get(PATH, headers={'x-fixture-owner': ''}).status_code == 401
    assert h.client.get(PATH, headers={'x-fixture-owner': 'foreign'}).status_code == 403
    assert h.client.get(PATH, headers={'sec-fetch-site': 'cross-site'}).status_code == 403
    assert h.client.get(PATH + '?profile=not-mine').status_code == 403
    assert h.client.get(PATH).status_code == 200
    # Nothing a stranger asked for was read.
    assert len(h.reads) == 1


def test_health_reports_what_the_plugin_observes(health):
    h = health
    h.cfg['profiles'] = ['default', 'work']
    value = read(h, 'profile=work')
    assert value['plugin'] == {'running': '1.1.23', 'installed': '1.1.23', 'gateway': '1.1.23'}
    assert value['dashboard'] == {'current': True, 'started_at': 1_700_000_000}
    assert value['gateway'] == {'known': True, 'running': True, 'busy': False, 'state': 'running', 'started_at': 1_700_000_000, 'current': True}
    assert value['update']['result'] == 'applied' and isinstance(value['update']['at'], int)
    assert value['calls'] == {'ready': True, 'provider': 'gemini', 'context_ms': 12, 'reason': ''}
    assert value['push']['reason'] == 'no_sender' and value['push']['ready'] is False
    assert value['cached'] is False and abs(value['observed_at'] - time.time()) < 5
    assert h.reads == ['work']
    assert 'token' not in json.dumps(value).lower() and 'key' not in json.dumps(value['calls']).lower()


def test_health_says_which_process_runs_old_code(health):
    h = health
    (h.plugin / 'plugin.yaml').write_text('name: beacon\nversion: 1.1.24\n', encoding='utf-8')
    value = read(h)
    assert value['plugin'] == {'running': '1.1.23', 'installed': '1.1.24', 'gateway': '1.1.23'}
    assert value['dashboard']['current'] is False and value['gateway']['current'] is False
    assert value['update']['result'] == 'restart_needed'
    # The gateway restarted onto the new code; only the dashboard is old.
    h.api.mobile.record_gateway_plugin_version('1.1.24', pid=4243)
    value = read(h)
    assert value['dashboard']['current'] is False and value['gateway']['current'] is True
    assert value['update']['result'] == 'restart_needed'
    h.module.RUNNING_VERSION = '1.1.24'
    assert read(h)['update']['result'] == 'applied'
    # A gateway that never recorded a version is unknown, not stale.
    with h.api.mobile.connect() as conn:
        conn.execute("DELETE FROM meta WHERE key=?", (h.api.mobile.GATEWAY_VERSION_KEY,))
    value = read(h)
    assert value['plugin']['gateway'] == '' and value['gateway']['current'] is None


def test_health_reads_gateway_busy_and_stopped_from_hermes(health):
    h = health
    h.status.read_runtime_status = lambda: {'gateway_state': 'running', 'active_agents': 2}
    assert read(h)['gateway']['busy'] is True
    h.status.resolve_gateway_liveness = lambda runtime=None: SimpleNamespace(running=False, pid=None)
    value = read(h)['gateway']
    assert value['running'] is False and value['busy'] is False and value['started_at'] is None and value['known'] is True


def test_health_degrades_when_this_hermes_cannot_say(health, monkeypatch):
    h = health
    monkeypatch.setitem(sys.modules, 'gateway', None)
    monkeypatch.setitem(sys.modules, 'gateway.status', None)
    value = read(h)
    assert value['gateway']['known'] is False and value['gateway']['running'] is False
    # Everything else still answers.
    assert value['plugin']['installed'] == '1.1.23' and value['calls']['ready'] is True


def test_push_needs_a_sender_and_a_registered_phone(health, monkeypatch):
    h = health
    assert read(h)['push'] == {'ready': False, 'sender': False, 'targets': 0, 'reason': 'no_sender'}
    h.cfg['firebase_credentials'] = {'type': 'service_account', 'project_id': 'example', 'client_email': 'synthetic-sender', 'private_key': 'synthetic'}
    sdk = ModuleType('firebase_admin')
    sdk.__spec__ = importlib.machinery.ModuleSpec('firebase_admin', None)
    monkeypatch.setitem(sys.modules, 'firebase_admin', sdk)
    assert read(h)['push'] == {'ready': False, 'sender': True, 'targets': 0, 'reason': 'no_phone'}
    device = phone(h.client)
    saved = h.client.post('/api/plugins/beacon/mobile/notifications/targets', json={'token': 'synthetic-token-' + 'x' * 24}, headers=device)
    assert saved.status_code == 200, saved.text
    assert read(h)['push'] == {'ready': True, 'sender': True, 'targets': 1, 'reason': ''}


def test_calls_say_why_they_are_not_ready(health, monkeypatch):
    h = health
    monkeypatch.setattr(h.module, '_context_read', lambda profile: 1500)
    slow = read(h)['calls']
    assert slow['ready'] is True and slow['reason'] == 'slow' and slow['context_ms'] == 1500

    with monkeypatch.context() as scoped:
        scoped.setattr(sys.modules['beacon_admin_api'], 'update_in_progress', lambda: True)
        updating = read(h)['calls']
    assert updating['ready'] is False and updating['reason'] == 'updating' and updating['context_ms'] is None

    def hang(profile):
        time.sleep(0.4)
        return 1
    monkeypatch.setattr(h.module, '_context_read', hang)
    monkeypatch.setattr(h.module, 'CONTEXT_BUDGET_SECONDS', 0.05)
    assert read(h)['calls']['reason'] == 'timeout'
    # While one read holds the single probe slot, a repeat says so rather than starting another.
    with h.module._probe_lock:
        assert read(h)['calls'] == {'ready': False, 'provider': 'gemini', 'context_ms': None, 'reason': 'busy'}

    def broken(profile):
        raise RuntimeError('profile home missing')
    monkeypatch.setattr(h.module, '_context_read', broken)
    assert read(h)['calls'] == {'ready': False, 'provider': 'gemini', 'context_ms': None, 'reason': 'unavailable'}


def test_calls_follow_the_selected_voice_provider(health):
    h = health
    assert h.client.put('/api/plugins/beacon/mobile/voice-settings', json={'provider': 'grok'}).status_code == 200
    assert read(h)['calls']['provider'] == 'grok'


def test_an_answer_is_reused_for_a_few_seconds(health, monkeypatch):
    h = health
    clock = [1000.0]
    monkeypatch.setattr(h.module, '_monotonic', lambda: clock[0])
    first = h.client.get(PATH).json()
    clock[0] += h.module.CACHE_SECONDS - 0.5
    again = h.client.get(PATH).json()
    assert first['cached'] is False and again['cached'] is True and again['observed_at'] == first['observed_at']
    assert len(h.reads) == 1
    # Check now asks for a fresh read, and so does an answer past its time.
    assert h.client.get(PATH + '?fresh=1').json()['cached'] is False and len(h.reads) == 2
    clock[0] += h.module.CACHE_SECONDS + 0.5
    assert h.client.get(PATH).json()['cached'] is False and len(h.reads) == 3
    # Each profile has its own answer.
    h.cfg['profiles'] = ['default', 'work']
    assert h.client.get(PATH + '?profile=work').json()['cached'] is False and h.reads[-1] == 'work'


def test_health_stays_fast_on_a_large_hermes_and_discovers_nothing(health, monkeypatch):
    h = health
    h.cfg['profiles'] = [f'profile-{n}' for n in range(64)]
    now = time.time()
    with h.api.mobile.connect() as conn:
        conn.executemany('INSERT INTO beacon_devices(id,owner,provider,profile,name,created,last_seen) VALUES(?,?,?,?,?,?,?)',
                         [(f'device-{n}', 'owner' if n % 3 else 'other', 'basic', f'profile-{n % 64}', 'Phone', now, now) for n in range(4000)])
        conn.executemany('INSERT INTO targets VALUES(?,?,?,?,?,?)',
                         [(f'target-{n}', 'owner' if n % 3 else 'other', f'profile-{n % 64}', 'synthetic-token-%020d' % n, now + 3600,
                           json.dumps({'device_id': f'device-{n}'})) for n in range(4000)])

    def discovery(*args, **kwargs):
        raise AssertionError('health must not list profiles, plugins, skills or MCP servers')
    profiles = ModuleType('hermes_cli.profiles')
    profiles.list_profiles = discovery
    hermes_cli = ModuleType('hermes_cli')
    hermes_cli.__path__ = []
    hermes_cli.profiles = profiles
    monkeypatch.setitem(sys.modules, 'hermes_cli', hermes_cli)
    monkeypatch.setitem(sys.modules, 'hermes_cli.profiles', profiles)
    tools = sys.modules['beacon_gateway_tools'].Gateway
    for name in ('warm_catalogue', 'compact_catalogue'):
        monkeypatch.setattr(tools, name, discovery)

    started = time.perf_counter()
    for _ in range(5):
        value = read(h, 'profile=profile-63')
    elapsed = time.perf_counter() - started
    assert elapsed < 2.0, elapsed  # five uncached reads
    assert value['push']['targets'] >= 1 and value['calls']['ready'] is True
    assert h.reads == ['profile-63'] * 5  # one profile read per answer, never one per profile


def test_one_probe_at_a_time_across_concurrent_checks(health, monkeypatch):
    h = health
    inside, peak, lock = [0], [0], threading.Lock()

    def read_once(profile):
        with lock:
            inside[0] += 1
            peak[0] = max(peak[0], inside[0])
        time.sleep(0.4)
        with lock:
            inside[0] -= 1
        return 5
    monkeypatch.setattr(h.module, '_context_read', read_once)
    results = []
    threads = [threading.Thread(target=lambda: results.append(h.client.get(PATH + '?fresh=1').json()['calls']['reason'])) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(10)
    assert peak[0] == 1 and sorted(results).count('busy') >= 1
