package dev.hermes.android.assist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Only the platform's assist gesture may start a call from the exported entry; any other caller gets the chat. */
class AssistCallerTest {
    private val own = "dev.hermes.android"
    private val home = "com.hihonor.android.launcher"

    @Test fun theSystemGestureIsTrusted() {
        assertTrue(assistCallerTrusted("android", home, own))
        assertTrue(assistCallerTrusted("com.android.systemui", home, own))
        assertTrue(assistCallerTrusted(home, home, own))
        assertTrue(assistCallerTrusted(own, home, own))
    }

    @Test fun anotherPackageNeverStartsACall() {
        assertFalse(assistCallerTrusted("com.example.other", home, own))
        assertFalse(assistCallerTrusted("dev.hermes.android.chat", home, own))
    }

    @Test fun anUnprovableCallerIsNotTrusted() {
        assertFalse(assistCallerTrusted(null, home, own))
        assertFalse(assistCallerTrusted("", home, own))
        assertFalse(assistCallerTrusted("com.example.other", null, own))
    }
}
