"""The agent phone's install (agent_phone_install.py) against synthetic SDK index XMLs and a local HTTP server; never Google's 2.4 GB.

The server speaks just enough HTTP for the installer: Range (206), a connection dropped mid-file, a damaged first copy, a server that
ignores Range, and 404s. The packages are tiny zips laid out like Google's (one top folder, ``source.properties``, the gRPC proto).
Nothing here runs Google's binaries: the emulator test run is a fake runner that records its arguments and environment.
"""
import hashlib
import http.server
import importlib.util
import io
import json
import os
import stat
import subprocess
import sys
import textwrap
import threading
import time
import zipfile
from pathlib import Path

import pytest

PLUGIN = Path(__file__).resolve().parents[1]


def _load(name):
    key = 'beacon_' + name
    if key not in sys.modules:
        spec = importlib.util.spec_from_file_location(key, PLUGIN / f'{name}.py')
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        spec.loader.exec_module(module)
    return sys.modules[key]


inst = _load('agent_phone_install')
WIN = {'os': 'windows', 'arch': 'x64', 'abi': 'x86_64', 'cpu': 'x86_64', 'supported': True}
LINUX = {'os': 'linux', 'arch': 'x64', 'abi': 'x86_64', 'cpu': 'x86_64', 'supported': True}
MAC_ARM = {'os': 'macosx', 'arch': 'aarch64', 'abi': 'arm64-v8a', 'cpu': 'arm64', 'supported': True}
SDK_TEXT = 'Terms and Conditions\n\nThis is a synthetic SDK licence for tests.\n  Indented line, trailing space \n\u00e9 accented'
ARM_TEXT = 'Synthetic ARM DBT licence text.\n'
PREVIEW_TEXT = 'Synthetic preview licence.'
OK = {'ok': True, 'needs_continue': False, 'continued': False, 'rows': []}


def make_zip(top, files, modes=None):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, 'w', zipfile.ZIP_DEFLATED) as z:
        z.writestr(zipfile.ZipInfo(top + '/'), b'')
        for rel, data in files.items():
            info = zipfile.ZipInfo(f'{top}/{rel}')
            info.external_attr = ((modes or {}).get(rel, 0o100644)) << 16
            z.writestr(info, data)
    return buffer.getvalue()


def emulator_zip(revision='37.2.13'):
    return make_zip('emulator', {'source.properties': f'Pkg.Revision={revision}\nPkg.Path=emulator\n',
                                 'emulator': b'#!/bin/sh\necho fake\n', 'emulator.exe': b'MZ fake',
                                 'lib/emulator_controller.proto': 'syntax = "proto3";\nservice EmulatorController {}\n'},
                    modes={'emulator': 0o100755})


def image_zip(abi='x86_64', revision='7'):
    return make_zip(abi, {'source.properties': f'Pkg.Revision={revision}\nSystemImage.Abi={abi}\n', 'system.img': os.urandom(4096)})


def sha1(data):
    return hashlib.sha1(data).hexdigest()


def esc(text):
    return text.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def archive_xml(url, data, host_os=None, host_arch=None):
    hos = f'<host-os>{host_os}</host-os>' if host_os else ''
    har = f'<host-arch>{host_arch}</host-arch>' if host_arch else ''
    return (f'<archive><complete><size>{len(data)}</size><checksum type="sha1">{sha1(data)}</checksum><url>{url}</url></complete>'
            f'{hos}{har}</archive>')


def repo_xml(emulators):
    """``emulators``: (revision, channel, {host_os: (url, data)}) with Google's real layout (both same-path entries, beta first)."""
    packages = []
    for revision, channel, archives in emulators:
        major, minor, micro = revision.split('.')
        body = ''.join(archive_xml(url, data, o, 'aarch64' if o == 'macosx-arm' else 'x64')
                       .replace('<host-os>macosx-arm</host-os>', '<host-os>macosx</host-os>')
                       for o, (url, data) in archives.items())
        packages.append(f'''<remotePackage path="emulator"><type-details xsi:type="generic:genericDetailsType"/>
<revision><major>{major}</major><minor>{minor}</minor><micro>{micro}</micro></revision><display-name>Android Emulator</display-name>
<uses-license ref="android-sdk-license"/><channelRef ref="{channel}"/><archives>{body}</archives></remotePackage>''')
    pt = archive_xml('platform-tools_r37.0.1-win.zip', b'pt', 'windows')
    return f'''<?xml version='1.0' encoding='utf-8'?>
<sdk:sdk-repository xmlns:sdk="http://schemas.android.com/sdk/android/repo/repository2/03" xmlns:generic="http://schemas.android.com/repository/android/generic/02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<license id="android-sdk-license" type="text">{esc(SDK_TEXT)}</license>
<license id="android-sdk-preview-license" type="text">{esc(PREVIEW_TEXT)}</license>
<channel id="channel-0">stable</channel><channel id="channel-1">beta</channel><channel id="channel-2">dev</channel>
<remotePackage path="platform-tools"><type-details xsi:type="generic:genericDetailsType"/><revision><major>37</major><minor>0</minor><micro>1</micro></revision>
<uses-license ref="android-sdk-license"/><channelRef ref="channel-0"/><archives>{pt}</archives></remotePackage>
{"".join(packages)}
</sdk:sdk-repository>'''


def img_xml(images):
    """``images``: (abi, revision, licence, url, data)."""
    packages = ''.join(f'''<remotePackage path="system-images;android-36;google_apis_playstore;{abi}">
<type-details xsi:type="sys-img:sysImgDetailsType"><api-level>36</api-level><abi>{abi}</abi></type-details>
<revision><major>{revision}</major></revision><uses-license ref="{lic}"/><channelRef ref="channel-0"/>
<archives>{archive_xml(url, data)}</archives></remotePackage>''' for abi, revision, lic, url, data in images)
    return f'''<?xml version='1.0' encoding='utf-8'?>
<sys-img:sdk-sys-img xmlns:sys-img="http://schemas.android.com/sdk/android/repo/sys-img2/03" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
<license id="android-sdk-license" type="text">{esc(SDK_TEXT)}</license>
<license id="android-sdk-arm-dbt-license" type="text">{esc(ARM_TEXT)}</license>
<channel id="channel-0">stable</channel><channel id="channel-1">beta</channel>
{packages}
</sys-img:sdk-sys-img>'''


class Server:
    """Files by path, with Range and per-path behaviours: 'drop' (close after N bytes, once), 'corrupt' (once or always),
    'norange', 404 for unknown paths. Records every request (method, path, Range)."""

    def __init__(self):
        self.files, self.drop, self.corrupt, self.norange, self.requests = {}, {}, {}, set(), []
        server = self

        class Handler(http.server.BaseHTTPRequestHandler):
            protocol_version = 'HTTP/1.1'

            def log_message(self, *args):
                pass

            def _serve(self, body):
                path = self.path.split('?')[0]
                rng = self.headers.get('Range')
                server.requests.append((self.command, path, rng))
                data = server.files.get(path)
                if data is None:
                    self.send_response(404)
                    self.send_header('Content-Length', '0')
                    self.end_headers()
                    return
                mode = server.corrupt.get(path)
                if mode:
                    data = bytes(b ^ 0xFF for b in data[:16]) + data[16:]
                    if mode == 'once':
                        server.corrupt.pop(path)
                start = 0
                if rng and path not in server.norange:
                    start = int(rng.split('=')[1].split('-')[0])
                    if start >= len(data):
                        self.send_response(416)
                        self.send_header('Content-Length', '0')
                        self.end_headers()
                        return
                    self.send_response(206)
                    self.send_header('Content-Range', f'bytes {start}-{len(data) - 1}/{len(data)}')
                else:
                    self.send_response(200)
                chunk = data[start:]
                self.send_header('Content-Length', str(len(chunk)))
                self.send_header('Accept-Ranges', 'bytes')
                self.end_headers()
                if not body:
                    return
                cut = server.drop.pop(path, None)
                if cut is not None:
                    self.wfile.write(chunk[:cut])
                    self.wfile.flush()
                    self.close_connection = True
                    self.connection.shutdown(2)
                    return
                self.wfile.write(chunk)

            def do_GET(self):
                self._serve(True)

            def do_HEAD(self):
                self._serve(False)

        self.httpd = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        self.base = f'http://127.0.0.1:{self.httpd.server_address[1]}/repo/'
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()

    def put(self, rel, data):
        self.files['/repo/' + rel] = data.encode('utf-8') if isinstance(data, str) else data

    def archive_gets(self):
        return [r for r in self.requests if r[1].endswith('.zip')]

    def close(self):
        self.httpd.shutdown()
        self.httpd.server_close()


@pytest.fixture
def server():
    s = Server()
    yield s
    s.close()


@pytest.fixture
def repo(server):
    """A full synthetic repository: beta 37.3.9 and stable 37.2.13 emulators, x86_64 and arm64 images."""
    emu, emu_beta, img, img_arm = emulator_zip(), emulator_zip('37.3.9'), image_zip(), image_zip('arm64-v8a')
    server.put('emulator-windows_x64-1.zip', emu)
    server.put('emulator-linux_x64-1.zip', emu)
    server.put('emulator-darwin_aarch64-1.zip', emu)
    server.put('repository2-3.xml', repo_xml([
        ('37.3.9', 'channel-1', {'windows': ('emulator-windows_x64-2.zip', emu_beta), 'linux': ('emulator-linux_x64-2.zip', emu_beta)}),
        ('37.2.13', 'channel-0', {'windows': ('emulator-windows_x64-1.zip', emu), 'linux': ('emulator-linux_x64-1.zip', emu),
                                  'macosx-arm': ('emulator-darwin_aarch64-1.zip', emu)})]))
    server.put('sys-img/google_apis_playstore/x86_64-36_r07.zip', img)
    server.put('sys-img/google_apis_playstore/arm64-v8a-36_r07.zip', img_arm)
    server.put('sys-img/google_apis_playstore/sys-img2-3.xml', img_xml([
        ('arm64-v8a', '7', 'android-sdk-arm-dbt-license', 'arm64-v8a-36_r07.zip', img_arm),
        ('x86_64', '7', 'android-sdk-license', 'x86_64-36_r07.zip', img)]))
    return {'emu': emu, 'img': img, 'img_arm': img_arm}


class Runner:
    def __init__(self, fail=None):
        self.calls, self.fail = [], fail

    def __call__(self, args):
        self.calls.append(list(args))
        code = 1 if self.fail and self.fail in args else 0
        return subprocess.CompletedProcess(args, code, b'', b'')


def accept_all(folder, host=WIN, base=None, server=None):
    picks = inst.plan(inst.fetch_indexes(folder, server.base if server else base), host)
    inst.accept_licences(folder, inst.licences(picks), by='owner (test)')
    return picks


# -- the index -------------------------------------------------------------------------------------------------------

def test_plan_picks_the_pinned_stable_emulator_and_this_computers_archives(tmp_path, server, repo):
    indexes = inst.fetch_indexes(tmp_path / 'phone', server.base)
    win = inst.plan(indexes, WIN)
    assert [p['key'] for p in win] == ['emulator', 'image']
    assert win[0]['revision'] == '37.2.13' and win[0]['url'].endswith('/repo/emulator-windows_x64-1.zip')
    assert win[0]['sha1'] == sha1(repo['emu']) and win[0]['size'] == len(repo['emu']) and not win[0]['fallback']
    assert win[1]['url'].endswith('/repo/sys-img/google_apis_playstore/x86_64-36_r07.zip')
    assert win[1]['dest'] == 'system-images/android-36/google_apis_playstore/x86_64'
    assert inst.plan(indexes, LINUX)[0]['url'].endswith('emulator-linux_x64-1.zip')
    arm = inst.plan(indexes, MAC_ARM)
    assert arm[0]['url'].endswith('emulator-darwin_aarch64-1.zip')
    assert arm[1]['licence'] == 'android-sdk-arm-dbt-license' and arm[1]['dest'].endswith('/arm64-v8a')
    # platform-tools is no longer downloaded (Root, 17 Nov): not in any plan, its licence math, or its sizes
    for picks in (win, arm, inst.plan(indexes, LINUX)):
        assert all('platform-tools' not in p['path'] and 'platform-tools' not in p['url'] for p in picks)
    with pytest.raises(inst.InstallError) as refused:
        inst.plan(indexes, {'os': 'windows', 'arch': 'aarch64', 'abi': 'arm64-v8a', 'cpu': 'arm64', 'supported': False})
    assert refused.value.code == 'cannot_run'


def test_host_platform_names_follow_googles_index():
    assert inst.host_platform('win32', 'AMD64') == WIN
    assert inst.host_platform('linux', 'x86_64') == LINUX
    assert inst.host_platform('darwin', 'arm64') == MAC_ARM
    assert not inst.host_platform('win32', 'ARM64')['supported']
    assert not inst.host_platform('linux', 'aarch64')['supported']


def test_index_with_a_document_type_is_refused():
    with pytest.raises(inst.InstallError):
        inst.parse_index(b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "b">]><x>&a;</x>', 'https://example.com/')


def test_terms_gives_the_screen_its_licences_and_sizes_in_one_call(tmp_path, server, repo):
    shown = inst.terms(tmp_path / 'phone', base_url=server.base, host=MAC_ARM)
    assert [x['id'] for x in shown['licences']] == ['android-sdk-license', 'android-sdk-arm-dbt-license']
    assert shown['licences'][1]['text'] == ARM_TEXT
    assert shown['sizes']['download_bytes'] == len(repo['emu']) + len(repo['img_arm'])
    assert server.archive_gets() == []


def test_the_phone_folder_is_never_inside_the_hermes_home(tmp_path, server, repo):
    home = tmp_path / 'hermes-home'
    with pytest.raises(ValueError):
        inst.run_install(home / 'plugin-data' / 'beacon' / 'agent-phone', checks=OK, hermes_home=home, host=WIN,
                         base_url=server.base, runner=Runner(), total_memory=64 << 30)
    assert server.requests == [] and not home.exists()


def test_sizes_count_only_what_is_still_to_get(tmp_path, server, repo):
    picks = inst.plan(inst.fetch_indexes(tmp_path / 'phone', server.base), WIN)
    s = inst.sizes(picks, tmp_path / 'phone')
    assert s['download_bytes'] == len(repo['emu']) + len(repo['img'])
    assert s['installed_bytes'] == inst.INSTALLED_BYTES['emulator'] + inst.INSTALLED_BYTES['image']
    assert s['peak_bytes'] == s['installed_bytes'] + max(len(repo['emu']), len(repo['img']))


# -- Google's terms --------------------------------------------------------------------------------------------------

def test_licences_are_verbatim_and_recorded_only_on_accept(tmp_path, server, repo):
    folder = tmp_path / 'phone'
    picks = inst.plan(inst.fetch_indexes(folder, server.base), WIN)
    shown = inst.licences(picks)
    assert shown == [{'id': 'android-sdk-license', 'text': SDK_TEXT}]  # verbatim: whitespace, blank lines, accents
    assert [x['id'] for x in inst.licences(inst.plan(inst.fetch_indexes(folder, server.base), MAC_ARM))] == [
        'android-sdk-license', 'android-sdk-arm-dbt-license']
    # nothing is recorded, and nothing downloads, before the owner's Accept
    with pytest.raises(inst.InstallError) as refused:
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30)
    assert refused.value.code == 'licences' and refused.value.line == inst.WORDS['licences']
    assert server.archive_gets() == [] and not (folder / 'sdk' / 'licenses').exists()
    inst.accept_licences(folder, shown, by='owner (test)')
    record = (folder / 'sdk' / 'licenses' / 'android-sdk-license').read_bytes()
    assert record == b'\r\n' + hashlib.sha1(SDK_TEXT.encode('utf-8')).hexdigest().encode() and len(record) == 42
    receipt = json.loads((folder / 'run' / 'licences.json').read_text(encoding='utf-8'))['accepted']
    assert receipt[0]['by'] == 'owner (test)' and receipt[0]['text_sha256'] == hashlib.sha256(SDK_TEXT.encode()).hexdigest()
    assert inst.licences_accepted(folder, shown)
    inst.accept_licences(folder, shown, by='owner (test)')  # twice: one record line, one receipt row
    assert (folder / 'sdk' / 'licenses' / 'android-sdk-license').read_bytes() == record
    assert len(json.loads((folder / 'run' / 'licences.json').read_text(encoding='utf-8'))['accepted']) == 1
    assert not inst.licences_accepted(folder, [{'id': 'android-sdk-license', 'text': SDK_TEXT + ' changed'}])
    (folder / 'sdk' / 'licenses' / 'android-sdk-license').write_bytes(b'')  # a receipt alone is not Google's record
    assert not inst.licences_accepted(folder, shown)


# -- downloads -------------------------------------------------------------------------------------------------------

def _get(server, name, data, dest, **kw):
    server.put(name, data)
    return inst.download(server.base + name, dest, len(data), sha1(data), **kw)


def test_download_resumes_with_range(tmp_path, server):
    data = os.urandom(3 * inst.CHUNK + 123)
    dest = tmp_path / 'a.zip'
    (tmp_path / 'a.zip.part').write_bytes(data[:inst.CHUNK + 7])
    seen = []
    _get(server, 'a.zip', data, dest, progress=lambda **e: seen.append(e))
    assert dest.read_bytes() == data and not (tmp_path / 'a.zip.part').exists()
    assert server.requests[-1][2] == f'bytes={inst.CHUNK + 7}-'
    assert sum(e['bytes'] for e in seen if 'bytes' in e) == len(data)


def test_a_dropped_connection_stops_with_try_again_and_the_next_run_resumes(tmp_path, server):
    data = os.urandom(2 * inst.CHUNK + 5)
    server.drop['/repo/b.zip'] = inst.CHUNK + 11
    dest = tmp_path / 'b.zip'
    with pytest.raises(inst.InstallError) as stopped:
        _get(server, 'b.zip', data, dest, timeout=5)
    assert stopped.value.code == 'offline' and stopped.value.retry
    assert stopped.value.line == "Download stopped. Check the Hermes computer's internet."
    kept = (tmp_path / 'b.zip.part').stat().st_size
    assert 0 < kept <= inst.CHUNK + 11
    _get(server, 'b.zip', data, dest)
    assert dest.read_bytes() == data and server.requests[-1][2] == f'bytes={kept}-'


def test_a_damaged_download_is_fetched_again_once(tmp_path, server):
    data = os.urandom(50_000)
    server.corrupt['/repo/c.zip'] = 'once'
    seen = []
    _get(server, 'c.zip', data, tmp_path / 'c.zip', progress=lambda **e: seen.append(e))
    assert (tmp_path / 'c.zip').read_bytes() == data
    assert [e['line'] for e in seen if e['event'] == 'damaged'] == ['A download was damaged. Getting it again.']
    assert sum(e['bytes'] for e in seen if 'bytes' in e) == len(data)
    server.corrupt['/repo/d.zip'] = 'always'
    with pytest.raises(inst.InstallError) as twice:
        _get(server, 'd.zip', data, tmp_path / 'd.zip')
    assert twice.value.code == 'offline' and not (tmp_path / 'd.zip.part').exists() and not (tmp_path / 'd.zip').exists()


def test_a_server_that_ignores_range_starts_over(tmp_path, server):
    data = os.urandom(70_000)
    server.norange.add('/repo/e.zip')
    (tmp_path / 'e.zip.part').write_bytes(data[:1000])
    _get(server, 'e.zip', data, tmp_path / 'e.zip')
    assert (tmp_path / 'e.zip').read_bytes() == data


@pytest.fixture
def big(server, repo):
    """An emulator archive of several chunks, so a pause or cancel lands mid-file."""
    data = make_zip('emulator', {'source.properties': 'Pkg.Revision=37.2.13\n', 'emulator.exe': b'MZ', 'blob': os.urandom(3 * inst.CHUNK),
                                 'lib/emulator_controller.proto': 'service EmulatorController {}'})
    server.put('emulator-windows_x64-1.zip', data)
    server.put('repository2-3.xml', repo_xml([('37.2.13', 'channel-0', {'windows': ('emulator-windows_x64-1.zip', data)})]))
    return data


def test_pause_keeps_the_partial_file_and_resume_continues_it(tmp_path, server, big):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    control = inst.Control()

    def pause_at_first_bytes(**event):
        if event.get('event') == 'bytes':
            control.pause()
    with pytest.raises(inst.Paused):
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, control=control, progress=pause_at_first_bytes,
                         runner=Runner(), total_memory=64 << 30)
    part = folder / 'downloads' / 'emulator-windows_x64-1.zip.part'
    kept = part.stat().st_size
    assert 0 < kept < len(big)
    control.resume()
    inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, control=control, runner=Runner(), total_memory=64 << 30)
    ranges = [r[2] for r in server.archive_gets() if r[1].endswith('emulator-windows_x64-1.zip')]
    assert ranges == [None, f'bytes={kept}-'] and not part.exists()
    assert (folder / 'sdk' / 'emulator' / 'blob').stat().st_size == 3 * inst.CHUNK


def test_cancel_deletes_partial_files(tmp_path, server, big):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    control = inst.Control()

    def cancel_at_first_bytes(**event):
        if event.get('event') == 'bytes':
            control.cancel()
    with pytest.raises(inst.Cancelled):
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, control=control, progress=cancel_at_first_bytes,
                         runner=Runner(), total_memory=64 << 30)
    assert [p.name for p in (folder / 'downloads').iterdir() if p.name != 'index'] == []
    assert not (folder / 'sdk' / 'emulator').exists()


def test_cancel_during_the_second_package_keeps_the_first(tmp_path, server, repo):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    control = inst.Control()

    def cancel_in_image(**event):
        if event.get('event') == 'phase' and event.get('key') == 'image' and event.get('phase') == 'check':
            control.cancel()
    with pytest.raises(inst.Cancelled):
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, control=control, progress=cancel_in_image,
                         runner=Runner(), total_memory=64 << 30)
    assert (folder / 'sdk' / 'emulator' / 'source.properties').exists()
    assert not (folder / 'sdk' / 'system-images').exists()
    assert [p.name for p in (folder / 'downloads').iterdir() if p.name != 'index'] == []


# -- the whole install ------------------------------------------------------------------------------------------------

AVD_GOLDEN = textwrap.dedent("""\
    AvdId=beacon_agent_phone
    PlayStore.enabled=yes
    avd.ini.displayname=Beacon agent phone
    avd.ini.encoding=UTF-8
    disk.dataPartition.size=8G
    fastboot.forceColdBoot=no
    hw.accelerometer=yes
    hw.audioInput=yes
    hw.battery=yes
    hw.camera.back=emulated
    hw.camera.front=emulated
    hw.cpu.ncore=4
    hw.dPad=no
    hw.device.hash2=MD5:bc5032b2a871da511332401af3ac6bb0
    hw.device.manufacturer=Google
    hw.gps=yes
    hw.gpu.enabled=yes
    hw.gpu.mode=auto
    hw.initialOrientation=Portrait
    hw.keyboard=yes
    hw.mainKeys=no
    hw.ramSize=4096
    hw.sensors.orientation=yes
    hw.sensors.proximity=yes
    hw.trackBall=no
    runtime.network.latency=none
    runtime.network.speed=full
    vm.heapSize=512
    tag.display=Google Play
    hw.lcd.density=420
    hw.lcd.height=2400
    hw.lcd.width=1080
    # Unused
    # hw.sdCard=yes
    # sdcard.size=512M

    tag.id=google_apis_playstore
    abi.type=x86_64
    hw.cpu.arch=x86_64
    image.sysdir.1=system-images/android-36/google_apis_playstore/x86_64/

    # End of default configuration
    """)


def test_avd_is_written_from_googles_template(tmp_path):
    # The golden is the spike's config.ini that booted (S4); compared with line endings normalised (worktrees check out CRLF).
    assert inst.avd_config(WIN, 4096).replace('\r\n', '\n') == AVD_GOLDEN
    arm = inst.avd_config(MAC_ARM, 3072)
    assert 'abi.type=arm64-v8a\n' in arm and 'hw.cpu.arch=arm64\n' in arm and 'hw.ramSize=3072\n' in arm
    assert 'image.sysdir.1=system-images/android-36/google_apis_playstore/arm64-v8a/\n' in arm
    assert '{{' not in arm
    folder = tmp_path / 'phone'
    assert inst.write_avd(folder, WIN, 4096) and not inst.write_avd(folder, WIN, 3072)
    avd_home = folder / 'home' / 'avd'
    assert (avd_home / 'beacon_agent_phone.avd' / 'config.ini').read_text(encoding='utf-8') == AVD_GOLDEN
    ini = (avd_home / 'beacon_agent_phone.ini').read_text(encoding='utf-8').splitlines()
    assert ini == ['avd.ini.encoding=UTF-8', f'path={avd_home / "beacon_agent_phone.avd"}', 'target=android-36']


def test_full_install_from_a_local_mirror(tmp_path, server, repo, monkeypatch):
    monkeypatch.setattr(inst, 'HELPER_PIN', 'no-pin.json')  # the mirror serves no helper: never fetch the release's pinned one here
    hermes_home = tmp_path / 'hermes-home'
    hermes_home.mkdir()
    folder = tmp_path / 'Beacon' / 'agent-phone' / 'f1'
    accept_all(folder, server=server)
    runner, events = Runner(), []
    receipt = inst.run_install(folder, checks=OK, hermes_home=hermes_home, profile='default', dashboard='http://127.0.0.1:9',
                               host=WIN, base_url=server.base, runner=runner, total_memory=8 << 30,
                               progress=lambda **e: events.append(e))
    sdk = folder / 'sdk'
    assert (sdk / 'emulator' / 'source.properties').read_text().startswith('Pkg.Revision=37.2.13')
    assert (sdk / 'emulator' / 'lib' / 'emulator_controller.proto').exists()
    assert (sdk / 'system-images' / 'android-36' / 'google_apis_playstore' / 'x86_64' / 'system.img').exists()
    # platform-tools: the folder the emulator needs, empty (no adb, so the emulator starts no adb server); never downloaded
    assert (sdk / 'platform-tools').is_dir() and list((sdk / 'platform-tools').iterdir()) == []
    assert not any('platform-tools' in r[1] for r in server.requests)
    assert [p.name for p in (folder / 'downloads').iterdir()] == ['index']  # archives and staging deleted
    assert [p['key'] for p in receipt['packages']] == ['emulator', 'image'] and receipt['test'] == {'version': 'ok', 'accel': 'ok'}
    # the binary ran once with -version and once with -accel-check, from the phone's own folder, with its own homes
    assert [c[1:] for c in runner.calls] == [['-version'], ['-accel-check']]
    assert runner.calls[0][0] == str(sdk / 'emulator' / 'emulator.exe')
    config = (folder / 'home' / 'avd' / 'beacon_agent_phone.avd' / 'config.ini').read_text(encoding='utf-8')
    assert 'hw.ramSize=3072' in config  # below 16 GB of memory (guest_ram_mb)
    assert (folder / 'home' / 'adbkey').exists() and (folder / 'home' / 'adbkey.pub').exists()
    assert receipt['key_generated'] and receipt['avd_written']
    pointer = inst.read_pointer(hermes_home)
    assert pointer['folder'] == str(folder) and pointer['profile'] == 'default' and pointer['dashboard'] == 'http://127.0.0.1:9'
    assert set(pointer) == {'version', 'folder', 'key', 'profile', 'dashboard'}
    phases = [(e['key'], e['phase']) for e in events if e['event'] == 'phase']
    assert phases == [('emulator', 'download'), ('emulator', 'check'), ('emulator', 'set up'),
                      ('image', 'download'), ('image', 'check'), ('image', 'set up')]
    plan_event = next(e for e in events if e['event'] == 'plan')
    assert plan_event['download_bytes'] == len(repo['emu']) + len(repo['img'])
    # a second run gets nothing again
    before = len(server.archive_gets())
    again = inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=8 << 30)
    assert len(server.archive_gets()) == before and all(p.get('already') for p in again['packages'])
    assert [p['sha1'] for p in again['packages']] == [sha1(repo['emu']), sha1(repo['img'])]  # the download record is kept
    assert not again['avd_written'] and not again['key_generated']


def test_the_emulator_runs_with_the_phones_own_environment(tmp_path, server, repo, monkeypatch):
    monkeypatch.setenv('HERMES_HOME', str(tmp_path / 'some-hermes'))
    monkeypatch.setenv('HERMES_SECRET_TEST', 'x')
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    seen = {}
    real_run = subprocess.run

    def fake_run(args, **kw):
        if args and str(args[0]).endswith(('emulator.exe', 'emulator')):
            seen.setdefault('envs', []).append(kw['env'])
            seen.setdefault('cwd', kw.get('cwd'))
            return subprocess.CompletedProcess(args, 0, b'', b'')
        return real_run(args, **kw)
    monkeypatch.setattr(inst.subprocess, 'run', fake_run)
    inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, total_memory=64 << 30)
    assert len(seen['envs']) == 2 and seen['cwd'] == str(folder / 'tmp')
    for env in seen['envs']:
        assert not any(k.upper().startswith('HERMES') for k in env)
        for name in ('HOME', 'ANDROID_USER_HOME', 'ANDROID_EMULATOR_HOME', 'ANDROID_AVD_HOME', 'TEMP', 'TMP', 'ANDROID_HOME'):
            assert Path(env[name]).is_relative_to(folder), name


def test_a_computer_that_cant_run_it_stops_after_the_test_package(tmp_path, server, repo):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    with pytest.raises(inst.InstallError) as stopped:
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(fail='-accel-check'), total_memory=64 << 30)
    assert stopped.value.line == "This computer can't run a virtual phone."
    assert stopped.value.fix == 'Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.'
    assert not (folder / 'sdk' / 'system-images').exists()  # the 1.9 GB image never downloaded
    assert not any('x86_64-36' in r[1] for r in server.requests)
    with pytest.raises(inst.InstallError) as broken:
        inst.run_install(folder, checks=OK, host=LINUX, base_url=server.base, runner=Runner(fail='-version'), total_memory=64 << 30)
    assert broken.value.code == 'cannot_run' and broken.value.fix == inst.FIX['broken']


def test_the_checks_must_pass_before_any_download(tmp_path, server, repo):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    server.requests.clear()
    for checks in (None, {}, {'ok': False}, {'ok': True, 'needs_continue': True, 'continued': False}):
        with pytest.raises(inst.InstallError) as refused:
            inst.run_install(folder, checks=checks, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30)
        assert refused.value.code == 'checks'
    assert server.requests == []
    inst.run_install(folder, checks={'ok': True, 'needs_continue': True, 'continued': True}, host=WIN, base_url=server.base,
                     runner=Runner(), total_memory=64 << 30)
    assert (folder / 'sdk' / 'emulator').exists()


def test_one_install_per_folder_at_a_time(tmp_path, server, repo):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    with inst._file_lock(folder / 'run' / 'install.lock', wait=0):
        with pytest.raises(inst.InstallError) as busy:
            inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30)
    assert busy.value.code == 'busy' and server.archive_gets() == []


def test_not_enough_space_names_the_gigabytes(tmp_path, server, repo, monkeypatch):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    monkeypatch.setattr(inst, '_free_bytes', lambda path: 200_000_000)
    with pytest.raises(inst.InstallError) as full:
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30)
    assert full.value.code == 'space' and full.value.line == 'Not enough free space. Free up 2 GB on the Hermes computer.'


# -- the 404 fallback --------------------------------------------------------------------------------------------------

@pytest.fixture
def gone(server):
    """The pinned 37.2.13 archive is gone (404); the index's newest stable is 37.2.14."""
    newer = emulator_zip('37.2.14')
    server.put('emulator-windows_x64-3.zip', newer)
    server.put('repository2-3.xml', repo_xml([
        ('37.3.9', 'channel-1', {'windows': ('emulator-windows_x64-2.zip', emulator_zip('37.3.9'))}),
        ('37.2.13', 'channel-0', {'windows': ('emulator-windows_x64-1.zip', emulator_zip())}),
        ('37.2.14', 'channel-0', {'windows': ('emulator-windows_x64-3.zip', newer)})]))
    img = image_zip()
    server.put('sys-img/google_apis_playstore/x86_64-36_r07.zip', img)
    server.put('sys-img/google_apis_playstore/sys-img2-3.xml', img_xml([('x86_64', '7', 'android-sdk-license', 'x86_64-36_r07.zip', img)]))
    return newer


def test_404_takes_the_newest_stable_only_when_its_proto_passes(tmp_path, server, gone):
    folder = tmp_path / 'phone'
    accept_all(folder, server=server)
    with pytest.raises(inst.InstallError) as refused:  # no contract test given: never accepted
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30)
    assert refused.value.code == 'update' and not (folder / 'sdk' / 'emulator').exists()
    assert [p.name for p in (folder / 'downloads').iterdir() if p.name != 'index'] == []
    with pytest.raises(inst.InstallError):
        inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30,
                         proto_ok=lambda path: False)
    checked = []

    def proto_ok(path):
        checked.append(path.read_text())
        return True
    receipt = inst.run_install(folder, checks=OK, host=WIN, base_url=server.base, runner=Runner(), total_memory=64 << 30,
                               proto_ok=proto_ok)
    assert checked and 'EmulatorController' in checked[0]
    assert receipt['packages'][0]['revision'] == '37.2.14' and receipt['packages'][0]['fallback']
    assert (folder / 'sdk' / 'emulator' / 'source.properties').read_text().startswith('Pkg.Revision=37.2.14')


def test_a_pinned_emulator_missing_from_the_index_needs_the_proto_check(server, tmp_path):
    emu = emulator_zip('37.2.20')
    server.put('repository2-3.xml', repo_xml([('37.2.20', 'channel-0', {'windows': ('emulator-windows_x64-9.zip', emu)})]))
    img = image_zip()
    server.put('sys-img/google_apis_playstore/sys-img2-3.xml', img_xml([('x86_64', '7', 'android-sdk-license', 'x86_64-36_r07.zip', img)]))
    picks = inst.plan(inst.fetch_indexes(tmp_path / 'phone', server.base), WIN)
    assert picks[0]['revision'] == '37.2.20' and picks[0]['proto_check'] and not picks[1]['proto_check']


# -- archives ----------------------------------------------------------------------------------------------------------

def test_extract_refuses_members_outside_the_folder(tmp_path):
    for name in ('../evil.txt', 'top/../../evil.txt', '/abs/evil.txt'):
        z = tmp_path / 'slip.zip'
        with zipfile.ZipFile(z, 'w') as out:
            out.writestr('top/ok.txt', 'ok')
            out.writestr(name, 'evil')
        with pytest.raises(inst.InstallError):
            inst.extract(z, tmp_path / 'root' / 'stage', tmp_path / 'root')
        assert not (tmp_path / 'evil.txt').exists() and not (tmp_path / 'root' / 'evil.txt').exists()
    two = tmp_path / 'two.zip'
    with zipfile.ZipFile(two, 'w') as out:
        out.writestr('a/x', '1')
        out.writestr('b/y', '2')
    with pytest.raises(inst.InstallError):
        inst.extract(two, tmp_path / 'root' / 'stage', tmp_path / 'root')


@pytest.mark.skipif(os.name == 'nt', reason='POSIX modes')
def test_extract_keeps_executable_bits(tmp_path):
    z = tmp_path / 'e.zip'
    z.write_bytes(emulator_zip())
    top = inst.extract(z, tmp_path / 'root' / 'stage', tmp_path / 'root')
    assert os.stat(top / 'emulator').st_mode & stat.S_IXUSR
    assert not os.stat(top / 'source.properties').st_mode & stat.S_IXUSR


# -- the phone's key ---------------------------------------------------------------------------------------------------

def test_the_phone_gets_its_own_key_before_the_first_start(tmp_path, monkeypatch):
    developer = tmp_path / 'developer-home'
    (developer / '.android').mkdir(parents=True)
    dev_key = developer / '.android' / 'adbkey'
    dev_key.write_bytes(b'-----BEGIN PRIVATE KEY' b'-----\nfake developer key\n-----END PRIVATE KEY' b'-----\n')  # split: not a real key
    stamp = dev_key.stat().st_mtime_ns
    for name in ('HOME', 'USERPROFILE', 'ANDROID_USER_HOME'):
        monkeypatch.setenv(name, str(developer))

    def no_binaries(*args, **kwargs):
        raise AssertionError('the phone key needs no adb binary')
    monkeypatch.setattr(subprocess, 'run', no_binaries)
    monkeypatch.setattr(subprocess, 'Popen', no_binaries)
    folder = tmp_path / 'phone'
    assert inst.make_phone_key(folder)
    phone_key = (folder / 'home' / 'adbkey').read_bytes()
    assert phone_key.startswith(b'-----BEGIN PRIVATE KEY-----') and phone_key != dev_key.read_bytes()
    assert (folder / 'home' / 'adbkey.pub').read_text().endswith(' beacon@agent-phone')
    assert dev_key.stat().st_mtime_ns == stamp and sorted(p.name for p in (developer / '.android').iterdir()) == ['adbkey']
    assert not inst.make_phone_key(folder) and (folder / 'home' / 'adbkey').read_bytes() == phone_key  # once only


# -- ports ---------------------------------------------------------------------------------------------------------------

def test_pick_ports_skips_ports_other_phones_hold(tmp_path):
    root = tmp_path / 'agent-phone'
    a, b, c = root / 'a', root / 'b', root / 'c'
    always = lambda port: True  # noqa: E731
    first = inst.pick_ports(a, console_ports=range(41000, 41010, 2), free=always)
    assert first['console'] == 41000 and first['grpc'] not in (41000, 41001)
    assert json.loads((a / 'run' / 'ports.json').read_text())['console'] == 41000
    (b / 'run').mkdir(parents=True)
    (b / 'run' / 'state.json').write_text(json.dumps({'running': True, 'ports': {'console': 41002, 'grpc': 1}}))
    assert inst.pick_ports(c, console_ports=range(41000, 41010, 2), free=always)['console'] == 41004
    assert inst.pick_ports(a, console_ports=range(41000, 41010, 2), free=always)['console'] == 41000  # its own pick again
    busy = {41000, 41001, 41003}
    assert inst.pick_ports(a, console_ports=range(41000, 41010, 2), free=lambda p: p not in busy)['console'] == 41006
    with pytest.raises(inst.InstallError) as none:
        inst.pick_ports(a, console_ports=range(41000, 41004, 2), free=lambda p: False)
    assert none.value.fix == 'Close other virtual phones on this computer, then try again.'


def test_pick_ports_sees_a_real_listener(tmp_path):
    import socket
    held = socket.socket()
    held.bind(('127.0.0.1', 0))
    held.listen(1)
    try:
        port = held.getsockname()[1]
        console = port if port % 2 == 0 else port - 1
        picked = inst.pick_ports(tmp_path / 'root' / 'a', console_ports=[console, console + 2, console + 4])
        assert picked['console'] != console
    finally:
        held.close()


PICKER = r'''
import importlib.util, json, sys, time
from pathlib import Path
plugin, folder, start = sys.argv[1], sys.argv[2], float(sys.argv[3])
for name in ('agent_phone_host', 'agent_phone_install'):
    spec = importlib.util.spec_from_file_location('beacon_' + name, Path(plugin) / (name + '.py'))
    module = importlib.util.module_from_spec(spec); sys.modules['beacon_' + name] = module; spec.loader.exec_module(module)
inst = sys.modules['beacon_agent_phone_install']
while time.time() < start:
    time.sleep(0.001)
print(json.dumps(inst.pick_ports(folder, console_ports=range(42000, 42040, 2), free=lambda p: True)))
'''


def test_two_installs_picking_at_once_get_different_ports(tmp_path):
    root = tmp_path / 'agent-phone'
    root.mkdir()
    script = tmp_path / 'picker.py'
    script.write_text(PICKER)
    start = time.time() + 2.0
    procs = [subprocess.Popen([sys.executable, '-I', str(script), str(PLUGIN), str(root / f'phone{i}'), str(start)],
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for i in range(4)]
    picks = []
    for proc in procs:
        out, err = proc.communicate(timeout=60)
        assert proc.returncode == 0, err
        picks.append(json.loads(out.strip().splitlines()[-1]))
    consoles = [p['console'] for p in picks]
    assert len(set(consoles)) == 4 and len({p['grpc'] for p in picks}) == 4


# -- where the phone lives -------------------------------------------------------------------------------------------

def test_the_phone_folder_is_outside_the_hermes_home_per_os(tmp_path):
    from hermes_constants import hermes_home_key
    assert inst.phone_root({'LOCALAPPDATA': r'C:\Users\u\AppData\Local'}, 'win32') == Path(r'C:\Users\u\AppData\Local') / 'Beacon' / 'agent-phone'
    assert inst.phone_root({'USERPROFILE': r'C:\Users\u'}, 'win32') == Path(r'C:\Users\u') / 'AppData' / 'Local' / 'Beacon' / 'agent-phone'
    assert inst.phone_root({'HOME': '/Users/u'}, 'darwin') == Path('/Users/u/Library/Application Support/Beacon/agent-phone')
    assert inst.phone_root({'HOME': '/home/u'}, 'linux') == Path('/home/u/.local/share/beacon/agent-phone')
    assert inst.phone_root({'HOME': '/home/u', 'XDG_DATA_HOME': '/data/u'}, 'linux') == Path('/data/u/beacon/agent-phone')
    assert inst.phone_root({'HOME': '/home/u', 'XDG_DATA_HOME': 'relative'}, 'linux') == Path('/home/u/.local/share/beacon/agent-phone')
    home = tmp_path / 'hermes-home'
    home.mkdir()
    env = {'LOCALAPPDATA': str(tmp_path / 'local')}
    folder = inst.phone_folder(home, env, 'win32')
    assert folder == tmp_path / 'local' / 'Beacon' / 'agent-phone' / hashlib.sha256(hermes_home_key(home).encode()).hexdigest()[:16]
    assert home not in folder.parents
    assert inst.phone_folder(home, env, 'win32') == folder  # stable
    other = tmp_path / 'other-home'
    other.mkdir()
    assert inst.phone_folder(other, env, 'win32') != folder  # one phone per Hermes home
    with pytest.raises(ValueError):
        inst.phone_folder(None, env, 'win32')
    pointer = inst.write_pointer(home, folder, profile='default', dashboard='http://127.0.0.1:9119')
    assert inst.pointer_path(home) == home / 'plugin-data' / 'beacon' / 'agent-phone.json'
    assert inst.read_pointer(home) == pointer and pointer['key'] == hermes_home_key(home)
    assert inst.read_pointer(other) is None


# -- Remove and the helper's pin -----------------------------------------------------------------------------------------

SLEEPER = 'import time\ntime.sleep(120)\n'


def _sleeper(cwd):
    import psutil
    proc = subprocess.Popen([sys.executable, '-I', '-c', SLEEPER], cwd=str(cwd))
    return proc, {'pid': proc.pid, 'create_time': psutil.Process(proc.pid).create_time()}


def _installed_phone(tmp_path, name='0123456789abcdef'):
    home = tmp_path / 'hermes-home'
    home.mkdir(exist_ok=True)
    folder = tmp_path / 'local' / 'Beacon' / 'agent-phone' / name
    for rel, size in (('sdk/emulator/emulator.exe', 3000), ('sdk/system-images/x/system.img', 5000), ('home/adbkey', 1700),
                      ('home/avd/beacon_agent_phone.avd/userdata-qemu.img', 4000), ('run/frames-5640.bin', 900)):
        (folder / rel).parent.mkdir(parents=True, exist_ok=True)
        (folder / rel).write_bytes(os.urandom(size))
    (folder.parent / 'ports.lock').write_bytes(b'')
    inst.write_pointer(home, folder, profile='default', dashboard='http://127.0.0.1:9')
    return home, folder


@pytest.fixture
def fake_vault(monkeypatch):
    calls = []

    def forget_phone(home, profile, *, delete_logins):
        calls.append((Path(home), profile, delete_logins))
        return 3 if delete_logins else 0
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', type(sys)('fake_vault'))
    sys.modules['beacon_agent_phone_vault'].forget_phone = forget_phone
    return calls


def test_remove_ends_the_phone_and_keeper_and_deletes_everything(tmp_path, fake_vault):
    home, folder = _installed_phone(tmp_path)
    emulator, emu_birth = _sleeper(tmp_path)
    keeper, keeper_birth = _sleeper(folder)  # the keeper's working folder is the phone folder (S7 rule 2)
    try:
        (folder / 'run' / 'state.json').write_text(json.dumps({'running': True, **emu_birth, 'ports': {'console': 5640, 'grpc': 1}}))
        (folder / 'run' / 'keeper.json').write_text(json.dumps(keeper_birth))
        size = sum(p.stat().st_size for p in folder.rglob('*') if p.is_file())
        result = inst.remove_phone(home, delete_logins=True)
        assert result == {'removed': True, 'freed_bytes': size, 'logins_deleted': 3}
        assert fake_vault == [(home, 'default', True)]
        assert emulator.wait(timeout=15) is not None and keeper.wait(timeout=15) is not None
        assert not folder.exists() and not inst.pointer_path(home).exists()
        assert not (tmp_path / 'local' / 'Beacon').exists()  # the last phone: ports.lock and the empty parents go too
        # idempotent
        assert inst.remove_phone(home) == {'removed': False, 'freed_bytes': 0, 'logins_deleted': 0}
        assert fake_vault[-1] == (home, None, False)
    finally:
        for proc in (emulator, keeper):
            proc.kill()


def test_remove_keeps_other_phones_and_their_port_lock(tmp_path, fake_vault):
    home, folder = _installed_phone(tmp_path)
    other = folder.parent / 'fedcba9876543210'
    (other / 'run').mkdir(parents=True)
    result = inst.remove_phone(home)
    assert result['removed'] and result['logins_deleted'] == 0 and fake_vault == [(home, 'default', False)]
    assert not folder.exists() and other.is_dir() and (folder.parent / 'ports.lock').exists()


def test_remove_still_removes_the_phone_when_the_vault_part_cant_run(tmp_path, monkeypatch):
    home, folder = _installed_phone(tmp_path)

    def broken(home, profile, *, delete_logins):
        raise RuntimeError('vault locked')
    monkeypatch.setitem(sys.modules, 'beacon_agent_phone_vault', type(sys)('broken_vault'))
    sys.modules['beacon_agent_phone_vault'].forget_phone = broken
    result = inst.remove_phone(home, delete_logins=True)
    assert result['removed'] and result['logins_deleted'] is None and result['logins_error'] == 'RuntimeError'
    assert not folder.exists()


def test_remove_deletes_only_a_phone_folder(tmp_path, fake_vault):
    home = tmp_path / 'hermes-home'
    home.mkdir()
    precious = tmp_path / 'precious'
    precious.mkdir()
    (precious / 'keep.txt').write_text('keep')
    for target in (precious, home / 'plugin-data' / 'Beacon' / 'agent-phone' / '0123456789abcdef', tmp_path / 'agent-phone' / 'x'):
        inst.write_pointer(home, target, profile='default')
        with pytest.raises(ValueError):
            inst.remove_phone(home)
    assert (precious / 'keep.txt').read_text() == 'keep' and inst.pointer_path(home).exists() and fake_vault == []


def test_helper_package_reads_the_release_pin(tmp_path):
    assert inst.helper_package(tmp_path) is None  # no pin in the plugin yet
    # L8's pinRelease format plus the release asset's url (Root, 17 Nov: the helper is downloaded, never in the plugin)
    pin = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'version_name': '1.2.1',
           'file': 'BeaconAgentPhoneHelper-release.apk', 'bytes': 109692, 'sha256': 'A' * 64, 'signer_sha256': 'b' * 64,
           'signed_by': 'stable', 'url': 'https://example.com/releases/BeaconAgentPhoneHelper-release.apk'}
    (tmp_path / 'agent_phone_helper.json').write_text(json.dumps(pin))
    assert inst.helper_package(tmp_path) == {**{k: pin[k] for k in ('package', 'version_code', 'version_name', 'signer_sha256',
                                                                    'bytes', 'url')}, 'sha256': 'a' * 64}
    for broken in ({'sha256': 'short'}, {'signer_sha256': None}, {'bytes': '109692'}, {'bytes': 0}):
        (tmp_path / 'agent_phone_helper.json').write_text(json.dumps({**pin, **broken}))
        assert inst.helper_package(tmp_path) is None


# -- words ---------------------------------------------------------------------------------------------------------------

JARGON = ('sdk', 'avd', 'adb', 'emulator', 'container', 'kvm', 'grpc', 'qemu', 'android-')


def test_owner_lines_are_plain(tmp_path):
    for code, line in inst.WORDS.items():
        if code == 'ports':  # used as a fix line only
            continue
        low = line.lower()
        assert not any(word in low for word in JARGON), line
        assert not any(ch.isdigit() for ch in line.replace('{n}', '')), line
        assert '/' not in line and '\\' not in line, line
    for fix in inst.FIX.values():
        assert '/' not in fix and '\\' not in fix and not any(ch.isdigit() for ch in fix)


# ---- PHONEFIX-121 (REVIEW-PHONE-121): each test below failed on tree dbc7ba9c -----------------------------------------------

STABLE_PIN = {'package': 'dev.hermes.agentphone.helper', 'version_code': 121, 'version_name': '1.2.1', 'file': 'helper.apk',
              'bytes': 123456, 'sha256': 'a' * 64, 'signer_sha256': 'b' * 64, 'signed_by': 'stable',
              'url': 'https://example.com/releases/helper.apk'}


def test_p3_4_a_local_debug_pin_is_refused(tmp_path):
    pin = dict(STABLE_PIN, signed_by='local-debug', url='http://127.0.0.1:8000/helper.apk')
    (tmp_path / inst.HELPER_PIN).write_text(json.dumps(pin), encoding='utf-8')
    assert inst.helper_package(tmp_path) is None


def test_p3_4_only_a_stable_pin_with_an_https_url_counts(tmp_path, monkeypatch):
    (tmp_path / inst.HELPER_PIN).write_text(json.dumps(STABLE_PIN), encoding='utf-8')
    assert inst.helper_package(tmp_path)['sha256'] == 'a' * 64
    unsigned = {k: v for k, v in STABLE_PIN.items() if k != 'signed_by'}
    for broken in (dict(STABLE_PIN, signed_by='local-debug'), dict(STABLE_PIN, signed_by=None), dict(STABLE_PIN, signed_by='Stable'), unsigned,
                   dict(STABLE_PIN, url='http://example.com/helper.apk'), dict(STABLE_PIN, url='file:///tmp/helper.apk')):
        (tmp_path / inst.HELPER_PIN).write_text(json.dumps(broken), encoding='utf-8')
        assert inst.helper_package(tmp_path) is None, broken
    # a release asset url that isn't there yet is a pin without a download, as before
    (tmp_path / inst.HELPER_PIN).write_text(json.dumps({k: v for k, v in STABLE_PIN.items() if k != 'url'}), encoding='utf-8')
    assert inst.helper_package(tmp_path)['url'] is None
    # the tests' own switch: a local server or a file, never a release
    monkeypatch.setattr(inst, 'HELPER_URL_SCHEMES', ('https', 'http', 'file'))
    (tmp_path / inst.HELPER_PIN).write_text(json.dumps(dict(STABLE_PIN, url='http://127.0.0.1:8000/helper.apk')), encoding='utf-8')
    assert inst.helper_package(tmp_path)['url'] == 'http://127.0.0.1:8000/helper.apk'


def test_p2_4_remove_clears_this_phones_discovery_file_wherever_it_was_found(tmp_path, fake_vault):
    home, folder = _installed_phone(tmp_path)
    elsewhere = tmp_path / 'elsewhere' / 'avd' / 'running'  # not one of the places the host scans: the log's line named it
    elsewhere.mkdir(parents=True)
    avd_dir = folder / 'home' / 'avd' / 'beacon_agent_phone.avd'
    ours, stranger = elsewhere / 'pid_3999981.ini', elsewhere / 'pid_3999982.ini'
    ours.write_text(f'avd.dir={avd_dir}\ngrpc.port=9\n')
    stranger.write_text(f'avd.dir={tmp_path / "someone-else.avd"}\n')
    (folder / 'run' / 'state.json').write_text(json.dumps({'running': False, 'discovery': str(ours), 'ports': {'console': 5640, 'grpc': 1}}))
    assert inst.remove_phone(home)['removed'] is True
    assert not ours.exists() and stranger.exists() and not folder.exists()
