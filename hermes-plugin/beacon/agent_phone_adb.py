"""The agent phone's adb: adb's own wire protocol, spoken straight to the emulator's adb port with the phone's own key.

No adb server runs, so nothing of Beacon's listens on loopback for adb (Root, 17 Nov 2026, EMU-DESIGN §11.14 taken): on Windows
every adb server loads the developer's ``~/.android/adbkey`` whatever HOME, USERPROFILE or ANDROID_USER_HOME say, and a phone app
can reach a loopback server through 10.0.2.2 (S10). Here only the phone's own key ever signs, and it signs only the phone's token.

Written from AOSP ``platform/packages/modules/adb`` (main, fetched 16 Nov 2026):
- ``docs/dev/protocol.md``: the 24-byte header, CNXN, AUTH (TOKEN 1, SIGNATURE 2, RSAPUBLICKEY 3), OPEN, OKAY, WRTE, CLSE, one WRTE
  in flight until the peer's OKAY, and "a new AUTH packet and a new random token" when a signature fails;
- ``adb.h``: command constants, ``A_VERSION`` 0x01000001 (skip checksum), ``MAX_PAYLOAD``, ``MAX_PAYLOAD_V1``, ``TOKEN_SIZE`` 20;
- ``adb.cpp`` ``calculate_apacket_checksum`` (byte sum) and ``get_connection_string`` (``host::features=…``);
- ``client/auth.cpp`` ``adb_auth_sign``: ``RSA_sign(NID_sha1, token, 20)``, i.e. PKCS#1 v1.5 over the token as a SHA-1 digest;
  ``send_auth_response`` sends the public key only when every key failed: this module never does (no "Allow USB debugging?");
- ``sockets.cpp`` ``connect_to_remote``: the OPEN payload is the destination plus a NUL;
- ``shell_protocol.h``: shell v2 packets (id byte, 4-byte little-endian length; stdin 0, stdout 1, stderr 2, exit 3, close-stdin 4);
- ``docs/dev/services.md``: ``shell,v2:`` (API 24+) and ``exec:``;
- ``client/adb_install.cpp`` ``install_app_streamed``: ``exec:cmd package install … -S <size>``, the file streamed, then a
  status line starting with ``Success``;
- ``crypto/rsa_2048_key.cpp`` and ``system/core`` ``libcrypto_utils/android_pubkey.cpp``: the ``adbkey.pub`` format.

Nothing here logs or raises with a command's arguments or output: they can hold tokens (the vault channel's start intent).
"""
import base64
import contextlib
import os
import socket
import struct
import subprocess
import time
from pathlib import Path

A_CNXN, A_AUTH, A_OPEN, A_OKAY = 0x4E584E43, 0x48545541, 0x4E45504F, 0x59414B4F
A_CLSE, A_WRTE, A_STLS = 0x45534C43, 0x45545257, 0x534C5453
COMMANDS = frozenset({A_CNXN, A_AUTH, A_OPEN, A_OKAY, A_CLSE, A_WRTE, A_STLS})
A_VERSION_MIN, A_VERSION_SKIP_CHECKSUM, A_VERSION = 0x01000000, 0x01000001, 0x01000001
MAX_PAYLOAD, MAX_PAYLOAD_V1 = 1024 * 1024, 4096
AUTH_TOKEN, AUTH_SIGNATURE, AUTH_RSAPUBLICKEY = 1, 2, 3
TOKEN_SIZE = 20
SHELL_STDIN, SHELL_STDOUT, SHELL_STDERR, SHELL_EXIT, SHELL_CLOSE_STDIN = 0, 1, 2, 3, 4
BANNER = b'host::features=shell_v2,cmd'
KEY_COMMENT = 'beacon@agent-phone'  # adb writes "<login>@<host>"; the phone never needs the owner's names
HEADER = struct.Struct('<6I')
CONNECT_SECONDS = 10.0
WRITE_CHUNK = 64 * 1024


class AdbError(Exception):
    """The phone's adb failed. The text is a fixed reason, never a command, an argument or device output."""


class KeyRefused(AdbError):
    """The phone did not accept the phone's key. Nothing else is offered, so the phone shows no prompt."""


def quote(arg):
    """One argument for the device's ``sh``: single-quoted, a ``'`` closed, escaped and reopened (as adb's ``escape_arg``)."""
    return "'" + str(arg).replace("'", "'\\''") + "'"


def command_line(args):
    """A list is quoted argument by argument; a str is used as written (a fixed script of the caller's, never outside text)."""
    if isinstance(args, str):
        return args
    return ' '.join(quote(a) for a in args)


# -- the phone's key ----------------------------------------------------------------------------------------------------

def android_pubkey(public_key):
    """Android's binary RSA public key (android_pubkey.cpp), base64, plus a neutral comment: the ``adbkey.pub`` text."""
    numbers = public_key.public_numbers()
    n, e = numbers.n, numbers.e
    size = public_key.key_size // 8
    if size != 256:
        raise AdbError('The phone key must be RSA 2048.')
    r32 = 1 << 32
    n0inv = (r32 - pow(n % r32, -1, r32)) % r32
    rr = pow(1 << (size * 8), 2, n)
    blob = (struct.pack('<II', size // 4, n0inv) + n.to_bytes(size, 'little') + rr.to_bytes(size, 'little')
            + struct.pack('<I', e))
    return base64.b64encode(blob).decode('ascii') + ' ' + KEY_COMMENT


def _write_private(path, data):
    path = Path(path)
    tmp = path.with_name(path.name + '.tmp')
    flags = os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_BINARY', 0)
    fd = os.open(tmp, flags, 0o600)
    try:
        os.write(fd, data)
    finally:
        os.close(fd)
    os.replace(tmp, path)


def generate_phone_key(home):
    """Writes ``home/adbkey`` (PKCS#8 PEM, as adb's ``generate_key``) and ``home/adbkey.pub`` once, before the first start.

    The emulator gives the phone the key it finds in its user home; without one it copied the developer's (S2). No adb binary
    runs, so the developer's ``~/.android`` is never read. Returns False when the phone already has its key."""
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    home = Path(home)
    if (home / 'adbkey').exists() and (home / 'adbkey.pub').exists():
        return False
    home.mkdir(parents=True, exist_ok=True)
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption())
    _write_private(home / 'adbkey', pem)
    _write_private(home / 'adbkey.pub', android_pubkey(key.public_key()).encode('ascii'))
    return True


def load_phone_key(home):
    from cryptography.hazmat.primitives import serialization

    try:
        data = (Path(home) / 'adbkey').read_bytes()
    except OSError:
        raise AdbError('The phone has no key yet.') from None
    return serialization.load_pem_private_key(data, password=None)


def sign_token(key, token):
    """``RSA_sign(NID_sha1, token, 20)`` (client/auth.cpp:268): PKCS#1 v1.5 with the token taken as the SHA-1 digest."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import padding, utils

    if len(token) != TOKEN_SIZE:
        raise AdbError('The phone sent an unexpected token.')
    return key.sign(bytes(token), padding.PKCS1v15(), utils.Prehashed(hashes.SHA1()))


# -- messages -----------------------------------------------------------------------------------------------------------

def checksum(data):
    return sum(data) & 0xFFFFFFFF


def pack(command, arg0, arg1, data=b''):
    data = bytes(data)
    return HEADER.pack(command, arg0 & 0xFFFFFFFF, arg1 & 0xFFFFFFFF, len(data), checksum(data), command ^ 0xFFFFFFFF) + data


def shell_packet(kind, data=b''):
    return struct.pack('<BI', kind, len(data)) + bytes(data)


class _Link:
    """One connection to the phone's adbd: the handshake, then streams opened one after another."""

    def __init__(self, sock, key, deadline):
        self.sock = sock
        self.key = key
        self.version = A_VERSION_MIN
        self.max_send = MAX_PAYLOAD_V1
        self.features = frozenset()
        self.next_id = 1
        self.deadline = deadline

    # -- wire --
    def _recv_exact(self, n):
        buf = bytearray()
        while len(buf) < n:
            self.sock.settimeout(max(0.05, self.deadline - time.monotonic()))
            try:
                chunk = self.sock.recv(n - len(buf))
            except socket.timeout:
                raise AdbError('The phone did not answer in time.') from None
            except OSError:
                raise AdbError('The connection to the phone broke.') from None
            if not chunk:
                raise AdbError('The phone closed the connection.')
            buf += chunk
        return bytes(buf)

    def read(self):
        command, arg0, arg1, length, check, magic = HEADER.unpack(self._recv_exact(HEADER.size))
        if command not in COMMANDS or magic != command ^ 0xFFFFFFFF or length > MAX_PAYLOAD:
            raise AdbError('The phone sent a message that is not adb.')
        # Not checked: AOSP's receiver never verifies data_check (transport.cpp:567-570 only sets it on send), and adbd sends 0
        # before the version is agreed (measured). TCP carries its own checksum.
        data = self._recv_exact(length) if length else b''
        return command, arg0, arg1, data

    def send(self, command, arg0, arg1, data=b''):
        self.sock.settimeout(max(0.05, self.deadline - time.monotonic()))
        try:
            self.sock.sendall(pack(command, arg0, arg1, data))
        except OSError:
            raise AdbError('The connection to the phone broke.') from None

    # -- handshake --
    def handshake(self):
        self.send(A_CNXN, A_VERSION, MAX_PAYLOAD, BANNER)
        signed = False
        while True:
            command, arg0, arg1, data = self.read()
            if command == A_CNXN:
                self.version = min(arg0, A_VERSION)
                self.max_send = max(MAX_PAYLOAD_V1, min(arg1, MAX_PAYLOAD))
                text = data.decode('utf-8', 'replace')
                for part in text.split('::', 1)[-1].split(';'):
                    if part.startswith('features='):
                        self.features = frozenset(f for f in part[len('features='):].split(',') if f)
                return
            if command == A_AUTH and arg0 == AUTH_TOKEN:
                if signed:  # a second token: the phone's adbd refused our one key (protocol.md, AUTH)
                    raise KeyRefused('The phone refused its key.')
                self.send(A_AUTH, AUTH_SIGNATURE, 0, sign_token(self.key, data))
                signed = True
                continue
            if command == A_STLS:
                raise AdbError('The phone asked for TLS, which an emulator never does.')
            # protocol.md: anything before CNXN is ignored

    # -- one stream --
    def open(self, destination):
        local = self.next_id
        self.next_id += 1
        self.send(A_OPEN, local, 0, destination.encode('utf-8') + b'\0')
        while True:
            command, arg0, arg1, data = self.read()
            if command == A_OKAY and arg1 == local:
                return _Stream(self, local, arg0)
            if command == A_CLSE and arg1 == local:
                raise AdbError('The phone refused the command.')
            if command == A_WRTE:  # a write for a stream we no longer have: acknowledge and drop it
                self.send(A_OKAY, arg1, arg0)


class _Stream:
    def __init__(self, link, local, remote):
        self.link, self.local, self.remote = link, local, remote
        self.closed = False

    def _handle(self, command, arg0, arg1, data, sink):
        if arg1 != self.local:
            if command == A_WRTE:
                self.link.send(A_OKAY, arg1, arg0)
            return False
        if command == A_WRTE:
            sink(data)
            self.link.send(A_OKAY, self.local, self.remote)
        elif command == A_CLSE:
            self.closed = True
        return command == A_OKAY

    def write(self, data, sink):
        """Sends data in chunks the phone accepts, one WRTE in flight (protocol.md), taking the phone's writes meanwhile.
        Returns False when the phone closed the stream first (a command can end before its input is sent)."""
        view = memoryview(data)
        step = min(self.link.max_send, WRITE_CHUNK)
        for start in range(0, len(view), step):
            if self.closed:
                return False
            self.link.send(A_WRTE, self.local, self.remote, view[start:start + step])
            while not self.closed:
                if self._handle(*self.link.read(), sink):
                    break
        return not self.closed

    def read_all(self, sink):
        while not self.closed:
            self._handle(*self.link.read(), sink)

    def close(self):
        if not self.closed:
            self.closed = True
            with contextlib.suppress(AdbError):
                self.link.send(A_CLSE, self.local, self.remote)


class _ShellParser:
    def __init__(self):
        self.buf = bytearray()
        self.out, self.err = bytearray(), bytearray()
        self.code = None

    def feed(self, data):
        self.buf += data
        while len(self.buf) >= 5:
            kind, length = struct.unpack_from('<BI', self.buf)
            if len(self.buf) < 5 + length:
                return
            body = bytes(self.buf[5:5 + length])
            del self.buf[:5 + length]
            if kind == SHELL_STDOUT:
                self.out += body
            elif kind == SHELL_STDERR:
                self.err += body
            elif kind == SHELL_EXIT and body:
                self.code = body[0]


def _result(code, out, err, binary):
    if not binary:
        out, err = bytes(out).decode('utf-8', 'replace'), bytes(err).decode('utf-8', 'replace')
    else:
        out, err = bytes(out), bytes(err)
    # args are never kept: a caller printing the result must not print a token
    return subprocess.CompletedProcess('<agent phone command>', code, out, err)


class Session:
    """Several commands over one connection and one handshake."""

    def __init__(self, link):
        self._link = link

    def _deadline(self, timeout):
        self._link.deadline = time.monotonic() + float(timeout)

    def shell(self, args, timeout=60.0, input=None, binary=False):
        """Runs a command through ``shell,v2`` and returns a CompletedProcess(returncode, stdout, stderr); never raises for a
        non-zero exit. The returncode is None when the phone ended the stream without one."""
        self._deadline(timeout)
        stream = self._link.open('shell,v2,raw:' + command_line(args))
        parser = _ShellParser()
        try:
            data = (input.encode('utf-8') if isinstance(input, str) else bytes(input)) if input else b''
            for start in range(0, len(data), WRITE_CHUNK):
                if not stream.write(shell_packet(SHELL_STDIN, data[start:start + WRITE_CHUNK]), parser.feed):
                    break
            if not stream.closed:
                stream.write(shell_packet(SHELL_CLOSE_STDIN), parser.feed)
            stream.read_all(parser.feed)
        finally:
            stream.close()
        return _result(parser.code, parser.out, parser.err, binary)

    def exec_out(self, args, timeout=60.0):
        """Runs a command through ``exec:`` (a raw pipe, no shell protocol) and returns its bytes, e.g. a tree on stdout."""
        self._deadline(timeout)
        stream = self._link.open('exec:' + command_line(args))
        out = bytearray()
        try:
            stream.read_all(out.extend)
        finally:
            stream.close()
        return bytes(out)

    def install(self, apk, extra=('-r',), timeout=300.0):
        """Streams an APK as adb's ``install_app_streamed`` does (``cmd package install … -S <size>``), never whole in memory.
        Returns a CompletedProcess whose returncode is 0 only when the status line starts with ``Success``."""
        apk = Path(apk)
        size = apk.stat().st_size
        self._deadline(timeout)
        stream = self._link.open('exec:cmd package ' + ' '.join([quote('install'), *(quote(a) for a in extra), '-S', str(size)]))
        out = bytearray()
        try:
            with apk.open('rb') as fh:
                while True:
                    chunk = fh.read(WRITE_CHUNK)
                    if not chunk or not stream.write(chunk, out.extend):
                        break  # a closed stream before the end means the install failed; its status line says why
            stream.read_all(out.extend)
        finally:
            stream.close()
        return _result(0 if bytes(out).startswith(b'Success') else 1, out, b'', False)


class PhoneAdb:
    """The agent phone's adb: ``PhoneAdb(home, adb_port)``; ``home`` holds ``adbkey``, ``adb_port`` is the emulator's (console+1).

    ``shell``, ``exec_out`` and ``install`` each open a connection, run one command and close it; ``session()`` keeps one
    connection for several. Nothing listens anywhere; a connection lives only while a command runs."""

    def __init__(self, home, adb_port, host='127.0.0.1', connect_seconds=CONNECT_SECONDS):
        self.home = Path(home)
        self.adb_port = int(adb_port)
        self.host = host
        self.connect_seconds = connect_seconds

    @contextlib.contextmanager
    def session(self):
        key = load_phone_key(self.home)
        deadline = time.monotonic() + self.connect_seconds
        try:
            sock = socket.create_connection((self.host, self.adb_port), timeout=self.connect_seconds)
        except OSError:
            raise AdbError('The phone is not reachable.') from None
        try:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            link = _Link(sock, key, deadline)
            link.handshake()
            yield Session(link)
        finally:
            with contextlib.suppress(OSError):
                sock.close()

    def shell(self, args, timeout=60.0, input=None, binary=False):
        with self.session() as s:
            return s.shell(args, timeout=timeout, input=input, binary=binary)

    def exec_out(self, args, timeout=60.0):
        with self.session() as s:
            return s.exec_out(args, timeout=timeout)

    def install(self, apk, extra=('-r',), timeout=300.0):
        with self.session() as s:
            return s.install(apk, extra=extra, timeout=timeout)

    def __call__(self, args):
        """``shell(args)`` as a plain callable, for ``agent_phone_channel.Session.start(shell)``."""
        return self.shell(args)
