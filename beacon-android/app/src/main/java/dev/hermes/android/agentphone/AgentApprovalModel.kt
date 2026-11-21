package dev.hermes.android.agentphone

import dev.hermes.android.PROMPT_ENDED_NOTICE
import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.phone.PhoneLine
import org.json.JSONArray
import org.json.JSONObject

/*
 * The questions the agent's own phone asks its owner (EMU-DESIGN rev 4 §5.4, §8; approvals-v1 rev 3 §2, §4). A record is decide-only:
 * nothing runs on this phone, the owner says yes or no and the plugin acts on the agent phone. It holds labels only (app, button,
 * amount, purpose, site, identifier), never a secret or an image, and every linked device of the owner sees it whatever Phone access
 * says. This file is the part without Android: reading the records, what each one says, and what an answer came back as.
 */

/** The six kinds of approvals-v1 §2. Every kind but [NeedsYou] is a decision the agent waits on (at most 120 s; saves 10 min). */
internal enum class ApprovalKind(val wire: String) {
    Action("agent_phone_action"), NewLogin("vault_new_login"), Save("vault_save"), Update("vault_update"), Code("vault_code"), NeedsYou("needs_you");

    companion object { fun of(wire: String?): ApprovalKind? = entries.firstOrNull { it.wire == wire } }
}

/** Why an `agent_phone_action` asks (approvals-v1 §4 `risk`). */
internal enum class ApprovalRisk(val wire: String) {
    Payment("payment"), Unlabeled("unlabeled"), Send("send"), Delete("delete"), Install("install"), Account("account"), Other("other");

    companion object { fun of(wire: String?): ApprovalRisk? = entries.firstOrNull { it.wire == wire } }
}

/**
 * One open record as the app keeps it. Text fields are the server's labels, cleaned to one plain line; an absent field is "".
 * [deadline] is on the elapsed clock (SystemClock.elapsedRealtime), from the record's own `expires_in_seconds`, so a phone clock that is
 * off never ends a question early or keeps it late.
 */
internal data class AgentApproval(
    val id: String,
    val kind: ApprovalKind,
    val deadline: Long,
    val risk: ApprovalRisk? = null,
    val action: String = "",
    val app: String = "",
    val label: String = "",
    val amount: String = "",
    val purpose: String = "",
    val identifier: String = "",
    val pin: String = "",
    val line: String = "",
    val note: String = "",
    /** The code of [line] (contract revision 5 §11.1) and the site it names; empty from an older plugin. */
    val reason: String = "",
    val site: String = "",
    /** The plugin's word that the tapped control itself pays (its words are a pay word or carry an amount); false when absent. */
    val pay: Boolean = false,
) {
    /** A question about money: the amount is the first thing the owner reads, and a no stands for a minute (approvals-v1 §1). */
    val payment: Boolean get() = kind == ApprovalKind.Action && (risk == ApprovalRisk.Payment || risk == ApprovalRisk.Unlabeled)
}

internal val APPROVAL_ID = Regex("[0-9a-f]{32}")

/** approvals-v1 §4: each field's longest length. A longer one means a server this app does not understand, so the record is not shown. */
private val FIELD_LIMITS = mapOf(
    "action" to 20, "app" to 80, "label" to 120, "amount" to 40, "purpose" to 120, "identifier" to 120, "pin" to 160, "line" to 200, "note" to 200,
    "reason" to 40, "site" to 120,
)

private val spaces = Regex("\\s+")

/** One plain line: control characters out, runs of space folded (the server already does this; a screen label is never trusted). */
internal fun plainLabel(value: String): String = value.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(spaces, " ").trim()

private fun JSONObject.field(name: String): String? {
    val raw = opt(name) as? String ?: return ""
    val text = plainLabel(raw)
    return if (text.length > (FIELD_LIMITS[name] ?: 200)) null else text
}

private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/**
 * `GET /mobile/agent-phone/approvals` → the records to ask about, decisions first (oldest first), then the Needs-you notices
 * (approvals-v1 §1). A record this app can't read whole (an unknown kind or risk, a bad id, no time left, a field over its limit) is
 * left out: nothing runs without the owner's yes, so leaving a question out only lets it end on its own.
 */
internal fun parseAgentApprovals(body: JSONObject, nowElapsed: Long): List<AgentApproval> {
    val records = body.optJSONArray("approvals").objects().mapNotNull { record ->
        val id = (record.opt("id") as? String)?.takeIf(APPROVAL_ID::matches) ?: return@mapNotNull null
        val kind = ApprovalKind.of(record.opt("kind") as? String) ?: return@mapNotNull null
        val left = (record.opt("expires_in_seconds") as? Number)?.toLong()?.takeIf { it > 0 } ?: return@mapNotNull null
        val riskWire = record.opt("risk") as? String
        val risk = ApprovalRisk.of(riskWire)
        if (kind == ApprovalKind.Action && risk == null || riskWire != null && risk == null) return@mapNotNull null
        val fields = FIELD_LIMITS.keys.associateWith { record.field(it) ?: return@mapNotNull null }
        AgentApproval(
            id, kind, nowElapsed + left.coerceAtMost(MAX_WAIT_SECONDS) * 1000, risk, fields.getValue("action"), fields.getValue("app"),
            fields.getValue("label"), fields.getValue("amount"), fields.getValue("purpose"), fields.getValue("identifier"),
            fields.getValue("pin"), fields.getValue("line"), fields.getValue("note"), fields.getValue("reason"), fields.getValue("site"),
            pay = record.opt("pay") == true,
        )
    }
    return records.filter { it.kind != ApprovalKind.NeedsYou } + records.filter { it.kind == ApprovalKind.NeedsYou }
}

/** A Needs-you notice lives 24 h (approvals-v1 §2); nothing waits longer, so a larger number is read as that. */
private const val MAX_WAIT_SECONDS = 24 * 3600L

// -- the server's sentences, in the app's words ------------------------------------------------------------------------------------

/**
 * The sentences the plugin writes into `line`, `pin`, `note` and activity labels, by their English words (L9 agent_phone_vault.py,
 * L3 agent_phone_tools.py / agent_phone_approvals.py in emu-join, 17 Nov). {0} and {1} are the owner's own words (a site, an app,
 * an identifier); everything else is translated. A sentence not listed here is shown as the server wrote it.
 */
private const val CREATE_LOGIN = "Create a login for {0} with {1}? It's saved to your vault."
private const val CHANGE_PASSWORD = "Change the {0} password?"
private const val CHANGE_PASSWORD_TITLE = "{name} wants to change the password"

internal val AgentPhoneServerLines: List<String> = listOf(
    "Enter the code for {0}.",
    "Sign in to {0} on the phone.",
    "The {0} login didn't work.",
    "Replace the saved {0} login?",
    "Save the new {0} login?",
    CHANGE_PASSWORD,
    CREATE_LOGIN,
    "Use the {0} login in {1}?",
    // Activity labels the plugin writes itself (save rows, the save prompt it tapped). Android's debugging question reads as its own row.
    "Saved: {0} · {1}",
    "Saved: {0}",
    AgentPhoneLines.templates.getValue("vault.saved_no_username"),
    "Save the login to Beacon",
)

// Each hole matches one or more characters, the first hole as few as it can; the braces are escaped (Android's ICU regex refuses a bare "}").
private val holePattern = Regex("""\{(\d)\}""")

private val serverPatterns: List<Pair<String, Regex>> by lazy {
    AgentPhoneServerLines.map { template ->
        val parts = holePattern.split(template).map(Regex::escape)
        val holes = holePattern.findAll(template).toList()
        template to Regex(parts.foldIndexed("") { index, acc, part -> acc + part + (if (index < holes.size) "(.+?)" else "") })
    }
}

private val namedHole = Regex("""\{([a-z]+)\}""")

/**
 * A record's sentence by its code (revision 5 §11.1, approvals-v1's optional `reason`): the code's template, each named hole but
 * `{name}` filled from the record's own label, identifier or site, in the positional form the card translates ("Enter the code for {0}.").
 * Null for a code the app doesn't know or a name the record lacks: the record's `line` decides then.
 */
internal fun codedLine(reason: String, names: Map<String, String>): PhoneLine? {
    val template = AgentPhoneLines.templates[reason] ?: return null
    val keys = namedHole.findAll(template).map { it.groupValues[1] }.filter { it != "name" }.toList()
    if (keys.any { names[it].isNullOrBlank() }) return null
    var positional = template
    keys.forEachIndexed { index, key -> positional = positional.replaceFirst("{$key}", "{$index}") }
    return PhoneLine(positional, keys.map { names.getValue(it) })
}

/** The record's own sentence: by its code first, else by its English words ([serverLine]); null when it has none. */
internal fun recordLine(record: AgentApproval): PhoneLine? =
    codedLine(record.reason, mapOf("label" to record.label, "identifier" to record.identifier, "site" to record.site))
        ?: record.line.takeIf(String::isNotBlank)?.let(::serverLine)

/** [raw] as a line the app can translate: a known sentence keeps its holes; anything else is shown as written, its holes left alone. */
internal fun serverLine(raw: String): PhoneLine {
    val text = plainLabel(raw)
    for ((template, pattern) in serverPatterns) {
        val match = pattern.matchEntire(text) ?: continue
        return PhoneLine(template, match.groupValues.drop(1))
    }
    return PhoneLine("{0}", listOf(text))
}

// -- what the card says ------------------------------------------------------------------------------------------------------------

/**
 * The buttons a record offers (approvals-v1 §4): Allow / Deny, Enter / Deny for a code ("Enter code": the catalog's "Enter" is the key),
 * Open the phone / Dismiss for Needs you, and verbs that answer the question itself (AGENTUX-121 P2-3): Save, Replace, Create or Change
 * a login, and Pay with the record's own amount in {0}.
 */
internal enum class ApprovalButtons(val yes: String, val no: String) {
    AllowDeny("Allow", "Deny"), EnterDeny("Enter code", "Deny"), OpenDismiss("Open the phone", "Dismiss"),
    SaveNotNow("Save", "Not now"), ReplaceKeepOld("Replace", "Keep old"), CreateNotNow("Create", "Not now"), ChangeNotNow("Change", "Not now"),
    Pay("Pay", "Deny"), PayAmount("Pay {0}", "Deny"),
}

/** The tile on the card: what the question is about, at a glance. */
internal enum class ApprovalGlyph { Payment, Tap, Send, Delete, Install, Account, Phone, Login, Code, NeedsYou }

/**
 * What the card shows for one record, in English templates the card translates ({name} is the assistant's name). [amount] is drawn
 * large and never cut; [subject] is where the input lands ("Uber Eats: Place order"); [question] is the record's own sentence (a pin,
 * a vault line, a Needs-you line); [detail] is a login's identifier; [purpose] is the agent's own words, shown as "For: …".
 */
internal data class ApprovalFace(
    val title: String,
    val glyph: ApprovalGlyph,
    val buttons: ApprovalButtons,
    val amount: String = "",
    val subject: PhoneLine? = null,
    val question: PhoneLine? = null,
    val detail: String = "",
    val purpose: String = "",
    val note: PhoneLine? = null,
    /** A code record: the card shows a code field and Enter sends it. */
    val codeField: Boolean = false,
    /** Payments: the amount leads and the card wears the attention colour. */
    val money: Boolean = false,
)

/** "<app>: <label>", "<app>" or "<label>", whichever the record has; null when it has neither. */
private fun where(app: String, label: String): PhoneLine? = when {
    app.isNotBlank() && label.isNotBlank() -> PhoneLine("{0}: {1}", listOf(app, label))
    app.isNotBlank() -> PhoneLine("{0}", listOf(app))
    label.isNotBlank() -> PhoneLine("{0}", listOf(label))
    else -> null
}

private fun riskTitle(risk: ApprovalRisk?, pay: Boolean): String = when (risk) {
    ApprovalRisk.Payment -> if (pay) "{name} wants to pay" else "{name} wants to tap on a payment screen"
    ApprovalRisk.Unlabeled -> "{name} wants to tap on a payment screen"
    ApprovalRisk.Send -> "{name} wants to send"
    ApprovalRisk.Delete -> "{name} wants to delete"
    ApprovalRisk.Install -> "{name} wants to install an app"
    ApprovalRisk.Account -> "{name} wants to change an account"
    ApprovalRisk.Other, null -> "{name} wants to use its phone"
}

private fun riskGlyph(risk: ApprovalRisk?, action: String): ApprovalGlyph = when (risk) {
    ApprovalRisk.Payment, ApprovalRisk.Unlabeled -> ApprovalGlyph.Payment
    ApprovalRisk.Send -> ApprovalGlyph.Send
    ApprovalRisk.Delete -> ApprovalGlyph.Delete
    ApprovalRisk.Install -> ApprovalGlyph.Install
    ApprovalRisk.Account -> ApprovalGlyph.Account
    ApprovalRisk.Other, null -> if (action == "type") ApprovalGlyph.Phone else ApprovalGlyph.Tap
}

/**
 * The contract's words for [record] (approvals-v1 §4 "Words L6 shows"). Titles name the assistant as {name}, as the other agent-phone
 * screens do: the card puts the owner's chosen name in after translating.
 */
internal fun approvalFace(record: AgentApproval): ApprovalFace = when (record.kind) {
    ApprovalKind.Action -> {
        val fill = record.action == "fill"
        val pin = record.pin.takeIf(String::isNotBlank)?.let(::serverLine)
        ApprovalFace(
            title = if (pin != null && fill) "{name} wants to use a saved login" else riskTitle(record.risk, record.pay),
            glyph = if (pin != null && fill) ApprovalGlyph.Login else riskGlyph(record.risk, record.action),
            // Pay only when the control itself pays (REVIEW-FIX-UI-121): a checkout's Sign in, Continue or unlabeled tap is Allow / Deny.
            buttons = when {
                pin != null && fill || !record.payment || !record.pay -> ApprovalButtons.AllowDeny
                record.amount.isNotBlank() -> ApprovalButtons.PayAmount
                else -> ApprovalButtons.Pay
            },
            amount = record.amount,
            // An unlabeled tap names only the app: there is no control text to show (§4 "risk unlabeled").
            subject = if (pin != null && fill) null else where(record.app, if (record.risk == ApprovalRisk.Unlabeled) "" else record.label),
            question = pin,
            purpose = record.purpose,
            money = record.payment,
        )
    }
    // The sentence names the site, so the app isn't said again above it (a code card likewise).
    ApprovalKind.NewLogin -> {
        // "Change the Amazon password?" for a reset; otherwise the contract's own sentence from the label and identifier.
        val question = recordLine(record) ?: PhoneLine(CREATE_LOGIN, listOf(record.label, record.identifier))
        val changingPassword = question.template == CHANGE_PASSWORD
        ApprovalFace(
            title = if (changingPassword) CHANGE_PASSWORD_TITLE else "{name} wants to make a login",
            glyph = ApprovalGlyph.Login, question = question,
            buttons = if (changingPassword) ApprovalButtons.ChangeNotNow else ApprovalButtons.CreateNotNow,
        )
    }
    ApprovalKind.Save, ApprovalKind.Update -> ApprovalFace(
        title = "{name} found a login", glyph = ApprovalGlyph.Login,
        buttons = if (record.kind == ApprovalKind.Update) ApprovalButtons.ReplaceKeepOld else ApprovalButtons.SaveNotNow,
        question = recordLine(record)
            ?: PhoneLine(if (record.kind == ApprovalKind.Update) "Replace the saved {0} login?" else "Save the new {0} login?", listOf(record.label.ifBlank { record.app })),
        detail = record.identifier,
    )
    ApprovalKind.Code -> ApprovalFace(
        title = "{name} needs a code", glyph = ApprovalGlyph.Code, buttons = ApprovalButtons.EnterDeny,
        question = recordLine(record) ?: PhoneLine("Enter the code for {0}.", listOf(record.label.ifBlank { record.app })),
        note = record.note.takeIf(String::isNotBlank)?.let(::serverLine),
        codeField = true,
    )
    ApprovalKind.NeedsYou -> ApprovalFace(
        title = "{name} needs you", glyph = ApprovalGlyph.NeedsYou, buttons = ApprovalButtons.OpenDismiss,
        question = recordLine(record) ?: where(record.app, record.label),
    )
}

// -- the code a vault_code record asks for -----------------------------------------------------------------------------------------

/** approvals-v1 §6: a code is 4-10 letters or digits; the server refuses anything else (400). */
internal val APPROVAL_CODE = Regex("[A-Za-z0-9]{4,10}")

/**
 * What the code field keeps of what the owner typed: ASCII letters and digits only, at most 10. A digit from another script (Arabic-
 * Indic, Persian, full-width) becomes its ASCII digit, so an Arabic keyboard's ٤٥٦٧ is the code 4567; spaces and dashes in a pasted
 * "123 456" are dropped.
 */
internal fun codeInput(typed: String): String = buildString {
    for (char in typed) {
        if (length >= 10) break
        when {
            char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' -> append(char)
            Character.isDigit(char) -> Character.digit(char, 10).takeIf { it >= 0 }?.let { append('0' + it) }
        }
    }
}

internal fun codeReady(code: String): Boolean = APPROVAL_CODE.matches(code)

// -- what an answer came back as ----------------------------------------------------------------------------------------------------

/** How `POST …/approvals/{id}/decision` ended (approvals-v1 §6, L2's routes). */
internal enum class DecisionResult {
    /** 200: the plugin has the answer. */
    Sent,
    /** 409 expired, or 404 (the record was removed): the agent stopped waiting, nothing ran. */
    Ended,
    /** 409 settled: another of the owner's devices answered first. */
    Elsewhere,
    /** 400: the server refused the answer (for a code: not 4-10 letters or digits). */
    Invalid,
    /** 403: this phone isn't a linked device of the owner any more. */
    NotLinked,
    /** No answer from Hermes, or 503: the owner can try again. */
    Retry,
}

internal fun decisionResult(status: Int, body: JSONObject?): DecisionResult {
    val said = (body?.opt("detail") as? String).orEmpty()
    return when {
        status in 200..299 -> DecisionResult.Sent
        status == 404 -> DecisionResult.Ended
        status == 409 -> if (said == "settled") DecisionResult.Elsewhere else DecisionResult.Ended
        status == 400 -> DecisionResult.Invalid
        status == 401 || status == 403 -> DecisionResult.NotLinked
        else -> DecisionResult.Retry
    }
}

/** The line the card shows after an answer that didn't go through, or after the question ended; null when the card simply moves on. */
internal fun decisionLine(result: DecisionResult, code: Boolean): String? = when (result) {
    DecisionResult.Sent -> null
    // The chat cards' own words for the same states (AGENTUX-121 P3-1): one sentence per state.
    DecisionResult.Ended -> PROMPT_ENDED_NOTICE
    DecisionResult.Elsewhere -> "Answered on another device."
    DecisionResult.Invalid -> if (code) "Check the code and try again." else "Something went wrong. Try again."
    DecisionResult.NotLinked -> "Open Beacon and sign in again to answer."
    DecisionResult.Retry -> APP_CONNECTION_ERROR
}

/** The short line after the owner's own answer, while the card steps aside (approvals-v1 §1: a no stands for a minute). */
internal fun answeredLine(record: AgentApproval, approved: Boolean): String = when {
    record.kind == ApprovalKind.NeedsYou -> if (approved) "Opening the phone…" else "Dismissed"
    record.kind == ApprovalKind.Code -> if (approved) "Code sent" else "Nothing was entered."
    approved -> "Allowed"
    record.payment -> "Nothing was paid."
    record.kind == ApprovalKind.Action -> "Nothing was done."
    else -> "Not saved"
}

/** Every fixed English string of the card and its states that reaches uiText through a variable; the words test checks each one. */
internal val AgentApprovalWords: List<String> by lazy {
    ApprovalRisk.entries.flatMap { listOf(riskTitle(it, true), riskTitle(it, false)) }.distinct() + listOf(
        "{name} wants to use a saved login", "{name} wants to make a login", CHANGE_PASSWORD_TITLE, "{name} found a login", "{name} needs a code", "{name} needs you",
    ) + ApprovalButtons.entries.flatMap { listOf(it.yes, it.no) } + AgentPhoneServerLines + DecisionResult.entries.mapNotNull { decisionLine(it, false) } +
        listOf("Check the code and try again.", "Opening the phone…", "Dismissed", "Code sent", "Nothing was entered.", "Allowed",
            "Nothing was paid.", "Nothing was done.", "Not saved")
}
