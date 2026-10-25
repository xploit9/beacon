"""A scheduled phone result needs no visited Beacon chat: an owned empty chat is saved on first use."""
import asyncio
from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)
from test_owner_claim import _native_profiles, _use_real_settings, _write_config

NO_CHAT = 'No owned assistant conversation is configured for phone delivery'
PATH = '/api/plugins/beacon/mobile/assistant-conversation'


def envelope(job_id):
    return f'Cronjob Response: Daily brief\n(job_id: {job_id})\n---\nYour task result.'


@pytest.fixture
def unregistered(harness, monkeypatch):
    api, client, _, home, _ = harness
    _write_config(home, {})
    _use_real_settings(api, monkeypatch)
    _native_profiles(monkeypatch, ['default', 'work'])
    monkeypatch.setattr(api.native, 'verify', lambda *args, **kwargs: {'id': args[3]})
    assert client.post('/api/plugins/beacon/owner/claim').status_code == 200
    created = []

    def provision(owner, provider, profile):
        created.append(profile)
        return f'saved-{profile}-{len(created)}'

    monkeypatch.setattr(api.native, 'provision', provision)
    phone = load_private('phone_delivery')
    profile = ['default']
    monkeypatch.setattr(phone, '_modules', lambda: (api.mobile, api.native, api.proactive))
    monkeypatch.setattr(phone, '_profile_state', lambda: (api.mobile, api.mobile.settings(profile[0]), profile[0]))

    def send(job_id):
        return asyncio.run(phone.standalone_send({}, 'phone', envelope(job_id)))

    return SimpleNamespace(api=api, client=client, phone=phone, created=created, profile=profile, send=send)


def stored_chat(api, profile='default'):
    return api.mobile.settings(profile).get('scheduled_callback_conversation_id')


def notice_conversation(api, event):
    with api.mobile.connect() as conn:
        row = conn.execute('SELECT conversation,kind,reason FROM notices WHERE id=?', (event,)).fetchone()
    return row['conversation'], row['kind'], row['reason']


def test_first_result_saves_an_owned_chat_and_reaches_the_phone(unregistered):
    s = unregistered
    sent = s.send('daily-brief')

    assert sent['success'] is True and s.created == ['default']
    assert stored_chat(s.api) == 'saved-default-1'
    assert notice_conversation(s.api, sent['message_id']) == ('saved-default-1', 'task_result', 'Your task result.')
    later = s.send('another-job')
    assert later['success'] and s.created == ['default']  # one chat serves every later result
    assert notice_conversation(s.api, later['message_id'])[0] == 'saved-default-1'


def test_a_registered_beacon_chat_is_used_unchanged(unregistered):
    s = unregistered
    assert s.client.put(PATH, json={'profile': 'default', 'session_id': 'my-beacon-chat'}).status_code == 200

    sent = s.send('daily-brief')

    assert sent['success'] and s.created == []
    assert notice_conversation(s.api, sent['message_id'])[0] == 'my-beacon-chat'


def test_the_owners_own_beacon_chat_replaces_the_saved_one_and_then_stays(unregistered):
    s = unregistered
    s.send('daily-brief')
    replaced = s.client.put(PATH, json={'profile': 'default', 'session_id': 'my-beacon-chat'})
    assert replaced.json() == {'registered': True, 'conversation_id': 'my-beacon-chat'}

    sent = s.send('another-job')

    assert notice_conversation(s.api, sent['message_id'])[0] == 'my-beacon-chat' and s.created == ['default']
    third = s.client.put(PATH, json={'profile': 'default', 'session_id': 'third-chat'})
    assert third.json()['conversation_id'] == 'my-beacon-chat'
    with s.api.mobile.connect() as conn:
        claim = json.loads(conn.execute("SELECT value FROM meta WHERE key='owner_claim'").fetchone()['value'])
    assert 'provisional_conversations' not in claim


def test_registering_the_saved_chat_itself_makes_it_the_beacon_chat(unregistered):
    s = unregistered
    s.send('daily-brief')
    assert s.client.put(PATH, json={'profile': 'default', 'session_id': 'saved-default-1'}).json()['conversation_id'] == 'saved-default-1'

    assert s.client.put(PATH, json={'profile': 'default', 'session_id': 'third-chat'}).json()['conversation_id'] == 'saved-default-1'


def test_each_profile_gets_its_own_chat(unregistered):
    s = unregistered
    s.send('daily-brief')
    s.profile[0] = 'work'
    sent = s.send('daily-brief')

    assert sent['success'] and s.created == ['default', 'work']
    assert (stored_chat(s.api, 'default'), stored_chat(s.api, 'work')) == ('saved-default-1', 'saved-work-2')
    assert notice_conversation(s.api, sent['message_id'])[0] == 'saved-work-2'


def test_nothing_is_saved_for_a_phone_without_an_owner(unregistered, monkeypatch):
    s = unregistered
    monkeypatch.setattr(s.phone, '_profile_state', lambda: (s.api.mobile, {}, 'default'))

    assert s.send('daily-brief') == {'error': NO_CHAT}
    assert s.created == []
    stranger = s.api.mobile.register_assistant_conversation('stranger', 'basic', 'default', 'x', s.api.native.verify, provisional=True)
    assert stranger is False and stored_chat(s.api) is None


@pytest.mark.parametrize('failure', [ImportError('hermes_state_ids'), AttributeError('create_session'), RuntimeError('store')])
def test_a_missing_or_changed_hermes_api_keeps_todays_error(unregistered, monkeypatch, failure):
    s = unregistered

    def broken(owner, provider, profile):
        raise failure

    monkeypatch.setattr(s.api.native, 'provision', broken)

    assert s.send('daily-brief') == {'error': NO_CHAT}
    sent = asyncio.run(s.phone.BeaconPhoneAdapter({}).send('phone', envelope('daily-brief')))
    assert sent.success is False and sent.retryable is True and sent.error == NO_CHAT
    assert stored_chat(s.api) is None
    with s.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] == 0


def test_a_refused_registration_delivers_nothing(unregistered, monkeypatch):
    s = unregistered
    monkeypatch.setattr(s.api.mobile, 'register_assistant_conversation', lambda *args, **kwargs: False)

    assert s.send('daily-brief') == {'error': NO_CHAT}
    with s.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] == 0


def test_a_saved_chat_never_replaces_a_live_one_but_replaces_a_gone_one(unregistered):
    s = unregistered
    register = s.api.mobile.register_assistant_conversation
    verify = s.api.native.verify
    assert register('owner', 'basic', 'default', 'chat-a', verify, provisional=True) == 'chat-a'
    assert register('owner', 'basic', 'default', 'chat-b', verify, provisional=True) == 'chat-a'

    def gone(*args):
        raise LookupError('gone')

    assert register('owner', 'basic', 'default', 'chat-b', gone, provisional=True) == 'chat-b'


def test_saving_a_chat_takes_the_write_lock_before_it_reads_the_claim(unregistered, monkeypatch):
    s = unregistered
    statements = []
    connect = s.api.mobile.connect

    def traced():
        conn = connect()
        conn.set_trace_callback(statements.append)
        return conn

    monkeypatch.setattr(s.api.mobile, 'connect', traced)

    assert s.api.mobile.register_assistant_conversation('owner', 'basic', 'default', 'chat-a', s.api.native.verify, provisional=True) == 'chat-a'

    assert statements[:2] == ['BEGIN IMMEDIATE', "SELECT value FROM meta WHERE key='owner_claim'"]


spec = importlib.util.spec_from_file_location('provision_native', Path(__file__).parents[1] / 'native.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


@pytest.fixture
def store(monkeypatch):
    rows, opened = {}, []
    state = SimpleNamespace(available=True)
    db = SimpleNamespace(get_session=rows.get,
                         create_session=lambda key, source, **kwargs: rows.update({key: {'source': source, **kwargs}}))

    @contextmanager
    def profile_db(params, *, writer=False):
        opened.append((params['profile'], writer))
        yield db if state.available else None

    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=SimpleNamespace(_profile_db=profile_db)))
    monkeypatch.setitem(sys.modules, 'hermes_state_ids', SimpleNamespace(new_session_id=lambda: '20261025_081500_abcdef'))
    return rows, opened, state


def test_provision_saves_one_owned_row_without_a_live_session(store):
    rows, opened, _ = store

    assert native.provision('owner', 'basic', 'work') == '20261025_081500_abcdef'
    assert rows == {'20261025_081500_abcdef': {'source': 'android', 'user_id': 'basic:owner'}}
    assert opened[0] == ('work', True)


def test_provision_fails_closed_when_the_store_is_unavailable(store):
    rows, _, state = store
    state.available = False

    with pytest.raises(RuntimeError):
        native.provision('owner', 'basic', 'work')
    assert not rows


def test_delivery_runs_the_real_provision_and_a_failure_keeps_the_old_error(unregistered, store, monkeypatch):
    s = unregistered
    rows, _, state = store
    verified = []
    verify = native.verify
    monkeypatch.setattr(native, 'verify', lambda *args, **kwargs: verified.append(args) or verify(*args, **kwargs))
    monkeypatch.setattr(s.api.native, 'provision', native.provision)

    sent = s.send('daily-brief')

    assert sent['success'] is True and stored_chat(s.api) == '20261025_081500_abcdef'
    assert rows == {'20261025_081500_abcdef': {'source': 'android', 'user_id': 'basic:owner'}}
    assert verified == [('owner', 'basic', 'default', '20261025_081500_abcdef')]
    state.available = False
    s.profile[0] = 'work'

    assert s.send('daily-brief') == {'error': NO_CHAT}
    assert stored_chat(s.api, 'work') is None and len(rows) == 1
