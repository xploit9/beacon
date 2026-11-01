@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.hermes.android.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.SettingsAction
import dev.hermes.android.ui.SettingsDivider
import dev.hermes.android.ui.SettingsList
import dev.hermes.android.ui.SettingsNavRow
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusHero
import dev.hermes.android.ui.SettingsStatusPill
import dev.hermes.android.ui.SettingsBadge
import dev.hermes.android.ui.SettingsValueRow
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Settings > App updates: GitHub's latest Beacon release, with the Hermes plugin that ships with it. */
@Composable internal fun AppUpdateScreen(updates: UpdateManager, client: BeaconClient?, connected: Boolean, pluginAbsent: Boolean, callBusy: Boolean) {
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()
    val account = client.takeIf { connected }
    var review by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    LaunchedEffect(client, connected, pluginAbsent, state.latest?.pluginVersion) { updates.refreshPlugin(account, pluginAbsent) }
    LaunchedEffect(state.plugin.status) { if (state.plugin.status == PluginStatus.NeedsReview) review = true }
    fun alertsEnabled() = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        context.getSystemService(android.app.NotificationManager::class.java).getNotificationChannel(UpdateManager.CHANNEL)?.importance != android.app.NotificationManager.IMPORTANCE_NONE
    var alertsAllowed by remember { mutableStateOf(alertsEnabled()) }
    val currentAccount by rememberUpdatedState(account)
    val currentAbsent by rememberUpdatedState(pluginAbsent)
    LifecycleResumeEffect(context) {
        val allowed = alertsEnabled()
        // Back from Android's permission pages: finish a waiting install, and tell the user now if alerts were just allowed.
        if (allowed && !alertsAllowed) updates.checkNow() else updates.resume(onUpdateScreen = true)
        // A restart Hermes refused while it was working goes ahead once Hermes is idle.
        updates.retryWaitingRestart(currentAccount, currentAbsent)
        alertsAllowed = allowed
        onPauseOrDispose { }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
        AppUpdateContent(state, BuildConfig.VERSION_NAME, callBusy, now,
            onCheck = updates::checkNow,
            onUpdate = { updates.startUpdate(account, pluginAbsent) },
            onRetry = { if (state.failure?.retriesCheck == true) updates.checkNow() else updates.startUpdate(account, pluginAbsent) },
            onCancel = updates::cancel,
            onAllow = { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onReview = { review = true },
            alertsBlocked = state.supported && !alertsAllowed,
            onAllowAlerts = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
    }
    if (review && state.plugin.status == PluginStatus.NeedsReview) PluginReviewDialog(state.plugin.review,
        onAllow = { review = false; updates.reviewPlugin(account) }, onDismiss = { review = false })
}

/** The whole screen from state alone, so every state can be shown without a network, a phone account or Hermes. */
@Composable internal fun AppUpdateContent(
    state: AppUpdateUi,
    installed: String,
    callBusy: Boolean,
    now: Long,
    onCheck: () -> Unit,
    onUpdate: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
    onAllow: () -> Unit,
    onReview: () -> Unit,
    modifier: Modifier = Modifier,
    alertsBlocked: Boolean = false,
    onAllowAlerts: () -> Unit = {},
) {
    val release = state.release
    val plugin = state.plugin
    val fade = if (rememberNativeMotionEnabled()) 200 else 0
    // Checking keeps the up-to-date hero, so nothing moves while it runs.
    val key = if (state.phase == UpdatePhase.Checking) UpdatePhase.Idle else state.phase
    SettingsList(modifier.widthIn(max = 640.dp), testTag = "update-screen") {
        item(key = "hero") {
            Crossfade(key, animationSpec = tween(fade), label = "update-state") { phase ->
                Box(Modifier.fillMaxWidth().testTag("update-state:${phase.name}")) { UpdateHero(state, phase, installed, callBusy, now, onCheck, onUpdate, onRetry, onCancel, onAllow) }
            }
        }
        if (state.supported && state.phase == UpdatePhase.Available && release != null && release.notes.isNotBlank()) item(key = "notes") {
            SettingsSection(uiText("What's new"), testTag = "update-notes-section") { Box(Modifier.padding(16.dp)) { ReleaseNotes(release.notes) } }
        }
        if (state.supported && (release != null || plugin.status != PluginStatus.Hidden)) item(key = "components") {
            SettingsSection(uiText("Components"), testTag = "update-components") {
                SettingsValueRow(uiText("App"), icon = Icons.Rounded.SystemUpdate, value = if (release != null) "$installed → ${release.name}" else installed,
                    status = if (release != null) SettingsStatus.Attention else SettingsStatus.Ok,
                    statusLabel = uiText(if (release != null) "Ready to update" else "Up to date"), testTag = "update-row-app")
                if (plugin.status != PluginStatus.Hidden) {
                    SettingsDivider()
                    PluginComponentRow(plugin, showMessage = release != null, onReview = onReview)
                }
            }
        }
        if (alertsBlocked) item(key = "alerts") {
            SettingsSection(testTag = "update-alerts") {
                SettingsNavRow(uiText("Allow update notifications"), onClick = onAllowAlerts, icon = Icons.Rounded.Notifications, external = true, testTag = "update-allow-notifications")
            }
        }
    }
}

/** Plugin states that carry a sentence of their own, shown once: in the hero when only the plugin has something to do, else on its row. */
private val PluginMessageStates = setOf(PluginStatus.Busy, PluginStatus.Failed, PluginStatus.Waiting, PluginStatus.DashboardRestart, PluginStatus.RestartFailed, PluginStatus.Newer)

/** The Hermes plugin row: versions and a status pill, or the one action it needs ([PluginStatus.NeedsReview]). */
@Composable private fun PluginComponentRow(plugin: PluginRow, showMessage: Boolean, onReview: () -> Unit) {
    val (status, label) = pluginPill(plugin.status)
    val changing = plugin.status in setOf(PluginStatus.Checking, PluginStatus.Available, PluginStatus.Updating, PluginStatus.NeedsReview, PluginStatus.Busy, PluginStatus.Failed)
    val versions = if (changing && plugin.installed.isNotBlank() && plugin.target.isNotBlank()) "${plugin.installed} → ${plugin.target}" else plugin.installed.ifBlank { plugin.target }
    val message = plugin.text.takeIf { showMessage && plugin.status in PluginMessageStates }
    // The tag sits on the row itself, so its versions, sentence and pill are read (and found) as one node.
    if (plugin.status == PluginStatus.NeedsReview) SettingsNavRow(uiText("Hermes plugin"), onClick = onReview, icon = Icons.Rounded.Hub, value = versions,
        status = status, statusLabel = label, testTag = "update-row-plugin")
    else SettingsValueRow(uiText("Hermes plugin"), icon = Icons.Rounded.Hub, value = message?.let { uiText(it) } ?: versions, status = status, statusLabel = label,
        valueMaxLines = if (message != null) 2 else 1, testTag = "update-row-plugin")
}

/** The pill a plugin state wears: short words, the sentence behind them is [PluginRow.text]. */
@Composable private fun pluginPill(status: PluginStatus): Pair<SettingsStatus, String> = when (status) {
    PluginStatus.Checking -> SettingsStatus.Working to uiText(PluginRow(status).text)
    PluginStatus.UpToDate, PluginStatus.Updated -> SettingsStatus.Ok to uiText(PluginRow(status).text)
    PluginStatus.Available -> SettingsStatus.Attention to uiText(PluginRow(status).text)
    PluginStatus.Updating -> SettingsStatus.Working to uiText(PluginRow(status).text)
    PluginStatus.NeedsReview -> SettingsStatus.Attention to uiText(PluginRow(status).text)
    PluginStatus.Restart, PluginStatus.DashboardRestart -> SettingsStatus.Attention to uiText("Restart needed")
    PluginStatus.Waiting -> SettingsStatus.Working to uiText("Waiting")
    PluginStatus.Busy -> SettingsStatus.Off to uiText("Hermes is busy")
    PluginStatus.Failed, PluginStatus.RestartFailed -> SettingsStatus.Error to uiText("Failed")
    PluginStatus.Newer -> SettingsStatus.Attention to uiText("Newer than app")
    PluginStatus.Hidden -> SettingsStatus.Off to ""
}

/** What the hero says for one phase: the verdict pill, one line of fact, and at most one primary action. */
private class HeroSpec(
    val status: SettingsStatus, val pill: String, val pillTag: String = "", val line: String = "", val lineTag: String = "",
    val primary: SettingsAction? = null, val secondary: SettingsAction? = null,
    val progress: Float? = null, val working: Boolean = false, val badge: Boolean = false, val callPill: Boolean = false,
)

@Composable private fun UpdateHero(
    state: AppUpdateUi, phase: UpdatePhase, installed: String, callBusy: Boolean, now: Long,
    onCheck: () -> Unit, onUpdate: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onAllow: () -> Unit,
) {
    val release = state.release
    val plugin = state.plugin
    val spec = heroSpec(state, phase, installed, callBusy, now, onCheck, onUpdate, onRetry, onCancel, onAllow)
    SettingsStatusHero(spec.status, "Beacon ${release?.name ?: installed}", // verbatim
        line = spec.line, lineTestTag = spec.lineTag, painter = painterResource(R.drawable.ic_beacon_logo), testTag = "update-hero",
        meta = {
            SettingsStatusPill(spec.status, spec.pill, testTag = spec.pillTag)
            // A plugin-only action also reads as Available, but the app itself is current then.
            if (spec.badge && release != null) SettingsBadge(uiText("New"), testTag = "update-badge-new")
            if (spec.callPill) SettingsStatusPill(SettingsStatus.Off, uiText(UpdateFailure.Call.text), testTag = "update-call-busy")
            if (spec.working) {
                val pluginBusy = plugin.status == PluginStatus.Updating
                val total = release?.size ?: 0L
                if (spec.progress != null && !pluginBusy && total > 0) LinearProgressIndicator(progress = { spec.progress }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
                else LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update-progress"))
            }
        },
        primary = spec.primary, secondary = spec.secondary)
}

@Composable private fun heroSpec(
    state: AppUpdateUi, phase: UpdatePhase, installed: String, callBusy: Boolean, now: Long,
    onCheck: () -> Unit, onUpdate: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onAllow: () -> Unit,
): HeroSpec {
    val release = state.release
    val plugin = state.plugin
    return when {
        !state.supported -> HeroSpec(SettingsStatus.Off, uiText("Preview build"), line = uiText("Installed manually"))
        phase == UpdatePhase.Idle -> {
            val checking = state.phase == UpdatePhase.Checking
            val ago = checkedAgo(state.checkedAt, now, uiText("just now"), uiText("{0} min ago"), uiText("{0} h ago"), uiText("{0} d ago"))
            HeroSpec(if (checking) SettingsStatus.Working else SettingsStatus.Ok, uiText(if (checking) "Checking for updates…" else "You're up to date"),
                pillTag = if (checking) "update-checking" else "update-status",
                line = if (checking || ago.isBlank()) "" else uiText("Checked {0}").replace("{0}", ago), lineTag = "update-checked",
                secondary = SettingsAction(uiText("Check for updates"), onCheck, enabled = !checking, testTag = "update-check"))
        }
        phase == UpdatePhase.Available -> availableSpec(state, callBusy, onUpdate)
        phase == UpdatePhase.Downloading -> {
            val total = release?.size ?: 0L
            val pluginBusy = plugin.status == PluginStatus.Updating
            val determinate = !pluginBusy && total > 0
            HeroSpec(SettingsStatus.Working, uiText(if (pluginBusy) "Updating the Hermes plugin…" else "Downloading"),
                line = if (determinate) progressLine(state.downloaded, total) else "", lineTag = "update-progress-text",
                secondary = SettingsAction(uiText("Cancel"), onCancel, testTag = "update-cancel"),
                progress = if (determinate) progressPercent(state.downloaded, total) / 100f else null, working = true)
        }
        phase == UpdatePhase.Installing -> HeroSpec(SettingsStatus.Working, uiText("Installing…"), working = true)
        phase == UpdatePhase.NeedsPermission -> HeroSpec(SettingsStatus.Attention, uiText("Allow installs"),
            primary = SettingsAction(uiText("Allow"), onAllow, testTag = "update-allow"))
        else -> HeroSpec(SettingsStatus.Error, uiText("Failed"), line = uiText(state.failure?.text ?: UpdateFailure.Download.text), lineTag = "update-failure",
            primary = SettingsAction(uiText("Try again"), onRetry, testTag = "update-retry"))
    }
}

/** An app release is waiting, or only the plugin has something for the owner. */
@Composable private fun availableSpec(state: AppUpdateUi, callBusy: Boolean, onUpdate: () -> Unit): HeroSpec {
    val release = state.release
    val plugin = state.plugin
    fun action(label: String) = SettingsAction(label, onUpdate, enabled = !callBusy, testTag = "update-primary")
    if (release != null) {
        val date = releaseDate(release.published)
        val meta = listOfNotNull(date.takeIf(String::isNotBlank)?.let { day -> uiText("Released {0}").replace("{0}", day) }, formatSize(release.size)).joinToString(" · ")
        return HeroSpec(SettingsStatus.Attention, uiText("Update available"), line = meta, lineTag = "update-meta", primary = action(uiText("Update")), badge = true, callPill = callBusy)
    }
    val message = uiText(plugin.text)
    return when (plugin.status) {
        PluginStatus.Restart -> HeroSpec(SettingsStatus.Attention, uiText("Restart needed"), primary = action(uiText("Restart Hermes")), callPill = callBusy)
        PluginStatus.RestartFailed -> HeroSpec(SettingsStatus.Error, uiText("Failed"), line = message, primary = action(uiText("Try again")), callPill = callBusy)
        PluginStatus.Failed -> HeroSpec(SettingsStatus.Error, uiText("Failed"), line = message, primary = action(uiText("Try again")), callPill = callBusy)
        PluginStatus.Busy -> HeroSpec(SettingsStatus.Off, uiText("Hermes is busy"), line = message, primary = action(uiText("Update")), callPill = callBusy)
        PluginStatus.Waiting -> HeroSpec(SettingsStatus.Working, uiText("Waiting"), line = message)
        PluginStatus.DashboardRestart -> HeroSpec(SettingsStatus.Attention, uiText("Restart needed"), line = message)
        PluginStatus.Newer -> HeroSpec(SettingsStatus.Attention, uiText("Newer than app"), line = message)
        PluginStatus.NeedsReview -> HeroSpec(SettingsStatus.Attention, uiText("Update available"))
        else -> HeroSpec(SettingsStatus.Attention, uiText("Update available"), primary = action(uiText("Update")), callPill = callBusy)
    }
}

/** A few lines first, the rest on "Show more"; links open in the browser, over HTTPS only. */
@Composable internal fun ReleaseNotes(notes: String) {
    val uri = LocalUriHandler.current
    val primary = MaterialTheme.colorScheme.primary
    val text = remember(notes, primary) { releaseNotesText(notes, primary) { link -> if (link.startsWith("https://")) runCatching { uri.openUri(link) } } }
    var expanded by rememberSaveable(notes) { mutableStateOf(false) }
    var overflows by remember(notes) { mutableStateOf(false) }
    Column(Modifier.testTag("update-notes")) {
        Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = if (expanded) Int.MAX_VALUE else 5, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        if (overflows || expanded) TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).testTag("update-notes-more")) {
            Text(uiText(if (expanded) "Show less" else "Show more"))
        }
    }
}

@Composable private fun PluginReviewDialog(lines: List<String>, onAllow: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("Review plugin capability changes")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText("This update changes what the plugin can access."))
                if (lines.isEmpty()) Text(uiText("No change list. Review the update on your PC first."))
                lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(uiText("Allow and update")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiText("Cancel")) } },
    )
}

/** "Updated to <version>" with What's new, once, on the first launch after the update. */
@Composable internal fun UpdatedSheet(notice: UpdatedNotice, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("update-updated-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("${uiText("Updated to")} ${notice.name}", Modifier.testTag("update-updated-title"), style = MaterialTheme.typography.titleLarge)
            if (notice.notes.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(uiText("What's new"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                ReleaseNotes(notice.notes)
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("update-updated-done")) { Text(uiText("Done")) }
        }
    }
}

/** Mounted once at the app's root: shows the "Updated" sheet and, each time Beacon comes back on screen, finishes a waiting install, checks if due, and restarts Hermes once it is idle. */
@Composable internal fun AppUpdateHost(updates: UpdateManager, client: BeaconClient? = null, pluginAbsent: Boolean = false) {
    val state by updates.state.collectAsStateWithLifecycle()
    val currentClient by rememberUpdatedState(client)
    val currentAbsent by rememberUpdatedState(pluginAbsent)
    LifecycleResumeEffect(updates) { updates.resume(); updates.retryWaitingRestart(currentClient, currentAbsent); onPauseOrDispose { } }
    state.updated?.let { UpdatedSheet(it, updates::dismissUpdated) }
}

private fun releaseDate(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault("")
