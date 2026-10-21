package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test
import dev.hermes.android.core.json

class CameraConsentTest {
    @Test fun cameraRequiresExplicitConsentAndVisibleLiveUnheldCall() {
        assertTrue(cameraMayShare(true, true, false, false))
        assertFalse(cameraMayShare(false, true, false, false))
        assertFalse(cameraMayShare(true, false, false, false))
        assertFalse(cameraMayShare(true, true, true, false))
        assertFalse(cameraMayShare(true, true, false, true))
    }
    @Test fun returningFromBackgroundOrHoldDoesNotRestoreWithdrawnConsent() {
        val withdrawn = cameraMayShare(true, false, false, false)
        assertFalse(cameraMayShare(withdrawn, true, false, false))
        val held = cameraMayShare(true, true, true, false)
        assertFalse(cameraMayShare(held, true, false, false))
    }
    @Test fun providerResetCanStopCameraButNeverGrantConsent() {
        assertTrue(cameraStopRequested(json("type" to "camera_state", "enabled" to false, "reason" to "provider_reconnected")))
        assertFalse(cameraStopRequested(json("type" to "camera_state", "enabled" to true)))
        assertFalse(cameraStopRequested(json("type" to "camera_state", "enabled" to "false")))
        assertFalse(cameraStopRequested(json("type" to "state", "enabled" to false)))
    }
}
