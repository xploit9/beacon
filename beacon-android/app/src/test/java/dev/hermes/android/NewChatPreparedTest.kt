package dev.hermes.android

import org.junit.Assert.*
import org.junit.Test

/**
 * Lane S3: every trigger that opens the empty chat behind a new conversation waits for the chat to be ready, and only sign-in's own start made it ready.
 * A route that ended on the Chat tab some other way (a return from the assistant tab with no chat to go back to) therefore left it dimmed for good.
 * That route prepares the chat itself when, and only when, nothing else owns the moment. Synthetic state only.
 */
class NewChatPreparedTest {
    private fun prepares(authenticated: Boolean = true, tab: String = "Chat", ready: Boolean = false, noConversation: Boolean = true, selecting: Boolean = false) =
        newChatNeedsPreparing(authenticated, tab, ready, noConversation, selecting)

    @Test fun aChatTabWithNoConversationThatWasNeverMadeReadyIsPrepared() {
        assertTrue(prepares())
    }

    @Test fun aChatThatIsAlreadyReadyIsLeftToTheTriggersThatOpenIt() {
        assertFalse(prepares(ready = true))
    }

    @Test fun nothingIsPreparedWhileAnotherStepOwnsTheMoment() {
        assertFalse("signed out", prepares(authenticated = false))
        assertFalse("another tab", prepares(tab = "More"))
        assertFalse("the assistant tab", prepares(tab = "Beacon"))
        assertFalse("a conversation is open", prepares(noConversation = false))
        assertFalse("a conversation is being selected", prepares(selecting = true))
    }

    /** Round 2: the recovery of a first chat Hermes no longer has removes the remembered shell itself, so it must not need the shell to still be there. */
    @Test fun theRecoveryOfAVanishedFirstChatContinuesAfterItForgetsTheShell() {
        assertTrue("the shell is still remembered", shellRecoveryCurrent(true, "shell-1", "shell-1"))
        assertTrue("the recovery already forgot it", shellRecoveryCurrent(true, null, "shell-1"))
        assertFalse("another shell replaced it", shellRecoveryCurrent(true, "shell-2", "shell-1"))
        assertFalse("the open moved on", shellRecoveryCurrent(false, null, "shell-1"))
    }
}
