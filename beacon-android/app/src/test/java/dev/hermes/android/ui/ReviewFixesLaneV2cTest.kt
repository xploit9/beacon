package dev.hermes.android.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import dev.hermes.android.assist.Caption
import dev.hermes.android.assist.liveStatusLabel
import dev.hermes.android.assist.liveWords
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.callStatusLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Lane V2C: the fixes from the review of the hard-to-reach screens (live call, overlay, approvals, rules), as far as plain JVM tests
 * can see them: the fade's rule, what the overlay shows beside an approval, the one place a tool is named, the light-theme speaker,
 * and the source guards for the lines taken off the call screen. The Compose side is in the matching androidTest classes.
 */
class ReviewFixesLaneV2cTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText()

    private fun busy(state: String) = CallState(
        active = true, state = state, callId = "v2c",
        toolReceipts = listOf(requireNotNull(CallToolReceipt.from(JSONObject()
            .put("request_id", "request-1").put("tool_name", "mcp__gmail__search_messages").put("status", "running")))),
    )

    // --- 1. the fade shows only while there is more to scroll to ---

    @Test fun theBottomFadeShowsOnlyWhileTheTextCanScrollForward() {
        assertEquals(VerticalFades(top = false, bottom = true), verticalFades(canScrollBackward = false, canScrollForward = true))
        // Text that fits, or a card scrolled to its end, has no fade at all.
        assertEquals(VerticalFades(top = false, bottom = false), verticalFades(canScrollBackward = false, canScrollForward = false))
        assertEquals(VerticalFades(top = false, bottom = false), verticalFades(canScrollBackward = true, canScrollForward = false))
        // Scrolled into the middle, the bottom fade stays: there is still more below.
        assertEquals(VerticalFades(top = false, bottom = true), verticalFades(canScrollBackward = true, canScrollForward = true))
    }

    @Test fun theTopFadeIsOptInAndShowsOnlyOnceTheTextHasScrolled() {
        assertEquals(VerticalFades(top = false, bottom = false), verticalFades(canScrollBackward = true, canScrollForward = false, topToo = false))
        assertEquals(VerticalFades(top = true, bottom = false), verticalFades(canScrollBackward = true, canScrollForward = false, topToo = true))
        assertEquals(VerticalFades(top = false, bottom = true), verticalFades(canScrollBackward = false, canScrollForward = true, topToo = true))
    }

    @Test fun everyApprovalNoteAndTheCaptionCardUseTheFadeWithTheScrollStateTheyScrollWith() {
        mapOf(
            "assist/AssistLive.kt" to "verticalScrollFade(scroll).verticalScroll(scroll)",
            "ui/HermesPhoneApproval.kt" to "verticalScrollFade(scroll).verticalScroll(scroll)",
            "BeaconActivity.kt" to "verticalScrollFade(scroll).verticalScroll(scroll)",
            "ui/LiveCallUi.kt" to "verticalScrollFade(scroll, top = true)",
        ).forEach { (file, use) -> assertTrue("$file fades its scrolling text: $use", source(file).contains(use)) }
        // The fade removes the content's own pixels on an offscreen layer, so it works on any background in both themes.
        val fade = source("ui/VerticalScrollFade.kt")
        assertTrue(fade.contains("CompositingStrategy.Offscreen") && fade.contains("BlendMode.DstOut"))
    }

    // --- 1. the overlay beside an approval card ---

    @Test fun whileAnApprovalCardIsUpOnlyTheLastCaptionLineShowsAndNoToolLine() {
        val captions = listOf(Caption(true, "Text Alex that I am late"), Caption(false, "Done. Shall I send it?"))
        assertEquals(listOf(captions.last()) to null, liveWords(captions, "Using Gmail…", card = true))
        assertEquals(captions to "Using Gmail…", liveWords(captions, "Using Gmail…", card = false))
        // Only a tool line and no words: nothing is left to draw beside the card.
        assertEquals(emptyList<Caption>() to null, liveWords(emptyList(), "Using Gmail…", card = true))
        assertEquals(listOf(captions.first()) to null, liveWords(captions.take(1), null, card = true))
    }

    // --- 2. one place for the tool in use ---

    @Test fun theTextBoxAndTheHeaderSayTheStateAndOnlyTheToolLineNamesTheTool() {
        for (state in listOf("listening", "thinking", "speaking")) {
            val call = busy(state)
            val withTool = callStatusLabel(call)
            val stateOnly = callStatusLabel(call, withTool = false)
            assertEquals("Using Gmail…", withTool)
            assertFalse("the state alone does not name the tool: $stateOnly", stateOnly.contains("Gmail"))
            assertEquals(stateOnly, liveStatusLabel(call, locked = false))
        }
        assertEquals("Thinking", callStatusLabel(busy("thinking"), withTool = false))
        assertEquals("Speaking", callStatusLabel(busy("speaking"), withTool = false))
        // Held and connecting still win, as before.
        assertEquals("Call on hold", callStatusLabel(busy("listening").copy(held = true), withTool = false))
    }

    @Test fun theHeaderAndTheOverlayTextBoxAskForTheStateOnly() {
        assertTrue(source("ui/LiveCallUi.kt").contains("callStatusText(call, withTool = false)"))
        val overlay = source("assist/AssistOverlay.kt")
        assertTrue(overlay.contains("uiText(liveStatusLabel(call, phoneLocked))"))
        assertFalse(overlay.contains("callStatusText("))
        // The tool line above the text box is the status template, so it reads like the tool card.
        assertTrue(overlay.contains("tool?.statusTemplate()"))
    }

    // --- 3. the explanatory lines are off the call screen ---

    @Test fun theCallScreenDrawsNoContextNoticeNoSaveStateLineAndNoApprovalCaption() {
        val call = source("ui/LiveCallUi.kt")
        listOf("contextNotice", "beacon-context-notice", "noticeMax", "transcriptLabel", "approvalCaption", "beacon-tool-approval-caption")
            .forEach { assertFalse("$it must be gone from the call screen", call.contains(it)) }
        // The receipt keeps who allowed the tool; only the caption for it is gone.
        assertFalse(source("voice/CallToolApproval.kt").contains("approvalCaption"))
    }

    // --- 4. the speaker in the light theme ---

    @Test fun aDisabledControlOnTheCallSurfaceIsDarkWithAMutedGlyphWhateverTheAppTheme() {
        val surface = CallBackground
        val container = CallControlDisabledContainer.compositeOver(surface)
        val glyph = CallControlDisabledContent
        assertEquals(1f, glyph.alpha, 0f)
        assertTrue("the fill stays dark: ${container.luminance()}", container.luminance() < .1f)
        assertTrue("the glyph reads on it: ${contrast(glyph, container)}", contrast(glyph, container) >= 3f)
        // The speaker takes these, not the theme's own (light) disabled colours, like the other round controls.
        val speaker = source("ui/LiveCallUi.kt").substringAfter("@Composable private fun CallSpeakerControl").substringBefore("DropdownMenu(")
        assertTrue(speaker.contains("disabledContainerColor = CallControlDisabledContainer") && speaker.contains("disabledContentColor = CallControlDisabledContent"))
    }

    private fun contrast(a: Color, b: Color): Float {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + .05f) / (lo + .05f)
    }

    // --- 5. the call transcript has a content direction ---

    @Test fun theCallTranscriptReadsEachLineInItsOwnDirection() {
        val captions = source("ui/LiveCallUi.kt").substringAfter("@Composable internal fun LiveCallCaptions").substringBefore("internal data class CallUiActions")
        assertTrue(captions.contains(".merge(ContentDirection)"))
    }

    // --- 8, 12. the approval dialogs ---

    @Test fun theApprovalCardsHaveNoPreviewLabelAndTheDialogNoReviewSentence() {
        val activity = source("BeaconActivity.kt")
        val phone = source("ui/HermesPhoneApproval.kt")
        assertFalse(phone.contains("Content preview"))
        assertFalse(activity.substringAfter("internal fun CallToolApprovalDialog").contains("Content preview"))
        assertFalse(activity.contains("Review the details before"))
        // The message text reads in its own direction.
        assertTrue(phone.contains("bodyMedium.merge(ContentDirection)"))
    }

    // --- 9. quiet hours in a right-to-left screen ---

    @Test fun theQuietHoursClockIsIsolatedSoItKeepsItsOrder() {
        assertTrue(source("ui/ProactiveSetup.kt").contains("value = isolateBidi(time)"))
    }

    // --- 10. the rule badge ---

    @Test fun aRuleSourceReadsAsAWordAndAnythingWhenThereIsNone() {
        assertEquals("Email", ruleSourceWords("email"))
        assertEquals("Email", ruleSourceWords("  email "))
        assertEquals("Google workspace", ruleSourceWords("google_workspace"))
        assertNull(ruleSourceWords(null))
        assertNull(ruleSourceWords(""))
        assertNull(ruleSourceWords("   "))
        // Every place that shows a rule's source goes through it.
        assertTrue(source("ui/BeaconSettings.kt").contains("ruleSourceLabel(rule.opt(\"signal\") as? String)"))
        // The importance rule's badge and the earlier-ratings sheet (the helper's own declaration is the third mention).
        assertEquals(3, Regex("ruleSourceLabel\\(").findAll(source("ui/EmailRuleSheet.kt")).count())
    }

    @Test fun theEmailSourceIsTranslatedInEveryLanguage() {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText())
        val key = normalized("Email")
        val missing = catalog.keys().asSequence().filter { catalog.getJSONObject(it).optString(key).isBlank() }.toList()
        assertTrue("These languages lack \"$key\": $missing", missing.isEmpty())
    }

    // --- 13. calls off on a rule that calls ---

    @Test fun aRuleThatCallsSaysCallsAreOffWhenThisHermesCannotPlaceCalls() {
        val block = source("ui/BeaconSettings.kt").substringAfter("private fun ProactiveRuleBlock").substringBefore("/** The assistant's name")
        assertTrue(block.contains("callsOff && rule.string(\"action\") == \"call\""))
        assertTrue(block.contains("uiText(\"Calls off on this Hermes\")"))
        assertTrue(source("ui/BeaconSettings.kt").contains("callsOff = !callbacksAvailable"))
    }
}
