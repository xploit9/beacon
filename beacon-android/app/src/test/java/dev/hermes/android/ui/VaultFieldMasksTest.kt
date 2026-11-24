package dev.hermes.android.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Which Vault fields are secrets (masked, with an eye button) and which are plain text. */
class VaultFieldMasksTest {
    private fun masked(form: VaultForm) = vaultFields(form).filter { it.masked }.map { it.key }

    @Test fun onlySecretsAreMasked() {
        assertEquals(listOf("password", "otp_secret"), masked(VaultForm.Login))
        assertEquals(listOf("card_number", "cvc"), masked(VaultForm.Card))
        assertEquals(emptyList<String>(), masked(VaultForm.Address))
    }

    @Test fun theServerPromptsStayMaskedByDefault() {
        assertTrue(SecureField("value", "Verification code").masked)
        assertTrue(SecureField("password", "Password").masked)
    }

    @Test fun theEyeLabelsExistInEveryLanguage() {
        val file = listOf("src/main/assets/native-locales.json", "app/src/main/assets/native-locales.json", "beacon-android/app/src/main/assets/native-locales.json")
            .map(::File).first { it.isFile }
        val catalog = JSONObject(file.readText(Charsets.UTF_8))
        val en = catalog.getJSONObject("en")
        assertEquals("Show {field}", en.getString("show {field}")); assertEquals("Hide {field}", en.getString("hide {field}"))
        for (language in catalog.keys()) for (key in listOf("show {field}", "hide {field}"))
            assertTrue("$language misses '$key'", catalog.getJSONObject(language).optString(key).isNotBlank())
    }
}
