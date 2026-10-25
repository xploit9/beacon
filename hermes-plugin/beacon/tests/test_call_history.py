import json
import time

import pytest

from test_mobile import harness


PATH = '/api/plugins/beacon/mobile/calls'


def history(client, **params):
    response = client.get(PATH, params=params)
    assert response.status_code == 200, response.text
    return response.json()


def add_call(api, owner='owner', profile='default', conversation='chat', created=None,
             closed=0, status='pending', provider='gemini', callback=None, transcript=False):
    call_id = api.mobile.reserve(owner, profile, conversation, {'provider': provider})['call_id']
    with api.mobile.connect() as conn:
        conn.execute('UPDATE calls SET created=?,closed=?,status=?,callback_context=? WHERE id=?',
                     (time.time() if created is None else created, closed, status,
                      json.dumps(callback or {}), call_id))
        if transcript:
            payload = json.dumps({'type': 'transcript', 'role': 'user', 'text': 'Saved call transcript'})
            conn.execute('INSERT INTO timeline(call_id,event_id,digest,payload) VALUES(?,?,?,?)',
                         (call_id, call_id + '-transcript', 'fixture', payload))
    return call_id


def add_invitation(api, conversation, reason, created, state='pending', expires=None):
    invitation_id = api.mobile.invitation('owner', 'default', conversation, reason)
    with api.mobile.connect() as conn:
        conn.execute('UPDATE notices SET created=?,state=?,expires=? WHERE id=?',
                     (created, state, time.time() + 120 if expires is None else expires, invitation_id))
    return invitation_id


def test_call_history_is_scoped_to_authenticated_owner_and_profile(harness):
    api, client, cfg, _, _ = harness
    cfg['profiles'] = ['default', 'other']
    own = add_call(api, conversation='own-chat')
    other_profile = add_call(api, profile='other', conversation='other-chat')
    foreign_owner = add_call(api, owner='foreign', conversation='foreign-chat')

    assert [row['id'] for row in history(client, profile='default')['calls']] == [own]
    assert [row['id'] for row in history(client, profile='other')['calls']] == [other_profile]
    active = history(client, profile='default')['calls'][0]
    assert active['state'] == 'active' and active['provider'] == 'gemini'
    assert active['duration_s'] is None and active['has_transcript'] is False
    client.headers['x-fixture-owner'] = 'foreign'
    assert client.get(PATH, params={'profile': 'default'}).status_code == 403
    client.headers['x-fixture-owner'] = 'owner'
    assert foreign_owner not in {row['id'] for row in history(client, profile='default')['calls']}


def test_call_history_applies_retention_cutoff(harness):
    api, client, _, _, _ = harness
    assert client.put('/api/plugins/beacon/mobile/history-retention', json={'history_retention_days': 30}).status_code == 200
    old = add_call(api, conversation='old-chat', created=time.time() - 31 * 86400, closed=1, status='saved')
    recent = add_call(api, conversation='recent-chat', closed=1, status='saved')

    rows = history(client)['calls']
    assert [row['id'] for row in rows] == [recent]
    assert old not in {row['id'] for row in rows}


def test_call_history_distinguishes_missed_and_answered_incoming_calls(harness):
    api, client, _, _, _ = harness
    now = time.time()
    missed_notice = add_invitation(api, 'missed-chat', 'No answer', now - 20)
    answered_notice = add_invitation(api, 'answered-chat', 'Please call back', now - 10)
    answered_call = add_call(api, conversation='answered-chat', created=now - 9, closed=1,
                             status='saved', transcript=True)
    with api.mobile.connect() as conn:
        conn.execute('UPDATE notices SET expires=? WHERE id=?', (now - 1, missed_notice))
        conn.execute("UPDATE notices SET state='answered',call_id=? WHERE id=?", (answered_call, answered_notice))

    rows = {row['id']: row for row in history(client)['calls']}
    assert missed_notice in rows and rows[missed_notice]['direction'] == 'incoming'
    assert rows[missed_notice]['state'] == 'missed' and not rows[missed_notice]['has_transcript']
    assert rows[answered_call]['direction'] == 'incoming' and rows[answered_call]['state'] == 'completed'
    assert rows[answered_call]['has_transcript'] and rows[answered_call]['duration_s'] is None
    assert rows[answered_call]['reason'] == 'Please call back'
    assert answered_notice not in rows


def test_answered_email_call_hides_feedback_id_only_after_it_is_saved(harness):
    api,client,_,_,_=harness
    notice_id=api.mobile.invitation('owner','default','email-chat','Important email')
    call_id=add_call(api,conversation='email-chat',closed=1,status='saved',
                     callback={'id':notice_id,'reason':'Important email'})
    with api.mobile.connect() as conn:
        conn.execute("UPDATE notices SET state='answered',call_id=? WHERE id=?",(call_id,notice_id))
        conn.execute('UPDATE call_log SET call_id=?,email_notice_id=? WHERE id=?',
                     (call_id,notice_id,notice_id))

    row=next(row for row in history(client)['calls'] if row['id']==call_id)
    assert row['direction']=='incoming' and row['email_notice_id']==notice_id
    with api.mobile.connect() as conn:
        conn.execute("UPDATE call_log SET feedback_verdict='important' WHERE id=?",(notice_id,))

    row=next(row for row in history(client)['calls'] if row['id']==call_id)
    assert 'email_notice_id' not in row


def test_incoming_outcomes_survive_notice_pruning(harness):
    api, client, _, _, _ = harness
    now = time.time()
    missed = add_invitation(api, 'missed-chat', 'No answer', now - 180, expires=now - 60)
    declined = add_invitation(api, 'declined-chat', 'Not now', now - 180, state='declined', expires=now - 60)
    with api.mobile.connect() as conn:
        api.mobile.prune_notices(conn)
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE id IN (?,?)', (missed, declined)).fetchone()[0] == 0
        outcomes = {row['id']: row['state'] for row in conn.execute(
            'SELECT id,state FROM call_log WHERE id IN (?,?)', (missed, declined))}

    assert outcomes == {missed: 'missed', declined: 'declined'}
    rows = {row['id']: row for row in history(client)['calls']}
    assert rows[missed]['state'] == 'missed'
    assert rows[declined]['state'] == 'declined'


def test_missed_invitation_log_obeys_history_retention(harness):
    api, client, _, _, _ = harness
    now = time.time()
    old = add_invitation(api, 'old-missed-chat', 'No answer', now - 31 * 86400,
                         expires=now - 31 * 86400 + 120)
    with api.mobile.connect() as conn:
        api.mobile.prune_notices(conn)
        assert conn.execute('SELECT state FROM call_log WHERE id=?', (old,)).fetchone()['state'] == 'missed'

    assert api.mobile.set_history_retention_days('owner', 30) == 30
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT 1 FROM call_log WHERE id=?', (old,)).fetchone() is None
    assert old not in {row['id'] for row in history(client)['calls']}


def test_call_history_duration_is_recorded_when_call_is_closed(harness):
    api, client, _, _, _ = harness
    call_id = add_call(api, created=time.time() - 4)

    response = client.delete(f'{PATH}/{call_id}')
    assert response.status_code == 200, response.text
    row = next(row for row in history(client)['calls'] if row['id'] == call_id)
    assert row['duration_s'] >= 3


def test_call_history_duration_is_recorded_when_transcript_finalizes(harness):
    api, client, _, _, _ = harness
    call_id = add_call(api, created=time.time() - 4)

    api.mobile.finalize(call_id, 'owner', {'incomplete': False})

    row = next(row for row in history(client)['calls'] if row['id'] == call_id)
    assert row['duration_s'] >= 3


@pytest.mark.parametrize('incomplete',[False,True])
@pytest.mark.parametrize('close_first',[False,True])
def test_never_connected_failure_survives_finalize_close_and_history(harness,incomplete,close_first):
    api,client,_,_,_=harness
    call_id=add_call(api)
    path=f'{PATH}/{call_id}'
    if close_first:
        assert client.delete(path).status_code==200
    body={'incomplete':incomplete,'failed':True,'error':'Synthetic provider response must not be retained'}
    finalized=client.post(path+'/finalize',json=body)
    assert finalized.status_code==200,finalized.text
    receipt=finalized.json()
    # An empty, never-connected call can have a complete text archive and still have failed.
    assert receipt.get('failed') is True and receipt['complete'] is (not incomplete)
    assert receipt['transcript_status']==('partial' if incomplete else 'saved')
    assert receipt['transcript_events']==0 and receipt['unfinalized_transcripts']==0
    assert receipt['audio_available'] is False
    for late_body in (body,{}, {'failed':False}, {'failed':False,'incomplete':True}):
        duplicate=client.post(path+'/finalize',json=late_body)
        assert duplicate.status_code==200 and duplicate.json()['failed'] is True
        assert client.delete(path).status_code==200
        row=next(row for row in history(client)['calls'] if row['id']==call_id)
        assert row['state']=='failed' and row['has_transcript'] is False
    with api.mobile.connect() as conn:
        stored=json.loads(conn.execute('SELECT receipt FROM calls WHERE id=?',(call_id,)).fetchone()[0])
        assert stored['failed'] is True
        assert conn.execute('SELECT COUNT(*) FROM timeline WHERE call_id=?',(call_id,)).fetchone()[0]==0
    assert set(stored)=={'audio_available','transcript_available','transcript_events',
                         'unfinalized_transcripts','incomplete','complete','failed'}


def test_failed_close_survives_lost_finalization_without_inventing_complete_text(harness):
    api,client,_,_,_=harness
    call_id=add_call(api);path=f'{PATH}/{call_id}'
    # No finalize reaches the server: only the independent terminal cleanup is acknowledged.
    response=client.request('DELETE',path,json={'failed':True})
    assert response.status_code==200 and response.json()['failed'] is True
    assert response.json()['closed'] is True and response.json().get('complete') is not True
    assert response.json().get('transcript_available') is not True
    for payload in (None,{}, {'failed':False}):
        late=client.delete(path) if payload is None else client.request('DELETE',path,json=payload)
        assert late.status_code==200 and late.json()['failed'] is True
        assert next(row for row in history(client)['calls'] if row['id']==call_id)['state']=='failed'
    assert client.post(path+'/finalize',json={}).json()['failed'] is True
    with api.mobile.connect() as conn:
        receipt=json.loads(conn.execute('SELECT receipt FROM calls WHERE id=?',(call_id,)).fetchone()[0])
        assert receipt['failed'] is True
        assert conn.execute('SELECT COUNT(*) FROM timeline WHERE call_id=?',(call_id,)).fetchone()[0]==0


@pytest.mark.parametrize('payload',[{'failed':None},{'failed':1},{'failed':'true'},{'failed':[]},{'other':True}])
def test_failed_close_rejects_invalid_flags_without_changing_call(harness,payload):
    api,client,_,_,_=harness
    call_id=add_call(api)
    assert client.request('DELETE',f'{PATH}/{call_id}',json=payload).status_code==400
    with api.mobile.connect() as conn:
        row=conn.execute('SELECT closed,receipt FROM calls WHERE id=?',(call_id,)).fetchone()
        assert row['closed']==0 and json.loads(row['receipt'])=={}


@pytest.mark.parametrize('body',[{}, {'incomplete':True}, {'failed':False}, {'failed':False,'incomplete':True}])
def test_legacy_normal_or_cancelled_call_is_not_reclassified_as_failure(harness,body):
    api,client,_,_,_=harness
    call_id=add_call(api)
    path=f'{PATH}/{call_id}'
    # Normal hangup/cancellation sends no terminal failure; interrupted speech is still not a call failure.
    events=[{'event_id':'caption','turn_id':'1','type':'transcript','role':'assistant',
             'text':'Interrupted fixture words','at_ms':1,'final':True,'interrupted':True}]
    assert client.post(path+'/events',json={'events':events}).status_code==200
    finalized=client.post(path+'/finalize',json=body)
    assert finalized.status_code==200 and finalized.json().get('failed',False) is False
    assert client.delete(path).status_code==200
    assert next(row for row in history(client)['calls'] if row['id']==call_id)['state']=='completed'


def test_late_finalization_never_upgrades_a_preexisting_stale_failure(harness):
    api,client,_,_,_=harness
    call_id=add_call(api,closed=1,status='failed')
    response=client.post(f'{PATH}/{call_id}/finalize',json={})
    assert response.status_code==200,response.text
    assert next(row for row in history(client)['calls'] if row['id']==call_id)['state']=='failed'


@pytest.mark.parametrize('incomplete',[False,True])
def test_terminal_failure_preserves_an_earlier_text_completeness_receipt(harness,incomplete):
    api,client,_,_,_=harness
    call_id=add_call(api)
    path=f'{PATH}/{call_id}/finalize'
    assert client.post(path,json={'incomplete':incomplete}).json()['complete'] is (not incomplete)
    failed=client.post(path,json={'failed':True})
    assert failed.status_code==200 and failed.json().get('failed') is True
    assert failed.json()['complete'] is (not incomplete) and failed.json()['incomplete'] is incomplete
    assert failed.json()['transcript_status']==('partial' if incomplete else 'saved')
    assert next(row for row in history(client)['calls'] if row['id']==call_id)['state']=='failed'


@pytest.mark.parametrize('value',[None,0,1,'failed',{},[]])
def test_terminal_failure_accepts_only_a_boolean(harness,value):
    api,client,_,_,_=harness
    call_id=add_call(api)
    response=client.post(f'{PATH}/{call_id}/finalize',json={'failed':value})
    assert response.status_code==409
    with api.mobile.connect() as conn:
        row=conn.execute('SELECT closed,receipt FROM calls WHERE id=?',(call_id,)).fetchone()
    assert row['closed']==0 and json.loads(row['receipt'])=={}


def test_stale_call_releases_waiting_invitation_and_history_ends(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api, 'watch', lambda *args: None)
    call_id = add_call(api)

    polled = client.get(f'{PATH}/{call_id}')

    assert polled.status_code == 200, polled.text
    with api.mobile.connect() as conn:
        seen = conn.execute('SELECT seen FROM calls WHERE id=?', (call_id,)).fetchone()['seen']
    assert seen is not None and seen >= time.time() - 1

    invitation = api.mobile.invitation('owner', 'default', 'native-stored', 'Call after this session')
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT 1 FROM deferred WHERE id=?', (invitation,)).fetchone()
        conn.execute('UPDATE calls SET seen=? WHERE id=?', (time.time() - 301, call_id))

    row = next(row for row in history(client)['calls'] if row['id'] == call_id)
    assert row['state'] == 'failed' and row['duration_s'] is not None

    api.activate('owner', 'default')

    with api.mobile.connect() as conn:
        call = conn.execute('SELECT closed,ended,status FROM calls WHERE id=?', (call_id,)).fetchone()
        assert call['closed'] == 1 and call['ended'] is not None and call['status'] == 'failed'
        assert conn.execute('SELECT 1 FROM deferred WHERE id=?', (invitation,)).fetchone() is None
        assert conn.execute("SELECT 1 FROM notices WHERE id=? AND kind='incoming_call'", (invitation,)).fetchone()


def test_call_history_pages_newest_first_with_a_stable_cursor(harness):
    api, client, _, _, _ = harness
    now = time.time()
    ids = [add_call(api, created=now - index, conversation=f'chat-{index}', closed=1, status='saved')
           for index in range(1, 4)]

    first = history(client, limit=2)
    second = history(client, limit=2, before=first['next'])
    assert [row['id'] for row in first['calls']] == ids[:2]
    assert [row['id'] for row in second['calls']] == ids[2:]
    assert first['next'] and second['next'] is None


@pytest.mark.parametrize('params', [{'limit': '0'}, {'limit': '51'}, {'limit': '²'}, {'before': 'invalid'}])
def test_call_history_rejects_invalid_page_arguments(harness, params):
    _, client, _, _, _ = harness
    assert client.get(PATH, params=params).status_code == 400
