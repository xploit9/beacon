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
    monkeypatch.setitem(sys.modules, 'tui_gateway', SimpleNamespace(server=server))
    monkeypatch.setattr(plugin, 'mobile', api.mobile)
    monkeypatch.setattr(plugin, 'native', api.native)
    monkeypatch.setattr(api.mobile, 'settings', lambda: cfg)
    monkeypatch.setattr(api.mobile, 'home', lambda: tmp_path)
    cfg.update(
        callback_tool_enabled=True,
        scheduled_callbacks_enabled=True,
        scheduled_callback_conversation_id='owned-chat',
    )
    return plugin


def test_mail_authority_rejects_foreign_cron_but_allows_unowned_cron(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-foreign': {'user_id': 'basic:intruder', 'source': 'cron'},
        'cron-unowned': {'user_id': None, 'source': 'cron'},
    })
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))

    with pytest.raises(LookupError):
        plugin.verify_mail_source(cfg, 'default', 'cron-foreign')
    assert verified == []

    plugin.verify_mail_source(cfg, 'default', 'cron-unowned')
    assert verified == [('owner', 'basic', 'default', 'owned-chat')]


def test_mail_read_returns_bounded_error_for_foreign_owned_cron(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-foreign': {'user_id': 'basic:intruder', 'source': 'cron'},
    })
    monkeypatch.setattr(plugin, 'current_profile', lambda config: 'default')

    result = json.loads(plugin.mail_read({'arguments': {}}, session_id='cron-foreign'))

    assert result == {'error': 'Mailbox scan session is not authorized'}


def test_call_user_rejects_foreign_owned_cron_and_allows_unowned_cron(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-foreign': {'user_id': 'basic:intruder', 'source': 'cron', 'title': 'Foreign cron'},
        'cron-unowned': {'user_id': None, 'source': 'cron', 'title': 'Owner callback'},
    })
    monkeypatch.setattr(api.native, 'verify', lambda *args: {})

    denied = json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='cron-foreign'))
    assert 'error' in denied
    with api.mobile.connect() as conn:
        assert conn.execute("SELECT COUNT(*) FROM notices WHERE kind='incoming_call'").fetchone()[0] == 0

    allowed = json.loads(plugin.call_user({'reason': 'ring the owner'}, session_id='cron-unowned'))
    assert allowed['status'] == 'pending' and allowed['conversation_id'] == 'owned-chat'
