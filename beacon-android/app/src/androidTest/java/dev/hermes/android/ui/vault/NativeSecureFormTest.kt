package dev.hermes.android.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import dev.hermes.android.ui.NativeSecureForm
import dev.hermes.android.ui.SecureField
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NativeSecureFormTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

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
        compose.onNodeWithText("Cancel").performClick()
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
}
