"""Bot Chat on a core that stamps every login with the host owner's subject (owner trial 1.1.33, F7).

The page opened with "Bot Chat is unavailable or belongs to another owner" and Retry never helped: the chat
the first tap created was stamped with the host subject, the plugin could not prove it was this owner's
without Beacon's own record, and the empty row stayed behind to fail every retry."""
import importlib.util
import sqlite3
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from tui_gateway.session_transports import _transport_auth_user_id

from test_mobile import harness  # noqa: F401  (fixture used by `bound`)
from test_native_owner_compat import bound  # noqa: F401

spec = importlib.util.spec_from_file_location('bot_chat_provenance_test', Path(__file__).parents[1] / 'bot_mode_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def serve(b, with_login=True):
    """The real plugin helpers against a SessionDB; only the native dispatcher is replaced."""
    seen = {'create': 0, 'title': 0}
    b.server._transport_auth_user_id = _transport_auth_user_id
    b.server._session_transport_contains = lambda record, transport: record.get('transport') is transport

    def dispatch(frame, transport):
        method, params = frame['method'], frame['params']
        def ok(result): return {'id': frame['id'], 'result': result}
        if method == 'profiles.list':
            return ok({'profiles': [{'name': 'default', 'display_name': 'Personal', 'ui_meta': {'hermes-bots': {'title': 'Personal'}}}]})
        if method == 'session.list':
            row = b.db.get_session_by_title(params['title'])
            return ok({'sessions': [{'id': row['id'], 'title': row['title']}] if row else []})
        if method == 'session.create':
            seen['create'] += 1
            b.server._sessions['runtime-1'] = {'session_key': 'bot-chat-1', 'profile_home': str(b.home), 'transport': transport,
                                               'auth_user_id': _transport_auth_user_id(transport)}
            return ok({'session_id': 'runtime-1', 'stored_session_id': 'bot-chat-1'})
        if method == 'session.title':
            seen['title'] += 1
            b.db.set_session_title('bot-chat-1', params['title'])
            return ok({'title': params['title']})
        raise AssertionError('unexpected native call ' + method)

    def persist(record):
        b.db.create_session('bot-chat-1', 'android', user_id=record['auth_user_id'])
        return True

    b.server.dispatch = dispatch
    b.server._ensure_session_db_row = persist
    app = FastAPI()

    if with_login:
        @app.middleware('http')
        async def login(request, call_next):
            request.state.session = SimpleNamespace(user_id='owner', provider='basic', issuer='https://one.synthetic')
            return await call_next(request)

    def identity(request, profile=None):
        return 'owner', 'basic', profile or 'default', {'profiles': ['default']}

    async def body(request, limit): return await request.json()
    app.include_router(api.build_router(identity, body, b.native, b.api.voice_principal))
    return TestClient(app), seen


def bound_to_login(b, stored):
    return b.api.mobile.native_conversation('owner', 'basic', 'default', b.native.home_fingerprint(b.home), stored, b.login)


def left_behind(b, stored='orphan', user=None, messages=0):
    """What the failed open left: a Bot Chat row stamped with the host subject and never bound."""
    b.db.create_session(stored, 'android', user_id=user or b.execution)
    b.db.set_session_title(stored, 'Bot Chat')
    if messages:
        # append_message would start Hermes' own runtime bootstrap; the stored counter is what a used chat carries.
        with sqlite3.connect(b.home / 'state.db') as conn:
            conn.execute('UPDATE sessions SET message_count=? WHERE id=?', (messages, stored))


def test_first_open_creates_a_chat_the_owner_can_reopen(bound):
    b = bound
    client, seen = serve(b)
    first = client.post('/mobile/bot-mode/chat', json={'bot': 'default'})
    assert first.status_code == 200, first.text
    assert first.json() == {'profile': 'default', 'stored_session_id': 'bot-chat-1', 'title': 'Bot Chat'}
    assert b.db.get_session('bot-chat-1')['user_id'] == b.execution
    assert bound_to_login(b, 'bot-chat-1')['principal'] == b.login
    # Tapping the bot again finds that chat through the title; it is never created twice.
    again = client.post('/mobile/bot-mode/chat', json={'bot': 'default'})
    assert again.status_code == 200 and again.json()['stored_session_id'] == 'bot-chat-1'
    assert seen['create'] == 1


def test_empty_chat_left_by_a_failed_open_is_recovered(bound):
    b = bound
    left_behind(b)
    client, seen = serve(b)
    reply = client.post('/mobile/bot-mode/chat', json={'bot': 'default'})
    assert reply.status_code == 200 and reply.json()['stored_session_id'] == 'orphan'
    assert bound_to_login(b, 'orphan')['execution'] == b.execution
    assert seen['create'] == 0
    # Retry after recovery keeps working.
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).json()['stored_session_id'] == 'orphan'


def test_chat_with_history_and_no_record_is_not_adopted(bound):
    b = bound
    left_behind(b, messages=1)
    client, seen = serve(b)
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).status_code == 409
    assert bound_to_login(b, 'orphan') is None and seen['create'] == 0


def test_another_owners_chat_is_never_adopted(bound):
    b = bound
    left_behind(b, user='basic:someone-else')
    client, seen = serve(b)
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).status_code == 409
    assert bound_to_login(b, 'orphan') is None and seen['create'] == 0


def test_without_a_verified_login_nothing_is_adopted_or_created(bound):
    b = bound
    left_behind(b)
    client, seen = serve(b, with_login=False)
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).status_code == 409
    assert bound_to_login(b, 'orphan') is None and seen['create'] == 0


def test_refusal_does_not_show_internal_wording(bound):
    b = bound
    left_behind(b, messages=1)
    client, _ = serve(b)
    detail = client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).json()['detail']
    assert 'owner' not in detail.lower() and 'native' not in detail.lower()


def test_bot_chat_reads_the_login_exactly_as_the_call_routes_do(harness, monkeypatch):
    """One check decides which login a request carries. A second copy of it in Bot Chat could drift from the calls'."""
    plugin = harness[0]
    made, asked = [], []
    profiles = {'profiles': [{'name': 'default', 'display_name': 'Personal', 'ui_meta': {'hermes-bots': {'title': 'Personal'}}}]}

    def rpc(owner, provider, method, params, *principal):
        asked.append(method)
        return {'profiles.list': profiles, 'session.list': {'sessions': []}, 'session.title': {},
                'session.create': {'session_id': 'runtime-1', 'stored_session_id': 'bot-chat-1'}}[method]

    monkeypatch.setattr(plugin.native, 'rpc', rpc)
    monkeypatch.setattr(plugin.native, 'create', lambda owner, provider, profile, principal, *rest: made.append(principal) or 'bot-chat-1')
    shape = {}
    app = FastAPI()
    app.state.auth_required = True

    @app.middleware('http')
    async def login(request, call_next):
        request.state.session = SimpleNamespace(user_id='owner', provider='basic', **shape.get('session', {}))
        if 'grant' in shape:
            request.state.native_http_principal = shape['grant']
        return await call_next(request)

    app.include_router(plugin.router, prefix='/api/plugins/beacon')
    client = TestClient(app)

    def open_chat():
        made.clear(); asked.clear()
        reply = client.post('/api/plugins/beacon/mobile/bot-mode/chat', json={'bot': 'default'})
        assert reply.status_code == 200, reply.text
        return made[0] if made else None

    def as_calls_read_it():
        state = SimpleNamespace(session=SimpleNamespace(user_id='owner', provider='basic', **shape.get('session', {})))
        if 'grant' in shape:
            state.native_http_principal = shape['grant']
        return plugin.voice_principal(SimpleNamespace(state=state), 'owner', 'basic')

    logins = [{'session': {'issuer': 'https://one.synthetic'}},
              {'session': {}},
              {'session': {'issuer': 5}},
              {'session': {'issuer': 'https://one.synthetic'}, 'grant': {'user_id': 'owner', 'provider': 'basic'}}]
    for shape_ in logins:
        shape.clear(); shape.update(shape_)
        expected = as_calls_read_it()
        assert open_chat() == (expected or None), shape_
        assert ('session.create' in asked) == (not expected), shape_
    assert as_calls_read_it() is None  # the last one is a grant, which is never a login

    # Whatever the calls' check says, Bot Chat follows it: they are one function, not two copies.
    shape.clear(); shape['session'] = {'issuer': 'https://one.synthetic'}
    monkeypatch.setattr(plugin, 'voice_principal', lambda request, owner, provider: 'principal-from-the-one-check')
    assert open_chat() == 'principal-from-the-one-check'
    monkeypatch.setattr(plugin, 'voice_principal', lambda request, owner, provider: None)
    assert open_chat() is None and 'session.create' in asked
