package dev.hermes.android

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.platform.LocalDensity
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.data.NativeSkillContent
import dev.hermes.android.ui.NativeSkillsScreen
import dev.hermes.android.ui.NativeSkillsState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SkillsPhoneUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun setupFilterShowsOnlyBlockedSkillsAndLongEditorHasBoundedHeight() {
        val ready = NativeSkill("ready-skill", "Ready description", "general", true, "agent")
        val needs = NativeSkill("needs-skill", "Setup description", "general", false, "agent", listOf("Set WORK_API_KEY on the host"))
        var state by mutableStateOf(NativeSkillsState(profile = "work", skills = listOf(needs, ready)))
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            MaterialTheme {
                NativeSkillsScreen(state, { state = state.copy(search = it) }, {}, {}, { _, _ -> }, {}, {}, {}, {})
            }
        }
        compose.onNodeWithText("2 installed · 1 enabled · 1 need setup").assertExists()
        compose.onNode(hasText("Needs setup") and isSelectable()).performScrollTo().performClick()
        compose.onNodeWithText("needs-skill").assertExists()
        compose.onNodeWithText("ready-skill").assertDoesNotExist()
        compose.onNodeWithTag("skill-row-needs-skill").performScrollTo().assertTextContains("Needs setup")
        compose.onNodeWithText("Enabled · Setup ready").assertDoesNotExist()
        compose.runOnIdle {
            state = state.copy(selected = needs, content = NativeSkillContent(needs.name, "Original"), editing = true, draft = (1..1000).joinToString("\n") { "Instruction $it" })
        }
        val field = compose.onNodeWithText("Skill instructions").performScrollTo().fetchSemanticsNode()
        assertTrue("Long instructions must scroll inside the bounded editor", field.layoutInfo.coordinates.size.height < 800 * density)
    }

    @Test fun readToggleAndEditAreRealCallbacksAndFailedSaveRetainsDraft() {
        val skill = NativeSkill("inbox", "Read the work inbox", "productivity", false, "agent")
        var toggled = false
        var saved = ""
        compose.setContent {
            var state by remember { mutableStateOf(NativeSkillsState(profile = "work", skills = listOf(skill))) }
            MaterialTheme {
                NativeSkillsScreen(state, { state = state.copy(search = it) },
                    { state = state.copy(selected = it, content = NativeSkillContent(it.name, "Sign in to the work inbox first.")) },
                    { state = state.copy(selected = null, content = null, editing = false) },
                    { selected, enabled -> toggled = enabled; val updated = selected.copy(enabled = enabled); state = state.copy(skills = listOf(updated), selected = state.selected?.let { updated }) },
                    { state = state.copy(editing = it, draft = state.content?.content.orEmpty()) },
                    { state = state.copy(draft = it) },
                    { saved = state.draft; state = state.copy(error = "Host rejected the instructions. Review the skill and retry.") }, {})
            }
        }
        compose.onNodeWithContentDescription("Enable inbox").performClick()
        compose.runOnIdle { assertTrue(toggled) }
        compose.onNodeWithTag("skill-row-inbox").assertExists()
        compose.onNodeWithText("Enabled · Setup ready").assertDoesNotExist()
        compose.onNodeWithText("Read & adjust").assertDoesNotExist()
        compose.onNodeWithText("inbox").performClick()
        compose.onNodeWithText("Sign in to the work inbox first.").assertExists()
        compose.onNodeWithText("Adjust instructions").performScrollTo().performClick()
        compose.onNodeWithText("Skill instructions").performScrollTo().performTextReplacement("Use the shared work inbox.")
        compose.onNodeWithText("Save instructions").performScrollTo().performClick()
        compose.runOnIdle { assertEquals("Use the shared work inbox.", saved) }
        compose.onNodeWithText("Use the shared work inbox.").assertExists()
        compose.onNodeWithText("Host rejected the instructions. Review the skill and retry.").assertExists()
    }
}
