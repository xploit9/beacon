package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

class ApiFailure(val status: Int, override val message: String) : IOException(message)
internal class LoopbackSessionRefreshFailed : IOException("Hermes restarted. Scan the Connect QR again.")
internal const val HERMES_LOOPBACK_SESSION_HEADER = "X-Hermes-Session-Token"
private const val MAX_LOOPBACK_ROOT_BYTES = 256L * 1024L
private const val CYPHER_PLUGIN_PATH = "/api/plugins/cypher/"
private val LOOPBACK_SESSION_ASSIGNMENT = Regex("""window\.__HERMES_SESSION_TOKEN__\s*=\s*("(?:\\.|[^"\\])*")\s*;""")

// Hermes reserves `profile` for plugin routing; Cypher uses its own key for the selected data profile.
private fun cypherPluginProfileQuery(query: Map<String, String>, profile: String) =
    (query - "profile" - "cypher_profile") + ("cypher_profile" to profile)

internal fun parseLoopbackSessionToken(html: String): String {
    val literal = LOOPBACK_SESSION_ASSIGNMENT.find(html)?.groupValues?.getOrNull(1) ?: throw LoopbackSessionRefreshFailed()
    val token = runCatching { JSONArray("[$literal]").getString(0) }.getOrNull()
    if (!validLoopbackSessionToken(token.orEmpty())) throw LoopbackSessionRefreshFailed()
    return token!!
}

private fun Response.isLoopbackSessionRejection() = runCatching {
    JSONObject(peekBody(4 * 1024L).string()).optString("detail") == "Unauthorized"
}.getOrDefault(false)

class CypherClient internal constructor(initial: AuthContext, private val authStore: AuthStore?, private val socketFactory: WebSocket.Factory? = null, private val sharedHttp: OkHttpClient? = null, private val detachedView: Boolean = false, private val persistContext: Boolean = true, private val credentialSource: CypherClient? = null, private val configureHttp: (OkHttpClient.Builder) -> Unit) {
    constructor(initial: AuthContext, authStore: AuthStore? = null, persistContext: Boolean = true) : this(initial, authStore, null, null, persistContext = persistContext, configureHttp = {})
    private val origin = trustedOrigin(initial.origin).toHttpUrl()
    val jar = ScopedCookies(origin, initial.cookies) { auth.bearer != null || auth.loopbackSession != null }
    @Volatile var auth: AuthContext = initial; private set
    val scope get() = Scope(auth.origin, auth.owner, auth.profile)
    // A coroutine Mutex: the refresh suspends (network read with timeout) while holding the lock.
    private val loopbackRefreshLock = kotlinx.coroutines.sync.Mutex()
    val http: OkHttpClient = (sharedHttp?.newBuilder() ?: OkHttpClient.Builder()).cookieJar(jar).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).apply {
        if (sharedHttp == null) { addInterceptor(bearerAuthInterceptor()); configureHttp(this) }
    }.build()
    private val nativeAuth = NativeAuth(auth.origin, http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build())
    private val operationLock = kotlinx.coroutines.sync.Mutex()
    internal suspend fun bindProfile(profile: String) {
        closeNative(); auth = auth.copy(profile = profile, bearer = auth.bearer?.copy(profile = profile))
        authStore?.takeIf { !detachedView }?.let { it.persist(auth); markLoopbackSessionPersisted() }
    }
    internal fun markLoopbackSessionPersisted() {
        auth.loopbackSession?.takeIf { it.verified }?.let { auth = auth.copy(loopbackSession = it.copy(persisted = true)) }
    }
    fun detached(): CypherClient = CypherClient(auth.copy(cookies = jar.snapshot()), authStore, socketFactory, http, detachedView = true, persistContext = persistContext, configureHttp = configureHttp, credentialSource = credentialSource)
    /** A short-lived profile view shares the signed-in account's refresh owner without persisting a profile switch. */
    internal fun forAuthorizedProfile(profile: String): CypherClient {
        require(validNativeProfile(profile) && auth.owner.isNotBlank()) { "Invalid native profile scope" }
        val source = credentialSource ?: this
        require(source.auth.origin == auth.origin && source.auth.owner == auth.owner) { "Native profile belongs to another account" }
        return CypherClient(
            auth.copy(profile = profile, bearer = source.auth.bearer?.copy(profile = profile), loopbackSession = source.auth.loopbackSession, cookies = jar.snapshot()),
            authStore, socketFactory, source.http, detachedView = true, persistContext = false,
            configureHttp = configureHttp, credentialSource = source,
        )
    }
    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\')) { "Invalid API path" }
        val decoded = java.net.URLDecoder.decode(path, "UTF-8")
        require(!decoded.contains('?') && !decoded.contains('#') && decoded.none { it <= ' ' } && decoded.split('/').none { it == "." || it == ".." }) { "Invalid API path" }
        val resolved = origin.resolve(path) ?: error("Invalid API path")
        require(resolved.host == origin.host && resolved.scheme == origin.scheme && resolved.port == origin.port) { "Cross-origin request rejected" }
        return resolved.newBuilder().apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }.build()
    }
    private fun requestAuth(): AuthContext {
        val source = credentialSource?.auth ?: return auth
        if (source.origin != auth.origin || source.owner != auth.owner) return auth
        return auth.copy(bearer = source.bearer?.copy(profile = auth.profile), loopbackSession = source.loopbackSession, cookies = jar.snapshot())
    }
    fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: RequestBody? = null): Request.Builder = Request.Builder().url(url(path, query)).apply {
        require(NativeOperationPolicy.direct(path, method)) { "Unsupported native endpoint" }
        require(path != "/" || method == "GET" && body == null && query.isEmpty()) { "Unsupported native endpoint" }
    }.header("Accept", "application/json").apply {
        val session = requestAuth()
        if (path != "/api/ws") {
            val bearer = session.bearer?.takeIf { it.accessToken.isNotBlank() && it.accessToken.none { ch -> ch.isWhitespace() || ch.isISOControl() } && it.origin == session.origin && it.owner == session.owner && it.profile == session.profile }
            val loopback = session.loopbackSession?.takeIf { it.origin == session.origin && it.owner == session.owner && it.owner == "local" && session.backend == "hermes-dashboard" && validLoopbackSessionToken(it.token) }
            if (bearer != null) header("Authorization", "Bearer " + bearer.accessToken)
            else loopback?.let { header(HERMES_LOOPBACK_SESSION_HEADER, it.token) }
        }
        method(method, body)
    }
    internal suspend fun readLoopbackSessionToken(): LoopbackSessionRecord = withContext(Dispatchers.IO) {
        val root = request("/", method = "GET").header("Accept", "text/html")
            .removeHeader("Authorization").removeHeader(HERMES_LOOPBACK_SESSION_HEADER).build()
        execute(root) { response ->
            if (!response.isSuccessful || !response.header("Content-Type").orEmpty().startsWith("text/html", ignoreCase = true)) throw LoopbackSessionRefreshFailed()
            val html = response.peekBody(MAX_LOOPBACK_ROOT_BYTES).string()
            LoopbackSessionRecord(parseLoopbackSessionToken(html), auth.origin, "local")
        }
    }
    private val native = NativeGateway(this, socketFactory ?: http)
    suspend fun nativeRpc(method: String, params: JSONObject = JSONObject()): JSONObject = native.rpc(method, params)
    suspend fun nativeRequest(path: String, method: String = "GET", body: JSONObject? = null): JSONObject {
        require(NativeOperationPolicy.direct(path, method)) { "Unsupported native endpoint" }
        if (body?.has("profile") == true) require(body.string("profile") == auth.profile) { "Native request belongs to another profile" }
        val query = if (path.startsWith(CYPHER_PLUGIN_PATH)) cypherPluginProfileQuery(emptyMap(), auth.profile) else emptyMap()
        return rawCall(path, query = query, method = method, data = body)
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
    internal suspend fun nativeSessionStatus(storedSessionId: String, runtimeSessionId: String): String? {
        val identity = Regex("[A-Za-z0-9_.:-]{1,256}")
        if (!storedSessionId.matches(identity) || !runtimeSessionId.matches(identity)) return null
        val rows = nativeRpc("session.active_list", json("current_session_id" to runtimeSessionId)).optJSONArray("sessions") ?: return null
        val sessions = (0 until rows.length()).map { rows.optJSONObject(it) ?: return null }
        val runtimeRows = sessions.filter { it.opt("id") == runtimeSessionId }
        val storedRows = sessions.filter { it.opt("session_key") == storedSessionId }
        val row = runtimeRows.singleOrNull() ?: return null
        if (storedRows.singleOrNull() !== row) return null
        return (row.opt("status") as? String)?.takeIf { it in setOf("idle", "working", "waiting") }
    }
    suspend fun nativeEvents(onEvent: suspend (JSONObject) -> Unit) = native.events(onEvent)
    fun closeNative() = native.close()
    private fun bearerAuthInterceptor() = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        val path = request.url.encodedPath
        val pluginRequest = path.startsWith("/api/plugins/")
        if (response.code != 401 || path.startsWith("/auth/")) return@Interceptor response
        val loopback = auth.loopbackSession?.takeIf {
                it.origin == auth.origin && it.owner == auth.owner && it.owner == "local" &&
                auth.backend == "hermes-dashboard" && validLoopbackSessionToken(it.token) &&
                request.header(HERMES_LOOPBACK_SESSION_HEADER) != null
        }
        if (loopback != null && (!pluginRequest || response.isLoopbackSessionRejection())) {
            response.close()
            if (request.body?.isOneShot() == true) throw LoopbackSessionRefreshFailed()
            val replacement = try {
                kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                    loopbackRefreshLock.withLock {
                        val latest = (if (loopback.persisted && authStore != null) authStore.read(loopback.origin).loopbackSession else auth.loopbackSession)
                            ?.takeIf { it.origin == loopback.origin && it.owner == loopback.owner }
                        if (loopback.persisted && latest != null && latest != loopback) latest
                        else {
                            val refreshed = kotlinx.coroutines.withTimeoutOrNull(30_000) { readLoopbackSessionToken() }
                                ?.copy(verified = loopback.verified, persisted = loopback.persisted) ?: throw LoopbackSessionRefreshFailed()
                            when {
                                auth.loopbackSession == loopback && (!loopback.verified || !loopback.persisted || authStore == null) -> {
                                    auth = auth.copy(loopbackSession = refreshed)
                                    refreshed
                                }
                                loopback.verified && loopback.persisted && authStore != null && authStore.replaceLoopbackSession(loopback, refreshed) -> {
                                    auth = auth.copy(loopbackSession = refreshed)
                                    refreshed
                                }
                                loopback.verified && loopback.persisted && authStore != null -> authStore.read(loopback.origin).loopbackSession
                                    ?.takeIf { it.origin == loopback.origin && it.owner == loopback.owner && it != loopback }
                                    ?.also { auth = auth.copy(loopbackSession = it) }
                                    ?: throw LoopbackSessionRefreshFailed()
                                else -> throw LoopbackSessionRefreshFailed()
                            }
                        }
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { throw LoopbackSessionRefreshFailed() }
            val retried = chain.proceed(request.newBuilder().header(HERMES_LOOPBACK_SESSION_HEADER, replacement.token).build())
            if (retried.code == 401 && (!pluginRequest || retried.isLoopbackSessionRejection())) {
                retried.close()
                throw LoopbackSessionRefreshFailed()
            }
            return@Interceptor retried
        }
        if (request.body?.isOneShot() == true) return@Interceptor response
        val bearer = auth.bearer
        val authorization = request.header("Authorization")
        if (bearer == null || !authorization.orEmpty().startsWith("Bearer ")) return@Interceptor response
        if (pluginRequest && !response.isExpiredSessionEnvelope()) return@Interceptor response
        val stored = if (authStore == null) bearer else authStore.read(bearer.origin).bearer
        val current = stored?.takeIf { it.origin == bearer.origin && it.owner == bearer.owner && it.profile == bearer.profile }
        if (current != null && authorization != "Bearer ${current.accessToken}") {
            auth = auth.copy(bearer = current)
            response.close()
            return@Interceptor chain.proceed(request.newBuilder().header("Authorization", "Bearer ${current.accessToken}").build())
        }
        if (authorization != "Bearer ${bearer.accessToken}") return@Interceptor response
        val refreshed = try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                nativeAuth.refresh(
                    current = bearer,
                    latest = { if (authStore == null) auth.bearer else authStore.read(bearer.origin).bearer },
                    persist = { expected, updated ->
                        if (authStore == null) {
                            if (auth.bearer != expected) false else { auth = auth.copy(bearer = updated); true }
                        } else {
                            val saved = authStore.replaceBearer(expected, updated)
                            if (saved) auth = auth.copy(bearer = updated)
                            saved
                        }
                    },
                )
            }
        } catch (error: NativeAuthException) {
            response.close()
            if (!error.sessionExpired) throw error
            if (authStore?.removeIfBearerUnchanged(bearer) != false) auth = auth.copy(bearer = null, cookies = emptyList())
            throw NativeAuthExpiredException()
        }
        response.close()
        chain.proceed(request.newBuilder().header("Authorization", "Bearer ${refreshed.accessToken}").build())
    }
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
    private suspend fun rawCall(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024, persistAuth: Boolean = true): JSONObject = withContext(Dispatchers.IO) {
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
            // An optional probe must not rewrite the verified native auth context or its persisted cookies.
            if (persistAuth && !detachedView && auth.loopbackSession?.verified != false) {
                auth = auth.copy(cookies = jar.snapshot())
                if (persistContext) authStore?.let { it.persist(auth); markLoopbackSessionPersisted() }
            }
            result
        }
    }
    suspend fun call(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024): JSONObject = operationLock.withLock { NativeOperations(this).call(path, query, method, data, maxBytes) }
    internal suspend fun nativeGet(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024, persistAuth: Boolean = true): JSONObject {
        require(NativeOperationPolicy.direct(path, method)) { "This capability is unavailable in native Hermes" }
        if (query.containsKey("profile")) require(query["profile"] == auth.profile) { "Native request belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == auth.profile) { "Native request belongs to another profile" }
        val scoped = if (path.startsWith(CYPHER_PLUGIN_PATH)) cypherPluginProfileQuery(query, auth.profile) else query
        return rawCall(path, scoped, method, data, maxBytes, persistAuth)
    }
    internal suspend fun nativeAuthorizedProfiles(): List<String> = nativeProfileCatalog() ?: listOf(auth.profile)
    internal suspend fun nativeGetForProfile(
        path: String,
        profile: String,
        authorizedProfiles: List<String>,
        query: Map<String, String> = emptyMap(),
        maxBytes: Int = 32 * 1024 * 1024,
    ): JSONObject {
        require(NativeOperationPolicy.direct(path, "GET") && validNativeProfile(profile) && profile in authorizedProfiles) {
            "Native request belongs to an unauthorized profile"
        }
        val scoped = if (path.startsWith(CYPHER_PLUGIN_PATH)) cypherPluginProfileQuery(query, profile)
        else (query - "cypher_profile") + ("profile" to profile)
        return rawCall(path, scoped, maxBytes = maxBytes, persistAuth = false)
    }
    /** Retained for existing direct native-protocol callers; the app's sign-in UI uses NativeAuth OAuth. */
    @Deprecated("The app's sign-in flow uses Hermes OAuth")
    internal suspend fun login(password: String, provider: String = "", username: String = ""): JSONObject {
        val providers = nativeGet("/api/auth/providers")
        val rows = providers.optJSONArray("providers") ?: org.json.JSONArray()
        val passwordProviders = (0 until rows.length()).map { rows.getJSONObject(it) }.filter { it.optString("type") == "password" || it.optBoolean("supports_password") }
        val selected = provider.ifBlank { passwordProviders.singleOrNull()?.let { it.optString("name").ifBlank { it.optString("id") } }.orEmpty() }
        require(selected.isNotBlank() && username.isNotBlank()) { "Native sign-in requires your provider and username" }
        nativeGet("/auth/password-login", method = "POST", data = json("provider" to selected, "username" to username, "password" to password, "next" to "/"))
        auth = auth.copy(owner = "", csrf = "", backend = "", bearer = null, cookies = jar.snapshot())
        return bootstrap()
    }
    /** Native dashboard verification only. This deliberately makes no optional-plugin request. */
    suspend fun bootstrap(): JSONObject {
        val identity = nativeGet("/api/auth/me")
        val owner = identity.string("user_id").trim()
        require(owner.isNotBlank()) { "This server did not return a verified native identity" }
        if (auth.loopbackSession != null && (owner != "local" || identity.optString("provider") != "loopback")) throw LoopbackSessionRefreshFailed()
        if (auth.bearer?.owner?.let { it.isNotBlank() && it != owner } == true) throw CachedIdentityMismatch()
        // A vault belongs to one native account. A different verified user must authenticate again.
        if (auth.owner.isNotBlank() && auth.owner != owner) throw CachedIdentityMismatch()
        val profile = resolveNativeProfile(nativeProfileCatalog())
        // Keep the /api/auth/me cookie rotation in the verified native context.
        auth = auth.copy(owner = owner, profile = profile, csrf = "", cookies = jar.snapshot(), backend = "hermes-dashboard", bearer = auth.bearer?.copy(owner = owner, profile = profile), loopbackSession = auth.loopbackSession?.copy(verified = true))
        if (persistContext && !detachedView && authStore != null) {
            authStore.persist(auth)
            markLoopbackSessionPersisted()
        }
        return nativeProjection(identity, owner, profile)
    }

    /** Explicit optional-feature probe for the integrator. Bounded and non-fatal; never an auth decision.
     * Call after native sign-in/chat initialization, then merge with [mergeOptionalFeatures]. */
    suspend fun probeOptionalFeatures(): JSONObject {
        check(auth.backend == "hermes-dashboard" && auth.owner.isNotBlank() && auth.profile.isNotBlank()) {
            "Verify native identity before probing optional features"
        }
        return optionalProjection(optionalExtension(auth.profile))
    }

    /** Native profiles this server actually lists. A non-401 failure is discovery loss, not sign-out. */
    private suspend fun nativeProfileCatalog(): List<String>? {
        val rows = try { nativeGet("/api/profiles").rows("profiles") }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: ApiFailure) { if (e.status == 401) throw e; return null }
        catch (_: IOException) { return null }
        catch (_: Exception) { return null }
        return rows.map { it.string("name").trim() }.filter(::validNativeProfile).distinct().take(256)
    }

    private suspend fun nativeActiveProfile(): String? = try {
        val active = nativeGet("/api/profiles/active")
        listOf(active.string("current"), active.string("active")).firstOrNull(::validNativeProfile)
    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
    catch (e: ApiFailure) { if (e.status == 401) throw e; null }
    catch (_: Exception) { null }

    private suspend fun resolveNativeProfile(catalog: List<String>?): String {
        val saved = auth.profile
        if (!catalog.isNullOrEmpty()) {
            if (saved in catalog) return saved
            nativeActiveProfile()?.takeIf { it in catalog }?.let { return it }
            return if ("default" in catalog) "default" else catalog.first()
        }
        // Older servers without /api/profiles: the server still authorizes each scoped request.
        if (validNativeProfile(saved)) return saved
        return nativeActiveProfile()?.takeIf(::validNativeProfile) ?: "default"
    }

    private class OptionalExtension(val payload: JSONObject?, val state: String, val reason: String = "") {
        val available get() = payload != null && state == "available"
    }

    /** The plugin is optional. Every failure becomes an unavailable projection, never an auth decision.
     * The probe is read-only with respect to the verified native auth context. */
    private suspend fun optionalExtension(profile: String): OptionalExtension {
        val payload = try {
            withTimeoutOrNull(OPTIONAL_EXTENSION_TIMEOUT_MS) {
                nativeGet("/api/plugins/cypher/mobile/bootstrap", mapOf("cypher_profile" to profile), persistAuth = false)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiFailure) {
            return when (e.status) {
                401, 403 -> OptionalExtension(null, "unauthorized", "optional_plugin_unauthorized")
                404, 405, 501 -> OptionalExtension(null, "absent", "optional_plugin_absent")
                else -> OptionalExtension(null, "error", "optional_plugin_http_${e.status}")
            }
        } catch (_: IOException) {
            return OptionalExtension(null, "error", "optional_plugin_unreachable")
        } catch (_: Exception) {
            return OptionalExtension(null, "malformed", "optional_plugin_invalid")
        }
        if (payload == null) return OptionalExtension(null, "error", "optional_plugin_timeout")
        if (payload.optInt("contract_version", -1) != 2 || payload.optString("backend") != "hermes-dashboard") {
            return OptionalExtension(null, "unsupported", "optional_plugin_contract_${payload.optInt("contract_version", -1)}")
        }
        // The optional projection may describe features, never rebind the verified native identity.
        if (payload.optString("owner_id").trim() != auth.owner) return OptionalExtension(null, "unauthorized", "optional_plugin_owner_mismatch")
        val pluginProfile = payload.optString("profile").trim()
        if (pluginProfile.isNotBlank() && pluginProfile != profile) return OptionalExtension(null, "malformed", "optional_plugin_profile_mismatch")
        return OptionalExtension(payload, "available")
    }

    private fun nativeProjection(identity: JSONObject, owner: String, profile: String): JSONObject = JSONObject().apply {
        put("contract_version", 2)
        put("backend", "hermes-dashboard")
        put("owner_id", owner)
        put("profile", profile)
        put("identity", JSONObject().apply {
            put("user_id", owner)
            put("email", identity.string("email"))
            put("display_name", identity.string("display_name"))
            put("provider", identity.string("provider"))
        })
        put("voice", json("available" to false, "availability_scope" to "not_probed", "reason" to "optional_features_not_probed"))
        put("capabilities", falseNativeCapabilities())
        put("optional_features", json(
            "plugin" to "not_probed",
            "reason" to "optional_features_not_probed",
            "unavailable" to JSONArray(),
            "independent_of_native_chat" to true,
        ))
        put("native", json("verified" to true, "chat" to true, "history" to true, "profiles" to true, "ws_rpc" to true, "attachments" to true))
    }

    /** Probe output only: capability fields, never owner/profile/backend/identity. */
    private fun optionalProjection(optional: OptionalExtension): JSONObject {
        val payload = optional.payload
        val voice = payload?.optJSONObject("voice")?.copy() ?: JSONObject()
        if (!optional.available) {
            voice.put("available", false).put("availability_scope", "optional_extension_unavailable").put("reason", optional.reason)
        }
        val capabilities = payload?.optJSONObject("capabilities")?.copy() ?: JSONObject()
        for (key in NATIVE_CAPABILITY_KEYS) if (!capabilities.has(key)) capabilities.put(key, false)
        val unavailable = JSONArray()
        if (!optional.available) OPTIONAL_FEATURES.forEach { unavailable.put(it) }
        return JSONObject().apply {
            put("plugin", optional.state)
            put("reason", optional.reason)
            put("voice", voice)
            put("capabilities", capabilities)
            put("unavailable", unavailable)
            payload?.opt("release")?.takeIf { it != JSONObject.NULL }?.let { put("release", it) }
        }
    }

    suspend fun logout() {
        try {
            closeNative()
            // Hermes has no native bearer revoke route.
            if (auth.bearer == null) try {
                val body = JSONObject().toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                withContext(Dispatchers.IO) {
                    execute(request("/auth/logout", method = "POST", body = body).build()) { response ->
                        if (response.code !in 200..399) throw ApiFailure(response.code, "Hermes sign-out could not be confirmed")
                    }
                }
            } catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: IOException) { }
        } finally { authStore?.remove(auth.origin) }
    }
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
        require(NativeOperationPolicy.direct(mapped, if (data == null) "GET" else "POST")) { "This download capability is unavailable in native Hermes" }
        if (query.containsKey("profile")) require(query["profile"] == auth.profile) { "Native download belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == auth.profile) { "Native download belongs to another profile" }
        val scopedQuery = (query - "session_id").let {
            if (mapped.startsWith(CYPHER_PLUGIN_PATH)) cypherPluginProfileQuery(it, auth.profile) else it
        }
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

/** Native profile ids are server-supplied directory names; accept only the bounded id shape. */
internal fun validNativeProfile(name: String): Boolean = name.length in 1..80 && name != "." && name != ".." &&
    (name.first() in 'a'..'z' || name.first() in 'A'..'Z' || name.first() in '0'..'9') &&
    name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' || it == '.' }

// Optional plugin capabilities surface through the explicit probe; none of them gate native chat/history.
private val OPTIONAL_FEATURES = listOf("voice", "callbacks", "push", "bot_mode", "projects", "skills_extensions", "memory_documents", "admin", "release", "notifications", "tasks")
private val NATIVE_CAPABILITY_KEYS = listOf("native_jobs", "pc_recording", "callbacks", "push", "permanent_recording_retention")

// A cold optional projection must not hold sign-in for the native read timeout.
private const val OPTIONAL_EXTENSION_TIMEOUT_MS = 8_000L

private fun falseNativeCapabilities(): JSONObject = json(
    "native_jobs" to false, "pc_recording" to false, "callbacks" to false, "push" to false, "permanent_recording_retention" to false,
)

/** Merge an explicit [CypherClient.probeOptionalFeatures] result into a native bootstrap projection.
 * Native authority fields (`owner_id`, `profile`, `backend`, `identity`) are never replaced. */
fun mergeOptionalFeatures(projection: JSONObject, optional: JSONObject): JSONObject {
    val merged = projection.copy()
    merged.put("voice", optional.optJSONObject("voice")?.copy() ?: json("available" to false, "availability_scope" to "optional_extension_unavailable"))
    merged.put("capabilities", optional.optJSONObject("capabilities")?.copy() ?: falseNativeCapabilities())
    merged.put("optional_features", json(
        "plugin" to optional.optString("plugin", "not_probed"),
        "reason" to optional.optString("reason"),
        "unavailable" to (optional.optJSONArray("unavailable") ?: JSONArray()),
        "independent_of_native_chat" to true,
    ))
    optional.opt("release")?.takeIf { it != JSONObject.NULL }?.let { merged.put("release", it) }
    return merged
}

/** Whether a native projection for [scope] still needs (or may retry) the optional probe. */
fun optionalFeaturesPending(projection: JSONObject, scope: Scope): Boolean =
    nativeProjectionFor(projection, scope) &&
        projection.optJSONObject("optional_features")?.optString("plugin", "not_probed") in setOf("not_probed", "error")

/** Merges a probe only into the native projection it was started for; a projection of another
 * owner/profile (a newer sign-in, profile switch or notification bind) yields null and is kept. */
fun mergeOptionalFeaturesFor(projection: JSONObject, scope: Scope, optional: JSONObject): JSONObject? =
    if (nativeProjectionFor(projection, scope)) mergeOptionalFeatures(projection, optional) else null

private fun nativeProjectionFor(projection: JSONObject, scope: Scope): Boolean =
    projection.optString("backend") == "hermes-dashboard" && scope.owner.isNotBlank() &&
        projection.optString("owner_id") == scope.owner && projection.optString("profile") == scope.profile

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

private fun Response.isExpiredSessionEnvelope(): Boolean = runCatching {
    val body = JSONObject(peekBody(4_096L).string())
    body.optString("reason") == "invalid_or_expired_session" && body.optString("error") in setOf("unauthenticated", "session_expired")
}.getOrDefault(false)
