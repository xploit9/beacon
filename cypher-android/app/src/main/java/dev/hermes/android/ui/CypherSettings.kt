package dev.hermes.android.ui

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.*
import kotlinx.coroutines.launch

@Composable internal fun CypherSettings(vm: CypherViewModel) {
    val data by vm.data.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    val value = data["voice-settings"]
    var saving by remember(ui.origin, ui.profile) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val path = "/api/plugins/cypher/mobile/voice-settings"
    LaunchedEffect(ui.origin, ui.owner, ui.profile) { vm.ensureLoaded("voice-settings", path) }
    LazyColumn(contentPadding=PaddingValues(16.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item {
            Text("Voice model", style=MaterialTheme.typography.titleLarge)
            Text("Choose who powers Cypher’s next call. Your current call keeps its model.", style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (value == null) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (value?.has("_error") == true) item {
            Text(value.string("_error"), color=MaterialTheme.colorScheme.error)
            TextButton({ vm.load("voice-settings", path) }, enabled=!saving) { Text("Retry") }
        }
        value?.rows("providers")?.forEach { provider -> item(key=provider.string("id")) {
            val id=provider.string("id")
            val selected=value.string("selected_provider")==id
            val enabled=provider.optBoolean("available")&&!saving&&"voice-settings" !in refreshing&&value.has("_error").not()
            val select: () -> Unit = {
                if (!selected && enabled && !saving) {
                    saving=true
                    scope.launch {
                    try {
                        vm.action(path,json("provider" to id),method="PUT",refresh=false,successNotice="Voice model saved for your next call") { _, _ -> vm.load("voice-settings",path).join() }.join()
                    } finally { saving=false }
                    }
                }
            }
            Glass { ListItem(
                headlineContent={Text(provider.string("label"))},
                supportingContent={Text(if(!provider.optBoolean("available")) "Needs server setup" else if(provider.optBoolean("vision")) "Voice, tools, camera and screen sharing" else "Voice and tools")},
                leadingContent={RadioButton(selected=selected,onClick=null,enabled=enabled)},
                modifier=Modifier.fillMaxWidth().testTag("voice-model-$id").selectable(selected=selected,enabled=enabled,role=Role.RadioButton,onClick=select),
                colors=ListItemDefaults.colors(containerColor=androidx.compose.ui.graphics.Color.Transparent)
            ) }
            fun saveOption(key:String, option:String) {
                if (saving || !enabled) return
                saving=true
                scope.launch {
                    try { vm.action(path,json("provider" to id,key to option),method="PUT",refresh=false,successNotice="Voice settings saved for your next call") { _, _ -> vm.load("voice-settings",path).join() }.join() }
                    finally { saving=false }
                }
            }
            var voiceMenu by remember { mutableStateOf(false) }
            var personalityMenu by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp)) {
                Box {
                    TextButton({voiceMenu=true},enabled=enabled) { Text("Voice: ${provider.string("voice")}") }
                    DropdownMenu(expanded=voiceMenu,onDismissRequest={voiceMenu=false}) {
                        val voices=provider.optJSONArray("voices")
                        for(index in 0 until (voices?.length()?:0)) {
                            val voice=voices!!.getString(index)
                            DropdownMenuItem(text={Text(voice)},onClick={voiceMenu=false;saveOption("voice",voice)})
                        }
                    }
                }
                Box {
                    TextButton({personalityMenu=true},enabled=enabled) { Text("Personality: ${provider.string("personality").replaceFirstChar { it.uppercase() }}") }
                    DropdownMenu(expanded=personalityMenu,onDismissRequest={personalityMenu=false}) {
                        provider.rows("personalities").forEach { personality ->
                            DropdownMenuItem(text={Text(personality.string("label"))},onClick={personalityMenu=false;saveOption("personality",personality.string("id"))})
                        }
                    }
                }
            }
        } }
        item { Text("Voice and personality apply to your next call. Changing either selects its model.", style=MaterialTheme.typography.bodySmall) }
        item { Text("Both models use your connected tools and approval controls. During a Grok call, Camera or Share screen offers to switch this call to Gemini.", style=MaterialTheme.typography.bodyMedium, color=MaterialTheme.colorScheme.onSurfaceVariant) }
        if(saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    }
}
