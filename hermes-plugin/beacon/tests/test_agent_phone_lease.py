"""The agent phone's lease (EMU-DESIGN §4.3): Bot Desktop's rules, a real cross-process lock, timeouts kept in the file."""
import importlib.util
import json
import os
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]


def _load():
    spec = importlib.util.spec_from_file_location('beacon_agent_phone_lease_under_test', ROOT / 'agent_phone_lease.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


lease_mod = _load()


class Clock:
    def __init__(self, now=1_000_000.0):
        self.now = now

    def __call__(self):
        return self.now


@pytest.fixture
def phone(tmp_path):
    clock = Clock()
    return lease_mod.PhoneLease(tmp_path, clock=clock), clock, tmp_path


def test_agent_holds_a_fresh_phone(phone):
    lease, _, folder = phone
    current = lease.get()
    assert current.holder == lease_mod.AGENT and current.epoch == 0
    assert not (folder / 'run' / 'lease.json').exists()  # nothing written until something changes
    assert lease.assert_agent_may_act().epoch == 0
    assert not lease.view_open()


def test_acquire_bumps_epoch_and_the_same_viewer_again_does_not(phone):
    lease, clock, _ = phone
    first = lease.acquire('v-a', reason='setup')
    assert first.holder == lease_mod.HUMAN and first.viewer_id == 'v-a' and first.epoch == 1
    clock.now += 1
    assert lease.acquire('v-a').epoch == 1
    assert lease.view_open() and lease.holds('v-a') and not lease.holds('v-b')


def test_another_viewer_evicts_the_first(phone):
    lease, _, _ = phone
    lease.acquire('v-a')
    taken = lease.acquire('v-b')
    assert taken.viewer_id == 'v-b' and taken.epoch == 2
    assert not lease.holds('v-a')


def test_release_only_by_the_holder_and_only_for_the_epoch_judged(phone):
    lease, _, _ = phone
    held = lease.acquire('v-a')
    assert lease.release('v-b').holder == lease_mod.HUMAN
    assert lease.release('v-a', epoch=held.epoch + 5).holder == lease_mod.HUMAN
    back = lease.release('v-a', epoch=held.epoch)
    assert back.holder == lease_mod.AGENT and back.epoch == held.epoch + 1
    # already the agent's: no new epoch, so an agent action admitted meanwhile stays valid
    assert lease.release().epoch == back.epoch


def test_agent_is_refused_while_the_owner_holds_and_old_epochs_are_void(phone):
    lease, _, _ = phone
    admitted = lease.assert_agent_may_act()
    lease.acquire('v-a')
    with pytest.raises(lease_mod.OwnerHasPhone) as refused:
        lease.assert_agent_may_act()
    assert 'The owner is setting up your phone' in str(refused.value)
    assert not lease.still(admitted.epoch)
    lease.release('v-a')
    assert not lease.still(admitted.epoch)  # control changed under the action, even though the agent holds again
    assert lease.still(lease.assert_agent_may_act().epoch)


@pytest.mark.parametrize('content', ['{not json', '{"holder": "robot", "epoch": 1}', '[1, 2]', '{"holder": "agent", "epoch": "1"}',
                                     '{"holder": "agent", "epoch": 1, "bogus": 2}'])
def test_unreadable_or_corrupt_file_fails_closed(phone, content):
    lease, _, folder = phone
    (folder / 'run').mkdir(parents=True, exist_ok=True)
    (folder / 'run' / 'lease.json').write_text(content, encoding='utf-8')
    current = lease.get()
    if content.startswith('{"holder": "agent", "epoch": 1, "bogus"'):
        # unknown fields are dropped; a well-formed agent lease is still the agent's (positive control for the parser)
        assert current.holder == lease_mod.AGENT
        return
    assert current.holder == lease_mod.HUMAN and current.viewer_id == 'unreadable-lease'
    with pytest.raises(lease_mod.OwnerHasPhone):
        lease.assert_agent_may_act()
    # a corrupt lease never times out on its own; a hand-back without a viewer repairs it
    assert lease.release().holder == lease_mod.AGENT
    assert json.loads((folder / 'run' / 'lease.json').read_text(encoding='utf-8'))['holder'] == 'agent'


def test_lease_ends_five_minutes_after_the_view_went_away(phone):
    lease, clock, _ = phone
    lease.acquire('v-a')
    clock.now += 60
    lease.went_away('v-a')
    clock.now += lease_mod.AWAY_SECONDS - 1
    assert lease.holds('v-a')  # background or a dropped socket keeps it for 5 min
    clock.now += 2
    current = lease.get()
    assert current.holder == lease_mod.AGENT and current.epoch == 2


def test_coming_back_inside_the_window_keeps_the_lease_and_its_epoch(phone):
    lease, clock, _ = phone
    held = lease.acquire('v-a')
    lease.went_away('v-a')
    clock.now += lease_mod.AWAY_SECONDS - 10
    back = lease.acquire('v-a')
    assert back.epoch == held.epoch and back.away is None
    clock.now += 60
    assert lease.holds('v-a')


def test_lease_ends_after_fifteen_minutes_without_input(phone):
    lease, clock, _ = phone
    lease.acquire('v-a')
    clock.now += lease_mod.IDLE_SECONDS - 1
    assert lease.holds('v-a')
    clock.now += 2
    assert lease.get().holder == lease_mod.AGENT


def test_input_keeps_the_lease_and_is_written_at_most_every_ten_seconds(phone):
    lease, clock, folder = phone
    lease.acquire('v-a')
    path = folder / 'run' / 'lease.json'
    clock.now += lease_mod.IDLE_SECONDS - 5
    holds, noted = lease.note_input('v-a')
    assert holds and noted == clock.now
    before = path.read_bytes()
    clock.now += 3
    assert lease.note_input('v-a', noted) == (True, noted)
    assert path.read_bytes() == before  # throttled: no write
    clock.now += lease_mod.INPUT_NOTE_SECONDS
    holds, again = lease.note_input('v-a', noted)
    assert holds and again == clock.now and path.read_bytes() != before
    clock.now += lease_mod.IDLE_SECONDS - 1
    assert lease.holds('v-a')
    assert lease.note_input('v-b') == (False, None)


def test_listeners_hear_transitions_but_not_quiet_input_notes(phone):
    lease, clock, _ = phone
    heard = []
    off = lease.on_change(lambda value: heard.append((value.holder, value.epoch)))
    lease.acquire('v-a')
    clock.now += 30
    lease.note_input('v-a')
    lease.went_away('v-a')
    clock.now += lease_mod.AWAY_SECONDS + 1
    lease.get()
    off()
    lease.acquire('v-b')
    assert heard == [('human', 1), ('agent', 2)]


def test_public_view_never_shows_the_viewer_id(phone):
    lease, _, _ = phone
    held = lease.acquire(lease_mod.viewer_id('owner', 'basic', 'device-1'))
    mine = lease_mod.public_view(held, lease_mod.viewer_id('owner', 'basic', 'device-1'))
    other = lease_mod.public_view(held, lease_mod.viewer_id('owner', 'basic', 'device-2'))
    assert mine == {'holder': 'human', 'you': True, 'epoch': 1, 'away': False}
    assert other['you'] is False
    assert held.viewer_id not in json.dumps(mine)


def test_lease_file_is_owner_only_on_posix(phone):
    lease, _, folder = phone
    lease.acquire('v-a')
    if os.name != 'nt':
        assert (folder / 'run' / 'lease.json').stat().st_mode & 0o077 == 0


def test_two_processes_contend_on_the_real_lock_without_a_lost_update(tmp_path):
    script = textwrap.dedent('''
        import importlib.util, sys
        spec = importlib.util.spec_from_file_location('lease_child', sys.argv[1])
        mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)
        lease = mod.PhoneLease(sys.argv[2])
        for _ in range(int(sys.argv[3])):
            lease._transition(lambda value, now: 'tick')
        print('child done')
    ''')
    runner = tmp_path / 'child.py'
    runner.write_text(script, encoding='utf-8')
    folder = tmp_path / 'phone'
    count = 150
    children = [subprocess.Popen([sys.executable, '-I', str(runner), str(ROOT / 'agent_phone_lease.py'), str(folder), str(count)],
                                 stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True) for _ in range(3)]
    outputs = [child.communicate(timeout=120) for child in children]
    assert all(child.returncode == 0 for child in children), outputs
    assert lease_mod.PhoneLease(folder).get().epoch == 3 * count
