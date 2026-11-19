"""WEDGE-121 follow-ups 3 and 4: the read-only route that tells the phone which chats (and bots' chats) hold a reply Hermes lost in a restart.

The store is a real Hermes state.db (Hermes' own SessionDB makes the schema) holding synthetic admission rows; the route reads it
through Hermes' state API. Synthetic ids only.
"""
from contextlib import closing
import hashlib
import importlib.util
from pathlib import Path
import sqlite3

import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

spec = importlib.util.spec_from_file_location('beacon_lost_turns_api_test', Path(__file__).parents[1] / 'lost_turns_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)


def _store(path, rows, titles=()):
    """A Hermes state.db with [(chat, admission, status)] admissions and [(chat, title)] sessions; foreign keys are off on this plain connection."""
    from hermes_state import SessionDB
    with closing(SessionDB(path)):
        pass
    with closing(sqlite3.connect(path)) as conn, conn:
        for chat, title in titles:
            conn.execute('INSERT INTO sessions(id,source,started_at,title) VALUES(?,?,?,?)', (chat, 'android', 1.0, title))
        for chat, admission, status in rows:
            started = status in ('started', 'unknown')
            conn.execute(
                'INSERT INTO session_admissions(admission_id,request_id,principal_id,target_session_id,lineage_json,payload_json,'
                'payload_digest,intent,status,owner_epoch,generation) VALUES(?,?,?,?,?,?,?,?,?,?,?)',
                (admission, 'request-' + admission, 'synthetic-principal', chat, '["%s"]' % chat, '{"text":"synthetic"}', 'digest',
                 'queue', status, 7 if started else None, 3 if started else None))
    return path


def _client(path, profiles=None, refuse=False, stores=None):
    seen = profiles if profiles is not None else []

    def identity(request):
        if refuse:
            raise HTTPException(403, 'Profile not authorized')
        return 'owner-1', 'provider-1', 'synthetic-profile', {'profiles': ['synthetic-profile', 'bot-a', 'bot-b']}

    def state_db(profile):
        seen.append(profile)
        return Path((stores or {}).get(profile, path))

    app = FastAPI()
    app.include_router(api.build_router(identity, state_db))
    return TestClient(app)


def _digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def test_a_lost_reply_is_listed_with_what_waits_behind_it(tmp_path):
    db = _store(tmp_path / 'state.db', [
        ('chat-lost-waiting', 'a1', 'terminal'), ('chat-lost-waiting', 'a2', 'unknown'), ('chat-lost-waiting', 'a3', 'queued'),
        ('chat-lost-alone', 'b1', 'unknown'),
        ('chat-running', 'c1', 'started'), ('chat-running', 'c2', 'queued'),
        ('chat-done', 'd1', 'terminal'),
    ])
    answer = _client(db).get('/mobile/lost-turns', params={'ids': 'chat-lost-waiting,chat-lost-alone,chat-running,chat-done,chat-unknown-id'})
    assert answer.status_code == 200
    # Each lost reply comes with what Discard (prompt.resolve_unknown) takes: its admission and the generation Hermes stamped.
    assert answer.json() == {'lost': {
        'chat-lost-waiting': {'waiting': 1, 'unknown': [{'admission_id': 'a2', 'execution_generation': 3}]},
        'chat-lost-alone': {'waiting': 0, 'unknown': [{'admission_id': 'b1', 'execution_generation': 3}]}}}


def test_the_read_never_writes_the_store_and_opens_it_read_only(tmp_path, monkeypatch):
    db = _store(tmp_path / 'state.db', [('chat-lost-alone', 'b1', 'unknown')])
    before = _digest(db), db.stat().st_mtime_ns
    import hermes_state
    opened = []
    real = hermes_state.SessionDB

    def recording(path=None, read_only=False):
        opened.append(read_only)
        return real(path, read_only=read_only)

    monkeypatch.setattr(hermes_state, 'SessionDB', recording)
    assert _client(db).get('/mobile/lost-turns', params={'ids': 'chat-lost-alone'}).json()['lost']['chat-lost-alone']['waiting'] == 0
    assert opened == [True]
    assert (_digest(db), db.stat().st_mtime_ns) == before


def test_the_read_is_the_callers_own_profile_and_a_refused_caller_reads_nothing(tmp_path):
    db = _store(tmp_path / 'state.db', [('chat-lost-alone', 'b1', 'unknown')])
    profiles = []
    assert _client(db, profiles).get('/mobile/lost-turns', params={'ids': 'chat-lost-alone'}).status_code == 200
    assert profiles == ['synthetic-profile']
    refused = []
    assert _client(db, refused, refuse=True).get('/mobile/lost-turns', params={'ids': 'chat-lost-alone'}).status_code == 403
    assert refused == []


@pytest.mark.parametrize('ids', ['chat/../x', 'chat a', ',', ','.join(f'chat-{n}' for n in range(api.MAX_CHATS + 1))])
def test_a_bad_chat_list_is_refused(tmp_path, ids):
    db = _store(tmp_path / 'state.db', [])
    answer = _client(db).get('/mobile/lost-turns', params={'ids': ids})
    assert answer.status_code == (200 if ids == ',' else 400)
    if ids == ',':
        assert answer.json() == {'lost': {}}


def test_a_profile_without_a_store_holds_nothing_and_an_unreadable_one_says_so(tmp_path):
    assert _client(tmp_path / 'missing.db').get('/mobile/lost-turns', params={'ids': 'chat-1'}).json() == {'lost': {}}
    broken = tmp_path / 'broken.db'
    broken.write_bytes(b'not a database' * 64)
    assert _client(broken).get('/mobile/lost-turns', params={'ids': 'chat-1'}).status_code == 503


def test_a_bots_lost_reply_is_found_in_its_own_bot_chat(tmp_path):
    """Follow-up 4: a bot row has no chat id on the phone, so the route finds the bot's Bot Chat in the bot's own store the way Hermes does (its exact title)."""
    main = _store(tmp_path / 'main.db', [])
    bot_a = _store(tmp_path / 'bot-a.db', [('bot-a-chat', 'x1', 'unknown'), ('bot-a-chat', 'x2', 'queued'), ('bot-a-other', 'x3', 'unknown')],
                   titles=[('bot-a-chat', 'Bot Chat'), ('bot-a-other', 'Something else')])
    bot_b = _store(tmp_path / 'bot-b.db', [('bot-b-chat', 'y1', 'started')], titles=[('bot-b-chat', 'Bot Chat')])
    stores = {'synthetic-profile': main, 'bot-a': bot_a, 'bot-b': bot_b}
    before = {name: _digest(path) for name, path in stores.items()}
    answer = _client(main, stores=stores).get('/mobile/lost-turns', params={'bots': 'bot-a,bot-b'})
    assert answer.status_code == 200, answer.text
    assert answer.json() == {'lost': {}, 'bots': {'bot-a': {'session_id': 'bot-a-chat', 'waiting': 1,
                                                            'unknown': [{'admission_id': 'x1', 'execution_generation': 3}]}}}
    assert {name: _digest(path) for name, path in stores.items()} == before


def test_a_bot_the_phone_may_not_use_is_never_probed_and_costs_the_others_nothing(tmp_path):
    main = _store(tmp_path / 'main.db', [])
    bot_a = _store(tmp_path / 'bot-a.db', [('bot-a-chat', 'x1', 'unknown')], titles=[('bot-a-chat', 'Bot Chat')])
    asked = []
    answer = _client(main, asked, stores={'synthetic-profile': main, 'bot-a': bot_a}).get('/mobile/lost-turns', params={'bots': 'bot-a,someone-elses-bot'})
    assert answer.status_code == 200
    assert list(answer.json()['bots']) == ['bot-a']
    assert 'someone-elses-bot' not in asked


from test_mobile import harness  # noqa: E402,F401  (the real plugin_api.py, mounted on a synthetic dashboard)


def test_the_dashboard_mounts_the_route_for_the_owner_only(harness):
    _, client, _, home, _ = harness
    _store(home / 'state.db', [('chat-lost-alone', 'b1', 'unknown')])
    asked = {'profile': 'default', 'ids': 'chat-lost-alone'}
    answer = client.get('/api/plugins/beacon/mobile/lost-turns', params=asked)
    assert answer.status_code == 200, answer.text
    assert answer.json() == {'lost': {'chat-lost-alone': {'waiting': 0, 'unknown': [{'admission_id': 'b1', 'execution_generation': 3}]}}}
    client.headers['x-fixture-owner'] = 'someone-else'
    assert client.get('/api/plugins/beacon/mobile/lost-turns', params=asked).status_code == 403
