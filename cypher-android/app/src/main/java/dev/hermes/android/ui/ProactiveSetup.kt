package dev.hermes.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.hermes.android.CypherViewModel
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.CypherClient
import dev.hermes.android.isMissingCypherPluginRoute
import org.json.JSONObject
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val proactiveTimePattern = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
private val proactiveHealthTime = DateTimeFormatter.ofPattern("HH:mm")

internal fun proactiveSettingsBody(
    enabled: Boolean,
    delivery: String,
    checkMinutes: Int,
    quietStart: String,
    quietEnd: String,
    preferences: AppPreferences,
): JSONObject = json(
    "enabled" to enabled,
    "delivery" to delivery,
    "check_minutes" to checkMinutes,
    "quiet_start" to quietStart,
    "quiet_end" to quietEnd,
).apply {
    require(proactiveSettingsValid(this)) { "Choose a valid delivery, check interval, and quiet-hours time." }
    if (preferences.assistantModel.isNotBlank()) put("model", preferences.assistantModel)
    if (preferences.assistantProvider.isNotBlank()) put("provider", preferences.assistantProvider)
}

internal fun proactiveSettingsValid(settings: JSONObject): Boolean =
    settings.string("delivery") in setOf("notify", "call") &&
        settings.optInt("check_minutes", -1) in setOf(5, 15, 30) &&
        proactiveTimePattern.matches(settings.optString("quiet_start")) &&
        proactiveTimePattern.matches(settings.optString("quiet_end"))

internal fun proactiveHealthText(proactive: JSONObject): String {
    val health = proactive.optJSONObject("health") ?: JSONObject()
    return when (health.string("state", "unknown")) {
        "ok" -> {
            val checkedAt = health.optLong("checked_at")
            if (checkedAt <= 0) "Checked"
            else {
                val timezone = runCatching { ZoneId.of(proactive.string("timezone")) }.getOrDefault(ZoneId.systemDefault())
                "Checked ${Instant.ofEpochSecond(checkedAt).atZone(timezone).format(proactiveHealthTime)}"
            }
        }
        "error" -> "Couldn't read your email: ${health.string("message").ifBlank { "Try again." }}"
        else -> "Not checked yet"
    }
}

internal fun proactiveErrorMessage(error: Throwable, fallback: String): String =
    if (isMissingCypherPluginRoute(error)) "Update the Cypher plugin on Hermes to use Proactive."
    else (error as? ApiFailure)?.message?.takeIf(String::isNotBlank) ?: error.message?.takeIf(String::isNotBlank) ?: fallback

internal fun proactiveLearnedNotes(proactive: JSONObject): List<String> {
    val notes = proactive.optJSONArray("learned") ?: return emptyList()
    return List(notes.length()) { notes.optString(it) }.filter(String::isNotBlank)
}

internal suspend fun saveProactiveSettings(vm: CypherViewModel, profile: String, body: JSONObject): JSONObject {
    require(proactiveSettingsValid(body)) { "Choose a valid delivery, check interval, and quiet-hours time." }
    val source = vm.client ?: throw IllegalStateException("Connect to Hermes to manage Proactive.")
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.call(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile), method = "PUT", data = body)
    } finally { client.closeNative() }
}

internal suspend fun fetchProactiveSettings(source: CypherClient, profile: String): JSONObject {
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.get(PROACTIVE_SETTINGS_PATH, mapOf("profile" to profile))
    } finally { client.closeNative() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun ProactiveTimePicker(
    label: String,
    value: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
) {
    var open by remember(label, value) { mutableStateOf(false) }
    val parts = value.split(':')
    val state = key(value) {
        rememberTimePickerState(
            initialHour = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it in 0..23 } ?: 22,
            initialMinute = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..59 } ?: 0,
            is24Hour = true,
        )
    }
    OutlinedButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.fillMaxWidth().testTag("proactive-time-$label")) {
        Text("$label: $value")
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(label) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = {
                onChange("%02d:%02d".format(state.hour, state.minute))
                open = false
            }, modifier = Modifier.testTag("proactive-time-confirm-$label")) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
    )
}

internal fun openProactiveRuleForm(vm: CypherViewModel, profile: String, onCreated: () -> Unit) {
    vm.openForm(ActionForm(
        "Add a rule", "", listOf(
            Field("text", "Your rule", FieldType.MULTILINE, required = true),
            Field("action", "When it happens", FieldType.CHOICE, options = listOf("notify", "call"), optionLabels = mapOf("notify" to "Notify", "call" to "Call")),
            Field("night", "Even at night", FieldType.BOOLEAN),
        ),
        seed = json("action" to "notify", "night" to false),
        explanation = "Describe which emails Cypher should notify or call you about.",
        actionLabel = "Add rule",
        onResult = { form ->
            val text = form.string("text").trim()
            require(text.isNotBlank() && text.codePointCount(0, text.length) <= 300) { "Add a rule of 300 characters or fewer." }
            val source = vm.client ?: throw IllegalStateException("Reconnect before adding a rule.")
            val client = source.forAuthorizedProfile(profile)
            try {
                try {
                    client.call("$PROACTIVE_SETTINGS_PATH/requests", mapOf("profile" to profile), method = "POST", data = json(
                        "text" to text, "action" to form.string("action"), "night" to form.optBoolean("night"), "signal" to "email",
                    ))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    throw IllegalStateException(proactiveErrorMessage(error, "Couldn't add the rule. Try again."))
                }
                vm.notice("Rule added.")
                onCreated()
            } finally { client.closeNative() }
        },
    ))
}
