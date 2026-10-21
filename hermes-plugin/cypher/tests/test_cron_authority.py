import json
import sys
from contextlib import nullcontext
from types import SimpleNamespace

import pytest

from test_mobile import harness, load_private


def install_cron_context(api, cfg, tmp_path, monkeypatch, sessions):
    plugin = load_private('__init__')
    rows = SimpleNamespace(get_session=sessions.get)
    server = SimpleNamespace(
        _profile_home=lambda profile: tmp_path,
        _profile_db=lambda scope: nullcontext(rows),
    )
    monkeypatch.setitem(sys.modules, 'hermes_constants', SimpleNamespace(get_hermes_home=lambda: tmp_path))
    monkeypatch.setitem(sys.modules, 'hermes_state_keys', SimpleNamespace(CRON_PRINCIPAL_ID='cron-owner'))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    monkeypatch.setattr(plugin, 'mobile', api.mobile)
    monkeypatch.setattr(plugin, 'native', api.native)
    monkeypatch.setattr(api.mobile, 'settings', lambda profile=None: cfg)
    monkeypatch.setattr(api.mobile, 'home', lambda: tmp_path)
    cfg.update(
        callback_tool_enabled=True,
        scheduled_callbacks_enabled=True,
        scheduled_callback_conversation_id='owned-chat',
    )
    return plugin


def test_current_profile_distinguishes_launch_and_named_profiles(harness, monkeypatch, tmp_path):
    api, _, cfg, _, _ = harness
    launch_home = tmp_path / 'launch'
    work_home = tmp_path / 'work'
    active_home = [launch_home]
    override = [None]
    server = SimpleNamespace(_profile_home=lambda profile: None if profile == 'default' else work_home)
    # A profile-scoped task sets a home override; with none, the run is the launch profile's.
    monkeypatch.setitem(sys.modules, 'hermes_constants', SimpleNamespace(
        get_hermes_home=lambda: active_home[0], get_hermes_home_override=lambda: override[0]))
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    monkeypatch.setattr(sys.modules['cypher_mobile'], 'home', lambda: launch_home)
    cfg['profiles'] = ['default', 'work']

    assert api.proactive.current_profile(cfg) == 'default'
    active_home[0] = override[0] = work_home
    assert api.proactive.current_profile(cfg) == 'work'
    # Hermes repoints HERMES_HOME while it deletes 'work': no override, yet the home reads as work's.
    override[0] = None
    assert api.proactive.current_profile(cfg) == 'default'


def test_call_user_rejects_foreign_owned_cron_and_allows_unowned_cron(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-foreign': {'user_id': 'basic:intruder', 'source': 'cron', 'title': 'Foreign cron'},
        'cron-unowned': {'user_id': None, 'source': 'cron', 'title': 'Owner callback'},
        'cron-owner': {'user_id': 'cron-owner', 'source': 'cron', 'title': 'Owner check-up'},
        'phone-unowned': {'user_id': None, 'source': 'android', 'title': 'Unowned phone chat'},
    })
    monkeypatch.setattr(api.native, 'verify', lambda *args: {})
    not_owned = {
        'error': 'This chat is not linked to your Hermes sign-in, so Beacon cannot ring from it. Start a new chat in Beacon and ask again.',
        'reason': 'conversation_not_owned',
    }

    denied = json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='cron-foreign'))
    assert denied == not_owned
    assert json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='phone-unowned')) == not_owned
    assert json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='missing')) == {'error': 'Owned native callback context unavailable'}
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0] == 0

    allowed = json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='cron-unowned'))
    assert allowed['status'] == 'pending' and allowed['conversation_id'] == 'owned-chat'

    allowed_principal = json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='cron-owner'))
    assert allowed_principal['status'] == 'pending' and allowed_principal['conversation_id'] == 'owned-chat'
    # Two cron jobs share the fallback chat but carry their own reasons, so neither swallows the other.
    assert allowed_principal['event_id'] != allowed['event_id']
