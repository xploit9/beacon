"""An existing owned draft may be persisted for a call; unrelated sessions may not."""
from contextlib import contextmanager
import importlib.util
from pathlib import Path
import sys
import threading
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location('draft_native', Path(__file__).parents[1] / 'native.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


@pytest.fixture
def state(monkeypatch):
    rows = {}
    record = {'session_key': 'stored', 'profile_home': '/profiles/academic', 'user_id': 'basic:owner'}
    @contextmanager
    def profile_db(params):
        assert params['profile'] == 'academic'
        yield SimpleNamespace(get_session=rows.get)
    writes = []
    def ensure(session):
        writes.append(session)
        rows[session['session_key']] = {'user_id': session['user_id']}
        return True
    server = SimpleNamespace(_sessions={'runtime': record}, _sessions_lock=threading.RLock(),
        _profile_db=profile_db, _profile_home=lambda profile: Path('/profiles') / profile,
        _session_auth_user_id=lambda session: session.get('user_id'), _ensure_session_db_row=ensure)
    record['profile_home'] = str(Path('/profiles/academic'))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    return server, rows, record, writes


def test_existing_owned_draft_keeps_both_ids_and_is_idempotent(state):
    server, rows, record, writes = state
    assert native.reserve_conversation('owner', 'basic', 'academic', 'stored')['user_id'] == 'basic:owner'
    assert native.reserve_conversation('owner', 'basic', 'academic', 'stored')['user_id'] == 'basic:owner'
    assert writes == [record] and server._sessions == {'runtime': record}


@pytest.mark.parametrize('change', [{'user_id': 'basic:other'}, {'profile_home': '/other'}, {'_finalized': True}, {'session_key': 'other'}])
def test_foreign_missing_or_closed_draft_is_never_adopted(state, change):
    _, rows, record, writes = state
    record.update(change)
    with pytest.raises(LookupError): native.reserve_conversation('owner', 'basic', 'academic', 'stored')
    assert not writes and not rows


def test_existing_foreign_row_cannot_be_replaced_by_owned_runtime(state):
    _, rows, _, writes = state
    rows['stored'] = {'user_id': 'basic:other'}
    with pytest.raises(LookupError): native.reserve_conversation('owner', 'basic', 'academic', 'stored')
    assert not writes and rows['stored']['user_id'] == 'basic:other'


def test_replaced_runtime_cannot_confirm_a_reservation(state):
    server, _, _, writes = state
    original = server._ensure_session_db_row
    def replace(record):
        original(record)
        server._sessions['runtime'] = dict(record)
        return True
    server._ensure_session_db_row = replace
    with pytest.raises(LookupError): native.reserve_conversation('owner', 'basic', 'academic', 'stored')
    assert len(writes) == 1
