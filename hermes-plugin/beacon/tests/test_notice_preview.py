import importlib.util
import sys
from pathlib import Path

import pytest
from test_mobile import harness, load_private  # noqa: F401  (fixture)


def test_reply_preview_is_the_newest_assistant_reply_as_plain_text(harness):
    api = harness[0]
    messages = [{"role": "user", "content": "Plan my week"},
                {"role": "assistant", "content": "Old reply"},
                {"role": "assistant", "content": "## Plan\n**Monday:** dentist at 17:30\n```code\nx=1\n```\nThen gym."}]
    assert api.reply_preview(messages) == "Plan Monday: dentist at 17:30 Then gym."
    assert api.reply_preview([{"role": "assistant", "content": "x" * 400}], limit=20).endswith("…")
    assert api.reply_preview([{"role": "user", "content": "only me"}]) == ""


def test_completion_notice_detail_carries_the_reply(harness, monkeypatch):
    api, client, _, _, _ = harness
    monkeypatch.setattr(api.native, "conversation_snapshot",
                        lambda *args: {"title": "Weekend in Lisbon", "messages": [{"role": "assistant", "content": "Here is a two-day plan."}]})
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
async def test_phone_delivery_keeps_cron_title_and_uses_hermes_for_standalone(harness,monkeypatch):
    api,client,cfg,_,_=harness
    cfg['scheduled_callback_conversation_id']='assistant-chat'
    phone=load_private('phone_delivery')
    monkeypatch.setattr(phone,'_modules',lambda:(api.mobile,api.native,api.proactive))
    monkeypatch.setattr(phone,'_profile_state',lambda:(api.mobile,cfg,'default'))
    monkeypatch.setattr(api.native,'verify',lambda *args:{'messages':[]})

    cron=await phone.BeaconPhoneAdapter({}).send(
        'phone','Cronjob Response: Daily brief\n(job_id: daily-brief)\n---\nYour task result.')
    standalone=await phone.standalone_send({},'phone','Hermes is shutting down.')

    assert cron.success and standalone['success']
    with api.mobile.connect() as conn:
        cron_job=conn.execute('SELECT job_id FROM notices WHERE id=?',(cron.message_id,)).fetchone()['job_id']
        standalone_job=conn.execute('SELECT job_id FROM notices WHERE id=?',(standalone['message_id'],)).fetchone()['job_id']
    assert cron_job.startswith('phone-cron-') and standalone_job.startswith('phone-hermes-')
    cron_detail=client.get(f"/api/plugins/beacon/mobile/notifications/events/{cron.message_id}").json()
    standalone_detail=client.get(
        f"/api/plugins/beacon/mobile/notifications/events/{standalone['message_id']}").json()
    assert cron_detail['preview']['title']=='Task finished'
    assert standalone_detail['preview']['title']=='Hermes'

