"""EMU-JOIN follow-up 2: limits that never close a well-behaved phone (A1), reason codes for every phone-screen line (A2, contract
revision 5 §11.1), the password hint (A3, §11.2). Each test was run on the fu1 code first and failed there (logs/fu2-red.log).
Test data is synthetic."""
import asyncio
import importlib.util
import json
import sys
import threading
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(Path(__file__).resolve().parent))


def load(name):
    key = 'beacon_' + name
    if key not in sys.modules:
        spec = importlib.util.spec_from_file_location(key, PLUGIN / f'{name}.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


streamtest = importlib.import_module('test_agent_phone_stream')
stream = streamtest.stream  # the module the session itself runs on, so its ProtocolError is the session's

# The app's own buckets (ViewSocket.kt `Lane`): what a well-behaved phone sends at most, per class (per second, burst).
APP = {'touch': (200.0, 200), 'key': (16.0, 16), 'text': (16.0, 32), 'paste': (1.5, 3), 'ack': (50.0, 50), 'control': (8.0, 8)}
STALL = 2.0  # a mobile stall: the link holds the phone's messages this long, then delivers them at once


class Clock:
    def __init__(self):
        self.now = 1000.0

    def __call__(self):
        return self.now


def message(kind, i):
    if kind == 'touch':  # taps: a down then an up, so nothing coalesces in the queue
        return {'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'down' if i % 2 == 0 else 'up'}]}
    if kind == 'key':
        return {'t': 'key', 'key': 'backspace'}
    if kind == 'text':
        return {'t': 'text', 'text': 'a'}
    if kind == 'paste':
        return {'t': 'paste', 'text': 'synthetic paste'}
    if kind == 'ack':
        return {'t': 'ack', 'seq': 0}
    return {'t': 'refresh'}


def drive(tmp_path, kind, schedule):
    """Runs a real ViewSession on a fake socket whose limits read the virtual clock. ``schedule`` is [(seconds to advance,
    messages to deliver at once)]. Returns (session's end reason or None if still open, close code, messages received)."""
    async def main():
        socket, log, clock = streamtest.FakeSocket(), [], Clock()
        gate = threading.Event()  # the phone holds every op: the worst case for the input queue
        session, _, _ = streamtest.make(tmp_path, socket, log, gate=gate, limits=stream.Limits(clock))
        task = asyncio.create_task(session.run())
        sent = 0
        for advance, count in schedule:
            clock.now += advance
            for _ in range(count):
                socket.push(message(kind, sent))
                sent += 1
            await streamtest.until(lambda: session.received >= sent or task.done(), timeout=10)
            if task.done():
                break
        ended = task.done()
        reason = task.result() if ended else None
        received = session.received
        if not ended:
            socket.push({'t': 'done'})
            gate.set()
            await task
        gate.set()
        return reason, socket.closed if ended else None, received
    return streamtest.run(main())


@pytest.mark.parametrize('kind', list(APP))
def test_a_stall_then_its_queued_compliant_burst_keeps_the_view_open(tmp_path, kind):
    rate, burst = APP[kind]
    paced = max(1, int(rate))  # one second of the phone's own pace before the stall
    schedule = [(1.0 / rate, 1)] * paced + [(STALL, int(burst + rate * STALL))] + [(1.0 / rate, 1)] * paced
    reason, code, received = drive(tmp_path, kind, schedule)
    assert reason is None and code is None, (kind, reason, code)
    assert received == sum(n for _, n in schedule)


@pytest.mark.parametrize('kind', list(APP))
def test_a_real_flood_still_closes_1008(tmp_path, kind):
    rate, burst = stream.RATES[kind]
    reason, code, _ = drive(tmp_path, kind, [(0.0, int(burst + rate * stream.CATCH_UP_SECONDS) + 10)])
    assert reason == 'violation' and code == 1008


# -- A2: every line a phone screen can show has its code (contract revision 5 §11.1) ----------------------------------------

import ast  # noqa: E402
import re  # noqa: E402

DOC = PLUGIN.parents[1] / 'docs' / 'agent-phone' / 'channel-v1.md'


def doc_table():
    """{code: English template} from §11.1's table."""
    text = DOC.read_text(encoding='utf-8').split('### 11.1', 1)[1].split('### 11.2', 1)[0]
    return {m.group(1): m.group(2).strip() for m in re.finditer(r'^\| `([a-z_]+\.[a-z_]+)` \| (.+?) \|$', text, re.M)}


def fstring_template(node):
    """An f-string as its template: '{label}' for a {label} expression, '{label}' for {meta.label} or {held.label}."""
    out = []
    for part in node.values:
        if isinstance(part, ast.Constant):
            out.append(part.value)
        else:
            expr = part.value
            out.append('{' + (expr.attr if isinstance(expr, ast.Attribute) else expr.id) + '}')
    return ''.join(out)


def vault_notices():
    """(reason, template) of every notify(...) payload in the vault module, read from its source."""
    found = []
    tree = ast.parse((PLUGIN / 'agent_phone_vault.py').read_text(encoding='utf-8'))
    for call in ast.walk(tree):
        if isinstance(call, ast.Call) and getattr(call.func, 'attr', '') == 'notify' and len(call.args) == 2 \
                and isinstance(call.args[1], ast.Dict):
            fields = {k.value: v for k, v in zip(call.args[1].keys, call.args[1].values) if isinstance(k, ast.Constant)}
            if 'line' in fields:
                line = fields['line']
                template = line.value if isinstance(line, ast.Constant) else fstring_template(line)
                reason = fields.get('reason')
                found.append((reason.value if isinstance(reason, ast.Constant) else None, template))
    return found


def server_codes():
    """{code: template} for every line the host's modules can send to a phone screen."""
    checks, install, host, api = load('agent_phone_checks'), load('agent_phone_install'), load('agent_phone_host'), load('agent_phone_api')
    codes = {f'check.{k}': v for k, v in checks.LINES.items()}
    codes.update({f'install.{k}': v for k, v in install.WORDS.items()})
    codes.update({f'fix.{k}': v for k, v in install.FIX.items()})
    codes.update({f'helper.{k}': v for k, v in install.HELPER_LINES.items()})
    codes.update({code: line for line, code in host.PHONE_REASONS.items()})
    codes.update({code: line for line, code in api.API_REASONS.items()})
    for reason, template in vault_notices():
        codes[reason] = template.replace('{site}', '{site}')
    return codes


def test_every_line_the_vault_tells_the_owner_has_its_code():
    notices = vault_notices()
    assert notices and all(reason for reason, _ in notices), [t for r, t in notices if not r]


def test_every_phone_error_line_has_its_code():
    host = load('agent_phone_host')
    tree = ast.parse((PLUGIN / 'agent_phone_host.py').read_text(encoding='utf-8'))
    lines = []
    for call in ast.walk(tree):
        if isinstance(call, ast.Call) and getattr(call.func, 'id', '') == 'PhoneError' and call.args:
            arg = call.args[0]
            lines.append(arg.value if isinstance(arg, ast.Constant) else getattr(host, arg.id))
    assert lines and all(line in host.PHONE_REASONS for line in lines), [l for l in lines if l not in host.PHONE_REASONS]
    assert host.PhoneError("The phone's ports are in use.").reason == 'phone.ports'


def test_the_checks_and_the_install_name_their_lines():
    checks, install = load('agent_phone_checks'), load('agent_phone_install')
    for key, line in checks.LINES.items():
        assert checks.row('x', 'fail', line, line)['reason'] == checks.row('x', 'fail', None, line)['fix_reason'] == f'check.{key}'
    error = install.InstallError('space', install.WORDS['space'].format(n=3), fix=install.FIX['broken'], args={'n': '3'})
    assert (error.reason, error.args_, error.fix_reason) == ('install.space', {'n': '3'}, 'fix.broken')
    assert install.InstallError('cannot_run', fix=install.WORDS['ports']).fix_reason == 'install.ports'
    assert load('agent_phone_api').reason_for("{name}'s phone can't start while Hermes' dashboard is off.") == 'check.dashboard_off'
    assert load('agent_phone_api').reason_for('A line nobody wrote.') is None


def test_the_contract_table_is_exactly_what_the_host_can_send():
    doc, server = doc_table(), server_codes()
    assert set(doc) == set(server), (sorted(set(server) - set(doc)), sorted(set(doc) - set(server)))
    assert {code: doc[code] for code in doc if doc[code] != server[code]} == {}


def test_the_views_notice_carries_its_code_and_names(tmp_path):
    async def main():
        socket, log = streamtest.FakeSocket(), []
        session, _, _ = streamtest.make(tmp_path, socket, log)
        task = asyncio.create_task(session.run())
        await streamtest.until(lambda: socket.of('state'))
        session.notice('saved', 'Saved: Kanto Foods · k@example.com', 'vault.saved',
                       {'label': 'Kanto Foods', 'identifier': 'k@example.com'})
        await streamtest.until(lambda: socket.of('notice'))
        socket.push({'t': 'done'})
        await task
        return socket.of('notice')
    assert streamtest.run(main()) == [{'t': 'notice', 'kind': 'saved', 'line': 'Saved: Kanto Foods · k@example.com',
                                       'reason': 'vault.saved', 'args': {'label': 'Kanto Foods', 'identifier': 'k@example.com'}}]


# -- A4: no crash dialog reaches the owner's view or the agent --------------------------------------------------------------

class DialogSession:
    """adb shell as first start sees it; ``focus`` is what `dumpsys window` names, popped per read (the last one stays)."""

    def __init__(self, focus):
        self.focus, self.calls = list(focus), []

    def shell(self, args, timeout=60, **_):
        from types import SimpleNamespace
        self.calls.append(args if isinstance(args, str) else tuple(args))
        if isinstance(args, str) and 'dumpsys window' in args:
            return SimpleNamespace(stdout=self.focus.pop(0) if len(self.focus) > 1 else self.focus[0], returncode=0)
        return SimpleNamespace(stdout='', returncode=0)


ERROR = '  mCurrentFocus=Window{4c2 u0 Application Error: com.android.bluetooth}'
LAUNCHER = '  mCurrentFocus=Window{8a1 u0 com.google.android.apps.nexuslauncher/.NexusLauncherActivity}'


def test_crash_dialogs_go_off_first_and_a_dialog_left_from_the_boot_is_closed(tmp_path):
    import contextlib
    from types import SimpleNamespace
    host = load('agent_phone_host')
    folder = host.Folder(tmp_path / 'p')
    folder.run.mkdir(parents=True)
    s = DialogSession([ERROR, LAUNCHER])  # the boot's Bluetooth crash dialog is up when first start runs
    result = host.PhoneHost(folder).first_start(SimpleNamespace(session=lambda: contextlib.nullcontext(s)))
    settings = [c for c in s.calls if isinstance(c, tuple) and c[:2] == ('settings', 'put')]
    assert settings[0] == ('settings', 'put', 'global', 'hide_error_dialogs', '1')  # before anything else
    assert result['error_dialogs_off'] == 'ok' and result['dialogs'] == 'closed'
    assert ('am', 'broadcast', '-a', 'android.intent.action.CLOSE_SYSTEM_DIALOGS') in s.calls
    s2 = DialogSession([LAUNCHER])
    again = host.PhoneHost(folder).first_start(SimpleNamespace(session=lambda: contextlib.nullcontext(s2)))
    assert again['error_dialogs_off'] == 'done' and again['dialogs'] == 'none'  # every start checks; the setting stays


def test_a_dialog_that_wont_close_is_reported(tmp_path):
    host = load('agent_phone_host')
    folder = host.Folder(tmp_path / 'p')
    folder.run.mkdir(parents=True)
    assert host.PhoneHost(folder).close_dialogs(DialogSession([ERROR]), gap=0.0) == 'left'
    assert host.PhoneHost(folder).close_dialogs(DialogSession(['  mCurrentFocus=Window{1 u0 Application Not Responding: x}', LAUNCHER]),
                                                gap=0.0) == 'closed'


# -- A3: the password hint (§11.2) ----------------------------------------------------------------------------------------------

def test_a_password_field_reaches_the_app_as_keyboard_password_with_the_hosts_exact_line(tmp_path, monkeypatch):
    """The username field first, then the password field: each tap reads the field again. The bytes the app gets are the ones
    beacon-android's AgentPhoneLinesTest.theHostsOwnStateLinesReachTheSession parses."""
    monkeypatch.setattr(stream, 'KEYBOARD_PROBE_DELAY', 0.01)
    monkeypatch.setattr(stream, 'KEYBOARD_PROBE_GAP', 0.0)
    fields = [True, 'password']

    async def main():
        socket, log = streamtest.FakeSocket(), []
        raw = []
        send_text = socket.send_text

        async def keep(text):
            raw.append(text)
            await send_text(text)
        socket.send_text = keep
        session, _, _ = streamtest.make(tmp_path, socket, log, keyboard_probe=lambda: fields[0])
        task = asyncio.create_task(session.run())
        await streamtest.until(lambda: any(s.get('helper') == 'ready' for s in socket.of('state')))

        def tap():
            socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'down'}]})
            socket.push({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'up'}]})
        tap()  # the email field
        await streamtest.until(lambda: socket.of('state')[-1].get('keyboard') is True)
        fields.pop(0)
        tap()  # then the password field
        await streamtest.until(lambda: socket.of('state')[-1].get('keyboard') == 'password')
        socket.push({'t': 'done'})
        await task
        return raw
    raw = streamtest.run(main())
    assert '{"t":"state","helper":"ready","keyboard":"password","rotation":0,"prompt":null,"lease":"you","epoch":1}' in raw


def test_the_probe_reads_androids_own_field_type_measured_on_a_fresh_phone():
    """The probe's output as the shell prints it: the window line, then (keyboard up only) curEditorInfo and its inputType, the
    lines measured on the fresh phone's shop login (evidence/a4-green1/a3-*-ime.txt)."""
    apimod = load('agent_phone_api')
    up = 'mImeShowing=true mLastDrawn=true\n'
    email = up + '      curEditorInfo:\n        inputType=0x21 imeOptions=0x8000005 privateImeOptions=null\n'
    password = up + '      curEditorInfo:\n        inputType=0x81 imeOptions=0x4000006 privateImeOptions=null\n'
    assert apimod.keyboard_shown(email) is True
    assert apimod.keyboard_shown(password) == 'password'
    assert apimod.keyboard_shown(up + 'curEditorInfo:\n inputType=0xe1\n') == 'password'  # a web password
    assert apimod.keyboard_shown(up + 'curEditorInfo:\n inputType=0x12\n') == 'password'  # a PIN
    assert apimod.keyboard_shown(up + 'curEditorInfo:\n inputType=0x91\n') is True  # a visible password: shown on purpose
    assert apimod.keyboard_shown('mImeShowing=false mLastDrawn=false\n') is False
    # the field's type only counts while the keyboard is up (the shell doesn't read it otherwise; a stale one is ignored)
    assert apimod.keyboard_shown('mImeShowing=false\ncurEditorInfo:\n inputType=0x81\n') is False
    probe = apimod.KEYBOARD_PROBE
    assert probe.startswith("s=$(dumpsys window | grep -m 1 -E 'mImeShowing=')") and '*mImeShowing=true*) dumpsys input_method' in probe
    assert '"' not in probe  # no double quotes to survive Windows' argument quoting on the way to adb


# -- A2: the routes' own refusals (§11.1 "HTTP refusals") -----------------------------------------------------------------------

from test_agent_phone_api import PREFIX, phone_api  # noqa: E402,F401  (the fixture)
from test_mobile import harness  # noqa: E402,F401  (the fixture)

# Refusals whose detail is not displayed (§11.1). The task dialog uses its localized fallback if policy changed after opening.
STATUS_ONLY = {(400, 'Expected request_id and task'), (401, 'Verified native session required'), (403, 'Device removed'),
               (403, 'The phone changed'), (403, 'Phone use is turned off'), (404, 'Approval not found')}


def test_every_refusal_a_screen_can_show_has_its_code():
    """Every HTTPException of the routes whose detail is a fixed line: either a line with a code, or an invalid request or one of
    the status-only refusals above. A new line with neither fails here."""
    api = load('agent_phone_api')
    tree = ast.parse((PLUGIN / 'agent_phone_api.py').read_text(encoding='utf-8'))
    consts = {t.id: n.value.value for n in tree.body if isinstance(n, ast.Assign) and isinstance(n.value, ast.Constant)
              and isinstance(n.value.value, str) for t in n.targets if isinstance(t, ast.Name)}
    seen, uncoded = set(), []
    for node in ast.walk(tree):
        if isinstance(node, ast.Call) and getattr(node.func, 'id', None) == 'HTTPException' and len(node.args) >= 2:
            detail = node.args[1]
            line = detail.value if isinstance(detail, ast.Constant) else consts.get(detail.id) if isinstance(detail, ast.Name) else None
            if line is None:
                continue  # str(exc), the decision's expired/settled: status-only
            seen.add(line)
            status = getattr(node.args[0], 'value', None)
            if not ((status == 400 and line.startswith('Invalid ')) or (status, line) in STATUS_ONLY or api.reason_for(line)):
                uncoded.append(line)
    assert uncoded == []
    for line, code in ((api.UPDATE_LINE, 'phone.needs_update'), (api.NOT_SET_UP, 'phone.none'), (api.OTHER_PROFILE, 'phone.other_profile'),
                       (api.BUSY, 'hermes.busy'), (api.UPDATING, 'hermes.updating')):
        assert line in seen and api.reason_for(line) == code


def test_the_other_profiles_refusal_carries_its_line_and_code_and_an_invalid_request_stays_fastapis(phone_api):  # noqa: F811
    api = load('agent_phone_api')
    other = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=other', json={})
    assert other.status_code == 403
    assert other.json() == {'detail': api.OTHER_PROFILE, 'line': api.OTHER_PROFILE, 'reason': 'phone.other_profile'}
    bad = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={'x': 1})
    assert bad.status_code == 400 and bad.json() == {'detail': 'Invalid start'}


def test_not_set_up_and_needs_an_update_carry_their_codes(harness, monkeypatch):  # noqa: F811
    api, client, cfg, tmp, app = harness
    apimod = sys.modules['beacon_agent_phone_api']
    hidden, real = ('agent_phone_install', 'agent_phone_checks', 'agent_phone_host', 'agent_phone_channel', 'agent_phone_approvals'), apimod.sibling
    monkeypatch.setattr(apimod, 'sibling', lambda name: None if name in hidden else real(name))
    start = client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={})
    assert start.status_code == 404 and start.json() == {'detail': apimod.NOT_SET_UP, 'line': apimod.NOT_SET_UP, 'reason': 'phone.none'}
    approvals = client.get(f'{PREFIX}/mobile/agent-phone/approvals?beacon_profile=default')
    assert approvals.status_code == 503
    assert approvals.json() == {'detail': apimod.UPDATE_LINE, 'line': apimod.UPDATE_LINE, 'reason': 'phone.needs_update'}


# -- B1: the debugging prompt reaches the view as the app reads it (state.prompt == "usb_debugging") -----------------------------

def test_the_debugging_prompts_state_line_is_the_exact_one_the_app_parses(tmp_path, monkeypatch):
    """The bytes beacon-android's AgentPhoneLinesTest.theHostsOwnStateLinesReachTheSession parses into prompt = "usb_debugging"."""
    monkeypatch.setattr(stream, 'GUARD_SECONDS', 0.1)
    monkeypatch.setattr(stream, 'GUARD_AFTER_INPUT', 0.05)
    guard = streamtest.FakeGuard()

    async def main():
        socket, log = streamtest.FakeSocket(), []
        raw = []
        send_text = socket.send_text

        async def keep(text):
            raw.append(text)
            await send_text(text)
        socket.send_text = keep
        session, _, _ = streamtest.make(tmp_path, socket, log, debug_prompt=guard.debug_prompt, deny_prompt=guard.deny)
        task = asyncio.create_task(session.run())
        await streamtest.until(lambda: guard.checks and any(s.get('helper') == 'ready' for s in socket.of('state')))
        guard.showing = 'usb'
        await streamtest.until(lambda: any(s.get('prompt') == 'usb_debugging' for s in socket.of('state')))
        socket.push({'t': 'done'})
        await task
        return raw
    raw = streamtest.run(main())
    assert '{"t":"state","helper":"ready","keyboard":false,"rotation":0,"prompt":"usb_debugging","lease":"you","epoch":1}' in raw
