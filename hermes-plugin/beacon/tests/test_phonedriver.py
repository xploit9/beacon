"""Synthetic policy/phone fixtures and scripted model answers; no model or device is used here."""
import json
import sys
import threading
import time
import uuid
from types import SimpleNamespace

import pytest
from test_agent_phone_tools import (CHROME, CLOCK, LAUNCHER, SHOP_HOME, FakeAdb, FakeHost, _shop_page, fixture, harness,
                                   load, node, phone, screen_xml)  # noqa: F401
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


SHOWN = (720, 1600)  # the image the model is sent: the fake phone's 1080x2400 screen at CAPTURE_WIDTH
CLICK = reply(action='click', coordinate=[353, 638])  # a pixel of that image, on the Notes button
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


@pytest.mark.parametrize('when', ['at_start', 'after_tap'])
def test_r4_shade_is_collapsed_and_the_task_continues(driver, monkeypatch, when):
    from test_agent_phone_tools import Done, FakeAdb
    tools, _, world, _, answers, prompts, _ = driver
    normal = world.xml
    shade = screen_xml(node(text='Synthetic notification', package='com.android.systemui'), package='com.android.systemui')
    def open_shade():
        world.xml, world.focus_override = shade, 'NotificationShade'
        world.dumpsys_windows = ('  Window #0 Window{ab u0 NotificationShade}:\n    package=com.android.systemui\n'
                                 '    mAttrs={ty=NOTIFICATION_SHADE\n      fl=NOT_FOCUSABLE HARDWARE_ACCELERATED\n    }\n')
    if when == 'at_start':
        open_shade()
    else:
        monkeypatch.setattr(world, 'on_input', open_shade)
    shell, collapsed = FakeAdb.shell, []
    def phone_shell(adb, args, **options):
        if args == ['cmd', 'statusbar', 'collapse']:
            collapsed.append(True)
            world.xml, world.focus_override = normal, None
            world.dumpsys_windows = None
            return Done('')
        return shell(adb, args, **options)
    monkeypatch.setattr(FakeAdb, 'shell', phone_shell)
    answers.extend([CLICK, OK, OK])
    result = invoke(tools)
    assert result.get('verified') and collapsed == [True] and len(world.inputs) == 1
    assert 'Synthetic notification' not in str(prompts)


def test_r4_real_secure_window_stops_without_collapsing_or_showing_it(driver):
    tools, _, world, _, answers, prompts, _ = driver
    focused = world.package + '/.Private'
    world.shell_answers = [
        ('mCurrentFocus', '  mCurrentFocus=Window{123 u0 ' + focused + '}'),
        ('^dumpsys window windows$', '  Window #0 Window{123 u0 ' + focused + '}:\n'
         '    mAttrs={(0,0)(fillxfill) ty=BASE_APPLICATION\n      fl=NOT_FOCUSABLE SECURE\n    }\n    isVisible=true'),
    ]
    answers.extend([OK, OK])
    assert invoke(tools).get('error') == 'masked'
    assert not prompts and not world.inputs and ('shell', ['cmd', 'statusbar', 'collapse']) not in world.calls


@pytest.mark.parametrize(('code', 'flags'), [(None, ''), (None, '      fl=NOT_FOCUSABLE\n'),
                                              (0, ''), (0, '      fl=\n'), (0, '      fl=???\n'),
                                              (0, '      fl=NOT_FOCUSABLE FLAG_SECURE\n'),
                                              (0, '      fl=NOT_FOCUSABLE\n      ROTATION_90={ty=NOTIFICATION_SHADE\n          fl=SECURE\n      }}\n')])
def test_r5_an_incomplete_shade_record_never_authorizes_collapse(driver, code, flags):
    tools, _, world, _, _, prompts, _ = driver
    world.focus_override = 'NotificationShade'
    dump = ('  Window #0 Window{ab u0 NotificationShade}:\n    package=com.android.systemui\n'
            '    mAttrs={(0,0)(fillxfill) ty=NOTIFICATION_SHADE\n' + flags +
            '  Window #1 Window{ac u0 StatusBar}:\n    mAttrs={ty=STATUS_BAR\n      fl=NOT_FOCUSABLE\n    }\n')
    world.shell_answers = [('^dumpsys window windows$', (dump, code))]
    result = invoke(tools)
    assert ('shell', ['cmd', 'statusbar', 'collapse']) not in world.calls
    assert result.get('error') == 'masked' and not prompts and not world.inputs


def set_status_frame(world, frame):
    dump = FakeAdb(world).shell(['dumpsys', 'window', 'windows']).stdout
    world.dumpsys_windows = dump.replace('frame=[0,0][1080,63]', frame)


@pytest.mark.parametrize(('frame', 'y'), [('', 50), ('frame=[0,0][1080,63]', 42)])
def test_w01_swipe_outside_the_default_or_measured_band_reaches_the_phone(driver, frame, y):
    tools, _, world, _, answers, _, _ = driver
    set_status_frame(world, frame)
    answers.extend([reply(action='swipe', coordinate=[36, y], coordinate2=[36, 400]), OK, OK])
    assert invoke(tools).get('verified')
    touches = [points[0][:2] for kind, points in world.inputs if kind == 'touch']
    assert touches[0] == (54, round(y * 2399 / 1599)) and touches[-1] == (54, 600)


@pytest.mark.parametrize(('frame', 'y', 'reason'), [('frame=[0,0][1080,120]', 70, 'swipe_status_bar'),
                                                   ('', 40, 'swipe_status_bar_fallback')])
def test_r4_swipe_from_the_status_bar_never_reaches_the_phone(driver, caplog, frame, y, reason):
    tools, _, world, _, answers, prompts, _ = driver
    caplog.set_level('INFO')
    set_status_frame(world, frame)
    swipe = reply(action='swipe', coordinate=[370, y], coordinate2=[370, 450])
    answers.extend([swipe, swipe])
    result = invoke(tools)
    assert result.get('error') == 'blocked' and 'status bar' in result['message'] and not world.inputs
    assert result['planner_calls'] == 2 and result['steps'] == 0
    instruction = json.loads(prompts[1][1]['content'][0]['text'])['instruction']
    assert 'status bar' in instruction and 'Home or Back' in instruction
    assert f'PhoneDriver re-planned ({reason})' in caplog.messages and f'PhoneDriver stopped ({reason})' in caplog.messages


@pytest.mark.parametrize(('key', 'device_key'), [('home', 'GoHome'), ('back', 'GoBack')])
def test_w01_task_inside_clock_reaches_settings(driver, monkeypatch, key, device_key):
    tools, _, world, _, answers, prompts, _ = driver
    settings = 'com.android.settings'
    world.package, world.xml = CLOCK, screen_xml(node(text='Timer', package=CLOCK), package=CLOCK, title='Clock')
    drawer = fixture('launcher_drawer.xml')
    press, swipe = tools.Phone.key, tools.Phone.swipe

    def navigate(phone, name):
        press(phone, name)
        if name == device_key and world.package == CLOCK:
            world.package, world.xml = LAUNCHER, fixture('launcher_home.xml')

    def open_drawer(phone, start, end, **kwargs):
        swipe(phone, start, end, **kwargs)
        if world.package == LAUNCHER and start[1] > end[1]:
            world.xml = drawer

    def open_settings(x, y):
        if world.xml == drawer and 46 <= x < 293 and 1979 <= y < 2307:  # Settings in the drawer fixture
            world.package, world.xml = settings, screen_xml(node(text='Display', package=settings), package=settings, title='Settings')

    monkeypatch.setattr(tools.Phone, 'key', navigate)
    monkeypatch.setattr(tools.Phone, 'swipe', open_drawer)
    world.tap_hooks.append(open_settings)
    answers.extend([reply(action='swipe', coordinate=[370, 40], coordinate2=[370, 450]), reply(action='key', key=key),
                    reply(action='swipe', coordinate=[360, 1200], coordinate2=[360, 300]),
                    reply(action='click', coordinate=[110, 1427]), OK, OK])
    result = tools.call({'task': 'Open the Settings app'}, session_id='chat', task_id='settings-turn')
    assert result.get('verified') and result['steps'] == 3, result
    assert [json.loads(p[1]['content'][1]['text'])['package'] for p in prompts] == [CLOCK, CLOCK, LAUNCHER, LAUNCHER, settings, settings]
    assert 'Home or Back' in json.loads(prompts[1][1]['content'][0]['text'])['instruction']
    assert world.inputs[0][:2] == ('key', device_key) and world.inputs[1][0] == 'touch' and world.inputs[-1][0] == 'tap'


def test_w01_navigation_key_rechecks_the_frame_before_dispatch(driver):
    tools, _, world, _, answers, _, _ = driver

    def move():
        world.xml = screen_xml(node(text='New screen ' + str(world.dumps)))
        return reply(action='key', key='back')

    answers.extend([move, move])
    result = invoke(tools)
    assert result.get('error') == 'changed' and result['planner_calls'] == 2 and not world.inputs


def test_w01_sensitive_swipe_replans_with_its_own_reason(driver, caplog):
    tools, _, world, _, answers, prompts, _ = driver
    world.xml = screen_xml(node(text='Factory reset'))
    swipe = reply(action='swipe', coordinate=[360, 1200], coordinate2=[360, 300])
    caplog.set_level('INFO')
    answers.extend([swipe, swipe])
    result = invoke(tools)
    assert result.get('error') == 'blocked' and not world.inputs and result['planner_calls'] == 2
    instruction = json.loads(prompts[1][1]['content'][0]['text'])['instruction']
    assert 'sensitive control' in instruction and 'Factory reset' not in instruction
    assert 'PhoneDriver stopped (swipe_sensitive_control)' in caplog.messages


def test_r4_a_shade_title_alone_does_not_authorize_a_collapse(driver):
    tools, _, world, _, _, prompts, _ = driver
    world.focus_override = 'NotificationShade'
    world.dumpsys_windows = ('  Window #0 Window{aa u0 NotificationShade}:\n    package=com.android.systemui.example\n'
                             '    mAttrs={ty=NOTIFICATION_SHADE\n      fl=NOT_FOCUSABLE\n    }\n')
    assert invoke(tools).get('error') == 'masked'
    assert not prompts and not world.inputs and ('shell', ['cmd', 'statusbar', 'collapse']) not in world.calls


@pytest.mark.parametrize('action', [
    {'action': 'click', 'coordinate': [0, 1600]}, {'action': 'click', 'coordinate': [720, 0]}, {'action': 'click', 'coordinate': [-1, 0]},
    {'action': 'click', 'coordinate': [True, 0]}, {'action': 'click', 'coordinate': [1.5, 1]}, {'action': 'click', 'coordinate': [1, 2, 3]},
    {'action': 'swipe', 'coordinate': [1, 1], 'coordinate2': [1, 1]}, {'action': 'swipe', 'coordinate': [1, 1]},
    {'action': 'key', 'key': 'recents'}, {'action': 'key', 'key': 'enter'}, {'action': 'wait', 'time': float('nan')},
    {'action': 'type', 'text': 'a\nb'}, {'action': 'terminate', 'status': 'maybe'},
    {'action': 'shell', 'command': 'id'}, {'action': 'click', 'coordinate': [1, 2], 'extra': 5}])
def test_strict_action_validation(driver, action):
    mod = driver[3]
    with pytest.raises(mod.Failure):
        mod.parse(reply(**action), SHOWN)
    assert mod.parse(reply(action='click', coordinate=[719, 1599]), SHOWN)['coordinate'] == [719, 1599]
    assert mod.parse(reply(action='click', coordinate=[0, 0]), SHOWN)['coordinate'] == [0, 0]
    with pytest.raises(mod.Failure):
        mod.parse(OK + OK, SHOWN)
    with pytest.raises(mod.Failure):
        mod.parse(OK.replace('mobile_use', 'unexpected'), SHOWN)


def test_coordinates_are_pixels_of_the_exact_image_sent(driver):
    """One frame (Q2, 22 Nov: the 0-999 prompt beside a 720x1600 picture drew pixel answers that were rejected or tapped elsewhere):
    the prompt, the summary's size and the picture agree, and parse() bounds a reply by that picture alone, never rescaling it."""
    from base64 import b64decode
    from io import BytesIO
    from PIL import Image
    tools, _, world, mod, answers, prompts, _ = driver
    assert '999' not in mod.PROMPT and 'Its size is the image field (width x height)' in mod.PROMPT
    answers.extend([CLICK, OK, OK])
    assert invoke(tools)['verified'] and world.inputs[0][:3] == ('tap', 540, 963)
    summary, picture = prompts[0][1]['content'][1:]
    with Image.open(BytesIO(b64decode(picture['image_url']['url'].split(',')[1]))) as image:
        assert json.loads(summary['text'])['image'] == f'{image.width}x{image.height}' == '%dx%d' % SHOWN
    # The bound is the image the reply answers, not a fixed grid: the same pixel fits a wider picture only.
    wide = reply(action='click', coordinate=[900, 1700])
    assert mod.parse(wide, (1080, 2400))['coordinate'] == [900, 1700]
    with pytest.raises(mod.Failure):
        mod.parse(wide, SHOWN)
    # A reply outside the picture (a 0-999 grid answer on the right edge) is refused after one fresh re-plan; nothing is tapped.
    world.inputs.clear()
    answers.extend([reply(action='click', coordinate=[870, 847]), reply(action='swipe', coordinate=[360, 1700], coordinate2=[360, 400])])
    result = tools.call({'task': 'Open Notes'}, session_id='chat', task_id='grid-turn')
    assert result['error'] == 'invalid_plan' and result['planner_calls'] == 2 and not world.inputs


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


def test_invalid_reply_replans_once_with_a_fresh_masked_screen(driver):
    tools, _, world, _, answers, prompts, _ = driver
    answers.extend(['unusable model reply', CLICK, OK, OK])
    result = invoke(tools)
    assert result['success'] and result['planner_calls'] == 4 and result['captures'] == 5
    assert len(world.inputs) == 1 and 'unusable model reply' not in str(prompts)


def test_two_invalid_replies_stop_without_input(driver, caplog):
    tools, _, world, _, answers, _, _ = driver
    caplog.set_level('INFO')
    answers.extend(['private synthetic screen words', OK + OK])
    result = invoke(tools)
    assert result['error'] == 'invalid_plan' and result['planner_calls'] == result['captures'] == 2
    assert not world.inputs
    assert sum('PhoneDriver rejected a model reply (response format)' in r.getMessage() for r in caplog.records) == 2
    assert 'private synthetic screen words' not in caplog.text


def test_plain_json_accepts_only_a_complete_validated_mobile_call(driver):
    module = driver[3]
    call = CLICK.removeprefix('<tool_call>').removesuffix('</tool_call>')
    assert module.parse(call, SHOWN) == module.parse(CLICK, SHOWN)
    for invalid in (call + call, 'choose ' + call, call.replace('353', '720'), call.replace('mobile_use', 'other')):
        with pytest.raises(module.Failure):
            module.parse(invalid, SHOWN)


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
    if change in ('tree', 'pixels'):
        # R3-F1: a screen change before the input re-plans once from a fresh capture; a second change in the same step ends the task.
        def changed_again():
            if change == 'tree':
                world.xml = screen_xml(node(text='Different again'))
            else:
                monkeypatch.setattr(FakeGrpc, 'screenshot', lambda self, *a, **kw: dict(width=kw.get('width') or 1080,
                    height=kw.get('height') or 2400, image=bytes([7]) * ((kw.get('width') or 1080) * (kw.get('height') or 2400) * 3)))
            return CLICK
        answers.append(changed_again)
    assert invoke(tools_mod)['error'] == code and not world.inputs


def test_r3_f1_a_screen_change_before_the_input_replans_once_from_a_fresh_capture(driver, caplog):
    """Q2 R3-F1: a status-bar tick or a notification between the plan's capture and the input stopped half the tasks with "The phone
    screen changed." Nothing was sent, so the step is planned again from a fresh capture, where every check runs again."""
    tools, _, world, _, answers, prompts, _ = driver
    caplog.set_level('INFO')
    def notification():
        world.xml = screen_xml(node(text='Notes'), node('android.widget.TextView', text='12:01', clickable=False, bounds=(900, 0, 1080, 60)))
        return CLICK
    world.after_input = screen_xml(node(text='Notes are open'))
    answers.extend([notification, CLICK, OK, OK])
    result = invoke(tools)
    assert result.get('success') is True and result['planner_calls'] == 4 and len(world.inputs) == 1
    assert '12:01' in str(prompts[1][1]['content'][1])  # the re-plan saw the fresh screen
    assert any('PhoneDriver re-planned (changed)' in r.getMessage() for r in caplog.records)


def test_r3_f1_a_change_that_goes_away_replans_the_same_input_on_the_same_screen(driver, monkeypatch):
    """A caret blinks or a toast comes and goes: the input's check sees another screen once, and the re-plan sees the first one again and
    proposes the same tap. It was never sent, so it is no repeat ("unchanged"); it runs once. A wait step resets the one re-plan."""
    tools, _, world, _, answers, _, _ = driver
    real_capture, blinked = tools.Call.do_capture, []
    def capture(self, encode=True):
        if encode or blinked:
            return real_capture(self, encode)
        blinked.append(1)
        steady, world.xml = world.xml, screen_xml(node(text='Notes'), node('android.widget.TextView', text='Saved', clickable=False, bounds=(63, 2000, 1017, 2100)))
        try:
            return real_capture(self, encode)
        finally:
            world.xml = steady
    monkeypatch.setattr(tools.Call, 'do_capture', capture)
    world.after_input = screen_xml(node(text='Notes are open'))
    answers.extend([CLICK, CLICK, OK, OK])
    result = invoke(tools)
    assert result.get('success') is True and len(world.inputs) == 1 and result['planner_calls'] == 4, result


def test_validation_captures_skip_model_payload_encoding(driver, monkeypatch):
    tools, _, world, _, answers, prompts, _ = driver
    encoded, captures = [], []
    real_b64, real_capture = tools.base64.b64encode, tools.Call.do_capture
    monkeypatch.setattr(tools.base64, 'b64encode', lambda data: encoded.append(1) or real_b64(data))
    monkeypatch.setattr(tools.Call, 'do_capture', lambda self, encode=True: captures.append(encode) or real_capture(self, encode))
    answers.extend([CLICK, OK, OK])
    assert invoke(tools).get('error') is None and world.inputs
    assert False in captures and len(encoded) == len(prompts) == captures.count(True)


def test_private_screen_at_validation_still_blocks_input_without_encoding(driver, monkeypatch):
    tools, _, world, _, answers, _, _ = driver
    encoded = []
    real_b64 = tools.base64.b64encode
    monkeypatch.setattr(tools.base64, 'b64encode', lambda data: encoded.append(1) or real_b64(data))
    def private():
        world.xml = screen_xml(node(text='synthetic-private-example', password=True))
        return CLICK
    # The validation capture is never encoded; the re-plan's capture is a masked planning frame, and its tap on the field is refused.
    answers.extend([private, CLICK])
    assert invoke(tools)['error'] == 'masked' and not world.inputs and len(encoded) == 2


@pytest.mark.parametrize('withheld,code', [({'error': 'masked'}, 'masked'), ({'error': 'private'}, 'private'), ({}, 'masked')])
def test_a_withheld_validation_frame_raises_and_dispatches_no_input(driver, monkeypatch, withheld, code):
    tools, _, world, _, answers, _, _ = driver
    real_capture = tools.Call.do_capture
    # Planning sees a normal frame; the validation capture (encode=False, in before_effect) is withheld by the guard.
    monkeypatch.setattr(tools.Call, 'do_capture', lambda self, encode=True: real_capture(self, encode) if encode else withheld)
    answers.append(CLICK)
    assert invoke(tools)['error'] == code and not world.inputs


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
    answers.extend([reply(action='swipe', coordinate=[36, 1280], coordinate2=[43, 320]), OK, OK])
    assert invoke(tools)['verified']
    touches = [v[1][0] for v in world.inputs if v[0] == 'touch']
    assert touches[0][:2] == (54, 1920) and touches[-1][:2] == (65, 480)
    world.inputs.clear()
    world.xml = screen_xml(node(text='private', password=True))
    answers.append(reply(action='swipe', coordinate=[36, 638], coordinate2=[684, 638]))
    result = tools.call({'task': 'Swipe across the form'}, session_id='chat', task_id='second-turn')
    assert result['error'] == 'masked' and not world.inputs


def test_d2c_scrolling_a_priced_product_list_is_not_blocked(driver):
    """D2c: every tile shows a price; before, each counted as a payment control and the scroll was blocked as crossing one."""
    tools, _, world, _, answers, _, _ = driver
    world.xml = SHOP_HOME
    answers.extend([reply(action='swipe', coordinate=[180, 1300], coordinate2=[180, 500]), OK, OK])
    result = invoke(tools)
    assert result.get('verified'), result
    assert [v for v in world.inputs if v[0] == 'touch']


def test_r3_f11_a_task_scrolls_a_shop_page_past_its_buttons(driver):
    """Q2 R3-F11 (state.db 494): a task's scroll on the store was "blocked" as crossing a sensitive control, since a footer Subscribe below
    the fold made the page a checkout and every button the swipe crossed asked. A vertical scroll off a checkout crosses nothing sensitive."""
    tools, _, world, _, answers, _, _ = driver
    world.xml = _shop_page(node(text='SUBSCRIBE', rid='newsletter-subscribe-button', bounds=(0, 2337, 1080, 2337), package=CHROME))
    answers.extend([reply(action='swipe', coordinate=[360, 1000], coordinate2=[360, 300]), OK, OK])
    result = invoke(tools)
    assert result.get('verified'), result
    assert [v for v in world.inputs if v[0] == 'touch']


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
    assert mod.parse('Thought: hidden\nAction: done\n'+OK, SHOWN) == {'action': 'terminate', 'status': 'success'}


def test_prompt_examples_use_the_upstream_action_field(driver):
    mod = driver[3]
    examples = [line for line in mod.PROMPT.splitlines() if line.startswith('<tool_call>')]
    assert [mod.parse(line, SHOWN) for line in examples] == [
        {'action': 'click', 'coordinate': [212, 948]}, {'action': 'key', 'key': 'home'}, {'action': 'terminate', 'status': 'success'}]
    with pytest.raises(mod.Failure):
        mod.parse('<tool_call>{"name":"mobile_use","arguments":{"click":[500,400]}}</tool_call>', SHOWN)
