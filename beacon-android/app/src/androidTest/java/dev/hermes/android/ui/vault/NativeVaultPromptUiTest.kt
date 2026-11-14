package dev.hermes.android.ui.vault

import android.app.NotificationManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Hermes' vault questions reach the phone as `vault.save_login`, `vault.code` and `vault.unlock_prompt` server requests
 * (server_requests.py:231-252) and a card fill as an `approval`. The fixture sends them exactly so, and applies the answer where
 * Hermes would (gateway/session_vault_prompt.py:56-69).
 */
class NativeVaultPromptUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val harness get() = VaultHarness(compose)
    private val mail = "pat" + 64.toChar() + "example.test"
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun noticeShown() = ApplicationProvider.getApplicationContext<android.content.Context>().getSystemService(NotificationManager::class.java)
        .activeNotifications.any { it.tag == "secure-input" }
    private fun masked(tag: String) = compose.onNodeWithTag(tag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))

    /** A chat with a running reply, so Hermes has a viewer attached on the stream (as when the agent is mid-task). */
    private fun startRun(vm: BeaconViewModel, fixture: NativeExperienceFixture) {
        val before = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Log into the site")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && fixture.streamSubscriptions.get() > before }
    }
    private fun answered(fixture: NativeExperienceFixture, id: String) = fixture.nativeServerResponses.firstOrNull { it.string("id") == id }

    @Test fun aSaveLoginQuestionIsAnsweredOnThePhoneAndLandsInTheNativeVault() = harness.run(chat = true) { vm, fixture ->
        startRun(vm, fixture)
        fixture.emitVaultPrompt("vault.save_login", "wire-login", json("origin" to "https://github.com", "site" to "github.com"))
        await { shown("vault-field:identifier") }
        assertTrue(compose.onAllNodesWithText("Save login").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("https://github.com", substring = true).assertExists()
        masked("vault-field:password")
        compose.onNodeWithTag("vault-field:identifier").performTextInput(mail)
        compose.onNodeWithTag("vault-field:password").performTextInput("synthetic-password")
        compose.onNodeWithTag("vault-submit").performClick()
        await { answered(fixture, "wire-login") != null }
        val value = JSONObject(answered(fixture, "wire-login")!!.getJSONObject("result").getString("value"))
        assertEquals("Exactly the two fields Hermes reads", setOf("identifier", "password"), value.keys().asSequence().toSet())
        val saved = fixture.vaultItems.getValue("default").single()
        assertEquals("https://github.com", saved.getString("origin")); assertEquals(mail, saved.getString("identifier"))
        assertEquals("synthetic-password", fixture.vaultSecrets.getValue(saved.getString("id")).getString("password"))
        assertTrue("The sheet is gone", !shown("vault-field:password"))
        // Remove what the run created.
        assertTrue(fixture.vaultItems.getValue("default").removeIf { it.getString("id") == saved.getString("id") })
    }

    @Test fun aCodeQuestionAndAManagerUnlockQuestionAreAnsweredToo() = harness.run(chat = true, configure = {
        seedVaultManager("default", "onepassword", "1Password", enabled = true, unlocked = false)
    }) { vm, fixture ->
        startRun(vm, fixture)
        fixture.emitVaultPrompt("vault.code", "wire-code", json("site" to "github.com", "hint" to "Authenticator app"))
        await { shown("vault-field:value") }
        assertTrue("The sheet is titled for the question", compose.onAllNodesWithText("Verification code").fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithTag("vault-field:value").performTextInput("123456")
        compose.onNodeWithTag("vault-submit").performClick()
        await { answered(fixture, "wire-code") != null }
        assertEquals("123456", answered(fixture, "wire-code")!!.getJSONObject("result").getString("value"))
        await { !shown("vault-field:value") }
        fixture.emitVaultPrompt("vault.unlock_prompt", "wire-unlock", json("backend" to "onepassword", "display_name" to "1Password"))
        await { shown("vault-field:value") }
        assertTrue(compose.onAllNodesWithText("Unlock Vault").fetchSemanticsNodes().isNotEmpty())
        masked("vault-field:value")
        compose.onNodeWithTag("vault-field:value").performTextInput(fixture.vaultMasterPassword)
        compose.onNodeWithTag("vault-submit").performClick()
        await { answered(fixture, "wire-unlock") != null }
        assertTrue("The manager is unlocked at Hermes", fixture.vaultManagers.getValue("default").single().unlocked)
    }

    @Test fun cancelAnswersEmptyButLeavingTheAppNeverDoes() = harness.run(chat = true) { vm, fixture ->
        startRun(vm, fixture)
        fixture.emitVaultPrompt("vault.code", "wire-cancel", json("site" to "github.com"))
        await { shown("vault-field:value") }
        compose.onNodeWithText("Cancel").performClick()
        await { answered(fixture, "wire-cancel") != null }
        assertEquals("A person's Cancel skips the question", "", answered(fixture, "wire-cancel")!!.getJSONObject("result").getString("value"))
    }

    @Test fun aQuestionOpenWhenTheAppLeavesIsHeldTheyAreNotifiedAndItReturnsToBeAnswered() = harness.run(chat = true) { vm, fixture ->
        startRun(vm, fixture)
        fixture.emitVaultPrompt("vault.code", "wire-held", json("site" to "github.com", "hint" to "Authenticator app"))
        await { shown("vault-field:value") }
        compose.onNodeWithTag("vault-field:value").performTextInput("1234")
        compose.runOnIdle { vm.foreground(false) }
        await { !shown("vault-field:value") }
        assertTrue("Leaving the app is not an answer: Hermes keeps waiting", fixture.nativeServerResponses.none { it.string("id") == "wire-held" })
        await { noticeShown() }
        compose.runOnIdle { vm.foreground(true) }
        await { shown("vault-field:value") }
        assertEquals("The half-typed code is not kept", "", compose.onNodeWithTag("vault-field:value").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        assertTrue("Back in the app the notice is withdrawn", run { await { !noticeShown() }; true })
        compose.onNodeWithTag("vault-field:value").performTextInput("654321")
        compose.onNodeWithTag("vault-submit").performClick()
        await { answered(fixture, "wire-held") != null }
        assertEquals("654321", answered(fixture, "wire-held")!!.getJSONObject("result").getString("value"))
    }

    @Test fun aCardFillApprovalAsksOneShortQuestionNamingTheCardAndSiteAndAnswersOnce() = harness.run(chat = true) { vm, fixture ->
        startRun(vm, fixture)
        fixture.emitCardFillApproval("wire-card", "Visa", "https://shop.example", amount = "12.50 USD")
        await { shown("approval-line") }
        compose.onNodeWithText("Confirm card payment").assertExists()
        // Hermes sends "Fill payment card 'Visa' on https://shop.example" and a three-sentence warning; the phone asks "Pay with your Visa on shop.example?" and says nothing more.
        compose.onNodeWithTag("approval-line").assertTextContains("Pay with your", substring = true).assertTextContains("Visa", substring = true).assertTextContains("shop.example", substring = true)
        assertEquals("https:// is not said to a person", 0, compose.onAllNodesWithText("https://", substring = true).fetchSemanticsNodes().size)
        compose.onAllNodesWithTag("approval-detail").assertCountEquals(0)
        assertEquals("no CVC sentence and no \"Approve only if\"", 0, compose.onAllNodesWithText("CVC", substring = true).fetchSemanticsNodes().size + compose.onAllNodesWithText("Approve only", substring = true).fetchSemanticsNodes().size)
        compose.onNodeWithTag("approval-amount").assertTextContains("12.50 USD", substring = true)
        assertEquals("Hermes offers once and deny for a card", 0, compose.onAllNodesWithText("For this chat").fetchSemanticsNodes().size)
        compose.onNodeWithText("Allow once").performScrollTo().performClick()
        await { answered(fixture, "wire-card") != null }
        assertEquals("once", answered(fixture, "wire-card")!!.getJSONObject("result").getString("choice"))
    }
}
