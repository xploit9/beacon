package dev.hermes.android.push
import dev.hermes.android.core.Scope
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class NotificationContentTest {
    @Test fun notificationCategoriesHaveIndependentChannelIdentities() {
        assertEquals("hermes-messages",notificationChannel("completion"))
        assertEquals("hermes-tasks",notificationChannel("task_result"))
        assertEquals("hermes-approvals",notificationChannel("approval"))
        assertEquals("hermes-approvals",notificationChannel("clarify"))
        assertEquals(CypherMessagingService.INCOMING_CHANNEL,notificationChannel("incoming_call"))
        assertEquals(4,listOf("completion","task_result","approval","incoming_call").map(::notificationChannel).toSet().size)
    }
    @Test fun deliberateChannelMuteOrSoundChoiceSurvivesMigration() {
        assertFalse(preserveIncomingChannel(false, false))
        assertTrue(preserveIncomingChannel(true, false))
        assertTrue(preserveIncomingChannel(false, true))
        assertTrue(preserveIncomingChannel(true, true))
    }
    @Test fun typesAreDistinctAndUnknownDoesNotInventContext() {
        val types = listOf("completion", "approval", "clarify", "task_result", "test", "incoming_call", "release")
        assertEquals(types.size, types.map { notificationFallback(it).first }.toSet().size)
        assertEquals(notificationFallback(""), notificationFallback("forged title"))
        assertFalse(notificationFallback("task_result").second.contains("finished"))
    }
    @Test fun boundedArabicTextAndUnicodeRemainReadable() {
        assertEquals("مرحبا بالعالم", boundedNotificationText("مرحبا\n\tبالعالم\u0000", 120))
        val long = boundedNotificationText("موضوع ".repeat(200), 120)
        assertEquals(120, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("a".repeat(118) + "…", boundedNotificationText("a".repeat(118) + "😀abc", 120))
    }
    @Test fun eventOwnerProfileSourceConversationAndKindMustMatch() {
        val scope = Scope("https://localhost:8796", "owner", "default")
        fun route() = JSONObject().put("event_id", "event").put("owner_id", "owner").put("profile", "default").put("source_ref", "source").put("conversation_ref", "chat").put("kind", "completion")
        assertTrue(matchesNotificationRoute(route(), "source", "event", scope, "chat", "completion"))
        listOf("event_id", "owner_id", "profile", "source_ref", "conversation_ref", "kind").forEach {
            assertFalse(it, matchesNotificationRoute(route().put(it, "forged"), "source", "event", scope, "chat", "completion"))
        }
    }

    @Test fun onlyAnAuthorizedEmailAlertTaskResultEnablesImportanceFeedback() {
        val scope = Scope("https://localhost:8796", "owner", "default")
        fun route(kind: String = "task_result", emailAlert: Boolean = true) = JSONObject()
            .put("event_id", "event").put("owner_id", "owner").put("profile", "default")
            .put("source_ref", "source").put("conversation_ref", "chat").put("kind", kind)
            .put("email_alert", emailAlert)

        assertTrue(isEmailAlertNotificationRoute(route(), "source", "event", scope, "chat"))
        assertFalse(isEmailAlertNotificationRoute(route(emailAlert = false), "source", "event", scope, "chat"))
        assertFalse(isEmailAlertNotificationRoute(route(kind = "completion"), "source", "event", scope, "chat"))
        assertFalse(isEmailAlertNotificationRoute(route(), "other", "event", scope, "chat"))
    }
    @Test fun completionNotificationIsSilentOnlyWhileCypherIsVisible() {
        assertTrue(silentPushNotification("completion", true))
        assertFalse(silentPushNotification("completion", false))
        listOf("failure", "approval", "clarify", "task_result", "test", "incoming_call").forEach {
            assertFalse(it, silentPushNotification(it, true))
        }
    }
}
