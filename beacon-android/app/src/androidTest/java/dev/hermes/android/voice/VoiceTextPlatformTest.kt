package dev.hermes.android.voice

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VoiceTextPlatformTest {
    @Test fun numericAdditionEchoKeepsItsInputOnAndroid() {
        val input="What is 2 + 3?"
        for(echo in listOf("What is 2 plus 3?","What is two plus three?")) {
            val ledger=VoiceFinalItems()
            ledger.activityStart()
            ledger.accept(org.json.JSONObject().put("text",input),true)
            ledger.echo(org.json.JSONObject().put("text",echo))
            ledger.activityEnd()
            assertEquals(input,ledger.generationComplete()?.second)
        }
        for(echo in listOf("What is two plus four?","What is two minus three?")) {
            val ledger=VoiceFinalItems()
            ledger.activityStart();ledger.accept(org.json.JSONObject().put("text",input),true)
            ledger.echo(org.json.JSONObject().put("text",echo));ledger.activityEnd()
            assertNull(ledger.generationComplete())
        }
    }
    @Test fun unicodeVoiceTextNormalizesOnAndroidWithoutChangingValues() {
        assertEquals("Café words", normalizeVoiceText("Cafe\u0301\u00a0words"))
        assertEquals("a b", normalizeVoiceText("a\u0085\u2003b"))
        assertEquals(normalizeVoiceText("I didn't change it"),normalizeVoiceText("I didn\u2019t change it"))
        assertNotEquals(normalizeVoiceText("Height 6\u2019"),normalizeVoiceText("Height 6"))
        assertNotEquals(normalizeVoiceText("Value -1.5"), normalizeVoiceText("Value 15"))
    }
}
