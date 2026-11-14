package dev.hermes.android.ui

import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review R25 Should 4 and Nice 1: the phone lists only commands that run from it, and a hidden one typed by hand is never sent to the model. */
class PhoneCommandsTest {
    /** As the app reads Hermes' catalog on a shared gateway: `runs` false marks a built-in Hermes refuses for this phone, `own` the owner's commands. */
    private val catalog = listOf(
        json("name" to "help", "description" to "Show available commands"),
        json("name" to "new", "description" to "Start a new session", "runs" to false),
        json("name" to "history", "description" to "Show conversation history", "runs" to false),
        json("name" to "copy", "description" to "Copy the last assistant response to clipboard"),
        json("name" to "standup", "description" to "Post the daily standup note", "own" to true),
        json("name" to "paste", "description" to "Paste my clipboard notes", "own" to true),
    )

    @Test fun aBuiltInHermesWillNotRunForThePhoneIsNotListed() {
        assertEquals(listOf("help", "standup", "paste"), mobileSlashCommands(catalog).map { it.string("name") })
    }

    @Test fun theOwnersCommandsStayListedWhateverTheirName() {
        val own = json("name" to "new", "description" to "My own new-day routine", "own" to true, "runs" to false)
        assertEquals(listOf("new"), mobileSlashCommands(listOf(own)).map { it.string("name") })
    }

    @Test fun aHiddenCommandTypedByHandIsKnownSoItIsNotSent() {
        for (typed in listOf("/copy", "/Copy that", "/new", "/NEW chat about tides", "/history 5")) assertTrue(typed, phoneHiddenCommand(typed, catalog))
        // Listed commands run; unknown words, paths and text that only mentions a command go to the model as before.
        for (typed in listOf("/help", "/standup", "/paste", "/unknown thing", "/path/to/file", "hello /copy", "", "/")) assertFalse(typed, phoneHiddenCommand(typed, catalog))
        assertFalse("No catalog, nothing is known", phoneHiddenCommand("/copy", emptyList()))
    }
}
