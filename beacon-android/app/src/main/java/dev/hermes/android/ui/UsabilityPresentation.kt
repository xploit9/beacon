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
    val cronJobs: Int = 0,
    val watches: Int = 0,
    val available: Boolean = true,
) {
    val active get() = agents > 0 || processes > 0 || cronJobs > 0 || watches > 0
}

/** The background line's fixed texts, which reach uiText through [backgroundActivityStatus]. Each count sits inside its own template as {0}. */
internal val BackgroundActivityStrings = listOf(
    "1 agent", "{0} agents", "1 process", "{0} processes", "1 schedule running", "{0} schedules running", "1 watch", "{0} watches",
    "More may be active", "Background status unavailable",
)

/** [text] translates one of [BackgroundActivityStrings] whole. */
internal fun backgroundActivityStatus(activity: BackgroundActivitySummary, text: (String) -> String = { it }): String {
    fun count(n: Int, one: String, many: String) = if (n > 0) text(if (n == 1) one else many).replace("{0}", "$n") else null
    val counts = listOfNotNull(
        count(activity.agents, "1 agent", "{0} agents"),
        count(activity.processes, "1 process", "{0} processes"),
        count(activity.cronJobs, "1 schedule running", "{0} schedules running"),
        count(activity.watches, "1 watch", "{0} watches"),
    )
    return when {
        counts.isNotEmpty() && !activity.available -> counts.joinToString(" · ") + " · " + text("More may be active")
        counts.isNotEmpty() -> counts.joinToString(" · ")
        !activity.available -> text("Background status unavailable")
        else -> ""
    }
}

internal fun sessionScopedSubagentCount(sessionRoster: JSONObject, profileStatus: JSONObject?): Int {
    val sessionIds = sessionRoster.rows("subagents").mapNotNull { it.string("subagent_id").takeIf(String::isNotBlank) }.toSet()
    if (profileStatus == null) return sessionIds.size
    val activeIds = profileStatus.rows("active").mapNotNull { it.string("subagent_id").takeIf(String::isNotBlank) }.toSet()
    return sessionIds.intersect(activeIds).size
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

/** Short user text for the commands people use most; the other commands keep Hermes' own words. */
private val slashCommandTexts = mapOf(
    "new" to "New chat", "reset" to "New chat", "clear" to "Start fresh", "history" to "Show history", "save" to "Export chat",
    "retry" to "Try again", "undo" to "Undo last turn", "title" to "Rename chat", "branch" to "Branch chat", "fork" to "Branch chat",
    "compress" to "Shorten history", "stop" to "Stop reply", "status" to "Show status", "model" to "Change model", "usage" to "Show usage",
    "help" to "Show commands", "queue" to "Queue a message", "background" to "Run in background", "reasoning" to "Set reasoning level",
    "skills" to "Browse skills", "tools" to "Choose tools",
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
