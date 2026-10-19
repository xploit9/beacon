from types import SimpleNamespace

from test_mobile import harness, load_private


def test_managed_persona_retries_old_requests_and_requires_current_tool_evidence(harness):
    api, _, _, _, _ = harness
    persona = api.proactive.PERSONA.casefold()

    assert "do not deny capabilities based on conversation history" in persona
    assert "tool result in this turn" in persona
    assert "i couldn't verify" in persona
    assert "do it again rather than refuse" in persona


def test_nightly_memory_upkeep_corrects_stale_capabilities_and_preserves_owner_facts(harness):
    api, _, _, _, _ = harness
    sources = {signal: [] for signal in api.proactive.SIGNALS}
    setup = api.proactive.default_setup(sources)
    memory = next(spec for spec in api.proactive._specs(setup, sources) if spec["id"] == "nightly_memory")
    prompt = memory["prompt"].casefold()

    assert "missing capability" in prompt
    assert "stale tool/account state" in prompt
    assert "preferences, people, requests and facts the owner gave" in prompt


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


def test_live_context_callback_injects_managed_proactive_cron_runs(harness, monkeypatch):
    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    scope = {}
    monkeypatch.setattr(plugin, "current_profile", lambda cfg: "default")
    monkeypatch.setattr(plugin.native, "verify",
                        lambda *args: (_ for _ in ()).throw(LookupError("Cron principal")))
    monkeypatch.setattr(plugin, "_cron_row", lambda profile, session_id: {
        "source": "cron", "cron_job_id": "cypher-job",
    })
    monkeypatch.setattr(plugin.proactive, "_managed_ids", lambda *args: {"brief_email": "cypher-job"})
    monkeypatch.setattr(plugin.proactive, "enabled", lambda *args: True)
    monkeypatch.setattr(plugin.proactive, "_setup_complete", lambda *args: True)
    monkeypatch.setattr(live, "snapshot", lambda *args, **kwargs: scope.update(kwargs) or "live capabilities")

    result = plugin.native_live_context("cron-run")

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
    monkeypatch.setattr(plugin, "_cron_row", lambda profile, session_id: {
        "source": "cron", "cron_job_id": "unrelated-job",
    })
    monkeypatch.setattr(plugin.proactive, "_managed_ids", lambda *args: {"brief_email": "cypher-job"})
    monkeypatch.setattr(live, "snapshot", lambda *args: "must not be injected")

    assert plugin.native_live_context("unrelated-cron-run") is None


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


def test_live_snapshot_lists_current_sources_without_exposing_source_content(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    source = {"id": "mcp:mail", "label": "mail", "kind": "mcp"}
    monkeypatch.setattr(api.proactive, "status", lambda *args, **kwargs: {
        "enabled": True, "setup_complete": True,
        "signals": [{"id": "email", "label": "Email", "source": "mcp:mail", "sources": [source],
                     "health": {"state": "unknown", "checked_at": None}}],
    })
    monkeypatch.setattr(api.proactive, "_mcp_catalog", lambda: [
        {"_server": "mail", "description": "Private message body"},
        {"_server": "calendar", "description": "Private event body"},
        {"_server": "private-token", "description": "Sensitive configuration"},
    ])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))
    monkeypatch.setitem(sys.modules, "agent.redact",
                        SimpleNamespace(redact_sensitive_text=lambda value, **kwargs:
                                        value.replace("private-token", "[redacted]")))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive, job_scope=True)

    assert "mail" in context and "calendar" in context
    assert "unknown" in context and "tools and skills available in this turn" in context
    assert "try it again once" in context
    assert "limited to its enabled toolset" in context
    assert "Private message body" not in context
    assert "Private event body" not in context
    assert "private-token" not in context and "[redacted]" in context
    assert len(context) <= 600


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
    monkeypatch.setattr(api.proactive, "status",
                        lambda *args, **kwargs: (_ for _ in ()).throw(RuntimeError("discovery failed")))

    assert live.snapshot(api.mobile, "owner", "default", api.proactive) is None


def test_live_snapshot_preserves_proactive_error_state_and_check_time(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    monkeypatch.setattr(api.proactive, "status", lambda *args, **kwargs: {
        "enabled": True, "setup_complete": True,
        "signals": [{"id": "email", "label": "Email", "source": "mcp:mail", "sources": [],
                     "health": {"state": "error", "checked_at": 1700000000}}],
    })
    monkeypatch.setattr(api.proactive, "_mcp_catalog", lambda: [])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive)

    assert "error" in context
    assert "2023-11-14 22:13Z" in context


def test_live_snapshot_caps_large_catalogs_and_keeps_retry_rule(harness, monkeypatch):
    from contextlib import nullcontext
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    monkeypatch.setattr(api.proactive, "status", lambda *args, **kwargs: {
        "enabled": False, "setup_complete": False, "signals": [],
    })
    monkeypatch.setattr(api.proactive, "_mcp_catalog", lambda: [
        {"_server": f"source-{index}-" + "x" * 80} for index in range(30)
    ])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive, job_scope=True)

    assert len(context) <= 600
    assert context.endswith(live._RULE + " This scheduled Proactive run is limited to its enabled toolset.")
    assert "…" in context


def test_live_snapshot_shows_only_recent_successful_read_time_as_source_evidence(harness, monkeypatch):
    from contextlib import nullcontext
    import json
    import sys

    api, _, _, _, _ = harness
    plugin = load_private("__init__")
    live = getattr(plugin, "live_context", None)
    assert live is not None
    live._CACHE.clear()
    monkeypatch.setattr(live.time, "time", lambda: 1700000010)
    source = {"id": "mcp:mail", "label": "mail", "kind": "mcp"}
    monkeypatch.setattr(api.proactive, "status", lambda *args, **kwargs: {
        "enabled": True, "setup_complete": True,
        "signals": [{"id": "email", "label": "Email", "source": "mcp:mail", "sources": [source],
                     "health": {"state": "unknown", "checked_at": None}}],
    })
    monkeypatch.setattr(api.proactive, "_mcp_catalog", lambda: [{"_server": "mail"}])
    monkeypatch.setitem(sys.modules, "hermes_cli.web_server_profiles",
                        SimpleNamespace(_profile_scope=lambda profile: nullcontext()))
    with api.mobile.connect() as conn:
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)", (
            "source-evidence:owner:default:session:source", json.dumps({
                "source_id": "mcp:mail", "signal": "email", "tool_name": "mcp__mail__search",
                "item_hashes": ["opaque"], "observed_at": 1700000000,
            }).encode("utf-8"),
        ))

    context = live.snapshot(api.mobile, "owner", "default", api.proactive)

    assert "health unknown" in context
    assert "last successful read 2023-11-14 22:13Z" in context
    monkeypatch.setattr(live.time, "time", lambda: 1700000400)
    live._CACHE.clear()
    expired = live.snapshot(api.mobile, "owner", "default", api.proactive)
    assert "health unknown" in expired
    assert "last successful read" not in expired
