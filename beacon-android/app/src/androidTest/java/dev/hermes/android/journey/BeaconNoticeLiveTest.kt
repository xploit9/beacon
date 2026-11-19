package dev.hermes.android.journey

import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.*
import dev.hermes.android.data.NativePairing
import dev.hermes.android.loginLiveTest
import dev.hermes.android.preferences
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.keepConnectedEvents
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/**
 * NOTIFY-121 live check on the demo Hermes (explicit opt-in, emulator only, inside demo-fixture isolation): a real Beacon chat reply, then the same after
 * Hermes continued the chat under a new session (compression). Records what the notification shade shows (text and a screenshot) and where the chat list
 * puts the Beacon chat; the coordinator's host script restores the app's original state.
 */
class BeaconNoticeLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()

    private suspend fun await(ms: Long, what: String, ready: suspend () -> Boolean) {
        try { withTimeout(ms) { while (!ready()) delay(250) } } catch (e: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $what") }
    }

    @Test(timeout = 1_700_000) fun aBeaconChatReplyIsNamedAfterTheAssistantAlsoAfterHermesMovedTheChat() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("beaconNoticeLive") == "1")
        val label = args.getString("beaconNoticeLabel").orEmpty().ifBlank { "run" }
        val app = compose.activity.application as BeaconApplication
        require(app.packageName.endsWith(".debug") && (android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))) { "emulator only" }
        require(!app.calls.state.value.active && app.calls.service == null)
        val file = File(app.filesDir, "live-auth.json")
        val credentials = JSONObject(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        check(file.delete() && credentials.string("purpose") == "beacon_visual_tour_demo")
        val origin = trustedOrigin(credentials.getString("origin"))
        val out = File(app.filesDir, "notify-live-$label").apply { deleteRecursively(); mkdirs() }
        val evidence = JSONObject().put("label", label).put("phases", JSONArray())
        fun save() = File(out, "evidence.json").writeText(evidence.toString(2))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ui = instrumentation.uiAutomation
        fun shell(command: String) = ui.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }
        val manager = app.getSystemService(NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 33) ui.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)

        loginLiveTest(app, origin, credentials)
        // Earlier trials left sends queued in the demo's old Beacon chat: this run opens a Beacon chat of its own, on the model that answers on the demo
        // (its default model is often rate limited and then hangs a turn for minutes). demo-fixture restores the app's preferences and sign-in, not this saved Beacon chat pointer or the chat itself: a run leaves a Beacon chat behind on the demo.
        val signedIn = app.auth.read(origin)
        val priorScope = Scope(origin, signedIn.owner, signedIn.profile, nativeProvider = signedIn.nativeProvider)
        app.cache.delete(priorScope, "beacon-session"); app.cache.delete(priorScope, "beacon-opening")
        app.preferences.edit { it[stringPreferencesKey("assistant_model")] = "command-code/deepseek-deepseek-v4.1-flash"; it[stringPreferencesKey("assistant_provider")] = "custom:opencodex" }
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
        await(60_000, "the connection") { vm.ui.value.connected && !vm.ui.value.restoringSession && vm.client != null }
        val client = checkNotNull(vm.client)
        val scope = client.scope.copy(session = "")
        NativePairing.deviceId(app, scope) ?: NativePairing.link(app, client)
        withContext(Dispatchers.Main.immediate) { vm.finishSetupWizard(keepConnected = true) }
        await(60_000, "Keep connected") { !vm.ui.value.setupWizard && !vm.ui.value.loading && KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED }
        evidence.put("profile", scope.profile).put("assistant_name", vm.preferences.value.assistantName)

        withContext(Dispatchers.Main.immediate) { vm.tab("Beacon") }
        await(90_000, "the Beacon chat") { !vm.ui.value.beaconOpening && vm.chat.value.projectionReady && vm.chat.value.sessionId.isNotBlank() && vm.isBeaconConversation() }
        val beaconChat = vm.chat.value.sessionId
        evidence.put("beacon_chat", beaconChat)
        evidence.put("assistant_model", vm.preferences.value.assistantModel)
        save()

        fun notices(): List<JSONObject> = manager.activeNotifications.filter { it.id == 1 && it.packageName == app.packageName }.map {
            JSONObject().put("tag", it.tag).put("title", it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty())
                .put("text", it.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()).put("posted", it.postTime)
                .put("has_reply", it.notification.actions.orEmpty().any { a -> !a.remoteInputs.isNullOrEmpty() })
        }
        fun shade(name: String) {
            shell("cmd statusbar expand-notifications"); Thread.sleep(2_000)
            val shot = ui.takeScreenshot()
            File(out, "$name.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            shell("cmd statusbar collapse")
        }
        // The app's own second send stays queued behind the first on the demo; the notification's Reply goes straight to the chat, so do the same.
        suspend fun assistantCount(): Int = client.get("/api/session", mapOf("session_id" to beaconChat), maxBytes = 1024 * 1024).rows("messages")
            .count { it.optString("role") == "assistant" && (it.optString("text").ifBlank { it.optString("content") }).isNotBlank() }
        suspend fun ask(text: String) {
            val had = assistantCount()
            client.post("/api/chat/start", json("session_id" to beaconChat, "message" to text, "attachments" to JSONArray(), "profile" to scope.profile, "client_request_id" to java.util.UUID.randomUUID().toString()))
            await(180_000, "the reply to a direct send") { assistantCount() > had }
        }
        suspend fun turn(phase: String, prompt: String, direct: Boolean = false) {
            runCatching { await(120_000, "the send queue to be empty") { vm.chat.value.queued.isEmpty() && vm.chat.value.streamId.isBlank() } }
            val before = notices().map { it.getString("tag") }.toSet()
            val answered = vm.chat.value.messages.count { it.role == "assistant" && it.text.isNotBlank() }
            if (direct) ask(prompt) else {
                withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue(prompt)); vm.send() }
                try {
                    await(240_000, "the reply to $phase") { vm.chat.value.streamId.isBlank() && vm.chat.value.messages.count { it.role == "assistant" && it.text.isNotBlank() } > answered }
                } catch (e: AssertionError) {
                    evidence.put("timeout-$phase", JSONObject().put("stream", vm.chat.value.streamId).put("queued", vm.chat.value.queued.size).put("messages", vm.chat.value.messages.map { it.role + ":" + it.text.take(30) }.toString())); save(); throw e
                }
            }
            // The owner is away from the app when the reply notice arrives.
            ui.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            // The real text replaces the typed fallback ("A reply is ready.") once Hermes' preview is read.
            await(120_000, "the $phase notification") { notices().any { it.getString("tag") !in before && it.getString("text").isNotBlank() && it.getString("text") != "A reply is ready." } }
            Thread.sleep(2_500)
            val posted = notices().filter { it.getString("tag") !in before }
            val page = keepConnectedEvents(client, scope.profile, listOf(scope.profile), 0, 0)
            val latest = page.events.lastOrNull { it.optString("kind") == "completion" }
            val route = latest?.optString("event_id")?.takeIf { it.isNotBlank() }?.let { id -> runCatching { client.nativeRequest("/api/plugins/beacon/mobile/notifications/events/$id") }.getOrNull() }
            evidence.getJSONArray("phases").put(JSONObject().put("phase", phase).put("notifications", JSONArray(posted))
                .put("latest_completion_session_id", latest?.optString("session_id").orEmpty()).put("route_assistant_chat", route?.opt("assistant_chat")?.toString().orEmpty()).put("route_session_id", route?.optString("session_id").orEmpty()).put("beacon_chat", beaconChat))
            save()
            shade(phase)
            manager.cancelAll()
        }

        turn("1-first-reply", "Reply with exactly three words: ready one now.")

        // The Beacon chat as the list gives it, and what the account says its chat is.
        suspend fun listing(phase: String) {
            val rows = client.get("/api/sessions", mapOf("profile" to scope.profile)).rows("sessions")
            val mine = rows.filter { r -> r.string("session_id", r.string("id")) == beaconChat || r.string("_lineage_root_id") == beaconChat || (r.optJSONArray("_lineage_ids")?.toString() ?: "").contains(beaconChat) }
            val registered = runCatching { client.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation").string("conversation_id") }
                .getOrElse { "failed:" + it.javaClass.simpleName + ":" + ((it as? dev.hermes.android.data.ApiFailure)?.status ?: 0) }
            evidence.put("listing-$phase", JSONObject().put("rows_for_beacon_chat", JSONArray(mine.map { JSONObject().put("id", it.string("session_id", it.string("id"))).put("title", it.string("title"))
                .put("lineage_root", it.string("_lineage_root_id")).put("lineage_ids", it.optJSONArray("_lineage_ids") ?: JSONArray()) })).put("registered_chat", registered))
            save()
        }
        listing("before-move")

        // Move the chat to a new Hermes session the way a long chat does: Hermes compresses it and carries on under a new id.
        var compressed = false
        // Hermes keeps the first 3 and the newest 20 messages as they are (a manual compress of a shorter chat answers "aborted"): talk until there is something to summarize.
        for (round in 1..16) {
            ask("Name one sea animal, round $round. One short line.")
            manager.cancelAll()
            if (round < 12) continue
            val status = runCatching { client.post("/api/session/compress", json("session_id" to beaconChat, "focus_topic" to "")).string("status") }.getOrElse { "failed:" + it.javaClass.simpleName }
            evidence.put("compress-$round", status); save()
            if (status == "compressed") { compressed = true; break }
        }
        evidence.put("chat_moved", compressed); save()
        if (compressed) listing("after-move")
        turn("2-after-move", "Reply with exactly three words: ready two now.", direct = true)
        listing("after-second-reply")
        // What the plugin says about the Beacon chat at the end (written last: it registers the chat, as the app does after a prompt).
        evidence.put("register_put", runCatching { client.nativeRequest("/api/plugins/beacon/mobile/assistant-conversation", "PUT", json("profile" to scope.profile, "session_id" to beaconChat)).toString().take(200) }
            .getOrElse { "failed:" + it.javaClass.simpleName + ":" + ((it as? dev.hermes.android.data.ApiFailure)?.status ?: 0) + ":" + it.message?.take(100) })
        evidence.put("done", true); save()
    }
}
