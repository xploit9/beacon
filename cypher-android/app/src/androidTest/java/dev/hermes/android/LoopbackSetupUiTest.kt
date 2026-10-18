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
import dev.hermes.android.data.LoopbackSessionRecord
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.SetupWizardScreen
import dev.hermes.android.ui.parseCypherConnectLink
import java.security.MessageDigest
import java.util.UUID
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
            val encrypted = context.getSharedPreferences("auth-private", Context.MODE_PRIVATE).getString(origin.digest(), "")
            assertFalse(encrypted.orEmpty().contains(secret))
        } finally {
            store.clear(origin)
        }
    }
}
