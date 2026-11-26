package dev.hermes.android

import dev.hermes.android.core.json
import dev.hermes.android.data.liveUsageIsEmpty
import dev.hermes.android.data.usageWithStoredTotals
import dev.hermes.android.ui.NewThreadDraft
import dev.hermes.android.ui.chatReasoningLabel
import dev.hermes.android.ui.reasoningLevelLabel
import dev.hermes.android.ui.sessionUsageFigures
import dev.hermes.android.ui.sessionUsageUnreported
import org.junit.Assert.*
import org.junit.Test

/** E3: R2-F18 (the chip's effort survives a reload) and R2-F22 (usage falls back to the stored row). */
class E3PolishTest {
    @Test fun switchingModelOrProviderDoesNotReuseThePreviousEffort() {
        val row = json("model" to "model-a", "model_provider" to "provider-a")
        val open = ChatState(sessionId = "chat-a", raw = row, sessionReasoningEffort = "xhigh")
        assertEquals("xhigh", reloadedEffort(open, "chat-a", row))
        assertEquals("new model", "", reloadedEffort(open, "chat-a", json("model" to "model-b", "model_provider" to "provider-a")))
        assertEquals("new provider", "", reloadedEffort(open, "chat-a", json("model" to "model-a", "model_provider" to "provider-b")))
    }

    @Test fun providerOnlyRowUsesTheCachedRuntimeEffortOnReopenAndColdStart() {
        val row = json("session_id" to "chat-a", "model_config" to """{"provider":"fixture"}""")
        val defaults = "max"
        val runtime = json("value" to "xhigh")
        for (previous in listOf(ChatState(), ChatState(sessionId = "chat-b", sessionReasoningEffort = "low"))) {
            val effort = reloadedEffort(previous, "chat-a", row, runtime)
            assertEquals("Extra high", reasoningLevelLabel(chatReasoningLabel(effort, "", defaults)))
        }
        assertEquals("", reloadedEffort(ChatState(), "chat-b", row, null))
    }

    @Test fun aReloadWithoutAnEffortKeepsTheOpenChatsEffortButNotAnotherChats() {
        val open = ChatState(sessionId = "a", sessionReasoningEffort = "low")
        val row = json("model_config" to "{\"model\":\"m\"}")
        assertEquals("low", reloadedEffort(open, "a", row))
        assertEquals("", reloadedEffort(open, "b", row))
        assertEquals("high", reloadedEffort(open, "a", json("reasoning_effort" to "high")))
    }

    @Test fun zeroLiveUsageTakesTheStoredTotals() {
        val live = json("input" to 0, "output" to 0, "prompt" to 0, "completion" to 0, "context_max" to 200000)
        val row = json("input_tokens" to 35160, "output_tokens" to 1977, "cache_read_tokens" to 180224, "cache_write_tokens" to 0)
        assertTrue(liveUsageIsEmpty(live))
        val merged = usageWithStoredTotals(live, row)
        val figures = sessionUsageFigures(merged)
        assertEquals(35160L, figures.input); assertEquals(1977L, figures.output); assertEquals(200000L, figures.window)
        assertFalse(sessionUsageUnreported(figures, true))
        assertFalse(merged.has("prompt")); assertEquals(180224L, merged.getLong("cache_read_tokens"))
    }

    @Test fun liveUsageWithTokensOrAnEmptyRowIsLeftAlone() {
        assertFalse(liveUsageIsEmpty(json("input" to 5, "output" to 0)))
        val live = json("input" to 0, "output" to 0)
        assertSame(live, usageWithStoredTotals(live, json("input_tokens" to 0, "output_tokens" to 0)))
    }

    /** R3-F2: the detail rows read the same stored totals as the header instead of the recycled runtime's zeros. */
    @Test fun recycledRuntimeDetailRowsShowHermesStoredFigures() {
        // Hermes' own reply for a runtime it no longer holds: every live counter is zero, and only the context window survives.
        val live = json("input_tokens" to 0, "output_tokens" to 0, "cache_read_tokens" to 0, "cache_write_tokens" to 0, "prompt_tokens" to 0,
            "completion_tokens" to 0, "total_tokens" to 0, "reasoning_tokens" to 0, "api_calls" to 0, "context_length" to 200000)
        // The stored row of a chat Q2 measured: 51,708 in, 2,057 out, 212,480 cache read, 17 API calls.
        val row = json("input_tokens" to 51708, "output_tokens" to 2057, "cache_read_tokens" to 212480, "cache_write_tokens" to 0,
            "reasoning_tokens" to 0, "api_call_count" to 17)
        val merged = usageWithStoredTotals(live, row)
        // Hermes counts a prompt as the uncached input plus the cache it read (agent/usage_pricing.py CanonicalUsage.prompt_tokens).
        assertEquals(51708L + 212480L, merged.getLong("prompt_tokens"))
        assertEquals(2057L, merged.getLong("completion_tokens"))
        assertEquals(17L, merged.getLong("api_calls"))
        assertEquals(212480L, merged.getLong("cache_read_tokens"))
        assertEquals(200000L, merged.getLong("context_length"))
        // Details lists what the header's figures did not take. Q2 read Prompt tokens, Completion tokens and Api calls there, so those rows must now
        // show the stored totals instead of the recycled runtime's zeros (R3-F2); the header's own two figures are not repeated.
        val figures = sessionUsageFigures(merged)
        val extra = merged.keys().asSequence().filter { !it.startsWith('_') && it !in figures.consumed }.toList()
        for (key in listOf("prompt_tokens", "completion_tokens", "api_calls"))
            assertTrue("$key is a Details row", key in extra)
        for (key in listOf("input_tokens", "output_tokens"))
            assertFalse("$key is the header's own figure, not a detail", key in extra)
        assertEquals(51708L + 212480L, merged.getLong("prompt_tokens"))
    }

    @Test fun theNewThreadDialogStateIsNotTiedToOneSidebar() {
        NewThreadDraft.from = "sidebar"; NewThreadDraft.name = "News"
        assertEquals("sidebar", NewThreadDraft.from); assertEquals("News", NewThreadDraft.name)
        NewThreadDraft.close()
        assertEquals("", NewThreadDraft.from); assertEquals("", NewThreadDraft.name)
    }
}
