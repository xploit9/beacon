package dev.hermes.android.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.hermes.android.core.digest
import dev.hermes.android.core.Scope
import dev.hermes.android.core.trustedOrigin
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal const val APP_CIPHER_IV_BYTES = 12
internal const val APP_CIPHER_OVERHEAD = APP_CIPHER_IV_BYTES + 16

class AppCipher {
    private val key: SecretKey by lazy {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("hermes-private-v1", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("hermes-private-v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(value: ByteArray, binding: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD(binding.toByteArray()) }
        check(cipher.iv.size == APP_CIPHER_IV_BYTES) { "Unexpected encrypted state IV size" }
        return cipher.iv + cipher.doFinal(value)
    }
    fun decrypt(value: ByteArray, binding: String): ByteArray {
        require(value.size >= APP_CIPHER_OVERHEAD) { "Encrypted state is damaged" }
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, value.copyOfRange(0, APP_CIPHER_IV_BYTES))); updateAAD(binding.toByteArray()) }.doFinal(value.copyOfRange(APP_CIPHER_IV_BYTES, value.size))
    }
}

data class NativeBearerRecord(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val origin: String,
    val owner: String,
    val profile: String,
    val provider: String = "",
    /** Unix seconds; zero identifies a pre-issue-time stored record. */
    val issuedAt: Long = 0,
) {
    override fun toString() = "NativeBearerRecord(origin=$origin, owner=$owner, profile=$profile, expiresAt=$expiresAt, issuedAt=$issuedAt, provider=$provider, accessToken=<redacted>, refreshToken=<redacted>)"
}

data class LoopbackSessionRecord(val token: String, val origin: String, val owner: String, val verified: Boolean = false, val persisted: Boolean = false) {
    override fun toString() = "LoopbackSessionRecord(origin=$origin, owner=$owner, verified=$verified, persisted=$persisted, token=<redacted>)"
}

internal fun validLoopbackSessionToken(value: String) =
    value.isNotBlank() && value.length <= 4_096 && value.all { it in ' '..'~' }

internal fun validNativeBearerToken(value: String) = value.isNotEmpty() && value.all { it in '!'..'~' }

data class AuthContext(
    val origin: String,
    val cookies: List<String>,
    val owner: String = "",
    val profile: String = "default",
    val csrf: String = "",
    val backend: String = "",
    val bearer: NativeBearerRecord? = null,
    val loopbackSession: LoopbackSessionRecord? = null,
    val nativeProvider: String = bearer?.provider.orEmpty().ifBlank { if (loopbackSession?.verified == true) "loopback" else "" },
) {
    fun scope() = Scope(origin, owner, profile, nativeProvider = nativeProvider)
    override fun toString() = "AuthContext(origin=$origin, cookies=${cookies.size}, owner=$owner, profile=$profile, nativeProvider=$nativeProvider, csrf=<redacted>, backend=$backend, bearer=$bearer, loopbackSession=$loopbackSession)"
}

// Issued-time ties keep stored on snapshot writes; checked sign-in can prefer incoming. Legacy expiry ties prefer incoming.
internal fun chooseNewestBearer(incoming: NativeBearerRecord?, stored: NativeBearerRecord?, preferIncomingOnIssueTimeTie: Boolean): NativeBearerRecord? = when {
    stored == null -> incoming
    incoming == null -> stored
    stored.issuedAt > 0 && incoming.issuedAt > 0 -> when {
        stored.issuedAt > incoming.issuedAt -> stored
        stored.issuedAt < incoming.issuedAt -> incoming
        preferIncomingOnIssueTimeTie -> incoming
        else -> stored
    }
    stored.issuedAt > 0 -> stored
    incoming.issuedAt > 0 -> incoming
    stored.expiresAt > incoming.expiresAt -> stored
    else -> incoming
}

internal fun AuthContext.withNewestBearer(stored: NativeBearerRecord?, preferIncomingOnIssueTimeTie: Boolean = false): AuthContext {
    val sameOwner = stored?.takeIf { loopbackSession == null && bearer != null && it.origin == origin && it.owner == owner && it.provider == bearer.provider }
    return copy(bearer = chooseNewestBearer(bearer, sameOwner, preferIncomingOnIssueTimeTie)?.copy(profile = profile))
}

// OAuth rotates account credentials; compare every token field while retaining the current UI profile.
internal fun AuthContext.compareAndSetBearer(expected: NativeBearerRecord, replacement: NativeBearerRecord): AuthContext? {
    if (origin != expected.origin || owner != expected.owner || replacement.origin != expected.origin || replacement.owner != expected.owner ||
        expected.provider.isNotBlank() && replacement.provider != expected.provider || replacement.profile != expected.profile || bearer?.copy(profile = expected.profile) != expected) return null
    if (nativeProvider.isNotBlank() && replacement.provider != nativeProvider) return null
    return copy(bearer = replacement.copy(profile = profile), nativeProvider = replacement.provider)
}

// Cookie refresh belongs to the account, not the profile or an ordinary context snapshot.
internal fun AuthContext.sameCookieAuthority(expected: AuthContext): Boolean =
    expected.owner.isNotBlank() && expected.nativeProvider.isNotBlank() && expected.backend == "hermes-dashboard" &&
        scope().sameAccount(expected.scope()) && backend == expected.backend &&
        bearer?.copy(profile = expected.profile) == expected.bearer && loopbackSession == expected.loopbackSession

internal fun AuthContext.compareAndSetCookies(expected: AuthContext, replacement: List<String>): AuthContext? {
    if (!sameCookieAuthority(expected) || cookies != expected.cookies) return null
    return copy(cookies = replacement)
}

/** Only an encrypted context from a previous successful bootstrap may open its local vault.
 * A password replacement starts with an empty cookie jar and never previews that old owner. */
fun AuthContext.cachedScopeFor(requestedOrigin: String, replacingAccount: Boolean, now: Long = System.currentTimeMillis()): Scope? {
    return scope().takeIf { !replacingAccount && nativeProvider.isNotBlank() && canReconnectNative(requestedOrigin, now) }
}
/** Unstamped legacy credentials may ask native Hermes for identity, never preview an unassigned vault. */
internal fun AuthContext.canReconnectNative(requestedOrigin: String, now: Long = System.currentTimeMillis()): Boolean {
    if (origin != requestedOrigin || owner.isBlank() || profile.isBlank() || backend != "hermes-dashboard") return false
    val url = runCatching { trustedOrigin(origin).toHttpUrl() }.getOrNull() ?: return false
    val bearerMatches = bearer?.let {
        it.origin == requestedOrigin && it.owner == owner && it.profile == profile && it.provider == nativeProvider &&
            it.accessToken.isNotBlank() && it.refreshToken.isNotBlank()
    } == true
    val loopbackMatches = loopbackSession?.let {
        nativeProvider == "loopback" && it.origin == requestedOrigin && it.owner == owner && owner == "local" && it.verified && it.persisted && validLoopbackSessionToken(it.token)
    } == true
    val cookieMatches = bearer == null && loopbackSession == null && cookies.any { raw -> Cookie.parse(url, raw)?.let { nativeSessionCookie(it.name) && it.value.isNotBlank() && it.secure && it.httpOnly && it.expiresAt > now && it.matches(url) } == true }
    return bearerMatches || loopbackMatches || cookieMatches
}
class CachedIdentityMismatch : SecurityException("Saved Beacon identity changed. Sign in again; local drafts are retained.")
fun AuthContext.requireCachedIdentity(scope: Scope) {
    if (nativeProvider.isBlank() || !this.scope().sameAccount(scope) || profile != scope.profile ||
        loopbackSession?.let { !it.verified || !it.persisted || it.origin != scope.origin || it.owner != scope.owner } == true) throw CachedIdentityMismatch()
}
/** AuthStore is empty after local sign-out. A profile switch preserves the owner's consent;
 * changing accounts or clearing local sign-in cannot authorize an old pending push callback. */
fun AuthContext.authorizesPushSource(scope: Scope): Boolean = backend == "hermes-dashboard" && nativeProvider.isNotBlank() && owner.isNotBlank() && this.scope().sameAccount(scope)

/** The target holds its own lock first; the store monitor covers only its synchronous mutation. */
internal fun applyDeviceRemovalIfCurrent(
    epoch: CredentialEpoch, version: Long?, admission: String?, readAdmission: () -> String?, remove: () -> Unit,
): Boolean {
    if (version == null || admission == null || !epoch.current(version) || readAdmission() != admission) return false
    remove()
    return true
}

internal fun applyRemovedAuthIfCurrent(epoch: CredentialEpoch, version: Long?, recordExists: () -> Boolean, remove: () -> Unit): Boolean {
    if (version == null || !epoch.current(version) || recordExists()) return false
    remove()
    return true
}

internal data class AuthRetirement(val removed: Boolean, val authority: DeviceRemovalAuthority? = null)
private val CANONICAL_STATE_JSON = Regex("""\{(?:"[0-9a-f]{64}":true(?:,"[0-9a-f]{64}":true)*)?\}""")

/** The caller holds the store monitor across the authority check, read and write. */
internal fun persistAuthContextIfUnchanged(
    auth: AuthContext, version: Long?, snapshot: Boolean, epoch: CredentialEpoch,
    read: () -> AuthContext, write: (AuthContext) -> Unit,
    selectProfileIf: (() -> Boolean)? = null,
): Boolean {
    if (version == null || !epoch.current(version) || selectProfileIf?.invoke() == false) return false
    val incoming = if (snapshot) {
        val stored = read()
        if (!stored.scope().sameAccount(auth.scope())) return false
        val profile = if (selectProfileIf != null) auth.profile else stored.profile
        // Exact refresh CAS can rotate without advancing this epoch, even if the clock moves back.
        auth.copy(profile = profile, bearer = stored.bearer?.copy(profile = profile), cookies = stored.cookies)
    } else auth
    val retained = incoming.copy(loopbackSession = incoming.loopbackSession?.takeIf { incoming.bearer == null })
    // Context snapshots keep the admission epoch; only new authority invalidates its readers.
    if (snapshot) write(retained)
    else epoch.mutate { write(retained.withNewestBearer(read().bearer, preferIncomingOnIssueTimeTie = true)) }
    return true
}

class AuthStore(context: Context, private val cipher: AppCipher) {
    private val prefs = context.getSharedPreferences("auth-private", Context.MODE_PRIVATE)
    internal val authorityEpoch = CredentialEpoch()
    @Volatile internal var incidentSink: ((BeaconClient, String, String, Throwable) -> Unit)? = null
    // Allows a regression to hold the actual disk write after the caller's authority check.
    @Volatile internal var ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO
    @Synchronized fun read(origin: String): AuthContext {
        return try {
            val raw = prefs.getString(origin.digest(), null) ?: return AuthContext(origin, emptyList())
            val o = JSONObject(String(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP), origin), Charsets.UTF_8))
            val arr = o.optJSONArray("cookies") ?: JSONArray()
            val owner = o.optString("owner")
            val profile = o.optString("profile", "default")
            val backend = o.optString("backend")
            val bearer = o.optJSONObject("bearer")?.let {
                NativeBearerRecord(it.optString("access_token"), it.optString("refresh_token"), it.optLong("expires_at"), it.optString("origin"), it.optString("owner"), it.optString("profile"), it.optString("provider"), it.optLong("issued_at"))
            }?.takeIf { token ->
                validNativeBearerToken(token.accessToken) &&
                    token.refreshToken.isNotBlank() && token.origin == origin && token.owner == o.optString("owner") &&
                    token.profile == profile
            }
            val loopbackSession = o.optJSONObject("loopback_session")?.let {
                LoopbackSessionRecord(it.optString("token"), it.optString("origin"), it.optString("owner"), it.optBoolean("verified"), persisted = true)
            }?.takeIf { session ->
                bearer == null && backend == "hermes-dashboard" && session.owner == "local" && session.verified &&
                    session.origin == origin && session.owner == owner && validLoopbackSessionToken(session.token)
            }
            val provider = o.optString("native_provider").ifBlank { bearer?.provider.orEmpty().ifBlank { if (loopbackSession != null) "loopback" else "" } }
            require(bearer == null || bearer.provider.isBlank() || bearer.provider == provider)
            require(loopbackSession == null || provider == "loopback")
            AuthContext(origin, (0 until arr.length()).map { arr.getString(it) }, owner, profile, o.optString("csrf"), backend, bearer, loopbackSession, provider)
        } catch (_: Exception) { AuthContext(origin, emptyList()) }
    }
    suspend fun persist(auth: AuthContext) = kotlinx.coroutines.withContext(ioDispatcher) { write(auth) }
    internal suspend fun persistIfUnchanged(auth: AuthContext, version: Long?, snapshot: Boolean = false, selectProfileIf: (() -> Boolean)? = null) = kotlinx.coroutines.withContext(ioDispatcher) {
        synchronized(this@AuthStore) {
            persistAuthContextIfUnchanged(auth, version, snapshot, authorityEpoch, read = { read(auth.origin) }, write = { writeRecord(it, newAdmission = !snapshot) }, selectProfileIf = selectProfileIf)
        }
    }
    internal suspend fun removeIfUnchanged(origin: String, version: Long?) = kotlinx.coroutines.withContext(ioDispatcher) {
        synchronized(this@AuthStore) {
            if (version != null && authorityEpoch.current(version)) { clear(origin); true } else false
        }
    }
    internal suspend fun retireIfUnchanged(scope: Scope, version: Long?): AuthRetirement {
        val store = this
        return kotlinx.coroutines.withContext(ioDispatcher) {
            synchronized(store) {
                if (version == null || !authorityEpoch.current(version)) AuthRetirement(false)
                else { clear(scope.origin); AuthRetirement(true, removedAuthority(scope)) }
            }
        }
    }
    private fun removedAuthority(scope: Scope): DeviceRemovalAuthority {
        val version = authorityEpoch.readVersion()
        return DeviceRemovalAuthority(scope) { remove -> synchronized(this) {
            applyRemovedAuthIfCurrent(authorityEpoch, version, { prefs.contains(scope.origin.digest()) }, remove)
        } }
    }
    suspend fun remove(origin: String) = kotlinx.coroutines.withContext(ioDispatcher) { clear(origin) }
    fun write(auth: AuthContext) = authorityEpoch.mutate { synchronized(this) { writeRecord(auth.withNewestBearer(read(auth.origin).bearer), newAdmission = true) } }
    private fun storedIncidentAdmission(origin: String): String? = runCatching {
        val raw = prefs.getString(origin.digest(), null) ?: return@runCatching null
        JSONObject(String(cipher.decrypt(Base64.decode(raw, Base64.NO_WRAP), origin), Charsets.UTF_8))
            .optString("incident_admission").takeIf { it == java.util.UUID.fromString(it).toString() }
    }.getOrNull()
    @Synchronized internal fun incidentAdmission(scope: Scope): String? {
        val version = authorityEpoch.readVersion() ?: return null
        val current = read(scope.origin)
        if (current.cachedScopeFor(scope.origin, replacingAccount = false) != scope) return null
        val admission = storedIncidentAdmission(scope.origin) ?: run {
            writeRecord(current)
            storedIncidentAdmission(scope.origin)
        }
        return admission.takeIf { authorityEpoch.current(version) }
    }
    @Synchronized internal fun withIncidentAdmission(scope: Scope, admission: String, enqueue: () -> Boolean): Boolean =
        incidentAdmission(scope) == admission && enqueue()
    @Synchronized internal fun applyDeviceRemoval(scope: Scope, version: Long?, admission: String?, remove: () -> Unit): Boolean =
        applyDeviceRemovalIfCurrent(authorityEpoch, version, admission, { incidentAdmission(scope) }, remove)
    @Synchronized internal fun deviceRemovalAuthority(scope: Scope): DeviceRemovalAuthority? {
        val version = authorityEpoch.readVersion() ?: return null
        val admission = incidentAdmission(scope) ?: return null
        return DeviceRemovalAuthority(scope) { remove -> applyDeviceRemoval(scope, version, admission, remove) }
    }
    private fun canonicalKey(origin: String) = "canonical:${origin.digest()}"
    private fun canonicalRecord(origin: String): JSONObject {
        val value = prefs.getString(canonicalKey(origin), null) ?: return JSONObject()
        require(CANONICAL_STATE_JSON.matches(value)) { "Canonical gateway state is damaged" }
        return JSONObject(value).also { record ->
            require(record.length() == value.count { it == ':' }) { "Canonical gateway state is damaged" }
        }
    }
    @Synchronized internal fun canonicalRequired(scope: Scope): Boolean {
        val record = canonicalRecord(scope.origin)
        // An unstamped credential cannot prove which saved provider it belongs to.
        if (scope.nativeProvider.isBlank()) return record.length() > 0
        return record.opt(scope.copy(session = "").key) == true || record.opt(scope.copy(session = "", nativeProvider = "").key) == true
    }
    @Synchronized internal fun rememberCanonical(scope: Scope, version: Long?): Boolean {
        if (version == null || !authorityEpoch.current(version)) return false
        val record = canonicalRecord(scope.origin).put(scope.copy(session = "").key, true)
        check(prefs.edit().putString(canonicalKey(scope.origin), record.toString()).commit()) { "Could not retain canonical gateway authority" }
        return authorityEpoch.current(version)
    }
    @Synchronized internal fun withLegacyCanonicalPermit(scope: Scope, version: Long?, send: () -> Boolean): Boolean =
        version != null && authorityEpoch.current(version) && !canonicalRequired(scope) && send()
    private fun writeRecord(auth: AuthContext, newAdmission: Boolean = false) {
        val admission = if (!newAdmission && read(auth.origin).scope() == auth.scope()) storedIncidentAdmission(auth.origin) else null
        val o = JSONObject().put("cookies", JSONArray(auth.cookies)).put("owner", auth.owner).put("profile", auth.profile).put("csrf", auth.csrf).put("backend", auth.backend).put("native_provider", auth.nativeProvider)
            .put("incident_admission", admission ?: java.util.UUID.randomUUID().toString())
        auth.bearer?.let { token ->
            require(validNativeBearerToken(token.accessToken) && token.refreshToken.isNotBlank()) { "Bearer credentials are incomplete" }
            require(token.origin == auth.origin && token.owner == auth.owner && token.profile == auth.profile) { "Bearer authority does not match the saved account" }
            require(token.provider == auth.nativeProvider) { "Bearer provider does not match the saved account" }
            val stored = JSONObject().put("access_token", token.accessToken).put("refresh_token", token.refreshToken).put("expires_at", token.expiresAt).put("origin", token.origin).put("owner", token.owner).put("profile", token.profile).put("provider", token.provider)
            if (token.issuedAt > 0) stored.put("issued_at", token.issuedAt)
            o.put("bearer", stored)
        }
        auth.loopbackSession?.let { session ->
            require(validLoopbackSessionToken(session.token)) { "Loopback session is invalid" }
            require(session.verified && auth.bearer == null && auth.backend == "hermes-dashboard" && session.owner == "local" && session.origin == auth.origin && session.owner == auth.owner) { "Loopback session authority does not match the saved account" }
            o.put("loopback_session", JSONObject().put("token", session.token).put("origin", session.origin).put("owner", session.owner).put("verified", session.verified))
        }
        check(prefs.edit().putString(auth.origin.digest(), Base64.encodeToString(cipher.encrypt(o.toString().toByteArray(), auth.origin), Base64.NO_WRAP)).commit()) { "Could not retain sign-in" }
    }
    internal fun replaceBearer(expected: NativeBearerRecord, replacement: NativeBearerRecord): Boolean = synchronized(this) {
        val replacementContext = read(expected.origin).compareAndSetBearer(expected, replacement) ?: return@synchronized false
        if (expected.provider != replacement.provider) authorityEpoch.mutate { writeRecord(replacementContext, newAdmission = true) } else writeRecord(replacementContext)
        true
    }
    internal fun replaceCookies(expected: AuthContext, replacement: List<String>, version: Long?): Boolean = synchronized(this) {
        if (version == null || !authorityEpoch.current(version)) return@synchronized false
        val replacementContext = read(expected.origin).compareAndSetCookies(expected, replacement) ?: return@synchronized false
        writeRecord(replacementContext)
        true
    }
    internal fun removeIfBearerUnchanged(expected: NativeBearerRecord): Boolean = retireBearerIfUnchanged(expected).removed
    internal fun retireBearerIfUnchanged(expected: NativeBearerRecord): AuthRetirement = synchronized(this) {
        val stored = read(expected.origin)
        if (stored.bearer?.copy(profile = expected.profile) != expected) return@synchronized AuthRetirement(false)
        clear(expected.origin)
        AuthRetirement(true, removedAuthority(stored.scope()))
    }
    internal fun replaceLoopbackSession(expected: LoopbackSessionRecord, replacement: LoopbackSessionRecord): Boolean = synchronized(this) {
        if (!expected.verified || !expected.persisted || replacement.origin != expected.origin || replacement.owner != expected.owner || !replacement.verified) return@synchronized false
        val current = read(expected.origin)
        if (current.loopbackSession != expected) return@synchronized false
        authorityEpoch.mutate { writeRecord(current.copy(loopbackSession = replacement)) }
        true
    }
    fun clear(origin: String) = authorityEpoch.mutate { synchronized(this) { check(prefs.edit().remove(origin.digest()).remove(canonicalKey(origin)).commit()) } }
}

/** Each client keeps its profile view; same-admission clients adopt retained account cookies before requests. */
class ScopedCookies(private val origin: HttpUrl, values: List<String>, private val bearerActive: () -> Boolean = { false }) : CookieJar {
    private val cookies = values.mapNotNull { Cookie.parse(origin, it) }.toMutableList()
    @Synchronized override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        if (url.host != origin.host || url.port != origin.port || url.scheme != origin.scheme) return
        received.filter { it.matches(url) }.forEach { cookie ->
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = if (!bearerActive() && url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
    @Synchronized internal fun replaceIfUnchanged(expected: List<String>, replacement: List<String>): Boolean {
        // Serialized expiry is second-precision; response cookies may still hold milliseconds.
        if (cookies.map { it.toString() } != expected.mapNotNull { Cookie.parse(origin, it)?.toString() }) return false
        cookies.clear()
        cookies.addAll(replacement.mapNotNull { Cookie.parse(origin, it) })
        return true
    }
    @Synchronized fun snapshot(): List<String> = cookies.map { it.toString() }
}
