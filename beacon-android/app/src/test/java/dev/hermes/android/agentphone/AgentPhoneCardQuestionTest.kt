package dev.hermes.android.agentphone

import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.ui.approvalFace
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The combined vault-card question (EMU-DESIGN §5.3, §6.3; approvals-v1 §5a): Hermes' own card consent (surface vault-payment), worded
 * by L9 as one question naming the shop, the amount and the card. The 1.2.0 card dialog reads it into its three parts, so the phone
 * asks it in its own language instead of showing L9's English sentence.
 */
class AgentPhoneCardQuestionTest {
    private val warning = "The agent wants to enter your saved card on its phone. The card number and security code never enter the conversation. Approve only if you intend to pay here."
    private fun consent(command: String, surface: String? = "vault-payment") =
        JSONObject().put("command", command).put("description", warning).put("title", "Confirm payment card fill?").apply { surface?.let { put("surface", it) } }

    @Test fun theCombinedQuestionIsReadIntoShopAmountAndCard() {
        val face = approvalFace(consent("Pay £12.40 at Kanto Foods with Visa ••42?"))
        assertTrue(face.payment && face.shop)
        assertEquals("£12.40", face.price)
        assertEquals("Kanto Foods", face.site)
        assertEquals("Visa ••42", face.card)
        assertEquals("Confirm card payment", face.title)
        // Hermes sends no amount of its own; the amount is the question's, so "Amount:" isn't shown a second time.
        assertEquals("", face.amount)
        assertEquals("Pay ⁨£12.40⁩ at ⁨Kanto Foods⁩ with ⁨Visa ••42⁩?", PhoneLine("Pay {0} at {1} with {2}?", listOf(face.price, face.site, face.card)).shown())
    }

    @Test fun aShopWhoseNameHoldsAtOrWithKeepsItsWholeName() {
        val face = approvalFace(consent("Pay 9.99 USD at Fish with Chips at Sea with Amex?"))
        assertEquals(listOf("9.99 USD", "Fish with Chips at Sea", "Amex"), listOf(face.price, face.site, face.card))
    }

    @Test fun withoutAnAmountItAsksToFillTheCardInTheShop() {
        val face = approvalFace(consent("Fill Visa in Kanto Foods?"))
        assertTrue(face.shop)
        assertEquals("" to "Visa", face.price to face.card)
        assertEquals("Kanto Foods", face.site)
    }

    @Test fun theBrowserFillStillReadsAsBefore() {
        val face = approvalFace(consent("Fill payment card 'Visa' on https://shop.example"))
        assertFalse(face.shop)
        assertEquals("Visa" to "shop.example", face.card to face.site)
    }

    @Test fun onlyHermesOwnMarkerMakesItAPayment() {
        // The same words from the agent or an MCP server, without Hermes' vault-payment surface, never borrow the card look.
        val face = approvalFace(consent("Pay £12.40 at Kanto Foods with Visa?", surface = null))
        assertFalse(face.payment || face.shop)
    }
}
