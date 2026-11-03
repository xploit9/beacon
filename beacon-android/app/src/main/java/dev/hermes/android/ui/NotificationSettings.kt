@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.ui
import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.push.DeliveryLog
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.awaitTestArrival
import dev.hermes.android.push.explainingStop
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Where a delivery stands, first failing item first: permission, alert channels, registration, battery, the poll, a confirmed arrival. */
internal enum class DeliveryState { Checking, Unreachable, ServerSetup, PhoneOff, Muted, Off, NotWorking, Limited, Reconnecting, SignedOut, Starting, Unconfirmed, Ok }

internal data class DeliveryInputs(
    /** False when this build has no Firebase: alerts then travel only through Keep connected and there is no test to send. */
    val firebase: Boolean,
    val statusLoaded: Boolean = true, val statusError: Boolean = false, val serverReady: Boolean = true,
    val deviceAllowed: Boolean = true, val channelsMuted: Boolean = false,
    val hasTarget: Boolean = false, val registered: Boolean = false,
    val keepConnected: Boolean = false, val keepStatus: String = KeepConnectedService.STATUS_OFF,
    val backgroundLimited: Boolean = false,
    val lastAlertAt: Long = 0, val now: Long = 0,
)

/** A confirmed arrival counts for a week; after that the next test, or the next real alert, confirms it again. */
internal const val ARRIVAL_FRESH_MILLIS = 7 * 24 * 3_600_000L

internal fun arrivalConfirmed(lastAlertAt: Long, now: Long): Boolean = lastAlertAt > 0 && now - lastAlertAt in 0..ARRIVAL_FRESH_MILLIS

/** The one state the hero names: Ok only when every check holds, else the first one that fails. */
internal fun deliveryState(i: DeliveryInputs): DeliveryState = when {
    i.firebase && !i.statusLoaded -> DeliveryState.Checking
    i.firebase && i.statusError -> DeliveryState.Unreachable
    i.firebase && !i.serverReady -> DeliveryState.ServerSetup
    !i.firebase && !i.keepConnected -> DeliveryState.Off
    !i.deviceAllowed -> DeliveryState.PhoneOff
    i.channelsMuted -> DeliveryState.Muted
    i.firebase && !i.registered -> if (i.hasTarget) DeliveryState.NotWorking else DeliveryState.Off
    i.backgroundLimited -> DeliveryState.Limited
    i.keepConnected && i.keepStatus == KeepConnectedService.STATUS_RECONNECTING -> DeliveryState.Reconnecting
    i.keepConnected && i.keepStatus == KeepConnectedService.STATUS_SIGNED_OUT -> DeliveryState.SignedOut
    i.keepConnected && i.keepStatus != KeepConnectedService.STATUS_CONNECTED -> DeliveryState.Starting
    i.firebase && !arrivalConfirmed(i.lastAlertAt, i.now) -> DeliveryState.Unconfirmed
    else -> DeliveryState.Ok
}

internal fun deliveryStatus(state: DeliveryState): SettingsStatus = when (state) {
    DeliveryState.Checking, DeliveryState.Starting -> SettingsStatus.Working
    DeliveryState.Unreachable, DeliveryState.SignedOut -> SettingsStatus.Error
    DeliveryState.Off -> SettingsStatus.Off
    DeliveryState.Ok -> SettingsStatus.Ok
    else -> SettingsStatus.Attention
}

/** How long ago something happened, as a kind plus a count the caller words in the user's language. */
internal enum class Ago { Never, Now, Minutes, Hours, Days }

internal fun deliveryAgo(now: Long, at: Long): Pair<Ago, Long> {
    val age = now - at
    return when {
        at <= 0 -> Ago.Never to 0L
        age < 60_000 -> Ago.Now to 0L
        age < 3_600_000 -> Ago.Minutes to age / 60_000
        age < 86_400_000 -> Ago.Hours to age / 3_600_000
        else -> Ago.Days to age / 86_400_000
    }
}

/** A blocked channel means Android hides that kind of alert completely. */
internal fun alertChannelsMuted(importances: List<Int?>): Boolean = importances.any { it == NotificationManager.IMPORTANCE_NONE }

private sealed interface TestResult {
    data class Arrived(val seconds: Long) : TestResult
    data object Missing : TestResult
}

private const val PUSH_STATUS_PATH = "/api/plugins/beacon/mobile/notifications/status"
private const val PUSH_TEST_PATH = "/api/plugins/beacon/mobile/notifications/test"

/** How long ago [at] was, in the one wording the whole app uses ("5 min ago", "2 h ago"), in the phone's language. */
@Composable internal fun agoText(now: Long, at: Long): String {
    val (kind, count) = deliveryAgo(now, at)
    return when (kind) {
        Ago.Never -> uiText("Never")
        Ago.Now -> uiText("Just now")
        Ago.Minutes -> uiText("{0} min ago").replace("{0}", "$count")
        Ago.Hours -> uiText("{0} h ago").replace("{0}", "$count")
        Ago.Days -> uiText("{0} d ago").replace("{0}", "$count")
    }
}

@Composable internal fun NotificationSettings(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshingKeys by vm.refreshing.collectAsStateWithLifecycle()
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
    val registrationUnavailable = uiText("Can’t set up notifications now. Check your network.")
    val couldntTurnOn = uiText("Couldn’t turn on notifications. Try again.")
    val couldntTurnOff = uiText("Couldn’t turn off notifications. Try again.")
    val testFailed = uiText("Couldn’t queue a test. Try again.")
    val appSettingsHint = uiText("Open Beacon’s app settings in Android")
    val backgroundHint = uiText("Open Beacon’s app settings in Android and allow background use.")
    val liveUpdatesHint = uiText("Open Android notification settings to manage Live Updates")
    // Android lists only channels that exist, so they are created before "Notification types" can open them.
    val alertChannelIds = remember(context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        dev.hermes.android.push.ensureActivityChannels(manager)
        listOf(dev.hermes.android.push.alertingChannel(manager, "hermes-messages"), dev.hermes.android.push.alertingChannel(manager, "hermes-tasks"),
            dev.hermes.android.push.promptNotificationChannel(manager), dev.hermes.android.push.incomingCallChannel(manager))
    }
    val ownerScope = remember(ui.owner, ui.profile, ui.origin) { vm.client?.scope?.key }
    var requestingClient by remember(ownerScope) { mutableStateOf<BeaconClient?>(null) }
    var target by remember(ownerScope) { mutableStateOf(vm.client?.scope?.let { vm.app.push.registeredTarget(it) }) }
    var busy by remember(ownerScope) { mutableStateOf(false) }
    var testing by remember(ownerScope) { mutableStateOf(false) }
    var testResult by remember(ownerScope) { mutableStateOf<TestResult?>(null) }
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
                // A saved answer stays on screen when only the refresh failed.
                if (!historyRetentionRecord.has("history_retention_days")) { historyRetentionDays = null; historyRetentionError = historyLoadFailed }
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
    fun liveAllowed() = android.os.Build.VERSION.SDK_INT >= 36 && context.getSystemService(NotificationManager::class.java).canPostPromotedNotifications()
    var liveUpdatesAllowed by remember { mutableStateOf(liveAllowed()) }
    fun backgroundRestricted() = context.getSystemService(android.app.ActivityManager::class.java).isBackgroundRestricted
    fun batteryOptimized() = !context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun channelsMuted() = context.getSystemService(NotificationManager::class.java).let { manager -> alertChannelsMuted(alertChannelIds.map { manager.getNotificationChannel(it)?.importance }) }
    var restricted by remember { mutableStateOf(backgroundRestricted()) }
    var optimized by remember { mutableStateOf(batteryOptimized()) }
    var muted by remember { mutableStateOf(channelsMuted()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var lastAlertAt by remember { mutableLongStateOf(DeliveryLog.lastAlertAt(context)) }
    var heardAt by remember { mutableLongStateOf(DeliveryLog.heardAt(context)) }
    var held by remember { mutableIntStateOf(DeliveryLog.held(context)) }
    var droppedAt by remember { mutableLongStateOf(DeliveryLog.droppedAt(context)) }
    var stop by remember { mutableStateOf(DeliveryLog.lastStop(context)) }
    val phoneMaker = remember { thisPhoneStrictMaker() }
    val liveUpdatesSettings = remember(context) { Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).takeIf { android.os.Build.VERSION.SDK_INT >= 36 && it.resolveActivity(context.packageManager) != null } }
    // Everything the phone itself can say about delivery, read again on return and every 15 seconds while the page is visible.
    fun refreshPhone() {
        target = vm.client?.scope?.let { vm.app.push.registeredTarget(it) }
        deviceAllowed = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
        keepConnectedEnabled = KeepConnectedService.isEnabled(context)
        keepConnectedStatus = KeepConnectedService.status(context)
        keepConnectedReason = KeepConnectedService.failureReason(context)
        liveUpdatesAllowed = liveAllowed()
        restricted = backgroundRestricted()
        optimized = batteryOptimized()
        muted = channelsMuted()
        now = System.currentTimeMillis()
        lastAlertAt = DeliveryLog.lastAlertAt(context)
        heardAt = DeliveryLog.heardAt(context)
        held = DeliveryLog.held(context)
        droppedAt = DeliveryLog.droppedAt(context)
        stop = DeliveryLog.lastStop(context)
    }
    androidx.lifecycle.compose.LifecycleResumeEffect(ownerScope) {
        refreshPhone()
        if (vm.app.firebaseConfigured) vm.load("push-status", PUSH_STATUS_PATH)
        vm.load(historyRetentionKey, HISTORY_RETENTION_PATH)
        onPauseOrDispose { }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, ownerScope) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { delay(15_000); refreshPhone() } }
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
                .onSuccess { if (vm.client?.scope == captured.scope) vm.load("push-status", PUSH_STATUS_PATH) }
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
    val pushStatus = data["push-status"]
    // Reaching Hermes for the status is itself a sign of life, for "Last contact".
    LaunchedEffect(pushStatus) { if (pushStatus != null && !pushStatus.has("_error")) { DeliveryLog.markHeard(context); heardAt = DeliveryLog.heardAt(context) } }
    val serverReady = pushStatus?.optBoolean("android_configured") == true
    val registered = target != null && pushStatus?.rows("targets")?.any { it.string("target_id") == target && it.string("kind") == "fcm" && !it.optBoolean("revoked") } == true
    val delivery = pushStatus?.optJSONObject("delivery")
    val firebase = vm.app.firebaseConfigured
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
            if (vm.client?.scope == captured.scope) { target = vm.app.push.registeredTarget(captured.scope); busy = false; vm.load("push-status", PUSH_STATUS_PATH) }
        }
    }
    fun setKeepConnected(enabled: Boolean) {
        if (!enabled) { vm.setKeepConnectedEnabled(false); return }
        if (android.os.Build.VERSION.SDK_INT >= 33 && !deviceAllowed) {
            pendingKeepConnectedChoice = true
            keepConnectedPermissionRequest.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else vm.setKeepConnectedEnabled(true)
    }
    // The server queued the test; it only counts once the same event shows up on this phone, by either path.
    fun sendTest() {
        busy = true; testing = true; operationError = ""; testResult = null
        val owner = vm.client?.scope
        val started = System.currentTimeMillis()
        scope.launch {
            var eventId = ""
            vm.action(PUSH_TEST_PATH, json("kind" to "fcm"), refresh = false, successNotice = "") { _, response -> eventId = response.string("event_id") }.join()
            if (vm.client?.scope == owner) {
                if (eventId.isBlank()) operationError = testFailed
                else {
                    val arrived = awaitTestArrival { DeliveryLog.testArrivedAt(context, eventId) }
                    testResult = if (arrived != null) TestResult.Arrived(((arrived - started) / 1000).coerceAtLeast(0)) else TestResult.Missing
                }
                refreshPhone()
                vm.load("push-status", PUSH_STATUS_PATH)
            }
            testing = false; busy = false
        }
    }
    fun openAppNotifications() {
        runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }.onFailure { vm.notice(appSettingsHint) }
    }
    fun openStep(step: BackgroundStep) {
        when (step) {
            BackgroundStep.Battery -> if (optimized) { if (!requestBackgroundUse(context)) vm.notice(backgroundHint) } else if (!openAppInfo(context)) vm.notice(appSettingsHint)
            BackgroundStep.AutoLaunch -> if (!openMakerBackgroundSettings(context)) vm.notice(appSettingsHint)
            BackgroundStep.Background -> if (!openAppInfo(context)) vm.notice(appSettingsHint)
        }
    }
    val nextStep = nextBackgroundStep(optimized, restricted)
    val state = deliveryState(DeliveryInputs(
        firebase = firebase, statusLoaded = pushStatus != null, statusError = pushStatus?.has("_error") == true,
        serverReady = serverReady, deviceAllowed = deviceAllowed, channelsMuted = muted, hasTarget = target != null, registered = registered,
        keepConnected = keepConnectedEnabled, keepStatus = keepConnectedStatus, backgroundLimited = nextStep != null, lastAlertAt = lastAlertAt, now = now,
    ))
    val testReady = firebase && serverReady && registered && deviceAllowed
    SettingsList(refreshing = "push-status" in refreshingKeys || historyRetentionKey in refreshingKeys, testTag = "notification-settings") {
        item("hero") {
            val retry = uiText("Retry")
            val allow = uiText("Allow")
            val title = when {
                !firebase -> if (keepConnectedEnabled) uiText("Keep connected is on") else uiText("Keep connected is off")
                state == DeliveryState.Checking -> uiText("Checking")
                state == DeliveryState.Unreachable -> uiText("Can’t reach Hermes")
                state == DeliveryState.ServerSetup -> uiText("Set up on your Hermes")
                state == DeliveryState.PhoneOff -> uiText("Off on this phone")
                state == DeliveryState.Muted -> uiText("Alerts are muted")
                state == DeliveryState.Off -> uiText("Off for {0}").replace("{0}", profileName)
                state == DeliveryState.NotWorking -> uiText("Not working yet")
                state == DeliveryState.Limited -> uiText("Android may stop Beacon")
                state == DeliveryState.Reconnecting -> uiText("Reconnecting")
                state == DeliveryState.SignedOut -> uiText("Signed out")
                state == DeliveryState.Starting -> uiText("Starting")
                state == DeliveryState.Unconfirmed -> uiText("Not confirmed yet")
                else -> uiText("Calls and alerts on")
            }
            val line = when {
                !firebase -> if (keepConnectedEnabled) uiText("Even when Beacon is closed") else uiText("Turn it on to get calls and alerts while Beacon is closed.")
                state == DeliveryState.Unreachable -> uiText("Check your network")
                state == DeliveryState.Limited -> uiText("Allow background use")
                state == DeliveryState.Unconfirmed -> uiText("Send a test alert")
                state == DeliveryState.SignedOut -> uiText("Sign in again")
                state == DeliveryState.Ok -> uiText("Even when Beacon is closed")
                else -> ""
            }
            val primary: SettingsAction? = when (state) {
                DeliveryState.Unreachable -> SettingsAction(retry, { vm.load("push-status", PUSH_STATUS_PATH) }, enabled = !busy)
                DeliveryState.PhoneOff -> SettingsAction(allow, ::openAppNotifications)
                DeliveryState.Muted -> SettingsAction(uiText("Open settings"), ::openAppNotifications)
                DeliveryState.Off -> SettingsAction(uiText("Turn on"), { if (firebase) enable() else setKeepConnected(true) }, enabled = !busy && ownerScope != null)
                DeliveryState.NotWorking -> SettingsAction(retry, ::enable, enabled = !busy)
                DeliveryState.Limited -> nextStep?.let { step -> SettingsAction(allow, { openStep(step) }) }
                DeliveryState.Unconfirmed -> SettingsAction(uiText("Send a test"), ::sendTest, enabled = testReady && !busy, loading = testing)
                else -> null
            }
            // Without Firebase the title stays about Keep connected, so the first failing item rides along as a badge and the action.
            val noFirebaseIssue = if (firebase) null else when (state) {
                DeliveryState.PhoneOff -> uiText("Off on this phone")
                DeliveryState.Muted -> uiText("Alerts are muted")
                DeliveryState.Limited -> uiText("Android may stop Beacon")
                DeliveryState.Reconnecting -> uiText("Reconnecting")
                DeliveryState.SignedOut -> uiText("Signed out")
                else -> null
            }
            val pending = delivery?.optInt("pending") ?: 0
            val lastError = !delivery?.string("last_error").isNullOrBlank() && serverReady
            val missed = held > 0 || (droppedAt > lastAlertAt && !keepConnectedEnabled)
            SettingsStatusHero(
                status = deliveryStatus(state), title = title, line = line,
                meta = if (pending > 0 || lastError || missed || noFirebaseIssue != null) ({
                    if (pending > 0) SettingsStatusPill(SettingsStatus.Working, uiText("Waiting: {0}").replace("{0}", "$pending"))
                    if (lastError) SettingsStatusPill(SettingsStatus.Error, uiText("Last delivery failed"))
                    if (missed) SettingsStatusPill(SettingsStatus.Attention, if (held > 0) uiText("Missed: {0}").replace("{0}", "$held") else uiText("Missed alerts"))
                    noFirebaseIssue?.let { SettingsStatusPill(deliveryStatus(state), it) }
                }) else null,
                primary = primary,
                testTag = "notification-hero",
            )
        }
        item("delivery") {
            SettingsSection(uiText("Delivery"), testTag = "notification-delivery") {
                if (firebase) {
                    SettingsSwitchRow(
                        uiText("Notifications for {0}").replace("{0}", profileName), target != null, { enabled -> if (enabled) enable() else disable() },
                        icon = Icons.Rounded.Notifications, summary = if (target != null) uiText("On") else uiText("Off"),
                        enabled = requestingClient == null && (target != null || (serverReady && pushStatus?.has("_error") != true)), busy = busy && !testing,
                        testTag = "notification-toggle",
                    )
                    SettingsDivider()
                }
                SettingsSwitchRow(
                    uiText("Keep connected"), keepConnectedEnabled, ::setKeepConnected, icon = Icons.Rounded.SettingsInputAntenna,
                    summary = uiText("Calls while Beacon is closed"), enabled = ownerScope != null, testTag = "keep-connected-toggle",
                )
                if (keepConnectedEnabled) KeepConnectedDetails(
                    keepConnectedStatus, keepConnectedReason, deviceAllowed,
                    stop = explainingStop(stop, keepConnectedEnabled, keepConnectedStatus, now)?.let { if (it == "Force-stopped") uiText("Force-stopped") else uiText("Stopped by Android") },
                )
                SettingsDivider()
                SettingsMetricRow(listOf(uiText("Last alert") to agoText(now, lastAlertAt), uiText("Last contact") to agoText(now, heardAt)), Modifier.testTag("notification-last-seen"))
                if (firebase) {
                    SettingsDivider()
                    SettingsActionRow(uiText("Send a test"), ::sendTest, icon = Icons.AutoMirrored.Rounded.Send, enabled = testReady && !busy, busy = testing, busyLabel = uiText("Sending…"), testTag = "notification-test")
                    when (val result = testResult) {
                        is TestResult.Arrived -> {
                            SettingsDivider()
                            SettingsValueRow(uiText("Last test"), Modifier.testTag("notification-test-result"), icon = Icons.Rounded.NotificationsActive,
                                value = uiText("Took {0} s").replace("{0}", "${result.seconds}"), status = SettingsStatus.Ok, statusLabel = uiText("Arrived"))
                        }
                        TestResult.Missing -> {
                            SettingsDivider()
                            SettingsNavRow(uiText("Last test"), { openStep(nextStep ?: BackgroundStep.Background) }, Modifier.testTag("notification-test-result"), icon = Icons.Rounded.NotificationsActive,
                                status = SettingsStatus.Error, statusLabel = uiText("Didn’t arrive"), external = true)
                        }
                        null -> Unit
                    }
                }
                SettingsDivider()
                SettingsNavRow(uiText("Schedules"), { vm.tab("Tasks") }, icon = Icons.Rounded.Schedule)
            }
        }
        if (operationError.isNotBlank()) item("error") {
            SettingsSection(testTag = "notification-error") { SettingsErrorRow(operationError, null, "") }
        }
        item("phone") {
            val onLabel = uiText("On")
            val offLabel = uiText("Off")
            SettingsSection(uiText("Phone settings"), footer = uiText("After a force stop, open Beacon once"), testTag = "notification-phone-settings") {
                SettingsNavRow(uiText("Notification types"), ::openAppNotifications, icon = Icons.Rounded.Tune, external = true)
                if (liveUpdatesSettings != null) {
                    SettingsDivider()
                    SettingsNavRow(uiText("Live Updates"), { runCatching { context.startActivity(liveUpdatesSettings) }.onFailure { vm.notice(liveUpdatesHint) } },
                        icon = Icons.Rounded.Bolt, status = if (liveUpdatesAllowed) SettingsStatus.Ok else SettingsStatus.Attention, statusLabel = if (liveUpdatesAllowed) onLabel else offLabel, external = true)
                }
                val allowed = uiText("Allowed")
                val limited = uiText("Limited")
                if (phoneMaker == null) {
                    SettingsDivider()
                    SettingsNavRow(uiText("Background use"), { openStep(nextStep ?: BackgroundStep.Background) }, icon = Icons.Rounded.BatteryChargingFull,
                        status = if (nextStep == null) SettingsStatus.Ok else SettingsStatus.Attention, statusLabel = if (nextStep == null) allowed else limited, external = true)
                } else backgroundSteps(phoneMaker).forEachIndexed { index, step ->
                    SettingsDivider()
                    val readable = backgroundStepLimited(step, optimized, restricted)
                    val name = when (step) {
                        BackgroundStep.Battery -> uiText("Battery")
                        BackgroundStep.AutoLaunch -> uiText("Auto-launch")
                        BackgroundStep.Background -> uiText("Background")
                    }
                    SettingsNavRow("${index + 1}. $name", { openStep(step) },
                        icon = when (step) { BackgroundStep.Battery -> Icons.Rounded.BatteryChargingFull; BackgroundStep.AutoLaunch -> Icons.Rounded.RocketLaunch; BackgroundStep.Background -> Icons.Rounded.Info },
                        status = when (readable) { null -> SettingsStatus.Off; true -> SettingsStatus.Attention; false -> SettingsStatus.Ok },
                        statusLabel = when (readable) { null -> uiText("Not checked"); true -> limited; false -> allowed }, external = true)
                }
            }
        }
        item("history") {
            key(ownerScope) {
                HistoryRetentionControl(historyRetentionDays, historyRetentionSaving, historyRetentionError, ::saveHistoryRetention,
                    onRetry = { vm.load(historyRetentionKey, HISTORY_RETENTION_PATH) })
            }
        }
    }
}

/** The Keep connected state as a pill, the reason it is not connected, and what else explains silence. */
@Composable private fun KeepConnectedDetails(status: String, reason: String, deviceAllowed: Boolean, stop: String?) {
    val palette = rememberSettingsStatusPalette()
    val (pill, label) = when (status) {
        KeepConnectedService.STATUS_CONNECTED -> SettingsStatus.Ok to uiText("Connected")
        KeepConnectedService.STATUS_RECONNECTING -> SettingsStatus.Attention to uiText("Reconnecting")
        KeepConnectedService.STATUS_SIGNED_OUT -> SettingsStatus.Error to uiText("Signed out")
        else -> SettingsStatus.Working to uiText("Starting")
    }
    Column(Modifier.fillMaxWidth().padding(start = SettingsKit.DividerStart, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsStatusPill(pill, label, testTag = "keep-connected-status")
            if (!deviceAllowed) SettingsStatusPill(SettingsStatus.Attention, uiText("Call alerts may be hidden"))
            stop?.let { SettingsStatusPill(SettingsStatus.Attention, it, testTag = "keep-connected-stop") }
        }
        if (reason.isNotBlank()) Text(
            reason,
            color = if (status == KeepConnectedService.STATUS_RECONNECTING || status == KeepConnectedService.STATUS_SIGNED_OUT) palette.ink(SettingsStatus.Error) else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium, maxLines = 2,
            modifier = Modifier.testTag("keep-connected-reason"),
        )
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

/** The History section: one row for the period the server keeps, a confirmation before anything older is deleted. */
@Composable internal fun HistoryRetentionControl(
    currentDays: Int?,
    saving: Boolean,
    error: String,
    onSave: (Int) -> Unit,
    onRetry: (() -> Unit)? = null,
) {
    var pendingDays by remember { mutableStateOf<Int?>(null) }
    SettingsSection(uiText("History"), footer = uiText("All profiles"), testTag = "history-retention-section") {
        if (currentDays != null) {
            SettingsChoiceRow(
                uiText("Keep call and task history"), currentDays.toString(), historyRetentionChoices.map { it.first.toString() },
                { choice ->
                    val days = choice.toInt()
                    if (days != currentDays) { if (historyRetentionNeedsConfirmation(currentDays, days)) pendingDays = days else onSave(days) }
                },
                icon = Icons.Rounded.History,
                optionLabels = historyRetentionChoices.associate { it.first.toString() to it.second },
                enabled = !saving, style = ChoiceStyle.Menu, anchorTestTag = "history-retention-choice", valueTestTag = "history-retention-current",
            )
        } else if (error.isBlank()) SettingsSkeletonRows(1)
        else SettingsErrorRow(error, onRetry, uiText("Retry"), Modifier.testTag("history-retention-error"), retryTestTag = "history-retention-retry")
        if (saving) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).testTag("history-retention-saving"))
        if (currentDays != null && error.isNotBlank()) SettingsErrorRow(error, null, "", Modifier.testTag("history-retention-error"))
    }
    pendingDays?.let { days ->
        SettingsConfirmDialog(
            title = uiText("Shorten history retention?"),
            text = uiText("Delete call and task history older than {0}. This can’t be undone.").replace("{0}", uiText(historyRetentionLabel(days))),
            confirmLabel = uiText("Delete older history"),
            onConfirm = { pendingDays = null; onSave(days) },
            onDismiss = { if (!saving) pendingDays = null },
            modifier = Modifier.testTag("history-retention-confirmation"),
        )
    }
}
