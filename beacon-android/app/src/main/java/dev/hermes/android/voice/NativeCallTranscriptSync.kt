package dev.hermes.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** The tool receipt statuses the call timeline takes (mobile.py `events`); a plugin refuses a whole batch on any other. */
private val TIMELINE_TOOL_STATUSES = setOf("ready", "running", "needs_approval", "transferred", "completed", "failed", "denied", "expired", "uncertain",
    "cancelled", "unavailable", "not_sent", "submission_unconfirmed")
private val TIMELINE_TOOL_CODE = Regex("http_[45][0-9]{2}|timeout|dns|tls|network|invalid_response|tool_http_failure|admission_rejected|" +
    "memory_lookup_busy|memory_lookup_timeout|memory_lookup_failed|hermes_updating|phone_in_use")
private val TIMELINE_IDENT = Regex("[A-Za-z0-9_-]{1,160}")
/** What lost a call's link, in the plugin's closed set (mobile.py `CAUSE_CLASSES`); a plugin refuses a whole batch on any other. */
private val TIMELINE_CAUSES = setOf("ssl_handshake", "ssl_peer", "ssl_protocol", "ssl_transport", "socket_timeout", "dns", "connect", "eof", "io", "other")
/** The category voiceTransportCause gives each exception class the call engine names; any other I/O failure is `io`. */
private val TIMELINE_CAUSE_OF = mapOf("CallRequestTimeout" to "socket_timeout", "SocketTimeoutException" to "socket_timeout",
    "UnknownHostException" to "dns", "ConnectException" to "connect", "EOFException" to "eof", "SSLHandshakeException" to "ssl_handshake",
    "SSLPeerUnverifiedException" to "ssl_peer", "SSLProtocolException" to "ssl_protocol", "SSLException" to "ssl_transport")
private val TIMELINE_CAUSE_NAME = Regex("[A-Za-z0-9_$]{1,80}")

/**
 * Whether the plugin keeps a timeline tool receipt's `cause_class` (call contract v3, `call_capabilities.tool_receipt_cause`, LINK-121).
 * A plugin without it refuses the whole event batch on that field, so nothing names a cause until the call's context says so.
 */
internal fun takesToolReceiptCause(context: JSONObject?): Boolean =
    context != null && context.optInt("call_contract", 0) >= 3 && context.optJSONObject("call_capabilities")?.optBoolean("tool_receipt_cause") == true

/**
 * The plugin's category for the failure that lost a call's link. The engine names it by its exception class (CallLinkState: the I/O failure
 * that lost the link, or CallRequestTimeout); a category is kept as it is, and anything that is not a class name is not sent.
 */
internal fun timelineCause(name: String): String? = when {
    name in TIMELINE_CAUSES -> name
    !TIMELINE_CAUSE_NAME.matches(name) -> null
    else -> TIMELINE_CAUSE_OF[name] ?: "io"
}

/**
 * A tool receipt as the call timeline stores it (calls contract section 6). The link states the model is told keep the timeline's
 * v2 values: still_running is running, hermes_unreachable is unavailable (network), lost_result is uncertain (timeout). Anything a
 * plugin would refuse is left out, so one receipt can never hold up the call's text behind it. [withCause]: the plugin takes the cause
 * of a lost link ([takesToolReceiptCause]), which then goes as its category.
 */
internal fun timelineToolReceipt(value: JSONObject, withCause: Boolean = false): JSONObject? {
    val (status, code) = when (val status = value.optString("status")) {
        "still_running", "handed_over" -> "running" to null
        "hermes_unreachable" -> "unavailable" to "network"
        "lost_result" -> "uncertain" to "timeout"
        else -> status to value.optString("error_code")
    }
    val requestId = value.optString("request_id")
    val tool = value.optString("tool_name")
    if (status !in TIMELINE_TOOL_STATUSES || !TIMELINE_IDENT.matches(requestId) || tool.isEmpty() || tool.length > 200) return null
    return JSONObject().put("type", "tool_receipt").put("request_id", requestId).put("tool_name", tool).put("status", status)
        .apply {
            code?.takeIf(TIMELINE_TOOL_CODE::matches)?.let { put("error_code", it) }
            if (withCause) timelineCause(value.optString("cause_class"))?.let { put("cause_class", it) }
        }
}

/** Text and call events synchronize without retaining microphone or playback audio. */
internal class NativeCallTranscriptSync(private val request:suspend (String,String,JSONObject?)->JSONObject,private val callId:String,scope:CoroutineScope,private val status:(String)->Unit) {
    private val path="/api/plugins/beacon/mobile/calls/$callId"
    private val pending=Channel<Pair<String,JSONObject>>(48)
    private val important=Channel<Pair<String,JSONObject>>(16)
    private val partialTranscripts=linkedMapOf<String,JSONObject>()
    private val identitySuffix=Regex("[A-Za-z0-9_-]{1,160}")
    private val statuses=Channel<String>(Channel.CONFLATED)
    private val backgroundJob=SupervisorJob(scope.coroutineContext[Job])
    private val background=CoroutineScope(scope.coroutineContext+backgroundJob+Dispatchers.IO)
    private val eventSequence=AtomicLong()
    private val finalized=CompletableDeferred<Boolean>()
    private var accepting=true
    @Volatile private var gap=false
    @Volatile private var statusFailed=false
    @Volatile private var speechFailurePending=false
    private val statusJob=background.launch {
        for(value in statuses) {
            try { status(if((gap||statusFailed)&&value !in setOf("unavailable","failed")) "partial" else value) }
            catch(error:CancellationException) { throw error }
            catch(error:Throwable) { statusFailed=true }
        }
    }
    // Independent text/event receipts may complete out of order.
    private val workers=List(3) { index -> background.launch {
        var drained=false
        try {
            for((route,body) in if(index==0) important else pending) {
                var receipt=requestReceipt(route,body,8000)
                if(index==0) {
                    // Keep the same event identity through a transient outage. Finalization bounds the drain.
                    while(receipt?.optBoolean("stored")!=true) {
                        report("syncing");delay(500)
                        receipt=requestReceipt(route,body,8000)
                    }
                    report(if(gap) "partial" else "syncing")
                }
            }
            drained=true
        } catch(error:CancellationException) { throw error }
        catch(error:Throwable) { if(index==0) { gap=true;report("partial") } }
        finally { if(!drained&&index==0) gap=true }
    } }
    private fun report(value:String) { statuses.trySend(value) }
    private suspend fun requestReceipt(route:String,body:JSONObject,timeout:Long):JSONObject?=withTimeoutOrNull(timeout) {
        // Event IDs and terminal receipts make both routes idempotent.
        val attempts=2
        val perAttempt=(timeout-100*(attempts-1))/attempts
        repeat(attempts) { attempt ->
            val receipt=try { withTimeoutOrNull(perAttempt) { request("$path/$route","POST",body) } }
                catch(error:CancellationException) { throw error }
                catch(error:Exception) { null }
            if(receipt!=null) return@withTimeoutOrNull receipt
            if(attempt+1<attempts) delay(100)
        }
        null
    }
    @Synchronized fun event(value:JSONObject) {
        if(!accepting) return
        val eventId=java.util.UUID.nameUUIDFromBytes((value.optString("event_id")+":"+eventSequence.incrementAndGet()).toByteArray(Charsets.UTF_8)).toString()
        val copy=JSONObject(value.toString()).put("event_id",eventId)
        copy.remove("display_event_id")
        if(copy.optString("type")=="interrupted") copy.put("type","interruption")
        val text=copy.optString("text")
        if(text.length>8192) {
            val end=if(text[8191].isHighSurrogate()&&text[8192].isLowSurrogate()) 8191 else 8192
            copy.put("text",text.take(end));gap=true;report("partial")
        }
        if(copy.optString("type")=="transcript") {
            val role=copy.optString("role");val prefix="$callId:$role:"
            val displayId=value.optString("event_id")
            // Display items survive delivery UUIDs; only this call's role-bound text identity is eligible.
            if(value.opt("event_id") is String&&role in setOf("user","assistant")&&displayId.length<=256&&
                displayId.startsWith(prefix)&&identitySuffix.matches(displayId.removePrefix(prefix))) copy.put("display_event_id",displayId)
            copy.remove("provider_item");copy.remove("content_index")
            val key=copy.optString("display_event_id").ifBlank { prefix+copy.optString("turn_id") }
            // A withdrawn caption (a cut where nothing of the reply played) is never sent, nor are its held live versions: Hermes has no line
            // for it, and no line without a final to save the call "partial" (REVIEW-E7 P2-2; live captions wait here until their final).
            if(copy.optBoolean(WITHDRAWN)) { partialTranscripts.remove(key);return }
            // Coalesced snapshots keep the time of the first caption that was held back.
            partialTranscripts[key]?.opt("at_ms")?.let { copy.put("at_ms",it) }
            if(!copy.optBoolean("final")&&!copy.optBoolean("interrupted")) {
                partialTranscripts[key]=copy
                if(partialTranscripts.size>16) { partialTranscripts.remove(partialTranscripts.keys.first());gap=true;report("partial") }
                return
            }
            partialTranscripts.remove(key)
        }
        val queue=if(copy.optString("type")=="media_diagnostic") pending else important
        val accepted=queue.trySend("events" to JSONObject().put("events",JSONArray().put(copy))).isSuccess
        // A failed speech render or a failed call start must reach Hermes before the call is released: finish() waits for them.
        if(accepted&&queue===pending&&(isSpeechFailureDiagnostic(copy)||copy.optString("stage")=="voice_error")) speechFailurePending=true
        if(!accepted&&queue===important) { gap=true;report("partial") }
    }
    suspend fun finish(failed:Boolean=false):Boolean {
        val first=synchronized(this) {
            if(!accepting) false else {
                accepting=false
                // Latest partial text remains useful when a transport closes before a final turn receipt.
                partialTranscripts.values.forEach { copy ->
                    if(!important.trySend("events" to JSONObject().put("events",JSONArray().put(copy))).isSuccess) { gap=true;report("partial") }
                }
                partialTranscripts.clear()
                pending.close();important.close();true
            }
        }
        if(!first) return finalized.await()
        try {
            val drainStarted=System.nanoTime()
            if(withTimeoutOrNull(15000) { workers.first().join();true }!=true) { gap=true;workers.first().cancel() }
            if(speechFailurePending) {
                val remaining=(15000L-(System.nanoTime()-drainStarted)/1_000_000).coerceAtLeast(0)
                withTimeoutOrNull(remaining) { workers.drop(1).joinAll() }
            }
            workers.drop(1).forEach { it.cancel() }
            val receipt=requestReceipt("finalize",JSONObject().put("incomplete",gap).put("failed",failed),15000)
            val state=receipt?.optString("transcript_status","finalizing")?:"unavailable"
            val closed=receipt?.optBoolean("closed")==true||receipt?.optString("status")=="closed"
            report(if(gap) "partial" else if(!closed&&state=="saved") "finalizing" else state)
            finalized.complete(closed)
            return closed
        } catch(error:Throwable) { report("unavailable");finalized.complete(false);throw error }
        finally {
            withContext(NonCancellable) {
                statuses.close()
                try { withTimeoutOrNull(2000) { statusJob.join() } }
                finally { backgroundJob.cancel() }
            }
        }
    }
}

