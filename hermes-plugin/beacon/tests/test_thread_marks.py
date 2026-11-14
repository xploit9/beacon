"""A thread made on a Hermes with session authority cannot carry the `android_thread` source (Hermes refuses any source that is not one of
its own surfaces: gateway/session_policy.py SURFACES), so the plugin keeps the thread marker itself, in its own store."""
import asyncio
from contextlib import contextmanager
import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)
from test_thread_posts import GONE, envelope, notices, poster  # noqa: F401  (fixtures)

BASE = '/api/plugins/beacon/mobile/threads'
MARKED = '20261111_090000_marked'
OTHER = '20261111_090500_plain'


def mark_url(stored=MARKED):
    return '/api/plugins/beacon/mobile/sessions/' + stored + '/thread'


def authority_row(stored, title='Cybersecurity news'):
    """What Hermes stores for a chat its authority created: source local, the creating surface in created_source."""
    return {'id': stored, 'title': title, 'source': 'local', 'created_source': 'cli'}


def marks(api):
    with api.mobile.connect() as conn:
        tables = {row[0] for row in conn.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if 'thread_marks' not in tables:
            return []
        return sorted((row['owner'], row['profile'], row['session']) for row in conn.execute('SELECT owner,profile,session FROM thread_marks'))


@pytest.fixture
def listing(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    rows = [authority_row(MARKED), authority_row(OTHER, 'A plain chat')]
    monkeypatch.setattr(api.native, 'conversation_list', lambda *args: {'sessions': list(rows)})
    return SimpleNamespace(api=api, client=client, rows=rows)


def test_a_chat_the_app_marks_is_listed_as_a_thread(listing):
    assert listing.client.get(BASE).json() == {'threads': []}

    marked = listing.client.post(mark_url())

    assert marked.status_code == 200
    assert marked.json() == {'thread': True, 'stored_session_id': MARKED, 'profile': 'default'}
    assert listing.client.get(BASE).json() == {'threads': [{'id': MARKED, 'title': 'Cybersecurity news'}]}
    assert marks(listing.api) == [('owner', 'default', MARKED)]


def test_marking_twice_is_one_mark_and_a_legacy_thread_is_listed_once(listing):
    listing.rows[0]['source'] = 'android_thread'  # marked and source-based at once

    assert listing.client.post(mark_url()).status_code == 200
    assert listing.client.post(mark_url()).status_code == 200

    assert marks(listing.api) == [('owner', 'default', MARKED)]
    assert listing.client.get(BASE).json() == {'threads': [{'id': MARKED, 'title': 'Cybersecurity news'}]}


def test_a_chat_the_caller_cannot_see_is_never_marked(listing, monkeypatch):
    def verify(owner, provider, profile, stored, *args):
        raise LookupError('Native stored conversation not owned')

    monkeypatch.setattr(listing.api.native, 'verify', verify)

    assert listing.client.post(mark_url('foreign')).status_code == 404
    assert marks(listing.api) == []


def test_the_mark_route_is_owner_scoped_and_checks_the_id(listing):
    assert listing.client.post(mark_url(), headers={'x-fixture-owner': 'foreign'}).status_code == 403
    assert listing.client.post(mark_url('x' * 257)).status_code in (400, 404)
    assert listing.client.post(mark_url('a b')).status_code in (400, 404)
    assert marks(listing.api) == []


def test_a_mark_is_per_owner_and_profile(listing):
    assert listing.client.post(mark_url()).status_code == 200
    native = listing.api.native

    assert [row['id'] for row in native.threads('owner', 'basic', 'default')] == [MARKED]
    assert native.threads('owner', 'basic', 'work') == []
    assert native.threads('someone', 'basic', 'default') == []


@contextmanager
def _db(sessions):
    yield SimpleNamespace(get_session=lambda stored: sessions.get(stored))


def test_a_deleted_chat_loses_its_mark(listing, monkeypatch):
    sessions = {}
    server = sys.modules['tui_gateway'].server
    monkeypatch.setattr(server, '_profile_db', lambda params, **kwargs: _db(sessions), raising=False)
    assert listing.client.post(mark_url()).status_code == 200
    listing.rows[:] = [authority_row(OTHER, 'A plain chat')]  # Hermes no longer lists it, and holds no row for it

    assert listing.client.get(BASE).json() == {'threads': []}
    assert marks(listing.api) == []


def test_a_hidden_or_archived_chat_keeps_its_mark(listing, monkeypatch):
    sessions = {MARKED: {'id': MARKED}}  # still in Hermes, only not in the open list
    server = sys.modules['tui_gateway'].server
    monkeypatch.setattr(server, '_profile_db', lambda params, **kwargs: _db(sessions), raising=False)
    assert listing.client.post(mark_url()).status_code == 200
    listing.rows[:] = [authority_row(OTHER, 'A plain chat')]

    assert listing.client.get(BASE).json() == {'threads': []}
    assert marks(listing.api) == [('owner', 'default', MARKED)]
    listing.rows[:] = [authority_row(MARKED)]
    assert listing.client.get(BASE).json() == {'threads': [{'id': MARKED, 'title': 'Cybersecurity news'}]}


def test_a_scheduled_post_reaches_a_marked_thread(poster, monkeypatch):  # noqa: F811
    monkeypatch.setattr(poster.api.native, 'conversation_list', lambda *args: {'sessions': [authority_row(MARKED)]})
    assert poster.client.post(mark_url()).status_code == 200

    sent = asyncio.run(poster.phone.standalone_send({}, MARKED, envelope()))

    assert sent['success'] is True
    (owner, provider, profile, stored, key, text, name), = poster.posts
    assert (owner, provider, profile, stored, text, name) == ('owner', 'basic', 'default', MARKED, 'Three new advisories.', 'Cybersecurity news')
    assert [row['conversation'] for row in notices(poster.api)] == [MARKED]
    assert GONE not in str(sent)
