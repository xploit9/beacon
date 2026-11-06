import importlib.util
import sys
import threading
from contextlib import contextmanager
from pathlib import Path
from types import SimpleNamespace

import pytest

from test_mobile import harness, reserve


@pytest.fixture
def lineage(harness, monkeypatch):
    api, client, _, _, _ = harness
    spec = importlib.util.spec_from_file_location('lineage_native_fixture', Path(api.native.__file__))
    native = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(native)
    rows = {
        'root': {'id': 'root', 'user_id': 'basic:owner', 'end_reason': 'compression'},
        'middle': {'id': 'middle', 'user_id': 'basic:owner', 'end_reason': 'compression'},
        'tip': {'id': 'tip', 'user_id': 'basic:owner', 'end_reason': None},
        'unrelated': {'id': 'unrelated', 'user_id': 'basic:owner', 'end_reason': None},
    }
    chains = {'root': ['root', 'middle', 'tip'], 'middle': ['middle', 'tip'],
              'tip': ['tip'], 'unrelated': ['unrelated']}
    db = SimpleNamespace(get_session=lambda sid: rows.get(sid),
                         get_compression_chain=lambda sid: chains[sid])
    @contextmanager
    def profile_db(params):
        yield db
    record = {'session_key': 'tip', 'profile_home': '/profiles/default', 'user_id': 'basic:owner',
              'running': False, 'history_lock': threading.RLock(), 'inflight_turn': None}
    server = SimpleNamespace(_profile_db=profile_db, _profile_home=lambda profile: '/profiles/' + profile,
                             _session_auth_user_id=lambda session: session['user_id'],
                             _sessions_lock=threading.RLock(), _sessions={'runtime': record})
    events = []
    replay = SimpleNamespace(replay_epoch=lambda: 'epoch-one', events_since=lambda *_: events)
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server, event_replay=replay))
    history = {'messages': [{'role': 'user', 'row_id': 10},
                            {'role': 'assistant', 'row_id': 11, 'content': 'native result'}]}
    rpc = native.rpc
    monkeypatch.setattr(native, 'rpc', lambda *args: history)
    monkeypatch.setattr(api.native, 'verify', native.verify)
    return SimpleNamespace(api=api, client=client, native=native, rows=rows, chains=chains,
                           record=record, server=server, events=events, history=history, db=db, rpc=rpc)


def test_native_verify_accepts_owned_proven_forward_compression_lineage(lineage):
    result = lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime')
    assert result['messages'][0]['row_id'] == 10
    assert lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime', False)['id'] == 'root'


def test_native_rpc_refusal_logs_only_integer_code(lineage, monkeypatch, caplog):
    monkeypatch.setattr(lineage.server, 'dispatch', lambda *args, **kwargs: {
        'error': {'code': 4000, 'message': 'private fixture detail', 'data': {'token': 'private token'}},
    }, raising=False)
    with caplog.at_level('WARNING'), pytest.raises(LookupError):
        lineage.rpc('owner', 'basic', 'session.history', {'session_id': 'private session'})
    assert 'Beacon native RPC refused: code=4000' in caplog.text
    assert 'private' not in caplog.text


@pytest.mark.parametrize('mutation', ['foreign_root', 'foreign_middle', 'foreign_tip', 'other_runtime',
                                       'wrong_profile', 'foreign_runtime', 'unrelated', 'reverse_lineage'])
def test_native_verify_rejects_unowned_or_unproven_lineage(lineage, mutation):
    stored = 'root'
    runtime = 'runtime'
    profile = 'default'
    if mutation.startswith('foreign_') and mutation != 'foreign_runtime':
        lineage.rows[mutation.removeprefix('foreign_')]['user_id'] = 'basic:other'
    elif mutation == 'other_runtime':
        runtime = 'missing-runtime'
    elif mutation == 'wrong_profile':
        profile = 'other'
    elif mutation == 'foreign_runtime':
        lineage.record['user_id'] = 'basic:other'
    elif mutation == 'unrelated':
        lineage.record['session_key'] = 'unrelated'
    elif mutation == 'reverse_lineage':
        stored = 'tip'
        lineage.record['session_key'] = 'root'
    with pytest.raises(LookupError):
        lineage.native.verify('owner', 'basic', profile, stored, runtime, False)


def test_native_verify_does_not_guess_when_lineage_lookup_unavailable(lineage):
    del lineage.db.get_compression_chain
    with pytest.raises(RuntimeError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime', False)


def job_request(client, call_id, request='first', row_id=10):
    return client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/jobs', json={
        'stored_session_id': 'root', 'runtime_session_id': 'runtime',
        'request_id': request, 'user_row_id': row_id, 'callback': True,
    })


def owned_call(lineage):
    return lineage.api.mobile.reserve('owner', 'default', 'root', {'backend': 'hermes'})['call_id']


def finish_event(lineage):
    lineage.events.append({'type': 'message.complete', 'payload': {'status': 'complete', 'persisted_turn': {
        'user_row_id': 10, 'final_assistant_row_id': 11,
    }}})


def test_compression_before_job_registration_keeps_exact_receipt(lineage):
    call_id = owned_call(lineage)
    finish_event(lineage)
    linked = job_request(lineage.client, call_id)
    assert linked.status_code == 200, linked.text
    result = lineage.client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert result['status'] == 'done' and result['result'] == 'native result'
    assert result['user_row_id'] == 10 and result['stored_session_id'] == 'root'


def test_compression_after_linkage_keeps_matching_terminal_proof(lineage):
    call_id = owned_call(lineage)
    lineage.record['session_key'] = 'root'
    linked = job_request(lineage.client, call_id)
    assert linked.status_code == 200, linked.text
    lineage.record['session_key'] = 'tip'
    finish_event(lineage)
    result = lineage.client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert result['status'] == 'done' and result['result'] == 'native result'


def test_compression_cannot_replace_original_user_row_with_another(lineage):
    call_id = owned_call(lineage)
    lineage.history['messages'] = [{'role': 'user', 'row_id': 12},
                                   {'role': 'assistant', 'row_id': 11, 'content': 'native result'}]
    linked = job_request(lineage.client, call_id)
    assert linked.status_code == 409
    with lineage.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE call_id=?', (call_id,)).fetchone()[0] == 0
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id<>\'\'').fetchone()[0] == 0


def test_compression_does_not_correlate_a_different_terminal_user_row(lineage):
    call_id = owned_call(lineage)
    lineage.record['session_key'] = 'root'
    assert job_request(lineage.client, call_id).status_code == 200
    lineage.record['session_key'] = 'tip'
    lineage.events.append({'type': 'message.complete', 'payload': {'status': 'complete', 'persisted_turn': {
        'user_row_id': 12, 'final_assistant_row_id': 11,
    }}})
    result = lineage.client.get(f'/api/plugins/beacon/mobile/calls/{call_id}').json()['jobs'][0]
    assert result['status'] == 'uncertain' and result['error_code'] == 'native_turn_unconfirmed'
    assert 'result' not in result
    with lineage.api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices WHERE job_id<>\'\'').fetchone()[0] == 0


def test_native_verify_fails_closed_if_runtime_changes_during_lineage_read(lineage):
    def rotate(_):
        lineage.record['session_key'] = 'unrelated'
        return ['root', 'middle', 'tip']
    lineage.db.get_compression_chain = rotate
    with pytest.raises(RuntimeError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime', False)


def test_native_verify_fails_closed_if_runtime_changes_during_history(lineage, monkeypatch):
    def history(*args):
        lineage.record['session_key'] = 'unrelated'
        return lineage.history
    monkeypatch.setattr(lineage.native, 'rpc', history)
    with pytest.raises(RuntimeError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime')


def test_owned_explicit_branch_is_not_a_compression_continuation(lineage):
    lineage.rows['branch'] = {'id': 'branch', 'parent_session_id': 'root',
                              'user_id': 'basic:owner', 'model_config': {'_branched_from': 'root'}}
    lineage.record['session_key'] = 'branch'
    with pytest.raises(LookupError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime', False)


def test_lineage_lookup_fault_is_retryable_not_proof_of_missing_session(lineage):
    def unavailable(_):
        raise OSError('store unavailable')
    lineage.db.get_compression_chain = unavailable
    with pytest.raises(RuntimeError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'runtime', False)


def test_canonical_owner_requires_exact_verified_issuer_and_never_warms(lineage, monkeypatch):
    from gateway.session_identity import authenticated_subject
    subject = authenticated_subject({'provider': 'basic', 'issuer': 'https://issuer.example', 'user_id': 'owner'})
    other = authenticated_subject({'provider': 'basic', 'issuer': 'https://other.example', 'user_id': 'owner'})
    lineage.rows['root']['user_id'] = subject
    monkeypatch.setattr(lineage.native, 'rpc', lambda *args, **kwargs: pytest.fail('A read guard dispatched or warmed a session'))
    assert lineage.native.verify('owner', 'basic', 'default', 'root', principal=subject)['id'] == 'root'
    for principal in (None, other, 'basic:owner', authenticated_subject({'provider': 'other', 'issuer': 'https://issuer.example', 'user_id': 'owner'})):
        with pytest.raises(LookupError):
            lineage.native.verify('owner', 'basic', 'default', 'root', principal=principal)
    with pytest.raises(LookupError):
        lineage.native.verify('owner', 'basic', 'default', 'root', 'root', False, principal=subject)


def test_legacy_owner_never_authorizes_a_foreign_canonical_target(lineage):
    from gateway.session_identity import authenticated_subject
    subject = authenticated_subject({'provider': 'basic', 'issuer': 'a', 'user_id': 'owner'})
    lineage.rows['root']['user_id'] = authenticated_subject({'provider': 'basic', 'issuer': 'b', 'user_id': 'owner'})
    with pytest.raises(LookupError):
        lineage.native.verify('owner', 'basic', 'default', 'root', principal=subject)
    lineage.rows['root']['user_id'] = 'basic:owner'
    assert lineage.native.verify('owner', 'basic', 'default', 'root', principal=subject)['id'] == 'root'


def test_canonical_executing_principal_requires_exact_active_native_admission(lineage, monkeypatch, tmp_path):
    from gateway.session_identity import authenticated_subject
    from gateway import session_ingress, session_authorities
    from hermes_cli import profiles
    import hermes_constants
    import hermes_state_runtime
    subject=authenticated_subject({'provider':'basic','issuer':'issuer-one','user_id':'owner'})
    live=SimpleNamespace(source=SimpleNamespace(user_id=subject))
    lineage.rows['root']['user_id']=subject
    authority=SimpleNamespace(profile_id=str(tmp_path),instance_id='instance',epoch=7,retiring=False,db=lineage.db,
                              sessions={'root':live},executing={'root':3})
    runner=SimpleNamespace(session_authority=authority,_draining=False,
                           session_runtime_descriptor={'state':'ready','instance_id':'instance','authority_epoch':7})
    monkeypatch.setitem(sys.modules,'gateway.run_api',SimpleNamespace(_ACTIVE_RUNNER=runner))
    monkeypatch.setattr(hermes_constants,'get_hermes_home',lambda:tmp_path)
    monkeypatch.setattr(lineage.server,'_profile_home',lambda profile:tmp_path)
    monkeypatch.setattr(profiles,'profile_matches_home',lambda profile,home:profile=='default' and home==tmp_path)
    monkeypatch.setattr(session_authorities,'authority_for_home',lambda candidate,home:authority if candidate is runner and home==tmp_path else None)
    row={'admission_id':'remote-one','target_session_id':'root','status':'started','owner_epoch':7,'generation':3,
         'request_id':'request','principal_id':subject,'payload':{'text':'synthetic'}}
    monkeypatch.setattr(hermes_state_runtime,'list_session_admissions',lambda db,session_id:[row])
    active=session_ingress.executing_admission.set(True)
    viewer=session_ingress.viewer_turn.set(live)
    try:
        # An identical row from a private named grant is not remote authorization.
        assert lineage.native.current_principal('owner','basic','default','root') is None
        assert lineage.api.mobile.confirm_remote_admission({
            'admission_id':'remote-one','owner':'owner','provider':'basic','profile':'default',
            'home':lineage.native.home_fingerprint(tmp_path),'session':'root','request':'request','principal':subject,
            'epoch':7,'wire_digest':'a'*64,'payload_digest':lineage.native._payload_fingerprint(row['payload'])})
        assert lineage.api.mobile.start_remote_admission('remote-one',7,3)
        assert lineage.native.current_principal('owner','basic','default','root')==subject
        row['generation']=4
        assert lineage.native.current_principal('owner','basic','default','root') is None
        row['generation']=3
        row['principal_id']=authenticated_subject({'provider':'basic','issuer':'issuer-two','user_id':'owner'})
        assert lineage.native.current_principal('owner','basic','default','root') is None
        row['principal_id']=subject
        assert lineage.native.current_principal('owner','basic','other','root') is None
        session_ingress.viewer_turn.set(None)
        assert lineage.native.current_principal('owner','basic','default','root') is None
    finally:
        session_ingress.viewer_turn.reset(viewer)
        session_ingress.executing_admission.reset(active)


def test_canonical_native_admission_cannot_distinguish_remote_from_named_local_subject(tmp_path):
    import inspect
    from gateway.session_controls import AuthorityConnection
    from gateway.session_identity import authenticated_subject
    from hermes_state_runtime import admit_session_input
    authority=SimpleNamespace(profile_id=str(tmp_path),instance_id='instance',events={})
    remote={'provider':'basic','issuer':'issuer-one','user_id':'owner'}
    subject=authenticated_subject(remote)
    private={'provider':'local','user_id':subject,'profile_id':str(tmp_path),'instance_id':'instance',
             'capabilities':['session:read','session:create','session:submit'],'native_bootstrap':True}
    normal=AuthorityConnection(authority,object(),remote,operator=True)
    local=AuthorityConnection(authority,object(),private,operator=True)
    assert normal.actor.subject==local.actor.subject==subject
    assert normal.native_owner is False and local.native_owner is True
    params=inspect.signature(admit_session_input).parameters
    assert 'principal_id' in params and 'transport_id' not in params and 'native_bootstrap' not in params


def test_canonical_native_exact_replay_receipt_has_no_fresh_or_transport_provenance(monkeypatch):
    import sqlite3
    import hermes_state_runtime
    import hermes_state_terminal
    from gateway.session_identity import authenticated_subject
    conn=sqlite3.connect(':memory:');conn.row_factory=sqlite3.Row
    conn.executescript('''
        CREATE TABLE runtime_epoch(singleton INTEGER PRIMARY KEY,epoch INTEGER);
        INSERT INTO runtime_epoch VALUES(1,7);
        CREATE TABLE sessions(id TEXT PRIMARY KEY);
        INSERT INTO sessions VALUES('same-chat');
        CREATE TABLE session_admissions(seq INTEGER PRIMARY KEY AUTOINCREMENT,admission_id TEXT UNIQUE,request_id TEXT,
            principal_id TEXT,target_session_id TEXT,lineage_json TEXT,payload_json TEXT,payload_digest TEXT,intent TEXT,
            status TEXT,owner_epoch INTEGER,generation INTEGER,outcome TEXT);
    ''')
    db=SimpleNamespace(_execute_write=lambda fn:fn(conn))
    monkeypatch.setattr(hermes_state_terminal,'retry_terminal_admission',lambda *args,**kwargs:None)
    subject=authenticated_subject({'provider':'basic','issuer':'issuer-one','user_id':'owner'})
    args={'epoch':7,'principal_id':subject,'session_id':'same-chat','request_id':'same-request','payload':{'text':'synthetic'}}
    private_first=hermes_state_runtime.admit_session_input(db,**args)
    remote_replay=hermes_state_runtime.admit_session_input(db,**args)
    assert private_first==remote_replay
    assert private_first['admission_id']==remote_replay['admission_id']
    assert not {'transport_id','native_bootstrap','fresh'} & set(remote_replay)
    assert conn.execute('SELECT COUNT(*) FROM session_admissions').fetchone()[0]==1
    conn.close()
