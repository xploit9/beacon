package dev.hermes.android.uiux.hermes

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeBot
import dev.hermes.android.data.NativeBotSummary
import dev.hermes.android.data.NativeBotsState
import dev.hermes.android.ui.NativeBotSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The Profiles page on its own: rows, the profile hero, the default-model editor and the single Delete entry. */
class HermesSettingsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val work = NativeBot("work", "Work bot", "Route work tasks", "Use the approved work inbox.", "custom", "old", "revision")
    private val profiles = listOf(NativeBotSummary("default", "Default", "Daily help"), NativeBotSummary("work", "Work", ""))

    @Test fun profilesAreTappableRowsWithAnActivePillAndNoButtonsPerRow() {
        var opened = ""
        var created = false
        compose.setContent {
            MaterialTheme {
                NativeBotSettings(NativeBotsState(profiles = profiles), { opened = it }, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {},
                    onNewProfile = { created = true }, canDelete = { true }, activeProfile = "default", approvals = { Text("approvals slot") })
            }
        }
        compose.onNodeWithText("approvals slot").assertIsDisplayed()
        compose.onNodeWithText("Your profiles").assertIsDisplayed()
        // The page header is the only "Profiles" on a Settings page.
        compose.onAllNodesWithText("Profiles").assertCountEquals(0)
        compose.onNodeWithText("View profile").assertDoesNotExist()
        compose.onNodeWithText("Delete profile").assertDoesNotExist()
        compose.onAllNodesWithText("Active").assertCountEquals(1)
        compose.onNodeWithText("Daily help").assertIsDisplayed()
        // A profile with no description shows only its name (UX-48): "No purpose" read as a judgement.
        compose.onNodeWithText("No purpose").assertDoesNotExist()
        compose.onNodeWithTag("profile-open:default").assertHasClickAction().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("profile-open:work").performScrollTo().performClick()
        assertEquals("work", opened)
        compose.onNodeWithTag("new-profile").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertTrue(created)
    }

    @Test fun anEmptyListSaysSoInOneLineWithRefresh() {
        var refreshed = false
        compose.setContent { MaterialTheme { NativeBotSettings(NativeBotsState(), {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, { refreshed = true }) } }
        compose.onNodeWithText("No profiles").assertIsDisplayed()
        compose.onNodeWithText("Refresh").performClick()
        assertTrue(refreshed)
    }

    @Test fun firstLoadShowsPlaceholdersAndALaterRefreshKeepsTheRowsUnderAThinBar() {
        var state by mutableStateOf(NativeBotsState(loading = true))
        compose.setContent { MaterialTheme { NativeBotSettings(state, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.runOnIdle { state = NativeBotsState(profiles = profiles, loading = true) }
        compose.onNodeWithTag("settings-skeleton").assertDoesNotExist()
        compose.onNodeWithTag("profile-open:default").assertIsDisplayed()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.runOnIdle { state = state.copy(loading = false) }
        compose.onNodeWithTag("settings-refreshing").assertDoesNotExist()
        compose.onNodeWithTag("profile-open:default").assertIsEnabled()
    }

    @Test fun aProfileOpensWithAHeroAndShortcutsAndHasOneDeleteEntryAtTheEnd() {
        val shortcuts = mutableListOf<Pair<String, String>>()
        var deleted = ""
        compose.setContent {
            MaterialTheme {
                NativeBotSettings(NativeBotsState(selected = work), {}, {}, {}, { _, _ -> }, {}, {}, { profile, page -> shortcuts += profile to page }, {},
                    profileLabel = { "Work profile" }, canDelete = { it == "work" }, onDelete = { deleted = it }, activeProfile = "work", approvals = { Text("approvals slot") })
            }
        }
        compose.onNodeWithTag("profile-hero").assertIsDisplayed()
        compose.onNodeWithText("Work profile").assertIsDisplayed()
        compose.onNodeWithText("Route work tasks").assertIsDisplayed()
        val hero = compose.onNodeWithTag("profile-hero").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Approvals sit right under the hero", hero < compose.onNodeWithText("approvals slot").fetchSemanticsNode().boundsInRoot.top)
        compose.onNodeWithText("Skills for this profile").assertDoesNotExist()
        compose.onNodeWithTag("profile-skills").performScrollTo().performClick()
        compose.onNodeWithTag("profile-memory").performScrollTo().performClick()
        assertEquals(listOf("work" to "Skills", "work" to "Memory"), shortcuts)
        compose.onAllNodesWithText("Profiles & model").assertCountEquals(0)
        compose.onNodeWithTag("delete-profile:work").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals("work", deleted)
        compose.onAllNodesWithText("Delete profile").assertCountEquals(1)
    }

    @Test fun instructionsOpenInPlaceAndTheRowSaysSo() {
        compose.setContent { MaterialTheme { NativeBotSettings(NativeBotsState(selected = work), {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}) } }
        compose.onNodeWithText("Use the approved work inbox.").assertDoesNotExist()
        compose.onNodeWithTag("profile-instructions").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed")).performClick()
        compose.onNodeWithText("Use the approved work inbox.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-instructions").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
    }

    @Test fun theModelEditorPicksACardAndSavesOnlyAChange() {
        var saved = ""
        compose.setContent {
            var state by remember { mutableStateOf(NativeBotsState(selected = work)) }
            MaterialTheme {
                NativeBotSettings(state, {}, {}, { state = state.copy(editing = true, draftProvider = "custom", draftModel = "old") },
                    { provider, model -> state = state.copy(draftProvider = provider, draftModel = model) }, { saved = state.draftModel },
                    { state = state.copy(editing = false) }, { _, _ -> }, {}, models = listOf("custom" to "listed-model", "other" to "another-model"))
            }
        }
        compose.onNodeWithTag("profile-model").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("profile-model-save").performScrollTo().assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("Search models or providers").performScrollTo().performTextInput("listed")
        compose.onNodeWithTag("profile-model-option:other:another-model").assertDoesNotExist()
        compose.onNodeWithTag("profile-model-option:custom:listed-model").performScrollTo().assertHasClickAction().performClick()
        compose.onNodeWithTag("profile-model-option:custom:listed-model").assertIsSelected()
        compose.onNodeWithTag("profile-model-save").performScrollTo().assertIsEnabled().performClick()
        assertEquals("listed-model", saved)
        compose.onNodeWithTag("profile-model-cancel").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("profile-model").performScrollTo().assertIsDisplayed()
    }

    @Test fun aModelIdCanBeTypedAndALongCatalogStartsCollapsedWithTheChosenModelShown() {
        val many = (1..12).map { "lab" to "model-$it" }
        compose.setContent {
            var state by remember { mutableStateOf(NativeBotsState(selected = work, editing = true, draftProvider = "lab", draftModel = "model-12")) }
            MaterialTheme {
                NativeBotSettings(state, {}, {}, {}, { provider, model -> state = state.copy(draftProvider = provider, draftModel = model) }, {}, {}, { _, _ -> }, {}, models = many)
            }
        }
        compose.onNodeWithTag("profile-model-option:lab:model-12").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("profile-model-option:lab:model-9").assertDoesNotExist()
        compose.onNodeWithText("More models (3)").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithTag("profile-model-option:lab:model-9").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-model-custom").performScrollTo().performClick()
        compose.onNodeWithText("Hide model ID").assertIsDisplayed()
        compose.onNodeWithText("Model ID").performScrollTo().performTextReplacement("model-12-x")
        compose.onNodeWithText("model-12-x").assertExists()
    }

    @Test fun anErrorShowsWithRetryOnlyWhenReloadingIsSafe() {
        var refreshed = 0
        var state by mutableStateOf(NativeBotsState(selected = work, error = "Model changed elsewhere. Review before saving."))
        compose.setContent { MaterialTheme { NativeBotSettings(state, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, { refreshed++ }) } }
        compose.onNodeWithText("Model changed elsewhere. Review before saving.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-retry").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(1, refreshed)
        compose.runOnIdle { state = state.copy(editing = true, draftProvider = "custom", draftModel = "old") }
        compose.onNodeWithText("Model changed elsewhere. Review before saving.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("profile-retry").assertDoesNotExist()
    }
}
