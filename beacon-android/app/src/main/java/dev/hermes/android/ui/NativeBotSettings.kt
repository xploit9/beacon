@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.NativeBotsState
import kotlinx.coroutines.delay

/** Models shown before "More models": the rest wait behind one button, and the chosen model always stays on screen. */
internal const val ProfileModelsCollapsed = 8

/** The id [SettingsOptionCards] keeps for one provider/model pair. */
internal fun profileModelOptionId(provider: String, model: String) = "$provider:$model"

/** The models that match a search typed over "provider model"; blank shows all, repeats show once. */
internal fun profileModelsMatching(models: List<Pair<String, String>>, query: String): List<Pair<String, String>> =
    models.distinct().filter { query.isBlank() || "${it.first} ${it.second}".contains(query, ignoreCase = true) }

/**
 * Profiles: a list of profile rows, and for one profile a hero with its default model, behavior and shortcuts.
 * [approvals] is the profile's approval choice; it sits first in the list and under the hero on a profile, where it applies to that profile.
 */
@Composable fun NativeBotSettings(state: NativeBotsState, onOpen: (String) -> Unit, onBack: () -> Unit,
    onEditModel: () -> Unit, onModelDraft: (String, String) -> Unit, onSaveModel: () -> Unit, onCancel: () -> Unit,
    onSettings: (String, String) -> Unit, onRefresh: () -> Unit, models: List<Pair<String, String>> = emptyList(), onEditDescription: (() -> Unit)? = null, onEditInstructions: (() -> Unit)? = null, profileLabel: (String) -> String = { it },
    onNewProfile: (() -> Unit)? = null, canDelete: (String) -> Boolean = { false }, onDelete: (String) -> Unit = {},
    activeProfile: String = "", approvals: (@Composable () -> Unit)? = null) {
    var modelSearch by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf("") }
    var customModel by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf(false) }
    var moreModels by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf(false) }
    // The pull-down spinner is only for a pull; a load that starts by itself shows the thin bar over the rows already there.
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulled = false }
    val motionEnabled = rememberNativeMotionEnabled()
    val retry = if (state.editing || state.saving) null else onRefresh
    PullToRefreshBox(isRefreshing = pulled && state.loading, onRefresh = { if (!state.saving && !state.editing) { pulled = true; onRefresh() } }, modifier = Modifier.fillMaxSize()) {
        SettingsList(refreshing = state.loading || state.saving, testTag = "profiles-list") {
            val bot = state.selected
            if (bot == null) {
                if (approvals != null) item(key = "approvals") { ProfileEntrance(0, motionEnabled) { approvals() } }
                item(key = "profiles") {
                    ProfileEntrance(1, motionEnabled) {
                        // Not "Profiles": the page header already says that, and a Settings page shows its title once.
                        SettingsSection(title = uiText("Your profiles"), testTag = "profiles-section") {
                            val shown = state.error.isNotBlank() || state.notice.isNotBlank()
                            ProfileFeedback(state, retry)
                            if (state.profiles.isEmpty()) when {
                                state.loading -> SettingsSkeletonRows(3)
                                state.error.isBlank() -> SettingsEmpty(uiText("No profiles"), ProfilesIcon, action = SettingsAction(uiText("Refresh"), onRefresh))
                            }
                            state.profiles.forEachIndexed { index, summary ->
                                if (index > 0 || shown) SettingsDivider()
                                val active = activeProfile.isNotBlank() && summary.profile == activeProfile
                                // A profile with no description shows only its name: "No purpose" read as a judgement.
                                SettingsNavRow(profileLabel(summary.profile), { onOpen(summary.profile) }, icon = ProfilesIcon, value = summary.purpose,
                                    status = if (active) SettingsStatus.Ok else null, statusLabel = if (active) uiText("Active") else "", enabled = !state.loading, testTag = "profile-open:${summary.profile}")
                            }
                            if (onNewProfile != null) {
                                if (state.profiles.isNotEmpty() || shown) SettingsDivider()
                                SettingsActionRow(uiText("New profile"), onNewProfile, icon = Icons.Rounded.Add, enabled = !state.loading, testTag = "new-profile")
                            }
                        }
                    }
                }
            } else {
                val active = activeProfile.isNotBlank() && bot.profile == activeProfile
                // The description is its own editable row under Behavior, so the hero shows it nowhere else.
                item(key = "hero") {
                    ProfileEntrance(0, motionEnabled) {
                        SettingsStatusHero(status = null, title = profileLabel(bot.profile), line = "", icon = ProfilesIcon,
                            meta = if (active) ({ SettingsStatusPill(SettingsStatus.Ok, uiText("Active")) }) else null, testTag = "profile-hero")
                    }
                }
                if (approvals != null) item(key = "approvals") { ProfileEntrance(1, motionEnabled) { approvals() } }
                item(key = "model") {
                    ProfileEntrance(2, motionEnabled) {
                        SettingsSection(title = uiText("Default model"), footer = if (state.editing && models.isEmpty()) uiText("Set up a provider on your computer") else "", testTag = "profile-model-section") {
                            ProfileFeedback(state, retry)
                            if (!state.editing) {
                                if (state.error.isNotBlank() || state.notice.isNotBlank()) SettingsDivider()
                                SettingsNavRow(uiText("Model"), onEditModel, icon = Icons.Rounded.Memory,
                                    value = "${bot.provider.ifBlank { uiText("Provider not set") }} · ${bot.model.ifBlank { uiText("Model not set") }}", enabled = !state.loading, testTag = "profile-model")
                            } else {
                                if (state.error.isNotBlank()) SettingsDivider()
                                ModelEditor(state, bot.provider, bot.model, models, modelSearch, { modelSearch = it }, moreModels, { moreModels = true }, customModel, { customModel = !customModel }, onModelDraft, onSaveModel, onCancel)
                            }
                        }
                    }
                }
                item(key = "behavior") {
                    ProfileEntrance(3, motionEnabled) {
                        // Owner, 4 Nov 2026: both are edited here. The instructions are a plain preview that opens the whole text to edit; the chat
                        // renderer drew Beacon's own <!-- markers --> as empty cards.
                        SettingsSection(title = uiText("Behavior"), testTag = "profile-behavior-section") {
                            SettingsNavRow(uiText("Description"), { onEditDescription?.invoke() }, icon = Icons.Rounded.Edit, value = bot.description,
                                enabled = onEditDescription != null && !state.loading && !state.saving, testTag = "profile-description")
                            SettingsDivider()
                            SettingsNavRow(uiText("Instructions"), { onEditInstructions?.invoke() }, icon = Icons.Rounded.Description,
                                value = profileInstructionsPreview(bot.soul).ifBlank { uiText("No instructions") },
                                enabled = onEditInstructions != null && !state.loading && !state.saving, testTag = "profile-instructions")
                        }
                    }
                }
                item(key = "profile-tools") {
                    ProfileEntrance(4, motionEnabled) {
                        SettingsSection(title = uiText("For this profile"), testTag = "profile-tools-section") {
                            SettingsNavRow(uiText("Skills"), { onSettings(bot.profile, "Skills") }, icon = SkillsIcon, minHeight = SettingsKit.RowMinHeight, testTag = "profile-skills")
                            SettingsDivider()
                            SettingsNavRow(uiText("Memory"), { onSettings(bot.profile, "Memory") }, icon = MemoryIcon, minHeight = SettingsKit.RowMinHeight, testTag = "profile-memory")
                        }
                    }
                }
                if (canDelete(bot.profile)) item(key = "delete") {
                    ProfileEntrance(5, motionEnabled) {
                        SettingsSection(testTag = "profile-delete-section") {
                            SettingsDestructiveRow(uiText("Delete profile"), { onDelete(bot.profile) }, enabled = !state.loading && !state.saving, testTag = "delete-profile:${bot.profile}")
                        }
                    }
                }
            }
        }
    }
}

/** The first words of a profile's instructions, without Markdown marks or the <!-- markers --> Beacon keeps in the file. */
internal fun profileInstructionsPreview(soul: String): String =
    soul.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "\n").lineSequence()
        .map { it.trim().trimStart('#', '>', '-', '*', ' ').trim() }.firstOrNull { it.isNotBlank() }.orEmpty().take(200)

/** The error (with Retry when a reload is safe) and the last saved notice, at the top of the section they are about. */
@Composable private fun ColumnScope.ProfileFeedback(state: NativeBotsState, onRetry: (() -> Unit)?) {
    if (state.error.isNotBlank()) SettingsErrorRow(uiText(state.error), onRetry, uiText("Retry"), retryTestTag = "profile-retry")
    if (state.notice.isNotBlank()) {
        if (state.error.isNotBlank()) SettingsDivider()
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) { SettingsStatusPill(SettingsStatus.Ok, uiText(state.notice), testTag = "profile-notice") }
    }
}

/** Choosing the default model: search, a card per model, an optional model-ID entry, then Save and Cancel. */
@Composable private fun ColumnScope.ModelEditor(
    state: NativeBotsState, savedProvider: String, savedModel: String, models: List<Pair<String, String>>, search: String, onSearch: (String) -> Unit,
    expanded: Boolean, onExpand: () -> Unit, custom: Boolean, onToggleCustom: () -> Unit,
    onModelDraft: (String, String) -> Unit, onSave: () -> Unit, onCancel: () -> Unit,
) {
    val visible = profileModelsMatching(models, search)
    OutlinedTextField(search, onSearch, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("profile-model-search"), label = { Text(uiText("Search models or providers")) },
        leadingIcon = { Icon(Icons.Rounded.Search, null) }, singleLine = true, enabled = !state.saving)
    if (visible.isEmpty()) {
        if (models.isEmpty()) SettingsEmpty(uiText("No models available"), Icons.Rounded.Memory)
        else SettingsEmpty(uiText("No matching models"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Clear search"), { onSearch("") }))
    } else {
        val options = visible.map { (provider, model) -> SettingsOption(profileModelOptionId(provider, model), model, provider, testTag = "profile-model-option:$provider:$model") }
        val selected = profileModelOptionId(state.draftProvider, state.draftModel)
        val hidden = options.size - settingsOptionsShown(options, selected, expanded, ProfileModelsCollapsed).size
        SettingsOptionCards(options, selected, onSelect = { id -> visible.firstOrNull { profileModelOptionId(it.first, it.second) == id }?.let { onModelDraft(it.first, it.second) } },
            enabled = !state.saving, collapsedCount = ProfileModelsCollapsed, moreLabel = uiText("More models ({0})").replace("{0}", "$hidden"),
            moreTestTag = "profile-more-models", expanded = expanded, onExpand = onExpand)
    }
    SettingsDivider()
    SettingsActionRow(uiText(if (custom) "Hide model ID" else "Use a model ID"), onToggleCustom, icon = Icons.Rounded.Edit, enabled = !state.saving, testTag = "profile-model-custom")
    if (custom) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.draftProvider, { onModelDraft(it, state.draftModel) }, Modifier.fillMaxWidth(), label = { Text(uiText("Configured provider ID")) }, singleLine = true, enabled = !state.saving)
        OutlinedTextField(state.draftModel, { onModelDraft(state.draftProvider, it) }, Modifier.fillMaxWidth(), label = { Text(uiText("Model ID")) }, singleLine = true, enabled = !state.saving)
    }
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = !state.saving && state.draftModel.isNotBlank() && state.draftProvider.isNotBlank() && (state.draftModel != savedModel || state.draftProvider != savedProvider),
            modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("profile-model-save")) { Text(uiText("Save default model")) }
        TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.heightIn(min = SettingsKit.TouchMin).testTag("profile-model-cancel")) { Text(uiText("Cancel")) }
    }
}

@Composable private fun ProfileEntrance(index: Int, motionEnabled: Boolean, content: @Composable () -> Unit) {
    var entered by remember(motionEnabled, index) { mutableStateOf(!motionEnabled) }
    LaunchedEffect(motionEnabled, index) {
        if (motionEnabled) {
            delay((index * 55L).coerceAtMost(275L))
            entered = true
        }
    }
    val progress by animateFloatAsState(if (entered) 1f else 0f, animationSpec = tween(220), label = "profile-entry-$index")
    Box(Modifier.graphicsLayer { alpha = .65f + .35f * progress; translationY = (1f - progress) * 10.dp.toPx() }) { content() }
}
