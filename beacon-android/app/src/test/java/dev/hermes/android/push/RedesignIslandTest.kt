package dev.hermes.android.push

import dev.hermes.android.core.*
import org.junit.Assert.*
import org.junit.Test

class RedesignIslandTest {
    @Test fun notificationDeadlineCannotOverflowOrExtendAnUrgentRequest() {
        val now=1_800_000_000_250L
        assertEquals(14399750L,nativeActivityTimeout(Long.MAX_VALUE,now))
        assertEquals(0L,nativeActivityTimeout(now/1000,now))
        assertEquals(1750L,nativeActivityTimeout(now/1000+2,now))
        val command="printf 'a  b'\n  next"
        assertEquals(command,boundedNotificationText(command,640,preserveWhitespace=true))
    }
    @Test fun aQuestionOrApprovalAlertLastsAsLongAsHermesWaits() {
        // Hermes' default clarify wait is an hour; the alert ends at the request's own expiry (or the server's activity_end), not at 5 minutes.
        val now=1_800_000_000_250L
        assertEquals(3_599_750L,nativeActivityTimeout(now/1000+3600,now))
        assertEquals(899_750L,nativeActivityTimeout(now/1000+900,now))
        assertEquals(14_399_750L,nativeActivityTimeout(now/1000+86_400,now))  // "working" keeps its 4 h cap
    }
    @Test fun onlyActualWorkAndHumanWaitsAreOngoing() {
        listOf("working", "approval", "clarify").forEach { assertTrue(ongoingNativeKind(it)) }
        listOf("completion", "task_result", "activity_end", "incoming_call", "failed").forEach { assertFalse(ongoingNativeKind(it)) }
    }
    @Test fun notificationActionsCannotInventChoices() {
        val route = json("kind" to "approval","preview" to json("complete" to true),"actions" to org.json.JSONArray().put(json("value" to "once")).put(json("value" to "deny")))
        assertTrue(validNativeAnswer(route, "once"))
        assertFalse(validNativeAnswer(route, "always"))
        assertFalse(validNativeAnswer(route, ""))
        assertFalse(validNativeAnswer(route, "x".repeat(1025)))
        assertTrue(validNativeAnswer(json("kind" to "clarify","preview" to json("complete" to true),"text_reply" to true), "Fixture answer"))
        route.getJSONObject("preview").put("complete",false)
        assertFalse(validNativeAnswer(route,"once"));assertTrue(validNativeAnswer(route,"deny"))
    }
    @Test fun fallbackNeverContainsCommandsOrQuestionContent() {
        assertEquals("Beacon · Working", notificationFallback("working").first)
        assertTrue(notificationFallback("approval").second.contains("approval"))
        assertTrue(notificationFallback("clarify").second.contains("answer"))
    }
    @Test fun denyStaysVisibleWhenNativeOffersFourApprovalChoices() {
        val route = json("preview" to json("complete" to true),"actions" to org.json.JSONArray(listOf("once", "session", "always", "deny").map { json("value" to it) }))
        assertEquals(listOf("once", "session", "deny"), nativeVisibleActions(route, "approval").map { it.string("value") })
    }
    @Test fun aQuestionDuringBeaconsCallPopsUpWithoutSoundAndHasItsSoundAfter() {
        assertEquals(IN_CALL_PROMPT_CHANNEL, promptChannelFor("hermes-prompts-v2", ownerChanged = false, inCall = true))
        assertEquals("hermes-prompts-v2", promptChannelFor("hermes-prompts-v2", ownerChanged = false, inCall = false))
        // The owner changed the prompt channel's importance or sound: their channel stays, in a call too.
        assertEquals("hermes-approvals", promptChannelFor("hermes-approvals", ownerChanged = true, inCall = true))
        // App start removes unknown channels; the in-call one is kept with the owner's settings.
        assertFalse(IN_CALL_PROMPT_CHANNEL in dev.hermes.android.notificationChannelIdsToDelete(setOf(IN_CALL_PROMPT_CHANNEL), dev.hermes.android.CURRENT_NOTIFICATION_CHANNEL_IDS))
    }
    @Test fun theInCallChannelIsNamedInEveryLanguage() {
        val file = listOf(".", "app", "beacon-android/app").map { java.io.File(it, "src/main/assets/native-locales.json") }.first { it.isFile }
        val catalog = org.json.JSONObject(file.readText(Charsets.UTF_8))
        val key = dev.hermes.android.ui.normalized(IN_CALL_PROMPT_CHANNEL_NAME)
        assertEquals(16, catalog.length())
        for (language in catalog.keys()) {
            val name = catalog.getJSONObject(language).optString(key)
            assertTrue("$language names the channel", name.isNotBlank() && name.length <= 40)
            if (language != "en") assertNotEquals("$language is translated", IN_CALL_PROMPT_CHANNEL_NAME, name)
        }
        assertTrue(Regex("[؀-ۿ]").containsMatchIn(catalog.getJSONObject("ar").getString(key)))
    }
}
