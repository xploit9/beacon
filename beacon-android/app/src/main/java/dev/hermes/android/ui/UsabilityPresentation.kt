package dev.hermes.android.ui

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

internal fun backgroundActivityStatus(activity: BackgroundActivitySummary): String {
    val counts = listOfNotNull(
    "${activity.agents} ${if (activity.agents == 1) "agent" else "agents"}".takeIf { activity.agents > 0 },
    "${activity.processes} ${if (activity.processes == 1) "process" else "processes"}".takeIf { activity.processes > 0 },
    "${activity.cronJobs} ${if (activity.cronJobs == 1) "cron job" else "cron jobs"}".takeIf { activity.cronJobs > 0 },
    "${activity.watches} ${if (activity.watches == 1) "watch" else "watches"}".takeIf { activity.watches > 0 },
    )
    return when {
        counts.isNotEmpty() && !activity.available -> counts.joinToString(" · ") + " · More may be active"
        counts.isNotEmpty() -> counts.joinToString(" · ")
        !activity.available -> "Background status unavailable"
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
internal fun mobileSlashCommands(commands: List<JSONObject>): List<JSONObject> = commands.filterNot { command ->
    val name = command.string("name").removePrefix("/").trim()
    name.equals("redraw", ignoreCase = true) || terminalOnlyCommand.containsMatchIn("$name ${command.string("description")}")
}

/** Ignore text field blur/disposal callbacks after its conversation stops accepting edits. */
internal fun acceptsComposerEdit(client: Any?, currentClient: Any?, scope: Any?, currentScope: Any?, callActiveHere: Boolean): Boolean =
    client === currentClient && scope == currentScope && !callActiveHere

internal val conversationActionGroups = linkedMapOf(
    "Conversation" to listOf("New conversation", "Rename", "Pin", "Archive", "Branch"),
    "Model and tools" to listOf("Model & provider", "Reasoning", "Toolsets"),
    "Context and replies" to listOf("Compress context", "Undo last turn", "Usage"),
    "Manage" to listOf("Delete conversation"),
)
