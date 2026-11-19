package dev.hermes.android.voice

/**
 * Owner, 4 Nov 2026, and device round 2: the live model said a sentence again in a later turn, word for word. A sentence the model said in
 * an earlier turn since the owner last spoke is not written again: while the turn's sentence so far is the start of such a sentence, its
 * caption pieces are held; when it turns out to be a repeat covering most of the earlier sentence ([cover]) they are dropped, and when it
 * diverges, ends short or is cut off every held piece is given back, in order (REVIEW-E4 P2-2: "You have a meeting at 2." after "You have a
 * meeting at 9." lost its start; REVIEW-E5-2 P3-2: a sentence cut off by the owner or by the session's end may have been heard). Captions
 * and the chat only: the audio is never held, delayed or dropped (REVIEW-E5 P2-4, Root's decision: Gemini's audio runs ahead of its
 * transcript, so an audio hold is late by design and dropped speech that was no repeat; the call's releases are what keeps the model from
 * saying an answer again). Sentences of the same turn are never compared, short sentences (under [minimum] letters) are never dropped, and
 * nothing is keyed to a language: letters and digits are compared, and sentences end at the enders below (with the Devanagari danda and the
 * Armenian full stop).
 */
internal class RepeatGuard(private val minimum: Int = 16, private val cover: Double = 0.8) {
    private val earlier = mutableSetOf<String>()
    /** This turn's finished sentences, each with where it ends in the turn's words (a cut keeps only those that played). */
    private val thisTurn = mutableListOf<Pair<String, Int>>()
    private var turnRaw = 0
    private val sentence = StringBuilder()
    private val held = StringBuilder()
    /** True while the turn's sentence so far is the start of one said in an earlier turn: its caption pieces are held. */
    var repeating = false
        private set

    private fun plain(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun startsAnEarlier(soFar: String) = soFar.isNotEmpty() && earlier.any { it.startsWith(soFar) }
    /** A repeat: the sentence covers most of an earlier one (it may be cut a little short). */
    private fun repeats(soFar: String) = soFar.length >= minimum && earlier.any { it.startsWith(soFar) && soFar.length >= it.length * cover }

    /** The owner spoke: anything may be said again; the caption pieces that were held are given back. */
    fun ownerSpoke(): String {
        val back = held.toString()
        earlier.clear(); thisTurn.clear(); turnRaw = 0; sentence.setLength(0); held.setLength(0); repeating = false
        return back
    }

    /** The model's next caption piece: what of it to show now (with any held pieces given back before it). */
    fun words(text: String): String {
        val shown = StringBuilder()
        var rest = text
        while (rest.isNotEmpty()) {
            val end = rest.indexOfFirst { it in ENDERS }
            val piece = if (end < 0) rest else rest.substring(0, end + 1)
            rest = if (end < 0) "" else rest.substring(end + 1)
            sentence.append(piece); turnRaw += piece.length
            val soFar = plain(sentence.toString())
            if (startsAnEarlier(soFar) && !(end >= 0 && !repeats(soFar))) {
                // Still the start of an earlier sentence (or a finished repeat): held.
                // Earlier pieces of this sentence were only spaces or marks (a longer match implies every shorter one): the hold starts here.
                if (!repeating) { repeating = true; held.setLength(0) }
                held.append(piece)
            } else if (repeating) {
                // It diverged, or ended short: everything held comes back, in order.
                shown.append(held).append(piece); held.setLength(0); repeating = false
            } else shown.append(piece)
            if (end >= 0) {
                if (repeating) { held.setLength(0); repeating = false }
                else if (soFar.isNotEmpty()) thisTurn += soFar to turnRaw
                sentence.setLength(0)
            }
        }
        return shown.toString()
    }

    /**
     * The model's turn ended, or was cut off (the owner spoke over it, the session ended): an unfinished sentence that repeats is dropped,
     * otherwise given back (the caption is then cut to what played). Its sentences count from now on; a cut turn's only as far as its audio
     * played ([share] of its words, device round 4, D2), so a sentence that never played may be said again.
     */
    fun turnEnded(share: Double = 1.0): String {
        val soFar = plain(sentence.toString())
        val dropped = repeating && repeats(soFar)
        val back = if (repeating && !dropped) held.toString() else ""
        val heard = if (share >= 1.0) Int.MAX_VALUE else (turnRaw * share.coerceAtLeast(0.0)).toInt()
        for ((said, end) in thisTurn) if (end <= heard) earlier += said
        if (!dropped && soFar.isNotEmpty() && turnRaw <= heard) earlier += soFar
        thisTurn.clear(); turnRaw = 0; sentence.setLength(0); held.setLength(0); repeating = false
        return back
    }

    private companion object {
        const val ENDERS = ".!?。！？؟।։"
    }
}
