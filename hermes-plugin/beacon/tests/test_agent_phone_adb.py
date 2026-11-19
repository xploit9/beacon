"""The agent phone's adb (agent_phone_adb.py): adb's wire protocol straight to the emulator's adb port, no adb server.

A fake adbd on loopback plays the device side as AOSP's docs/dev/protocol.md describes it: it sends AUTH tokens, verifies
signatures with the public key it trusts (RSA_verify(NID_sha1, token) as daemon/auth.cpp:178), refuses a WRTE sent before its
OKAY, and records everything a test needs to assert (handshakes, public keys offered, chunk sizes, commands).
"""
import base64
import importlib.util
import socket
import struct
import threading
from pathlib import Path

import pytest
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import padding, rsa, utils

PLUGIN = Path(__file__).resolve().parents[1]
_spec = importlib.util.spec_from_file_location('beacon_agent_phone_adb_under_test', PLUGIN / 'agent_phone_adb.py')
adb = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(adb)

H = struct.Struct('<6I')


def _msg(cmd, a0, a1, data=b'', check=0):
    return H.pack(cmd, a0, a1, len(data), check, cmd ^ 0xFFFFFFFF) + data


class FakeAdbd:
    """One phone's adbd. ``trusted`` is the public key it accepts (None: accepts nothing)."""

    def __init__(self, trusted, maxdata=256 * 1024, handler=None, stls=False, drop_after_open=False):
        self.trusted, self.maxdata, self.handler = trusted, maxdata, handler or self.default_handler
        self.stls, self.drop_after_open = stls, drop_after_open
        self.listener = socket.socket()
        self.listener.bind(('127.0.0.1', 0))
        self.listener.listen(8)
        self.port = self.listener.getsockname()[1]
        self.handshakes = self.pubkeys_offered = self.signatures = 0
        self.services, self.install_chunks, self.installed, self.violations = [], [], bytearray(), []
        self.thread = threading.Thread(target=self.serve, daemon=True)
        self.thread.start()

    @staticmethod
    def default_handler(cmd):
        if cmd == "'echo' 'hi'":
            return b'hi\n', b'', 0
        return b'', b'unknown\n', 127

    def close(self):
        self.listener.close()

    def serve(self):
        while True:
            try:
                conn, _ = self.listener.accept()
            except OSError:
                return
            threading.Thread(target=self.session, args=(conn,), daemon=True).start()

    def _read(self, conn):
        head = b''
        while len(head) < 24:
            chunk = conn.recv(24 - len(head))
            if not chunk:
                raise ConnectionError
            head += chunk
        cmd, a0, a1, n, _check, magic = H.unpack(head)
        assert magic == cmd ^ 0xFFFFFFFF
        data = b''
        while len(data) < n:
            data += conn.recv(n - len(data))
        return cmd, a0, a1, data

    def session(self, conn):
        try:
            cmd, a0, a1, data = self._read(conn)
            assert cmd == adb.A_CNXN and data.startswith(b'host::')
            if self.stls:
                conn.sendall(_msg(adb.A_STLS, 0x01000000, 0))
                return
            token = b'\x05' * 20
            conn.sendall(_msg(adb.A_AUTH, 1, 0, token))  # check 0 before the version is agreed, as measured
            while True:
                cmd, a0, a1, data = self._read(conn)
                if cmd == adb.A_AUTH and a0 == 3:
                    self.pubkeys_offered += 1
                    return
                assert cmd == adb.A_AUTH and a0 == 2
                self.signatures += 1
                try:
                    self.trusted.verify(data, token, padding.PKCS1v15(), utils.Prehashed(hashes.SHA1()))
                    break
                except Exception:
                    token = bytes([token[0] + 1]) * 20
                    conn.sendall(_msg(adb.A_AUTH, 1, 0, token))
            self.handshakes += 1
            conn.sendall(_msg(adb.A_CNXN, 0x01000001, self.maxdata, b'device::ro.product.name=fake;features=shell_v2,cmd'))
            remote = 100
            while True:
                cmd, a0, a1, data = self._read(conn)
                if cmd != adb.A_OPEN:
                    continue
                remote += 1
                service = data.rstrip(b'\0').decode()
                self.services.append(service)
                conn.sendall(_msg(adb.A_OKAY, remote, a0))
                if self.drop_after_open:
                    conn.close()
                    return
                self.run(conn, service, remote, a0)
        except (ConnectionError, OSError, AssertionError):
            pass
        finally:
            conn.close()

    def _write(self, conn, remote, local, payload):
        conn.sendall(_msg(adb.A_WRTE, remote, local, payload))
        while True:  # wait for the host's OKAY for this write
            cmd, a0, a1, _ = self._read(conn)
            if cmd == adb.A_OKAY and a1 == remote:
                return
            if cmd == adb.A_WRTE:
                self.violations.append('host wrote while the phone waited for an OKAY')

    def run(self, conn, service, remote, local):
        if service.startswith('shell,v2,raw:'):
            out, err, code = self.handler(service[len('shell,v2,raw:'):])
            if code == 'early-close':  # the command ends before the host's close-stdin
                self._write(conn, remote, local, adb.shell_packet(adb.SHELL_STDOUT, b'fast\n')
                            + adb.shell_packet(adb.SHELL_EXIT, b'\0'))
                conn.sendall(_msg(adb.A_CLSE, remote, local))
                return
            cmd, a0, a1, data = self._read(conn)  # the host's close-stdin packet
            assert cmd == adb.A_WRTE and data[:1] == bytes([adb.SHELL_CLOSE_STDIN])
            conn.sendall(_msg(adb.A_OKAY, remote, local))
            packets = b''
            if out:
                packets += adb.shell_packet(adb.SHELL_STDOUT, out)
            if err:
                packets += adb.shell_packet(adb.SHELL_STDERR, err)
            # split the stream at an odd place: the host must reassemble shell packets across writes
            packets += adb.shell_packet(adb.SHELL_EXIT, bytes([code]))
            for start in range(0, len(packets), 3):
                self._write(conn, remote, local, packets[start:start + 3])
        elif service.startswith('exec:cmd package '):
            size = int(service.rsplit('-S ', 1)[1])
            got = 0
            while got < size:
                cmd, a0, a1, data = self._read(conn)
                assert cmd == adb.A_WRTE
                if len(data) > self.maxdata:
                    self.violations.append('chunk larger than maxdata')
                self.install_chunks.append(len(data))
                self.installed += data
                got += len(data)
                conn.sendall(_msg(adb.A_OKAY, remote, local))
            self._write(conn, remote, local, b'Success\n')
        elif service.startswith('exec:'):
            self._write(conn, remote, local, bytes(range(256)) + b'\r\n\n')
        conn.sendall(_msg(adb.A_CLSE, remote, local))


@pytest.fixture
def phone(tmp_path):
    home = tmp_path / 'home'
    assert adb.generate_phone_key(home) is True
    fake = FakeAdbd(adb.load_phone_key(home).public_key())
    yield home, fake
    fake.close()


def test_phone_key_files_have_adbs_formats_and_are_never_rewritten(tmp_path):
    home = tmp_path / 'home'
    assert adb.generate_phone_key(home) is True
    pem = (home / 'adbkey').read_bytes()
    assert pem.startswith(b'-----BEGIN PRIVATE KEY-----')  # PKCS#8, as PEM_write_PrivateKey in adb's generate_key
    pub_text = (home / 'adbkey.pub').read_text()
    b64, comment = pub_text.split(' ')
    assert comment == 'beacon@agent-phone' and not pub_text.endswith('\n')  # adb writes no newline
    blob = base64.b64decode(b64)
    assert len(blob) == 4 + 4 + 256 + 256 + 4  # android_pubkey.cpp RSAPublicKey
    words, n0inv = struct.unpack_from('<II', blob)
    n = int.from_bytes(blob[8:264], 'little')
    rr = int.from_bytes(blob[264:520], 'little')
    (e,) = struct.unpack_from('<I', blob, 520)
    key = adb.load_phone_key(home)
    assert words == 64 and n == key.public_key().public_numbers().n and e == 65537
    assert (n0inv * n) % (1 << 32) == (1 << 32) - 1  # n0inv = -1 / n[0] mod 2^32
    assert rr == pow(1 << 2048, 2, n)
    assert adb.generate_phone_key(home) is False and (home / 'adbkey').read_bytes() == pem


def test_handshake_with_the_phone_key_alone_and_one_shell_command(phone):
    home, fake = phone
    r = adb.PhoneAdb(home, fake.port).shell(['echo', 'hi'])
    assert (r.returncode, r.stdout, r.stderr) == (0, 'hi\n', '')
    assert fake.handshakes == 1 and fake.signatures == 1 and fake.pubkeys_offered == 0
    assert fake.services == ["shell,v2,raw:'echo' 'hi'"]


def test_exit_status_stderr_and_reassembly_across_writes(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    fake = FakeAdbd(adb.load_phone_key(home).public_key(), handler=lambda c: (b'o' * 70, b'e' * 9, 7))
    try:
        r = adb.PhoneAdb(home, fake.port).shell(['anything'])
        assert (r.returncode, r.stdout, r.stderr) == (7, 'o' * 70, 'e' * 9)
        assert not fake.violations
    finally:
        fake.close()


def test_every_argument_is_quoted_for_the_device_shell(phone):
    home, fake = phone
    adb.PhoneAdb(home, fake.port).shell(['am', 'start', '--es', 'x', "it's $HOME; rm -rf / `id` \"q\""])
    assert fake.services[-1] == "shell,v2,raw:'am' 'start' '--es' 'x' 'it'\\''s $HOME; rm -rf / `id` \"q\"'"


def test_a_refused_key_fails_cleanly_and_never_offers_a_public_key(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    stranger = rsa.generate_private_key(public_exponent=65537, key_size=2048).public_key()
    fake = FakeAdbd(stranger)
    try:
        with pytest.raises(adb.KeyRefused):
            adb.PhoneAdb(home, fake.port).shell(['echo', 'hi'])
        assert fake.signatures == 1 and fake.pubkeys_offered == 0 and fake.handshakes == 0
    finally:
        fake.close()


def test_exec_out_returns_the_bytes_unchanged(phone):
    home, fake = phone
    assert adb.PhoneAdb(home, fake.port).exec_out(['screencap', '-p']) == bytes(range(256)) + b'\r\n\n'
    assert fake.services[-1] == "exec:'screencap' '-p'"


def test_several_commands_share_one_handshake(phone):
    home, fake = phone
    with adb.PhoneAdb(home, fake.port).session() as s:
        assert s.shell(['echo', 'hi']).stdout == 'hi\n'
        assert s.exec_out(['x']).endswith(b'\r\n\n')
        assert s.shell(['echo', 'hi']).returncode == 0
    assert fake.handshakes == 1 and len(fake.services) == 3


def test_install_streams_in_bounded_chunks_one_write_in_flight(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    fake = FakeAdbd(adb.load_phone_key(home).public_key(), maxdata=40000)
    apk = tmp_path / 'helper.apk'
    payload = bytes(range(256)) * 1000  # 256000 bytes
    apk.write_bytes(payload)
    try:
        r = adb.PhoneAdb(home, fake.port).install(apk)
        assert r.returncode == 0 and r.stdout.startswith('Success')
        assert bytes(fake.installed) == payload
        assert max(fake.install_chunks) <= 40000 and len(fake.install_chunks) >= 7  # 64 KiB reads, split at maxdata
        assert fake.services[-1] == "exec:cmd package 'install' '-r' -S 256000"
        assert not fake.violations
    finally:
        fake.close()


def test_a_command_that_ends_before_close_stdin_is_not_an_error(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    fake = FakeAdbd(adb.load_phone_key(home).public_key(), handler=lambda c: (b'', b'', 'early-close'))
    try:
        r = adb.PhoneAdb(home, fake.port).shell(['true'])
        assert (r.returncode, r.stdout) == (0, 'fast\n')
    finally:
        fake.close()


def test_failures_never_carry_the_arguments(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    fake = FakeAdbd(adb.load_phone_key(home).public_key(), drop_after_open=True)
    secret = 'tok_' + 'Z' * 40
    try:
        with pytest.raises(adb.AdbError) as err:
            adb.PhoneAdb(home, fake.port).shell(['am', 'start-foreground-service', '--es', 'token', secret])
        assert secret not in str(err.value) and secret not in repr(err.value)
        r = adb.PhoneAdb(home, FakeAdbd(adb.load_phone_key(home).public_key()).port).shell(['x', secret])
        assert secret not in str(r) and r.args == '<agent phone command>'
    finally:
        fake.close()


def test_tls_and_an_unreachable_phone_are_clean_errors(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    fake = FakeAdbd(None, stls=True)
    try:
        with pytest.raises(adb.AdbError):
            adb.PhoneAdb(home, fake.port).shell(['id'])
    finally:
        fake.close()
    s = socket.socket()
    s.bind(('127.0.0.1', 0))
    free = s.getsockname()[1]
    s.close()
    with pytest.raises(adb.AdbError):
        adb.PhoneAdb(home, free, connect_seconds=1).shell(['id'])
    with pytest.raises(adb.AdbError):
        adb.PhoneAdb(tmp_path / 'no-key', free).shell(['id'])


def test_a_silent_phone_times_out(tmp_path):
    home = tmp_path / 'home'
    adb.generate_phone_key(home)
    silent = socket.socket()
    silent.bind(('127.0.0.1', 0))
    silent.listen(1)
    try:
        with pytest.raises(adb.AdbError):
            adb.PhoneAdb(home, silent.getsockname()[1], connect_seconds=0.5).shell(['id'])
    finally:
        silent.close()


def test_the_callable_is_l9s_shell(phone):
    home, fake = phone
    result = adb.PhoneAdb(home, fake.port)(['echo', 'hi'])
    assert getattr(result, 'returncode', None) == 0


def test_no_code_path_offers_a_public_key_or_runs_an_adb_binary():
    src = (PLUGIN / 'agent_phone_adb.py').read_text(encoding='utf-8')
    assert 'send(A_AUTH, AUTH_RSAPUBLICKEY' not in src and 'AUTH_RSAPUBLICKEY,' not in src.split('AUTH_TOKEN, AUTH_SIGNATURE')[1]
    assert 'subprocess.run' not in src and 'Popen' not in src and 'adb.exe' not in src
    assert 'listen(' not in src and '.bind(' not in src  # nothing of ours listens
