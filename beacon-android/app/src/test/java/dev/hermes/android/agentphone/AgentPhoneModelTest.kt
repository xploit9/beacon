package dev.hermes.android.agentphone

import dev.hermes.android.data.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The journey's model and the page's controller (§3, §8, §10 "setup state machine"), driven by the fake plugin. */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPhoneModelTest {
    private fun status(body: String) = parseAgentPhoneStatus(JSONObject(body))
    private fun checks(body: String) = parseAgentPhoneChecks(JSONObject(body))

    // -- where the journey opens --------------------------------------------------------------------------------------------------

    @Test fun theJourneyOpensWhereTheServerSaysItStands() {
        assertEquals(AgentPhoneStage.Before, agentPhoneEntryStage(null))
        assertEquals(AgentPhoneStage.Before, agentPhoneEntryStage(status(Contract.NOT_SET_UP)))
        assertEquals(AgentPhoneStage.Download, agentPhoneEntryStage(status(Contract.DOWNLOADING)))
        assertEquals(AgentPhoneStage.Download, agentPhoneEntryStage(status(Contract.NOT_SET_UP.replace(""""state": "idle"""", """"state": "paused"""") )))
        assertEquals(AgentPhoneStage.FirstStart, agentPhoneEntryStage(status(Contract.OFF)))
        assertEquals(AgentPhoneStage.Ready, agentPhoneEntryStage(status(Contract.ON)))
    }

    @Test fun theFourStepsAreCheckTermsGetReadyAndSetUp() {
        assertEquals(listOf(1, 1, 2, 3, 3, 4, 4, 4), AgentPhoneStage.entries.map(::agentPhoneStepNumber))
    }

    // -- the checks -----------------------------------------------------------------------------------------------------------------

    @Test fun signInOffRefusesContinueWhateverTheOwnerDoes() {
        val result = checks(Contract.CHECKS_SIGN_IN_OFF)
        assertFalse(agentPhoneChecksPass(result, continued = false))
        assertFalse(agentPhoneChecksPass(result, continued = true))
    }

    @Test fun theDeveloperToolsRowNeedsItsOwnContinue() {
        val result = checks(Contract.CHECKS_WARN_ONLY)
        assertFalse(agentPhoneChecksPass(result, continued = false))
        assertTrue(agentPhoneChecksPass(result, continued = true))
        assertTrue(agentPhoneChecksPass(checks(Contract.CHECKS_READY), continued = false))
        assertFalse(agentPhoneChecksPass(null, continued = true))
    }

    @Test fun whatBlocksComesFirstThenNotesThenUnreadThenReady() {
        val rows = checks(Contract.CHECKS_SIGN_IN_OFF).rows.sortedBy { agentPhoneCheckOrder(it.status) }
        assertEquals(listOf("disk", "sign_in", "memory", "developer_tools", "platform", "virtualisation"), rows.map { it.id })
    }

    @Test fun everyCheckIdOfL1aHasPlainWords() {
        for (id in listOf("platform", "container", "virtualisation", "memory", "disk", "network", "ports", "sign_in", "developer_tools"))
            assertTrue(id, agentPhoneCheckNames[id].orEmpty().isNotBlank())
    }

    // -- time left ------------------------------------------------------------------------------------------------------------------

    private fun downloading(done: Long, total: Long? = 2_400_000_000L, state: AgentPhoneInstallState = AgentPhoneInstallState.Running, phase: AgentPhoneInstallPhase? = AgentPhoneInstallPhase.Download) =
        AgentPhoneInstall(state, phase, done, total)

    @Test fun timeLeftComesFromTheRateAfterAFewSeconds() {
        val left = AgentPhoneTimeLeft()
        assertNull(left.update(downloading(0), 0))
        assertNull(left.update(downloading(10_000_000), 1_000)) // under 3 s: nothing honest yet
        // 10 MB/s: 2.38 GB left at 4 s is about 4 min.
        val minutes = left.update(downloading(40_000_000), 4_000)
        assertEquals(4L, minutes)
    }

    @Test fun timeLeftForgetsWhenTheBytesGoBackAndSaysNothingWithoutATotal() {
        val left = AgentPhoneTimeLeft()
        left.update(downloading(0), 0); left.update(downloading(40_000_000), 4_000)
        assertNull("a damaged file fetched again starts over", left.update(downloading(1_000_000), 5_000))
        assertNull(AgentPhoneTimeLeft().also { it.update(downloading(0, total = null), 0) }.update(downloading(40_000_000, total = null), 4_000))
        assertNull(AgentPhoneTimeLeft().also { it.update(downloading(0, phase = AgentPhoneInstallPhase.Check), 0) }.update(downloading(40_000_000, phase = AgentPhoneInstallPhase.Check), 4_000))
        assertNull(AgentPhoneTimeLeft().update(downloading(40_000_000, state = AgentPhoneInstallState.Paused), 4_000))
    }

    @Test fun underAMinuteIsSaidAsSuch() {
        val left = AgentPhoneTimeLeft()
        left.update(downloading(2_300_000_000), 0)
        assertEquals(0L, left.update(downloading(2_350_000_000), 4_000))
    }

    // -- the checklist ---------------------------------------------------------------------------------------------------------------

    private fun AgentPhoneTaskRow?.state() = this?.let { Triple(it.done, it.byHand, it.canTick) }
    private fun List<AgentPhoneTaskRow>.of(task: AgentPhoneTask) = firstOrNull { it.task == task }

    @Test fun chromesStepShowsOnlyBelow135AndTicksItselfAt135() {
        assertEquals(Triple(false, false, false), agentPhoneChecklist(AgentPhoneFacts(chrome = 133), emptySet()).of(AgentPhoneTask.Chrome).state())
        assertNull("135 or later never asked", agentPhoneChecklist(AgentPhoneFacts(chrome = 135), emptySet()).of(AgentPhoneTask.Chrome))
        assertEquals("seen below 135, it stays and ticks", Triple(true, false, false), agentPhoneChecklist(AgentPhoneFacts(chrome = 136), emptySet(), chromeSeenBelow = true).of(AgentPhoneTask.Chrome).state())
        assertEquals("unread: the owner may tick it", Triple(false, false, true), agentPhoneChecklist(AgentPhoneFacts(), emptySet()).of(AgentPhoneTask.Chrome).state())
    }

    @Test fun aReadValueAlwaysWinsOverAHandTick() {
        val ticks = setOf("google", "chrome_autofill", "chrome", "shops")
        val rows = agentPhoneChecklist(AgentPhoneFacts(google = false, chrome = 120, chromeAutofill = true, logins = 2), ticks)
        assertEquals(Triple(false, false, false), rows.of(AgentPhoneTask.Google).state())
        assertEquals(Triple(false, false, false), rows.of(AgentPhoneTask.Chrome).state())
        assertEquals(Triple(true, false, false), rows.of(AgentPhoneTask.ChromeAutofill).state())
        assertEquals(Triple(true, false, false), rows.of(AgentPhoneTask.Shops).state())
    }

    @Test fun handTicksCountOnlyWhereThePhoneCantRead() {
        val rows = agentPhoneChecklist(null, setOf("google", "chrome", "chrome_autofill", "shops"))
        assertTrue(rows.all { it.done && it.byHand && it.canTick })
        assertTrue(agentPhoneChecklistDone(rows))
        assertFalse(agentPhoneChecklistDone(agentPhoneChecklist(null, emptySet())))
    }

    @Test fun shoppingTakesATickWhileNothingWasSavedFromThePhone() {
        // An app that keeps you signed in saves nothing to the vault, so a count of 0 is not a "no".
        assertEquals(Triple(true, true, true), agentPhoneChecklist(AgentPhoneFacts(logins = 0), setOf("shops")).of(AgentPhoneTask.Shops).state())
    }

    // -- the page --------------------------------------------------------------------------------------------------------------------

    @Test fun theHeroNeverClaimsUsingItWithoutHermesSayingSo() {
        assertEquals(AgentPhoneHero.On, agentPhoneHero(status(Contract.ON), 0))
        assertEquals(AgentPhoneHero.InUse, agentPhoneHero(status(Contract.ON_FULL), 0))
        assertEquals(AgentPhoneHero.On, agentPhoneHero(status(Contract.ON_FULL.replace(""""in_use": true""", """"in_use": null""")), 0))
    }

    @Test fun theHeroPutsWhatMattersFirst() {
        assertEquals(AgentPhoneHero.NotSetUp, agentPhoneHero(status(Contract.NOT_SET_UP), 0))
        assertEquals(AgentPhoneHero.Installing, agentPhoneHero(status(Contract.DOWNLOADING), 0))
        assertEquals(AgentPhoneHero.OtherProfile, agentPhoneHero(status(Contract.OTHER_PROFILE), 2))
        assertEquals(AgentPhoneHero.NeedsYou, agentPhoneHero(status(Contract.OFF), 1))
        assertEquals(AgentPhoneHero.Starting, agentPhoneHero(status(Contract.STARTING), 0))
        assertEquals(AgentPhoneHero.Off, agentPhoneHero(status(Contract.OFF), 0))
    }

    @Test fun theSettingsRowStateFollowsTheCachedStatus() {
        assertEquals("setup", agentPhoneHubState(JSONObject(Contract.NOT_SET_UP)))
        assertEquals("on", agentPhoneHubState(JSONObject(Contract.ON)))
        assertEquals("off", agentPhoneHubState(JSONObject(Contract.OFF)))
        assertEquals("starting", agentPhoneHubState(JSONObject(Contract.STARTING)))
        assertNull(agentPhoneHubState(null))
        assertNull(agentPhoneHubState(JSONObject(Contract.OTHER_PROFILE)))
        assertNull(agentPhoneHubState(JSONObject().put("_error", true)))
    }

    @Test fun aStoppedDownloadOffersExactlyOneWayOn() {
        assertEquals(AgentPhoneAction.TryAgain, agentPhoneFailedAction(AgentPhoneAction.Resume, retry = false))
        assertEquals(AgentPhoneAction.TryAgain, agentPhoneFailedAction(null, retry = false))
        assertEquals(AgentPhoneAction.CheckAgain, agentPhoneFailedAction(AgentPhoneAction.CheckAgain, retry = false))
        assertEquals(AgentPhoneAction.TryAgain, agentPhoneFailedAction(AgentPhoneAction.CheckAgain, retry = true))
        assertEquals(AgentPhoneAction.Update, agentPhoneFailedAction(AgentPhoneAction.Update, retry = false))
        assertEquals(AgentPhoneAction.StartAgain, agentPhoneFailedAction(AgentPhoneAction.StartAgain, retry = false))
    }

    // -- the controller --------------------------------------------------------------------------------------------------------------

    private fun TestScope.controller(phone: FakePhone, cached: String? = null, clock: () -> Long = { testScheduler.currentTime }) =
        AgentPhoneController(AgentPhoneApi(phone), this, null, cached?.let(::JSONObject), now = clock)

    @Test fun thePageOpensFromTheCacheThenReadsHermes() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON).on("GET", "/policy", 200, Contract.POLICY).on("GET", "/activity?limit=50", 200, Contract.ACTIVITY)
            .on("GET", "/approvals", 200, Contract.APPROVALS)
        val cached = mutableListOf<JSONObject>()
        val owner = AgentPhoneController(AgentPhoneApi(phone), this, null, JSONObject(Contract.OFF), onStatus = { cached += it })
        assertEquals(AgentPhonePower.Off, owner.state.value.status?.power)
        assertFalse(owner.state.value.loaded)
        owner.refresh()
        assertEquals(AgentPhonePower.On, owner.state.value.status?.power)
        assertTrue(owner.state.value.loaded)
        assertEquals(1, owner.state.value.needsYou.size)
        assertEquals(2, owner.state.value.activity?.size)
        assertEquals(2, cached.size)  // the status at once, then the copy with the page's other answers (cache review, fu2)
        assertTrue(cached.last().has(PAGE_ACTIVITY) && cached.last().has(PAGE_POLICY) && cached.last().has(PAGE_NEEDS_YOU))
    }

    @Test fun theWholeJourneyFromChecksToReady() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.NOT_SET_UP)
            .on("POST", "/checks", 200, Contract.CHECKS_WARN_ONLY)
            .on("GET", "/terms", 200, Contract.TERMS)
            .on("POST", "/install", 202, Contract.INSTALL_STARTED)
            .on("GET", "/install", 200, """{"state": "running", "phase": "download", "done_bytes": 0, "total_bytes": 2400000000}""")
            .on("GET", "/install", 200, Contract.INSTALL_DONE)
            .on("POST", "/start", 202, """{"phone": "starting"}""")
        val owner = controller(phone)
        owner.refresh()
        owner.openFlow()
        assertEquals(AgentPhoneStage.Before, owner.state.value.setup.stage)
        owner.confirm(true)
        owner.check(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Checks, owner.state.value.setup.stage)
        assertFalse(agentPhoneChecksPass(owner.state.value.setup.checks, owner.state.value.setup.continued))
        owner.continueWarning()
        assertTrue(agentPhoneChecksPass(owner.state.value.setup.checks, owner.state.value.setup.continued))
        owner.toTerms(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Terms, owner.state.value.setup.stage)
        owner.accept(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Download, owner.state.value.setup.stage)
        val install = phone.calls.single { it.route == "/install" && it.method == "POST" }.body!!
        assertEquals(true, install.getBoolean("continue"))
        owner.poll(); owner.poll(); advanceUntilIdle()
        // Created, not started: the app starts it and waits for it.
        assertEquals(AgentPhoneStage.FirstStart, owner.state.value.setup.stage)
        assertTrue(phone.routes().contains("POST /start"))
        phone.now("GET", "", 200, Contract.STARTING).on("GET", "", 200, Contract.ON)
        owner.poll(); owner.poll(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Ready, owner.state.value.setup.stage)
    }

    @Test fun backStepsThroughTheJourneyThenCloses() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.NOT_SET_UP).on("POST", "/checks", 200, Contract.CHECKS_READY).on("GET", "/terms", 200, Contract.TERMS)
        val owner = controller(phone)
        owner.refresh(); owner.openFlow(); owner.confirm(true); owner.check(); advanceUntilIdle(); owner.toTerms(); advanceUntilIdle()
        owner.back(); assertEquals(AgentPhoneStage.Checks, owner.state.value.setup.stage)
        owner.back(); assertEquals(AgentPhoneStage.Before, owner.state.value.setup.stage)
        assertTrue(owner.state.value.setup.confirmed)
        owner.back(); assertFalse(owner.state.value.flowOpen)
    }

    @Test fun aFirstStartThatFailsShowsTheLineWithDetails() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.OFF).on("POST", "/start", 202, """{"phone": "starting"}""")
        val owner = controller(phone)
        owner.refresh()
        owner.openFlow(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.FirstStart, owner.state.value.setup.stage)
        phone.now("GET", "", 200, Contract.STARTING).on("GET", "", 200, Contract.START_FAILED)
        owner.poll(); owner.poll(); advanceUntilIdle()
        val line = owner.state.value.setup.startLine!!
        assertEquals(AGENT_PHONE_DIDNT_START, line.template)
        assertTrue(line.hasDetails)
        assertEquals(AgentPhoneStage.FirstStart, owner.state.value.setup.stage)
    }

    @Test fun signInOffAtStartIsOneLineWithHow() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.OFF).on("POST", "/start", 409, """{"phone": "off", "line": "Turn on Hermes sign-in first."}""")
        val owner = controller(phone)
        owner.refresh()
        owner.start(); advanceUntilIdle()
        assertEquals(AgentPhoneAction.How, owner.state.value.actionLine?.action)
    }

    @Test fun oldChecksAtAcceptAreDoneAgain() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.NOT_SET_UP).on("POST", "/checks", 200, Contract.CHECKS_READY).on("GET", "/terms", 200, Contract.TERMS)
            .on("POST", "/install", 409, """{"detail": "Check this computer first."}""")
        val owner = controller(phone)
        owner.refresh(); owner.openFlow(); owner.confirm(true); owner.check(); advanceUntilIdle()
        owner.toTerms(); advanceUntilIdle()
        owner.accept(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Checks, owner.state.value.setup.stage)
        assertEquals(2, phone.routes().count { it == "POST /checks" })
        assertEquals("the reason stays above the fresh checks", AgentPhoneAction.CheckAgain, owner.state.value.setup.checksLine?.action)
    }

    @Test fun pauseAndResumeKeepTheJourneyOnItsDownload() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.DOWNLOADING).on("GET", "/install", 200, """{"state": "running", "phase": "download", "done_bytes": 1200000000, "total_bytes": 2400000000}""")
            .on("POST", "/install/pause", 200, """{"state": "paused", "phase": "download", "done_bytes": 1200000000, "total_bytes": 2400000000}""")
            .on("POST", "/install/resume", 202, """{"state": "running", "phase": "download", "done_bytes": 1200000000, "total_bytes": 2400000000}""")
        val owner = controller(phone)
        owner.refresh(); owner.openFlow(); advanceUntilIdle()
        assertEquals(AgentPhoneStage.Download, owner.state.value.setup.stage)
        owner.pause(); advanceUntilIdle()
        assertEquals(AgentPhoneInstallState.Paused, owner.state.value.setup.install.state)
        owner.resume(); advanceUntilIdle()
        assertEquals(AgentPhoneInstallState.Running, owner.state.value.setup.install.state)
        assertEquals(AgentPhoneStage.Download, owner.state.value.setup.stage)
    }

    @Test fun aRefusedPolicyChangePutsTheSwitchBack() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON).on("GET", "/policy", 200, Contract.POLICY).on("GET", "/activity?limit=50", 200, Contract.ACTIVITY)
            .on("GET", "/approvals", 200, Contract.APPROVALS).on("PUT", "/policy", 503, """{"detail": "Busy, try again."}""")
        val owner = controller(phone)
        owner.refresh()
        owner.setEnabled(false)
        assertEquals(false, owner.state.value.policy?.enabled)
        advanceUntilIdle()
        assertEquals(true, owner.state.value.policy?.enabled)
        assertEquals(AGENT_PHONE_BUSY, owner.state.value.actionLine?.template)
    }

    @Test fun openingAnOffPhoneStartsItThenOpensTheView() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.OFF).on("POST", "/start", 202, """{"phone": "starting"}""")
        val owner = controller(phone)
        owner.refresh()
        owner.open(); advanceUntilIdle()
        assertEquals(0, owner.state.value.openRequest)
        phone.now("GET", "", 200, Contract.ON)
        owner.poll(); advanceUntilIdle()
        assertEquals(1, owner.state.value.openRequest)
    }

    @Test fun theHelperUpdateStaysBusyUntilTheHelperReadsReady() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON_FULL).on("POST", "/helper/update", 202, "{}")
        val owner = controller(phone)
        owner.refresh(all = false)
        owner.updateHelper(); advanceUntilIdle()
        assertEquals(AgentPhoneBusy.Helper, owner.state.value.busy)
        phone.now("GET", "", 200, Contract.ON_FULL.replace(""""helper": "update"""", """"helper": "ready""""))
        owner.refresh(all = false)
        assertNull(owner.state.value.busy)
    }

    @Test fun aHelperUpdateThatFailsAfterItsAcceptSaysSoWithTryAgain() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON_FULL).on("POST", "/helper/update", 202, "{}")
        val owner = controller(phone)
        owner.refresh(all = false)
        owner.updateHelper(); advanceUntilIdle()
        phone.now("GET", "", 200, Contract.ON_FULL.replace(""""error": null""", """"error": "The helper didn't install. Try again.""""))
        owner.refresh(all = false)
        assertNull(owner.state.value.busy)
        assertEquals(AGENT_PHONE_HELPER_FAILED, owner.state.value.actionLine?.template)
        assertEquals(AgentPhoneAction.TryAgain, owner.state.value.actionLine?.action)
    }

    @Test fun aHelperThatNeverReadsReadyStopsTheWait() = runTest {
        var clock = 0L
        val phone = FakePhone().on("GET", "", 200, Contract.ON_FULL).on("POST", "/helper/update", 202, "{}")
        val owner = controller(phone, clock = { clock })
        owner.refresh(all = false)
        owner.updateHelper(); advanceUntilIdle()
        owner.refresh(all = false)
        assertEquals(AgentPhoneBusy.Helper, owner.state.value.busy)
        clock += 181_000
        owner.refresh(all = false)
        assertNull(owner.state.value.busy)
        assertEquals(AGENT_PHONE_HELPER_FAILED, owner.state.value.actionLine?.template)
    }

    @Test fun removeClearsTheJourneyAndReportsTheSpaceFreed() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON).on("POST", "/remove", 200, """{"removed": true, "freed_bytes": 9800000000}""")
        val owner = controller(phone)
        owner.refresh(all = false)
        owner.tick(AgentPhoneTask.Shops, true)
        phone.now("GET", "", 200, Contract.NOT_SET_UP)
        var freed: Long? = null
        owner.remove(deleteLogins = false) { freed = it }; advanceUntilIdle()
        assertEquals(9_800_000_000L, freed)
        assertTrue(owner.state.value.setup.ticks.isEmpty())
        assertFalse(owner.state.value.status!!.installed)
    }

    @Test fun eachCheckResultHasItsOwnTitleAndLine() {
        val fail = AgentPhoneChecks(false, false, listOf(AgentPhoneCheckRow("disk", AgentPhoneCheckStatus.Fail)))
        val warn = AgentPhoneChecks(true, true, listOf(AgentPhoneCheckRow("developer_tools", AgentPhoneCheckStatus.Warn, needsContinue = true)))
        val words = listOf(agentPhoneChecksWords(true, null, false), agentPhoneChecksWords(false, null, false), agentPhoneChecksWords(false, fail, false),
            agentPhoneChecksWords(false, warn, false), agentPhoneChecksWords(false, warn, true))
        assertEquals(listOf("Checking this computer…", "Check this computer", "Not ready yet", "Almost ready", "This computer is ready"), words.map { it.first })
        // P2-2: no result keeps the checking line, and each result says its own.
        val results = words.drop(2).map { it.second }
        assertEquals(results.distinct(), results)
        assertTrue(results.none { it.isBlank() || it == words[0].second })
        assertTrue(words.none { "0.5 GB" in it.second })
        assertTrue(words.flatMap { it.toList() }.filter(String::isNotBlank).all { it in AgentPhoneSetupStrings })
    }

    @Test fun eachHeroStateOffersOneNameForEachAction() {
        // P3-3: off starts with Turn on (Open is for a phone that is on); a failed start's card is its only Try again.
        assertEquals(listOf(AgentPhoneHeroAction.TurnOn), agentPhoneHeroActions(AgentPhoneHero.Off, cardStarts = false))
        assertEquals(emptyList<AgentPhoneHeroAction>(), agentPhoneHeroActions(AgentPhoneHero.Off, cardStarts = true))
        for (hero in listOf(AgentPhoneHero.On, AgentPhoneHero.InUse))
            assertEquals(listOf(AgentPhoneHeroAction.Open, AgentPhoneHeroAction.TurnOff), agentPhoneHeroActions(hero, cardStarts = false))
        // Needs you: each notice's own Open the phone (which also answers it) is the one; starting and stopping wait.
        for (hero in listOf(AgentPhoneHero.NeedsYou, AgentPhoneHero.Starting, AgentPhoneHero.Stopping))
            assertEquals(emptyList<AgentPhoneHeroAction>(), agentPhoneHeroActions(hero, cardStarts = false))
        // A phone that needs the owner while it is on can still be turned off from the hero (and only that: the notice opens it).
        assertEquals(listOf(AgentPhoneHeroAction.TurnOff), agentPhoneHeroActions(AgentPhoneHero.NeedsYou, cardStarts = false, on = true))
    }
}
