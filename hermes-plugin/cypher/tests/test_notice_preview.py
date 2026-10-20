import importlib.util
import sys
from pathlib import Path

from test_mobile import harness  # noqa: F401  (fixture)


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
    detail = client.get(f"/api/plugins/cypher/mobile/notifications/events/{event}").json()
    assert detail["preview"] == {"title": "Weekend in Lisbon", "body": "Here is a two-day plan."}
