package dev.hermes.android

import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * chat-live (owner, 26 Nov): leaving Beacon while a reply ran left the chat where it was, then the new words came a moment after the
 * return. The rules pinned here are the ones the return depends on: a followed turn's socket lives only while Keep connected holds the
 * process, a saved chat draws only when it has a row the open chat lacks, and the reply that ended away is shown once.
 */
class ChatLiveReturnTest {
    private fun module() = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
    private fun source(path: String) = File(module(), path).readText().replace("\r\n", "\n")

    private val ask = json("role" to "user", "content" to "Write a guide to the herbs.", "row_id" to 101)
    private val reply = json("role" to "assistant", "content" to "Water the basil on Sundays.", "row_id" to 102)
    private fun saved(vararg rows: JSONObject) = json("session_id" to "chat-live", "profile" to "default", "messages" to JSONArray(rows.toList()))
    private fun shown(vararg rows: JSONObject, id: String = "chat-live") =
        ChatState(sessionId = id, raw = json("messages" to JSONArray(rows.toList())), messages = rows.mapIndexed { index, row -> Message.from(row, index, id) })

    @Test fun aFollowedTurnKeepsItsSocketOnlyWhileKeepConnectedHoldsTheProcess() {
        assertTrue(keepsFollowedStream(keepConnected = true, followingTurn = true))
        assertFalse("keep connected off: Android freezes the cached process", keepsFollowedStream(keepConnected = false, followingTurn = true))
        assertFalse("nothing running", keepsFollowedStream(keepConnected = true, followingTurn = false))
    }

    @Test fun theSavedChatDrawsOnlyWhenItHasARowTheOpenChatLacks() {
        assertTrue("the reply that ended while away", cachedChatAddsRows(saved(ask, reply), shown(ask)))
        assertTrue("a full /api/session response", cachedChatAddsRows(json("session" to saved(ask, reply)), shown(ask)))
        assertFalse("an equal saved copy would step the chat back", cachedChatAddsRows(saved(ask), shown(ask)))
        assertTrue("another chat's copy", cachedChatAddsRows(saved(ask), shown(ask, id = "other-chat")))
        assertFalse("a row with no id cannot be proven new", cachedChatAddsRows(saved(ask, json("role" to "assistant", "content" to "No id")), shown(ask)))
    }

    @Test fun theSavedReplyShowsOnceAndEndsTheRunningTurn() {
        val running = ChatState(sessionId = "chat-live", streamId = "runtime-live", status = "Working", liveText = "Water the basil",
            raw = json("messages" to JSONArray(listOf(ask))), messages = listOf(Message.from(ask, 0, "chat-live")))
        val saved = saved(ask, reply)
        assertTrue(cachedChatAddsRows(saved, running))
        // The saved chat ends on the reply, so the history read may end the turn; the caller then does not paint its stale frame again.
        assertFalse(passiveReadKeepsTurn(running.streamId, namesStream = false, rows = saved.rows("messages")))
        val merged = mergeChatProjection(running.raw, saved)
        assertEquals(2, merged.rows("messages").size)
        assertEquals("the reply is shown once", 1, merged.rows("messages").count { it.string("role") == "assistant" && it.string("content").contains("Sundays") })
    }

    @Test fun aReturnPaintsTheSavedChatBeforeItsNetworkWork() {
        val vm = source("src/main/java/dev/hermes/android/BeaconViewModel.kt")
        val back = vm.substring(vm.indexOf("if (active && _ui.value.authenticated)"), vm.indexOf("fun downloadAppUpdate("))
        val painted = back.indexOf("paintSavedChat(generation)")
        assertTrue("the return paints the saved chat", painted >= 0)
        assertTrue("before the connection or the open chat's reads", painted < back.indexOf("if (client == null && !_ui.value.loading) connect("))
        assertTrue("and before recovery", painted < back.indexOf("recoverForeground()"))
        assertTrue("a kept socket is replaced before the saved chat is drawn", back.indexOf("keptTurnStream = false") in 0 until painted)
        val away = vm.substring(vm.indexOf("if (!active) {"), vm.indexOf("fun downloadAppUpdate("))
        assertTrue("a followed turn stays only with Keep connected's process", away.contains("if (keepsFollowedStream(held, following)) keptTurnStream = true else"))
    }

    @Test fun keepConnectedSavesATurnThatEndedWhileTheAppWasAway() {
        val service = source("src/main/java/dev/hermes/android/push/KeepConnectedService.kt")
        assertTrue("a completion or failure saves the chat", service.contains("in setOf(") && service.contains("cacheSettledChat(app.cache"))
        val body = service.substring(service.indexOf("internal suspend fun cacheSettledChat"), service.indexOf("/** How recently"))
        assertTrue("the chat's own live frame stays its writer", body.contains("KEEP_CONNECTED_CHAT_FRAME_FRESH_MILLIS"))
        assertTrue("only a released turn clears its record", body.contains("active_stream_id") && body.contains("isNullOrBlank()) return"))
    }
}
