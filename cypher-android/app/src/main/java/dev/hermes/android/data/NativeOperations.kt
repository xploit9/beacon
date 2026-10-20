package dev.hermes.android.data

import kotlinx.coroutines.CancellationException
import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject

/** "high" for an assistant chat whose profile default is slower than that, else null (keep the default). */
internal fun assistantEffortCap(current: String): String? = if (current in setOf("xhigh", "max", "ultra")) "high" else null

/** Projects existing Compose contracts onto native operations; never proxies the WebUI. */
private class NativeTurnEnded : Exception()
internal class NativeModelConfirmation(message: String) : IllegalStateException(message)
internal val nativeApprovalModes = listOf("manual", "smart", "off")
private val storedSessionId = Regex("[A-Za-z0-9_.:-]{1,256}")
// Stock dashboard bounds: /api/sessions pages reject limit > 100; Cypher keeps its 200-row drawer window.
private const val SESSION_PAGE_LIMIT = 100
private const val SESSION_INDEX_WINDOW = 200
/** A Hermes platform name (telegram, api_server, …); anything else is not sent as a source filter. */
private val sourceName = Regex("[a-z][a-z0-9_]{0,39}")
// /api/sessions/{id}/messages allows limit <= 500; passive browsing reads one newest window.
private const val HISTORY_WINDOW = 500
private const val MAX_COMPRESSION_CHAIN = 512
private const val OPTIONAL_VOICE_HISTORY_TIMEOUT_MS = 1_500L

internal suspend fun CypherClient.recoveredNativeSessionStatus(id: String): String? {
    if (!id.matches(storedSessionId)) return null
    // A resumed socket may bind a different runtime id than the stale ChatState stream id.
    // Lazy: a stored conversation resumes as usual; one Hermes no longer holds answers 4007 (unknown) instead of a new ownerless row.
    val binding = nativeSession(id, lazy = true)
    if (binding.scope != scope || binding.storedSessionId != id) return null
    return nativeSessionStatus(id, binding.runtimeSessionId)
}

internal class NativeOperations(private val c: CypherClient) {
    suspend fun call(path: String, q: Map<String, String>, method: String, body: JSONObject?, maxBytes: Int): JSONObject {
        var keepCreatedSessionLive = false
        return try {
            dispatch(path, q, method, body, maxBytes).also { keepCreatedSessionLive = path == "/api/session/new" || path == "/api/cypher/open" }
        } finally { if (!keepCreatedSessionLive) c.closeNative() }
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
        val result = c.nativeRpc("config.set", json("session_id" to target.runtimeSessionId, "key" to "yolo", "value" to value, "scope" to "session"))
        require(result.string("value") == value) { "Hermes did not confirm the chat YOLO state. Refresh before trying again." }
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
            "/api/commands/exec" -> { val b = binding(); return c.nativeRpc("command.dispatch", json("session_id" to b.runtimeSessionId, "name" to d.getString("name"), "arg" to d.string("argument"))) }
            // What Hermes starts a new chat with in this profile, for the composer of a chat with no messages yet.
            "/api/profile/defaults" -> return json("profile" to c.auth.profile, "model" to c.nativeRpc("config.get", json("key" to "provider")).string("model"),
                "reasoning_effort" to c.nativeRpc("config.get", json("key" to "reasoning")).string("value"))
            "/api/profile/switch" -> {
                val name = d.getString("name")
                val profiles = c.nativeGet("/api/profiles").rows("profiles")
                require(profiles.any { it.string("name") == name }) { "Native profile does not exist" }
                c.bindProfile(name)
                return json("ok" to true, "profile" to name)
            }
            "/api/sessions", "/api/sessions/search" -> return sessionIndex(archived = q["include_archived"] == "1", query = q["q"].orEmpty(), threads = q["view"] == "threads", hidden = q["hidden_sources"].orEmpty(), order = q["order"].orEmpty())
            "/api/session/new", "/api/cypher/open" -> {
                val params = JSONObject()
                for (key in setOf("title", "model")) if (d.has(key) && !d.isNull(key)) params.put(key, d.opt(key))
                d.string("model_provider").takeIf { it.isNotBlank() }?.let { params.put("provider", it) }
                d.string("workspace").takeIf { it.isNotBlank() }?.let { params.put("cwd", it) }
                if (d.optBoolean("thread")) params.put("source", CYPHER_THREAD_SOURCE)
                val b = c.nativeCreate(params)
                require(b.storedSessionId.isNotBlank()) { "Native session has no durable identity yet; submit the first prompt before saving selection" }
                val cappedEffort = if (path == "/api/cypher/open") capAssistantEffort(b.runtimeSessionId) else null
                if (path == "/api/cypher/open") try {
                    kotlinx.coroutines.withTimeoutOrNull(1_500L) {
                        c.nativeRequest("/api/plugins/cypher/mobile/assistant-conversation", "PUT",
                            json("profile" to c.auth.profile, "session_id" to b.storedSessionId))
                    }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
                return sessionProjection(c.nativeSessionSnapshot().put("messages", JSONArray()).put("acknowledged_shell", true), b).put("profile", c.auth.profile).apply {
                    // The composer shows the effort this chat really got.
                    cappedEffort?.let { put("reasoning_effort", it) }
                    // Hermes holds a create title until the first turn; show it (and the thread lane) from the start.
                    if (string("title").isBlank() && params.string("title").isNotBlank()) put("title", params.string("title"))
                    if (params.string("source") == CYPHER_THREAD_SOURCE) put("source", CYPHER_THREAD_SOURCE)
                }
            }
            "/api/session" -> {
                require(sid().matches(storedSessionId)) { "Choose a native conversation first" }
                return passiveHistory(sid())
            }
            "/api/chat/start", "/api/chat/steer" -> {
                require(path != "/api/chat/steer" || d.rows("attachments").isEmpty()) { "Guide accepts text only" }
                val b = binding()
                c.nativeReplay(beforePrompt = true)
                val refs = mutableListOf<String>()
                val receipts = JSONArray()
                val queuedPaths = mutableListOf<String>()
                try {
                for (attachment in d.rows("attachments")) {
                    require(attachment.string("scope_key") == c.scope.session(b.storedSessionId).key) { "Attachment belongs to another conversation" }
                    val data = attachment.getString("staged_base64")
                    val mime = attachment.string("mime")
                    val receipt = if (mime.startsWith("image/")) {
                        require(attachment.getString("id").matches(Regex("[a-fA-F0-9-]{36}")) && validUploadName(attachment.getString("name"))) { "This staged attachment has an invalid filename or identity. Rename it without quotes, slashes, dollar signs, percent signs or control characters, then attach it again." }
                        val uploadPath = workspacePath(b.storedSessionId, ".cypher-attachments/${attachment.getString("id")}-${attachment.getString("name")}")
                        c.nativeRequest("/api/files/upload", "POST", json("path" to uploadPath, "data_url" to "data:$mime;base64,$data", "overwrite" to true))
                        queuedPaths.add(uploadPath)
                        c.nativeRpc("image.attach", json("session_id" to b.runtimeSessionId, "path" to "\"$uploadPath\""))
                    } else c.nativeRpc("file.attach", json("session_id" to b.runtimeSessionId, "data_url" to "data:$mime;base64,$data", "name" to attachment.string("name")))
                    require(receipt.optBoolean("attached")) { "Native attachment was not accepted" }
                    receipt.string("path").takeIf { it.isNotBlank() && mime.startsWith("image/") }?.let(queuedPaths::add)
                    receipts.put(receipt.copy().put("id", attachment.string("id")).put("name", attachment.string("name")).put("mime", mime))
                    receipt.string("ref_text").takeIf { it.isNotBlank() }?.let(refs::add)
                }
                val text = (listOf(d.string("message", d.string("text"))) + refs).joinToString("\n")
                val result = c.nativeRpc(if (path == "/api/chat/steer") "session.steer" else "prompt.submit", json("session_id" to b.runtimeSessionId, "text" to text))
                require(result.string("status") in setOf("streaming", "queued", "steered", "redirected")) { "Native prompt was not accepted" }
                return result.put("accepted", true).put("session_id", b.storedSessionId).put("stream_id", b.runtimeSessionId).put("attachment_receipts", receipts)
                } catch (error: Throwable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { for (queuedPath in queuedPaths) runCatching { c.nativeRpc("image.detach", json("session_id" to b.runtimeSessionId, "path" to queuedPath)) } }
                    throw error
                }
            }
            "/api/chat/cancel" -> {
                val runtime = d.string("stream_id").ifBlank { require(sid().isNotBlank()) { "Choose a native session before stopping" }; binding().runtimeSessionId }
                return c.nativeRpc("session.interrupt", json("session_id" to runtime))
            }
            "/api/approval/pending" -> {
                val result = c.nativeRpc("approval.pending", json("session_id" to binding().runtimeSessionId))
                return json("pending" to result.rows("approvals").firstOrNull()?.let { it.copy().put("approval_id", it.string("request_id")) })
            }
            "/api/clarify/pending" -> {
                val b = binding()
                val result = c.nativeRpc("session.resume", json("session_id" to b.storedSessionId, "omit_messages" to true, "close_on_disconnect" to false))
                val requests = result.rows("open_requests")
                return json("pending" to requests.firstOrNull { it.string("method").contains("clarify") }?.let { (it.optJSONObject("params") ?: it).copy().put("clarify_id", it.string("id")) })
            }
            "/api/approval/mode" -> {
                require(method in setOf("GET", "POST")) { "Unsupported approval setting operation" }
                val yoloWrite = method == "POST" && d.has("yolo")
                if (method == "POST") {
                    val allowed = if (yoloWrite) setOf("session_id", "yolo", "scope_key") else setOf("session_id", "mode", "scope_key")
                    require(d.keys().asSequence().all { it in allowed } && (if (yoloWrite) d.opt("yolo") is Boolean else d.string("mode") in nativeApprovalModes)) { "Choose a native approval mode or chat YOLO state" }
                    require(d.string("scope_key") == c.scope.session(sid()).key) { "Reopen Approvals in the original account and profile" }
                    val target = binding()
                    require(d.string("scope_key") == c.scope.session(target.storedSessionId).key) { "Refresh the resolved conversation before changing approvals" }
                    if (yoloWrite) {
                        if (d.getBoolean("yolo")) {
                            val profileMode = c.nativeRpc("config.get", json("key" to "approvals.mode")).string("value")
                            require(profileMode in nativeApprovalModes) { "Hermes returned an unrecognized approval mode" }
                            require(profileMode != "off") { "Approvals are Off for this profile, so every chat already skips prompts." }
                        }
                        setSessionYolo(target, d.getBoolean("yolo"))
                    } else {
                        val selected = d.getString("mode")
                        // A deliberate mode choice makes this chat follow its profile instead of a session bypass.
                        val bypass = c.nativeRpc("config.set", json("session_id" to target.runtimeSessionId, "key" to "yolo", "value" to "off", "scope" to "session"))
                        require(bypass.string("value") == "0") { "The conversation still has an independent approval bypass. Refresh before trying again." }
                        val applied = c.nativeRpc("config.set", json("key" to "approvals.mode", "value" to selected))
                        require(applied.string("value") == selected) { "Hermes did not confirm the approval mode. Refresh before trying again." }
                    }
                }
                val result = c.nativeRpc("config.get", json("key" to "approvals.mode"))
                require(result.string("value") in nativeApprovalModes) { "Hermes returned an unrecognized approval mode" }
                if (!yoloWrite) require(method == "GET" || result.string("value") == d.string("mode")) { "Hermes did not activate the selected approval mode. Refresh to check for an override or another change." }
                val target = binding()
                val fresh = c.nativeRpc("session.resume", json("session_id" to target.storedSessionId, "omit_messages" to true, "close_on_disconnect" to false))
                require(nativeSessionBinding(c.scope, fresh).storedSessionId == target.storedSessionId) { "Approval status belongs to another conversation" }
                val bypass = fresh.optJSONObject("info")?.opt("yolo") as? Boolean ?: storedYolo(target.storedSessionId)
                val profileMode = result.getString("value")
                if (yoloWrite && bypass is Boolean) require(bypass == d.getBoolean("yolo")) { "Hermes did not confirm the chat YOLO state. Refresh before trying again." }
                else require(method == "GET" || d.string("mode") == "off" || bypass != true) { "A host approval bypass remains enabled. Check Hermes before continuing." }
                val sessionBypass = if (bypass is Boolean) bypass == true && profileMode != "off" else JSONObject.NULL
                return result.put("profile_mode", profileMode).put("value", if (bypass == true) "off" else profileMode).put("session_status_known", bypass is Boolean).put("session_bypass", sessionBypass).put("session_id", target.storedSessionId).put("scope_key", c.scope.key)
            }
            "/api/approval/respond", "/api/clarify/respond" -> {
                require(d.string("scope_key") == c.scope.session(sid()).key) { "Reopen this request in its original conversation." }
                val target = binding()
                return if (path == "/api/approval/respond") c.nativeAnswerServerRequest("approval", d.getString("approval_id"), target.runtimeSessionId, d)
                else c.nativeAnswerServerRequest("clarify", d.getString("clarify_id"), target.runtimeSessionId, d)
            }
            "/api/session/branch" -> {
                val result = c.nativeRpc("session.branch", json("session_id" to binding().runtimeSessionId, "name" to d.string("title").ifBlank { null }, "count" to if (d.has("keep_count")) d.getInt("keep_count") else null))
                return sessionProjection(result, NativeSessionBinding(c.scope, result.getString("stored_session_id"), result.getString("session_id")))
            }
            "/api/session/pin" -> {
                require(method == "POST" && sid().matches(storedSessionId) && d.opt("pinned") is Boolean) { "Choose a conversation to pin" }
                return c.nativeRequest("/api/sessions/${sid()}", "PATCH", json("pinned" to d.getBoolean("pinned"), "profile" to c.auth.profile))
            }
            "/api/session/rename" -> return c.nativeRpc("session.title", json("session_id" to binding().runtimeSessionId, "title" to d.getString("title")))
            "/api/session/archive" -> {
                val target = binding(); val hidden = d.optBoolean("archived", true)
                return c.nativeRpc("session.set_hidden", json("session_id" to target.runtimeSessionId, "hidden" to hidden)).also {
                    require(it.string("session_key") == target.storedSessionId && it.opt("hidden") == hidden) { "Archive change was not confirmed. Refresh before trying again." }
                }
            }
            "/api/session/delete" -> {
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
            "/api/session/undo" -> return c.nativeRpc("session.undo", json("session_id" to binding().runtimeSessionId))
            "/api/session/compress" -> return c.nativeRpc("session.compress", json("session_id" to binding().runtimeSessionId, "focus_topic" to d.string("focus_topic")))
            "/api/session/usage" -> return c.nativeRpc("session.usage", json("session_id" to binding().runtimeSessionId))
            "/api/session/update" -> {
                val b = binding()
                if (d.has("model")) {
                    val result = c.nativeRpc("config.set", json("session_id" to b.runtimeSessionId, "key" to "model", "value" to (d.getString("model") + d.string("model_provider").takeIf { it.isNotBlank() }?.let { " --provider $it" }.orEmpty()), "scope" to "session", "confirm_expensive_model" to d.optBoolean("confirm_expensive_model")))
                    if (result.optBoolean("confirm_required")) {
                        val message = result.string("confirm_message", "Hermes requires confirmation before selecting this model.")
                        require(!d.optBoolean("confirm_expensive_model")) { message }
                        throw NativeModelConfirmation(message)
                    }
                    val fresh = c.nativeRpc("session.resume", json("session_id" to b.storedSessionId, "close_on_disconnect" to false, "omit_messages" to false))
                    val updated = nativeSessionBinding(c.scope, fresh)
                    require(updated.scope == b.scope && updated.storedSessionId == b.storedSessionId) { "Model change returned another conversation. Refresh before sending." }
                    return json("session" to withVoiceHistory(sessionProjection(fresh, updated), updated.storedSessionId), "deferred" to result.optBoolean("deferred"))
                }
                if (d.has("workspace")) return c.nativeRpc("session.cwd.set", json("session_id" to b.runtimeSessionId, "cwd" to d.getString("workspace")))
                throw ApiFailure(501, "These conversation settings are unavailable in native Hermes")
            }
            "/api/reasoning", "/api/mobile/cypher/reasoning" -> {
                val params = json("session_id" to binding().runtimeSessionId, "key" to "reasoning")
                if (method != "GET") { require(!d.has("display") && d.string("effort") in setOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")) { "Choose a native reasoning effort; display preferences are local" }; params.put("value", d.getString("effort")).put("scope", "session") }
                val result = c.nativeRpc(if (method == "GET") "config.get" else "config.set", params)
                if (method != "GET") {
                    val effective = c.nativeRpc("config.get", json("session_id" to params.getString("session_id"), "key" to "reasoning"))
                    require(effective.string("value").isNotBlank() && effective.string("value") == result.string("value")) { "Reasoning change was not confirmed. Refresh before trying again." }
                    return effective
                }
                if (q["model"].orEmpty().isNotBlank()) {
                    val capabilities = c.nativeGet("/api/plugins/cypher/admin/model-capabilities", q.filterKeys { it in setOf("model", "provider") })
                    result.put("options", capabilities.getJSONArray("reasoning_options")).put("known", capabilities.getBoolean("reasoning_known"))
                }
                return result
            }
            "/api/models" -> {
                val result = c.nativeGet("/api/model/options", q + mapOf("profile" to c.auth.profile, "include_unconfigured" to "false"))
                return result.put("groups", JSONArray(result.rows("providers").map { p ->
                    json("provider_id" to p.string("slug"), "provider" to p.string("name"), "models" to JSONArray(p.optJSONArray("models")?.strings().orEmpty().map { json("id" to it, "label" to it) }))
                }))
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
            "/api/pc/file" -> { require(method == "GET"); return c.nativeGet("/api/plugins/cypher/mobile/files/text", mapOf("path" to d.getString("path"))).put("_pc", true) }
            "/api/pc/file/save" -> { require(method == "POST"); return c.nativeRequest("/api/plugins/cypher/mobile/files/text", "POST", d) }
            "/api/pc/file/create" -> { require(method == "POST"); return c.nativeRequest("/api/plugins/cypher/mobile/files/create", "POST", d) }
            "/api/list" -> return c.nativeGet("/api/fs/list", mapOf("path" to workspacePath(sid(), d.string("path", "."))))
            "/api/file" -> return c.nativeGet("/api/fs/read-text", mapOf("path" to workspacePath(sid(), d.getString("path")))).apply { put("content", if (optBoolean("binary")) "" else opt("text")); if (optBoolean("binary")) put("edit_blocked_reason", "Binary preview is unavailable; download this file to view it"); if (optBoolean("truncated")) put("edit_blocked_reason", "File preview is truncated; download the complete file before editing") }
            "/api/file/create" -> return c.nativeRequest("/api/plugins/cypher/mobile/files/create", "POST", json("session_id" to sid(), "path" to workspacePath(sid(), d.getString("path")), "content" to d.string("content")))
            "/api/file/save" -> {
                val path = workspacePath(sid(), d.getString("path"))
                require(d.has("expected_content")) { "Reopen this file before saving; its disk revision is required." }
                val text = d.string("content").replace("\r\n", "\n")
                // Native previews stop at 512 KiB; include Windows newline expansion before committing.
                require(text.toByteArray(Charsets.UTF_8).size.toLong() + text.count { it == '\n' } <= 512 * 1024 && '\u0000' !in text) { "The phone editor supports text files up to 512 KiB without binary characters. Your draft is kept." }
                val latest = c.nativeGet("/api/fs/read-text", mapOf("path" to path))
                require(!latest.optBoolean("truncated") && !latest.optBoolean("binary") && latest.string("text") == d.getString("expected_content")) { "This file changed on the computer. Reopen it before saving; your edits are retained." }
                val result = c.nativeGet("/api/fs/write-text", method = "POST", data = json("path" to path, "content" to text))
                val actual = c.nativeGet("/api/fs/read-text", mapOf("path" to path))
                require(!actual.optBoolean("truncated") && !actual.optBoolean("binary")) { "Saved file preview is unavailable; reopen or download it before editing again." }
                return result.put("content", actual.string("text")).put("byteSize", actual.optLong("byteSize")).put("path", actual.string("path", path))
            }
        }
        if (path.startsWith("/api/mobile/receipts/")) throw ApiFailure(501, "Native Hermes has no prompt receipt lookup. Refresh conversation history before deciding whether to resend; your draft is retained.")
        if (NativeOperationPolicy.direct(path, method)) return c.nativeGet(path, q + ("profile" to c.auth.profile), method, body, maxBytes)
        throw ApiFailure(501, "This operation is unavailable in native Hermes: ${path.removePrefix("/api/")}")
    }
    /** The optional plugin's voice archive. Any failure, including its own 401, only marks voice history unavailable. */
    private suspend fun withVoiceHistory(snapshot: JSONObject, id: String): JSONObject {
        require(id.matches(storedSessionId)) { "Invalid native stored session identity" }
        try {
            // Only this local timeout yields null; an external cancellation still propagates below.
            val archive = kotlinx.coroutines.withTimeoutOrNull(OPTIONAL_VOICE_HISTORY_TIMEOUT_MS) {
                c.detached().nativeGet("/api/plugins/cypher/mobile/sessions/$id/voice-history", mapOf("profile" to c.auth.profile), persistAuth = false)
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
        val scope = if (threads) mapOf("sources" to "$CYPHER_THREAD_SOURCE,phone") else mapOf("exclude_sources" to (listOf("cron") + hiddenSources).distinct().joinToString(","))
        val rows = LinkedHashMap<String, JSONObject>()
        var offset = 0
        while (offset < SESSION_INDEX_WINDOW) {
            val limit = minOf(SESSION_PAGE_LIMIT, SESSION_INDEX_WINDOW - offset)
            val page = c.nativeGet("/api/sessions", mapOf("profile" to c.auth.profile, "archived" to if (archived) "only" else "exclude", "order" to if (order == "created") "created" else "recent", "min_messages" to "1", "limit" to "$limit", "offset" to "$offset") + scope)
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
        val listed = rows.filter { (id, row) -> query.isBlank() || row.string("title").contains(query, true) || id.contains(query, true) }
        return json("sessions" to JSONArray(listed.map { (id, row) -> row.copy().put("session_id", id).put("profile", c.auth.profile).put("archived", archived) }),
            "search_scope" to "Newest 200 conversation titles and identities", "limit" to SESSION_INDEX_WINDOW)
    }

    /**
     * Read-only transcript: stock metadata plus the newest [HISTORY_WINDOW] messages (Relay getSessionMessages, LATEST).
     * No socket, resume or live identity: `runtime_session_id` and `active_stream_id` stay blank. The UI keeps the
     * requested id; a compressed history is accepted only with a verified descendant chain to the returned id.
     */
    private suspend fun passiveHistory(requested: String): JSONObject {
        val scoped = mapOf("profile" to c.auth.profile)
        val meta = c.nativeGet("/api/sessions/$requested", scoped)
        require(meta.string("id", meta.string("session_id")) == requested && ownedByProfile(meta)) { "Conversation scope changed" }
        val page = c.nativeGet("/api/sessions/$requested/messages", scoped + mapOf("order" to "latest", "limit" to "$HISTORY_WINDOW", "offset" to "0"))
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
            rows.put(row)
        }
        val returned = page.optJSONObject("pagination")?.optInt("returned", messages.length()) ?: messages.length()
        val projection = meta.copy().apply { remove("id"); remove("runtime_session_id"); remove("active_stream_id"); if (has("provider")) put("model_provider", opt("provider")); if (has("cwd")) put("workspace", opt("cwd")) }
            .put("session_id", requested).put("stored_session_id", resolved).put("profile", c.auth.profile)
            .put("messages", rows).put("history_limit", HISTORY_WINDOW).put("history_truncated", returned >= HISTORY_WINDOW)
        return withVoiceHistory(projection, requested)
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
        val o = (result.optJSONObject("info")?.copy() ?: JSONObject()).apply { result.keys().forEach { put(it, result.opt(it)) } }.apply { if (has("provider")) put("model_provider", opt("provider")); if (has("cwd")) put("workspace", opt("cwd")) }.put("session_id", b.storedSessionId).put("stored_session_id", b.storedSessionId).put("runtime_session_id", b.runtimeSessionId)
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
                "status.update" -> "warning"
                else -> "native_event"
            }
            if (raw.has("id")) {
                payload.put("id", raw.opt("id")).put("approval_id", params.string("request_id", raw.string("id"))).put("clarify_id", raw.opt("id"))
            }
            payload.put("stream_id", b.runtimeSessionId)
            callback(SseEvent(type, payload.toString(), if (params.has("seq")) "${b.runtimeSessionId}:${params.optLong("seq")}" else ""))
            if (path == "/api/chat/stream" && type in setOf("done", "error")) throw NativeTurnEnded()
        } } catch (_: NativeTurnEnded) { } finally { c.closeNative() }
    }
}
