"""Owner's Hermes, 3-4 Nov 2026: the gateway dropped Beacon three times right after an update ("Failed to load plugin 'beacon': load
timed out after 10s"). register() scanned every process on the PC for a stale dashboard, which took 10.9 s there (704 processes), so
the gateway ran without Beacon's tools and hooks until its next restart. The scan now runs beside the load and reads only the
processes that can be a dashboard."""
import importlib.util
import time
from pathlib import Path
from types import SimpleNamespace

from test_mobile import harness, load_private  # noqa: F401  (harness is a fixture)

ROOT = Path(__file__).resolve().parents[1]


def _restart_module():
    spec = importlib.util.spec_from_file_location('beacon_dashboard_restart_load_test', ROOT / 'dashboard_restart.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_register_returns_at_once_while_the_after_load_steps_still_run(harness, monkeypatch):
    plugin = load_private('__init__')
    slow = []
    monkeypatch.setattr(plugin, '_record_gateway_plugin_version', lambda: slow.append('version'))
    monkeypatch.setattr(plugin, '_restart_stale_dashboard', lambda: (time.sleep(2), slow.append('dashboard')))
    tools = []
    started = time.monotonic()
    plugin.register(SimpleNamespace(register_tool=lambda **kwargs: tools.append(kwargs['name']), register_hook=lambda *args: None))
    assert time.monotonic() - started < 1.5, 'register() must not wait for the dashboard check'
    assert 'beacon_call_user' in tools
    plugin._after_load_thread.join(10)
    assert slow == ['version', 'dashboard']


def test_the_process_scan_reads_details_only_for_python_and_hermes_processes():
    restart = _restart_module()
    host = restart.Host.__new__(restart.Host)
    names = {11: 'chrome.exe', 12: 'python.exe', 13: 'qemu-system-x86_64.exe', 14: 'hermes.exe', 15: 'Python3.12.exe'}

    class Process:
        def __init__(self, pid): self.pid, self.info = pid, {'name': names[pid]}

    class Error(Exception):
        pass

    host.ps = SimpleNamespace(process_iter=lambda attrs: [Process(pid) for pid in names], Error=Error)
    read = []
    host.info = lambda pid: read.append(pid) or {'pid': pid, 'name': names[pid]}
    rows = host.processes()
    assert read == [12, 14, 15]
    assert [row['pid'] for row in rows] == [12, 14, 15]
