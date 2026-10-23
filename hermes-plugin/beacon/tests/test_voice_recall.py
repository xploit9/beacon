import contextlib
import contextvars
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from test_mobile import harness, reserve


@pytest.fixture
def recall_env(harness, monkeypatch):
    api, client, _, root, _ = harness
    voice = api.voice_context
    server = sys.modules['tui_gateway'].server
    active_profile = contextvars.ContextVar('recall_profile', default=None)

    @contextlib.contextmanager
    def profile_scope(record):
        token = active_profile.set(record.get('profile_home') or str(root))
        try:
            yield
        finally:
            active_profile.reset(token)

    server._profile_home = lambda profile: None if profile == 'default' else root / profile
    server._hermes_home = root
    server._session_profile_runtime_scope = profile_scope
    server._session_auth_user_id = lambda record: record.get('auth_user_id')
    monkeypatch.setitem(sys.modules, 'agent.memory_manager', SimpleNamespace(sanitize_context=lambda text: text))
    monkeypatch.setattr(voice, 'builtin_memory', lambda *args: '')
    return voice, client, root, server, active_profile


class FakeProvider:
    name = 'mnemosyne-bridge'

    def __init__(self, text='Saved: favorite color is green'):
        self.text = text
        self.initialized = []
        self.queries = []
        self.closed = False

    def is_available(self):
        return True

    def initialize(self, session_id, **kwargs):
        self.initialized.append((session_id, kwargs))

    def prefetch(self, query, *, session_id):
        self.queries.append((query, session_id))
        return self.text

    def shutdown(self):
        self.closed = True


def configured_provider(monkeypatch, voice, provider):
    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': provider.name}})
    loads = []

    def load(name, *, register_skills):
        loads.append((name, register_skills))
        return provider

    monkeypatch.setitem(sys.modules, 'plugins.memory', SimpleNamespace(load_memory_provider=load))
    return loads


def test_configured_provider_recall_without_live_agent_is_profile_scoped_and_read_only(recall_env, monkeypatch):
    voice, _, root, server, active = recall_env
    provider = FakeProvider()
    loads = configured_provider(monkeypatch, voice, provider)
    seen = []
    original_prefetch = provider.prefetch
    provider.prefetch = lambda query, **kwargs: seen.append(active.get()) or original_prefetch(query, **kwargs)
    assert server._sessions == {}

    result = voice.recall('owner', 'basic', 'work', 'new-chat', 'favorite color')

    assert result['status'] == 'completed'
    assert result['summary'] == 'Saved: favorite color is green'
    assert result['sources'][0]['ref'] == 'native-memory:mnemosyne-bridge'
    assert loads == [('mnemosyne-bridge', False)]
    session_id, kwargs = provider.initialized[0]
    assert session_id == 'new-chat'
    assert kwargs['hermes_home'] == str(root / 'work')
    assert kwargs['agent_context'] == 'subagent'
    assert kwargs['user_id'] == 'owner'
    assert provider.queries == [('favorite color', 'new-chat')]
    assert seen == [str(root / 'work')] and active.get() is None
    assert provider.closed


def test_live_provider_is_reused_only_for_the_owned_profile_and_chat(recall_env, monkeypatch):
    voice, _, root, server, _ = recall_env
    matching = FakeProvider('Already initialized memory')
    manager = SimpleNamespace(get_provider=lambda name: matching)
    server._sessions['live'] = {'auth_user_id': 'basic:owner', 'profile_home': str(root / 'work'),
                                'session_key': 'owned-chat', 'agent': SimpleNamespace(_memory_manager=manager)}
    configured_provider(monkeypatch, voice, FakeProvider('Wrong fallback'))

    result = voice.recall('owner', 'basic', 'work', 'owned-chat', 'memory')

    assert result['summary'] == 'Already initialized memory'
    assert matching.queries == [('memory', 'owned-chat')]
    assert not matching.initialized and not matching.closed


@pytest.mark.parametrize('changed', [
    {'auth_user_id': 'basic:foreign'}, {'profile_home': 'foreign'}, {'session_key': 'another-chat'},
])
def test_unrelated_live_provider_cannot_supply_recall(recall_env, monkeypatch, changed):
    voice, _, root, server, _ = recall_env
    other = FakeProvider('Foreign memory')
    record = {'auth_user_id': 'basic:owner', 'profile_home': str(root / 'work'), 'session_key': 'owned-chat',
              'agent': SimpleNamespace(_memory_manager=SimpleNamespace(get_provider=lambda name: other))}
    record.update(changed)
    server._sessions['other'] = record
    configured_provider(monkeypatch, voice, FakeProvider('Correct profile memory'))

    result = voice.recall('owner', 'basic', 'work', 'owned-chat', 'memory')

    assert result['summary'] == 'Correct profile memory'
    assert other.queries == []


@pytest.mark.parametrize('stage', ['load', 'initialize', 'prefetch'])
def test_provider_timeout_is_a_clear_failure_and_releases_resources(recall_env, monkeypatch, stage):
    voice, _, _, _, _ = recall_env
    provider = FakeProvider()
    configured_provider(monkeypatch, voice, provider)
    release = threading.Event()
    finished = threading.Event()

    def stuck(*args, **kwargs):
        release.wait(2)
        return 'Too late'

    if stage == 'load':
        monkeypatch.setitem(sys.modules, 'plugins.memory', SimpleNamespace(
            load_memory_provider=lambda *args, **kwargs: stuck() and provider))
    else:
        setattr(provider, stage, stuck)
    provider.shutdown = lambda: finished.set()
    monkeypatch.setattr(voice, '_MEMORY_LOOKUP_TIMEOUT', 0.03, raising=False)
    started = time.monotonic()
    try:
        result = voice.recall('owner', 'basic', 'default', 'chat', 'saved memories')
        assert result['status'] == 'unavailable'
        assert result['error_code'] == 'memory_lookup_timeout'
        assert result['summary'] == 'Memory lookup failed.'
        assert result['sources'] == []
        assert time.monotonic() - started < 0.75
    finally:
        release.set()
        assert finished.wait(2)


def test_timed_out_provider_keeps_capacity_until_it_exits(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    provider = FakeProvider()
    loads = configured_provider(monkeypatch, voice, provider)
    release = threading.Event()
    finished = threading.Event()
    provider.prefetch = lambda *args, **kwargs: release.wait(2) and 'Too late'
    provider.shutdown = lambda: finished.set()
    monkeypatch.setattr(voice, '_memory_lookup_slots', threading.BoundedSemaphore(1))
    monkeypatch.setattr(voice, '_MEMORY_LOOKUP_TIMEOUT', 0.03)
    try:
        first = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
        second = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
        assert first['error_code'] == 'memory_lookup_timeout'
        assert second['status'] == 'unavailable'
        assert second['error_code'] == 'memory_lookup_busy'
        assert loads == [('mnemosyne-bridge', False)]
    finally:
        release.set()
        assert finished.wait(2)


def test_retired_provider_is_never_loaded(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    provider = FakeProvider()
    loads = configured_provider(monkeypatch, voice, provider)
    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': 'hindsight'}})
    result = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
    assert result['status'] == 'unavailable'
    assert loads == [] and provider.initialized == []


@pytest.mark.parametrize('text', ['', '   ', 'Mnemosyne retrieval failed for this turn. Disclose this and verify current source files; do not invent memory.'])
def test_external_empty_and_failed_results_are_honest(recall_env, monkeypatch, text):
    voice, _, _, _, _ = recall_env
    configured_provider(monkeypatch, voice, FakeProvider(text))
    result = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
    assert result['status'] == ('empty' if not text.strip() else 'unavailable')
    assert result['summary'] == ('No saved memories matched.' if not text.strip() else 'Memory lookup failed.')
    assert result['sources'] == []


def test_mnemosyne_envelope_without_memories_is_empty(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    provider = FakeProvider('[Mnemosyne bank=fixture; untrusted historical evidence]\nPolicy text')
    provider.recall_status = lambda: SimpleNamespace(count=0)
    configured_provider(monkeypatch, voice, provider)
    result = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
    assert result['status'] == 'empty' and result['summary'] == 'No saved memories matched.'
    assert result['sources'] == []


def test_provider_exception_does_not_disclose_private_error_or_fake_builtin_success(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    provider = FakeProvider()
    configured_provider(monkeypatch, voice, provider)

    def fail(*args, **kwargs):
        raise RuntimeError('private credential detail')

    provider.prefetch = fail
    monkeypatch.setattr(voice, 'builtin_memory', lambda *args: pytest.fail('Provider failure is not builtin evidence'))
    result = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
    assert result['status'] == 'unavailable'
    assert 'private credential' not in str(result)
    assert provider.closed


@pytest.mark.parametrize('query', ['What do you remember about me?', 'What do you know about me?', 'unmatched question'])
def test_general_or_unmatched_query_returns_full_bounded_curated_memory(recall_env, monkeypatch, query):
    voice, _, _, _, _ = recall_env
    memory = 'Name: Maya\nPrefers jasmine tea\n' + 'Curated detail\n' * 750
    monkeypatch.setattr(voice, 'builtin_memory', lambda *args: memory)
    result = voice.recall('owner', 'basic', 'default', 'chat', query)
    assert result['status'] == 'completed'
    assert result['summary'] == memory[:8000]
    assert result['sources'][0]['ref'] == 'native-memory:builtin'


def test_specific_query_uses_meaningful_matching_lines(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    monkeypatch.setattr(voice, 'builtin_memory', lambda *args: 'The owner works in robotics\nName: Maya\nFavorite tea: jasmine\nLikes tea with honey')
    result = voice.recall('owner', 'basic', 'default', 'chat', 'What is the favorite tea?')
    assert result['summary'] == 'Favorite tea: jasmine\nLikes tea with honey'


def test_empty_builtin_is_explicitly_empty(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env
    monkeypatch.setattr(voice, 'builtin_memory', lambda *args: '')
    result = voice.recall('owner', 'basic', 'default', 'chat', 'anything')
    assert result['status'] == 'empty'
    assert result['summary'] == 'No saved memories matched.'
    assert result['sources'] == []


def test_denied_conversation_never_loads_profile_or_provider(recall_env, monkeypatch):
    voice, _, _, _, _ = recall_env

    def denied(*args):
        raise LookupError('Not owned')

    monkeypatch.setattr(voice.native, 'verify', denied)
    monkeypatch.setattr(voice, 'config', lambda *args: pytest.fail('Authority must be verified first'))
    with pytest.raises(LookupError, match='Not owned'):
        voice.recall('foreign', 'basic', 'default', 'chat', 'anything')


def test_builtin_allows_an_empty_native_user_snapshot(harness, monkeypatch):
    api, _, _, root, _ = harness
    (root / 'memories').mkdir()

    class Store:
        def __init__(self, *args, **kwargs):
            pass

        def load_from_disk(self):
            pass

        def format_for_system_prompt(self, target):
            return 'Curated memory' if target == 'memory' else None

    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        MemoryStore=Store, get_builtin_memory_config=lambda cfg: {},
        get_builtin_memory_store_flags=lambda cfg: (True, True)))
    assert api.voice_context.builtin_memory(root, {}) == 'Curated memory\n\n'


@pytest.mark.parametrize('failed', [False, True])
def test_call_memory_route_preserves_empty_and_failed_evidence(recall_env, monkeypatch, failed):
    voice, client, _, _, _ = recall_env
    call_id = reserve(client)
    if failed:
        provider = FakeProvider()
        configured_provider(monkeypatch, voice, provider)

        def fail(*args, **kwargs):
            raise RuntimeError('lookup offline')

        provider.prefetch = fail
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/memory', json={'query': 'saved memories'})
    assert response.status_code == 200
    result = response.json()
    assert result['status'] == ('unavailable' if failed else 'empty')
    assert result['summary'] == ('Memory lookup failed.' if failed else 'No saved memories matched.')
    assert result['sources'] == [] and isinstance(result['observed_at'], int)
