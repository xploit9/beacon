import json
import pytest
from test_mobile import harness, reserve


def test_choice_is_scoped_and_call_binding_is_immutable(harness):
    api, client, cfg, _, _ = harness
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
    assert json.loads(before['voice_config'])['provider'] == 'gemini'
    assert json.loads(after['voice_config'])['provider'] == 'grok'
    assert client.put(base+'?profile=default', json={'provider':'gemini'}).status_code == 200
    assert json.loads(after['voice_config'])['provider'] == 'grok'
    client.headers['x-fixture-owner'] = 'other'
    assert client.get(base+'?profile=default').status_code == 403


def test_choice_rejects_unknown_and_overposting_but_accepts_phone_key_providers(harness):
    api, client, _, _, _ = harness
    base='/api/plugins/cypher/mobile/voice-settings'
    for value in ({'provider':'unknown'}, {'provider':'grok','key':'private'}, {'provider':[]}, {'provider':None}):
        assert client.put(base,json=value).status_code == 400
    assert client.put(base,json={'provider':'gemini'}).status_code == 200
    assert client.get(base).json()['selected_provider'] == 'gemini'


def test_voice_settings_lists_phone_key_providers_without_server_credentials(harness):
    _, client, _, _, _ = harness

    response = client.get('/api/plugins/cypher/mobile/voice-settings')

    assert response.status_code == 200, response.text
    providers = {item['id']: item for item in response.json()['providers']}
    assert set(providers) == {'gemini', 'grok', 'openai'}
    required = {'id', 'label', 'model', 'voice', 'voices', 'personality', 'personalities', 'vision', 'available', 'configured'}
    for provider in providers.values():
        assert required <= provider.keys()
        assert provider['available'] is True
        assert provider['configured'] is True
        assert 'configuration_error' not in provider


def test_explicit_call_override_does_not_replace_profile_preference(harness):
    api,c,cfg,_,_=harness
    base='/api/plugins/cypher/mobile'
    assert c.put(base+'/voice-settings',json={'provider':'grok'}).status_code==200
    result=c.post(base+'/calls',json={'profile':'default','voice_provider':'gemini','client_key':True})
    assert result.status_code==200 and result.json()['provider']=='gemini'
    assert c.get(base+'/voice-settings').json()['selected_provider']=='grok'
    assert c.post(base+'/calls',json={'profile':'default','voice_provider':'unknown','client_key':True}).status_code==400


def test_provider_options_apply_to_next_admission_and_handoff(harness):
    api,c,cfg,_,_=harness
    url='/api/plugins/cypher/mobile/voice-settings'
    first=reserve(c)
    assert c.put(url,json={'provider':'grok','voice':'ara','personality':'warm'}).status_code==200
    second=reserve(c)
    assert c.put(url,json={'provider':'gemini','voice':'Kore','personality':'concise'}).status_code==200
    handoff=c.post('/api/plugins/cypher/mobile/calls',json={'voice_provider':'gemini','client_key':True}).json()
    assert handoff['voice']=='Kore' and handoff['personality']=='concise'
    with api.mobile.connect() as conn:
        before=json.loads(api.mobile.owned(conn,first,'owner')['voice_config'])
        after=json.loads(api.mobile.owned(conn,second,'owner')['voice_config'])
    assert before['voice']=='Aoede' and before['personality']=='balanced'
    assert after['voice']=='ara' and after['personality']=='warm'
    assert 'warmly' in after['personality_instruction']
    assert c.put(url,json={'provider':'grok'}).status_code==200
    row=next(row for row in c.get(url).json()['providers'] if row['id']=='grok')
    assert row['voice']=='ara' and row['personality']=='warm'
    cfg['profiles'].append('academic')
    other=c.get(url+'?profile=academic').json()
    assert next(row for row in other['providers'] if row['id']=='grok')['voice']=='eve'


def test_options_reject_wrong_provider_and_malformed_values(harness):
    api,c,_,_,_=harness
    for value in ({'voice':'Aoede'}, {'voice':[]}, {'personality':[]}, {'personality':'invented'}, {'pitch':1}):
        result=c.put('/api/plugins/cypher/mobile/voice-settings',json={'provider':'grok',**value})
        assert result.status_code in (400,409),result.text

def test_malformed_voice_preference_does_not_break_provider_listing(harness):
    api,c,_,_,_=harness
    key=api.voice_models.options_key('owner','default','grok')
    with api.mobile.connect() as conn:conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,b'{'))
    response=c.get('/api/plugins/cypher/mobile/voice-settings')
    assert response.status_code==200
    grok=next(item for item in response.json()['providers'] if item['id']=='grok')
    assert grok['voice']=='eve' and grok['personality']=='balanced'

def test_voice_preference_only_overrides_valid_voice_and_personality(harness):
    api,c,cfg,_,_=harness
    key=api.voice_models.options_key('owner','default','grok')
    saved={'voice':'ara','personality':'warm','provider':'gemini','model':'attacker-model',
           'personality_instruction':'attacker','transport':'attacker','capabilities':{'vision':True}}
    with api.mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,json.dumps(saved)))
    selection=api.voice_models.provider_selection(api.mobile,'owner','default',cfg,'grok')
    assert selection['provider']=='grok' and selection['model']=='grok-voice-latest'
    assert selection['voice']=='ara' and selection['personality']=='warm'
    assert selection['personality_instruction']==api.voice_models.PERSONALITIES['warm']
    assert selection['transport']=='grok_live_direct' and selection['capabilities']=={'vision':False}

def test_invalid_stored_voice_options_fall_back_to_provider_defaults(harness):
    api,_,cfg,_,_=harness
    key=api.voice_models.options_key('owner','default','grok')
    saved={'voice':'Aoede','personality':'unknown'}
    with api.mobile.connect() as conn:
        conn.execute('INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)',(key,json.dumps(saved)))
    selection=api.voice_models.provider_selection(api.mobile,'owner','default',cfg,'grok')
    assert selection['voice']=='eve' and selection['personality']=='balanced'

def test_validate_options_rejects_unknown_provider(harness):
    api,_,_,_,_=harness
    with pytest.raises(ValueError,match='Unknown voice model'):
        api.voice_models.validate_options('unknown',{'voice':'arbitrary'})
