package dev.hermes.android.chatqa

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.AppState
import dev.hermes.android.ARCHIVED_HERE_AT
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.OFFLINE_OUTCOME
import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.nextOfflineToSend
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** CHATQA-121 follow-up 2 (REVIEW-CHATQA): P2-1, P2-2, P2-3 and the P3s, each failing on fix 1 and passing after. */
class OfflineFollowUpTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val owner = Scope("https://hermes.example.com", "owner-a", "default", "")
    private val chat = owner.session("chat-1")

    private fun viewModel(): BeaconViewModel = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BeaconViewModel::class.java) as BeaconViewModel
    private fun BeaconViewModel.set(name: String, value: Any?) = apply { BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(this, value) }

    private fun offlineChat(open: Scope?): BeaconViewModel = viewModel()
        .set("client", null).set("cachedOwnerScope", owner).set("sourceScope", open).set("saved", SavedStateHandle())
        .set("preferences", MutableStateFlow(AppPreferences())).set("_ui", MutableStateFlow(AppState(tab = "Chat")))
        .set("_chat", MutableStateFlow(ChatState(sessionId = open?.session.orEmpty(), raw = json("session_id" to open?.session.orEmpty()))))

    @Test fun p22WithNoConnectionSendStaysOnAndQueues() {
        // An app started offline has no client (connect() installs it only after a bootstrap): Send must still work.
        assertNull("offline, the open chat's Send works", offlineChat(chat).sendRefusal())
        // With no chat open, Send stays off and says why.
        assertEquals("Open a chat first.", offlineChat(null).set("_chat", MutableStateFlow(ChatState())).sendRefusal())
    }

    @Test fun p23TheNextMessageToSendKeepsTheOrderOfAChat() {
        val sent = QueuedMessage("Already with Hermes", id = "a", state = "accepted")
        val first = QueuedMessage("First while offline", id = "b", state = "queued", outcome = OFFLINE_OUTCOME)
        val second = QueuedMessage("Second while offline", id = "c", state = "queued", outcome = OFFLINE_OUTCOME)
        assertEquals("b", nextOfflineToSend(Draft(queued = listOf(sent, first, second)))?.id)
        assertEquals("c", nextOfflineToSend(Draft(queued = listOf(sent, first.copy(state = "accepted"), second)))?.id)
        // A message waiting on a reply, or one Hermes must confirm, goes first in its chat: nothing behind it is sent from here.
        assertNull(nextOfflineToSend(Draft(queued = listOf(QueuedMessage("Waits for the reply", id = "q", state = "queued"), first))))
        assertNull(nextOfflineToSend(Draft(queued = listOf(QueuedMessage("Outcome unknown", id = "u", state = "unknown"), first))))
        assertNull("a claimed message is not sent twice", nextOfflineToSend(Draft(queued = listOf(first.copy(state = "sending"), second))))
        assertNull(nextOfflineToSend(Draft()))
    }

    @Test fun p33AChatUnarchivedElsewhereIsForgotten() {
        val now = System.currentTimeMillis()
        fun kept(id: String, at: Long) = owner.session(id).key to (owner to json("session_id" to id, "profile" to "default", "archived" to true, ARCHIVED_HERE_AT to at))
        val here = linkedMapOf(kept("old", now - 60_000), kept("fresh", now - 2_000), kept("unlisted", now - 60_000))
        val vm = viewModel().set("archivedHere", here)
        val listed = listOf(json("session_id" to "old", "profile" to "default"), json("session_id" to "fresh", "profile" to "default"))
        BeaconViewModel::class.java.getDeclaredMethod("forgetUnarchivedElsewhere", List::class.java, Scope::class.java, Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(vm, listed, owner, now)
        assertEquals("Hermes lists it as not archived well after the archive", setOf(owner.session("fresh").key, owner.session("unlisted").key), here.keys)
    }

    @Test fun p34TheNewLinesAreInEveryLanguage() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val catalog = JSONObject(File(module, "src/main/assets/native-locales.json").readText())
        val lines = listOf("Not sent yet. It goes when Hermes is back.", "Sign-in wasn't finished. Tap Sign in with Hermes to try again.")
        for (language in catalog.keys()) for (line in lines) {
            val text = catalog.getJSONObject(language).optString(line.lowercase())
            assertTrue("$language is missing \"$line\"", text.isNotBlank())
            if (language != "en") assertFalse("$language shows \"$line\" in English", text == line)
        }
    }

    @Test fun p21AFailedNotificationOpenFollowsTheKeptChatAgain() {
        // The view model needs Android to run a notification open; its wiring is read from source (as BeaconStaleChatTest does).
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val push = vm.substringAfter("fun handlePush(").substringBefore("override fun onCleared()")
        assertTrue("the chat is marked left where it is left", push.contains("left = true\n            clearDetails(); retireSavedDrafts(null); sessionWatch?.cancel()"))
        assertTrue("a failure that kept the chat follows it again", push.contains("if (!left && generation != -1) followKeptChat(generation, keptScope)"))
        val switch = vm.substringAfter("private fun switchProfile(name: String, retainBotMode: Boolean)").substringBefore("private suspend fun openBotChat(")
        assertTrue(switch.contains("finally { if (!left) followKeptChat(generation, oldScope) }"))
    }

    @Test fun p35QueueAndSteerSayWhyWithNoOpenChat() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val busy = vm.substringAfter("private fun submitBusy(").substringBefore("private suspend fun sendSteer(")
        assertFalse("no silent return without an open chat", busy.contains("val scope = sourceScope ?: return@launch"))
        assertTrue(busy.contains("val scope = sourceScope ?: run { notice(\"Open a chat first.\"); return@launch }"))
    }

    /** P2-3: a chat that waits long for its reply never holds a chat whose messages were queued later; each "Hermes is back" starts those. */
    @Test fun p23AChatStillWaitingDoesNotHoldAChatQueuedLater() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val drain = vm.substringAfter("internal fun drainOfflineOutbox(", "").substringBefore("private suspend fun sendWaiting(")
        assertTrue("the drain is found", drain.isNotBlank())
        assertFalse("a drain still running is not the answer to the next one", drain.contains("?.takeIf { it.isActive }?.let { return it }"))
        assertTrue("every drain reads the outbox again", drain.contains("for (id in readOutbox(owner))"))
        assertTrue("one job per chat; only that chat is skipped while it goes", drain.contains("if (outboxSends[scope.key]?.isActive == true) continue"))
    }

    /** P2-3: a chat left while its messages still wait (its reply still running) sends them from the background; opening a chat is enough. */
    @Test fun p23LeavingAChatSendsItsWaitingMessages() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
        val vm = File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n")
        val select = vm.substringAfter("    fun selectSession(id: String,", "").substringBefore("    internal suspend fun awaitSelection()")
        assertTrue(select.contains("sourceScope = scope; clearDetails()\n            // P2-3: the chat just left may still hold waiting messages; they go from the background now.\n            drainOfflineOutbox(openQueue = false)"))
    }
}
