package dev.hermes.android.ui.vault

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconApplication
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.NativeBearerRecord
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real encrypted-store guards in a separate synthetic preference namespace; no owner auth is opened. */
class NativeAdmissionGuardDeviceTest {
    private fun fixture(action: (AuthStore, AuthContext, Context) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val name = "qa-admission-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        val origin = "https://admission-fixture.invalid"
        val auth = AuthContext(origin, emptyList(), "fixture-owner", "default", backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", 4_000_000_000L, origin, "fixture-owner", "default", "basic"))
        try { action(AuthStore(context, AppCipher()), auth, context) }
        finally { assertTrue(app.deleteSharedPreferences(name)) }
    }

    @Test fun profileAbaChangesAdmissionWhileCredentialRefreshAndRestartRetainIt() = fixture { store, auth, context ->
        store.write(auth)
        val first = requireNotNull(store.incidentAdmission(auth.scope()))
        val rotated = requireNotNull(auth.bearer).copy(accessToken = "fixture-rotated-access", refreshToken = "fixture-rotated-refresh")
        assertTrue(store.replaceBearer(requireNotNull(auth.bearer), rotated))
        assertEquals(first, store.incidentAdmission(auth.scope()))
        fun select(profile: String) = runBlocking {
            val current = store.read(auth.origin)
            val selected = current.copy(profile = profile, bearer = current.bearer?.copy(profile = profile))
            assertTrue(store.persistIfUnchanged(selected, store.authorityEpoch.readVersion(), snapshot = true, selectProfileIf = { true }))
            requireNotNull(store.incidentAdmission(selected.scope()))
        }
        val other = select("other")
        assertNull(store.incidentAdmission(auth.scope()))
        val again = select("default")
        assertNotEquals(first, other); assertNotEquals(first, again); assertNotEquals(other, again)
        var sent = false
        assertFalse(store.withIncidentAdmission(auth.scope(), first) { sent = true; true })
        assertFalse(sent)
        assertEquals(again, AuthStore(context, AppCipher()).incidentAdmission(auth.scope()))
    }

    @Test fun signInAndSignOutCannotReviveAnOldAdmission() = fixture { store, auth, _ ->
        store.write(auth); val first = requireNotNull(store.incidentAdmission(auth.scope()))
        store.write(auth); val next = requireNotNull(store.incidentAdmission(auth.scope()))
        assertNotEquals(first, next)
        store.clear(auth.origin)
        assertNull(store.incidentAdmission(auth.scope()))
        assertFalse(store.withIncidentAdmission(auth.scope(), next) { true })
        store.write(auth)
        assertNotEquals(next, store.incidentAdmission(auth.scope()))
    }

    @Test fun aProfileCommitCannotInterleaveWithTheFinalResponseEnqueue() = fixture { store, auth, _ ->
        store.write(auth); val admission = requireNotNull(store.incidentAdmission(auth.scope()))
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val mutationStarted = CountDownLatch(1)
        val threads = Executors.newFixedThreadPool(2)
        try {
            val send = threads.submit<Boolean> { store.withIncidentAdmission(auth.scope(), admission) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); true
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val change = threads.submit { mutationStarted.countDown(); store.write(auth.copy(profile = "other", bearer = auth.bearer?.copy(profile = "other"))) }
            assertTrue(mutationStarted.await(5, TimeUnit.SECONDS))
            assertFalse(change.isDone)
            release.countDown(); assertTrue(send.get(5, TimeUnit.SECONDS)); change.get(5, TimeUnit.SECONDS)
            assertFalse(store.withIncidentAdmission(auth.scope(), admission) { true })
        } finally { release.countDown(); threads.shutdownNow(); assertTrue(threads.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
