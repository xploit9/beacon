"""The agent phone's install (EMU-DESIGN §2.1, §2.2, §3 steps 3-6): Google's own packages, no Java and no new Python packages.

- **Where:** one phone folder per Hermes home, in the platform's local data folder, outside the Hermes home so Hermes backups never
  copy its accounts (§2.1, Root rev 3). The folder is named by a hash of ``hermes_home_key(home)``: that key is a full normcased
  path (``hermes_constants.py:212``), so it can't be a folder name itself. ``<home>/plugin-data/beacon/agent-phone.json`` points
  at the folder and keeps the key, the owning profile and the dashboard address the start checks read; it holds no secret.
- **What:** two packages from Google's SDK index (the emulator and the Play system image). platform-tools is not downloaded: the
  phone's adb is agent_phone_adb (L1b), which speaks adb's protocol itself (Root, 17 Nov 2026). ``sdk/platform-tools/`` is
  created empty: the emulator refuses to start without it, and starts its own adb server when adb is in it (L1b, 17 Nov).
- **How:** the index XML (``repository2-3.xml``; ``sys-img/google_apis_playstore/sys-img2-3.xml``) gives each archive's URL, size and
  SHA-1; downloads resume with HTTP Range; a damaged archive is fetched again once; archives are extracted to staging, moved into
  ``sdk/`` and deleted (S4: 59.5 s on the owner PC, SHA-1 OK, a resume after interruption worked).
- **Google's terms:** the ``<license>`` texts of the planned packages, verbatim. Only the owner's Accept (``accept_licences``) writes
  Google's record (``sdk/licenses/<id>``: CRLF + the SHA-1 of the UTF-8 text, as Android's ``License.setAccepted`` wrote it in S4)
  and Beacon's receipt. ``run_install`` refuses to download a package whose licence has no such record.
- **The phone:** the AVD is written from Google's own template (google/android-emulator-container-scripts,
  ``emu/templates/avd/MediumPhone.avd/config.ini``, Apache-2.0, copied below unchanged); the phone's own adb key is generated
  before the first start (agent_phone_adb.generate_phone_key; without one the emulator copied the developer's key, S2).
- **Ports:** ``pick_ports`` takes an even console port in 5640-5680 (adb is the next odd one) and a free loopback port for gRPC,
  under a file lock beside the phone folders, and records them in the phone's own ``run/ports.json`` so parallel installs never
  pick the same ports.

Every line meant for the owner or the agent is short and plain, with no SDK/AVD/adb/emulator word outside a fix line, and never a
path, port or token.
"""
import contextlib
import hashlib
import http.client
import importlib.util
import json
import math
import os
import platform as _platform
import shutil
import socket
import stat
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path, PurePosixPath

REPOSITORY = 'https://dl.google.com/android/repository/'
INDEXES = {'repository': 'repository2-3.xml', 'image': 'sys-img/google_apis_playstore/sys-img2-3.xml'}
API_LEVEL = 36
# Pinned per Beacon release: the packages S4 installed and booted on 16 Nov 2026 (stable channel). An API-level change is never
# automatic (§7.1); a newer revision of the same image path is.
PINS = {
    'emulator': {'index': 'repository', 'path': 'emulator', 'revision': '37.2.13', 'dest': 'emulator', 'proto': True},
    'image': {'index': 'image', 'path': f'system-images;android-{API_LEVEL};google_apis_playstore;{{abi}}', 'revision': '7',
              'dest': f'system-images/android-{API_LEVEL}/google_apis_playstore/{{abi}}', 'proto': False},
}
ORDER = ('emulator', 'image')  # §3 step 4: the emulator first (≈0.5 GB), run it, then the rest
# What a package takes once extracted (measured on the owner PC: emulator 1.1 GB, Play image 2.3 GB; §1), rounded up.
INSTALLED_BYTES = {'emulator': 1_200_000_000, 'image': 2_400_000_000}
HOSTS = {('windows', 'x64'), ('linux', 'x64'), ('macosx', 'x64'), ('macosx', 'aarch64')}  # stable emulator builds (§1)
CONSOLE_PORTS = range(5640, 5681, 2)  # §2.2: even console port P, adb P+1, outside 5554-5585 and away from 5578/5580
AVD_NAME = 'beacon_agent_phone'
POINTER = 'agent-phone.json'
PORTS_LOCK = 'ports.lock'
CHUNK = 1 << 20
INDEX_LIMIT = 16 * 1024 * 1024
USER_AGENT = 'Beacon-agent-phone/1'
LICENCE_RECORD_SEPARATOR = '\r\n'

# Google's AVD template, google/android-emulator-container-scripts emu/templates/avd/MediumPhone.avd/config.ini (Apache-2.0),
# byte for byte as the spike used it (S4 booted a phone written from it). Only the {{…}} fields and the overrides below change.
AVD_TEMPLATE = """AvdId=MediumPhone
PlayStore.enabled={{playstore}}
avd.ini.displayname=MediumPhone
avd.ini.encoding=UTF-8
disk.dataPartition.size=10G
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
tag.display=Google APIs
hw.lcd.density=420
hw.lcd.height=2400
hw.lcd.width=1080
# Unused
# hw.sdCard=yes
# sdcard.size=512M

tag.id={{qemu_tag}}
abi.type={{ro_product_cpu_abi}}
hw.cpu.arch={{qemu_cpu}}
image.sysdir.1=system-images/android/{{ro_product_cpu_abi}}/

# End of default configuration
"""

WORDS = {
    'checks': "Check this computer first.",
    'licences': "The phone uses Google's Android software. Read and accept to continue.",
    'offline': "Download stopped. Check the Hermes computer's internet.",
    'damaged': "A download was damaged. Getting it again.",
    'space': "Not enough free space. Free up {n} GB on the Hermes computer.",
    'cannot_run': "This computer can't run a virtual phone.",
    'update': "Beacon needs an update to get the phone. Update Beacon, then try again.",
    'busy': "The phone is already being set up.",
    'ports': "Close other virtual phones on this computer, then try again.",
}
FIX = {
    'windows': "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer.",
    'linux': "Turn on virtualisation (KVM) on this computer.",
    'macosx': "This Mac can't run it. Use another computer for Hermes.",
    'unsupported': "There is no phone software for this kind of computer.",
    'broken': "The phone software doesn't run here. Check the computer's updates, then try again.",
}


class InstallError(Exception):
    """A plain reason the owner can act on (``code`` for the app, ``line`` and ``fix`` in plain words, ``retry`` for [Try again])."""

    def __init__(self, code, line=None, fix=None, retry=False, args=None):
        self.code, self.line, self.fix, self.retry = code, line or WORDS.get(code, WORDS['cannot_run']), fix, retry
        self.args_ = args  # the numbers in the line (contract revision 5 §11.1), e.g. {'n': '3'} for 'space'
        super().__init__(self.line)

    @property
    def reason(self):
        return f'install.{self.code}' if self.code in WORDS else None

    @property
    def fix_reason(self):
        return reason_of(self.fix)


def reason_of(line):
    """The revision 5 code of a line of this module's own tables (``install.<key>``, ``fix.<key>``), else None."""
    for prefix, table in (('install', WORDS), ('fix', FIX)):
        for key, value in table.items():
            if line is not None and value == line:
                return f'{prefix}.{key}'
    return None


class Paused(Exception):
    """The owner paused. Partial downloads stay; the next run resumes them."""


class Cancelled(Exception):
    """The owner cancelled. Partial files were deleted."""


class _Gone(Exception):
    """The pinned archive is gone from Google's server (HTTP 404)."""


class Control:
    """Pause and Cancel from another thread (the API's buttons)."""

    def __init__(self):
        self._pause, self._cancel = threading.Event(), threading.Event()

    def pause(self):
        self._pause.set()

    def resume(self):
        self._pause.clear()

    def cancel(self):
        self._cancel.set()

    def check(self):
        if self._cancel.is_set():
            raise Cancelled()
        if self._pause.is_set():
            raise Paused()


def _sibling(name):
    """A Beacon module beside this one, loaded the way the plugin loads its siblings (by path, under ``beacon_<name>``)."""
    key = 'beacon_' + name
    if key not in sys.modules:
        path = Path(__file__).resolve().parent / f'{name}.py'
        spec = importlib.util.spec_from_file_location(key, path)
        module = importlib.util.module_from_spec(spec)
        sys.modules[key] = module
        try:
            spec.loader.exec_module(module)
        except BaseException:
            sys.modules.pop(key, None)  # never leave a half-loaded module for the next caller
            raise
    return sys.modules[key]


# -- the host and the folder -----------------------------------------------------------------------------------------------

def host_platform(platform=None, machine=None):
    """Google's names for this computer: ``{'os', 'arch', 'abi', 'cpu', 'supported'}`` (index ``host-os``/``host-arch``)."""
    platform = sys.platform if platform is None else platform
    machine = (_platform.machine() if machine is None else machine).lower()
    os_name = 'windows' if platform == 'win32' else 'macosx' if platform == 'darwin' else 'linux' if platform.startswith('linux') else platform
    arch = 'x64' if machine in ('amd64', 'x86_64', 'x64') else 'aarch64' if machine in ('arm64', 'aarch64') else machine
    abi, cpu = ('arm64-v8a', 'arm64') if arch == 'aarch64' else ('x86_64', 'x86_64')
    return {'os': os_name, 'arch': arch, 'abi': abi, 'cpu': cpu, 'supported': (os_name, arch) in HOSTS}


def phone_root(env=None, platform=None):
    """``Beacon/agent-phone`` in the platform's local data folder (§2.1)."""
    env = os.environ if env is None else env
    platform = sys.platform if platform is None else platform
    if platform == 'win32':
        base = (env.get('LOCALAPPDATA') or '').strip() or str(Path(env.get('USERPROFILE') or Path.home()) / 'AppData' / 'Local')
        return Path(base) / 'Beacon' / 'agent-phone'
    home = Path(env.get('HOME') or Path.home())
    if platform == 'darwin':
        return home / 'Library' / 'Application Support' / 'Beacon' / 'agent-phone'
    xdg = (env.get('XDG_DATA_HOME') or '').strip()
    base = Path(xdg) if xdg.startswith('/') else home / '.local' / 'share'  # XDG: a relative value is ignored
    return base / 'beacon' / 'agent-phone'


def home_key(hermes_home):
    from hermes_constants import hermes_home_key
    return hermes_home_key(Path(hermes_home))


def folder_name(key):
    """The phone folder's name for a Hermes home key: the key is a full path, so the folder is named by its hash."""
    return hashlib.sha256(str(key).encode('utf-8')).hexdigest()[:16]


def phone_folder(hermes_home, env=None, platform=None):
    """The phone folder of one Hermes home. The home is always given: an ambient fallback could name the wrong Hermes."""
    if not hermes_home:
        raise ValueError('hermes_home is required')
    return phone_root(env, platform) / folder_name(home_key(hermes_home))


def pointer_path(hermes_home):
    return Path(hermes_home) / 'plugin-data' / 'beacon' / POINTER


def write_pointer(hermes_home, folder, *, profile, dashboard=None):
    """``plugin-data/beacon/agent-phone.json``: folder, key, owning profile, dashboard address. No secrets."""
    value = {'version': 1, 'folder': str(Path(folder)), 'key': home_key(hermes_home), 'profile': profile, 'dashboard': dashboard}
    _write_json(pointer_path(hermes_home), value)
    return value


def read_pointer(hermes_home):
    value = _read_json(pointer_path(hermes_home))
    return value if isinstance(value, dict) and value.get('folder') else None


def _host_module():
    return _sibling('agent_phone_host')


def layout(folder):
    """L1b's folder layout (agent_phone_host.Folder) plus the download folder this module adds."""
    f = _host_module().Folder(folder)
    f.downloads = f.root / 'downloads'
    f.licences = f.sdk / 'licenses'
    f.receipt = f.run / 'licences.json'
    f.install_receipt = f.run / 'install.json'
    f.ports = f.run / 'ports.json'
    return f


def _inside(path, root):
    path, root = Path(path).resolve(), Path(root).resolve()
    return path == root or root in path.parents


def _remove_tree(path, root):
    """Deletes a folder this install made (staging, a replaced package), only ever inside the phone folder."""
    path = Path(path)
    if not path.exists():
        return
    if not _inside(path, root) or Path(path).resolve() == Path(root).resolve():
        raise InstallError('cannot_run', fix=FIX['broken'])
    shutil.rmtree(path)


def _write_json(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(f'{path.name}.{os.getpid()}.{threading.get_ident()}.tmp')
    tmp.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')
    os.replace(tmp, path)


def _read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding='utf-8'))
    except (OSError, ValueError):
        return None


@contextlib.contextmanager
def _file_lock(path, wait=30.0):
    """An OS file lock (msvcrt on Windows, fcntl elsewhere); the OS drops it when the process ends. ``wait=0`` doesn't wait."""
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
    deadline = time.monotonic() + wait
    try:
        while True:
            try:
                if os.name == 'nt':
                    import msvcrt
                    os.lseek(fd, 0, os.SEEK_SET)
                    msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
                else:
                    import fcntl
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if time.monotonic() >= deadline:
                    raise InstallError('busy') from None
                time.sleep(0.05)
        try:
            yield
        finally:
            if os.name == 'nt':
                import msvcrt
                os.lseek(fd, 0, os.SEEK_SET)
                with contextlib.suppress(OSError):
                    msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(fd, fcntl.LOCK_UN)
    finally:
        os.close(fd)


# -- Google's index ----------------------------------------------------------------------------------------------------

def _local(tag):
    return tag.rsplit('}', 1)[-1]


def _child(element, name):
    if element is None:
        return None
    return next((c for c in element if _local(c.tag) == name), None)


def parse_index(data, base_url):
    """One SDK index XML: ``{'licences': {id: text}, 'packages': [...]}``. Each package keeps its path, revision, channel name,
    licence id and archives (absolute URL, size, SHA-1, host-os, host-arch). A document type declaration is refused."""
    if isinstance(data, str):
        data = data.encode('utf-8')
    if len(data) > INDEX_LIMIT or b'<!DOCTYPE' in data[:4096].upper():
        raise InstallError('offline', retry=True)
    try:
        root = ET.fromstring(data)
    except ET.ParseError:
        raise InstallError('offline', retry=True) from None
    licences = {el.get('id'): el.text or '' for el in root if _local(el.tag) == 'license' and el.get('id')}
    channels = {el.get('id'): (el.text or '').strip() for el in root if _local(el.tag) == 'channel'}
    packages = []
    for rp in root:
        if _local(rp.tag) != 'remotePackage':
            continue
        revision = _child(rp, 'revision')
        parts = []
        for name in ('major', 'minor', 'micro'):
            part = _child(revision, name)
            if part is not None and (part.text or '').strip().isdigit():
                parts.append(int(part.text))
        if not parts or _child(revision, 'preview') is not None:
            continue
        licence = _child(rp, 'uses-license')
        channel = _child(rp, 'channelRef')
        archives = []
        for archive in rp.iter():
            if _local(archive.tag) != 'archive':
                continue
            complete = _child(archive, 'complete')
            url, size, checksum = (_child(complete, n) for n in ('url', 'size', 'checksum'))
            if url is None or size is None or checksum is None or checksum.get('type') != 'sha1':
                continue
            host_os, host_arch = _child(archive, 'host-os'), _child(archive, 'host-arch')
            archives.append({'url': urllib.parse.urljoin(base_url, (url.text or '').strip()), 'size': int(size.text),
                             'sha1': (checksum.text or '').strip().lower(),
                             'os': host_os.text.strip() if host_os is not None else None,
                             'arch': host_arch.text.strip() if host_arch is not None else None})
        packages.append({'path': rp.get('path'), 'revision': tuple(parts), 'revision_text': '.'.join(map(str, parts)),
                         'channel': channels.get(channel.get('ref')) if channel is not None else None,
                         'licence': licence.get('ref') if licence is not None else None, 'archives': archives})
    return {'licences': licences, 'packages': packages}


def _archive_for(package, host):
    for archive in package['archives']:
        if archive['os'] not in (None, host['os']) or archive['arch'] not in (None, host['arch']):
            continue
        return archive
    return None


def _pick(key, index, package, host, *, fallback=False):
    archive = _archive_for(package, host)
    if archive is None or package['licence'] not in index['licences']:
        return None
    pin = PINS[key]
    return {'key': key, 'path': package['path'], 'revision': package['revision_text'], 'url': archive['url'],
            'size': archive['size'], 'sha1': archive['sha1'], 'licence': package['licence'],
            'licence_text': index['licences'][package['licence']], 'dest': pin['dest'].format(abi=host['abi']),
            'fallback': fallback, 'proto_check': fallback and pin['proto']}


def pinned_pick(key, index, host):
    pin = PINS[key]
    path = pin['path'].format(abi=host['abi'])
    package = next((p for p in index['packages'] if p['path'] == path and p['revision_text'] == pin['revision']), None)
    return _pick(key, index, package, host) if package else None


def stable_pick(key, index, host):
    """The index's newest stable revision of the pinned path (the 404 fallback, §3 step 5)."""
    path = PINS[key]['path'].format(abi=host['abi'])
    stable = [p for p in index['packages'] if p['path'] == path and p['channel'] == 'stable' and _archive_for(p, host)]
    if not stable:
        return None
    return _pick(key, index, max(stable, key=lambda p: p['revision']), host, fallback=True)


def plan(indexes, host):
    """The packages to get, in order, for this computer. Raises when the computer has no stable build."""
    if not host['supported']:
        raise InstallError('cannot_run', fix=FIX['unsupported'])
    picks = []
    for key in ORDER:
        index = indexes[PINS[key]['index']]
        pick = pinned_pick(key, index, host) or stable_pick(key, index, host)
        if pick is None:
            raise InstallError('update')
        picks.append(pick)
    return picks


def sizes(picks, folder=None):
    """The size math for the screens (§3 step 5): download, installed and peak bytes (archives are deleted after each package)."""
    todo = [p for p in picks if folder is None or not installed(folder, p)]
    download = sum(p['size'] for p in todo)
    installed_bytes = sum(INSTALLED_BYTES[p['key']] for p in picks)
    peak = installed_bytes + max((p['size'] for p in todo), default=0)
    return {'download_bytes': download, 'installed_bytes': installed_bytes, 'peak_bytes': peak}


def _opener():
    return urllib.request.build_opener()


def fetch_indexes(folder, base_url=REPOSITORY, opener=None, timeout=60):
    """Both index XMLs, fetched and kept in ``downloads/index/`` (the last good copy is used when Google can't be reached)."""
    f = layout(folder)
    opener = opener or _opener()
    out = {}
    for name, rel in INDEXES.items():
        url = urllib.parse.urljoin(base_url, rel)
        keep = f.downloads / 'index' / PurePosixPath(rel).name
        try:
            request = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
            with opener.open(request, timeout=timeout) as response:
                data = response.read(INDEX_LIMIT + 1)
            parsed = parse_index(data, url)
            keep.parent.mkdir(parents=True, exist_ok=True)
            keep.write_bytes(data)
        except (urllib.error.URLError, OSError, http.client.HTTPException, InstallError):
            try:
                parsed = parse_index(keep.read_bytes(), url)
            except OSError:
                raise InstallError('offline', retry=True) from None
        out[name] = parsed
    return out


# -- Google's terms --------------------------------------------------------------------------------------------------

def licences(picks):
    """The terms to show, verbatim, one per licence id, in plan order."""
    seen, out = set(), []
    for p in picks:
        if p['licence'] not in seen:
            seen.add(p['licence'])
            out.append({'id': p['licence'], 'text': p['licence_text']})
    return out


def terms(folder, *, base_url=REPOSITORY, host=None, opener=None):
    """What the terms screen shows, in one call: ``{'licences': licences(picks), 'sizes': sizes(picks, folder)}``."""
    picks = plan(fetch_indexes(folder, base_url, opener), host or host_platform())
    return {'licences': licences(picks), 'sizes': sizes(picks, folder)}


def _digests(text):
    raw = text.encode('utf-8')
    return hashlib.sha1(raw).hexdigest(), hashlib.sha256(raw).hexdigest()


def accept_licences(folder, accepted, *, by):
    """The owner's Accept: Google's record per licence (``sdk/licenses/<id>`` gets CRLF + the text's SHA-1 unless it is there) and
    Beacon's receipt (id, text hashes, when, by whom). ``accepted`` is the list ``licences()`` returned and the owner read."""
    f = layout(folder)
    receipt = _read_json(f.receipt) or {'accepted': []}
    for item in accepted:
        lic_id, text = item['id'], item['text']
        if not lic_id or '/' in lic_id or '\\' in lic_id or lic_id.startswith('.'):
            raise ValueError('bad licence id')
        sha1, sha256 = _digests(text)
        record = f.licences / lic_id
        record.parent.mkdir(parents=True, exist_ok=True)
        existing = record.read_text(encoding='utf-8') if record.exists() else ''
        if sha1 not in existing.split():
            with record.open('a', encoding='utf-8', newline='') as out:
                out.write(LICENCE_RECORD_SEPARATOR + sha1)
        receipt['accepted'] = [r for r in receipt['accepted'] if not (r.get('id') == lic_id and r.get('text_sha256') == sha256)]
        receipt['accepted'].append({'id': lic_id, 'text_sha1': sha1, 'text_sha256': sha256, 'by': by,
                                    'accepted_at': time.strftime('%Y-%m-%dT%H:%M:%S%z')})
    _write_json(f.receipt, receipt)
    return receipt


def licences_accepted(folder, wanted):
    """True only when every licence text has Beacon's receipt for that exact text and Google's record holds its SHA-1."""
    f = layout(folder)
    receipt = _read_json(f.receipt) or {}
    have = {(r.get('id'), r.get('text_sha256')) for r in receipt.get('accepted', []) if isinstance(r, dict)}
    for item in wanted:
        sha1, sha256 = _digests(item['text'])
        try:
            record = (f.licences / item['id']).read_text(encoding='utf-8').split()
        except OSError:
            return False
        if (item['id'], sha256) not in have or sha1 not in record:
            return False
    return True


# -- downloads -------------------------------------------------------------------------------------------------------

def sha1_file(path, algorithm='sha1'):
    digest = hashlib.new(algorithm)
    with open(path, 'rb') as stream:
        for chunk in iter(lambda: stream.read(CHUNK), b''):
            digest.update(chunk)
    return digest.hexdigest()


def download(url, dest, size, sha1, *, opener=None, control=None, progress=None, timeout=60, algorithm='sha1'):
    """``dest`` from ``url``, resuming ``dest.part`` with HTTP Range, checked by size and SHA-1 (Google's index; the helper's pin
    gives a SHA-256 with ``algorithm='sha256'``). A damaged file is fetched again once (progress says so); a second damage or a
    lost connection is ``offline`` with [Try again]; a 404 is ``_Gone``."""
    dest = Path(dest)
    part = dest.with_name(dest.name + '.part')
    opener = opener or _opener()
    control = control or Control()
    report = progress or (lambda **_: None)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():  # finished before a pause or cancel during extraction
        if dest.stat().st_size == size and sha1_file(dest, algorithm) == sha1:
            report(event='resume', bytes=size)
            return dest
        dest.unlink()
    for attempt in (1, 2):
        have = part.stat().st_size if part.exists() else 0
        if have > size:
            part.unlink()
            have = 0
        report(event='resume', bytes=have)
        if have < size:
            headers = {'User-Agent': USER_AGENT}
            if have:
                headers['Range'] = f'bytes={have}-'
            try:
                with opener.open(urllib.request.Request(url, headers=headers), timeout=timeout) as response:
                    if have and response.status != 206:  # the server ignored Range: start over
                        report(event='restart', bytes=-have)
                        have = 0
                    with part.open('ab' if have else 'wb') as out:
                        while True:
                            control.check()
                            chunk = response.read(CHUNK)
                            if not chunk:
                                break
                            out.write(chunk)
                            have += len(chunk)
                            report(event='bytes', bytes=len(chunk))
                            if have > size:
                                break
            except urllib.error.HTTPError as error:
                if error.code == 404:
                    raise _Gone() from None
                if error.code != 416:
                    raise InstallError('offline', retry=True) from None
                have = size + 1  # 416: the server has less than we kept, so what we kept is not its file
            except (urllib.error.URLError, OSError, http.client.HTTPException):
                raise InstallError('offline', retry=True) from None
            if have < size:  # the connection ended early (http.client returns a short read without an error): resume next run
                raise InstallError('offline', retry=True)
        control.check()
        if part.stat().st_size == size and sha1_file(part, algorithm) == sha1:
            os.replace(part, dest)
            return dest
        report(event='damaged', bytes=-part.stat().st_size, line=WORDS['damaged'])
        part.unlink()
    raise InstallError('offline', retry=True)


def extract(archive, stage, root, control=None):
    """``archive`` into ``stage`` and the path of its single top folder. Refuses any member outside ``stage`` (zip slip, links
    included); keeps POSIX modes (the emulator's binaries must stay executable) and links."""
    control = control or Control()
    stage = Path(stage)
    _remove_tree(stage, root)
    stage.mkdir(parents=True)
    base = stage.resolve()
    tops = set()
    with zipfile.ZipFile(archive) as z:
        for n, member in enumerate(z.infolist()):
            if n % 64 == 0:
                control.check()
            name = member.filename
            parts = PurePosixPath(name.replace('\\', '/')).parts
            target = (stage.joinpath(*parts)).resolve() if parts else base
            if not parts or (target != base and base not in target.parents) or PurePosixPath(name).is_absolute():
                raise InstallError('update')
            tops.add(parts[0])
            mode = member.external_attr >> 16
            if member.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            if stat.S_ISLNK(mode):
                link = z.read(member).decode('utf-8')
                if base not in (target.parent / link).resolve().parents:
                    raise InstallError('update')
                if os.name != 'nt':
                    os.symlink(link, target)
                continue
            with z.open(member) as src, open(target, 'wb') as out:
                shutil.copyfileobj(src, out, CHUNK)
            if os.name != 'nt' and mode & 0o777:
                os.chmod(target, mode & 0o777)
    if len(tops) != 1 or not (stage / next(iter(tops))).is_dir():
        raise InstallError('update')
    return stage / next(iter(tops))


def installed(folder, pick):
    """True when ``sdk/<dest>`` holds the picked package's revision (its own ``source.properties``)."""
    props = layout(folder).sdk / pick['dest'] / 'source.properties'
    try:
        lines = props.read_text(encoding='utf-8', errors='replace').splitlines()
    except OSError:
        return False
    found = dict(line.split('=', 1) for line in lines if '=' in line)
    return found.get('Pkg.Revision', '').strip() == pick['revision']


def _free_bytes(path):
    path = Path(path)
    while not path.exists() and path.parent != path:
        path = path.parent
    return shutil.disk_usage(path).free


def _need_space(folder, pick):
    need = pick['size'] + INSTALLED_BYTES[pick['key']]
    free = _free_bytes(folder)
    if free < need:
        n = max(1, math.ceil((need - free) / 1e9))
        raise InstallError('space', WORDS['space'].format(n=n), args={'n': str(n)})


def install_package(folder, pick, *, opener=None, control=None, progress=None, proto_ok=None):
    """One package: download (resumable, SHA-1), extract to staging, the proto contract when it isn't the pinned emulator, then
    move into ``sdk/`` and delete the archive. Returns the receipt line."""
    f = layout(folder)
    control = control or Control()
    _need_space(folder, pick)
    archive = f.downloads / PurePosixPath(urllib.parse.urlparse(pick['url']).path).name
    if progress:
        progress(event='phase', phase='download', key=pick['key'])
    started = time.monotonic()
    download(pick['url'], archive, pick['size'], pick['sha1'], opener=opener, control=control, progress=progress)
    downloaded = time.monotonic() - started
    stage = f.downloads / f'stage-{pick["key"]}'
    if progress:
        progress(event='phase', phase='check', key=pick['key'])
    top = extract(archive, stage, f.root, control)
    if pick['proto_check']:
        proto = top / 'lib' / 'emulator_controller.proto'
        ok = False
        if proto_ok is not None:
            with contextlib.suppress(Exception):
                ok = bool(proto_ok(proto))
        if not ok:
            _remove_tree(stage, f.root)
            archive.unlink(missing_ok=True)
            raise InstallError('update')
    if progress:
        progress(event='phase', phase='set up', key=pick['key'])
    dest = f.sdk / pick['dest']
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists():
        old = dest.with_name(dest.name + '.old')
        _remove_tree(old, f.root)
        os.replace(dest, old)
        _remove_tree(old, f.root)
    os.replace(top, dest)
    _remove_tree(stage, f.root)
    archive.unlink(missing_ok=True)
    return {'key': pick['key'], 'path': pick['path'], 'revision': pick['revision'], 'bytes': pick['size'], 'sha1': pick['sha1'],
            'fallback': pick['fallback'], 'download_s': round(downloaded, 1), 'total_s': round(time.monotonic() - started, 1)}


def clean_partial(folder):
    """Cancel: every partial download, archive and staging folder goes; installed packages stay."""
    f = layout(folder)
    if not f.downloads.is_dir():
        return
    for item in f.downloads.iterdir():
        if item.is_dir() and item.name.startswith('stage-'):
            _remove_tree(item, f.root)
        elif item.is_file() and item.suffix in ('.part', '.zip'):
            item.unlink(missing_ok=True)


# -- running Google's binary (§3 step 4) ------------------------------------------------------------------------------

def test_emulator(folder, host, *, runner=None, timeout=180):
    """Runs the emulator's binary (missing system libraries show here) and ``-accel-check`` with the phone's own environment
    (agent_phone_host.phone_env: the folder's homes and temp, no HERMES_*). Raises ``cannot_run`` with this computer's fix."""
    hostm = _host_module()
    f = layout(folder)
    for d in (f.home, f.tmp):
        d.mkdir(parents=True, exist_ok=True)
    env = hostm.phone_env(f)
    exe = str(f.emulator('nt' if host['os'] == 'windows' else 'posix'))
    runner = runner or (lambda args: subprocess.run(args, env=env, cwd=str(f.tmp), capture_output=True, timeout=timeout,
                                                    stdin=subprocess.DEVNULL))
    result = {}
    for name, args, fix in (('version', [exe, '-version'], FIX['broken']), ('accel', [exe, '-accel-check'], FIX.get(host['os']))):
        try:
            done = runner(args)
        except (OSError, subprocess.SubprocessError):
            raise InstallError('cannot_run', fix=FIX['broken']) from None
        if done.returncode != 0:
            raise InstallError('cannot_run', fix=fix or FIX['broken'])
        result[name] = 'ok'
    return result


# -- the phone itself (§3 step 6) -------------------------------------------------------------------------------------

def avd_config(host, ram_mb):
    """``config.ini`` from Google's template: Play Store, the pinned image, 8 GB data, ``ram_mb`` (§2.4)."""
    text = AVD_TEMPLATE
    for field, value in (('{{playstore}}', 'yes'), ('{{qemu_tag}}', 'google_apis_playstore'),
                         ('{{ro_product_cpu_abi}}', host['abi']), ('{{qemu_cpu}}', host['cpu'])):
        text = text.replace(field, value)
    overrides = {'AvdId': AVD_NAME, 'avd.ini.displayname': 'Beacon agent phone', 'disk.dataPartition.size': '8G',
                 'hw.ramSize': str(int(ram_mb)), 'tag.display': 'Google Play',
                 'image.sysdir.1': PINS['image']['dest'].format(abi=host['abi']) + '/'}
    lines = []
    for line in text.splitlines():
        key = line.split('=', 1)[0]
        lines.append(f'{key}={overrides[key]}' if key in overrides and '=' in line else line)
    return '\n'.join(lines) + '\n'


def write_avd(folder, host, ram_mb):
    """The phone's AVD (``home/avd/beacon_agent_phone.avd/config.ini`` and ``.ini``). False when it is already there."""
    f = layout(folder)
    config = f.avd_dir / 'config.ini'
    if config.exists():
        return False
    f.avd_dir.mkdir(parents=True, exist_ok=True)
    config.write_bytes(avd_config(host, ram_mb).encode('utf-8'))
    (f.avd_home / f'{AVD_NAME}.ini').write_bytes(
        f'avd.ini.encoding=UTF-8\npath={f.avd_dir}\ntarget=android-{API_LEVEL}\n'.encode('utf-8'))
    return True


def make_phone_key(folder):
    """The phone's own adb key, before the first start (S2; agent_phone_adb.generate_phone_key, no adb binary)."""
    return _sibling('agent_phone_adb').generate_phone_key(layout(folder).home)


def pick_ports(folder, *, console_ports=CONSOLE_PORTS, free=None, wait=30.0):
    """``{'console': P, 'grpc': G}``: P even in 5640-5680 with P and P+1 free, G a free loopback port. Taken under ``ports.lock``
    beside the phone folders; every phone's pick is kept in its own ``run/ports.json``, and picks of the other phone folders
    (and the ports in their ``run/state.json``) are never handed out again while those folders exist."""
    folder = Path(folder)
    root = folder.parent
    free = free or _host_module().port_free
    with _file_lock(root / PORTS_LOCK, wait=wait):
        taken = set()
        for sibling in (p for p in root.iterdir() if p.is_dir() and p.name != folder.name) if root.is_dir() else ():
            for kept in (_read_json(sibling / 'run' / 'ports.json'), (_read_json(sibling / 'run' / 'state.json') or {}).get('ports')):
                if isinstance(kept, dict):
                    for name in ('console', 'grpc'):
                        with contextlib.suppress(TypeError, ValueError):
                            taken.add(int(kept[name]))
                            if name == 'console':
                                taken.add(int(kept[name]) + 1)
        console = next((p for p in console_ports if p % 2 == 0 and p not in taken and p + 1 not in taken and free(p) and free(p + 1)),
                       None)
        if console is None:
            raise InstallError('cannot_run', fix=WORDS['ports'])
        grpc = None
        for _ in range(50):
            with socket.socket() as s:
                s.bind(('127.0.0.1', 0))
                candidate = s.getsockname()[1]
            if candidate not in taken and candidate not in (console, console + 1):
                grpc = candidate
                break
        if grpc is None:
            raise InstallError('cannot_run', fix=WORDS['ports'])
        ports = {'console': console, 'grpc': grpc}
        _write_json(folder / 'run' / 'ports.json', {**ports, 'picked_at': time.time()})
    return ports


# -- the whole install -------------------------------------------------------------------------------------------------

# -- Remove (§7.1) and the helper's pin ------------------------------------------------------------------------------------

HELPER_PIN = 'agent_phone_helper.json'  # the release pin beside this module: L8's helper-pin.json plus its 'url', added by Root
HELPER_URL_SCHEMES = ('https',)  # the release asset; a test that serves a local file or server sets this itself
APK_SIG_BLOCK_MAGIC = b'APK Sig Block 42'
APK_SIGNERS = (0xF05368C0, 0x7109871A)  # APK Signature Scheme v3, then v2 (source.android.com, "APK Signature Scheme v2/v3")


def helper_package(plugin_dir=None):
    """The helper the plugin pins: ``{'package', 'version_code', 'version_name', 'sha256', 'signer_sha256', 'bytes', 'url'}``
    from ``agent_phone_helper.json`` (the format L8's ``pinRelease`` writes, plus the release asset's ``url``), or None while the
    plugin carries no usable pin. The APK is never in the plugin: it is fetched into the phone folder (``fetch_helper``)."""
    pin = _read_json(Path(plugin_dir or Path(__file__).resolve().parent) / HELPER_PIN)
    keys = ('package', 'version_code', 'version_name', 'sha256', 'signer_sha256', 'bytes', 'url')
    if not isinstance(pin, dict) or not pin.get('package') or type(pin.get('bytes')) is not int or pin['bytes'] <= 0:
        return None
    if not all(isinstance(pin.get(k), str) and len(pin[k]) == 64 for k in ('sha256', 'signer_sha256')):
        return None
    if pin.get('signed_by') != 'stable':
        return None  # a pin written by the local-debug build names the debug key's signer: never the release's
    url = pin.get('url')
    if url is not None and (not isinstance(url, str) or urllib.parse.urlsplit(url).scheme not in HELPER_URL_SCHEMES):
        return None  # the release asset is fetched over https only
    value = {k: pin.get(k) for k in keys}
    value['sha256'], value['signer_sha256'] = value['sha256'].lower(), value['signer_sha256'].lower()
    return value


def apk_signers(path):
    """The SHA-256 of each signer's certificate in an APK's v3 (else v2) signing block, as ``apksigner verify --print-certs``
    prints them, read with the standard library only (no apksigner on a Hermes host); None for an APK without one.
    Source.android.com, "APK Signature Scheme v2/v3": the block sits just before the ZIP central directory, ends with
    "APK Sig Block 42", and holds uint64-length ID-value pairs (v3 0xf05368c0, v2 0x7109871a); each signer is length-prefixed
    signed data whose digests come first, then its certificates (each a length-prefixed X.509 DER). Written by L2 (follow-up 1)
    and by the join; one copy here, used by the fetch and by the setup side's helper update."""
    import struct
    data = Path(path).read_bytes()
    eocd = data.rfind(b'PK\x05\x06', max(0, len(data) - 65557))
    if eocd < 0 or len(data) < eocd + 22:
        return None
    central = struct.unpack_from('<I', data, eocd + 16)[0]
    if central < 32 or central > len(data) or data[central - 16:central] != APK_SIG_BLOCK_MAGIC:
        return None
    size = struct.unpack_from('<Q', data, central - 24)[0]
    start, end = central - size - 8, central - 24
    if start < 0 or struct.unpack_from('<Q', data, start)[0] != size:
        return None
    pairs, at = {}, start + 8
    while at + 12 <= end:
        length, ident = struct.unpack_from('<QI', data, at)
        pairs[ident] = data[at + 12:at + 8 + length]
        at += 8 + length

    def items(buf):
        total, at, out = struct.unpack_from('<I', buf, 0)[0], 4, []
        if 4 + total > len(buf):
            raise struct.error('short')
        while at < 4 + total:
            n = struct.unpack_from('<I', buf, at)[0]
            out.append(buf[at + 4:at + 4 + n])
            at += 4 + n
        return out
    block = next((pairs[i] for i in APK_SIGNERS if i in pairs), None)
    if block is None:
        return None
    found = []
    try:
        for signer in items(block):
            signed = signer[4:4 + struct.unpack_from('<I', signer, 0)[0]]
            digests = struct.unpack_from('<I', signed, 0)[0]
            certs = items(signed[4 + digests:])
            if certs:
                found.append(hashlib.sha256(certs[0]).hexdigest())
    except struct.error:
        return None
    return found


def helper_file(folder, pin):
    """Where the fetched helper is kept: the phone folder's ``downloads/helper/<sha256>.apk`` (outside the Hermes home)."""
    return layout(folder).downloads / 'helper' / f"{pin['sha256']}.apk"


def _helper_ok(path, pin):
    try:
        return (path.is_file() and path.stat().st_size == pin['bytes'] and sha1_file(path, 'sha256') == pin['sha256']
                and apk_signers(path) == [pin['signer_sha256']])
    except OSError:
        return False


def helper_apk(folder, plugin_dir=None):
    """The fetched helper's path once its size, SHA-256 and signer match the pin, else None."""
    pin = helper_package(plugin_dir)
    path = helper_file(folder, pin) if pin else None
    return path if path is not None and _helper_ok(path, pin) else None


def fetch_helper(folder, plugin_dir=None, *, opener=None, control=None, progress=None):
    """The pinned helper in the phone folder (fetched at install, and at a start while it isn't there or a Beacon update pinned a
    new one): resumable, checked by size and SHA-256, then by its signer before anything installs it. Returns its path; None
    when this Beacon pins no helper. Raises ``InstallError`` ('offline', retry) when it can't be fetched, ('update') when the
    release has no such file or the file isn't signed as pinned."""
    pin = helper_package(plugin_dir)
    if not pin or not isinstance(pin.get('url'), str) or urllib.parse.urlsplit(pin['url']).scheme not in HELPER_URL_SCHEMES:
        return None
    path = helper_file(folder, pin)
    if _helper_ok(path, pin):
        return path
    try:
        download(pin['url'], path, pin['bytes'], pin['sha256'], opener=opener, control=control, progress=progress,
                 algorithm='sha256')
    except _Gone:
        raise InstallError('update') from None
    if apk_signers(path) != [pin['signer_sha256']]:
        path.unlink(missing_ok=True)
        raise InstallError('update')
    for old in path.parent.glob('*.apk'):
        if old != path:
            old.unlink(missing_ok=True)
    return path


HELPER_LINES = {
    'no_pin': "Saved logins on {name}'s phone need a Beacon update.",
    'not_fetched': "Saved logins on {name}'s phone need a download. Turn the phone on while online.",
}


def helper_status(folder, plugin_dir=None):
    """What the phone folder knows of the helper: ``{'state': 'installed' | 'pending' | 'missing', 'line', 'on_phone'}``.
    installed: on the phone (a newer pin that isn't fetched yet keeps the old one working); pending: fetched and checked, it
    installs at the next start; missing: no pin, or not fetched yet (offline). ``line`` is set only for missing. The phone
    page's own word (L2) comes from what the phone reports; this decides only before the phone was read."""
    pin = helper_package(plugin_dir)
    marker = _host_module().read_json(layout(folder).run / _host_module().FIRST_START) or {}
    on_phone = 'helper' in (marker.get('done') or [])
    current = not pin or marker.get('helper_sha256') in (None, pin['sha256'])
    fetched = helper_apk(folder, plugin_dir) is not None
    if on_phone and (current or not fetched):
        return {'state': 'installed', 'line': None, 'on_phone': True}
    if fetched:
        return {'state': 'pending', 'line': None, 'on_phone': on_phone}
    key = 'not_fetched' if pin and pin.get('url') else 'no_pin'
    return {'state': 'missing', 'line': HELPER_LINES[key], 'reason': f'helper.{key}', 'on_phone': on_phone}


def phone_host(folder, lease=None, plugin_dir=None):
    """L1b's ``PhoneHost`` for this phone as Beacon runs it, the one way the routes and the agent's tool build it: this module's
    locked port pick, the pinned helper (fetched at the first start that needs it), and L2's lease, so an adopt never deletes
    the frame file under an open setup view."""
    pin = helper_package(plugin_dir)
    lease = lease or _sibling('agent_phone_lease').PhoneLease(folder)
    return _host_module().PhoneHost(folder, pick_ports=pick_ports, helper_apk=helper_apk(folder, plugin_dir),
                                    helper_sha256=pin['sha256'] if pin else None,
                                    helper_fetch=lambda: fetch_helper(folder, plugin_dir), view_open=lease.view_open)


def _is_phone_folder(folder, hermes_home):
    """Only ever a phone folder: ``…/Beacon/agent-phone/<16 hex>`` (or ``beacon``), outside the Hermes home."""
    folder = Path(folder)
    return (len(folder.name) == 16 and all(c in '0123456789abcdef' for c in folder.name) and folder.parent.name == 'agent-phone'
            and folder.parent.parent.name in ('Beacon', 'beacon') and not _inside(folder, hermes_home))


def _bytes_in(folder):
    total = 0
    for path in Path(folder).rglob('*'):
        with contextlib.suppress(OSError):
            if path.is_file() and not path.is_symlink():
                total += path.stat().st_size
    return total


def _delete_folder(folder, tries=20):
    """The phone folder, retried for a few seconds: Windows keeps a dead process's files open briefly."""
    def writable(func, path, _):
        with contextlib.suppress(OSError):
            os.chmod(path, stat.S_IWRITE)
            func(path)
    for _ in range(tries):
        if not Path(folder).exists():
            return True
        shutil.rmtree(folder, onerror=writable)
        if not Path(folder).exists():
            return True
        time.sleep(0.5)
    return not Path(folder).exists()


def remove_phone(hermes_home, delete_logins=False, *, profile=None):
    """Remove (§7.1), idempotent: ends the keeper and the phone (a hard stop: nothing is kept, so no Quick Boot save), deletes
    the phone folder (SDK, image, accounts, key, snapshots, logs, frame file), this phone's discovery files in the user's Temp,
    the pointer, the vault sidecar, and ``ports.lock`` plus the empty ``Beacon/agent-phone`` parents when it was the last phone.
    ``delete_logins``: the logins saved from the phone go from the owning profile's vault too (L9's ``forget_phone``).
    Returns ``{'removed', 'freed_bytes', 'logins_deleted'}``; ``logins_deleted`` is None when the vault part couldn't run
    (``logins_error`` then says why), and the phone is removed anyway."""
    hermes_home = Path(hermes_home)
    pointer = read_pointer(hermes_home) or {}
    folder = Path(pointer.get('folder') or phone_folder(hermes_home))
    if not _is_phone_folder(folder, hermes_home):
        raise ValueError('not a phone folder')
    profile = profile or pointer.get('profile')
    result = {'removed': False, 'freed_bytes': 0, 'logins_deleted': 0}
    try:  # before the folder goes: the vault side reads its sidecar to know which logins came from the phone
        forget = getattr(_sibling('agent_phone_vault'), 'forget_phone', None)
        if forget is None:
            raise LookupError('unavailable')
        result['logins_deleted'] = forget(hermes_home, profile, delete_logins=bool(delete_logins))
    except Exception as error:  # health: allow BLE001 -- the phone is removed even when the vault part can't run
        result['logins_deleted'], result['logins_error'] = None, type(error).__name__
    pointer_path(hermes_home).unlink(missing_ok=True)
    if folder.is_dir():
        hostm = _host_module()
        f = hostm.Folder(folder)
        host = hostm.PhoneHost(folder)
        with host.lock():
            for conf in (hostm.read_json(f.keeper_conf) or {}, hostm.read_json(f.state) or {}):
                hostm.end_tree(conf.get('pid'), conf.get('create_time'))
                hostm.end_tree(conf.get('qemu_pid'), conf.get('qemu_create_time'))
            with contextlib.suppress(Exception):
                host.cleanup_discovery()
        result['freed_bytes'] = _bytes_in(folder)
        result['removed'] = _delete_folder(folder)
        if not result['removed']:
            result['freed_bytes'] -= _bytes_in(folder)
    root = folder.parent
    if root.is_dir() and not any(p.is_dir() for p in root.iterdir()):
        with contextlib.suppress(OSError):
            (root / PORTS_LOCK).unlink(missing_ok=True)
        for parent in (root, root.parent):
            with contextlib.suppress(OSError):
                parent.rmdir()  # only when empty
    return result


def checks_passed(checks):
    """The host checks ran (agent_phone_checks.run_checks) and nothing blocks: no failed row, and a warning that asks for Continue
    was continued by the owner."""
    return bool(isinstance(checks, dict) and checks.get('ok') and (not checks.get('needs_continue') or checks.get('continued')))


def run_install(folder, *, checks, hermes_home=None, profile=None, dashboard=None, host=None, base_url=REPOSITORY, opener=None,
                control=None, progress=None, proto_ok=None, total_memory=None, runner=None):
    """Steps 4-6 of §3 after the checks and the owner's Accept: the emulator, its test run, the image, the AVD, the phone's key.

    Refuses before any download unless ``checks`` passed and every planned licence was accepted. One install per folder at a time.
    ``progress(event=…)`` gets phases (download, check, set up), byte counts and the "damaged, getting it again" line;
    ``proto_ok(path) -> bool`` is the gRPC proto contract test (agent_phone_grpc), needed only for the 404 fallback."""
    if not checks_passed(checks):
        raise InstallError('checks')
    if hermes_home is not None and _inside(folder, hermes_home):  # §2.1: never inside the Hermes home (backups would copy it)
        raise ValueError('the phone folder must be outside the Hermes home')
    host = host or host_platform()
    if not host['supported']:
        raise InstallError('cannot_run', fix=FIX['unsupported'])
    f = layout(folder)
    for d in (f.root, f.run, f.downloads):
        d.mkdir(parents=True, exist_ok=True)
    with contextlib.suppress(Exception):
        from hermes_constants import secure_parent_dir
        secure_parent_dir(f.root / 'run')  # the phone folder itself becomes owner-only (POSIX 0700)
    control = control or Control()
    with _file_lock(f.run / 'install.lock', wait=0):
        indexes = fetch_indexes(folder, base_url, opener)
        picks = plan(indexes, host)
        if not licences_accepted(folder, licences(picks)):
            raise InstallError('licences')
        total = sizes(picks, folder)
        if progress:
            progress(event='plan', **total)
        receipt = {'started': time.strftime('%Y-%m-%dT%H:%M:%S%z'), 'packages': []}
        earlier = {p.get('key'): p for p in (_read_json(f.install_receipt) or {}).get('packages', []) if isinstance(p, dict)}
        try:
            for pick in picks:
                if installed(folder, pick):
                    kept = earlier.get(pick['key']) if earlier.get(pick['key'], {}).get('revision') == pick['revision'] else {}
                    receipt['packages'].append({**kept, 'key': pick['key'], 'revision': pick['revision'], 'already': True})
                else:
                    try:
                        receipt['packages'].append(install_package(folder, pick, opener=opener, control=control, progress=progress,
                                                                   proto_ok=proto_ok))
                    except _Gone:
                        fallback = stable_pick(pick['key'], indexes[PINS[pick['key']]['index']], host)
                        if fallback is None or fallback['url'] == pick['url']:
                            raise InstallError('update') from None
                        if not licences_accepted(folder, licences([fallback])):
                            raise InstallError('licences') from None
                        try:
                            receipt['packages'].append(install_package(folder, fallback, opener=opener, control=control,
                                                                       progress=progress, proto_ok=proto_ok))
                        except _Gone:
                            raise InstallError('update') from None
                if pick['key'] == 'emulator':
                    receipt['test'] = test_emulator(folder, host, runner=runner)
        except Cancelled:
            clean_partial(folder)
            raise
        # An empty platform-tools folder, never the package: emulator 37.2.13 refuses to start without the folder ("Broken AVD
        # system path"), and with adb inside it the emulator starts its own adb server with the developer's key (L1b, 17 Nov).
        (f.sdk / 'platform-tools').mkdir(parents=True, exist_ok=True)
        if total_memory is None:
            import psutil
            total_memory = psutil.virtual_memory().total
        receipt['avd_written'] = write_avd(folder, host, _host_module().guest_ram_mb(total_memory))
        receipt['key_generated'] = bool(make_phone_key(folder))
        try:  # Beacon's helper for the phone (Root, 17 Nov: fetched at install, never shipped in the plugin)
            receipt['helper'] = 'fetched' if fetch_helper(folder, opener=opener, control=control) else 'not pinned'
        except InstallError:
            receipt['helper'] = 'not fetched'  # the phone's first start tries again; the phone page says so meanwhile
        receipt['finished'] = time.strftime('%Y-%m-%dT%H:%M:%S%z')
        _write_json(f.install_receipt, receipt)
        if hermes_home is not None:
            write_pointer(hermes_home, f.root, profile=profile, dashboard=dashboard)
        return receipt
