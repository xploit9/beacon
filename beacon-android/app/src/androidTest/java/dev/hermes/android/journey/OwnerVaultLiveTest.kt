package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.data.VaultMetadata
import dev.hermes.android.data.VaultSnapshot
import dev.hermes.android.data.vaultMetadataOnly
import dev.hermes.android.data.vaultSavedItem
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.translateUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import java.util.UUID

private const val VAULT = "/api/plugins/beacon/mobile/vault"

/** Only a coordinator-created isolated QA profile may receive these disposable values. */
class OwnerVaultLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 300_000) fun isolatedVaultWritesReadBackAndRemoveOnlyTheirOwnItems() = runBlocking {
        val h = OwnerJourneyHarness(compose, "vault")
        h.checked {
            qa()
            val labelPrefix = "Beacon QA ${runId}"
            val origin = "https://example.invalid"
            val owned = mutableListOf<VaultMetadata>()
            var writeInFlight = false
            var unreconciled = false
            suspend fun snapshot(): VaultSnapshot {
                current()
                val result = withTimeout(15_000) { client.nativeRequest(VAULT) }
                current()
                check(vaultMetadataOnly(result))
                return VaultSnapshot.from(result)
            }
            suspend fun mutate(path: String, method: String, body: JSONObject): JSONObject {
                current()
                writeInFlight = true
                val result = client.nativeSensitiveRequest(path, method, body)
                current()
                writeInFlight = false
                check(vaultMetadataOnly(result))
                return result
            }
            suspend fun create(kind: String, secret: JSONObject) {
                val label = "$labelPrefix $kind"
                check(snapshot().items.none { it.label == label })
                val request = UUID.randomUUID().toString()
                unreconciled = true
                val receipt = mutate("$VAULT/items", "POST", json("request_id" to request, "kind" to kind,
                    "label" to label, "origin" to origin, "secret" to secret))
                val item = vaultSavedItem(receipt, request)
                check(item.label == label && item.kind == kind && item.origin == origin)
                owned += item
                unreconciled = false
                check(snapshot().items.singleOrNull { it.id == item.id } == item)
                mark("X-01", "vault_${kind}_readback", "passed")
            }
            try {
                val initial = snapshot()
                check(initial.capabilities.supported && initial.capabilities.local)
                check(initial.items.none { it.label.startsWith(labelPrefix) })
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
                compose.onNodeWithTag("vault-field:label").assertExists()
                compose.onNodeWithText(translateUi(dictionary, "Cancel")).performClick()
                await { compose.onAllNodesWithTag("vault-field:label").fetchSemanticsNodes().isEmpty() }
                check(snapshot().items.none { it.label.startsWith(labelPrefix) })
                mark("X-01", "vault_form_cancel", "passed")

                val qaEmail = "qa" + 64.toChar() + "example.invalid"
                val loginLabel = "$labelPrefix login"
                compose.onNodeWithText(addLogin).performScrollTo().performClick()
                compose.onNodeWithTag("vault-field:label").performTextInput(loginLabel)
                compose.onNodeWithTag("vault-field:origin").performScrollTo().performTextInput(origin)
                compose.onNodeWithTag("vault-field:identifier").performScrollTo().performTextInput(qaEmail)
                compose.onNodeWithTag("vault-field:password").performScrollTo().performTextInput("qa-synthetic-only")
                unreconciled = true
                compose.onNodeWithTag("vault-submit").performClick()
                var login: VaultMetadata? = null
                await(30_000) {
                    delay(300)
                    login = snapshot().items.singleOrNull { it.label == loginLabel && it.kind == "login" && it.origin == origin }
                    login != null
                }
                owned += checkNotNull(login)
                unreconciled = false
                mark("X-01", "vault_login_ui_readback", "passed")
                create("payment", json("card_number" to "4111111111111111", "exp_month" to "12", "exp_year" to "2099", "cvc" to "123"))
                create("address", json("address_line1" to "1 Example Street", "city" to "Example", "postal_code" to "00000", "country" to "Example"))
                val generatedLabel = "$labelPrefix generated"
                val generatedRequest = UUID.randomUUID().toString()
                unreconciled = true
                val generated = vaultSavedItem(mutate("$VAULT/generate-login", "POST", json("request_id" to generatedRequest,
                    "label" to generatedLabel, "origin" to origin, "identifier_type" to "email", "identifier" to qaEmail)), generatedRequest)
                check(generated.label == generatedLabel && generated.kind == "login" && generated.origin == origin)
                owned += generated
                unreconciled = false
                check(snapshot().items.any { it == generated })
                mark("X-01", "vault_generated_readback", "passed")

                val original = owned.first()
                val updatedLabel = "$labelPrefix edited"
                await { compose.onAllNodesWithTag("vault-item:${original.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("vault-item:${original.id}").performScrollTo().performClick()
                compose.onNodeWithText(translateUi(dictionary, "Edit")).performClick()
                compose.onNodeWithTag("vault-field:label").performTextReplacement(updatedLabel)
                unreconciled = true
                compose.onNodeWithTag("vault-submit").performClick()
                var edited: VaultMetadata? = null
                await(30_000) {
                    delay(300)
                    edited = snapshot().items.singleOrNull { it.id == original.id && it.label == updatedLabel && it.origin == origin }
                    edited != null
                }
                owned[0] = checkNotNull(edited)
                unreconciled = false
                mark("X-01", "vault_edit_ui_readback", "passed")

                compose.onNodeWithTag("vault-item:${original.id}").performScrollTo().performClick()
                val deleteLabel = translateUi(dictionary, "Delete")
                compose.onNodeWithText(deleteLabel).performClick()
                unreconciled = true
                compose.onNodeWithText(deleteLabel).performClick()
                await(30_000) { delay(300); snapshot().items.none { it.id == original.id } }
                owned.removeAt(0)
                unreconciled = false
                mark("X-01", "vault_delete_ui_readback", "passed")

                // The coordinator-provisioned profile must start with no identity to overwrite.
                val beforeIdentity = snapshot().identity
                check(beforeIdentity.name.isBlank() && beforeIdentity.email.isBlank())
                val identityRequest = UUID.randomUUID().toString()
                unreconciled = true
                val identity = mutate("$VAULT/identity", "PUT", json("request_id" to identityRequest, "name" to "QA", "email" to qaEmail))
                check(identity.optString("request_id") == identityRequest && identity.optString("name") == "QA")
                check(snapshot().identity.name == "QA" && snapshot().identity.email == qaEmail)
                val restoreRequest = UUID.randomUUID().toString()
                val restored = mutate("$VAULT/identity", "PUT", json("request_id" to restoreRequest, "name" to "", "email" to ""))
                check(restored.optString("request_id") == restoreRequest && snapshot().identity == beforeIdentity)
                unreconciled = false
                mark("X-01", "vault_identity_restored", "passed")
            } finally {
                try {
                    // An unconfirmed request can still settle. Do not guess its outcome or replay it.
                    if (writeInFlight || unreconciled) mark("X-01", "vault_write_unconfirmed", "needs_human")
                    else {
                        for (item in owned.asReversed()) {
                            current()
                            val found = snapshot().items.singleOrNull { it.id == item.id }
                            check(found == item && item.label.startsWith(labelPrefix) && item.origin == origin)
                            val request = UUID.randomUUID().toString()
                            val removed = mutate("$VAULT/items/${item.id}", "DELETE", json("request_id" to request))
                            check(removed.optString("request_id") == request && removed.opt("removed") == true)
                            check(snapshot().items.none { it.id == item.id })
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
