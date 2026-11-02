"""Hot queries are served by an index, and the connection helpers stay cheap and leak-free."""
import ast
import re
import sqlite3
from pathlib import Path

import pytest

from test_mobile import harness

ROOT = Path(__file__).resolve().parents[1]
NEW_INDEXES = ('notices_owner_profile', 'notices_expires', 'notices_call_id', 'jobs_call_id',
               'jobs_owner_profile_status', 'call_log_call_id', 'call_log_email_notice', 'open_calls')


def plan(conn, sql, args=()):
    return [row['detail'] for row in conn.execute('EXPLAIN QUERY PLAN ' + sql, args)]


def assert_indexed(conn, table, sql, args=(), alias=None):
    """Every read of `table` names an index; a bare "SCAN table" reads the whole table."""
    details = plan(conn, sql, args)
    mine = [d for d in details if re.match(r'(SCAN|SEARCH) (%s)( |$)' % '|'.join(filter(None, (table, alias))), d)]
    assert mine, details
    assert all('USING' in d for d in mine), details


def test_query_plan_notices_filters_use_an_index(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        for sql, args in (
            ('SELECT * FROM notices WHERE owner=? AND profile=? AND seq>? ORDER BY seq LIMIT 100', ('o', 'p', 0)),
            ("SELECT * FROM notices WHERE owner=? AND profile=? AND push='pending' AND state='pending' AND expires>? ORDER BY seq LIMIT 64", ('o', 'p', 0)),
            ("SELECT COUNT(*) FROM notices WHERE owner=? AND profile=? AND push='sent'", ('o', 'p')),
            ("SELECT * FROM notices WHERE call_id=? AND owner=? AND profile=? AND state='answered'", ('c', 'o', 'p')),
            ("SELECT * FROM notices WHERE owner=? AND profile=? AND kind IN ('working','approval','clarify') AND activity_key!='' AND state='pending'", ('o', 'p')),
            ("SELECT id FROM notices WHERE owner=? AND profile=? AND conversation=? AND job_id=? AND kind='task_result' AND expires>? ORDER BY created DESC LIMIT 1", ('o', 'p', 'c', 'j', 0)),
            ("DELETE FROM notices WHERE expires<? AND NOT(activity_key!='' AND state='pending' AND kind IN ('working','approval','clarify'))", (0,)),
            ("SELECT json_object('id',n.id) FROM notices n WHERE n.call_id=? AND n.owner=? AND n.profile=? AND n.state='answered' ORDER BY n.created DESC LIMIT 1", ('c', 'o', 'p')),
        ):
            assert_indexed(conn, 'notices', sql, args, alias='n')


def test_query_plan_history_notice_lookup_uses_an_index(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        assert_indexed(conn, 'notices', """SELECT 1 FROM notices n
            WHERE n.owner=? AND n.profile=? AND n.kind='incoming_call' AND n.state='pending' AND n.expires>?
              AND (n.call_id=? OR n.job_id IN (SELECT id FROM jobs WHERE call_id=? AND owner=? AND profile=?)) LIMIT 1""",
                       ('o', 'p', 0, 'c', 'c', 'o', 'p'), alias='n')


def test_query_plan_jobs_filters_use_an_index(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        for sql, args in (
            ('SELECT * FROM jobs WHERE call_id=? AND owner=?', ('c', 'o')),
            ('SELECT COUNT(*) FROM jobs WHERE call_id=?', ('c',)),
            ('SELECT * FROM jobs WHERE call_id=? AND owner=? ORDER BY rowid DESC', ('c', 'o')),
            ("SELECT * FROM jobs WHERE owner=? AND profile=? AND ((epoch=? AND status IN ('watching','needs_input')) OR (status='completed' AND callback=1)) ORDER BY rowid LIMIT 512", ('o', 'p', 'e')),
            ("SELECT COUNT(*) FROM jobs WHERE owner=? AND profile=? AND epoch=? AND status IN ('watching','needs_input')", ('o', 'p', 'e')),
            ('SELECT 1 FROM jobs j WHERE j.call_id=? AND j.owner=? AND j.profile=?', ('c', 'o', 'p')),
        ):
            assert_indexed(conn, 'jobs', sql, args, alias='j')
        assert any('jobs_call_id' in d for d in plan(conn, 'SELECT * FROM jobs WHERE call_id=?', ('c',)))
        assert any('jobs_owner_profile_status' in d for d in plan(
            conn, "SELECT * FROM jobs WHERE owner=? AND profile=? AND status IN ('watching','needs_input')", ('o', 'p')))


def test_query_plan_call_log_lookups_use_an_index(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        for sql, args in (
            ('SELECT id,owner,profile,reason,feedback_verdict FROM call_log WHERE email_notice_id=?', ('n',)),
            ('SELECT 1 FROM call_log WHERE email_notice_id=?', ('n',)),
            ('SELECT feedback_verdict FROM call_log l WHERE l.call_id=? AND l.owner=? AND l.profile=? LIMIT 1', ('c', 'o', 'p')),
            ('SELECT 1 FROM call_log l WHERE l.call_id=?', ('c',)),
        ):
            assert_indexed(conn, 'call_log', sql, args, alias='l')


def test_query_plan_open_call_scans_read_only_open_calls(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        for sql, args in (
            ('SELECT COUNT(*) FROM calls WHERE owner=? AND closed=0 AND COALESCE(seen,created)>=?', ('o', 0)),
            ('SELECT COUNT(*) FROM calls WHERE closed=0 AND COALESCE(seen,created)>=?', (0,)),
            ("UPDATE calls SET closed=1,ended=COALESCE(ended,seen,created),status='failed' WHERE closed=0 AND COALESCE(seen,created)<?", (0,)),
        ):
            assert_indexed(conn, 'calls', sql, args)
            assert any('open_calls' in d for d in plan(conn, sql, args))


def test_query_plan_meta_prefix_scans_use_the_key_index(harness):
    api = harness[0]
    observers = api.sys.modules['beacon_observers']
    callbacks = api.sys.modules['beacon_callbacks']
    with api.mobile.connect() as conn:
        where, bounds = observers.prefix_range('email-evidence:', 'source-evidence:', 'mail-evidence:')
        assert_indexed(conn, 'meta', 'SELECT key,value FROM meta WHERE ' + where + ' ORDER BY key LIMIT ?', (*bounds, 256))
        where, bounds = observers.prefix_range('completion:')
        assert_indexed(conn, 'meta', 'SELECT key,value FROM meta WHERE ' + where, bounds)
        low, high = callbacks._MARKER_RANGE
        assert_indexed(conn, 'meta', """SELECT value FROM meta WHERE key>=? AND key<? AND json_valid(CAST(value AS TEXT))
            AND json_extract(CAST(value AS TEXT),'$.owner')=? ORDER BY rowid DESC LIMIT ?""", (low, high, 'o', 5))
        # The old shapes read every row: LIKE and substr() cannot use the key index.
        assert any(d.startswith('SCAN meta') and 'USING' not in d for d in plan(conn, "SELECT key,value FROM meta WHERE key LIKE 'completion:%'"))
        assert any(d.startswith('SCAN meta') and 'USING' not in d for d in plan(conn, 'SELECT key,value FROM meta WHERE substr(key,1,?)=?', (3, 'abc')))


def test_query_plan_delivered_marker_lookup_never_scans_meta(harness):
    api = harness[0]
    callbacks = api.sys.modules['beacon_callbacks']
    with api.mobile.connect() as conn:
        sql = """SELECT value FROM meta WHERE key=? UNION ALL SELECT value FROM meta WHERE key>=? AND key<? AND
            CASE WHEN json_valid(CAST(value AS TEXT)) THEN json_extract(CAST(value AS TEXT),'$.delivered')=? END LIMIT 1"""
        details = plan(conn, sql, ('scheduled-callback:' + 'a' * 64, *callbacks._MARKER_RANGE, 'a' * 32))
        assert [d for d in details if 'meta' in d] and all('USING' in d for d in details if 'meta' in d), details


@pytest.mark.parametrize('prefix', ['scheduled-callback:', 'completion:', 'proactive-call:owner:default:'])
def test_prefix_range_matches_exactly_the_keys_like_matched(harness, prefix):
    api = harness[0]
    observers = api.sys.modules['beacon_observers']
    keys = [prefix, prefix + 'a', prefix + 'zzz', prefix[:-1], prefix[:-1] + ';x', prefix[:-1] + '9', 'a' + prefix, prefix.upper() + 'x']
    where, bounds = observers.prefix_range(prefix)
    with api.mobile.connect() as conn:
        conn.executemany('INSERT INTO meta(key,value) VALUES(?,?)', [(key, b'1') for key in keys])
        ranged = {r[0] for r in conn.execute('SELECT key FROM meta WHERE ' + where, bounds)}
    assert ranged == {key for key in keys if key.startswith(prefix)}


def test_new_indexes_upgrade_an_existing_database_in_place(harness):
    api = harness[0]
    mobile = api.mobile
    with mobile.connect() as conn:
        for name in NEW_INDEXES: conn.execute('DROP INDEX ' + name)
        conn.execute("INSERT INTO meta(key,value) VALUES('keep-me',?)", (b'1',))
    mobile._SCHEMA_READY.clear()  # a fresh process meets the old database
    with mobile.connect() as conn:
        names = {r['name'] for r in conn.execute("SELECT name FROM sqlite_master WHERE type='index'")}
        assert set(NEW_INDEXES) <= names
        assert conn.execute("SELECT value FROM meta WHERE key='keep-me'").fetchone()[0] == b'1'


def test_connection_context_manager_closes_the_handle_and_commits(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO meta(key,value) VALUES('closed-check',?)", (b'1',))
    with pytest.raises(sqlite3.ProgrammingError):
        conn.execute('SELECT 1')
    with pytest.raises(RuntimeError):
        with api.mobile.connect() as failing:
            failing.execute("INSERT INTO meta(key,value) VALUES('rolled-back',?)", (b'1',))
            raise RuntimeError('caller failure')
    with pytest.raises(sqlite3.ProgrammingError):
        failing.execute('SELECT 1')
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM meta WHERE key='closed-check'").fetchone()[0] == 1
        assert conn.execute("SELECT COUNT(*) FROM meta WHERE key='rolled-back'").fetchone()[0] == 0


def test_connections_use_normal_synchronous_in_wal_mode(harness):
    api = harness[0]
    with api.mobile.connect() as conn:
        assert conn.execute('PRAGMA synchronous').fetchone()[0] == 1  # NORMAL
        assert conn.execute('PRAGMA journal_mode').fetchone()[0] == 'wal'
        assert conn.execute('PRAGMA foreign_keys').fetchone()[0] == 1


def test_private_folder_is_protected_once_not_on_every_connect(harness, monkeypatch):
    api = harness[0]
    mobile = api.mobile
    calls = []
    monkeypatch.setattr(mobile, 'protect', lambda path: calls.append(path) or path.mkdir(parents=True, exist_ok=True))
    mobile._ROOT_READY.clear()
    for _ in range(5):
        with mobile.connect():
            pass
    assert len(calls) == 1


def test_owner_lookup_subprocess_has_a_timeout():
    tree = ast.parse((ROOT / 'mobile.py').read_text(encoding='utf-8'))
    calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and isinstance(node.func, ast.Attribute)
             and node.func.attr == 'check_output']
    assert calls and all(any(keyword.arg == 'timeout' for keyword in call.keywords) for call in calls)
