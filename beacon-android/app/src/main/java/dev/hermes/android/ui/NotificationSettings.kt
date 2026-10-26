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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.CoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
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
                historyRetentionDays = null
                historyRetentionError = historyRetentionRecord.optString("_error").ifBlank { "Couldn’t load history retention from Hermes." }
            }
            historyRetentionRecord?.has("history_retention_days") == true -> {
                val days = historyRetentionRecord.optInt("history_retention_days", -1)
                if (historyRetentionChoices.any { it.first == days }) {
                    historyRetentionDays = days
                    historyRetentionError = ""
                } else {
                    historyRetentionDays = null
                    historyRetentionError = "Hermes returned an unsupported history retention period."
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
            else { operationError = "Notification permission is off. Keep connected was not enabled."; vm.notice(operationError) }
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
                .onFailure { failure -> if (vm.client?.scope == captured.scope) {
                    val unavailable = generateSequence(failure) { it.cause }.take(8).any { it.message?.contains("SERVICE_NOT_AVAILABLE") == true }
                    operationError = if (unavailable) "Notification registration is temporarily unavailable. Check your internet connection and Google Play services, then try again." else failure.message ?: "Couldn’t enable notifications. Try again."
                    vm.notice(operationError)
                } }
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
                successNotice = "History retention saved",
            ) { _, response ->
                val value = response.optInt("history_retention_days", -1)
                if (historyRetentionChoices.any { it.first == value }) savedDays = value
            }.join()
            if (vm.client?.scope == capturedScope) {
                historyRetentionSaving = false
                if (savedDays == null) historyRetentionError = vm.ui.value.error.ifBlank { "Couldn’t save history retention. Try again." }
                else historyRetentionDays = savedDays
            }
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val captured = requestingClient
        requestingClient = null
        if (captured != null && vm.client?.scope == captured.scope) {
            if (allowed) register(captured)
            else if (vm.client?.scope == captured.scope) { busy = false; operationError = "Notification permission is off. Events stay available inside Beacon."; vm.notice(operationError) }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val pushStatus = data["push-status"]
        val serverReady = pushStatus?.optBoolean("android_configured") == true
        val registered = target != null && pushStatus?.rows("targets")?.any { it.string("target_id") == target && it.string("kind") == "fcm" && !it.optBoolean("revoked") } == true
        val delivery = pushStatus?.optJSONObject("delivery")
        if (!vm.app.firebaseConfigured) {
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (keepConnectedEnabled) "Keep connected is on" else "Keep connected is off", style = MaterialTheme.typography.titleLarge)
                Text("Calls and alerts arrive through Keep connected.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        } else {
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when { pushStatus == null -> "Checking notification status…"; pushStatus.has("_error") -> "Couldn’t reach Hermes"; !serverReady -> "Server setup needed"; !deviceAllowed -> "Notifications are off on this phone"; !registered -> if (target == null) "Notifications are off for $profileName" else "Registration needs attention"; else -> "Ready for notifications" }, style = MaterialTheme.typography.titleLarge)
                Text(when { pushStatus == null -> "Connecting to your private server."; pushStatus.has("_error") -> "Reconnect to Tailscale, then retry below."; !serverReady -> "This phone can receive notifications, but your Hermes server still needs its notification sender configured."; !deviceAllowed -> "Allow Beacon notifications in Android settings."; !registered -> if (target == null) "Turn on notifications for native calls and approval requests." else "This profile has a saved registration, but the server has not confirmed it. Retry registration or turn it off."; else -> "Calls and approval requests can reach you while the app is closed. Send a test to check delivery on this phone." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if ((delivery?.optInt("pending") ?: 0) > 0) Text("${delivery?.optInt("pending")} notifications waiting to be delivered", style = MaterialTheme.typography.labelMedium)
                if (!delivery?.string("last_error").isNullOrBlank() && serverReady) Text("The last delivery needs attention. Try a test notification.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            } }
            recordError(pushStatus).takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (operationError.isNotBlank()) Text(operationError, color = MaterialTheme.colorScheme.error)
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
                runCatching { vm.app.push.revoke(captured) }.onFailure { if (vm.client?.scope == captured.scope) { operationError = it.message ?: "Couldn’t turn off notifications. Try again."; vm.notice(operationError) } }
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
        if (vm.app.firebaseConfigured) Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            LabeledToggle("Notifications for $profileName", target != null, { enabled -> if (enabled) enable() else disable() },
                description = if (target != null) "On for $profileName" else "Off · you can still check activity in Beacon",
                enabled = !busy && requestingClient == null && (target != null || (serverReady && pushStatus?.has("_error") != true)))
        } }
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 16.dp)) {
                    Text("Keep connected", style = MaterialTheme.typography.titleMedium)
                    Text("Let Hermes reach this phone for incoming calls while Beacon is closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = keepConnectedEnabled, enabled = ownerScope != null, onCheckedChange = ::setKeepConnected, modifier = Modifier.testTag("keep-connected-toggle"))
            }
            Text(when {
                !keepConnectedEnabled -> "Off"
                keepConnectedStatus == KeepConnectedService.STATUS_CONNECTED -> "Connected"
                keepConnectedStatus == KeepConnectedService.STATUS_RECONNECTING -> "Reconnecting"
                keepConnectedStatus == KeepConnectedService.STATUS_SIGNED_OUT -> "Signed out · sign in again to receive calls"
                else -> "Starting…"
            }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("keep-connected-status"))
            if (keepConnectedEnabled && keepConnectedReason.isNotBlank()) Text(
                keepConnectedReason,
                color = if (keepConnectedStatus == KeepConnectedService.STATUS_RECONNECTING || keepConnectedStatus == KeepConnectedService.STATUS_SIGNED_OUT) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("keep-connected-reason"),
            )
            if (keepConnectedEnabled && !deviceAllowed) Text("Android notifications are off; incoming call alerts may be hidden.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (optimized && !restricted) OutlinedButton(
                onClick = { if (!requestBackgroundUse(context)) vm.notice("Open Beacon’s app settings in Android and allow background use.") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.OpenInNew, null)
                Text("Allow background use", Modifier.padding(start = 8.dp))
            }
        } }
        if (vm.app.firebaseConfigured) {
            if (pushStatus?.has("_error") == true) OutlinedButton(enabled = !busy, onClick = { vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry notification status") }
            else if (target != null && !registered && serverReady && deviceAllowed) OutlinedButton(enabled = !busy, onClick = { enable() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Retry registration") }
            OutlinedButton(onClick = { busy = true; operationError = ""; val owner = vm.client?.scope; scope.launch { var accepted = false; vm.action("/api/plugins/beacon/mobile/notifications/test", json("kind" to "fcm"), refresh = false) { _, _ -> accepted = true; vm.notice("Test sent.") }.join(); if (vm.client?.scope == owner) { if (!accepted) operationError = vm.ui.value.error.ifBlank { "Couldn’t queue a test. Try again." }; busy = false; vm.load("push-status", "/api/plugins/beacon/mobile/notifications/status") } } }, enabled = !busy && serverReady && registered && deviceAllowed) { Text(uiText("Send a test notification")) }
        }
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                key(ownerScope) {
                    HistoryRetentionControl(historyRetentionDays, historyRetentionSaving, historyRetentionError, ::saveHistoryRetention)
                }
            }
        }
        OutlinedButton(onClick = { vm.tab("Tasks") }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Check-up calls and schedules") }
        if (liveUpdatesSettings != null) {
            Text("Live Updates", style = MaterialTheme.typography.titleMedium)
            Text(if (liveUpdatesAllowed) "Calls, ongoing work and requests can appear in your phone’s status chip. Tap to return or respond." else "Enable Live Updates in Android to keep calls, work and requests in the status bar.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { runCatching { context.startActivity(liveUpdatesSettings) }.onFailure { vm.notice("Open Android notification settings to manage Live Updates") } }, modifier = Modifier.heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.OpenInNew, null)
                Text("Manage Live Updates", Modifier.padding(start = 8.dp))
            }
        }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        dev.hermes.android.push.ensureActivityChannels(manager)
        Text("Notification types", style = MaterialTheme.typography.titleMedium)
        Text("Choose sound, vibration and visibility in Android settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Messages" to "hermes-messages", "Calls" to dev.hermes.android.push.incomingCallChannel(manager), "Tasks" to "hermes-tasks", "Questions and permissions" to dev.hermes.android.push.promptNotificationChannel(manager), "Updates" to "hermes-updates").forEach { (label, channel) ->
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Icon(Icons.Rounded.OpenInNew, null)
                Text(label, Modifier.weight(1f).padding(start = 8.dp))
            }
        }
        OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.OpenInNew, null)
            Text(uiText("Android notification settings"), Modifier.padding(start = 8.dp))
        }
        Text("Background delivery",style=MaterialTheme.typography.titleMedium)
        Text(when { restricted -> "Android is restricting Beacon in the background. Open app settings and allow background activity."; optimized -> "Battery optimization is on. If alerts arrive late, allow background activity in your phone’s app or battery settings."; else -> "Android battery optimization is off for Beacon." },color=if(restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Shows a quiet notification and uses some battery.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keep Tailscale on. On Honor phones, allow Beacon to run in the background.",style=MaterialTheme.typography.bodySmall)
        Text("After a force stop, open Beacon once to receive calls again.",style=MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick={runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }.onFailure { vm.notice("Open Beacon’s app settings in Android") } }, modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(Icons.Rounded.OpenInNew, null)
            Text("Open background app settings", Modifier.padding(start = 8.dp))
        }
    }
}

private const val HISTORY_RETENTION_KEY = "history-retention"
private const val HISTORY_RETENTION_PATH = "/api/plugins/beacon/mobile/history-retention"
internal val historyRetentionChoices = listOf(0 to "Forever", 365 to "1 year", 90 to "90 days", 30 to "30 days")
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
        Text("Keep call and task history", style = MaterialTheme.typography.titleMedium)
        Text("Call transcripts and task history, all profiles.", style = MaterialTheme.typography.bodySmall)
        Text(
            currentDays?.let { "Current server setting: ${historyRetentionLabel(it)}" }
                ?: if (error.isBlank()) "Checking the current server setting…" else "Current server setting unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("history-retention-current"),
        )
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                enabled = currentDays != null && !saving,
                modifier = Modifier
                    .semantics {
                        contentDescription = "History retention choice"
                        stateDescription = currentDays?.let { "Current server setting: ${historyRetentionLabel(it)}" } ?: "Unavailable"
                    }
                    .testTag("history-retention-choice"),
            ) {
                Text(currentDays?.let(::historyRetentionLabel) ?: "Unavailable")
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                historyRetentionChoices.forEach { (days, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
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
            title = { Text("Shorten history retention?") },
            text = { Text("Call transcripts and task history older than ${historyRetentionLabel(days)} will be deleted from Hermes. This can’t be undone.") },
            confirmButton = {
                TextButton(onClick = { pendingDays = null; onSave(days) }, enabled = !saving) { Text("Delete older history") }
            },
            dismissButton = { TextButton(onClick = { pendingDays = null }, enabled = !saving) { Text("Cancel") } },
            modifier = Modifier.testTag("history-retention-confirmation"),
        )
    }
}
