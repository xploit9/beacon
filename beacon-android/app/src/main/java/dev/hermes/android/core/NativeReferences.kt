package dev.hermes.android.core

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.Locale
import org.json.JSONObject

sealed interface NativeReference {
    data class LocalMedia(val path: String, val sessionId: String, val snapshot: String?, val endpoint: String = "/api/media") : NativeReference {
        fun query(): Map<String, String> = mapOf("path" to path, "session_id" to sessionId) + (snapshot?.let { mapOf("snap" to it) } ?: emptyMap())
    }
    data class DataImage(val source: String, val mime: String, val base64: Boolean) : NativeReference {
        fun bytes(): ByteArray {
            require(source.length <= DATA_IMAGE_LIMIT)
            val payload = source.substringAfter(',')
            val bytes = if (base64) Base64.getDecoder().decode(payload) else percentBytes(payload)
            require(bytes.size <= DATA_IMAGE_LIMIT)
            return bytes
        }
    }
    data class ExternalMedia(val url: HttpUrl) : NativeReference
    data class HistoryImage(val sessionId: String, val rowId: Long, val imageIndex: Int) : NativeReference
    data class Conversation(val sessionId: String) : NativeReference
    data class Workspace(val path: String, val sessionId: String) : NativeReference
    data class ExternalLink(val url: String) : NativeReference
    data class Unsupported(val source: String, val reason: String) : NativeReference
}

/** Typed attachment receipts contain server filesystem paths, never origin-relative public URLs. */
internal fun resolveAttachmentReference(attachment: JSONObject, scope: Scope, snapshots: Map<String, String> = emptyMap()): NativeReference {
    if (attachment.has("history_row_id")) {
        val row = attachment.optLong("history_row_id")
        val index = attachment.optInt("history_image_index", -1)
        return if (scope.session.isNotBlank() && row > 0 && index in 0..499) NativeReference.HistoryImage(scope.session, row, index)
            else NativeReference.Unsupported("", "This historical image has no valid message identity")
    }
    val path = attachment.optString("path")
    if (path.isBlank()) return resolveNativeReference(attachment.optJSONObject("image_url")?.optString("url")
        ?: attachment.optString("image_url", attachment.optString("url")), scope, snapshots)
    return if (validReferenceText(path, 8192) && scope.session.isNotBlank()) {
        val snapshot = snapshots[path]?.takeIf(::validMediaSnapshot)
        NativeReference.LocalMedia(path, scope.session, snapshot?.lowercase(), "/api/file/raw")
    } else NativeReference.Unsupported(path, "This attachment has no valid originating conversation or file path")
}

/** Adapt Relay PersistedImageReferenceParser's canonical full-line, absolute user image references. */
internal fun persistedImagePaths(content: String): List<String> = content.lineSequence().mapNotNull { line ->
    if (!line.startsWith("@image:")) return@mapNotNull null
    val value = line.removePrefix("@image:")
    val path = if (value.firstOrNull() in listOf('`', '"', '\'')) {
        val quote = value.first()
        if (value.length < 3 || value.last() != quote || quote in value.substring(1, value.lastIndex)) return@mapNotNull null
        value.substring(1, value.lastIndex)
    } else value.takeIf { it.none(Char::isWhitespace) } ?: return@mapNotNull null
    path.takeIf { validReferenceText(it, 8192) && (it.startsWith('/') || Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(it)) &&
        it.substringAfterLast('.').lowercase(Locale.ROOT) in setOf("avif", "bmp", "gif", "heic", "heif", "jpeg", "jpg", "png", "webp") }
}.toList()
const val DATA_IMAGE_LIMIT = 2 * 1024 * 1024
private val digestPattern = Regex("[0-9a-fA-F]{64}")
fun validMediaSnapshot(value: String): Boolean = digestPattern.matches(value)
private fun decoded(value: String) = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
private fun percentBytes(source: String): ByteArray {
    val output = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < source.length) {
        if (source[i] == '%') { require(i + 2 < source.length); output.write(source.substring(i + 1, i + 3).toInt(16)); i += 3 }
        else { require(source[i].code in 32..126); output.write(source[i].code); i++ }
    }
    return output.toByteArray()
}
internal fun sameOrigin(a: HttpUrl, b: HttpUrl) = a.scheme == b.scheme && a.host == b.host && a.port == b.port
/** Any outside image or media (Markdown image or a MEDIA: reference) waits for a tap, so showing a reply never contacts another server. */
internal fun externalMediaNeedsTap(reference: NativeReference, origin: HttpUrl) = reference is NativeReference.ExternalMedia && !sameOrigin(reference.url, origin)
private fun validReferenceText(value: String, max: Int) = value.length in 1..max && value.none { it.code < 32 || it.code == 127 }
private fun validSessionReference(value: String) = validReferenceText(value, 200) && value.none { it in "/\\" || it.isWhitespace() }
private fun fileReferencePath(source: String): String {
    val raw = source.substringAfter("://").substringBefore('?').substringBefore('#')
    val path = if (Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(raw)) decoded(raw) else {
        val uri = URI(source)
        require(uri.rawAuthority.isNullOrEmpty() || uri.host.equals("localhost", true))
        uri.path
    }
    return if (Regex("^/[A-Za-z]:[\\\\/]").containsMatchIn(path)) path.drop(1) else path
}
/** Finite WebUI references are resolved once. Local URLs never supply a different
 * session identity; snapshot selection is exact-path, never a prefix replacement. */
fun resolveNativeReference(raw: String, scope: Scope, snapshots: Map<String, String> = emptyMap(), media: Boolean = true): NativeReference {
    val source = raw.trim().let { if (it.startsWith("MEDIA:", true)) it.drop(6).trim() else it }
    fun denied(reason: String) = NativeReference.Unsupported(source, reason)
    return try {
        require(source.isNotBlank() && source.length <= DATA_IMAGE_LIMIT && source.none { it.code < 32 || it.code == 127 })
        fun local(path: String, endpoint: String = "/api/media", explicitSnapshot: String? = null): NativeReference {
            require(path.isNotBlank() && validReferenceText(path, 8192) && scope.session.isNotBlank())
            val snapshot = snapshots[path]?.takeIf(::validMediaSnapshot) ?: explicitSnapshot?.takeIf(::validMediaSnapshot)
            return NativeReference.LocalMedia(path, scope.session, snapshot?.lowercase(), endpoint)
        }
        when {
            source.startsWith("session://", true) -> { val id = decoded(source.substringAfter("://").substringBefore('?').substringBefore('#')); require(validSessionReference(id)); NativeReference.Conversation(id) }
            source.startsWith("workspace://", true) || source.startsWith("#workspace=") -> { val path = decoded(if (source.startsWith('#')) source.substringAfter('=') else source.substringAfter("://")).removePrefix("~/").removePrefix("./"); require(path.isNotBlank() && validReferenceText(path, 8192) && scope.session.isNotBlank()); NativeReference.Workspace(path, scope.session) }
            source.startsWith("file://", true) -> local(fileReferencePath(source))
            source.startsWith("data:", true) -> {
                val header = source.substringBefore(',').lowercase(Locale.ROOT)
                val mime = header.removePrefix("data:").substringBefore(';')
                val base64 = header.endsWith(";base64")
                require(source.contains(',') && mime in setOf("image/png", "image/jpg", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/svg+xml") && header == "data:$mime" + (if (base64) ";base64" else ""))
                NativeReference.DataImage(source, mime, base64)
            }
            else -> {
                val origin = trustedOrigin(scope.origin).toHttpUrl()
                val url = when { source.startsWith("api/") || source.startsWith("./api/") -> origin.resolve("/${source.removePrefix("./")}"); source.startsWith('/') -> { require(!source.startsWith("//")); origin.resolve(source) }; source.startsWith("https://", true) || source.startsWith("http://", true) -> source.toHttpUrl(); else -> null }
                if (url != null) {
                    require(url.username.isBlank() && url.password.isBlank())
                    val localApi = sameOrigin(url, origin) || url.host in setOf("localhost", "127.0.0.1")
                    when {
                        localApi && url.encodedPath in setOf("/api/media", "/api/file/raw") -> local(url.queryParameter("path").orEmpty(), url.encodedPath, url.queryParameter("snap"))
                        sameOrigin(url, origin) && url.fragment?.startsWith("workspace=") == true -> { val path = url.fragment!!.substringAfter('=').removePrefix("~/").removePrefix("./"); require(validReferenceText(path, 8192) && scope.session.isNotBlank()); NativeReference.Workspace(path, scope.session) }
                        sameOrigin(url, origin) && url.encodedPath.startsWith("/session/") -> { val id = url.pathSegments.drop(1).single(); require(validSessionReference(id)); NativeReference.Conversation(id) }
                        media -> { require(url.scheme == "https"); NativeReference.ExternalMedia(url) }
                        else -> NativeReference.ExternalLink(url.toString())
                    }
                } else if (!media && source.startsWith("session/")) {
                    val id = decoded(source.removePrefix("session/").substringBefore('?').substringBefore('#')); require(validSessionReference(id)); NativeReference.Conversation(id)
                } else if (!media && source.startsWith("mailto:", true) || !media && source.startsWith("tel:", true) || !media && source.startsWith("message:", true)) NativeReference.ExternalLink(source)
                else if (source.contains(Regex("^[A-Za-z][A-Za-z0-9+.-]*:")) && !Regex("^[A-Za-z]:[\\\\/]").containsMatchIn(source)) denied("This reference scheme is not supported")
                else local(source)
            }
        }
    } catch (_: Exception) { denied("This reference is invalid or exceeds the native preview limit") }
}
