package dev.hermes.android.uiux.defects

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.sp
import dev.hermes.android.core.json
import dev.hermes.android.ui.*
import dev.hermes.android.uiux.gaps.GapsAssets
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** The defects the gap tests and the visual tour found, as plain functions: each one has its failing case here. */
class DefectsTest {
    private fun row(route: String, snapshot: SettingsSnapshot) = settingsRowSummary(settingsSummary(route, snapshot)) { it }
    private val others = GapsAssets.languages.filter { it != "en" }

    // Session usage: a failed refresh keeps the record

    @Test fun aUsageRecordIsReadableWhenItHoldsAnyFigureOrFieldBeyondTheMarkers() {
        assertTrue(sessionUsageReadable(json("input_tokens" to 5)))
        assertTrue(sessionUsageReadable(json("model" to "synthetic-model")))
        assertTrue("A failed refresh keeps the record and adds the marker", sessionUsageReadable(json("input_tokens" to 5, "_error" to "Synthetic failure")))
        assertTrue(sessionUsageReadable(json("input_tokens" to 5, "error" to "Synthetic failure")))
    }

    @Test fun aUsageRecordWithOnlyAFailureHasNothingToShow() {
        assertFalse(sessionUsageReadable(json("_error" to "Synthetic failure")))
        assertFalse(sessionUsageReadable(json("error" to "Synthetic failure")))
        assertFalse(sessionUsageReadable(json("success" to false, "message" to "Synthetic refusal")))
        assertFalse("Blank values are nothing", sessionUsageReadable(json("input_tokens" to "", "model" to org.json.JSONObject.NULL)))
        assertFalse(sessionUsageReadable(org.json.JSONObject()))
    }

    // Skills: a notice is a success only when a save produced it

    @Test fun onlyTheTwoSaveNoticesAreSuccessAndAnyWarningIsNot() {
        assertTrue(skillNoticeIsSuccess(SkillsSavedNotice))
        assertTrue(skillNoticeIsSuccess(SkillInstructionsSavedNotice))
        for (warning in listOf("This skill could not be saved", "Saved with warnings", "Skill X was not saved", "", "saved")) assertFalse(warning, skillNoticeIsSuccess(warning))
    }

    @Test fun thePhoneSettingsWriteTheSameTwoSaveNotices() {
        // NativePhoneSettings (outside the UI package) writes the notices; if its words drift, the success pill would silently turn into a warning row.
        val source = File(GapsAssets.appDir, "src/main/java/dev/hermes/android/NativePhoneSettings.kt").readText()
        // A skill switch shows its own change; only saved instructions add a notice above the list.
        assertFalse(source.contains("notice = \"$SkillsSavedNotice\""))
        assertTrue(source.contains("notice = \"$SkillInstructionsSavedNotice\""))
    }

    // Hub: keywords in the phone's language and in English

    @Test fun everyKeywordedRowIsTranslatedAndFoundByItsOwnTranslatedWordsAndByEnglishOnes() {
        val keyworded = settingsSearchRoutes.filter { settingsKeywordsOf(it).isNotBlank() }
        assertTrue("The rows with search words", keyworded.size >= 10)
        for (language in others) for (route in keyworded) {
            val translated = GapsAssets.translated(language, settingsKeywordsOf(route))
            assertTrue("$language has no translation of the $route search words", translated.isNotBlank())
            fun shown(text: String) = GapsAssets.translated(language, text).ifBlank { text }
            val own = translated.split(Regex("[,、，،;\\s]+")).first { it.length >= 2 }
            assertTrue("$language: $route is found by \"$own\"", settingsRowMatches(route, shown(settingsLabel(route)), shown(settingsDescriptions.getValue(route)), translated, own))
            val english = settingsKeywordsOf(route).split(' ').first { it.length >= 3 }
            assertTrue("$language: $route is found by the English \"$english\"", settingsRowMatches(route, shown(settingsLabel(route)), shown(settingsDescriptions.getValue(route)), translated, english))
        }
    }

    @Test fun aWordFromAnotherRowDoesNotFindARow() {
        assertFalse(settingsRowMatches("Connection", "Connexion", "Serveur, compte, téléphones", "Serveur, compte connecté", "steer"))
        assertTrue(settingsRowMatches("Chat", "Chat", "Envoi et affichage", "pendant la réponse", "steer"))
    }

    // Hub: the Connection pill follows the page

    @Test fun theConnectionPillReadsReconnectingWhileThePhoneIsChecking() {
        assertEquals(SettingsRowSummary("", SettingsStatus.Working, "Reconnecting…"), row("Connection", SettingsSnapshot(connecting = true)))
        assertEquals("Connected wins over a check that is still finishing", SettingsStatus.Ok, row("Connection", SettingsSnapshot(connected = true, connecting = true)).status)
        assertEquals("A check wins over the signed-out word", SettingsStatus.Working, row("Connection", SettingsSnapshot(connecting = true, signedOut = true)).status)
        assertEquals(SettingsStatus.Error, row("Connection", SettingsSnapshot()).status)
    }

    @Test fun theConnectionVerdictHasTheStatesThePillMaps() {
        assertEquals(ConnectionVerdict.Checking, connectionVerdict(connected = false, working = true, authenticated = false, hasAddress = true))
        assertEquals(ConnectionVerdict.SignedOut, connectionVerdict(connected = false, working = false, authenticated = false, hasAddress = true))
        assertEquals(ConnectionVerdict.Offline, connectionVerdict(connected = false, working = false, authenticated = true, hasAddress = true))
    }

    // Hub: one server

    @Test fun oneServerIsNotPluralInAnyLanguage() {
        assertEquals(SettingsRowSummary("1 server"), row("MCP servers", SettingsSnapshot(mcpServers = 1)))
        for (language in others) {
            assertTrue("$language has no \"1 server\"", GapsAssets.translated(language, "1 server").isNotBlank())
        }
    }

    // Hub Health pill and Health page: one mapping

    @Test fun theHealthPillWordsFollowTheStatusThePageUses() {
        assertEquals(SettingsRowSummary("", SettingsStatus.Ok, "Healthy"), row("Health", SettingsSnapshot(serverOverall = "ok")))
        assertEquals(SettingsRowSummary("", SettingsStatus.Attention, "Needs attention"), row("Health", SettingsSnapshot(serverOverall = "degraded")))
        assertEquals(SettingsRowSummary("", SettingsStatus.Error, "Failed"), row("Health", SettingsSnapshot(serverOverall = "down")))
        assertEquals("Hermes' own word wins over the older yes/no", SettingsStatus.Error, row("Health", SettingsSnapshot(serverHealthy = true, serverOverall = "error")).status)
        assertEquals("No word and no yes/no is unknown, not healthy", SettingsRowSummary(), row("Health", SettingsSnapshot()))
        for (status in listOf(SettingsStatus.Ok, SettingsStatus.Attention, SettingsStatus.Error)) assertTrue(GapsAssets.translated("de", serverHealthWord(status)).isNotBlank())
    }

    // Reasoning, offline text and the call title are translated

    @Test fun everyReasoningWordSentenceAndErrorTextHasAnEntryInEveryLanguage() {
        val missing = (ReasoningStrings + listOf(OfflineReadError, DefaultReadError) + CallTitleStrings).distinct().flatMap { text -> others.filter { GapsAssets.translated(it, text).isBlank() }.map { "$it: $text" } }
        assertTrue("Without a translation:\n" + missing.take(20).joinToString("\n"), missing.isEmpty())
    }

    @Test fun theReasoningListHoldsEveryLevelWordAndSentence() {
        for (level in reasoningLevels) {
            assertTrue(reasoningLevelLabel(level) in ReasoningStrings)
            assertTrue(reasoningLevelDescription(level) in ReasoningStrings)
        }
        assertTrue("The sentence for a level Hermes does not name", reasoningLevelDescription("provider-own-level") in ReasoningStrings)
    }

    @Test fun theOfflineAndDefaultErrorsAreTheTextsRecordErrorAndTheViewModelUse() {
        assertEquals(DefaultReadError, recordError(json("success" to false)))
        val source = File(GapsAssets.appDir, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText()
        assertTrue(source.contains("put(\"_error\", dev.hermes.android.ui.OfflineReadError)"))
    }

    // Reduced motion: the status glyph is the still icon

    @Test fun withReducedMotionNoStatusGlyphMovesAndEveryStateHasAStillIcon() {
        for (status in SettingsStatus.entries) for (signal in listOf(true, false)) {
            assertFalse("$status signal=$signal must not move", settingsGlyphMoves(status, signal, motion = false))
            assertNotNull(settingsStaticGlyph(status))
        }
        assertSame("Working has no icon of its own, so it shows the hourglass", Icons.Rounded.HourglassTop, settingsStaticGlyph(SettingsStatus.Working))
        for (status in SettingsStatus.entries.filter { it != SettingsStatus.Working }) assertSame(settingsStatusIcon(status), settingsStaticGlyph(status))
    }

    @Test fun withMotionTheBeaconStillMovesWhereItDidBefore() {
        assertTrue(settingsGlyphMoves(SettingsStatus.Working, signal = false, motion = true))
        assertTrue(settingsGlyphMoves(SettingsStatus.Ok, signal = true, motion = true))
        assertTrue(settingsGlyphMoves(SettingsStatus.Attention, signal = true, motion = true))
        assertFalse("A pill's glyph is the icon", settingsGlyphMoves(SettingsStatus.Ok, signal = false, motion = true))
        assertFalse(settingsGlyphMoves(SettingsStatus.Error, signal = false, motion = true))
    }

    // Right-to-left: content reads in its own direction

    @Test fun contentTakesTheDirectionOfItsFirstStrongCharacterAndKeepsTheRestOfTheStyle() {
        val style = TextStyle(fontSize = 17.sp).contentDirection()
        assertEquals(TextDirection.Content, style.textDirection)
        assertEquals(17.sp, style.fontSize)
        assertEquals("Labels keep the layout's direction", TextDirection.Unspecified, TextStyle().textDirection)
    }
}
