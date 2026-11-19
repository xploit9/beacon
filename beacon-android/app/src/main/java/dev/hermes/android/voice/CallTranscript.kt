package dev.hermes.android.voice

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.core.Scope
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val speechDiagnosticTurns = setOf(
    "speech-ready", "speech-close", "speech-transport", "speech-provider-error", "speech-send",
    "speech-protocol", "speech-status", "speech-tool-attempt", "speech-interrupted", "speech-timeout",
    "speech-invalid-audio", "speech-invalid-transcript", "speech-missing-audio", "speech-missing-transcript",
    "speech-transcript-mismatch",
)

internal fun directDiagnosticTurnId(value: JSONObject): String =
    value.string("turn_id").takeIf { value.string("stream") == "assistant" && it in speechDiagnosticTurns } ?: "connection"

internal fun isSpeechFailureDiagnostic(value: JSONObject): Boolean =
    value.string("type") == "media_diagnostic" && directDiagnosticTurnId(value) !in setOf("connection", "speech-ready")

data class CallTranscript(val eventId: String, val role: String, val text: String, val final: Boolean = true, val messageId: String = "", val interrupted: Boolean = false, val at: Long = 0L, val turnId: String = "") {
    companion object {
        fun from(value: JSONObject, sessionId: String, callId: String): CallTranscript? {
            val role = value.string("role")
            val id = value.string("event_id")
            val prefix = "$callId:$role:"
            if (role !in setOf("user", "assistant") || callId.isBlank() || sessionId.isBlank() || id.length > 256 || !id.startsWith(prefix) || id.removePrefix(prefix).isBlank() || value.string("session_id") != sessionId) return null
            return CallTranscript(id, role, value.string("text", value.string("transcript")), value.optBoolean("final", true), value.string("message_id"), value.optBoolean("interrupted"), dev.hermes.android.core.recordedMessageTime(value.opt("created_at")), value.string("turn_id"))
        }
    }
}

internal fun directCaption(value: JSONObject, callId: String, sessionId: String, turn: Long, text: String): JSONObject =
    value.put("event_id", "$callId:${value.string("role")}:$turn").put("session_id", sessionId).put("turn_id", turn.toString()).put("text", text)

internal fun CallState.withTranscript(value: JSONObject, sessionId: String, callId: String): CallState {
    val event = CallTranscript.from(value, sessionId, callId)
    // A withdrawn caption (a cut where nothing of the reply played) leaves the screen: no final comes for it.
    if (event != null && value.optBoolean(WITHDRAWN)) return transcripts.filterNot { it.eventId == event.eventId && !it.final }
        .let { kept -> if (kept.size == transcripts.size) this else copy(transcripts = kept) }
    // Older final events lacked session/final fields; only their scalar call-stage text is compatible.
    if (event == null && (value.has("session_id") || !value.optBoolean("final", true))) return this
    val next = if (event == null) transcripts else transcripts.acceptTranscript(event)
    if (event != null && next === transcripts) return this
    return copy(transcript = if (event != null && event.interrupted && !event.final) "" else value.string("text", value.string("transcript")), transcripts = next)
}

internal fun orderCallTranscriptLines(lines: List<CallTranscript>): List<CallTranscript> {
    if (lines.isEmpty()) return emptyList()
    val numericTurns = lines.all { it.turnId.toLongOrNull() != null }
    return lines.withIndex().sortedWith(compareBy<IndexedValue<CallTranscript>> {
        if (numericTurns) it.value.turnId.toLong() else it.value.at.takeIf { at -> at > 0 } ?: Long.MAX_VALUE
    }.thenBy { if (it.value.role == "user") 0 else 1 }.thenBy { it.index }).map { it.value }
}

internal fun visibleCallTranscriptLines(lines: List<CallTranscript>, expanded: Boolean): List<CallTranscript> {
    val ordered = orderCallTranscriptLines(lines)
    return if (expanded) ordered else ordered.takeLast(4)
}

/**
 * Returns a title only for a genuinely untitled conversation.
 *
 * Hermes keeps one title per chat (hermes_state_titles.py) and refuses a repeat: its canonical rename (session.mutate) raises, the gateway logs a dispatch crash and the chat stays
 * untitled. So the title is made to differ from every title this phone lists ([takenTitles]), the same way Hermes numbers its own automatic titles ("name #2"). A call with no spoken
 * request carries the time it started, so two such calls are told apart in History. [callWith] is the phrase in the phone's language with the assistant's current name
 * ("Call with Beacon", "Anruf mit Mira"); [startedLabel] writes the start in the app's own date and clock style for the phone's locale.
 */
internal fun deriveCallEndTitle(
    currentTitle: String, transcripts: List<CallTranscript>, assistantName: String,
    takenTitles: Collection<String> = emptyList(), startedAt: Long = 0, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault(),
    callWith: String = "Call with ${assistantName.trim().ifBlank { "Beacon" }}",
    startedLabel: (Long) -> String = { DateTimeFormatter.ofPattern("d MMM, HH:mm", locale).withZone(zone).format(Instant.ofEpochMilli(it)) },
): String? {
    if (currentTitle.isNotBlank() && !currentTitle.trim().equals("New conversation", ignoreCase = true)) return null
    val firstRequest = orderCallTranscriptLines(transcripts).firstOrNull { it.role == "user" && !it.interrupted && it.text.isNotBlank() }
        ?.text?.trim()?.replace(Regex("\\s+"), " ")
    if (!firstRequest.isNullOrBlank()) {
        val characters = firstRequest.codePointCount(0, firstRequest.length).coerceAtMost(40)
        return uniqueCallTitle(firstRequest.substring(0, firstRequest.offsetByCodePoints(0, characters)).trimEnd(), takenTitles)
    }
    val base = callWith.trim().ifBlank { "Call with ${assistantName.trim().ifBlank { "Beacon" }}" }
    val stamp = if (startedAt > 0) startedLabel(startedAt).trim() else ""
    return uniqueCallTitle(if (stamp.isNotEmpty()) "$base, $stamp" else base, takenTitles)
}

/** [base], or "[base] #N" for the first N from 2 that no [taken] title holds (compared without case, as a name is read). */
internal fun uniqueCallTitle(base: String, taken: Collection<String>): String {
    val held = taken.map { it.trim().lowercase(Locale.ROOT) }.toSet()
    // Hermes refuses a title over 100 characters (MAX_TITLE_LENGTH): leave room for " #N".
    val name = base.take(CALL_TITLE_ROOM).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
    if (name.lowercase(Locale.ROOT) !in held) return name
    return generateSequence(2) { it + 1 }.map { "$name #$it" }.first { it.lowercase(Locale.ROOT) !in held }
}
private const val CALL_TITLE_ROOM = 92

/**
 * Names the call's chat, best effort: a rename Hermes refuses or that fails for any reason (no connection, a title taken by a chat this phone does not list) must never stop
 * the call's end from being rendered and saved. True when the title was set.
 */
internal suspend fun renameCallChat(title: String?, rename: suspend (String) -> Unit): Boolean {
    if (title == null) return false
    return try { rename(title); true } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
}

internal fun List<CallTranscript>.acceptTranscript(event: CallTranscript): List<CallTranscript> {
    val previous = firstOrNull { it.eventId == event.eventId }
    if ((previous?.final == true || previous?.interrupted == true) && !event.final) return this
    val next = event.copy(at = previous?.at?.takeIf { it > 0 } ?: event.at)
    // Room speech progress and canonical conversation items have different IDs.
    // A final item supersedes transient progress of that role; partials never persist.
    val retained = if (next.final) filterNot { it.role == next.role && !it.final && it.eventId != next.eventId } else this
    val updated = if (previous == null) retained + next else retained.map { if (it.eventId == next.eventId) next else it }
    if (updated.any { it.turnId.toLongOrNull() == null }) return updated
    return updated.groupBy { it.turnId.toLong() }.toSortedMap().values.flatMap { turn ->
        turn.sortedBy { if (it.role == "user") 0 else 1 }
    }
}

/** Saved call turns sit where they happened among text messages; untimed (live) turns stay at the end. */
internal fun interleaveCallMessages(messages: List<Message>, calls: List<Message>): List<Message> {
    val out = ArrayList<Message>(messages.size + calls.size); var next = 0
    for (message in messages) {
        while (next < calls.size && calls[next].recordedAt > 0 && message.recordedAt > 0 && calls[next].recordedAt < message.recordedAt) out += calls[next++]
        out += message
    }
    return out + calls.drop(next)
}

/** The database remains authoritative; these rows never enter chat raw data or draft caches. */
internal fun List<CallTranscript>.pendingTranscripts(messages: List<Message>, active: Boolean): List<CallTranscript> =
    filter { event -> event.text.isNotBlank() && !(event.interrupted&&!event.final) && (active || event.final) && messages.none { message ->
        message.id == event.eventId || event.messageId.isNotBlank() && message.id == event.messageId
    } }

internal fun CallState.transcriptsFor(scope: Scope?, messages: List<Message>): List<CallTranscript> =
    if (scope == null || conversationScope != scope) emptyList() else transcripts.pendingTranscripts(messages, active)

/** Commit the same final caption to live state and the archive; consuming it prevents duplicate completion. */
/** Grok and OpenAI flag every new user utterance; only a reply already under way is interrupted by it. */
internal fun interruptsReply(interrupted:Boolean,captions:Map<String,String>)=interrupted&&!captions["assistant"].isNullOrBlank()

internal fun finishDirectTranscriptTurn(callId:String,sessionId:String,turn:Long,captions:MutableMap<String,String>,atMs:Long,
    interrupted:Boolean=false,deliver:(JSONObject)->Unit):Long {
    if(captions.isEmpty()) return turn+1
    captions.entries.sortedBy { if (it.key == "user") 0 else 1 }.forEach { (role,text) ->
        // A blank caption has no line (a cut where nothing of the reply played, [cutCaptions]): its live caption is withdrawn, from the screen
        // and from the sync's held captions, so Hermes never stores it and no line is left without a final (REVIEW-E7 P2-2).
        if(text.isBlank()) deliver(json("type" to "transcript","event_id" to "$callId:$role:$turn","session_id" to sessionId,
            "turn_id" to turn.toString(),"role" to role,"text" to "","final" to false,WITHDRAWN to true,"at_ms" to atMs))
        else deliver(json("type" to "transcript","event_id" to "$callId:$role:$turn","session_id" to sessionId,
            "turn_id" to turn.toString(),"role" to role,"text" to text,"final" to true,
            "interrupted" to (interrupted&&role=="assistant"),"at_ms" to atMs))
    }
    captions.clear()
    return turn+1
}

/** Completed provider items are snapshots, independent of response timing and caption deltas. */
internal fun providerTranscript(value:JSONObject,callId:String,sessionId:String,atMs:Long):JSONObject? {
    val item=value.optString("provider_item")
    if(item.isBlank()||item.length>200||value.optString("role")!="user") return null
    val identity=java.util.UUID.nameUUIDFromBytes((item+":"+value.optInt("content_index",0)).toByteArray(Charsets.UTF_8))
    val turn=value.optString("turn_id").takeIf(String::isNotBlank) ?: "provider-$identity"
    return JSONObject(value.toString()).put("event_id","$callId:user:provider-$identity").put("session_id",sessionId)
        .put("turn_id",turn).put("at_ms",atMs).put("final",true)
}

/**
 * What played of a reply cut off (device round 4, D2), from its received words and its audio: [playedMs] of [receivedMs]. Gemini Live gives
 * its output transcription no ordering against the audio (round 4: the whole 724-character answer had come 6.7 s before the cut, when 12.3 s
 * of its ~46 s of audio had played), and Grok streams its transcript deltas with the audio response: both run ahead of the speaker. So the
 * words are cut to the audio's share, and to [CUT_CHARS_PER_SECOND] a second of played audio (mid-reply the words can run ahead of the audio
 * received too), back to the last whole word (at the character in a script without spaces), with "…" for the cut. Nothing played: nothing
 * kept.
 */
internal fun playedCaption(text: String, playedMs: Long, receivedMs: Long): String {
    if (receivedMs <= 0L || playedMs <= 0L || text.isBlank()) return ""
    val keep = minOf(text.length.toLong() * minOf(playedMs, receivedMs) / receivedMs, playedMs * CUT_CHARS_PER_SECOND / 1000).toInt()
    if (keep >= text.length) return text
    // Back to the last whole word where the words have spaces; a script without them (Chinese, Japanese, Thai) is cut at the character.
    var end = keep
    while (end > 0 && keep - end <= 24 && !text[end].isWhitespace()) end--
    if (!text[end].isWhitespace()) { end = keep; if (end > 0 && Character.isHighSurrogate(text[end - 1])) end-- }
    val words = text.substring(0, end).trimEnd()
    return if (words.isEmpty()) "" else "$words…"
}
/** A ceiling on speech: no voice says more characters a second than this (English runs about 15; most other scripts fewer). */
internal const val CUT_CHARS_PER_SECOND = 25L

/**
 * A cut turn's reply keeps only what played ([playedCaption]); without the cut's audio figures it is kept as it came. Nothing played leaves
 * it blank, and the turn's end withdraws its live caption ([finishDirectTranscriptTurn]).
 */
internal fun cutCaptions(captions: MutableMap<String, String>, cut: JSONObject) {
    if (!cut.has("received_ms")) return
    val text = captions["assistant"] ?: return
    captions["assistant"] = playedCaption(text, cut.optLong("played_ms"), cut.optLong("received_ms"))
}
/**
 * Marks a caption event that withdraws its live caption: it leaves the screen ([withTranscript]) and the sync drops it with its held live
 * versions ([NativeCallTranscriptSync.event]); it never reaches Hermes.
 */
internal const val WITHDRAWN = "withdrawn"
