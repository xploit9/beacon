package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class BotModeBot(val profile: String, val title: String, val description: String)
/** The newest message read in a space: who wrote it (a member id, or "You") and its text on one line. */
data class BotModePreview(val speaker: String, val text: String)
/** [members] are the bots' profiles, [updatedAt] is Hermes' last change in epoch seconds (0 when it sent none), [last] stays null until the space has been read on this phone. */
data class BotModeSpace(val id: String, val name: String, val members: List<String> = emptyList(), val updatedAt: Long = 0L, val last: BotModePreview? = null)
/** [at] is Hermes' event time in epoch seconds; 0 when it sent none. */
data class BotModeEvent(val id: String, val speaker: String, val text: String, val failed: Boolean = false, val at: Long = 0L)
/** A bot in a shared space. [id] is Hermes' member id (what events and typing name), [handle] is what an `@mention` addresses, [name] is blank unless Hermes set one. */
data class BotModeMember(val id: String, val profile: String, val handle: String, val name: String = "")
data class BotModeAction(val kind: String, val task: String, val member: String = "", val request: String = "", val generation: Int = 0, val description: String = "", val choices: List<String> = emptyList()) {
    fun payload(choice: String = ""): JSONObject {
        require(kind == "approval" && task.isNotBlank() && member.isNotBlank() && request.isNotBlank() && generation > 0) { "This action cannot be safely resolved in Beacon. Review the shared space in Hermes." }
        require(choice in choices && choice in setOf("once", "deny")) { "This approval choice is no longer available. Refresh the shared space." }
        return json("kind" to kind, "task_id" to task, "member_id" to member, "request_id" to request, "execution_generation" to generation, "choice" to choice)
    }
}
data class NativeBotModeState(
    val bots: List<BotModeBot> = emptyList(), val spaces: List<BotModeSpace> = emptyList(),
    val selectedSpace: String = "", val members: List<String> = emptyList(), val events: List<BotModeEvent> = emptyList(), val draft: String = "",
    val selectedBots: Set<String> = emptySet(), val openingBot: BotModeBot? = null, val activeBot: BotModeBot? = null, val sessionId: String = "",
    val loading: Boolean = false, val sending: Boolean = false, val error: String = "", val status: String = "",
    val canRename: Boolean = false, val canDelete: Boolean = false,
    val earlierEventsOmitted: Boolean = false, val moreSpaces: Boolean = false, val actions: List<BotModeAction> = emptyList(),
    val roster: List<BotModeMember> = emptyList(), val typing: List<String> = emptyList(), val working: Boolean = false,
    /** True once a list is on screen: Hermes' answer or the last one saved on this phone. Until then "no bots" is not known, so no screen may say it. */
    val loaded: Boolean = false,
)

/** A room is read fast while a bot answers, so its "typing" and the reply show up promptly. */
internal fun botModePollMillis(working: Boolean): Long = if (working) 1_000L else 3_000L

/** The bots a new space gets: those picked plus the one in use (Hermes only lets a space's own bots open it), two to six of them, else null. */
internal fun botModeChosen(selected: Set<String>, using: String): List<String>? =
    (selected + using).filter(String::isNotBlank).distinct().sorted().takeIf { it.size in 2..6 }

/** What Bot Mode says when a request fails: Hermes' refusals and internal messages become one plain line; the app's own plain sentences pass. */
internal fun botModeFailureText(error: Throwable): String = when {
    error is java.io.IOException -> appErrorText(error)
    // Only a sentence this file wrote itself (a require or check) passes; a parser's or the runtime's message never does.
    (error is IllegalArgumentException || error is IllegalStateException) && error !is NumberFormatException ->
        error.message.orEmpty().takeIf { it.isNotBlank() && it.length <= 160 && !it.contains("native", ignoreCase = true) && '\n' !in it && it.endsWith('.') } ?: BOT_MODE_UNAVAILABLE
    else -> BOT_MODE_UNAVAILABLE
}
internal const val BOT_MODE_UNAVAILABLE = "Bot Mode isn't available right now."
/** What a Bot Chat that would not open says: short, and nothing about owners, stores or routes. */
internal fun botChatFailure(status: Int): String = when {
    status == 409 -> "This Bot Chat can't be opened right now."
    status >= 500 -> "Bot Chat isn't available right now."
    else -> "Couldn't open this Bot Chat."
}

/** Hermes names a new space this; an owner's or the bots' name goes on with the rename route. */
internal const val NEW_SPACE_NAME = "Shared space"

internal fun botModePreview(events: List<BotModeEvent>): BotModePreview? =
    events.lastOrNull { !it.failed }?.let { BotModePreview(it.speaker, it.text.replace(Regex("\\s+"), " ").trim().take(140)) }

internal fun botModeSpaces(rows: List<JSONObject>, last: Map<String, BotModePreview>): List<BotModeSpace> = rows.map { row ->
    val id = row.getString("room_id")
    BotModeSpace(id, row.getString("name"), row.rows("members").map { it.string("profile") }.filter(String::isNotBlank),
        (row.opt("updated_at") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L, last[id])
}

internal fun botModeRoster(room: JSONObject?): List<BotModeMember> = room?.rows("members").orEmpty().mapNotNull { member ->
    val profile = member.string("profile").ifBlank { return@mapNotNull null }
    // Hermes' own handle rule for a default-profile member, for a room that sent none.
    BotModeMember(member.string("member_id").ifBlank { profile }, profile, member.string("handle").ifBlank { if (profile == "default") "hermes" else profile }, member.string("display_name"))
}

/** Who is answering now: Hermes' `member_activity`, once per member. Empty on a Hermes that does not send it. */
// Like Hermes' own group room: a bot waiting its turn is queued, not typing; only the one answering types.
internal fun botModeTyping(data: JSONObject): List<String> =
    data.optJSONObject("driver_status")?.rows("member_activity").orEmpty().filter { it.string("status") != "queued" }
        .map { it.string("member_id") }.filter(String::isNotBlank).distinct()

internal fun botModeDisbanded(data: JSONObject, id: String): Boolean {
    val receipt = data.optJSONObject("tombstone") ?: return false
    val event = receipt.optJSONObject("event") ?: return false
    val stamp = receipt.opt("disbanded_at") as? Number ?: return false
    return receipt.string("room_id") == id && stamp.toDouble().isFinite() && stamp.toDouble() > 0 &&
        receipt.opt("idempotent") is Boolean && event.string("room_id") == id &&
        event.string("kind") == "room.disbanded" && event.string("event_id") == "system:room-disbanded"
}

internal fun botModeActions(data: JSONObject): List<BotModeAction> = data.optJSONObject("driver_status")?.rows("pending_actions").orEmpty().mapNotNull { action ->
    val kind = action.string("kind")
    if (kind in setOf("retry", "discard")) return@mapNotNull BotModeAction("unavailable", action.string("task_id"), description = "Finish this in Hermes. It may already have run.")
    if (kind != "approval") return@mapNotNull null
    if (data.optJSONObject("capabilities")?.optBoolean("approval") != true) return@mapNotNull BotModeAction("unavailable", action.string("task_id"), description = "Couldn't check approvals. Refresh this space.")
    val task = action.opt("task_id") as? String ?: return@mapNotNull null
    val member = action.opt("member_id") as? String ?: return@mapNotNull null
    val request = action.opt("request_id") as? String ?: return@mapNotNull null
    val generation = action.opt("execution_generation") as? Int ?: return@mapNotNull null
    if (task.isBlank() || member.isBlank() || request.isBlank() || generation < 1) return@mapNotNull null
    val approval = action.optJSONObject("approval") ?: return@mapNotNull null
    val choices = approval.optJSONArray("choices")
    BotModeAction("approval", task, member, request, generation,
        listOf(approval.string("description"), approval.string("command")).filter { it.isNotBlank() }.joinToString("\n"),
        (0 until (choices?.length() ?: 0)).mapNotNull { choices?.optString(it) }.filter { it in setOf("once", "deny") })
}

internal fun botModeEvents(data: JSONObject): List<BotModeEvent> = data.rows("events").mapNotNull { event ->
    val payload = event.optJSONObject("payload") ?: return@mapNotNull null
    val kind = event.string("kind")
    if (kind == "turn.failed") {
        val reason = payload.string("reason_code")
        val error = payload.string("error")
        val readable = when {
            reason == "provider_quota_limit" || Regex("(?i)http\\s*402|insufficient.?balance|out of funds").containsMatchIn(error) -> "This bot's provider is out of credit. Check it in Profiles."
            reason == "provider_auth_or_access" -> "This bot's provider refused its key. Check it in Profiles."
            reason == "provider_rate_limit" -> "This bot's provider is busy. Try again soon."
            reason == "provider_server_error" -> "This bot's provider didn't reply."
            reason == "missing_config" || reason == "model_unavailable" -> "This bot's model isn't available. Check it in Profiles."
            reason == "context_overflow" -> "This bot's chat is too long for its model."
            reason == "runtime_offline" || reason == "delivery_timeout" -> "This bot didn't answer."
            reason == "agent_blocked" -> "This bot was stopped from replying."
            reason == "cancelled" -> "This bot's reply was cancelled."
            else -> "This bot couldn't reply. Check its model in Profiles."
        }
        return@mapNotNull BotModeEvent(event.getString("event_id"), payload.getString("member_id"), readable, true, eventSeconds(event))
    }
    val text = payload.string("text")
    if (kind !in setOf("message.user", "message.member") || text.isBlank()) return@mapNotNull null
    BotModeEvent(event.getString("event_id"), if (kind == "message.user") "You" else payload.getString("member_id"), text, at = eventSeconds(event))
}

private fun eventSeconds(event: JSONObject): Long = (event.opt("created_at") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: 0L

/** Chat rendering and tool approvals stay in the existing conversation controller. */
class NativeBotModeController(
    private val scope: CoroutineScope, private val saved: SavedStateHandle,
    private val openChat: suspend (String, String) -> Unit,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val screens: ScreenCache? = null,
    private val projections: ProjectionCache? = null,
    private val restoredScope: () -> Scope? = { null },
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
    // The newest line read in each space, kept for the list; a space not opened on this phone yet has none.
    private val last = HashMap<String, BotModePreview>()
    // The list as last read, kept with those lines so Bot Mode opens already filled after a restart.
    private var rosterRead: JSONObject? = null
    private var rosterSaved = ""
    // The account the list shown before any client came was saved for.
    private var paint: Triple<String, String, String>? = null
    private val path = "/api/plugins/beacon/mobile/bot-mode"
    private fun key(space: String) = "bot-mode:${requireNotNull(root ?: restoredScope()) { "Load Bot Mode before editing a draft" }.key}:$space"
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
        client()?.let { it.scope.sameAccount(c.scope) } == true
    fun clear() { ++generation; job?.cancel(); job = null; source?.closeNative(); source = null; accountClient = null; root = null; rosterAt = 0L; hasRoster = false; backgroundRead = false; last.clear(); rosterRead = null; rosterSaved = ""; paint = null; mutable.value = NativeBotModeState() }
    fun draft(text: String) {
        if ((root ?: restoredScope()) == null || mutable.value.selectedSpace.isBlank()) { mutable.value = mutable.value.copy(error = "Open a shared space first."); return }
        mutable.value = mutable.value.copy(draft = text); saved[key(mutable.value.selectedSpace)] = text
    }
    /** The bot you are using is always in a space you create: Hermes lets only a space's own bots open it. */
    fun usingBot(): String = (root ?: client()?.scope)?.profile.orEmpty()
    fun toggleBot(profile: String) {
        val old = mutable.value.selectedBots
        if (profile == usingBot()) return
        if (profile in old || (old + usingBot()).size < 6) mutable.value = mutable.value.copy(selectedBots = if (profile in old) old - profile else old + profile)
    }
    /** Opens a New chat with nobody picked yet, or with [first] already picked. */
    fun startNewChat(first: String = "") { mutable.value = mutable.value.copy(selectedBots = setOf(first).filterTo(HashSet()) { it.isNotBlank() && it != usingBot() }, error = "") }
    fun cancelNewChat() { mutable.value = mutable.value.copy(selectedBots = emptySet(), error = "") }
    /** Bots or their roles changed on Hermes: read the list again now, not at the next 30-second refresh. */
    fun rosterChanged() { rosterAt = 0L; if (job?.isActive != true || backgroundRead) refresh() }
    private fun launch(userAction: Boolean = true, action: suspend (BeaconClient, Int) -> Unit) {
        // Before any client exists (a cold start) there is nothing bound to clear, and the saved list stays on screen.
        val live = client() ?: run { if (root != null) clear(); return }
        paint?.let { (origin, owner, profile) -> if (live.scope.origin != origin || live.scope.owner != owner || live.scope.profile != profile) clear() else paint = null }
        if (root?.let { !it.sameAccount(live.scope) } == true) clear()
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
                    mutable.value = if (e is ApiFailure && e.status in setOf(401, 403)) mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), roster = emptyList(), typing = emptyList(), working = false, draft = "", status = "", error = botModeFailureText(e))
                    else mutable.value.copy(error = botModeFailureText(e))
                }
            }
            finally { if (current(c, token)) mutable.value = mutable.value.copy(loading = false, sending = false) }
        }
    }
    private fun showRoster(result: JSONObject) {
        mutable.value = mutable.value.copy(
            bots = result.rows("bots").map { BotModeBot(it.getString("profile"), it.getString("title"), it.string("description")) },
            spaces = botModeSpaces(result.rows("spaces"), last),
            moreSpaces = result.optBoolean("has_more_spaces"),
            canRename = result.optJSONObject("capabilities")?.optBoolean("rename") == true,
            canDelete = result.optJSONObject("capabilities")?.optBoolean("delete") == true,
            loaded = true,
        )
    }
    private fun saveRoster(c: BeaconClient) {
        val read = rosterRead ?: return
        val cache = screens ?: return
        val previews = JSONObject().apply { last.forEach { (id, line) -> put(id, JSONObject().put("speaker", line.speaker).put("text", line.text)) } }
        val value = JSONObject(read.toString()).put("previews", previews)
        val text = value.toString()
        val at = c.scope
        if (text == rosterSaved && cache.exact(at, "bot-mode:roster") != null) return
        rosterSaved = text
        // Publish memory snapshots in order; ScreenCache already writes the disk row asynchronously.
        try { cache.set(at, "bot-mode:roster", value) } catch (e: Exception) { if (e is CancellationException) throw e }
    }
    /**
     * The last list saved on this phone, shown before the app is connected (a cold start waits a moment for Hermes). Ignored once a list is
     * on screen or the controller is bound to an account: from then on it reads its own cache, scoped to that account and profile.
     * [origin], [owner] and [profile] name the account it was saved for; a client of any other one drops it before reading its own list.
     */
    fun showCached(stored: JSONObject?, origin: String, owner: String, profile: String) {
        if (stored == null || root != null || mutable.value.loaded) return
        paint = Triple(origin, owner, profile)
        stored.optJSONObject("previews")?.let { lines -> lines.keys().forEach { id -> lines.optJSONObject(id)?.let { last.putIfAbsent(id, BotModePreview(it.string("speaker"), it.string("text"))) } } }
        try { showRoster(stored) } catch (e: org.json.JSONException) { last.clear() }
    }
    fun refresh(silent: Boolean = false) {
        launch(userAction = !silent) { c, token ->
            if (!hasRoster && rosterRead == null) try {
                screens?.open(c.scope)
                screens?.get(c.scope, "bot-mode:roster")?.let { stored ->
                    if (!current(c, token) || hasRoster) return@let
                    stored.optJSONObject("previews")?.let { lines -> lines.keys().forEach { id -> lines.optJSONObject(id)?.let { last.putIfAbsent(id, BotModePreview(it.string("speaker"), it.string("text"))) } } }
                    rosterRead = stored; showRoster(stored)
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
            val now = clock()
            if (!silent || !hasRoster || now < rosterAt || now - rosterAt >= 30_000L) {
                val result = c.nativeRequest(path)
                if (!current(c, token)) return@launch
                showRoster(result)
                rosterRead = result; saveRoster(c)
                hasRoster = true; rosterAt = clock()
            }
            val selected = mutable.value.selectedSpace
            if (selected.isNotBlank() && mutable.value.activeBot == null) {
                if (mutable.value.spaces.none { it.id == selected } && !mutable.value.moreSpaces) {
                    mutable.value = mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), roster = emptyList(), typing = emptyList(), working = false, draft = "", status = "", error = "This shared space isn't available any more. Your draft is kept.")
                } else readSpace(c, token, selected)
            }
        }
    }
    private suspend fun readSpace(c: BeaconClient, token: Int, id: String) {
        require(id.matches(Regex("[A-Za-z0-9_.:-]{1,128}"))) { "Unsupported native shared-space identity" }
        val data = try { c.nativeRequest("$path/space/$id") } catch (e: ApiFailure) {
            if (e.status in setOf(401, 403, 404) && current(c, token)) {
                mutable.value = mutable.value.copy(events = emptyList(), actions = emptyList())
                projections?.delete(c.scope, "space-history:$id")
            }
            throw e
        }
        if (!current(c, token) || mutable.value.selectedSpace != id) return
        val driver = data.optJSONObject("driver_status")
        val failed = driver?.optJSONObject("counts")?.optInt("failed", 0) ?: 0
        val room = data.optJSONObject("room")
        val typing = botModeTyping(data)
        val events = botModeEvents(data)
        val historyChanged = events != mutable.value.events || data.optBoolean("earlier_events_omitted") != mutable.value.earlierEventsOmitted
        botModePreview(events)?.let { if (last[id] != it) { last[id] = it; saveRoster(c) } }
        mutable.value = mutable.value.copy(
            roster = botModeRoster(room), typing = typing, working = typing.isNotEmpty() || driver?.optBoolean("working") == true,
            canRename = data.optJSONObject("capabilities")?.optBoolean("rename") == true,
            canDelete = data.optJSONObject("capabilities")?.optBoolean("delete") == true,
            members = room?.rows("members").orEmpty().map { it.string("profile") },
            spaces = mutable.value.spaces.map { if (it.id == id) it.copy(name = room?.string("name", it.name) ?: it.name, last = last[id] ?: it.last, updatedAt = maxOf(it.updatedAt, events.maxOfOrNull { event -> event.at } ?: 0L)) else it },
            events = events, earlierEventsOmitted = data.optBoolean("earlier_events_omitted"),
            actions = botModeActions(data),
            status = when { driver?.optBoolean("blocked") == true -> "Shared space needs you"; driver?.optBoolean("working") == true -> "Bots are discussing…"; failed > 0 -> "Shared space needs you"; driver?.optBoolean("running") == true -> "Shared space is ready"; else -> "Shared space is offline" } + if (failed > 0) " · $failed bot ${if (failed == 1) "reply" else "replies"} failed" else "")
        if (projections == null || !historyChanged) return
        val kept = events.takeLast(100).map { json("id" to it.id, "speaker" to it.speaker, "text" to it.text, "failed" to it.failed, "at" to it.at) }
        val history = json("events" to org.json.JSONArray(kept), "earlier" to (events.size > kept.size || data.optBoolean("earlier_events_omitted")))
        try { projections.write(c.scope, "space-history:$id", history.toString()) }
        catch (e: CancellationException) { throw e } catch (_: Exception) { /* The live copy is already on screen. */ }
    }
    private suspend fun openSavedSpace(owner: Scope, id: String, current: () -> Boolean) {
        val cached = if (mutable.value.spaces.any { it.id == id }) try {
            projections?.read(owner, "space-history:$id")?.let(::JSONObject)
        } catch (e: CancellationException) { throw e } catch (_: Exception) { null } else null
        if (!current()) return
        val events = cached?.rows("events").orEmpty().map { BotModeEvent(it.string("id"), it.string("speaker"), it.string("text"), it.optBoolean("failed"), it.optLong("at")) }
        mutable.value = mutable.value.copy(selectedSpace = id, events = events, earlierEventsOmitted = cached?.optBoolean("earlier") == true,
            roster = emptyList(), members = emptyList(), typing = emptyList(), working = false, draft = saved.get<String>("bot-mode:${owner.key}:$id").orEmpty(),
            canRename = false, canDelete = false, error = "", status = "", actions = emptyList())
    }
    fun selectSpace(id: String) {
        back()
        if (client() == null) {
            val owner = restoredScope() ?: return
            showCached(screens?.exact(owner, "bot-mode:roster")?.value, owner.origin, owner.owner, owner.profile)
            if (paint != Triple(owner.origin, owner.owner, owner.profile) || mutable.value.spaces.none { it.id == id }) return
            val token = generation
            job = scope.launch { openSavedSpace(owner, id) { generation == token && (client()?.scope ?: restoredScope()) == owner } }
            return
        }
        launch { c, token ->
            openSavedSpace(c.scope, id) { current(c, token) }
            if (!current(c, token)) return@launch
            readSpace(c, token, id)
        }
    }
    /** Creates the space with the picked bots and the one in use. A non-blank [name] replaces Hermes' own "Shared space" through the rename route. */
    fun createSpace(name: String = "") {
        val chosen = botModeChosen(mutable.value.selectedBots, usingBot()) ?: run { mutable.value = mutable.value.copy(error = "Choose two to six bots."); return }
        launch { c, token ->
            val members = chosen.joinToString(",")
            val requestKey = operationKey("create", members, key("create:$members"))
            val requestId = saved.get<String>(requestKey) ?: UUID.randomUUID().toString().also { saved[requestKey] = it }
            val data = c.nativeRequest("$path/space", "POST", json("profiles" to org.json.JSONArray(chosen), "request_id" to requestId))
            if (current(c, token)) {
                val room = data.getJSONObject("room")
                val id = room.getString("room_id")
                saved.remove<String>(requestKey)
                var created = room.getString("name")
                var unnamed = false
                val wanted = name.trim().take(120)
                // Only a space still carrying Hermes' own name is renamed: a retry that finds an older space must not overwrite its name.
                if (wanted.isNotEmpty() && created == NEW_SPACE_NAME && wanted != created && !wanted.any { it.isISOControl() } && mutable.value.canRename) {
                    try { created = c.nativeRequest("$path/space/$id/rename", "POST", json("name" to wanted, "expected_name" to created, "event_id" to UUID.randomUUID().toString())).getJSONObject("room").getString("name") }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { unnamed = true }
                }
                mutable.value = mutable.value.copy(selectedSpace = id, spaces = (listOf(BotModeSpace(id, created, chosen, clock() / 1000)) + mutable.value.spaces).distinctBy { it.id }, draft = saved.get<String>(key(id)).orEmpty())
                readSpace(c, token, id)
                if (unnamed && current(c, token)) mutable.value = mutable.value.copy(error = "The chat was created, but its name wasn't saved.")
            }
        }
    }
    fun renameSpace(name: String, id: String = mutable.value.selectedSpace) {
        val state = mutable.value
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
            if (current(c, token)) {
                saved.remove<String>(intentKey)
                mutable.value = mutable.value.copy(spaces = mutable.value.spaces.map { if (it.id == id) it.copy(name = result.getString("name")) else it })
                if (mutable.value.selectedSpace == id) readSpace(c, token, id)
            }
        }
    }
    fun deleteSpace(id: String = mutable.value.selectedSpace) {
        val before = mutable.value
        if (!before.canDelete) { mutable.value = before.copy(error = "Can't delete this space right now. Refresh and try again."); return }
        val name = before.spaces.firstOrNull { it.id == id }?.name ?: run { mutable.value = before.copy(error = "This space changed. Refresh before deleting."); return }
        launch { c, token ->
            val intentKey = operationKey("delete", id, key("delete:$id"))
            val event = saved.get<String>(intentKey) ?: UUID.randomUUID().toString().also { saved[intentKey] = it }
            try {
                val result = c.nativeRequest("$path/space/$id/delete", "POST", json("event_id" to event, "expected_name" to name))
                require(botModeDisbanded(result, id)) { "Space deletion was not confirmed. Refresh before retrying." }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                // Stop can commit before deletion fails; reread without discarding the draft or intent.
                try { readSpace(c, token, id) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
                throw e
            }
            if (current(c, token)) {
                saved.remove<String>(intentKey); saved.remove<String>(key(id)); last.remove(id)
                val now = mutable.value
                val rest = now.copy(spaces = now.spaces.filterNot { it.id == id })
                mutable.value = if (now.selectedSpace != id) rest else rest.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), members = emptyList(), roster = emptyList(), typing = emptyList(), working = false, draft = "", status = "")
                projections?.delete(c.scope, "space-history:$id")
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
        val payload = try { action.payload(choice) } catch (e: IllegalArgumentException) { mutable.value = mutable.value.copy(error = botModeFailureText(e)); return }
        if (id.isBlank() || actionScope == null) { mutable.value = mutable.value.copy(error = "Open the shared space first."); return }
        launch { c, token ->
            require(root == actionScope) { "The account changed. Refresh before resolving this action." }
            c.nativeRequest("$path/space/$id/action", "POST", payload)
            readSpace(c, token, id)
        }
    }
    fun openBot(bot: BotModeBot) = launch { c, token ->
        mutable.value = mutable.value.copy(openingBot = bot, activeBot = null, sessionId = "")
        try {
            val data = c.nativeRequest("$path/chat", "POST", json("bot" to bot.profile))
            require(data.getString("profile") == bot.profile && data.getString("title") == "Bot Chat") { "Native Bot Chat identity changed" }
            val id = data.getString("stored_session_id")
            if (!current(c, token)) return@launch
            openChat(bot.profile, id)
            if (current(c, token)) mutable.value = mutable.value.copy(activeBot = bot, openingBot = null, sessionId = id)
        } catch (e: CancellationException) { throw e }
        // A signed-out or refused account keeps its own handling; anything else is said in plain words, whatever an older plugin wrote.
        catch (e: ApiFailure) { if (e.status in setOf(401, 403)) throw e; throw IllegalStateException(botChatFailure(e.status)) }
        catch (e: IllegalArgumentException) { throw IllegalStateException(botChatFailure(0)) }
        catch (e: org.json.JSONException) { throw IllegalStateException(botChatFailure(0)) }
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
        mutable.value = mutable.value.copy(selectedSpace = "", events = emptyList(), actions = emptyList(), roster = emptyList(), typing = emptyList(), working = false, draft = "", status = "")
    }
    fun back() {
        ++generation
        job?.cancel(); job = null
        mutable.value = mutable.value.copy(activeBot = null, openingBot = null, sessionId = "", loading = false, sending = false, error = "")
    }
}
