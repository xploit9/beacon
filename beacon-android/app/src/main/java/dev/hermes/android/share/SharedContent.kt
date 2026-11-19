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
internal const val SHARE_FILE_LIMIT = 10
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
    const val ONE_FILE = "1 file"
    const val FILES = "{0} files"
    const val SIGN_IN = "Open Beacon and connect to your Hermes first."
    const val UNSUPPORTED = "Beacon can only take text, links, images and documents."
    const val FILE_CAP = "Beacon takes up to {0} files at a time."
    const val TOO_LONG = "That is too long to share here."
    const val NOT_OPENED = "Couldn't open the chat. Try again."
    val all = listOf(TITLE, RECENT, NEW_CHAT, ONE_IMAGE, IMAGES, ONE_FILE, FILES, SIGN_IN, UNSUPPORTED, FILE_CAP, TOO_LONG, NOT_OPENED)
}

/** [files]: the shared images and documents, attached as the attach button does; [onlyImages] when every one of them is an image. */
internal data class SharedContent(val text: String, val files: List<String>, val skippedFiles: Int = 0, val tooLong: Boolean = false, val onlyImages: Boolean = true) {
    val isEmpty get() = text.isBlank() && files.isEmpty()
}

/** What a chat can send from a share: images and documents (PDF, Office, text and any other application type Hermes stages with file.attach). */
internal fun sharableFileType(type: String?): Boolean = type != null && listOf("image/", "application/", "text/").any { type.startsWith(it, ignoreCase = true) }

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
 * The text, link and files of one share. A bare link keeps its page title above it when the sender gave one; files are only the ones the
 * sender's provider says are images or documents ([sharableFileType]), at most [SHARE_FILE_LIMIT].
 */
internal fun sharedContentOf(text: CharSequence?, subject: CharSequence?, streams: List<String>, typeOf: (String) -> String?, ownPackage: String): SharedContent {
    val body = cleanLine(text)
    val title = cleanLine(subject).lineSequence().firstOrNull().orEmpty().take(SHARE_SUBJECT_LIMIT).trim()
    val combined = if (title.isNotEmpty() && bareLink(body) && !body.contains(title)) "$title\n$body" else body
    val files = streams.filter { acceptableShareUri(it, ownPackage) }.distinct().mapNotNull { uri -> typeOf(uri)?.takeIf(::sharableFileType)?.let { uri to it } }
    val tooLong = combined.length > SHARE_TEXT_LIMIT
    val kept = files.take(SHARE_FILE_LIMIT)
    return SharedContent(if (tooLong) "" else combined, kept.map { it.first }, (files.size - SHARE_FILE_LIMIT).coerceAtLeast(0), tooLong,
        kept.all { it.second.startsWith("image/", ignoreCase = true) })
}

/** The shared text under what the composer already holds, never over it. */
internal fun mergeSharedDraft(existing: String, shared: String): String = when {
    shared.isBlank() -> existing
    existing.isBlank() -> shared
    else -> existing.trimEnd() + "\n\n" + shared
}

/** One short line naming what was shared: the start of its text. */
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
