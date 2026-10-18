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
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.CypherClient
import dev.hermes.android.push.KeepConnectedService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
@Composable internal fun NotificationSettings(vm: CypherViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val ownerScope = remember(ui.owner, ui.profile, ui.origin) { vm.client?.scope?.key }
    var requestingClient by remember(ownerScope) { mutableStateOf<CypherClient?>(null) }
    var target by remember(ownerScope) { mutableStateOf(vm.client?.scope?.let { vm.app.push.registeredTarget(it) }) }
    var busy by remember(ownerScope) { mutableStateOf(false) }
    var operationError by remember(ownerScope) { mutableStateOf("") }
    var deviceAllowed by remember { mutableStateOf(androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var keepConnectedEnabled by remember(context) { mutableStateOf(KeepConnectedService.isEnabled(context)) }
    var keepConnectedStatus by remember(context) { mutableStateOf(KeepConnectedService.status(context)) }
    var pendingKeepConnectedChoice by remember { mutableStateOf<Boolean?>(null) }
    val keepConnectedPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        deviceAllowed = allowed || androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        pendingKeepConnectedChoice?.let(vm::setKeepConnectedEnabled)
        pendingKeepConnectedChoice = null
    }
    fun liveAllowed() = android.os.Build.VERSION.SDK_INT >= 36 && context.getSystemService(android.app.NotificationManager::class.java).canPostPromotedNotifications()
    var liveUpdatesAllowed by remember { mutableStateOf(liveAllowed()) }
    fun backgroundRestricted()=context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
    fun batteryOptimized()=!context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    var restricted by remember { mutableStateOf(backgroundRestricted()) }
    var optimized by remember { mutableStateOf(batteryOptimized()) }
    val liveUpdatesSettings = remember(context) { Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).takeIf { android.os.Build.VERSION.SDK_INT >= 36 && it.resolveActivity(context.packageManager) != null } }
    androidx.lifecycle.compose.LifecycleResumeEffect(ownerScope) { target = vm.client?.scope?.let { vm.app.push.registeredTarget(it) }; deviceAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled(); keepConnectedEnabled = KeepConnectedService.isEnabled(context); keepConnectedStatus = KeepConnectedService.status(context); liveUpdatesAllowed = liveAllowed();restricted=backgroundRestricted();optimized=batteryOptimized(); vm.load("push-status", "/api/plugins/cypher/mobile/notifications/status"); onPauseOrDispose { } }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(KeepConnectedService.PREFS, android.content.Context.MODE_PRIVATE)
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KeepConnectedService.KEY_ENABLED) keepConnectedEnabled = KeepConnectedService.isEnabled(context)
            if (key == KeepConnectedService.KEY_STATUS) keepConnectedStatus = KeepConnectedService.status(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun register(captured: CypherClient) {
        busy = true; operationError = ""
        scope.launch {
            runCatching { vm.app.push.register(captured) }
                .onSuccess { if (vm.client?.scope == captured.scope) vm.load("push-status", "/api/plugins/cypher/mobile/notifications/status") }
                .onFailure { failure -> if (vm.client?.scope == captured.scope) {
                    val unavailable = generateSequence(failure) { it.cause }.take(8).any { it.message?.contains("SERVICE_NOT_AVAILABLE") == true }
                    operationError = if (unavailable) "Notification registration is temporarily unavailable. Check your internet connection and Google Play services, then try again." else failure.message ?: "Couldn’t enable notifications. Try again."
                    vm.notice(operationError)
                } }
            if (vm.client?.scope == captured.scope) { target = vm.app.push.registeredTarget(captured.scope); busy = false; deviceAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() }
        }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        val captured = requestingClient
        requestingClient = null
        if (captured != null && vm.client?.scope == captured.scope) {
            if (allowed) register(captured)
            else if (vm.client?.scope == captured.scope) { busy = false; operationError = "Notification permission is off. Events stay available inside Cypher."; vm.notice(operationError) }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val pushStatus = data["push-status"]
        val serverReady = pushStatus?.optBoolean("android_configured") == true
        val registered = target != null && pushStatus?.rows("targets")?.any { it.string("target_id") == target && it.string("kind") == "fcm" && !it.optBoolean("revoked") } == true
        val delivery = pushStatus?.optJSONObject("delivery")
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(when { pushStatus == null -> "Checking notification status…"; pushStatus.has("_error") -> "Couldn’t reach Cypher"; !vm.app.firebaseConfigured -> "Notifications unavailable in this build"; !serverReady -> "Server setup needed"; !deviceAllowed -> "Notifications are off on this phone"; !registered -> if (target == null) "Notifications are off for this profile" else "Registration needs attention"; else -> "Ready for notifications" }, style = MaterialTheme.typography.titleLarge)
            Text(when { pushStatus == null -> "Connecting to your private server."; pushStatus.has("_error") -> "Reconnect to Tailscale, then retry below."; !vm.app.firebaseConfigured -> "This app build needs its owner Firebase configuration before it can register this phone."; !serverReady -> "This phone can receive notifications, but your Cypher server still needs its notification sender configured."; !deviceAllowed -> "Allow Cypher notifications in Android settings."; !registered -> if (target == null) "Turn on notifications for native calls and approval requests." else "This profile has a saved registration, but the server has not confirmed it. Retry registration or turn it off."; else -> "Calls and approval requests can reach you while the app is closed. Send a test to check delivery on this phone." }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if ((delivery?.optInt("pending") ?: 0) > 0) Text("${delivery?.optInt("pending")} notifications waiting to be delivered", style = MaterialTheme.typography.labelMedium)
            if (!delivery?.string("last_error").isNullOrBlank() && serverReady) Text("The last delivery needs attention. Try a test notification.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
        recordError(pushStatus).takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (operationError.isNotBlank()) Text(operationError, color = MaterialTheme.colorScheme.error)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
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
                if (vm.client?.scope == captured.scope) { target = vm.app.push.registeredTarget(captured.scope); busy = false; vm.load("push-status", "/api/plugins/cypher/mobile/notifications/status") }
            }
        }
        fun setKeepConnected(enabled: Boolean) {
            if (!enabled) { vm.setKeepConnectedEnabled(false); return }
            if (android.os.Build.VERSION.SDK_INT >= 33 && !deviceAllowed) {
                pendingKeepConnectedChoice = true
                keepConnectedPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else vm.setKeepConnectedEnabled(true)
        }
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            LabeledToggle("Notifications for this profile", target != null, { enabled -> if (enabled) enable() else disable() },
                description = if (target != null) "On for this profile" else "Off · you can still check activity in Cypher",
                enabled = !busy && requestingClient == null && (target != null || (vm.app.firebaseConfigured && serverReady && pushStatus?.has("_error") != true)))
        } }
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep connected", style = MaterialTheme.typography.titleMedium)
                    Text("Let Hermes reach this phone for incoming calls while Cypher is closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            if (keepConnectedEnabled && !deviceAllowed) Text("Android notifications are off; incoming call alerts may be hidden.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
        if (pushStatus?.has("_error") == true) TextButton(enabled = !busy, onClick = { vm.load("push-status", "/api/plugins/cypher/mobile/notifications/status") }) { Text("Retry notification status") }
        else if (target != null && !registered && vm.app.firebaseConfigured && serverReady && deviceAllowed) TextButton(enabled = !busy, onClick = { enable() }) { Text("Retry registration") }
        OutlinedButton(onClick = { busy = true; operationError = ""; val owner = vm.client?.scope; scope.launch { var accepted = false; vm.action("/api/plugins/cypher/mobile/notifications/test", json("kind" to "fcm"), refresh = false) { _, _ -> accepted = true; vm.notice("Test queued. Delivery is confirmed only when the notification arrives on this phone.") }.join(); if (vm.client?.scope == owner) { if (!accepted) operationError = vm.ui.value.error.ifBlank { "Couldn’t queue a test. Try again." }; busy = false; vm.load("push-status", "/api/plugins/cypher/mobile/notifications/status") } } }, enabled = !busy && vm.app.firebaseConfigured && serverReady && registered && deviceAllowed) { Text(uiText("Send a test notification")) }
        TextButton(onClick = { vm.tab("Tasks") }) { Text("Manage check-up calls and email alerts") }
        Text("Live Updates", style = MaterialTheme.typography.titleMedium)
        Text(when { liveUpdatesAllowed -> "Calls, ongoing work and requests can appear in your phone’s status chip. Tap to return or respond."; liveUpdatesSettings != null -> "Enable Live Updates in Android to keep calls, work and requests in the status bar."; else -> "Calls and requests use standard notifications on this Android version. Status chips need a supported Android 16 update." }, style = MaterialTheme.typography.bodyMedium)
        if (liveUpdatesSettings != null) TextButton(onClick = { runCatching { context.startActivity(liveUpdatesSettings) }.onFailure { vm.notice("Open Android notification settings to manage Live Updates") } }) { Text("Manage Live Updates") }
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        dev.hermes.android.push.ensureActivityChannels(manager)
        Text("Notification types", style = MaterialTheme.typography.titleMedium)
        Text("Choose sound, vibration and visibility in Android settings.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Messages" to "hermes-messages", "Calls" to dev.hermes.android.push.incomingCallChannel(manager), "Tasks" to "hermes-tasks", "Questions and permissions" to dev.hermes.android.push.promptNotificationChannel(manager), "Updates" to "hermes-updates").forEach { (label, channel) ->
            TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channel)) }, modifier = Modifier.fillMaxWidth()) { Text(label, Modifier.weight(1f)); Icon(Icons.Rounded.OpenInNew, null) }
        }
        TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text(uiText("Android notification settings")) }
        Text("Background delivery",style=MaterialTheme.typography.titleMedium)
        Text(when { restricted -> "Android is restricting Cypher in the background. Open app settings and allow background activity."; optimized -> "Battery optimization is on. If alerts arrive late, allow background activity in your phone’s app or battery settings."; else -> "Android battery optimization is off for Cypher." },color=if(restricted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keep connected uses a persistent low-importance notification and may use more battery. Allow background activity and keep Tailscale connected for reliable calls.", style=MaterialTheme.typography.bodySmall, color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Keep Tailscale connected to answer private requests. Do Not Disturb and notification settings can silence popups. On Honor, also check App launch → Manage manually → Run in background.",style=MaterialTheme.typography.bodySmall)
        Text("After Force stop in Android settings, reopen Cypher once to receive notifications again. Hiding the call bubble keeps your call available in the notification and supported status chip.",style=MaterialTheme.typography.bodySmall)
        TextButton(onClick={runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:${context.packageName}"))) }.onFailure { vm.notice("Open Cypher’s app settings in Android") }}) { Text("Open background app settings") }
    }
}
