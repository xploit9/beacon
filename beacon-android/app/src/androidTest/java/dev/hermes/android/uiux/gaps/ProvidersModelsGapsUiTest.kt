package dev.hermes.android.uiux.gaps

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.ui.NativeAdministration
import dev.hermes.android.ui.NativeCatalog
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Providers & models on a device: the page's two rows and the model sheet it opens. The ViewModel has no client, so a tap that would change
 * the chat or ask Hermes for models stops at "Open a conversation before continuing." (a notice), which is how these tests see that the tap arrived.
 */
class ProvidersModelsGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val chatRaw = json("model" to "model-b", "model_provider" to "fixture")
    /** "model-c" is listed as "Model C". */
    private fun model(id: String) = json("id" to id, "label" to id.split('-').joinToString(" ") { it.replaceFirstChar(Char::uppercase) })
    private fun catalog(vararg ids: String) = json("groups" to JSONArray().put(json(
        "provider_id" to "fixture", "provider" to "Configured provider", "models" to JSONArray(ids.map { model(it) }), "extra_models" to JSONArray(),
    )))
    private val reasoning = json("options" to JSONArray(listOf("low", "medium", "high")), "value" to "medium", "known" to true)

    // The page

    @Test fun withoutAChatTheModelRowIsOffAndSaysOpenAChatFirst() = withClientlessVm(compose) { vm ->
        compose.showPage { NativeAdministration(vm, "Providers & models") }
        compose.onNodeWithTag("providers-page").assertIsDisplayed()
        compose.onNodeWithTag("providers-this-chat").assertIsNotEnabled().assertTextContains("Open a chat first")
        compose.onNodeWithTag("providers-profile-defaults").assertIsEnabled()
        compose.onNodeWithText("This chat").assertIsDisplayed()
        compose.onNodeWithText("Defaults").assertIsDisplayed()
    }

    @Test fun withAChatTheRowShowsItsModelOrTheProfileDefault() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.openSyntheticChat("chat-1", raw = json("model" to "synthetic-model")) }
        compose.showPage { NativeAdministration(vm, "Providers & models") }
        compose.onNodeWithTag("providers-this-chat").assertIsEnabled().assertTextContains("synthetic-model")
        compose.runOnIdle { vm.openSyntheticChat("chat-1", raw = JSONObject()) }
        compose.onNodeWithTag("providers-this-chat").assertTextContains("Profile default")
    }

    @Test fun theModelRowAsksForTheModelSheet() = withClientlessVm(compose) { vm ->
        compose.runOnIdle { vm.openSyntheticChat("chat-1", raw = chatRaw) }
        compose.showPage { NativeAdministration(vm, "Providers & models") }
        compose.onNodeWithTag("providers-this-chat").performClick()
        compose.waitForIdle()
        assertTrue("The tap reached the model sheet (opened, or refused with a notice without a conversation)", vm.ui.value.notice.isNotBlank())
    }

    @Test fun profileDefaultsOpensTheProfilesPage() = withClientlessVm(compose) { vm ->
        compose.showPage { NativeAdministration(vm, "Providers & models") }
        compose.onNodeWithTag("providers-profile-defaults").performClick()
        compose.waitUntil(5_000) { vm.ui.value.morePage == "Profiles" }
        assertEquals("Profiles", vm.ui.value.morePage)
    }

    // The model sheet

    private fun openSheet(vm: BeaconViewModel, raw: JSONObject = chatRaw, kind: String = "Model & provider") {
        compose.runOnIdle { vm.openSyntheticChat("chat-1", raw = raw); vm.catalogFlow.value = kind }
        compose.showPage { NativeCatalog(vm, containedActions = true) }
        compose.waitForIdle()
    }

    @Test fun theFirstReadShowsPlaceholdersAndNoCustomModelRow() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.onNodeWithTag("models-loading").assertIsDisplayed()
        compose.onNodeWithText("Couldn't load models").assertDoesNotExist()
        compose.onNodeWithText("Enter a configured model ID").assertDoesNotExist()
        compose.onNodeWithText("No matching models").assertDoesNotExist()
    }

    @Test fun aFailedModelReadSaysSoAndRetryAsksAgain() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning); vm.storeData("picker:models", json("_error" to "Synthetic models failure")) }
        compose.onNodeWithText("Couldn't load models").assertIsDisplayed()
        compose.onNodeWithTag("models-loading").assertDoesNotExist()
        compose.runOnIdle { vm.notice("") }
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertTrue("Retry asked for the models again (refused without a conversation)", vm.ui.value.notice.isNotBlank())
    }

    @Test fun anEmptyListSaysNoMatchingModelsAndASearchCanBeCleared() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning); vm.storeData("picker:models", json("groups" to JSONArray())) }
        compose.onNodeWithText("No matching models").assertIsDisplayed()
        compose.onNodeWithText("Clear search").assertDoesNotExist()
        compose.onNodeWithTag("model-current").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("Search model or provider")).performTextReplacement("zzz")
        compose.onNodeWithText("No matching models").assertIsDisplayed()
        compose.onNodeWithTag("model-current").assertDoesNotExist()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("Clear search").assertDoesNotExist()
        compose.onNodeWithTag("model-current").assertIsDisplayed()
    }

    @Test fun theChatsModelLeadsItsProviderAndIsMarked() = withClientlessVm(compose) { vm ->
        openSheet(vm, raw = json("model" to "model-c", "model_provider" to "fixture"))
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning); vm.storeData("picker:models", catalog("model-a", "model-b", "model-c")) }
        compose.onNodeWithTag("model:fixture:model-c").assertIsSelected()
        compose.onNodeWithTag("model:fixture:model-a").assertIsNotSelected()
        val lead = compose.onNodeWithTag("model:fixture:model-c").fetchSemanticsNode().positionInRoot.y
        val other = compose.onNodeWithTag("model:fixture:model-a").fetchSemanticsNode().positionInRoot.y
        assertTrue("The chat's model sits above the others ($lead < $other)", lead < other)
        compose.onNode(hasContentDescription("Current: Model C, Configured provider")).assertExists()
        compose.onNodeWithText("Enter a configured model ID").assertIsDisplayed()
    }

    @Test fun theCustomModelRowOpensTheFormAndClosesTheSheet() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning); vm.storeData("picker:models", catalog("model-a", "model-b")) }
        compose.onNodeWithText("Enter a configured model ID").performClick()
        compose.waitForIdle()
        assertEquals("", vm.catalog.value)
        val form = requireNotNull(vm.form.value) { "The custom model form did not open" }
        assertEquals("Custom model", form.title)
        assertEquals("/api/session/update", form.path)
        assertEquals(listOf("model", "model_provider"), form.fields.map { it.key })
        assertEquals("chat-1", form.seed.getString("session_id"))
    }

    @Test fun theHeaderRefreshAsksAgainAndWaitsWhileAReadRuns() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning); vm.storeData("picker:models", catalog("model-a")) }
        compose.onNodeWithContentDescription("Refresh models").assertIsEnabled()
        compose.runOnIdle { vm.notice("") }
        compose.onNodeWithContentDescription("Refresh models").performClick()
        compose.waitForIdle()
        assertTrue("Refresh asked for the models again", vm.ui.value.notice.isNotBlank())
        compose.runOnIdle { vm.refreshingFlow.value = setOf("picker:models") }
        compose.onNodeWithContentDescription("Refresh models").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("model:fixture:model-a").assertIsDisplayed()
    }

    @Test fun closePickerClosesTheSheet() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.onNodeWithContentDescription("Close picker").performClick()
        compose.waitForIdle()
        assertEquals("", vm.catalog.value)
        compose.onNodeWithContentDescription("Close picker").assertDoesNotExist()
    }

    // Reasoning in the Current card: loading, failed, choices, and a model that decides by itself

    @Test fun reasoningShowsAPlaceholderWhileItsChoicesLoad() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.forget("picker:reasoning") }
        compose.onNodeWithTag("reasoning-loading").assertIsDisplayed()
        compose.onNodeWithText("Couldn't load reasoning").assertDoesNotExist()
    }

    @Test fun reasoningThatCouldNotLoadSaysSoAndRetryAsksAgain() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        // The sheet's own read of the reasoning choices has already ended offline.
        compose.onNodeWithText("Couldn't load reasoning").assertIsDisplayed()
        compose.runOnIdle { vm.notice("") }
        compose.onNodeWithText("Retry").performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.notice.isNotBlank())
    }

    @Test fun reasoningChoicesShowTheCurrentLevelAndATapAsksToChangeIt() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning) }
        compose.onNodeWithText("Reasoning").assertIsDisplayed()
        for (level in listOf("low", "medium", "high")) compose.onNodeWithTag("reasoning-level:$level").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-level:medium").assertIsSelected()
        compose.onNodeWithTag("reasoning-level:high").assertIsNotSelected()
        compose.runOnIdle { vm.notice("") }
        compose.onNodeWithTag("reasoning-level:high").performClick()
        compose.waitForIdle()
        assertTrue("The tap reached setReasoning (refused without a conversation)", vm.ui.value.notice.isNotBlank())
    }

    @Test fun aModelThatManagesReasoningItselfSaysSoAndOffersNoLevels() = withClientlessVm(compose) { vm ->
        openSheet(vm)
        compose.runOnIdle { vm.storeData("picker:reasoning", json("options" to JSONArray(), "known" to true)) }
        compose.onNodeWithText("This model manages reasoning automatically.").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-level:low").assertDoesNotExist()
        compose.runOnIdle { vm.storeData("picker:reasoning", json("options" to JSONArray())) }
        compose.onNodeWithText("This model manages reasoning automatically.").assertDoesNotExist()
        compose.onNodeWithText("Couldn't load reasoning").assertDoesNotExist()
        compose.onNodeWithTag("reasoning-loading").assertDoesNotExist()
    }

    // The Reasoning sheet (the composer's level chip opens it)

    @Test fun theReasoningSheetShowsPlaceholdersThenLevelCards() = withClientlessVm(compose) { vm ->
        openSheet(vm, kind = "Reasoning")
        compose.runOnIdle { vm.forget("picker:reasoning") }
        compose.onNodeWithTag("reasoning-skeleton").assertIsDisplayed()
        compose.runOnIdle { vm.storeData("picker:reasoning", reasoning) }
        compose.onNodeWithTag("reasoning-skeleton").assertDoesNotExist()
        for (level in listOf("low", "medium", "high")) compose.onNodeWithTag("reasoning-level:$level").assertIsDisplayed()
        compose.onNodeWithTag("reasoning-level:medium").assertIsSelected()
    }

    @Test fun theReasoningSheetNamesAFailureAndAnEmptyAnswer() = withClientlessVm(compose) { vm ->
        openSheet(vm, kind = "Reasoning")
        compose.runOnIdle { vm.storeData("picker:reasoning", json("_error" to "Synthetic failure")) }
        compose.onNodeWithText("Couldn't load reasoning").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
        compose.runOnIdle { vm.storeData("picker:reasoning", json("options" to JSONArray())) }
        compose.onNodeWithText("No reasoning choices are available.").assertIsDisplayed()
        compose.runOnIdle { vm.storeData("picker:reasoning", json("options" to JSONArray(), "known" to true)) }
        compose.onNodeWithText("This model manages reasoning automatically.").assertIsDisplayed()
    }
}
