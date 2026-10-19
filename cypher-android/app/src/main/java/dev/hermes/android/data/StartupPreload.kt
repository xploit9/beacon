package dev.hermes.android.data

data class StartupRead(val key: String, val path: String)

// Local configuration/catalog handlers only; no provider probes, MCP connect or mail reads.
val startupReads = listOf(
    StartupRead("navigation-profiles", "/api/profiles"),
    StartupRead("native-tools", "/api/tools/toolsets"),
    StartupRead("slash:catalog", "/api/commands/catalog"),
    StartupRead("native-projects", "/api/projects"),
    StartupRead("voice-settings", "/api/plugins/cypher/mobile/voice-settings")
)

const val STARTUP_READ_TTL = 120_000L
const val STARTUP_READ_PARALLELISM = 3
const val STARTUP_READ_BUDGET = 8_000L
fun startupReadFresh(at: Long, now: Long, invalidatedAt: Long = 0): Boolean = at > invalidatedAt && at > 0 && now >= at && now - at < STARTUP_READ_TTL

// The server supplies recent-first rows; prepare only six distinct non-Cypher conversations.
fun startupRecentSessions(ids: List<String>, cypher: String): List<String> =
    ids.asSequence().filter { it.isNotBlank() && it != cypher }.distinct().take(6).toList()
