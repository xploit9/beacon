package dev.hermes.android.uiux.catalog

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.data.NativeMemoryDocument
import dev.hermes.android.data.NativeMemoryState
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.data.NativeSkillContent
import dev.hermes.android.ui.CommandRow
import dev.hermes.android.ui.ModelRow
import dev.hermes.android.ui.NativeMemoryScreen
import dev.hermes.android.ui.NativeSkillsScreen
import dev.hermes.android.ui.NativeSkillsState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** The catalog lane's screens that take plain state: Skills (list and detail), Memory, and the model and command rows. */
class CatalogScreensUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun doc(target: String, content: String, enabled: Boolean = true, limit: Int = 2000) = NativeMemoryDocument(target, content, "r1", enabled, limit)
    private fun skill(name: String, category: String = "general", enabled: Boolean = false, needs: List<String> = emptyList(), essential: Boolean = false, description: String = "A $name skill") =
        NativeSkill(name, description, category, enabled, "agent", needs, essential)

    private fun skills(initial: NativeSkillsState, toggled: (NativeSkill, Boolean) -> Unit = { _, _ -> }, opened: (NativeSkill) -> Unit = {}): () -> NativeSkillsState {
        var state by mutableStateOf(initial)
        compose.setContent { MaterialTheme { NativeSkillsScreen(state, { state = state.copy(search = it) }, { opened(it); state = state.copy(selected = it, content = NativeSkillContent(it.name, "Steps")) }, {}, toggled, {}, {}, {}, {}) } }
        return { state }
    }

    @Test fun skillsAreSectionedByCategoryWithReadableNamesAndPills() {
        skills(NativeSkillsState(profile = "work", skills = listOf(
            skill("apple-notes", "apple", enabled = true), skill("work-inbox", "productivity", needs = listOf("Set WORK_API_KEY on the host")), skill("loose-skill", "", essential = true, enabled = true),
        )))
        compose.onNodeWithTag("settings-section:Apple").assertExists()
        compose.onNodeWithTag("settings-section:Productivity").performScrollTo().assertExists()
        compose.onNodeWithTag("settings-section:Other").performScrollTo().assertExists()
        compose.onNodeWithText("Apple Notes").assertExists()
        compose.onNodeWithText("Work Inbox").performScrollTo().assertExists()
        compose.onNode(hasText("Needs setup") and hasAnyAncestor(hasTestTag("skill-row-work-inbox")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Always on") and hasAnyAncestor(hasTestTag("skill-row-loose-skill")), useUnmergedTree = true).performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Enable loose-skill").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Enable apple-notes").assertIsEnabled()
    }

    @Test fun theEnabledFilterAndSearchNarrowTheList() {
        skills(NativeSkillsState(profile = "work", skills = listOf(skill("apple-notes", enabled = true), skill("work-inbox"))))
        compose.onNodeWithTag("skills-filter-all").assertIsSelected()
        compose.onNodeWithTag("skills-filter-on").performScrollTo().performClick()
        compose.onNodeWithTag("skills-filter-on").assertIsSelected()
        compose.onNodeWithTag("skill-row-apple-notes").assertExists()
        compose.onNodeWithTag("skill-row-work-inbox").assertDoesNotExist()
        compose.onNodeWithTag("skills-filter-all").performClick()
        compose.onNode(hasSetTextAction() and hasText("Search skills")).performTextReplacement("work inbox")
        compose.onNodeWithTag("skill-row-work-inbox").assertExists()
        compose.onNodeWithTag("skill-row-apple-notes").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("work inbox")).performTextReplacement("nothing like it")
        compose.onNodeWithText("No matching skills").assertExists()
    }

    @Test fun aSkillDetailLeadsWithItsStateAndShowsWhatItNeeds() {
        var toggled: Pair<String, Boolean>? = null
        skills(NativeSkillsState(profile = "work", skills = listOf(skill("work-inbox", needs = listOf("Set WORK_API_KEY on the host", "Install the mail CLI")))),
            toggled = { s, on -> toggled = s.name to on })
        compose.onNodeWithTag("skill-row-work-inbox").performClick()
        compose.onNodeWithTag("skill-hero").assertExists()
        compose.onNode(hasText("Work Inbox") and hasAnyAncestor(hasTestTag("skill-hero")), useUnmergedTree = true).assertExists()
        compose.onNode(hasText("Needs setup") and hasAnyAncestor(hasTestTag("skill-hero"))).assertExists()
        compose.onNodeWithText("Set WORK_API_KEY on the host").performScrollTo().assertExists()
        compose.onNodeWithText("Install the mail CLI").assertExists()
        compose.onNodeWithTag("skill-enabled").assertIsOff()
        compose.onNodeWithTag("skill-enabled").performClick()
        compose.runOnIdle { assertEquals("work-inbox" to true, toggled) }
        compose.onNodeWithTag("skill-edit").performScrollTo().assertExists()
        compose.onNodeWithText("Steps").assertExists()
    }

    @Test fun anEssentialSkillCannotBeSwitchedOff() {
        skills(NativeSkillsState(profile = "work", skills = listOf(skill("core", essential = true, enabled = true))))
        compose.onNodeWithTag("skill-row-core").performClick()
        compose.onNodeWithTag("skill-enabled").assertIsOn().assertIsNotEnabled()
        compose.onNodeWithTag("skill-enabled").assertTextContains("Always on")
        compose.onNodeWithText("Ready").assertExists()
    }

    private fun memory(initial: NativeMemoryState, edit: (String) -> Unit = {}): () -> NativeMemoryState {
        var state by mutableStateOf(initial)
        compose.setContent { MaterialTheme { NativeMemoryScreen(state, edit, { state = state.copy(draft = it) }, {}, {}) } }
        return { state }
    }

    @Test fun memoryTabsAreASegmentedControlWithWholePhraseEditLabels() {
        var edited = ""
        memory(NativeMemoryState("s", documents = listOf(doc("memory", "Prefers quiet places"), doc("user", "Synthetic owner"))), edit = { edited = it })
        compose.onNodeWithTag("memory-tab-memory").assertIsSelected()
        compose.onNodeWithTag("memory-tab-user").assertIsNotSelected()
        compose.onNodeWithText("Prefers quiet places").assertExists()
        compose.onNodeWithText("Synthetic owner").assertDoesNotExist()
        compose.onNodeWithText("Edit agent notes").assertExists().performClick()
        compose.runOnIdle { assertEquals("memory", edited) }
        compose.onNodeWithText("About you").performClick()
        compose.onNodeWithTag("memory-tab-user").assertIsSelected()
        compose.onNodeWithText("Synthetic owner").assertExists()
        compose.onNodeWithText("Edit about you").performClick()
        compose.runOnIdle { assertEquals("user", edited) }
        compose.onNodeWithText("Prefers quiet places").assertDoesNotExist()
    }

    @Test fun whileEditingTheTabsAreLockedAndTheCounterWaitsForTheLimit() {
        val read = memory(NativeMemoryState("s", documents = listOf(doc("memory", "Prefers quiet places", limit = 2000), doc("user", "x")), editing = "memory", draft = "short"))
        compose.onNodeWithTag("memory-tab-user").assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Edit agent notes")).assertExists()
        compose.onNodeWithText("5 / 2000").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("short")).performTextReplacement("y".repeat(1599))
        compose.onNodeWithText("1599 / 2000").assertDoesNotExist()
        compose.onNode(hasSetTextAction() and hasText("y".repeat(1599))).performTextReplacement("y".repeat(1600))
        compose.onNodeWithText("1600 / 2000").assertExists()
        compose.onNode(hasSetTextAction() and hasText("y".repeat(1600))).performTextReplacement("y".repeat(2001))
        compose.onNodeWithText("2001 / 2000").assertExists()
        compose.onNodeWithText("Save notes").assertIsNotEnabled()
        assertEquals(2001, read().draft.length)
    }

    @Test fun memoryStatesAreWordsWithGlyphsNotRedSentences() {
        memory(NativeMemoryState("s", provider = "mem0", providerReady = false, documents = listOf(doc("memory", "Notes", enabled = false)), message = "Notes saved"))
        compose.onNodeWithTag("memory-recall-pill").assertExists()
        compose.onNodeWithText("Recall not ready").assertExists()
        compose.onNodeWithText("Off for new chats").assertExists()
        compose.onNodeWithText("Notes saved").assertExists()
        compose.onNodeWithText("Memory recall is not ready. Your profile notes remain available.").assertDoesNotExist()
        compose.onNodeWithText("Facts and working notes your agent keeps.").assertDoesNotExist()
    }

    @Test fun theServiceStaysOutOfTheWayUntilAskedFor() {
        memory(NativeMemoryState("s", provider = "mem0", documents = listOf(doc("memory", "Notes"))))
        compose.onNodeWithText("Memory service").assertDoesNotExist()
        compose.onNodeWithText("How memory works").performScrollTo().performClick()
        compose.onNodeWithText("Memory service").performScrollTo().assertExists()
        compose.onNodeWithText("mem0 keeps more than these notes").assertExists()
        compose.onNodeWithText("Hide how memory works").performClick()
        compose.onNodeWithText("Memory service").assertDoesNotExist()
    }

    @Test fun aLongNoteFoldsToThreeLinesAndOpensOnTap() {
        val long = (1..40).joinToString("\n\n") { "Line $it of a long note" }
        memory(NativeMemoryState("s", documents = listOf(doc("memory", "$long\n§\nShort note"))))
        compose.onNodeWithTag("memory-note-0").assertHasClickAction()
        compose.onNodeWithTag("memory-note-1").assertHasNoClickAction()
        val folded = compose.onNodeWithTag("memory-note-0").fetchSemanticsNode().size.height
        assertTrue("A folded note stays compact ($folded px)", folded < compose.density.run { 200.dp.toPx() })
        compose.onNodeWithTag("memory-note-0").performClick()
        compose.waitForIdle()
        val opened = compose.onNodeWithTag("memory-note-0").fetchSemanticsNode().size.height
        assertTrue("Tapping opens the whole note ($folded -> $opened px)", opened > folded * 4)
        compose.onNodeWithTag("memory-note-0").performClick()
        compose.waitForIdle()
        assertTrue("Tapping again folds it back", kotlin.math.abs(folded - compose.onNodeWithTag("memory-note-0").fetchSemanticsNode().size.height) <= 2)
    }

    @Test fun anEmptyDocumentOffersToAddNotes() {
        var edited = ""
        memory(NativeMemoryState("s", documents = listOf(doc("memory", "", limit = 100))), edit = { edited = it })
        compose.onNodeWithText("No notes yet").assertExists()
        compose.onNodeWithTag("memory-add").performClick()
        compose.runOnIdle { assertEquals("memory", edited) }
    }

    @Test fun aModelRowIsOneSelectableTargetThatShowsTheChosenModel() {
        var picked = 0
        compose.setContent {
            MaterialTheme { Column {
                ModelRow("Luna", "gpt-6-luna", selected = true, applying = false, enabled = true, tag = "model:p:luna") { picked++ }
                ModelRow("Plain", "", selected = false, applying = false, enabled = false, tag = "model:p:plain") { picked += 100 }
                ModelRow("Busy", "", selected = false, applying = true, enabled = true, tag = "model:p:busy") {}
            } }
        }
        compose.onNodeWithTag("model:p:luna", useUnmergedTree = true).assert(isSelected()).assertHeightIsAtLeast(64.dp).assert(hasAnyDescendant(hasContentDescription("Selected")))
        compose.onNodeWithText("gpt-6-luna").assertExists()
        compose.onNodeWithTag("model:p:luna").performClick()
        compose.runOnIdle { assertEquals(1, picked) }
        compose.onNodeWithTag("model:p:plain").assertIsNotEnabled().assert(isNotSelected())
        compose.onNodeWithTag("model-applying", useUnmergedTree = true).assertExists()
    }

    @Test fun aCommandRowShowsTheSlashNameAndPreparesIt() {
        var clicked = false
        compose.setContent { MaterialTheme { CommandRow("help", "Show what Hermes can do") { clicked = true } } }
        compose.onNodeWithText("/help").assertExists()
        compose.onNodeWithText("Show what Hermes can do").assertExists()
        compose.onNode(hasClickAction() and hasText("/help")).performClick()
        compose.runOnIdle { assertTrue(clicked) }
    }
}
