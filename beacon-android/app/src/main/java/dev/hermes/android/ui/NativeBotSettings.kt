package dev.hermes.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Launch
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import dev.hermes.android.data.NativeBotsState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativeBotSettings(state: NativeBotsState, onOpen: (String) -> Unit, onBack: () -> Unit,
    onEditModel: () -> Unit, onModelDraft: (String, String) -> Unit, onSaveModel: () -> Unit, onCancel: () -> Unit,
    onSettings: (String, String) -> Unit, onRefresh: () -> Unit, models: List<Pair<String, String>> = emptyList(), renderBehavior: @Composable (String) -> Unit = { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } }, profileLabel: (String) -> String = { it },
    onNewProfile: (() -> Unit)? = null, canDelete: (String) -> Boolean = { false }, onDelete: (String) -> Unit = {}) {
    var showBehavior by rememberSaveable(state.selected?.profile) { mutableStateOf(false) }
    var modelSearch by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf("") }
    var customModel by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf(false) }
    val motionEnabled = rememberNativeMotionEnabled()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = { if (!state.saving && !state.editing) onRefresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(SettingsPagePadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ProfileEntrance(0, motionEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(uiText("Purpose, model and behavior for each profile."), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (onNewProfile != null && state.selected == null) Button(onClick = onNewProfile, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("new-profile")) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(uiText("New profile")) }
                    }
                }
            }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error.isNotBlank()) item { Text(uiText(state.error), color = MaterialTheme.colorScheme.error) }
            if (state.notice.isNotBlank()) item { Text(uiText(state.notice)) }
            if (state.selected == null) {
                if (!state.loading && state.profiles.isEmpty()) item { EmptyState(uiText("No profiles"), uiText("Pull down to check again."), ProfilesIcon, action = uiText("Refresh"), onAction = onRefresh) }
                itemsIndexed(state.profiles, key = { _, bot -> bot.profile }) { index, bot ->
                    ProfileEntrance(index + 1, motionEnabled) {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(ProfilesIcon, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(profileLabel(bot.profile), style = MaterialTheme.typography.titleMedium)
                                }
                                ProfileValue(Icons.Rounded.Flag, "Purpose", bot.purpose.ifBlank { uiText("Not set") })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedButton(onClick = { onOpen(bot.profile) }, enabled = !state.loading) { Text(uiText("View profile")) }
                                    if (canDelete(bot.profile)) TextButton(onClick = { onDelete(bot.profile) }, enabled = !state.loading, modifier = Modifier.heightIn(min = 48.dp).testTag("delete-profile:${bot.profile}")) { Text(uiText("Delete profile"), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                        }
                    }
                }
            } else {
                val bot = state.selected
                item {
                    ProfileEntrance(0, motionEnabled) {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                                Text(profileLabel(bot.profile), style = MaterialTheme.typography.titleLarge)
                                Text(uiText("Profile settings"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    ProfileEntrance(1, motionEnabled) {
                        ProfileCard(Icons.Rounded.Flag, "Purpose") {
                            SelectionContainer { Text(bot.description.ifBlank { uiText("No purpose set.") }) }
                        }
                    }
                }
                item {
                    ProfileEntrance(2, motionEnabled) {
                        ProfileCard(Icons.Rounded.Memory, "Default model") {
                            Text("${bot.provider.ifBlank { uiText("Provider not set") }} · ${bot.model.ifBlank { uiText("Model not set") }}", style = MaterialTheme.typography.bodyLarge)
                            Text("${uiText("Used for new chats in")} ${profileLabel(bot.profile)}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!state.editing) OutlinedButton(onClick = onEditModel, enabled = !state.loading) { Text(uiText("Change default model")) }
                        }
                    }
                }
                if (state.editing) {
                    item {
                        ProfileEntrance(3, motionEnabled) {
                            ProfileCard(Icons.Rounded.Tune, "Change default model") {
                                Text("${uiText("Selected")}: ${state.draftProvider} · ${state.draftModel}")
                                OutlinedTextField(modelSearch, { modelSearch = it }, label = { Text(uiText("Search models or providers")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                            }
                        }
                    }
                    val visible = models.distinct().filter { modelSearch.isBlank() || "${it.first} ${it.second}".contains(modelSearch, ignoreCase = true) }
                    if (visible.isEmpty()) item {
                        if (models.isEmpty()) EmptyState(uiText("No models available"), uiText("Set up a provider for this profile on your computer."), Icons.Rounded.Memory)
                        else EmptyState(uiText("No matching models"), uiText("Try another search."), Icons.Rounded.SearchOff, action = uiText("Clear search"), onAction = { modelSearch = "" })
                    }
                    itemsIndexed(visible, key = { _, model -> "${model.first}:${model.second}" }) { _, (provider, model) ->
                        val chosen = provider == state.draftProvider && model == state.draftModel
                        ListItem(headlineContent = { Text(model) }, supportingContent = { Text(provider) },
                            trailingContent = { if (chosen) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).selectable(selected = chosen, enabled = !state.saving, role = Role.RadioButton) { onModelDraft(provider, model) })
                    }
                    item {
                        ProfileCard(Icons.Rounded.Edit, "Configured model ID") {
                            OutlinedButton(onClick = { customModel = !customModel }, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText(if (customModel) "Hide custom model entry" else "Enter a configured model ID")) }
                            if (customModel) {
                                OutlinedTextField(state.draftProvider, { onModelDraft(it, state.draftModel) }, label = { Text(uiText("Configured provider ID")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                                OutlinedTextField(state.draftModel, { onModelDraft(state.draftProvider, it) }, label = { Text(uiText("Model ID")) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                            }
                            Text(uiText("The server checks this profile's configured providers."), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onSaveModel, enabled = !state.saving && state.draftModel.isNotBlank() && state.draftProvider.isNotBlank() && (state.draftModel != bot.model || state.draftProvider != bot.provider)) { Text(uiText("Save default model")) }
                            TextButton(onClick = onCancel, enabled = !state.saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel edit")) }
                        }
                    }
                }
                item {
                    ProfileEntrance(4, motionEnabled) {
                        ProfileCard(Icons.Rounded.Description, "Behavior instructions") {
                            TextButton(onClick = { showBehavior = !showBehavior }, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText(if (showBehavior) "Hide behavior instructions" else "Read behavior instructions")) }
                            if (showBehavior) {
                                renderBehavior(bot.soul.ifBlank { uiText("No behavior instructions are set.") })
                                Text(uiText("Read-only profile instructions."), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    ProfileEntrance(5, motionEnabled) {
                        ProfileCard(Icons.AutoMirrored.Rounded.Launch, "Profile tools") {
                            ProfileAction("Skills for this profile", SkillsIcon) { onSettings(bot.profile, "Skills") }
                            ProfileAction("Memory for this profile", MemoryIcon) { onSettings(bot.profile, "Memory") }
                            ProfileAction("Profiles & model", ProfilesIcon) { onSettings(bot.profile, "Profiles & model") }
                        }
                    }
                }
                if (canDelete(bot.profile)) item {
                    TextButton(onClick = { onDelete(bot.profile) }, enabled = !state.loading && !state.saving, modifier = Modifier.heightIn(min = 48.dp).testTag("delete-profile:${bot.profile}")) { Text(uiText("Delete profile"), color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable private fun ProfileCard(icon: ImageVector, title: String, content: @Composable ColumnScope.() -> Unit) {
    Glass(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                Text(uiText(title), style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable private fun ProfileValue(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column { Text(uiText(title), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun ProfileAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(uiText(label), Modifier.weight(1f)); Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null)
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
