package dev.hermes.android.ui

import android.os.SystemClock
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.PLUGIN_VERSION_PATTERN
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
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
internal data class HealthLine(val status: SettingsStatus, val text: String, val target: HealthTarget = HealthTarget.None)

/** What the Beacon plugin section says. A row the plugin could not observe is null and not shown. */
internal data class PluginHealth(val version: String, val dashboard: HealthLine?, val gateway: HealthLine?, val calls: HealthLine, val notifications: HealthLine) {
    /** The worst row decides the pill; a row that is working, or switched off on purpose, does not. */
    val overall: SettingsStatus get() {
        val all = listOfNotNull(dashboard, gateway, calls, notifications).map { it.status }
        return when {
            SettingsStatus.Error in all -> SettingsStatus.Error
            SettingsStatus.Attention in all -> SettingsStatus.Attention
            else -> SettingsStatus.Ok
        }
    }
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
    "Slow to start", "Hermes is updating", "Hermes is busy", "Hermes took too long", "Not ready", "Keep connected is on", "Turn on Keep connected", "Not set up",
    "The Beacon plugin isn't installed", "Update Beacon on your Hermes", "Hermes answered in {0} ms",
)

private fun JSONObject.flag(name: String): Boolean? = opt(name) as? Boolean

/**
 * Maps the plugin's answer to rows. Only what the answer says is shown: a missing part is null, never an assumed state.
 * [keepConnected] and [voiceKeySaved] are what this phone itself knows (notifications can arrive without push, calls need the phone's key).
 */
internal fun pluginHealthOf(answer: JSONObject?, keepConnected: Boolean, voiceKeySaved: Boolean? = null): PluginHealth? {
    val plugin = answer?.optJSONObject("plugin") ?: return null
    val version = listOf("installed", "running").map { plugin.optString(it).trim() }.firstOrNull { PLUGIN_VERSION_PATTERN.matches(it) } ?: return null
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
        else -> HealthLine(SettingsStatus.Off, "Turn on Keep connected", HealthTarget.Notifications)
    }
    return PluginHealth(version, dashboard, gateway, callsLine, notifications)
}

/** The text under Check now once Hermes has answered. */
@Composable internal fun roundTripText(milliseconds: Long): String = uiText("Hermes answered in {0} ms").replace("{0}", milliseconds.toString())

/** A verdict row: plain when nothing is asked of the owner, else a tappable row that goes where the problem is fixed. */
@Composable private fun HealthRow(label: String, icon: ImageVector, line: HealthLine, tag: String, onOpen: (HealthTarget) -> Unit) {
    val word = uiText(line.text)
    if (line.target == HealthTarget.None) SettingsValueRow(label, icon = icon, status = line.status, statusLabel = word, testTag = tag)
    else SettingsNavRow(label, { onOpen(line.target) }, icon = icon, status = line.status, statusLabel = word, testTag = tag,
        trailingIcon = if (line.target == HealthTarget.CheckNow) Icons.Rounded.Refresh else null)
}

/**
 * The rows themselves, with no state of their own, so a screenshot or test can show any verdict. [onOpen] receives the target of a tapped
 * row; [resultSignal] counts finished checks, and each new count brings Check now (and the answer under it) into view.
 */
@Composable internal fun PluginHealthRows(health: PluginHealth, roundTrip: Long?, checking: Boolean, onOpen: (HealthTarget) -> Unit = {}, resultSignal: Int = 0, onCheck: () -> Unit) {
    val result = remember { BringIntoViewRequester() }
    LaunchedEffect(resultSignal) { if (resultSignal > 0) result.bringIntoView() }
    SettingsValueRow(uiText("Health"), icon = Icons.Rounded.MonitorHeart, status = health.overall, statusLabel = uiText(health.overallText), testTag = "plugin-health-status")
    SettingsDivider()
    SettingsValueRow(uiText("Version"), icon = Icons.Rounded.Tag, value = health.version, testTag = "plugin-health-version")
    health.dashboard?.let { SettingsDivider(); HealthRow(uiText("Hermes dashboard"), Icons.Rounded.Dashboard, it, "plugin-health-dashboard", onOpen) }
    health.gateway?.let { SettingsDivider(); HealthRow(uiText("Hermes gateway"), Icons.Rounded.Router, it, "plugin-health-gateway", onOpen) }
    SettingsDivider()
    HealthRow(uiText("Calls"), Icons.Rounded.Call, health.calls, "plugin-health-calls", onOpen)
    SettingsDivider()
    HealthRow(uiText("Notifications"), Icons.Rounded.NotificationsNone, health.notifications, "plugin-health-notifications", onOpen)
    SettingsDivider()
    SettingsActionRow(uiText("Check now"), onCheck, Modifier.bringIntoViewRequester(result), icon = Icons.Rounded.Refresh, value = roundTrip?.let { roundTripText(it) }.orEmpty(),
        busy = checking, busyLabel = uiText("Checking…"), testTag = "plugin-health-check")
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
    fun read(fresh: Boolean) {
        val active = vm.client
        if (active == null || checking) return
        checking = true
        asked = fresh
        requests.launch {
            try {
                val started = SystemClock.elapsedRealtime()
                val answer = active.nativeRequest(PLUGIN_HEALTH_PATH, params = if (fresh) mapOf("fresh" to "1") else emptyMap())
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
    // The route answers the owner only; to anyone else the plugin has nothing to say here.
    if (notOwner && !pluginAbsent) return
    SettingsSection(uiText("Beacon plugin"), testTag = "settings-section:Plugin") {
        when {
            pluginAbsent -> {
                SettingsValueRow(uiText("The Beacon plugin isn't installed"), icon = Icons.Rounded.Extension, testTag = "plugin-health-absent")
                SettingsDivider()
                MissingBeaconPluginState(compact = true, illustration = false)
            }
            oldPlugin -> MissingBeaconPluginState(compact = true, update = true, illustration = false)
            // A failed read shows no verdicts at all: the last answer may be out of date, and it is not shown as if it were current.
            failed -> SettingsErrorRow(uiText("Can't reach Hermes"), { read(false) }, uiText("Retry"), retryTestTag = "plugin-health-retry")
            health != null -> PluginHealthRows(health, roundTrip, checking && asked, open, finishedChecks) { read(true) }
            else -> SettingsSkeletonRows(2)
        }
    }
}
