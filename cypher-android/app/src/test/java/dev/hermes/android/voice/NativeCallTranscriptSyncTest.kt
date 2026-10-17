package dev.hermes.android.voice

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class NativeCallTranscriptSyncTest {
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
            assertTrue(sync.finish());assertTrue(terminal!!.getBoolean("incomplete"))
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
