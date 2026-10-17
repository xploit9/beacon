package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, override val message: String) : IOException(message)

class CypherClient internal constructor(initial: AuthContext, private val authStore: AuthStore?, private val socketFactory: WebSocket.Factory? = null, private val sharedHttp: OkHttpClient? = null, private val configureHttp: (OkHttpClient.Builder) -> Unit) {
    constructor(initial: AuthContext, authStore: AuthStore? = null) : this(initial, authStore, null, null, {})
    private val origin = trustedOrigin(initial.origin).toHttpUrl()
    val jar = ScopedCookies(origin, initial.cookies)
    var auth: AuthContext = initial; private set
    val scope get() = Scope(auth.origin, auth.owner, auth.profile)
    val http = (sharedHttp?.newBuilder() ?: OkHttpClient.Builder()).cookieJar(jar).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).apply { if (sharedHttp == null) configureHttp(this) }.build()
    private val operationLock = kotlinx.coroutines.sync.Mutex()
    internal suspend fun bindProfile(profile: String) { closeNative(); auth = auth.copy(profile = profile); authStore?.persist(auth) }
    fun detached(): CypherClient = CypherClient(auth.copy(cookies = jar.snapshot()), null, socketFactory, http, configureHttp)
    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\')) { "Invalid API path" }
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        require(!decoded.contains('?') && !decoded.contains('#') && decoded.none { it <= ' ' } && decoded.split('/').none { it == "." || it == ".." }) { "Invalid API path" }
        val resolved = origin.resolve(path) ?: error("Invalid API path")
        require(resolved.host == origin.host && resolved.scheme == origin.scheme && resolved.port == origin.port) { "Cross-origin request rejected" }
        return resolved.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }
    fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: RequestBody? = null): Request.Builder = Request.Builder().url(url(path, query)).apply { require(NativeOperationPolicy.direct(path)) { "Unsupported native endpoint" } }.header("Accept", "application/json").apply {
        method(method, body)
    }
    private val native = NativeGateway(this, socketFactory ?: http)
    suspend fun nativeRpc(method: String, params: JSONObject = JSONObject()): JSONObject = native.rpc(method, params)
    suspend fun nativeRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        require(NativeOperationPolicy.direct(path)) { "Unsupported native endpoint" }
        if (body?.has("profile") == true) require(body.string("profile") == auth.profile) { "Native request belongs to another profile" }
        return rawCall(path, query = if (path.startsWith("/api/plugins/cypher/")) mapOf("profile" to auth.profile) else emptyMap(), method = method, data = body)
    }
    suspend fun nativeCallContext(callId: String): JSONObject = kotlinx.coroutines.withTimeout(20_000) {
        val path = "/api/plugins/cypher/mobile/calls/$callId/context"
        try { nativeRequest(path) }
        catch (error: ApiFailure) {
            if (error.status !in setOf(429, 502, 503, 504)) throw error
            // Retry only this read: a cold native lookup can outlast its server deadline.
            kotlinx.coroutines.delay(300)
            nativeRequest(path)
        }
    }
    internal suspend fun nativeReplay(lastId: String = "", beforePrompt: Boolean = false) = native.prepareReplay(lastId, beforePrompt)
    internal suspend fun nativeCreate(params: JSONObject): NativeSessionBinding = native.create(params)
    internal fun nativeSessionSnapshot() = native.sessionSnapshot()
    suspend fun nativeSession(storedSessionId: String?): NativeSessionBinding = native.session(storedSessionId)
    suspend fun nativeEvents(onEvent: suspend (JSONObject) -> Unit) = native.events(onEvent)
    fun closeNative() = native.close()
    private suspend fun <T> execute(request: Request, read: suspend (Response) -> T): T {
        val call = http.newCall(request)
        // Cancellation must close a stalled body too, after response headers have arrived.
        val watcher = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launchCancel(call)
        try { return call.execute().use { read(it) } }
        catch (error: IOException) { currentCoroutineContext().ensureActive(); throw error }
        finally { watcher.cancel(); call.cancel() }
    }
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), maxBytes: Int = 32 * 1024 * 1024): JSONObject = call(path, query, maxBytes = maxBytes)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call(path, method = "POST", data = body)
    private suspend fun rawCall(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024): JSONObject = withContext(Dispatchers.IO) {
        require(maxBytes in 1..32 * 1024 * 1024) { "Invalid response size limit" }
        val body = data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        execute(request(path, query, method, body).build()) { response ->
            val input = response.body?.source()
            val payload = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32768)
            if (input != null) while (true) { currentCoroutineContext().ensureActive(); val n = input.read(chunk); if (n < 0) break; require(payload.size().toLong() + n <= maxBytes) { "Server response exceeds the size limit" }; payload.write(chunk, 0, n) }
            val raw = payload.toString("UTF-8")
            val result = runCatching { JSONObject(raw) }.getOrElse { if (raw.trim().startsWith('[')) json("items" to org.json.JSONArray(raw)) else json("message" to raw.take(2048)) }
            if (!response.isSuccessful || result.has("error") && !result.isNull("error") && result.string("error").isNotBlank()) throw ApiFailure(response.code, nativeErrorMessage(result, response.code))
            auth = auth.copy(cookies = jar.snapshot())
            authStore?.persist(auth)
            result
        }
    }
    suspend fun call(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024): JSONObject = operationLock.withLock { NativeOperations(this).call(path, query, method, data, maxBytes) }
    internal suspend fun nativeGet(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024): JSONObject {
        require(NativeOperationPolicy.direct(path)) { "This capability is unavailable in native Hermes" }
        if (query.containsKey("profile")) require(query["profile"] == auth.profile) { "Native request belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == auth.profile) { "Native request belongs to another profile" }
        val scoped = if (path.startsWith("/api/plugins/cypher/")) query + ("profile" to auth.profile) else query
        return rawCall(path, scoped, method, data, maxBytes)
    }
    suspend fun login(password: String, provider: String = "", username: String = ""): JSONObject {
        val providers = nativeGet("/api/auth/providers")
        val rows = providers.optJSONArray("providers") ?: org.json.JSONArray()
        val passwordProviders = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("type") == "password" || it.optBoolean("supports_password") }
        val selected = provider.ifBlank { passwordProviders.singleOrNull()?.let { it.optString("name").ifBlank { it.optString("id") } }.orEmpty() }
        require(selected.isNotBlank() && username.isNotBlank()) { "Native sign-in requires your provider and username" }
        nativeGet("/auth/password-login", method = "POST", data = json("provider" to selected, "username" to username, "password" to password, "next" to "/"))
        return bootstrap()
    }
    suspend fun bootstrap(): JSONObject {
        val identity = nativeRequest("/api/auth/me")
        val payload = nativeGet("/api/plugins/cypher/mobile/bootstrap", mapOf("profile" to auth.profile))
        require(payload.getInt("contract_version") == 2 && payload.string("backend") == "hermes-dashboard") { "This server needs the native Cypher dashboard plugin" }
        val owner = identity.getString("user_id")
        if (owner.isBlank() || owner != payload.getString("owner_id")) throw CachedIdentityMismatch()
        auth = auth.copy(owner = owner, profile = payload.string("profile", auth.profile), csrf = "", cookies = jar.snapshot(), backend = "hermes-dashboard")
        authStore?.persist(auth)
        return payload
    }
    suspend fun logout() { try { nativeRequest("/auth/logout", "POST", JSONObject()) } finally { closeNative(); authStore?.remove(auth.origin) } }
    suspend fun stream(path: String, query: Map<String, String>, lastId: String = "", onEvent: suspend (SseEvent) -> Unit) {
        require(path in setOf("/api/chat/stream", "/api/session/stream")) { "Streaming this capability is unavailable in native Hermes" }
        NativeOperations(detached()).stream(path, query, lastId, onEvent)
    }
    suspend fun upload(path: String, file: File, name: String, mime: String, sessionId: String, directory: String = "."): JSONObject = withContext(Dispatchers.IO) {
        operationLock.withLock {
            try {
                require(file.length() <= 32L * 1024 * 1024) { "Attachment exceeds 32 MiB" }
                require(path in setOf("/api/upload", "/api/workspace/upload")) { "This upload capability is unavailable in native Hermes" }
                require(validUploadName(name)) { "Choose a filename without quotes, slashes, dollar signs, percent signs or control characters." }
                val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                if (path == "/api/upload") {
                    json("file" to json("id" to java.util.UUID.randomUUID().toString(), "state" to "ready", "scope_key" to scope.session(sessionId).key, "staged_base64" to encoded, "name" to name, "mime" to mime))
                } else {
                    require(path == "/api/workspace/upload") { "Native chat attachments currently support images; upload other files in Spaces" }
                    val nativePath = NativeOperations(this@CypherClient).workspacePath(sessionId, directory.trimEnd('/') + "/" + name)
                    nativeRequest("/api/files/upload", "POST", json("path" to nativePath, "data_url" to "data:$mime;base64,$encoded", "overwrite" to false))
                }
            } finally { closeNative() }
        }
    }
    suspend fun copyTo(path: String, output: OutputStream, query: Map<String, String> = emptyMap(), data: JSONObject? = null, limit: Long = 32L * 1024 * 1024): Long = withContext(Dispatchers.IO) {
        val mapped = if (path == "/api/file/raw") "/api/fs/download" else path
        require(NativeOperationPolicy.direct(mapped)) { "This download capability is unavailable in native Hermes" }
        if (query.containsKey("profile")) require(query["profile"] == auth.profile) { "Native download belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == auth.profile) { "Native download belongs to another profile" }
        val scopedQuery = (query - "session_id").let { if (mapped.startsWith("/api/plugins/cypher/")) it + ("profile" to auth.profile) else it }
        execute(request(mapped, scopedQuery, if (data == null) "GET" else "POST", data?.toString()?.toRequestBody("application/json".toMediaType())).build()) { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "Download failed (${response.code})")
            val body = response.body ?: error("Missing file")
            require(body.contentLength() <= limit) { "Download exceeds permitted size" }
            copyDownload(body.byteStream(), output, limit)
        }
    }

    suspend fun bytes(path: String, query: Map<String, String> = emptyMap(), data: JSONObject? = null, limit: Long = 32L * 1024 * 1024): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copyTo(path, output, query, data, limit)
        return output.toByteArray()
    }
}

internal fun validUploadName(name: String): Boolean = name.isNotBlank() && name !in setOf(".", "..") &&
    name.none { it == '/' || it == '\\' || it == '"' || it == '$' || it == '%' || it.isISOControl() }

internal suspend fun copyDownload(input: InputStream, output: OutputStream, limit: Long): Long {
    require(limit >= 0) { "Invalid download limit" }
    val buffer = ByteArray(32 * 1024)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val count = input.read(buffer)
        if (count < 0) return total
        require(count.toLong() <= limit - total) { "Download exceeds permitted size" }
        output.write(buffer, 0, count)
        total += count
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchCancel(call: Call) = this.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() } }

