"""The agent phone's routes (EMU-DESIGN §3, §4.2-§4.3, §7.1; approvals-v1 §6) against fakes of L1a, L1b, L9 and L3."""
import contextlib
import hashlib
import json
import os
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient
from starlette.websockets import WebSocketDisconnect

from test_mobile import harness  # noqa: F401  (the fixture)

PREFIX = '/api/plugins/beacon'
SUB = 'beacon-agent-phone-v1'


class FakeGrpc:
    """L1b's EmulatorGrpc for one connection: control calls are recorded; a stream writes one frame into the MMAP file."""

    def __init__(self, host):
        self.host = host
        self.blocked = threading.Event()

    def cancel(self):
        self.blocked.set()

    def connect(self):
        return self

    def close(self):
        self.blocked.set()

    def screenshots(self, fmt, width=0, height=0, mmap_path=None):
        self.host.frame_paths.append(Path(mmap_path))
        with open(mmap_path, 'r+b') as fh:
            fh.write(bytes([40, 90, 200]) * (width * height))
        yield {'width': width, 'height': height, 'rotation': b'', 'image': b''}
        self.blocked.wait(20)

    def touch(self, touches, display=0):
        self.host.inputs.append(('touch', touches))

    def key(self, key=None, text=None, keycode=None, code_type=1, event_type=2):
        self.host.inputs.append(('key', key, event_type))


class FakeAdb:
    """L1b's PhoneAdb: shell (a list or a fixed script), install, session(). ``host.phone`` answers the fact scripts the way
    the B6 phone did (synthetic values only)."""

    def __init__(self, host):
        self.host = host

    def shell(self, args, timeout=60.0, input=None, binary=False):
        self.host.adb_calls.append(args if isinstance(args, str) else list(args))
        text = args if isinstance(args, str) else ' '.join(args)
        out = 'mInputShown=false' if 'dumpsys input_method' in text else ''
        for needle, key in (('dumpsys account', 'google'), ('com.android.chrome |', 'chrome'),
                            ('AutofillThirdPartyModeContentProvider', 'chrome_autofill'), ('dev.hermes.agentphone.helper |', 'helper'),
                            ('settings get secure autofill_service', 'autofill')):
            if needle in text:
                out = self.host.phone.get(key, '')
        if text.startswith('settings put secure autofill_service '):
            self.host.phone['autofill'] = text.rsplit(' ', 1)[-1]
        if text.startswith('pm uninstall'):
            self.host.phone['installed_signer'] = None
        return subprocess.CompletedProcess('<agent phone command>', 0, out, '')

    def install(self, apk, extra=('-r',), timeout=300.0):
        self.host.installs.append(Path(apk).name)
        if self.host.phone.get('installed_signer') not in (None, 'ours'):
            return subprocess.CompletedProcess('<agent phone command>', 1, 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]', '')
        self.host.phone.update(installed_signer='ours', helper=f"versionCode={self.host.phone.get('pinned_version', 121)}")
        return subprocess.CompletedProcess('<agent phone command>', 0, 'Success', '')

    @contextlib.contextmanager
    def session(self):
        self.host.sessions += 1
        yield self


class FakeHost:
    def __init__(self, real_host):
        self.folder = real_host.folder
        self.cleanup_frames = real_host.cleanup_frames
        self.is_running = True
        self.started = self.stopped = 0
        self.start_error = None
        self.start_gate_open = threading.Event()
        self.start_gate_open.set()
        self.inputs, self.adb_calls, self.frame_paths, self.installs = [], [], [], []
        self.activity = self.sessions = 0
        self.phone = {}

    def state(self):
        return {'running': self.is_running, 'ports': {'console': 5640, 'grpc': 8554}, 'pid': 1}

    def helper_installed(self, sha256):  # L1b: the setup side's install recorded as first start records its own
        self.helper_recorded = sha256

    def running(self, state=None):
        return self.is_running

    def grpc(self, state=None, timeout=15.0):
        return FakeGrpc(self)

    def adb(self, state=None):
        return FakeAdb(self)

    def note_activity(self):
        self.activity += 1

    def start(self):
        self.start_gate_open.wait(5)
        self.started += 1
        if self.start_error:
            raise self.start_error
        self.is_running = True

    def stop(self, reason='requested'):
        self.stopped += 1
        self.is_running = False


@pytest.fixture
def phone_api(harness, monkeypatch):  # noqa: F811
    from hermes_cli.dashboard_auth.base import Session
    api, client, cfg, tmp, app = harness
    cfg['profiles'] = ['default', 'other']
    apimod = sys.modules['beacon_agent_phone_api']
    lease_mod = apimod.need('agent_phone_lease')
    stream = apimod.need('agent_phone_stream')
    monkeypatch.setattr(stream, 'REVALIDATE_SECONDS', 0.1)
    monkeypatch.setattr(stream, 'LEASE_CHECK_SECONDS', 0.05)
    folder = tmp / 'phone-folder'
    (folder / 'home' / 'avd' / 'beacon_agent_phone.avd').mkdir(parents=True)
    (folder / 'home' / 'avd' / 'beacon_agent_phone.avd' / 'config.ini').write_text('hw.lcd.width=1080\nhw.lcd.height=2400\n')
    pointer = tmp / 'plugin-data' / 'beacon' / 'agent-phone.json'
    pointer.parent.mkdir(parents=True, exist_ok=True)
    pointer.write_text(json.dumps({'version': 1, 'folder': str(folder), 'profile': 'default', 'dashboard': 'http://127.0.0.1:9119'}))
    host = FakeHost(apimod.need('agent_phone_host').PhoneHost(folder, view_open=lease_mod.PhoneLease(folder).view_open))
    host.folder.run.mkdir(exist_ok=True)
    apimod.need('agent_phone_keeper').announce_frame_cleanup(folder)
    current = json.loads((host.folder.run / 'mmap-cleaner-v1.json').read_text())['launcher']
    host.folder.keeper_conf.write_text(json.dumps({'pid': current[0], 'create_time': current[1]}))
    gate = {'value': (True, None)}
    updating = {'value': False}

    class Deps(apimod.Deps):
        def home(self):
            return tmp

        def host(self, folder_, lease):
            assert Path(folder_) == folder
            return host

        def start_gate(self, home):
            assert Path(home) == tmp
            return gate['value']

        def developer_tools(self):
            return "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to."

        def channel(self, host_, state, notice):
            return None

    def session_for(seconds):
        return Session(user_id='owner', provider='basic', issuer='', email='', display_name='', org_id='',
                       expires_at=int(time.time()) + seconds, access_token='synthetic-token', refresh_token='')
    current = {'session': session_for(600)}
    monkeypatch.setattr(api.admin, 'update_in_progress', lambda: updating['value'])

    def client_for(router, bound_port=None):
        app2 = FastAPI()
        app2.state.auth_required = True
        if bound_port is not None:
            app2.state.bound_port = bound_port

        @app2.middleware('http')
        async def authenticated(request, call_next):
            request.state.session = current['session']
            return await call_next(request)
        app2.include_router(router, prefix=PREFIX)
        return TestClient(app2)

    def router_for(deps):
        return apimod.build_router(api.mobile, api.identity_async, api.body, api.dashboard_identity, api.enforce_request_policy,
                                   api._native_profile_names, api._check_device_request, lambda: updating['value'], deps=deps)
    phone_client = client_for(router_for(Deps(api.mobile)))
    device = api.mobile.link_device('owner', 'basic', 'Synthetic phone')['device_id']
    other_device = api.mobile.link_device('owner', 'basic', 'Second synthetic phone')['device_id']
    phone_client.headers['x-beacon-device-id'] = device
    return SimpleNamespace(api=api, apimod=apimod, client=phone_client, client_for=client_for, router_for=router_for, Deps=Deps,
                           current=current, session_for=session_for, plugin_client=client, folder=folder, host=host, gate=gate,
                           updating=updating, device=device, other_device=other_device, lease=lease_mod.PhoneLease(folder),
                           lease_mod=lease_mod, stream=stream, tmp=tmp, cfg=cfg)


def ticket(p, device=None, profile='default'):
    headers = {'x-beacon-device-id': device} if device else {}
    response = p.client.post(f'{PREFIX}/mobile/agent-phone/view-ticket?beacon_profile={profile}', json={}, headers=headers)
    assert response.status_code == 200, response.text
    return response.json()


def read_until(ws, stop):
    """Messages (dicts for text, bytes for frames) up to and including the first one ``stop`` accepts."""
    seen = []
    while True:
        message = ws.receive()
        if message['type'] == 'websocket.close':
            raise AssertionError(f'closed early after {seen}')
        value = json.loads(message['text']) if message.get('text') is not None else message.get('bytes')
        seen.append(value)
        if stop(value):
            return seen


def until(cond, timeout=5.0):
    deadline = time.monotonic() + timeout
    while not cond():
        if time.monotonic() > deadline:
            raise AssertionError('condition not met in time')
        time.sleep(0.01)


# -- mounted in plugin_api, importable without the other lanes -----------------------------------------------------------------

def test_plugin_api_mounts_the_routes_and_they_answer_without_the_other_lanes(harness, monkeypatch):  # noqa: F811
    api, client, cfg, tmp, app = harness
    # The joined tree has every sibling; a Beacon whose files are part-updated must still answer: hide them.
    apimod = sys.modules['beacon_agent_phone_api']
    hidden, real = ('agent_phone_install', 'agent_phone_checks', 'agent_phone_host', 'agent_phone_channel', 'agent_phone_approvals'), apimod.sibling
    monkeypatch.setattr(apimod, 'sibling', lambda name: None if name in hidden else real(name))
    status = client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default')
    assert status.status_code == 200 and status.json()['installed'] is False and status.json()['phone'] == 'off'
    assert client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={}).status_code == 404
    assert client.get(f'{PREFIX}/mobile/agent-phone/approvals?beacon_profile=default').status_code == 503
    assert client.post(f'{PREFIX}/mobile/agent-phone/checks?beacon_profile=default', json={}).status_code == 503


def test_plugin_api_notice_lines_for_approval_records(harness):  # noqa: F811
    api = harness[0]
    assert api.notice_preview('agent_phone_approval') == ('Approval needed', 'Open Beacon to review the request.')
    assert api._visible_notice({'kind': 'agent_phone_approval', 'conversation': 'agent-phone-' + 'a' * 32}, 'owner', 'basic',
                               'default', '') is True


def add_done_notice(api, reason, label):
    """L3's notify_done as it writes the row (L3-fu1 §5.5), and the activity row it names, with L3's table columns."""
    with api.mobile.LOCK, api.mobile.connect() as conn:
        conn.execute('CREATE TABLE IF NOT EXISTS agent_phone_activity(seq INTEGER PRIMARY KEY AUTOINCREMENT, owner TEXT, profile TEXT, '
                     'at REAL, app TEXT, action TEXT, label TEXT, approved INTEGER, outcome TEXT)')
        seq = conn.execute('INSERT INTO agent_phone_activity(owner,profile,at,app,action,label,approved,outcome) VALUES(?,?,?,?,?,?,?,?)',
                           ('owner', 'default', time.time(), 'Kanto Foods', 'tap', label, 1, 'done')).lastrowid
        notice = os.urandom(16).hex()
        conn.execute('INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason,activity_key) VALUES(?,?,?,?,?,?,?,?,?)',
                     (notice, 'owner', 'default', 'agent_phone_done', 'agent-phone-done-' + notice, time.time(), time.time() + 3600,
                      reason, f'agent_phone_activity:{seq}'))
    return notice


def test_the_done_notice_says_payment_done_or_sent_then_the_activity_label(harness, monkeypatch):  # noqa: F811
    api, client, cfg, tmp, app = harness
    assert api.notice_preview('agent_phone_done', 'payment') == ('Payment done', 'Open Beacon to see it.')
    assert api.notice_preview('agent_phone_done', 'send') == ('Sent', 'Open Beacon to see it.')
    assert api._visible_notice({'kind': 'agent_phone_done', 'conversation': 'agent-phone-done-' + 'a' * 32}, 'owner', 'basic',
                               'default', '') is True
    paid = add_done_notice(api, 'payment', 'Pay  £12.40\nnow')
    sent_notice = add_done_notice(api, 'send', 'Send to Sam')
    events = {e['event_id']: e for e in client.get('/api/plugins/beacon/mobile/notifications/events').json()['events']}
    assert events[paid]['title'] == 'Payment done' and events[sent_notice]['title'] == 'Sent'
    detail = client.get('/api/plugins/beacon/mobile/notifications/events/' + paid).json()
    assert detail['preview'] == {'title': 'Payment done', 'body': 'Pay £12.40 now'}
    assert detail['activity_ref'].startswith('agent_phone_activity:')
    assert client.get('/api/plugins/beacon/mobile/notifications/events/' + sent_notice).json()['preview'] == {'title': 'Sent',
                                                                                                            'body': 'Send to Sam'}
    # The push says only which: the label stays on Hermes until the phone reads the detail.
    cfg['firebase_credentials'] = 'fixture-private-file'
    from test_mobile import phone
    assert client.post('/api/plugins/beacon/mobile/notifications/targets', json={'id': 'device', 'token': 'fixture-fcm-token-' + 30 * 'x'},
                       headers=phone(client)).status_code == 200
    pushed = []
    messaging = SimpleNamespace(Message=lambda **kwargs: kwargs, AndroidConfig=lambda **kwargs: kwargs,
                                send=lambda message, app=None: pushed.append(message['data']))
    monkeypatch.setitem(sys.modules, 'firebase_admin', SimpleNamespace(get_app=lambda name: object(), credentials=SimpleNamespace(),
                                                                       messaging=messaging))
    api.push('owner', 'default')
    by_event = {d['event_id']: d for d in pushed}
    assert (by_event[paid]['title'], by_event[paid]['body']) == ('Payment done', 'Open Beacon to see it.')
    assert by_event[sent_notice]['title'] == 'Sent' and 'Sam' not in json.dumps(by_event[sent_notice]) and '12.40' not in json.dumps(pushed)


def test_the_done_label_is_none_without_l3s_table_or_its_row(phone_api):
    api = phone_api.api
    assert phone_api.apimod.done_label(api.mobile.connect, 'owner', 'agent_phone_activity:1') is None  # no L3 table
    assert phone_api.apimod.done_label(api.mobile.connect, 'owner', 'agent_phone_activity:1; DROP') is None
    assert phone_api.apimod.done_label(api.mobile.connect, 'owner', '') is None


def test_sweep_approvals_calls_l3_and_never_raises(phone_api, monkeypatch):
    calls = []
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_approvals', SimpleNamespace(sweep=lambda: calls.append(1)))
    phone_api.apimod.sweep_approvals()
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_approvals', SimpleNamespace(sweep=lambda: 1 / 0))
    phone_api.apimod.sweep_approvals()
    assert calls == [1]


# -- status, start, stop, hand back ----------------------------------------------------------------------------------------------

def test_status_says_what_the_page_needs_and_leaks_nothing(phone_api):
    response = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default')
    value = response.json()
    assert response.headers['cache-control'] == 'no-store'
    assert value['installed'] and value['yours'] and value['profile'] == 'default' and value['phone'] == 'on'
    assert value['lease'] == {'holder': 'agent', 'you': False, 'epoch': 0, 'away': False}
    assert value['warning'].startswith('Close Android developer tools')
    text = response.text
    for leak in (str(phone_api.folder), str(phone_api.folder).replace('\\', '\\\\'), '5640', '8554', 'synthetic-token'):
        assert leak not in text
    other = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=other').json()
    assert other['installed'] and not other['yours']


def test_start_runs_the_start_checks_first_and_refuses_on_a_failed_one(phone_api):
    phone_api.host.is_running = False
    phone_api.gate['value'] = (False, 'Turn on Hermes sign-in first.')
    refused = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={})
    assert refused.status_code == 409 and refused.json() == {'phone': 'off', 'line': 'Turn on Hermes sign-in first.',
                                                         'reason': 'check.sign_in_off'}
    assert phone_api.host.started == 0
    phone_api.gate['value'] = (True, None)
    phone_api.host.start_gate_open.clear()
    first = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={})
    again = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={})
    assert first.status_code == again.status_code == 202 and first.json() == {'phone': 'starting'}
    assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['phone'] == 'starting'
    phone_api.host.start_gate_open.set()
    until(lambda: phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['phone'] == 'on')
    assert phone_api.host.started == 1


def test_a_failed_start_shows_its_plain_line(phone_api, monkeypatch):
    hostm = SimpleNamespace(PhoneError=type('PhoneError', (Exception,), {}))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_host', hostm)
    phone_api.host.is_running = False
    phone_api.host.start_error = hostm.PhoneError("The phone didn't start.")
    phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={})
    until(lambda: phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['error'])
    assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['error'] == "The phone didn't start."


def test_only_the_owning_profile_starts_or_stops_it(phone_api):
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=other', json={}).status_code == 403
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/stop?beacon_profile=other', json={}).status_code == 403
    assert phone_api.host.started == phone_api.host.stopped == 0


def test_stop_stops_the_phone_and_frees_the_lease(phone_api):
    phone_api.lease.acquire('v-someone')
    response = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/stop?beacon_profile=default', json={})
    assert response.status_code == 202
    until(lambda: phone_api.host.stopped == 1 and phone_api.lease.get().holder == 'agent')


def test_hand_back_frees_only_this_phones_lease(phone_api):
    mine = phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.device)
    theirs = phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.other_device)
    phone_api.lease.acquire(theirs)
    kept = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/hand-back?beacon_profile=default', json={})
    assert kept.json()['lease']['holder'] == 'human' and not kept.json()['lease']['you']
    phone_api.lease.acquire(mine)
    back = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/hand-back?beacon_profile=default', json={})
    assert back.json()['lease'] == {'holder': 'agent', 'you': False, 'epoch': 3, 'away': False}


def test_hand_back_repairs_a_damaged_lease(phone_api):
    (phone_api.folder / 'run').mkdir(exist_ok=True)
    (phone_api.folder / 'run' / 'lease.json').write_text('{broken')
    back = phone_api.client.post(f'{PREFIX}/mobile/agent-phone/hand-back?beacon_profile=default', json={})
    assert back.json()['lease']['holder'] == 'agent'


# -- the ticket ------------------------------------------------------------------------------------------------------------------

def test_ticket_needs_a_linked_device_and_the_owning_profile(phone_api):
    url = f'{PREFIX}/mobile/agent-phone/view-ticket?beacon_profile=default'
    assert phone_api.client.post(url, json={}, headers={'x-beacon-device-id': ''}).status_code == 403
    assert phone_api.client.post(url, json={}, headers={'x-beacon-device-id': 'a' * 32}).status_code == 403
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/view-ticket?beacon_profile=other', json={}).status_code == 403
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/view-ticket', json={}).status_code == 403
    assert phone_api.client.post(url + '&x=1', json={}).status_code == 403
    phone_api.api.mobile.remove_device('owner', phone_api.other_device)
    assert phone_api.client.post(url, json={}, headers={'x-beacon-device-id': phone_api.other_device}).status_code == 403
    phone_api.updating['value'] = True
    assert phone_api.client.post(url, json={}).status_code == 503
    phone_api.updating['value'] = False
    issued = ticket(phone_api)
    assert issued['path'] == '/api/plugins/beacon/agent-phone/view' and issued['subprotocol'] == SUB
    assert 0 < issued['expires_in'] <= 20 and issued['profile'] == 'default'


def test_ticket_is_single_use(phone_api):
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.receive_json()['t'] == 'state'
        ws.send_json({'t': 'done'})
        assert read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'bye')[-1] == {'t': 'bye', 'reason': 'done'}
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]):
            pass
    assert closed.value.code == 1008


def test_an_expired_ticket_is_refused(phone_api, monkeypatch):
    now = [1000.0]
    real = phone_api.apimod.sys.modules['beacon_canonical_transport'].TicketCache
    monkeypatch.setattr(phone_api.apimod.sys.modules['beacon_canonical_transport'], 'TicketCache',
                        lambda capacity: real(capacity=capacity, clock=lambda: now[0]))
    client = phone_api.client_for(phone_api.router_for(phone_api.Deps(phone_api.api.mobile)))
    client.headers['x-beacon-device-id'] = phone_api.device
    issued = client.post(f'{PREFIX}/mobile/agent-phone/view-ticket?beacon_profile=default', json={}).json()
    now[0] += 21
    with pytest.raises(WebSocketDisconnect) as closed:
        with client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']],
                                      headers={'x-beacon-device-id': phone_api.device}):
            pass
    assert closed.value.code == 1008


@pytest.mark.parametrize('protocols', [lambda t: ['beacon-ticket.' + t], lambda t: [SUB, 'beacon-ticket.' + t, 'extra'],
                                       lambda t: ['beacon-canonical-v1', 'beacon-ticket.' + t],
                                       lambda t: [SUB, 'beacon-ticket.' + t, 'beacon-ticket.' + t]])
def test_the_socket_takes_exactly_two_subprotocols(phone_api, protocols):
    issued = ticket(phone_api)
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=protocols(issued['ticket'])):
            pass
    assert closed.value.code == 1008


def test_the_socket_refuses_another_device_profile_or_an_update(phone_api):
    issued = ticket(phone_api)
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']],
                                                headers={'x-beacon-device-id': phone_api.other_device}):
            pass
    assert closed.value.code == 1008
    issued = ticket(phone_api)
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'] + '?beacon_profile=other',
                                                subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]):
            pass
    assert closed.value.code == 1008
    issued = ticket(phone_api)
    phone_api.updating['value'] = True
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]):
            pass
    assert closed.value.code == 1013
    assert phone_api.lease.get().holder == 'agent'


def test_hermes_ws_client_check_runs_on_the_socket(phone_api, monkeypatch):
    import hermes_cli.web_server_boundary as boundary
    monkeypatch.setattr(boundary, 'ws_client_reason', lambda ws, state: 'peer_not_loopback')
    issued = ticket(phone_api)
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]):
            pass
    assert closed.value.code == 1008


# -- the open view ---------------------------------------------------------------------------------------------------------------

def test_the_view_takes_the_lease_streams_and_hands_back(phone_api, monkeypatch):
    import io
    from PIL import Image

    approved = phone_api.folder / 'tmp' / 'AndroidEmulator'
    legacy = phone_api.folder / 'run'
    for directory in (approved, legacy):
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'frames-5640-deadbeef.bin').touch()
        (directory / 'keep.bin').touch()
    screenshot = FakeGrpc.screenshots
    grpc = phone_api.apimod.need('agent_phone_grpc')
    def allowed(client, fmt, width=0, height=0, mmap_path=None):
        if mmap_path is None or Path(mmap_path).parent != approved:
            raise grpc.GrpcError(7, 'Synthetic frame directory refusal')
        yield from screenshot(client, fmt, width, height, mmap_path)
    monkeypatch.setattr(FakeGrpc, 'screenshots', allowed)
    issued = ticket(phone_api)
    viewer = phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.device)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.accepted_subprotocol == SUB
        assert ws.receive_json() == {'t': 'state', 'keyboard': False, 'rotation': 0, 'helper': 'starting', 'prompt': None, 'lease': 'you'}
        assert phone_api.lease.holds(viewer)
        with pytest.raises(phone_api.lease_mod.OwnerHasPhone):
            phone_api.lease.assert_agent_may_act()
        first = read_until(ws, lambda m: isinstance(m, bytes) or m.get('t') == 'bye')
        frame = first[-1]
        assert isinstance(frame, bytes), 'No MMAP frame from the emulator-approved directory'
        if not any(isinstance(m, dict) and m.get('helper') == 'off' for m in first):
            assert read_until(ws, lambda m: isinstance(m, dict))[-1]['helper'] == 'off'  # no helper channel in this fake
        seq, width, height, rotation, jpeg = phone_api.stream.read_frame_message(frame)
        assert (seq, width, height, rotation) == (1, 720, 1600, 0) and jpeg[:2] == b'\xff\xd8'
        path = phone_api.host.frame_paths[0]
        assert path.parent == approved and path.name.startswith('frames-5640-') and path.is_file()
        with Image.open(io.BytesIO(jpeg)) as image:
            assert all(abs(a - b) <= 3 for a, b in zip(image.getpixel((10, 10)), (40, 90, 200)))
        for directory in (approved, legacy):
            assert not (directory / 'frames-5640-deadbeef.bin').exists() and (directory / 'keep.bin').exists()
        ws.send_json({'t': 'ack', 'seq': 1})
        ws.send_json({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.25, 'phase': 'down'}]})
        ws.send_json({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.25, 'phase': 'up'}]})
        ws.send_json({'t': 'key', 'key': 'back'})
        until(lambda: len(phone_api.host.inputs) == 3)
        assert phone_api.host.inputs[0] == ('touch', [(540, 600, 0, 1024)])
        assert phone_api.host.inputs[2] == ('key', 'GoBack', 2)
        ws.send_json({'t': 'text', 'text': 'مرحبا'})
        refused = read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'refused')[-1]
        assert refused == {'t': 'refused', 'what': 'text', 'reason': 'helper'}  # no helper link here: refused plainly
        ws.send_json({'t': 'done'})
        assert read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'bye')[-1] == {'t': 'bye', 'reason': 'done'}
    assert phone_api.lease.get().holder == 'agent'
    assert not path.exists() and not list(approved.glob('frames-*.bin')) and not list(legacy.glob('frames-*.bin'))
    assert phone_api.host.activity >= 1


def test_a_device_removed_during_the_view_closes_it(phone_api):
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.receive_json()['t'] == 'state'
        phone_api.api.mobile.remove_device('owner', phone_api.device)
        received = []
        with pytest.raises(WebSocketDisconnect) as closed:
            while True:
                message = ws.receive()
                if message.get('text'):
                    received.append(json.loads(message['text']))
                if message['type'] == 'websocket.close':
                    raise WebSocketDisconnect(message.get('code', 1000))
        assert closed.value.code == 1008
        assert {'t': 'bye', 'reason': 'removed'} in received
    assert phone_api.lease.get().holder == 'agent'


def test_the_view_outlives_the_ticket_time_session_expiry_which_is_checked_at_accept(phone_api):
    phone_api.current['session'] = phone_api.session_for(2)  # an access token about to expire (Hermes refreshes it meanwhile)
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.receive_json()['t'] == 'state'
        time.sleep(3.0)  # past the binding's expiry; the view revalidates every 0.1 s here
        ws.send_json({'t': 'key', 'key': 'home'})
        until(lambda: ('key', 'GoHome', 2) in phone_api.host.inputs)
        ws.send_json({'t': 'done'})
        assert read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'bye')[-1] == {'t': 'bye', 'reason': 'done'}
    # positive control: an expiry already past at accept refuses the socket
    phone_api.current['session'] = phone_api.session_for(2)  # whole seconds: 2 keeps it valid while the ticket is issued
    issued = ticket(phone_api)
    time.sleep(2.6)
    with pytest.raises(WebSocketDisconnect) as closed:
        with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]):
            pass
    assert closed.value.code == 1008


def test_the_sign_in_check_reads_the_dashboards_own_loopback_port_over_http(phone_api):
    def request(bound=None, scheme='https', server=('127.0.0.1', 8443)):
        state = SimpleNamespace(bound_port=bound) if bound is not None else SimpleNamespace()
        return SimpleNamespace(app=SimpleNamespace(state=state), scope={'scheme': scheme, 'server': server})
    address = phone_api.apimod.dashboard_address
    assert address(request(bound=9119)) == 'http://127.0.0.1:9119'
    assert address(request()) == 'http://127.0.0.1:8443'  # a proxy's https scheme is not the dashboard's
    assert address(request(server=None)) is None


def test_a_phone_that_is_off_says_so(phone_api):
    phone_api.host.is_running = False
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.receive_json() == {'t': 'bye', 'reason': 'off'}
    assert phone_api.lease.get().holder == 'agent'


def test_opening_a_replacement_view_preserves_files_of_the_previous_view(phone_api):
    prior = [phone_api.folder / d / 'frames-5640-12345678.bin' for d in ('run', 'tmp/AndroidEmulator')]
    for path in prior:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b'previous')
    phone_api.lease.acquire(phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.other_device), 'setup')
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        read_until(ws, lambda message: isinstance(message, bytes))
        assert all(path.is_file() for path in prior)
        own = phone_api.host.frame_paths[-1]
        ws.send_json({'t': 'done'})
        read_until(ws, lambda message: isinstance(message, dict) and message.get('t') == 'bye')
    assert not own.exists() and all(path.read_bytes() == b'previous' for path in prior)


def test_a_second_phone_opening_the_view_replaces_the_first(phone_api):
    first = ticket(phone_api)
    with phone_api.client.websocket_connect(first['path'], subprotocols=[SUB, 'beacon-ticket.' + first['ticket']]) as ws:
        assert ws.receive_json()['t'] == 'state'
        phone_api.lease.acquire(phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.other_device))
        seen = []
        while True:
            message = ws.receive()
            if message['type'] == 'websocket.close':
                break
            if message.get('text'):
                seen.append(json.loads(message['text']))
        assert {'t': 'bye', 'reason': 'replaced'} in seen
    assert phone_api.lease.get().viewer_id == phone_api.lease_mod.viewer_id('owner', 'basic', phone_api.other_device)


# -- approvals (L3) --------------------------------------------------------------------------------------------------------------

class FakeApprovals:
    def __init__(self):
        self.calls = []

    def open_records(self, owner):
        self.calls.append(('open', owner))
        return [{'id': 'a' * 32, 'kind': 'agent_phone_action'}]

    def decide(self, owner, record_id, approved, device, code=None):
        self.calls.append(('decide', owner, record_id, approved, device, code))
        if record_id == 'b' * 32:
            raise LookupError('Approval not found')
        if record_id == 'c' * 32:
            raise ValueError('expired')
        if record_id == 'd' * 32:
            raise ValueError('invalid')
        return approved

    def activity(self, owner, limit=50):
        self.calls.append(('activity', owner, limit))
        return []

    def policy(self, owner):
        return {'enabled': True, 'ask': 'payments_and_sends'}

    def set_policy(self, owner, enabled=None, ask=None):
        if ask not in (None, 'payments_and_sends', 'every_action'):
            raise ValueError('invalid')
        return {'enabled': True if enabled is None else enabled, 'ask': ask or 'payments_and_sends'}


def test_approval_routes_follow_l3s_contract(phone_api, monkeypatch):
    fake = FakeApprovals()
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_approvals', fake)
    c, base = phone_api.client, f'{PREFIX}/mobile/agent-phone'
    assert c.get(f'{base}/approvals?beacon_profile=other').json() == {'approvals': [{'id': 'a' * 32, 'kind': 'agent_phone_action'}]}
    assert c.get(f'{base}/approvals?beacon_profile=default', headers={'x-beacon-device-id': ''}).status_code == 403
    ok = c.post(f'{base}/approvals/{"a" * 32}/decision?beacon_profile=default', json={'approved': True})
    assert ok.status_code == 200 and ok.json() == {'approved': True} and ok.headers['cache-control'] == 'no-store'
    assert fake.calls[-1] == ('decide', 'owner', 'a' * 32, True, phone_api.device, None)
    c.post(f'{base}/approvals/{"a" * 32}/decision?beacon_profile=default', json={'approved': True, 'code': '123456'})
    assert fake.calls[-1][-1] == '123456'
    assert c.post(f'{base}/approvals/{"b" * 32}/decision?beacon_profile=default', json={'approved': True}).status_code == 404
    expired = c.post(f'{base}/approvals/{"c" * 32}/decision?beacon_profile=default', json={'approved': False})
    assert expired.status_code == 409 and expired.json()['detail'] == 'expired'
    assert c.post(f'{base}/approvals/{"d" * 32}/decision?beacon_profile=default', json={'approved': True}).status_code == 400
    assert c.post(f'{base}/approvals/NOTANID/decision?beacon_profile=default', json={'approved': True}).status_code == 404
    assert c.post(f'{base}/approvals/{"a" * 32}/decision?beacon_profile=default', json={'approved': 'yes'}).status_code == 400
    assert c.post(f'{base}/approvals/{"a" * 32}/decision?beacon_profile=default', json={'approved': True, 'x': 1}).status_code == 400
    assert c.get(f'{base}/activity?beacon_profile=default&limit=20').json() == {'activity': []}
    assert fake.calls[-1] == ('activity', 'owner', 20)
    assert c.get(f'{base}/activity?beacon_profile=default&limit=0').status_code == 400
    assert c.get(f'{base}/policy?beacon_profile=default').json() == {'enabled': True, 'ask': 'payments_and_sends'}
    assert c.put(f'{base}/policy?beacon_profile=default', json={'ask': 'every_action'}).json()['ask'] == 'every_action'
    assert c.put(f'{base}/policy?beacon_profile=default', json={'ask': 'never'}).status_code == 400
    assert c.put(f'{base}/policy?beacon_profile=default', json={'surprise': 1}).status_code == 400


# -- the install flow (L1a) ------------------------------------------------------------------------------------------------------

class FakeInstall:
    class InstallError(Exception):
        def __init__(self, code, line=None, fix=None, retry=False):
            super().__init__(code)
            self.code, self.line, self.fix, self.retry = code, line or 'x', fix, retry

    class Paused(Exception):
        pass

    class Cancelled(Exception):
        pass

    class Control:
        def __init__(self):
            self.paused = self.cancelled = False

        def pause(self):
            self.paused = True

        def resume(self):
            self.paused = False

        def cancel(self):
            self.cancelled = True

    LICENCES = [{'id': 'android-sdk-license', 'text': 'Synthetic Google terms.'}]

    def __init__(self, folder):
        self.folder = folder
        self.accepted, self.runs, self.cleaned = [], [], 0
        self.release = threading.Event()

    def read_pointer(self, home):
        return None

    def phone_folder(self, home):
        return self.folder

    def terms(self, folder):
        return {'licences': [dict(item) for item in self.LICENCES], 'sizes': {'download_bytes': 2000, 'installed_bytes': 3000,
                                                                           'peak_bytes': 5000}}

    def accept_licences(self, folder, accepted, by):
        self.accepted.append((Path(folder), accepted, by))

    def checks_passed(self, checks):
        return bool(checks.get('ok') and (not checks.get('needs_continue') or checks.get('continued')))

    def run_install(self, folder, *, checks, hermes_home, profile, dashboard, control, progress, proto_ok):
        self.runs.append({'folder': Path(folder), 'checks': checks, 'profile': profile, 'dashboard': dashboard, 'proto_ok': proto_ok})
        progress(event='plan', download_bytes=2000, installed_bytes=3000, peak_bytes=5000)
        progress(event='phase', phase='download', key='emulator')
        progress(event='bytes', bytes=500)
        self.release.wait(5)
        if control.cancelled:
            raise self.Cancelled()
        if control.paused:
            raise self.Paused()
        return {}

    def clean_partial(self, folder):
        self.cleaned += 1


class FakeChecks:
    def __init__(self, needs_continue=False):
        self.needs_continue = needs_continue
        self.calls = []

    def run_checks(self, folder, dashboard):
        self.calls.append((Path(folder), dashboard))
        return {'ok': True, 'needs_continue': self.needs_continue,
                'rows': [{'id': 'developer_tools', 'status': 'warn', 'line': 'Close Android developer tools…', 'fix': None,
                          'how': None, 'needs_continue': self.needs_continue}]}

    def continue_warnings(self, result):
        return {**result, 'continued': True}


@pytest.fixture
def installing(phone_api, monkeypatch):
    pointer = phone_api.tmp / 'plugin-data' / 'beacon' / 'agent-phone.json'
    pointer.unlink()  # not installed yet
    install, checks = FakeInstall(phone_api.tmp / 'new-phone'), FakeChecks(needs_continue=True)
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', install)
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_checks', checks)
    return phone_api, install, checks


def test_install_needs_checks_and_the_servers_own_terms(installing):
    p, install, checks = installing
    c, base = p.client, f'{PREFIX}/mobile/agent-phone'
    assert c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license']}).status_code == 409
    assert c.get(f'{base}/terms?beacon_profile=default').status_code == 409  # nothing is fetched from Google before the checks
    rows = c.post(f'{base}/checks?beacon_profile=default', json={}).json()
    assert rows['ok'] and rows['needs_continue'] and rows['rows'][0]['id'] == 'developer_tools'
    assert checks.calls[0][0] == install.folder and checks.calls[0][1].startswith('http')
    assert c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license']}).status_code == 409  # no terms
    shown = c.get(f'{base}/terms?beacon_profile=default').json()
    assert shown['licences'] == FakeInstall.LICENCES and shown['sizes']['download_bytes'] == 2000
    assert c.post(f'{base}/install?beacon_profile=default', json={'accept': ['something-else']}).status_code == 409
    # an amber row without Continue does not start the download
    assert c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license']}).status_code == 409
    started = c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license'], 'continue': True})
    assert started.status_code == 202
    folder, accepted, by = install.accepted[0]
    assert folder == install.folder and accepted == FakeInstall.LICENCES and p.device[:8] in by
    until(lambda: install.runs)
    assert install.runs[0]['profile'] == 'default' and install.runs[0]['checks']['continued']
    # the 404 fallback's proto_ok is L1b's proto contract check (the emulator's shipped proto, read by path)
    proto = Path(__file__).resolve().parent / 'fixtures' / 'emulator_controller_37_2_13_excerpt.proto'
    assert install.runs[0]['proto_ok'](proto) is True
    renamed = p.tmp / 'emulator_controller.proto'
    renamed.write_text(proto.read_text(encoding='utf-8').replace('rpc sendTouch', 'rpc sendTouches'), encoding='utf-8')
    assert install.runs[0]['proto_ok'](renamed) is False
    until(lambda: c.get(f'{base}/install?beacon_profile=default').json().get('done_bytes') == 500)
    progress = c.get(f'{base}/install?beacon_profile=default').json()
    assert progress['state'] == 'running' and progress['total_bytes'] == 2000 and progress['phase'] == 'download'
    install.release.set()
    until(lambda: c.get(f'{base}/install?beacon_profile=default').json()['state'] == 'done')


def test_install_pause_resume_and_cancel(installing):
    p, install, checks = installing
    c, base = p.client, f'{PREFIX}/mobile/agent-phone'
    c.post(f'{base}/checks?beacon_profile=default', json={})
    c.get(f'{base}/terms?beacon_profile=default')
    c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license'], 'continue': True})
    until(lambda: install.runs)
    c.post(f'{base}/install/pause?beacon_profile=default', json={})
    install.release.set()
    until(lambda: c.get(f'{base}/install?beacon_profile=default').json()['state'] == 'paused')
    resumed = c.post(f'{base}/install/resume?beacon_profile=default', json={})
    assert resumed.status_code == 202
    until(lambda: len(install.runs) == 2 and c.get(f'{base}/install?beacon_profile=default').json()['state'] == 'done')
    install.release.clear()
    c.post(f'{base}/install?beacon_profile=default', json={'accept': ['android-sdk-license'], 'continue': True})
    until(lambda: len(install.runs) == 3)
    c.post(f'{base}/install/cancel?beacon_profile=default', json={})
    install.release.set()
    until(lambda: c.get(f'{base}/install?beacon_profile=default').json()['state'] == 'cancelled')
    assert c.post(f'{base}/install/resume?beacon_profile=default', json={}).status_code == 409
    assert c.post(f'{base}/install/explode?beacon_profile=default', json={}).status_code == 400


def test_remove_runs_l1as_remove_phone_with_the_owning_profile(phone_api, monkeypatch):
    install = SimpleNamespace(read_pointer=lambda home: json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text()))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', install)
    url = f'{PREFIX}/mobile/agent-phone/remove?beacon_profile=default'
    assert phone_api.client.post(url, json={}).status_code == 503  # an L1a without remove_phone
    removed = []

    def remove_phone(hermes_home, delete_logins=False, *, profile=None):  # L1a's surface (emu-l1a patch 79a7e269)
        removed.append((Path(hermes_home), delete_logins, profile))
        return {'removed': True, 'freed_bytes': 9_800_000_000, 'logins_deleted': 2}
    install.remove_phone = remove_phone
    assert phone_api.client.post(url, json={'delete_logins': 'yes'}).status_code == 400
    done = phone_api.client.post(url, json={'delete_logins': True})
    assert done.status_code == 200
    assert done.json() == {'removed': True, 'freed_bytes': 9_800_000_000, 'logins_deleted': 2}
    assert removed == [(phone_api.tmp, True, 'default')]
    install.remove_phone = lambda hermes_home, delete_logins=False, *, profile=None: {
        'removed': True, 'freed_bytes': 5, 'logins_deleted': None, 'logins_error': 'LookupError'}
    assert phone_api.client.post(url, json={}).json() == {'removed': True, 'freed_bytes': 5, 'logins_deleted': None}

    def not_ours(hermes_home, delete_logins=False, *, profile=None):
        raise ValueError('not a phone folder')
    install.remove_phone = not_ours
    assert phone_api.client.post(url, json={}).status_code == 409
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/remove?beacon_profile=other', json={}).status_code == 403


# -- follow-up 1: the phone page's fields, the helper, the guard -------------------------------------------------------------

def synthetic_apk(path, cert=b'synthetic-signer-certificate', version=2):
    """A file with the parts of an APK that apk_signers reads: a v2/v3 signing block before the central directory and the
    end-of-central-directory record pointing at it. The certificate bytes are synthetic."""
    def lp(data):
        return struct.pack('<I', len(data)) + data
    signed = lp(b'') + lp(lp(cert))  # digests (none), then the certificates
    signer = lp(signed) + lp(b'') + lp(b'')  # signed data, signatures, public key
    value = lp(lp(signer))
    pair_id = 0x7109871A if version == 2 else 0xF05368C0
    pairs = struct.pack('<QI', 4 + len(value), pair_id) + value
    size = len(pairs) + 8 + 16
    block = struct.pack('<Q', size) + pairs + struct.pack('<Q', size) + b'APK Sig Block 42'
    head = b'PK\x03\x04' + b'\0' * 60
    central_offset = len(head) + len(block)
    central = b'PK\x01\x02' + b'\0' * 42
    eocd = b'PK\x05\x06' + struct.pack('<HHHHIIH', 0, 0, 1, 1, len(central), central_offset, 0)
    Path(path).write_bytes(head + block + central + eocd)
    return hashlib.sha256(cert).hexdigest()


def test_apk_signers_reads_the_signing_block(phone_api, tmp_path):
    apkmod = phone_api.apimod.need('agent_phone_install')  # one parser for the plugin: L1a's (the join moved L2's there)
    for version in (2, 3):
        apk = tmp_path / f'helper-v{version}.apk'
        signer = synthetic_apk(apk, version=version)
        assert apkmod.apk_signers(apk) == [signer]
    plain = tmp_path / 'plain.zip'
    plain.write_bytes(b'PK\x03\x04' + b'\0' * 30 + b'PK\x05\x06' + b'\0' * 18)
    assert apkmod.apk_signers(plain) is None


def test_parse_facts_keeps_only_counts_versions_and_flags(phone_api):
    parse = phone_api.apimod.parse_facts
    assert parse({'google': '1\n', 'chrome': 'versionName=133\nversionName=136\n', 'chrome_autofill': 'Row: 0 autofill_third_party_state=1',
                  'helper': 'versionCode=121'}) == {'google': True, 'chrome': 136, 'chrome_autofill': True, 'helper_version': 121,
                                                    'helper_installed': True, 'autofill': None}
    assert parse({'google': '0', 'chrome_autofill': 'Row: 0 autofill_third_party_state=0', 'helper': ''}) == {
        'google': False, 'chrome': None, 'chrome_autofill': False, 'helper_version': None, 'helper_installed': False,
        'autofill': None}
    # Android's active autofill service: the helper, in the short or the full component form; anything else is not
    for service, ours in (('dev.hermes.agentphone.helper/.HelperAutofillService', True),
                          ('dev.hermes.agentphone.helper/dev.hermes.agentphone.helper.HelperAutofillService', True),
                          ('com.google.android.gms/.autofill.service.AutofillService', False), ('null', False), ('', False)):
        assert parse({'autofill': service + '\n'})['autofill'] is ours
    # an account name never becomes a value: only a bare count is read
    assert parse({'google': 'Account {name=someone@example.com, type=com.google}'})['google'] is None
    assert parse({'chrome_autofill': 'Error while accessing provider'})['chrome_autofill'] is None  # Chrome below 135: unknown
    google = phone_api.apimod.FACT_SCRIPTS['google']
    assert google.startswith('dumpsys account | grep -c ') and 'name=' in google  # -c: the phone prints a count, never a name


def wait_status(phone_api, done, timeout=8.0, profile='default'):
    deadline = time.monotonic() + timeout
    while True:
        value = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile={profile}').json()
        if done(value) or time.monotonic() > deadline:
            return value
        time.sleep(0.05)


def test_status_carries_the_phone_page_fields(phone_api, monkeypatch):
    host = phone_api.host
    real = phone_api.apimod.need('agent_phone_install')
    host.phone.update(google='1', chrome='versionName=136', chrome_autofill='Row: 0 autofill_third_party_state=1',
                      helper='versionCode=120')
    pin = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'sha256': 'a' * 64, 'signer_sha256': 'b' * 64,
           'file': 'helper.apk'}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', SimpleNamespace(
        read_pointer=lambda home: json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text()),
        helper_package=lambda: pin, helper_status=lambda folder: {'state': 'installed', 'line': None, 'on_phone': True}, apk_signers=real.apk_signers, InstallError=real.InstallError, write_pointer=real.write_pointer))
    sidecar = phone_api.tmp / 'owning-home' / 'plugin-data' / 'beacon' / 'agent-phone' / 'vault-state.json'
    sidecar.parent.mkdir(parents=True)
    sidecar.write_text(json.dumps({'items': {'vault_a': {'saved_on_phone': True}, 'vault_b': {'saved_on_phone': True},
                                             'vault_c': {'package': 'example.shop'}}}))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', SimpleNamespace(
        owning_home=lambda profile: phone_api.tmp / 'owning-home' if profile == 'default' else None,
        sidecar_path=lambda home: Path(home) / 'plugin-data' / 'beacon' / 'agent-phone' / 'vault-state.json'))
    (phone_api.folder / 'run').mkdir(exist_ok=True)
    (phone_api.folder / 'run' / 'agent-activity').write_text('')  # L3's tool call (note_activity(agent=True))
    (phone_api.folder / 'blob.bin').write_bytes(b'x' * 12345)
    value = wait_status(phone_api, lambda v: v['setup']['google'] is not None and v['storage_bytes'] is not None)
    expected_bytes = sum(os.path.getsize(os.path.join(d, f)) for d, _, fs in os.walk(phone_api.folder) for f in fs)
    assert value['setup'] == {'google': True, 'chrome': 136, 'chrome_autofill': True, 'logins': 2}
    assert value['storage_bytes'] == expected_bytes
    assert value['helper'] == 'update'  # 120 installed, 121 pinned
    assert value['in_use'] is True  # the agent holds the lease and the phone was used in the last 2 minutes
    assert '@' not in json.dumps(value)
    old = time.time() - 600
    os.utime(phone_api.folder / 'run' / 'agent-activity', (old, old))
    assert wait_status(phone_api, lambda v: v['in_use'] is False)['in_use'] is False
    phone_api.lease.acquire('v-owner')
    os.utime(phone_api.folder / 'run' / 'agent-activity', None)
    assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['in_use'] is False


def test_in_use_counts_only_the_agents_own_actions(phone_api):
    """Root's rule (17 Nov): the owner's Done or Turn on, and setup input, touch the keeper's activity file but never make the page
    say "{name} is using it"; only the agent's own tool calls do."""
    run = phone_api.folder / 'run'
    run.mkdir(exist_ok=True)
    (run / 'activity').write_text('')  # setup input and Turn on (the keeper's idle clock)
    old = time.time() - 600
    if (run / 'agent-activity').exists():
        os.utime(run / 'agent-activity', (old, old))
    assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['in_use'] is False
    (run / 'agent-activity').write_text('')
    assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['in_use'] is True


def test_the_helper_is_ready_only_as_androids_autofill_service(phone_api, monkeypatch):
    """Root's rule (17 Nov): ready = installed, current and Android's active autofill service; installed but not the autofill
    service = off; not installed = missing."""
    real = phone_api.apimod.need('agent_phone_install')
    pin = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'sha256': 'a' * 64, 'signer_sha256': 'b' * 64}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', SimpleNamespace(
        read_pointer=lambda home: json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text()),
        helper_package=lambda: pin, helper_status=lambda folder: {'state': 'installed', 'line': None, 'on_phone': True}, apk_signers=real.apk_signers, InstallError=real.InstallError, write_pointer=real.write_pointer))
    monkeypatch.setattr(phone_api.apimod, 'FACTS_SECONDS', 0.05)
    phone = phone_api.host.phone
    phone.update(helper='versionCode=121', autofill='com.google.android.gms/.autofill.service.AutofillService')
    assert wait_status(phone_api, lambda v: v['helper'] is not None)['helper'] == 'off'
    phone.update(autofill='dev.hermes.agentphone.helper/.HelperAutofillService')
    assert wait_status(phone_api, lambda v: v['helper'] == 'ready')['helper'] == 'ready'
    phone.update(helper='')
    value = wait_status(phone_api, lambda v: v['helper'] == 'missing')
    assert value['helper'] == 'missing'


def test_status_leaves_facts_unknown_while_the_phone_is_off_and_without_a_pin(phone_api, monkeypatch):
    monkeypatch.setattr(phone_api.apimod.need('agent_phone_install'), 'HELPER_PIN', 'no-pin.json')  # a release ships a pin; this Beacon has none
    phone_api.host.is_running = False
    phone_api.host.phone.update(google='1', helper='versionCode=121')
    value = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()
    time.sleep(0.3)
    value = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()
    # L9's real vault module (joined tree): no sidecar yet, so no logins saved from the phone
    assert value['setup'] == {'google': None, 'chrome': None, 'chrome_autofill': None, 'logins': 0}
    # not read yet: the phone folder says nothing was installed, and this Beacon pins no helper
    assert value['helper'] == 'missing' and value['helper_line'] == "Saved logins on {name}'s phone need a Beacon update."
    assert value['in_use'] is False
    assert phone_api.host.sessions == 0  # nothing ran on a phone that is off
    phone_api.host.is_running = True
    value = wait_status(phone_api, lambda v: v['setup']['google'] is not None)
    # read now: a helper is on the phone, but it is not Android's autofill service
    assert value['setup']['google'] is True and value['helper'] == 'off' and value['helper_line'] is None


def test_helper_update_reinstalls_the_pinned_helper_after_checking_its_file(phone_api, monkeypatch, tmp_path):
    real = phone_api.apimod.need('agent_phone_install')
    apk = tmp_path / 'BeaconAgentPhoneHelper-release.apk'
    signer = synthetic_apk(apk)
    pin = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'version_name': '1.2.1',
           'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(), 'signer_sha256': signer, 'file': apk.name, 'bytes': apk.stat().st_size}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', SimpleNamespace(
        read_pointer=lambda home: json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text()),
        helper_package=lambda: dict(pin), helper_status=lambda folder: {'state': 'installed', 'line': None, 'on_phone': True}, apk_signers=real.apk_signers, InstallError=real.InstallError, write_pointer=real.write_pointer))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_host', SimpleNamespace(
        AUTOFILL_SERVICE='dev.hermes.agentphone.helper/.HelperAutofillService',
        CODE_LISTENER='dev.hermes.agentphone.helper/dev.hermes.agentphone.helper.CodeListener', PhoneError=RuntimeError))
    # L1a fetches the pinned helper into the phone folder (Root, 17 Nov); here, the file it fetched
    monkeypatch.setattr(phone_api.Deps, 'fetch_helper', lambda self, folder: apk, raising=False)
    host = phone_api.host
    host.phone.update(helper='versionCode=120', installed_signer='someone-else')  # an older helper signed by another key
    url = f'{PREFIX}/mobile/agent-phone/helper/update?beacon_profile=default'
    assert wait_status(phone_api, lambda v: v['helper'] is not None)['helper'] == 'update'
    started = phone_api.client.post(url, json={})
    assert started.status_code == 202 and started.json() == {'helper': 'updating'}
    value = wait_status(phone_api, lambda v: v['helper'] == 'ready')
    assert value['helper'] == 'ready' and value['error'] is None and host.helper_recorded == pin['sha256']
    assert host.installs == [apk.name, apk.name]  # refused (other signer), removed, installed again
    assert ['pm', 'uninstall', 'dev.hermes.agentphone.helper'] in host.adb_calls
    assert ['settings', 'put', 'secure', 'autofill_service', 'dev.hermes.agentphone.helper/.HelperAutofillService'] in host.adb_calls
    assert ['cmd', 'notification', 'allow_listener', 'dev.hermes.agentphone.helper/dev.hermes.agentphone.helper.CodeListener'] in host.adb_calls
    # a file that isn't the pinned one is never installed
    for broken in ({'sha256': 'f' * 64}, {'signer_sha256': 'e' * 64}):
        pin.update(broken)
        host.installs.clear()
        assert phone_api.client.post(url, json={}).status_code == 202
        time.sleep(0.5)  # the check of a small file is done long before this
        value = phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()
        assert value['error'] == "Beacon's helper file is damaged. Update Beacon." and host.installs == []
        pin.update(sha256=hashlib.sha256(apk.read_bytes()).hexdigest(), signer_sha256=signer)
    host.is_running = False
    assert phone_api.client.post(url, json={}).json() == {'helper': None, 'line': 'Turn the phone on first.',
                                                      'reason': 'phone.turn_on_first'}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', SimpleNamespace(
        read_pointer=lambda home: json.loads((Path(home) / 'plugin-data' / 'beacon' / 'agent-phone.json').read_text()),
        helper_package=lambda: None))
    host.is_running = True
    assert phone_api.client.post(url, json={}).status_code == 409
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/helper/update?beacon_profile=other', json={}).status_code == 403


def test_the_host_is_l1as_phone_host_with_the_routes_lease(phone_api, monkeypatch):
    """The join: one factory for the routes and the agent's tool (L1a's phone_host: port pick, the fetched helper, this lease's
    view_open); test_agent_phone_join.py checks what it builds."""
    made = {}
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_host', SimpleNamespace())
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_install', SimpleNamespace(
        phone_host=lambda folder, lease: made.update(folder=folder, lease=lease) or 'host'))
    lease = phone_api.lease_mod.PhoneLease(phone_api.folder)
    assert phone_api.apimod.Deps(phone_api.api.mobile).host(phone_api.folder, lease) == 'host'
    assert made == {'folder': phone_api.folder, 'lease': lease}


def test_the_view_answers_a_debugging_prompt_no_through_l3s_guard(phone_api, monkeypatch):
    monkeypatch.setattr(phone_api.stream, 'GUARD_SECONDS', 0.1)
    calls = []

    class Guard:  # L3's agent_phone_guard surface: debug_prompt(adb) -> 'usb' | 'wifi' | None, deny(adb)
        showing = None

        @classmethod
        def debug_prompt(cls, adb):
            calls.append(('check', type(adb).__name__))
            return cls.showing

        @classmethod
        def deny(cls, adb):
            calls.append(('deny', type(adb).__name__))
            return True
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_guard', Guard)
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        assert ws.receive_json()['prompt'] is None
        until(lambda: calls)
        Guard.showing = 'usb'
        seen = read_until(ws, lambda m: isinstance(m, dict) and m.get('prompt') == 'usb_debugging')
        assert seen[-1]['t'] == 'state'
        until(lambda: ('deny', 'FakeAdb') in calls)  # the app is told first, then the prompt is answered No
        ws.send_json({'t': 'touch', 'points': [{'id': 0, 'x': 0.5, 'y': 0.5, 'phase': 'down'}]})
        ws.send_json({'t': 'key', 'key': 'back'})
        until(lambda: ('key', 'GoBack', 2) in phone_api.host.inputs)
        assert not [i for i in phone_api.host.inputs if i[0] == 'touch']
        Guard.showing = None
        read_until(ws, lambda m: isinstance(m, dict) and m.get('t') == 'state' and m.get('prompt') is None)
        ws.send_json({'t': 'done'})
        read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'bye')


def test_every_setup_input_notes_the_phones_activity(phone_api, monkeypatch):
    # L1b's callers: every setup input calls note_activity(); a write at most every 5 s (was 30) keeps the keeper and the
    # page's "in use" (2 minutes) right without a file write per touch
    assert phone_api.stream.ACTIVITY_NOTE_SECONDS <= 5.0
    monkeypatch.setattr(phone_api.stream, 'ACTIVITY_NOTE_SECONDS', 0.0)
    issued = ticket(phone_api)
    with phone_api.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        ws.receive_json()
        for _ in range(3):
            ws.send_json({'t': 'key', 'key': 'tab'})
        until(lambda: phone_api.host.activity >= 3)
        ws.send_json({'t': 'done'})
        read_until(ws, lambda m: isinstance(m, dict) and m['t'] == 'bye')


def test_the_setup_channel_carries_the_owning_profiles_vault_for_the_owner(phone_api, monkeypatch):
    calls = {}

    class Vault:
        @classmethod
        def for_dashboard(cls, owning_profile, masks, **kw):
            calls['profile'], calls['kw'] = owning_profile, kw
            return cls()

        def handler(self, session, owner=False):
            calls['owner'] = owner
            return 'handler'

    def connect(adb, events=None, **options):
        calls['adb'], calls['events'], calls['options'] = adb, events('session'), options
        return 'linked'
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_channel', SimpleNamespace(connect=connect))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', SimpleNamespace(PhoneVault=Vault))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', SimpleNamespace(MaskSet=lambda **kw: 'masks'))
    notices = []
    deps = phone_api.apimod.Deps(phone_api.api.mobile)
    deps.home = lambda: phone_api.tmp
    assert deps.channel(phone_api.host, phone_api.host.state(), lambda kind, payload: notices.append((kind, payload))) == 'linked'
    assert calls['profile'] == 'default' and calls['owner'] is True and calls['events'] == 'handler'
    assert calls['options'] == {'keepalive_seconds': 30}  # channel-v1: the host pings every 30 s during a setup session
    calls['kw']['refresh']()
    assert phone_api.host.adb_calls[-1] == ['cmd', 'autofill', 'reset']
    saved = {'line': 'Saved: Kanto Foods · k@example.com', 'reason': 'vault.saved', 'label': 'Kanto Foods', 'identifier': 'k@example.com'}
    calls['kw']['notify']('saved', saved)
    assert notices == [('saved', saved)]  # the whole payload: the view sends its code and names on (contract revision 5)


def test_the_setup_channel_still_opens_when_the_vault_cannot_be_built(phone_api, monkeypatch):
    calls = {}

    class Vault:
        @classmethod
        def for_dashboard(cls, owning_profile, masks, **kw):
            raise LookupError('Saved logins on the assistant phone need a Beacon update for this Hermes')

    def connect(adb, events=None, **options):
        calls['events'], calls['options'] = events, options
        return 'linked'
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_channel', SimpleNamespace(connect=connect))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', SimpleNamespace(PhoneVault=Vault))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', SimpleNamespace(MaskSet=lambda **kw: 'masks'))
    deps = phone_api.apimod.Deps(phone_api.api.mobile)
    deps.home = lambda: phone_api.tmp
    assert deps.channel(phone_api.host, phone_api.host.state(), lambda kind, line: None) == 'linked'
    assert calls == {'events': None, 'options': {'keepalive_seconds': 30}}  # text and Paste still work without the vault


def test_keyboard_and_size_helpers():
    apimod = sys.modules.get('beacon_agent_phone_api')
    if apimod is None:
        import importlib.util
        spec = importlib.util.spec_from_file_location('beacon_agent_phone_api', Path(__file__).resolve().parents[1] / 'agent_phone_api.py')
        apimod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(apimod)
    assert apimod.keyboard_shown('  mInputShown=true mShowRequested=true') is True
    assert apimod.keyboard_shown('mInputShown=false') is False
    assert apimod.keyboard_shown('mInputShown=false mIsInputViewShown=true') is False  # measured: the view flag stays true
    assert apimod.keyboard_shown('nothing here') is None
    assert apimod.keyboard_shown('    mImeShowing=true mLastDrawn=true') is True  # dumpsys window, measured on the B6 phone
    assert apimod.keyboard_shown('    mImeShowing=false mLastDrawn=false') is False
    # fu2 A3: the window line first, the field's type only while the keyboard is up (test_agent_phone_fu2.py)
    assert apimod.KEYBOARD_PROBE.startswith("s=$(dumpsys window | grep -m 1 -E 'mImeShowing=')")


def test_the_phone_is_not_read_while_it_starts_and_is_read_again_after(phone_api, monkeypatch):
    """EMU-JOIN live (17 Nov): a read during boot, before first start installed the helper, kept 'missing' on the page for a
    minute. While a start runs nothing is read; when it ends the next status reads the phone afresh."""
    host = phone_api.host
    host.is_running = False
    host.start_gate_open.clear()
    assert phone_api.client.post(f'{PREFIX}/mobile/agent-phone/start?beacon_profile=default', json={}).status_code == 202
    host.is_running = True  # booting: the emulator runs, first start has not finished
    host.phone.update(helper='')  # nothing installed yet
    for _ in range(3):
        assert phone_api.client.get(f'{PREFIX}/mobile/agent-phone?beacon_profile=default').json()['phone'] == 'starting'
    time.sleep(0.2)
    assert host.sessions == 0  # never read while it starts
    host.phone.update(helper='versionCode=121', autofill='dev.hermes.agentphone.helper/.HelperAutofillService')
    host.start_gate_open.set()  # first start done: the helper is on the phone
    value = wait_status(phone_api, lambda v: v['phone'] == 'on' and v['setup']['google'] is not None)
    assert host.sessions >= 1 and value['helper'] in ('ready', 'update', 'off')  # read after the start, never the boot's 'missing'


def test_p3_2_the_setup_sessions_masks_are_kept_in_the_phone_folders_run(phone_api, monkeypatch):
    seen = {}

    class Vault:
        @classmethod
        def for_dashboard(cls, owning_profile, masks, **kw):
            seen['masks'] = masks
            return cls()

        def handler(self, session, owner=False):
            return 'handler'
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_channel', SimpleNamespace(connect=lambda adb, events=None, **o: 'linked'))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', SimpleNamespace(PhoneVault=Vault))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', SimpleNamespace(MaskSet=lambda **kw: seen.update(kw) or 'masks'))
    deps = phone_api.apimod.Deps(phone_api.api.mobile)
    deps.home = lambda: phone_api.tmp
    assert deps.channel(phone_api.host, phone_api.host.state(), lambda kind, payload: None) == 'linked'
    # the setup session's fills register into the set the gateway's own set reads: one file in the phone folder's run/, digests only
    assert seen['store'] == phone_api.folder / 'run' / 'masks.json' and seen['masks'] == 'masks'

def test_view_with_unconfirmed_keeper_delivers_inline_pixels_without_a_frame_file(phone_api, monkeypatch):
    import io
    from PIL import Image

    p = phone_api
    p.host.folder.run.mkdir(parents=True, exist_ok=True)
    p.host.folder.keeper_conf.write_text('{}')
    modes = []
    def capture(client, fmt, width, height, mmap_path=None):
        modes.append('inline' if mmap_path is None else 'mapped')
        pixels = bytes((28, 95, 198)) * (width * height)
        if mmap_path is not None:
            with Path(mmap_path).open('r+b') as file:
                file.write(pixels)
        yield {'width': width, 'height': height, 'image': pixels if mmap_path is None else b''}
        client.blocked.wait(5)
    monkeypatch.setattr(FakeGrpc, 'screenshots', capture)
    issued = ticket(p)
    with p.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        frame = read_until(ws, lambda m: isinstance(m, bytes) or m.get('t') == 'bye')[-1]
        assert isinstance(frame, bytes)
        seq, width, height, _, jpeg = p.stream.read_frame_message(frame)
        with Image.open(io.BytesIO(jpeg)) as image:
            assert image.size == (width, height) == (720, 1600)
            assert max(abs(a-b) for a,b in zip(image.getpixel((8, 12)), (28, 95, 198))) <= 3
        assert modes == ['inline'] and not list(p.folder.rglob('frames-*.bin'))
        ws.send_json({'t':'ack','seq':seq})
        ws.send_json({'t':'done'})
        read_until(ws, lambda m: isinstance(m, dict) and m.get('t') == 'bye')

@pytest.mark.parametrize('status', [3, 9, 7, 16])
def test_unconfirmed_keeper_inline_refusal_is_terminal(phone_api, monkeypatch, status):
    p = phone_api
    p.host.folder.keeper_conf.write_text('{}')
    modes = []
    def refused(client, fmt, width, height, mmap_path=None):
        modes.append(mmap_path)
        raise p.apimod.need('agent_phone_grpc').GrpcError(status, 'synthetic inline refusal')
        yield
    monkeypatch.setattr(FakeGrpc, 'screenshots', refused)
    issued = ticket(p)
    with p.client.websocket_connect(issued['path'], subprotocols=[SUB, 'beacon-ticket.' + issued['ticket']]) as ws:
        first = read_until(ws, lambda m: isinstance(m, bytes) or m.get('t') == 'bye')[-1]
        assert first == {'t': 'bye', 'reason': 'error'} and modes == [None]
    assert not list(p.folder.rglob('frames-*.bin'))


def test_capture_capability_needs_the_registered_live_generation(phone_api):
    p = phone_api
    folder = p.host.folder
    proof_path = folder.run / 'mmap-cleaner-v1.json'
    receipt = json.loads(proof_path.read_text())
    assert p.stream.frame_directory_for_view(folder) == folder.frames
    prior = json.loads(folder.keeper_conf.read_text())
    folder.keeper_conf.write_text(json.dumps(dict(prior, create_time=prior['create_time'] + 0.01)))
    assert p.stream.frame_directory_for_view(folder) is None
    folder.keeper_conf.write_text(json.dumps(prior))
    proof_path.write_text(json.dumps(dict(receipt, folder=str(p.tmp / 'other-phone'))))
    assert p.stream.frame_directory_for_view(folder) is None
    stale = [receipt['worker'][0], receipt['worker'][1] + 0.01]
    proof_path.write_text(json.dumps(dict(receipt, worker=stale)))
    assert p.stream.frame_directory_for_view(folder) is None
    proof_path.write_text('{')
    assert p.stream.frame_directory_for_view(folder) is None
    proof_path.unlink()
    assert p.stream.frame_directory_for_view(folder) is None
