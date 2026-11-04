package dev.hermes.android.voice

/**
 * Owner, 4 Nov 2026: after two Gmail tool results the live model said "I'll draft that email to Carlos…" three times in one turn,
 * against its instruction never to repeat a sentence. A sentence it already said since the owner last spoke is not said again:
 * while [repeating], the call drops its audio and leaves its words out of the captions. Words that turn out to differ come back.
 * Short sentences (under [minimum] letters) are never held back.
 */
internal class RepeatGuard(private val minimum: Int = 16) {
    private val said = mutableSetOf<String>()
    private val sentence = StringBuilder()
    var repeating = false
        private set

    private fun plain(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun matches(soFar: String) = said.any { it.startsWith(soFar) }

    /** The owner spoke: anything may be said again (the owner may have asked for it). */
    fun ownerSpoke() { said.clear(); sentence.setLength(0); repeating = false }

    /** The model's next words; returns the part to show (blank while it repeats itself). */
    fun words(text: String): String {
        val shown = StringBuilder()
        var rest = text
        while (rest.isNotEmpty()) {
            val end = rest.indexOfFirst { it in ".!?。！？؟" }
            val piece = if (end < 0) rest else rest.substring(0, end + 1)
            rest = if (end < 0) "" else rest.substring(end + 1)
            sentence.append(piece)
            val soFar = plain(sentence.toString())
            if (!repeating && soFar.length >= minimum && matches(soFar)) repeating = true
            else if (repeating && !matches(soFar)) repeating = false
            if (!repeating) shown.append(piece)
            if (end >= 0) {
                if (!repeating && soFar.length >= minimum) said += soFar
                sentence.setLength(0); repeating = false
            }
        }
        return shown.toString()
    }

    /** The model's turn ended or was interrupted: a sentence it left unfinished still counts as said. */
    fun turnEnded() {
        val soFar = plain(sentence.toString())
        if (!repeating && soFar.length >= minimum) said += soFar
        sentence.setLength(0); repeating = false
    }
}
