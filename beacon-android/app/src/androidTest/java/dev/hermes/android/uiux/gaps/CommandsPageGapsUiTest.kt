package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.ui.NativeAdministration
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Commands page on a device: loading, failed, empty and filled states, search with Clear search, Refresh, and what a tap on a command does. */
class CommandsPageGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val offline = "Reconnect to refresh this page."
    private val key = "slash:catalog"
    private val catalog = json("commands" to JSONArray()
        .put(json("name" to "tide", "description" to "Show the tide table"))
        .put(json("name" to "moon", "description" to "Show the moon phase"))
        .put(json("name" to "redraw", "description" to "Repaint the screen")))

    private fun show(vm: BeaconViewModel) {
        compose.showPage { NativeAdministration(vm, "Commands") }
        compose.waitForIdle()
    }
    private fun serve(vm: BeaconViewModel, payload: org.json.JSONObject) = compose.runOnIdle { vm.storeData(key, payload) }
    private fun search() = compose.onNode(hasSetTextAction() and hasText("Search commands"))

    @Test fun theFirstReadShowsPlaceholderRowsNotAnEmptyOrFailedPage() = withClientlessVm(compose) { vm ->
        show(vm)
        compose.runOnIdle { vm.forget(key) }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithText("No matching commands").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("commands-list").assertDoesNotExist()
    }

    @Test fun aFailedReadSaysWhyAndRetryReadsAgainWithoutShowingAnEmptyList() = withClientlessVm(compose) { vm ->
        show(vm)
        compose.onNodeWithText(offline).assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.onNodeWithText("No matching commands").assertDoesNotExist()
        serve(vm, json("_error" to "Synthetic commands failure"))
        compose.onNodeWithText("Synthetic commands failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.onNodeWithText("Synthetic commands failure").assertDoesNotExist()
        compose.onNodeWithText(offline).assertIsDisplayed()
    }

    @Test fun theListHidesTerminalOnlyCommandsAndSearchNarrowsIt() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, catalog)
        compose.onNodeWithTag("commands-list").assertIsDisplayed()
        compose.onNodeWithText("/tide").assertIsDisplayed()
        compose.onNodeWithText("Show the moon phase").assertIsDisplayed()
        compose.onNodeWithText("/redraw").assertDoesNotExist()
        search().performTextReplacement("moon")
        compose.onNodeWithText("/moon").assertIsDisplayed()
        compose.onNodeWithText("/tide").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("moon")).performTextReplacement("TABLE")
        compose.onNodeWithText("/tide").assertIsDisplayed()
        compose.onNodeWithText("/moon").assertDoesNotExist()
    }

    @Test fun aSearchWithNoMatchOffersClearSearchWhichBringsTheListBack() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, catalog)
        search().performTextReplacement("zzz")
        compose.onNodeWithText("No matching commands").assertIsDisplayed()
        compose.onNodeWithTag("commands-list").assertDoesNotExist()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("No matching commands").assertDoesNotExist()
        compose.onNodeWithText("/tide").assertIsDisplayed()
        compose.onNodeWithText("/moon").assertIsDisplayed()
    }

    @Test fun anEmptyCatalogSaysNoMatchingCommandsWithoutAClearAction() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("commands" to JSONArray()))
        compose.onNodeWithText("No matching commands").assertIsDisplayed()
        compose.onNodeWithText("Clear search").assertDoesNotExist()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test fun refreshReadsAgainAndWaitsWhileAReadRuns() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, json("_error" to "Synthetic commands failure"))
        compose.onNodeWithContentDescription("Refresh commands").assertIsEnabled().performClick()
        compose.onNodeWithText("Synthetic commands failure").assertDoesNotExist()
        serve(vm, catalog)
        compose.runOnIdle { vm.refreshingFlow.value = setOf(key) }
        compose.onNodeWithContentDescription("Refresh commands").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithText("/tide").assertIsDisplayed()
        compose.runOnIdle { vm.refreshingFlow.value = emptySet() }
        compose.onNodeWithContentDescription("Refresh commands").assertIsEnabled()
    }

    @Test fun aTappedCommandIsPreparedInChatAndNeverSent() = withClientlessVm(compose) { vm ->
        show(vm)
        serve(vm, catalog)
        compose.onNode(hasClickAction() and hasText("/tide")).performClick()
        compose.waitUntil(5_000) { vm.composer.value.text == "/tide " }
        assertEquals("/tide ", vm.composer.value.text)
        assertEquals("Chat", vm.ui.value.tab)
    }
}
