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
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.QrCodeScanner
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import dev.hermes.android.AppState
import dev.hermes.android.CypherViewModel
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.PairingFailure
import dev.hermes.android.data.PairingGrant
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

internal data class CypherConnectInput(
    val origin: String,
    val sessionToken: String? = null,
    val pairingCode: String? = null,
) {
    override fun toString() = "CypherConnectInput(origin=$origin, sessionToken=<redacted>, pairingCode=<redacted>)"
}

private enum class SetupPairingStage { Redeeming, Confirm, Probing, SignIn, Linking, Verify }
private enum class SetupPairingRetry { Redeem, Probe, Link }

private fun pairingSecurityLabel(address: String): String =
    if (Uri.parse(address).host.orEmpty().endsWith(".ts.net", ignoreCase = true)) "Tailscale · HTTPS" else "HTTPS"

@Composable
private fun SetupPairingStepIndicator(stage: SetupPairingStage?) {
    val current = when (stage) {
        SetupPairingStage.Confirm -> 1
        SetupPairingStage.Probing, SetupPairingStage.SignIn -> 2
        SetupPairingStage.Linking, SetupPairingStage.Verify -> 3
        else -> 0
    }
    val labels = listOf("Connect", "Confirm", "Sign in", "Verify")
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
    val pairingCodes = when {
        scannerPayload != null && scannerPayload.has("code") -> listOf(scannerPayload.optString("code"))
        link -> uri.getQueryParameters("code")
        else -> emptyList()
    }
    require(pairingCodes.size <= 1) { "This Cypher QR has an invalid pairing code. Scan it again." }
    val pairingCode = pairingCodes.singleOrNull()?.let { dev.hermes.android.data.normalizePairingCode(it) }
    val explicitScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*://").containsMatchIn(address.trim())
    return CypherConnectInput(trustedOrigin(if (explicitScheme) address else "https://$address"), session, pairingCode)
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
        onRedeemPairing = { origin, code -> NativePairing.redeem(origin, code) },
        onLinkPairing = { grant -> NativePairing.link(context, vm.client ?: throw PairingFailure("Sign in with Hermes before linking this phone."), grant) },
        onOpenBackgroundSettings = { if (!requestBackgroundUse(context)) vm.notice("Open Cypher’s app settings in Android and allow background use.") },
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
    var pairingInputCode by remember { mutableStateOf("") }
    var pairingSetupLink by remember { mutableStateOf("") }
    var pairingOrigin by remember { mutableStateOf("") }
    var pairingStage by remember { mutableStateOf<SetupPairingStage?>(null) }
    var pairingGrant by remember { mutableStateOf<PairingGrant?>(null) }
    var pairingError by remember { mutableStateOf("") }
    var pairingRetry by remember { mutableStateOf(SetupPairingRetry.Redeem) }
    var linkAttempt by remember { mutableStateOf(0) }
    val pairingScope = rememberCoroutineScope()
    val latestOnRedeemPairing by rememberUpdatedState(onRedeemPairing)
    val latestOnLinkPairing by rememberUpdatedState(onLinkPairing)
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
        pairingGrant = null
        pairingRetry = SetupPairingRetry.Redeem
        latestOnStageLink(setupLink)
        pairingStage = SetupPairingStage.Redeeming
        pairingScope.launch {
            try {
                pairingGrant = latestOnRedeemPairing(origin, normalized)
                pairingStage = SetupPairingStage.Confirm
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pairingError = error.message ?: "Couldn't check that code. Check the address and try again."
                pairingRetry = SetupPairingRetry.Redeem
                pairingStage = SetupPairingStage.Verify
            }
        }
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
        val qr = runCatching { parseCypherConnectLink(value) }.getOrNull()?.takeIf { it.pairingCode != null }
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
        val origin = runCatching { parseCypherConnectInput(address) }.getOrElse {
            pairingError = it.message ?: "Enter an HTTPS Hermes address."
            return
        }
        val setupLink = pairingSetupLink.ifBlank { origin }
        beginPairingRedeem(origin, pairingInputCode, setupLink)
    }

    fun retryPairing() {
        when (pairingRetry) {
            SetupPairingRetry.Redeem -> beginPairingRedeem(pairingOrigin, pairingInputCode, pairingSetupLink.ifBlank { pairingOrigin })
            SetupPairingRetry.Probe -> pairingGrant?.let {
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

    fun stageQr(value: String): Boolean = stageCypherConnectQr(value) {
        val parsed = parseCypherConnectLink(it, allowAddress = false)
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
                    else handleQrScanResult(value, photoDetected, ::stageQr) { photoError = INVALID_CYPHER_QR_HINT }
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

    LaunchedEffect(pairingStage, state.setupStep, state.loading, state.authenticated, state.origin) {
        val grant = pairingGrant
        when (pairingStage) {
            SetupPairingStage.Probing -> when {
                state.authenticated && grant != null && state.origin == grant.dashboardUrl -> pairingStage = SetupPairingStage.Linking
                !state.loading && state.setupStep == "signin" -> pairingStage = SetupPairingStage.SignIn
                !state.loading && (state.error.isNotBlank() || state.setupStep in setOf("probe", "unsupported", "connect")) -> {
                    pairingError = state.error.ifBlank {
                        if (state.setupStep == "unsupported") "This Hermes needs an update before phones can sign in." else "Couldn't reach Hermes. Check the address and try again."
                    }
                    pairingRetry = SetupPairingRetry.Probe
                    pairingStage = SetupPairingStage.Verify
                }
            }
            SetupPairingStage.SignIn -> if (state.authenticated && grant != null && state.origin == grant.dashboardUrl) {
                pairingStage = SetupPairingStage.Linking
            }
            else -> Unit
        }
    }

    LaunchedEffect(pairingStage, linkAttempt, state.authenticated, state.origin) {
        val grant = pairingGrant ?: return@LaunchedEffect
        if (pairingStage != SetupPairingStage.Linking) return@LaunchedEffect
        if (!state.authenticated || state.origin != grant.dashboardUrl) {
            pairingError = "Sign in with the Hermes dashboard shown above before linking this phone."
            pairingRetry = SetupPairingRetry.Probe
            pairingStage = SetupPairingStage.Verify
            return@LaunchedEffect
        }
        try {
            latestOnLinkPairing(grant)
            pairingGrant = null
            pairingStage = null
            pairingInputCode = ""
            pairingSetupLink = ""
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            pairingError = error.message ?: "Couldn't link this phone. Retry or choose another method."
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
            Text("Cypher", style = MaterialTheme.typography.displaySmall)
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
                                SetupPairingStage.Redeeming -> {
                                    Text("Checking pairing code", style = MaterialTheme.typography.headlineSmall)
                                    BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 32.dp)
                                    Text("Connecting to the Hermes dashboard…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SetupPairingStage.Confirm -> pairingGrant?.let { grant ->
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
                                    Text("After you confirm, sign in with your Hermes account to link this phone.", style = MaterialTheme.typography.bodyMedium)
                                    Button(
                                        onClick = { pairingError = ""; pairingStage = SetupPairingStage.Probing; onProbe(grant.dashboardUrl) },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-confirm"),
                                    ) { Text("Continue") }
                                    TextButton(onClick = { pairingStage = null; pairingGrant = null; pairingError = "" }) { Text("Back") }
                                } ?: Text("The pairing details couldn't be read. Choose another method.", color = MaterialTheme.colorScheme.error)
                                SetupPairingStage.Probing -> {
                                    Text("Checking the secure connection…", style = MaterialTheme.typography.headlineSmall)
                                    BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 32.dp)
                                    Text(pairingGrant?.dashboardUrl.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SetupPairingStage.SignIn -> {
                                    Text("Sign in with Hermes", style = MaterialTheme.typography.headlineSmall)
                                    pairingGrant?.let { Text("${it.displayName} · ${it.dashboardUrl}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    Text("Use your Hermes account for chat, history, plugin pages, and calls.", style = MaterialTheme.typography.bodyMedium)
                                    state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                    Button(
                                        onClick = { onSignIn(context) },
                                        enabled = !state.loading,
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-signin"),
                                    ) {
                                        if (state.loading) BeaconSignal(BeaconMotionMode.Sweep, size = 20.dp) else Text("Sign in with Hermes")
                                    }
                                    TextButton(onClick = { pairingStage = SetupPairingStage.Confirm }) { Text("Back") }
                                }
                                SetupPairingStage.Linking -> {
                                    Text("Linking this phone", style = MaterialTheme.typography.headlineSmall)
                                    BeaconSignal(BeaconMotionMode.Sweep, Modifier.align(Alignment.CenterHorizontally), 32.dp)
                                    Text("Your phone will appear in Hermes Devices.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                SetupPairingStage.Verify -> {
                                    Text("Couldn't finish pairing", style = MaterialTheme.typography.headlineSmall)
                                    Text(pairingError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("setup-pairing-error"))
                                    Button(onClick = ::retryPairing, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-retry")) { Text("Retry") }
                                    TextButton(onClick = {
                                        pairingStage = null
                                        pairingGrant = null
                                        pairingError = ""
                                        pairingMode = "address"
                                        pairingInputCode = ""
                                        pairingSetupLink = ""
                                    }) { Text("Choose another method") }
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
                            if (state.loading) BeaconSignal(BeaconMotionMode.Sweep, size = 20.dp) else Text("Sign in with Hermes")
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
                        Text("Choose how to connect to Hermes.", style = MaterialTheme.typography.bodyLarge)
                        externalLinkOrigin?.takeIf(String::isNotBlank)?.let {
                            Text("Address from external link: $it", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { qrScanner = scannerForPhoto(); scanning = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("setup-scan-qr")) {
                                Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
                                Text("Scan QR code")
                            }
                            OutlinedButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("setup-choose-image")) {
                                Icon(Icons.Rounded.PhotoLibrary, contentDescription = null)
                                Text("Choose QR image")
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (pairingMode == "code") Button(onClick = { pairingMode = "code" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("setup-method-code")) { Text("Enter pairing code") }
                            else OutlinedButton(onClick = { pairingMode = "code"; pairingError = "" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("setup-method-code")) { Text("Enter pairing code") }
                            if (pairingMode == "address") Button(onClick = { pairingMode = "address" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("setup-method-address")) { Text("Type address") }
                            else OutlinedButton(onClick = { pairingMode = "address"; pairingError = "" }, modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("setup-method-address")) { Text("Type address") }
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
                        if (pairingMode == "code") {
                            OutlinedTextField(
                                value = pairingInputCode,
                                onValueChange = { value ->
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
                            pairingError.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Button(
                                onClick = ::submitPairingCode,
                                enabled = !state.loading && runCatching { normalizePairingCode(pairingInputCode) }.isSuccess && address.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-pairing-redeem"),
                            ) { Text("Continue") }
                        } else {
                            state.error.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            state.notice.takeIf(String::isNotBlank)?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Button(onClick = { onProbe(address) }, enabled = !state.loading && address.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("setup-connect")) { Text("Continue") }
                        }
                        photoError.takeIf(String::isNotBlank)?.let { Text(it, color = if (it == "No QR code found in that image") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("setup-photo-error")) }
                        if (state.authenticated) TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Close setup") }
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
