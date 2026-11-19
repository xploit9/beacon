package dev.hermes.agentphone.helper.core

import java.util.Locale

/**
 * The role of one form field (docs/agent-phone/channel-v1.md §6.2): Android's autofill hints first, then the HTML
 * `autocomplete` tokens, then the input type. Hint names come from View.AUTOFILL_HINT_* and androidx.autofill
 * HintConstants 1.0.0 (newPassword, smsOTPCode); the per-digit hints smsOTPCode1-8 name one box of a code, so they
 * get no role (a whole code can't go into one box).
 */
object Roles {
    // android.text.InputType values (compile-time constants, kept here so this file has no android imports).
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000080
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0

    private val HINTS = mapOf(
        "username" to "username", "emailaddress" to "username",
        "password" to "password",
        "newpassword" to "new_password",
        "smsotpcode" to "otp",
        "creditcardnumber" to "card_number",
        "creditcardexpirationdate" to "exp_date",
        "creditcardexpirationmonth" to "exp_month",
        "creditcardexpirationyear" to "exp_year",
        "creditcardsecuritycode" to "cvc",
        "postaladdress" to "address_line1",
        "postalcode" to "postal_code",
    )
    private val AUTOCOMPLETE = mapOf(
        "username" to "username", "email" to "username",
        "current-password" to "password",
        "new-password" to "new_password",
        "one-time-code" to "otp",
        "cc-number" to "card_number",
        "cc-name" to "cardholder_name",
        "cc-exp" to "exp_date",
        "cc-exp-month" to "exp_month",
        "cc-exp-year" to "exp_year",
        "cc-csc" to "cvc",
        "postal-code" to "postal_code",
        "address-line1" to "address_line1",
        "address-line2" to "address_line2",
        "address-level2" to "city",
        "address-level1" to "state",
        "country-name" to "country",
    )
    private val CARD = setOf("card_number", "cardholder_name", "exp_date", "exp_month", "exp_year", "cvc")

    private fun low(s: String) = s.trim().lowercase(Locale.ROOT)

    fun of(hints: List<String>?, autocomplete: String?, htmlType: String?, inputType: Int): String? {
        hints?.forEach { h -> HINTS[low(h)]?.let { return it } }
        autocomplete?.split(' ', '\t', '\n', '\r', '\u000c')?.forEach { token -> AUTOCOMPLETE[low(token)]?.let { return it } }
        when (htmlType?.let(::low)) {
            "password" -> return "password"
            "email" -> return "username"
        }
        val variation = inputType and TYPE_MASK_VARIATION
        if (inputType and TYPE_MASK_CLASS == TYPE_CLASS_TEXT &&
            (variation == TYPE_TEXT_VARIATION_PASSWORD || variation == TYPE_TEXT_VARIATION_WEB_PASSWORD)) return "password"
        return null
    }

    /** A postcode in a form that has a card field is the card's billing postcode. */
    fun withBilling(roles: List<String?>): List<String?> =
        if (roles.none { it in CARD }) roles else roles.map { if (it == "postal_code") "billing_postal_code" else it }
}
