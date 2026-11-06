package dev.hermes.android.voice
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NativeSubmissionTest {
    @Test fun idleAdmissionUsesElapsedDeadlineWithoutTimingOutAcceptedMutation()=runBlocking {
        var clock=0L;var writes=0
        val expired=submitNativeWhenIdle({true},{false},{writes++;JSONObject()},{clock+=40},600,100,{clock})
        assertEquals("idle_wait_expired",expired.getString("reason"));assertEquals(0,writes)
        val accepted=submitNativeWhenIdle({true},{true},{clock+=200;writes++;JSONObject().put("status","streaming")},{},600,100,{clock})
        assertEquals("streaming",accepted.getString("status"));assertEquals(1,writes)
    }
    @Test fun idleButFailedNativeAgentCannotPassVoiceAdmission() {
        assertFalse(nativeVoiceAgentReady(JSONObject().put("info",JSONObject().put("lazy",true))))
        assertTrue(nativeVoiceAgentReady(JSONObject().put("info",JSONObject().put("model","native-model"))))
        assertTrue(runCatching { nativeVoiceAgentReady(JSONObject()) }.isFailure)
    }
    @Test fun voiceReadinessUsesNativeRuntimeAndDurableKeyAndWaitsForBuild() {
        fun active(status:String="idle",runtime:String="runtime",stored:String="stored")=JSONObject().put("sessions",org.json.JSONArray().put(JSONObject().put("id",runtime).put("session_key",stored).put("status",status)))
        assertFalse(nativeVoiceSessionReady(active("starting"),"runtime","stored"))
        for(status in listOf("idle","working","waiting")) assertTrue(nativeVoiceSessionReady(active(status),"runtime","stored"))
        assertTrue(runCatching { nativeVoiceSessionReady(active(runtime="other"),"runtime","stored") }.isFailure)
        assertTrue(runCatching { nativeVoiceSessionReady(active(stored="other"),"runtime","stored") }.isFailure)
        assertTrue(runCatching { nativeVoiceSessionReady(active("failed"),"runtime","stored") }.isFailure)
    }
    @Test fun blankOrMissingNativeIdentitiesCannotPassReadiness() {
        fun active(row:JSONObject)=JSONObject().put("sessions",org.json.JSONArray().put(row))
        for((runtime,stored,row) in listOf(
            Triple("","",JSONObject().put("status","idle")),
            Triple("runtime","",JSONObject().put("id","runtime").put("status","idle")),
            Triple("runtime"," ",JSONObject().put("id","runtime").put("session_key"," ").put("status","idle")))) {
            assertTrue(runCatching { nativeVoiceSessionReady(active(row),runtime,stored) }.isFailure)
        }
    }
    @Test fun nativeIdentitiesMustBeJsonStringsWithoutCoercion() {
        fun active(row:JSONObject)=JSONObject().put("sessions",org.json.JSONArray().put(row))
        for((runtime,stored,row) in listOf(
            Triple("null","stored",JSONObject().put("id",JSONObject.NULL).put("session_key","stored").put("status","idle")),
            Triple("runtime","null",JSONObject().put("id","runtime").put("session_key",JSONObject.NULL).put("status","idle")),
            Triple("7","stored",JSONObject().put("id",7).put("session_key","stored").put("status","idle")),
            Triple("runtime","7",JSONObject().put("id","runtime").put("session_key",7).put("status","idle")))) {
            assertTrue(runCatching { nativeVoiceSessionReady(active(row),runtime,stored) }.isFailure)
        }
    }
    @Test fun busyThenIdleSubmitsExactlyOnce()=runBlocking {
        var probes=0;var writes=0
        val receipt=submitNativeWhenIdle({true},{++probes>=3},{writes++;JSONObject().put("status","streaming").put("user_row_id",123)},{yield()})
        assertEquals(3,probes);assertEquals(1,writes);assertEquals(123,receipt.getInt("user_row_id"))
    }
    @Test fun concurrentBusyRaceQueuedAckNeverReplaysMutation()=runBlocking {
        var writes=0
        val receipt=submitNativeWhenIdle({true},{true},{writes++;JSONObject().put("status","queued")},{error("ACK must not be polled or replayed")})
        assertEquals("queued",receipt.getString("status"));assertEquals(1,writes)
    }
    @Test fun hangupOrWaitExpiryNeverPromisesUnsentWork()=runBlocking {
        var owned=true;var writes=0
        val ended=submitNativeWhenIdle({owned},{false},{writes++;JSONObject()},{owned=false},5)
        assertEquals("not_sent",ended.getString("status"));assertEquals("call_ended",ended.getString("reason"))
        val expired=submitNativeWhenIdle({true},{false},{writes++;JSONObject()},{yield()},3)
        assertEquals("not_sent",expired.getString("status"));assertEquals(0,writes)
    }
    @Test fun queuedWorkMustNotBeLostWhenAudioEndsWhileNativeIsBusy()=runBlocking {
        var owned=true;var writes=0;var durable=false
        val result=submitVoiceContinuation({owned},{durable=true;JSONObject().put("job_id","job").put("continuation",true).put("submit",true)},{
            assertTrue(durable);writes++;owned=false
            JSONObject().put("status","queued").put("admission_id","admission")
        })
        assertEquals("queued",result.getString("status"));assertEquals(1,writes)
    }
}
