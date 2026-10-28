package dev.hermes.android.voice

import dev.hermes.android.ui.presenceEnergy
import dev.hermes.android.ui.presenceGain
import dev.hermes.android.ui.presenceSpread
import org.junit.Assert.*
import org.junit.Test

class VoiceResponseTest {
    private val call=CallState(active=true,state="listening")
    @Test fun quietMeasuredSpeechIsVisibleOnBothChannels() {
        assertTrue(call.copy(userSpeaking=true,localAudioLevel=.04f).voiceAmplitude() >= .3f)
        assertTrue(call.copy(remoteSpeaking=true,remoteAudioLevel=.04f).voiceAmplitude() >= .3f)
    }
    @Test fun louderSpeechBrightensTheCloudMoreThanQuietSpeech() {
        val quiet=call.copy(userSpeaking=true,localAudioLevel=.04f).voiceAmplitude()
        val voiced=call.copy(userSpeaking=true,localAudioLevel=.25f).voiceAmplitude()
        // Who talks sets the motion; how loud only sets how bright the dust glows.
        fun excursion(a: Float)= presenceGain(presenceEnergy(a))-presenceGain(presenceEnergy(call.voiceAmplitude()))
        assertTrue(excursion(quiet) >= .06f)
        assertTrue(excursion(voiced) > excursion(quiet)*1.4f)
    }
    @Test fun duplexKeepsTheStrongerMeasuredChannel() {
        val user=call.copy(userSpeaking=true,localAudioLevel=.25f).voiceAmplitude()
        assertEquals(user,call.copy(userSpeaking=true,remoteSpeaking=true,localAudioLevel=.25f,remoteAudioLevel=.001f).voiceAmplitude(),0f)
    }
    @Test fun zeroSilenceMuteHoldAndConnectingDoNotInventSpeech() {
        assertEquals(0f,call.voiceAmplitude(),0f)
        assertEquals(0f,call.copy(remoteSpeaking=true,remoteAudioLevel=0f).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(userSpeaking=true,localAudioLevel=.5f,muted=true).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(remoteSpeaking=true,remoteAudioLevel=.5f,held=true).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(remoteSpeaking=true,state="connecting").voiceAmplitude(),0f)
    }
    @Test fun missingMeasurementUsesActivityButInvalidMeasurementCannotInventSpeech() {
        assertTrue(call.copy(userSpeaking=true).voiceAmplitude()>0f)
        assertTrue(call.copy(remoteSpeaking=true).voiceAmplitude()>0f)
        assertEquals(0f,call.copy(userSpeaking=true,localAudioLevel=Float.NaN).voiceAmplitude(),0f)
        assertEquals(0f,call.copy(remoteSpeaking=true,remoteAudioLevel=-1f).voiceAmplitude(),0f)
    }
    @Test fun reducedMotionHiddenIdleAndSilenceStopPhaseButKeepStateFeedback() {
        val speech=call.copy(remoteSpeaking=true,remoteAudioLevel=.2f).voiceAmplitude()
        assertTrue(voiceMotionActive(speech,true,true))
        assertFalse(voiceMotionActive(speech,false,true))
        assertFalse(voiceMotionActive(speech,true,false))
        assertFalse(voiceMotionActive(0f,true,true))
        assertFalse(voiceMotionActive(Float.NaN,true,true))
        assertTrue(speech>0f)
    }
    @Test fun eachChannelReceivesItsOwnBoundedUpdateCadence() {
        val cadence=VoiceLevelCadence()
        for(t in 0L..200L) {
            assertEquals(t%40L==0L,cadence.accept(true,t))
            assertEquals(t%40L==0L,cadence.accept(false,t))
        }
    }
    @Test fun gainIsMonotonicFiniteAndTheCloudStaysWithinItsBounds() {
        var previous=0f
        for(i in 0..100) {
            val amplitude=call.copy(userSpeaking=true,localAudioLevel=i/100f).voiceAmplitude()
            assertTrue(amplitude in 0f..1f);assertTrue(amplitude>=previous);previous=amplitude
            val energy=presenceEnergy(amplitude)
            assertEquals(amplitude,energy,0f)
            assertTrue(presenceSpread(energy) in 1f..1.1f)
            assertTrue(presenceGain(energy) in .6f..1f)
        }
        assertEquals(1f,call.copy(userSpeaking=true,localAudioLevel=3f).voiceAmplitude(),0f)
    }
}
