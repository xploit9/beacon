package dev.hermes.android.voice

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceContinuationTest {
    private fun prepared()=JSONObject().put("continuation",true).put("job_id","durable-job").put("submit",true)

    @Test fun nativeQueueReceiptSurvivesImmediateAudioHangup()=runBlocking {
        var owned=true;var writes=0;val order=mutableListOf<String>()
        val result=submitVoiceContinuation({owned},{order+="intent";prepared()},{
            order+="submit";writes++;owned=false
            JSONObject().put("status","queued").put("admission_id","native-admission")
        })
        assertEquals(listOf("intent","submit"),order)
        assertEquals("queued",result.getString("status"))
        assertEquals("native-admission",result.getString("admission_id"))
        assertEquals("durable-job",result.getString("job_id"));assertEquals(1,writes)
    }

    @Test fun callbackIntentMustBeDurableBeforeAnyNativeWrite()=runBlocking {
        var writes=0
        assertTrue(runCatching {
            submitVoiceContinuation({true},{JSONObject().put("continuation",false)},{writes++;JSONObject()})
        }.isFailure)
        assertEquals(0,writes)
    }

    @Test fun hangupBeforeSubmissionHasNoNativeMutation()=runBlocking {
        var owned=true;var writes=0
        val result=submitVoiceContinuation({owned},{owned=false;prepared()},{writes++;JSONObject()})
        assertEquals("not_sent",result.getString("status"));assertEquals(0,writes)
        submitVoiceContinuation({false},{error("No intent after hangup")},{error("No native mutation")})
        Unit
    }

    @Test fun lostSubmissionReceiptNeverReplaysUnknownWork()=runBlocking {
        var writes=0
        assertTrue(runCatching { submitVoiceContinuation({true},{prepared()},{writes++;throw java.io.IOException("Synthetic lost receipt")}) }.isFailure)
        assertEquals(1,writes)
    }

    @Test fun recreatedPhoneNeverResubmitsAnExistingDurableIntent()=runBlocking {
        var writes=0
        val result=submitVoiceContinuation({true},{prepared().put("submit",false).put("status","submission_unconfirmed")},{writes++;JSONObject()})
        assertEquals(0,writes);assertEquals("submission_unconfirmed",result.getString("status"))
        assertTrue(result.getBoolean("continuation_replay"))
    }

    @Test fun submittedWorkIsNotCancelledByCallEndClassification() {
        assertFalse(directToolMayCancel("submitting_native"))
        assertFalse(directToolMayCancel("queued"))
        assertFalse(directToolMayCancel("watching"))
        assertTrue(directToolMayCancel("staging_native"))
        assertFalse(outcomeUnknownAtCallEnd(JSONObject().put("function_name","hermes_work").put("status","queued")))
    }

    @Test fun queuedCallbackUsesHermesVoiceAndDoesNotAskForAnotherAssistant() {
        val hint=callToolHint("hermes_work",JSONObject().put("status","queued").put("callback","scheduled"))
        assertTrue(hint.contains("hang up"))
        assertTrue(hint.contains("you")&&hint.contains("call"))
        assertFalse(hint.contains("once it starts"))
    }
}
