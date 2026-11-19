"""Android's USB and wireless debugging prompts on the agent phone, answered No at once.

An app on the agent phone can make the phone ask "Allow USB debugging?" by offering its own key to the phone's adbd through
10.0.2.2 (EMU-DESIGN §2.3, §5.2); a yes would let that app run shell commands on the phone. Root's rule (17 Nov 2026): answer it
No straight away with Back, never only refuse to tap it. The prompt is found by the window in focus (SystemUI's
UsbDebuggingActivity, UsbDebuggingSecondaryUserActivity or WifiDebuggingActivity), never by its words, so no app can dress up a
look-alike that this answers. Used by the agent's tool (L3) before every action and after every tap, and by the setup view (L2)
while the owner holds the phone. adb is L1b's PhoneAdb (or anything with its shell()).
"""
import re
import time

SYSTEMUI = 'com.android.systemui'
PROMPTS = {'usb': ('.usb.UsbDebuggingActivity', '.usb.UsbDebuggingSecondaryUserActivity'),
           'wifi': ('.wifi.WifiDebuggingActivity',)}
TRIES = 3
PAUSE = 0.6  # seconds for SystemUI to close the prompt after Back
_FOCUS = re.compile(r'mCurrentFocus=Window\{[0-9a-f]+ (?:u\d+ )?([^}\s]+)\}')


def prompt_in(text):
    """'usb', 'wifi' or None from `dumpsys window` output: the focused window, only when it is SystemUI's own prompt."""
    for line in (text or '').splitlines():
        found = _FOCUS.search(line)
        if not found:
            continue
        package, _, activity = found.group(1).partition('/')
        if package != SYSTEMUI or not activity:
            return None
        short = activity[len(package):] if activity.startswith(package + '.') else activity
        for kind, names in PROMPTS.items():
            if short in names:
                return kind
        return None
    return None


def debug_prompt(adb):
    """'usb' or 'wifi' while the phone asks to allow debugging, else None."""
    return prompt_in(adb.shell('dumpsys window | grep mCurrentFocus', timeout=20).stdout)


def deny(adb):
    """Answers the prompt No with Back (the key is not added); True once no prompt is showing. Back is only pressed while one is."""
    for _ in range(TRIES):
        if debug_prompt(adb) is None:
            return True
        adb.shell(['input', 'keyevent', 'KEYCODE_BACK'], timeout=15)
        time.sleep(PAUSE)
    return debug_prompt(adb) is None


def answer(adb):
    """The prompt it answered No ('usb' | 'wifi'), or None when none was showing. RuntimeError when one stays on screen."""
    kind = debug_prompt(adb)
    if kind is None:
        return None
    if not deny(adb):
        raise RuntimeError('debugging prompt stayed')
    return kind
