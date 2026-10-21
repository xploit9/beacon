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
