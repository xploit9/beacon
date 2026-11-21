package dev.hermes.android.agentphone

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.ui.vault.VaultHarness
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * The combined vault-card question (EMU-DESIGN §5.3, §6.3) in the real chat, on Hermes' synthetic native protocol: Hermes' own card
 * consent (surface vault-payment) worded by L9 as "Pay £12.40 at Kanto Foods with Visa ••42?", shown by 1.2.0's card as one question in
 * the phone's words. Needs `-e native_ui_probe 1`; with `-e shots 1` the screen is saved. Synthetic data only.
 */
class AgentApprovalCardQuestionShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()

    private fun question(theme: String, command: String, expected: String, name: String, title: String = "Confirm card payment") = VaultHarness(compose).run(chat = true, dark = theme == "dark") { vm, fixture ->
        val before = fixture.streamSubscriptions.get()
        compose.runOnIdle { vm.composer(TextFieldValue("Order dinner")); vm.send() }
        compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "prompt.submit" } && fixture.streamSubscriptions.get() > before }
        // Exactly what request_elicitation_consent sends for L9's _card_question (tools/approval_prompt.py), once/deny only.
        fixture.emitNativeServerRequest("approval", "agent-card-$name", json("request_id" to "agent-card-$name", "command" to command,
            "description" to "The agent wants to enter your saved card on its phone. The card number and security code never enter the conversation. Approve only if you intend to pay here.",
            "pattern_key" to "mcp_elicitation", "pattern_keys" to JSONArray().put("mcp_elicitation"), "surface" to "vault-payment",
            "choices" to JSONArray().put("once").put("deny"), "allow_session" to false, "allow_permanent" to false))
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("approval-line").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(title).assertIsDisplayed()
        val shown = compose.onAllNodesWithTag("approval-line").fetchSemanticsNodes().single().config[SemanticsProperties.Text].joinToString { it.text }
        assertEquals(expected, shown.filter { it !in "⁦⁧⁨⁩" })
        // Hermes sends no amount of its own, so the card doesn't say the amount a second time.
        assertTrue(compose.onAllNodesWithTag("approval-amount").fetchSemanticsNodes().isEmpty())
        if (InstrumentationRegistry.getArguments().getString("shots") == "1") {
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 3000)
            val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "agent-approval-shots").apply { mkdirs() }
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            File(dir, "$theme-40-card-question-$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    @Test fun payAtShopWithCardDark() = question("dark", "Pay £12.40 at Kanto Foods with Visa ••42?", "Pay £12.40 at Kanto Foods with Visa ••42?", "pay")
    @Test fun payAtShopWithCardLight() = question("light", "Pay £12.40 at Kanto Foods with Visa ••42?", "Pay £12.40 at Kanto Foods with Visa ••42?", "pay")
    @Test fun fillWithoutAnAmountDark() = question("dark", "Fill Visa ••42 in Kanto Foods?", "Fill Visa ••42 in Kanto Foods?", "fill",
        title = "The agent wants to fill your saved card.")
}
