package dev.hermes.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.Context
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.CypherViewModel
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.validLoopbackSessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CypherConnectInput(val origin: String, val sessionToken: String? = null) {
    override fun toString() = "CypherConnectInput(origin=$origin, sessionToken=<redacted>)"
}

internal fun parseCypherConnectLink(value: String): CypherConnectInput {
    val input = value.trim()
    val uri = Uri.parse(input)
    val link = uri.scheme.equals("cypher", ignoreCase = true)
    val address = if (link) {
        require(uri.host == "connect" && uri.path.isNullOrEmpty()) { "Scan a Cypher connection QR or paste an HTTPS Hermes address." }
        uri.getQueryParameter("url")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This Cypher link has no server address. Scan the QR again or paste the HTTPS address.")
    } else input
    val sessions = if (link) uri.getQueryParameters("session") else emptyList()
    require(sessions.size <= 1) { "This Cypher link has an invalid session. Scan the Connect QR again." }
    val session = sessions.singleOrNull()?.takeIf(String::isNotBlank)
    require(session == null || validLoopbackSessionToken(session)) { "This Cypher link has an invalid session. Scan the Connect QR again." }
    return CypherConnectInput(trustedOrigin(address), session)
}

internal fun parseCypherConnectInput(value: String): String = parseCypherConnectLink(value).origin

internal fun setupAddressForDisplay(value: String): String {
    val input = value.trim()
    val uri = Uri.parse(input)
    if (!uri.scheme.equals("cypher", ignoreCase = true)) return value
    if (!uri.host.equals("connect", ignoreCase = true)) return ""
    return runCatching { trustedOrigin(uri.getQueryParameter("url").orEmpty()) }.getOrDefault("")
}

@Composable
fun SetupWizard(vm: CypherViewModel, modifier: Modifier = Modifier) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    SetupWizardScreen(
        state = state,
        modifier = modifier,
        onProbe = { vm.probeSetupAddress(it) },
        onSignIn = { vm.signIn(context) },
        onAdvancePermission = vm::advanceSetupPermission,
        onVoiceKeySaved = vm::voiceKeyConfigured,
        onFinish = { vm.finishSetupWizard(it) },
        onReopen = vm::reopenSetupWizard,
        onCancel = vm::cancelSetupWizard,
        onStageLink = vm::openSetupLink,
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
) {
    val context = LocalContext.current
    val initialAddress = setupAddressForDisplay(state.setupOrigin.ifBlank { state.origin })
    val visibleOrigin = setupAddressForDisplay(state.setupOrigin.ifBlank { state.origin })
    var address by rememberSaveable(initialAddress) { mutableStateOf(initialAddress) }
    var scanning by rememberSaveable { mutableStateOf(false) }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onAdvancePermission() }
    val settingsRequest = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onAdvancePermission() }
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
            onResult = { value -> scanning = false; address = setupAddressForDisplay(value); onStageLink(value) },
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
                        Text("Add a provider key for one-tap realtime calls. You can skip this and add it later in Settings.", style = MaterialTheme.typography.bodyLarge)
                        VoiceKeySetupPanel(onKeySaved = { voiceKeySaved = true; onVoiceKeySaved() }, showTitle = false)
                        if (voiceKeySaved) Button(onClick = onAdvancePermission, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-voice-continue")) { Text("Continue") }
                        TextButton(onClick = onAdvancePermission, modifier = Modifier.align(Alignment.CenterHorizontally).testTag("setup-voice-skip")) { Text("Skip for now") }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
                    }
                    // KEEP_CONNECTED_STEP_EXTENSION_POINT; kept empty until the connection-service leaf adds its step.
                    "permission-microphone", "permission-notifications", "permission-full-screen" -> {
                        val permission = state.setupStep
                        val (title, reason, allowed) = when (permission) {
                            "permission-microphone" -> Triple(
                                "Microphone",
                                "Allow the microphone when you choose to start a call.",
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
                            )
                            "permission-notifications" -> Triple(
                                "Notifications",
                                "Allow notifications so call and connection updates can reach you.",
                                Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
                            )
                            else -> {
                                val fullScreenAllowed = Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java)?.canUseFullScreenIntent() == true
                                Triple(
                                    "Full-screen call alerts",
                                    "Allow full-screen alerts so an incoming call can get your attention.",
                                    fullScreenAllowed,
                                )
                            }
                        }
                        val action: () -> Unit = when (permission) {
                            "permission-microphone" -> { { permissionRequest.launch(Manifest.permission.RECORD_AUDIO) } }
                            "permission-notifications" -> { { permissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS) } }
                            else -> { { settingsRequest.launch(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT).setData(Uri.parse("package:${context.packageName}"))) } }
                        }
                        Text(title, style = MaterialTheme.typography.headlineSmall)
                        Text(reason, style = MaterialTheme.typography.bodyLarge)
                        state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        state.setupPluginMessage.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Button(onClick = if (allowed) onAdvancePermission else action, enabled = !state.loading, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-permission-primary")) {
                            Text(if (allowed) "Continue" else "Allow $title")
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
                        Text("Android battery settings may delay background calls. Allow Cypher to run in the background and keep Tailscale connected.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { onFinish(keepConnected) }, enabled = state.authenticated, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-finish")) { Text("Open chat") }
                    }
                    else -> {
                        Text("Connect to Hermes", style = MaterialTheme.typography.headlineSmall)
                        Text("Scan the Cypher QR on your Hermes dashboard, or paste its link or HTTPS server address.", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            value = address,
                            onValueChange = { value ->
                                val input = value.trim()
                                val uri = Uri.parse(input)
                                if (uri.scheme.equals("cypher", ignoreCase = true) && uri.host.equals("connect", ignoreCase = true)) {
                                    val parsed = runCatching { parseCypherConnectLink(value) }.getOrNull()
                                    val hasSession = runCatching { uri.getQueryParameters("session").isNotEmpty() }.getOrDefault(false) || input.contains("session=", ignoreCase = true)
                                    if (parsed != null || hasSession) {
                                        address = setupAddressForDisplay(value)
                                        onStageLink(value)
                                    } else address = value
                                } else address = value
                            },
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
