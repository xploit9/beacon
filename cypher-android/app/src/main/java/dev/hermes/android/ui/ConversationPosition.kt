package dev.hermes.android.ui

/** Seed the first measurement, so history never renders at the top before restoration. */
internal fun initialConversationPosition(ids: List<String>, anchorId: String, index: Int, offset: Int): Pair<Int, Int> {
    if (ids.isEmpty()) return 0 to 0
    if (anchorId.isBlank() && index == 0 && offset == 0) return Int.MAX_VALUE to 0
    return (ids.indexOf(anchorId).takeIf { it >= 0 } ?: index.coerceIn(0, ids.lastIndex)) to offset.coerceAtLeast(0)
}
