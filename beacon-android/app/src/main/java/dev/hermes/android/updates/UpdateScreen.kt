@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.updates

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.Glass
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
    LifecycleResumeEffect(context) {
        val allowed = alertsEnabled()
        // Back from Android's permission pages: finish a waiting install, and tell the user now if alerts were just allowed.
        if (allowed && !alertsAllowed) updates.checkNow() else updates.resume(onUpdateScreen = true)
        alertsAllowed = allowed
        onPauseOrDispose { }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        AppUpdateContent(state, BuildConfig.VERSION_NAME, callBusy, now,
            onCheck = updates::checkNow,
            onUpdate = { updates.startUpdate(account, pluginAbsent) },
            onRetry = { if (state.failure?.retriesCheck == true) updates.checkNow() else updates.startUpdate(account, pluginAbsent) },
            onCancel = updates::cancel,
            onAllow = { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
            onReview = { review = true },
            footer = {
                if (state.supported && !alertsAllowed) TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("update-allow-notifications")) { Text(uiText("Allow update notifications")) }
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
    footer: @Composable () -> Unit = {},
) {
    val release = state.release
    val fade = if (rememberNativeMotionEnabled()) 200 else 0
    // Checking keeps the up-to-date card, so nothing moves while it runs.
    val key = if (state.phase == UpdatePhase.Checking) UpdatePhase.Idle else state.phase
    Column(modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).testTag("update-screen"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Glass(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.ic_beacon_logo), null, Modifier.size(44.dp), tint = Color.Unspecified)
                    Text("Beacon ${release?.name ?: installed}", Modifier.weight(1f, fill = false).testTag("update-version"), style = MaterialTheme.typography.titleLarge) // verbatim
                    // A plugin-only action also reads as Available, but the app itself is current then.
                    if (state.phase == UpdatePhase.Available && release != null) NewBadge()
                }
                Crossfade(key, animationSpec = tween(fade), label = "update-state") { phase ->
                    Column(Modifier.fillMaxWidth().testTag("update-state:${phase.name}"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        when {
                            !state.supported -> Text(uiText("Preview builds are installed manually."), style = MaterialTheme.typography.bodyMedium)
                            phase == UpdatePhase.Idle -> UpdateUpToDate(state, now, onCheck)
                            phase == UpdatePhase.Available -> UpdateAvailable(state, installed, callBusy, onUpdate, onReview)
                            phase == UpdatePhase.Downloading -> UpdateDownloading(state, onCancel)
                            phase == UpdatePhase.Installing -> UpdateInstalling()
                            phase == UpdatePhase.NeedsPermission -> UpdatePermission(onAllow)
                            else -> UpdateFailed(state, onRetry)
                        }
                    }
                }
            }
        }
        footer()
    }
}

@Composable private fun NewBadge() {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.testTag("update-badge-new")) {
        Text(uiText("New"), Modifier.padding(horizontal = 10.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable private fun UpdateUpToDate(state: AppUpdateUi, now: Long, onCheck: () -> Unit) {
    val checking = state.phase == UpdatePhase.Checking
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(uiText(if (checking) "Checking for updates…" else "You're up to date"), style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.heightIn(min = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (checking) CircularProgressIndicator(Modifier.size(16.dp).testTag("update-checking"), strokeWidth = 2.dp)
            else checkedAgo(state.checkedAt, now, uiText("just now"), uiText("{0} min ago"), uiText("{0} h ago"), uiText("{0} d ago")).takeIf { it.isNotBlank() }?.let {
                Text(uiText("Checked {0}").replace("{0}", it), Modifier.testTag("update-checked"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    TextButton(onClick = onCheck, enabled = !checking, modifier = Modifier.heightIn(min = 48.dp).testTag("update-check")) { Text(uiText("Check for updates")) }
}

@Composable private fun UpdateAvailable(state: AppUpdateUi, installed: String, callBusy: Boolean, onUpdate: () -> Unit, onReview: () -> Unit) {
    val release = state.release
    val plugin = state.plugin
    release?.let {
        val date = remember(it.published) { releaseDate(it.published) }
        Text(listOfNotNull(date.takeIf(String::isNotBlank)?.let { day -> uiText("Released {0}").replace("{0}", day) }, formatSize(it.size)).joinToString(" · "), Modifier.testTag("update-meta"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (it.notes.isNotBlank()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(uiText("What's new"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            ReleaseNotes(it.notes)
        }
    }
    Column(Modifier.fillMaxWidth()) {
        UpdateRow("update-row-app", uiText("App"), if (release != null) "$installed → ${release.name}" else installed, uiText(if (release != null) "Ready to update" else "Up to date"))
        if (plugin.status != PluginStatus.Hidden) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            UpdateRow("update-row-plugin", uiText("Hermes plugin"), if (plugin.installed.isNotBlank()) "${plugin.installed} → ${plugin.target}" else plugin.target, uiText(plugin.text),
                onAction = onReview.takeIf { plugin.status == PluginStatus.NeedsReview })
        }
    }
    if (release != null || plugin.status in setOf(PluginStatus.Available, PluginStatus.Busy, PluginStatus.Failed)) {
        Button(onClick = onUpdate, enabled = !callBusy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("update-primary")) { Text(uiText("Update")) }
        if (callBusy) Text(uiText(UpdateFailure.Call.text), Modifier.testTag("update-call-busy"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun UpdateRow(tag: String, title: String, detail: String, status: String, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onAction == null) Modifier.semantics(mergeDescendants = true) {} else Modifier).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag-action")) { Text(status) }
        else Text(status, Modifier.widthIn(max = 168.dp).testTag("$tag-status"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
    }
}

@Composable private fun UpdateDownloading(state: AppUpdateUi, onCancel: () -> Unit) {
    val total = state.release?.size ?: 0L
    val pluginBusy = state.plugin.status == PluginStatus.Updating
    Text(uiText(if (pluginBusy) "Updating the Hermes plugin…" else "Downloading"), style = MaterialTheme.typography.bodyMedium)
    if (pluginBusy || total <= 0) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update-progress"))
    else {
        LinearProgressIndicator(progress = { progressPercent(state.downloaded, total) / 100f }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
        Text(progressLine(state.downloaded, total), Modifier.testTag("update-progress-text"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextButton(onClick = onCancel, modifier = Modifier.heightIn(min = 48.dp).testTag("update-cancel")) { Text(uiText("Cancel")) }
}

@Composable private fun UpdateInstalling() {
    Text(uiText("Installing…"), style = MaterialTheme.typography.bodyMedium)
    LinearProgressIndicator(Modifier.fillMaxWidth().testTag("update-progress"))
}

@Composable private fun UpdatePermission(onAllow: () -> Unit) {
    Text(uiText("Allow Beacon to install updates."), style = MaterialTheme.typography.bodyMedium)
    Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("update-allow")) { Text(uiText("Allow")) }
}

@Composable private fun UpdateFailed(state: AppUpdateUi, onRetry: () -> Unit) {
    Text(uiText(state.failure?.text ?: UpdateFailure.Download.text), Modifier.testTag("update-failure"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("update-retry")) { Text(uiText("Try again")) }
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
                Text(uiText("This update changes what the Beacon plugin can access. Review the changes before continuing."))
                if (lines.isEmpty()) Text(uiText("Hermes did not provide a change list. Review the plugin update on your PC before allowing it."))
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

/** Mounted once at the app's root: shows the "Updated" sheet and, each time Beacon comes back on screen, finishes a waiting install or checks if due. */
@Composable internal fun AppUpdateHost(updates: UpdateManager) {
    val state by updates.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(updates) { updates.resume(); onPauseOrDispose { } }
    state.updated?.let { UpdatedSheet(it, updates::dismissUpdated) }
}

private fun releaseDate(iso: String): String = runCatching {
    Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
}.getOrDefault("")
