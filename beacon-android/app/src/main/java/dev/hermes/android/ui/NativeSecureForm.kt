package dev.hermes.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.SecurePrompt
import dev.hermes.android.phone.deviceLockedNow
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class SecureField(val key: String, val label: String, val required: Boolean = true,
    val masked: Boolean = true, val initial: String = "", val choices: List<String> = emptyList())

/** The protected dialog owns its input; neither the ViewModel nor saved state receives a draft. */
@Composable internal fun NativeSecureForm(
    identity: String, title: String, description: String, fields: List<SecureField>,
    expiresAt: Long = Long.MAX_VALUE, validate: (JSONObject) -> String? = { null }, onCancel: () -> Unit, onSubmit: (JSONObject) -> Unit,
) {
    require(fields.none { it.masked && it.initial.isNotEmpty() })
    val context = LocalContext.current
    val locked = deviceLockedNow(context)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cancel by rememberUpdatedState(onCancel)
    val submit by rememberUpdatedState(onSubmit)
    val values = remember(identity) { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.initial) } } }
    var settled by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf("") }
    fun close() {
        if (!settled) { settled = true; values.clear(); cancel() }
    }
    DisposableEffect(identity, lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) close() }
        val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { close() } }
        lifecycle.addObserver(observer)
        val registered = runCatching { ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        onDispose { lifecycle.removeObserver(observer); if (registered) runCatching { context.unregisterReceiver(screenOff) }; close(); values.clear() }
    }
    LaunchedEffect(identity, expiresAt) {
        if (locked) close()
        if (expiresAt != Long.MAX_VALUE) { delay((expiresAt - System.nanoTime() / 1_000_000).coerceAtLeast(0)); close() }
    }
    if (!settled && !locked) AlertDialog(
        onDismissRequest = ::close,
        properties = DialogProperties(dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text(uiText(title)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (description.isNotBlank()) Text(description, style = MaterialTheme.typography.bodyMedium)
                if (error.isNotBlank()) Text(uiText(error), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                fields.forEach { field ->
                    if (field.choices.isNotEmpty()) {
                        Text(uiText(field.label), style = MaterialTheme.typography.labelLarge)
                        Column {
                            field.choices.forEach { choice -> FilterChip(selected = values[field.key] == choice, onClick = { values[field.key] = choice },
                                label = { Text(uiText(choice.replaceFirstChar(Char::uppercase))) }) }
                        }
                    } else OutlinedTextField(
                        value = values[field.key].orEmpty(), onValueChange = {
                            val limit = when (field.key) { "label", "name" -> 120; "identifier", "email" -> 320; "origin" -> 2048; else -> 4096 }
                            values[field.key] = it.take(limit)
                        },
                        modifier = Modifier.fillMaxWidth().testTag("vault-field:${field.key}"), singleLine = true,
                        label = { Text(uiText(field.label)) },
                        visualTransformation = if (field.masked) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = KeyboardOptions(keyboardType = if (field.masked) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (deviceLockedNow(context) || System.nanoTime() / 1_000_000 >= expiresAt) close()
                else {
                    val answer = JSONObject()
                    fields.forEach { field -> values[field.key]?.takeIf(String::isNotBlank)?.let { answer.put(field.key, it) } }
                    val problem = validate(answer)
                    if (problem != null) { error = problem; answer.keys().asSequence().toList().forEach(answer::remove) }
                    else { settled = true; values.clear(); submit(answer) }
                }
            }, enabled = fields.all { !it.required || values[it.key].orEmpty().isNotBlank() }, modifier = Modifier.testTag("vault-submit")) { Text(uiText("Continue")) }
        },
        dismissButton = { TextButton(onClick = ::close) { Text(uiText("Cancel")) } },
    )
}

@Composable internal fun NativeServerSecurePrompt(vm: BeaconViewModel, prompt: SecurePrompt) {
    val fields = when (prompt.method) {
        "vault.save_login" -> listOf(SecureField("identifier", "Identifier", masked = false), SecureField("password", "Password"))
        "vault.code" -> listOf(SecureField("value", "Verification code"))
        "vault.unlock_prompt" -> listOf(SecureField("value", "Password"))
        else -> listOf(SecureField("value", "Secret"))
    }
    val title = when (prompt.method) {
        "vault.save_login" -> "Save login"
        "vault.code" -> "Verification code"
        "vault.unlock_prompt" -> "Unlock Vault"
        else -> "Secure input"
    }
    NativeSecureForm(prompt.connection + ":" + prompt.id, title, prompt.detail, fields, expiresAt = prompt.expiresAt,
        onCancel = { vm.dismissSecurePrompt(prompt) }, onSubmit = { input ->
            var payload: JSONObject? = null
            val sent = try {
                val client = vm.securePromptClient(prompt)
                if (client == null) false else {
                    val value = if (prompt.method == "vault.save_login") json("identifier" to input.getString("identifier"), "password" to input.getString("password")).toString()
                        else input.getString("value")
                    payload = json("value" to value, "secure_connection" to prompt.connection)
                    client.nativeAnswerServerRequest(prompt.method, prompt.id, prompt.runtime, payload!!).optBoolean("ok")
                }
            } catch (_: Exception) { false }
            finally { input.keys().asSequence().toList().forEach(input::remove); payload?.remove("value") }
            vm.securePromptFinished(prompt, sent)
        })
}
