"""The agent is told how a file reaches the owner's phone: Hermes' own MEDIA: tag, in owned Beacon chats only."""
from types import SimpleNamespace

from test_mobile import harness, load_private


def _owned(plugin, monkeypatch, owned=("owned-chat", "second-chat")):
    def verify(*args):
        if args[3] not in owned:
            raise LookupError("Foreign owner")
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify", verify)
    _phone_with_file_cards(plugin)
    plugin._FILE_NOTE_SENT.clear()


def _phone_with_file_cards(plugin):
    """The owner's phone runs an app that shows file cards (it reports its version); test_app_version_gate covers the other cases."""
    device = plugin.mobile.link_device("owner", "basic", "Synthetic phone")["device_id"]
    plugin.mobile.touch_device("owner", "basic", "default", device, app_version="1.2.1")


def test_file_delivery_note_is_a_pre_llm_hook(harness):
    plugin = load_private("__init__")
    hooks = []
    plugin.register(SimpleNamespace(register_tool=lambda **kwargs: None,
                                     register_hook=lambda name, handler: hooks.append((name, handler))))
    assert "native_file_delivery_context" in [handler.__name__ for name, handler in hooks if name == "pre_llm_call"]


def test_file_delivery_note_uses_hermes_media_wording_once_per_owned_chat(harness, monkeypatch):
    plugin = load_private("__init__")
    _owned(plugin, monkeypatch)

    first = plugin.native_file_delivery_context("owned-chat")
    assert first == {"context": plugin.FILE_DELIVERY_NOTE}
    # One short sentence stating the surface as a fact, in Hermes' own framing of a note in the owner's turn and its desktop file wording.
    # It names no hint, terminal, tag or instruction, so a reply has nothing to reconcile in front of the owner.
    note = first["context"]
    assert note.startswith("[System: This conversation is shown in the Beacon phone app") and note.endswith("]")
    assert "MEDIA:/absolute/path/to/file" in note and "open, save or share" in note
    assert not any(word in note.lower() for word in ("terminal", "tui", "hint", "instruction", "intercept", "tag", "literal"))
    assert note.count(". ") == 0 and len(note) < 220
    # Never Hermes' own surface-switch prefix, which Hermes parses back as a change of interface.
    assert "now being answered on a different interface" not in note
    # Hermes replays the turn's context, so the same chat is not told again right away; another chat is.
    assert plugin.native_file_delivery_context("owned-chat") is None
    assert plugin.native_file_delivery_context("second-chat") == first


def test_file_delivery_note_is_repeated_after_the_window(harness, monkeypatch):
    plugin = load_private("__init__")
    _owned(plugin, monkeypatch)
    clock = [1000.0]
    monkeypatch.setattr(plugin.time, "monotonic", lambda: clock[0])

    assert plugin.native_file_delivery_context("owned-chat") is not None
    clock[0] += plugin.FILE_NOTE_REPEAT_SECONDS - 1
    assert plugin.native_file_delivery_context("owned-chat") is None
    clock[0] += 2
    assert plugin.native_file_delivery_context("owned-chat") is not None


def test_file_delivery_note_skips_foreign_and_unnamed_sessions(harness, monkeypatch):
    plugin = load_private("__init__")
    _owned(plugin, monkeypatch)

    assert plugin.native_file_delivery_context("foreign-chat") is None
    assert plugin.native_file_delivery_context(None) is None
    assert plugin.native_file_delivery_context("") is None
    assert "foreign-chat" not in plugin._FILE_NOTE_SENT


def test_file_delivery_note_checks_ownership_only_when_a_note_is_due(harness, monkeypatch):
    # REVIEW-RECV P3-3: the note goes once per 30 minutes, so the state.db ownership reads must not run on every turn.
    plugin = load_private("__init__")
    checks = []
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify", lambda *args: checks.append(args[3]))
    _phone_with_file_cards(plugin)
    plugin._FILE_NOTE_SENT.clear()

    notes = [plugin.native_file_delivery_context("owned-chat") for _ in range(10)]
    assert sum(note is not None for note in notes) == 1
    assert checks == ["owned-chat"]


def test_a_failed_ownership_check_does_not_silence_the_note(harness, monkeypatch):
    plugin = load_private("__init__")
    owned = {"yes": False}

    def verify(*args):
        if not owned["yes"]:
            raise LookupError("Not proven yet")
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify", verify)
    _phone_with_file_cards(plugin)
    plugin._FILE_NOTE_SENT.clear()

    assert plugin.native_file_delivery_context("owned-chat") is None
    owned["yes"] = True
    assert plugin.native_file_delivery_context("owned-chat") == {"context": plugin.FILE_DELIVERY_NOTE}
