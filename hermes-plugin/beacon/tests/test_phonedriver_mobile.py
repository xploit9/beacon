"""Receipt admission uses local SQLite and a simulated worker clock."""
import time
from types import SimpleNamespace

import pytest
from fastapi import HTTPException
from test_agent_phone_tools import load


def test_receipt_expiry_keeps_live_worker_during_admission(tmp_path, monkeypatch):
    mod = load('phonedriver_mobile', 'test_beacon_mobile_receipts')
    approvals = SimpleNamespace(holder_tag=lambda: 'live', _holder_alive=lambda _: True)
    store = mod.Tasks(tmp_path, 'owner', 'basic', 'default', approvals)
    monkeypatch.setattr(mod.threading.Thread, 'start', lambda _: None)
    stamp = int(time.time() * 1000)
    monkeypatch.setattr(mod.time, 'time', lambda: stamp / 1000)
    first = f'{stamp - mod.DAY_MS + 1000}-first'
    noop = lambda *args: None
    store.submit(first, 'one', noop, noop, 180)
    monkeypatch.setattr(mod.time, 'time', lambda: (stamp + 2000) / 1000)
    with pytest.raises(HTTPException) as busy:
        store.submit(f'{stamp + 2000}-second', 'two', noop, noop, 180)
    assert busy.value.status_code == 409
    assert not store.cancelled(first)
