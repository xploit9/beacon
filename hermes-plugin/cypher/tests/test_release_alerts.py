import json
import sys
from types import SimpleNamespace
import pytest
from test_mobile import harness


def configured(harness,monkeypatch):
    api,c,cfg,tmp,app=harness
    cfg['release_root']=str(tmp/'releases');(tmp/'releases').mkdir()
    cfg['firebase_credentials']='private-fixture'
    manifest=tmp/'releases/latest.json';manifest.write_text(json.dumps({'version_code':28}))
    calls=[]
    def verified(config): calls.append(True);return {'version_code':28,'sha256':'a'*64}
    monkeypatch.setattr(api,'release',verified)
    sent=[];failure=[False]
    def send(message,app=None):
        if failure[0]:raise RuntimeError('temporary provider error')
        sent.append(message)
    messaging=SimpleNamespace(Message=lambda **kw:kw,AndroidConfig=lambda **kw:kw,send=send)
    monkeypatch.setitem(sys.modules,'firebase_admin',SimpleNamespace(get_app=lambda name:object(),credentials=SimpleNamespace(),messaging=messaging))
    return api,c,cfg,manifest,calls,sent,failure


def register(c,id,version=0):
    result=c.post('/api/plugins/cypher/mobile/notifications/targets',json={'id':id,'token':'fixture-'+id+'-token-'+'x'*30,'app_version_code':version})
    assert result.status_code==200


def test_publication_announces_once_retries_and_sends_no_download_credentials(harness,monkeypatch):
    api,c,cfg,manifest,checks,sent,failed=configured(harness,monkeypatch)
    register(c,'old',27);register(c,'current',28)
    api.announce_release(cfg);api.announce_release(cfg)
    assert len(checks)==1
    with api.mobile.connect() as db:
        rows=db.execute("SELECT * FROM notices WHERE kind='release'").fetchall()
        assert len(rows)==1 and rows[0]['release_version']==28
    failed[0]=True;api.push('owner','default');assert sent==[]
    with api.mobile.connect() as db:db.execute('UPDATE notice_deliveries SET next_attempt=0')
    failed[0]=False;api.push('owner','default')
    assert len(sent)==2
    payload=sent[0]['data'];assert payload['kind']=='release' and payload['release_version']=='28'
    assert len(payload['source_ref'])==len(payload['conversation_ref'])==64
    assert not any(k in payload for k in ('url','download_url','password','token','cookie'))
    assert sent[0]['android']['priority']=='high'
    api.announce_release(cfg);api.push('owner','default');assert len(sent)==2
    # Restart/pruning never reannounces the same or older version.
    with api.mobile.connect() as db:db.execute("DELETE FROM notices WHERE kind='release'")
    api.announce_release(cfg);assert len(checks)==1
    manifest.write_text(json.dumps({'version_code':27}));api.announce_release(cfg);assert len(checks)==1


def test_invalid_signed_release_never_queues_or_advances_marker(harness,monkeypatch):
    api,c,cfg,_,_,_,_=configured(harness,monkeypatch);register(c,'old')
    def invalid(cfg):raise ValueError('signature rejected')
    monkeypatch.setattr(api,'release',invalid)
    with pytest.raises(ValueError):api.announce_release(cfg)
    with api.mobile.connect() as db:
        assert db.execute("SELECT COUNT(*) FROM notices WHERE kind='release'").fetchone()[0]==0
        assert db.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'release-announced:%'").fetchone()[0]==0


def test_failed_release_verification_is_not_repeated_for_unchanged_manifest(harness,monkeypatch):
    api,c,cfg,manifest,checks,sent,_=configured(harness,monkeypatch);register(c,'old')
    attempts=[]
    def invalid(cfg):
        attempts.append(True)
        raise ValueError('signature rejected')
    monkeypatch.setattr(api,'release',invalid)

    with pytest.raises(ValueError): api.announce_release(cfg)
    api.announce_release(cfg)
    assert len(attempts)==1

    manifest.write_text(json.dumps({'version_code':29}))
    with pytest.raises(ValueError): api.announce_release(cfg)
    assert len(attempts)==2


def test_new_version_and_profile_are_separate(harness,monkeypatch):
    api,c,cfg,manifest,checks,sent,_=configured(harness,monkeypatch)
    register(c,'legacy')
    api.announce_release(cfg);api.push('owner','default')
    assert len(sent)==1
    manifest.write_text(json.dumps({'version_code':29}))
    monkeypatch.setattr(api,'release',lambda cfg:{'version_code':29,'sha256':'b'*64})
    api.announce_release(cfg);api.push('owner','default');assert len(sent)==2 and sent[-1]['data']['release_version']=='29'
    cfg['profiles'].append('academic')
    assert c.post('/api/plugins/cypher/mobile/notifications/targets?profile=academic',json={'id':'academic-device','token':'fixture-token-'+'x'*30}).status_code==200
    api.announce_release(cfg);api.push('owner','academic')
    assert len(sent)==3 and sent[-1]['data']['profile']=='academic'


def test_profile_without_phone_does_not_trigger_repeated_apk_verification(harness,monkeypatch):
    api,c,cfg,manifest,checks,sent,_=configured(harness,monkeypatch)
    register(c,'phone');cfg['profiles'].append('academic')
    api.announce_release(cfg);api.announce_release(cfg)
    assert len(checks)==1
