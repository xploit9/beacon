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
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dev.hermes.android.AppState
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.ownerNameOf
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.PairingFailure
import dev.hermes.android.data.PairingGrant
import dev.hermes.android.data.PendingPairing
import dev.hermes.android.data.appErrorText
import dev.hermes.android.data.formatPairingCodeInput
import dev.hermes.android.data.normalizePairingCode
import dev.hermes.android.data.parsePairingBlock
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.validLoopbackSessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal fun handleSetupPermissionResult(granted: Boolean, advance: () -> Unit, denied: () -> Unit) {
    if (granted) advance() else denied()
}

internal fun setupNeedsBackgroundExemption(isIgnoringBatteryOptimizations: Boolean): Boolean = !isIgnoringBatteryOptimizations

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
            Column(Modifier.weight(1f)) {
                Text("Keep connected", style = MaterialTheme.typography.titleMedium)
                Text("Let Hermes reach this phone for incoming calls while Beacon is closed.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = keepConnected, onCheckedChange = onKeepConnectedChange, modifier = Modifier.testTag("setup-keep-connected"))
        }
        if (keepConnected && !notificationAllowed) Text("Allow notifications so incoming call alerts can appear.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (backgroundActionVisible) OutlinedButton(
            onClick = onOpenBackgroundSettings,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("setup-background-use"),
        ) {
            Icon(Icons.Rounded.OpenInNew, null)
            Spacer(Modifier.width(8.dp))
            Text("Allow Beacon to run in the background")
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

internal data class BeaconConnectInput(
    val origin: String,
    val sessionToken: String? = null,
    val pairingCode: String? = null,
) {
    override fun toString() = "BeaconConnectInput(origin=$origin, sessionToken=<redacted>, pairingCode=<redacted>)"
}

private enum class SetupPairingStage { Confirm, Probing, SignIn, Linking, Verify, Linked }
private enum class SetupPairingRetry { Redeem, Probe, Link }

internal class SetupPairingState {
    val pending = mutableStateOf<PendingPairing?>(null)
    val redeemed = mutableStateOf<PairingGrant?>(null)
}

private fun pairingSecurityLabel(address: String): String =
    if (Uri.parse(address).host.orEmpty().endsWith(".ts.net", ignoreCase = true)) "Tailscale · HTTPS" else "HTTPS"

@Composable
private fun SetupPairingStepIndicator(stage: SetupPairingStage?) {
    val current = when (stage) {
        SetupPairingStage.Confirm -> 1
        SetupPairingStage.Probing, SetupPairingStage.SignIn -> 2
        SetupPairingStage.Linking, SetupPairingStage.Verify, SetupPairingStage.Linked -> 3
        else -> 0
    }
    val labels = listOf("Connect", "Confirm", "Sign in", when (stage) {
        SetupPairingStage.Linking -> "Linking"
        SetupPairingStage.Linked -> "Linked"
        SetupPairingStage.Verify -> "Verify"
        else -> "Link"
    })
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            labels.indices.forEach { index ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (index <= current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                )
            }
        }
        Text("Step ${current + 1} of ${labels.size} · ${labels[current]}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SetupMethodTile(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    tag: String,
    labelTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stacked: Boolean = false,
) {
    // Across: icon over a short label. Stacked (narrow screen or large text): one full-width row per method.
    val labelText: @Composable () -> Unit = {
        Text(
            label,
            modifier = Modifier.testTag(labelTag),
            style = if (stacked) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
    }
    val content: @Composable RowScope.() -> Unit = {
        if (stacked) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null)
            labelText()
        } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null)
            labelText()
        }
    }
    val tileModifier = modifier.heightIn(min = if (stacked) 52.dp else 76.dp).testTag(tag)
    val shape = RoundedCornerShape(18.dp)
    val padding = if (stacked) PaddingValues(horizontal = 16.dp, vertical = 8.dp) else PaddingValues(horizontal = 6.dp, vertical = 10.dp)
    if (selected) {
        Button(onClick, modifier = tileModifier, shape = shape, contentPadding = padding, content = content)
    } else {
        OutlinedButton(onClick, modifier = tileModifier, shape = shape, contentPadding = padding, content = content)
    }
}

internal fun parseBeaconConnectLink(value: String, allowAddress: Boolean = true): BeaconConnectInput {
    val input = value.trim()
    val uri = Uri.parse(input)
    val scannerPayload = runCatching { org.json.JSONObject(input) }.getOrNull()
        ?.takeIf { it.optString("type") == "beacon-connect" }
    val link = uri.scheme.equals("beacon", ignoreCase = true)
    val address = when {
        scannerPayload != null -> scannerPayload.opt("url") as? String
            ?: throw IllegalArgumentException("This Beacon QR has no server address. Scan it again or paste the HTTPS address.")
        link -> {
            require(uri.host.equals("connect", ignoreCase = true) && uri.path.isNullOrEmpty()) { "Scan a Beacon connection QR or paste an HTTPS Hermes address." }
            uri.getQueryParameter("url")?.takeIf(String::isNotBlank)
                ?: throw IllegalArgumentException("This Beacon link has no server address. Scan the QR again or paste the HTTPS address.")
        }
        else -> {
            require(allowAddress) { "Scan a Beacon connection QR or paste an HTTPS Hermes address." }
            input
        }
    }
    val sessions = when {
        scannerPayload != null -> listOf((scannerPayload.opt("session") as? String)?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("This Beacon QR has an invalid session. Scan it again."))
        link -> uri.getQueryParameters("session")
        else -> emptyList()
    }
    require(sessions.size <= 1) { "This Beacon link has an invalid session. Scan the Connect QR again." }
    val session = sessions.singleOrNull()?.takeIf(String::isNotBlank)
    require(session == null || validLoopbackSessionToken(session)) { "This Beacon link has an invalid session. Scan the Connect QR again." }
    val pairingCodes = when {
        scannerPayload != null && scannerPayload.has("code") -> listOf(scannerPayload.optString("code"))
        link -> uri.getQueryParameters("code")
        else -> emptyList()
    }
    require(pairingCodes.size <= 1) { "This Beacon QR has an invalid pairing code. Scan it again." }
    val pairingCode = pairingCodes.singleOrNull()?.let { dev.hermes.android.data.normalizePairingCode(it) }
    val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(address.trim())
    return BeaconConnectInput(trustedOrigin(if (explicitScheme) address else "https://$address"), session, pairingCode)
}

internal fun parseBeaconConnectInput(value: String): String = parseBeaconConnectLink(value).origin

internal fun stageBeaconConnectQr(value: String, onStage: (String) -> Unit): Boolean =
    runCatching { parseBeaconConnectLink(value, allowAddress = false) }.fold(
        onSuccess = { onStage(value); true },
        onFailure = { false },
    )

internal fun setupAddressForDisplay(value: String): String {
    val input = value.trim()
    val scannerPayload = runCatching { org.json.JSONObject(input) }.getOrNull()
        ?.takeIf { it.optString("type") == "beacon-connect" }
    if (scannerPayload != null) return runCatching { trustedOrigin(scannerPayload.optString("url")) }.getOrDefault("")
    val uri = Uri.parse(input)
    if (!uri.scheme.equals("beacon", ignoreCase = true)) return value
    if (!uri.host.equals("connect", ignoreCase = true)) return ""
    return runCatching { trustedOrigin(uri.getQueryParameter("url").orEmpty()) }.getOrDefault("")
}

@Composable
fun SetupWizard(vm: BeaconViewModel, modifier: Modifier = Modifier, externalLinkOrigin: String? = null, onExternalLinkCleared: () -> Unit = {}) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun matchingPairingClient(origin: String) = vm.client?.takeIf { active ->
        val current = vm.ui.value
        current.authenticated && current.origin == origin && active.auth.origin == origin &&
            current.owner.isNotBlank() && current.owner == active.auth.owner && current.owner == active.scope.owner &&
            current.profile.isNotBlank() && current.profile == active.scope.profile
    }
    SetupWizardScreen(
        state = state,
        modifier = modifier,
        externalLinkOrigin = externalLinkOrigin,
        onProbe = { vm.probeSetupAddress(it) },
        onSignIn = { vm.signIn(context) },
        onAdvancePermission = vm::advanceSetupPermission,
        onSkipVoiceSetup = vm::skipVoiceSetup,
        onVoiceKeySaved = vm::voiceKeyConfigured,
        ownerName = prefs.ownerName,
        onOwnerNameChange = { vm.setOwnerName(it) },
        onFinish = { vm.finishSetupWizard(it); onExternalLinkCleared() },
        onReopen = vm::reopenSetupWizard,
        onCancel = { vm.cancelSetupWizard(); onExternalLinkCleared() },
        onStageLink = vm::openSetupLink,
        onCurrentSessionMatches = { matchingPairingClient(it) != null },
        pairingState = vm.setupPairing,
        onCompletePairing = vm::completeSetupPairing,
        onOpenBackgroundSettings = { if (!requestBackgroundUse(context)) vm.notice("Open Beacon’s app settings in Android and allow background use.") },
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
    onRedeemPairing: suspend (String, String) -> PairingGrant = { _, _ -> throw PairingFailure("Pairing is unavailable.") },
    onLinkPairing: suspend (PairingGrant) -> Unit = {},
    onCurrentSessionMatches: (String) -> Boolean = { false },
    onSkipVoiceSetup: () -> Unit = onAdvancePermission,
    ownerName: String = "",
    onOwnerNameChange: (String) -> Unit = {},
    pairingState: SetupPairingState = remember { SetupPairingState() },
    onCompletePairing: (suspend (PendingPairing) -> Unit)? = null,
) {
    val context = LocalContext.current
    val motionEnabled = rememberNativeMotionEnabled()
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
    var photoError by rememberSaveable(initialAddress) { mutableStateOf("") }
    var pairingMode by rememberSaveable(initialAddress) { mutableStateOf("address") }
    var pairingInputCode by rememberSaveable { mutableStateOf("") }
    var pairingSetupLink by rememberSaveable { mutableStateOf("") }
    var pairingOrigin by rememberSaveable { mutableStateOf("") }
    var pairingStage by rememberSaveable { mutableStateOf<SetupPairingStage?>(null) }
    var pairingPending by pairingState.pending
    var pairingRedeemed by pairingState.redeemed
    var pairingError by rememberSaveable { mutableStateOf("") }
    var pairingRetry by rememberSaveable { mutableStateOf(SetupPairingRetry.Redeem) }
    var linkAttempt by rememberSaveable { mutableStateOf(0) }
    var pairingUsingCurrentSession by rememberSaveable { mutableStateOf(false) }
    val latestOnRedeemPairing by rememberUpdatedState(onRedeemPairing)
    val latestOnLinkPairing by rememberUpdatedState(onLinkPairing)
    val latestOnCompletePairing by rememberUpdatedState(onCompletePairing)
    val latestCurrentSessionMatches by rememberUpdatedState(onCurrentSessionMatches)
    val qrOptions = remember { BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build() }
    var qrScanner by remember { mutableStateOf<BarcodeScanner?>(null) }
    val photoDetected = remember { AtomicBoolean(false) }
    val latestOnStageLink by rememberUpdatedState(onStageLink)
    // One scanner serves camera and photo; a qrScanner key would close each new one as soon as it was created.
    DisposableEffect(Unit) { onDispose { qrScanner?.close() } }
    fun scannerForPhoto(): BarcodeScanner = qrScanner ?: BarcodeScanning.getClient(qrOptions).also { qrScanner = it }

    fun beginPairingRedeem(origin: String, code: String, setupLink: String) {
        val normalized = runCatching { normalizePairingCode(code) }.getOrElse {
            pairingError = it.message ?: "Enter the six-character code shown on your PC."
            return
        }
        pairingOrigin = origin
        pairingSetupLink = setupLink
        pairingError = ""
        pairingRedeemed = null
        pairingUsingCurrentSession = false
        pairingRetry = SetupPairingRetry.Redeem
        pairingPending = runCatching { NativePairing.prepare(origin, normalized) }.getOrElse {
            pairingError = it.message ?: "Enter an HTTPS Hermes address."
            return
        }
        latestOnStageLink(setupLink)
        pairingStage = SetupPairingStage.Confirm
    }

    fun updatePairingAddress(value: String) {
        parsePairingBlock(value)?.let { block ->
            address = block.address
            pairingInputCode = formatPairingCodeInput(block.code)
            pairingMode = "code"
            pairingSetupLink = ""
            pairingError = ""
            photoError = ""
            return
        }
        val qr = runCatching { parseBeaconConnectLink(value) }.getOrNull()?.takeIf { it.pairingCode != null }
        if (qr != null) {
            address = qr.origin
            pairingInputCode = formatPairingCodeInput(qr.pairingCode.orEmpty())
            pairingMode = "code"
            pairingSetupLink = value
            pairingError = ""
            photoError = ""
            return
        }
        address = value.take(4096)
        pairingSetupLink = ""
        pairingError = ""
        photoError = ""
    }

    fun submitPairingCode() {
        val origin = runCatching { parseBeaconConnectInput(address) }.getOrElse {
            pairingError = it.message ?: "Enter an HTTPS Hermes address."
            return
        }
        val setupLink = pairingSetupLink.ifBlank { origin }
        beginPairingRedeem(origin, pairingInputCode, setupLink)
    }

    fun retryPairing() {
        when (pairingRetry) {
            SetupPairingRetry.Redeem -> beginPairingRedeem(pairingOrigin, pairingInputCode, pairingSetupLink.ifBlank { pairingOrigin })
            SetupPairingRetry.Probe -> pairingPending?.let {
                pairingStage = SetupPairingStage.Probing
                pairingError = ""
                onProbe(it.dashboardUrl)
            }
            SetupPairingRetry.Link -> {
                pairingStage = SetupPairingStage.Linking
                pairingError = ""
                linkAttempt++
            }
        }
    }

    fun stageQr(value: String): Boolean = stageBeaconConnectQr(value) {
        val parsed = parseBeaconConnectLink(it, allowAddress = false)
        scanning = false
        address = setupAddressForDisplay(it)
        parsed.pairingCode?.let { code ->
            pairingMode = "code"
            pairingInputCode = formatPairingCodeInput(code)
            beginPairingRedeem(parsed.origin, code, it)
        } ?: latestOnStageLink(it)
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        photoError = ""
        photoDetected.set(false)
        if (uri != null) try {
            scannerForPhoto().process(InputImage.fromFilePath(context, uri))
                .addOnSuccessListener { codes ->
                    val value = codes.firstNotNullOfOrNull { it.rawValue?.takeIf(String::isNotBlank) }
                    if (value == null) photoError = "No QR code found in that image"
                    else handleQrScanResult(value, photoDetected, ::stageQr) { photoError = INVALID_BEACON_QR_HINT }
                }
                .addOnFailureListener { photoError = "Couldn't read that image." }
        } catch (_: Exception) { photoError = "Couldn't read that image." }
    }
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
            voiceKeySaved = withContext(Dispatchers.IO) { val provider = store.settings().provider; store.hasKey(provider) }
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

    LaunchedEffect(Unit) {
        if (pairingPending == null && pairingStage != null && pairingStage != SetupPairingStage.Linked) {
            if (pairingStage in setOf(SetupPairingStage.Confirm, SetupPairingStage.Probing, SetupPairingStage.SignIn)) {
                pairingPending = runCatching { NativePairing.prepare(pairingOrigin, pairingInputCode) }.getOrNull()
                if (pairingPending == null) pairingStage = null
            } else {
                // A process restart has no in-memory ticket; a configuration change retains it in the ViewModel.
                pairingStage = null; pairingMode = "code"; pairingInputCode = ""; pairingSetupLink = ""
                pairingError = "Pairing was interrupted. Make a new code on your PC."
            }
        }
    }

    LaunchedEffect(pairingStage, state.setupStep, state.loading, state.authenticated, state.origin, state.owner, state.profile) {
        val grant = pairingPending
        when (pairingStage) {
            SetupPairingStage.Probing -> when {
                state.authenticated && grant != null && state.origin == grant.dashboardUrl && latestCurrentSessionMatches(grant.dashboardUrl) -> pairingStage = SetupPairingStage.Linking
                !state.loading && state.setupStep == "signin" -> pairingStage = SetupPairingStage.SignIn
                !state.loading && (state.error.isNotBlank() || state.setupStep in setOf("probe", "unsupported", "connect")) -> {
                    pairingError = state.error.ifBlank {
                        if (state.setupStep == "unsupported") "This Hermes needs an update before phones can sign in." else "Couldn't reach Hermes. Check the address and try again."
                    }
                    pairingRetry = SetupPairingRetry.Probe
                    pairingStage = SetupPairingStage.Verify
                }
            }
            SetupPairingStage.SignIn -> if (state.authenticated && grant != null && state.origin == grant.dashboardUrl && latestCurrentSessionMatches(grant.dashboardUrl)) {
                pairingStage = SetupPairingStage.Linking
            }
            else -> Unit
        }
    }

    // Keyed on the stage only: a later auth/origin update must not restart it mid-redeem and spend the single-use code twice.
    LaunchedEffect(pairingStage, linkAttempt) {
        if (pairingStage != SetupPairingStage.Linking) return@LaunchedEffect
        val grant = pairingPending
        if (grant == null) {
            pairingError = "Pairing was interrupted. Make a new code on your PC."
            pairingRetry = SetupPairingRetry.Redeem
            pairingStage = SetupPairingStage.Verify
            return@LaunchedEffect
        }
        if (!state.authenticated || state.origin != grant.dashboardUrl || !latestCurrentSessionMatches(grant.dashboardUrl)) {
            pairingError = "Sign in with the Hermes dashboard shown above before linking this phone."
            pairingRetry = SetupPairingRetry.Probe
            pairingStage = SetupPairingStage.Verify
            return@LaunchedEffect
        }
        try {
            // A redeemed code is single use, so a retry after a failed link reuses its ticket.
            val complete = latestOnCompletePairing
            if (complete != null) complete(grant) else {
                val redeemed = pairingRedeemed ?: latestOnRedeemPairing(grant.dashboardUrl, grant.code).also { pairingRedeemed = it }
                latestOnLinkPairing(redeemed)
            }
            pairingRedeemed = null
            pairingStage = SetupPairingStage.Linked
            pairingInputCode = ""
            pairingSetupLink = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pairingError = appErrorText(error)
            pairingRetry = SetupPairingRetry.Link
            pairingStage = SetupPairingStage.Verify
        }
    }

    if (scanning) {
        QrScanner(
            onResult = ::stageQr,
            onClose = { scanning = false },
            barcodeScanner = qrScanner,
        )
        return
    }

    val baseScheme = MaterialTheme.colorScheme
    val beaconSea = Color(0xff07151e)
    val beaconSurface = Color(0xff10232d)
    val beaconInk = Color(0xfff4f0e3)
    val beaconMuted = Color(0xffb3c3ca)
    val beaconScheme = baseScheme.copy(
        background = beaconSea, onBackground = beaconInk,
        surface = beaconSurface, onSurface = beaconInk, surfaceVariant = Color(0xff172b35), onSurfaceVariant = beaconMuted,
        surfaceContainerLowest = Color(0xff0b1d27), surfaceContainerLow = beaconSurface,
        surfaceContainer = beaconSurface, surfaceContainerHigh = Color(0xff142934), surfaceContainerHighest = Color(0xff1b303b),
        outline = beaconMuted.copy(alpha = .62f), outlineVariant = beaconMuted.copy(alpha = .3f),
        onPrimary = beaconSea,
        primaryContainer = Color(0xff35412c), onPrimaryContainer = beaconInk,
    )
    MaterialTheme(colorScheme = beaconScheme, typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
    CompositionLocalProvider(LocalContentColor provides beaconInk) {
    val beaconGold = MaterialTheme.colorScheme.primary
    Box(Modifier.fillMaxSize().background(beaconSea)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(
                colors = listOf(beaconGold.copy(alpha = .2f), Color.Transparent),
                center = Offset(size.width * .5f, size.height * .18f),
                radius = maxOf(size.width, size.height) * .72f,
            ))
        }
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.height(112.dp).width(112.dp), contentAlignment = Alignment.Center) {
                BeaconSignal(BeaconMotionMode.Pulse, Modifier.testTag("setup-beacon-glow"), 108.dp)
                BeaconMark(size = 74.dp, contentDescription = assistantText("Beacon"))
            }
            Spacer(Modifier.height(4.dp))
            Text(uiText("Beacon"), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(8.dp))
            Text("A simple, secure connection to Hermes.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
            Surface(
                Modifier.widthIn(max = 560.dp).fillMaxWidth(),
                shape = RoundedCornerShape(26.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .96f),
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = .28f)),
            ) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (pairingStage != null) {
                    SetupPairingStepIndicator(pairingStage)
                    AnimatedContent(
                        targetState = pairingStage!!,
                        transitionSpec = {
                            if (motionEnabled) fadeIn(tween(200)) togetherWith fadeOut(tween(180))
                            else EnterTransition.None togetherWith ExitTransition.None
                        },
                        label = "setup-pairing-step",
                    ) { current ->
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            when (current) {
                                SetupPairingStage.Confirm -> pairingPending?.let { grant ->
                                    Text("Confirm this dashboard", style = MaterialTheme.typography.headlineSmall)
                                    Text(grant.displayName, style = MaterialTheme.typography.titleLarge)
                                    Text(grant.dashboardUrl, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        shape = RoundedCornerShape(100.dp),
                                    ) {
                                        Text(pairingSecurityLabel(grant.dashboardUrl), Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
                                    }
                                    Text(
                                        if (latestCurrentSessionMatches(grant.dashboardUrl)) "Your current Hermes sign-in will link this phone."
                                        else "After you confirm, sign in with your Hermes account to link this phone.",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Button(
                                        onClick = {
                                            pairingError = ""
                                            pairingUsingCurrentSession = latestCurrentSessionMatches(grant.dashboardUrl)
                                            if (pairingUsingCurrentSession) pairingStage = SetupPairingStage.Linking
                                            else { pairingStage = SetupPairingStage.Probing; onProbe(grant.dashboardUrl) }
                                        },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-confirm"),
                                    ) { Text("Continue") }
                                    // Back to step 1 even after a sign-in attempt left the app on its plain sign-in step.
                                    TextButton(onClick = { pairingStage = null; pairingPending = null; pairingRedeemed = null; pairingError = ""; onReopen(grant.dashboardUrl) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                                } ?: Text("The pairing details couldn't be read. Choose another method.", color = MaterialTheme.colorScheme.error)
                                SetupPairingStage.Probing -> {
                                    Text("Checking the secure connection…", style = MaterialTheme.typography.headlineSmall)
                                    BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 32.dp)
                                    Text(pairingPending?.dashboardUrl.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SetupPairingStage.SignIn -> {
                                    Text("Sign in with Hermes", style = MaterialTheme.typography.headlineSmall)
                                    pairingPending?.let { Text("${it.displayName} · ${it.dashboardUrl}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text("Use your Hermes account for chat, history, plugin pages, and calls.", style = MaterialTheme.typography.bodyMedium)
                                    state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    Button(
                                        onClick = { onSignIn(context) },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-signin"),
                                    ) {
                                        if (state.loading) BeaconSignal(BeaconMotionMode.Sweep, size = 20.dp) else Text("Sign in with Hermes")
                                    }
                                    TextButton(onClick = { pairingStage = SetupPairingStage.Confirm }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Back") }
                                }
                                SetupPairingStage.Linking -> {
                                    Text("Linking this phone", style = MaterialTheme.typography.headlineSmall)
                                    BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 32.dp)
                                    Text("Checking your code and adding this phone.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SetupPairingStage.Verify -> {
                                    Text("Couldn't finish pairing", style = MaterialTheme.typography.headlineSmall)
                                    Text(pairingError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("setup-pairing-error"))
                                    Button(onClick = ::retryPairing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-retry")) { Text("Retry") }
                                    TextButton(onClick = {
                                        pairingStage = null
                                        pairingPending = null; pairingRedeemed = null
                                        pairingError = ""
                                        pairingMode = "address"
                                        pairingInputCode = ""
                                        pairingSetupLink = ""
                                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Choose another method") }
                                }
                                SetupPairingStage.Linked -> {
                                    Text("Linked", style = MaterialTheme.typography.headlineSmall)
                                    pairingPending?.let { Text("${it.displayName} · ${it.dashboardUrl}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text("This phone is paired with Hermes.", style = MaterialTheme.typography.bodyMedium)
                                    Button(
                                        onClick = {
                                            val returnToSettings = pairingUsingCurrentSession
                                            pairingStage = null
                                            pairingPending = null
                                            pairingRedeemed = null
                                            pairingError = ""
                                            pairingUsingCurrentSession = false
                                            if (returnToSettings) onCancel()
                                        },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-linked-done"),
                                    ) { Text(if (pairingUsingCurrentSession) "Done" else "Continue setup") }
                                }
                            }
                        }
                    }
                } else {
                if (state.setupStep == "connect" && externalLinkOrigin.isNullOrBlank()) SetupPairingStepIndicator(null)
                when (state.setupStep) {
                    "probe" -> {
                        Text("Check the server", style = MaterialTheme.typography.headlineSmall)
                        Text(visibleOrigin, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.loading) {
                            BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 40.dp)
                            Text("Checking the secure connection…", style = MaterialTheme.typography.bodyMedium)
                        } else {
                            state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Button(onClick = { onProbe(visibleOrigin) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-probe-retry")) { Text("Retry") }
                            OutlinedButton(onClick = { onReopen(visibleOrigin) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Change address") }
                            if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
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
                            if (state.loading) BeaconSignal(BeaconMotionMode.Sweep, size = 20.dp) else Text("Sign in with Hermes")
                        }
                        OutlinedButton(onClick = { onReopen(visibleOrigin) }, enabled = !state.loading, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Use another server") }
                        if (state.authenticated && !state.loading) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
                    }
                    "unsupported" -> {
                        Text("This Hermes needs an update before phones can sign in.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { onReopen(visibleOrigin) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Use another server") }
                        if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
                    }
                    "voice-provider" -> {
                        Text("Voice & calls", style = MaterialTheme.typography.headlineSmall)
                        Text("Add a voice key for calls, or skip for now.", style = MaterialTheme.typography.bodyLarge)
                        VoiceKeySetupPanel(
                            onKeySaved = onVoiceKeySaved,
                            onKeyAvailabilityChanged = { voiceKeySaved = it },
                            showTitle = false,
                            showCustomization = false,
                        )
                        if (voiceKeySaved) Button(onClick = onAdvancePermission, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-voice-continue")) { Text("Continue") }
                        TextButton(onClick = onSkipVoiceSetup, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("setup-voice-skip")) { Text("Skip for now") }
                        if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
                    }
                    "voice-customize" -> {
                        VoiceCustomizationPanel(ownerName, onOwnerNameChange, onContinue = onAdvancePermission, onSkip = onAdvancePermission)
                        if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
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
                        TextButton(onClick = onAdvancePermission, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag("setup-permission-skip")) { Text("Skip for now") }
                        if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
                    }
                    "done" -> {
                        Text("You're connected", style = MaterialTheme.typography.headlineSmall)
                        Text("Beacon is ready to use with your Hermes account.", style = MaterialTheme.typography.bodyLarge)
                        state.setupPluginMessage.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        KeepConnectedSetupGroup(
                            keepConnected = keepConnected,
                            notificationAllowed = notificationAllowed,
                            backgroundActionVisible = setupNeedsBackgroundExemption(ignoringBatteryOptimizations),
                            onKeepConnectedChange = { enabled -> keepConnected = enabled; if (enabled) requestKeepConnectedNotifications() },
                            onOpenBackgroundSettings = onOpenBackgroundSettings,
                        )
                        Button(onClick = { onFinish(keepConnected) }, enabled = state.authenticated, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-finish")) { Text("Open chat") }
                    }
                    else -> {
                        Text("Connect to Hermes", style = MaterialTheme.typography.headlineSmall)
                        Text("Choose how to connect to Hermes.", style = MaterialTheme.typography.bodyLarge)
                        externalLinkOrigin?.takeIf(String::isNotBlank)?.let {
                            Text("Address from external link: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        OutlinedTextField(
                            value = address,
                            onValueChange = ::updatePairingAddress,
                            label = { Text("Paste link or address") },
                            placeholder = { Text("https://your-device.ts.net") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            minLines = 1,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().testTag("setup-address"),
                        )
                        val codeSelected = pairingMode == "code" || pairingInputCode.isNotBlank()
                        if (codeSelected) {
                            OutlinedTextField(
                                // The cursor stays at the end: the inserted dash would otherwise leave it one place
                                // early, so typing M9HEFB came out as M9H-FBE.
                                value = TextFieldValue(pairingInputCode, TextRange(pairingInputCode.length)),
                                onValueChange = { field ->
                                    val value = field.text
                                    val block = parsePairingBlock(value)
                                    if (block != null) {
                                        address = block.address
                                        pairingInputCode = formatPairingCodeInput(block.code)
                                        pairingSetupLink = ""
                                    } else pairingInputCode = formatPairingCodeInput(value)
                                    pairingError = ""
                                },
                                label = { Text("Pairing code") },
                                placeholder = { Text("ABC-123") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("setup-pairing-code"),
                            )
                            Text("Paste the full Hermes pairing block into the address field to fill both fields.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("Or connect with:", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            // Three across only while the longest label still fits one line at the user's text size.
                            val measurer = rememberTextMeasurer()
                            val longestLabel = measurer.measure("Pairing code", MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false).size.width
                            val stacked = longestLabel > with(LocalDensity.current) { ((maxWidth - 16.dp) / 3 - 16.dp).toPx() }
                            val tiles: List<@Composable (Modifier) -> Unit> = listOf(
                                { m -> SetupMethodTile(
                                    label = "Scan QR", icon = Icons.Rounded.QrCodeScanner, selected = pairingMode == "scan",
                                    tag = "setup-scan-qr", labelTag = "setup-method-label-scan",
                                    onClick = { pairingMode = "scan"; pairingInputCode = ""; pairingError = ""; qrScanner = scannerForPhoto(); scanning = true },
                                    modifier = m, stacked = stacked,
                                ) },
                                { m -> SetupMethodTile(
                                    label = "QR image", icon = Icons.Rounded.PhotoLibrary, selected = pairingMode == "photo",
                                    tag = "setup-choose-image", labelTag = "setup-method-label-image",
                                    onClick = { pairingMode = "photo"; pairingInputCode = ""; pairingError = ""; photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                                    modifier = m, stacked = stacked,
                                ) },
                                { m -> SetupMethodTile(
                                    label = "Pairing code", icon = Icons.Rounded.Key, selected = pairingMode == "code",
                                    tag = "setup-method-code", labelTag = "setup-method-label-code",
                                    onClick = { pairingMode = "code"; pairingError = "" },
                                    modifier = m, stacked = stacked,
                                ) },
                            )
                            if (stacked) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { it(Modifier.fillMaxWidth()) } }
                            else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tiles.forEach { it(Modifier.weight(1f)) } }
                        }
                        if (codeSelected) pairingError.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        else {
                            state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        photoError.takeIf(String::isNotBlank)?.let { Text(it, color = if (it == "No QR code found in that image") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("setup-photo-error")) }
                        if (state.authenticated) OutlinedButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)) { Text("Close setup") }
                        if (codeSelected) {
                            Button(
                                onClick = ::submitPairingCode,
                                enabled = !state.loading && runCatching { normalizePairingCode(pairingInputCode) }.isSuccess && address.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-redeem"),
                            ) { Text("Continue") }
                        } else {
                            Button(onClick = { onProbe(address) }, enabled = !state.loading && address.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-connect")) { Text("Continue") }
                        }
                    }
                }
                }
                }
            }
        }
    }
    }
    }
}
