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

data class Scope(val origin: String, val owner: String, val profile: String, val session: String = "") {
    val key: String get() = listOf(origin, owner, profile, session).joinToString("\u0000").digest()
    fun session(id: String) = copy(session = id)
}

fun trustedOrigin(input: String): String {
    val url = input.trim().toHttpUrl()
    require(url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.encodedPath == "/" && url.query == null && url.fragment == null) {
        "Enter an HTTPS Cypher origin, without a path, credentials or query."
    }
    return url.toString().trimEnd('/')
}

fun safeArtifactPath(path: String): String {
    require(path.startsWith("/api/") && !path.startsWith("//") && !path.contains('\\') && !path.contains('#') && !path.contains('?')) { "Invalid artifact path" }
    val decoded = java.net.URLDecoder.decode(path, "UTF-8")
    require(decoded.split('/').none { it == ".." || it == "." } && !decoded.contains('\\') && !decoded.contains('%') && decoded.none { it <= ' ' || it == '?' || it == '#' }) { "Invalid artifact path" }
    return path
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

data class QueuedMessage(val text: String, val attachments: List<JSONObject> = emptyList(), val model: String = "", val provider: String = "") {
    fun encode(): JSONObject = json("text" to text, "attachments" to JSONArray(attachments), "model" to model, "provider" to provider)
}
data class Draft(val text: String = "", val selectionStart: Int = 0, val selectionEnd: Int = 0, val attachments: List<JSONObject> = emptyList(), val queued: List<QueuedMessage> = emptyList(), val anchor: Int = 0, val anchorOffset: Int = 0, val anchorId: String = "") {
    fun encode(): String = json("text" to text, "start" to selectionStart, "end" to selectionEnd, "attachments" to JSONArray(attachments), "queue" to JSONArray(queued.map { it.encode() }), "anchor" to anchor, "anchor_offset" to anchorOffset, "anchor_id" to anchorId).toString()
    fun afterAccepted(sent: Draft): Draft = if (text == sent.text && attachments.map { it.toString() } == sent.attachments.map { it.toString() }) copy(text = "", selectionStart = 0, selectionEnd = 0, attachments = emptyList()) else this
    companion object {
        fun decode(raw: String): Draft { val o = JSONObject(raw); val t = o.string("text"); val queue = o.optJSONArray("queue"); val queued = (0 until (queue?.length() ?: 0)).map { i -> val item = queue?.opt(i); if (item is JSONObject) QueuedMessage(item.string("text"), item.rows("attachments"), item.string("model"), item.string("provider")) else QueuedMessage(item?.toString().orEmpty()) }; return Draft(t, o.optInt("start").coerceIn(0, t.length), o.optInt("end").coerceIn(0, t.length), o.rows("attachments"), queued, o.optInt("anchor").coerceAtLeast(0), o.optInt("anchor_offset").coerceAtLeast(0), o.string("anchor_id")) }
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

data class PendingSend(val id: String, val scope: Scope, val draft: Draft, val state: String = "uncertain") {
    fun encode(): String = json("id" to id, "draft" to draft.encode()).toString()
    companion object { fun create(scope: Scope, draft: Draft) = PendingSend(UUID.randomUUID().toString(), scope, draft) }
}

data class Release(val version: Long, val name: String, val packageName: String, val path: String, val sha: String, val size: Long, val signer: String, val minContract: Int, val maxContract: Int) {
    fun validate(installedPackage: String, installedVersion: Long, installedSigner: String, contract: Int = 2) {
        safeArtifactPath(path)
        require(packageName == installedPackage) { "Update belongs to another app" }
        require(version > installedVersion) { "Cypher is already up to date" }
        require(signer.equals(installedSigner, true)) { "Update signing identity differs" }
        require(sha.matches(Regex("[a-fA-F0-9]{64}")) && size in 1..256L * 1024 * 1024) { "Invalid release manifest" }
        require(contract in minContract..maxContract) { "Update is incompatible with this API contract" }
    }
    companion object { fun from(o: JSONObject) = Release(o.getLong("version_code"), o.getString("version_name"), o.getString("package_name"), o.getString("artifact_path"), o.getString("sha256"), o.getLong("size_bytes"), o.getString("certificate_sha256"), o.getInt("min_contract_version"), o.getInt("max_contract_version")) }
}
