package dev.hermes.android.voice

import dev.hermes.android.core.json
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.data.defaultVoiceModel
import dev.hermes.android.data.defaultVoiceName
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

class VoiceStallRecoveryTest {
    private class Provider {
        val inputs = CopyOnWriteArrayList<Pair<WebSocket, WebSocketListener>>()
        val frames = CopyOnWriteArrayList<JSONObject>()
        val factory = WebSocket.Factory { request, listener ->
            val socket = object : WebSocket {
                override fun request() = request
                override fun queueSize() = 0L
                override fun send(bytes: ByteString) = false
                override fun close(code: Int, reason: String?) = true
                override fun cancel() = Unit
                override fun send(text: String): Boolean {
                    val frame = JSONObject(text); frames += frame
                    val instructions = frame.optJSONObject("setup")?.optJSONObject("systemInstruction")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                        ?: frame.optJSONObject("session")?.optString("instructions").orEmpty()
                    if (instructions.startsWith("Transcribe") && inputs.none { it.first === this }) inputs += this to listener
                    if (frame.has("setup")) listener.onMessage(this, json("setupComplete" to json()).toString())
                    else if (frame.optString("type") == "session.update") listener.onMessage(this, json("type" to "session.updated").toString())
                    return true
                }
            }
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Fixture").build())
            socket
        }
    }
    private fun credentials(provider: String) = VoiceSessionCredentials(provider, "fixture-key", defaultVoiceModel(provider), defaultVoiceName(provider), "balanced")
    private fun client() = BeaconClient(AuthContext("https://voice-fixture.invalid", emptyList(), "owner", "default", backend = "hermes-dashboard"), null) { builder ->
        builder.addInterceptor { chain ->
            val body = if (chain.request().url.encodedPath.endsWith("/context")) json("profile" to "default", "selected_session_id" to "stored") else json("jobs" to JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    private val speech = ByteArray(640).also { for (i in it.indices step 2) { it[i] = (8000 and 255).toByte(); it[i + 1] = (8000 shr 8).toByte() } }
    private suspend fun await(predicate: () -> Boolean) = withTimeout(7000) { while (!predicate()) delay(10) }
    private suspend fun DirectVoice.talk() = repeat(55) { pcm(speech) { true }; delay(20) }

    @Test(timeout = 30000) fun missingFinalTranscriptionReconnectsWithoutResumingOrReplayingAudio() = runBlocking {
        for (wire in listOf("gemini", "grok", "openai")) {
            val provider=Provider();val events=CopyOnWriteArrayList<JSONObject>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val client=client()
            val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials(wire),providerSocketFactory=provider.factory,stallAfterMs=400)
            try {
                voice.start();await { events.any { it.optString("state")=="listening" } }
                voice.talk();await { provider.inputs.size >= 2 }
                if(wire=="gemini") {
                    val inputSetups=provider.frames.mapNotNull { it.optJSONObject("setup") }.filter {
                        it.optJSONObject("systemInstruction")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")?.startsWith("Transcribe")==true
                    }
                    assertTrue(inputSetups.size>=2);assertTrue(inputSetups.none { it.has("sessionResumption") })
                }
            } finally { voice.close();scope.cancel();client.closeNative() }
        }
    }

    @Test(timeout = 12000) fun providerAnswerWithoutFinalTranscriptionDoesNotHideAStalledInput() = runBlocking {
        val provider=Provider();val events=CopyOnWriteArrayList<JSONObject>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val client=client()
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials("gemini"),providerSocketFactory=provider.factory,stallAfterMs=400)
        try {
            voice.start();await { events.any { it.optString("state")=="listening" } }
            voice.talk()
            val (socket,listener)=provider.inputs.first()
            listener.onMessage(socket,json("serverContent" to json("outputTranscription" to json("text" to "Unverified"),"turnComplete" to true)).toString())
            await { provider.inputs.size >= 2 }
            assertTrue(events.none { it.optString("role")=="assistant" && it.optString("text")=="Unverified" })
        } finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout = 8000) fun silenceAloneDoesNotReconnectTheInputProvider() = runBlocking {
        val provider=Provider();val events=CopyOnWriteArrayList<JSONObject>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val client=client()
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials("gemini"),providerSocketFactory=provider.factory,stallAfterMs=300)
        try { voice.start();await { events.any { it.optString("state")=="listening" } };delay(1200);assertEquals(1,provider.inputs.size) }
        finally { voice.close();scope.cancel();client.closeNative() }
    }

    @Test(timeout = 8000) fun sharingFramesKeepsPixelsOffTheSpeechProvider() = runBlocking {
        val provider=Provider();val events=CopyOnWriteArrayList<JSONObject>();val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default);val client=client()
        val voice=DirectVoice(client,"call","stored",scope,{}, { events+=it }, {},credentials=credentials("gemini"),providerSocketFactory=provider.factory)
        try {
            voice.start();await { events.any { it.optString("state")=="listening" } };voice.setVisualState("camera","starting")
            val frame=ByteArray(30000);frame[0]=0xff.toByte();frame[1]=0xd8.toByte();frame[2]=0xff.toByte()
            assertTrue(voice.image(frame,{ true }));assertTrue(voice.image(frame,{ true }))
            assertTrue(voice.pcm(speech) { true });assertTrue(voice.send(GeminiWire.endAudio()))
            await { provider.frames.any { it.optJSONObject("realtimeInput")?.has("audioStreamEnd")==true } }
            assertTrue(provider.frames.none { it.optJSONObject("realtimeInput")?.has("video")==true })
        } finally { voice.close();scope.cancel();client.closeNative() }
    }
}
