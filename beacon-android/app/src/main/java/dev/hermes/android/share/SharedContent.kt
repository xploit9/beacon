package dev.hermes.android.share

import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.push.validOpaque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * What another app shares into Beacon from Android's share sheet. It is untrusted data: it is bounded here, shown to the owner, and
 * only ever placed in a composer; nothing is sent until the owner taps send.
 */
internal const val SHARE_TEXT_LIMIT = 40_000
internal const val SHARE_IMAGE_LIMIT = 10
private const val SHARE_URI_LIMIT = 2_048
private const val SHARE_SUBJECT_LIMIT = 200
internal const val SHARE_RECENT_LIMIT = 6
/** A share that nobody picked up in this long (the app never opened) is dropped, not delivered to a later session. */
internal const val SHARE_VALID_MILLIS = 120_000L
/** How long the app may take to be signed in and ready, and then to open the chosen chat, before the share gives up with a line. */
internal const val SHARE_READY_MILLIS = 30_000L
internal const val SHARE_OPEN_MILLIS = 20_000L

/** The lines the share chooser and its hand-off show, in English; the chooser and notices run them through uiText. */
internal object ShareStrings {
    const val TITLE = "Add to a chat"
    const val RECENT = "Recent chats"
    const val NEW_CHAT = "New chat"
    const val ONE_IMAGE = "1 image"
    const val IMAGES = "{0} images"
    const val SIGN_IN = "Open Beacon and connect to your Hermes first."
    const val UNSUPPORTED = "Beacon can only take text, links and images."
    const val IMAGE_CAP = "Beacon takes up to {0} images at a time."
    const val TOO_LONG = "That is too long to share here."
    const val NOT_OPENED = "Couldn't open the chat. Try again."
    val all = listOf(TITLE, RECENT, NEW_CHAT, ONE_IMAGE, IMAGES, SIGN_IN, UNSUPPORTED, IMAGE_CAP, TOO_LONG, NOT_OPENED)
}

internal data class SharedContent(val text: String, val images: List<String>, val skippedImages: Int = 0, val tooLong: Boolean = false) {
    val isEmpty get() = text.isBlank() && images.isEmpty()
}

private fun cleanLine(value: CharSequence?): String = value?.toString().orEmpty().filter { !it.isISOControl() || it == '\n' || it == '\t' }.trim()

private fun bareLink(text: String): Boolean = (text.startsWith("http://") || text.startsWith("https://")) && text.none { it.isWhitespace() }

/**
 * A content address another app may name: a `content://` item from some other app's provider. A file path, or any of Beacon's own
 * providers, is refused, since reading it would use Beacon's access to the owner's files on another app's say.
 */
internal fun acceptableShareUri(uri: String, ownPackage: String): Boolean {
    if (uri.length > SHARE_URI_LIMIT) return false
    val parsed = runCatching { java.net.URI(uri) }.getOrNull() ?: return false
    // Android reads "user@authority" as that authority for another user, and decodes %40 first, so the part after the last "@" names the provider.
    val authorities = listOfNotNull(parsed.rawAuthority, parsed.authority).map { it.substringAfterLast('@').lowercase() }
    if (!parsed.scheme.equals("content", ignoreCase = true) || authorities.isEmpty() || authorities.any { it.isBlank() }) return false
    val own = ownPackage.lowercase()
    return authorities.none { it == own || it.startsWith("$own.") }
}

/**
 * The text, link and images of one share. A bare link keeps its page title above it when the sender gave one; images are only the ones
 * the sender's provider says are images, at most [SHARE_IMAGE_LIMIT].
 */
internal fun sharedContentOf(text: CharSequence?, subject: CharSequence?, streams: List<String>, typeOf: (String) -> String?, ownPackage: String): SharedContent {
    val body = cleanLine(text)
    val title = cleanLine(subject).lineSequence().firstOrNull().orEmpty().take(SHARE_SUBJECT_LIMIT).trim()
    val combined = if (title.isNotEmpty() && bareLink(body) && !body.contains(title)) "$title\n$body" else body
    val images = streams.filter { acceptableShareUri(it, ownPackage) && typeOf(it)?.startsWith("image/", ignoreCase = true) == true }.distinct()
    val tooLong = combined.length > SHARE_TEXT_LIMIT
    return SharedContent(if (tooLong) "" else combined, images.take(SHARE_IMAGE_LIMIT), (images.size - SHARE_IMAGE_LIMIT).coerceAtLeast(0), tooLong)
}

/** The shared text under what the composer already holds, never over it. */
internal fun mergeSharedDraft(existing: String, shared: String): String = when {
    shared.isBlank() -> existing
    existing.isBlank() -> shared
    else -> existing.trimEnd() + "\n\n" + shared
}

/** One short line naming what was shared: the start of its text, or how many images. */
internal fun shareSnippet(text: String, limit: Int = 80): String {
    val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
    return if (first.length <= limit) first else first.take(limit - 1).trimEnd() + "…"
}

/** A saved chat the share can add to: one Beacon can open as it is, not an imported or read-only one. */
internal fun sharableChat(row: JSONObject): Boolean {
    val source = row.string("session_source", row.string("raw_source", row.string("source_tag", row.string("source")))).lowercase()
    val imported = source != "webui" && (row.optBoolean("is_cli_session") || source in setOf("telegram", "discord", "slack", "whatsapp", "signal", "matrix", "messenger", "sms", "email"))
    return validOpaque(sessionRowId(row)) && !imported && !row.optBoolean("archived") && !row.optBoolean("read_only") && !row.optBoolean("is_read_only")
}

/** The recent chats to offer, id and title (blank for a chat with no title yet), from the saved chat list the app already keeps (so the chooser opens filled), most recent first. */
internal fun recentChats(index: JSONObject?, limit: Int = SHARE_RECENT_LIMIT): List<Pair<String, String>> =
    index?.rows("sessions").orEmpty().filter(::sharableChat).map { sessionRowId(it) to it.string("title").trim() }.distinctBy { it.first }.take(limit)

internal sealed interface ShareTarget {
    data object Beacon : ShareTarget
    data object NewChat : ShareTarget
    data class Chat(val id: String) : ShareTarget
}

internal data class PendingShare(val target: ShareTarget, val content: SharedContent, val offeredAt: Long)

/** The hand-off from the chooser to the app: held in memory only, taken once, and dropped when it is old. */
internal object SharedIntake {
    private val held = MutableStateFlow<PendingShare?>(null)
    val pending: StateFlow<PendingShare?> = held
    /** The last share offered. A running app claims a share at once, so [pending] may never show it; device tests read this instead. */
    @Volatile var lastOffered: PendingShare? = null; private set
    fun offer(share: PendingShare) { lastOffered = share; held.value = share }
    /** True for the first caller only, and only while the share is fresh. */
    fun claim(share: PendingShare, now: Long): Boolean = held.compareAndSet(share, null) && now - share.offeredAt in 0..SHARE_VALID_MILLIS
}
