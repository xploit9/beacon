package dev.hermes.android.ui


/** Each settings column needs readable text after icons, padding and text scaling. */
internal fun settingsUseColumns(contentWidthDp: Float, fontScale: Float): Boolean =
    contentWidthDp >= 52f + 720f * fontScale.coerceAtLeast(1f)

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
