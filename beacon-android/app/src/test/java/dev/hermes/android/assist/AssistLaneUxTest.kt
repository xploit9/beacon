package dev.hermes.android.assist

import androidx.compose.ui.graphics.Color
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.callStatusLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lane UX in the overlay: when a tap beside the glass closes it, and the colour of the tool activity line in each theme. */
class AssistLaneUxTest {
    private fun shown() = reduce(AssistOverlayState(), OverlayEvent.Show(fresh = true, callActive = false)).let { reduce(it, OverlayEvent.Summoned) }

    @Test fun aTapBesideTheGlassClosesTheOverlayExceptWhileAConversationIsLive() {
        assertTrue(outsideTapCloses(OverlaySurface.Pill))
        assertTrue(outsideTapCloses(OverlaySurface.Answer))
        assertFalse(outsideTapCloses(OverlaySurface.Live))
    }

    @Test fun theConversationHoldsTheOverlayFromItsStartToItsEnd() {
        val open = shown()
        assertTrue("closes before the conversation", outsideTapCloses(open.surface))
        // Still starting counts: the surface goes live the moment the owner asks for it, before the call exists.
        val live = reduce(open, OverlayEvent.LiveStarted)
        assertFalse("ignored while the conversation runs", outsideTapCloses(live.surface))
        // The orb ends it, and the tap closes the overlay again.
        val ended = reduce(live, OverlayEvent.LiveEnded)
        assertTrue("closes after the conversation", outsideTapCloses(ended.surface))
    }

    private fun call(state: String, tool: Boolean) = CallState(
        active = true, state = state, callId = "lock",
        toolReceipts = if (tool) listOf(requireNotNull(CallToolReceipt.from(JSONObject()
            .put("request_id", "request-1").put("tool_name", "mcp__gmail__search_messages").put("status", "running").put("approved_by", "chat_yolo")))) else emptyList(),
    )

    @Test fun onALockedPhoneAToolInUseIsOnlyWorkingAndNeverWhichOne() {
        val busy = call("thinking", tool = true)
        assertEquals("Working…", liveStatusLabel(busy, locked = true))
        assertFalse(liveStatusLabel(busy, locked = true).contains("Gmail", ignoreCase = true))
        // Unlocked it is the call's state alone: the tool in use has its own line above the text box, so it is not named twice.
        assertEquals("Thinking", liveStatusLabel(busy, locked = false))
        assertEquals(callStatusLabel(busy, withTool = false), liveStatusLabel(busy, locked = false))
        assertFalse(liveStatusLabel(busy, locked = false).contains("Gmail", ignoreCase = true))
    }

    @Test fun theStateWithoutAToolReadsTheSameLockedOrNot() {
        for (state in listOf("listening", "speaking", "thinking", "connecting")) {
            val quiet = call(state, tool = false)
            assertEquals(state, callStatusLabel(quiet), liveStatusLabel(quiet, locked = true))
            assertEquals(callStatusLabel(quiet), liveStatusLabel(quiet, locked = false))
        }
    }

    @Test fun theToolActivityLineIsTheThemesOwnAccentOnALightCardAndGoldOnADarkOne() {
        val primary = Color(0xFF7A4B00)
        assertEquals(primary, liveActivityColor(light = true, primary = primary))
        assertEquals(Gold, liveActivityColor(light = false, primary = primary))
    }
}
