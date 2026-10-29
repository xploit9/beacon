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
CLAIM_FIELDS = {"id", "message_id", "messageid", "thread_id", "threadid", "event_id", "eventid", "task_id", "taskid",
                "url", "uri", "link", "web_url", "subject", "title", "name", "summary"}
_TEXT_IDENTIFIER = re.compile(r"""(?i)^\s*(?:[-*]\s*)?(?:message[_ -]?id|thread[_ -]?id|event[_ -]?id|\bid\b)\s*[:=]\s*[\"'<]?([A-Za-z0-9._:-]{1,256})[\"'>]?\s*$""")
_TEXT_BODY_START = re.compile(r"(?i)^\s*(?:(?:message|email)\s+)?(?:body|content)\s*:")
_TEXT_BODY_FIELDS = frozenset({'body','message_body','email_body','body_text','plain_body','html_body','raw_body'})


# An email seen again in a later run is normal; the plain message keeps a watch run from reporting it as a failure.
DUPLICATE_ALERT = {"status": "duplicate", "message": "The owner already has this alert. Nothing more to do; this is not an error."}


INSTRUCTION_MARKERS = ("ignore previous", "ignore all", "system prompt", "override policy", "new instructions",
                       "pre-approved", "preapproved", "send email", "mark as read", "tool_call")
# Plain words, prices in common currencies and degrees; no markup or symbols that could carry instructions.
CALLBACK_REASON_CHARACTERS = re.compile(r"""[\w .,!?'"():;/@#&+$%€£¥₹₩₽₺°-]*\Z""", re.UNICODE)


def normalize_callback_text(value, limit=240):
    if not isinstance(value, str):
        raise ValueError("Invalid callback reason")
    value = unicodedata.normalize("NFKC", value).translate(str.maketrans({
        "‘": "'", "’": "'", "“": '"', "”": '"', "–": "-", "—": "-", "…": "...", "→": " to ",
    }))
    value = re.sub(" {2,}", " ", value)
    if len(value) > limit:
        raise ValueError("Invalid callback reason")
    return value


def _squashed(value):
    return re.sub(r"[\W_]+", "", value.casefold())


def check_instruction_markers(value):
    # Compare letters and digits only, so "ignore-previous" or "system  prompt" still match.
    if any(_squashed(term) in _squashed(value) for term in INSTRUCTION_MARKERS):
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
    # A caller reads its clock before it waits for the write lock, so a marker a faster request just committed can
    # be dated after that stale reading; only a marker ahead of the live clock is corrupt.
    horizon = max(now, time.time())
    for key, value in conn.execute("SELECT key,value FROM meta WHERE key LIKE 'email-evidence:%' OR key LIKE 'source-evidence:%' OR key LIKE 'mail-evidence:%' ORDER BY key LIMIT ?", (PRUNE_BATCH,)).fetchall():
        try:
            observed = json.loads(value).get("observed_at")
        except (AttributeError, TypeError, ValueError):
            observed = None
        if type(observed) not in (int, float) or not math.isfinite(observed) or now - observed > EVIDENCE_TTL:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))
    for key, value in conn.execute("SELECT key,value FROM meta WHERE key LIKE 'proactive-alert:%' OR key LIKE 'proactive-call:%' OR key LIKE 'proactive-email-notice:%' OR key LIKE 'proactive-email-rule:%' OR key LIKE 'mail-notified:%' ORDER BY key LIMIT ?", (PRUNE_BATCH,)).fetchall():
        created = _number(value)
        if key.startswith("proactive-email-rule:"):
            try:
                created = _number(json.loads(value).get("created"))
            except (AttributeError, TypeError, ValueError):
                created = None
            # History can outlive its invitation; retain source provenance for its lifetime, including feedback retries.
            if conn.execute("SELECT 1 FROM call_log WHERE email_notice_id=?",
                            (key[len("proactive-email-rule:"):],)).fetchone():
                continue
        retention = CALL_WINDOW if key.startswith("proactive-call:") else ALERT_RETENTION
        if created is None or created > horizon or now - created > retention:
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


# Hermes' on_session_end hook passes no turn_id; that call has no turn to report and returns quietly.
def completion(mobile, native, cfg, profile, session_id, turn_id=None, completed=False, failed=False,
               interrupted=False, job_id=None, **kwargs):
    if (completed is not True and failed is not True) or interrupted or not session_id or not turn_id:
        return
    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or not _profile_allowed(cfg,profile):
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


def _claims(value, depth=0, budget=None, text_ids=False):
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
            return _claims(parsed, depth + 1, budget, text_ids)
        output = set()
        for line in value.splitlines()[:80]:
            if text_ids and _TEXT_BODY_START.match(line): break
            line = line.strip()
            if 2 <= len(line) <= 256:
                output.add(_digest(line))
            output.update(_digest(url) for url in re.findall(r"https?://[^\s<>\"']+", line)[:8])
            if text_ids and (match:=_TEXT_IDENTIFIER.fullmatch(line)):
                output.add(_digest(match.group(1)))
        return output
    if isinstance(value, dict):
        output = set()
        for key, child in list(value.items())[:100]:
            if text_ids and key.casefold() in _TEXT_BODY_FIELDS:
                continue
            if isinstance(child, str) and key.casefold() in CLAIM_FIELDS and 1 <= len(child.strip()) <= 256:
                output.add(_digest(child))
            output.update(_claims(child, depth + 1, budget, text_ids))
        return output
    if isinstance(value, list):
        output = set()
        for child in value[:100]:
            output.update(_claims(child, depth + 1, budget, text_ids))
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


def _evidence_key(owner, profile, session_id):
    return f"email-evidence:{owner}:{profile}:{session_id}"

def _profile_allowed(cfg,profile):
    return 'profiles' not in cfg or isinstance(cfg['profiles'],list) and profile in cfg['profiles']


def _tool_sources(tool_name):
    from beacon_proactive import source_name
    names = [tool_name]
    if tool_name.startswith("mcp__"):
        names.append(tool_name.split("__", 2)[1])
    try:
        from tools.registry import registry
        toolset = registry.get_toolset_for_tool(tool_name)
        if toolset:
            names.append(toolset.removeprefix("mcp-"))
    except (ImportError, AttributeError, KeyError):
        pass
    # Match the existing compact catalogue's mail/Workspace labels as well as native source ids.
    words = set(re.findall(r"[a-z0-9]+", tool_name.casefold()))
    if words & {"mail", "email", "gmail", "gmai", "outlook", "himalaya"} or tool_name == "terminal":
        names.extend(("email", "gmail"))
    if "google_workspace" in tool_name.casefold():
        names.append("Google Workspace")
    output = set()
    for name in names:
        try:
            output.add(source_name(name))
        except ValueError:
            continue
    return sorted(output)


def source_observed(mobile, cfg, profile, session_id, tool_name, result, status):
    if (status not in ("ok", "success") or not session_id or not _profile_allowed(cfg,profile)):
        return False
    if _source_error(result):
        return False
    owner = cfg.get("owner_id")
    if not owner or not isinstance(tool_name, str) or not tool_name or tool_name.startswith("beacon_"):
        return False
    try:
        from beacon_proactive import _setup_complete, enabled
        if not enabled(mobile, owner, profile) or not _setup_complete(mobile, owner, profile):
            return False
    except (ImportError, LookupError, RuntimeError, ValueError):
        return False
    text_ids = any(token in tool_name.casefold() for token in ("gmail", "google_workspace", "himalaya", "email", "mail"))
    claims = sorted(_claims(result, text_ids=text_ids))[:128]
    if not claims:
        return False
    now = time.time()
    key = _evidence_key(owner, profile, session_id)
    with mobile.LOCK, mobile.connect() as conn:
        prune_meta(conn, now)
        previous = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        reads = []
        if previous:
            try:
                old = json.loads(previous[0])
                # Older aggregate hashes mix sources; only individually attributed reads can survive an upgrade.
                reads = [read for read in old.get("reads", [])
                         if type(read.get("observed_at")) in (int, float)
                         and 0 <= now - read["observed_at"] <= EVIDENCE_TTL]
            except (AttributeError, TypeError, ValueError, UnicodeError):
                pass
        reads.append({"tool_name": tool_name, "sources": _tool_sources(tool_name),
                      "item_hashes": claims, "observed_at": now})
        while len(reads) > 64 or sum(len(read.get("item_hashes", [])) for read in reads) > MAX_EVIDENCE_HASHES:
            reads.pop(0)
        record = {"reads": reads, "observed_at": now}
        conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                     (key, json.dumps(record, separators=(",", ":")).encode()))
    return True


def _inside_quiet(now, setup):
    start, end = setup["quiet_start"], setup["quiet_end"]
    if start == end:
        return False
    minute = now.hour * 60 + now.minute
    first = int(start[:2]) * 60 + int(start[3:])
    last = int(end[:2]) * 60 + int(end[3:])
    return first <= minute < last if first < last else minute >= first or minute < last


def _notice(mobile, owner, profile, conversation, reason, alert_key, now, email_alert=False, source="email", *, conn=None):
    from contextlib import nullcontext
    with mobile.LOCK, (mobile.connect() if conn is None else nullcontext(conn)) as conn:
        prune_meta(conn, now.timestamp())
        if conn.execute("SELECT 1 FROM meta WHERE key=?", (alert_key,)).fetchone():
            return dict(DUPLICATE_ALERT)
        mobile.prune_notices(conn)
        if conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] >= 10000:
            raise ValueError("Notification capacity reached")
        event = uuid.uuid4().hex
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason) VALUES(?,?,?,?,?,?,?,?)",
                     (event, owner, profile, "task_result", conversation, now.timestamp(),
                      now.timestamp() + 86400, reason[:240]))
        conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", (alert_key, str(now.timestamp()).encode()))
        if email_alert:
            conn.execute("INSERT INTO meta(key,value) VALUES(?,?)",
                         ("proactive-email-notice:" + event, str(now.timestamp()).encode()))
            conn.execute("INSERT INTO meta(key,value) VALUES(?,?)",
                         ("proactive-email-rule:" + event,
                          json.dumps({"created": now.timestamp(), "source": source}).encode()))
    return {"status": "notified", "event_id": event}


def email_notice(mobile, native, cfg, profile, session_id, args, now):
    if not isinstance(args, dict) or "source" in args:
        raise ValueError("Item id and reason are required; request_id is optional")
    return event_notice(mobile, native, cfg, profile, session_id, {**args, "source": "email"}, now)


def event_notice(mobile, native, cfg, profile, session_id, args, now):
    from beacon_proactive import EMAIL_RULE_PREFIX, _setup, _setup_complete, enabled, list_requests, source_name

    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or not _profile_allowed(cfg,profile) or not session_id:
        raise ValueError("Owned proactive session required")
    if not isinstance(args, dict) or set(args) - {"request_id", "source", "item_id", "reason"} or not {"source", "item_id", "reason"} <= set(args):
        raise ValueError("Source, item id and reason are required; request_id is optional")
    source = source_name(args["source"])
    request_id, item_id, reason = args.get("request_id"), args.get("item_id"), args.get("reason")
    if request_id is not None and (not isinstance(request_id, str) or not mobile.ID.fullmatch(request_id)):
        raise ValueError("Invalid Proactive rule id")
    if not isinstance(item_id, str) or not 1 <= len(item_id.strip()) <= 256:
        raise ValueError("A source-returned item id, title, or URL is required")
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("A brief reason is required")
    reason = reason_text(reason)
    setup = _setup(mobile, owner, profile)
    if not enabled(mobile, owner, profile) or not _setup_complete(mobile, owner, profile):
        raise ValueError("Proactive is not enabled")
    request = next((row for row in list_requests(mobile, owner, profile)
                    if row.get("id") == request_id and row.get("enabled") is True), None) if request_id else None
    if request_id and not request:
        raise ValueError("No enabled source rule matches this alert")
    evidence_key = _evidence_key(owner, profile, session_id)
    with mobile.connect() as conn:
        row = conn.execute("SELECT value FROM meta WHERE key=?", (evidence_key,)).fetchone()
    try:
        proof = json.loads(row[0]) if row else None
        reads = proof.get("reads", []) if isinstance(proof, dict) else []
        matched = [read for read in reads if isinstance(read, dict) and type(read.get("observed_at")) in (int, float)
                      and math.isfinite(read["observed_at"]) and 0 <= now.timestamp() - read["observed_at"] <= EVIDENCE_TTL
                      and source in read.get("sources", [])
                      and _digest(item_id) in read.get("item_hashes", [])]
    except (AttributeError, TypeError, ValueError):
        matched = False
    if not matched:
        raise ValueError("Fresh same-session evidence from a successful tool read of that source is required")
    if (request and request.get("signal") is not None
            and not any(source_name(request["signal"]) in read.get("sources", [])
                        for read in matched)):
        raise ValueError("No enabled source rule matches this alert")

    stored = cfg.get("scheduled_callback_conversation_id")
    if not isinstance(stored, str) or not stored.strip():
        raise ValueError("Choose an owned assistant conversation before enabling Proactive")
    native.verify(owner, provider, profile, stored)
    if _inside_quiet(now, setup) and not (request and request.get("action") == "call" and request.get("night") is True):
        return {"status": "quiet", "message": "Quiet hours; only a matching night-call rule may interrupt."}
    delivery = request["action"] if request else setup["delivery"]
    dedupe_rule = request_id or "default"
    if source != "email":
        dedupe_rule += ":source:" + _digest(source)
    dedupe = "proactive-alert:" + owner + ":" + profile + ":" + dedupe_rule + ":" + _digest(item_id)
    if delivery == "call":
        from beacon_mobile import invitation
        with mobile.LOCK, mobile.connect() as conn:
            conn.execute("BEGIN IMMEDIATE")
            prune_meta(conn, now.timestamp())
            if conn.execute("SELECT 1 FROM meta WHERE key=?", (dedupe,)).fetchone():
                return dict(DUPLICATE_ALERT)
            prefix = f"proactive-call:{owner}:{profile}:"
            count = sum((_number(row[0]) or 0) > now.timestamp() - CALL_WINDOW
                        for row in conn.execute("SELECT value FROM meta WHERE substr(key,1,?)=?",
                                                (len(prefix), prefix)))
            callbacks_ready = cfg.get("callback_tool_enabled") is True and cfg.get("scheduled_callbacks_enabled") is True
            if callbacks_ready and count < CALL_LIMIT:
                # The event's own reason leads (call history shows it first); the rule follows.
                rule_note = f" Rule: {request['text'][:100]}" if request else ""
                lead = reason.strip() if not rule_note or reason.strip()[-1:] in ".!?" else reason.strip() + "."
                try:
                    relay_reason = reason_text((lead + rule_note)[:240])
                except ValueError:
                    relay_reason = reason_text(reason.strip())
                event = invitation(owner, profile, stored, relay_reason, conn=conn)
                conn.execute("INSERT INTO meta(key,value) VALUES(?,?)",
                             ("proactive-call:" + owner + ":" + profile + ":" + uuid.uuid4().hex,
                              str(now.timestamp()).encode()))
                conn.execute("INSERT INTO meta(key,value) VALUES(?,?)", (dedupe, str(now.timestamp()).encode()))
                conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                             ("proactive-email-notice:" + event, str(now.timestamp()).encode()))
                conn.execute("INSERT OR REPLACE INTO meta(key,value) VALUES(?,?)",
                             (EMAIL_RULE_PREFIX + event,
                              json.dumps({"created": now.timestamp(), "rule_id": request_id or "default",
                                          "source": source}).encode()))
                conn.execute("UPDATE call_log SET email_notice_id=? WHERE id=? AND owner=? AND profile=?",
                             (event, event, owner, profile))
                return {"status": "pending", "event_id": event, "conversation_id": stored}
            return _notice(mobile, owner, profile, stored, reason.strip(), dedupe, now, email_alert=True, source=source, conn=conn)
    return _notice(mobile, owner, profile, stored, reason.strip(), dedupe, now, email_alert=True, source=source)
