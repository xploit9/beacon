package dev.hermes.android.agentphone

import dev.hermes.android.data.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The app's side of L2's contract v1 (plus Root's additions and L3's approvals), against a fake that answers as the plugin does. */
class AgentPhoneApiTest {
    private fun <T> AgentPhoneResult<T>.value(): T = (this as AgentPhoneResult.Done).value
    private fun AgentPhoneResult<*>.line(): AgentPhoneLine = (this as AgentPhoneResult.Refused).line

    @Test fun aHermesWithNoPhoneReadsAsNotSetUp() = runTest {
        val (status, body) = AgentPhoneApi(FakePhone().on("GET", "", 200, Contract.NOT_SET_UP)).status().value()
        assertFalse(status.installed)
        assertEquals(AgentPhonePower.Off, status.power)
        assertEquals(AgentPhoneInstallState.Idle, status.install.state)
        assertNull(status.facts); assertNull(status.storageBytes); assertNull(status.helper); assertNull(status.inUse)
        assertEquals(false, body.optBoolean("installed"))
    }

    @Test fun aRunningDownloadIsReadWithItsBytes() = runTest {
        val status = AgentPhoneApi(FakePhone().on("GET", "", 200, Contract.DOWNLOADING)).status().value().first
        assertEquals(AgentPhoneInstallState.Running, status.install.state)
        assertEquals(AgentPhoneInstallPhase.Download, status.install.phase)
        assertEquals(1_200_000_000L, status.install.doneBytes)
        assertEquals(2_400_000_000L, status.install.totalBytes)
        assertTrue(status.install.working)
    }

    @Test fun rootsAdditionsAreReadWhenTheStatusHasThem() = runTest {
        val status = AgentPhoneApi(FakePhone().on("GET", "", 200, Contract.ON_FULL)).status().value().first
        assertEquals(AgentPhoneFacts(google = true, chrome = 133, chromeAutofill = null, logins = 0), status.facts)
        assertEquals(9_800_000_000L, status.storageBytes)
        assertEquals(AgentPhoneHelper.Update, status.helper)
        assertEquals(true, status.inUse)
        assertEquals(AgentPhonePower.On, status.power)
        assertEquals(AgentPhoneLease(human = false, you = false, away = false), status.lease)
        // AGENTUX-121 P2-1: the page says the short line; the host's whole reason is kept for Details.
        assertEquals(AGENT_PHONE_DEV_TOOLS, agentPhoneLine(status.warning)?.template)
        assertEquals("Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to.", agentPhoneLine(status.warning)?.raw)
    }

    @Test fun everyFinalHelperValueOfL2Parses() {
        val read = { word: String -> parseAgentPhoneStatus(JSONObject("""{"installed": true, "phone": "on", "helper": "$word"}""")).helper }
        assertEquals(AgentPhoneHelper.Ready, read("ready"))
        assertEquals(AgentPhoneHelper.Update, read("update"))
        assertEquals(AgentPhoneHelper.Off, read("off"))
        assertEquals(AgentPhoneHelper.Missing, read("missing"))
        assertNull(parseAgentPhoneStatus(JSONObject("""{"installed": true, "phone": "on", "helper": null}""")).helper)
    }

    @Test fun theHelperUpdateLinesOfL2HaveOneActionEach() {
        val fallback = AgentPhoneLine(AGENT_PHONE_WENT_WRONG, action = AgentPhoneAction.TryAgain)
        assertEquals(AgentPhoneAction.TryAgain, agentPhoneLine("The helper didn't install. Try again.", fallback)?.action)
        assertEquals(AgentPhoneAction.Update, agentPhoneLine("Beacon's helper file is damaged. Update Beacon.", fallback)?.action)
        assertEquals(AgentPhoneAction.Update, agentPhoneLine("This Beacon has no helper to install.", fallback)?.action)
        assertEquals(AgentPhoneAction.None, agentPhoneLine("Turn the phone on first.", fallback)?.action)
    }

    @Test fun anUnknownHelperWordOrAnOddFieldIsUnknownNotAGuess() {
        val status = parseAgentPhoneStatus(JSONObject("""{"installed": "yes", "phone": "rebooting", "helper": "maybe", "in_use": 1, "storage_bytes": -5, "setup": {"chrome": "135"}}"""))
        assertFalse(status.installed)
        assertEquals(AgentPhonePower.Off, status.power)
        assertNull(status.helper); assertNull(status.inUse); assertNull(status.storageBytes)
        assertEquals(AgentPhoneFacts(), status.facts)
    }

    @Test fun startIsAcceptedAsStartingOrAlreadyOn() = runTest {
        val phone = FakePhone().on("POST", "/start", 202, """{"phone": "starting"}""").on("POST", "/start", 200, """{"phone": "on"}""")
        val api = AgentPhoneApi(phone)
        assertEquals(AgentPhonePower.Starting, api.start().value())
        assertEquals(AgentPhonePower.On, api.start().value())
        assertEquals(listOf("POST /start", "POST /start"), phone.routes())
    }

    @Test fun aRefusedStartKeepsItsOwnLineFromTheBody() = runTest {
        val line = AgentPhoneApi(FakePhone().on("POST", "/start", 409, """{"phone": "off", "line": "Turn on Hermes sign-in first."}""")).start().line()
        assertEquals("Turn on Hermes sign-in first.", line.template)
        assertEquals(AgentPhoneAction.How, line.action)
    }

    @Test fun theStartGatesFilledInNameIsReplacedByTheAppsOwn() = runTest {
        // start_gate() fills in "Beacon"; the app shows its own name for the assistant.
        val line = AgentPhoneApi(FakePhone().on("POST", "/start", 409, """{"phone": "off", "line": "Beacon's phone can't start while Hermes' dashboard is off."}""")).start().line()
        assertEquals("{name}'s phone can't start while Hermes' dashboard is off.", line.template)
        assertEquals(AgentPhoneAction.TryAgain, line.action)
    }

    @Test fun checksAreReadRowByRowWithTheirGuideLinks() = runTest {
        val phone = FakePhone().on("POST", "/checks", 200, Contract.CHECKS_SIGN_IN_OFF)
        val checks = AgentPhoneApi(phone).checks().value()
        assertFalse(checks.ok)
        assertTrue(checks.needsContinue)
        assertEquals(listOf("platform", "virtualisation", "memory", "disk", "sign_in", "developer_tools"), checks.rows.map { it.id })
        val signIn = checks.rows.single { it.id == "sign_in" }
        assertEquals(AgentPhoneCheckStatus.Fail, signIn.status)
        assertEquals(AGENT_PHONE_SIGN_IN_GUIDE, signIn.how)
        assertTrue(checks.rows.single { it.id == "developer_tools" }.needsContinue)
        assertEquals(AgentPhoneApi.CHECKS_MS, phone.calls.single().timeoutMillis)
    }

    @Test fun aGuideLinkThatIsNotAnHttpsPageIsDropped() {
        val checks = parseAgentPhoneChecks(JSONObject("""{"ok": false, "rows": [{"id": "sign_in", "status": "fail", "how": "javascript:alert(1)"}]}"""))
        assertNull(checks.rows.single().how)
    }

    @Test fun termsAreKeptVerbatimWithTheirSizes() = runTest {
        val phone = FakePhone().on("GET", "/terms", 200, Contract.TERMS)
        val terms = AgentPhoneApi(phone).terms().value()
        assertEquals(listOf("android-sdk-license"), terms.licences.map { it.id })
        assertEquals("Terms and Conditions\n\nThis is the Android Software Development Kit License Agreement.", terms.licences.single().text)
        assertEquals(2_400_000_000L, terms.downloadBytes)
        assertEquals(3_400_000_000L, terms.installedBytes)
        assertEquals(AgentPhoneApi.TERMS_MS, phone.calls.single().timeoutMillis)
    }

    @Test fun termsThatCantBeFetchedSayWhyAndOfferTheirAction() = runTest {
        val line = AgentPhoneApi(FakePhone().on("GET", "/terms", 502, Contract.TERMS_OFFLINE)).terms().line()
        assertEquals("Download stopped. Check the Hermes computer's internet.", line.template)
        // Here trying again fetches the terms again; nothing was downloaded to resume.
        assertEquals(AgentPhoneAction.TryAgain, line.action)
    }

    @Test fun installSendsExactlyTheLicencesShownAndTheOwnersContinue() = runTest {
        val phone = FakePhone().on("POST", "/install", 202, Contract.INSTALL_STARTED)
        val install = AgentPhoneApi(phone).install(listOf("android-sdk-license"), continueWarnings = true).value()
        assertEquals(AgentPhoneInstallState.Running, install.state)
        val body = phone.calls.single().body!!
        assertEquals(setOf("accept", "continue"), body.keys().asSequence().toSet())
        assertEquals("android-sdk-license", body.getJSONArray("accept").getString(0))
        assertEquals(true, body.getBoolean("continue"))
    }

    @Test fun installRefusedForOldChecksAsksToCheckAgain() = runTest {
        val line = AgentPhoneApi(FakePhone().on("POST", "/install", 409, """{"detail": "Check this computer first."}""")).install(listOf("x"), false).line()
        assertEquals(AgentPhoneAction.CheckAgain, line.action)
        val terms = AgentPhoneApi(FakePhone().on("POST", "/install", 409, """{"detail": "Read and accept the terms first."}""")).install(listOf("x"), false).line()
        assertEquals(AgentPhoneAction.ReadTerms, terms.action)
    }

    @Test fun anInstallThatAlreadyRunsAnswersWithItsProgress() = runTest {
        val install = AgentPhoneApi(FakePhone().on("POST", "/install", 409, Contract.INSTALL_STARTED)).install(listOf("x"), false).value()
        assertEquals(AgentPhoneInstallState.Running, install.state)
    }

    @Test fun pauseResumeAndCancelGoToTheirOwnRoutes() = runTest {
        val phone = FakePhone().on("POST", "/install/pause", 200, """{"state": "paused"}""").on("POST", "/install/resume", 202, """{"state": "running", "phase": "download"}""")
            .on("POST", "/install/cancel", 200, """{"state": "cancelled"}""")
        val api = AgentPhoneApi(phone)
        assertEquals(AgentPhoneInstallState.Paused, api.pause().value().state)
        assertEquals(AgentPhoneInstallState.Running, api.resume().value().state)
        assertEquals(AgentPhoneInstallState.Cancelled, api.cancel().value().state)
        assertEquals(listOf("POST /install/pause", "POST /install/resume", "POST /install/cancel"), phone.routes())
    }

    @Test fun nothingToResumeOffersToStartAgain() = runTest {
        val line = AgentPhoneApi(FakePhone().on("POST", "/install/resume", 409, """{"detail": "Nothing to resume."}""")).resume().line()
        assertEquals(AgentPhoneAction.StartAgain, line.action)
        assertEquals("Nothing to resume.", line.raw)
    }

    @Test fun aFailedInstallKeepsItsLineFixAndRetry() {
        val install = parseAgentPhoneInstall(JSONObject("""{"state": "failed", "line": "Not enough free space. Free up 3 GB on the Hermes computer.", "fix": null, "retry": false}"""))
        assertEquals(AgentPhoneInstallState.Failed, install.state)
        assertTrue(install.resumable)
        val line = agentPhoneLine(install.line)!!
        assertEquals("Not enough free space. Free up {0} GB on the Hermes computer.", line.template)
        assertEquals(listOf("3"), line.args)
        assertEquals(AgentPhoneAction.CheckAgain, line.action)
    }

    @Test fun removeSendsTheLoginsChoiceAndReturnsTheSpaceFreed() = runTest {
        val phone = FakePhone().on("POST", "/remove", 200, """{"removed": true, "freed_bytes": 9800000000}""")
        assertEquals(9_800_000_000L, AgentPhoneApi(phone).remove(deleteLogins = true).value())
        assertEquals(true, phone.calls.single().body!!.getBoolean("delete_logins"))
        assertEquals(AgentPhoneApi.REMOVE_MS, phone.calls.single().timeoutMillis)
        val unknown = FakePhone().on("POST", "/remove", 200, """{"removed": true, "freed_bytes": null}""")
        assertNull(AgentPhoneApi(unknown).remove(false).value())
    }

    @Test fun removeBeforeThePluginCanAsksForAnUpdate() = runTest {
        val line = AgentPhoneApi(FakePhone().on("POST", "/remove", 503, """{"detail": "{name}'s phone needs a Beacon update."}""")).remove(false).line()
        assertEquals(AGENT_PHONE_NEEDS_UPDATE, line.template)
        assertEquals(AgentPhoneAction.Update, line.action)
    }

    @Test fun anOlderPluginWithoutTheRoutesAsksForAnUpdate() = runTest {
        val line = AgentPhoneApi(FakePhone()).status().line()
        assertEquals(AGENT_PHONE_NEEDS_UPDATE, line.template)
        assertEquals(AgentPhoneAction.Update, line.action)
    }

    @Test fun hermesOutOfReachSaysSoWithTryAgain() = runTest {
        val line = AgentPhoneApi(FakePhone().offline("GET", "")).status().line()
        assertEquals(AGENT_PHONE_OFFLINE, line.template)
        assertEquals(AgentPhoneAction.TryAgain, line.action)
    }

    @Test fun hermesUpdatingAndBusyAreTheAppsOwnLines() = runTest {
        val updating = AgentPhoneApi(FakePhone().on("POST", "/stop", 503, """{"detail": "Update in progress"}""")).stop().line()
        assertEquals(AGENT_PHONE_UPDATING, updating.template)
        val busy = AgentPhoneApi(FakePhone().on("POST", "/stop", 503, """{"detail": "Busy, try again."}""")).stop().line()
        assertEquals(AGENT_PHONE_BUSY, busy.template)
        assertEquals(AgentPhoneAction.TryAgain, busy.action)
    }

    @Test fun anotherProfilesPhoneOffersToChooseAProfile() = runTest {
        val line = AgentPhoneApi(FakePhone().on("POST", "/start", 403, """{"detail": "{name}'s phone belongs to another profile."}""")).start().line()
        assertEquals(AgentPhoneAction.ChooseProfile, line.action)
    }

    @Test fun onlyNeedsYouNoticesAreReadFromTheApprovals() = runTest {
        val notices = AgentPhoneApi(FakePhone().on("GET", "/approvals", 200, Contract.APPROVALS)).needsYou().value()
        assertEquals(listOf(AgentPhoneNeedsYou("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "The Kanto Foods login didn't work.", "Kanto Foods")), notices)
    }

    @Test fun answeringANoticeIsItsDecision() = runTest {
        val phone = FakePhone().on("POST", "/approvals/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb/decision", 200, """{"approved": true}""")
        AgentPhoneApi(phone).answerNeedsYou("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", open = true).value()
        assertEquals(true, phone.calls.single().body!!.getBoolean("approved"))
    }

    @Test fun activityIsReadWithItsTimesInMilliseconds() = runTest {
        val phone = FakePhone().on("GET", "/activity?limit=50", 200, Contract.ACTIVITY)
        val rows = AgentPhoneApi(phone).activity().value()
        assertEquals(2, rows.size)
        assertEquals(1_794_916_000_500L, rows[0].atMillis)
        assertTrue(rows[0].approved)
        assertEquals("fill", rows[1].action)
        assertFalse(rows[1].approved)
    }

    @Test fun thePolicyIsReadAndWrittenFieldByField() = runTest {
        val phone = FakePhone().on("GET", "/policy", 200, Contract.POLICY).on("PUT", "/policy", 200, """{"enabled": true, "ask": "every_action"}""")
        val api = AgentPhoneApi(phone)
        assertEquals(AgentPhonePolicy(true, AgentPhoneAsk.PaymentsAndSends), api.policy().value())
        assertEquals(AgentPhoneAsk.EveryAction, api.setPolicy(ask = AgentPhoneAsk.EveryAction).value().ask)
        assertEquals(setOf("ask"), phone.calls.last().body!!.keys().asSequence().toSet())
        assertEquals("every_action", phone.calls.last().body!!.getString("ask"))
    }

    @Test fun theHelperUpdateIsItsOwnRoute() = runTest {
        val phone = FakePhone().on("POST", "/helper/update", 202, "{}")
        AgentPhoneApi(phone).updateHelper().value()
        assertEquals(listOf("POST /helper/update"), phone.routes())
    }

    @Test fun everyLineTheLanesSendMapsToOneTemplateAndOneAction() {
        val sent = mapOf(
            "This computer can't run a virtual phone." to AgentPhoneAction.CheckAgain,
            "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer." to AgentPhoneAction.CheckAgain,
            "The computer is low on memory. Close something there and try again." to AgentPhoneAction.CheckAgain,
            "Download stopped. Check the Hermes computer's internet." to AgentPhoneAction.Resume,
            "A download was damaged. Getting it again." to AgentPhoneAction.None,
            "The phone didn't start." to AgentPhoneAction.TryAgain,
            "Beacon needs an update to get the phone. Update Beacon, then try again." to AgentPhoneAction.Update,
            "Space is tight: about 21 GB free." to AgentPhoneAction.None,
        )
        for ((raw, action) in sent) {
            val line = agentPhoneLine(raw)!!
            assertTrue("$raw is a known line", line.template in AgentPhoneLineStrings)
            assertEquals(raw, action, line.action)
        }
        assertEquals(listOf("21"), agentPhoneLine("Space is tight: about 21 GB free.")!!.args)
    }

    @Test fun aTechnicalStartErrorIsTheDidntStartLineWithDetails() {
        val line = agentPhoneLine("The phone's process could not start outside Hermes' job.", AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain))!!
        assertEquals(AGENT_PHONE_DIDNT_START, line.template)
        assertEquals(AgentPhoneAction.TryAgain, line.action)
        assertTrue(line.hasDetails)
        assertEquals("The phone's process could not start outside Hermes' job.", line.raw)
    }

    @Test fun anUnknownCheckLineIsShownAsTheServerWroteIt() {
        val line = agentPhoneLine("A new check said something.")!!
        assertEquals("A new check said something.", line.template)
        assertEquals(AgentPhoneAction.None, line.action)
        assertNull(agentPhoneLine("   "))
    }
}
