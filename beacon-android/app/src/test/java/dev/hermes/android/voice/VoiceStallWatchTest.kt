package dev.hermes.android.voice

import dev.hermes.android.core.json
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VoiceStallWatchTest {
    private var now=10_000L
    private val watch=VoiceStallWatch({ now },20_000)
    private fun speak(ms:Long) { repeat((ms/20).toInt()) { watch.speech(true,20);now+=20 } }

    @Test fun stallsCountInARowButOneLongAfterTheLastStartsAgain() {
        assertEquals(1, watch.stalledAgain()); now += 30_000
        assertEquals(2, watch.stalledAgain()); now += 121_000
        assertEquals("A stall two minutes later is unrelated", 1, watch.stalledAgain())
        watch.replied()
        assertEquals("A reply clears them", 0, watch.stallsInARow())
    }

    @Test fun silenceAloneIsNeverAStall() {
        now+=3_600_000
        assertFalse(watch.stalled())
        watch.heard();now+=3_600_000
        assertFalse(watch.stalled())
    }

    @Test fun speechThatGetsNoFrameAtAllStallsAfterTheLimitAndAReplySettlesIt() {
        speak(1000)
        now+=19_000;assertFalse(watch.stalled())
        now+=1_000;assertTrue(watch.stalled())
        watch.replied();now+=60_000
        assertFalse(watch.stalled())
    }

    @Test fun anyServerFrameShowsTheSessionIsAliveAndRestartsTheClock() {
        speak(1000);now+=19_000
        watch.heard()
        now+=19_000;assertFalse("A transcript or setup frame is not silence",watch.stalled())
        now+=1_000;assertTrue("The model still owes its answer",watch.stalled())
    }

    @Test fun aToolResultOrTurnMustBeAnsweredToo() {
        watch.expect();now+=20_000
        assertTrue(watch.stalled())
        watch.reset();now+=60_000
        assertFalse("A new provider session owes nothing",watch.stalled())
    }

    @Test fun typingDoorsAndShortNoisesAreNotTheOwnerTalking() {
        repeat(100) { watch.speech(true,20);now+=20;repeat(20) { watch.speech(false,20);now+=20 } }
        now+=60_000
        assertFalse(watch.stalled())
    }

    @Test fun pausesBetweenWordsKeepOneUtterance() {
        repeat(10) { speak(100);repeat(10) { watch.speech(false,20);now+=20 } }
        now+=20_000
        assertTrue(watch.stalled())
    }

    @Test fun theSpeakersOwnEchoIsNotTheOwnerTalkingUntilABargeIn() {
        watch.playing(5_000)
        speak(5_000)
        now+=60_000
        assertFalse(watch.stalled())
        watch.playing(5_000);watch.interrupted()
        speak(1_000)
        now+=20_000
        assertTrue("After the server cut the reply, speech counts at once",watch.stalled())
    }

    @Test fun onlyRealSpeechLevelCountsAsLoud() {
        fun pcm(amplitude:Int)=ByteArray(640).also { for(i in it.indices step 2) { it[i]=(amplitude and 255).toByte();it[i+1]=(amplitude shr 8).toByte() } }
        assertTrue(voiceLoud(pcm(8000)))
        assertTrue(voiceLoud(pcm(-8000)))
        assertFalse(voiceLoud(pcm(100)))
        assertFalse(voiceLoud(ByteArray(0)))
    }

    @Test fun onlyModelOutputCountsAsAReply() {
        assertTrue(voiceReplyFrame(json("serverContent" to json("modelTurn" to json("parts" to JSONArray())))))
        assertTrue(voiceReplyFrame(json("serverContent" to json("outputTranscription" to json("text" to "hi")))))
        assertTrue(voiceReplyFrame(json("serverContent" to json("turnComplete" to true))))
        assertTrue(voiceReplyFrame(json("toolCall" to json("functionCalls" to JSONArray()))))
        assertFalse(voiceReplyFrame(json("setupComplete" to json())))
        assertFalse(voiceReplyFrame(json("sessionResumptionUpdate" to json("resumable" to true))))
        assertFalse(voiceReplyFrame(json("serverContent" to json("interrupted" to true))))
        assertFalse(voiceReplyFrame(json("serverContent" to json("inputTranscription" to json("text" to "hello")))))
    }

    @Test fun onlyFramesTheModelMustAnswerOweAReply() {
        fun tool(name:String,status:String)=GeminiWire.tool("id",name,JSONObject().put("status",status)).toString()
        assertEquals(OwedReply.Tool,voiceOwedReply(tool("invoke_tool","completed")))
        assertEquals(OwedReply.Tool,voiceOwedReply(tool("invoke_tool","needs_approval")))
        assertEquals(OwedReply.Tool,voiceOwedReply(tool("hermes_search","completed")))
        // Progress receipts are SILENT: the model is not expected to speak for them.
        assertEquals(OwedReply.None,voiceOwedReply(tool("invoke_tool","running")))
        assertEquals(OwedReply.None,voiceOwedReply(tool("hermes_work","waiting_for_idle")))
        val update=json("clientContent" to json("turns" to JSONArray(),"turnComplete" to true)).toString()
        assertEquals(OwedReply.Turn,voiceOwedReply(update))
        assertEquals(OwedReply.None,voiceOwedReply(json("clientContent" to json("turns" to JSONArray(),"turnComplete" to false)).toString()))
        assertEquals(OwedReply.None,voiceOwedReply(GeminiWire.audio("AAAA").toString()))
        assertEquals(OwedReply.None,voiceOwedReply(GeminiWire.endAudio().toString()))
    }

    @Test fun videoWaitsForTheSocketToDrainAndBacksOffOnASlowLink() {
        var clock=10_000L
        val pacing=VisualPacing { clock }
        assertTrue(pacing.mayQueue())
        pacing.sent()
        pacing.drained(40_000);clock+=100
        assertFalse("The last frame is still in the socket queue",pacing.mayQueue())
        // Drained after 600 ms: the next frame waits three times that, never faster than 0.8 s.
        clock+=500;pacing.drained(0)
        assertFalse(pacing.mayQueue())
        clock+=1_199;assertFalse(pacing.mayQueue())
        clock+=1;assertTrue(pacing.mayQueue())
        // A fast link keeps about one frame a second (the capture rate); a 9 s drain waits 27 s, so video never hogs a weak uplink.
        pacing.sent();clock+=10;pacing.drained(0);clock+=789;assertFalse(pacing.mayQueue());clock+=1;assertTrue(pacing.mayQueue())
        pacing.sent();clock+=9_000;pacing.drained(0);clock+=17_999;assertFalse(pacing.mayQueue());clock+=1;assertTrue(pacing.mayQueue())
        pacing.sent();pacing.reset()
        assertTrue("A new socket starts clean",pacing.mayQueue())
    }
}
