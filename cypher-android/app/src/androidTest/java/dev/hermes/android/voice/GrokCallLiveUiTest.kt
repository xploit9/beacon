package dev.hermes.android.voice

import android.app.NotificationManager
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

/** Opt-in real Grok/service/gateway journey on an owned silent emulator. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class GrokCallLiveUiTest {
    @get:Rule val rule=createAndroidComposeRule<CypherActivity>(effectContext = kotlinx.coroutines.test.StandardTestDispatcher())
    @Test(timeout=480_000) fun modelChoiceDirectToolBackgroundAndHangup()=runBlocking<Unit> {
        val args=InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cypherLiveService")=="1")
        require(args.getString("cypherNoHostAudio")=="1")
        require(android.os.Build.FINGERPRINT.contains("generic")||android.os.Build.MODEL.contains("sdk"))
        val app=ApplicationProvider.getApplicationContext<CypherApplication>()
        require(app.packageName.endsWith(".debug")&&!app.calls.state.value.active)
        val auth=JSONObject(File(app.filesDir,"live-auth.json").readText(Charsets.UTF_8))
        val voiceStore=VoiceKeyStore(app)
        val previousProvider=voiceStore.settings().provider
        val previousSelections=listOf("gemini","grok","openai").map(voiceStore::settings)
        lateinit var vm:CypherViewModel
        rule.runOnIdle { vm=ViewModelProvider(rule.activity)[CypherViewModel::class.java] }
        var reader:CypherClient?=null;var session="";var runtime="";var call=""
        val callAdmissions=ConcurrentLinkedQueue<JSONObject>()
        val requests=ConcurrentLinkedQueue<String>();var started=0L
        val rejectGemini=java.util.concurrent.atomic.AtomicBoolean(false)
        var phase="restore"
        var primary:Throwable?=null
        suspend fun await(ms:Long=30000,condition:()->Boolean) {
            try { withTimeout(ms) { while(true) {
                rule.mainClock.advanceTimeBy(32);rule.waitForIdle()
                check(app.calls.state.value.error.isBlank()) { app.calls.state.value.error }
                if(condition()) { rule.mainClock.advanceTimeBy(32);rule.waitForIdle();break }
                delay(100)
            } } }
            catch(error:TimeoutCancellationException) { throw AssertionError("Grok QA phase=$phase authenticated=${vm.ui.value.authenticated} connected=${vm.ui.value.connected} loading=${vm.ui.value.loading} page=${vm.ui.value.morePage} call=${app.calls.state.value.state}/${app.calls.state.value.active}/${app.calls.state.value.voiceProvider} visible=${app.calls.uiVisible} bound=${app.calls.state.value.conversationScope==vm.authenticatedScope()} service=${app.calls.service!=null} handoff=${vm.voiceHandoff.value?.media}/${vm.voiceHandoff.value?.targetCall?.isNotBlank()} notice=${vm.ui.value.error} voice=${voiceStore.settings()}",error) }
        }
        try {
            await { !vm.ui.value.restoringSession }
            phase="login"
            loginLiveTest(app, BuildConfig.DEFAULT_ORIGIN, auth)
            withContext(Dispatchers.Main.immediate) { vm.connect(BuildConfig.DEFAULT_ORIGIN) }.join()
            await { vm.ui.value.authenticated&&vm.ui.value.connected&&!vm.ui.value.loading }
            (CypherViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible=true }.get(vm) as? Job)?.join()
            reader=requireNotNull(vm.client).detached()
            phase="settings-load"
            withContext(Dispatchers.Main.immediate) { vm.tab("More");vm.more("Cypher") }
            rule.waitForIdle()
            rule.onNodeWithTag("voice-model-grok").performScrollTo().performClick()
            phase="settings-save"
            await { voiceStore.settings().provider=="grok" }
            rule.onNodeWithTag("voice-model-grok").assertIsSelected()
            val grokBefore=voiceStore.settings("grok")
            val targetVoice=if(grokBefore.voice=="ara") "eve" else "ara"
            val targetPersonality=if(grokBefore.personality=="warm") "balanced" else "warm"
            rule.onNodeWithText("Voice: ${grokBefore.voice}").performScrollTo().performClick()
            rule.onNodeWithText(targetVoice).performClick()
            await { voiceStore.settings("grok").voice==targetVoice }
            rule.onNodeWithText("Personality: ${grokBefore.personality.replaceFirstChar { it.uppercase() }}").performScrollTo().performClick()
            rule.onNodeWithText(targetPersonality.replaceFirstChar { it.uppercase() }).performClick()
            await { voiceStore.settings("grok").personality==targetPersonality }
            phase="create-chat"
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Grok call verification")) }.join()
            await { vm.chat.value.sessionId.isNotBlank()&&!vm.ui.value.newChatOpening }
            session=vm.chat.value.sessionId;runtime=reader!!.nativeSession(session).runtimeSessionId
            File(app.filesDir,"grok-call-owned.json").writeText(json("session_id" to session).toString(),Charsets.UTF_8)
            val original=requireNotNull(vm.client)
            val traced=original.http.newBuilder().addNetworkInterceptor { chain ->
                val request=chain.request()
                if(request.url.pathSegments.last()=="calls"&&request.method=="POST") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer)
                    val body=JSONObject(buffer.readUtf8())
                    callAdmissions.add(body)
                    if(rejectGemini.get()&&body.optString("voice_provider")=="gemini") throw ApiFailure(503,"QA simulated Gemini admission unavailable")
                }
                var invocation: String?=null
                if(request.url.pathSegments.last()=="invoke") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
                    val arguments=body.getJSONObject("arguments")
                    require(arguments.optString("url")=="https://example.com"&&arguments.keys().asSequence().all { it in setOf("url","extraction_type","timeout","main_content_only") }) { "QA permits only the inspected example.com read" }
                    invocation=body.getString("request_id")
                }
                require(request.url.pathSegments.last() !in setOf("jobs","callback","decision")) { "No delegated work, write approval or callback in this test" }
                chain.proceed(request).also { response ->
                    if(response.isSuccessful) invocation?.let(requests::add)
                }
            }.build()
            rule.runOnIdle { CypherViewModel::class.java.getDeclaredField("client").apply { isAccessible=true }.set(vm,CypherClient(original.auth.copy(cookies=original.jar.snapshot()),null,null,traced){}) ; vm.tab("Chat") }
            rule.onNodeWithTag("chat-call-xara").performClick()
            phase="connect-grok"
            await(45000) { app.calls.state.value.active&&app.calls.state.value.state=="listening"&&app.calls.state.value.transcripts.any { it.role=="assistant" } }
            call=app.calls.state.value.callId
            assertEquals("grok",app.calls.state.value.voiceProvider)
            val admission=callAdmissions.firstOrNull { it.optString("voice_provider")=="grok" }
            assertNotNull("The phone-key Grok admission must be captured",admission)
            assertTrue(admission!!.optBoolean("client_key"))
            assertFalse(admission.has("api_key")||admission.has("token"))
            for(control in listOf("xara-camera","cypher-share-screen")) {
                rule.onNodeWithTag(control).assertIsEnabled().performClick()
                rule.onNodeWithTag("cypher-cancel-vision-switch").performClick()
                assertEquals(call,app.calls.state.value.callId)
                assertEquals("grok",app.calls.state.value.voiceProvider)
                assertTrue(app.calls.state.value.active)
            }
            val service=requireNotNull(app.calls.service)
            val direct=CypherCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }.get(service) as DirectVoice
            val callCredentials=DirectVoice::class.java.getDeclaredField("credentials").apply { isAccessible=true }.get(direct) as dev.hermes.android.data.VoiceSessionCredentials
            assertEquals("grok",callCredentials.provider)
            assertEquals(targetVoice,callCredentials.voice)
            assertEquals(targetPersonality,callCredentials.personality)
            val sessionUpdate=GrokWire().outbound(GeminiWire.setup(callCredentials.model,callCredentials.voice,null,
                JSONObject().put("personality_instruction",callCredentials.personalityInstruction))).single().getJSONObject("session")
            assertEquals(targetVoice,sessionUpdate.getString("voice"))
            assertTrue(sessionUpdate.getString("instructions").contains(callCredentials.personalityInstruction))
            started=System.nanoTime()
            phase="direct-tool"
            val question="Use discover_tools to find read-only scrapling_fetch, then invoke_tool to fetch https://example.com with extraction_type text. Tell me the result. Do not delegate, send mail, write anything or call back."
            val turn=json("role" to "user","parts" to JSONArray().put(json("text" to question)))
            assertTrue(direct.send(json("clientContent" to json("turns" to JSONArray().put(turn),"turnComplete" to true))))
            await(60000) { requests.isNotEmpty() }
            val gateway=NativeToolGateway(reader!!,call)
            var receipt=JSONObject()
            withTimeout(60000) { do { receipt=gateway.status(requests.first());if(receipt.string("status") in setOf("ready","running")) delay(200) else break } while(true) }
            assertEquals("completed",receipt.string("status"))
            assertTrue(receipt.get("result").toString().contains("documentation examples",true))
            val toolMs=(System.nanoTime()-started)/1000000
            phase="spoken-tool-result"
            await(30000) { app.calls.state.value.transcripts.any { it.role=="assistant"&&it.final&&it.text.contains("documentation",true) } }
            voiceStore.setSelection(voiceStore.settings("gemini"))
            assertEquals("grok",app.calls.state.value.voiceProvider)
            InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            phase="background"
            await { app.calls.state.value.minimized }
            await { rule.onAllNodesWithTag("cypher-call-bubble").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            rule.onNodeWithTag("cypher-call-bubble").performClick()
            rule.onNodeWithTag("cypher-bubble-hide").performClick()
            delay(1400)
            rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
            assertTrue(app.calls.state.value.active)
            val notifications=app.getSystemService(NotificationManager::class.java)
            val note=notifications.activeNotifications.single { it.id==41 }.notification
            assertEquals(dev.hermes.android.R.drawable.ic_cypher_voice,note.smallIcon.resId)
            assertTrue(note.extras.getBoolean("android.requestPromotedOngoing"))
            val hangup=note.extras.getParcelable<android.app.PendingIntent>(android.app.Notification.EXTRA_HANG_UP_INTENT)
            requireNotNull(hangup).send()
            phase="hangup"
            await(45000) { !app.calls.state.value.active&&app.calls.service==null }
            assertTrue(notifications.activeNotifications.none { it.id==41 })
            val archive=reader!!.nativeRequest("/api/plugins/cypher/mobile/calls/$call")
            assertFalse(archive.optBoolean("audio_available"))
            assertEquals("disabled",archive.getString("recording_status"))
            assertTrue(archive.getString("transcript_status") in setOf("saved","partial"))
            File(app.filesDir,"grok-call-proof.json").writeText(json("provider" to "grok","call_id" to call,"model_choice_ui" to true,"bound_during_switch" to true,"direct_tool_completed" to true,"prompt_to_tool_ms" to toolMs,"background_preserved" to true,"dismiss_preserved" to true,"system_hangup_closed" to true,"audio_not_saved" to true,"transcript_status" to archive.getString("transcript_status"),"version_code" to BuildConfig.VERSION_CODE).toString(),Charsets.UTF_8)
            rule.activityRule.scenario.onActivity { it.startActivity(android.content.Intent(it.intent).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
            await { app.calls.uiVisible }
            rule.waitForIdle()
            (CypherViewModel::class.java.getDeclaredField("recoveryJob").apply { isAccessible=true }.get(vm) as? Job)?.join()
            (CypherViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible=true }.get(vm) as? Job)?.join()
            val handoffs=JSONArray()
            for(media in listOf("camera","screen","failure")) {
                phase="handoff-$media-start"
                voiceStore.setSelection(voiceStore.settings("grok"))
                withContext(Dispatchers.Main.immediate) { vm.tab("Chat");app.calls.start(requireNotNull(vm.client),session) }
                await(45000) { app.calls.state.value.active&&app.calls.state.value.voiceProvider=="grok"&&app.calls.state.value.state in setOf("listening","speaking") }
                val oldId=app.calls.state.value.callId;val oldService=requireNotNull(app.calls.service)
                call=oldId
                val oldVoice=CypherCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }.get(oldService) as DirectVoice
                val oldHttp=DirectVoice::class.java.getDeclaredField("providerHttp").apply { isAccessible=true }.get(oldVoice) as okhttp3.OkHttpClient
                rejectGemini.set(media=="failure")
                rule.onNodeWithTag(if(media=="screen") "cypher-share-screen" else "xara-camera").performClick()
                rule.onNodeWithTag("cypher-confirm-vision-switch").performClick()
                phase="handoff-$media-connect"
                if(media=="failure") {
                    withTimeout(45000) { while(vm.voiceHandoff.value!=null||app.calls.service!=null) delay(100) }
                    assertFalse(app.calls.state.value.active)
                    assertTrue(oldService.destroyed.isCompleted&&oldHttp.dispatcher.executorService.isShutdown)
                    handoffs.put(json("media" to media,"old_closed" to true,"no_replacement" to true))
                    rejectGemini.set(false)
                    continue
                }
                if(media=="screen") {
                    await(45000) { app.calls.state.value.active&&app.calls.state.value.voiceProvider=="gemini"&&app.calls.state.value.state in setOf("listening","speaking") }
                    rule.waitForIdle()
                    ScreenConsentDriver.grant()
                }
                await(60000) { app.calls.state.value.active&&app.calls.state.value.voiceProvider=="gemini"&&app.calls.state.value.callId!=oldId&&(if(media=="camera") app.calls.state.value.cameraSharing else app.calls.state.value.screenSharing) }
                call=app.calls.state.value.callId
                assertEquals(session,app.calls.state.value.conversationScope?.session)
                assertTrue("Grok must be fully destroyed before Gemini capture",oldService.destroyed.isCompleted)
                assertTrue(oldHttp.dispatcher.executorService.isShutdown)
                assertEquals(0,oldHttp.dispatcher.runningCallsCount());assertEquals(0,oldHttp.connectionPool.connectionCount())
                assertTrue(reader!!.nativeRequest("/api/plugins/cypher/mobile/calls/$oldId").optBoolean("closed"))
                assertEquals("grok",voiceStore.settings().provider)
                handoffs.put(json("media" to media,"old_closed" to true,"old_socket_closed" to true,"same_conversation" to true,"gemini_capture_sent" to true,"default_unchanged" to true))
                rule.onNodeWithTag("xara-hang-up").performClick()
                await(45000) { !app.calls.state.value.active&&app.calls.service==null }
            }
            File(app.filesDir,"vision-handoff-proof.json").writeText(json("cancel_preserved_grok" to true,"handoffs" to handoffs,"no_active_recorder" to app.getSystemService(android.media.AudioManager::class.java).activeRecordingConfigurations.isEmpty(),"version_code" to BuildConfig.VERSION_CODE).toString(),Charsets.UTF_8)
            File(app.filesDir,"voice-settings-proof.json").writeText(json("voice_and_personality_ui_saved" to true,"grok_connected_with_selected_settings" to true,"phone_key_admission" to true,"client_key" to admission.optBoolean("client_key"),"phone_voice" to callCredentials.voice,"phone_personality" to callCredentials.personality,"session_update_voice" to sessionUpdate.getString("voice"),"personality_instructions_sent" to sessionUpdate.getString("instructions").contains(callCredentials.personalityInstruction),"version_code" to BuildConfig.VERSION_CODE).toString(),Charsets.UTF_8)
        } catch(error:Throwable) { primary=error;throw error }
        finally { withContext(NonCancellable) {
            val cleanup=mutableListOf<Throwable>()
            suspend fun attempt(block:suspend ()->Unit) { try { block() } catch(error:Throwable) { cleanup+=error } }
            attempt { withContext(Dispatchers.Main.immediate) { app.calls.hangup() };withTimeout(45000) { while(app.calls.service!=null) delay(100) } }
            previousSelections.forEach { selection -> attempt { voiceStore.setSelection(selection) } }
            previousSelections.firstOrNull { it.provider==previousProvider }?.let { selection -> attempt { voiceStore.setSelection(selection) } }
            attempt { if(call.isNotBlank()) reader?.nativeRequest("/api/plugins/cypher/mobile/calls/$call","DELETE") }
            attempt { if(runtime.isNotBlank()) reader?.nativeRpc("session.close",json("session_id" to runtime)) }
            attempt { if(session.isNotBlank()) reader?.nativeRpc("session.set_hidden",json("session_id" to session,"hidden" to true)) }
            attempt { reader?.closeNative() }
            if(cleanup.isNotEmpty()) { val failure=AssertionError("Grok verification cleanup incomplete");cleanup.forEach(failure::addSuppressed);if(primary!=null) primary!!.addSuppressed(failure) else throw failure }
        } }
    }
}
