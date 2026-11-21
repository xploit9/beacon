"""Who drives the agent phone: the agent (default) or exactly one human viewer (EMU-DESIGN §4.3).

Mirrors Hermes' Bot Desktop lease (``tools/bot_desktop/lease.py``): the truth is on disk, ``epoch`` goes up on every change so an
action admitted under one lease can tell control changed under it, an unreadable file fails closed (a human holds), and the
holder's viewer id stays private (``public_view`` shows a short hash). Three differences, each because the agent phone needs it:

- **A real cross-process lock on every OS.** The dashboard (setup view) and the gateway (the agent's tool) share this file on
  Windows too; Hermes' lock is a no-op there. ``msvcrt.locking`` on Windows, ``fcntl.flock`` elsewhere, on ``run/lease.lock``.
- **The timeouts live in the file.** A human lease ends 5 min after its view went away (socket gone or the app in the background:
  ``away``) or 15 min without input (``last_input``). Whichever process reads the lease next decides it under the lock, so a
  dashboard restart never leaves the agent fenced out.
- **Input notes are throttled.** A viewer's input refreshes ``last_input`` at most every ``INPUT_NOTE_SECONDS``; nothing else is
  written per touch.

The file lives in the phone folder's ``run/`` (owner-only, §2.1). It holds no secret: the viewer id is a hash of the owner and the
device, never a ticket or a token.
"""
import contextlib
import hashlib
import json
import os
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path

AGENT, HUMAN = 'agent', 'human'
AWAY_SECONDS = 5 * 60  # §4.3: the lease ends 5 min after the socket dies; a backgrounded view keeps it as long
IDLE_SECONDS = 15 * 60  # §4.3: or 15 min without input
INPUT_NOTE_SECONDS = 10.0  # a viewer's input is written at most this often
LOCK_SECONDS = 10.0
OWNER_HOLDS = 'The owner is setting up your phone. Actions and captures wait until they hand it back.'


class OwnerHasPhone(RuntimeError):
    """The agent may not act or capture: the owner holds the phone (§4.3)."""


@dataclass
class Lease:
    holder: str = AGENT
    viewer_id: str = None
    since: float = field(default_factory=time.time)
    reason: str = ''
    epoch: int = 0
    last_input: float = None
    away: float = None  # when the holder's view went away (socket gone or app in the background); None while it is there

    def as_dict(self):
        return asdict(self)


def viewer_id(owner, provider, device):
    """The lease's name for one human viewer: the owner on one linked phone. Stable across re-tickets, so a view that dropped and
    came back takes its own lease again without a new epoch."""
    raw = '\0'.join(str(part) for part in (owner, provider, device))
    return 'v-' + hashlib.sha256(raw.encode('utf-8')).hexdigest()[:32]


def public_view(lease, viewer=None):
    """The lease as a client may see it: who holds it, whether it is ``viewer``, the epoch. No viewer id."""
    return {'holder': lease.holder, 'you': bool(viewer and lease.holder == HUMAN and lease.viewer_id == viewer),
            'epoch': lease.epoch, 'away': lease.away is not None}


class _FileLock:
    """An exclusive lock on one file across processes and threads (each use opens its own handle)."""

    def __init__(self, path, seconds=LOCK_SECONDS):
        self.path, self.seconds = Path(path), seconds
        self._fd = None

    def __enter__(self):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._fd = os.open(self.path, os.O_RDWR | os.O_CREAT | getattr(os, 'O_BINARY', 0), 0o600)
        deadline = time.monotonic() + self.seconds
        try:
            while True:
                try:
                    self._try_lock()
                    return self
                except OSError:
                    if time.monotonic() >= deadline:
                        raise OSError('The phone lease is busy.') from None
                    time.sleep(0.01)
        except BaseException:
            os.close(self._fd)
            self._fd = None
            raise

    def _try_lock(self):
        if os.name == 'nt':
            import msvcrt
            os.lseek(self._fd, 0, os.SEEK_SET)
            msvcrt.locking(self._fd, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)

    def __exit__(self, *exc):
        try:
            if os.name == 'nt':
                import msvcrt
                os.lseek(self._fd, 0, os.SEEK_SET)
                with contextlib.suppress(OSError):
                    msvcrt.locking(self._fd, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self._fd, fcntl.LOCK_UN)
        finally:
            os.close(self._fd)
            self._fd = None


def _corrupt(why):
    return Lease(holder=HUMAN, viewer_id='unreadable-lease', reason=why)


class PhoneLease:
    """The lease of one phone folder: ``run/lease.json`` under ``run/lease.lock``. Every read happens under the lock and applies
    the timeouts first; ``listeners`` (in this process) hear every transition this process makes."""

    def __init__(self, folder, clock=time.time, away_seconds=AWAY_SECONDS, idle_seconds=IDLE_SECONDS):
        run = Path(folder) / 'run'
        self.path, self.lock_path = run / 'lease.json', run / 'lease.lock'
        self.clock = clock
        self.away_seconds, self.idle_seconds = away_seconds, idle_seconds
        self._local = threading.Lock()
        self._listeners = []

    # -- disk --
    def _read(self):
        try:
            raw = self.path.read_text(encoding='utf-8-sig')
        except FileNotFoundError:
            return Lease(since=self.clock())
        except OSError:
            return _corrupt('lease file unreadable')
        try:
            data = json.loads(raw)
        except ValueError:
            return _corrupt('lease file corrupt')
        if not isinstance(data, dict) or data.get('holder') not in (AGENT, HUMAN) or type(data.get('epoch', 0)) is not int:
            return _corrupt('lease file corrupt')
        try:
            return Lease(**{k: v for k, v in data.items() if k in Lease.__dataclass_fields__})
        except TypeError:
            return _corrupt('lease file corrupt')

    def _write(self, lease):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + '.tmp')
        fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | getattr(os, 'O_BINARY', 0), 0o600)
        try:
            os.write(fd, json.dumps(lease.as_dict()).encode('utf-8'))
        finally:
            os.close(fd)
        for attempt in range(50):  # Windows: a reader outside the lock holding the file open refuses the replace for a moment
            try:
                os.replace(tmp, self.path)
                return
            except PermissionError:
                if attempt == 49:
                    raise
                time.sleep(0.01)

    def _expired(self, lease, now):
        if lease.holder != HUMAN or lease.viewer_id == 'unreadable-lease':
            return False
        if lease.away is not None and now - lease.away >= self.away_seconds:
            return True
        return now - (lease.last_input or lease.since) >= self.idle_seconds

    def _transition(self, mutate):
        """Reads under the lock, ends a timed-out human lease, applies ``mutate`` (True = changed) and writes once."""
        changed = []
        with self._local, _FileLock(self.lock_path):
            lease = self._read()
            now = self.clock()
            if self._expired(lease, now):
                lease.holder, lease.viewer_id, lease.since, lease.reason = AGENT, None, now, ''
                lease.last_input = lease.away = None
                lease.epoch += 1
                changed.append('expired')
            outcome = mutate(lease, now) if mutate else None
            if outcome:
                if outcome != 'quiet':
                    lease.epoch += 1
                changed.append(outcome)
            if changed:
                self._write(lease)
        if any(c != 'quiet' for c in changed):
            for listener in list(self._listeners):
                with contextlib.suppress(Exception):  # health: allow BLE001 -- a broken listener must not wedge the hand-over
                    listener(lease)
        return lease

    # -- what callers use --
    def get(self):
        """The lease as of now, after the timeouts."""
        return self._transition(None)

    def interrupt(self):
        """Fence in-flight input immediately when the owner requests a stop, even with an agent-held lease."""
        return self._transition(lambda lease, now: 'stopping')

    def on_change(self, listener):
        self._listeners.append(listener)
        return lambda: self._listeners.remove(listener) if listener in self._listeners else None

    def acquire(self, viewer, reason=''):
        """``viewer`` takes the phone. Last writer wins: another viewer is evicted (its view sees the epoch change)."""
        def mutate(lease, now):
            if lease.holder == HUMAN and lease.viewer_id == viewer:
                if lease.away is not None or now - (lease.last_input or 0) >= INPUT_NOTE_SECONDS:
                    lease.away, lease.last_input = None, now
                    return 'quiet'  # the same viewer back: no new epoch, so its own actions stay valid
                return None
            lease.holder, lease.viewer_id, lease.since, lease.reason = HUMAN, viewer, now, reason or ''
            lease.last_input, lease.away = now, None
            return 'acquired'
        return self._transition(mutate)

    def release(self, viewer=None, epoch=None):
        """Back to the agent. With ``viewer``, only that holder may release; with ``epoch``, only that generation."""
        def mutate(lease, now):
            if epoch is not None and lease.epoch != epoch:
                return None
            if lease.holder == AGENT:
                return None
            if viewer is not None and lease.viewer_id != viewer:
                return None
            lease.holder, lease.viewer_id, lease.since, lease.reason = AGENT, None, now, ''
            lease.last_input = lease.away = None
            return 'released'
        return self._transition(mutate)

    def note_input(self, viewer, last_note=None):
        """``viewer`` sent input. Returns (holds, written_at): whether it still holds, and when ``last_input`` was last written.
        Writes only when ``last_note`` is older than ``INPUT_NOTE_SECONDS`` (callers keep it), so a finger drag costs no I/O."""
        now = self.clock()
        if last_note is not None and now - last_note < INPUT_NOTE_SECONDS:
            return True, last_note

        def mutate(lease, now):
            if lease.holder == HUMAN and lease.viewer_id == viewer:
                lease.last_input, lease.away = now, None
                return 'quiet'
            return None
        lease = self._transition(mutate)
        holds = lease.holder == HUMAN and lease.viewer_id == viewer
        return holds, (now if holds else None)

    def went_away(self, viewer):
        """``viewer``'s view is gone (socket closed) or in the background: frames stop, the lease is kept ``away_seconds``."""
        def mutate(lease, now):
            if lease.holder == HUMAN and lease.viewer_id == viewer and lease.away is None:
                lease.away = now
                return 'quiet'
            return None
        return self._transition(mutate)

    def holds(self, viewer):
        lease = self.get()
        return lease.holder == HUMAN and lease.viewer_id == viewer

    def assert_agent_may_act(self):
        """The lease as of now, or ``OwnerHasPhone``. Keep the returned ``epoch``; ``still(epoch)`` after the action tells
        whether control changed under it (§4.3: an action admitted under an old epoch is void)."""
        lease = self.get()
        if lease.holder == HUMAN:
            raise OwnerHasPhone(OWNER_HOLDS)
        return lease

    def still(self, epoch):
        lease = self.get()
        return lease.holder == AGENT and lease.epoch == epoch

    def view_open(self):
        """For L1b's ``PhoneHost(view_open=…)``: a setup view may be using the live-view frame file while a human holds."""
        return self.get().holder == HUMAN


def task_slot(folder):
    """One visual loop at a time on this virtual phone, including across gateway processes."""
    return _FileLock(Path(folder) / 'run' / 'phonedriver.lock', seconds=0)


def read(folder):
    """``{"holder": "agent" | "human", "epoch": int}`` for the phone folder ``folder``, after the timeouts (L3's seam,
    approvals-v1 §7). An unreadable lease reads as held by a human."""
    lease = PhoneLease(folder).get()
    return {'holder': lease.holder, 'epoch': lease.epoch}
