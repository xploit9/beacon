package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The R7 text changes: Turkish Send, the Steer/Queue hint, the unconfirmed-message wording, and no unused strings left behind. */
class R7LocaleTest {
    private val catalog by lazy {
        val file = requireNotNull(listOf("src/main/assets/native-locales.json", "app/src/main/assets/native-locales.json", "beacon-android/app/src/main/assets/native-locales.json")
            .map(::File).firstOrNull { it.isFile }) { "Run this check from the Beacon app, Android project or repository root." }
        JSONObject(file.readText(Charsets.UTF_8))
    }
    private val languages = listOf("en", "it", "ja", "ru", "es", "de", "zh", "zh-Hant", "pt", "ko", "fr", "cs", "tr", "pl", "vi", "ar")

    /** Strings the queue box and the old unconfirmed-send block used to show; nothing in the code reads them any more. */
    private val deadKeys = listOf(
        "queued on this phone", "sending guidance", "queued message canceled", "message rejected", "send outcome unknown", "will send after this reply",
        "change the reply", "send after", "guidance could not be sent; queued on this phone", "not sure your last message arrived.", "correct the response...",
        "retry queued message", "edit queued message", "remove queued message",
        // Replaced by "Not confirmed yet" and by "Tap your message to check it." in this round.
        "not sent yet", "check whether your message arrived, then resend or dismiss.",
    )

    @Test fun noLanguageKeepsAStringNothingShowsAnyMore() {
        assertEquals(languages.sorted(), catalog.keys().asSequence().toList().sorted())
        val left = languages.flatMap { language -> deadKeys.filter { catalog.getJSONObject(language).has(it) }.map { "$language: $it" } }
        assertTrue("unused strings still in the catalog: $left", left.isEmpty())
    }

    @Test fun theNewTextsExistInAllSixteenLanguagesAndAreTranslated() {
        val english = mapOf(
            "not confirmed yet" to "Not confirmed yet",
            "tap your message to check it." to "Tap your message to check it.",
            "wait for the current reply first." to "Wait for the current reply first.",
            "steer changes the reply now. queue sends it after." to "Steer changes the reply now. Queue sends it after.",
        )
        for ((key, text) in english) for (language in languages) {
            val value = catalog.getJSONObject(language).optString(key)
            assertTrue("$language is missing '$key'", value.isNotBlank())
            if (language == "en") assertEquals(text, value) else assertNotEquals("$language kept the English text for '$key'", text, value)
        }
    }

    @Test fun theHintIsOneShortLineInEveryLanguage() {
        for (language in languages) {
            val hint = catalog.getJSONObject(language).getString("steer changes the reply now. queue sends it after.")
            assertTrue("$language hint is not one short line: $hint", hint.length <= 110 && '\n' !in hint)
        }
    }

    @Test fun turkishSendIsTheButtonWordNotTheInfinitive() {
        assertEquals("Gönder", catalog.getJSONObject("tr").getString("send"))
        assertFalse(catalog.getJSONObject("tr").getString("send").contains("mek"))
    }
}
