package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
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
            BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = 0, saving = false, error = "", onSave = { saved = it })
                }
            }
        }
        compose.onNodeWithText("Keep call and task history").assertIsDisplayed()
        compose.onNodeWithTag("history-retention-current", useUnmergedTree = true).assertTextEquals("Forever")
        compose.onNodeWithTag("history-retention-choice").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Delete call and task history older than 30 days. This can’t be undone.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(-1, saved)

        compose.onNodeWithTag("history-retention-choice").performClick()
        compose.onNodeWithText("30 days").performClick()
        compose.onNodeWithText("Delete older history").performClick()
        compose.waitForIdle()
        assertEquals(30, saved)
    }

    @Test fun savingDisablesTheChoiceAndErrorsRemainVisible() {
        compose.setContent {
            BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = 90, saving = true, error = "Server declined the change", onSave = {})
                }
            }
        }
        compose.onNodeWithTag("history-retention-choice").assertIsNotEnabled()
        compose.onNodeWithTag("history-retention-current", useUnmergedTree = true).assertTextEquals("90 days")
        compose.onNodeWithText("Server declined the change").assertIsDisplayed()
        compose.onNodeWithTag("history-retention-saving").assertIsDisplayed()
    }

    @Test fun lengtheningAsksNothingAndSavesAtOnce() {
        var saved = -1
        compose.setContent {
            BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = 30, saving = false, error = "", onSave = { saved = it })
                }
            }
        }
        compose.onNodeWithTag("history-retention-choice").performClick()
        compose.onNodeWithText("1 year").performClick()
        compose.waitForIdle()
        assertEquals(365, saved)
        compose.onNodeWithTag("history-retention-confirmation").assertDoesNotExist()
    }

    @Test fun nothingLoadedYetShowsASkeletonAndAFailedLoadOffersRetry() {
        var retried = 0
        var days by mutableStateOf<Int?>(null)
        var error by mutableStateOf("")
        compose.setContent {
            BeaconTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    HistoryRetentionControl(currentDays = days, saving = false, error = error, onSave = {}, onRetry = { retried++ })
                }
            }
        }
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithTag("history-retention-choice").assertDoesNotExist()
        compose.runOnIdle { error = "Couldn’t load history retention from Hermes." }
        compose.onNodeWithTag("history-retention-error").assertIsDisplayed()
        compose.onNodeWithTag("history-retention-retry").performClick()
        assertEquals(1, retried)
        compose.runOnIdle { days = 365; error = "" }
        compose.onNodeWithTag("history-retention-current", useUnmergedTree = true).assertTextEquals("1 year")
        compose.onNodeWithTag("history-retention-error").assertDoesNotExist()
    }
}
