"""Small, profile-scoped capability context for Cypher's native turn hook."""
import json
import math
import re
import threading
import time


MAX_CONTEXT_CHARS = 600
CACHE_SECONDS = 60
NOTE_REPEAT_SECONDS = 30 * 60
MAX_SENT_SESSIONS = 512
_RULE = ("Your abilities are the tools and skills available in this turn, not what earlier messages, summaries, or memories say. "
         "If something failed before, try it again once and report the actual error.")
_CACHE = {}
_SENT = {}
_LOCK = threading.Lock()


def _label(value):
    if not isinstance(value, str):
        return None
    value = " ".join(value.split())
    if not value:
        return None
    from agent.redact import redact_sensitive_text
    value = redact_sensitive_text(value[:120], force=True, redact_url_credentials=True)
    return " ".join(re.sub(r"[\x00-\x1f\x7f]", " ", value).split())[:60] or None


def _time_label(value):
    if type(value) not in (int, float) or not math.isfinite(value) or value <= 0:
        return ""
    return time.strftime("%Y-%m-%d %H:%MZ", time.gmtime(value))


def _catalog_names(rows, key, kind):
    names = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        source_name = row.get(key)
        if not isinstance(source_name, str):
            continue
        label = _label(source_name)
        if label:
            names.add(label)
    return sorted(names)


def _format(items, job_scope=False):
    prefix = "Live capabilities: "
    rule = _RULE + (" This Proactive email watch is read-only; use only this profile's tools and its email notice/check tools." if job_scope else "")
    details = "; ".join(items) or "No sources are selected."
    room = MAX_CONTEXT_CHARS - len(prefix) - len(rule) - 1
    if len(details) > room:
        details = details[:max(0, room - 1)].rstrip(" ,;") + "…"
    return prefix + details + "\n" + rule


def _build_snapshot(mobile, owner, profile, proactive, job_scope=False):
    setup = proactive._setup(mobile, owner, profile)
    items = []
    if proactive.enabled(mobile, owner, profile) and proactive._setup_complete(mobile, owner, profile):
        health = proactive.health(mobile, owner, profile, setup)
        checked = _time_label(health.get("checked_at"))
        detail = f"{health['state']}" + (f"; last checked {checked}" if checked else "")
        notes = proactive.learned_notes(profile)
        items.append(f"Proactive important email: {detail}; {len(notes)} owner importance notes in Hermes memory")

    from hermes_cli.web_server_profiles import _profile_scope
    from tools.registry import registry
    with _profile_scope(profile):
        mcp_rows = [{"_server": entry.toolset[4:]} for entry in registry.get_all_entries()
                    if isinstance(entry.toolset, str) and entry.toolset.startswith("mcp-")]
    mcp_names = _catalog_names(mcp_rows, "_server", "mcp")
    if mcp_names:
        items.append("Other MCP (health unknown): " + ", ".join(mcp_names))
    return _format(items, job_scope)


def snapshot(mobile, owner, profile, proactive, job_scope=False, session_id=None):
    """Return a cached, redacted capability snapshot, or None when local state is unavailable."""
    key = (owner, profile, job_scope)
    now = time.monotonic()
    with _LOCK:
        cached = _CACHE.get(key)
    if cached and now - cached[0] < CACHE_SECONDS:
        context = cached[1]
    else:
        try:
            context = _build_snapshot(mobile, owner, profile, proactive, job_scope)
        except Exception:
            return None
        if not isinstance(context, str) or not context:
            return None
        with _LOCK:
            _CACHE[key] = (time.monotonic(), context)
    if session_id:
        with _LOCK:
            sent = _SENT.get(session_id)
            now = time.monotonic()
            if sent and sent[:2] == (key, context) and now - sent[2] < NOTE_REPEAT_SECONDS:
                return None
            _SENT.pop(session_id, None)
            _SENT[session_id] = (key, context, now)
            while len(_SENT) > MAX_SENT_SESSIONS:
                _SENT.pop(next(iter(_SENT)))
    return context
