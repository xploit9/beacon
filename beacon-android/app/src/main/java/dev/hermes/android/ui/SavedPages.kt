package dev.hermes.android.ui

import org.json.JSONObject

/**
 * The saved answers a page can show by itself while Hermes is not reached (a cold start offline). A page with none keeps the full
 * "Reconnect to Hermes" page. Log text is only held in process.
 */
private fun savedKeys(page: String): List<(String) -> Boolean> = when (page) {
    "Skills" -> listOf { it == "settings:skills" }
    "Memory" -> listOf { it == "settings:memory" }
    "Vault" -> listOf { it == VAULT_CACHE_KEY }
    "Profiles" -> listOf { it == "settings:bots" }
    "Bots" -> listOf({ it == "bot-mode:roster" }, { it == "settings:bots" })
    "Providers & models" -> listOf { it == "picker:models" }
    "Tools & commands" -> listOf { it == "native-tools" }
    "MCP servers" -> listOf { it == "native-mcp" }
    "Health" -> listOf { it == "settings:health" }
    "Logs" -> listOf { it == "settings:logs:errors" }
    "Server updates" -> listOf { it == "settings:update-check" }
    "Session checkpoints" -> listOf { it == "settings:backups" }
    "Commands" -> listOf { it == "slash:catalog" }
    "Notifications" -> listOf { it.startsWith("history-retention") }
    "Tasks" -> listOf { it == "crons" }
    "Spaces" -> listOf { it == "files" }
    "Projects" -> listOf { it == "native-projects" }
    "Kanban" -> listOf { it.startsWith("kanban:") }
    else -> emptyList()
}

/** True when [saved] (the screens held in memory) already carries a good answer for [page]. */
internal fun savedPageHasCopy(page: String, saved: Map<String, JSONObject>): Boolean =
    savedKeys(page).any { match -> saved.any { (key, value) -> match(key) && !value.has("_error") && !value.optBoolean("_loading") } }

/** The strip over every tab: hidden while a restored sign-in reconnects for the first time (the saved pages show quietly), shown once a read failed, while a Reconnect runs, or when Hermes stopped answering. */
internal fun offlineBannerShown(connected: Boolean, loading: Boolean, cantReach: Boolean, failedToConnect: Boolean): Boolean =
    cantReach || !connected && (!loading || failedToConnect)
