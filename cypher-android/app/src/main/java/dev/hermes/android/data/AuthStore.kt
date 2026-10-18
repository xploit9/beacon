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
    value.isNotBlank() && value.length <= 4_096 && value.none { it.isISOControl() }

data class AuthContext(
    val origin: String,
    val cookies: List<String>,
    val owner: String = "",
    val profile: String = "default",
    val csrf: String = "",
    val backend: String = "",
    val bearer: NativeBearerRecord? = null,
    val loopbackSession: LoopbackSessionRecord? = null,
) {
    override fun toString() = "AuthContext(origin=$origin, cookies=${cookies.size}, owner=$owner, profile=$profile, csrf=<redacted>, backend=$backend, bearer=$bearer, loopbackSession=$loopbackSession)"
}

/** Only an encrypted context from a previous successful bootstrap may open its local vault.
 * A password replacement starts with an empty cookie jar and never previews that old owner. */
fun AuthContext.cachedScopeFor(requestedOrigin: String, replacingAccount: Boolean, now: Long = System.currentTimeMillis()): Scope? {
    if (replacingAccount || origin != requestedOrigin || owner.isBlank() || profile.isBlank() || backend != "hermes-dashboard") return null
    val url = runCatching { trustedOrigin(origin).toHttpUrl() }.getOrNull() ?: return null
    val bearerMatches = bearer?.let {
        it.origin == requestedOrigin && it.owner == owner && it.profile == profile &&
            it.accessToken.isNotBlank() && it.refreshToken.isNotBlank()
    } == true
    val loopbackMatches = loopbackSession?.let {
        it.origin == requestedOrigin && it.owner == owner && owner == "local" && it.verified && it.persisted && validLoopbackSessionToken(it.token)
    } == true
    val cookieMatches = loopbackSession == null && cookies.any { raw -> Cookie.parse(url, raw)?.let { nativeSessionCookie(it.name) && it.value.isNotBlank() && it.secure && it.httpOnly && it.expiresAt > now && it.matches(url) } == true }
    if (!bearerMatches && !loopbackMatches && !cookieMatches) return null
    return Scope(requestedOrigin, owner, profile)
}
class CachedIdentityMismatch : SecurityException("Saved Cypher identity changed. Sign in again; local drafts are retained.")
fun AuthContext.requireCachedIdentity(scope: Scope) {
    if (origin != scope.origin || owner != scope.owner || profile != scope.profile ||
        loopbackSession?.let { !it.verified || !it.persisted || it.origin != scope.origin || it.owner != scope.owner } == true) throw CachedIdentityMismatch()
}
/** AuthStore is empty after local sign-out. A profile switch preserves the owner's consent;
 * changing accounts or clearing local sign-in cannot authorize an old pending push callback. */
fun AuthContext.authorizesPushSource(scope: Scope): Boolean = backend == "hermes-dashboard" && origin == scope.origin && owner.isNotBlank() && owner == scope.owner

class AuthStore(context: Context, private val cipher: AppCipher) {
    private val prefs = context.getSharedPreferences("auth-private", Context.MODE_PRIVATE)
    internal val authorityEpoch = CredentialEpoch()
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
                token.accessToken.isNotBlank() && token.accessToken.none { it.isWhitespace() || it.isISOControl() } &&
                    token.refreshToken.isNotBlank() && token.origin == origin && token.owner == o.optString("owner") &&
                    token.profile == profile
            }
            val loopbackSession = o.optJSONObject("loopback_session")?.let {
                LoopbackSessionRecord(it.optString("token"), it.optString("origin"), it.optString("owner"), it.optBoolean("verified"), persisted = true)
            }?.takeIf { session ->
                bearer == null && backend == "hermes-dashboard" && session.owner == "local" && session.verified &&
                    session.origin == origin && session.owner == owner && validLoopbackSessionToken(session.token)
            }
            AuthContext(origin, (0 until arr.length()).map { arr.getString(it) }, owner, profile, o.optString("csrf"), backend, bearer, loopbackSession)
        } catch (_: Exception) { AuthContext(origin, emptyList()) }
    }
    suspend fun persist(auth: AuthContext) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { write(auth) }
    internal suspend fun persistIfUnchanged(auth: AuthContext, version: Long?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(this@AuthStore) {
            if (version != null && authorityEpoch.current(version)) {
                val current = read(auth.origin)
                val storedBearer = if (auth.loopbackSession == null) current.bearer?.takeIf { it.origin == auth.origin && it.owner == auth.owner && it.profile == auth.profile } else null
                // Use issuance time for current records and expiry as the legacy fallback; ties prefer incoming auth.
                val newestBearer = when {
                    storedBearer == null -> auth.bearer
                    auth.bearer == null -> storedBearer
                    storedBearer.issuedAt > 0 && auth.bearer.issuedAt > 0 -> if (storedBearer.issuedAt > auth.bearer.issuedAt) storedBearer else auth.bearer
                    storedBearer.issuedAt > 0 -> storedBearer
                    auth.bearer.issuedAt > 0 -> auth.bearer
                    storedBearer.expiresAt > auth.bearer.expiresAt -> storedBearer
                    else -> auth.bearer
                }
                write(auth.copy(
                    bearer = newestBearer ?: auth.bearer,
                    loopbackSession = if (newestBearer != null || auth.bearer != null) null else auth.loopbackSession,
                ))
                true
            } else false
        }
    }
    internal suspend fun removeIfUnchanged(origin: String, version: Long?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(this@AuthStore) {
            if (version != null && authorityEpoch.current(version)) { clear(origin); true } else false
        }
    }
    suspend fun remove(origin: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { clear(origin) }
    fun write(auth: AuthContext) = authorityEpoch.mutate { synchronized(this) { writeRecord(auth) } }
    private fun writeRecord(auth: AuthContext) {
        val o = JSONObject().put("cookies", JSONArray(auth.cookies)).put("owner", auth.owner).put("profile", auth.profile).put("csrf", auth.csrf).put("backend", auth.backend)
        auth.bearer?.let { token ->
            require(token.accessToken.isNotBlank() && token.accessToken.none { it.isWhitespace() || it.isISOControl() } && token.refreshToken.isNotBlank()) { "Bearer credentials are incomplete" }
            require(token.origin == auth.origin && token.owner == auth.owner && token.profile == auth.profile) { "Bearer authority does not match the saved account" }
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
        if (replacement.origin != expected.origin || replacement.owner != expected.owner || replacement.profile != expected.profile) return@synchronized false
        val current = read(expected.origin)
        if (current.bearer != expected) return@synchronized false
        writeRecord(current.copy(bearer = replacement))
        true
    }
    internal fun removeIfBearerUnchanged(expected: NativeBearerRecord): Boolean = synchronized(this) {
        if (read(expected.origin).bearer != expected) return@synchronized false
        clear(expected.origin)
        true
    }
    internal fun replaceLoopbackSession(expected: LoopbackSessionRecord, replacement: LoopbackSessionRecord): Boolean = synchronized(this) {
        if (!expected.verified || !expected.persisted || replacement.origin != expected.origin || replacement.owner != expected.owner || !replacement.verified) return@synchronized false
        val current = read(expected.origin)
        if (current.loopbackSession != expected) return@synchronized false
        authorityEpoch.mutate { writeRecord(current.copy(loopbackSession = replacement)) }
        true
    }
    fun clear(origin: String) = authorityEpoch.mutate { synchronized(this) { check(prefs.edit().remove(origin.digest()).commit()) } }
}

/** One jar belongs to one client. A call gets a detached copy so UI profile switches cannot rebind it. */
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
    @Synchronized fun snapshot(): List<String> = cookies.map { it.toString() }
}
