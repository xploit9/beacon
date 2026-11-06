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
_TEXT_BODY_START = re.compile(r"(?i)^\s*(?:(?:message|email)\s+)?(?:body|content)\s*:")
# A header line of a rendered message ("Subject: ...", "Message-ID: ..."), and a bare line that is shaped like an identifier.
_TEXT_HEADER = re.compile(r"""(?i)^\s*(?:[-*]\s*)?(?:subject|title|name|summary|url|uri|link|web[_ ]url|id|(?:message|thread|event|task)[_ -]?id)\s*[:=]\s*(\S.{0,254})$""")
_TEXT_TOKEN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:@<>=+/~-]{3,127}")
_TEXT_BODY_FIELDS = frozenset({'body','message_body','email_body','body_text','plain_body','html_body','raw_body',
                               'snippet','preview','body_preview','plain_text','text_body','html','plain'})
# One run (session) can raise this many alerts; a message that fools the id check still cannot flood the owner.
ALERTS_PER_RUN = 10


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


# Letters from other scripts that read as Latin ones; case folding and decomposition leave them alone.
_LOOKALIKES = str.maketrans({
    **dict(zip("асеіјѕԁԍһӏкмнортухѵѡԛԝ", "aceijsdghlkmhoptyxvwqw")),
    **dict(zip("αεικνορτυχγωη", "aeikvoptuxywn")),
    **dict(zip("ɑɡɩȷɪʜʟɴʀꜱʏᴀʙᴄᴅᴇɢᴋᴍᴏᴘᴛᴜᴠᴡᴢıøđłħŧɨʉ", "agijihlnrsyabcdegkmoptuvwzioldhtiu")),
})


def _squashed(value):
    # Decompose and drop accents, then map look-alike letters onto Latin, so one swapped letter cannot hide a phrase.
    plain = "".join(char for char in unicodedata.normalize("NFKD", value) if not unicodedata.combining(char))
    return re.sub(r"[\W_]+", "", plain.casefold().translate(_LOOKALIKES))


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


def prefix_range(*prefixes):
    """A WHERE term and its arguments that match every meta key starting with one of the prefixes. Unlike LIKE and
    substr(), a key range reads only those rows of the primary-key index."""
    return ("(" + " OR ".join("(key>=? AND key<?)" for _ in prefixes) + ")",
            [bound for prefix in prefixes for bound in (prefix, prefix[:-1] + chr(ord(prefix[-1]) + 1))])


def prune_meta(conn, now):
    # A caller reads its clock before it waits for the write lock, so a marker a faster request just committed can
    # be dated after that stale reading; only a marker ahead of the live clock is corrupt.
    horizon = max(now, time.time())
    where, bounds = prefix_range("email-evidence:", "source-evidence:", "mail-evidence:")
    for key, value in conn.execute("SELECT key,value FROM meta WHERE " + where + " ORDER BY key LIMIT ?", (*bounds, PRUNE_BATCH)).fetchall():
        try:
            observed = json.loads(value).get("observed_at")
        except (AttributeError, TypeError, ValueError):
            observed = None
        if type(observed) not in (int, float) or not math.isfinite(observed) or now - observed > EVIDENCE_TTL:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))
    where, bounds = prefix_range("proactive-alert:", "proactive-call:", "proactive-email-notice:", "proactive-email-rule:", "mail-notified:")
    for key, value in conn.execute("SELECT key,value FROM meta WHERE " + where + " ORDER BY key LIMIT ?", (*bounds, PRUNE_BATCH)).fetchall():
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
    where, bounds = prefix_range("completion:")
    for key, value in conn.execute("SELECT key,value FROM meta WHERE " + where, bounds).fetchall():
        created = _number(value)
        if created is None:
            if value == b"notified":
                conn.execute("UPDATE meta SET value=? WHERE key=?", (str(now).encode(), key))
            else:
                conn.execute("DELETE FROM meta WHERE key=?", (key,))
        elif now - created > 86400:
            conn.execute("DELETE FROM meta WHERE key=?", (key,))


# Session-boundary cleanup has no turn_id; only the native per-turn finalizer can report a completion.
def completion(mobile, native, cfg, profile, session_id, turn_id=None, completed=False, failed=False,
               interrupted=False, job_id=None, **kwargs):
    if (completed is not True and failed is not True) or interrupted or not session_id or not turn_id:
        return
    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or not _profile_allowed(cfg,profile):
        return
    principal = native.current_principal(owner, provider, profile, session_id)
    row = (native.verify(owner, provider, profile, session_id, None, True, principal) if principal
           else native.verify(owner, provider, profile, session_id))
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
        # The owner is on a call in this conversation: the reply is spoken and shown there, so it is not also an alert.
        if kind != "task_result" and mobile.call_open_on(conn, owner, profile, session_id):
            return
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,job_id,principal) VALUES(?,?,?,?,?,?,?,?,?)",
                     (uuid.uuid4().hex, owner, profile, kind, session_id, now, now + 86400,
                      job_id if isinstance(job_id, str) and mobile.ID.fullmatch(job_id) else "", principal or ""))


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
        started = False
        for line in value.splitlines()[:80]:
            if text_ids:
                # A rendered message: the header block names the message, the prose after its blank line (or body label) is
                # attacker-written text, and only header lines and id-shaped tokens can be claimed.
                text = line.strip()
                if _TEXT_BODY_START.match(line) or started and not text: break
                started = started or bool(text)
                if header:=_TEXT_HEADER.match(line):
                    value_text = header.group(1).strip()
                    output.update((_digest(text), _digest(value_text), _digest(value_text.strip("\"'<>"))))
                    output.update(_digest(url) for url in re.findall(r"https?://[^\s<>\"']+", value_text)[:8])
                elif _TEXT_TOKEN.fullmatch(text):
                    output.add(_digest(text))
                continue
            line = line.strip()
            if 2 <= len(line) <= 256:
                output.add(_digest(line))
            output.update(_digest(url) for url in re.findall(r"https?://[^\s<>\"']+", line)[:8])
        return output
    if isinstance(value, dict):
        output = set()
        for key, child in list(value.items())[:100]:
            # A field named for message prose holds attacker-written text whichever server returned it.
            if isinstance(key, str) and key.casefold() in _TEXT_BODY_FIELDS:
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


# The opening lines of a message as a server renders it: two different ones (From, Subject, Message-ID, ...) before the first blank
# line, among at most the 80 lines a result is read for.
_MESSAGE_HEADER = re.compile(r"(?i)\s*(?P<key>from|to|cc|bcc|reply-to|subject|date|message[_ -]?id|in-reply-to)\s*:")


def _rendered_message(value, depth=0, budget=None):
    """Whether a tool result holds a rendered message (a header block, then its body), whatever the server is called. The prose
    after the header block is written by the sender, so it must never count as the message's items."""
    budget = [0] if budget is None else budget
    budget[0] += 1
    if depth > 5 or budget[0] > 512:
        return False
    if isinstance(value, str):
        if len(value) > 65536:
            return False
        try:
            parsed = json.loads(value)
        except (TypeError, ValueError):
            parsed = None
        if isinstance(parsed, (dict, list)):
            return _rendered_message(parsed, depth + 1, budget)
        keys = set()
        for line in value.splitlines()[:80]:
            if not line.strip():
                if keys:
                    break
                continue
            if header := _MESSAGE_HEADER.match(line):
                keys.add(re.sub(r"[_ -]", "", header["key"].casefold()))
        return len(keys) >= 2
    children = list(value.values())[:100] if isinstance(value, dict) else value[:100] if isinstance(value, list) else []
    return any(_rendered_message(child, depth + 1, budget) for child in children)


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
    # Match the existing compact catalogue's mail/Workspace labels as well as native source ids. The mail aliases say
    # "this read is that mailbox under another name", so a read earns them only when the tool itself is a mail tool or
    # a server named for a mail provider. A tool that names another source (a calendar, contacts or drive tool on the
    # same "outlook" server) is that other source, never mail: otherwise an email-scoped Not important rule would
    # silence that other source's alerts, so the owner is never told.
    mail_words = {"mail", "email", "gmail", "gmai", "outlook", "himalaya"}
    other_sources = {"calendar", "calendars", "event", "events", "invite", "invites", "contact", "contacts",
                     "address", "addresses", "drive", "file", "files", "folder", "folders", "doc", "docs",
                     "document", "documents", "sheet", "sheets", "slide", "slides", "task", "tasks", "todo",
                     "todos", "reminder", "reminders", "note", "notes", "chat", "chats", "channel", "channels",
                     "issue", "issues", "ticket", "tickets", "pull", "repo", "repos", "repository", "commit",
                     "commits", "build", "builds", "device", "devices", "phone", "sms"}
    server, _, tool_part = tool_name.partition("__")[2].partition("__") if tool_name.startswith("mcp__") else ("", "", "")
    tool_words = set(re.findall(r"[a-z0-9]+", (tool_part or tool_name).casefold()))
    if tool_name == "terminal" or (not tool_words & other_sources and (
            tool_words & mail_words or set(re.findall(r"[a-z0-9]+", server.casefold())) & mail_words)):
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
    # A rendered message is told by its own shape; a mail-looking tool name only adds to that, so a server called anything is covered.
    text_ids = (tool_name == "terminal" or any(token in tool_name.casefold() for token in ("gmail", "google_workspace", "himalaya", "email", "mail"))
                or _rendered_message(result))
    claims = sorted(_claims(result, text_ids=text_ids))[:128]
    if not claims:
        return False
    now = time.time()
    key = _evidence_key(owner, profile, session_id)
    with mobile.LOCK, mobile.connect() as conn:
        prune_meta(conn, now)
        previous = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
        reads = []
        alerts = 0
        if previous:
            try:
                old = json.loads(previous[0])
                alerts = old.get("alerts") if type(old.get("alerts")) is int else 0
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
        record = {"reads": reads, "observed_at": now, "alerts": alerts}
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


def _words(value):
    """Comparable words: case, accents and look-alike letters folded, punctuation dropped."""
    plain = "".join(char for char in unicodedata.normalize("NFKD", value) if not unicodedata.combining(char))
    return re.findall(r"\w+", plain.casefold().translate(_LOOKALIKES))


def _holds(words, phrase):
    return bool(phrase) and any(words[start:start + len(phrase)] == phrase for start in range(len(words) - len(phrase) + 1))


# The common ways to tell an assistant to drop what it was told: one of these verbs and one of these objects together, in any order, in
# the words of a rule. INSTRUCTION_MARKERS holds exact phrases; this catches the same order given in other words.
OVERRIDE_VERBS = frozenset({"ignore", "disregard", "forget", "override", "overrule", "bypass", "circumvent", "disable", "neglect",
                            "skip", "stop", "drop", "discard"})
OVERRIDE_OBJECTS = frozenset({"rule", "rules", "instruction", "instructions", "prompt", "prompts", "policy", "policies",
                              "guideline", "guidelines", "guidance", "directive", "directives"})
NEGATIONS = frozenset({"not", "never", "t", "dont"})


def check_override_phrase(value):
    """ValueError for rule text that tells the assistant to drop its rules or instructions ("Ignore not important rules"), in the
    words a deny-list of exact phrases cannot hold. A name or subject that only shares a word (Skip The Dishes) is left alone."""
    words = set(_words(value))
    orders = words & OVERRIDE_VERBS or ({"follow", "obey"} & words if words & NEGATIONS else set())
    if orders and words & OVERRIDE_OBJECTS:
        raise ValueError("Instruction-like text cannot be saved as a rule")


def _rule_matches(text, sender, topic, whole):
    """Whether an importance rule's words describe this alert. A rule is built from an alert's own sender ("Sent by ...") or
    subject ("About ..."); wording the owner changed still matches when it names the alert's sender or subject."""
    rule = _words(text)
    kind = "sender" if rule[:2] == ["sent", "by"] else "topic" if rule[:1] == ["about"] else ""
    rule = rule[2:] if kind == "sender" else rule[1:] if kind else rule
    if kind == "sender": return _holds(sender, rule)
    if kind == "topic": return _holds(topic, rule)
    return _holds(whole, rule) or _holds(rule, sender) or _holds(rule, topic)


def _muted(rules, labels, reason):
    """The owner's enabled importance rules against one alert: the more specific rule wins (one limited to this source beats one for
    any source) and Not important wins a tie. True when the alert is one the owner said not to be told about. [labels] are the names
    the alert's own source goes by: a rule limited to any of them applies, so a mailbox saved as "gmail" is not heard again as "email"."""
    from beacon_proactive import source_name
    sender_text, slash, topic_text = reason.partition(" / ")
    if not slash: sender_text, topic_text = "", reason
    sender, topic, whole = _words(sender_text), _words(topic_text), _words(reason)
    best = {}
    for row in rules:
        if not isinstance(row, dict) or row.get("enabled") is not True or row.get("action") not in ("important", "not_important"):
            continue
        signal = row.get("signal")
        try:
            if signal is not None and source_name(signal) not in labels: continue
        except ValueError:
            continue
        if _rule_matches(str(row.get("text", "")), sender, topic, whole):
            best[row["action"]] = max(best.get(row["action"], -1), int(signal is not None))
    return "not_important" in best and best["not_important"] >= best.get("important", -1)


def _take_run_slot(conn, owner, profile, session_id):
    """One alert of this run's allowance, counted on the run's own evidence record so it lasts as long as the evidence does;
    ValueError once the run has raised ALERTS_PER_RUN of them."""
    key = _evidence_key(owner, profile, session_id)
    row = conn.execute("SELECT value FROM meta WHERE key=?", (key,)).fetchone()
    try:
        record = json.loads(row[0]) if row else None
    except (TypeError, ValueError):
        record = None
    if not isinstance(record, dict):
        return
    used = record.get("alerts")
    used = used if type(used) is int and used >= 0 else 0
    if used >= ALERTS_PER_RUN:
        raise ValueError("Too many alerts in this run; the owner already has the important ones")
    record["alerts"] = used + 1
    conn.execute("UPDATE meta SET value=? WHERE key=?", (json.dumps(record, separators=(",", ":")).encode(), key))


def _notice(mobile, owner, profile, conversation, reason, alert_key, now, email_alert=False, source="email", *, conn=None, run_session=None,principal=''):
    from contextlib import nullcontext
    with mobile.LOCK, (mobile.connect() if conn is None else nullcontext(conn)) as conn:
        prune_meta(conn, now.timestamp())
        if conn.execute("SELECT 1 FROM meta WHERE key=?", (alert_key,)).fetchone():
            return dict(DUPLICATE_ALERT)
        mobile.prune_notices(conn)
        if conn.execute("SELECT COUNT(*) FROM notices").fetchone()[0] >= 10000:
            raise ValueError("Notification capacity reached")
        if run_session:
            _take_run_slot(conn, owner, profile, run_session)
        event = uuid.uuid4().hex
        conn.execute("INSERT INTO notices(id,owner,profile,kind,conversation,created,expires,reason,principal) VALUES(?,?,?,?,?,?,?,?,?)",
                     (event, owner, profile, "task_result", conversation, now.timestamp(),
                      now.timestamp() + 86400, reason[:240],principal))
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
    from beacon_proactive import EMAIL_RULE_PREFIX, _setup, _setup_complete, _without_links, enabled, list_requests, source_name

    owner, provider = cfg.get("owner_id"), cfg.get("owner_provider")
    if not owner or not provider or not _profile_allowed(cfg,profile) or not session_id:
        raise ValueError("Owned proactive session required")
    if not isinstance(args, dict) or set(args) - {"request_id", "source", "item_id", "reason"} or not {"source", "item_id", "reason"} <= set(args):
        raise ValueError("Source, item id and reason are required; request_id is optional")
    source = source_name(args["source"])
    request_id, item_id, reason = args.get("request_id"), args.get("item_id"), args.get("reason")
    if request_id is not None and (not isinstance(request_id, str) or not mobile.ID.fullmatch(request_id)):
        raise ValueError("Invalid Proactive rule id")
    if not isinstance(item_id, str) or not 1 <= len(item_id.strip()) <= 256 or any(c < " " or c == "\x7f" for c in item_id):
        raise ValueError("A source-returned item id, title, or URL is required")
    if not isinstance(reason, str) or not reason.strip():
        raise ValueError("A brief reason is required")
    # The words of an alert come from mail: any link, address or number to dial in them is dropped before the alert is stored,
    # shown in the chat or spoken to the model on a call, the same as for what the watch learns from it.
    reason = _without_links(reason_text(reason))
    if not reason.strip(". "):
        raise ValueError("A brief reason is required")
    setup = _setup(mobile, owner, profile)
    if not enabled(mobile, owner, profile) or not _setup_complete(mobile, owner, profile):
        raise ValueError("Proactive is not enabled")
    # A "Not important" rule only silences alerts; it can never be the reason for one.
    rules = list_requests(mobile, owner, profile)
    request = next((row for row in rules
                    if row.get("id") == request_id and row.get("enabled") is True and row.get("action") != "not_important"),
                   None) if request_id else None
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
    # The owner's saved Not important rules are enforced here, not left to the watch prompt: whatever the model decided,
    # no notice or call is created for an alert they said not to be told about.
    # The labels the reads that proved this alert carry: the claimed one, and the mailbox's others (email and gmail, its server's name).
    labels = {source, *(label for read in matched if isinstance(read.get("sources"), list)
                        for label in read["sources"] if isinstance(label, str))}
    if _muted(rules, labels, reason):
        return {"status": "quiet", "message": "The owner marked this kind of alert as not important; nothing was sent."}

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
            where, bounds = prefix_range(prefix)
            count = sum((_number(row[0]) or 0) > now.timestamp() - CALL_WINDOW
                        for row in conn.execute("SELECT value FROM meta WHERE " + where, bounds))
            callbacks_ready = cfg.get("callback_tool_enabled") is True and cfg.get("scheduled_callbacks_enabled") is True
            if callbacks_ready and count < CALL_LIMIT:
                _take_run_slot(conn, owner, profile, session_id)
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
            return _notice(mobile, owner, profile, stored, reason.strip(), dedupe, now, email_alert=True, source=source, conn=conn,
                           run_session=session_id)
    return _notice(mobile, owner, profile, stored, reason.strip(), dedupe, now, email_alert=True, source=source, run_session=session_id)
