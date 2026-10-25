package dev.hermes.android.data

data class StartupRead(val key: String, val path: String, val query: Map<String, String> = emptyMap())

// Local configuration/catalog handlers only; no provider probes, MCP connect or mail reads.
val startupReads = listOf(
    StartupRead("navigation-profiles", "/api/profiles"),
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

const val PREFETCH_PARALLELISM = 2
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
