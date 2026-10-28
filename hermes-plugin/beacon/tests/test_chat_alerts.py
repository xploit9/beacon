import json
import sys
import time
from contextlib import nullcontext
from types import SimpleNamespace

import pytest

from test_mobile import harness  # noqa: F401  (fixture)

CHAT = "assistant-chat"
ALERTS = "/api/plugins/beacon/mobile/proactive/alerts"
FEEDBACK = "/api/plugins/beacon/mobile/proactive/feedback"


@pytest.fixture(autouse=True)
def _learned_mirror_is_not_under_test(monkeypatch):
    # Feedback mirrors the learned notes into the email watch's Hermes notepad; these tests read only the saved notes.
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles", SimpleNamespace(_profile_scope=lambda profile: nullcontext()))
    monkeypatch.setitem(sys.modules, "cron.notepad", SimpleNamespace(set_note=lambda *args: None, delete_note=lambda *args: None))


def _alert(api, event, reason, created, *, owner="owner", profile="default", conversation=CHAT, source="email",
           expires=None, kind="task_result", marker=True):
    with api.mobile.connect() as conn:
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)",
                     (event, owner, profile, kind, conversation, created, expires or created + 86400, reason))
        if marker:
            conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", ("proactive-email-notice:" + event, str(created).encode()))
            conn.execute("INSERT INTO meta(key,value) VALUES(?,?)",
                         ("proactive-email-rule:" + event, json.dumps({"created": created, "source": source}).encode()))


def _listed(client, **query):
    return client.get(ALERTS, params={"session_id": CHAT, **query}).json()


def test_alerts_list_this_chat_newest_first_with_source_and_expiry(harness):
    api, client, _, _, _ = harness
    now = time.time()
    _alert(api, "a" * 32, "Daniel moved the client review to 09:00", now - 300)
    _alert(api, "b" * 32, "Invoice 4471 is overdue", now - 100, source="billing")
    # Not this chat, owner or profile; a task result that is no alert; a call.
    _alert(api, "c" * 32, "Another chat", now, conversation="other-chat")
    _alert(api, "d" * 32, "Another owner", now, owner="someone")
    _alert(api, "e" * 32, "Another profile", now, profile="work")
    _alert(api, "f" * 32, "A scheduled job finished", now, marker=False)
    _alert(api, "9" * 32, "A call", now, kind="incoming_call")

    body = _listed(client)

    assert [row["notice_id"] for row in body["alerts"]] == ["b" * 32, "a" * 32]
    newest, oldest = body["alerts"]
    assert (newest["text"], newest["source"], newest["verdict"]) == ("Invoice 4471 is overdue", "Billing", "")
    assert newest["created_at"] == pytest.approx(now - 100) and newest["expires_at"] == int(now - 100 + 86400)
    assert oldest["source"] == "Email"
    assert body["session_id"] == CHAT and body["profile"] == "default" and body["next"] == ""


def test_alert_verdict_is_read_back_from_the_saved_feedback(harness):
    api, client, _, _, _ = harness
    now = time.time()
    first, second = "a" * 32, "b" * 32
    _alert(api, first, "Daniel Reyes / Client moved the review", now - 20)
    _alert(api, second, "Billing / Invoice 4471 overdue", now - 10)

    def verdicts():
        return {row["notice_id"]: row["verdict"] for row in _listed(client)["alerts"]}

    assert verdicts() == {first: "", second: ""}
    assert client.post(FEEDBACK, json={"notice_id": first, "verdict": "important"}).status_code == 200
    assert verdicts() == {first: "important", second: ""}
    # A new choice replaces the old one, and taking the note out of what Beacon learned opens the alert again.
    assert client.post(FEEDBACK, json={"notice_id": first, "verdict": "not_important"}).status_code == 200
    assert verdicts()[first] == "not_important"
    note = api.proactive.learned_notes(api.mobile, "owner", "default")[0]
    assert client.request("DELETE", "/api/plugins/beacon/mobile/proactive/learned", json={"note": note}).status_code == 200
    assert verdicts() == {first: "", second: ""}


def test_alerts_are_paged_and_bounded(harness):
    api, client, _, _, _ = harness
    now = time.time()
    ids = [f"{n:032x}" for n in range(1, 6)]
    for n, event in enumerate(ids):
        _alert(api, event, f"Alert {n}", now - 100 + n)

    first = _listed(client, limit=2)
    second = _listed(client, limit=2, before=first["next"])
    last = _listed(client, limit=2, before=second["next"])

    assert [row["notice_id"] for row in first["alerts"]] == [ids[4], ids[3]] and first["next"]
    assert [row["notice_id"] for row in second["alerts"]] == [ids[2], ids[1]] and second["next"]
    assert [row["notice_id"] for row in last["alerts"]] == [ids[0]] and last["next"] == ""
    assert len(_listed(client, limit=0)["alerts"]) == 1
    assert client.get(ALERTS, params={"session_id": CHAT, "before": -1}).status_code == 400


def test_alerts_of_a_chat_the_owner_does_not_own_are_refused(harness, monkeypatch):
    api, client, _, _, _ = harness
    _alert(api, "a" * 32, "Daniel moved the review", time.time(), conversation="foreign-chat")

    def verify(owner, provider, profile, stored, *args):
        if stored == "foreign-chat":
            raise LookupError("not owned")
        return {"messages": []}

    monkeypatch.setattr(api.native, "verify", verify)

    assert client.get(ALERTS, params={"session_id": "foreign-chat"}).status_code == 404
    assert _listed(client)["alerts"] == []


def test_an_alert_is_listed_after_it_expires_until_its_notice_is_pruned(harness):
    api, client, _, _, _ = harness
    now = time.time()
    _alert(api, "a" * 32, "Daniel moved the review", now - 90000, expires=now - 3600)

    assert _listed(client)["alerts"][0]["expires_at"] < now
    with api.mobile.connect() as conn:
        api.mobile.prune_notices(conn)
    assert _listed(client)["alerts"] == []
