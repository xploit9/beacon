package dev.hermes.android.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ProviderTranscriptTest {
    private fun item(id:String,text:String):CallTranscript {
        val normalized=GrokWire().inbound(JSONObject().put("type","conversation.item.input_audio_transcription.completed")
            .put("event_id",java.util.UUID.randomUUID().toString()).put("item_id",id).put("content_index",0).put("transcript",text))!!
            .getJSONObject("serverContent").getJSONObject("inputTranscription")
        val event=JSONObject().put("type","transcript").put("role","user").put("provider_item",normalized.getString("item_id"))
            .put("content_index",normalized.getInt("content_index")).put("text",normalized.getString("text"))
        return CallTranscript.from(providerTranscript(event,"call","session",100)!!,"session","call")!!
    }
    @Test fun replayIsSuppressedByItemIdentityWithoutDiscardingCorrections() {
        val wire=GrokWire()
        fun frame(item:String,text:String)=JSONObject().put("type","conversation.item.input_audio_transcription.completed")
            .put("item_id",item).put("content_index",0).put("transcript",text)
        assertNotNull(wire.inbound(frame("one","yes")))
        assertNull(wire.inbound(frame("one","yes")))
        assertNotNull(wire.inbound(frame("one","yes please")))
        assertNotNull(wire.inbound(frame("two","yes please")))
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
