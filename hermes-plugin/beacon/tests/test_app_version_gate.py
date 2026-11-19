"""The file note goes only to an owner whose phone shows file cards (REVIEW-RECV FU2-P3-4), and again when Hermes dropped it (FU2-P3-5);
summaries name a file even in code. An app with file cards reports its version (X-Beacon-App-Version) on Beacon requests; the
released 1.2.0 app reports none, so it is never told to expect MEDIA: lines it would show as raw paths."""
from test_mobile import harness, load_private  # noqa: F401  (fixture)


def _plugin(monkeypatch):
    plugin = load_private("__init__")
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify", lambda *args: None)
    plugin._FILE_NOTE_SENT.clear()
    getattr(plugin, "_FILE_NOTE_SEEN", set()).clear()
    return plugin


def test_a_phone_reports_its_app_version_on_beacon_requests(harness):
    api, client, cfg, _, _ = harness
    device = api.mobile.link_device("owner", "basic", "Synthetic phone")["device_id"]
    assert api.mobile.newest_app_version("owner", "basic") == ""
    client.get("/api/plugins/beacon/mobile/bootstrap", headers={"x-beacon-device-id": device, "x-beacon-app-version": "1.2.1"})
    assert api.mobile.newest_app_version("owner", "basic") == "1.2.1"
    # Not a version: ignored, the last good one stays.
    client.get("/api/plugins/beacon/mobile/bootstrap", headers={"x-beacon-device-id": device, "x-beacon-app-version": "1.2.1; drop table"})
    assert api.mobile.newest_app_version("owner", "basic") == "1.2.1"


def test_the_note_waits_for_a_phone_that_shows_file_cards(harness, monkeypatch):
    plugin = _plugin(monkeypatch)
    mobile = plugin.mobile
    # No phone, and then a phone whose app reports no version (the released 1.2.0): no note, so the agent keeps writing plain paths.
    assert plugin.native_file_delivery_context("owned-chat") is None
    old = mobile.link_device("owner", "basic", "Phone with 1.2.0")["device_id"]
    assert plugin.native_file_delivery_context("owned-chat") is None
    mobile.touch_device("owner", "basic", "default", old, app_version="1.2.1")
    assert plugin.native_file_delivery_context("owned-chat") == {"context": plugin.FILE_DELIVERY_NOTE}
    # The owner's newest phone decides: one seen later that reports nothing (an older app) holds the note back.
    plugin._FILE_NOTE_SENT.clear()
    newer = mobile.link_device("owner", "basic", "Second phone, older app")["device_id"]
    with mobile.connect() as conn:
        conn.execute("UPDATE beacon_devices SET last_seen=last_seen+600 WHERE id=?", (newer,))
    assert plugin.native_file_delivery_context("owned-chat") is None


def test_the_note_goes_again_when_hermes_dropped_it_from_the_context(harness, monkeypatch):
    plugin = _plugin(monkeypatch)
    # A phone with file cards (stubbed, so this test reads the same against a plugin from before the version gate).
    monkeypatch.setattr(plugin.mobile, "newest_app_version", lambda *args: "1.2.1", raising=False)

    first = plugin.native_file_delivery_context("owned-chat", conversation_history=[])
    assert first == {"context": plugin.FILE_DELIVERY_NOTE}
    # Still in what the model reads (Hermes keeps it on the turn's user row): nothing more.
    kept = [{"role": "user", "content": "Hi", "api_content": "Hi\n\n" + first["context"]}, {"role": "assistant", "content": "Hello"}]
    assert plugin.native_file_delivery_context("owned-chat", conversation_history=kept) is None
    # Hermes rewrote or compressed that turn and the note is gone: it goes again at once, not after 30 minutes.
    dropped = [{"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"}]
    assert plugin.native_file_delivery_context("owned-chat", conversation_history=dropped) == first


def test_a_turn_that_never_keeps_the_note_gets_it_once_per_window(harness, monkeypatch):
    # MoA and codex_app_server turns stamp no api_content, so the note is never "in context" there: the 30 minutes stay the floor.
    plugin = _plugin(monkeypatch)
    checks = []
    monkeypatch.setattr(plugin.native, "verify", lambda *args: checks.append(args[3]))
    monkeypatch.setattr(plugin.mobile, "newest_app_version", lambda *args: "1.2.1", raising=False)
    history = [{"role": "user", "content": "Hi"}, {"role": "assistant", "content": "Hello"}]
    notes = [plugin.native_file_delivery_context("owned-chat", conversation_history=history) for _ in range(10)]
    assert sum(note is not None for note in notes) == 1 and checks == ["owned-chat"]


def test_summaries_name_a_file_even_when_the_tag_is_in_code(harness):
    # A notification or the call's hand-over answer is a summary: no path on the computer, even for a tag the model put in inline code.
    api = harness[0]
    mobile = api.mobile
    assert mobile.named_files("Here: `MEDIA:/home/me/out/report.pdf`") == "Here: report.pdf"
    preview = api.reply_preview([{"role": "assistant", "text": "Here: `MEDIA:/home/me/out/report.pdf`"}])
    assert "report.pdf" in preview and "/home/me" not in preview
    assert mobile.named_files("Done: `MEDIA:/home/me/out/report.pdf`", after=mobile.CALL_FILE_NOTE) == "Done: report.pdf (sent to the chat)"
