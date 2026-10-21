package dev.hermes.android.voice

import dev.hermes.android.core.Scope
import org.junit.Assert.*
import org.junit.Test

class CallPresentationTest {
    private val owner=Scope("https://demo.invalid","synthetic-owner","default")
    private val call=CallState(active=true,state="listening",callId="call",conversationScope=owner.session("chat"))
    @Test fun overlayRequiresCurrentCallOwnerProfileAuthenticationAndUnlockedScreen() {
        assertTrue(overlayOwnsCall(call,"call",owner,true,true))
        assertFalse(overlayOwnsCall(call,"other",owner,true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(origin="https://other.invalid"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(owner="another"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner.copy(profile="work"),true,true))
        assertFalse(overlayOwnsCall(call,"call",owner,false,true))
        assertFalse(overlayOwnsCall(call,"call",owner,true,false))
        assertFalse(overlayOwnsCall(call.copy(active=false),"call",owner,true,true))
    }
    @Test fun levelsAndActivityNeverInventOutputForInactiveHeldOrConnectingCall() {
        assertNull(normalizedVoiceLevel(Float.NaN)); assertNull(normalizedVoiceLevel(-1f))
        assertEquals(1f,normalizedVoiceLevel(2f)!!,0f)
        assertEquals(0f,call.copy(active=false,remoteSpeaking=true).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(held=true,remoteSpeaking=true).voiceAmplitude(),0f)
        assertTrue(call.copy(state="speaking",remoteAudioLevel=.7f).voiceAmplitude() >= .7f)
    }
    @Test fun captionProjectionPreservesCanonicalTextAndHidesAtEnd() {
        val values=listOf(CallTranscript("user","user","Current caller text",false),CallTranscript("bot","assistant","Current assistant text",false))
        assertEquals("Current caller text",call.copy(transcripts=values).latestCaption("user"))
        assertEquals("",call.copy(active=false,transcripts=values).latestCaption("user"))
        assertEquals(values,call.copy(transcripts=values).transcripts)
        assertEquals("",call.copy(transcripts=values+CallTranscript("cancelled","assistant","",true,interrupted=true)).latestCaption("assistant"))
    }
    @Test fun navigationUsesAvailableDpAndLargeTextFallback() {
        assertFalse(persistentCallNavigation(390f,1f)); assertTrue(persistentCallNavigation(864f,1f))
        assertFalse(persistentCallNavigation(720f,1.6f)); assertTrue(persistentCallNavigation(1200f,1.6f))
    }
    @Test fun draggingClampsBothEdgesAndSafeSystemInsets() {
        assertEquals(0 to 24,clampBubblePosition(-100,-100,64,64,400,800,24,32))
        assertEquals(336 to 704,clampBubblePosition(2000,2000,64,64,400,800,24,32))
    }
    @Test fun waveGeometryIsFiniteBoundedAndUsesActivityInsteadOfUnboundedFrameWork() {
        for(phase in listOf(0f,1f,4f)) for(i in 0..56) for(layer in 0..2) {
            val y=voiceWaveY(i/56f,phase,1f,layer); assertTrue(y.isFinite()&&y in .1f..0.95f)
        }
        assertNotEquals(voiceWaveY(.4f,0f,.1f,0),voiceWaveY(.4f,1f,.8f,0))
    }
}
