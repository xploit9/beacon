package dev.hermes.android.ui.vault

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * Frames of the Vault screens in light and dark, saved under the app's external files dir, vault-shots/<theme>/ for the coordinator to pull. Synthetic data only.
 * The secure input sheets set FLAG_SECURE, so Android hides them from a screenshot by design; the page, the approval card and the states show.
 */
class NativeVaultShotsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val mail = "pat" + 64.toChar() + "example.test"
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun shown(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    private fun capture(theme: String, name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 3000)
        val directory = File(app.getExternalFilesDir(null) ?: app.filesDir, "vault-shots/$theme").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun seed(fixture: NativeExperienceFixture) {
        fixture.seedVaultManager("default", "onepassword", "1Password", enabled = true, unlocked = true)
        fixture.seedVaultManager("default", "bitwarden", "Bitwarden", enabled = true, unlocked = false)
        fixture.seedVaultLogin("default", "GitHub", "https://github.com", mail, otp = true)
        fixture.seedVaultLogin("default", "Shop", "https://shop.example", "pat", backend = "onepassword")
        fixture.seedVaultLogin("default", "Mail", "https://mail.example", mail)
    }

    private fun openVault(vm: BeaconViewModel) {
        compose.runOnIdle { vm.navigate("Vault") }
        await { shown("vault-page") && shown("vault-add-login") }
    }

    // One test per frame set: a Compose rule sets its content once per test.
    private fun page(theme: String) = VaultHarness(compose).run(dark = theme == "dark", configure = {
        profileDisplayNames = mapOf("default" to "Personal profile", "coder" to "Code profile"); seed(this)
    }) { vm, fixture ->
        compose.runOnIdle { vm.load("navigation-profiles", "/api/profiles", force = true) }
        await { vm.data.value["navigation-profiles"]?.rows("profiles")?.any { it.string("display_name") == "Personal profile" } == true }
        openVault(vm)
        await { shown("vault-source:onepassword") }
        capture(theme, "vault-list")
        val list = compose.onAllNodes(hasScrollToNodeAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }
        compose.onNode(SemanticsMatcher("Settings detail list") { it.id == list.id }).performScrollToNode(hasTestTag("vault-source:bitwarden"))
        capture(theme, "vault-managers")
        compose.onNodeWithTag("vault-item:${fixture.vaultItems.getValue("default").first().getString("id")}").performScrollTo().performClick()
        capture(theme, "vault-item-details")
    }

    private fun unsupported(theme: String) = VaultHarness(compose).run(dark = theme == "dark", configure = { vaultAvailable = false }) { vm, _ ->
        compose.runOnIdle { vm.navigate("Vault") }
        await { compose.onAllNodesWithText("This Hermes doesn't have a Vault. Update Hermes to use it.").fetchSemanticsNodes().isNotEmpty() }
        capture(theme, "vault-unsupported")
    }

    private fun approval(theme: String) = VaultHarness(compose).run(chat = true, dark = theme == "dark") { vm, fixture ->
        val before = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Buy it")); vm.send() }
        await { fixture.requests.any { it.string("method") == "prompt.submit" } && fixture.streamSubscriptions.get() > before }
        fixture.emitCardFillApproval("shot-card", "Visa", "https://shop.example", amount = "12.50 USD")
        await { shown("approval-line") }
        capture(theme, "approval-card-fill")
    }

    @Test fun pageDark() = page("dark")
    @Test fun pageLight() = page("light")
    @Test fun unsupportedDark() = unsupported("dark")
    @Test fun unsupportedLight() = unsupported("light")
    @Test fun approvalDark() = approval("dark")
    @Test fun approvalLight() = approval("light")
}
