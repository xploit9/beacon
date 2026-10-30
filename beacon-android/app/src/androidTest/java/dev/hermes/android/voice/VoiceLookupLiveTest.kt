package dev.hermes.android.voice

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Real speech, native row receipts and presented replies; no replacement client or retained audio. */
class VoiceLookupLiveTest {
    @get:Rule val rule=createAndroidComposeRule<BeaconActivity>()
    @Test(timeout=360_000) fun grokUsesNativeHermesBackend() = exercise("grok")
    @Test(timeout=360_000) fun geminiUsesNativeHermesBackend() = exercise("gemini")

    private fun exercise(provider:String)=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService")=="1")
        require(args.getString("beaconNoHostAudio")=="1")
        require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val app=ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug")&&!app.calls.state.value.active)
        val authFile=File(app.filesDir,"live-auth.json")
        val auth=if(authFile.isFile) JSONObject(authFile.readText(Charsets.UTF_8)).also { check(authFile.delete()) } else null
        val voiceStore=VoiceKeyStore(app)
        val previous=voiceStore.settings()
        val selected=voiceStore.settings(provider)
        lateinit var vm:BeaconViewModel
        rule.runOnIdle { vm=ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        suspend fun await(ms:Long=90_000,condition:suspend ()->Boolean) { withTimeout(ms) { while(!condition()) delay(100) } }
        var client:BeaconClient?=null;var session="";var call="";var observedDirect:DirectVoice?=null
        var nativeTraceJob:Job?=null
        val nativeTrace=ConcurrentLinkedQueue<JSONObject>()
        val phases=JSONObject().put("provider",provider).put("fixture_source","synthetic private PCM16")
        val completed=mutableListOf<JSONObject>()
        try {
            voiceStore.setSelection(selected)
            if(auth!=null) {
                loginLiveTest(app,auth.getString("origin"),auth)
                withContext(Dispatchers.Main.immediate) { vm.connect(auth.getString("origin")) }.join()
            }
            await { vm.ui.value.connected&&vm.client!=null }
            client=requireNotNull(vm.client).detached()
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Native voice backend verification")) }.join()
            await { vm.chat.value.sessionId.isNotBlank()&&!vm.ui.value.newChatOpening }
            session=vm.chat.value.sessionId
            rule.runOnIdle { vm.tab("Chat") }
            phases.put("phase","connecting")
            rule.onNodeWithTag("chat-call-beacon").performClick()
            await(45_000) { app.calls.state.value.active&&app.calls.state.value.state in setOf("listening","speaking") }
            call=app.calls.state.value.callId
            assertEquals(provider,app.calls.state.value.voiceProvider)
            val context=client.nativeCallContext(call)
            assertEquals("hermes",context.getString("backend"))
            assertEquals(client.auth.profile,context.getString("profile"))
            assertEquals(session,context.getString("selected_session_id"))
            val service=requireNotNull(app.calls.service)
            val pcm=BeaconCallService::class.java.getDeclaredField("pcm").apply { isAccessible=true }.get(service) as NativePcm
            service.mute(true) // Keep the owner's gate muted too; routing callbacks may reapply it.
            assertTrue(app.calls.state.value.muted)
            phases.put("hardware_input_forwarding",false)
            val direct=BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }.get(service) as DirectVoice
            observedDirect=direct
            val callClient=DirectVoice::class.java.getDeclaredField("client").apply { isAccessible=true }.get(direct) as BeaconClient
            val gateway=BeaconClient::class.java.getDeclaredField("native").apply { isAccessible=true }.get(callClient)
            val cursorField=gateway.javaClass.getDeclaredField("cursor").apply { isAccessible=true }
            val connectionField=gateway.javaClass.getDeclaredField("connection").apply { isAccessible=true }
            val cursorLock=gateway.javaClass.getDeclaredField("cursorLock").apply { isAccessible=true }.get(gateway)
            val eventField=DirectVoice::class.java.getDeclaredField("nativeEvents").apply { isAccessible=true }
            val traceStarted=System.nanoTime()
            nativeTraceJob=launch(Dispatchers.Default) {
                var previous=""
                while(isActive) {
                    val current=connectionField.get(gateway)
                    val cursor=synchronized(cursorLock) { cursorField.get(gateway) }
                    val held=cursor?.javaClass?.getDeclaredField("connection")?.apply { isAccessible=true }?.get(cursor)
                    val failure=held?.javaClass?.getDeclaredField("failure")?.apply { isAccessible=true }?.get(held) as? Throwable
                    val message=failure?.message.orEmpty()
                    val category=when {
                        message.startsWith("Native event queue overflow") -> "event_overflow"
                        message.startsWith("Native frame exceeds") -> "frame_too_large"
                        message.startsWith("Native client connection closed") -> "client_closed"
                        message.startsWith("Native connection closed") -> "peer_closed"
                        message.startsWith("Native connection interrupted") -> "transport_failure"
                        message.isBlank() -> "none"
                        else -> "other"
                    }
                    val events=eventField.get(direct) as? Job
                    val row=json("connected" to (current!=null),"cursor" to (cursor!=null),"cursor_on_current" to (held!=null&&held===current),
                        "failure" to category,"cause" to failure?.cause?.javaClass?.simpleName.orEmpty(),"collector_active" to (events?.isActive==true))
                    val signature=row.toString()
                    if(signature!=previous&&nativeTrace.size<100) { previous=signature;row.put("at_ms",(System.nanoTime()-traceStarted)/1_000_000);nativeTrace.add(row) }
                    delay(100)
                }
            }
            suspend fun jobs()=client.nativeRequest("/api/plugins/beacon/mobile/calls/$call").rows("jobs")
            suspend fun drainSpeech() {
                val queue=NativePcm::class.java.getDeclaredField("queue").apply { isAccessible=true }.get(pcm) as PcmQueue
                val ledger=NativePcm::class.java.getDeclaredField("ledger").apply { isAccessible=true }.get(pcm) as PlaybackLedger
                val lock=NativePcm::class.java.getDeclaredField("lock").apply { isAccessible=true }.get(pcm)
                val bytes=PcmQueue::class.java.getDeclaredField("bytes").apply { isAccessible=true }
                var quiet=0L
                await {
                    val empty=synchronized(lock) { synchronized(queue) { bytes.getInt(queue)==0 }&&ledger.pendingBytes==0 }
                    if(!empty) { quiet=0;false } else {
                        val now=System.nanoTime();if(quiet==0L) quiet=now
                        now-quiet>1_500_000_000L
                    }
                }
            }
            withTimeoutOrNull(35_000) { await { app.calls.state.value.transcripts.any { it.role=="assistant"&&it.final } || app.calls.state.value.error.isNotBlank() } }
            phases.put("greeting_spoken",app.calls.state.value.transcripts.any { it.role=="assistant"&&it.final })
            drainSpeech()
            assertTrue("No native voice work before owner speech",jobs().isEmpty())
            val rate=if(provider=="openai") 24000 else 16000
            suspend fun stream(bytes:ByteArray) {
                var offset=0
                while(offset<bytes.size) {
                    val end=minOf(offset+rate*2/50,bytes.size)
                    check(direct.pcm(bytes.copyOfRange(offset,end)) { true }) { "Speech input stopped before the fixture finished" }
                    offset=end;delay(20)
                }
            }
            for((kind,purpose) in listOf("ordinary" to "brief arithmetic question","tools" to "read-only skill listing","memory" to "read-only preferred-name recall")) {
                phases.put("phase",kind+"_input").put(kind+"_fixture_purpose",purpose)
                val prior=jobs().map { it.getString("job_id") }.toSet()
                val priorUsers=app.calls.state.value.transcripts.filter { it.role=="user"&&it.final }.map { it.eventId }.toSet()
                val priorAssistant=app.calls.state.value.transcripts.filter { it.role=="assistant"&&it.final }.map { it.eventId }.toSet()
                val file=File(app.filesDir,"voice-lookup-$provider-$kind.pcm")
                val fixture=try {
                    check(file.isFile&&file.length() in 2L..(rate*2L*60)&&file.length()%2L==0L) { "A bounded synthetic PCM16 fixture is required" }
                    file.readBytes()
                } finally { if(file.exists()) check(file.delete()) }
                try { stream(ByteArray(rate*2*600/1000));stream(fixture);stream(ByteArray(rate*2*2200/1000)) }
                finally { fixture.fill(0) }
                phases.put("phase",kind+"_native_admission")
                var job:JSONObject?=null
                await { job=jobs().firstOrNull { it.getString("job_id") !in prior };job!=null }
                val accepted=app.calls.state.value.transcripts.lastOrNull { it.role=="user"&&it.final&&it.eventId !in priorUsers }
                assertNotNull("A native voice job must follow accepted owner speech",accepted)
                val cues=when(kind) {
                    "ordinary" -> listOf(Regex("(?i)\\b(?:two|2)\\b"),Regex("(?i)\\b(?:three|3)\\b"))
                    "tools" -> listOf(Regex("(?i)\\bskills?\\b"))
                    else -> listOf(Regex("(?i)\\b(?:memory|remember)\\b"),Regex("(?i)\\bname\\b"))
                }
                assertTrue("Speech keeps the fixture purpose",cues.all { it.containsMatchIn(accepted!!.text) })
                assertEquals(session,job!!.getString("stored_session_id"))
                assertTrue(job!!.getString("runtime_session_id").isNotBlank())
                assertTrue(job!!.getLong("user_row_id")>0)
                val id=job!!.getString("job_id")
                phases.put("phase",kind+"_native_completion")
                await {
                    job=jobs().first { it.getString("job_id")==id }
                    val state=job!!.getString("status")
                    check(state !in setOf("failed","cancelled")&&!(state=="uncertain"&&!job!!.optBoolean("will_continue"))) {
                        "Native voice result is $state (${job!!.optString("error_code")})"
                    }
                    state in setOf("done","completed")
                }
                val result=job!!.getString("result")
                assertTrue(result.isNotBlank())
                phases.put(kind+"_native_completed",true).put(kind+"_accepted_asr_before_job",true)
                phases.put("phase",kind+"_spoken_reply")
                await(45_000) {
                    val spoken=app.calls.state.value.transcripts.filter { it.role=="assistant"&&it.final&&it.eventId !in priorAssistant }.joinToString(" ") { it.text }
                    normalizeVoiceText(spoken).contains(normalizeVoiceText(result))
                }
                phases.put(kind+"_spoken",true);completed+=job!!
                drainSpeech()
            }
            app.calls.hangup();await { !app.calls.state.value.active&&app.calls.service==null }
            val final=client.nativeRequest("/api/plugins/beacon/mobile/calls/$call")
            assertTrue(final.optBoolean("closed"));assertFalse(final.optBoolean("audio_available"))
            assertEquals("saved",final.getString("transcript_status"))
            phases.put("native_job_count",completed.size).put("same_conversation",completed.all { it.getString("stored_session_id")==session })
                .put("transcript_saved",true).put("no_audio_saved",true).put("version_code",BuildConfig.VERSION_CODE)
            File(app.filesDir,"voice-lookup-$provider.json").writeText(phases.toString(),Charsets.UTF_8)
        } finally { withContext(NonCancellable) {
            nativeTraceJob?.cancelAndJoin()
            phases.put("native_connection_trace",org.json.JSONArray(nativeTrace.toList()))
            observedDirect?.let { direct ->
                val callClient=DirectVoice::class.java.getDeclaredField("client").apply { isAccessible=true }.get(direct) as BeaconClient
                val gateway=BeaconClient::class.java.getDeclaredField("native").apply { isAccessible=true }.get(callClient)
                val cursor=gateway.javaClass.getDeclaredField("cursor").apply { isAccessible=true }.get(gateway)
                val connection=cursor?.javaClass?.getDeclaredField("connection")?.apply { isAccessible=true }?.get(cursor)
                val failure=connection?.javaClass?.getDeclaredField("failure")?.apply { isAccessible=true }?.get(connection) as? Throwable
                val message=failure?.message.orEmpty()
                phases.put("native_connection_failure",when {
                    message.startsWith("Native event queue overflow") -> "event_overflow"
                    message.startsWith("Native frame exceeds") -> "frame_too_large"
                    message.startsWith("Native client connection closed") -> "client_closed"
                    message.startsWith("Native connection closed") -> "peer_closed"
                    message.startsWith("Native connection interrupted") -> "transport_failure"
                    message.isBlank() -> "none"
                    else -> "other"
                }).put("native_failure_cause",failure?.cause?.javaClass?.simpleName.orEmpty())
                val events=DirectVoice::class.java.getDeclaredField("nativeEvents").apply { isAccessible=true }.get(direct) as? Job
                phases.put("native_event_collector_active",events?.isActive==true)
            }
            val removed=listOf("ordinary","tools","memory").map { kind -> File(app.filesDir,"voice-lookup-$provider-$kind.pcm").let { !it.exists()||it.delete() } }.all { it }
            phases.put("fixtures_removed",removed).put("completed_jobs",completed.size)
            app.calls.hangup();withTimeoutOrNull(60_000) { while(app.calls.service!=null) delay(100) }
            runCatching { voiceStore.setSelection(previous) }
            if(call.isNotBlank()) runCatching { client?.nativeRequest("/api/plugins/beacon/mobile/calls/$call","DELETE") }
            if(session.isNotBlank()) runCatching { client?.nativeRpc("session.set_hidden",json("session_id" to session,"hidden" to true)) }
            phases.put("warning_present",app.calls.state.value.error.isNotBlank())
            File(app.filesDir,"voice-lookup-$provider-last-phase.json").writeText(phases.toString(),Charsets.UTF_8)
            client?.closeNative();check(removed) { "Synthetic PCM fixture cleanup failed" }
        } }
    }
}
