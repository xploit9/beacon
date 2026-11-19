"""The agent phone's seams, with every lane's real module on both sides (EMU-JOIN).

Each test names the seam it holds. Nothing here stands in for another lane's module; the only stand-ins are the phone itself
(an adb command that is recorded instead of sent, a start that is recorded instead of booting) and Hermes' dashboard status.
"""
import hashlib
import http.server
import importlib.util
import json
import os
import shutil
import socket
import sys
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import pytest

PLUGIN = Path(__file__).resolve().parents[1]
FIXTURES = Path(__file__).resolve().parent / 'fixtures'


def load(name):
    """The module the plugin itself loads (``beacon_<name>``, by path); reused when already loaded."""
    key = 'beacon_' + name
    if key not in sys.modules:
        spec = importlib.util.spec_from_file_location(key, PLUGIN / f'{name}.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


@pytest.fixture(autouse=True)
def a_local_helper_url(monkeypatch):
    """These tests serve the pinned helper from a file: only a test may (the plugin takes https release assets only, P3-4)."""
    monkeypatch.setattr(load('agent_phone_install'), 'HELPER_URL_SCHEMES', ('https', 'http', 'file'))


@pytest.fixture
def m():
    return SimpleNamespace(**{name: load('agent_phone_' + name) for name in
                              ('install', 'host', 'lease', 'checks', 'api', 'tools', 'vault', 'channel', 'grpc', 'keeper', 'adb')})


@pytest.fixture
def phone(tmp_path, m):
    """A Hermes home with its pointer, and an installed phone folder shaped as L1a makes it (outside the home)."""
    home = tmp_path / 'hermes-home'
    folder = tmp_path / 'Local' / 'Beacon' / 'agent-phone' / '0123456789abcdef'
    (folder / 'home' / 'avd' / 'beacon_agent_phone.avd').mkdir(parents=True)
    (folder / 'home' / 'avd' / 'beacon_agent_phone.avd' / 'config.ini').write_text('hw.lcd.width=1080\n', encoding='utf-8')
    (folder / 'run').mkdir()
    m.install.write_pointer(home, folder, profile='default', dashboard=None)
    return SimpleNamespace(home=home, folder=folder)


def signed_apk(cert=b'synthetic signer certificate (DER)', scheme=0x7109871A):
    """A tiny APK-shaped zip with an APK Signing Block before its central directory, as apksigner writes one (v2 by default)."""
    import io
    import struct
    import zipfile
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, 'w') as z:
        z.writestr('AndroidManifest.xml', b'synthetic manifest')
    raw = buf.getvalue()
    eocd = raw.rfind(b'PK\x05\x06')
    cd = struct.unpack_from('<I', raw, eocd + 16)[0]
    lp = lambda b: struct.pack('<I', len(b)) + b  # noqa: E731
    signed = lp(b'') + lp(lp(cert)) + lp(b'')               # digests, certificates, additional attributes
    signer = lp(signed) + lp(b'') + lp(b'synthetic public key')
    value = lp(lp(signer))
    pair = struct.pack('<QI', 4 + len(value), scheme) + value
    size = len(pair) + 8 + 16
    block = struct.pack('<Q', size) + pair + struct.pack('<Q', size) + b'APK Sig Block 42'
    end = bytearray(raw[eocd:])
    struct.pack_into('<I', end, 16, cd + len(block))
    return raw[:cd] + block + raw[cd:eocd] + bytes(end)


CERT = b'synthetic signer certificate (DER)'


def pinned_plugin(tmp_path, apk=None, signer=None, url=True):
    """A plugin folder carrying L8's pin plus its release ``url`` (Root, 17 Nov); the APK is served from outside the plugin."""
    apk = apk if apk is not None else signed_apk()
    served = tmp_path / 'release-assets' / 'BeaconAgentPhoneHelper-release.apk'
    served.parent.mkdir(exist_ok=True)
    served.write_bytes(apk)
    plugin = tmp_path / 'plugin'
    plugin.mkdir(exist_ok=True)
    pin = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'version_name': '1.2.1', 'bytes': len(apk),
           'sha256': hashlib.sha256(apk).hexdigest(), 'signer_sha256': signer or hashlib.sha256(CERT).hexdigest(),
           'signed_by': 'stable', 'url': served.as_uri() if url else None}
    (plugin / 'agent_phone_helper.json').write_text(json.dumps(pin), encoding='utf-8')
    return SimpleNamespace(plugin=plugin, sha=pin['sha256'], served=served)


class Session:
    """The phone's adb for first start: commands succeed and installs are recorded (no phone)."""

    def __init__(self):
        self.installed, self.shells = [], []

    def install(self, apk):
        self.installed.append(Path(apk).read_bytes())
        return SimpleNamespace(returncode=0)

    def shell(self, args, timeout=30):
        self.shells.append(args)
        return SimpleNamespace(returncode=0, stdout='', stderr='')

    def session(self):
        import contextlib
        return contextlib.nullcontext(self)


# -- one host for both sides: L1a's port pick + pinned helper + L2's lease -> L1b's PhoneHost ---------------------------------

def test_the_routes_and_the_tool_build_the_same_host(phone, m, tmp_path):
    lease = m.lease.PhoneLease(phone.folder)
    routes = m.api.Deps(SimpleNamespace(home=lambda: phone.home)).host(phone.folder, lease)
    tool = m.tools._host(phone.folder)
    for host in (routes, tool):
        assert isinstance(host, m.host.PhoneHost) and host.pick_ports is m.install.pick_ports
        assert host.view_open is not None and host.view_open() is False  # L2's lease: nobody is viewing
    assert routes.view_open.__self__ is lease
    lease.acquire(m.lease.viewer_id('owner', 'basic', 'device'), 'setup')
    assert routes.view_open() is True and tool.view_open() is True  # an adopt now keeps the live view's frame file


def test_the_signer_is_read_from_the_apks_signing_block(m, tmp_path):
    for scheme in (0x7109871A, 0xF05368C0):
        apk = tmp_path / f'{scheme:x}.apk'
        apk.write_bytes(signed_apk(scheme=scheme))
        assert m.install.apk_signers(apk) == [hashlib.sha256(CERT).hexdigest()]
    import zipfile
    unsigned = tmp_path / 'unsigned.apk'
    with zipfile.ZipFile(unsigned, 'w') as z:
        z.writestr('AndroidManifest.xml', b'synthetic manifest')
    assert m.install.apk_signers(unsigned) is None


def test_the_first_start_fetches_the_pinned_helper_and_installs_it(phone, m, tmp_path):
    pinned = pinned_plugin(tmp_path)
    host = m.install.phone_host(phone.folder, plugin_dir=pinned.plugin)
    assert host.helper_apk is None and host.helper_sha256 == pinned.sha  # not fetched yet
    assert m.install.helper_status(phone.folder, plugin_dir=pinned.plugin) == {
        'state': 'missing', 'line': "Saved logins on {name}'s phone need a download. Turn the phone on while online.",
        'reason': 'helper.not_fetched',
        'on_phone': False}
    adb = Session()
    result = host.first_start(adb)
    assert result['helper'] == 'ok' and result['autofill'] == 'ok' and result['code_listener'] == 'ok'
    assert adb.installed == [pinned.served.read_bytes()]
    kept = phone.folder / 'downloads' / 'helper' / f'{pinned.sha}.apk'
    assert kept.read_bytes() == pinned.served.read_bytes() and not (pinned.plugin / 'BeaconAgentPhoneHelper-release.apk').exists()
    assert json.loads((phone.folder / 'run' / 'first-start.json').read_text(encoding='utf-8'))['helper_sha256'] == pinned.sha
    assert m.install.helper_status(phone.folder, plugin_dir=pinned.plugin)['state'] == 'installed'
    again = Session()
    assert m.install.phone_host(phone.folder, plugin_dir=pinned.plugin).first_start(again)['helper'] == 'done' and not again.installed


def test_a_newer_pinned_helper_installs_over_the_old_one(phone, m, tmp_path):
    first = pinned_plugin(tmp_path)
    m.install.phone_host(phone.folder, plugin_dir=first.plugin).first_start(Session())
    # A Beacon update: the same signer, a new build
    rebuilt = pinned_plugin(tmp_path, apk=signed_apk().replace(b'synthetic manifest', b'synthetic manifesT'))
    assert rebuilt.sha != first.sha
    assert m.install.helper_status(phone.folder, plugin_dir=rebuilt.plugin)['state'] == 'installed'  # the old one still works
    assert m.install.fetch_helper(phone.folder, plugin_dir=rebuilt.plugin) is not None
    assert m.install.helper_status(phone.folder, plugin_dir=rebuilt.plugin)['state'] == 'pending'
    adb = Session()
    assert m.install.phone_host(phone.folder, plugin_dir=rebuilt.plugin).first_start(adb)['helper'] == 'ok'
    assert adb.installed == [rebuilt.served.read_bytes()]
    assert sorted(p.name for p in (phone.folder / 'downloads' / 'helper').iterdir()) == [f'{rebuilt.sha}.apk']


def test_a_helper_not_signed_as_pinned_is_never_installed(phone, m, tmp_path):
    pinned = pinned_plugin(tmp_path, signer='f' * 64)
    with pytest.raises(m.install.InstallError) as refused:
        m.install.fetch_helper(phone.folder, plugin_dir=pinned.plugin)
    assert refused.value.code == 'update' and not list((phone.folder / 'downloads' / 'helper').glob('*.apk'))
    adb = Session()
    assert m.install.phone_host(phone.folder, plugin_dir=pinned.plugin).first_start(adb)['helper'] == 'failed'
    assert adb.installed == [] and m.install.helper_status(phone.folder, plugin_dir=pinned.plugin)['state'] == 'missing'


def test_a_damaged_kept_helper_is_fetched_again(phone, m, tmp_path):
    pinned = pinned_plugin(tmp_path)
    kept = m.install.fetch_helper(phone.folder, plugin_dir=pinned.plugin)
    kept.write_bytes(b'damaged on disk')
    assert m.install.helper_apk(phone.folder, plugin_dir=pinned.plugin) is None
    assert m.install.fetch_helper(phone.folder, plugin_dir=pinned.plugin).read_bytes() == pinned.served.read_bytes()


def test_no_pin_no_helper_and_the_phone_page_says_so(phone, m, tmp_path):
    empty = tmp_path / 'no-pin'
    empty.mkdir()
    host = m.install.phone_host(phone.folder, plugin_dir=empty)
    assert host.helper_apk is None and host.helper_sha256 is None
    assert host._install_helper(None) is False  # refused before any adb
    assert m.install.fetch_helper(phone.folder, plugin_dir=empty) is None
    assert m.install.helper_status(phone.folder, plugin_dir=empty) == {
        'state': 'missing', 'line': "Saved logins on {name}'s phone need a Beacon update.", 'reason': 'helper.no_pin',
        'on_phone': False}
    (phone.folder / 'run' / 'first-start.json').write_text(json.dumps({'done': ['helper', 'stylus']}), encoding='utf-8')
    assert m.install.helper_status(phone.folder, plugin_dir=empty)['state'] == 'installed'


def test_an_unreachable_helper_leaves_the_phone_starting_without_it(phone, m, tmp_path):
    pinned = pinned_plugin(tmp_path)
    pinned.served.unlink()  # offline, or the asset isn't there
    adb = Session()
    result = m.install.phone_host(phone.folder, plugin_dir=pinned.plugin).first_start(adb)
    assert result['helper'] == 'failed' and result['autofill'] == 'waits-for-helper' and result['stylus'] == 'ok'
    assert adb.installed == []


# -- the heartbeat (dashboard watcher) and the activity notes (agent calls, setup inputs) the keeper reads -------------------

def keeper_state():
    import psutil
    me = psutil.Process()
    return {'running': True, 'pid': me.pid, 'create_time': me.create_time(), 'started_at_epoch': time.time() - 2 * 86400}


def test_the_watchers_heartbeat_is_what_the_keeper_reads(phone, m, monkeypatch):
    clock = [1000.0]
    monkeypatch.setattr(m.api, '_heartbeat_at', None)
    assert m.api.heartbeat(phone.home, clock=lambda: clock[0]) is True
    beat = phone.folder / 'run' / 'heartbeat'
    assert beat.is_file()
    clock[0] += 30
    assert m.api.heartbeat(phone.home, clock=lambda: clock[0]) is False  # once a minute
    clock[0] += 31
    assert m.api.heartbeat(phone.home, clock=lambda: clock[0]) is True
    conf, state, now = {'heartbeat_hours': 24, 'idle_minutes': 30}, keeper_state(), time.time()
    (phone.folder / 'run' / 'activity').touch()
    assert m.keeper.decide(phone.folder, now, conf, state) is None
    os.utime(beat, (now - 25 * 3600, now - 25 * 3600))
    assert m.keeper.decide(phone.folder, now, conf, state) == 'hermes-silent'


def test_the_watcher_pass_writes_the_heartbeat_from_beacons_own_home(phone, m, monkeypatch):
    monkeypatch.setattr(m.api, '_heartbeat_at', None)
    monkeypatch.setitem(sys.modules, 'beacon_mobile', SimpleNamespace(home=lambda: phone.home))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_approvals', SimpleNamespace(sweep=lambda: None))
    m.api.sweep_approvals()
    assert (phone.folder / 'run' / 'heartbeat').is_file()


def test_activity_from_an_agent_call_and_a_setup_input_keeps_the_phone_on(phone, m):
    """L3 notes activity on every agent call, L2's view on setup input (both through host.note_activity); the keeper's idle
    stop reads that file."""
    conf, state, now = {'heartbeat_hours': 24, 'idle_minutes': 30}, keeper_state(), time.time()
    (phone.folder / 'run' / 'heartbeat').touch()
    assert m.keeper.decide(phone.folder, now, conf, state) == 'idle'
    m.tools._host(phone.folder).note_activity()
    assert m.keeper.decide(phone.folder, now, conf, state) is None
    os.utime(phone.folder / 'run' / 'activity', (now - 3600, now - 3600))
    m.api.Deps(SimpleNamespace(home=lambda: phone.home)).host(phone.folder, m.lease.PhoneLease(phone.folder)).note_activity()
    assert m.keeper.decide(phone.folder, now, conf, state) is None


def test_no_heartbeat_or_activity_brings_a_removed_phone_back(phone, m, monkeypatch):
    host = m.install.phone_host(phone.folder)
    shutil.rmtree(phone.folder)
    host.write_heartbeat()
    host.note_activity()
    monkeypatch.setattr(m.api, '_heartbeat_at', None)
    assert m.api.heartbeat(phone.home) is False
    assert not phone.folder.exists()


# -- the keeper's interpreter (found live on the demo: the dashboard runs on Hermes' bare base interpreter) ------------------

def test_a_spawn_whose_intermediate_had_no_psutil_still_gets_its_birth(phone, m, tmp_path):
    import subprocess
    child = subprocess.Popen([sys.executable, '-I', '-c', 'import time; time.sleep(30)'])
    try:
        class NoPsutil(m.host.Spawner):
            def _intermediate(self, spec, env):  # what keeper.py spawn wrote under an interpreter without psutil
                out = json.loads(Path(spec).read_text(encoding='utf-8'))['out']
                Path(out).write_text(json.dumps({'pid': child.pid, 'create_time': None, 'mode': 'breakaway'}), encoding='utf-8')
        res = NoPsutil(phone.folder, python=sys.executable, check_job=lambda pid: False).spawn('emulator', ['x'], {})
        assert res['create_time'] and m.host.alive(res['pid'], res['create_time'])  # not taken for dead (and orphaned)
    finally:
        child.kill()


def test_the_keeper_runs_on_an_interpreter_that_has_psutil(m, monkeypatch):
    import subprocess
    monkeypatch.setattr(m.host, '_KEEPER_PYTHONS', {})
    env = {k: v for k, v in os.environ.items() if k.upper() in ('SYSTEMROOT', 'WINDIR', 'PATH', 'TEMP', 'TMP')}
    bare = getattr(sys, '_base_executable', None)
    if not bare or subprocess.run([bare, '-I', '-c', 'import psutil'], env=env, capture_output=True).returncode == 0:
        pytest.skip('no interpreter without psutil on this machine to stand for the bare one')
    assert m.host.keeper_python(env, candidates=[bare, sys.executable]) == sys.executable  # the bare one is passed over
    venv = m.host._venv_pythons()
    assert venv and subprocess.run([str(venv[0]), '-I', '-c', 'import psutil'], env=env).returncode == 0
    monkeypatch.setattr(m.host, '_KEEPER_PYTHONS', {})
    calls = []
    run = lambda cmd, **kw: calls.append(cmd[0]) or SimpleNamespace(returncode=0 if cmd[0] == 'good' else 1)  # noqa: E731
    assert m.host.keeper_python(env, candidates=['bad', 'good'], run=run) == 'good'
    assert m.host.keeper_python(env, candidates=['bad', 'good'], run=run) == 'good' and calls == ['bad', 'good']  # checked once
    assert m.host.keeper_python(env, candidates=['bad'], run=run) is None


def test_a_dashboard_on_the_bare_interpreter_gives_the_keeper_the_venvs(m):
    """As Hermes' launcher runs the dashboard: the base interpreter with ``-I`` and the venv's site-packages put on sys.path by
    hand. keeper_python, asked from there, must pick an interpreter that imports psutil on its own (the venv's), never the bare
    one. On a stock install this venv is Hermes' own (here: the hermes-agent venv the tests run in)."""
    import subprocess
    import psutil
    bare = getattr(sys, '_base_executable', None)
    env = {k: v for k, v in os.environ.items() if k.upper() in ('SYSTEMROOT', 'WINDIR', 'PATH', 'TEMP', 'TMP')}
    if not bare or Path(bare) == Path(sys.executable) or subprocess.run([bare, '-I', '-c', 'import psutil'], env=env,
                                                                          capture_output=True).returncode == 0:
        pytest.skip('no bare interpreter without psutil here')
    site = str(Path(psutil.__file__).resolve().parents[1])
    code = ('import sys, importlib.util; sys.path.insert(0, %r); s = importlib.util.spec_from_file_location("h", %r); '
            'h = importlib.util.module_from_spec(s); s.loader.exec_module(h); '
            'print(h.keeper_python({k: v for k, v in __import__("os").environ.items() if k.upper() in '
            '("SYSTEMROOT", "WINDIR", "PATH", "TEMP", "TMP")}))') % (site, str(PLUGIN / 'agent_phone_host.py'))
    picked = subprocess.run([bare, '-I', '-c', code], env=env, capture_output=True, text=True, timeout=120).stdout.strip()
    assert picked and picked != 'None' and Path(picked) != Path(bare)
    assert subprocess.run([picked, '-I', '-c', 'import psutil'], env=env).returncode == 0


def test_no_interpreter_for_the_keeper_means_no_start_and_a_plain_line(phone, m, monkeypatch):
    spawned = []
    monkeypatch.setattr(m.host, 'keeper_python', lambda env, **kw: None)
    host = m.install.phone_host(phone.folder)
    host.spawner = SimpleNamespace(spawn=lambda *a, **kw: spawned.append(a))
    monkeypatch.setattr(host, 'adopt', lambda check_anr=True: None)
    with pytest.raises(m.host.PhoneError) as refused:
        host._start(False, 'auto', False)
    assert str(refused.value) == "The phone can't start on this Hermes. Update Beacon." and spawned == []


# -- L1b's proto contract as L1a's proto_ok ---------------------------------------------------------------------------------

def test_the_proto_contract_check_is_the_fallbacks_proto_ok(m, tmp_path):
    proto = FIXTURES / 'emulator_controller_37_2_13_excerpt.proto'
    assert m.grpc.check_proto(proto.read_text(encoding='utf-8')) == []
    moved = tmp_path / 'emulator_controller.proto'
    moved.write_text(proto.read_text(encoding='utf-8').replace('rpc streamScreenshot', 'rpc streamScreenshotV2'), encoding='utf-8')
    assert m.grpc.check_proto(moved.read_text(encoding='utf-8'))


# -- Remove: L2's route -> L1a's remove_phone -> L9's forget_phone ----------------------------------------------------------

def test_remove_forgets_the_phone_and_only_the_logins_it_saved(phone, m, monkeypatch):
    from agent.vault_store import VaultStore
    monkeypatch.setenv('HERMES_HOME', str(phone.home))
    store = VaultStore(base_dir=phone.home / 'vault')
    fields = lambda who: {'identifier_type': 'email', 'identifier': who + '@' + 'example.com', 'password': 'Synthetic-' + who}  # noqa: E731
    from_phone = store.add_item('login', 'Kanto Foods', fields('saved.on.phone'), origin='android://dev.beacon.fixture.shop')
    elsewhere = store.add_item('login', 'Kanto Foods web', fields('saved.elsewhere'), origin='https://kanto.example')
    sidecar = m.vault.Sidecar(m.vault.sidecar_path(phone.home))
    sidecar.update(from_phone.id, package='dev.beacon.fixture.shop', saved_on_phone=True)
    sidecar.update(elsewhere.id, package='dev.beacon.fixture.shop')  # pinned by the phone, saved elsewhere: it stays
    (phone.folder / 'run' / 'frames-5640-abc.bin').write_bytes(b'\0' * 64)
    done = m.install.remove_phone(phone.home, delete_logins=True)
    assert done['removed'] is True and done['logins_deleted'] == 1 and done['freed_bytes'] > 0
    left = {item.id for item in VaultStore(base_dir=phone.home / 'vault').list_items()}
    assert left == {elsewhere.id}
    assert not phone.folder.exists() and not phone.folder.parent.exists() and not phone.folder.parents[1].exists()
    assert m.install.read_pointer(phone.home) is None
    assert not m.vault.sidecar_path(phone.home).exists() and not m.vault.sidecar_path(phone.home).parent.exists()
    assert m.install.remove_phone(phone.home)['removed'] is False  # idempotent


def test_remove_keeps_other_files_beside_the_sidecar(phone, m, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(phone.home))
    place = m.vault.sidecar_path(phone.home).parent
    place.mkdir(parents=True)
    (place / 'someone-elses.txt').write_text('kept', encoding='utf-8')
    m.vault.Sidecar(m.vault.sidecar_path(phone.home)).update('vault_' + '0' * 32, package='x', saved_on_phone=True)
    assert m.install.remove_phone(phone.home)['logins_deleted'] == 0  # logins stay unless asked
    assert (place / 'someone-elses.txt').read_text(encoding='utf-8') == 'kept' and not m.vault.sidecar_path(phone.home).exists()


# -- the vault's autofill refresh, on the setup side (L2) and the agent side (L3) -------------------------------------------

@pytest.fixture
def recorded_adb(m, monkeypatch):
    sent = []
    monkeypatch.setattr(m.adb.PhoneAdb, 'shell', lambda self, args, timeout=60.0, **kw: sent.append((self.adb_port, args)))
    return sent


def test_setup_and_agent_vaults_refresh_autofill_through_the_phones_adb(phone, m, monkeypatch, recorded_adb):
    made = {}
    monkeypatch.setattr(m.vault.PhoneVault, 'for_dashboard', classmethod(lambda cls, *a, **kw: made.setdefault('setup', kw) and
                                                                         SimpleNamespace(handler=lambda s, owner: None)))
    monkeypatch.setattr(m.vault.PhoneVault, 'for_agent', classmethod(lambda cls, *a, **kw: made.setdefault('agent', kw) and
                                                                       SimpleNamespace()))
    monkeypatch.setattr(m.channel, 'connect', lambda adb, events=None, **kw: SimpleNamespace(adb=adb))
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_redact', load('agent_phone_redact'))
    state = {'ports': {'console': 5640, 'grpc': 8554}}
    host = m.install.phone_host(phone.folder)
    deps = m.api.Deps(SimpleNamespace(home=lambda: phone.home))
    assert deps.channel(host, state, lambda kind, line: None) is not None
    made['setup']['refresh']()
    m.tools._vaults.clear()
    m.tools._vault_for('owner', 'default', 'default')
    m.tools._phones[('owner', 'default')] = m.tools.Phone(host, state)
    try:
        made['agent']['refresh']()
    finally:
        m.tools._phones.clear()
        m.tools._vaults.clear()
    assert recorded_adb == [(5641, ['cmd', 'autofill', 'reset'])] * 2


# -- L1a's start gate before any start, from the routes and from the agent's tool ---------------------------------------------

@pytest.fixture
def dashboard():
    """Hermes' /api/status as the start gate reads it; ``answer['auth_required']`` is what it says."""
    answer = {'auth_required': False}

    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def do_GET(self):
            body = json.dumps(answer).encode() if self.path == '/api/status' else b''
            self.send_response(200 if body else 404)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield SimpleNamespace(answer=answer, address=f'http://127.0.0.1:{server.server_address[1]}')
    server.shutdown()


def test_the_start_gate_comes_before_the_agents_start(phone, m, monkeypatch, dashboard):
    import hermes_constants
    monkeypatch.setattr(hermes_constants, 'get_process_hermes_home', lambda: phone.home)
    started = []
    monkeypatch.setattr(m.host.PhoneHost, 'start', lambda self: started.append(self.folder.root) or {'running': True})
    m.api.Deps(SimpleNamespace(home=lambda: phone.home)).keep_dashboard(phone.home, dashboard.address)
    assert m.install.read_pointer(phone.home)['dashboard'] == dashboard.address
    with pytest.raises(m.tools.Refusal) as refused:
        m.tools.running_phone(phone.folder)
    assert refused.value.message == 'Turn on Hermes sign-in first.' and started == []
    dashboard.answer['auth_required'] = True
    assert isinstance(m.tools.running_phone(phone.folder), m.tools.Phone) and started == [phone.folder]


def test_the_routes_start_gate_keeps_the_name_for_the_app(phone, m, dashboard):
    deps = m.api.Deps(SimpleNamespace(home=lambda: phone.home))
    assert deps.start_gate(phone.home) == (False, "{name}'s phone can't start while Hermes' dashboard is off.")
    deps.keep_dashboard(phone.home, dashboard.address)
    assert deps.start_gate(phone.home) == (False, 'Turn on Hermes sign-in first.')
    dashboard.answer['auth_required'] = True
    assert deps.start_gate(phone.home) == (True, None)
    pointer = m.install.read_pointer(phone.home)
    assert pointer['folder'] == str(phone.folder) and pointer['profile'] == 'default'


# -- L1b's public stream cancel, which L2's live view uses to end a still screen's stream -----------------------------------

def test_cancel_ends_a_stream_that_waits_on_a_still_screen(m):
    server = socket.socket()
    server.bind(('127.0.0.1', 0))
    server.listen(1)

    def still_screen():
        """Reads the call and answers nothing, as the emulator does for a screen that doesn't change; closes at the client's
        end of the connection, as any server does."""
        conn, _ = server.accept()
        with conn:
            while conn.recv(65536):
                pass
    threading.Thread(target=still_screen, daemon=True).start()
    client = m.grpc.EmulatorGrpc(server.getsockname()[1], 'synthetic-token', timeout=30).connect()
    ended = threading.Event()

    def watch():
        try:
            for _ in client.screenshots(width=720, height=1600):
                pass
        except Exception:  # health: allow BLE001 -- the stream ends with the connection
            pass
        ended.set()
    threading.Thread(target=watch, daemon=True).start()
    time.sleep(0.3)
    assert not ended.is_set()
    t0 = time.monotonic()
    client.cancel()
    assert ended.wait(5) and time.monotonic() - t0 < 5
    client.close()
    server.close()
