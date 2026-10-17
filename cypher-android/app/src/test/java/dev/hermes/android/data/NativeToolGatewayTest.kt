package dev.hermes.android.data

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeToolGatewayTest {
    @Test fun receiptIdentityRejectsPathAndQueryInjection() {
        listOf("", "../other", "a/b", "a?profile=other", "a%2fb", "a\\b", "x".repeat(161)).forEach {
            assertThrows(IllegalArgumentException::class.java) { NativeToolGateway.requireRequestId(it) }
        }
        NativeToolGateway.requireRequestId("gemini_call-123_abc")
    }
}
