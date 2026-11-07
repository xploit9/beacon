"""Synthetic continuation evidence; no owner runtime, model, email or push delivery."""
import importlib.util
import os
import sys
import threading
import json
import time
import asyncio
import contextvars
from pathlib import Path
import subprocess
from contextlib import contextmanager
from types import SimpleNamespace

import pytest

from test_mobile import harness, reserve

NATIVE = Path(os.environ['HERMES_AGENT_SOURCE']) if 'HERMES_AGENT_SOURCE' in os.environ else Path.home() / 'AppData/Local/hermes/hermes-agent'


def approval_presentation():
    """Native approval state stays real; only the model runner's presentation helpers are replaced.

    ``session_pending_controls`` formats prompts with ``_redact_approval_command`` and
    ``SessionAuthority.interrupt`` compares the route's agent with ``_AGENT_PENDING_SENTINEL``.
    Both are defined in ``gateway.run``, whose import pulls in the model runner and the owner's
    ``.env``; the fixture supplies the same symbols without either."""
    return SimpleNamespace(_redact_approval_command=lambda value:value or '', _AGENT_PENDING_SENTINEL=object())


@pytest.fixture
def continuation(harness, monkeypatch):
    api, client, cfg, tmp, app = harness
    # Native approval state stays real while presentation avoids importing the model runner.
    monkeypatch.setitem(sys.modules,'gateway.run',approval_presentation())
    from hermes_cli.dashboard_auth.base import Session
    verified=Session(user_id='owner',provider='basic',issuer='',email='',display_name='',org_id='',
                     expires_at=int(time.time())+120,access_token='synthetic-token',refresh_token='')
    del client.headers['x-fixture-owner']
    @app.middleware('http')
    async def authenticated(request,call_next):
        request.state.session=verified
        return await call_next(request)
    monkeypatch.syspath_prepend(str(NATIVE))
    from hermes_state import SessionDB
    from hermes_state_runtime import begin_runtime_epoch
    from gateway.session_identity import authenticated_subject
    db = SessionDB(tmp / 'synthetic-native.sqlite')
    db.create_session('native-stored', 'local', user_id='basic:owner')
    epoch = begin_runtime_epoch(db, instance_id='synthetic-instance')
    principal = authenticated_subject({'user_id': 'owner', 'provider': 'basic', 'issuer': ''})
    @contextmanager
    def profile_db(params, **kwargs):
        assert params == {'profile': 'default'}
        yield db
    server = sys.modules['tui_gateway'].server
    server._profile_db = profile_db
    monkeypatch.setattr(api, 'activate', lambda *args: None)
    call_id = reserve(client)
    yield api, client, cfg, db, epoch, principal, call_id
    db.close()


def intent(c, **changes):
    api, client, _, _, _, _, call_id = c
    value = dict(stored_session_id='native-stored', runtime_session_id='runtime',
                 request_id='voice-request', text='Prepare a synthetic draft only', callback=True)
    value.update(changes)
    response = client.post(f'/api/plugins/beacon/mobile/calls/{call_id}/work', json=value)
    assert response.status_code == 200, response.text
    return response.json()


def admit(c, text='Prepare a synthetic draft only', request='voice-request', principal=None, stored='native-stored'):
    from hermes_state_runtime import admit_session_input
    _, _, _, db, epoch, subject, _ = c
    return admit_session_input(db, epoch=epoch, principal_id=principal or subject,
        session_id=stored, request_id=request, payload={'text': text, 'surface': 'voice-live'})


def close(c):
    api, _, _, _, _, _, call_id = c
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute('UPDATE calls SET closed=1 WHERE id=?', (call_id,))


def row(c):
    api = c[0]
    with api.mobile.connect() as conn:
        return dict(conn.execute('SELECT * FROM jobs WHERE call_id=?', (c[-1],)).fetchone())


def notices(c):
    with c[0].mobile.connect() as conn:
        return [dict(r) for r in conn.execute("SELECT * FROM notices WHERE kind='incoming_call'")]


def residency_preparations(c,count=1,consent=False):
    """Only synthetic unbound intents; never insert or infer a native admission."""
    if consent is None:
        response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
            'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'voice-request',
            'text':'Prepare a synthetic draft only'})
        assert response.status_code==200,response.text
        first=response.json()
    else:first=intent(c,callback=consent)
    now=time.time()
    with c[0].mobile.connect() as conn:
        template=dict(conn.execute('SELECT * FROM jobs WHERE id=?',(first['job_id'],)).fetchone())
        fields=list(template);columns=','.join(fields);values=','.join('?' for _ in fields)
        for index in range(count):
            call=c[-1] if index<64 else 'residency-call-'+str(index//64)
            conn.execute('INSERT OR IGNORE INTO calls(id,owner,profile,conversation,created,ended,closed,status) VALUES(?,?,?,?,?,?,1,?)',
                         (call,'owner','default','native-stored',now-600,now-600,'saved'))
            if index:
                job={**template,'id':'residency-job-'+str(index),'request':'residency-request-'+str(index),'call_id':call}
                conn.execute('INSERT INTO jobs('+columns+') VALUES('+values+')',[job[f] for f in fields])
        conn.execute('UPDATE calls SET closed=1,created=?,ended=? WHERE owner=?',(now-600,now-600,'owner'))
        conn.execute('UPDATE jobs SET history_updated=? WHERE owner=?',(now-600,'owner'))
    return first


def test_residency_red_old_missing_preparations_do_not_exhaust_active_capacity(continuation):
    c=continuation;residency_preparations(c,512)
    module=sys.modules['beacon_voice_continuation'];module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    fresh=reserve(c[1])
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{fresh}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'new-live-work','text':'Synthetic new work'})
    assert response.status_code==200,response.text
    with c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE parked=1').fetchone()[0]==512
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE call_id=?',(c[-1],)).fetchone()[0]==64


def test_residency_red_missing_old_rows_do_not_starve_later_committed_result(continuation):
    c=continuation;residency_preparations(c,512)
    with c[0].mobile.connect() as conn:
        template=dict(conn.execute('SELECT * FROM jobs LIMIT 1').fetchone())
        job={**template,'id':'later-real-job','request':'later-real-request'}
        conn.execute('INSERT INTO jobs('+','.join(job)+') VALUES('+','.join('?' for _ in job)+')',list(job.values()))
    accepted=admit(c,request='later-real-request');settle(c)
    module=sys.modules['beacon_voice_continuation']
    for _ in range(2):module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    with c[0].mobile.connect() as conn:
        actual=dict(conn.execute('SELECT * FROM jobs WHERE id=?',('later-real-job',)).fetchone())
    assert actual['status']=='completed' and actual['admission']==accepted['admission_id']


@pytest.mark.parametrize('boundary',['open','recent_close','recent_intent','legacy_row','admission_id','unavailable','foreign','retired'])
def test_residency_parking_requires_closed_aged_unbound_verified_preparation(continuation,monkeypatch,boundary):
    c=continuation;residency_preparations(c);module=sys.modules['beacon_voice_continuation']
    with c[0].mobile.connect() as conn:
        before=dict(conn.execute('SELECT * FROM jobs').fetchone())
        if boundary=='open':conn.execute('UPDATE calls SET closed=0')
        elif boundary=='recent_close':conn.execute('UPDATE calls SET ended=?',(time.time(),))
        elif boundary=='recent_intent':conn.execute('UPDATE jobs SET history_updated=?',(time.time(),))
        elif boundary=='legacy_row':conn.execute('UPDATE jobs SET seq=17')
        elif boundary=='admission_id':conn.execute("UPDATE jobs SET admission='unconfirmed-admission'")
        before=dict(conn.execute('SELECT * FROM jobs').fetchone())
    def unavailable(*args):raise RuntimeError('Synthetic unavailable store')
    if boundary=='unavailable':monkeypatch.setattr(module,'native_receipt',unavailable)
    elif boundary in ('foreign','retired'):
        def invalid(*args):raise LookupError('Synthetic native ownership unavailable')
        monkeypatch.setattr(c[0].native,'verify',invalid)
    module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    actual=row(c)
    assert actual['parked']==0 and actual['status']==before['status']
    assert actual['history_updated']==before['history_updated'] and actual['native_probe']>before['native_probe']
    assert not notices(c)


def test_residency_exact_parked_identity_replays_unconfirmed_without_submission(continuation):
    c=continuation;first=residency_preparations(c,consent=True)
    before=row(c);module=sys.modules['beacon_voice_continuation']
    module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    after=row(c);replay=intent(c)
    assert after['parked']==1 and after['status']=='awaiting_admission'
    assert all(after[k]==v for k,v in before.items() if k not in ('parked','native_probe'))
    assert replay['job_id']==first['job_id'] and replay['submit'] is False and replay['status']=='submission_unconfirmed'
    assert not notices(c)


def test_residency_archive_limit_is_backpressure_not_eviction(continuation):
    c=continuation;residency_preparations(c,2049)
    with c[0].mobile.connect() as conn:
        conn.execute('UPDATE jobs SET parked=1 WHERE request<>?',('voice-request',))
    module=sys.modules['beacon_voice_continuation'];module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    with c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs WHERE parked=1').fetchone()[0]==2048
        assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==2049
        assert conn.execute('SELECT parked FROM jobs WHERE request=?',('voice-request',)).fetchone()[0]==0
    fresh=reserve(c[1]);response=c[1].post(f'/api/plugins/beacon/mobile/calls/{fresh}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'blocked-new','text':'Synthetic work'})
    assert response.status_code==409 and 'archive capacity' in response.text
    assert intent(c,callback=False)['submit'] is False


def test_residency_accepted_unknown_and_legacy_link_reactivate_without_age_settlement(continuation):
    c=continuation;residency_preparations(c)
    module=sys.modules['beacon_voice_continuation'];module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    assert row(c)['parked']==1
    from hermes_state_runtime import claim_session_input,begin_runtime_epoch,recover_session_inputs,get_session_admission
    accepted=admit(c);claim_session_input(c[3],epoch=c[4],session_id='native-stored')
    current=begin_runtime_epoch(c[3],instance_id='residency-restart');recover_session_inputs(c[3],epoch=current)
    module.sync(c[0].mobile,c[0].native,'owner','basic','default')
    actual=row(c)
    assert actual['status']=='unknown' and actual['parked']==0 and actual['admission']==accepted['admission_id']
    assert get_session_admission(c[3],admission_id=accepted['admission_id'])['status']=='unknown'
    with c[0].mobile.connect() as conn:
        conn.execute("UPDATE jobs SET parked=1,status='awaiting_admission',admission='' WHERE id=?",(actual['id'],))
        conn.execute("UPDATE jobs SET seq=25,status='watching',epoch='synthetic-legacy' WHERE id=?",(actual['id'],))
        assert conn.execute('SELECT parked FROM jobs WHERE id=?',(actual['id'],)).fetchone()[0]==0
    assert not notices(c)


def settle(c, outcome='completed', claimed=None):
    from hermes_state_runtime import claim_session_input, settle_session_input
    db, epoch = c[3:5]
    claimed = claimed or claim_session_input(db, epoch=epoch, session_id='native-stored')
    assert claimed is not None
    # A native result commits in the SAME settlement as the exact admission outcome.
    return settle_session_input(db, epoch=epoch, admission_id=claimed['admission_id'],
        generation=claimed['generation'], outcome=outcome,
        result={'result': {'final_response': 'Synthetic draft ready', 'completed': outcome == 'completed'}, 'usage': {}})


def test_intent_immediate_hangup_after_durable_native_receipt(continuation):
    c = continuation
    receipt = intent(c)
    accepted = admit(c)
    close(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['admission'] == accepted['admission_id']
    assert row(c)['status'] == 'queued'
    assert receipt['status'] == 'prepared' and not notices(c)


def test_intent_delayed_linkage_after_closed_call(continuation):
    c = continuation
    intent(c)
    close(c)
    accepted = admit(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['admission'] == accepted['admission_id']
    assert row(c)['seq'] is None  # an admission sequence is never invented as a native user row


def test_intent_queued_behind_another_native_turn_settles_only_its_own_admission(continuation):
    from hermes_state_runtime import claim_session_input, get_session_admission
    c = continuation
    first = admit(c, request='earlier-request', text='Earlier native work')
    claimed = claim_session_input(c[3], epoch=c[4], session_id='native-stored')
    intent(c)
    accepted = admit(c)
    close(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['admission'] == accepted['admission_id'] and row(c)['status'] == 'queued'
    settle(c, claimed=claimed)
    c[0].watch('owner', 'basic', 'default')
    assert not notices(c) and get_session_admission(c[3], admission_id=first['admission_id'])['status'] == 'terminal'
    settle(c)
    c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1 and row(c)['admission'] == accepted['admission_id']


def test_intent_closed_call_cannot_admit_new_work(continuation):
    c = continuation
    close(c)
    response = c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work', json={
        'request_id': 'new', 'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime', 'text': 'No work'})
    assert response.status_code == 409
    with c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0] == 0


def test_approval_pending_survives_hangup_without_approval_or_completion(continuation):
    from hermes_state_runtime import claim_session_input, get_session_admission
    c = continuation
    intent(c)
    accepted = admit(c)
    claimed = claim_session_input(c[3], epoch=c[4], session_id='native-stored')
    from gateway.session_pending_controls import PendingControls
    published = []
    controls = PendingControls(SimpleNamespace(lock=threading.RLock(), publish=lambda *a, **k: published.append((a, k))))
    controls.register('native-stored', 'synthetic-route', claimed['generation'],
        {'request_id': 'draft-approval', 'command': 'Prepare synthetic draft', 'allow_session': False, 'allow_permanent': False})
    approved = []
    controls.remote_responders['draft-approval'] = lambda *args: approved.append(args)
    close(c)
    c[0].watch('owner', 'basic', 'default')
    actual = get_session_admission(c[3], admission_id=accepted['admission_id'])
    assert actual['status'] == 'started' and actual['generation'] == claimed['generation']
    assert row(c)['status'] == 'watching' and not notices(c)
    assert actual['target_session_id'] == 'native-stored'
    pending = controls.snapshot('native-stored', claimed['generation'])
    assert pending[0]['prompt_id'] == 'draft-approval' and pending[0]['choices'] == ['once', 'deny']
    assert approved == []
    # Synthetic chat action uses the native approval queue, with its exact prompt and generation.
    result = controls.respond('native-stored', claimed['generation'], 'draft-approval', {'choice': 'once'})
    assert result['status'] == 'resolved' and len(approved) == 1
    assert controls.respond('native-stored', claimed['generation'], 'draft-approval', {'choice': 'once'})['status'] == 'already_resolved'
    settle(c, claimed=claimed)
    c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1


def test_approval_approved_draft_continues_to_exact_verified_completion(continuation):
    c = continuation
    intent(c)
    admit(c)
    close(c)
    settle(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'callback_pending'
    assert row(c)['result'] == 'Synthetic draft ready'
    assert len(notices(c)) == 1 and notices(c)[0]['conversation'] == 'native-stored'


def test_dedup_reconnect_close_and_restart_never_replay_work_or_callback(continuation):
    c = continuation
    first = intent(c)
    assert first['submit'] is True
    accepted = admit(c)
    replay = intent(c)
    assert replay['job_id'] == first['job_id'] and replay['submit'] is False
    close(c)
    assert intent(c)['job_id'] == first['job_id']
    assert admit(c)['admission_id'] == accepted['admission_id']
    settle(c)
    # Reload just our continuation observer as a plugin restart would. Durable native state owns the answer.
    module = sys.modules['beacon_voice_continuation']
    spec = importlib.util.spec_from_file_location('beacon_voice_continuation', module.__file__)
    replacement = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(replacement)
    sys.modules['beacon_voice_continuation'] = replacement
    for _ in range(3): c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1
    from hermes_state_runtime import list_session_admissions
    assert len(list_session_admissions(c[3], session_id='native-stored', pending_only=False)) == 1


def test_dedup_conflicting_payload_or_callback_is_refused(continuation):
    c = continuation
    intent(c)
    for changes in ({'text': 'Different task'}, {'runtime_session_id': 'different'}, {'callback': False}):
        value = dict(stored_session_id='native-stored', runtime_session_id='runtime', request_id='voice-request',
                     text='Prepare a synthetic draft only', callback=True)
        value.update(changes)
        response = c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work', json=value)
        assert response.status_code == 409


def test_dedup_queued_native_admission_survives_runtime_epoch_restart(continuation):
    from hermes_state_runtime import begin_runtime_epoch, recover_session_inputs, list_session_admissions
    c = continuation
    intent(c)
    accepted = admit(c)
    close(c)
    epoch = begin_runtime_epoch(c[3], instance_id='queued-restart')
    recover_session_inputs(c[3], epoch=epoch)
    restarted = (*c[:4], epoch, *c[5:])
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert row(c)['admission'] == accepted['admission_id'] and row(c)['status'] == 'queued'
    assert len(list_session_admissions(c[3], session_id='native-stored', pending_only=False)) == 1
    settle(restarted)
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1


@pytest.mark.parametrize('boundary', ['owner', 'provider', 'profile', 'conversation', 'principal', 'payload'])
def test_isolation_rejects_foreign_receipt(continuation, boundary):
    c = continuation
    intent(c)
    if boundary in ('owner', 'profile'):
        response = c[1].get(f'/api/plugins/beacon/mobile/calls/{c[-1]}',
            headers={'x-fixture-owner': 'foreign'} if boundary == 'owner' else {},
            params={'profile': 'foreign'} if boundary == 'profile' else {})
        assert response.status_code == 403
    elif boundary != 'provider':
        if boundary == 'conversation': c[3].create_session('foreign-conversation', 'local', user_id='basic:owner')
        accepted = admit(c, principal='foreign-subject' if boundary == 'principal' else None,
                         text='Different task' if boundary == 'payload' else 'Prepare a synthetic draft only',
                         stored='foreign-conversation' if boundary == 'conversation' else 'native-stored')
    close(c)
    c[0].watch('foreign' if boundary == 'owner' else 'owner', 'foreign' if boundary == 'provider' else 'basic', 'foreign' if boundary == 'profile' else 'default')
    assert not notices(c)
    assert row(c)['admission'] == ''


@pytest.mark.parametrize('outcome', ['failed', 'interrupted', 'unknown', 'missing'])
def test_terminal_failure_unknown_or_missing_never_callback_or_resubmit(continuation, outcome):
    c = continuation
    intent(c)
    if outcome != 'missing':
        accepted = admit(c)
        if outcome == 'unknown':
            from hermes_state_runtime import claim_session_input, get_session_admission, begin_runtime_epoch, recover_session_inputs
            claim_session_input(c[3], epoch=c[4], session_id='native-stored')
            new_epoch = begin_runtime_epoch(c[3], instance_id='synthetic-restart')
            recover_session_inputs(c[3], epoch=new_epoch)
            assert get_session_admission(c[3], admission_id=accepted['admission_id'])['status'] == 'unknown'
        else: settle(c, outcome)
    close(c)
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert not notices(c)
    assert row(c)['status'] == {'failed': 'failed', 'interrupted': 'cancelled', 'unknown': 'unknown', 'missing': 'awaiting_admission'}[outcome]
    replay = intent(c)
    assert replay['submit'] is False and replay.get('callback') != 'scheduled'


def test_terminal_explicit_cancel_is_separate_from_audio_close(continuation):
    from hermes_state_runtime import cancel_session_input
    c = continuation
    intent(c)
    accepted = admit(c)
    close(c)
    assert row(c)['status'] == 'awaiting_admission'
    cancel_session_input(c[3], epoch=c[4], admission_id=accepted['admission_id'])
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'cancelled' and not notices(c)


def test_terminal_completed_ledger_without_committed_result_is_not_completion_proof(continuation):
    from hermes_state_runtime import claim_session_input, settle_session_input
    c = continuation
    intent(c)
    admit(c)
    claimed = claim_session_input(c[3], epoch=c[4], session_id='native-stored')
    settle_session_input(c[3], epoch=c[4], admission_id=claimed['admission_id'],
        generation=claimed['generation'], outcome='completed')
    close(c)
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'unknown' and not notices(c)


def test_dedup_callback_added_later_does_not_change_original_intent(continuation):
    c = continuation
    prepared = intent(c, callback=False)
    admit(c)
    response = c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/callback', json={'job_id': prepared['job_id']})
    assert response.status_code == 200
    close(c)
    assert intent(c, callback=False)['job_id'] == prepared['job_id']
    settle(c)
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1


def test_terminal_no_callback_without_owner_request(continuation):
    c = continuation
    intent(c, callback=False)
    admit(c)
    close(c)
    settle(c)
    for _ in range(2): c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'completed' and not notices(c)


def test_isolation_completed_callback_cannot_cross_provider_after_account_change(continuation):
    c = continuation
    intent(c)
    admit(c)
    settle(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['status'] == 'completed' and not notices(c)
    close(c)
    c[0].watch('owner', 'foreign-provider', 'default')
    assert row(c)['status'] == 'completed' and not notices(c)
    c[0].watch('owner', 'basic', 'default')
    assert len(notices(c)) == 1


def test_intent_legacy_native_receipt_links_after_hangup(continuation, monkeypatch):
    c = continuation
    intent(c)
    close(c)
    from test_job_watch import install_replay
    install_replay(monkeypatch, [])
    monkeypatch.setattr(c[0].native, 'verify', lambda *args: {'messages': [
        {'role': 'user', 'row_id': 123, 'text': 'Prepare a synthetic draft only'}]})
    response = c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'voice-request', 'title': 'Synthetic work', 'user_row_id': 123, 'callback': True})
    assert response.status_code == 200, response.text
    assert row(c)['seq'] == 123 and row(c)['epoch'] == 'epoch-one'


def test_intent_native_image_failure_notice_preserves_input_identity(continuation):
    c = continuation
    intent(c)
    accepted = admit(c, text='Prepare a synthetic draft only\n[The current shared image could not be attached.]')
    close(c)
    c[0].watch('owner', 'basic', 'default')
    assert row(c)['admission'] == accepted['admission_id'] and row(c)['status'] == 'queued'


def test_intent_canonical_receipt_does_not_spend_legacy_row_capacity(continuation):
    c = continuation
    with c[0].mobile.connect() as conn:
        conn.executemany('''INSERT INTO jobs(id,call_id,owner,profile,stored,runtime,request,title,callback,epoch,seq,status)
                            VALUES(?,?,'owner','default','native-stored','runtime',?,'Synthetic legacy work',0,'legacy',?,'completed')''',
                         [('legacy-'+str(i),c[-1],'legacy-request-'+str(i),i+1) for i in range(64)])
    assert intent(c)['submit'] is True
    with c[0].mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM jobs WHERE submission_digest<>''").fetchone()[0] == 1


def test_isolation_legacy_link_requires_the_preparing_principal(continuation, monkeypatch):
    c = continuation
    intent(c)
    close(c)
    from test_job_watch import install_replay
    install_replay(monkeypatch, [])
    monkeypatch.setattr(c[0].native, 'verify', lambda *args: {'messages': [
        {'role': 'user', 'row_id': 123, 'text': 'Prepare a synthetic draft only'}]})
    monkeypatch.setattr(c[0], 'voice_principal', lambda *args: 'different-issuer-principal')
    response = c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/jobs', json={
        'stored_session_id': 'native-stored', 'runtime_session_id': 'runtime',
        'request_id': 'voice-request', 'title': 'Synthetic work', 'user_row_id': 123, 'callback': True})
    assert response.status_code == 403
    assert row(c)['seq'] is None and row(c)['status'] == 'awaiting_admission' and not notices(c)


@pytest.mark.parametrize('boundary',['conversation','owner','profile'])
def test_adaptive_runtime_initial_admission_must_verify_runtime_binding(continuation,monkeypatch,boundary):
    c=continuation
    server=sys.modules['tui_gateway'].server
    server._profile_home=lambda profile:'synthetic-profile-home'
    server._session_auth_user_id=lambda record:record['user_id']
    c[3].create_session('other-stored','local',user_id='basic:owner')
    server._sessions['runtime']={'session_key':'other-stored' if boundary=='conversation' else 'native-stored',
        'profile_home':'other-profile' if boundary=='profile' else 'synthetic-profile-home',
        'user_id':'basic:foreign' if boundary=='owner' else 'basic:owner'}
    spec=importlib.util.spec_from_file_location('adaptive_native_contract',c[0].ROOT/'native.py')
    native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
    monkeypatch.setattr(c[0].native,'verify',native.verify)
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'voice-request',
        'text':'Prepare a synthetic draft only','callback':False})
    assert response.status_code in (403,404,409),response.text
    with c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0


def test_adaptive_runtime_exact_replay_survives_runtime_restart(continuation,monkeypatch):
    c=continuation
    first=intent(c)
    close(c)
    original=c[0].native.verify
    def verify(owner,provider,profile,stored,runtime=None,history=True,principal=None):
        assert runtime is None,'Receipt replay must not require the old runtime'
        assert principal==c[5]
        return original(owner,provider,profile,stored)
    monkeypatch.setattr(c[0].native,'verify',verify)
    replay=intent(c)
    assert replay['job_id']==first['job_id'] and replay['submit'] is False


def inline_receipt(c,status='running',session='native-stored'):
    gateway=sys.modules['beacon_gateway_tools']
    receipt={'request_id':'inline-original','tool_id':'a'*64,'tool_name':'synthetic_action','status':status,
             '_principal':c[5],
             '_session_id':session,'_boot':gateway.BOOT,'_started':time.time(),'_expires':time.time()+120,
             '_arguments':{'query':'synthetic'},'_read_only':False}
    if status=='completed': receipt['result']={'draft':'Synthetic completed draft'}
    with c[0].mobile.connect() as conn:
        conn.execute('INSERT INTO tool_receipts VALUES(?,?,?,?,?,?,?,?)',
                     ('owner','basic','default',c[-1],'inline-original','b'*64,json.dumps(receipt),time.time()))
    return receipt


def follow(c,callback=False,request='follow-once'):
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':request,
        'tool_request_id':'inline-original','callback':callback})
    assert response.status_code==200,response.text
    return response.json()


def test_adaptive_follow_running_receipt_survives_hangup_without_dispatch(continuation,monkeypatch):
    c=continuation
    source=inline_receipt(c)
    monkeypatch.setattr(sys.modules['beacon_gateway_tools'].Gateway,'_start',lambda *a,**k:pytest.fail('An admitted action must not be submitted again'))
    first=follow(c,callback=True)
    close(c)
    c[0].watch('owner','basic','default')
    assert not notices(c)
    source.update(status='completed',result={'draft':'Synthetic completed draft'})
    with c[0].mobile.connect() as conn:
        conn.execute('UPDATE tool_receipts SET payload=? WHERE request_id=?',(json.dumps(source),'inline-original'))
    for _ in range(3): c[0].watch('owner','basic','default')
    assert row(c)['id']==first['job_id'] and row(c)['status']=='callback_pending'
    assert len(notices(c))==1
    from hermes_state_runtime import list_session_admissions
    assert list_session_admissions(c[3],session_id='native-stored',pending_only=False)==[]
    with c[3]._read_ctx() as conn:
        notes=conn.execute("SELECT content FROM messages WHERE display_kind='voice_work_receipt'").fetchall()
    assert sum('Synthetic completed draft' in r[0] for r in notes)==1


@pytest.mark.parametrize('consent',[None,False,True])
def test_adaptive_consent_never_inferred_from_hangup_or_overrun(continuation,consent):
    c=continuation
    inline_receipt(c,'completed')
    prepared=follow(c,callback=consent is True)
    close(c)
    for _ in range(2): c[0].watch('owner','basic','default')
    assert row(c)['id']==prepared['job_id']
    assert len(notices(c))==int(consent is True)


def test_adaptive_consent_offer_accept_and_decline_are_separate_from_work(continuation):
    c=continuation
    inline_receipt(c,'completed')
    prepared=follow(c)
    assert row(c)['callback']==0
    path=f'/api/plugins/beacon/mobile/calls/{c[-1]}/callback'
    assert c[1].post(path,json={'job_id':prepared['job_id'],'enabled':True}).json()['subscribed'] is True
    assert c[1].post(path,json={'job_id':prepared['job_id'],'enabled':False}).json()['subscribed'] is False
    close(c);c[0].watch('owner','basic','default')
    assert row(c)['status']=='completed' and not notices(c)


@pytest.mark.parametrize('state',['failed','denied','expired','cancelled','uncertain'])
def test_adaptive_follow_failure_or_unknown_never_becomes_callback_success(continuation,state):
    c=continuation
    inline_receipt(c,state)
    follow(c,callback=True)
    close(c)
    for _ in range(2): c[0].watch('owner','basic','default')
    assert not notices(c)
    assert row(c)['status'] in ('failed','cancelled','unknown')


def test_adaptive_follow_reconnect_is_exactly_once_and_foreign_session_is_refused(continuation):
    c=continuation
    inline_receipt(c)
    first=follow(c)
    assert follow(c)['job_id']==first['job_id']
    close(c)
    assert follow(c)['job_id']==first['job_id']
    with c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==1


@pytest.fixture
def native_contract(tmp_path,monkeypatch):
    monkeypatch.setenv('HERMES_HOME',str(tmp_path))
    monkeypatch.syspath_prepend(str(NATIVE))
    from gateway.session_authority import SessionAuthority,LiveSession
    from gateway import session_contract
    from gateway.session_contract import SessionRef
    Principal=getattr(session_contract,'SessionActor',None) or session_contract.Principal
    from gateway.session import SessionSource
    from gateway.config import Platform
    from gateway.session_ingress import viewer_turn,executing_admission
    import gateway.run_api
    # Native approval state is real; its display formatter must not import the model runner or owner .env.
    monkeypatch.setitem(sys.modules,'gateway.run',approval_presentation())
    return SimpleNamespace(**locals())


@pytest.fixture
def transfer_case(native_contract,continuation,monkeypatch):
    c=continuation;n=native_contract
    authority=n.SessionAuthority(SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False)),
        profile_id=str(c[0].mobile.home()),instance_id='synthetic-owner',db=c[3],epoch=c[4])
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),session_authority=authority,session_authorities=None,
                           _adapter_for_source=lambda source:None,_draining=False)
    authority.runner=runner
    live=n.LiveSession(n.SessionSource(platform=n.Platform.LOCAL,chat_id='native-stored',user_id=c[5],chat_type='dm'),'synthetic-native-route')
    authority.sessions['native-stored']=live
    subject=c[0].native.execution_subject('owner','basic',c[5])
    actor=n.Principal(subject,authority.profile_id,frozenset({'session:read','session:submit','session:approve','session:respond','session:control'}),'owner-transport')
    live.subscribers['owner-subscription']=actor
    monkeypatch.setattr(sys.modules['gateway.run_api'],'_ACTIVE_RUNNER',runner)
    server=sys.modules['tui_gateway'].server
    server._hermes_home=c[0].mobile.home();server._profile_home=lambda profile:None
    from test_gateway_tools import Backend
    backend=Backend();backend.read_only=False
    dispatcher=sys.modules['beacon_gateway_tools'].Gateway(c[0].mobile,lambda profile,fn:fn(c[0].mobile.home()),backend)
    module=sys.modules['beacon_voice_continuation']
    monkeypatch.setattr(module,'gateway',lambda mobile:dispatcher)
    from tools.registry import registry
    module.register(SimpleNamespace(register_tool=lambda **kw:registry.register(scope=str(c[0].mobile.home()),**kw),
        register_hook=lambda *args:None),c[0].mobile,c[0].native)
    receipt=dispatcher.invoke(('owner','basic','default',c[-1]),
        {'request_id':'inline-original','tool_id':'a'*64,'arguments':{'query':'synthetic'},'approval':'ask'},'native-stored',principal=c[5])
    assert receipt['status']=='needs_approval'
    return SimpleNamespace(c=c,n=n,authority=authority,live=live,actor=actor,backend=backend,dispatcher=dispatcher,module=module,receipt=receipt)


def native_claim(t,callback=True):
    from hermes_state_runtime import admit_session_input,claim_session_input
    prepared=follow(t.c,callback=callback)
    assert prepared['native_transfer'] is True
    row=admit_session_input(t.c[3],epoch=t.c[4],principal_id=t.actor.subject,session_id='native-stored',
        request_id=prepared['request_id'],payload={'text':prepared['text']})
    claimed=claim_session_input(t.c[3],epoch=t.c[4],session_id='native-stored')
    assert claimed['admission_id']==row['admission_id']
    t.authority.executing['native-stored']=claimed['generation']
    t.claimed=claimed;t.prepared=prepared
    return prepared


def native_handler(t):
    tokens=(t.n.viewer_turn.set(t.live),t.n.executing_admission.set(True))
    result=[]
    try:
        context=contextvars.copy_context()
        thread=threading.Thread(target=lambda:result.append(context.run(t.module.continue_tool,t.c[0].mobile,t.c[0].native,
            {'job_id':t.prepared['job_id']},'native-stored')),daemon=True)
        thread.start()
    finally:
        t.n.viewer_turn.reset(tokens[0]);t.n.executing_admission.reset(tokens[1])
    deadline=time.time()+4
    while time.time()<deadline and not t.live.controls.pending and thread.is_alive(): time.sleep(.01)
    return thread,result


@pytest.mark.parametrize('choice',['once','deny'])
def test_adaptive_transfer_native_approve_deny_settles_original_exactly_once(transfer_case,choice):
    t=transfer_case
    native_claim(t);close(t.c)
    tokens=(t.n.viewer_turn.set(t.live),t.n.executing_admission.set(True))
    try: assert t.module.native_binding(t.c[0].mobile,t.prepared['job_id'],'native-stored')
    finally:
        t.n.viewer_turn.reset(tokens[0]);t.n.executing_admission.reset(tokens[1])
    # The old HTTP proposal can no longer approve or dispatch this operation.
    assert t.dispatcher.decide(('owner','basic','default',t.c[-1]),'inline-original',
        {'proposal_id':t.receipt['proposal']['id'],'decision':'approve'})['status']=='transferred'
    assert not t.backend.calls
    thread,result=native_handler(t)
    assert t.live.controls.pending, result
    prompt=t.receipt['proposal']['id']
    pending=t.live.controls.snapshot('native-stored',t.claimed['generation'])
    assert pending[0]['prompt_id']==prompt and pending[0]['choices']==['once','deny']
    foreign=t.n.Principal('foreign-principal',t.authority.profile_id,t.actor.capabilities,'foreign-transport')
    with pytest.raises(ValueError):
        asyncio.run(t.authority.respond(foreign,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],prompt,{'choice':'once'}))
    response=asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],prompt,{'choice':choice}))
    assert response['status']=='resolved'
    thread.join(5);assert not thread.is_alive()
    assert len(t.backend.calls)==int(choice=='once'),result
    # An actor replay, or a helper replay, cannot dispatch the action twice.
    assert asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],prompt,{'choice':choice}))['status']=='already_resolved'
    t.c[0].watch('owner','basic','default');assert not notices(t.c)
    settle(t.c,claimed=t.claimed)
    for _ in range(2):t.c[0].watch('owner','basic','default')
    assert len(notices(t.c))==int(choice=='once')


def test_adaptive_transfer_guard_restricts_real_turn_not_model_text(transfer_case):
    t=transfer_case;native_claim(t)
    tokens=t.n.viewer_turn.set(t.live),t.n.executing_admission.set(True)
    try:
        assert t.module.guard(t.c[0].mobile,tool_name='terminal',args={'command':'synthetic'},session_id='native-stored')['block']
        assert t.module.guard(t.c[0].mobile,tool_name='beacon_call_user',args={},session_id='native-stored')['block']
        assert t.module.guard(t.c[0].mobile,tool_name='beacon_continue_call_work',args={'job_id':t.prepared['job_id']},session_id='native-stored') is None
        assert t.module.guard(t.c[0].mobile,tool_name='beacon_continue_call_work',args={'job_id':'foreign-job'},session_id='native-stored')['block']
    finally:t.n.viewer_turn.reset(tokens[0]);t.n.executing_admission.reset(tokens[1])
    assert t.module.guard(t.c[0].mobile,tool_name='terminal',args={},session_id='native-stored')['block']
    assert t.module.guard(t.c[0].mobile,tool_name='beacon_continue_call_work',args={'job_id':t.prepared['job_id']},session_id='native-stored')['block']
    settle(t.c,claimed=t.claimed)
    assert t.module.guard(t.c[0].mobile,tool_name='future_dynamic_tool',args={},session_id='native-stored') is None


def test_adaptive_transfer_expiry_and_schema_change_never_dispatch(transfer_case):
    t=transfer_case;native_claim(t)
    thread,result=native_handler(t)
    assert t.live.controls.pending,result
    t.backend.version='c'
    prompt=t.receipt['proposal']['id']
    asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],prompt,{'choice':'once'}))
    thread.join(5);assert not thread.is_alive()
    assert not t.backend.calls
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='failed'


def test_adaptive_transfer_narration_without_original_result_is_not_success(transfer_case):
    t=transfer_case;native_claim(t);close(t.c)
    settle(t.c,claimed=t.claimed)
    for _ in range(2):t.c[0].watch('owner','basic','default')
    assert not notices(t.c) and not t.backend.calls
    assert row(t.c)['status']=='unknown'


def test_adaptive_runtime_canonical_mount_uses_actual_owner_after_sidecar_restart(transfer_case,monkeypatch):
    t=transfer_case;c=t.c
    spec=importlib.util.spec_from_file_location('adaptive_canonical_verify',c[0].ROOT/'native.py')
    native=importlib.util.module_from_spec(spec);spec.loader.exec_module(native)
    monkeypatch.setattr(c[0].native,'verify',native.verify)
    sys.modules['tui_gateway'].server._sessions.clear()
    c[3]._execute_write(lambda conn:conn.execute("UPDATE sessions SET user_id=? WHERE id='native-stored'",(c[5],)))
    t.authority.events[t.actor.transport_id]=SimpleNamespace(auth_identity={'provider':'basic','issuer':'','user_id':'owner'})
    runner=t.authority.runner
    runner.session_runtime_descriptor={'state':'ready','instance_id':t.authority.instance_id,'authority_epoch':t.authority.epoch}
    c[1].app.state.gateway_runner=runner;c[1].app.state.session_authority=t.authority
    transport=sys.modules['beacon_canonical_transport']
    monkeypatch.setattr(transport,'verify_live_session',lambda request,p,s,r:
                        transport.canonical_live_proof(request,p,c[0].mobile.home(),s,r,'a'*64))
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'native-stored','request_id':'canonical-runtime',
        'text':'Synthetic quick protected action','callback':False})
    assert response.status_code==200,response.text
    t.live.subscribers.clear()
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'native-stored','request_id':'unbound-runtime',
        'text':'Must not be admitted','callback':False})
    assert response.status_code in (403,404,409)


def test_adaptive_transfer_expiry_rejects_late_native_and_http_decisions(transfer_case):
    t=transfer_case
    with t.c[0].mobile.connect() as conn:
        source=json.loads(conn.execute('SELECT payload FROM tool_receipts').fetchone()[0])
        source['_expires']=time.time()+.7
        conn.execute('UPDATE tool_receipts SET payload=?',(json.dumps(source),))
    native_claim(t);close(t.c)
    thread,result=native_handler(t)
    assert t.live.controls.pending,result
    thread.join(3);assert not thread.is_alive()
    assert not t.backend.calls
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='expired'
    assert not t.live.controls.pending
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/tools/requests/inline-original/decision',
        json={'proposal_id':t.receipt['proposal']['id'],'decision':'approve'})
    assert response.status_code==409


def test_adaptive_transfer_duplicate_helpers_and_wrong_scope_cannot_dispatch(transfer_case):
    t=transfer_case;native_claim(t)
    thread,result=native_handler(t)
    assert t.live.controls.pending,result
    tokens=t.n.viewer_turn.set(t.live),t.n.executing_admission.set(True)
    try:
        assert json.loads(t.module.continue_tool(t.c[0].mobile,t.c[0].native,{'job_id':t.prepared['job_id']},'foreign-session'))['status']=='failed'
        assert json.loads(t.module.continue_tool(t.c[0].mobile,t.c[0].native,{'job_id':t.prepared['job_id']},'native-stored'))['status']=='failed'
    finally:t.n.viewer_turn.reset(tokens[0]);t.n.executing_admission.reset(tokens[1])
    assert len(t.live.controls.pending)==1 and not t.backend.calls
    prompt=t.receipt['proposal']['id']
    asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],prompt,{'choice':'deny'}))
    thread.join(3);assert not thread.is_alive()
    assert not t.backend.calls


def test_adaptive_native_registration_is_bounded_and_internal(continuation):
    module=sys.modules['beacon_voice_continuation'];calls=[]
    ctx=SimpleNamespace(register_tool=lambda **kw:calls.append(('tool',kw)),register_hook=lambda *args:calls.append(('hook',args)))
    started=time.perf_counter();module.register(ctx,continuation[0].mobile,continuation[0].native)
    assert time.perf_counter()-started<10
    assert calls[0][1]['name']=='beacon_continue_call_work'
    assert 'beacon_continue_call_work' in sys.modules['beacon_gateway_tools'].NATIVE_EXCLUDED_TOOLS


def test_adaptive_consent_foreign_principal_cannot_change_callback(continuation,monkeypatch):
    c=continuation;inline_receipt(c)
    prepared=follow(c)
    monkeypatch.setattr(c[0],'voice_principal',lambda *a:'foreign-principal')
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/callback',json={'job_id':prepared['job_id'],'enabled':True})
    assert response.status_code==403 and row(c)['callback']==0


def test_adaptive_transfer_original_dispatch_guard_is_exact_and_single_use(transfer_case):
    t=transfer_case;native_claim(t);close(t.c)
    execute=t.backend.execute;checks=[]
    def guarded(tool,args,scope,approved,session_id=None):
        assert t.module.guard(t.c[0].mobile,tool_name='unrelated_tool',args={},session_id=session_id)['block']
        assert t.module.guard(t.c[0].mobile,tool_name=tool['name'],args=args,session_id=session_id) is None
        assert t.module.guard(t.c[0].mobile,tool_name=tool['name'],args=args,session_id=session_id)['block']
        checks.append(True)
        return execute(tool,args,scope,approved,session_id)
    t.backend.execute=guarded
    thread,result=native_handler(t);assert t.live.controls.pending,result
    asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],
        t.receipt['proposal']['id'],{'choice':'once'}))
    thread.join(5);assert not thread.is_alive()
    assert checks==[True] and len(t.backend.calls)==1


def test_adaptive_transfer_wrong_profile_and_changed_receipt_are_rejected(transfer_case):
    t=transfer_case;native_claim(t)
    thread,result=native_handler(t);assert t.live.controls.pending,result
    wrong=t.n.Principal(t.actor.subject,'foreign-profile',t.actor.capabilities,'foreign-profile-transport')
    with pytest.raises(ValueError):
        asyncio.run(t.authority.respond(wrong,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],
            t.receipt['proposal']['id'],{'choice':'once'}))
    with t.c[0].mobile.connect() as conn:
        conn.execute("UPDATE tool_receipts SET signature=? WHERE request_id='inline-original'",('c'*64,))
    asyncio.run(t.authority.respond(t.actor,t.n.SessionRef(t.authority.profile_id,'native-stored'),t.claimed['generation'],
        t.receipt['proposal']['id'],{'choice':'once'}))
    thread.join(5);assert not thread.is_alive()
    assert not t.backend.calls and json.loads(result[0])['status']=='failed'


@pytest.mark.parametrize('provenance',['missing','foreign'])
def test_adaptive_follow_original_principal_is_not_inferred(continuation,provenance):
    c=continuation;receipt=inline_receipt(c)
    if provenance=='missing': receipt.pop('_principal')
    else: receipt['_principal']='same-owner-different-principal'
    with c[0].mobile.connect() as conn:
        conn.execute('UPDATE tool_receipts SET payload=?',(json.dumps(receipt),))
    response=c[1].post(f'/api/plugins/beacon/mobile/calls/{c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'cannot-adopt',
        'tool_request_id':'inline-original','callback':True})
    assert response.status_code==409
    with c[0].mobile.connect() as conn: assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0


def test_adaptive_follow_completed_without_original_result_is_not_proof(continuation):
    c=continuation;receipt=inline_receipt(c,'completed');receipt.pop('result')
    with c[0].mobile.connect() as conn: conn.execute('UPDATE tool_receipts SET payload=?',(json.dumps(receipt),))
    prepared=follow(c,callback=True)
    assert prepared['status']=='uncertain'
    close(c)
    for _ in range(2):c[0].watch('owner','basic','default')
    assert row(c)['status']=='unknown' and not notices(c)


def test_adaptive_transfer_unsupported_owner_process_keeps_original_proposal(transfer_case,monkeypatch):
    t=transfer_case
    from gateway import session_managed_worker
    monkeypatch.setattr(session_managed_worker,'managed_policy',lambda *args:object())
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'unsupported-transfer',
        'tool_request_id':'inline-original','callback':True})
    assert response.status_code==409
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='needs_approval'
    assert not t.backend.calls
    with t.c[0].mobile.connect() as conn: assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0


@pytest.mark.parametrize('consent',[False,True])
def test_adaptive_native_completion_callback_cannot_be_duplicated_inside_work(transfer_case,consent):
    t=transfer_case;c=t.c
    intent(c,callback=consent)
    admit(c)
    from hermes_state_runtime import claim_session_input
    claimed=claim_session_input(c[3],epoch=c[4],session_id='native-stored')
    t.authority.executing['native-stored']=claimed['generation']
    tokens=t.n.viewer_turn.set(t.live),t.n.executing_admission.set(True)
    try:
        assert t.module.guard(c[0].mobile,tool_name='beacon_call_user',args={'reason':'premature'},session_id='native-stored')['block']
        assert t.module.guard(c[0].mobile,tool_name='future_dynamic_tool',args={},session_id='native-stored') is None
        assert 'verified receipt watcher' in t.module.callback_context(c[0].mobile,'native-stored')['context']
    finally:t.n.viewer_turn.reset(tokens[0]);t.n.executing_admission.reset(tokens[1])
    assert t.module.guard(c[0].mobile,tool_name='beacon_call_user',args={},session_id='native-stored') is None


def _owner_followup_server():
    """Separate synthetic owner; real native authority, tickets, approvals and admission ledger."""
    from hermes_state import SessionDB
    from gateway.session_authority import SessionAuthority,LiveSession
    from gateway import session_contract
    from gateway.session_contract import SessionRef
    Principal=getattr(session_contract,'SessionActor',None) or session_contract.Principal
    from gateway.session import SessionSource
    from gateway.config import Platform
    from gateway.runtime_bootstrap import TicketStore
    from gateway.run_api import GatewayRuntimeAPI
    from gateway.session_ingress import viewer_turn,executing_admission
    from hermes_cli.dashboard_auth.native_http import authenticate_native_http,native_profile_scope
    from fastapi import Request
    from tools.registry import registry
    import agent.redact as native_redaction
    import gateway.run_api
    import uvicorn
    import socket
    home=Path(os.environ['HERMES_HOME']);initial=json.loads((home/'owner-input.json').read_text())
    patch=pytest.MonkeyPatch();fixture=harness.__wrapped__(home,patch)
    api,_,owner_cfg,_,app=next(fixture)
    patch.setitem(sys.modules,'agent.redact',native_redaction)
    patch.setitem(sys.modules,'gateway.run',approval_presentation())
    db=SessionDB(home/'synthetic-native.sqlite')
    authority=SessionAuthority(SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False)),
        profile_id=str(home),instance_id='split-synthetic-owner',db=db,epoch=initial['epoch'])
    runner=SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False),session_authority=authority,
        session_authorities=None,_draining=False,_adapter_for_source=lambda source:None,_cached_agent_for=lambda *a:None,
        session_runtime_descriptor={'state':'ready','instance_id':authority.instance_id,
            'served_profiles':[{'profile_id':str(home),'home':str(home)}]},
        session_ticket_store=TicketStore(authority.instance_id,frozenset({str(home)})))
    authority.runner=runner
    live=LiveSession(SessionSource(platform=Platform.LOCAL,chat_id='native-stored',user_id=initial['principal'],chat_type='dm'),'split-route')
    authority.sessions['native-stored']=live
    actor=Principal(api.native.execution_subject('owner','basic',initial['principal']),str(home),frozenset({'session:read','session:submit','session:approve','session:respond','session:control'}),'split-viewer')
    live.subscribers['split-viewer']=actor
    gateway.run_api._ACTIVE_RUNNER=runner
    native_server=sys.modules['tui_gateway'].server
    native_server._hermes_home=home;native_server._profile_home=lambda profile:None
    from test_gateway_tools import Backend
    backend=Backend();backend.read_only=False
    module=sys.modules['beacon_voice_continuation']
    dispatcher=sys.modules['beacon_gateway_tools'].Gateway(api.mobile,lambda profile,fn:fn(home),backend)
    module.gateway=lambda mobile:dispatcher
    module.register(SimpleNamespace(register_tool=lambda **kw:registry.register(scope=str(home),**kw),
        register_hook=lambda *args:None),api.mobile,api.native)
    app.state.gateway_runner=runner;app.state.session_authority=authority
    @app.middleware('http')
    async def native_grant(request:Request,call_next):
        rejection=await authenticate_native_http(request)
        if rejection is not None:return rejection
        with native_profile_scope(request):return await call_next(request)
    listener=socket.socket();listener.bind(('127.0.0.1',0));listener.listen()
    server=uvicorn.Server(uvicorn.Config(GatewayRuntimeAPI(app,runner,app),log_level='critical',lifespan='off',proxy_headers=False))
    service=threading.Thread(target=server.run,kwargs={'sockets':[listener]},daemon=True);service.start()
    deadline=time.monotonic()+8
    while not server.started and time.monotonic()<deadline:time.sleep(.01)
    assert server.started
    controls=socket.socket();controls.bind(('127.0.0.1',0));controls.listen();controls.settimeout(60)
    ready={'origin':'http://127.0.0.1:'+str(listener.getsockname()[1]),
        'control_port':controls.getsockname()[1],'instance_id':authority.instance_id,
        'profile_id':str(home),'epoch':authority.epoch,'pid':os.getpid()}
    staged=home/'owner-ready.tmp'
    staged.write_text(json.dumps(ready),encoding='utf-8')
    os.replace(staged,home/'owner-ready.json')  # publish atomically: an exists() poll never sees a partial file
    worker=None;result=[];claimed=None
    while True:
        connection,_=controls.accept()
        with connection.makefile('rb') as incoming:line=incoming.readline(65536)
        command=json.loads(line);operation=command['operation']
        (home/'owner-stage.json').write_text(json.dumps({'operation':operation,'stage':'entered'}),encoding='utf-8')
        if operation=='stop':connection.sendall(b'{}\n');connection.close();break
        if operation=='mint':
            answer={'ticket':runner.session_ticket_store.mint(profile_id=command.get('profile_id',str(home)),
                subject=command.get('subject',initial['principal']),purpose=command.get('purpose','native-http'))}
        elif operation=='start':
            from hermes_state_runtime import admit_session_input,claim_session_input
            with api.mobile.connect() as conn:job=dict(conn.execute('SELECT * FROM jobs WHERE id=?',(command['job_id'],)).fetchone())
            admitted=admit_session_input(db,epoch=authority.epoch,principal_id=actor.subject,session_id='native-stored',
                request_id=job['request'],payload={'text':module.transfer_prompt(job['id'])})
            (home/'owner-stage.json').write_text(json.dumps({'operation':operation,'stage':'admitted'}),encoding='utf-8')
            claimed=claim_session_input(db,epoch=authority.epoch,session_id='native-stored')
            (home/'owner-stage.json').write_text(json.dumps({'operation':operation,'stage':'claimed'}),encoding='utf-8')
            assert admitted['admission_id']==claimed['admission_id']
            authority.executing['native-stored']=claimed['generation']
            def work():
                a=viewer_turn.set(live);b=executing_admission.set(True)
                try:result.append(module.continue_tool(api.mobile,api.native,{'job_id':job['id']},'native-stored'))
                finally:viewer_turn.reset(a);executing_admission.reset(b)
            worker=threading.Thread(target=work,daemon=True);worker.start()
            deadline=time.monotonic()+5
            while worker.is_alive() and not live.controls.pending and time.monotonic()<deadline:time.sleep(.01)
            answer={'pending':live.controls.snapshot('native-stored',claimed['generation']),'result':result}
        elif operation=='respond':
            prompt=next(iter(live.controls.pending))
            answer=asyncio.run(authority.respond(actor,SessionRef(str(home),'native-stored'),claimed['generation'],prompt,{'choice':command['choice']}))
            if command.get('wait',True):worker.join(6)
            answer.update(result=result,calls=len(backend.calls),alive=worker.is_alive())
        elif operation=='hold':backend.wait=threading.Event();answer={'holding':True}
        elif operation=='release':
            backend.wait.set();worker.join(6);answer={'result':result,'calls':len(backend.calls),'alive':worker.is_alive()}
        elif operation=='wait':
            worker.join(6);answer={'result':result,'calls':len(backend.calls),'alive':worker.is_alive()}
        elif operation=='cancel':
            asyncio.run(authority.interrupt(actor,SessionRef(str(home),'native-stored'),claimed['generation']))
            from hermes_state_runtime import settle_session_input
            settle_session_input(db,epoch=authority.epoch,admission_id=claimed['admission_id'],generation=claimed['generation'],outcome='interrupted')
            worker.join(6);answer={'result':result,'calls':len(backend.calls),'alive':worker.is_alive()}
        elif operation=='settle':
            from hermes_state_runtime import settle_session_input
            answer=settle_session_input(db,epoch=authority.epoch,admission_id=claimed['admission_id'],generation=claimed['generation'],
                outcome='completed',result={'result':{'completed':True,'final_response':'Synthetic owner work finished'},'usage':{}})
        elif operation=='schema':backend.version=command['version'];answer={'changed':True}
        elif operation=='registration':
            from hermes_constants import hermes_home_key
            entry=registry.get_entry('beacon_continue_call_work',scope=hermes_home_key(home))
            entry.handler=lambda *a,**kw:'wrong implementation'
            answer={'changed':True}
        elif operation=='gateway_implementation':
            sys.modules['beacon_gateway_tools'].LOADED_SHA='old-loaded-gateway';answer={'changed':True}
        elif operation=='gateway_code':
            sys.modules['beacon_gateway_tools'].Gateway._run=lambda *a,**kw:None;answer={'changed':True}
        elif operation=='epoch':
            from hermes_state_runtime import begin_runtime_epoch
            authority.epoch=begin_runtime_epoch(db,instance_id=authority.instance_id);answer={'changed':True}
        elif operation=='settings':owner_cfg['owner_id']='revoked-owner';answer={'changed':True}
        elif operation=='detach':live.subscribers.clear();answer={'changed':True}
        elif operation=='reconnect':
            ticket=runner.session_ticket_store.mint(profile_id=str(home),subject=actor.subject,purpose='interactive')
            grant=runner.session_ticket_store.redeem(ticket,profile_id=str(home),purpose='interactive')
            actor=Principal(grant['subject'],grant['profile_id'],grant['capabilities'],'reconnected-native-viewer')
            live.subscribers[actor.transport_id]=actor;answer={'attached':True}
        elif operation=='ticket_expiry':runner.session_ticket_store.TTL_SECONDS=-1;answer={'changed':True}
        elif operation=='instance':runner.session_ticket_store=TicketStore('replaced-owner',frozenset({str(home)}));answer={'changed':True}
        else:raise AssertionError('Unknown synthetic control')
        connection.sendall(json.dumps(answer).encode()+b'\n');connection.close()
    controls.close();server.should_exit=True;service.join(6);listener.close();db.close()


@pytest.fixture
def split_owner(transfer_case,monkeypatch):
    t=transfer_case;home=t.c[0].mobile.home()
    (home/'owner-input.json').write_text(json.dumps({'epoch':t.c[4],'principal':t.c[5]}),encoding='utf-8')
    script="import sys,json,traceback,pathlib,os,psutil\np=psutil.Process(os.getpid());pathlib.Path(os.environ['HERMES_HOME'],'owner-process.json').write_text(json.dumps({'pid':p.pid,'created':p.create_time()}),encoding='utf-8')\nsys.path.insert(0,os.environ['HERMES_AGENT_SOURCE']);sys.path.insert(0,sys.argv[1]);from test_voice_continuation import _owner_followup_server\ntry:_owner_followup_server()\nexcept BaseException:\n error=traceback.format_exc();pathlib.Path(os.environ['HERMES_HOME'],'owner-failure.json').write_text(error,encoding='utf-8');print(json.dumps({'synthetic_error':error}),flush=True);raise"
    env=dict(os.environ,HERMES_HOME=str(home),HERMES_DISABLE_LAZY_INSTALLS='1')
    process=subprocess.Popen([sys.executable,'-X','utf8','-c',script,str(Path(__file__).parent)],
        stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,env=env)
    owner=None
    def stop_unready():
        import psutil
        marker=home/'owner-process.json'
        if marker.exists():
            identity=json.loads(marker.read_text())
            try:
                child=psutil.Process(identity['pid'])
                if child.create_time()==identity['created']:child.terminate();child.wait(timeout=5)
            except psutil.NoSuchProcess:pass
        if process.poll() is None:process.kill()
        process.wait(timeout=5)
    def control(operation,**values):
        import socket
        try:
            with socket.create_connection(('127.0.0.1',owner['control_port']),timeout=8) as connection:
                connection.sendall(json.dumps({'operation':operation,**values}).encode()+b'\n')
                with connection.makefile('rb') as incoming:line=incoming.readline(65536)
            assert line,'Synthetic owner closed its control response'
            return json.loads(line)
        except (OSError,AssertionError):
            failure=(home/'owner-failure.json').read_text() if (home/'owner-failure.json').exists() else ''
            pytest.fail('Synthetic owner control failed: '+failure)
    try:
        deadline=time.monotonic()+30
        while not (home/'owner-ready.json').exists() and process.poll() is None and time.monotonic()<deadline:time.sleep(.01)
        if not (home/'owner-ready.json').exists():
            stop_unready()
            pytest.fail('Synthetic owner failed bounded startup: '+process.communicate(timeout=3)[1][-3000:])
        owner=None
        for _ in range(200):
            try:
                owner=json.loads((home/'owner-ready.json').read_text())
                break
            except (OSError,ValueError): time.sleep(.01)
        assert owner is not None,'Synthetic owner readiness was not published';assert owner['pid']!=os.getpid()
        import hermes_cli.gateway_client as native_client
        import hermes_cli.gateway_runtime as native_runtime
        endpoint=SimpleNamespace(api_origin=owner['origin'],profile_id=owner['profile_id'],instance_id=owner['instance_id'],
                                 authority_epoch=owner['epoch'],runtime_protocol=1)
        monkeypatch.setattr(native_runtime,'discover_gateway_endpoint',lambda home,timeout=1.5:SimpleNamespace(state='ready',endpoint=endpoint))
        monkeypatch.setattr(native_runtime,'ensure_gateway_runtime',lambda *a,**k:pytest.fail('A probe must never start an owner'))
        def mint(home,explicit,*,purpose='interactive',scope=None,subject=None):
            assert explicit is endpoint and purpose=='native-http' and subject==t.c[5]
            return control('mint',purpose=purpose,subject=subject)['ticket']
        monkeypatch.setattr(native_client,'_session_ticket',mint)
        monkeypatch.setattr(sys.modules['gateway.run_api'],'_ACTIVE_RUNNER',None)
        errors=[];remote=getattr(t.module,'remote_preflight',None)
        def observed(*args):
            try:return remote(*args)
            except Exception as exc:errors.append((type(exc).__name__,str(exc)));raise
        if remote is not None:monkeypatch.setattr(t.module,'remote_preflight',observed)
        yield SimpleNamespace(t=t,owner=owner,endpoint=endpoint,control=control,errors=errors)
    finally:
        if process.poll() is None:
            try:
                if owner is not None:control('stop');process.wait(timeout=10)
                else:stop_unready()
            except (OSError,subprocess.TimeoutExpired,pytest.fail.Exception):stop_unready()
        process.stdout.close();process.stderr.close()


def test_owner_followup_red_split_owner_accepts_exact_pending_proposal(split_owner):
    peer=split_owner;t=peer.t
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'split-follow',
        'tool_request_id':'inline-original','callback':True})
    assert response.status_code==200,(response.text,peer.errors)
    assert response.json()['native_transfer'] is True
    assert not t.backend.calls


def test_separated_owner_reconciles_stranded_create_from_verified_readiness(split_owner,monkeypatch):
    """The dashboard process has no live runner; the verified owner readiness supplies the epoch."""
    from gateway.session_local import stage_local_session
    from gateway.session_policy import LocalSessionPolicy
    from hermes_state_local import commit_local_session
    peer=split_owner;t=peer.t
    monkeypatch.setattr(sys.modules.get('gateway.run'),'_resolve_gateway_model',
        lambda config:(_ for _ in ()).throw(AssertionError('Creation resolved a model')),raising=False)
    monkeypatch.setattr(t.authority.runner,'session_store',
        SimpleNamespace(_generate_session_key=lambda source:'local:'+source.chat_id),raising=False)
    home=t.c[0].mobile.home();request='separated-create'
    assert getattr(sys.modules['gateway.run_api'],'_ACTIVE_RUNNER',None) is None
    pending=t.c[0].native.prepare_created(t.c[0].mobile,
        {'owner':'owner','provider':'basic','principal':t.c[5],'home':t.c[0].native.home_fingerprint(home),'epoch':t.c[4]},
        'default',request)
    assert pending is not None
    policy=LocalSessionPolicy(source='desktop',platform='local',cwd=str(home),model='gpt-6-luna',
        toolsets=(),config_json='{}',request_json='{}',terminal_json='{}')
    create_actor=t.n.Principal(t.actor.subject,t.authority.profile_id,
        frozenset(set(t.actor.capabilities)|{'session:create'}),'separated-create-transport')
    staged=stage_local_session(t.authority,create_actor,{'request_id':request},trusted_policy=policy)
    saved=commit_local_session(t.c[3],epoch=t.c[4],receipt=staged)
    session=saved['session_id'];assert session==pending['session']
    with t.c[0].mobile.connect() as conn:
        assert conn.execute('SELECT kind FROM native_conversations WHERE session=?',(session,)).fetchone()[0]=='pending'
    # The child owner's readiness endpoint carries the same verified epoch the canonical socket uses.
    assert t.c[0].native.current_epoch('default')==t.c[4]
    assert t.c[0].native.reconcile_created(t.c[0].mobile,'owner','basic','default',session,t.c[5])
    final=t.c[0].mobile.native_conversation('owner','basic','default',t.c[0].native.home_fingerprint(home),session,t.c[5])
    assert final['kind']=='canonical' and final['principal']==t.c[5]


def _owner_followup_value(peer):
    t=peer.t
    with t.c[0].mobile.connect() as conn:
        _,binding=t.module.receipt_binding(t.c[0].mobile,('owner','basic','default',t.c[-1]),'native-stored',t.c[5],'inline-original',conn)
    return dict(owner='owner',provider='basic',profile='default',call_id=t.c[-1],stored='native-stored',request='inline-original',
                binding=binding,nonce='synthetic-probe',implementation=t.module._loaded_sha)


def _owner_followup_http(peer,value=None,headers=None,ticket=None):
    import httpx
    if ticket is None:ticket=peer.control('mint')['ticket']
    with httpx.Client(timeout=3,trust_env=False,follow_redirects=False) as client:
        return client.post(peer.owner['origin']+'/api/plugins/beacon/mobile/voice-owner/preflight',
            headers={'x-hermes-gateway-ticket':ticket,**(headers or {})},json=value or _owner_followup_value(peer))


def _owner_followup_admit(peer,consent=True):
    t=peer.t
    value={'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'split-follow',
           'tool_request_id':'inline-original'}
    if consent is not None:value['callback']=consent
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/work',json=value)
    assert response.status_code==200,(response.text,peer.errors)
    return response.json()


@pytest.mark.parametrize('choice',['once','deny'])
@pytest.mark.parametrize('consent',[None,False,True])
def test_owner_followup_split_work_uses_native_approval_and_committed_result_after_hangup(split_owner,choice,consent):
    peer=split_owner;t=peer.t;prepared=_owner_followup_admit(peer,consent)
    assert prepared['callback_consent']==('unset' if consent is None else 'requested' if consent else 'declined')
    if consent is None:peer.control('detach')
    close(t.c)
    pending=peer.control('start',job_id=prepared['job_id'])
    assert pending['pending'][0]['choices']==['once','deny']
    assert t.dispatcher.decide(('owner','basic','default',t.c[-1]),'inline-original',
        {'proposal_id':t.receipt['proposal']['id'],'decision':'approve'})['status']=='transferred'
    t.c[0].watch('owner','basic','default');assert not notices(t.c)
    if consent is None:assert peer.control('reconnect')['attached']
    settled=peer.control('respond',choice=choice)
    assert not settled['alive'] and settled['calls']==int(choice=='once'),settled
    t.c[0].watch('owner','basic','default');assert not notices(t.c),'A receipt alone is not committed native completion'
    peer.control('settle')
    for _ in range(3):t.c[0].watch('owner','basic','default')
    assert len(notices(t.c))==int(choice=='once' and consent is True)
    assert not t.backend.calls,'The dashboard must never execute the transferred operation'
    replay=_owner_followup_admit(peer,consent)
    assert replay['job_id']==prepared['job_id'] and replay['submit'] is False


def test_owner_followup_running_native_owner_receipt_survives_foreign_poll_without_completion_claim(split_owner):
    peer=split_owner;t=peer.t;prepared=_owner_followup_admit(peer)
    close(t.c);peer.control('hold');peer.control('start',job_id=prepared['job_id'])
    responding=peer.control('respond',choice='once',wait=False)
    assert responding['alive']
    deadline=time.monotonic()+1
    while True:
        observed=t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')
        if observed['status']!='transferred' or time.monotonic()>=deadline:break
        time.sleep(.01)
    assert observed['status']=='running' and 'result' not in observed
    t.c[0].watch('owner','basic','default');assert not notices(t.c)
    done=peer.control('release');assert done['calls']==1 and not done['alive']
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='completed'
    t.c[0].watch('owner','basic','default');assert not notices(t.c)
    peer.control('settle')
    for _ in range(2):t.c[0].watch('owner','basic','default')
    assert len(notices(t.c))==1


@pytest.mark.parametrize('boundary',['subject','purpose','origin','bearer','profile','stored','implementation','schema','registration','gateway_implementation','gateway_code','expiry','instance'])
def test_owner_followup_native_probe_rejects_foreign_or_stale_authority_without_retiring(split_owner,boundary):
    peer=split_owner;t=peer.t;value=_owner_followup_value(peer);headers={};ticket=None
    if boundary=='subject':ticket=peer.control('mint',subject='foreign-principal')['ticket']
    elif boundary=='purpose':ticket=peer.control('mint',purpose='interactive')['ticket']
    elif boundary=='origin':headers['origin']=''
    elif boundary=='bearer':headers['authorization']='Bearer synthetic-unrelated'
    elif boundary=='profile':value['profile']='foreign'
    elif boundary=='stored':value['stored']='foreign-conversation'
    elif boundary=='implementation':value['implementation']='old-loaded-implementation'
    elif boundary=='schema':peer.control('schema',version='b')
    elif boundary=='registration':peer.control('registration')
    elif boundary=='gateway_implementation':peer.control('gateway_implementation')
    elif boundary=='gateway_code':peer.control('gateway_code')
    elif boundary=='expiry':peer.control('ticket_expiry')
    elif boundary=='instance':peer.control('instance')
    response=_owner_followup_http(peer,value,headers,ticket)
    assert response.status_code in (401,403,409),response.text
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='needs_approval'
    with t.c[0].mobile.connect() as conn:assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0
    assert not t.backend.calls


def test_owner_followup_probe_requires_native_grant_and_is_not_a_shadowable_command(split_owner,monkeypatch):
    import httpx
    import gateway.session_commands as commands
    peer=split_owner
    monkeypatch.setattr(commands,'execute_command',lambda *a,**kw:pytest.fail('No alias, exec, skill or prompt may run to probe support'))
    with httpx.Client(timeout=3,trust_env=False,follow_redirects=False) as client:
        response=client.post(peer.owner['origin']+'/api/plugins/beacon/mobile/voice-owner/preflight',json=_owner_followup_value(peer))
    assert response.status_code==401 and response.headers['cache-control']=='no-store'
    ticket=peer.control('mint')['ticket']
    first=_owner_followup_http(peer,ticket=ticket)
    assert first.status_code==200 and first.headers['cache-control']=='no-store'
    assert first.json()['epoch']==peer.owner['epoch']
    assert _owner_followup_http(peer,ticket=ticket).status_code==401,'The genuine native grant is single use'
    assert _owner_followup_admit(peer)['native_transfer'] is True


@pytest.mark.parametrize('boundary',['transport','nonce','implementation','binding','deadline'])
def test_owner_followup_ambiguous_or_replayed_probe_keeps_original_action_actionable(split_owner,monkeypatch,boundary):
    peer=split_owner;t=peer.t;remote=t.module.remote_preflight
    def changed(profile,principal,value):
        proof=remote(profile,principal,value)
        if boundary=='transport':raise TimeoutError('Synthetic response lost after read-only preflight')
        if boundary=='deadline':proof['valid_until']=time.time()-1
        else:proof[boundary]='stale-proof'
        return proof
    monkeypatch.setattr(t.module,'remote_preflight',changed)
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'split-follow',
        'tool_request_id':'inline-original','callback':True})
    assert response.status_code==409,response.text
    assert t.dispatcher.status(('owner','basic','default',t.c[-1]),'inline-original')['status']=='needs_approval'
    with t.c[0].mobile.connect() as conn:assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0


@pytest.mark.parametrize('boundary',['epoch','registration','schema','settings','cancel'])
def test_owner_followup_change_after_preflight_cannot_execute_original_work(split_owner,boundary):
    peer=split_owner;t=peer.t;prepared=_owner_followup_admit(peer)
    if boundary in ('epoch','registration','schema','settings'):
        peer.control(boundary,**({'version':'b'} if boundary=='schema' else {}))
    pending=peer.control('start',job_id=prepared['job_id'])
    if boundary=='cancel':
        assert pending['pending']
        pending=peer.control('cancel')
    assert not pending.get('pending') and pending['result'],pending
    assert not t.backend.calls
    t.c[0].watch('owner','basic','default');assert not notices(t.c)


@pytest.mark.parametrize('boundary',['proposal','settings'])
def test_owner_followup_delayed_probe_never_retires_changed_or_closed_call(split_owner,monkeypatch,boundary):
    peer=split_owner;t=peer.t;remote=t.module.remote_preflight
    def changed(*args):
        proof=remote(*args)
        if boundary=='settings':t.c[2]['profiles']=['revoked-profile']
        else:
            with t.c[0].mobile.connect() as conn:
                source=conn.execute('SELECT payload FROM tool_receipts WHERE request_id=?',('inline-original',)).fetchone()
                receipt=json.loads(source[0]);receipt['proposal']['id']='changed-proposal'
                conn.execute('UPDATE tool_receipts SET payload=? WHERE request_id=?',(json.dumps(receipt),'inline-original'))
        return proof
    monkeypatch.setattr(t.module,'remote_preflight',changed)
    response=t.c[1].post(f'/api/plugins/beacon/mobile/calls/{t.c[-1]}/work',json={
        'stored_session_id':'native-stored','runtime_session_id':'runtime','request_id':'split-follow',
        'tool_request_id':'inline-original','callback':True})
    assert response.status_code in (403,409),response.text
    with t.c[0].mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM jobs').fetchone()[0]==0
        receipt=json.loads(conn.execute('SELECT payload FROM tool_receipts WHERE request_id=?',('inline-original',)).fetchone()[0])
    assert receipt['status']=='needs_approval' and not t.backend.calls


def test_owner_followup_receipted_hangup_during_probe_continues_same_native_work(split_owner,monkeypatch):
    peer=split_owner;t=peer.t;remote=t.module.remote_preflight
    def disconnected(*args):
        proof=remote(*args);close(t.c);return proof
    monkeypatch.setattr(t.module,'remote_preflight',disconnected)
    prepared=_owner_followup_admit(peer)
    assert prepared['native_transfer'] is True
    pending=peer.control('start',job_id=prepared['job_id'])
    assert pending['pending'][0]['choices']==['once','deny']
    answer=peer.control('respond',choice='once')
    assert answer['calls']==1 and not answer['alive']
    t.c[0].watch('owner','basic','default');assert not notices(t.c)
    peer.control('settle')
    for _ in range(2):t.c[0].watch('owner','basic','default')
    assert len(notices(t.c))==1 and not t.backend.calls


def test_owner_followup_lost_admission_link_is_unconfirmed_without_native_or_mutation_replay(split_owner):
    peer=split_owner;t=peer.t;first=_owner_followup_admit(peer)
    close(t.c)
    replay=_owner_followup_admit(peer)
    assert replay['job_id']==first['job_id'] and replay['status']=='submission_unconfirmed' and replay['submit'] is False
    from hermes_state_runtime import list_session_admissions
    assert list_session_admissions(t.c[3],session_id='native-stored',pending_only=False)==[]
    assert not t.backend.calls and not notices(t.c)


def test_owner_followup_receipt_drift_keeps_native_turn_restricted(transfer_case):
    t=transfer_case;native_claim(t)
    with t.c[0].mobile.connect() as conn:
        receipt=json.loads(conn.execute('SELECT payload FROM tool_receipts WHERE request_id=?',('inline-original',)).fetchone()[0])
        receipt['_continuation_owner']['binding']='changed-binding'
        conn.execute('UPDATE tool_receipts SET payload=? WHERE request_id=?',(json.dumps(receipt),'inline-original'))
    a=t.n.viewer_turn.set(t.live);b=t.n.executing_admission.set(True)
    try:
        assert t.module.guard(t.c[0].mobile,tool_name='unrelated_mutation',args={},session_id='native-stored')['block']
        assert json.loads(t.module.continue_tool(t.c[0].mobile,t.c[0].native,{'job_id':t.prepared['job_id']},'native-stored'))['status']=='failed'
        assert not t.backend.calls
    finally:t.n.viewer_turn.reset(a);t.n.executing_admission.reset(b)
