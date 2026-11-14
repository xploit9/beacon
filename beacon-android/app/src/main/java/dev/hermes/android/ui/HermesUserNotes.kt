package dev.hermes.android.ui

/**
 * What the owner sent, as the chat shows it. Hermes rewrites the stored user row of an attachment: it puts its own note in front of the typed words
 * (a vision description with the file's path for a photo, a "saved at" note for a document, audio, video or voice message), and an older core writes
 * "[Image attached at: <path>]" after them. None of that is the owner's text, and a path on the computer is never shown. This is the one cleaner every path
 * uses that turns a stored user row into something the owner sees: the transcript, the drawer and project previews, a steer's pairing, find, copy, share and quote.
 *
 * Only where Hermes puts a note is it cut: notes in front of the row (a run of them, each ended by its closing bracket), the old marker at the very end.
 * The same words anywhere else in a message are the owner's own (pasted from a log, quoted) and stay. A note cut short at the front (a list preview)
 * is dropped whole; a note that is complete but of a shape this app does not know is left as it is, so no message ever disappears.
 *
 * The templates are Hermes' own: gateway/run_inbound.py `_enrich_message_with_vision` and `_prepend_inbound_media_file_notes`,
 * gateway/run.py `_build_document_context_note`, gateway/run_inbound_transcription.py.
 */
data class AttachedMedia(val image: Boolean, val path: String, val name: String = "")
internal data class UserMessageParts(val text: String, val media: List<AttachedMedia>)

// Hermes' text-only projection of a photo turn ends with one "[screenshot]" line per image after the hint lines (tui_gateway/session_history.py _SCREENSHOT_PLACEHOLDER).
private val trailingPlaceholders = Regex("""(?:[ \t]*\r?\n[ \t]*\[screenshot])+\s*$""")
private val trailingMarker = Regex("""\s*\[(Image|File|Document|Audio|Video) attached at:[^\]\r\n]*(?:\](?:[ \t]*\[[^\]\r\n]*\])?)?\s*$""")
private val visionNote = Regex("""^\s*\[The user sent an image~ Here's what I can see:\s*(.*?)\]\s*\[If you need a closer look, use vision_analyze with image_url: ([^\r\n]*?) ~\]""", RegexOption.DOT_MATCHES_ALL)
private val unseenNote = Regex("""^\s*\[The user sent an image but (?:I couldn't quite see it this time \(>_<\) You can try looking at it yourself|something went wrong when I tried to look at it~ You can try examining it yourself) with vision_analyze using image_url: ([^\]\r\n]*)\]""")
// The name may hold brackets: it ends at the quote that the "saved at" sentence follows. The body runs to the bracket that ends the note (a path may hold brackets too).
private val fileNote = Regex("""^\s*\[The user sent (?:a text document|a document|an audio file attachment|a video attachment): '([^\r\n]*?)'\. (?=It is saved at|Its content has been included below)((?:(?!\n\n).)*?)\](?=\s|$)""", RegexOption.DOT_MATCHES_ALL)
private val voiceNote = Regex("""^\s*\[The user sent a voice message: ([^\]\r\n]*?)(?: \(duration: [^)\r\n]*\))?\]""")
private val bareVoiceNote = Regex("""^\s*\[The user sent a voice message but [^\]]*\]""")
private val savedAt = Regex("""(?:The file is also saved at|It is saved at): (.+?)(?:\. (?:Its|Read|To|If)\b.*)?$""", RegexOption.DOT_MATCHES_ALL)
private val unfinishedNote = Regex("""^\s*\[The user sent (?:an image|a text document|a document|an audio file attachment|a video attachment|a voice message)""")

/** True when [text] may hold one of those notes; the cleaner only runs its patterns on such text. */
internal fun mentionsHermesNote(text: String): Boolean = "[The user sent" in text || "attached at:" in text

private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

internal fun splitAttachedMedia(text: String): UserMessageParts {
    val media = mutableListOf<AttachedMedia>()
    var rest = text
    if (mentionsHermesNote(text)) {
        // The notes Hermes writes in front, one after another.
        while (true) rest = leadingNote(rest, media) ?: break
        // A note cut short at the front is no longer the owner's words either: drop it whole (a complete note of an unknown shape is kept).
        if (unfinishedNote.containsMatchIn(rest) && "\n\n" !in rest) rest = ""
        // The old marker, put after the words (and the placeholder lines Hermes' stored text adds after the markers, only when a marker comes before them).
        val withoutPlaceholders = rest.replace(trailingPlaceholders, "")
        if (trailingMarker.containsMatchIn(withoutPlaceholders)) rest = withoutPlaceholders
        val later = mutableListOf<AttachedMedia>()
        while (true) {
            val marker = trailingMarker.find(rest) ?: break
            val path = marker.value.substringAfter("attached at:").substringBefore(']').trim()
            if (path.isNotEmpty()) later += AttachedMedia(marker.groupValues[1] == "Image", path)
            rest = rest.substring(0, marker.range.first)
        }
        media += later.asReversed()
    }
    return UserMessageParts(rest.replace(Regex("[ \t]+(?=\n)"), "").replace(Regex("\n{3,}"), "\n\n").trim(), media)
}

/** The row without its first note (and that note's file added to [media]), or null when it does not start with one. */
private fun leadingNote(rest: String, media: MutableList<AttachedMedia>): String? {
    visionNote.find(rest)?.let { match -> match.groupValues[2].trim().takeIf { it.isNotEmpty() }?.let { media += AttachedMedia(true, it) }; return rest.substring(match.range.last + 1) }
    unseenNote.find(rest)?.let { match -> match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { media += AttachedMedia(true, it) }; return rest.substring(match.range.last + 1) }
    fileNote.find(rest)?.let { match ->
        val path = savedAt.find(match.groupValues[2])?.groupValues?.get(1)?.trim().orEmpty()
        media += AttachedMedia(false, path, match.groupValues[1])
        return rest.substring(match.range.last + 1)
    }
    voiceNote.find(rest)?.let { match -> match.groupValues[1].trim().takeIf { it.isNotEmpty() }?.let { media += AttachedMedia(false, it, fileName(it)) }; return rest.substring(match.range.last + 1) }
    bareVoiceNote.find(rest)?.let { match -> return rest.substring(match.range.last + 1) }
    return null
}
