@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/** The picker group whose slug, or a name Hermes lists for it (custom providers carry `aliases`), is [name]; blank when none. */
internal fun pickerGroupNamed(groups: List<JSONObject>, name: String): String =
    groups.firstOrNull { g -> name.isNotBlank() && (g.string("provider_id").equals(name.trim(), true) || g.optJSONArray("aliases")?.strings().orEmpty().any { it.equals(name.trim(), true) }) }?.string("provider_id").orEmpty()

/** The picker group of the chat's provider. Hermes may report that provider under another name than the group's slug (a Copilot chat says "github-copilot", its group is "copilot"):
 *  the group the name matches, else the row Hermes itself marked for this running chat ([hermesRow]), else the only group listing the chat's model. With no provider reported, the group holding the chat's model. */
internal fun currentPickerProvider(groups: List<JSONObject>, provider: String, model: String, hermesRow: String = ""): String {
    if (provider.isBlank()) return groups.firstOrNull { g -> model.isNotBlank() && (g.rows("models") + g.rows("extra_models")).any { it.string("id") == model } }?.string("provider_id").orEmpty()
    return pickerGroupNamed(groups, provider).ifBlank { groups.firstOrNull { hermesRow.isNotBlank() && it.string("provider_id") == hermesRow }?.string("provider_id").orEmpty() }
        // A chat that is not running cannot ask Hermes; when exactly one group lists its model, that group is the only possible one.
        .ifBlank { groups.filter { g -> model.isNotBlank() && (g.rows("models") + g.rows("extra_models")).any { it.string("id") == model } }.singleOrNull()?.string("provider_id").orEmpty() }
}

/** The one place the composer chip (and any other control) opens the model sheet: current model, reasoning level and every provider's models. */
internal fun openModelPicker(vm: BeaconViewModel) = vm.openCatalog("Model & provider")

/** A provider's section label without its qualifier: "OpenAI Codex (ChatGPT subscription)" reads "OpenAI Codex". */
internal fun shortProviderLabel(label: String): String = label.substringBefore(" (").substringBefore(" — ").substringBefore(" – ").trim().ifBlank { label.trim() }

/** The chat's model leads its own provider's list; every other order stays as Hermes sent it. */
internal fun pinSelectedModel(models: List<JSONObject>, selectedId: String, inCurrentProvider: Boolean): List<JSONObject> =
    if (!inCurrentProvider || selectedId.isBlank()) models else models.sortedBy { it.string("id") != selectedId }

@Composable fun NativeCatalog(vm: BeaconViewModel, containedActions: Boolean = false) {
    val kind by vm.catalog.collectAsStateWithLifecycle()
    if (kind.isBlank()) return
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val reasoningSaving by vm.reasoningSaving.collectAsStateWithLifecycle()
    val modelSaving by vm.modelSaving.collectAsStateWithLifecycle()
    val pending by vm.conversationPending.collectAsStateWithLifecycle()
    val scopeKey = vm.client?.scope?.session(chat.sessionId)?.key
    val modelPending = modelSaving.isNotBlank() || "$scopeKey|/api/session/update" in pending
    val reasoningPending = reasoningSaving || "$scopeKey|/api/reasoning" in pending
    var toolsMcp by rememberSaveable(kind, chat.sessionId) { mutableStateOf(false) }
    val assistant = LocalAssistantName.current
    // A few reasoning levels take their own height; the long model and tool lists keep the tall sheet.
    val wrapsContent = kind == "Reasoning"
    Dialog(vm::closeCatalog, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.testTag("catalog-sheet").widthIn(max = if (kind == "Toolsets") 560.dp else 900.dp).fillMaxWidth().then(if (wrapsContent) Modifier else Modifier.fillMaxHeight(if (kind == "Toolsets") .8f else .92f)).padding(12.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = 1f)) {
            Column(if (wrapsContent) Modifier.fillMaxWidth() else Modifier.fillMaxSize()) {
                // Toolsets belong to the profile, not to this chat, so the chat's title is not shown under them. The Beacon conversation is named for the assistant, not for its Hermes session title.
                Header(uiText(if (kind == "Toolsets") "Tools" else kind), if (kind == "Toolsets") "" else if (vm.isBeaconConversation()) assistant else chat.title, actions = {
                    if (kind == "Reasoning" || kind == "Model & provider") IconButton(
                        onClick = { vm.openCatalog(kind) },
                        enabled = if (kind == "Reasoning") !reasoningPending && "picker:reasoning" !in refreshing else !modelPending && "picker:models" !in refreshing,
                    ) {
                        Icon(Icons.Rounded.Refresh, uiText(if (kind == "Reasoning") "Reload reasoning choices" else "Refresh models"))
                    }
                    IconButton(onClick = vm::closeCatalog) { Icon(Icons.Rounded.Close, uiText("Close picker")) }
                })
                when (kind) {
                    "Model & provider" -> ModelPickerBody(vm, containedActions)
                    // The same pages as Settings > Tools & MCP; the Tools page already says its changes apply to new chats.
                    "Toolsets" -> Column(Modifier.fillMaxSize().testTag("chat-toolsets")) {
                        SegmentedChoice("Tools", if (toolsMcp) "mcp" else "tools", listOf("tools", "mcp"), listOf(uiText("Tools"), uiText("MCP servers")), { toolsMcp = it == "mcp" },
                            Modifier.padding(horizontal = 20.dp), testTags = mapOf("tools" to "chat-toolsets-tools", "mcp" to "chat-toolsets-mcp"))
                        if (toolsMcp) Text(uiText("Changes apply to this profile's new chats."), Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        // One scroller inside the sheet: the page's own list fills this bounded box.
                        Box(Modifier.weight(1f).fillMaxWidth()) { NativeToolSettings(vm, mcp = toolsMcp, containedActions = true) }
                    }
                    "Reasoning" -> {
                        val value = vm.savedReasoning(data["picker:reasoning"])
                        val options = value?.optJSONArray("options")?.strings().orEmpty()
                        var level by remember(value?.toString()) { mutableStateOf(value?.string("value").orEmpty().takeIf { it in options }.orEmpty()) }
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            // The options read before stay on screen while Hermes is asked again; only a first read shows the skeleton.
                            if (value == null) ReasoningSkeleton()
                            else if (recordError(value).isNotBlank()) SettingsSection { SettingsErrorRow(uiText("Couldn't load reasoning"), { vm.openCatalog("Reasoning") }, uiText("Retry")) }
                            else if (options.isNotEmpty()) {
                                if (reasoningPending) SettingsStatusPill(SettingsStatus.Working, uiText("Applying…"))
                                // A tap applies the level: there is no second step.
                                SettingsSection { SettingsOptionCards(
                                    options.map { SettingsOption(it, uiText(reasoningLevelLabel(it)), uiText(reasoningLevelDescription(it)), testTag = "reasoning-level:$it") },
                                    selected = level, onSelect = { option -> level = option; vm.setReasoning(option) }, enabled = !reasoningPending,
                                ) }
                            } else if (value.optBoolean("known")) SettingsSection { SettingsEmpty(uiText("This model manages reasoning automatically."), Icons.Rounded.Psychology) }
                            else SettingsSection { SettingsEmpty(uiText("No reasoning choices are available."), Icons.Rounded.Psychology) }
                        }
                    }
                }
            }
        }
    }
}

/** Model sheet: the chat's model and reasoning level on top, then every provider's models with the chosen one first. */
@Suppress("UNUSED_PARAMETER")
@Composable private fun ColumnScope.ModelPickerBody(vm: BeaconViewModel, containedActions: Boolean) {
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val reasoningSaving by vm.reasoningSaving.collectAsStateWithLifecycle()
    val modelSaving by vm.modelSaving.collectAsStateWithLifecycle()
    val pending by vm.conversationPending.collectAsStateWithLifecycle()
    val scopeKey = vm.client?.scope?.session(chat.sessionId)?.key
    val modelPending = modelSaving.isNotBlank() || "$scopeKey|/api/session/update" in pending
    val reasoningPending = reasoningSaving || "$scopeKey|/api/reasoning" in pending
    var query by rememberSaveable(chat.sessionId) { mutableStateOf("") }
    val catalog = data["picker:models"]
    val loading = catalog == null // a cached list is shown while the refresh runs
    val catalogGroups = catalog?.rows("groups").orEmpty()
    val chatModel = chat.raw.string("model")
    val chatProvider = chat.raw.string("model_provider")
    // Hermes may report a running chat's provider under another name than its picker row; Hermes then says which row it means.
    val askHermes = chatProvider.isNotBlank() && chat.raw.string("runtime_session_id").isNotBlank() && catalogGroups.isNotEmpty() && pickerGroupNamed(catalogGroups, chatProvider).isBlank()
    LaunchedEffect(askHermes, chat.sessionId, chatProvider) {
        if (askHermes) vm.load("picker:model-current", "/api/model/current", mapOf("session_id" to chat.sessionId), quiet = true)
    }
    // The reasoning choices belong to the chat's model; they load beside the models so the level can be changed from the same sheet.
    LaunchedEffect(chat.sessionId, chatModel, chatProvider) {
        if (chat.sessionId.isNotBlank()) vm.load("picker:reasoning", if (vm.isBeaconConversation()) "/api/mobile/beacon/reasoning" else "/api/reasoning", mapOf("session_id" to chat.sessionId, "model" to chatModel, "provider" to chatProvider), quiet = true)
    }
    val hermesRow = data["picker:model-current"]?.takeIf { it.string("scope_key") == vm.client?.scope?.key && it.string("session_id") == chat.sessionId && it.string("provider") == chatProvider }?.string("provider_id").orEmpty()
    val currentProvider = currentPickerProvider(catalogGroups, chatProvider, chatModel, hermesRow)
    val groups = remember(catalog, query, currentProvider, chatModel) { catalog?.rows("groups").orEmpty().map { group ->
        val provider = group.string("provider", group.string("label", group.string("provider_id")))
        group to pinSelectedModel((group.rows("models") + group.rows("extra_models")).distinctBy { it.string("id") }.filter { modelSearchMatches(provider, it.string("id"), it.string("label"), query) }, chatModel, group.string("provider_id") == currentProvider)
    }.filter { it.second.isNotEmpty() }.sortedBy { it.first.string("provider_id") != currentProvider } }
    val reasoning = vm.savedReasoning(data["picker:reasoning"])
    val options = reasoning?.optJSONArray("options")?.strings().orEmpty()
    val effort = chat.sessionReasoningEffort.ifBlank { chat.raw.string("reasoning_effort", chat.raw.string("reasoning")) }.ifBlank { reasoning?.string("value").orEmpty() }
    val currentGroup = catalogGroups.firstOrNull { it.string("provider_id") == currentProvider }
    val currentTitle = modelTitle(currentGroup?.let { g -> (g.rows("models") + g.rows("extra_models")).firstOrNull { it.string("id") == chatModel }?.string("label") }.orEmpty(), chatModel)
    val currentProviderLabel = currentGroup?.let { shortProviderLabel(it.string("provider", it.string("label", it.string("provider_id")))) } ?: chatProvider
    SettingsSearchField(query, { query = it }, uiText("Search model or provider"), Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp), testTag = "models-search")
    SettingsList(Modifier.weight(1f), refreshing = (modelPending && modelSaving.isBlank()) || (!loading && "picker:models" in refreshing)) {
        if (query.isBlank()) item(key = "current") {
            CurrentModelCard(
                model = currentTitle, modelId = chatModel, provider = currentProviderLabel, applying = modelPending && modelSaving.isBlank(),
                options = options, effort = effort, reasoningLoading = (reasoning == null && chat.sessionId.isNotBlank()) || ("picker:reasoning" in refreshing && options.isEmpty()),
                reasoningFailed = reasoning != null && recordError(reasoning).isNotBlank(), automatic = reasoning != null && options.isEmpty() && reasoning.optBoolean("known"),
                reasoningPending = reasoningPending, onEffort = vm::setReasoning, onRetry = { vm.openCatalog("Model & provider") },
            )
        }
        if (loading) item(key = "loading") { SettingsSection { Box(Modifier.testTag("models-loading")) { SettingsSkeletonRows(5) } } }
        else if (recordError(catalog).isNotBlank()) item(key = "error") { SettingsSection { SettingsErrorRow(uiText("Couldn't load models"), { vm.openCatalog("Model & provider") }, uiText("Retry")) } }
        groups.forEach { (group, models) ->
            val provider = group.string("provider_id")
            val label = shortProviderLabel(group.string("provider", group.string("label", provider)))
            item(key = "provider:$provider") {
                SettingsSection(title = label) {
                    models.forEachIndexed { index, model ->
                        val id = model.string("id")
                        val title = modelTitle(model.string("label"), id)
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                        ModelRow(title, modelSubtitle(title, id), selected = chatModel == id && currentProvider == provider, applying = modelSaving == "$provider:$id",
                            enabled = !loading && !modelPending, tag = "model:$provider:$id") { vm.pickModel(id, provider) }
                    }
                }
            }
        }
        if (catalog != null && recordError(catalog).isBlank() && groups.isEmpty()) item(key = "empty") {
            SettingsSection { SettingsEmpty(uiText("No matching models"), Icons.Rounded.SearchOff, action = if (query.isBlank()) null else SettingsAction(uiText("Clear search"), { query = "" })) }
        }
        if (!loading) item(key = "custom") {
            val customModelTitle = uiText("Custom model"); val modelLabel = uiText("Model"); val providerIdLabel = uiText("Provider ID")
            val enterCustomModel = { vm.closeCatalog(); vm.openForm(ActionForm(customModelTitle, "/api/session/update", listOf(Field("model", modelLabel, required = true), Field("model_provider", providerIdLabel)), json("session_id" to chat.sessionId))) }
            SettingsSection { SettingsActionRow(uiText("Enter a configured model ID"), enterCustomModel, icon = Icons.Rounded.Add, enabled = !modelPending) }
        }
    }
}

/** A model in a provider's list: its name, its id when that differs, and a check (or a spinner while it is being applied). */
@Composable internal fun ModelRow(title: String, subtitle: String, selected: Boolean, applying: Boolean, enabled: Boolean, tag: String, onPick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().background(if (selected) scheme.primaryContainer.copy(alpha = .35f) else Color.Transparent).testTag(tag).semantics { this.selected = selected }
        .clickable(enabled = enabled) { onPick() }.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when {
            applying -> CircularProgressIndicator(Modifier.size(24.dp).testTag("model-applying"), strokeWidth = 2.dp)
            selected -> Icon(Icons.Rounded.Check, uiText("Selected"), tint = scheme.primary)
        }
    }
}

/** The chips a loading reasoning control draws: as many as the usual levels, each as wide and tall as a loaded chip. */
internal const val ReasoningSkeletonChips = 4
/** The cards a loading Reasoning sheet draws: the usual set of levels a model offers, so the sheet does not change height when they arrive. */
internal const val ReasoningSkeletonRows = 5

/** Reopening the sheet keeps the options already read for this chat's model (same request), so they show at once while Hermes is asked again; any other answer is dropped. */
internal fun reasoningKeptOnOpen(loaded: JSONObject?, requestId: String): Boolean =
    loaded != null && !loaded.has("_error") && requestId.isNotBlank() && loaded.optString("_request_key") == requestId
internal val ReasoningSkeletonWidth = 72.dp
internal val ReasoningSkeletonHeight = 32.dp

/** The chat's model with its provider, and the reasoning level as segments that apply on tap. Spoken as one line; the segments stay separate controls. */
@Composable private fun CurrentModelCard(
    model: String, modelId: String = "", provider: String, applying: Boolean, options: List<String>, effort: String, reasoningLoading: Boolean, reasoningFailed: Boolean,
    automatic: Boolean, reasoningPending: Boolean, onEffort: (String) -> Unit, onRetry: () -> Unit,
) {
    val title = model.ifBlank { uiText("Profile default") }
    val spoken = listOf("${uiText("Current")}: $title", provider).filter { it.isNotBlank() }.joinToString(", ")
    SettingsSection(title = uiText("Current"), testTag = "model-current") {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingsIconTile(Icons.Rounded.Memory)
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // The id stays visible under the readable name, so the exact model is never hidden.
                if (modelId.isNotBlank() && modelId != model) Text(modelId, Modifier.testTag("model-current-id"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (provider.isNotBlank()) Text(provider, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (applying) SettingsStatusPill(SettingsStatus.Working, uiText("Applying model…"))
        }
        when {
            options.isNotEmpty() -> {
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiText("Reasoning"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    ReasoningChoice(options, effort, enabled = !reasoningPending, onPick = onEffort)
                }
            }
            reasoningFailed -> { SettingsDivider(); SettingsErrorRow(uiText("Couldn't load reasoning"), onRetry, uiText("Retry")) }
            // The label and four chip-shaped blocks, the shape the loaded control has, so nothing jumps when it arrives.
            reasoningLoading -> {
                SettingsDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp).testTag("reasoning-loading"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(uiText("Reasoning"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        // The chat's own level shows at once; the blank chips hold the rest of the row until the list arrives.
                        if (effort.isNotBlank()) FilterChip(selected = true, onClick = {}, enabled = false, label = { Text(uiText(reasoningLevelLabel(effort))) }, modifier = Modifier.testTag("reasoning-current"))
                        repeat(reasoningSkeletonChipCount(effort)) { Box(Modifier.size(width = ReasoningSkeletonWidth, height = ReasoningSkeletonHeight).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = .06f))) }
                    }
                }
            }
            automatic -> { SettingsDivider(); Text(uiText("This model manages reasoning automatically."), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Two to four short levels read best as segments; a longer or larger set wraps as chips. Both keep the tag `reasoning-level:<level>`. */
@Composable private fun ReasoningChoice(options: List<String>, selected: String, enabled: Boolean, onPick: (String) -> Unit) {
    val labels = options.map { uiText(reasoningLevelLabel(it)) }
    if (options.size in 2..4 && labels.all { it.length <= 12 } && LocalDensity.current.fontScale < 1.3f) {
        SegmentedChoice("reasoning", selected, options, labels, onPick, enabled = enabled, testTags = options.associateWith { "reasoning-level:$it" })
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEachIndexed { index, option ->
                FilterChip(selected = option == selected, onClick = { if (option != selected) onPick(option) }, enabled = enabled, label = { Text(labels[index]) }, modifier = Modifier.testTag("reasoning-level:$option"))
            }
        }
    }
}

internal fun modelSubtitle(title: String, id: String): String = id.takeUnless { it == title }.orEmpty()

/** A model's name for people: a label Hermes sent, else the id read as words ("claude-cli/claude-opus-5-5" is "Claude Opus 5.5"). */
internal fun modelTitle(label: String, id: String): String = label.takeIf { it.isNotBlank() && it != id } ?: readableModelName(id)

/**
 * An id read as a name: the part after the provider's slash, split at hyphens; short numbers beside each other read as one version
 * ("5-5" is 5.5), a word repeated by the provider is shown once, and "gpt" stays capitals. Only the title changes; the id stays visible.
 */
internal fun readableModelName(id: String): String {
    val parts = id.substringAfterLast('/').split('-', '_').filter(String::isNotBlank)
    fun short(part: String) = part.length in 1..2 && part.all(Char::isDigit)
    val words = mutableListOf<String>()
    var index = 0
    while (index < parts.size) {
        val part = parts[index]
        val next = parts.getOrNull(index + 1)
        if (short(part) && next != null && short(next)) {
            words += "$part.$next"
            index += 2
            continue
        }
        if (words.lastOrNull()?.lowercase() != part.lowercase()) words += part
        index++
    }
    return words.joinToString(" ") { word ->
        when {
            word.lowercase() == "gpt" -> "GPT"
            word.first().isLetter() -> word.replaceFirstChar(Char::titlecase)
            else -> word
        }
    }.ifBlank { id }
}

/** The reasoning row's blank chips while the list loads: the chat's own level takes one of the slots, so the row keeps its width. */
internal fun reasoningSkeletonChipCount(effort: String): Int = ReasoningSkeletonChips - if (effort.isNotBlank()) 1 else 0
internal fun reasoningLevelLabel(level: String): String = if (level == "xhigh") "Extra high" else level.replaceFirstChar(Char::uppercase)
internal fun reasoningLevelDescription(level: String): String = when (level.lowercase()) {
    "none" -> "No thinking step"
    "minimal" -> "Use the least reasoning for quick replies"
    "low" -> "Faster, lighter responses"
    "medium" -> "Balanced detail"
    "high" -> "More detailed reasoning"
    "xhigh" -> "Spend more time on difficult problems"
    "max" -> "Thinks as long as it needs"
    "ultra" -> "Longest thinking"
    else -> "Use the model's default reasoning"
}

/** The levels Hermes names; a provider's own level shows as its capitalised word. */
internal val reasoningLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max", "ultra")

/** The reasoning words and sentences reach uiText through variables, so the translation test reads them from here. */
internal val ReasoningStrings = (reasoningLevels.map(::reasoningLevelLabel) + reasoningLevels.map(::reasoningLevelDescription) + reasoningLevelDescription("")).distinct()

/**
 * The loaded sheet's shape: one pane, [ReasoningSkeletonRows] option cards of the same height, so nothing moves when the real options arrive.
 * The working dust core sits in the first card where a chosen level's check would be, so it adds no height.
 */
@Composable internal fun ReasoningSkeleton() {
    val bar = MaterialTheme.colorScheme.onSurface
    SettingsSection(testTag = "reasoning-skeleton") {
        Column(Modifier.fillMaxWidth().padding(12.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(ReasoningSkeletonRows) { index ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeightTwoLine)) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.fillMaxWidth(if (index % 2 == 1) .34f else .46f).height(12.dp).clip(RoundedCornerShape(8.dp)).background(bar.copy(alpha = .08f)))
                            Box(Modifier.fillMaxWidth(.78f).height(9.dp).clip(RoundedCornerShape(8.dp)).background(bar.copy(alpha = .05f)))
                        }
                        if (index == 0) BeaconDustCore(28.dp)
                    }
                }
            }
        }
    }
}

/** A session's token figures, read from whichever names Hermes uses; the keys it consumed are left out of the Details view. */
internal data class SessionUsageFigures(
    val input: Long?, val output: Long?, val window: Long?, val used: Long?, val consumed: Set<String>,
    /** Hermes' own context_percent when it sent one; null falls back to the dashboard's rounding. */
    val reportedPercent: Int? = null,
    /** Hermes' context_estimated: the occupancy is an estimate, not a provider reading. */
    val estimated: Boolean = false,
) {
    /** Share of the context window in use, 0–100; null without both figures. */
    val percent: Int? get() = if (used != null && window != null && window > 0)
        (reportedPercent ?: (used.toDouble() / window * 100).roundToInt()).coerceIn(0, 100) else null
}

internal fun sessionUsageFigures(usage: JSONObject): SessionUsageFigures {
    val consumed = mutableSetOf<String>()
    fun parse(key: String): Long? {
        val raw = usage.opt(key)
        return (raw as? Number)?.toLong() ?: (raw as? String)?.toLongOrNull()
    }
    fun number(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key -> parse(key)?.also { consumed += key } }
    // The dashboard's counters are first non-zero: input_tokens is uncached input, prompt_tokens the prompt total.
    fun counter(vararg keys: String): Long? = keys.firstNotNullOfOrNull { key -> parse(key)?.takeIf { it != 0L }?.also { consumed += key } }
    val window = listOf("context_max", "context_length", "context_window").firstNotNullOfOrNull { key ->
        parse(key)?.takeIf { it > 0 }?.also { consumed += key }
    }
    // Hermes clamps occupancy to the window before display; an absent occupancy stays absent, so no bar is invented.
    val used = number("context_used", "context_tokens", "last_prompt_tokens")?.let { value ->
        if (window != null) value.coerceIn(0, window) else value.coerceAtLeast(0)
    }
    return SessionUsageFigures(
        input = counter("input_tokens", "prompt_tokens", "input", "prompt"),
        output = counter("output_tokens", "completion_tokens", "output", "completion"),
        window = window,
        used = used,
        consumed = consumed,
        reportedPercent = number("context_percent")?.toInt(),
        // The dashboard derives the flag from context_source when the record carries only that half of the pair.
        estimated = if (usage.has("context_estimated")) usage.optBoolean("context_estimated")
        else (usage.opt("context_source") as? String).orEmpty().let { it.isNotBlank() && it != "provider_usage" },
    )
}

/** The keys [sessionUsageFigures] interprets, so Details shows neither a second copy nor the lifetime totals it refuses as occupancy. */
internal val sessionUsageContextKeys = setOf(
    "input_tokens", "prompt_tokens", "output_tokens", "completion_tokens", "input", "prompt", "output", "completion",
    "context_used", "context_tokens", "last_prompt_tokens",
    "context_max", "context_length", "context_window",
    "context_percent", "context_source", "context_estimated", "context_breakdown", "context_files",
    "total_tokens", "total", "model",
)

/** One category of Hermes' context breakdown, in the order and with the colour Hermes sent. */
internal data class ContextUsageCategory(val id: String, val label: String, val tokens: Long, val colorToken: String)

internal data class ContextUsageBreakdown(val categories: List<ContextUsageCategory>, val estimated: Boolean) {
    val total: Long get() = categories.sumOf { it.tokens }
}

/** Hermes' session.context_breakdown payload, or the same shape nested in a usage record; null when it has no categories to draw. */
internal fun contextUsageBreakdown(usage: JSONObject): ContextUsageBreakdown? {
    val record = usage.optJSONObject("context_breakdown") ?: usage
    val rows = record.optJSONArray("categories") ?: return null
    val categories = (0 until rows.length()).mapNotNull { index ->
        val row = rows.optJSONObject(index) ?: return@mapNotNull null
        val id = row.optString("id").trim()
        val label = row.optString("label").trim()
        // Hermes may serialise a count as text; the figures above read both forms, so a row must too.
        val tokens = when (val raw = row.opt("tokens")) {
            is Number -> raw.toLong()
            is String -> raw.trim().toLongOrNull()
            else -> null
        }?.takeIf { it >= 0 } ?: return@mapNotNull null
        id.takeIf { it.isNotBlank() && label.isNotBlank() }?.let { ContextUsageCategory(it, label, tokens, row.optString("color")) }
    }
    if (categories.isEmpty()) return null
    return ContextUsageBreakdown(categories, record.optBoolean("context_estimated") || usage.optBoolean("context_estimated"))
}

/** A category's share of the breakdown Hermes returned, rounded as the dashboard rounds a percent. */
internal fun contextCategoryShare(tokens: Long, total: Long): Int =
    if (total > 0) ((tokens.toDouble() / total) * 100).roundToInt().coerceIn(0, 100) else 0

/** The CSS colour token Hermes' breakdown sends, mapped to the app's palette; the category id is the fallback for a token we do not know. */
internal fun contextCategoryBaseColor(category: ContextUsageCategory): Color {
    // var(--context-usage-x), var(--context-usage-x, #hex) and a raw colour all name the same category.
    val inner = category.colorToken.trim().removePrefix("var(").removeSuffix(")").trim()
    val named = inner.substringAfter("--", "").substringBefore(',').trim()
    val token = named.ifBlank { inner }
    if (token.startsWith("#")) runCatching { return Color(android.graphics.Color.parseColor(token)) }
    return when (token) {
        "context-usage-system" -> Color(0xFF7C7D81)
        "context-usage-tools" -> Color(0xFF9E94D5)
        "context-usage-rules" -> Color(0xFF1F8A65)
        "context-usage-skills" -> Color(0xFFC08532)
        "context-usage-mcp" -> Color(0xFFC14A7A)
        "context-usage-subagents" -> Color(0xFF1760DB)
        "context-usage-memory" -> Color(0xFFD67446)
        "context-usage-conversation" -> Color(0xFF4C7F8C)
        else -> when (category.id) {
            "system_prompt" -> Color(0xFF7C7D81)
            "tool_definitions" -> Color(0xFF9E94D5)
            "rules" -> Color(0xFF1F8A65)
            "skills" -> Color(0xFFC08532)
            "mcp" -> Color(0xFFC14A7A)
            "subagent_definitions" -> Color(0xFF1760DB)
            "memory" -> Color(0xFFD67446)
            "conversation" -> Color(0xFF4C7F8C)
            else -> Color(0xFF7C7D81)
        }
    }
}

/** Hermes sent no token figures for a chat that holds replies: input and output are both zero or missing. */
internal fun sessionUsageUnreported(figures: SessionUsageFigures, hasReplies: Boolean): Boolean =
    hasReplies && (figures.input ?: 0L) == 0L && (figures.output ?: 0L) == 0L

/** The calm words for a context window at or near its end; null below 90%. */
internal fun usageContextStatus(percent: Int?): String? = when {
    percent == null -> null
    percent >= 100 -> "Context full"
    percent >= 90 -> "Nearly full"
    else -> null
}

/** Whether a usage record holds anything to show: a key that is not a marker (`_…`) or the server's own error, success or message fields, with a value. */
internal fun sessionUsageReadable(usage: JSONObject): Boolean = usage.keys().asSequence().any { key ->
    !key.startsWith('_') && key !in setOf("error", "success", "message") && usage.opt(key).let { it != null && it != JSONObject.NULL && it.toString().isNotBlank() }
}

/** 100 → "100", 1,234 → "1.2K", 32,000 → "32K", 1,200,000 → "1.2M". */
internal fun formatTokenCount(value: Long, locale: Locale = Locale.getDefault()): String {
    val number = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
    return when {
        value < 1_000 -> number.format(value)
        value < 10_000 -> number.format(value / 1_000.0) + "K"
        value < 1_000_000 -> NumberFormat.getIntegerInstance(locale).format(value / 1_000) + "K"
        else -> number.format(value / 1_000_000.0) + "M"
    }
}

/** 38 -> "38%" in the shown language's own digits and percent sign ("٣٨٪", "%38"). */
internal fun formatContextPercent(percent: Int, locale: Locale = Locale.getDefault()): String =
    NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0; minimumFractionDigits = 0 }.format(percent / 100.0)

/** Each administration destination has a native operation or a concrete reader. */
@Composable fun NativeAdministration(vm: BeaconViewModel, page: String) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var query by rememberSaveable(page, ui.origin, ui.owner, ui.profile) { mutableStateOf("") }
    val usageKey = "native-usage:${ui.profile}:${chat.sessionId}"
    LaunchedEffect(page, ui.origin, ui.owner, ui.profile, chat.sessionId) {
        if (page == "Commands") vm.loadSlashCatalog()
        if (page == "Session usage" && chat.sessionId.isNotBlank()) vm.load(usageKey, "/api/session/usage", mapOf("session_id" to chat.sessionId))
    }
    Column(Modifier.fillMaxSize()) {
        Header(uiText(settingsLabel(page)), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current, actions = {
            if (page == "Commands") IconButton(onClick = { vm.loadSlashCatalog(force = true) }, enabled = "slash:catalog" !in refreshing) { Icon(Icons.Rounded.Refresh, uiText("Refresh commands")) }
            if (page == "Session usage") IconButton(onClick = { vm.load(usageKey, "/api/session/usage", mapOf("session_id" to chat.sessionId)) }, enabled = chat.sessionId.isNotBlank() && usageKey !in refreshing) { Icon(Icons.Rounded.Refresh, uiText("Refresh usage")) }
        })
        when (page) {
            "Tools & commands" -> NativeToolSettings(vm, containedActions = true)
            "MCP servers" -> NativeToolSettings(vm, mcp = true, containedActions = true)
            "Providers & models" -> ProvidersModelsPage(vm)
            "Commands" -> CommandsPage(vm, query) { query = it }
            "Session usage" -> SessionUsagePage(vm, usageKey)
            else -> LaunchedEffect(page) { vm.tab("More") }
        }
    }
}

/** Two rows: this chat's model, and the profile defaults it falls back to. */
@Composable private fun ProvidersModelsPage(vm: BeaconViewModel) {
    val chat by vm.chat.collectAsStateWithLifecycle()
    val hasChat = chat.sessionId.isNotBlank()
    SettingsList(testTag = "providers-page") {
        item { SettingsSection(title = uiText("This chat")) {
            SettingsNavRow(uiText("Model"), { openModelPicker(vm) }, icon = Icons.Rounded.Memory, enabled = hasChat,
                value = if (!hasChat) uiText("Open a chat first") else chat.raw.string("model").ifBlank { uiText("Profile default") }, testTag = "providers-this-chat")
        } }
        item { SettingsSection(title = uiText("Defaults")) {
            SettingsNavRow(uiText("Profile defaults"), { vm.more("Profiles") }, icon = Icons.Rounded.SmartToy, testTag = "providers-profile-defaults")
        } }
    }
}

@Composable private fun CommandsPage(vm: BeaconViewModel, query: String, onQuery: (String) -> Unit) {
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val catalog = data["slash:catalog"]
    val error = recordError(catalog)
    val commands = remember(catalog, query) { mobileSlashCommands(catalog?.rows("commands").orEmpty()).filter { query.isBlank() || (it.string("name") + it.string("description")).contains(query, true) } }
    SettingsList(refreshing = catalog != null && "slash:catalog" in refreshing, testTag = "commands-page") {
        item { SettingsSearchField(query, onQuery, uiText("Search commands"), testTag = "commands-search") }
        if (error.isNotBlank()) item { SettingsSection { SettingsErrorRow(uiText(error), { vm.loadSlashCatalog(force = true) }, uiText("Retry")) } }
        when {
            catalog == null -> item { SettingsSection { SettingsSkeletonRows(4) } }
            commands.isNotEmpty() -> item { SettingsSection(testTag = "commands-list") {
                commands.forEachIndexed { index, row ->
                    if (index > 0) SettingsDivider()
                    val name = row.string("name").removePrefix("/")
                    // Choosing a command prepares it in Chat for review; it is never sent from here.
                    CommandRow(name, commandDescriptionText(name, row.string("description"))) { vm.tab("Chat"); vm.composer(TextFieldValue("/$name ")) }
                }
            } }
            error.isBlank() -> item { SettingsSection { SettingsEmpty(uiText("No matching commands"), Icons.Rounded.SearchOff, action = if (query.isBlank()) null else SettingsAction(uiText("Clear search"), { onQuery("") })) } }
        }
    }
}

/** A built-in command has the app's own short description, which goes through uiText; a custom command keeps the description it came with. */
internal fun isBuiltInCommand(name: String): Boolean = slashCommandText(name, "") in SlashCommandStrings

@Composable private fun commandDescriptionText(name: String, description: String): String = if (isBuiltInCommand(name)) uiText(description) else description

@Composable internal fun CommandRow(name: String, description: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = SettingsKit.RowMinHeightTwoLine).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsIconTile(Icons.Rounded.Terminal)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("/$name", style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium).contentDirection(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium.contentDirection(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The open chat's token use as figures and a context bar; keys it does not know sit behind Details. */
@Composable private fun SessionUsagePage(vm: BeaconViewModel, usageKey: String) {
    val data by vm.data.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val chat by vm.chat.collectAsStateWithLifecycle()
    val usage = data[usageKey]
    val error = recordError(usage)
    val locale = uiLocale()
    var details by rememberSaveable(usageKey) { mutableStateOf(false) }
    val reload = { vm.load(usageKey, "/api/session/usage", mapOf("session_id" to chat.sessionId)); Unit }
    // REVIEW-FIXCHAT-121 P3: the title follows the chat header's rule, and the model is named as the composer's chip names it (no raw ids).
    val title = if (chat.title == "New conversation") uiText("New chat") else chat.title.ifBlank { uiText("Chat usage") }
    // Hermes' own session.usage model is the live session's; the chat projection is only the fallback before it lands.
    val modelLine = chatModelTitle(usage?.string("model").orEmpty().ifBlank { chat.raw.string("model") }, data["picker:models"]?.rows("groups").orEmpty())
    SettingsList(refreshing = usage != null && usageKey in refreshing, testTag = "usage-page") {
        when {
            chat.sessionId.isBlank() -> item { SettingsSection { SettingsEmpty(uiText("Open a chat first"), Icons.Rounded.ChatBubbleOutline) } }
            // UX-121 P3-10: what the phone knows (the chat and its model) shows at once; only the figures wait for Hermes.
            usage == null -> {
                item { SettingsStatusHero(null, title, line = modelLine, icon = Icons.Rounded.BarChart, testTag = "usage-hero") }
                item { SettingsSection { SettingsSkeletonRows(2) } }
            }
            error.isNotBlank() && !sessionUsageReadable(usage) -> item { SettingsSection { SettingsErrorRow(uiText(error), reload, uiText("Retry")) } }
            else -> {
                val figures = sessionUsageFigures(usage)
                val breakdown = contextUsageBreakdown(usage)
                val estimated = figures.estimated || breakdown?.estimated == true
                item { SettingsStatusHero(null, title, line = modelLine, icon = Icons.Rounded.BarChart, testTag = "usage-hero") }
                // "0 in, 0 out" under a chat full of replies is Hermes not counting, not the chat using nothing.
                val unreported = sessionUsageUnreported(figures, chat.messages.any { it.role == "assistant" })
                val hasTokens = !unreported && (figures.input != null || figures.output != null)
                val hasContext = figures.window != null || figures.used != null
                if (unreported || hasTokens || hasContext) item { SettingsSection(testTag = "usage-figures") {
                    val tokens = listOfNotNull(
                        figures.input?.takeUnless { unreported }?.let { uiText("Tokens in") to formatTokenCount(it, locale) },
                        figures.output?.takeUnless { unreported }?.let { uiText("Tokens out") to formatTokenCount(it, locale) },
                    )
                    val context = listOfNotNull(
                        figures.window?.let { uiText("Context window") to formatTokenCount(it, locale) },
                        figures.used?.let { uiText("Context used") to formatTokenCount(it, locale) },
                    )
                    if (unreported) SettingsValueRow(uiText("Hermes didn't report usage for this chat."), icon = Icons.Rounded.Info, testTag = "usage-unreported")
                    if (tokens.isNotEmpty()) {
                        SettingsMetricRow(tokens)
                        // These two are the chat's whole history; the context figures below are the window as it stands now.
                        Text(uiText("Cumulative tokens for this chat."), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (tokens.isNotEmpty() && context.isNotEmpty()) SettingsDivider()
                    if (context.isNotEmpty()) SettingsMetricRow(context)
                    val percent = figures.percent
                    val used = figures.used
                    val window = figures.window
                    if (percent != null && used != null && window != null && window > 0) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).testTag("usage-context-bar"))
                            // Hermes' own used and window figures, never a sum of the two token totals above (they can differ).
                            val estimatedNote = if (estimated) " · " + uiText("estimated") else ""
                            Text(uiText("{0} of {1} used").replace("{0}", formatTokenCount(used, locale)).replace("{1}", formatTokenCount(window, locale)) + estimatedNote,
                                style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            val share = uiText("{0}% of context").replace("{0}", "$percent")
                            val state = usageContextStatus(percent)?.let { uiText(it) }
                            Text(if (state.isNullOrBlank()) share else "$share · $state", style = nativeMetadataStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } }
                val extra = usage.keys().asSequence().filter { key ->
                    !key.startsWith('_') && key != "error" && key !in figures.consumed && key !in sessionUsageContextKeys &&
                        usage.opt(key).let { value -> value != null && value != JSONObject.NULL && value.toString().isNotBlank() }
                }.toList()
                item { SettingsSection(testTag = "usage-details") {
                    val stateText = uiText(if (details) "Expanded" else "Collapsed")
                    SettingsNavRow(uiText("Details"), { details = !details }, Modifier.semantics { stateDescription = stateText }, icon = Icons.Rounded.Info,
                        minHeight = SettingsKit.RowMinHeight, trailingIcon = if (details) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, testTag = "usage-details-toggle")
                    if (details) {
                        SettingsDivider()
                        if (breakdown != null) ContextBreakdownDetails(breakdown)
                        else ContextBreakdownUnavailable()
                        if (extra.isNotEmpty()) {
                            SettingsDivider()
                            Box(Modifier.fillMaxWidth().padding(16.dp)) { StructuredRead(JSONObject(usage, extra.toTypedArray())) }
                        }
                    }
                } }
                // A failed refresh keeps the figures already read; its error sits under them.
                if (error.isNotBlank()) item(key = "refresh-error") { SettingsSection { SettingsErrorRow(uiText(error), reload, uiText("Retry")) } }
            }
        }
    }
}

/** Hermes' own context categories as one stacked bar and its rows, in the order and colours Hermes returned. */
@Composable private fun ContextBreakdownDetails(breakdown: ContextUsageBreakdown) {
    val locale = uiLocale()
    val scheme = MaterialTheme.colorScheme
    val total = breakdown.total
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(uiText("Token breakdown"), style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        if (total > 0) Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(scheme.surfaceContainerHighest)
            .testTag("usage-breakdown-bar")) {
            breakdown.categories.filter { it.tokens > 0 }.forEach { category ->
                Box(Modifier.weight(category.tokens.toFloat()).fillMaxHeight().background(contextCategoryColor(category)))
            }
        }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            breakdown.categories.forEach { category ->
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("usage-breakdown-${category.id}"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(contextCategoryColor(category)).clearAndSetSemantics {})
                    Text(category.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.contentDirection(),
                        color = scheme.onSurface, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                    Text("${formatTokenCount(category.tokens, locale)} · ${formatContextPercent(contextCategoryShare(category.tokens, total), locale)}",
                        style = nativeMetadataStyle(), color = scheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 2)
                }
            }
        }
    }
}

/** No breakdown for this chat: the plain figures above stay, and this says why there is no bar rather than drawing an empty one. */
@Composable private fun ContextBreakdownUnavailable() {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(uiText("Token breakdown"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(uiText("The context breakdown isn't available for this chat."), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The category colour Hermes sent, kept at 3:1 against the theme's own surfaces in light and dark. */
@Composable private fun contextCategoryColor(category: ContextUsageCategory): Color {
    val scheme = MaterialTheme.colorScheme
    return remember(category.id, category.colorToken, scheme.background, scheme.surface) {
        readableInk(contextCategoryBaseColor(category), min = 3.05f) { listOf(scheme.background, scheme.surface) }
    }
}
