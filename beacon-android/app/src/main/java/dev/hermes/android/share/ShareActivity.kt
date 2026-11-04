@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package dev.hermes.android.share

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.cachedScopeFor
import dev.hermes.android.preferences
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the chooser shows once it has read the saved account: all of it from this phone, so it opens filled. */
internal data class ShareState(val content: SharedContent, val ready: Boolean, val assistant: String, val recents: List<Pair<String, String>>, val theme: String, val skin: String)

/**
 * Beacon in Android's share sheet for text, links and images. It draws a small chooser over the sharing app: the Beacon chat, a new chat
 * or a recent chat. The choice opens Beacon with the shared content already in that chat's composer (BeaconViewModel reads the hand-off);
 * nothing is sent until the owner taps send.
 */
class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val shared = intent
        setContent {
            val state by produceState<ShareState?>(null) { value = withContext(Dispatchers.IO) { loadShareState(this@ShareActivity, shared) } }
            val language = nativeLanguageFor(LocalConfiguration.current.locales[0])
            val loaded = state
            val dark = when (loaded?.theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
            CompositionLocalProvider(LocalAssistantName provides (loaded?.assistant ?: dev.hermes.android.data.DEFAULT_ASSISTANT_NAME)) {
                NativeLocalization(language) { BeaconTheme(dark, loaded?.skin ?: "default") {
                    if (loaded != null) ShareChooser(loaded, ::pick, ::finish)
                } }
            }
        }
    }

    private fun pick(content: SharedContent, target: ShareTarget) {
        SharedIntake.offer(PendingShare(target, content, System.currentTimeMillis()))
        val open = Intent(this, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        // Android lets the sharing app's access to its images follow them into Beacon only with the images named in the intent.
        val uris = content.images.map(Uri::parse)
        val withImages = Intent(open).apply {
            if (uris.isNotEmpty()) {
                clipData = ClipData.newRawUri("", uris.first()).also { clip -> uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) } }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        // If Android refuses to pass the images on, the chat still opens and shows which image could not be attached.
        try { startActivity(withImages) } catch (_: SecurityException) { startActivity(open) }
        finish()
    }
}

private suspend fun loadShareState(activity: ShareActivity, intent: Intent): ShareState {
    val app = activity.application as BeaconApplication
    val prefs = app.preferences.data.first()
    val settings = AppPreferences.read(prefs)
    val origin = prefs[stringPreferencesKey("origin")] ?: BuildConfig.DEFAULT_ORIGIN
    val scope = if (origin.isBlank()) null else runCatching { app.auth.read(origin).cachedScopeFor(origin, replacingAccount = false) }.getOrNull()
    val ready = scope != null && prefs[booleanPreferencesKey("setup_complete")] == true && prefs[booleanPreferencesKey("setup_active")] != true
    val index = if (ready && scope != null) runCatching { app.cache.read(scope, "conversation-index")?.let(::JSONObject) }.getOrNull() else null
    return ShareState(readShareIntent(activity, intent), ready, settings.assistantName, recentChats(index), settings.theme, settings.skin)
}

/** The shared text, link and images of an ACTION_SEND or ACTION_SEND_MULTIPLE intent; any malformed extra reads as nothing. */
private fun readShareIntent(activity: ShareActivity, intent: Intent): SharedContent {
    if (intent.action != Intent.ACTION_SEND && intent.action != Intent.ACTION_SEND_MULTIPLE) return SharedContent("", emptyList())
    return try {
        val streams = if (intent.action == Intent.ACTION_SEND) listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            else IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        val sentType = intent.type
        sharedContentOf(intent.getCharSequenceExtra(Intent.EXTRA_TEXT), intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT), streams.map(Uri::toString), { uri ->
            // The sender's own provider says what an item is; a single image sent without a provider type keeps the intent's.
            runCatching { activity.contentResolver.getType(Uri.parse(uri)) }.getOrNull() ?: sentType?.takeIf { streams.size == 1 && !it.endsWith("/*") }
        }, activity.packageName)
    } catch (_: RuntimeException) { SharedContent("", emptyList()) }
}

@Composable
private fun ShareChooser(state: ShareState, onPick: (SharedContent, ShareTarget) -> Unit, onClose: () -> Unit) {
    val content = state.content
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("beacon-share-sheet")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(uiText(ShareStrings.TITLE), style = MaterialTheme.typography.titleLarge)
            val snippet = shareSnippet(content.text)
            if (snippet.isNotEmpty()) Text(snippet, Modifier.padding(top = 6.dp).testTag("beacon-share-text"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (content.images.isNotEmpty()) Row(Modifier.padding(top = 6.dp).testTag("beacon-share-images"), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Rounded.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(if (content.images.size == 1) uiText(ShareStrings.ONE_IMAGE) else uiText(ShareStrings.IMAGES).replace("{0}", content.images.size.toString()),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (content.skippedImages > 0) Text(uiText(ShareStrings.IMAGE_CAP).replace("{0}", SHARE_IMAGE_LIMIT.toString()), Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            val line = when {
                !state.ready -> ShareStrings.SIGN_IN
                content.tooLong -> ShareStrings.TOO_LONG
                content.isEmpty -> ShareStrings.UNSUPPORTED
                else -> ""
            }
            if (line.isNotEmpty()) {
                Text(uiText(line), Modifier.padding(vertical = 8.dp).testTag("beacon-share-note"), style = MaterialTheme.typography.bodyLarge)
            } else {
                Destination(state.assistant, { Icon(painterResource(R.drawable.ic_beacon_logo), null, Modifier.size(24.dp), tint = Color.Unspecified) }, "beacon-share-beacon") { onPick(content, ShareTarget.Beacon) }
                Destination(uiText(ShareStrings.NEW_CHAT), { Icon(Icons.Rounded.Add, null) }, "beacon-share-new") { onPick(content, ShareTarget.NewChat) }
                if (state.recents.isNotEmpty()) {
                    Text(uiText(ShareStrings.RECENT), Modifier.padding(top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    state.recents.forEach { (id, title) ->
                        Destination(title.ifBlank { uiText(ShareStrings.NEW_CHAT) }, { Icon(Icons.Rounded.ChatBubbleOutline, null) }, "beacon-share-chat-$id") { onPick(content, ShareTarget.Chat(id)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Destination(title: String, icon: @Composable () -> Unit, tag: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingContent = icon,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}
