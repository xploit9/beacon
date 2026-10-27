"""A scheduled job can post into one of the owner's threads (`deliver: phone:<thread id>`)."""
import asyncio
from contextlib import contextmanager
import importlib
import os
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)

THREAD = '20261026_083012_abcdef'
GONE = 'That thread is no longer available'
BASE = '/api/plugins/beacon/mobile/threads'


def envelope(job_id='news', name='Cybersecurity news'):
    return f'Cronjob Response: {name}\n(job_id: {job_id})\n---\nThree new advisories.'


def notices(api):
    with api.mobile.connect() as conn:
        return [dict(row) for row in conn.execute('SELECT conversation,kind,reason,job_id FROM notices')]


@pytest.fixture
def poster(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    phone = load_private('phone_delivery')
    monkeypatch.setattr(phone, '_modules', lambda: (api.mobile, api.native, api.proactive))
    monkeypatch.setattr(phone, '_profile_state', lambda: (api.mobile, cfg, 'default'))
    posts = []
    monkeypatch.setattr(api.native, 'post', lambda *args: posts.append(args) or 1)
    return SimpleNamespace(api=api, client=client, cfg=cfg, phone=phone, posts=posts)


def test_a_job_posts_into_its_thread_and_the_phone_opens_it(poster):
    sent = asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))

    assert sent['success'] is True
    (owner, provider, profile, stored, key, text, name), = poster.posts
    assert (owner, provider, profile, stored, text, name) == (
        'owner', 'basic', 'default', THREAD, 'Three new advisories.', 'Cybersecurity news')
    assert notices(poster.api) == [{'conversation': THREAD, 'kind': 'task_result', 'reason': 'Three new advisories.', 'job_id': key}]


def test_a_retry_keeps_one_key_and_one_notice(poster):
    first = asyncio.run(poster.phone.BeaconPhoneAdapter({}).send(THREAD, envelope()))
    again = asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))

    assert first.success and again['message_id'] == first.message_id
    assert len(poster.posts) == 2 and poster.posts[0][4] == poster.posts[1][4]  # Hermes keeps one row per key
    assert len(notices(poster.api)) == 1


@pytest.mark.parametrize('chat_id', ['phone', '', ' phone ', None])
def test_the_home_channel_stays_the_beacon_chat(poster, chat_id):
    sent = asyncio.run(poster.phone.standalone_send({}, chat_id, envelope()))

    assert sent['success'] is True and poster.posts == []
    assert [row['conversation'] for row in notices(poster.api)] == ['assistant-chat']


def test_a_thread_the_owner_does_not_hold_gets_nothing(poster, monkeypatch):
    def verify(owner, provider, profile, stored):
        if stored == 'foreign':
            raise LookupError('Native stored conversation not owned')

    monkeypatch.setattr(poster.api.native, 'verify', verify)

    for target in ('foreign', '../etc', 'x' * 161):
        assert asyncio.run(poster.phone.standalone_send({}, target, envelope())) == {'error': GONE}
    assert poster.posts == [] and notices(poster.api) == []


class SessionTurnLeaseLostError(RuntimeError):
    """Hermes' refusal while the thread's own turn runs (same class name as hermes_state_errors)."""


def test_a_post_that_arrives_mid_turn_waits_and_lands_with_its_notice_after_the_turn(poster, monkeypatch):
    busy = [True]

    def post(*args):
        if busy[0]:
            raise SessionTurnLeaseLostError('Session has an active turn lease')
        poster.posts.append(args)

    monkeypatch.setattr(poster.api.native, 'post', post)
    monkeypatch.setattr(poster.api, '_posts_until', {})

    sent = asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))
    assert sent['success'] is True and sent['message_id'].startswith('held-')
    assert notices(poster.api) == [] and len(poster.api.mobile.held_posts('owner', 'default')) == 1
    poster.api.flush_held_posts('owner', 'basic', 'default')
    assert poster.posts == [] and len(poster.api.mobile.held_posts('owner', 'default')) == 1  # still mid-turn: kept

    busy[0] = False
    monkeypatch.setattr(poster.api, '_posts_until', {})
    poster.api.flush_held_posts('owner', 'basic', 'default')

    assert len(poster.posts) == 1 and poster.posts[0][5] == 'Three new advisories.'
    assert [row['conversation'] for row in notices(poster.api)] == [THREAD]
    assert poster.api.mobile.held_posts('owner', 'default') == []


def mid_turn(poster, monkeypatch):
    """Every thread is mid-turn until the returned flag is cleared."""
    busy = [True]

    def post(*args):
        if busy[0]:
            raise SessionTurnLeaseLostError('Session has an active turn lease')
        poster.posts.append(args)

    monkeypatch.setattr(poster.api.native, 'post', post)
    monkeypatch.setattr(poster.api, '_posts_until', {})
    return busy


def held(api):
    with api.mobile.connect() as conn:
        return sorted((row['profile'], row['conversation']) for row in conn.execute('SELECT profile,conversation FROM held_posts'))


def test_one_run_delivering_to_two_threads_holds_a_post_for_each(poster, monkeypatch):
    # Strix 1c4f37e vuln-0008: the second thread's post shared the first one's key and was silently dropped.
    mid_turn(poster, monkeypatch)
    other = '20261026_083012_fedcba'

    sent = [asyncio.run(poster.phone.standalone_send({}, thread, envelope())) for thread in (THREAD, other)]

    assert all(result['success'] for result in sent) and sent[0]['message_id'] != sent[1]['message_id']
    assert held(poster.api) == [('default', THREAD), ('default', other)]
    asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))  # a retry of one delivery still lands on its row
    assert held(poster.api) == [('default', THREAD), ('default', other)]


def test_one_job_in_two_profiles_holds_a_post_for_each_and_a_flush_keeps_the_other(poster, monkeypatch):
    busy = mid_turn(poster, monkeypatch)
    profile = ['default']
    monkeypatch.setattr(poster.phone, '_profile_state', lambda: (poster.api.mobile, poster.cfg, profile[0]))

    for name in ('default', 'work'):
        profile[0] = name
        assert asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))['success'] is True

    assert held(poster.api) == [('default', THREAD), ('work', THREAD)]
    busy[0] = False
    poster.api.flush_held_posts('owner', 'basic', 'default')
    assert [post[2] for post in poster.posts] == ['default'] and held(poster.api) == [('work', THREAD)]


def test_dropping_a_held_post_needs_its_owner_and_profile(poster):
    mobile = poster.api.mobile
    key = 'phone-cron-' + 'a' * 32
    mobile.hold_post('owner', 'basic', 'default', THREAD, key, 'News', 'News')

    mobile.drop_held_post('owner', 'work', key)
    mobile.drop_held_post('someone-else', 'default', key)
    assert held(poster.api) == [('default', THREAD)]

    mobile.drop_held_post('owner', 'default', key)
    assert held(poster.api) == []


def test_a_thread_post_notification_shows_the_thread_name_over_the_post(poster, monkeypatch):
    monkeypatch.setattr(poster.api.native, 'threads', lambda *args: [{'id': THREAD, 'title': '🛡️ Cybersecurity news'}])
    sent = asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))

    detail = poster.client.get('/api/plugins/beacon/mobile/notifications/events/' + sent['message_id']).json()

    assert detail['preview'] == {'title': '🛡️ Cybersecurity news', 'body': 'Three new advisories.'}
    assert detail['session_id'] == THREAD


def test_a_held_post_for_a_thread_that_is_gone_never_lands_and_expires(poster, monkeypatch):
    poster.api.mobile.hold_post('owner', 'basic', 'default', THREAD, 'phone-cron-' + 'a' * 32, 'Late news', 'News')

    def verify(owner, provider, profile, stored):
        raise LookupError('Native stored conversation not owned')

    monkeypatch.setattr(poster.api.native, 'verify', verify)
    monkeypatch.setattr(poster.api, '_posts_until', {})
    poster.api.flush_held_posts('owner', 'basic', 'default')
    assert poster.posts == [] and notices(poster.api) == []

    monkeypatch.setattr(poster.api.mobile, 'HELD_POST_SECONDS', -1)
    assert poster.api.mobile.held_posts('owner', 'default') == []
    with poster.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM held_posts').fetchone()[0] == 0  # gone from the host, not just hidden


def test_the_event_watch_keeps_activity_while_the_store_cannot_be_opened(harness, monkeypatch):
    api = harness[0]

    def unavailable(*args, **kwargs):
        raise RuntimeError('Native conversation store unavailable')

    monkeypatch.setattr(api.activity, 'sync', unavailable)
    api.watch('owner', 'basic', 'default')  # no 500 on the phone's event poll or end-call


def test_a_held_post_survives_a_store_that_is_briefly_unavailable(poster, monkeypatch):
    poster.api.mobile.hold_post('owner', 'basic', 'default', THREAD, 'phone-cron-' + 'b' * 32, 'Late news', 'News')

    def unavailable(*args):
        raise RuntimeError('Native conversation store unavailable')

    monkeypatch.setattr(poster.api.native, 'verify', unavailable)
    monkeypatch.setattr(poster.api, '_posts_until', {})
    poster.api.flush_held_posts('owner', 'basic', 'default')
    assert len(poster.api.mobile.held_posts('owner', 'default')) == 1 and notices(poster.api) == []

    monkeypatch.setattr(poster.api.native, 'verify', lambda *args: {'id': THREAD})
    monkeypatch.setattr(poster.api, '_posts_until', {})
    poster.api.flush_held_posts('owner', 'basic', 'default')
    assert len(poster.posts) == 1 and [row['conversation'] for row in notices(poster.api)] == [THREAD]


def test_any_other_refusal_stays_a_retryable_failure_without_a_notice(poster, monkeypatch):
    def post(*args):
        raise RuntimeError('Native conversation store unavailable')

    monkeypatch.setattr(poster.api.native, 'post', post)

    failed = asyncio.run(poster.phone.standalone_send({}, THREAD, envelope()))

    assert failed == {'error': 'Native conversation store unavailable'} and notices(poster.api) == []
    assert poster.api.mobile.held_posts('owner', 'default') == []


@pytest.fixture
def store(monkeypatch):
    notes, opened = [], []
    state = SimpleNamespace(db=SimpleNamespace(append_completion_note=lambda *args: notes.append(args) or 9))

    @contextmanager
    def profile_db(params, *, writer=False):
        opened.append((params['profile'], writer))
        yield state.db

    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=SimpleNamespace(_profile_db=profile_db)))
    return load_private('native'), notes, opened, state


def test_post_records_one_labelled_user_row_for_the_key(store):
    native, notes, opened, _ = store

    assert native.post('owner', 'basic', 'work', THREAD, 'phone-cron-k', 'Three new advisories.', 'Cybersecurity news') == 9
    assert notes == [(THREAD, 'phone-cron-k', '[Cron delivery: Cybersecurity news]\nThree new advisories.',
                      'cron_delivery', {'job': 'Cybersecurity news'})]
    assert opened == [('work', True)]


def test_post_lands_on_the_continuation_of_a_compressed_thread(store):
    native, notes, _, state = store
    state.db.resolve_resume_session_id = lambda stored: 'continued-' + stored

    native.post('owner', 'basic', 'work', THREAD, 'phone-cron-k', 'text', 'name')
    state.db.resolve_resume_session_id = lambda stored: 1 / 0  # a failed lookup keeps the thread's own id
    native.post('owner', 'basic', 'work', THREAD, 'phone-cron-k', 'text', 'name')

    assert [note[0] for note in notes] == ['continued-' + THREAD, THREAD]


def test_post_fails_closed_without_a_store_or_the_native_row_writer(store):
    native, notes, _, state = store
    state.db = None
    with pytest.raises(RuntimeError, match='store unavailable'):
        native.post('owner', 'basic', 'work', THREAD, 'k', 'text', 'name')
    state.db = SimpleNamespace()
    with pytest.raises(RuntimeError, match='newer Hermes'):
        native.post('owner', 'basic', 'work', THREAD, 'k', 'text', 'name')
    assert notes == []


def test_threads_are_the_beacon_and_relay_threads_only():
    native = load_private('native')
    native.conversation_list = lambda owner, provider, profile: {'sessions': [
        {'id': 'a', 'title': 'Cybersecurity news', 'source': 'android_thread'},
        {'id': 'b', 'title': '', 'source': 'phone'},
        {'id': 'c', 'title': 'Beacon', 'source': 'android'},
        {'id': 'd', 'title': 'A cron run', 'source': 'cron'}]}

    assert native.threads('owner', 'basic', 'default') == [
        {'id': 'a', 'title': 'Cybersecurity news'}, {'id': 'b', 'title': ''}]


def test_the_thread_list_is_owner_and_profile_scoped(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    monkeypatch.setattr(api.native, 'threads', lambda owner, provider, profile: [{'id': THREAD, 'title': 'Cybersecurity news'}])

    assert client.get(BASE).json() == {'threads': [{'id': THREAD, 'title': 'Cybersecurity news'}]}
    assert client.get(BASE, headers={'x-fixture-owner': 'foreign'}).status_code == 403


def test_hermes_hands_the_thread_id_to_the_phone_platform(harness, monkeypatch):
    monkeypatch.syspath_prepend(str(Path(os.environ.get('HERMES_AGENT_SOURCE') or Path.home() / 'AppData' / 'Local' / 'hermes' / 'hermes-agent')))
    import hermes_cli.plugins as native_plugins
    from cron.scheduler_delivery import _resolve_delivery_targets
    from gateway.platform_registry import PlatformEntry, PlatformRegistry
    registry_module = importlib.import_module('gateway.platform_registry')
    registry = PlatformRegistry()
    monkeypatch.setattr(registry_module, 'platform_registry', registry)
    monkeypatch.setattr(native_plugins, 'discover_plugins', lambda: None)
    monkeypatch.delenv('PHONE_HOME_CHANNEL', raising=False)
    load_private('phone_delivery').register(SimpleNamespace(register_platform=lambda **kwargs: registry.register(PlatformEntry(**kwargs))))

    targets = _resolve_delivery_targets({'deliver': 'phone:' + THREAD})

    assert [(target['platform'], target['chat_id'], target['thread_id']) for target in targets] == [('phone', THREAD, None)]
