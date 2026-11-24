package dev.hermes.android.journey

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.NativePairing
import dev.hermes.android.loginLiveTest
import dev.hermes.android.openSteer
import dev.hermes.android.preferences
import dev.hermes.android.push.KeepConnectedService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * H1 (1.2.3) live checks on the demo Hermes: explicit opt-in (h1Live=1), emulator only, inside demo-fixture isolation, synthetic words only.
 * Phases (h1Phases, comma separated): steerA (R3-F5: Send now during a plain-text reply lands as the next turn), steerB (during a tool step it
 * still folds in), alert (R3-F7: tapping a question alert opens its own chat), screen (R3-F4: back from the screen off, the open chat's reply
 * posts no loud alert). Evidence goes to files/h1-live-<h1Label>/evidence.json and screenshots beside it.
 */
class H1ChatLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()

    private suspend fun await(ms: Long, what: String, ready: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!ready()) delay(250) } } catch (e: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $what") }
    }

    @Test(timeout = 1_700_000) fun h1LiveChecks() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("h1Live") == "1")
        val phases = args.getString("h1Phases").orEmpty().split(',').filter(String::isNotBlank).toSet()
        val app = compose.activity.application as BeaconApplication
        require(app.packageName.endsWith(".debug") && (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))) { "emulator only" }
        require(!app.calls.state.value.active && app.calls.service == null)
        val file = File(app.filesDir, "live-auth.json")
        val credentials = JSONObject(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        check(file.delete() && credentials.string("purpose") == "beacon_visual_tour_demo")
        val origin = trustedOrigin(credentials.getString("origin"))
        val out = File(app.filesDir, "h1-live-" + args.getString("h1Label").orEmpty().ifBlank { "run" }).apply { deleteRecursively(); mkdirs() }
        val evidence = JSONObject().put("phases", JSONArray(phases.toList()))
        fun save() = File(out, "evidence.json").writeText(evidence.toString(2))
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        // The compose rule holds the activity's frame clock: frames run only when the test moves it (the app's own effects, a tapped alert's routing included).
        fun frames(n: Int = 6) = repeat(n) { compose.mainClock.advanceTimeByFrame() }
        fun shot(name: String) { frames(30); ui.takeScreenshot()?.let { b -> File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) } } }
        val manager = app.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)

        loginLiveTest(app, origin, credentials)
        val signedIn = app.auth.read(origin)
        val priorScope = Scope(origin, signedIn.owner, signedIn.profile, nativeProvider = signedIn.nativeProvider)
        app.cache.delete(priorScope, "beacon-session"); app.cache.delete(priorScope, "beacon-opening")
        val model = "command-code/deepseek-deepseek-v4.1-flash"; val provider = "custom:opencodex"
        app.preferences.edit { it[stringPreferencesKey("assistant_model")] = model; it[stringPreferencesKey("assistant_provider")] = provider }
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
        await(60_000, "the connection") { vm.ui.value.connected && !vm.ui.value.restoringSession && vm.client != null }
        val client = checkNotNull(vm.client)
        val scope = client.scope.copy(session = "")
        NativePairing.deviceId(app, scope) ?: NativePairing.link(app, client)
        withContext(Dispatchers.Main.immediate) { vm.finishSetupWizard(keepConnected = true) }
        await(60_000, "Keep connected") { !vm.ui.value.setupWizard && !vm.ui.value.loading && KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED }
        withContext(Dispatchers.Main.immediate) { vm.tab("Beacon") }
        await(90_000, "the Beacon chat") { !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.isBeaconConversation() }
        val beaconChat = vm.chat.value.sessionId
        evidence.put("beacon_chat", beaconChat); save()

        fun text(row: JSONObject) = row.optString("text").ifBlank { row.optString("content") }
        suspend fun rows(chat: String) = client.get("/api/session", mapOf("session_id" to chat), maxBytes = 4 * 1024 * 1024).let { it.optJSONObject("session") ?: it }.rows("messages")
        fun tail(list: List<JSONObject>) = JSONArray(list.takeLast(8).map { JSONObject().put("role", it.optString("role")).put("kind", it.optString("display_kind")).put("text", text(it).take(80)) })
        fun queue() = JSONArray(vm.chat.value.queued.map { JSONObject().put("text", it.text.take(40)).put("kind", it.kind).put("state", it.state).put("row", it.rowId).put("outcome", it.outcome) })
        suspend fun idle(what: String, ms: Long = 300_000) = await(ms, what) { vm.chat.value.streamId.isBlank() && vm.chat.value.queued.none { it.state in setOf("sending", "queued") } }
        suspend fun send(words: String) { withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(words)); vm.send() } }
        suspend fun steer(words: String) { withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(words)); vm.steer() } }
        val run = java.util.UUID.randomUUID().toString().take(8)
        suspend fun stopTurn(chat: String) {
            withContext(Dispatchers.Main.immediate) { vm.selectSession(chat) }
            runCatching { await(30_000, "chat $chat") { vm.chat.value.sessionId == chat && vm.chat.value.projectionReady } }
            // The app's own Stop route (session.interrupt on the running claim): a turn waiting on a question ends even when no stream is attached.
            val stop = runCatching { client.post("/api/chat/cancel", json("session_id" to chat)).toString().take(200) }.getOrElse { "failed:" + it.javaClass.simpleName + ":" + it.message?.take(120) }
            val stopped = runCatching { await(60_000, "chat $chat to stop") { vm.chat.value.streamId.isBlank() } }.isSuccess
            evidence.put("stopped_$chat", JSONObject().put("stream_clear", stopped).put("stop", stop)); save()
        }
        // First: stop the waiting turns of earlier runs' test chats (h1Stop), so the demo is idle again whatever this run does.
        args.getString("h1Stop").orEmpty().split(',').filter(String::isNotBlank).forEach { stopTurn(it) }

        if ("steerA" in phases) {
            idle("a quiet chat")
            // A: Send now while Beacon writes a plain-text reply (no tool step follows, so Hermes cannot fold it into the turn).
            val a = "H1 steer A $run. After the story, say the word ORANGE."
            send("Write a 500 word story about a lighthouse keeper named Ida. Plain text only. Do not use any tools.")
            await(180_000, "the story words") { vm.chat.value.liveText.length > 60 }
            steer(a)
            await(60_000, "Hermes to take the steer") { vm.chat.value.queued.any { it.text == a && it.state != "sending" } }
            val taken = vm.chat.value.queued.first { it.text == a }
            evidence.put("steer_a_taken", JSONObject().put("state", taken.state).put("kind", taken.kind).put("while_running", vm.chat.value.streamId.isNotBlank())); save()
            assertEquals("Hermes accepted the steer while the reply ran", "accepted", taken.state)
            await(420_000, "the steer to land as its own turn") {
                val saved = rows(beaconChat)
                val at = saved.indexOfLast { it.optString("role") == "user" && text(it).contains(a) }
                at >= 0 && saved.drop(at + 1).any { it.optString("role") == "assistant" && text(it).isNotBlank() } && vm.chat.value.streamId.isBlank()
            }
            val savedA = rows(beaconChat)
            val rowA = savedA.last { it.optString("role") == "user" && text(it).contains(a) }
            evidence.put("steer_a", JSONObject().put("row_kind", rowA.optString("display_kind")).put("tail", tail(savedA)).put("queue", queue())); save()
            assertNotEquals("The leftover steer is its own turn", "steer", rowA.optString("display_kind"))
            assertTrue("No steer copy stays open", vm.chat.value.queued.none { it.kind == "steer" && it.rowId.isBlank() && it.state in setOf("accepted", "unknown") })
            shot("steer-a-end")
        }

        if ("steerB" in phases) {
            // B: Send now during a tool step still folds into the running turn (a plain chat on the working model: the Beacon chat may offer no terminal).
            idle("the chat to settle")
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("model" to model, "provider" to provider)) }.join()
            await(60_000, "a plain chat") { vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != beaconChat }
            val workChat = vm.chat.value.sessionId
            val b = "H1 steer B $run. Mention the word PLUM in your reply."
            send("Use your terminal tool to run exactly: sleep 20 && echo h1-tool-done . Then reply with one short line.")
            try { await(240_000, "the tool step") { vm.chat.value.liveTools.isNotEmpty() || vm.chat.value.approval != null } }
            catch (e: AssertionError) {
                evidence.put("steer_b_no_tool", JSONObject().put("chat", workChat).put("stream", vm.chat.value.streamId).put("status", vm.chat.value.status).put("tail", tail(rows(workChat)))); save(); throw e
            }
            evidence.put("steer_b_tool", JSONObject().put("chat", workChat).put("tools", vm.chat.value.liveTools.size).put("approval", vm.chat.value.approval != null)); save()
            // H1b P1-3: every chat state until the turn ends, checked for a saved step drawn again in the live reply and for the steer beside its saved row.
            val doubled = java.util.concurrent.CopyOnWriteArrayList<String>()
            val watch = CoroutineScope(Dispatchers.Main.immediate).launch {
                vm.chat.collect { c ->
                    if (c.sessionId != workChat) return@collect
                    val saved = historyToolIdentities(c.raw.rows("messages"))
                    c.liveTools.map(::toolIdentity).filter { it.isNotBlank() && it in saved }.forEach { doubled += "step $it" }
                    val copy = c.queued.firstOrNull { it.text == b }
                    if (copy != null && c.messages.any { it.role == "user" && it.displayKind == "steer" && it.text.contains(b) } &&
                        "queued:${copy.id}" in dev.hermes.android.chatTimelineMessages(c.messages, c.queued).map { it.id }) doubled += "steer beside its row"
                }
            }
            steer(b)
            await(60_000, "Hermes to take steer B") { vm.chat.value.queued.any { it.text == b && it.state != "sending" } }
            evidence.put("steer_b_taken", vm.chat.value.queued.first { it.text == b }.state); save()
            // A terminal step may ask first (the demo's smart approvals): the steer waits behind the step either way; answer once so the step runs.
            vm.chat.value.approval?.let { card ->
                delay(2_000)
                val choice = card.optJSONArray("choices")?.let { c -> (0 until c.length()).map(c::getString) }.orEmpty().firstOrNull { it.contains("once") } ?: "once"
                withContext(Dispatchers.Main.immediate) { vm.approval(choice, card, scope.session(workChat)) }
            }
            idle("the tool turn")
            watch.cancel()
            evidence.put("steer_b_doubled", JSONArray(doubled.distinct())); save()
            val savedB = rows(workChat)
            val rowB = savedB.lastOrNull { it.optString("role") == "user" && text(it).contains(b) }
            evidence.put("steer_b", JSONObject().put("row_kind", rowB?.optString("display_kind")).put("tail", tail(savedB)).put("queue", queue())); save()
            assertEquals("A steer during a tool step folds into the turn", "steer", rowB?.optString("display_kind"))
            assertTrue("Something showed twice while the steered turn ran: $doubled", doubled.isEmpty())
            shot("steer-b-end")
        }

        if ("screen" in phases) {
            idle("a quiet chat before the screen check")
            manager.cancelAll()
            val screenChat = vm.chat.value.sessionId
            val seen = java.util.concurrent.ConcurrentHashMap<String, JSONObject>()
            val watch = launch(Dispatchers.Default) {
                while (isActive) {
                    manager.activeNotifications.filter { it.id == 1 && it.packageName == app.packageName }.forEach {
                        seen.putIfAbsent("${it.tag}:${it.postTime}", JSONObject().put("tag", it.tag).put("quiet", it.notification.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY)
                            .put("title", it.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()).put("at", System.currentTimeMillis()))
                    }
                    delay(50)
                }
            }
            send("Write a 400 word note about tides for a sailing class. Plain text only. Do not use any tools.")
            await(180_000, "the tides words") { vm.chat.value.liveText.length > 40 }
            // A Send now just before the screen goes off: the turn ends while Beacon is away, and the steer must still land or wait as Not sent.
            val off = "H1 steer screen $run. End with the word TEAL."
            steer(off)
            await(60_000, "Hermes to take the screen steer") { vm.chat.value.queued.any { it.text == off && it.state != "sending" } }
            evidence.put("screen_steer_taken", vm.chat.value.queued.first { it.text == off }.state); save()
            shell("input keyevent KEYCODE_SLEEP"); delay(6_000)
            shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard")
            await(30_000, "Beacon back in front") { vm.chat.value.sessionId == screenChat &&compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            evidence.put("screen_shown_chat", dev.hermes.android.push.ShownChat.scope?.session.orEmpty()); save()
            idle("the tides reply", 240_000)
            repeat(3) { i -> delay(1_000); shot("screen-after-end-$i") }
            delay(8_000)
            watch.cancelAndJoin()
            val left = manager.activeNotifications.filter { it.id == 1 && it.packageName == app.packageName }.map { it.tag }
            evidence.put("screen", JSONObject().put("seen", JSONArray(seen.values.toList())).put("left", JSONArray(left))); save()
            assertTrue("Every alert posted while Beacon showed the chat came quietly", seen.values.all { it.getBoolean("quiet") })
            assertTrue("No alert of the open chat stays", left.isEmpty())
            idle("the screen steer's own turn", 240_000)
            val savedS = rows(screenChat)
            val rowS = savedS.lastOrNull { it.optString("role") == "user" && text(it).contains(off) }
            evidence.put("screen_steer", JSONObject().put("row_kind", rowS?.optString("display_kind")).put("tail", tail(savedS)).put("queue", queue())); save()
            assertNotNull("The steer sent before the screen went off reached Hermes", rowS)
            assertTrue("No steer copy stays open", vm.chat.value.queued.none { it.openSteer() })
        }

        if ("alert" in phases) {
            idle("a quiet chat before the alert check")
            manager.cancelAll()
            // Chat A asks a question and waits; chat B is opened after it; Beacon goes to the background.
            val previous = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("model" to model, "provider" to provider)) }.join()
            await(60_000, "chat A") { vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId !in setOf(beaconChat, previous) }
            send("H1 alert $run. Call your clarify tool now with the question \"Which colour do you prefer?\" and the choices red and blue. Do not write anything before my answer.")
            try { await(240_000, "chat A's question") { vm.chat.value.clarification != null } }
            catch (e: AssertionError) {
                val open = vm.chat.value.sessionId
                evidence.put("alert_no_question", JSONObject().put("open", open).put("stream", vm.chat.value.streamId).put("status", vm.chat.value.status)
                    .put("waiting", vm.chat.value.waitingClarifications.size).put("tail", tail(rows(open)))); save(); stopTurn(open); throw e
            }
            // The chat's own id once Hermes saved it (a new chat starts under the phone's shell id).
            val chatA = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.tab("Beacon") }
            await(60_000, "chat B (the Beacon chat)") { vm.chat.value.sessionId == beaconChat && vm.chat.value.projectionReady }
            ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            fun question() = manager.activeNotifications.firstOrNull { it.id == 3 && it.packageName == app.packageName && it.notification.category == Notification.CATEGORY_STATUS }
            // The alert as Keep connected posts it; else the plugin's own question notice for chat A, posted by the same function (its route,
            // its tap intent and its routing are the real ones: only the delivery is skipped).
            val delivered = runCatching { await(60_000, "the question alert") { question() != null } }.isSuccess
            evidence.put("alert_delivered", delivered); save()
            if (!delivered) {
                val notice = dev.hermes.android.push.keepConnectedEvents(client, scope.profile, listOf(scope.profile), 0, 0).events
                    .lastOrNull { it.optString("kind") == "clarify" && it.optString("session_id") == chatA && it.optString("state") == "pending" }
                evidence.put("alert_notice", notice?.let { JSONObject().put("event", it.optString("event_id")).put("activity", it.optString("activity_ref")) } ?: JSONObject.NULL); save()
                checkNotNull(notice) { "The plugin holds no question notice for chat A" }
                assertTrue(app.push.bindKeepConnectedSource(scope, notice.getString("source_ref")))
                dev.hermes.android.push.presentNativeActivity(app, notice.getString("source_ref"), notice.getString("event_id"), notice.getString("activity_ref"), "clarify", notice.optLong("expires_at"))
                await(30_000, "the posted question alert") { question() != null }
            }
            val alert = checkNotNull(question())
            // A notification open bumps the sign-in generation first thing (handlePush): it tells whether the tap reached the app's routing at all.
            fun generation() = BeaconViewModel::class.java.getDeclaredField("originGeneration").apply { isAccessible = true }.getInt(vm)
            evidence.put("alert", JSONObject().put("chat_a", chatA).put("chat_b", beaconChat).put("generation_before", generation()).put("title", alert.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty())); save()
            // The owner's tap: the alert's row in the shade, else its own content intent.
            ui.serviceInfo = ui.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            shell("cmd statusbar expand-notifications"); delay(2_000)
            shot("alert-shade")
            val title = alert.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            var node: AccessibilityNodeInfo? = ui.windows.firstNotNullOfOrNull { w -> w.root?.findAccessibilityNodeInfosByText(title)?.firstOrNull() }
            while (node != null && !node.isClickable) node = node.parent
            val tapped = node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            if (!tapped) { shell("cmd statusbar collapse"); alert.notification.contentIntent.send() }
            evidence.getJSONObject("alert").put("tapped_in_shade", tapped); save()
            try { await(60_000, "chat A at its question") { frames(); vm.chat.value.sessionId == chatA && vm.chat.value.clarification != null } }
            finally {
                shot("alert-after-tap")
                evidence.getJSONObject("alert").put("opened", vm.chat.value.sessionId).put("question_shown", vm.chat.value.clarification != null).put("generation_after", generation())
                    .put("error", vm.ui.value.error).put("notice", vm.ui.value.notice).put("tab", vm.ui.value.tab); save()
                stopTurn(chatA)
            }
        }
        evidence.put("done", true); save()
    }
}
