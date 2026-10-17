package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class CameraOperationTest {
    @Test fun newerRequestAndShutdownInvalidatePendingCapture() {
        val gate = CameraOperationGate()
        val first = gate.request(true, false)
        val latest = gate.request(false, true)
        assertFalse(gate.current(first))
        assertTrue(gate.current(latest))
        assertFalse(latest.enabled)
        assertTrue(latest.rear)
        gate.invalidate()
        assertFalse(gate.current(latest))
    }
    @Test fun unconfirmedCaptureCleanupBlocksNewCallAdmission() {
        for (state in listOf(CallState(cameraStopPending = true), CallState(screenStopPending = true))) {
            assertTrue(runCatching { requireCameraReleasedForNewCall(state) }.isFailure)
        }
        requireCameraReleasedForNewCall(CallState())
    }
}
