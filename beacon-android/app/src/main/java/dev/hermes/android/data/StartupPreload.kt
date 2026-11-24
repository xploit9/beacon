package dev.hermes.android.data

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager

data class StartupRead(val key: String, val path: String, val query: Map<String, String> = emptyMap())

// Local configuration/catalog handlers only; no provider probes, MCP connect or mail reads.
val startupReads = listOf(
    StartupRead("navigation-profiles", "/api/profiles"),
    StartupRead("profile-defaults", PROFILE_DEFAULTS_PATH),
    StartupRead("native-tools", "/api/tools/toolsets"),
    StartupRead("slash:catalog", "/api/commands/catalog"),
    StartupRead("native-projects", "/api/projects"),
    StartupRead("voice-settings", "/api/plugins/beacon/mobile/voice-settings")
)

// Drawer and settings data warmed after the chat is usable (Files root is added at run time), so those screens open already loaded.
val prefetchReads = listOf(
    StartupRead("crons", "/api/crons"),
    StartupRead("cron-delivery", "/api/crons/delivery-options"),
    StartupRead("task-capabilities", "/api/plugins/beacon/mobile/tasks/capabilities"),
    StartupRead("picker:models", "/api/models"),
    StartupRead("native-mcp", "/api/mcp/servers"),
    StartupRead("profile:approvals", "/api/profile/approval-mode")
)

// The notification pages' two plugin reads: warmed too, so Notifications opens with its push status and history retention already in hand.
const val PUSH_STATUS_READ_KEY = "push-status"
const val PUSH_STATUS_READ_PATH = "/api/plugins/beacon/mobile/notifications/status"
const val HISTORY_RETENTION_READ_KEY = "history-retention"
const val HISTORY_RETENTION_READ_PATH = "/api/plugins/beacon/mobile/history-retention"

const val PREFETCH_PARALLELISM = 2
const val PREFETCH_SCHEDULE_HISTORIES = 3
const val PREFETCH_PROBE_WAIT = 6_000L

/** How many left profiles keep their screens in memory for a hop back (the assistant's profile and out). */
const val KEPT_PROFILE_SCREENS = 4

/** What a read asked for before the client exists puts on its screen. */
enum class EarlyRead { SavedCopy, Waiting, Reconnect }

/** A restored sign-in shows what is saved for the read; with nothing saved it waits while connecting, and says "Reconnect" only when it is not. No restored sign-in has nothing to show. */
fun earlyReadShows(restoredOwner: Boolean, hasSavedCopy: Boolean, connecting: Boolean): EarlyRead = when {
    !restoredOwner -> EarlyRead.Reconnect
    hasSavedCopy -> EarlyRead.SavedCopy
    connecting -> EarlyRead.Waiting
    else -> EarlyRead.Reconnect
}

/** The screens of the profiles this run left, by profile scope key, so a hop out and back (the assistant's profile) shows them at once.
 *  Memory only; a profile gets back only its own answers (never an error or a loading mark), marked stale until read again. */
class KeptProfileData(private val limit: Int = KEPT_PROFILE_SCREENS) {
    private val kept = LinkedHashMap<String, Map<String, org.json.JSONObject>>()
    fun swap(left: String, entered: String, showing: Map<String, org.json.JSONObject>): Map<String, org.json.JSONObject> {
        val leaving = showing.filterValues { !it.has("_error") && !it.optBoolean("_loading") }
        kept.remove(left)
        if (leaving.isNotEmpty()) kept[left] = leaving
        while (kept.size > limit) kept.remove(kept.keys.first())
        return kept.remove(entered).orEmpty().mapValues { org.json.JSONObject(it.value.toString()).put("_stale", true) }
    }
    fun clear() = kept.clear()
    val size get() = kept.size
}

/** The ids of the chats whose saved transcripts leave the phone at sign-out: those the saved chat list names, plus the pointers (the Beacon chat, the open chat, the new-chat shell). */
fun savedChatIds(indexJson: String?, pointers: List<String?>): Set<String> {
    val ids = linkedSetOf<String>()
    runCatching {
        indexJson?.let { org.json.JSONObject(it).optJSONArray("sessions") }?.let { rows ->
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let { row -> row.optString("session_id", row.optString("id")).takeIf(String::isNotBlank)?.let(ids::add) }
        }
    }
    pointers.forEach { it?.takeIf(String::isNotBlank)?.let(ids::add) }
    return ids
}
const val PREFETCH_READY_WAIT = 15_000L
const val PREFETCH_DELAY = 1_500L
const val PREFETCH_REWARM_AFTER = 5 * 60_000L
const val STARTUP_READ_TTL = 120_000L
const val STARTUP_READ_PARALLELISM = 3
const val STARTUP_READ_BUDGET = 8_000L
fun startupReadFresh(at: Long, now: Long, invalidatedAt: Long = 0): Boolean = at > invalidatedAt && at > 0 && now >= at && now - at < STARTUP_READ_TTL

// The server supplies recent-first rows; prepare only six distinct non-Beacon conversations.
fun startupRecentSessions(ids: List<String>, beacon: String): List<String> =
    ids.asSequence().filter { it.isNotBlank() && it != beacon }.distinct().take(6).toList()

// What the warm-up may spend. The phone is asked when the warm-up runs, in the foreground only, and a phone that cannot say is treated as unrestricted.
data class WarmConditions(val metered: Boolean = false, val dataSaver: Boolean = false, val powerSave: Boolean = false)

fun readWarmConditions(context: Context): WarmConditions = try {
    val network = context.getSystemService(ConnectivityManager::class.java)
    WarmConditions(
        metered = network?.isActiveNetworkMetered == true,
        dataSaver = network?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
        powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
    )
} catch (_: Exception) { WarmConditions() }

const val WARM_CHATS = 6
const val WARM_CHAT_PARALLELISM = 2
/** Its own budget: the Beacon chat and the other recent chats no longer share one. */
const val WARM_BEACON_BUDGET = 8_000L
/** What the list of chats may take before the recent ones are read, and then what those reads may take together. */
const val WARM_INDEX_WAIT = 20_000L
const val WARM_CHAT_BUDGET = 30_000L

/** How many recent chats to warm: six, three on a metered network or with Data Saver, two with both, one under battery saver. */
fun warmChatLimit(conditions: WarmConditions): Int = when {
    conditions.powerSave -> 1
    conditions.metered && conditions.dataSaver -> 2
    conditions.metered || conditions.dataSaver -> 3
    else -> WARM_CHATS
}

/** Battery saver warms the few small reads the screens open on, and nothing else. */
fun warmScreensAllowed(conditions: WarmConditions): Boolean = !conditions.powerSave

/** A listed chat: its id and its last activity in epoch ms (0 when the row has none). */
data class WarmRow(val id: String, val activityMs: Long)

/** A saved transcript older than this is about to expire, so it counts as absent (ProjectionCache drops it on the next read). */
private const val TRANSCRIPT_EXPIRY_MARGIN = 60 * 60_000L

/**
 * A saved transcript is current when it was saved after the chat's last activity. With no activity to compare (the Beacon chat may not be in the list), it is current for [STARTUP_READ_TTL].
 * [savedAt] is when the phone saved it (0 when nothing is saved).
 */
fun transcriptFresh(savedAt: Long, activityMs: Long, now: Long): Boolean =
    savedAt > 0 && savedAt <= now && now - savedAt < ProjectionCache.MAX_AGE - TRANSCRIPT_EXPIRY_MARGIN &&
        if (activityMs > 0) savedAt >= activityMs else now - savedAt < STARTUP_READ_TTL

/** The top [limit] listed chats (the server lists them recent-first), without the Beacon chat. */
fun startupWarmCandidates(rows: List<WarmRow>, beacon: String, limit: Int): List<WarmRow> =
    rows.asSequence().filter { it.id.isNotBlank() && it.id != beacon }.distinctBy { it.id }.take(limit).toList()

/** The chats to read: the candidates less the ones whose saved copy is already current. */
fun startupWarmTargets(rows: List<WarmRow>, beacon: String, limit: Int, savedAt: (String) -> Long, now: Long): List<WarmRow> =
    startupWarmCandidates(rows, beacon, limit).filterNot { transcriptFresh(savedAt(it.id), it.activityMs, now) }

// Keys that name one chat. They are kept for the open chat only, so a long session does not collect one for every chat it ever opened.
private val CHAT_KEY_PREFIXES = listOf("project-of:", "session-usage:")
fun isChatScreenKey(key: String): Boolean = CHAT_KEY_PREFIXES.any { key.startsWith(it) }
fun <T> withoutOtherChatKeys(data: Map<String, T>, openId: String): Map<String, T> =
    if (data.keys.none(::isChatScreenKey)) data
    else data.filterKeys { key -> !isChatScreenKey(key) || openId.isNotBlank() && CHAT_KEY_PREFIXES.any { key == it + openId } }
