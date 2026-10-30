package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderTranscriptTest {
    private fun item(id:String,text:String):CallTranscript {
        val wire=GrokWire()
        wire.inbound(JSONObject().put("type","input_audio_buffer.speech_started").put("item_id",id))
        wire.inbound(JSONObject().put("type","input_audio_buffer.committed").put("item_id",id))
        val normalized=wire.inbound(JSONObject().put("type","conversation.item.input_audio_transcription.completed")
            .put("event_id",java.util.UUID.randomUUID().toString()).put("item_id",id).put("content_index",0).put("transcript",text).put("status","completed"))!!
            .getJSONObject("serverContent").getJSONObject("inputTranscription")
        val event=JSONObject().put("type","transcript").put("role","user").put("provider_item",normalized.getString("item_id"))
            .put("content_index",normalized.getInt("content_index")).put("text",normalized.getString("text"))
        return CallTranscript.from(providerTranscript(event,"call","session",100)!!,"session","call")!!
    }
    @Test fun partialCorrectionsDoNotPoisonTheCommittedFinalOrReemitIt() {
        val wire=GrokWire()
        fun event(type:String,item:String)=JSONObject().put("type",type).put("item_id",item)
        fun frame(item:String,text:String,status:String)=event("conversation.item.input_audio_transcription.completed",item)
            .put("content_index",0).put("transcript",text).put("status",status)
        wire.inbound(event("input_audio_buffer.speech_started","one"))
        val first=wire.inbound(frame("one","yes","in_progress"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription")
        val correction=wire.inbound(frame("one","yes please","in_progress"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription")
        assertFalse(first.getBoolean("final"));assertFalse(correction.getBoolean("final"));assertEquals("yes please",correction.getString("text"))
        wire.inbound(event("input_audio_buffer.committed","one"))
        assertTrue(wire.inbound(frame("one","yes please","completed"))!!.getJSONObject("serverContent").getJSONObject("inputTranscription").getBoolean("final"))
        assertNull(wire.inbound(frame("one","yes please","completed")))
        wire.inbound(event("input_audio_buffer.speech_started","two"));wire.inbound(event("input_audio_buffer.committed","two"))
        assertNotNull(wire.inbound(frame("two","yes please","completed")))
    }
    @Test fun replayAndCumulativeCorrectionsReplaceOneItem() {
        var rows=emptyList<CallTranscript>()
        for(text in listOf("turn", "turn", "turn left", "turn right")) rows=rows.acceptTranscript(item("one",text))
        assertEquals(1,rows.size);assertEquals("turn right",rows.single().text);assertTrue(rows.single().final)
    }
    @Test fun genuineRepeatedSpeechAndDifferentItemsArePreserved() {
        val rows=emptyList<CallTranscript>().acceptTranscript(item("one","yes yes yes")).acceptTranscript(item("two","yes yes yes"))
        assertEquals(2,rows.size);assertTrue(rows.all { it.text=="yes yes yes" });assertNotEquals(rows[0].eventId,rows[1].eventId)
    }
    @Test fun malformedProviderItemsAreIgnoredWithoutInterruptingTheCall() {
        for (event in listOf(JSONObject().put("role", "assistant").put("provider_item", "item"),
            JSONObject().put("role", "user").put("provider_item", "x".repeat(201)))) {
            assertNull(providerTranscript(event, "call", "session", 0))
        }
        assertNotNull(item("valid", "The next valid item still works"))
    }
    @Test fun providerSnapshotDoesNotEnterGeminiCaptionAccumulator() {
        assertNull(providerTranscript(JSONObject().put("role","user").put("text","hello"),"call","session",0))
        val captions=mutableMapOf("assistant" to "hello")
        item("late-user","late completion")
        val final=mutableListOf<JSONObject>()
        finishDirectTranscriptTurn("call","session",2,captions,200,deliver={final+=it})
        assertEquals(listOf("assistant"),final.map { it.getString("role") })
    }
}
