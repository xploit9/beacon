package dev.hermes.android.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.UnfoldLess
import androidx.compose.ui.graphics.vector.ImageVector
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.ApiFailure
import org.json.JSONObject

internal fun profileDisplayName(profileId: String, profiles: List<JSONObject>): String =
    profiles.firstOrNull { it.string("name") == profileId }?.string("display_name")?.trim()?.takeIf(String::isNotBlank) ?: profileId
internal fun profileDisplayLabel(profileId: String, profiles: List<JSONObject>, loading: Boolean): String =
    if (loading) "Loading profile…" else profileDisplayName(profileId, profiles)


internal fun choiceDisplayLabel(value: String, labels: Map<String, String>, offered: Boolean): String =
    labels[value]?.takeIf(String::isNotBlank) ?: value.ifBlank { if (offered) "Default" else "Choose" }

internal fun chatModelLabel(conversationModel: String, profileDefault: String): String = conversationModel.ifBlank { profileDefault }
internal fun chatReasoningLabel(conversationEffort: String, sessionEffort: String, profileDefault: String): String = conversationEffort.ifBlank { sessionEffort }.ifBlank { profileDefault }

internal data class HistoryAttachmentReference(val kind: String, val name: String)
internal data class HistoryAttachmentPresentation(val text: String, val attachments: List<HistoryAttachmentReference>)

private val historyAttachmentReference = Regex("""(^|[ \t])@(image|file):(`[^`\r\n]+`|"[^"\r\n]+"|'[^'\r\n]+'|[^ \t\r\n]+)""")
private val attachmentUuidPrefix = Regex("""(?i)^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{32})-""")

internal fun historyAttachmentPresentation(source: String): HistoryAttachmentPresentation {
    val normalized = source.replace("\r\n", "\n").replace('\r', '\n')
    val attachments = mutableListOf<HistoryAttachmentReference>()
    val text = normalized.lineSequence().map { line ->
        // Native Hermes quotes any path with spaces, so an unquoted reference ends at the first space.
        historyAttachmentReference.findAll(line).forEach { match ->
            val path = match.groupValues[3].trim().removeSurrounding("`").removeSurrounding("\"").removeSurrounding("'")
            val filename = path.substringAfterLast('/').substringAfterLast('\\').replaceFirst(attachmentUuidPrefix, "")
            attachments += HistoryAttachmentReference(match.groupValues[2], filename.ifBlank { if (match.groupValues[2] == "image") "Image" else "File" })
        }
        historyAttachmentReference.replace(line) { it.groupValues[1] }
    }.joinToString("\n")
        .replace(Regex("[ \\t]+(?=\\n)"), "")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
    return HistoryAttachmentPresentation(text, attachments)
}

internal fun safeHistoryMessagePresentation(role: String, text: String): HistoryAttachmentPresentation =
    if (role == "assistant" && text.trimStart().startsWith("Operation interrupted:")) HistoryAttachmentPresentation("", emptyList())
    else if (role == "user") historyAttachmentPresentation(text) else HistoryAttachmentPresentation(text, emptyList())

internal fun safeHistoryMessageText(role: String, text: String): String = safeHistoryMessagePresentation(role, text).text

internal fun stoppedMessageMarker(role: String, text: String, interrupted: Boolean): String? =
    "Stopped".takeIf { role == "assistant" && (interrupted || text.trimStart().startsWith("Operation interrupted:")) }

internal fun imageMimeType(value: String): Boolean = value.substringBefore(';').trim().startsWith("image/", ignoreCase = true)

internal data class BackgroundActivitySummary(
    val agents: Int = 0,
    val processes: Int = 0,
    val available: Boolean = true,
    val items: List<BackgroundActivityItem> = emptyList(),
) {
    val active get() = agents > 0 || processes > 0 || items.any { it.status == "Status unknown" }
    val working get() = agents > 0 || processes > 0
}

/** The background line's fixed texts, which reach uiText through [backgroundActivityStatus]. Each count sits inside its own template as {0}. */
internal val BackgroundActivityStrings = listOf(
    "1 agent", "{0} agents", "1 process", "{0} processes",
    "More may be active", "Background status unavailable",
)

/** [text] translates one of [BackgroundActivityStrings] whole. */
internal fun backgroundActivityStatus(activity: BackgroundActivitySummary, text: (String) -> String = { it }): String {
    fun count(n: Int, one: String, many: String) = if (n > 0) text(if (n == 1) one else many).replace("{0}", "$n") else null
    val counts = listOfNotNull(
        count(activity.agents, "1 agent", "{0} agents"),
        count(activity.processes, "1 process", "{0} processes"),
    )
    return when {
        counts.isNotEmpty() && !activity.available -> counts.joinToString(" · ") + " · " + text("More may be active")
        counts.isNotEmpty() -> counts.joinToString(" · ")
        !activity.available -> text("Background status unavailable")
        else -> ""
    }
}

internal fun sessionScopedSubagentCount(sessionRoster: JSONObject): Int =
    sessionRoster.rows("subagents").filter { it.string("status") in setOf("running", "queued") }
        .map { it.string("subagent_id") }.filter(String::isNotBlank).distinct().size

internal data class BackgroundActivityItem(val id: String, val kind: String, val title: String, val status: String, val elapsedSeconds: Long? = null) {
    val running get() = status in setOf("Running", "Queued")
}

/** Both legacy runtimes and canonical owners publish the stored key in this read-only inventory. */
internal fun backgroundActivityRuntime(inventory: JSONObject?, storedSessionId: String): String? {
    if (storedSessionId.isBlank() || inventory?.optJSONArray("sessions") == null) return null
    val matches = inventory.rows("sessions").filter { it.opt("session_key") == storedSessionId }
    val row = matches.singleOrNull() ?: return null
    val runtime = (row.opt("id") as? String)?.takeIf(String::isNotBlank) ?: return null
    if (inventory.rows("sessions").count { it.opt("id") == runtime } != 1) return null
    return runtime
}

internal fun conversationBackgroundActivity(roster: JSONObject?, processes: JSONObject?, nowSeconds: Long = System.currentTimeMillis() / 1000): BackgroundActivitySummary {
    fun elapsed(row: JSONObject, startField: String, endField: String, running: Boolean): Long? {
        val start = (row.opt(startField) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return null
        val end = if (running) nowSeconds.toDouble() else (row.opt(endField) as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return null
        return (end - start).takeIf { it >= 0 && it < Long.MAX_VALUE.toDouble() }?.toLong()
    }
    fun status(value: String) = when (value) {
        "queued" -> "Queued"
        "running" -> "Running"
        "completed" -> "Completed"
        "failed", "error", "timeout" -> "Failed"
        "interrupted", "cancelled", "killed" -> "Cancelled"
        else -> "Status unknown"
    }
    val agents = roster?.rows("subagents").orEmpty().mapNotNull { row ->
        val id = row.string("subagent_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val state = status(row.string("status"))
        BackgroundActivityItem("agent:$id", "Background agent", row.string("goal"), state, elapsed(row, "started_at", "completed_at", state in setOf("Running", "Queued")))
    }.distinctBy { it.id }
    val delegations = roster?.rows("delegations").orEmpty().mapNotNull { row ->
        val id = row.string("delegation_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val state = status(row.string("status"))
        BackgroundActivityItem("delegation:$id:${row.optInt("task_index")}", "Delegation", row.string("goal"), state, elapsed(row, "dispatched_at", "completed_at", state in setOf("Running", "Queued")))
    }.distinctBy { it.id }
    val jobs = processes?.rows("processes").orEmpty().mapNotNull { row ->
        val id = row.string("session_id").takeIf(String::isNotBlank) ?: return@mapNotNull null
        val state = if (row.string("status") == "exited") when {
            row.string("completion_reason") in setOf("killed", "cancelled", "interrupted", "stopped") -> "Cancelled"
            row.opt("exit_code") is Number -> if (row.optInt("exit_code") == 0) "Completed" else "Failed"
            else -> "Status unknown"
        } else status(row.string("status"))
        val duration = elapsed(row, "started_epoch", "exited_at", state in setOf("Running", "Queued"))
            ?: (row.opt("uptime_seconds") as? Number)?.toLong()?.takeIf { it >= 0 }
        BackgroundActivityItem("process:$id", "Background process", row.string("command"), state, duration)
    }.distinctBy { it.id }
    val items = agents + delegations + jobs
    val valid = roster?.optJSONArray("subagents") != null && processes?.optJSONArray("processes") != null && items.none { it.status == "Status unknown" } &&
        agents.size == roster.getJSONArray("subagents").length() && jobs.size == processes.getJSONArray("processes").length() &&
        delegations.size == (roster.optJSONArray("delegations")?.length() ?: 0)
    return BackgroundActivitySummary(agents = sessionScopedSubagentCount(roster ?: JSONObject()) + delegations.count { it.running }, processes = jobs.count { it.running }, available = valid, items = items)
}

/** A missing row proves only that it is no longer listed; a failed read proves no completion. */
internal fun reconcileBackgroundActivity(previous: BackgroundActivitySummary, current: BackgroundActivitySummary): BackgroundActivitySummary {
    val knownIds = current.items.mapTo(mutableSetOf()) { it.id }
    val previousItems = previous.items.filter { it.id !in knownIds }.map { item ->
        if (item.running || item.status == "Status unknown") item.copy(status = if (current.available) "No longer listed" else "Status unknown") else item
    }
    return current.copy(items = current.items + previousItems.takeLast(20))
}

internal fun conversationActionLabel(action: String, pinned: Boolean, archived: Boolean): String = when {
    action == "Pin" && pinned -> "Unpin"
    action == "Archive" && archived -> "Unarchive"
    // The sheet it opens is called Tools; "Toolsets" stays the action's id.
    action == "Toolsets" -> "Tools"
    // The id the menu dispatches on stays "Compress context"; the row says what it does for the owner.
    action == "Compress context" -> "Shorten history"
    else -> action
}

internal fun conversationMenuTitle(label: String): String = label.replaceFirstChar(Char::uppercase)

internal fun branchSessionId(response: JSONObject): String =
    response.optJSONObject("session")?.string("session_id")?.takeIf(String::isNotBlank) ?: response.string("session_id").takeIf(String::isNotBlank).orEmpty()

internal fun fileDownloadError(error: Throwable): String = when {
    error is IllegalArgumentException && error.message?.contains("Download exceeds permitted size") == true -> "This file is larger than the 256 MB download limit."
    error is ApiFailure && error.status == 404 -> "This file is no longer available."
    else -> "Couldn’t download this file. Check your connection and try again."
}

private val terminalOnlyCommand = Regex("(?:\\bcli|\\bterminal)[ -]only\\b|terminal drift|terminal ui", RegexOption.IGNORE_CASE)
// shortcut: Hermes exposes no per-client command capability, so this omits explicit terminal-only cues until it does.
/** The commands the phone offers. Each keeps its name; its description is what a person reads: [slashCommandText]. */
internal fun mobileSlashCommands(commands: List<JSONObject>): List<JSONObject> = commands.filterNot { command ->
    val name = command.string("name").removePrefix("/").trim()
    name.equals("redraw", ignoreCase = true) || terminalOnlyCommand.containsMatchIn("$name ${command.string("description")}")
}.map { command ->
    val text = slashCommandText(command.string("name"), command.string("description"))
    if (text == command.string("description")) command else JSONObject(command.toString()).put("description", text)
}

/**
 * Short user text for every built-in command of Hermes' registry that its command catalog offers to a client (the catalog leaves out the
 * gateway-only ones: start, topic, discard, pause, approve, deny, sethome, commands, restart, platform). Only commands the owner wrote
 * (quick commands, plugin commands, skills) keep Hermes' own words.
 */
private val slashCommandTexts = mapOf(
    "new" to "New chat", "reset" to "New chat", "clear" to "Start fresh", "history" to "Show history", "save" to "Export chat",
    "retry" to "Try again", "undo" to "Undo last turn", "rewind" to "Return to an earlier prompt", "title" to "Rename chat", "branch" to "Branch chat", "fork" to "Branch chat",
    "compress" to "Shorten history", "stop" to "Stop reply", "status" to "Show status", "model" to "Change model", "usage" to "Show usage",
    "help" to "Show commands", "queue" to "Queue a message", "background" to "Run in background", "bg" to "Run in background", "reasoning" to "Set reasoning level",
    "skills" to "Browse skills", "tools" to "Choose tools",
    "prompt" to "Write a prompt in your editor", "handoff" to "Hand off to a messaging app", "worktree" to "Manage git worktrees",
    "rollback" to "Restore a saved checkpoint", "snapshot" to "Save or restore a snapshot", "export" to "Export a profile", "import" to "Import a profile",
    "btw" to "Ask a side question", "agents" to "Show active agents", "journey" to "Open the learning timeline", "steer" to "Guide the reply in progress",
    "goal" to "Set a standing goal", "heartbeat" to "Repeat a prompt when idle", "refine" to "Save lessons from this chat", "review" to "Get an independent review",
    "loop" to "Repeat a prompt on a schedule", "plan" to "Write a plan without running it", "moa" to "Ask several models at once", "subgoal" to "Refine the current goal",
    "egress" to "Show network proxy status", "context" to "Show context usage", "whoami" to "Show your command access", "profile" to "Show the active profile",
    "resume" to "Resume a saved chat", "sessions" to "Browse past chats", "config" to "Show settings", "codex-runtime" to "Switch the Codex runtime",
    "personality" to "Choose a personality", "statusbar" to "Show or hide the status bar", "battery" to "Show or hide the battery meter", "timestamps" to "Show or hide timestamps",
    "diff" to "Show code changes", "verbose" to "Change tool progress detail", "focus" to "Switch focus view", "footer" to "Show or hide the reply footer",
    "yolo" to "Skip approval prompts", "approvals" to "Set the approval mode", "fast" to "Turn fast mode on or off", "skin" to "Change the theme",
    "indicator" to "Change the busy indicator", "voice" to "Turn voice mode on or off", "wake" to "Turn the wake word on or off", "busy" to "Set how messages act while busy",
    "autoprune" to "Clean up old chats automatically", "toolsets" to "List available tools", "memory" to "Review memory changes", "bundles" to "List skill bundles",
    "pet" to "Show or hide your pet", "hatch" to "Create a new pet", "learn" to "Learn a skill from anything", "init" to "Create project instructions",
    "cron" to "Manage schedules", "suggestions" to "Review suggested automations", "blueprint" to "Set up an automation", "curator" to "Tidy up skills",
    "kanban" to "Open the task board", "reload" to "Reload environment settings", "reload-mcp" to "Reload MCP servers", "reload-skills" to "Reload skills",
    "browser" to "Connect your browser", "plugins" to "List installed plugins", "palette" to "Open the command palette", "subscription" to "Manage your plan",
    "login" to "Sign in to your Nous account", "topup" to "Show balance and billing", "insights" to "Show usage insights", "platforms" to "Show messaging platforms",
    "copy" to "Copy the last reply", "paste" to "Attach a clipboard image", "image" to "Attach an image", "update" to "Update Hermes",
    "version" to "Show the Hermes version", "debug" to "Share a debug report", "quit" to "Exit", "density" to "Switch compact view",
    "logs" to "Show recent logs", "mouse" to "Set mouse tracking",
)

/** The fixed texts the command rows show through uiText: each short text above. */
internal val SlashCommandStrings: List<String> = slashCommandTexts.values.distinct()

private val parenthetical = Regex("""\s*\([^()]*\)""")
private val openParenthetical = Regex("""\s*\([^)]*$""")
private val whitespaceRun = Regex("""\s+""")

/** What a command's row says: a short text for a common command, else Hermes' description without "(usage: ...)" and other parenthetical mechanics. */
internal fun slashCommandText(name: String, description: String): String {
    slashCommandTexts[name.removePrefix("/").trim().lowercase()]?.let { return it }
    var text = description
    // Nested or repeated groups come off from the inside out; a cut-off group at the end goes too.
    while (parenthetical.containsMatchIn(text)) text = parenthetical.replace(text, "")
    text = whitespaceRun.replace(openParenthetical.replace(text, ""), " ").trim().trimEnd('-', ':', ';', ',', '…').trim()
    return text.ifBlank { description.trim() }
}

/** The glyph in front of a command row; a command with no glyph of its own gets the terminal mark. */
internal fun slashCommandIcon(name: String): ImageVector = when (name.removePrefix("/").trim().lowercase()) {
    "new", "reset" -> Icons.Rounded.Add
    "clear" -> Icons.Rounded.ClearAll
    "history" -> Icons.Rounded.History
    "save" -> Icons.Rounded.FileDownload
    "retry" -> Icons.Rounded.Refresh
    "undo" -> Icons.AutoMirrored.Rounded.Undo
    "title" -> Icons.Rounded.Edit
    "branch", "fork" -> Icons.AutoMirrored.Rounded.CallSplit
    "compress" -> Icons.Rounded.UnfoldLess
    "stop" -> Icons.Rounded.Stop
    "status", "help" -> Icons.Rounded.Info
    "model" -> Icons.Rounded.Memory
    "usage" -> Icons.Rounded.DataUsage
    "queue" -> Icons.AutoMirrored.Rounded.PlaylistAdd
    "background" -> Icons.Rounded.Layers
    "reasoning" -> Icons.Rounded.Psychology
    "skills" -> Icons.Rounded.AutoAwesome
    "tools", "toolsets" -> Icons.Rounded.Build
    "voice" -> Icons.Rounded.RecordVoiceOver
    "profile" -> Icons.Rounded.AccountCircle
    "cron", "schedule" -> Icons.Rounded.Schedule
    else -> Icons.Rounded.Terminal
}

/** Ignore text field blur/disposal callbacks after its conversation stops accepting edits. */
internal fun acceptsComposerEdit(client: Any?, currentClient: Any?, scope: Any?, currentScope: Any?, callActiveHere: Boolean): Boolean =
    client === currentClient && scope == currentScope && !callActiveHere

/** The title of the group that has no heading: the destructive one, drawn last behind a divider and in red. */
internal const val ConversationDestructiveGroup = ""

/** The chat menu's daily items under one heading, then the destructive group without a heading; model and reasoning live on the composer, and the rarer actions sit under More. */
internal val conversationActionGroups = linkedMapOf(
    "Conversation" to listOf("New conversation", "Rename", "Pin", "Archive", "Branch"),
    ConversationDestructiveGroup to listOf("Delete conversation"),
)
/** The actions to draw in red, last in the menu. */
internal val conversationDestructiveActions: List<String> get() = conversationActionGroups[ConversationDestructiveGroup].orEmpty()
internal val conversationMoreActions = listOf("Toolsets", "Compress context", "Undo last turn", "Usage")
/** Rows for developers: shown under More in debug builds only. */
internal val conversationDebugActions = listOf("Copy conversation ID")

/** What the More submenu lists: the rarer actions, plus the developer rows when [debug]. */
internal fun conversationMoreMenu(debug: Boolean = BuildConfig.DEBUG): List<String> = conversationMoreActions + if (debug) conversationDebugActions else emptyList()

/** The chat menu's and message marker's fixed texts, which reach uiText through variables. */
internal val ConversationStrings = conversationActionGroups.keys.filter(String::isNotBlank) + conversationActionGroups.values.flatten() + conversationMoreActions + conversationDebugActions +
    listOf("Unpin", "Unarchive", "Tools", "Shorten history", "Stopped") + SlashCommandStrings
