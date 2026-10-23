package dev.hermes.android.voice

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.core.Scope
import org.json.JSONObject

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

/** Returns a title only for a genuinely untitled conversation. */
internal fun deriveCallEndTitle(currentTitle: String, transcripts: List<CallTranscript>, assistantName: String): String? {
    if (currentTitle.isNotBlank() && !currentTitle.trim().equals("New conversation", ignoreCase = true)) return null
    val firstRequest = orderCallTranscriptLines(transcripts).firstOrNull { it.role == "user" && !it.interrupted && it.text.isNotBlank() }
        ?.text?.trim()?.replace(Regex("\\s+"), " ")
    if (!firstRequest.isNullOrBlank()) {
        val characters = firstRequest.codePointCount(0, firstRequest.length).coerceAtMost(40)
        return firstRequest.substring(0, firstRequest.offsetByCodePoints(0, characters)).trimEnd()
    }
    return "Call with ${assistantName.trim().ifBlank { "Beacon" }}"
}

internal fun List<CallTranscript>.acceptTranscript(event: CallTranscript): List<CallTranscript> {
    val previous = firstOrNull { it.eventId == event.eventId }
    if ((previous?.final == true || previous?.interrupted == true) && !event.final) return this
    val next = event
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
        deliver(json("type" to "transcript","event_id" to "$callId:$role:$turn","session_id" to sessionId,
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
