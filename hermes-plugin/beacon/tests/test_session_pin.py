from contextlib import contextmanager
from types import SimpleNamespace
import sys

import pytest
from test_mobile import harness


def pin_store(monkeypatch, *, owner='basic:owner', exists=True, available=True, changed=True):
    calls=[]
    class Store:
        def resolve_session_id(self, value):
            calls.append(('resolve',value))
            return 'stored-chat' if exists else None
        def get_session(self, key):
            return {'id':key,'user_id':owner}
        def set_session_pinned(self, key, pinned):
            calls.append(('pin',key,pinned))
            return changed
    @contextmanager
    def profile_db(params, *, writer=False):
        calls.append(('profile',params,writer))
        yield Store() if available else None
    monkeypatch.setitem(sys.modules,'tui_gateway',SimpleNamespace(server=SimpleNamespace(_profile_db=profile_db)))
    return calls


@pytest.mark.parametrize('pinned',[True,False])
def test_pin_uses_authorized_gateway_writer_and_canonical_session(harness,monkeypatch,pinned):
    _,client,cfg,_,_=harness
    cfg['profiles']=['default','work']
    calls=pin_store(monkeypatch)
    response=client.post('/api/plugins/beacon/sessions/pin',json={'session_id':'prefix','profile':'work','pinned':pinned})
    assert response.status_code==200,response.text
    assert response.json()=={'session_id':'stored-chat','pinned':pinned}
    assert calls==[('profile',{'profile':'work'},True),('resolve','prefix'),('pin','stored-chat',pinned)]


@pytest.mark.parametrize(('options','status'),[
    ({'exists':False},404),({'owner':'basic:foreign'},404),({'owner':None},404),
    ({'available':False},503),({'changed':False},404),
])
def test_pin_refuses_unknown_unowned_or_unavailable_session(harness,monkeypatch,options,status):
    _,client,_,_,_=harness
    calls=pin_store(monkeypatch,**options)
    response=client.post('/api/plugins/beacon/sessions/pin',json={'session_id':'stored-chat','profile':'default','pinned':True})
    assert response.status_code==status
    if options.get('changed',True):
        assert not any(call[0]=='pin' for call in calls)


def test_pin_auth_and_profile_checks_precede_database_access(harness,monkeypatch):
    _,client,_,_,_=harness
    calls=pin_store(monkeypatch)
    body={'session_id':'stored-chat','profile':'default','pinned':True}
    client.headers['x-fixture-owner']='foreign'
    assert client.post('/api/plugins/beacon/sessions/pin',json=body).status_code==403
    del client.headers['x-fixture-owner']
    assert client.post('/api/plugins/beacon/sessions/pin',json=body).status_code==401
    client.headers['x-fixture-owner']='owner'
    assert client.post('/api/plugins/beacon/sessions/pin',json={**body,'profile':'foreign'}).status_code==403
    assert not calls


@pytest.mark.parametrize('change',[
    {'pinned':1},{'pinned':'true'},{'session_id':'../chat'},{'session_id':''},
    {'session_id':None},{'extra':True},
])
def test_pin_rejects_invalid_fields_without_writing(harness,monkeypatch,change):
    _,client,_,_,_=harness
    calls=pin_store(monkeypatch)
    response=client.post('/api/plugins/beacon/sessions/pin',json={'session_id':'stored-chat','profile':'default','pinned':True,**change})
    assert response.status_code==400
    assert not calls


def test_pin_does_not_expose_database_error_paths(harness,monkeypatch):
    _,client,_,_,_=harness
    def broken(*args,**kwargs): raise RuntimeError('private profile path')
    monkeypatch.setitem(sys.modules,'tui_gateway',SimpleNamespace(server=SimpleNamespace(_profile_db=broken)))
    response=client.post('/api/plugins/beacon/sessions/pin',json={'session_id':'stored-chat','profile':'default','pinned':True})
    assert response.status_code==503
    assert response.json()=={'detail':'Conversation is unavailable. Try again.'}


def test_pin_accepts_the_phone_json_headers_device_and_profile_query(harness, monkeypatch):
    _, client, cfg, _, _ = harness
    cfg['profiles'] = ['default', 'work']
    calls = pin_store(monkeypatch)
    response = client.post('/api/plugins/beacon/sessions/pin?beacon_profile=work',
        content='{"session_id":"stored-chat","pinned":true,"profile":"work"}',
        headers={'content-type': 'application/json; charset=utf-8', 'accept': 'application/json',
                 'authorization': 'Bearer synthetic-fixture', 'x-beacon-device-id': 'paired-phone-01'})
    assert response.status_code == 200, response.text
    assert response.json() == {'session_id': 'stored-chat', 'pinned': True}
    assert calls == [('profile', {'profile': 'work'}, True), ('resolve', 'stored-chat'), ('pin', 'stored-chat', True)]
