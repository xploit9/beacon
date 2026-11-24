package dev.hermes.android.push

import dev.hermes.android.core.Scope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundActivityNotificationsTest {
    @Test fun ordinaryWorkIsQuietWithOrWithoutTheUi() {
        assertTrue("A background start must not make a sound", silentPushNotification("working", false))
        assertTrue("An on-screen start must not make a sound", silentPushNotification("working", true))
    }

    @Test fun meaningfulAlertsKeepTheirExistingPolicy() {
        // R3-F4: a thread post that lands while Beacon is open is listed quietly, as a reply is: no heads-up over the chat being read.
        listOf("completion", "task_result").forEach { kind ->
            assertTrue(kind, silentPushNotification(kind, true))
            assertFalse(kind, silentPushNotification(kind, false))
        }
        listOf("failure", "approval", "clarify", "test", "incoming_call").forEach { kind ->
            assertFalse(kind, silentPushNotification(kind, false))
            assertFalse(kind, silentPushNotification(kind, true))
        }
    }

    // Root's exception: an important alert ("Important from…", told by its route) is not kept quiet by Beacon being open; old or burst alerts stay quiet.
    @Test fun anImportantAlertIsNeverQuietedByBeaconBeingOpen() {
        assertTrue("quiet because Beacon is open", updateStaysQuiet(postedQuiet = true, oldOrBurst = false, important = false))
        assertFalse("important: its words arrive loud", updateStaysQuiet(postedQuiet = true, oldOrBurst = false, important = true))
        assertTrue("an old or burst one stays quiet, important or not", updateStaysQuiet(postedQuiet = true, oldOrBurst = true, important = true))
        assertFalse("a loud one stays loud", updateStaysQuiet(postedQuiet = false, oldOrBurst = false, important = false))
    }

    // R3-F4: the open chat's own reply or post shows in that chat; only the same account, profile and chat count.
    @Test fun onlyTheChatOnScreenTakesItsOwnAlert() {
        val account = Scope("https://hermes.example", "synthetic-owner", "default", nativeProvider = "fixture")
        val shown = account.session("chat-a")
        assertTrue(shownChatNotice(shown, account, "chat-a"))
        assertFalse(shownChatNotice(shown, account, "chat-b"))
        assertFalse(shownChatNotice(shown, account, ""))
        assertFalse("nothing on screen", shownChatNotice(null, account, "chat-a"))
        assertFalse(shownChatNotice(shown, account.copy(profile = "coder"), "chat-a"))
        assertFalse(shownChatNotice(shown, account.copy(owner = "another-owner"), "chat-a"))
    }
}
