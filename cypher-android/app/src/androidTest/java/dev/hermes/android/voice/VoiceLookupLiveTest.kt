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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** Ordinary user requests, real providers and the production gateway. No tool-result fixtures. */
class VoiceLookupLiveTest {
    @get:Rule val rule=createAndroidComposeRule<CypherActivity>()
    @Test(timeout=300_000) fun grokSearchAndInbox() = exercise("grok")
    @Test(timeout=300_000) fun geminiSearchAndInbox() = exercise("gemini")

    private fun exercise(provider:String)=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cypherLiveService")=="1")
        require(args.getString("cypherNoHostAudio")=="1")
        require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val app=ApplicationProvider.getApplicationContext<CypherApplication>()
        require(app.packageName.endsWith(".debug")&&!app.calls.state.value.active)
        val auth=JSONObject(File(app.filesDir,"live-auth.json").readText(Charsets.UTF_8))
        val voiceStore=VoiceKeyStore(app)
        val previousVoiceSettings=voiceStore.settings()
        val providerSelection=voiceStore.settings(provider)
        lateinit var vm:CypherViewModel
        rule.runOnIdle { vm=ViewModelProvider(rule.activity)[CypherViewModel::class.java] }
        suspend fun await(ms:Long=45_000,condition:()->Boolean) { withTimeout(ms) { while(!condition()) delay(75) } }
        var client:CypherClient?=null;var session="";var call=""
        val web=AtomicInteger();val mail=AtomicInteger();val skills=AtomicInteger();val memory=AtomicInteger();val completedAfter=AtomicInteger()
        val tools=ConcurrentLinkedQueue<String>();val discoveries=ConcurrentLinkedQueue<String>()
        val callAdmissions=ConcurrentLinkedQueue<JSONObject>()
        val seenReceipts=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val mailTools=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val phases=JSONObject().put("provider",provider)
        try {
            voiceStore.setSelection(providerSelection)
            loginLiveTest(app, auth.getString("origin"), auth)
            withContext(Dispatchers.Main.immediate) { vm.connect(auth.getString("origin")) }.join()
            await { vm.ui.value.connected&&vm.client!=null }
            client=vm.client!!.detached()
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Voice lookup verification")) }.join()
            await { vm.chat.value.sessionId.isNotBlank()&&!vm.ui.value.newChatOpening }
            session=vm.chat.value.sessionId
            val original=requireNotNull(vm.client)
            val observed=original.http.newBuilder().addNetworkInterceptor { chain ->
                val request=chain.request()
                if(request.url.encodedPath=="/api/plugins/cypher/mobile/calls"&&request.method=="POST") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer);callAdmissions.add(JSONObject(buffer.readUtf8()))
                }
                check(request.url.pathSegments.last() !in setOf("jobs","callback","decision")) { "Read-only verification does not authorize delegated work or writes" }
                chain.proceed(request).also { response ->
                    if(response.isSuccessful) {
                        val path=request.url.encodedPath
                        if(path.endsWith("/tools")) {
                            discoveries.add(request.url.queryParameter("query").orEmpty())
                            JSONObject(response.peekBody(65536).string()).rows("tools").filter { it.optBoolean("read_only")&&it.optString("description").contains("Gmail",true) }.forEach { mailTools.add(it.getString("tool_id")) }
                        }
                        if(path.endsWith("/search") || path.endsWith("/skills") || path.endsWith("/memory") || path.contains("/tools/requests/") || path.endsWith("/tools/invoke")) {
                            val body=JSONObject(response.peekBody(131072).string())
                            if(path.endsWith("/search")&&body.optJSONArray("sources")?.length()?.let { it>0 }==true) {
                                completedAfter.set(app.calls.state.value.transcripts.count { it.role=="assistant"&&it.final });web.incrementAndGet()
                            }
                            if(path.endsWith("/skills")&&body.optString("revision").isNotBlank()&&body.optString("summary").isNotBlank()) {
                                completedAfter.set(app.calls.state.value.transcripts.count { it.role=="assistant"&&it.final });skills.incrementAndGet()
                            }
                            if(path.endsWith("/memory")&&body.opt("summary") is String&&body.optJSONArray("sources")?.length()?.let { it>0 }==true) {
                                phases.put("memory_context_chars",body.getString("summary").length)
                                completedAfter.set(app.calls.state.value.transcripts.count { it.role=="assistant"&&it.final });memory.incrementAndGet()
                            }
                            if(body.optString("status")=="completed"&&seenReceipts.add(body.optString("request_id"))) {
                                val name=body.optString("tool_name");tools.add(name)
                                val text=body.opt("result").toString()
                                if(body.optString("tool_id") in mailTools&&Regex("subject",RegexOption.IGNORE_CASE).findAll(text).count()>=5) {
                                    completedAfter.set(app.calls.state.value.transcripts.count { it.role=="assistant"&&it.final });mail.incrementAndGet()
                                }
                            }
                        }
                    }
                }
            }.build()
            rule.runOnIdle { CypherViewModel::class.java.getDeclaredField("client").apply { isAccessible=true }.set(vm,CypherClient(original.auth.copy(cookies=original.jar.snapshot()),null,null,observed){}) ; vm.tab("Chat") }
            phases.put("phase","connecting")
            val start=System.nanoTime()
            rule.onNodeWithTag("chat-call-xara").performClick()
            await(25_000) { app.calls.state.value.active&&app.calls.state.value.state in setOf("listening","speaking") }
            phases.put("ready_ms",(System.nanoTime()-start)/1_000_000)
            call=app.calls.state.value.callId
            assertEquals(provider,app.calls.state.value.voiceProvider)
            val admission=callAdmissions.singleOrNull().also { assertNotNull("The phone-key call admission was captured exactly once",it) }!!
            assertEquals(provider,admission.getString("voice_provider"));assertTrue(admission.optBoolean("client_key"))
            assertFalse(admission.has("api_key")||admission.has("token"))
            await { app.calls.state.value.transcripts.any { it.role=="assistant"&&it.final } }
            val direct=CypherCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }.get(requireNotNull(app.calls.service)) as DirectVoice
            val callCredentials=DirectVoice::class.java.getDeclaredField("credentials").apply { isAccessible=true }.get(direct) as dev.hermes.android.data.VoiceSessionCredentials
            assertEquals(providerSelection.provider,callCredentials.provider)
            assertEquals(providerSelection.voice,callCredentials.voice)
            assertEquals(providerSelection.personality,callCredentials.personality)
            phases.put("phone_key_admission",true).put("client_key",true).put("provider_voice",callCredentials.voice).put("provider_personality",callCredentials.personality)
            suspend fun drainSpeech() {
                val audio=CypherCallService::class.java.getDeclaredField("pcm").apply { isAccessible=true }.get(requireNotNull(app.calls.service)) as NativePcm
                val audioLock=NativePcm::class.java.getDeclaredField("lock").apply { isAccessible=true }.get(audio)
                val queue=NativePcm::class.java.getDeclaredField("queue").apply { isAccessible=true }.get(audio) as PcmQueue
                val bytes=PcmQueue::class.java.getDeclaredField("bytes").apply { isAccessible=true }
                val ledger=NativePcm::class.java.getDeclaredField("ledger").apply { isAccessible=true }.get(audio) as PlaybackLedger
                var quietSince=0L
                await(90000) {
                    check(app.calls.state.value.error.isBlank()) { app.calls.state.value.error }
                    val empty=synchronized(audioLock) { synchronized(queue) { bytes.getInt(queue)==0 } && ledger.pendingBytes==0 }
                    if(!empty) { quietSince=0;false } else {
                        val now=System.nanoTime();if(quietSince==0L) quietSince=now
                        now-quietSince>1_500_000_000L
                    }
                }
            }
            // Transcript completion can precede actual speech playback. Test ordinary turn-taking.
            drainSpeech()
            for((kind,prompt) in listOf("web" to "Search the web for the latest Hermes Agent release. Give a brief answer and a source.",
                "mail" to "Read the subject lines of my five most recent inbox emails.",
                "skills" to "Find an enabled Hermes skill for web research and read its instructions. Tell me its name briefly; do not execute the workflow.",
                "memory" to "Look up my evidence-verification preferences in Hermes memory, then summarize what you found in one sentence.")) {
                phases.put("phase",kind)
                val started=System.nanoTime();val counter=when(kind) { "web" -> web; "mail" -> mail; "skills" -> skills; else -> memory };val prior=counter.get()
                assertTrue(direct.send(json("clientContent" to json("turns" to JSONArray().put(json("role" to "user","parts" to JSONArray().put(json("text" to prompt)))),"turnComplete" to true))))
                await(75_000) { check(app.calls.state.value.toolApprovals.isEmpty()) { "A read-only request proposed a write" };counter.get()>prior }
                phases.put(kind+"_tool_ms",(System.nanoTime()-started)/1_000_000)
                await(60_000) { app.calls.state.value.transcripts.count { it.role=="assistant"&&it.final }>completedAfter.get() }
                phases.put("phase",kind+"-playback");drainSpeech()
            }
            phases.put("playback_drained",true).put("no_playback_overflow",app.calls.state.value.error.isBlank())
            rule.onNodeWithTag("xara-hang-up").performClick()
            await { !app.calls.state.value.active&&app.calls.service==null }
            val final=client!!.nativeRequest("/api/plugins/cypher/mobile/calls/$call")
            assertFalse(final.optBoolean("audio_available"));assertEquals("saved",final.getString("transcript_status"))
            phases.put("ordinary_web_verified",web.get()>0).put("ordinary_mail_verified",mail.get()>0).put("native_skill_read_verified",skills.get()>0).put("native_memory_read_verified",memory.get()>0)
                .put("tools",JSONArray(tools.distinct())).put("discovery_queries",JSONArray(discoveries.toList()))
                .put("call_id",call).put("session_id",session).put("transcript_saved",true).put("no_audio_saved",true).put("version_code",BuildConfig.VERSION_CODE)
            File(app.filesDir,"voice-lookup-$provider.json").writeText(phases.toString(),Charsets.UTF_8)
        } finally { withContext(NonCancellable) {
            phases.put("call_id",call).put("session_id",session).put("version_code",BuildConfig.VERSION_CODE)
            phases.put("call_error",app.calls.state.value.error.take(300))
            app.calls.hangup();withTimeoutOrNull(45000) { while(app.calls.service!=null) delay(100) }
            runCatching { voiceStore.setSelection(previousVoiceSettings) }.onFailure { phases.put("restore_error",it.javaClass.simpleName) }
            if(call.isNotBlank()) client?.nativeRequest("/api/plugins/cypher/mobile/calls/$call","DELETE")
            if(session.isNotBlank()) client?.nativeRpc("session.set_hidden",json("session_id" to session,"hidden" to true))
            File(app.filesDir,"voice-lookup-$provider-last-phase.json").writeText(phases.toString(),Charsets.UTF_8)
            client?.closeNative()
        } }
    }
}
