package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.data.NativeBot
import dev.hermes.android.data.NativeBotSummary
import dev.hermes.android.data.NativeBotsState
import dev.hermes.android.ui.NativeBotSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The Profiles page in the states the other tests skip: a failed list with Retry, and the default-model editor with no models, no match, and a saving model. */
class ProfilesModelEditorGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val work = NativeBot("work", "Work bot", "Route work tasks", "Use the approved work inbox.", "provider-a", "model-a", "rev")
    private val profiles = listOf(NativeBotSummary("default", "Default", "Daily help"), NativeBotSummary("work", "Work", ""))
    private class Calls { var refresh = 0; var cancel = 0; var save = 0; val drafts = mutableListOf<Pair<String, String>>() }

    private fun show(initial: NativeBotsState, models: List<Pair<String, String>> = emptyList(), calls: Calls = Calls()): () -> NativeBotsState {
        var state by mutableStateOf(initial)
        compose.showPage {
            NativeBotSettings(state, {}, {}, {}, { provider, model -> calls.drafts += provider to model; state = state.copy(draftProvider = provider, draftModel = model) },
                { calls.save++ }, { calls.cancel++ }, { _, _ -> }, { calls.refresh++ }, models = models)
        }
        return { state }
    }

    // The profile list

    @Test fun aFailedListSaysWhyKeepsTheRowsAndRetryReadsAgain() {
        val calls = Calls()
        show(NativeBotsState(profiles = profiles, error = "Synthetic profiles failure"), calls = calls)
        compose.onNodeWithText("Synthetic profiles failure").assertIsDisplayed()
        compose.onNodeWithTag("profile-open:default").assertIsDisplayed()
        compose.onNodeWithTag("profile-retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls.refresh) }
    }

    @Test fun anEmptyFailedListShowsTheErrorInsteadOfNoProfiles() {
        show(NativeBotsState(error = "Synthetic profiles failure"))
        compose.onNodeWithText("Synthetic profiles failure").assertIsDisplayed()
        compose.onNodeWithText("No profiles").assertDoesNotExist()
        compose.onNodeWithTag("profile-retry").assertIsDisplayed()
    }

    @Test fun whileTheListReloadsTheRowsAreOffUnderAThinBar() {
        show(NativeBotsState(profiles = profiles, error = "Synthetic profiles failure", loading = true))
        compose.onNodeWithTag("profile-open:default").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
    }

    // The default-model editor

    private val editing = NativeBotsState(selected = work, editing = true, draftProvider = "provider-a", draftModel = "model-a")

    @Test fun withNoModelsTheEditorSaysSoAndPointsToTheComputer() {
        show(editing, models = emptyList())
        compose.onNodeWithText("No models available").assertIsDisplayed()
        compose.onNodeWithText("Set up a provider on your computer").assertIsDisplayed()
        compose.onNodeWithText("Clear search").assertDoesNotExist()
        compose.onNodeWithTag("profile-model-save").assertIsNotEnabled()
    }

    @Test fun aSearchWithNoMatchOffersClearSearchWhichBringsTheModelsBack() {
        show(editing, models = listOf("provider-a" to "model-a", "provider-b" to "model-b"))
        compose.onNodeWithTag("profile-model-option:provider-a:model-a").assertIsDisplayed()
        compose.onNodeWithTag("profile-model-search").performTextReplacement("zzz")
        compose.onNodeWithText("No matching models").assertIsDisplayed()
        compose.onNodeWithTag("profile-model-option:provider-a:model-a").assertDoesNotExist()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("No matching models").assertDoesNotExist()
        compose.onNodeWithTag("profile-model-option:provider-a:model-a").assertIsDisplayed()
        compose.onNodeWithTag("profile-model-option:provider-b:model-b").assertIsDisplayed()
    }

    @Test fun pickingAModelEnablesSaveAndSaveAndCancelCallBack() {
        val calls = Calls()
        val read = show(editing, models = listOf("provider-a" to "model-a", "provider-b" to "model-b"), calls = calls)
        compose.onNodeWithTag("profile-model-save").assertIsNotEnabled()
        compose.onNodeWithTag("profile-model-option:provider-b:model-b").performClick()
        compose.runOnIdle { assertEquals(listOf("provider-b" to "model-b"), calls.drafts); assertEquals("model-b", read().draftModel) }
        compose.onNodeWithTag("profile-model-save").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("profile-model-cancel").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, calls.save); assertEquals(1, calls.cancel) }
    }

    @Test fun whileSavingEveryEditorControlIsOff() {
        show(editing.copy(draftModel = "model-b", saving = true), models = listOf("provider-a" to "model-a", "provider-b" to "model-b"))
        compose.onNodeWithTag("profile-model-save").assertIsNotEnabled()
        compose.onNodeWithTag("profile-model-cancel").assertIsNotEnabled()
        compose.onNodeWithTag("profile-model-search").assertIsNotEnabled()
        compose.onNodeWithTag("profile-model-custom").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
    }

    @Test fun editingHasNoRetryEvenWithAnError() {
        show(editing.copy(error = "Synthetic model failure"), models = listOf("provider-a" to "model-a"))
        compose.onNodeWithText("Synthetic model failure").assertIsDisplayed()
        compose.onNodeWithTag("profile-retry").assertDoesNotExist()
    }

    @Test fun moreThanEightModelsFoldBehindMoreModels() {
        val models = (1..12).map { "provider-a" to "model-$it" }
        show(editing, models = models)
        compose.onNodeWithTag("profile-more-models").performScrollTo().assertTextContains("More models (4)")
        compose.onNodeWithTag("profile-model-option:provider-a:model-12").assertDoesNotExist()
        compose.onNodeWithTag("profile-more-models").performClick()
        compose.onNodeWithTag("profile-model-option:provider-a:model-12").assertExists()
    }
}
