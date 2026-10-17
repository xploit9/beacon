package dev.hermes.android.ui

import androidx.compose.runtime.*
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.Scope
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal val LocalChecklist = compositionLocalOf { ChecklistReading(emptyMap(), {}, false) }
internal data class ChecklistReading(val values: Map<String,Boolean>, val toggle: (Pair<String,Boolean>) -> Unit, val ready: Boolean)
@Composable internal fun checklistReading(vm: CypherViewModel, scope: Scope?, documentKey: String, source: String, interactive: Boolean): ChecklistReading {
    val identity = scope?.key.orEmpty() + ":" + documentKey.ifBlank { if (interactive) source.hashCode().toString() else "stream" }
    var values by remember(identity) { mutableStateOf<Map<String,Boolean>>(emptyMap()) }
    var ready by remember(identity) { mutableStateOf(false) }
    val persist = scope != null && scope.session.isNotBlank() && documentKey.isNotBlank()
    val kind = "reading-checklist:$documentKey"
    LaunchedEffect(identity) {
        if(persist) runCatching { vm.app.cache.read(scope!!, kind)?.let(::JSONObject)?.let { json -> values=json.keys().asSequence().associateWith { json.optBoolean(it) } } }.onFailure { if(it is CancellationException) throw it; vm.notice("Checklist reading state could not be restored") }
        ready=true
    }
    return ChecklistReading(values, { (key,checked) ->
        if (interactive) {
            val next=values + (key to checked); values=next
            if(persist) vm.saveChecklistReading(scope!!, documentKey, next)
        }
    }, ready && interactive)
}
