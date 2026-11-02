"""mobile.settings() re-reads config.yaml only when the file changed."""
import os
import sys
import time
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml

from test_mobile import harness

CONFIG = """plugins:
  enabled: [beacon]
  entries:
    beacon:
      settings:
        owner_id: owner
        owner_provider: basic
        profiles: [default]
        language: %s
"""


@pytest.fixture
def parsed(harness, monkeypatch):
    """The real settings(), reading a config.yaml in the test home through a counting stand-in for Hermes' YAML loader."""
    api, _, _, tmp_path, _ = harness
    calls = []

    def load(path):
        calls.append(str(path))
        return yaml.safe_load(Path(path).read_text(encoding='utf-8'))

    monkeypatch.setitem(sys.modules, 'utils', SimpleNamespace(load_yaml_file_readonly=load))
    path = tmp_path / 'config.yaml'

    def write(language, age=10):
        path.write_text(CONFIG % language, encoding='utf-8')
        # A file touched a moment ago is deliberately never cached, so settle its time in the past.
        stamp = time.time() - age
        os.utime(path, (stamp, stamp))

    return api._beacon_original_mobile_settings, calls, write, path


def test_settings_does_not_reparse_an_unchanged_config(parsed):
    settings, calls, write, _ = parsed
    write('English')
    for _ in range(6):
        assert settings()['language'] == 'English'
        assert settings('default')['owner_id'] == 'owner'
    assert len(calls) == 1


def test_settings_reparses_when_the_config_changes(parsed):
    settings, calls, write, path = parsed
    write('English')
    assert settings()['language'] == 'English' and len(calls) == 1
    write('Arabic', age=20)  # another content length and time, as an edit would be
    assert settings()['language'] == 'Arabic' and len(calls) == 2
    assert settings()['language'] == 'Arabic' and len(calls) == 2
    path.write_text(CONFIG % 'Arabic' + '# a comment\n', encoding='utf-8')  # same keys, another size
    os.utime(path, (time.time() - 5, time.time() - 5))
    assert settings()['language'] == 'Arabic' and len(calls) == 3


def test_settings_callers_cannot_change_the_cached_parse(parsed):
    settings, calls, write, _ = parsed
    write('English')
    first = settings()
    first['language'] = 'tampered'
    first['profiles'].append('extra')
    again = settings()
    assert again['language'] == 'English' and again['profiles'] == ['default']
    assert len(calls) == 1


def test_settings_never_caches_a_config_written_a_moment_ago(parsed):
    settings, calls, write, path = parsed
    write('English', age=0)
    settings(); settings()
    assert len(calls) == 2
    # The same size and the same timestamp as before, but other content: still read afresh.
    stat = path.stat()
    path.write_text(CONFIG % 'French', encoding='utf-8')
    os.utime(path, ns=(stat.st_atime_ns, stat.st_mtime_ns))
    assert settings()['language'] == 'French'


def test_settings_stays_closed_when_the_plugin_is_disabled_or_the_file_is_unreadable(parsed):
    settings, calls, write, path = parsed
    write('English')
    assert settings()
    path.write_text('plugins:\n  enabled: []\n', encoding='utf-8')
    os.utime(path, (time.time() - 30, time.time() - 30))
    assert settings() == {}
    path.write_text('not: [valid', encoding='utf-8')
    os.utime(path, (time.time() - 40, time.time() - 40))
    assert settings() == {}
    path.unlink()
    assert settings() == {}
    write('English', age=50)
    assert settings()['language'] == 'English'  # a parse failure is never remembered as the answer
