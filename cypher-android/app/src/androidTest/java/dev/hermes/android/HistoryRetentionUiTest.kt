package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.hermes.android.ui.HistoryRetentionControl
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryRetentionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun shorteningShowsTheWarningAndWaitsForConfirmation() {
        var saved = -1
        compose.setContent {
            CypherTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = 0, saving = false, error = "", onSave = { saved = it })
                }
            }
        }
        compose.onNodeWithText("Keep call and task history").assertIsDisplayed()
        compose.onNodeWithText("Current server setting: Forever").assertIsDisplayed()
        compose.onNodeWithContentDescription("History retention choice").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Call transcripts and task history older than 30 days will be deleted from Hermes. This can’t be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(-1, saved)

        compose.onNodeWithContentDescription("History retention choice").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Delete older history").performClick()
        compose.waitForIdle()
        assertEquals(30, saved)
    }

    @Test fun savingDisablesTheChoiceAndErrorsRemainVisible() {
        compose.setContent {
            CypherTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = 90, saving = true, error = "Server declined the change", onSave = {})
                }
            }
        }
        compose.onNodeWithContentDescription("History retention choice").assertIsNotEnabled()
        compose.onNodeWithText("Current server setting: 90 days").assertIsDisplayed()
        compose.onNodeWithText("Server declined the change").assertIsDisplayed()
    }
}
