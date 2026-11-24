"""Beacon reads its manifest and profile config on a Hermes without PyYAML.

Hermes' default install (extras [all]) ships ruamel.yaml, not PyYAML (install-123: a fresh [all] environment
answered /mobile/plugin/version with running='' and installed=''). PyYAML only arrives with optional extras,
so Beacon reads YAML through Hermes' own public reader, hermes_cli.config.read_config_doc.
"""
from pathlib import Path
import sys

import pytest
from test_mobile import harness

ROOT = Path(__file__).resolve().parents[1]
VERSION = next(line.split(':', 1)[1].strip() for line in (ROOT / 'plugin.yaml').read_text(encoding='utf-8').splitlines()
               if line.startswith('version:'))


@pytest.fixture
def no_pyyaml(monkeypatch):
    monkeypatch.setitem(sys.modules, 'yaml', None)


def _config(tmp_path):
    (tmp_path / 'config.yaml').write_text('memory:\n  provider: example\nvoice:\n  language: ar\ndisplay:\n  compact: yes\n',
                                          encoding='utf-8')
    return tmp_path


def test_the_version_route_reports_the_installed_release_without_pyyaml(harness, no_pyyaml):
    api, client, _, _, _ = harness
    assert api.mobile.read_plugin_version(ROOT / 'plugin.yaml') == VERSION
    assert client.get('/api/plugins/beacon/mobile/plugin/version').json()['installed'] == VERSION


def test_profile_config_reads_without_pyyaml_as_hermes_reads_it(harness, no_pyyaml, tmp_path):
    config = harness[0].voice_context.config(_config(tmp_path))
    assert config == {'memory': {'provider': 'example'}, 'voice': {'language': 'ar'}, 'display': {'compact': True}}


def test_a_hermes_without_its_config_reader_still_reads_with_pyyaml(harness, monkeypatch, tmp_path):
    pytest.importorskip('yaml')
    monkeypatch.setitem(sys.modules, 'hermes_cli.config', None)
    api = harness[0]
    assert api.mobile.read_plugin_version(ROOT / 'plugin.yaml') == VERSION
    assert api.voice_context.config(_config(tmp_path))['display'] == {'compact': True}


def test_with_no_yaml_reader_at_all_both_fail_open(harness, no_pyyaml, monkeypatch, tmp_path):
    monkeypatch.setitem(sys.modules, 'hermes_cli.config', None)
    api = harness[0]
    assert api.mobile.read_plugin_version(ROOT / 'plugin.yaml') == ''
    assert api.voice_context.config(_config(tmp_path)) == {}


def test_malformed_config_is_empty(harness, tmp_path):
    (tmp_path / 'config.yaml').write_text('memory: [unclosed\n', encoding='utf-8')
    assert harness[0].voice_context.config(tmp_path) == {}
