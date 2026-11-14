"""Setup shared by every test module: Hermes gets a private home and a quiet process, and tests leave neither changed."""
import os
import sys
import tempfile

import pytest

# Test modules import Hermes at module level (test_canonical_same_chat, test_native_owner_compat, test_mcp_schema_cache), and pytest
# imports them during collection, before any fixture runs. So the private home and the quiet process are set here, at import time, too:
# the fixture below only moves them per test. The folder lives as long as the run and is removed with it.
_COLLECTION_HOME = tempfile.TemporaryDirectory(prefix='beacon-tests-hermes-home-', ignore_cleanup_errors=True)
os.environ['HERMES_HOME'] = _COLLECTION_HOME.name
os.environ['HERMES_DISABLE_LAZY_INSTALLS'] = '1'


@pytest.fixture(autouse=True)
def hermes_has_a_private_home_and_a_quiet_process(tmp_path_factory, monkeypatch):
    """The test process is not a Hermes entry point and must not touch the owner's Hermes.

    1. ``HERMES_DISABLE_LAZY_INSTALLS`` (Hermes' own switch for tests, ``hermes_cli/venv_sync.py:456``). ``agent/managed_worker.py:10``
       runs ``import hermes_bootstrap``, which ends in ``prepare_launch`` (``hermes_bootstrap.py:588``). In a process Hermes did
       not start, that builds a runtime, installs dependencies and re-runs pytest in it (``hermes_bootstrap.py:598``): minutes per
       test, then ``RelaunchExit``. ``execute_admission`` (``gateway/session_ingress.py:289``) and ``managed_policy`` (used by
       ``voice_continuation.transfer_available``) reach that import. Inside the dashboard and gateway ``hermes_bootstrap`` is
       imported first (``hermes_cli/main.py:16``, ``gateway/run.py:8``), so there it does nothing.
    2. ``HERMES_HOME`` points at an empty temporary folder. ``import model_tools`` (``model_tools.py:164``) reaches
       ``load_hermes_dotenv`` (``hermes_cli/plugins.py:1311``) for the ambient home, which put the owner's real ``.env``
       (61 variables, 12 of them credentials) into ``os.environ`` for the rest of the run and for every child process.
       A test that needs its own home still sets one; its value replaces this one.
    3. After each test ``os.environ`` is put back, and Hermes is put back to a single-profile process. Entering the scope of
       another profile (``hermes_cli/web_server_profiles.py:310``) calls ``set_multiplex_active(True)``
       (``tui_gateway/launch_profile_policy.py:51``) and Hermes never undoes it, so every later test saw routed-profile
       connection keys (``tools/mcp_tool_scope.py:_server_key``)."""
    monkeypatch.setenv('HERMES_DISABLE_LAZY_INSTALLS', '1')
    monkeypatch.setenv('HERMES_HOME', str(tmp_path_factory.mktemp('private-hermes-home')))
    environment = dict(os.environ)
    scope = sys.modules.get('agent.secret_scope')
    multiplexed = bool(scope and scope.is_multiplex_active())
    yield
    for name in [name for name in os.environ if name not in environment]:
        del os.environ[name]
    for name, value in environment.items():
        if os.environ.get(name) != value:
            os.environ[name] = value
    scope = sys.modules.get('agent.secret_scope')
    if scope is not None and not multiplexed and scope.is_multiplex_active():
        scope.set_multiplex_active(False)
