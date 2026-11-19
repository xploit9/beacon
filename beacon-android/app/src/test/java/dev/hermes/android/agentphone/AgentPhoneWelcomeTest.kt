package dev.hermes.android.agentphone

import androidx.compose.ui.geometry.Offset
import dev.hermes.android.ui.DustPhoneField
import dev.hermes.android.data.AGENT_PHONE_BUSY
import dev.hermes.android.data.AGENT_PHONE_DIDNT_START
import dev.hermes.android.data.AgentPhoneAction
import dev.hermes.android.data.AgentPhoneLine
import dev.hermes.android.data.agentPhoneLine
import dev.hermes.android.ui.agentPhoneLineStartsPhone
import dev.hermes.android.ui.agentPhoneRemoveBody
import dev.hermes.android.ui.agentPhoneVaultName
import dev.hermes.android.ui.dustPhoneLayout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The welcome (Root's follow-up 1): the dust that gathers into a phone, and what Saved logins calls the vault. */
class AgentPhoneWelcomeTest {
    private val width = 1080f
    private val height = 840f
    private val layout = dustPhoneLayout(width, height, 2.75f)
    private val field = DustPhoneField()
    private val specks = 0 until field.count
    private fun kind(kind: Int) = specks.filter { field.kind(it) == kind }

    /** Signed distance from [p] to the phone's rounded outline: 0 on it, negative inside. */
    private fun edgeDistance(p: Offset): Float {
        val box = layout.box
        val r = layout.corner
        val qx = abs(p.x - box.center.x) - (box.width / 2f - r)
        val qy = abs(p.y - box.center.y) - (box.height / 2f - r)
        return hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - r
    }

    @Test fun everyOutlineSpeckLandsOnThePhonesEdgeAllTheWayRound() {
        val outline = kind(DustPhoneField.OUTLINE)
        assertTrue(outline.size >= 100)
        for (i in outline) assertEquals("speck $i", 0f, edgeDistance(field.target(i, layout)), .5f)
        val quarters = outline.map { field.target(it, layout) }
            .groupBy { (if (it.x > layout.center.x) 1 else 0) + (if (it.y > layout.center.y) 2 else 0) }
        assertEquals(4, quarters.size)
        assertTrue(quarters.values.all { it.size >= outline.size / 6 })
        // the speaker slit sits inside the phone, near its top
        for (i in kind(DustPhoneField.SPEAKER)) {
            val p = field.target(i, layout)
            assertTrue(edgeDistance(p) < 0f && p.y < layout.center.y)
        }
    }

    @Test fun reducedMotionDrawsThePhoneGatheredAndStill() {
        for (i in specks) {
            val still = field.position(i, 0f, motion = false, layout)
            assertEquals(still, field.position(i, 9f, motion = false, layout))
            if (field.kind(i) != DustPhoneField.LOOSE) assertEquals(field.target(i, layout), still)
        }
    }

    @Test fun theDustGathersFromAllAroundAndSettlesOnThePhone() {
        val edge = specks.filter { field.kind(it) != DustPhoneField.LOOSE }
        val start = edge.map { (field.position(it, 0f, true, layout) - field.target(it, layout)).getDistance() }.average()
        assertTrue("starts away from the phone: $start", start > layout.box.width * .5f)
        for (i in edge) assertTrue("speck $i rests within a dp",
            (field.position(i, 4f, true, layout) - field.target(i, layout)).getDistance() <= layout.density)
        for (i in kind(DustPhoneField.LOOSE)) for (t in listOf(0f, 3f, 30f, 300f)) {
            val p = field.position(i, t, true, layout)
            assertTrue("loose speck $i stays in the canvas at $t s", p.x in 0f..width && p.y in 0f..height)
        }
    }

    @Test fun onlyACardThatStartsThePhoneQuietsTheHero() {
        // A failed start: the card's Try again starts the phone, so the hero drops its own Turn on.
        assertTrue(agentPhoneLineStartsPhone(AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)))
        val unknown = agentPhoneLine("The phone's process could not start.", AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain))!!
        assertTrue(agentPhoneLineStartsPhone(unknown))
        // Refusals keep the hero's Turn on: their Try again reads Hermes again, or they only dismiss.
        assertFalse(agentPhoneLineStartsPhone(AgentPhoneLine(AGENT_PHONE_BUSY, action = AgentPhoneAction.TryAgain)))
        assertFalse(agentPhoneLineStartsPhone(agentPhoneLine("Turn the phone on first.", null)!!))
        assertFalse(agentPhoneLineStartsPhone(AgentPhoneLine("The helper didn't install. Try again.", action = AgentPhoneAction.TryAgain)))
    }

    @Test fun theRemoveDialogFreesTheRealSizeOrSaysNothingAboutIt() {
        val deleted = "Its apps and sign-ins are deleted. Your online accounts stay as they are."
        assertEquals("$deleted Frees 9.8 GB.", agentPhoneRemoveBody(deleted, "Frees {0}.", "9.8 GB"))
        // No storage_bytes in the status: the size is never guessed.
        assertEquals(deleted, agentPhoneRemoveBody(deleted, "Frees {0}.", null))
    }

    @Test fun savedLoginsSaysYourVaultWithOneProfileAndTheVaultsNameWithSeveral() {
        val one = listOf(JSONObject("""{"name": "default"}"""))
        val two = listOf(JSONObject("""{"name": "default"}"""), JSONObject("""{"name": "work-2", "display_name": "Work"}"""))
        assertNull(agentPhoneVaultName("default", one))
        assertNull(agentPhoneVaultName("default", emptyList()))
        assertEquals("Default", agentPhoneVaultName("default", two))
        assertEquals("Work", agentPhoneVaultName("work-2", two))
    }
}
