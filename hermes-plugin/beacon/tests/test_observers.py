from test_callbacks import load,env
import time
from types import SimpleNamespace
import sys

import pytest
from test_mobile import harness
from test_canonical_same_chat import canonical_call, subject


def test_stale_completion_metadata_is_pruned_without_touching_recent_entries(env,monkeypatch):
    _,mobile,_,cfg,_,_=env
    observer=load('observers')
    now=time.time()
    monkeypatch.setattr(observer,'time',SimpleNamespace(time=lambda:now))
    with mobile.connect() as conn:
        conn.execute('INSERT INTO meta VALUES(?,?)',('completion:owner:default:old:turn',str(now-86401).encode()))
        conn.execute('INSERT INTO meta VALUES(?,?)',('completion:owner:default:legacy:turn',b'notified'))
        conn.execute('INSERT INTO meta VALUES(?,?)',('completion:owner:default:recent:turn',str(now-60).encode()))
        conn.execute('INSERT INTO meta VALUES(?,?)',('mail-evidence:owner:default:old:message',b'legacy'))
        conn.execute('INSERT INTO meta VALUES(?,?)',('mail-notified:owner:default:old:message',b'legacy'))
        observer.prune_meta(conn,now)
        assert conn.execute("SELECT 1 FROM meta WHERE key='completion:owner:default:old:turn'").fetchone() is None
        assert float(conn.execute("SELECT value FROM meta WHERE key='completion:owner:default:legacy:turn'").fetchone()[0])==now
        assert float(conn.execute("SELECT value FROM meta WHERE key='completion:owner:default:recent:turn'").fetchone()[0])==now-60
        assert conn.execute("SELECT 1 FROM meta WHERE key LIKE 'mail-evidence:%' OR key LIKE 'mail-notified:%'").fetchone() is None


def test_a_stale_clock_reading_keeps_the_markers_a_faster_request_just_committed(env,monkeypatch):
    # Strix afd1b07 S1: the hourly Proactive call cap and the duplicate guard live in these markers.
    _,mobile,_,_,_,_=env
    observer=load('observers')
    now=time.time()
    monkeypatch.setattr(observer,'time',SimpleNamespace(time=lambda:now))
    stale=now-5  # this request read its clock before the other one committed
    with mobile.connect() as conn:
        conn.execute('INSERT INTO meta VALUES(?,?)',('proactive-call:owner:default:fresh',str(now-1).encode()))
        conn.execute('INSERT INTO meta VALUES(?,?)',('proactive-alert:owner:default:fresh',str(now-1).encode()))
        conn.execute('INSERT INTO meta VALUES(?,?)',('proactive-alert:owner:default:corrupt',str(now+3600).encode()))
        observer.prune_meta(conn,stale)
        kept={row[0] for row in conn.execute("SELECT key FROM meta WHERE key LIKE 'proactive-%'")}
    assert kept=={'proactive-call:owner:default:fresh','proactive-alert:owner:default:fresh'}


@pytest.mark.parametrize('value', [{'completed': False}, {'completed': True, 'interrupted': True},
                                  {'completed': True, 'turn_id': None}])
def test_completion_does_not_authorize_a_start_or_incomplete_boundary(harness, monkeypatch, value):
    api, _, cfg, _, _ = harness
    monkeypatch.setattr(api.native, 'current_principal', lambda *args: pytest.fail('Nonterminal hook sought authorization'))
    observer = sys.modules['beacon_observers']
    observer.completion(api.mobile, api.native, cfg, 'default', 'same-chat', **dict({'turn_id': 'turn'}, **value))
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] == 0


@pytest.mark.parametrize('verified,supplied', [(None, subject()), (subject('https://other.example'), subject())])
def test_completion_never_uses_hook_kwargs_or_a_different_proven_subject(canonical_call, monkeypatch, verified, supplied):
    api, _, cfg, _, _, _, _ = canonical_call
    monkeypatch.setattr(api.native, 'current_principal', lambda *args: verified)
    observer = sys.modules['beacon_observers']
    with pytest.raises(LookupError):
        observer.completion(api.mobile, api.native, cfg, 'default', 'same-chat', turn_id='turn', completed=True, principal=supplied)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] == 0
