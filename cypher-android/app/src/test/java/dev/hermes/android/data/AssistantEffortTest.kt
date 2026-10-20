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
}
