package dev.hermes.android.setup

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.BEACON_PC_INSTALL_URL
import dev.hermes.android.ui.SETUP_PLUGIN_GATE_STEPS
import dev.hermes.android.ui.SetupPluginAction
import dev.hermes.android.ui.SetupPluginPhase
import dev.hermes.android.ui.SetupPluginRow
import dev.hermes.android.ui.SetupPluginRowState
import dev.hermes.android.ui.SetupSea
import dev.hermes.android.ui.nativeContrast
import dev.hermes.android.ui.setupColorScheme
import dev.hermes.android.ui.setupPluginActions
import dev.hermes.android.ui.setupPluginCanRetry
import dev.hermes.android.ui.setupPluginPhase
import dev.hermes.android.ui.setupPluginRows
import dev.hermes.android.ui.setupPluginStepShown
import dev.hermes.android.updates.GatewayStatus
import dev.hermes.android.updates.PluginInstallController
import dev.hermes.android.updates.PluginInstallEndpoint
import dev.hermes.android.updates.PluginInstallFailure
import dev.hermes.android.updates.PluginInstallStage
import dev.hermes.android.updates.PluginInstallUi
import java.io.File
import java.net.ConnectException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wizard's "Add the Beacon plugin to Hermes" page, against a Hermes that does not have the plugin yet. */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupPluginStepTest {
    /** A Hermes without Beacon: it answers only once the install has run, and says whether its gateway needs a restart. */
    private class AbsentPlugin(
        var installFailure: Exception? = null,
        private val restartRequired: Boolean = false,
        private val unansweredChecks: Int = 0,
    ) : PluginInstallEndpoint {
        var installed = false
        var installs = 0
        var restarts = 0
        private var asked = 0
        override suspend fun install(): JSONObject {
            installs++
            installFailure?.let { throw it }
            installed = true
            return JSONObject().put("ok", true).put("restart_required", restartRequired)
        }
        override suspend fun enable(): JSONObject = JSONObject().put("ok", true)
        override suspend fun present(): Boolean = installed && asked++ >= unansweredChecks
        override suspend fun gateway(): GatewayStatus = GatewayStatus(running = true, busy = false)
        override suspend fun restartGateway() { restarts++ }
    }

    private fun signedIn() = BeaconClient(AuthContext("https://hermes.example", emptyList(), "owner", "default", backend = "hermes-dashboard"), null, persistContext = false)
    private fun bootstrap(plugin: String?) = JSONObject().apply { if (plugin != null) put("optional_features", JSONObject().put("plugin", plugin)) }
    private fun shown(step: String, phase: SetupPluginPhase, install: PluginInstallUi = PluginInstallUi(), handled: Boolean = false, timedOut: Boolean = false, authenticated: Boolean = true) =
        setupPluginStepShown(step, authenticated, phase, install, handled, timedOut)
    private val idle = PluginInstallUi()
    private val done = PluginInstallUi(PluginInstallStage.Done)
    private fun failed(failure: PluginInstallFailure?) = PluginInstallUi(PluginInstallStage.Failed, failure)

    @Test fun theProbeAnswerDecidesWhatThePageDoes() {
        assertEquals(SetupPluginPhase.Checking, setupPluginPhase(bootstrap("not_probed")))
        assertEquals(SetupPluginPhase.Absent, setupPluginPhase(bootstrap("absent")))
        assertEquals(SetupPluginPhase.Present, setupPluginPhase(bootstrap("available")))
        // The plugin answering before this phone is linked is a present plugin: signing in links the phone.
        assertEquals(SetupPluginPhase.Present, setupPluginPhase(bootstrap("unauthorized")))
        // A failed, unreadable or missing probe says nothing about the plugin, so the wizard never stops for it.
        for (plugin in listOf("error", "malformed", "unsupported", "", null)) assertEquals("$plugin", SetupPluginPhase.Unknown, setupPluginPhase(bootstrap(plugin)))
        assertEquals(SetupPluginPhase.Unknown, setupPluginPhase(JSONObject()))
    }

    @Test fun onlyAnAbsentPluginStopsTheWizardOnTheFirstCallPage() {
        for (step in SETUP_PLUGIN_GATE_STEPS) {
            assertTrue(step, shown(step, SetupPluginPhase.Absent))
            assertTrue("$step while the probe runs", shown(step, SetupPluginPhase.Checking))
            assertFalse("$step with Beacon there", shown(step, SetupPluginPhase.Present))
            assertFalse("$step with an unreadable probe", shown(step, SetupPluginPhase.Unknown))
        }
        // Not on the pages before sign-in, in the middle of the key page, or on the last page, which has its own install card.
        for (step in listOf("connect", "probe", "signin", "unsupported", "voice-key", "done")) assertFalse(step, shown(step, SetupPluginPhase.Absent))
        assertFalse("signed out", shown("voice-provider", SetupPluginPhase.Absent, authenticated = false))
    }

    @Test fun theOwnerMovesPastThePageOnceAndASlowProbeDoesNotHoldTheWizard() {
        assertFalse(shown("voice-provider", SetupPluginPhase.Absent, handled = true))
        assertFalse("checking gives up after its wait", shown("voice-provider", SetupPluginPhase.Checking, timedOut = true))
        assertTrue("a late answer still brings the page back", shown("voice-provider", SetupPluginPhase.Absent, timedOut = true))
        // An install in progress, or one that just finished, keeps the page until the owner continues, even once the probe reads Beacon.
        for (stage in PluginInstallStage.values().filter { it != PluginInstallStage.Idle }) {
            assertTrue("$stage", shown("voice-provider", SetupPluginPhase.Present, PluginInstallUi(stage)))
            assertTrue("$stage", shown("voice-provider", SetupPluginPhase.Absent, PluginInstallUi(stage)))
        }
    }

    @Test fun theChecklistShowsOnlyWhatTheInstallReported() {
        fun rows(ui: PluginInstallUi, restartSeen: Boolean = false) = setupPluginRows(ui, restartSeen)
        val i = SetupPluginRow.Install
        val a = SetupPluginRow.Answers
        val r = SetupPluginRow.Restart
        assertEquals(emptyList<Any>(), rows(idle))
        assertEquals(listOf(i to SetupPluginRowState.Working, a to SetupPluginRowState.Waiting), rows(PluginInstallUi(PluginInstallStage.Installing)))
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Working), rows(PluginInstallUi(PluginInstallStage.Checking)))
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Done, r to SetupPluginRowState.Waiting), rows(PluginInstallUi(PluginInstallStage.NeedsRestart)))
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Done, r to SetupPluginRowState.Working), rows(PluginInstallUi(PluginInstallStage.Restarting)))
        // No restart row unless Hermes asked for a restart.
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Done), rows(done))
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Done, r to SetupPluginRowState.Done), rows(done, restartSeen = true))
        for (failure in listOf(PluginInstallFailure.SignIn, PluginInstallFailure.Denied, PluginInstallFailure.Offline, PluginInstallFailure.Unsupported, PluginInstallFailure.Busy, PluginInstallFailure.Error)) {
            assertEquals("$failure", listOf(i to SetupPluginRowState.Failed, a to SetupPluginRowState.Waiting), rows(failed(failure)))
        }
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Failed), rows(failed(PluginInstallFailure.NotYet)))
        assertEquals(listOf(i to SetupPluginRowState.Done, a to SetupPluginRowState.Done, r to SetupPluginRowState.Failed), rows(failed(PluginInstallFailure.GatewayDown)))
    }

    @Test fun tryAgainAppearsOnlyWhereTryingAgainCanChangeTheOutcome() {
        // Hermes refusing a non-owner and a sign-in that has ended fail the same way every time; a Hermes without the install route has only the computer route.
        for (failure in listOf(PluginInstallFailure.Unsupported, PluginInstallFailure.Denied, PluginInstallFailure.SignIn)) assertFalse("$failure", setupPluginCanRetry(failed(failure)))
        for (failure in PluginInstallFailure.values().filter { it !in setOf(PluginInstallFailure.Unsupported, PluginInstallFailure.Denied, PluginInstallFailure.SignIn) }) assertTrue("$failure", setupPluginCanRetry(failed(failure)))
        assertTrue("a failure without a reason can still be tried again", setupPluginCanRetry(failed(null)))
        assertFalse(setupPluginCanRetry(PluginInstallUi(PluginInstallStage.Installing)))
        assertFalse(setupPluginCanRetry(done))
    }

    @Test fun eachStateOffersOnePrimaryAndAtMostTwoQuietActions() {
        fun actions(ui: PluginInstallUi) = setupPluginActions(ui).let { it.primary to it.text }
        assertEquals(SetupPluginAction.Install to listOf(SetupPluginAction.Skip, SetupPluginAction.Computer), actions(idle))
        // While Hermes works there is nothing to tap; the page shows the working button.
        for (stage in listOf(PluginInstallStage.Installing, PluginInstallStage.Checking, PluginInstallStage.Restarting)) assertEquals("$stage", null to emptyList<SetupPluginAction>(), actions(PluginInstallUi(stage)))
        assertEquals(SetupPluginAction.Restart to listOf(SetupPluginAction.RestartLater), actions(PluginInstallUi(PluginInstallStage.NeedsRestart)))
        assertEquals("a refused restart keeps the same offer", SetupPluginAction.Restart to listOf(SetupPluginAction.RestartLater), actions(PluginInstallUi(PluginInstallStage.NeedsRestart, PluginInstallFailure.Busy)))
        assertEquals(SetupPluginAction.Continue to emptyList<SetupPluginAction>(), actions(done))
        // Denied: only the owner can add it, so the way on is to skip, or to ask the owner to set it up from a computer.
        assertEquals(SetupPluginAction.Skip to listOf(SetupPluginAction.Computer), actions(failed(PluginInstallFailure.Denied)))
        // SignIn: the primary signs in again, which is what makes the next try work.
        assertEquals(SetupPluginAction.SignInAgain to listOf(SetupPluginAction.Skip, SetupPluginAction.Computer), actions(failed(PluginInstallFailure.SignIn)))
        assertEquals(SetupPluginAction.Computer to listOf(SetupPluginAction.Skip), actions(failed(PluginInstallFailure.Unsupported)))
        assertEquals("the plugin answers and only the restart is late", SetupPluginAction.TryAgain to listOf(SetupPluginAction.ContinueAnyway), actions(failed(PluginInstallFailure.GatewayDown)))
        for (failure in listOf(PluginInstallFailure.Offline, PluginInstallFailure.Busy, PluginInstallFailure.Error, PluginInstallFailure.NotYet, null)) assertEquals("$failure", SetupPluginAction.TryAgain to listOf(SetupPluginAction.Skip, SetupPluginAction.Computer), actions(failed(failure)))
        // Whatever the state, the text actions are at most two and the primary is never repeated among them.
        for (stage in PluginInstallStage.values()) for (failure in listOf(null) + PluginInstallFailure.values().toList()) {
            val set = setupPluginActions(PluginInstallUi(stage, failure))
            assertTrue("$stage $failure", set.text.size <= 2)
            assertFalse("$stage $failure", set.primary in set.text)
            assertEquals("$stage $failure", set.text.size, set.text.toSet().size)
        }
    }

    @Test fun theWizardKeepsTheDarkGoldAndErrorRedInBothSystemThemes() {
        val light = setupColorScheme(lightColorScheme(primary = Color(0xFF80511D), error = Color(0xFFA93128)))
        val dark = setupColorScheme(darkColorScheme(primary = Color(0xFFE7B579), error = Color(0xFFFFB4AB)))
        assertEquals("the same accent whichever theme the phone is in", dark.primary, light.primary)
        assertEquals(dark.error, light.error)
        for (scheme in listOf(light, dark)) {
            for (surface in listOf(SetupSea, scheme.surface, scheme.surfaceContainer, scheme.surfaceContainerHigh, scheme.surfaceContainerHighest)) {
                assertTrue("primary on $surface", nativeContrast(scheme.primary, surface) >= 4.5f)
                assertTrue("error on $surface", nativeContrast(scheme.error, surface) >= 4.5f)
            }
            assertTrue("label on the filled button", nativeContrast(scheme.onPrimary, scheme.primary) >= 4.5f)
        }
    }

    @Test fun anAbsentPluginIsInstalledFromTheWizardAndTheOwnerContinuesToTheCallPages() = runTest {
        val hermes = AbsentPlugin()
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { hermes })
        // Signed in, the probe says absent, and the first call page would come next: the plugin page takes its place.
        assertTrue(shown("voice-provider", setupPluginPhase(bootstrap("absent")), controller.state.value))

        controller.install()
        assertEquals(PluginInstallStage.Installing, controller.state.value.stage)
        assertEquals(listOf(SetupPluginRow.Install to SetupPluginRowState.Working, SetupPluginRow.Answers to SetupPluginRowState.Waiting), setupPluginRows(controller.state.value, false))
        advanceUntilIdle()
        assertEquals(done, controller.state.value)
        assertEquals("the app reads the plugin again exactly once", 1, finished)
        assertEquals(1, hermes.installs)
        assertEquals("a fresh install on a Hermes that loads it by itself restarts nothing", 0, hermes.restarts)

        // The app's re-read now says Beacon is there; the page stays on its Done state until the owner continues.
        assertTrue(shown("voice-provider", setupPluginPhase(bootstrap("available")), controller.state.value))
        controller.reset()
        assertFalse(shown("voice-provider", setupPluginPhase(bootstrap("available")), controller.state.value))
        assertFalse(shown("voice-provider", setupPluginPhase(bootstrap("available")), controller.state.value, handled = true))
    }

    @Test fun aFailedInstallKeepsThePageSaysWhyAndRetriesIntoSuccessWhereRetryingCanWork() = runTest {
        val cases = listOf(
            ApiFailure(403, "owner only") to PluginInstallFailure.Denied,
            ApiFailure(401, "signed out") to PluginInstallFailure.SignIn,
            ApiFailure(404, "no route") to PluginInstallFailure.Unsupported,
            ApiFailure(409, "busy") to PluginInstallFailure.Busy,
            ApiFailure(500, "boom") to PluginInstallFailure.Error,
            ConnectException("refused") to PluginInstallFailure.Offline,
        )
        for ((error, failure) in cases) {
            val hermes = AbsentPlugin(installFailure = error)
            var finished = 0
            val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { hermes })
            controller.install(); advanceUntilIdle()
            assertEquals("$error", PluginInstallUi(PluginInstallStage.Failed, failure), controller.state.value)
            assertEquals("a failure never reads the plugin as installed", 0, finished)
            assertTrue("the page stays", shown("voice-provider", SetupPluginPhase.Absent, controller.state.value))
            assertEquals(listOf(SetupPluginRow.Install to SetupPluginRowState.Failed, SetupPluginRow.Answers to SetupPluginRowState.Waiting), setupPluginRows(controller.state.value, false))
            if (!setupPluginCanRetry(controller.state.value)) {
                assertTrue("$failure has no Try again", failure in setOf(PluginInstallFailure.Unsupported, PluginInstallFailure.Denied, PluginInstallFailure.SignIn))
                continue
            }
            hermes.installFailure = null
            controller.retry(); advanceUntilIdle()
            assertEquals("$error", done, controller.state.value)
            assertEquals(1, finished)
        }
    }

    @Test fun signingInAgainClearsTheFailedInstallSoTheOfferIsBackAfterwards() = runTest {
        val hermes = AbsentPlugin(installFailure = ApiFailure(401, "signed out"))
        val controller = PluginInstallController(this, ::signedIn, {}, {}, { hermes })
        controller.install(); advanceUntilIdle()
        assertEquals(SetupPluginAction.SignInAgain, setupPluginActions(controller.state.value).primary)
        // The wizard's Sign in again resets the install and signs in; the page is the offer again and Install runs once more.
        controller.reset()
        assertEquals(idle, controller.state.value)
        assertEquals(SetupPluginAction.Install, setupPluginActions(controller.state.value).primary)
        assertTrue(shown("voice-provider", SetupPluginPhase.Absent, controller.state.value))
        hermes.installFailure = null
        controller.install(); advanceUntilIdle()
        assertEquals(done, controller.state.value)
    }

    @Test fun aHermesThatAnswersLateFailsHonestlyThenTheCheckRetriesWithoutInstallingTwice() = runTest {
        val hermes = AbsentPlugin(unansweredChecks = 8)
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { hermes })
        controller.install(); advanceUntilIdle()
        assertEquals(PluginInstallUi(PluginInstallStage.Failed, PluginInstallFailure.NotYet), controller.state.value)
        assertEquals(listOf(SetupPluginRow.Install to SetupPluginRowState.Done, SetupPluginRow.Answers to SetupPluginRowState.Failed), setupPluginRows(controller.state.value, false))
        assertEquals(0, finished)
        controller.retry(); advanceUntilIdle()
        assertEquals(done, controller.state.value)
        assertEquals("only the check ran again", 1, hermes.installs)
    }

    @Test fun aRestartIsOfferedOnlyWhenHermesAsksAndTheChecklistKeepsItWhenItFinishes() = runTest {
        val hermes = AbsentPlugin(restartRequired = true)
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { hermes })
        controller.install(); advanceUntilIdle()
        assertEquals(PluginInstallStage.NeedsRestart, controller.state.value.stage)
        assertEquals(0, hermes.restarts)
        assertEquals(0, finished)
        controller.restart()
        assertEquals(PluginInstallStage.Restarting, controller.state.value.stage)
        advanceUntilIdle()
        assertEquals(done, controller.state.value)
        assertEquals(1, hermes.restarts)
        assertEquals(1, finished)
        assertEquals(3, setupPluginRows(controller.state.value, restartSeen = true).size)
    }

    @Test fun restartLaterAtTheRestartOfferLetsTheWizardOnAndReadsBeaconAgain() = runTest {
        val hermes = AbsentPlugin(restartRequired = true)
        var finished = 0
        val controller = PluginInstallController(this, ::signedIn, { finished++ }, {}, { hermes })
        controller.install(); advanceUntilIdle()
        assertEquals(SetupPluginAction.RestartLater, setupPluginActions(controller.state.value).text.single())
        controller.dismiss()
        assertEquals(idle, controller.state.value)
        assertEquals(1, finished)
        assertEquals(0, hermes.restarts)
        // The wizard marks the page handled when the owner taps Restart later, so the page does not come back while the app reads Beacon again.
        assertFalse(shown("voice-provider", SetupPluginPhase.Absent, controller.state.value, handled = true))
    }

    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) { "Run from the Beacon app, Android project or repository root." }
    private val catalog by lazy { JSONObject(File(app, "src/main/assets/native-locales.json").readText()) }
    private val normalize = { text: String -> text.lowercase().replace("…", "...").replace(Regex("\\s+"), " ").trim() }

    @Test fun theNewTextIsTranslatedIntoEveryLanguage() {
        // The English text, and the names that stay as they are in every language; the rest must differ from English.
        val texts = mapOf(
            "Add the Beacon plugin to Hermes" to listOf("Beacon", "Hermes"),
            "The Beacon plugin is on your Hermes" to listOf("Beacon", "Hermes"),
            "Checking for the Beacon plugin…" to listOf("Beacon"),
            "Hermes downloads the Beacon plugin from GitHub and turns it on." to listOf("Hermes", "Beacon", "GitHub"),
            "Install the Beacon plugin" to listOf("Beacon"),
            "Check that it answers" to emptyList(),
            "The Beacon plugin is installed. Restart Hermes to finish turning it on. Anything Hermes is doing right now will stop." to listOf("Beacon", "Hermes"),
            "The Beacon plugin is installed. Hermes is working right now; restarting stops that work." to listOf("Beacon", "Hermes"),
            "Only the owner of this Hermes can add the Beacon plugin." to listOf("Beacon", "Hermes"),
            "Hermes needs you to sign in again." to listOf("Hermes"),
            "Set up from a computer" to emptyList(),
            "Restart later" to emptyList(),
        )
        val languages = catalog.keys().asSequence().filter { it != "en" }.toList()
        assertEquals("15 languages besides English", 15, languages.size)
        for (language in languages) for ((english, names) in texts) {
            val value = catalog.getJSONObject(language).optString(normalize(english))
            assertTrue("$language is missing \"$english\"", value.isNotBlank())
            assertNotEquals("$language shows \"$english\" in English", english, value)
            for (name in names) assertTrue("$language keeps $name in \"$english\"", name in value)
        }
        // Arabic reads in Arabic script around the app and server names, not in a transliteration or English.
        val arabic = Regex("[\\u0600-\\u06FF]")
        for ((english, _) in texts) assertTrue("ar \"$english\" is in Arabic", arabic.containsMatchIn(catalog.getJSONObject("ar").getString(normalize(english))))
    }

    private fun pageLiterals(): Set<String> {
        val source = File(app, "src/main/java/dev/hermes/android/ui/SetupPluginStep.kt").readText()
        return Regex("""uiText\((?:if \([^)]*\) )?"((?:[^"\\]|\\.)*)"(?: else "((?:[^"\\]|\\.)*)")?\)""").findAll(source).flatMap { m -> m.groupValues.drop(1).filter { it.isNotBlank() } }.map { it.replace("\\'", "'") }.toSet()
    }

    @Test fun everyTextThePageShowsIsInTheCatalogForEveryLanguage() {
        val literals = pageLiterals()
        assertTrue("The scan found only ${literals.size} texts", literals.size >= 25)
        val missing = catalog.keys().asSequence().filter { it != "en" }.flatMap { language -> literals.filter { catalog.getJSONObject(language).optString(normalize(it)).isBlank() }.map { "$language: $it" } }.toList()
        assertTrue("Not translated:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test fun thePageCallsTheThingOnHermesTheBeaconPluginAndNothingElse() {
        val literals = pageLiterals()
        // The owner's words: the Beacon plugin, on Hermes. Not "Beacon on your Hermes", "Hermes plugin" or a bare "the plugin".
        for (text in literals) {
            assertFalse("\"$text\" says Beacon on Hermes", Regex("(?i)beacon on (your )?hermes").containsMatchIn(text))
            assertFalse("\"$text\" says Hermes plugin", Regex("(?i)hermes plugin").containsMatchIn(text))
            assertFalse("\"$text\" names a URL", "github.com/" in text || "http" in text)
        }
        for (expected in listOf("Add the Beacon plugin to Hermes", "The Beacon plugin is on your Hermes", "Install the Beacon plugin", "Check that it answers", "Hermes downloads the Beacon plugin from GitHub and turns it on.",
            "Only the owner of this Hermes can add the Beacon plugin.", "Set up from a computer", "Restart later", "Skip for now")) assertTrue(expected, expected in literals)
        // The old wording is gone from the page.
        for (gone in listOf("Install Beacon on your Hermes", "Do it on the PC instead", "Hermes only lets its owner add Beacon.", "Not now", "Beacon is ready on your Hermes.")) assertFalse(gone, gone in literals)
    }

    @Test fun thePcLinkNamesTheReadmeSectionThePageSendsTheOwnerTo() {
        assertEquals("https://github.com/xploit9/beacon#alternative-set-up-from-the-pc", BEACON_PC_INSTALL_URL)
    }

    @Test fun theHeaderCloseButtonReplacesTheTextLinksOnEverySetupPage() {
        val wizard = File(app, "src/main/java/dev/hermes/android/ui/SetupWizard.kt").readText()
        val shell = File(app, "src/main/java/dev/hermes/android/ui/SetupShell.kt").readText()
        val plugin = File(app, "src/main/java/dev/hermes/android/ui/SetupPluginStep.kt").readText()
        // One X in the shell's header, labelled "Close setup"; no page draws its own "Close setup" link any more.
        assertTrue("setup-close" in shell && """Icon(Icons.Rounded.Close, uiText("Close setup"))""" in shell)
        assertFalse("SetupTextAction(uiText(\"Close setup\")" in wizard)
        assertFalse("the plugin page leaves Close setup to the header", "Close setup" in plugin)
        assertFalse("the wizard gives the voice page no close link of its own", "onClose = onCancel.takeIf" in wizard)
        assertTrue("the wizard hands every page the same close action", "LocalSetupClose provides close" in wizard)
    }
}
