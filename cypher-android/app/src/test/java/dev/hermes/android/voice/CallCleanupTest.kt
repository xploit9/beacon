package dev.hermes.android.voice

import dev.hermes.android.data.ApiFailure
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*

class CallCleanupTest {
    @Test fun stalledTeardownReturnsAnActionableFailureInsteadOfBlockingRedialForever() = runBlocking {
        val failure=runCatching { awaitCallTeardown(CompletableDeferred(),25) { true } }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(failure!!.message!!.contains("previous call is still closing"))
    }
    @Test fun archiveFailureDoesNotPreventHostReleaseAndNativeClientCleanup() = runBlocking {
        val closed=finishTranscriptSyncForCleanup { throw IOException("Lost transcript receipt") }
        assertFalse(closed)
        assertTrue(finishTranscriptSyncForCleanup { true })
    }
    @Test fun terminalControlsClearButLinkedHistoryAndRecordingReceiptRemain() {
        val previous=CallState(held=true,muted=true,userSpeaking=true,remoteSpeaking=true,localAudioLevel=1f,
            remoteAudioLevel=1f,routes=listOf(AudioRoute("speaker","Speaker")),route="speaker",cameraRear=true,
            cameraCanFlip=true,overlayPermissionMissing=true,contextNotice="Old context",callId="linked-call",
            transcript="Retained prefix",transcriptStatus="partial",hostCleanupPending=true)
        val ended=previous.afterTerminalControls(null)
        assertFalse(ended.held);assertFalse(ended.muted);assertFalse(ended.userSpeaking);assertFalse(ended.remoteSpeaking)
        assertNull(ended.localAudioLevel);assertNull(ended.remoteAudioLevel);assertTrue(ended.routes.isEmpty())
        assertEquals("",ended.route);assertEquals("",ended.contextNotice);assertFalse(ended.cameraCanFlip)
        assertFalse(ended.cameraRear);assertFalse(ended.overlayPermissionMissing)
        assertEquals(previous.callId,ended.callId);assertEquals(previous.transcript,ended.transcript)
        assertEquals(previous.transcripts,ended.transcripts);assertEquals("partial",ended.transcriptStatus)
        assertTrue(ended.hostCleanupPending)
    }
    @Test fun failingStopFenceStillFinishesOnceAndRetainsTruthfulFailure() {
        val closed=AtomicBoolean(false)
        var ended=CallState(error="Voice connection lost")
        var cleanups=0
        assertTrue(finishCallOnce(closed,{throw IOException("private failure detail")}) { failure ->
            cleanups++;ended=ended.afterTerminalControls(failure)
        })
        assertEquals(1,cleanups);assertTrue(ended.error.contains("Voice connection lost"))
        assertTrue(ended.error.contains("Call stop fence failed"));assertFalse(ended.error.contains("private failure detail"))
        assertFalse(finishCallOnce(closed) { cleanups++ });assertEquals(1,cleanups)
    }
    @Test fun immediateRedialWaitsForOldServiceDestructionAfterDelayedCleanup() = runBlocking {
        val destroyed = CompletableDeferred<Unit>()
        var grantRequests = 0
        val redial = launch { awaitCallTeardown(destroyed) { true }; grantRequests++ }
        yield()
        assertEquals(0, grantRequests)
        // DELETE completing alone is insufficient; onDestroy releases the service lifetime.
        yield()
        assertEquals(0, grantRequests)
        destroyed.complete(Unit)
        redial.join()
        assertEquals(1, grantRequests)
    }

    @Test fun hangupDuringPendingRedialPreventsGrantAfterDestruction() = runBlocking {
        val destroyed = CompletableDeferred<Unit>()
        var current = true
        var grantRequests = 0
        var rejected = false
        val redial = launch {
            try { awaitCallTeardown(destroyed) { current }; grantRequests++ }
            catch (_: IllegalStateException) { rejected = true }
        }
        yield(); current = false; destroyed.complete(Unit); redial.join()
        assertTrue(rejected); assertEquals(0, grantRequests)
    }

    @Test fun oldCleanupCannotOverwriteNewCallState() {
        val newCall = CallState(active = true, state = "listening", startedAt = 200)
        assertSame(newCall, newCall.afterOwnedCleanupFailure(IOException("Old DELETE failed"), false))
        assertEquals("Call ended locally; PC call cleanup is unconfirmed", CallState().afterOwnedCleanupFailure(IOException("Current DELETE failed"), true).error)
    }
    @Test fun normalHangupWinsOnceAndLateSocketFailureCannotOverwriteEndedState() {
        val closed = AtomicBoolean(false)
        var state = CallState(active = true, state = "listening", transcript = "Retained call transcript")
        var cleanupCount = 0
        assertTrue(finishCallOnce(closed) { cleanupCount++; state = state.copy(active = false, state = "idle") })
        assertFalse(finishCallOnce(closed) { cleanupCount++; state = state.copy(error = "Voice connection lost") })
        assertEquals(1, cleanupCount)
        assertFalse(state.active); assertEquals("idle", state.state); assertEquals("", state.error)
        assertEquals("Retained call transcript", state.transcript)
    }

    @Test fun genuineOpenCallFailureIsPreservedAgainstLateFailuresAndHangup() {
        val closed = AtomicBoolean(false)
        var state = CallState(active = true, state = "listening")
        assertTrue(finishCallOnce(closed) { state = state.copy(active = false, state = "idle", error = "Voice authorization expired") })
        assertFalse(finishCallOnce(closed) { state = state.copy(error = "Late EOF") })
        assertFalse(finishCallOnce(closed) { state = state.copy(error = "") })
        assertEquals("Voice authorization expired", state.error)
        assertFalse(state.active)
    }
    @Test fun alreadyEndedCallPreservesSuccessfulLocalTeardownAndPriorErrors() {
        val ended = CallState(active = false, state = "idle", transcript = "Retained call transcript")
        assertSame(ended, ended.afterCleanupFailure(ApiFailure(404, "Call no longer exists")))
        val priorFailure = ended.copy(error = "Voice connection lost")
        assertSame(priorFailure, priorFailure.afterCleanupFailure(ApiFailure(404, "Call no longer exists")))
    }

    @Test fun authorizationAndNetworkFailuresStillRequireReconciliation() {
        val ended = CallState(transcript = "Retained call transcript")
        listOf(ApiFailure(401, "Signed out"), ApiFailure(403, "Wrong owner"), ApiFailure(500, "Server failed"), IOException("Connection lost"), IllegalArgumentException("404 is only text")).forEach { failure ->
            val result = ended.afterCleanupFailure(failure)
            assertFalse(result.active)
            assertTrue(result.hostCleanupPending)
            assertEquals("idle", result.state)
            assertEquals(ended.transcript, result.transcript)
            assertEquals("Call ended locally; PC call cleanup is unconfirmed", result.error)
        }
    }
}
