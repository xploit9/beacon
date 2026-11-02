package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSearchResultsTest {
    private val routes = listOf("Connection", "Skills", "Memory", "Appearance")
    private fun shown(vararg overrides: Pair<String, Triple<String, String, String>>) = routes.map { route -> overrides.toMap()[route] ?: Triple(route, "", "") }

    @Test fun aBlankQueryListsNothingBecauseTheOverviewShowsTheHubInstead() {
        assertEquals(emptyList<String>(), settingsSearchResults(routes, shown(), ""))
        assertEquals(emptyList<String>(), settingsSearchResults(routes, shown(), "   "))
    }

    @Test fun aQueryKeepsTheRoutesInOrderAndMatchesWhatThePhoneShows() {
        assertEquals(listOf("Skills"), settingsSearchResults(routes, shown(), "skill"))
        // A phone in another language shows other words; they match, and so do the English ones.
        val arabic = shown("Skills" to Triple("المهارات", "المثبتة", ""))
        assertEquals(listOf("Skills"), settingsSearchResults(routes, arabic, "المهارات"))
        assertEquals(listOf("Skills"), settingsSearchResults(routes, arabic, "skill"))
        assertEquals(listOf("Connection", "Memory"), settingsSearchResults(routes, shown(), "sign out").plus(settingsSearchResults(routes, shown(), "knows")))
    }

    @Test fun textsNotYetWorkedOutMatchNothing() {
        assertEquals(emptyList<String>(), settingsSearchResults(routes, emptyList(), "skill"))
    }
}
