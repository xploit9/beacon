package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class CallbackRingPolicyTest {
    @Test fun nativeCallbackWaitsSilentlyDuringCypherAndOtherTelecomCalls() {
        assertTrue(callbackMayAlert(false,true))
        assertFalse(callbackMayAlert(true,true))
        assertFalse(callbackMayAlert(false,false))
        assertFalse(callbackMayAlert(true,false))
    }
}
