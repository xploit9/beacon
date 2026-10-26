"""Calls use Hermes' own memory and session tools, and hand a finished call to the memory provider."""
import contextlib
import json
import sys
import threading
import time
from types import SimpleNamespace

import pytest
from test_mobile import harness, reserve

SCOPE = ('owner', 'basic', 'default', 'call-1')


class Manager:
    """The part of Hermes' MemoryManager that a call touches."""

    def __init__(self, tools=()):
        self.tools = set(tools)
        self.providers = []
        self.calls = []
        self.writes = []
        self.synced = []
        self.flushed = []
        self.closed = 0
        self.initialized = None

    def add_provider(self, provider): self.providers.append(provider)
    def initialize_all(self, session_id, **kwargs): self.initialized = (session_id, kwargs)
    def has_tool(self, name): return name in self.tools

    def handle_tool_call(self, name, args):
        self.calls.append((name, args))
        return json.dumps({'ran': name})

    def notify_memory_tool_write(self, result, args, *, build_metadata):
        assert not self.closed, 'A provider that shut down takes no more writes'
        self.writes.append((result, args, build_metadata()))

    def sync_all(self, user, assistant, *, session_id, messages):
        self.synced.append((user, assistant, session_id, messages))

    def flush_pending(self, timeout=None):
        self.flushed.append(timeout)
        return True

    def shutdown_all(self): self.closed += 1


@pytest.fixture
def memory_env(harness, monkeypatch):
    api, client, _, root, _ = harness
    voice = api.voice_context
    server = sys.modules['tui_gateway'].server
    server._profile_home = lambda profile: None if profile == 'default' else root / profile
    server._hermes_home = root
    server._session_auth_user_id = lambda record: record.get('auth_user_id')
    monkeypatch.setattr(voice, 'scoped', lambda profile, fn: fn(root))
    monkeypatch.setattr(voice, 'CALL_MEMORY', {})
    monkeypatch.setattr(voice, 'PROVIDER_SCHEMAS', {})
    return api, client, voice, server, root


def open_chat(server, **agent):
    server._sessions['chat'] = {'auth_user_id': 'basic:owner', 'profile_home': None, 'session_key': 'chat',
                                'agent': SimpleNamespace(**agent)}


def provider_environment(monkeypatch, voice, available=True):
    """A configured memory provider that Hermes' loader finds, and the manager Hermes would build for it."""
    made = []
    provider = SimpleNamespace(name='mnemo', is_available=lambda: available)

    class Built(Manager):
        def __init__(self):
            super().__init__({'mnemo_search'})
            made.append(self)

    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': 'mnemo'}})
    monkeypatch.setattr(voice, 'memory_toolset_on', lambda: True)
    monkeypatch.setattr(voice.native, 'verify', lambda *args: {'source': 'android'})
    monkeypatch.setitem(sys.modules, 'agent.memory_manager', SimpleNamespace(MemoryManager=Built))
    monkeypatch.setitem(sys.modules, 'plugins.memory', SimpleNamespace(
        load_memory_provider=lambda name, *, register_skills: provider if name == 'mnemo' else None))
    return made, provider


def mirrored():
    """Waits for the background copies of memory writes to the provider."""
    for thread in threading.enumerate():
        if thread.name == 'beacon-call-memory-mirror': thread.join(2)


def say(api, call_id, rows):
    events = [{'event_id': f'e{index}', 'turn_id': str(turn), 'type': 'transcript', 'role': role, 'text': text,
               'final': final, 'at_ms': 100 * (index + 1)} for index, (turn, role, text, final) in enumerate(rows)]
    api.mobile.events(call_id, 'owner', {'events': events})


def handed_over(api, call_id):
    with api.mobile.connect() as conn:
        return conn.execute("SELECT 1 FROM timeline WHERE call_id=? AND event_id='memory:handed-over'",
                            (call_id,)).fetchone() is not None


def test_memory_tool_runs_in_hermes_handler_with_the_open_chats_store_and_mirrors_the_write(memory_env, monkeypatch):
    _, _, voice, server, root = memory_env
    resets, manager, seen = [], Manager(), []
    store = SimpleNamespace(reset_consolidation_failures=lambda: resets.append(True))
    open_chat(server, _memory_store=store, _memory_manager=manager)
    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        memory_tool=lambda **kwargs: seen.append(kwargs) or json.dumps({'success': True}),
        load_on_disk_store=lambda: pytest.fail("The open chat's own store comes first")))
    arguments = {'action': 'add', 'target': 'user', 'content': 'Likes jasmine tea'}

    result = voice.call_inline_tool('memory', 'memory', arguments, SCOPE, 'chat', root)

    assert json.loads(result) == {'success': True} and resets == [True]
    assert seen == [{'store': store, 'action': 'add', 'target': 'user', 'content': 'Likes jasmine tea',
                     'old_text': None, 'new_text': None, 'operations': None}]
    mirrored()
    written = manager.writes[0]
    assert written[1] == arguments and written[2]['write_origin'] == 'assistant_tool'
    assert written[2]['session_id'] == 'chat' and written[2]['tool_name'] == 'memory' and written[2]['task_id'] == 'call-1'
    assert manager.closed == 0


def test_without_an_open_chat_a_call_builds_the_store_and_manager_as_an_agent_build_does(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    made, provider = provider_environment(monkeypatch, voice)
    store = object()
    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        memory_tool=lambda **kwargs: json.dumps({'success': True, 'on_disk': kwargs['store'] is store}),
        load_on_disk_store=lambda: store))

    result = voice.call_inline_tool('memory', 'memory', {'action': 'add', 'target': 'memory', 'content': 'x'},
                                    SCOPE, 'chat', root)

    assert json.loads(result)['on_disk'] is True
    mirrored()
    manager = made[0]
    assert manager.providers == [provider] and len(manager.writes) == 1
    session_id, kwargs = manager.initialized
    assert session_id == 'chat'
    assert kwargs == {'platform': 'android', 'hermes_home': str(root), 'agent_context': 'primary',
                      'user_id': 'basic:owner', 'agent_identity': 'default', 'agent_workspace': 'hermes'}
    # The same manager serves the rest of the call; it is not built again.
    assert json.loads(voice.call_inline_tool('provider', 'mnemo_search', {'query': 'tea'}, SCOPE, 'chat', root)) == {'ran': 'mnemo_search'}
    assert len(made) == 1 and manager.calls == [('mnemo_search', {'query': 'tea'})]


def test_a_store_that_cannot_be_opened_is_a_clean_failure_and_never_written(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env

    def broken():
        raise OSError('private path detail')

    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        memory_tool=lambda **kwargs: pytest.fail('Nothing may be written without a store'), load_on_disk_store=broken))

    result = json.loads(voice.call_inline_tool('memory', 'memory', {'action': 'add', 'target': 'memory'}, SCOPE, 'chat', root))

    assert result == {'success': False, 'error': 'Memory is not available in this chat.'}


def test_a_provider_tool_runs_only_when_its_manager_owns_the_name(memory_env):
    _, _, voice, server, root = memory_env
    manager = Manager({'mnemo_search'})
    open_chat(server, _memory_manager=manager)

    assert json.loads(voice.call_inline_tool('provider', 'mnemo_search', {'query': 'tea'}, SCOPE, 'chat', root)) == {'ran': 'mnemo_search'}
    refused = json.loads(voice.call_inline_tool('provider', 'other_tool', {}, SCOPE, 'chat', root))

    assert refused == {'error': 'The memory provider is not available for this chat.', 'not_started': True}
    assert manager.calls == [('mnemo_search', {'query': 'tea'})]


def test_a_manager_that_is_shutting_down_is_not_borrowed(memory_env, monkeypatch):
    _, _, voice, server, root = memory_env
    closing = Manager({'mnemo_search'})
    closing._shutting_down = True
    open_chat(server, _memory_manager=closing)
    made, _ = provider_environment(monkeypatch, voice)

    voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root)

    assert closing.calls == [] and made[0].calls == [('mnemo_search', {})]


def test_a_borrowed_manager_that_stops_mid_call_is_replaced(memory_env, monkeypatch):
    _, _, voice, server, root = memory_env
    chat = Manager({'mnemo_search'})
    open_chat(server, _memory_manager=chat)
    made, _ = provider_environment(monkeypatch, voice)
    voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root)
    # The chat closed during the call, and Hermes shut its manager down.
    chat._shutting_down = True
    del server._sessions['chat']

    voice.call_inline_tool('provider', 'mnemo_search', {'query': 'tea'}, SCOPE, 'chat', root)

    assert chat.calls == [('mnemo_search', {})] and made[0].calls == [('mnemo_search', {'query': 'tea'})]


def test_a_call_that_ends_while_its_provider_starts_still_shuts_that_provider_down(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    made, _ = provider_environment(monkeypatch, voice)
    started, release = threading.Event(), threading.Event()
    build = voice.build_manager

    def slow(*args):
        started.set()
        release.wait(2)
        return build(*args)

    monkeypatch.setattr(voice, 'build_manager', slow)
    starting = threading.Thread(target=voice.call_manager, args=(root, 'owner', 'basic', 'default', 'chat', 'call-1'))
    starting.start()
    assert started.wait(2)
    ending = threading.Thread(target=voice.end_call_memory, args=('call-1',))
    ending.start()
    release.set()
    starting.join(2)
    ending.join(2)

    assert made[0].closed == 1
    assert voice.call_manager(root, 'owner', 'basic', 'default', 'chat', 'call-1') is None and len(made) == 1


def test_a_provider_tool_waits_only_briefly_while_the_provider_is_still_starting(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    monkeypatch.setattr(voice, 'PROVIDER_WAIT', 0.05)
    held = voice.call_slot('call-1')
    held.lock.acquire()
    try:
        refused = json.loads(voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root))
    finally:
        held.lock.release()

    assert refused == {'error': 'The memory provider is not available for this chat.', 'not_started': True}


@pytest.mark.parametrize('answer,copied', [({'success': True}, 1), ({'success': False, 'error': 'full'}, 0),
                                           ({'success': True, 'staged': True}, 0)])
def test_only_a_saved_memory_is_copied_to_the_provider_and_never_on_the_tool_path(memory_env, monkeypatch, answer, copied):
    _, _, voice, _, root = memory_env
    made, _ = provider_environment(monkeypatch, voice)
    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        memory_tool=lambda **kwargs: json.dumps(answer), load_on_disk_store=lambda: object()))
    held = voice.call_slot('call-1')
    # A provider that is slow to start does not hold the tool's answer.
    held.lock.acquire()
    try:
        assert json.loads(voice.call_inline_tool('memory', 'memory', {'action': 'add', 'target': 'memory', 'content': 'x'},
                                                 SCOPE, 'chat', root)) == answer
    finally:
        held.lock.release()
    mirrored()

    assert sum(len(manager.writes) for manager in made) == copied and len(made) == copied


def test_provider_tool_schemas_are_read_without_initializing_the_provider(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    provider = SimpleNamespace(name='mnemo', is_available=lambda: True)
    made, loads = [], []
    schemas = [{'name': 'mnemo_search', 'parameters': {'type': 'object'}}]

    class Reader(Manager):
        def __init__(self):
            super().__init__()
            made.append(self)

        def get_all_tool_schemas(self): return schemas

    monkeypatch.setitem(sys.modules, 'agent.memory_manager', SimpleNamespace(MemoryManager=Reader))
    monkeypatch.setitem(sys.modules, 'plugins.memory', SimpleNamespace(
        load_memory_provider=lambda name, *, register_skills: loads.append((name, register_skills)) or provider))
    monkeypatch.setitem(sys.modules, 'hermes_constants', SimpleNamespace(get_hermes_home=lambda: root))
    configured = {'memory': {'provider': 'mnemo'}}

    assert voice.provider_tool_schemas(configured) == schemas
    assert voice.provider_tool_schemas(configured) == schemas
    assert loads == [('mnemo', False)] and made[0].providers == [provider] and made[0].initialized is None
    assert voice.provider_tool_schemas({}) == [] and voice.provider_tool_schemas({'memory': {'provider': 'builtin'}}) == []
    provider.is_available = lambda: False
    voice.PROVIDER_SCHEMAS.clear()
    assert voice.provider_tool_schemas(configured) == []


class Sessions:
    rows = {'mine': {'user_id': 'basic:owner'}, 'theirs': {'user_id': 'basic:other'}}

    def get_session(self, session_id): return self.rows.get(session_id)
    def search_messages(self, query, **kwargs): return [{'session_id': 'theirs'}, {'session_id': 'mine'}]
    def resolve_session_by_title(self, title): return 'theirs' if title == 'secret' else 'mine'
    def list_recent_sessions_bounded(self, **kwargs): return [{'id': 'theirs'}, {'id': 'mine'}]
    def get_messages(self, session_id): return ['kept']
    def get_messages_around(self, session_id, around, window=5): return {'window': ['near']}
    def get_anchored_view(self, session_id, anchor, **kwargs): return {'window': ['anchored']}


def test_session_search_sees_only_the_owners_conversations_in_the_calls_profile(memory_env, monkeypatch):
    _, _, voice, server, root = memory_env
    opened = []

    @contextlib.contextmanager
    def profile_db(params, writer=False):
        opened.append(params)
        yield Sessions()

    def session_search(db, **kwargs):
        return json.dumps({'kwargs': kwargs, 'hits': [row['session_id'] for row in db.search_messages('tea')],
                           'recent': [row['id'] for row in db.list_recent_sessions_bounded(limit=5)],
                           'title': db.resolve_session_by_title('secret'), 'theirs': db.get_session('theirs'),
                           'mine': db.get_session('mine')['user_id'], 'messages': db.get_messages('mine'),
                           'their_messages': db.get_messages('theirs'), 'their_window': db.get_messages_around('theirs', 1),
                           'my_window': db.get_messages_around('mine', 1), 'my_view': db.get_anchored_view('mine', 1)})

    server._profile_db = profile_db
    monkeypatch.setitem(sys.modules, 'tools.session_search_tool', SimpleNamespace(session_search=session_search))

    result = json.loads(voice.call_inline_tool('session_search', 'session_search', {'query': 'tea', 'limit': 2},
                                               ('owner', 'basic', 'work', 'call-1'), 'chat', root))

    assert result == {'kwargs': {'query': 'tea', 'limit': 2}, 'hits': ['mine'], 'recent': ['mine'], 'title': None,
                      'theirs': None, 'mine': 'basic:owner', 'messages': ['kept'], 'their_messages': [], 'their_window': {},
                      'my_window': {'window': ['near']}, 'my_view': {'window': ['anchored']}}
    with pytest.raises(LookupError): voice.OwnedSessions(Sessions(), 'basic:owner').get_anchored_view('theirs', 1)
    assert opened == [{'profile': 'work'}]
    for arguments in ({'query': 'x', 'profile': 'default'}, {'session_id': 'default/abc'}):
        refused = json.loads(voice.call_inline_tool('session_search', 'session_search', arguments, SCOPE, 'chat', root))
        assert refused['success'] is False and 'current profile' in refused['error']
    assert opened == [{'profile': 'work'}]


def test_a_finished_call_is_handed_to_the_provider_once_like_chat_turns(memory_env, monkeypatch):
    api, _, voice, server, _ = memory_env
    manager = Manager()
    open_chat(server, _memory_manager=manager)
    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': 'mnemo'}})
    monkeypatch.setattr(voice, 'memory_toolset_on', lambda: True)
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    say(api, call_id, [(1, 'user', 'Remember I like jasmine tea', True), (1, 'assistant', 'Noted, jasmine tea.', True),
                       (2, 'user', 'What about tomorrow', True), (2, 'assistant', 'Tomorrow is', True),
                       (3, 'user', 'Never mind', True), (3, 'assistant', 'Okay', False),
                       (4, 'user', 'Thanks', True), (4, 'assistant', 'Anytime.', True)])
    api.mobile.events(call_id, 'owner', {'events': [{'event_id': 'cut2', 'turn_id': '2', 'type': 'interruption', 'at_ms': 999}]})

    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is True

    # An interrupted or unfinished reply is not kept, as Hermes keeps no interrupted turn; an unanswered line rides with the next.
    assert [entry[:3] for entry in manager.synced] == [
        ('Remember I like jasmine tea', 'Noted, jasmine tea.', 'chat'), ('Never mind Thanks', 'Anytime.', 'chat')]
    assert manager.synced[1][3] == [
        {'role': 'user', 'content': 'Remember I like jasmine tea'}, {'role': 'assistant', 'content': 'Noted, jasmine tea.'},
        {'role': 'user', 'content': 'Never mind Thanks'}, {'role': 'assistant', 'content': 'Anytime.'}]
    # Finalize and close both ask; the second finds the call already taken, and the open chat's manager keeps running.
    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is False
    assert len(manager.synced) == 2 and manager.closed == 0 and manager.flushed == []


@pytest.mark.parametrize('cfg,toolset', [({}, True), ({'memory': {'provider': 'mnemo'}}, False)])
def test_without_a_provider_or_with_memory_switched_off_nothing_is_handed_over(memory_env, monkeypatch, cfg, toolset):
    api, _, voice, server, _ = memory_env
    manager = Manager()
    open_chat(server, _memory_manager=manager)
    monkeypatch.setattr(voice, 'config', lambda root: cfg)
    monkeypatch.setattr(voice, 'memory_toolset_on', lambda: toolset)
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    say(api, call_id, [(1, 'user', 'Hello there', True), (1, 'assistant', 'Hi', True)])

    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is False

    assert manager.synced == [] and not handed_over(api, call_id)


def test_a_manager_the_call_built_is_drained_and_shut_down_after_the_hand_over(memory_env, monkeypatch):
    api, _, voice, _, _ = memory_env
    made, _ = provider_environment(monkeypatch, voice)
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    say(api, call_id, [(1, 'user', 'Hello there', True), (1, 'assistant', 'Hi', True)])

    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is True

    manager = made[0]
    assert manager.synced[0][:3] == ('Hello there', 'Hi', 'chat')
    assert manager.initialized[0] == 'chat' and manager.initialized[1]['agent_context'] == 'primary'
    assert manager.flushed == [voice.HAND_OVER_DRAIN] and manager.closed == 1
    assert voice.CALL_MEMORY[call_id].closed and voice.CALL_MEMORY[call_id].manager is None


def test_a_hand_over_without_a_usable_provider_leaves_the_call_free_to_try_again(memory_env, monkeypatch):
    api, _, voice, _, _ = memory_env
    made, provider = provider_environment(monkeypatch, voice, available=False)
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    say(api, call_id, [(1, 'user', 'Hello there', True), (1, 'assistant', 'Hi', True)])

    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is False
    assert made == [] and not handed_over(api, call_id)

    provider.is_available = lambda: True
    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is True
    assert made[0].synced[0][:2] == ('Hello there', 'Hi') and handed_over(api, call_id)


def test_a_provider_fault_before_the_hand_over_leaves_the_call_free_to_try_again(memory_env, monkeypatch):
    api, _, voice, _, _ = memory_env
    made, provider = provider_environment(monkeypatch, voice)
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    say(api, call_id, [(1, 'user', 'Hello there', True), (1, 'assistant', 'Hi', True)])

    def down():
        raise RuntimeError('provider down')

    provider.is_available = down
    with pytest.raises(RuntimeError):
        voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id)
    assert not handed_over(api, call_id)

    provider.is_available = lambda: True
    assert voice.hand_over_call('owner', 'basic', 'default', 'chat', call_id) is True
    assert made[0].synced[0][:2] == ('Hello there', 'Hi')


def test_a_long_call_is_handed_over_from_its_first_exchange(memory_env):
    api, _, voice, _, _ = memory_env
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    rows = [{'event_id': f'{role}{turn}', 'turn_id': str(turn), 'type': 'transcript', 'role': role,
             'text': f'{"Question" if role == "user" else "Answer"} {turn}', 'final': True, 'at_ms': 1000 * turn + (role == 'assistant')}
            for turn in range(1, 151) for role in ('user', 'assistant')]
    for start in range(0, len(rows), 64):
        api.mobile.events(call_id, 'owner', {'events': rows[start:start + 64]})

    turns = voice.call_turns('owner', 'default', 'chat', call_id)

    assert len(turns) == 150 and turns[0] == ('Question 1', 'Answer 1') and turns[-1] == ('Question 150', 'Answer 150')


def test_recall_skips_a_borrowed_manager_that_hermes_shut_down(memory_env, monkeypatch):
    _, _, voice, server, root = memory_env
    dead = Manager()
    dead._shutting_down = True
    dead.get_provider = lambda name: pytest.fail('A shut-down manager is not asked')
    voice.call_slot('call-1').manager = dead
    fresh = SimpleNamespace(prefetch=lambda *args, **kwargs: 'Fresh memory')
    monkeypatch.setattr(voice, 'load_recall_provider', lambda *args: fresh)

    external, name = voice.external_provider('owner', 'basic', 'default', 'chat', {'memory': {'provider': 'mnemo'}}, 'call-1')

    assert external == (None, fresh) and name == 'mnemo'


def test_a_note_saved_just_before_hanging_up_still_reaches_the_provider(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    made, _ = provider_environment(monkeypatch, voice)
    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(
        memory_tool=lambda **kwargs: json.dumps({'success': True}), load_on_disk_store=lambda: object()))
    started, release = threading.Event(), threading.Event()
    build = voice.build_manager

    def slow(*args):
        started.set()
        release.wait(2)
        return build(*args)

    monkeypatch.setattr(voice, 'build_manager', slow)
    write = Manager.notify_memory_tool_write

    def slow_write(self, *args, **kwargs):
        time.sleep(0.3)  # a provider that takes a moment: a release that does not wait shuts it down first
        write(self, *args, **kwargs)

    monkeypatch.setattr(Manager, 'notify_memory_tool_write', slow_write)
    voice.call_inline_tool('memory', 'memory', {'action': 'add', 'target': 'user', 'content': 'Likes jasmine tea'}, SCOPE, 'chat', root)
    assert started.wait(2)
    ending = threading.Thread(target=voice.end_call_memory, args=('call-1',))
    ending.start()
    release.set()
    ending.join(5)

    assert not ending.is_alive() and len(made[0].writes) == 1 and made[0].closed == 1


def test_a_first_provider_start_that_hangs_answers_not_started(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    provider_environment(monkeypatch, voice)
    release = threading.Event()
    monkeypatch.setattr(voice, 'build_manager', lambda *args: release.wait(2) and (None, False))
    monkeypatch.setattr(voice, '_MEMORY_LOOKUP_TIMEOUT', 0.05)
    try:
        refused = json.loads(voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root))
    finally:
        release.set()

    assert refused == {'error': 'The memory provider is not available for this chat.', 'not_started': True}


def test_a_provider_that_fails_to_start_answers_not_started(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    provider_environment(monkeypatch, voice)

    def down(*args):
        raise ConnectionError('provider service down')

    monkeypatch.setattr(voice, 'build_manager', down)

    refused = json.loads(voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root))

    assert refused == {'error': 'The memory provider is not available for this chat.', 'not_started': True}


def test_later_provider_tools_reuse_the_manager_without_a_lookup_slot(memory_env, monkeypatch):
    _, _, voice, _, root = memory_env
    made, _ = provider_environment(monkeypatch, voice)
    voice.call_inline_tool('provider', 'mnemo_search', {}, SCOPE, 'chat', root)
    monkeypatch.setattr(voice, 'bounded_memory_lookup', lambda *args: pytest.fail('The held manager needs no new start'))

    assert json.loads(voice.call_inline_tool('provider', 'mnemo_search', {'query': 'tea'}, SCOPE, 'chat', root)) == {'ran': 'mnemo_search'}
    assert len(made) == 1


def test_ended_calls_leave_the_table_before_a_call_still_running(memory_env, monkeypatch):
    _, _, voice, _, _ = memory_env
    monkeypatch.setattr(voice, 'MAX_CALL_MEMORY', 2)
    running = voice.call_slot('running')
    running.created -= 100
    ended = voice.call_slot('ended')
    ended.closed = True

    voice.call_slot('next')

    assert set(voice.CALL_MEMORY) == {'running', 'next'}


def test_call_memory_held_past_its_time_is_released_for_the_next_call(memory_env, monkeypatch):
    _, _, voice, _, _ = memory_env
    old = voice.call_slot('old-call')
    old.manager, old.owned = Manager(), True
    old.created -= voice.CALL_MEMORY_SECONDS + 1
    released = threading.Event()
    monkeypatch.setattr(voice, 'release_memory', lambda held, drain=0: released.set())

    voice.call_slot('new-call')

    assert released.wait(2) and set(voice.CALL_MEMORY) == {'new-call'}


@pytest.mark.parametrize('entries,expected', [
    (['Prefers tea', 'Name: Maya Example'], 'Maya'),
    (["The user's name is Ahmed"], 'Ahmed'),
    (['User goes by "Sam"'], 'Sam'),
    (['Project name: Beacon', "The user's dog is named Rex", 'Name of the user: Maya', 'Favorite tea: jasmine'], ''),
])
def test_the_owners_name_is_read_from_hermes_user_notes(memory_env, monkeypatch, entries, expected):
    _, _, voice, _, root = memory_env
    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(get_builtin_memory_store_flags=lambda cfg: (True, True)))
    (root / 'memories').mkdir()
    (root / 'memories' / 'USER.md').write_text('\n§\n'.join(entries), encoding='utf-8')

    assert voice.owner_name_in_memory(root, {}) == expected

    monkeypatch.setitem(sys.modules, 'tools.memory_tool', SimpleNamespace(get_builtin_memory_store_flags=lambda cfg: (True, False)))
    assert voice.owner_name_in_memory(root, {}) == ''


def test_the_greeting_name_is_the_typed_one_then_hermes_memory_then_the_sign_in_name(memory_env, monkeypatch):
    api, _, voice, _, _ = memory_env
    call_id = api.mobile.reserve('owner', 'default', 'chat')['call_id']
    monkeypatch.setattr(voice, 'config', lambda root: {})
    monkeypatch.setattr(voice, 'personality', lambda root: '')
    monkeypatch.setattr(voice, 'builtin_memory', lambda root, cfg: '')
    monkeypatch.setattr(voice, 'history', lambda *args: [])
    monkeypatch.setattr(voice, 'owner_name_in_memory', lambda root, cfg: 'Maya')

    def context(display, typed):
        return voice.context('owner', 'basic', 'default', 'chat', call_id, display, typed)

    assert context('Robin Example', 'Sam')['owner_first_name'] == 'Sam'
    result = context('Robin Example', '')
    assert result['owner_first_name'] == 'Maya'
    assert 'check hermes_memory' in result['system_instruction'] and 'memory tool' in result['system_instruction']
    assert 'session_search' in result['system_instruction']
    monkeypatch.setattr(voice, 'owner_name_in_memory', lambda root, cfg: '')
    assert context('Robin Example', '')['owner_first_name'] == 'Robin'


def test_finalize_and_close_hand_a_call_over_unless_it_failed(harness, monkeypatch):
    api, client, _, _, _ = harness
    handed, arrived, ended = [], threading.Event(), []
    monkeypatch.setattr(api.voice_context, 'hand_over_call', lambda *args: (handed.append(args), arrived.set()))
    monkeypatch.setattr(api.voice_context, 'end_call_memory', lambda *args: ended.append(args[0]))
    path = '/api/plugins/beacon/mobile/calls/'

    first = reserve(client)
    assert client.post(path + first + '/finalize', json={}).status_code == 200
    assert arrived.wait(2) and handed == [('owner', 'basic', 'default', 'native-stored', first)]

    arrived.clear()
    second = reserve(client)
    assert client.request('DELETE', path + second, json={}).status_code == 200
    assert arrived.wait(2) and handed[-1] == ('owner', 'basic', 'default', 'native-stored', second)

    before = len(handed)
    failed_finalize, failed_close = reserve(client), reserve(client)
    assert client.post(path + failed_finalize + '/finalize', json={'failed': True}).status_code == 200
    assert client.request('DELETE', path + failed_close, json={'failed': True}).status_code == 200
    time.sleep(0.2)
    # A failed call hands nothing over; it only lets go of any memory provider the call had warmed.
    assert len(handed) == before and sorted(ended) == sorted([failed_finalize, failed_close])


def test_a_call_the_phone_never_ended_is_handed_over_once_after_beacon_closes_it(harness, monkeypatch):
    api, _, _, _, _ = harness
    handed, arrived = [], threading.Event()
    monkeypatch.setattr(api.voice_context, 'hand_over_call', lambda *args: (handed.append(args), arrived.set()))
    monkeypatch.setattr(api, '_swept_until', {})
    monkeypatch.setattr(api, '_swept_calls', set())
    stale, flagged, silent = (api.mobile.reserve('owner', 'default', 'chat')['call_id'] for _ in range(3))
    spoken = json.dumps({'type': 'transcript', 'role': 'user', 'text': 'hello', 'final': True})
    with api.mobile.connect() as conn:
        for call_id in (stale, flagged, silent):
            conn.execute("UPDATE calls SET closed=1,ended=?,status='failed' WHERE id=?", (time.time(), call_id))
        conn.execute('UPDATE calls SET receipt=? WHERE id=?', (json.dumps({'failed': True}), flagged))
        for call_id in (stale, flagged):
            conn.execute('INSERT INTO timeline VALUES(?,?,?,?)', (call_id, 'spoken', '', spoken))

    api.sweep_unended_calls('owner', 'basic', 'default')

    # Only the stale call with something said; a call the phone reported as failed, or a silent one, is left alone.
    assert arrived.wait(2) and handed == [('owner', 'basic', 'default', 'chat', stale)]
    api.sweep_unended_calls('owner', 'basic', 'default')
    api._swept_until.clear()
    api.sweep_unended_calls('owner', 'basic', 'default')
    time.sleep(0.2)
    assert len(handed) == 1


def test_the_sweep_reaches_older_unended_calls_after_three_failed_hand_overs(harness, monkeypatch):
    api = harness[0]
    tried = []
    monkeypatch.setattr(api, 'hand_over', lambda owner, provider, row, failed=False: tried.append(row['id']))
    ids = []
    for index in range(5):
        call_id = api.mobile.reserve('owner', 'default', 'native-stored')['call_id']
        say(api, call_id, [(1, 'user', 'Hello', True), (1, 'assistant', 'Hi', True)])
        ids.append(call_id)
    with api.mobile.connect() as conn:
        for index, call_id in enumerate(ids):
            conn.execute("UPDATE calls SET closed=1,status='failed',ended=? WHERE id=?", (time.time() - 60 * (index + 1), call_id))
    monkeypatch.setattr(api, '_swept_calls', set(ids[:3]))
    monkeypatch.setattr(api, '_swept_until', {})

    api.sweep_unended_calls('owner', 'basic', 'default')

    assert tried == ids[3:]


def test_the_owners_first_words_start_the_providers_recall_once_memory_is_on(memory_env, monkeypatch):
    _, _, voice, server, _ = memory_env
    manager, queued = Manager(), []
    manager.queue_prefetch_all = lambda words, *, session_id: queued.append((words, session_id))
    open_chat(server, _memory_manager=manager)
    monkeypatch.setattr(voice, 'config', lambda root: {'memory': {'provider': 'mnemo'}})
    monkeypatch.setattr(voice, 'memory_toolset_on', lambda: True)
    monkeypatch.setitem(sys.modules, 'agent.memory_provider', SimpleNamespace(
        is_trivial_prompt=lambda text: text.strip().lower() in {'hi', 'ok'}))

    assert voice.warm_recall('owner', 'basic', 'default', 'chat', 'call-1', 'What is on my calendar tomorrow') is True
    assert voice.warm_recall('owner', 'basic', 'default', 'chat', 'call-1', 'hi') is False
    monkeypatch.setattr(voice, 'memory_toolset_on', lambda: False)
    assert voice.warm_recall('owner', 'basic', 'default', 'chat', 'call-1', 'Another real question') is False

    assert queued == [('What is on my calendar tomorrow', 'chat')]


def test_hermes_memory_reads_through_the_provider_the_call_already_holds(memory_env, monkeypatch):
    _, _, voice, _, _ = memory_env
    matching = SimpleNamespace(prefetch=lambda query, *, session_id: 'Warm recall')
    voice.call_slot('call-1').manager = SimpleNamespace(get_provider=lambda name: matching)
    configured = {'memory': {'provider': 'mnemo'}}
    monkeypatch.setattr(voice, 'load_recall_provider', lambda *args: 'fresh')

    (manager, match), name = voice.external_provider('owner', 'basic', 'default', 'chat', configured, 'call-1')

    assert manager is voice.CALL_MEMORY['call-1'].manager and match is matching and name == 'mnemo'
    # Another call does not borrow it, and a lookup with no held provider reads through a fresh one, as before.
    assert voice.external_provider('owner', 'basic', 'default', 'chat', configured, 'call-2') == ((None, 'fresh'), 'mnemo')
    assert voice.external_provider('owner', 'basic', 'default', 'chat', configured) == ((None, 'fresh'), 'mnemo')


def test_first_final_words_on_a_call_start_the_warm_up_and_the_memory_route_names_the_call(harness, monkeypatch):
    api, client, _, _, _ = harness
    warmed, arrived, looked_up = [], threading.Event(), []
    monkeypatch.setattr(api.voice_context, 'warm_recall', lambda *args: (warmed.append(args), arrived.set()))
    monkeypatch.setattr(api.voice_context, 'recall', lambda *args: looked_up.append(args) or {
        'status': 'empty', 'summary': 'No saved memories matched.', 'sources': [], 'observed_at': 1})
    monkeypatch.setattr(api, '_warmed', set())
    call_id = reserve(client)
    path = '/api/plugins/beacon/mobile/calls/' + call_id

    def say_once(event_id, turn, text, final):
        response = client.post(path + '/events', json={'events': [{
            'event_id': event_id, 'turn_id': turn, 'type': 'transcript', 'role': 'user', 'text': text,
            'final': final, 'at_ms': 10}]})
        assert response.status_code == 200, response.text

    say_once('p1', '1', 'What is', False)
    time.sleep(0.2)
    assert warmed == []
    say_once('f1', '1', 'What is next on my list', True)
    assert arrived.wait(2)
    arrived.clear()
    say_once('f2', '2', 'And after that', True)
    time.sleep(0.2)
    assert warmed == [('owner', 'basic', 'default', 'native-stored', call_id, 'What is next on my list')]

    assert client.post(path + '/memory', json={'query': 'tea'}).status_code == 200
    assert looked_up == [('owner', 'basic', 'default', 'native-stored', 'tea', call_id)]
