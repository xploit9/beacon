package dev.hermes.android.chatsteer

import dev.hermes.android.ui.ChatStrings
import dev.hermes.android.ui.SettingsStrings
import dev.hermes.android.ui.busyComposerCaption
import dev.hermes.android.ui.normalized
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner, 24 Nov: "Send now" reads "Steer" everywhere. The labels are the same word in the Settings
 * option, the composer caption and button, the queue box action and a steer bubble's marker; every
 * language keeps its own short word, including Arabic (flagged for the native-reader pass).
 */
class ChatSteerStringsTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private val catalog by lazy { JSONObject(File(app, "src/main/assets/native-locales.json").readText()) }
    private val languages = listOf("en", "ar", "it", "ja", "ru", "es", "de", "zh", "zh-Hant", "pt", "ko", "fr", "cs", "tr", "pl", "vi")

    @Test fun everyLanguageHasItsOwnShortSteerWord() {
        assertEquals("the bundled languages", languages.sorted(), catalog.keys().asSequence().toList().sorted())
        assertEquals("English says the owner's word", "Steer", catalog.getJSONObject("en").getString("steer"))
        for (language in languages - "en") {
            val word = catalog.getJSONObject(language).optString("steer")
            assertTrue("$language has no Steer word", word.isNotBlank())
            assertFalse("$language copies English", normalized(word) == "steer")
            assertTrue("$language's Steer word is one short word or two, not a sentence: $word", word.trim().split(Regex("\\s+")).size <= 2)
        }
    }

    @Test fun everyOwnerFacingLabelSaysSteer() {
        assertEquals("Steer", busyComposerCaption(slash = false, hasFiles = false, busyAction = "steer", explicitQueue = false))
        assertTrue("the chat's fixed labels carry Steer", "Steer" in ChatStrings)
        assertFalse("the chat's fixed labels dropped Send now", "Send now" in ChatStrings)
        assertTrue("Settings carries the owner's word", "Steer" in SettingsStrings)
        assertFalse("Settings dropped Send now", "Send now" in SettingsStrings)
        val settings = File(app, "src/main/java/dev/hermes/android/ui/MoreScreen.kt").readText()
        assertTrue("the Settings option reads Steer", settings.contains("optionLabels = mapOf(\"steer\" to \"Steer\", \"queue\" to \"Queue\")"))
        val chat = File(app, "src/main/java/dev/hermes/android/ui/ChatScreen.kt").readText()
        assertTrue("the queue box action reads Steer", chat.contains("QueueBoxAction.Steer -> TextButton(onClick = { vm.steerQueued(item.id) }") && chat.contains("Text(uiText(\"Steer\"))"))
        assertTrue("a steer bubble's marker reads Steer", chat.contains("Text(uiText(\"Steer\"), style = nativeMetadataStyle()"))
        assertTrue("the composer label reads Steer", chat.contains("else if (chat.streamId.isNotBlank()) \"Steer\" else \"Send\""))
        assertTrue("search still knows the owner's word", File(app, "src/main/java/dev/hermes/android/ui/SettingsSearch.kt").readText().contains("\"Steer\""))
    }

    @Test fun noProductionStringStillSaysSendNow() {
        val offenders = File(app, "src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { "\"Send now\"" in it.readText() }.map { it.relativeTo(app).invariantSeparatorsPath }.toList()
        assertTrue("owner-facing Send now left in: $offenders", offenders.isEmpty())
    }

    @Test fun theArabicWordIsShortRtlAndNamedForTheNativeReaderPass() {
        val arabic = catalog.getJSONObject("ar").getString("steer")
        assertEquals("وجّه", arabic)
        assertTrue("Arabic letters only, no Latin mechanics: $arabic", arabic.all { it.code in 0x0600..0x06FF || it.isWhitespace() })
        assertTrue("the Arabic UI keeps right-to-left words", arabic.isNotBlank() && arabic.first().code in 0x0600..0x06FF)
    }
}
