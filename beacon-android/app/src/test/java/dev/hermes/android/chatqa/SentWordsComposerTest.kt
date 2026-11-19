package dev.hermes.android.chatqa

import androidx.compose.ui.text.input.TextFieldValue
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.core.Draft
import dev.hermes.android.core.PendingSend
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.ui.quoteExcerpt
import dev.hermes.android.ui.quotedDraft
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHATQA-121 D10 regression (was MidFlightComposerProofTest): the words a send is taking leave the composer at tap time and stay in the
 * PendingSend. Until the composer lets go of them (the moment between the tap and the saved receipt) a quote, dictation or share builds on
 * an empty composer, so the sent words never go a second time; typed words, once there, are the next message and are kept.
 */
class SentWordsComposerTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private val chat = Scope("https://hermes.example.com", "owner-a", "default", "chat-1")
    private val sent = Draft("Book the 9am slot", attachments = listOf(json("id" to "file-1", "name" to "Photo.png")))
    private val send = PendingSend.create(chat, sent)

    private fun viewModel(composer: String, attachments: List<JSONObject>, state: ChatState, sending: PendingSend? = send): BeaconViewModel {
        val vm = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BeaconViewModel::class.java) as BeaconViewModel
        fun set(name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
        set("sourceScope", chat)
        set("pending", mutableMapOf<String, PendingSend>().apply { if (sending != null) put(chat.key, sending) })
        set("_chat", MutableStateFlow(state))
        set("_composer", MutableStateFlow(TextFieldValue(composer)))
        set("_attachments", MutableStateFlow(attachments))
        return vm
    }
    private val sendingChat = ChatState(sessionId = "chat-1", uncertain = send.id, status = "Sending")

    @Test fun aQuoteDuringTheSendDoesNotCarryTheSentWords() {
        val vm = viewModel(sent.text, sent.attachments, sendingChat)
        assertTrue(vm.heldBySend())
        assertEquals("", vm.composerBase())
        val quoted = quotedDraft(quoteExcerpt("Which slot do you want?"), vm.composerBase())
        assertFalse("the sent words are not quoted again", quoted.contains(sent.text))
        assertEquals("Book it", (vm.composerBase() + " " + "Book it").trim())
    }

    @Test fun wordsTypedSinceTheTapAreKept() {
        val vm = viewModel("and remind me", emptyList(), sendingChat)
        assertFalse(vm.heldBySend())
        assertEquals("and remind me", vm.composerBase())
    }

    @Test fun onlyADirectSendInFlightHoldsTheComposer() {
        assertFalse("no send", viewModel(sent.text, sent.attachments, sendingChat, sending = null).heldBySend())
        assertFalse("a queued send never held the composer", viewModel(sent.text, sent.attachments, sendingChat, sending = send.copy(queued = true)).heldBySend())
        assertFalse("the send already answered", viewModel(sent.text, sent.attachments, sendingChat.copy(uncertain = "", status = "")).heldBySend())
        assertFalse("other files", viewModel(sent.text, emptyList(), sendingChat).heldBySend())
    }
}
