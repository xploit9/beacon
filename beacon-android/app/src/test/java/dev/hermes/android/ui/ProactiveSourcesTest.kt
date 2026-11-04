package dev.hermes.android.ui

import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The owner chooses which sources the watch reads: what Hermes sends, what the sheet shows, what a tap saves. */
class ProactiveSourcesTest {
    private fun choice(name: String, watched: Boolean) = json("name" to name, "watched" to watched)
    private fun status(all: Boolean, vararg rows: JSONObject) = json("sources_all" to all, "source_choices" to JSONArray(rows.toList()))
    private val sources = listOf(ProactiveSource("Hermes", true), ProactiveSource("github", true), ProactiveSource("google calendar", true))

    @Test fun choicesComeFromTheStatusAndAnOlderPluginSendsNone() {
        val record = status(false, choice("gmail", true), choice("github", false))
        assertEquals(listOf(ProactiveSource("gmail", true), ProactiveSource("github", false)), proactiveSourceChoices(record))
        // An older plugin sends plain labels only: the row stays a read-only list.
        assertNull(proactiveSourceChoices(json("sources" to JSONArray(listOf("Gmail")))))
        assertNull(proactiveSourceChoices(json("source_choices" to "gmail")))
        assertEquals(emptyList<ProactiveSource>(), proactiveSourceChoices(json("source_choices" to JSONArray())))
    }

    @Test fun choicesDropBlankDuplicateAndMalformedRows() {
        val rows = JSONArray().put(choice(" gmail ", true)).put(choice("gmail", false)).put(choice("", true)).put(choice("  ", true))
            .put("github").put(JSONObject.NULL).put(json("watched" to true)).put(json("name" to "news"))
        // A row without a verdict is read as watched: that is what the watch does with a source nobody turned off.
        assertEquals(listOf(ProactiveSource("gmail", true), ProactiveSource("news", true)),
            proactiveSourceChoices(json("source_choices" to rows)))
    }

    @Test fun theViewIsWhatHermesSentUntilTheOwnerTapsAndThenTheTap() {
        val record = status(true, choice("Hermes", true), choice("github", true), choice("google calendar", true))
        assertEquals(ProactiveSourcesView(sources, all = true), proactiveSourcesView(record))
        assertNull(proactiveSourcesView(json("sources" to JSONArray(listOf("Gmail")))))

        val picked = proactiveSourcesView(record, pick = listOf("github"))
        assertFalse(picked!!.all)
        assertEquals(listOf(false, true, false), picked.sources.map { it.watched })
        // An empty pick is "All sources" again.
        val everything = proactiveSourcesView(status(false, choice("Hermes", false), choice("github", true)), pick = emptyList())
        assertTrue(everything!!.all && everything.sources.all { it.watched })
        assertFalse(proactiveSourcesView(status(false, choice("github", true)))!!.all)
        // No verdict from Hermes at all is the default state.
        assertTrue(proactiveSourcesView(json("source_choices" to JSONArray().put(choice("github", true))))!!.all)
    }

    @Test fun theRowReadsAllSourcesOrTheFirstTwoChosenNamesAndACount() {
        assertEquals("All sources", proactiveWatchSummary(ProactiveSourcesView(sources, all = true), "All sources"))
        val some = ProactiveSourcesView(listOf(ProactiveSource("gmail", true), ProactiveSource("github", false), ProactiveSource("google calendar", true)), all = false)
        assertEquals("Gmail, Google calendar", proactiveWatchSummary(some, "All sources"))
        val many = ProactiveSourcesView(listOf("a", "b", "c", "d").map { ProactiveSource(it, true) }, all = false)
        assertEquals("A, B +2", proactiveWatchSummary(many, "All sources"))
    }

    @Test fun allSourcesSavesNothingChosenAndTurningItOffNamesEverySource() {
        val all = ProactiveSourcesView(sources, all = true)
        assertEquals(listOf("Hermes", "github", "google calendar"), proactiveSourcesAfterAll(all, on = false))
        assertEquals(emptyList<String>(), proactiveSourcesAfterAll(ProactiveSourcesView(sources.map { it.copy(watched = it.name == "github") }, all = false), on = true))
    }

    @Test fun aSourceSwitchSavesTheWatchedNamesInOrderAndNeverTheLastOneOff() {
        val some = ProactiveSourcesView(sources.map { it.copy(watched = it.name != "github") }, all = false)
        assertEquals(listOf("Hermes", "github", "google calendar"), proactiveSourcesAfterToggle(some, "github", on = true))
        assertEquals(listOf("Hermes"), proactiveSourcesAfterToggle(some, "google calendar", on = false))
        val one = ProactiveSourcesView(sources.map { it.copy(watched = it.name == "github") }, all = false)
        // None chosen would mean every source, so the last one stays on.
        assertNull(proactiveSourcesAfterToggle(one, "github", on = false))
        // With All sources on, switching one off keeps every other source; switching one on changes nothing.
        assertEquals(listOf("Hermes", "google calendar"), proactiveSourcesAfterToggle(ProactiveSourcesView(sources, all = true), "github", on = false))
        assertNull(proactiveSourcesAfterToggle(ProactiveSourcesView(sources, all = true), "github", on = true))
        assertNull(proactiveSourcesAfterToggle(some, "unknown", on = true))
    }

    @Test fun theSaveCarriesOnlyTheChosenNames() {
        val body = proactiveSourcesBody(listOf("gmail", "github"))
        assertEquals(setOf("sources"), body.keys().asSequence().toSet())
        assertEquals(listOf("gmail", "github"), List(body.getJSONArray("sources").length()) { body.getJSONArray("sources").getString(it) })
        assertEquals(0, proactiveSourcesBody(emptyList()).getJSONArray("sources").length())
    }

    @Test fun theNewTextIsInEveryLanguage() {
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        assertEquals(16, catalog.length())
        for (language in catalog.keys()) {
            val text = catalog.getJSONObject(language).optString("all sources")
            assertTrue("$language is missing \"All sources\"", text.isNotBlank())
            if (language != "en") assertNotEquals("$language shows it in English", "All sources", text) else assertEquals("All sources", text)
        }
    }
}
