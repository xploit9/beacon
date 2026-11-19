package dev.hermes.android.agentphone

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconApplication
import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneEndpoint
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notification a new agent-phone question posts (approvals-v1 §6): the question's title and "Tap to answer." only, never the amount,
 * the shop, the purpose or a code; on the lock screen only "Approval needed". With `-e shots 1` the open shade is saved. Synthetic only.
 */
class AgentApprovalNoticeShotsTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as BeaconApplication

    @Test fun aNewQuestionPostsItsTitleOnly() {
        val id = "f".repeat(31) + "1"
        val fake = object : AgentPhoneEndpoint {
            override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer =
                if (method == "GET") AgentPhoneAnswer(200, JSONObject().put("approvals", JSONArray().put(JSONObject().put("id", id).put("kind", "agent_phone_action")
                    .put("risk", "payment").put("action", "tap").put("app", "Kanto Foods").put("label", "Place order").put("amount", "£12.40").put("pay", true)
                    .put("purpose", "Dinner you asked for").put("expires_in_seconds", 100))))
                else AgentPhoneAnswer(200, JSONObject().put("approved", true))
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        try {
            runBlocking { AgentApprovals.refresh(app, ApprovalLink("notice-test-${System.nanoTime()}", fake)) }
            val posted = manager.activeNotifications.firstOrNull { it.tag == "agent-phone:$id" }
            assertNotNull("the question was announced", posted)
            val extras = posted!!.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
            assertTrue(title, title.endsWith("wants to pay"))
            assertEquals("Tap to answer.", text)
            for (secret in listOf("12.40", "Kanto", "Place order", "Dinner")) assertFalse(secret, "$title $text".contains(secret))
            val public = posted.notification.publicVersion
            assertEquals("Approval needed", public.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            assertEquals(Notification.VISIBILITY_PRIVATE, posted.notification.visibility)
            if (InstrumentationRegistry.getArguments().getString("shots") == "1") {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                Thread.sleep(1_500)
                val shot = requireNotNull(automation.takeScreenshot())
                val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "agent-approval-shots").apply { mkdirs() }
                File(dir, "notice-50-approval-in-shade.png").outputStream().use { assertTrue(shot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                shot.recycle()
                automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                Thread.sleep(500)
            }
        } finally {
            manager.cancel("agent-phone:$id", 7)
        }
    }
}
