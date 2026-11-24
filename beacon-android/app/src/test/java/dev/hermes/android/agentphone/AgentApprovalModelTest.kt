package dev.hermes.android.agentphone

import dev.hermes.android.phone.PhoneLine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The records of approvals-v1 rev 3 §4 and the words of its table, without Android. */
class AgentApprovalModelTest {
    private val now = 1_000_000L
    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    private fun record(kind: String, n: Int = 1, left: Any? = 100, vararg fields: Pair<String, Any?>) = JSONObject().apply {
        put("id", id(n)); put("kind", kind); put("created_at", 1_800_000_000); put("expires_at", 1_800_000_120)
        left?.let { put("expires_in_seconds", it) }
        fields.forEach { (key, value) -> put(key, value) }
    }
    private fun body(vararg records: JSONObject) = JSONObject().put("approvals", JSONArray(records.toList()))
    private fun parse(vararg records: JSONObject) = parseAgentApprovals(body(*records), now)

    @Test fun everyKindIsReadWithItsLabelsAndTheDeadlineComesFromTheSecondsLeft() {
        val records = parse(
            record("agent_phone_action", 1, 100, "risk" to "payment", "action" to "tap", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40", "purpose" to "dinner you asked for"),
            record("vault_new_login", 2, 90, "app" to "Kanto Foods", "label" to "Kanto Foods", "identifier" to "agent${'@'}example.com"),
            record("vault_save", 3, 500, "label" to "Amazon", "identifier" to "k${'@'}example.com", "line" to "Save the new Amazon login?"),
            record("vault_update", 4, 500, "label" to "Amazon", "line" to "Replace the saved Amazon login?"),
            record("vault_code", 5, 60, "app" to "Amazon", "label" to "Amazon", "line" to "Enter the code for Amazon."),
            record("needs_you", 6, 80_000, "app" to "Amazon", "line" to "The Amazon login didn't work."),
        )
        assertEquals(ApprovalKind.entries.toList(), records.map { it.kind })
        val pay = records[0]
        assertEquals(ApprovalRisk.Payment, pay.risk)
        assertEquals(listOf("tap", "Kanto Foods", "Place order", "£12.40", "dinner you asked for"), listOf(pay.action, pay.app, pay.label, pay.amount, pay.purpose))
        assertEquals(now + 100_000, pay.deadline)
        assertTrue(pay.payment)
        assertEquals(now + 60_000, records[4].deadline)
    }

    @Test fun decisionsComeFirstThenTheNeedsYouNoticesInTheServersOrder() {
        val records = parse(record("needs_you", 1, 50, "line" to "x"), record("vault_save", 2), record("needs_you", 3, 50, "line" to "y"),
            record("agent_phone_action", 4, 100, "risk" to "send"))
        assertEquals(listOf(id(2), id(4), id(1), id(3)), records.map { it.id })
    }

    @Test fun aRecordTheAppCantReadWholeIsLeftOutSoItEndsOnItsOwn() {
        val records = parse(
            record("agent_phone_action", 1, 100), // no risk
            record("agent_phone_action", 2, 100, "risk" to "bribe"),
            record("vault_save", 3, 100, "risk" to "bribe"), // a risk this app doesn't know, on any kind
            record("future_kind", 4),
            record("vault_save", 5, 0),
            record("vault_save", 6, null),
            record("vault_save", 7, 100, "amount" to "1".repeat(41)),
            record("vault_save", 8, 100, "line" to "x".repeat(201)),
            JSONObject().put("id", "NOT-HEX").put("kind", "vault_save").put("expires_in_seconds", 50),
            record("vault_save", 9, 100, "label" to "Amazon"),
        )
        assertEquals(listOf(id(9)), records.map { it.id })
        assertTrue(parseAgentApprovals(JSONObject(), now).isEmpty())
    }

    @Test fun labelsAreOnePlainLineWhateverTheScreenSaid() {
        val record = parse(record("agent_phone_action", 1, 100, "risk" to "send", "label" to "Send\n\tnow\u0000  please")).single()
        assertEquals("Send now please", record.label)
    }

    @Test fun eachRiskHasTheContractsTitleAndBody() {
        fun face(risk: String, vararg fields: Pair<String, Any?>) = approvalFace(parse(record("agent_phone_action", 1, 100, "risk" to risk, *fields)).single())
        assertEquals("{name} wants to pay", face("payment", "pay" to true).title)
        // A checkout tap whose control doesn't pay (a Sign in, Continue) says where it taps.
        assertEquals("{name} wants to tap on a payment screen", face("payment").title)
        // D2c: a tap Beacon can't read says so; it is no payment claim. UX P1-8: verb first, saying what Allow lets it do.
        assertEquals("{name} wants to tap an unreadable button", face("unlabeled").title)
        assertFalse(face("unlabeled").money)  // no amount: no payment card
        val checkout = face("unlabeled", "amount" to "€8.90")
        assertEquals("{name} wants to tap an unreadable button", checkout.title)
        assertEquals("€8.90", checkout.amount)  // the amount line stays on a checkout
        assertEquals("{name} wants to send", face("send").title)
        assertEquals("{name} wants to delete", face("delete").title)
        assertEquals("{name} wants to install an app", face("install").title)
        assertEquals("{name} wants to change an account", face("account").title)
        assertEquals("{name} wants to use its phone", face("other").title)

        val pay = face("payment", "app" to "Uber Eats", "label" to "Place order", "amount" to "£12.40", "purpose" to "dinner", "pay" to true)
        assertEquals("£12.40", pay.amount)
        assertEquals(PhoneLine("{0}: {1}", listOf("Uber Eats", "Place order")), pay.subject)
        assertEquals("dinner", pay.purpose)
        assertTrue(pay.money)
        // P2-3: a paying control's yes is "Pay {0}" with the record's own amount, its no stays Deny.
        assertEquals(ApprovalButtons.PayAmount, pay.buttons)
        assertEquals("Pay {0}" to "Deny", pay.buttons.yes to pay.buttons.no)
        assertEquals(ApprovalGlyph.Payment, pay.glyph)

        // An unlabeled tap names only the app; the amount when the screen showed one, which the plugin sends only on a checkout.
        val unlabeled = face("unlabeled", "app" to "Kanto Foods", "label" to "ignored", "amount" to "€8")
        assertEquals(PhoneLine("{0}", listOf("Kanto Foods")), unlabeled.subject)
        assertEquals("€8", unlabeled.amount)
        assertTrue(unlabeled.money)
        assertEquals(ApprovalGlyph.Payment, unlabeled.glyph)
        // Off a checkout (no amount): a plain tap question, nothing about money (D2c, the owner's product-page tap).
        val product = face("unlabeled", "app" to "Kanto Foods", "purpose" to "Open the Golden Eggs product page")
        assertFalse(product.money)
        assertEquals(ApprovalGlyph.Tap, product.glyph)
        assertEquals(ApprovalButtons.AllowDeny, product.buttons)
        assertEquals("Nothing was done.", answeredLine(parse(record("agent_phone_action", 1, 100, "risk" to "unlabeled")).single(), false))

        val send = face("send", "app" to "Messages", "label" to "Send")
        assertFalse(send.money)
        assertEquals("", send.amount)
        assertEquals(ApprovalGlyph.Send, send.glyph)
        assertEquals(ApprovalButtons.AllowDeny, send.buttons)
    }

    @Test fun onlyAControlThatPaysSaysPayAndEveryOtherCheckoutTapAsksAllow() {
        fun face(vararg fields: Pair<String, Any?>) = approvalFace(parse(record("agent_phone_action", 1, 100, *fields)).single())
        // REVIEW-FIX-UI-121 P2: the plugin's pay is true only when the tapped control's own words pay or carry an amount.
        val order = face("risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40", "pay" to true)
        assertEquals(ApprovalButtons.PayAmount, order.buttons)
        assertEquals("{name} wants to pay", order.title)
        // With no amount on the screen the yes is a plain "Pay", never "Pay ".
        assertEquals(ApprovalButtons.Pay, face("risk" to "payment", "app" to "Kanto Foods", "label" to "Pay now", "pay" to true).buttons)
        assertEquals("Pay", ApprovalButtons.Pay.yes)
        // A Sign in on a checkout, an unlabeled tap, a record from a plugin without pay, or a pay that isn't true: Allow / Deny. The
        // amount line and the attention colour stay, and the title says where it taps, not that it pays.
        val signIn = face("risk" to "payment", "app" to "Kanto Foods", "label" to "Sign in", "amount" to "£12.40", "pay" to false)
        assertEquals(PhoneLine("{0}: {1}", listOf("Kanto Foods", "Sign in")), signIn.subject)
        for (tap in listOf(signIn, face("risk" to "payment", "app" to "Kanto Foods", "label" to "Sign in", "amount" to "£12.40"),
                face("risk" to "payment", "app" to "Kanto Foods", "label" to "Place order", "amount" to "£12.40", "pay" to "true"))) {
            assertEquals(ApprovalButtons.AllowDeny, tap.buttons)
            assertEquals("{name} wants to tap on a payment screen", tap.title)
            assertTrue(tap.money)
            assertTrue(tap.amount.isNotBlank())
        }
        // A tap Beacon can't read on a checkout: Allow / Deny with the amount, and a title that says what Allow lets it do (D2c, UX P1-8).
        val unread = face("risk" to "unlabeled", "app" to "Kanto Foods", "amount" to "€8.90")
        assertEquals(ApprovalButtons.AllowDeny, unread.buttons)
        assertEquals("{name} wants to tap an unreadable button", unread.title)
        assertTrue(unread.money && unread.amount.isNotBlank())
        // A saved login used on a payment screen is about the login: Allow / Deny.
        assertEquals(ApprovalButtons.AllowDeny, face("risk" to "payment", "action" to "fill", "pin" to "Use the Kanto Foods login in Kanto Foods?").buttons)
    }

    @Test fun aFirstPinOnAFillAsksToUseTheSavedLoginInItsOwnWords() {
        val face = approvalFace(parse(record("agent_phone_action", 1, 100, "risk" to "account", "action" to "fill", "app" to "Kanto Foods",
            "pin" to "Use the Kanto Foods login in Kanto Foods?")).single())
        assertEquals("{name} wants to use a saved login", face.title)
        assertEquals(PhoneLine("Use the {0} login in {1}?", listOf("Kanto Foods", "Kanto Foods")), face.question)
        assertNull(face.subject)
        assertEquals(ApprovalGlyph.Login, face.glyph)
    }

    @Test fun vaultRecordsAndNeedsYouSayTheirOwnSentence() {
        val newLogin = approvalFace(parse(record("vault_new_login", 1, 100, "app" to "Kanto Foods", "label" to "Kanto Foods", "identifier" to "agent${'@'}example.com")).single())
        assertEquals("{name} wants to make a login", newLogin.title)
        assertEquals(PhoneLine("Create a login for {0} with {1}? It's saved to your vault.", listOf("Kanto Foods", "agent${'@'}example.com")), newLogin.question)
        assertEquals("Create" to "Not now", newLogin.buttons.yes to newLogin.buttons.no)
        val reset = approvalFace(parse(record("vault_new_login", 1, 100, "label" to "Amazon", "line" to "Change the Amazon password?")).single())
        assertEquals("{name} wants to change the password", reset.title)
        assertEquals(PhoneLine("Change the {0} password?", listOf("Amazon")), reset.question)
        assertEquals("Change" to "Not now", reset.buttons.yes to reset.buttons.no)

        val save = approvalFace(parse(record("vault_save", 1, 100, "label" to "Amazon", "identifier" to "k${'@'}example.com", "line" to "Save the new Amazon login?")).single())
        assertEquals("{name} found a login", save.title)
        assertEquals(PhoneLine("Save the new {0} login?", listOf("Amazon")), save.question)
        assertEquals("k${'@'}example.com", save.detail)
        // P2-3: the answers say what they do to the login.
        assertEquals("Save" to "Not now", save.buttons.yes to save.buttons.no)
        val update = approvalFace(parse(record("vault_update", 1, 100, "label" to "Amazon")).single())
        assertEquals(PhoneLine("Replace the saved {0} login?", listOf("Amazon")), update.question)
        assertEquals("Replace" to "Keep old", update.buttons.yes to update.buttons.no)

        val code = approvalFace(parse(record("vault_code", 1, 100, "app" to "Amazon", "label" to "Amazon", "line" to "Enter the code for Amazon.")).single())
        assertEquals("{name} needs a code", code.title)
        assertEquals(ApprovalButtons.EnterDeny, code.buttons)
        assertTrue(code.codeField)
        assertEquals(PhoneLine("Enter the code for {0}.", listOf("Amazon")), code.question)
        assertEquals(null, code.note)  // the title and the line say enough (Root, 17 Nov: no rule text on the card)

        val needsYou = approvalFace(parse(record("needs_you", 1, 100, "app" to "Amazon", "line" to "The Amazon login didn't work.")).single())
        assertEquals("{name} needs you", needsYou.title)
        assertEquals(ApprovalButtons.OpenDismiss, needsYou.buttons)
        assertEquals(PhoneLine("The {0} login didn't work.", listOf("Amazon")), needsYou.question)
        assertEquals("Open the phone" to "Dismiss", needsYou.buttons.yes to needsYou.buttons.no)
    }

    @Test fun everySentenceThePluginWritesIsATemplateWithTheOwnersWordsInItsHoles() {
        // The exact f-strings of L9 agent_phone_vault.py and L3 agent_phone_tools.py / agent_phone_approvals.py (emu-join, 17 Nov).
        val written = mapOf(
            "Enter the code for Amazon." to listOf("Amazon"),
            "Sign in to shop.example.com on the phone." to listOf("shop.example.com"),
            "The Kanto Foods login didn't work." to listOf("Kanto Foods"),
            "Replace the saved Kanto Foods login?" to listOf("Kanto Foods"),
            "Save the new Kanto Foods login?" to listOf("Kanto Foods"),
            "Change the Kanto Foods password?" to listOf("Kanto Foods"),
            "Create a login for Kanto Foods with agent${'@'}example.com? It's saved to your vault." to listOf("Kanto Foods", "agent${'@'}example.com"),
            "Use the Kanto Foods login in Kanto Foods?" to listOf("Kanto Foods", "Kanto Foods"),
            "Saved: Kanto Foods · k${'@'}example.com" to listOf("Kanto Foods", "k${'@'}example.com"),
            "Saved: Kanto Foods" to listOf("Kanto Foods"),
            "Save the login to Beacon" to emptyList(),
            "Couldn't save that login: the app didn't give its username." to emptyList(),
        )
        for ((text, args) in written) {
            val line = serverLine(text)
            assertTrue("$text is not a known sentence", line.template in AgentPhoneServerLines)
            assertEquals(text, args, line.args)
            assertEquals(text, line.text())
        }
        // A sentence this app doesn't know is shown as written, whole, its braces left alone.
        assertEquals(PhoneLine("{0}", listOf("Something new {0}")), serverLine("Something new {0}"))
    }

    @Test fun aCodeIsFourToTenLettersOrDigitsAndDigitsFromOtherScriptsCount() {
        assertEquals("4567", codeInput("٤٥٦٧"))
        assertEquals("123456", codeInput("123 456"))
        assertEquals("12AB34", codeInput("12-ab-34".uppercase()))
        assertEquals("0123456789", codeInput("0123456789999"))
        assertEquals("123", codeInput("１２３"))
        assertEquals("", codeInput("é😀"))
        assertTrue(codeReady("1234"))
        assertTrue(codeReady("ABCdef1234"))
        assertFalse(codeReady("123"))
        assertFalse(codeReady("12345678901"))
        assertFalse(codeReady("12 34"))
    }

    @Test fun eachAnswerFromTheRouteHasItsOwnOutcome() {
        fun result(status: Int, detail: String? = null) = decisionResult(status, detail?.let { JSONObject().put("detail", it) })
        assertEquals(DecisionResult.Sent, result(200))
        assertEquals(DecisionResult.Ended, result(404, "Approval not found"))
        assertEquals(DecisionResult.Ended, result(409, "expired"))
        assertEquals(DecisionResult.Elsewhere, result(409, "settled"))
        assertEquals(DecisionResult.Invalid, result(400, "Invalid decision"))
        assertEquals(DecisionResult.NotLinked, result(403))
        assertEquals(DecisionResult.NotLinked, result(401))
        assertEquals(DecisionResult.Retry, result(0))
        assertEquals(DecisionResult.Retry, result(503, "Busy, try again."))
        assertNull(decisionLine(DecisionResult.Sent, code = false))
        // P3-1: the same states say the chat cards' own words.
        assertEquals(dev.hermes.android.PROMPT_ENDED_NOTICE, decisionLine(DecisionResult.Ended, code = false))
        assertEquals("This request has ended.", decisionLine(DecisionResult.Ended, code = false))
        assertEquals(dev.hermes.android.data.APP_CONNECTION_ERROR, decisionLine(DecisionResult.Retry, code = false))
        assertEquals("Check the code and try again.", decisionLine(DecisionResult.Invalid, code = true))
        assertEquals("Answered on another device.", decisionLine(DecisionResult.Elsewhere, code = false))
    }

    @Test fun aNoToAPaymentSaysOnlyNothingWasPaid() {
        val pay = parse(record("agent_phone_action", 1, 100, "risk" to "payment")).single()
        val send = parse(record("agent_phone_action", 2, 100, "risk" to "send")).single()
        val save = parse(record("vault_save", 3, 100)).single()
        assertEquals("Nothing was paid.", answeredLine(pay, approved = false))
        assertEquals("Nothing was done.", answeredLine(send, approved = false))
        assertEquals("Allowed", answeredLine(pay, approved = true))
        assertEquals("Not saved", answeredLine(save, approved = false))
    }

    @Test fun theWordsListHoldsEveryTitleAndButton() {
        for (risk in ApprovalRisk.entries) {
            val title = approvalFace(AgentApproval(id(1), ApprovalKind.Action, now, risk)).title
            assertTrue(title, title in AgentApprovalWords)
        }
        for (kind in ApprovalKind.entries) assertTrue(approvalFace(AgentApproval(id(1), kind, now, ApprovalRisk.Other)).title in AgentApprovalWords)
        for (buttons in ApprovalButtons.entries) assertTrue(buttons.yes in AgentApprovalWords && buttons.no in AgentApprovalWords)
    }
}
