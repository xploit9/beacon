package dev.hermes.android

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.digest
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.cachedScopeFor
import kotlinx.coroutines.runBlocking
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Test

class PersistentSignInTest {
    @Test fun corruptCredentialNeverRestoresAuthorityOrCrashesStartup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val origin="https://corrupt-${java.util.UUID.randomUUID()}.example"
        val prefs=context.getSharedPreferences("auth-private",Context.MODE_PRIVATE)
        try {
            assertTrue(prefs.edit().putString(origin.digest(),"not a valid encrypted credential").commit())
            val read=AuthStore(context,AppCipher()).read(origin)
            assertTrue(read.cookies.isEmpty());assertTrue(read.owner.isEmpty())
            assertNull(read.cachedScopeFor(origin,false))
        } finally { prefs.edit().remove(origin.digest()).commit() }
    }
    @Test fun encryptedSessionSurvivesStoreRecreationAndLogoutFencesOldRefresh() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val origin = "https://restore-${java.util.UUID.randomUUID()}.example"
        val token = "fixture-${java.util.UUID.randomUUID()}"
        val cookie = Cookie.Builder().name("__Host-hermes_session_rt").value(token)
            .hostOnlyDomain(origin.removePrefix("https://")).path("/").secure().httpOnly()
            .expiresAt(System.currentTimeMillis() + 60_000).build()
        val auth = AuthContext(origin, listOf(cookie.toString()), "fixture-owner", "work", backend = "hermes-dashboard")
        val first = AuthStore(context, AppCipher())
        try {
            first.persist(auth)
            val raw = context.getSharedPreferences("auth-private", Context.MODE_PRIVATE).getString(origin.digest(), "")!!
            assertFalse(raw.contains(token))
            val recreated = AuthStore(context, AppCipher())
            assertEquals(auth, recreated.read(origin))
            assertNotNull(recreated.read(origin).cachedScopeFor(origin, false))
            val version = recreated.authorityEpoch.readVersion()
            recreated.remove(origin)
            assertFalse(recreated.persistIfUnchanged(auth, version))
            assertTrue(AuthStore(context, AppCipher()).read(origin).cookies.isEmpty())
        } finally { first.remove(origin) }
    }
}
