package dev.hermes.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject

/** "high" for an assistant chat whose profile default is slower than that, else null (keep the default). */
internal fun assistantEffortCap(current: String): String? = if (current in setOf("xhigh", "max", "ultra")) "high" else null

/** The precondition Hermes requires on a sidebar PATCH, from its `mutation-snapshot` (as its web client sends it). */
internal fun pinMutationIdentity(snapshot: JSONObject, requestId: String): JSONObject {
    val revision = snapshot.opt("runtime_revision"); val generation = snapshot.opt("runtime_generation")
    require(snapshot.opt("exists") == true && revision is Number && revision.toLong() >= 0 && generation is Number && generation.toLong() >= 0) {
        "This conversation changed. Refresh and try again."
    }
    return JSONObject().put("request_id", requestId).put("expected_revision", revision.toLong()).put("expected_generation", generation.toLong())
}

/** Projects existing Compose contracts onto native operations; never proxies the WebUI. */
private class NativeTurnEnded : Exception()
internal class NativeModelConfirmation(message: String) : IllegalStateException(message)
private data class CanonicalModelGuard(val version: Long, val model: String, val provider: String, val token: String)
private val canonicalModelGuards = linkedMapOf<String, CanonicalModelGuard>()
internal val nativeApprovalModes = listOf("manual", "smart", "off")
/** Session detail keeps SQLite 0/1 flags; session lists expose booleans. */
internal fun nativeSessionFlag(row: JSONObject, key: String): Boolean = row.optBoolean(key) || (row.opt(key) as? Number)?.toDouble() == 1.0
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
private val storedSessionId = Regex("[A-Za-z0-9_.:-]{1,256}")
// Stock dashboard bounds: /api/sessions pages reject limit > 100; Beacon keeps its 200-row drawer window.
private const val SESSION_PAGE_LIMIT = 100
private const val SESSION_INDEX_WINDOW = 200
/** A Hermes platform name (telegram, api_server, …); anything else is not sent as a source filter. */
private val sourceName = Regex("[a-z][a-z0-9_]{0,39}")
// /api/sessions/{id}/messages allows limit <= 500; passive browsing reads one newest window.
private const val HISTORY_WINDOW = 500
private const val MAX_COMPRESSION_CHAIN = 512
private const val OPTIONAL_VOICE_HISTORY_TIMEOUT_MS = 1_500L

/** One lazy resume of [id]: the runtime Hermes holds for it (the stream id) and that runtime's turn status; null when the answer is not about this conversation. */
internal suspend fun BeaconClient.recoveredNativeSession(id: String): Pair<String, String?>? {
    if (!id.matches(storedSessionId)) return null
    // A resumed socket may bind a different runtime id than the stale ChatState stream id.
    // Lazy: a stored conversation resumes as usual; one Hermes no longer holds answers 4007 (unknown) instead of a new ownerless row.
    val binding = nativeSession(id, lazy = true)
    if (binding.scope != scope || binding.storedSessionId != id) return null
    val lease = nativeTurnLease(id, binding.runtimeSessionId)
    val status = if (!lease.authority) nativeSessionStatus(id, binding.runtimeSessionId) else {
        // Canonical active_list omits internal task sources; the attached conversation's own snapshot is authoritative.
        val fresh = nativeTurnRpc(lease, "session.resume", json("session_id" to binding.runtimeSessionId, "omit_messages" to true))
        require(nativeSessionBinding(scope, fresh) == binding) { "Conversation identity changed during recovery" }
        val profile = fresh.optJSONObject("info")?.opt("profile_name")
        require(profile == null || profile == JSONObject.NULL || profile == scope.profile) { "Conversation scope changed during recovery" }
        when {
            fresh.rows("pending").any { it.string("status") == "unknown" } -> null
            fresh.opt("running") == true -> if (fresh.rows("prompts").isEmpty()) "working" else "waiting"
            fresh.opt("running") == false && fresh.rows("pending").none { it.string("status") in setOf("queued", "started") } -> "idle"
            else -> null
        }
    }
    return binding.runtimeSessionId to status
}

internal suspend fun BeaconClient.recoveredNativeSessionStatus(id: String): String? = recoveredNativeSession(id)?.second

internal class NativeOperations(private val c: BeaconClient) {
    suspend fun call(path: String, q: Map<String, String>, method: String, body: JSONObject?, maxBytes: Int): JSONObject {
        var keepCreatedSessionLive = false
        return try {
            dispatch(path, q, method, body, maxBytes).also { keepCreatedSessionLive = path == "/api/session/new" || path == "/api/beacon/open" }
        } finally { if (!keepCreatedSessionLive && path != "/api/chat/steer") c.closeNative() }
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
            val rows = snapshot.optJSONArray("messages") ?: error("Refresh the conversation before changing its history.")
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
    private fun canonicalPrompts(snapshot: JSONObject, kind: String): List<JSONObject> {
        val array = snapshot.optJSONArray("prompts") ?: error("Refresh this conversation before answering a request.")
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
            "/api/commands", "/api/commands/catalog" -> { val result = c.nativeRpc("commands.catalog"); val pairs = result.optJSONArray("pairs") ?: JSONArray(); val commands = JSONArray(); for (i in 0 until pairs.length()) { val pair = pairs.optJSONArray(i) ?: continue; commands.put(json("name" to pair.optString(0).removePrefix("/"), "description" to pair.optString(1))) }; return json("commands" to commands, "available" to true, "warning" to result.string("warning")) }
            "/api/commands/exec" -> {
                val name = d.getString("name").removePrefix("/").lowercase()
                val argument = d.string("argument").trim()
                require(!chatCommandChangesProfile(name, argument)) { "Change this for all chats in Settings." }
                require(name != "yolo") { "Use Approvals beside the composer to skip approvals." }
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
                return c.nativeRpc("command.dispatch", json("session_id" to binding().runtimeSessionId, "name" to name, "arg" to argument))
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
                val params = JSONObject()
                for (key in setOf("title", "model")) if (d.has(key) && !d.isNull(key)) params.put(key, d.opt(key))
                d.string("model_provider").takeIf { it.isNotBlank() }?.let { params.put("provider", it) }
                d.string("workspace").takeIf { it.isNotBlank() }?.let { params.put("cwd", it) }
                if (d.optBoolean("thread")) params.put("source", BEACON_THREAD_SOURCE)
                val b = c.nativeCreate(params)
                require(b.storedSessionId.isNotBlank()) { "Native session has no durable identity yet; submit the first prompt before saving selection" }
                val cappedEffort = if (path == "/api/beacon/open") capAssistantEffort(b.runtimeSessionId) else null
                return sessionProjection(c.nativeSessionSnapshot().put("messages", JSONArray()).put("acknowledged_shell", true), b).put("profile", c.auth.profile).apply {
                    // The composer shows the effort this chat really got.
                    cappedEffort?.let { put("reasoning_effort", it) }
                    // Hermes holds a create title until the first turn; show it (and the thread lane) from the start.
                    if (string("title").isBlank() && params.string("title").isNotBlank()) put("title", params.string("title"))
                    if (params.string("source") == BEACON_THREAD_SOURCE) put("source", BEACON_THREAD_SOURCE)
                }
            }
            "/api/session" -> {
                require(sid().matches(storedSessionId)) { "Choose a native conversation first" }
                return passiveHistory(sid())
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
                    throw NativeTurnNotSent("The conversation could not be prepared. The message is retained.", e)
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
                        throw NativeTurnNotSent("The queued message's model changed. Select its saved model before sending it.")
                }
                for (attachment in d.rows("attachments")) {
                    require(attachment.string("scope_key") == c.scope.session(bound.storedSessionId).key) { "Attachment belongs to another conversation" }
                    val data = attachment.getString("staged_base64")
                    val mime = attachment.string("mime")
                    val receipt = if (lease.authority) {
                        if (mime !in setOf("image/png", "image/jpeg", "image/gif", "image/webp"))
                            throw NativeTurnNotSent("This server cannot send the queued attachment type. The message is kept on this phone.")
                        val staged = c.nativeTurnRpc(lease, "image.attach_bytes", json("session_id" to bound.runtimeSessionId,
                            "content_base64" to data, "filename" to attachment.string("name")))
                        require(staged.optBoolean("attached") && staged.string("path").isNotBlank() && staged.string("mime") == mime) { "Native image staging was not confirmed" }
                        canonicalAttachments.put(json("path" to staged.string("path"), "mime" to staged.string("mime")))
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
                    if (!promptAttempted && error !is CancellationException && error !is NativeTurnNotSent) throw NativeTurnNotSent("The message was not sent. Its draft is retained.", error)
                    if (steer && error is ApiFailure && ((!lease.authority && error.status == 4010) ||
                            (lease.authority && error.detail in setOf("unsupported_control", "execution_not_ready", "stale_generation"))))
                        throw NativeTurnNotSent("Guidance was not accepted. The message is retained.", error)
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
                val generation = nativeReplayNumber(snapshot.opt("execution_generation"))?.takeIf { it >= 0 }
                require(snapshot.opt("running") == true && generation != null) { "The active reply could not be confirmed." }
                val result = c.nativeTurnRpc(lease, "session.interrupt", json("session_id" to runtime, "execution_generation" to generation))
                val ref = result.optJSONObject("ref")
                val profile = snapshot.optJSONObject("info")?.string("profile_id").orEmpty()
                require(ref?.string("session_id") == b.storedSessionId && (profile.isBlank() || ref?.string("profile_id") == profile) &&
                    nativeReplayNumber(result.opt("execution_generation")) == generation) { "Stop was not confirmed. Refresh before trying again." }
                return result
            }
            "/api/approval/pending" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) return json("pending" to canonicalPrompts(canonicalSnapshot(lease), "approval").firstOrNull()?.let { it.copy().put("approval_id", it.getString("prompt_id")) })
                val result = c.nativeRpc("approval.pending", json("session_id" to b.runtimeSessionId))
                return json("pending" to result.rows("approvals").firstOrNull()?.let { it.copy().put("approval_id", it.string("request_id")) })
            }
            "/api/clarify/pending" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                if (lease.authority) return json("pending" to canonicalPrompts(canonicalSnapshot(lease), "clarify").firstOrNull()?.let { it.copy().put("clarify_id", it.getString("prompt_id")) })
                val result = c.nativeRpc("session.resume", json("session_id" to b.storedSessionId, "omit_messages" to true, "close_on_disconnect" to false))
                val requests = result.rows("open_requests")
                return json("pending" to requests.firstOrNull { it.string("method").contains("clarify") }?.let { (it.optJSONObject("params") ?: it).copy().put("clarify_id", it.string("id")) })
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
                    require(d.keys().asSequence().all { it in setOf("session_id", "yolo", "scope_key") } && d.opt("yolo") is Boolean) { "Choose Ask first or YOLO for this chat." }
                    require(d.string("scope_key") == c.scope.session(sid()).key) { "Reopen Approvals in the original account and profile" }
                    val target = binding()
                    require(d.string("scope_key") == c.scope.session(target.storedSessionId).key) { "Refresh the resolved conversation before changing approvals" }
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
                return if (path == "/api/approval/respond") c.nativeAnswerServerRequest("approval", d.getString("approval_id"), target.runtimeSessionId, d)
                else c.nativeAnswerServerRequest("clarify", d.getString("clarify_id"), target.runtimeSessionId, d)
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
                if (c.nativeSessionAuthority()) {
                    val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                    val pinned = d.getBoolean("pinned")
                    val result = canonicalMutation(lease, canonicalSnapshot(lease), "sidebar", json("pinned" to pinned))
                    require(result.opt("pinned") == pinned) { "Pin change was not confirmed. Refresh before trying again." }
                    return result
                }
                try {
                    return c.nativeRequest("/api/plugins/beacon/sessions/pin", "POST", json("session_id" to sid(), "pinned" to d.getBoolean("pinned"), "profile" to c.auth.profile)).also {
                        require(it.string("session_id") == sid() && it.opt("pinned") == d.getBoolean("pinned")) { "Pin change was not confirmed. Try again." }
                    }
                } catch (error: ApiFailure) {
                    // Missing or unpaired plugins use native authority; explicit owner/session refusals stay final.
                    if (!(error.plugin && error.status == 401 || error.status in setOf(405, 501) || error.status == 404 && error.detail.equals("Not Found", true))) throw error
                }
                // Hermes accepts a sidebar change only with its revision precondition and a fresh request id, as its own web client sends.
                val snapshot = c.nativeGet("/api/sessions/${sid()}/mutation-snapshot", mapOf("profile" to c.auth.profile))
                val identity = pinMutationIdentity(snapshot, java.util.UUID.randomUUID().toString())
                return c.nativeRequest("/api/sessions/${sid()}", "PATCH", identity.put("pinned", d.getBoolean("pinned")).put("profile", c.auth.profile))
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
                    val snapshot = canonicalSnapshot(lease)
                    require(snapshot.opt("running") == false && snapshot.opt("pending") is JSONArray && snapshot.rows("pending").isEmpty()) { "Stop the current reply before deleting this conversation." }
                    val result = canonicalMutation(lease, snapshot, "delete", JSONObject())
                    require(result.optJSONArray("deleted_ids")?.strings()?.contains(b.storedSessionId) == true) { "Deletion was not confirmed. Refresh before trying again." }
                    return result.put("deleted", b.storedSessionId)
                }
                val fresh = c.nativeRpc("session.resume", json("session_id" to sid(), "omit_messages" to true, "close_on_disconnect" to false))
                val target = nativeSessionBinding(c.scope, fresh)
                require(target.storedSessionId == sid()) { "Conversation identity changed. Reopen before deleting." }
                require(fresh.opt("running") is Boolean) { "Refresh this conversation to confirm whether a reply is still running." }
                require(fresh.opt("running") == false && fresh.opt("inflight") in listOf(null, JSONObject.NULL) && fresh.opt("queued") in listOf(null, JSONObject.NULL)) { "Stop the current reply before deleting this conversation." }
                val closed = c.nativeRpc("session.close", json("session_id" to target.runtimeSessionId))
                require(closed.opt("closed") == true) { "Conversation close was not confirmed." }
                val deleted = try { c.nativeRpc("session.delete", json("session_id" to target.storedSessionId)) }
                catch (error: ApiFailure) {
                    if (error.status != 4007 || error.message != "session not found") throw error
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
                require(result.string("status").isBlank() && result.string("target_session_id").matches(storedSessionId) && nativeReplayNumber(result.opt("message_count"))?.let { it >= 0 } == true) { "Compression was not confirmed. Refresh before trying again." }
                return result.put("status", "compressed")
            }
            "/api/session/usage" -> {
                val b = binding(); val lease = c.nativeTurnLease(b.storedSessionId, b.runtimeSessionId)
                return c.nativeTurnRpc(lease, if (lease.authority) "session.stats" else "session.usage", json("session_id" to b.runtimeSessionId))
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
        require(stored.matches(storedSessionId) && input.matches(storedSessionId)) { "Choose a saved send receipt" }
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
        val profile = profileId(snapshot.optJSONObject("info"))
            ?: profileId(c.nativeTurnRpc(lease, "session.info", json("session_id" to b.runtimeSessionId)))
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
        return receipt.put("session_id", stored).put("stream_id", b.runtimeSessionId)
    }

    private suspend fun nativeTextFile(path: String): JSONObject =
        c.nativeGet("/api/fs/read-text", mapOf("path" to path, "profile" to c.auth.profile)).apply {
            require(opt("text") is String) { "Reopen this file before editing it." }
            put("content", if (optBoolean("binary")) "" else opt("text"))
            if (optBoolean("binary") || '\uFFFD' in string("text")) put("edit_blocked_reason", "This file can't be edited here.")
            else if (optBoolean("truncated")) put("edit_blocked_reason", "File preview is truncated; download the complete file before editing")
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
        require(text.toByteArray(Charsets.UTF_8).size.toLong() + text.count { it == '\n' } <= 512 * 1024 && '\u0000' !in text) { "The phone editor supports text files up to 512 KiB without binary characters. Your draft is kept." }
        try { return c.nativeRequest("/api/plugins/beacon/mobile/files/text", "POST", fields) }
        catch (error: ApiFailure) { if (error.status != 404 && !(error.plugin && error.status == 401)) throw error }
        val path = fields.getString("path")
        val latest = nativeTextFile(path)
        require('\uFFFD' !in latest.string("text") && '\uFFFD' !in fields.getString("expected_content")) { "This file can't be edited here." }
        require(!latest.optBoolean("truncated") && !latest.optBoolean("binary") && latest.string("text") == fields.getString("expected_content")) { "This file changed on the computer. Reopen it before saving; your edits are retained." }
        // Stock Hermes chooses platform newlines; preserving disk formatting requires the optional plugin.
        // shortcut: stock write-text has no compare-and-write, so two devices saving in the same instant are last-writer-wins; use the plugin's revision-locked save if native Hermes adds a precondition.
        val result = c.nativeGet("/api/fs/write-text", mapOf("profile" to c.auth.profile), "POST", json("path" to path, "content" to text.replace("\r\n", "\n")))
        val actual = nativeTextFile(path)
        require(!actual.optBoolean("truncated") && !actual.optBoolean("binary") && '\uFFFD' !in actual.string("text")) { "Saved file preview is unavailable; reopen or download it before editing again." }
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
        return json("sessions" to JSONArray(listed.map { (id, row) -> row.copy().put("session_id", id).put("profile", c.auth.profile).put("archived", nativeSessionFlag(row, "archived") || nativeSessionFlag(row, "hidden")) }),
            "search_scope" to "Newest 200 conversation titles and identities", "limit" to SESSION_INDEX_WINDOW)
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
        require(row.optLong("id", row.optLong("row_id")) == rowId && row.string("role") == "user") { "Historical image message changed. Reopen it before previewing." }
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
    suspend fun stream(path: String, q: Map<String, String>, lastId: String, callback: suspend (SseEvent) -> Unit) {
        try {
        val b = c.nativeSession(q["session_id"])
        if (path == "/api/chat/stream") c.nativeReplay(lastId) else c.nativeReplay(beforePrompt = true)
        c.nativeEvents { raw ->
            val params = raw.optJSONObject("params") ?: JSONObject()
            if (params.string("type") == "gateway.ready") return@nativeEvents
            val payload = (params.optJSONObject("payload") ?: params).copy().put("session_id", b.storedSessionId)
            val type = if (raw.optString("method") == "reconciliation.required") { params.optJSONObject("history")?.let { payload.put("session", sessionProjection(it, b)) }; if (path == "/api/session/stream") "session_updated" else "state_saved" } else when (params.string("type", raw.string("method"))) {
                "message.delta" -> "token"
                "reasoning.delta", "reasoning.available", "thinking.delta" -> "reasoning"
                "message.interim" -> "interim_assistant"
                "error" -> "error"
                "message.start" -> "server_turn_started"
                "message.complete" -> if (path == "/api/session/stream") "session_updated" else if (payload.string("status") == "error") { payload.put("message", payload.string("failure_reason", payload.string("error", "Native Hermes turn failed"))); "error" } else "done"
                "tool.start" -> "tool"
                "tool.complete" -> "tool_complete"
                "session.title" -> "title"
                "approval" -> "approval"
                "clarify" -> "clarify"
                "request.cancel" -> "request_cancel"
                "status.update" -> {
                    payload.string("text").takeIf(String::isNotBlank)?.let { payload.put("message", it) }
                    "warning"
                }
                else -> "native_event"
            }
            if (raw.has("id")) {
                payload.put("id", raw.opt("id")).put("approval_id", params.string("request_id", raw.string("id"))).put("clarify_id", raw.opt("id"))
            }
            payload.put("stream_id", b.runtimeSessionId)
            val epoch = params.string("replay_epoch")
            callback(SseEvent(type, payload.toString(), if (params.has("seq")) "${b.runtimeSessionId}:${if (epoch.isBlank()) "" else "$epoch:"}${params.optLong("seq")}" else ""))
            if (path == "/api/chat/stream" && type in setOf("done", "error")) throw NativeTurnEnded()
        } } catch (_: NativeTurnEnded) { } finally { c.closeNative() }
    }
}
