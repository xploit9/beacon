"""The agent phone's routes (EMU-DESIGN §3, §4.2-§4.3, §7.1, §8; approvals-v1 §6), mounted by dashboard/plugin_api.py under
``/api/plugins/beacon``.

- **Install flow** (§3): checks, Google's terms and the owner's Accept, the download with Pause/Resume/Cancel, and Remove, over
  L1a's ``agent_phone_install`` / ``agent_phone_checks``.
- **The phone** (§7.1): status, start (the start checks first: no start without Hermes sign-in), stop, hand back.
- **The setup view** (§4.2-§4.3): a 20 s single-use ticket for a linked phone of the owner and the phone's owning profile, then a
  WebSocket with the same checks as ``/gateway/ws`` (Hermes' client and origin checks, exactly two subprotocols, the binding
  revalidated at accept and every few seconds while open). Accept takes the lease; ``agent_phone_stream.ViewSession`` runs it.
- **Approvals** (L3's contract, approvals-v1 §6): the five routes over ``agent_phone_approvals``.

Every sibling module is loaded when a route needs it, by path, under ``beacon_<name>`` (as L1a's and L1b's loaders do), so this
module imports with none of them present and a route without its module answers 503. No answer carries a path, a port, a serial
or a token.
"""
import asyncio
import contextlib
import importlib.util
import json
import logging
import os
import re
import sqlite3
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

from fastapi import APIRouter, HTTPException, Request, WebSocket
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

ROOT = Path(__file__).resolve().parent
NO_STORE = {'Cache-Control': 'no-store'}
VIEW_PATH = '/api/plugins/beacon/agent-phone/view'
VIEW_SOCKETS = 4
CALL_SECONDS = 6.0
CHECKS_SECONDS = 120.0  # the virtualisation query (30 s) and the network check (10 s) are the slow ones
TERMS_SECONDS = 90.0
KEPT_SECONDS = 15 * 60  # checks and terms an Accept may rely on
APPROVAL_ID = re.compile(r'[0-9a-f]{32}')
AVD_NAME = 'beacon_agent_phone'
UPDATE_LINE = "{name}'s phone needs a Beacon update."
NOT_SET_UP = "{name}'s phone isn't set up yet."
OTHER_PROFILE = "{name}'s phone belongs to another profile."
START_FAILED = "The phone didn't start."
log = logging.getLogger('beacon.agent_phone.api')


def sibling(name):
    """A Beacon module beside this one (``beacon_<name>``), or None when its file is not there. A module whose file changed
    since it was loaded (a plugin update) is loaded again, as plugin_api does for its own siblings."""
    key = 'beacon_' + name
    path = ROOT / f'{name}.py'
    module = sys.modules.get(key)
    try:
        mtime = path.stat().st_mtime_ns
    except OSError:
        return module if module is not None and getattr(module, '_beacon_mtime', None) is None else None
    if module is not None and getattr(module, '_beacon_mtime', mtime) == mtime:
        return module
    spec = importlib.util.spec_from_file_location(key, path)
    module = importlib.util.module_from_spec(spec)
    module._beacon_mtime = mtime
    sys.modules[key] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(key, None)
        raise
    return module


def done_label(connect, owner, activity_key):
    """The label of the activity row an agent_phone_done notice names (L3 §5.5, `agent_phone_activity:<seq>`), for the notification's
    detail, which the phone reads over Hermes; the push itself carries only 'Payment done' or 'Sent'. None when the key is malformed,
    the row is gone or L3's table isn't there."""
    match = re.fullmatch(r'agent_phone_activity:(\d{1,18})', activity_key or '')
    if not match:
        return None
    try:
        with connect() as conn:
            row = conn.execute('SELECT label FROM agent_phone_activity WHERE owner=? AND seq=?', (owner, int(match[1]))).fetchone()
    except sqlite3.Error:  # no L3: no activity table
        return None
    label = ' '.join(str(row[0] or '').split())[:120] if row else ''
    return label or None


def sweep_approvals():
    """The dashboard watcher's agent-phone pass (every few seconds); never raises: L3's approval records (approvals-v1 §6), and
    the phone's heartbeat for its keeper."""
    try:
        module = sibling('agent_phone_approvals')
        if module is not None:
            module.sweep()
    except Exception as exc:  # health: allow BLE001 -- the next pass tries again
        log.info('agent phone: approval sweep failed (%s)', type(exc).__name__)
    try:
        mobile = sys.modules.get('beacon_mobile')
        if mobile is not None:
            heartbeat(mobile.home())
    except Exception as exc:  # health: allow BLE001 -- the next pass tries again
        log.info('agent phone: heartbeat failed (%s)', type(exc).__name__)


HEARTBEAT_SECONDS = 60
_heartbeat_at = None


def heartbeat(home, clock=time.monotonic):
    """§7.2: "the plugin's heartbeat (written each minute while Hermes runs)"; the keeper stops a phone whose heartbeat is a day
    old. At most once a minute, only for an installed phone, never creating its folder (a Remove may be running). True when
    written."""
    global _heartbeat_at
    now = clock()
    if _heartbeat_at is not None and 0 <= now - _heartbeat_at < HEARTBEAT_SECONDS:
        return False
    _heartbeat_at = now
    pointer, hostm = read_pointer(home), sibling('agent_phone_host')
    if not pointer or hostm is None or not (Path(pointer['folder']) / 'run').is_dir():
        return False
    hostm.PhoneHost(pointer['folder']).write_heartbeat()
    return True


def need(name):
    module = sibling(name)
    if module is None:
        raise HTTPException(503, UPDATE_LINE)
    return module


def read_pointer(home):
    """``<home>/plugin-data/beacon/agent-phone.json`` (L1a): ``folder``, ``profile``, ``dashboard``; None when there is none."""
    install = sibling('agent_phone_install')
    if install is not None:
        return install.read_pointer(home)
    try:
        value = json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None
    return value if isinstance(value, dict) and value.get('folder') else None


def device_size(folder):
    """The phone's screen in pixels from its AVD (``hw.lcd.width``/``hw.lcd.height``), else 1080x2400 (§2.4)."""
    stream = sibling('agent_phone_stream')
    size = list(stream.DEVICE_SIZE)
    try:
        text = (Path(folder) / 'home' / 'avd' / f'{AVD_NAME}.avd' / 'config.ini').read_text(encoding='utf-8', errors='replace')
    except OSError:
        return tuple(size)
    for line in text.splitlines():
        key, _, value = line.partition('=')
        if key.strip() in ('hw.lcd.width', 'hw.lcd.height') and value.strip().isdigit():
            size[0 if key.strip() == 'hw.lcd.width' else 1] = int(value.strip())
    return tuple(size)


# L9 (gates B5, B8, 17 Nov): Android keeps a screen's first autofill answer, so after every arm the vault resets autofill;
# `cmd autofill destroy sessions` was not enough after an empty first answer.
AUTOFILL_RESET = ['cmd', 'autofill', 'reset']
SETUP_PING_SECONDS = 30
# A fixed script. Measured on the B6 phone (Android 16): `dumpsys window` answers mImeShowing in 53-66 ms; `dumpsys input_method`
# (~800 KB) took 170-216 ms and slowed taps while it ran (loopback tap median 55.7 -> 85.2 ms with a probe after every touch).
# EMU-JOIN fu2 A3: only while the keyboard is up, the same shell reads the field it serves from `dumpsys input_method`
# (curEditorInfo's inputType, Android's own field info, never any text). Measured on a fresh phone (evidence/a4-green1.json):
# 264-545 ms, against ~2 s for `uiautomator dump`'s password flag, which agreed (email 0x21 / false, password 0x81 / true).
KEYBOARD_PROBE = ("s=$(dumpsys window | grep -m 1 -E 'mImeShowing='); echo $s; "
                  "case $s in *mImeShowing=true*) dumpsys input_method | grep -m 1 -A 1 curEditorInfo: ;; esac")
# android.text.InputType, as the helper's core/Roles.kt (text + password or web password), and a number password (a PIN).
# A visible password is left plain: the app shows that text on purpose.
TYPE_MASK_CLASS, TYPE_MASK_VARIATION = 0x0f, 0xff0
PASSWORD_TYPES = {(0x1, 0x80), (0x1, 0xe0), (0x2, 0x10)}


def keyboard_shown(dumpsys):
    """Whether the on-phone keyboard is up: ``mImeShowing`` (``dumpsys window``), else ``mInputShown`` (``dumpsys
    input_method``); None when neither says. ``mIsInputViewShown`` stays true while the keyboard is hidden (B6 phone), so it is
    not read. 'password' when it is up for a password field (the inputType after ``curEditorInfo:``, contract revision 5
    §11.2)."""
    for name in ('mImeShowing', 'mInputShown'):
        found = re.findall(r'\b' + name + r'=(true|false)\b', dumpsys or '')
        if found:
            shown = 'true' in found
            field = re.search(r'curEditorInfo:\s*inputType=0x([0-9a-fA-F]{1,8})\b', dumpsys or '')
            if shown and field:
                kind = int(field.group(1), 16)
                if (kind & TYPE_MASK_CLASS, kind & TYPE_MASK_VARIATION) in PASSWORD_TYPES:
                    return 'password'
            return shown
    return None


def dashboard_address(request):
    """The dashboard's own loopback address, which the sign-in check reads (§3): ``http://127.0.0.1:<port>``. The port is the one
    Hermes records when it listens (``app.state.bound_port``, hermes_cli/web_server.py), else this connection's server port. Always
    http: the dashboard serves no TLS itself (a TLS front is a proxy), and the request's own scheme may be the proxy's."""
    port = getattr(request.app.state, 'bound_port', None)
    if not isinstance(port, int) or not 0 < port < 65536:
        server = request.scope.get('server') or (None, None)
        port = server[1] if server and isinstance(server[1], int) else None
    return f'http://127.0.0.1:{port}' if port else None


# -- the phone page's facts (follow-up 1) -----------------------------------------------------------------------------------

HELPER_PACKAGE = 'dev.hermes.agentphone.helper'
FACTS_SECONDS = 60.0  # read through PhoneAdb at most this often, only while the phone is on
STORAGE_SECONDS = 600.0
IN_USE_SECONDS = 120.0
# Fixed scripts: only counts, version numbers and a 0/1 leave the phone, never an account name or e-mail.
FACT_SCRIPTS = {
    'google': "dumpsys account | grep -c -E 'Account \\{name=.*, type=com\\.google\\}'",
    'chrome': "dumpsys package com.android.chrome | grep -E -o 'versionName=[0-9]+' | head -n 4",
    # Chrome 135+ (android-developers.googleblog.com/2025/02/chrome-3p-autofill-services-update.html): 1 = "Autofill using
    # another service" is on, 0 = off; an older Chrome has no such provider (null).
    'chrome_autofill': ('content query --uri content://com.android.chrome.AutofillThirdPartyModeContentProvider/'
                        'autofill_third_party_mode --projection autofill_third_party_state 2>/dev/null'),
    'helper': f"dumpsys package {HELPER_PACKAGE} | grep -E -o 'versionCode=[0-9]+' | head -n 1",
    # Android's active autofill service (Root, 17 Nov: the helper reads 'ready' only while it is that service)
    'autofill': 'settings get secure autofill_service',
}


def parse_facts(out):
    """{google, chrome, chrome_autofill, helper_version, helper_installed, autofill} from the scripts' outputs (None = unknown);
    ``autofill`` is whether Beacon's helper is Android's autofill service (short or full component form)."""
    facts = {'google': None, 'chrome': None, 'chrome_autofill': None, 'helper_version': None, 'helper_installed': None,
             'autofill': None}
    if 'autofill' in out:
        service = (out.get('autofill') or '').strip()
        facts['autofill'] = service.split('/', 1)[0] == HELPER_PACKAGE and service.endswith('.HelperAutofillService')
    google = (out.get('google') or '').strip()
    if google.isdigit():
        facts['google'] = int(google) > 0
    majors = [int(m) for m in re.findall(r'versionName=(\d+)', out.get('chrome') or '')]
    facts['chrome'] = max(majors) if majors else None
    state = re.search(r'autofill_third_party_state=(\d)', out.get('chrome_autofill') or '')
    facts['chrome_autofill'] = (state.group(1) == '1') if state else None
    if 'helper' in out:
        code = re.search(r'versionCode=(\d+)', out.get('helper') or '')
        facts['helper_installed'] = bool(code)
        facts['helper_version'] = int(code.group(1)) if code else None
    return facts


HELPER_DAMAGED = "Beacon's helper file is damaged. Update Beacon."
HELPER_FAILED = "The helper didn't install. Try again."
HELPER_NONE = "This Beacon has no helper to install."
TURN_ON_FIRST = "Turn the phone on first."
CHECK_FIRST = 'Check this computer first.'
TERMS_FIRST = 'Read and accept the terms first.'
NOTHING_TO_RESUME = 'Nothing to resume.'
NOT_ITS_FOLDER = "That isn't {name}'s phone folder."
INSTALL_FAILED = 'Something went wrong.'
BUSY = 'Busy, try again.'
UPDATING = 'Update in progress'
# The routes' own lines and their codes (contract revision 5 §11.1). The other modules hold theirs beside their own tables.
API_REASONS = {START_FAILED: 'phone.not_started', HELPER_DAMAGED: 'helper.damaged', HELPER_FAILED: 'helper.failed',
               HELPER_NONE: 'helper.none', TURN_ON_FIRST: 'phone.turn_on_first', CHECK_FIRST: 'install.checks',
               TERMS_FIRST: 'install.terms_first', NOTHING_TO_RESUME: 'install.nothing_to_resume',
               NOT_ITS_FOLDER: 'phone.not_its_folder', INSTALL_FAILED: 'install.failed',
               UPDATE_LINE: 'phone.needs_update', NOT_SET_UP: 'phone.none', OTHER_PROFILE: 'phone.other_profile',
               BUSY: 'hermes.busy', UPDATING: 'hermes.updating'}


def reason_for(line):
    """The revision 5 code of a line a route sends (§11.1): the routes' own table, then the table of the module the line came
    from (L1b's PhoneError lines, L1a's install and check lines). None for a line no table holds: the app then shows ``line``."""
    if not isinstance(line, str):
        return None
    if line == 'The owner has the phone':
        return 'phone.busy'
    if line in API_REASONS:
        return API_REASONS[line]
    host = sibling('agent_phone_host')
    if host is not None and line in getattr(host, 'PHONE_REASONS', {}):
        return host.PHONE_REASONS[line]
    for name in ('agent_phone_install', 'agent_phone_checks'):
        module = sibling(name)
        found = module.reason_of(line) if module is not None and hasattr(module, 'reason_of') else None
        if found:
            return found
    return None


NOTICE_ARGS = ('label', 'identifier', 'site')


def notice_args(payload):
    """The names a vault notice's line holds (§11.1 ``args``), as strings; never a value of a login."""
    found = {k: payload[k] for k in NOTICE_ARGS if isinstance(payload.get(k), str) and payload[k]}
    return found or None


def refused(status, line):
    """A refusal a screen shows: FastAPI's ``detail`` as before, and the line with its code (§11.1)."""
    return JSONResponse({'detail': line, 'line': line, 'reason': reason_for(line)}, status_code=status, headers=NO_STORE)


class CodedRoute(APIRoute):
    """The routes' class: an HTTPException whose detail is a line with a code (one a screen shows: the phone isn't set up, belongs
    to another profile, needs a Beacon update, Hermes is busy or updating) answers as ``refused`` does, ``detail`` unchanged beside
    ``line`` and ``reason`` (§11.1). Any other HTTPException (an invalid request, a removed device) is FastAPI's own. include_router
    keeps a route's class, so this holds wherever Hermes mounts the router."""

    def get_route_handler(self):
        handler = super().get_route_handler()

        async def coded(request):
            try:
                return await handler(request)
            except HTTPException as exc:
                if isinstance(exc.detail, str) and reason_for(exc.detail) and not exc.headers:
                    return refused(exc.status_code, exc.detail)
                raise
        return coded


def install_helper(adb, apk, pin, autofill_service, code_listener):
    """Reinstalls the pinned helper through PhoneAdb (follow-up 1): the file's SHA-256 and its one signer are checked against the
    pin first; an installed helper signed by another key is removed first (Android refuses such an update); then the helper is
    the autofill service and the notification listener again (S8, S13). Returns None, or the plain line the owner sees."""
    import hashlib

    if apk is None or not Path(apk).is_file():
        return HELPER_DAMAGED
    digest = hashlib.sha256(Path(apk).read_bytes()).hexdigest()
    signers = sibling('agent_phone_install').apk_signers(apk)
    if digest != str(pin.get('sha256', '')).lower() or signers != [str(pin.get('signer_sha256', '')).lower()]:
        return HELPER_DAMAGED
    with adb.session() as session:
        done = session.install(apk)
        if done.returncode != 0 and 'INSTALL_FAILED_UPDATE_INCOMPATIBLE' in (done.stdout or ''):
            session.shell(['pm', 'uninstall', HELPER_PACKAGE], timeout=60)
            done = session.install(apk)
        if done.returncode != 0:
            return HELPER_FAILED
        session.shell(['settings', 'put', 'secure', 'autofill_service', autofill_service], timeout=30)
        session.shell(['cmd', 'notification', 'allow_listener', code_listener], timeout=30)
    return None


def folder_bytes(folder):
    total, stack = 0, [str(folder)]
    while stack:
        try:
            entries = list(os.scandir(stack.pop()))
        except OSError:
            continue
        for entry in entries:
            try:
                if entry.is_dir(follow_symlinks=False):
                    stack.append(entry.path)
                elif entry.is_file(follow_symlinks=False):
                    total += entry.stat(follow_symlinks=False).st_size
            except OSError:
                continue
    return total


class Facts:
    """The phone page's facts for one phone folder, cached in this process; refreshed on a thread so a status call never waits
    on the phone. ``storage_bytes`` is the folder's size, walked at most every ``STORAGE_SECONDS``."""

    def __init__(self):
        self.lock = threading.Lock()
        self.values, self.at, self.busy = {}, None, False
        self.storage, self.storage_at, self.storage_busy = None, None, False

    def snapshot(self):
        with self.lock:
            return dict(self.values), self.storage

    def due(self):
        with self.lock:
            return not self.busy and (self.at is None or time.monotonic() - self.at >= FACTS_SECONDS)

    def read(self, adb):
        """Runs the fact scripts in one adb session and stores what they say."""
        with self.lock:
            if self.busy:
                return
            self.busy = True
        try:
            out = {}
            with adb.session() as session:
                for name, script in FACT_SCRIPTS.items():
                    try:
                        out[name] = session.shell(script, timeout=20).stdout
                    except Exception:  # health: allow BLE001 -- one fact unread stays unknown
                        continue
            facts = parse_facts(out)
            with self.lock:
                self.values, self.at = facts, time.monotonic()
        except Exception as exc:  # health: allow BLE001 -- the phone stopped meanwhile: the last facts stay
            log.info('agent phone: facts unread (%s)', type(exc).__name__)
        finally:
            with self.lock:
                self.busy = False

    def wait_idle(self, seconds=30.0):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            with self.lock:
                if not self.busy:
                    return True
            time.sleep(0.05)
        return False

    def storage_due(self):
        with self.lock:
            return not self.storage_busy and (self.storage_at is None or time.monotonic() - self.storage_at >= STORAGE_SECONDS)

    def measure(self, folder):
        with self.lock:
            if self.storage_busy or (self.storage_at is not None and time.monotonic() - self.storage_at < STORAGE_SECONDS):
                return
            self.storage_busy = True
        try:
            size = folder_bytes(folder)
            with self.lock:
                self.storage, self.storage_at = size, time.monotonic()
        finally:
            with self.lock:
                self.storage_busy = False

    def forget(self):
        with self.lock:
            self.values, self.at, self.storage, self.storage_at = {}, None, None, None


class Deps:
    """The seams the routes use; tests replace them."""

    def __init__(self, mobile):
        self.mobile = mobile

    def home(self):
        return self.mobile.home()

    def module(self, name):
        return sibling(name)

    def host(self, folder, lease):
        need('agent_phone_host')
        return need('agent_phone_install').phone_host(folder, lease)

    def helper(self, folder):
        install = sibling('agent_phone_install')
        return install.helper_status(folder) if install is not None else None

    def helper_pin(self):
        """L1a's ``helper_package()``: {package, version_code, version_name, sha256, signer_sha256, bytes, url} or None."""
        install = sibling('agent_phone_install')
        return install.helper_package() if install is not None else None

    def fetch_helper(self, folder):
        """The pinned helper fetched into the phone folder and checked (L1a's ``fetch_helper``; Root, 17 Nov), or None."""
        return need('agent_phone_install').fetch_helper(folder)

    def guard(self):
        """L3's agent_phone_guard (debug_prompt(adb), deny(adb)), or None."""
        return sibling('agent_phone_guard')

    def logins(self, profile):
        """How many logins the phone saved to the owning profile's vault: L9's sidecar items marked ``saved_on_phone``."""
        vault = sibling('agent_phone_vault')
        if vault is None or not profile:
            return None
        try:
            data = json.loads(vault.sidecar_path(vault.owning_home(profile)).read_text(encoding='utf-8'))
        except FileNotFoundError:
            return 0
        except Exception:  # health: allow BLE001 -- the profile's home or the sidecar can't be read: unknown, never a guess
            return None
        items = data.get('items') if isinstance(data, dict) else None
        if not isinstance(items, dict):
            return None
        return sum(1 for entry in items.values() if isinstance(entry, dict) and entry.get('saved_on_phone') is True)

    def start_gate(self, home):
        checks = sibling('agent_phone_checks')
        if checks is None:
            return False, UPDATE_LINE
        return checks.start_gate(home, name='{name}')  # the app fills in the assistant's name (CONTRACT.md)

    def keep_dashboard(self, home, address):
        """The start checks read Hermes' sign-in at the address the pointer records (L1a): keep it this dashboard's own, so a
        dashboard that came back on another port doesn't refuse every start."""
        install = sibling('agent_phone_install')
        pointer = read_pointer(home)
        if install is not None and address and pointer and pointer.get('dashboard') != address:
            install.write_pointer(home, pointer['folder'], profile=pointer.get('profile'), dashboard=address)

    def developer_tools(self):
        checks = sibling('agent_phone_checks')
        if checks is None:
            return None
        found = checks.check_developer_tools()
        return found.get('line') if found and found.get('status') != 'ready' else None

    def channel(self, host, state, notice):
        """The setup session's helper channel (§4.3) with the owning profile's vault on it (L9), or None."""
        channel = sibling('agent_phone_channel')
        if channel is None:
            return None
        adb = host.adb(state)
        events = None
        vault = sibling('agent_phone_vault')
        redact = sibling('agent_phone_redact')
        if vault is not None and redact is not None:
            try:
                pointer = read_pointer(self.home()) or {}
                # The setup session's fills register into the file the gateway's set reads (the phone folder's run/masks.json).
                run = getattr(host.folder, 'run', None) or Path(host.folder) / 'run'
                phone_vault = vault.PhoneVault.for_dashboard(
                    pointer.get('profile'), redact.MaskSet(store=Path(run) / 'masks.json'), notify=lambda kind, payload: notice(kind, payload or {}),
                    refresh=lambda: adb.shell(AUTOFILL_RESET, timeout=10))
                events = lambda session: phone_vault.handler(session, owner=True)  # noqa: E731
            except Exception as exc:  # health: allow BLE001 -- no vault (an older Hermes, an unreadable profile): text and Paste still work
                log.info('agent phone: setup channel without the vault (%s)', type(exc).__name__)
                events = None
        # channel-v1 §5: "The host pings every 30 s during a setup session" (else the link ends after 20 min without traffic)
        return channel.connect(adb, events=events, keepalive_seconds=SETUP_PING_SECONDS)


class Jobs:
    """Start, stop and install run on threads; their state lives here for the status route (one dashboard process)."""

    def __init__(self):
        self.lock = threading.Lock()
        self.phone = None  # 'starting' | 'stopping' | None
        self.error = None
        self.error_reason = None  # the error line's code (contract revision 5 §11.1)
        self.install = {'state': 'idle'}
        self.control = None
        self.thread = None
        self.checks = self.checks_at = None
        self.terms = self.terms_at = None
        self.checks_value = None  # the checks (and the owner's Continue) the running install was started with
        self.helper = None  # 'updating' while the helper is being reinstalled


def build_router(mobile, identity_async, body, dashboard_identity, enforce, profile_names, check_device, update_in_progress,
                 deps=None):
    ct = sys.modules['beacon_canonical_transport']
    lease_mod, stream = need('agent_phone_lease'), need('agent_phone_stream')
    deps = deps or Deps(mobile)
    cache, sockets, jobs = ct.TicketCache(capacity=16), threading.BoundedSemaphore(VIEW_SOCKETS), Jobs()
    router = APIRouter(route_class=CodedRoute)

    async def bounded(fn, *args, seconds=CALL_SECONDS):
        try:
            return await asyncio.wait_for(asyncio.to_thread(fn, *args), timeout=seconds)
        except HTTPException:
            raise
        except asyncio.TimeoutError:
            raise HTTPException(503, BUSY) from None

    async def refuse_while_updating():
        if await bounded(update_in_progress):
            raise HTTPException(503, UPDATING)

    def linked_device(request, owner, provider, profile):
        try:
            mobile.touch_device(owner, provider, profile, request.headers.get('x-beacon-device-id'), True, True)
        except LookupError as exc:
            raise HTTPException(403, str(exc)) from None
        return request.headers['x-beacon-device-id']

    def phone_of(profile, need_installed=True):
        """(home, pointer, folder) of this Hermes home's phone, for ``profile``, which must be its owning profile."""
        home = deps.home()
        pointer = read_pointer(home)
        if not pointer:
            if need_installed:
                raise HTTPException(404, NOT_SET_UP)
            return home, None, None
        if pointer.get('profile') != profile:
            raise HTTPException(403, OTHER_PROFILE)
        folder = Path(pointer['folder'])
        if need_installed and not (folder / 'home' / 'avd' / f'{AVD_NAME}.avd' / 'config.ini').is_file():
            raise HTTPException(404, NOT_SET_UP)
        return home, pointer, folder

    facts_by_folder = {}

    def facts_for(folder):
        with jobs.lock:
            return facts_by_folder.setdefault(str(folder), Facts())

    def refresh_facts(facts, host):
        """Reads the phone's facts on a thread (never in the request)."""
        def read():
            with contextlib.suppress(Exception):  # health: allow BLE001 -- the phone stopped meanwhile: the last facts stay
                facts.read(host.adb(host.state()))
        threading.Thread(target=read, name='beacon-agent-phone-facts', daemon=True).start()

    def helper_word(values, folder):
        """(word, line), Root's values (17 Nov): 'ready' = on the phone, current, and Android's autofill service; 'update' = older
        than this Beacon's pin; 'off' = on the phone but not the autofill service (Turn on sets it again); 'missing' = not on the
        phone (with L1a's plain line: no pin here, or not downloaded yet); None = not read yet. What the phone says wins; before
        a read, the phone folder's record (L1a's helper_status) decides only 'missing'."""
        known = deps.helper(folder) or {}
        installed = values.get('helper_installed')
        missing = ('missing', known.get('line'), known.get('reason') or reason_for(known.get('line')))
        if installed is None:
            return missing if known.get('state') and not known.get('on_phone') else (None, None, None)
        if not installed:
            return missing
        pin = deps.helper_pin()
        if pin and (values.get('helper_version') or 0) < int(pin.get('version_code') or 0):
            return 'update', None, None
        return ('ready', None, None) if values.get('autofill') is True else ('off', None, None)

    def status_of(profile, viewer):
        home = deps.home()
        pointer = read_pointer(home)
        out = {'control_driver': 'phonedriver', 'installed': False, 'profile': None, 'yours': False, 'phone': 'off', 'lease': None, 'warning': None,
               'warning_reason': None, 'error': None, 'error_reason': None, 'error_args': None, 'install': dict(jobs.install),
               'setup': {'google': None, 'chrome': None, 'chrome_autofill': None, 'logins': None}, 'storage_bytes': None,
               'helper': None, 'helper_line': None, 'helper_reason': None, 'in_use': None}
        with contextlib.suppress(Exception):  # health: allow BLE001 -- the developer-tools line is a hint
            out['warning'] = deps.developer_tools()
            out['warning_reason'] = 'check.developer_tools' if out['warning'] else None
        if not pointer:
            return out
        folder = Path(pointer['folder'])
        installed = (folder / 'home' / 'avd' / f'{AVD_NAME}.avd' / 'config.ini').is_file()
        out.update(installed=installed, profile=pointer.get('profile'), yours=pointer.get('profile') == profile)
        lease = lease_mod.PhoneLease(folder)
        current = lease.get()
        out['lease'] = lease_mod.public_view(current, viewer)
        with jobs.lock:
            out['error'], out['error_reason'] = jobs.error, jobs.error_reason
            busy = jobs.phone
        running = False
        host = None
        if installed:
            with contextlib.suppress(Exception):  # health: allow BLE001 -- a phone whose state can't be read is off
                host = deps.host(folder, lease)
                running = bool(host.running())
        out['phone'] = busy or ('on' if running else 'off')
        facts = facts_for(folder)
        # Not while a start runs (EMU-JOIN live, 17 Nov): a read during boot, before first start installs the helper, said
        # 'missing' for a minute after the phone came on. A finished start asks for a fresh read (run_start).
        if running and not busy and facts.due():
            refresh_facts(facts, host)
        if folder.is_dir() and facts.storage_due():
            threading.Thread(target=facts.measure, args=(folder,), name='beacon-agent-phone-size', daemon=True).start()
        values, storage = facts.snapshot()
        out['setup'] = {'google': values.get('google'), 'chrome': values.get('chrome'),
                        'chrome_autofill': values.get('chrome_autofill'), 'logins': deps.logins(pointer.get('profile'))}
        out['storage_bytes'] = storage
        if installed:
            with contextlib.suppress(Exception):  # health: allow BLE001 -- unreadable reads as not known
                out['helper'], out['helper_line'], out['helper_reason'] = helper_word(values, folder)
        if installed:
            try:
                recent = time.time() - (folder / 'run' / 'agent-activity').stat().st_mtime < IN_USE_SECONDS
            except OSError:
                recent = False
            out['in_use'] = bool(running and current.holder == lease_mod.AGENT and recent)
        return out

    def viewer_for(owner, provider, request):
        return lease_mod.viewer_id(owner, provider, request.headers.get('x-beacon-device-id') or '')

    # -- the phone ------------------------------------------------------------------------------------------------------------
    @router.get('/mobile/agent-phone')
    async def status(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        await bounded(deps.keep_dashboard, deps.home(), dashboard_address(request))
        value = await bounded(status_of, profile, viewer_for(owner, provider, request))
        if value['yours'] and value['installed']:
            def latest():
                _, _, folder = phone_of(profile)
                return task_store(folder, owner, provider, profile).latest()
            summary = await bounded(latest)
            if summary:
                value['driver_task'] = summary
        return JSONResponse(value, headers=NO_STORE)

    def task_store(folder, owner, provider, profile):
        return need('phonedriver_mobile').Tasks(folder, owner, provider, profile, need('agent_phone_approvals'))

    @router.post('/mobile/agent-phone/task')
    async def submit_phone_task(request: Request):
        await refuse_while_updating()
        owner, provider, profile, _ = await identity_async(request)
        payload = await body(request, 28_672)
        if not isinstance(payload, dict) or set(payload) != {'request_id', 'task'}:
            raise HTTPException(400, 'Expected request_id and task')
        tools = need('agent_phone_tools')
        try:
            args = tools.validate({'task': payload['task']})
        except ValueError:
            raise HTTPException(400, 'Invalid phone task') from None
        device = await bounded(linked_device, request, owner, provider, profile)
        expires = getattr(getattr(request.state, 'session', None), 'expires_at', None)
        if type(expires) is not int or expires <= time.time():
            raise HTTPException(401, 'Verified native session required')
        _, _, folder = await bounded(phone_of, profile)
        binding = dict(owner=owner, provider=provider, profile=profile, device=device, folder=str(folder), expires=expires)
        # Retain only device/profile metadata for revalidation; the request carries credentials and the original body.
        subject = SimpleNamespace(headers={'x-beacon-device-id': device}, query_params=request.query_params)
        def authorize():
            revalidate(subject, binding)
            return owner, profile
        def admit():
            authorize()
            if not need('agent_phone_approvals').policy(owner)['enabled']:
                raise HTTPException(403, 'Phone use is turned off')
            current = lease_mod.PhoneLease(folder).get()
            if current.holder != lease_mod.AGENT:
                raise HTTPException(409, 'The owner has the phone')
            return current.epoch
        driver = need('phonedriver')
        task_id = payload['request_id']
        def execute(epoch, cancelled, progress):
            step = tools.Call(args, owner, profile, profile, folder, None, 'mobile-phone-' + task_id)
            step.epoch = epoch
            return driver.run(step, authorize=authorize, cancelled=cancelled, progress=progress)
        summary = await bounded(task_store(folder, owner, provider, profile).submit, task_id, args['task'],
                                admit, execute, driver.TASK_SECONDS)
        return JSONResponse(summary, status_code=202 if summary['state'] == 'running' else 200, headers=NO_STORE)

    @router.post('/mobile/agent-phone/task/{task_id}/cancel')
    async def cancel_phone_task(task_id: str, request: Request):
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', task_id):
            raise HTTPException(400, 'Invalid phone task cancellation')
        await bounded(linked_device, request, owner, provider, profile)
        _, _, folder = await bounded(phone_of, profile)
        summary = await bounded(task_store(folder, owner, provider, profile).cancel, task_id)
        return JSONResponse(summary, headers=NO_STORE)

    def run_start(folder, lease):
        try:
            deps.host(folder, lease).start()
            error = None
        except Exception as exc:  # health: allow BLE001 -- the host's errors are plain lines; anything else is the fixed one
            hostm = sibling('agent_phone_host')
            error = str(exc) if hostm is not None and isinstance(exc, hostm.PhoneError) else START_FAILED
            log.info('agent phone: start failed (%s)', type(exc).__name__)
        facts_for(folder).forget()  # the next status reads the phone as first start left it
        with jobs.lock:
            jobs.phone, jobs.error, jobs.error_reason = None, error, reason_for(error)

    @router.post('/mobile/agent-phone/start')
    async def start(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid start')
        await refuse_while_updating()
        home, _, folder = await bounded(phone_of, profile)
        await bounded(deps.keep_dashboard, home, dashboard_address(request))
        ok, line = await bounded(deps.start_gate, home, seconds=15.0)
        if not ok:
            return JSONResponse({'phone': 'off', 'line': line, 'reason': reason_for(line)}, status_code=409, headers=NO_STORE)
        lease = lease_mod.PhoneLease(folder)
        with jobs.lock:
            if jobs.phone:
                return JSONResponse({'phone': jobs.phone}, status_code=202, headers=NO_STORE)
        if await bounded(lambda: deps.host(folder, lease).running()):
            return JSONResponse({'phone': 'on'}, headers=NO_STORE)
        with jobs.lock:
            if jobs.phone:
                return JSONResponse({'phone': jobs.phone}, status_code=202, headers=NO_STORE)
            jobs.phone, jobs.error, jobs.error_reason = 'starting', None, None
        threading.Thread(target=run_start, args=(folder, lease), name='beacon-agent-phone-start', daemon=True).start()
        return JSONResponse({'phone': 'starting'}, status_code=202, headers=NO_STORE)

    def run_stop(folder, lease):
        try:
            deps.host(folder, lease).stop(reason='owner')
        except Exception as exc:  # health: allow BLE001 -- the phone page shows the state it reads next
            log.info('agent phone: stop failed (%s)', type(exc).__name__)
        with contextlib.suppress(OSError):
            lease.release()  # nobody drives a phone that is off; an open view ends with "stopped"
        with jobs.lock:
            jobs.phone = None

    @router.post('/mobile/agent-phone/stop')
    async def stop(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid stop')
        _, _, folder = await bounded(phone_of, profile)
        lease = lease_mod.PhoneLease(folder)
        with jobs.lock:
            if jobs.phone:
                return JSONResponse({'phone': jobs.phone}, status_code=409, headers=NO_STORE)
            lease.interrupt()
            jobs.phone = 'stopping'
        threading.Thread(target=run_stop, args=(folder, lease), name='beacon-agent-phone-stop', daemon=True).start()
        return JSONResponse({'phone': 'stopping'}, status_code=202, headers=NO_STORE)

    @router.post('/mobile/agent-phone/hand-back')
    async def hand_back(request: Request):
        """"Done" when the view's socket is already gone: the phone goes back to the agent at once (§4.3)."""
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid hand back')
        device = await bounded(linked_device, request, owner, provider, profile)
        _, _, folder = await bounded(phone_of, profile)
        lease = lease_mod.PhoneLease(folder)
        viewer = lease_mod.viewer_id(owner, provider, device)

        def give_back():
            current = lease.release(viewer)
            if current.holder == lease_mod.HUMAN and current.viewer_id == 'unreadable-lease':
                current = lease.release()  # a damaged lease file is repaired by the owner's own hand-back
            return lease_mod.public_view(current, viewer)
        return JSONResponse({'lease': await bounded(give_back)}, headers=NO_STORE)

    # -- the setup view ---------------------------------------------------------------------------------------------------------
    @router.post('/mobile/agent-phone/view-ticket')
    async def view_ticket(request: Request):
        await refuse_while_updating()
        owner, provider, profile = await bounded(ct.authorize_request, request, mobile, dashboard_identity, enforce, profile_names,
                                                 check_device)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid ticket request')
        device = await bounded(linked_device, request, owner, provider, profile)
        session = request.state.session
        expires = getattr(session, 'expires_at', None)
        if type(expires) is not int or expires <= time.time():
            raise HTTPException(401, 'Verified native session required')
        _, _, folder = await bounded(phone_of, profile)
        binding = {'owner': owner, 'provider': provider, 'profile': profile, 'device': device, 'expires': expires,
                   'folder': str(folder), 'viewer': lease_mod.viewer_id(owner, provider, device)}
        try:
            ticket = cache.issue(binding, loop=asyncio.get_running_loop())
        except ValueError:
            raise HTTPException(503, BUSY) from None
        threading.Thread(target=warm_view, name='beacon-agent-phone-warm', daemon=True).start()
        expires_in = min(cache.remaining(ticket), int(expires - time.time()))
        return JSONResponse({'ticket': ticket, 'path': VIEW_PATH, 'subprotocol': stream.SUBPROTOCOL, 'profile': profile,
                             'expires_in': expires_in}, headers=NO_STORE)

    def warm_view():
        """The first view in a process: Pillow's JPEG encoder and the vendored h2 load before the socket opens."""
        with contextlib.suppress(Exception):  # health: allow BLE001 -- warming is best effort
            stream.warm()
            grpc = sibling('agent_phone_grpc')
            if grpc is not None:
                grpc.EmulatorGrpc(1, None)  # loads the vendored h2; connects nothing

    def revalidate(socket, binding, at_accept=True):
        """The ticket's binding still holds: owner, linked device (not removed), profile, the phone's owning profile and folder
        (/gateway/ws's revalidation plus the device and phone checks). The session's expiry is checked at accept only, as
        /gateway/ws does: it is the access token's own expiry, which Hermes refreshes under an open view (OAuth sign-ins can be
        short), and a setup session must not end in the middle of a Google sign-in for it."""
        ct.revalidate_binding(socket, binding if at_accept else dict(binding, expires=float('inf')), mobile, profile_names,
                              check_device)
        try:
            mobile.touch_device(binding['owner'], binding['provider'], binding['profile'], binding['device'], False, True)
        except LookupError:
            raise HTTPException(403, 'Device removed') from None
        _, _, folder = phone_of(binding['profile'])
        if str(folder) != binding['folder']:
            raise HTTPException(403, 'The phone changed')

    @router.websocket('/agent-phone/view')
    async def view(socket: WebSocket):
        from hermes_cli.web_server_boundary import ws_client_reason, ws_host_origin_reason
        protocols = [part.strip() for value in socket.headers.getlist('sec-websocket-protocol') for part in value.split(',')
                     if part.strip()]
        tickets = [value[len('beacon-ticket.'):] for value in protocols if value.startswith('beacon-ticket.')]
        if (protocols.count(stream.SUBPROTOCOL) != 1 or len(tickets) != 1 or len(protocols) != 2
                or ws_client_reason(socket, socket.app.state) is not None or ws_host_origin_reason(socket, socket.app.state) is not None):
            await socket.close(code=stream.CLOSE_POLICY)
            return
        binding = cache.consume(tickets[0])
        if binding is None or not sockets.acquire(blocking=False):
            await socket.close(code=stream.CLOSE_POLICY)
            return
        try:
            try:
                if await bounded(update_in_progress):
                    await socket.close(code=stream.CLOSE_AGAIN)
                    return
                await bounded(revalidate, socket, binding)
            except Exception:  # health: allow BLE001 -- any refusal closes before accept; nothing of it is sent
                await socket.close(code=stream.CLOSE_POLICY)
                return
            folder = Path(binding['folder'])
            lease = lease_mod.PhoneLease(folder)
            try:
                host = deps.host(folder, lease)
                state = await bounded(host.state)
                running = await bounded(host.running, state)
            except Exception:  # health: allow BLE001 -- the host's module is missing or its state unreadable
                await socket.close(code=stream.CLOSE_AGAIN)
                return
            await socket.accept(subprotocol=stream.SUBPROTOCOL)
            if not running:
                await socket.send_text(json.dumps({'t': 'bye', 'reason': 'off'}))
                await socket.close(code=stream.CLOSE_DONE)
                return
            viewer = binding['viewer']
            await asyncio.to_thread(host.cleanup_frames)
            await asyncio.to_thread(lease.acquire, viewer, 'setup')
            session = None

            def notice(kind, payload):
                line = payload.get('line')
                if session is not None and kind in ('saved', 'needs_you') and isinstance(line, str):
                    session.notice(kind, line, payload.get('reason'), notice_args(payload))
            port = (state.get('ports') or {}).get('console', 'phone')
            guard = deps.guard()  # L3's agent_phone_guard: Android's debugging prompt answered No while the owner holds the phone
            session = stream.ViewSession(
                socket, lease, viewer,
                open_frames=lambda on_frame: stream.MmapFrames(lambda: host.grpc(state, timeout=15.0).connect(),
                                                               stream.frame_directory_for_view(host.folder), port,
                                                               on_frame=on_frame),
                open_control=lambda: host.grpc(state, timeout=10.0).connect(),
                open_channel=lambda: deps.channel(host, state, notice),
                keyboard_probe=lambda: keyboard_shown(host.adb(state).shell(KEYBOARD_PROBE, timeout=5).stdout),
                revalidate=lambda: revalidate(socket, binding, at_accept=False), note_activity=host.note_activity,
                phone_running=lambda: host.running(), update_in_progress=update_in_progress, size=device_size(folder),
                phone_stopping=lambda: jobs.phone == 'stopping',  # set by the stop route before the host stops (L5 live)
                debug_prompt=(lambda: guard.debug_prompt(host.adb(state))) if guard is not None else None,
                deny_prompt=(lambda: guard.deny(host.adb(state))) if guard is not None else None)
            await session.run()
        except Exception as exc:  # health: allow BLE001 -- the view ends; the app re-tickets
            log.info('agent phone: view failed (%s)', type(exc).__name__)
            with contextlib.suppress(Exception):  # health: allow BLE001 -- the socket may already be gone
                await socket.close(code=stream.CLOSE_FAILED)
        finally:
            sockets.release()

    # -- the install flow (§3) ---------------------------------------------------------------------------------------------------
    def fresh(at):
        return at is not None and time.monotonic() - at < KEPT_SECONDS

    @router.post('/mobile/agent-phone/checks')
    async def checks(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid checks')
        checks_mod, install = need('agent_phone_checks'), need('agent_phone_install')
        home, pointer, _ = await bounded(phone_of, profile, False)
        folder = Path(pointer['folder']) if pointer else install.phone_folder(home)
        dashboard = dashboard_address(request)
        result = await bounded(checks_mod.run_checks, folder, dashboard, seconds=CHECKS_SECONDS)
        with jobs.lock:
            jobs.checks, jobs.checks_at = dict(result, dashboard=dashboard, profile=profile), time.monotonic()
        return JSONResponse({k: v for k, v in result.items() if k in ('ok', 'rows', 'needs_continue')}, headers=NO_STORE)

    @router.get('/mobile/agent-phone/terms')
    async def terms(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        install = need('agent_phone_install')
        with jobs.lock:
            checked = jobs.checks if fresh(jobs.checks_at) else None
        if not checked or checked.get('profile') != profile or not checked.get('ok'):
            return refused(409, CHECK_FIRST)  # §3: nothing is fetched from Google before the checks pass
        home, pointer, _ = await bounded(phone_of, profile, False)
        folder = Path(pointer['folder']) if pointer else install.phone_folder(home)
        try:
            found = await bounded(install.terms, folder, seconds=TERMS_SECONDS)
        except install.InstallError as exc:
            return JSONResponse({'line': exc.line, 'reason': exc.reason, 'args': exc.args_, 'fix': exc.fix,
                                 'fix_reason': exc.fix_reason, 'retry': exc.retry}, status_code=502, headers=NO_STORE)
        with jobs.lock:
            jobs.terms, jobs.terms_at = found, time.monotonic()
        return JSONResponse({'licences': [{'id': item['id'], 'text': item['text']} for item in found['licences']],
                             'sizes': found['sizes']}, headers=NO_STORE)

    def install_progress(event=None, **fields):
        with jobs.lock:
            state = jobs.install
            if event == 'plan':
                state['total_bytes'] = int(fields.get('download_bytes') or 0)
                state['done_bytes'] = 0
            elif event == 'phase':
                state['phase'] = fields.get('phase')
            elif event in ('bytes', 'resume', 'restart') and isinstance(fields.get('bytes'), int):
                state['done_bytes'] = max(0, int(state.get('done_bytes') or 0) + fields['bytes'])
            if isinstance(fields.get('line'), str):
                state['line'], state['reason'] = fields['line'], reason_for(fields['line'])

    def run_install(install, folder, checks_value, home, profile, dashboard, control):
        try:
            grpc = sibling('agent_phone_grpc')
            proto_ok = (lambda path: not grpc.check_proto(Path(path).read_text(encoding='utf-8'))) if grpc is not None else None
            install.run_install(folder, checks=checks_value, hermes_home=home, profile=profile, dashboard=dashboard, control=control,
                                progress=install_progress, proto_ok=proto_ok)
            outcome = {'state': 'done'}
        except install.Paused:
            outcome = {'state': 'paused'}
        except install.Cancelled:
            outcome = {'state': 'cancelled'}
        except install.InstallError as exc:
            outcome = {'state': 'failed', 'line': exc.line, 'reason': exc.reason, 'args': exc.args_, 'fix': exc.fix,
                       'fix_reason': exc.fix_reason, 'retry': exc.retry}
        except Exception as exc:  # health: allow BLE001 -- anything else is the plain retry line
            log.info('agent phone: install failed (%s)', type(exc).__name__)
            outcome = {'state': 'failed', 'line': INSTALL_FAILED, 'reason': API_REASONS[INSTALL_FAILED], 'args': None, 'fix': None,
                       'fix_reason': None, 'retry': True}
        with jobs.lock:
            jobs.install.update(outcome)
            if outcome['state'] != 'failed':
                for key in ('fix', 'fix_reason', 'reason', 'args'):
                    jobs.install.pop(key, None)
            jobs.thread = None

    def begin_install(install, folder, home, profile, checks_value):
        with jobs.lock:
            if jobs.thread is not None:
                return False
            jobs.control = jobs.control or install.Control()
            jobs.control.resume()
            jobs.install = {'state': 'running', 'phase': None, 'done_bytes': jobs.install.get('done_bytes'),
                            'total_bytes': jobs.install.get('total_bytes'), 'line': None, 'reason': None, 'retry': False}
            jobs.thread = threading.Thread(target=run_install, name='beacon-agent-phone-install', daemon=True,
                                           args=(install, folder, checks_value, home, profile, checks_value.get('dashboard'),
                                                 jobs.control))
            jobs.thread.start()
        return True

    @router.post('/mobile/agent-phone/install')
    async def install_start(request: Request):
        """The owner's Accept of the terms shown (and Continue on amber check rows), then the download (§3 steps 3-6)."""
        owner, provider, profile, _ = await identity_async(request)
        value = await body(request, 4096)
        if (set(value) - {'accept', 'continue'} or not isinstance(value.get('accept'), list)
                or not all(isinstance(i, str) for i in value['accept']) or type(value.get('continue', False)) is not bool):
            raise HTTPException(400, 'Invalid install')
        await refuse_while_updating()
        install, checks_mod = need('agent_phone_install'), need('agent_phone_checks')
        device = await bounded(linked_device, request, owner, provider, profile)
        home, pointer, _ = await bounded(phone_of, profile, False)
        folder = Path(pointer['folder']) if pointer else install.phone_folder(home)
        with jobs.lock:
            checked, shown = (jobs.checks if fresh(jobs.checks_at) else None), (jobs.terms if fresh(jobs.terms_at) else None)
        if not checked or checked.get('profile') != profile:
            return refused(409, CHECK_FIRST)
        if not shown or sorted(value['accept']) != sorted(item['id'] for item in shown['licences']):
            return refused(409, TERMS_FIRST)
        checks_value = checks_mod.continue_warnings(checked) if value.get('continue') else checked
        if not install.checks_passed(checks_value):
            return refused(409, CHECK_FIRST)
        with jobs.lock:
            jobs.checks_value = checks_value
        # Google's terms as the server fetched and showed them, never text from the app; accepted by this owner's phone.
        await bounded(lambda: install.accept_licences(folder, shown['licences'], by=f'owner device {device[:8]}'))
        if not begin_install(install, folder, home, profile, checks_value):
            return JSONResponse(jobs.install, status_code=409, headers=NO_STORE)
        return JSONResponse(dict(jobs.install), status_code=202, headers=NO_STORE)

    @router.get('/mobile/agent-phone/install')
    async def install_status(request: Request):
        await identity_async(request)
        with jobs.lock:
            return JSONResponse(dict(jobs.install), headers=NO_STORE)

    @router.post('/mobile/agent-phone/install/{action}')
    async def install_control(action: str, request: Request):
        owner, provider, profile, _ = await identity_async(request)
        if action not in ('pause', 'resume', 'cancel') or await body(request, 256):
            raise HTTPException(400, 'Invalid install action')
        install = need('agent_phone_install')
        if action == 'resume':
            await refuse_while_updating()
            home, pointer, _ = await bounded(phone_of, profile, False)
            folder = Path(pointer['folder']) if pointer else install.phone_folder(home)
            with jobs.lock:
                checks_value = jobs.checks_value
                stopped = jobs.install.get('state') in ('paused', 'failed')
            if not checks_value or not stopped or not install.checks_passed(checks_value):
                return refused(409, NOTHING_TO_RESUME)
            begin_install(install, folder, home, profile, checks_value)
            return JSONResponse(dict(jobs.install), status_code=202, headers=NO_STORE)
        with jobs.lock:
            control, running = jobs.control, jobs.thread is not None
            if control is not None:
                (control.pause if action == 'pause' else control.cancel)()
        if action == 'cancel' and not running:
            home, pointer, _ = await bounded(phone_of, profile, False)
            folder = Path(pointer['folder']) if pointer else install.phone_folder(home)
            await bounded(install.clean_partial, folder)
            with jobs.lock:
                jobs.install, jobs.control, jobs.checks_value = {'state': 'cancelled'}, None, None
        return JSONResponse(dict(jobs.install), headers=NO_STORE)

    @router.post('/mobile/agent-phone/remove')
    async def remove(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        value = await body(request, 256)
        if set(value) - {'delete_logins'} or type(value.get('delete_logins', False)) is not bool:
            raise HTTPException(400, 'Invalid remove')
        await refuse_while_updating()
        install = need('agent_phone_install')
        if not hasattr(install, 'remove_phone'):
            raise HTTPException(503, UPDATE_LINE)
        home, pointer, folder = await bounded(phone_of, profile, False)
        if folder is None:
            raise HTTPException(404, NOT_SET_UP)
        try:  # L1a's remove_phone(home, delete_logins=False, *, profile=None): keeper, phone, folder, pointer, sidecar
            result = await bounded(lambda: install.remove_phone(home, delete_logins=value.get('delete_logins', False),
                                                                profile=pointer.get('profile')), seconds=600.0)
        except ValueError:
            return refused(409, NOT_ITS_FOLDER)
        with jobs.lock:
            jobs.install, jobs.control, jobs.error, jobs.helper = {'state': 'idle'}, None, None, None
            facts_by_folder.pop(str(folder), None)
        result = result if isinstance(result, dict) else {}
        freed = result.get('freed_bytes')
        deleted = result.get('logins_deleted')
        return JSONResponse({'removed': bool(result.get('removed')), 'freed_bytes': freed if isinstance(freed, int) else None,
                             'logins_deleted': deleted if isinstance(deleted, int) else None}, headers=NO_STORE)

    # -- the helper (follow-up 1) ------------------------------------------------------------------------------------------------
    def run_helper_update(folder, lease, pin):
        line = None
        try:
            host = deps.host(folder, lease)
            hostm = need('agent_phone_host')
            install = need('agent_phone_install')
            try:
                apk = deps.fetch_helper(folder)
            except install.InstallError as exc:
                apk, line = None, exc.line
            if apk is not None:
                line = install_helper(host.adb(host.state()), apk, pin, hostm.AUTOFILL_SERVICE, hostm.CODE_LISTENER)
                if line is None:
                    host.helper_installed(pin['sha256'])
            elif line is None:
                line = HELPER_DAMAGED
            if line is None:
                facts = facts_for(folder)
                facts.wait_idle()  # a read that began before the install would report the old helper
                facts.read(host.adb(host.state()))
        except Exception as exc:  # health: allow BLE001 -- the owner sees the plain line and can try again
            log.info('agent phone: helper update failed (%s)', type(exc).__name__)
            line = HELPER_FAILED
        with jobs.lock:
            jobs.helper, jobs.error, jobs.error_reason = None, line, reason_for(line)

    @router.post('/mobile/agent-phone/helper/update')
    async def helper_update(request: Request):
        """Reinstalls the pinned helper (sha256 and signer checked) through PhoneAdb; the status's ``helper`` moves to 'ready'."""
        owner, provider, profile, _ = await identity_async(request)
        if await body(request, 256):
            raise HTTPException(400, 'Invalid helper update')
        await refuse_while_updating()
        _, _, folder = await bounded(phone_of, profile)
        pin = await bounded(deps.helper_pin)
        if not pin:
            return JSONResponse({'helper': None, 'line': HELPER_NONE, 'reason': API_REASONS[HELPER_NONE]}, status_code=409,
                                headers=NO_STORE)
        lease = lease_mod.PhoneLease(folder)
        if not await bounded(lambda: deps.host(folder, lease).running()):
            return JSONResponse({'helper': None, 'line': TURN_ON_FIRST, 'reason': API_REASONS[TURN_ON_FIRST]}, status_code=409,
                                headers=NO_STORE)
        with jobs.lock:
            if jobs.helper is None:
                jobs.helper = 'updating'
                threading.Thread(target=run_helper_update, args=(folder, lease, pin), name='beacon-agent-phone-helper',
                                 daemon=True).start()
        return JSONResponse({'helper': 'updating'}, status_code=202, headers=NO_STORE)

    # -- approvals (L3, approvals-v1 §6) -------------------------------------------------------------------------------------
    def approval_id(value):
        if not isinstance(value, str) or not APPROVAL_ID.fullmatch(value):
            raise HTTPException(404, 'Approval not found')
        return value

    @router.get('/mobile/agent-phone/approvals')
    async def approvals(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        module = need('agent_phone_approvals')
        await bounded(linked_device, request, owner, provider, profile)
        return JSONResponse({'approvals': await bounded(module.open_records, owner)}, headers=NO_STORE)

    @router.post('/mobile/agent-phone/approvals/{record_id}/decision')
    async def approval_decision(record_id: str, request: Request):
        value = await body(request, 256)
        if set(value) not in ({'approved'}, {'approved', 'code'}) or type(value['approved']) is not bool:
            raise HTTPException(400, 'Invalid decision')
        owner, provider, profile, _ = await identity_async(request)
        module = need('agent_phone_approvals')
        device = await bounded(linked_device, request, owner, provider, profile)
        try:
            approved = await bounded(module.decide, owner, approval_id(record_id), value['approved'], device, value.get('code'))
        except LookupError:
            raise HTTPException(404, 'Approval not found') from None
        except ValueError as exc:
            reason = str(exc)
            raise HTTPException(409 if reason in ('expired', 'settled') else 400, reason if reason in ('expired', 'settled')
                                else 'Invalid decision') from None
        return JSONResponse({'approved': approved}, headers=NO_STORE)

    @router.get('/mobile/agent-phone/activity')
    async def activity(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        module = need('agent_phone_approvals')
        raw = request.query_params.get('limit', '50')
        if not raw.isdigit() or not 1 <= int(raw) <= 200:
            raise HTTPException(400, 'Invalid limit')
        return JSONResponse({'activity': await bounded(module.activity, owner, int(raw))}, headers=NO_STORE)

    @router.get('/mobile/agent-phone/policy')
    async def policy(request: Request):
        owner, provider, profile, _ = await identity_async(request)
        module = need('agent_phone_approvals')
        return JSONResponse(await bounded(module.policy, owner), headers=NO_STORE)

    @router.put('/mobile/agent-phone/policy')
    async def set_policy(request: Request):
        value = await body(request, 256)
        if not value or set(value) - {'enabled', 'ask'}:
            raise HTTPException(400, 'Invalid policy')
        owner, provider, profile, _ = await identity_async(request)
        module = need('agent_phone_approvals')
        try:
            return JSONResponse(await bounded(lambda: module.set_policy(owner, **value)), headers=NO_STORE)
        except ValueError:
            raise HTTPException(400, 'Invalid policy') from None

    return router
