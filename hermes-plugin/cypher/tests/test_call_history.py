import json
import time

import pytest

from test_mobile import harness


PATH = '/api/plugins/cypher/mobile/calls'


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
    assert client.put('/api/plugins/cypher/mobile/history-retention', json={'history_retention_days': 30}).status_code == 200
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


def test_call_history_pages_newest_first_with_a_stable_cursor(harness):
    api, client, _, _, _ = harness
    now = time.time()
    ids = [add_call(api, conversation=f'chat-{index}', created=now - index, closed=1, status='saved')
           for index in range(1, 4)]

    first = history(client, limit=2)
    second = history(client, limit=2, before=first['next'])
    assert [row['id'] for row in first['calls']] == ids[:2]
    assert [row['id'] for row in second['calls']] == ids[2:]
    assert first['next'] and second['next'] is None


@pytest.mark.parametrize('params', [{'limit': '0'}, {'limit': '51'}, {'before': 'invalid'}])
def test_call_history_rejects_invalid_page_arguments(harness, params):
    _, client, _, _, _ = harness
    assert client.get(PATH, params=params).status_code == 400
