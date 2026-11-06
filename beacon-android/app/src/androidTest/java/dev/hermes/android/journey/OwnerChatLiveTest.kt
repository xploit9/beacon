package dev.hermes.android.journey

import android.content.Intent
import android.content.IntentFilter
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.core.string
import dev.hermes.android.data.nativeSessionFlag
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.translateUi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class OwnerChatLiveTest {
    private val compose = createAndroidComposeRule<BeaconActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(ownerRuntimeOptIn).around(compose)

    @Test(timeout = 360_000) fun newThreadSendAndCleanupStayInTheQaProfile() = runBlocking {
        val h = OwnerJourneyHarness(compose, "chat")
        h.checked {
            qa()
            check(!vm.ui.value.botModeEnabled)
            check(vm.composer.value.text.isBlank())
            val title = "Beacon QA ${runId}"
            val oldSession = vm.chat.value.sessionId
            var session = ""
            var createdVerified = false
            var createUnconfirmed = false
            var sendUnconfirmed = false
            var editUnconfirmed = false
            val sawStream = AtomicBoolean(false)
            val observation = launch { vm.chat.collect { if (it.streamId.isNotBlank()) sawStream.set(true) } }
            suspend fun projection() = client.get("/api/session", mapOf("session_id" to session)).also { current() }
            try {
                compose.runOnIdle { vm.tab("Chat") }
                compose.onNodeWithTag("navigation-toggle").performClick()
                await { vm.ui.value.showSessions }
                compose.mainClock.advanceTimeBy(600)
                compose.waitForIdle()
                compose.onNodeWithTag("navigation-new-thread").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
                await(10_000) { compose.onAllNodesWithTag("new-thread-name").fetchSemanticsNodes().size==1 }
                mark("C-11", "thread_dialog_opened", "passed")
                compose.onNodeWithTag("new-thread-name").performClick().performTextInput(title)
                createUnconfirmed = true
                compose.onNodeWithTag("new-thread-start").performClick()
                await(60_000) { vm.chat.value.sessionId.isNotBlank() && vm.chat.value.sessionId != oldSession && vm.chat.value.title == title && vm.chat.value.projectionReady }
                session = vm.chat.value.sessionId
                check(vm.authenticatedScope() == scope.session(session))
                check(app.cache.read(scope.session(session), "client-created-shell") != null)
                createdVerified = true
                createUnconfirmed = false
                mark("C-11", "thread_created", "passed")

                compose.onNodeWithTag("composer-attach").performClick()
                compose.onNodeWithTag("attach-menu").assertExists()
                androidx.test.espresso.Espresso.pressBack()
                compose.onNodeWithTag("composer-model-effort").performClick()
                compose.onNodeWithTag("catalog-sheet").assertExists()
                androidx.test.espresso.Espresso.pressBack()
                mark("C-06", "attachment_menu_cancel", "passed")
                mark("C-08", "model_picker_cancel", "passed")

                compose.onNodeWithTag("message-input").performTextInput("Reply with two short sentences about a fictional blue kite. Do not use tools or contact anyone.")
                current(); sendUnconfirmed = true
                compose.onNodeWithTag("message-send").performClick()
                await(45_000) { vm.chat.value.streamId.isNotBlank() || vm.chat.value.messages.any { it.role == "assistant" && it.text.isNotBlank() } }
                var queued = false
                if (vm.chat.value.streamId.isNotBlank()) {
                    compose.onNodeWithTag("message-input").performTextInput("/queue Answer QA only. Do not use tools or contact anyone.")
                    val busyAtTap = vm.chat.value.streamId.isNotBlank()
                    compose.onNodeWithTag("message-send").performClick()
                    queued = true
                    mark("C-05", "busy_queue_attempted", if (busyAtTap) "passed" else "needs_human")
                } else mark("C-05", "busy_queue_attempted", "needs_human")
                await(150_000) {
                    current()
                    val chat = vm.chat.value
                    chat.sessionId == session && chat.streamId.isBlank() && chat.uncertain.isBlank() &&
                        chat.messages.count { it.role == "user" } >= (if (queued) 2 else 1) &&
                        chat.messages.count { it.role == "assistant" && it.text.isNotBlank() } >= (if (queued) 2 else 1)
                }
                val saved = projection().let { it.optJSONObject("session") ?: it }
                check(saved.string("session_id") == session && saved.string("profile", scope.profile) == scope.profile)
                val rows = saved.rows("messages")
                check(rows.count { it.string("role") == "user" } >= (if (queued) 2 else 1) &&
                    rows.count { it.string("role") == "assistant" } >= (if (queued) 2 else 1))
                sendUnconfirmed = false
                mark("C-04", "send_and_history_readback", "passed")
                mark("C-04", "stream_observed", if (sawStream.get()) "passed" else "needs_human")
                if (queued) mark("C-05", "queued_history_readback", "passed")

                val language = nativeLanguageFor(compose.activity.resources.configuration.locales[0])
                val dictionary = if (language == "en") JSONObject() else app.readTranslations().optJSONObject(language) ?: JSONObject()
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                val chooser = instrumentation.addMonitor(IntentFilter(Intent.ACTION_CHOOSER), null, false)
                var chooserClosed = false
                try {
                    compose.onNodeWithTag("chat-header-title").performClick()
                    compose.onNodeWithText(translateUi(dictionary, "Share conversation")).performClick()
                    await(10_000) { chooser.hits > 0 }
                    await(10_000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    check(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                    chooserClosed = true
                    await(10_000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                    current()
                    mark("C-13", "share_chooser_cancel", "passed")
                } finally {
                    if (!chooserClosed && chooser.hits > 0 && !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                        runCatching { instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK) }
                    instrumentation.removeMonitor(chooser)
                }
                val renamed = "$title edited"
                compose.onNodeWithTag("chat-header-title").performClick()
                compose.onNodeWithTag("conversation-action:Rename").performClick()
                await { vm.form.value?.path == "/api/session/rename" }
                val renameField = compose.onNode(hasSetTextAction() and hasText(title))
                renameField.performClick().performTextReplacement(renamed)
                compose.onNode(hasSetTextAction() and hasText(renamed, substring = false)).assertIsDisplayed()
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                compose.waitForIdle()
                editUnconfirmed = true
                compose.onNodeWithText(translateUi(dictionary, "Save")).assertIsDisplayed().assertIsEnabled().performClick()
                await(30_000) { vm.form.value == null && vm.chat.value.title == renamed }
                check(client.nativeGet("/api/sessions/$session", mapOf("profile" to scope.profile)).string("title") == renamed)
                editUnconfirmed = false
                mark("C-03", "rename_readback", "passed")

                compose.onNodeWithTag("chat-header-title").performClick()
                editUnconfirmed = true
                compose.onNodeWithTag("conversation-action:Pin").performClick()
                await(30_000) { nativeSessionFlag(client.nativeGet("/api/sessions/$session", mapOf("profile" to scope.profile)), "pinned") }
                editUnconfirmed = false
                mark("C-03", "pin_readback", "passed")

                compose.onNodeWithTag("chat-header-title").performClick()
                editUnconfirmed = true
                compose.onNodeWithTag("conversation-action:Archive").performClick()
                await(30_000) { vm.chat.value.raw.optBoolean("archived") && nativeSessionFlag(client.nativeGet("/api/sessions/$session", mapOf("profile" to scope.profile)), "hidden") }
                editUnconfirmed = false
                mark("C-03", "archive_readback", "passed")
                compose.onNodeWithTag("chat-header-title").performClick()
                editUnconfirmed = true
                compose.onNodeWithTag("conversation-action:Archive").performClick()
                await(30_000) { !vm.chat.value.raw.optBoolean("archived") && !nativeSessionFlag(client.nativeGet("/api/sessions/$session", mapOf("profile" to scope.profile)), "hidden") }
                editUnconfirmed = false
                mark("C-03", "unarchive_readback", "passed")
            } finally {
                observation.cancelAndJoin()
                try {
                    if (createUnconfirmed || sendUnconfirmed || editUnconfirmed) mark("C-04", "chat_outcome_unconfirmed", "needs_human")
                    else if (session.isNotBlank()) {
                        current()
                        val saved = projection().let { it.optJSONObject("session") ?: it }
                        check(saved.string("session_id") == session && saved.string("profile", scope.profile) == scope.profile)
                        // The app intentionally retires its client-created-shell marker after the first accepted prompt;
                        // creation was verified above, and the unique QA title and exact scope must still match.
                        check(createdVerified && vm.authenticatedScope() == scope.session(session) && saved.string("title") in setOf(title, "$title edited"))
                        // The app's native delete verifies this exact session is idle and confirms its close and removal.
                        val receipt = client.post("/api/session/delete", json("session_id" to session, "scope_key" to scope.session(session).key))
                        current()
                        check(receipt.string("deleted") == session)
                        val listed = client.get("/api/sessions", mapOf("profile" to scope.profile)).rows("sessions")
                        check(listed.none { sessionRowId(it) == session })
                        mark("C-11", "thread_cleanup", "passed")
                    }
                } catch (error: Throwable) {
                    runCatching { mark("C-11", "thread_cleanup_unconfirmed", "needs_human") }
                    throw error
                }
            }
        }
    }
}
