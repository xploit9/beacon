import importlib.util
from pathlib import Path
from types import SimpleNamespace
import pytest
from fastapi import FastAPI, HTTPException
from fastapi.testclient import TestClient

spec = importlib.util.spec_from_file_location('bot_mode_test', Path(__file__).parents[1] / 'bot_mode_api.py')
api = importlib.util.module_from_spec(spec)
spec.loader.exec_module(api)

@pytest.fixture
def harness():
    calls = []
    rows = [{'name': 'default', 'display_name': 'Hermes', 'ui_meta': {'hermes-bots': {'title': 'Hermes'}}}, {'name': 'writer'}, {'name': 'private'}]
    members = [{'profile': 'default', 'target': {'kind': 'local', 'profile': 'default'}}, {'profile': 'writer', 'target': {'kind': 'local', 'profile': 'writer'}}]
    state = {'room': {'room_id': 'native-room', 'name': 'Team', 'members': members, 'latest_seq': 301}}
    results = {'profiles.list': {'profiles': rows}, 'groups.list': {'rooms': [state['room']]}, 'groups.state': state,
               'groups.log': {'events': [], 'cursor': 301, 'latest_seq': 301, 'has_more': False},
               'session.list': {'sessions': [{'id': 'canonical', 'title': 'Bot Chat'}]},
               'session.create': {'session_id': 'runtime', 'stored_session_id': 'new-canonical'},
               'session.title': {}, 'profiles.configure': {'applied': {'ui_meta': True}},
               'groups.create': state, 'groups.send': {'accepted': True}}
    def identity(request, profile=None):
        if request.headers.get('x-owner') != 'owner': raise HTTPException(401, 'Verified native session required')
        profile = profile or request.query_params.get('profile', 'default')
        if profile not in results.get('authorized_profiles', ['default', 'writer']): raise HTTPException(403, 'Profile not authorized')
        return 'owner', 'password', profile, {'profiles': results.get('authorized_profiles', ['default', 'writer'])}
    async def body(request, limit): return await request.json()
    def rpc(owner, provider, method, params):
        calls.append((owner, provider, method, params))
        result = results[method]
        if isinstance(result, Exception): raise result
        return result(params) if callable(result) else result
    def verify(*args):
        calls.append(('verify', *args))
        if results.get('verify_error'): raise LookupError()
    app = FastAPI(); app.include_router(api.build_router(identity, body, SimpleNamespace(rpc=rpc, verify=verify)))
    return TestClient(app, headers={'x-owner': 'owner'}), calls, results

def test_unauthenticated_cannot_reach_native(harness):
    client, calls, _ = harness
    assert client.get('/mobile/bot-mode', headers={'x-owner': ''}).status_code == 401
    assert calls == []

def test_snapshot_is_real_roster_and_filters_unauthorized_rooms(harness):
    client, calls, results = harness
    results['groups.list']['rooms'].append({'room_id': 'private', 'members': [{'profile': 'private'}]})
    data = client.get('/mobile/bot-mode').json()
    assert [b['profile'] for b in data['bots']] == ['default', 'writer']
    assert [r['room_id'] for r in data['spaces']] == ['native-room']
    assert all(c[0:2] == ('owner', 'password') for c in calls)
    assert not any(c[2] in ['session.create', 'groups.create', 'profiles.configure'] for c in calls)

def test_canonical_lookup_verifies_owner_and_uses_exact_title(harness):
    client, calls, _ = harness
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'writer'}).json()['stored_session_id'] == 'canonical'
    assert ('verify', 'owner', 'password', 'writer', 'canonical') in calls
    lookup = next(c for c in calls if len(c) == 4 and c[2] == 'session.list')
    assert lookup[3] == {'profile': 'writer', 'title': 'Bot Chat', 'include_hidden': True, 'limit': 200}
    assert not any(len(c) == 4 and c[2] == 'session.create' for c in calls)

def test_forbidden_bot_fails_before_native(harness):
    client, calls, _ = harness
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'private'}).status_code == 403
    assert calls == []

def test_foreign_owner_canonical_never_returns(harness):
    client, calls, results = harness; results['verify_error'] = True
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'default'}).status_code == 409
    assert not any(len(c) == 4 and c[2] == 'session.create' for c in calls)

def test_new_bot_is_managed_and_canonical_title_persisted(harness):
    client, calls, results = harness; results['session.list'] = {'sessions': []}
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'writer'}).json()['stored_session_id'] == 'new-canonical'
    configure = next(c for c in calls if len(c) == 4 and c[2] == 'profiles.configure')
    assert configure[3]['name'] == 'writer' and configure[3]['ui_meta_expected_revisions'] == {'hermes-bots': 0}
    create = next(c for c in calls if len(c) == 4 and c[2] == 'session.create')
    assert create[3]['hidden'] and create[3]['follow_profile_config']
    assert next(c for c in calls if len(c) == 4 and c[2] == 'session.title')[3]['title'] == 'Bot Chat'

@pytest.mark.parametrize('profiles', [[], ['default'], ['private', 'writer'], ['default', 'default'], [[], 'writer']])
def test_bad_room_members_are_rejected(harness, profiles):
    client, calls, _ = harness
    assert client.post('/mobile/bot-mode/space', json={'profiles': profiles}).status_code == 400
    assert not any(c[2] == 'groups.create' for c in calls)

def test_room_creation_is_explicit_and_scope_bound(harness):
    client, calls, _ = harness
    assert client.post('/mobile/bot-mode/space', json={'profiles': ['default', 'writer']}).status_code == 200
    params = next(c[3] for c in calls if c[2] == 'groups.create')
    assert params['room_id'] == api.space_id('owner', 'password', 'default', 'members:default,writer')
    assert params['room_id'] != api.space_id('other', 'password', 'default', 'members:default,writer')
    assert params['room_id'] != api.space_id('owner', 'password', 'writer', 'members:default,writer')
    assert [r['target'] for r in params['members']] == [{'kind': 'local', 'profile': 'default'}, {'kind': 'local', 'profile': 'writer'}]

def test_room_read_is_recent_native_log(harness):
    client, calls, _ = harness
    assert client.get('/mobile/bot-mode/space/native-room').json()['earlier_events_omitted']
    assert next(c[3] for c in calls if c[2] == 'groups.log')['since_seq'] == 101

def test_revoked_member_denies_read_and_send(harness):
    client, calls, results = harness; results['groups.state']['room']['members'].append({'profile': 'private'})
    assert client.get('/mobile/bot-mode/space/native-room').status_code == 403
    assert client.post('/mobile/bot-mode/space/native-room/messages', json={'text': 'hello', 'event_id': 'stable'}).status_code == 403
    assert not any(c[2] in ['groups.log', 'groups.send'] for c in calls)

def test_message_uses_server_actor_and_stable_event(harness):
    client, calls, _ = harness
    assert client.post('/mobile/bot-mode/space/native-room/messages', json={'text': 'hello @writer', 'event_id': 'stable'}).status_code == 200
    params = next(c[3] for c in calls if c[2] == 'groups.send')
    assert params == {'profile': 'default', 'room_id': 'native-room', 'event_id': 'stable', 'payload': {'text': 'hello @writer', 'thread_id': 'native-room'}}
    assert client.post('/mobile/bot-mode/space/native-room/messages', json={'text': 'x', 'event_id': 'stable', 'actor': 'admin'}).status_code == 400

def test_native_failure_is_not_empty_success(harness):
    client, _, results = harness; results['profiles.list'] = RuntimeError('offline')
    assert client.get('/mobile/bot-mode').status_code == 503

def test_profile_marker_compare_and_swap_conflict_stops_before_chat(harness):
    client, calls, results = harness; results['profiles.configure'] = {'applied': {'ui_meta': False}}
    assert client.post('/mobile/bot-mode/chat', json={'bot': 'writer'}).status_code == 503
    assert not any(len(c) == 4 and c[2] in ['session.list', 'session.create'] for c in calls)

def test_stale_or_permanent_approval_is_rejected(harness):
    client, calls, results = harness
    pending = {'kind': 'approval', 'task_id': 'task', 'member_id': 'writer', 'request_id': 'req', 'execution_generation': 3}
    results['groups.state']['driver_status'] = {'pending_actions': [pending]}
    results['groups.approve'] = {'approved': True}
    path = '/mobile/bot-mode/space/native-room/action'
    assert client.post(path, json=dict(pending, choice='always')).status_code == 400
    assert client.post(path, json=dict(pending, execution_generation=2, choice='once')).status_code == 409
    assert not any(c[2] == 'groups.approve' for c in calls)
    assert client.post(path, json=dict(pending, choice='deny')).status_code == 200
    params = next(c[3] for c in calls if c[2] == 'groups.approve')
    assert params['execution_generation'] == 3 and params['request_id'] == 'req' and params['choice'] == 'deny'

def test_native_retry_requires_pending_task(harness):
    client, calls, results = harness
    results['groups.retry'] = {'retried': True}
    path = '/mobile/bot-mode/space/native-room/action'
    assert client.post(path, json={'kind': 'retry', 'task_id': 'other'}).status_code == 409
    results['groups.state']['driver_status'] = {'pending_actions': [{'kind': 'retry', 'task_id': 'task'}]}
    assert client.post(path, json={'kind': 'retry', 'task_id': 'task'}).status_code == 200
    assert next(c[3] for c in calls if c[2] == 'groups.retry') == {'profile': 'default', 'room_id': 'native-room', 'task_id': 'task'}


@pytest.mark.parametrize('member', [{'profile': 'writer'}, {'profile': 'writer', 'target': {'profile': 'writer'}}, {'profile': 'writer', 'target': {'kind': 'local'}}, {'profile': 'default', 'target': {'kind': 'local', 'profile': 'writer'}}])
def test_native_members_require_explicit_matching_local_target(harness, member):
    client, _, results = harness
    results['groups.state']['room']['members'] = [member]
    assert client.get('/mobile/bot-mode/space/native-room').status_code == 403
    assert client.get('/mobile/bot-mode').json()['spaces'] == []

@pytest.mark.parametrize('page', [{'events': [], 'has_more': True}, {'events': [], 'cursor': 101, 'has_more': True}, {'events': [], 'cursor': 102, 'has_more': True}, {'events': [], 'cursor': 'bad'}, {'events': [{}]*201, 'cursor': 301, 'has_more': False}])
def test_invalid_native_replay_fails_with_readable_503(harness, page):
    client, _, results = harness; results['groups.log'] = page
    response = client.get('/mobile/bot-mode/space/native-room')
    assert response.status_code == 503
    assert 'invalid cursor or page' in response.json()['detail']

def test_native_paged_replay_requests_only_remaining_window(harness):
    client, calls, results = harness
    def log(params):
        count = min(120, params['limit'])
        return {'events': [{'seq': params['since_seq']+i+1} for i in range(count)], 'cursor': params['since_seq']+count, 'has_more': True}
    results['groups.log'] = log
    response = client.get('/mobile/bot-mode/space/native-room')
    assert len(response.json()['events']) == 200
    assert [c[3]['limit'] for c in calls if c[2] == 'groups.log'] == [200, 80]


def test_changing_members_creates_a_distinct_room_and_retries_are_idempotent(harness):
    client, calls, results = harness
    results['authorized_profiles'] = ['default', 'writer', 'researcher']
    results['profiles.list']['profiles'].append({'name': 'researcher'})
    rooms = {}
    def create(params):
        rid = params['room_id']
        state = {'name': params['name'], 'members': params['members']}
        if rid in rooms and rooms[rid] != state:
            raise LookupError('room_id already exists with different state')
        rooms[rid] = state
        return {'room': dict(state, room_id=rid)}
    results['groups.create'] = create
    first = client.post('/mobile/bot-mode/space', json={'profiles': ['default', 'writer']})
    assert first.status_code == 200
    second = client.post('/mobile/bot-mode/space', json={'profiles': ['default', 'writer', 'researcher']})
    assert second.status_code == 200
    again = client.post('/mobile/bot-mode/space', json={'profiles': ['writer', 'default']})
    assert again.status_code == 200
    assert again.json()['room']['room_id'] == first.json()['room']['room_id']
    assert len(rooms) == 2
    assert first.json()['room']['room_id'] != second.json()['room']['room_id']


def test_creation_reopens_matching_legacy_room_without_replacing_its_history(harness):
    client, calls, results = harness
    room = results['groups.list']['rooms'][0]
    room['room_id'] = api.space_id('owner', 'password', 'default')
    room['name'] = 'Existing owner discussion'
    response = client.post('/mobile/bot-mode/space', json={'profiles': ['writer', 'default']})
    assert response.status_code == 200
    assert response.json()['room'] == room
    assert not any(c[2] == 'groups.create' for c in calls)


@pytest.mark.parametrize('request_id', ['', None, '../escape', [], 'x'*101])
def test_invalid_creation_identity_never_calls_native_create(harness, request_id):
    client, calls, _ = harness
    response = client.post('/mobile/bot-mode/space', json={'profiles': ['default', 'writer'], 'request_id': request_id})
    assert response.status_code == 400
    assert not any(c[2] == 'groups.create' for c in calls)


def test_new_create_intents_get_distinct_ids_and_renamed_retries_reopen(harness):
    client, calls, results = harness
    first = {'profiles': ['default', 'writer'], 'request_id': 'new-one'}
    client.post('/mobile/bot-mode/space', json=first)
    rid = [c[3]['room_id'] for c in calls if c[2] == 'groups.create'][-1]
    room = dict(results['groups.state']['room'], room_id=rid, name='Renamed room')
    results['groups.list']['rooms'].append(room)
    retry = client.post('/mobile/bot-mode/space', json=first)
    assert retry.json()['room'] == room
    client.post('/mobile/bot-mode/space', json=dict(first, request_id='new-two'))
    created = [c[3]['room_id'] for c in calls if c[2] == 'groups.create']
    assert len(created) == 2 and created[0] != created[1]


def test_create_retry_finds_renamed_room_beyond_first_page(harness):
    client, calls, results = harness
    room = dict(results['groups.state']['room'], room_id=api.space_id('owner','password','default','retry-me'), name='Renamed')
    results['groups.list'] = lambda params: {'rooms': [], 'next_offset': 100} if params['offset'] == 0 else {'rooms': [room], 'next_offset': None}
    result = client.post('/mobile/bot-mode/space', json={'profiles': ['default','writer'], 'request_id':'retry-me'})
    assert result.status_code == 200 and result.json()['room'] == room
    assert not any(c[2] == 'groups.create' for c in calls)
    assert [c[3]['offset'] for c in calls if c[2] == 'groups.list'] == [0,100]


def test_create_rejects_nonadvancing_native_page(harness):
    client, _, results = harness
    results['groups.list'] = {'rooms': [], 'next_offset':0}
    assert client.post('/mobile/bot-mode/space', json={'profiles':['default','writer']}).status_code == 503


def test_rename_uses_owned_room_and_stable_native_event(harness):
    client, calls, results = harness
    results['groups.rename'] = lambda p: {'room': dict(results['groups.state']['room'], name=p['name'])}
    result = client.post('/mobile/bot-mode/space/native-room/rename', json={'name': 'Research team', 'expected_name': 'Team', 'event_id': 'rename-1'})
    assert result.status_code == 200 and result.json()['room']['name'] == 'Research team'
    assert calls[-1][2:] == ('groups.rename', {'profile': 'default', 'room_id': 'native-room', 'event_id': 'rename-1', 'name': 'Research team'})
    assert not any(c[2] in ('groups.create', 'groups.disband') for c in calls)


def test_rename_rejects_stale_disabled_foreign_and_injected_changes(harness):
    client, calls, results = harness
    payload = {'name': 'Research team', 'expected_name': 'Different', 'event_id': 'rename-1'}
    assert client.post('/mobile/bot-mode/space/native-room/rename', json=payload).status_code == 409
    assert not any(c[2] == 'groups.rename' for c in calls)
    assert client.post('/mobile/bot-mode/space/native-room/rename', json=dict(payload, members=[])).status_code == 400
    assert client.post('/mobile/bot-mode/space/native-room/rename', json=dict(payload, name='x\n')).status_code == 400
    results['authorized_profiles'] = ['default']
    assert client.post('/mobile/bot-mode/space/native-room/rename', json=payload).status_code == 403
    assert client.post('/mobile/bot-mode/space/native-room/rename', json=payload, headers={'x-owner': ''}).status_code == 401

@pytest.mark.parametrize('action,value,method', [
    ('members', {'profiles': ['default','writer'], 'expected_profiles': ['default','writer'], 'event_id':'edit1'}, 'groups.members.set'),
    ('delete', {'event_id':'delete1','expected_name':'Team'}, 'groups.delete')])
def test_management_is_authenticated_and_native_receipted(harness, action, value, method):
    client,calls,results=harness
    results[method] = ({'room':results['groups.state']['room'], 'event':{'event_id':'edit1'}} if action=='members' else {'tombstone':{'room_id':'native-room','event_id':'delete1'}})
    path='/mobile/bot-mode/space/native-room/'+action
    assert client.post(path,json=value,headers={'x-owner':''}).status_code==401
    assert not any(c[2]==method for c in calls)
    response=client.post(path,json=value)
    assert response.status_code==200,response.text
    assert next(c[3] for c in calls if c[2]==method)['event_id']==value['event_id']


def test_members_rejects_foreign_new_roster_and_stale_expected(harness):
    client,calls,results=harness
    path='/mobile/bot-mode/space/native-room/members'
    base={'profiles':['default','private'],'expected_profiles':['default','writer'],'event_id':'e'}
    assert client.post(path,json=base).status_code==400
    assert client.post(path,json=dict(base,profiles=['default','writer'],expected_profiles=['default','private'])).status_code in (400,409)
    assert not any(c[2]=='groups.members.set' for c in calls)


def test_delete_requires_actual_native_receipt_and_name(harness):
    client,calls,results=harness
    path='/mobile/bot-mode/space/native-room/delete'
    assert client.post(path,json={'event_id':'d','expected_name':'Stale'}).status_code==409
    results['groups.delete']={}
    assert client.post(path,json={'event_id':'d','expected_name':'Team'}).status_code==503
