package dev.hermes.android.data

import org.junit.Assert.*
import org.junit.Test

class NativeMissingConversationTest {
    @Test fun missingNativeSessionIsDistinctFromUnknownDeliveryAndAuthFailure() {
        assertTrue(missingNativeConversation(ApiFailure(4007, "session not found")))
        assertTrue(missingNativeConversation(ApiFailure(404, "Not found")))
        assertFalse(missingNativeConversation(ApiFailure(4007, "session_key required")))
        assertFalse(missingNativeConversation(ApiFailure(401, "Sign in again")))
        assertFalse(missingNativeConversation(ApiFailure(503, "Busy")))
        assertFalse(missingNativeConversation(java.io.IOException("Timed out")))
    }
}
