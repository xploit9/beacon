import asyncio
from concurrent.futures import Future
from types import SimpleNamespace
import threading

import pytest
from fastapi import HTTPException

from test_mobile import harness


def test_abandoned_calls_do_not_exhaust_lookup_state(harness):
    api = harness[0]
    for number in range(1100):
        api.lookup_budget(f'abandoned-{number}', 'search')
    api.lookup_budget('active-call', 'search')
    assert len(api._feature_counts) == 1024
    for _ in range(59):
        api.lookup_budget('active-call', 'search')
    with pytest.raises(HTTPException) as error:
        api.lookup_budget('active-call', 'search')
    assert error.value.status_code == 429
    api.clear_lookup_counts('active-call')
    api.lookup_budget('active-call', 'search')


@pytest.mark.parametrize('failure', [None, RuntimeError('lookup failed')])
def test_worker_slot_returns_after_lookup_success_or_error(harness, monkeypatch, failure):
    api = harness[0]
    slots = threading.BoundedSemaphore(1)
    monkeypatch.setattr(api, '_feature_slots', slots)
    def lookup():
        if failure: raise failure
        return 'result'
    async def run():
        if failure:
            with pytest.raises(HTTPException): await api.feature(lookup)
        else:
            assert await api.feature(lookup) == 'result'
    asyncio.run(run())
    assert slots.acquire(blocking=False)
    slots.release()


def test_cancelled_queued_lookup_returns_its_slot(harness, monkeypatch):
    api = harness[0]
    slots, future = threading.BoundedSemaphore(1), Future()
    monkeypatch.setattr(api, '_feature_slots', slots)
    monkeypatch.setattr(api, '_feature_pool', SimpleNamespace(submit=lambda fn: future))
    async def run():
        with pytest.raises(HTTPException) as error:
            await api.feature(lambda: 'unused', timeout=0.01)
        assert error.value.status_code == 504
        assert future.cancelled()
    asyncio.run(run())
    assert slots.acquire(blocking=False)
    slots.release()


def test_submit_failure_returns_the_slot(harness, monkeypatch):
    api = harness[0]
    slots = threading.BoundedSemaphore(1)
    monkeypatch.setattr(api, '_feature_slots', slots)
    def fail(fn): raise RuntimeError('executor stopped')
    monkeypatch.setattr(api, '_feature_pool', SimpleNamespace(submit=fail))
    async def run():
        with pytest.raises(HTTPException) as error: await api.feature(lambda: 'unused')
        assert error.value.status_code == 503
    asyncio.run(run())
    assert slots.acquire(blocking=False)
    slots.release()


@pytest.mark.parametrize('cancel', [False, True])
def test_running_lookup_holds_the_slot_until_worker_exits(harness, monkeypatch, cancel):
    api = harness[0]
    slots = threading.BoundedSemaphore(1)
    started, finish = threading.Event(), threading.Event()
    monkeypatch.setattr(api, '_feature_slots', slots)
    def lookup():
        started.set()
        assert finish.wait(2)
        return 'result'
    async def run():
        task = asyncio.create_task(api.feature(lookup, timeout=1 if cancel else 0.05))
        assert await asyncio.to_thread(started.wait, 1)
        if cancel:
            task.cancel()
            with pytest.raises(asyncio.CancelledError): await task
        else:
            with pytest.raises(HTTPException) as error: await task
            assert error.value.status_code == 504
        assert not slots.acquire(blocking=False)
        finish.set()
        assert await asyncio.to_thread(slots.acquire, True, 1)
        slots.release()
    try: asyncio.run(run())
    finally: finish.set()
