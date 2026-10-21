package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssistantEffortTest {
    @Test fun assistantChatStartsAtHighOnlyWhenTheProfileDefaultIsSlower() {
        assertEquals("high", assistantEffortCap("max"))
        assertEquals("high", assistantEffortCap("xhigh"))
        assertNull(assistantEffortCap("high"))
        assertNull(assistantEffortCap("low"))
        assertNull(assistantEffortCap(""))
    }

    @Test fun pinCarriesHermesRevisionPreconditionAndARequestId() {
        val identity = pinMutationIdentity(org.json.JSONObject("""{"session_id":"s","exists":true,"runtime_revision":7,"runtime_generation":2}"""), "rid-1")
        assertEquals("rid-1", identity.getString("request_id"))
        assertEquals(7L, identity.getLong("expected_revision"))
        assertEquals(2L, identity.getLong("expected_generation"))
        for (bad in listOf("""{"exists":false,"runtime_revision":0,"runtime_generation":null}""", """{"exists":true,"runtime_revision":-1,"runtime_generation":0}""", """{"exists":true}"""))
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { pinMutationIdentity(org.json.JSONObject(bad), "rid") }
    }
}
