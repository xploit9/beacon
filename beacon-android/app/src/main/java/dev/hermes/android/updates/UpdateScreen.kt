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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.ui.BeaconDustCore
import dev.hermes.android.ui.SettingsAction
import dev.hermes.android.ui.SettingsActionRow
import dev.hermes.android.ui.SettingsDivider
import dev.hermes.android.ui.SettingsList
import dev.hermes.android.ui.SettingsNavRow
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.SettingsStatusHero
import dev.hermes.android.ui.SettingsStatusPill
import dev.hermes.android.ui.SettingsBadge
import dev.hermes.android.ui.SettingsValueRow
import dev.hermes.android.ui.rememberBeaconClock
import dev.hermes.android.ui.rememberNativeMotionEnabled
import dev.hermes.android.ui.rememberSettingsStatusPalette
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.sin

/** Settings > App updates: GitHub's latest Beacon release, with the Beacon plugin that ships with it. */
@Composable internal fun AppUpdateScreen(updates: UpdateManager, client: BeaconClient?, connected: Boolean, pluginAbsent: Boolean, callBusy: Boolean) {
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()
    val account = client.takeIf { connected }
    var review by remember { mutableStateOf(false) }
    val now by produceState(System.currentTimeMillis()) { while (true) { delay(30_000); value = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { updates.screenOpened() }
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
    // The hero's action is the screen's one primary action: a checklist then has no Try again of its own.
    val hero = heroActions(state, key)
    val said = heroSentence(state, key)
    SettingsList(modifier.widthIn(max = 640.dp), testTag = "update-screen") {
        item(key = "hero") {
            Crossfade(key, animationSpec = tween(fade), label = "update-state") { phase ->
                Box(Modifier.fillMaxWidth().testTag("update-state:${phase.name}")) { UpdateHero(state, phase, installed, callBusy, now, onCheck, onUpdate, onRetry, onCancel, onAllow) }
            }
        }
        if (state.supported && state.phase == UpdatePhase.Available && release != null && release.notes.isNotBlank()) item(key = "notes") {
            SettingsSection(uiText("What's new"), testTag = "update-notes-section") { Box(Modifier.padding(16.dp)) { ReleaseNotes(release.notes) } }
        }
        // While a component updates, its checklist stands in for its row: the same facts, step by step.
        val pluginChecklist = state.supported && state.pluginSteps.isNotEmpty()
        val appChecklist = state.supported && state.appSteps.isNotEmpty()
        if (pluginChecklist) item(key = "plugin-steps") {
            StepsSection(uiText("Beacon plugin"), state.pluginSteps, "update-steps-plugin", doneDetail = plugin.installed, percent = null,
                said = said, onRetry = onUpdate.takeIf { stepperRetryShown(state, state.pluginSteps, hero) })
        }
        if (appChecklist) item(key = "app-steps") {
            val total = release?.size ?: 0L
            StepsSection(uiText("App"), state.appSteps, "update-steps-app", doneDetail = release?.name.orEmpty(),
                percent = if (total > 0) progressPercent(state.downloaded, total) else null,
                said = said, onRetry = onUpdate.takeIf { stepperRetryShown(state, state.appSteps, hero) })
        }
        val showApp = !appChecklist
        val showPlugin = !pluginChecklist && plugin.status != PluginStatus.Hidden
        if (state.supported && (release != null || plugin.status != PluginStatus.Hidden) && (showApp || showPlugin)) item(key = "components") {
            SettingsSection(uiText("Components"), testTag = "update-components") {
                if (showApp) SettingsValueRow(uiText("App"), icon = Icons.Rounded.SystemUpdate, value = if (release != null) "$installed → ${release.name}" else installed,
                    status = if (release != null) SettingsStatus.Attention else SettingsStatus.Ok,
                    statusLabel = uiText(if (release != null) "Ready to update" else "Up to date"), testTag = "update-row-app")
                if (showPlugin) {
                    if (showApp) SettingsDivider()
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

/**
 * One component's checklist as a vertical stepper: one connected line, a glyph per step (done ✓, working in gold dust, waiting ○,
 * failed !) beside its label, one short line when a step failed or is held back, and Try again when one failed and the hero has no action of
 * its own ([onRetry] is null otherwise). A note that says what the hero [said] already is left out. The line fills in gold
 * as the update passes each step. [percent] is the live download figure, shown on its step, and [doneDetail] the version the closing
 * Done row reports once everything finished. Each row is read as one node whose state ("Working", "Done"…) is its state description.
 */
@Composable private fun StepsSection(title: String, rows: List<StepRow>, tag: String, doneDetail: String, percent: Int?, said: String, onRetry: (() -> Unit)?) {
    // The dust only turns while a step runs; a finished or failed list is still.
    val clock = rememberBeaconClock(rememberNativeMotionEnabled() && rows.active != null)
    SettingsSection(title, testTag = tag) {
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            rows.forEachIndexed { index, row ->
                val detail = when {
                    row.step.last && row.state == StepState.Done -> doneDetail
                    row.step == UpdateStep.AppDownload && row.state == StepState.Active && percent != null -> "$percent%" // verbatim
                    else -> ""
                }
                StepItem(row.copy(note = visibleStepNote(row, said)), previous = index > 0, reached = index > 0 && rows[index - 1].state == StepState.Done, next = index < rows.lastIndex, detail = detail, clock = clock)
            }
        }
        if (rows.failed != null && onRetry != null) {
            SettingsDivider()
            SettingsActionRow(uiText("Try again"), onRetry, testTag = "$tag-retry")
        }
    }
}

/** Beacon gold for the stepper, deep enough on a light screen to read as a shape beside its label. */
@Composable private fun stepGold(): Color = if (MaterialTheme.colorScheme.background.luminance() < .5f) Color(0xFFF0B24A) else Color(0xFFB8740F)

/**
 * One step: the rail (the line in from the [previous] step, filled once that step is [reached]; and out to the [next] one, filled once this
 * one is done), the glyph, the label with its note under it, and [detail] (the download percent, or the version) at the end. The step
 * that is running glows softly.
 */
@Composable private fun StepItem(row: StepRow, previous: Boolean, reached: Boolean, next: Boolean, detail: String, clock: FloatState) {
    val scheme = MaterialTheme.colorScheme
    val gold = stepGold()
    val error = rememberSettingsStatusPalette().error
    val track = scheme.outlineVariant
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // TalkBack: "Restarting Hermes gateway, Done"; the closing row waits as "Done, Not yet".
    val stateLabel = stepStateText(row).let { if (it.isBlank()) "" else uiText(it) }
    val note = if (row.note.isNotBlank()) uiText(row.note) else ""
    val active = row.state == StepState.Active
    val firstLine = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }.coerceAtLeast(24.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .semantics(mergeDescendants = true) { if (stateLabel.isNotBlank()) stateDescription = stateLabel }
            .drawBehind {
                val x = if (rtl) size.width - 30.dp.toPx() else 30.dp.toPx()
                val cy = 12.dp.toPx() + firstLine.toPx() / 2f
                val gap = 15.dp.toPx()
                val width = 2.dp.toPx()
                if (active) {
                    // The running step glows: a soft light behind its glyph and a faint wash along the row.
                    val breathe = .85f + .15f * sin(clock.floatValue * 2f)
                    drawRect(Brush.horizontalGradient(listOf(gold.copy(alpha = .1f * breathe), Color.Transparent), if (rtl) size.width else 0f, if (rtl) size.width * .35f else size.width * .65f))
                    drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = .3f * breathe), Color.Transparent), Offset(x, cy), 30.dp.toPx()), 30.dp.toPx(), Offset(x, cy))
                }
                if (previous && cy - gap > 0f) drawLine(if (reached) gold else track, Offset(x, 0f), Offset(x, cy - gap), width)
                if (next) drawLine(if (row.state == StepState.Done) gold else track, Offset(x, cy + gap), Offset(x, size.height), width)
            }
            .padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
            .testTag("update-step:${row.step.name}"),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.height(firstLine).width(24.dp), contentAlignment = Alignment.Center) { StepGlyph(row.state, gold, error, track) }
        Column(Modifier.weight(1f).padding(start = 16.dp)) {
            Text(uiText(row.step.label), style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (row.state == StepState.Waiting && note.isBlank()) scheme.onSurfaceVariant else scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium, color = if (row.state == StepState.Failed) error else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (detail.isNotBlank()) Box(Modifier.height(firstLine).padding(start = 12.dp), contentAlignment = Alignment.Center) {
            Text(detail, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = if (active) gold else scheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** ✓ on gold when done, a turning core of gold dust while it runs, a hollow ring while it waits, ! when it failed. */
@Composable private fun StepGlyph(state: StepState, gold: Color, error: Color, track: Color) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    when (state) {
        StepState.Done -> Box(Modifier.size(22.dp).drawBehind { drawCircle(gold) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = if (dark) Color(0xFF2E1C00) else Color.White)
        }
        StepState.Failed -> Box(Modifier.size(22.dp).drawBehind { drawCircle(error) }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PriorityHigh, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onError)
        }
        StepState.Waiting -> Box(Modifier.size(22.dp).drawBehind { drawCircle(track, size.minDimension / 2f - 1.dp.toPx(), style = Stroke(1.5.dp.toPx())) })
        StepState.Active -> BeaconDustCore(24.dp, color = gold)
    }
}

/** The Beacon plugin row: versions and a status pill, or the one action it needs ([PluginStatus.NeedsReview]). */
@Composable private fun PluginComponentRow(plugin: PluginRow, showMessage: Boolean, onReview: () -> Unit) {
    val (status, label) = pluginPill(plugin.status)
    val changing = plugin.status in setOf(PluginStatus.Checking, PluginStatus.Available, PluginStatus.Updating, PluginStatus.NeedsReview, PluginStatus.Busy, PluginStatus.Failed)
    val versions = if (changing && plugin.installed.isNotBlank() && plugin.target.isNotBlank()) "${plugin.installed} → ${plugin.target}" else plugin.installed.ifBlank { plugin.target }
    val message = plugin.text.takeIf { showMessage && plugin.status in PluginSentenceStates }
    // The tag sits on the row itself, so its versions, sentence and pill are read (and found) as one node.
    if (plugin.status == PluginStatus.NeedsReview) SettingsNavRow(uiText("Beacon plugin"), onClick = onReview, icon = Icons.Rounded.Hub, value = versions,
        status = status, statusLabel = label, testTag = "update-row-plugin")
    else SettingsValueRow(uiText("Beacon plugin"), icon = Icons.Rounded.Hub, value = message?.let { uiText(it) } ?: versions, status = status, statusLabel = label,
        valueMaxLines = if (message != null) 2 else 1, testTag = "update-row-plugin")
}

/** The pill a plugin state wears: short words, the sentence behind them is [PluginRow.text]. */
@Composable private fun pluginPill(status: PluginStatus): Pair<SettingsStatus, String> = when (status) {
    PluginStatus.Checking -> SettingsStatus.Working to uiText(PluginRow(status).text)
    PluginStatus.UpToDate, PluginStatus.Updated -> SettingsStatus.Ok to uiText(PluginRow(status).text)
    PluginStatus.Available -> SettingsStatus.Attention to uiText(PluginRow(status).text)
    PluginStatus.Updating -> SettingsStatus.Working to uiText(PluginRow(status).text)
    PluginStatus.NeedsReview -> SettingsStatus.Attention to uiText(PluginRow(status).text)
    PluginStatus.Restart, PluginStatus.DashboardRestart, PluginStatus.DashboardFailed -> SettingsStatus.Attention to uiText("Restart needed")
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
    /** A plugin's sentence, shown in full under the title: the hero's own line stops at two lines, which cuts it at large text. */
    val longLine: String = "",
    val progress: Float? = null, val working: Boolean = false, val badge: Boolean = false, val callPill: Boolean = false,
)

@Composable private fun UpdateHero(
    state: AppUpdateUi, phase: UpdatePhase, installed: String, callBusy: Boolean, now: Long,
    onCheck: () -> Unit, onUpdate: () -> Unit, onRetry: () -> Unit, onCancel: () -> Unit, onAllow: () -> Unit,
) {
    val release = state.release
    val spec = heroSpec(state, phase, installed, callBusy, now, onCheck, onUpdate, onRetry, onCancel, onAllow)
    SettingsStatusHero(spec.status, "Beacon ${release?.name ?: installed}", // verbatim
        line = spec.line, lineTestTag = spec.lineTag, painter = painterResource(R.drawable.ic_beacon_logo), testTag = "update-hero",
        meta = {
            if (spec.longLine.isNotBlank()) Text(spec.longLine, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag("update-plugin-line"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsStatusPill(spec.status, spec.pill, testTag = spec.pillTag)
            // A plugin-only action also reads as Available, but the app itself is current then.
            if (spec.badge && release != null) SettingsBadge(uiText("New"), testTag = "update-badge-new")
            if (spec.callPill) SettingsStatusPill(SettingsStatus.Off, uiText(UpdateFailure.Call.text), testTag = "update-call-busy")
            // With a checklist on screen the running step carries the progress; the hero keeps its sentence.
            if (spec.working && heroBarShown(state)) {
                if (spec.progress != null) LinearProgressIndicator(progress = { spec.progress }, modifier = Modifier.fillMaxWidth().testTag("update-progress"))
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
    val uri = LocalUriHandler.current
    val actions = heroActions(state, phase)
    val primary = actions.primary?.let { which ->
        val label = uiText(which.label)
        when (which) {
            HeroPrimary.Allow -> SettingsAction(label, onAllow, testTag = "update-allow")
            HeroPrimary.RetryFailed -> SettingsAction(label, onRetry, testTag = "update-retry")
            else -> SettingsAction(label, onUpdate, enabled = !callBusy, testTag = "update-primary")
        }
    }
    val secondary = actions.secondary?.let { which ->
        val label = uiText(which.label)
        when (which) {
            HeroSecondary.Check -> SettingsAction(label, onCheck, enabled = state.phase != UpdatePhase.Checking, testTag = "update-check")
            HeroSecondary.Cancel -> SettingsAction(label, onCancel, testTag = "update-cancel")
            HeroSecondary.How -> SettingsAction(label, { runCatching { uri.openUri(RESTART_DASHBOARD_HELP_URL) } }, testTag = "update-restart-how")
        }
    }
    return when {
        !state.supported -> HeroSpec(SettingsStatus.Off, uiText("Preview build"), line = uiText("Installed manually"))
        // Hermes installs and restarts without the phone: nothing here to cancel, and never "up to date" while it runs.
        plugin.status == PluginStatus.Updating -> HeroSpec(SettingsStatus.Working, uiText("Updating the Beacon plugin…"), working = true)
        phase == UpdatePhase.Idle -> {
            val checking = state.phase == UpdatePhase.Checking
            val ago = checkedAgo(state.checkedAt, now, uiText("just now"), uiText("{0} min ago"), uiText("{0} h ago"), uiText("{0} d ago"))
            HeroSpec(if (checking) SettingsStatus.Working else SettingsStatus.Ok, uiText(if (checking) "Checking for updates…" else "You're up to date"),
                pillTag = if (checking) "update-checking" else "update-status",
                line = if (checking || ago.isBlank()) "" else uiText("Checked {0}").replace("{0}", ago), lineTag = "update-checked",
                secondary = secondary)
        }
        phase == UpdatePhase.Available -> availableSpec(state, callBusy, primary, secondary)
        phase == UpdatePhase.Downloading -> {
            val total = release?.size ?: 0L
            HeroSpec(SettingsStatus.Working, uiText("Downloading"),
                line = if (total > 0) heroProgressLine(state, total, uiText("{0} of {1}")) else "", lineTag = "update-progress-text",
                secondary = secondary,
                progress = if (total > 0) progressPercent(state.downloaded, total) / 100f else null, working = true)
        }
        phase == UpdatePhase.Verifying -> HeroSpec(SettingsStatus.Working, uiText("Verifying"), working = true, secondary = secondary)
        phase == UpdatePhase.Installing -> HeroSpec(SettingsStatus.Working, uiText("Installing…"), working = true)
        phase == UpdatePhase.NeedsPermission -> HeroSpec(SettingsStatus.Attention, uiText("Allow installs"), primary = primary)
        else -> HeroSpec(SettingsStatus.Error, uiText("Failed"), line = uiText(state.failure?.text ?: UpdateFailure.Download.text), lineTag = "update-failure",
            primary = primary)
    }
}

/** An app release is waiting, or only the plugin has something for the owner. */
@Composable private fun availableSpec(state: AppUpdateUi, callBusy: Boolean, primary: SettingsAction?, secondary: SettingsAction?): HeroSpec {
    val release = state.release
    val plugin = state.plugin
    if (release != null) {
        val date = releaseDate(release.published)
        val meta = listOfNotNull(date.takeIf(String::isNotBlank)?.let { day -> uiText("Released {0}").replace("{0}", day) }, formatSize(release.size)).joinToString(" · ")
        return HeroSpec(SettingsStatus.Attention, uiText("Update available"), line = meta, lineTag = "update-meta", primary = primary, badge = true, callPill = callBusy)
    }
    val message = uiText(plugin.text)
    return when (plugin.status) {
        PluginStatus.Restart -> HeroSpec(SettingsStatus.Attention, uiText("Restart needed"), primary = primary, callPill = callBusy)
        PluginStatus.RestartFailed -> HeroSpec(SettingsStatus.Error, uiText("Failed"), longLine = message, primary = primary, callPill = callBusy)
        PluginStatus.Failed -> HeroSpec(SettingsStatus.Error, uiText("Failed"), longLine = message, primary = primary, callPill = callBusy)
        // The plugin itself updated; only the dashboard restart is left, and the button asks for just that.
        PluginStatus.DashboardFailed -> HeroSpec(SettingsStatus.Attention, uiText("Restart needed"), longLine = message, primary = primary, callPill = callBusy)
        PluginStatus.Busy -> HeroSpec(SettingsStatus.Off, uiText("Hermes is busy"), longLine = message, primary = primary, callPill = callBusy)
        PluginStatus.Waiting -> HeroSpec(SettingsStatus.Working, uiText("Waiting"), longLine = message)
        PluginStatus.DashboardRestart -> HeroSpec(SettingsStatus.Attention, uiText("Restart needed"), longLine = message, secondary = secondary)
        PluginStatus.Newer -> HeroSpec(SettingsStatus.Attention, uiText("Newer than app"), longLine = message)
        PluginStatus.NeedsReview -> HeroSpec(SettingsStatus.Attention, uiText("Update available"))
        else -> HeroSpec(SettingsStatus.Attention, uiText("Update available"), primary = primary, callPill = callBusy)
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
        title = { Text(uiText("Review what's changing")) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiText("This update changes what the Beacon plugin can do on Hermes."))
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
