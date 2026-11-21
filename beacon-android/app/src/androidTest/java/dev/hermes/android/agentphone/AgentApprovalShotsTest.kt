package dev.hermes.android.agentphone

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.AgentPhoneActivity
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.chatBeaconBackground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Every kind and state of the agent phone's question card, over Beacon, and of "What {name} did on its phone": dark and light, 1.5x and
 * 2x text, a narrow phone and the unfolded one, Arabic right to left and German at large text. Each state checks that no text is cut,
 * every button keeps 48 dp and stays on screen, and the card reads well with TalkBack (a heading, named buttons); with `-e shots 1`
 * each is saved as a PNG for the report. The real dialog is FLAG_SECURE (its own screenshot is black, AgentApprovalDeviceTest), so
 * these are drawn in a plain test window. Synthetic data only: example.com, made-up shops, no real card.
 */
@RunWith(AndroidJUnit4::class)
class AgentApprovalShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    private val far = Long.MAX_VALUE / 2
    private fun action(n: Int, risk: ApprovalRisk, app: String = "Kanto Foods", label: String = "Place order", amount: String = "", purpose: String = "Dinner you asked for", action: String = "tap", pin: String = "", pay: Boolean = false) =
        AgentApproval(id(n), ApprovalKind.Action, far, risk, action, app, label, amount, purpose, pin = pin, pay = pay)

    // Place order pays: the plugin says so (pay true).
    private val payment = action(1, ApprovalRisk.Payment, amount = "£12.40", pay = true)
    private val code = AgentApproval(id(12), ApprovalKind.Code, far, app = "Amazon", label = "Amazon", line = "Enter the code for Amazon.")

    private class Case(val name: String, val ui: DialogUi, val type: String? = null)

    private fun card(record: AgentApproval, phase: CardPhase = CardPhase.Asking, count: Int = 1, left: Float? = .82f, seconds: Long? = 98) =
        DialogUi(card = ApprovalCardUi(record, phase, 1, count, left, seconds, answerable = true), loading = false)

    private val cases = listOf(
        Case("01-pay", card(payment, count = 3)),
        Case("02-payment-screen-tap", card(action(2, ApprovalRisk.Unlabeled, label = "", amount = "€8.90"))),
        Case("03-send", card(action(3, ApprovalRisk.Send, app = "Messages", label = "Send", purpose = "Tell the shop the door code is at reception"))),
        Case("04-delete", card(action(4, ApprovalRisk.Delete, app = "Photos", label = "Delete 3 items", purpose = "Clear the old receipts"))),
        Case("05-install", card(action(5, ApprovalRisk.Install, app = "Play Store", label = "Install", purpose = "Get the Kanto Foods app"))),
        Case("06-account", card(action(6, ApprovalRisk.Account, app = "Kanto Foods", label = "Change email", purpose = "Use the order address"))),
        Case("07-every-action", card(action(7, ApprovalRisk.Other, app = "Maps", label = "Directions", purpose = "Find the nearest branch"))),
        Case("08-first-pin", card(action(8, ApprovalRisk.Account, action = "fill", label = "", purpose = "Sign in to order", pin = "Use the Kanto Foods login in Kanto Foods?"))),
        Case("09-new-login", card(AgentApproval(id(9), ApprovalKind.NewLogin, far, app = "Kanto Foods", label = "Kanto Foods", identifier = "agent${'@'}example.com"))),
        Case("10-save-login", card(AgentApproval(id(10), ApprovalKind.Save, far, label = "Amazon", identifier = "k${'@'}example.com", line = "Save the new Amazon login?"), left = .6f, seconds = 360)),
        Case("11-update-login", card(AgentApproval(id(11), ApprovalKind.Update, far, label = "Amazon", identifier = "k${'@'}example.com", line = "Replace the saved Amazon login?"), left = .6f, seconds = 360)),
        Case("12-code", card(code)),
        Case("13-code-typed", card(code), type = "482913"),
        Case("14-needs-you", card(AgentApproval(id(14), ApprovalKind.NeedsYou, far, app = "Amazon", line = "The Amazon login didn't work."), left = null, seconds = null)),
        Case("15-sending", card(payment, CardPhase.Sending(true))),
        Case("16-allowed", card(payment, CardPhase.Answered("Allowed", true))),
        Case("17-denied-payment", card(payment, CardPhase.Answered("Nothing was paid.", false))),
        Case("18-offline", card(payment, CardPhase.Problem(decisionLine(DecisionResult.Retry, code = false)!!, retry = true))),
        Case("19-ended", card(payment, CardPhase.Problem(decisionLine(DecisionResult.Ended, code = false)!!, retry = false), left = 0f, seconds = 0)),
        Case("20-answered-elsewhere", card(payment, CardPhase.Problem("Answered on another device.", retry = false))),
        Case("21-code-refused", card(code, CardPhase.Problem("Check the code and try again.", retry = true))),
        Case("22-last-seconds", card(payment, left = .08f, seconds = 9)),
        Case("23-signed-out", DialogUi(line = "Open Beacon and sign in again to answer.", loading = false)),
        Case("24-already-over", DialogUi(line = "Already answered or ended.", loading = false)),
        Case("25-loading", DialogUi()),
        // PHONEFIX P2-2: a Sign in tap on a checkout screen asks as a payment and names the button; it doesn't pay, so its yes is Allow
        // (REVIEW-FIX-UI-121), with the amount still large.
        Case("26-checkout-sign-in", card(action(26, ApprovalRisk.Payment, label = "Sign in", amount = "£12.40"))),
        Case("27-reset-password", card(AgentApproval(id(27), ApprovalKind.NewLogin, far, label = "Amazon", line = "Change the Amazon password?"))),
    )

    private var shown by mutableStateOf(cases.first())
    private var showList by mutableStateOf<LogState?>(null)
    private var highlight by mutableStateOf<Long?>(null)

    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.of(2026, 11, 17)
    private fun at(day: Int, hour: Int, minute: Int) = ZonedDateTime.of(2026, 11, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private val rows = listOf(
        AgentPhoneActivity(at(17, 19, 42), "Kanto Foods", "tap", "Place order · £12.40", true, "done", 12),
        AgentPhoneActivity(at(17, 19, 41), "Kanto Foods", "fill", "payment", false, "filled", 11),
        AgentPhoneActivity(at(17, 19, 40), "Kanto Foods", "fill", "login", false, "filled", 10),
        AgentPhoneActivity(at(17, 19, 38), "Kanto Foods", "type", "", false, "done", 9),
        AgentPhoneActivity(at(17, 18, 5), "Messages", "tap", "Send", false, "declined", 8),
        AgentPhoneActivity(at(17, 9, 15), "Kanto Foods", "save", "Saved: Kanto Foods · agent${'@'}example.com", true, "saved", 7),
        AgentPhoneActivity(at(16, 21, 3), "Android", "answer", "Allow USB debugging?", false, "said_no", 6),
        AgentPhoneActivity(at(16, 20, 50), "Android", "save", "Save the login to Beacon", false, "asked_owner", 5),
        AgentPhoneActivity(at(16, 20, 49), "Settings", "tap", "Factory reset", false, "blocked", 4),
        AgentPhoneActivity(at(12, 13, 0), "Maps", "swipe", "", false, "done", 3),
        AgentPhoneActivity(at(12, 12, 58), "Maps", "long_press", "Kanto Foods, High St", false, "done", 2),
        AgentPhoneActivity(at(12, 12, 57), "Maps", "key", "", false, "done", 1),
    )
    private val listCases = listOf(
        "31-list" to LogState(rows, loading = false),
        "32-list-empty" to LogState(emptyList(), loading = false),
        "33-list-offline" to LogState(null, failed = true, loading = false),
        "34-list-loading" to LogState(null),
    )

    /**
     * Draws at exactly [widthDp] x [heightDp]: larger than the test emulator's window (the unfolded phone), the density is lowered until it
     * fits, so every layout decision is made at the real dp size (ported from L5's capture test).
     */
    private fun show(language: String, dark: Boolean, fontScale: Float, widthDp: Int, heightDp: Int) {
        // The real dialog resizes for the keyboard (adjustResize, edge to edge); the test window does the same, so it isn't panned.
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
        }
        compose.setContent {
            val density = LocalDensity.current
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val fit = minOf(density.density, constraints.maxWidth / widthDp.toFloat(), constraints.maxHeight / heightDp.toFloat())
                CompositionLocalProvider(LocalDensity provides Density(fit, fontScale),
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr, LocalAssistantName provides "Maya") {
                    Box(Modifier.size(widthDp.dp, heightDp.dp).testTag("agent-approval-shot")) {
                        NativeLocalization(language) { BeaconTheme(dark = dark) {
                            val list = showList
                            if (list != null) Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background).chatBeaconBackground()) {
                                AgentPhoneLogScreen(list, highlight, onBack = {}, onRetry = {}, zone = zone, today = today)
                            } else Box(Modifier.fillMaxSize().chatBeaconBackground()) {
                                val case = shown
                                key(case.name) { ApprovalDialogScreen(case.ui, onAnswer = { _, _ -> }, onClose = {}) }
                            }
                        } }
                    }
                }
            }
        }
    }

    private fun texts(): List<Pair<String, TextLayoutResult>> = compose.onAllNodes(SemanticsMatcher("has text") { it.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null }, useUnmergedTree = true)
        .fetchSemanticsNodes().mapNotNull { node ->
            val results = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
            results.firstOrNull()?.let { (node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: it.layoutInput.text.text) to it }
        }

    private val buttons = listOf("agent-approval-yes", "agent-approval-no", "agent-approval-close", "agent-phone-log-back", "agent-phone-log-retry",
        "agent-phone-log-reset", "agent-phone-log-more", "agent-phone-log-filter:all", "agent-phone-log-filter:approved")

    private fun check(label: String) {
        for ((text, layout) in texts()) {
            if (text.isBlank()) continue
            val last = layout.lineCount - 1
            assertEquals("$label: \"$text\" shows every character", layout.layoutInput.text.length, layout.getLineEnd(last, visibleEnd = true))
            assertFalse("$label: \"$text\" ends in an ellipsis", layout.isLineEllipsized(last))
        }
        val root = compose.onNodeWithTag("agent-approval-shot").getUnclippedBoundsInRoot()
        for (tag in buttons) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) continue
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$label $tag keeps a 48 dp target", bounds.bottom - bounds.top >= 47.9.dp && bounds.right - bounds.left >= 47.9.dp)
            assertTrue("$label $tag stays on screen", bounds.left >= root.left - 0.5.dp && bounds.right <= root.right + 0.5.dp && bounds.bottom <= root.bottom + 0.5.dp)
            compose.onNodeWithTag(tag).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button).or(SemanticsMatcher.keyIsDefined(SemanticsProperties.Role)))
        }
        // TalkBack: the question is a heading, and the time left is described once (it never ticks aloud).
        if (compose.onAllNodesWithTag("agent-approval-title").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("agent-approval-title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        if (compose.onAllNodesWithTag("agent-approval-time").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("agent-approval-time").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription))
        if (compose.onAllNodesWithTag("agent-phone-log-title").fetchSemanticsNodes().isNotEmpty())
            compose.onNodeWithTag("agent-phone-log-title").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "1") return
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("agent-approval-shots") ?: return
        dir.mkdirs()
        val image = compose.onNodeWithTag("agent-approval-shot").captureToImage()
        File(dir, "$name.png").outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun tour(variant: String, language: String = "en", dark: Boolean = true, fontScale: Float = 1f, widthDp: Int = 411, heightDp: Int = 891) {
        show(language, dark, fontScale, widthDp, heightDp)
        for (case in cases) {
            compose.runOnUiThread { showList = null; shown = case }
            compose.waitForIdle()
            case.type?.let {
                compose.onNodeWithTag("agent-approval-code").performTextInput(it)
                // The keyboard is not part of a capture; with it closed the card shows as typed (the real dialog with the keyboard up is
                // checked on screen in AgentApprovalDeviceTest).
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
            }
            shot("$variant-${case.name}")
            check("$variant/${case.name}")
        }
        for ((name, state) in listCases) {
            compose.runOnUiThread { highlight = null; showList = state }
            compose.waitForIdle()
            shot("$variant-$name")
            check("$variant/$name")
        }
        captureNoticeRow(variant)
    }

    private fun captureNoticeRow(variant: String) {
        compose.runOnUiThread { highlight = 8; showList = LogState(rows, loading = false) }
        compose.waitForIdle()
        compose.assertActivityRowFullyVisible(8)
        shot("$variant-35-list-at-row")
        check("$variant/35-list-at-row")
    }

    @Test fun activityNoticeAtStandardText() {
        show("en", dark = false, fontScale = 1f, widthDp = 411, heightDp = 891)
        compose.runOnUiThread { showList = LogState(rows, loading = false) }
        compose.onNodeWithTag("agent-phone-log-summary").assertIsDisplayed()
        captureNoticeRow("light")
    }

    @Test fun activityNoticeAtTwiceTextOnANarrowPhone() {
        show("en", dark = false, fontScale = 2f, widthDp = 360, heightDp = 760)
        captureNoticeRow("large2-narrow")
    }

    @Test fun lateActivityNoticeRevealsTheRowOnce() {
        show("en", dark = false, fontScale = 2f, widthDp = 360, heightDp = 760)
        val cached = (40L downTo 13L).map { rows.first().copy(seq = it, label = "Step $it", approved = false) }
        val target = compose.onNodeWithTag("agent-phone-log-row:8")
        compose.runOnUiThread { highlight = 8; showList = LogState(cached) }
        compose.onNodeWithTag("agent-phone-log-summary").assertIsDisplayed()
        target.assertDoesNotExist()

        compose.runOnUiThread { showList = LogState(cached + rows, loading = false) }
        compose.assertActivityRowFullyVisible(8)

        // A refresh with a new leading day must respect a scroll back to the summary.
        compose.onNode(activityViewport).performScrollToIndex(0)
        val tomorrow = rows.first().copy(seq = 41, atMillis = at(18, 9, 0))
        compose.runOnUiThread { showList = LogState(listOf(tomorrow) + cached + rows, loading = false) }
        compose.onNodeWithTag("agent-phone-log-summary").assertIsDisplayed()
        target.assertIsNotDisplayed()
    }

    @Test fun everyStateInDark() = tour("dark")
    @Test fun everyStateInLight() = tour("light", dark = false)
    @Test fun everyStateAtOneAndAHalfTimesText() = tour("large15", fontScale = 1.5f)
    @Test fun everyStateAtTwiceTheTextOnANarrowPhone() = tour("large2-narrow", dark = false, fontScale = 2f, widthDp = 360, heightDp = 760)
    @Test fun everyStateOnTheUnfoldedPhone() = tour("unfolded", widthDp = 840, heightDp = 900)
    @Test fun everyStateOnTheUnfoldedPhoneInLight() = tour("unfolded-light", dark = false, widthDp = 840, heightDp = 900)
    @Test fun everyStateInArabicRightToLeft() = tour("ar", language = "ar")
    @Test fun everyStateInGermanAtLargeText() = tour("de-large", language = "de", dark = false, fontScale = 1.3f, widthDp = 360, heightDp = 780)
    @Test fun everyStateAtOnePointThreeTimesText() = tour("large13", dark = false, fontScale = 1.3f)
    @Test fun everyStateAtOnePointEightTimesText() = tour("large18", fontScale = 1.8f)
    @Test fun everyStateInArabicInLightAtLargeText() = tour("ar-light-large13", language = "ar", dark = false, fontScale = 1.3f)

    @Test fun theCardsButtonsAreNamedForTalkBack() {
        show("en", true, 1f, 411, 891)
        compose.runOnUiThread { shown = cases.first() }
        compose.waitForIdle()
        compose.onNodeWithTag("agent-approval-yes").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        compose.onNodeWithTag("agent-approval-no").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        // P2-3: a payment's yes says what it does, with the amount the screen showed.
        fun said(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }.filter { it !in "\u2066\u2067\u2068\u2069" }
        assertEquals("Pay £12.40", said("agent-approval-yes"))
        assertEquals("Deny", said("agent-approval-no"))
        // A Sign in on the checkout doesn't pay: Allow, a title that says where it taps, and the amount kept.
        compose.runOnUiThread { shown = cases.first { it.name == "26-checkout-sign-in" } }
        compose.waitForIdle()
        assertEquals("Allow", said("agent-approval-yes"))
        assertEquals("Maya wants to tap on a payment screen", said("agent-approval-title"))
        assertEquals("£12.40", said("agent-approval-amount"))
        compose.runOnUiThread { shown = cases.first() }
        compose.waitForIdle()
        // Whose phone it is, and how many questions wait, in one line under the heading.
        assertEquals("Maya's phone · 1 of 3", compose.onAllNodesWithTag("agent-approval-count").fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString { it.text })
    }
}

private val activityViewport = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

internal fun SemanticsNodeInteractionsProvider.assertActivityRowFullyVisible(seq: Long) {
    val viewport = onNode(activityViewport).getUnclippedBoundsInRoot()
    val row = onNodeWithTag("agent-phone-log-row:$seq")
    val bounds = row.getUnclippedBoundsInRoot()
    val rounding = 0.5.dp
    assertTrue("Activity row $seq has bounds $bounds inside the scroll viewport $viewport",
        bounds.right > bounds.left && bounds.bottom > bounds.top &&
            bounds.left >= viewport.left - rounding && bounds.right <= viewport.right + rounding &&
            bounds.top >= viewport.top - rounding && bounds.bottom <= viewport.bottom + rounding)
    row.assertIsDisplayed()
}
