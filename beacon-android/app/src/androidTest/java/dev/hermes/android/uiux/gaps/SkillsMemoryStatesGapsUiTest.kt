package dev.hermes.android.uiux.gaps

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.data.NativeMemoryDocument
import dev.hermes.android.data.NativeMemoryState
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.data.NativeSkillContent
import dev.hermes.android.ui.NativeMemoryScreen
import dev.hermes.android.ui.NativeSkillsScreen
import dev.hermes.android.ui.NativeSkillsState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Skills and Memory in their less common states, driven by plain state: loading, failed, empty, saving, notices, Cancel and the draft. */
class SkillsMemoryStatesGapsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun skill(name: String, enabled: Boolean = false, needs: List<String> = emptyList()) = NativeSkill(name, "A $name skill", "general", enabled, "agent", needs)
    private class Calls { var retry = 0; var save = 0; val edits = mutableListOf<Boolean>(); val searches = mutableListOf<String>(); val drafts = mutableListOf<String>() }

    private fun skills(initial: NativeSkillsState, calls: Calls = Calls()): () -> NativeSkillsState {
        var state by mutableStateOf(initial)
        compose.showPage {
            NativeSkillsScreen(state, { calls.searches += it; state = state.copy(search = it) }, { state = state.copy(selected = it) }, {}, { _, _ -> },
                { calls.edits += it; state = state.copy(editing = it) }, { calls.drafts += it; state = state.copy(draft = it) }, { calls.save++ }, { calls.retry++ })
        }
        return { state }
    }
    private val work = NativeSkillsState(profile = "work", skills = listOf(skill("apple-notes", enabled = true), skill("work-inbox")))

    // Skills: loading, failed, empty, saving

    @Test fun aFailedReadSaysWhyAndRetryReadsAgainButOnlyWhenNothingRuns() {
        val calls = Calls()
        skills(work.copy(error = "Synthetic skills failure"), calls)
        compose.onNodeWithText("Synthetic skills failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, calls.retry) }
        compose.onNodeWithTag("skill-row-apple-notes").assertIsDisplayed()
    }

    @Test fun whileAReadRunsThereIsNoRetryAndTheRowsStayUnderAThinBar() {
        skills(work.copy(error = "Synthetic skills failure", loading = true))
        compose.onNodeWithText("Synthetic skills failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("skill-row-apple-notes").assertIsDisplayed()
    }

    @Test fun theFirstLoadShowsPlaceholderRowsNotAnEmptyList() {
        skills(NativeSkillsState(profile = "work", loading = true))
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithText("No skills yet").assertDoesNotExist()
        compose.onNodeWithText("No matching skills").assertDoesNotExist()
        compose.onNodeWithTag("skills-counts").assertDoesNotExist()
    }

    @Test fun noSkillsAtAllSaysNoSkillsYetWithoutAClearAction() {
        skills(NativeSkillsState(profile = "work"))
        compose.onNodeWithText("No skills yet").assertIsDisplayed()
        compose.onNodeWithText("Clear search").assertDoesNotExist()
        compose.onNodeWithTag("skills-counts").assertIsDisplayed()
    }

    @Test fun aSearchWithNoMatchOffersClearSearchWhichAlsoResetsTheFilter() {
        val calls = Calls()
        val read = skills(work, calls)
        compose.onNodeWithTag("skills-filter-needs-setup").performScrollTo().performClick()
        compose.onNodeWithText("No matching skills").assertIsDisplayed()
        compose.onNodeWithTag("skills-filter-needs-setup").assertIsSelected()
        compose.onNodeWithText("Clear search").performClick()
        compose.onNodeWithText("No matching skills").assertDoesNotExist()
        compose.onNodeWithTag("skills-filter-all").assertIsSelected()
        compose.onNodeWithTag("skill-row-work-inbox").assertIsDisplayed()
        compose.runOnIdle { assertEquals("Clear search also clears the typed search", "", read().search); assertEquals("", calls.searches.last()) }
    }

    @Test fun savingShowsAPillKeepsTheRowsAndTurnsTheSwitchesOff() {
        skills(work.copy(saving = true))
        compose.onNodeWithText("Saving skill…").assertIsDisplayed()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("skill-row-apple-notes").assertIsDisplayed()
        compose.onNodeWithContentDescription("Enable apple-notes").assertIsNotEnabled()
    }

    @Test fun aSavedNoticeIsAPillAndAWarningIsARowOfItsOwn() {
        var state by mutableStateOf(work.copy(notice = "Saved. New chats use this change."))
        compose.showPage { NativeSkillsScreen(state, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}) }
        compose.onNodeWithText("Saved. New chats use this change.").assertIsDisplayed()
        compose.onNode(hasText("Saved. New chats use this change.") and hasAnyAncestor(hasTestTag("settings-section:"))).assertDoesNotExist()
        compose.runOnIdle { state = work.copy(notice = "The skill name already exists") }
        compose.onNode(hasText("The skill name already exists") and hasAnyAncestor(hasTestTag("settings-section:"))).assertExists()
        compose.runOnIdle { state = work.copy(notice = "") }
        compose.onNodeWithText("The skill name already exists").assertDoesNotExist()
    }

    @Test fun aWarningThatMentionsSavedStillReadsAsAWarning() {
        skills(work.copy(notice = "This skill could not be saved"))
        compose.onNode(hasText("This skill could not be saved") and hasAnyAncestor(hasTestTag("settings-section:"))).assertExists()
    }

    @Test fun bothSaveNoticesAreSuccessPills() {
        skills(work.copy(notice = "Instructions saved. New chats use this change."))
        compose.onNodeWithText("Instructions saved. New chats use this change.").assertIsDisplayed()
        compose.onNode(hasText("Instructions saved. New chats use this change.") and hasAnyAncestor(hasTestTag("settings-section:"))).assertDoesNotExist()
    }

    // Skills: editing the instructions

    private val editing = NativeSkillsState(
        profile = "work", skills = listOf(skill("work-inbox")), selected = skill("work-inbox"), content = NativeSkillContent("work-inbox", "Steps"), editing = true, draft = "Steps",
    )

    @Test fun saveNeedsARealChangeAndCancelLeavesTheEditor() {
        val calls = Calls()
        val read = skills(editing, calls)
        compose.onNodeWithText("Save instructions").performScrollTo().assertIsNotEnabled()
        compose.onNode(hasSetTextAction() and hasText("Steps")).performTextReplacement("Steps, then verify")
        compose.onNodeWithText("Save instructions").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, calls.save); assertEquals("Steps, then verify", read().draft) }
        compose.onNodeWithText("Cancel").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf(false), calls.edits); assertFalse(read().editing) }
        compose.onNodeWithTag("skill-edit").performScrollTo().assertIsDisplayed()
    }

    @Test fun anEmptyDraftCannotBeSaved() {
        skills(editing.copy(draft = ""))
        compose.onNodeWithText("Save instructions").performScrollTo().assertIsNotEnabled()
    }

    @Test fun whileSavingNeitherSaveNorCancelCanBeUsed() {
        skills(editing.copy(draft = "Steps, then verify", saving = true))
        compose.onNodeWithText("Save instructions").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.onNodeWithText("Saving skill…").assertIsDisplayed()
    }

    @Test fun aFailedSaveShowsBesideSaveOnceWithNoRetryAndNotAtThePageTop() {
        skills(editing.copy(draft = "Steps, then verify", error = "Synthetic save failure"))
        compose.onAllNodesWithText("Synthetic save failure").assertCountEquals(1)
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Save instructions").performScrollTo().assertIsEnabled()
    }

    @Test fun whenNotEditingAFailedReadShowsAtTheTopWithRetry() {
        val calls = Calls()
        skills(editing.copy(editing = false, error = "Synthetic read failure"), calls)
        compose.onNodeWithText("Synthetic read failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, calls.retry) }
    }

    // Memory

    private fun memory(initial: NativeMemoryState): () -> NativeMemoryState {
        var state by mutableStateOf(initial)
        compose.showPage { NativeMemoryScreen(state, {}, { state = state.copy(draft = it) }, {}, {}) }
        return { state }
    }
    private fun doc(target: String = "memory", content: String = "Prefers quiet places") = NativeMemoryDocument(target, content, "r1", true, 2000)

    @Test fun theFirstLoadShowsPlaceholderRowsNotAnEmptyDocument() {
        memory(NativeMemoryState("s", loading = true))
        compose.onNodeWithTag("settings-skeleton").assertIsDisplayed()
        compose.onNodeWithText("No notes yet").assertDoesNotExist()
        compose.onNodeWithTag("memory-document").assertDoesNotExist()
    }

    @Test fun aLaterReadKeepsTheNotesUnderAThinBarAndTurnsEditingOff() {
        memory(NativeMemoryState("s", loading = true, documents = listOf(doc())))
        compose.onNodeWithText("Prefers quiet places").assertIsDisplayed()
        compose.onNodeWithTag("settings-refreshing").assertExists()
        compose.onNodeWithTag("settings-skeleton").assertDoesNotExist()
        compose.onNodeWithTag("memory-edit").assertIsNotEnabled()
    }

    @Test fun aFailedReadIsARowWithoutRetryAndNoDraftLine() {
        memory(NativeMemoryState("s", documents = listOf(doc()), error = "Synthetic memory failure"))
        compose.onNodeWithText("Synthetic memory failure").assertIsDisplayed()
        compose.onNodeWithText("Retry").assertDoesNotExist()
        compose.onNodeWithText("Your unsaved draft is kept. Review newer notes before saving again.").assertDoesNotExist()
        compose.onNodeWithText("Prefers quiet places").assertIsDisplayed()
    }

    @Test fun aFailedSaveWhileEditingSaysTheDraftIsKept() {
        memory(NativeMemoryState("s", documents = listOf(doc()), editing = "memory", draft = "Prefers quiet places, near water", error = "Synthetic memory failure"))
        compose.onNodeWithText("Synthetic memory failure").assertIsDisplayed()
        compose.onNodeWithText("Your unsaved draft is kept. Review newer notes before saving again.").assertIsDisplayed()
        compose.onNodeWithText("Save notes").assertIsEnabled()
    }

    @Test fun whileSavingTheButtonsSayAndStayOff() {
        memory(NativeMemoryState("s", documents = listOf(doc()), editing = "memory", draft = "Prefers quiet places, near water", saving = true))
        compose.onNodeWithText("Saving…").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Cancel edit").assertIsNotEnabled()
        compose.onNodeWithTag("settings-refreshing").assertExists()
    }
}
