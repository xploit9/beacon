package dev.hermes.agentphone.helper.core

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RolesTest {
    @Test
    fun inputTypeValuesAreAndroids() {
        assertEquals(InputType.TYPE_MASK_CLASS, Roles.TYPE_MASK_CLASS)
        assertEquals(InputType.TYPE_MASK_VARIATION, Roles.TYPE_MASK_VARIATION)
        assertEquals(InputType.TYPE_CLASS_TEXT, Roles.TYPE_CLASS_TEXT)
        assertEquals(InputType.TYPE_TEXT_VARIATION_PASSWORD, Roles.TYPE_TEXT_VARIATION_PASSWORD)
        assertEquals(InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, Roles.TYPE_TEXT_VARIATION_WEB_PASSWORD)
    }

    @Test
    fun hintsFirst() {
        val cases = mapOf(
            "username" to "username", "emailAddress" to "username", "password" to "password", "newPassword" to "new_password",
            "smsOTPCode" to "otp", "creditCardNumber" to "card_number", "creditCardExpirationDate" to "exp_date",
            "creditCardExpirationMonth" to "exp_month", "creditCardExpirationYear" to "exp_year",
            "creditCardSecurityCode" to "cvc", "postalAddress" to "address_line1", "postalCode" to "postal_code",
        )
        for ((hint, role) in cases) assertEquals(hint, role, Roles.of(listOf(hint), "cc-number", "email", 0))
        assertNull(Roles.of(listOf("smsOTPCode1"), null, null, 0)) // one box of a code: no role
        assertNull(Roles.of(listOf("phone", "name"), null, null, 0))
    }

    @Test
    fun thenAutocompleteThenType() {
        val cases = mapOf(
            "username" to "username", "email" to "username", "current-password" to "password", "new-password" to "new_password",
            "one-time-code" to "otp", "cc-number" to "card_number", "cc-name" to "cardholder_name", "cc-exp" to "exp_date",
            "cc-exp-month" to "exp_month", "cc-exp-year" to "exp_year", "cc-csc" to "cvc", "postal-code" to "postal_code",
            "address-line1" to "address_line1", "address-line2" to "address_line2", "address-level2" to "city",
            "address-level1" to "state", "country-name" to "country",
        )
        for ((token, role) in cases) assertEquals(token, role, Roles.of(null, "section-a shipping $token", "password", 0))
        assertEquals("username", Roles.of(null, "username webauthn", null, 0))
        assertEquals("password", Roles.of(null, "off", "Password", 0))
        assertEquals("username", Roles.of(null, null, "email", 0))
        val text = InputType.TYPE_CLASS_TEXT
        assertEquals("password", Roles.of(null, null, null, text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertEquals("password", Roles.of(null, null, null, text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        // Visible-password is how apps turn suggestions off; a number PIN is not a text password.
        assertNull(Roles.of(null, null, null, text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertNull(Roles.of(null, null, null, InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun aPostcodeBesideACardIsTheBillingPostcode() {
        assertEquals(listOf("card_number", null, "billing_postal_code"), Roles.withBilling(listOf("card_number", null, "postal_code")))
        assertEquals(listOf("address_line1", "postal_code"), Roles.withBilling(listOf("address_line1", "postal_code")))
    }
}
