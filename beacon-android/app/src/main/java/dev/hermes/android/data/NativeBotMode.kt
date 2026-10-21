package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class BotModeBot(val profile: String, val title: String, val description: String)
data class BotModeSpace(val id: String, val name: String)
data class BotModeEvent(val id: String, val speaker: String, val text: String, val failed: Boolean = false)
data class BotModeAction(val kind: String, val task: String, val member: String = "", val request: String = "", val generation: Int = 0, val description: String = "", val choices: List<String> = emptyList()) {
    fun payload(choice: String = ""): JSONObject = json("kind" to kind, "task_id" to task).apply {
        if (kind == "approval") { require(choice in choices && choice in setOf("once", "deny")) { "This approval choice is no longer available. Refresh the shared space." }; put("member_id", member); put("request_id", request); put("execution_generation", generation); put("choice", choice) }
    }
}
data class NativeBotModeState(
    val bots: List<BotModeBot> = emptyList(), val spaces: List<BotModeSpace> = emptyList(),
    val selectedSpace: String = "", val members: List<String> = emptyList(), val events: List<BotModeEvent> = emptyList(), val draft: String = "",
    val selectedBots: Set<String> = emptySet(), val openingBot: BotModeBot? = null, val activeBot: BotModeBot? = null, val sessionId: String = "",
    val loading: Boolean = false, val sending: Boolean = false, val error: String = "", val status: String = "",
    val pendingChange: JSONObject? = null, val earlierEventsOmitted: Boolean = false, val moreSpaces: Boolean = false, val actions: List<BotModeAction> = emptyList(),
)

internal fun botModeEvents(data: JSONObject): List<BotModeEvent> = data.rows("events").mapNotNull { event ->
    val payload = event.optJSONObject("payload") ?: return@mapNotNull null
    val kind = event.string("kind")
    if (kind == "turn.failed") {
        val reason = payload.string("reason_code")
        val error = payload.string("error")
        val readable = when {
            reason == "provider_quota_limit" || Regex("(?i)http\\s*402|insufficient.?balance|out of funds").containsMatchIn(error) -> "Provider balance or quota is exhausted. Review this bot’s provider in Profiles."
            reason == "provider_auth_or_access" -> "Provider authentication or access was refused. Review this bot’s provider in Profiles."
            reason == "provider_rate_limit" -> "The provider is rate limiting this bot."
            reason == "provider_server_error" -> "The provider could not complete this bot’s turn."
            reason == "missing_config" || reason == "model_unavailable" -> "This bot’s model or provider configuration is unavailable. Review Profiles."
            reason == "context_overflow" -> "This bot’s conversation exceeded the provider context limit."
            reason == "runtime_offline" || reason == "delivery_timeout" -> "This bot’s runtime did not respond."
            reason == "agent_blocked" -> "This bot’s turn was blocked."
            reason == "cancelled" -> "This bot’s turn was cancelled."
            else -> "This bot’s turn failed. Review its provider and model in Profiles."
        }
        return@mapNotNull BotModeEvent(event.getString("event_id"), payload.getString("member_id"), readable, true)
    }
    val text = payload.string("text")
    if (kind !in setOf("message.user", "message.member") || text.isBlank()) return@mapNotNull null
    BotModeEvent(event.getString("event_id"), if (kind == "message.user") "You" else payload.getString("member_id"), text)
}

/** Chat rendering and tool approvals stay in the existing conversation controller. */
class NativeBotModeController(
    private val scope: CoroutineScope, private val saved: SavedStateHandle,
    private val openChat: suspend (String, String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val client: () -> BeaconClient?,
) {
    private val mutable = MutableStateFlow(NativeBotModeState())
    val state = mutable.asStateFlow()
    private var source: BeaconClient? = null
    private var accountClient: BeaconClient? = null
    private var root: Scope? = null
    private var job: Job? = null
    private var generation = 0
    private var backgroundRead = false
    private var rosterAt = 0L
    private var hasRoster = false
    private val path = "/api/plugins/beacon/mobile/bot-mode"
    private fun key(space: String) = "bot-mode:${requireNotNull(root) { "Load Bot Mode before editing a draft" }.key}:$space"
    private fun operationKey(purpose: String, identity: String, legacy: String): String {
        val fresh = "bot-mode-action:${requireNotNull(root).key}:$purpose:${identity.digest()}"
        fun eventId(value: String?) = value != null && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)
        // Draft keys stay compatible. A removed room can also leave a legacy
        // draft here, so require the original operation shape before adopting it.
        if (!saved.contains(fresh) && saved.get<Boolean>("$fresh:migrated") != true && hasRoster && !mutable.value.moreSpaces && mutable.value.spaces.none { key(it.id) == legacy }) {
            saved.get<String>(legacy)?.takeIf { value -> when (purpose) {
                "create" -> eventId(value)
                "event" -> eventId(value) && saved.get<String>(key(identity) + ":event-text") == mutable.value.draft
                "event-text" -> eventId(saved.get<String>(key(identity) + ":event")) && value == mutable.value.draft
                "rename" -> runCatching { JSONObject(value).let { it.opt("name") is String && it.string("name").isNotBlank() && it.opt("expected_name") is String && eventId(it.optString("event_id")) } }.getOrDefault(false)
                else -> false
            } }?.let { saved[fresh] = it }
            saved["$fresh:migrated"] = true
        }
        return fresh
    }
    private fun current(c: BeaconClient, token: Int): Boolean = source === c && token == generation &&
        client()?.let { it.auth.owner == c.auth.owner && it.auth.origin == c.auth.origin } == true
    fun clear() { ++generation; job?.cancel(); job = null; source?.closeNative(); source = null; accountClient = null; root = null; rosterAt = 0L; hasRoster = false; backgroundRead = false; mutable.value = NativeBotModeState() }
    fun draft(text: String) {
        if (root == null || mutable.value.selectedSpace.isBlank()) { mutable.value = mutable.value.copy(error = "Open an authorized shared space before editing its draft"); return }
        mutable.value = mutable.value.copy(draft = text); saved[key(mutable.value.selectedSpace)] = text
    }
    fun toggleBot(profile: String) {
        val old = mutable.value.selectedBots
        if (profile in old || old.size < 6) mutable.value = mutable.value.copy(selectedBots = if (profile in old) old - profile else old + profile)
    }
    private fun launch(userAction: Boolean = true, action: suspend (BeaconClient, Int) -> Unit) {
        val live = client() ?: run { clear(); return }
        if (root?.let { it.owner != live.auth.owner || it.origin != live.auth.origin } == true) clear()
        if (job?.isActive == true) {
            if (!userAction) return
            if (backgroundRead) { ++generation; job?.cancel(); job = null }
            else { mutable.value = mutable.value.copy(error = "A Bot Mode request is still running. Try again when it finishes."); return }
        }
        if (root == null) root = live.scope
        if (accountClient !== live) {
            source?.closeNative()
            source = live.detached()
            accountClient = live
        }
        val c = source ?: return
        val originalProfile = root!!.profile
        val token = generation
        backgroundRead = !userAction
        job = scope.launch {
            mutable.value = mutable.value.copy(loading = userAction || !hasRoster, error = if (userAction) "" else mutable.value.error)
            try {
                // An internal bot switch refreshes credentials without moving the shared-space scope.
                if (c.auth.profile != originalProfile) c.bindProfile(originalProfile)
                action(c, token)
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (current(c, token)) {
                    mutable.value = if (e is ApiFailure && e.status in setOf(401, 403)) mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), draft = "", status = "", error = e.message)
                    else mutable.value.copy(error = e.message ?: "Native Bot Mode unavailable")
                }
            }
            finally { if (current(c, token)) mutable.value = mutable.value.copy(loading = false, sending = false) }
        }
    }
    fun refresh(silent: Boolean = false) {
        launch(userAction = !silent) { c, token ->
            val now = clock()
            if (!silent || !hasRoster || now < rosterAt || now - rosterAt >= 30_000L) {
                val result = c.nativeRequest(path)
                if (!current(c, token)) return@launch
                mutable.value = mutable.value.copy(
                    bots = result.rows("bots").map { BotModeBot(it.getString("profile"), it.getString("title"), it.string("description")) },
                    spaces = result.rows("spaces").map { BotModeSpace(it.getString("room_id"), it.getString("name")) },
                    moreSpaces = result.optBoolean("has_more_spaces"),
                )
                hasRoster = true; rosterAt = clock()
            }
            val selected = mutable.value.selectedSpace
            if (selected.isNotBlank() && mutable.value.activeBot == null) {
                if (mutable.value.spaces.none { it.id == selected } && !mutable.value.moreSpaces) {
                    mutable.value = mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), draft = "", status = "", error = "This shared space is no longer authorized or available. Its local draft is retained.")
                } else readSpace(c, token, selected)
            }
        }
    }
    private suspend fun readSpace(c: BeaconClient, token: Int, id: String) {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Unsupported native shared-space identity" }
        val data = c.nativeRequest("$path/space/$id")
        if (!current(c, token) || mutable.value.selectedSpace != id) return
        val driver = data.optJSONObject("driver_status")
        val failed = driver?.optJSONObject("counts")?.optInt("failed", 0) ?: 0
        val room = data.optJSONObject("room")
        mutable.value = mutable.value.copy(
            pendingChange = room?.optJSONObject("pending_control"),
            members = room?.rows("members").orEmpty().map { it.string("profile") },
            spaces = mutable.value.spaces.map { if (it.id == id && room != null) it.copy(name = room.string("name", it.name)) else it },
            events = botModeEvents(data), earlierEventsOmitted = data.optBoolean("earlier_events_omitted"),
            actions = driver?.rows("pending_actions").orEmpty().mapNotNull { action ->
                when (action.string("kind")) {
                    "retry" -> BotModeAction("retry", action.getString("task_id"))
                    "approval" -> {
                        val approval = action.optJSONObject("approval") ?: return@mapNotNull null
                        val choices = approval.optJSONArray("choices")
                        BotModeAction("approval", action.getString("task_id"), action.getString("member_id"), action.getString("request_id"), action.getInt("execution_generation"),
                            listOf(approval.string("description"), approval.string("command")).filter { it.isNotBlank() }.joinToString("\n"),
                            (0 until (choices?.length() ?: 0)).mapNotNull { choices?.optString(it) }.filter { it in setOf("once", "deny") })
                    }
                    else -> null
                }
            },
            status = when { driver?.optBoolean("blocked") == true -> "Waiting for approval or retry"; driver?.optBoolean("working") == true -> "Bots are discussing…"; failed > 0 -> "Shared space needs attention"; driver?.optBoolean("running") == true -> "Shared space is ready"; else -> "Shared-space worker is unavailable" } + if (failed > 0) " · $failed failed bot turn${if (failed == 1) "" else "s"}. Review the failures below." else "")
    }
    fun selectSpace(id: String) {
        back()
        launch { c, token ->
            mutable.value = mutable.value.copy(selectedSpace = id, events = emptyList(), draft = saved.get<String>(key(id)).orEmpty(), error = "", status = "", actions = emptyList())
            readSpace(c, token, id)
        }
    }
    fun createSpace() {
        val chosen = mutable.value.selectedBots.sorted()
        launch { c, token ->
            val members = chosen.joinToString(",")
            val requestKey = operationKey("create", members, key("create:$members"))
            val requestId = saved.get<String>(requestKey) ?: UUID.randomUUID().toString().also { saved[requestKey] = it }
            val data = c.nativeRequest("$path/space", "POST", json("profiles" to org.json.JSONArray(chosen), "request_id" to requestId))
            if (current(c, token)) {
                val room = data.getJSONObject("room")
                val id = room.getString("room_id")
                saved.remove<String>(requestKey)
                mutable.value = mutable.value.copy(selectedSpace = id, spaces = (mutable.value.spaces + BotModeSpace(id, room.getString("name"))).distinctBy { it.id }, draft = saved.get<String>(key(id)).orEmpty())
                readSpace(c, token, id)
            }
        }
    }
    fun renameSpace(name: String) {
        val state = mutable.value
        val id = state.selectedSpace
        val original = state.spaces.firstOrNull { it.id == id }?.name ?: return
        if (name.isBlank() || name.length > 120 || name.any { it.isISOControl() }) {
            mutable.value = state.copy(error = "Enter a shared-space name of up to 120 characters"); return
        }
        launch { c, token ->
            val intentKey = operationKey("rename", id, key("rename:$id"))
            val intent = saved.get<String>(intentKey)?.let { runCatching { JSONObject(it) }.getOrNull() }?.takeIf { it.string("name") == name.trim() && it.string("expected_name") == original }
                ?: json("name" to name.trim(), "expected_name" to original, "event_id" to UUID.randomUUID().toString()).also { saved[intentKey] = it.toString() }
            val result = c.nativeRequest("$path/space/$id/rename", "POST", intent).getJSONObject("room")
            require(result.getString("room_id") == id && result.getString("name") == name.trim()) { "The new name was not confirmed. Refresh before retrying." }
            if (current(c, token) && mutable.value.selectedSpace == id) {
                saved.remove<String>(intentKey)
                mutable.value = mutable.value.copy(spaces = mutable.value.spaces.map { if (it.id == id) it.copy(name = result.getString("name")) else it })
                readSpace(c, token, id)
            }
        }
    }
    fun retryChange() {
        val state = mutable.value
        val id = state.selectedSpace
        val pending = state.pendingChange ?: return
        val request = pending.optJSONObject("request") ?: return
        val event = pending.string("event_id")
        if (id.isBlank() || event.isBlank()) return
        launch { c, token ->
            when (pending.string("operation")) {
                "members" -> {
                    val payload = json("event_id" to event, "expected_profiles" to request.getJSONArray("expected_profiles"),
                        "profiles" to org.json.JSONArray(request.rows("members").map { it.getString("profile") }))
                    c.nativeRequest("$path/space/$id/members", "POST", payload)
                    readSpace(c, token, id)
                }
                "delete" -> {
                    val result = c.nativeRequest("$path/space/$id/delete", "POST", json("event_id" to event, "expected_name" to request.getString("expected_name")))
                    require(result.optJSONObject("tombstone")?.string("room_id") == id && result.optJSONObject("tombstone")?.string("event_id") == event) { "Deletion was not confirmed. Refresh before retrying." }
                    if (current(c, token)) { saved.remove<String>(key(id)); mutable.value = mutable.value.copy(selectedSpace = "", spaces = state.spaces.filterNot { it.id == id }, events = emptyList(), actions = emptyList(), members = emptyList(), draft = "", status = "", pendingChange = null) }
                }
                else -> error("Refresh this space to review its pending change")
            }
        }
    }
    fun updateMembers(profiles: Set<String>) {
        val before = mutable.value
        val id = before.selectedSpace
        val chosen = profiles.sorted()
        if (id.isBlank() || chosen.size !in 2..6 || chosen.any { name -> before.bots.none { it.profile == name } }) {
            mutable.value = before.copy(error = "Choose two to six currently available bots. Refresh the space if its team changed."); return
        }
        launch { c, token ->
            val intentKey = operationKey("members", id, key("members:$id"))
            val intent = saved.get<String>(intentKey)?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.takeIf { it.optJSONArray("profiles")?.strings()?.sorted() == chosen && it.optJSONArray("expected_profiles")?.strings()?.sorted() == before.members.sorted() }
                ?: json("profiles" to org.json.JSONArray(chosen), "expected_profiles" to org.json.JSONArray(before.members.sorted()), "event_id" to UUID.randomUUID().toString()).also { saved[intentKey] = it.toString() }
            val result = c.nativeRequest("$path/space/$id/members", "POST", intent).getJSONObject("room")
            require(result.getString("room_id") == id && result.rows("members").map { it.getString("profile") }.sorted() == chosen) { "The bot selection was not confirmed. Refresh before retrying." }
            if (current(c, token) && mutable.value.selectedSpace == id) {
                saved.remove<String>(intentKey)
                mutable.value = mutable.value.copy(members = chosen)
                readSpace(c, token, id)
            }
        }
    }
    fun deleteSpace() {
        val before = mutable.value
        val id = before.selectedSpace
        val name = before.spaces.firstOrNull { it.id == id }?.name ?: run { mutable.value = before.copy(error = "This space changed. Refresh before deleting."); return }
        launch { c, token ->
            val intentKey = operationKey("delete", id, key("delete:$id"))
            val event = saved.get<String>(intentKey) ?: UUID.randomUUID().toString().also { saved[intentKey] = it }
            val result = c.nativeRequest("$path/space/$id/delete", "POST", json("event_id" to event, "expected_name" to name))
            require(result.optJSONObject("tombstone")?.string("room_id") == id && result.optJSONObject("tombstone")?.string("event_id") == event) { "Space deletion was not confirmed. Refresh before retrying." }
            if (current(c, token)) {
                saved.remove<String>(intentKey); saved.remove<String>(key(id))
                mutable.value = mutable.value.copy(selectedSpace = "", spaces = before.spaces.filterNot { it.id == id }, events = emptyList(), actions = emptyList(), members = emptyList(), draft = "", status = "")
            }
        }
    }
    fun send() = launch { c, token ->
        val old = mutable.value
        if (old.draft.isBlank() || old.selectedSpace.isBlank()) return@launch
        mutable.value = old.copy(sending = true, error = "")
        // Keep the identity with the draft: retry after an uncertain acknowledgement cannot duplicate a turn.
        val eventKey = operationKey("event", old.selectedSpace, key(old.selectedSpace) + ":event")
        val textKey = operationKey("event-text", old.selectedSpace, key(old.selectedSpace) + ":event-text")
        val event = saved.get<String>(eventKey)?.takeIf { saved.get<String>(textKey) == old.draft } ?: UUID.randomUUID().toString()
        saved[eventKey] = event; saved[textKey] = old.draft
        c.nativeRequest("$path/space/${old.selectedSpace}/messages", "POST", json("text" to old.draft, "event_id" to event))
        if (current(c, token)) {
            if (mutable.value.draft == old.draft) draft("")
            saved.remove<String>(eventKey); saved.remove<String>(textKey)
            readSpace(c, token, old.selectedSpace)
        }
    }
    fun action(action: BotModeAction, choice: String = "") {
        val id = mutable.value.selectedSpace
        val actionScope = root
        val payload = try { action.payload(choice) } catch (e: IllegalArgumentException) { mutable.value = mutable.value.copy(error = e.message.orEmpty()); return }
        if (id.isBlank() || actionScope == null) { mutable.value = mutable.value.copy(error = "Open the shared space before resolving its action"); return }
        launch { c, token ->
            require(root == actionScope) { "The account changed. Refresh before resolving this action." }
            c.nativeRequest("$path/space/$id/action", "POST", payload)
            readSpace(c, token, id)
        }
    }
    fun openBot(bot: BotModeBot) = launch { c, token ->
        mutable.value = mutable.value.copy(openingBot = bot, activeBot = null, sessionId = "")
        val data = c.nativeRequest("$path/chat", "POST", json("bot" to bot.profile))
        require(data.getString("profile") == bot.profile && data.getString("title") == "Bot Chat") { "Native Bot Chat identity changed" }
        val id = data.getString("stored_session_id")
        if (!current(c, token)) return@launch
        openChat(bot.profile, id)
        if (current(c, token)) mutable.value = mutable.value.copy(activeBot = bot, openingBot = null, sessionId = id)
    }
    fun pause() {
        ++generation; job?.cancel(); job = null
        mutable.value = mutable.value.copy(loading = false, sending = false)
    }
    fun resume() {
        val bot = mutable.value.activeBot ?: mutable.value.openingBot
        if (bot != null) openBot(bot) else refresh(silent = true)
    }
    fun home() {
        back()
        mutable.value = mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), draft = "", status = "")
    }
    fun back() {
        ++generation
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(activeBot = null, openingBot = null, sessionId = "", loading = false, sending = false, error = "")
    }
}
