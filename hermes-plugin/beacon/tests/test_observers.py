from test_callbacks import load,env
import time
from types import SimpleNamespace


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
