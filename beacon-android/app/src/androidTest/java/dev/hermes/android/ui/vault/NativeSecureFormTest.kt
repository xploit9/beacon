package dev.hermes.android.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.ui.NativeSecureForm
import dev.hermes.android.ui.SecureField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeSecureFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun keyboardUp(): Boolean = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys input_method")
        .use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText() }.contains("mInputShown=true")

    /**
     * The sheet is inset by the keyboard (NativeSecureForm), so Continue and Cancel move while it rises, and a tap goes where the button was when the tap was worked out.
     * #869 tapped Continue straight after typing, on an emulator the recorder was loading, and the tap found nothing (attempts 0). So: let the keyboard come up if it is
     * going to, then tap once the button has held still for [STILL_MS]. Both are real signals; the sibling NativeSecureSheetUiTest waits for the keyboard the same way.
     */
    private fun tapWhenStill(target: () -> SemanticsNodeInteraction) {
        val begun = SystemClock.elapsedRealtime()
        val first = target().fetchSemanticsNode().boundsInRoot
        val keyboardBy = begun + 5_000
        while (!keyboardUp() && SystemClock.elapsedRealtime() < keyboardBy) Thread.sleep(100)
        var seen = target().fetchSemanticsNode().boundsInRoot
        var since = SystemClock.elapsedRealtime()
        compose.waitUntil(15_000) {
            val now = target().fetchSemanticsNode().boundsInRoot
            if (now != seen) { seen = now; since = SystemClock.elapsedRealtime() }
            SystemClock.elapsedRealtime() - since >= STILL_MS
        }
        println("SECURE_FORM_TAP moved=${first != seen} first=$first still=$seen waited_ms=${SystemClock.elapsedRealtime() - begun}")
        target().performClick()
    }

    @Test fun cancellingAndReopeningNeverRestoresTheSecret() {
        var cancelled = 0
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeSecureForm("fixture", "Secure input", "", listOf(SecureField("value", "Password")),
                onCancel = { cancelled++; open = false }, onSubmit = { open = false })
            else Button(onClick = { open = true }) { Text("Open") }
        }
        compose.onNodeWithTag("vault-field:value").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("vault-field:value").performTextInput("synthetic-canary")
        tapWhenStill { compose.onNodeWithText("Cancel") }
        compose.onNodeWithText("Open").performClick()
        assertEquals("", compose.onNodeWithTag("vault-field:value").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun backgroundingClosesTheSecureFormAndClearsItsInput() {
        var cancelled = 0
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeSecureForm("fixture", "Secure input", "", listOf(SecureField("value", "Password")),
                onCancel = { cancelled++; open = false }, onSubmit = { open = false })
            else Button(onClick = { open = true }) { Text("Open") }
        }
        compose.onNodeWithTag("vault-field:value").performTextInput("synthetic-canary")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Open").performClick()
        assertEquals("", compose.onNodeWithTag("vault-field:value").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals(1, cancelled) }
    }

    @Test fun aHeldQuestionIsSuspendedNotCancelledWhenTheSystemClosesTheSheet() {
        var cancelled = 0; var suspended = 0
        compose.setContent {
            var open by remember { mutableStateOf(true) }
            if (open) NativeSecureForm("fixture", "Secure input", "", listOf(SecureField("value", "Password")),
                onCancel = { cancelled++; open = false }, onSuspend = { suspended++; open = false }, onSubmit = { open = false })
            else Button(onClick = { open = true }) { Text("Open") }
        }
        compose.onNodeWithTag("vault-field:value").performTextInput("synthetic-canary")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Open").performClick()
        assertEquals("", compose.onNodeWithTag("vault-field:value").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.runOnIdle { assertEquals("The system closing the sheet is not the person's answer", 0, cancelled); assertEquals(1, suspended) }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("The person's own Cancel still cancels", 1, cancelled); assertEquals(1, suspended) }
    }
    // ---- round 2 (G5 F6): an answer that could not be handed over never costs the person what they typed ----

    @Test fun aRefusedSendLeavesTheSheetOpenWithWhatWasTypedAndTheNextContinueClosesIt() {
        var attempts = 0; var accepted = false; var cancelled = 0
        compose.setContent {
            NativeSecureForm("fixture", "Secure input", "", listOf(SecureField("value", "Password")),
                onCancel = { cancelled++ }, onSubmit = {}, onSend = { attempts++; accepted })
        }
        compose.onNodeWithTag("vault-field:value").performTextInput("synthetic-canary")
        tapWhenStill { compose.onNodeWithTag("vault-submit") }
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithTag("vault-field:value").assertExists()
        assertEquals("What was typed is still there", 16, compose.onNodeWithTag("vault-field:value").fetchSemanticsNode().config[SemanticsProperties.EditableText].text.length)
        compose.onNodeWithText("Couldn't confirm it was sent.").assertExists()
        accepted = true
        tapWhenStill { compose.onNodeWithTag("vault-submit") }
        compose.runOnIdle { assertEquals(2, attempts); assertEquals("A sent answer is not a cancel", 0, cancelled) }
        assertEquals("The accepted answer closes the sheet", 0, compose.onAllNodesWithTag("vault-field:value").fetchSemanticsNodes().size)
    }

    @Test fun aSendThatThrowsIsARefusedSendToo() {
        var attempts = 0
        compose.setContent {
            NativeSecureForm("fixture", "Secure input", "", listOf(SecureField("value", "Password")),
                onCancel = {}, onSubmit = {}, onSend = { attempts++; error("connection gone") })
        }
        compose.onNodeWithTag("vault-field:value").performTextInput("synthetic-canary")
        tapWhenStill { compose.onNodeWithTag("vault-submit") }
        compose.runOnIdle { assertEquals(1, attempts) }
        compose.onNodeWithTag("vault-field:value").assertExists()
        compose.onNodeWithText("Couldn't confirm it was sent.").assertExists()
    }

    private companion object { const val STILL_MS = 300L }
}
