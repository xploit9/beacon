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
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** [plugin]: the failure came from the optional Beacon plugin, whose 401 is a plugin answer, never a native sign-out. */
class ApiFailure(val status: Int, override val message: String, val plugin: Boolean = false) : IOException(message) {
    internal val detail = message
}
internal class LoopbackSessionRefreshFailed : IOException("Hermes restarted. Paste the link again.")
internal const val HERMES_LOOPBACK_SESSION_HEADER = "X-Hermes-Session-Token"
private const val MAX_LOOPBACK_ROOT_BYTES = 256L * 1024L
private const val BEACON_PLUGIN_PATH = "/api/plugins/beacon/"
internal const val CANONICAL_TICKET_PATH = BEACON_PLUGIN_PATH + "mobile/gateway-ticket"
internal const val CANONICAL_SOCKET_PATH = BEACON_PLUGIN_PATH + "gateway/ws"
internal class CanonicalRouteMissing : RuntimeException("Canonical gateway route is unavailable")
private val GATEWAY_JSON_STRING = "\"" + """(?:[^"\\\x00-\x1f]|\\(?:["\\/bfnrt]|u[0-9a-fA-F]{4}))*""" + "\""
private val GATEWAY_JSON_VALUE = """(?:$GATEWAY_JSON_STRING|true|false|null|-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)"""
private val GATEWAY_JSON_PAIR = """$GATEWAY_JSON_STRING[ \t\r\n]*:[ \t\r\n]*$GATEWAY_JSON_VALUE"""
private val GATEWAY_JSON_OBJECT = Regex("""[ \t\r\n]*\{[ \t\r\n]*(?:$GATEWAY_JSON_PAIR(?:[ \t\r\n]*,[ \t\r\n]*$GATEWAY_JSON_PAIR)*)?[ \t\r\n]*\}[ \t\r\n]*""")
private const val OPTIONAL_BOOTSTRAP_PATH = BEACON_PLUGIN_PATH + "mobile/bootstrap"
private const val VAULT_PATH = BEACON_PLUGIN_PATH + "mobile/vault"
private val VAULT_RESPONSE_KEYS = setOf("items", "item", "capabilities", "identity", "request_id", "removed", "id", "kind", "label", "origin",
    "identifier_type", "identifier", "has_otp", "created_at", "backend", "supported", "local", "signup", "otp", "secure_prompts", "name", "email", "status")
internal fun vaultMetadataOnly(value: Any?, depth: Int = 0): Boolean {
    if (depth > 32) return false
    return when (value) {
        is JSONObject -> value.keys().asSequence().all { key ->
            key in VAULT_RESPONSE_KEYS && vaultMetadataOnly(value.opt(key), depth + 1)
        }
        is JSONArray -> (0 until value.length()).all { vaultMetadataOnly(value.opt(it), depth + 1) }
        else -> true
    }
}
/** Carry the request's authority to each target, including work deferred until after the response. */
internal class DeviceRemovalAuthority(val scope: Scope, private val removeIfCurrent: (() -> Unit) -> Boolean) {
    fun ifCurrent(remove: () -> Unit): Boolean = removeIfCurrent(remove)
}
internal object NativeDeviceRemoval {
    @Volatile var onRemoved: ((DeviceRemovalAuthority) -> Unit)? = null
    /** Set by the ViewModel: a removed phone keeps none of its account's saved screen answers. */
    @Volatile var onForgetScreens: ((DeviceRemovalAuthority) -> Unit)? = null
    fun removed(removal: DeviceRemovalAuthority) {
        // Hooks may take PushManager's lock or suspend later; no authority monitor spans a hook.
        if (removal.ifCurrent {}) onRemoved?.invoke(removal)
        if (removal.ifCurrent {}) onForgetScreens?.invoke(removal)
    }
}
private val LOOPBACK_SESSION_ASSIGNMENT = Regex("""window\.__HERMES_SESSION_TOKEN__\s*=\s*("(?:\\.|[^"\\])*")\s*;""")

// Hermes reserves `profile` for plugin routing; Beacon uses its own key for the selected data profile.
private fun beaconPluginProfileQuery(query: Map<String, String>, profile: String) =
    (query - "profile" - "beacon_profile") + ("beacon_profile" to profile)

internal fun parseLoopbackSessionToken(html: String): String {
    val literal = LOOPBACK_SESSION_ASSIGNMENT.find(html)?.groupValues?.getOrNull(1) ?: throw LoopbackSessionRefreshFailed()
    val token = runCatching { JSONArray("[$literal]").getString(0) }.getOrNull()
    if (!validLoopbackSessionToken(token.orEmpty())) throw LoopbackSessionRefreshFailed()
    return token!!
}

private fun Response.isLoopbackSessionRejection() = runCatching {
    JSONObject(peekBody(4 * 1024L).string()).optString("detail") == "Unauthorized"
}.getOrDefault(false)

class BeaconClient internal constructor(initial: AuthContext, private val authStore: AuthStore?, private val socketFactory: WebSocket.Factory? = null, private val sharedHttp: OkHttpClient? = null, private val detachedView: Boolean = false, private val persistContext: Boolean = true, private val credentialSource: BeaconClient? = null, private val canonicalScopes: MutableSet<Scope> = mutableSetOf(), private val configureHttp: (OkHttpClient.Builder) -> Unit) {
    constructor(initial: AuthContext, authStore: AuthStore? = null, persistContext: Boolean = true) : this(initial, authStore, null, null, persistContext = persistContext, configureHttp = {})
    private val origin = trustedOrigin(initial.origin).toHttpUrl()
    val jar = ScopedCookies(origin, initial.cookies) { auth.bearer != null || auth.loopbackSession != null }
    private val scopeGeneration = AtomicLong()
    @Volatile var auth: AuthContext = initial
        private set(value) {
            synchronized(this) {
                if (field.scope() != value.scope()) scopeGeneration.incrementAndGet()
                field = value
            }
        }
    // Detached jars normalize cookie text; retain the exact CAS baseline and its admission.
    @Volatile private var expectedCookies = initial.cookies
    @Volatile private var cookieVersion = authStore?.authorityEpoch?.readVersion()
    @Volatile private var cookieAuthorityLost = false
    val scope get() = auth.scope()
    // A coroutine Mutex: the refresh suspends (network read with timeout) while holding the lock.
    private val loopbackRefreshLock = kotlinx.coroutines.sync.Mutex()
    val http: OkHttpClient = (sharedHttp?.newBuilder() ?: OkHttpClient.Builder()).cookieJar(object : CookieJar {
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (url.host != origin.host || url.port != origin.port || url.scheme != origin.scheme) return emptyList()
            return synchronized(this@BeaconClient) {
                if (auth.bearer == null && auth.loopbackSession == null) {
                    adoptCookieRotation()
                    retainCookieRotationLocked()
                }
                jar.loadForRequest(url)
            }
        }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            // The in-process probe may rotate native credentials, but cannot plant other cookies.
            jar.saveFromResponse(url, if (url.encodedPath == OPTIONAL_BOOTSTRAP_PATH) cookies.filter { nativeSessionCookie(it.name) } else cookies)
        }
    }).followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).apply {
        if (sharedHttp == null) { addInterceptor(bearerAuthInterceptor()); configureHttp(this) }
    }.build()
    private val nativeAuth = NativeAuth(auth.origin, http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build())
    private val operationLock = kotlinx.coroutines.sync.Mutex()
    private data class RequestAuthority(val scope: Scope, val generation: Long, val version: Long?, val admission: String?)
    private suspend fun requestAuthority(trackRemoval: Boolean): RequestAuthority {
        val (bound, generation) = synchronized(this) { scope to scopeGeneration.get() }
        val version = nativeAuthorityVersion()
        val admission = if (trackRemoval && authStore != null) withContext(Dispatchers.IO) {
            runCatching { authStore?.incidentAdmission(bound) }.getOrNull()
        } else null
        return RequestAuthority(bound, generation, version, admission)
    }
    private suspend fun requestStillCurrent(bound: RequestAuthority): Boolean = withContext(Dispatchers.IO) {
        if (bound.version == null || nativeAuthorityVersion() != bound.version ||
            authStore != null && (bound.admission == null || runCatching { authStore?.incidentAdmission(bound.scope) }.getOrNull() != bound.admission)) return@withContext false
        synchronized(this@BeaconClient) { scope == bound.scope && scopeGeneration.get() == bound.generation }
    }
    private suspend fun removeDeviceIfCurrent(bound: RequestAuthority, query: Map<String, String>) = withContext(Dispatchers.IO) {
        val target = query["beacon_profile"] ?: query["profile"] ?: bound.scope.profile
        if (target == bound.scope.profile && requestStillCurrent(bound)) NativeDeviceRemoval.removed(DeviceRemovalAuthority(bound.scope) { remove ->
            synchronized(this) {
                if (scope != bound.scope || scopeGeneration.get() != bound.generation || bound.version == null || nativeAuthorityVersion() != bound.version) false
                else if (authStore != null) authStore.applyDeviceRemoval(bound.scope, bound.version, bound.admission, remove)
                else { remove(); true }
            }
        })
    }
    internal fun nativeAuthorityVersion(): Long? = authStore?.authorityEpoch?.readVersion() ?: if (authStore == null) Long.MIN_VALUE else null
    internal data class GatewayIdentity(val scope: Scope, val generation: Long, val authority: Long?, val admission: String?)
    internal fun gatewayIdentity(): GatewayIdentity {
        val (scope, generation) = synchronized(this) { scope to scopeGeneration.get() }
        return GatewayIdentity(scope, generation, nativeAuthorityVersion(), nativeSecureAdmission())
    }
    internal fun gatewayIdentityCurrent(identity: GatewayIdentity, checkAdmission: Boolean = false): Boolean {
        val generation = scopeGeneration.get()
        return generation == identity.generation && scope == identity.scope && scopeGeneration.get() == generation && identity.authority != null &&
            nativeAuthorityVersion() == identity.authority && (!checkAdmission || identity.admission == null || nativeSecureAdmission() == identity.admission) &&
            nativeAuthorityVersion() == identity.authority && scopeGeneration.get() == generation
    }
    private fun canonicalRemembered(scope: Scope): Boolean = if (scope.nativeProvider.isBlank()) canonicalScopes.any { it.origin == scope.origin }
        else scope in canonicalScopes || scope.copy(nativeProvider = "") in canonicalScopes
    internal fun canonicalRequired(identity: GatewayIdentity): Boolean = synchronized(canonicalScopes) {
        canonicalRemembered(identity.scope) || authStore?.canonicalRequired(identity.scope) == true
    }
    internal fun rememberCanonical(identity: GatewayIdentity): Boolean = synchronized(canonicalScopes) {
        if (!gatewayIdentityCurrent(identity, checkAdmission = true) || authStore?.rememberCanonical(identity.scope, identity.authority) == false) false
        else { canonicalScopes += identity.scope; true }
    }
    internal fun withLegacyCanonicalPermit(identity: GatewayIdentity, send: () -> Boolean): Boolean = synchronized(canonicalScopes) {
        if (!gatewayIdentityCurrent(identity) || canonicalRemembered(identity.scope)) false
        else if (authStore == null) send() else authStore.withLegacyCanonicalPermit(identity.scope, identity.authority) {
            gatewayIdentityCurrent(identity) && send()
        }
    }
    internal fun incidentAdmission(): String? = authStore?.incidentAdmission(scope)
    internal fun nativeSecureAdmission(): String? = if (authStore == null) "transient-native-client" else runCatching { incidentAdmission() }.getOrNull()
    internal fun enqueueSecureResponse(admission: String?, enqueue: () -> Boolean): Boolean {
        if (admission == null) return false
        return if (authStore == null) nativeSecureAdmission() == admission && enqueue()
        else authStore.withIncidentAdmission(scope, admission, enqueue)
    }
    internal suspend fun bindProfile(profile: String) {
        closeNative(); auth = auth.copy(profile = profile, bearer = auth.bearer?.copy(profile = profile))
        authStore?.takeIf { !detachedView }?.let {
            if (it.persistIfUnchanged(auth, cookieVersion, snapshot = true, selectProfileIf = { true })) markAuthPersisted()
        }
    }
    internal fun markAuthPersisted() {
        cookieVersion = authStore?.authorityEpoch?.readVersion()
        expectedCookies = auth.cookies
        auth.loopbackSession?.takeIf { it.verified }?.let { auth = auth.copy(loopbackSession = it.copy(persisted = true)) }
    }
    private fun adoptCookieRotation() {
        // Only cookie sessions rotate cookies; a bearer or loopback request never needs the store read.
        if (auth.bearer != null || auth.loopbackSession != null) return
        synchronized(this) {
            val store = authStore ?: return
            val version = cookieVersion ?: return
            if (!store.authorityEpoch.current(version)) return
            val stored = store.read(auth.origin)
            if (!store.authorityEpoch.current(version) || !stored.sameCookieAuthority(auth) || stored.cookies == expectedCookies) return
            // Keep a response's not-yet-retained rotation; only an unchanged jar can adopt a sibling's.
            if (jar.replaceIfUnchanged(expectedCookies, stored.cookies)) {
                expectedCookies = stored.cookies
                auth = auth.copy(cookies = stored.cookies)
            }
        }
    }
    private fun retainCookieRotationLocked() {
        if (cookieAuthorityLost) throw CachedIdentityMismatch()
        val cookies = jar.snapshot()
        val store = authStore
        val version = cookieVersion
        if (store != null && (version == null || !store.authorityEpoch.current(version))) {
            cookieAuthorityLost = true
            jar.replaceIfUnchanged(cookies, emptyList())
            throw CachedIdentityMismatch()
        }
        val normalized = expectedCookies.mapNotNull { Cookie.parse(origin, it)?.toString() }
        if (cookies == normalized) return
        if (store != null && !store.replaceCookies(auth.copy(cookies = expectedCookies), cookies, version)) {
            val latest = store.read(auth.origin)
            if (version != null && store.authorityEpoch.current(version) && latest.sameCookieAuthority(auth) &&
                jar.replaceIfUnchanged(cookies, latest.cookies)) {
                expectedCookies = latest.cookies
                auth = auth.copy(cookies = latest.cookies)
                return
            }
            cookieAuthorityLost = true
            jar.replaceIfUnchanged(cookies, emptyList())
            throw CachedIdentityMismatch()
        }
        expectedCookies = cookies
        auth = auth.copy(cookies = cookies)
    }
    internal suspend fun retainCookieRotation() = withContext(Dispatchers.IO) {
        synchronized(this@BeaconClient) {
            if (auth.bearer == null && auth.loopbackSession == null) adoptCookieRotation()
            retainCookieRotationLocked()
        }
    }
    fun detached(): BeaconClient = BeaconClient(auth.copy(cookies = jar.snapshot()), authStore, socketFactory, http, detachedView = true, persistContext = persistContext, configureHttp = configureHttp, credentialSource = credentialSource, canonicalScopes = canonicalScopes).also {
        it.expectedCookies = expectedCookies; it.cookieVersion = cookieVersion
    }
    /** Cleanup owns its captured credential; it cannot adopt or persist a later phone sign-in. */
    internal fun detachedForLogout(): BeaconClient = BeaconClient(auth.copy(cookies = jar.snapshot()), null, socketFactory, detachedView = true, persistContext = false, canonicalScopes = canonicalScopes, configureHttp = configureHttp)
    /** A short-lived profile view shares the signed-in account's refresh owner without persisting a profile switch. */
    internal fun forAuthorizedProfile(profile: String): BeaconClient {
        require(validNativeProfile(profile) && auth.owner.isNotBlank()) { "Invalid native profile scope" }
        val source = credentialSource ?: this
        require(source.scope.sameAccount(scope)) { "Native profile belongs to another account" }
        return BeaconClient(
            auth.copy(profile = profile, bearer = source.auth.bearer?.copy(profile = profile), loopbackSession = source.auth.loopbackSession, cookies = jar.snapshot()),
            authStore, socketFactory, source.http, detachedView = true, persistContext = false,
            configureHttp = configureHttp, credentialSource = source, canonicalScopes = canonicalScopes,
        ).also { it.expectedCookies = expectedCookies; it.cookieVersion = cookieVersion }
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
        if (!source.scope().sameAccount(scope)) return auth
        return auth.copy(bearer = source.bearer?.copy(profile = auth.profile), loopbackSession = source.loopbackSession, cookies = jar.snapshot())
    }
    fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: RequestBody? = null): Request.Builder = Request.Builder().url(url(path, query)).apply {
        require(NativeOperationPolicy.direct(path, method)) { "Unsupported native endpoint" }
        require(path != "/" || method == "GET" && body == null && query.isEmpty()) { "Unsupported native endpoint" }
    }.header("Accept", "application/json").apply {
        val session = requestAuth()
        if (path != "/api/ws") {
            val bearer = session.bearer?.takeIf { validNativeBearerToken(it.accessToken) && it.origin == session.origin && it.owner == session.owner && it.profile == session.profile && it.provider == session.nativeProvider }
            val loopback = session.loopbackSession?.takeIf { it.origin == session.origin && it.owner == session.owner && it.owner == "local" && session.backend == "hermes-dashboard" && validLoopbackSessionToken(it.token) }
            if (bearer != null) header("Authorization", "Bearer " + bearer.accessToken)
            else loopback?.let { header(HERMES_LOOPBACK_SESSION_HEADER, it.token) }
            NativePairing.deviceHeader(path, scope)?.let { header("X-Beacon-Device-ID", it) }
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
    private val native = NativeGateway(this, socketFactory ?: http.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build())
    suspend fun nativeRpc(method: String, params: JSONObject = JSONObject()): JSONObject = try { native.rpc(method, params) }
        catch (error: Throwable) { reportIncident("gateway", if (method in setOf("session.list", "session.history", "session.active_list", "status", "health")) "lookup" else "invoke_tool", error); throw error }
    internal suspend fun nativeSessionAuthority(): Boolean = native.sessionAuthority()
    internal suspend fun nativeTurnLease(storedSessionId: String, expectedRuntimeSessionId: String? = null): NativeTurnLease =
        native.turnLease(storedSessionId, expectedRuntimeSessionId)
    internal suspend fun nativeTurnRpc(lease: NativeTurnLease, method: String, params: JSONObject): JSONObject =
        native.turnRpc(lease, method, params)
    internal suspend fun nativeVoiceSubmit(binding: NativeSessionBinding, params: JSONObject, jpeg: String?, legacyJobCapacity: Boolean,
        sendLock: Any, callOwned: () -> Boolean, markAttempt: () -> Boolean, permitted: () -> Boolean): JSONObject =
        native.voiceSubmit(binding, params, jpeg, legacyJobCapacity, sendLock, callOwned, markAttempt, permitted)
    internal fun nativeAnswerServerRequest(method: String, requestId: String, sessionId: String, payload: JSONObject): JSONObject =
        native.answerServerRequest(method, requestId, sessionId, payload)
    suspend fun nativeRequest(path: String, method: String = "GET", body: JSONObject? = null, params: Map<String, String> = emptyMap()): JSONObject {
        require(NativeOperationPolicy.direct(path, method)) { "Unsupported native endpoint" }
        val bound = requestAuthority(path.startsWith(BEACON_PLUGIN_PATH))
        if (body?.has("profile") == true) require(body.string("profile") == bound.scope.profile) { "Native request belongs to another profile" }
        val query = if (path.startsWith(BEACON_PLUGIN_PATH)) beaconPluginProfileQuery(params, bound.scope.profile) else params
        val deviceId = NativePairing.deviceHeader(path, bound.scope)
        val result = rawCall(path, query = query, method = method, data = body, maxBytes = if (path == CANONICAL_TICKET_PATH) 8192 else 32 * 1024 * 1024, boundRequest = bound)
        if (method == "DELETE" && deviceId != null && path == "/api/plugins/beacon/devices/$deviceId") removeDeviceIfCurrent(bound, query)
        return result
    }
    // Phone access: Hermes asks this phone to use its calendar, alarms, contacts, calls or messages, and the phone decides.
    private fun phoneRequestPath(id: String): String {
        require(id.matches(Regex("[0-9a-f]{32}"))) { "Invalid phone request" }
        return "/api/plugins/beacon/mobile/phone/requests/$id"
    }
    internal suspend fun phoneRequests(): JSONObject = nativeRequest("/api/plugins/beacon/mobile/phone/requests")
    internal suspend fun phoneDecision(id: String, approved: Boolean): JSONObject = nativeRequest(phoneRequestPath(id) + "/decision", "POST", json("approved" to approved))
    internal suspend fun phoneResult(id: String, result: JSONObject): JSONObject = nativeRequest(phoneRequestPath(id) + "/result", "POST", json("result" to result))
    internal suspend fun phoneState(state: JSONObject): JSONObject = nativeRequest("/api/plugins/beacon/mobile/phone/state", "PUT", state)
    suspend fun nativeCallContext(callId: String, ownerName: String = ""): JSONObject = kotlinx.coroutines.withTimeout(20_000) {
        val path = "/api/plugins/beacon/mobile/calls/$callId/context"
        // The live model answers the owner itself (the direct brief: persona, memory, this chat, tools), and Beacon hands the call to Hermes memory when it ends.
        val params = mapOf("owner_name" to ownerName)
        try { nativeRequest(path, params = params) }
        catch (error: ApiFailure) {
            if (error.status !in setOf(429, 502, 503, 504)) throw error
            // Retry only this read: a cold native lookup can outlast its server deadline.
            kotlinx.coroutines.delay(300)
            nativeRequest(path, params = params)
        }
    }
    /** The call context of [storedSessionId] before any call exists; an older plugin answers 404 and the call reads it after the grant. */
    suspend fun nativeVoiceContext(storedSessionId: String, ownerName: String = ""): JSONObject =
        nativeRequest("/api/plugins/beacon/mobile/voice/context", params = mapOf("session" to storedSessionId, "owner_name" to ownerName))
    internal suspend fun nativeReplay(lastId: String = "", beforePrompt: Boolean = false) = native.prepareReplay(lastId, beforePrompt)
    internal suspend fun nativeCreate(params: JSONObject): NativeSessionBinding = native.create(params)
    internal fun nativeSessionSnapshot() = native.sessionSnapshot()
    suspend fun nativeSession(storedSessionId: String?, lazy: Boolean = false): NativeSessionBinding = native.session(storedSessionId, lazy)
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
    suspend fun nativeEvents(onEvent: suspend (JSONObject) -> Unit) = try { native.events(onEvent) }
        catch (error: Throwable) { reportIncident("gateway", "sync", error); throw error }
    internal fun reportIncident(component: String, operation: String, error: Throwable) {
        runCatching { authStore?.incidentSink?.invoke(this, component, operation, error) }
    }
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
        val current = stored?.takeIf { it.origin == bearer.origin && it.owner == bearer.owner && it.provider == bearer.provider }?.copy(profile = bearer.profile)
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
                            if (auth.bearer != expected) false else { auth = auth.copy(bearer = updated, nativeProvider = updated.provider); true }
                        } else {
                            val saved = authStore.replaceBearer(expected, updated)
                            if (saved) auth = auth.copy(bearer = updated, nativeProvider = updated.provider)
                            saved
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            response.close()
            if (error !is NativeAuthException || !error.sessionExpired) throw error
            val retired = authStore?.retireBearerIfUnchanged(bearer)
            if (retired?.removed != false) auth = auth.copy(bearer = null, cookies = emptyList())
            throw NativeAuthExpiredException(retired?.authority)
        }
        auth = auth.copy(bearer = refreshed.copy(profile = auth.profile), nativeProvider = refreshed.provider)
        response.close()
        chain.proceed(request.newBuilder().header("Authorization", "Bearer ${refreshed.accessToken}").build())
    }
    private suspend fun <T> execute(request: Request, timeoutMillis: Long? = null, retainCookies: Boolean = false, read: suspend (Response) -> T): T {
        val call = http.newCall(request)
        timeoutMillis?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) }
        // Cancellation must close a stalled body too, after response headers have arrived.
        val watcher = kotlinx.coroutines.CoroutineScope(currentCoroutineContext()).launchCancel(call)
        try { return call.execute().use { response ->
            // Any answer, an error status included, means Hermes is reachable again.
            HermesReach.answered(auth.origin)
            // Retain native rotations before any status/body failure, after loopback verification.
            if (retainCookies && auth.loopbackSession?.verified != false) retainCookieRotation()
            read(response)
        } }
        catch (error: IOException) { currentCoroutineContext().ensureActive(); HermesReach.failed(auth.origin, error); throw error }
        finally { watcher.cancel(); call.cancel() }
    }
    /** A caller-parsed native response that still keeps Hermes' cookie rotation. */
    internal suspend fun <T> nativeResponse(request: Request, timeoutMillis: Long? = null, read: suspend (Response) -> T): T =
        withContext(Dispatchers.IO) { execute(request, timeoutMillis, retainCookies = true, read) }
    suspend fun get(path: String, query: Map<String, String> = emptyMap(), maxBytes: Int = 32 * 1024 * 1024): JSONObject = call(path, query, maxBytes = maxBytes)
    suspend fun post(path: String, body: JSONObject = JSONObject()): JSONObject = call(path, method = "POST", data = body)
    private suspend fun rawCall(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024, persistAuth: Boolean = true, timeoutMillis: Long? = null, sensitive: Boolean = false, boundRequest: RequestAuthority? = null, sensitiveDeviceId: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val requestBound = boundRequest ?: requestAuthority(path.startsWith(BEACON_PLUGIN_PATH))
        require(maxBytes in 1..32 * 1024 * 1024) { "Invalid response size limit" }
        val body = if (sensitive) object : RequestBody() {
            private val encoded = (data?.toString() ?: "{}").toByteArray(Charsets.UTF_8)
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = encoded.size.toLong()
            override fun isOneShot() = true
            override fun writeTo(sink: okio.BufferedSink) { sink.write(encoded) }
        } else data?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        // /auth/me credentials are retained by bootstrap only after identity verification.
        suspend fun fetch() = execute(request(path, query, method, body).apply {
            if (sensitive) {
                if (sensitiveDeviceId == null) removeHeader("X-Beacon-Device-ID") else header("X-Beacon-Device-ID", sensitiveDeviceId)
            }
        }.build(), timeoutMillis, retainCookies = persistAuth || path != "/api/auth/me") { response ->
            if (sensitive && !response.isSuccessful) throw ApiFailure(response.code, "Vault change could not be confirmed. Refresh Vault before trying again.", plugin = true)
            val input = response.body?.source()
            val payload = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(32768)
            if (input != null) while (true) { currentCoroutineContext().ensureActive(); val n = input.read(chunk); if (n < 0) break; require(payload.size().toLong() + n <= maxBytes) { "Server response exceeds the size limit" }; payload.write(chunk, 0, n) }
            val raw = payload.toString("UTF-8")
            val result = runCatching {
                if (path == CANONICAL_TICKET_PATH) {
                    require(!response.isSuccessful || GATEWAY_JSON_OBJECT.matches(raw))
                    val tokenizer = org.json.JSONTokener(raw)
                    val parsed = tokenizer.nextValue()
                    require(parsed is JSONObject && tokenizer.nextClean() == '\u0000')
                    parsed
                } else JSONObject(raw)
            }.getOrElse {
                if (sensitive) throw ApiFailure(502, "Vault response could not be verified.", plugin = true)
                if (path == CANONICAL_TICKET_PATH) throw IOException("Canonical gateway response is not a JSON object", it)
                if (raw.trim().startsWith('[')) json("items" to org.json.JSONArray(raw)) else json("message" to raw.take(2048))
            }
            if (path == CANONICAL_TICKET_PATH && response.code == 404 && raw.matches(Regex("""[ \t\r\n]*\{[ \t\r\n]*"detail"[ \t\r\n]*:[ \t\r\n]*"Not Found"[ \t\r\n]*\}[ \t\r\n]*""")))
                throw CanonicalRouteMissing()
            if (sensitive && (!vaultMetadataOnly(result) || result.has("error") && !result.isNull("error"))) throw ApiFailure(502, "Vault response could not be verified.", plugin = true)
            if (response.code == 403 && path.startsWith(BEACON_PLUGIN_PATH) && listOf("code", "error", "detail").any { result.string(it) == "device_removed" }) removeDeviceIfCurrent(requestBound, query)
            if (!response.isSuccessful || result.has("error") && !result.isNull("error") && result.string("error").isNotBlank()) throw ApiFailure(response.code, nativeErrorMessage(result, response.code), plugin = path.startsWith(BEACON_PLUGIN_PATH))
            result
        }
        val bound = requestBound.scope; val authority = requestBound.version
        try {
            try { fetch() }
            catch (error: IOException) {
                if (error !is java.net.SocketException && error !is java.net.UnknownHostException) throw error
                if (sensitive || method != "GET" || authority == null || scope != bound || scopeGeneration.get() != requestBound.generation || nativeAuthorityVersion() != authority) throw error
                kotlinx.coroutines.delay(150)
                if (scope != bound || scopeGeneration.get() != requestBound.generation || nativeAuthorityVersion() != authority) throw error
                fetch()
            }
        } catch (error: Throwable) {
            if (error !is CanonicalRouteMissing && !path.startsWith(BEACON_PLUGIN_PATH + "mobile/incidents") && path != BEACON_PLUGIN_PATH + "mobile/healing/capabilities")
                reportIncident(if (path.startsWith(VAULT_PATH)) "vault" else "network", if (method == "GET") "request" else "save", error)
            throw error
        }
    }
    suspend fun call(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024): JSONObject = operationLock.withLock { NativeOperations(this).call(path, query, method, data, maxBytes) }
    /** Write-only secret input: no transport retry, response-body error echo, draft or mutation cache. */
    suspend fun nativeSensitiveRequest(path: String, method: String, body: JSONObject?): JSONObject {
        val bound = requestAuthority(trackRemoval = true)
        if (bound.version == null || authStore != null && bound.admission == null) throw ApiFailure(409, "Vault needs a stable signed-in account.", plugin = true)
        val origin = bound.scope.origin.toHttpUrl()
        require(origin.isHttps || origin.host in setOf("localhost", "127.0.0.1", "::1")) { "Vault requires a secure connection" }
        require((path == VAULT_PATH || path.startsWith("$VAULT_PATH/")) && method in setOf("POST", "PUT", "DELETE") && NativeOperationPolicy.direct(path, method)) { "Unsupported sensitive request" }
        val captured = synchronized(this) {
            if (scope != bound.scope || scopeGeneration.get() != bound.generation) throw ApiFailure(409, "Vault needs a stable signed-in account.", plugin = true)
            if (auth.bearer == null && auth.loopbackSession == null) { adoptCookieRotation(); retainCookieRotationLocked() }
            requestAuth().also { require(it.scope() == bound.scope) }.copy(cookies = jar.snapshot())
        }
        if (!requestStillCurrent(bound)) throw ApiFailure(409, "Vault needs a stable signed-in account.", plugin = true)
        val device = NativePairing.deviceHeader(path, bound.scope)
        // The isolated jar and credentials cannot adopt a later profile or token rotation.
        val isolated = BeaconClient(captured, null, socketFactory, http, detachedView = true, persistContext = false,
            credentialSource = null, configureHttp = configureHttp)
        try {
            currentCoroutineContext().ensureActive()
            val frozen = try { withContext(Dispatchers.IO) { JSONObject(if (body == null) "{}" else requireNotNull(body.toString())) } }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (_: Exception) { throw ApiFailure(400, "Vault input could not be verified.", plugin = true) }
            require(!frozen.has("profile") || frozen.string("profile") == bound.scope.profile) { "Vault belongs to another profile" }
            val result = isolated.rawCall(path, beaconPluginProfileQuery(emptyMap(), bound.scope.profile), method, frozen, 1024 * 1024,
                timeoutMillis = 15000, sensitive = true, sensitiveDeviceId = device)
            if (!requestStillCurrent(bound)) throw ApiFailure(409, "Vault change could not be confirmed after the account or profile changed.", plugin = true)
            return result
        } catch (error: Throwable) {
            reportIncident("vault", "save", error)
            throw error
        } finally {
            try {
                val returned = isolated.jar.snapshot()
                if (captured.loopbackSession?.verified != false && returned != captured.cookies && requestStillCurrent(bound)) synchronized(this) {
                    if (scope == bound.scope && scopeGeneration.get() == bound.generation && jar.replaceIfUnchanged(captured.cookies, returned))
                        retainCookieRotationLocked()
                }
            } finally { isolated.closeNative() }
        }
    }
    internal suspend fun nativeGet(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", data: JSONObject? = null, maxBytes: Int = 32 * 1024 * 1024, persistAuth: Boolean = true): JSONObject {
        require(NativeOperationPolicy.direct(path, method)) { "This capability is unavailable in native Hermes" }
        val bound = requestAuthority(path.startsWith(BEACON_PLUGIN_PATH))
        if (query.containsKey("profile")) require(query["profile"] == bound.scope.profile) { "Native request belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == bound.scope.profile) { "Native request belongs to another profile" }
        val scoped = if (path.startsWith(BEACON_PLUGIN_PATH)) beaconPluginProfileQuery(query, bound.scope.profile) else query
        return rawCall(path, scoped, method, data, maxBytes, persistAuth, boundRequest = bound)
    }
    internal suspend fun nativeAuthorizedProfiles(): List<String> = nativeProfileCatalog() ?: listOf(auth.profile)
    internal suspend fun nativeGetForProfile(
        path: String,
        profile: String,
        authorizedProfiles: List<String>,
        query: Map<String, String> = emptyMap(),
        maxBytes: Int = 32 * 1024 * 1024,
        timeoutMillis: Long? = null,
    ): JSONObject {
        require(NativeOperationPolicy.direct(path, "GET") && validNativeProfile(profile) && profile in authorizedProfiles) {
            "Native request belongs to an unauthorized profile"
        }
        val scoped = if (path.startsWith(BEACON_PLUGIN_PATH)) beaconPluginProfileQuery(query, profile)
        else (query - "beacon_profile") + ("profile" to profile)
        return rawCall(path, scoped, maxBytes = maxBytes, persistAuth = false, timeoutMillis = timeoutMillis)
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
        auth = auth.copy(owner = "", csrf = "", backend = "", bearer = null, cookies = jar.snapshot(), nativeProvider = "")
        return bootstrap()
    }
    /** Native dashboard verification only. This deliberately makes no optional-plugin request. */
    suspend fun bootstrap(): JSONObject {
        val identity = nativeGet("/api/auth/me", persistAuth = false)
        val owner = identity.string("user_id").trim()
        val provider = identity.string("provider")
        require(owner.isNotBlank()) { "This server did not return a verified native identity" }
        if (auth.loopbackSession != null && (owner != "local" || identity.optString("provider") != "loopback")) throw LoopbackSessionRefreshFailed()
        if (auth.bearer?.owner?.let { it.isNotBlank() && it != owner } == true) throw CachedIdentityMismatch()
        if (auth.bearer?.provider?.let { it.isNotBlank() && it != identity.string("provider") } == true) throw CachedIdentityMismatch()
        if (auth.nativeProvider.isNotBlank() && auth.nativeProvider != provider) throw CachedIdentityMismatch()
        // A vault belongs to one native account. A different verified user must authenticate again.
        if (auth.owner.isNotBlank() && auth.owner != owner) throw CachedIdentityMismatch()
        require(provider.isNotBlank()) { "This server did not return a verified native identity" }
        retainCookieRotation()
        val profile = resolveNativeProfile(nativeProfileCatalog())
        // Keep the /api/auth/me cookie rotation in the verified native context.
        auth = auth.copy(owner = owner, profile = profile, nativeProvider = provider, csrf = "", cookies = jar.snapshot(), backend = "hermes-dashboard", bearer = auth.bearer?.copy(owner = owner, profile = profile, provider = provider), loopbackSession = auth.loopbackSession?.copy(verified = true))
        if (persistContext && !detachedView && authStore != null) {
            val saved = withContext(Dispatchers.IO) {
                val snapshot = authStore.read(auth.origin).scope().sameAccount(auth.scope())
                authStore.persistIfUnchanged(auth, cookieVersion, snapshot = snapshot, selectProfileIf = { true })
            }
            if (saved) markAuthPersisted()
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
     * Its payload cannot change verified native identity; middleware may rotate native cookies. */
    private suspend fun optionalExtension(profile: String): OptionalExtension {
        val payload = try {
            withTimeoutOrNull(OPTIONAL_EXTENSION_TIMEOUT_MS) {
                nativeGet(OPTIONAL_BOOTSTRAP_PATH, mapOf("beacon_profile" to profile), persistAuth = false)
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
        }
    }

    suspend fun logout() {
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
        // The ViewModel removed local authority before push cleanup; this old client must not erase a newer sign-in.
    }
    suspend fun stream(path: String, query: Map<String, String>, lastId: String = "", onEvent: suspend (SseEvent) -> Unit) {
        detached().nativeStream(path, query, lastId, onEvent)
    }
    /** Consume on a dedicated client so busy-turn controls address its live connection. */
    internal suspend fun nativeStream(path: String, query: Map<String, String>, lastId: String = "", onEvent: suspend (SseEvent) -> Unit) {
        require(path in setOf("/api/chat/stream", "/api/session/stream")) { "Streaming this capability is unavailable in native Hermes" }
        NativeOperations(this).stream(path, query, lastId, onEvent)
    }
    suspend fun upload(path: String, file: File, name: String, mime: String, sessionId: String, directory: String = "."): JSONObject = withContext(Dispatchers.IO) {
        operationLock.withLock {
            try {
                require(file.length() <= 32L * 1024 * 1024) { "Attachment exceeds 32 MiB" }
                require(path in setOf("/api/upload", "/api/workspace/upload")) { "This upload capability is unavailable in native Hermes" }
                require(validUploadName(name)) { "Choose a filename without quotes, slashes, dollar signs, percent signs or control characters." }
                if (path == "/api/upload") {
                    val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    json("file" to json("id" to java.util.UUID.randomUUID().toString(), "state" to "ready", "scope_key" to scope.session(sessionId).key, "staged_base64" to encoded, "name" to name, "mime" to mime))
                } else {
                    require(path == "/api/workspace/upload") { "Native chat attachments currently support images; upload other files in Spaces" }
                    val nativePath = NativeOperations(this@BeaconClient).workspacePath(sessionId, directory.trimEnd('/') + "/" + name)
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                        .addFormDataPart("path", nativePath).addFormDataPart("overwrite", "false")
                        .addFormDataPart("file", name, file.asRequestBody(mime.toMediaType())).build()
                    execute(request("/api/files/upload-stream", mapOf("profile" to auth.profile), "POST", body).build(), retainCookies = true) { response ->
                        val raw = response.peekBody(1024 * 1024L + 1).string()
                        if (!response.isSuccessful) throw ApiFailure(response.code,
                            runCatching { nativeErrorMessage(JSONObject(raw), response.code) }.getOrDefault("Upload failed (${response.code})"))
                        require(raw.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Upload response is too large" }
                        val result = runCatching { JSONObject(raw) }.getOrElse { throw IOException("Hermes couldn't confirm the upload.", it) }
                        result
                    }
                }
            } finally { closeNative() }
        }
    }
    internal suspend fun historyImage(reference: NativeReference.HistoryImage): NativeMediaBytes =
        NativeOperations(this).historyImage(reference.sessionId, reference.rowId, reference.imageIndex)

    suspend fun copyTo(path: String, output: OutputStream, query: Map<String, String> = emptyMap(), data: JSONObject? = null, limit: Long = 32L * 1024 * 1024): Long = withContext(Dispatchers.IO) {
        val mapped = if (path in setOf("/api/file/raw", "/api/media")) "/api/fs/download" else path
        require(NativeOperationPolicy.direct(mapped, if (data == null) "GET" else "POST")) { "This download capability is unavailable in native Hermes" }
        if (query.containsKey("profile")) require(query["profile"] == auth.profile) { "Native download belongs to another profile" }
        if (data?.has("profile") == true) require(data.string("profile") == auth.profile) { "Native download belongs to another profile" }
        val scopedQuery = if (mapped.startsWith(BEACON_PLUGIN_PATH)) beaconPluginProfileQuery(query - "session_id", auth.profile)
            else query + ("profile" to auth.profile)
        val snapshot = query["snap"]?.takeIf { mapped == "/api/fs/download" }?.also { require(validMediaSnapshot(it)) { "Invalid file snapshot" } }
        execute(request(mapped, scopedQuery, if (data == null) "GET" else "POST", data?.toString()?.toRequestBody("application/json".toMediaType())).build(), retainCookies = true) { response ->
            if (!response.isSuccessful) throw ApiFailure(response.code, "Download failed (${response.code})")
            val body = response.body ?: error("Missing file")
            require(body.contentLength() <= limit) { "Download exceeds permitted size" }
            val digest = snapshot?.let { java.security.MessageDigest.getInstance("SHA-256") }
            val count = copyDownload(body.byteStream(), digest?.let { java.security.DigestOutputStream(output, it) } ?: output, limit)
            require(snapshot == null || digest!!.digest().joinToString("") { "%02x".format(it) }.equals(snapshot, true)) { "The file changed since this message. Refresh before previewing it." }
            count
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
private val OPTIONAL_FEATURES = listOf("voice", "callbacks", "push", "bot_mode", "projects", "skills_extensions", "memory_documents", "admin", "notifications", "tasks")
private val NATIVE_CAPABILITY_KEYS = listOf("native_jobs", "pc_recording", "callbacks", "push", "permanent_recording_retention")

// A cold optional projection must not hold sign-in for the native read timeout.
private const val OPTIONAL_EXTENSION_TIMEOUT_MS = 8_000L

private fun falseNativeCapabilities(): JSONObject = json(
    "native_jobs" to false, "pc_recording" to false, "callbacks" to false, "push" to false, "permanent_recording_retention" to false,
)

/** Merge an explicit [BeaconClient.probeOptionalFeatures] result into a native bootstrap projection.
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
        scope.nativeProvider.isNotBlank() && projection.optJSONObject("identity")?.optString("provider") == scope.nativeProvider &&
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
