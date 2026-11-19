import importlib.util
import sys
from pathlib import Path

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)


def test_reply_preview_is_the_newest_assistant_reply_as_plain_text(harness):
    api = harness[0]
    # Hermes' projection (tui_gateway _history_to_messages) carries each reply as `text`.
    messages = [{"role": "user", "text": "Plan my week"},
                {"role": "assistant", "text": "Old reply"},
                {"role": "assistant", "text": "## Plan\n**Monday:** dentist at 17:30\n```code\nx=1\n```\nThen gym."}]
    assert api.reply_preview(messages) == "Plan Monday: dentist at 17:30 Then gym."
    assert api.reply_preview([{"role": "assistant", "text": "x" * 400}], limit=20).endswith("…")
    assert api.reply_preview([{"role": "user", "text": "only me"}]) == ""
    # Only this turn: an earlier reply never stands in for one that has no text yet.
    assert api.reply_preview([{"role": "assistant", "text": "Yesterday's answer"}, {"role": "user", "text": "And today?"}]) == ""
    tool_only = [{"role": "user", "text": "Clean the folder"}, {"role": "assistant", "text": "Let me check that."},
                 {"role": "tool", "name": "terminal"}, {"role": "assistant", "text": ""}]
    assert api.reply_preview(tool_only) == ""
    # A failed-turn notice is not a reply, and Hermes' cleaned copy wins over the raw text.
    assert api.reply_preview([{"role": "user", "text": "Hi"}, {"role": "assistant", "display_kind": "failed_turn", "text": "Your request was not processed."}]) == ""
    assert api.reply_preview([{"role": "assistant", "text": "token sk-raw", "display_content": "token [redacted]"}]) == "token [redacted]"


def test_the_hermes_under_test_projects_replies_as_the_preview_reads_them():
    # Pins the two Hermes facts 1.1.16 rests on, against the installed or upstream source this run tests with.
    import os
    root = os.environ.get("HERMES_AGENT_SOURCE", "")
    source = Path(root) / "tui_gateway" / "session_history.py"
    if not root or not source.is_file():
        pytest.skip("no Hermes source on this run")
    text = source.read_text(encoding="utf-8")
    assert "def _history_to_messages(" in text
    assert '"text": content_text' in text, "assistant rows no longer carry the reply as text"
    native = (Path(__file__).parents[1] / "native.py").read_text(encoding="utf-8")
    if "bind_module(globals(), server" in text:
        assert "getattr(server, '_history_to_messages'" in native, "the module copy cannot run outside server's binding"


def test_completion_notice_detail_carries_the_reply(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api.native, "conversation_snapshot",
                        lambda *args: {"title": "Weekend in Lisbon", "messages": [{"role": "assistant", "text": "Here is a two-day plan."}]})
    import time
    event = "c" * 32
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)",
                     (event, "owner", "default", "completion", "native-stored", time.time(), time.time() + 300))
    detail = client.get(f"/api/plugins/beacon/mobile/notifications/events/{event}").json()
    assert detail["preview"] == {"title": "Weekend in Lisbon", "body": "Here is a two-day plan."}


def test_a_reply_in_the_beacon_chat_says_so_so_the_phone_titles_it_with_the_assistants_name(harness, monkeypatch):
    # Owner, 4 Nov: a reply notice from the Beacon chat showed the chat's auto title ("hi"); the phone names it after the assistant.
    api, client, cfg, _, _ = harness
    monkeypatch.setattr(api.native, "conversation_snapshot", lambda *args: {"title": "hi", "messages": [{"role": "assistant", "text": "Alarm set."}]})
    import time
    for event, conversation in (("a" * 32, cfg["scheduled_callback_conversation_id"]), ("b" * 32, "native-stored")):
        with api.mobile.connect() as conn:
            conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)",
                         (event, "owner", "default", "completion", conversation, time.time(), time.time() + 300))
    assert client.get(f"/api/plugins/beacon/mobile/notifications/events/{'a' * 32}").json()["assistant_chat"] is True
    assert client.get(f"/api/plugins/beacon/mobile/notifications/events/{'b' * 32}").json()["assistant_chat"] is False


def test_legacy_alert_notice_detail_uses_generic_title_and_keeps_feedback_marker(harness):
    import time
    api, client, _, _, _ = harness
    event = "e" * 32
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)",
                     (event, "owner", "default", "task_result", "native-stored", time.time(), time.time() + 300,
                      "Daniel moved the client review to 09:00 tomorrow"))
        conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", ("proactive-email-notice:" + event, b"1"))
    detail = client.get(f"/api/plugins/beacon/mobile/notifications/events/{event}").json()
    assert detail["email_alert"] is True
    assert detail["preview"] == {"title": "Important update", "body": "Daniel moved the client review to 09:00 tomorrow"}


@pytest.mark.asyncio
async def test_phone_delivery_keeps_cron_title_and_rejects_unwrapped_standalone(harness,monkeypatch):
    api,client,cfg,_,_=harness
    cfg['scheduled_callback_conversation_id']='assistant-chat'
    phone=load_private('phone_delivery')
    from types import SimpleNamespace
    from gateway import platform_registry as registry_module
    registry = registry_module.PlatformRegistry()
    monkeypatch.setattr(registry_module, 'platform_registry', registry)
    monkeypatch.delenv('PHONE_HOME_CHANNEL', raising=False)
    phone.register(SimpleNamespace(register_platform=lambda **values: registry.register(registry_module.PlatformEntry(**values))))
    monkeypatch.setattr(phone,'_modules',lambda:(api.mobile,api.native,api.proactive))
    monkeypatch.setattr(phone,'_profile_state',lambda:(api.mobile,cfg,'default'))
    monkeypatch.setattr(api.native,'verify',lambda *args:{'messages':[]})

    envelope='Cronjob Response: Daily brief\n(job_id: daily-brief)\n---\nYour task result.'
    cron=await phone.BeaconPhoneAdapter({}).send('phone',envelope)
    retry=await phone.standalone_send({},'phone',envelope)
    standalone=await phone.standalone_send({},'phone','Hermes is shutting down.')

    assert cron.success and retry['success'] and retry['message_id']==cron.message_id
    assert standalone.get('error') and not standalone.get('success')
    with api.mobile.connect() as conn:
        cron_job=conn.execute('SELECT job_id FROM notices WHERE id=?',(cron.message_id,)).fetchone()['job_id']
        assert conn.execute('SELECT COUNT(*) FROM notices').fetchone()[0]==1
    assert cron_job.startswith('phone-cron-')
    cron_detail=client.get(f"/api/plugins/beacon/mobile/notifications/events/{cron.message_id}").json()
    assert cron_detail['preview']['title']=='Task finished'



def _chat_store(monkeypatch, chains):
    """The saved chats of a fake Hermes store: `chains` maps a chat's first id to the ids Hermes continued it under (compression)."""
    import contextlib
    from types import SimpleNamespace
    db = SimpleNamespace(get_compression_chain=lambda sid: [sid, *chains.get(sid, [])])
    server = sys.modules["tui_gateway"].server
    monkeypatch.setattr(server, "_profile_db", lambda *a, **k: contextlib.nullcontext(db), raising=False)


def _notice(api, event, conversation, kind="completion"):
    import time
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires) VALUES(?,?,?,?,?,?,?)",
                     (event, "owner", "default", kind, conversation, time.time(), time.time() + 300))
    return f"/api/plugins/beacon/mobile/notifications/events/{event}"


def test_a_reply_after_hermes_moved_the_beacon_chat_to_a_new_session_still_says_so(harness, monkeypatch):
    # Hermes ends a chat's session and continues it under a new id when it compresses the history; the turn then finishes under the new id
    # (agent/turn_finalizer.py on_session_end session_id=agent.session_id), the chat's saved id stays the first one.
    api, client, cfg, _, _ = harness
    monkeypatch.setattr(api.native, "conversation_snapshot", lambda *args: {"title": "hi", "messages": [{"role": "assistant", "text": "Alarm set."}]})
    stored = cfg["scheduled_callback_conversation_id"]
    _chat_store(monkeypatch, {stored: ["assistant-chat-2", "assistant-chat-3"], "other-chat": ["other-chat-2"]})
    for event, conversation, expected in (("1" * 32, "assistant-chat-2", True), ("2" * 32, "assistant-chat-3", True), ("3" * 32, stored, True),
                                          ("4" * 32, "other-chat-2", False), ("5" * 32, "native-stored", False)):
        detail = client.get(_notice(api, event, conversation)).json()
        assert detail["assistant_chat"] is expected, conversation
        # The phone opens and answers the chat by the id it saved it under, not by the one the turn happened to finish under.
        assert detail["session_id"] == (stored if expected else conversation), conversation
        assert detail["conversation_id"] == detail["session_id"]


def test_a_notice_of_the_beacon_chat_stays_named_when_hermes_cannot_read_the_chat_lineage(harness, monkeypatch):
    api, client, cfg, _, _ = harness
    monkeypatch.setattr(api.native, "conversation_snapshot", lambda *args: {"title": "hi", "messages": [{"role": "assistant", "text": "Done."}]})
    monkeypatch.setattr(sys.modules["tui_gateway"].server, "_profile_db", lambda *a, **k: (_ for _ in ()).throw(RuntimeError("store busy")), raising=False)
    stored = cfg["scheduled_callback_conversation_id"]
    assert client.get(_notice(api, "6" * 32, stored)).json()["assistant_chat"] is True
    assert client.get(_notice(api, "7" * 32, "assistant-chat-2")).json()["assistant_chat"] is False  # unknown stays unnamed, never an error
