package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.data.*
import dev.hermes.android.ui.NativeBotSettings
import dev.hermes.android.ui.NativeServerSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AdministrationUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun botModelFailureKeepsDraftAndShowsRealInstructions() {
        var saved = ""
        compose.setContent {
            var state by remember { mutableStateOf(NativeBotsState(selected = NativeBot("work", "Work bot", "Route work tasks", "Use the approved work inbox.", "custom", "old", "revision"))) }
            MaterialTheme { NativeBotSettings(state, {}, {}, { state = state.copy(editing = true, draftProvider = "custom", draftModel = "old") },
                { provider, model -> state = state.copy(draftProvider = provider, draftModel = model) },
                { saved = state.draftModel; state = state.copy(error = "Model changed elsewhere. Review before saving.") },
                { state = state.copy(editing = false) }, { _, _ -> }, {}, models = listOf("custom" to "listed-model")) }
        }
        compose.onNodeWithText("Read behavior instructions").performScrollTo().performClick()
        compose.onNodeWithText("Use the approved work inbox.").performScrollTo().assertExists()
        compose.onNodeWithText("Change default model").performScrollTo().performClick()
        compose.onNodeWithText("Search models or providers").performScrollTo().performTextInput("listed")
        compose.onNodeWithText("listed-model").performScrollTo().performClick()
        compose.onNodeWithText("Enter a configured model ID").performScrollTo().performClick()
        compose.onNodeWithText("Model ID").performScrollTo().performTextReplacement("new-model")
        compose.onNodeWithText("Save default model").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("new-model", saved) }
        compose.onNodeWithText("new-model").assertExists()
        compose.onNodeWithText("Model changed elsewhere. Review before saving.").performScrollTo().assertExists()
    }
    @Test fun serverCheckAndApplyAreSeparateRealCallbacks() {
        var checked = false
        var applied = false
        compose.setContent { MaterialTheme { NativeServerSettings("Server updates", NativeServerState(update = JSONObject("""{"current_version":"1","can_apply":true,"update_available":true,"message":null}""")), { checked = true }, { applied = true }, {}, {}) } }
        compose.onNodeWithText("An update is available.").assertExists()
        compose.onNodeWithText("null").assertDoesNotExist()
        compose.onNodeWithText("Check now").performClick()
        compose.runOnIdle { assertTrue(checked); assertFalse(applied) }
        compose.onNodeWithText("Apply server update").performScrollTo().performClick()
        compose.runOnIdle { assertTrue(applied) }
    }
}
