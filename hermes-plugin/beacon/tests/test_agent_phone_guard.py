"""Android's USB and wireless debugging prompts on the agent phone: found by the focused window, answered No with Back."""
import importlib.util
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def load():
    spec = importlib.util.spec_from_file_location('beacon_agent_phone_guard', ROOT / 'agent_phone_guard.py')
    module = importlib.util.module_from_spec(spec)
    sys.modules['beacon_agent_phone_guard'] = module
    spec.loader.exec_module(module)
    return module


class Done:
    def __init__(self, stdout=''):
        self.stdout, self.returncode, self.stderr = stdout, 0, ''


class Phone:
    """PhoneAdb's shape: the focused window until Back is pressed `closes_after` times."""

    def __init__(self, focus, closes_after=1):
        self.focus, self.closes_after, self.calls = focus, closes_after, []

    def shell(self, args, timeout=60.0, input=None, binary=False):
        self.calls.append(args)
        if args == ['input', 'keyevent', 'KEYCODE_BACK']:
            self.closes_after -= 1
            if self.closes_after <= 0:
                self.focus = 'com.google.android.apps.nexuslauncher/.NexusLauncherActivity'
            return Done()
        if isinstance(args, str) and args.startswith('dumpsys window'):
            return Done(f'  mCurrentFocus=Window{{a1b2c3 u0 {self.focus}}}\n')
        return Done()


USB = 'com.android.systemui/com.android.systemui.usb.UsbDebuggingActivity'


@pytest.mark.parametrize('focus,kind', [
    (USB, 'usb'),
    ('com.android.systemui/.usb.UsbDebuggingActivity', 'usb'),
    ('com.android.systemui/com.android.systemui.usb.UsbDebuggingSecondaryUserActivity', 'usb'),
    ('com.android.systemui/.wifi.WifiDebuggingActivity', 'wifi'),
    ('com.android.systemui/com.android.systemui.wifi.WifiDebuggingActivity', 'wifi'),
    ('com.android.systemui/com.android.systemui.media.MediaProjectionPermissionActivity', None),
    ('dev.evil.app/dev.evil.app.usb.UsbDebuggingActivity', None),
    ('dev.beacon.fixture.shop/dev.beacon.fixture.shop.LoginActivity', None),
])
def test_the_prompt_is_found_by_the_focused_window(focus, kind):
    guard = load()
    assert guard.debug_prompt(Phone(focus)) == kind


def test_a_phone_with_no_focus_line_has_no_prompt():
    guard = load()
    assert guard.prompt_in('') is None and guard.prompt_in('  mCurrentFocus=null\n') is None
    assert guard.prompt_in('  mCurrentFocus=Window{1 u0 NotificationShade}\n') is None


def test_deny_presses_back_until_the_prompt_is_gone():
    guard = load()
    phone = Phone(USB, closes_after=2)
    guard.PAUSE = 0
    assert guard.deny(phone) is True
    assert phone.calls.count(['input', 'keyevent', 'KEYCODE_BACK']) == 2


def test_deny_gives_up_and_says_so():
    guard = load()
    phone = Phone(USB, closes_after=99)
    guard.PAUSE = 0
    assert guard.deny(phone) is False
    assert phone.calls.count(['input', 'keyevent', 'KEYCODE_BACK']) == guard.TRIES


def test_answer_denies_and_names_what_it_answered():
    guard = load()
    guard.PAUSE = 0
    phone = Phone('com.android.systemui/.wifi.WifiDebuggingActivity')
    assert guard.answer(phone) == 'wifi' and guard.debug_prompt(phone) is None
    quiet = Phone('dev.beacon.fixture.shop/.LoginActivity')
    assert guard.answer(quiet) is None and ['input', 'keyevent', 'KEYCODE_BACK'] not in quiet.calls


def test_back_is_never_pressed_without_a_prompt():
    guard = load()
    phone = Phone('dev.beacon.fixture.shop/.LoginActivity')
    assert guard.deny(phone) is True and ['input', 'keyevent', 'KEYCODE_BACK'] not in phone.calls
