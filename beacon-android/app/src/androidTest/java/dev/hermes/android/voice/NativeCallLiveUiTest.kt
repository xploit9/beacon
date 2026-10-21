package dev.hermes.android.voice

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.data.VoicePreferences
import dev.hermes.android.data.VoiceSessionCredentials
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Real Activity -> VM -> CoreTelecom service -> Google + projection + native jobs. */
class NativeCallLiveUiTest {
    @get:Rule val rule=createAndroidComposeRule<BeaconActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val app get()=ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val failures=ConcurrentLinkedQueue<String>()
    private var phase="startup"
    private fun capture(name:String) {
        rule.waitForIdle()
        val image=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val folder=File(app.filesDir,"assistant-next").apply { mkdirs() }
        File(folder,"$name.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        image.recycle()
    }

    private val reportedResult = Regex("""\b(?:391|three hundred(?: and)? ninety[- ]one)\b""", RegexOption.IGNORE_CASE)
    private fun reports391(text:String) = reportedResult.containsMatchIn(text)
    private suspend fun await(timeout:Long=30_000,condition:()->Boolean)=withTimeout(timeout) {
        while(!condition()) { check(failures.isEmpty()) { "Actual call service failed; inspect private CallState diagnostics" };delay(50) }
    }
    @Test(timeout=720_000) fun realServiceSharesScreenRunsNativeTerminalAndKeepsCaptionsAndArchive()=runBlocking<Unit> {
        val arguments=InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("beaconLiveService")=="1")
        // Parent must independently launch/verify -no-audio; the Android guest cannot prove host CLI flags.
        require(arguments.getString("beaconNoHostAudio")=="1") { "Owned emulator must use -no-audio" }
        require(Build.VERSION.SDK_INT==36&&contextIsEmulator())
        require(app.packageName.endsWith(".debug"))
        require(app.resources.configuration.locales[0].language=="en") { "Consent driver requires English system UI" }
        require(app.checkSelfPermission(Manifest.permission.RECORD_AUDIO)==PackageManager.PERMISSION_GRANTED)
        require(!app.calls.state.value.active) { "Do not disturb an existing call" }
        val voiceStore=VoiceKeyStore(app)
        val previousVoiceSettings=voiceStore.settings()
        val geminiSettings=voiceStore.settings("gemini")
        val credentials=JSONObject(File(app.filesDir,"live-auth.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        require(BuildConfig.DEFAULT_ORIGIN.startsWith("https://"))
        if(credentials.has("origin")) require(credentials.getString("origin")==BuildConfig.DEFAULT_ORIGIN)
        val resultFile=File(app.filesDir,"live-service-result.json")
        require(!resultFile.exists()||resultFile.delete())
        lateinit var vm:BeaconViewModel
        lateinit var returnIntent:android.content.Intent
        rule.runOnIdle {
            vm=ViewModelProvider(rule.activity)[BeaconViewModel::class.java]
            // ActivityScenario tracks the original intent filter across onNewIntent.
            returnIntent=android.content.Intent(rule.activity.intent).setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        var reader:BeaconClient?=null;var stored="";var callId="";var runtime=""
        val cameraManager=app.getSystemService(android.hardware.camera2.CameraManager::class.java)
        val cameraAvailability=java.util.concurrent.ConcurrentHashMap<String,Boolean>()
        val cameraObserver=object:android.hardware.camera2.CameraManager.AvailabilityCallback() {
            override fun onCameraAvailable(id:String) { cameraAvailability[id]=true }
            override fun onCameraUnavailable(id:String) { cameraAvailability[id]=false }
        }
        cameraManager.registerAvailabilityCallback(cameraObserver,android.os.Handler(android.os.Looper.getMainLooper()))
        val collector=launch { app.calls.state.collect { if(stored.isNotBlank()&&it.conversationScope?.session==stored&&it.error.isNotBlank()) failures.add("Owned call reported error") } }
        val transport=ConcurrentLinkedQueue<JSONObject>()
        val callAdmissions=ConcurrentLinkedQueue<JSONObject>()
        var primary:Throwable?=null
        try {
            voiceStore.setSelection(geminiSettings)
            loginLiveTest(app, BuildConfig.DEFAULT_ORIGIN, credentials)
            withContext(Dispatchers.Main.immediate) { vm.connect(BuildConfig.DEFAULT_ORIGIN) }.join()
            phase="connected-auth"
            await { vm.ui.value.authenticated&&vm.ui.value.connected&&!vm.ui.value.loading }
            // Authentication returns before the cached conversation selection settles.
            (BeaconViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible=true }.get(vm) as? Job)?.join()
            rule.waitForIdle()
            withContext(Dispatchers.Main.immediate) { vm.tab("Chat") }
            phase="restored-conversation"
            await { !vm.ui.value.newChatOpening&&(vm.authenticatedScope()!=null||vm.ui.value.initialChatReady) }
            val previousSession=vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Native call UI verification")) }.join()
            phase="new-conversation"
            repeat(2) {
                if(vm.ui.value.newChatError.isNotBlank() && vm.authenticatedScope()==null) {
                    require(vm.composer.value.text.isBlank() && vm.attachments.value.isEmpty()) { "Do not replace an existing draft during live QA" }
                    rule.onNodeWithText("Retry").performClick()
                    await { !vm.ui.value.newChatOpening && (vm.authenticatedScope()!=null || vm.ui.value.newChatError.isNotBlank()) }
                }
            }
            await { vm.chat.value.sessionId.isNotBlank()&&vm.chat.value.sessionId!=previousSession }
            stored=vm.chat.value.sessionId;reader=requireNotNull(vm.client).detached()
            File(app.filesDir,"live-service-owned.json").writeText(json("session_id" to stored).toString(),Charsets.UTF_8)
            phase="composer"
            val voiceBounds=rule.onNodeWithTag("chat-call-beacon").fetchSemanticsNode().boundsInRoot
            capture("composer-voice")
            rule.onNodeWithTag("message-input").performTextInput("Composer verification")
            rule.onNodeWithTag("chat-call-beacon").assertDoesNotExist()
            val sendBounds=rule.onNodeWithTag("message-send").fetchSemanticsNode().boundsInRoot
            assertEquals(voiceBounds.width,sendBounds.width,1f)
            assertEquals(voiceBounds.height,sendBounds.height,1f)
            assertEquals(voiceBounds.right,sendBounds.right,1f)
            capture("composer-send")
            rule.onNodeWithTag("message-input").performTextClearance()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            val original=requireNotNull(vm.client)
            val allowedToolIds=java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val allowedDecisions=java.util.concurrent.ConcurrentHashMap<String,Pair<String,String>>()
            val traced=original.http.newBuilder().addInterceptor { chain ->
                var request=chain.request();val started=System.nanoTime()
                val trace=json("route" to request.url.pathSegments.last(),"method" to request.method)
                if(request.url.encodedPath=="/api/plugins/beacon/mobile/calls" && request.method=="POST") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer)
                    val body=JSONObject(buffer.readUtf8())
                    callAdmissions.add(body)
                    trace.put("voice_provider",body.optString("voice_provider")).put("client_key",body.optBoolean("client_key"))
                }
                check(request.url.pathSegments.last()!="audio") { "Call audio must never be uploaded" }
                if(request.url.pathSegments.last()=="invoke") {
                    val buffer=okio.Buffer();request.body?.writeTo(buffer)
                    val value=JSONObject(buffer.readUtf8());trace.put("request_id",value.getString("request_id"))
                    val args=value.getJSONObject("arguments")
                    require(value.getString("tool_id") in allowedToolIds && args.optString("url")=="https://example.com" && args.optString("method","GET")=="GET" && args.keys().asSequence().all { it in setOf("url","method","extraction_type","timeout","main_content_only") }) { "QA permits only the verified read-only example.com tool" }
                }
                try {
                    if(request.url.pathSegments.last()=="callback") { trace.put("blocked_by_qa",true);throw dev.hermes.android.data.ApiFailure(403,"Callbacks are disabled in QA") }
                    if(request.url.pathSegments.last()=="decision") {
                        val buffer=okio.Buffer();request.body?.writeTo(buffer);val body=JSONObject(buffer.readUtf8())
                        val expected=allowedDecisions[request.url.pathSegments[request.url.pathSegments.size-2]]
                        require(expected!=null&&expected.first==body.getString("proposal_id")&&expected.second==body.getString("decision")) { "QA only approves an inspected example.com GET" }
                    }
                    chain.proceed(request).also { trace.put("status",it.code) }
                }
                catch(error:Exception) { trace.put("failure",error.javaClass.simpleName);throw error }
                finally { trace.put("elapsed_ms",(System.nanoTime()-started)/1_000_000);transport.add(trace) }
            }.build()
            fun installGuard() = rule.runOnIdle {
                val current = requireNotNull(vm.client)
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible=true }.set(vm,BeaconClient(current.auth.copy(cookies=current.jar.snapshot()),null,null,traced) {})
            }
            installGuard()
            assertEquals(stored,vm.authenticatedScope()?.session)
            rule.onNodeWithTag("chat-call-beacon").assertIsEnabled().performClick()
            phase="voice-admission"
            await(20_000) { app.calls.state.value.active&&app.calls.state.value.conversationScope?.session==stored&&app.calls.state.value.callId.isNotBlank() }
            callId=app.calls.state.value.callId
            await(45_000) { app.calls.state.value.active&&app.calls.state.value.state=="listening"&&app.calls.service!=null }
            assertTrue("Actual CoreTelecom/native service reported a failure",failures.isEmpty())
            callId=app.calls.state.value.callId
            assertEquals(stored,app.calls.state.value.conversationScope?.session)
            assertPhoneKeyCall(app,callAdmissions,geminiSettings)
            rule.onNodeWithTag("beacon-live-call-surface").assertExists()
            rule.onNodeWithTag("beacon-minimize-call").assertDoesNotExist()
            rule.onNodeWithTag("beacon-share-screen").assertIsEnabled().performClick()
            phase="screen-consent"
            assertFalse("Consent request may not claim sharing",app.calls.state.value.screenSharing)
            ScreenConsentDriver.grant()
            await(15_000) { app.calls.state.value.screenSharing&&!app.calls.state.value.screenBusy }
            assertTrue("Projection/Google frame queue failed",failures.isEmpty())
            val service=requireNotNull(app.calls.service)
            val field=BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }
            val google=requireNotNull(field.get(service) as? DirectVoice)
            fun say(text:String) { assertTrue(google.send(JSONObject().put("clientContent",JSONObject().put("turns",JSONArray().put(json("role" to "user","parts" to JSONArray().put(json("text" to text))))).put("turnComplete",true)))) }
            val catalog=dev.hermes.android.data.NativeToolGateway(reader!!,callId).discover("scrapling fetch").rows("tools")
            allowedToolIds.addAll(catalog.filter { it.optBoolean("read_only")&&it.string("name").endsWith("scrapling_fetch") }.map { it.getString("tool_id") })
            assertTrue("Configured read-only Scrapling fetch must be discoverable",allowedToolIds.isNotEmpty())
            val directRequest="Use discover_tools to find the read-only scrapling_fetch tool, then invoke_tool to fetch https://example.com (exact URL, extraction_type text). Tell me its purpose using the result. Do not delegate this to hermes_work or use hermes_search. Do not send email or request callbacks."
            phase="direct-tool"
            say(directRequest)
            await(75_000) { transport.any { it.has("request_id")&&it.optInt("status")==200 } }
            val toolRequest=transport.first { it.has("request_id")&&it.optInt("status")==200 }.getString("request_id")
            var toolReceipt=JSONObject()
            withTimeout(75_000) {
                do { toolReceipt=dev.hermes.android.data.NativeToolGateway(reader!!,callId).status(toolRequest);if(toolReceipt.string("status") !in setOf("ready","running")) break;delay(500) } while(true)
            }
            assertEquals("completed",toolReceipt.string("status"))
            assertTrue(toolReceipt.get("result").toString().contains("documentation examples",ignoreCase=true))
            assertTrue("Direct tool call must not create a Hermes model job",reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId").rows("jobs").isEmpty())
            val confirmationTools=dev.hermes.android.data.NativeToolGateway(reader!!,callId).discover("scrapling make_request").rows("tools")
                .filter { !it.optBoolean("read_only")&&it.string("name").endsWith("scrapling_make_request") }
            assertTrue("Approval-capable harmless GET fixture must be available",confirmationTools.isNotEmpty())
            allowedToolIds.addAll(confirmationTools.map { it.getString("tool_id") })
            for(allow in listOf(false,true)) {
                phase=if(allow) "approval-allow" else "approval-deny"
                say("This is a new request${if(allow) " after my earlier denial" else ""}. Use discover_tools for scrapling make_request, then invoke_tool with the tool named mcp__tool_gateway_write__scrapling_make_request using exactly url https://example.com, method GET, extraction_type text. I will decide its permission on my phone. Do not use another tool or delegate to Hermes.")
                await(60_000) { app.calls.state.value.toolApprovals.isNotEmpty() }
                val proposal=app.calls.state.value.toolApprovals.first()
                val arguments=JSONObject(proposal.arguments)
                assertEquals("https://example.com",arguments.getString("url"))
                assertEquals("GET",arguments.optString("method","GET"))
                allowedDecisions[proposal.requestId]=proposal.proposalId to if(allow) "approve" else "deny"
                val beforeDecision=app.calls.state.value.transcripts.lastOrNull { it.role=="assistant"&&it.final }?.eventId
                capture(if(allow) "tool-allow" else "tool-deny")
                if(allow) {
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
                    await { !app.calls.uiVisible }
                    val manager=app.getSystemService(android.app.NotificationManager::class.java)
                    val prompt=manager.activeNotifications.single { it.id==BeaconCallService.TOOL_NOTIFICATION }.notification
                    assertEquals(android.app.Notification.VISIBILITY_PRIVATE,prompt.visibility)
                    assertEquals(android.app.NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel(prompt.channelId).importance)
                    prompt.actions.single { it.title.toString()=="Allow once" }.actionIntent.send()
                } else rule.onNodeWithTag("beacon-tool-deny").assertIsEnabled().performClick()
                var settled=JSONObject()
                withTimeout(45_000) {
                    do { settled=dev.hermes.android.data.NativeToolGateway(reader!!,callId).status(proposal.requestId);if(settled.string("status") !in setOf("ready","running","needs_approval")) break;delay(500) } while(true)
                }
                assertEquals(if(allow) "completed" else "denied",settled.string("status"))
                if(allow) assertTrue(settled.get("result").toString().contains("documentation examples",ignoreCase=true))
                await { app.calls.state.value.toolApprovals.isEmpty() }
                if(allow) { app.startActivity(returnIntent);await { app.calls.uiVisible } }
                // The model must finish speaking this result before the next synthetic turn.
                await(35_000) { app.calls.state.value.state=="listening" && app.calls.state.value.transcripts.lastOrNull { it.role=="assistant"&&it.final }?.let { it.eventId!=beforeDecision }==true }
            }
            val pcm=BeaconCallService::class.java.getDeclaredField("pcm").apply { isAccessible=true }.get(service) as NativePcm
            val recorder=NativePcm::class.java.getDeclaredField("recorder").apply { isAccessible=true }.get(pcm) as android.media.AudioRecord
            val player=NativePcm::class.java.getDeclaredField("player").apply { isAccessible=true }.get(pcm) as android.media.AudioTrack
            var presentedFrames=0
            await(10_000) { presentedFrames=player.playbackHeadPosition;presentedFrames>0 }
            val beforeHome=android.media.AudioTimestamp()
            assertEquals(android.media.AudioRecord.SUCCESS,recorder.getTimestamp(beforeHome,android.media.AudioTimestamp.TIMEBASE_MONOTONIC))
            phase="background-audio"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            delay(3000)
            assertTrue(app.calls.state.value.active)
            assertEquals(callId,app.calls.state.value.callId)
            assertTrue("Screen sharing must survive an unrelated camera-off on Home",app.calls.state.value.screenSharing)
            val afterHome=android.media.AudioTimestamp()
            assertEquals(android.media.AudioRecord.SUCCESS,recorder.getTimestamp(afterHome,android.media.AudioTimestamp.TIMEBASE_MONOTONIC))
            assertTrue("Live microphone frames must continue in background without a recording upload",afterHome.framePosition>beforeHome.framePosition)
            assertEquals(android.media.AudioRecord.RECORDSTATE_RECORDING,recorder.recordingState)
            app.startActivity(returnIntent)
            phase="return-to-app"
            await { app.calls.uiVisible }
            rule.runOnIdle { vm=ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
            withContext(Dispatchers.Main.immediate) { vm.connect(BuildConfig.DEFAULT_ORIGIN) }.join()
            phase="saved-reconnect"
            await { vm.ui.value.connected&&!vm.ui.value.loading }
            installGuard()
            assertTrue("Saved-session reconnect must preserve foreground audio",app.calls.state.value.active)
            assertEquals(callId,app.calls.state.value.callId)
            withContext(Dispatchers.Main.immediate) { vm.openCurrentCall(callId) }.join()
            phase="return-to-call"
            await { vm.authenticatedScope()?.session==stored }
            val request="Please delegate with hermes_work to execute 17*23 using the host terminal or Python execution tool, then report its observed output in this same conversation. Respond in English. Do not send email, edit files or request callbacks."
            say(request)
            phase="native-delegation"
            var job:JSONObject?=null
            withTimeout(120_000) {
                while(job?.string("status")!="done") {
                    assertTrue("Service failed while sharing screen and using tools",failures.isEmpty())
                    assertTrue(app.calls.state.value.screenSharing)
                    val jobs=reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId").rows("jobs")
                    assertTrue("Only one verification job is permitted",jobs.size<=1)
                    job=jobs.firstOrNull()
                    job?.let { assertEquals(stored,it.getString("stored_session_id"));runtime=it.getString("runtime_session_id");assertTrue(it.string("status") !in setOf("failed","cancelled","needs_input")) }
                    delay(500)
                }
            }
            assertTrue(reports391(job!!.string("result")))
            val history=reader!!.nativeRpc("session.history",json("session_id" to runtime)).rows("messages")
            val execution=history.firstOrNull { row ->
                row.string("role")=="tool"&&row.string("name") in setOf("terminal","execute_code")&&
                    row.optJSONObject("args")?.let { it.string(if(row.string("name")=="terminal") "command" else "code") }.orEmpty().let { command -> command.contains("17")&&command.contains("23") }
            }
            assertNotNull("A successful native calculation tool receipt is required; assistant text alone is insufficient",execution)
            File(app.filesDir,"native-execution-proof.json").writeText(json("call_id" to callId,"tool" to execution!!.string("name"),"reported_result" to "391","runtime_session_id" to runtime).toString(),Charsets.UTF_8)
            await(40_000) { app.calls.state.value.transcripts.any { it.role=="assistant"&&it.final&&reports391(it.text) } }
            rule.onNodeWithTag("beacon-assistant-caption").assertExists()
            assertEquals(stored,vm.authenticatedScope()?.session)
            rule.onNodeWithTag("beacon-share-screen").performClick()
            await(10_000) { !app.calls.state.value.screenSharing&&!app.calls.state.value.screenBusy&&!app.calls.state.value.screenStopPending }
            assertTrue("Call must survive confirmed screen stop",app.calls.state.value.active)
            rule.onNodeWithTag("beacon-camera").assertIsEnabled().performClick()
            phase="camera-front"
            capture("camera-requested")
            await(15_000) { app.calls.state.value.cameraSharing&&!app.calls.state.value.cameraBusy }
            rule.onNodeWithTag("beacon-camera-preview").assertExists()
            capture("camera-front")
            val beforeVision=app.calls.state.value.transcripts.lastOrNull { it.role=="assistant"&&it.final }?.eventId
            phase="camera-description"
            say("Use get_visual_state and briefly describe only what you can actually see in the current camera frame. If no fresh image is visible, say so. Do not infer it from the app controls.")
            await(35_000) { app.calls.state.value.transcripts.lastOrNull { it.role=="assistant"&&it.final&&it.text.isNotBlank() }?.let { it.eventId!=beforeVision }==true }
            File(app.filesDir,"camera-description.json").writeText(json("call_id" to callId,"text" to app.calls.state.value.transcripts.last { it.role=="assistant"&&it.final }.text).toString(),Charsets.UTF_8)
            rule.onNodeWithTag("beacon-camera-flip").performClick()
            phase="camera-rear"
            await(15_000) { app.calls.state.value.cameraSharing&&!app.calls.state.value.cameraBusy&&app.calls.state.value.cameraRear }
            capture("camera-rear")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            phase="camera-background"
            await(10_000) { !app.calls.state.value.cameraSharing&&!app.calls.state.value.cameraBusy&&!app.calls.state.value.cameraStopPending }
            assertTrue(app.calls.state.value.active);assertTrue(app.calls.state.value.minimized);assertEquals(callId,app.calls.state.value.callId)
            app.startActivity(returnIntent)
            await { app.calls.uiVisible }
            capture("camera-off-after-home")
            assertTrue("Camera toggles must not produce spontaneous Spanish",app.calls.state.value.transcripts.none { it.role=="assistant"&&Regex("(?i)todav[ií]a|puedo ver|podr[ií]a intentar").containsMatchIn(it.text) })
            assertTrue("No queue/projection/tool errors may be hidden",failures.isEmpty())
            phase="hangup-resource-proof"
            rule.onNodeWithTag("beacon-camera").assertIsEnabled().performClick()
            await(15_000) { app.calls.state.value.cameraSharing && cameraAvailability.values.any { !it } }
            val audioManager=app.getSystemService(android.media.AudioManager::class.java)
            assertTrue("Positive control: this real call must own recording hardware before hang-up",audioManager.activeRecordingConfigurations.isNotEmpty())
            val googleHttp=DirectVoice::class.java.getDeclaredField("providerHttp").apply { isAccessible=true }.get(google) as okhttp3.OkHttpClient
            val nativeClient=DirectVoice::class.java.getDeclaredField("client").apply { isAccessible=true }.get(google) as BeaconClient
            val nativeTransport=BeaconClient::class.java.getDeclaredField("native").apply { isAccessible=true }.get(nativeClient)
            val nativeConnection=nativeTransport.javaClass.getDeclaredField("connection").apply { isAccessible=true }
            assertNotNull("Positive control: native work owns a gateway connection",nativeConnection.get(nativeTransport))
            assertTrue("Positive control: live Gemini owns a network connection",googleHttp.dispatcher.runningCallsCount()>0||googleHttp.connectionPool.connectionCount()>0)
            val notifications=app.getSystemService(android.app.NotificationManager::class.java)
            fun hangupAction(notification:android.app.Notification):android.app.PendingIntent {
                val action=checkNotNull(notification.extras.getParcelable<android.app.PendingIntent>(android.app.Notification.EXTRA_HANG_UP_INTENT))
                assertTrue("The platform hang-up action must be visible",notification.actions.any { it.actionIntent==action })
                return action
            }
            val oldNotification=notifications.activeNotifications.single { it.id==BeaconCallService.NOTIFICATION }.notification
            val oldMute=oldNotification.actions.single { it.title.toString()=="Mute" }.actionIntent
            val oldHangup=hangupAction(oldNotification)
            val hangupAt=android.os.SystemClock.elapsedRealtime()
            rule.onNodeWithTag("beacon-hang-up").performClick()
            await(6_000) { audioManager.activeRecordingConfigurations.isEmpty() }
            val audioStopMillis=android.os.SystemClock.elapsedRealtime()-hangupAt
            await(8_000) { cameraManager.cameraIdList.all { cameraAvailability[it]==true } }
            phase="archive"
            withTimeout(35_000) { service.destroyed.await() }
            assertNull("The foreground call service must really detach",app.calls.service)
            await(5_000) { googleHttp.dispatcher.runningCallsCount()==0 && googleHttp.connectionPool.connectionCount()==0 }
            assertTrue(googleHttp.dispatcher.executorService.isShutdown)
            assertNull("Final cleanup must close the call-owned native gateway",nativeConnection.get(nativeTransport))
            assertTrue("The ongoing call notification must be removed",app.getSystemService(android.app.NotificationManager::class.java).activeNotifications.none { it.id==BeaconCallService.NOTIFICATION })
            assertFalse(app.calls.state.value.active)
            delay(500)
            assertTrue("Late callbacks must not reopen recording",audioManager.activeRecordingConfigurations.isEmpty())
            File(app.filesDir,"hangup-resource-proof.json").writeText(json("call_id" to callId,"button_clicked" to true,"positive_recording_before" to true,"audio_stop_ms" to audioStopMillis,"recording_configurations_after" to 0,"camera_available_after" to true,"service_detached" to true,"notification_removed" to true,"google_calls_after" to 0,"google_connections_after" to 0).toString(),Charsets.UTF_8)
            assertFalse(app.calls.state.value.audioStopPending)
            assertFalse(app.calls.state.value.hostCleanupPending)
            val receipt=reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId")
            assertTrue(receipt.optBoolean("closed"));assertFalse(receipt.optBoolean("audio_available"))
            assertEquals("disabled",receipt.getString("recording_status"))
            assertEquals("saved",receipt.getString("transcript_status"))
            assertTrue(receipt.optBoolean("transcript_available"))
            assertTrue("The owner did not request a callback",transport.none { it.optBoolean("blocked_by_qa") })
            assertTrue(receipt.getInt("transcript_events")>0)
            val completedProof=json("session_id" to stored,"call_id" to callId,"runtime_session_id" to runtime,"job_id" to job!!.getString("job_id"),"tool_request_id" to toolRequest,"direct_tool_verified" to true,"composer_equal_size" to true,"background_pcm_continued" to true,"google_hardware_frames" to presentedFrames,"saved_reconnect_preserved_call" to true,"front_rear_camera_sent" to true,"actual_service" to true,"projection_queued" to true,"projection_stop_confirmed" to true,"native_result_verified" to true,"native_execution_seen" to true,"final_caption_visible" to true,"archive_audio_available" to false,"no_audio_uploads" to true,"transcript_status" to receipt.getString("transcript_status"),"transcript_events" to receipt.getInt("transcript_events"),"call_errors" to failures.size,"version_code" to BuildConfig.VERSION_CODE)
            val routes=mutableListOf<String>()
            suspend fun startRoute():BeaconCallService {
                withContext(Dispatchers.Main.immediate) { app.calls.start(requireNotNull(vm.client),stored) }
                await(20_000) { app.calls.state.value.callId.isNotBlank()&&app.calls.state.value.active }
                callId=app.calls.state.value.callId
                await(45_000) { app.calls.state.value.state=="listening"&&app.calls.service!=null }
                assertTrue(audioManager.activeRecordingConfigurations.isNotEmpty())
                return requireNotNull(app.calls.service)
            }
            suspend fun checkEnded(owner:BeaconCallService,label:String) {
                withTimeout(35_000) { owner.destroyed.await() }
                assertNull(app.calls.service);assertFalse(app.calls.state.value.active)
                assertTrue(audioManager.activeRecordingConfigurations.isEmpty())
                assertTrue(notifications.activeNotifications.none { it.id==BeaconCallService.NOTIFICATION })
                assertTrue(reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId").optBoolean("closed"))
                routes+=label
            }
            phase="notification-hangup"
            val notificationService=startRoute()
            oldMute.send();oldHangup.send();delay(500)
            assertTrue("Old notification actions cannot affect the new call",app.calls.state.value.active&&!app.calls.state.value.muted)
            assertEquals(callId,app.calls.state.value.callId)
            notifications.activeNotifications.single { it.id==BeaconCallService.NOTIFICATION }.notification.actions.single { it.title=="Mute" }.actionIntent.send()
            await { app.calls.state.value.muted }
            hangupAction(notifications.activeNotifications.single { it.id==BeaconCallService.NOTIFICATION }.notification).send()
            checkEnded(notificationService,"notification; stale actions ignored")
            phase="bubble-hangup"
            require(android.provider.Settings.canDrawOverlays(app)) { "Owned emulator overlay permission required for real bubble test" }
            val bubbleService=startRoute()
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            await { app.calls.state.value.minimized&&!app.calls.uiVisible }
            rule.waitUntil(8_000) { rule.onAllNodesWithTag("beacon-call-bubble").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            rule.onNodeWithTag("beacon-call-bubble").performClick()
            rule.onNodeWithTag("beacon-bubble-mute").performClick();await { app.calls.state.value.muted }
            rule.onNodeWithTag("beacon-bubble-hide").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("beacon-call-bubble").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty() }
            rule.onNodeWithTag("beacon-call-bubble").assertDoesNotExist()
            assertTrue(app.calls.state.value.active)
            assertTrue(audioManager.activeRecordingConfigurations.isNotEmpty())
            val retained=notifications.activeNotifications.single { it.id==BeaconCallService.NOTIFICATION }.notification
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE,retained.visibility)
            if(android.os.Build.VERSION.SDK_INT>=36) {
                assertTrue(retained.extras.getBoolean("android.requestPromotedOngoing"))
                if(notifications.canPostPromotedNotifications()) assertTrue(retained.hasPromotableCharacteristics())
            }
            retained.contentIntent.send();await { app.calls.uiVisible }
            assertEquals(callId,app.calls.state.value.callId)
            android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("input keyevent 3")).use { it.readBytes() }
            await { app.calls.state.value.minimized&&!app.calls.uiVisible }
            rule.waitUntil(8_000) { rule.onAllNodesWithTag("beacon-call-bubble").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty() }
            rule.onNodeWithTag("beacon-call-bubble").performClick()
            rule.onNodeWithTag("beacon-bubble-end").performClick()
            checkEnded(bubbleService,"bubble hide retained recorder and call notification; notification returned; bubble end stopped media")
            rule.onNodeWithTag("beacon-call-bubble").assertDoesNotExist()
            app.startActivity(returnIntent);await { app.calls.uiVisible }
            phase="banner-hangup"
            val bannerService=startRoute()
            withContext(Dispatchers.Main.immediate) { vm.navigate("Memory") }
            rule.onNodeWithContentDescription("Hang up ${dev.hermes.android.data.currentAssistantName}").performClick()
            checkEnded(bannerService,"other-page call banner")
            phase="connecting-hangup"
            withContext(Dispatchers.Main.immediate) { app.calls.start(requireNotNull(vm.client),stored);callId=app.calls.state.value.callId;app.calls.hangup() }
            await(35_000) { !app.calls.state.value.active&&app.calls.service==null }
            delay(6_000)
            assertTrue(audioManager.activeRecordingConfigurations.isEmpty())
            assertTrue(reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId").optBoolean("closed"))
            routes+="cancel during connection"
            assertTrue("No late call or cleanup errors may be hidden",failures.isEmpty())
            resultFile.writeText(completedProof.put("all_hangup_routes_verified",true).put("call_errors",failures.size).toString(),Charsets.UTF_8)
            File(app.filesDir,"hangup-routes-proof.json").writeText(json("routes" to JSONArray(routes),"no_active_recorder" to true,"no_call_notification" to notifications.activeNotifications.none { it.id==BeaconCallService.NOTIFICATION },"version_code" to BuildConfig.VERSION_CODE).toString(),Charsets.UTF_8)
        } catch(error:Throwable) {
            primary=error
            File(app.filesDir,"live-service-stack.txt").writeText(error.stackTraceToString(),Charsets.UTF_8)
            File(app.filesDir,"live-service-failure.json").writeText(json("phase" to phase,"call_id" to callId,"visible" to app.calls.uiVisible,"state" to app.calls.state.value.state,"call_error" to app.calls.state.value.error,"failure_type" to error.javaClass.simpleName,"ui_error" to vm.ui.value.error,"ui_authenticated" to vm.ui.value.authenticated,"ui_connected" to vm.ui.value.connected,"initial_ready" to vm.ui.value.initialChatReady,"has_scope" to (vm.authenticatedScope()!=null),"held" to app.calls.state.value.held,"camera_busy" to app.calls.state.value.cameraBusy,"camera_sharing" to app.calls.state.value.cameraSharing,"screen_pending" to app.calls.state.value.screenStopPending,"camera_requested" to app.calls.service?.let { BeaconCallService::class.java.getDeclaredField("cameraRequested").apply { isAccessible=true }.getBoolean(it) },"new_chat_error" to vm.ui.value.newChatError,"new_chat_opening" to vm.ui.value.newChatOpening,"ui_notice" to vm.ui.value.notice).toString(),Charsets.UTF_8)
            throw error
        }
        finally {
            val cleanup=cleanupOwnedCall(stored,callId,runtime,reader)
            var settingsRestore:Throwable?=null
            try { voiceStore.setSelection(previousVoiceSettings) } catch(error:Throwable) { settingsRestore=error }
            File(app.filesDir,"live-service-http.json").writeText(JSONArray(transport.toList()).toString(),Charsets.UTF_8)
            cameraManager.unregisterAvailabilityCallback(cameraObserver)
            collector.cancel();reader?.closeNative()
            if(cleanup!=null||settingsRestore!=null) {
                val error=AssertionError("Verification call/runtime cleanup incomplete")
                cleanup?.let(error::addSuppressed);settingsRestore?.let(error::addSuppressed)
                if(primary!=null) primary!!.addSuppressed(error) else throw error
            }
        }
    }
    private suspend fun cleanupOwnedCall(stored:String,callId:String,runtime:String,reader:BeaconClient?):Throwable? = try {
        withContext(NonCancellable) { withTimeout(45_000) {
            val current=app.calls.state.value
            val ownsCall=stored.isNotBlank()&&current.conversationScope?.session==stored&&(callId.isBlank()||current.callId==callId)
            val service=if(ownsCall) app.calls.service else null
            if(ownsCall) withContext(Dispatchers.Main.immediate) { app.calls.stopScreen();app.calls.hangup() }
            service?.destroyed?.await()
            if(stored.isNotBlank()&&reader!=null) {
                val activeRuntime=runtime.ifBlank { reader.nativeSession(stored).runtimeSessionId }
                reader.nativeRpc("session.close",json("session_id" to activeRuntime))
                reader.nativeRpc("session.set_hidden",json("session_id" to stored,"hidden" to true))
            }
        } }
        null
    } catch(error:Throwable) { error }
    private fun assertPhoneKeyCall(app:BeaconApplication,admissions:Collection<JSONObject>,settings:VoicePreferences) {
        val admission=admissions.firstOrNull()?:throw AssertionError("Phone-key call admission was not captured")
        assertEquals("gemini",admission.getString("voice_provider"));assertTrue(admission.optBoolean("client_key"))
        assertFalse(admission.has("api_key")||admission.has("token"))
        val direct=BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible=true }.get(requireNotNull(app.calls.service)) as DirectVoice
        val credentials=DirectVoice::class.java.getDeclaredField("credentials").apply { isAccessible=true }.get(direct) as VoiceSessionCredentials
        assertEquals("gemini",credentials.provider)
        assertEquals(settings.voice,credentials.voice)
        assertEquals(settings.personality,credentials.personality)
    }
    private fun contextIsEmulator()=Build.FINGERPRINT.contains("generic")||Build.FINGERPRINT.contains("emulator")||Build.MODEL.contains("sdk_gphone")
}
