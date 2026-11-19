"""LINK-121: a wait for mobile.LOCK happens on a worker thread, never on the dashboard's event loop.

mobile.LOCK is one process-wide lock: every mobile.connect() takes it, and a writer holds it across BEGIN IMMEDIATE, which SQLite lets
wait up to 15 s while another process (the gateway, mid-turn) writes. identity() reads the settings under it. A route that ran identity()
on the loop stopped every request of the dashboard (the relay sockets, /updates, hand-overs) for as long as the lock was held; the call
path already ran it on a thread (identity_async). Each test holds the lock from another thread for HOLD seconds and measures the loop."""
import asyncio
import sys
import threading
import time

import httpx
import pytest
from fastapi import HTTPException

from test_calls_v3 import BASE, V3, handover  # noqa: F401  (a fixture: a v3 call with its principal and a stub gateway)
from test_mobile import harness  # noqa: F401  (a fixture)

HOLD = 0.6
PREFIX = '/api/plugins/beacon'
ROUTES = [
    ('GET', '/mobile/health', None),
    ('GET', '/mobile/calls', None),
    ('POST', '/mobile/sessions/chat-1/thread', {}),
    ('GET', '/mobile/files/text?path=notes.txt', None),
    ('POST', '/mobile/files/text', {}),
    ('POST', '/mobile/files/create', {}),
    ('GET', '/memory/documents', None),
    ('PUT', '/memory/documents', {}),
    ('GET', '/mobile/skills', None),
    ('GET', '/mobile/skills/content?name=example', None),
    ('PUT', '/mobile/skills/content', {}),
    ('POST', '/admin/plugin/restart', None),
    ('GET', '/admin/plugin/restart/status?pid=1', None),
    ('POST', '/admin/dashboard/restart', None),
    ('GET', '/admin/dashboard/restart/status?id=' + '0' * 32, None),
    ('POST', '/admin/profile-plugin/enable', {'profile': 'default'}),
    ('GET', '/admin/model-capabilities?model=example-model', None),
    ('GET', '/admin/bots', None),
    ('GET', '/mobile/bot-mode', None),
    ('GET', '/mobile/bot-mode/space/room-1', None),
    ('POST', '/mobile/bot-mode/space/room-1/delete', {}),
    ('POST', '/mobile/bot-mode/chat', {'bot': 'default'}),
]


def lock_held_settings(api, reached):
    """The real settings() reads the owner claim under mobile.LOCK; this one waits for the lock the same way, then refuses, so nothing
    after identity() runs (a restart route must never restart anything here)."""
    def settings(profile=None):
        with api.mobile.LOCK:
            reached.append(threading.current_thread() is threading.main_thread())
        raise HTTPException(503, 'Settings read after the lock')
    return settings


async def longest_pause(seconds):
    """The longest time this loop did not run a 10 ms timer, over `seconds`."""
    longest, last = 0.0, time.monotonic()
    end = last + seconds
    while time.monotonic() < end:
        await asyncio.sleep(0.01)
        now = time.monotonic()
        longest, last = max(longest, now - last), now
    return longest


def hold_lock(api):
    held = threading.Event()

    def run():
        with api.mobile.LOCK:
            held.set()
            time.sleep(HOLD)
    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    assert held.wait(5)
    return thread


@pytest.mark.parametrize('method,path,body', ROUTES, ids=[method + ' ' + path.split('?')[0] for method, path, body in ROUTES])
def test_a_route_waiting_for_the_store_lock_leaves_the_loop_running(harness, monkeypatch, method, path, body):
    api, _, _, _, app = harness
    reached = []
    monkeypatch.setattr(api.mobile, 'settings', lock_held_settings(api, reached))

    async def scenario():
        holder = hold_lock(api)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url='http://testserver', headers={'x-fixture-owner': 'owner'}) as client:
            request = asyncio.ensure_future(client.request(method, PREFIX + path, json=body))
            pause = await longest_pause(HOLD + 0.2)
            response = await asyncio.wait_for(request, 10)
        holder.join(5)
        return pause, response

    pause, response = asyncio.run(scenario())
    assert reached, 'the route never asked for the settings (%s): the check proved nothing' % response.status_code
    assert response.status_code == 503, response.text
    assert pause < HOLD / 2, 'the dashboard loop stopped %.2f s while %s %s waited for mobile.LOCK' % (pause, method, path)


def test_the_watcher_reads_the_settings_off_the_loop(harness, monkeypatch):
    api, _, _, _, _ = harness
    reached = []
    monkeypatch.setattr(api.mobile, 'settings', lock_held_settings(api, reached))
    monkeypatch.setattr(api, 'check_hermes', lambda: None)

    async def scenario():
        holder = hold_lock(api)
        await api.start_watcher()
        try:
            pause = await longest_pause(HOLD + 0.2)
        finally:
            await api.stop_watcher()
        holder.join(5)
        return pause

    pause = asyncio.run(scenario())
    assert reached
    assert pause < HOLD / 2, 'the dashboard loop stopped %.2f s while the watcher waited for mobile.LOCK' % pause

# REVIEW-E5 P3-4: the three other moves. The settings above refuse before any of them runs, so each case here reaches its own step with
# the lock held at that moment, and fails if that step goes back on the loop.

def hold_lock_on(api, go):
    """Holds mobile.LOCK for HOLD seconds once `go` is set; `held` is set while it has the lock."""
    held = threading.Event()

    def run():
        go.wait(10)
        with api.mobile.LOCK:
            held.set()
            time.sleep(HOLD)
    thread = threading.Thread(target=run, daemon=True)
    thread.start()
    return thread, held


@pytest.mark.parametrize('path', ['/admin/plugin/restart', '/admin/dashboard/restart'])
def test_a_restart_checks_for_a_live_call_off_the_loop(harness, monkeypatch, path):
    api, _, _, _, app = harness
    checked = []
    # The check finds a call, so the route refuses (409) and nothing restarts.
    monkeypatch.setattr(api.mobile, 'active_calls', lambda conn, owner: checked.append(owner) or 1)

    async def scenario():
        holder = hold_lock(api)
        transport = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport, base_url='http://testserver', headers={'x-fixture-owner': 'owner'}) as client:
            request = asyncio.ensure_future(client.post(PREFIX + path))
            pause = await longest_pause(HOLD + 0.2)
            response = await asyncio.wait_for(request, 10)
        holder.join(5)
        return pause, response

    pause, response = asyncio.run(scenario())
    assert checked == ['owner'], 'the active-call check never ran (%s): the case proved nothing' % response.status_code
    assert response.status_code == 409, response.text
    assert pause < HOLD / 2, 'the dashboard loop stopped %.2f s while %s checked for a live call under mobile.LOCK' % (pause, path)


def test_an_uncertain_hand_over_reads_its_job_off_the_loop(harness, handover, monkeypatch):
    api, _, _, _, app = harness
    transport = sys.modules['beacon_canonical_transport']
    go = threading.Event()
    holder, held = hold_lock_on(api, go)

    async def unconfirmed(*args, **kwargs):
        # The submit went out and its answer was lost: the route then looks the job up, with the lock taken by another thread.
        go.set()
        assert await asyncio.to_thread(held.wait, 5)
        raise transport.HandoverUnconfirmed()
    monkeypatch.setattr(transport, 'canonical_handover', unconfirmed)

    async def scenario():
        transport_ = httpx.ASGITransport(app=app)
        async with httpx.AsyncClient(transport=transport_, base_url='http://testserver') as client:
            request = asyncio.ensure_future(client.post('%s/calls/%s/handover' % (BASE, handover.call_id), headers=V3,
                                                        json={'request_id': 'handover-lock', 'text': 'A synthetic request.'}))
            pause = await longest_pause(HOLD + 0.4)
            response = await asyncio.wait_for(request, 10)
        holder.join(5)
        return pause, response

    pause, response = asyncio.run(scenario())
    assert held.is_set(), 'the hand-over never reached its uncertain answer: the case proved nothing'
    assert response.status_code == 200 and response.json()['status'] == 'uncertain', response.text
    assert pause < HOLD / 2, 'the dashboard loop stopped %.2f s while an uncertain hand-over read its job under mobile.LOCK' % pause


def test_the_watcher_reads_the_jobs_version_off_the_loop(harness, monkeypatch):
    api, _, cfg, _, _ = harness
    read = []
    real = api.proactive._meta_get

    def meta_get(mobile, key):
        read.append(key)
        return real(mobile, key)
    monkeypatch.setattr(api.proactive, '_meta_get', meta_get)
    # One pass that reaches the jobs-version read and nothing else of consequence.
    for name in ('watch', 'push', 'sweep_unended_calls', 'flush_held_posts', '_warm_profile'):
        monkeypatch.setattr(api, name, lambda *args: None)
    monkeypatch.setattr(api.voice_context, 'prefetch_tools', lambda profile: False)
    monkeypatch.setattr(api.mobile, 'phone_requests_sweep', lambda: None)
    monkeypatch.setattr(api, 'check_hermes', lambda: None)
    monkeypatch.setattr(api, '_profiles_on_next', float('inf'))
    monkeypatch.setattr(api, '_release_next', float('inf'))
    monkeypatch.setattr(api, '_jobs_warming', set())
    monkeypatch.setattr(api, '_jobs_retry_after', {})

    async def scenario():
        holder = hold_lock(api)
        await api.start_watcher()
        try:
            pause = await longest_pause(HOLD + 0.2)
        finally:
            await api.stop_watcher()
        holder.join(5)
        return pause

    pause = asyncio.run(scenario())
    assert read and read[0].endswith('jobs-version'), 'the watcher never read the jobs version: the case proved nothing'
    assert pause < HOLD / 2, 'the dashboard loop stopped %.2f s while the watcher read the jobs version under mobile.LOCK' % pause
