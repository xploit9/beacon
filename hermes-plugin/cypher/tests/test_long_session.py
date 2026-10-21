import sys
from contextlib import nullcontext
from types import SimpleNamespace

from test_mobile import harness, load_private


def _install_profile_db(monkeypatch, session_id, row, identity):
    db = SimpleNamespace(get_conversation_root=lambda requested: requested,
                         get_session=lambda requested: row if requested == session_id else None,
                         cron_run_identity=lambda requested: identity if requested == session_id else None)
    server = SimpleNamespace(_profile_db=lambda scope: nullcontext(db))
    monkeypatch.setitem(sys.modules, "tui_gateway", SimpleNamespace(server=server))


def test_managed_persona_retries_old_requests_and_requires_current_tool_evidence(harness):
    api, _, _, _, _ = harness
    persona = api.proactive.PERSONA.casefold()

    assert "do not deny capabilities based on conversation history" in persona
    assert "tool result in this turn" in persona
    assert "i couldn't verify" in persona
    assert "do it again rather than refuse" in persona


def test_catalog_names_uses_three_argument_contract(harness):
    live = load_private("__init__").live_context

    assert live._catalog_names([{"_server": "mail"}], "_server", "mcp") == ["mail"]


def test_nightly_memory_upkeep_corrects_stale_capabilities_and_preserves_owner_facts(harness):
    api, _, _, _, _ = harness
    setup = api.proactive.default_setup()
    memory = next(spec for spec in api.proactive._specs(setup) if spec["id"] == "nightly_memory")
    prompt = memory["prompt"].casefold()

    assert "missing capability" in prompt
    assert "stale tool/account state" in prompt
    assert "important email notes" in prompt
    assert "never save transcripts" in prompt


def test_live_capability_context_uses_pre_llm_hook_without_replacing_voice_context(harness):
    plugin = load_private("__init__")
    hooks = []
    plugin.register(SimpleNamespace(register_tool=lambda **kwargs: None,
                                     register_hook=lambda name, handler: hooks.append((name, handler))))

    names = [handler.__name__ for name, handler in hooks if name == "pre_llm_call"]
    assert "native_voice_context" in names
    assert "native_live_context" in names


def test_live_context_callback_injects_only_a_verified_owner_conversation(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    scope = {}
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify", lambda *args: None)
    monkeypatch.setattr(live, "snapshot", lambda *args, **kwargs: scope.update(kwargs) or "live capabilities")

    result = plugin.native_live_context("owned-chat")

    assert result == {"context": "live capabilities"}
    assert scope["job_scope"] is False
    assert scope["session_id"] == "owned-chat"


def test_live_context_callback_injects_managed_proactive_cron_runs(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    scope = {}
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify",
                        lambda *args: (_ for _ in ()).throw(LookupError("Cron principal")))
    session_id = "local-cypher-run"
    _install_profile_db(monkeypatch, session_id, {"id": session_id, "source": "cron"},
                        {"cron_job_id": "cypher-job", "cron_execution_id": "run-1"})
    monkeypatch.setattr(plugin.proactive, "_managed_ids", lambda *args: {"watch_email": "cypher-job"})
    monkeypatch.setattr(plugin.proactive, "_managed_id", lambda *args: "cypher-job")
    monkeypatch.setattr(plugin.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(plugin.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(live, "snapshot", lambda *args, **kwargs: scope.update(kwargs) or "live capabilities")

    result = plugin.native_live_context(session_id)

    assert result == {"context": "live capabilities"}
    assert scope["job_scope"] is True


def test_live_context_callback_skips_unmanaged_cron_runs(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify",
                        lambda *args: (_ for _ in ()).throw(LookupError("Cron principal")))
    session_id = "local-unrelated-run"
    _install_profile_db(monkeypatch, session_id, {"id": session_id, "source": "cron"},
                        {"cron_job_id": "unrelated-job", "cron_execution_id": "run-2"})
    monkeypatch.setattr(plugin.proactive, "_managed_ids", lambda *args: {"watch_email": "cypher-job"})
    monkeypatch.setattr(live, "snapshot", lambda *args: "must not be injected")

    assert plugin.native_live_context(session_id) is None


def test_live_context_callback_skips_unowned_interactive_sessions(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify",
                        lambda *args: (_ for _ in ()).throw(LookupError("Foreign owner")))
    monkeypatch.setattr(plugin, "_cron_row", lambda profile, session_id: {"source": "chat"})
    monkeypatch.setattr(live, "snapshot", lambda *args: "must not be injected")

    assert plugin.native_live_context("foreign-chat") is None


def test_live_snapshot_uses_registered_mcp_toolsets_without_discovery(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    live._SENT.clear()
    setup = api.proactive.default_setup()
    monkeypatch.setattr(api.proactive, "_setup", lambda *args: setup)
    monkeypatch.setattr(api.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(api.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(api.proactive, "health", lambda *args: {"state": "unknown", "checked_at": None, "message": "Not checked yet"})
    monkeypatch.setattr(api.proactive, "learned_notes", lambda *args: [])
    fail = lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("slow discovery path used"))
    for name in ("status", "_cron_helpers", "_profile_timezone"):
        monkeypatch.setattr(api.proactive, name, fail)
    from tools.registry import registry
    monkeypatch.setattr(registry, "get_all_entries", lambda: [
        SimpleNamespace(toolset="mcp-mail"),
        SimpleNamespace(toolset="mcp-calendar"),
        SimpleNamespace(toolset="mcp-private-token"),
    ])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))
    monkeypatch.setitem(sys.modules, "agent.redact",
                        SimpleNamespace(redact_sensitive_text=lambda value, **kwargs:
                                        value.replace("private-token", "[redacted]")))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive, job_scope=True,
                            session_id="snapshot-session")

    assert "Proactive important email" in context and "Other MCP" in context
    assert "unknown" in context and "tools and skills available in this turn" in context
    assert "try it again once" in context
    assert "Proactive email watch is read-only" in context
    assert "Private message body" not in context
    assert "Private event body" not in context
    assert "private-token" not in context and "[redacted]" in context
    assert len(context) <= 600


def test_live_snapshot_deduplicates_unchanged_notes_per_session_and_bounds_cache(harness, monkeypatch):
    api, _, _, _, _ = harness
    live = load_private("__init__").live_context
    live._CACHE.clear()
    live._SENT.clear()
    clock = [100.0]
    monkeypatch.setattr(live.time, "monotonic", lambda: clock[0])
    note = ["first note"]
    monkeypatch.setattr(live, "_build_snapshot", lambda *args, **kwargs: note[0])

    assert live.snapshot(api.mobile, "owner", "default", api.proactive, session_id="conversation") == "first note"
    assert live.snapshot(api.mobile, "owner", "default", api.proactive, session_id="conversation") is None
    note[0] = "updated note"
    clock[0] += live.CACHE_SECONDS + 1
    assert live.snapshot(api.mobile, "owner", "default", api.proactive, session_id="conversation") == "updated note"
    assert live.snapshot(api.mobile, "owner", "default", api.proactive, session_id="conversation") is None
    clock[0] += 1800
    assert live.snapshot(api.mobile, "owner", "default", api.proactive, session_id="conversation") == "updated note"

    for index in range(513):
        live.snapshot(api.mobile, "owner", "default", api.proactive, session_id=f"session-{index}")
    assert len(live._SENT) == 512
    assert "conversation" not in live._SENT


def test_live_snapshot_cache_is_profile_scoped_and_expires_after_one_minute(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    now = [100.0]
    calls = []
    monkeypatch.setattr(live.time, "monotonic", lambda: now[0])
    monkeypatch.setattr(live, "_build_snapshot",
                        lambda *args: calls.append((args[1], args[2], args[4])) or "snapshot")

    assert live.snapshot(api.mobile, "owner", "default", api.proactive) == "snapshot"
    assert live.snapshot(api.mobile, "owner", "default", api.proactive) == "snapshot"
    assert live.snapshot(api.mobile, "owner", "work", api.proactive) == "snapshot"
    assert live.snapshot(api.mobile, "other-owner", "default", api.proactive) == "snapshot"
    assert live.snapshot(api.mobile, "owner", "default", api.proactive, job_scope=True) == "snapshot"
    now[0] += 61
    assert live.snapshot(api.mobile, "owner", "default", api.proactive) == "snapshot"

    assert calls == [
        ("owner", "default", False), ("owner", "work", False),
        ("other-owner", "default", False), ("owner", "default", True),
        ("owner", "default", False),
    ]


def test_live_snapshot_fails_open_when_source_discovery_raises(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    monkeypatch.setattr(api.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(api.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(api.proactive, "learned_notes",
                        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("memory unavailable")))

    assert live.snapshot(api.mobile, "owner", "default", api.proactive) is None


def test_live_snapshot_uses_saved_selection_without_status_probe(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    live._SENT.clear()
    setup = api.proactive.default_setup()
    monkeypatch.setattr(api.proactive, "_setup", lambda *args: setup)
    monkeypatch.setattr(api.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(api.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(api.proactive, "health", lambda *args: {"state": "unknown", "checked_at": None, "message": "Not checked yet"})
    monkeypatch.setattr(api.proactive, "learned_notes", lambda *args: [])
    fail = lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("status discovery used"))
    monkeypatch.setattr(api.proactive, "status", fail)
    from tools.registry import registry
    monkeypatch.setattr(registry, "get_all_entries", lambda: [])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive)

    assert "Proactive important email: unknown" in context
    assert "2023-11-14 22:13Z" not in context


def test_live_snapshot_caps_large_catalogs_and_keeps_retry_rule(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    live._SENT.clear()
    monkeypatch.setattr(api.proactive, "_setup",
                        lambda *args: api.proactive.default_setup())
    monkeypatch.setattr(api.proactive, "enabled", lambda *args: False)
    monkeypatch.setattr(api.proactive, "_setup_complete", lambda *args: False)
    monkeypatch.setattr(api.proactive, "learned_notes", lambda *args: [])
    monkeypatch.setattr(api.proactive, "status",
                        lambda *args, **kwargs: (_ for _ in ()).throw(AssertionError("status discovery used")))
    from tools.registry import registry
    monkeypatch.setattr(registry, "get_all_entries", lambda: [
        SimpleNamespace(toolset=f"mcp-source-{index}-" + "x" * 80) for index in range(30)
    ])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive, job_scope=True)

    assert len(context) <= 600
    assert context.endswith(live._RULE + " This Proactive email watch is read-only; use only this profile's tools and its email notice/check tools.")
    assert "…" in context


def test_live_snapshot_shows_watch_health_and_learned_count(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    live._SENT.clear()
    monkeypatch.setattr(live.time, "time", lambda: 1700000010)
    setup = api.proactive.default_setup()
    monkeypatch.setattr(api.proactive, "_setup", lambda *args: setup)
    monkeypatch.setattr(api.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(api.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(api.proactive, "health", lambda *args: {
        "state": "ok", "checked_at": 1700000000, "message": "Read complete"})
    monkeypatch.setattr(api.proactive, "learned_notes", lambda *args: ["important", "not important"])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))
    from tools.registry import registry
    monkeypatch.setattr(registry, "get_all_entries", lambda: [SimpleNamespace(toolset="mcp-mail")])

    context = live.snapshot(api.mobile, "owner", "default", api.proactive)

    assert "Proactive important email: ok" in context
    assert "2 alerts the owner marked" in context
