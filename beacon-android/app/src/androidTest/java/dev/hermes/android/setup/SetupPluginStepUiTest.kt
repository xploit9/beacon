package dev.hermes.android.setup

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconTheme
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.BEACON_PC_INSTALL_URL
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupPluginPhase
import dev.hermes.android.ui.SetupPluginStepPage
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.updates.GatewayStatus
import dev.hermes.android.updates.PluginInstallController
import dev.hermes.android.updates.PluginInstallEndpoint
import dev.hermes.android.updates.PluginInstallFailure
import dev.hermes.android.updates.PluginInstallStage
import dev.hermes.android.updates.PluginInstallUi
import java.io.File
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The setup wizard's "Add the Beacon plugin to Hermes" page, driven with a fake Hermes that does not have the plugin: the page comes after
 * sign-in, installs through the app's own install flow, shows what it is doing, and hands over to the call pages. Arabic runs in a real
 * right-to-left layout, and a tour of every page in dark, light, Arabic dark and large light checks that each fits and keeps 48 dp targets.
 */
class SetupPluginStepUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun stop() { scope.cancel() }

    /** A Hermes without Beacon: it answers once the install has run; [hold] keeps the install running until the test lets it end. */
    private class AbsentHermes(
        @Volatile var installFailure: Exception? = null,
        private val restartRequired: Boolean = false,
        private val hold: CompletableDeferred<Unit>? = null,
    ) : PluginInstallEndpoint {
        @Volatile var installed = false
        @Volatile var installs = 0
        @Volatile var restarts = 0
        override suspend fun install(): JSONObject {
            installs++
            hold?.await()
            installFailure?.let { throw it }
            installed = true
            return JSONObject().put("ok", true).put("restart_required", restartRequired)
        }
        override suspend fun enable(): JSONObject = JSONObject().put("ok", true)
        override suspend fun present(): Boolean = installed
        override suspend fun gateway(): GatewayStatus = GatewayStatus(running = true, busy = false)
        override suspend fun restartGateway() { restarts++ }
    }

    private fun bootstrap(plugin: String) = JSONObject().put("optional_features", JSONObject().put("plugin", plugin))
    private fun signedIn() = BeaconClient(AuthContext("https://hermes.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false)
    private fun afterSignIn(plugin: String, step: String = "voice-provider") =
        AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = step, restoringSession = false, bootstrap = bootstrap(plugin))

    private var state by mutableStateOf(afterSignIn("absent"))
    private val opened = CopyOnWriteArrayList<String>()
    private var cancelled = 0
    private var signIns = 0

    /** Mirrors the app: when an install finishes, the app reads the plugin again and every screen sees Beacon there. */
    private fun controller(hermes: PluginInstallEndpoint) = PluginInstallController(
        scope, ::signedIn,
        { compose.runOnUiThread { state = state.copy(bootstrap = bootstrap("available"), setupPluginMessage = "") } },
        {}, { hermes },
    )

    private fun direction(language: String) = if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr

    private fun show(install: PluginInstallController?, language: String = "en", dark: Boolean = true, fontScale: Float = 1f, widthDp: Int? = null) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale),
                // The Arabic pages run in a real right-to-left layout, as the app does when the phone's language is Arabic.
                LocalLayoutDirection provides direction(language),
                LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } },
            ) {
                Box(if (widthDp == null) Modifier else Modifier.width(widthDp.dp)) {
                    NativeLocalization(language) { BeaconTheme(dark = dark) {
                        SetupWizardScreen(
                            state = state, onProbe = {}, onSignIn = { signIns++ }, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = { cancelled++ },
                            pluginInstall = install,
                        )
                    } }
                }
            }
        }
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    private fun gone(tag: String) = assertTrue("$tag is not shown", compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty())
    private fun row(name: String, word: String) = compose.onNodeWithTag("setup-plugin-row-$name").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, word))

    private fun shot(name: String) {
        if (InstrumentationRegistry.getArguments().getString("shots") != "1") return
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir("setup-plugin-shots") ?: return
        dir.mkdirs()
        compose.waitForIdle()
        File(dir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun anAbsentPluginIsInstalledFromTheWizardAndTheOwnerContinuesToTheCallPages() {
        val hold = CompletableDeferred<Unit>()
        val hermes = AbsentHermes(hold = hold)
        show(controller(hermes))
        compose.onNodeWithText("Add the Beacon plugin to Hermes").assertIsDisplayed()
        compose.onNodeWithText("Step 2 of 4").assertIsDisplayed()
        compose.onNodeWithText("Hermes downloads the Beacon plugin from GitHub and turns it on.").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-install").assertTextEquals("Install Beacon")
        // At most two quiet actions under the primary: Skip for now and Set up from a computer. Close setup is the header's X, not a third link.
        compose.onNodeWithTag("setup-plugin-skip").assertTextEquals("Skip for now")
        compose.onNodeWithTag("setup-plugin-pc").assertTextEquals("Set up from a computer")
        compose.onNodeWithText("Close setup").assertDoesNotExist()
        compose.onNodeWithTag("setup-close").assertIsDisplayed().assertIsEnabled()
        gone("setup-plugin-rows")
        compose.onNodeWithText("Choose a voice provider").assertDoesNotExist()

        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-working")
        // The button keeps its label next to the turning dust core and is not tappable, and the X waits too.
        compose.onNodeWithTag("setup-plugin-working").assertTextEquals("Installing…").assertIsNotEnabled()
        compose.onNodeWithTag("setup-close").assertIsNotEnabled()
        row("install", "Working")
        row("answers", "Waiting")
        compose.onNodeWithText("Install the Beacon plugin").assertIsDisplayed()
        compose.onNodeWithText("Check that it answers").assertIsDisplayed()
        hold.complete(Unit)

        waitFor("setup-plugin-continue")
        row("install", "Done")
        row("answers", "Done")
        gone("setup-plugin-row-restart")
        compose.onNodeWithText("The Beacon plugin is on your Hermes").assertIsDisplayed()
        compose.onNodeWithText("Beacon is ready on your Hermes.").assertDoesNotExist()
        compose.onNodeWithTag("setup-close").assertIsEnabled()
        assertEquals(1, hermes.installs)
        assertEquals("a fresh install restarts nothing", 0, hermes.restarts)

        compose.onNodeWithTag("setup-plugin-continue").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-plugin-install").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
        compose.onNodeWithText("Step 3 of 4").assertIsDisplayed()
        // The same X closes every later page too, in place of each page's own link (the old page's X leaves with its fade).
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-close").fetchSemanticsNodes().size == 1 && compose.onAllNodesWithTag("setup-plugin-continue").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("setup-close").assertIsDisplayed()
    }

    @Test fun aHermesThatRefusesANonOwnerOffersSkipNotTryAgain() {
        val hermes = AbsentHermes(installFailure = ApiFailure(403, "owner only"))
        show(controller(hermes))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-error")
        compose.onNodeWithText("Only the owner of this Hermes can add the Beacon plugin.").assertIsDisplayed()
        row("install", "Failed")
        // Trying again as a non-owner would fail the same way, so there is no Try again: Skip for now leads, with the computer route beside it.
        gone("setup-plugin-retry")
        compose.onNodeWithTag("setup-plugin-skip").assertIsDisplayed().assertTextEquals("Skip for now")
        compose.onNodeWithTag("setup-plugin-pc").assertIsDisplayed().assertTextEquals("Set up from a computer")
        compose.onNodeWithText("The Beacon plugin is on your Hermes").assertDoesNotExist()
        assertEquals(1, hermes.installs)
        compose.onNodeWithTag("setup-plugin-skip").performClick()
        compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
        assertEquals("skipping installs nothing", 1, hermes.installs)
    }

    @Test fun anEndedSignInOffersSignInAgainAndTheOfferIsBackAfterwards() {
        val hermes = AbsentHermes(installFailure = ApiFailure(401, "signed out"))
        show(controller(hermes))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-error")
        compose.onNodeWithText("Hermes needs you to sign in again.").assertIsDisplayed()
        gone("setup-plugin-retry")
        compose.onNodeWithTag("setup-plugin-signin").assertIsDisplayed().assertTextEquals("Sign in again")
        compose.onNodeWithTag("setup-plugin-skip").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-pc").assertIsDisplayed()

        compose.onNodeWithTag("setup-plugin-signin").performClick()
        assertEquals("the wizard's own sign-in runs", 1, signIns)
        // The failed install is cleared, so once the phone is signed in again the offer is there and Install works.
        compose.onNodeWithTag("setup-plugin-install").assertIsDisplayed()
        gone("setup-plugin-error")
        hermes.installFailure = null
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-continue")
        assertEquals(2, hermes.installs)
    }

    @Test fun aFailedInstallThatCanWorkLaterKeepsThePageAndRetriesIntoSuccess() {
        val hermes = AbsentHermes(installFailure = ConnectException("refused"))
        show(controller(hermes))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-error")
        compose.onNodeWithText("Can't reach Hermes. Check your connection, then try again.").assertIsDisplayed()
        row("install", "Failed")
        compose.onNodeWithTag("setup-plugin-retry").assertIsDisplayed().assertTextEquals("Try again")
        compose.onNodeWithTag("setup-plugin-skip").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-pc").assertIsDisplayed()

        hermes.installFailure = null
        compose.onNodeWithTag("setup-plugin-retry").performClick()
        waitFor("setup-plugin-continue")
        row("install", "Done")
        assertEquals(2, hermes.installs)
    }

    @Test fun aHermesThatCannotAddPluginsOffersOnlyTheComputerRoute() {
        show(controller(AbsentHermes(installFailure = ApiFailure(404, "no route"))))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-error")
        compose.onNodeWithText("This Hermes can't add plugins from the app.").assertIsDisplayed()
        gone("setup-plugin-retry")
        compose.onNodeWithTag("setup-plugin-skip").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-pc").assertTextEquals("Set up from a computer").performClick()
        assertEquals(listOf(BEACON_PC_INSTALL_URL), opened.toList())
    }

    @Test fun aRestartIsOfferedOnlyWhenHermesAsksForOne() {
        val hermes = AbsentHermes(restartRequired = true)
        show(controller(hermes))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-restart")
        compose.onNodeWithText("Restart Hermes to finish").assertIsDisplayed()
        compose.onNodeWithText("The Beacon plugin is installed. Restart Hermes to finish turning it on. Anything Hermes is doing right now will stop.").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-not-now").assertTextEquals("Restart later")
        row("install", "Done")
        row("answers", "Done")
        row("restart", "Waiting")
        assertEquals(0, hermes.restarts)

        compose.onNodeWithTag("setup-plugin-restart").performClick()
        waitFor("setup-plugin-continue")
        row("restart", "Done")
        assertEquals(1, hermes.restarts)
    }

    @Test fun restartLaterAtTheRestartOfferMovesOnToTheCallPages() {
        show(controller(AbsentHermes(restartRequired = true)))
        compose.onNodeWithTag("setup-plugin-install").performClick()
        waitFor("setup-plugin-not-now")
        compose.onNodeWithTag("setup-plugin-not-now").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-plugin-not-now").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
    }

    @Test fun skipForNowMovesOnAndTheLastPageStillOffersTheInstall() {
        val hermes = AbsentHermes()
        show(controller(hermes))
        compose.onNodeWithTag("setup-plugin-skip").performClick()
        compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
        assertEquals(0, hermes.installs)
        // At the end of the wizard a skipped install is still one tap away, as before.
        compose.runOnUiThread { state = state.copy(setupStep = "done", setupPluginMessage = "Beacon plugin not installed on this Hermes — chat works; calls need the plugin.") }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("missing-plugin-install").fetchSemanticsNodes().isNotEmpty() }
        gone("setup-plugin-install")
        // The last page finishes setup with its own button, so it has no X (the old page's X leaves with its fade).
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-close").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun theXClosesSetupFromThePluginPageAndOnlyWhereThereIsSomewhereToGo() {
        show(controller(AbsentHermes()))
        val close = compose.onNodeWithTag("setup-close")
        close.assertContentDescriptionEquals("Close setup")
        close.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        close.performClick()
        assertEquals(1, cancelled)
    }

    @Test fun aSignedOutWizardHasNoXBecauseThereIsNothingToCloseBackTo() {
        state = afterSignIn("absent", step = "connect").copy(authenticated = false)
        show(controller(AbsentHermes()))
        compose.onNodeWithTag("setup-connect").assertIsDisplayed()
        gone("setup-close")
    }

    @Test fun theProbeRunningShowsOneCheckThenGoesStraightToTheCallPagesWhenBeaconIsThere() {
        val hermes = AbsentHermes()
        state = afterSignIn("not_probed")
        show(controller(hermes))
        compose.onNodeWithText("Checking for the Beacon plugin…").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-checking").assertIsDisplayed()
        compose.onNodeWithTag("setup-close").assertIsDisplayed().assertIsEnabled()
        gone("setup-plugin-install")
        compose.runOnUiThread { state = state.copy(bootstrap = bootstrap("available")) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-plugin-checking").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
        assertEquals(0, hermes.installs)
    }

    @Test fun aPluginThatIsThereOrAProbeThatFailedNeverShowsThePage() {
        state = afterSignIn("available")
        show(controller(AbsentHermes()))
        for (plugin in listOf("available", "unauthorized", "error")) {
            compose.runOnUiThread { state = state.copy(bootstrap = bootstrap(plugin)) }
            compose.onNodeWithText("Choose a voice provider").assertIsDisplayed()
            gone("setup-plugin-install")
            gone("setup-plugin-checking")
        }
    }

    @Test fun withoutAnInstallToReachTheButtonOpensThePcSteps() {
        show(null)
        compose.onNodeWithTag("setup-plugin-install").performClick()
        assertEquals(listOf(BEACON_PC_INSTALL_URL), opened.toList())
    }

    // ---- Every page in every look: fits, 48 dp targets, and the screenshots the lane report names. ----

    private class PageCase(val name: String, val phase: SetupPluginPhase, val install: PluginInstallUi, val restartSeen: Boolean = false)

    private val tour = listOf(
        PageCase("0-checking", SetupPluginPhase.Checking, PluginInstallUi()),
        PageCase("1-offer", SetupPluginPhase.Absent, PluginInstallUi()),
        PageCase("2-installing", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Installing)),
        PageCase("3-done", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Done)),
        PageCase("4-failed-denied", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.Denied)),
        PageCase("4-failed-offline", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.Offline)),
        PageCase("5-restart-offer", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.NeedsRestart)),
    )

    // States the screenshots do not name but the fit check still covers.
    private val fitOnly = listOf(
        PageCase("restart-busy", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.NeedsRestart, busy = true)),
        PageCase("restarting", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Restarting)),
        PageCase("done-after-restart", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Done), restartSeen = true),
        PageCase("failed-sign-in", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.SignIn)),
        PageCase("failed-unsupported", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.Unsupported)),
        PageCase("failed-gateway", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.GatewayDown)),
        PageCase("failed-not-yet", SetupPluginPhase.Absent, PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.NotYet)),
    )

    private val tapTargets = listOf("setup-close", "setup-plugin-install", "setup-plugin-retry", "setup-plugin-signin", "setup-plugin-skip", "setup-plugin-pc",
        "setup-plugin-restart", "setup-plugin-not-now", "setup-plugin-continue", "setup-plugin-working",
        // The other wizard pages share the same header and buttons.
        "setup-connect", "setup-probe-retry", "setup-signin", "setup-permission-primary", "setup-permission-skip", "setup-finish")

    private var pageCase by mutableStateOf(tour[1])

    /** Draws the page itself in the given look, so each state can be set directly. [widthDp] null means the screen's own width. */
    private fun showPage(language: String, dark: Boolean, fontScale: Float, widthDp: Int?) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides direction(language)) {
                Box(if (widthDp == null) Modifier else Modifier.width(widthDp.dp)) {
                    NativeLocalization(language) { BeaconTheme(dark = dark) {
                        SetupPluginStepPage(
                            modifier = Modifier, phase = pageCase.phase, install = pageCase.install, restartSeen = pageCase.restartSeen,
                            onInstall = {}, onRetry = {}, onRestart = {}, onNotNow = {}, onContinue = {}, onSkip = {}, onPcInstead = {}, onCancel = {}, onSignIn = {},
                        )
                    } }
                }
            }
        }
    }

    /** Every tap target on the page stays inside [widthDp] (or the screen) and is at least 48 dp tall; in a right-to-left layout the X sits at the left edge. */
    private fun checkPage(label: String, widthDp: Int?, rtl: Boolean) {
        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        val width = widthDp?.dp ?: screen.right
        for (tag in tapTargets) {
            if (compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()) continue
            val bounds = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$label $tag stays inside ${width.value} dp (${bounds.left.value}..${bounds.right.value})", bounds.left >= 0.dp && bounds.right <= width + 0.5.dp)
            assertTrue("$label $tag keeps a 48 dp target (${(bounds.bottom - bounds.top).value})", bounds.bottom - bounds.top >= 47.9.dp && bounds.right - bounds.left >= 47.9.dp)
        }
        if (compose.onAllNodesWithTag("setup-close").fetchSemanticsNodes().isNotEmpty()) {
            val close = compose.onNodeWithTag("setup-close").getUnclippedBoundsInRoot()
            val mark = compose.onNodeWithTag("setup-beacon-glow").getUnclippedBoundsInRoot()
            if (rtl) assertTrue("$label: in right-to-left the X is at the left edge and the logo at the right", close.left < mark.left)
            else assertTrue("$label: the X is at the right edge and the logo at the left", close.left > mark.left)
        }
    }

    private fun tourLook(variant: String, language: String, dark: Boolean, fontScale: Float, widthDp: Int? = null) {
        showPage(language, dark, fontScale, widthDp)
        for (case in tour + fitOnly) {
            compose.runOnUiThread { pageCase = case }
            compose.waitForIdle()
            checkPage("$variant ${case.name}", widthDp, rtl = language == "ar")
            if (case in tour) shot("${case.name}-$variant")
        }
    }

    // ---- The shared header and buttons on the other wizard pages: the X, the loading button and the dust core must not break their layout. ----

    private class WizardCase(val name: String, val app: AppState)

    private fun wizardCases(): List<WizardCase> {
        val base = afterSignIn("available")
        return listOf(
            WizardCase("connect", base.copy(setupStep = "connect")),
            WizardCase("probe-loading", base.copy(setupStep = "probe", loading = true)),
            WizardCase("signin-loading", base.copy(setupStep = "signin", loading = true)),
            WizardCase("voice-provider", base),
            WizardCase("permission-microphone", base.copy(setupStep = "permission-microphone")),
            WizardCase("unsupported", base.copy(setupStep = "unsupported")),
            WizardCase("done", base.copy(setupStep = "done")),
        )
    }

    private fun wizardLook(variant: String, language: String, dark: Boolean, fontScale: Float) {
        state = wizardCases().first().app
        show(null, language, dark, fontScale)
        for (case in wizardCases()) {
            compose.runOnUiThread { state = case.app }
            // The fade between pages ends with one header on screen.
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("setup-beacon-glow").fetchSemanticsNodes().size == 1 }
            compose.waitForIdle()
            checkPage("$variant ${case.name}", null, rtl = language == "ar")
            // Pages that can be closed show the X; the last page and the signed-out first page do not.
            if (case.name != "done") compose.onNodeWithTag("setup-close").assertIsDisplayed()
            // A page in the middle of a request keeps the X, but waits to be tapped.
            if (case.name.endsWith("-loading")) compose.onNodeWithTag("setup-close").assertIsNotEnabled()
            shot("wizard-${case.name}-$variant")
        }
    }

    @Test fun theOtherWizardPagesKeepTheirLayoutInDark() = wizardLook("dark", "en", dark = true, fontScale = 1f)

    @Test fun theOtherWizardPagesKeepTheirLayoutInArabicDarkInARealRightToLeftLayout() = wizardLook("ar-dark", "ar", dark = true, fontScale = 1f)

    @Test fun theOtherWizardPagesKeepTheirLayoutInLightAtLargeText() = wizardLook("large-light", "en", dark = false, fontScale = 1.3f)

    @Test fun everyPageFitsAndIsShotInDark() = tourLook("dark", "en", dark = true, fontScale = 1f)

    @Test fun everyPageFitsAndIsShotInLight() = tourLook("light", "en", dark = false, fontScale = 1f)

    @Test fun everyPageFitsAndIsShotInArabicDarkInARealRightToLeftLayout() = tourLook("ar-dark", "ar", dark = true, fontScale = 1f)

    @Test fun everyPageFitsAndIsShotInLightAtLargeText() = tourLook("large-light", "en", dark = false, fontScale = 1.3f)

    @Test fun everyPageFitsASmallPhoneAtLargeText() = tourLook("small-large", "en", dark = true, fontScale = 1.3f, widthDp = 320)

    @Test fun everyPageFitsASmallPhoneAtLargeTextInArabicRightToLeft() = tourLook("small-large-ar", "ar", dark = true, fontScale = 1.3f, widthDp = 320)

    @Test fun theLightSystemThemeKeepsTheWizardsOwnLookAndGold() {
        show(controller(AbsentHermes()), dark = false)
        compose.onNodeWithText("Add the Beacon plugin to Hermes").assertIsDisplayed()
        compose.onNodeWithTag("setup-plugin-install").assertIsDisplayed()
    }
}
