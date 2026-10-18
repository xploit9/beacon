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
        'cron-owner': {'user_id': 'cron-owner', 'source': 'cron'},
    })
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))

    with pytest.raises(LookupError):
        plugin.verify_mail_source(cfg, 'default', 'cron-foreign')
    assert verified == []

    plugin.verify_mail_source(cfg, 'default', 'cron-unowned')
    assert verified == [('owner', 'basic', 'default', 'owned-chat')]

    plugin.verify_mail_source(cfg, 'default', 'cron-owner')
    assert verified == [('owner', 'basic', 'default', 'owned-chat')] * 2


def test_mail_authority_falls_back_when_hermes_state_keys_is_unavailable(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-owner': {'user_id': 'cron-owner', 'source': 'cron'},
    })
    monkeypatch.setitem(sys.modules, 'hermes_state_keys', None)
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))

    plugin.verify_mail_source(cfg, 'default', 'cron-owner')

    assert verified == [('owner', 'basic', 'default', 'owned-chat')]


def test_mail_read_returns_bounded_error_for_foreign_owned_cron(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-foreign': {'user_id': 'basic:intruder', 'source': 'cron'},
    })
    monkeypatch.setattr(plugin, 'current_profile', lambda config: 'default')

    result = json.loads(plugin.mail_read({'arguments': {}}, session_id='cron-foreign'))

    assert result == {'error': 'Mailbox scan session is not authorized'}


def test_mail_read_accepts_hermes_cron_principal(harness, monkeypatch):
    api, _, cfg, tmp_path, _ = harness
    plugin = install_cron_context(api, cfg, tmp_path, monkeypatch, {
        'cron-owner': {'user_id': 'cron-owner', 'source': 'cron'},
    })
    monkeypatch.setattr(plugin, 'current_profile', lambda config: 'default')
    monkeypatch.setattr(plugin.observers, 'mailbox_facts', lambda *args: [])
    monkeypatch.setattr(plugin.observers, 'mailbox_observed', lambda *args: None)
    tool = {'name': 'exact', '_server': 'mail', 'read_only': True, 'parameters': {}, '_handler': lambda *args, **kwargs: {'messages': []}}
    monkeypatch.setitem(sys.modules, 'cypher_gateway_tools', SimpleNamespace(
        NativeMcp=lambda: SimpleNamespace(catalog=lambda: [tool]), validate=lambda *args: None))
    verified = []
    monkeypatch.setattr(api.native, 'verify', lambda *args: verified.append(args))
    cfg['mailbox_evidence'] = {'server': 'mail', 'tool': 'exact', 'account': 'fixture'}

    result = json.loads(plugin.mail_read({'arguments': {}}, session_id='cron-owner'))

    assert result['source_tool'] == 'exact'
    assert verified == [('owner', 'basic', 'default', 'owned-chat')]


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
        'error': 'This chat is not linked to your Hermes sign-in, so Cypher cannot ring from it. Start a new chat in Cypher and ask again.',
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
