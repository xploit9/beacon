package dev.hermes.android.ui

import dev.hermes.android.core.string
import dev.hermes.android.data.ApiFailure
import org.json.JSONObject

internal fun profileDisplayName(profileId: String, profiles: List<JSONObject>): String =
    profiles.firstOrNull { it.string("name") == profileId }?.string("display_name")?.trim()?.takeIf(String::isNotBlank) ?: profileId
internal fun profileDisplayLabel(profileId: String, profiles: List<JSONObject>, loading: Boolean): String =
    if (loading) "Loading profile…" else profileDisplayName(profileId, profiles)


internal fun choiceDisplayLabel(value: String, labels: Map<String, String>, offered: Boolean): String =
    labels[value]?.takeIf(String::isNotBlank) ?: value.ifBlank { if (offered) "Default" else "Choose" }

internal fun chatModelLabel(conversationModel: String, profileDefault: String): String = conversationModel.ifBlank { profileDefault }.ifBlank { "Model" }
internal fun chatReasoningLabel(conversationEffort: String, sessionEffort: String, profileDefault: String): String = conversationEffort.ifBlank { sessionEffort }.ifBlank { profileDefault }.ifBlank { "Effort" }

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
