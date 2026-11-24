"""Canonical notices use actual native admission/ingress with a deterministic handler, never a model."""
import ast
import asyncio
from contextlib import contextmanager
import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest
from test_mobile import harness
from test_canonical_same_chat import canonical_call, subject


@pytest.fixture
def native_notice(canonical_call, monkeypatch, tmp_path):
    import hermes_constants
    from hermes_cli import profiles
    from hermes_state import SessionDB
    from hermes_state_runtime import RuntimeStoreError, begin_runtime_epoch, claim_session_input, settle_session_input
    from gateway.config import Platform
    from gateway.session import SessionSource
    from gateway.session_authority import LiveSession, SessionAuthority
    from gateway.session_contract import SessionRef
    from gateway.session_controls import AuthorityConnection
    from gateway.session_ingress import execute_admission
    from gateway.session_results import execution_result

    api, client, cfg, sessions, make_session, _, server = canonical_call
    observer = sys.modules['beacon_observers']
    transport = sys.modules['beacon_canonical_transport']
    db = SessionDB(tmp_path / 'notice-native.sqlite')
    db.create_session('same-chat', 'local', user_id=subject())
    epoch = begin_runtime_epoch(db, instance_id='notice-instance')
    runner = SimpleNamespace(config=SimpleNamespace(multiplex_profiles=False), _draining=False,
                             _voice_key_for_source=lambda source: 'test-route', _adapter_for_source=lambda source: None,
                             session_runtime_descriptor={'state': 'ready', 'instance_id': 'notice-instance', 'authority_epoch': epoch})
    authority = SessionAuthority(runner, profile_id=str(tmp_path), instance_id='notice-instance', db=db, epoch=epoch)
    runner.session_authority = authority
    live = LiveSession(SessionSource(platform=Platform.LOCAL, chat_id='same-chat', user_id=subject(), chat_type='dm'), 'test-route')
    authority.sessions['same-chat'] = live
    identity = {'provider': 'basic', 'issuer': sessions[0].issuer, 'user_id': 'owner'}
    connection = AuthorityConnection(authority, SimpleNamespace(auth_identity=identity), identity)
    assert 'session:operator' not in connection.actor.capabilities and not connection.native_owner
    connection.subscriptions['same-chat'] = 'notice-subscription'
    live.subscribers['notice-subscription'] = connection.actor
    monkeypatch.setattr(authority, '_schedule_admitted', lambda *args: None)
    monkeypatch.setitem(sys.modules, 'gateway.run_api', SimpleNamespace(_ACTIVE_RUNNER=runner))
    monkeypatch.setattr(server, '_profile_home', lambda profile: tmp_path)
    @contextmanager
    def profile_db(params, **kwargs):
        assert params['profile'] == 'default'
        yield db
    monkeypatch.setattr(server, '_profile_db', profile_db)
    monkeypatch.setattr(hermes_constants, 'get_hermes_home', lambda: tmp_path)
    monkeypatch.setattr(profiles, 'profile_matches_home', lambda profile, home: profile == 'default' and home == tmp_path)

    # Load the production hook and observer under this fixture's private configuration.
    plugin_path = Path(observer.__file__).with_name('__init__.py')
    spec=importlib.util.spec_from_file_location('native_notice_production_hook',plugin_path)
    entry=importlib.util.module_from_spec(spec);spec.loader.exec_module(entry)
    monkeypatch.setattr(entry,'current_profile',lambda settings:'default')
    # Run precisely the pinned finalizer's emitted hook call, under real execute_admission context.
    import agent.turn_finalizer
    finalizer_path = Path(agent.turn_finalizer.__file__)
    launch_path = finalizer_path.parents[1] / 'tui_gateway/launch_profile_policy.py'
    spec = importlib.util.spec_from_file_location('tui_gateway.launch_profile_policy', launch_path)
    launch_policy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(launch_policy)
    monkeypatch.setitem(sys.modules, spec.name, launch_policy)
    hook_call = next(node for node in ast.walk(ast.parse(finalizer_path.read_text(encoding='utf-8')))
                     if isinstance(node, ast.Call) and isinstance(node.func, ast.Name)
                     and node.func.id == '_invoke_hook_safely' and node.args
                     and isinstance(node.args[0], ast.Constant) and node.args[0].value == 'on_session_end')
    emitted_hook = compile(ast.Expression(body=hook_call), str(finalizer_path), 'eval')
    seen = []
    # Hermes' own wait on a person (tools/human_input_hooks.py), reaching whatever hooks the plugin registers, as Hermes' dispatch would.
    import hermes_cli.lifecycle
    from tools import approval_context
    from tools.human_input_hooks import human_input_request
    registered = {}
    entry.register(SimpleNamespace(register_tool=lambda **kwargs: None,
                                   register_hook=lambda name, callback: registered.setdefault(name, []).append(callback)))
    fired = []
    def invoke_hook(name, **kwargs):
        fired.append(name)
        return [callback(**kwargs) for callback in registered.get(name, [])]
    monkeypatch.setattr(hermes_cli.lifecycle, 'invoke_hook', invoke_hook)
    waits = []

    def ask(kind, claimed):
        """A tool in this turn waits for the owner, the way clarify or a gateway approval does; the notices are read during the wait."""
        tokens = approval_context.set_current_observability_context(turn_id=claimed['admission_id'], tool_call_id='synthetic-call', session_id='same-chat')
        try:
            with human_input_request(kind, prompt='Synthetic question?', **({'session_key': 'synthetic-key'} if kind == 'approval' else {})) as human:
                waits.append(notice_rows(api))
                api.activity.sync(api.mobile, api.native, 'owner', 'basic', 'default')  # the dashboard watcher's pass meanwhile
                waits.append(notice_rows(api))
                human.outcome = 'answered'
        finally:
            approval_context.reset_current_observability_context(tokens)

    async def drive(*, proven=True, foreign_binding=False, foreign_actor=False, failed=False,
                    interrupted=False, execute=True, live_call=False, stale_generation=False, asks=None):
        ref = SessionRef(str(tmp_path), 'same-chat')
        submitting=connection
        if foreign_actor:
            other = dict(identity, issuer='https://other.example')
            actor = AuthorityConnection(authority, SimpleNamespace(auth_identity=other), other)
            if actor.actor.subject!=connection.actor.subject:
                with pytest.raises(RuntimeStoreError, match='permission_denied'):
                    await actor.submit(ref, {'submission_id': 'foreign-request', 'text': 'Synthetic text'})
                return None
            submitting=actor  # Current native shares execution identity; Beacon must still refuse this issuer.
            actor.subscriptions['same-chat']='foreign-subscription'
            live.subscribers['foreign-subscription']=actor.actor
        if live_call:
            api.mobile.reserve('owner', 'default', 'same-chat', principal=subject())
        binding = {'owner': 'owner', 'provider': 'basic', 'principal': subject('https://other.example') if foreign_binding or foreign_actor else subject(),
                   'home': transport.home_digest(tmp_path)}
        wire = json.dumps({'jsonrpc': '2.0', 'id': 17, 'method': 'prompt.submit', 'params': {
            'profile': 'default', 'session_id': 'same-chat', 'submission_id': 'notice-request', 'text': 'Synthetic text task'}})
        outgoing, incoming, received = asyncio.Queue(), asyncio.Queue(), asyncio.Queue()
        class Client:
            async def receive(self): return await outgoing.get()
            async def send_text(self, raw): await received.put(raw)
        class Upstream:
            async def recv(self): return await incoming.get()
            async def send(self, raw):
                assert raw == wire
                params = dict(json.loads(raw)['params'])
                params.pop('profile')
                receipt = await submitting.submit(ref, params)
                await incoming.put(json.dumps({'jsonrpc': '2.0', 'id': 17 if proven else 99, 'result': receipt}))
        relay = asyncio.create_task(transport.relay_sockets(Client(), Upstream(), 'default', api.mobile, binding))
        try:
            await outgoing.put({'type': 'websocket.receive', 'text': wire})
            await asyncio.wait_for(received.get(), 5)
            claimed = claim_session_input(db, epoch=epoch, session_id='same-chat')
            assert claimed and claimed['principal_id'] == api.native.execution_subject('owner','basic',subject())
            authority.executing['same-chat'] = claimed['generation']
            start = {'method': 'event', 'params': {'type': 'message.start', 'session_id': 'same-chat',
                     'admission_id': claimed['admission_id'], 'authority_epoch': epoch,
                     'execution_generation': claimed['generation'], 'payload': {}}}
            await incoming.put(json.dumps(start))
            assert json.loads(await asyncio.wait_for(received.get(), 5)) == start
            if stale_generation:
                authority.executing['same-chat'] += 1
            def emit():
                seen.append(api.native.current_principal('owner', 'basic', 'default', 'same-chat'))
                agent = SimpleNamespace(session_id='same-chat', model='synthetic')
                values = dict(agent=agent, logger=None, effective_task_id=None, turn_id=claimed['admission_id'],
                              completed=not failed, failed=failed, interrupted=interrupted,
                              _turn_exit_reason='error' if failed else 'completed', _platform='local',
                              _invoke_hook_safely=lambda name, logger, **kwargs: entry.native_completion(**kwargs))
                eval(emitted_hook, values)
            async def handle(event):
                assert event.source.user_id in (subject(),api.native.execution_subject('owner','basic',subject())) and event.text == 'Synthetic text task'
                if asks:
                    ask(asks, claimed)
                else:
                    emit()
                    emit()  # A duplicate hook for this native turn must not duplicate the outbox row.
                execution_result.get()['terminal_result'] = {'completed': not failed, 'failed': failed,
                                                            'final_response': 'Synthetic task result'}
                return 'Synthetic task result'
            runner._handle_message = handle
            if execute:
                await execute_admission(authority, ref, claimed)
            elif asks:
                ask(asks, claimed)  # A real receipt and a viewer alone do not create executing-turn proof.
            else:
                emit()  # A real receipt and a viewer alone do not create executing-turn proof.
            result = authority.pending_results.get(claimed['admission_id'], {'result': {'completed': True}})
            settle_session_input(db, epoch=epoch, admission_id=claimed['admission_id'], generation=claimed['generation'],
                                 outcome='failed' if failed else 'completed', result=result)
            return claimed
        finally:
            relay.cancel()
            await asyncio.gather(relay, return_exceptions=True)
            authority.executing.pop('same-chat', None)
    drive.waits = waits
    try:
        yield api, client, sessions, make_session, seen, drive
    finally:
        db.close()


def notice_rows(api):
    with api.mobile.connect() as conn:
        return [(row['id'], row['kind'], row['state'], row['principal'], row['conversation'], row['activity_key'])
                for row in conn.execute('SELECT * FROM notices ORDER BY seq')]


@pytest.mark.parametrize('live_call', [False, True])  # the call screen cannot show it, so a question in the call's chat alerts too
@pytest.mark.parametrize('kind', ['clarify', 'approval'])
def test_a_question_or_approval_in_an_app_chat_alerts_the_phone_once_until_it_is_answered(native_notice, kind, live_call):
    api, client, sessions, make_session, seen, drive = native_notice
    asyncio.run(drive(asks=kind, live_call=live_call))
    waits = drive.waits
    # While Hermes waits: exactly one pending notice of that kind, bound to the chat's login. The dashboard's own pass keeps it.
    assert len(waits) == 2 and waits[0] == waits[1]
    (event, wait_kind, state, principal, conversation, ref), = waits[0]
    assert (wait_kind, state, principal, conversation) == (kind, 'pending', subject(), 'same-chat') and ref == event
    # Answered: the notice is settled and its activity ended, so the phone takes the alert down.
    rows = notice_rows(api)
    assert [(r[1], r[2], r[3], r[5]) for r in rows] == [(kind, 'settled', subject(), event), ('activity_end', 'pending', subject(), event)]
    # Only the chat's own login sees it; no request text is stored or served.
    detail = client.get('/api/plugins/beacon/mobile/notifications/events/' + event)
    assert detail.status_code == 200 and detail.json()['kind'] == kind and 'Synthetic question' not in detail.text
    assert {e['event_id'] for e in client.get('/api/plugins/beacon/mobile/notifications/events').json()['events']} == {r[0] for r in rows}
    sessions[0] = make_session('https://other.example')
    assert client.get('/api/plugins/beacon/mobile/notifications/events/' + event).status_code == 404
    assert client.get('/api/plugins/beacon/mobile/notifications/events').json()['events'] == []


@pytest.mark.parametrize('options', [{'foreign_binding': True}, {'execute': False}, {'proven': False}])
def test_a_wait_outside_a_proven_owner_turn_makes_no_notice(native_notice, options):
    api, _, _, _, _, drive = native_notice
    asyncio.run(drive(asks='clarify', **options))
    assert notice_rows(api) == [] and drive.waits == [[], []]


@pytest.mark.parametrize('failed,kind', [(False, 'completion'), (True, 'failure')])
def test_native_completed_input_creates_one_principal_bound_notice(native_notice, failed, kind):
    api, client, sessions, make_session, seen, drive = native_notice
    asyncio.run(drive(failed=failed))
    assert seen == [subject(), subject()]
    with api.mobile.connect() as conn:
        rows = [dict(row) for row in conn.execute('SELECT * FROM notices')]
    assert [(row['kind'], row['principal'], row['conversation']) for row in rows] == [(kind, subject(), 'same-chat')]
    # Real route filtering, not an invented transport event: only the original issuer sees this outbox row.
    event = rows[0]['id']
    assert client.get('/api/plugins/beacon/mobile/notifications/events/' + event).status_code == 200
    assert [row['event_id'] for row in client.get('/api/plugins/beacon/mobile/notifications/events').json()['events']] == [event]
    sessions[0] = make_session('https://other.example')
    assert client.get('/api/plugins/beacon/mobile/notifications/events/' + event).status_code == 404
    assert client.get('/api/plugins/beacon/mobile/notifications/events').json()['events'] == []


@pytest.mark.parametrize('options', [{'proven': False}, {'foreign_binding': True}, {'foreign_actor': True},
                                     {'execute': False}, {'stale_generation': True}, {'interrupted': True}, {'live_call': True}])
def test_native_notice_refuses_unproven_foreign_or_inapplicable_turns(native_notice, options):
    api, _, _, _, seen, drive = native_notice
    asyncio.run(drive(**options))
    if options.keys() & {'proven', 'foreign_binding', 'execute', 'stale_generation'}:
        assert seen and all(value is None for value in seen)
    with api.mobile.connect() as conn:
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0] == 0
