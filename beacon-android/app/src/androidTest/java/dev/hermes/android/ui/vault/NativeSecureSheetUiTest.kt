package dev.hermes.android.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Round 2 of lane F8 (G5 findings F6 and F7). Typed secrets are never lost to the keyboard, to Back or to a reconnect; an answer goes to
 * Hermes only on an explicit Continue or Cancel; and every Vault sheet keeps Continue reachable above the on-screen keyboard.
 * Everything typed here is synthetic. The keyboard is the emulator's real one: the taps that matter are real screen taps.
 */
class NativeSecureSheetUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val harness get() = VaultHarness(compose)
    private val mail = "pat" + 64.toChar() + "example.test"
    private val password = "synthetic-password"
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun typed(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.EditableText].text
    /** A masked field shows dots, so the length is what can be compared; the exact value is checked on what reaches Hermes. */
    private fun typedLength(tag: String) = typed(tag).length
    private fun answered(fixture: NativeExperienceFixture, id: String) = fixture.nativeServerResponses.firstOrNull { it.string("id") == id }
    private fun keyboardShown() = harness.shell("dumpsys input_method").contains("mInputShown=true")

    /** Back exactly as the system sends it to the window that has focus. */
    private fun pressBack() { harness.shell("input keyevent 4") }

    /** A real finger on the middle of [tag]: what is under it (the button, or the keyboard) takes the tap. */
    private fun tapOnScreen(tag: String) {
        val node = compose.onNodeWithTag(tag).fetchSemanticsNode()
        val at = node.positionOnScreen
        harness.shell("input tap ${(at.x + node.size.width / 2).toInt()} ${(at.y + node.size.height / 2).toInt()}")
    }

    private fun startRun(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        val before = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Log into the site")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && fixture.streamSubscriptions.get() > before }
    }

    private fun openSaveLogin(vm: BeaconViewModel, fixture: NativeExperienceFixture, id: String) {
        startRun(vm, fixture)
        fixture.emitVaultPrompt("vault.save_login", id, json("origin" to "https://github.com", "site" to "github.com"))
        await { shown("vault-field:identifier") }
    }

    private fun assertAnsweredWithWhatWasTyped(fixture: NativeExperienceFixture, id: String) {
        await { answered(fixture, id) != null }
        val value = JSONObject(answered(fixture, id)!!.getJSONObject("result").getString("value"))
        assertEquals(mail, value.getString("identifier"))
        assertEquals(password, value.getString("password"))
        assertTrue("The sheet is gone once it was sent", !shown("vault-field:password"))
    }

    // ---- F6: typed secrets are never lost; an answer is only ever an explicit Continue or Cancel -----------------------------------------

    @Test fun backOnlyHidesTheKeyboardAndContinueSendsWhatWasTyped() = harness.run(chat = true) { vm, fixture ->
        openSaveLogin(vm, fixture, "wire-back")
        compose.onNodeWithTag("vault-field:identifier").performClick()
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performClick()
        compose.onNodeWithTag("vault-field:password").performTextInput(password)
        await { keyboardShown() }
        pressBack()
        await { !keyboardShown() }
        compose.waitForIdle()
        assertTrue("Back is not an answer to Hermes", answered(fixture, "wire-back") == null)
        assertTrue("Back only hides the keyboard: the sheet is still there", shown("vault-field:password"))
        assertEquals(mail, typed("vault-field:identifier"))
        assertEquals(password.length, typedLength("vault-field:password"))
        compose.onNodeWithTag("vault-submit").performClick()
        assertAnsweredWithWhatWasTyped(fixture, "wire-back")
    }

    @Test fun backWithTheKeyboardAlreadyHiddenDoesNotAnswerOrCloseTheSheetEither() = harness.run(chat = true) { vm, fixture ->
        openSaveLogin(vm, fixture, "wire-back-twice")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput(password)
        await { keyboardShown() }
        pressBack()
        await { !keyboardShown() }
        compose.waitForIdle()
        assertTrue("The first Back only hid the keyboard", shown("vault-field:password") && answered(fixture, "wire-back-twice") == null)
        pressBack() // the keyboard is already hidden: this Back must not close the sheet or answer Hermes either
        compose.waitForIdle()
        Thread.sleep(1500)
        assertTrue("Back is not an answer to Hermes", answered(fixture, "wire-back-twice") == null)
        assertTrue("The sheet holds what was typed until Continue or Cancel", shown("vault-field:password"))
        assertEquals(mail, typed("vault-field:identifier"))
        assertEquals(password.length, typedLength("vault-field:password"))
        compose.onNodeWithTag("vault-submit").performClick()
        assertAnsweredWithWhatWasTyped(fixture, "wire-back-twice")
    }

    /** What the owner met in G5: a running reply goes quiet, the app resubscribes, Hermes asks again on the new connection. */
    @Test fun aReconnectOfTheStreamKeepsTheTypedValuesAndContinueStillReachesHermes() = harness.run(chat = true) { vm, fixture ->
        openSaveLogin(vm, fixture, "wire-reconnect")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput(password)
        val before = fixture.streamSubscriptions.get()
        fixture.interruptActiveNativeStream()
        await { fixture.streamSubscriptions.get() > before }
        Thread.sleep(4000) // the open question comes back on the new connection and the poll settles on it
        compose.waitForIdle()
        assertTrue("Hermes is still asking: the sheet is there", shown("vault-field:password"))
        assertEquals("The reconnect does not clear what was typed", mail, typed("vault-field:identifier"))
        assertEquals(password.length, typedLength("vault-field:password"))
        assertTrue("Nothing was sent by the reconnect", answered(fixture, "wire-reconnect") == null)
        compose.onNodeWithTag("vault-submit").performClick()
        assertAnsweredWithWhatWasTyped(fixture, "wire-reconnect")
    }

    /** Continue pressed in the moment the connection is gone: nothing is lost, and the next Continue reaches Hermes. */
    @Test fun aContinueThatCannotBeSentKeepsTheSheetAndWhatWasTyped() = harness.run(chat = true) { vm, fixture ->
        openSaveLogin(vm, fixture, "wire-stale")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput(password)
        val before = fixture.streamSubscriptions.get()
        fixture.interruptActiveNativeStream()
        assertTrue("The sheet is not taken away by the connection dropping", shown("vault-submit"))
        compose.onNodeWithTag("vault-submit").performClick()
        await { fixture.streamSubscriptions.get() > before }
        Thread.sleep(4000)
        compose.waitForIdle()
        if (answered(fixture, "wire-stale") == null) {
            assertTrue("A send that failed leaves the sheet open", shown("vault-field:password"))
            assertEquals(mail, typed("vault-field:identifier"))
            assertEquals(password.length, typedLength("vault-field:password"))
            compose.onNodeWithTag("vault-submit").performClick()
        }
        assertAnsweredWithWhatWasTyped(fixture, "wire-stale")
    }

    // ---- F7: Continue stays reachable above the keyboard on every Vault sheet -------------------------------------------------------------------

    private fun openVault(vm: BeaconViewModel) {
        compose.runOnIdle { vm.navigate("Vault") }
        await { shown("vault-page") }
    }

    private fun scrollTo(tag: String) {
        val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
        compose.onNode(SemanticsMatcher("Settings detail list") { it.id == list.id }).performScrollToNode(hasTestTag(tag))
    }

    /**
     * Opens [add], fills [required], focuses [last] with a real tap (so the keyboard comes up), then taps the middle of Continue with a real
     * finger. The keyboard must be up, Continue must take the tap, and the focused field must hold exactly what was typed (no stray character).
     */
    private fun continueIsReachableAboveTheKeyboard(vm: BeaconViewModel, fixture: NativeExperienceFixture, add: String, required: List<Pair<String, String>>, last: String, lastText: String, saved: () -> Boolean) {
        openVault(vm)
        await { shown(add) && runCatching { compose.onNodeWithTag(add).assertIsEnabled() }.isSuccess }
        scrollTo(add)
        compose.onNodeWithTag(add).performClick()
        await { shown("vault-field:${required.first().first}") }
        required.forEach { (tag, value) -> compose.onNodeWithTag("vault-field:$tag").performTextInput(value) }
        compose.onNodeWithTag("vault-field:$last").performScrollTo().performClick()
        compose.onNodeWithTag("vault-field:$last").performTextInput(lastText)
        await { keyboardShown() }
        compose.waitForIdle()
        assertTrue("The keyboard is up with the last field focused", keyboardShown())
        tapOnScreen("vault-submit")
        compose.waitForIdle()
        if (shown("vault-field:$last")) assertEquals("A tap meant for Continue typed nothing into the form", lastText.length, typedLength("vault-field:$last"))
        await { saved() }
        assertTrue("The sheet took the tap and closed", !shown("vault-submit"))
    }

    @Test fun addPaymentCardKeepsContinueAboveTheKeyboardWhenTheLastFieldIsFocused() = harness.run { vm, fixture ->
        continueIsReachableAboveTheKeyboard(vm, fixture, "vault-add-card",
            listOf("label" to "Test card", "card_number" to "4242424242424242", "exp_month" to "12", "exp_year" to "2099", "cvc" to "737", "cardholder_name" to "Pat Example"),
            last = "billing_postal_code", lastText = "00000", saved = { fixture.vaultCalls.any { it.first == "vault.add" } })
        fixture.vaultItems.getValue("default").clear()
    }

    @Test fun addLoginKeepsContinueAboveTheKeyboardWhenTheLastFieldIsFocused() = harness.run { vm, fixture ->
        continueIsReachableAboveTheKeyboard(vm, fixture, "vault-add-login",
            listOf("site" to "github.com/login", "identifier" to mail, "password" to password),
            last = "otp_secret", lastText = "jbswy3dp", saved = { fixture.vaultCalls.any { it.first == "vault.add" } })
        fixture.vaultItems.getValue("default").clear()
    }

    @Test fun addAddressKeepsContinueAboveTheKeyboardWhenTheLastFieldIsFocused() = harness.run { vm, fixture ->
        continueIsReachableAboveTheKeyboard(vm, fixture, "vault-add-address",
            listOf("label" to "Home", "site" to "example.com", "address_line1" to "1 Example Street", "city" to "Example", "postal_code" to "00000"),
            last = "country", lastText = "Example", saved = { fixture.vaultCalls.any { it.first == "vault.add" } })
        fixture.vaultItems.getValue("default").clear()
    }
}
