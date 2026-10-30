package dev.hermes.android.core

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

fun json(vararg entries: Pair<String, Any?>): JSONObject = JSONObject().apply { entries.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) } }
fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }
fun JSONObject.rows(key: String): List<JSONObject> = optJSONArray(key)?.objects().orEmpty()
fun JSONObject.string(key: String, default: String = ""): String = if (isNull(key)) default else optString(key, default)
fun JSONObject.copy(): JSONObject = JSONObject(toString())
fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
fun sha256(file: java.io.File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32 * 1024)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
fun String.digest(): String = sha256(toByteArray())

data class Scope(val origin: String, val owner: String, val profile: String, val session: String = "", val nativeProvider: String = "") {
    val key: String get() = if (nativeProvider.isEmpty()) listOf(origin, owner, profile, session).joinToString("\u0000").digest()
        else JSONArray(listOf(origin, nativeProvider, owner, profile, session)).toString().digest()
    fun session(id: String) = copy(session = id)
    fun sameAccount(other: Scope): Boolean = origin == other.origin && owner == other.owner && nativeProvider == other.nativeProvider
}

fun trustedOrigin(input: String): String {
    val url = input.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "Paste the https:// link from your Hermes dashboard, with nothing after the address."
    }
    return url.toString().trimEnd('/')
}

data class SseEvent(val type: String, val data: String, val id: String = "") {
    val terminal get() = type in setOf("stream_end", "cancel", "error", "apperror")
    fun payload(): JSONObject = runCatching { JSONObject(data) }.getOrElse { json("text" to data) }
}

class ReplayCursor(private val stream: String, last: String = "") {
    var lastId: String = last; private set
    private val seen = LinkedHashSet<String>().apply { if (last.isNotEmpty()) add(last) }
    fun accept(event: SseEvent): Boolean {
        if (event.id.isEmpty()) return true
        val prefix = event.id.substringBeforeLast(':', "")
        val seq = event.id.substringAfterLast(':').toLongOrNull()
        val lastPrefix = lastId.substringBeforeLast(':', "")
        val lastSeq = lastId.substringAfterLast(':').toLongOrNull()
        if (prefix == stream && lastPrefix == stream && seq != null && lastSeq != null && seq <= lastSeq) return false
        if (!seen.add(event.id)) return false
        if (seen.size > 4096) seen.remove(seen.first())
        lastId = event.id
        return true
    }
}

data class QueuedMessage(
    val text: String, val attachments: List<JSONObject> = emptyList(), val model: String = "", val provider: String = "",
    val id: String = UUID.randomUUID().toString(), val createdAt: Long = System.currentTimeMillis(),
    val state: String = "queued", val kind: String = "queue", val rowId: String = "", val admissionId: String = "", val outcome: String = "", val submissionId: String = id,
) {
    fun encode(): JSONObject = json("text" to text, "attachments" to JSONArray(attachments), "model" to model, "provider" to provider,
        "id" to id, "created_at" to createdAt, "state" to state, "kind" to kind, "row_id" to rowId, "admission_id" to admissionId, "outcome" to outcome, "submission_id" to submissionId)
    companion object {
        fun decode(item: Any?): QueuedMessage {
            if (item !is JSONObject) return QueuedMessage(item?.toString().orEmpty(), createdAt = 0L)
            val state = item.string("state", "queued").let { if (it in setOf("saving", "sending")) "unknown" else it }
                .takeIf { it in setOf("queued", "accepted", "unknown", "sent", "settled") } ?: "unknown"
            val id = item.string("id").ifBlank { UUID.randomUUID().toString() }
            return QueuedMessage(item.string("text"), item.rows("attachments"), item.string("model"), item.string("provider"),
                id, item.optLong("created_at").coerceAtLeast(0), state, item.string("kind").takeIf { it in setOf("queue", "steer") } ?: "queue",
                item.string("row_id"), item.string("admission_id"), item.string("outcome"), item.string("submission_id").ifBlank { id })
        }
    }
}
data class Draft(val text: String = "", val selectionStart: Int = 0, val selectionEnd: Int = 0, val attachments: List<JSONObject> = emptyList(), val queued: List<QueuedMessage> = emptyList(), val anchor: Int = 0, val anchorOffset: Int = 0, val anchorId: String = "") {
    fun encode(): String = json("text" to text, "start" to selectionStart, "end" to selectionEnd, "attachments" to JSONArray(attachments), "queue" to JSONArray(queued.map { it.encode() }), "anchor" to anchor, "anchor_offset" to anchorOffset, "anchor_id" to anchorId).toString()
    fun afterAccepted(sent: Draft): Draft = if (text == sent.text && attachments.map { it.toString() } == sent.attachments.map { it.toString() }) copy(text = "", selectionStart = 0, selectionEnd = 0, attachments = emptyList()) else this
    companion object {
        fun decode(raw: String): Draft { val o = JSONObject(raw); val t = o.string("text"); val queue = o.optJSONArray("queue"); val queued = (0 until (queue?.length() ?: 0)).map { i -> QueuedMessage.decode(queue?.opt(i)) }; return Draft(t, o.optInt("start").coerceIn(0, t.length), o.optInt("end").coerceIn(0, t.length), o.rows("attachments"), queued, o.optInt("anchor").coerceAtLeast(0), o.optInt("anchor_offset").coerceAtLeast(0), o.string("anchor_id")) }
    }
}

sealed interface SendReceipt {
    data class Accepted(val sessionId: String, val streamId: String) : SendReceipt
    data object Pending : SendReceipt
    data object Failed : SendReceipt
    companion object {
        fun parse(value: JSONObject, scope: Scope): SendReceipt = when (value.string("state")) {
            "accepted" -> { val session = value.string("session_id"); val stream = value.string("stream_id"); require(session == scope.session && stream.isNotBlank()) { "The send receipt does not yet resolve to this conversation" }; Accepted(session, stream) }
            "failed" -> Failed
            "pending" -> Pending
            else -> error("The server returned an unknown send receipt. Keep the retained draft and check again.")
        }
    }
}

data class PendingSend(val id: String, val scope: Scope, val draft: Draft, val state: String = "uncertain",
    val createdAt: Long = System.currentTimeMillis(), val model: String = "", val provider: String = "", val queued: Boolean = false,
    val rowId: String = "", val admissionId: String = "", val submissionId: String = id) {
    fun encode(): String = json("id" to id, "draft" to draft.encode(), "state" to state, "created_at" to createdAt,
        "model" to model, "provider" to provider, "queued" to queued, "row_id" to rowId, "admission_id" to admissionId, "submission_id" to submissionId).toString()
    companion object {
        fun create(scope: Scope, draft: Draft, queued: QueuedMessage? = null): PendingSend {
            val id = queued?.id ?: UUID.randomUUID().toString()
            return PendingSend(id, scope, draft, createdAt = queued?.createdAt ?: System.currentTimeMillis(), model = queued?.model.orEmpty(),
                provider = queued?.provider.orEmpty(), queued = queued != null, submissionId = queued?.submissionId ?: id)
        }
        fun decode(scope: Scope, raw: String): PendingSend = JSONObject(raw).let { o -> PendingSend(o.getString("id"), scope, Draft.decode(o.getString("draft")),
            o.string("state", "uncertain"), o.optLong("created_at").coerceAtLeast(0), o.string("model"), o.string("provider"), o.optBoolean("queued"), o.string("row_id"), o.string("admission_id"), o.string("submission_id").ifBlank { o.getString("id") }) }
    }
}

const val MAX_UPDATE_APK_BYTES = 256L * 1024 * 1024
/** The release-version rule for app and plugin (major.minor.patch), bounded so each part fits an Int. */
val PLUGIN_VERSION_PATTERN = Regex("(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})")

/** One public GitHub release: this app version and the Hermes plugin version it ships with. [url] is the APK asset's download address. */
data class Release(val version: Long, val name: String, val packageName: String, val url: String, val sha: String, val size: Long, val signer: String, val pluginVersion: String, val notes: String = "", val published: String = "") {
    fun validate(installedPackage: String, installedVersion: Long, installedSigner: String) {
        require(url.isNotBlank()) { "Invalid release manifest" }
        require(packageName == installedPackage) { "Update belongs to another app" }
        require(version > installedVersion) { "Beacon is already up to date" }
        require(signer.equals(installedSigner, true)) { "Update signing identity differs" }
        require(sha.matches(Regex("[a-fA-F0-9]{64}")) && size in 1..MAX_UPDATE_APK_BYTES) { "Invalid release manifest" }
        require(pluginVersion.matches(PLUGIN_VERSION_PATTERN)) { "Invalid release manifest" }
    }
    /** Phone-local copy, so a 304 from GitHub can still show the release. */
    fun encode(): String = json("version_code" to version, "version_name" to name, "package_name" to packageName, "download_url" to url, "sha256" to sha,
        "size_bytes" to size, "certificate_sha256" to signer, "plugin_version" to pluginVersion, "notes" to notes, "published_at" to published).toString()
    companion object {
        fun from(metadata: JSONObject, url: String, notes: String, published: String) = Release(metadata.getLong("version_code"), metadata.getString("version_name"), metadata.getString("package_name"), url,
            metadata.getString("sha256"), metadata.getLong("size_bytes"), metadata.getString("certificate_sha256"), metadata.getString("plugin_version"), notes, published)
        fun decode(raw: String?): Release? = runCatching { JSONObject(raw!!).let { from(it, it.getString("download_url"), it.string("notes"), it.string("published_at")) } }.getOrNull()
    }
}
