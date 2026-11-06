package dev.hermes.android.data

import android.content.ContextWrapper
import android.content.SharedPreferences
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

internal class CanonicalStoreFixture {
    val values = mutableMapOf<String, String>()
    private val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0] as String] ?: args[1]
            "edit" -> {
                val writes = mutableMapOf<String, String?>()
                lateinit var editor: SharedPreferences.Editor
                editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, edit, inputs ->
                    when (edit.name) {
                        "putString" -> { writes[inputs!![0] as String] = inputs[1] as String; editor }
                        "remove" -> { writes[inputs!![0] as String] = null; editor }
                        "commit" -> { writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; true }
                        else -> error("Unexpected editor operation: ${edit.name}")
                    }
                } as SharedPreferences.Editor
                editor
            }
            else -> error("Unexpected preference operation: ${method.name}")
        }
    } as SharedPreferences
    fun newStore() = AuthStore(object : ContextWrapper(null) {
        override fun getSharedPreferences(ignored: String, mode: Int) = prefs
    }, AppCipher())
}

class AuthStoreTest {
    @Test fun nonAsciiCredentialsCannotReachHttpHeaders() {
        val origin = "https://native.example"
        for (value in listOf("fixture-\u00e9", "fixture-\u00a0", "fixture-\u007f", "fixture\nother")) {
            assertFalse(validLoopbackSessionToken(value))
            val bearer = NativeBearerRecord(value, "fixture-refresh", 4_000_000_000, origin, "owner", "default", "basic")
            val remote = BeaconClient(AuthContext(origin, emptyList(), "owner", "default", backend = "hermes-dashboard", bearer = bearer))
            val local = BeaconClient(AuthContext(origin, emptyList(), "local", "default", backend = "hermes-dashboard",
                loopbackSession = LoopbackSessionRecord(value, origin, "local", verified = true)))
            try {
                assertNull(remote.request("/api/profiles").build().header("Authorization"))
                assertNull(local.request("/api/profiles").build().header(HERMES_LOOPBACK_SESSION_HEADER))
            } finally { remote.closeNative(); local.closeNative() }
        }
        assertTrue(validLoopbackSessionToken("fixture-safe_123"))
    }

    @Test fun canonicalRequirementSurvivesRecreationStaysScopedAndClearsWithSignOut() {
        val fixture = CanonicalStoreFixture()
        val scope = Scope("https://native.example", "owner", "default", nativeProvider = "basic")
        val original = fixture.newStore()
        val version = original.authorityEpoch.readVersion()
        assertFalse(original.canonicalRequired(scope))
        assertTrue(original.rememberCanonical(scope, version))
        assertTrue(original.canonicalRequired(scope))
        assertFalse(original.canonicalRequired(scope.copy(profile = "work")))
        assertFalse(original.canonicalRequired(scope.copy(owner = "another")))
        assertFalse(original.canonicalRequired(scope.copy(nativeProvider = "another")))
        assertTrue("An unverified provider cannot forget known canonical authority", original.canonicalRequired(scope.copy(nativeProvider = "")))
        val unstamped = scope.copy(profile = "unstamped", nativeProvider = "")
        assertTrue(original.rememberCanonical(unstamped, version))
        assertTrue(original.canonicalRequired(unstamped.copy(nativeProvider = "basic")))
        assertTrue(fixture.values.values.none { it.contains(scope.origin) || it.contains(scope.owner) })
        val recreated = fixture.newStore()
        assertTrue(recreated.canonicalRequired(scope))
        assertFalse(recreated.withLegacyCanonicalPermit(scope, recreated.authorityEpoch.readVersion()) { error("A remembered canonical profile cannot use legacy") })
        assertTrue(recreated.withLegacyCanonicalPermit(scope.copy(profile = "work"), recreated.authorityEpoch.readVersion()) { true })
        recreated.clear(scope.origin)
        assertFalse(fixture.newStore().canonicalRequired(scope))
        assertFalse(original.rememberCanonical(scope, null))
        val stale = original.authorityEpoch.readVersion()
        original.authorityEpoch.mutate { }
        assertFalse(original.rememberCanonical(scope, stale))
        for (damaged in listOf("not-json", "{} trailing", "{\"${scope.key}\":false}", "{\"${scope.key}\":true,\"${scope.key}\":true}")) {
            fixture.values["canonical:${scope.origin.digest()}"] = damaged
            assertTrue(runCatching { fixture.newStore().canonicalRequired(scope) }.isFailure)
        }
    }

    @Test fun postRemovalAuthorityRejectsReadmissionPendingWritersAndUnknownEpoch() {
        val epoch = CredentialEpoch()
        var exists = true
        epoch.mutate { exists = false }
        val removedVersion = epoch.readVersion()
        var cleaned = 0
        assertTrue(applyRemovedAuthIfCurrent(epoch, removedVersion, { exists }) { cleaned++ })
        assertEquals(1, cleaned)
        assertFalse(applyRemovedAuthIfCurrent(epoch, null, { exists }) { error("unknown epoch") })
        epoch.mutate {
            assertFalse(applyRemovedAuthIfCurrent(epoch, removedVersion, { exists }) { error("pending writer") })
            exists = true
        }
        assertFalse(applyRemovedAuthIfCurrent(epoch, removedVersion, { exists }) { error("fresh sign-in") })
        assertFalse(applyRemovedAuthIfCurrent(epoch, epoch.readVersion(), { exists }) { error("stored record exists") })
    }

    @Test fun removalOperationsUseTheInjectedIoDispatcher() = runBlocking {
        for (conditional in listOf(false, true)) {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, _ ->
                when (method.name) { "remove" -> editor; "commit" -> true; else -> error("Unexpected editor operation") }
            } as SharedPreferences.Editor
            val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) { "edit" -> editor; "getString" -> args!![1]; else -> error("Unexpected preference operation") }
            } as SharedPreferences
            val store = AuthStore(object : ContextWrapper(null) {
                override fun getSharedPreferences(ignored: String, mode: Int) = prefs
            }, AppCipher())
            val scheduled = LinkedBlockingQueue<Runnable>()
            store.ioDispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { scheduled.put(block) }
            }
            val job = async(start = CoroutineStart.UNDISPATCHED) {
                if (conditional) store.removeIfUnchanged("https://native.example", store.authorityEpoch.readVersion())
                else { store.remove("https://native.example"); true }
            }
            val task = scheduled.poll(1, TimeUnit.SECONDS)
            try {
                assertNotNull("Removal must use the injected dispatcher", task)
                task?.run()
                assertTrue(withTimeout(2000) { job.await() })
            } finally { job.cancel() }
        }
    }
    /** Runs AuthStore's real transaction policy without Android encryption or preferences. */
    private class SnapshotStore(initial: AuthContext) {
        val epoch = CredentialEpoch()
        var stored = initial
        fun persist(auth: AuthContext, version: Long?, snapshot: Boolean = true, selectProfileIf: (() -> Boolean)? = null) = persistAuthContextIfUnchanged(
            auth, version, snapshot, epoch, read = { stored }, write = { stored = it }, selectProfileIf = selectProfileIf,
        )
    }

    private fun snapshotContext(): AuthContext {
        val token = NativeBearerRecord("access", "refresh", 3000, "https://native.example", "alice", "default", "basic", issuedAt = 200)
        return AuthContext(token.origin, emptyList(), token.owner, token.profile, backend = "hermes-dashboard", bearer = token)
    }

    @Test fun interleavedSameAccountSnapshotsKeepAuthorityAndPersistTheIncomingProfile() {
        for (cookieOnly in listOf(false, true)) {
            val initial = snapshotContext().let { if (cookieOnly) it.copy(bearer = null, cookies = listOf("__Host-hermes_session_rt=fixture; Path=/; Secure; HttpOnly")) else it }
            val store = SnapshotStore(initial)
            val captured = store.epoch.readVersion()
            val history = initial.copy(cookies = initial.cookies + "preferences=history; Path=/; Secure")
            val switched = initial.copy(profile = "coder", bearer = initial.bearer?.copy(profile = "coder"))

            assertTrue(store.persist(history, captured))
            assertTrue("An ordinary history snapshot must not fence a captured profile switch", store.persist(switched, captured, selectProfileIf = { true }))
            assertEquals("coder", store.stored.profile)
            assertEquals(switched.bearer, store.stored.bearer)
            assertEquals(initial.nativeProvider, store.stored.nativeProvider)
            assertEquals(captured, store.epoch.readVersion())
        }
    }

    @Test fun aLateHistorySnapshotCannotRollBackACompletedProfileSelection() {
        val initial = snapshotContext()
        val store = SnapshotStore(initial)
        val captured = store.epoch.readVersion()
        assertTrue(store.persist(initial.copy(profile = "coder"), captured, selectProfileIf = { true }))
        assertTrue(store.persist(initial.copy(csrf = "history"), captured))
        assertEquals("coder", store.stored.profile)
        assertEquals(initial.bearer!!.copy(profile = "coder"), store.stored.bearer)
        assertEquals(captured, store.epoch.readVersion())
    }

    @Test fun sameAccountSnapshotsKeepTheExactlyRotatedCookiesInEitherOrder() {
        val initial = snapshotContext().copy(bearer = null, cookies = listOf("__Host-hermes_session_rt=before; Path=/; Secure; HttpOnly"))
        val rotated = listOf("__Host-hermes_session_rt=after; Path=/; Secure; HttpOnly")
        for (historyFirst in listOf(false, true)) {
            val store = SnapshotStore(initial)
            val captured = store.epoch.readVersion()
            val history = initial.copy(csrf = "history")
            val selection = initial.copy(profile = "coder")
            store.stored = initial.copy(cookies = rotated)
            if (historyFirst) assertTrue(store.persist(history, captured))
            assertTrue(store.persist(selection, captured, selectProfileIf = { true }))
            if (!historyFirst) assertTrue(store.persist(history, captured))
            assertEquals(rotated, store.stored.cookies)
            assertEquals("coder", store.stored.profile)
            assertEquals(captured, store.epoch.readVersion())
        }
    }

    @Test fun cookieRotationRequiresTheExactAccountAndJarAndKeepsCurrentAuthority() {
        val initial = snapshotContext().copy(bearer = null, cookies = listOf("session=before"))
        val selected = initial.copy(profile = "coder", csrf = "selected-csrf")
        val rotated = listOf("session=after")
        val saved = requireNotNull(selected.compareAndSetCookies(initial, rotated))
        assertEquals(selected.copy(cookies = rotated), saved)
        assertNull(saved.compareAndSetCookies(initial, initial.cookies))
        for (changed in listOf(selected.copy(cookies = listOf("session=other")), selected.copy(owner = "bob"),
            selected.copy(origin = "https://other.example"), selected.copy(nativeProvider = "oidc"),
            selected.copy(backend = "other-backend"), selected.copy(bearer = snapshotContext().bearer),
            selected.copy(loopbackSession = LoopbackSessionRecord("fixture", initial.origin, "alice", verified = true)))) {
            assertNull(changed.compareAndSetCookies(initial, rotated))
        }
        assertNull(AuthContext(initial.origin, emptyList()).compareAndSetCookies(initial, rotated))
        assertNull(initial.copy(nativeProvider = "").compareAndSetCookies(initial.copy(nativeProvider = ""), rotated))
    }

    @Test fun interleavedSnapshotCannotReviveLogoutOrOverwriteAFreshAdmission() {
        val initial = snapshotContext()
        val empty = AuthContext(initial.origin, emptyList())
        val replacement = initial.copy(bearer = initial.bearer!!.copy(accessToken = "fresh", refreshToken = "fresh-refresh", issuedAt = 100))
        for (newer in listOf(empty, initial, replacement, initial.copy(nativeProvider = "oidc", bearer = initial.bearer!!.copy(provider = "oidc")),
            initial.copy(owner = "bob", bearer = initial.bearer!!.copy(owner = "bob")))) {
            val store = SnapshotStore(initial)
            val captured = store.epoch.readVersion()
            assertTrue(store.persist(initial.copy(csrf = "history"), captured))
            // Clear and even an identical fresh sign-in are authority changes, not ordinary snapshots.
            store.epoch.mutate { store.stored = newer }
            assertFalse(store.persist(initial.copy(profile = "coder"), captured))
            assertEquals(newer, store.stored)
        }
    }

    @Test fun freshAdmissionAfterASnapshotStillInvalidatesOtherCapturedAdmissions() {
        val initial = snapshotContext()
        val store = SnapshotStore(initial)
        val captured = store.epoch.readVersion()
        assertTrue(store.persist(initial.copy(csrf = "history"), captured))
        val fresh = initial.copy(bearer = initial.bearer!!.copy(accessToken = "fresh", refreshToken = "fresh-refresh"))
        assertTrue(store.persist(fresh, captured, snapshot = false))
        assertFalse(store.persist(initial, captured, snapshot = false))
        assertFalse(store.persist(initial, captured))
        assertEquals(fresh, store.stored)
    }

    @Test fun concurrentSnapshotKeepsExactRefreshWithEqualOrBackwardIssueSeconds() {
        for (issuedAt in listOf(200L, 100L)) {
            val initial = snapshotContext()
            val store = SnapshotStore(initial)
            val captured = store.epoch.readVersion()
            assertTrue(store.persist(initial.copy(csrf = "history"), captured))
            val rotated = initial.bearer!!.copy(accessToken = "rotated", refreshToken = "rotated-refresh", issuedAt = issuedAt)
            store.stored = requireNotNull(store.stored.compareAndSetBearer(initial.bearer!!, rotated))
            assertNull(store.stored.compareAndSetBearer(initial.bearer!!, initial.bearer!!))

            assertTrue(store.persist(initial.copy(profile = "coder"), captured, selectProfileIf = { true }))
            assertEquals("coder", store.stored.profile)
            assertEquals(rotated.copy(profile = "coder"), store.stored.bearer)
            assertEquals(captured, store.epoch.readVersion())
        }
    }

    @Test fun snapshotsStillRequireAnIdleEpochAndTheStoredAccount() {
        val initial = snapshotContext()
        val store = SnapshotStore(initial)
        val captured = store.epoch.readVersion()
        assertFalse(store.persist(initial, null))
        assertFalse(store.persist(initial.copy(profile = "coder"), captured, selectProfileIf = { false }))
        for (foreign in listOf(initial.copy(owner = "bob"), initial.copy(origin = "https://other.example"), initial.copy(nativeProvider = "oidc"))) {
            assertFalse(store.persist(foreign, captured))
        }
        store.epoch.mutate { assertFalse(store.persist(initial, captured)); assertNull(store.epoch.readVersion()) }
        assertFalse(store.persist(initial, captured))
        assertEquals(initial, store.stored)
    }

    @Test fun accountRefreshPreservesTheCurrentlySelectedProfile() {
        val old = NativeBearerRecord("access", "refresh", 100, "https://native.example", "alice", "a", "basic", issuedAt = 1)
        val selected = AuthContext(old.origin, emptyList(), old.owner, "c", backend = "hermes-dashboard", bearer = old.copy(profile = "c"))
        val rotated = old.copy(accessToken = "rotated", refreshToken = "rotated-refresh", issuedAt = 2)

        val saved = selected.compareAndSetBearer(old, rotated)

        assertEquals("c", saved?.profile)
        assertEquals(rotated.copy(profile = "c"), saved?.bearer)
        assertEquals("basic", saved?.nativeProvider)
    }

    @Test fun profileIndependentRefreshStillRequiresTheExactAccountAndTokenRecord() {
        val old = NativeBearerRecord("access", "refresh", 100, "https://native.example", "alice", "a", "basic", issuedAt = 1)
        val selected = AuthContext(old.origin, emptyList(), old.owner, "b", backend = "hermes-dashboard", bearer = old.copy(profile = "b"))
        val rotated = old.copy(accessToken = "rotated", refreshToken = "rotated-refresh")
        for (changed in listOf(old.copy(accessToken = "replacement"), old.copy(refreshToken = "replacement"),
            old.copy(issuedAt = 2), old.copy(expiresAt = 200), old.copy(provider = "oidc"), old.copy(owner = "bob"), old.copy(origin = "https://other.example"))) {
            assertNull(selected.copy(bearer = changed.copy(profile = "b")).compareAndSetBearer(old, rotated))
        }
        assertNull(selected.copy(bearer = null).compareAndSetBearer(old, rotated))
        assertNull(selected.copy(owner = "bob").compareAndSetBearer(old, rotated))
        assertNull(selected.copy(origin = "https://other.example").compareAndSetBearer(old, rotated))
        assertNull(selected.copy(nativeProvider = "oidc").compareAndSetBearer(old, rotated))
        assertNull(selected.compareAndSetBearer(old, rotated.copy(profile = "b")))
        assertNull(selected.compareAndSetBearer(old, rotated.copy(provider = "oidc")))
        assertNull(selected.compareAndSetBearer(old, rotated.copy(owner = "bob")))
        assertNull(selected.compareAndSetBearer(old, rotated.copy(origin = "https://other.example")))
    }

    @Test fun oldUnstampedBearerCanLearnItsNativeProviderOnlyFromAnExactRefresh() {
        val old = NativeBearerRecord("old", "refresh", 1, "https://native.example", "alice", "default")
        val auth = AuthContext(old.origin, emptyList(), old.owner, bearer = old)
        val refreshed = old.copy(accessToken = "new", refreshToken = "rotated", provider = "basic")
        assertEquals("basic", auth.compareAndSetBearer(old, refreshed)?.nativeProvider)
        assertNull(auth.copy(bearer = old.copy(accessToken = "other")).compareAndSetBearer(old, refreshed))
    }

    @Test fun sameUserIdFromAnotherProviderCannotWinBearerSelectionOrRefresh() {
        val basic = NativeBearerRecord("basic-access", "basic-refresh", 100, "https://native.example", "alice", "default", "basic", issuedAt = 1)
        val oidc = basic.copy(accessToken = "oidc-access", refreshToken = "oidc-refresh", provider = "oidc", issuedAt = 2)
        val current = AuthContext(basic.origin, emptyList(), basic.owner, basic.profile, backend = "hermes-dashboard", bearer = basic)
        assertEquals(basic, current.withNewestBearer(oidc).bearer)
        assertNull(current.compareAndSetBearer(basic, oidc))
    }

    @Test fun olderAuthCopyKeepsNewerSameOwnerBearerAndIncomingProfile() {
        val origin = "https://native.example"
        val old = NativeBearerRecord("old-access", "old-refresh", 9000, origin, "owner", "work", issuedAt = 100)
        val stored = NativeBearerRecord("new-access", "new-refresh", 1000, origin, "owner", "assistant", issuedAt = 200)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = old)

        val saved = auth.withNewestBearer(stored)

        assertEquals("new-access", saved.bearer?.accessToken)
        assertEquals("new-refresh", saved.bearer?.refreshToken)
        assertEquals(200L, saved.bearer?.issuedAt)
        assertEquals("work", saved.bearer?.profile)
    }

    @Test fun sameIssuedAtKeepsStoredOnWriteButLetsCheckedPersistChooseIncoming() {
        val origin = "https://native.example"
        val stale = NativeBearerRecord("stale-access", "stale-refresh", 4000, origin, "owner", "work", issuedAt = 300)
        val refreshed = NativeBearerRecord("refreshed-access", "refreshed-refresh", 2000, origin, "owner", "work", issuedAt = 300)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = stale)

        assertEquals(refreshed, auth.withNewestBearer(refreshed).bearer)
        assertEquals(stale, auth.withNewestBearer(refreshed, preferIncomingOnIssueTimeTie = true).bearer)
    }

    @Test fun compareAndSetBearerKeepsRefreshWrittenWhenClockMovesBack() {
        val origin = "https://native.example"
        val expected = NativeBearerRecord("expected-access", "expected-refresh", 3000, origin, "owner", "work", issuedAt = 200)
        val refreshed = expected.copy(accessToken = "refreshed-access", refreshToken = "refreshed-refresh", expiresAt = 4000, issuedAt = 100)
        val current = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = expected)

        assertEquals(refreshed, current.compareAndSetBearer(expected, refreshed)?.bearer)
    }

    @Test fun bearerFromAnotherOriginOrOwnerIsNotKept() {
        val origin = "https://native.example"
        val old = NativeBearerRecord("old-access", "old-refresh", 9000, origin, "owner", "work", issuedAt = 100)
        val auth = AuthContext(origin, emptyList(), "owner", "work", backend = "hermes-dashboard", bearer = old)

        assertEquals(old, auth.withNewestBearer(old.copy(origin = "https://other.example", accessToken = "foreign-access")).bearer)
        assertEquals(old, auth.withNewestBearer(old.copy(owner = "other-owner", accessToken = "foreign-access")).bearer)
    }

    @Test fun legacyRecordsUseExpiryAndTiesPreferIncoming() {
        val origin = "https://native.example"
        val stored = NativeBearerRecord("stored-access", "stored-refresh", 4000, origin, "owner", "work")
        val auth = AuthContext(origin, emptyList(), "owner", "work", bearer = stored.copy(expiresAt = 3000))

        assertEquals("stored-access", auth.withNewestBearer(stored).bearer?.accessToken)
        assertEquals("incoming-access", auth.copy(bearer = stored.copy(accessToken = "incoming-access", refreshToken = "incoming-refresh")).withNewestBearer(stored).bearer?.accessToken)
    }
}
