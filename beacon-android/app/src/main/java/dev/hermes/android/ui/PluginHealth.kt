package dev.hermes.android.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.updates.HermesPluginInstaller
import dev.hermes.android.updates.NEWER_THAN_RELEASE
import dev.hermes.android.updates.RepairFinding
import dev.hermes.android.updates.RepairUi
import dev.hermes.android.updates.RepairStage
import dev.hermes.android.updates.readRepairFinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** The plugin's own account of itself (`GET /mobile/health`, owner only), kept per account and profile so the section opens already filled. */
internal const val PLUGIN_HEALTH_KEY = "plugin-health"
internal const val PLUGIN_HEALTH_PATH = "/api/plugins/beacon/mobile/health"

/** Where tapping a row leads: the page where its problem is fixed, or a fresh check. A row with [None] has nothing for the owner to do. */
internal enum class HealthTarget { None, Notifications, VoiceCalls, AppUpdates, CheckNow }

/** One row's verdict. [text] is the English word the row shows through uiText; [target] is where a tap on the row goes. */
internal data class HealthLine(val status: SettingsStatus, val text: String, val target: HealthTarget = HealthTarget.None) {
    /** A row that is fine, switched off on purpose or at work asks nothing of the owner. */
    val needsAttention: Boolean get() = status == SettingsStatus.Attention || status == SettingsStatus.Error
}

/** What the Beacon plugin section says. A row the plugin could not observe is null and not shown. */
internal data class PluginHealth(val version: String, val dashboard: HealthLine?, val gateway: HealthLine?, val calls: HealthLine, val notifications: HealthLine) {
    /** The worst row decides the pill; a row that is working, or switched off on purpose, does not. */
    val overall: SettingsStatus get() {
        val all = listOfNotNull(dashboard, gateway, calls, notifications).map { it.status }
        return when {
            SettingsStatus.Error in all -> SettingsStatus.Error
            SettingsStatus.Attention in all || version.isEmpty() -> SettingsStatus.Attention
            else -> SettingsStatus.Ok
        }
    }
    /** The first row, in the order shown, that asks something of the owner: its English label, which names the cause beside the verdict. */
    val cause: String? get() = listOf(dashboard to "Hermes dashboard", gateway to "Hermes gateway", calls to "Calls", notifications to "Notifications")
        .firstOrNull { it.first?.needsAttention == true }?.second
    val overallText: String get() = when (overall) {
        SettingsStatus.Ok -> "Healthy"
        SettingsStatus.Attention -> "Needs attention"
        else -> "Not working"
    }
}

/** Every fixed word the section can show; each goes through uiText and must exist in all languages. */
internal val PluginHealthStrings = listOf(
    "Beacon plugin", "Health", "Version", "Hermes dashboard", "Hermes gateway", "Calls", "Notifications", "Check now", "Checking…", "Retry", "Can't reach Hermes",
    "Healthy", "Needs attention", "Not working", "Up to date", "Needs restart", "Running", "Working", "Stopped", "Ready", "Add key",
    "Slow to start", "Hermes is updating", "Hermes is busy", "Hermes took too long", "Not ready", "Keep connected is on", "Keep connected is off", "Not set up",
    "Beacon isn't installed on this Hermes", "Update Beacon on your Hermes", "Hermes answered in {0} ms", "Unknown",
)

private fun JSONObject.flag(name: String): Boolean? = opt(name) as? Boolean

/**
 * Maps the plugin's answer to rows. Only what the answer says is shown: a missing part is null, never an assumed state.
 * [keepConnected] and [voiceKeySaved] are what this phone itself knows (notifications can arrive without push, calls need the phone's key).
 */
internal fun pluginHealthOf(answer: JSONObject?, keepConnected: Boolean, voiceKeySaved: Boolean? = null): PluginHealth? {
    val plugin = answer?.optJSONObject("plugin") ?: return null
    val reported = listOf("installed", "running").map { plugin.optString(it).trim() }
    // A plugin that answers with no version at all (1.2.2 on a Hermes without PyYAML) is old, not missing: it still gets its rows and Repair.
    val version = reported.firstOrNull { PLUGIN_VERSION_PATTERN.matches(it) } ?: if (reported.all { it.isEmpty() }) "" else return null
    val calls = answer.optJSONObject("calls") ?: return null
    val push = answer.optJSONObject("push") ?: return null
    // A restart is done from App updates; a row that is wrong in some other way is read again.
    val dashboard = answer.optJSONObject("dashboard")?.flag("current")?.let { current ->
        if (current) HealthLine(SettingsStatus.Ok, "Up to date") else HealthLine(SettingsStatus.Attention, "Needs restart", HealthTarget.AppUpdates)
    }
    val gateway = answer.optJSONObject("gateway")?.takeIf { it.flag("known") == true }?.let { value ->
        when {
            value.flag("running") != true -> HealthLine(SettingsStatus.Error, "Stopped", HealthTarget.CheckNow)
            value.flag("current") == false -> HealthLine(SettingsStatus.Attention, "Needs restart", HealthTarget.AppUpdates)
            // A gateway at work is normal: neither a fault nor a success, so it never turns the verdict.
            value.flag("busy") == true -> HealthLine(SettingsStatus.Working, "Working")
            else -> HealthLine(SettingsStatus.Ok, "Running")
        }
    }
    val reason = calls.optString("reason")
    val callsLine = when {
        calls.flag("ready") == true && voiceKeySaved == false -> HealthLine(SettingsStatus.Attention, "Add key", HealthTarget.VoiceCalls)
        calls.flag("ready") == true && reason.isEmpty() -> HealthLine(SettingsStatus.Ok, "Ready")
        calls.flag("ready") == true -> HealthLine(SettingsStatus.Attention, "Slow to start", HealthTarget.CheckNow)
        reason == "updating" -> HealthLine(SettingsStatus.Attention, "Hermes is updating", HealthTarget.CheckNow)
        reason == "busy" -> HealthLine(SettingsStatus.Working, "Hermes is busy")
        reason == "timeout" -> HealthLine(SettingsStatus.Error, "Hermes took too long", HealthTarget.CheckNow)
        else -> HealthLine(SettingsStatus.Error, "Not ready", HealthTarget.CheckNow)
    }
    // Keep connected off is the owner's choice (the setup wizard starts there): the row shows its hint and opens Notifications,
    // but it is Off, so on its own it never turns the verdict. A phone the plugin has never seen is a real gap.
    val notifications = when {
        push.flag("ready") == true -> HealthLine(SettingsStatus.Ok, "Ready")
        keepConnected -> HealthLine(SettingsStatus.Ok, "Keep connected is on")
        push.optString("reason") == "no_phone" -> HealthLine(SettingsStatus.Attention, "Not set up", HealthTarget.Notifications)
        else -> HealthLine(SettingsStatus.Off, "Keep connected is off", HealthTarget.Notifications)
    }
    return PluginHealth(version, dashboard, gateway, callsLine, notifications)
}

/** The text under Check now once Hermes has answered. */
@Composable internal fun roundTripText(milliseconds: Long): String = uiText("Hermes answered in {0} ms").replace("{0}", milliseconds.toString())

/** A verdict row: plain when nothing is asked of the owner, else a tappable row that goes where the problem is fixed. */
@Composable private fun HealthRow(label: String, icon: ImageVector, line: HealthLine, tag: String, onOpen: (HealthTarget) -> Unit, modifier: Modifier = Modifier) {
    val word = uiText(line.text)
    if (line.target == HealthTarget.None) SettingsValueRow(label, modifier, icon = icon, status = line.status, statusLabel = word, testTag = tag)
    else SettingsNavRow(label, { onOpen(line.target) }, modifier, icon = icon, status = line.status, statusLabel = word, testTag = tag,
        trailingIcon = if (line.target == HealthTarget.CheckNow) Icons.Rounded.Refresh else null)
}

/**
 * The rows themselves, with no state of their own, so a screenshot or test can show any verdict. [onOpen] receives the target of a tapped
 * row; [resultSignal] counts finished checks, and each new count brings Check now (and the answer under it) into view.
 */
@Composable internal fun PluginHealthRows(health: PluginHealth, roundTrip: Long?, checking: Boolean, onOpen: (HealthTarget) -> Unit = {}, resultSignal: Int = 0, onCheck: () -> Unit) {
    val result = remember { BringIntoViewRequester() }
    LaunchedEffect(resultSignal) { if (resultSignal > 0) result.bringIntoView() }
    // A verdict that names a cause leads to that row, which is further down the page.
    val problem = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    val first = health.cause
    fun mark(label: String) = if (label == first) Modifier.bringIntoViewRequester(problem) else Modifier
    val verdict = uiText(health.overallText)
    if (first == null || health.overall == SettingsStatus.Ok) SettingsValueRow(uiText("Beacon plugin"), icon = Icons.Rounded.MonitorHeart, status = health.overall, statusLabel = verdict, testTag = "plugin-health-status")
    else {
        val largeText = LocalDensity.current.fontScale >= 1.5f
        val cause = uiText(first)
        SettingsNavRow(uiText("Beacon plugin"), { scope.launch { problem.bringIntoView() } }, icon = if (largeText) null else Icons.Rounded.MonitorHeart,
            value = if (largeText) cause else "", valueMaxLines = 2, status = health.overall,
            statusLabel = if (largeText) verdict else "$verdict · $cause", testTag = "plugin-health-status")
    }
    SettingsDivider()
    SettingsValueRow(uiText("Version"), icon = Icons.Rounded.Tag, value = health.version.ifEmpty { uiText("Unknown") }, testTag = "plugin-health-version")
    // The dashboard and the gateway only appear when one of them needs the owner; "Healthy" above already says they are fine.
    health.dashboard?.takeIf { it.needsAttention }?.let { SettingsDivider(); HealthRow(uiText("Hermes dashboard"), Icons.Rounded.Dashboard, it, "plugin-health-dashboard", onOpen, mark("Hermes dashboard")) }
    health.gateway?.takeIf { it.needsAttention }?.let { SettingsDivider(); HealthRow(uiText("Hermes gateway"), Icons.Rounded.Router, it, "plugin-health-gateway", onOpen, mark("Hermes gateway")) }
    SettingsDivider()
    HealthRow(uiText("Calls"), Icons.Rounded.Call, health.calls, "plugin-health-calls", onOpen, mark("Calls"))
    SettingsDivider()
    HealthRow(uiText("Notifications"), Icons.Rounded.NotificationsNone, health.notifications, "plugin-health-notifications", onOpen, mark("Notifications"))
    SettingsDivider()
    SettingsActionRow(uiText("Check now"), onCheck, Modifier.bringIntoViewRequester(result), icon = Icons.Rounded.Refresh, value = roundTrip?.let { roundTripText(it) }.orEmpty(),
        busy = checking, busyLabel = uiText("Checking…"), testTag = "plugin-health-check")
}

/** The line beside the Repair button for what Hermes says is wrong with Beacon; null when nothing is. */
private fun RepairFinding.line(): String? = when (this) {
    RepairFinding.NotInstalled -> "Beacon isn't installed on this Hermes"
    RepairFinding.TurnedOff -> "Beacon is turned off on Hermes."
    RepairFinding.NotRunning -> "Beacon is on Hermes but isn't running."
    RepairFinding.OutOfDate -> "Update Beacon on your Hermes"
    RepairFinding.StillOld -> "Needs restart"
    RepairFinding.Healthy, RepairFinding.Newer -> null
}

/**
 * Repair Beacon: one row, one confirm sheet, one result line. It puts the published plugin back through Hermes' own routes and never runs by
 * itself. The sheet says only what will really happen: the plugin is reinstalled and Beacon then restarts the Hermes dashboard on its own.
 * No state of its own besides the sheet, so a screenshot or test can show any stage; [onRepair] runs once, after the owner confirms.
 */
@Composable internal fun PluginRepairContent(repair: RepairUi, onRepair: () -> Unit, finding: RepairFinding? = null) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    when (repair.stage) {
        RepairStage.Failed -> SettingsErrorRow(uiText(repair.problem?.text.orEmpty()), { confirming = true }, uiText("Retry"), retryTestTag = "plugin-repair-retry")
        // A Beacon past the latest release is left as it is: saying "repaired" would claim a change that did not happen.
        RepairStage.Done -> SettingsValueRow(uiText(if (repair.finding == RepairFinding.Newer) NEWER_THAN_RELEASE else "Beacon is repaired."), icon = Icons.Rounded.Build, status = SettingsStatus.Ok, testTag = "plugin-repair-done")
        else -> SettingsActionRow(uiText("Repair Beacon"), { confirming = true }, icon = Icons.Rounded.Build, value = finding?.line()?.let { uiText(it) }.orEmpty(),
            busy = repair.working, busyLabel = uiText(when (repair.stage) {
                RepairStage.Repairing -> "Repairing Beacon…"
                RepairStage.Waiting -> "Reconnecting…"
                else -> "Checking…"
            }), testTag = "plugin-repair")
    }
    if (confirming) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(uiText("Repair Beacon")) },
        text = { Text(uiText("Reinstalls the latest Beacon. The Hermes dashboard restarts briefly.")) },
        confirmButton = { TextButton(onClick = { confirming = false; onRepair() }, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("plugin-repair-confirm")) { Text(uiText("Repair")) } },
        dismissButton = { TextButton(onClick = { confirming = false }, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("plugin-repair-cancel")) { Text(uiText("Cancel")) } },
    )
}

/** The Repair row wired to the signed-in Hermes: the controller's stage in, one repair out. */
@Composable internal fun PluginRepairRow(vm: BeaconViewModel, finding: RepairFinding? = null) {
    val repair by vm.pluginRepair.state.collectAsStateWithLifecycle()
    // A finished repair is shown once; the next time the section opens it starts fresh.
    DisposableEffect(vm) { onDispose { if (vm.pluginRepair.state.value.stage == RepairStage.Done) vm.pluginRepair.reset() } }
    PluginRepairContent(repair, { vm.pluginRepair.repair() }, finding)
}

/** Settings › Connection › Beacon plugin: opens from the last answer kept for this account and profile, then reads again in the background. */
@Composable internal fun PluginHealthSection(vm: BeaconViewModel, pluginAbsent: Boolean) {
    val data by vm.data.collectAsStateWithLifecycle()
    val scopeKey = vm.client?.scope?.key.orEmpty()
    val context = LocalContext.current.applicationContext
    var keepConnected by remember(context) { mutableStateOf(KeepConnectedService.isEnabled(context)) }
    DisposableEffect(context) {
        val preferences = context.getSharedPreferences(KeepConnectedService.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeepConnectedService.KEY_ENABLED) keepConnected = KeepConnectedService.isEnabled(context)
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val voiceKeySaved by produceState<Boolean?>(null, context) {
        value = withContext(Dispatchers.IO) { runCatching { VoiceKeyStore(context).let { it.hasKey(it.settings().provider) } }.getOrNull() }
    }
    var checking by remember(scopeKey) { mutableStateOf(false) }
    var asked by remember(scopeKey) { mutableStateOf(false) }
    var failed by remember(scopeKey) { mutableStateOf(false) }
    var oldPlugin by remember(scopeKey) { mutableStateOf(false) }
    var notOwner by remember(scopeKey) { mutableStateOf(false) }
    var roundTrip by remember(scopeKey) { mutableStateOf<Long?>(null) }
    var finishedChecks by remember(scopeKey) { mutableIntStateOf(0) }
    val requests = rememberCoroutineScope()
    // Check now reads past the plugin's few-second memory and times the whole trip from this phone; the quiet read on opening does neither.
    fun read(fresh: Boolean, bypass: Boolean = fresh) {
        val active = vm.client
        if (active == null || checking) return
        checking = true
        asked = fresh
        requests.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val answer = active.nativeRequest(PLUGIN_HEALTH_PATH, params = if (bypass) mapOf("fresh" to "1") else emptyMap())
                val elapsed = SystemClock.elapsedRealtime() - started
                if (vm.client === active) {
                    vm.keepScreen(active.scope, PLUGIN_HEALTH_KEY, answer)
                    failed = false; oldPlugin = false; notOwner = false
                    if (fresh) { roundTrip = elapsed; finishedChecks++ }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (vm.client === active) {
                    val status = (error as? ApiFailure)?.status
                    notOwner = status == 403
                    oldPlugin = status == 404
                    failed = status != 403 && status != 404
                    roundTrip = null
                }
            } finally {
                checking = false; asked = false
            }
        }
    }
    LaunchedEffect(scopeKey, pluginAbsent) { if (!pluginAbsent && scopeKey.isNotBlank()) read(false) }
    // A row that needs the owner leads to where it is fixed; the others read the plugin again.
    val open: (HealthTarget) -> Unit = { target ->
        when (target) {
            HealthTarget.Notifications -> vm.more("Notifications")
            HealthTarget.VoiceCalls -> vm.more("Beacon")
            HealthTarget.AppUpdates -> vm.more("App updates")
            HealthTarget.CheckNow -> read(true)
            HealthTarget.None -> Unit
        }
    }
    val health = remember(data[PLUGIN_HEALTH_KEY], keepConnected, voiceKeySaved) { pluginHealthOf(data[PLUGIN_HEALTH_KEY], keepConnected, voiceKeySaved) }
    val repair by vm.pluginRepair.state.collectAsStateWithLifecycle()
    // A finished repair changed what the plugin says about itself (its version, the dashboard's start), so the rows read it again instead of keeping the old answer.
    LaunchedEffect(repair.stage) { if (repair.stage == RepairStage.Done && !pluginAbsent) read(false, bypass = true) }
    // When Beacon's routes do not answer, Hermes' own list tells "not installed" from "installed but off or not running".
    val found by produceState<RepairFinding?>(null, scopeKey, pluginAbsent, repair.working) {
        value = null
        val active = vm.client
        if (!pluginAbsent || active == null || repair.working) return@produceState
        val detached = active.detached()
        value = try { withContext(Dispatchers.IO) { readRepairFinding(HermesPluginInstaller(detached)) } } finally { detached.closeNative() }
    }
    // The route answers the owner only; to anyone else the plugin has nothing to say here.
    if (notOwner && !pluginAbsent) return
    SettingsSection(uiText("Beacon plugin"), testTag = "settings-section:Plugin") {
        when {
            pluginAbsent && (found == RepairFinding.TurnedOff || found == RepairFinding.NotRunning || repair.stage != RepairStage.Idle) -> {
                SettingsValueRow(uiText(found?.line() ?: "Beacon isn't installed on this Hermes"), icon = Icons.Rounded.Extension, testTag = "plugin-health-absent")
                SettingsDivider()
                PluginRepairRow(vm)
            }
            pluginAbsent -> {
                SettingsValueRow(uiText("Beacon isn't installed on this Hermes"), icon = Icons.Rounded.Extension, testTag = "plugin-health-absent")
                SettingsDivider()
                MissingBeaconPluginState(compact = true, illustration = false)
            }
            oldPlugin -> {
                MissingBeaconPluginState(compact = true, update = true, illustration = false)
                SettingsDivider()
                PluginRepairRow(vm)
            }
            // A failed read shows no verdicts at all: the last answer may be out of date, and it is not shown as if it were current.
            failed -> SettingsErrorRow(uiText("Can't reach Hermes"), { read(false) }, uiText("Retry"), retryTestTag = "plugin-health-retry")
            health != null -> {
                PluginHealthRows(health, roundTrip, checking && asked, open, finishedChecks) { read(true) }
                SettingsDivider()
                PluginRepairRow(vm, RepairFinding.OutOfDate.takeIf { health.version.isEmpty() })
            }
            else -> SettingsSkeletonRows(2)
        }
    }
}
