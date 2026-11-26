package dev.hermes.android.voice

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeToolGateway
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.loginLiveTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentLinkedQueue

/** Opt-in live proof on the isolated demo Hermes: a call declares the profile's tools, runs a real terminal echo and a
 *  real memory read, and Hermes' dashboard/gateway identities stay the same. No audio is retained. */
class VoiceToolsLiveTest {
    @get:Rule val rule = createAndroidComposeRule<BeaconActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test(timeout = 480_000) fun aCallDeclaresAndRunsTheProfilesTerminalAndMemory() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconLiveService") == "1")
        require(args.getString("beaconNoHostAudio") == "1") { "Owned emulator must use -no-audio" }
        require(app.packageName.endsWith(".debug")) { "Live tools proof runs the debug app" }
        require(!app.calls.state.value.active) { "Do not disturb an existing call" }
        val voice = VoiceKeyStore(app)
        val previous = voice.settings()
        // A one-off local run may pass the key as an app-private file: it is read once and deleted; never logged or copied.
        val keyFile = File(app.filesDir, "live-grok-key.txt")
        if (args.getString("beaconVoiceKeySetup") == "1" && keyFile.isFile) {
            val key = keyFile.readText(Charsets.UTF_8).trim()
            require(key.isNotEmpty() && key.length <= 8192 && key.none { it.isWhitespace() || it.isISOControl() })
            voice.saveKey("grok", key)
            check(keyFile.delete()) { "The temporary key file was not removed" }
        }
        val grok = voice.session("grok")
        assumeTrue("A saved Grok key is required for this live proof", grok != null)
        val credentials = requireNotNull(grok)
        val auth = JSONObject(File(app.filesDir, "live-auth.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val origin = auth.getString("origin")
        lateinit var vm: BeaconViewModel
        rule.runOnIdle { vm = ViewModelProvider(rule.activity)[BeaconViewModel::class.java] }
        var reader: BeaconClient? = null
        var callId = ""
        var session = ""
        var runtime = ""
        val healthBefore = JSONObject()
        val requests = ConcurrentLinkedQueue<JSONObject>()
        val memoryReads = ConcurrentLinkedQueue<JSONObject>()
        val failures = ConcurrentLinkedQueue<String>()
        suspend fun await(ms: Long = 30_000, condition: () -> Boolean) = withTimeout(ms) {
            while (!condition()) {
                check(failures.isEmpty()) { failures.joinToString("; ") }
                rule.waitForIdle()
                delay(50)
            }
        }
        try {
            voice.setSelection(voice.settings("grok"))
            rule.runOnIdle { VoiceKeyStore(app).setSelection(voice.settings("grok")) }
            loginLiveTest(app, origin, auth)
            withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
            await { vm.ui.value.authenticated && vm.ui.value.connected && !vm.ui.value.loading }
            (BeaconViewModel::class.java.getDeclaredField("selectionJob").apply { isAccessible = true }.get(vm) as? kotlinx.coroutines.Job)?.join()
            withContext(Dispatchers.Main.immediate) { vm.tab("Chat") }
            await { !vm.ui.value.newChatOpening && (vm.authenticatedScope() != null || vm.ui.value.initialChatReady) }
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to "Voice tools live proof")) }.join()
            await(60_000) { vm.chat.value.sessionId.isNotBlank() && !vm.ui.value.newChatOpening }
            if (vm.ui.value.setupWizard) withContext(Dispatchers.Main.immediate) { vm.finishSetupWizard(keepConnected = false) }
            withContext(Dispatchers.Main.immediate) { vm.tab("Chat") }
            rule.waitForIdle()
            await(30_000) { rule.onAllNodesWithTag("chat-call-beacon").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            session = vm.chat.value.sessionId
            reader = requireNotNull(vm.client).detached()
            runtime = reader!!.nativeSession(session).runtimeSessionId
            val current = requireNotNull(vm.client)
            val traced = current.http.newBuilder().addNetworkInterceptor { chain ->
                val request = chain.request()
                val path = request.url.encodedPath
                if (path.endsWith("/tools/invoke")) {
                    val buffer = okio.Buffer(); request.body?.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    requests.add(JSONObject().put("request_id", body.optString("request_id"))
                        .put("tool", body.optString("tool").ifBlank { body.optString("tool_id") })
                        .put("arguments", body.opt("arguments")?.toString()?.take(1_000) ?: ""))
                }
                if (path.endsWith("/memory")) {
                    val buffer = okio.Buffer(); request.body?.writeTo(buffer)
                    val body = JSONObject(buffer.readUtf8())
                    memoryReads.add(body)
                }
                chain.proceed(request).also { response ->
                    if (path.endsWith("/tools/invoke") || path.endsWith("/memory")) {
                        val row = requests.lastOrNull()?.takeIf { it.optString("status").isBlank() }
                        if (row != null && path.endsWith("/tools/invoke")) row.put("status", response.code)
                    }
                }
            }.build()
            rule.runOnIdle {
                BeaconViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }
                    .set(vm, BeaconClient(current.auth.copy(cookies = current.jar.snapshot()), null, null, traced) {})
            }
            val health = reader!!.nativeRequest("/api/plugins/beacon/mobile/health")
            healthBefore.put("dashboard_started_at", health.optJSONObject("dashboard")?.optLong("started_at") ?: 0L)
                .put("gateway_started_at", health.optJSONObject("gateway")?.optLong("started_at") ?: 0L)
            rule.onNodeWithTag("chat-call-beacon").assertIsEnabled().performClick()
            await(60_000) { app.calls.state.value.active && app.calls.state.value.callId.isNotBlank() }
            callId = app.calls.state.value.callId
            await(90_000) { app.calls.state.value.state in setOf("listening", "thinking", "speaking") }
            val context = reader!!.nativeRequest("/api/plugins/beacon/mobile/calls/$callId/context")
            val declared = (0 until (context.optJSONArray("tool_declarations")?.length() ?: 0)).mapNotNull {
                context.optJSONArray("tool_declarations")?.optJSONObject(it)
            }
            val names = declared.map { it.optString("name") }
            assertTrue("terminal must be declared: $names", "terminal" in names)
            assertTrue("hermes_memory must be available in the call instruction", context.optJSONObject("capabilities")?.optBoolean("memory_recall", false) == true)
            val setup = GeminiWire.setup(credentials.model, credentials.voice, null, context,
                PhoneVoiceTools.enabledNames(), PhoneVoiceTools.approvalMode())
            val functions = setup.getJSONObject("setup").getJSONArray("tools").getJSONObject(0).getJSONArray("functionDeclarations")
            val functionNames = (0 until functions.length()).map { functions.getJSONObject(it).getString("name") }
            assertTrue("the model is sent terminal", "terminal" in functionNames)
            assertTrue("the model is sent hermes_memory", "hermes_memory" in functionNames)
            val grokSetup = GrokWire().outbound(setup).single().toString()
            val direct = BeaconCallService::class.java.getDeclaredField("directVoice").apply { isAccessible = true }
                .get(requireNotNull(app.calls.service)) as DirectVoice
            val question = "Use the terminal tool declared in this session, not discover_tools and not hermes_work, to run exactly: " +
                "echo BEACON-VOICE-TOOLS-OK . Then tell me its output."
            val turn = json("role" to "user", "parts" to JSONArray().put(json("text" to question)))
            assertTrue(direct.send(json("clientContent" to json("turns" to JSONArray().put(turn), "turnComplete" to true))))
            await(60_000) { requests.isNotEmpty() || app.calls.state.value.toolApprovals.isNotEmpty() }
            var terminal: JSONObject? = null
            withTimeoutOrNull(90_000) {
                while (terminal == null) {
                    val approval = app.calls.state.value.toolApprovals.firstOrNull()
                    if (approval != null) {
                        app.calls.decideTool(callId, approval.requestId, approval.proposalId, "approve")
                        delay(500)
                    }
                    for (request in requests) {
                        // The interceptor sees the invoke before the plugin has stored its receipt; a 404 here is a race, not a failure.
                        val receipt = try { NativeToolGateway(reader!!, callId).status(request.getString("request_id")) }
                            catch (error: dev.hermes.android.data.ApiFailure) { if (error.status == 404) null else throw error }
                            ?: continue
                        if (receipt.optString("status") in setOf("completed", "failed", "uncertain") &&
                            (receipt.optString("tool_name").contains("terminal", true) || receipt.toString().contains("BEACON-VOICE-TOOLS-OK"))) {
                            terminal = receipt
                            break
                        }
                    }
                    delay(200)
                }
            }
            val terminalReceipt = requireNotNull(terminal) { "terminal was not invoked: ${requests.joinToString()}" }
            assertEquals("terminal completed: $terminalReceipt", "completed", terminalReceipt.optString("status"))
            assertTrue("terminal echo: $terminalReceipt", terminalReceipt.toString().contains("BEACON-VOICE-TOOLS-OK"))
            // A second turn, so the memory read is a request of its own and not a choice the model may skip.
            val memoryTurn = json("role" to "user", "parts" to JSONArray().put(json("text" to
                "Now use hermes_memory with query \"boiler\" and tell me what it answers.")))
            assertTrue(direct.send(json("clientContent" to json("turns" to JSONArray().put(memoryTurn), "turnComplete" to true))))
            withTimeoutOrNull(60_000) { while (memoryReads.isEmpty()) delay(100) }
            assertTrue("hermes_memory was called", "boiler" in memoryReads.joinToString())
            withTimeoutOrNull(60_000) {
                while (app.calls.state.value.toolReceipts.none { it.toolName == "hermes_memory" && it.status == "completed" && it.hasResult }) delay(100)
            }
            assertTrue("hermes_memory returned a result", app.calls.state.value.toolReceipts.any {
                it.toolName == "hermes_memory" && it.status == "completed" && it.hasResult
            })
            val applied = setup.toString()
            val healthAfter = reader!!.nativeRequest("/api/plugins/beacon/mobile/health")
            assertEquals(healthBefore.getLong("dashboard_started_at"), healthAfter.optJSONObject("dashboard")?.optLong("started_at") ?: 0L)
            assertEquals(healthBefore.getLong("gateway_started_at"), healthAfter.optJSONObject("gateway")?.optLong("started_at") ?: 0L)
            assertTrue(healthAfter.optJSONObject("dashboard")?.optBoolean("current", false) == true)
            assertTrue(healthAfter.optJSONObject("gateway")?.optBoolean("running", false) == true)
            val proof = json("call_id" to callId, "session_id" to session, "provider" to credentials.provider,
                "declared" to JSONArray(names), "setup_functions" to functionNames.size,
                "setup_sha256" to sha(applied), "grok_setup_sha256" to sha(grokSetup),
                "declarations_sha256" to sha(context.optJSONArray("tool_declarations")?.toString() ?: "[]"),
                "terminal_schema" to functions.let { list -> (0 until list.length()).map { list.getJSONObject(it) }
                    .firstOrNull { it.optString("name") == "terminal" }?.toString()?.take(4_000) ?: "" },
                "terminal_request" to terminalReceipt.optString("request_id"), "terminal_status" to terminalReceipt.optString("status"),
                "terminal_result" to terminalReceipt.opt("result").toString().take(2_000),
                "memory_requests" to JSONArray(memoryReads), "health_before" to healthBefore, "health_after" to healthAfter,
                "audio_saved" to false, "version_code" to dev.hermes.android.BuildConfig.VERSION_CODE)
            File(app.filesDir, "voice-tools-live-proof.json").writeText(proof.toString(), Charsets.UTF_8)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { app.calls.hangup() }
                withTimeoutOrNull(45_000) { while (app.calls.service != null) delay(100) }
                runCatching { if (callId.isNotBlank()) reader?.nativeRequest("/api/plugins/beacon/mobile/calls/$callId", "DELETE") }
                runCatching { if (runtime.isNotBlank()) reader?.nativeRpc("session.close", json("session_id" to runtime)) }
                runCatching { if (session.isNotBlank()) reader?.nativeRpc("session.set_hidden", json("session_id" to session, "hidden" to true)) }
                runCatching { reader?.closeNative() }
                runCatching { rule.runOnIdle { VoiceKeyStore(app).setSelection(previous) } }
            }
        }
    }
}
