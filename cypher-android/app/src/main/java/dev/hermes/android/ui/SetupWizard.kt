package dev.hermes.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.CypherViewModel
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.validLoopbackSessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun handleSetupPermissionResult(granted: Boolean, advance: () -> Unit, denied: () -> Unit) {
    if (granted) advance() else denied()
}

internal fun setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations: Boolean): Boolean = !isIgnoringBatteryOptimizations

internal fun setupPermissionRequiresSettings(denied: Boolean, showRationale: Boolean): Boolean = denied && !showRationale

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal data class CypherConnectInput(val origin: String, val sessionToken: String? = null) {
    override fun toString() = "CypherConnectInput(origin=$origin, sessionToken=<redacted>)"
}

internal fun parseCypherConnectLink(value: String, allowAddress: Boolean = true): CypherConnectInput {
    val input = value.trim()
    val uri = Uri.parse(input)
    val scannerPayload = runCatching { org.json.JSONObject(input) }.getOrNull()
        ?.takeIf { it.optString("type") == "cypher-connect" }
    val link = uri.scheme.equals("cypher", ignoreCase = true)
    val address = when {
        scannerPayload != null -> scannerPayload.opt("url") as? String
            ?: throw IllegalArgumentException("This Cypher QR has no server address. Scan it again or paste the HTTPS address.")
        link -> {
            require(uri.host.equals("connect", ignoreCase = true) && uri.path.isNullOrEmpty()) { "Scan a Cypher connection QR or paste an HTTPS Hermes address." }
            uri.getQueryParameter("url")?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("This Cypher link has no server address. Scan the QR again or paste the HTTPS address.")
        }
        else -> {
            require(allowAddress) { "Scan a Cypher connection QR or paste an HTTPS Hermes address." }
            input
        }
    }
    val sessions = when {
        scannerPayload != null -> listOf((scannerPayload.opt("session") as? String)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This Cypher QR has an invalid session. Scan it again."))
        link -> uri.getQueryParameters("session")
        else -> emptyList()
    }
    require(sessions.size <= 1) { "This Cypher link has an invalid session. Scan the Connect QR again." }
    val session = sessions.singleOrNull()?.takeIf(String::isNotBlank)
    require(session == null || validLoopbackSessionToken(session)) { "This Cypher link has an invalid session. Scan the Connect QR again." }
    val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(address.trim())
    return CypherConnectInput(trustedOrigin(if (explicitScheme) address else "https://$address"), session)
}

internal fun parseCypherConnectInput(value: String): String = parseCypherConnectLink(value).origin

internal fun stageCypherConnectQr(value: String, onStage: (String) -> Unit): Boolean =
    runCatching { parseCypherConnectLink(value, allowAddress = false) }.fold(
        onSuccess = { onStage(value); true },
        onFailure = { false },
    )

internal fun setupAddressForDisplay(value: String): String {
    val input = value.trim()
    val scannerPayload = runCatching { org.json.JSONObject(input) }.getOrNull()
        ?.takeIf { it.optString("type") == "cypher-connect" }
    if (scannerPayload != null) return runCatching { trustedOrigin(scannerPayload.optString("url")) }.getOrDefault("")
    val uri = Uri.parse(input)
    if (!uri.scheme.equals("cypher", ignoreCase = true)) return value
    if (!uri.host.equals("connect", ignoreCase = true)) return ""
    return runCatching { trustedOrigin(uri.getQueryParameter("url").orEmpty()) }.getOrDefault("")
}

@Composable
fun SetupWizard(vm: CypherViewModel, modifier: Modifier = Modifier, externalLinkOrigin: String? = null, onExternalLinkCleared: () -> Unit = {}) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SetupWizardScreen(
        state = state,
        modifier = modifier,
        externalLinkOrigin = externalLinkOrigin,
        onProbe = { vm.probeSetupAddress(it) },
        onSignIn = { vm.signIn(context) },
        onAdvancePermission = vm::advanceSetupPermission,
        onVoiceKeySaved = vm::voiceKeyConfigured,
        onFinish = { vm.finishSetupWizard(it); onExternalLinkCleared() },
        onReopen = vm::reopenSetupWizard,
        onCancel = { vm.cancelSetupWizard(); onExternalLinkCleared() },
        onStageLink = vm::openSetupLink,
        onOpenBackgroundSettings = {
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { vm.notice("Open Cypher’s app settings in Android and allow background use.") }
        },
    )
}

@Composable
internal fun SetupWizardScreen(
    state: AppState,
    modifier: Modifier = Modifier,
    onProbe: (String) -> Unit,
    onSignIn: (Context) -> Unit,
    onAdvancePermission: () -> Unit,
    onFinish: (Boolean) -> Unit,
    onReopen: (String) -> Unit,
    onCancel: () -> Unit,
    onVoiceKeySaved: () -> Unit = {},
    onStageLink: (String) -> Unit = {},
    onOpenBackgroundSettings: () -> Unit = {},
    externalLinkOrigin: String? = null,
) {
    val context = LocalContext.current
    val powerManager = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var ignoringBatteryOptimizations by remember(context) { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context) {
        ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    val initialAddress = setupAddressForDisplay(state.setupOrigin.ifBlank { state.origin })
    val visibleOrigin = setupAddressForDisplay(state.setupOrigin.ifBlank { state.origin })
    var address by rememberSaveable(initialAddress) { mutableStateOf(initialAddress) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by rememberSaveable(state.setupStep) { mutableStateOf(false) }
    var permissionNotice by rememberSaveable(state.setupStep) { mutableStateOf("") }

    fun permissionAllowed(step: String): Boolean = when (step) {
        "permission-microphone" -> ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        "permission-notifications" -> Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        "permission-full-screen" -> Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
        else -> false
    }

    fun showRationale(permission: String): Boolean =
        context.findActivity()?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, permission) } ?: true

    fun reportPermissionResult(granted: Boolean, step: String) {
        handleSetupPermissionResult(granted,
            advance = { permissionDenied = false; permissionNotice = ""; onAdvancePermission() },
            denied = {
                permissionDenied = true
                val title = when (step) {
                    "permission-microphone" -> "Microphone"
                    "permission-notifications" -> "Notifications"
                    else -> "Full-screen call alerts"
                }
                val permanent = when (step) {
                    "permission-microphone" -> setupPermissionRequiresSettings(true, showRationale(Manifest.permission.RECORD_AUDIO))
                    "permission-notifications" -> setupPermissionRequiresSettings(true, showRationale(Manifest.permission.POST_NOTIFICATIONS))
                    else -> true
                }
                permissionNotice = when {
                    step == "permission-full-screen" -> "Full-screen call alerts are still off. Open settings again or skip for now."
                    permanent -> "$title permission is blocked. Open app settings or skip for now."
                    else -> "$title permission wasn't granted. You can try again or skip for now."
                }
            },
        )
    }

    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        reportPermissionResult(granted, state.setupStep)
    }
    val settingsRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        reportPermissionResult(permissionAllowed(state.setupStep), state.setupStep)
    }
    var keepConnected by rememberSaveable(state.setupComplete, state.setupOrigin) {
        mutableStateOf(if (state.setupComplete) KeepConnectedService.isEnabled(context) else true)
    }
    var notificationAllowed by remember(context, state.setupStep) {
        mutableStateOf(Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    var voiceKeySaved by remember(context) { mutableStateOf(false) }
    LaunchedEffect(context, state.setupStep) {
        if (state.setupStep == "voice-provider") {
            val store = VoiceKeyStore(context.applicationContext)
            voiceKeySaved = withContext(Dispatchers.IO) { listOf("gemini", "grok", "openai").any(store::hasKey) }
        }
    }
    var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
    val keepConnectedPermissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationAllowed = allowed
        notificationPermissionRequested = true
    }

    fun requestKeepConnectedNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationAllowed && !notificationPermissionRequested) {
            notificationPermissionRequested = true
            keepConnectedPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    if (scanning) {
        QrScanner(
            onResult = { value -> stageCypherConnectQr(value) { scanning = false; address = setupAddressForDisplay(it); onStageLink(it) } },
            onClose = { scanning = false },
        )
        return
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Cypher", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text("A simple, secure connection to Hermes.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        Glass(Modifier.widthIn(max = 560.dp).fillMaxWidth()) {
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when (state.setupStep) {
                    "probe" -> {
                        Text("Check the server", style = MaterialTheme.typography.headlineSmall)
                        Text(visibleOrigin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.loading) {
                            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                            Text("Checking the secure connection…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Button(onClick = { onProbe(visibleOrigin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-probe-retry")) { Text("Retry") }
                            TextButton(onClick = { onReopen(visibleOrigin) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Change address") }
                            if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                        }
                    }
                    "signin" -> {
                        Text("Sign in with Hermes", style = MaterialTheme.typography.headlineSmall)
                        Text("Use your Hermes account for chat, history, plugin pages, and calls.", style = MaterialTheme.typography.bodyLarge)
                        Text(visibleOrigin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        state.setupPluginMessage.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { onSignIn(context) }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-signin")) {
                            if (state.loading) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Sign in with Hermes")
                        }
                        TextButton(onClick = { onReopen(visibleOrigin) }, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use another server") }
                        if (state.authenticated && !state.loading) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                    "unsupported" -> {
                        Text("This Hermes needs an update before phones can sign in.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { onReopen(visibleOrigin) }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Use another server") }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                    "voice-provider" -> {
                        Text("Voice & calls", style = MaterialTheme.typography.headlineSmall)
                        Text("Add a voice key for calls, or skip for now.", style = MaterialTheme.typography.bodyLarge)
                        VoiceKeySetupPanel(onKeySaved = { voiceKeySaved = true; onVoiceKeySaved() }, showTitle = false)
                        if (voiceKeySaved) Button(onClick = onAdvancePermission, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-voice-continue")) { Text("Continue") }
                        TextButton(onClick = onAdvancePermission, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("setup-voice-skip")) { Text("Skip for now") }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                    // KEEP_CONNECTED_STEP_EXTENSION_POINT; kept empty until the connection-service leaf adds its step.
                    "permission-microphone", "permission-notifications", "permission-full-screen" -> {
                        val permission = state.setupStep
                        val (title, reason) = when (permission) {
                            "permission-microphone" -> Pair(
                                "Microphone",
                                "Allow the microphone when you choose to start a call.",
                            )
                            "permission-notifications" -> Pair(
                                "Notifications",
                                "Allow notifications so call and connection updates can reach you.",
                            )
                            else -> {
                                Pair(
                                    "Full-screen call alerts",
                                    "Allow full-screen alerts so an incoming call can get your attention.",
                                )
                            }
                        }
                        val allowed = permissionAllowed(permission)
                        val permanentDenial = when (permission) {
                            "permission-microphone" -> setupPermissionRequiresSettings(permissionDenied, showRationale(Manifest.permission.RECORD_AUDIO))
                            "permission-notifications" -> setupPermissionRequiresSettings(permissionDenied, showRationale(Manifest.permission.POST_NOTIFICATIONS))
                            else -> false
                        }
                        val openAppSettings = {
                            settingsRequest.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}")))
                        }
                        val action: () -> Unit = {
                            when (permission) {
                                "permission-microphone" -> if (permanentDenial) openAppSettings() else permissionRequest.launch(Manifest.permission.RECORD_AUDIO)
                                "permission-notifications" -> if (permanentDenial) openAppSettings() else permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
                                else -> settingsRequest.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:${context.packageName}")))
                            }
                        }
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(reason, style = MaterialTheme.typography.bodyLarge)
                        permissionNotice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.setupPluginMessage.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { if (allowed) reportPermissionResult(true, permission) else action() }, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-permission-primary")) {
                            Text(when {
                                allowed -> "Continue"
                                permission == "permission-full-screen" -> "Open settings"
                                permanentDenial -> "Open app settings"
                                else -> "Allow $title"
                            })
                        }
                        TextButton(onClick = onAdvancePermission, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("setup-permission-skip")) { Text("Skip for now") }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                    "done" -> {
                        Text("You're connected", style = MaterialTheme.typography.headlineSmall)
                        Text("Cypher is ready to use with your Hermes account.", style = MaterialTheme.typography.bodyLarge)
                        state.setupPluginMessage.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("Keep connected", style = MaterialTheme.typography.titleMedium)
                                Text("Let Hermes reach this phone for incoming calls while Cypher is closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = keepConnected, onCheckedChange = { keepConnected = it; if (it) requestKeepConnectedNotifications() }, modifier = Modifier.testTag("setup-keep-connected"))
                        }
                        if (keepConnected && !notificationAllowed) Text("Allow notifications so incoming call alerts can appear.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        if (setupNeedsBackgroundExemption(ignoringBatteryOptimizations)) TextButton(onClick = onOpenBackgroundSettings, modifier = Modifier.testTag("setup-background-use")) { Text("Allow Cypher to run in the background") }
                        Button(onClick = { onFinish(keepConnected) }, enabled = state.authenticated, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-finish")) { Text("Open chat") }
                    }
                    else -> {
                        Text("Connect to Hermes", style = MaterialTheme.typography.headlineSmall)
                        Text("Scan the QR on your Hermes dashboard, or paste its address.", style = MaterialTheme.typography.bodyLarge)
                        externalLinkOrigin?.takeIf(String::isNotBlank)?.let {
                            Text("Address from external link: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            label = { Text("Paste link or address") },
                            placeholder = { Text("https://your-device.ts.net") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("setup-address"),
                        )
                        state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = { onProbe(address) }, enabled = !state.loading && address.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-connect")) { Text("Continue") }
                        TextButton(onClick = { scanning = true }, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("setup-scan-qr")) {
                            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                            Text("Scan QR")
                        }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                }
            }
        }
    }
}
