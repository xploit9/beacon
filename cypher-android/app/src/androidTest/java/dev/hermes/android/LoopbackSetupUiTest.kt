package dev.hermes.android

import android.content.Context
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.core.digest
import dev.hermes.android.data.AppCipher
import dev.hermes.android.data.cachedScopeFor
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.AuthStore
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.LoopbackSessionRecord
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.ui.parseCypherConnectLink
import dev.hermes.android.ui.setupAddressForDisplay
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import androidx.activity.ComponentActivity

class LoopbackSetupUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun token() = UUID.randomUUID().toString().replace("-", "")
    private fun assertToken(expected: String, actual: String?) {
        assertTrue(actual != null && MessageDigest.isEqual(expected.toByteArray(), actual.toByteArray()))
    }

    @Test fun externalConnectLinkPrefillsAddressAndWaitsForContinue() {
        val secret = token()
        val link = "cypher://connect?url=${Uri.encode("https://hermes.example")}&name=Hermes&session=${Uri.encode(secret)}"
        val parsed = parseCypherConnectLink(link)
        assertEquals("https://hermes.example", parsed.origin)
        assertToken(secret, parsed.sessionToken)
        assertFalse(parsed.toString().contains(secret))
        assertEquals("https://hermes.example", parseCypherConnectLink("cypher://CONNECT?url=${Uri.encode("https://hermes.example")}").origin)
        val scannerQr = org.json.JSONObject()
            .put("type", "cypher-connect")
            .put("url", "https://hermes.example")
            .put("session", secret)
            .toString()
        assertToken(secret, parseCypherConnectLink(scannerQr).sessionToken)
        assertEquals("https://hermes.example", setupAddressForDisplay(scannerQr))

        val state = AppState(setupOrigin = parsed.origin, setupWizard = true, setupStep = "connect", restoringSession = false)
        var submitted: String? = null
        compose.setContent {
            NativeLocalization("en") {
                CypherTheme(dark = true) {
                    SetupWizardScreen(
                        state = state,
                        onProbe = { submitted = it },
                        onSignIn = {},
                        onAdvancePermission = {},
                        onFinish = {},
                        onReopen = {},
                        onCancel = {},
                    )
                }
            }
        }

        compose.onNodeWithTag("setup-address").assertTextEquals("Paste link or address", parsed.origin)
        assertNull(submitted)
        compose.onNodeWithTag("setup-connect").performClick()
        assertEquals(parsed.origin, submitted)
    }

    @Test fun loopbackModeContinuesAtPermissionsWithoutShowingSignIn() {
        val state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "permission-microphone", restoringSession = false)
        compose.setContent {
            NativeLocalization("en") {
                CypherTheme(dark = true) {
                    SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithText("Microphone").assertIsDisplayed()
        compose.onNodeWithTag("setup-signin").assertDoesNotExist()
        compose.onNodeWithText("Sign in with Hermes").assertDoesNotExist()
    }

    @Test fun gatedHermesWithoutNativePkceShowsOnlyTheUpdateMessage() {
        val state = AppState(origin = "https://hermes.example", setupWizard = true, setupStep = "unsupported", restoringSession = false)
        compose.setContent {
            NativeLocalization("en") {
                CypherTheme(dark = true) {
                    SetupWizardScreen(state, onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = {}, onReopen = {}, onCancel = {})
                }
            }
        }
        compose.onNodeWithText("This Hermes needs an update before phones can sign in.").assertIsDisplayed()
        compose.onNodeWithTag("setup-signin").assertDoesNotExist()
        compose.onNodeWithText("Hermes username").assertDoesNotExist()
        compose.onNodeWithText("Cypher password").assertDoesNotExist()
    }

    @Test fun loopbackSessionRoundTripsOnlyInsideTheEncryptedAuthRecord() {
        val origin = "https://loopback-${UUID.randomUUID()}.example"
        val secret = token()
        val context = ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        val store = AuthStore(context, AppCipher())
        val auth = AuthContext(
            origin = origin,
            cookies = emptyList(),
            owner = "local",
            profile = "default",
            backend = "hermes-dashboard",
            loopbackSession = LoopbackSessionRecord(secret, origin, "local", verified = true),
        )
        try {
            assertTrue(runCatching { store.write(auth.copy(loopbackSession = requireNotNull(auth.loopbackSession).copy(verified = false))) }.isFailure)
            assertNull(store.read(origin).loopbackSession)
            store.write(auth)
            val restored = store.read(origin)
            val restoredSession = requireNotNull(restored.loopbackSession)
            assertToken(secret, restoredSession.token)
            assertTrue(restoredSession.verified && restoredSession.persisted)
            assertTrue(restored.cachedScopeFor(origin, replacingAccount = false) != null)
            assertFalse(restored.toString().contains(secret))
            val encrypted = context.getSharedPreferences("auth-private", Context.MODE_PRIVATE).getString(origin.digest(), null)
            assertTrue("Encrypted auth record is missing", !encrypted.isNullOrBlank())
            assertFalse(requireNotNull(encrypted).contains(secret))
        } finally {
            store.clear(origin)
        }
    }

    @Test fun authPersistenceKeepsFreshSignInAndNewestRestoredBearer() = runBlocking {
        val origin = "https://auth-${UUID.randomUUID()}.example"
        val owner = "native-owner"
        val profile = "default"
        val context = ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        val store = AuthStore(context, AppCipher())
        fun bearer(access: String, expiry: Long, issuedAt: Long = 0) = NativeBearerRecord(access, "refresh-$access", expiry, origin, owner, profile, "fixture", issuedAt)
        val saved = AuthContext(origin, emptyList(), owner, profile, backend = "hermes-dashboard", bearer = bearer("stored", 100))
        try {
            store.write(saved)
            val fresh = saved.copy(bearer = bearer("fresh", 0, 200))
            assertTrue(store.persistIfUnchanged(fresh, store.authorityEpoch.readVersion()))
            assertEquals("fresh", store.read(origin).bearer?.accessToken)
            assertEquals(200L, requireNotNull(store.read(origin).bearer).issuedAt)

            val newestStored = saved.copy(bearer = bearer("newest-stored", 0, 300))
            store.write(newestStored)
            assertTrue(store.persistIfUnchanged(fresh, store.authorityEpoch.readVersion()))
            assertEquals("newest-stored", store.read(origin).bearer?.accessToken)

            val tiedIncoming = saved.copy(bearer = bearer("tied-incoming", 0, 300))
            assertTrue(store.persistIfUnchanged(tiedIncoming, store.authorityEpoch.readVersion()))
            assertEquals("tied-incoming", store.read(origin).bearer?.accessToken)

            // write() keeps a newer stored sign-in, so start this legacy case from an empty record.
            store.clear(origin)
            store.write(saved.copy(bearer = bearer("legacy-newest", 250)))
            val legacyRestore = saved.copy(bearer = bearer("legacy-restore", 200))
            assertTrue(store.persistIfUnchanged(legacyRestore, store.authorityEpoch.readVersion()))
            assertEquals("legacy-newest", store.read(origin).bearer?.accessToken)
        } finally {
            store.clear(origin)
        }
    }

    @Test fun detachedProfileViewsNeverPersistOrRecreateAuth() = runBlocking {
        val origin = "https://detached-${UUID.randomUUID()}.example"
        val secret = token()
        val context = ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        val store = AuthStore(context, AppCipher())
        val auth = AuthContext(origin, emptyList(), "local", "default", backend = "hermes-dashboard", loopbackSession = LoopbackSessionRecord(secret, origin, "local", verified = true))
        val client = CypherClient(auth, store)
        try {
            store.write(auth)
            val foreignProfile = client.detached()
            try { foreignProfile.bindProfile("coder") } finally { foreignProfile.closeNative() }
            assertEquals("default", store.read(origin).profile)

            store.clear(origin)
            val signedOutView = client.detached()
            try { signedOutView.bindProfile("coder") } finally { signedOutView.closeNative() }
            val restored = store.read(origin)
            assertTrue(restored.owner.isBlank())
            assertNull(restored.loopbackSession)
        } finally {
            client.closeNative()
            store.clear(origin)
        }
    }

    @Test fun detachedBootstrapDoesNotRecreateClearedAuth() = runBlocking {
        val fixture = NativeExperienceFixture()
        val origin = fixture.scope.origin
        val context = ApplicationProvider.getApplicationContext<Context>().createDeviceProtectedStorageContext()
        val store = AuthStore(context, AppCipher())
        val bearer = NativeBearerRecord("access", "refresh", 200, origin, fixture.scope.owner, fixture.scope.profile, "fixture")
        val auth = AuthContext(origin, emptyList(), fixture.scope.owner, fixture.scope.profile, backend = "hermes-dashboard", bearer = bearer)
        val client = CypherClient(
            initial = auth,
            authStore = store,
            socketFactory = fixture,
            sharedHttp = fixture.client.http,
            persistContext = true,
            configureHttp = {},
        )
        try {
            store.clear(origin)
            val detached = client.detached()
            try { detached.bootstrap() } finally { detached.closeNative() }
            val restored = store.read(origin)
            assertTrue(restored.owner.isBlank())
            assertNull(restored.bearer)
        } finally {
            client.closeNative()
            fixture.client.closeNative()
            store.clear(origin)
        }
    }
}
