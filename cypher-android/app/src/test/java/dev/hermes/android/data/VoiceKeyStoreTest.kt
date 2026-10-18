package dev.hermes.android.data

import org.junit.Assert.*
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class VoiceKeyStoreTest {
    private val storage = linkedMapOf<String, String>()
    private val key = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val store = VoiceKeyStore(storage, ::encrypt, ::decrypt)

    @Test fun aMissingProviderKeyDoesNotProduceCallCredentials() {
        assertNull(store.session())
        assertFalse(store.hasKey("gemini"))
    }

    @Test fun providerKeysRoundTripAsAuthenticatedCiphertextAndExposeOnlyLastFour() {
        val secret = "fixture-provider-key-6403"
        store.saveKey("openai", secret)
        assertEquals(secret, store.key("openai"))
        assertEquals(8, store.maskedKey("openai")?.length)
        assertTrue(checkNotNull(store.maskedKey("openai")).endsWith("6403"))
        assertTrue(storage.values.all { !it.contains(secret) })
        assertFalse(store.toString().contains(secret))
        assertFalse(checkNotNull(store.session("openai")).toString().contains(secret))
    }

    @Test fun keysAreProviderBoundReplaceableAndRemovable() {
        store.saveKey("gemini", "fixture-google-key")
        assertTrue(store.hasKey("gemini"))
        val original = storage.getValue("key.gemini")
        storage["key.grok"] = original
        assertNull(store.key("grok"))
        assertFalse(store.hasKey("grok"))
        store.saveKey("gemini", "fixture-google-key-new")
        assertEquals("fixture-google-key-new", store.key("gemini"))
        store.removeKey("gemini")
        assertNull(store.key("gemini"))
        assertFalse(store.hasKey("gemini"))
        assertFalse(storage.containsKey("key.gemini"))
    }

    @Test fun localProviderModelVoiceAndPersonalitySurviveAStoreReopen() {
        store.setSelection(VoicePreferences("openai", "gpt-realtime-2.1", "marin", "warm"))
        val reopened = VoiceKeyStore(storage, ::encrypt, ::decrypt)
        assertEquals(VoicePreferences("openai", "gpt-realtime-2.1", "marin", "warm"), reopened.settings())
    }

    private fun encrypt(plain: ByteArray, binding: String): ByteArray {
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
        return iv + cipher.doFinal(plain)
    }

    private fun decrypt(encrypted: ByteArray, binding: String): ByteArray {
        require(encrypted.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
        cipher.updateAAD(binding.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted.copyOfRange(12, encrypted.size))
    }
}
