package dev.hermes.android.ui

import android.content.ClipboardManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.VoicePreferences
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.ownerNameOf
import dev.hermes.android.voice.GeminiWire
import dev.hermes.android.voice.GrokWire
import dev.hermes.android.voice.OpenAiWire
import dev.hermes.android.voice.VoiceProviderRequest
import dev.hermes.android.voice.VoiceProviderTestResult
import dev.hermes.android.voice.testVoiceProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// The three Voice & calls pages (Provider, Key, Make it yours). The wizard shows them in the shared shell and
// Settings > Voice & calls shows the very same pages. Row-in-a-rounded-container lists are adapted from Hermes Relay's
// ConnectionWizard chooser (MIT); the radio lists follow Android's selectableGroup pattern.

internal data class VoiceCatalog(val models: List<String> = emptyList(), val voices: List<String> = emptyList())

internal class VoiceProviderInfo(val id: String, val name: String, val short: String, val note: String, val keyUrl: String)

internal val voiceProviders = listOf(
    VoiceProviderInfo("gemini", "Gemini Live", "Gemini", "Voice, camera and screen sharing", "https://aistudio.google.com/apikey"),
    VoiceProviderInfo("grok", "Grok", "Grok", "Voice calls", "https://console.x.ai/team/default/api-keys"),
    VoiceProviderInfo("openai", "OpenAI Realtime", "OpenAI", "Voice calls", "https://platform.openai.com/api-keys"),
)

internal fun voiceProviderInfo(provider: String): VoiceProviderInfo = voiceProviders.firstOrNull { it.id == provider } ?: voiceProviders.first()

/** The catalog read for each provider, kept in memory so the choices open already filled and refresh behind it. */
internal object VoiceCatalogCache {
    private val catalogs = ConcurrentHashMap<String, VoiceCatalog>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The provider read; tests replace it to stay offline. */
    @Volatile var loader: suspend (String, String) -> VoiceCatalog = ::loadVoiceCatalog

    fun get(provider: String): VoiceCatalog? = catalogs[provider]
    fun clear(provider: String) { catalogs.remove(provider) }

    /** Null when the read failed; the cached answer, if any, stays. */
    suspend fun refresh(provider: String, apiKey: String): VoiceCatalog? {
        val catalog = try { loader(provider, apiKey) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { return null }
        if (catalog.models.isEmpty() && catalog.voices.isEmpty()) return null
        catalogs[provider] = catalog
        return catalog
    }

    fun prefetch(provider: String, apiKey: String) {
        if (inFlight.add(provider)) background.launch { try { refresh(provider, apiKey) } finally { inFlight.remove(provider) } }
    }
}

internal fun defaultVoiceChoices(provider: String): List<String> = when (provider) {
    "grok" -> listOf("eve", "ara", "rex", "sal", "leo")
    "openai" -> OpenAiWire.VOICES
    else -> listOf("Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr")
}

internal suspend fun loadVoiceCatalog(provider: String, apiKey: String): VoiceCatalog = withContext(Dispatchers.IO) {
    val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).followRedirects(false).followSslRedirects(false)
        .callTimeout(15, TimeUnit.SECONDS).build()
    fun get(request: VoiceProviderRequest): JSONObject? = try {
        client.newCall(request.rawRequest()).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val body = response.body ?: return@use null
            if (body.contentLength() > 1_000_000) return@use null
            JSONObject(body.string())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
    try {
        when (provider) {
            "gemini" -> VoiceCatalog(
                GeminiWire.models(get(GeminiWire.modelRequest(apiKey)) ?: JSONObject()),
                GeminiWire.voices(get(GeminiWire.voiceRequest(apiKey)) ?: JSONObject()),
            )
            "grok" -> VoiceCatalog(
                GrokWire.models(get(GrokWire.modelRequest(apiKey)) ?: JSONObject()),
                GrokWire.voices(get(GrokWire.voiceRequest(apiKey)) ?: JSONObject()),
            )
            "openai" -> VoiceCatalog(
                OpenAiWire.models(get(OpenAiWire.modelRequest(apiKey)) ?: JSONObject()),
                OpenAiWire.VOICES,
            )
            else -> VoiceCatalog()
        }
    } finally {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

// One word per voice from the providers' own documentation; a voice outside these lists shows its name alone.
private val voiceStyles: Map<String, Map<String, String>> = mapOf(
    "gemini" to mapOf(
        "Zephyr" to "Bright", "Puck" to "Upbeat", "Charon" to "Informative", "Kore" to "Firm", "Fenrir" to "Excitable",
        "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy", "Callirrhoe" to "Easy-going", "Autonoe" to "Bright",
        "Enceladus" to "Breathy", "Iapetus" to "Clear", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth",
        "Erinome" to "Clear", "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
        "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively", "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
    ),
    "grok" to mapOf("eve" to "Energetic", "ara" to "Warm and friendly", "rex" to "Confident and clear", "sal" to "Smooth", "leo" to "Authoritative"),
    "openai" to mapOf("marin" to "Best quality", "cedar" to "Best quality"),
).mapValues { (_, styles) -> styles.mapKeys { it.key.lowercase() } }

internal fun voiceStyle(provider: String, voice: String): String = voiceStyles[provider]?.get(voice.lowercase()).orEmpty()

internal fun voiceModelNote(provider: String, model: String): String = when {
    model == defaultVoiceModel(provider) -> "Default"
    model.endsWith("extended-thinking") -> "Thinks longer before answering"
    model.endsWith("gemini-3.1-flash-live-preview") || model.endsWith("grok-voice-2.0") -> "Older"
    model.endsWith("grok-voice-latest") -> "Newest, moves with updates"
    else -> ""
}

internal val voicePersonalities = listOf(
    Triple("balanced", "Balanced", "Natural, everyday tone"),
    Triple("concise", "Concise", "Short, direct answers"),
    Triple("warm", "Warm", "Friendly and patient"),
)

internal fun voiceKeyResultMessage(provider: String, result: VoiceProviderTestResult): String {
    val name = voiceProviderInfo(provider).short
    return when (result) {
        VoiceProviderTestResult.Success -> "Your $name key works."
        VoiceProviderTestResult.KeyRejected -> "$name didn't accept this key. Check it and try again."
        VoiceProviderTestResult.Unreachable -> "Couldn't reach $name. Check your connection and try again."
        VoiceProviderTestResult.ProviderError -> "$name returned an error. Try again in a moment."
    }
}

/** The rows shown before "More": the first [limit], plus the selected one wherever it sits in the stable order. */
internal fun <T> visibleOptions(all: List<T>, selected: T, expanded: Boolean, limit: Int): List<T> =
    if (expanded) all else all.filterIndexed { index, option -> index < limit || option == selected }

private const val VOICE_PAGE_DISCLOSURE = "During a call, Beacon sends your assistant's instructions, memory notes, recent chat, tool results and any camera or screen you share to the voice provider you choose."

@Composable
private fun KeySavedBadge() {
    Surface(shape = RoundedCornerShape(100.dp), color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp))
            Text("Key saved", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** Page 1: pick the provider. Nothing is written until Continue, so backing out never leaves a provider without a key. */
@Composable
internal fun voiceProviderPage(
    onContinue: (provider: String, hasKey: Boolean) -> Unit,
    onSkip: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
): SetupPage {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VoiceKeyStore(context) }
    var selected by remember { mutableStateOf(store.settings().provider) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { voiceProviders.map { it.id }.filter(store::hasKey).toSet() } }

    fun proceed() {
        busy = true
        status = ""
        scope.launch {
            val provider = selected
            val hasKey = withContext(Dispatchers.IO) { store.hasKey(provider) }
            if (hasKey && runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }.isFailure) {
                status = "Couldn't save that choice. Try again."
                busy = false
                return@launch
            }
            busy = false
            onContinue(provider, hasKey)
        }
    }
    return SetupPage(
        content = {
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                voiceProviders.forEach { info ->
                    val isSelected = selected == info.id
                    Surface(
                        Modifier.fillMaxWidth().heightIn(min = 72.dp).testTag("voice-model-${info.id}")
                            .selectable(selected = isSelected, enabled = !busy, role = Role.RadioButton, onClick = { selected = info.id; status = "" }),
                        shape = MaterialTheme.shapes.medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = .35f) else MaterialTheme.colorScheme.surfaceContainer,
                        border = BorderStroke(if (isSelected) 2.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            RadioButton(isSelected, onClick = null)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(info.name, style = MaterialTheme.typography.titleMedium)
                                Text(info.note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (info.id in saved) KeySavedBadge()
                            }
                        }
                    }
                }
            }
            SetupStatusLine(status, error = true)
        },
        actions = {
            SetupPrimaryButton("Continue", ::proceed, Modifier.testTag("setup-voice-continue"), enabled = !busy)
            onSkip?.let { SetupTextAction("Skip for now", it, Modifier.testTag("setup-voice-skip")) }
            onBack?.let { SetupTextAction("Back", it, Modifier.testTag("voice-page-back")) }
            onClose?.let { SetupTextAction("Close setup", it) }
        },
    )
}

/**
 * Page 2: paste the key and prove it works. A first key is never lost (it is saved when the page is left), a replacement
 * is saved only by Test key and a rejected one is undone, and the field keeps its text so a typo can be fixed in place.
 */
@Composable
internal fun voiceKeyPage(
    provider: String,
    onKeySaved: () -> Unit,
    onVerified: () -> Unit,
    onContinueAnyway: () -> Unit,
    onBack: () -> Unit,
    onSkip: (() -> Unit)? = null,
    removable: Boolean = false,
    onRemoved: () -> Unit = {},
): SetupPage {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VoiceKeyStore(context) }
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current
    val info = voiceProviderInfo(provider)
    var draft by remember(provider) { mutableStateOf("") }
    var visible by remember(provider) { mutableStateOf(false) }
    var saved by remember(provider) { mutableStateOf<String?>(null) }
    var testing by remember(provider) { mutableStateOf(false) }
    var rejected by remember(provider) { mutableStateOf(false) }
    var retry by remember(provider) { mutableStateOf(false) }
    var status by remember(provider) { mutableStateOf("") }
    var statusIsError by remember(provider) { mutableStateOf(false) }
    var confirmRemove by remember(provider) { mutableStateOf(false) }
    LaunchedEffect(provider) { saved = withContext(Dispatchers.IO) { store.maskedKey(provider) } }
    // A first key typed here is saved (encrypted) when the page is left, unless the provider just refused it. A saved key is only replaced by Test key, so a stray paste cannot overwrite a working one.
    DisposableEffect(provider) { onDispose { if (saved == null && draft.isNotBlank() && !rejected) runCatching { store.saveKey(provider, draft) } } }
    val canTest = !testing && (draft.isNotEmpty() || saved != null)

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        draft = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().filterNot(Char::isWhitespace).take(8192)
        status = ""; rejected = false; retry = false
    }

    fun test() {
        val entered = draft
        testing = true; rejected = false; retry = false; statusIsError = false; status = "Checking…"
        scope.launch {
            val previous = withContext(Dispatchers.IO) { store.key(provider) }
            val apiKey = entered.ifEmpty { previous.orEmpty() }
            if (apiKey.isBlank()) { status = "Enter an API key first."; statusIsError = true; testing = false; return@launch }
            if (entered.isNotEmpty() && runCatchingCancellable { withContext(Dispatchers.IO) { store.saveKey(provider, entered) } }.isFailure) {
                status = "That key couldn't be saved. Try again."; statusIsError = true; testing = false; return@launch
            }
            val outcome = testVoiceProvider(provider, apiKey, store.settings(provider).model)
            when (outcome) {
                VoiceProviderTestResult.Success -> {
                    val chosen = runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }.isSuccess
                    draft = ""
                    saved = withContext(Dispatchers.IO) { store.maskedKey(provider) }
                    VoiceCatalogCache.clear(provider)
                    VoiceCatalogCache.prefetch(provider, apiKey)
                    if (chosen) { status = ""; onKeySaved(); onVerified() }
                    else { status = "Couldn't save that choice. Try again."; statusIsError = true }
                }
                VoiceProviderTestResult.KeyRejected -> {
                    // Undo the replacement: the refused key must never show up as the saved one.
                    if (entered.isNotEmpty()) withContext(Dispatchers.IO) {
                        runCatching { if (previous != null) store.saveKey(provider, previous) else store.removeKey(provider) }
                    }
                    saved = withContext(Dispatchers.IO) { store.maskedKey(provider) }
                    rejected = true
                    status = voiceKeyResultMessage(provider, outcome); statusIsError = true
                }
                else -> {
                    // The key could not be judged; it stays saved, the provider choice is kept, and the user may carry on.
                    runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }
                    if (entered.isNotEmpty()) { draft = ""; saved = withContext(Dispatchers.IO) { store.maskedKey(provider) } }
                    retry = true
                    status = voiceKeyResultMessage(provider, outcome); statusIsError = true
                }
            }
            testing = false
        }
    }

    return SetupPage(
        content = {
            TextButton(onClick = { uriHandler.openUri(info.keyUrl) }, Modifier.heightIn(min = 48.dp).testTag("voice-key-get")) {
                Text("Get a key")
                Spacer(Modifier.size(8.dp))
                Icon(Icons.Rounded.OpenInNew, null, Modifier.size(18.dp))
            }
            SetupContainer {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Rounded.Info, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(VOICE_PAGE_DISCLOSURE, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it.filterNot(Char::isWhitespace).take(8192); status = ""; rejected = false; retry = false },
                modifier = Modifier.fillMaxWidth().testTag("voice-provider-key"),
                label = { Text("${info.short} API key") },
                supportingText = { saved?.let { Text("Saved $it. Paste a new key to replace it.") } },
                isError = rejected,
                enabled = !testing,
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canTest) test() }),
                trailingIcon = {
                    IconButton(onClick = { if (draft.isEmpty()) paste() else visible = !visible }, enabled = !testing) {
                        Icon(
                            if (draft.isEmpty()) Icons.Rounded.ContentPaste else if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                            if (draft.isEmpty()) "Paste key" else if (visible) "Hide key" else "Show key",
                        )
                    }
                },
            )
            SetupStatusLine(status, statusIsError)
            if (removable && saved != null) TextButton(
                onClick = { confirmRemove = true }, enabled = !testing,
                modifier = Modifier.heightIn(min = 48.dp).testTag("voice-key-remove"),
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Remove key") }
            if (confirmRemove) AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text("Remove the ${info.short} key from this phone?") },
                text = { Text("Calls with ${info.short} stop until you add a key.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemove = false
                        scope.launch {
                            if (runCatchingCancellable { withContext(Dispatchers.IO) { store.removeKey(provider) } }.isSuccess) {
                                VoiceCatalogCache.clear(provider)
                                saved = null
                                onRemoved()
                            } else { status = "The key could not be removed."; statusIsError = true }
                        }
                    }, Modifier.testTag("voice-key-remove-confirm")) { Text("Remove key") }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
            )
        },
        actions = {
            SetupPrimaryButton(if (retry) "Try again" else "Test key", ::test, Modifier.testTag("voice-key-test"), enabled = canTest, loading = testing)
            if (retry) SetupTextAction("Continue anyway", onContinueAnyway, Modifier.testTag("voice-key-continue-anyway"), enabled = !testing)
            SetupTextAction("Back", onBack, Modifier.testTag("voice-page-back"), enabled = !testing)
            if (!retry) onSkip?.let { SetupTextAction("Skip for now", it, Modifier.testTag("setup-voice-skip"), enabled = !testing) }
        },
    )
}

@Composable
private fun VoiceOptionGroup(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.selectableGroup()) { content() }
        }
    }
}

@Composable
private fun VoiceOptionRow(label: String, note: String, selected: Boolean, tag: String, divider: Boolean, onSelect: () -> Unit) {
    Column {
        if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RadioButton(selected, onClick = null)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun VoiceMoreRow(text: String, tag: String, onClick: () -> Unit) {
    Column {
        HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag).clickable(role = Role.Button, onClick = onClick)
                .semantics { stateDescription = "Collapsed" }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Page 3: Voice, Personality and Model as real lists with the current choice marked. A tap saves at once and the lists never
 * reorder (the model chosen when the page opens is listed first). In Settings ([settingsMode]) it has no buttons of its own, a provider row with Change, and "Add key" when none is saved.
 */
@Composable
internal fun voiceChoicesPage(
    settingsMode: Boolean,
    keyWorks: Boolean = false,
    callActive: Boolean = false,
    ownerName: String = "",
    onOwnerNameChange: ((String) -> Unit)? = null,
    onContinue: () -> Unit = {},
    onBack: () -> Unit = {},
    onChange: () -> Unit = {},
    onAddKey: () -> Unit = {},
): SetupPage {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { VoiceKeyStore(context) }
    val scope = rememberCoroutineScope()
    var selection by remember { mutableStateOf(store.settings()) }
    val provider = selection.provider
    val openingModel = remember(provider) { selection.model }
    var hasKey by remember(provider) { mutableStateOf<Boolean?>(null) }
    var catalog by remember(provider) { mutableStateOf(VoiceCatalogCache.get(provider)) }
    var moreVoices by remember(provider) { mutableStateOf(false) }
    var moreModels by remember(provider) { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var ownerDraft by remember(ownerName) { mutableStateOf(ownerName) }
    LaunchedEffect(provider) {
        val key = withContext(Dispatchers.IO) { store.key(provider) }
        hasKey = key != null
        // A key removed elsewhere sends the wizard back to the provider page; Settings shows "Add key" instead.
        if (key == null) { if (!settingsMode) onBack(); return@LaunchedEffect }
        VoiceCatalogCache.refresh(provider, key)?.let { catalog = it }
    }

    fun pick(next: VoicePreferences) {
        val previous = selection
        selection = next
        status = ""
        scope.launch {
            if (runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(next) } }.isFailure) {
                selection = previous
                status = "Couldn't save that choice. Try again."
            }
        }
    }

    val info = voiceProviderInfo(provider)
    return SetupPage(
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasKey == true) Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Text(
                    info.name + " · " + when { hasKey == false -> "No key"; keyWorks -> "Key works"; else -> "Key saved" },
                    Modifier.weight(1f).testTag("voice-provider-summary"), style = MaterialTheme.typography.bodyMedium,
                )
                if (settingsMode) TextButton(onClick = onChange, Modifier.heightIn(min = 48.dp).testTag("voice-provider-change")) { Text("Change") }
            }
            if (settingsMode && hasKey == false) SetupPrimaryButton("Add key", onAddKey, Modifier.testTag("voice-add-key"))
            // The lists render at once from the built-in choices; only a confirmed missing key hides them (the key read can be slow).
            if (hasKey != false) {
                if (settingsMode && callActive) Text("Changes apply to your next call.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // One row per voice whatever its capitalisation; a saved "aoede" selects the "Aoede" row.
                val voices = (defaultVoiceChoices(provider) + catalog?.voices.orEmpty() + selection.voice).distinctBy { it.lowercase() }
                val selectedVoice = voices.firstOrNull { it.equals(selection.voice, ignoreCase = true) } ?: selection.voice
                val shownVoices = visibleOptions(voices, selectedVoice, moreVoices, 6)
                VoiceOptionGroup("Voice") {
                    shownVoices.forEachIndexed { index, voice ->
                        VoiceOptionRow(voiceChoiceLabel(voice, voices), voiceStyle(provider, voice), voice == selectedVoice, "voice-option-voice-$voice", index > 0) { pick(selection.copy(voice = voice)) }
                    }
                    if (shownVoices.size < voices.size) VoiceMoreRow("More voices (${voices.size - shownVoices.size})", "voice-more-voices") { moreVoices = true }
                }
                VoiceOptionGroup("Personality") {
                    voicePersonalities.forEachIndexed { index, (id, label, note) ->
                        VoiceOptionRow(label, note, id == selection.personality, "voice-option-personality-$id", index > 0) { pick(selection.copy(personality = id)) }
                    }
                }
                val models = (listOf(openingModel) + catalog?.models.orEmpty().ifEmpty { listOf(defaultVoiceModel(provider)) } + selection.model).distinct()
                if (models.size > 1) {
                    val shownModels = visibleOptions(models, selection.model, moreModels, 4)
                    VoiceOptionGroup("Model") {
                        shownModels.forEachIndexed { index, model ->
                            VoiceOptionRow(voiceModelDisplayName(model), voiceModelNote(provider, model), model == selection.model, "voice-option-model-$model", index > 0) { pick(selection.copy(model = model)) }
                        }
                        if (shownModels.size < models.size) VoiceMoreRow("More models (${models.size - shownModels.size})", "voice-more-models") { moreModels = true }
                    }
                }
                if (onOwnerNameChange != null) OutlinedTextField(
                    ownerDraft, { ownerDraft = ownerNameOf(it); onOwnerNameChange(ownerDraft) },
                    label = { Text("What should Beacon call you?") }, supportingText = { Text("Optional") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("setup-owner-name"),
                )
                if (models.size <= 1) Text("Model: " + voiceModelDisplayName(selection.model), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SetupStatusLine(status, error = true)
            }
        },
        actions = {
            if (!settingsMode) {
                SetupPrimaryButton("Continue", onContinue, Modifier.testTag("setup-voice-customize-continue"))
                SetupTextAction("Back", onBack, Modifier.testTag("voice-page-back"))
            }
        },
    )
}
