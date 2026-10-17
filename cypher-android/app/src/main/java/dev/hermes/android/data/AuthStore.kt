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

data class AuthContext(val origin: String, val cookies: List<String>, val owner: String = "", val profile: String = "default", val csrf: String = "", val backend: String = "")

/** Only an encrypted context from a previous successful bootstrap may open its local vault.
 * A password replacement starts with an empty cookie jar and never previews that old owner. */
fun AuthContext.cachedScopeFor(requestedOrigin: String, replacingAccount: Boolean, now: Long = System.currentTimeMillis()): Scope? {
    if (replacingAccount || origin != requestedOrigin || owner.isBlank() || profile.isBlank() || backend != "hermes-dashboard") return null
    val url = runCatching { trustedOrigin(origin).toHttpUrl() }.getOrNull() ?: return null
    if (cookies.none { raw -> Cookie.parse(url, raw)?.let { nativeSessionCookie(it.name) && it.value.isNotBlank() && it.secure && it.httpOnly && it.expiresAt > now && it.matches(url) } == true }) return null
    return Scope(requestedOrigin, owner, profile)
}
class CachedIdentityMismatch : SecurityException("Saved Cypher identity changed. Sign in again; local drafts are retained.")
fun AuthContext.requireCachedIdentity(scope: Scope) {
    if (origin != scope.origin || owner != scope.owner || profile != scope.profile) throw CachedIdentityMismatch()
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
            AuthContext(origin, (0 until arr.length()).map { arr.getString(it) }, o.optString("owner"), o.optString("profile", "default"), o.optString("csrf"), o.optString("backend"))
        } catch (_: Exception) { AuthContext(origin, emptyList()) }
    }
    suspend fun persist(auth: AuthContext) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { write(auth) }
    internal suspend fun persistIfUnchanged(auth: AuthContext, version: Long?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(this@AuthStore) {
            if (version != null && authorityEpoch.current(version)) { write(auth); true } else false
        }
    }
    internal suspend fun removeIfUnchanged(origin: String, version: Long?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        synchronized(this@AuthStore) {
            if (version != null && authorityEpoch.current(version)) { clear(origin); true } else false
        }
    }
    suspend fun remove(origin: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { clear(origin) }
    fun write(auth: AuthContext) = authorityEpoch.mutate { synchronized(this) {
        val o = JSONObject().put("cookies", JSONArray(auth.cookies)).put("owner", auth.owner).put("profile", auth.profile).put("csrf", auth.csrf).put("backend", auth.backend)
        check(prefs.edit().putString(auth.origin.digest(), Base64.encodeToString(cipher.encrypt(o.toString().toByteArray(), auth.origin), Base64.NO_WRAP)).commit()) { "Could not retain sign-in" }
    } }
    fun clear(origin: String) = authorityEpoch.mutate { synchronized(this) { check(prefs.edit().remove(origin.digest()).commit()) } }
}

/** One jar belongs to one client. A call gets a detached copy so UI profile switches cannot rebind it. */
class ScopedCookies(private val origin: HttpUrl, values: List<String>) : CookieJar {
    private val cookies = values.mapNotNull { Cookie.parse(origin, it) }.toMutableList()
    @Synchronized override fun saveFromResponse(url: HttpUrl, received: List<Cookie>) {
        if (url.host != origin.host || url.port != origin.port || url.scheme != origin.scheme) return
        received.filter { it.matches(url) }.forEach { cookie ->
            cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
            if (cookie.expiresAt > System.currentTimeMillis()) cookies.add(cookie)
        }
    }
    @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = if (url.host == origin.host && url.port == origin.port && url.scheme == origin.scheme) cookies.filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) } else emptyList()
    @Synchronized fun snapshot(): List<String> = cookies.map { it.toString() }
}
