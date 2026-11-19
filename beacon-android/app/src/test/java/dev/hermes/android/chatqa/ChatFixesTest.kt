package dev.hermes.android.chatqa

import dev.hermes.android.BeaconViewModel
import dev.hermes.android.OFFLINE_OUTCOME
import dev.hermes.android.QueueBoxAction
import dev.hermes.android.attachmentName
import dev.hermes.android.core.Draft
import dev.hermes.android.core.QueuedMessage
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.sessionRowId
import dev.hermes.android.data.APP_CONNECTION_ERROR
import dev.hermes.android.data.NativeAuth
import dev.hermes.android.data.appErrorText
import dev.hermes.android.data.signInErrorText
import dev.hermes.android.queueBoxAction
import dev.hermes.android.ui.chatModelTitle
import dev.hermes.android.waitingForConnection
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CHATQA-121 follow-up 1 regressions: D5 archived rows, D6 offline queue, D12 sign-in words, D13 photo names, D21 model chip. */
class ChatFixesTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val owner = Scope("https://hermes.example.com", "owner-a", "default", "")

    private fun viewModelArchived(vararg rows: JSONObject): BeaconViewModel {
        val vm = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BeaconViewModel::class.java) as BeaconViewModel
        val here = LinkedHashMap<String, Pair<Scope, JSONObject>>()
        rows.forEach { here[owner.copy(session = sessionRowId(it)).key] = owner to it }
        BeaconViewModel::class.java.getDeclaredField("archivedHere").apply { isAccessible = true }.set(vm, here)
        return vm
    }
    @Suppress("UNCHECKED_CAST")
    private fun BeaconViewModel.withArchivedHere(rows: List<JSONObject>, query: String, archived: Boolean): List<JSONObject> =
        BeaconViewModel::class.java.getDeclaredMethod("withArchivedHere", List::class.java, Scope::class.java, List::class.java, String::class.java, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(this, rows, owner, listOf("default"), query, archived) as List<JSONObject>

    @Test fun d5AnArchivedChatShowsUnderArchivedAtOnceAndLeavesAll() {
        val archivedRow = json("session_id" to "chat-9", "title" to "Trip plan", "profile" to "default", "archived" to true)
        val vm = viewModelArchived(archivedRow)
        val groceries = json("session_id" to "chat-1", "title" to "Groceries", "profile" to "default")
        val stillListed = json("session_id" to "chat-9", "title" to "Trip plan", "profile" to "default")
        // All: gone at once, even while Hermes' list still has it.
        assertEquals(listOf("chat-1"), vm.withArchivedHere(listOf(groceries, stillListed), "", archived = false).map(::sessionRowId))
        // Archived: listed at once, before Hermes' bounded hidden-chat scan returns it; never twice once it does.
        assertEquals(listOf("chat-9"), vm.withArchivedHere(emptyList(), "", archived = true).map(::sessionRowId))
        assertEquals(listOf("chat-9"), vm.withArchivedHere(listOf(stillListed.copyWith("archived", true)), "", archived = true).map(::sessionRowId))
        assertEquals(emptyList<String>(), vm.withArchivedHere(emptyList(), "Groceries", archived = true).map(::sessionRowId))
        // Search outside Archived: the row says it is archived, so its menu offers Unarchive.
        assertTrue(vm.withArchivedHere(listOf(stillListed), "Trip", archived = false).single().optBoolean("archived"))
    }

    @Test fun d6AnOfflineSendWaitsInTheQueueWithRetryAndKeepsItsIdentity() {
        val waiting = QueuedMessage("Book the 9am slot", id = "send-1", state = "queued", outcome = OFFLINE_OUTCOME, submissionId = "send-1")
        assertTrue(waiting.waitingForConnection())
        assertEquals(QueueBoxAction.Retry, queueBoxAction(waiting, replying = false))
        assertEquals(QueueBoxAction.Send, queueBoxAction(waiting.copy(outcome = ""), replying = false))
        // A restart keeps it: same id and submission id (Hermes takes one turn per submission id, so it is never sent twice).
        val restored = Draft.decode(Draft(queued = listOf(waiting)).encode()).queued.single()
        assertEquals(listOf("send-1", "send-1", "queued", OFFLINE_OUTCOME), listOf(restored.id, restored.submissionId, restored.state, restored.outcome))
        assertFalse(waiting.copy(state = "sending").waitingForConnection())
    }

    @Test fun d12AClosedSignInTabSaysSoInsteadOfCantReachHermes() {
        val closed = NativeAuth.mapSignInFailure(java.net.SocketException("Socket closed"), cancelledOnResume = true)
        assertEquals(APP_CONNECTION_ERROR, appErrorText(closed))
        assertEquals("Sign-in wasn't finished. Tap Sign in with Hermes to try again.", signInErrorText(closed))
        assertEquals(APP_CONNECTION_ERROR, signInErrorText(java.io.IOException("unreachable")))
    }

    @Test fun d13APhotoPickerItemIsCalledPhotoNotItsMediaNumber() {
        assertEquals("Photo.png", attachmentName("content://media/picker/0/com.android.providers.media.photopicker/media/753", "753.png"))
        assertEquals("Photo.jpg", attachmentName("content://media/picker_get_content/0/com.android.providers.media.photopicker/media/12", "12.JPG"))
        assertEquals("report.pdf", attachmentName("content://com.android.providers.downloads.documents/document/42", "report.pdf"))
        assertEquals("A document's own number-only name is kept", "2024.csv", attachmentName("content://com.android.externalstorage.documents/document/primary%3A2024.csv", "2024.csv"))
        assertEquals("Attachment", attachmentName("content://media/picker/0/x/media/1", " "))
    }

    @Test fun d21TheModelChipShowsThePickersName() {
        val groups = listOf(
            json("provider_id" to "command-code", "provider" to "Command Code", "models" to JSONArray(listOf(json("id" to "deepseek-deepseek-v4.1-flash")))),
            json("provider_id" to "fixture", "provider" to "Fixture", "models" to JSONArray(listOf(json("id" to "gpt-6-luna", "label" to "GPT-6 Luna")))),
        )
        assertEquals("Deepseek V4.1 Flash", chatModelTitle("command-code/deepseek-deepseek-v4.1-flash", groups))
        assertEquals("GPT-6 Luna", chatModelTitle("gpt-6-luna", groups))
        assertEquals("Not in the picker: the id read as a name", "Synthetic Model", chatModelTitle("synthetic-model", emptyList()))
        assertEquals("", chatModelTitle("", groups))
    }

    private fun JSONObject.copyWith(key: String, value: Any): JSONObject = JSONObject(toString()).put(key, value)
}
