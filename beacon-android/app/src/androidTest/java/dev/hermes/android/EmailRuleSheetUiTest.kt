package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.core.json
import dev.hermes.android.ui.EmailRuleRequest
import dev.hermes.android.ui.ImportanceRuleBlock
import dev.hermes.android.ui.EmailRuleSheet
import dev.hermes.android.ui.EmailRuleSheetHost
import dev.hermes.android.ui.EmailRuleSuggestion
import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The sheet an Important / Not important tap opens: the suggested rule, editable, with "Save rule" and "Just this email". */
class EmailRuleSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun show(content: @Composable () -> Unit) = compose.setContent { MaterialTheme(colorScheme = darkColorScheme()) { content() } }
    private fun awaitNode(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun awaitGone(tag: String) = compose.waitUntil(5_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }

    @Test fun theSheetShowsTheVerdictAndTheSuggestedRuleReadyToSave() {
        var saved: String? = null
        var justThis = 0
        show {
            EmailRuleSheet("important", EmailRuleSuggestion(sender = "Daniel Reyes", topic = "Client moved the review", rule = "Sent by Daniel Reyes"),
                busy = false, error = "", onSave = { saved = it }, onJustThisEmail = { justThis++ }, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        compose.onNodeWithTag("email-rule-title").assertTextEquals("Mark as important")
        compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes")
        // The suggestion is here, so nothing is loading.
        compose.onNodeWithTag("email-rule-loading").assertDoesNotExist()
        compose.onNodeWithTag("email-rule-save").assertIsEnabled().performClick()
        assertEquals("Sent by Daniel Reyes", saved)
        assertEquals(0, justThis)
    }

    @Test fun theOwnerCanEditTheRuleBeforeSavingAndAnEmptyRuleCannotBeSaved() {
        var saved: String? = null
        show {
            EmailRuleSheet("not_important", EmailRuleSuggestion(topic = "Weekly deals"), busy = false, error = "", onSave = { saved = it }, onJustThisEmail = {}, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        compose.onNodeWithTag("email-rule-title").assertTextEquals("Mark as not important")
        // With no sender the rule is about the subject.
        compose.onNodeWithTag("email-rule-text").assertTextContains("About Weekly deals")
        compose.onNodeWithTag("email-rule-text").performTextReplacement("  Anything from the deals team ")
        compose.onNodeWithTag("email-rule-save").performClick()
        assertEquals("Anything from the deals team", saved)
        compose.onNodeWithTag("email-rule-text").performTextReplacement("   ")
        compose.onNodeWithTag("email-rule-save").assertIsNotEnabled()
        // Marking only this email never needs a rule.
        compose.onNodeWithTag("email-rule-just-this").assertIsEnabled()
    }

    @Test fun justThisEmailSendsOnlyTheChoiceAndNoRule() {
        val sends = mutableListOf<Pair<EmailRuleRequest, String?>>()
        var chosen: EmailRuleRequest? = null
        show {
            EmailRuleSheetHost(EmailRuleRequest("notice-1", "not_important"), fetch = { json("sender" to "Daniel Reyes", "topic" to "Review", "rule" to "Sent by Daniel Reyes") },
                send = { request, rule -> sends += request to rule }, onChosen = { chosen = it }, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
        compose.onNodeWithTag("email-rule-just-this").performClick()
        compose.waitUntil(5_000) { chosen != null }
        assertEquals(listOf(EmailRuleRequest("notice-1", "not_important") to null), sends)
        assertEquals(EmailRuleRequest("notice-1", "not_important"), chosen)
    }

    @Test fun saveRuleSendsTheWordsInTheField() {
        val sends = mutableListOf<Pair<EmailRuleRequest, String?>>()
        var chosen: EmailRuleRequest? = null
        show {
            EmailRuleSheetHost(EmailRuleRequest("notice-2", "important"), fetch = { json("sender" to "Daniel Reyes", "topic" to "Review", "rule" to "Sent by Daniel Reyes") },
                send = { request, rule -> sends += request to rule }, onChosen = { chosen = it }, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
        compose.onNodeWithTag("email-rule-text").performTextReplacement("Sent by Daniel Reyes or Maya")
        compose.onNodeWithTag("email-rule-save").performClick()
        compose.waitUntil(5_000) { chosen != null }
        assertEquals(listOf(EmailRuleRequest("notice-2", "important") to "Sent by Daniel Reyes or Maya"), sends)
    }

    @Test fun theOwnerCanTypeWhileTheSuggestionLoadsAndTheirWordsStay() {
        val release = CompletableDeferred<JSONObject>()
        val sends = mutableListOf<String?>()
        show {
            EmailRuleSheetHost(EmailRuleRequest("notice-3", "important"), fetch = { release.await() }, send = { _, rule -> sends += rule }, onChosen = {}, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        // While Hermes works out the rule a thin bar says so; the owner can type meanwhile.
        compose.onNodeWithTag("email-rule-loading").assertExists()
        compose.onNodeWithTag("email-rule-save").assertIsNotEnabled()
        compose.onNodeWithTag("email-rule-text").performTextReplacement("My own words")
        release.complete(json("sender" to "Daniel Reyes", "topic" to "", "rule" to "Sent by Daniel Reyes"))
        compose.waitForIdle()
        awaitGone("email-rule-loading")
        compose.onNodeWithTag("email-rule-text").assertTextContains("My own words")
        compose.onNodeWithTag("email-rule-save").assertIsEnabled().performClick()
        compose.waitUntil(5_000) { sends.isNotEmpty() }
        assertEquals(listOf<String?>("My own words"), sends)
    }

    @Test fun aRefusedSaveKeepsTheSheetOpenWithAShortMessageAndCanBeRetried() {
        var attempts = 0
        var chosen: EmailRuleRequest? = null
        show {
            EmailRuleSheetHost(EmailRuleRequest("notice-4", "important"), fetch = { json("sender" to "Daniel Reyes") },
                send = { _, rule ->
                    attempts++
                    if (attempts == 1) throw IllegalStateException("Proactive request limit reached")
                    if (attempts == 2) throw IllegalStateException("Unavailable")
                    check(rule == null || rule.isNotBlank())
                }, onChosen = { chosen = it }, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("email-rule-text").assertTextContains("Sent by Daniel Reyes") }.isSuccess }
        // A full list says so; the rule is not lost and the sheet stays.
        compose.onNodeWithTag("email-rule-save").performClick()
        awaitNode("email-rule-error")
        compose.onNodeWithTag("email-rule-error").assertTextContains("Rules are full. Delete one first.")
        assertNull(chosen)
        // Any other failure reads the same everywhere.
        compose.onNodeWithTag("email-rule-save").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("email-rule-error").assertTextContains("Couldn't save the rule. Try again.") }.isSuccess }
        // The retry goes through.
        compose.onNodeWithTag("email-rule-save").performClick()
        compose.waitUntil(5_000) { chosen != null }
        assertEquals(3, attempts)
    }

    @Test fun aSuggestionThatFailedToLoadStopsTheBarAndLeavesTheFieldToTheOwner() {
        show {
            EmailRuleSheetHost(EmailRuleRequest("notice-6", "important"), fetch = { throw IllegalStateException("Unavailable") }, send = { _, _ -> }, onChosen = {}, onDismiss = {})
        }
        awaitNode("email-rule-sheet")
        awaitGone("email-rule-loading")
        compose.onNodeWithTag("email-rule-text").assertIsEnabled()
    }

    /** A rule is up to 300 characters and the list is where it is read whole: a long one takes more than the three lines it had, up to six. */
    @Test fun aRuleInTheRulesListShowsMoreThanThreeLinesWhole() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp)) {
                    ImportanceRuleBlock(json("id" to "r1", "text" to "Anything from the finance team about invoices that are overdue, or a payment that failed, or a contract to sign.", "enabled" to true,
                        "action" to "important", "signal" to "email"), enabled = true, onUpdate = {}, onDelete = {})
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("proactive-rule-text-r1").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        val layout = layouts.single()
        assertTrue("${layout.lineCount} lines", layout.lineCount in 4..6)
        assertFalse(layout.isLineEllipsized(layout.lineCount - 1))
    }

    /** The badge says "Email", not the id Hermes sends. */
    @Test fun aRulesSourceReadsAsAWordNotAsTheRawId() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp)) {
                    ImportanceRuleBlock(json("id" to "r3", "text" to "Sent by Daniel Reyes", "enabled" to true, "action" to "important", "signal" to "email"),
                        enabled = true, onUpdate = {}, onDelete = {})
                }
            }
        }
        compose.onNodeWithText("Email").assertIsDisplayed()
        compose.onAllNodesWithText("email").assertCountEquals(0)
    }

    @Test fun aVeryLongRuleIsCutAtTheSixthLine() {
        compose.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.width(360.dp)) {
                    ImportanceRuleBlock(json("id" to "r2", "text" to "x ".repeat(150), "enabled" to true, "action" to "not_important", "signal" to "email"), enabled = true, onUpdate = {}, onDelete = {})
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("proactive-rule-text-r2").fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(6, layouts.single().lineCount)
        assertTrue(layouts.single().isLineEllipsized(5))
    }

    @Test fun aRefusedJustThisEmailSaysSoAndTheSheetClosesWhenTheOwnerDismissesIt() {
        var dismissed by mutableStateOf(false)
        show {
            if (!dismissed) EmailRuleSheetHost(EmailRuleRequest("notice-5", "not_important"), fetch = { json("sender" to "Daniel Reyes") },
                send = { _, _ -> throw IllegalStateException("Unavailable") }, onChosen = {}, onDismiss = { dismissed = true })
        }
        awaitNode("email-rule-sheet")
        compose.onNodeWithTag("email-rule-just-this").performClick()
        compose.waitUntil(5_000) { runCatching { compose.onNodeWithTag("email-rule-error").assertTextContains("Couldn't save your choice. Try again.") }.isSuccess }
        assertFalse(dismissed)
        compose.runOnIdle { dismissed = true }
        awaitGone("email-rule-sheet")
        assertTrue(dismissed)
    }
}
