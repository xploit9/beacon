package dev.hermes.android.data

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.push.securePromptNoticeTitle
import dev.hermes.android.ui.NavigationRoute
import dev.hermes.android.ui.navigationRoute
import dev.hermes.android.ui.navigationTiles
import dev.hermes.android.ui.settingsHubRoutes
import dev.hermes.android.ui.vaultFormProblem
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The seven `vault.*` calls, the shapes Hermes' contract takes (profiles_vault_complete_foreign_subagents.py 444-566), and how the phone reads them. */
class NativeVaultTest {
    private class Wire(val answers: Map<String, JSONObject> = emptyMap()) {
        val calls = mutableListOf<Pair<String, JSONObject>>()
        val api = NativeVaultApi { method, params -> calls += method to JSONObject(params.toString()); answers[method] ?: JSONObject() }
    }

    @Test fun drawerOpensVaultWhileMemoryLivesUnderProfileSettings() {
        assertEquals(listOf("Skills", "Vault", "Cron", "Files", "Projects", "Bots", "Kanban", "Settings"), navigationTiles)
        assertEquals(NavigationRoute("More", "Vault"), navigationRoute("Vault"))
        assertFalse("Memory is reached from its profile, not the settings hub", settingsHubRoutes.contains("Memory"))
    }

    // ---- reading ----

    @Test fun snapshotReadsListAndSourcesAndKeepsMetadataOnly() = runBlocking {
        val ownerIdentifier = "owner" + 64.toChar() + "example.test"
        val wire = Wire(mapOf(
            "vault.list" to json("items" to JSONArray()
                .put(json("id" to "vault_1", "kind" to "login", "label" to "Mail", "origin" to "https://example.test", "identifier_type" to "email",
                    "identifier" to ownerIdentifier, "has_otp" to true, "backend" to "local", "password" to "private-canary", "created_at" to "2026-11-08T10:00:00+00:00"))
                .put(json("id" to "op_9", "kind" to "login", "label" to "Shop", "origin" to "https://shop.example", "identifier_type" to "username", "identifier" to "pat", "backend" to "onepassword"))
                .put(json("id" to "vault_2", "kind" to "payment", "label" to "Card", "origin" to "https://shop.example", "backend" to "local"))
                .put(json("id" to "vault_3", "kind" to "address", "label" to "Home", "origin" to "https://shop.example", "backend" to "local"))),
            "vault.sources" to json("sources" to JSONArray()
                .put(json("name" to "local", "display_name" to "Hermes vault", "enabled" to true, "needs_unlock" to false, "unlocked" to true, "installed" to true))
                .put(json("name" to "onepassword", "display_name" to "1Password", "enabled" to true, "needs_unlock" to true, "unlocked" to false, "installed" to true))
                .put(json("name" to "bitwarden", "display_name" to "Bitwarden", "enabled" to false, "needs_unlock" to true, "unlocked" to false, "installed" to false)))))
        val snapshot = wire.api.snapshot()
        assertEquals(listOf("vault.list", "vault.sources"), wire.calls.map { it.first })
        assertEquals(listOf(VaultKind.Login, VaultKind.Login, VaultKind.Payment, VaultKind.Address), snapshot.items.map { it.kind })
        assertEquals(ownerIdentifier, snapshot.items.first().identifier)
        assertTrue(snapshot.items.first().hasOtp)
        assertFalse("No secret-bearing field survives in the metadata or its cache form", snapshot.toJson().toString().contains("private-canary"))
        assertEquals("Hermes", vaultBadge(snapshot.items[0], snapshot.sources))
        assertEquals("1Password", vaultBadge(snapshot.items[1], snapshot.sources))
        assertEquals("Only a manager that is installed or already on is offered", listOf("onepassword"), snapshot.managers.map { it.name })
        assertEquals("The cache form reads back the same", snapshot, VaultSnapshot.from(snapshot.toJson()))
    }

    @Test fun anOddRowIsSkippedAndNeverHidesTheRest() = runBlocking {
        val wire = Wire(mapOf("vault.list" to json("items" to JSONArray()
            .put(json("id" to "good", "kind" to "login", "label" to "Mail", "origin" to "https://example.test", "backend" to "local"))
            .put(json("id" to "odd-kind", "kind" to "passkey", "label" to "Key", "origin" to ""))
            .put(json("id" to "", "kind" to "login", "label" to "No id"))
            .put(json("id" to "control", "kind" to "login", "label" to "Bad" + 7.toChar(), "origin" to "https://example.test"))
            .put(json("id" to "good", "kind" to "login", "label" to "Duplicate", "origin" to "https://example.test")))))
        assertEquals(listOf("good"), wire.api.snapshot().items.map { it.id })
    }

    // ---- writing: exactly what the contract takes ----

    @Test fun addLoginSendsKindLabelOriginAndTheFourLoginSecretKeys() = runBlocking {
        val wire = Wire(mapOf("vault.add" to json("id" to "vault_new")))
        val draft = vaultDraft(VaultKind.Login, "", "github.com/login", mapOf("identifier" to "pat" + 64.toChar() + "example.test", "password" to "synthetic-password", "otp_secret" to " JBSWY3DP "))!!
        assertEquals("vault_new", wire.api.add(draft))
        val (method, params) = wire.calls.single()
        assertEquals("vault.add", method)
        assertEquals(setOf("kind", "label", "origin", "secret"), params.keys().asSequence().toSet())
        assertEquals("login", params.getString("kind")); assertEquals("github.com", params.getString("label")); assertEquals("https://github.com", params.getString("origin"))
        val secret = params.getJSONObject("secret")
        assertEquals(setOf("identifier_type", "identifier", "password", "otp_secret"), secret.keys().asSequence().toSet())
        assertEquals("email", secret.getString("identifier_type")); assertEquals("JBSWY3DP", secret.getString("otp_secret"))
    }

    @Test fun anOptionalAuthenticatorKeyIsOmittedWhenBlank() {
        val draft = vaultDraft(VaultKind.Login, "Work", "https://example.test", mapOf("identifier" to "pat", "password" to "synthetic-password", "otp_secret" to "  "))!!
        assertEquals(setOf("identifier_type", "identifier", "password"), draft.secret.keys)
        assertEquals("username", draft.secret.getValue("identifier_type")); assertEquals("Work", draft.label)
    }

    @Test fun cardAndAddressSendOnlyHermesCanonicalFieldsAndNeverBlankOnes() {
        val card = vaultDraft(VaultKind.Payment, "Visa", "shop.example", mapOf("card_number" to "4111111111111111", "exp_month" to "12", "exp_year" to "2030", "cvc" to "123",
            "cardholder_name" to "", "billing_postal_code" to "10001", "unknown_extra" to "ignored"))!!
        assertEquals(setOf("card_number", "exp_month", "exp_year", "cvc", "billing_postal_code"), card.secret.keys)
        val address = vaultDraft(VaultKind.Address, "Home", "https://shop.example", mapOf("address_line1" to "1 Main St", "address_line2" to " ", "city" to "Town",
            "state" to "ST", "postal_code" to "10001", "country" to "US", "password" to "ignored"))!!
        assertEquals(setOf("address_line1", "city", "state", "postal_code", "country"), address.secret.keys)
        assertEquals("payment", card.kind.wire); assertEquals("https://shop.example", address.origin)
    }

    @Test fun aDraftWithoutASiteOrALoginSecretIsRefusedBeforeAnythingIsSent() {
        assertNull(vaultDraft(VaultKind.Login, "", "", mapOf("identifier" to "pat", "password" to "synthetic-password")))
        assertNull(vaultDraft(VaultKind.Login, "", "example.test", mapOf("identifier" to "", "password" to "synthetic-password")))
        assertNull(vaultDraft(VaultKind.Login, "", "example.test", mapOf("identifier" to "pat", "password" to "")))
        assertNull(vaultDraft(VaultKind.Payment, "Card", "not a site", emptyMap()))
    }

    // ---- a card saved once, with no site: usable on any site, and each payment asks the person first ----

    private val cardFields = mapOf("card_number" to "4242424242424242", "exp_month" to "12", "exp_year" to "2099", "cvc" to "737")

    @Test fun aCardIsSavedWithNoSiteAndSendsNoOrigin() = runBlocking {
        val wire = Wire(mapOf("vault.add" to json("id" to "vault_card")))
        val draft = vaultDraft(VaultKind.Payment, "Visa", "", cardFields)!!
        assertEquals("", draft.origin)
        assertEquals("vault_card", wire.api.add(draft))
        val params = wire.calls.single().second
        assertEquals("No origin key at all: Hermes stores the card with no site", setOf("kind", "label", "secret"), params.keys().asSequence().toSet())
        assertEquals("payment", params.getString("kind")); assertEquals("Visa", params.getString("label"))
        assertEquals(setOf("card_number", "exp_month", "exp_year", "cvc"), params.getJSONObject("secret").keys().asSequence().toSet())
    }

    @Test fun aCardWithNoSiteAndNoNameIsRefusedBeforeAnythingIsSent() {
        assertNull(vaultDraft(VaultKind.Payment, "  ", "", cardFields))
    }

    @Test fun onlyACardMaySkipTheSite() {
        assertNull(vaultDraft(VaultKind.Login, "Mail", "", mapOf("identifier" to "pat", "password" to "synthetic-password")))
        assertNull(vaultDraft(VaultKind.Address, "Home", "", mapOf("address_line1" to "1 Main St", "city" to "Town", "postal_code" to "10001", "country" to "US")))
        assertNull("A site that is typed but is not one still stops the card", vaultDraft(VaultKind.Payment, "Visa", "not a site", cardFields))
    }

    @Test fun aCardFromHermesWithNoSiteIsListedAsAnySiteAndOneWithASiteKeepsItsSite() {
        val none = VaultItem.from(json("id" to "vault_1", "kind" to "payment", "label" to "Visa", "origin" to JSONObject.NULL, "backend" to "local"))!!
        val absent = VaultItem.from(json("id" to "vault_2", "kind" to "payment", "label" to "Amex", "backend" to "local"))!!
        val bound = VaultItem.from(json("id" to "vault_3", "kind" to "payment", "label" to "Shop card", "origin" to "https://shop.example", "backend" to "local"))!!
        val login = VaultItem.from(json("id" to "vault_4", "kind" to "login", "label" to "Mail", "origin" to "https://example.test", "backend" to "local"))!!
        assertTrue(vaultAnySite(none) && vaultAnySite(absent))
        assertFalse(vaultAnySite(bound) || vaultAnySite(login))
        assertEquals("Any site", vaultItemSite(none, "Any site"))
        assertEquals("shop.example", vaultItemSite(bound, "Any site"))
        assertEquals("example.test", vaultItemSite(login, "Any site"))
        val snapshot = VaultSnapshot(listOf(none, bound), emptyList())
        assertEquals("The cache keeps a card's missing site as missing", snapshot, VaultSnapshot.from(snapshot.toJson()))
    }

    @Test fun theCardFormNeedsNoSiteWhileLoginsAndAddressesStillDo() {
        assertNull(vaultFormProblem(VaultKind.Payment, json("exp_month" to "12")))
        assertEquals("Enter a valid expiration month.", vaultFormProblem(VaultKind.Payment, json("exp_month" to "13")))
        assertEquals("Enter the site, like github.com.", vaultFormProblem(VaultKind.Login, json("site" to "")))
        assertEquals("Enter the site, like github.com.", vaultFormProblem(VaultKind.Address, json()))
        assertNull(vaultFormProblem(VaultKind.Login, json("site" to "github.com")))
    }

    @Test fun aSiteBecomesTheOriginHermesFillsOn() {
        assertEquals("https://github.com", vaultSiteOrigin("github.com"))
        assertEquals("https://github.com", vaultSiteOrigin(" https://GitHub.com/login?x=1 "))
        assertEquals("https://shop.example:8443", vaultSiteOrigin("https://shop.example:8443/cart"))
        assertEquals("https://shop.example", vaultSiteOrigin("https://shop.example:443"))
        assertEquals("http://localhost:3000", vaultSiteOrigin("http://localhost:3000"))
        for (bad in listOf("", "http://example.test", "https://user" + 64.toChar() + "example.test", "nodots", "not a site", "ftp://example.test"))
            assertNull(bad, vaultSiteOrigin(bad))
    }

    @Test fun identifiersAreTypedAsHermesNamesThem() {
        assertEquals("email", vaultIdentifierType("pat" + 64.toChar() + "example.test"))
        assertEquals("phone", vaultIdentifierType("+1 (555) 010-0100"))
        assertEquals("username", vaultIdentifierType("pat_s"))
    }

    @Test fun removeSourceSwitchUnlockAndLockSendTheContractParams() = runBlocking {
        val wire = Wire(mapOf("vault.remove" to json("removed" to true), "vault.source.set" to json("name" to "bitwarden", "enabled" to false),
            "vault.unlock" to json("name" to "onepassword", "unlocked" to true), "vault.lock" to json("locked" to true)))
        wire.api.remove("vault_1")
        wire.api.setSource("bitwarden", false)
        wire.api.unlock("onepassword", "synthetic-master")
        wire.api.lock("onepassword")
        wire.api.lock()
        assertEquals(listOf("vault.remove", "vault.source.set", "vault.unlock", "vault.lock", "vault.lock"), wire.calls.map { it.first })
        assertEquals("vault_1", wire.calls[0].second.getString("id"))
        assertEquals("bitwarden", wire.calls[1].second.getString("name")); assertEquals(false, wire.calls[1].second.get("enabled"))
        assertEquals(setOf("name", "password"), wire.calls[2].second.keys().asSequence().toSet())
        assertEquals("onepassword", wire.calls[3].second.getString("name"))
        assertTrue("Locking every manager sends no name", wire.calls[4].second.length() == 0)
    }

    @Test fun aChangeHermesDidNotConfirmIsAFailureNotAFalseSuccess() = runBlocking<Unit> {
        val wire = Wire(mapOf("vault.remove" to json("removed" to false), "vault.add" to json(), "vault.source.set" to json("name" to "bitwarden", "enabled" to true),
            "vault.unlock" to json("name" to "onepassword", "unlocked" to false)))
        assertTrue(runCatching { wire.api.remove("gone") }.isFailure)
        assertTrue(runCatching { wire.api.add(vaultDraft(VaultKind.Login, "", "example.test", mapOf("identifier" to "pat", "password" to "synthetic-password"))!!) }.isFailure)
        assertTrue(runCatching { wire.api.setSource("bitwarden", false) }.isFailure)
        assertTrue(runCatching { wire.api.unlock("onepassword", "synthetic-master") }.isFailure)
    }

    // ---- errors in short user text ----

    @Test fun errorsBecomeOneShortLineAndNeverHermesOrCliText() {
        fun vault(message: String) = ApiFailure(5095, message)
        assertEquals(VaultFailure.Unsupported, vaultFailure(ApiFailure(-32601, "unknown method: vault.list")))
        assertEquals("This Hermes doesn't have a Vault. Update Hermes to use it.", vaultErrorText(VaultOp.Read, ApiFailure(-32601, "unknown method: vault.list")))
        assertEquals("Enter the master password.", vaultErrorText(VaultOp.Unlock, vault("master password is required")))
        assertEquals("Turn that manager on first.", vaultErrorText(VaultOp.Unlock, vault("onepassword is not an enabled password manager")))
        assertEquals("Check the authenticator key.", vaultErrorText(VaultOp.Add, vault("authenticator key must be a base32 secret or an otpauth:// URI")))
        assertEquals("Fill in every required field.", vaultErrorText(VaultOp.Add, vault("payment items require card_number, cvc")))
        val cli = vaultErrorText(VaultOp.Unlock, vault("[ERROR] op: signin failed at /usr/lib/op/main.go:123 stack trace"))
        assertEquals("Couldn't unlock. Check the master password.", cli)
        assertEquals("Couldn't remove it.", vaultErrorText(VaultOp.Remove, vault("disk full")))
        assertEquals("Sign in again", vaultErrorText(VaultOp.Read, ApiFailure(401, "no")))
        assertEquals("Can't reach Hermes", vaultErrorText(VaultOp.Read, java.net.SocketTimeoutException()))
    }

    // ---- prompts ----

    private val scope = Scope("https://example.test", "owner", "work", session = "chat")
    private fun prompt(id: String = "id", method: String = "vault.code", expires: Long = 1_000, s: Scope = scope) =
        SecurePrompt(s, "runtime", id, method, "connection", expires, 3, 8, "example.test")

    @Test fun aPromptIsShownHeldOrDroppedByWhereThePersonIs() {
        fun d(foreground: Boolean = true, authenticated: Boolean = true, locked: Boolean = false, now: Long = 99, origin: Int = 3, s: Scope? = scope, p: SecurePrompt = prompt(expires = 100)) =
            securePromptDisposition(p, s, origin, 8, foreground, authenticated, locked, now)
        assertEquals(SecureDisposition.Show, d())
        assertEquals("Leaving the app keeps the question open", SecureDisposition.Hold, d(foreground = false))
        assertEquals("A locked phone keeps the question open", SecureDisposition.Hold, d(locked = true))
        assertEquals("Hermes' own time ran out", SecureDisposition.Drop, d(now = 100))
        assertEquals("Signed out", SecureDisposition.Drop, d(authenticated = false))
        assertEquals("Another account or profile or chat", SecureDisposition.Drop, d(s = scope.copy(profile = "other")))
        assertEquals(SecureDisposition.Drop, d(s = scope.copy(session = "other")))
        assertEquals("A new origin generation", SecureDisposition.Drop, d(origin = 4))
        assertEquals("Hold never outlives the deadline", SecureDisposition.Drop, d(foreground = false, now = 100))
    }

    @Test fun promptWindowsAreHermesOwnTimeouts() {
        assertEquals(120_000L, securePromptWindowMs("vault.unlock_prompt"))
        assertEquals(180_000L, securePromptWindowMs("vault.save_login"))
        assertEquals(180_000L, securePromptWindowMs("vault.code"))
        assertEquals(120_000L, securePromptWindowMs("secret"))
    }

    @Test fun securePromptCurrentStaysTheShowRule() {
        val p = prompt(expires = 100)
        assertTrue(securePromptCurrent(p, scope, 3, 8, true, true, false, 99))
        assertFalse(securePromptCurrent(p, scope, 3, 8, false, true, false, 99))
        assertFalse(securePromptCurrent(p, scope, 3, 8, true, true, true, 99))
    }

    // ---- the keeper: hold, notify once, show on return, answer only on purpose ----

    private class Room {
        val answers = mutableListOf<Pair<String, String>>()
        val noticed = mutableListOf<String>()
        val cleared = mutableListOf<String>()
        val keeper = SecurePromptKeeper({ p, v -> answers += p.id to v; true }, { noticed += it.id }, { cleared += it.id })
    }
    private fun ctx(foreground: Boolean = true, locked: Boolean = false, now: Long = 10, authenticated: Boolean = true) =
        SecureContext(scope, 3, 8, foreground, authenticated, locked, now)

    // ---- round 2 (G5 F6): what was typed in the sheet survives a reconnect of the stream ----

    @Test fun theQuestionIsKnownByHermesIdAndNotByTheConnectionItIsAskedOn() {
        val p = prompt("id", expires = 100_000)
        assertEquals(securePromptQuestionKey(p), securePromptQuestionKey(p.copy(connection = "new-connection")))
        assertNotEquals(securePromptQuestionKey(p), securePromptQuestionKey(prompt("other", expires = 100_000)))
        assertNotEquals(securePromptQuestionKey(p), securePromptQuestionKey(p.copy(method = "vault.save_login")))
    }

    @Test fun aReconnectKeepsTheSheetOnScreenAndTheSameQuestionOnTheNewConnectionTakesItOver() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        room.keeper.release(scope)                                         // the stream that carried it stopped
        room.keeper.settle(emptyList(), ctx(), linkLive = false)           // and the poll finds no connection: nothing is known
        assertEquals("The sheet, and what was typed in it, stays", p, room.keeper.shown)
        assertTrue("Nothing is answered", room.answers.isEmpty())
        val again = p.copy(connection = "new-connection")
        assertEquals(SecureDisposition.Show, room.keeper.offer(again, ctx()))
        assertEquals("The same question on the new connection replaces the stale one", again, room.keeper.shown)
        assertTrue(room.answers.isEmpty())
    }

    @Test fun aLiveListingOfTheSameQuestionOnANewConnectionReplacesTheSheetWithoutTakingItDown() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        val again = p.copy(connection = "new-connection")
        room.keeper.settle(listOf(again), ctx())
        assertEquals(again, room.keeper.shown)
        assertTrue(room.answers.isEmpty())
    }

    @Test fun aLiveListingThatKeepsMissingTheQuestionTakesTheSheetDownOnlyAfterTheGrace() {
        val room = Room()
        val p = prompt(expires = 1_000_000)
        room.keeper.offer(p, ctx(now = 10))
        room.keeper.settle(emptyList(), ctx(now = 10))
        room.keeper.settle(emptyList(), ctx(now = 10 + SECURE_UNLISTED_GRACE_MS - 1))
        assertEquals("The new connection has not replayed the question yet", p, room.keeper.shown)
        room.keeper.settle(emptyList(), ctx(now = 10 + SECURE_UNLISTED_GRACE_MS))
        assertNull("Hermes answered or withdrew it", room.keeper.shown)
        assertTrue("No answer is sent for it", room.answers.isEmpty())
    }

    @Test fun beingListedAgainRestartsTheGraceOfASheetThatWentMissingForAMoment() {
        val room = Room()
        val p = prompt(expires = 1_000_000)
        room.keeper.offer(p, ctx(now = 10))
        room.keeper.settle(emptyList(), ctx(now = 10))
        room.keeper.settle(listOf(p), ctx(now = 10 + SECURE_UNLISTED_GRACE_MS - 1))
        room.keeper.settle(emptyList(), ctx(now = 10 + SECURE_UNLISTED_GRACE_MS + 5))
        room.keeper.settle(emptyList(), ctx(now = 10 + SECURE_UNLISTED_GRACE_MS + 6))
        assertEquals("A moment missing after being listed starts a new grace", p, room.keeper.shown)
    }

    @Test fun aPromptThatArrivesInTheBackgroundIsHeldNotCancelledAndTheyAreTold() {
        val room = Room()
        val p = prompt(expires = 100_000)
        assertEquals(SecureDisposition.Hold, room.keeper.offer(p, ctx(foreground = false)))
        assertNull(room.keeper.shown)
        assertTrue("Hermes is not told 'no'", room.answers.isEmpty())
        assertEquals(listOf("id"), room.noticed)
        room.keeper.settle(listOf(p), ctx(foreground = false)); room.keeper.settle(listOf(p), ctx(foreground = false))
        assertEquals("Told once, not on every poll", listOf("id"), room.noticed)
        room.keeper.settle(listOf(p), ctx())
        assertEquals("Back in the app it is on screen", p, room.keeper.shown)
        assertEquals("Showing it withdraws the notice", listOf("id"), room.cleared)
        assertTrue(room.answers.isEmpty())
    }

    @Test fun aHeldQuestionThatReturnsOnANewConnectionStillWithdrawsItsNotice() {
        val room = Room()
        val first = prompt(expires = 100_000)
        room.keeper.offer(first, ctx(foreground = false))
        assertEquals(listOf("id"), room.noticed)
        val again = first.copy(connection = "new-connection")
        room.keeper.settle(listOf(again), ctx(foreground = false)); room.keeper.settle(listOf(again), ctx())
        assertEquals("Hermes' id, not the connection marker, names the question: told once", listOf("id"), room.noticed)
        assertEquals(again, room.keeper.shown)
        assertEquals(listOf("id"), room.cleared)
    }

    @Test fun aPromptThatArrivesOnALockedPhoneIsHeldAndShownAfterUnlock() {
        val room = Room()
        val p = prompt(expires = 100_000)
        assertEquals(SecureDisposition.Hold, room.keeper.offer(p, ctx(locked = true)))
        assertTrue(room.answers.isEmpty()); assertEquals(listOf("id"), room.noticed)
        room.keeper.settle(listOf(p), ctx(locked = false))
        assertEquals(p, room.keeper.shown)
    }

    @Test fun leavingTheAppWithTheSheetOpenHoldsItInsteadOfAnsweringEmpty() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        assertEquals(p, room.keeper.shown)
        room.keeper.settle(listOf(p), ctx(foreground = false))
        assertNull(room.keeper.shown)
        assertTrue("No empty answer reaches Hermes", room.answers.isEmpty())
        assertEquals(listOf("id"), room.noticed)
    }

    @Test fun theSystemClosingTheSheetIsNotAnAnswerButCancelIs() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        room.keeper.suspend(p)
        assertNull(room.keeper.shown); assertTrue(room.answers.isEmpty()); assertEquals(listOf("id"), room.noticed)
        room.keeper.settle(listOf(p), ctx())
        room.keeper.cancel(p)
        assertEquals(listOf("id" to ""), room.answers)
        assertNull(room.keeper.shown)
    }

    @Test fun aHeldPromptPastHermesDeadlineIsLetGoWithoutAnswering() {
        val room = Room()
        val p = prompt(expires = 50)
        room.keeper.offer(p, ctx(foreground = false, now = 10))
        room.keeper.settle(emptyList(), ctx(now = 60))
        assertEquals(SecureDisposition.Drop, room.keeper.offer(p, ctx(now = 60)))
        assertNull(room.keeper.shown); assertTrue("Nothing is waiting at Hermes any more", room.answers.isEmpty())
    }

    @Test fun aSecondPromptWaitsItsTurnInsteadOfBeingSkipped() {
        val room = Room()
        val first = prompt("one", expires = 100_000); val second = prompt("two", expires = 100_000)
        room.keeper.offer(first, ctx()); room.keeper.offer(second, ctx())
        assertEquals(first, room.keeper.shown)
        assertTrue("The second is still open at Hermes", room.answers.isEmpty())
        room.keeper.finished(first)
        room.keeper.settle(listOf(second), ctx())
        assertEquals(second, room.keeper.shown)
    }

    @Test fun signingOutOrChangingAccountSkipsTheOneOnScreen() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        room.keeper.clear()
        assertEquals(listOf("id" to ""), room.answers); assertNull(room.keeper.shown)
        val other = Room(); other.keeper.offer(p, ctx())
        other.keeper.settle(listOf(p), ctx(authenticated = false))
        assertEquals(listOf("id" to ""), other.answers)
    }

    @Test fun hermesWithdrawingAPromptRemovesItFromTheScreen() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx())
        room.keeper.withdrawn(scope, "id", "vault.code")
        assertNull(room.keeper.shown); assertTrue(room.answers.isEmpty())
    }

    @Test fun aHeldQuestionHermesNoLongerListsTakesItsNoticeWithIt() {
        // The agent's turn was interrupted while the app was away: Hermes withdrew the question, and "Save a login" must not sit there.
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx(foreground = false))
        assertEquals(listOf("id"), room.noticed); assertTrue(room.cleared.isEmpty())
        room.keeper.settle(emptyList(), ctx(foreground = false))
        assertEquals("The notice is withdrawn on the next look", listOf("id"), room.cleared)
        room.keeper.settle(emptyList(), ctx(foreground = false))
        assertEquals("Once, not on every look", listOf("id"), room.cleared)
        assertTrue("Hermes is not told anything", room.answers.isEmpty())
        // If Hermes asks again later the person is told again.
        room.keeper.offer(p, ctx(foreground = false))
        assertEquals(listOf("id", "id"), room.noticed)
    }

    @Test fun aWithdrawalHermesAnnouncesTakesAHeldNoticeToo() {
        val room = Room()
        val p = prompt(expires = 100_000)
        room.keeper.offer(p, ctx(foreground = false))
        room.keeper.withdrawn(scope, "other-id", "vault.code")
        room.keeper.withdrawn(scope, "id", "vault.save_login")
        assertTrue("Another question or kind leaves it alone", room.cleared.isEmpty())
        room.keeper.withdrawn(scope, "id", "vault.code")
        assertEquals(listOf("id"), room.cleared)
        assertTrue(room.answers.isEmpty())
    }

    @Test fun theOneNoticeStaysWhileAnyQuestionIsStillHeld() {
        val room = Room()
        val one = prompt("one", expires = 100_000); val two = prompt("two", expires = 100_000)
        room.keeper.offer(one, ctx(foreground = false)); room.keeper.offer(two, ctx(foreground = false))
        assertEquals(listOf("one", "two"), room.noticed)
        room.keeper.settle(listOf(two), ctx(foreground = false))
        assertTrue("one question is still held, so the notice stays", room.cleared.isEmpty())
        room.keeper.settle(emptyList(), ctx(foreground = false))
        assertEquals("the last one went", 1, room.cleared.size)
    }

    @Test fun aQuestionHeldForAnotherChatKeepsItsNoticeWhileThisChatSettles() {
        val room = Room()
        val elsewhere = prompt("elsewhere", expires = 100_000, s = scope.copy(session = "other-chat"))
        room.keeper.offer(elsewhere, ctx(foreground = false).copy(scope = elsewhere.scope))
        room.keeper.settle(emptyList(), ctx(foreground = false))
        assertTrue("this chat's list says nothing about the other chat", room.cleared.isEmpty())
    }

    @Test fun theNoticeNamesTheKindOfQuestionAndNeverTheSiteOrTheCode() {
        assertEquals("Save a login", securePromptNoticeTitle("vault.save_login"))
        assertEquals("Enter a verification code", securePromptNoticeTitle("vault.code"))
        assertEquals("Unlock your password manager", securePromptNoticeTitle("vault.unlock_prompt"))
        assertEquals("Hermes needs you", securePromptNoticeTitle("secret"))
    }

    @Test fun theLineAFormShowsWhenItCannotBeSentYetIsShortAndTranslated() {
        assertEquals("Still working. Try again in a moment.", dev.hermes.android.ui.VAULT_BUSY_LINE)
        assertTrue(dev.hermes.android.ui.VAULT_BUSY_LINE.length <= 70)
        val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.firstOrNull { java.io.File(it, "src/main/assets/native-locales.json").isFile })
        val locales = JSONObject(java.io.File(app, "src/main/assets/native-locales.json").readText())
        val missing = locales.keys().asSequence().filter { it != "en" }.filter { locales.getJSONObject(it).optString(dev.hermes.android.ui.normalized(dev.hermes.android.ui.VAULT_BUSY_LINE)).isBlank() }.toList()
        assertTrue("Missing in: $missing", missing.isEmpty())
    }
}
