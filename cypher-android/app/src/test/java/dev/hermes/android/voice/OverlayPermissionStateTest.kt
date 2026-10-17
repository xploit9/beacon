package dev.hermes.android.voice

import org.junit.Assert.*
import org.junit.Test

class OverlayPermissionStateTest {
    private val call=CallState(active=true,callId="current")
    @Test fun firstCallHomeAndReturnAllRetainMissingPermissionUntilGranted() {
        val first=call.withOverlayPermission(false)
        assertTrue(first.overlayPermissionMissing)
        val home=first.copy(minimized=true).withOverlayPermission(false)
        assertTrue(home.overlayPermissionMissing)
        val returned=home.copy(minimized=false).withOverlayPermission(false)
        assertTrue(returned.overlayPermissionMissing)
        assertFalse(returned.withOverlayPermission(true).overlayPermissionMissing)
    }
    @Test fun revocationIsProjectedAndEndedOrUnidentifiedCallsHaveNoPrompt() {
        assertTrue(call.withOverlayPermission(true).withOverlayPermission(false).overlayPermissionMissing)
        assertFalse(call.copy(active=false).withOverlayPermission(false).overlayPermissionMissing)
        assertFalse(call.copy(callId="").withOverlayPermission(false).overlayPermissionMissing)
    }
    @Test fun homeBeforeServiceAdmissionStillNeedsBubbleOnceCallIsIdentified() {
        val pending=CallState(active=true,state="connecting")
        assertFalse(backgroundNeedsBubble(pending,false))
        val admitted=pending.copy(callId="current")
        assertTrue(backgroundNeedsBubble(admitted,false))
        assertTrue("Camera release must not block background call controls",backgroundNeedsBubble(admitted.copy(cameraStopPending=true),false))
        assertFalse(backgroundNeedsBubble(admitted,true))
        assertFalse(backgroundNeedsBubble(admitted.copy(active=false),false))
    }
}
