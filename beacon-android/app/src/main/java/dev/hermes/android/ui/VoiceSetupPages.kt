package dev.hermes.android.ui

import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Badge
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
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
// Settings > Voice & calls shows the very same pages, built from the Settings kit: option cards for every choice,
// a status hero for the provider and its key, and one Glass pane per section.

internal data class VoiceCatalog(val models: List<String> = emptyList(), val voices: List<String> = emptyList())

internal class VoiceProviderInfo(val id: String, val name: String, val short: String, val keyUrl: String)

internal val voiceProviders = listOf(
    VoiceProviderInfo("gemini", "Gemini Live", "Gemini", "https://aistudio.google.com/apikey"),
    VoiceProviderInfo("grok", "Grok", "Grok", "https://console.x.ai/team/default/api-keys"),
    VoiceProviderInfo("openai", "OpenAI Realtime", "OpenAI", "https://platform.openai.com/api-keys"),
)

internal fun voiceProviderInfo(provider: String): VoiceProviderInfo = voiceProviders.firstOrNull { it.id == provider } ?: voiceProviders.first()

/** Owner, 3 Nov: shown as "Coming soon" and not selectable on the provider page. */
internal val voiceProvidersComingSoon = setOf("openai")

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
        "Enceladus" to "Breathy", "Iapetus" to "Clear voice", "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth",
        "Erinome" to "Clear voice", "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
        "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward", "Achird" to "Friendly",
        "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively", "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
    ),
    "grok" to mapOf("eve" to "Energetic", "ara" to "Warm and friendly", "rex" to "Confident and clear", "sal" to "Smooth", "leo" to "Authoritative"),
    "openai" to mapOf("marin" to "Best quality", "cedar" to "Best quality"),
).mapValues { (_, styles) -> styles.mapKeys { it.key.lowercase() } }

/** The style words under the voices; the picker shows each through uiText. */
internal val VoiceStyleStrings = voiceStyles.values.flatMap { it.values }.distinct()

internal fun voiceStyle(provider: String, voice: String): String = voiceStyles[provider]?.get(voice.lowercase()).orEmpty()

internal fun voiceModelNote(provider: String, model: String): String = when {
    model == defaultVoiceModel(provider) -> "Default"
    model.endsWith("extended-thinking") -> "Thinks longer before answering"
    model.endsWith("gemini-3.1-flash-live-preview") || model.endsWith("grok-voice-2.0") -> "Older"
    model.endsWith("grok-voice-latest") -> "Newest, moves with updates"
    else -> ""
}

/** A model as a card: its name, with a trailing "Preview 09 2025" moved to the detail, so a long name never hides what tells it apart. */
internal fun voiceModelCardText(model: String): Pair<String, String> {
    val name = voiceModelDisplayName(model)
    val at = name.indexOf(" Preview")
    return if (at > 0) name.substring(0, at) to name.substring(at + 1) else name to ""
}

/** The rows shown before "More": the first [limit], plus the selected one wherever it sits in the stable order. */
internal fun <T> visibleOptions(all: List<T>, selected: T, expanded: Boolean, limit: Int): List<T> =
    if (expanded) all else all.filterIndexed { index, option -> index < limit || option == selected }

/** What each provider can do on a call. */
@Composable private fun voiceProviderNote(provider: String): String = when (provider) {
    "gemini" -> uiText("Voice, camera, screen")
    else -> uiText("Voice calls")
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
    // A provider that is coming soon is never what Continue goes on with.
    var selected by remember { mutableStateOf(store.settings().provider.takeIf { it !in voiceProvidersComingSoon } ?: voiceProviders.first().id) }
    var saved by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val choiceNotSaved = uiText("Couldn't save that choice. Try again.")
    LaunchedEffect(Unit) { saved = withContext(Dispatchers.IO) { voiceProviders.map { it.id }.filter(store::hasKey).toSet() } }

    fun proceed() {
        busy = true
        status = ""
        scope.launch {
            val provider = selected
            val hasKey = withContext(Dispatchers.IO) { store.hasKey(provider) }
            if (hasKey && runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }.isFailure) {
                status = choiceNotSaved
                busy = false
                return@launch
            }
            busy = false
            onContinue(provider, hasKey)
        }
    }
    val keySaved = uiText("Key saved")
    val comingSoon = uiText("Coming soon")
    val options = voiceProviders.map { info ->
        val ready = info.id !in voiceProvidersComingSoon
        SettingsOption(info.id, info.name, voiceProviderNote(info.id), Icons.Rounded.GraphicEq, badge = if (!ready) comingSoon else if (info.id in saved) keySaved else "",
            testTag = "voice-model-${info.id}", enabled = ready)
    }
    return SetupPage(
        content = {
            SettingsOptionCards(options, selected, { selected = it; status = "" }, enabled = !busy, contentPadding = PaddingValues(0.dp))
            SetupStatusLine(status, error = true)
        },
        actions = {
            SetupPrimaryButton(uiText("Continue"), ::proceed, Modifier.testTag("setup-voice-continue"), enabled = !busy)
            onSkip?.let { SetupTextAction(uiText("Skip for now"), it, Modifier.testTag("setup-voice-skip")) }
            onBack?.let { SetupTextAction(uiText("Back"), it, Modifier.testTag("voice-page-back")) }
            onClose?.let { SetupTextAction(uiText("Close setup"), it) }
        },
    )
}

/** What the last Test key said, shown as a status pill under the field. */
internal enum class VoiceKeyCheck { None, Checking, Works, Rejected, Unreachable, ProviderError }

/** The pill for a key check: a status and the English words (with {0} for the provider) the page passes through uiText. */
internal fun voiceKeyCheckStatus(check: VoiceKeyCheck): SettingsStatus? = when (check) {
    VoiceKeyCheck.None -> null
    VoiceKeyCheck.Checking -> SettingsStatus.Working
    VoiceKeyCheck.Works -> SettingsStatus.Ok
    VoiceKeyCheck.Rejected, VoiceKeyCheck.ProviderError -> SettingsStatus.Error
    VoiceKeyCheck.Unreachable -> SettingsStatus.Attention
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
    var check by remember(provider) { mutableStateOf(VoiceKeyCheck.None) }
    // Anything else that went wrong (no key typed, a save that failed), in one short line.
    var status by remember(provider) { mutableStateOf("") }
    var confirmRemove by remember(provider) { mutableStateOf(false) }
    // Words the callbacks below show are translated here, where uiText can run.
    val enterKeyFirst = uiText("Enter an API key first.")
    val keyNotSaved = uiText("That key couldn't be saved. Try again.")
    val choiceNotSaved = uiText("Couldn't save that choice. Try again.")
    val keyNotRemoved = uiText("The key could not be removed.")
    LaunchedEffect(provider) { saved = withContext(Dispatchers.IO) { store.maskedKey(provider) } }
    // A first key typed here is saved (encrypted) when the page is left, unless the provider just refused it. A saved key is only replaced by Test key, so a stray paste cannot overwrite a working one.
    DisposableEffect(provider) { onDispose { if (saved == null && draft.isNotBlank() && !rejected) runCatching { store.saveKey(provider, draft) } } }
    val canTest = !testing && (draft.isNotEmpty() || saved != null)

    fun paste() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip?.takeIf { it.itemCount > 0 }
        draft = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty().filterNot(Char::isWhitespace).take(8192)
        status = ""; check = VoiceKeyCheck.None; rejected = false; retry = false
    }

    fun test() {
        val entered = draft
        testing = true; rejected = false; retry = false; status = ""; check = VoiceKeyCheck.Checking
        scope.launch {
            val previous = withContext(Dispatchers.IO) { store.key(provider) }
            val apiKey = entered.ifEmpty { previous.orEmpty() }
            if (apiKey.isBlank()) { check = VoiceKeyCheck.None; status = enterKeyFirst; testing = false; return@launch }
            if (entered.isNotEmpty() && runCatchingCancellable { withContext(Dispatchers.IO) { store.saveKey(provider, entered) } }.isFailure) {
                check = VoiceKeyCheck.None; status = keyNotSaved; testing = false; return@launch
            }
            val outcome = testVoiceProvider(provider, apiKey, store.settings(provider).model)
            when (outcome) {
                VoiceProviderTestResult.Success -> {
                    val chosen = runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }.isSuccess
                    draft = ""
                    saved = withContext(Dispatchers.IO) { store.maskedKey(provider) }
                    VoiceCatalogCache.clear(provider)
                    VoiceCatalogCache.prefetch(provider, apiKey)
                    check = VoiceKeyCheck.Works
                    if (chosen) { onKeySaved(); onVerified() } else { status = choiceNotSaved }
                }
                VoiceProviderTestResult.KeyRejected -> {
                    // Undo the replacement: the refused key must never show up as the saved one.
                    if (entered.isNotEmpty()) withContext(Dispatchers.IO) {
                        runCatching { if (previous != null) store.saveKey(provider, previous) else store.removeKey(provider) }
                    }
                    saved = withContext(Dispatchers.IO) { store.maskedKey(provider) }
                    rejected = true
                    check = VoiceKeyCheck.Rejected
                }
                else -> {
                    // The key could not be judged; it stays saved, the provider choice is kept, and the user may carry on.
                    runCatchingCancellable { withContext(Dispatchers.IO) { store.setSelection(store.settings(provider)) } }
                    if (entered.isNotEmpty()) { draft = ""; saved = withContext(Dispatchers.IO) { store.maskedKey(provider) } }
                    retry = true
                    check = if (outcome == VoiceProviderTestResult.Unreachable) VoiceKeyCheck.Unreachable else VoiceKeyCheck.ProviderError
                }
            }
            testing = false
        }
    }

    val checkLabel = when (check) {
        VoiceKeyCheck.None -> ""
        VoiceKeyCheck.Checking -> uiText("Checking…")
        VoiceKeyCheck.Works -> uiText("Your key works")
        VoiceKeyCheck.Rejected -> uiText("{0} didn't accept this key").replace("{0}", info.short)
        VoiceKeyCheck.Unreachable -> uiText("Couldn't reach {0}").replace("{0}", info.short)
        VoiceKeyCheck.ProviderError -> uiText("{0} returned an error").replace("{0}", info.short)
    }
    return SetupPage(
        content = {
            SettingsSection {
                SettingsNavRow(uiText("Get a key"), { uriHandler.openUri(info.keyUrl) }, icon = Icons.Rounded.Key, external = true, testTag = "voice-key-get")
                SettingsDivider()
                SettingsSecretField(uiText("{0} API key").replace("{0}", info.short), draft,
                    { draft = it.filterNot(Char::isWhitespace).take(8192); status = ""; check = VoiceKeyCheck.None; rejected = false; retry = false },
                    savedMask = saved, onPaste = ::paste, visible = visible, onToggleVisible = { visible = !visible }, enabled = !testing, isError = rejected,
                    imeDone = { if (canTest) test() }, pasteLabel = uiText("Paste key"), showLabel = uiText("Show key"), hideLabel = uiText("Hide key"),
                    savedLabel = uiText("Saved"), testTag = "voice-provider-key")
                voiceKeyCheckStatus(check)?.let { state ->
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                        SettingsStatusPill(state, checkLabel, testTag = "voice-key-status")
                    }
                }
                if (status.isNotBlank()) SettingsErrorRow(status, null, "")
                SettingsDivider()
                // What a call shares, in one line: the provider hears and speaks, Hermes does the thinking.
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(SettingsKit.TileSize), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(uiText("Your provider hears you and speaks the replies"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
            }
            if (removable && saved != null) SettingsSection {
                SettingsDestructiveRow(uiText("Remove key"), { confirmRemove = true }, enabled = !testing, testTag = "voice-key-remove")
            }
            if (confirmRemove) SettingsConfirmDialog(
                uiText("Remove the {0} key from this phone?").replace("{0}", info.short),
                uiText("Calls with {0} stop until you add a key.").replace("{0}", info.short),
                uiText("Remove key"),
                onConfirm = {
                    confirmRemove = false
                    scope.launch {
                        if (runCatchingCancellable { withContext(Dispatchers.IO) { store.removeKey(provider) } }.isSuccess) {
                            VoiceCatalogCache.clear(provider)
                            saved = null
                            onRemoved()
                        } else { status = keyNotRemoved }
                    }
                },
                onDismiss = { confirmRemove = false },
                confirmTestTag = "voice-key-remove-confirm",
            )
        },
        actions = {
            SetupPrimaryButton(if (retry) uiText("Try again") else uiText("Test key"), ::test, Modifier.testTag("voice-key-test"), enabled = canTest, loading = testing)
            if (retry) SetupTextAction(uiText("Continue anyway"), onContinueAnyway, Modifier.testTag("voice-key-continue-anyway"), enabled = !testing)
            SetupTextAction(uiText("Back"), onBack, Modifier.testTag("voice-page-back"), enabled = !testing)
            if (!retry) onSkip?.let { SetupTextAction(uiText("Skip for now"), it, Modifier.testTag("setup-voice-skip"), enabled = !testing) }
        },
    )
}

/**
 * Page 3: Voice, Personality and Model as option cards with the current choice marked. A tap saves at once and the lists never
 * reorder (the model chosen when the page opens is listed first). A hero says which provider calls use and whether its key is
 * there; in Settings ([settingsMode]) it also offers Change, and "Add key" when none is saved.
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
    val choiceNotSaved = uiText("Couldn't save that choice. Try again.")
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
                status = choiceNotSaved
            }
        }
    }

    val info = voiceProviderInfo(provider)
    val heroStatus = when (hasKey) { true -> SettingsStatus.Ok; false -> SettingsStatus.Attention; null -> null }
    val heroLine = when { hasKey == null -> ""; hasKey == false -> uiText("Add a key to call"); keyWorks -> uiText("Key works"); else -> uiText("Key saved") }
    val keyOnPhone = uiText("Key on this phone")
    val nextCall = uiText("Applies next call")
    val addKey = SettingsAction(uiText("Add key"), onAddKey, testTag = "voice-add-key")
    val change = SettingsAction(uiText("Change"), onChange, testTag = "voice-provider-change")
    // The personalities with their words written here, each through uiText.
    val personalities = listOf(
        SettingsOption("balanced", uiText("Balanced"), uiText("Everyday"), Icons.Rounded.Balance, testTag = "voice-option-personality-balanced"),
        SettingsOption("concise", uiText("Concise"), uiText("Short answers"), Icons.AutoMirrored.Rounded.ShortText, testTag = "voice-option-personality-concise"),
        SettingsOption("warm", uiText("Warm"), uiText("Friendly"), Icons.Rounded.Favorite, testTag = "voice-option-personality-warm"),
    )
    return SetupPage(
        content = {
            SettingsStatusHero(heroStatus, info.name, line = heroLine, icon = Icons.Rounded.GraphicEq,
                meta = if (hasKey == true || settingsMode && callActive) ({
                    if (hasKey == true) SettingsStatusPill(SettingsStatus.Ok, keyOnPhone)
                    if (settingsMode && callActive) SettingsStatusPill(SettingsStatus.Working, nextCall)
                }) else null,
                primary = if (settingsMode && hasKey == false) addKey else null,
                secondary = if (settingsMode) change else null,
                lineTestTag = "voice-provider-summary", testTag = "voice-hero")
            // The lists render at once from the built-in choices; only a confirmed missing key hides them (the key read can be slow).
            if (hasKey != false) {
                // One card per voice whatever its capitalisation; a saved "aoede" selects the "Aoede" card.
                val voices = (defaultVoiceChoices(provider) + catalog?.voices.orEmpty() + selection.voice).distinctBy { it.lowercase() }
                val selectedVoice = voices.firstOrNull { it.equals(selection.voice, ignoreCase = true) } ?: selection.voice
                val hiddenVoices = voices.size - visibleOptions(voices, selectedVoice, moreVoices, 6).size
                SettingsSection(uiText("Voice")) {
                    SettingsOptionCards(voices.map { voice ->
                        SettingsOption(voice, voiceChoiceLabel(voice, voices), voiceStyle(provider, voice).takeIf(String::isNotBlank)?.let { uiText(it) }.orEmpty(), testTag = "voice-option-voice-$voice")
                    }, selectedVoice, { voice -> pick(selection.copy(voice = voice)) }, columns = 2, collapsedCount = 6,
                        moreLabel = uiText("More voices ({0})").replace("{0}", "$hiddenVoices"), moreTestTag = "voice-more-voices", expanded = moreVoices, onExpand = { moreVoices = true })
                }
                SettingsSection(uiText("Personality")) {
                    // Three cards side by side break their names at large text; one column keeps every word whole.
                    SettingsOptionCards(personalities, selection.personality, { id -> pick(selection.copy(personality = id)) }, columns = if (isLargeText(androidx.compose.ui.platform.LocalDensity.current.fontScale)) 1 else 3)
                }
                val models = (listOf(openingModel) + catalog?.models.orEmpty().ifEmpty { listOf(defaultVoiceModel(provider)) } + selection.model).distinct()
                SettingsSection(uiText("Model")) {
                    if (models.size > 1) {
                        val hiddenModels = models.size - visibleOptions(models, selection.model, moreModels, 4).size
                        SettingsOptionCards(models.map { model ->
                            val (label, version) = voiceModelCardText(model)
                            val note = voiceModelNote(provider, model).takeIf(String::isNotBlank)?.let { uiText(it) }
                            SettingsOption(model, label, listOfNotNull(version.takeIf(String::isNotBlank), note).joinToString(" · "), testTag = "voice-option-model-$model")
                        }, selection.model, { model -> pick(selection.copy(model = model)) }, collapsedCount = 4,
                            moreLabel = uiText("More models ({0})").replace("{0}", "$hiddenModels"), moreTestTag = "voice-more-models", expanded = moreModels, onExpand = { moreModels = true })
                    } else SettingsValueRow(uiText("Model"), icon = Icons.Rounded.GraphicEq, value = voiceModelDisplayName(selection.model), testTag = "voice-model-single")
                }
                if (onOwnerNameChange != null) SettingsSection {
                    SettingsTextFieldRow(assistantText("What should Beacon call you?"), ownerDraft, { ownerDraft = ownerNameDraft(it); onOwnerNameChange(ownerNameOf(ownerDraft)) },
                        icon = Icons.Rounded.Badge, placeholder = uiText("Optional"), fieldTestTag = "setup-owner-name")
                }
                if (status.isNotBlank()) SettingsErrorRow(status, null, "")
            }
        },
        actions = {
            if (!settingsMode) {
                SetupPrimaryButton(uiText("Continue"), onContinue, Modifier.testTag("setup-voice-customize-continue"))
                SetupTextAction(uiText("Back"), onBack, Modifier.testTag("voice-page-back"))
            }
        },
    )
}
