package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class CameraFailureIsolationTest {
    @Test fun failedStopPreservesLiveVoiceScopeAndNeverClaimsCameraOff() {
        val voice=CallState(active=true,state="connected",callId="owned",muted=true,held=true,
            transcript="existing",cameraSharing=true,cameraCanFlip=true,startedAt=123)
        val failed=voice.afterCameraFailure(false)
        assertTrue(failed.active);assertEquals(voice.callId,failed.callId);assertEquals(voice.state,failed.state)
        assertEquals(voice.muted,failed.muted);assertEquals(voice.held,failed.held)
        assertEquals(voice.startedAt,failed.startedAt);assertEquals(voice.transcript,failed.transcript)
        assertFalse(failed.cameraSharing);assertTrue(failed.cameraStopPending)
        assertFalse(failed.cameraCanFlip);assertFalse(failed.cameraBusy)
        assertTrue(failed.error.contains("could not be confirmed"));assertFalse(failed.error.contains("sharing is off"))
    }
    @Test fun confirmedCleanupOnlyDisablesVideoAndClearsUnconfirmedState() {
        val voice=CallState(active=true,state="connected",callId="owned",cameraSharing=true,cameraStopPending=true)
        val stopped=voice.afterCameraFailure(true)
        assertTrue(stopped.active);assertEquals(voice.callId,stopped.callId);assertEquals(voice.state,stopped.state)
        assertFalse(stopped.cameraSharing);assertFalse(stopped.cameraStopPending)
        assertTrue(stopped.error.contains("sharing is off"))
    }
}
