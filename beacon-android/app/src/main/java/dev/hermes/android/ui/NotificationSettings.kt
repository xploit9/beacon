@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui
import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.KeepConnectedService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
@Composable internal fun NotificationSettings(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val profileCatalog = data["navigation-profiles"]
    val profileRows = profileCatalog?.rows("profiles").orEmpty()
    val profileName = profileDisplayLabel(ui.profile, profileRows, ui.connected && (profileCatalog == null || profileCatalog.optBoolean("_loading")))
    LaunchedEffect(ui.origin, ui.owner, ui.connected) { if (ui.connected) vm.ensureLoaded("navigation-profiles", "/api/profiles") }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    // Words the callbacks below show are translated here, where uiText can run.
    val historyLoadFailed = uiText("Couldn’t load history retention from Hermes.")
    val historyUnsupported = uiText("Hermes returned an unsupported history retention period.")
    val historySaved = uiText("History retention saved")
    val historySaveFailed = uiText("Couldn’t save history retention. Try again.")
    val permissionOffKeepConnected = uiText("Notification permission is off. Keep connected was not enabled.")
    val permissionOffEvents = uiText("Notification permission is off. Events stay available inside Beacon.")
    val registrationUnavailable = uiText("Notifications can’t be set up right now. Check your network, then try again.")
    val couldntTurnOn = uiText("Couldn’t turn on notifications. Try again.")
    val couldntTurnOff = uiText("Couldn’t turn off notifications. Try again.")
    val testSent = uiText("Test sent.")
    val testFailed = uiText("Couldn’t queue a test. Try again.")
    val appSettingsHint = uiText("Open Beacon’s app settings in Android")
    val backgroundHint = uiText("Open Beacon’s app settings in Android and allow background use.")
    val liveUpdatesHint = uiText("Open Android notification settings to manage Live Updates")
    // Android lists only channels that exist, so they are created before "Notification types" can open them.
    LaunchedEffect(context) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        dev.hermes.android.push.ensureActivityChannels(manager)
        dev.hermes.android.push.incomingCallChannel(manager)
    }
    val ownerScope = remember(ui.owner, ui.profile, ui.origin) { vm.client?.scope?.key }
    var requestingClient by remember(ownerScope) { mutableStateOf<BeaconClient?>(null) }
    var target by remember(ownerScope) { mutableStateOf(vm.client?.scope?.let { vm.app.push.registeredTarget(it) }) }
    var busy by remember(ownerScope) { mutableStateOf(false) }
    var operationError by remember(ownerScope) { mutableStateOf("") }
    var historyRetentionDays by remember(ownerScope) { mutableStateOf<Int?>(null) }
    var historyRetentionSaving by remember(ownerScope) { mutableStateOf(false) }
    var historyRetentionError by remember(ownerScope) { mutableStateOf("") }
    val historyRetentionKey = "$HISTORY_RETENTION_KEY:${ownerScope.orEmpty()}"
    val historyRetentionRecord = data[historyRetentionKey]
    LaunchedEffect(ownerScope, historyRetentionRecord) {
        when {
            historyRetentionRecord?.has("_error") == true -> {
                android.util.Log.w("BeaconSettings", "History retention load failed: ${historyRetentionRecord.optString("_error")}")
                historyRetentionDays = null
                historyRetentionError = historyLoadFailed
            }
            historyRetentionRecord?.has("history_retention_days") == true -> {
                val days = historyRetentionRecord.optInt("history_retention_days", -1)
                if (historyRetentionChoices.any { it.first == days }) {
                    historyRetentionDays = days
                    historyRetentionError = ""
                } else {
                    android.util.Log.w("BeaconSettings", "History retention period not offered: $days")
                    historyRetentionDays = null
                    historyRetentionError = historyUnsupported
                }
            }
        }
    }
    var deviceAllowed by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var keepConnectedEnabled by remember(context) { mutableStateOf(KeepConnectedService.isEnabled(context)) }
    var keepConnectedStatus by remember(context) { mutableStateOf(KeepConnectedService.status(context)) }
    var keepConnectedReason by remember(context) { mutableStateOf(KeepConnectedService.failureReason(context)) }
    var pendingKeepConnectedChoice by remember { mutableStateOf<Boolean?>(null) }
    val keepConnectedPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        deviceAllowed = allowed || androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        pendingKeepConnectedChoice?.let { enabled ->
            if (!enabled || deviceAllowed) vm.setKeepConnectedEnabled(enabled)
            else { operationError = permissionOffKeepConnected; vm.notice(operationError) }
        }
        pendingKeepConnectedChoice = null
    }
    fun liveAllowed() = android.os.Build.VERSION.SDK_INT >= 36 && context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()
    var liveUpdatesAllowed by remember { mutableStateOf(liveAllowed()) }
    fun backgroundRestricted()=context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
    fun batteryOptimized()=!context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    var restricted by remember { mutableStateOf(backgroundRestricted()) }
    var optimized by remember { mutableStateOf(batteryOptimized()) }
    val liveUpdatesSettings = remember(context) { Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).takeIf { android.os.Build.VERSION.SDK_INT >= 36 && it.resolveActivity(context.packageManager) != null } }
    androidx.lifecycle.compose.LifecycleResumeEffect(ownerScope) {
        target = vm.client?.scope?.let { vm.app.push.registeredTarget(it) }
        deviceAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        keepConnectedEnabled = KeepConnectedService.isEnabled(context)
        keepConnectedStatus = KeepConnectedService.status(context)
        keepConnectedReason = KeepConnectedService.failureReason(context)
        liveUpdatesAllowed = liveAllowed()
        restricted = backgroundRestricted()
        optimized = batteryOptimized()
        if (vm.app.firebaseConfigured) vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status")
        vm.load(historyRetentionKey, HISTORY_RETENTION_PATH)
        onPauseOrDispose { }
    }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(KeepConnectedService.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeepConnectedService.KEY_ENABLED) keepConnectedEnabled = KeepConnectedService.isEnabled(context)
            if (key == KeepConnectedService.KEY_STATUS) keepConnectedStatus = KeepConnectedService.status(context)
            if (key == KeepConnectedService.KEY_REASON) keepConnectedReason = KeepConnectedService.failureReason(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun register(captured: BeaconClient) {
        busy = true; operationError = ""
        scope.launch {
            runCatching { vm.app.push.register(captured) }
                .onSuccess { if (vm.client?.scope == captured.scope) vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") }
                .onFailure { failure ->
                    android.util.Log.w("BeaconPush", "Turning on notifications failed: ${failureCause(failure)}")
                    if (vm.client?.scope == captured.scope) {
                        val unavailable = generateSequence(failure) { it.cause }.take(8).any { it.message?.contains("SERVICE_NOT_AVAILABLE") == true }
                        operationError = if (unavailable) registrationUnavailable else couldntTurnOn
                        vm.notice(operationError)
                    }
                }
            if (vm.client?.scope == captured.scope) { target = vm.app.push.registeredTarget(captured.scope); busy = false; deviceAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
        }
    }
    fun saveHistoryRetention(days: Int) {
        val capturedScope = vm.client?.scope ?: return
        historyRetentionSaving = true
        historyRetentionError = ""
        scope.launch {
            var savedDays: Int? = null
            vm.action(
                HISTORY_RETENTION_PATH,
                json("history_retention_days" to days),
                method = "PUT",
                refresh = false,
                successNotice = historySaved,
            ) { _, response ->
                val value = response.optInt("history_retention_days", -1)
                if (historyRetentionChoices.any { it.first == value }) savedDays = value
            }.join()
            if (vm.client?.scope == capturedScope) {
                historyRetentionSaving = false
                if (savedDays == null) historyRetentionError = historySaveFailed
                else historyRetentionDays = savedDays
            }
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val captured = requestingClient
        requestingClient = null
        if (captured != null && vm.client?.scope == captured.scope) {
            if (allowed) register(captured)
            else if (vm.client?.scope == captured.scope) { busy = false; operationError = permissionOffEvents; vm.notice(operationError) }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val pushStatus = data["push-status"]
        val serverReady = pushStatus?.optBoolean("android_configured") == true
        val registered = target != null && pushStatus?.rows("targets")?.any { it.string("target_id") == target && it.string("kind") == "fcm" && !it.optBoolean("revoked") } == true
        val delivery = pushStatus?.optJSONObject("delivery")
        fun enable() {
            val captured = vm.client?.detached() ?: return
            busy = true; operationError = ""
            if (android.os.Build.VERSION.SDK_INT >= 33 && !deviceAllowed) { requestingClient = captured; request.launch(Manifest.permission.POST_NOTIFICATIONS) }
            else register(captured)
        }
        fun disable() {
            val captured = vm.client?.detached() ?: return
            busy = true; operationError = ""
            scope.launch {
                runCatching { vm.app.push.revoke(captured) }.onFailure { failure ->
                    android.util.Log.w("BeaconPush", "Turning off notifications failed: ${failureCause(failure)}")
                    if (vm.client?.scope == captured.scope) { operationError = couldntTurnOff; vm.notice(operationError) }
                }
                if (vm.client?.scope == captured.scope) { target = vm.app.push.registeredTarget(captured.scope); busy = false; vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") }
            }
        }
        fun setKeepConnected(enabled: Boolean) {
            if (!enabled) { vm.setKeepConnectedEnabled(false); return }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !deviceAllowed) {
                pendingKeepConnectedChoice = true
                keepConnectedPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.setKeepConnectedEnabled(true)
        }
        // Card 1: is it working, and the switch.
        if (!vm.app.firebaseConfigured) {
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (keepConnectedEnabled) uiText("Keep connected is on") else uiText("Keep connected is off"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(uiText("Calls and alerts arrive through Keep connected."), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } else {
            val statusTitle = when {
                pushStatus == null -> uiText("Checking notification status…")
                pushStatus.has("_error") -> uiText("Couldn’t reach Hermes")
                !serverReady -> uiText("Server setup needed")
                !deviceAllowed -> uiText("Notifications are off on this phone")
                !registered -> if (target == null) uiText("Notifications are off for {0}").replace("{0}", profileName) else uiText("Notifications need attention")
                else -> uiText("Ready for notifications")
            }
            val statusBody = when {
                pushStatus == null -> uiText("Checking with Hermes.")
                pushStatus.has("_error") -> uiText("Check your network, then retry.")
                !serverReady -> uiText("Notifications aren’t set up on your Hermes yet.")
                !deviceAllowed -> uiText("Allow Beacon notifications in Android settings.")
                !registered -> if (target == null) uiText("Turn on notifications to get calls and approval requests.") else uiText("Notifications aren’t working for this profile yet. Try again or turn them off.")
                else -> uiText("Calls and approval requests reach you even when Beacon is closed.")
            }
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(statusTitle, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                Text(statusBody, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if ((delivery?.optInt("pending") ?: 0) > 0) Text(uiText("Waiting to send: {0}").replace("{0}", "${delivery?.optInt("pending")}"), style = MaterialTheme.typography.labelMedium)
                if (!delivery?.string("last_error").isNullOrBlank() && serverReady) Text(uiText("The last delivery needs attention. Try a test notification."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LabeledToggle(uiText("Notifications for {0}").replace("{0}", profileName), target != null, { enabled -> if (enabled) enable() else disable() },
                    description = if (target != null) uiText("On for {0}").replace("{0}", profileName) else uiText("Off · you can still check activity in Beacon"),
                    enabled = !busy && requestingClient == null && (target != null || (serverReady && pushStatus?.has("_error") != true)))
                if (pushStatus?.has("_error") == true) OutlinedButton(enabled = !busy, onClick = { vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry notification status")) }
                else if (target != null && !registered && serverReady && deviceAllowed) OutlinedButton(enabled = !busy, onClick = { enable() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Retry registration")) }
                OutlinedButton(onClick = { busy = true; operationError = ""; val owner = vm.client?.scope; scope.launch { var accepted = false; vm.action("/api/plugins/beacon/mobile/notifications/test", json("kind" to "fcm"), refresh = false) { _, _ -> accepted = true; vm.notice(testSent) }.join(); if (vm.client?.scope == owner) { if (!accepted) operationError = testFailed; busy = false; vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") } } }, enabled = !busy && serverReady && registered && deviceAllowed, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Send a test notification")) }
            } }
        }
        if (operationError.isNotBlank()) Text(operationError, color = MaterialTheme.colorScheme.error)
        // Card 2: Keep connected, what makes calls arrive.
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text(uiText("Keep connected"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(uiText("Let Hermes reach this phone for incoming calls while Beacon is closed."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(uiText("Shows a quiet notification and uses some battery."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = keepConnectedEnabled, enabled = ownerScope != null, onCheckedChange = ::setKeepConnected, modifier = Modifier.testTag("keep-connected-toggle"))
            }
            Text(when {
                !keepConnectedEnabled -> uiText("Off")
                keepConnectedStatus == KeepConnectedService.STATUS_CONNECTED -> uiText("Connected")
                keepConnectedStatus == KeepConnectedService.STATUS_RECONNECTING -> uiText("Reconnecting")
                keepConnectedStatus == KeepConnectedService.STATUS_SIGNED_OUT -> uiText("Signed out · sign in again to receive calls")
                else -> uiText("Starting…")
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("keep-connected-status"))
            if (keepConnectedEnabled && keepConnectedReason.isNotBlank()) Text(
                keepConnectedReason,
                color = if (keepConnectedStatus == KeepConnectedService.STATUS_RECONNECTING || keepConnectedStatus == KeepConnectedService.STATUS_SIGNED_OUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("keep-connected-reason"),
            )
            if (keepConnectedEnabled && !deviceAllowed) Text(uiText("Android notifications are off; incoming call alerts may be hidden."), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
        // Card 3: where Android and the rest of Beacon decide how alerts behave.
        val backgroundSummary = when {
            restricted -> uiText("Android is limiting Beacon in the background. Allow background activity.")
            optimized -> uiText("Allow background use so calls and alerts arrive on time.")
            else -> uiText("Background use is allowed.")
        }
        val rowDivider: @Composable () -> Unit = { HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f)) }
        Glass(Modifier.fillMaxWidth()) { Column {
            ActionRow(uiText("Notification types"), uiText("Sound, vibration and visibility for each kind of alert."), Icons.AutoMirrored.Rounded.OpenInNew) {
                runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }.onFailure { vm.notice(appSettingsHint) }
            }
            if (liveUpdatesSettings != null) {
                rowDivider()
                ActionRow(
                    uiText("Live Updates"),
                    if (liveUpdatesAllowed) uiText("Calls, ongoing work and requests can appear in your phone’s status chip.") else uiText("Turn on Live Updates in Android to keep calls and work in the status bar."),
                    Icons.AutoMirrored.Rounded.OpenInNew,
                ) { runCatching { context.startActivity(liveUpdatesSettings) }.onFailure { vm.notice(liveUpdatesHint) } }
            }
            rowDivider()
            ActionRow(uiText("Background use"), backgroundSummary, Icons.AutoMirrored.Rounded.OpenInNew) {
                if (optimized && !restricted) { if (!requestBackgroundUse(context)) vm.notice(backgroundHint) }
                else runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }.onFailure { vm.notice(appSettingsHint) }
            }
            rowDivider()
            ActionRow(uiText("Schedules"), uiText("Check-up calls and scheduled jobs"), Icons.AutoMirrored.Rounded.KeyboardArrowRight) { vm.tab("Tasks") }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText("After a force stop, open Beacon once to receive calls again."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (android.os.Build.MANUFACTURER.equals("HONOR", ignoreCase = true) || android.os.Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true))
                    Text(uiText("On Honor and Huawei phones, also allow Beacon to run in the background in the battery settings."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } }
        // Last: the one destructive setting, in a card with its own title.
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                key(ownerScope) {
                    HistoryRetentionControl(historyRetentionDays, historyRetentionSaving, historyRetentionError, ::saveHistoryRetention)
                }
            }
        }
    }
}

/** A failure for the log: class names down the cause chain, with Hermes' refusal text or a Firebase error code. Other messages can carry an address, so they stay out. */
private fun failureCause(failure: Throwable): String = generateSequence(failure) { it.cause }.take(4).joinToString(" <- ") { error ->
    val detail = error.message?.takeIf { error is ApiFailure || Regex("[A-Z][A-Z0-9_]+").matches(it) }
    error.javaClass.simpleName + (detail?.let { ": $it" } ?: "")
}
private const val HISTORY_RETENTION_KEY = "history-retention"
private const val HISTORY_RETENTION_PATH = "/api/plugins/beacon/mobile/history-retention"
internal val historyRetentionChoices = listOf(0 to "Forever", 365 to "1 year", 90 to "90 days", 30 to "30 days")
internal val HistoryRetentionStrings = historyRetentionChoices.map { it.second }
internal fun historyRetentionLabel(days: Int): String = historyRetentionChoices.firstOrNull { it.first == days }?.second ?: "Unavailable"
internal fun historyRetentionNeedsConfirmation(currentDays: Int, selectedDays: Int): Boolean =
    selectedDays != currentDays && selectedDays > 0 && (currentDays == 0 || selectedDays < currentDays)

@Composable internal fun HistoryRetentionControl(
    currentDays: Int?,
    saving: Boolean,
    error: String,
    onSave: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pendingDays by remember { mutableStateOf<Int?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(uiText("Keep call and task history"), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        Text(uiText("Call transcripts and task history, all profiles."), style = MaterialTheme.typography.bodySmall)
        Text(
            currentDays?.let { uiText("Current server setting: {0}").replace("{0}", uiText(historyRetentionLabel(it))) }
                ?: if (error.isBlank()) uiText("Checking the current server setting…") else uiText("Current server setting unavailable"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("history-retention-current"),
        )
        Box {
            // The button is named by its visible value, and the arrow says it opens a list.
            OutlinedButton(
                onClick = { expanded = true },
                enabled = currentDays != null && !saving,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("history-retention-choice"),
            ) {
                Text(currentDays?.let { uiText(historyRetentionLabel(it)) } ?: uiText("Unavailable"), Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                historyRetentionChoices.forEach { (days, label) ->
                    DropdownMenuItem(
                        text = { Text(uiText(label)) },
                        enabled = currentDays != null && !saving,
                        onClick = {
                            expanded = false
                            val current = currentDays
                            if (current != null && days != current) {
                                if (historyRetentionNeedsConfirmation(current, days)) pendingDays = days
                                else onSave(days)
                            }
                        },
                    )
                }
            }
        }
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("history-retention-saving"))
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("history-retention-error"))
    }
    pendingDays?.let { days ->
        AlertDialog(
            onDismissRequest = { if (!saving) pendingDays = null },
            title = { Text(uiText("Shorten history retention?")) },
            text = { Text(uiText("Call transcripts and task history older than {0} will be deleted from Hermes. This can’t be undone.").replace("{0}", uiText(historyRetentionLabel(days)))) },
            confirmButton = {
                TextButton(onClick = { pendingDays = null; onSave(days) }, enabled = !saving) { Text(uiText("Delete older history")) }
            },
            dismissButton = { TextButton(onClick = { pendingDays = null }, enabled = !saving) { Text(uiText("Cancel")) } },
            modifier = Modifier.testTag("history-retention-confirmation"),
        )
    }
}
