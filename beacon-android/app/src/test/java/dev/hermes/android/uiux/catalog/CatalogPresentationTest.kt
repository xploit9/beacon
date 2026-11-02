package dev.hermes.android.uiux.catalog

import dev.hermes.android.core.json
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.ui.formatTokenCount
import dev.hermes.android.ui.memoryCounterVisible
import dev.hermes.android.ui.memoryEntries
import dev.hermes.android.ui.pinSelectedModel
import dev.hermes.android.ui.sessionUsageFigures
import dev.hermes.android.ui.shortProviderLabel
import dev.hermes.android.ui.skillDisplayName
import dev.hermes.android.ui.skillMatches
import dev.hermes.android.ui.skillSections
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class CatalogPresentationTest {
    private fun model(id: String) = json("id" to id, "label" to id)
    private fun skill(name: String, category: String = "general", enabled: Boolean = false, needs: List<String> = emptyList(), essential: Boolean = false, description: String = "") =
        NativeSkill(name, description, category, enabled, "agent", needs, essential)

    // Model sheet

    @Test fun aProviderLabelLosesItsQualifier() {
        assertEquals("OpenAI Codex", shortProviderLabel("OpenAI Codex (ChatGPT subscription)"))
        assertEquals("Nous Portal", shortProviderLabel("Nous Portal — Hermes models"))
        assertEquals("GitHub Copilot", shortProviderLabel("GitHub Copilot"))
        assertEquals("(local)", shortProviderLabel("(local)"))
    }

    @Test fun theChatsModelLeadsItsOwnProviderOnly() {
        val models = listOf(model("a"), model("b"), model("c"))
        assertEquals(listOf("b", "a", "c"), pinSelectedModel(models, "b", inCurrentProvider = true).map { it.getString("id") })
        assertEquals("Another provider keeps Hermes' order", listOf("a", "b", "c"), pinSelectedModel(models, "b", inCurrentProvider = false).map { it.getString("id") })
        assertEquals("No chat model pins nothing", listOf("a", "b", "c"), pinSelectedModel(models, "", inCurrentProvider = true).map { it.getString("id") })
        assertEquals("A model the provider does not list changes nothing", listOf("a", "b", "c"), pinSelectedModel(models, "zzz", inCurrentProvider = true).map { it.getString("id") })
    }

    // Session usage

    @Test fun usageFiguresReadTheNamesHermesUses() {
        val figures = sessionUsageFigures(json("input_tokens" to 100, "output_tokens" to 20, "context_window" to 32000, "total_tokens" to 8000, "model" to "m"))
        assertEquals(100L, figures.input); assertEquals(20L, figures.output); assertEquals(32000L, figures.window); assertEquals(8000L, figures.used)
        assertEquals(25, figures.percent)
        assertEquals("What the figures use stays out of Details", setOf("input_tokens", "output_tokens", "context_window", "total_tokens"), figures.consumed)
        val short = sessionUsageFigures(json("input" to "12", "output" to 3, "context_max" to 1000, "context_used" to 2000))
        assertEquals(12L, short.input); assertEquals(1000L, short.window)
        assertEquals("Over the window reads as full, not beyond", 100, short.percent)
    }

    @Test fun usageWithoutAWindowHasNoPercentAndNothingToShowIsEmpty() {
        val noWindow = sessionUsageFigures(json("input_tokens" to 5))
        assertNull(noWindow.percent); assertNull(noWindow.window); assertNull(noWindow.used)
        val empty = sessionUsageFigures(JSONObject())
        assertTrue(empty.input == null && empty.output == null && empty.window == null && empty.consumed.isEmpty())
        assertNull("A zero window cannot be divided by", sessionUsageFigures(json("context_window" to 0, "total_tokens" to 4)).percent)
    }

    @Test fun tokenCountsAreShort() {
        val us = Locale.US
        assertEquals("100", formatTokenCount(100, us))
        assertEquals("1.2K", formatTokenCount(1234, us))
        assertEquals("32K", formatTokenCount(32000, us))
        assertEquals("128K", formatTokenCount(128_000, us))
        assertEquals("1.2M", formatTokenCount(1_200_000, us))
        assertEquals("0", formatTokenCount(0, us))
    }

    // Skills

    @Test fun slugsReadAsNames() {
        assertEquals("Apple Notes", skillDisplayName("apple-notes"))
        assertEquals("Hermes Agent", skillDisplayName("hermes_agent"))
        assertEquals("Inbox", skillDisplayName("inbox"))
        assertEquals("-", skillDisplayName("-"))
        assertEquals("Autonomous AI Agents", skillDisplayName("autonomous-ai-agents"))
        assertEquals("MCP Servers", skillDisplayName("mcp_servers"))
    }

    @Test fun filtersAndSearchSelectSkills() {
        val ready = skill("ready-skill", enabled = true, description = "Reads the work inbox")
        val needs = skill("needs-skill", needs = listOf("Set WORK_API_KEY on the host"))
        assertTrue(skillMatches(ready, "all", "")); assertTrue(skillMatches(needs, "all", ""))
        assertTrue(skillMatches(ready, "on", "")); assertFalse(skillMatches(needs, "on", ""))
        assertFalse(skillMatches(ready, "setup", "")); assertTrue(skillMatches(needs, "setup", ""))
        assertTrue("The name as shown matches", skillMatches(ready, "all", "ready skill"))
        assertTrue(skillMatches(ready, "all", "INBOX")); assertFalse(skillMatches(ready, "all", "calendar"))
        assertFalse("The filter and the search both have to hold", skillMatches(ready, "setup", "inbox"))
    }

    @Test fun skillsGroupByCategoryOnlyWhenThereAreSeveral() {
        val one = skillSections(listOf(skill("b", enabled = false), skill("a", enabled = true), skill("c", enabled = false)))
        assertEquals(1, one.size); assertNull(one.single().first)
        assertEquals("Enabled first, then by name", listOf("a", "b", "c"), one.single().second.map { it.name })

        val many = skillSections(listOf(skill("x", "productivity"), skill("y", "apple", enabled = true), skill("z", ""), skill("w", "apple")))
        assertEquals(listOf("apple", "productivity", ""), many.map { it.first })
        assertEquals(listOf("y", "w"), many.first().second.map { it.name })
        assertEquals("A skill with no category lands in Other, last", listOf("z"), many.last().second.map { it.name })
    }

    // Memory

    @Test fun notesSplitOnTheirSeparatorAndDropComments() {
        assertEquals(listOf("one", "two"), memoryEntries("one\n§\ntwo"))
        assertEquals(listOf("one", "two"), memoryEntries("<!-- managed -->\none\r\n§\r\ntwo\n"))
        assertEquals("A paragraph break is not a separator", listOf("one\n\ntwo"), memoryEntries("one\n\ntwo"))
        assertTrue(memoryEntries("  \n").isEmpty()); assertTrue(memoryEntries("<!-- only -->").isEmpty())
    }

    @Test fun theLengthCounterAppearsFromEightyPercent() {
        assertFalse(memoryCounterVisible(0, 2000)); assertFalse(memoryCounterVisible(1599, 2000))
        assertTrue(memoryCounterVisible(1600, 2000)); assertTrue(memoryCounterVisible(2500, 2000))
        assertFalse("Without a limit there is nothing to approach", memoryCounterVisible(10, 0))
    }
}
