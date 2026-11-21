"""Synthetic policy/phone fixtures and scripted model answers; no model or device is used here."""
import json
import sys
import threading
import time
import uuid
from types import SimpleNamespace

import pytest
from test_agent_phone_tools import FakeHost, harness, load, node, phone, screen_xml  # noqa: F401
from test_agent_phone_api import PREFIX, phone_api  # noqa: F401


def test_registered_default_task(phone):
    tools, _, world, _ = phone
    registered = {}
    tools.register(SimpleNamespace(register_tool=lambda **kwargs: registered.update(kwargs)))
    assert 'task' in registered['schema']['parameters']['properties']
    assert tools.validate({'task': 'Open notes'}) == {'action': 'task', 'task': 'Open notes'}
    assert registered['check_fn']()
    assert json.loads(registered['handler']({'action': 'status'}, session_id='chat'))['control_driver'] == 'phonedriver'
    for invalid in ({'task': ' '}, {'task': 'abc', 'action': 'key', 'key': 'home'}, {'action': 'task', 'text': 'x'}):
        with pytest.raises(ValueError):
            tools.validate(invalid)


def test_authenticated_capability_before_setup(phone_api):
    url = PREFIX + '/mobile/agent-phone?beacon_profile=default'
    assert phone_api.client.get(url).json()['control_driver'] == 'phonedriver'
    (phone_api.folder.parent / 'plugin-data/beacon/agent-phone.json').unlink()
    response = phone_api.client.get(url)
    assert response.json()['control_driver'] == 'phonedriver' and not response.json()['installed']
    assert response.headers['cache-control'] == 'no-store'


def reply(**args):
    return '<tool_call>' + json.dumps({'name': 'mobile_use', 'arguments': args}) + '</tool_call>'


CLICK = reply(action='click', coordinate=[490, 399])
OK = reply(action='terminate', status='success')


@pytest.fixture
def driver(phone, monkeypatch, request):
    tools, approvals, world, _ = phone
    module = tools._sibling('phonedriver')
    lease = load('agent_phone_lease', 'phone_driver_test_lease')
    if 'phone_api' in request.fixturenames:
        monkeypatch.setitem(sys.modules, 'beacon_agent_phone_lease', lease)
    else:
        monkeypatch.setattr(tools._sibling('agent_phone_lease'), 'task_slot', lease.task_slot, raising=False)
    # Exercise the production tree/pixel masker with synthetic data.
    real = load('agent_phone_redact', 'beacon_agent_phone_redact')
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', real)
    world.xml = screen_xml(node(text='Notes'))
    replies, prompts = [], []
    def scripted(profile, messages, timeout, route):
        assert profile == 'default'
        prompts.append(messages)
        answer = replies.pop(0)
        return answer() if callable(answer) else answer
    monkeypatch.setattr(module, 'infer', scripted)
    activity = []
    monkeypatch.setattr(FakeHost, 'note_activity', lambda self, agent=False: activity.append(agent))
    return tools, approvals, world, module, replies, prompts, activity


def invoke(tools):
    return json.loads(tools.handler({'task': 'Open Notes'}, session_id='chat', task_id='synthetic-turn'))


def test_checked_action_and_independent_verification(driver):
    tools, approvals, world, _, answers, prompts, activity = driver
    world.after_input = screen_xml(node(text='Notes are open'))
    answers.extend([CLICK, OK, OK])
    result = invoke(tools)
    assert result == dict(success=True, verified=True, control_driver='phonedriver', steps=1, planner_calls=3, captures=4)
    assert len(world.inputs) == 1 and world.inputs[0][:3] == ('tap', 540, 963)
    assert len(activity) >= 4 and all(activity)
    assert 'Notes are open' in str(prompts[-1][1]['content'][1])
    assert 'verify' in prompts[-1][1]['content'][0]['text'].lower()
    assert 'Open Notes' not in json.dumps(result)
    events = approvals.activity('owner')
    assert events[0]['action'] == 'tap' and events[0]['outcome'] == 'done'
    assert 'Open Notes' not in json.dumps(events)
    answers.extend([CLICK])
    assert invoke(tools)['error'] == 'already_attempted' and len(world.inputs) == 1


@pytest.mark.parametrize('action', [
    {'action': 'click', 'coordinate': [0, 1000]}, {'action': 'click', 'coordinate': [-1, 0]},
    {'action': 'click', 'coordinate': [True, 0]}, {'action': 'click', 'coordinate': [1.5, 1]},
    {'action': 'swipe', 'coordinate': [1, 1]}, {'action': 'wait', 'time': float('nan')},
    {'action': 'type', 'text': 'a\nb'}, {'action': 'terminate', 'status': 'maybe'},
    {'action': 'shell', 'command': 'id'}, {'action': 'click', 'coordinate': [1, 2], 'extra': 5}])
def test_strict_action_validation(driver, action):
    mod = driver[3]
    with pytest.raises(mod.Failure):
        mod.parse(reply(**action))
    assert mod.parse(reply(action='click', coordinate=[0, 999]))['coordinate'] == [0, 999]
    with pytest.raises(mod.Failure):
        mod.parse(OK + OK)
    with pytest.raises(mod.Failure):
        mod.parse(OK.replace('mobile_use', 'unexpected'))


def test_masks_reach_model_and_block_input(driver):
    from base64 import b64decode
    from io import BytesIO
    from PIL import Image
    tools, _, world, _, answers, prompts, _ = driver
    secret = 'synthetic-private-example'
    world.xml = screen_xml(node(text=secret, password=True))
    answers.append(CLICK)
    assert invoke(tools)['error'] == 'masked' and not world.inputs
    summary, picture = prompts[0][1]['content'][1:]
    assert secret not in summary['text']
    with Image.open(BytesIO(b64decode(picture['image_url']['url'].split(',')[1]))) as image:
        assert max(image.getpixel((360, 638))) < 10


def test_same_screen_retry_and_fresh_verification(driver):
    tools, _, world, _, answers, _, _ = driver
    answers.extend([CLICK, CLICK])
    assert invoke(tools)['error'] == 'unchanged' and len(world.inputs) == 1
    answers.extend([OK, CLICK])
    assert invoke(tools)['error'] == 'unverified' and len(world.inputs) == 1


@pytest.mark.parametrize('change,code', [('tree', 'changed'), ('pixels', 'changed'), ('epoch', 'busy'),
                                         ('holder', 'busy'), ('policy', 'off'), ('identity', 'unavailable'),
                                         ('pointer', 'unavailable'), ('interrupt', 'cancelled')])
def test_changes_during_inference_fail_without_input(driver, monkeypatch, change, code):
    from test_agent_phone_tools import FakeGrpc
    import tools.interrupt
    tools_mod, approvals, world, _, answers, _, _ = driver
    def changed():
        if change == 'tree':
            world.xml = screen_xml(node(text='Different'))
        elif change == 'pixels':
            old = FakeGrpc.screenshot
            def different(*a, **kw):
                v = old(*a, **kw)
                return dict(v, image=bytes(len(v['image'])))
            monkeypatch.setattr(FakeGrpc, 'screenshot', different)
        elif change == 'holder':
            world.lease['holder'] = 'human'
        elif change == 'epoch':
            world.lease['epoch'] += 1
        elif change == 'policy':
            monkeypatch.setattr(approvals, 'policy', lambda owner: {'enabled': False})
        elif change == 'identity':
            monkeypatch.setattr(tools_mod, '_authorize', lambda session: ('other', 'default'))
        elif change == 'pointer':
            monkeypatch.setattr(tools_mod, 'pointer', lambda: None)
        else:
            monkeypatch.setattr(tools.interrupt, 'is_interrupted', lambda: True)
        return CLICK
    answers.append(changed)
    assert invoke(tools_mod)['error'] == code and not world.inputs


@pytest.mark.parametrize('answer,expected', [('denied', 'denied'), ('expired', 'expired')])
def test_approval_refusal_is_terminal_on_retry(driver, monkeypatch, answer, expected):
    tools, approvals, world, _, answers, _, _ = driver
    world.xml = screen_xml(node(text='Send message'))
    asked = []
    monkeypatch.setattr(approvals, 'ask', lambda *args, **options: (asked.append(args) or answer, 'synthetic'))
    answers.extend([CLICK, CLICK])
    assert invoke(tools)['error'] == expected
    assert invoke(tools)['error'] == 'already_attempted'
    assert len(asked) == 1 and not world.inputs


def answer_pending(approvals, monkeypatch, after=lambda: None):
    # Run the synthetic owner's response when the real record exists, independent of host load.
    original = approvals.wait
    seen = []
    def wait(owner, record, seconds, sleep):
        seen.append(record)
        after()
        approvals.decide(owner, record, True, 'device-1')
        return original(owner, record, seconds, sleep)
    monkeypatch.setattr(approvals, 'wait', wait)
    return seen


def test_confirmed_send_is_never_repeated(driver, monkeypatch):
    tools, approvals, world, _, answers, _, _ = driver
    world.xml = screen_xml(node(text='Send message'))
    world.after_input = screen_xml(node(text='Send again'))
    answers.extend([CLICK, CLICK])
    approvals_seen = answer_pending(approvals, monkeypatch)
    result = invoke(tools)
    assert result['error'] == 'incomplete' and len(world.inputs) == len(approvals_seen) == 1


def test_unavailable_timeout_and_step_limit(driver, monkeypatch):
    tools, _, world, mod, answers, _, _ = driver
    answers.append(lambda: 1/0)
    result = invoke(tools)
    assert result['error'] == 'vision_unavailable' and 'auxiliary.vision' in result['message']
    release = threading.Event()
    answers.append(lambda: release.wait(5) and OK)
    monkeypatch.setattr(mod, 'MODEL_SECONDS', .05)
    try:
        assert invoke(tools)['error'] == 'timeout'
    finally:
        release.set()
    monkeypatch.setattr(mod, 'MAX_CYCLES', 1)
    monkeypatch.setattr(mod, 'MODEL_SECONDS', 5)
    answers.append(reply(action='wait', time=0))
    assert invoke(tools)['error'] == 'limit' and not world.inputs


def test_swipe_uses_exact_displayed_endpoints_and_checks_the_whole_path(driver):
    tools, _, world, _, answers, _, _ = driver
    answers.extend([reply(action='swipe', coordinate=[50, 800], coordinate2=[60, 200]), OK, OK])
    assert invoke(tools)['verified']
    touches = [v[1][0] for v in world.inputs if v[0] == 'touch']
    assert touches[0][:2] == (54, 1920) and touches[-1][:2] == (65, 480)
    world.inputs.clear()
    world.xml = screen_xml(node(text='private', password=True))
    answers.append(reply(action='swipe', coordinate=[50, 399], coordinate2=[950, 399]))
    result = tools.call({'task': 'Swipe across the form'}, session_id='chat', task_id='second-turn')
    assert result['error'] == 'masked' and not world.inputs


def test_scripted_type_goes_through_the_existing_focused_field(driver):
    tools, _, world, _, answers, _, _ = driver
    world.xml = screen_xml(node(cls='android.widget.EditText', focused=True, text='Title'))
    answers.extend([reply(action='type', text='note'), OK, OK])
    assert invoke(tools)['verified']
    assert ''.join(v[1] for v in world.inputs if v[0] == 'key') == 'note'


def test_stopped_running_agent_lease_is_fenced(phone_api):
    before = phone_api.lease.get()
    assert before.holder == 'agent'
    result = phone_api.client.post(PREFIX + '/mobile/agent-phone/stop?beacon_profile=default', json={})
    assert result.status_code == 202 and phone_api.lease.get().epoch != before.epoch


def test_native_route_selection_and_missing_model_without_fallback(phone, monkeypatch):
    """Real adapter entry, deterministic native client/config doubles. No network or personal profile."""
    from agent import auxiliary_client as aux, image_routing
    from hermes_cli import config

    tools = phone[0]
    mod = tools._sibling('phonedriver')
    requests, scopes, selected = [], [], []
    class Chat:
        def create(self, **opts):
            requests.append(opts)
            return SimpleNamespace(choices=[SimpleNamespace(message=SimpleNamespace(content=OK, reasoning_content='secret'))])
    client = SimpleNamespace(chat=SimpleNamespace(completions=Chat()))
    scoped = SimpleNamespace(scoped=lambda profile, fn: scopes.append(profile) or fn(None))
    monkeypatch.setitem(sys.modules, 'beacon_voice_context', scoped)
    monkeypatch.setattr(aux, '_read_main_model', lambda: 'synthetic-main')
    monkeypatch.setattr(aux, '_read_main_provider', lambda: 'custom')
    monkeypatch.setattr(aux, '_read_main_base_url', lambda: 'http://127.0.0.1:1/v1')
    monkeypatch.setattr(aux, '_read_main_api_key', lambda: '')
    monkeypatch.setattr(aux, 'auxiliary_max_tokens_param', lambda n, model: {'max_tokens': n})
    monkeypatch.setattr(config, 'load_config', lambda: {})
    monkeypatch.setattr(aux, '_resolve_task_provider_model', lambda _: ('auto', None, None, None, None))
    monkeypatch.setattr(image_routing, 'decide_image_input_mode', lambda *args: 'native')
    monkeypatch.setattr(aux, 'resolve_provider_client', lambda provider, **kw: selected.append(provider) or (client, kw['model']))
    messages = [{'role': 'user', 'content': [{'type': 'image_url', 'image_url': {'url': 'data:image/jpeg;base64,c3lu'}}]}]
    assert mod.infer('test-profile', messages, 7) == OK
    assert selected == ['custom'] and requests[0]['messages'] is messages and requests[0]['timeout'] == 7
    monkeypatch.setattr(aux, '_resolve_task_provider_model', lambda _: ('custom', 'Qwen3-VL', 'http://127.0.0.1:1/v1', None, None))
    def auxiliary(**kw):
        selected.append((kw['provider'], kw['base_url']))
        return 'custom', client, kw['model']
    monkeypatch.setattr(aux, 'resolve_vision_provider_client', auxiliary)
    assert mod.infer('test-profile', messages, 7) == OK
    assert requests[-1]['model'] == 'Qwen3-VL' and selected[-1] == ('custom', 'http://127.0.0.1:1/v1')
    monkeypatch.setattr(image_routing, 'decide_image_input_mode', lambda *args: 'text')
    monkeypatch.setattr(aux, '_resolve_task_provider_model', lambda _: ('auto', None, None, None, None))
    with pytest.raises(ValueError):
        mod.infer('test-profile', messages, 7)
    assert len(requests) == 2 and scopes == ['test-profile'] * 3


def test_stale_approval_cannot_dispatch_input(driver, monkeypatch):
    tools, approvals, world, _, answers, _, _ = driver
    world.xml = screen_xml(node(text='Pay now'))
    approvals.GRANTS.record(world.package, 'synthetic-card', 'USD 1.00', task='synthetic-turn', session='chat')
    assert approvals.GRANTS.owned(world.package, 'synthetic-turn', 'chat')
    answers.append(CLICK)
    records = answer_pending(approvals, monkeypatch, lambda: setattr(world, 'xml', screen_xml(node(text='Unrelated action'))))
    result = invoke(tools)
    assert not world.inputs and len(records) == 1 and result['error'] == 'changed'
    assert not approvals.GRANTS.owned(world.package, 'synthetic-turn', 'chat')


def test_takeover_with_pending_approval_withdraws_request(driver, monkeypatch):
    tools, approvals, world, _, answers, _, _ = driver
    world.xml = screen_xml(node(text='Send message'))
    answers.append(CLICK)
    original = approvals.wait
    def takeover(owner, record, seconds, sleep):
        assert approvals.open_records(owner)
        world.lease.update(holder='human', epoch=4)
        return original(owner, record, seconds, sleep)
    monkeypatch.setattr(approvals, 'wait', takeover)
    result = invoke(tools)
    assert result['error'] == 'busy' and not approvals.open_records('owner') and not world.inputs


def test_concurrent_task_and_wrong_profile_have_no_phone_effects(driver, monkeypatch):
    tools, _, world, _, answers, _, _ = driver
    with tools._sibling('agent_phone_lease').task_slot(tools.pointer()['folder']):
        assert invoke(tools)['error'] == 'busy'
    monkeypatch.setattr(tools, '_authorize', lambda session: ('owner', 'different-profile'))
    assert invoke(tools)['error'] == 'unavailable' and not world.started and not answers


def test_deadline_before_opening_phone(driver, monkeypatch):
    tools, _, world, mod, _, _, _ = driver
    monkeypatch.setattr(mod, 'TASK_SECONDS', 0)
    assert invoke(tools)['error'] == 'timeout' and not world.started


def request_id():
    return f'{int(time.time()*1000)}-{uuid.uuid4()}'


def test_http_task_uses_the_same_masked_loop_and_replays_only_the_summary(driver, phone_api, monkeypatch):
    tools, _, world, _, answers, _, _ = driver
    monkeypatch.setattr(tools, '_authorize', lambda _: pytest.fail('HTTP must not invent a chat session'))
    api = phone_api.client
    root = PREFIX + '/mobile/agent-phone'
    q = '?beacon_profile=default'
    goal, key = 'Open the synthetic Notes page', request_id()
    body = {'request_id': key, 'task': goal}
    answers.extend([CLICK, OK, OK])
    response = api.post(root+'/task'+q, json=body)
    assert response.status_code == 202, response.text
    assert response.json() == {'id': key, 'state': 'running', 'steps': 0}
    assert response.headers['cache-control'] == 'no-store'
    until = time.monotonic()+6
    while time.monotonic() < until:
        summary = api.get(root+q).json()['driver_task']
        if summary['state'] != 'running':
            break
        threading.Event().wait(.02)
    assert summary == {'id': key, 'state': 'completed', 'steps': 1}
    assert api.post(root+'/task'+q, json=body).json() == summary and len(world.inputs) == 1
    assert api.post(root+'/task'+q, json=dict(body, task='Different')).status_code == 409
    assert api.post(root+'/task/'+key+'/cancel'+q, json={}).json() == summary
    assert 'driver_task' not in api.get(root+'?beacon_profile=other').json()
    assert api.post(root+'/task/'+key+'/cancel?beacon_profile=other', json={}).status_code == 403
    receipt = (phone_api.folder/'run/phonedriver-mobile.sqlite').read_bytes()
    assert goal.encode() not in receipt and b'data:image' not in receipt


def test_http_cancel_during_model_request_and_reject_late_effects(driver, phone_api):
    _, _, world, _, answers, _, _ = driver
    started, release = threading.Event(), threading.Event()
    def delayed():
        started.set()
        release.wait(4)
        return CLICK
    answers.append(delayed)
    key = request_id()
    url = PREFIX + '/mobile/agent-phone/task'
    q = '?beacon_profile=default'
    try:
        assert phone_api.client.post(url+q, json={'request_id': key, 'task': 'Open Notes'}).status_code == 202
        assert started.wait(3)
        response = phone_api.client.post(url+'/'+key+'/cancel'+q, json={})
        assert response.status_code == 200 and response.json()['state'] == 'cancelled'
    finally:
        release.set()
    threading.Event().wait(.2)
    assert not world.inputs


def test_http_rejects_expired_unlinked_and_changed_scope(driver, phone_api):
    _, approvals, world, _, _, _, _ = driver
    url = PREFIX + '/mobile/agent-phone/task?beacon_profile=default'
    def post(key, **extra):
        return phone_api.client.post(url, json=dict(request_id=key, task='Task', **extra))
    assert post('1000000000000-stale').status_code == 400
    malformed = post(request_id(), owner='forged')
    assert malformed.status_code == 400 and malformed.json() == {'detail': 'Expected request_id and task'}
    approvals.set_policy('owner', enabled=False)
    disabled = post(request_id())
    assert disabled.status_code == 403 and disabled.json() == {'detail': 'Phone use is turned off'}
    approvals.set_policy('owner', enabled=True)
    phone_api.lease.acquire('owner-view')
    busy = post(request_id())
    assert busy.status_code == 409
    assert busy.json() == {'detail': 'The owner has the phone', 'line': 'The owner has the phone', 'reason': 'phone.busy'}
    phone_api.client.headers.pop('x-beacon-device-id')
    assert post(request_id()).status_code in (401, 403)
    assert not world.inputs


def test_restart_recovers_a_stranded_receipt_without_replaying(driver, tmp_path, monkeypatch):
    tools, approvals, *_ = driver
    module = tools._sibling('phonedriver_mobile')
    store = module.Tasks(tmp_path, 'owner', 'basic', 'default', approvals)
    key = request_id()
    monkeypatch.setattr(module.threading.Thread, 'start', lambda _: None)
    execute = lambda *args: pytest.fail('Must never replay the request')
    assert store.submit(key, 'Private task', lambda: 0, execute, 180)['state'] == 'running'
    monkeypatch.setattr(approvals, '_holder_alive', lambda _: False)
    reopened = module.Tasks(tmp_path, 'owner', 'basic', 'default', approvals)
    assert reopened.latest()['state'] == 'failed'
    assert reopened.submit(key, 'Private task', lambda: 0, execute, 180)['state'] == 'failed'


def test_receipt_capacity_expiry_and_redelivery(driver, tmp_path, monkeypatch):
    from fastapi import HTTPException
    tools, approvals, *_ = driver
    mod = tools._sibling('phonedriver_mobile')
    monkeypatch.setattr(mod, 'MAX_RECEIPTS', 1)
    monkeypatch.setattr(mod.threading.Thread, 'start', lambda _: None)
    store = mod.Tasks(tmp_path, 'owner', 'basic', 'default', approvals)
    stamp = int(time.time()*1000)
    first = f'{stamp}-first'
    noop = lambda *a: None
    store.submit(first, 'one', noop, noop, 180)
    store.cancel(first)
    with pytest.raises(HTTPException) as capacity:
        store.submit(f'{stamp}-second', 'two', noop, noop, 180)
    assert capacity.value.status_code == 429
    future = stamp+mod.DAY_MS+1
    monkeypatch.setattr(mod.time, 'time', lambda: future/1000)
    assert store.submit(f'{future}-second', 'two', noop, noop, 180)['state'] == 'running'
    with pytest.raises(HTTPException) as expired:
        store.submit(first, 'one', noop, noop, 180)
    assert expired.value.status_code == 400


def test_upstream_thought_is_discarded(driver):
    mod = driver[3]
    assert mod.parse('Thought: hidden\nAction: done\n'+OK) == {'action': 'terminate', 'status': 'success'}


def test_prompt_examples_use_the_upstream_action_field(driver):
    mod = driver[3]
    examples = [line for line in mod.PROMPT.splitlines() if line.startswith('<tool_call>')]
    assert [mod.parse(line) for line in examples] == [
        {'action': 'click', 'coordinate': [500, 400]}, {'action': 'terminate', 'status': 'success'}]
    with pytest.raises(mod.Failure):
        mod.parse('<tool_call>{"name":"mobile_use","arguments":{"click":[500,400]}}</tool_call>')
