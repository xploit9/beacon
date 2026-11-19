package dev.hermes.android.core

import java.util.Locale

/**
 * Files the agent hands to a chat. Hermes' contract (checked at Hermes 2bd378ed): the agent writes `MEDIA:/absolute/path` in its reply
 * (agent/prompt_builder.py, the desktop hint); messaging gateways lift the tag out and send the file (gateway/platforms/base.py
 * MEDIA_TAG_CLEANUP_RE), while the canonical session stream keeps it in the reply text (tui_gateway does not touch it), and Hermes'
 * desktop turns it into a file card that downloads through `/api/fs/download` (apps/desktop/src/lib/chat-messages/parts.ts,
 * browser-transport.ts FILE_ENDPOINTS). The phone reads the same tag with the same grammar.
 */

/** Hermes' deliverable extensions, gateway/platforms/base.py MEDIA_DELIVERY_EXTS. */
internal val MEDIA_DELIVERY_EXTS = listOf(
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg",
    "mp4", "mov", "avi", "mkv", "webm", "3gp",
    "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac",
    "pdf", "docx", "doc", "odt", "rtf", "txt", "md", "epub",
    "xlsx", "xls", "ods", "csv", "tsv", "json", "xml", "yaml", "yml",
    "kmz", "kml", "geojson", "gpx",
    "pptx", "ppt", "odp", "key",
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar", "apk", "ipa",
    "html", "htm",
)

// Longest first, so a short extension never ends a match that a longer one would (base.py _MEDIA_EXT_ALTERNATION).
private val extensionAlternation = MEDIA_DELIVERY_EXTS.sortedByDescending { it.length }.joinToString("|")
// base.py _MEDIA_CJK_TERMINATORS.
private const val CJK_TERMINATORS = "（）〈〉《》：，。；！？、\u201c\u201d\u2018\u2019【】"

/**
 * base.py MEDIA_TAG_CLEANUP_RE: quoted paths, or an anchored path (`~/`, `/`, `X:\`, `X:/`) that may hold spaces and ends in a deliverable
 * extension; a sentence-final `.` and the next `MEDIA:` end it. Anything else is the desktop's bare-word fallback (parts.ts _MEDIA_PATH_BARE),
 * which never runs into a glued `MEDIA:` and, like the desktop, needs the tag in capitals: "Social media: example.com" stays words.
 * Braces in classes are escaped: Android's ICU regex rejects a bare `}`.
 */
private val mediaTagPattern = Regex(
    "[`\"'*_]{0,3}(MEDIA:)[ \\t]*(?:`([^`\\n]+?)`|\"([^\"\\n]+?)\"|'([^'\\n]+?)'|" +
        "((?:~/|/|[A-Za-z]:[/\\\\])\\S+?(?:[^\\S\\n]+\\S+?)*?\\.(?:$extensionAlternation))(?=[\\s`\"'*_,;:)\\]\\}\\[$CJK_TERMINATORS]|MEDIA:|\\.(?:\\s|$)|$)|" +
        "((?:(?!MEDIA:)[^\\s`\"])+))[`\"'*_]{0,3}",
    RegexOption.IGNORE_CASE,
)
private const val TRAILING_PUNCTUATION = ".,;:!?"

/** One `MEDIA:` tag in reply text: the path it names, and the characters it takes ([start], [end) exclusive). */
internal data class MediaTag(val path: String, val start: Int, val end: Int)

/** Whether a value can name a file: a separator, or a dot with something after it (parts.ts isPlausibleMediaPath). */
private fun plausiblePath(value: String) = '/' in value || '\\' in value || Regex("\\.[^.]").containsMatchIn(value)

/** Every `MEDIA:` tag in [text], in order. A bare tag gives back the sentence punctuation after it; a quoted one keeps every character inside its quotes. */
internal fun mediaTags(text: String): List<MediaTag> {
    if (!text.contains("MEDIA:", ignoreCase = true)) return emptyList()
    return mediaTagPattern.findAll(text).mapNotNull { match ->
        val quoted = (2..5).firstNotNullOfOrNull { match.groups[it] }
        if (quoted != null) return@mapNotNull quoted.value.trim().takeIf(::plausiblePath)?.let { MediaTag(it, match.range.first, match.range.last + 1) }
        val bare = match.groups[6]?.takeIf { match.groups[1]?.value == "MEDIA:" } ?: return@mapNotNull null
        var path = bare.value
        while (path.isNotEmpty() && path.last() in TRAILING_PUNCTUATION && plausiblePath(path.dropLast(1))) path = path.dropLast(1)
        // A trailing quote or backtick is formatting left over, not part of the name; an apostrophe can be (john's.md).
        path = path.trimEnd('`', '"')
        if (!plausiblePath(path)) return@mapNotNull null
        MediaTag(path, match.range.first, bare.range.first + path.length)
    }.toList()
}

/**
 * A Markdown link that names a file on the Hermes computer (`[report](/home/me/report.pdf)`, `C:\…`, `~/…`) by a deliverable extension, as
 * Hermes' desktop routes such links to a file card (markdown-text.tsx MarkdownLink). Hermes' own routes (/api/…, /session/…) and anything
 * with a query or fragment stay links.
 */
internal fun localFileLinkPath(destination: String): String? {
    val value = destination.trim()
    val windows = Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(value)
    if (!windows && !value.startsWith("~/") && !(value.startsWith("/") && !value.startsWith("//"))) return null
    if (value.startsWith("/api/") || value.startsWith("/session/") || '?' in value || '#' in value || value.any { it.isISOControl() }) return null
    return value.takeIf { receivedFileExtension(receivedFileName(it)) in MEDIA_DELIVERY_EXTS }
}

/**
 * What a tapped reply link opens, and whether as a file card (second): only a link naming a file on the Hermes computer by its path, as the
 * agent delivers one, is a card. Hermes' own file routes (`/api/file/raw`, `/api/media`) and file:// addresses keep the in-app viewer they
 * always had (PDF pages, the JSON tree).
 */
internal fun tappedLink(destination: String, scope: Scope, snapshots: Map<String, String> = emptyMap()): Pair<NativeReference, Boolean> =
    localFileLinkPath(destination)?.let { resolveMediaTag(it, scope, snapshots) to true } ?: (resolveNativeReference(destination, scope, snapshots, media = false) to false)

/** What a list line or a notification puts before the names of files a message only delivers ("📎 report.pdf"): the same in every language. */
internal const val FILES_MARK = "📎 "

/**
 * Reply text as people see it outside the bubble (read aloud, copy, share, the chat list): each `MEDIA:` tag becomes the file's name, never the
 * tag or the folders on the computer. With [filesMark], a message that only delivers files reads as the mark and the names ("📎 report.pdf, data.csv").
 */
internal fun withFileNames(text: String, filesMark: String? = null, keepCode: Boolean = false): String {
    // [keepCode], for Copy, Share and Quote: code shows as written in the bubble, so it is copied as written too (Hermes masks code the same way
    // before it lifts tags). Everywhere the text leaves the bubble as a summary (chat list, read aloud) every tag is a name, code or not, so a
    // path on the computer never shows (models often put the tag itself in inline code).
    val code = if (keepCode) codeSpans(text) else emptyList()
    val tags = mediaTags(text).filter { tag -> code.none { tag.start in it } }
    if (tags.isEmpty()) return text
    val out = StringBuilder()
    var at = 0
    for (tag in tags) { out.append(text, at, tag.start).append(receivedFileName(tag.path)); at = tag.end }
    out.append(text, at, text.length)
    if (filesMark != null) {
        var rest = text
        tags.asReversed().forEach { rest = rest.removeRange(it.start, it.end) }
        if (rest.all { it.isWhitespace() || it in ".,;:!?-–—`\"'*_()[]#>" }) return filesMark + tags.joinToString(", ") { receivedFileName(it.path) }
    }
    return out.toString()
}

// gateway/platforms/base.py _FENCED_CODE_RE and _INLINE_CODE_RE.
private val fencedCode = Regex("```[^\\n]*\\n.*?```", RegexOption.DOT_MATCHES_ALL)
private val inlineCode = Regex("`[^`\\n]+`")
private val mediaBeforeQuote = Regex("MEDIA:\\s*$", RegexOption.IGNORE_CASE)

/** Fenced blocks and inline code in [text], as base.py _mask_protected_spans finds them: a backtick-quoted path right after `MEDIA:` is the tag's own quote, not code. */
internal fun codeSpans(text: String): List<IntRange> =
    fencedCode.findAll(text).map { it.range }.toList() +
        inlineCode.findAll(text).filter { !mediaBeforeQuote.containsMatchIn(text.substring(maxOf(0, it.range.first - 20), it.range.first)) }.map { it.range }

/** What the card calls the file: its own name, never the folders around it. */
internal fun receivedFileName(path: String): String =
    path.trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\').filterNot { it.isISOControl() }.trim().take(200).ifBlank { "File" }

internal fun receivedFileExtension(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT).takeIf { it.isNotEmpty() && it.length <= 10 && it.all(Char::isLetterOrDigit) }.orEmpty()

/** The kind of file, for its icon and for whether the reply shows it as a picture. */
internal enum class FileCategory { Image, Pdf, Document, Spreadsheet, Presentation, Archive, Audio, Video, Text, App, Other }

internal fun receivedFileCategory(name: String): FileCategory = when (receivedFileExtension(name)) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "tiff", "svg", "heic", "heif", "avif" -> FileCategory.Image
    "pdf" -> FileCategory.Pdf
    "docx", "doc", "odt", "rtf", "epub" -> FileCategory.Document
    "xlsx", "xls", "ods", "csv", "tsv" -> FileCategory.Spreadsheet
    "pptx", "ppt", "odp", "key" -> FileCategory.Presentation
    "zip", "tar", "gz", "tgz", "bz2", "xz", "7z", "rar" -> FileCategory.Archive
    "mp3", "m2a", "wav", "ogg", "opus", "m4a", "flac", "aac" -> FileCategory.Audio
    "mp4", "mov", "avi", "mkv", "webm", "3gp" -> FileCategory.Video
    "txt", "md", "json", "xml", "yaml", "yml", "html", "htm", "log", "kml", "geojson", "gpx" -> FileCategory.Text
    "apk", "ipa" -> FileCategory.App
    else -> FileCategory.Other
}

/** The type as a person reads it on the card: the extension (PDF, DOCX, CSV), or File when it has none. */
internal fun receivedFileType(name: String): String = receivedFileExtension(name).uppercase(Locale.ROOT).ifEmpty { "File" }

private val knownMime = mapOf(
    "pdf" to "application/pdf", "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "gif" to "image/gif", "webp" to "image/webp",
    "bmp" to "image/bmp", "tiff" to "image/tiff", "svg" to "image/svg+xml", "heic" to "image/heic", "avif" to "image/avif",
    "mp4" to "video/mp4", "mov" to "video/quicktime", "avi" to "video/x-msvideo", "mkv" to "video/x-matroska", "webm" to "video/webm", "3gp" to "video/3gpp",
    "mp3" to "audio/mpeg", "wav" to "audio/wav", "ogg" to "audio/ogg", "opus" to "audio/opus", "m4a" to "audio/mp4", "flac" to "audio/flac", "aac" to "audio/aac",
    "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "doc" to "application/msword",
    "odt" to "application/vnd.oasis.opendocument.text", "rtf" to "application/rtf", "epub" to "application/epub+zip",
    "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xls" to "application/vnd.ms-excel",
    "ods" to "application/vnd.oasis.opendocument.spreadsheet", "csv" to "text/csv", "tsv" to "text/tab-separated-values",
    "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation", "ppt" to "application/vnd.ms-powerpoint",
    "odp" to "application/vnd.oasis.opendocument.presentation",
    "zip" to "application/zip", "tar" to "application/x-tar", "gz" to "application/gzip", "tgz" to "application/gzip", "bz2" to "application/x-bzip2",
    "xz" to "application/x-xz", "7z" to "application/x-7z-compressed", "rar" to "application/vnd.rar",
    "txt" to "text/plain", "md" to "text/markdown", "json" to "application/json", "xml" to "text/xml", "yaml" to "text/yaml", "yml" to "text/yaml",
    "html" to "text/html", "htm" to "text/html", "kml" to "application/vnd.google-earth.kml+xml", "kmz" to "application/vnd.google-earth.kmz",
    "geojson" to "application/geo+json", "gpx" to "application/gpx+xml",
    "apk" to "application/vnd.android.package-archive",
)

/** The type other apps are told: the name's own type first (Hermes may say application/octet-stream for anything it does not know), then Hermes' word, then a plain byte stream. */
internal fun receivedFileMime(name: String, serverMime: String = ""): String =
    knownMime[receivedFileExtension(name)]
        ?: serverMime.substringBefore(';').trim().lowercase(Locale.ROOT).takeIf { Regex("[a-z0-9.+-]+/[a-z0-9.+-]+").matches(it) && it != "application/octet-stream" }
        ?: "application/octet-stream"

/** A package that would install an app is saved or shared, never opened from a chat with one tap. */
internal fun receivedFileOpens(name: String): Boolean = receivedFileExtension(name) !in setOf("apk", "apks", "xapk", "aab")

internal fun formatFileSize(bytes: Long): String {
    if (bytes < 0) return ""
    if (bytes < 1000) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1000.0
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    val shown = if (value >= 100 || unit == 0) String.format(Locale.ROOT, "%.0f", value) else String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
    return "$shown ${units[unit]}"
}

/** The card's second line: type and, once known, size ("PDF · 24 KB"). */
internal fun receivedFileDetail(name: String, size: Long?): String = listOfNotNull(receivedFileType(name), size?.takeIf { it >= 0 }?.let(::formatFileSize)).joinToString(" · ")

/**
 * Why a file could not be fetched, as the card says it. Hermes answers 404 for a file that is gone, 403 for one the profile may not read and
 * 400 for a folder or anything that is not a regular file (hermes_cli/web_routers/files.py _fs_regular_file, _is_sensitive_path,
 * web_server_files.py _hosted_fs_read_guard). Anything else, a sign-in problem included, is worth another try.
 */
internal enum class ReceivedFileProblem(val text: String) {
    Gone("This file is no longer on Hermes."),
    Refused("Hermes doesn't allow opening this file."),
    TooLarge("This file is too large for this phone."),
    NoSpace("Not enough space on this phone."),
    Changed("This file changed since this message."),
    Offline("Couldn't download. Check your connection and try again."),
}

internal fun receivedFileProblem(status: Int?): ReceivedFileProblem = when (status) {
    404, 410 -> ReceivedFileProblem.Gone
    400, 403 -> ReceivedFileProblem.Refused
    413 -> ReceivedFileProblem.TooLarge
    else -> ReceivedFileProblem.Offline
}

/** A name that is safe on this phone's disk and in Downloads, keeping the extension. */
internal fun safeLocalFileName(name: String): String {
    val cleaned = name.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.').trim()
    val base = cleaned.ifBlank { "file" }
    // Android's file systems keep a name to 255 bytes: 200 leaves room for a temporary's suffix. Cut on whole characters, keeping the type.
    if (base.toByteArray(Charsets.UTF_8).size <= LOCAL_NAME_BYTES) return base
    val extension = receivedFileExtension(base).let { if (it.isEmpty()) "" else ".$it" }
    val stem = if (extension.isEmpty()) base else base.dropLast(extension.length)
    val out = StringBuilder()
    var bytes = extension.length
    var at = 0
    while (at < stem.length) {
        val point = stem.codePointAt(at)
        val width = String(Character.toChars(point)).toByteArray(Charsets.UTF_8).size
        if (bytes + width > LOCAL_NAME_BYTES) break
        out.appendCodePoint(point); bytes += width; at += Character.charCount(point)
    }
    return out.toString().trim().trimEnd('.').ifBlank { "file" } + extension
}

private const val LOCAL_NAME_BYTES = 200

/** The Content-Disposition filename Hermes sends (Starlette FileResponse: filename*=utf-8''… or filename="…"). */
internal fun contentDispositionName(header: String?): String? {
    if (header.isNullOrBlank()) return null
    Regex("filename\\*\\s*=\\s*([^;]+)", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()?.let { value ->
        val encoded = value.substringAfter("''", value).trim('"')
        runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf(String::isNotBlank)?.let { return receivedFileName(it) }
    }
    return Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(header)?.groupValues?.get(1)?.trim()?.takeIf(String::isNotBlank)?.let(::receivedFileName)
}
