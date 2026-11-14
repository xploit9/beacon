package dev.hermes.android.ui

import dev.hermes.android.AppState
import dev.hermes.android.ChatState
import dev.hermes.android.InitialChatStep
import dev.hermes.android.initialChatStep
import org.junit.Assert.*
import org.junit.Test

/**
 * Live check 8 Nov 2026: right after signing in, the first new chat had dimmed controls and Attach did nothing for about four minutes, until a message was typed.
 * Nothing had asked for the chat's conversation again after the first start was skipped (the setup was still showing, or the app was away), and Attach was
 * off for any chat without a conversation, even one that could be written to. Synthetic state only.
 */
class NewChatOpensTest {
    private val waiting = AppState(authenticated = true, tab = "Chat", initialChatReady = true)

    @Test fun aNewChatWithNoConversationAndNothingOpeningOneIsAskedToOpen() {
        assertTrue(initialChatNeedsOpening(waiting, ChatState()))
    }

    @Test fun itIsNotAskedWhenAnotherStepOwnsTheMoment() {
        assertFalse("setup still showing", initialChatNeedsOpening(waiting.copy(setupWizard = true), ChatState()))
        assertFalse("already opening", initialChatNeedsOpening(waiting.copy(newChatOpening = true), ChatState()))
        assertFalse("an opening that failed waits for its Retry", initialChatNeedsOpening(waiting.copy(newChatError = "Tap Retry"), ChatState()))
        assertFalse("not on the chat tab", initialChatNeedsOpening(waiting.copy(tab = "More"), ChatState()))
        assertFalse("the draft is not restored yet", initialChatNeedsOpening(waiting.copy(initialChatReady = false), ChatState()))
        assertFalse("signed out", initialChatNeedsOpening(waiting.copy(authenticated = false), ChatState()))
        assertFalse("a conversation is open", initialChatNeedsOpening(waiting, ChatState(sessionId = "chat-1")))
    }

    @Test fun attachIsOnForAChatThatCanBeWrittenToEvenBeforeItsConversationExists() {
        assertTrue("new chat, no conversation yet", composerAttachEnabled(canonical = false, editable = true, callActive = false, readOnly = false))
        assertTrue("a conversation", composerAttachEnabled(canonical = true, editable = true, callActive = false, readOnly = false))
    }

    @Test fun attachStaysOffWhereItCannotWork() {
        assertFalse("the chat is not ready to be written to", composerAttachEnabled(canonical = false, editable = false, callActive = false, readOnly = false))
        assertFalse("during a call", composerAttachEnabled(canonical = true, editable = true, callActive = true, readOnly = false))
        assertFalse("read-only", composerAttachEnabled(canonical = true, editable = true, callActive = false, readOnly = true))
    }

    /** The inputs the view model reads when anything asks for the empty chat behind a new conversation, and what each outcome does to them. */
    private class Account {
        var opening = false; var attempt = false; var shell = false; var open = false; var failed = false; var creates = 0
        fun ask(foreground: Boolean = true): InitialChatStep = initialChatStep(unreachable = false, connected = true, foreground = foreground, composable = !open, setupWizard = false,
            opening = opening, failed = failed, attemptPending = attempt, shellKept = shell).also { step ->
            when (step) {
                InitialChatStep.Create -> { creates++; opening = true; attempt = true }
                InitialChatStep.Resume -> opening = true
                InitialChatStep.Wait -> Unit
            }
        }
        fun created() { opening = false; attempt = false; shell = true }
        fun selected() { open = true; opening = false }
    }

    @Test fun theForegroundHookTheComposerEffectAndAnAttachTapTogetherCreateOneSession() {
        val account = Account()
        val steps = listOf(account.ask(), account.ask(), account.ask())
        assertEquals(listOf(InitialChatStep.Create, InitialChatStep.Wait, InitialChatStep.Wait), steps)
        assertEquals(1, account.creates)
        account.created()
        // The conversation exists but is not selected yet: asking again resumes it and never creates another.
        assertEquals(InitialChatStep.Resume, account.ask())
        assertEquals(InitialChatStep.Wait, account.ask())
        assertEquals(1, account.creates)
        account.selected()
        assertEquals(InitialChatStep.Wait, account.ask())
        assertEquals(1, account.creates)
    }

    @Test fun anOpenWhoseAnswerWasLostWaitsForRetryInsteadOfCreatingAgain() {
        val account = Account()
        assertEquals(InitialChatStep.Create, account.ask())
        account.opening = false; account.failed = true
        assertEquals(InitialChatStep.Wait, account.ask())
        assertEquals(InitialChatStep.Wait, account.ask())
        assertEquals(1, account.creates)
    }

    @Test fun nothingOpensWhileTheAppIsAwayOrSetupShowsOrHermesCannotBeReached() {
        assertEquals(InitialChatStep.Wait, Account().ask(foreground = false))
        val inputs = { unreachable: Boolean, connected: Boolean, setup: Boolean, composable: Boolean ->
            initialChatStep(unreachable, connected, true, composable, setup, opening = false, failed = false, attemptPending = false, shellKept = false)
        }
        assertEquals(InitialChatStep.Wait, inputs(false, true, true, true))
        assertEquals(InitialChatStep.Wait, inputs(false, true, false, false))
        assertEquals(InitialChatStep.Wait, inputs(true, false, false, true))
        assertEquals("an unreachable account that is reachable again opens", InitialChatStep.Create, inputs(true, true, false, true))
        assertEquals(InitialChatStep.Create, inputs(false, true, false, true))
    }

    /** Live-suite flake 8 Nov 2026: a trigger between "chat created" and "chat selected" read the new chat a second time. */
    @Test fun aChatBeingSelectedIsNotOpenedAgain() {
        fun ask(shellKept: Boolean, selecting: Boolean) = initialChatStep(unreachable = false, connected = true, foreground = true, composable = true, setupWizard = false,
            opening = false, failed = false, attemptPending = false, shellKept = shellKept, selecting = selecting)
        assertEquals(InitialChatStep.Wait, ask(shellKept = true, selecting = true))
        assertEquals(InitialChatStep.Wait, ask(shellKept = false, selecting = true))
        assertEquals("nothing is being selected: the kept shell resumes", InitialChatStep.Resume, ask(shellKept = true, selecting = false))
        assertEquals(InitialChatStep.Create, ask(shellKept = false, selecting = false))
    }
}
