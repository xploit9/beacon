"""Review 121's open question, against real Hermes code: does Hermes admit Beacon for a secondary profile that has no `plugins/beacon` of its own?

Beacon is installed once, in the dashboard's (default) profile. The other profiles have no plugin folder. Everything below runs Hermes' own code in a temporary
HERMES_HOME (never the owner's): Hermes creates the second profile (`hermes_cli.profiles.create_profile`), the watcher's pass (`admin_api.turn_on_for_profiles`)
runs Hermes' own enable (`_config_profile_scope` -> `plugins_cmd._activate_key` -> `_set_plugin_enabled` -> `plugins_admission.admit_plugin_set_change`), and
Beacon's real gate (`canonical_transport.native_policy`, the one that answered 403 "Plugin not authorized") is asked before and after.

Only the PM worker boundary (`pm.client.sync_venv`) is replaced, by PM's own in-process publication: `pm.publication.PluginSelection` validates the selection,
computes its dependency members and proposes the config bytes the worker writes. Left out are the worker process, the install lock and the dependency build,
which would build a whole environment for a temporary root. Beacon declares `python_dependencies: []`, and for a profile without `plugins/beacon` PM finds no
plugin folder for the key (`pm/workspace.py` `selected_plugin_dir`), so enabling it there adds no dependency member and the build has nothing to do.
"""
import importlib.util
import shutil
import sys
from pathlib import Path

import pytest
from fastapi import HTTPException

ROOT = Path(__file__).resolve().parents[1]


def _load(name):
    spec = importlib.util.spec_from_file_location('beacon_real_hermes_' + name, ROOT / (name + '.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.fixture
def real_hermes(tmp_path, monkeypatch):
    try:
        from hermes_cli import plugins_cmd, profiles  # noqa: F401
        from hermes_cli import web_server, web_server_profiles  # noqa: F401
        import pm.client
        import pm.publication
    except Exception as exc:  # no Hermes source on the path: the contract tests say so too
        pytest.skip('Set HERMES_AGENT_SOURCE and PYTHONPATH to a Hermes source tree: %s' % type(exc).__name__)
    root = tmp_path / 'hermes-root'
    root.mkdir()
    monkeypatch.setenv('HERMES_HOME', str(root))
    # Beacon installed once, in the dashboard's own profile, and enabled there. Hermes' discovery and gate read only its manifests.
    plugin = root / 'plugins' / 'beacon'
    (plugin / 'dashboard').mkdir(parents=True)
    shutil.copy(ROOT / 'plugin.yaml', plugin / 'plugin.yaml')
    shutil.copy(ROOT / 'dashboard' / 'manifest.json', plugin / 'dashboard' / 'manifest.json')
    (plugin / 'dashboard' / 'plugin_api.py').write_text('', encoding='utf-8')
    (root / 'config.yaml').write_text('plugins:\n  enabled:\n  - beacon\n', encoding='utf-8')
    # A second profile made the way `hermes profile create work` makes it: no plugin folder of its own.
    work = profiles.create_profile('work', no_alias=True, no_skills=True)
    assert not (work / 'plugins' / 'beacon').exists()
    # Hermes keeps the launch profile's dashboard plugins in a module global; this test's home is a new launch home.
    monkeypatch.setattr(web_server, '_dashboard_plugins_cache', None)
    published = []

    def sync_venv(extras=None, *, explicit=False, plugins=None, **kwargs):
        """PM's worker, in process: its own selection check and member computation, then the config bytes it publishes."""
        assert explicit and plugins is not None
        selection = pm.publication.PluginSelection(dict(plugins.data))
        published.append({'home': selection.home, 'members': [Path(m).resolve() for m in selection.members], 'enabled': list(plugins.data['enabled'])})
        selection.path.write_bytes(selection.proposed)
    monkeypatch.setattr(pm.client, 'sync_venv', sync_venv)
    admin, transport = _load('admin_api'), _load('canonical_transport')
    monkeypatch.setattr(admin, 'update_in_progress', lambda: False)
    yield root, work, admin, transport, published
    # Each test loads its own copy of admin_api; the background pass's file lock its copy took is released here (in Hermes the module is loaded
    # once per process and keeps it for the process's life).
    if admin._profile_pass_fd is not None:
        import os
        os.close(admin._profile_pass_fd)


def _config(home):
    import yaml
    return yaml.safe_load((home / 'config.yaml').read_text(encoding='utf-8')) or {}


def test_hermes_admits_beacon_for_a_second_profile_without_its_own_plugin_folder(real_hermes):
    root, work, admin, transport, published = real_hermes
    # Before: the gate that answered 403 "Plugin not authorized" refuses the second profile and serves the dashboard's own.
    with pytest.raises(HTTPException) as refused:
        transport.native_policy('work')
    assert (refused.value.status_code, refused.value.detail) == (403, 'Plugin not authorized')
    assert Path(transport.native_policy('default')).resolve() == root.resolve()
    # The watcher's pass runs Hermes' own enable for the second profile only.
    assert admin.turn_on_for_profiles(['default', 'work']) == ['work']
    assert len(published) == 1 and published[0]['home'] == work.resolve() and 'beacon' in published[0]['enabled']
    # PM found no plugin folder for `beacon` in that profile, so it adds no dependency member there; the dashboard's own copy is the only Beacon tree.
    assert not any(member.is_relative_to(work.resolve()) for member in published[0]['members'])
    # Hermes wrote the selection into that profile's own config, and nowhere else; no plugin folder was made for it.
    assert _config(work)['plugins']['enabled'] == ['beacon']
    assert _config(root)['plugins']['enabled'] == ['beacon']
    assert not (work / 'plugins' / 'beacon').exists()
    # After: the same gate admits Beacon for the second profile and answers with that profile's own home.
    assert Path(transport.native_policy('work')).resolve() == work.resolve()
    # A second pass finds it on and asks Hermes nothing.
    assert admin.turn_on_for_profiles(['default', 'work']) == [] and len(published) == 1


def test_hermes_own_disable_in_the_second_profile_is_left_alone(real_hermes):
    root, work, admin, transport, published = real_hermes
    from hermes_cli import plugins_cmd
    from hermes_cli.web_server_profiles import _config_profile_scope
    with _config_profile_scope('work'):
        plugins_cmd._activate_key('beacon', enable=False)  # `hermes -p work plugins disable beacon`
    published.clear()
    assert 'beacon' in _config(work)['plugins']['disabled']
    assert admin.turn_on_for_profiles(['work']) == [] and published == []
    with pytest.raises(HTTPException):
        transport.native_policy('work')



def test_the_race_with_another_turn_on_ends_with_beacon_on_and_nothing_parked(real_hermes, monkeypatch, caplog):
    """calls-121 DEVICE-ROUND-1 item 6, with Hermes' own wrapping: while PM worked on the pass's selection the phone's turn-on wrote that profile's
    config, PM refused the stale selection with its compare-and-swap ValueError, and Hermes' admit_plugin_set_change turned it into AdmissionRefused."""
    import pm.client
    import pm.publication
    from hermes_cli.plugins_admission import AdmissionRefused
    root, work, admin, transport, published = real_hermes
    seen = []

    def racing_sync(extras=None, *, explicit=False, plugins=None, **kwargs):
        selection = pm.publication.PluginSelection(dict(plugins.data))
        selection.path.write_bytes(selection.proposed)  # the other turn-on's publication: the same selection, written first
        raise ValueError('plugin configuration changed while preparing publication; retry')
    monkeypatch.setattr(pm.client, 'sync_venv', racing_sync)
    original_warning = admin.log.warning
    monkeypatch.setattr(admin.log, 'warning', lambda *args: seen.append(args) or original_warning(*args))
    assert admin.turn_on_for_profiles(['work']) == []
    assert seen == [] and admin.PROFILES_REFUSED == {}
    assert Path(transport.native_policy('work')).resolve() == work.resolve()
    # And the error the pass met is Hermes' own AdmissionRefused, not something this test made up.
    from hermes_cli import plugins_cmd
    from hermes_cli.web_server_profiles import _config_profile_scope
    (work / 'config.yaml').write_text('plugins:\n  enabled: []\n', encoding='utf-8')
    with pytest.raises(AdmissionRefused) as refused, _config_profile_scope('work'):
        plugins_cmd._activate_key('beacon', enable=True)
    assert admin._changed_meanwhile(refused.value)


def test_a_selection_changed_by_another_writer_is_asked_again_by_the_next_pass(real_hermes, monkeypatch):
    import pm.client
    root, work, admin, transport, published = real_hermes
    honest = pm.client.sync_venv  # the fixture's in-process PM publication

    def changed_once(extras=None, *, explicit=False, plugins=None, **kwargs):
        monkeypatch.setattr(pm.client, 'sync_venv', honest)
        raise ValueError('Plugin configuration changed since this selection was read; retry.')
    monkeypatch.setattr(pm.client, 'sync_venv', changed_once)
    assert admin.turn_on_for_profiles(['work']) == [] and admin.PROFILES_REFUSED == {}
    with pytest.raises(HTTPException):
        transport.native_policy('work')
    assert admin.turn_on_for_profiles(['work']) == ['work']
    assert Path(transport.native_policy('work')).resolve() == work.resolve()
