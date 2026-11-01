@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The Settings kit (design-spec "Settings kit"): one Glass pane per section, rows with a tinted icon tile, real choice controls and
// glyph-plus-word statuses. Every visible string arrives localized from the caller; choice option labels stay English and are
// translated here, as Choice does.

/** Spacing and sizes every Settings page shares. */
internal object SettingsKit {
    val PagePadding: Dp get() = SettingsPagePadding
    val SectionGap: Dp = 24.dp
    val HeaderGap: Dp = 8.dp
    val RowMinHeight: Dp = 56.dp
    val RowMinHeightTwoLine: Dp = 64.dp
    val TileSize: Dp = 40.dp
    val TileCorner: Dp = 13.dp
    val DividerStart: Dp = 72.dp
    /** Smallest touch target of any interactive kit part. */
    val TouchMin: Dp = 48.dp
}

/** Where a setting stands: working, needs the user, failed, deliberately off or unknown, or still checking. */
internal enum class SettingsStatus { Ok, Attention, Error, Off, Working }

/** How strongly a pill or hero tile is tinted with its status ink. */
internal const val SettingsStatusTint = .14f

@Immutable internal data class SettingsStatusPalette(val ok: Color, val attention: Color, val error: Color, val off: Color, val working: Color) {
    fun ink(status: SettingsStatus): Color = when (status) {
        SettingsStatus.Ok -> ok
        SettingsStatus.Attention -> attention
        SettingsStatus.Error -> error
        SettingsStatus.Off -> off
        SettingsStatus.Working -> working
    }
}

/** Every backdrop a status ink is read on: the theme's surfaces and Glass, and the ink's own tinted pill over each of them. */
internal fun settingsStatusBackdrops(scheme: ColorScheme, ink: Color): List<Color> {
    val base = nativeRoleBackgrounds(scheme)
    return base + base.map { ink.copy(alpha = SettingsStatusTint).compositeOver(it) }
}

/** Ok is Beacon's own success sage on every skin and Attention a fixed amber; each ink is moved until it reads at 4.55:1 everywhere it is shown. */
internal fun settingsStatusPalette(scheme: ColorScheme): SettingsStatusPalette {
    val dark = scheme.background.luminance() < .5f
    fun readable(seed: Color) = readableInk(seed) { settingsStatusBackdrops(scheme, it) }
    return SettingsStatusPalette(
        ok = readable(if (dark) Color(0xFFA6C7AE) else Color(0xFF356944)),
        attention = readable(if (dark) Color(0xFFF0C674) else Color(0xFF8A5A00)),
        error = readable(scheme.error),
        off = readable(scheme.onSurfaceVariant),
        working = readable(scheme.tertiary),
    )
}

@Composable internal fun rememberSettingsStatusPalette(): SettingsStatusPalette {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme) { settingsStatusPalette(scheme) }
}

/** The still glyph of a state; Working has none because it shows the beacon's sweep instead. */
internal fun settingsStatusIcon(status: SettingsStatus): ImageVector? = when (status) {
    SettingsStatus.Ok -> Icons.Rounded.CheckCircle
    SettingsStatus.Attention -> Icons.Rounded.ErrorOutline
    SettingsStatus.Error -> Icons.Rounded.Error
    SettingsStatus.Off -> Icons.Rounded.DoNotDisturbOn
    SettingsStatus.Working -> null
}

/** The beacon's motion for a state in the hero: sweep while checking, a pulse when something needs the user, one ripple on OK. */
internal fun settingsStatusMotion(status: SettingsStatus): BeaconMotionMode = when (status) {
    SettingsStatus.Working -> BeaconMotionMode.Sweep
    SettingsStatus.Attention -> BeaconMotionMode.Pulse
    else -> BeaconMotionMode.Ripple
}

/** Which options a collapsed list shows: the first [limit], plus the selected one wherever it sits. */
internal fun settingsOptionsShown(options: List<SettingsOption>, selected: String, expanded: Boolean, limit: Int): List<SettingsOption> {
    val ids = visibleOptions(options.map { it.id }, selected, expanded, limit).toSet()
    return options.filter { it.id in ids }
}

/** Swatches per row: six on a wide pane, four while each cell keeps 72dp, else three. */
internal fun settingsSwatchColumns(width: Dp): Int = when {
    width >= 600.dp -> 6
    (width - 8.dp * 3) / 4 >= 72.dp -> 4
    else -> 3
}

private fun Modifier.kitTag(tag: String): Modifier = if (tag.isBlank()) this else testTag(tag)

@Composable internal fun SettingsIconTile(icon: ImageVector, modifier: Modifier = Modifier, destructive: Boolean = false, tint: Color? = null, size: Dp = SettingsKit.TileSize) {
    val scheme = MaterialTheme.colorScheme
    val ink = tint ?: if (destructive) scheme.error else scheme.primary
    val alpha = when { destructive -> .10f; scheme.background.luminance() > .5f -> .12f; else -> .09f }
    Box(modifier.size(size).clip(RoundedCornerShape(size * .325f)).background(ink.copy(alpha = alpha)), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size * .525f), tint = ink)
    }
}

/** An icon-only action with a 48dp target (a plain IconButton measures 40dp); [label] is what TalkBack reads. */
@Composable internal fun SettingsIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = Color.Unspecified) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled) {
        Icon(icon, label, tint = if (tint == Color.Unspecified) LocalContentColor.current else tint)
    }
}

@Composable internal fun SettingsDivider() {
    HorizontalDivider(Modifier.padding(start = SettingsKit.DividerStart, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
}

/** Body only, for routes whose Header MoreSettingsRoute already draws. The root Scaffold consumes the system insets, so only the 32dp tail is added. */
@Composable internal fun SettingsList(
    modifier: Modifier = Modifier,
    refreshing: Boolean = false,
    testTag: String = "",
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().imePadding().kitTag(testTag), state = state,
            contentPadding = PaddingValues(start = SettingsKit.PagePadding, end = SettingsKit.PagePadding, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsKit.SectionGap), content = content)
        // A background refresh over content already shown: a thin bar, never a blank page.
        if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter).testTag("settings-refreshing"))
    }
}

/** Header and body, for pages that own their header. */
@Composable internal fun SettingsPage(
    title: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    actions: @Composable RowScope.() -> Unit = {},
    refreshing: Boolean = false,
    testTag: String = "",
    content: LazyListScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Header(title, subtitle, back = onBack, menu = LocalOpenNavigationMenu.current, actions = actions)
        SettingsList(Modifier.weight(1f), refreshing, testTag, content = content)
    }
}

/** One titled Glass pane; callers put [SettingsDivider] between its rows. Icon actions in [trailing] use [SettingsIconButton]. */
@Composable internal fun SettingsSection(
    title: String = "",
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    footer: String = "",
    testTag: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth().testTag(testTag.ifBlank { "settings-section:$title" }), verticalArrangement = Arrangement.spacedBy(SettingsKit.HeaderGap)) {
        if (title.isNotBlank() || trailing != null) Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f).then(if (title.isBlank()) Modifier else Modifier.semantics { heading() }), style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            trailing?.invoke(this)
        }
        Glass(Modifier.fillMaxWidth()) { Column(Modifier.fillMaxWidth(), content = content) }
        if (footer.isNotBlank()) Text(footer, Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Immutable internal data class SettingsAction(
    val label: String, val onClick: () -> Unit, val enabled: Boolean = true,
    val loading: Boolean = false, val destructive: Boolean = false, val testTag: String = "",
)

/** The still glyph drawn when the beacon does not move (reduced motion): a state's own icon, or an hourglass for Working, which has none. */
internal fun settingsStaticGlyph(status: SettingsStatus): ImageVector = settingsStatusIcon(status) ?: Icons.Rounded.HourglassTop

/** Whether a state's glyph is the beacon's motion: when [signal] (or Working, which has no icon) and the phone allows motion; otherwise the still icon. */
internal fun settingsGlyphMoves(status: SettingsStatus, signal: Boolean, motion: Boolean): Boolean = motion && (settingsStatusIcon(status) == null || signal)

/** A state's glyph tinted with its ink: the beacon's motion when [signal], else the still icon (Working always moves). With reduced motion the still icon replaces the frozen beacon. */
@Composable private fun SettingsStatusGlyph(status: SettingsStatus, size: Dp, ink: Color, signal: Boolean) {
    if (settingsGlyphMoves(status, signal, rememberNativeMotionEnabled())) {
        // BeaconSignal paints with the theme's primary; a local theme lends it the status ink.
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = ink), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
            BeaconSignal(settingsStatusMotion(status), size = size, trigger = status)
        }
    } else Icon(settingsStaticGlyph(status), null, Modifier.size(size), tint = ink)
}

@Composable private fun SettingsActionButton(action: SettingsAction) {
    val colors = if (action.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()
    Button(onClick = action.onClick, enabled = action.enabled && !action.loading, colors = colors, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).kitTag(action.testTag)) {
        if (action.loading) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
        Text(action.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The answer to "is this working?" at the top of a page: a state tile, a verdict that is announced when it changes, one line of fact,
 * badges and at most one primary action. A null [status] is an identity hero (About, a profile) with the [icon] or [painter] only.
 */
@Composable internal fun SettingsStatusHero(
    status: SettingsStatus?,
    title: String,
    modifier: Modifier = Modifier,
    line: String = "",
    icon: ImageVector? = null,
    painter: Painter? = null,
    meta: (@Composable FlowRowScope.() -> Unit)? = null,
    primary: SettingsAction? = null,
    secondary: SettingsAction? = null,
    lineTestTag: String = "",
    testTag: String = "settings-hero",
) {
    val scheme = MaterialTheme.colorScheme
    val ink = status?.let { rememberSettingsStatusPalette().ink(it) } ?: scheme.primary
    val fade = if (rememberNativeMotionEnabled()) 200 else 0
    Glass(modifier.fillMaxWidth().testTag(testTag)) {
        Column(Modifier.padding(20.dp).animateContentSize(tween(fade)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(ink.copy(alpha = SettingsStatusTint)).testTag("$testTag-tile"), contentAlignment = Alignment.Center) {
                    Crossfade(status, animationSpec = tween(fade), label = "settings-hero-glyph") { shown ->
                        when {
                            painter != null -> Icon(painter, null, Modifier.size(32.dp), tint = Color.Unspecified)
                            icon != null -> Icon(icon, null, Modifier.size(24.dp), tint = ink)
                            shown != null -> SettingsStatusGlyph(shown, 28.dp, ink, signal = shown != SettingsStatus.Error && shown != SettingsStatus.Off)
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite }.testTag("$testTag-title"), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (line.isNotBlank()) Text(line, Modifier.semantics { liveRegion = LiveRegionMode.Polite }.kitTag(lineTestTag), style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (meta != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = meta)
            if (primary != null || secondary != null) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                primary?.let { SettingsActionButton(it) }
                secondary?.let { TextButton(onClick = it.onClick, enabled = it.enabled && !it.loading, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).kitTag(it.testTag)) { Text(it.label, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            }
        }
    }
}

/** The shared row: the interaction sits on [modifier], so the whole row is the touch target. */
@Composable private fun SettingsRowFrame(icon: ImageVector?, minHeight: Dp, modifier: Modifier, enabled: Boolean = true, destructive: Boolean = false, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().heightIn(min = minHeight).alpha(if (enabled) 1f else .38f).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        icon?.let { SettingsIconTile(it, destructive = destructive) }
        content()
    }
}

@Composable private fun RowScope.SettingsRowText(label: String, value: String, status: SettingsStatus?, statusLabel: String, labelColor: Color, valueMaxLines: Int = 1, valueTestTag: String = "") {
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = labelColor, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val pill = status != null && statusLabel.isNotBlank()
        if (value.isNotBlank() || pill) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (value.isNotBlank()) Text(value, Modifier.weight(1f, fill = false).kitTag(valueTestTag), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = valueMaxLines, overflow = TextOverflow.Ellipsis)
            if (pill) SettingsStatusPill(status, statusLabel)
        }
    }
}

/** A switch whose whole row toggles; TalkBack reads label, summary or status, then on/off. [testTag] sits on the toggleable row. */
@Composable internal fun SettingsSwitchRow(
    label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: String = "",
    status: SettingsStatus? = null, statusLabel: String = "",
    enabled: Boolean = true,
    busy: Boolean = false,
    testTag: String = "",
) {
    val active = enabled && !busy
    val twoLine = summary.isNotBlank() || (status != null && statusLabel.isNotBlank())
    SettingsRowFrame(icon, if (twoLine) SettingsKit.RowMinHeightTwoLine else SettingsKit.RowMinHeight,
        modifier.toggleable(checked, enabled = active, role = Role.Switch, onValueChange = onCheckedChange).kitTag(testTag), enabled = enabled) {
        SettingsRowText(label, if (status != null && statusLabel.isNotBlank()) "" else summary, status, statusLabel, MaterialTheme.colorScheme.onSurface)
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        Switch(checked, onCheckedChange = null, enabled = active)
    }
}

/** Opens a page ([external] = leaves Beacon, shown with OpenInNew). [selected] marks the open page in the two-pane layout. */
@Composable internal fun SettingsNavRow(
    label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String = "",
    status: SettingsStatus? = null, statusLabel: String = "",
    external: Boolean = false,
    enabled: Boolean = true,
    selected: Boolean? = null,
    minHeight: Dp = SettingsKit.RowMinHeightTwoLine,
    valueMaxLines: Int = 1,
    valueTestTag: String = "",
    testTag: String = "",
) {
    val interaction = if (selected == null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else Modifier.selectable(selected = selected, enabled = enabled, role = Role.Button, onClick = onClick)
    SettingsRowFrame(icon, minHeight, modifier.background(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent).then(interaction).kitTag(testTag), enabled = enabled) {
        SettingsRowText(label, value, status, statusLabel, MaterialTheme.colorScheme.onSurface, valueMaxLines, valueTestTag)
        Icon(if (external) Icons.AutoMirrored.Rounded.OpenInNew else Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
            Modifier.size(if (external) 18.dp else 20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A value shown in a card that does nothing when tapped (sources, the memory service). */
@Composable internal fun SettingsValueRow(
    label: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String = "",
    status: SettingsStatus? = null, statusLabel: String = "",
    valueMaxLines: Int = 1,
    testTag: String = "",
) {
    SettingsRowFrame(icon, if (value.isNotBlank() || status != null) SettingsKit.RowMinHeightTwoLine else SettingsKit.RowMinHeight, modifier.semantics(mergeDescendants = true) {}.kitTag(testTag)) {
        SettingsRowText(label, value, status, statusLabel, MaterialTheme.colorScheme.onSurface, valueMaxLines)
    }
}

/** Acts in place (Send a test, Check now): the label in the accent, no chevron, and a spinner with [busyLabel] while it runs. */
@Composable internal fun SettingsActionRow(
    label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String = "",
    enabled: Boolean = true,
    busy: Boolean = false, busyLabel: String = "",
    testTag: String = "",
) {
    val shown = if (busy && busyLabel.isNotBlank()) busyLabel else label
    SettingsRowFrame(icon, if (value.isNotBlank()) SettingsKit.RowMinHeightTwoLine else SettingsKit.RowMinHeight,
        modifier.clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick).then(if (busy) Modifier.semantics { stateDescription = shown } else Modifier).kitTag(testTag), enabled = enabled) {
        SettingsRowText(shown, value, null, "", MaterialTheme.colorScheme.primary)
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

/** Removes or resets something. It never acts by itself: [onClick] opens the caller's [SettingsConfirmDialog]. */
@Composable internal fun SettingsDestructiveRow(
    label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.DeleteOutline,
    value: String = "",
    enabled: Boolean = true,
    testTag: String = "",
) {
    SettingsRowFrame(icon, if (value.isNotBlank()) SettingsKit.RowMinHeightTwoLine else SettingsKit.RowMinHeight,
        modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).kitTag(testTag), enabled = enabled, destructive = true) {
        SettingsRowText(label, value, null, "", MaterialTheme.colorScheme.error)
    }
}

/** Inline text editing; the tonal Save appears only when the text differs from what is saved ([dirty]). */
@Composable internal fun SettingsTextFieldRow(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    placeholder: String = "",
    saveLabel: String = "", onSave: (() -> Unit)? = null, dirty: Boolean = false,
    enabled: Boolean = true, singleLine: Boolean = true,
    fieldTestTag: String = "", saveTestTag: String = "",
) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        icon?.let { SettingsIconTile(it) }
        OutlinedTextField(value, onValueChange, Modifier.weight(1f).kitTag(fieldTestTag), enabled = enabled, singleLine = singleLine, label = { Text(label) },
            placeholder = if (placeholder.isBlank()) null else ({ Text(placeholder) }),
            trailingIcon = if (dirty && onSave != null && saveLabel.isNotBlank()) ({
                FilledTonalButton(onClick = onSave, enabled = enabled, modifier = Modifier.padding(end = 4.dp).heightIn(min = SettingsKit.TouchMin).kitTag(saveTestTag), contentPadding = PaddingValues(horizontal = 12.dp)) { Text(saveLabel) }
            }) else null)
    }
}

/** Asks before a removal; the confirm button is in the error colour when [destructive]. [dismissLabel] defaults to Cancel. */
@Composable internal fun SettingsConfirmDialog(
    title: String, text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    destructive: Boolean = true, confirmTestTag: String = "", modifier: Modifier = Modifier, dismissLabel: String = "",
) {
    val cancel = uiText("Cancel")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = if (text.isBlank()) null else ({ Text(text) }),
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).kitTag(confirmTestTag),
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = SettingsKit.TouchMin)) { Text(dismissLabel.ifBlank { cancel }) } },
        modifier = modifier,
    )
}

/**
 * A choice in a section card. [label] is shown as given (pass it through uiText) and names the option tags `choice-option:<label>:<option>`
 * unless [optionTestTags] overrides one; [optionLabels] are English and translated here, like [Choice]. Auto shows segments for 2–4 short
 * options at a normal font size, else a menu row whose anchor carries [anchorTestTag].
 */
@Composable internal fun SettingsChoiceRow(
    label: String, selected: String, options: List<String>, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    optionLabels: Map<String, String> = emptyMap(),
    optionIcons: Map<String, ImageVector> = emptyMap(),
    optionTestTags: Map<String, String> = emptyMap(),
    enabled: Boolean = true,
    style: ChoiceStyle = ChoiceStyle.Auto,
    anchorTestTag: String = "",
    valueTestTag: String = "",
) {
    val labels = options.map { translatedChoiceLabel(it, optionLabels, true) }
    if (resolveChoiceStyle(style, labels, LocalDensity.current.fontScale) == ChoiceStyle.Segmented) {
        Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                icon?.let { SettingsIconTile(it) }
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            SegmentedChoice(label, selected, options, labels, onSelect, enabled = enabled, icons = optionIcons, testTags = optionTestTags)
        }
        return
    }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier.fillMaxWidth()) {
        SettingsRowFrame(icon, SettingsKit.RowMinHeightTwoLine, Modifier.clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }.kitTag(anchorTestTag), enabled = enabled) {
            SettingsRowText(label, translatedChoiceLabel(selected, optionLabels, selected in options), null, "", MaterialTheme.colorScheme.onSurface, valueTestTag = valueTestTag)
            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(labels[index]) }, enabled = enabled, onClick = { expanded = false; if (option != selected) onSelect(option) },
                    leadingIcon = if (option == selected) ({ Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) }) else null,
                    modifier = Modifier.testTag(optionTestTags[option] ?: "choice-option:$label:$option"))
            }
        }
    }
}

/**
 * A state as a small pill: glyph and word in the state's ink, never colour alone. The word is plain text, not its own
 * accessibility node, so a row that holds the pill is read with its state in one stop; the tag sits on the word.
 */
@Composable internal fun SettingsStatusPill(status: SettingsStatus, label: String, modifier: Modifier = Modifier, testTag: String = "") {
    val ink = rememberSettingsStatusPalette().ink(status)
    Row(modifier.clip(RoundedCornerShape(50)).background(ink.copy(alpha = SettingsStatusTint)).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SettingsStatusGlyph(status, 14.dp, ink, signal = false)
        Text(label, Modifier.kitTag(testTag), style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A neutral marker: "New", a count, an account or profile name. */
@Composable internal fun SettingsBadge(text: String, modifier: Modifier = Modifier, testTag: String = "") {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer, modifier = modifier.kitTag(testTag)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Immutable internal data class SettingsOption(val id: String, val label: String, val detail: String = "", val icon: ImageVector? = null, val badge: String = "", val testTag: String = "")

/**
 * Choices worth seeing side by side (voices, personality, models): selectable cards, [columns] per row. A collapsed list shows
 * [collapsedCount] cards plus the selected one; [moreLabel] arrives formatted ("More voices (10)").
 */
@Composable internal fun SettingsOptionCards(
    options: List<SettingsOption>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    columns: Int = 1,
    enabled: Boolean = true,
    collapsedCount: Int = Int.MAX_VALUE,
    moreLabel: String = "",
    moreTestTag: String = "",
    expanded: Boolean = false, onExpand: () -> Unit = {},
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    val shown = settingsOptionsShown(options, selected, expanded, collapsedCount)
    val perRow = columns.coerceAtLeast(1)
    val collapsed = uiText("Collapsed")
    Column(modifier.fillMaxWidth().padding(contentPadding).animateContentSize(tween(if (rememberNativeMotionEnabled()) 200 else 0)), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option -> SettingsOptionCard(option, option.id == selected, enabled, grid = perRow > 1, Modifier.weight(1f).fillMaxHeight()) { if (option.id != selected) onSelect(option.id) } }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (shown.size < options.size && moreLabel.isNotBlank()) TextButton(onClick = onExpand, modifier = Modifier.fillMaxWidth().heightIn(min = SettingsKit.TouchMin).kitTag(moreTestTag).semantics { stateDescription = collapsed }) {
            Text(moreLabel, Modifier.weight(1f))
            Icon(Icons.Rounded.ExpandMore, null)
        }
    }
}

@Composable private fun SettingsOptionCard(option: SettingsOption, selected: Boolean, enabled: Boolean, grid: Boolean, modifier: Modifier, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val speed = tween<Color>(if (rememberNativeMotionEnabled()) 150 else 0)
    val container by animateColorAsState(if (selected) scheme.primaryContainer.copy(alpha = .35f) else scheme.surfaceContainerHigh.copy(alpha = .6f), speed, label = "option-container")
    val edge by animateColorAsState(if (selected) scheme.primary else scheme.outlineVariant, speed, label = "option-edge")
    val shape = RoundedCornerShape(16.dp)
    Surface(modifier.heightIn(min = if (grid) 72.dp else SettingsKit.RowMinHeightTwoLine).clip(shape).selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect).kitTag(option.testTag),
        shape = shape, color = container, border = BorderStroke(if (selected) 2.dp else 1.dp, edge)) {
        val check: @Composable () -> Unit = { if (selected) Icon(Icons.Rounded.CheckCircle, null, Modifier.size(18.dp), tint = scheme.primary) }
        val text: @Composable ColumnScope.() -> Unit = {
            Text(option.label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (option.detail.isNotBlank()) Text(option.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (option.badge.isNotBlank()) SettingsBadge(option.badge, Modifier.padding(top = 4.dp))
        }
        if (grid) Box(Modifier.alpha(if (enabled) 1f else .38f)) {
            Column(Modifier.padding(start = 12.dp, top = 12.dp, end = 28.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                option.icon?.let { Icon(it, null, Modifier.size(24.dp), tint = scheme.primary) }
                text()
            }
            Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) { check() }
        } else Row(Modifier.alpha(if (enabled) 1f else .38f).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            option.icon?.let { SettingsIconTile(it) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = text)
            check()
        }
    }
}

/** A style shown as its own background and accent; null colours are still being read and draw a neutral tile, never a gap. */
@Immutable internal data class SettingsSwatch(val id: String, val label: String, val accent: Color?, val background: Color?)

@Composable internal fun SettingsSwatchPicker(
    swatches: List<SettingsSwatch>, selected: String, onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTagPrefix: String = "skin-option:",
) {
    BoxWithConstraints(modifier.fillMaxWidth().padding(12.dp)) {
        val perRow = settingsSwatchColumns(maxWidth)
        val cell = (maxWidth - 8.dp * (perRow - 1)) / perRow
        FlowRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = perRow) {
            swatches.forEach { swatch -> SettingsSwatchCell(swatch, swatch.id == selected, Modifier.width(cell), "$testTagPrefix${swatch.id}") { if (swatch.id != selected) onSelect(swatch.id) } }
        }
    }
}

@Composable private fun SettingsSwatchCell(swatch: SettingsSwatch, selected: Boolean, modifier: Modifier, tag: String, onSelect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ring by animateColorAsState(if (selected) scheme.primary else Color.Transparent, tween(if (rememberNativeMotionEnabled()) 150 else 0), label = "swatch-ring")
    Column(modifier.clip(RoundedCornerShape(16.dp)).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).testTag(tag).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(64.dp).border(2.dp, ring, RoundedCornerShape(20.dp)), contentAlignment = Alignment.Center) {
            val loaded = swatch.background != null && swatch.accent != null
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(swatch.background ?: scheme.surfaceContainerHigh)
                .border(1.dp, scheme.outlineVariant, RoundedCornerShape(16.dp)).testTag(if (loaded) "settings-swatch-colors" else "settings-swatch-placeholder"), contentAlignment = Alignment.Center) {
                swatch.accent?.let { Box(Modifier.size(22.dp).clip(CircleShape).background(it)) }
            }
            if (selected) Box(Modifier.align(Alignment.TopEnd).size(18.dp).clip(CircleShape).background(scheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, Modifier.size(12.dp), tint = scheme.onPrimary)
            }
        }
        Text(swatch.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/**
 * An API key field: when a key is saved and nothing is typed, the masked tail and [savedLabel] show above the field. The trailing
 * button pastes while the field is empty and shows or hides the text after that. A key is never logged or echoed.
 */
@Composable internal fun SettingsSecretField(
    label: String, value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    savedMask: String? = null,
    onPaste: () -> Unit,
    visible: Boolean, onToggleVisible: () -> Unit,
    enabled: Boolean = true, isError: Boolean = false,
    imeDone: () -> Unit = {},
    pasteLabel: String, showLabel: String, hideLabel: String,
    savedLabel: String = "",
    testTag: String = "",
) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (savedMask != null && value.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsIconTile(Icons.Rounded.Key)
            Text(savedMask, Modifier.weight(1f), style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (savedLabel.isNotBlank()) SettingsStatusPill(SettingsStatus.Ok, savedLabel)
        }
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth().kitTag(testTag), enabled = enabled, isError = isError, singleLine = true, label = { Text(label) },
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { imeDone() }),
            trailingIcon = {
                SettingsIconButton(if (value.isEmpty()) Icons.Rounded.ContentPaste else if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    if (value.isEmpty()) pasteLabel else if (visible) hideLabel else showLabel, { if (value.isEmpty()) onPaste() else onToggleVisible() }, enabled = enabled)
            })
    }
}

/** First load only, when nothing is cached for this account and profile: tile-and-bar rows inside the section card. */
@Composable internal fun SettingsSkeletonRows(count: Int = 3, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.onSurface
    Column(modifier.fillMaxWidth().testTag("settings-skeleton").clearAndSetSemantics {}) {
        repeat(count.coerceAtLeast(1)) { index ->
            if (index > 0) SettingsDivider()
            Row(Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeightTwoLine).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(SettingsKit.TileSize).clip(RoundedCornerShape(SettingsKit.TileCorner)).background(bar.copy(alpha = .08f)))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(Modifier.fillMaxWidth(if (index % 2 == 1) .42f else .58f).height(12.dp).clip(RoundedCornerShape(8.dp)).background(bar.copy(alpha = .08f)))
                    Box(Modifier.fillMaxWidth(.78f).height(9.dp).clip(RoundedCornerShape(8.dp)).background(bar.copy(alpha = .05f)))
                }
            }
        }
    }
}

/** An empty section in one line, with an optional action. */
@Composable internal fun SettingsEmpty(title: String, icon: ImageVector, modifier: Modifier = Modifier, action: SettingsAction? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeight).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(icon)
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        action?.let { TextButton(onClick = it.onClick, enabled = it.enabled, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).kitTag(it.testTag)) { Text(it.label) } }
    }
}

/** A failed load or refresh: the error glyph, one short message that is announced, and Retry when [onRetry] is given. */
@Composable internal fun SettingsErrorRow(message: String, onRetry: (() -> Unit)?, retryLabel: String, modifier: Modifier = Modifier, retryTestTag: String = "") {
    val ink = rememberSettingsStatusPalette().error
    Row(modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeight).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Rounded.Error, null, Modifier.size(20.dp), tint = ink)
        Text(message, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyMedium, color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (onRetry != null && retryLabel.isNotBlank()) TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).kitTag(retryTestTag)) { Text(retryLabel) }
    }
}

/** Two or three figures side by side, each pair being (label, value): the value large with even-width digits, the label under it. */
@Composable internal fun SettingsMetricRow(metrics: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(vertical = 16.dp)) {
        metrics.forEachIndexed { index, (label, value) ->
            if (index > 0) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(label, style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    }
}
