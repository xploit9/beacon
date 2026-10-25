package dev.hermes.android.voice

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class NativeCallTranscriptSyncTest {
    @Test fun neverConnectedFailureFinalizesAnEmptyArchiveAndRetriesTheSameSafeFlag()=runBlocking {
        val attempts=mutableListOf<String>()
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            assertTrue(path.endsWith("finalize"));attempts.add(body.toString())
            if(attempts.size==1) throw java.io.IOException("Synthetic lost receipt")
            JSONObject().put("closed",true).put("transcript_status","saved").put("failed",true)
        },"never-connected",worker,{})
        try {
            assertTrue(sync.finish(failed=true))
            assertTrue(sync.finish()) // A late normal cleanup cannot replace the first terminal disposition.
            assertEquals(2,attempts.size);assertEquals(attempts.first(),attempts.last())
            val receipt=JSONObject(attempts.first())
            assertTrue(receipt.optBoolean("failed"));assertFalse(receipt.getBoolean("incomplete"))
            assertEquals(setOf("failed","incomplete"),receipt.keys().asSequence().toSet())
        } finally { worker.cancel() }
    }

    @Test fun ordinaryCancellationAndInterruptedSpeechDoNotSetTerminalFailure()=runBlocking {
        var terminal:JSONObject?=null
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("finalize")) terminal=JSONObject(body.toString())
            JSONObject().put("stored",true).put("closed",true).put("transcript_status","saved")
        },"cancelled",worker,{})
        try {
            sync.event(JSONObject().put("event_id","caption").put("turn_id","1").put("type","transcript")
                .put("role","assistant").put("text","Interrupted fixture words").put("final",true)
                .put("interrupted",true).put("at_ms",1))
            assertTrue(sync.finish())
            assertFalse(terminal!!.optBoolean("failed"));assertFalse(terminal!!.getBoolean("incomplete"))
        } finally { worker.cancel() }
    }

    @Test fun actualLookupReceiptsKeepOnlyBoundedFailureEvidenceThroughSync()=runBlocking {
        for(code in listOf("memory_lookup_busy","memory_lookup_timeout","memory_lookup_failed")) {
            val requests=mutableListOf<JSONObject>()
            val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
            val sync=NativeCallTranscriptSync({path,_,body ->
                if(path.endsWith("events")) requests.add(body!!.getJSONArray("events").getJSONObject(0))
                JSONObject().put("stored",true).put("closed",true)
            },"memory",worker,{})
            try {
                val returned=JSONObject().put("status","unavailable").put("error_code",code)
                    .put("summary","Synthetic private lookup detail").put("sources",org.json.JSONArray())
                val produced=directLookupReceipt("lookup","hermes_memory",returned)
                // Match BeaconCallService's bounded receipt envelope at the sync boundary.
                val event=JSONObject().put("event_id","failure").put("turn_id","tools").put("type","tool_receipt").put("at_ms",1)
                for(key in listOf("request_id","tool_name","status","error_code")) event.put(key,produced.get(key))
                sync.event(event)
                assertTrue(sync.finish())
                val stored=requests.single()
                assertEquals("failed",stored.getString("status"));assertEquals(code,stored.getString("error_code"))
                assertFalse(stored.toString().contains("Synthetic private lookup detail"))
                assertFalse(stored.has("summary"));assertFalse(stored.has("sources"));assertFalse(stored.has("result_present"))
            } finally { worker.cancel() }
        }
    }

    // Same normalization and caption envelope as DirectVoice/BeaconCallService; no provider connection.
    private fun providerItem(wire:GrokWire,item:String,text:String,turn:String="3",at:Long=10,index:Int=0):JSONObject? {
        val input=wire.inbound(JSONObject().put("type","conversation.item.input_audio_transcription.completed")
            .put("item_id",item).put("content_index",index).put("transcript",text))
            ?.getJSONObject("serverContent")?.getJSONObject("inputTranscription") ?: return null
        return providerTranscript(JSONObject().put("type","transcript").put("role","user")
            .put("provider_item",input.getString("item_id")).put("content_index",input.getInt("content_index"))
            .put("text",input.getString("text")).put("final",input.getBoolean("final")).put("turn_id",turn),"captions","session",at)
    }

    @Test fun distinctGrokItemsKeepDisplayIdentityWhileEveryVersionHasItsOwnReceipt()=runBlocking {
        val requests=Collections.synchronizedList(mutableListOf<JSONObject>())
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) requests.add(JSONObject(body.toString()).getJSONArray("events").getJSONObject(0))
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val wire=GrokWire()
            val first=providerItem(wire,"one","yes")!!
            sync.event(first)
            assertNull(providerItem(wire,"one","yes"))
            val second=providerItem(wire,"two","yes",at=20)!!
            sync.event(second)
            val correction=providerItem(wire,"one","yes please",turn="4",at=30)!!
            sync.event(correction)
            val nextContent=providerItem(wire,"one","yes",at=40,index=1)!!
            sync.event(nextContent)
            assertTrue(sync.finish())
            assertEquals(listOf(first,second,correction,nextContent).map { it.getString("event_id") },requests.map { it.optString("display_event_id") })
            assertEquals(4,requests.map { it.getString("event_id") }.toSet().size)
            assertEquals(listOf("3","3","4","3"),requests.map { it.getString("turn_id") })
            assertEquals(listOf(10L,20L,30L,40L),requests.map { it.getLong("at_ms") })
            assertTrue(requests.all { !it.has("provider_item")&&!it.has("content_index") })
            assertTrue(requests.all { it.getString("event_id")!=it.getString("display_event_id") })
            assertEquals(first.getString("event_id"),correction.getString("event_id"))
            assertNotEquals(first.getString("event_id"),nextContent.getString("event_id"))
        } finally { worker.cancel() }
    }

    @Test fun oneFinalDoesNotConsumeOtherItemsPendingInTheSameNumericTurn()=runBlocking {
        val events=Collections.synchronizedList(mutableListOf<JSONObject>())
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) events.add(body!!.getJSONArray("events").getJSONObject(0))
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val wire=GrokWire()
            val first=providerItem(wire,"one","first")!!
            val second=providerItem(wire,"two","unfinished",at=20)!!
            val third=providerItem(wire,"three","another unfinished",at=30)!!
            // Exercise the sync boundary's partial snapshots independently of Grok's completed flag.
            sync.event(JSONObject(first.toString()).put("final",false))
            sync.event(JSONObject(second.toString()).put("final",false))
            sync.event(JSONObject(second.toString()).put("text","unfinished correction").put("at_ms",40).put("final",false))
            sync.event(JSONObject(third.toString()).put("final",false))
            sync.event(JSONObject(first.toString()).put("at_ms",50))
            assertTrue(sync.finish())
            assertEquals(3,events.size)
            val byItem=events.associateBy { it.getString("display_event_id") }
            assertTrue(byItem.getValue(first.getString("event_id")).getBoolean("final"))
            assertEquals("unfinished correction",byItem.getValue(second.getString("event_id")).getString("text"))
            assertFalse(byItem.getValue(second.getString("event_id")).getBoolean("final"))
            assertFalse(byItem.getValue(third.getString("event_id")).getBoolean("final"))
            assertEquals(listOf(10L,20L,30L),listOf(first,second,third).map { byItem.getValue(it.getString("event_id")).getLong("at_ms") })
        } finally { worker.cancel() }
    }

    @Test fun onlyOriginalCallAndRoleBoundTextIdentityIsForwarded()=runBlocking {
        val events=Collections.synchronizedList(mutableListOf<JSONObject>())
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) events.add(body!!.getJSONArray("events").getJSONObject(0))
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val ids=listOf("another:user:one","captions:assistant:one","captions:user:","captions:user:bad:key",
                "captions:user:"+"x".repeat(257),"captions:user:\nkey","captions:user:عربي","legacy")
            for(id in ids) sync.event(JSONObject().put("type","transcript").put("role","user").put("turn_id","3")
                .put("event_id",id).put("display_event_id","captions:user:spoofed").put("text","kept").put("final",true).put("at_ms",10))
            val valid=directCaption(JSONObject().put("type","transcript").put("role","assistant").put("final",true),"captions","session",3,"reply").put("at_ms",20)
            sync.event(valid)
            assertTrue(sync.finish())
            assertEquals(ids.size+1,events.size)
            assertTrue(events.take(ids.size).all { !it.has("display_event_id") })
            assertEquals("captions:assistant:3",events.last().getString("display_event_id"))
        } finally { worker.cancel() }
    }

    @Test fun retryReusesTheExactDeliveryIdentityAndItemMetadata()=runBlocking {
        val attempts=mutableListOf<String>()
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) {
                attempts.add(body.toString())
                if(attempts.size==1) throw java.io.IOException("Lost event receipt")
            }
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val input=providerItem(GrokWire(),"one","yes")!!
            sync.event(input)
            assertTrue(sync.finish())
            assertEquals(2,attempts.size);assertEquals(attempts.first(),attempts.last())
            assertEquals(input.getString("event_id"),JSONObject(attempts.first()).getJSONArray("events").getJSONObject(0).getString("display_event_id"))
        } finally { worker.cancel() }
    }

    @Test fun distinctPartialBufferRemainsBoundedAndReportsEviction()=runBlocking {
        val events=Collections.synchronizedList(mutableListOf<JSONObject>())
        var terminal:JSONObject?=null
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) events.add(body!!.getJSONArray("events").getJSONObject(0)) else terminal=body
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val wire=GrokWire()
            repeat(17) { sync.event(providerItem(wire,"item-$it","partial $it",at=it.toLong())!!.put("final",false)) }
            assertTrue(sync.finish())
            assertEquals(16,events.size)
            assertEquals((1..16).map { "partial $it" },events.map { it.getString("text") })
            assertTrue(terminal!!.getBoolean("incomplete"))
        } finally { worker.cancel() }
    }

    @Test fun textTruncationKeepsIdentityAndDoesNotSplitASurrogatePair()=runBlocking {
        var event:JSONObject?=null;var terminal:JSONObject?=null
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) event=body!!.getJSONArray("events").getJSONObject(0) else terminal=body
            JSONObject().put("stored",true).put("closed",true)
        },"captions",worker,{})
        try {
            val input=providerItem(GrokWire(),"one","x".repeat(8191)+"😊tail")!!
            sync.event(input)
            assertTrue(sync.finish())
            assertEquals("x".repeat(8191),event!!.getString("text"))
            assertEquals(input.getString("event_id"),event!!.getString("display_event_id"))
            assertTrue(terminal!!.getBoolean("incomplete"))
        } finally { worker.cancel() }
    }

    @Test fun captionsSyncWithoutAnyAudioRequest()=runBlocking {
        val requests=Collections.synchronizedList(mutableListOf<Pair<String,JSONObject>>())
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body -> requests.add(path to JSONObject(body.toString()));JSONObject().put("stored",true).put("closed",true) },"captions",worker,{})
        try {
            val value=JSONObject().put("event_id","caption").put("turn_id","0").put("role","assistant").put("type","transcript").put("at_ms",0)
            repeat(10000) { sync.event(JSONObject(value.toString()).put("text","progress $it")) }
            sync.event(JSONObject(value.toString()).put("text","Final text").put("final",true))
            assertTrue(sync.finish())
            assertEquals(1,requests.count { it.first.endsWith("events") })
            assertTrue(requests.all { it.first.endsWith("events")||it.first.endsWith("finalize") })
            assertEquals("Final text",requests.first().second.getJSONArray("events").getJSONObject(0).getString("text"))
        } finally { worker.cancel() }
    }
    @Test fun unfinishedCaptionRetainedAtHangup()=runBlocking {
        val requests=mutableListOf<JSONObject>()
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body -> if(path.endsWith("events")) requests.add(body!!);JSONObject().put("stored",true).put("closed",true) },"captions",worker,{})
        try {
            sync.event(JSONObject().put("event_id","partial").put("turn_id","0").put("role","user").put("type","transcript").put("text","Unfinished").put("at_ms",0))
            assertTrue(sync.finish())
            assertEquals("Unfinished",requests.single().getJSONArray("events").getJSONObject(0).getString("text"))
        } finally { worker.cancel() }
    }
    @Test fun finalizeRetriesIdenticalIdempotentBodyAfterLostReceipt()=runBlocking {
        val attempts=mutableListOf<String>()
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            assertTrue(path.endsWith("finalize"));attempts.add(body.toString())
            if(attempts.size==1) throw java.io.IOException("Lost receipt after host finalized")
            JSONObject().put("closed",true).put("transcript_status","saved")
        },"retry",worker,{})
        try {
            assertTrue(sync.finish());assertTrue(sync.finish())
            assertEquals(2,attempts.size);assertEquals(attempts.first(),attempts.last())
        } finally { worker.cancel() }
    }
    @Test fun failedTextUploadStillFinalizesExplicitlyIncomplete()=runBlocking {
        var terminal:JSONObject?=null
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({path,_,body ->
            if(path.endsWith("events")) throw java.io.IOException("Events unavailable")
            terminal=JSONObject(body.toString());JSONObject().put("closed",true).put("transcript_status","partial")
        },"gap",worker,{})
        try {
            sync.event(JSONObject().put("event_id","text").put("turn_id","0").put("role","user").put("type","transcript").put("text","Lost text").put("final",true).put("at_ms",0))
            assertTrue(sync.finish(failed=true));assertTrue(terminal!!.getBoolean("incomplete"));assertTrue(terminal!!.getBoolean("failed"))
        } finally { worker.cancel() }
    }
    @Test fun cancelledFirstFinishDoesNotCancelIndependentWaiter()=runBlocking {
        val entered=CompletableDeferred<Unit>()
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({_,_,_ -> entered.complete(Unit);awaitCancellation() },"cancel",worker,{})
        try {
            val first=async { sync.finish() };entered.await()
            val second=async(start=CoroutineStart.UNDISPATCHED) { sync.finish() }
            first.cancelAndJoin()
            assertFalse(withTimeout(3000) { second.await() })
            assertTrue(second.isCompleted);assertFalse(second.isCancelled)
        } finally { worker.cancel() }
    }
    @Test fun concurrentFinishSharesOneSuccessfulFinalization()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var attempts=0
        val worker=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val sync=NativeCallTranscriptSync({_,_,_ -> attempts++;entered.complete(Unit);release.await();JSONObject().put("closed",true) },"concurrent",worker,{})
        try {
            val first=async { sync.finish() };entered.await()
            val second=async(start=CoroutineStart.UNDISPATCHED) { sync.finish() }
            release.complete(Unit)
            assertTrue(first.await());assertTrue(second.await());assertEquals(1,attempts)
        } finally { worker.cancel() }
    }

}
