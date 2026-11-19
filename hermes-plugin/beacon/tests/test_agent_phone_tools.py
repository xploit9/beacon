"""The agent's phone tool: risk check, hard blocks, bound approvals, typing, the tree on stdout, masked captures, links.

No emulator: a fake PhoneAdb (no adb server, as L1b's), a fake gRPC client and fixture screens laid out as the spike's
vaultapps shop app draws them (tests/fixtures/agent_phone). The approvals are the real module on a private home.
"""
import importlib.util
import json
import logging
import re
import subprocess
import sys
import threading
import time
import types
from pathlib import Path

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / 'fixtures' / 'agent_phone'
PKG = 'dev.beacon.fixture.shop'
ADDRESS = 'agent' + '@' + 'example.com'


def load(name, key):
    spec = importlib.util.spec_from_file_location(key, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    sys.modules[key] = module
    spec.loader.exec_module(module)
    return module


def fixture(name):
    return (FIXTURES / name).read_text(encoding='utf-8')


def node(cls='android.widget.Button', text='', desc='', hint='', bounds=(63, 900, 1017, 1026), clickable=True, focused=False,
         password=False, scrollable=False, package=PKG, rid=''):
    from xml.sax.saxutils import quoteattr
    attrs = {'text': text, 'resource-id': rid, 'class': cls, 'package': package, 'content-desc': desc, 'clickable': str(clickable).lower(),
             'enabled': 'true', 'focusable': str(clickable).lower(), 'focused': str(focused).lower(), 'scrollable': str(scrollable).lower(),
             'long-clickable': 'false', 'password': str(password).lower(), 'checkable': 'false',
             'bounds': '[%d,%d][%d,%d]' % bounds, 'hint': hint}
    return '<node ' + ' '.join(f'{k}={quoteattr(v)}' for k, v in attrs.items()) + ' />'


def screen_xml(*nodes, package=PKG, title='Kanto Foods'):
    head = node('android.widget.TextView', text=title, bounds=(63, 252, 1017, 344), clickable=False, package=package)
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            f'<node index="0" text="" class="android.widget.FrameLayout" package="{package}" content-desc="" clickable="false" '
            'enabled="true" focusable="false" focused="false" scrollable="false" password="false" bounds="[0,0][1080,2400]" hint="">'
            + head + ''.join(nodes) + '</node></hierarchy>')


# ---- fakes: L1b's adb and gRPC, L9's read guard, channel and vault, L2's lease, L1a's checks --------------------------------

class Done:
    def __init__(self, stdout='', returncode=0, stderr=''):
        self.stdout, self.returncode, self.stderr = stdout, returncode, stderr


def as_windows(xml):
    """The plain tree as `uiautomator dump --windows` lists it for a screen with one window: that window, active."""
    start, end = xml.find('<hierarchy'), xml.rfind('</hierarchy>') + len('</hierarchy>')
    if start < 0 or end <= start:
        return xml
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id=\"0\"><window index=\"0\" title=\"\" "
            'bounds="[0,0][1080,2400]" active="true" focused="true" accessibility-focused="false" id="20" layer="0" type="TYPE_APPLICATION">'
            + xml[start:end] + '</window></display></displays>')


class FakeAdb:
    """PhoneAdb's shape: shell(args) and exec_out(args); every call recorded."""

    def __init__(self, world):
        self.world = world

    def exec_out(self, args, timeout=60.0):
        self.world.calls.append(('exec_out', list(args)))
        self.world.dumps += 1
        self.world.on_dump()
        xml = self.world.xml
        if '--windows' in args and not self.world.windows_plain:
            xml = self.world.windows_xml or as_windows(self.world.xml)
        return (xml + 'UI hierchary dumped to: /dev/tty\n').encode('utf-8')

    def shell(self, args, timeout=60.0, input=None, binary=False):
        self.world.calls.append(('shell', args if isinstance(args, str) else list(args)))
        text = args if isinstance(args, str) else ' '.join(args)
        for pattern, answer in self.world.shell_answers:
            if re.search(pattern, text):
                return Done(*answer) if isinstance(answer, tuple) else Done(answer)
        if args == ['input', 'keyevent', 'KEYCODE_BACK']:
            self.world.backs += 1
            if not self.world.prompt_sticky:
                self.world.focus_override = None
            return Done('')
        if text.startswith('dumpsys window') and self.world.focus_override:
            return Done(f'  mCurrentFocus=Window{{77aa u0 {self.world.focus_override}}}\n')
        if text.startswith('dumpsys window') and not text.startswith('dumpsys window windows') and self.world.xml == SAVE_PROMPT_XML:
            # Android's own save prompt, as measured: the window `android` over the app that asked
            return Done('  mCurrentFocus=Window{146d42b u0 android}\n'
                        f'  mFocusedApp=ActivityRecord{{202457094 u0 {self.world.behind}/.LoginActivity t44}}\n')
        if text.startswith('dumpsys window windows') and self.world.dumpsys_windows is not None:
            return Done(self.world.dumpsys_windows)
        if text.startswith('dumpsys window windows'):
            # The input method window as measured on the agent phone: its touchable region while it is on screen.
            region = ''.join('(%d,%d,%d,%d)' % r for r in (self.world.keyboard or []))
            return Done('  Window #7 Window{16dee7b u0 InputMethod}:\n    mIsImWindow=true\n'
                        f'    touchable region=SkRegion({region})\n    isOnScreen={str(bool(self.world.keyboard)).lower()}\n'
                        f'    isVisible={str(bool(self.world.keyboard)).lower()}\n  Window #8 Window{{1 u0 NavigationBar0}}:\n')
        if text.startswith('dumpsys input_method'):
            return Done(f'  mInputShown={str(bool(self.world.keyboard)).lower()}\n')
        if text.startswith('dumpsys window'):
            return Done(f'  mCurrentFocus=Window{{4f2a u0 {self.world.package}/{self.world.package}.{self.world.activity}}}\n'
                        f'  mFocusedApp=ActivityRecord{{1 u0 {self.world.package}/.{self.world.activity} t44}}\n')
        return Done('')

    def __call__(self, args):
        return self.shell(args)


class FakeGrpc:
    def __init__(self, world):
        self.world = world

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def screenshot(self, fmt=2, width=0, height=0, display=0, timeout=30.0):
        w, h = (width or 1080), (height or 2400)
        self.world.calls.append(('screenshot', (w, h)))
        return {'width': w, 'height': h, 'image': bytes([200]) * (w * h * 3)}

    def tap(self, x, y, hold=0.05, ident=1):
        self.world.inputs.append(('tap', x, y, hold))
        self.world.on_tap(x, y)
        self.world.on_input()

    def touch(self, touches, display=0):
        self.world.inputs.append(('touch', tuple(touches)))

    def key(self, key=None, text=None, keycode=None, code_type=1, event_type=2):
        self.world.inputs.append(('key', key, keycode, event_type))


class FakeHost:
    def __init__(self, world):
        self.world = world

    def adb(self, state=None):
        return FakeAdb(self.world)

    def grpc(self, state=None, timeout=15.0):
        return FakeGrpc(self.world)

    def note_activity(self):
        self.world.activity += 1


class World:
    """The phone as the fakes see it: the screen now, and what changes it."""

    def __init__(self):
        self.xml = fixture('shop_checkout.xml')
        self.package, self.activity = PKG, 'CardActivity'
        self.calls, self.inputs = [], []
        self.dumps = 0
        self.windows_xml = None  # what `uiautomator dump --windows` answers; None: the plain tree as one active window
        self.windows_plain = False  # True: a phone whose `--windows` still answers the plain tree (no window list)
        self.dumpsys_windows = None  # what `dumpsys window windows` answers; None: the fake's input method block alone (no app window named)
        self.activity = 0
        self.shell_answers = []
        self.after_dumps = {}  # dump number -> new xml
        self.after_input = None
        self.keyboard = False
        self.focus_override = None  # a component in focus that is not the app (a debugging prompt)
        self.behind = PKG  # the app under Android's own save prompt
        self.prompt_sticky = False
        self.backs = 0
        self.live_session = None
        self.tap_hooks = []

    def on_tap(self, x, y):
        for hook in list(self.tap_hooks):
            hook(x, y)

    def on_dump(self):
        if self.dumps in self.after_dumps:
            self.xml = self.after_dumps[self.dumps]

    def on_input(self):
        if self.after_input:
            self.xml, self.after_input = self.after_input, None


class MaskSet:
    def __init__(self, store=None):
        self.store = store
        self.values, self.filled_packages = set(), set()

    def register(self, value, package=None):
        self.values.add(value)
        if package:
            self.filled_packages.add(package)


def fake_redact():
    """L9's read guard, small: password nodes and registered values masked; an unreadable screen after a fill refused."""
    module = types.ModuleType('beacon_agent_phone_redact')
    import xml.etree.ElementTree as ET

    def mask_tree(xml_text, masks, package=None, commit=True):
        root = ET.fromstring(xml_text)
        texts = [n for n in root.iter('node') if n.get('text') or n.get('content-desc')]
        if len(texts) < 2:
            if package in masks.filled_packages:
                return None, None, {'refused': 'The screen has a saved login on it.'}
            return '', [], {}
        boxes = []
        for n in root.iter('node'):
            if n.get('password') == 'true' and n.get('text') or any(v in (n.get('text') or '') for v in masks.values):
                n.set('text', '••••')
                boxes.append((0, 0, 1, 1))
        return ET.tostring(root, encoding='unicode'), boxes, {'password': len(boxes)} if boxes else {}

    def mask_capture(xml_text, image, masks, package=None, screen=(1080, 2400), commit=True):
        module.captures.append(package)
        tree, boxes, counts = mask_tree(xml_text, masks, package)
        if tree is None:
            return {'refused': counts['refused']}
        return {'tree': tree, 'image': image, 'masked': counts}
    module.MaskSet, module.mask_tree, module.mask_capture, module.captures = MaskSet, mask_tree, mask_capture, []
    return module


class FakeSession:
    def __init__(self, world):
        self.world, self.requests, self.closed = world, [], False

    def request(self, t, timeout=None, **fields):
        self.requests.append((t, fields))
        self.world.channel.append((t, fields))
        return {'t': 'ack'}

    def close(self, reason='done'):
        self.closed = True
        if self.world.live_session is self:
            self.world.live_session = None


class FakeVault:
    def __init__(self, world):
        self.world, self.calls = world, []
        self.fill_results = []
        self.since = []  # what the tool passed as L9's `since` (its last submit), per code / open_link call

    def handler(self, session, owner=False):
        def on_event(message):
            if message.get('t') == 'save' and not owner:
                self.calls.append(('save_event', owner))
                self.built_with['notify']('vault_save', {'held': 'held_save1', 'label': 'Kanto Foods', 'identifier': ADDRESS,
                                                         'line': 'Save the new Kanto Foods login?'})
        return on_event

    def vault_list(self):
        return {'success': True, 'items': [{'handle': 'vault_card', 'label': 'Visa', 'kind': 'payment'}]}

    def fill(self, handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        self.calls.append(('fill', handle, shop, amount, pin_approved))
        return self.fill_results.pop(0) if self.fill_results else {'success': True, 'filled_fields': 2, 'kind': 'login', 'app': shop}

    def code(self, session, driver, *, handle=None, mail_packages=(), task_id='default', pin_approved=False, ask_owner=None,
             since=None):
        self.calls.append(('code', handle, tuple(mail_packages), pin_approved, ask_owner is not None))
        self.since.append(since)
        return {'success': True, 'filled_fields': 1, 'kind': 'otp'}

    def open_link(self, session, opener, *, handle, mail_packages=(), since=None):
        self.calls.append(('open_link', handle, tuple(mail_packages)))
        self.since.append(since)
        return {'success': opener('https://kanto.example.com/magic?t=SECRET'), 'opened': 'link'}

    def new_login(self, session, driver, *, identifier, label, replaces=None, pending=None):
        self.calls.append(('new_login', identifier, label, replaces, pending))
        return {'success': True, 'filled_fields': 2, 'kind': 'login', 'pending': 'held_1'}

    def settle_signup(self, pending, outcome):
        self.calls.append(('settle_signup', pending, outcome))
        return {'success': True, 'saved': True, 'handle': 'vault_new'}

    def login_result(self, handle, outcome):
        self.calls.append(('login_result', handle, outcome))
        return {'success': outcome != 'failure', 'status': 'login_failed' if outcome == 'failure' else 'ok'}

    def save_login(self, session, driver, *, label='', task_id='default'):
        self.calls.append(('save_login', label))
        return {'success': False, 'error_type': 'prompt_unavailable'}


@pytest.fixture
def phone(harness, monkeypatch):
    api, client, cfg, tmp, app = harness
    world = World()
    world.channel = []
    approvals = load('agent_phone_approvals', 'beacon_agent_phone_approvals')
    monkeypatch.setattr(approvals, 'POLL_SECONDS', 0.01)
    approvals.GRANTS.clear()
    approvals._declined.clear()
    redact = fake_redact()
    lease_state = {'holder': 'agent', 'epoch': 3}
    lease_module = types.ModuleType('beacon_agent_phone_lease')
    lease_module.read = lambda folder: dict(lease_state)
    channel = types.ModuleType('beacon_agent_phone_channel')
    def connect(adb, events=None, timeout=None, **o):
        session = FakeSession(world)
        session.on_event = events(session) if events else None
        world.live_session = session
        return session
    channel.connect = connect
    grpc_module = types.ModuleType('beacon_agent_phone_grpc')
    grpc_module.EVDEV, grpc_module.KEYDOWN, grpc_module.KEYUP, grpc_module.KEYPRESS = 1, 0, 1, 2
    vault = FakeVault(world)
    vault_module = types.ModuleType('beacon_agent_phone_vault')
    class PhoneVault:  # L9's constructor shape: the tool passes refresh= only when it is there
        def __init__(self, source, sidecar, masks, hermes, *, notify=None, app_label=None, dal_fetch=None, on_card_approved=None,
                     refresh=None):
            pass

        @staticmethod
        def for_agent(profile, owning_profile, masks, **kw):
            vault.built_with = kw
            return vault
    vault_module.PhoneVault = PhoneVault
    vault_module.submit_outcome = lambda trees: world.outcome
    world.outcome = 'success'
    for key, module in (('beacon_agent_phone_redact', redact), ('beacon_agent_phone_lease', lease_module),
                        ('beacon_agent_phone_channel', channel), ('beacon_agent_phone_grpc', grpc_module),
                        ('beacon_agent_phone_vault', vault_module)):
        monkeypatch.setitem(sys.modules, key, module)
    tools = load('agent_phone_tools', 'beacon_agent_phone_tools')
    monkeypatch.setattr(tools, 'KEY_PACE', 0)
    monkeypatch.setattr(tools, 'SETTLE_SECONDS', 0.3)
    monkeypatch.setattr(tools.time, 'sleep', lambda s: None)
    monkeypatch.setattr(tools, '_authorize', lambda session: ('owner', 'default'))
    started = []

    def running(folder, start=True):
        started.append(start)
        return tools.Phone(FakeHost(world), {}) if world.on else None
    world.on = True
    monkeypatch.setattr(tools, 'running_phone', running)
    pointer = tmp / 'plugin-data' / 'beacon' / 'agent-phone.json'
    pointer.parent.mkdir(parents=True, exist_ok=True)
    pointer.write_text(json.dumps({'folder': str(tmp / 'phone'), 'profile': 'default'}), encoding='utf-8')
    for name in ('_views', '_pending', '_masks', '_vaults', '_labels'):
        getattr(tools, name).clear()
    world.lease, world.vault, world.redact, world.started = lease_state, vault, redact, started
    yield tools, approvals, world, api
    sys.modules.pop('beacon_agent_phone_tools', None)
    sys.modules.pop('beacon_agent_phone_approvals', None)


def run(tools, session='chat', task='task-1', **args):
    return tools.call(args, session_id=session, task_id=task)


def owner_answers(approvals, yes=True, times=1, check=None, code=None):
    """The owner's phone: waits for records and answers them in order, in its own thread."""
    seen = []

    def answer():
        deadline = time.time() + 5
        while time.time() < deadline and len(seen) < times:
            for record in approvals.open_records('owner'):
                if record['id'] not in [r['id'] for r in seen]:
                    seen.append(record)
                    if check:
                        check(record)
                    approvals.decide('owner', record['id'], yes if not isinstance(yes, list) else yes[len(seen) - 1], 'device-1', code=code)
            time.sleep(0.005)
    thread = threading.Thread(target=answer)
    thread.start()
    return thread, seen


def capture(tools):
    result = run(tools, action='capture')
    assert result.get('_multimodal') is True, result
    return json.loads(result['text_summary'])


def element(summary, label):
    for line in summary['untrusted_data']['elements']:
        found = re.match(r'\[(\d+)\] \S+ "(.*)"', line)
        if found and found.group(2) == label:
            return int(found.group(1))
    raise AssertionError(label + ' not in ' + repr(summary['untrusted_data']['elements']))


def taps(world):
    return [i for i in world.inputs if i[0] == 'tap']


# ---- the risk check, on fixture screens ------------------------------------------------------------------------------

def screen_of(tools, xml, package=PKG):
    return tools.Screen(xml, package=package, window=package + '/.Main')


def index_of(screen, label):
    for i, n in enumerate(screen.nodes):
        if label in (n.get('text'), n.get('content-desc'), n.get('hint')):
            return i
    raise AssertionError(label)


def test_pay_button_on_the_shop_checkout_is_a_payment(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, fixture('shop_checkout.xml'))
    assert tools.assess(screen, 'tap', index_of(screen, 'Pay £12.40'))[0] == 'payment'
    assert screen.amount_text(index_of(screen, 'Pay £12.40')) == '£12.40'
    assert screen.amount_text() == '£12.40'


@pytest.mark.parametrize('label', ['ادفع الآن', 'اطلب الآن', 'شراء', 'إتمام الطلب', 'Place order', 'Buy now', 'Subscribe', 'Jetzt kaufen',
                                   'Passer la commande', 'Comprar', '提交订单', 'Satın al'])
def test_payment_labels_in_several_languages(phone, label):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text=label)))
    assert tools.assess(screen, 'tap', index_of(screen, label))[0] == 'payment'


def test_currency_amount_alone_makes_a_payment_button(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text='١٢٫٤٠ ر.س')))
    assert tools.assess(screen, 'tap', index_of(screen, '١٢٫٤٠ ر.س'))[0] == 'payment'


def test_icon_only_cart_button_on_a_payment_screen_asks_like_a_payment(phone):
    tools, approvals, world, api = phone
    xml = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                     node('android.widget.ImageButton', bounds=(900, 2200, 1017, 2300)))
    screen = screen_of(tools, xml)
    icon = [i for i, n in enumerate(screen.nodes) if n.get('class') == 'android.widget.ImageButton'][0]
    assert tools.assess(screen, 'tap', icon)[0] == 'unlabeled'
    # The same icon on a screen with no amount and no checkout words is an ordinary tap.
    plain = screen_of(tools, screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)),
                                        node('android.widget.ImageButton', bounds=(900, 2200, 1017, 2300))))
    assert tools.assess(plain, 'tap', len(plain.nodes) - 1)[0] is None


def test_coordinate_tap_on_a_checkout_asks_like_a_payment(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, fixture('shop_checkout.xml'))
    target = screen.hit(540, 400)  # the name field
    assert tools.assess(screen, 'tap', target, coordinate=True)[0] == 'unlabeled'


def test_slide_to_pay_and_hold_to_order_ask(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text='Slide to pay')))
    assert tools.assess(screen, 'swipe', index_of(screen, 'Slide to pay'), direction='right')[0] == 'payment'
    screen = screen_of(tools, screen_xml(node(text='Hold to order')))
    assert tools.assess(screen, 'long_press', index_of(screen, 'Hold to order'))[0] == 'payment'
    screen = screen_of(tools, screen_xml(node('android.widget.TextView', text='Total £9.00', clickable=False), node(desc='slider', bounds=(63, 2000, 1017, 2100))))
    assert tools.assess(screen, 'swipe', index_of(screen, 'slider'), direction='right')[0] == 'unlabeled'


def test_scrolling_a_checkout_list_does_not_ask(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node('android.widget.TextView', text='Total £9.00', clickable=False),
                                         node('android.widget.ScrollView', desc='items', scrollable=True, bounds=(0, 400, 1080, 2000))))
    assert tools.assess(screen, 'swipe', index_of(screen, 'items'), direction='up')[0] is None


def test_unreadable_screen_fails_closed(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, '<hierarchy><node class="android.view.View" bounds="[0,0][1080,2400]" clickable="true"/></hierarchy>')
    assert tools.assess(screen, 'tap', None, coordinate=True)[0] == 'unlabeled'
    assert tools.assess(tools.Screen('', package=PKG), 'tap', None, coordinate=True)[0] == 'unlabeled'


def test_enter_and_submit_on_a_payment_screen_ask(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, fixture('shop_checkout.xml'))
    field = screen.focused()
    assert tools.assess(screen, 'type', field, submit=True)[0] == 'payment'
    assert tools.assess(screen, 'key', field, key='enter')[0] == 'payment'
    assert tools.assess(screen, 'type', field, submit=False)[0] is None


@pytest.mark.parametrize('label,risk', [('Send', 'send'), ('إرسال', 'send'), ('Delete', 'delete'), ('حذف', 'delete'), ('Install', 'install'),
                                        ('Sign out', 'account'), ('تسجيل الخروج', 'account'), ('Browse the menu', None)])
def test_send_delete_install_account_ask_by_default(phone, label, risk):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text=label)))
    assert tools.assess(screen, 'tap', index_of(screen, label))[0] == risk


def test_every_action_asks_for_everything_and_declared_risk_only_raises(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text='Browse the menu')))
    target = index_of(screen, 'Browse the menu')
    assert tools.assess(screen, 'tap', target, ask_level='every_action')[0] == 'other'
    assert tools.assess(screen, 'tap', target, declared='payment')[0] == 'payment'
    pay = screen_of(tools, screen_xml(node(text='Pay now')))
    assert tools.assess(pay, 'tap', index_of(pay, 'Pay now'), declared='send')[0] == 'payment'  # never lowered


# ---- hard blocks -------------------------------------------------------------------------------------------------------

def test_allow_usb_debugging_is_never_tapped(phone):
    tools, approvals, world, api = phone
    xml = screen_xml(node('android.widget.TextView', text='Allow USB debugging?', clickable=False, package='com.android.systemui'),
                     node(text='Allow', package='com.android.systemui', bounds=(600, 1500, 1000, 1600)),
                     node(text='Cancel', package='com.android.systemui', bounds=(100, 1500, 500, 1600)), package='com.android.systemui')
    screen = screen_of(tools, xml, package='com.android.systemui')
    for label in ('Allow', 'Cancel'):
        assert 'USB debugging' in tools.hard_block(screen, 'tap', index_of(screen, label))
    assert tools.hard_block(screen, 'tap', None) and tools.hard_block(screen, 'key', None, key='enter')
    assert tools.hard_block(screen, 'key', None, key='back') is None  # back closes it (a no)


def test_usb_prompt_tap_is_refused_end_to_end(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.TextView', text='Allow USB debugging?', clickable=False, package='com.android.systemui'),
                           node(text='Always allow from this computer', package='com.android.systemui', bounds=(63, 1300, 1017, 1400)),
                           node(text='Allow', package='com.android.systemui', bounds=(600, 1500, 1000, 1600)), package='com.android.systemui')
    world.package = 'com.android.systemui'
    summary = capture(tools)
    result = run(tools, action='tap', element=element(summary, 'Allow'), purpose='continue')
    assert result['error'] == 'blocked' and taps(world) == []
    assert approvals.open_records('owner') == []
    assert run(tools, action='tap', coordinate=[500, 1030], purpose='continue')['error'] == 'blocked'
    assert run(tools, action='key', key='back', purpose='close it') == {'success': True, 'action': 'key', 'key': 'back'}


@pytest.mark.parametrize('label', ['Factory reset', 'Erase all data (factory reset)', 'Developer options', 'Remove account',
                                   'Install unknown apps', 'Security & privacy', 'USB debugging', 'إعادة ضبط المصنع', 'خيارات المطور'])
def test_reset_accounts_security_and_developer_settings_are_blocked(phone, label):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node(text=label), package='com.android.settings'), package='com.android.settings')
    assert tools.hard_block(screen, 'tap', index_of(screen, label))


def test_a_blocked_settings_screen_blocks_every_input_but_back(phone):
    tools, approvals, world, api = phone
    xml = screen_xml(node('android.widget.TextView', text='Remove account', clickable=False, package='com.android.settings'),
                     node(text='OK', package='com.android.settings'), package='com.android.settings')
    screen = screen_of(tools, xml, package='com.android.settings')
    assert tools.hard_block(screen, 'tap', index_of(screen, 'OK'))
    assert tools.hard_block(screen, 'key', None, key='home') is None
    # An ordinary settings screen (language) is fine.
    plain = screen_of(tools, screen_xml(node(text='Languages'), package='com.android.settings'), package='com.android.settings')
    assert tools.hard_block(plain, 'tap', index_of(plain, 'Languages')) is None


def test_sideloading_and_turning_off_the_helper_are_blocked(phone):
    tools, approvals, world, api = phone
    installer = screen_of(tools, screen_xml(node(text='Install'), package='com.google.android.packageinstaller'),
                          package='com.google.android.packageinstaller')
    assert 'outside the Play Store' in tools.hard_block(installer, 'tap', index_of(installer, 'Install'))
    helper = screen_of(tools, screen_xml(node('android.widget.TextView', text='Beacon', clickable=False, package='com.android.settings'),
                                         node(text='Force stop', package='com.android.settings'), package='com.android.settings', title='App info'),
                       package='com.android.settings')
    assert "helper" in tools.hard_block(helper, 'tap', index_of(helper, 'Force stop'))
    world.on = True
    assert run(tools, action='open_app', package='com.google.android.packageinstaller')['error'] == 'blocked'


def test_the_model_cannot_approve_androids_save_prompt(phone):
    tools, approvals, world, api = phone
    screen = tools.Screen(screen_xml(node('android.widget.TextView', text='Save username and password to Beacon?', clickable=False, package='android'),
                                     node(text='Save', package='android'), node(text='Not now', package='android'), package='android'),
                          package='android', window='android')  # Android's own window, as measured
    assert 'save a login' in tools.hard_block(screen, 'tap', index_of(screen, 'Save'))
    assert tools.hard_block(screen, 'key', None, key='back') is None


@pytest.mark.parametrize('fixture_name,label', [('shop_login.xml', 'Password'), ('shop_checkout.xml', 'Card number'), ('shop_checkout.xml', 'CVC')])
def test_secret_fields_never_take_model_text(phone, fixture_name, label):
    tools, approvals, world, api = phone
    screen = screen_of(tools, fixture(fixture_name))
    assert 'Never type those' in tools.hard_block(screen, 'type', index_of(screen, label))


def test_pin_field_by_its_label(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(node('android.widget.EditText', hint='Enter PIN', focused=True)))
    assert tools.hard_block(screen, 'type', screen.focused())


def test_typing_into_the_password_field_is_refused_end_to_end(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml').replace('focused="true"', 'focused="false"').replace(
        'content-desc="password" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false"',
        'content-desc="password" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true"')
    result = run(tools, action='type', text='hunter2', purpose='sign in')
    assert result['error'] == 'blocked' and not [i for i in world.inputs if i[0] == 'key']


def test_typing_with_no_readable_focus_fails_closed(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node(text='Browse the menu'))
    result = run(tools, action='type', text='pizza', purpose='search')
    assert result['error'] == 'no_field' and world.inputs == []


def test_type_with_an_element_that_is_a_button_is_refused(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    result = run(tools, action='type', element=element(summary, 'Pay £12.40'), text='x', purpose='x')
    assert result['error'] == 'invalid' and taps(world) == []


# ---- the tree, the capture, the masks -------------------------------------------------------------------------------------

def test_tree_is_read_from_stdout_never_sdcard(phone):
    tools, approvals, world, api = phone
    capture(tools)
    run(tools, action='tap', element=1, purpose='name field')
    assert ('exec_out', ['uiautomator', 'dump', '/dev/tty']) in world.calls
    assert not any('sdcard' in json.dumps(call) for call in world.calls)
    assert tools.tree_from_stdout(b"<?xml version='1.0' ?><hierarchy><node/></hierarchy>UI hierchary dumped to: /dev/tty") \
        == "<?xml version='1.0' ?><hierarchy><node/></hierarchy>"
    assert tools.tree_from_stdout(b'ERROR: null root node returned by UiTestAutomationBridge.') == ''


def test_capture_is_masked_numbered_and_untrusted(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', text=ADDRESS, hint='Email', bounds=(63, 344, 1017, 470)),
                           node('android.widget.EditText', text='hunter2-secret', hint='Password', password=True, bounds=(63, 470, 1017, 596)),
                           node(text='Sign in', bounds=(63, 722, 1017, 848)))
    result = run(tools, action='capture')
    assert result['_multimodal'] is True
    assert result['content'][1]['image_url']['url'].startswith('data:image/jpeg;base64,')
    summary = json.loads(result['text_summary'])
    assert 'hunter2-secret' not in result['text_summary'] and summary['notice'].startswith('Everything inside untrusted_data')
    assert any('"Sign in"' in line for line in summary['untrusted_data']['elements'])
    assert world.redact.captures == [PKG]  # every capture goes through the read guard, with the app in front


def test_capture_refused_after_a_fill_on_an_unreadable_screen(phone):
    tools, approvals, world, api = phone
    tools._masks_for('default').filled_packages.add(PKG)
    world.xml = '<hierarchy><node class="android.view.View" package="dev.beacon.fixture.shop" bounds="[0,0][1080,2400]"/></hierarchy>'
    result = run(tools, action='capture')
    assert result['error'] == 'masked' and 'saved login' in result['message']


def test_element_numbers_need_a_capture_of_the_same_app(phone):
    tools, approvals, world, api = phone
    assert run(tools, action='tap', element=1, purpose='x')['error'] == 'invalid'
    capture(tools)
    world.xml = fixture('shop_login.xml')
    assert run(tools, action='tap', element=7, purpose='x')['error'] in ('changed', 'invalid')


# ---- bound approvals end to end ------------------------------------------------------------------------------------------

def test_pay_asks_with_labels_and_taps_once_after_yes(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    thread, seen = owner_answers(approvals, True)
    result = run(tools, action='tap', element=pay, purpose='dinner you asked for')
    thread.join()
    assert result == {'success': True, 'action': 'tap', 'approved_by_owner': True}
    record = seen[0]
    assert (record['kind'], record['risk'], record['action'], record['label'], record['amount'], record['purpose']) == \
        ('agent_phone_action', 'payment', 'tap', 'Pay £12.40', '£12.40', 'dinner you asked for')
    assert taps(world) == [('tap', 540, 1163, 0.05)]  # the Pay button's centre, once
    stored = api.mobile.connect().execute('SELECT bind FROM agent_phone_approvals WHERE id=?', (record['id'],)).fetchone()[0]
    bind = json.loads(stored)
    assert bind['package'] == PKG and bind['bounds'] == [63, 1100, 1017, 1226] and bind['amount'] == '£12.40'
    assert 'Pay' not in stored  # the label is bound by its hash
    assert approvals.activity('owner')[0]['approved'] is True


def test_no_means_nothing_and_stands_sixty_seconds(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='tap', element=pay, purpose='dinner')
    thread.join()
    assert result['error'] == 'denied' and taps(world) == []
    capture(tools)
    again = run(tools, action='tap', element=pay, purpose='dinner')
    assert again['error'] == 'denied' and len(seen) == 1 and approvals.open_records('owner') == []
    assert run(tools, task='task-2', action='capture')['_multimodal']  # another task is not held back by it


def test_unanswered_question_expires_and_nothing_runs(phone, monkeypatch):
    tools, approvals, world, api = phone
    monkeypatch.setattr(approvals, 'WAIT_SECONDS', 0.05)
    summary = capture(tools)
    result = run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')
    assert result['error'] == 'expired' and taps(world) == []


def test_amount_changes_during_the_wait_and_the_tool_asks_again(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    world.after_dumps[world.dumps + 2] = fixture('shop_checkout.xml').replace('£12.40', '£19.99')
    thread, seen = owner_answers(approvals, True, times=2)
    result = run(tools, action='tap', element=pay, purpose='dinner')
    thread.join()
    assert [r['amount'] for r in seen] == ['£12.40', '£19.99']
    assert result['success'] is True and len(taps(world)) == 1


def test_screen_keeps_changing_after_yes_and_nothing_runs(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    world.after_dumps[world.dumps + 2] = fixture('shop_checkout.xml').replace('£12.40', '£19.99')
    world.after_dumps[world.dumps + 3] = fixture('shop_checkout.xml').replace('£12.40', '£25.00')
    thread, seen = owner_answers(approvals, True, times=2)
    result = run(tools, action='tap', element=pay, purpose='dinner')
    thread.join()
    assert result['error'] == 'changed' and taps(world) == []


def test_lease_changing_during_the_wait_voids_the_yes(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)

    def owner_takes_the_phone(record):
        world.lease.update(holder='human', epoch=4)
    thread, _ = owner_answers(approvals, True, check=owner_takes_the_phone)
    result = run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')
    thread.join()
    assert result['error'] == 'busy' and taps(world) == []


def test_busy_while_the_owner_sets_up_the_phone(phone):
    tools, approvals, world, api = phone
    world.lease.update(holder='human')
    for args in ({'action': 'capture'}, {'action': 'tap', 'coordinate': [1, 1], 'purpose': 'x'}, {'action': 'list_apps'}):
        result = run(tools, **args)
        assert result['error'] == 'busy' and result['message'] == tools.BUSY_LINE
    assert world.calls == [] and world.inputs == []
    assert run(tools, action='status') == {'success': True, 'phone': 'owner_setting_up', 'note': None}


def test_a_broken_lease_reads_as_the_owners(phone, monkeypatch):
    tools, approvals, world, api = phone
    monkeypatch.setattr(sys.modules['beacon_agent_phone_lease'], 'read', lambda folder: (_ for _ in ()).throw(OSError('locked')))
    assert run(tools, action='capture')['error'] == 'busy'


def test_ordinary_taps_do_not_ask_and_every_action_does(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml')
    summary = capture(tools)
    assert run(tools, action='tap', element=element(summary, 'Browse the menu'), purpose='look')['approved_by_owner'] is False
    approvals.set_policy('owner', ask='every_action')
    summary = capture(tools)
    thread, seen = owner_answers(approvals, True)
    assert run(tools, action='tap', element=element(summary, 'Browse the menu'), purpose='look')['approved_by_owner'] is True
    thread.join()
    assert seen[0]['risk'] == 'other'


# ---- the bound purchase grant ------------------------------------------------------------------------------------------

def test_vault_card_yes_covers_the_one_order_tap_at_that_amount(phone):
    tools, approvals, world, api = phone
    approvals.on_card_approved(package=PKG, handle='vault_card', amount='£12.40', certs=['ab' * 32], task='task-1', session='chat')
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    result = run(tools, action='tap', element=pay, purpose='dinner')
    assert result == {'success': True, 'action': 'tap', 'approved_by_owner': True} and len(taps(world)) == 1
    assert approvals.open_records('owner') == []
    # A second order asks again.
    capture(tools)
    thread, seen = owner_answers(approvals, False)
    assert run(tools, action='tap', element=pay, purpose='dinner again')['error'] == 'denied'
    thread.join()
    assert len(seen) == 1 and len(taps(world)) == 1


def test_vault_card_grant_does_not_cover_another_amount_or_app(phone):
    tools, approvals, world, api = phone
    approvals.on_card_approved(package=PKG, handle='vault_card', amount='£9.00', task='task-1', session='chat')
    summary = capture(tools)
    thread, seen = owner_answers(approvals, False)
    assert run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')['error'] == 'denied'
    thread.join()
    approvals.GRANTS.clear()
    approvals._declined.clear()
    approvals.on_card_approved(package='dev.other.shop', handle='vault_card', amount='£12.40', task='task-1', session='chat')
    summary = capture(tools)
    thread, seen2 = owner_answers(approvals, False)
    assert run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')['error'] == 'denied'
    thread.join()
    assert len(seen) == 1 and len(seen2) == 1 and taps(world) == []


def test_fill_passes_the_shop_and_amount_read_from_the_screen(phone):
    tools, approvals, world, api = phone
    result = run(tools, action='fill', handle='vault_card')
    assert result['success'] is True
    assert world.vault.calls[0] == ('fill', 'vault_card', PKG, '£12.40', False)


def test_first_pin_is_folded_into_the_actions_card(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.vault.fill_results = [{'success': False, 'error_type': 'pin_approval_needed', 'question': 'Use the Kanto Foods login in Kanto Foods?'}]
    thread, seen = owner_answers(approvals, True)
    result = run(tools, action='fill', handle='vault_login')
    thread.join()
    assert seen[0]['pin'] == 'Use the Kanto Foods login in Kanto Foods?' and seen[0]['action'] == 'fill'
    assert [c[4] for c in world.vault.calls] == [False, True] and result['success'] is True


def test_first_pin_declined_fills_nothing(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.vault.fill_results = [{'success': False, 'error_type': 'pin_approval_needed', 'question': 'Use the X login in Y?'}]
    thread, _ = owner_answers(approvals, False)
    result = run(tools, action='fill', handle='vault_login')
    thread.join()
    assert result['error'] == 'denied' and len(world.vault.calls) == 1


# ---- sign-ups and logins settle after the submit -------------------------------------------------------------------------

def test_new_login_asks_fills_and_commits_after_the_submit(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signup.xml')
    thread, seen = owner_answers(approvals, True)
    result = run(tools, action='new_login', identifier=ADDRESS, label='Kanto Foods')
    thread.join()
    assert seen[0]['kind'] == 'vault_new_login' and seen[0]['line'] == f"Create a login for Kanto Foods with {ADDRESS}? It's saved to your vault."
    assert result['success'] is True and 'pending' not in result
    summary = capture(tools)
    world.after_input = fixture('shop_signed_in.xml')
    tapped = run(tools, action='tap', element=element(summary, 'Create account'), purpose='finish the sign-up')
    assert tapped['signup'] == {'success': True, 'saved': True}
    assert ('settle_signup', 'held_1', 'success') in world.vault.calls


def test_a_login_fill_reports_a_failed_sign_in(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    run(tools, action='fill', handle='vault_login')
    world.outcome = 'failure'
    summary = capture(tools)
    tapped = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert tapped['login'] == 'login_failed' and ('login_result', 'vault_login', 'failure') in world.vault.calls


def test_a_non_submit_tap_does_not_settle_a_sign_up(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    run(tools, action='fill', handle='vault_login')
    summary = capture(tools)
    assert 'login' not in run(tools, action='tap', element=element(summary, 'Show'), purpose='look')
    assert not [c for c in world.vault.calls if c[0] == 'login_result']


# ---- typing ------------------------------------------------------------------------------------------------------------

def test_ascii_is_typed_as_paced_keys_into_the_focused_field(phone, monkeypatch):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml').replace('</node></node></hierarchy>', node('android.widget.EditText', hint='Search', focused=True,
                                                                                       bounds=(63, 1500, 1017, 1626)) + '</node></node></hierarchy>')
    paced = []
    monkeypatch.setattr(tools.time, 'sleep', lambda s: paced.append(s))
    monkeypatch.setattr(tools, 'KEY_PACE', 0.06)
    result = run(tools, action='type', text='Pad Thai #2 (large)!', purpose='search the menu')
    keys = [i[1] for i in world.inputs if i[0] == 'key']
    assert ''.join(keys) == 'Pad Thai #2 (large)!' and result['via'] == 'keys'
    assert paced.count(0.06) == len('Pad Thai #2 (large)!')
    assert world.channel == []


def test_other_text_goes_whole_through_the_helper_and_the_clipboard_is_cleared(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml').replace('</node></node></hierarchy>', node('android.widget.EditText', hint='Search', focused=True,
                                                                                       bounds=(63, 1500, 1017, 1626)) + '</node></node></hierarchy>')
    result = run(tools, action='type', text='بيتزا café 🍕', purpose='search the menu')
    assert result['via'] == 'helper'
    assert [t for t, _ in world.channel] == ['clip_set', 'clip_clear']
    assert world.channel[0][1]['text'] == 'بيتزا café 🍕'
    keys = [i for i in world.inputs if i[0] == 'key']
    assert [(k[2], k[3]) for k in keys] == [(29, 0), (47, 2), (29, 1)]  # Ctrl+V as evdev keys, no per-character keys


def test_type_with_submit_presses_enter(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml').replace('</node></node></hierarchy>', node('android.widget.EditText', hint='Search', focused=True,
                                                                                       bounds=(63, 1500, 1017, 1626)) + '</node></node></hierarchy>')
    result = run(tools, action='type', text='noodles', submit=True, purpose='search')
    assert result['submitted'] is True and [i[1] for i in world.inputs if i[0] == 'key'][-1] == 'Enter'


# ---- keys, swipes, apps, links -------------------------------------------------------------------------------------------

def test_keys_map_to_the_emulators_names(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml')
    for name in ('back', 'home', 'recents', 'tab', 'delete'):
        assert run(tools, action='key', key=name, purpose='move')['success'] is True
    assert [i[1] for i in world.inputs if i[0] == 'key'] == ['GoBack', 'GoHome', 'AppSwitch', 'Tab', 'Backspace']


def test_swipe_moves_the_finger_the_given_way(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml')
    assert run(tools, action='swipe', direction='up', purpose='see more')['success'] is True
    moves = [i[1][0] for i in world.inputs if i[0] == 'touch']
    assert moves[0][1] > moves[-1][1] and moves[-1][3] == 0  # bottom to top, then lifted


def test_open_app_through_its_launcher_entry(phone):
    tools, approvals, world, api = phone
    world.shell_answers.append((r'resolve-activity', 'priority=0 preferredOrder=0\ndev.beacon.fixture.shop/.LoginActivity\n'))
    assert run(tools, action='open_app', package=PKG) == {'success': True, 'opened': PKG}
    assert ('shell', ['am', 'start', '-n', 'dev.beacon.fixture.shop/.LoginActivity']) in world.calls
    world.shell_answers[:] = [(r'resolve-activity', 'No activity found\n')]
    assert run(tools, action='open_app', package='com.example.none')['error'] == 'not_found'


def test_list_apps_hides_the_helper(phone):
    tools, approvals, world, api = phone
    world.shell_answers.append((r'query-activities --brief -a android.intent.action.MAIN',
                                'com.android.chrome/com.google.android.apps.chrome.Main\ndev.beacon.fixture.shop/.LoginActivity\n'
                                'dev.hermes.agentphone.helper/.Main\n'))
    assert run(tools, action='list_apps') == {'success': True, 'apps': ['com.android.chrome', PKG]}


def test_open_link_is_https_one_argument_and_never_logged(phone, caplog):
    tools, approvals, world, api = phone
    caplog.set_level(logging.DEBUG)
    url = 'https://kanto.example.com/menu?promo=SECRET-TOKEN'
    world.shell_answers.append((r'^am start -a android.intent.action.VIEW', 'Starting: Intent { act=android.intent.action.VIEW }'))
    assert run(tools, action='open_link', url=url) == {'success': True, 'opened': 'link'}
    assert ('shell', ['am', 'start', '-a', 'android.intent.action.VIEW', '-d', url]) in world.calls
    assert 'SECRET-TOKEN' not in caplog.text
    for bad in ('http://kanto.example.com/', 'javascript:alert(1)', 'intent://x#Intent;end', 'https://kanto.example.com/a b',
                'https://user:pw' + '@' + 'kanto.example.com/', 'https://localhost/', 'file:///sdcard/x'):
        assert run(tools, action='open_link', url=bad)['error'] == 'invalid', bad


def test_open_link_reads_ams_error_line(phone):
    tools, approvals, world, api = phone
    world.shell_answers.append((r'^am start', ('Error: Activity not started, unable to resolve Intent', 0)))
    assert run(tools, action='open_link', url='https://kanto.example.com/')['error'] == 'invalid'


def test_open_link_by_handle_is_the_vaults_magic_link_with_mail_packages(phone):
    tools, approvals, world, api = phone
    world.shell_answers += [(r'^am start', 'Starting: Intent'), *MAIL_ANSWERS]
    result = run(tools, action='open_link', handle='vault_login')
    assert result == {'success': True, 'opened': 'link'}
    assert world.vault.calls[0] == ('open_link', 'vault_login', ('com.google.android.gm', 'com.fastmail.app'))
    assert 'SECRET' not in json.dumps(result)


MAIL_ANSWERS = [
    (r'SENDTO -d mailto:', 'com.google.android.gm/.ComposeActivityGmail\ncom.fastmail.app/.Compose\ncom.sideload.mail/.Compose\n'),
    (r'VIEW -d mailto:', 'com.google.android.gm/.ComposeActivityGmail\n'),
    (r'pm list packages -s', 'package:com.android.chrome\npackage:com.google.android.gm\n'),
    (r'pm list packages -i', 'package:com.google.android.gm  installer=com.android.vending\npackage:com.fastmail.app  installer=com.android.vending\n'
                             'package:com.sideload.mail  installer=null\n'),
]


def test_mail_packages_are_system_or_play_installed_mailto_handlers(phone):
    tools, approvals, world, api = phone
    world.shell_answers += MAIL_ANSWERS
    phone_handle = tools.Phone(FakeHost(world), {})
    assert tools.mail_packages(phone_handle) == ['com.google.android.gm', 'com.fastmail.app']
    world.shell_answers[:] = [(r'mailto:', ''), (r'pm list', '')]
    assert tools.mail_packages(phone_handle) == []


def test_code_passes_mail_packages_and_the_owner_code_seam(phone):
    tools, approvals, world, api = phone
    world.shell_answers += MAIL_ANSWERS
    run(tools, action='code', handle='vault_login')
    assert world.vault.calls[0] == ('code', 'vault_login', ('com.google.android.gm', 'com.fastmail.app'), False, True)


# ---- who may use it ----------------------------------------------------------------------------------------------------

def test_not_set_up_other_profile_and_turned_off(phone, harness):
    tools, approvals, world, api = phone
    tmp = harness[3]
    pointer = tmp / 'plugin-data' / 'beacon' / 'agent-phone.json'
    pointer.write_text(json.dumps({'folder': str(tmp / 'phone'), 'profile': 'work'}), encoding='utf-8')
    assert run(tools, action='capture')['message'] == tools.PROFILE_LINE
    pointer.unlink()
    assert run(tools, action='capture') == {'error': 'off', 'message': tools.SET_UP_LINE}
    pointer.write_text(json.dumps({'folder': str(tmp / 'phone'), 'profile': 'default'}), encoding='utf-8')
    approvals.set_policy('owner', enabled=False)
    assert run(tools, action='capture') == {'error': 'off', 'message': tools.OFF_LINE}
    assert world.calls == []


def test_a_chat_that_is_not_the_owners_is_refused(phone, monkeypatch):
    tools, approvals, world, api = phone
    monkeypatch.setattr(tools, '_authorize', lambda session: (_ for _ in ()).throw(tools.Refusal('unavailable', 'not linked')))
    assert run(tools, action='capture') == {'error': 'unavailable', 'message': 'not linked'}


def test_status_does_not_start_the_phone(phone):
    tools, approvals, world, api = phone
    world.on = False
    assert run(tools, action='status')['phone'] == 'off'
    assert world.started == [False]


@pytest.mark.parametrize('args,message', [
    ({'action': 'tap', 'element': 1}, 'purpose'), ({'action': 'tap', 'purpose': 'x'}, 'element or coordinate'),
    ({'action': 'tap', 'purpose': 'x', 'element': 1, 'coordinate': [1, 2]}, 'element or coordinate'),
    ({'action': 'open_app', 'package': '../x'}, 'package'), ({'action': 'open_link'}, 'url or handle'),
    ({'action': 'type', 'text': 'a\nb', 'purpose': 'x'}, 'control'), ({'action': 'nuke'}, 'action'),
    ({'action': 'capture', 'extra': 1}, 'unknown'), ({'action': 'new_login', 'label': 'x'}, 'identifier'),
    ({'action': 'wait', 'seconds': 60}, 'from 0 to 10')])
def test_arguments_are_checked(phone, args, message):
    tools, approvals, world, api = phone
    result = tools.call(args, session_id='chat', task_id='t')
    assert result['error'] == 'invalid' and message in result['message']


def test_errors_never_carry_an_exceptions_text(phone, monkeypatch):
    tools, approvals, world, api = phone

    def boom(*a, **k):
        raise RuntimeError('https://kanto.example.com/?token=SECRET')
    monkeypatch.setattr(tools.Call, 'do_capture', boom)
    result = run(tools, action='capture')
    assert result['error'] == 'unavailable' and 'SECRET' not in json.dumps(result)


# ---- registration ------------------------------------------------------------------------------------------------------

def test_register_adds_only_the_agent_phone_tool(phone):
    tools, approvals, world, api = phone
    seen = []
    tools.register(types.SimpleNamespace(register_tool=lambda **kw: seen.append(('tool', kw['name'], kw['toolset'])),
                                         register_hook=lambda *a: seen.append(('hook',) + a)))
    assert seen == [('tool', 'agent_phone', 'beacon-agent-phone')]


def test_registration_stays_cheap():
    code = ('import importlib.util,sys;spec=importlib.util.spec_from_file_location("t",sys.argv[1]);m=importlib.util.module_from_spec(spec);'
            'spec.loader.exec_module(m);m.register(type("C",(),{"register_tool":lambda self,**k:None})());'
            'print(sorted(n for n in ("PIL","cryptography","h2","beacon_agent_phone_vault","beacon_agent_phone_redact") if n in sys.modules))')
    done = subprocess.run([sys.executable, '-c', code, str(ROOT / 'agent_phone_tools.py')], capture_output=True, text=True, timeout=60)
    assert done.returncode == 0 and done.stdout.strip() == '[]', done.stderr


def test_available_reads_the_pointer_and_the_policy(phone):
    tools, approvals, world, api = phone
    tools._AVAILABLE['at'] = -10.0
    assert tools.available() is True
    approvals.set_policy('owner', enabled=False)
    tools._AVAILABLE['at'] = -10.0
    assert tools.available() is False


def test_proactive_runs_cannot_use_the_agent_phone(harness):
    plugin = load_private('__init__')
    assert 'agent_phone' in plugin._BLOCKED_PROACTIVE_TOOLS


def test_labels_are_blank_when_the_read_guard_refuses_the_screen(phone):
    tools, approvals, world, api = phone
    masks = tools._masks_for('default')
    masks.filled_packages.add(PKG)
    xml = '<hierarchy><node class="android.widget.Button" package="dev.beacon.fixture.shop" text="hunter2-secret" clickable="true" bounds="[0,0][500,200]"/></hierarchy>'
    screen = tools.Screen(xml, package=PKG, masks=masks, redact=world.redact)
    assert screen.readable and _label_of(screen) == 'hunter2-secret'
    assert screen.masked_label(0) == ''


def _label_of(screen):
    return screen.nodes[0].get('text')


# Texts of the real "Security & privacy" screen on the agent phone (android-36 Play image, L3 live run, 17 Nov).
SECURITY_HUB = ['Security & privacy', 'Navigate up', 'Device may be at risk', 'Device unlock', 'Dismiss', 'Set a screen lock',
                'Set screen lock', 'More alerts', 'Security', 'App security', 'Play Protect scanning is on', 'Account security']


def test_androids_security_hub_in_the_permission_controller_is_blocked(phone):
    tools, approvals, world, api = phone
    pc = 'com.google.android.permissioncontroller'
    nodes = [node(text=t, package=pc, bounds=(63, 300 + 120 * i, 1017, 400 + 120 * i)) for i, t in enumerate(SECURITY_HUB)]
    hub = screen_of(tools, screen_xml(*nodes, package=pc, title='Security & privacy'), package=pc)
    assert tools.hard_block(hub, 'tap', index_of(hub, 'Set screen lock'))
    for label in ('Dismiss', 'More alerts', 'Navigate up'):
        assert 'Security settings' in tools.hard_block(hub, 'tap', index_of(hub, label))
    assert tools.hard_block(hub, 'key', None, key='back') is None
    # The same package's ordinary permission prompt is an ordinary screen.
    prompt = screen_of(tools, screen_xml(node(text='Allow', package=pc), node(text="Don't allow", package=pc, bounds=(63, 1100, 1017, 1200)),
                                         package=pc, title='Allow Kanto Foods to send you notifications?'), package=pc)
    assert tools.hard_block(prompt, 'tap', index_of(prompt, 'Allow')) is None


def test_settings_home_blocks_only_the_security_row(phone):
    tools, approvals, world, api = phone
    st = 'com.android.settings'
    home = screen_of(tools, screen_xml(node(text='Network & internet', package=st), node(text='Security & privacy', package=st, bounds=(63, 1100, 1017, 1200)),
                                       package=st, title='Settings'), package=st)
    assert tools.hard_block(home, 'tap', index_of(home, 'Network & internet')) is None
    assert tools.hard_block(home, 'tap', index_of(home, 'Security & privacy'))


def test_a_phone_that_is_off_starts_only_behind_the_start_gate(phone, monkeypatch):
    tools, approvals, world, api = phone
    started = []

    class Host:
        def __init__(self, folder):
            self.folder = folder

        def state(self):
            return {}

        def running(self, state=None):
            return False

        def start(self):
            started.append(True)
            return {'running': True}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_host', types.SimpleNamespace(PhoneHost=Host))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', types.SimpleNamespace(phone_host=Host))
    real = load('agent_phone_tools', 'beacon_agent_phone_tools_start')
    present = {'agent_phone_host', 'agent_phone_install'}

    def sibling(name, required=True):
        if name in present:
            return sys.modules['beacon_' + name]
        if required:
            raise real.Refusal('unavailable', real.UPDATE_LINE)
        return None
    monkeypatch.setattr(real, '_sibling', sibling)
    with pytest.raises(real.Refusal) as missing:  # L1a's checks missing: never start without the sign-in check
        real.running_phone(Path('phone'))
    assert missing.value.message == real.UPDATE_LINE and started == []
    present.add('agent_phone_checks')
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_checks', types.SimpleNamespace(start_gate=lambda: (False, 'Turn on Hermes sign-in first.')))
    with pytest.raises(real.Refusal) as refused:
        real.running_phone(Path('phone'))
    assert refused.value.message == 'Turn on Hermes sign-in first.' and started == []
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_checks', types.SimpleNamespace(start_gate=lambda: (True, None)))
    assert isinstance(real.running_phone(Path('phone')), real.Phone) and started == [True]
    assert real.running_phone(Path('phone'), start=False) is None and started == [True]



# ---- review 1: rows whose text is on children, the grant's one amount, windows that are not an activity -------------------

def row(text, summary='', package=PKG, bounds=(0, 900, 1080, 1060), cls='android.widget.LinearLayout'):
    """An ordinary Android row: a clickable container whose text sits on non-clickable children."""
    from xml.sax.saxutils import quoteattr
    inner = node('android.widget.TextView', text=text, clickable=False, package=package, bounds=(bounds[0] + 40, bounds[1] + 10, bounds[2] - 40, bounds[1] + 70))
    if summary:
        inner += node('android.widget.TextView', text=summary, clickable=False, package=package, bounds=(bounds[0] + 40, bounds[1] + 80, bounds[2] - 40, bounds[3] - 10))
    attrs = {'text': '', 'class': cls, 'package': package, 'content-desc': '', 'clickable': 'true', 'enabled': 'true', 'focusable': 'true',
             'focused': 'false', 'scrollable': 'false', 'password': 'false', 'bounds': '[%d,%d][%d,%d]' % bounds, 'hint': ''}
    return '<node ' + ' '.join(f'{k}={quoteattr(v)}' for k, v in attrs.items()) + '>' + inner + '</node>'


def test_a_settings_row_with_its_text_on_children_is_blocked(phone):
    tools, approvals, world, api = phone
    st = 'com.android.settings'
    xml = screen_xml(row('Network & internet', 'Mobile, Wi-Fi, hotspot', package=st, bounds=(0, 400, 1080, 560)),
                     row('Security & privacy', 'App security, device unlock', package=st, bounds=(0, 900, 1080, 1060)), package=st, title='Settings')
    world.xml, world.package = xml, st
    summary = capture(tools)
    assert element(summary, 'Security & privacy') and element(summary, 'Network & internet')
    result = run(tools, action='tap', element=element(summary, 'Security & privacy'), purpose='look around')
    assert result['error'] == 'blocked' and taps(world) == []
    assert run(tools, action='tap', element=element(summary, 'Network & internet'), purpose='check Wi-Fi')['success'] is True


def test_a_place_order_row_with_its_text_on_a_child_is_a_payment(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, screen_xml(row('Place order', bounds=(0, 2100, 1080, 2260)), row('Edit address', bounds=(0, 600, 1080, 760))))
    order = [i for i, _ in screen.elements() if screen.masked_label(i) == 'Place order'][0]
    assert tools.assess(screen, 'tap', order)[0] == 'payment'
    edit = [i for i, _ in screen.elements() if screen.masked_label(i) == 'Edit address'][0]
    assert tools.assess(screen, 'tap', edit)[0] is None


def test_the_grant_does_not_match_a_line_item_when_the_total_differs(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.TextView', text='Pad Thai £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node('android.widget.TextView', text='Spring rolls £12.40', clickable=False, bounds=(63, 600, 1017, 700)),
                           node('android.widget.TextView', text='Total £24.80', clickable=False, bounds=(63, 700, 1017, 800)),
                           node(text='Place order', bounds=(63, 2100, 1017, 2226)))
    approvals.on_card_approved(package=PKG, handle='vault_card', amount='£12.40', task='task-1', session='chat')
    summary = capture(tools)
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='tap', element=element(summary, 'Place order'), purpose='dinner')
    thread.join()
    assert result['error'] == 'denied' and len(seen) == 1 and seen[0]['amount'] == '£24.80' and taps(world) == []
    approvals.GRANTS.clear()
    approvals._declined.clear()
    approvals.on_card_approved(package=PKG, handle='vault_card', amount='£24.80', task='task-1', session='chat')
    summary = capture(tools)
    assert run(tools, action='tap', element=element(summary, 'Place order'), purpose='dinner')['approved_by_owner'] is True
    assert len(taps(world)) == 1


@pytest.mark.parametrize('label', ['Continue', 'Confirm', 'OK', 'متابعة', 'تأكيد', 'Weiter'])
def test_commit_buttons_on_a_payment_screen_ask(phone, label):
    tools, approvals, world, api = phone
    screen = screen_of(tools, fixture('shop_checkout.xml').replace('text="Pay £12.40"', f'text="{label}"'))
    assert tools.assess(screen, 'tap', index_of(screen, label))[0] == 'payment'
    plain = screen_of(tools, screen_xml(node(text=label)))
    assert tools.assess(plain, 'tap', index_of(plain, label))[0] is None


def test_element_numbers_work_in_a_window_that_is_no_activity(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node(text='Share'), node(text='Copy link', bounds=(63, 1100, 1017, 1226)), title='Options')
    world.shell_answers.append((r'^dumpsys window', '  mCurrentFocus=Window{9c1 u0 PopupWindow:4e2f1a}\n'))
    summary = capture(tools)
    assert summary['package'] == PKG
    assert run(tools, action='tap', element=element(summary, 'Copy link'), purpose='copy the link')['success'] is True
    assert world.redact.captures[-1] == PKG
    assert tools.parse_window('  mCurrentFocus=Window{9c1 u0 PopupWindow:4e2f1a}') == ('PopupWindow:4e2f1a', None)
    assert tools.parse_window('mCurrentFocus=Window{4f2a u0 dev.pkg/dev.pkg.Main}') == ('dev.pkg/dev.pkg.Main', 'dev.pkg')
    assert tools.parse_window('mCurrentFocus=null') == (None, None)


# ---- L9's gates (msg_c2716f926b97): the autofill refresh and the driver's steps -------------------------------------------

def test_the_vaults_refresh_runs_cmd_autofill_reset_through_the_calls_adb(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.vault.fill_results = []
    original = world.vault.fill

    def fill(*args, **kwargs):
        world.vault.built_with['refresh']()  # L9 refreshes after every arm
        return original(*args, **kwargs)
    world.vault.fill = fill
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    assert ('shell', ['cmd', 'autofill', 'reset']) in world.calls
    assert tools._phones == {}  # the call's phone is not kept after the call
    world.vault.built_with['refresh']()  # a refresh with no running call does nothing
    assert world.calls.count(('shell', ['cmd', 'autofill', 'reset'])) == 1


ENTRY = 'Beacon · Kanto Foods · ' + ADDRESS


def login_with_entry():
    entry = node('android.widget.TextView', text=ENTRY, clickable=True, package='android', bounds=(63, 600, 1017, 700))
    return fixture('shop_login.xml').replace('</node></node></hierarchy>', entry + '</node></node></hierarchy>')


def test_pick_entry_tabs_off_taps_the_field_then_the_entry(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.after_input = login_with_entry()  # the field's tap makes Android ask the helper again
    driver = tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470))
    assert driver.pick_entry({}, 'Kanto Foods') is True
    keys = [i[1] for i in world.inputs if i[0] == 'key']
    assert keys == ['Tab'] and taps(world) == [('tap', 540, 407, 0.05), ('tap', 123, 650, 0.05)]


def typed_login(focused_role):
    email = node('android.widget.EditText', text='agent.join@example.com', desc='email', bounds=(63, 344, 1017, 470), focused=True)
    return screen_xml(email), {'fields': [{'id': '1073741824', 'role': focused_role, 'focused': True}]}


def test_pick_entry_never_empties_a_field_the_item_does_not_fill(phone):
    tools, approvals, world, api = phone
    world.xml, context = typed_login('new_password')  # focused, typed, but not one of the item's roles
    driver = tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470))
    assert driver.pick_entry(context, 'Kanto Foods', ('username', 'password', 'otp')) is False
    assert not [i for i in world.inputs if i[0] == 'key' and (i[2] == 30 or i[1] == 'Backspace')]


def test_pick_entry_leaves_an_empty_field_and_a_call_without_roles_alone(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.after_input = login_with_entry()
    driver = tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470))
    assert driver.pick_entry({'fields': [{'id': '1', 'role': 'username', 'focused': True}]}, 'Kanto Foods', ('username',)) is True
    assert [i[1] for i in world.inputs if i[0] == 'key'] == ['Tab']  # the shop's email field is empty: nothing to delete
    world.inputs.clear()
    world.xml, context = typed_login('username')
    assert tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470)).pick_entry(context, 'Kanto Foods') is False
    assert not [i for i in world.inputs if i[0] == 'key' and i[1] != 'Tab']  # no roles given: nothing is emptied


def test_pick_entry_gives_up_without_an_entry_or_a_field(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    driver = tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470))
    assert driver.pick_entry({}, 'Kanto Foods') is False
    assert [i[1] for i in world.inputs if i[0] == 'key'] == ['Tab'] * 3 and len(taps(world)) == 3  # never a blind tap below
    assert tools.Driver(tools.Phone(FakeHost(world), {}), None).pick_entry({}, 'x') is False


# ---- review 2: the control an input lands on, Enter's send rule, keyboard taps on a secret field -------------------------

def test_a_container_element_is_checked_on_the_control_at_its_centre(phone):
    tools, approvals, world, api = phone
    pay = node(text='Pay £12.40', bounds=(390, 1150, 690, 1250))  # sits at the centre of the list below
    world.xml = screen_xml(f'<node class="androidx.recyclerview.widget.RecyclerView" package="{PKG}" text="" content-desc="" '
                           'resource-id="dev.beacon.fixture.shop:id/cart_list" clickable="false" enabled="true" focusable="true" '
                           'focused="false" scrollable="true" password="false" bounds="[0,400][1080,2000]" hint="">' + pay + '</node>')
    summary = capture(tools)
    container = int(next(line for line in summary['untrusted_data']['elements'] if 'RecyclerView' in line)[1:].split(']')[0])
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='tap', element=container, purpose='look at the cart')
    thread.join()
    assert result['error'] == 'denied' and taps(world) == []
    assert seen[0]['risk'] == 'payment' and seen[0]['label'] == 'Pay £12.40'  # the record names what the tap would hit


def test_a_container_over_a_blocked_settings_row_is_blocked(phone):
    tools, approvals, world, api = phone
    st = 'com.android.settings'
    row = node(text='Security & privacy', package=st, bounds=(0, 1150, 1080, 1250))
    world.xml = screen_xml(f'<node class="android.widget.ScrollView" package="{st}" text="" content-desc="content parent" clickable="false" '
                           'enabled="true" focusable="false" focused="false" scrollable="true" password="false" bounds="[0,400][1080,2000]" '
                           'hint="">' + row + '</node>', package=st, title='Settings')
    world.package = st
    summary = capture(tools)
    assert run(tools, action='tap', element=element(summary, 'content parent'), purpose='scroll')['error'] == 'blocked'
    assert taps(world) == []


def test_enter_after_typing_a_message_asks_like_send(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', hint='Message', focused=True, bounds=(63, 2100, 900, 2226)),
                           node(desc='Send', bounds=(900, 2100, 1017, 2226)), title='Chat')
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='key', key='enter', purpose='send the reply')
    thread.join()
    assert result['error'] == 'denied' and seen[0]['risk'] == 'send'
    assert [i for i in world.inputs if i[0] == 'key'] == []


def test_no_key_taps_into_a_secret_field(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml').replace('content-desc="email" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true"',
                                                  'content-desc="email" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false"').replace(
        'content-desc="password" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false"',
        'content-desc="password" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="true"')
    summary = capture(tools)
    assert 'element number' in run(tools, action='tap', coordinate=[360, 1500], purpose='press a key')['message']
    world.keyboard = [(0, 700, 1080, 2400)]  # a full keyboard covering the Sign in button
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert result['error'] == 'blocked' and 'keyboard covers' in result['message'] and taps(world) == []
    assert 'back' not in result['message'].lower()  # one Back leaves the app on the phone (measured): never advised
    world.keyboard = [(21, 1135, 169, 1281), (0, 2337, 1080, 2400)]  # the toolbar bubble the phone shows (measured)
    assert run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')['success'] is True


def test_keyboard_region_is_read_from_the_input_method_window(phone):
    tools, approvals, world, api = phone
    measured = ('  Window #7 Window{16dee7b u0 InputMethod}:\n    mIsImWindow=true mIsWallpaper=false mIsFloatingLayer=true\n'
                '    touchable region=SkRegion((21,1135,169,1281)(0,2337,1080,2400))\n    isOnScreen=true\n    isVisible=true\n'
                '  Window #8 Window{1 u0 NavigationBar0}:\n    touchable region=SkRegion((0,2337,1080,2400))\n')
    assert tools.keyboard_region(measured) == [(21, 1135, 169, 1281), (0, 2337, 1080, 2400)]
    hidden = measured.replace('isOnScreen=true', 'isOnScreen=false').replace('isVisible=true', 'isVisible=false')
    assert tools.keyboard_region(hidden) == []
    assert tools.keyboard_region('  Window #3 Window{1 u0 StatusBar}:\n') == []
    unreadable = measured.replace('touchable region=SkRegion((21,1135,169,1281)(0,2337,1080,2400))', 'touchable region=?')
    assert tools.keyboard_region(unreadable) == [(0, 0, 100000, 100000)]  # visible but unreadable: the whole screen


# ---- follow-up 1: Android's save prompt after the agent's own sign-in (§6.4) -------------------------------------------------

SAVE_PROMPT_XML = screen_xml(
    node('android.widget.TextView', text='Save username and password to Beacon?', clickable=False, package='android', bounds=(63, 1700, 1017, 1800)),
    node(text='Not now', package='android', bounds=(63, 2000, 500, 2100)),
    node(text='Save', package='android', bounds=(560, 2000, 1017, 2100)), package='android', title='Beacon')


def login_typed():
    """The shop's sign-in with an identifier and a password the field shows as bullets."""
    return screen_xml(node('android.widget.EditText', text=ADDRESS, hint='Email', bounds=(63, 344, 1017, 470)),
                      node('android.widget.EditText', text='••••••••', hint='Password', password=True, bounds=(63, 470, 1017, 596)),
                      node(text='Sign in', bounds=(63, 722, 1017, 848)), title='Kanto Foods · Sign in')


def save_button_hook(world):
    def hook(x, y):
        if 560 <= x <= 1017 and 2000 <= y <= 2100:
            world.calls.append(('save_tapped', world.live_session is not None))
            if world.live_session is not None and world.live_session.on_event:
                world.live_session.on_event({'t': 'save', 'save_id': 's1'})
            world.xml = fixture('shop_signed_in.xml')
    world.tap_hooks.append(hook)


def test_the_plugin_taps_save_after_the_agents_own_sign_in(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    save_button_hook(world)
    summary = capture(tools)
    world.after_input = SAVE_PROMPT_XML  # Android asks right after the agent's submit
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert result['success'] is True and result['login_save'] == 'asked_owner'
    assert ('save_tapped', True) in world.calls  # tapped by the plugin, with the channel open so the helper can deliver it
    assert world.live_session is None  # and closed after
    records = [r for r in approvals.open_records('owner') if r['kind'] == 'vault_save']
    assert records and records[0]['line'] == 'Save the new Kanto Foods login?'


def test_no_save_tap_for_a_form_the_agent_did_not_submit(phone):
    tools, approvals, world, api = phone
    world.xml = SAVE_PROMPT_XML
    save_button_hook(world)
    summary = capture(tools)
    assert ('save_tapped', True) not in world.calls and ('save_tapped', False) not in world.calls
    assert run(tools, action='tap', element=element(summary, 'Save'), purpose='save it')['error'] == 'blocked'
    assert taps(world) == [] and approvals.open_records('owner') == []


def test_no_save_tap_after_a_submit_on_a_form_without_a_password(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', text='pad thai', hint='Search', focused=True, bounds=(63, 344, 1017, 470)),
                           node(text='Search', bounds=(63, 600, 1017, 700)))
    save_button_hook(world)
    summary = capture(tools)
    world.after_input = SAVE_PROMPT_XML
    result = run(tools, action='tap', element=element(summary, 'Search'), purpose='search')
    assert 'login_save' not in result and not [c for c in world.calls if c[0] == 'save_tapped']


def test_a_late_save_prompt_after_the_agents_sign_in_is_saved_at_the_next_action(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    save_button_hook(world)
    summary = capture(tools)
    run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    world.xml = SAVE_PROMPT_XML  # it showed only after the tool's short watch
    result = run(tools, action='capture')
    assert ('save_tapped', True) in world.calls
    assert [r['kind'] for r in approvals.open_records('owner')] == ['vault_save']
    assert result.get('_multimodal') or result.get('login_save') == 'asked_owner'


def test_a_save_the_helper_does_not_deliver_is_reported(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    world.tap_hooks.append(lambda x, y: world.calls.append(('save_tapped', world.live_session is not None))
                           if 560 <= x <= 1017 and 2000 <= y <= 2100 else None)
    summary = capture(tools)
    world.after_input = SAVE_PROMPT_XML
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert result['login_save'] == 'not_saved' and approvals.open_records('owner') == []


# ---- follow-up 1: Android's USB and wireless debugging prompts are answered No ------------------------------------------------

USB = 'com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity'


def test_a_debugging_prompt_is_answered_no_before_any_action(phone):
    tools, approvals, world, api = phone
    world.focus_override = USB
    result = run(tools, action='capture')
    assert world.backs == 1 and result['_multimodal'] is True
    assert json.loads(result['text_summary'])['debugging_prompt'] == 'answered no'
    assert approvals.activity('owner')[0]['outcome'] == 'said_no' and 'debugging' in approvals.activity('owner')[0]['label']


def test_a_debugging_prompt_raised_by_a_tap_is_answered_no(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml')
    summary = capture(tools)
    world.tap_hooks.append(lambda x, y: setattr(world, 'focus_override', 'com.android.systemui/.wifi.WifiDebuggingActivity'))
    result = run(tools, action='tap', element=element(summary, 'Browse the menu'), purpose='look')
    assert result['success'] is True and result['debugging_prompt'] == 'answered no' and world.backs == 1


def test_a_debugging_prompt_that_stays_stops_the_action(phone):
    tools, approvals, world, api = phone
    world.focus_override, world.prompt_sticky = USB, True
    result = run(tools, action='tap', coordinate=[100, 100], purpose='x')
    assert result['error'] == 'blocked' and taps(world) == [] and world.backs >= 1


def test_vault_list_and_status_need_no_phone_check(phone):
    tools, approvals, world, api = phone
    world.focus_override = USB
    run(tools, action='status')
    assert world.backs == 0


# ---- follow-up 1: the push after an approved payment or send (§5.5) ---------------------------------------------------------

def done_notices(api):
    with api.mobile.connect() as conn:
        return [dict(r) for r in conn.execute("SELECT kind,reason,activity_key,state FROM notices WHERE kind='agent_phone_done'")]


def test_an_approved_payment_raises_a_done_notice_naming_its_activity_row(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    thread, _ = owner_answers(approvals, True)
    assert run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')['success'] is True
    thread.join()
    notices = done_notices(api)
    row = approvals.activity('owner')[0]
    assert notices == [{'kind': 'agent_phone_done', 'reason': 'payment', 'activity_key': f"agent_phone_activity:{row['seq']}", 'state': 'pending'}]
    assert row['label'] == 'Pay £12.40' and row['approved'] is True


def test_no_done_notice_for_a_decline_or_an_ordinary_tap(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)
    thread, _ = owner_answers(approvals, False)
    run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')
    thread.join()
    world.xml = fixture('shop_signed_in.xml')
    summary = capture(tools)
    run(tools, task='task-2', action='tap', element=element(summary, 'Browse the menu'), purpose='look')
    assert done_notices(api) == []


def test_an_approved_send_raises_a_done_notice(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node(text='Send', bounds=(63, 2100, 1017, 2226)), title='Chat')
    summary = capture(tools)
    thread, _ = owner_answers(approvals, True)
    run(tools, action='tap', element=element(summary, 'Send'), purpose='reply')
    thread.join()
    assert [n['reason'] for n in done_notices(api)] == ['send']


def test_the_vault_card_order_raises_a_done_notice(phone):
    tools, approvals, world, api = phone
    approvals.on_card_approved(package=PKG, handle='vault_card', amount='£12.40', task='task-1', session='chat')
    summary = capture(tools)
    assert run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')['approved_by_owner'] is True
    assert [n['reason'] for n in done_notices(api)] == ['payment']


def test_a_submit_with_no_prompt_does_not_cover_a_later_one(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    save_button_hook(world)
    summary = capture(tools)
    run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')  # no prompt came
    world.xml = login_typed()
    capture(tools)  # the agent moved on
    world.xml = SAVE_PROMPT_XML  # a prompt from a later sign-in the agent did not submit
    capture(tools)
    assert not [c for c in world.calls if c[0] == 'save_tapped'] and approvals.open_records('owner') == []


# ---- follow-up 1 review: Android's own window, a narrow submit, every tap guarded, the driver's field -------------------------

def look_alike_prompt():
    """The save prompt's words drawn by the app itself, inside its own window."""
    return screen_xml(node('android.widget.TextView', text='Save username and password to Beacon?', clickable=False, bounds=(63, 1700, 1017, 1800)),
                      node(text='Save', bounds=(560, 2000, 1017, 2100)), title='Kanto Foods')


def test_a_look_alike_save_prompt_inside_an_app_is_never_tapped(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    save_button_hook(world)
    summary = capture(tools)
    world.after_input = look_alike_prompt()
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert 'login_save' not in result and not [c for c in world.calls if c[0] == 'save_tapped']


def test_a_save_prompt_over_another_app_is_not_the_agents(phone):
    tools, approvals, world, api = phone
    world.xml = login_typed()
    save_button_hook(world)
    summary = capture(tools)
    world.behind = 'com.example.other'  # Android's prompt, but for an app the agent did not submit in
    world.after_input = SAVE_PROMPT_XML
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    assert 'login_save' not in result and not [c for c in world.calls if c[0] == 'save_tapped']


def test_tab_or_a_confirm_password_field_does_not_settle_a_sign_up(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signup.xml')
    thread, _ = owner_answers(approvals, True)
    run(tools, action='new_login', identifier=ADDRESS, label='Kanto Foods')
    thread.join()
    summary = capture(tools)
    assert 'signup' not in run(tools, action='key', key='tab', purpose='next field')
    assert 'signup' not in run(tools, action='tap', element=element(summary, 'confirm password'), purpose='check it')
    assert not [c for c in world.vault.calls if c[0] == 'settle_signup']
    world.after_input = fixture('shop_signed_in.xml')
    assert run(tools, action='tap', element=element(summary, 'Create account'), purpose='finish')['signup']['saved'] is True


def test_the_focus_tap_before_typing_is_guarded(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', hint='Search', bounds=(63, 344, 1017, 470)), node(text='Go'))
    summary = capture(tools)

    def prompt_once(x, y):
        world.tap_hooks.clear()
        world.focus_override = USB
        world.xml = screen_xml(node('android.widget.EditText', hint='Search', focused=True, bounds=(63, 344, 1017, 470)), node(text='Go'))
    world.tap_hooks.append(prompt_once)
    result = run(tools, action='type', element=element(summary, 'Search'), text='noodles', purpose='search')
    assert result['success'] is True and result['debugging_prompt'] == 'answered no' and world.backs == 1


def test_a_prompt_that_stays_after_a_tap_keeps_the_result(phone):
    tools, approvals, world, api = phone
    summary = capture(tools)

    def stuck(x, y):
        world.focus_override, world.prompt_sticky = USB, True
    world.tap_hooks.append(stuck)
    thread, _ = owner_answers(approvals, True)
    result = run(tools, action='tap', element=element(summary, 'Pay £12.40'), purpose='dinner')
    thread.join()
    assert result['success'] is True and result['debugging_prompt'] == 'still up' and len(taps(world)) == 1
    assert [n['reason'] for n in done_notices(api)] == ['payment']  # the payment ran; its line still goes to the owner


def test_the_driver_never_taps_a_field_that_moved_away(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_signed_in.xml')  # the login form is gone
    taps_made = []
    driver = tools.Driver(tools.Phone(FakeHost(world), {}), (63, 344, 1017, 470), tap=lambda x, y: taps_made.append((x, y)))
    assert driver.pick_entry({}, 'Kanto Foods') is False and taps_made == []


def test_the_drivers_taps_go_through_the_guarded_tap(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')
    world.after_input = login_with_entry()
    guarded = []
    handle = tools.Phone(FakeHost(world), {})
    driver = tools.Driver(handle, (63, 344, 1017, 470), tap=lambda x, y: (guarded.append((x, y)), handle.tap(x, y)))
    assert driver.pick_entry({}, 'Kanto Foods') is True and guarded == [(540, 407), (123, 650)]


# ---- L9 follow-up 1 wired (EMU-JOIN): a code, link or temporary password counts only after the agent's own submit ------------

def test_the_agents_submit_tap_is_the_vaults_since_for_the_next_code(phone):
    """Root (17 Nov): L3 passes since=time.monotonic() taken right before its submit tap; without it, a sign-in the agent typed
    itself never auto-fills its code. A submit in another app is no step here; no submit at all passes none."""
    tools, approvals, world, api = phone
    tools._sign_in_steps.clear()
    world.xml = fixture('shop_login.xml')
    run(tools, action='code', handle='vault_login')
    assert world.vault.since == [None]  # no submit yet: L9 then asks the owner, never an old code
    summary = capture(tools)
    before = time.monotonic()
    run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in')
    after = time.monotonic()
    run(tools, action='code', handle='vault_login')
    assert before <= world.vault.since[-1] <= after  # taken right before the tap, on the vault's clock
    run(tools, action='open_link', handle='vault_login')
    assert before <= world.vault.since[-1] <= after
    capture(tools)
    run(tools, action='tap', element=element(summary, 'Show'), purpose='look')  # not a submit: the step stays
    run(tools, action='code', handle='vault_login')
    assert before <= world.vault.since[-1] <= after
    tools._sign_in_steps[next(iter(tools._sign_in_steps))]['package'] = 'com.example.other'  # the last submit was in another app
    run(tools, action='code', handle='vault_login')
    assert world.vault.since[-1] is None


# ---- PHONEFIX-121 (REVIEW-PHONE-121): each test below failed on tree dbc7ba9c -----------------------------------------------

def _checkout_with_icon(rid, package=PKG, cls='android.widget.ImageButton'):
    return screen_xml(node('android.widget.TextView', text='Total £58.10', clickable=False, bounds=(63, 500, 1017, 600), package=package),
                      node(cls, rid=rid, bounds=(900, 2200, 1017, 2300), package=package), package=package)


def test_p2_1_icon_only_pay_button_with_a_view_id_asks(phone):
    """The id names the element to the model, but it is not a label a person reads: an icon with only an id on a payment screen is
    unlabeled. Chrome reports an HTML id as the resource id, so a web `<button id="go">→</button>` is the same case."""
    tools, approvals, world, api = phone
    for rid, package in ((PKG + ':id/fab', PKG), ('go', 'com.android.chrome'), (PKG + ':id/iv_arrow', PKG)):
        screen = screen_of(tools, _checkout_with_icon(rid, package), package=package)
        icon = len(screen.nodes) - 1
        assert screen.checkout()
        assert tools.assess(screen, 'tap', icon)[0] in ('unlabeled', 'payment'), rid


def test_p2_1_end_to_end_the_icon_tap_asks(phone):
    tools, approvals, world, api = phone
    world.xml = _checkout_with_icon(PKG + ':id/fab')
    summary = capture(tools)
    number = element(summary, 'fab')  # the model still sees the icon by its id
    thread, seen = owner_answers(approvals, False)  # the owner says no
    result = run(tools, action='tap', element=number, purpose='finish the order')
    thread.join()
    assert len(seen) == 1, 'no question was asked'
    assert taps(world) == [] and result.get('error') == 'denied'


def test_p2_1_a_pay_word_in_the_view_id_is_still_a_payment(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, _checkout_with_icon(PKG + ':id/pay_button'))
    assert tools.assess(screen, 'tap', len(screen.nodes) - 1)[0] == 'payment'


def test_p2_1_an_icon_with_a_description_is_not_unlabeled_and_an_icon_with_an_id_on_a_plain_screen_is_an_ordinary_tap(phone):
    tools, approvals, world, api = phone
    described = screen_of(tools, screen_xml(node('android.widget.TextView', text='Total £58.10', clickable=False, bounds=(63, 500, 1017, 600)),
                                            node('android.widget.ImageButton', rid=PKG + ':id/fab', desc='Open menu', bounds=(900, 2200, 1017, 2300))))
    assert tools.assess(described, 'tap', len(described.nodes) - 1)[0] is None  # its description names it
    plain = screen_of(tools, screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)),
                                        node('android.widget.ImageButton', rid=PKG + ':id/fab', bounds=(900, 2200, 1017, 2300))))
    assert tools.assess(plain, 'tap', len(plain.nodes) - 1)[0] is None


ORDER_TOTAL = screen_xml(node('android.widget.TextView', text='Order total £58.10', clickable=False, bounds=(63, 500, 1017, 600)),
                         node(text='Sign in', bounds=(63, 2100, 1017, 2226)))


def test_p2_2_sign_in_control_on_a_payment_screen_asks(phone):
    tools, approvals, world, api = phone
    screen = screen_of(tools, ORDER_TOTAL)
    target = len(screen.nodes) - 1
    assert screen.checkout()
    assert tools.assess(screen, 'tap', target)[0] is not None  # a submit on a payment screen may place the order
    assert tools.assess(screen, 'tap', target, declared='account')[0] is not None  # the agent's own word stands here


def test_p2_2_end_to_end_sign_in_on_a_payment_screen_asks(phone):
    tools, approvals, world, api = phone
    world.xml = ORDER_TOTAL
    summary = capture(tools)
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in', risk='account')
    thread.join()
    assert len(seen) == 1, 'no question was asked'
    assert taps(world) == [] and result.get('error') == 'denied'


@pytest.mark.parametrize('label', ['Sign in', 'Log in', 'Register', 'Create account', 'Verify', 'Save', 'Join', 'Next', 'Done',
                                   'Sign-in', 'تسجيل الدخول', 'Se connecter', 'ログイン'])
def test_p2_2_every_submit_word_asks_on_a_payment_screen_and_nothing_extra_on_a_sign_in(phone, label):
    tools, approvals, world, api = phone
    pay = screen_of(tools, screen_xml(node('android.widget.TextView', text='Order total £58.10', clickable=False, bounds=(63, 500, 1017, 600)),
                                      node(text=label, bounds=(63, 2100, 1017, 2226))))
    assert tools.assess(pay, 'tap', len(pay.nodes) - 1)[0] is not None, label
    ordinary = screen_of(tools, screen_xml(node('android.widget.EditText', desc='email', bounds=(63, 344, 1017, 470)),
                                           node(text=label, bounds=(63, 722, 1017, 848))))
    assert not ordinary.checkout()
    assert tools.assess(ordinary, 'tap', len(ordinary.nodes) - 1)[0] is None, label  # an ordinary sign-in gains no question


def test_p2_2_the_first_submit_after_a_fill_keeps_the_declared_risk_on_a_payment_screen(phone):
    """The fill's one question covers the sign-in's own `account` only on a screen that is not a checkout (one question per
    sign-in, block 14); on a payment screen the agent's word stands."""
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', desc='email', bounds=(63, 344, 1017, 470)),
                           node('android.widget.TextView', text='Order total £58.10', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Sign in', bounds=(63, 2100, 1017, 2226)))
    assert run(tools, action='fill', handle='vault_login')['success'] is True
    summary = capture(tools)
    thread, seen = owner_answers(approvals, False)
    result = run(tools, action='tap', element=element(summary, 'Sign in'), purpose='sign in', risk='account')
    thread.join()
    assert len(seen) == 1 and seen[0]['risk'] in ('account', 'payment')
    assert taps(world) == [] and result.get('error') == 'denied'


def capture_in(tools, session, task='task-1'):
    result = tools.call({'action': 'capture'}, session_id=session, task_id=task)
    assert result.get('_multimodal') is True, result
    return json.loads(result['text_summary'])


def test_p2_3_the_tools_grant_is_bound_to_the_task_and_session_that_got_the_yes(phone):
    tools, approvals, world, api = phone
    run(tools, action='vault_list')  # builds the vault: the callback it was given knows the session
    world.vault.built_with['on_card_approved'](package=PKG, handle='vault_card', amount='£12.40', certs=['ab' * 32], task='task-1', session='chat')  # the vault's grant carries the session of the fill's call
    summary = capture(tools)
    pay = element(summary, 'Pay £12.40')
    # a scheduled run (another task) of the same profile in the same app at the same amount is not covered
    thread, seen = owner_answers(approvals, False)
    assert run(tools, task='task-2', action='tap', element=pay, purpose='dinner')['error'] == 'denied'
    thread.join()
    assert len(seen) == 1 and taps(world) == []
    # nor another session
    other = capture_in(tools, 'other-chat')
    thread, seen = owner_answers(approvals, False)
    assert run(tools, session='other-chat', task='task-1', action='tap', element=element(other, 'Pay £12.40'), purpose='dinner')['error'] == 'denied'
    thread.join()
    assert len(seen) == 1 and taps(world) == []
    # the task and session that got the yes: covered, once
    result = run(tools, action='tap', element=pay, purpose='dinner')
    assert result == {'success': True, 'action': 'tap', 'approved_by_owner': True} and len(taps(world)) == 1


def test_p3_8_the_tool_description_names_no_one_owners_tool(phone):
    tools, approvals, world, api = phone
    assert 'PhoneDriver' not in tools.DESCRIPTION and 'PhoneDriver' not in json.dumps(tools.SCHEMA)
    assert '(phone_* tools)' in tools.DESCRIPTION  # the generic boundary stays


def test_p3_7_a_page_that_draws_the_save_prompts_words_does_not_block_inputs(phone):
    tools, approvals, world, api = phone
    page = screen_of(tools, look_alike_prompt())  # an app page: its own window
    assert tools.hard_block(page, 'tap', index_of(page, 'Save')) is None
    real = tools.Screen(SAVE_PROMPT_XML, package='android', window='android')  # Android's own prompt: the window `android`
    assert 'save a login' in tools.hard_block(real, 'tap', index_of(real, 'Save'))
    unknown = tools.Screen(SAVE_PROMPT_XML, package='android', window=None)  # a window that can't be read fails closed
    assert 'save a login' in tools.hard_block(unknown, 'tap', index_of(unknown, 'Save'))
    assert tools.hard_block(real, 'key', None, key='back') is None  # Back still leaves it


def test_p3_7_end_to_end_a_look_alike_page_does_not_stop_the_agent(phone):
    tools, approvals, world, api = phone
    world.xml = look_alike_prompt()
    summary = capture(tools)
    result = run(tools, action='tap', element=element(summary, 'Save'), purpose='save the note')
    assert result.get('success') is True and len(taps(world)) == 1


def test_p3_3_every_tap_checks_the_lease_so_a_fill_stops_when_the_owner_opens_the_view(phone):
    tools, approvals, world, api = phone
    world.xml = fixture('shop_login.xml')

    def fill_that_loses_the_lease(handle, session, driver, **kw):
        driver.tap(100, 100)  # the first tap: the agent still holds the phone
        world.lease['holder'] = 'human'  # the owner opens the view mid-fill
        driver.tap(200, 200)  # the driver's next tap must not land on the owner's screen
        return {'success': True, 'filled_fields': 2, 'kind': 'login'}
    world.vault.fill = fill_that_loses_the_lease
    result = run(tools, action='fill', handle='vault_login')
    assert result.get('error') == 'busy', result
    assert [t[1:3] for t in taps(world)] == [(100, 100)]


def test_p3_3_a_new_lease_epoch_stops_the_taps_too(phone):
    tools, approvals, world, api = phone
    call = tools.Call({'action': 'tap'}, 'owner', 'default', 'default', Path('unused'), 'chat', 'task-1')
    call.phone = tools.Phone(FakeHost(world), {})
    call._tap(10, 10)  # the first check takes the epoch
    world.lease['epoch'] += 1  # the owner took the phone and gave it back: another epoch
    with pytest.raises(tools.Refusal) as error:
        call._tap(20, 20)
    assert error.value.code == 'busy' and [t[1:3] for t in taps(world)] == [(10, 10)]


# -- P3-1: the picture and the tree its masks come from are read around one screen --------------------------------------------

CARD_AT_500 = screen_xml(node('android.widget.EditText', text='4242 4242 4242 4242', hint='Card number', bounds=(63, 500, 1017, 600)),
                         node(text='Done', bounds=(63, 2100, 1017, 2226)))
CARD_AT_300 = screen_xml(node('android.widget.EditText', text='4242 4242 4242 4242', hint='Card number', bounds=(63, 300, 1017, 400)),
                         node(text='Done', bounds=(63, 2100, 1017, 2226)))


def real_read_guard(monkeypatch):
    module = load('agent_phone_redact', 'beacon_agent_phone_redact')
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', module)
    return module


def test_p3_1_a_screen_that_moved_while_the_picture_was_taken_is_not_shown(phone, monkeypatch):
    """Same window; the card number is at one y in the tree read before the picture and at another in the one read after it
    (a list scrolled, or the keyboard closed). A window or focus comparison cannot see that; the masks of the two reads can."""
    tools, approvals, world, api = phone
    real_read_guard(monkeypatch)
    tools._masks_for('default').register('Filled-Value-1', PKG)  # a fill happened in this app
    world.xml = CARD_AT_500
    world.after_dumps = {2: CARD_AT_300}
    result = run(tools, action='capture')
    assert result.get('error') == 'moving' and '_multimodal' not in result, result
    assert 'chat' not in tools._views  # nothing of it can be acted on


def test_p3_1_a_steady_screen_is_shown_with_its_secret_blacked_out_in_the_picture(phone, monkeypatch):
    import base64
    import io
    from PIL import Image
    tools, approvals, world, api = phone
    real_read_guard(monkeypatch)
    tools._masks_for('default').register('Filled-Value-1', PKG)  # a fill happened in this app
    world.xml = CARD_AT_500
    result = run(tools, action='capture')
    assert result['_multimodal'] is True and world.dumps == 2  # the tree is read on both sides of the picture
    jpeg = base64.b64decode(result['content'][1]['image_url']['url'].split(',', 1)[1])
    picture = Image.open(io.BytesIO(jpeg)).convert('L')
    inside, outside = picture.getpixel((360, 365)), picture.getpixel((360, 900))  # 720 x 1600: the field is y 333-400, x 42-678
    assert inside < 40 and outside > 150, (inside, outside)
    assert '4242' not in result['text_summary']


def test_p3_1_a_window_that_changed_between_the_reads_is_not_shown(phone, monkeypatch):
    tools, approvals, world, api = phone
    real_read_guard(monkeypatch)
    tools._masks_for('default').register('Filled-Value-1', PKG)  # a fill happened in this app
    world.xml = CARD_AT_500
    original = FakeAdb.shell

    def shell(self, args, timeout=60.0, input=None, binary=False):
        text = args if isinstance(args, str) else ' '.join(args)
        if text.startswith('dumpsys window') and not text.startswith('dumpsys window windows') and any(c[0] == 'screenshot' for c in self.world.calls):
            return Done('  mCurrentFocus=Window{4f2a u0 com.example.other/.Main}\n')  # the focus read after the picture: another window
        return original(self, args, timeout=timeout, input=input, binary=binary)
    monkeypatch.setattr(FakeAdb, 'shell', shell)
    assert run(tools, action='capture').get('error') == 'moving'


def test_fu1_a_capture_in_an_app_with_no_fill_reads_the_tree_once_and_a_filled_app_twice(phone, monkeypatch):
    """PHONEFIX-121 follow-up 1: the second tree read is only for an app where a fill happened (a mask can apply there)."""
    tools, approvals, world, api = phone
    real_read_guard(monkeypatch)
    world.xml = CARD_AT_500
    assert run(tools, action='capture')['_multimodal'] is True and world.dumps == 1  # no fill here: one read, as before
    tools._masks_for('default').register('Filled-Value-1', PKG)
    world.dumps = 0
    assert run(tools, action='capture')['_multimodal'] is True and world.dumps == 2  # a fill happened: both sides of the picture
    world.dumps, world.after_dumps = 0, {2: CARD_AT_300}
    assert run(tools, action='capture').get('error') == 'moving'  # and a moving card number is still refused


def test_fu1_an_unnamed_app_with_a_fill_somewhere_still_reads_twice(phone, monkeypatch):
    tools, approvals, world, api = phone
    real_read_guard(monkeypatch)
    tools._masks_for('default').register('Filled-Value-1', 'dev.other.shop')
    world.focus_override = 'com.example.shade'  # a window with no activity package
    world.xml = '<hierarchy><node class="android.view.View" bounds="[0,0][1080,2400]"/></hierarchy>'  # and a tree that names no app
    result = run(tools, action='capture')
    assert result.get('error') == 'masked'  # the read guard refuses it, as before: the capture reads the tree before it decides
    assert world.dumps == 2


# ---- PHONEFIX-121 follow-up 1: one question per vault-card purchase (the owner's rule), the grant's lifetime -------------------

class Owner:
    """The owner's phone answering no to any question until stopped, so a question the tool should not ask shows up in `seen`
    instead of blocking the call."""

    def __init__(self, approvals):
        self.approvals, self.seen, self._stop = approvals, [], threading.Event()
        self.thread = threading.Thread(target=self._run, daemon=True)  # a failed assertion must not leave a thread that holds pytest open
        self.thread.start()

    def _run(self):
        while not self._stop.is_set():
            for record in self.approvals.open_records('owner'):
                if record['id'] not in [r['id'] for r in self.seen]:
                    self.seen.append(record)
                    self.approvals.decide('owner', record['id'], False, 'device-1')
            time.sleep(0.005)

    def stop(self):
        self._stop.set()
        self.thread.join()
        return self.seen


CARD_STEP = screen_xml(node('android.widget.EditText', hint='Card number', bounds=(63, 344, 1017, 470)),
                       node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                       node(text='Continue', bounds=(63, 2000, 1017, 2126)))
REVIEW = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                    node(text='Place order', bounds=(63, 2000, 1017, 2126)))
REVIEW_WITH_STEP = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                              node(text='Continue', bounds=(63, 1800, 1017, 1926)), node(text='Place order', bounds=(63, 2000, 1017, 2126)))
CONFIRM_STEP = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                          node(text='Confirm', bounds=(63, 2000, 1017, 2126)))
PLACED = screen_xml(node('android.widget.TextView', text='Order placed, total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                    node(text='Continue shopping', bounds=(63, 2000, 1017, 2126)))
HOME = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)),
                  node(text='Browse', bounds=(63, 2000, 1017, 2126)))
EDIT_ADDRESS = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                          node(text='Edit address', bounds=(63, 2000, 1017, 2126)))
REVIEW_OTHER_AMOUNT = REVIEW.replace('£12.40', '£14.90')


def card_filled(tools, world):
    """The agent's fill of a vault card: the vault asks Hermes' one consent question, puts the card in, and then records the grant."""
    asked = []

    def fill(handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        asked.append(amount)  # the one question of the purchase
        _grant_call(world, PKG, handle, amount, task_id, session_id)
        return {'success': True, 'filled_fields': 4, 'kind': 'payment', 'app': shop}
    world.vault.fill = fill
    world.xml = CARD_STEP
    assert run(tools, action='fill', handle='vault_card')['success'] is True
    return asked


def press(tools, world, label, then=None, **extra):
    """Capture, then tap `label` by its element number; `then` is the screen the tap leads to."""
    element_number = element(capture(tools), label)
    world.after_input = then
    return run(tools, action='tap', element=element_number, purpose='pay for dinner', **extra)


def test_fu1_card_yes_fill_continue_then_place_order_is_one_question(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    asked = card_filled(tools, world)
    step = press(tools, world, 'Continue', then=REVIEW)
    order = press(tools, world, 'Place order', then=PLACED)
    assert owner.stop() == [] and asked == ['£12.40']  # the card's own yes was the one question
    assert step['success'] is True and step['approved_by_owner'] is True and order['success'] is True and len(taps(world)) == 2


def test_fu1_after_the_order_another_pay_at_the_same_amount_in_the_same_task_asks_again(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    press(tools, world, 'Continue', then=REVIEW)
    assert press(tools, world, 'Place order', then=PLACED)['success'] is True  # the order went
    world.xml = REVIEW  # the same checkout again: a second order
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1 and len(taps(world)) == 2


def test_fu1_a_failed_place_order_that_is_retried_asks_again(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    press(tools, world, 'Continue', then=REVIEW)
    assert press(tools, world, 'Place order')['success'] is True  # the screen stays: the order did not go through
    assert press(tools, world, 'Place order').get('error') == 'denied'  # a retry is a second order: it asks
    assert len(owner.stop()) == 1


def test_fu1_a_confirm_that_leads_to_order_placed_spends_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = CONFIRM_STEP
    assert press(tools, world, 'Confirm', then=PLACED)['approved_by_owner'] is True  # a step: covered
    world.xml = REVIEW  # a later Pay at the same amount in the same task
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_a_step_that_leaves_the_payment_screens_spends_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    assert press(tools, world, 'Continue', then=HOME)['success'] is True
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_a_step_to_a_payment_screen_with_no_pay_control_spends_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    assert press(tools, world, 'Continue', then=EDIT_ADDRESS)['success'] is True  # still a payment screen, but no order button ahead
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_another_amount_on_the_review_screen_asks(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    press(tools, world, 'Continue', then=REVIEW_OTHER_AMOUNT)  # shipping added: the total is not what the owner approved
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_another_session_or_another_task_never_rides_the_flow(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = REVIEW_WITH_STEP
    element_number = element(capture(tools), 'Continue')
    assert run(tools, task='task-2', action='tap', element=element_number, purpose='dinner').get('error') == 'denied'  # another task
    other = element(capture_in(tools, 'other-chat'), 'Continue')
    assert tools.call({'action': 'tap', 'element': other, 'purpose': 'dinner'}, session_id='other-chat', task_id='task-1').get('error') == 'denied'
    assert len(owner.stop()) == 2 and taps(world) == []


REVIEW_FIELD = screen_xml(node('android.widget.EditText', hint='Card number', focused=True, bounds=(63, 344, 1017, 470)),
                          node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                          node(text='Place order', bounds=(63, 2000, 1017, 2126)))
REVIEW_PROMO = screen_xml(node('android.widget.EditText', hint='Promo code', focused=True, bounds=(63, 344, 1017, 470)),
                          node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                          node(text='Place order', bounds=(63, 2000, 1017, 2126)))
REVIEW_GET_IT = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Get it now', bounds=(63, 1800, 1017, 1926)), node(text='Place order', bounds=(63, 2000, 1017, 2126)))


def test_fu1_enter_on_a_payment_screen_may_place_the_order_so_it_uses_the_grant_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = REVIEW_FIELD
    enter = run(tools, action='key', key='enter', purpose='send the order')
    assert enter['success'] is True and enter['approved_by_owner'] is True  # the purchase's one order action
    assert press(tools, world, 'Place order').get('error') == 'denied'  # the Enter may have placed it: no second order on this yes
    assert len(owner.stop()) == 1


def test_fu1_a_typed_submit_on_a_payment_screen_uses_the_grant_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = REVIEW_PROMO
    typed = run(tools, action='type', text='SAVE5', submit=True, purpose='apply the code')
    assert typed['success'] is True and typed['approved_by_owner'] is True
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_a_button_the_agent_declares_a_payment_without_a_commit_word_uses_the_grant_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = REVIEW_GET_IT
    assert press(tools, world, 'Get it now', risk='payment')['approved_by_owner'] is True  # not a step word: it may be the order
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu1_a_review_page_that_arrives_one_read_late_still_keeps_the_grant(phone):
    """After a step tap the tool looks at where it led; the new page is not always up at the first look."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    summary = capture(tools)
    world.after_dumps = {world.dumps + 4: REVIEW}  # reads in the tap: the screen, the grant's fresh look, then the two looks after it
    step = run(tools, action='tap', element=element(summary, 'Continue'), purpose='next step')
    assert step['approved_by_owner'] is True
    assert press(tools, world, 'Place order')['approved_by_owner'] is True  # the grant was kept: no second question
    assert owner.stop() == []


# ---- PHONEFIX-121 follow-up 4: REVIEW-FIX-PHONE-121 (the reviewer's proofs ported, fail-before) --------------------------------------

def _grant_call(world, package, handle, amount, task_id, session_id, **extra):
    """What the vault's fill does after the card went in: its grant, with the session that asked (the call's, never a shared slot)."""
    grant = dict(package=package, handle=handle, amount=amount, certs=[], task=task_id, **extra)
    if session_id is not None:
        grant['session'] = session_id
    world.vault.built_with['on_card_approved'](**grant)


TOTAL = node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600))
PLACE_ORDER = node(text='Place order', bounds=(63, 2000, 1017, 2126))


def _review_with_field(hint):
    return screen_xml(node('android.widget.EditText', hint=hint, focused=True, bounds=(63, 344, 1017, 470)), TOTAL, PLACE_ORDER)


# F1 (P1): only a tap can be a step

@pytest.mark.parametrize('hint', ['Email for order confirmation', 'Confirm email'])
def test_f1_a_typed_submit_on_a_payment_screen_uses_the_grant_up_whatever_the_field_is_called(phone, hint):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = _review_with_field(hint)
    typed = run(tools, action='type', text='agent.kanto' + '@' + 'example.com', submit=True, purpose='finish the checkout')
    assert typed['success'] is True and typed['approved_by_owner'] is True
    second = press(tools, world, 'Place order')  # the page is still processing: the agent retries the order
    seen = owner.stop()
    assert second.get('error') == 'denied' and len(seen) == 1, 'a second order ran on the one card yes'


def test_f1_enter_in_a_confirm_field_uses_the_grant_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = _review_with_field('Confirm email')
    assert run(tools, action='key', key='enter', purpose='send the order')['approved_by_owner'] is True
    second = press(tools, world, 'Place order')
    seen = owner.stop()
    assert second.get('error') == 'denied' and len(seen) == 1, 'a second order ran on the one card yes'


SLIDE_ORDER = screen_xml(TOTAL, node(text='Slide to confirm', bounds=(63, 1800, 1017, 1926)), PLACE_ORDER)
HOLD_ORDER = screen_xml(TOTAL, node(text='Hold to confirm', bounds=(63, 1800, 1017, 1926)), PLACE_ORDER)


@pytest.mark.parametrize('action, xml, label', [('swipe', SLIDE_ORDER, 'Slide to confirm'), ('long_press', HOLD_ORDER, 'Hold to confirm')])
def test_f1_a_slide_or_hold_gesture_uses_the_grant_up(phone, action, xml, label):
    """"Slide to confirm" and "Hold to confirm" place the order; "confirm" is a commit word, so they were taken for steps."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = xml
    extra = {'direction': 'right'} if action == 'swipe' else {}
    gesture = run(tools, action=action, element=element(capture(tools), label), purpose='pay for dinner', **extra)
    assert gesture['success'] is True and gesture['approved_by_owner'] is True
    second = press(tools, world, 'Place order')
    seen = owner.stop()
    assert second.get('error') == 'denied' and len(seen) == 1, 'a second order ran on the one card yes'


# An element that is big and says "Continue" but whose centre lands on the order button (a card that holds it).
LANDS_ON_ORDER = screen_xml(TOTAL, node(text='Continue', bounds=(63, 1900, 1017, 2300)),
                            node(text='Place order', bounds=(400, 2050, 700, 2150)))


def test_f1_a_step_whose_centre_lands_on_the_order_button_uses_the_grant_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = LANDS_ON_ORDER
    step = run(tools, action='tap', element=element(capture(tools), 'Continue'), purpose='pay for dinner')
    assert step['success'] is True and step['approved_by_owner'] is True
    second = press(tools, world, 'Place order')
    seen = owner.stop()
    assert second.get('error') == 'denied' and len(seen) == 1, 'the tap that landed on Place order kept the grant'


# F3 (P1, older than 1.2.1): a dialog in front of a filled form

def _dialog(*nodes):
    return ("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">"
            f'<node index="0" text="" class="android.widget.FrameLayout" package="{PKG}" content-desc="" clickable="false" '
            'enabled="true" focusable="false" focused="false" scrollable="false" password="false" bounds="[100,900][980,1500]" hint="">'
            + ''.join(nodes) + '</node></hierarchy>')


def _shown_picture(result):
    import base64
    import io
    from PIL import Image
    url = result['content'][1]['image_url']['url']
    return Image.open(io.BytesIO(base64.b64decode(url.split(',', 1)[1]))).convert('L')


def test_f3_a_dialog_over_a_filled_card_form_never_shows_the_form_in_the_picture(phone, monkeypatch):
    """`uiautomator dump` without --windows dumps the active window only; the screenshot is the whole screen. With the real read
    guard, nothing outside the dialog was blacked out."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())  # the real read guard
    tools._masks.clear()
    tools._masks_for('default').register('4242424242424242', PKG)  # the card went in: the shop is a filled app
    world.xml = _dialog(node('android.widget.TextView', text='Save this card for next time?', clickable=False, bounds=(140, 940, 940, 1100)),
                        node(text='Not now', bounds=(140, 1350, 520, 1460)), node(text='Save', bounds=(560, 1350, 940, 1460)))
    result = run(tools, action='capture')
    if result.get('error'):
        return  # refused: nothing of the form behind reached the model
    shown = _shown_picture(result)
    y = int(400 * shown.height / 2400)  # the card form behind the dialog sits above it
    assert shown.getpixel((shown.width // 2, y)) < 20, 'the filled form behind the dialog is in the picture, unmasked'
    assert shown.getpixel((shown.width // 2, int(1200 * shown.height / 2400))) > 100  # the dialog itself stays readable


def test_f3_a_secret_inside_a_dialog_is_blacked_out_where_it_is(phone, monkeypatch):
    """Screen.size was the tree root's right and bottom; a dialog's root is the dialog, so its boxes were scaled to the wrong place."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    world.xml = _dialog(node('android.widget.TextView', text='Add a card', clickable=False, bounds=(140, 920, 940, 990)),
                        node('android.widget.EditText', text='4242 4242 4242 4242', hint='Card number', bounds=(140, 1000, 940, 1100)),
                        node(text='OK', bounds=(560, 1350, 940, 1460)))
    result = run(tools, action='capture')
    assert '••••' in result['text_summary'] and '4242 4242' not in result['text_summary']  # the text side is masked (Luhn)
    shown = _shown_picture(result)
    x, y = int(540 * shown.width / 1080), int(1050 * shown.height / 2400)  # the field's centre on the 1080x2400 screen
    assert shown.getpixel((x, y)) < 20, "the card field's box is drawn elsewhere: its digits are in the picture"


def test_f3_a_tree_that_covers_the_screen_blacks_out_nothing_more(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)))
    shown = _shown_picture(run(tools, action='capture'))
    assert shown.getpixel((shown.width // 2, int(1200 * shown.height / 2400))) > 100 and shown.getpixel((5, 5)) > 100


def test_f3_another_window_in_front_of_an_app_that_had_a_fill_refuses_the_capture(phone, monkeypatch):
    """A window that is not the filled app's (another app's translucent screen, Android's own prompt) hides the app from the tree
    but not from the picture."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Filled-Value-1', PKG)
    world.shell_answers.append((r'^dumpsys window \|', ('  mCurrentFocus=Window{4f2a u0 com.example.overlay/.Veil}\n'
                                                       f'  mFocusedApp=ActivityRecord{{1 u0 {PKG}/.CardActivity t44}}\n',)))
    world.xml = screen_xml(node('android.widget.TextView', text='Veil', clickable=False, bounds=(63, 500, 1017, 600), package='com.example.overlay'),
                           node(text='Close', bounds=(63, 900, 1017, 1000), package='com.example.overlay'), package='com.example.overlay')
    result = run(tools, action='capture')
    assert result.get('error') == 'masked' and not result.get('_multimodal') and 'back' in result['message']  # what the agent can do
    world.shell_answers.clear()
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)))
    assert run(tools, action='capture').get('_multimodal') is True  # the filled app itself in front: shown as before


# F4 (P2): a loading screen, or a not-yet-enabled order button, must not spend the grant at a step

LOADING = screen_xml(node('android.widget.ProgressBar', clickable=False, bounds=(490, 1150, 590, 1250)))
DISABLED_ORDER = screen_xml(TOTAL, node(text='Place order', bounds=(63, 2000, 1017, 2126)).replace('enabled="true"', 'enabled="false"'))


def test_f4_a_loading_screen_between_continue_and_the_review_page_keeps_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    summary = capture(tools)
    world.after_input = LOADING  # the step tap brings a spinner first, as apps and web pages do
    world.after_dumps = {world.dumps + 4: REVIEW}  # the review page is up by the second look
    step = run(tools, action='tap', element=element(summary, 'Continue'), purpose='next step')
    assert step['approved_by_owner'] is True
    order = press(tools, world, 'Place order')
    seen = owner.stop()
    assert seen == [] and order.get('approved_by_owner') is True, 'one purchase asked the owner twice'


def test_f4_a_review_page_whose_order_button_is_not_enabled_yet_keeps_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    press(tools, world, 'Continue', then=DISABLED_ORDER)  # "Place order" waits for the terms box
    world.xml = REVIEW  # the box ticked: the order button is enabled
    order = press(tools, world, 'Place order')
    seen = owner.stop()
    assert seen == [] and order.get('approved_by_owner') is True, 'one purchase asked the owner twice'


# F5 (P2): in a browser the grant is bound to the shop, not the browser

CHROME = 'com.android.chrome'


def _site(host_name, *nodes, bar=True):
    bar_node = node('android.widget.EditText', text=host_name, rid=CHROME + ':id/url_bar', bounds=(150, 100, 900, 200), package=CHROME) if bar else ''
    return screen_xml(bar_node, *nodes, package=CHROME, title=host_name)


def _web_card_filled(tools, world, domain='shop-a.example'):
    def fill(handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        _grant_call(world, CHROME, handle, amount, task_id, session_id, web_domain=domain)  # the vault's grant names the site
        return {'success': True, 'filled_fields': 4, 'kind': 'payment', 'app': shop}
    world.vault.fill = fill
    world.package = CHROME
    world.xml = _site('shop-a.example', node('android.widget.EditText', hint='Card number', focused=True, bounds=(63, 344, 1017, 470), package=CHROME),
                      node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600), package=CHROME))
    assert run(tools, action='fill', handle='vault_card')['success'] is True


def _web_review(host_name, button='Place order', **kw):
    return _site(host_name, node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600), package=CHROME),
                 node(text=button, bounds=(63, 2000, 1017, 2126), package=CHROME), **kw)


def test_f5_a_card_yes_on_one_site_does_not_cover_a_pay_on_another_site(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('shop-b.example')
    order = press(tools, world, 'Place order')
    seen = owner.stop()
    assert order.get('error') == 'denied' and len(seen) == 1, "shop-a's card yes paid at shop-b"


def test_f5_the_same_site_is_covered(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('shop-a.example')
    assert press(tools, world, 'Place order')['approved_by_owner'] is True
    assert owner.stop() == []


def test_f5_a_subdomain_of_the_site_is_the_site(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('checkout.shop-a.example')
    assert press(tools, world, 'Place order')['approved_by_owner'] is True
    assert owner.stop() == []


def test_f5_a_look_alike_host_is_not_the_site(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('evilshop-a.example')
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_f5_a_web_grant_covers_no_tap_when_the_page_host_cannot_be_read(phone):
    """No address bar in the tree (a full-screen page, a tree that does not show it): web orders ask once more, never a wrong yes."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('shop-a.example', bar=False)
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_f5_a_step_on_the_site_keeps_the_grant_only_while_the_host_matches(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    world.xml = _web_review('shop-a.example', button='Continue')
    summary = capture(tools)
    world.after_input = _web_review('shop-b.example')  # the step led to another site
    assert run(tools, action='tap', element=element(summary, 'Continue'), purpose='next step')['approved_by_owner'] is True
    assert press(tools, world, 'Place order').get('error') == 'denied'  # the grant did not follow to the other site
    assert len(owner.stop()) == 1


# F6 (P3): the grant takes its session from the call, not from a shared slot

def test_f6_another_chats_vault_call_during_the_card_question_does_not_move_the_grant(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)

    def fill(handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        # While the owner reads the card question (up to 2 minutes), another chat of the same profile lists the vault.
        assert run(tools, session='other-chat', task='task-9', action='vault_list')['success'] is True
        _grant_call(world, PKG, handle, amount, task_id, session_id)
        return {'success': True, 'filled_fields': 4, 'kind': 'payment', 'app': shop}
    world.vault.fill = fill
    world.xml = CARD_STEP
    assert run(tools, action='fill', handle='vault_card')['success'] is True
    step = press(tools, world, 'Continue', then=REVIEW)  # this chat's own purchase, right after its own card yes
    seen = owner.stop()
    assert seen == [] and step.get('approved_by_owner') is True, 'the purchase asked twice: the grant names the other chat'


# F8 (P3): typing and keys re-check the lease

def test_f8_typing_stops_when_the_owner_takes_the_phone(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', hint='Search', focused=True, bounds=(63, 344, 1017, 470)))

    class Flip(list):
        def append(self, item):
            super().append(item)
            if sum(1 for i in self if i[0] == 'key') == 5:
                world.lease['holder'] = 'human'  # the owner opened the live view mid-typing
    world.inputs = Flip(world.inputs)
    result = run(tools, action='type', text='x' * 60, submit=True, purpose='search the shop')
    keys = [i for i in world.inputs if i[0] == 'key']
    assert result.get('error') == 'busy' and len(keys) < 60, f"{len(keys)} keys landed on the owner's screen, Enter included"


def test_f8_enter_after_typing_checks_the_lease_again(phone):
    tools, approvals, world, api = phone
    world.xml = screen_xml(node('android.widget.EditText', hint='Search', focused=True, bounds=(63, 344, 1017, 470)))

    class Flip(list):
        def append(self, item):
            super().append(item)
            if sum(1 for i in self if i[0] == 'key') == 2:
                world.lease['holder'] = 'human'
    world.inputs = Flip(world.inputs)
    result = run(tools, action='type', text='ab', submit=True, purpose='search the shop')  # fewer than 10 keys: only the Enter check can catch it
    assert result.get('error') == 'busy' and len([i for i in world.inputs if i[0] == 'key']) == 2, 'Enter landed on the owner\'s screen'


def test_f8_the_drivers_keys_check_the_lease_first(phone):
    tools, approvals, world, api = phone
    checks, keys = [], []

    class Keys:
        def tree(self):
            return screen_xml(node('android.widget.EditText', text='old', hint='Email', focused=True, bounds=(63, 344, 1017, 470)))

        def select_all(self):
            keys.append('ctrl+a')

        def key(self, name):
            keys.append(name)

        def tap(self, x, y):
            pass

    def check():
        checks.append(1)
        if len(checks) >= 2:
            raise tools.Refusal('busy', 'the owner has the phone')
    driver = tools.Driver(Keys(), (63, 344, 1017, 470), check=check)
    with pytest.raises(tools.Refusal):
        driver._empty_own_field({'fields': [{'focused': True, 'role': 'identifier'}]}, ('identifier',))
    assert keys == ['ctrl+a'] and len(checks) == 2  # Ctrl+A went; the check before Delete stopped it


# The agent_phone_action question's `pay` flag (REVIEW-FIX-UI-121): true only when the tapped control itself says it pays

def _asked(phone, xml, **tap):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = xml
    if tap.get('action', 'tap') == 'tap':
        press(tools, world, tap.pop('label'), **tap)
    else:
        run(tools, purpose='pay for dinner', **tap)
    seen = owner.stop()
    assert len(seen) == 1 and seen[0]['kind'] == 'agent_phone_action', seen
    return seen[0]


PAY_NODES = {
    'a pay word': (screen_xml(TOTAL, PLACE_ORDER), 'Place order', True),
    'an amount': (screen_xml(TOTAL, node(text='£12.40', bounds=(63, 2000, 1017, 2126))), '£12.40', True),
    'Sign in': (screen_xml(TOTAL, node(text='Sign in', bounds=(63, 2000, 1017, 2126))), 'Sign in', False),
    'Continue': (screen_xml(TOTAL, node(text='Continue', bounds=(63, 2000, 1017, 2126))), 'Continue', False),
    'an unlabeled control': (screen_xml(TOTAL, node(text='', bounds=(63, 2000, 1017, 2126))), '', False),
}


@pytest.mark.parametrize('name', list(PAY_NODES))
def test_the_action_question_says_pay_only_when_the_tapped_control_itself_pays(phone, name):
    xml, label, expected = PAY_NODES[name]
    record = _asked(phone, xml, label=label)
    assert record['pay'] is expected, (name, record)


def test_the_action_question_says_pay_false_for_enter_even_in_a_field_that_says_pay(phone):
    """Enter has no tapped control: the focused field's words ("Pay by card") must not make it a Pay question."""
    xml = screen_xml(node('android.widget.EditText', hint='Pay by card', focused=True, bounds=(63, 344, 1017, 470)), TOTAL, PLACE_ORDER)
    record = _asked(phone, xml, action='key', key='enter')
    assert record['risk'] == 'payment' and record['pay'] is False


def test_the_action_question_says_pay_false_for_a_typed_submit(phone):
    xml = screen_xml(node('android.widget.EditText', hint='Pay by card', focused=True, bounds=(63, 344, 1017, 470)), TOTAL, PLACE_ORDER)
    record = _asked(phone, xml, action='type', text='ok', submit=True)
    assert record['pay'] is False


# F13 (P3): the first look after a covered step does not wait

def test_f13_the_first_look_after_a_step_does_not_sleep(phone, monkeypatch):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    napped = []
    monkeypatch.setattr(tools.time, 'sleep', lambda s: napped.append(s))  # the owner's thread naps 5 ms: only the look's second counts
    press(tools, world, 'Continue', then=REVIEW)  # the review page is up at the first look
    assert owner.stop() == [] and 1.0 not in napped, 'the step waited before it looked'


# ---- F3 and F5 follow-ups (the advisor's review of the first draft) ----------------------------------------------------------------

def test_f3_a_menu_of_the_filled_app_itself_is_shown_not_refused(phone, monkeypatch):
    """A popup or menu is a window with no package of its own: its tree root names the filled app, and that is no other window."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Filled-Value-1', PKG)
    world.shell_answers.append((r'^dumpsys window \|', ('  mCurrentFocus=Window{4f2a u0 PopupWindow:1a2b}\n'
                                                       f'  mFocusedApp=ActivityRecord{{1 u0 {PKG}/.CardActivity t44}}\n',)))
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)))
    assert run(tools, action='capture').get('_multimodal') is True


def _webview_page(host_name, *nodes):
    """Chrome's tree: the toolbar's address bar, then the page inside a WebView (the page's own elements are the WebView's children)."""
    page = ('<node class="android.webkit.WebView" package="com.android.chrome" resource-id="" text="" content-desc="" clickable="false" '
            'enabled="true" focusable="true" focused="false" scrollable="true" password="false" bounds="[0,300][1080,2400]" hint="">'
            + ''.join(nodes) + '</node>')
    bar = node('android.widget.EditText', text=host_name, rid=CHROME + ':id/url_bar', bounds=(150, 100, 900, 200), package=CHROME) if host_name else ''
    return screen_xml(bar, page, package=CHROME, title='page')


def test_f5_a_page_that_draws_its_own_url_bar_id_does_not_name_the_host(phone):
    """The tree shows HTML ids as resource ids: with Chrome's toolbar scrolled away, the page's own element with the address bar's id
    must not become the host."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    _web_card_filled(tools, world)
    forged = node('android.widget.EditText', text='shop-a.example', rid=CHROME + ':id/url_bar', bounds=(150, 320, 900, 400), package=CHROME)
    world.xml = _webview_page(None, forged, node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600), package=CHROME),
                              node(text='Place order', bounds=(63, 2000, 1017, 2126), package=CHROME))
    assert tools._page_host(tools.Screen(world.xml, package=CHROME)) == ''
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_f5_the_real_address_bar_wins_over_a_page_that_draws_one(phone):
    tools, approvals, world, api = phone
    forged = node('android.widget.EditText', text='shop-a.example', rid=CHROME + ':id/url_bar', bounds=(150, 320, 900, 400), package=CHROME)
    xml = _webview_page('shop-b.example', forged)
    assert tools._page_host(tools.Screen(xml, package=CHROME)) == 'shop-b.example'


def test_f5_a_bar_of_another_package_is_not_this_apps_address_bar(phone):
    tools, approvals, world, api = phone
    other = node('android.widget.EditText', text='shop-a.example', rid='com.other.browser:id/url_bar', bounds=(150, 100, 900, 200), package=CHROME)
    assert tools._page_host(tools.Screen(screen_xml(other, package=CHROME), package=CHROME)) == ''


def test_f5_chrome_on_the_agent_phone_shows_the_host_in_its_address_bar_until_the_page_scrolls(phone):
    """Measured on the agent phone (Chrome, 19 Nov 2026, fixtures/agent_phone/chrome_*.xml): com.android.chrome:id/url_bar is an
    EditText whose text is the host (the host and path when the address is long), outside the WebView; once the page is scrolled
    down the toolbar is gone and the tree holds no address bar, so no host can be read."""
    tools, approvals, world, api = phone
    page = tools.Screen(fixture('chrome_example_com.xml'), package='com.android.chrome')
    assert tools._page_host(page) == 'example.com'
    scrolled = tools.Screen(fixture('chrome_scrolled_page.xml'), package='com.android.chrome')
    assert tools._page_host(scrolled) == ''


# ---- the advisor's last review: an order the owner approved by question ends the purchase's grant ----------------------------------

def test_f5_an_order_the_owner_approved_by_question_ends_the_grant(phone):
    """A scrolled-down web page hides the address bar, so the order tap asks; the owner says yes and the order goes. The page that
    follows shows the toolbar again, and a retried "Place order" at the same amount must not be covered by the card's yes."""
    tools, approvals, world, api = phone
    _web_card_filled(tools, world)
    thread, seen = owner_answers(approvals, [True, False], times=2)
    world.xml = _web_review('shop-a.example', bar=False)
    first = press(tools, world, 'Place order')
    assert first['success'] is True and first['approved_by_owner'] is True
    world.xml = _web_review('shop-a.example')  # the confirmation or "payment failed" page: the toolbar is back
    second = press(tools, world, 'Place order')
    thread.join()
    assert second.get('error') == 'denied' and len(seen) == 2, 'a second order ran on the one card yes'


def test_fu4_an_order_the_owner_approved_at_another_amount_ends_the_grant(phone):
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, [True, False], times=2)
    world.xml = REVIEW_OTHER_AMOUNT  # shipping was added: the total is not what the card's yes was for
    first = press(tools, world, 'Place order')
    assert first['success'] is True and first['approved_by_owner'] is True
    world.xml = REVIEW  # the same checkout back at the approved amount: a second order
    second = press(tools, world, 'Place order')
    thread.join()
    assert second.get('error') == 'denied' and len(seen) == 2, 'a second order ran on the one card yes'


def test_fu4_a_plain_step_the_owner_approved_by_question_keeps_the_grant(phone):
    tools, approvals, world, api = phone
    _web_card_filled(tools, world)
    thread, seen = owner_answers(approvals, True, times=1)
    world.xml = _web_review('shop-a.example', button='Continue', bar=False)  # no address bar: the step asks
    step = press(tools, world, 'Continue', then=_web_review('shop-a.example'))
    assert step['success'] is True and step['approved_by_owner'] is True
    order = press(tools, world, 'Place order')  # the card's yes still stands for the order
    thread.join()
    assert order.get('approved_by_owner') is True and len(seen) == 1


def test_f5_an_address_bar_being_typed_into_is_not_the_page_host(phone):
    tools, approvals, world, api = phone
    typing = node('android.widget.EditText', text='shop-a.example', rid=CHROME + ':id/url_bar', focused=True, bounds=(150, 100, 900, 200), package=CHROME)
    assert tools._page_host(tools.Screen(screen_xml(typing, package=CHROME), package=CHROME)) == ''


# ---- PHONEFIX-121 follow-up 5: REVIEW-FIX-PHONE-121-R2 (the reviewer's proofs ported, fail-before) -----------------------------------

# R2-1 (P1): a scrolled Chrome page lets an order tap run with no question

def test_r2_1_a_coordinate_tap_on_a_scrolled_chrome_page_asks(phone):
    """The measured tree of a scrolled Chrome page (13 nodes, the only name is the "Web View" placeholder, no page content, no address
    bar) was "readable" to the risk check and no payment screen, so a coordinate tap on the page's order button had no risk at all."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)  # answers No to any question
    world.package = CHROME
    world.xml = fixture('chrome_scrolled_page.xml')
    result = run(tools, action='tap', coordinate=[540, 2000], purpose='place the order')  # the order button, from the picture
    seen = owner.stop()
    assert result.get('error') == 'denied' and taps(world) == [] and len(seen) == 1, 'the order tap ran with no question'


def test_r2_1_a_coordinate_tap_that_lands_on_no_labeled_control_asks_as_unlabeled(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Browse', bounds=(63, 2000, 1017, 2126)))  # readable, and no payment screen
    nowhere = run(tools, action='tap', coordinate=[540, 1200], purpose='open the menu')
    seen = owner.stop()
    assert nowhere.get('error') == 'denied' and taps(world) == [] and len(seen) == 1 and seen[0]['risk'] == 'unlabeled'


def test_r2_1_a_coordinate_tap_on_a_labeled_control_does_not_ask(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Browse', bounds=(63, 2000, 1017, 2126)))
    assert run(tools, action='tap', coordinate=[540, 2060], purpose='browse')['success'] is True
    assert owner.stop() == []


# R2-2 (P1): the owner's No ends the card's yes

NO_TOTAL = screen_xml(node('android.widget.TextView', text='Subtotal £10.00', clickable=False, bounds=(63, 500, 1017, 600)),
                      node('android.widget.TextView', text='Delivery £2.40', clickable=False, bounds=(63, 600, 1017, 700)),
                      node(text='Place order', bounds=(63, 2000, 1017, 2126)))


def test_r2_2_an_order_the_owner_said_no_to_is_not_then_placed_on_the_card_yes(phone):
    """The order asks when no grant covers it (here: two amounts and no total; on the web: the address bar scrolled away). The owner says
    No. The agent scrolls, the total (or the bar) shows, and it taps Place order again: the card's earlier yes covered it."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)  # answers No to every question
    card_filled(tools, world)
    world.xml = NO_TOTAL
    first = press(tools, world, 'Place order')
    assert first.get('error') == 'denied'  # the owner said no to this order
    world.xml = REVIEW  # the same order, its total now readable
    second = press(tools, world, 'Place order')
    owner.stop()
    assert second.get('error') == 'denied' and taps(world) == [], "the owner's No was overridden: the order ran on the card's yes"


def test_r2_2_a_missed_question_ends_the_grant_too(phone, monkeypatch):
    tools, approvals, world, api = phone
    card_filled(tools, world)
    world.xml = NO_TOTAL
    with monkeypatch.context() as nobody:
        nobody.setattr(approvals, 'wait', lambda *a, **k: 'expired')  # nobody answers: the question expires
        assert press(tools, world, 'Place order').get('error') == 'expired'
    owner = Owner(approvals)
    world.xml = REVIEW
    second = press(tools, world, 'Place order')
    owner.stop()
    assert second.get('error') == 'denied' and taps(world) == []


# R2-9: an approved tap with no pay word and no amount keeps the grant


def test_r2_9_an_approved_coordinate_tap_on_no_control_ends_the_grant(phone):
    """With no node under it the tap could be an order drawn on a canvas: it keeps nothing."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, [True, False], times=2)
    world.xml = screen_xml(TOTAL, PLACE_ORDER)
    assert run(tools, action='tap', coordinate=[540, 1200], purpose='next')['approved_by_owner'] is True
    second = press(tools, world, 'Place order')
    thread.join()
    assert second.get('error') == 'denied' and len(seen) == 2


# N1: the pay flag uses the shorter list (the UI review); the risk check's PAY_WORDS is unchanged

PAYS = ['Place order', 'Place your order', 'Pay', 'Pay now', 'Buy', 'Buy now', 'Purchase', 'Confirm order', 'Confirm payment', 'Confirm purchase',
        'Complete order', 'Complete purchase', 'Subscribe', 'Payer', 'Acheter', 'Pagar', 'Comprar', 'Jetzt kaufen', 'Bezahlen', 'Acquista',
        'Satın al', 'Купить', 'ادفع', 'شراء', '支付', '购买', '購入', '결제', 'Pay £12.40', '£12.40']
NOT_PAYS = ['Checkout', 'Check out', 'Proceed to checkout', 'Continue to payment', 'Continue', 'Sign in', 'Next', 'Done', 'Confirm', 'Add to cart',
            'Transfer', 'Donate', 'Top up', 'Book now', 'Zur Kasse', 'Passer à la caisse', '']


@pytest.mark.parametrize('label', PAYS)
def test_n1_the_pay_flag_says_pay_for_the_pay_list(phone, label):
    tools = phone[0]
    shown = tools.Screen(screen_xml(node(text=label, bounds=(63, 2000, 1017, 2126))), package=PKG)
    assert tools._pays(shown.nodes[-1]) is True, label


@pytest.mark.parametrize('label', NOT_PAYS)
def test_n1_checkout_navigation_and_the_other_pay_words_are_not_pay(phone, label):
    tools = phone[0]
    shown = tools.Screen(screen_xml(node(text=label, bounds=(63, 2000, 1017, 2126))), package=PKG)
    assert tools._pays(shown.nodes[-1]) is False, label


def test_n1_proceed_to_checkout_still_asks_as_a_payment_but_is_not_pay(phone):
    """PAY_WORDS stays as it is for the risk check: the tap asks; only the question's flag differs."""
    tools, approvals, world, api = phone
    cart = screen_xml(node('android.widget.TextView', text='Cart total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                      node(text='Proceed to checkout', bounds=(63, 2000, 1017, 2126)))
    record = _asked(phone, cart, label='Proceed to checkout')
    assert record['risk'] == 'payment' and record['pay'] is False


# R2-7: a fill's site is carried for every browser or app; a tap is covered only while that package's own address bar shows it

def test_r2_7_a_grant_that_names_a_site_covers_no_tap_in_an_app_without_an_address_bar(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)

    def fill(handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        _grant_call(world, PKG, handle, amount, task_id, session_id, web_domain='pay.kanto.example')  # a page in the shop's own app
        return {'success': True, 'filled_fields': 4, 'kind': 'payment', 'app': shop}
    world.vault.fill = fill
    world.xml = CARD_STEP
    assert run(tools, action='fill', handle='vault_card')['success'] is True
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'  # no address bar to read: it asks
    assert len(owner.stop()) == 1


def test_r2_7_another_browsers_own_address_bar_names_the_host(phone):
    tools, approvals, world, api = phone
    bar = node('android.widget.EditText', text='shop-a.example', rid='com.brave.browser:id/url_bar', bounds=(150, 100, 900, 200), package='com.brave.browser')
    page = tools.Screen(screen_xml(bar, package='com.brave.browser'), package='com.brave.browser')
    assert tools._page_host(page) == 'shop-a.example'


# R2-3 (P1, older): a window that fills the screen hides the filled form from the masks

def _root(package, *nodes):
    return (f'<node index="0" text="" class="android.widget.FrameLayout" package="{package}" content-desc="" clickable="false" '
            'enabled="true" focusable="false" focused="false" scrollable="false" password="false" bounds="[0,0][1080,2400]" hint="">'
            + ''.join(nodes) + '</node>')


def _plain(root):
    return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><hierarchy rotation=\"0\">" + root + '</hierarchy>'


def _windows_dump(*windows):
    """`uiautomator dump --windows` as the agent phone prints it (fixtures/agent_phone/chrome_windows_dump.xml): one hierarchy per window."""
    body = ''.join(f'<window index="{i}" title="" bounds="[0,0][1080,2400]" active="{str(active).lower()}" focused="{str(active).lower()}" '
                   f'accessibility-focused="false" id="{i + 20}" layer="{i}" type="TYPE_APPLICATION"><hierarchy rotation="0">{root}</hierarchy></window>'
                   for i, (active, root) in enumerate(windows))
    return "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id=\"0\">" + body + '</display></displays>'


def _sheet_over_a_card_form():
    sheet = node('android.widget.FrameLayout', clickable=False, bounds=(0, 1600, 1080, 2400), rid=PKG + ':id/design_bottom_sheet')
    sheet_root = _root(PKG, sheet, node('android.widget.TextView', text='Save this card for next time?', clickable=False, bounds=(63, 1650, 1017, 1800)),
                       node(text='Not now', bounds=(63, 2200, 520, 2330)), node(text='Save', bounds=(560, 2200, 1017, 2330)))
    form_root = _root(PKG, node('android.widget.TextView', text='Kanto Foods checkout', clickable=False, bounds=(63, 252, 1017, 344)),
                      node('android.widget.EditText', text='4242 4242 4242 4242', hint='Card number', bounds=(63, 344, 1017, 470)))
    return sheet_root, form_root


def test_r2_3_a_bottom_sheet_over_a_filled_card_form_never_shows_the_form_in_the_picture(phone, monkeypatch):
    """Material's BottomSheetDialog sets its window to MATCH_PARENT both ways, so the dumped root covers the screen: the black-out outside
    the root does nothing, the window is the filled app's own (no refusal), and the plain dump holds the sheet only. The form behind the
    sheet's scrim was in the picture with no box. The windows dump lists the form's window too (adapted: the fake phone answers
    `--windows` with `world.windows_xml`)."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())  # the real read guard
    tools._masks.clear()
    tools._masks_for('default').register('4242424242424242', PKG)  # the card went in: the shop is a filled app
    sheet_root, form_root = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    world.windows_xml = _windows_dump((True, sheet_root), (False, form_root))
    result = run(tools, action='capture')
    assert not result.get('error'), result
    shown = _shown_picture(result)
    y = int(400 * shown.height / 2400)  # the card number field of the form behind the sheet (y 344-470)
    assert shown.getpixel((shown.width // 2, y)) < 20, 'the filled form behind the bottom sheet is in the picture, unmasked'
    summary = json.loads(result['text_summary'])
    assert 'Save this card for next time?' in summary['untrusted_data']['text'] and '4242' not in result['text_summary']  # text: the active window's
    assert any('Save' in line for line in summary['untrusted_data']['elements'])  # elements: the active window's


def test_r2_3_the_sheets_own_secrets_and_the_forms_are_both_masked(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('4242424242424242', PKG)
    sheet_root = _root(PKG, node('android.widget.TextView', text='Add a card', clickable=False, bounds=(63, 1650, 1017, 1750)),
                       node('android.widget.EditText', text='5555 5555 5555 4444', hint='Card number', bounds=(63, 1800, 1017, 1900)))
    _, form_root = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    world.windows_xml = _windows_dump((True, sheet_root), (False, form_root))
    result = run(tools, action='capture')
    assert '5555 5555' not in result['text_summary']
    shown = _shown_picture(result)
    assert shown.getpixel((shown.width // 2, int(400 * shown.height / 2400))) < 20  # the form behind
    assert shown.getpixel((shown.width // 2, int(1850 * shown.height / 2400))) < 20  # the sheet's own card field


def test_r2_3_a_filled_apps_unreadable_window_refuses_the_capture(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('4242424242424242', PKG)
    sheet_root, _ = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    world.windows_xml = _windows_dump((True, sheet_root), (False, _root(PKG)))  # the shop's own window behind, with nothing readable in it
    assert run(tools, action='capture').get('error') == 'masked'


def test_r2_3_the_windows_dump_of_the_agent_phone_is_read_window_by_window(phone):
    tools = phone[0]
    windows = tools.parse_windows(fixture('chrome_windows_dump.xml'))
    assert [w['package'] for w in windows] == ['com.android.systemui', 'com.android.chrome'] and [w['active'] for w in windows] == [False, True]
    assert tools._page_host(tools.Screen(windows[1]['xml'], package='com.android.chrome')) == 'example.com'  # the active window as a plain tree
    assert tools.parse_windows(fixture('chrome_example_com.xml')) is None  # a plain tree is not a window list
    assert tools.parse_windows('') is None and tools.parse_windows('<displays') is None and tools.parse_windows(None) is None


# R2-6: the Recents overview shows pictures of the apps' last screens (measured: they stay after a force-stop)

def test_r2_6_the_recents_overview_is_never_captured(phone):
    """Measured on the agent phone (fixtures/agent_phone/launcher_overview.xml): Recents is the launcher's own activity, so the focused
    window and the focused app name the launcher whether it shows home or the overview. The overview's tree holds `overview_panel` with
    one `task_view_single`, and its `snapshot` picture, per recent app: the picture of that app's last screen, which no mask covers. A
    force-stop does not remove the task or its picture (measured), so the refusal does not wait for a fill to be on record."""
    tools, approvals, world, api = phone
    world.package = 'com.google.android.apps.nexuslauncher'
    world.xml = fixture('launcher_overview.xml')
    result = run(tools, action='capture')
    assert result.get('error') == 'masked' and 'back' in result['message'] and not result.get('_multimodal')
    tools._masks_for('default').register('Filled-Value-1', PKG)  # and while a fill is on record
    assert run(tools, action='capture').get('error') == 'masked'


def test_r2_6_the_launchers_home_screen_is_captured_as_before(phone):
    tools, approvals, world, api = phone
    world.package = 'com.google.android.apps.nexuslauncher'
    package = world.package
    world.xml = screen_xml(node('android.widget.TextView', text='Pixel Launcher', clickable=False, bounds=(63, 500, 1017, 600), package=package),
                           node(text='Chrome', desc='Chrome', bounds=(63, 900, 300, 1100), package=package), package=package)
    assert run(tools, action='capture').get('_multimodal') is True


def test_r2_3_the_windows_dump_the_phone_printed_after_a_fill_lists_the_keyboard_window_too(phone):
    from test_agent_phone_redact import load as load_redact
    tools = phone[0]
    windows = tools.parse_windows(fixture('shop_windows_after_fill.xml'))
    assert [w['package'] for w in windows] == ['com.android.systemui', 'dev.hermes.agentphone.helper', PKG]
    assert [w['active'] for w in windows] == [False, False, True]
    redact = load_redact()
    masks = redact.MaskSet()
    masks.register('Beacon-Filled-1', PKG)
    boxes = redact.mask_tree(windows[2]['xml'], masks, PKG)[1]
    assert (63, 470, 1017, 596) in boxes  # the password field the shop shows after the fill


# ---- PHONEFIX-121 follow-up 6: the plan (R3 and the invariants), tests pinned to the invariants ---------------------------------------

# I2, money: an approved input ends the grant unless it is a plain step; a plain step goes through the step count and the next-screen look

ICON_CHECKOUT = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node('android.widget.ImageButton', rid=PKG + ':id/fab', bounds=(900, 2200, 1017, 2300)))
FAILED = screen_xml(node('android.widget.TextView', text='Payment failed. Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                    node(text='Pay £12.40', bounds=(63, 2000, 1017, 2126)))
NO_TOTAL_CONFIRM = screen_xml(node('android.widget.TextView', text='Subtotal £10.00', clickable=False, bounds=(63, 500, 1017, 600)),
                              node('android.widget.TextView', text='Delivery £2.40', clickable=False, bounds=(63, 600, 1017, 700)),
                              node(text='Confirm', bounds=(63, 2000, 1017, 2126)))  # two amounts and no total: the step asks
NO_TOTAL_CONFIRM_AND_ORDER = screen_xml(node('android.widget.TextView', text='Subtotal £10.00', clickable=False, bounds=(63, 500, 1017, 600)),
                                        node('android.widget.TextView', text='Delivery £2.40', clickable=False, bounds=(63, 600, 1017, 700)),
                                        node(text='Confirm', bounds=(63, 1800, 1017, 1926)), node(text='Place order', bounds=(63, 2000, 1017, 2126)))


def test_r3_2_an_approved_icon_only_order_does_not_leave_the_grant_for_a_second_order(phone):
    """The checkout's order button is an icon (`fab`, no words): unlabeled, so it asks, and the owner approves the order. v5 kept the
    card's grant after any approved tap with no pay word on it (R2-9). The shop answers "Payment failed" with a "Pay £12.40" button:
    the retry, a second order, was covered with no question."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    world.xml = ICON_CHECKOUT
    thread, seen = owner_answers(approvals, yes=True, times=1)  # the owner approves the icon tap: it is the order
    first = press(tools, world, 'fab', then=FAILED)
    thread.join()
    assert first['approved_by_owner'] is True and len(seen) == 1 and len(taps(world)) == 1
    owner = Owner(approvals)  # from here every question is answered No
    second = press(tools, world, 'Pay £12.40')
    owner.stop()
    assert second.get('error') == 'denied' and len(taps(world)) == 1, 'a second order ran on the card yes after an approved icon order'


def test_fu6_an_icon_whose_id_holds_a_commit_word_is_not_a_step(phone):
    """`_is_step` reads the words a person reads on the control, never its id: an icon called order_icon is no "step"."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, yes=True, times=1)
    world.xml = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node('android.widget.ImageButton', rid=PKG + ':id/order_icon', bounds=(900, 2200, 1017, 2300)))
    assert press(tools, world, 'order icon', then=FAILED)['approved_by_owner'] is True
    thread.join()
    owner = Owner(approvals)
    assert press(tools, world, 'Pay £12.40').get('error') == 'denied'
    owner.stop()


def test_fu6_an_approved_step_that_leads_to_order_placed_ends_the_grant(phone):
    """The review page's "Confirm" asks (the total can't be read) and the owner approves: it places the order. The page after it is
    "Order placed": a covered step would have ended the yes there, so an approved one does too."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, yes=True, times=1)
    world.xml = NO_TOTAL_CONFIRM
    assert press(tools, world, 'Confirm', then=PLACED)['approved_by_owner'] is True
    thread.join()
    owner = Owner(approvals)
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'
    owner.stop()


def test_fu6_an_approved_step_keeps_the_grant_while_the_order_button_is_ahead(phone):
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, yes=True, times=1)
    world.xml = NO_TOTAL_CONFIRM
    assert press(tools, world, 'Confirm', then=REVIEW)['approved_by_owner'] is True
    order = press(tools, world, 'Place order')  # the card's yes still stands: the order button was ahead
    thread.join()
    assert order.get('approved_by_owner') is True and len(seen) == 1


def test_fu6_an_approved_step_on_a_screen_that_already_shows_the_order_button_uses_the_yes_up(phone):
    tools, approvals, world, api = phone
    card_filled(tools, world)
    thread, seen = owner_answers(approvals, yes=[True, False], times=2)
    world.xml = NO_TOTAL_CONFIRM_AND_ORDER
    assert press(tools, world, 'Confirm', then=REVIEW)['approved_by_owner'] is True  # the "step" may be the order
    world.xml = REVIEW
    second = press(tools, world, 'Place order')
    thread.join()
    assert second.get('error') == 'denied' and len(seen) == 2


def test_fu6_a_covered_step_on_a_screen_that_shows_the_order_button_uses_the_yes_up(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = REVIEW_WITH_STEP  # Continue and Place order on the same page
    assert press(tools, world, 'Continue')['approved_by_owner'] is True  # covered, once
    assert press(tools, world, 'Place order').get('error') == 'denied'
    assert len(owner.stop()) == 1


def test_fu6_one_yes_pays_for_at_most_two_orders_even_when_a_step_button_orders(phone):
    """The owner's two accepted limits ("Buy again" on a confirmation, a step-word button that orders) with the tightening: a "Confirm"
    that orders, then "Buy again" on its confirmation, are the two; a third asks."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    world.xml = CONFIRM_STEP
    again = screen_xml(node('android.widget.TextView', text='Order placed · total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                       node(text='Buy again £12.40', bounds=(63, 2000, 1017, 2126)))
    assert press(tools, world, 'Confirm', then=again)['approved_by_owner'] is True  # order 1
    assert press(tools, world, 'Buy again £12.40')['approved_by_owner'] is True  # order 2 (the named limit)
    assert press(tools, world, 'Buy again £12.40').get('error') == 'denied'  # order 3 asks
    assert len(owner.stop()) == 1 and len(taps(world)) == 2


# I4, asking: an input whose target or effect the tool can't read asks, except a vertical scroll

def test_r2_1_a_scrolled_back_chrome_page_with_only_the_bar_asks_by_coordinate_and_enter_but_not_a_named_button(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.package = CHROME
    world.xml = fixture('chrome_scrolled_back_bar_only.xml')
    assert run(tools, action='tap', coordinate=[540, 2000], purpose='place the order').get('error') == 'denied'
    assert run(tools, task='task-2', action='key', key='enter', purpose='send it').get('error') == 'denied'  # no focused node to read
    summary = capture(tools)
    button = run(tools, task='task-3', action='tap', element=element(summary, 'New tab'), purpose='open a tab')
    seen = owner.stop()
    assert button.get('success') is True and len(seen) == 2  # a named toolbar button is no question


def test_r2_1_the_empty_page_does_not_make_the_screen_unreadable(phone):
    """The hollow-WebView rule is gone: what the tree can't read is asked about by the I4 rules, not by a screen-wide flag."""
    tools = phone[0]
    placeholder = node('android.widget.FrameLayout', desc='Web View', clickable=False, bounds=(0, 63, 1080, 2337), package=CHROME)
    assert tools.Screen(screen_xml(placeholder, package=CHROME), package=CHROME).readable is True
    assert tools.Screen(_webview_page('shop-a.example'), package=CHROME).readable is True
    assert not hasattr(tools, '_hollow_page')


def test_r3_4_an_empty_webview_beside_ordinary_controls_does_not_make_add_to_cart_ask(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)  # answers No to any question
    world.xml = screen_xml(node('android.widget.TextView', text='Rice crackers', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Add to cart', bounds=(63, 1500, 1017, 1626)),
                           node('android.webkit.WebView', clickable=False, bounds=(0, 1800, 1080, 2100)))  # a promo slot, nothing exposed
    result = press(tools, world, 'Add to cart')
    owner.stop()
    assert result.get('success') is True and result['approved_by_owner'] is False, 'an ordinary tap asked the owner'


def test_i4_enter_with_no_readable_focused_node_asks_on_any_screen(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)))  # no focus, no payment
    assert run(tools, action='key', key='enter', purpose='go').get('error') == 'denied'
    seen = owner.stop()
    assert len(seen) == 1 and seen[0]['risk'] == 'unlabeled' and 'enter' not in json.dumps(taps(world)) and not world.inputs


def test_i4_enter_in_a_labeled_field_does_not_ask(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.EditText', hint='Search', focused=True, bounds=(63, 344, 1017, 470)))
    assert run(tools, action='key', key='enter', purpose='search')['success'] is True
    assert owner.stop() == []


def test_i4_a_sideways_swipe_that_starts_on_no_labeled_control_asks_and_a_scroll_does_not(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.TextView', text='Carousel', clickable=False, bounds=(63, 500, 1017, 600)))
    assert run(tools, action='swipe', direction='up', purpose='scroll')['success'] is True  # a vertical scroll never asks
    assert run(tools, action='swipe', direction='down', purpose='scroll')['success'] is True
    assert run(tools, action='swipe', direction='left', purpose='next slide').get('error') == 'denied'
    seen = owner.stop()
    assert len(seen) == 1 and seen[0]['risk'] == 'unlabeled'


def test_i4_a_sideways_swipe_on_a_labeled_control_does_not_ask(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node(text='Slide to see more', bounds=(63, 900, 1017, 1026), clickable=True))
    summary = capture(tools)
    assert run(tools, action='swipe', direction='left', element=element(summary, 'Slide to see more'), purpose='more')['success'] is True
    assert owner.stop() == []


def test_i4_a_coordinate_tap_that_lands_on_a_scrollable_container_counts_as_no_labeled_control(phone):
    """A WebView named by its page title, or a list, holds the finger: what is under it may be an order button the tree does not expose."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    page = node('android.webkit.WebView', text='Kanto Foods menu', clickable=True, bounds=(0, 210, 1080, 2339), scrollable=True)
    world.xml = screen_xml(page)
    assert run(tools, action='tap', coordinate=[540, 2000], purpose='place the order').get('error') == 'denied'
    seen = owner.stop()
    assert len(seen) == 1 and seen[0]['risk'] == 'unlabeled' and taps(world) == []


# I3, reading: a tentative read moves no watch; every window of the screen is read while a fill is live

CODE_PAGE = _root(PKG, node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                  node('android.widget.EditText', text='482913', hint='Code', bounds=(63, 600, 1017, 726)),
                  node(text='Verify', bounds=(63, 900, 1017, 1026)))
STATUS_BAR = _root('com.android.systemui', node('android.widget.TextView', text='12:30', clickable=False, bounds=(40, 10, 160, 60),
                                                package='com.android.systemui'))


def _code_capture(tools, world, monkeypatch, windows_xml, plain=None):
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    # The helper filled a code it read itself (code_filled); its reported field could not be placed (the keyboard moved the screen), so
    # every text field of the app is watched by digest: the watch is the only thing that masks the code.
    tools._masks_for('default').watch_fields(PKG, [{'bounds': [0, 0, 10, 10]}])
    world.xml = plain or _plain(CODE_PAGE)
    world.windows_xml = windows_xml
    return run(tools, action='capture')


def test_r3_1_control_a_screen_with_one_window_keeps_the_watched_code_masked(phone, monkeypatch):
    tools, approvals, world, api = phone
    result = _code_capture(tools, world, monkeypatch, None)
    assert result.get('_multimodal') is True and '482913' not in result['text_summary']


def test_r3_1_the_status_bar_in_the_windows_list_does_not_unmask_the_watched_code(phone, monkeypatch):
    """`uiautomator dump --windows` always lists the status bar (measured: shop_windows_after_fill.xml). v5 ran the read guard on it
    before it built the screen whose text goes to the model, and `filled_nodes` ended every watch of another package."""
    tools, approvals, world, api = phone
    measured = fixture('shop_windows_after_fill.xml')
    bar = next(w for w in tools.parse_windows(measured) if w['package'] == 'com.android.systemui')['xml']
    bar_root = bar.split('>', 1)[1].rsplit('</hierarchy>', 1)[0]
    result = _code_capture(tools, world, monkeypatch, _windows_dump((False, bar_root), (True, CODE_PAGE)))
    assert result.get('_multimodal') is True
    assert '482913' not in result['text_summary'], 'the code the helper filled went to the model as text'
    again = run(tools, action='capture')  # and the watch is still there for the next capture
    assert '482913' not in again['text_summary']


def test_i3_a_watched_code_field_in_a_window_behind_the_active_one_is_boxed_in_the_picture(phone, monkeypatch):
    """The form that holds the watched code sits in a window behind a sheet: no tree of the active window places the watch there, so
    every text field of a watched app's other windows is boxed."""
    tools, approvals, world, api = phone
    sheet = _root(PKG, node('android.widget.TextView', text='Save this card for next time?', clickable=False, bounds=(63, 1650, 1017, 1800)),
                  node(text='Not now', bounds=(63, 2200, 520, 2330)))
    result = _code_capture(tools, world, monkeypatch, _windows_dump((True, sheet), (False, CODE_PAGE)), plain=_plain(sheet))
    assert result.get('_multimodal') is True
    shown = _shown_picture(result)
    assert shown.getpixel((shown.width // 2, int(660 * shown.height / 2400))) < 20, 'the watched code field behind the sheet is in the picture'


def test_i3_a_capture_that_is_refused_as_moving_does_not_end_the_watch(phone, monkeypatch):
    """The second read lacks the code field (the screen moved): the capture is refused. v5's second read ended the watch, so the next
    capture, with the field back, showed the code."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').watch_fields(PKG, [{'bounds': [0, 0, 10, 10]}])
    moved = _plain(_root(PKG, node('android.widget.TextView', text='Loading', clickable=False, bounds=(63, 400, 1017, 500)),
                         node('android.widget.TextView', text='Please wait', clickable=False, bounds=(63, 600, 1017, 700))))
    world.xml = _plain(CODE_PAGE)
    world.after_dumps = {2: moved}  # the first read has the code page, the second read has moved on
    assert run(tools, action='capture').get('error') == 'moving'
    world.after_dumps = {}
    world.xml = _plain(CODE_PAGE)  # the code page is back
    assert '482913' not in run(tools, action='capture')['text_summary'], 'a refused capture ended the watch'


def test_r3_6_a_windows_dump_that_cannot_be_read_does_not_show_a_filled_form_behind_a_sheet(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('4242424242424242', PKG)
    sheet_root, _ = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    world.windows_xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?><displays><display id=\"0\"><window index=\"0\" active"  # cut
    result = run(tools, action='capture')
    assert result.get('error') == 'masked' and not result.get('_multimodal')


def test_i3_a_window_with_no_tree_refuses_the_capture_while_a_fill_is_live(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Filled-Value-1', PKG)
    sheet_root, _ = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    no_tree = ('<window index="1" title="" bounds="[0,0][1080,2400]" active="false" focused="false" id="21" layer="1" '
               'type="TYPE_APPLICATION"></window>')
    no_nodes = ('<window index="2" title="" bounds="[0,0][1080,2400]" active="false" focused="false" id="22" layer="2" '
                'type="TYPE_APPLICATION"><hierarchy rotation="0"></hierarchy></window>')
    for extra in (no_tree, no_nodes):
        world.windows_xml = _windows_dump((True, sheet_root)).replace('</display>', extra + '</display>')
        assert run(tools, action='capture').get('error') == 'masked'


def test_i3_a_window_list_with_no_active_window_refuses_the_capture_while_a_fill_is_live(phone, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Filled-Value-1', PKG)
    sheet_root, _ = _sheet_over_a_card_form()
    world.xml = _plain(sheet_root)
    world.windows_xml = _windows_dump((False, sheet_root))
    assert run(tools, action='capture').get('error') == 'masked'


def test_i3_a_phone_that_answers_the_plain_tree_is_refused_while_a_fill_is_live(phone, monkeypatch):
    """v5 fell back to the active window's tree for "a phone with no --windows"; the agent phone has it (measured), so the fallback is
    gone: a filled screen is never shown on the guess that nothing is behind or beside the active window."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Filled-Value-1', PKG)
    world.xml = screen_xml(node('android.widget.TextView', text='Menu', clickable=False, bounds=(63, 500, 1017, 600)))
    world.windows_plain = True
    assert run(tools, action='capture').get('error') == 'masked'
    tools._masks.clear()  # with no fill on record nothing needs the window list
    assert run(tools, action='capture').get('_multimodal') is True


def test_i3_the_keyboards_sparse_window_never_refuses_a_capture(phone, monkeypatch):
    """The measured keyboard window has two named nodes and the status bar five: "sparse" is not "unreadable"."""
    from test_agent_phone_redact import load as load_redact
    tools, approvals, world, api = phone
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').register('Beacon-Filled-1', PKG)
    measured = fixture('shop_windows_after_fill.xml')
    shop = next(w for w in tools.parse_windows(measured) if w['active'])
    world.xml = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" + shop['xml']
    world.windows_xml = measured
    result = run(tools, action='capture')
    assert result.get('_multimodal') is True, result


def test_fu6_the_real_home_screen_and_app_drawer_are_never_taken_for_the_overview(phone):
    """Measured on the agent phone (fixtures/agent_phone/launcher_home.xml, launcher_drawer.xml, 19 Nov): neither holds `overview_panel`.
    The home screen does hold `overview_actions_view`, which is why the refusal keys on the panel and on nothing looser."""
    tools, approvals, world, api = phone
    world.package = 'com.google.android.apps.nexuslauncher'
    assert ':id/overview_actions_view' in fixture('launcher_home.xml') and ':id/overview_panel' not in fixture('launcher_home.xml')
    for name in ('launcher_home.xml', 'launcher_drawer.xml'):
        world.xml = fixture(name)
        assert run(tools, action='capture').get('_multimodal') is True, name
    tools._masks_for('default').register('Filled-Value-1', PKG)  # and while a fill is on record
    for name in ('launcher_home.xml', 'launcher_drawer.xml'):
        world.xml = fixture(name)
        assert run(tools, action='capture').get('_multimodal') is True, name


# ---- PHONEFIX-121 follow-up 7 (REVIEW-FIX-PHONE-121 R4): the reviewer's proofs, ported ---------------------------------------------------

R4_CODE_PAGE = _root(PKG, node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                     node('android.widget.EditText', text='482913', hint='Code', rid=PKG + ':id/code', bounds=(63, 600, 1017, 726)),
                     node(text='Verify', bounds=(63, 900, 1017, 1026)))
R4_SHEET = _root(PKG, node('android.widget.TextView', text='Checking the code', clickable=False, bounds=(63, 1700, 1017, 1800)),
                 node(text='Cancel', bounds=(63, 2200, 1017, 2330)))


def test_r4_1_a_same_app_sheet_over_the_code_page_does_not_end_the_code_watch(phone, monkeypatch):
    """The helper filled a code it read itself into the shop's code field (`watch_fields`, with the field's view id). Before the agent's
    first capture the shop shows a sheet of its own (a "Checking the code" dialog: the same package, no text field). That capture is
    shown, so the watch is committed against the active window, the sheet: `_place` finds no field there, and v6 ended the watch on
    that. The sheet closes on a failed check; the next capture of the code page showed the code as text."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').watch_fields(PKG, [{'view_id': PKG + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    world.xml = _plain(R4_SHEET)
    world.windows_xml = _windows_dump((False, R4_CODE_PAGE), (True, R4_SHEET))
    first = run(tools, action='capture')
    assert first.get('_multimodal') is True and '482913' not in first['text_summary']  # the sheet is shown; the code page is behind it
    world.xml = _plain(R4_CODE_PAGE)
    world.windows_xml = _windows_dump((True, R4_CODE_PAGE))  # the sheet closed: "That code didn't work"
    second = run(tools, action='capture')
    assert second.get('_multimodal') is True
    assert '482913' not in second['text_summary'], 'the code the helper filled went to the model as text'


def test_r4_2_going_back_to_the_step_screen_does_not_let_one_yes_pay_a_third_order(phone):
    """§5c item 2 and I2 promise at most two orders per yes. The tightening looks only at the screen of the step: a "Confirm" that
    really orders sits on a screen with no pay-word control, so it is a step, and the confirmation that follows shows "Buy again", so
    the grant is kept. Back (never gated) returns to the "Confirm" screen, and the same tap was a step again, up to GRANT_STEPS (3);
    then "Buy again" is the order tap. One yes, four orders."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)  # answers No to any question
    card_filled(tools, world)
    confirm = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                         node(text='Confirm', bounds=(63, 2000, 1017, 2126)))
    placed = screen_xml(node('android.widget.TextView', text='Order placed, total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                        node(text='Buy again', bounds=(63, 2000, 1017, 2126)))
    covered = 0
    for _ in range(3):
        world.xml = confirm  # the agent pressed Back to the "Confirm" screen
        if press(tools, world, 'Confirm', then=placed).get('approved_by_owner') is True and not owner.seen:
            covered += 1
    world.xml = placed  # the agent reaches the confirmation (the reviewer's proof left the screen where the last tap put it; a Confirm that
    last = press(tools, world, 'Buy again')  # asks no longer moves it, so the screen is set here)
    owner.stop()
    orders = covered + (1 if last.get('success') else 0)
    assert orders <= 2, f'one card yes paid for {orders} orders ({len(taps(world))} taps ran)'


def test_fu7_the_second_step_of_one_yes_asks_whatever_the_screen(phone):
    """The grant keeps one step (GRANT_STEPS = 1): the same "Confirm" tapped again, after Back, is a question, and the card's yes is not
    what answers it."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    confirm = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                         node(text='Confirm', bounds=(63, 2000, 1017, 2126)))
    placed = screen_xml(node('android.widget.TextView', text='Order placed, total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                        node(text='Buy again', bounds=(63, 2000, 1017, 2126)))
    world.xml = confirm
    assert press(tools, world, 'Confirm', then=placed)['approved_by_owner'] is True and not owner.seen  # the one covered step
    world.xml = confirm  # Back
    assert press(tools, world, 'Confirm', then=placed).get('error') == 'denied'  # the second asks
    assert len(owner.stop()) == 1


def test_fu7_a_straight_checkout_still_runs_on_one_yes(phone):
    """Nothing is lost on the straight path: a step on the page before the order button keeps the yes (the order button is ahead), and
    the order button then runs on it."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    cart = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                      node(text='Continue', bounds=(63, 2000, 1017, 2126)))
    world.xml = cart
    assert press(tools, world, 'Continue', then=REVIEW)['approved_by_owner'] is True
    assert press(tools, world, 'Place order')['success'] is True
    assert owner.stop() == [] and len(taps(world)) == 2


def test_r4_check_i2_one_yes_pays_for_at_most_two_orders(phone):
    """§5c item 2: a step that orders ("Confirm"), then "Buy again" on the next screen, is two orders on one yes; a third asks."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    card_filled(tools, world)
    confirm = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                         node(text='Confirm', bounds=(63, 2000, 1017, 2126)))
    placed = screen_xml(node('android.widget.TextView', text='Order placed, total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                        node(text='Buy again', bounds=(63, 2000, 1017, 2126)))
    world.xml = confirm
    assert press(tools, world, 'Confirm', then=placed)['approved_by_owner'] is True  # order 1 (a step-word button that orders)
    assert press(tools, world, 'Buy again', then=placed)['approved_by_owner'] is True  # order 2 (Buy again)
    assert press(tools, world, 'Buy again').get('error') == 'denied'  # a third asks
    assert len(owner.stop()) == 1 and len(taps(world)) == 2


def test_r4_check_limit_6_an_icon_only_step_costs_two_more_questions(phone):
    """§5c item 6: the icon asks, and then the order asks too."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    world.xml = screen_xml(node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node('android.widget.ImageButton', rid=PKG + ':id/fab', bounds=(900, 2200, 1017, 2300)))
    thread, seen = owner_answers(approvals, yes=True, times=1)
    assert press(tools, world, 'fab', then=REVIEW)['approved_by_owner'] is True
    thread.join()
    owner = Owner(approvals)
    assert press(tools, world, 'Place order').get('error') == 'denied'  # the order asks again
    assert len(seen) == 1 and len(owner.stop()) == 1


def test_r4_check_limit_7_an_unknown_order_word_runs_without_asking(phone):
    """§5c item 7, read against the code: "Get it now" on a checkout is no payment word, no commit or submit word: it runs unasked."""
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = screen_xml(node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600)),
                           node(text='Get it now', bounds=(63, 2000, 1017, 2126)))
    result = press(tools, world, 'Get it now')
    assert owner.stop() == [] and result.get('success') is True and result['approved_by_owner'] is False


CAROUSEL_PAGE = screen_xml(node('android.widget.TextView', text='Kanto Foods', clickable=False, bounds=(63, 500, 1017, 600)),
                           node('androidx.recyclerview.widget.RecyclerView', desc='Featured products', clickable=False, scrollable=True,
                                bounds=(0, 800, 1080, 1200)),
                           node(text='Rice crackers', bounds=(40, 820, 500, 1180)), node(text='Green tea', bounds=(580, 820, 1040, 1180)))


def test_r4_3_a_sideways_swipe_on_a_named_carousel_asks_and_the_owners_yes_ends_the_card_yes(phone):
    """R4-3, kept as v6 has it for 1.2.1 (the native-scroll exemption is after 1.2.1; §5c item 17 says so): a left swipe on a named,
    scrollable carousel is a sideways swipe on a control that is no labeled control, so it asks; mid-purchase the owner's yes to it is no
    step, so it uses the card's yes up, and the order asks again. The reviewer's proof asserts the opposite and stays out of the suite."""
    tools, approvals, world, api = phone
    card_filled(tools, world)
    world.xml = CAROUSEL_PAGE
    number = element(capture(tools), 'Featured products')
    thread, seen = owner_answers(approvals, yes=True, times=1)
    swipe = run(tools, action='swipe', element=number, direction='left', purpose='see more products')
    thread.join()
    assert swipe.get('success') is True and swipe['approved_by_owner'] is True and len(seen) == 1
    owner = Owner(approvals)
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'  # the card's yes ended with the swipe's question
    assert len(owner.stop()) == 1


def test_r4_3_the_owners_no_to_a_sideways_swipe_ends_the_card_yes_too(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)  # answers No to any question
    card_filled(tools, world)
    world.xml = CAROUSEL_PAGE
    number = element(capture(tools), 'Featured products')
    assert run(tools, action='swipe', element=number, direction='left', purpose='see more products').get('error') == 'denied'
    world.xml = REVIEW
    assert press(tools, world, 'Place order').get('error') == 'denied'  # not run on the card's yes
    assert len(owner.stop()) == 1 and taps(world) == []


def test_r4_3_a_vertical_scroll_of_the_same_carousel_never_asks(phone):
    tools, approvals, world, api = phone
    owner = Owner(approvals)
    world.xml = CAROUSEL_PAGE
    up = run(tools, action='swipe', element=element(capture(tools), 'Featured products'), direction='up', purpose='scroll the list')
    assert up.get('success') is True and owner.stop() == []


def test_fu8_a_digest_watched_code_survives_a_later_sheet_of_the_same_app(phone, monkeypatch):
    """Probe (b) of follow-up 7, through the tool: the helper's reported field could not be placed, so the watch is by digest. A sheet of
    the same app (no text field) is shown, then the code page comes back: the code must not reach the model as text."""
    tools, approvals, world, api = phone
    first = _code_capture(tools, world, monkeypatch, None)
    assert first.get('_multimodal') is True and '482913' not in first['text_summary']
    world.xml = _plain(R4_SHEET)
    world.windows_xml = _windows_dump((False, CODE_PAGE), (True, R4_SHEET))
    assert run(tools, action='capture').get('_multimodal') is True  # the sheet is shown
    world.xml = _plain(CODE_PAGE)
    world.windows_xml = _windows_dump((True, CODE_PAGE))  # the sheet closed
    back = run(tools, action='capture')
    assert back.get('_multimodal') is True and '482913' not in back['text_summary'], 'the code the helper filled went to the model as text'


SHEET_WITH_A_FIELD = _root(PKG, node('android.widget.TextView', text='Add a note', clickable=False, bounds=(63, 1700, 1017, 1800)),
                           node(text='Cancel', bounds=(63, 1800, 1017, 1850)),
                           node('android.widget.EditText', text='hello', hint='Note', rid=PKG + ':id/note', bounds=(63, 1850, 1017, 1950)))


def test_fu9_a_sheet_with_a_text_field_of_its_own_does_not_leave_the_code_page_unwatched(phone, monkeypatch):
    """The sibling probe of follow-up 8, through the tool: the first shown capture after the helper's fill is a sheet of the same app that
    has a text field of its own (the watch is by digest, since the reported field can't be placed). The code page that comes back must
    not show the code as text."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').watch_fields(PKG, [{'bounds': [0, 0, 10, 10]}])
    world.xml = _plain(SHEET_WITH_A_FIELD)
    world.windows_xml = _windows_dump((True, SHEET_WITH_A_FIELD), (False, CODE_PAGE))
    sheet = run(tools, action='capture')
    assert sheet.get('_multimodal') is True and 'hello' not in sheet['text_summary']
    world.xml = _plain(CODE_PAGE)
    world.windows_xml = _windows_dump((True, CODE_PAGE))
    back = run(tools, action='capture')
    assert back.get('_multimodal') is True and '482913' not in back['text_summary'], 'the code the helper filled went to the model as text'


# ---- PHONEFIX-121 follow-up 10 (REVIEW-FIX-PHONE-121 R5): the reviewer's proofs, ported ---------------------------------------------------

def test_r5_1_a_same_app_sheet_with_one_box_at_the_codes_position_does_not_end_the_code_watch(phone, monkeypatch):
    """R4-1's shape, one step on. The helper reports the code field's view id and bounds. The first shown capture is a sheet of the same app
    whose only text box sits under those bounds: `_place` finds no view id, took that box by position (`placed: True`), and `watch_all` added
    no other box, so the watch had no digest field and v9's extension never applied. The sheet's box is at another tree path than the code
    field; when the sheet closed, that path held a button, the placed field was "gone", and the watch ended."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').watch_fields(PKG, [{'view_id': PKG + ':id/code', 'bounds': [63, 600, 1017, 726]}])
    code_page = _root(PKG, node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                      node('android.widget.EditText', text='482913', hint='Code', rid=PKG + ':id/code', bounds=(63, 600, 1017, 726)),
                      node(text='Verify', bounds=(63, 900, 1017, 1026)))
    sheet = _root(PKG, node('android.widget.TextView', text='Trust this phone?', clickable=False, bounds=(63, 300, 1017, 380)),
                  node('android.widget.TextView', text='Give it a name', clickable=False, bounds=(63, 400, 1017, 480)),
                  node('android.widget.EditText', hint='Phone name', bounds=(63, 560, 1017, 760)),  # its only box, over the code field's centre
                  node(text='Save', bounds=(63, 2200, 1017, 2330)))
    world.xml = _plain(sheet)
    world.windows_xml = _windows_dump((False, code_page), (True, sheet))
    first = run(tools, action='capture')
    assert first.get('_multimodal') is True and '482913' not in first['text_summary']
    world.xml = _plain(code_page)
    world.windows_xml = _windows_dump((True, code_page))  # the sheet closed
    second = run(tools, action='capture')
    assert second.get('_multimodal') is True
    assert '482913' not in second['text_summary'], 'the code the helper filled went to the model as text'


R5_CODE_PAGE = screen_xml(node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                          node('android.widget.EditText', text='482913', hint='Code', bounds=(63, 600, 1017, 726)),
                          node('android.widget.EditText', hint='Phone number', bounds=(63, 760, 1017, 886)),
                          node(text='Verify', bounds=(63, 900, 1017, 1026)))


def test_r5_2_eight_later_code_fills_do_not_unmask_a_code_still_on_screen(phone, monkeypatch):
    """A digest field is never registered by value: only its watch masked it, and `watch_fields` keeps the newest MAX_WATCHES (8), so the
    ninth code fill in one phone session evicted the oldest watch, and if that app's code page was still up the code was shown. The app is
    now a code app, kept outside the cap."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    redact = load_redact()
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', redact)
    tools._masks.clear()
    masks = tools._masks_for('default')
    masks.watch_fields(PKG, [{'bounds': [0, 0, 10, 10]}])  # by digest
    world.xml = R5_CODE_PAGE
    assert '482913' not in str(capture(tools))  # masked by the watch
    for i in range(redact.MAX_WATCHES):  # eight later code fills in other apps, same phone session
        masks.watch_fields(f'dev.example.app{i}', [{'bounds': [0, 0, 10, 10]}])
    assert '482913' not in str(capture(tools)), 'an evicted watch left its code on screen unmasked'


def test_r5_check_a_digest_watch_in_chrome_hides_the_address_bar_but_never_the_host_check(phone, monkeypatch):
    """A code filled by position in Chrome starts a digest watch: every Chrome text field is masked until the stop, the address bar
    included (it is an EditText). The masks change only what the model is shown; the web grant reads the host from the raw tree, so a
    covered web order is still covered: no extra question, nothing unmasked."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    chrome = 'com.android.chrome'
    owner = Owner(approvals)  # answers No to any question

    def site(host_name, *nodes):
        bar = node('android.widget.EditText', text=host_name, rid=chrome + ':id/url_bar', bounds=(150, 100, 900, 200), package=chrome)
        return screen_xml(bar, *nodes, package=chrome, title='Checkout')

    world.package = chrome
    tools._masks_for('default').watch_fields(chrome, [{'bounds': [0, 0, 10, 10]}])  # the helper's field could not be placed: by digest
    world.xml = site('shop-a.example', node('android.widget.EditText', text='482913', hint='Code', bounds=(63, 600, 1017, 726), package=chrome),
                     node(text='Verify', bounds=(63, 900, 1017, 1026), package=chrome))
    code_page = capture(tools)
    assert '482913' not in str(code_page) and 'shop-a.example' not in str(code_page)  # code and address bar both hidden

    def fill(handle, session, driver, *, task_id='default', owner=False, shop=None, amount=None, pin_approved=False, session_id=None):
        world.vault.built_with['on_card_approved'](package=chrome, handle=handle, amount=amount, certs=[], task=task_id,
                                                   session=session_id, web_domain='shop-a.example')
        return {'success': True, 'filled_fields': 4, 'kind': 'payment', 'app': shop}
    world.vault.fill = fill
    world.xml = site('shop-a.example', node('android.widget.EditText', hint='Card number', focused=True, bounds=(63, 344, 1017, 470), package=chrome),
                     node('android.widget.TextView', text='Total £12.40', clickable=False, bounds=(63, 500, 1017, 600), package=chrome))
    assert run(tools, action='fill', handle='vault_card')['success'] is True
    world.xml = site('shop-a.example',
                     node('android.widget.TextView', text='Order total £12.40', clickable=False, bounds=(63, 500, 1017, 600), package=chrome),
                     node(text='Place order', bounds=(63, 2000, 1017, 2126), package=chrome))
    review = capture(tools)
    assert 'shop-a.example' not in str(review) and '••••' in str(review)  # the agent can't read the address bar any more
    order = press(tools, world, 'Place order')
    assert owner.stop() == [] and order.get('approved_by_owner') is True  # the host check still reads the raw tree: covered, one yes


# ---- PHONEFIX-121 follow-up 11 (REVIEW-FIX-PHONE-121 R6): the reviewer's proofs, ported -----------------------------------------------------

R6_CODE_ID = PKG + ':id/code'
R6_SHEET_NODES = {
    # an input layout the app reuses (same view id) on a "name this phone" sheet, away from the code field's place
    'a text box with the same view id': lambda: [node('android.widget.TextView', text='Trust this phone?', clickable=False, bounds=(63, 300, 1017, 380)),
                                                 node('android.widget.TextView', text='Give it a name', clickable=False, bounds=(63, 400, 1017, 480)),
                                                 node('android.widget.EditText', hint='Phone name', rid=R6_CODE_ID, bounds=(63, 1500, 1017, 1600))],
    # `named` is not limited to text fields (the docstring says "only a text field ever counts")
    'a label with the same view id': lambda: [node('android.widget.TextView', text='Checking the code', rid=R6_CODE_ID, clickable=False,
                                                   bounds=(63, 1700, 1017, 1800))],
}


@pytest.mark.parametrize('kind', sorted(R6_SHEET_NODES))
def test_r6_1_a_same_app_sheet_with_the_code_fields_view_id_does_not_end_the_code_watch(phone, monkeypatch, kind):
    """The helper names the code field by its view id, so v10 made no code app. The first shown capture is a sheet of the same app that has
    a node with that view id: `_place` took it by the view id (`placed: True`). The sheet closes; at that node's tree path the code page
    holds another class, the placed field is "gone", the watch ends, and the code (never registered by value: the sheet's node was) went to
    the model as text. Every helper code fill now makes its app a code app."""
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    tools._masks_for('default').watch_fields(PKG, [{'view_id': R6_CODE_ID, 'bounds': [63, 600, 1017, 726]}])
    code_page = _root(PKG, node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                      node('android.widget.EditText', text='482913', hint='Code', rid=R6_CODE_ID, bounds=(63, 600, 1017, 726)),
                      node(text='Verify', bounds=(63, 900, 1017, 1026)))
    sheet = _root(PKG, *R6_SHEET_NODES[kind](), node(text='Save', bounds=(63, 2200, 1017, 2330)))
    world.xml = _plain(sheet)
    world.windows_xml = _windows_dump((False, code_page), (True, sheet))
    first = run(tools, action='capture')
    assert first.get('_multimodal') is True and '482913' not in first['text_summary']
    world.xml = _plain(code_page)
    world.windows_xml = _windows_dump((True, code_page))  # the sheet closed
    second = run(tools, action='capture')
    assert second.get('_multimodal') is True
    assert '482913' not in second['text_summary'], 'the code the helper filled went to the model as text'


# ---- PHONEFIX-121 follow-up 12 (REVIEW-FIX-PHONE-121 R7-4): a code drawn one digit per view, in the picture -----------------------------------

R7_CELLS = [node('android.widget.TextView', text=d, clickable=False, bounds=(63 + 150 * i, 600, 183 + 150 * i, 726)) for i, d in enumerate('482913')]
R7_OTP_PAGE = _root(PKG, node('android.widget.TextView', text='Enter the code we sent', clickable=False, bounds=(63, 400, 1017, 500)),
                    node('android.widget.EditText', text='482913', hint='Code', rid=PKG + ':id/code', bounds=(63, 600, 1017, 726)), *R7_CELLS)


def test_r7_4_a_code_drawn_one_digit_per_view_is_hidden_in_the_text_and_the_picture(phone, monkeypatch):
    tools, approvals, world, api = phone
    result = _code_capture(tools, world, monkeypatch, None, plain=_plain(R7_OTP_PAGE))
    assert result.get('_multimodal') is True
    assert not [d for d in '482913' if f'"{d}"' in result['text_summary']], result['text_summary']
    shown = _shown_picture(result)
    for i in range(6):  # the middle of every digit cell is black in the picture
        x, y = 63 + 150 * i + 60, 663
        assert shown.getpixel((int(x * shown.width / 1080), int(y * shown.height / 2400))) < 20, f'cell {i} is in the picture'


def test_r7_4_the_digit_views_of_a_window_behind_a_sheet_are_boxed_in_the_picture(phone, monkeypatch):
    tools, approvals, world, api = phone
    sheet = _root(PKG, node('android.widget.TextView', text='Save this card for next time?', clickable=False, bounds=(63, 1650, 1017, 1800)),
                  node(text='Not now', bounds=(63, 2200, 520, 2330)))
    result = _code_capture(tools, world, monkeypatch, _windows_dump((True, sheet), (False, R7_OTP_PAGE)), plain=_plain(sheet))
    shown = _shown_picture(result)
    assert shown.getpixel((int(123 * shown.width / 1080), int(663 * shown.height / 2400))) < 20, 'the first digit cell behind the sheet is in the picture'


# ---- PHONEFIX-121 follow-up 14 (FINAL-121 B8-1): the save-card sheet over the filled card form, with the phone's own dumps -------------------
# fixtures/agent_phone/sheet_over_form_*, form_only_*, form_keyboard_*: measured on the agent phone, 20 Nov (the fixture shop's SheetActivity: a
# full-screen BottomSheetDialog over the filled card form). `uiautomator dump --windows` lists the dialog's window only: the activity under it
# is covered entirely and is not listed, while the picture shows it through the scrim. `dumpsys window windows` names both (each @ of an object
# id in it is written as ~).

CARD_NUMBER = '4111111111111111'


def _filled_shop(tools, monkeypatch):
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    masks = tools._masks_for('default')
    for value in (CARD_NUMBER, '12/99', '737'):  # what the vault's release registers (the card's number, expiry and security code)
        masks.register(value, PKG)


def test_fu14_visible_app_windows_reads_the_real_dumpsys(phone):
    tools, approvals, world, api = phone
    sheet = tools.visible_app_windows(fixture('sheet_over_form_dumpsys.txt'))
    assert sheet[PKG] == 2  # the dialog and the activity under it
    form = tools.visible_app_windows(fixture('form_only_dumpsys.txt'))
    assert form[PKG] == 1
    keyboard = tools.visible_app_windows(fixture('form_keyboard_dumpsys.txt'))
    assert keyboard[PKG] == 1 and keyboard['dev.hermes.agentphone.helper'] == 1  # the keyboard is the helper's window, not the shop's
    assert tools.visible_app_windows('') is None and tools.visible_app_windows('nothing here') is None
    listed = [w for w in tools.parse_windows(fixture('sheet_over_form_windows.xml')) if w['package'] == PKG]
    assert len(listed) == 1  # `--windows` lists one: the covered activity is the window that is not on the list


def test_fu14_the_real_sheet_over_a_filled_form_withholds_the_picture_and_keeps_the_text(phone, monkeypatch):
    """The leak FINAL-121 found: the capture's picture showed the card number, expiry and security code legibly behind the scrim, while its
    text list was clean. The windows list holds the dialog only, so no read of the form's tree exists: the picture is withheld."""
    tools, approvals, world, api = phone
    _filled_shop(tools, monkeypatch)
    world.xml = fixture('sheet_over_form_plain.xml')
    world.windows_xml = fixture('sheet_over_form_windows.xml')
    world.dumpsys_windows = fixture('sheet_over_form_dumpsys.txt')
    result = run(tools, action='capture')
    assert result.get('_multimodal') is None and 'content' not in result, 'a picture of the covered form went to the model'
    assert result['success'] is True and result['picture'] == 'withheld' and result['image'] is None
    assert 'withheld' in result['note'] and 'element numbers still work' in result['note']
    lines = result['untrusted_data']['elements']
    assert any('sheet card number' in line for line in lines) and any('"Save"' in line for line in lines) and any('"Not now"' in line for line in lines)
    assert CARD_NUMBER not in json.dumps(result) and '12/99' not in json.dumps(result)


def test_fu14_the_element_numbers_of_a_withheld_capture_still_act(phone, monkeypatch):
    tools, approvals, world, api = phone
    _filled_shop(tools, monkeypatch)
    world.xml = fixture('sheet_over_form_plain.xml')
    world.windows_xml = fixture('sheet_over_form_windows.xml')
    world.dumpsys_windows = fixture('sheet_over_form_dumpsys.txt')
    lines = run(tools, action='capture')['untrusted_data']['elements']
    number = next(i for i, line in enumerate(lines, start=1) if '"Not now"' in line)
    assert run(tools, action='tap', element=number, purpose='Do not save the card in the shop').get('success') is True
    assert taps(world)


def test_fu14_the_real_form_alone_and_with_its_keyboard_still_get_their_picture(phone, monkeypatch):
    """Nothing is withheld for a screen whose windows `--windows` lists: one window of the shop on the screen, one in the list (the keyboard is
    the helper's window)."""
    tools, approvals, world, api = phone
    _filled_shop(tools, monkeypatch)
    for name in ('form_only', 'form_keyboard'):
        world.xml = fixture('sheet_over_form_plain.xml').replace('Save this card for next time?', 'Kanto Foods')  # any readable shop screen
        world.windows_xml = fixture(f'{name}_windows.xml')
        world.dumpsys_windows = fixture(f'{name}_dumpsys.txt')
        result = run(tools, action='capture')
        assert result.get('_multimodal') is True, name


@pytest.mark.parametrize('answer', ['', 'dumpsys: no window service\n'])
def test_fu14_a_dumpsys_that_names_no_window_withholds_the_picture(phone, monkeypatch, answer):
    """Fail closed: while a fill is live, a phone that can't say what is on its screen is as good as a window nothing can read."""
    tools, approvals, world, api = phone
    _filled_shop(tools, monkeypatch)
    world.xml = fixture('sheet_over_form_plain.xml').replace('Save this card for next time?', 'Kanto Foods')
    world.windows_xml = fixture('form_only_windows.xml')
    world.dumpsys_windows = answer
    result = run(tools, action='capture')
    assert result.get('picture') == 'withheld' and result.get('_multimodal') is None


def test_fu14_with_nothing_filled_the_sheet_is_captured_as_before_and_dumpsys_is_not_asked(phone, monkeypatch):
    tools, approvals, world, api = phone
    from test_agent_phone_redact import load as load_redact
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load_redact())
    tools._masks.clear()
    world.xml = fixture('sheet_over_form_plain.xml')
    world.dumpsys_windows = fixture('sheet_over_form_dumpsys.txt')
    result = run(tools, action='capture')
    assert result.get('_multimodal') is True
    assert ('shell', 'dumpsys window windows') not in [c for c in world.calls if c[0] == 'shell' and isinstance(c[1], str)]


def test_fu14_every_text_field_of_a_listed_window_of_a_filled_app_is_boxed_whether_or_not_it_matches_a_value(phone, monkeypatch):
    """A window behind a dialog that the list does name: its text fields are boxed in the picture, a value or not (the filled value can sit
    in a field no value match finds: a changed text, a value the vault never registered)."""
    tools, approvals, world, api = phone
    _filled_shop(tools, monkeypatch)
    sheet = _root(PKG, node('android.widget.TextView', text='Save this card for next time?', clickable=False, bounds=(63, 1650, 1017, 1800)),
                  node(text='Not now', bounds=(63, 2200, 520, 2330)))
    form = _root(PKG, node('android.widget.TextView', text='Kanto Foods checkout', clickable=False, bounds=(63, 252, 1017, 344)),
                 node('android.widget.EditText', text='a note for the courier', hint='Note', bounds=(63, 344, 1017, 470)))  # no value, no rule: nothing masks it
    world.xml = _plain(sheet)
    world.windows_xml = _windows_dump((True, sheet), (False, form))
    result = run(tools, action='capture')
    assert result.get('_multimodal') is True
    shown = _shown_picture(result)
    assert shown.getpixel((shown.width // 2, int(407 * shown.height / 2400))) < 20, 'the form behind the sheet is in the picture'
