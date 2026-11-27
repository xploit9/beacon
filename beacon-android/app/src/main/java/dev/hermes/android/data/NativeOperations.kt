package dev.hermes.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.hermes.android.core.*
import dev.hermes.android.ui.splitAttachedMedia
import org.json.JSONArray
import org.json.JSONObject

/** The characters Hermes turns into spaces before it searches (hermes_state_search._FTS5_SPECIAL_CHARS, outside a quoted phrase). */
private const val HERMES_SEARCH_SPECIAL = "+{}():\"^@/#&|~[]<>,;!?\$=\\'"

/**
 * Whether [text] holds a word of a search [query] as Hermes' own message search reads one: a quoted phrase is one term (held apart, so its punctuation stays), every other
 * word loses the characters Hermes strips (`invoice?` is `invoice`), a trailing `*` is a prefix, and a term after NOT is not asked for. Hermes also retries a query nothing
 * matched with ANY of its terms (hermes_state_search._or_relaxed_query), so one term in the visible text is enough: a hit whose words are only in Hermes' own note has none.
 * Used only to check that a hit lies in words the owner can see.
 */
internal fun visibleSearchMatch(text: String, query: String): Boolean {
    var negated = false
    val terms = mutableListOf<String>()
    for (token in Regex("\"[^\"]*\"|\\S+").findAll(query.trim()).map { it.value }) {
        when {
            token == "AND" || token == "OR" -> Unit
            token == "NOT" -> negated = true
            negated -> negated = false
            token.length > 1 && token.startsWith('"') && token.endsWith('"') -> token.trim('"').trim().takeIf(String::isNotEmpty)?.let(terms::add)
            else -> token.map { if (it in HERMES_SEARCH_SPECIAL) ' ' else it }.joinToString("").split(' ').forEach { part -> part.trimEnd('*').takeIf(String::isNotEmpty)?.let(terms::add) }
        }
    }
    return terms.isEmpty() || terms.any { text.contains(it, ignoreCase = true) }
}

/** "high" for an assistant chat whose profile default is slower than that, else null (keep the default). */
internal fun assistantEffortCap(current: String): String? = if (current in setOf("xhigh", "max", "ultra")) "high" else null

/** The precondition Hermes requires on a sidebar PATCH, from its `mutation-snapshot` (as its web client sends it). */
internal fun pinMutationIdentity(snapshot: JSONObject, requestId: String): JSONObject {
    val revision = snapshot.opt("runtime_revision"); val generation = snapshot.opt("runtime_generation")
    require(snapshot.opt("exists") == true && revision is Number && revision.toLong() >= 0 && generation is Number && generation.toLong() >= 0) {
        "This chat changed. Refresh and try again."
    }
    return JSONObject().put("request_id", requestId).put("expected_revision", revision.toLong()).put("expected_generation", generation.toLong())
}

// Projects existing Compose contracts onto native operations; never proxies the WebUI.
/** The normal end of a followed turn: it unwinds the event callback and is not a failure of the connection. */
internal class NativeTurnEnded : Exception()
internal class NativeModelConfirmation(message: String) : IllegalStateException(message)
private data class CanonicalModelGuard(val version: Long, val model: String, val provider: String, val token: String)
private val canonicalModelGuards = linkedMapOf<String, CanonicalModelGuard>()
internal val nativeApprovalModes = listOf("manual", "smart", "off")
/** Session detail keeps SQLite 0/1 flags; session lists expose booleans. */
internal fun nativeSessionFlag(row: JSONObject, key: String): Boolean = row.optBoolean(key) || (row.opt(key) as? Number)?.toDouble() == 1.0
/** The same flag, or null when the row does not carry it at all (so a caller can keep a better-known value). */
internal fun nativeSessionFlagOrNull(row: JSONObject?, key: String): Boolean? =
    row?.takeIf { it.has(key) }?.let { nativeSessionFlag(it, key) }
/** True when Hermes' live counters hold no tokens (a runtime that was recycled), so its zeros say nothing about the chat. */
internal fun liveUsageIsEmpty(live: JSONObject): Boolean =
    listOf("input", "input_tokens", "output", "output_tokens").all { (live.opt(it) as? Number)?.toLong().let { n -> n == null || n == 0L } }

/**
 * The live usage with the stored row's token totals (Hermes' `input_tokens`, `output_tokens`, cache and reasoning columns) when the row has any.
 * A recycled runtime also leaves its other counters at zero under the live names, so the totals Hermes stores fill those in as well: its prompt is
 * the input plus the cache it read (agent/turn_usage.py keeps `input_tokens` = prompt - cache read), its completion is the output, and it counted
 * every call (R3-F2).
 */
internal fun usageWithStoredTotals(live: JSONObject, row: JSONObject): JSONObject {
    fun stored(key: String) = (row.opt(key) as? Number)?.toLong() ?: 0L
    if (stored("input_tokens") + stored("output_tokens") <= 0L) return live
    return live.copy().apply {
        for (zero in listOf("prompt", "completion", "input", "output", "reasoning")) remove(zero)
        for (key in listOf("input_tokens", "output_tokens", "cache_read_tokens", "cache_write_tokens", "reasoning_tokens")) if (row.has(key)) put(key, stored(key))
        put("prompt_tokens", stored("input_tokens") + stored("cache_read_tokens") + stored("cache_write_tokens"))
        put("completion_tokens", stored("output_tokens"))
        if (row.has("api_call_count")) put("api_calls", stored("api_call_count"))
    }
}

internal fun nativeHistoryStamp(row: JSONObject): List<String>? =
    if (row.opt("message_count") !is Number || row.opt("last_active") !is Number) null else
        listOf("message_count", "last_active", "title", "model", "provider", "model_config", "cwd", "hidden", "archived", "ended_at")
            .map { row.opt(it)?.toString().orEmpty() }
internal fun nativeHistoryNeedsRefresh(observed: List<String>?, stamp: List<String>?): Boolean =
    observed == null || stamp == null || stamp != observed
internal fun displayedNativeHistoryStamp(projection: JSONObject): List<String>? =
    projection.optJSONArray("_history_metadata_stamp")?.strings()?.takeIf { it.size == 10 }

private fun nativeReplayNumber(value: Any?): Long? = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Number -> value.toLong().takeIf { it.toDouble() == value.toDouble() }
    else -> null
}

/** Canonical replay suppresses a truncated response; find its retained suffix without resubmitting. */
internal suspend fun nativeRetainedReplay(epoch: String, fetch: suspend (Long) -> JSONObject): Pair<Long, JSONObject>? {
    fun latest(reply: JSONObject, after: Long): Long? {
        val value = nativeReplayNumber(reply.opt("latest_seq")) ?: return null
        return value.takeIf { epoch.isNotBlank() && reply.string("epoch") == epoch && reply.opt("truncated") is Boolean &&
            (reply.opt("truncated") == true || reply.opt("snapshot_required") != true) && it >= after }
    }
    val first = fetch(0)
    val upper = latest(first, 0) ?: return null
    if (first.opt("truncated") == false) return 0L to first
    if (upper == 0L) return null
    // The native ring retains at most 512 events, so the eviction boundary lies in this window.
    var low = maxOf(0L, upper - 512L)
    var high = upper
    if (low > 0) {
        val earliest = fetch(low)
        if ((latest(earliest, low) ?: return null) < upper) return null
        if (earliest.opt("truncated") == false) return low to earliest
    }
    low++
    while (low < high) {
        val mid = low + (high - low) / 2
        val probe = fetch(mid)
        if ((latest(probe, mid) ?: return null) < upper) return null
        if (probe.opt("truncated") == true) low = mid + 1 else high = mid
    }
    val retained = fetch(low)
    if ((latest(retained, low) ?: return null) < upper || retained.opt("truncated") != false) return null
    return low to retained
}

/** A retained completion can identify one input even when earlier replay events have expired. */
internal fun nativeCompletedTurn(replay: JSONObject, runtime: String, epoch: String, receipt: JSONObject, submission: String, after: Long, requireComplete: Boolean = true): JSONObject? {    val admission = receipt.string("admission_id")
    if (runtime.isBlank() || epoch.isBlank() || admission.isBlank() || submission.isBlank() ||
        receipt.string("status") != "terminal" || receipt.string("outcome") != "completed" ||
        receipt.has("input_id") && receipt.opt("input_id") != submission ||
        replay.opt("truncated") != false || replay.opt("snapshot_required") == true || replay.string("epoch") != epoch) return null
    val array = replay.optJSONArray("events") ?: return null
    val events = replay.rows("events")
    val latest = nativeReplayNumber(replay.opt("latest_seq")) ?: return null
    if (after < 0 || latest < after || latest - after != events.size.toLong() || events.size != array.length() ||
        events.withIndex().any { (index, event) -> nativeReplayNumber(event.opt("seq")) != after + index + 1 ||
            event.string("session_id") != runtime || event.has("replay_epoch") && event.string("replay_epoch") != epoch }) return null
    val event = events.filter {
        it.string("type") == "message.complete" && it.optJSONObject("payload")?.string("admission_id") == admission
    }.singleOrNull() ?: return null
    if (event.has("admission_id") && event.opt("admission_id") != admission) return null
    for (key in listOf("authority_epoch", "execution_generation")) {
        val fromReceipt = receipt.opt(key); val fromEvent = event.opt(key)
        if (fromReceipt != null && fromReceipt != JSONObject.NULL || fromEvent != null && fromEvent != JSONObject.NULL) {
            val expected = nativeReplayNumber(fromReceipt) ?: return null
            if (nativeReplayNumber(fromEvent) != expected) return null
        }
    }
    val payload = event.optJSONObject("payload") ?: return null
    if (payload.string("status") in setOf("error", "failed", "failure", "interrupted", "cancelled", "canceled", "rejected") ||
        payload.string("outcome").let { it.isNotBlank() && it != "completed" } ||
        payload.string("error").isNotBlank() || payload.string("failure_reason").isNotBlank()) return null
    return payload.optJSONObject("persisted_turn")?.takeIf { (!requireComplete || it.opt("complete") == true) && it.string("submission_id") == submission }
}

/**
 * The terminal settlement result carries the committed row addresses of this admission
 * (`persisted_turn`). Those rows are durable in the session transcript even after the runtime's
 * event ring that once carried `message.complete` is gone, so a completed receipt can still be
 * proven by exact row identity. The caller's submission is the same admission it asked about; it
 * is added here as the submission identity and never inferred from message content.
 */
internal fun nativeSettledTurn(receipt: JSONObject, submission: String): JSONObject? {
    if (receipt.string("status") != "terminal" || receipt.string("outcome") != "completed" || submission.isBlank()) return null
    if (receipt.has("input_id") && receipt.opt("input_id") != submission) return null
    val turn = receipt.optJSONObject("result")?.optJSONObject("persisted_turn") ?: return null
    if (turn.opt("complete") != true) return null
    val user = nativeReplayNumber(turn.opt("user_row_id")) ?: return null
    val final = nativeReplayNumber(turn.opt("final_assistant_row_id")) ?: return null
    // A missing or non-array row list is a malformed settlement, not proof: fail closed so the caller can
    // still fall back to a retained message.complete event instead of throwing here.
    val rowIds = turn.optJSONArray("row_ids") ?: return null
    val userIds = turn.optJSONArray("user_row_ids") ?: return null
    val rows = (0 until rowIds.length()).mapNotNull { index -> nativeReplayNumber(rowIds.opt(index)) }
    val users = (0 until userIds.length()).mapNotNull { index -> nativeReplayNumber(userIds.opt(index)) }
    if (user <= 0 || final <= 0 || final == user || rows.size != rowIds.length() ||
        users.size != userIds.length() || users != listOf(user) ||
        rows.distinct().size != rows.size || user !in rows || final !in rows) return null
    val existing = turn.string("submission_id")
    if (existing.isNotBlank() && existing != submission) return null
    return turn.copy().put("submission_id", submission)
}

/**
 * A steered turn: Hermes never calls it `complete` (it holds more than one user row), but its `persisted_turn` still lists every committed user row
 * in order (the prompt, then each steer) and names the final reply. That is enough to show the reply once and to pair each steer bubble with its row.
 * Anything short of a fully listed, committed, distinct set of rows is not proof.
 */
internal fun nativeSteeredTurn(turn: JSONObject, submission: String): JSONObject? {
    if (submission.isBlank() || turn.has("submission_id") && turn.string("submission_id") != submission) return null
    fun ids(key: String): List<Long>? {
        val array = turn.optJSONArray(key) ?: return null
        return (0 until array.length()).map { nativeReplayNumber(array.opt(it))?.takeIf { id -> id > 0 } ?: return null }
    }
    val user = nativeReplayNumber(turn.opt("user_row_id"))?.takeIf { it > 0 } ?: return null
    val final = nativeReplayNumber(turn.opt("final_assistant_row_id"))?.takeIf { it > 0 } ?: return null
    val users = ids("user_row_ids") ?: return null
    val rows = ids("row_ids") ?: return null
    if (users.size < 2 || users.first() != user || users.distinct().size != users.size || rows.distinct().size != rows.size ||
        !rows.containsAll(users) || final !in rows || final in users) return null
    return turn.copy().put("submission_id", submission)
}

internal fun storedSessionSettings(snapshot: JSONObject): JSONObject {
    val raw = snapshot.opt("model_config")
    val config = (raw as? JSONObject) ?: (raw as? String)?.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    return snapshot.copy().apply {
        if (has("hidden") || has("archived")) put("archived", nativeSessionFlag(snapshot, "archived") || nativeSessionFlag(snapshot, "hidden"))
        if (has("hidden")) put("hidden", nativeSessionFlag(snapshot, "hidden"))
        if (string("model").isBlank()) config.string("model").takeIf(String::isNotBlank)?.let { put("model", it) }
        if (string("model_provider").isBlank()) config.string("provider").takeIf(String::isNotBlank)?.let { put("model_provider", it) }
        if (string("reasoning_effort").isBlank()) config.optJSONObject("reasoning_config")?.let { reasoning ->
            put("reasoning_effort", if (reasoning.opt("enabled") == false) "none" else reasoning.string("effort", "medium"))
        }
    }
}
/**
 * True when a chat's saved row holds a setting Hermes keeps per chat and a new chat does not start with: "Never ask" (`yolo_mode`, hermes_state_sessions.set_session_yolo, restored on
 * resume) or a Fast mode (`service_tier` other than normal, written to the row by tui_gateway/agent_factory._persist_live_session_runtime after config.set fast; both keys are in
 * tui_gateway/methods_config_set._SESSION_SCOPED_KEYS). A config that cannot be read is not a fresh chat.
 */
internal fun chatKeepsApprovalOrSpeed(session: JSONObject): Boolean {
    val raw = session.opt("model_config")
    val config = when {
        raw is JSONObject -> raw
        raw is String && raw.isNotBlank() -> try { JSONObject(raw) } catch (_: org.json.JSONException) { return true }
        else -> return false
    }
    return config.optBoolean("yolo_mode") || config.string("service_tier").trim().lowercase() !in setOf("", "normal", "default", "standard", "off", "none")
}
private val storedSessionId = Regex("[A-Za-z0-9_.:-]{1,256}")
/** The session sources Hermes runs as classic-CLI turns (gateway/session_policy.SURFACES to "cli", and kanban). */
private val CLI_SURFACES = setOf("cli", "tool", "oneshot", "kanban")
// Stock dashboard bounds: /api/sessions pages reject limit > 100; Beacon keeps its 200-row drawer window.
private const val SESSION_PAGE_LIMIT = 100
private const val SESSION_INDEX_WINDOW = 200
/** A Hermes platform name (telegram, api_server, …); anything else is not sent as a source filter. */
private val sourceName = Regex("[a-z][a-z0-9_]{0,39}")
// /api/sessions/{id}/messages allows limit <= 500; passive browsing reads one newest window.
private const val HISTORY_WINDOW = 500
private const val MAX_COMPRESSION_CHAIN = 512
private const val OPTIONAL_VOICE_HISTORY_TIMEOUT_MS = 1_500L

/** The images a chat with session authority stages as images (image.attach_bytes); every other file goes through file.attach. */
internal val CANONICAL_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
/** The Beacon plugin relay's frame cap (canonical_transport.MAX_FRAME, 1.2.0 and later): a larger frame from the phone ends the socket. */
internal const val CANONICAL_RELAY_FRAME_BYTES = 8 * 1024 * 1024
/**
 * Whether one staging frame for this file fits the relay's cap, as Android's JSON writes it: base64 escapes each "/" as "\/", a name may take up to 6
 * bytes a character once escaped, and the envelope (method, ids, session, profile, the data URL's prefix) stays well under 1 KiB.
 */
/**
 * The phone's own cap on one file it sends in pieces (CHAT-121 follow-up 4). A file is read from its kept copy as it is sent, so the phone's heap holds
 * only a piece and OkHttp's queue whatever the size (measured on the emulator: see CHAT-121.md, follow-up 4); this caps the copy kept on the phone.
 */
internal const val PHONE_ATTACHMENT_MAX_BYTES = 256L * 1024 * 1024
/** The largest file one text frame of 8 MiB carries (a 1.2.0 plugin, or no pieces): its base64 with each "/" escaped, the envelope and the name. */
internal fun textFrameFileBytes(name: String, mime: String): Long = rawBytesForFrame(CANONICAL_RELAY_FRAME_BYTES.toLong() * 64 / 65, name, mime)
/** The largest file of [mime] a chat with session authority sends on a connection with [limits]: the phone's cap, Hermes' own cap, one frame. */
internal fun attachmentLimitBytes(limits: AttachmentLimits, name: String, mime: String): Long =
    if (!limits.pieces) textFrameFileBytes(name, mime)
    else minOf(PHONE_ATTACHMENT_MAX_BYTES, (if (mime in CANONICAL_IMAGE_TYPES) limits.imageBytes else limits.fileBytes) ?: Long.MAX_VALUE,
        rawBytesForFrame(limits.frameBytes, name, mime))
internal fun fitsCanonicalFrame(base64: String, name: String, mime: String): Boolean =
    base64.length.toLong() + base64.count { it == '/' } + name.length * 6L + mime.length * 2L + 1024 <= CANONICAL_RELAY_FRAME_BYTES
/**
 * Hermes' answer to file.attach that means it cannot take this file, from its source: no such method on the chat's authority (-32601), the owner's
 * file.attach refusing the file itself (4001 invalid_params: bytes it cannot read, past its size limit), or a Hermes before 5551a2c342, whose authority
 * passed file.attach to the legacy sidecar, which never holds such a chat (4001 "session not found"). Anything else (permission, storage, a dropped
 * connection) is not about the file.
 */
internal fun hermesRefusesFile(error: ApiFailure): Boolean =
    error.status == -32601 || error.status == 4001 && error.message in setOf("invalid_params", "session not found")

/**
 * The create parameters for a new conversation, from the request body. Every other key of the body stays on the phone (the profile rides in the envelope, a `worktree`
 * flag or a previous chat means nothing to Hermes). `request_id` is the create's retry identity: Hermes derives the session from it and returns the session an earlier
 * create with the same id made (gateway/session_local.py:333-340), as its own desktop client relies on (apps/desktop/src/api/canonical-protocol.ts, prepareCreate).
 */
internal fun nativeCreateParams(d: JSONObject): JSONObject {
    val params = JSONObject()
    for (key in setOf("title", "model")) if (d.has(key) && !d.isNull(key)) params.put(key, d.opt(key))
    d.string("model_provider").takeIf { it.isNotBlank() }?.let { params.put("provider", it) }
    d.string("workspace").takeIf { it.isNotBlank() }?.let { params.put("cwd", it) }
    d.string("request_id").takeIf { it.isNotBlank() && it.length <= 256 }?.let { params.put("request_id", it) }
    if (d.optBoolean("thread")) params.put("source", BEACON_THREAD_SOURCE)
    return params
}

/**
 * When Beacon may close a turn Hermes lost across its restart. Hermes holds what waits behind such a turn until a person decides
 * (gateway-internals "neither replays the input nor lets the queued turns behind it run"; Desktop's Discard, /discard, the last viewer's close).
 * [WhenAlone]: only when nothing waits behind it, so closing starts nothing. [Never]: the phone holds a waiting message, so a read never closes.
 * [Decided]: the owner's own act (the Send of the restart line, Send on a waiting message, Delete).
 */
internal enum class LostTurnClose { WhenAlone, Never, Decided }

/** Hermes' lost turn holds this chat and something waits behind it: in Hermes' queue, or (said by the caller) on the phone. */
internal fun lostTurnWithWaiting(snapshot: JSONObject): Boolean =
    snapshot.rows("pending").any { it.string("status") == "unknown" } && snapshot.rows("pending").any { it.string("status") != "unknown" }

/**
 * Hermes keeps a turn its previous run lost (it restarted or crashed mid-reply) as `unknown` and runs nothing queued behind it until the person
 * discards it (hermes_state_runtime.recover_session_inputs, claim_session_input). Its own clients ask the person: Desktop shows "Turn lost during
 * restart" with Discard, the dashboard chat Ctrl+X; both call prompt.resolve_unknown with the generation Hermes stamped on that row. Beacon closes
 * the turn the same way, as [mode] allows: Hermes ends it as interrupted with its "did not complete" note (or keeps the answer if the turn had
 * finished), never runs the lost message again, and starts the next queued one. Returns how many lost turns are closed now; one another client
 * closed first (stale_generation) counts too, so the caller reads the chat again.
 */
internal suspend fun BeaconClient.closeLostTurns(lease: NativeTurnLease, snapshot: JSONObject, mode: LostTurnClose = LostTurnClose.WhenAlone): Int {
    if (!lease.authority || mode == LostTurnClose.Never || mode == LostTurnClose.WhenAlone && lostTurnWithWaiting(snapshot)) return 0
    var closed = 0
    for (row in snapshot.rows("pending").filter { it.string("status") == "unknown" }) {
        val admission = row.string("admission_id")
        val generation = nativeReplayNumber(row.opt("execution_generation"))?.takeIf { it >= 0 }
        require(admission.isNotBlank() && generation != null) { LOST_REPLY_ERROR }
        val result = try {
            nativeTurnRpc(lease, "prompt.resolve_unknown", json("session_id" to lease.binding.runtimeSessionId, "admission_id" to admission, "execution_generation" to generation))
        } catch (e: ApiFailure) { if (e.detail == "stale_generation") { closed++; continue }; throw e }
        require(result.string("admission_id") == admission && result.string("status") == "terminal") { LOST_REPLY_ERROR }
        closed++
    }
    return closed
}

/**
 * Lets [sessionId]'s lost replies go the way Hermes' own Discard does, with no session resumed and no agent built (WEDGE-121 follow-up 4):
 * prompt.resolve_unknown with the admission and the generation Hermes stamped, on the profile's connection. Hermes ends each one interrupted
 * with its "did not complete" note and never requeues it; only a message already queued behind it would run
 * (gateway/session_authority.resolve_unknown). One another client let go first (stale_generation) counts as gone. True when every one is gone.
 */
internal suspend fun BeaconClient.discardLostTurns(sessionId: String, lost: List<LostAdmission>): Boolean {
    if (lost.isEmpty()) return false
    for (turn in lost) {
        val result = try {
            nativeRpc("prompt.resolve_unknown", json("session_id" to sessionId, "admission_id" to turn.admissionId, "execution_generation" to turn.generation))
        } catch (e: ApiFailure) { if (e.detail == "stale_generation") continue; throw e }
        require(result.string("admission_id") == turn.admissionId && result.string("status") == "terminal") { LOST_REPLY_ERROR }
    }
    return true
}

/**
 * One lazy resume of [id]: the runtime Hermes holds for it (the stream id) and that runtime's turn status; null when the answer is not about this conversation.
 * A turn Hermes lost across its restart is closed first as [close] allows ([closeLostTurns]), so the status is the chat's real one: idle, or the next
 * queued message running; "lost" while it still holds the chat (something waits behind it for the owner's decision).
 */
internal suspend fun BeaconClient.recoveredNativeSession(id: String, close: LostTurnClose = LostTurnClose.WhenAlone, onClosed: (() -> Unit)? = null): Pair<String, String?>? {
    if (!id.matches(storedSessionId)) return null
    // A resumed socket may bind a different runtime id than the stale ChatState stream id.
    // Lazy: a stored conversation resumes as usual; one Hermes no longer holds answers 4007 (legacy) or 4001 not_found (canonical) instead of a new ownerless row.
    val binding = nativeSession(id, lazy = true)
    if (binding.scope != scope || binding.storedSessionId != id) return null
    val lease = nativeTurnLease(id, binding.runtimeSessionId)
    val status = if (!lease.authority) nativeSessionStatus(id, binding.runtimeSessionId) else {
        // Canonical active_list omits internal task sources; the attached conversation's own snapshot is authoritative.
        suspend fun snapshot() = nativeTurnRpc(lease, "session.resume", json("session_id" to binding.runtimeSessionId, "omit_messages" to true)).also { fresh ->
            require(nativeSessionBinding(scope, fresh) == binding) { "Conversation identity changed during recovery" }
            val profile = fresh.optJSONObject("info")?.opt("profile_name")
            require(profile == null || profile == JSONObject.NULL || profile == scope.profile) { "Conversation scope changed during recovery" }
        }
        val fresh = snapshot().let { first -> if (closeLostTurns(lease, first, close) > 0) { onClosed?.invoke(); snapshot() } else first }
        when {
            fresh.rows("pending").any { it.string("status") == "unknown" } -> "lost"
            fresh.opt("running") == true -> if (fresh.rows("prompts").isEmpty()) "working" else "waiting"
            fresh.opt("running") == false && fresh.rows("pending").none { it.string("status") in setOf("queued", "started") } -> "idle"
            else -> null
        }
    }
    return binding.runtimeSessionId to status
}

internal suspend fun BeaconClient.recoveredNativeSessionStatus(id: String, close: LostTurnClose = LostTurnClose.WhenAlone): String? = recoveredNativeSession(id, close)?.second

/**
 * Delete withdrew the messages waiting behind a lost turn (so none could start), then could not finish. Their words go back to the phone's queue
 * ([withdrawn]: admission id and text), and the owner is told in one line.
 */
internal class DeleteKeptWaiting(val withdrawn: List<Pair<String, String>>) : ApiFailure(409, DELETE_KEPT_WAITING_ERROR)

private const val THREAD_MARK_READ_MS = 60_000L
private const val MARKED_THREAD_FETCH_LIMIT = 40
/** When each account last asked the plugin which chats are threads (only a throttle; the answer joins [ThreadMarks]). */
internal val threadMarkReads = HashMap<String, Long>()

internal class NativeOperations(private val c: BeaconClient) {
    suspend fun call(path: String, q: Map<String, String>, method: String, body: JSONObject?, maxBytes: Int): JSONObject {
        var keepCreatedSessionLive = false
        var failedSend = false
        return try {
            dispatch(path, q, method, body, maxBytes).also { keepCreatedSessionLive = path == "/api/session/new" || path == "/api/beacon/open" }
        } catch (e: Throwable) {
            failedSend = path == "/api/chat/start"
            throw e
        } finally {
            // CHAT-121 follow-up 11: a send that failed on the chat's own client (not a detached one) leaves its connection as it is: it carries the
            // chat's other work, and a first prompt sent again needs the socket that created the chat (follow-up 10's cut lost the chat's socket here).
            if (!keepCreatedSessionLive && path != "/api/chat/steer" && !(failedSend && c.ownsChatConnection)) c.closeNative()
        }
    }
    /**
     * The chat's saved YOLO flag. Hermes persists it in the conversation's model_config (`yolo_mode`) and restores it on
     * resume (hermes_state_sessions.session_yolo_enabled), so it is the chat's state when a resume does not report one.
     * Null when the conversation has no saved row yet.
     */
    private suspend fun storedYolo(id: String): Boolean? = try {
        val config = c.nativeGet("/api/sessions/$id", mapOf("profile" to c.auth.profile)).opt("model_config")
        ((config as? JSONObject) ?: (config as? String)?.takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()).optBoolean("yolo_mode")
    } catch (e: ApiFailure) { if (e.status == 404) null else throw e }
    /** The assistant chat answers everyday questions: start it at "high" when the profile default is slower; the owner can raise it per chat. */
    private suspend fun capAssistantEffort(runtimeSessionId: String): String? {
        return try {
            val current = c.nativeRpc("config.get", json("session_id" to runtimeSessionId, "key" to "reasoning")).string("value")
            assistantEffortCap(current)?.also { c.nativeRpc("config.set", json("session_id" to runtimeSessionId, "key" to "reasoning", "value" to it, "scope" to "session")) }
        } catch (error: CancellationException) { throw error } catch (_: Exception) { null } // the chat still opens at the profile default
    }
    /** Adapts Hermes Relay's session-scoped GatewayChatClient.setYolo wire contract. */
    private suspend fun setSessionYolo(target: NativeSessionBinding, enabled: Boolean) {
        val value = if (enabled) "1" else "0"
        val lease = c.nativeTurnLease(target.storedSessionId, target.runtimeSessionId)
        val params = json("session_id" to target.runtimeSessionId, "key" to "yolo", "value" to value).apply { if (!lease.authority) put("scope", "session") }
        val result = c.nativeTurnRpc(lease, "config.set", params)
        require(result.string("value") == value) { "Hermes did not confirm this chat's approval setting. Refresh before trying again." }
    }
    private suspend fun canonicalSnapshot(lease: NativeTurnLease, messages: Boolean = false): JSONObject {
        require(lease.authority) { "Native gateway authority changed. Refresh before continuing." }
        val b = lease.binding
        val snapshot = c.nativeTurnRpc(lease, "session.resume", json("session_id" to b.runtimeSessionId, "omit_messages" to !messages))
        require(nativeSessionBinding(c.scope, snapshot) == b) { "Conversation identity changed. Refresh before continuing." }
        snapshot.optJSONObject("info")?.let { info ->
            if (info.has("profile_name") && !info.isNull("profile_name")) require(info.opt("profile_name") == c.scope.profile) { "Conversation profile changed." }
        }
        if (messages) {
            val rows = snapshot.optJSONArray("messages") ?: error("Refresh the chat first.")
            require(snapshot.opt("messages_omitted") != true && nativeReplayNumber(snapshot.opt("message_count")) == rows.length().toLong() &&
                snapshot.rows("messages").size == rows.length()) { "Conversation history could not be confirmed." }
        }
        return snapshot
    }
    private suspend fun canonicalMutation(lease: NativeTurnLease, snapshot: JSONObject, operation: String, payload: JSONObject): JSONObject {
        val revision = nativeReplayNumber(snapshot.opt("revision"))?.takeIf { it >= 0 }
        val generation = nativeReplayNumber(snapshot.opt("execution_generation"))?.takeIf { it >= 0 }
        require(lease.authority && nativeSessionBinding(c.scope, snapshot) == lease.binding && revision != null && generation != null) { "Conversation changed. Refresh before continuing." }
        val result = c.nativeTurnRpc(lease, "session.mutate", json("session_id" to lease.binding.runtimeSessionId,
            "request_id" to java.util.UUID.randomUUID().toString(), "expected_revision" to revision, "expected_generation" to generation,
            "operation" to operation, "payload" to payload))
        require(result.string("session_id") == lease.binding.runtimeSessionId && result.string("operation") == operation) { "Conversation change was not confirmed." }
        val status = result.string("status")
        when (status) {
            "confirmation_required" -> require(operation == "model" && result.opt("confirm_required") == true && result.string("confirm").isNotBlank() && result.string("confirm_message").isNotBlank()) { "Model confirmation was not supplied." }
            "noop", "preview" -> require(operation == "compress") { "Conversation change was not confirmed." }
            else -> {
                require(status.isBlank() || status == "deferred" && operation == "model") { "Conversation change was not confirmed." }
                require(nativeReplayNumber(result.opt("revision"))?.let { it >= revision } == true &&
                    nativeReplayNumber(result.opt("execution_generation"))?.let { it >= generation } == true) { "Conversation change was not confirmed." }
            }
        }
        return result
    }
    /** The plugin's owner-only store write, the same flag `hermes sessions pin|unpin` sets. */
    private suspend fun legacyPin(sessionId: String, pinned: Boolean): JSONObject =
        c.nativeRequest(LEGACY_SESSION_PIN_PATH, "POST",
            json("session_id" to sessionId, "pinned" to pinned, "profile" to c.auth.profile)).also {
            require(it.string("session_id") == sessionId && it.opt("pinned") == pinned) { "Pin change was not confirmed. Refresh before trying again." }
        }
    /** What Hermes holds now: the first open prompt (null = none) and the ones behind it, oldest first. */
    private fun pendingPrompts(prompts: List<JSONObject>): JSONObject =
        json("pending" to prompts.firstOrNull(), "waiting" to JSONArray().apply { prompts.drop(1).forEach { put(it) } })
    private fun canonicalPrompts(snapshot: JSONObject, kind: String): List<JSONObject> {
        val array = snapshot.optJSONArray("prompts") ?: error("Refresh this chat first.")
        val rows = snapshot.rows("prompts")
        val generation = nativeReplayNumber(snapshot.opt("execution_generation"))?.takeIf { it >= 0 }
        require(rows.size == array.length() && generation != null) { "Request status could not be confirmed." }
        return rows.filter { it.string("kind") == kind }.also { prompts ->
            require(prompts.all { it.opt("prompt_id") is String && it.string("prompt_id").isNotBlank() &&
                nativeReplayNumber(it.opt("execution_generation")) == generation } && prompts.map { it.string("prompt_id") }.distinct().size == prompts.size) { "The active request changed. Refresh before answering." }
        }
    }
    private suspend fun dispatch(path: String, q: Map<String, String>, method: String, body: JSONObject?, maxBytes: Int): JSONObject {
        require(q["profile"] == null || q["profile"] == c.auth.profile) { "Native request belongs to another profile" }
        require(body?.has("profile") != true || body.string("profile") == c.auth.profile) { "Native request belongs to another profile" }
        if (path.startsWith("/api/crons")) return dev.hermes.android.tasks.NativeSchedules(c).call(path, q, method, body)
        val d = body ?: JSONObject(q)
        if (path == "/api/projects" || path.startsWith("/api/projects/")) return NativeProjects(c).call(path, method, d)
        fun sid() = d.string("session_id")
        suspend fun binding(): NativeSessionBinding { require(sid().isNotBlank()) { "Choose a native conversation first" }; return c.nativeSession(sid()) }
        when (path) {
            "/api/mobile/bootstrap" -> return c.bootstrap()
            "/api/commands", "/api/commands/catalog" -> {
                val result = c.nativeRpc("commands.catalog"); val pairs = result.optJSONArray("pairs") ?: JSONArray(); val own = ownCommandEntries(result); val commands = JSONArray()
                // A shared gateway runs only some built-ins for a phone ([sharedGatewayRuns]); with its readiness unknown the list stays whole, as before.
                val shared = try { c.nativeSessionAuthority() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                for (i in 0 until pairs.length()) {
                    val pair = pairs.optJSONArray(i) ?: continue
                    val name = pair.optString(0).removePrefix("/")
                    commands.put(json("name" to name, "description" to pair.optString(1)).apply {
                        if (pair.optString(0) to pair.optString(1) in own) put("own", true) else if (shared && !sharedGatewayRuns(name)) put("runs", false)
                    })
                }
                return json("commands" to commands, "available" to true, "warning" to result.string("warning"))
            }
            "/api/commands/exec" -> {
                val name = d.getString("name").removePrefix("/").lowercase()
                val argument = d.string("argument").trim()
                require(!chatCommandChangesProfile(name, argument)) { "Change this for all chats in Settings." }
                require(name != "yolo") { "Use Approvals next to the message box." }
                if (name == "model") {
                    val (model, provider) = chatModelChoice(argument)
                    return dispatch("/api/session/update", emptyMap(), "POST", json("session_id" to sid(), "model" to model, "model_provider" to provider,
                        "confirm_expensive_model" to d.optBoolean("confirm_expensive_model")), maxBytes).put("type", "local").put("output", "Model updated for this chat")
                }
                if (name in setOf("reasoning", "fast")) {
                    require(when (name) {
                        "reasoning" -> argument in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")
                        else -> argument in setOf("normal", "fast", "auto", "cold", "ultrafast")
                    }) { "Use the chat's settings picker for this choice." }
                    val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                    val params = json("session_id" to b.runtimeSessionId, "key" to name, "value" to argument).apply { if (name != "fast" || !lease.authority) put("scope", "session") }
                    val result = c.nativeTurnRpc(lease, "config.set", params)
                    require(result.string("value").isNotBlank()) { "The chat setting was not confirmed." }
                    return result.put("type", "local").put("output", "Updated for this chat")
                }
                // Hermes' own clients run a typed command with slash.exec (apps/desktop .../use-prompt-actions/slash.ts:521, acp_adapter/gateway_commands.py:89), which a
                // shared gateway answers exactly as command.dispatch (gateway/session_controls.py:441-447). Without one, slash.exec would run a command Hermes' clients
                // run themselves (/new, /branch, /bg …, desktop slash.ts:649-1217) in a separate slash worker, not this chat (tui_gateway/methods_tools.py:1070-1098).
                val session = binding().runtimeSessionId
                val shared = try { c.nativeSessionAuthority() } catch (e: CancellationException) { throw e } catch (e: Exception) { false }
                return if (shared) c.nativeRpc("slash.exec", json("session_id" to session, "command" to listOf(name, argument).filter(String::isNotBlank).joinToString(" ")))
                else c.nativeRpc("command.dispatch", json("session_id" to session, "name" to name, "arg" to argument))
            }
            // What Hermes starts a new chat with in this profile, for the composer of a chat with no messages yet.
            "/api/profile/defaults" -> {
                val models = c.nativeGet("/api/model/options", mapOf("profile" to c.auth.profile, "include_unconfigured" to "false"))
                val reasoning = c.nativeRpc("config.get", json("key" to "reasoning"))
                require(models.opt("model") is String && reasoning.opt("value") is String && reasoning.string("value").isNotBlank()) { "Profile defaults are unavailable. Refresh and try again." }
                return json("profile" to c.auth.profile, "scope_key" to c.scope.key, "session_id" to sid(), "model" to models.string("model"), "reasoning_effort" to reasoning.string("value"))
            }
            "/api/profile/approval-mode" -> {
                require(method in setOf("GET", "POST")) { "Unsupported profile approval operation" }
                if (method == "POST") {
                    require(d.keys().asSequence().all { it in setOf("mode", "scope_key") } && d.string("scope_key") == c.scope.key && d.string("mode") in setOf("manual", "smart")) { "Choose Manual or Auto in this profile's Settings." }
                    val applied = c.nativeRpc("config.set", json("key" to "approvals.mode", "value" to d.getString("mode")))
                    require(applied.string("value") == d.string("mode")) { "Approval change was not confirmed. Refresh and try again." }
                }
                val result = c.nativeRpc("config.get", json("key" to "approvals.mode"))
                require(result.string("value") in nativeApprovalModes && (method == "GET" || result.string("value") == d.string("mode"))) { "Approval change was not confirmed. Refresh and try again." }
                return result.put("scope_key", c.scope.key)
            }
            "/api/profile/switch" -> {
                val name = d.getString("name")
                val profiles = c.nativeGet("/api/profiles").rows("profiles")
                require(profiles.any { it.string("name") == name }) { "Native profile does not exist" }
                c.bindProfile(name)
                return json("ok" to true, "profile" to name)
            }
            "/api/sessions", "/api/sessions/search" -> return sessionIndex(archived = q["include_archived"] == "1", query = q["q"].orEmpty(), threads = q["view"] == "threads", hidden = q["hidden_sources"].orEmpty(), order = q["order"].orEmpty())
            "/api/session/new", "/api/beacon/open" -> {
                val params = nativeCreateParams(d)
                // A Hermes with session authority stores a chat only under its own surfaces (the thread source is refused), so a thread there is made as a
                // titled chat and marked as a thread on this phone and in the plugin; anywhere else it keeps its source as before.
                val markThread = params.string("source") == BEACON_THREAD_SOURCE && c.nativeSessionAuthority()
                if (markThread) params.remove("source")
                val b = c.nativeCreate(params)
                require(b.storedSessionId.isNotBlank()) { "Native session has no durable identity yet; submit the first prompt before saving selection" }
                if (markThread) markAsThread(b.storedSessionId)
                val cappedEffort = if (path == "/api/beacon/open") capAssistantEffort(b.runtimeSessionId) else null
                return sessionProjection(c.nativeSessionSnapshot().put("messages", JSONArray()).put("acknowledged_shell", true), b).put("profile", c.auth.profile).apply {
                    // The composer shows the effort this chat really got.
                    cappedEffort?.let { put("reasoning_effort", it) }
                    // Hermes holds a create title until the first turn; show it (and the thread lane) from the start.
                    if (string("title").isBlank() && params.string("title").isNotBlank()) put("title", params.string("title"))
                    if (markThread || params.string("source") == BEACON_THREAD_SOURCE) put("source", BEACON_THREAD_SOURCE)
                }
            }
            "/api/session" -> {
                require(sid().matches(storedSessionId)) { "Choose a native conversation first" }
                return passiveHistory(sid()).also { if (sid() in ThreadMarks.ids(c.scope)) it.put("source", BEACON_THREAD_SOURCE) }
            }
            "/api/chat/start", "/api/chat/steer" -> {
                require(path != "/api/chat/steer" || d.rows("attachments").isEmpty()) { "Guide accepts text only" }
                val steer = path == "/api/chat/steer"
                val lease = try {
                    val b = if (steer) null else binding().also { c.nativeReplay(beforePrompt = true) }
                    c.nativeTurnLease(sid(), if (steer) d.string("runtime_session_id").takeIf(String::isNotBlank)
                        ?: throw NativeTurnNotSent("The running conversation is no longer available") else b?.runtimeSessionId)
                } catch (e: Throwable) {
                    if (e is CancellationException || e is NativeTurnNotSent) throw e
                    throw NativeTurnNotSent("Couldn't open the chat. Your message is kept.", e)
                }
                val bound = lease.binding
                if (bound.scope != c.scope || bound.storedSessionId != sid()) throw NativeTurnNotSent("Conversation identity changed")
                val refs = mutableListOf<String>()
                val receipts = JSONArray()
                val queuedPaths = mutableListOf<String>()
                val canonicalAttachments = JSONArray()
                var promptAttempted = false
                try {
                if (!steer && !lease.authority && d.optBoolean("queued_model_snapshot") && d.string("model").isNotBlank()) {
                    val info = c.nativeSessionSnapshot().optJSONObject("info") ?: JSONObject()
                    val model = info.string("model")
                    val provider = info.string("provider")
                    if (model != d.string("model") || d.string("model_provider").isNotBlank() && provider != d.string("model_provider"))
                        throw NativeTurnNotSent("The model changed. Pick this message's model to send it.")
                }
                // Every staging frame of a chat with session authority passes the Beacon plugin's relay (Hermes serves its staged upload route to its own
                // webapp only, hermes_cli/web_server_surface.py: browser_files). A large file was kept as a file on this phone and goes in pieces up to what
                // this connection takes (CHAT-121 follow-up 4); through a plugin that acknowledges them, a small one kept as base64 goes in pieces too, so a
                // slow link sends it a piece at a time and the socket keeps answering Hermes' pings (follow-up 8); otherwise it goes in one text frame. A
                // file that cannot go, or whose kept copy is gone, is named before anything of the message is sent.
                // CHATQA-121 follow-up 5: asked again under the same identity, the payload is the first one's: its staged attachments, never staged again.
                val resubmit = !steer && d.optBoolean("resubmit")
                if (resubmit) {
                    if (!lease.authority) throw NativeTurnNotSent("This Hermes is not asked again.")
                    val staged = d.optJSONArray("staged_attachments") ?: JSONArray()
                    if (staged.length() != d.rows("attachments").size) throw NativeTurnNotSent("This send's attachments are not saved.")
                    for (index in 0 until staged.length()) canonicalAttachments.put(staged.getJSONObject(index))
                }
                // Chat join: a resubmit stages nothing, so there is no file to check either.
                val files = if (resubmit) emptyList() else d.rows("attachments")
                for (item in files) {
                    val name = item.string("name").ifBlank { "Attachment" }
                    val mime = item.string("mime")
                    val kept = ChatAttachmentStore.file(item)
                    if (item.optBoolean("staged_file") && kept?.isFile != true) throw NativeTurnNotSent("The attached file is no longer on this phone")
                    val fits = if (kept != null) lease.authority && lease.attachLimits.pieces && kept.length() <= attachmentLimitBytes(lease.attachLimits, name, mime)
                        else !lease.authority || fitsCanonicalFrame(item.string("staged_base64"), name, mime)
                    if (!fits) throw AttachmentRefused(name, ATTACHMENT_TOO_LARGE_LINE,
                        limitMb = megabytes(if (lease.authority) attachmentLimitBytes(lease.attachLimits, name, mime) else textFrameFileBytes(name, mime)))
                }
                for (attachment in files) {
                    require(attachment.string("scope_key") == c.scope.session(bound.storedSessionId).key) { "Attachment belongs to another conversation" }
                    val data = attachment.string("staged_base64")
                    val mime = attachment.string("mime")
                    val kept = ChatAttachmentStore.file(attachment)
                    val pieces = lease.authority && lease.attachLimits.pieces
                    val receipt = if (lease.authority && mime in CANONICAL_IMAGE_TYPES) {
                        val staged = if (kept != null || pieces) c.nativeTurnRpc(lease, "image.attach_bytes", json("session_id" to bound.runtimeSessionId,
                            "filename" to attachment.string("name")), InlineFile(kept, "content_base64", "", if (kept == null) data else null))
                        else c.nativeTurnRpc(lease, "image.attach_bytes", json("session_id" to bound.runtimeSessionId,
                            "content_base64" to data, "filename" to attachment.string("name")))
                        require(staged.optBoolean("attached") && staged.string("path").isNotBlank() && staged.string("mime") == mime) { "Native image staging was not confirmed" }
                        canonicalAttachments.put(json("path" to staged.string("path"), "mime" to staged.string("mime")))
                        staged
                    } else if (lease.authority) {
                        // Any other file is staged as Hermes' own Desktop does (gateway/session_ancillary.attach_file, since 5551a2c342): file.attach with
                        // the bytes, and its @file: reference travels in the text of the submit, as on a chat without session authority.
                        val name = attachment.string("name").ifBlank { "Attachment" }
                        val type = mime.ifBlank { "application/octet-stream" }
                        val staged = try {
                            if (kept != null || pieces) c.nativeTurnRpc(lease, "file.attach", json("session_id" to bound.runtimeSessionId, "name" to name),
                                InlineFile(kept, "data_url", "data:$type;base64,", if (kept == null) data else null))
                            else c.nativeTurnRpc(lease, "file.attach", json("session_id" to bound.runtimeSessionId, "data_url" to "data:$type;base64,$data", "name" to name))
                        }
                        catch (e: ApiFailure) { if (hermesRefusesFile(e)) throw AttachmentRefused(name, ATTACHMENT_REFUSED_LINE, e) else throw e }
                        val ref = staged.string("ref_text")
                        require(staged.optBoolean("attached") && ref.startsWith("@file:") && ref.length <= 2048 && ref.none { it == '\n' || it == '\r' || it == '\u0000' }) { "Native file staging was not confirmed" }
                        refs.add(ref)
                        staged
                    } else if (mime.startsWith("image/")) {
                        require(attachment.getString("id").matches(Regex("[a-fA-F0-9-]{36}")) && validUploadName(attachment.getString("name"))) { "This staged attachment has an invalid filename or identity. Rename it without quotes, slashes, dollar signs, percent signs or control characters, then attach it again." }
                        val uploadPath = workspacePath(bound.storedSessionId, ".beacon-attachments/${attachment.getString("id")}-${attachment.getString("name")}")
                        c.nativeRequest("/api/files/upload", "POST", json("path" to uploadPath, "data_url" to "data:$mime;base64,$data", "overwrite" to true))
                        queuedPaths.add(uploadPath)
                        c.nativeTurnRpc(lease, "image.attach", json("session_id" to bound.runtimeSessionId, "path" to "\"$uploadPath\""))
                    } else c.nativeTurnRpc(lease, "file.attach", json("session_id" to bound.runtimeSessionId, "data_url" to "data:$mime;base64,$data", "name" to attachment.string("name")))
                    require(receipt.optBoolean("attached")) { "Native attachment was not accepted" }
                    if (!lease.authority) receipt.string("path").takeIf { it.isNotBlank() && mime.startsWith("image/") }?.let(queuedPaths::add)
                    receipts.put(receipt.copy().put("id", attachment.string("id")).put("name", attachment.string("name")).put("mime", mime))
                    if (!lease.authority) receipt.string("ref_text").takeIf { it.isNotBlank() }?.let(refs::add)
                }
                val text = (listOf(d.string("message", d.string("text"))) + refs).joinToString("\n")
                val params = json("session_id" to bound.runtimeSessionId, "text" to text)
                if (!steer && d.optBoolean("queued_model_snapshot")) params.put("queued", true)
                if (!steer && lease.authority) {
                    val submission = d.string("client_request_id").takeIf(String::isNotBlank) ?: throw NativeTurnNotSent("A saved submission identity is required")
                    params.put("submission_id", submission)
                    if (canonicalAttachments.length() > 0) params.put("attachments", canonicalAttachments)
                    if (d.optBoolean("queued_model_snapshot") && d.string("model").isNotBlank()) params.put("model_override", json("model" to d.string("model")).apply {
                        d.string("model_provider").takeIf(String::isNotBlank)?.let { put("provider", it) }
                    })
                }
                promptAttempted = true
                val result = c.nativeTurnRpc(lease, if (steer) "session.steer" else "prompt.submit", params)
                val rowId = result.opt("user_row_id")
                require(rowId == null || rowId == JSONObject.NULL || rowId is Number && rowId.toLong() > 0) { "Native row identity is not confirmed" }
                if (steer) {
                    require(result.string("status") in setOf("queued", "rejected")) { "The guidance outcome is unknown" }
                    result.put("safe_to_queue", result.string("status") == "rejected" && (!lease.authority || result.opt("delivered") == false))
                } else if (lease.authority) {
                    require(result.optJSONObject("ref")?.string("session_id") == bound.storedSessionId && result.string("admission_id").isNotBlank() &&
                        result.string("status") in setOf("queued", "started", "unknown", "terminal")) { "Native admission identity is not confirmed" }
                } else require(result.string("status") in setOf("streaming", "queued")) { "Native prompt outcome is unknown" }
                return result.put("accepted", result.string("status") in setOf("streaming", "queued", "started", "terminal"))
                    .put("session_id", bound.storedSessionId).put("stream_id", bound.runtimeSessionId).put("attachment_receipts", receipts)
                } catch (error: Throwable) {
                    if (!promptAttempted || error is NativeTurnNotSent || requestNeverSent(error)) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        for (queuedPath in queuedPaths.distinct()) runCatching { c.nativeTurnRpc(lease, "image.detach", json("session_id" to bound.runtimeSessionId, "path" to queuedPath)) }
                    }
                    if (!promptAttempted && error !is CancellationException && error !is NativeTurnNotSent) throw NativeTurnNotSent("Not sent. Your draft is kept.", error)
                    if (steer && error is ApiFailure && ((!lease.authority && error.status == 4010) ||
                            (lease.authority && error.detail in setOf("unsupported_control", "execution_not_ready", "stale_generation"))))
                        throw NativeTurnNotSent("Couldn't change the reply. Your message is kept.", error)
                    // Follow-up 5: the answer was lost (the prompt may have reached Hermes): its staged attachments go with it, so asking again re-sends them unchanged.
                    if (!steer && lease.authority && promptAttempted && generateSequence(error) { it.cause }.take(8).any { it.message == NATIVE_OUTCOME_UNKNOWN })
                        throw NativeSubmitUnknown(canonicalAttachments, error)
                    throw error
                }
            }
            "/api/chat/cancel" -> {
                val runtime = d.string("stream_id").ifBlank { require(sid().isNotBlank()) { "Choose a native session before stopping" }; binding().runtimeSessionId }
                if (!c.nativeSessionAuthority()) return c.nativeRpc("session.interrupt", json("session_id" to runtime))
                val b = c.nativeSession(runtime)
                val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                require(b.runtimeSessionId == runtime) { "The active reply changed. Refresh before stopping." }
                val snapshot = canonicalSnapshot(lease)
                // Stop on a reply Hermes lost in a restart: nothing runs to interrupt. Alone, it is closed. With messages waiting behind it (in Hermes' queue,
                // or on the phone: `waiting`), Stop never starts the next one; the owner gets the restart line's choice instead.
                if (snapshot.opt("running") != true && snapshot.rows("pending").any { it.string("status") == "unknown" }) {
                    if (d.string("waiting") == "1" || lostTurnWithWaiting(snapshot)) return json("stopped" to "lost_waiting", "session_id" to b.storedSessionId)
                    if (c.closeLostTurns(lease, snapshot) > 0) return json("stopped" to "lost_turn", "session_id" to b.storedSessionId)
                }
                val generation = nativeReplayNumber(snapshot.opt("execution_generation"))?.takeIf { it >= 0 }
                require(snapshot.opt("running") == true && generation != null) { "The active reply could not be confirmed." }
                val result = c.nativeTurnRpc(lease, "session.interrupt", json("session_id" to runtime, "execution_generation" to generation))
                val ref = result.optJSONObject("ref")
                val profile = snapshot.optJSONObject("info")?.string("profile_id").orEmpty()
                require(ref?.string("session_id") == b.storedSessionId && (profile.isBlank() || ref?.string("profile_id") == profile) &&
                    nativeReplayNumber(result.opt("execution_generation")) == generation) { "Couldn't confirm the stop. Refresh and try again." }
                return result
            }
            "/api/approval/pending" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) return pendingPrompts(canonicalPrompts(canonicalSnapshot(lease), "approval").map { it.copy().put("approval_id", it.getString("prompt_id")) })
                val result = c.nativeRpc("approval.pending", json("session_id" to b.runtimeSessionId))
                return pendingPrompts(result.rows("approvals").map { it.copy().put("approval_id", it.string("request_id")) })
            }
            "/api/clarify/pending" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) return pendingPrompts(canonicalPrompts(canonicalSnapshot(lease), "clarify").map { it.copy().put("clarify_id", it.getString("prompt_id")) })
                val result = c.nativeRpc("session.resume", json("session_id" to b.storedSessionId, "omit_messages" to true, "close_on_disconnect" to false))
                val requests = result.rows("open_requests")
                return pendingPrompts(requests.filter { it.string("method").contains("clarify") }.map { (it.optJSONObject("params") ?: it).copy().put("clarify_id", it.string("id")) })
            }
            "/api/approval/mode" -> {
                require(method in setOf("GET", "POST")) { "Unsupported approval setting operation" }
                require(sid().matches(storedSessionId)) { "Choose a conversation first" }
                if (method == "GET" && q["passive"] == "1") {
                    // A browsed chat has no runtime: show its own saved YOLO choice without resuming it. A bypass held only by
                    // another live runtime shows once this chat runs here (its live read replaces this one).
                    val profileMode = c.nativeRpc("config.get", json("key" to "approvals.mode")).string("value")
                    require(profileMode in nativeApprovalModes) { "Approvals are unavailable. Refresh and try again." }
                    val saved = storedYolo(sid()) == true
                    return json("value" to if (saved) "off" else profileMode, "profile_mode" to profileMode, "session_status_known" to true,
                        "session_bypass" to (saved && profileMode != "off"), "session_id" to sid(), "scope_key" to c.scope.key)
                }
                val yoloWrite = method == "POST"
                if (method == "POST") {
                    require(d.keys().asSequence().all { it in setOf("session_id", "yolo", "scope_key") } && d.opt("yolo") is Boolean) { "Choose Always ask or Don't ask for this chat." }
                    require(d.string("scope_key") == c.scope.session(sid()).key) { "Reopen Approvals in the original account and profile" }
                    val target = binding()
                    require(d.string("scope_key") == c.scope.session(target.storedSessionId).key) { "Refresh the chat first." }
                    if (d.getBoolean("yolo")) {
                        val profileMode = c.nativeRpc("config.get", json("key" to "approvals.mode")).string("value")
                        require(profileMode in nativeApprovalModes) { "Approvals are unavailable. Refresh and try again." }
                        require(profileMode != "off") { "Approvals are off for this profile." }
                    }
                    // Hermes reports a chat's YOLO state only from a built agent, and the check below needs it; a chat opened moments ago may still be
                    // building one. This scoped read starts the agent and waits for it; a failed build (5032) is left to the check below.
                    try { c.nativeRpc("approval.pending", json("session_id" to target.runtimeSessionId)) } catch (e: ApiFailure) { if (e.status != 5032) throw e }
                    // An unsent chat has no saved row, so Hermes would keep its YOLO only in memory: save it first (Beacon plugin, best
                    // effort) and the choice lands on the row and survives a restart. Saved chats never touch the plugin.
                    if (storedYolo(target.storedSessionId) == null) {
                        try { c.nativeRequest("/api/plugins/beacon/mobile/sessions/${target.storedSessionId}/reserve", "POST", JSONObject()) }
                        catch (e: ApiFailure) { if (!e.plugin) throw e }
                    }
                    setSessionYolo(target, d.getBoolean("yolo"))
                }
                val result = c.nativeRpc("config.get", json("key" to "approvals.mode"))
                require(result.string("value") in nativeApprovalModes) { "Approvals are unavailable. Refresh and try again." }
                // Lazy reads never construct an agent merely to paint the shield in a browsed conversation.
                val fresh = if (c.nativeSessionAuthority()) {
                    val target = c.nativeSession(sid(), lazy = true)
                    canonicalSnapshot(c.nativeTurnLease(target.storedSessionId, target.runtimeSessionId))
                } else c.nativeRpc("session.resume", json("session_id" to sid(), "lazy" to true, "omit_messages" to true))
                require(nativeSessionBinding(c.scope, fresh).storedSessionId == sid()) { "Approval status belongs to another conversation" }
                val reported = fresh.optJSONObject("info")?.opt("yolo") as? Boolean
                val savedBypass = if (reported == null) storedYolo(sid()) else null
                // Saved true proves a bypass. Saved false cannot rule out process-wide --yolo.
                val bypass = reported ?: savedBypass?.takeIf { it }
                val reportedMode = fresh.optJSONObject("info")?.string("approval_mode").orEmpty()
                val profileMode = if (result.string("value") == "off" || reportedMode == "off") "off" else reportedMode.takeIf { it in nativeApprovalModes } ?: result.getString("value")
                // Without a saved row Hermes cannot report the state back (its resume of an unsaved chat has no yolo). Turning YOLO on then
                // trusts config.set's confirmation; turning it off must be proven, so approvals are never shown active when they might not be.
                // shortcut: without the Beacon plugin an unsent chat's YOLO can be turned on but not off until its first message saves it; ask Hermes for a session read that reports yolo if this matters.
                if (yoloWrite && !(bypass == null && d.getBoolean("yolo"))) require(bypass == d.getBoolean("yolo")) { "Approvals could not be verified. Refresh before continuing." }
                val sessionBypass = if (bypass is Boolean) bypass == true && profileMode != "off" else JSONObject.NULL
                return result.put("profile_mode", profileMode).put("value", if (bypass == true || profileMode == "off") "off" else profileMode).put("session_status_known", bypass is Boolean).put("session_bypass", sessionBypass).put("session_id", sid()).put("scope_key", c.scope.key)
            }
            "/api/approval/respond", "/api/clarify/respond" -> {
                require(d.string("scope_key") == c.scope.session(sid()).key) { "Reopen this request in its original conversation." }
                val target = binding()
                val lease = c.nativeTurnLease(target.storedSessionId, target.runtimeSessionId)
                val kind = if (path == "/api/approval/respond") "approval" else "clarify"
                val id = d.getString("${kind}_id")
                if (lease.authority) {
                    val prompt = canonicalPrompts(canonicalSnapshot(lease), kind).singleOrNull { it.string("prompt_id") == id }
                    if (prompt != null) {
                        val params = json("session_id" to target.runtimeSessionId, "prompt_id" to id, "execution_generation" to prompt.getLong("execution_generation"))
                        if (kind == "approval") {
                            require(d.opt("choice") is String && prompt.optJSONArray("choices")?.strings()?.contains(d.string("choice")) == true && !d.optBoolean("all")) { "Choose an approval Hermes offered for this request." }
                            params.put("choice", d.getString("choice"))
                        } else {
                            require(d.opt("response") is String) { "Answer this request before continuing." }
                            params.put("answer", d.getString("response"))
                        }
                        val result = c.nativeTurnRpc(lease, "$kind.respond", params)
                        require(result.string("prompt_id") == id && result.string("status") in setOf("resolved", "already_resolved")) { "The request response was not confirmed. Refresh before continuing." }
                        return result
                    }
                }
                return try {
                    if (path == "/api/approval/respond") c.nativeAnswerServerRequest("approval", d.getString("approval_id"), target.runtimeSessionId, d)
                    else c.nativeAnswerServerRequest("clarify", d.getString("clarify_id"), target.runtimeSessionId, d)
                } catch (e: NativeRequestEnded) {
                    // Hermes' own snapshot no longer holds the prompt and this route sends no server request for it: Hermes ended it (its own wait ran out, or it settled
                    // while the phone was away). Nothing is sent; the card closes and says so, instead of a lost connection. A gateway without session authority keeps its error.
                    if (!lease.authority) throw e
                    json("status" to "ended", "prompt_id" to id)
                }
            }
            "/api/session/branch" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) {
                    val snapshot = canonicalSnapshot(lease, messages = true)
                    val rows = snapshot.rows("messages")
                    fun rowId(row: JSONObject) = nativeReplayNumber(row.opt("row_id"))?.takeIf { it > 0 }
                    val before = d.has("before_message_id")
                    val boundary = when {
                        before -> nativeReplayNumber(d.opt("before_message_id"))
                        d.has("through_message_id") -> nativeReplayNumber(d.opt("through_message_id"))
                        d.has("branch_row_ids") -> {
                            val captured = d.optJSONArray("branch_row_ids") ?: error("Reopen the branch form.")
                            val count = nativeReplayNumber(d.opt("keep_count")) ?: error("Choose messages to keep.")
                            require(count in 1..captured.length().toLong()) { "An empty branch is unavailable in native Hermes." }
                            nativeReplayNumber(captured.opt((count - 1).toInt()))
                        }
                        d.has("keep_count") -> error("Reopen the branch action to identify the selected message.")
                        else -> null
                    }
                    if (d.has("before_message_id") || d.has("through_message_id") || d.has("branch_row_ids")) require(boundary != null && boundary > 0) { "The selected message has no durable identity." }
                    val payload = JSONObject().apply { d.string("title").takeIf(String::isNotBlank)?.let { put("title", it) } }
                    if (boundary != null) {
                        val matches = rows.withIndex().filter { rowId(it.value) == boundary }
                        require(matches.size == 1 && matches.single().value.opt("archived") != true &&
                            (d.string("selected_role").isBlank() || matches.single().value.string("role") == d.string("selected_role"))) { "The selected message changed. Reopen the branch action." }
                        val index = matches.single().index
                        if (before) {
                            require(matches.single().value.string("role") == "user" && index > 0) { "An empty branch is unavailable in native Hermes." }
                            val previous = rows.take(index).lastOrNull { it.opt("archived") != true } ?: error("An empty branch is unavailable in native Hermes.")
                            payload.put("through_message_id", rowId(previous) ?: error("The previous message has no durable identity."))
                        } else payload.put("through_message_id", boundary)
                    }
                    val result = canonicalMutation(lease, snapshot, "branch", payload)
                    val child = result.string("branched_session_id")
                    require(child.matches(storedSessionId) && child != b.storedSessionId) { "Branch creation was not confirmed." }
                    val meta = c.nativeGet("/api/sessions/$child", mapOf("profile" to c.auth.profile))
                    require(meta.string("id", meta.string("session_id")) == child && ownedByProfile(meta)) { "Branch belongs to another conversation." }
                    return json("session" to meta.copy().put("session_id", child))
                }
                val result = c.nativeRpc("session.branch", json("session_id" to b.runtimeSessionId, "name" to d.string("title").ifBlank { null }, "count" to if (d.has("keep_count")) d.getInt("keep_count") else null))
                return sessionProjection(result, NativeSessionBinding(c.scope, result.getString("stored_session_id"), result.getString("session_id")))
            }
            "/api/session/pin" -> {
                require(method == "POST" && sid().matches(storedSessionId) && d.opt("pinned") is Boolean) { "Choose a conversation to pin" }
                val pinned = d.getBoolean("pinned")
                if (c.nativeSessionAuthority()) {
                    try {
                        val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                        val result = canonicalMutation(lease, canonicalSnapshot(lease), "sidebar", json("pinned" to pinned))
                        require(result.opt("pinned") == pinned) { "Pin change was not confirmed. Refresh before trying again." }
                        return result
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        // Canonical is always tried first: an adopted row must publish its own session.updated.
                        // Only a keyed legacy row is read-only history to the authority (session.resume ->
                        // adopt_legacy_session -> validate_legacy_row -> not_found). Hermes still exposes the
                        // pin flag itself; the plugin writes it through SessionDB, exactly `hermes sessions pin|unpin`.
                        if (!nativeSessionNotFound(e)) throw e
                    }
                    return legacyPin(sid(), pinned)
                }
                // Hermes' own PATCH reaches a running gateway itself (web_server_sessions._mutate_session_request); it accepts a sidebar change only with
                // its revision precondition and a fresh request id, as its own web client sends.
                val snapshot = c.nativeGet("/api/sessions/${sid()}/mutation-snapshot", mapOf("profile" to c.auth.profile))
                val identity = pinMutationIdentity(snapshot, java.util.UUID.randomUUID().toString())
                return c.nativeRequest("/api/sessions/${sid()}", "PATCH", identity.put("pinned", pinned).put("profile", c.auth.profile)).also {
                    require(it.opt("pinned") == pinned) { "Pin change was not confirmed. Refresh before trying again." }
                }
            }
            "/api/session/rename" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                val title = d.getString("title")
                if (!lease.authority) return c.nativeRpc("session.title", json("session_id" to b.runtimeSessionId, "title" to title))
                val result = canonicalMutation(lease, canonicalSnapshot(lease), "rename", json("title" to title))
                require(result.opt("title") is String && result.string("title").isNotBlank()) { "Rename was not confirmed. Refresh before trying again." }
                return result
            }
            "/api/session/archive" -> {
                require(sid().matches(storedSessionId) && d.opt("archived") is Boolean) { "Choose a conversation to archive" }
                val hidden = d.getBoolean("archived")
                if (c.nativeSessionAuthority()) {
                    val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                    val payload = json("hidden" to hidden).apply { if (!hidden) put("archived", false) }
                    val result = canonicalMutation(lease, canonicalSnapshot(lease), "sidebar", payload)
                    require(result.opt("hidden") == hidden && (hidden || result.opt("archived") == false)) { "Archive change was not confirmed. Refresh before trying again." }
                    return result
                }
                if (!hidden) {
                    val meta = c.nativeGet("/api/sessions/${sid()}", mapOf("profile" to c.auth.profile))
                    if (nativeSessionFlag(meta, "archived")) c.nativeRpc("session.archive", json("session_id" to sid(), "archived" to false)).also {
                        require(it.string("session_key") == sid() && it.opt("archived") == false) { "Unarchive was not confirmed. Try again." }
                    }
                }
                return c.nativeRpc("session.set_hidden", json("session_id" to sid(), "hidden" to hidden)).also {
                    require(it.string("session_key") == sid() && it.opt("hidden") == hidden) { "Archive change was not confirmed. Refresh before trying again." }
                }
            }
            "/api/session/delete" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) {
                    var snapshot = canonicalSnapshot(lease)
                    val held = snapshot.rows("pending")
                    // Messages wait while nothing runs: a task Hermes lost in its restart holds them. A lost reply can be closed; a lost background task
                    // only goes with Hermes' own reset, and Hermes refuses Delete until then (unknown_execution), so nothing is withdrawn for nothing.
                    if (snapshot.opt("running") == false) require(held.isEmpty() || held.any { it.string("status") == "unknown" }) { LOST_TASK_ERROR }
                    // A lost reply holds the chat, and Hermes refuses to delete it (unknown_execution). Nothing queued behind it can start while it is
                    // held, so withdraw those messages first (they go with the chat), then close the lost turn and read the chat again. If Delete then
                    // cannot finish, the withdrawn words go back to the phone's queue ([DeleteKeptWaiting]): a waiting message is never lost silently.
                    val withdrawn = mutableListOf<Pair<String, String>>()
                    try {
                        if (snapshot.opt("running") == false && held.any { it.string("status") == "unknown" }) {
                            for (row in held.filter { it.string("status") == "queued" }) {
                                val admission = row.string("admission_id")
                                val result = try { c.nativeTurnRpc(lease, "prompt.cancel", json("session_id" to b.runtimeSessionId, "admission_id" to admission)) }
                                catch (e: Throwable) {
                                    if (e is CancellationException) throw e
                                    // The answer was lost or refused: Hermes may still hold the message (then it stays there, and is not handed back
                                    // to be sent twice), or may have withdrawn it before the answer was lost (then it is handed back).
                                    val kept = try { canonicalSnapshot(lease).rows("pending").any { it.string("admission_id") == admission } }
                                        catch (r: Throwable) { if (r is CancellationException) throw r; false }
                                    if (!kept) withdrawn += admission to row.string("text")
                                    throw e
                                }
                                require(result.string("admission_id") == admission && result.string("status") == "terminal") { LOST_REPLY_ERROR }
                                withdrawn += admission to row.string("text")
                            }
                            if (c.closeLostTurns(lease, snapshot, LostTurnClose.Decided) > 0) snapshot = canonicalSnapshot(lease)
                        }
                        require(snapshot.opt("running") == false && snapshot.opt("pending") is JSONArray && snapshot.rows("pending").isEmpty()) { "Stop the reply before deleting this chat." }
                        val result = try { canonicalMutation(lease, snapshot, "delete", JSONObject()) }
                        catch (e: ApiFailure) { if (e.detail == "unknown_execution") throw ApiFailure(e.status, LOST_TASK_ERROR, e.plugin); throw e }
                        require(result.optJSONArray("deleted_ids")?.strings()?.contains(b.storedSessionId) == true) { "Deletion was not confirmed. Refresh before trying again." }
                        return result.put("deleted", b.storedSessionId)
                    } catch (e: Throwable) {
                        if (e is CancellationException || withdrawn.isEmpty()) throw e
                        throw DeleteKeptWaiting(withdrawn)
                    }
                }
                val fresh = c.nativeRpc("session.resume", json("session_id" to sid(), "omit_messages" to true, "close_on_disconnect" to false))
                val target = nativeSessionBinding(c.scope, fresh)
                require(target.storedSessionId == sid()) { "Conversation identity changed. Reopen before deleting." }
                require(fresh.opt("running") is Boolean) { "Refresh this chat to see if the reply is still running." }
                require(fresh.opt("running") == false && fresh.opt("inflight") in listOf(null, JSONObject.NULL) && fresh.opt("queued") in listOf(null, JSONObject.NULL)) { "Stop the reply before deleting this chat." }
                val closed = c.nativeRpc("session.close", json("session_id" to target.runtimeSessionId))
                require(closed.opt("closed") == true) { "Conversation close was not confirmed." }
                val deleted = try { c.nativeRpc("session.delete", json("session_id" to target.storedSessionId)) }
                catch (error: ApiFailure) {
                    if (!nativeSessionNotFound(error)) throw error
                    json("deleted" to target.storedSessionId)
                }
                return deleted.also {
                    require(it.string("deleted") == target.storedSessionId) { "Deletion was not confirmed. Refresh before trying again." }
                }
            }
            "/api/session/undo" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (!lease.authority) return c.nativeRpc("session.undo", json("session_id" to b.runtimeSessionId))
                val snapshot = canonicalSnapshot(lease, messages = true)
                val rows = snapshot.rows("messages")
                val target = rows.lastOrNull { it.string("role") == "user" && it.opt("archived") != true }
                    ?: error("There is no active turn to undo.")
                val id = nativeReplayNumber(target.opt("row_id"))?.takeIf { it > 0 } ?: error("The last turn has no durable identity.")
                require(rows.count { nativeReplayNumber(it.opt("row_id")) == id } == 1) { "The last turn has an ambiguous identity." }
                val result = canonicalMutation(lease, snapshot, "rewind", json("target_message_id" to id))
                val message = result.optJSONObject("target_message")
                val removed = nativeReplayNumber(result.opt("rewound_count"))?.takeIf { it > 0 }
                require(message?.string("role") == "user" && nativeReplayNumber(message?.opt("id")) == id && removed != null) { "Undo was not confirmed. Refresh before trying again." }
                return result.put("removed", removed)
            }
            "/api/session/compress" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (!lease.authority) return c.nativeRpc("session.compress", json("session_id" to b.runtimeSessionId, "focus_topic" to d.string("focus_topic")))
                val payload = JSONObject().apply { d.string("focus_topic").takeIf(String::isNotBlank)?.let { put("focus", it) } }
                val result = canonicalMutation(lease, canonicalSnapshot(lease), "compress", payload)
                if (result.string("status") == "noop") return result.put("status", "aborted")
                require(result.string("status").isBlank() && result.string("target_session_id").matches(storedSessionId) && nativeReplayNumber(result.opt("message_count"))?.let { it >= 0 } == true) { "Couldn't confirm the summary. Refresh and try again." }
                return result.put("status", "compressed")
            }
            "/api/session/usage" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                val live = c.nativeTurnRpc(lease, if (lease.authority) "session.stats" else "session.usage", json("session_id" to b.runtimeSessionId))
                // The category split is Hermes' own session.context_breakdown; an older gateway without it still gets the plain figures.
                val breakdown = try {
                    c.nativeTurnRpc(lease, "session.context_breakdown", json("session_id" to b.runtimeSessionId))
                } catch (e: CancellationException) { throw e }
                catch (e: ApiFailure) { if (e.status == 401 || e.status == 403) throw e else null }
                catch (_: Exception) { null }
                // A recycled runtime counts from zero; Hermes keeps the chat's real totals on its stored row.
                val result = if (!liveUsageIsEmpty(live)) live else {
                    val row = try { c.nativeGet("/api/sessions/${b.storedSessionId}", mapOf("profile" to c.auth.profile)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    if (row != null) usageWithStoredTotals(live, row) else live
                }
                if (breakdown != null) {
                    // The breakdown carries the same occupancy the dashboard reads; fill what the live counters omitted.
                    for (key in listOf("context_used", "context_max")) {
                        (breakdown.opt(key) as? Number)?.toLong()?.takeIf { it > 0 }?.let { result.put(key, it) }
                    }
                    for (key in listOf("context_percent", "context_source", "context_estimated")) if (breakdown.has(key)) result.put(key, breakdown.opt(key))
                    breakdown.optString("model").takeIf(String::isNotBlank)?.let { result.put("model", it) }
                    result.put("context_breakdown", breakdown)
                }
                return result
            }
            "/api/session/update" -> {
                val b = binding()
                if (d.has("model")) {
                    val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                    if (lease.authority) {
                        val model = d.getString("model"); val provider = d.string("model_provider")
                        require(model.isNotBlank() && (!d.has("scope_key") || d.string("scope_key") == c.scope.session(b.storedSessionId).key)) { "Reopen the model picker in its original chat." }
                        val version = c.nativeAuthorityVersion() ?: error("Sign-in changed. Reopen the model picker.")
                        val key = c.scope.session(b.storedSessionId).key
                        val payload = json("model" to model).apply { if (provider.isNotBlank()) put("provider", provider) }
                        val confirmed = d.optBoolean("confirm_expensive_model")
                        val guard = synchronized(canonicalModelGuards) { canonicalModelGuards.remove(key) }
                        if (confirmed) {
                            require(guard != null && guard.version == version && guard.model == model && guard.provider == provider) { "Model confirmation expired. Reopen the picker." }
                            payload.put("confirm", guard.token)
                        }
                        val result = canonicalMutation(lease, canonicalSnapshot(lease), "model", payload)
                        if (result.string("status") == "confirmation_required") {
                            val message = result.string("confirm_message")
                            require(!confirmed) { message }
                            synchronized(canonicalModelGuards) {
                                canonicalModelGuards[key] = CanonicalModelGuard(version, model, provider, result.getString("confirm"))
                                if (canonicalModelGuards.size > 16) canonicalModelGuards.remove(canonicalModelGuards.keys.first())
                            }
                            throw NativeModelConfirmation(message)
                        }
                        require(result.opt("model") is String && result.string("model").isNotBlank()) { "Model change was not confirmed." }
                        val fresh = canonicalSnapshot(lease, messages = true)
                        val updated = nativeSessionBinding(c.scope, fresh)
                        require(updated == b) { "Model change returned another conversation. Refresh before sending." }
                        val history = passiveHistory(updated.storedSessionId, fresh)
                        sessionProjection(fresh, updated).let { live -> live.keys().forEach { key -> if (key != "messages") history.put(key, live.opt(key)) } }
                        return json("session" to history, "deferred" to (result.string("status") == "deferred"))
                    }
                    // Hermes keeps a pick that names its provider only on a built agent; a chat still resuming would lose it when this socket closes.
                    // A live lazy runtime ignores eager_build, so this scoped read is what starts the agent and waits for it (as in awaitNativeVoiceAgent).
                    // A failed build (5032) is left to config.set, which repairs it.
                    try { c.nativeRpc("approval.pending", json("session_id" to b.runtimeSessionId)) } catch (e: ApiFailure) { if (e.status != 5032) throw e }
                    val result = c.nativeRpc("config.set", json("session_id" to b.runtimeSessionId, "key" to "model", "value" to (d.getString("model") + d.string("model_provider").takeIf { it.isNotBlank() }?.let { " --provider $it" }.orEmpty()), "scope" to "session", "confirm_expensive_model" to d.optBoolean("confirm_expensive_model")))
                    if (result.optBoolean("confirm_required")) {
                        val message = result.string("confirm_message", "Hermes requires confirmation before selecting this model.")
                        require(!d.optBoolean("confirm_expensive_model")) { message }
                        throw NativeModelConfirmation(message)
                    }
                    val fresh = c.nativeRpc("session.resume", json("session_id" to b.storedSessionId, "close_on_disconnect" to false, "omit_messages" to false))
                    val updated = nativeSessionBinding(c.scope, fresh)
                    require(updated.scope == b.scope && updated.storedSessionId == b.storedSessionId) { "Model change returned another conversation. Refresh before sending." }
                    val history = passiveHistory(updated.storedSessionId, fresh)
                    // REST owns saved image receipts; the confirmed live snapshot owns model and run state.
                    sessionProjection(fresh, updated).let { live -> live.keys().forEach { key -> if (key != "messages") history.put(key, live.opt(key)) } }
                    return json("session" to history, "deferred" to result.optBoolean("deferred"))
                }
                if (d.has("workspace")) {
                    val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                    if (!lease.authority) return c.nativeRpc("session.cwd.set", json("session_id" to b.runtimeSessionId, "cwd" to d.getString("workspace")))
                    val result = canonicalMutation(lease, canonicalSnapshot(lease), "workspace", json("cwd" to d.getString("workspace")))
                    require(result.string("cwd").isNotBlank()) { "Workspace change was not confirmed." }
                    return result
                }
                throw ApiFailure(501, "These conversation settings are unavailable in native Hermes")
            }
            "/api/reasoning", "/api/mobile/beacon/reasoning" -> {
                val params = json("session_id" to binding().runtimeSessionId, "key" to "reasoning")
                if (method != "GET") { require(!d.has("display") && d.string("effort") in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")) { "Choose a native reasoning effort; display preferences are local" }; params.put("value", d.getString("effort")).put("scope", "session") }
                val result = c.nativeRpc(if (method == "GET") "config.get" else "config.set", params)
                if (method != "GET") {
                    val effective = c.nativeRpc("config.get", json("session_id" to params.getString("session_id"), "key" to "reasoning"))
                    require(effective.string("value").isNotBlank() && effective.string("value") == result.string("value")) { "Reasoning change was not confirmed. Refresh before trying again." }
                    return effective
                }
                if (q["model"].orEmpty().isNotBlank()) {
                    val capabilities = c.nativeGet("/api/plugins/beacon/admin/model-capabilities", q.filterKeys { it in setOf("model", "provider") })
                    result.put("options", capabilities.getJSONArray("reasoning_options")).put("known", capabilities.getBoolean("reasoning_known"))
                }
                return result
            }
            "/api/models" -> {
                val result = c.nativeGet("/api/model/options", q + mapOf("profile" to c.auth.profile, "include_unconfigured" to "false"))
                return result.put("groups", JSONArray(result.rows("providers").map { p ->
                    // `aliases` is Hermes' own list of the other names a custom provider goes by.
                    json("provider_id" to p.string("slug"), "provider" to p.string("name"), "aliases" to (p.optJSONArray("aliases") ?: JSONArray()),
                        "models" to JSONArray(p.optJSONArray("models")?.strings().orEmpty().map { json("id" to it, "label" to it) }))
                }))
            }
            // The live agent may report its provider under another name than the picker row's slug ("github-copilot" for the "copilot" row);
            // Hermes' session-scoped model.options marks the row it means. The picker asks only for a chat that already runs here, never for a browsed one.
            "/api/model/current" -> {
                require(method == "GET" && sid().matches(storedSessionId)) { "Choose a conversation first" }
                val b = binding()
                require(b.storedSessionId == sid()) { "Model status belongs to another conversation" }
                val options = c.nativeRpc("model.options", json("session_id" to b.runtimeSessionId))
                return json("session_id" to sid(), "scope_key" to c.scope.key, "provider" to options.string("provider"),
                    "provider_id" to options.rows("providers").firstOrNull { it.optBoolean("is_current") }?.string("slug").orEmpty())
            }
            "/api/skills" -> { require(method == "GET"); val result = c.nativeGet(path, q + ("profile" to c.auth.profile)); return result.put("skills", result.optJSONArray("items") ?: result.optJSONArray("skills") ?: JSONArray()) }
            "/api/skills/content", "/api/memory" -> { require(method == "GET"); return c.nativeGet(path, q + ("profile" to c.auth.profile)) }
            "/api/profiles" -> return c.nativeGet(path)
            "/api/toolsets" -> return c.nativeGet("/api/tools/toolsets", q + ("profile" to c.auth.profile), method, body, maxBytes)
            "/api/cron/jobs" -> return c.nativeGet(path, q + ("profile" to c.auth.profile), method, body)
            "/api/pc/files" -> {
                require(method == "GET")
                return c.nativeGet("/api/files", d.string("path").takeIf(String::isNotBlank)?.let { mapOf("path" to it) } ?: emptyMap())
                    .put("_requested_path", d.string("path"))
            }
            "/api/pc/file" -> { require(method == "GET"); return readTextFile(d.getString("path")).put("_pc", true) }
            "/api/pc/file/save" -> { require(method == "POST"); return saveTextFile(d) }
            "/api/pc/file/create" -> { require(method == "POST"); return c.nativeRequest("/api/plugins/beacon/mobile/files/create", "POST", d) }
            "/api/list" -> return c.nativeGet("/api/fs/list", mapOf("path" to workspacePath(sid(), d.string("path", ".")), "profile" to c.auth.profile))
            "/api/file" -> return readTextFile(workspacePath(sid(), d.getString("path")))
            "/api/file/create" -> return c.nativeRequest("/api/plugins/beacon/mobile/files/create", "POST", json("session_id" to sid(), "path" to workspacePath(sid(), d.getString("path")), "content" to d.string("content")))
            "/api/file/save" -> {
                val path = workspacePath(sid(), d.getString("path"))
                return saveTextFile(d.copy().put("path", path))
            }
        }
        if (path.startsWith("/api/mobile/receipts/")) {
            require(method == "GET") { "Receipt checks are read-only" }
            return submissionReceipt(d.string("session_id"), path.removePrefix("/api/mobile/receipts/"), d.string("admission_id"), d.string("completed_proof") == "1")
        }
        if (NativeOperationPolicy.direct(path, method)) return c.nativeGet(path, q + ("profile" to c.auth.profile), method, body, maxBytes)
        throw ApiFailure(501, "This operation is unavailable in native Hermes: ${path.removePrefix("/api/")}")
    }

    /** Resolve an uncertain send by native identity only; absence never authorizes another submission. */
    private suspend fun submissionReceipt(stored: String, input: String, knownAdmission: String, completedProof: Boolean): JSONObject {
        require(stored.matches(storedSessionId) && input.matches(storedSessionId)) { "Choose a message to check." }
        val b = c.nativeSession(stored)
        require(b.scope == c.scope && b.storedSessionId == stored) { "The admission belongs to another conversation" }
        val lease = c.nativeTurnLease(stored, b.runtimeSessionId)
        if (!lease.authority) throw ApiFailure(501, "Native Hermes has no prompt receipt lookup. Refresh conversation history before deciding whether to resend; your draft is retained.")
        val snapshot = c.nativeSessionSnapshot()
        fun profileId(info: JSONObject?): String? {
            if (info == null) return null
            if (info.has("profile_name") && !info.isNull("profile_name")) require(info.opt("profile_name") == c.scope.profile) { "The admission belongs to another conversation" }
            if (info.has("session_id") && !info.isNull("session_id")) require(info.opt("session_id") == b.runtimeSessionId) { "The admission belongs to another conversation" }
            if (!info.has("profile_id") || info.isNull("profile_id")) return null
            return (info.opt("profile_id") as? String)?.takeIf(String::isNotBlank) ?: error("The admission belongs to another conversation")
        }
        var info = snapshot.optJSONObject("info")
        val profile = profileId(info)
            ?: profileId(c.nativeTurnRpc(lease, "session.info", json("session_id" to b.runtimeSessionId)).also { info = it })
            ?: return json("status" to "unknown")
        var admission = knownAdmission
        if (admission.isBlank()) {
            val candidates = snapshot.rows("pending").toMutableList()
            if (candidates.none { it.string("input_id") == input }) {
                val replay = c.nativeTurnRpc(lease, "session.events.since", json("session_id" to b.runtimeSessionId, "last_seen" to 0).apply {
                    snapshot.string("replay_epoch").takeIf(String::isNotBlank)?.let { put("replay_epoch", it) }
                })
                replay.rows("events").filter { it.string("session_id") == b.runtimeSessionId && it.string("type") == "session.info" }
                    .forEach { candidates += it.optJSONObject("payload")?.rows("pending").orEmpty() }
            }
            val matches = candidates.filter { it.string("input_id") == input }
            matches.forEach { require(it.optJSONObject("ref")?.string("session_id") == stored && it.optJSONObject("ref")?.opt("profile_id") == profile) { "The admission belongs to another conversation" } }
            val ids = matches.map { it.string("admission_id") }.distinct()
            require(ids.size <= 1 && ids.none(String::isBlank)) { "The saved submission identity is ambiguous" }
            admission = ids.singleOrNull().orEmpty()
        }
        if (admission.isBlank()) return json("status" to "unknown")
        val receipt = c.nativeTurnRpc(lease, "prompt.receipt", json("session_id" to b.runtimeSessionId, "admission_id" to admission)
            .apply { if (completedProof) put("include_result", true) })
        val ref = receipt.optJSONObject("ref")
        require(receipt.string("admission_id") == admission && ref?.string("session_id") == stored && ref.opt("profile_id") == profile) { "The admission belongs to another conversation" }
        receipt.remove("_confirmed_persisted_turn")
        receipt.remove("_confirmed_persisted_input")
        val epoch = snapshot.string("replay_epoch")
        // The committed settlement rows outlive the runtime event ring; use them first, then fall
        // back to the retained message.complete event for older native versions without a stored result.
        val settled = if (completedProof) nativeSettledTurn(receipt, input) else null
        // R3-F5: a steer that came after the turn's last tool step is left over when the turn ends. On a classic-CLI surface (every chat Beacon
        // creates: a canonical create without a source is "cli") Hermes hands it back here for the client to send as its own next turn
        // (gateway/session_cli_turn.take_cli_result_fields); on any other surface Hermes runs it itself as the turn's follow-up.
        receipt.optJSONObject("result")?.takeIf { completedProof }?.let { result ->
            when (info?.string("source").orEmpty()) {
                "" -> Unit
                in CLI_SURFACES -> receipt.put("pending_steer", result.opt("pending_steer") as? String ?: "")
                else -> receipt.put("steer_followup", true)
            }
        }
        receipt.remove("result")
        receipt.remove("usage")
        if (settled != null) {
            receipt.put("_confirmed_persisted_input", settled)
            receipt.put("_confirmed_persisted_turn", settled)
        } else if (completedProof && receipt.string("status") == "terminal" && receipt.string("outcome") == "completed" && epoch.isNotBlank()) {
            val retained = nativeRetainedReplay(epoch) { after ->
                c.nativeTurnRpc(lease, "session.events.since", json("session_id" to b.runtimeSessionId, "last_seen" to after, "replay_epoch" to epoch))
            }
            retained?.let { (after, replay) -> nativeCompletedTurn(replay, b.runtimeSessionId, epoch, receipt, input, after, requireComplete = false) }
                ?.let { proof ->
                    receipt.put("_confirmed_persisted_input", proof)
                    if (proof.opt("complete") == true) receipt.put("_confirmed_persisted_turn", proof)
                }
        }
        // A reply Hermes lost in a restart holds this message (or is it): the chat closes that turn ([closeLostTurns]) instead of waiting forever.
        if (receipt.string("status") == "unknown" || snapshot.rows("pending").any { it.string("status") == "unknown" }) receipt.put("lost_turn", true)
        return receipt.put("session_id", stored).put("stream_id", b.runtimeSessionId)
    }

    private suspend fun nativeTextFile(path: String): JSONObject =
        c.nativeGet("/api/fs/read-text", mapOf("path" to path, "profile" to c.auth.profile)).apply {
            require(opt("text") is String) { "Reopen this file before editing it." }
            put("content", if (optBoolean("binary")) "" else opt("text"))
            if (optBoolean("binary") || '\uFFFD' in string("text")) put("edit_blocked_reason", "This file can't be edited here.")
            else if (optBoolean("truncated")) put("edit_blocked_reason", "Only part of this file is shown. Download it to edit.")
        }

    private suspend fun readTextFile(path: String): JSONObject = try {
        c.nativeGet("/api/plugins/beacon/mobile/files/text", mapOf("path" to path))
    } catch (error: ApiFailure) {
        if (error.status != 404 && !(error.plugin && error.status == 401)) throw error
        nativeTextFile(path)
    }

    private suspend fun saveTextFile(fields: JSONObject): JSONObject {
        require(fields.opt("content") is String && fields.opt("expected_content") is String) { "Reopen this file before saving; its disk revision is required." }
        val text = fields.string("content")
        // The plugin keeps the disk's newlines and checks the exact bytes itself; the client checks only the normalized length.
        val normalized = text.replace("\r\n", "\n")
        require(normalized.toByteArray(Charsets.UTF_8).size <= 512 * 1024 && '\u0000' !in text) { "This file can't be edited on the phone. Your draft is kept." }
        try { return c.nativeRequest("/api/plugins/beacon/mobile/files/text", "POST", fields) }
        catch (error: ApiFailure) { if (error.status != 404 && !(error.plugin && error.status == 401)) throw error }
        // Stock Hermes writes platform newlines the client cannot see: assume each newline may become two bytes.
        require(normalized.toByteArray(Charsets.UTF_8).size.toLong() + normalized.count { it == '\n' } <= 512 * 1024) { "This file can't be edited on the phone. Your draft is kept." }
        val path = fields.getString("path")
        val latest = nativeTextFile(path)
        require('\uFFFD' !in latest.string("text") && '\uFFFD' !in fields.getString("expected_content")) { "This file can't be edited here." }
        require(!latest.optBoolean("truncated") && !latest.optBoolean("binary") && latest.string("text") == fields.getString("expected_content")) { "This file changed on the computer. Reopen it to save; your edits are kept." }
        // Stock Hermes chooses platform newlines; preserving disk formatting requires the optional plugin.
        // shortcut: stock write-text has no compare-and-write, so two devices saving in the same instant are last-writer-wins; use the plugin's revision-locked save if native Hermes adds a precondition.
        val result = c.nativeGet("/api/fs/write-text", mapOf("profile" to c.auth.profile), "POST", json("path" to path, "content" to text.replace("\r\n", "\n")))
        val actual = nativeTextFile(path)
        require(!actual.optBoolean("truncated") && !actual.optBoolean("binary") && '\uFFFD' !in actual.string("text")) { "Reopen or download this file to edit it again." }
        return result.put("content", actual.string("text")).put("byteSize", actual.optLong("byteSize")).put("path", actual.string("path", path))
    }

    /** The optional plugin's voice archive. Any failure, including its own 401, only marks voice history unavailable. */
    private suspend fun withVoiceHistory(snapshot: JSONObject, id: String): JSONObject {
        require(id.matches(storedSessionId)) { "Invalid native stored session identity" }
        try {
            // Only this local timeout yields null; an external cancellation still propagates below.
            val archive = kotlinx.coroutines.withTimeoutOrNull(OPTIONAL_VOICE_HISTORY_TIMEOUT_MS) {
                c.detached().nativeGet("/api/plugins/beacon/mobile/sessions/$id/voice-history", mapOf("profile" to c.auth.profile), persistAuth = false)
            }
            if (archive == null) unavailableVoiceHistory(snapshot) else {
                archivedVoiceTranscripts(c.scope.session(id), archive)
                snapshot.put("voice_transcripts", archive.optJSONArray("events") ?: JSONArray()).put("voice_transcripts_available", true).put("voice_transcripts_truncated", archive.optBoolean("truncated"))
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { unavailableVoiceHistory(snapshot) }
        return snapshot.put("profile", c.auth.profile)
    }
    private fun unavailableVoiceHistory(snapshot: JSONObject) = snapshot.put("voice_transcripts_available", false).put("voice_transcripts", JSONArray())

    private fun ownedByProfile(row: JSONObject) = row.string("profile").let { it.isBlank() || it == c.auth.profile }

    /**
     * Stock dashboard conversation index, as Hermes Relay's DashboardApiClient.listSessions reads it (MIT; see
     * licenses/Hermes-Relay-MIT.txt): the newest [SESSION_INDEX_WINDOW] rows in profile-scoped pages of at most
     * [SESSION_PAGE_LIMIT], stopping at a short page. Search covers only titles and identities within that window.
     */
    private suspend fun sessionIndex(archived: Boolean, query: String, threads: Boolean = false, hidden: String = "", order: String = ""): JSONObject {
        // Filters run in Hermes, so the 200-row window holds only what the drawer shows.
        val hiddenSources = hidden.split(',').map { it.trim().lowercase() }.filter { it.matches(sourceName) }
        val scope = if (threads) mapOf("sources" to THREAD_SOURCES.joinToString(",")) else mapOf("exclude_sources" to (listOf("cron") + hiddenSources).distinct().joinToString(","))
        val rows = LinkedHashMap<String, JSONObject>()
        var offset = 0
        while (offset < SESSION_INDEX_WINDOW) {
            val limit = minOf(SESSION_PAGE_LIMIT, SESSION_INDEX_WINDOW - offset)
            val page = c.nativeGet("/api/sessions", mapOf("profile" to c.auth.profile, "archived" to if (archived) "only" else "exclude", "order" to if (order == "created") "created" else "recent", "min_messages" to if (threads) "0" else "1", "limit" to "$limit", "offset" to "$offset") + scope)
            val items = listOf("sessions", "items", "data").firstNotNullOfOrNull { page.opt(it) as? JSONArray } ?: error("Hermes returned an unreadable conversation list")
            for (index in 0 until items.length()) {
                val row = items.opt(index) as? JSONObject ?: error("Hermes returned an unreadable conversation list")
                val id = row.string("id", row.string("session_id"))
                require(id.matches(storedSessionId)) { "Hermes returned a conversation without a valid identity" }
                require(ownedByProfile(row)) { "The conversation list belongs to another profile" }
                rows.putIfAbsent(id, row)
            }
            if (items.length() < limit) break
            offset += limit
        }
        // A thread waiting for its first post is still a channel the owner has: list empty threads with the others.
        val mergedThreads = mutableSetOf<String>()
        if (!threads && !archived) {
            val empty = try {
                c.nativeGet("/api/sessions", mapOf("profile" to c.auth.profile, "archived" to "exclude", "order" to if (order == "created") "created" else "recent",
                    "min_messages" to "0", "sources" to THREAD_SOURCES.joinToString(","), "limit" to "$SESSION_PAGE_LIMIT"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Only the empty threads wait for the next refresh; a sign-in that ended still ends here.
                if (e is NativeAuthExpiredException || e is LoopbackSessionRefreshFailed || e is CachedIdentityMismatch || e is ApiFailure && e.status == 401) throw e
                null
            }
            val items = listOf("sessions", "items", "data").firstNotNullOfOrNull { empty?.opt(it) as? JSONArray } ?: JSONArray()
            for (index in 0 until items.length()) {
                val row = items.opt(index) as? JSONObject ?: continue
                val id = row.string("id", row.string("session_id"))
                // Only an empty thread joins (an older Hermes may ignore the sources filter); empty plain chats stay out.
                if (!id.matches(storedSessionId) || !ownedByProfile(row) || row.optInt("message_count") > 0 ||
                    row.string("source").lowercase() !in THREAD_SOURCES) continue
                if (rows.putIfAbsent(id, row) == null) mergedThreads += id
            }
        }
        // A chat marked as a thread (made where Hermes cannot store the thread source) reads as one; the lane and the empty ones are read by id.
        if (!archived) {
            val marked = threadMarkIds()
            if (marked.isNotEmpty()) {
                rows.forEach { (id, row) -> if (id in marked) row.put("source", BEACON_THREAD_SOURCE) }
                markedThreadRows(marked.filter { it !in rows }.takeLast(MARKED_THREAD_FETCH_LIMIT)).forEach { (id, row) ->
                    // A thread with messages is already in the main list's window; the lane lists every marked thread.
                    if (threads || row.optInt("message_count") == 0) { if (rows.putIfAbsent(id, row) == null) mergedThreads += id }
                }
            }
        }
        // REST has no include_hidden option. Gateway session.list is read-only and does not resume an agent.
        if (archived) {
            rows.entries.removeAll { !nativeSessionFlag(it.value, "archived") }
            val params = json("profile" to c.auth.profile, "limit" to SESSION_INDEX_WINDOW)
            val visibleIds = c.nativeRpc("session.list", params).rows("sessions").map(::sessionRowId).toSet()
            val candidates = c.nativeRpc("session.list", params.copy().put("include_hidden", true)).rows("sessions")
                .filter { sessionRowId(it) !in visibleIds }.distinctBy(::sessionRowId)
            val verified = coroutineScope {
                val permits = Semaphore(6)
                candidates.map { candidate -> async { permits.withPermit {
                    val id = sessionRowId(candidate)
                    require(id.matches(storedSessionId)) { "Conversation list is unavailable. Try again." }
                    // Verify the flag, including when the two bounded list windows moved during the read.
                    val row = try { c.nativeGet("/api/sessions/$id", mapOf("profile" to c.auth.profile)) }
                        catch (error: ApiFailure) { if (error.status == 404) return@withPermit null else throw error }
                    require(ownedByProfile(row) && row.string("id", row.string("session_id")) == id) { "Conversation list belongs to another profile" }
                    val source = row.string("source").lowercase()
                    if (nativeSessionFlag(row, "hidden") && candidate.optInt("message_count", row.optInt("message_count")) > 0 && source != "cron" &&
                        (if (threads) source in THREAD_SOURCES else source !in hiddenSources)) id to row else null
                } } }.awaitAll().filterNotNull()
            }
            verified.forEach { (id, row) -> rows[id] = row }
        } else rows.entries.removeAll { nativeSessionFlag(it.value, "hidden") || nativeSessionFlag(it.value, "archived") }
        val window = if (archived || mergedThreads.any(rows::containsKey)) rows.entries.sortedByDescending { (_, row) -> row.optDouble(if (order == "created") "started_at" else "last_active", row.optDouble("started_at", 0.0)) } else rows.entries.toList()
        // Hermes back-fills every pin onto a page, so a pinned chat stays past the window after a re-sort too.
        val listed = window.filterIndexed { index, entry -> index < SESSION_INDEX_WINDOW || nativeSessionFlag(entry.value, "pinned") }.filter { (id, row) -> query.isBlank() || row.string("title").contains(query, true) || id.contains(query, true) }
            .map { (id, row) -> id to row }
        // Words inside the messages find a chat too: Hermes' own search answers for the chats a title cannot.
        val found = listed.map { it.first }.toSet()
        val shown = listed + (if (query.isBlank() || archived) emptyList() else sessionContentHits(query, threads, hiddenSources, found))
        return json("sessions" to JSONArray(shown.map { (id, row) -> row.copy().put("session_id", id).put("profile", c.auth.profile).put("archived", nativeSessionFlag(row, "archived") || nativeSessionFlag(row, "hidden")).put("pinned", nativeSessionFlag(row, "pinned")) }),
            "search_scope" to "Conversation titles and message text", "limit" to SESSION_INDEX_WINDOW)
    }

    /** Remember a chat as a thread on this phone and in the plugin (for the owner's other phones and scheduled posts); without the plugin route this phone still lists it. */
    private suspend fun markAsThread(id: String) {
        ThreadMarks.add(c.scope, id)
        try { c.nativeRequest("/api/plugins/beacon/mobile/sessions/$id/thread", "POST", JSONObject()) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* an older plugin: the mark on this phone is enough to list it */ }
    }

    /** The chats known to be threads: this phone's marks plus the plugin's, read at most once a minute and only as a best effort. */
    private suspend fun threadMarkIds(): Set<String> {
        val now = System.currentTimeMillis()
        val account = c.scope.copy(session = "").key
        val due = synchronized(threadMarkReads) { (now - (threadMarkReads[account] ?: 0L) >= THREAD_MARK_READ_MS).also { if (it) threadMarkReads[account] = now } }
        if (due) try {
            // Optional, like the voice archive: any failure, including the plugin's own 401, only leaves this phone's marks.
            val listed = kotlinx.coroutines.withTimeoutOrNull(OPTIONAL_VOICE_HISTORY_TIMEOUT_MS) {
                c.detached().nativeGet("/api/plugins/beacon/mobile/threads", mapOf("profile" to c.auth.profile), persistAuth = false)
            }
            if (listed == null) synchronized(threadMarkReads) { threadMarkReads.remove(account) }
            else ThreadMarks.add(c.scope, *listed.rows("threads").map { it.string("id") }.filter { it.matches(storedSessionId) }.toTypedArray())
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { synchronized(threadMarkReads) { threadMarkReads.remove(account) } }  // an older plugin or a busy one: ask again with the next list
        return ThreadMarks.ids(c.scope)
    }

    /** The marked threads the index did not return (the thread source filter cannot match them), read by id; a chat Hermes no longer has loses its mark. */
    private suspend fun markedThreadRows(ids: List<String>): List<Pair<String, JSONObject>> = coroutineScope {
        val permits = Semaphore(6)
        ids.map { id -> async { permits.withPermit {
            try {
                val row = c.nativeGet("/api/sessions/$id", mapOf("profile" to c.auth.profile))
                if (row.string("id", row.string("session_id")) == id && ownedByProfile(row) && !nativeSessionFlag(row, "hidden") && !nativeSessionFlag(row, "archived"))
                    id to row.copy().put("session_id", id).put("source", BEACON_THREAD_SOURCE) else null
            } catch (e: Exception) {
                if (e is CancellationException || e is NativeAuthExpiredException || e is LoopbackSessionRefreshFailed || e is CachedIdentityMismatch || e is ApiFailure && e.status == 401) throw e
                if (e is ApiFailure && e.status == 404) ThreadMarks.remove(c.scope, id)
                null
            }
        } } }.awaitAll().filterNotNull()
    }

    /** The chats whose message text matches [query], from Hermes' own search (the stock dashboard route); a search that fails only leaves the title matches. */
    private suspend fun sessionContentHits(query: String, threads: Boolean, hidden: List<String>, alreadyListed: Set<String>): List<Pair<String, JSONObject>> {
        val scope = if (threads) mapOf("sources" to THREAD_SOURCES.joinToString(",")) else mapOf("exclude_sources" to (listOf("cron") + hidden).distinct().joinToString(","))
        val page = try { c.nativeGet("/api/sessions/search", mapOf("profile" to c.auth.profile, "q" to query.trim().take(200), "limit" to "30") + scope) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is NativeAuthExpiredException || e is LoopbackSessionRefreshFailed || e is CachedIdentityMismatch || e is ApiFailure && e.status == 401) throw e
                return emptyList()
            }
        val hits = page.rows("results").mapNotNull { row ->
            val id = row.string("session_id", row.string("id"))
            // A chat the title search already lists is not read again for its message text.
            if (id in alreadyListed || !id.matches(storedSessionId) || !ownedByProfile(row) || nativeSessionFlag(row, "archived") || nativeSessionFlag(row, "hidden") || row.string("source").lowercase() == "cron") null else id to row
        }
        // Hermes searches the stored text, which includes the notes it writes into a user row (a photo's description and file path). Only words the owner can see count.
        return coroutineScope {
            val permits = Semaphore(4)
            hits.map { hit -> async { permits.withPermit { hit.takeIf { visibleContentHit(it.first, it.second, query) }?.let { (id, row) -> id to row.copy().put("hidden", contentHitHidden(id)) } } } }.awaitAll().filterNotNull()
        }
    }

    /** True when the words of [query] are in what the owner sees of the chat. Only a stored user row can hold Hermes' own note, so every other hit stands; a user-row hit is read against the chat's newest rows, cleaned as the chat cleans them. A check that cannot be made leaves the hit out. */
    /** Whether Hermes keeps the chat [id] hidden (archived from a sidebar); unreadable reads as not hidden. */
    private suspend fun contentHitHidden(id: String): Boolean = try { nativeSessionFlag(c.nativeGet("/api/sessions/$id", mapOf("profile" to c.auth.profile)), "hidden") }
        catch (e: Exception) {
            if (e is CancellationException || e is NativeAuthExpiredException || e is LoopbackSessionRefreshFailed || e is CachedIdentityMismatch || e is ApiFailure && e.status == 401) throw e
            false
        }
    private suspend fun visibleContentHit(id: String, hit: JSONObject, query: String): Boolean {
        if (hit.string("role") != "user") return true
        val page = try { c.nativeGet("/api/sessions/$id/messages", mapOf("profile" to c.auth.profile, "order" to "latest", "limit" to "$HISTORY_WINDOW", "offset" to "0", "inline_images" to "false")) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (e is NativeAuthExpiredException || e is LoopbackSessionRefreshFailed || e is CachedIdentityMismatch || e is ApiFailure && e.status == 401) throw e
                return false
            }
        return (page.opt("messages") as? JSONArray)?.objects().orEmpty().any { row ->
            row.string("role") == "user" && visibleSearchMatch(splitAttachedMedia(messageContentText("user", historyRowContent(row))).text, query)
        }
    }

    /**
     * Read-only transcript: stock metadata plus the newest [HISTORY_WINDOW] messages (Relay getSessionMessages, LATEST).
     * Browsing creates no socket, resume or live identity. Model confirmation may supply an unprompted native shell
     * before its first saved row. The UI keeps the requested id; compression still requires a verified descendant chain.
     */
    internal suspend fun passiveHistory(requested: String, liveShell: JSONObject? = null): JSONObject {
        require(requested.matches(storedSessionId)) { "Invalid native stored session identity" }
        val scoped = mapOf("profile" to c.auth.profile)
        val meta = try { c.nativeGet("/api/sessions/$requested", scoped) } catch (error: ApiFailure) {
            val messages = liveShell?.optJSONArray("messages")
            // Native unpersisted resumes name stored_session_id; only runtime pivots may precede the first prompt.
            if (error.status != 404 || liveShell == null || liveShell.has("session_key") || liveShell.string("stored_session_id") != requested ||
                messages == null || liveShell.opt("message_count") !is Number || liveShell.optDouble("message_count") != messages.length().toDouble() ||
                messages.objects().size != messages.length() || messages.objects().any { it.string("display_kind") != "model_switch" } ||
                liveShell.optBoolean("running") || liveShell.opt("inflight") !in listOf(null, JSONObject.NULL)) throw error
            return withVoiceHistory(sessionProjection(liveShell, nativeSessionBinding(c.scope, liveShell)), requested)
        }
        require(meta.string("id", meta.string("session_id")) == requested && ownedByProfile(meta)) { "Conversation scope changed" }
        val page = c.nativeGet("/api/sessions/$requested/messages", scoped + mapOf("order" to "latest", "limit" to "$HISTORY_WINDOW", "offset" to "0", "inline_images" to "false"))
        val messages = page.opt("messages") as? JSONArray ?: error("Hermes returned an unreadable conversation history")
        require(ownedByProfile(page)) { "Conversation scope changed" }
        val resolved = page.string("session_id")
        require(resolved.matches(storedSessionId)) { "Hermes returned a conversation without a valid identity" }
        if (resolved != requested) requireDescendant(requested, resolved, scoped)
        val rows = JSONArray()
        for (index in 0 until messages.length()) {
            val row = (messages.opt(index) as? JSONObject)?.copy() ?: error("Hermes returned an unreadable conversation history")
            // Dashboard rows carry the SQLite row id as numeric `id`; gateway rows name it `row_id` (Relay MessageItem.resolvedRowId).
            val rowId = row.opt("id")
            if (!row.has("row_id") && (rowId is Number || rowId is String && rowId.toLongOrNull() != null)) row.put("row_id", rowId)
            if (row.string("role") == "user") {
                val content = row.string("content")
                val paths = persistedImagePaths(content)
                val attachments = row.optJSONArray("attachments") ?: JSONArray()
                paths.filterNot { path -> attachments.objects().any { it.string("path") == path } }.forEach { path ->
                    attachments.put(json("path" to path, "name" to path.substringAfterLast('/').substringAfterLast('\\')))
                }
                // Saved inline bytes stay authoritative even if the original file changes or disappears.
                val images = Regex("(?m)^\\[image]$").findAll(content).toList()
                if (row.optLong("row_id") > 0) images.forEachIndexed { imageIndex, _ ->
                    val canonical = if (paths.size == images.size) attachments.objects().firstOrNull { it.string("path") == paths[imageIndex] && !it.has("history_image_index") } else null
                    (canonical ?: json("name" to "Image ${imageIndex + 1}").also(attachments::put))
                        .put("history_row_id", row.optLong("row_id")).put("history_image_index", imageIndex)
                }
                if (attachments.length() > 0) row.put("attachments", attachments)
            }
            rows.put(row)
        }
        val returned = page.optJSONObject("pagination")?.optInt("returned", messages.length()) ?: messages.length()
        val projection = meta.copy().apply { remove("id"); remove("runtime_session_id"); remove("active_stream_id"); if (has("provider")) put("model_provider", opt("provider")); if (has("cwd")) put("workspace", opt("cwd")) }
            .put("session_id", requested).put("stored_session_id", resolved).put("profile", c.auth.profile)
            .put("_history_metadata_stamp", nativeHistoryStamp(meta)?.let(::JSONArray))
            .put("messages", rows).put("history_limit", HISTORY_WINDOW).put("history_truncated", returned >= HISTORY_WINDOW)
        return withVoiceHistory(storedSessionSettings(projection), requested)
    }

    internal suspend fun historyImage(requested: String, rowId: Long, imageIndex: Int): NativeMediaBytes {
        require(requested.matches(storedSessionId) && rowId > 0 && imageIndex in 0..499) { "Invalid historical image identity" }
        val scoped = mapOf("profile" to c.auth.profile)
        val page = try { c.nativeGet("/api/sessions/$requested/messages/around", scoped + mapOf("row_id" to "$rowId", "limit" to "1")) }
        catch (error: ApiFailure) {
            if (error.status != 404) throw error
            // Around addresses current-session prompts; a compression ancestor can still be in the displayed newest window.
            val compact = c.nativeGet("/api/sessions/$requested/messages", scoped + mapOf("order" to "latest", "limit" to "$HISTORY_WINDOW", "inline_images" to "false"))
            require(ownedByProfile(compact)) { "Historical image belongs to another profile" }
            val rows = compact.rows("messages")
            val index = rows.indexOfFirst { it.optLong("id", it.optLong("row_id")) == rowId }
            require(index >= 0) { "This image is outside the loaded history window. Reopen the conversation." }
            c.nativeGet("/api/sessions/$requested/messages", scoped + mapOf("order" to "latest", "limit" to "1", "offset" to "${rows.size - index - 1}", "inline_images" to "true"))
        }
        require(ownedByProfile(page)) { "Historical image belongs to another profile" }
        val resolved = page.string("session_id")
        require(resolved.matches(storedSessionId)) { "Invalid historical conversation identity" }
        if (resolved != requested) requireDescendant(requested, resolved, scoped)
        val row = page.rows("messages").single()
        require(row.optLong("id", row.optLong("row_id")) == rowId && row.string("role") == "user") { "This image changed. Reopen it to see it." }
        val content = row.opt("content")
        val parts = (content as? JSONArray)?.objects() ?: listOfNotNull(content as? JSONObject)
        val part = parts.filter { it.string("type") in setOf("image_url", "input_image", "image") }.getOrNull(imageIndex) ?: error("Historical image is unavailable")
        val source = part.optJSONObject("image_url")?.string("url") ?: part.string("image_url")
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val header = source.substringBefore(',').lowercase(java.util.Locale.ROOT)
            require(source.length <= 32 * 1024 * 1024 && source.contains(',') && header.matches(Regex("data:image/(?:png|jpg|jpeg|gif|webp|avif|svg\\+xml);base64"))) { "Historical image exceeds the native preview limit or has an unsupported MIME type" }
            NativeMediaBytes(java.util.Base64.getDecoder().decode(source.substringAfter(',')), header.removePrefix("data:").removeSuffix(";base64"))
        }
    }

    /** A different returned id is a compression descendant only if the native chain runs from [requested] to [resolved]. */
    private suspend fun requireDescendant(requested: String, resolved: String, scoped: Map<String, String>) {
        val chain = c.nativeGet("/api/sessions/$requested/latest-descendant", scoped)
        val path = chain.opt("path") as? JSONArray
        val ids = path?.let { values -> (0 until values.length()).map { values.opt(it) as? String ?: "" } }.orEmpty()
        require(chain.string("requested_session_id") == requested && chain.string("session_id") == resolved && chain.opt("changed") == true &&
            ids.size in 2..MAX_COMPRESSION_CHAIN && ids.first() == requested && ids.last() == resolved &&
            ids.all { it.matches(storedSessionId) } && ids.distinct().size == ids.size && ownedByProfile(chain)) { "Conversation history belongs to another conversation" }
    }
    internal suspend fun workspacePath(stored: String, path: String): String {
        if (path.startsWith('/') || path.matches(Regex("^[A-Za-z]:[/\\\\].*"))) return path
        require(stored.isNotBlank()) { "Choose a native conversation first" }
        c.nativeSession(stored)
        val result = c.nativeSessionSnapshot()
        val cwd = result.optJSONObject("info")?.string("cwd").orEmpty()
        require(cwd.isNotBlank()) { "Native conversation has no working directory" }
        return cwd.trimEnd('/', '\\') + "/" + path.removePrefix("./")
    }
    private fun sessionProjection(result: JSONObject, b: NativeSessionBinding): JSONObject {
        val o = (result.optJSONObject("info")?.copy() ?: JSONObject()).apply { result.keys().forEach { put(it, result.opt(it)) } }.apply { if (has("provider")) put("model_provider", opt("provider")); if (has("cwd")) put("workspace", opt("cwd")) }.put("session_id", b.storedSessionId).put("stored_session_id", b.storedSessionId).put("runtime_session_id", b.runtimeSessionId).put("profile", b.scope.profile)
        // Agent construction/history hydration is not a turn; the first prompt must still send.
        if (result.optBoolean("running") || result.has("inflight") && !result.isNull("inflight") || result.string("status") in setOf("streaming", "running", "working", "waiting")) o.put("active_stream_id", b.runtimeSessionId)
        return o
    }
    suspend fun stream(path: String, q: Map<String, String>, lastId: String, onOpen: (suspend () -> Unit)? = null, callback: suspend (SseEvent) -> Unit) {
        try {
        val b = c.nativeSession(q["session_id"])
        if (path == "/api/chat/stream") c.nativeReplay(lastId) else c.nativeReplay(beforePrompt = true)
        // The subscription is live (rules27): the caller learns the link works before any event comes, and a turn waiting on a card sends none.
        onOpen?.invoke()
        c.nativeEvents { raw ->
            val params = raw.optJSONObject("params") ?: JSONObject()
            if (params.string("type") == "gateway.ready") return@nativeEvents
            val payload = (params.optJSONObject("payload") ?: params).copy().put("session_id", b.storedSessionId)
            val promptId = if (params.has("payload")) payload.string("prompt_id") else ""
            // Hermes stamps each frame of the turn it runs with that turn's admission (session_events.publish, stamped at the claim in session_authority._stamp_claim).
            // The payload's own admission_id is not that stamp, so the stamp is kept under its own key.
            params.string("admission_id").takeIf(String::isNotBlank)?.let { payload.put("turn_admission_id", it) }
            val type = if (raw.optString("method") == "reconciliation.required") { params.optJSONObject("history")?.let { payload.put("session", sessionProjection(it, b)) }; if (path == "/api/session/stream") "session_updated" else "state_saved" } else when (params.string("type", raw.string("method"))) {
                "message.delta" -> "token"
                "reasoning.delta", "reasoning.available" -> "reasoning"
                // Hermes' thinking_callback: a spinner face, an empty clear or an explained wait, each replacing the last (gateway/local_viewer_events.py). A status line, not reasoning.
                "thinking.delta" -> "thinking"
                "message.interim" -> "interim_assistant"
                "error" -> "error"
                "message.start" -> "server_turn_started"
                "message.complete" -> if (path == "/api/session/stream") "session_updated" else if (payload.string("status") == "error") { payload.put("message", payload.string("failure_reason", payload.string("error", "Native Hermes turn failed"))); "error" } else "done"
                "tool.start" -> "tool"
                "tool.complete" -> "tool_complete"
                "session.title" -> "title"
                "session.info" -> "session_info"
                "approval" -> "approval"
                "clarify" -> "clarify"
                "request.cancel" -> "request_cancel"
                // The canonical authority asks and withdraws its questions as events carrying the prompt (gateway/session_pending_controls.py), not as requests.
                "approval.request" -> if (promptId.isBlank()) "native_event" else "approval"
                "clarify.request" -> if (promptId.isBlank()) "native_event" else "clarify"
                "approval.settled", "clarify.settled" -> if (promptId.isBlank()) "native_event" else "request_cancel"
                "status.update" -> {
                    payload.string("text").takeIf(String::isNotBlank)?.let { payload.put("message", it) }
                    "warning"
                }
                else -> "native_event"
            }
            if (raw.has("id")) {
                payload.put("id", raw.opt("id")).put("approval_id", params.string("request_id", raw.string("id"))).put("clarify_id", raw.opt("id"))
            }
            // A canonical prompt is known by its id: the card is answered and withdrawn by it.
            if (promptId.isNotBlank() && type in setOf("approval", "clarify", "request_cancel")) {
                payload.put("id", promptId)
                if (type == "approval") payload.put("approval_id", promptId) else if (type == "clarify") payload.put("clarify_id", promptId)
            }
            payload.put("stream_id", b.runtimeSessionId)
            val epoch = params.string("replay_epoch")
            callback(SseEvent(type, payload.toString(), if (params.has("seq")) "${b.runtimeSessionId}:${if (epoch.isBlank()) "" else "$epoch:"}${params.optLong("seq")}" else ""))
            if (path == "/api/chat/stream" && type in setOf("done", "error")) throw NativeTurnEnded()
        } } catch (_: NativeTurnEnded) { } finally { c.closeNative() }
    }
}
