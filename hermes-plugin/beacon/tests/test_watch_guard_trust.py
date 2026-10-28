"""The Proactive watch guard judges an MCP tool by its server's trust, as the call gateway does (Strix 70c9aa7 #1 and #2)."""
import sys
from types import SimpleNamespace

import pytest

from test_mobile import harness, load_private  # noqa: F401  (harness is the fixture watch_guard builds on)
from test_security_hardening import _mcp_trust, watch_guard  # noqa: F401


def _serve(hints):
    """Offer these tools ({name: read-only hint}) on the fixture's MCP server api."""
    mcp_tool = sys.modules["tools.mcp_tool"]
    mcp_tool._mcp_tool_server_names.update({"mcp__api__" + name: "api" for name in hints})
    mcp_tool._tool_read_only_hints["api"] = dict(hints)


def _guard(plugin, name, args=None):
    return plugin.native_proactive_read_only_guard(tool_name="mcp__api__" + name, args=args or {}, session_id="watch-run")


# Before the fix the server's hint alone released any name with no listed write word, on every server. A watch runs
# unattended, so the name must hold a read word on a trusted server too; on an untrusted one it may not hold a noun either.
@pytest.mark.parametrize("trust, noun_runs", [(None, True), ("full", True), ("untrusted", False)])
def test_a_hinted_tool_runs_in_a_watch_only_when_its_name_says_it_reads(watch_guard, monkeypatch, trust, noun_runs):
    plugin, _ = watch_guard
    names = ("cleanup", "nudge_user", "deploy_service", "upsert_row")
    _serve({name: True for name in names + ("get_pull_request",)})
    _mcp_trust(monkeypatch, api=trust)
    blocked = {"action": "block", "message": plugin._READ_ONLY_MESSAGE}
    for name in names:
        assert _guard(plugin, name) == blocked, (name, trust)
    assert _guard(plugin, "get_pull_request") == (None if noun_runs else blocked), trust


def test_an_untrusted_servers_hinted_tool_runs_only_when_its_name_says_it_reads_with_no_noun_or_write_word(watch_guard, monkeypatch):
    plugin, _ = watch_guard
    reads = ("get_resource", "search_issues", "list_files")
    not_reads = ("get_pull_request", "get_message", "GMAIL_FETCH_EMAILS", "get_and_delete_x", "get_or_call_user")
    _serve({name: True for name in reads + not_reads})
    _mcp_trust(monkeypatch, api="untrusted")
    blocked = {"action": "block", "message": plugin._READ_ONLY_MESSAGE}
    for name in reads:
        assert _guard(plugin, name) is None, name
    for name in not_reads:
        assert _guard(plugin, name) == blocked, name


@pytest.mark.parametrize("unreadable", ["config raises", "server not listed", "entry is not a mapping", "normalizer missing"])
def test_a_server_whose_trust_cannot_be_read_is_untrusted(watch_guard, monkeypatch, unreadable):
    plugin, _ = watch_guard
    if unreadable == "config raises":
        def locked(): raise OSError("config locked")
        monkeypatch.setitem(sys.modules, "tools.mcp_tool_config", SimpleNamespace(_load_mcp_config=locked))
    elif unreadable == "server not listed":
        _mcp_trust(monkeypatch, other=None)
    elif unreadable == "entry is not a mapping":
        monkeypatch.setitem(sys.modules, "tools.mcp_tool_config", SimpleNamespace(_load_mcp_config=lambda: {"api": "oops"}))
    else:
        monkeypatch.setitem(sys.modules, "tools.mcp_tool_registration", None)  # the import fails
    _serve({"cleanup": True, "get_resource": True, "search_inbox": False})
    blocked = {"action": "block", "message": plugin._READ_ONLY_MESSAGE}

    assert _guard(plugin, "cleanup") == blocked  # the hint alone is not enough
    assert _guard(plugin, "search_inbox") == blocked  # nor is an unannotated mail name
    assert _guard(plugin, "get_resource") is None  # untrusted is not shut off: a hinted, read-named tool still runs


# The unannotated mail read by name is for trusted servers: Hermes itself asks the owner about an untrusted server's tool without a hint.
@pytest.mark.parametrize("name", ["search_inbox", "list_calendar_events", "GMAIL_FETCH_EMAILS",
                                  "GMAIL_FETCH_MESSAGE_BY_THREAD_ID", "getMessageAttachment"])
def test_the_unannotated_mail_read_by_name_is_for_trusted_servers_only(watch_guard, monkeypatch, name):
    plugin, _ = watch_guard
    _serve({name: False})
    assert _guard(plugin, name) is None
    _mcp_trust(monkeypatch, api="untrusted")
    assert _guard(plugin, name) == {"action": "block", "message": plugin._READ_ONLY_MESSAGE}


def test_the_mail_query_allowance_is_for_trusted_servers_only(watch_guard, monkeypatch):
    plugin, _ = watch_guard
    _serve({"search_inbox": True})
    args = {"query": "from:bank.com newer_than:1d"}
    assert _guard(plugin, "search_inbox", args) is None
    _mcp_trust(monkeypatch, api="untrusted")
    assert _guard(plugin, "search_inbox", args) == {"action": "block", "message": plugin._READ_ONLY_MESSAGE}


# The call gateway's new classification reaches the guard: chained 'or' and the added verbs are writes whatever the hint says.
@pytest.mark.parametrize("hint", [False, True])
@pytest.mark.parametrize("name", ["get_or_call_user", "get_or_run_pipeline", "list_or_run", "get_x_or_delete_y",
                                  "search_or_message_user", "message_user", "ping_host", "alert_oncall", "comment_on_issue",
                                  "escalate_ticket", "triage_inbox", "process_payment", "truncate_table", "rotate_secret",
                                  "suspend_account", "terminate_session"])
def test_a_trusted_servers_tool_chaining_with_or_or_using_an_added_write_verb_is_blocked(watch_guard, name, hint):
    plugin, _ = watch_guard
    _serve({name: hint})
    assert _guard(plugin, name) == {"action": "block", "message": plugin._READ_ONLY_MESSAGE}


@pytest.mark.parametrize("name", ["get_message", "get_comment", "list_comments", "get_process", "get_alert", "read_message_thread"])
def test_an_added_noun_after_a_leading_read_verb_still_reads_on_a_trusted_server(watch_guard, name):
    plugin, _ = watch_guard
    _serve({name: True})
    assert _guard(plugin, name) is None


# _plain_mail_read stands without a name_writes check because its vocabulary holds no unsafe word but the mail noun 'message'.
def test_the_plain_mail_vocabulary_shares_only_the_message_noun_with_the_unsafe_words(harness):
    plugin = load_private("__init__")
    gateway = plugin._gateway_tools()
    assert (plugin._MAIL_NAME_WORDS | gateway.READ_NAME_WORDS | {"fetch"}) & gateway.UNSAFE_NAME_WORDS == {"message"}
