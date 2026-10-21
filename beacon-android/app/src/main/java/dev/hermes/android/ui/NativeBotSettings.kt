package dev.hermes.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import dev.hermes.android.data.NativeBotsState
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun NativeBotSettings(state: NativeBotsState, onOpen: (String) -> Unit, onBack: () -> Unit,
    onEditModel: () -> Unit, onModelDraft: (String, String) -> Unit, onSaveModel: () -> Unit, onCancel: () -> Unit,
    onSettings: (String, String) -> Unit, onRefresh: () -> Unit, models: List<Pair<String, String>> = emptyList(), renderBehavior: @Composable (String) -> Unit = { SelectionContainer { Text(it, style = MaterialTheme.typography.bodyLarge) } }, profileLabel: (String) -> String = { it }) {
    var showBehavior by rememberSaveable(state.selected?.profile) { mutableStateOf(false) }
    var modelSearch by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf("") }
    var customModel by rememberSaveable(state.selected?.profile, state.editing) { mutableStateOf(false) }
    val motionEnabled = rememberNativeMotionEnabled()
    PullToRefreshBox(isRefreshing = state.loading, onRefresh = { if (!state.saving && !state.editing) onRefresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                ProfileEntrance(0, motionEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Profiles", style = MaterialTheme.typography.titleLarge)
                        Text("Purpose, model and behavior for each profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (state.loading || state.saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            if (state.error.isNotBlank()) item { Text(state.error, color = MaterialTheme.colorScheme.error) }
            if (state.notice.isNotBlank()) item { Text(state.notice) }
            if (state.selected == null) {
                if (!state.loading && state.profiles.isEmpty()) item {
                    Glass(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            BeaconSignal(BeaconMotionMode.Sweep, size = 48.dp)
                            Text("No profiles", style = MaterialTheme.typography.titleLarge)
                            Text("Pull down to check again.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                itemsIndexed(state.profiles, key = { _, bot -> bot.profile }) { index, bot ->
                    ProfileEntrance(index + 1, motionEnabled) {
                        Glass(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Rounded.SmartToy, null, tint = MaterialTheme.colorScheme.primary)
                                    Text(profileLabel(bot.profile), style = MaterialTheme.typography.titleMedium)
                                }
                                ProfileValue(Icons.Rounded.Flag, "Purpose", bot.purpose.ifBlank { "Not set" })
                                OutlinedButton(onClick = { onOpen(bot.profile) }, enabled = !state.loading) { Text("View profile") }
                            }
                        }
                    }
                }
            } else {
                val bot = state.selected
                item {
                    ProfileEntrance(0, motionEnabled) {
                        Glass(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(profileLabel(bot.profile), style = MaterialTheme.typography.titleLarge)
                                    Text("Profile settings", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = onBack, enabled = !state.saving && !state.editing) { Text("All profiles") }
                            }
                        }
                    }
                }
                item {
                    ProfileEntrance(1, motionEnabled) {
                        ProfileCard(Icons.Rounded.Flag, "Purpose") {
                            SelectionContainer { Text(bot.description.ifBlank { "No purpose set." }) }
                        }
                    }
                }
                item {
                    ProfileEntrance(2, motionEnabled) {
                        ProfileCard(Icons.Rounded.Memory, "Default model") {
                            Text("${bot.provider.ifBlank { "Provider not set" }} · ${bot.model.ifBlank { "Model not set" }}", style = MaterialTheme.typography.bodyLarge)
                            Text("Used for new chats in ${profileLabel(bot.profile)}.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (!state.editing) OutlinedButton(onClick = onEditModel, enabled = !state.loading) { Text("Change default model") }
                        }
                    }
                }
                if (state.editing) {
                    item {
                        ProfileEntrance(3, motionEnabled) {
                            ProfileCard(Icons.Rounded.Tune, "Change default model") {
                                Text("Selected: ${state.draftProvider} · ${state.draftModel}")
                                OutlinedTextField(modelSearch, { modelSearch = it }, label = { Text("Search models or providers") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                            }
                        }
                    }
                    val visible = models.distinct().filter { modelSearch.isBlank() || "${it.first} ${it.second}".contains(modelSearch, ignoreCase = true) }
                    if (visible.isEmpty()) item { Text(if (models.isEmpty()) "No configured model choices are available." else "No models match this search.") }
                    itemsIndexed(visible, key = { _, model -> "${model.first}:${model.second}" }) { _, (provider, model) ->
                        TextButton(onClick = { onModelDraft(provider, model) }, enabled = !state.saving, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                            Column(Modifier.fillMaxWidth()) { Text(model); Text(provider + if (provider == state.draftProvider && model == state.draftModel) " · selected" else "", style = MaterialTheme.typography.bodyMedium) }
                        }
                    }
                    item {
                        ProfileCard(Icons.Rounded.Edit, "Configured model ID") {
                            TextButton(onClick = { customModel = !customModel }, enabled = !state.saving) { Text(if (customModel) "Hide custom model entry" else "Enter a configured model ID") }
                            if (customModel) {
                                OutlinedTextField(state.draftProvider, { onModelDraft(it, state.draftModel) }, label = { Text("Configured provider ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                                OutlinedTextField(state.draftModel, { onModelDraft(state.draftProvider, it) }, label = { Text("Model ID") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !state.saving)
                            }
                            Text("The server checks this profile's configured providers.", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = onSaveModel, enabled = !state.saving && state.draftModel.isNotBlank() && state.draftProvider.isNotBlank() && (state.draftModel != bot.model || state.draftProvider != bot.provider)) { Text("Save default model") }
                            TextButton(onClick = onCancel, enabled = !state.saving) { Text("Cancel edit") }
                        }
                    }
                }
                item {
                    ProfileEntrance(4, motionEnabled) {
                        ProfileCard(Icons.Rounded.Description, "Behavior instructions") {
                            TextButton(onClick = { showBehavior = !showBehavior }) { Text(if (showBehavior) "Hide behavior instructions" else "Read behavior instructions") }
                            if (showBehavior) {
                                renderBehavior(bot.soul.ifBlank { "No behavior instructions are set." })
                                Text("Read-only profile instructions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                item {
                    ProfileEntrance(5, motionEnabled) {
                        ProfileCard(Icons.Rounded.Launch, "Profile tools") {
                            ProfileAction("Skills for this profile", Icons.Rounded.AutoAwesome) { onSettings(bot.profile, "Skills") }
                            ProfileAction("Memory for this profile", Icons.Rounded.Psychology) { onSettings(bot.profile, "Memory") }
                            ProfileAction("Profiles & model", Icons.Rounded.Tune) { onSettings(bot.profile, "Profiles & model") }
                        }
                    }
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
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable private fun ProfileValue(icon: ImageVector, title: String, value: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Column { Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(value, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable private fun ProfileAction(label: String, icon: ImageVector, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Icon(icon, null); Spacer(Modifier.width(10.dp)); Text(label, Modifier.weight(1f)); Icon(Icons.Rounded.ChevronRight, null)
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
