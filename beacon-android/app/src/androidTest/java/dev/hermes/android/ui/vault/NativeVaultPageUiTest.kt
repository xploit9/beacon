package dev.hermes.android.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * The Vault page on Hermes' native Passwords & Logins, against a fixture that answers `vault.*` exactly as the contracts define
 * (NativeExperienceFixture "Hermes' native Passwords & Logins"). Nothing here touches a real Hermes, vault or manager.
 */
class NativeVaultPageUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val harness get() = VaultHarness(compose)
    private val mail = "pat" + 64.toChar() + "example.test"
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun usable(tag: String) = shown(tag) && runCatching { compose.onNodeWithTag(tag).assertIsEnabled() }.isSuccess
    private fun openVault(vm: BeaconViewModel) {
        compose.runOnIdle { vm.navigate("Vault") }
        await { shown("vault-page") }
    }
    private fun scrollTo(tag: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
        compose.onNode(SemanticsMatcher("Settings detail list") { it.id == list.id }).performScrollToNode(hasTestTag(tag))
    }
    private fun masked(tag: String) = compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
    private fun plain(tag: String) = compose.onNodeWithTag(tag).assert(SemanticsMatcher("is not a password field") { SemanticsProperties.Password !in it.config })
    private fun lastCall(fixture: NativeExperienceFixture, method: String) = fixture.vaultCalls.last { it.first == method }.second

    @Test fun theVaultListsOnlyThisProfilesItemsWithTheirSourceAnd2faBadgeAndNamesTheProfile() = harness.run(configure = {
        profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
        seedVaultManager("default", "onepassword", "1Password", enabled = true, unlocked = true)
    }) { vm, fixture ->
        val own = fixture.seedVaultLogin("default", "GitHub", "https://github.com", mail, otp = true)
        val managed = fixture.seedVaultLogin("default", "Shop", "https://shop.example", "pat", backend = "onepassword")
        fixture.seedVaultLogin("coder", "Coder only", "https://coder.example", "dev")
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true) }
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("display_name") == "Personal profile" } == true }
        openVault(vm)
        await { shown("vault-item:$own") }
        compose.onNodeWithTag("vault-item:$own").assertTextContains("Hermes · Fills 2FA codes", substring = true)
        compose.onNodeWithTag("vault-item:$managed").assertTextContains("1Password", substring = true)
        compose.onNodeWithTag("vault-item:$managed").assertTextContains("shop.example", substring = true)
        assertEquals("Another profile's items never show", 0, compose.onAllNodesWithText("Coder only").fetchSemanticsNodes().size)
        assertTrue("The page says which profile it shows", compose.onAllNodesWithText("Personal profile").fetchSemanticsNodes().isNotEmpty())
        assertTrue(fixture.vaultCalls.map { it.first }.containsAll(listOf("vault.list", "vault.sources")))
        assertTrue("Every call names its profile", fixture.vaultCalls.all { it.second.string("profile") == "default" })
        // Precache: the list is kept per account and profile, metadata only.
        val cached = vm.screens.exact(fixture.scope, "vault")?.value
        assertNotNull(cached)
        assertFalse("No secret field in the cache", cached.toString().contains("\"password\"") || cached.toString().contains("otp_secret"))
    }

    @Test fun addLoginMasksEverySecretAndSendsExactlyWhatHermesTakes() = harness.run { vm, fixture ->
        openVault(vm)
        await { usable("vault-add-login") }
        compose.onNodeWithTag("vault-add-login").performClick()
        await { shown("vault-field:site") }
        plain("vault-field:site"); plain("vault-field:identifier"); masked("vault-field:password"); masked("vault-field:otp_secret")
        compose.onNodeWithTag("vault-field:site").performTextInput("github.com/login")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput("synthetic-password")
        compose.onNodeWithTag("vault-field:otp_secret").performTextInput("jbswy3dp")
        compose.onNodeWithTag("vault-submit").performClick()
        await { fixture.vaultCalls.any { it.first == "vault.add" } }
        val call = lastCall(fixture, "vault.add")
        assertEquals(setOf("kind", "label", "origin", "secret", "profile"), call.keys().asSequence().toSet())
        assertEquals("login", call.getString("kind")); assertEquals("https://github.com", call.getString("origin")); assertEquals("github.com", call.getString("label"))
        val secret = call.getJSONObject("secret")
        assertEquals(setOf("identifier_type", "identifier", "password", "otp_secret"), secret.keys().asSequence().toSet())
        assertEquals("email", secret.getString("identifier_type")); assertEquals(mail, secret.getString("identifier"))
        val saved = fixture.vaultItems.getValue("default").single()
        assertEquals("JBSWY3DP", fixture.vaultSecrets.getValue(saved.getString("id")).getString("otp_secret"))
        await { shown("vault-item:${saved.getString("id")}") }
        compose.onNodeWithTag("vault-item:${saved.getString("id")}").assertTextContains("Fills 2FA codes", substring = true)
        assertTrue("The form is gone and holds nothing", !shown("vault-field:password"))
    }

    @Test fun addCardAndAddressSendOnlyHermesCanonicalFields() = harness.run { vm, fixture ->
        openVault(vm)
        await { usable("vault-add-card") }
        compose.onNodeWithTag("vault-add-card").performClick()
        await { shown("vault-field:card_number") }
        for (tag in listOf("card_number", "exp_month", "exp_year", "cvc", "cardholder_name", "billing_postal_code")) masked("vault-field:$tag")
        assertFalse("A card has no site field: it works on any site", shown("vault-field:site"))
        compose.onNodeWithText("Works on any site. Each payment asks you first.").assertExists()
        compose.onNodeWithTag("vault-field:label").performTextInput("Visa")
        compose.onNodeWithTag("vault-field:card_number").performTextInput("4111111111111111")
        compose.onNodeWithTag("vault-field:exp_month").performTextInput("13")
        compose.onNodeWithTag("vault-field:exp_year").performTextInput("2030")
        compose.onNodeWithTag("vault-field:cvc").performTextInput("123")
        compose.onNodeWithTag("vault-submit").performClick()
        compose.onNodeWithText("Enter a valid expiration month.").assertExists()
        assertTrue("A bad month is stopped before anything is sent", fixture.vaultCalls.none { it.first == "vault.add" })
        compose.onNodeWithTag("vault-field:exp_month").performTextReplacement("12")
        compose.onNodeWithTag("vault-submit").performClick()
        await { fixture.vaultCalls.any { it.first == "vault.add" } }
        var call = lastCall(fixture, "vault.add")
        assertEquals("payment", call.getString("kind")); assertEquals("Visa", call.getString("label"))
        assertEquals("No site is sent: Hermes keeps the card for any site", setOf("kind", "label", "secret", "profile"), call.keys().asSequence().toSet())
        assertEquals(setOf("card_number", "exp_month", "exp_year", "cvc"), call.getJSONObject("secret").keys().asSequence().toSet())
        val card = fixture.vaultItems.getValue("default").single()
        assertTrue("Hermes saved the card with no site", card.isNull("origin"))
        await { shown("vault-item:${card.getString("id")}") }
        compose.onNodeWithTag("vault-item:${card.getString("id")}").assertTextContains("Any site", substring = true)
        await { usable("vault-add-address") && !shown("vault-field:card_number") }
        compose.onNodeWithTag("vault-add-address").performClick()
        await { shown("vault-field:address_line1") }
        compose.onNodeWithTag("vault-field:label").performTextInput("Home")
        compose.onNodeWithTag("vault-field:site").performTextInput("https://shop.example/checkout")
        for ((key, value) in listOf("address_line1" to "1 Example Street", "city" to "Example", "postal_code" to "00000", "country" to "Example")) {
            masked("vault-field:$key")
            compose.onNodeWithTag("vault-field:$key").performScrollTo().performTextInput(value)
        }
        compose.onNodeWithTag("vault-submit").performClick()
        await { fixture.vaultCalls.count { it.first == "vault.add" } == 2 }
        call = lastCall(fixture, "vault.add")
        assertEquals("address", call.getString("kind"))
        assertEquals(setOf("address_line1", "city", "postal_code", "country"), call.getJSONObject("secret").keys().asSequence().toSet())
        assertEquals(2, fixture.vaultItems.getValue("default").size)
    }

    @Test fun aCardWithNoSiteIsListedAsAnySiteWithOneLineAndACardWithASiteKeepsItsSite() = harness.run { vm, fixture ->
        val rows = fixture.vaultItems.computeIfAbsent("default") { java.util.concurrent.CopyOnWriteArrayList() }
        rows.add(json("id" to "vault_any", "kind" to "payment", "label" to "Travel card", "origin" to org.json.JSONObject.NULL, "created_at" to "2026-11-10T10:00:00+00:00", "backend" to "local"))
        rows.add(json("id" to "vault_site", "kind" to "payment", "label" to "Shop card", "origin" to "https://shop.example", "created_at" to "2026-11-10T10:00:00+00:00", "backend" to "local"))
        openVault(vm)
        await { shown("vault-item:vault_any") && shown("vault-item:vault_site") }
        compose.onNodeWithTag("vault-item:vault_any").assertTextContains("Any site", substring = true)
        compose.onNodeWithTag("vault-item:vault_site").assertTextContains("shop.example", substring = true)
        assertEquals("One line under the cards says each payment asks first", 1, compose.onAllNodesWithText("Each payment asks you first.").fetchSemanticsNodes().size)
        compose.onNodeWithTag("vault-item:vault_any").performClick()
        await { compose.onAllNodesWithText("Each payment asks you first.").fetchSemanticsNodes().size == 2 }
        assertTrue(compose.onAllNodesWithText("Any site").fetchSemanticsNodes().size >= 2)
        compose.onNodeWithText("Close").performClick()
        await { compose.onAllNodesWithText("Each payment asks you first.").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("vault-item:vault_site").performClick()
        compose.onNodeWithText("https://shop.example").assertExists()
        assertEquals("A card with a site keeps Hermes' own rule and says nothing about any site", 1, compose.onAllNodesWithText("Each payment asks you first.").fetchSemanticsNodes().size)
        compose.onNodeWithText("Close").performClick()
    }

    @Test fun removeAsksFirstAndOnlyThenSendsTheRemoval() = harness.run { vm, fixture ->
        val id = fixture.seedVaultLogin("default", "GitHub", "https://github.com", mail)
        openVault(vm)
        await { shown("vault-item:$id") }
        compose.onNodeWithTag("vault-item:$id").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()
        assertTrue("Closing removes nothing", fixture.vaultCalls.none { it.first == "vault.remove" })
        compose.onNodeWithTag("vault-item:$id").performClick()
        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithTag("vault-remove-confirm").performClick()
        await { fixture.vaultCalls.any { it.first == "vault.remove" } }
        assertEquals(id, lastCall(fixture, "vault.remove").getString("id"))
        await { !shown("vault-item:$id") }
        assertTrue(fixture.vaultItems.getValue("default").isEmpty())
    }

    @Test fun anItemFromAPasswordManagerCannotBeRemovedHere() = harness.run(configure = {
        seedVaultManager("default", "bitwarden", "Bitwarden", enabled = true, unlocked = true)
    }) { vm, fixture ->
        val id = fixture.seedVaultLogin("default", "Shop", "https://shop.example", "pat", backend = "bitwarden")
        openVault(vm)
        await { shown("vault-item:$id") }
        compose.onNodeWithTag("vault-item:$id").performClick()
        compose.onNodeWithText("Managed in Bitwarden").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Remove").fetchSemanticsNodes().size)
    }

    @Test fun passwordManagersSwitchUnlockWrongAndRightPasswordAndLock() = harness.run(configure = {
        seedVaultManager("default", "onepassword", "1Password", enabled = true, unlocked = false)
        seedVaultManager("default", "bitwarden", "Bitwarden", enabled = false, unlocked = false, installed = false)
    }) { vm, fixture ->
        openVault(vm)
        await { usable("vault-unlock:onepassword") }
        compose.onNodeWithTag("vault-source:onepassword").assertTextContains("Locked", substring = true)
        assertFalse("A manager that is not installed and not on is not offered", shown("vault-source:bitwarden"))
        compose.onNodeWithTag("vault-unlock:onepassword").performClick()
        await { shown("vault-field:password") }
        masked("vault-field:password")
        compose.onNodeWithTag("vault-field:password").performTextInput("wrong-master")
        compose.onNodeWithTag("vault-submit").performClick()
        await { fixture.vaultCalls.any { it.first == "vault.unlock" } }
        assertEquals(setOf("name", "password", "profile"), lastCall(fixture, "vault.unlock").keys().asSequence().toSet())
        await { compose.onAllNodesWithText("Couldn't unlock. Check the master password.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals("Hermes' and the tool's own words never reach the screen", 0, compose.onAllNodesWithText("signin failed", substring = true).fetchSemanticsNodes().size)
        await { usable("vault-unlock:onepassword") }
        compose.onNodeWithTag("vault-unlock:onepassword").performClick()
        await { shown("vault-field:password") }
        compose.onNodeWithTag("vault-field:password").performTextInput(fixture.vaultMasterPassword)
        compose.onNodeWithTag("vault-submit").performClick()
        await { shown("vault-lock:onepassword") }
        compose.onNodeWithTag("vault-source:onepassword").assertTextContains("Unlocked", substring = true)
        compose.onNodeWithTag("vault-lock:onepassword").performClick()
        await { usable("vault-unlock:onepassword") }
        assertEquals("onepassword", lastCall(fixture, "vault.lock").getString("name"))
        compose.onNodeWithTag("vault-source:onepassword").performClick()
        await { fixture.vaultCalls.any { it.first == "vault.source.set" } }
        assertEquals(false, lastCall(fixture, "vault.source.set").get("enabled"))
        await { !shown("vault-unlock:onepassword") }
    }

    @Test fun manageProfileVaultOpensTheSamePageForThatProfile() = harness.run(configure = {
        profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile")
    }) { vm, fixture ->
        fixture.seedVaultLogin("default", "Personal login", "https://personal.example", "me")
        val coder = fixture.seedVaultLogin("coder", "Coder login", "https://coder.example", "dev")
        await { vm.phoneSettings.bots.value.profiles.size == 3 && !vm.phoneSettings.bots.value.loading }
        scrollTo("profile-open:coder")
        compose.onNodeWithTag("profile-open:coder").performClick()
        await { vm.phoneSettings.bots.value.selected?.profile == "coder" && !vm.phoneSettings.bots.value.loading }
        scrollTo("profile-vault")
        compose.onNodeWithTag("profile-vault").performClick()
        await { shown("vault-page") && shown("vault-item:$coder") }
        assertEquals("Personal items never show in the coder profile", 0, compose.onAllNodesWithText("Personal login").fetchSemanticsNodes().size)
        assertEquals("coder", vm.settingsProfile.value)
        assertEquals("default", vm.ui.value.profile)
        assertTrue("The page asked for the coder profile", fixture.vaultCalls.last { it.first == "vault.list" }.second.string("profile") == "coder")
    }

    @Test fun aHermesWithoutAVaultShowsOneShortLineAndNoPage() = harness.run(configure = { vaultAvailable = false }) { vm, _ ->
        openVault(vm)
        await { compose.onAllNodesWithText("This Hermes doesn't have a Vault. Update Hermes to use it.").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(!shown("vault-add-login") && !shown("vault-managers"))
    }

    /**
     * The page reads again while the person types: the form is open and the read is in flight, its answer held by the fixture, so the page is
     * busy for exactly as long as the test says. The read is started the way the person starts one, with the page's own Refresh (Compose injects
     * the tap into the page behind the form's dialog). [releaseTheRead] delivers the held answer.
     */
    private fun startAReadWhileTheFormIsOpen(fixture: NativeExperienceFixture) {
        val reads = fixture.vaultCalls.count { it.first == "vault.list" }
        fixture.holdVaultListAcknowledgement = true
        compose.onNodeWithContentDescription("Refresh").performClick()
        await { fixture.vaultCalls.count { it.first == "vault.list" } > reads }
    }

    private fun releaseTheRead(fixture: NativeExperienceFixture) {
        fixture.releaseCreateAcknowledgement()
        compose.waitForIdle()
    }

    private val busyLine = "Still working. Try again in a moment."

    /** Taps Continue until [sent] says Hermes got the change: the read's answer reaches the page a moment after the fixture gives it. */
    private fun continueUntil(fixture: NativeExperienceFixture, sent: () -> Boolean) = compose.waitUntil(20_000) {
        if (!sent()) runCatching { compose.onNodeWithTag("vault-submit").performClick() }
        compose.waitForIdle()
        sent()
    }

    @Test fun aLoginTappedWhileThePageIsStillReadingStaysOpenWithWhatWasTypedAndSendsNothingUntilItCan() = harness.run { vm, fixture ->
        openVault(vm)
        await { usable("vault-add-login") }
        compose.onNodeWithTag("vault-add-login").performClick()
        await { shown("vault-field:site") }
        compose.onNodeWithTag("vault-field:site").performTextInput("github.com")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput("synthetic-password")
        startAReadWhileTheFormIsOpen(fixture)
        compose.onNodeWithTag("vault-submit").performClick()
        compose.waitForIdle()
        val said = compose.onAllNodesWithText(busyLine).fetchSemanticsNodes().isNotEmpty()
        assertTrue("busy line shown=$said; form open=${shown("vault-field:password")}; add sent=${fixture.vaultCalls.any { it.first == "vault.add" }}", said)
        assertTrue("The form is still open", shown("vault-field:password"))
        compose.onNodeWithTag("vault-field:site").assertTextContains("github.com")
        assertTrue("Nothing was sent for a change that did not start", fixture.vaultCalls.none { it.first == "vault.add" })
        // The read ends; the same Continue now sends exactly what was typed.
        releaseTheRead(fixture)
        continueUntil(fixture) { fixture.vaultCalls.any { it.first == "vault.add" } }
        assertEquals("github.com", lastCall(fixture, "vault.add").getString("label"))
        assertEquals("synthetic-password", lastCall(fixture, "vault.add").getJSONObject("secret").getString("password"))
        await { !shown("vault-field:password") }
    }

    @Test fun theMasterPasswordTappedWhileThePageIsStillReadingStaysOpenToo() = harness.run(configure = {
        seedVaultManager("default", "onepassword", "1Password", enabled = true, unlocked = false)
    }) { vm, fixture ->
        openVault(vm)
        await { usable("vault-unlock:onepassword") }
        compose.onNodeWithTag("vault-unlock:onepassword").performClick()
        await { shown("vault-field:password") }
        compose.onNodeWithTag("vault-field:password").performTextInput(fixture.vaultMasterPassword)
        startAReadWhileTheFormIsOpen(fixture)
        compose.onNodeWithTag("vault-submit").performClick()
        compose.waitForIdle()
        val said = compose.onAllNodesWithText(busyLine).fetchSemanticsNodes().isNotEmpty()
        assertTrue("busy line shown=$said; form open=${shown("vault-field:password")}; unlock sent=${fixture.vaultCalls.any { it.first == "vault.unlock" }}", said)
        assertTrue(shown("vault-field:password"))
        assertTrue(fixture.vaultCalls.none { it.first == "vault.unlock" })
        releaseTheRead(fixture)
        continueUntil(fixture) { fixture.vaultCalls.any { it.first == "vault.unlock" } }
        await { shown("vault-lock:onepassword") }
    }

    @Test fun aRefusalIsOneShortLineAndHermesWordsNeverShow() = harness.run { vm, fixture ->
        openVault(vm)
        await { usable("vault-add-login") }
        compose.onNodeWithTag("vault-add-login").performClick()
        await { shown("vault-field:site") }
        compose.onNodeWithTag("vault-field:site").performTextInput("github.com")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput("synthetic-password")
        compose.onNodeWithTag("vault-field:otp_secret").performTextInput("not base32 !")
        compose.onNodeWithTag("vault-submit").performClick()
        await { compose.onAllNodesWithText("Check the authenticator key.").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, compose.onAllNodesWithText("base32", substring = true).fetchSemanticsNodes().size)
        assertTrue(fixture.vaultItems["default"].isNullOrEmpty())
    }
}
