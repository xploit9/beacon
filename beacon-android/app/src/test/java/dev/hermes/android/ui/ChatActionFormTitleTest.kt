package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner-Hermes check of F9, finding W2: Chat actions > "Delete chat" opened a confirm titled "Delete conversation", because the form took the action's id
 * as its title while the menu shows its mapped label. Every chat action that opens a form is titled with the menu's own words.
 */
class ChatActionFormTitleTest {
    private val oldWords = listOf("conversation", "session", "turn", "retained", "steer")

    @Test fun everyChatActionFormIsTitledWithTheMenusLabel() {
        for (key in ChatActionFormKeys) {
            val title = chatActionFormTitle(key)
            assertEquals(key, conversationActionLabel(key, pinned = false, archived = false), title)
            for (word in oldWords) assertFalse("$key: \"$title\" says $word", title.contains(word, ignoreCase = true))
        }
        assertEquals("Delete chat", chatActionFormTitle("Delete conversation"))
        assertEquals("Undo last message", chatActionFormTitle("Undo last turn"))
    }

    @Test fun everyChatActionFormTitleIsTranslated() {
        val locales = JSONObject(File(listOf(".", "app", "beacon-android/app").first { File(it, "src/main/assets/native-locales.json").isFile }, "src/main/assets/native-locales.json").readText())
        for (language in locales.keys().asSequence().filter { it != "en" }) for (key in ChatActionFormKeys) {
            val title = chatActionFormTitle(key)
            assertTrue("$language: $title", translateUi(locales.getJSONObject(language), title) != title)
        }
    }
}
