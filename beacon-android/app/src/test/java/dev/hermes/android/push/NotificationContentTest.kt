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
        assertEquals(BeaconMessagingService.INCOMING_CHANNEL,notificationChannel("incoming_call"))
        assertEquals(4,listOf("completion","task_result","approval","incoming_call").map(::notificationChannel).toSet().size)
    }
    @Test fun typesAreDistinctAndUnknownDoesNotInventContext() {
        val types = listOf("completion", "approval", "clarify", "task_result", "test", "incoming_call")
        assertEquals(types.size, types.map { notificationFallback(it).first }.toSet().size)
        assertEquals(notificationFallback(""), notificationFallback("forged title"))
        assertFalse(notificationFallback("task_result").second.contains("finished"))
    }
    @Test fun everyActivityFallbackAndReviewActionHasALocalizedEntry() {
        val file = java.io.File("src/main/assets/native-locales.json")
        val catalogs = JSONObject(file.readText())
        val keys = ActivityNotificationStrings.toMutableSet()
        keys += listOf("Open Beacon to answer.", "Open Beacon to review the request.", "Review notification action",
            "Beacon activity", "Open Beacon to review.", "Open Beacon to view private activity.", "Your turn", "Your answer", "Answer")
        for (kind in listOf("working", "failure", "completion", "approval", "clarify", "task_result", "test", "incoming_call", "")) {
            notificationFallback(kind) { keys += it; it }
        }
        for (lang in dev.hermes.android.ui.NativeLanguages.keys) {
            val words = catalogs.getJSONObject(lang)
            for (key in keys) assertTrue("$lang: $key", words.optString(dev.hermes.android.ui.normalized(key)).isNotBlank())
        }
        // Arabic alert wording is unverified pending native-speaker review.
        val arabic = catalogs.getJSONObject("ar")
        assertNotEquals(arabic.getString("review"), arabic.getString("review notification action"))
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
    /** R3-F8: the app's own language wins over the phone's, so an in-call question is said in Arabic when Beacon is. */
    @Test fun anAlertsLanguageFollowsTheAppsOwnLocale() {
        val arabic = java.util.Locale.forLanguageTag("ar")
        assertEquals("ar", alertLanguage(arabic, java.util.Locale.ENGLISH))
        assertEquals("en", alertLanguage(java.util.Locale.ENGLISH, arabic))
        // No per-app language set: the phone's own, as before.
        assertEquals("ar", alertLanguage(null, arabic))
        // A language Beacon does not have falls back to English words rather than a half-translated alert.
        assertEquals("en", alertLanguage(java.util.Locale.forLanguageTag("xx"), java.util.Locale.ENGLISH))
    }

    /** R3-F6: a prompt is never left in the group Android aggregates and silences, and the in-call channel stays quiet. */
    @Test fun aPromptKeepsItsOwnAlertingGroupOutsideTheAggregatedShade() {
        assertNotEquals("Its own group, never Android's Aggregate_AlertingSection", "", PROMPT_GROUP)
        assertTrue("a prompt's group is its own, never the quiet summary's", PROMPT_GROUP != ALERT_GROUP)
        assertEquals("the in-call prompt keeps its own quiet channel", IN_CALL_PROMPT_CHANNEL, promptChannelFor("hermes-prompts-v2", ownerChanged = false, inCall = true))
        assertEquals("an owner's own channel choice stands", "hermes-prompts-v2", promptChannelFor("hermes-prompts-v2", ownerChanged = true, inCall = true))
        assertEquals("outside a call the prompt channel is used", "hermes-prompts-v2", promptChannelFor("hermes-prompts-v2", ownerChanged = false, inCall = false))
    }

    @Test fun completionNotificationIsSilentOnlyWhileBeaconIsVisible() {
        // R3-F4: a thread post is a chat's message too: quiet while Beacon is open, like a reply.
        listOf("completion", "task_result").forEach {
            assertTrue(it, silentPushNotification(it, true))
            assertFalse(it, silentPushNotification(it, false))
        }
        listOf("failure", "approval", "clarify", "test", "incoming_call").forEach {
            assertFalse(it, silentPushNotification(it, true))
        }
    }
}
