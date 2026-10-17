package dev.hermes.android.ui

/** The native adapter is closed: identity alone never enables a retired WebUI action. */
internal fun nativeConversationAction(action: String): Boolean = action in setOf(
    "New conversation", "Rename", "Archive", "Branch", "Model & provider", "Reasoning", "Toolsets", "Compress context", "Undo last turn", "Usage", "Delete conversation"
)

internal fun tableColumnWidths(rows: List<List<String>>): List<Int> =
    (0 until (rows.maxOfOrNull { it.size } ?: 0)).map { column ->
        (rows.take(200).maxOfOrNull { it.getOrNull(column).orEmpty().lineSequence().maxOfOrNull(String::length) ?: 0 }?.times(7)?.plus(24) ?: 100).coerceIn(100, 280)
    }

internal fun slashMatches(name: String, description: String, draft: String): Boolean =
    draft.startsWith('/') && !draft.substringAfter('/').contains(' ') &&
        (name.removePrefix("/").startsWith(draft.removePrefix("/"), true) || draft.removePrefix("/").length >= 3 && description.contains(draft.removePrefix("/"), true))

internal fun archivedCallLabel(final: Boolean, interrupted: Boolean, active: Boolean): String = when {
    !final && active -> "Live call transcript"
    !final -> "Unfinished call transcript"
    interrupted -> "Interrupted call transcript"
    else -> "Call transcript"
}

internal fun nativeSlashName(draft: String, names: List<String>): String? {
    if (!draft.startsWith('/')) return null
    val name = draft.substringBefore(' ').removePrefix("/")
    return names.firstOrNull { it.removePrefix("/").equals(name, true) }?.removePrefix("/")
}
