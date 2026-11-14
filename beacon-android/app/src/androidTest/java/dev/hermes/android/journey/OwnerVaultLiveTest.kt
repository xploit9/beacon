package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.data.NativeVaultApi
import dev.hermes.android.data.VaultItem
import dev.hermes.android.data.VaultKind
import dev.hermes.android.data.vaultDraft
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.translateUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/** Only a coordinator-created isolated QA profile may receive these disposable values. Hermes' own Passwords & Logins, through the app's page. */
class OwnerVaultLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 300_000) fun isolatedVaultWritesReadBackAndRemoveOnlyTheirOwnItems() = runBlocking {
        val h = OwnerJourneyHarness(compose, "vault")
        h.checked {
            qa()
            val labelPrefix = "Beacon QA ${runId}"
            val site = "https://example.invalid"
            val owned = mutableListOf<VaultItem>()
            var unreconciled = false
            val api = NativeVaultApi(client)
            suspend fun items(): List<VaultItem> { current(); return withTimeout(15_000) { api.snapshot().items } }
            try {
                check(items().none { it.label.startsWith(labelPrefix) })
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                if (compose.onAllNodesWithTag("navigation-vault").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("navigation-more").performClick()
                compose.onNodeWithTag("navigation-vault").performClick()
                await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Vault" }
                compose.onNodeWithTag("vault-page").assertExists()
                val language = nativeLanguageFor(compose.activity.resources.configuration.locales[0])
                val dictionary = if (language == "en") JSONObject() else app.readTranslations().optJSONObject(language) ?: JSONObject()
                val addLogin = translateUi(dictionary, "Add login")
                await { compose.onAllNodesWithText(addLogin).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(addLogin).performScrollTo().performClick()
                compose.onNodeWithTag("vault-field:site").assertExists()
                compose.onNodeWithText(translateUi(dictionary, "Cancel")).performClick()
                await { compose.onAllNodesWithTag("vault-field:site").fetchSemanticsNodes().isEmpty() }
                check(items().none { it.label.startsWith(labelPrefix) })
                mark("X-01", "vault_form_cancel", "passed")

                val qaEmail = "qa" + 64.toChar() + "example.invalid"
                compose.onNodeWithText(addLogin).performScrollTo().performClick()
                compose.onNodeWithTag("vault-field:site").performTextInput(site)
                compose.onNodeWithTag("vault-field:identifier").performScrollTo().performTextInput(qaEmail)
                compose.onNodeWithTag("vault-field:password").performScrollTo().performTextInput("qa-synthetic-only")
                unreconciled = true
                compose.onNodeWithTag("vault-submit").performClick()
                var login: VaultItem? = null
                await(30_000) {
                    delay(300)
                    login = items().singleOrNull { it.kind == VaultKind.Login && it.origin == site && it.identifier == qaEmail }
                    login != null
                }
                owned += checkNotNull(login)
                unreconciled = false
                mark("X-01", "vault_login_ui_readback", "passed")

                for (draft in listOf(
                    vaultDraft(VaultKind.Payment, "$labelPrefix card", site, mapOf("card_number" to "4111111111111111", "exp_month" to "12", "exp_year" to "2099", "cvc" to "123")),
                    vaultDraft(VaultKind.Address, "$labelPrefix address", site, mapOf("address_line1" to "1 Example Street", "city" to "Example", "postal_code" to "00000", "country" to "Example")),
                )) {
                    unreconciled = true
                    val id = api.add(checkNotNull(draft))
                    val saved = items().singleOrNull { it.id == id }
                    check(saved != null && saved.label.startsWith(labelPrefix) && saved.origin == site)
                    owned += saved
                    unreconciled = false
                }
                mark("X-01", "vault_card_address_readback", "passed")

                val first = owned.first()
                await { compose.onAllNodesWithTag("vault-item:${first.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("vault-item:${first.id}").performScrollTo().performClick()
                val removeLabel = translateUi(dictionary, "Remove")
                compose.onNodeWithText(removeLabel).performClick()
                unreconciled = true
                compose.onNodeWithTag("vault-remove-confirm").performClick()
                await(30_000) { delay(300); items().none { it.id == first.id } }
                owned.removeAt(0)
                unreconciled = false
                mark("X-01", "vault_remove_ui_readback", "passed")
            } finally {
                try {
                    // An unconfirmed request can still settle. Do not guess its outcome or replay it.
                    if (unreconciled) mark("X-01", "vault_write_unconfirmed", "needs_human")
                    else {
                        for (item in owned.asReversed()) {
                            // Only what this run made: the card and address carry the prefix, the login the synthetic address.
                            val found = items().singleOrNull { it.id == item.id }
                            check(found != null && found.origin == site && (found.label.startsWith(labelPrefix) || found.identifier.startsWith("qa")))
                            api.remove(item.id)
                            check(items().none { it.id == item.id })
                        }
                        mark("X-01", "vault_owned_cleanup", "passed", owned.size)
                    }
                } catch (error: Throwable) {
                    runCatching { mark("X-01", "vault_cleanup_unconfirmed", "needs_human") }
                    throw error
                }
            }
        }
    }
}
