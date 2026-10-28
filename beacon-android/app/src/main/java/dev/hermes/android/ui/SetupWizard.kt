package dev.hermes.android.ui

import android.Manifest
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.AppState
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService

internal fun handleSetupPermissionResult(granted: Boolean, advance: () -> Unit, denied: () -> Unit) {
    if (granted) advance() else denied()
}

internal fun setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations: Boolean): Boolean = !isIgnoringBatteryOptimizations

/** Keep connected: one switch and, while Android still restricts Beacon, the one-tap background action. */
@Composable
internal fun KeepConnectedSetupGroup(
    keepConnected: Boolean,
    notificationAllowed: Boolean,
    backgroundActionVisible: Boolean,
    onKeepConnectedChange: (Boolean) -> Unit,
    onOpenBackgroundSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().testTag("setup-keep-connected-group"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f).padding(end = 16.dp)) {
                Text(uiText("Keep connected"), style = MaterialTheme.typography.titleMedium)
                Text(uiText("Let Hermes reach this phone for incoming calls while Beacon is closed."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(uiText("Shows a quiet notification and uses some battery."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = keepConnected, onCheckedChange = onKeepConnectedChange, modifier = Modifier.testTag("setup-keep-connected"))
        }
        if (keepConnected && !notificationAllowed) Text(uiText("Allow notifications so incoming call alerts can appear."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (backgroundActionVisible) OutlinedButton(
            onClick = onOpenBackgroundSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-background-use"),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text(uiText("Allow Beacon to run in the background"))
        }
    }
}

/** Android's one-tap "run in background" dialog, else the battery list. Behaviour adapted from Hermes Relay util/BatteryOptimizations.kt (MIT). */
internal fun requestBackgroundUse(context: Context): Boolean =
    runCatching { context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }.isSuccess

internal fun setupPermissionRequiresSettings(denied: Boolean, showRationale: Boolean): Boolean = denied && !showRationale

private tailrec fun Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal data class BeaconConnectInput(val origin: String)

/** The Hermes address in what the user pasted: a `beacon://connect` link or a plain address. */
internal fun parseBeaconConnectLink(value: String): BeaconConnectInput {
    val input = value.trim()
    val uri = Uri.parse(input)
    val link = uri.scheme.equals("beacon", ignoreCase = true)
    val address = if (link) {
        require(uri.host.equals("connect", ignoreCase = true) && uri.path.isNullOrEmpty()) { "Paste the link from your Hermes dashboard or its https address." }
        uri.getQueryParameter("url")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This link has no Hermes address. Copy it again or paste the https address.")
    } else input
    val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(address.trim())
    return BeaconConnectInput(trustedOrigin(if (explicitScheme) address else "https://$address"))
}

internal fun parseBeaconConnectInput(value: String): String = parseBeaconConnectLink(value).origin

internal fun setupAddressForDisplay(value: String): String {
    val uri = Uri.parse(value.trim())
    if (!uri.scheme.equals("beacon", ignoreCase = true)) return value
    if (!uri.host.equals("connect", ignoreCase = true)) return ""
    return runCatching { trustedOrigin(uri.getQueryParameter("url").orEmpty()) }.getOrDefault("")
}

@Composable
fun SetupWizard(vm: BeaconViewModel, modifier: Modifier = Modifier, externalLinkOrigin: String? = null, onExternalLinkCleared: () -> Unit = {}) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val backgroundHint = uiText("Open Beacon’s app settings in Android and allow background use.")
    SetupWizardScreen(
        state = state,
        modifier = modifier,
        externalLinkOrigin = externalLinkOrigin,
        onProbe = { vm.probeSetupAddress(it) },
        onSignIn = { vm.signIn(context) },
        onAdvancePermission = vm::advanceSetupPermission,
        onSkipVoiceSetup = vm::skipVoiceSetup,
        onVoicePage = vm::showVoiceSetupPage,
        onVoiceKeySaved = vm::voiceKeyConfigured,
        ownerName = prefs.ownerName,
        onOwnerNameChange = { vm.setOwnerName(it) },
        onFinish = { vm.finishSetupWizard(it); onExternalLinkCleared() },
        onReopen = vm::reopenSetupWizard,
        onCancel = { vm.cancelSetupWizard(); onExternalLinkCleared() },
        onOpenBackgroundSettings = { if (!requestBackgroundUse(context)) vm.notice(backgroundHint) },
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
    onVoicePage: (String) -> Unit = {},
    onOpenBackgroundSettings: () -> Unit = {},
    externalLinkOrigin: String? = null,
    onSkipVoiceSetup: () -> Unit = onAdvancePermission,
    ownerName: String = "",
    onOwnerNameChange: (String) -> Unit = {},
) {
    val context = LocalContext.current
    SetupSystemBars()
    val motionEnabled = rememberNativeMotionEnabled()
    val powerManager = remember(context) { context.getSystemService(android.os.PowerManager::class.java) }
    var ignoringBatteryOptimizations by remember(context) { mutableStateOf(powerManager.isIgnoringBatteryOptimizations(context.packageName)) }
    androidx.lifecycle.compose.LifecycleResumeEffect(context) {
        ignoringBatteryOptimizations = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        onPauseOrDispose { }
    }
    val initialAddress = setupAddressForDisplay(state.setupOrigin.ifBlank { state.origin })
    val visibleOrigin = initialAddress
    var address by rememberSaveable(initialAddress) { mutableStateOf(initialAddress) }
    val canContinue = !state.loading && address.isNotBlank()
    // The provider chosen on the first voice page, for the key page after it; empty means the saved one.
    var voiceKeyProvider by rememberSaveable { mutableStateOf("") }
    var keyWorks by rememberSaveable { mutableStateOf(false) }

    fun updateAddress(value: String) { address = value.take(4096) }
    fun pasteAddress() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        clip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(::updateAddress)
    }

    val microphoneLabel = uiText("Microphone")
    val notificationsLabel = uiText("Notifications")
    val fullScreenLabel = uiText("Full-screen call alerts")
    val fullScreenStillOff = uiText("Full-screen call alerts are still off. Open settings again or skip for now.")
    val permissionBlocked = uiText("{0} permission is blocked. Open app settings or skip for now.")
    val permissionNotGranted = uiText("{0} permission wasn't granted. You can try again or skip for now.")
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
                    "permission-microphone" -> microphoneLabel
                    "permission-notifications" -> notificationsLabel
                    else -> fullScreenLabel
                }
                val permanent = when (step) {
                    "permission-microphone" -> setupPermissionRequiresSettings(true, showRationale(Manifest.permission.RECORD_AUDIO))
                    "permission-notifications" -> setupPermissionRequiresSettings(true, showRationale(Manifest.permission.POST_NOTIFICATIONS))
                    else -> true
                }
                permissionNotice = when {
                    step == "permission-full-screen" -> fullScreenStillOff
                    permanent -> permissionBlocked.replace("{0}", title)
                    else -> permissionNotGranted.replace("{0}", title)
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

    AnimatedContent(
        targetState = state.setupStep,
        transitionSpec = {
            if (motionEnabled) fadeIn(tween(200)) togetherWith fadeOut(tween(180))
            else EnterTransition.None togetherWith ExitTransition.None
        },
        label = "setup-step",
    ) { current ->
        val step = setupStepNumber(current)
        when (current) {
            "probe" -> SetupShell(
                step, if (state.loading) uiText("Checking the connection…") else uiText("Check the connection"), modifier, visibleOrigin,
                content = {
                    if (state.loading) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BeaconSignal(BeaconMotionMode.Sweep, size = 40.dp) }
                    else {
                        SetupStatusLine(state.error, error = true)
                        SetupStatusLine(state.notice, error = false)
                    }
                },
                actions = {
                    if (!state.loading) {
                        SetupPrimaryButton(uiText("Retry"), { onProbe(visibleOrigin) }, Modifier.testTag("setup-probe-retry"))
                        SetupTextAction(uiText("Change address"), { onReopen(visibleOrigin) })
                        if (state.authenticated) SetupTextAction(uiText("Close setup"), onCancel)
                    }
                },
            )
            "signin" -> SetupShell(
                step, uiText("Sign in with Hermes"), modifier, uiText("Use your Hermes account."),
                content = {
                    Text(visibleOrigin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    SetupStatusLine(state.setupPluginMessage, error = false)
                    SetupStatusLine(state.error, error = true)
                    SetupStatusLine(state.notice, error = false)
                },
                actions = {
                    SetupPrimaryButton(uiText("Sign in with Hermes"), { onSignIn(context) }, Modifier.testTag("setup-signin"), enabled = !state.loading, loading = state.loading)
                    SetupTextAction(uiText("Use another server"), { onReopen(visibleOrigin) }, enabled = !state.loading)
                    if (state.authenticated && !state.loading) SetupTextAction(uiText("Close setup"), onCancel)
                },
            )
            "unsupported" -> SetupShell(
                step, uiText("Update needed"), modifier, uiText("This Hermes needs an update before phones can sign in."),
                content = {},
                actions = {
                    SetupTextAction(uiText("Use another server"), { onReopen(visibleOrigin) })
                    if (state.authenticated) SetupTextAction(uiText("Close setup"), onCancel)
                },
            )
            "voice-provider" -> {
                val page = voiceProviderPage(
                    onContinue = { provider, hasKey -> if (hasKey) onAdvancePermission() else { voiceKeyProvider = provider; onVoicePage("voice-key") } },
                    onSkip = onSkipVoiceSetup,
                    onClose = onCancel.takeIf { state.authenticated },
                )
                SetupShell(step, uiText("Choose a voice provider"), modifier, uiText("Calls use your own account. You can add or change this later in Settings."), page.content, page.actions)
            }
            "voice-key" -> {
                val provider = voiceKeyProvider.ifBlank { VoiceKeyStore(context.applicationContext).settings().provider }
                val name = voiceProviderInfo(provider).short
                val page = voiceKeyPage(
                    provider = provider, onKeySaved = onVoiceKeySaved,
                    onVerified = { keyWorks = true; onAdvancePermission() }, onContinueAnyway = onAdvancePermission,
                    onBack = { onVoicePage("voice-provider") }, onSkip = onSkipVoiceSetup,
                )
                SetupShell(step, uiText("Add your {0} key").replace("{0}", name), modifier, uiText("Calls use your {0} account.").replace("{0}", name), page.content, page.actions)
            }
            "voice-customize" -> {
                val page = voiceChoicesPage(
                    settingsMode = false, keyWorks = keyWorks, ownerName = ownerName, onOwnerNameChange = onOwnerNameChange,
                    onContinue = onAdvancePermission, onBack = { onVoicePage("voice-provider") },
                )
                SetupShell(step, uiText("Make it yours"), modifier, uiText("Pick how calls sound."), page.content, page.actions)
            }
            // KEEP_CONNECTED_STEP_EXTENSION_POINT; kept empty until the connection-service leaf adds its step.
            "permission-microphone", "permission-notifications", "permission-full-screen" -> {
                val permission = current
                val (title, reason) = when (permission) {
                    "permission-microphone" -> Pair(microphoneLabel, uiText("Allow the microphone when you start a call."))
                    "permission-notifications" -> Pair(notificationsLabel, uiText("Allow notifications so calls and updates can reach you."))
                    else -> Pair(fullScreenLabel, uiText("Allow full-screen alerts so an incoming call can get your attention."))
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
                SetupShell(
                    step, title, modifier, reason,
                    content = {
                        SetupStatusLine(permissionNotice, error = true)
                        SetupStatusLine(state.notice, error = false)
                        SetupStatusLine(state.setupPluginMessage, error = false)
                    },
                    actions = {
                        SetupPrimaryButton(
                            when {
                                allowed -> uiText("Continue")
                                permission == "permission-full-screen" -> uiText("Open settings")
                                permanentDenial -> uiText("Open app settings")
                                else -> uiText("Allow {0}").replace("{0}", title)
                            },
                            { if (allowed) reportPermissionResult(true, permission) else action() },
                            Modifier.testTag("setup-permission-primary"), enabled = !state.loading,
                        )
                        SetupTextAction(uiText("Skip for now"), onAdvancePermission, Modifier.testTag("setup-permission-skip"))
                        if (state.authenticated) SetupTextAction(uiText("Close setup"), onCancel)
                    },
                )
            }
            "done" -> SetupShell(
                step, uiText("You're connected"), modifier, assistantText("Beacon is ready."),
                content = {
                    SetupStatusLine(state.setupPluginMessage, error = false)
                    SetupContainer {
                        KeepConnectedSetupGroup(
                            keepConnected = keepConnected,
                            notificationAllowed = notificationAllowed,
                            backgroundActionVisible = setupNeedsBackgroundExemption(ignoringBatteryOptimizations),
                            onKeepConnectedChange = { enabled -> keepConnected = enabled; if (enabled) requestKeepConnectedNotifications() },
                            onOpenBackgroundSettings = onOpenBackgroundSettings,
                        )
                    }
                },
                actions = { SetupPrimaryButton(uiText("Open chat"), { onFinish(keepConnected) }, Modifier.testTag("setup-finish"), enabled = state.authenticated) },
            )
            else -> SetupShell(
                step, uiText("Connect to Hermes"), modifier, uiText("Paste the link from your Hermes dashboard."),
                content = {
                    externalLinkOrigin?.takeIf(String::isNotBlank)?.let {
                        Text(uiText("Address from external link: {0}").replace("{0}", it), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = ::updateAddress,
                        label = { Text(uiText("Paste link or address")) },
                        placeholder = { Text("https://hermes.example.com") },
                        trailingIcon = {
                            if (address.isEmpty()) IconButton(onClick = ::pasteAddress) { Icon(Icons.Rounded.ContentPaste, uiText("Paste link")) }
                            else IconButton(onClick = { updateAddress("") }) { Icon(Icons.Rounded.Close, uiText("Clear")) }
                        },
                        isError = state.error.isNotBlank(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { if (canContinue) onProbe(address) }),
                        minLines = 1,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("setup-address"),
                    )
                    var showHelp by rememberSaveable { mutableStateOf(false) }
                    SetupTextAction(uiText("Where do I find it?"), { showHelp = !showHelp }, Modifier.testTag("setup-address-help"))
                    if (showHelp) Text(uiText("In your Hermes dashboard, open Beacon and tap Copy link."), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    SetupStatusLine(state.error, error = true)
                    SetupStatusLine(state.notice, error = false)
                },
                actions = {
                    SetupPrimaryButton(uiText("Continue"), { onProbe(address) }, Modifier.testTag("setup-connect"), enabled = canContinue)
                    if (state.authenticated) SetupTextAction(uiText("Close setup"), onCancel)
                },
            )
        }
    }
}
