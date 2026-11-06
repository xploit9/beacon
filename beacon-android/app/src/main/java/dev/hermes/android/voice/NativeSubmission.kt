package dev.hermes.android.voice

import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull

private fun notSent(reason:String)=JSONObject().put("status","not_sent").put("reason",reason)

/** A bounded progress cue, never a routing or dispatch decision. */
internal class VoiceWorkProgress {
    private val started=mutableMapOf<String,Long>()
    private val reported=mutableSetOf<String>()
    @Synchronized fun overrun(id:String,status:String,now:Long):Boolean {
        if(status !in setOf("ready","running","queued","working","watching","needs_approval","needs_input")) return false
        val since=started.getOrPut(id) { now }
        return now-since>=20000 && reported.add(id)
    }
}

/** Persist callback intent before the ONE native submission; Hermes' durable FIFO owns busy work. */
internal suspend fun submitVoiceContinuation(owned:()->Boolean,prepare:suspend ()->JSONObject,
    submit:suspend ()->JSONObject):JSONObject {
    if(!owned()) return notSent("call_ended")
    val intent=prepare()
    require(intent.optBoolean("continuation")&&intent.optString("job_id").isNotBlank()) { "Durable work linkage was not confirmed" }
    require(intent.opt("submit") is Boolean) { "Native submission ownership was not confirmed" }
    if(!intent.getBoolean("submit")) return intent.put("continuation_replay",true)
    if(!owned()) return notSent("call_ended")
    return submit().put("job_id",intent.getString("job_id"))
}

/** Observe idle, then mutate once. An anonymous queued ACK is still an ACK and is never retried. */
internal suspend fun submitNativeWhenIdle(owned:()->Boolean,idle:suspend ()->Boolean,submit:suspend ()->JSONObject,
    pause:suspend ()->Unit,maxProbes:Int=600,maxWaitMs:Long=600000,now:()->Long={System.nanoTime()/1000000}):JSONObject {
    val deadline=now()+maxWaitMs
    repeat(maxProbes) {
        if(!owned()) return notSent("call_ended")
        val remaining=deadline-now()
        if(remaining<=0) return notSent("idle_wait_expired")
        val ready=withTimeoutOrNull(remaining) { idle() }?:return notSent("idle_wait_expired")
        if(ready) {
            if(!owned()) return notSent("call_ended")
            if(now()>=deadline) return notSent("idle_wait_expired")
            // Mutation is outside the idle deadline: a timed-out submission is ambiguous, never not_sent.
            return submit()
        }
        if(withTimeoutOrNull((deadline-now()).coerceAtLeast(1)) { pause();true }!=true) return notSent("idle_wait_expired")
    }
    return notSent("idle_wait_expired")
}

/** Native active_list carries runtime id and durable session_key, not stored_session_id. */
internal fun nativeVoiceSessionReady(active:JSONObject,runtime:String,stored:String):Boolean {
    require(runtime.isNotBlank()&&stored.isNotBlank()) { "Native conversation identity required" }
    val rows=active.getJSONArray("sessions")
    val row=(0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.opt("id")==runtime }
        ?:error("Owned native runtime unavailable")
    require(row.opt("session_key")==stored) { "Native runtime conversation mismatch" }
    return when(row.getString("status")) {
        "starting", "resuming" -> false
        "idle", "working", "waiting" -> true
        else -> error("Native runtime readiness unavailable")
    }
}

internal fun nativeVoiceAgentReady(resumed:JSONObject):Boolean = !resumed.getJSONObject("info").optBoolean("lazy")
