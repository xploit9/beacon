import importlib.util
import sys
import types

from test_mobile import ROOT, harness, load_private


def test_a_plugin_update_reloads_sibling_modules_cached_from_the_old_install(harness):
    saved = {k: v for k, v in sys.modules.items() if k.startswith('cypher_')}
    stale = types.ModuleType('cypher_mobile')
    stale.__file__ = str(ROOT / 'mobile.py')
    stale._cypher_mtime = 1  # recorded by an older install
    stale.settings = lambda: {}
    try:
        sys.modules['cypher_mobile'] = stale
        plugin = load_private('__init__')
        assert plugin.mobile is not stale
        assert plugin.mobile.settings.__code__.co_argcount == 1  # settings(profile=None)
        assert plugin.mobile._cypher_mtime == (ROOT / 'mobile.py').stat().st_mtime_ns
        # A second load in the same process keeps the fresh modules.
        assert load_private('__init__').mobile is plugin.mobile
    finally:
        for k in [k for k in sys.modules if k.startswith('cypher_')]:
            del sys.modules[k]
        sys.modules.update(saved)


def test_reloading_native_loader_keeps_dashboard_notification_route_live(harness):
    _, client, *_ = harness
    saved = {k: v for k, v in sys.modules.items() if k.startswith('cypher_')}
    callbacks = sys.modules['cypher_callbacks']
    stale = types.ModuleType('cypher_mobile')
    stale.__file__ = str(ROOT / 'mobile.py')
    stale._cypher_mtime = 1
    try:
        sys.modules['cypher_mobile'] = stale
        plugin = load_private('__init__')

        assert plugin.mobile is not stale
        assert sys.modules['cypher_callbacks'] is callbacks
        assert client.get('/api/plugins/cypher/mobile/notifications/events').status_code == 200
    finally:
        for k in [k for k in sys.modules if k.startswith('cypher_')]:
            del sys.modules[k]
        sys.modules.update(saved)


def test_plugin_update_reloads_stale_gateway_tools_before_gateway_api(harness):
    saved={key:value for key,value in sys.modules.items() if key.startswith('cypher_')}
    stale=types.ModuleType('cypher_gateway_tools')
    stale.__file__=str(ROOT/'gateway_tools.py')
    stale._cypher_mtime=1
    stale.Gateway=saved['cypher_gateway_tools'].Gateway
    try:
        sys.modules['cypher_gateway_tools']=stale
        spec=importlib.util.spec_from_file_location('cypher_dashboard_gateway_reload_test',ROOT/'dashboard'/'plugin_api.py')
        plugin=importlib.util.module_from_spec(spec)
        spec.loader.exec_module(plugin)

        fresh=sys.modules['cypher_gateway_tools']
        assert fresh is not stale
        assert fresh._cypher_mtime==(ROOT/'gateway_tools.py').stat().st_mtime_ns
    finally:
        for key in [key for key in sys.modules if key.startswith('cypher_')]:
            del sys.modules[key]
        sys.modules.update(saved)
