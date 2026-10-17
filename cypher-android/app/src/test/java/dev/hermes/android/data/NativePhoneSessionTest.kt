package dev.hermes.android.data

import dev.hermes.android.BuildConfig
import dev.hermes.android.core.*
import dev.hermes.android.voice.nativeVoiceAgentReady
import dev.hermes.android.voice.nativeVoiceSessionReady
import dev.hermes.android.voice.submitNativeWhenIdle
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class NativePhoneSessionTest {
    private val scope = Scope("https://native.example", "owner", "default")
    @Test fun createAndAllResumeShapesPreserveAuthoritativeIdentity() {
        val id = "20261014_125947_0d8269"
        for (fields in listOf(json("stored_session_id" to id), json("session_key" to id, "resumed" to id), json("stored_session_id" to id, "session_key" to id))) {
            val binding = nativeSessionBinding(scope, fields.put("session_id", "android-runtime"))
            assertEquals(id, binding.storedSessionId)
            assertEquals("android-runtime", binding.runtimeSessionId)
            assertEquals(scope, binding.scope)
        }
    }
    @Test fun missingConflictingAndUnsafeIdentitiesCannotBind() {
        for (fields in listOf(json("resumed" to "title"), json("stored_session_id" to "one", "session_key" to "two"), json("session_key" to "../other"), json("session_key" to ""))) {
            assertTrue(runCatching { nativeSessionBinding(scope, fields.put("session_id", "runtime")) }.isFailure)
        }
        assertTrue(runCatching { nativeSessionBinding(scope, json("session_key" to "stored", "session_id" to "")) }.isFailure)
    }

    /** Production Android client against the owner's server, enabled only in the coordinated live slot. */
    @Test(timeout = 180_000) fun androidClientCreatesResumesAndSubmitsVoiceBridge() = runBlocking<Unit> {
        assumeTrue(System.getenv("CYPHER_LIVE_PHONE_SESSION") == "1")
        val path = System.getenv("CYPHER_LIVE_CREDENTIAL_FILE") ?: error("Private credential file required")
        val origin = System.getenv("CYPHER_LIVE_ORIGIN").orEmpty()
        require(origin.startsWith("https://") && origin == BuildConfig.DEFAULT_ORIGIN)
        val credentials = JSONObject(File(path).readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val client = CypherClient(AuthContext(origin, emptyList()))
        client.login(credentials.getString("password"), username = credentials.getString("username"))
        var callId=""; var stored=""; var runtime=""
        try {
            val created = client.post("/api/session/new", json("title" to "Cypher Android voice bridge verification"))
            stored = created.getString("session_id")
            val freshRuntime = created.getString("runtime_session_id"); runtime = freshRuntime
            val fresh = client.get("/api/session", mapOf("session_id" to stored))
            assertEquals(stored, fresh.getString("session_id"))
            assertTrue(fresh.has("voice_transcripts_available"))
            val reserved=client.nativeRequest("/api/plugins/cypher/mobile/calls","POST",json("conversation_id" to stored,"profile" to client.auth.profile))
            callId=reserved.getString("call_id")
            assertEquals(stored,reserved.getString("conversation_id"))
            val context=client.nativeCallContext(callId)
            assertEquals(stored,context.getString("selected_session_id"))
            assertEquals(client.auth.profile,context.getString("profile"))
            val binding=client.nativeSession(stored)
            assertEquals(stored,binding.storedSessionId)
            client.nativeRpc("session.history",json("session_id" to binding.runtimeSessionId))
            kotlinx.coroutines.withTimeout(15000) {
                while(!nativeVoiceSessionReady(client.nativeRpc("session.active_list"),binding.runtimeSessionId,stored)) kotlinx.coroutines.delay(250)
            }
            val admitted=client.nativeRpc("session.resume",json("session_id" to stored,"omit_messages" to true,"close_on_disconnect" to false))
            assertEquals(binding,nativeSessionBinding(client.scope,admitted))
            assertTrue(nativeVoiceAgentReady(admitted))
            val ack=submitNativeWhenIdle({true},{
                val rows=client.nativeRpc("session.active_list").rows("sessions")
                rows.firstOrNull { it.string("id")==binding.runtimeSessionId }?.string("status")=="idle"
            },{
                client.nativeRpc("prompt.submit",json("session_id" to binding.runtimeSessionId,"text" to "Use your terminal tool to calculate 17*23, then reply with the result only.","surface" to "voice-live","voice_context" to "Owner requested a safe terminal calculation through the Android voice bridge.","queued" to true))
            },{kotlinx.coroutines.delay(250)},maxProbes=60,maxWaitMs=15000)
            assertTrue(ack.string("status") in setOf("streaming","queued"))
            assertTrue(ack.has("user_row_id")&&!ack.isNull("user_row_id"))
            val requestId=java.util.UUID.randomUUID().toString()
            val linked=client.nativeRequest("/api/plugins/cypher/mobile/calls/$callId/jobs","POST",json("stored_session_id" to stored,"runtime_session_id" to binding.runtimeSessionId,"request_id" to requestId,"user_row_id" to ack.getLong("user_row_id"),"title" to "Android voice bridge calculation","callback" to false))
            val jobId=linked.getString("job_id")
            client.closeNative()
            var done=false
            repeat(90) {
                if(!done) {
                    kotlinx.coroutines.delay(1000)
                    val status=client.nativeRequest("/api/plugins/cypher/mobile/calls/$callId")
                    val job=status.rows("jobs").firstOrNull { it.string("job_id")==jobId }
                    assertTrue(job?.string("status") !in setOf("failed","cancelled","needs_input"))
                    done=job?.string("status")=="done"&&job.string("result").contains("391")
                }
            }
            assertTrue("Owned voice job must complete through actual native tool execution",done)
            val resumed=client.get("/api/session",mapOf("session_id" to stored))
            assertEquals(stored,resumed.getString("stored_session_id"))
            assertTrue(resumed.getBoolean("voice_transcripts_available"))
            assertTrue(resumed.rows("messages").any { it.string("role")=="tool"&&it.string("name").contains("terminal") })
            assertTrue(resumed.rows("messages").any { it.string("role")=="assistant"&&it.string("content",it.string("text")).contains("391") })
            assertTrue(freshRuntime.isNotBlank())
        } finally {
            try { if(callId.isNotBlank()) client.nativeRequest("/api/plugins/cypher/mobile/calls/$callId/finalize","POST",json("last_user_sequence" to -1,"last_assistant_sequence" to -1)) }
            finally {
                try {
                    if(runtime.isNotBlank()) client.nativeRpc("session.close",json("session_id" to runtime))
                    if(callId.isNotBlank()) client.nativeRpc("session.set_hidden",json("session_id" to stored,"hidden" to true))
                } finally { client.closeNative() }
            }
        }
    }
}
