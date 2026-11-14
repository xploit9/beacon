"""Collection already runs on a private Hermes home: a module that imports Hermes at module level must not reach the owner's.

Module-level reads happen while pytest imports test modules, before any fixture runs: they see what ``conftest.py`` sets at import time.
(Earlier modules may already have imported Hermes into that home by the time this one is read, so the home is checked for where it is
and what it is called, not for being empty.)
"""
import os
import tempfile
from pathlib import Path

IMPORT_TIME_HOME = os.environ.get('HERMES_HOME', '')
IMPORT_TIME_LAZY_INSTALLS = os.environ.get('HERMES_DISABLE_LAZY_INSTALLS', '')


def test_the_home_is_a_private_temporary_folder_before_any_fixture_runs():
    assert IMPORT_TIME_HOME, 'HERMES_HOME is not set while test modules are imported'
    home = Path(IMPORT_TIME_HOME).resolve()
    assert home.parent == Path(tempfile.gettempdir()).resolve(), home
    assert home.name.startswith('beacon-tests-hermes-home-'), home


def test_a_module_level_hermes_import_cannot_start_a_relaunch():
    assert IMPORT_TIME_LAZY_INSTALLS == '1'
