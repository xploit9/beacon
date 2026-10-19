"""Small, profile-scoped capability context for Cypher's native turn hook."""
import json
import math
import re
import threading
import time


MAX_CONTEXT_CHARS = 600
CACHE_SECONDS = 60
_RULE = ("Your abilities are the tools and skills available in this turn, not what earlier messages, summaries, or memories say. "
         "If something failed before, try it again once and report the actual error.")
_CACHE = {}
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


def _catalog_names(rows, key, kind, selected):
    names = set()
    for row in rows:
        if not isinstance(row, dict):
            continue
        source_name = row.get(key)
        if not isinstance(source_name, str) or f"{kind}:{source_name}" in selected:
            continue
        label = _label(source_name)
        if label:
            names.add(label)
    return sorted(names)


def _recent_successes(mobile, owner, profile, source_ids, now):
    from cypher_observers import EVIDENCE_TTL

    prefix = f"source-evidence:{owner}:{profile}:"
    with mobile.connect() as conn:
        rows = conn.execute("SELECT value FROM meta WHERE substr(key,1,?)=?", (len(prefix), prefix)).fetchall()
    latest = {}
    for row in rows:
        try:
            record = json.loads(row[0])
        except (TypeError, ValueError, UnicodeError):
            continue
        source_id = record.get("source_id") if isinstance(record, dict) else None
        observed = record.get("observed_at") if isinstance(record, dict) else None
        if (source_id in source_ids and type(observed) in (int, float) and math.isfinite(observed)
                and 0 <= now - observed <= EVIDENCE_TTL and observed > latest.get(source_id, 0)):
            latest[source_id] = observed
    return latest


def _format(items, job_scope=False):
    prefix = "Live capabilities: "
    rule = _RULE + (" This scheduled Proactive run is limited to its enabled toolset." if job_scope else "")
    details = "; ".join(items) or "No sources are currently discoverable."
    room = MAX_CONTEXT_CHARS - len(prefix) - len(rule) - 1
    if len(details) > room:
        details = details[:max(0, room - 1)].rstrip(" ,;") + "…"
    return prefix + details + "\n" + rule


def _build_snapshot(mobile, owner, profile, proactive, job_scope=False):
    status = proactive.status(mobile, owner, profile)
    if not isinstance(status, dict) or not isinstance(status.get("signals"), list):
        raise ValueError("Proactive status is unavailable")

    items, selected, health_rows = [], set(), []
    if status.get("enabled") is True and status.get("setup_complete") is True:
        for signal in status["signals"]:
            if not isinstance(signal, dict):
                continue
            source_id = signal.get("source")
            if not isinstance(source_id, str) or not source_id:
                continue
            selected.add(source_id)
            source = next((row for row in signal.get("sources", ())
                           if isinstance(row, dict) and row.get("id") == source_id), {})
            signal_name = _label(signal.get("label") or signal.get("id"))
            source_name = _label(source.get("label") or source_id)
            health = signal.get("health")
            state = health.get("state") if isinstance(health, dict) else None
            if state not in ("ok", "error", "unknown"):
                state = "unknown"
            checked_at = health.get("checked_at") if isinstance(health, dict) else None
            health_rows.append((signal_name, source_name, source_id, state, checked_at))

    successful_at = _recent_successes(mobile, owner, profile, selected, time.time()) if selected else {}
    for signal_name, source_name, source_id, state, checked_at in health_rows:
        timestamp = _time_label(checked_at)
        detail = f"health {state}"
        if timestamp:
            detail += f" {timestamp}"
        elif state == "unknown" and source_id in successful_at:
            detail += f"; last successful read {_time_label(successful_at[source_id])}"
        items.append(f"{signal_name or 'Proactive'}: {source_name or 'selected source'} ({detail})")

    # Skills are already indexed in Hermes' system prompt; a ~100-name list would only be truncated here.
    from hermes_cli.web_server_profiles import _profile_scope
    with _profile_scope(profile):
        mcp_rows = proactive._mcp_catalog()
    if not isinstance(mcp_rows, list):
        raise ValueError("Profile source discovery is unavailable")

    mcp_names = _catalog_names(mcp_rows, "_server", "mcp", selected)
    if mcp_names:
        items.append("Other MCP (health unknown): " + ", ".join(mcp_names))
    return _format(items, job_scope)


def snapshot(mobile, owner, profile, proactive, job_scope=False):
    """Return a cached, redacted capability snapshot, or None when discovery fails."""
    key = (owner, profile, job_scope)
    now = time.monotonic()
    with _LOCK:
        cached = _CACHE.get(key)
        if cached and now - cached[0] < CACHE_SECONDS:
            return cached[1]
    try:
        context = _build_snapshot(mobile, owner, profile, proactive, job_scope)
    except Exception:
        return None
    if not isinstance(context, str) or not context:
        return None
    with _LOCK:
        _CACHE[key] = (time.monotonic(), context)
    return context
