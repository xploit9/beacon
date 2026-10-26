package dev.hermes.android.voice

import org.json.JSONObject

/** The provider took the owner's turn or a tool result and never answered, on every retry. */
internal class VoiceStalled:java.net.SocketTimeoutException("Voice stopped answering and couldn't recover. Try calling again.")

/**
 * Notices a provider session that stopped answering while the call still says "Listening". Silence alone is normal
 * (no provider sends an application keep-alive, and OkHttp's ping covers a dead socket), so only an unanswered
 * obligation counts: sustained owner speech or a tool result/turn the model must answer. Any server frame proves the
 * session is alive and restarts the clock; only a model reply settles the obligation.
 */
internal class VoiceStallWatch(private val clock:()->Long={ System.nanoTime()/1_000_000 },private val limit:Long=20_000) {
    private var owedAt:Long?=null
    private var heardAt=0L
    private var run=0L;private var gap=0L
    private var playingUntil=0L
    @Synchronized fun reset() { owedAt=null;run=0;gap=0;playingUntil=0;heardAt=clock() }
    @Synchronized fun heard() { heardAt=clock() }
    @Synchronized fun replied() { heardAt=clock();owedAt=null;run=0;gap=0;stalls=0 }
    @Synchronized fun expect() { owedAt=clock() }
    /** The server cut the reply short (barge-in): the speaker is quiet again. */
    @Synchronized fun interrupted() { heardAt=clock();playingUntil=0 }
    /** Model audio was queued for the speaker; the microphone hears its echo for that long. */
    @Synchronized fun playing(ms:Long) { playingUntil=maxOf(playingUntil,clock())+ms }
    /** One microphone chunk: ≥700 ms of near-continuous speech counts as the owner talking; typing, doors and echo do not. */
    @Synchronized fun speech(loud:Boolean,ms:Long) {
        val now=clock()
        if(playingUntil>0&&now<playingUntil+ECHO_TAIL_MS) { run=0;gap=0;return }
        if(loud) { gap=0;run+=ms;if(run>=SPEECH_MS) owedAt=now } else { gap+=ms;if(gap>GAP_MS) run=0 }
    }
    @Synchronized fun stalled():Boolean = owedAt?.let { clock()-maxOf(it,heardAt)>=limit }==true
    private var stalls=0;private var lastStallAt=0L
    /** Counts one more replaced session and returns how many came in a row; one long after the last starts the count again, so rare false alarms in a quiet call never add up. */
    @Synchronized fun stalledAgain():Int { val now=clock();if(stalls>0&&now-lastStallAt>STALL_MEMORY_MS) stalls=0;lastStallAt=now;return ++stalls }
    /** Stalls in a row so far; a model reply clears them. */
    @Synchronized fun stallsInARow():Int = stalls
    private companion object {
        const val SPEECH_MS=700L;const val GAP_MS=300L;const val ECHO_TAIL_MS=700L;const val STALL_MEMORY_MS=120_000L
    }
}

internal fun voiceLoud(pcm:ByteArray):Boolean {
    var sum=0.0;var count=0
    for(i in 0 until pcm.size-1 step 2) { val sample=((pcm[i].toInt() and 255) or (pcm[i+1].toInt() shl 8)).toShort().toInt();sum+=sample.toDouble()*sample;count++ }
    return count>0&&Math.sqrt(sum/count)>=0.015*32768
}

/** A model reply (audio, words, a finished turn or a tool call), as opposed to setup, resumption or a barge-in notice. */
internal fun voiceReplyFrame(frame:JSONObject):Boolean = frame.has("toolCall")||
    frame.optJSONObject("serverContent")?.let { it.has("modelTurn")||it.has("outputTranscription")||it.optBoolean("turnComplete")||it.optBoolean("generationComplete") }==true

internal enum class OwedReply { None, Tool, Turn }

/** Whether a frame sent to the provider obliges the model to speak. Progress receipts are SILENT by design. */
internal fun voiceOwedReply(json:String):OwedReply {
    val frame=JSONObject(json)
    frame.optJSONObject("toolResponse")?.optJSONArray("functionResponses")?.optJSONObject(0)?.let {
        return if(it.optString("scheduling")=="SILENT") OwedReply.None else OwedReply.Tool
    }
    return if(frame.optJSONObject("clientContent")?.optBoolean("turnComplete")==true) OwedReply.Turn else OwedReply.None
}

/** One video frame at a time: the next waits until the socket drained the last, so a slow link loses frames, never audio. */
internal class VisualPacing(private val clock:()->Long={ System.nanoTime()/1_000_000 }) {
    private var sentAt=0L;private var nextAt=0L;private var inFlight=false
    @Synchronized fun reset() { inFlight=false;nextAt=0 }
    @Synchronized fun mayQueue():Boolean = !inFlight&&clock()>=nextAt
    @Synchronized fun sent() { sentAt=clock();inFlight=true }
    /** Call with the socket's queued bytes; the next frame is due 3x what this one took to drain after it was sent (0.8 to 30 s: video uses a third of a slow link at most). */
    @Synchronized fun drained(backlog:Long) {
        if(!inFlight||backlog>=GeminiWire.VISUAL_BACKLOG_MAX) return
        inFlight=false;nextAt=sentAt+((clock()-sentAt)*3).coerceIn(800,30_000)
    }
}
