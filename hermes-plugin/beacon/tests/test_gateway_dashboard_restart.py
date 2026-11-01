"""After a plugin update the gateway starts with the new plugin and hands a dashboard still running the old one to the restart helper."""
from contextlib import nullcontext
import importlib.util
from pathlib import Path
import sys
import threading
from types import ModuleType, SimpleNamespace

from test_mobile import harness

ROOT = Path(__file__).resolve().parents[1]


def load_plugin(tmp_path, monkeypatch):
    server = SimpleNamespace(_profile_home=lambda profile: tmp_path, _profile_db=lambda options: nullcontext(None),
                             _sessions_lock=threading.RLock(), _sessions={})
    tui_gateway = ModuleType('tui_gateway')
    tui_gateway.server = server
    tui_gateway.server_requests = SimpleNamespace(open_requests=lambda session_id: [])
    constants = ModuleType('hermes_constants')
    constants.get_hermes_home = lambda: tmp_path
    monkeypatch.setitem(sys.modules, 'tui_gateway', tui_gateway)
    monkeypatch.setitem(sys.modules, 'hermes_constants', constants)
    spec = importlib.util.spec_from_file_location('beacon_test_gateway_restart', ROOT / '__init__.py')
    plugin = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(plugin)
    return plugin


def fake_restarter(monkeypatch):
    calls = []
    module = ModuleType('beacon_dashboard_restart')
    module.restart_stale = lambda home, written, call_open=lambda: False: calls.append((home, written, call_open())) or ['x' * 32]
    monkeypatch.setitem(sys.modules, 'beacon_dashboard_restart', module)
    return calls


def test_only_the_gateway_hands_over_a_stale_dashboard_with_the_plugin_files_time(harness, monkeypatch):
    api, _, _, tmp_path, _ = harness
    plugin = load_plugin(tmp_path, monkeypatch)
    calls = fake_restarter(monkeypatch)
    monkeypatch.setattr(plugin, '_is_gateway_process', lambda: False)
    plugin._restart_stale_dashboard()
    assert calls == [], 'a dashboard or CLI process loading the plugin never restarts anything'
    monkeypatch.setattr(plugin, '_is_gateway_process', lambda: True)
    plugin._restart_stale_dashboard()
    written = max((ROOT / name).stat().st_mtime for name in ('plugin.yaml', 'dashboard/plugin_api.py'))
    assert calls == [(plugin.mobile.home(), written, False)]


def test_an_open_call_is_reported_to_the_restart_and_a_failure_never_blocks_loading(harness, monkeypatch):
    api, _, _, tmp_path, _ = harness
    plugin = load_plugin(tmp_path, monkeypatch)
    calls = fake_restarter(monkeypatch)
    monkeypatch.setattr(plugin, '_is_gateway_process', lambda: True)
    with plugin.mobile.connect() as conn:
        conn.execute('INSERT INTO calls(id,owner,profile,conversation,created,closed,status,voice_config,callback_context) VALUES(?,?,?,?,?,?,?,?,?)',
                     ('call-open', 'owner', 'default', 'stored', __import__('time').time(), 0, 'live', '{}', '{}'))
    plugin._restart_stale_dashboard()
    assert calls and calls[-1][2] is True, 'a live call is seen, so the helper is never started during it'
    sys.modules['beacon_dashboard_restart'].restart_stale = lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError('boom'))
    plugin._restart_stale_dashboard()  # never raises
