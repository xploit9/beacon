package dev.hermes.android

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID
import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

private const val LEGACY_SESSION_KIND = "xara-session" // This test verifies the released cache kind is migrated.
private const val LEGACY_OPENING_KIND = "xara-opening" // This test verifies the released cache kind is migrated.

/** Real Android SQLite + keystore regression; never touches the owner's installed database. */
class NativeProjectionStorageTest {
    @Test fun originalEncryptedCookieBearerAndLoopbackRecordsRemainReadableWithoutRewritingThem() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "legacy-auth-fixture-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        val prefs = app.getSharedPreferences(name, Context.MODE_PRIVATE)
        val cipher = AppCipher()
        val origin = "https://native.example"
        val cookie = okhttp3.Cookie.Builder().name("hermes_session_rt").value("legacy-cookie").hostOnlyDomain("native.example")
            .path("/").secure().httpOnly().expiresAt(System.currentTimeMillis() + 60_000).build().toString()
        try {
            for (kind in listOf("cookie", "bearer", "loopback")) {
                val owner = if (kind == "loopback") "local" else "alice"
                val raw = org.json.JSONObject().put("owner", owner).put("profile", "default").put("backend", "hermes-dashboard")
                    .put("cookies", org.json.JSONArray(if (kind == "cookie") listOf(cookie) else emptyList<String>()))
                if (kind == "bearer") raw.put("bearer", org.json.JSONObject().put("access_token", "legacy-access").put("refresh_token", "legacy-refresh")
                    .put("expires_at", 1).put("origin", origin).put("owner", owner).put("profile", "default").put("provider", "basic"))
                if (kind == "loopback") raw.put("loopback_session", org.json.JSONObject().put("token", "legacy-loopback").put("origin", origin).put("owner", owner).put("verified", true))
                val original = android.util.Base64.encodeToString(cipher.encrypt(raw.toString().toByteArray(), origin), android.util.Base64.NO_WRAP)
                assertTrue(prefs.edit().putString(origin.digest(), original).commit())
                val restored = AuthStore(context, cipher).read(origin)
                assertEquals(owner, restored.owner)
                assertTrue(restored.canReconnectNative(origin))
                assertEquals(original, prefs.getString(origin.digest(), null))
                when (kind) {
                    "cookie" -> { assertEquals(listOf(cookie), restored.cookies); assertNull(restored.cachedScopeFor(origin, false)) }
                    "bearer" -> { assertEquals("legacy-access", restored.bearer?.accessToken); assertEquals("basic", restored.cachedScopeFor(origin, false)?.nativeProvider) }
                    "loopback" -> { assertEquals("legacy-loopback", restored.loopbackSession?.token); assertEquals("loopback", restored.cachedScopeFor(origin, false)?.nativeProvider) }
                }
            }
        } finally { app.deleteSharedPreferences(name) }
    }

    private fun legacyRestoreClient(provider: String, storedUser: String?, denied: Boolean = false): BeaconClient {
        val auth = AuthContext("https://native.example", emptyList(), "alice", backend = "hermes-dashboard", nativeProvider = provider)
        return BeaconClient(auth, null, persistContext = false, configureHttp = { builder ->
            builder.addInterceptor { chain ->
                val path = chain.request().url.encodedPath
                val body = when (path) {
                    "/api/auth/me" -> """{"user_id":"alice","provider":"$provider"}"""
                    "/api/profiles" -> """{"profiles":[{"name":"default"}]}"""
                    "/api/sessions/legacy-chat" -> org.json.JSONObject().put("id", "legacy-chat").put("profile", "default").put("user_id", storedUser ?: org.json.JSONObject.NULL).toString()
                    else -> error("Unexpected fixture path: $path")
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (denied) 403 else 200).message("Fixture")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            }
        })
    }

    @Test fun creatorMatchedLegacySelectionNeverAssignsUnstampedSharedConversationDrafts() = runBlocking {
        val db = database()
        val client = legacyRestoreClient("basic", "basic:alice")
        try {
            val cache = ProjectionCache(db, AppCipher())
            val legacy = Scope(client.auth.origin, "alice", "default", "legacy-chat")
            val initial = legacy.copy(session = "")
            cache.write(legacy, "draft", "Unstamped private draft from a shared conversation", protected = true)
            cache.write(initial, "initial-draft", "unbound initial draft", protected = true)
            val selectionKey = "${initial.key}:selected-session"
            val selectionBytes = AppCipher().encrypt("legacy-chat".toByteArray(), selectionKey)
            db.rows().put(Projection(selectionKey, initial.key, selectionBytes, 1234, true, selectionBytes.size.toLong()))
            val oldKey = "${legacy.key}:${"draft".digest()}"
            val oldMetadata = requireNotNull(db.rows().get(oldKey))
            val oldCiphertext = db.rows().encryptedChunk(oldKey, 1, oldMetadata.encryptedSize.toInt())
            assertNull(cache.read(client.scope.session("legacy-chat"), "draft"))
            assertEquals("legacy-chat", cache.authorizedLegacySelection(client))
            assertArrayEquals(selectionBytes, db.rows().encryptedChunk(selectionKey, 1, selectionBytes.size))
            assertNull(cache.read(client.scope.session("legacy-chat"), "draft"))
            assertArrayEquals(oldCiphertext, db.rows().encryptedChunk(oldKey, 1, oldMetadata.encryptedSize.toInt()))
            assertNull(cache.read(client.scope, "initial-draft"))
            assertEquals("unbound initial draft", cache.read(initial, "initial-draft"))
            cache.write(client.scope.session("legacy-chat"), "draft", "New provider-bound draft", protected = true)
            assertEquals("legacy-chat", cache.authorizedLegacySelection(client))
            assertEquals("New provider-bound draft", cache.read(client.scope.session("legacy-chat"), "draft"))
            assertEquals("Unstamped private draft from a shared conversation", cache.read(legacy, "draft"))
        } finally { client.closeNative(); db.close() }
    }

    @Test fun wrongProviderMissingNativeProvenanceAndDenialNeverExposeOrConsumeLegacyDrafts() = runBlocking {
        for ((provider, storedUser, denied) in listOf(Triple("oidc", "basic:alice", false), Triple("basic", null, false), Triple("basic", "basic:alice", true))) {
            val db = database()
            val client = legacyRestoreClient(provider, storedUser, denied)
            try {
                val cache = ProjectionCache(db, AppCipher())
                val legacy = Scope(client.auth.origin, "alice", "default", "legacy-chat")
                cache.write(legacy, "draft", "private original", protected = true)
                val selectionKey = "${legacy.copy(session = "").key}:selected-session"
                val selected = AppCipher().encrypt("legacy-chat".toByteArray(), selectionKey)
                db.rows().put(Projection(selectionKey, legacy.copy(session = "").key, selected, 1234, true, selected.size.toLong()))
                runCatching { assertNull(cache.authorizedLegacySelection(client)) }.getOrElse {
                    if (it !is ApiFailure) throw it
                }
                assertArrayEquals(selected, db.rows().encryptedChunk(selectionKey, 1, selected.size))
                assertNull(cache.read(client.scope.session("legacy-chat"), "draft"))
                assertEquals("private original", cache.read(legacy, "draft"))
            } finally { client.closeNative(); db.close() }
        }
    }

    private suspend fun withAuthStore(block: suspend (AuthStore) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "authority-fixture-${UUID.randomUUID()}"
        val context = object : ContextWrapper(app) {
            override fun getSharedPreferences(ignored: String, mode: Int) = app.getSharedPreferences(name, mode)
        }
        try { block(AuthStore(context, AppCipher())) } finally { app.deleteSharedPreferences(name) }
    }

    @Test fun delayedOldBearerLogoutCannotRemoveANewSignIn() = runBlocking {
        withAuthStore { store ->
            val old = NativeBearerRecord("old-access", "old-refresh", 100, "https://native.example", "alice", "default", "basic")
            store.write(AuthContext(old.origin, emptyList(), old.owner, old.profile, backend = "hermes-dashboard", bearer = old))
            val frozen = BeaconClient(store.read(old.origin), store, persistContext = false)
            // The ViewModel removes local authority before its suspended push cleanup resumes.
            store.clear(old.origin)
            val resumeCleanup = CompletableDeferred<Unit>()
            val cleanup = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) { resumeCleanup.await(); frozen.logout() }
            val newer = old.copy(accessToken = "new-access", refreshToken = "new-refresh", provider = "oidc")
            store.write(AuthContext(newer.origin, emptyList(), newer.owner, newer.profile, backend = "hermes-dashboard", bearer = newer))
            resumeCleanup.complete(Unit)
            cleanup.join()
            assertEquals(newer, store.read(old.origin).bearer)
        }
    }

    @Test fun logoutEpochCapturedBeforeSuspensionCannotClearAReplacementCredential() = runBlocking {
        withAuthStore { store ->
            val old = NativeBearerRecord("old-access", "old-refresh", 100, "https://native.example", "alice", "default", "basic")
            store.write(AuthContext(old.origin, emptyList(), old.owner, bearer = old, backend = "hermes-dashboard"))
            val capturedVersion = store.authorityEpoch.readVersion()
            val replacement = old.copy(accessToken = "new-access", refreshToken = "new-refresh")
            store.write(AuthContext(old.origin, emptyList(), old.owner, bearer = replacement, backend = "hermes-dashboard"))
            assertFalse(store.removeIfUnchanged(old.origin, capturedVersion))
            assertEquals(replacement, store.read(old.origin).bearer)
        }
    }

    @Test fun frozenCleanupRefreshesOnlyItsCapturedCredentialAndCannotWriteTheNewLogin() = runBlocking {
        withAuthStore { store ->
            val old = NativeBearerRecord("old-access", "old-refresh", 1, "https://native.example", "alice", "default", "basic")
            val auth = AuthContext(old.origin, emptyList(), old.owner, bearer = old, backend = "hermes-dashboard")
            store.write(auth)
            val requests = mutableListOf<okhttp3.Request>()
            val live = BeaconClient(auth, store, persistContext = false, configureHttp = { builder ->
                builder.addInterceptor { chain ->
                    val request = chain.request(); requests += request
                    val refresh = request.url.encodedPath == "/auth/native/refresh"
                    val accepted = request.header("Authorization") == "Bearer cleanup-access"
                    val body = if (refresh) """{"access_token":"cleanup-access","refresh_token":"cleanup-refresh","token_type":"Bearer","user_id":"alice","provider":"basic"}"""
                        else if (accepted) """{"user_id":"alice","provider":"basic"}""" else """{"detail":"Unauthorized"}"""
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (refresh || accepted) 200 else 401).message("Fixture")
                        .body(body.toResponseBody("application/json".toMediaType())).build()
                }
            })
            val frozen = live.detachedForLogout()
            val replacement = old.copy(accessToken = "new-login-access", refreshToken = "new-login-refresh")
            store.clear(old.origin)
            store.write(auth.copy(bearer = replacement))
            try {
                frozen.nativeRequest("/api/auth/me")
                assertEquals(listOf("/api/auth/me", "/auth/native/refresh", "/api/auth/me"), requests.map { it.url.encodedPath })
                assertEquals("Bearer cleanup-access", requests.last().header("Authorization"))
                assertTrue(requests.none { it.header("Authorization") == "Bearer new-login-access" })
                assertEquals(replacement, store.read(old.origin).bearer)
            } finally { frozen.closeNative(); live.closeNative() }
        }
    }

    @Test fun cookieLogoutStillPostsWithoutErasingALoginCompletedDuringCleanup() = runBlocking {
        withAuthStore { store ->
            val origin = "https://native.example"
            val cookie = "hermes_session_at=old-cookie; Path=/; Secure; HttpOnly"
            val old = AuthContext(origin, listOf(cookie), "alice", backend = "hermes-dashboard")
            store.write(old)
            val newer = NativeBearerRecord("new-access", "new-refresh", 200, origin, "alice", "default", "basic")
            val requests = mutableListOf<String>()
            val frozen = BeaconClient(old, store, persistContext = false, configureHttp = { builder ->
                builder.addInterceptor { chain ->
                    requests += "${chain.request().method} ${chain.request().url.encodedPath}"
                    store.write(AuthContext(origin, emptyList(), "alice", backend = "hermes-dashboard", bearer = newer))
                    Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Signed out")
                        .body("".toResponseBody("text/plain".toMediaType())).build()
                }
            })
            store.clear(origin)
            frozen.logout()
            assertEquals(listOf("POST /auth/logout"), requests)
            assertEquals(newer, store.read(origin).bearer)
        }
    }

    @Test fun ordinaryLocalLogoutRemovesOnlyTheCredentialAndKeepsDrafts() = runBlocking {
        withAuthStore { store ->
            val bearer = NativeBearerRecord("access", "refresh", 100, "https://native.example", "alice", "default", "basic")
            val auth = AuthContext(bearer.origin, emptyList(), bearer.owner, bearer.profile, backend = "hermes-dashboard", bearer = bearer)
            store.write(auth)
            val db = database()
            try {
                val cache = ProjectionCache(db, AppCipher())
                val scope = auth.scope()
                cache.write(scope, "initial-draft", "retained draft", protected = true)
                val frozen = BeaconClient(store.read(auth.origin), store, persistContext = false)
                store.clear(auth.origin)
                frozen.logout()
                assertNull(store.read(auth.origin).bearer)
                assertEquals("retained draft", cache.read(scope, "initial-draft"))
            } finally { db.close() }
        }
    }

    private fun database(): ProjectionDb = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(), ProjectionDb::class.java
    ).build()

    @Test fun legacyAssistantCacheKindsMigrateWithoutLosingProtectedPointers() = runBlocking {
        val db = database()
        try {
            val cache = ProjectionCache(db, AppCipher())
            val scope = Scope("https://native.example", "fixture-owner", "default", "fixture-chat")
            // Released builds stored the assistant pointer and unresolved-open marker under these kinds.
            cache.write(scope, LEGACY_SESSION_KIND, "pinned-session", protected = true)
            cache.write(scope, LEGACY_OPENING_KIND, "unknown", protected = true)

            assertEquals("pinned-session", cache.readMigrating(scope, "beacon-session", LEGACY_SESSION_KIND))
            assertEquals("unknown", cache.readMigrating(scope, "beacon-opening", LEGACY_OPENING_KIND))
            assertEquals("pinned-session", cache.read(scope, "beacon-session"))
            assertEquals("unknown", cache.read(scope, "beacon-opening"))
            assertNull(cache.read(scope, LEGACY_SESSION_KIND))
            assertNull(cache.read(scope, LEGACY_OPENING_KIND))
        } finally { db.close() }
    }

    @Test fun existingLargeEncryptedDraftReadsMigratesAndOverwritesWithoutCursorOverflow() = runBlocking {
        val db = database()
        try {
            val cipher = AppCipher()
            val cache = ProjectionCache(db, cipher)
            val scope = Scope("https://native.example", "fixture-owner", "default", "fixture-chat")
            val kind = "draft"
            val oldKey = "${scope.key}:$kind"
            val newKey = "${scope.key}:${kind.digest()}"
            val text = "large retained draft\n".repeat(180_000)
            val ciphertext = cipher.encrypt(text.toByteArray(), oldKey)
            assertTrue(ciphertext.size > 3 * 1024 * 1024)
            db.rows().put(Projection(oldKey, scope.key, ciphertext, 1234L, true, ciphertext.size.toLong()))
            assertEquals(text, cache.read(scope, kind))
            assertNull(db.rows().get(oldKey))
            val migrated = requireNotNull(db.rows().get(newKey))
            assertEquals(1234L, migrated.updated)
            assertTrue(migrated.protected)
            assertNull(cache.read(scope.copy(owner = "another-owner"), kind))
            cache.write(scope, kind, "replacement", protected = true)
            assertEquals("replacement", cache.read(scope, kind))
            cache.write(scope, "another", "second checkpoint", protected = true)
            assertEquals(mapOf(kind to "replacement", "another" to "second checkpoint"), cache.readMany(scope, listOf(kind, "missing", "another", kind)))
            assertTrue(cache.readMany(scope.copy(owner = "other-owner"), listOf(kind, "another")).isEmpty())
            val damaged = cipher.encrypt(text.toByteArray(), newKey).apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
            db.rows().put(Projection(newKey, scope.key, damaged, 1234L, true, damaged.size.toLong()))
            assertTrue(runCatching { cache.read(scope, kind) }.isFailure)
            assertNotNull("Damaged draft must remain recoverable", db.rows().get(newKey))
        } finally { db.close() }
    }

    @Test fun reviewingAnOlderReceiptCannotDeleteAReplacementCheckpoint() = runBlocking {
        val db = database()
        try {
            val cache = ProjectionCache(db, AppCipher())
            val scope = Scope("https://native.example", "fixture-owner", "default", "fixture-chat")
            cache.write(scope, "pending", "new receipt", protected = true)
            assertFalse(cache.delete(scope, "pending", "old receipt"))
            assertEquals("new receipt", cache.read(scope, "pending"))
            assertTrue(cache.delete(scope, "pending", "new receipt"))
            assertNull(cache.read(scope, "pending"))
            assertTrue(cache.delete(scope, "pending", "new receipt"))
        } finally { db.close() }
    }

    @Test fun evictionSelectsMetadataWithoutLoadingOversizedPayloadRows() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "native-projection-proof-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ProjectionDb::class.java, name).build()
        try {
            val scope = Scope("https://native.example", "fixture-owner", "default")
            val cache = ProjectionCache(db, AppCipher())
            cache.write(scope, "draft", "never evict protected drafts", protected = true)
            val now = System.currentTimeMillis() + 1000
            // Real large BLOBs trigger the old SELECT * CursorWindow failure in oldest()/get().
            repeat(13) { n -> db.openHelper.writableDatabase.execSQL(
                "INSERT INTO projections (`key`,scope,encrypted,updated,protected,bytes) VALUES (?,?,zeroblob(?),?,0,?)",
                arrayOf<Any>("cache-$n", "fixture", 11 * 1024 * 1024, now + n, 11 * 1024 * 1024)
            ) }
            assertTrue(db.rows().total() > ProjectionCache.LIMIT)
            cache.write(scope, "trigger", "evict only cache rows", protected = false)
            assertTrue(db.rows().total() <= ProjectionCache.LIMIT)
            assertNull(db.rows().get("cache-0"))
            assertEquals("never evict protected drafts", cache.read(scope, "draft"))
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
