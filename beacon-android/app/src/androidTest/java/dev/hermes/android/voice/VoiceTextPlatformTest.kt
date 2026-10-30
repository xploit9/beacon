package dev.hermes.android.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTextPlatformTest {
    @Test fun unicodeVoiceTextNormalizesOnAndroidWithoutChangingValues() {
        assertEquals("Café words", normalizeVoiceText("Cafe\u0301\u00a0words"))
        assertEquals("a b", normalizeVoiceText("a\u0085\u2003b"))
        assertNotEquals(normalizeVoiceText("Value -1.5"), normalizeVoiceText("Value 15"))
    }
}
