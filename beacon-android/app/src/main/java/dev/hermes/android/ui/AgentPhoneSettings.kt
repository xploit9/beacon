@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui

import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import dev.hermes.android.phone.PhoneDayName
import dev.hermes.android.phone.isolateBidi
import dev.hermes.android.phone.phoneDayName
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.agentphone.logRowText
import dev.hermes.android.agentphone.serverLine
import dev.hermes.android.agentphone.shownLine
import dev.hermes.android.data.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

// Settings › {name}'s phone (EMU-DESIGN rev 4 §8 "Phone page"): the intro card while there is no phone, the journey that sets it up
// (AgentPhoneSetup.kt), and then one page for everything about it. The controller below holds what the page shows; every value comes
// from the plugin (L2's routes), cached per account and profile so the page opens already filled in.

/** The live view (L5's screen), opened by its class (joined 17 Nov); the page reads the status again when it returns. */
internal object AgentPhoneLiveView {
    fun intent(context: Context): Intent = Intent(context, dev.hermes.android.agentphone.AgentPhoneActivity::class.java)
    fun available(context: Context): Boolean = intent(context).resolveActivity(context.packageManager) != null
}

/** The Settings route of the page; the row's words carry the assistant's name, the route never does (it is kept in navigation). */
internal const val AGENT_PHONE_ROUTE = "Agent phone"

/** Where the page keeps its last status, per profile (the account is the cache's own scope). */
internal fun agentPhoneCacheKey(profile: String) = "agent-phone:$profile"

/** The page's own reads kept in its cached copy beside the status, as the answers came (cache review, Root msg_8b7aac5cc13b):
 *  the page opens with its last activity, policy and Needs-you rows, then reads them again. */
internal const val PAGE_POLICY = "page_policy"
internal const val PAGE_ACTIVITY = "page_activity"
internal const val PAGE_NEEDS_YOU = "page_needs_you"

/** The status answer with the page's other answers beside it: one copy per account and profile. */
internal fun agentPhonePageCopy(status: JSONObject, kept: JSONObject?, policy: JSONObject? = null, activity: JSONObject? = null,
                                needsYou: JSONObject? = null): JSONObject {
    // Task state must be read live before offering to cancel that task.
    val copy = JSONObject(status.toString()).apply { remove("driver_task") }
    for ((key, value) in listOf(PAGE_POLICY to policy, PAGE_ACTIVITY to activity, PAGE_NEEDS_YOU to needsYou)) {
        (value ?: kept?.optJSONObject(key))?.let { copy.put(key, it) }
    }
    return copy
}

/** The whole page read once, for the prefetch: the status, then with a phone of this profile its other three reads. */
internal suspend fun agentPhonePageRead(api: AgentPhoneApi): JSONObject? = coroutineScope {
    val (status, body) = (api.status() as? AgentPhoneResult.Done)?.value ?: return@coroutineScope null
    if (!status.installed || !status.yours) return@coroutineScope agentPhonePageCopy(body, null)
    val policy = async { (api.policyRead() as? AgentPhoneResult.Done)?.value?.second }
    val activity = async { (api.activityRead() as? AgentPhoneResult.Done)?.value?.second }
    val notices = async { (api.needsYouRead() as? AgentPhoneResult.Done)?.value?.second }
    agentPhonePageCopy(body, null, policy.await(), activity.await(), notices.await())
}

// -- the page's own model ------------------------------------------------------------------------------------------------------

/** What the hero says, in order of what matters most to the owner. */
internal enum class AgentPhoneHero { NotSetUp, Installing, OtherProfile, NeedsYou, Starting, Stopping, Off, InUse, On }

internal fun agentPhoneHero(status: AgentPhoneStatus, needsYou: Int): AgentPhoneHero = when {
    !status.installed && (status.install.working || status.install.resumable) -> AgentPhoneHero.Installing
    !status.installed -> AgentPhoneHero.NotSetUp
    !status.yours -> AgentPhoneHero.OtherProfile
    needsYou > 0 -> AgentPhoneHero.NeedsYou
    status.power == AgentPhonePower.Starting -> AgentPhoneHero.Starting
    status.power == AgentPhonePower.Stopping -> AgentPhoneHero.Stopping
    status.power == AgentPhonePower.Off -> AgentPhoneHero.Off
    // Only Hermes can say the agent is using it; without that answer the page never claims it (Root, msg_ed1d395edfa5).
    status.inUse == true -> AgentPhoneHero.InUse
    else -> AgentPhoneHero.On
}

internal fun agentPhoneHeroTitle(hero: AgentPhoneHero): String = when (hero) {
    AgentPhoneHero.NotSetUp -> "Give {name} its own phone"
    AgentPhoneHero.Installing -> "Getting the phone ready"
    AgentPhoneHero.OtherProfile -> "Set up for another profile"
    AgentPhoneHero.NeedsYou -> "Needs you"
    AgentPhoneHero.Starting -> "Starting…"
    AgentPhoneHero.Stopping -> "Turning off…"
    AgentPhoneHero.Off -> "Off"
    AgentPhoneHero.InUse -> "{name} is using it"
    AgentPhoneHero.On -> "On"
}

internal fun agentPhoneHeroStatus(hero: AgentPhoneHero): SettingsStatus = when (hero) {
    AgentPhoneHero.NeedsYou, AgentPhoneHero.OtherProfile -> SettingsStatus.Attention
    AgentPhoneHero.Starting, AgentPhoneHero.Stopping, AgentPhoneHero.Installing, AgentPhoneHero.InUse -> SettingsStatus.Working
    AgentPhoneHero.Off, AgentPhoneHero.NotSetUp -> SettingsStatus.Off
    AgentPhoneHero.On -> SettingsStatus.Ok
}

/** The Settings row's state from the cached status: setup, starting, on or off; null while nothing is known. */
internal fun agentPhoneHubState(cached: JSONObject?): String? {
    if (cached == null || cached.has("_error") || cached.optBoolean("_loading")) return null
    val status = parseAgentPhoneStatus(cached)
    return when {
        !status.installed -> "setup"
        !status.yours -> null
        status.power == AgentPhonePower.Starting -> "starting"
        status.power == AgentPhonePower.On -> "on"
        else -> "off"
    }
}

/** What the hero offers in each state (AGENTUX-121 P3-3): one name for one action, and none while the phone is busy starting or stopping. */
internal enum class AgentPhoneHeroAction { TurnOn, Open, TurnOff, ShowProgress, ChooseProfile }

internal fun agentPhoneHeroActions(hero: AgentPhoneHero, cardStarts: Boolean, on: Boolean = false): List<AgentPhoneHeroAction> = when (hero) {
    AgentPhoneHero.Installing -> listOf(AgentPhoneHeroAction.ShowProgress)
    AgentPhoneHero.OtherProfile -> listOf(AgentPhoneHeroAction.ChooseProfile)
    // A failed start's card says Try again; the hero doesn't offer the same start under another name.
    AgentPhoneHero.Off -> if (cardStarts) emptyList() else listOf(AgentPhoneHeroAction.TurnOn)
    AgentPhoneHero.On, AgentPhoneHero.InUse -> listOf(AgentPhoneHeroAction.Open, AgentPhoneHeroAction.TurnOff)
    // Each Needs-you notice has its own Open the phone, which also answers it; the phone can still be turned off from here.
    AgentPhoneHero.NeedsYou -> if (on) listOf(AgentPhoneHeroAction.TurnOff) else emptyList()
    // Starting and Stopping wait.
    AgentPhoneHero.Starting, AgentPhoneHero.Stopping, AgentPhoneHero.NotSetUp -> emptyList()
}

/** Fixed page words that reach uiText through a variable, for the translation checks. */
internal val AgentPhonePageStrings: List<String> by lazy {
    AgentPhoneHero.entries.map(::agentPhoneHeroTitle) + AgentPhoneDriverState.entries.map { it.label } + phoneDriverFailureWords +
        listOf("Payments and sends", "Every action")
}

// The fixed public failure lines from the plugin. Task or screen text is never used as a status label.
private val phoneDriverFailureWords = setOf(
    "Configure a working vision model for this Hermes profile.",
    "The owner declined the action.",
    "Approval expired.",
    "The phone screen changed.",
    "Control of the phone changed.",
    "The phone screen is protected.",
    "The phone task timed out.",
    "The phone task could not be verified.",
    "The phone task was interrupted.",
    "The phone task could not start.",
)

/** One action is running; the page shows it on its own button. */
internal enum class AgentPhoneBusy { Start, Stop, Policy, Remove, Helper, Notice }

internal data class AgentPhoneUi(
    val status: AgentPhoneStatus? = null,
    /** A server answer arrived in this visit (the status may be the cached one until then). */
    val loaded: Boolean = false,
    val loadLine: AgentPhoneLine? = null,
    val policy: AgentPhonePolicy? = null,
    val activity: List<AgentPhoneActivity>? = null,
    val needsYou: List<AgentPhoneNeedsYou> = emptyList(),
    val busy: AgentPhoneBusy? = null,
    /** The last button's refusal, shown once on the page with its one action. */
    val actionLine: AgentPhoneLine? = null,
    val flowOpen: Boolean = false,
    val setup: AgentPhoneSetupState = AgentPhoneSetupState(),
    /** Bumped when the live view should open; the page launches it. */
    val openRequest: Int = 0,
    val driverTaskBusy: Boolean = false,
    val driverTaskLine: AgentPhoneLine? = null,
) {
    val driverTaskRunning: Boolean get() = status?.driverTask?.state == AgentPhoneDriverState.Running
    val canGiveTask: Boolean get() = loaded && loadLine == null && busy == null && !driverTaskBusy && policy?.enabled == true &&
        status?.let { it.hasOwnedDriver && !it.install.working && it.lease?.human != true &&
            it.power in setOf(AgentPhonePower.Off, AgentPhonePower.On) && !driverTaskRunning } == true
}

private val AgentPhoneStatus.hasOwnedDriver: Boolean get() = supportsPhoneDriver && installed && yours

/** How long a helper update may take (L2 installs the file, sets the autofill service and reads the phone again) before the page gives up. */
private const val HELPER_WAIT_MS = 180_000L

/**
 * Holds what the phone page and the journey show, and talks to the plugin. Every change goes through [state]. The phone keeps only the
 * owner's confirmation and hand ticks; where the journey stands is read from the server each time.
 */
internal fun savedAgentPhone(cached: JSONObject?): AgentPhoneUi = AgentPhoneUi(
    status = cached?.takeIf { !it.has("_error") }?.let(::parseAgentPhoneStatus),
    policy = cached?.optJSONObject(PAGE_POLICY)?.let { runCatching { parseAgentPhonePolicy(it) }.getOrNull() },
    activity = cached?.optJSONObject(PAGE_ACTIVITY)?.let { runCatching { parseAgentPhoneActivity(it) }.getOrNull() },
    needsYou = cached?.optJSONObject(PAGE_NEEDS_YOU)?.let { runCatching { parseAgentPhoneNeedsYou(it) }.getOrNull() }.orEmpty(),
)

internal class AgentPhoneController(
    private val api: AgentPhoneApi,
    private val scope: CoroutineScope,
    private val ticks: AgentPhoneTicks?,
    cached: JSONObject?,
    private val onStatus: (JSONObject) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(savedAgentPhone(cached).copy(setup = AgentPhoneSetupState(ticks = ticks?.read().orEmpty())))
    /** The page's copy as last kept: the status answer and the other three (cache review, fu2). */
    private var kept: JSONObject? = cached?.takeIf { !it.has("_error") }
    val state: StateFlow<AgentPhoneUi> = _state
    private val timeLeft = AgentPhoneTimeLeft()
    private var openWhenOn = false
    private var startWatch = false
    private var lastFull = 0L
    private var driverTaskVersion = 0L

    private fun update(change: (AgentPhoneUi) -> AgentPhoneUi) = _state.update(change)
    private fun setup(change: (AgentPhoneSetupState) -> AgentPhoneSetupState) = _state.update { it.copy(setup = change(it.setup)) }

    // -- reading ---------------------------------------------------------------------------------------------------------------

    /** The status, and with a phone also its policy, activity and Needs-you notices. */
    suspend fun refresh(all: Boolean = true) {
        val version = ++driverTaskVersion
        when (val read = api.status()) {
            is AgentPhoneResult.Done -> {
                val (status, body) = read.value
                keep(agentPhonePageCopy(body, kept))
                // An overlapping poll can have sampled the task before the last start or cancel receipt.
                val latest = if (version == driverTaskVersion && !_state.value.driverTaskBusy) status
                    else status.copy(driverTask = _state.value.status?.driverTask?.takeIf { status.hasOwnedDriver })
                applyStatus(latest)
                if (all && status.installed && status.yours) readMore(status)
            }
            is AgentPhoneResult.Refused -> if (version == driverTaskVersion) update { it.copy(loaded = true, loadLine = read.line) }
        }
    }

    /** The policy, the activity and the Needs-you notices, read together. */
    private suspend fun readMore(status: AgentPhoneStatus) = coroutineScope {
        lastFull = now()
        val policy = async { api.policyRead() }
        val activity = async { api.activityRead() }
        val notices = async { api.needsYouRead() }
        val (p, a, n) = Triple(policy.await().valueOrNull(), activity.await().valueOrNull(), notices.await().valueOrNull())
        update { ui -> ui.copy(policy = p?.first ?: ui.policy, activity = a?.first ?: ui.activity, needsYou = n?.first ?: ui.needsYou) }
        kept?.let { keep(agentPhonePageCopy(it, it, p?.second, a?.second, n?.second)) }
        if (status.power == AgentPhonePower.On && openWhenOn) { openWhenOn = false; update { it.copy(openRequest = it.openRequest + 1) } }
    }

    private fun keep(copy: JSONObject) {
        kept = copy
        onStatus(copy)
    }

    private fun applyStatus(status: AgentPhoneStatus) {
        val chromeBelow = status.facts?.chrome?.let { it < AGENT_PHONE_CHROME_AUTOFILL } == true
        update { ui -> ui.copy(status = status, loaded = true, loadLine = null, setup = ui.setup.copy(status = status, chromeSeenBelow = ui.setup.chromeSeenBelow || chromeBelow)) }
        // A start this page asked for has ended: on (open the view if asked) or off with the reason.
        if (startWatch && status.power != AgentPhonePower.Starting) {
            startWatch = false
            if (status.power == AgentPhonePower.On) {
                setup { if (it.stage == AgentPhoneStage.FirstStart) it.copy(stage = AgentPhoneStage.Ready, startLine = null) else it }
                if (openWhenOn) { openWhenOn = false; update { it.copy(openRequest = it.openRequest + 1) } }
            } else {
                openWhenOn = false
                val line = agentPhoneLine(status.error, AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain), status.errorCode, status.errorArgs)
                    ?: AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)
                setup { it.copy(startLine = line, opening = false) }
                update { it.copy(actionLine = if (it.flowOpen) it.actionLine else line) }
            }
            setup { it.copy(opening = false) }
        }
        if (_state.value.busy == AgentPhoneBusy.Helper) {
            // A failed update answers 202 and then leaves its line in the status's error (CONTRACT.md); a helper that never reads
            // ready stops the wait after a while. Either way the row says so once, with Try again, instead of spinning.
            val fresh = status.error?.takeIf { it != helperErrorBefore }
            if (status.helper == AgentPhoneHelper.Ready) update { it.copy(busy = null) }
            else if (fresh != null || now() - helperSince > HELPER_WAIT_MS) {
                val fallback = AgentPhoneLine(AGENT_PHONE_HELPER_FAILED, action = AgentPhoneAction.TryAgain)
                update { it.copy(busy = null, actionLine = agentPhoneLine(fresh, fallback, status.errorCode, status.errorArgs) ?: fallback) }
            }
        }
    }

    /** The status's error when the helper update was asked for, and when: a new error, or no ready helper in time, ends the wait. */
    private var helperErrorBefore: String? = null
    private var helperSince = 0L

    /** How long the page waits before reading again: fast while something changes, slow at rest. */
    fun pollMillis(): Long {
        val ui = _state.value
        val status = ui.status
        return when {
            ui.flowOpen && ui.setup.stage == AgentPhoneStage.Download -> 1_500
            startWatch || status?.power == AgentPhonePower.Starting || status?.power == AgentPhonePower.Stopping -> 2_000
            ui.busy == AgentPhoneBusy.Helper -> 3_000
            status?.install?.working == true -> 3_000
            ui.driverTaskRunning -> 2_000
            else -> 15_000
        }
    }

    suspend fun awaitPoll() {
        val interval = pollMillis()
        if (_state.value.driverTaskRunning) delay(interval)
        else withTimeoutOrNull(interval) { state.first { it.driverTaskRunning } }
    }

    /** One poll: the install while the journey is on its download, else the status (with the rest at most once a minute). */
    suspend fun poll() {
        val ui = _state.value
        if (ui.flowOpen && ui.setup.stage == AgentPhoneStage.Download) readInstall()
        else refresh(all = ui.status?.installed == true && !startWatch && now() - lastFull >= 60_000)
    }

    /** Reads again now (Check again on the page). */
    fun refreshNow() { scope.launch { refresh() } }

    private suspend fun readInstall() {
        when (val read = api.installProgress()) {
            is AgentPhoneResult.Done -> installed(read.value)
            is AgentPhoneResult.Refused -> setup { it.copy(installLine = read.line) }
        }
    }

    private suspend fun installed(install: AgentPhoneInstall) {
        val minutes = timeLeft.update(install, now())
        setup { it.copy(install = install, minutesLeft = minutes, installLine = null) }
        if (install.state == AgentPhoneInstallState.Done) {
            // Created, not started: the first start is the app's own call (§3 step 6).
            setup { it.copy(stage = AgentPhoneStage.FirstStart, startLine = null) }
            refresh(all = false)
            start()
        }
    }

    // -- the journey -------------------------------------------------------------------------------------------------------------

    fun openFlow() {
        val status = _state.value.status
        val stage = agentPhoneEntryStage(status)
        update { it.copy(flowOpen = true, actionLine = null, setup = it.setup.copy(stage = stage, install = status?.install ?: it.setup.install, startLine = null, openLine = null)) }
        when (stage) {
            AgentPhoneStage.Download -> scope.launch { readInstall() }
            AgentPhoneStage.FirstStart -> if (status?.power != AgentPhonePower.Starting) start() else startWatch = true
            else -> Unit
        }
    }

    fun closeFlow() { update { it.copy(flowOpen = false) }; scope.launch { refresh() } }

    /** System Back in the journey: one page back where there is one to go to (terms → checks → before you start), else close. */
    fun back() {
        val setup = _state.value.setup
        when {
            setup.accepting || setup.installBusy -> Unit
            setup.stage == AgentPhoneStage.Terms -> setup { it.copy(stage = AgentPhoneStage.Checks) }
            setup.stage == AgentPhoneStage.Checks -> setup { it.copy(stage = AgentPhoneStage.Before) }
            setup.stage == AgentPhoneStage.Checklist -> setup { it.copy(stage = AgentPhoneStage.Ready) }
            else -> closeFlow()
        }
    }

    fun confirm(value: Boolean) = setup { it.copy(confirmed = value) }

    /** Runs the host checks; [because] is why they run again (the server found them too old), kept above the fresh rows. */
    fun check(because: AgentPhoneLine? = null) {
        if (_state.value.setup.checking) return
        setup { it.copy(stage = AgentPhoneStage.Checks, checking = true, checks = null, continued = false, checksLine = because) }
        scope.launch {
            val read = api.checks()
            setup { it.copy(checking = false, checks = read.valueOrNull(), checksLine = (read as? AgentPhoneResult.Refused)?.line ?: because) }
        }
    }

    fun continueWarning() = setup { it.copy(continued = true) }

    fun toTerms() {
        setup { it.copy(stage = AgentPhoneStage.Terms) }
        if (_state.value.setup.terms == null) loadTerms()
    }

    fun loadTerms() {
        if (_state.value.setup.termsLoading) return
        setup { it.copy(termsLoading = true, termsLine = null) }
        scope.launch {
            val read = api.terms()
            setup { it.copy(termsLoading = false, terms = read.valueOrNull() ?: it.terms, termsLine = (read as? AgentPhoneResult.Refused)?.line) }
        }
    }

    fun accept() {
        val ui = _state.value.setup
        val terms = ui.terms ?: return
        if (ui.accepting) return
        setup { it.copy(accepting = true, termsLine = null) }
        scope.launch {
            // Exactly the licences the server showed, and the owner's own Continue on an amber row.
            when (val read = api.install(terms.licences.map { it.id }, ui.continued)) {
                is AgentPhoneResult.Done -> {
                    timeLeft.reset()
                    setup { it.copy(accepting = false, stage = AgentPhoneStage.Download, install = read.value, installLine = null) }
                }
                is AgentPhoneResult.Refused -> when (read.line.action) {
                    // The checks or the terms are older than 15 minutes (or another phone answered them): do them again.
                    AgentPhoneAction.CheckAgain -> { setup { it.copy(accepting = false) }; check(because = read.line) }
                    AgentPhoneAction.ReadTerms -> { setup { it.copy(accepting = false, terms = null) }; loadTerms() }
                    else -> setup { it.copy(accepting = false, termsLine = read.line) }
                }
            }
        }
    }

    fun pause() = installControl { api.pause() }
    fun resume() = installControl { api.resume() }
    fun cancel() = installControl { api.cancel() }

    private fun installControl(call: suspend () -> AgentPhoneResult<AgentPhoneInstall>) {
        if (_state.value.setup.installBusy) return
        setup { it.copy(installBusy = true, installLine = null) }
        scope.launch {
            when (val read = call()) {
                is AgentPhoneResult.Done -> { setup { it.copy(installBusy = false) }; installed(read.value) }
                is AgentPhoneResult.Refused -> setup { it.copy(installBusy = false, installLine = read.line) }
            }
        }
    }

    /** Back to the first page: the confirmation stays, the checks and terms are asked again. */
    fun startAgain() = setup { it.copy(stage = AgentPhoneStage.Before, checks = null, continued = false, checksLine = null, terms = null, termsLine = null, installLine = null) }

    fun tick(task: AgentPhoneTask, done: Boolean) {
        ticks?.set(task.key, done)
        setup { it.copy(ticks = if (done) it.ticks + task.key else it.ticks - task.key) }
    }

    fun finish() = setup { it.copy(stage = AgentPhoneStage.Done) }

    /** The live view closed: read again (the checklist ticks itself), and a journey on Ready goes on to the checklist. */
    fun returnedFromLiveView() {
        setup { it.copy(opening = false, stage = if (it.stage == AgentPhoneStage.Ready) AgentPhoneStage.Checklist else it.stage) }
        scope.launch { refresh() }
    }

    fun liveViewMissing() {
        val line = AgentPhoneLine(AGENT_PHONE_VIEW_UPDATE, action = AgentPhoneAction.Update)
        setup { it.copy(opening = false, openLine = line) }
        update { it.copy(actionLine = if (it.flowOpen) it.actionLine else line) }
    }

    // -- the phone -----------------------------------------------------------------------------------------------------------------

    /** Opens the live view, starting the phone first when it is off. */
    fun open() {
        val status = _state.value.status
        setup { it.copy(opening = true, openLine = null) }
        if (status?.power == AgentPhonePower.On) update { it.copy(openRequest = it.openRequest + 1) }
        else { openWhenOn = true; if (status?.power != AgentPhonePower.Starting) start() else startWatch = true }
    }

    fun start() {
        if (_state.value.busy == AgentPhoneBusy.Start) return
        update { it.copy(busy = AgentPhoneBusy.Start, actionLine = null) }
        setup { it.copy(startLine = null) }
        scope.launch {
            when (val read = api.start()) {
                is AgentPhoneResult.Done -> {
                    update { it.copy(busy = null, status = it.status?.copy(power = read.value)) }
                    if (read.value == AgentPhonePower.On) { startWatch = true; applyStatus(_state.value.status ?: AgentPhoneStatus(power = AgentPhonePower.On)) }
                    else startWatch = true
                }
                is AgentPhoneResult.Refused -> {
                    openWhenOn = false
                    update { it.copy(busy = null, actionLine = if (it.flowOpen) it.actionLine else read.line) }
                    setup { it.copy(startLine = read.line, opening = false) }
                }
            }
        }
    }

    fun stop() {
        if (_state.value.busy == AgentPhoneBusy.Stop) return
        update { it.copy(busy = AgentPhoneBusy.Stop, actionLine = null) }
        scope.launch {
            when (val read = api.stop()) {
                is AgentPhoneResult.Done -> update { it.copy(busy = null, status = it.status?.copy(power = read.value)) }
                is AgentPhoneResult.Refused -> update { it.copy(busy = null, actionLine = read.line) }
            }
        }
    }

    suspend fun giveTask(requestId: String, task: String): AgentPhoneResult<AgentPhoneDriverTask> {
        if (!_state.value.canGiveTask || !validAgentPhoneTask(task)) {
            return AgentPhoneResult.Refused(AgentPhoneLine(AGENT_PHONE_WENT_WRONG), 0)
        }
        return changeDriverTask { api.giveTask(requestId, task) }
    }

    fun cancelTask(id: String) {
        scope.launch {
            val ui = _state.value
            if (!ui.loaded || ui.driverTaskBusy || ui.loadLine != null || !ui.driverTaskRunning || ui.status?.driverTask?.id != id) return@launch
            val result = changeDriverTask { api.cancelTask(id) }
            if (result is AgentPhoneResult.Refused) update { it.copy(driverTaskLine = result.line) }
        }
    }

    private suspend fun changeDriverTask(call: suspend () -> AgentPhoneResult<AgentPhoneDriverTask>): AgentPhoneResult<AgentPhoneDriverTask> {
        ++driverTaskVersion
        update { it.copy(driverTaskBusy = true, driverTaskLine = null) }
        val result = try {
            call()
        } finally {
            ++driverTaskVersion
            update { it.copy(driverTaskBusy = false) }
        }
        when (result) {
            is AgentPhoneResult.Done -> update { ui ->
                ui.copy(status = ui.status?.let { status -> status.copy(driverTask = result.value.takeIf { status.hasOwnedDriver }) })
            }
            // A lost reply may still have started or cancelled the task.
            is AgentPhoneResult.Refused -> scope.launch { refresh(all = false) }
        }
        return result
    }

    fun setEnabled(enabled: Boolean) = savePolicy(_state.value.policy?.copy(enabled = enabled) ?: AgentPhonePolicy(enabled = enabled)) { api.setPolicy(enabled = enabled) }
    fun setAsk(ask: AgentPhoneAsk) = savePolicy(_state.value.policy?.copy(ask = ask) ?: AgentPhonePolicy(ask = ask)) { api.setPolicy(ask = ask) }

    private fun savePolicy(shown: AgentPhonePolicy, call: suspend () -> AgentPhoneResult<AgentPhonePolicy>) {
        if (_state.value.busy == AgentPhoneBusy.Policy) return
        val before = _state.value.policy
        update { it.copy(policy = shown, busy = AgentPhoneBusy.Policy, actionLine = null) }
        scope.launch {
            when (val read = call()) {
                is AgentPhoneResult.Done -> update { it.copy(policy = read.value, busy = null) }
                // The switch goes back to what Hermes has.
                is AgentPhoneResult.Refused -> update { it.copy(policy = before, busy = null, actionLine = read.line) }
            }
        }
    }

    fun answerNotice(notice: AgentPhoneNeedsYou, openPhone: Boolean) {
        update { it.copy(busy = AgentPhoneBusy.Notice, needsYou = it.needsYou - notice) }
        scope.launch {
            val read = api.answerNeedsYou(notice.id, openPhone)
            // A notice that already ended (409) or went (404) needs no word: it is simply gone.
            val refused = read as? AgentPhoneResult.Refused
            update { it.copy(busy = null, actionLine = refused?.line?.takeIf { refused.status != 409 && refused.status != 404 }) }
            if (openPhone) open()
        }
    }

    fun updateHelper() {
        if (_state.value.busy == AgentPhoneBusy.Helper) return
        helperErrorBefore = _state.value.status?.error
        helperSince = now()
        update { it.copy(busy = AgentPhoneBusy.Helper, actionLine = null) }
        scope.launch {
            val read = api.updateHelper()
            if (read is AgentPhoneResult.Refused) update { it.copy(busy = null, actionLine = read.line) }
        }
    }

    /** Remove deletes the phone and its folder; the hand ticks go with it. [done] gets the space freed, for the page's notice. */
    fun remove(deleteLogins: Boolean, done: (Long?) -> Unit = {}) {
        if (_state.value.busy == AgentPhoneBusy.Remove) return
        update { it.copy(busy = AgentPhoneBusy.Remove, actionLine = null) }
        scope.launch {
            when (val read = api.remove(deleteLogins)) {
                is AgentPhoneResult.Done -> {
                    ticks?.clear()
                    update { it.copy(busy = null, policy = null, activity = null, needsYou = emptyList(), setup = AgentPhoneSetupState()) }
                    done(read.value)
                    refresh(all = false)
                }
                is AgentPhoneResult.Refused -> update { it.copy(busy = null, actionLine = read.line) }
            }
        }
    }

    fun dismissLine() = update { it.copy(actionLine = null) }
}

internal const val AGENT_PHONE_VIEW_UPDATE = "Update Beacon to open the phone."

// -- the page ---------------------------------------------------------------------------------------------------------------------

/** What the page's buttons do; navigation and links come from the app. */
internal class AgentPhonePageActions(
    val getStarted: () -> Unit = {},
    val notNow: () -> Unit = {},
    val showProgress: () -> Unit = {},
    val open: () -> Unit = {},
    val start: () -> Unit = {},
    val stop: () -> Unit = {},
    val refresh: () -> Unit = {},
    val line: (AgentPhoneLine) -> Unit = {},
    val setEnabled: (Boolean) -> Unit = {},
    val setAsk: (AgentPhoneAsk) -> Unit = {},
    val openVault: () -> Unit = {},
    val updateHelper: () -> Unit = {},
    val notice: (AgentPhoneNeedsYou, Boolean) -> Unit = { _, _ -> },
    val tick: (AgentPhoneTask, Boolean) -> Unit = { _, _ -> },
    val remove: () -> Unit = {},
    /** The words a profile id is shown with ("default" reads "Default"). */
    val profileLabel: (String) -> String = ::readableProfileId,
    /** The vault's own name for a profile when there are several, else null ("In your vault"). */
    val vaultName: (String) -> String? = { null },
    /** L6's whole activity list (joined 17 Nov). */
    val seeAll: () -> Unit = {},
    val giveTask: (suspend (String, String) -> AgentPhoneResult<AgentPhoneDriverTask>)? = null,
    val cancelTask: (String) -> Unit = {},
) {
    fun copyWithLine(line: (AgentPhoneLine) -> Unit) = AgentPhonePageActions(getStarted, notNow, showProgress, open, start, stop, refresh, line, setEnabled, setAsk,
        openVault, updateHelper, notice, tick, remove, profileLabel, vaultName, seeAll, giveTask, cancelTask)
}

/**
 * The vault Saved logins opens: with one profile it is simply "your vault" (null); with several, the vault goes by its profile's name, as
 * the Vault page titles it (Hermes' display name, else the id made readable).
 */
internal fun agentPhoneVaultName(profileId: String, profiles: List<org.json.JSONObject>): String? =
    if (profiles.size > 1) profileDisplayName(profileId, profiles).takeIf { it != profileId } ?: readableProfileId(profileId) else null

/** Settings › {name}'s phone, wired to the app. The phone belongs to the assistant's profile (Settings › Assistant). */
@Composable internal fun AgentPhoneSettings(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val profile = prefs.assistantProfile
    val source = vm.client
    val account = source?.scope
    val key = agentPhoneCacheKey(profile)
    val profileRows = data["navigation-profiles"]?.optJSONArray("profiles")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) } }.orEmpty()
    // One client for the assistant's profile while the page is open; closed when it goes.
    val client = remember(source, profile) { source?.let { runCatching { it.forAuthorizedProfile(profile) }.getOrNull() } }
    val saved = remember(key, data[key], vm.restoredScope()) { savedAgentPhone(data[key].takeIf { vm.restoredScope() != null }) }
    DisposableEffect(client) { onDispose { client?.closeNative() } }
    val scope = rememberCoroutineScope()
    val controller = remember(client) {
        client?.let { scoped ->
            val ticks = AgentPhoneTicks(context.applicationContext.getSharedPreferences(AgentPhoneTicks.PREFS, Context.MODE_PRIVATE), AgentPhoneTicks.key(scoped.scope, profile))
            AgentPhoneController(AgentPhoneApi(ClientAgentPhoneEndpoint(scoped)), scope, ticks, data[key],
                onStatus = { body -> account?.let { vm.keepScreen(it, key, body) } })
        }
    }
    val state by (controller?.state ?: remember { MutableStateFlow(AgentPhoneUi()) }).collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(controller) {
        val owner = controller ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            owner.refresh()
            while (true) { owner.awaitPoll(); owner.poll() }
        }
    }
    val live = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { controller?.returnedFromLiveView() }
    LaunchedEffect(state.openRequest) {
        if (state.openRequest == 0) return@LaunchedEffect
        if (AgentPhoneLiveView.available(context)) runCatching { live.launch(AgentPhoneLiveView.intent(context)) }.onFailure { controller?.liveViewMissing() }
        else controller?.liveViewMissing()
    }
    val removedLine = uiText("Removed. {0} freed.")
    val removedPlain = uiText("Removed.")
    fun onLine(line: AgentPhoneLine) {
        val owner = controller ?: return
        when (line.action) {
            AgentPhoneAction.Update -> vm.more("App updates")
            AgentPhoneAction.ChooseProfile -> vm.more("Assistant")
            AgentPhoneAction.How -> runCatching { uri.openUri(AGENT_PHONE_SIGN_IN_GUIDE) }
            AgentPhoneAction.CheckAgain -> if (state.flowOpen) owner.check() else owner.refreshNow()
            AgentPhoneAction.Resume -> owner.resume()
            AgentPhoneAction.StartAgain -> owner.startAgain()
            AgentPhoneAction.ReadTerms -> owner.toTerms()
            AgentPhoneAction.TryAgain -> when {
                state.flowOpen && state.setup.stage == AgentPhoneStage.Terms -> owner.loadTerms()
                state.flowOpen && state.setup.stage == AgentPhoneStage.Checks -> owner.check()
                state.flowOpen && state.setup.stage == AgentPhoneStage.Download -> owner.resume()
                state.flowOpen && state.setup.stage == AgentPhoneStage.FirstStart -> owner.start()
                agentPhoneLineStartsPhone(line) -> owner.start()
                line.template == AGENT_PHONE_HELPER_FAILED -> owner.updateHelper()
                else -> owner.refreshNow()
            }
            AgentPhoneAction.None -> owner.dismissLine()
        }
    }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    BackHandler { vm.backFromSettings() }
    Column(Modifier.fillMaxSize()) {
        Header(uiText("{name}'s phone").named(), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        if (client == null && saved.status != null) {
            val status = saved.status
            val hero = agentPhoneHero(status, saved.needsYou.size)
            SettingsList(Modifier.weight(1f), testTag = "agent-phone-page") {
                item { PhoneHero(hero, status, heroLine(hero, status, saved, profile, ::readableProfileId),
                    saved.activity?.maxByOrNull { it.atMillis }, emptyList()) }
                if (saved.policy != null) item { PhonePolicySection(saved, null) }
                saved.activity?.let { rows -> item { SettingsSection(uiText("Recent activity")) {
                    if (rows.isEmpty()) SettingsEmpty(uiText("Nothing yet"), Icons.Rounded.History)
                    rows.take(8).forEach { ActivityRow(it) }
                } } }
            }
            return@Column
        }
        key(controller) {
            AgentPhonePage(
                state, profileName = profile,
                actions = AgentPhonePageActions(
                    getStarted = { controller?.openFlow() }, notNow = { vm.backFromSettings() }, showProgress = { controller?.openFlow() },
                    open = { controller?.open() }, start = { controller?.start() }, stop = { controller?.stop() }, refresh = { controller?.refreshNow() },
                    line = ::onLine, setEnabled = { controller?.setEnabled(it) }, setAsk = { controller?.setAsk(it) }, openVault = { vm.more("Vault") },
                    updateHelper = { controller?.updateHelper() }, notice = { notice, open -> controller?.answerNotice(notice, open) },
                    tick = { task, done -> controller?.tick(task, done) }, remove = { confirmRemove = true },
                    seeAll = { runCatching { context.startActivity(dev.hermes.android.agentphone.AgentPhoneLogActivity.intent(context, null)) } },
                    profileLabel = { id -> profileDisplayName(id, profileRows).takeIf { it != id } ?: readableProfileId(id) },
                    vaultName = { id -> agentPhoneVaultName(id, profileRows) },
                    giveTask = controller?.let { it::giveTask }, cancelTask = { controller?.cancelTask(it) },
                ),
                modifier = Modifier.weight(1f),
            )
        }
    }
    if (confirmRemove) AgentPhoneRemoveDialog(
        freedBytes = state.status?.storageBytes, removing = state.busy == AgentPhoneBusy.Remove,
        onRemove = { deleteLogins ->
            controller?.remove(deleteLogins) { freed ->
                confirmRemove = false
                vm.notice(if (freed != null) removedLine.replace("{0}", agentPhoneSize(context, freed)) else removedPlain)
            }
        },
        onDismiss = { if (state.busy != AgentPhoneBusy.Remove) confirmRemove = false },
    )
    if (state.flowOpen && controller != null) AgentPhoneFlowDialog(state.setup, AgentPhoneSetupActions(
        close = controller::closeFlow, confirm = controller::confirm, check = controller::check, continueWarning = controller::continueWarning,
        toTerms = controller::toTerms, loadTerms = controller::loadTerms, accept = controller::accept, pause = controller::pause, resume = controller::resume,
        cancel = controller::cancel, startAgain = controller::startAgain, start = controller::start, open = controller::open, tick = controller::tick,
        finish = controller::finish, line = ::onLine, openLink = { runCatching { uri.openUri(it) } }, back = controller::back,
    ))
}

/** The journey over the app, full screen, on the setup wizard's sea; the system bars draw light on it. */
@Composable internal fun AgentPhoneFlowDialog(setup: AgentPhoneSetupState, actions: AgentPhoneSetupActions) {
    val working = setup.checking || setup.accepting || setup.installBusy
    var confirmCancel by rememberSaveable { mutableStateOf(false) }
    val flowActions = remember(actions) {
        AgentPhoneSetupActions(close = actions.close, confirm = actions.confirm, check = actions.check, continueWarning = actions.continueWarning, toTerms = actions.toTerms,
            loadTerms = actions.loadTerms, accept = actions.accept, pause = actions.pause, resume = actions.resume, cancel = { confirmCancel = true },
            startAgain = actions.startAgain, start = actions.start, open = actions.open, tick = actions.tick, finish = actions.finish, line = actions.line, openLink = actions.openLink,
            back = actions.back)
    }
    Dialog(onDismissRequest = { if (!working) actions.close() }, properties = DialogProperties(dismissOnBackPress = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler { actions.back() }
        val view = LocalView.current
        val window = (view.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, view) {
            val bars = window?.let { WindowCompat.getInsetsController(it, view) }
            bars?.isAppearanceLightStatusBars = false
            bars?.isAppearanceLightNavigationBars = false
            onDispose {}
        }
        Box(Modifier.fillMaxSize().background(SetupSea).testTag("agent-phone-flow")) {
            AgentPhoneSetupScreen(setup, flowActions, Modifier.windowInsetsPadding(WindowInsets.safeDrawing))
        }
        if (confirmCancel) SetupSeaTheme {
            SettingsConfirmDialog(uiText("Cancel the download?"), uiText("What's downloaded so far is deleted."), uiText("Cancel download"),
                onConfirm = { confirmCancel = false; actions.cancel() }, onDismiss = { confirmCancel = false }, confirmTestTag = "agent-phone-cancel-confirm",
                dismissLabel = uiText("Keep going"))
        }
    }
}

/** The Remove dialog's words: what is deleted, then the space it frees from the phone's real size; with no size, no Frees sentence. */
internal fun agentPhoneRemoveBody(deleted: String, freesTemplate: String, size: String?): String =
    listOfNotNull(deleted, size?.let { freesTemplate.replace("{0}", it) }).joinToString(" ")

/** Whether a line's Try again starts the phone (a start that failed, or a start refused while Hermes' dashboard was off). */
internal fun agentPhoneLineStartsPhone(line: AgentPhoneLine): Boolean =
    line.action == AgentPhoneAction.TryAgain && (line.template == AGENT_PHONE_DIDNT_START || line.template.endsWith("dashboard is off."))

/** A size as the page shows it: number and unit never split across lines, and kept whole inside right-to-left text. */
internal fun agentPhoneSize(context: Context, bytes: Long): String = isolateBidi(Formatter.formatShortFileSize(context, bytes).replace(' ', '\u00A0'))

/** Remove, with the one choice it has (SettingsConfirmDialog has no room for it): the phone's saved logins go too, or stay in the vault. */
@Composable internal fun AgentPhoneRemoveDialog(freedBytes: Long?, removing: Boolean, onRemove: (Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteLogins by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val body = agentPhoneRemoveBody(uiText("Its apps and sign-ins are deleted. Your online accounts stay as they are."), uiText("Frees {0}."),
        freedBytes?.let { agentPhoneSize(context, it) })
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("agent-phone-remove-dialog"),
        title = { Text(uiText("Remove {name}'s phone?").named()) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(deleteLogins, enabled = !removing, role = Role.Checkbox) { deleteLogins = it }.testTag("agent-phone-remove-logins"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(deleteLogins, onCheckedChange = null, enabled = !removing)
                    Text(uiText("Also delete logins saved from the phone"), Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton({ onRemove(deleteLogins) }, Modifier.heightIn(min = 48.dp).testTag("agent-phone-remove-confirm"), enabled = !removing,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                if (removing) { BeaconDustCore(16.dp, color = MaterialTheme.colorScheme.error); Spacer(Modifier.width(8.dp)) }
                Text(uiText(if (removing) "Removing…" else "Remove"))
            }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp), enabled = !removing) { Text(uiText("Cancel")) } },
    )
}

/** The page itself, from [state] alone, so the tour can show every state of it. */
@Composable internal fun AgentPhonePage(state: AgentPhoneUi, profileName: String, actions: AgentPhonePageActions, modifier: Modifier = Modifier) {
    val status = state.status
    var enteringTask by remember(status?.hasOwnedDriver) { mutableStateOf(false) }
    // No phone yet: the welcome fills the page (follow-up 1), not a card in the list.
    if (status != null && !status.installed && !status.install.working && !status.install.resumable) {
        AgentPhoneWelcome(actions, modifier)
        return
    }
    SettingsList(modifier, refreshing = status != null && !state.loaded, testTag = "agent-phone-page") {
        when {
            status == null && state.loadLine != null -> item { LoadProblem(state.loadLine, actions) }
            status == null -> item { SettingsSection(testTag = "agent-phone-loading") { SettingsSkeletonRows(3) } }
            else -> phonePage(state, status, profileName, actions, enterTask = { enteringTask = true })
        }
    }
    val giveTask = actions.giveTask
    if (enteringTask && status?.hasOwnedDriver == true && giveTask != null) {
        AgentPhoneTaskDialog(state.canGiveTask, giveTask) { enteringTask = false }
    }
}

@Composable private fun LoadProblem(line: AgentPhoneLine, actions: AgentPhonePageActions) {
    // A card, not the hero: the hero's title stops at two lines, and this line must be read whole in every language and size.
    val shown = if (line.action == AgentPhoneAction.None) line.copy(action = AgentPhoneAction.TryAgain) else line
    ProblemCard(shown, actions.copyWithLine { if (it.action == AgentPhoneAction.TryAgain) actions.refresh() else actions.line(it) }, Modifier.testTag("agent-phone-load-problem"))
}

@Composable private fun heroLine(hero: AgentPhoneHero, status: AgentPhoneStatus, state: AgentPhoneUi, profileName: String, label: (String) -> String): String = when (hero) {
    AgentPhoneHero.Off -> uiText("{name} turns it on when needed.").named()
    AgentPhoneHero.On -> if (status.lease?.let { it.human && !it.you } == true) uiText("Open on another phone.") else uiText("Ready for {name}.").named()
    AgentPhoneHero.InUse -> uiText("{name} waits while you have it open.").named()
    // The notices themselves are listed right under the hero, each with its own answer.
    AgentPhoneHero.NeedsYou -> ""
    AgentPhoneHero.OtherProfile -> uiText("{name}'s phone belongs to the {0} profile.").named().replace("{0}", label(status.profile ?: profileName))
    AgentPhoneHero.Installing -> uiText(if (status.install.state == AgentPhoneInstallState.Paused) "Paused" else if (status.install.state == AgentPhoneInstallState.Failed) "The download stopped" else "Downloading")
    else -> ""
}

@Composable private fun PhonePolicySection(state: AgentPhoneUi, actions: AgentPhonePageActions?) {
    val busy = state.busy
    val policy = state.policy
    // Under "Ask before": the two honest lines the owner decided on (design Q3 and Q11).
    val honestLines = uiText("Payment questions cover {name}'s phone tool.").named() + "\n" + uiText("Scheduled orders can't use cards from your vault.")
    SettingsSection(uiText("Using the phone"), footer = honestLines, testTag = "agent-phone-use") {
        SettingsSwitchRow(uiText("Let {name} use its phone").named(), policy?.enabled ?: true, { actions?.setEnabled?.invoke(it) }, icon = Icons.Rounded.Smartphone,
            enabled = actions != null && policy != null, busy = busy == AgentPhoneBusy.Policy, testTag = "agent-phone-enabled")
        SettingsDivider()
        val asks = AgentPhoneAsk.entries.map { it.wire }
        SettingsChoiceRow(uiText("Ask before"), (policy?.ask ?: AgentPhoneAsk.PaymentsAndSends).wire, asks, { wire -> AgentPhoneAsk.entries.firstOrNull { it.wire == wire }?.let { actions?.setAsk?.invoke(it) } },
            icon = Icons.Rounded.VerifiedUser, optionLabels = mapOf("payments_and_sends" to "Payments and sends", "every_action" to "Every action"),
            optionTestTags = asks.associateWith { "agent-phone-ask:$it" }, enabled = actions != null && policy != null && policy.enabled && busy != AgentPhoneBusy.Policy,
            style = ChoiceStyle.Auto, fitSegments = true)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.phonePage(
    state: AgentPhoneUi, status: AgentPhoneStatus, profileName: String, actions: AgentPhonePageActions, enterTask: () -> Unit,
) {
    val hero = agentPhoneHero(status, state.needsYou.size)
    val busy = state.busy
    // The last start's error shows only while the phone is off and the server still reports one.
    val problem = state.actionLine ?: status.error?.takeIf { status.power == AgentPhonePower.Off && status.installed }
        ?.let { agentPhoneLine(it, AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain), status.errorCode, status.errorArgs) }
    // A card whose own answer starts the phone (a failed start) is the one way to start it; the hero then says no more.
    val cardStarts = problem != null && agentPhoneLineStartsPhone(problem)
    item(key = "hero") {
        val buttons = agentPhoneHeroActions(hero, cardStarts, status.power == AgentPhonePower.On).map { action -> action to
            when (action) {
                AgentPhoneHeroAction.ShowProgress -> SettingsAction(uiText("Show progress"), actions.showProgress, testTag = "agent-phone-show-progress")
                AgentPhoneHeroAction.ChooseProfile -> SettingsAction(uiText("Choose a profile"), { actions.line(AgentPhoneLine("", action = AgentPhoneAction.ChooseProfile)) }, testTag = "agent-phone-choose-profile")
                AgentPhoneHeroAction.TurnOn -> SettingsAction(uiText("Turn on"), actions.start, enabled = busy == null, loading = busy == AgentPhoneBusy.Start, testTag = "agent-phone-turn-on")
                AgentPhoneHeroAction.Open -> SettingsAction(uiText("Open"), actions.open, enabled = busy != AgentPhoneBusy.Stop, loading = state.setup.opening, testTag = "agent-phone-open")
                AgentPhoneHeroAction.TurnOff -> SettingsAction(uiText("Turn off"), actions.stop, enabled = busy == null, testTag = "agent-phone-turn-off")
            }
        }
        val last = state.activity?.maxByOrNull { it.atMillis }?.takeIf { status.installed && status.yours }
        PhoneHero(hero, status, if (cardStarts && hero == AgentPhoneHero.Off) "" else heroLine(hero, status, state, profileName, actions.profileLabel), last, buttons)
    }
    val warning = agentPhoneLine(status.warning, code = status.warningCode)
    if (warning != null) item(key = "warning") { ProblemCard(warning, actions, Modifier.testTag("agent-phone-warning"), attention = true) }
    if (problem != null) item(key = "problem") { ProblemCard(problem, actions) }
    if (state.needsYou.isNotEmpty()) item(key = "needs-you") {
        SettingsSection(testTag = "agent-phone-needs-you") {
            state.needsYou.forEachIndexed { index, notice ->
                if (index > 0) SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("agent-phone-notice:${notice.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // The plugin's sentence in the owner's language, as the approval card says it (P2-4).
                    Text(shownLine(serverLine(notice.line)), style = MaterialTheme.typography.bodyLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ actions.notice(notice, true) }, Modifier.heightIn(min = 48.dp)) { Text(uiText("Open the phone")) }
                        TextButton({ actions.notice(notice, false) }, Modifier.heightIn(min = 48.dp)) { Text(uiText("Dismiss")) }
                    }
                }
            }
        }
    }
    if (status.hasOwnedDriver && actions.giveTask != null) item(key = "driver-task") {
        AgentPhoneTaskSection(state, enterTask, actions.cancelTask)
    }
    if (!status.installed) return
    val rows = agentPhoneChecklist(status.facts, state.setup.ticks, state.setup.chromeSeenBelow)
    if (status.yours && !agentPhoneChecklistDone(rows)) item(key = "finish") {
        SettingsSection(uiText("Finish setting up"), testTag = "agent-phone-finish-setup") {
            AgentPhoneChecklist(rows, onTick = actions.tick, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), contained = false)
        }
    }
    if (!status.yours) return
    item(key = "use") { PhonePolicySection(state, actions) }
    item(key = "phone") {
        SettingsSection(uiText("Phone"), testTag = "agent-phone-settings") {
            SettingsNavRow(uiText("Saved logins"), actions.openVault, icon = Icons.Rounded.Key,
                value = actions.vaultName(status.profile ?: profileName) ?: uiText("In your vault"), testTag = "agent-phone-vault")
            status.helper?.let { helper ->
                SettingsDivider()
                HelperRow(helper, busy == AgentPhoneBusy.Helper, actions)
            }
        }
    }
    item(key = "activity") {
        SettingsSection(uiText("Recent activity"), testTag = "agent-phone-activity") {
            val rowsShown = state.activity
            when {
                rowsShown == null -> SettingsSkeletonRows(2)
                rowsShown.isEmpty() -> SettingsEmpty(uiText("Nothing yet"), Icons.Rounded.History)
                else -> {
                    rowsShown.take(8).forEachIndexed { index, row ->
                        if (index > 0) SettingsDivider()
                        ActivityRow(row)
                    }
                    SettingsDivider()
                    SettingsNavRow(uiText("Show all"), actions.seeAll, icon = Icons.Rounded.History, testTag = "agent-phone-activity-all")
                }
            }
        }
    }
    item(key = "storage") {
        val context = LocalContext.current
        // One figure with the whole width: two side by side cut their labels at large text.
        status.storageBytes?.let { used ->
            SettingsSection(uiText("Storage"), testTag = "agent-phone-storage") { SettingsMetricRow(listOf(uiText("On the computer") to agentPhoneSize(context, used))) }
        }
    }
    item(key = "remove") {
        SettingsSection(testTag = "agent-phone-remove-section") {
            SettingsDestructiveRow(uiText("Remove phone"), actions.remove, enabled = busy != AgentPhoneBusy.Remove, testTag = "agent-phone-remove")
        }
    }
}

@Composable private fun AgentPhoneTaskSection(state: AgentPhoneUi, enterTask: () -> Unit, cancelTask: (String) -> Unit) {
    val task = state.status?.driverTask
    SettingsSection(title = if (task == null) "" else uiText("Task"), testTag = "agent-phone-driver-task") {
        if (task != null) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-driver-state")) {
                    Text(uiText(task.state.label), style = MaterialTheme.typography.titleMedium)
                    task.message?.takeIf { it in phoneDriverFailureWords }?.let {
                        Text(uiText(it), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                (state.driverTaskLine ?: state.loadLine)?.let {
                    Text(agentPhoneText(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                }
                if (state.driverTaskRunning) {
                    TextButton(onClick = { cancelTask(task.id) }, enabled = state.loaded && !state.driverTaskBusy && state.loadLine == null,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("agent-phone-cancel-task")) { Text(uiText("Cancel task")) }
                }
            }
        }
        if (!state.driverTaskRunning) SettingsNavRow(uiText("Give a task"), enterTask, icon = Icons.Rounded.PlayArrow,
            enabled = state.canGiveTask, testTag = "agent-phone-give-task")
    }
}

@Composable private fun AgentPhoneTaskDialog(
    enabled: Boolean, giveTask: suspend (String, String) -> AgentPhoneResult<AgentPhoneDriverTask>, close: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val task = text.trim()
    var requestId by remember(task) { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<AgentPhoneLine?>(null) }
    val scope = rememberCoroutineScope()
    val tooLong = task.isNotEmpty() && !validAgentPhoneTask(task)
    AlertDialog(
        onDismissRequest = { if (!sending) close() },
        title = { Text(uiText("Give a task"), Modifier.semantics { heading() }) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().testTag("agent-phone-task-goal"),
                    label = { Text(uiText("Task")) }, enabled = !sending, maxLines = 4, isError = tooLong)
                if (tooLong) Text(uiText("This message is too long. Shorten it."), color = MaterialTheme.colorScheme.error)
                error?.let {
                    Text(agentPhoneText(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-task-send-problem"),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val id = requestId ?: "${System.currentTimeMillis()}-${UUID.randomUUID()}".also { requestId = it }
                    sending = true
                    error = null
                    scope.launch {
                        try {
                            when (val result = giveTask(id, task)) {
                                is AgentPhoneResult.Done -> close()
                                is AgentPhoneResult.Refused -> error = result.line
                            }
                        } finally { sending = false }
                    }
                },
                enabled = enabled && !sending && task.isNotEmpty() && !tooLong,
                modifier = Modifier.heightIn(min = 48.dp).testTag("agent-phone-send-task"),
            ) { Text(uiText(if (sending) "Sending…" else "Send")) }
        },
        dismissButton = {
            TextButton(onClick = close, enabled = !sending, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel")) }
        },
    )
}

/**
 * The hero (AGENTUX-121 idea 1): {name}'s phone drawn as a phone. Its screen holds the state as a pill, the app of the last thing {name}
 * did while the phone is on, and that last line as the phone's own notification. It is the record's words, never a picture: nothing is
 * recorded, and nothing looks at the phone (a peek would take {name}'s turn on it). Under it, the state's line and its one action.
 */
@Composable private fun PhoneHero(hero: AgentPhoneHero, status: AgentPhoneStatus, line: String, last: AgentPhoneActivity?, buttons: List<Pair<AgentPhoneHeroAction, SettingsAction>>) {
    val scheme = MaterialTheme.colorScheme
    val state = agentPhoneHeroStatus(hero)
    val ink = rememberSettingsStatusPalette().ink(state)
    val on = status.power == AgentPhonePower.On
    val locale = uiLocale()
    val word = uiText(agentPhoneHeroTitle(hero)).named().replaceFirstChar { it.titlecase(locale) }
    // The phone grows with the text, so its words wrap instead of being cut at large sizes.
    val grow = LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    val frame = RoundedCornerShape(34.dp)
    Column(Modifier.fillMaxWidth().padding(top = 4.dp).testTag("agent-phone-hero"), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.width(176.dp * grow).heightIn(min = 272.dp)
                // A soft light behind the phone in its state's colour, brighter while it is on.
                .drawBehind {
                    val glow = size.maxDimension * .75f
                    drawCircle(Brush.radialGradient(listOf(ink.copy(alpha = if (on) .2f else .07f), Color.Transparent), center, glow), glow)
                }
                .clip(frame).background(Brush.verticalGradient(listOf(scheme.surfaceContainerHigh, scheme.surfaceContainerLow)))
                .border(1.5.dp, ink.copy(alpha = if (on) .65f else .3f), frame)
                .semantics(mergeDescendants = true) {}.testTag("agent-phone-hero-phone")
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // 12 dp under the pill at least, so at large text it never meets the app tile when the phone has no room to spare.
            Column(Modifier.padding(bottom = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(50)).background(scheme.onSurfaceVariant.copy(alpha = .3f)))
                // The state as the phone's status: the page's heading, its own node (a merging child stays out of the phone's merged node),
                // and a live region of its own, so a state change speaks only the new state word, never the whole phone again.
                Row(Modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = SettingsStatusTint)).padding(horizontal = 12.dp, vertical = 6.dp)
                    .semantics(mergeDescendants = true) { heading(); liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-hero-title"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeroGlyph(state, 16.dp, ink)
                    Text(word, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Medium), color = ink, textAlign = TextAlign.Center)
                }
            }
            val app = last?.app?.trim()?.takeIf { on && it.isNotEmpty() }
            if (app != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The app's first letter on a tile, where its icon would be: Beacon has the app's name, not its picture.
                Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(scheme.primary.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                    Text(String(Character.toChars(app.codePointAt(0))).uppercase(locale), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = scheme.primary, modifier = Modifier.clearAndSetSemantics {}) // decorative: the app's name follows, a lone "C" node only confuses readers
                }
                Text(isolateBidi(app), Modifier.testTag("agent-phone-hero-app"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            } else HeroGlyph(state, 44.dp, if (on) ink else ink.copy(alpha = .7f))
            if (last != null) {
                val (said, value) = logRowText(last, activityTime(last))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHighest).padding(10.dp).testTag("agent-phone-hero-last"),
                    verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(said, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), color = scheme.onSurface)
                    Text(value, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
                }
            } else Spacer(Modifier.height(4.dp))
        }
        if (line.isNotBlank()) Text(line, Modifier.padding(horizontal = 24.dp).semantics { liveRegion = LiveRegionMode.Polite }.testTag("agent-phone-hero-line"),
            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (buttons.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The state's main action is filled; Turn off is always the quiet one.
            buttons.forEachIndexed { index, (kind, action) ->
                val enabled = action.enabled && !action.loading
                if (index == 0 && kind != AgentPhoneHeroAction.TurnOff) Button(action.onClick, Modifier.heightIn(min = 48.dp).testTag(action.testTag), enabled = enabled) {
                    if (action.loading) { BeaconDustCore(18.dp, color = scheme.onPrimary); Spacer(Modifier.width(8.dp)) }
                    Text(action.label, textAlign = TextAlign.Center)
                } else TextButton(action.onClick, Modifier.heightIn(min = 48.dp).testTag(action.testTag), enabled = enabled) { Text(action.label, textAlign = TextAlign.Center) }
            }
        }
    }
}

/** A state's mark: Beacon's turning dust while the phone works on something, else the state's still icon. */
@Composable private fun HeroGlyph(state: SettingsStatus, size: androidx.compose.ui.unit.Dp, ink: Color) {
    if (state == SettingsStatus.Working && rememberNativeMotionEnabled()) BeaconDustCore(size, color = ink)
    else Icon(settingsStaticGlyph(state), null, Modifier.size(size), tint = ink)
}

/** L2's final values (msg_5374fd68fb90): Update offers Update, Off (installed, not the autofill service) offers Turn on, Missing (not on the
 *  phone yet) says so and offers only a fresh read. Update and Turn on both go through POST /helper/update, which reinstalls the pinned
 *  helper and makes it the autofill service again; the row stays busy until the status reads ready. */
@Composable private fun HelperRow(helper: AgentPhoneHelper, updating: Boolean, actions: AgentPhonePageActions) {
    val status = when (helper) {
        AgentPhoneHelper.Ready -> SettingsStatus.Ok
        AgentPhoneHelper.Update -> SettingsStatus.Attention
        AgentPhoneHelper.Off, AgentPhoneHelper.Missing -> SettingsStatus.Off
    }
    val busyWord = if (helper == AgentPhoneHelper.Off) "Turning on…" else "Updating…"
    val word = uiText(when {
        updating -> busyWord
        helper == AgentPhoneHelper.Ready -> "Ready"
        helper == AgentPhoneHelper.Update -> "Needs an update"
        else -> "Off"
    })
    // A missing helper is one plain line (L2: not on the phone yet), with only Try again under it.
    val line = when (helper) {
        AgentPhoneHelper.Off -> uiText("Saved logins won't fill.")
        AgentPhoneHelper.Missing -> uiText("Not on the phone yet.")
        else -> ""
    }
    // An update is one row that is its own action (Root, 17 Nov: not "Needs an update" over a separate Update row).
    if (helper == AgentPhoneHelper.Update || (updating && helper != AgentPhoneHelper.Off)) {
        SettingsActionRow(uiText("Update autofill"), actions.updateHelper, icon = Icons.Rounded.SystemUpdate, busy = updating,
            busyLabel = uiText(busyWord), testTag = "agent-phone-helper-update")
        return
    }
    // Named for what it does on the phone (P3-4): it fills saved logins, as the setup's Autofill step says.
    SettingsValueRow(uiText("Autofill"), icon = Icons.Rounded.Extension, valueMaxLines = 2, value = line,
        status = if (updating) SettingsStatus.Working else status.takeIf { helper != AgentPhoneHelper.Missing },
        statusLabel = word, testTag = "agent-phone-helper")
    when {
        helper == AgentPhoneHelper.Off -> {
            SettingsDivider()
            SettingsActionRow(uiText("Turn on"), actions.updateHelper, icon = Icons.Rounded.PowerSettingsNew, busy = updating, busyLabel = uiText(busyWord), testTag = "agent-phone-helper-turn-on")
        }
        helper == AgentPhoneHelper.Missing -> {
            SettingsDivider()
            SettingsActionRow(uiText("Try again"), actions.refresh, icon = Icons.Rounded.Refresh, testTag = "agent-phone-helper-retry")
        }
    }
}

/**
 * An activity row's time as the chats write theirs (Root, 17 Nov): today the clock time, then Yesterday, then the date, with the year
 * only when it isn't this year. [atMillis] is the host's own time of the action (approvals-v1 §3 `at`).
 */
internal fun agentPhoneActivityTime(atMillis: Long, today: java.time.LocalDate, zone: java.time.ZoneId, locale: java.util.Locale, is24Hour: Boolean, yesterday: String): String {
    val day = java.time.Instant.ofEpochMilli(atMillis).atZone(zone).toLocalDate()
    val timeZone = java.util.TimeZone.getTimeZone(zone)
    return when (phoneDayName(day, today)) {
        PhoneDayName.Today -> messageTimeLabel(atMillis, timeZone, locale, is24Hour).orEmpty()
        PhoneDayName.Yesterday -> yesterday
        PhoneDayName.Date -> java.text.SimpleDateFormat(nativeDayPattern(locale, withYear = day.year != today.year, withWeekday = false), locale)
            .also { it.timeZone = timeZone }.format(java.util.Date(atMillis))
    }
}

/** A row's time as [agentPhoneActivityTime] writes it, in the shown language and the phone's clock. */
@Composable private fun activityTime(row: AgentPhoneActivity): String {
    val locale = uiLocale()
    val is24Hour = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val yesterday = uiText("Yesterday")
    val zone = java.time.ZoneId.systemDefault()
    val today = java.time.LocalDate.now(zone)
    return remember(row.atMillis, locale, is24Hour, yesterday, today) { agentPhoneActivityTime(row.atMillis, today, zone, locale, is24Hour, yesterday) }
}

/** One activity row in plain words, as the whole list reads it ("Tapped Place order at Kanto Foods", idea 6), its time, and the owner's OK. */
@Composable private fun ActivityRow(row: AgentPhoneActivity) {
    val (title, value) = logRowText(row, activityTime(row))
    val icon = when (row.action) {
        "type" -> Icons.Rounded.Keyboard
        "fill", "save" -> Icons.Rounded.Key
        "swipe" -> Icons.Rounded.Swipe
        "key" -> Icons.Rounded.KeyboardReturn
        else -> Icons.Rounded.TouchApp
    }
    SettingsValueRow(title, icon = icon, value = value, valueMaxLines = 3,
        status = if (row.approved) SettingsStatus.Ok else null, statusLabel = if (row.approved) uiText("Approved by you") else "")
}

/**
 * The last button's refusal or the last start's error: one line, its one action, and Details when the server said more. [attention]
 * draws the developer-tools line (§3, §8) in amber: "Close Android developer tools on this computer." with Check now, and its reason
 * behind Details.
 */
@Composable private fun ProblemCard(line: AgentPhoneLine, actions: AgentPhonePageActions, modifier: Modifier = Modifier.testTag("agent-phone-problem"), attention: Boolean = false) {
    val palette = rememberSettingsStatusPalette()
    val ink = if (attention) palette.attention else palette.error
    var details by rememberSaveable(line.raw) { mutableStateOf(false) }
    Glass(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(if (attention) Icons.Rounded.ErrorOutline else Icons.Rounded.Error, null, Modifier.padding(top = 2.dp).size(20.dp), tint = ink)
                Text(agentPhoneText(line), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            if (details) Text(agentPhoneDetails(line), Modifier.padding(start = 32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val word = agentPhoneActionWord(line.action)
                if (word.isNotBlank()) TextButton({ actions.line(line) }, Modifier.heightIn(min = 48.dp).testTag("agent-phone-problem-action")) { Text(uiText(word)) }
                if (line.hasDetails) TextButton({ details = !details }, Modifier.heightIn(min = 48.dp).testTag("agent-phone-problem-details")) { Text(uiText(if (details) "Hide details" else "Details")) }
            }
        }
    }
}
