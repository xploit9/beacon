package dev.hermes.android.data

import dev.hermes.android.ui.NavigationRoute
import dev.hermes.android.ui.navigationRoute
import dev.hermes.android.ui.navigationTiles
import dev.hermes.android.ui.settingsHubRoutes
import dev.hermes.android.ui.NativeLanguages
import dev.hermes.android.core.json
import dev.hermes.android.core.Scope
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class NativeVaultTest {
    @Test fun drawerOpensVaultWhileMemoryLivesUnderProfileSettings() {
        assertEquals(listOf("Skills", "Vault", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings"), navigationTiles)
        assertEquals(NavigationRoute("More", "Vault"), navigationRoute("Vault"))
        assertFalse("Memory is reached from its profile, not the settings hub", settingsHubRoutes.contains("Memory"))
    }

    @Test fun metadataKeepsOnlyPublicFieldsAndCapabilityMustBeAnActualBoolean() {
        val ownerIdentifier = "owner" + 64.toChar() + "example.test"
        val item = json("id" to "item-1", "kind" to "login", "label" to "Mail", "origin" to "https://example.test",
            "identifier_type" to "email", "identifier" to ownerIdentifier, "password" to "private-canary",
            "secret" to json("password" to "private-canary"))
        val snapshot = VaultSnapshot.from(json("items" to JSONArray().put(item),
            "capabilities" to json("supported" to true, "local" to true, "signup" to "true", "secure_prompts" to "true"),
            "identity" to json("name" to "Hermes", "email" to "agent" + 64.toChar() + "example.test", "status" to "unverified")))
        assertEquals(ownerIdentifier, snapshot.items.single().identifier)
        assertFalse("No secret-bearing source object survives in the metadata", snapshot.toString().contains("private-canary"))
        assertFalse(snapshot.capabilities.signup); assertFalse(snapshot.capabilities.securePrompts)
        assertEquals("unverified", snapshot.identity.status)
    }

    @Test fun saveReceiptMustMatchTheSubmittedIdentityAndContainValidMetadata() {
        val item = json("id" to "item-1", "kind" to "login", "label" to "Mail", "origin" to "https://example.test")
        assertEquals("item-1", vaultSavedItem(json("request_id" to "request-1", "item" to item), "request-1").id)
        assertTrue(runCatching { vaultSavedItem(json("request_id" to "other", "item" to item), "request-1") }.isFailure)
        assertTrue(runCatching { vaultSavedItem(json("request_id" to "request-1", "item" to item.put("kind", "unknown")), "request-1") }.isFailure)
    }

    @Test fun securePromptIsRejectedAfterAnyOwnerProfileSessionEpochLockOrExpiryChange() {
        val scope = Scope("https://example.test", "owner", "work", session = "chat")
        val prompt = SecurePrompt(scope, "runtime", "id", "vault.code", "connection", 100, 3, 8, "example.test")
        fun allowed(s: Scope? = scope, origin: Int = 3, session: Int = 8, foreground: Boolean = true,
            authenticated: Boolean = true, locked: Boolean = false, now: Long = 99) =
            securePromptCurrent(prompt, s, origin, session, foreground, authenticated, locked, now)
        assertTrue(allowed())
        assertFalse(allowed(s = scope.copy(owner = "other")))
        assertFalse(allowed(s = scope.copy(profile = "other")))
        assertFalse(allowed(s = scope.copy(session = "other")))
        assertFalse(allowed(origin = 4)); assertFalse(allowed(session = 9))
        assertFalse(allowed(foreground = false)); assertFalse(allowed(authenticated = false))
        assertFalse(allowed(locked = true)); assertFalse(allowed(now = 100))
    }

    @Test fun siteOriginsRequireHttpsAndCannotContainPathsOrCredentials() {
        assertTrue(validVaultOrigin("https://example.test"))
        for (origin in listOf("http://127.0.0.1:8080", "http://example.test", "https://user" + 64.toChar() + "example.test", "https://example.test/login", "https://example.test?x=1", "not a site"))
            assertFalse(origin, validVaultOrigin(origin))
    }

    @Test fun generatingALocalLoginDoesNotClaimWebsiteSignupSupport() {
        assertTrue(vaultCanGenerate(VaultCapabilities(supported = true, local = true, signup = false, otp = false, securePrompts = false)))
        assertFalse(vaultCanGenerate(VaultCapabilities(supported = true, local = false, signup = true, otp = false, securePrompts = false)))
    }

    @Test fun vaultAndSecurePromptLabelsExistInEveryNativeLanguage() {
        val root = listOf(".", "app", "beacon-android/app").map { java.io.File(it) }
            .first { java.io.File(it, "src/main/assets/native-locales.json").isFile }
        val catalog = org.json.JSONObject(java.io.File(root, "src/main/assets/native-locales.json").readText(Charsets.UTF_8))
        val keys = listOf("vault", "vault items", "no vault items", "vault unavailable", "identity", "unverified",
            "add login", "add payment card", "add address", "generate login", "replace secret", "delete vault item?",
            "identifier type", "identifier", "phone", "username", "authenticator key", "card number", "expiration month",
            "expiration year", "security code", "cardholder name", "billing postal code", "address line 1", "address line 2",
            "city", "state", "postal code", "country", "secret", "secure input", "verification code", "save login", "unlock vault",
            "enter an https site address.", "edit the site address first.", "enter a valid expiration month.",
            "change unconfirmed. refresh before trying again.", "secure input sent. waiting for hermes.",
            "secure input could not be confirmed.", "secure input is needed. open the conversation to continue.", "password", "origin", "not configured")
        for (language in NativeLanguages.keys) for (key in keys)
            assertTrue("$language: $key", catalog.getJSONObject(language).optString(key).isNotBlank())
    }
}
