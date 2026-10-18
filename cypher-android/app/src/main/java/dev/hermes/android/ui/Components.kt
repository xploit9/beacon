package dev.hermes.android.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class FieldType { TEXT, MULTILINE, SECRET, INTEGER, DECIMAL, BOOLEAN, CHOICE, LIST, MAP }
data class Field(val key: String, val label: String = key.replace('_', ' ').replaceFirstChar { it.titlecase() }, val type: FieldType = FieldType.TEXT, val required: Boolean = false, val options: List<String> = emptyList(), val help: String = "", val inverse: Boolean = false, val optionLabels: Map<String, String> = emptyMap(), val section: String = "")
data class ActionForm(val title: String, val path: String, val fields: List<Field>, val seed: JSONObject = JSONObject(), val method: String = "POST", val values: Map<String, String> = emptyMap(), val explanation: String = "", val actionLabel: String = "Save", val id: String = UUID.randomUUID().toString(), val onResult: (suspend (JSONObject) -> Unit)? = null, val error: String = "")

fun fieldValue(seed: JSONObject, key: String): Any? { var value: Any? = seed; key.split('.').forEach { value = (value as? JSONObject)?.opt(it) }; return value?.takeUnless { it == JSONObject.NULL } }
fun formValues(form: ActionForm): Map<String, String> = form.fields.associate { f -> f.key to when (val v = fieldValue(form.seed, f.key)) { is JSONArray -> v.strings().joinToString("\n"); is JSONObject -> if (f.type == FieldType.MAP) v.keys().asSequence().joinToString("\n") { "$it=${v.opt(it)}" } else v.toString(); else -> v?.toString().orEmpty() } }
fun formBody(form: ActionForm): JSONObject {
    val result = form.seed.copy()
    form.fields.forEach { f ->
        val raw = form.values[f.key].orEmpty()
        require(!f.required || raw.isNotBlank()) { "${f.label} is required" }
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

@Composable fun Header(title: String, subtitle: String = "", back: (() -> Unit)? = null, menu: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        menu?.takeIf { back == null }?.let { toggle -> IconButton(onClick = toggle, modifier = Modifier.testTag("navigation-toggle")) { Icon(Icons.Rounded.Menu, uiText(if (LocalPersistentNavigation.current) "Hide navigation" else "Show navigation")) } }
        if (back != null) IconButton(onClick = back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, uiText("Back")) }
        Column(Modifier.weight(1f)) { Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis); if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
        actions()
    }
}
@Composable fun EmptyState(title: String, description: String, icon: ImageVector = Icons.Rounded.ChatBubbleOutline, action: String = "", onAction: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action.isNotBlank()) Button(onClick = onAction) { Text(action) }
    }
}
@Composable fun ActionRow(label: String, description: String = "", icon: ImageVector = Icons.Rounded.ChevronRight, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, supportingContent = if (description.isBlank()) null else ({ Text(description) }), trailingContent = { Icon(icon, null) }, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 64.dp), colors = ListItemDefaults.colors(containerColor = Color.Transparent))
}
@Composable fun Choice(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier, optionLabels: Map<String, String> = emptyMap(), enabled: Boolean = true) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) expanded = false }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("$label: ${translatedChoiceLabel(selected, optionLabels, selected in options)}", modifier = Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(translatedChoiceLabel(option, optionLabels, true)) }, enabled = enabled, onClick = { expanded = false; onSelect(option) }, modifier = Modifier.testTag("choice-option:$label:$option")) } }
    }
}
@Composable private fun translatedChoiceLabel(value: String, labels: Map<String, String>, offered: Boolean): String {
    val text = choiceDisplayLabel(value, labels, offered)
    return if (value.isBlank() || labels[value]?.isNotBlank() == true) uiText(text) else text
}
@Composable fun SectionPicker(selected: String, options: List<String>, onSelect: (String) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (maxWidth < 420.dp || fontScale > 1.15f) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                FilledTonalButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("section-picker")) {
                    Text(uiText(selected), Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Start)
                    Icon(Icons.Rounded.ExpandMore, null)
                }
                DropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem(text = { Text(uiText(option)) }, onClick = { expanded = false; onSelect(option) }, trailingIcon = if (option == selected) ({ Icon(Icons.Rounded.Check, null) }) else null) } }
            }
        } else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option -> FilterChip(selected == option, { onSelect(option) }, { Text(uiText(option), maxLines = 1) }, modifier = Modifier.heightIn(min = 48.dp)) }
        }
    }
}
@Composable fun NativeForm(vm: CypherViewModel, form: ActionForm) {
    var confirmDiscard by remember(form.id) { mutableStateOf(false) }
    if (confirmDiscard) AlertDialog(onDismissRequest = { confirmDiscard = false }, title = { Text("Discard this draft?") }, text = { Text("Your saved task will stay unchanged.") }, confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discardScheduleDraft() }) { Text("Discard") } }, dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } })
    val parentDensity = LocalDensity.current
    val lightSystemBars = MaterialTheme.colorScheme.background.luminance() > .5f
    val submitting by vm.submittingForm.collectAsStateWithLifecycle()
    val busy = submitting == form.id
    val scheduleForm = form.path in setOf("/api/crons/create", "/api/crons/update")
    val fixedWindow = form.values["_schedule_mode"] == "Fixed window"
    val visibleFields = form.fields.filter { field -> !scheduleForm || (field.key != "schedule" || !fixedWindow) && (field.section != "Fixed window" || fixedWindow) }
    val longForm = form.fields.size > 4 || form.fields.any { it.section.isNotBlank() || it.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP) }
    val error: @Composable () -> Unit = { if (form.error.isNotBlank()) Text(form.error, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
    val fields: @Composable () -> Unit = {
        if (!longForm) error()
        if (form.explanation.isNotBlank()) Text(form.explanation, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val renderField: @Composable (Field) -> Unit = { field ->
            val value = form.values[field.key].orEmpty()
            when (field.type) {
                FieldType.BOOLEAN -> LabeledToggle(uiText(field.label), displayedBoolean(field, value), { vm.formValue(field.key, storedBoolean(field, it)) }, field.help, enabled = !busy)
                FieldType.CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Choice(field.label, value, field.options, { vm.formValue(field.key, it) }, optionLabels = field.optionLabels, enabled = !busy); if (field.help.isNotBlank()) Text(field.help, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                else -> OutlinedTextField(value, { vm.formValue(field.key, it) }, enabled = !busy, label = { Text(field.label + if (field.required) " *" else "") }, modifier = Modifier.fillMaxWidth(), minLines = if (field.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP)) 3 else 1, maxLines = if (field.type in setOf(FieldType.MULTILINE, FieldType.LIST, FieldType.MAP)) 12 else 1, visualTransformation = if (field.type == FieldType.SECRET) PasswordVisualTransformation() else VisualTransformation.None, keyboardOptions = KeyboardOptions(keyboardType = when (field.type) { FieldType.INTEGER -> KeyboardType.Number; FieldType.DECIMAL -> KeyboardType.Decimal; FieldType.SECRET -> KeyboardType.Password; else -> KeyboardType.Text }), supportingText = if (field.help.isBlank()) null else ({ Text(field.help, style = MaterialTheme.typography.bodyMedium) }))
            }
        }
        if (scheduleForm && fixedWindow) dev.hermes.android.tasks.fixedWindowPreview(form.values)?.let { preview -> Text("Runs at $preview · ${form.seed.string("_timezone")}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite }) }
        if (visibleFields.any { it.section.isNotBlank() }) visibleFields.groupBy { it.section }.forEach { (section, fields) ->
            if (section.isNotBlank()) Text(section, style = MaterialTheme.typography.titleMedium)
            Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { fields.forEach { renderField(it) } } }
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
                        Text(form.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (scheduleForm) IconButton(onClick = { confirmDiscard = true }, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Discard draft") }
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) { fields() }
                    Surface(shadowElevation = 4.dp) { Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        error()
                        Button(onClick = vm::submitForm, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (busy) "Saving…" else form.actionLabel) }
                    } }
                }
                }
            }
            }
        }
    } else AlertDialog(onDismissRequest = { if (!busy) vm.dismissForm() }, title = { Text(form.title) }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 540.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { fields() }
    }, confirmButton = { Button(onClick = vm::submitForm, enabled = !busy) { Text(if (busy) "Saving…" else form.actionLabel) } }, dismissButton = { TextButton(onClick = vm::dismissForm, enabled = !busy) { Text(uiText("Cancel")) } })
}
