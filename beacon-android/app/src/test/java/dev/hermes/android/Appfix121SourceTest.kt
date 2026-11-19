package dev.hermes.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * APPFIX-121 (1.2.1 backlog 47/66, 54, 27, 44). The view model needs Android to run, so its wiring is read from source (as OfflineFollowUpTest does);
 * the pure title helpers are tested by value in CallTranscriptTest, and the behaviour on a device in S3ComposerUiTest and NativeExperienceUiTest.
 */
class Appfix121SourceTest {
    private val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
    private fun source(path: String) = File(module, path).readText().replace("\r\n", "\n")
    private val vm get() = source("src/main/java/dev/hermes/android/BeaconViewModel.kt")

    /** 47/66: a refused or failed rename never stops the call-end reconcile from rendering and saving the chat. */
    @Test fun callEndRenameFailureStillRendersAndSaves() {
        val reconcile = vm.substringAfter("private fun reconcileCallConversation(").substringBefore("private fun restoreDraft(")
        assertTrue("the rename has its own failure handling", reconcile.contains("renameCallChat("))
        assertFalse("the rename is not left to the one catch that skips renderSession", reconcile.contains("client.post(\"/api/session/rename\", body)"))
    }

    /** 47/66: the title is checked against the chats this phone lists, so a repeat title is never sent. */
    @Test fun callEndTitleKnowsTheTitlesAlreadyTaken() {
        val reconcile = vm.substringAfter("private fun reconcileCallConversation(").substringBefore("private fun restoreDraft(")
        assertTrue(reconcile.contains("_ui.value.sessions"))
    }

    /** 47/66 (Root): the phrase and date come from the phone's language, locale and clock setting, and the name is the assistant's current one. */
    @Test fun callTitleWordsFollowThePhoneAndTheAssistantsName() {
        val words = vm.substringAfter("private suspend fun callTitleWords()").substringBefore("private fun reconcileCallConversation(")
        assertTrue(words.contains("translateUi(dictionary, \"Call with Beacon\")"))
        assertTrue(words.contains("assistantName(phrase, dev.hermes.android.data.currentAssistantName)"))
        assertTrue(words.contains("nativeDayPattern(locale") && words.contains("nativeClockPattern(locale, android.text.format.DateFormat.is24HourFormat(app))"))
    }

    /** 47/66: a chat given a call title is remembered, so the next call's title differs even before that empty chat is listed in History. */
    @Test fun callTitlesAlreadyGivenAreRemembered() {
        val reconcile = vm.substringAfter("private fun reconcileCallConversation(").substringBefore("private fun restoreDraft(")
        assertTrue(reconcile.contains("callChatTitles[ownerKey].orEmpty()"))
        assertTrue(reconcile.contains("callChatTitles.getOrPut(ownerKey)"))
    }

    /** 54: the command list reloads by itself after a profile switch. */
    @Test fun profileSwitchReloadsTheCommandList() {
        val switch = vm.substringAfter("private fun switchProfile(name: String, retainBotMode: Boolean)").substringBefore("private suspend fun openBotChat(")
        assertTrue(switch.contains("reloadCommandsAfterSwitch(next, generation)"))
    }

    /** 54: the reload goes on until the record is clean, so no Retry waits on a list the owner sees. */
    @Test fun theCommandReloadTriesAgainUntilTheRecordIsClean() {
        val reload = vm.substringAfter("private fun reloadCommandsAfterSwitch(").substringBefore("private suspend fun openBotChat(")
        assertTrue(reload.contains("COMMAND_RELOAD_TRIES"))
        // A quiet read: a failed try raises no app-wide banner (force alone does: loadFailureRaisesBanner).
        assertTrue(reload.contains("load(\"slash:catalog\", \"/api/commands/catalog\", force = true, quiet = true)"))
        assertTrue(reload.contains("recordError(_data.value[\"slash:catalog\"]).isBlank()"))
        assertTrue("a switch another tap replaced stops it", reload.contains("generation != originGeneration"))
    }

    /** 27: the two silent returns of switchProfile say so. */
    @Test fun switchProfileIsNeverSilent() {
        val switch = vm.substringAfter("private fun switchProfile(name: String, retainBotMode: Boolean)").substringBefore("private suspend fun openBotChat(")
        assertFalse("no client: not a silent return", switch.contains("val c = client?.detached() ?: return@launch"))
        assertTrue(switch.contains("Reconnect before switching profiles. Your drafts are kept."))
        assertFalse("changed sign-in: not a silent return", switch.contains(".takeIf { it.scope() == c.scope } ?: return@launch"))
        assertTrue(switch.contains("Sign-in changed while switching profiles. Reconnect and try again."))
        assertTrue("both exits (the saved sign-in, the re-read sign-in) say it", Regex("signInChanged\\(\\)").findAll(switch).count() >= 2)
    }

    /** 44: a New chat tap with a kept create marker asks Hermes again (the kept id is Hermes' retry identity); it is not a dead end. */
    @Test fun newChatWithAKeptCreateIsNotADeadEnd() {
        val tap = vm.substringAfter("    fun newChat(body: JSONObject = JSONObject()): Job {").substringBefore("fun reserveThread(")
        assertFalse(tap.contains("notice(\"Tap Retry to open the chat. Your draft is kept.\")"))
        val kept = tap.substringAfter("if (initialCreateAttempts.containsKey(owner.key)) {").substringBefore("// A legitimate New intent")
        assertTrue("the kept id is sent again with the plain request it was made for", kept.contains("createNewChat(json(\"worktree\" to false), initial = true).join()"))
        assertTrue("an unsettled create stays with its card", kept.contains("if (initialCreateAttempts.containsKey(owner.key) || options.length() == 0"))
    }

    /** 44: the setup harness waits for the first chat to finish and retries it when it failed. */
    @Test fun demoLiveSetupWaitsForTheFirstChat() {
        val setup = source("src/androidTest/java/dev/hermes/android/journey/DemoLiveSetupTest.kt")
        assertTrue(setup.contains("newChatOpening"))
        assertTrue(setup.contains("retryInitialChat()"))
    }
}
