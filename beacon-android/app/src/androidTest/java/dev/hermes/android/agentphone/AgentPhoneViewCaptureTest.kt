package dev.hermes.android.agentphone

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.ui.NativeLocalization
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Every screen and state of the setup view, drawn from a synthetic agent-phone frame (a sign-in screen with plain example data), in
 * dark and light, at 1.5x and 2x text, on a narrow phone and an unfolded one, and in Arabic right to left. Each state checks that no
 * text is cut and every tap target keeps 48 dp; with `-e shots 1` each is saved as a PNG for the report.
 */
class AgentPhoneViewCaptureTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private class Case(val name: String, val ui: ViewUi, val landscape: Boolean = false, val frame: Boolean = true, val row: TextRowState? = null,
        val checklist: Boolean = false, val waitFor: String? = null)

    private val live = ViewUi(ViewPhase.Live, hasFrame = true, helper = "ready")
    private fun flash(kind: FlashKind, line: String = "") = Flash(kind, line, 1)
    private fun row(text: String, hidden: Boolean = false) = TextRowState().apply { open = true; value = TextFieldValue(text); this.hidden = hidden }

    private val cases = listOf(
        Case("01-live", live),
        Case("02-live-landscape", live, landscape = true),
        Case("03-connecting", ViewUi(ViewPhase.Connecting), frame = false),
        Case("04-starting", ViewUi(ViewPhase.Starting), frame = false),
        Case("05-reconnecting", live.copy(phase = ViewPhase.Reconnecting)),
        Case("06-back-in-a-moment", live.copy(phase = ViewPhase.BackSoon)),
        Case("07-picture-lowered", live.copy(lowered = true, flash = flash(FlashKind.Lowered))),
        Case("08-opened-on-another-phone", live.copy(phase = ViewPhase.Replaced)),
        Case("09-lease-ended", live.copy(phase = ViewPhase.Ended)),
        Case("10-phone-off", live.copy(phase = ViewPhase.Stopped)),
        Case("11-start-refused", ViewUi(ViewPhase.StartRefused, line = "Turn on Hermes sign-in first."), frame = false),
        Case("12-removed", ViewUi(ViewPhase.Removed), frame = false),
        Case("13-picture-stopped", live.copy(phase = ViewPhase.Failed)),
        Case("14-hermes-not-answering", live.copy(phase = ViewPhase.HermesDown)),
        Case("15-not-set-up", ViewUi(ViewPhase.NotSetUp), frame = false),
        Case("16-other-profile", ViewUi(ViewPhase.NotYours), frame = false),
        Case("17-signed-out", ViewUi(ViewPhase.SignedOut), frame = false),
        Case("18-debugging-prompt", live.copy(prompt = true, promptSince = 1)),
        Case("19-debugging-prompt-cancel", live.copy(prompt = true, promptSince = 2), waitFor = "agent-phone-prompt-cancel"),
        Case("20-keyboard-row", live.copy(keyboard = true), row = row("kanto.agent")),
        Case("21-keyboard-row-hidden", live.copy(keyboard = true), row = row("a-synthetic-pass", hidden = true)),
        Case("22-keyboard-row-empty", live.copy(keyboard = true, helper = "starting"), row = row("")),
        Case("23-text-refused", live.copy(flash = flash(FlashKind.TextRefused))),
        Case("24-saved-notice", live.copy(flash = flash(FlashKind.Notice, "Saved: Kanto Foods · agent${'@'}example.com"))),
        Case("25-paste-off", live.copy(helper = "off")),
        Case("26-background", live.copy(background = true)),
        Case("27-setup-steps", live, checklist = true),
    )

    private var shown by mutableStateOf(cases.first())

    /** A plain sign-in screen of a made-up shop, drawn at the stream's 720x1600 (or turned). */
    private fun syntheticFrame(landscape: Boolean): ShownFrame {
        val (w, h) = if (landscape) 1600 to 720 else 720 to 1600
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(0xFFF7F3EE.toInt())
        paint.color = 0xFF1F6F5C.toInt(); canvas.drawRect(0f, 0f, w.toFloat(), 150f, paint)
        paint.color = 0xFFFFFFFF.toInt(); paint.textSize = 30f; canvas.drawText("12:40", 36f, 44f, paint)
        paint.textSize = 46f; paint.typeface = Typeface.DEFAULT_BOLD; canvas.drawText("Kanto Foods", 48f, 122f, paint)
        paint.typeface = Typeface.DEFAULT; paint.color = 0xFF2B2B2B.toInt(); paint.textSize = 40f
        canvas.drawText("Sign in to order", 48f, 240f, paint)
        paint.textSize = 28f; paint.color = 0xFF6B6B6B.toInt()
        canvas.drawText("Email", 48f, 320f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = 0xFF9A9A9A.toInt()
        val field = (w - 96).toFloat()
        canvas.drawRoundRect(RectF(48f, 336f, 48f + field, 426f), 16f, 16f, paint)
        canvas.drawRoundRect(RectF(48f, 496f, 48f + field, 586f), 16f, 16f, paint)
        paint.style = Paint.Style.FILL; paint.color = 0xFF2B2B2B.toInt(); paint.textSize = 32f
        canvas.drawText("agent${'@'}example.com", 72f, 392f, paint)
        paint.color = 0xFF6B6B6B.toInt(); paint.textSize = 28f; canvas.drawText("Password", 48f, 480f, paint)
        paint.color = 0xFF2B2B2B.toInt(); paint.textSize = 40f; canvas.drawText("••••••••••", 72f, 556f, paint)
        paint.color = 0xFF1F6F5C.toInt(); canvas.drawRoundRect(RectF(48f, 640f, 48f + field, 736f), 48f, 48f, paint)
        paint.color = 0xFFFFFFFF.toInt(); paint.textSize = 34f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("Sign in", w / 2f, 700f, paint)
        paint.textAlign = Paint.Align.LEFT; paint.color = 0xFF6B6B6B.toInt(); paint.textSize = 28f
        if (!landscape) for (i in 0 until 6) canvas.drawText("Today's pick ${i + 1}: rice bowl · £${8 + i}.40", 48f, 860f + i * 110f, paint)
        return ShownFrame(bitmap.asImageBitmap(), w, h, if (landscape) 1 else 0, 1)
    }

    private val portrait by lazy { syntheticFrame(false) }
    private val wide by lazy { syntheticFrame(true) }

    /**
     * Draws the screen at exactly [widthDp] x [heightDp]: when that is larger than the test emulator's window (the unfolded phone), the
     * density is lowered until it fits, so every layout decision is made at the real dp size and nothing is clamped.
     */
    private fun show(language: String, dark: Boolean, fontScale: Float, widthDp: Int, heightDp: Int) {
        compose.setContent {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
            val fit = minOf(density.density, constraints.maxWidth / widthDp.toFloat(), constraints.maxHeight / heightDp.toFloat())
            CompositionLocalProvider(LocalDensity provides Density(fit, fontScale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(widthDp.dp, heightDp.dp)) {
                    NativeLocalization(language) { BeaconTheme(dark = dark) {
                        val case = shown
                        key(case.name) {
                            AgentPhoneViewScreen(
                                ui = case.ui,
                                frame = if (!case.frame) null else if (case.landscape) wide else portrait,
                                name = "Beacon",
                                actions = ViewActions(paste = { ViewSession.Paste.Sent }),
                                row = case.row ?: androidx.compose.runtime.remember { TextRowState() },
                                // As the real view: the steps are always there (a sheet on a phone, beside the picture unfolded).
                                checklist = realChecklist,
                                checklistOpen = case.checklist,
                                rowKeyboard = false,
                            )
                        }
                    } }
                }
            }
            }
        }
    }

    /** The product's own setup steps (the page's rows, in the look's language), from synthetic facts: two done, two to do. */
    private val realChecklist: @androidx.compose.runtime.Composable () -> Unit = {
        dev.hermes.android.ui.AgentPhoneChecklist(dev.hermes.android.ui.agentPhoneChecklist(
            dev.hermes.android.data.AgentPhoneFacts(google = true, chrome = 136, chromeAutofill = false, logins = 0), emptySet(), chromeSeenBelow = true), onTick = { _, _ -> })
    }

    private fun texts(): List<Pair<String, TextLayoutResult>> = compose.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }, useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.firstOrNull()?.let { (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: it.layoutInput.text.text) to it }
        }

    private fun check(label: String) {
        for ((text, layout) in texts()) {
            if (text.isBlank()) continue
            val last = layout.lineCount - 1
            assertEquals("$label: \"$text\" shows every character", layout.layoutInput.text.length, layout.getLineEnd(last, visibleEnd = true))
            assertFalse("$label: \"$text\" ends in an ellipsis", layout.isLineEllipsized(last))
        }
        // The view's own bounds (with the setup-steps sheet open there is a second root).
        val root = compose.onNodeWithTag("agent-phone-view").getUnclippedBoundsInRoot()
        for (tag in listOf("agent-phone-done", "agent-phone-back", "agent-phone-home", "agent-phone-recents", "agent-phone-keyboard", "agent-phone-paste",
                "agent-phone-row-close", "agent-phone-row-delete", "agent-phone-row-enter", "agent-phone-prompt-cancel", "agent-phone-close", "agent-phone-retry",
                "agent-phone-open-here", "agent-phone-open-again", "agent-phone-turn-on", "agent-phone-steps")) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) continue
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$label $tag keeps a 48 dp target", bounds.bottom - bounds.top >= 47.9.dp && bounds.right - bounds.left >= 47.9.dp)
            assertTrue("$label $tag stays on screen", bounds.left >= root.left - 0.5.dp && bounds.right <= root.right + 0.5.dp)
        }
    }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "1") return
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("agent-phone-shots") ?: return
        dir.mkdirs()
        // A sheet is a window of its own, which captureToImage draws black: the system's own screenshot takes both windows (the test
        // activity is not secure; the real view's FLAG_SECURE is AgentPhoneViewSecureTest's).
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        val image = if (dialog) InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            else compose.onNodeWithTag("agent-phone-view").captureToImage().asAndroidBitmap()
        assertTrue("$name: screenshot taken", image != null)
        File(dir, "$name.png").outputStream().use { image!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(variant: String, language: String = "en", dark: Boolean = true, fontScale: Float = 1f, widthDp: Int = 411, heightDp: Int = 891) {
        show(language, dark, fontScale, widthDp, heightDp)
        for (case in cases) {
            compose.runOnUiThread { shown = case }
            compose.waitForIdle()
            // Effects wait in real time on a device (the Cancel offer comes 2 s after the prompt).
            case.waitFor?.let { tag -> compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }; compose.waitForIdle() }
            check("$variant/${case.name}")
            shot("$variant-${case.name}")
        }
    }

    @Test fun everyStateInDark() = tour("dark")
    @Test fun everyStateInLight() = tour("light", dark = false)
    @Test fun everyStateAtOneAndAHalfTimesText() = tour("large15", fontScale = 1.5f)
    @Test fun everyStateAtTwiceTheTextOnANarrowPhone() = tour("large2-narrow", dark = false, fontScale = 2f, widthDp = 360, heightDp = 760)
    @Test fun everyStateOnTheUnfoldedPhone() = tour("unfolded", widthDp = 840, heightDp = 900)
    @Test fun everyStateInArabicRightToLeft() = tour("ar", language = "ar")
    @Test fun everyStateInGermanAtLargeText() = tour("de-large", language = "de", dark = false, fontScale = 1.3f, widthDp = 360, heightDp = 780)
    @Test fun everyStateAtOnePointThreeTimesText() = tour("large13", dark = false, fontScale = 1.3f)
    @Test fun everyStateAtOnePointEightTimesText() = tour("large18", fontScale = 1.8f)
    @Test fun everyStateOnTheUnfoldedPhoneInLight() = tour("unfolded-light", dark = false, widthDp = 840, heightDp = 900)
    @Test fun everyStateOnTheUnfoldedPhoneInArabic() = tour("unfolded-ar", language = "ar", widthDp = 840, heightDp = 900)

    @Test fun theDebuggingPromptOffersCancelOnlyAfterTwoSeconds() {
        show("en", true, 1f, 411, 891)
        compose.runOnUiThread { shown = Case("prompt", live.copy(prompt = true, promptSince = 7)) }
        compose.waitForIdle()
        compose.onNodeWithTag("agent-phone-prompt").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, androidx.compose.ui.semantics.LiveRegionMode.Assertive))
        val shownAt = System.currentTimeMillis()
        assertTrue(compose.onAllNodesWithTag("agent-phone-prompt-cancel").fetchSemanticsNodes().isEmpty())
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("agent-phone-prompt-cancel").fetchSemanticsNodes().isNotEmpty() }
        assertTrue("Cancel comes only after Beacon's own No had its 2 s", System.currentTimeMillis() - shownAt >= PROMPT_CANCEL_AFTER_MILLIS - 300)
    }

    @Test fun everyBarButtonHasANameForTalkBackAndThePictureIsDescribed() {
        show("en", true, 1f, 411, 891)
        compose.waitForIdle()
        for ((tag, name) in listOf("agent-phone-back" to "Back", "agent-phone-home" to "Home screen", "agent-phone-recents" to "Recent apps",
                "agent-phone-keyboard" to "Keyboard", "agent-phone-paste" to "Paste")) {
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(name)))
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button))
        }
        compose.onNodeWithTag("agent-phone-frame").assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Beacon's phone screen")))
    }
}
