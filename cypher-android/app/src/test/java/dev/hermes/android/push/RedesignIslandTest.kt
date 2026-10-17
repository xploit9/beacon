package dev.hermes.android.push

import dev.hermes.android.core.*
import org.junit.Assert.*
import org.junit.Test

class RedesignIslandTest {
    @Test fun notificationDeadlineCannotOverflowOrExtendAnUrgentRequest() {
        val now=1_800_000_000_250L
        assertEquals(299750L,nativeActivityTimeout("approval",Long.MAX_VALUE,now))
        assertEquals(14399750L,nativeActivityTimeout("working",Long.MAX_VALUE,now))
        assertEquals(0L,nativeActivityTimeout("clarify",now/1000,now))
        assertEquals(1750L,nativeActivityTimeout("clarify",now/1000+2,now))
        val command="printf 'a  b'\n  next"
        assertEquals(command,boundedNotificationText(command,640,preserveWhitespace=true))
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
        assertEquals("Cypher · Working", notificationFallback("working").first)
        assertTrue(notificationFallback("approval").second.contains("approval"))
        assertTrue(notificationFallback("clarify").second.contains("answer"))
    }
    @Test fun denyStaysVisibleWhenNativeOffersFourApprovalChoices() {
        val route = json("preview" to json("complete" to true),"actions" to org.json.JSONArray(listOf("once", "session", "always", "deny").map { json("value" to it) }))
        assertEquals(listOf("once", "session", "deny"), nativeVisibleActions(route, "approval").map { it.string("value") })
    }
}
