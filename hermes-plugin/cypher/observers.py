"""Native lifecycle and source-evidence observers."""
import hashlib
import json
import math
import re
import time
import unicodedata
import uuid

EVIDENCE_TTL = 300
ALERT_RETENTION = 24 * 60 * 60
CALL_WINDOW = 60 * 60
CALL_LIMIT = 3
PRUNE_BATCH = 256
MAX_EVIDENCE_HASHES = 1024
CLAIM_FIELDS = {"id", "message_id", "messageid", "event_id", "eventid", "task_id", "taskid",
                "url", "uri", "link", "web_url", "subject", "title", "name", "summary"}


INSTRUCTION_MARKERS = ("ignore previous", "ignore all", "system prompt", "override policy", "new instructions",
                       "pre-approved", "preapproved", "send email", "mark as read", "tool_call")
CALLBACK_REASON_CHARACTERS = re.compile(r"""[\w .,!?'"():;/@#&+$%€-]*\Z""", re.UNICODE)


def normalize_callback_text(value, limit=240):
    if not isinstance(value, str):
        raise ValueError("Invalid callback reason")
    value = unicodedata.normalize("NFKC", value).translate(str.maketrans({
        "‘": "'", "’": "'", "“": '"', "”": '"', "–": "-", "—": "-", "…": "...",
    }))
    if len(value) > limit:
        raise ValueError("Invalid callback reason")
    return value


def check_instruction_markers(value):
    if any(term in value.casefold() for term in INSTRUCTION_MARKERS):
        raise ValueError("Instruction-like text cannot be relayed as trusted call guidance")


def reason_text(value, limit=240):
    """Every ring/notice reason passes here: one line of plain text, never instruction-shaped."""
    value = normalize_callback_text(value, limit)
    if any(c in "\r\n" or ord(c) < 32 for c in value) or not CALLBACK_REASON_CHARACTERS.fullmatch(value):
        raise ValueError("A callback reason must be a single line of plain text")
    check_instruction_markers(value)
    return value


def _number(value):
    try:
        value = float(value.decode("utf-8") if isinstance(value, bytes) else value)
        return value if math.isfinite(value) else None
    except (TypeError, ValueError, UnicodeError, OverflowError):
        return None


def prune_meta(conn, now):
    for key, value in conn.execute("SELECT key,value FROM meta WHERE key LIKE 'source-evidence:%' OR key LIKE 'mail-evidence:%' ORDER BY key LIMIT ?", (PRUNE_BATCH,)).fetchall():
        try:
            observed = json.loads(value).get("observed_at")
        except (AttributeError, TypeError, ValueError):
            observed = None
        if type(observed) not in (int, float) or not math.isfinite(observed) or now - observed > EVIDENCE_TTL:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))
    for key, value in conn.execute("SELECT key,value FROM meta WHERE key LIKE 'proactive-alert:%' OR key LIKE 'proactive-call:%' OR key LIKE 'mail-notified:%' ORDER BY key LIMIT ?", (PRUNE_BATCH,)).fetchall():
        created = _number(value)
        retention = CALL_WINDOW if key.startswith("proactive-call:") else ALERT_RETENTION
        if created is None or created > now or now - created > retention:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))
    for key, value in conn.execute("SELECT key,value FROM meta WHERE key LIKE 'completion:%'").fetchall():
        created = _number(value)
        if created is None:
            if value == b"notified":
                conn.execute("UPDATE meta SET value=? WHERE key=?", (str(now).encode(), key))
            else:
                conn.execute("DELETE FROM meta WHERE key=?", (key,))
        elif now - created > 86400:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))


def completion(mobile, native, cfg, profile, session_id, turn_id, completed=False, failed=False,
               interrupted=False, job_id=None, **kwargs):
    if (completed is not True and failed is not True) or interrupted or not session_id or not turn_id:
        return
    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or profile not in cfg.get("profiles", []):
        return
    row = native.verify(owner, provider, profile, session_id)
    kind = "failure" if failed is True else ("task_result" if row.get("source") == "cron" else "completion")
    key = "completion:" + owner + ":" + profile + ":" + session_id + ":" + str(turn_id)
    now = time.time()
    with mobile.LOCK, mobile.connect() as conn:
        prune_meta(conn, now)
        if conn.execute("SELECT 1 FROM meta WHERE key=?", (key,)).fetchone():
            return
        if conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] >= 10000:
            raise ValueError("Notification capacity reached")
        conn.execute("INSERT INTO meta VALUES(?,?)", (key, str(now).encode()))
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,job_id) VALUES(?,?,?,?,?,?,?,?)",
                     (uuid.uuid4().hex, owner, profile, kind, session_id, now, now + 86400,
                      job_id if isinstance(job_id, str) and mobile.ID.fullmatch(job_id) else ""))


def _digest(value):
    return hashlib.sha256(value.strip().casefold().encode("utf-8")).hexdigest()


def _claims(value, depth=0, budget=None):
    budget = [0] if budget is None else budget
    budget[0] += 1
    if depth > 5 or budget[0] > 512:
        return set()
    if isinstance(value, str):
        if len(value) > 65536:
            return set()
        try:
            parsed = json.loads(value)
        except (TypeError, ValueError):
            parsed = None
        if isinstance(parsed, (dict, list)):
            return _claims(parsed, depth + 1, budget)
        output = set()
        for line in value.splitlines()[:80]:
            line = line.strip()
            if 2 <= len(line) <= 256:
                output.add(_digest(line))
            output.update(_digest(url) for url in re.findall(r"https?://[^\s<>\"']+", line)[:8])
        return output
    if isinstance(value, dict):
        output = set()
        for key, child in list(value.items())[:100]:
            if isinstance(child, str) and key.casefold() in CLAIM_FIELDS and 1 <= len(child.strip()) <= 256:
                output.add(_digest(child))
            output.update(_claims(child, depth + 1, budget))
        return output
    if isinstance(value, list):
        output = set()
        for child in value[:100]:
            output.update(_claims(child, depth + 1, budget))
        return output
    return set()


def _source_error(value, depth=0, budget=None):
    budget = [0] if budget is None else budget
    budget[0] += 1
    if depth > 5 or budget[0] > 256:
        return True
    if isinstance(value, str):
        if len(value) > 65536:
            return True
        try:
            value = json.loads(value)
        except (TypeError, ValueError):
            return bool(re.match(r"(?i)\s*(?:error:|traceback \(most recent call last\))", value))
    if isinstance(value, list):
        return any(_source_error(child, depth + 1, budget) for child in value[:64])
    if not isinstance(value, dict):
        return False
    if value.get("isError") is True or value.get("is_error") is True or value.get("outcome_uncertain") is True:
        return True
    if value.get("exit_code") not in (None, 0, "0"):
        return True
    if value.get("error") not in (None, False, "", {}, []):
        return True
    for key in ("status_code", "http_status"):
        code = value.get(key)
        if (type(code) is int or isinstance(code, str) and code.isdecimal()) and 400 <= int(code) <= 599:
            return True
    children = [value[key] for key in ("result", "structuredContent", "structured_content") if key in value]
    blocks = value.get("content", [])
    if isinstance(blocks, list):
        children.extend(block.get("text") for block in blocks[:64]
                        if isinstance(block, dict) and block.get("type") == "text")
    return any(_source_error(child, depth + 1, budget) for child in children[:64])


def _evidence_key(owner, profile, session_id, source_id, signal):
    source = hashlib.sha256((source_id + "\0" + signal).encode("utf-8")).hexdigest()[:24]
    return f"source-evidence:{owner}:{profile}:{session_id}:{source}"


def source_observed(mobile, cfg, profile, session_id, signal, source_id, tool_name, result,
                    status, read_only_verified=None):
    if (status not in ("ok", "success") or not session_id or not isinstance(source_id, str)
            or profile not in cfg.get("profiles", [])):
        return False
    if source_id.startswith("mcp:") and read_only_verified is not True:
        return False
    if _source_error(result):
        return False
    owner = cfg.get("owner_id")
    if not owner or not isinstance(tool_name, str) or not tool_name:
        return False
    try:
        from cypher_proactive import SIGNALS, _setup, _setup_complete, enabled
        if not enabled(mobile, owner, profile) or not _setup_complete(mobile, owner, profile):
            return False
        setup = _setup(mobile, owner, profile, {name: [] for name in SIGNALS})
        if setup["signals"].get(signal) != source_id:
            return False
    except (ImportError, LookupError, RuntimeError, ValueError):
        return False
    claims = sorted(_claims(result))[:128]
    if not claims:
        return False
    now = time.time()
    key = _evidence_key(owner, profile, session_id, source_id, signal)
    with mobile.LOCK, mobile.connect() as conn:
        prune_meta(conn, now)
        previous = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        if previous:
            try:
                old = json.loads(previous[0])
                observed = old.get("observed_at") if isinstance(old, dict) else None
                old_hashes = old.get("item_hashes", []) if isinstance(old, dict) else []
                if (old.get("source_id") == source_id and old.get("signal") == signal
                        and type(observed) in (int, float) and math.isfinite(observed)
                        and 0 <= now - observed <= EVIDENCE_TTL and isinstance(old_hashes, list)):
                    claims = list(dict.fromkeys(
                        [value for value in old_hashes
                         if isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value)] + claims
                    ))[-MAX_EVIDENCE_HASHES:]
            except (AttributeError, TypeError, ValueError, UnicodeError):
                pass
        record = {"source_id": source_id, "signal": signal, "tool_name": tool_name,
                  "item_hashes": claims[-MAX_EVIDENCE_HASHES:], "observed_at": now}
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                     (key, json.dumps(record, separators=(",", ":")).encode()))
    return True


def _inside_quiet(now, setup):
    start, end = setup["schedule"]["quiet_start"], setup["schedule"]["quiet_end"]
    if start == end:
        return False
    minute = now.hour * 60 + now.minute
    first = int(start[:2]) * 60 + int(start[3:])
    last = int(end[:2]) * 60 + int(end[3:])
    return first <= minute < last if first < last else minute >= first or minute < last


def _notice(mobile, owner, profile, conversation, reason, alert_key, now):
    with mobile.LOCK, mobile.connect() as conn:
        prune_meta(conn, now.timestamp())
        if conn.execute("SELECT 1 FROM meta WHERE key=?", (alert_key,)).fetchone():
            return {"status": "duplicate"}
        mobile.prune_notices(conn)
        if conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] >= 10000:
            raise ValueError("Notification capacity reached")
        event = uuid.uuid4().hex
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)",
                     (event, owner, profile, "task_result", conversation, now.timestamp(),
                      now.timestamp() + 86400, reason[:240]))
        conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", (alert_key, str(now.timestamp()).encode()))
    return {"status": "notified", "event_id": event}


def email_notice(mobile, native, cfg, profile, session_id, args, now):
    from cypher_proactive import SIGNALS, _setup, _setup_complete, enabled, list_requests

    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or profile not in cfg.get("profiles", []) or not session_id:
        raise ValueError("Owned proactive session required")
    if not isinstance(args, dict) or set(args) != {"request_id", "signal", "source_id", "item_id", "reason"}:
        raise ValueError("Request id, signal, source id, item id, and reason are required")
    request_id, signal, source_id, item_id, reason = (args.get("request_id"), args.get("signal"),
                                                       args.get("source_id"), args.get("item_id"), args.get("reason"))
    if not isinstance(request_id, str) or not mobile.ID.fullmatch(request_id):
        raise ValueError("Invalid Proactive request id")
    if not isinstance(source_id, str) or len(source_id) > 256:
        raise ValueError("Invalid source id")
    if signal not in ("email", "calendar", "tasks", "news"):
        raise ValueError("Invalid Proactive signal")
    if not isinstance(item_id, str) or not 1 <= len(item_id.strip()) <= 256:
        raise ValueError("A source-returned item id, title, or URL is required")
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("A brief reason is required")
    reason = reason_text(reason)
    setup = _setup(mobile, owner, profile, {name: [] for name in SIGNALS})
    if not enabled(mobile, owner, profile) or not _setup_complete(mobile, owner, profile):
        raise ValueError("Proactive is not enabled")
    if setup["signals"].get(signal) != source_id:
        raise ValueError("Source is not selected for this profile")
    request = next((row for row in list_requests(mobile, owner, profile)
                    if row.get("id") == request_id and row.get("enabled") is True
                    and row.get("signal") in (None, signal)), None)
    if not request:
        raise ValueError("No enabled request matches this selected signal")
    evidence_key = _evidence_key(owner, profile, session_id, source_id, signal)
    with mobile.connect() as conn:
        row = conn.execute("SELECT value FROM meta WHERE key=?", (evidence_key,)).fetchone()
    try:
        proof = json.loads(row[0]) if row else None
        observed = proof.get("observed_at") if proof else None
    except (AttributeError, TypeError, ValueError):
        proof, observed = None, None
    if (not proof or type(observed) not in (int, float) or not math.isfinite(observed)
            or not 0 <= now.timestamp() - observed <= EVIDENCE_TTL
            or proof.get("source_id") != source_id or proof.get("signal") != signal
            or _digest(item_id) not in proof.get("item_hashes", [])):
        raise ValueError("Fresh same-session evidence from the selected source is required")

    stored = cfg.get("scheduled_callback_conversation_id")
    if not isinstance(stored, str) or not stored.strip():
        raise ValueError("Choose an owned assistant conversation before enabling Proactive")
    native.verify(owner, provider, profile, stored)
    request_label = f"Request {request_id}: {request['text'][:100]}"
    relay_reason = (request_label + "; " + reason.strip())[:240]
    dedupe = "proactive-alert:" + owner + ":" + profile + ":" + request_id + ":" + _digest(source_id + "\0" + item_id)
    if request["action"] == "call":
        from cypher_mobile import invitation
        with mobile.LOCK:
            with mobile.connect() as conn:
                prune_meta(conn, now.timestamp())
                if conn.execute("SELECT 1 FROM meta WHERE key=?", (dedupe,)).fetchone():
                    return {"status": "duplicate"}
                prefix = f"proactive-call:{owner}:{profile}:"
                count = sum((_number(row[0]) or 0) > now.timestamp() - CALL_WINDOW
                            for row in conn.execute("SELECT value FROM meta WHERE substr(key,1,?)=?",
                                                    (len(prefix), prefix)))
            quiet_blocked = _inside_quiet(now, setup) and request.get("night") is not True
            callbacks_ready = cfg.get("callback_tool_enabled") is True and cfg.get("scheduled_callbacks_enabled") is True
            if not quiet_blocked and callbacks_ready and count < CALL_LIMIT:
                event = invitation(owner, profile, stored, relay_reason)
                with mobile.connect() as conn:
                    conn.execute("INSERT INTO meta(key,value) VALUES(?,?)",
                                 ("proactive-call:" + owner + ":" + profile + ":" + uuid.uuid4().hex,
                                  str(now.timestamp()).encode()))
                    conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", (dedupe, str(now.timestamp()).encode()))
                return {"status": "pending", "event_id": event, "conversation_id": stored}
    return _notice(mobile, owner, profile, stored, relay_reason, dedupe, now)
