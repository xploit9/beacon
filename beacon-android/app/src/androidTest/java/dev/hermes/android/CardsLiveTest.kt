package dev.hermes.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.input.TextFieldValue
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.ApiFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Opt-in demo-authority check for CARDS-121 (backlog 17 and 18): a real approval from Hermes answered from the card, then a real approval whose answer cannot go because the phone's
 * network is cut, which the card must say, keep, and answer once the network is back. The synthetic chat uses the demo's working model, set on its own session (the demo config is never touched),
 * and Hermes' own always-ask approval: a write to a protected agent-instruction file (tools/file_tools_write_guards.py, asked every time, never decided
 * by the guardian or auto-approve). Each write names a literal path in a folder of the check's own and is denied, so nothing is written. Everything
 * the check created is deleted.
 */
class CardsLiveTest {
    @get:Rule val compose = createAndroidComposeRule<BeaconActivity>()

    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    @Test(timeout = 900_000) fun aRealApprovalIsAnsweredFromTheCardAndAFailedAnswerIsShownHonestly() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconCardsLive") == "1")
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(BuildConfig.DEBUG && app.packageName.endsWith(".debug"))
        require(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.FINGERPRINT.contains("emulator") || android.os.Build.MODEL.contains("sdk"))
        require(!app.calls.state.value.active && app.calls.service == null)
        val prior = app.preferences.data.first()
        require(prior[stringPreferencesKey("origin")].isNullOrBlank()) { "Use an isolated debug install without a saved connection" }
        val authFile = File(app.filesDir, "live-auth.json")
        val credentials = try { JSONObject(authFile.readText(Charsets.UTF_8).removePrefix("﻿")) } finally { if (authFile.exists()) check(authFile.delete()) }
        // Chat join (rules25): the shared demo fixture stages its sign-in as "beacon_visual_tour_demo" (demo-fixture.py), as the other live tests read it.
        require(credentials.optString("purpose") in setOf("beacon_busy_send_demo", "beacon_visual_tour_demo")) { "A dedicated demo fixture is required" }
        val origin = trustedOrigin(credentials.getString("origin"))
        val existingAuth = app.auth.read(origin)
        require(existingAuth.owner.isBlank() && existingAuth.cookies.isEmpty() && existingAuth.bearer == null && existingAuth.loopbackSession == null) { "Use a debug install without saved credentials" }
        lateinit var vm: BeaconViewModel
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        suspend fun await(what: String, ms: Long = 150_000, pollMs: Long = 100, ready: suspend () -> Boolean) {
            try { withTimeout(ms) { while (!ready()) delay(pollMs) } } catch (e: TimeoutCancellationException) { throw AssertionError("Timed out waiting for $what: ${state(vm)}") }
        }
        val log = StringBuilder()
        fun say(line: String) { log.appendLine(line); println("CARDS_LIVE $line") }
        var createdScope: Scope? = null
        val title = "Beacon cards QA ${UUID.randomUUID()}"
        val directory = File(app.filesDir, "cards-live").apply { mkdirs() }
        fun shot(name: String) {
            compose.waitForIdle()
            val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            loginLiveTest(app, origin, credentials)
            withContext(Dispatchers.Main.immediate) { vm.connect(origin) }.join()
            await("connected") { vm.ui.value.connected && vm.client != null }
            withContext(Dispatchers.Main.immediate) { vm.finishSetupWizard(keepConnected = false) }
            val previous = vm.chat.value.sessionId
            withContext(Dispatchers.Main.immediate) { vm.newChat(json("title" to title, "model" to "command-code/deepseek-deepseek-v4.1-flash", "model_provider" to "custom:opencodex")) }.join()
            await("the QA chat") { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != previous && vm.chat.value.title == title && vm.chat.value.projectionReady }
            val scope = requireNotNull(vm.authenticatedScope())
            require(app.cache.read(scope, "client-created-shell") != null) { "The QA chat was not proven to be newly created" }
            createdScope = scope
            say("chat created, model command-code/deepseek-deepseek-v4.1-flash via custom:opencodex")

            // The check's own folder for the writes Hermes asks about (a literal relative path; both are denied, so it is never made).
            val folder = "beacon-cards-live-${UUID.randomUUID().toString().take(8)}"
            // 1. A real approval, answered from the card by a real tap.
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("Use the write_file tool to write the single word synthetic to this exact path and nothing else: $folder/AGENTS.md. Use no other tool. Then say whether it was written.")); vm.send() }.join()
            await("the first approval card") { vm.chat.value.approval != null }
            val first = vm.chat.value.approval!!.string("approval_id", vm.chat.value.approval!!.string("id"))
            say("approval card up: id=$first; settle window ${PromptSettleWindow.ms} ms")
            compose.waitUntil(30_000) { compose.onAllNodesWithTextSafe("Deny") }
            CardMoveProbe.waitToSettle(compose, "Deny")
            shot("1-card-up")
            CardMoveProbe.answerWhenSettled(compose, "Deny") { vm.chat.value.approval == null || vm.chat.value.approval?.string("approval_id", "") != first }
            say("first approval answered from the card (Deny); card closed")
            // The turn has ended only when its reply is in. Hermes may ask again in the same turn (the model can try the write once more): each such card is
            // denied from the card too, until the turn ends.
            var denied = setOf(first)
            fun firstTurnEnded() = vm.chat.value.streamId.isBlank() && vm.chat.value.approval == null && vm.chat.value.status.isBlank() &&
                vm.chat.value.messages.lastOrNull()?.role == "assistant" && vm.chat.value.queued.none { it.state in setOf("queued", "sending", "accepted") }
            val turnDeadline = android.os.SystemClock.elapsedRealtime() + 180_000
            while (!firstTurnEnded() && android.os.SystemClock.elapsedRealtime() < turnDeadline) {
                val again = vm.chat.value.approval?.let { it.string("approval_id", it.string("id")) }
                if (again != null && again !in denied) {
                    say("Hermes asked again in the same turn: id=$again; denied from the card")
                    CardMoveProbe.waitToSettle(compose, "Deny")
                    val before = vm.chat.value.lastAnswerAt
                    CardMoveProbe.answerWhenSettled(compose, "Deny") { vm.chat.value.approval?.string("approval_id", "") != again || vm.chat.value.lastAnswerAt != before }
                    denied = denied + again
                }
                delay(200)
            }
            await("the reply to the first, denied write", 1_000) { firstTurnEnded() }
            say("Hermes went on after the Deny: its turn ended, nothing was written")
            shot("2-answered")

            // 2. A second real approval whose answer cannot go: the phone loses the network just before the tap.
            withContext(Dispatchers.Main.immediate) { vm.composer(TextFieldValue("Use the write_file tool to write the single word synthetic to this exact path and nothing else: $folder/CLAUDE.md. Use no other tool. Then say whether it was written.")); vm.send() }.join()
            await("the second approval card") { vm.chat.value.approval != null && vm.chat.value.approval?.string("approval_id", vm.chat.value.approval?.string("id").orEmpty()) !in denied }
            val second = vm.chat.value.approval!!.string("approval_id", vm.chat.value.approval!!.string("id"))
            say("second approval card up: id=$second")
            compose.waitUntil(30_000) { compose.onAllNodesWithTextSafe("Deny") }
            CardMoveProbe.waitToSettle(compose, "Deny")
            say("network cut: ${listOf("svc wifi disable", "svc data disable").joinToString(" | ") { shell(it).ifBlank { "ok" } }}")
            delay(1_500)
            val tappedBefore = vm.chat.value.lastAnswerAt
            CardMoveProbe.answerWhenSettled(compose, "Deny", done = { vm.ui.value.error.isNotBlank() || vm.ui.value.notice.contains("didn't go") || vm.chat.value.lastAnswerAt != tappedBefore })
            await("the failed answer to be shown", 60_000) { vm.ui.value.error.isNotBlank() || vm.ui.value.notice.contains("didn't go") }
            say("failed answer shown: error='${vm.ui.value.error}' notice='${vm.ui.value.notice}'; card still up=${vm.chat.value.approval != null}; connected=${vm.ui.value.connected} status='${vm.chat.value.status}'")
            shot("3-failed-answer-shown")
            val cardStayed = vm.chat.value.approval != null
            say("network back: ${listOf("svc wifi enable", "svc data enable").joinToString(" | ") { shell(it).ifBlank { "ok" } }}")
            await("the connection back", 120_000) { vm.ui.value.connected && vm.client != null }
            await("the card to be answerable again after the reconnect", 60_000) { vm.chat.value.approval?.string("approval_id", vm.chat.value.approval?.string("id").orEmpty()) == second }
            say("card answerable again after the reconnect: id=$second")
            CardMoveProbe.waitToSettle(compose, "Deny")
            CardMoveProbe.answerWhenSettled(compose, "Deny") { vm.chat.value.approval?.string("approval_id", vm.chat.value.approval?.string("id").orEmpty()) != second }
            say("second answer (Deny) went once the network was back")
            await("the second run to end", 180_000) { vm.chat.value.streamId.isBlank() && vm.chat.value.approval == null && vm.chat.value.status.isBlank() &&
                vm.chat.value.messages.lastOrNull()?.role == "assistant" }
            shot("4-after")
            say("CARDS_LIVE_RESULT cardStayed=$cardStayed")
        } finally {
            for (command in listOf("svc wifi enable", "svc data enable")) try { shell(command) } catch (_: Throwable) {}
            File(directory, "log.txt").writeText(log.toString())
            try {
                val scope = createdScope
                if (scope != null && vm.authenticatedScope() == scope && vm.chat.value.sessionId == scope.session) {
                    if (vm.chat.value.streamId.isNotBlank()) withContext(Dispatchers.Main.immediate) { vm.cancel() }.join()
                    withTimeoutOrNull(20_000) { while (vm.chat.value.streamId.isNotBlank()) delay(100) }
                    val current = requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session))
                    require(current.string("session_id") == scope.session && current.string("title") == title) { "The QA conversation identity changed; it was not deleted" }
                    withContext(Dispatchers.Main.immediate) { vm.action("/api/session/delete", json("session_id" to scope.session), refresh = false, successNotice = "") }.join()
                    val gone = runCatching { requireNotNull(vm.client).get("/api/session", mapOf("session_id" to scope.session)) }.exceptionOrNull()
                    say("cleanup: deleted=${gone is ApiFailure && gone.status == 404}")
                    (listOf("draft", "pending", "session", "running-turn")).forEach { app.cache.delete(scope, it) }
                    File(directory, "log.txt").writeText(log.toString())
                }
            } finally {
                try { app.auth.remove(origin) } finally { app.preferences.updateData { prior } }
            }
        }
    }

    private fun state(vm: BeaconViewModel): String {
        val chat = vm.chat.value; val ui = vm.ui.value
        return "connected=${ui.connected} error='${ui.error}' notice='${ui.notice}' stream='${chat.streamId}' status='${chat.status}' approval='${chat.approval?.string("approval_id", "")}' messages=${chat.messages.size}"
    }
    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextSafe(text: String): Boolean = onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
}
