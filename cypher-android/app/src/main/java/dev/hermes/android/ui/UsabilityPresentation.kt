package dev.hermes.android.ui


internal fun statusChipUnsupportedDescription(androidVersion: String, sdkInt: Int): String = when {
    sdkInt >= 36 -> "Status chips aren't supported on this Android $androidVersion build. Calls and requests use standard notifications."
    else -> "Status chips require Android 16 or later. Calls and requests use standard notifications."
}

internal fun choiceDisplayLabel(value: String, labels: Map<String, String>, offered: Boolean): String =
    labels[value]?.takeIf(String::isNotBlank) ?: value.ifBlank { if (offered) "Default" else "Choose" }

internal fun conversationActionLabel(action: String, pinned: Boolean, archived: Boolean): String = when {
    action == "Pin" && pinned -> "Unpin"
    action == "Archive" && archived -> "Unarchive"
    else -> action
}

/** Ignore text field blur/disposal callbacks after its conversation stops accepting edits. */
internal fun acceptsComposerEdit(client: Any?, currentClient: Any?, scope: Any?, currentScope: Any?, callActiveHere: Boolean): Boolean =
    client === currentClient && scope == currentScope && !callActiveHere

internal val conversationActionGroups = linkedMapOf(
    "Conversation" to listOf("New conversation", "Rename", "Archive", "Branch"),
    "Model and tools" to listOf("Model & provider", "Reasoning", "Toolsets"),
    "Context and replies" to listOf("Compress context", "Undo last turn", "Usage"),
    "Manage" to listOf("Delete conversation"),
)
