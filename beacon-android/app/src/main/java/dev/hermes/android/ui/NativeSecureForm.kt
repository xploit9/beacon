package dev.hermes.android.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.SecurePrompt
import dev.hermes.android.data.securePromptQuestionKey
import dev.hermes.android.phone.deviceLockedNow
import kotlinx.coroutines.delay
import org.json.JSONObject

internal data class SecureField(val key: String, val label: String, val required: Boolean = true,
    val masked: Boolean = true, val initial: String = "", val choices: List<String> = emptyList())

/** Shown inside the sheet when Continue could not hand the answer over: the sheet stays open with everything that was typed. */
internal const val SECURE_SEND_FAILED_LINE = "Couldn't confirm it was sent."

/**
 * The protected dialog owns its input; neither the ViewModel nor saved state receives a draft.
 *
 * What the person typed is only ever gone because they chose it: Continue (sent), Cancel, or the system closing the sheet (app left, screen
 * off, phone locked). Back only hides the keyboard, and a tap outside does nothing, so neither can answer Hermes or throw the input away. The
 * sheet sits above the on-screen keyboard (the window is inset by it), so Continue is never under a key.
 */
@Composable internal fun NativeSecureForm(
    identity: String, title: String, description: String, fields: List<SecureField>,
    expiresAt: Long = Long.MAX_VALUE, validate: (JSONObject) -> String? = { null }, onCancel: () -> Unit, onSubmit: (JSONObject) -> Unit,
    /** The system closed the sheet (app paused, screen off, phone locked). Defaults to a cancel; a question Hermes still holds open keeps waiting instead. */
    onSuspend: (() -> Unit)? = null,
    /**
     * Hands the answer over and says whether it was taken. When set it replaces [onSubmit]: a refused answer leaves the sheet open with what was
     * typed (the connection can be gone for a moment), and only an accepted one closes it. The callee must not keep the JSON.
     */
    onSend: ((JSONObject) -> Boolean)? = null,
) {
    require(fields.none { it.masked && it.initial.isNotEmpty() })
    val context = LocalContext.current
    val locked = deviceLockedNow(context)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val cancel by rememberUpdatedState(onCancel)
    val suspend by rememberUpdatedState(onSuspend ?: onCancel)
    val submit by rememberUpdatedState(onSubmit)
    val send by rememberUpdatedState(onSend)
    val values = remember(identity) { mutableStateMapOf<String, String>().apply { fields.forEach { put(it.key, it.initial) } } }
    // Which secret fields the person chose to show. Nothing is remembered: every open starts hidden and it is cleared whenever the values are.
    val shown = remember(identity) { mutableStateListOf<String>() }
    var settled by remember(identity) { mutableStateOf(false) }
    var error by remember(identity) { mutableStateOf("") }
    fun close(byPerson: Boolean = true) {
        if (!settled) { settled = true; values.clear(); shown.clear(); if (byPerson) cancel() else suspend() }
    }
    DisposableEffect(identity, lifecycle, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) close(byPerson = false) }
        val screenOff = object : BroadcastReceiver() { override fun onReceive(context: Context, intent: Intent) { close(byPerson = false) } }
        lifecycle.addObserver(observer)
        val registered = runCatching { ContextCompat.registerReceiver(context, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }.isSuccess
        onDispose { lifecycle.removeObserver(observer); if (registered) runCatching { context.unregisterReceiver(screenOff) }; close(byPerson = false); values.clear(); shown.clear() }
    }
    LaunchedEffect(identity, expiresAt) {
        if (locked) close(byPerson = false)
        if (expiresAt != Long.MAX_VALUE) { delay((expiresAt - System.nanoTime() / 1_000_000).coerceAtLeast(0)); close(byPerson = false) }
    }
    if (!settled && !locked) Dialog(
        // Back (which with the keyboard up only hides it) and a tap outside are not answers.
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, securePolicy = SecureFlagPolicy.SecureOn,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        // The window is not resized for the keyboard on this Android (edge to edge), so the sheet is inset by it: Continue stays above the keys.
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 24.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth(), shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor,
                tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.padding(24.dp)) {
                    Text(uiText(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.weight(1f, fill = false).heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                                visualTransformation = if (field.masked && field.key !in shown) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(keyboardType = if (field.masked) KeyboardType.Password else KeyboardType.Text, autoCorrectEnabled = false),
                                trailingIcon = if (!field.masked) null else ({
                                    val visible = field.key in shown
                                    SettingsIconButton(if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, uiText(if (visible) "Hide {field}" else "Show {field}").replace("{field}", uiText(field.label).let { if (it == field.label) it.lowercase() else it }),
                                        { if (visible) shown.remove(field.key) else shown.add(field.key) }, Modifier.testTag("vault-eye:${field.key}"))
                                }),
                            )
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { close() }) { Text(uiText("Cancel")) }
                        TextButton(onClick = {
                            if (deviceLockedNow(context) || System.nanoTime() / 1_000_000 >= expiresAt) close(byPerson = false)
                            else {
                                val answer = JSONObject()
                                fields.forEach { field -> values[field.key]?.takeIf(String::isNotBlank)?.let { answer.put(field.key, it) } }
                                val problem = validate(answer)
                                val sender = send
                                if (problem != null) { error = problem; answer.keys().asSequence().toList().forEach(answer::remove) }
                                else if (sender != null) {
                                    val taken = try { sender(answer) } catch (_: Exception) { false }
                                    answer.keys().asSequence().toList().forEach(answer::remove)
                                    if (taken) { settled = true; values.clear(); shown.clear() } else error = SECURE_SEND_FAILED_LINE
                                }
                                else { settled = true; values.clear(); shown.clear(); submit(answer) }
                            }
                        }, enabled = fields.all { !it.required || values[it.key].orEmpty().isNotBlank() }, modifier = Modifier.testTag("vault-submit")) { Text(uiText("Continue")) }
                    }
                }
            }
        }
    }
}

@Composable internal fun NativeServerSecurePrompt(vm: BeaconViewModel, prompt: SecurePrompt) {
    val fields = when (prompt.method) {
        "vault.save_login" -> listOf(SecureField("identifier", "Username or email", masked = false), SecureField("password", "Password"))
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
    // The sheet is Hermes' one question, not one connection: when the stream resubscribes Hermes asks the same question again on a new
    // connection, and what was typed stays (the answer goes out on the connection that is current when Continue is pressed).
    NativeSecureForm(securePromptQuestionKey(prompt), title, prompt.detail, fields, expiresAt = prompt.expiresAt,
        onCancel = { vm.dismissSecurePrompt(prompt) }, onSuspend = { vm.holdSecurePrompt(prompt) }, onSubmit = {}, onSend = { input ->
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
            finally { payload?.remove("value") }
            vm.securePromptFinished(prompt, sent)
            sent
        })
}
