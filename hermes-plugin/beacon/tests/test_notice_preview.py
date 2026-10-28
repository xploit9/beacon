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

