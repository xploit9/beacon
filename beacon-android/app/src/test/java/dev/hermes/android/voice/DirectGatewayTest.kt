package dev.hermes.android.voice

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import dev.hermes.android.data.ApiFailure
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DirectGatewayTest {
    private val toolId="a".repeat(64)
    private val proposalId="p".repeat(32)
    private fun receipt(status:String="needs_approval",requestId:String="request")=JSONObject().put("request_id",requestId).put("tool_id",toolId).put("tool_name","send_email").put("status",status).apply {
        if(status=="needs_approval") put("proposal",JSONObject().put("id",proposalId).put("tool_name","send_email").put("arguments",JSONObject().put("to","fixture@example.test").put("body","fixture")))
    }
    private fun registered():DirectGatewayReceipts=DirectGatewayReceipts().apply { reserve("request","function-id",toolId,JSONObject().put("body","fixture").put("to","fixture@example.test")) }
    @Test fun ownerDecisionNeedsAnExactKnownProposalAndArguments() {
        val ledger=registered()
        assertThrows(IllegalArgumentException::class.java) { ledger.decision("request",proposalId) }
        ledger.record("request",receipt())
        assertEquals(proposalId,ledger.decision("request",proposalId).getJSONObject("proposal").getString("id"))
        assertThrows(IllegalArgumentException::class.java) { ledger.decision("request","q".repeat(32)) }
        val tampered=receipt();tampered.getJSONObject("proposal").getJSONObject("arguments").put("to","other@example.test")
        assertThrows(IllegalArgumentException::class.java) { ledger.record("request",tampered) }
        assertThrows(IllegalArgumentException::class.java) { ledger.record("request",receipt(requestId="foreign")) }
    }
    @Test fun uncertainRequestPollsOriginalIdentityAndNeverReservesAgain() {
        val ledger=registered();ledger.record("request",receipt("uncertain"))
        assertEquals(listOf("request"),ledger.pending())
        assertEquals("function-id",ledger.functionId("request"))
        assertThrows(IllegalArgumentException::class.java) { ledger.reserve("request","new-function",toolId,JSONObject()) }
        ledger.record("request",receipt("completed").put("result",JSONObject().put("ok",true)))
        assertTrue(ledger.pending().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { ledger.decision("request",proposalId) }
        assertEquals("completed",ledger.record("request",receipt("running")).getString("status"))
    }
    @Test fun receiptsAreCopiedAndUnrecognizedStatesCannotClaimCompletion() {
        val ledger=registered();val value=receipt("running");ledger.record("request",value);value.put("status","completed")
        assertEquals("running",ledger.receipt("request")!!.getString("status"))
        ledger.receipt("request")!!.put("status","completed")
        assertEquals("running",ledger.receipt("request")!!.getString("status"))
        assertThrows(IllegalArgumentException::class.java) { ledger.record("request",receipt("imagined_success")) }
    }
    @Test fun currentVisionRequiresASentFrameAndExpiresOrStopsImmediately() {
        val vision=DirectVisualEvidence()
        assertFalse(vision.snapshot(1000).getBoolean("can_describe_current"))
        vision.update("camera","starting")
        val ticket=vision.ticket()!!
        assertFalse(vision.snapshot(1000).getBoolean("can_describe_current"))
        assertEquals(1,vision.sent(ticket,1000)!!.getInt("frames_sent"))
        assertTrue(vision.snapshot(2000).getBoolean("can_describe_current"))
        assertFalse(vision.snapshot(6001).getBoolean("can_describe_current"))
        assertFalse(vision.snapshot(999).getBoolean("can_describe_current"))
        assertNull(vision.sent(ticket,3000)) // No per-frame archive flood.
        vision.update("camera","failed","fixture capture error")
        assertFalse(vision.snapshot(3000).getBoolean("can_describe_current"))
        assertFalse(vision.permits(ticket));assertNull(vision.sent(ticket,3001))
    }
    @Test fun oldQueuedVisualCannotAuthorizeNewCaptureOrAnotherSource() {
        val vision=DirectVisualEvidence();vision.update("camera","starting");val old=vision.ticket()!!
        vision.update("camera","off");vision.update("camera","starting")
        assertNull(vision.sent(old,1000))
        vision.update("screen","starting");assertEquals("screen",vision.ticket()!!.source)
        assertFalse(vision.snapshot(1000).getBoolean("can_describe_current"))
    }
    @Test fun controlReceiptsPrecedeSaturatedPcmWithoutBlockingAudioProducer()=runBlocking<Unit> {
        val control=Channel<String>(2);val pcm=Channel<String>(2);val visual=Channel<String>(Channel.CONFLATED)
        assertTrue(pcm.trySend("audio1").isSuccess);assertTrue(pcm.trySend("audio2").isSuccess);assertFalse(pcm.trySend("audio-drop").isSuccess)
        control.send("receipt");visual.trySend("image")
        assertEquals("receipt",nextDirectFrame(control,pcm,visual))
        assertEquals("audio1",nextDirectFrame(control,pcm,visual))
        control.close();pcm.close();visual.cancel()
    }
    @Test fun admissionDeadlineAndTransientFailurePermitAnotherAttempt()=runBlocking<Unit> {
        assertNull(directAdmissionAttempt(1) { kotlinx.coroutines.delay(30);"too late" })
        assertNull(directAdmissionAttempt(100) { kotlinx.coroutines.withTimeout(1) { kotlinx.coroutines.delay(30);"nested timeout" } })
        assertNull(directAdmissionAttempt<String>(100) { throw java.io.IOException("fixture outage") })
        assertEquals("fresh token",directAdmissionAttempt(100) { "fresh token" })
    }
    @Test fun rejectedAuthorityAndParentCancellationNeverBecomeRetryable()=runBlocking<Unit> {
        val rejected=ApiFailure(401,"fixture rejected authority")
        val result=runCatching { directAdmissionAttempt<String>(100,{it is ApiFailure&&it.status==401}) { throw rejected } }
        assertSame(rejected,result.exceptionOrNull())
        assertTrue(runCatching { kotlinx.coroutines.withTimeout(1) { directAdmissionAttempt(10000) { kotlinx.coroutines.delay(30);"late" } } }.exceptionOrNull() is kotlinx.coroutines.TimeoutCancellationException)
        val parent=kotlinx.coroutines.Job()
        val worker=kotlinx.coroutines.CoroutineScope(parent+coroutineContext.minusKey(kotlinx.coroutines.Job)).async {
            directAdmissionAttempt<String>(10000) { kotlinx.coroutines.awaitCancellation() }
        }
        kotlinx.coroutines.yield();parent.cancel()
        assertTrue(runCatching { worker.await() }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
    }
    @Test fun closingCallCancelsOnlyLocalReadsAndIdleWaits() {
        assertTrue(directToolMayCancel(null))
        assertTrue(directToolMayCancel("submitting"))
        assertTrue(directToolMayCancel("waiting_for_idle"))
        listOf("creating_native_session","submitting_native","running","uncertain","submitted").forEach { assertFalse(it,directToolMayCancel(it)) }
    }
    @Test fun identicalPendingOperationHasOneRequestAndOneProposal() {
        val args=JSONObject().put("to","fixture@example.test").put("body","fixture")
        for(status in listOf("ready","running","needs_approval","uncertain")) {
            val ledger=registered();ledger.record("request",receipt(status))
            val duplicate=ledger.reserve("duplicate","second-function",toolId,args)!!
            assertEquals("already_requested",duplicate.getString("status"))
            assertEquals("request",duplicate.getString("request_id"))
            assertEquals(status,duplicate.getString("current_status"))
            assertEquals(listOf("request"),ledger.pending())
            assertNull(ledger.receipt("duplicate"))
            assertEquals("function-id",ledger.functionId("request"))
            val response=GeminiWire.tool("second-function","invoke_tool",duplicate).getJSONObject("toolResponse").getJSONArray("functionResponses").getJSONObject(0)
            assertEquals("second-function",response.getString("id"))
            assertFalse(response.getBoolean("willContinue"))
        }
    }
    @Test fun reservationCoalescesBeforeFirstHttpReceiptAndIsAtomic()=runBlocking<Unit> {
        val ledger=DirectGatewayReceipts()
        val results=(0 until 8).map { n -> async(kotlinx.coroutines.Dispatchers.Default) {
            ledger.reserve("request-$n","function-$n",toolId,JSONObject().put("nested",JSONObject().put("b",2).put("a",1)))
        } }.awaitAll()
        assertEquals(1,results.count { it==null })
        assertEquals(1,ledger.pending().size)
        val original=ledger.pending().single()
        results.filterNotNull().forEach { assertEquals(original,it.getString("request_id"));assertEquals("uncertain",it.getString("current_status")) }
    }
    @Test fun terminalResultOrDifferentArgumentsPermitNewExplicitOperation() {
        for(status in listOf("completed","denied","failed","expired","cancelled")) {
            val ledger=registered();ledger.record("request",receipt(status))
            assertNull(ledger.reserve("new-intent","new-function",toolId,JSONObject().put("to","fixture@example.test").put("body","fixture")))
            assertNotNull(ledger.receipt("new-intent"))
        }
        val different=registered()
        assertNull(different.reserve("new-args","new-function",toolId,JSONObject().put("to","other@example.test").put("body","fixture")))
        assertNull(different.reserve("new-tool","third-function","b".repeat(64),JSONObject().put("to","fixture@example.test").put("body","fixture")))
        assertEquals(3,different.pending().size)
    }
}
