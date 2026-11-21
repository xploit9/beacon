package dev.hermes.android.agentphone

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.Alignment
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.*
import dev.hermes.android.ui.*
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Every screen and state of {name}'s phone (§8), drawn from the contract's answers with no plugin: the setup journey's pages and the
 * phone page, in dark, light, Arabic right to left, 1.5x and 2x text, and the unfolded phone's widths. On each: no text is cut (the
 * last line ends at the text's end, nothing ellipsized), every tappable thing is at least 48 dp, the page has a heading. Each state is
 * saved as a picture under the app's files (agent-phone-tour/), which the lane pulls and looks at.
 */
class AgentPhoneTourUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private sealed interface Shown
    private data class Setup(val state: AgentPhoneSetupState) : Shown
    private data class Page(val state: AgentPhoneUi, val actions: AgentPhonePageActions = AgentPhonePageActions()) : Shown
    private data class Hub(val snapshot: SettingsSnapshot) : Shown
    private class Case(val name: String, val shown: Shown, val removeDialog: Boolean = false)

    private fun status(body: String) = parseAgentPhoneStatus(JSONObject(body))
    private val notSetUp = """{"installed": false, "phone": "off", "install": {"state": "idle"}}"""
    private val downloading = """{"installed": false, "phone": "off", "install": {"state": "running", "phase": "download", "done_bytes": 1288490188, "total_bytes": 2576980377}}"""
    private val off = """{"installed": true, "profile": "default", "yours": true, "phone": "off", "install": {"state": "idle"},
        "warning": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.",
        "setup": {"google": true, "chrome": 133, "chrome_autofill": null, "logins": 0}, "storage_bytes": 10522669875, "helper": "ready"}"""
    private val on = """{"installed": true, "profile": "default", "yours": true, "phone": "on", "lease": {"holder": "agent", "you": false, "epoch": 3, "away": false},
        "install": {"state": "idle"}, "setup": {"google": true, "chrome": 136, "chrome_autofill": true, "logins": 3}, "storage_bytes": 10522669875,
        "helper": "update", "in_use": false}"""
    private val inUse = on.replace(""""in_use": false""", """"in_use": true""").replace(""""helper": "update"""", """"helper": "off"""")
    private val starting = """{"installed": true, "profile": "default", "yours": true, "phone": "starting", "install": {"state": "idle"}}"""
    private val failedStart = """{"installed": true, "profile": "default", "yours": true, "phone": "off", "install": {"state": "idle"},
        "error": "The phone's process could not start outside Hermes' job.", "helper": "missing"}"""
    private val other = """{"installed": true, "profile": "work", "yours": false, "phone": "on", "install": {"state": "idle"}}"""
    private val policy = AgentPhonePolicy(true, AgentPhoneAsk.PaymentsAndSends)
    // The phone's own clock (Root, 17 Nov: a fixed 2025 time showed a wrong year); rows read as today, as real ones do.
    private val now = System.currentTimeMillis()
    private val activity = listOf(
        AgentPhoneActivity(now - 120_000, "Kanto Foods", "tap", "Place order", true, "done"),
        AgentPhoneActivity(now - 600_000, "Kanto Foods", "fill", "", false, "done"),
        AgentPhoneActivity(now - 3_600_000, "Example Mail", "type", "", false, "done"),
    )
    private val checksFail = parseAgentPhoneChecks(JSONObject("""{"ok": false, "needs_continue": true, "rows": [
        {"id": "platform", "status": "ready", "needs_continue": false},
        {"id": "virtualisation", "status": "ready", "needs_continue": false},
        {"id": "memory", "status": "warn", "line": "This computer has less than 16 GB memory, so the phone may be slow.", "needs_continue": false},
        {"id": "disk", "status": "fail", "line": "Not enough free space. Free up 9 GB on the Hermes computer.", "needs_continue": false},
        {"id": "network", "status": "ready", "needs_continue": false},
        {"id": "sign_in", "status": "fail", "line": "Turn on Hermes sign-in first.",
            "fix": "Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes.",
            "how": "https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard#authentication-gated-mode", "needs_continue": false},
        {"id": "developer_tools", "status": "warn",
            "line": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.", "needs_continue": true}]}"""))
    /** Hermes in a container without /dev/kvm, as the host sends it (agent_phone_checks.check_container): the line and its one fix. */
    private val checksContainer = parseAgentPhoneChecks(JSONObject("""{"ok": false, "needs_continue": false, "rows": [
        {"id": "platform", "status": "ready", "needs_continue": false},
        {"id": "container", "status": "fail", "line": "This computer can't run a virtual phone.",
            "fix": "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.", "needs_continue": false}]}"""))
    private val checksWarn = parseAgentPhoneChecks(JSONObject("""{"ok": true, "needs_continue": true, "rows": [
        {"id": "platform", "status": "ready", "needs_continue": false}, {"id": "virtualisation", "status": "ready", "needs_continue": false},
        {"id": "memory", "status": "ready", "needs_continue": false}, {"id": "disk", "status": "ready", "needs_continue": false},
        {"id": "network", "status": "ready", "needs_continue": false}, {"id": "ports", "status": "ready", "needs_continue": false},
        {"id": "sign_in", "status": "ready", "needs_continue": false},
        {"id": "developer_tools", "status": "warn",
            "line": "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.", "needs_continue": true}]}"""))
    private val terms = AgentPhoneTerms(listOf(AgentPhoneLicence("android-sdk-license",
        "Terms and Conditions\n\nThis is the Android Software Development Kit License Agreement\n\n1. Introduction\n\n1.1 The Android Software Development Kit " +
            "(referred to in the License Agreement as the \"SDK\" and specifically including the Android system files, packaged APIs, and Google APIs add-ons) " +
            "is licensed to you subject to the terms of the License Agreement.")), 2_576_980_377L, 3_650_722_201L, 5_905_580_032L)

    private fun cases(): List<Case> {
        val setup = AgentPhoneSetupState()
        val running = AgentPhoneInstall(AgentPhoneInstallState.Running, AgentPhoneInstallPhase.Download, 1_288_490_188, 2_576_980_377)
        return listOf(
            Case("hub-row", Hub(SettingsSnapshot(agentPhone = "on", phoneAccess = listOf("Contacts")))),
            Case("page-loading", Page(AgentPhoneUi())),
            Case("page-needs-update", Page(AgentPhoneUi(loaded = true, loadLine = AgentPhoneLine(AGENT_PHONE_NEEDS_UPDATE, action = AgentPhoneAction.Update)))),
            Case("page-intro", Page(AgentPhoneUi(status = status(notSetUp), loaded = true))),
            Case("page-installing", Page(AgentPhoneUi(status = status(downloading), loaded = true))),
            Case("page-off-warning", Page(AgentPhoneUi(status = status(off), loaded = true, policy = policy, activity = emptyList(),
                setup = setup.copy(ticks = setOf("shops"))))),
            Case("page-on", Page(AgentPhoneUi(status = status(on), loaded = true, policy = policy, activity = activity))),
            // Several profiles: Saved logins names the vault instead of "In your vault".
            Case("page-on-profiles", Page(AgentPhoneUi(status = status(on), loaded = true, policy = policy, activity = activity),
                AgentPhonePageActions(vaultName = { "Work" }))),
            Case("page-in-use", Page(AgentPhoneUi(status = status(inUse), loaded = true, policy = policy.copy(ask = AgentPhoneAsk.EveryAction), activity = activity))),
            Case("page-needs-you", Page(AgentPhoneUi(status = status(on), loaded = true, policy = policy, activity = activity,
                needsYou = listOf(AgentPhoneNeedsYou("b".repeat(32), "The Kanto Foods login didn't work.", "Kanto Foods"))))),
            Case("page-starting", Page(AgentPhoneUi(status = status(starting), loaded = true, policy = policy, activity = activity))),
            Case("page-start-failed", Page(AgentPhoneUi(status = status(failedStart), loaded = true, policy = policy, activity = activity))),
            Case("page-other-profile", Page(AgentPhoneUi(status = status(other), loaded = true))),
            Case("page-remove-dialog", Page(AgentPhoneUi(status = status(on), loaded = true, policy = policy, activity = activity)), removeDialog = true),
            Case("setup-before", Setup(setup)),
            Case("setup-before-confirmed", Setup(setup.copy(confirmed = true))),
            Case("setup-checking", Setup(setup.copy(stage = AgentPhoneStage.Checks, checking = true))),
            Case("setup-checks-fail", Setup(setup.copy(stage = AgentPhoneStage.Checks, checks = checksFail))),
            Case("setup-checks-warn", Setup(setup.copy(stage = AgentPhoneStage.Checks, checks = checksWarn))),
            Case("setup-checks-container", Setup(setup.copy(stage = AgentPhoneStage.Checks, checks = checksContainer))),
            Case("setup-checks-error", Setup(setup.copy(stage = AgentPhoneStage.Checks, checksLine = AgentPhoneLine(AGENT_PHONE_OFFLINE, action = AgentPhoneAction.TryAgain)))),
            Case("setup-checks-continued", Setup(setup.copy(stage = AgentPhoneStage.Checks, checks = checksWarn, continued = true))),
            Case("setup-terms-loading", Setup(setup.copy(stage = AgentPhoneStage.Terms, termsLoading = true))),
            Case("setup-terms", Setup(setup.copy(stage = AgentPhoneStage.Terms, terms = terms))),
            Case("setup-terms-error", Setup(setup.copy(stage = AgentPhoneStage.Terms, termsLine = AgentPhoneLine("Download stopped. Check the Hermes computer's internet.", action = AgentPhoneAction.TryAgain)))),
            Case("setup-download", Setup(setup.copy(stage = AgentPhoneStage.Download, install = running, minutesLeft = 6))),
            Case("setup-download-check", Setup(setup.copy(stage = AgentPhoneStage.Download, install = running.copy(phase = AgentPhoneInstallPhase.Check, doneBytes = 2_576_980_377)))),
            Case("setup-paused", Setup(setup.copy(stage = AgentPhoneStage.Download, install = running.copy(state = AgentPhoneInstallState.Paused)))),
            Case("setup-download-failed", Setup(setup.copy(stage = AgentPhoneStage.Download,
                install = running.copy(state = AgentPhoneInstallState.Failed, line = "Download stopped. Check the Hermes computer's internet.", retry = true)))),
            Case("setup-download-no-space", Setup(setup.copy(stage = AgentPhoneStage.Download,
                install = running.copy(state = AgentPhoneInstallState.Failed, line = "Not enough free space. Free up 3 GB on the Hermes computer.")))),
            Case("setup-cancelled", Setup(setup.copy(stage = AgentPhoneStage.Download, install = AgentPhoneInstall(AgentPhoneInstallState.Cancelled)))),
            Case("setup-first-start", Setup(setup.copy(stage = AgentPhoneStage.FirstStart))),
            Case("setup-first-start-failed", Setup(setup.copy(stage = AgentPhoneStage.FirstStart,
                startLine = agentPhoneLine("The phone's process could not start outside Hermes' job.", AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain))))),
            Case("setup-ready", Setup(setup.copy(stage = AgentPhoneStage.Ready, status = status(off)))),
            Case("setup-checklist", Setup(setup.copy(stage = AgentPhoneStage.Checklist, status = status(off), chromeSeenBelow = true, ticks = setOf("shops")))),
            Case("setup-checklist-done", Setup(setup.copy(stage = AgentPhoneStage.Checklist, status = status(on), chromeSeenBelow = true))),
            Case("setup-done", Setup(setup.copy(stage = AgentPhoneStage.Done))),
        )
    }

    private var shown by mutableStateOf<Case?>(null)
    /** The look's own catalog, read in composition, so a check can say what the page must show in that language. */
    @Volatile private var words: (String) -> String = { it }
    /** The density the current look draws with (lowered for the unfolded widths), so 48 dp is measured in this look's pixels. */
    @Volatile private var lookDensity = 1f
    /** How many times a setup page asked to close (its X or its Not now: both are AgentPhoneSetupActions.close). */
    @Volatile private var closes = 0

    /** [widthDp]: the window is drawn this many dp wide (the unfolded Magic V5 is about 720-900 dp), by lowering the density. */
    private fun look(name: String, language: String, dark: Boolean, fontScale: Float, widthDp: Int? = null) {
        compose.setContent {
            val density = LocalDensity.current
            val widthPx = compose.activity.resources.displayMetrics.widthPixels
            val scale = if (widthDp == null) density.density else widthPx.toFloat() / widthDp
            lookDensity = scale
            CompositionLocalProvider(
                LocalDensity provides Density(scale, fontScale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
                LocalAssistantName provides "Maya",
            ) {
                NativeLocalization(language) { BeaconTheme(dark = dark) {
                    words = uiTextLookup()
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        when (val case = shown?.shown) {
                            is Setup -> Box(Modifier.fillMaxSize()) { key(shown?.name) { AgentPhoneSetupScreen(case.state, AgentPhoneSetupActions(close = { closes++ })) } }
                            is Page -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                                    Box(Modifier.testTag(APP_HEADER)) { Header(uiText("{name}'s phone").named(), back = {}) }
                                    key(shown?.name) { AgentPhonePage(case.state, "default", case.actions, Modifier.weight(1f)) }
                                }
                                if (shown?.removeDialog == true) AgentPhoneRemoveDialog(case.state.status?.storageBytes, removing = false, onRemove = {}, onDismiss = {})
                            }
                            is Hub -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) { SettingsOverview(localizedSettingsSummaries(case.snapshot), {}) }
                            }
                            null -> Unit
                        }
                    }
                } }
            }
        }
        val problems = mutableListOf<String>()
        for (case in cases()) {
            // The welcome's dust gathers over about two and a half seconds: shoot the phone it becomes. The clock goes manual before
            // the welcome is composed (a test cancels an infinite animation that starts while its clock auto-advances), then steps
            // frame by frame (the dust's clock, frameSeconds, reads one 4 s jump as a pause and would not move at all).
            if (case.name == "page-intro") {
                compose.mainClock.autoAdvance = false
                compose.runOnUiThread { shown = case }
                repeat(250) { compose.mainClock.advanceTimeByFrame() }
                compose.mainClock.autoAdvance = true
            } else compose.runOnUiThread { shown = case }
            compose.waitForIdle()
            check("$name ${case.name}", case, problems)
            firstView("$name ${case.name}", case, problems)
            shot("$name-${case.name}", dialog = case.removeDialog)
            // The developer-tools line is short; its whole reason (a protected line) is one tap away under Details: opened, read, shown.
            val details = mapOf("page-off-warning" to "agent-phone-problem-details", "setup-checks-warn" to "agent-phone-details:developer_tools")[case.name]
            if (details != null) {
                compose.onNodeWithTag(details, useUnmergedTree = true).performScrollTo().performClick()
                compose.waitForIdle()
                val reason = words("Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.").replace("{name}", "Maya")
                if (compose.onAllNodesWithText(reason).fetchSemanticsNodes().isEmpty()) problems += "$name ${case.name}: Details doesn't say \"$reason\""
                check("$name ${case.name} (details)", case, problems)
                shot("$name-${case.name}-details")
            }
            // The phone page is a list: its lower sections are checked and shown after a scroll to the end.
            val page = (case.shown as? Page)?.state?.status
            if (page != null && page.installed && page.yours && !case.removeDialog) {
                // The owner's two honest lines (design Q3, Q11) under Ask before, in this look's language and size: asserted, then shown.
                compose.onNodeWithTag("agent-phone-page").performScrollToNode(hasTestTag("agent-phone-use"))
                compose.waitForIdle()
                for (line in listOf(words("Payment questions cover {name}'s phone tool.").replace("{name}", "Maya"), words("Scheduled orders can't use cards from your vault.")))
                    if (compose.onAllNodesWithText(line, substring = true).fetchSemanticsNodes().isEmpty()) problems += "$name ${case.name}: no \"$line\" under Ask before"
                check("$name ${case.name} (use)", case, problems)
                shot("$name-${case.name}-use")
                compose.onNodeWithTag("agent-phone-page").performScrollToNode(hasTestTag("agent-phone-remove-section"))
                compose.waitForIdle()
                check("$name ${case.name} (end)", case, problems)
                shot("$name-${case.name}-end")
            }
            // P2-4: the Needs-you line is the owner's language too, not the plugin's English.
            if (case.name == "page-needs-you" && language != "en" && compose.onAllNodesWithText("The Kanto Foods login didn't work.").fetchSemanticsNodes().isNotEmpty())
                problems += "$name ${case.name}: the Needs-you line is still English"
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object { const val APP_HEADER = "tour-app-header" }

    private fun nodes(matcher: SemanticsMatcher): List<SemanticsNode> = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun check(where: String, case: Case, problems: MutableList<String>) {
        val root = compose.onAllNodes(SemanticsMatcher("root") { it.parent == null }).fetchSemanticsNodes().first().boundsInRoot
        val minTarget = 48f * lookDensity - 1f
        // Text: the last line ends at the text's end and nothing is ellipsized. Google's licence is the one long text that scrolls.
        for (node in nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult))) {
            if (!node.boundsInRoot.overlaps(root) || node.boundsInRoot.height <= 0f) continue
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.firstOrNull() ?: continue
            val text = layout.layoutInput.text.text
            val last = layout.lineCount - 1
            if (last < 0 || text.isBlank()) continue
            if (layout.isLineEllipsized(last) || layout.getLineEnd(last, visibleEnd = true) < text.trimEnd().length)
                problems += "$where: text cut: \"${text.take(80)}\""
            // Only this feature's own screens: the hub case also draws other pages' rows.
            if (case.shown !is Hub && !cjk.containsMatchIn(text)) for (line in 0 until last) {
                val end = layout.getLineEnd(line)
                if (end in 1 until text.length && text[end - 1].isLetter() && text[end].isLetter()) {
                    problems += "$where: word broken: \"${text.take(80)}\" at \"${text.substring((end - 6).coerceAtLeast(0), (end + 6).coerceAtMost(text.length))}\""
                    break
                }
            }
        }
        // Targets: everything tappable and fully on screen is at least 48 dp both ways.
        val header = nodes(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, APP_HEADER)).map { it.boundsInRoot }
        for (node in nodes(SemanticsMatcher.keyIsDefined(SemanticsActions.OnClick))) {
            val bounds = node.boundsInRoot
            if (header.any { it.contains(bounds.center) }) continue
            val size = node.size
            if (bounds.width <= 0f || bounds.height + 1f < size.height || bounds.width + 1f < size.width) continue
            if (size.height < minTarget || size.width < minTarget)
                problems += "$where: small target ${node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: node.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString() ?: "?"} ${size.width / lookDensity}x${size.height / lookDensity} dp"
        }
        // A heading names every page (TalkBack's heading navigation).
        if (case.shown !is Hub && nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).isEmpty() && case.name != "page-loading")
            problems += "$where: no heading"
        if (case.name == "setup-download") {
            val bar = compose.onNodeWithTag("agent-phone-progress", useUnmergedTree = true).fetchSemanticsNode()
            if (bar.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) == null) problems += "$where: the bar does not read its progress"
        }
    }

    /**
     * REVIEW-FIX-UI-121, on each case as it first shows: the hero's pill is its own heading and the phone's merged node is neither a
     * heading nor a live region (the line under it is), with room between the pill and the app tile; Ready's list can't be ticked
     * before the phone was opened; Before you start's protected loopback line is whole and clear of the scroll fade.
     */
    private fun firstView(where: String, case: Case, problems: MutableList<String>) {
        val d = lookDensity
        if (nodes(hasTestTag("agent-phone-hero-phone")).isNotEmpty()) {
            val phone = compose.onAllNodesWithTag("agent-phone-hero-phone").fetchSemanticsNodes().single().config
            if (SemanticsProperties.Heading in phone || SemanticsProperties.LiveRegion in phone) problems += "$where: the whole phone is one heading or live region"
            if (compose.onAllNodes(hasTestTag("agent-phone-hero-title") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).fetchSemanticsNodes().isEmpty())
                problems += "$where: the state pill is not its own heading"
            if (nodes(hasTestTag("agent-phone-hero-line")).any { SemanticsProperties.LiveRegion !in it.config }) problems += "$where: the line under the phone is not a live region"
            val pill = nodes(hasTestTag("agent-phone-hero-title")).single().boundsInRoot
            // The app's name sits 8 dp under its 52 dp tile.
            nodes(hasTestTag("agent-phone-hero-app")).singleOrNull()?.boundsInRoot?.let { app ->
                val gap = app.top - (8f + 52f) * d - pill.bottom
                if (gap < 12f * d - 1f) problems += "$where: the state pill is ${gap / d} dp from the app tile"
            }
        }
        if (case.name == "setup-ready" && nodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState) and hasAnyAncestor(hasTestTag("agent-phone-checklist"))).isNotEmpty())
            problems += "$where: the list can be ticked before the phone was opened"
        if (case.name.startsWith("setup-before")) {
            val line = nodes(hasText(words("Apps on the phone can reach programs on your Hermes computer that don't ask for a sign-in."))).firstOrNull()
            var scroller = line?.parent
            while (scroller != null && SemanticsProperties.VerticalScrollAxisRange !in scroller.config) scroller = scroller.parent
            if (line == null || scroller == null) problems += "$where: no loopback line in a scrolling page"
            else {
                val range = scroller.config[SemanticsProperties.VerticalScrollAxisRange]
                // The scroll area's last 28 dp fade while there is more below it.
                val fade = if (range.value() < range.maxValue()) 28f * d else 0f
                // The prerequisites and the loopback line, whole and clear with no scroll. Unclipped: boundsInRoot stops at the scroll
                // area's edge, so a line cut by the fold would look as if it ended there.
                for ((what, node) in listOf("the prerequisites" to nodes(hasTestTag("agent-phone-needs")).firstOrNull(), "the loopback line" to line)) {
                    val end = node?.let { it.positionInRoot.y + it.size.height } ?: Float.MAX_VALUE
                    if (end > scroller.boundsInRoot.bottom - fade + 1f)
                        problems += "$where: $what end ${(end - scroller.boundsInRoot.bottom + fade) / d} dp into the fade or below the fold"
                }
            }
            // Root's (A): no Not now here; the header's X is the way out. It is named, 48 dp, enabled, and closes as Not now did.
            if (nodes(hasText(words("Not now"))).isNotEmpty()) problems += "$where: Before you start still shows Not now"
            val close = compose.onAllNodesWithTag("setup-close").fetchSemanticsNodes().singleOrNull()
            if (close == null) problems += "$where: no close button"
            else {
                if (close.config.getOrNull(SemanticsProperties.ContentDescription)?.singleOrNull() != words("Close setup")) problems += "$where: the X isn't named \"${words("Close setup")}\""
                if (SemanticsProperties.Disabled in close.config) problems += "$where: the X is disabled"
                if (close.size.width < 48f * d - 1f || close.size.height < 48f * d - 1f) problems += "$where: the X is ${close.size.width / d}x${close.size.height / d} dp"
                val before = closes
                compose.onNodeWithTag("setup-close").performClick()
                compose.waitForIdle()
                if (closes != before + 1) problems += "$where: the X didn't close setup"
            }
        }
        // REVIEW-FIX-UI-121 P2: the container row says its line once and shows its /dev/kvm fix, not the line again.
        if (case.name == "setup-checks-container") {
            val line = words("This computer can't run a virtual phone.")
            if (nodes(hasText(line)).size != 1) problems += "$where: \"$line\" shows ${nodes(hasText(line)).size} times"
            val fix = words("Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.")
            val shownFix = nodes(hasText("/dev/kvm", substring = true)).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
            if (shownFix.none { it.text.replace("\u2068", "").replace("\u2069", "") == fix })
                problems += "$where: the container row hides its /dev/kvm fix"
        }
        // The other setup pages keep their Not now.
        if (case.name in setOf("setup-ready", "setup-terms", "setup-terms-loading", "setup-terms-error") && nodes(hasText(words("Not now"))).isEmpty())
            problems += "$where: Not now is gone"
    }

    private val cjk = Regex("""[\u3040-\u30ff\u3400-\u9fff\uac00-\ud7af]""")

    private fun Rect.overlaps(other: Rect) = left < other.right && right > other.left && top < other.bottom && bottom > other.top

    private fun shot(name: String, dialog: Boolean = false) {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("agent-phone-tour") ?: return
        dir.mkdirs()
        compose.waitForIdle()
        val image = if (dialog) compose.onNode(isDialog()).captureToImage() else compose.onRoot().captureToImage()
        File(dir, "$name.png").outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    /**
     * REVIEW-FIX-UI-121-R2 N2: a state change on the hero is spoken. The page stays composed while its state moves Off -> Starting ->
     * On -> Needs you -> Off, and after each move a live region holds exactly the new state word (the line under the phone is empty in
     * some of these states, so it can't be the one that speaks).
     */
    @Test fun aStateChangeOnTheHeroIsSpokenAsItsWord() {
        val steps = listOf("page-off-warning" to "Off", "page-starting" to "Starting...", "page-on" to "On", "page-needs-you" to "Needs you")
            .map { (name, word) -> (cases().first { it.name == name }.shown as Page) to word }
        var page by mutableStateOf(steps.first().first)
        compose.setContent {
            CompositionLocalProvider(LocalAssistantName provides "Maya") {
                NativeLocalization("en") { BeaconTheme(dark = true) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { AgentPhonePage(page.state, "default", page.actions, Modifier.fillMaxSize()) }
                } }
            }
        }
        compose.waitForIdle()
        val problems = mutableListOf<String>()
        for ((next, word) in steps.drop(1) + steps.take(1)) {
            compose.runOnUiThread { page = next }
            compose.waitForIdle()
            val spoken = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion)).fetchSemanticsNodes()
                .map { node -> node.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty().replace("…", "...") }
            if (word !in spoken) problems += "after the move to $word, the live regions say $spoken"
        }
        assertTrue(problems.joinToString("; "), problems.isEmpty())
    }

    @Test fun everyStateInDark() = look("dark", "en", dark = true, fontScale = 1f)
    @Test fun everyStateInLight() = look("light", "en", dark = false, fontScale = 1f)
    @Test fun everyStateInArabicRightToLeft() = look("ar-dark", "ar", dark = true, fontScale = 1f)
    @Test fun everyStateAtOneAndAHalfTimesText() = look("x15-light", "en", dark = false, fontScale = 1.5f)
    @Test fun everyStateAtTwiceTheText() = look("x2-dark", "en", dark = true, fontScale = 2f)
    @Test fun everyStateInGermanAtLargeText() = look("de-x15-dark", "de", dark = true, fontScale = 1.5f)
    @Test fun everyStateOnTheUnfoldedPhone() = look("unfolded-900-light", "en", dark = false, fontScale = 1f, widthDp = 900)
    @Test fun everyStateOnTheUnfoldedPhoneAt720() = look("unfolded-720-dark", "en", dark = true, fontScale = 1.3f, widthDp = 720)
    @Test fun everyStateAtOnePointThreeTimesText() = look("x13-light", "en", dark = false, fontScale = 1.3f)
    @Test fun everyStateAtOnePointEightTimesText() = look("x18-dark", "en", dark = true, fontScale = 1.8f)
    @Test fun everyStateInArabicInLightAtLargeText() = look("ar-x13-light", "ar", dark = false, fontScale = 1.3f)
    @Test fun everyStateInArabicAtOnePointEightTimesText() = look("ar-x18-dark", "ar", dark = true, fontScale = 1.8f)
}
