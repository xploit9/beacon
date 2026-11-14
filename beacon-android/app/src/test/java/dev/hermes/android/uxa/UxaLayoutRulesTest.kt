package dev.hermes.android.uxa

import androidx.compose.ui.unit.dp
import dev.hermes.android.ui.ReasoningSkeletonRows
import dev.hermes.android.ui.historyFilterFits
import dev.hermes.android.ui.reasoningKeptOnOpen
import dev.hermes.android.ui.setupDoneCopy
import dev.hermes.android.uiux.gaps.GapsAssets
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layout and theme P1s of the 1.2.0 UX review (lane UX-A). The pixels are pinned on the device (UxaLayoutUiTest); these pin the
 * decisions behind them, which a JVM test can reach.
 */
class UxaLayoutRulesTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/java").isDirectory }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    // P1-4: the add-on being missing is said once, on the last page, in plain words.
    @Test fun theLastPageSaysChatIsReadyAndWhatCallsNeedWhenTheAddOnIsMissing() {
        val missing = setupDoneCopy(addOnMissing = true)
        assertEquals("Chat is ready", missing.title)
        assertEquals("Calls need Beacon on Hermes.", missing.line)
        val there = setupDoneCopy(addOnMissing = false)
        assertEquals("You're connected", there.title)
        assertEquals("Beacon is ready.", there.line)
    }

    @Test fun theLastPagesWordsAreTranslatedInEveryLanguage() {
        val words = listOf(setupDoneCopy(true).title, setupDoneCopy(true).line, "Add it")
        for (language in GapsAssets.languages) for (text in words) {
            val shown = GapsAssets.translated(language, text)
            assertTrue("$language has no translation for \"$text\"", shown.isNotBlank())
            if (language != "en") assertFalse("$language shows the English \"$text\"", shown == text)
        }
    }

    @Test fun thePermissionPagesNeverShowTheAddOnNoticeAndNoWordHasPluginJargonOrADash() {
        val wizard = source("ui/SetupWizard.kt")
        val permissions = wizard.substringAfter("\"permission-microphone\", \"permission-notifications\", \"permission-full-screen\" ->").substringBefore("\"done\" ->")
        assertTrue("the permission branch was found", permissions.contains("setup-permission-primary"))
        assertFalse("a permission page shows the add-on notice", permissions.contains("setupPluginMessage"))
        // The words the person reads: the string the view model stores when Hermes has no Beacon add-on.
        val message = source("BeaconViewModel.kt").lineSequence().first { it.contains("val pluginMessage = ") }.substringAfter("== \"absent\") \"").substringBefore('"')
        assertEquals("Calls need Beacon on Hermes.", message)
        assertFalse("the notice is plain words: $message", message.contains("plugin", ignoreCase = true) || message.contains("—"))
    }

    // P1-5: a footer is a sentence the person has to read; it wraps at every font size.
    @Test fun aSettingsSectionFooterWrapsInsteadOfEndingInAnEllipsis() {
        val kit = source("ui/SettingsKit.kt")
        val footer = kit.lineSequence().dropWhile { !it.contains("if (footer.isNotBlank()) Text(footer") }.take(2).joinToString("\n")
        assertTrue("the footer Text was found", footer.contains("Text(footer"))
        assertFalse("footer: $footer", footer.contains("maxLines") || footer.contains("Ellipsis"))
    }

    // P1-6: three equal segments only while the widest word fits one; else the filter sizes by its text and wraps.
    @Test fun theHistoryFilterIsSegmentedOnlyWhileItsWidestWordFitsASegment() {
        // A 338dp drawer: 12dp each side, the filter button and its gap leave 258dp for three segments.
        val room = 258.dp
        assertTrue("normal font: Threads is about 50dp wide", historyFilterFits(50.dp, 3, room))
        assertFalse("large font: Threads is about 66dp wide", historyFilterFits(66.dp, 3, room))
        assertFalse("largest font", historyFilterFits(100.dp, 3, room))
        assertFalse("a language with longer words", historyFilterFits(70.dp, 3, 200.dp))
    }

    @Test fun noHistoryFilterLabelIsEverEllipsized() {
        val chat = source("ui/ChatScreen.kt")
        val filters = chat.substringAfter("fun HistoryViewChoices(").substringBefore("\n}\n")
        assertTrue("the filter block was found", filters.contains("history-view-all"))
        assertFalse("a segment label is ellipsized", filters.contains("Ellipsis"))
    }

    // P1-8: the Reasoning sheet's loading state has the loaded state's shape, and a reopen keeps the options already read for this chat's model.
    @Test fun theReasoningSkeletonHasAsManyRowsAsTheUsualLevels() {
        assertEquals("five levels are the usual set a model offers", 5, ReasoningSkeletonRows)
    }

    @Test fun aReopenKeepsOnlyTheOptionsReadForTheSameRequest() {
        val same = "scope|/api/reasoning|session-1|model-a"
        val loaded = JSONObject().put("options", org.json.JSONArray(listOf("low", "high"))).put("_request_key", same)
        assertTrue(reasoningKeptOnOpen(loaded, same))
        assertFalse("another chat or model never shows these", reasoningKeptOnOpen(loaded, "scope|/api/reasoning|session-2|model-a"))
        assertFalse("a failed read is read again", reasoningKeptOnOpen(JSONObject(loaded.toString()).put("_error", "offline"), same))
        assertFalse("nothing read yet", reasoningKeptOnOpen(null, same))
        assertFalse("an answer without its request is not trusted", reasoningKeptOnOpen(JSONObject().put("options", org.json.JSONArray(listOf("low"))), same))
    }
}
