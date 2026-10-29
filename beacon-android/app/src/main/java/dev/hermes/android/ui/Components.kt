package dev.hermes.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.animation.core.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.R
import dev.hermes.android.core.*
import dev.hermes.android.updates.PluginInstallFailure
import dev.hermes.android.updates.PluginInstallHub
import dev.hermes.android.updates.PluginInstallStage
import dev.hermes.android.updates.PluginInstallUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FieldType { TEXT, MULTILINE, SECRET, INTEGER, DECIMAL, BOOLEAN, CHOICE, LIST, MAP }
data class Field(val key: String, val label: String = key.replace('_', ' ').replaceFirstChar { it.titlecase() }, val type: FieldType = FieldType.TEXT, val required: Boolean = false, val options: List<String> = emptyList(), val help: String = "", val inverse: Boolean = false, val optionLabels: Map<String, String> = emptyMap(), val section: String = "")
internal fun busyLabel(action: String) = when (action) { "Save" -> "Saving…"; "Run" -> "Running…"; else -> "Working…" }
data class ActionForm(val title: String, val path: String, val fields: List<Field>, val seed: JSONObject = JSONObject(), val method: String = "POST", val values: Map<String, String> = emptyMap(), val explanation: String = "", val actionLabel: String = "Save", val id: String = UUID.randomUUID().toString(), val onResult: (suspend (JSONObject) -> Unit)? = null, val error: String = "", val destructive: Boolean = false)

fun fieldValue(seed: JSONObject, key: String): Any? { var value: Any? = seed; key.split('.').forEach { value = (value as? JSONObject)?.opt(it) }; return value?.takeUnless { it == JSONObject.NULL } }
fun formValues(form: ActionForm): Map<String, String> = form.fields.associate { f -> f.key to when (val v = fieldValue(form.seed, f.key)) { is JSONArray -> v.strings().joinToString("\n"); is JSONObject -> if (f.type == FieldType.MAP) v.keys().asSequence().joinToString("\n") { "$it=${v.opt(it)}" } else v.toString(); else -> v?.toString().orEmpty() } }
fun formBody(form: ActionForm): JSONObject {
    val result = form.seed.copy()
    form.fields.forEach { f ->
        val raw = form.values[f.key].orEmpty()
        require(!f.required || raw.isNotBlank()) { "Enter ${f.label}." }
        if (form.path == "/api/profile/create" && f.key == "clone_from" && raw.isBlank()) { result.remove(f.key); return@forEach }
        if (f.type == FieldType.SECRET && (raw.isBlank() || raw.all { it == '•' || it == '*' })) return@forEach
        val value: Any = when (f.type) { FieldType.BOOLEAN -> raw.toBooleanStrictOrNull() ?: false; FieldType.CHOICE -> if (fieldValue(form.seed, f.key) is Number) raw.toLongOrNull() ?: error("${f.label} must be a whole number") else raw; FieldType.INTEGER -> if (raw.isBlank()) { if (f.key.startsWith("advanced.")) "" else JSONObject.NULL } else raw.toLongOrNull() ?: error("${f.label} must be a whole number"); FieldType.DECIMAL -> if (raw.isBlank()) JSONObject.NULL else raw.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("${f.label} must be a number"); FieldType.LIST -> JSONArray(raw.split('\n').map(String::trim).filter(String::isNotEmpty)); FieldType.MAP -> JSONObject().also { map -> raw.lineSequence().filter(String::isNotBlank).forEach { line -> require(line.contains('=')) { "${f.label}: use one key=value per line" }; val key = line.substringBefore('=').trim(); require(key.isNotBlank() && !map.has(key)) { "${f.label}: keys must be unique and nonempty" }; map.put(key, line.substringAfter('=')) } }; else -> raw }
        if (form.path == "/api/settings") validateSetting(f, value)
        val path = f.key.split('.')
        var target = result
        path.dropLast(1).forEach { name -> target = target.optJSONObject(name) ?: JSONObject().also { target.put(name, it) } }
        target.put(path.last(), value)
    }
    if (form.path == "/api/settings" && result.optBoolean("virtualize_transcript")) require(result.optBoolean("virtualize_transcript_optin")) { "Enable experimental rendering before efficient transcript rendering." }
    if (form.path.startsWith("/api/mcp/servers/") && form.method == "PUT") require(result.optString("url").isNotBlank() != result.optString("command").isNotBlank()) { "Use either an HTTP URL or a stdio command, not both." }
    return result
}

@Composable fun Glass(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > .5f
    val surface = nativeGlassFill(scheme)
    val edge = Brush.linearGradient(if (light)
        listOf(Color.White.copy(alpha = .95f), scheme.onSurface.copy(alpha = .04f), scheme.primary.copy(alpha = .22f))
        else listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .05f), Color.White.copy(alpha = .14f)))
    val highlightAlpha = nativeGlassHighlightAlpha(scheme)
    Surface(modifier, shape = RoundedCornerShape(26.dp), color = surface,
        border = BorderStroke(1.dp, edge), tonalElevation = GlassElevation.dp,
        shadowElevation = 0.dp) {
        Box(Modifier.drawWithCache {
            val highlight = Brush.linearGradient(
                listOf(Color.White.copy(alpha = highlightAlpha), Color.Transparent, Color.White.copy(alpha = .018f)),
                end = Offset(size.width * .7f, size.height),
            )
            onDrawBehind {
                drawRect(highlight)
                val inset = 2.dp.toPx()
                if (size.width > inset * 2 && size.height > inset * 2) drawRoundRect(
                    Color.White.copy(alpha = if (light) .55f else .035f), topLeft = Offset(inset, inset),
                    size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
                    cornerRadius = CornerRadius(24.dp.toPx()), style = Stroke(.5.dp.toPx()),
                )
            }
        }) { content() }
    }
}
@Composable fun LabeledToggle(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, description: String = "", checkbox: Boolean = false, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) {}.toggleable(checked, enabled = enabled, role = if (checkbox) Role.Checkbox else Role.Switch, onValueChange = onCheckedChange), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(label, style = MaterialTheme.typography.bodyLarge); if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (checkbox) Checkbox(checked, onCheckedChange = null, enabled = enabled) else Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}
val LocalOpenNavigationMenu = staticCompositionLocalOf<(() -> Unit)?> { null }

@Composable fun Header(title: String, subtitle: String = "", back: (() -> Unit)? = null, menu: (() -> Unit)? = null, liveSubtitle: Boolean = false, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        menu?.takeIf { back == null }?.let { toggle -> IconButton(onClick = toggle, modifier = Modifier.testTag("navigation-toggle")) { Icon(Icons.Rounded.Menu, uiText(if (LocalPersistentNavigation.current) "Hide navigation" else "Show navigation")) } }
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Back")) }
        Column(Modifier.weight(1f)) { Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); if (subtitle.isNotBlank()) Text(subtitle, if (liveSubtitle) Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite } else Modifier, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        actions()
    }
}
/** [centered] is for a state that sits under a centred picture (a null [icon] when the picture is already there). */
@Composable fun EmptyState(title: String, description: String, icon: ImageVector? = Icons.Rounded.ChatBubbleOutline, action: String = "", onAction: () -> Unit = {}, centered: Boolean = false) {
    val align = if (centered) androidx.compose.ui.text.style.TextAlign.Center else androidx.compose.ui.text.style.TextAlign.Start
    Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = if (centered) androidx.compose.ui.Alignment.CenterHorizontally else androidx.compose.ui.Alignment.Start) {
        if (icon != null) Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = align)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = align)
        if (action.isNotBlank()) Button(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
    }
}

private const val BEACON_PLUGIN_INSTALL_URL = "https://github.com/xploit9/beacon#install"

internal enum class BeaconMotionMode { Pulse, Sweep, Ripple }

@Composable internal fun BeaconMark(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp, contentDescription: String? = null) {
    Icon(painterResource(R.drawable.ic_beacon), contentDescription, modifier.size(size), tint = MaterialTheme.colorScheme.primary)
}

@Composable internal fun BeaconSignal(mode: BeaconMotionMode, modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 24.dp, trigger: Any? = Unit) {
    val motionEnabled = rememberNativeMotionEnabled()
    val phase = if (motionEnabled && mode != BeaconMotionMode.Ripple) {
        val transition = rememberInfiniteTransition(label = "beacon-${mode.name}")
        val spec: InfiniteRepeatableSpec<Float> = if (mode == BeaconMotionMode.Pulse)
            infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse)
        else infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart)
        val value by transition.animateFloat(0f, 1f, spec, label = "beacon-phase-${mode.name}")
        value
    } else 0f
    val ripple = remember { Animatable(0f) }
    LaunchedEffect(mode, trigger, motionEnabled) {
        if (mode == BeaconMotionMode.Ripple && motionEnabled) {
            ripple.snapTo(0f)
            ripple.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
        } else ripple.snapTo(1f)
    }
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size)) {
        val canvasSize = this.size
        val center = Offset(canvasSize.width / 2f, canvasSize.height / 2f)
        val radius = canvasSize.minDimension * .37f
        val stroke = (canvasSize.minDimension * .055f).coerceAtLeast(1.dp.toPx())
        when (mode) {
            BeaconMotionMode.Pulse -> {
                drawCircle(color.copy(alpha = .12f + .12f * phase), radius * (1.05f + .2f * phase), center)
                drawCircle(color.copy(alpha = .62f), radius * .72f, center, style = Stroke(stroke))
                drawCircle(color.copy(alpha = .95f), radius * (.16f + .05f * phase), center)
            }
            BeaconMotionMode.Sweep -> {
                drawCircle(color.copy(alpha = .18f), radius, center, style = Stroke(stroke))
                val angle = phase * (Math.PI * 2).toFloat() - Math.PI.toFloat() / 2f
                val end = Offset(center.x + kotlin.math.cos(angle) * radius, center.y + kotlin.math.sin(angle) * radius)
                drawLine(color.copy(alpha = .34f), center, end, strokeWidth = stroke * 2.5f, cap = StrokeCap.Round)
                drawCircle(color, stroke * 1.4f, end)
            }
            BeaconMotionMode.Ripple -> {
                val progress = if (motionEnabled) ripple.value else 1f
                drawCircle(color.copy(alpha = .12f), radius * .48f, center)
                if (progress < 1f) drawCircle(color.copy(alpha = .62f * (1f - progress)), radius * (.2f + .8f * progress), center, style = Stroke(stroke * (1.5f - progress)))
                drawCircle(color.copy(alpha = .9f), radius * .16f, center)
            }
        }
    }
}

private val NoInstall = MutableStateFlow(PluginInstallUi()).asStateFlow()

@Composable private fun installFailureText(failure: PluginInstallFailure?): String = when (failure) {
    PluginInstallFailure.SignIn -> uiText("Sign in with Hermes again, then try again.")
    PluginInstallFailure.Denied -> uiText("Hermes only lets its owner add Beacon.")
    PluginInstallFailure.Offline -> uiText("Can't reach Hermes. Check your connection, then try again.")
    PluginInstallFailure.Unsupported -> uiText("This Hermes can't add plugins from the app.")
    PluginInstallFailure.Busy -> uiText("Hermes is busy. Wait a moment and try again.")
    PluginInstallFailure.NotYet, PluginInstallFailure.GatewayDown -> uiText("Hermes isn't answering yet. Try again in a moment.")
    PluginInstallFailure.Error, null -> uiText("Hermes couldn't do that. Try again.")
}

/**
 * "Add Beacon to your Hermes": the owner confirms, Hermes installs Beacon from GitHub, and a restart is offered only when Hermes says its
 * gateway needs one. [update] is a Beacon too old for this app, which opens the update screen. Where no install can be reached (previews,
 * tests) the button opens the install steps instead. [illustration] is false where the screen already has its own picture, as in setup.
 */
@Composable internal fun MissingBeaconPluginState(modifier: Modifier = Modifier, compact: Boolean = false, update: Boolean = false, illustration: Boolean = true) {
    val uriHandler = LocalUriHandler.current
    val install = PluginInstallHub.controller
    val progress by (install?.state ?: NoInstall).collectAsStateWithLifecycle()
    var confirming by rememberSaveable { mutableStateOf(false) }
    // A finished install is shown once; the next time the card appears it starts fresh.
    DisposableEffect(install) { onDispose { install?.let { if (it.state.value.stage == PluginInstallStage.Done) it.reset() } } }
    val link = { uriHandler.openUri(BEACON_PLUGIN_INSTALL_URL) }
    val buttonHeight = Modifier.heightIn(min = 48.dp)
    val centered = androidx.compose.ui.text.style.TextAlign.Center
    Column(
        modifier.fillMaxWidth().padding(if (compact) 8.dp else 24.dp),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        if (illustration) Box(Modifier.testTag("missing-plugin-illustration").size(if (compact) 64.dp else 108.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            BeaconSignal(BeaconMotionMode.Sweep, size = if (compact) 64.dp else 108.dp)
            BeaconMark(size = if (compact) 36.dp else 60.dp)
        }
        Text(when {
            progress.stage == PluginInstallStage.Done -> uiText("Beacon is ready on your Hermes.")
            progress.stage == PluginInstallStage.NeedsRestart || progress.stage == PluginInstallStage.Restarting -> uiText("Restart Hermes to finish")
            update -> uiText("Update Beacon on your Hermes")
            else -> uiText("Add Beacon to your Hermes")
        }, Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium, textAlign = centered)
        when (progress.stage) {
            PluginInstallStage.Idle -> Button(
                onClick = { if (install == null) link() else if (update) install.openUpdates() else confirming = true },
                modifier = buttonHeight.testTag("missing-plugin-install"),
            ) { Text(uiText(if (update) "Update Beacon" else "Install Beacon")) }
            PluginInstallStage.Installing, PluginInstallStage.Checking, PluginInstallStage.Restarting -> Button(onClick = {}, enabled = false, modifier = buttonHeight.testTag("missing-plugin-working")) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(when (progress.stage) {
                    PluginInstallStage.Checking -> uiText("Checking…")
                    PluginInstallStage.Restarting -> uiText("Restarting…")
                    else -> uiText("Installing…")
                })
            }
            PluginInstallStage.NeedsRestart -> {
                Text(uiText(if (progress.busy) "Hermes is working right now. Restarting stops that work." else "Restarting stops any reply or scheduled task that is running."),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = centered)
                progress.failure?.let { Text(installFailureText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = centered) }
                Button(onClick = { install?.restart() }, modifier = buttonHeight.testTag("missing-plugin-restart")) { Text(uiText("Restart Hermes")) }
                TextButton(onClick = { install?.dismiss() }, modifier = buttonHeight.testTag("missing-plugin-not-now")) { Text(uiText("Not now")) }
            }
            PluginInstallStage.Failed -> {
                Text(installFailureText(progress.failure), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = centered,
                    modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                // A Hermes without the install route cannot be retried into one: the steps are its only way.
                if (progress.failure == PluginInstallFailure.Unsupported) Button(onClick = link, modifier = buttonHeight.testTag("missing-plugin-steps")) { Text(uiText("How to install")) }
                // Beacon is installed and answers; only the restarted gateway is late, so the owner can wait or move on.
                else if (progress.failure == PluginInstallFailure.GatewayDown) {
                    Button(onClick = { install?.retry() }, modifier = buttonHeight.testTag("missing-plugin-retry")) { Text(uiText("Try again")) }
                    TextButton(onClick = { install?.dismiss() }, modifier = buttonHeight.testTag("missing-plugin-not-now")) { Text(uiText("Not now")) }
                } else {
                    Button(onClick = { install?.retry() }, modifier = buttonHeight.testTag("missing-plugin-retry")) { Text(uiText("Try again")) }
                    TextButton(onClick = link, modifier = buttonHeight.testTag("missing-plugin-steps")) { Text(uiText("How to install")) }
                }
            }
            PluginInstallStage.Done -> Unit
        }
    }
    if (confirming && install != null) AlertDialog(
        onDismissRequest = { confirming = false },
        title = { Text(uiText("Add Beacon to your Hermes")) },
        text = { Text(uiText("Hermes will download Beacon from github.com/xploit9/beacon and turn it on.")) },
        confirmButton = { TextButton(onClick = { confirming = false; install.install() }, modifier = Modifier.testTag("missing-plugin-confirm")) { Text(uiText("Install Beacon")) } },
        dismissButton = { TextButton(onClick = { confirming = false }) { Text(uiText("Cancel")) } },
    )
}

/** A null [icon] drops the trailing mark, for a row that acts at once instead of opening something. */
@Composable fun ActionRow(label: String, description: String = "", icon: ImageVector? = Icons.AutoMirrored.Rounded.KeyboardArrowRight, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, supportingContent = if (description.isBlank()) null else ({ Text(description) }), trailingContent = if (icon == null) null else ({ Icon(icon, null) }), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}
/** A form select: a read-only field like its neighbours, whose anchor carries the dropdown role and expanded state. A long value wraps to a second line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun Choice(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, optionLabels: Map<String, String> = emptyMap(), enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    ExposedDropdownMenuBox(expanded, { expanded = enabled && it }, modifier) {
        OutlinedTextField(translatedChoiceLabel(selected, optionLabels, selected in options), {}, readOnly = true, enabled = enabled, maxLines = 2, label = { Text(uiText(label)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled))
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(translatedChoiceLabel(option, optionLabels, true)) }, enabled = enabled, onClick = { expanded = false; onSelect(option) }, modifier = Modifier.testTag("choice-option:$label:$option")) } }
    }
}
@Composable private fun translatedChoiceLabel(value: String, labels: Map<String, String>, offered: Boolean): String {
    val text = choiceDisplayLabel(value, labels, offered)
    return if (value.isBlank() || labels[value]?.isNotBlank() == true) uiText(text) else text
}
@Composable fun NativeForm(vm: BeaconViewModel, form: ActionForm) {
    var confirmDiscard by remember(form.id) { mutableStateOf(false) }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text(uiText("Discard this draft?")) }, text = { Text(uiText("Your saved task will stay unchanged.")) }, confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discardScheduleDraft() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text(uiText("Discard")) } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(uiText("Keep editing")) } })
    // A destructive form confirms in the error colours, like the history Delete.
    val confirmColors = if (form.destructive) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors()
    val parentDensity = LocalDensity.current
    val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > .5f
    val submitting by vm.submittingForm.collectAsStateWithLifecycle()
    val busy = submitting == form.id
    val scheduleForm = form.path in setOf("/api/crons/create", "/api/crons/update")
    val scheduleMode = form.values["_schedule_mode"].orEmpty()
    val visibleFields = form.fields.filter { field -> !scheduleForm || dev.hermes.android.tasks.scheduleFieldVisible(field.key, scheduleMode) }
    val longForm = form.fields.size > 4 || form.fields.any { it.section.isNotBlank() || it.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP) }
    val error: @Composable () -> Unit = { if (form.error.isNotBlank()) Text(uiText(form.error), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
    val fields: @Composable () -> Unit = {
        if (!longForm) error()
        if (form.explanation.isNotBlank()) Text(uiText(form.explanation), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val renderField: @Composable (Field) -> Unit = { field ->
            val value = form.values[field.key].orEmpty()
            when (field.type) {
                FieldType.BOOLEAN -> LabeledToggle(uiText(field.label), displayedBoolean(field, value), { vm.formValue(field.key, storedBoolean(field, it)) }, uiText(field.help), enabled = !busy)
                FieldType.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Choice(field.label, value, field.options, { vm.formValue(field.key, it) }, optionLabels = field.optionLabels, enabled = !busy); if (field.help.isNotBlank()) Text(uiText(field.help), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> OutlinedTextField(value, { vm.formValue(field.key, it) }, enabled = !busy, label = { Text(uiText(field.label) + if (field.required) " *" else "") }, modifier = Modifier.fillMaxWidth(), minLines = if (field.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP)) 3 else 1, maxLines = if (field.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP)) 12 else 1, visualTransformation = if (field.type == FieldType.SECRET) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = when (field.type) { FieldType.INTEGER -> KeyboardType.Number; FieldType.DECIMAL -> KeyboardType.Decimal; FieldType.SECRET -> KeyboardType.Password; else -> KeyboardType.Text }), supportingText = if (field.help.isBlank()) null else ({ Text(uiText(field.help), style = MaterialTheme.typography.bodyMedium) }))
            }
        }
        if (visibleFields.any { it.section.isNotBlank() }) visibleFields.groupBy { it.section }.forEach { (section, fields) ->
            if (section.isNotBlank()) Text(uiText(section), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { fields.forEach { renderField(it) }; if (scheduleForm && section == "When") ScheduleSummary(form.values, form.seed.string("_timezone")) } }
        } else visibleFields.forEach { renderField(it) }

    }
    if (longForm) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { if (!busy) vm.dismissForm() }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            val view = LocalView.current
            val window = (view.parent as? DialogWindowProvider)?.window
            DisposableEffect(window, view, lightSystemBars) {
                val controller = window?.let { WindowCompat.getInsetsController(it, view) }
                val previousStatus = controller?.isAppearanceLightStatusBars
                val previousNavigation = controller?.isAppearanceLightNavigationBars
                controller?.isAppearanceLightStatusBars = lightSystemBars
                controller?.isAppearanceLightNavigationBars = lightSystemBars
                onDispose {
                    previousStatus?.let { controller?.isAppearanceLightStatusBars = it }
                    previousNavigation?.let { controller?.isAppearanceLightNavigationBars = it }
                }
            }
            CompositionLocalProvider(LocalDensity provides parentDensity) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding().widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = vm::dismissForm, enabled = !busy) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText(if (scheduleForm) "Close editor" else "Cancel")) }
                        Text(uiText(form.title), Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge)
                        if (scheduleForm) IconButton(onClick = { confirmDiscard = true }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, uiText("Discard draft")) }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { fields() }
                    Surface(shadowElevation = 4.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error()
                        Button(onClick = vm::submitForm, enabled = !busy, colors = confirmColors, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(uiText(if (busy) busyLabel(form.actionLabel) else form.actionLabel)) }
                    } }
                }
                }
            }
            }
        }
    } else AlertDialog(onDismissRequest = { if (!busy) vm.dismissForm() }, title = { Text(uiText(form.title)) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { fields() }
    }, confirmButton = { Button(onClick = vm::submitForm, enabled = !busy, colors = confirmColors) { Text(uiText(if (busy) busyLabel(form.actionLabel) else form.actionLabel)) } }, dismissButton = { TextButton(onClick = vm::dismissForm, enabled = !busy) { Text(uiText("Cancel")) } })
}

// Telegram-style chat parts: avatar, date chip, send tick, bubble corners and the chat-list row.

/** A chat's avatar: Beacon's mark, the title's emoji, or its initial on a colour fixed by the chat id. */
@Composable internal fun ChatAvatar(title: String, id: String, modifier: Modifier = Modifier, size: Dp = 48.dp, beacon: Boolean = false, description: String? = null) {
    val spec = remember(title, id) { chatAvatarSpec(title, id) }
    val tint = Color(spec.color)
    // Sized in px terms, so a larger system font does not overflow the circle.
    val glyph = with(LocalDensity.current) { (size * .42f).toSp() }
    val emoji = spec.emoji
    Box(
        modifier.size(size).clip(CircleShape).background(if (beacon) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else if (emoji != null) tint.copy(alpha = .22f) else tint).testTag("chat-avatar").clearAndSetSemantics { if (description != null) contentDescription = description },
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        when {
            beacon -> BeaconMark(size = size * .56f)
            emoji != null -> Text(emoji, fontSize = glyph * 1.1f)
            else -> Text(spec.initial, color = Color.White, fontSize = glyph, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** The day between messages of different days, as a small centred chip. */
@Composable internal fun DateChip(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).testTag("date-chip"), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Surface(shape = RoundedCornerShape(999.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .72f)) {
            Text(label, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Where an own message stands: still going out, accepted by Hermes, or with an unknown outcome. */
internal enum class SendTick { Sending, Sent, Unconfirmed }

@Composable internal fun SendTickIcon(state: SendTick, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current.copy(alpha = .72f)
    val (icon, label, tint) = when (state) {
        SendTick.Sending -> Triple(Icons.Rounded.Schedule, "Sending", ink)
        SendTick.Sent -> Triple(Icons.Rounded.Check, "Sent", ink)
        SendTick.Unconfirmed -> Triple(Icons.Rounded.ErrorOutline, "Not confirmed", MaterialTheme.colorScheme.error)
    }
    Icon(icon, uiText(label), modifier.size(14.dp).testTag("send-tick:" + state.name.lowercase()), tint = tint)
}

/** Large corners all round; the sender's side is tighter where bubbles of one group meet, and the last bubble carries the tail. */
internal fun bubbleShape(own: Boolean, joinedTop: Boolean, tail: Boolean): RoundedCornerShape {
    val round = 18.dp
    val top = if (joinedTop) 6.dp else round
    val bottom = if (tail) 4.dp else 6.dp
    return if (own) RoundedCornerShape(topStart = round, topEnd = top, bottomEnd = bottom, bottomStart = round)
    else RoundedCornerShape(topStart = top, topEnd = round, bottomEnd = round, bottomStart = bottom)
}

/** One Telegram-style row of the chat list. The caller adds the click, tag and long-press through [modifier]. */
@Composable internal fun ChatListRow(
    id: String,
    title: String,
    preview: String,
    time: String,
    modifier: Modifier = Modifier,
    unread: Boolean = false,
    pinned: Boolean = false,
    thread: Boolean = false,
    streaming: Boolean = false,
    selected: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val unreadLabel = uiText("Unread reply")
    Row(
        modifier.fillMaxWidth().background(if (selected) scheme.secondaryContainer else Color.Transparent).heightIn(min = 72.dp).padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (unread) Modifier.semantics { stateDescription = unreadLabel } else Modifier),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        ChatAvatar(title, id, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(plainTitle(title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium.copy(fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium), maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (pinned) Icon(Icons.Rounded.PushPin, uiText("Pinned"), Modifier.padding(start = 6.dp).size(14.dp), tint = scheme.onSurfaceVariant)
                else if (thread) ThreadSpoolGlyph(Modifier.padding(start = 6.dp).size(14.dp), tint = scheme.onSurfaceVariant)
                if (time.isNotBlank()) Text(time, Modifier.padding(start = 6.dp), style = nativeMetadataStyle(), color = if (unread) scheme.primary else scheme.onSurfaceVariant, maxLines = 1)
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(preview, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (streaming) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 2.dp)
                else if (unread) Box(Modifier.padding(start = 8.dp).size(10.dp).clip(CircleShape).background(scheme.primary).testTag("unread-dot"))
            }
        }
    }
}
