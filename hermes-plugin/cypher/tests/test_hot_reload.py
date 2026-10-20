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
