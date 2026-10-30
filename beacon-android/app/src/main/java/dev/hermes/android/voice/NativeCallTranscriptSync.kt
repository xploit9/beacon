package dev.hermes.android.voice

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

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
        if(!queue.trySend("events" to JSONObject().put("events",JSONArray().put(copy))).isSuccess&&queue===important) { gap=true;report("partial") }
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
            if(withTimeoutOrNull(15000) { workers.first().join();true }!=true) { gap=true;workers.first().cancel() }
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

