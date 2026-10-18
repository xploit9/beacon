from test_mobile import harness, reserve, audio


def test_old_clients_cannot_persist_audio_but_text_survives(harness):
    api,c,_,tmp,_=harness;cid=reserve(c);path=f'/api/plugins/cypher/mobile/calls/{cid}'
    assert c.post(path+'/audio',json=audio()).status_code==409
    event={'event_id':'caption','turn_id':'turn','type':'transcript','role':'user','text':'Keep my conversation','final':True,'at_ms':0}
    assert c.post(path+'/events',json={'events':[event]}).status_code==200
    result=c.post(path+'/finalize',json={'last_user_sequence':999,'last_assistant_sequence':999})
    assert result.status_code==200 and result.json()['audio_available'] is False
    assert result.json()['transcript_available'] is True
    assert result.json()['recording_status']=='disabled' and result.json()['transcript_status']=='saved'
    assert c.post(path+'/finalize',json={}).json()==result.json()
    assert api.mobile.voice_history('owner','default','native-stored')['events'][0]['text']=='Keep my conversation'
    with api.mobile.connect() as conn: assert conn.execute("SELECT name FROM sqlite_master WHERE name='chunks'").fetchone() is None
    assert not (tmp/'call-recordings').exists()


def test_audio_rejection_retains_owner_scope(harness):
    _,c,_,_,_=harness;cid=reserve(c)
    c.headers['x-fixture-owner']='foreign'
    assert c.post(f'/api/plugins/cypher/mobile/calls/{cid}/audio',json=audio()).status_code==403


def test_finalize_persists_explicit_missing_text_and_validates_flag(harness):
    api,c,_,_,_=harness;cid=reserve(c);path=f'/api/plugins/cypher/mobile/calls/{cid}/finalize'
    assert c.post(path,json={'incomplete':'true'}).status_code==409
    result=c.post(path,json={'incomplete':True})
    assert result.status_code==200
    assert result.json()['closed'] and not result.json()['complete']
    assert result.json()['incomplete'] and result.json()['transcript_status']=='partial'
    assert c.post(path,json={'incomplete':True}).json()==result.json()


def test_late_incomplete_receipt_can_only_downgrade_completeness(harness):
    _,c,_,_,_=harness;cid=reserve(c);path=f'/api/plugins/cypher/mobile/calls/{cid}/finalize'
    assert c.post(path,json={}).json()['complete'] is True
    result=c.post(path,json={'incomplete':True}).json()
    assert result['complete'] is False and result['transcript_status']=='partial'
    assert c.post(path,json={}).json()==result


def test_connection_and_lookup_diagnostics_are_bounded_without_private_payloads(harness):
    api,c,_,_,_=harness;cid=reserve(c);path=f'/api/plugins/cypher/mobile/calls/{cid}/events'
    diagnostic={'event_id':'connect','turn_id':'connection','type':'media_diagnostic','at_ms':100,'elapsed_ms':100,'stage':'provider_error','error_code':'tls','token':'must-not-persist'}
    assert c.post(path,json={'events':[diagnostic]}).status_code==200
    stored=api.mobile.voice_history('owner','default','native-stored')['events']
    # Diagnostics are stored for investigation but are not spoken dialogue.
    assert stored == []
    with api.mobile.connect() as db:
        payload=db.execute('SELECT payload FROM timeline WHERE call_id=?',(cid,)).fetchone()[0]
    assert 'must-not-persist' not in payload and 'tls' in payload
    assert c.post(path,json={'events':[dict(diagnostic,event_id='bad',error_code='Bearer private credential')]}).status_code==409


def test_redacted_history_row_does_not_erase_earlier_usable_context(harness, monkeypatch):
    import sys
    from contextlib import contextmanager
    from types import SimpleNamespace
    api, _, _, _, _ = harness
    rows = [{'role': 'user', 'content': 'remember the plan'}, {'role': 'assistant', 'content': 'private'}, {'role': 'user', 'content': 'continue'}]
    @contextmanager
    def db(params):
        yield SimpleNamespace(get_messages=lambda *args, **kwargs: rows)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=SimpleNamespace(_profile_db=db)))
    monkeypatch.setattr(api.voice_context.native, 'verify', lambda *args: None)
    monkeypatch.setattr(api.voice_context, 'private_text', lambda text, limit: '' if text == 'private' else text)
    assert [row['content'] for row in api.voice_context.history('owner', 'basic', 'default', 'native-stored')] == ['remember the plan', 'continue']
