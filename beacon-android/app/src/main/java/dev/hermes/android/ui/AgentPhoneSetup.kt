package dev.hermes.android.ui

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountBalance
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.hermes.android.agentphone.AgentPhoneLines
import dev.hermes.android.data.AGENT_PHONE_CHROME_AUTOFILL
import dev.hermes.android.data.AGENT_PHONE_DISK_BYTES
import dev.hermes.android.data.AGENT_PHONE_SIGN_IN_GUIDE
import dev.hermes.android.data.AgentPhoneAction
import dev.hermes.android.data.AgentPhoneCheckRow
import dev.hermes.android.data.AgentPhoneCheckStatus
import dev.hermes.android.data.AgentPhoneChecks
import dev.hermes.android.data.AgentPhoneFacts
import dev.hermes.android.data.AgentPhoneInstall
import dev.hermes.android.data.AgentPhoneInstallPhase
import dev.hermes.android.data.AgentPhoneInstallState
import dev.hermes.android.data.AgentPhoneLine
import dev.hermes.android.data.AgentPhonePower
import dev.hermes.android.data.AgentPhoneStatus
import dev.hermes.android.data.AgentPhoneTerms
import dev.hermes.android.data.AgentPhoneTicks
import dev.hermes.android.data.agentPhoneLine
import dev.hermes.android.phone.isolateBidi
import kotlin.math.ceil

// "Give {name} its own phone": the setup journey (EMU-DESIGN rev 4 §3, §8). The pages use the setup wizard's shell (SetupShell), so
// the journey looks like Beacon's own first-run setup; its four steps are Check, Terms, Get ready and Set up. The model at the top is
// free of Android and is what the JVM tests drive; the server decides where the journey stands, the phone keeps only the owner's
// confirmation and hand ticks.

// -- the model ------------------------------------------------------------------------------------------------------------------

/** One page of the journey. The intro card is on the phone page; the journey opens at [Before]. */
internal enum class AgentPhoneStage { Before, Checks, Terms, Download, FirstStart, Ready, Checklist, Done }

/** SetupShell's "Step n of 4": Check this computer, Google's terms, Getting the phone ready, Set it up. */
internal fun agentPhoneStepNumber(stage: AgentPhoneStage): Int = when (stage) {
    AgentPhoneStage.Before, AgentPhoneStage.Checks -> 1
    AgentPhoneStage.Terms -> 2
    AgentPhoneStage.Download, AgentPhoneStage.FirstStart -> 3
    AgentPhoneStage.Ready, AgentPhoneStage.Checklist, AgentPhoneStage.Done -> 4
}

/**
 * Where the journey opens, from what the server says (its install state lives in the dashboard and is lost when Hermes restarts):
 * an install that runs or stopped picks up at Getting ready, a phone that exists goes to its start or to setting it up.
 */
internal fun agentPhoneEntryStage(status: AgentPhoneStatus?): AgentPhoneStage = when {
    status == null -> AgentPhoneStage.Before
    status.installed && status.power == AgentPhonePower.On -> AgentPhoneStage.Ready
    status.installed -> AgentPhoneStage.FirstStart
    status.install.state in setOf(AgentPhoneInstallState.Running, AgentPhoneInstallState.Paused, AgentPhoneInstallState.Failed, AgentPhoneInstallState.Done) -> AgentPhoneStage.Download
    else -> AgentPhoneStage.Before
}

/**
 * Whether the checks let the owner go on: no row failed (Hermes sign-in among them), and every amber row that asks for it got its
 * own Continue ([continued]).
 */
internal fun agentPhoneChecksPass(checks: AgentPhoneChecks?, continued: Boolean): Boolean =
    checks != null && checks.ok && checks.rows.none { it.status == AgentPhoneCheckStatus.Fail } && (!checks.needsContinue || continued)

/** The words for a check row's name, by L1a's ids; an unknown id shows its line instead. */
internal val agentPhoneCheckNames = mapOf(
    "platform" to "Phone software for this computer",
    "container" to "Hermes on a real computer",
    "virtualisation" to "Virtual phone support",
    "memory" to "Computer memory",
    "disk" to "Free space",
    "network" to "Google's download site",
    "ports" to "Other virtual phones",
    "sign_in" to "Hermes sign-in",
    "developer_tools" to "Android developer tools",
)

/** Where a check row sits on the page: what blocks first, then what to note, then what couldn't be read, then what is ready. */
internal fun agentPhoneCheckOrder(status: AgentPhoneCheckStatus): Int = when (status) {
    AgentPhoneCheckStatus.Fail -> 0
    AgentPhoneCheckStatus.Warn -> 1
    AgentPhoneCheckStatus.Unknown -> 2
    AgentPhoneCheckStatus.Ready -> 3
}

/** The checks page's title and line where the checks stand (AGENTUX-121 P2-2): each result has its own, never the checking line. */
private enum class ChecksWords(val title: String, val line: String) {
    Checking("Checking this computer…", "This can take a minute or two."),
    // The checks could not be asked for (Hermes out of reach, say): the line below says why, Check now asks again.
    Unknown("Check this computer", ""),
    Pass("This computer is ready", "Nothing to fix."),
    Fail("Not ready yet", "Fix these, then check again."),
    Warn("Almost ready", "Read the note, then continue."),
}

internal fun agentPhoneChecksWords(checking: Boolean, checks: AgentPhoneChecks?, pass: Boolean): Pair<String, String> = when {
    checking -> ChecksWords.Checking
    checks == null -> ChecksWords.Unknown
    pass -> ChecksWords.Pass
    checks.rows.any { it.status == AgentPhoneCheckStatus.Fail } -> ChecksWords.Fail
    else -> ChecksWords.Warn
}.let { it.title to it.line }

internal fun agentPhoneCheckWord(status: AgentPhoneCheckStatus): String = when (status) {
    AgentPhoneCheckStatus.Ready -> "Ready"
    AgentPhoneCheckStatus.Warn -> "Note"
    AgentPhoneCheckStatus.Fail -> "Needs a fix"
    AgentPhoneCheckStatus.Unknown -> "Couldn't check"
}

/**
 * Time left on the download, from the bytes the server reports: only while it downloads with a known total, after two readings at
 * least 3 s apart, smoothed; it forgets everything when the bytes go back (a damaged file fetched again, or a fresh start).
 */
internal class AgentPhoneTimeLeft {
    private var lastBytes = -1L
    private var lastAt = 0L
    private var firstAt = 0L
    private var rate = 0.0 // bytes per millisecond

    /** Minutes left (0 = under a minute), or null when there is nothing honest to say. */
    fun update(install: AgentPhoneInstall, nowMillis: Long): Long? {
        val done = install.doneBytes
        val total = install.totalBytes
        if (install.state != AgentPhoneInstallState.Running || install.phase != AgentPhoneInstallPhase.Download || done == null || total == null || total <= 0) {
            if (install.state != AgentPhoneInstallState.Running) reset()
            return null
        }
        if (done < lastBytes || lastBytes < 0) {
            reset()
            lastBytes = done; lastAt = nowMillis; firstAt = nowMillis
            return null
        }
        val gap = nowMillis - lastAt
        if (gap > 0 && done > lastBytes) {
            val now = (done - lastBytes).toDouble() / gap
            rate = if (rate == 0.0) now else rate * .7 + now * .3
            lastBytes = done; lastAt = nowMillis
        } else if (gap > 0) lastAt = nowMillis
        if (rate <= 0.0 || nowMillis - firstAt < 3_000) return null
        val left = (total - done).coerceAtLeast(0)
        return ceil(left / rate / 60_000.0).toLong().let { if (left < rate * 60_000.0) 0L else it }
    }

    fun reset() { lastBytes = -1L; lastAt = 0L; firstAt = 0L; rate = 0.0 }
}

/** The setup checklist (§8 Setup view, §6.6): Chrome's update shows only while Chrome is below 135 (or can't be read). */
internal enum class AgentPhoneTask(val key: String, val words: String) {
    Google("google", "Sign in to Google in the Play Store"),
    Chrome("chrome", "Update Chrome in the Play Store"),
    ChromeAutofill("chrome_autofill", "In Chrome, turn on Autofill using another service"),
    Shops("shops", "Sign in to your shopping apps"),
}

/** One checklist row: [done], whether it is the owner's own tick ([byHand]), and whether a hand tick is allowed ([canTick]). */
internal data class AgentPhoneTaskRow(val task: AgentPhoneTask, val done: Boolean, val byHand: Boolean, val canTick: Boolean)

/**
 * The checklist from what the phone reads ([facts]) and the owner's own [ticks]. A read value always wins; a hand tick counts only
 * where the phone can't read the row (null). The shopping row also takes a tick while no login was saved from the phone: an app that
 * keeps you signed in saves nothing. Chrome's row shows while Chrome is below 135 or unread, and stays (ticked) once it was seen below
 * 135 in this session ([chromeSeenBelow]), so the owner sees it tick itself.
 */
internal fun agentPhoneChecklist(facts: AgentPhoneFacts?, ticks: Set<String>, chromeSeenBelow: Boolean = false): List<AgentPhoneTaskRow> {
    fun flagRow(task: AgentPhoneTask, read: Boolean?) =
        AgentPhoneTaskRow(task, read == true || read == null && task.key in ticks, read == null && task.key in ticks, read == null)
    val chrome = facts?.chrome
    val chromeRow = when {
        chrome == null -> flagRow(AgentPhoneTask.Chrome, null)
        chrome < AGENT_PHONE_CHROME_AUTOFILL -> AgentPhoneTaskRow(AgentPhoneTask.Chrome, done = false, byHand = false, canTick = false)
        chromeSeenBelow -> AgentPhoneTaskRow(AgentPhoneTask.Chrome, done = true, byHand = false, canTick = false)
        else -> null
    }
    val logins = facts?.logins
    val shops = if (logins != null && logins > 0) AgentPhoneTaskRow(AgentPhoneTask.Shops, done = true, byHand = false, canTick = false)
        else AgentPhoneTaskRow(AgentPhoneTask.Shops, AgentPhoneTask.Shops.key in ticks, AgentPhoneTask.Shops.key in ticks, canTick = true)
    return listOfNotNull(flagRow(AgentPhoneTask.Google, facts?.google), chromeRow, flagRow(AgentPhoneTask.ChromeAutofill, facts?.chromeAutofill), shops)
}

internal fun agentPhoneChecklistDone(rows: List<AgentPhoneTaskRow>): Boolean = rows.isNotEmpty() && rows.all { it.done }

/**
 * The one button of a stopped download: picking up where it stopped (Try again) when the server says trying again can help or the line
 * offers Resume or Try again; going back to the checks when the computer needs a fix first; else the line's own action (Update Beacon,
 * Read the terms). A line with no action at all is picked up again too.
 */
internal fun agentPhoneFailedAction(action: AgentPhoneAction?, retry: Boolean): AgentPhoneAction = when {
    retry || action == null || action == AgentPhoneAction.None || action == AgentPhoneAction.Resume || action == AgentPhoneAction.TryAgain -> AgentPhoneAction.TryAgain
    action == AgentPhoneAction.How || action == AgentPhoneAction.ChooseProfile -> AgentPhoneAction.CheckAgain
    else -> action
}

/** The words of an action button, by the action a line offers. */
internal fun agentPhoneActionWord(action: AgentPhoneAction): String = when (action) {
    AgentPhoneAction.None -> ""
    AgentPhoneAction.TryAgain -> "Try again"
    AgentPhoneAction.CheckAgain -> "Check now"
    AgentPhoneAction.Resume -> "Resume"
    AgentPhoneAction.StartAgain -> "Start again"
    AgentPhoneAction.ReadTerms -> "Read the terms"
    AgentPhoneAction.How -> "How"
    AgentPhoneAction.Update -> "Update Beacon"
    AgentPhoneAction.ChooseProfile -> "Choose a profile"
}

/** The fixed words the setup pages pass through uiText by a variable, for the translation checks. */
internal val AgentPhoneSetupStrings: List<String> by lazy {
    agentPhoneCheckNames.values.toList() + AgentPhoneCheckStatus.entries.map(::agentPhoneCheckWord) + AgentPhoneTask.entries.map { it.words } +
        AgentPhoneAction.entries.map(::agentPhoneActionWord).filter(String::isNotBlank) + AgentPhonePhaseWords +
        ChecksWords.entries.flatMap { listOf(it.title, it.line) }.filter(String::isNotBlank)
}

/** The install's three phases as the progress page names them. */
internal val AgentPhonePhaseWords = listOf("Download", "Check", "Set up")

// -- what the pages show and do -------------------------------------------------------------------------------------------------

/** What the journey's pages read: the server's answers and the few things the phone keeps for the journey. */
internal data class AgentPhoneSetupState(
    val stage: AgentPhoneStage = AgentPhoneStage.Before,
    val status: AgentPhoneStatus? = null,
    val confirmed: Boolean = false,
    val checks: AgentPhoneChecks? = null,
    val checking: Boolean = false,
    val continued: Boolean = false,
    val checksLine: AgentPhoneLine? = null,
    val terms: AgentPhoneTerms? = null,
    val termsLoading: Boolean = false,
    val termsLine: AgentPhoneLine? = null,
    val accepting: Boolean = false,
    val install: AgentPhoneInstall = AgentPhoneInstall(),
    val minutesLeft: Long? = null,
    val installLine: AgentPhoneLine? = null,
    val installBusy: Boolean = false,
    val startLine: AgentPhoneLine? = null,
    val opening: Boolean = false,
    val openLine: AgentPhoneLine? = null,
    val ticks: Set<String> = emptySet(),
    val chromeSeenBelow: Boolean = false,
)

/** Every button the journey has; the phone page wires them to the controller. */
internal class AgentPhoneSetupActions(
    val close: () -> Unit = {},
    val confirm: (Boolean) -> Unit = {},
    val check: () -> Unit = {},
    val continueWarning: () -> Unit = {},
    val toTerms: () -> Unit = {},
    val loadTerms: () -> Unit = {},
    val accept: () -> Unit = {},
    val pause: () -> Unit = {},
    val resume: () -> Unit = {},
    val cancel: () -> Unit = {},
    val startAgain: () -> Unit = {},
    val start: () -> Unit = {},
    val open: () -> Unit = {},
    val tick: (AgentPhoneTask, Boolean) -> Unit = { _, _ -> },
    val finish: () -> Unit = {},
    val line: (AgentPhoneLine) -> Unit = {},
    val openLink: (String) -> Unit = {},
    val back: () -> Unit = close,
)

/** A server line in the app's words: the template translated, its numbers and names put back, `{name}` the assistant's own name. */
@Composable internal fun agentPhoneText(line: AgentPhoneLine): String {
    var text = line.args.foldIndexed(uiText(line.template)) { index, said, arg -> said.replace("{$index}", arg) }
    for ((key, value) in line.named) text = text.replace("{$key}", value)
    return text.named()
}

/** What Details says: the host's code in the app's words when the app knows it (revision 5), else the host's own words. */
@Composable internal fun agentPhoneDetails(line: AgentPhoneLine): String {
    val lookup = uiTextLookup()
    return line.code?.let { AgentPhoneLines.words(it, line.raw, line.named, LocalAssistantName.current, lookup) } ?: line.raw
}

/** `{name}` in a translated line, as the owner named the assistant. */
@Composable internal fun String.named(): String = replace("{name}", LocalAssistantName.current)

/** A size as the phone writes it ("1.3 GB"), isolated so it keeps its own order inside right-to-left text. */
@Composable private fun bytes(value: Long?): String = if (value == null) "" else agentPhoneSize(LocalContext.current, value)

/** The whole journey: one SetupShell page per stage, with its close button (the download keeps going on Hermes when it closes). */
@Composable internal fun AgentPhoneSetupScreen(state: AgentPhoneSetupState, actions: AgentPhoneSetupActions, modifier: Modifier = Modifier) {
    // The checks only read the computer, so the owner may leave while they run; an accept or an install button in flight waits.
    val working = state.accepting || state.installBusy
    CompositionLocalProvider(LocalSetupClose provides SetupClose(enabled = !working, onClose = actions.close)) {
        val step = agentPhoneStepNumber(state.stage)
        val tag = Modifier.testTag("agent-phone-stage:${state.stage.name}")
        when (state.stage) {
            AgentPhoneStage.Before -> SetupShell(step, uiText("Before you start"), modifier.then(tag), uiText("{name}'s phone runs on your Hermes computer. It needs:").named(),
                content = {
                    BeforeContent()
                    // The confirmation scrolls with what it confirms, so at large text the pinned buttons leave the loopback line in view.
                    ConfirmRow(state, actions)
                },
                // One pinned button, so the needs and the loopback line stay in view at 2x text; the header's X (the same actions.close)
                // is this page's way out (Root, REVIEW-FIX-UI-121). The other pages keep their Not now.
                actions = { SetupPrimaryButton(uiText("Check this computer"), actions.check, Modifier.testTag("agent-phone-check"), enabled = state.confirmed) })
            AgentPhoneStage.Checks -> ChecksPage(state, actions, modifier.then(tag), step)
            AgentPhoneStage.Terms -> TermsPage(state, actions, modifier.then(tag), step)
            AgentPhoneStage.Download -> DownloadPage(state, actions, modifier.then(tag), step)
            AgentPhoneStage.FirstStart -> SetupShell(step, uiText("Starting the phone"), modifier.then(tag), if (state.startLine == null) uiText("About a minute.") else "",
                content = {
                    AgentPhoneDust(working = state.startLine == null)
                    state.startLine?.let { LineWithDetails(it, Modifier.testTag("agent-phone-start-error")) }
                },
                actions = {
                    if (state.startLine == null) SetupPrimaryButton(uiText("Starting…"), {}, Modifier.testTag("agent-phone-starting"), loading = true)
                    else SetupPrimaryButton(uiText("Try again"), actions.start, Modifier.testTag("agent-phone-start-again"))
                })
            // One "Sign in" page before and after the phone opens (AGENTUX-121 idea 3, P3-7): the same list, ticking itself as the owner goes.
            AgentPhoneStage.Ready, AgentPhoneStage.Checklist -> {
                val rows = agentPhoneChecklist(state.status?.facts, state.ticks, state.chromeSeenBelow)
                val all = agentPhoneChecklistDone(rows)
                SetupShell(step, uiText("Set up the phone"), modifier.then(tag), if (all) uiText("All set.") else uiText("Sign in to {name}'s apps.").named(),
                    content = {
                        // Hand ticks only once the phone was opened (Checklist): on Ready the list shows what is left.
                        AgentPhoneChecklist(rows, onTick = if (state.stage == AgentPhoneStage.Ready) null else actions.tick)
                        state.openLine?.let { SetupLine(it, actions) }
                    },
                    actions = {
                        if (all) {
                            SetupPrimaryButton(uiText("Continue"), actions.finish, Modifier.testTag("agent-phone-finish"))
                            SetupTextAction(uiText("Open the phone"), actions.open, Modifier.testTag("agent-phone-open"))
                        } else {
                            SetupPrimaryButton(uiText("Open the phone"), actions.open, Modifier.testTag("agent-phone-open"), loading = state.opening)
                            if (state.stage == AgentPhoneStage.Ready) SetupTextAction(uiText("Not now"), actions.close, Modifier.testTag("agent-phone-later"))
                            else SetupTextAction(uiText("Continue"), actions.finish, Modifier.testTag("agent-phone-finish"))
                        }
                    })
            }
            AgentPhoneStage.Done -> SetupShell(step, uiText("{name} has its phone").named(), modifier.then(tag),
                content = {
                    // The beacon's one ripple around a solid gold check: the journey's end, said once.
                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                        BeaconSignal(BeaconMotionMode.Ripple, Modifier.testTag("agent-phone-done-signal"), 144.dp)
                        Surface(Modifier.size(80.dp), shape = androidx.compose.foundation.shape.CircleShape, color = MaterialTheme.colorScheme.primary) {
                            Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Check, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onPrimary) }
                        }
                    }
                },
                actions = { SetupPrimaryButton(uiText("Done"), actions.close, Modifier.testTag("agent-phone-done")) })
        }
    }
}

/** The prerequisites the owner confirms (§8 Before you start; the owner's disclaimer), three short rows, and the plain loopback fact. */
@Composable private fun BeforeContent() {
    SetupContainer(Modifier.testTag("agent-phone-needs")) {
        NeedRow(Icons.Rounded.Computer, uiText("A real computer with about 16 GB free space and 8 GB memory"))
        NeedRow(Icons.Rounded.PowerSettingsNew, uiText("Hermes stays on, with sign-in turned on"))
        NeedRow(Icons.Rounded.AccountBalance, uiText("Bank and wallet apps don't work on it"))
    }
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Lan, null, Modifier.padding(top = 2.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(uiText("Apps on the phone can reach programs on your Hermes computer that don't ask for a sign-in."), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun ConfirmRow(state: AgentPhoneSetupState, actions: AgentPhoneSetupActions) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(state.confirmed, role = Role.Checkbox, onValueChange = actions.confirm).testTag("agent-phone-confirm"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Checkbox(state.confirmed, onCheckedChange = null)
        Text(uiText("This computer has these"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

@Composable private fun NeedRow(icon: ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun ChecksPage(state: AgentPhoneSetupState, actions: AgentPhoneSetupActions, modifier: Modifier, step: Int) {
    val checks = state.checks
    val pass = agentPhoneChecksPass(checks, state.continued)
    // Each result says its own title and line (AGENTUX-121 P2-2); while checking, only how long it takes.
    val (title, body) = agentPhoneChecksWords(state.checking, checks, pass)
    SetupShell(step, uiText(title), modifier, uiText(body),
        content = {
            if (checks == null && !state.checking) Unit
            else if (state.checking || checks == null) {
                Box(Modifier.fillMaxWidth().testTag("agent-phone-checking"), contentAlignment = Alignment.Center) { BeaconDustCore(48.dp) }
            } else SetupContainer(Modifier.testTag("agent-phone-check-rows")) {
                // What needs the owner comes first (fixes, then notes, then what couldn't be read), so the page opens on it at any text size.
                checks.rows.sortedBy { agentPhoneCheckOrder(it.status) }.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CheckRow(row, state.continued, actions)
                }
            }
            state.checksLine?.let { line ->
                // With no rows to show, Check again below is the way on; the line needs no button of its own.
                if (checks == null) SetupStatusLine(agentPhoneText(line), error = true, modifier = Modifier.testTag("agent-phone-line")) else SetupLine(line, actions)
            }
        },
        actions = {
            if (state.checking) SetupPrimaryButton(uiText("Checking…"), {}, Modifier.testTag("agent-phone-checking-button"), loading = true)
            else if (checks == null) SetupPrimaryButton(uiText("Check now"), actions.check, Modifier.testTag("agent-phone-check-again"))
            else {
                SetupPrimaryButton(uiText("Continue"), actions.toTerms, Modifier.testTag("agent-phone-checks-continue"), enabled = pass)
                SetupTextAction(uiText("Check now"), actions.check, Modifier.testTag("agent-phone-check-again"))
            }
        })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CheckRow(row: AgentPhoneCheckRow, continued: Boolean, actions: AgentPhoneSetupActions) {
    val palette = rememberSettingsStatusPalette()
    val warned = row.status == AgentPhoneCheckStatus.Warn && row.needsContinue
    val status = when (row.status) {
        AgentPhoneCheckStatus.Ready -> SettingsStatus.Ok
        AgentPhoneCheckStatus.Warn -> SettingsStatus.Attention
        AgentPhoneCheckStatus.Fail -> SettingsStatus.Error
        AgentPhoneCheckStatus.Unknown -> SettingsStatus.Off
    }
    val name = agentPhoneCheckNames[row.id]?.let { uiText(it) } ?: agentPhoneLine(row.line, code = row.lineCode, args = row.lineArgs)?.let { agentPhoneText(it) }.orEmpty()
    val word = if (warned && continued) uiText("Accepted") else uiText(agentPhoneCheckWord(row.status))
    val line = agentPhoneLine(row.line, code = row.lineCode, args = row.lineArgs)?.takeIf { row.status != AgentPhoneCheckStatus.Ready && agentPhoneCheckNames.containsKey(row.id) }
    val fix = agentPhoneLine(row.fix, code = row.fixCode)?.takeIf { row.status != AgentPhoneCheckStatus.Ready }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("agent-phone-check:${row.id}"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // The status word sits under the name, so a long name in any language keeps the row's width; the row is read once, name then word.
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(settingsStaticGlyph(status), null, Modifier.padding(top = 2.dp).size(20.dp), tint = palette.ink(status))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(word, style = MaterialTheme.typography.labelMedium, color = palette.ink(status))
            }
        }
        // The reason and its one fix, at body size: these are the lines the owner acts on.
        line?.let { Text(agentPhoneText(it), Modifier.padding(start = 32.dp), style = MaterialTheme.typography.bodyMedium) }
        fix?.let { Text(agentPhoneText(it), Modifier.padding(start = 32.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        // A shortened line keeps the host's whole reason one tap away (the developer-tools line's why, say).
        val detailed = listOfNotNull(line, fix).filter { it.hasDetails }
        var details by rememberSaveable(row.id) { mutableStateOf(false) }
        if (details) for (said in detailed) Text(agentPhoneDetails(said), Modifier.padding(start = 32.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val how = row.how ?: AGENT_PHONE_SIGN_IN_GUIDE.takeIf { row.id == "sign_in" && row.status == AgentPhoneCheckStatus.Fail && line?.action == AgentPhoneAction.How }
        if (how != null || warned || detailed.isNotEmpty()) FlowRow(Modifier.padding(start = 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (how != null && row.status != AgentPhoneCheckStatus.Ready) TextButton({ actions.openLink(how) }, Modifier.heightIn(min = 48.dp).testTag("agent-phone-how:${row.id}")) { Text(uiText("How")) }
            if (warned) {
                TextButton(actions.check, Modifier.heightIn(min = 48.dp).testTag("agent-phone-recheck:${row.id}")) { Text(uiText("Check now")) }
                if (!continued) TextButton(actions.continueWarning, Modifier.heightIn(min = 48.dp).testTag("agent-phone-continue:${row.id}")) { Text(uiText("Continue")) }
            }
            if (detailed.isNotEmpty()) TextButton({ details = !details }, Modifier.heightIn(min = 48.dp).testTag("agent-phone-details:${row.id}")) { Text(uiText(if (details) "Hide details" else "Details")) }
        }
    }
}

/** The terms page's line: the host's own sentence for the same step, one key for both. */
private val TERMS_BODY = AgentPhoneLines.templates.getValue("install.licences")

@Composable private fun TermsPage(state: AgentPhoneSetupState, actions: AgentPhoneSetupActions, modifier: Modifier, step: Int) {
    val terms = state.terms
    SetupShell(step, uiText("Google's terms"), modifier, uiText(TERMS_BODY),
        content = {
            when {
                terms != null -> {
                    // What the phone can grow to on the computer (the install, its data and its quick start), not only the install itself.
                    if (terms.downloadBytes != null)
                        Text(uiText("About {0} to download, up to {1} on the computer").replace("{0}", bytes(terms.downloadBytes)).replace("{1}", bytes(AGENT_PHONE_DISK_BYTES)),
                            Modifier.testTag("agent-phone-terms-sizes"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    // Google's words exactly as the server fetched them: never translated, left to right in every language.
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Surface(Modifier.fillMaxWidth().testTag("agent-phone-terms-text"), shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                terms.licences.forEach { licence ->
                                    Text(licence.text, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                    }
                }
                state.termsLine != null -> SetupLine(state.termsLine, actions, fallback = actions.loadTerms)
                else -> {
                    Box(Modifier.fillMaxWidth().testTag("agent-phone-terms-loading"), contentAlignment = Alignment.Center) { BeaconDustCore(40.dp) }
                    Text(uiText("Getting Google's terms…"), Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        actions = {
            SetupPrimaryButton(uiText("Accept and download"), actions.accept, Modifier.testTag("agent-phone-accept"), enabled = terms != null, loading = state.accepting)
            SetupTextAction(uiText("Not now"), actions.close, Modifier.testTag("agent-phone-terms-not-now"))
        })
}

@Composable private fun DownloadPage(state: AgentPhoneSetupState, actions: AgentPhoneSetupActions, modifier: Modifier, step: Int) {
    val install = state.install
    val failed = install.state == AgentPhoneInstallState.Failed
    val title = when (install.state) {
        AgentPhoneInstallState.Paused -> uiText("Paused")
        AgentPhoneInstallState.Cancelled -> uiText("Cancelled")
        AgentPhoneInstallState.Failed -> uiText("The download stopped")
        else -> uiText("Getting the phone ready")
    }
    val body = when (install.state) {
        AgentPhoneInstallState.Paused -> uiText("Resume any time. Nothing is lost.")
        AgentPhoneInstallState.Cancelled -> uiText("Nothing was kept on the computer.")
        AgentPhoneInstallState.Failed -> ""
        else -> uiText("It keeps going if you close this.")
    }
    SetupShell(step, title, modifier, body,
        content = {
            AgentPhoneDust(working = install.state == AgentPhoneInstallState.Running || install.state == AgentPhoneInstallState.Done)
            if (install.state != AgentPhoneInstallState.Cancelled) {
                AgentPhoneProgress(install, state.minutesLeft)
                AgentPhonePhases(install)
            }
            val line = (state.installLine ?: agentPhoneLine(install.line, code = install.lineCode, args = install.lineArgs)?.takeIf { failed || install.state == AgentPhoneInstallState.Running })
                // Under "The download stopped", the line says only what to do (P3-7: not "Download stopped" twice).
                ?.let { if (failed && it.template == AgentPhoneLines.templates["install.offline"]) it.copy(template = AgentPhoneLines.templates.getValue("check.network_fix")) else it }
            // The failed page's one action is its primary button, so the line stands alone; a running install's line ("A download was damaged…") is news, not an error.
            line?.let { SetupStatusLine(agentPhoneText(it), error = failed, modifier = Modifier.testTag("agent-phone-line")) }
            if (failed) agentPhoneLine(install.fix, code = install.fixCode)?.let { Text(agentPhoneText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        },
        actions = {
            when (install.state) {
                AgentPhoneInstallState.Paused -> {
                    SetupPrimaryButton(uiText("Resume"), actions.resume, Modifier.testTag("agent-phone-resume"), loading = state.installBusy)
                    SetupTextAction(uiText("Cancel"), actions.cancel, Modifier.testTag("agent-phone-cancel"), enabled = !state.installBusy)
                }
                AgentPhoneInstallState.Failed -> {
                    val line = state.installLine ?: agentPhoneLine(install.line, code = install.lineCode, args = install.lineArgs)
                    when (val action = agentPhoneFailedAction(line?.action, install.retry)) {
                        AgentPhoneAction.TryAgain -> SetupPrimaryButton(uiText("Try again"), actions.resume, Modifier.testTag("agent-phone-resume"), loading = state.installBusy)
                        AgentPhoneAction.CheckAgain -> SetupPrimaryButton(uiText("Check now"), actions.check, Modifier.testTag("agent-phone-check-again"))
                        AgentPhoneAction.StartAgain -> SetupPrimaryButton(uiText("Start again"), actions.startAgain, Modifier.testTag("agent-phone-start-over"))
                        else -> SetupPrimaryButton(uiText(agentPhoneActionWord(action)), { line?.let(actions.line) }, Modifier.testTag("agent-phone-line-action"))
                    }
                    SetupTextAction(uiText("Cancel"), actions.cancel, Modifier.testTag("agent-phone-cancel"), enabled = !state.installBusy)
                }
                AgentPhoneInstallState.Cancelled, AgentPhoneInstallState.Idle -> SetupPrimaryButton(uiText("Start again"), actions.startAgain, Modifier.testTag("agent-phone-start-over"))
                else -> {
                    OutlinedButton(actions.pause, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("agent-phone-pause"), enabled = !state.installBusy && install.state == AgentPhoneInstallState.Running) { Text(uiText("Pause")) }
                    SetupTextAction(uiText("Cancel"), actions.cancel, Modifier.testTag("agent-phone-cancel"), enabled = !state.installBusy)
                }
            }
        })
}

/** The working dust: Beacon's gold core turning while something runs, the same core held still and dimmed when it stopped. */
@Composable private fun AgentPhoneDust(working: Boolean) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).testTag("agent-phone-dust"), contentAlignment = Alignment.Center) {
        if (working) BeaconDustCore(88.dp) else StillDust(88.dp, MaterialTheme.colorScheme.primary.copy(alpha = .55f))
    }
}

/** BeaconDustCore's disc, ring and specks at rest (its clock at zero): the same mark, not moving. */
@Composable private fun StillDust(size: androidx.compose.ui.unit.Dp, color: androidx.compose.ui.graphics.Color) {
    val specks = androidx.compose.runtime.remember { val random = kotlin.random.Random(11); FloatArray(30) { random.nextFloat() } }
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2f
        val unit = this.size.minDimension / 24.dp.toPx()
        drawCircle(color.copy(alpha = color.alpha * .16f), r)
        drawCircle(color.copy(alpha = color.alpha * .45f), r - .75.dp.toPx() * unit, style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx() * unit))
        for (i in 0 until 10) {
            val orbit = r * (.32f + .5f * specks[i * 3])
            val angle = specks[i * 3 + 1] * 6.2832f
            val speck = ((.9f + .8f * specks[i * 3 + 2]) * unit).dp.toPx().coerceAtLeast(.8.dp.toPx())
            drawCircle(color, speck, center + androidx.compose.ui.geometry.Offset(kotlin.math.cos(angle) * orbit, kotlin.math.sin(angle) * orbit))
        }
        drawCircle(color, r * .2f)
    }
}

/** "1.2 of 2.4 GB · about 6 min left" over a bar that reads its own fraction to TalkBack. */
@Composable private fun AgentPhoneProgress(install: AgentPhoneInstall, minutesLeft: Long?) {
    val total = install.totalBytes
    val done = (install.doneBytes ?: 0L).coerceAtMost(total ?: Long.MAX_VALUE)
    val fraction = if (total != null && total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    val amount = if (total != null) uiText("{0} of {1}").replace("{0}", bytes(done)).replace("{1}", bytes(total)) else ""
    val left = when {
        install.phase != AgentPhoneInstallPhase.Download || install.state != AgentPhoneInstallState.Running -> ""
        minutesLeft == null -> ""
        minutesLeft <= 0 -> uiText("less than a minute left")
        else -> uiText("about {0} min left").replace("{0}", "$minutesLeft")
    }
    val words = listOf(amount, left).filter(String::isNotBlank).joinToString(" · ")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bar = Modifier.fillMaxWidth().height(6.dp).testTag("agent-phone-progress").semantics {
            if (fraction != null) progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            if (words.isNotBlank()) contentDescription = words
        }
        val track = MaterialTheme.colorScheme.surfaceVariant
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = bar, trackColor = track, drawStopIndicator = {})
        else LinearProgressIndicator(bar, trackColor = track)
        // Not a live region: the bytes change on every poll, and TalkBack would read them all download long.
        if (words.isNotBlank()) Text(words, Modifier.testTag("agent-phone-progress-words"),
            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"))
    }
}

/** Download → Check → Set up: the install's three phases, the current one marked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AgentPhonePhases(install: AgentPhoneInstall) {
    val order = listOf(AgentPhoneInstallPhase.Download, AgentPhoneInstallPhase.Check, AgentPhoneInstallPhase.SetUp).zip(AgentPhonePhaseWords)
    val current = when {
        install.state == AgentPhoneInstallState.Done -> order.size
        install.phase == null -> 0
        else -> order.indexOfFirst { it.first == install.phase }
    }
    val palette = rememberSettingsStatusPalette()
    val running = install.state == AgentPhoneInstallState.Running
    val words = order.mapIndexed { index, (_, word) -> uiText(word) to uiText(if (index < current) "Done" else if (index == current) "Now" else "Next") }
    // One spoken summary that changes only when the phase does ("Download, Done. Check, Now. Set up, Next."), announced politely.
    val summary = words.joinToString(". ") { (label, state) -> "$label, $state" }
    FlowRow(Modifier.fillMaxWidth().testTag("agent-phone-phases").clearAndSetSemantics { contentDescription = summary; liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        words.forEachIndexed { index, (label, _) ->
            val done = index < current
            val now = index == current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    done -> Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = palette.ok)
                    // The current phase keeps its dust: turning while it runs, held still when paused or stopped.
                    now && running -> BeaconDustCore(18.dp)
                    now -> StillDust(18.dp, MaterialTheme.colorScheme.primary)
                    else -> Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .5f), androidx.compose.foundation.shape.CircleShape))
                    }
                }
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (done || now) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A server line with its one action, in the setup's look: an error icon when it stopped something. */
@Composable private fun SetupLine(line: AgentPhoneLine, actions: AgentPhoneSetupActions, error: Boolean = true, fallback: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().testTag("agent-phone-line"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SetupStatusLine(agentPhoneText(line), error = error)
        val word = agentPhoneActionWord(line.action)
        if (word.isNotBlank()) TextButton({ actions.line(line) }, Modifier.padding(start = 18.dp).heightIn(min = 48.dp).testTag("agent-phone-line-action")) { Text(uiText(word)) }
        else if (fallback != null) TextButton(fallback, Modifier.padding(start = 18.dp).heightIn(min = 48.dp).testTag("agent-phone-line-action")) { Text(uiText("Try again")) }
    }
}

/** "The phone didn't start." with Details, which shows the server's own words. */
@Composable private fun LineWithDetails(line: AgentPhoneLine, modifier: Modifier = Modifier) {
    var open by rememberSaveable(line.raw) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SetupStatusLine(agentPhoneText(line), error = true)
        if (line.hasDetails) {
            TextButton({ open = !open }, Modifier.padding(start = 18.dp).heightIn(min = 48.dp).testTag("agent-phone-details")) { Text(uiText(if (open) "Hide details" else "Details")) }
            if (open) Text(agentPhoneDetails(line), Modifier.padding(start = 26.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The checklist in the live view's "Setup steps" (L5, joined 17 Nov): the phone page's rows, from the phone's status read when the
 * sheet opens and the owner's ticks on this phone (the same store the page uses, per account and profile). [client] is the view's own
 * sign-in; with none, or before the status answers, the sheet shows the rows as the ticks alone give them.
 */
@Composable internal fun AgentPhoneViewChecklist(client: suspend () -> dev.hermes.android.data.BeaconClient?) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var ticks by remember { mutableStateOf<AgentPhoneTicks?>(null) }
    var ticked by remember { mutableStateOf(emptySet<String>()) }
    var facts by remember { mutableStateOf<dev.hermes.android.data.AgentPhoneFacts?>(null) }
    LaunchedEffect(Unit) {
        val scoped = client() ?: return@LaunchedEffect
        val store = AgentPhoneTicks(context.applicationContext.getSharedPreferences(AgentPhoneTicks.PREFS, android.content.Context.MODE_PRIVATE),
            AgentPhoneTicks.key(scoped.scope, scoped.auth.profile))
        ticks = store
        ticked = store.read()
        facts = (dev.hermes.android.data.AgentPhoneApi(dev.hermes.android.data.ClientAgentPhoneEndpoint(scoped)).status() as? dev.hermes.android.data.AgentPhoneResult.Done)
            ?.value?.first?.facts
    }
    AgentPhoneChecklist(agentPhoneChecklist(facts, ticked), onTick = { task, done -> ticks?.let { it.set(task.key, done); ticked = it.read() } })
}

/**
 * The setup checklist (§8 Setup view): each step ticks itself from what the phone reads; where it can't read one, the owner ticks it
 * ([onTick]; null shows the list without hand ticks, as a preview). Shared with the live view (L5), which shows it beside the phone.
 */
@Composable internal fun AgentPhoneChecklist(rows: List<AgentPhoneTaskRow>, onTick: ((AgentPhoneTask, Boolean) -> Unit)?, modifier: Modifier = Modifier, contained: Boolean = true) {
    // In the setup pages it is its own quiet card; on the phone page the section's card already holds it ([contained] false).
    if (contained) SetupContainer(modifier.testTag("agent-phone-checklist")) { AgentPhoneChecklistRows(rows, onTick) }
    else Column(modifier.fillMaxWidth().testTag("agent-phone-checklist"), verticalArrangement = Arrangement.spacedBy(4.dp)) { AgentPhoneChecklistRows(rows, onTick) }
}

@Composable private fun AgentPhoneChecklistRows(rows: List<AgentPhoneTaskRow>, onTick: ((AgentPhoneTask, Boolean) -> Unit)?) {
    val palette = rememberSettingsStatusPalette()
    run {
        rows.forEach { row ->
            val label = uiText(row.task.words)
            val state = uiText(if (row.done) "Done" else "To do")
            val tickable = onTick != null && row.canTick
            val rowModifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("agent-phone-task:${row.task.key}")
                .then(if (tickable) Modifier.toggleable(row.done, role = Role.Checkbox) { onTick?.invoke(row.task, it) } else Modifier.semantics(mergeDescendants = true) { stateDescription = state })
            Row(rowModifier.padding(vertical = 4.dp), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (row.done) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(22.dp), tint = palette.ok)
                else Icon(Icons.Rounded.RadioButtonUnchecked, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                // The hand tick's words sit under the step, so a long step in any language keeps the whole width.
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(label, style = MaterialTheme.typography.bodyLarge, color = if (row.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (tickable && !row.done) Text(uiText("Mark done"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
