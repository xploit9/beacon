package dev.hermes.android.journey

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.ViewModelProvider
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.string
import dev.hermes.android.loginLiveTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import dev.hermes.android.core.json
import dev.hermes.android.data.NativeVaultApi
import dev.hermes.android.data.VaultItem
import dev.hermes.android.data.VaultKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain

/**
 * The finished Vault page against the DEMO Hermes (never the owner's), driven through the app's own socket: the plugin's canonical proxy
 * when the plugin is installed, `/api/ws` otherwise. Everything it writes is a disposable item it removes again. Run only with
 * `-e beaconDemoVault 1` (plus the owner-runtime flags the live journeys use); the account must live under the demo Hermes home.
 */
class DemoVaultLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)


    /**
     * The demo account is signed in from a one-shot file the coordinator places in the app's private storage (purpose beacon_vault_demo),
     * exactly as the other demo journeys do; nothing secret is on a command line. With no file the app's existing sign-in is used.
     */
    private suspend fun signInDemoWhenAsked() {
        val app = compose.activity.application as BeaconApplication
        val file = File(app.filesDir, "live-auth.json")
        if (!file.isFile) return
        val credentials = JSONObject(file.readText())
        check(file.delete() && credentials.string("purpose") == "beacon_vault_demo")
        val origin = credentials.getString("origin")
        loginLiveTest(app, origin, credentials)
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
    }

    @Test(timeout = 300_000) fun theVaultPageListsAddsAndRemovesOnTheDemoHermes() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconDemoVault") == "1")
        signInDemoWhenAsked()
        val h = OwnerJourneyHarness(compose, "demovault")
        h.checked {
            // Demo only: the profile's Hermes home is the isolated demo home, as the other live journeys require.
            val home = client.nativeRpc("config.get", json("key" to "profile")).optString("home").replace('\\', '/').trimEnd('/')
            check(home.endsWith("/hermes-demo") || home.contains("/hermes-demo/")) { "demo_home_required" }
            mark("V-01", "demo_home_verified", "passed")
            val api = NativeVaultApi(client)
            val label = "Beacon QA live ${runId.take(8)}"
            val site = "https://example.invalid"
            val owner = "qa-live-${runId.take(6)}"
            suspend fun items(): List<VaultItem> { current(); return withTimeout(15_000) { api.snapshot().items } }
            var created: VaultItem? = null
            try {
                val baseline = items()
                check(baseline.none { it.origin == site && it.identifier == owner })
                mark("V-01", "snapshot_over_app_socket", "passed", baseline.size)
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                if (compose.onAllNodesWithTag("navigation-vault").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("navigation-more").performClick()
                compose.onNodeWithTag("navigation-vault").performClick()
                await { vm.ui.value.tab == "More" && vm.ui.value.morePage == "Vault" }
                await { compose.onAllNodesWithTag("vault-page").fetchSemanticsNodes().isNotEmpty() }
                // The sources row group shows the managers Hermes reports, or none when neither is installed.
                await { compose.onAllNodesWithTag("vault-add-login").fetchSemanticsNodes().isNotEmpty() }
                mark("V-01", "page_listed_from_demo", "passed", baseline.size)

                compose.onNodeWithTag("vault-add-login").performScrollTo().performClick()
                await { compose.onAllNodesWithTag("vault-field:site").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("vault-field:site").performTextInput(site)
                compose.onNodeWithTag("vault-field:identifier").performScrollTo().performTextInput(owner)
                compose.onNodeWithTag("vault-field:password").performScrollTo().performTextInput("qa-synthetic-only")
                compose.onNodeWithTag("vault-submit").performClick()
                await(30_000) {
                    delay(300)
                    created = items().singleOrNull { it.kind == VaultKind.Login && it.origin == site && it.identifier == owner }
                    created != null
                }
                val saved = checkNotNull(created)
                check(saved.identifierType == "username" && saved.backend == "local" && !saved.hasOtp)
                mark("V-01", "ui_add_landed_in_native_vault", "passed")
                await { compose.onAllNodesWithTag("vault-item:${saved.id}").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("vault-item:${saved.id}").performScrollTo().performClick()
                compose.onNodeWithText("Remove").performClick()
                compose.onNodeWithTag("vault-remove-confirm").performClick()
                await(30_000) { delay(300); items().none { it.id == saved.id } }
                created = null
                mark("V-01", "ui_remove_landed_in_native_vault", "passed")
            } finally {
                // Whatever this run made is removed, whether or not a check failed.
                created?.let { runCatching { api.remove(it.id) } }
                val leftovers = runCatching { items().filter { it.origin == site && it.identifier == owner } }.getOrDefault(emptyList())
                leftovers.forEach { runCatching { api.remove(it.id) } }
                mark("V-01", "demo_vault_cleaned", if (runCatching { items().none { it.origin == site && it.identifier == owner } }.getOrDefault(false)) "passed" else "needs_human")
            }
        }
    }

    /**
     * The real agent on the demo asks for a login with `browser_vault save_login` (it needs the demo's browser): the question must reach
     * this phone, the answer must land in the native vault, and the item is removed again. Run only with `-e beaconDemoVaultAgent 1`.
     */
    @Test(timeout = 900_000) fun theAgentsLoginQuestionReachesThePhoneAndItsAnswerLandsInTheNativeVault() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconDemoVaultAgent") == "1")
        signInDemoWhenAsked()
        val h = OwnerJourneyHarness(compose, "demovaultagent")
        h.checked {
            val home = client.nativeRpc("config.get", json("key" to "profile")).optString("home").replace('\\', '/').trimEnd('/')
            check(home.endsWith("/hermes-demo") || home.contains("/hermes-demo/")) { "demo_home_required" }
            val api = NativeVaultApi(client)
            val owner = "qa-agent-${runId.take(6)}"
            suspend fun items(): List<VaultItem> { current(); return withTimeout(15_000) { api.snapshot().items } }
            check(items().none { it.identifier == owner })
            try {
                compose.runOnIdle {
                    vm.tab("Chat")
                    vm.composer(androidx.compose.ui.text.input.TextFieldValue("Use your browser tool to open https://example.com , then call the browser_vault tool with action save_login for that page. Do nothing else."))
                    vm.send()
                }
                await(480_000) { compose.onAllNodesWithTag("vault-field:identifier").fetchSemanticsNodes().isNotEmpty() }
                mark("V-02", "agent_login_question_reached_phone", "passed")
                compose.onNodeWithTag("vault-field:identifier").performTextInput(owner)
                compose.onNodeWithTag("vault-field:password").performTextInput("qa-synthetic-only")
                compose.onNodeWithTag("vault-submit").performClick()
                await(120_000) { delay(500); items().any { it.identifier == owner && it.kind == VaultKind.Login } }
                mark("V-02", "answer_landed_in_native_vault", "passed")
            } finally {
                runCatching { items().filter { it.identifier == owner }.forEach { api.remove(it.id) } }
                mark("V-02", "agent_vault_cleaned", if (runCatching { items().none { it.identifier == owner } }.getOrDefault(false)) "passed" else "needs_human")
            }
        }
    }
}
