package dev.hermes.android.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundActivityNotificationsTest {
    @Test fun ordinaryWorkIsQuietWithOrWithoutTheUi() {
        assertTrue("A background start must not make a sound", silentPushNotification("working", false))
        assertTrue("An on-screen start must not make a sound", silentPushNotification("working", true))
    }

    @Test fun meaningfulAlertsKeepTheirExistingPolicy() {
        assertTrue(silentPushNotification("completion", true))
        assertFalse(silentPushNotification("completion", false))
        listOf("failure", "approval", "clarify", "task_result", "test", "incoming_call").forEach { kind ->
            assertFalse(kind, silentPushNotification(kind, false))
            assertFalse(kind, silentPushNotification(kind, true))
        }
    }
}
