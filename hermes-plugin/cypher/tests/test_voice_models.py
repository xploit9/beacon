import json
import time
from types import SimpleNamespace
import pytest
from test_mobile import harness, reserve


def ready(api, monkeypatch):
    monkeypatch.setattr(api, 'secret', lambda cfg: 'google-private-fixture')
    monkeypatch.setattr(api.voice_models, 'xai_secret', lambda cfg: 'xai-private-fixture')
    monkeypatch.setattr(api.voice_models.importlib.util, 'find_spec', lambda name: SimpleNamespace())


def test_choice_is_scoped_and_call_binding_is_immutable(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    ready(api, monkeypatch)
    cfg['profiles'].append('academic')
    base = '/api/plugins/cypher/mobile/voice-settings'
    first = reserve(client)
    assert client.put(base+'?profile=default', json={'provider':'grok'}).status_code == 200
    assert client.get(base+'?profile=default').json()['selected_provider'] == 'grok'
    assert client.get(base+'?profile=academic').json()['selected_provider'] == 'gemini'
    second = reserve(client)
    with api.mobile.connect() as conn:
        before = api.mobile.owned(conn, first, 'owner')
        after = api.mobile.owned(conn, second, 'owner')
    assert api.voice_models.bound(before,cfg)['provider'] == 'gemini'
    assert api.voice_models.bound(after,cfg)['provider'] == 'grok'
    assert client.put(base+'?profile=default', json={'provider':'gemini'}).status_code == 200
    assert api.voice_models.bound(after,cfg)['provider'] == 'grok'
    assert api.voice_models.bound({'voice_config':'{}'},cfg)['provider'] == 'gemini'
    client.headers['x-fixture-owner'] = 'other'
    assert client.get(base+'?profile=default').status_code == 403


def test_choice_rejects_unknown_unconfigured_and_overposting(harness):
    api, client, _, _, _ = harness
    base='/api/plugins/cypher/mobile/voice-settings'
    for value in ({'provider':'unknown'}, {'provider':'grok','key':'private'}, {'provider':[]}, {'provider':None}):
        assert client.put(base,json=value).status_code == 400
    assert client.put(base,json={'provider':'gemini'}).status_code == 409
    assert client.get(base).json()['selected_provider'] == 'gemini'


def test_xai_token_is_ephemeral_fixed_endpoint_and_not_cached(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    ready(api,monkeypatch)
    import httpx
    seen={}
    class Response:
        content=b'fixture'
        def raise_for_status(self): pass
        def json(self): return {'value':'ephemeral-fixture','expires_at':int(time.time())+300}
    class Client:
        def __init__(self, **kwargs): seen['options']=kwargs
        def __enter__(self): return self
        def __exit__(self,*args): pass
        def post(self,url,**kwargs): seen.update(url=url,**kwargs);return Response()
    monkeypatch.setattr(httpx,'Client',Client)
    assert client.put('/api/plugins/cypher/mobile/voice-settings',json={'provider':'grok'}).status_code==200
    call=reserve(client)
    result=client.post(f'/api/plugins/cypher/mobile/calls/{call}/token',json={})
    assert result.status_code==200 and result.headers['cache-control']=='no-store'
    assert result.json()['token']=='ephemeral-fixture' and result.json()['provider']=='grok'
    assert result.json()['capabilities']['vision'] is False
    assert 'xai-private-fixture' not in result.text
    assert seen['url']=='https://api.x.ai/v1/realtime/client_secrets'
    assert seen['json']=={'expires_after':{'seconds':300}}
    assert seen['options']['follow_redirects'] is False
    assert client.delete(f'/api/plugins/cypher/mobile/calls/{call}').status_code==200
    assert client.post(f'/api/plugins/cypher/mobile/calls/{call}/token',json={}).status_code==409


def test_provider_failure_does_not_expose_key(harness,monkeypatch):
    api, client, _, _, _=harness
    ready(api,monkeypatch)
    client.put('/api/plugins/cypher/mobile/voice-settings',json={'provider':'grok'})
    call=reserve(client)
    def fail(*args): raise RuntimeError('xai-secret-should-never-leak')
    monkeypatch.setattr(api.voice_models,'mint_xai',fail)
    response=client.post(f'/api/plugins/cypher/mobile/calls/{call}/token',json={})
    assert response.status_code==503 and 'xai-secret' not in response.text


def test_raw_private_key_file_is_bounded_and_not_public(harness):
    api,client,cfg,tmp,_=harness
    path=tmp/'key.txt';path.write_text('xai-'+'s'*80,encoding='utf-8');cfg['xai_credentials_file']=str(path)
    assert api.voice_models.xai_secret(cfg)=='xai-'+'s'*80
    assert ('s'*80) not in client.get('/api/plugins/cypher/mobile/voice-settings').text
    path.write_text('x'*16385,encoding='utf-8')
    with pytest.raises(ValueError): api.voice_models.xai_secret(cfg)


def test_explicit_call_override_does_not_replace_profile_preference(harness,monkeypatch):
    api,c,cfg,_,_=harness;ready(api,monkeypatch)
    base='/api/plugins/cypher/mobile'
    assert c.put(base+'/voice-settings',json={'provider':'grok'}).status_code==200
    result=c.post(base+'/calls',json={'profile':'default','voice_provider':'gemini'})
    assert result.status_code==200 and result.json()['provider']=='gemini'
    assert c.get(base+'/voice-settings').json()['selected_provider']=='grok'
    assert c.post(base+'/calls',json={'profile':'default','voice_provider':'unknown'}).status_code==400
    monkeypatch.setattr(api,'secret',lambda cfg:None)
    assert c.post(base+'/calls',json={'profile':'default','voice_provider':'gemini'}).status_code==409


def test_provider_options_apply_to_next_admission_and_handoff(harness,monkeypatch):
    api,c,cfg,_,_=harness;ready(api,monkeypatch)
    url='/api/plugins/cypher/mobile/voice-settings'
    first=reserve(c)
    assert c.put(url,json={'provider':'grok','voice':'ara','personality':'warm'}).status_code==200
    second=reserve(c)
    assert c.put(url,json={'provider':'gemini','voice':'Kore','personality':'concise'}).status_code==200
    handoff=c.post('/api/plugins/cypher/mobile/calls',json={'voice_provider':'gemini'}).json()
    assert handoff['voice']=='Kore' and handoff['personality']=='concise'
    with api.mobile.connect() as conn:
        before=api.voice_models.bound(api.mobile.owned(conn,first,'owner'),cfg)
        after=api.voice_models.bound(api.mobile.owned(conn,second,'owner'),cfg)
    assert before['voice']=='Aoede' and before['personality']=='balanced'
    assert after['voice']=='ara' and after['personality']=='warm'
    assert 'warmly' in after['personality_instruction']
    monkeypatch.setattr(api.voice_models,'mint_xai',lambda cfg,selection:{**selection,'token':'ephemeral-fixture'})
    grant=c.post(f'/api/plugins/cypher/mobile/calls/{second}/token',json={}).json()
    assert grant['voice']=='ara' and grant['personality']=='warm' and 'warmly' in grant['personality_instruction']
    assert c.put(url,json={'provider':'grok'}).status_code==200
    row=next(row for row in c.get(url).json()['providers'] if row['id']=='grok')
    assert row['voice']=='ara' and row['personality']=='warm'
    cfg['profiles'].append('academic')
    other=c.get(url+'?profile=academic').json()
    assert next(row for row in other['providers'] if row['id']=='grok')['voice']=='eve'


def test_options_reject_wrong_provider_and_malformed_values(harness,monkeypatch):
    api,c,_,_,_=harness;ready(api,monkeypatch)
    for value in ({'voice':'Aoede'}, {'voice':[]}, {'personality':[]}, {'personality':'invented'}, {'pitch':1}):
        result=c.put('/api/plugins/cypher/mobile/voice-settings',json={'provider':'grok',**value})
        assert result.status_code in (400,409),result.text
