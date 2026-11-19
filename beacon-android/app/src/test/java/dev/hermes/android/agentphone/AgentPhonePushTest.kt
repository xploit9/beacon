package dev.hermes.android.agentphone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The push wiring (approvals-v1 §6, §6b; L2 CONTRACT §4, §6): both agent-phone notices reach the app through the one receivePush that
 * FCM and Keep connected share, before its list of chat kinds drops them; the dialog is a secure, private activity of its own.
 */
class AgentPhonePushTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map(::File).firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText()

    @Test fun bothNoticeKindsAreTheAgentPhonesAndNoOtherKindIs() {
        assertTrue(isAgentPhoneNotice("agent_phone_approval"))
        assertTrue(isAgentPhoneNotice("agent_phone_done"))
        for (kind in listOf("phone_request", "approval", "clarify", "completion", "agent_phone", "", "AGENT_PHONE_APPROVAL")) assertFalse(kind, isAgentPhoneNotice(kind))
    }

    @Test fun receivePushHandsThemOverBeforeTheChatKindsListDropsThem() {
        val text = source("push/BeaconMessagingService.kt")
        val body = text.substring(text.indexOf("internal fun receivePush(context: Context, data: Map<String, String>, localActionToken: String?, deferPreview: Boolean)"))
        val routed = body.indexOf("dev.hermes.android.agentphone.isAgentPhoneNotice(nativeKind)")
        val handed = body.indexOf("dev.hermes.android.agentphone.AgentApprovals.onPush(context, source, data[\"event_id\"].orEmpty(), data[\"conversation_ref\"].orEmpty(), nativeKind); return null")
        val allowList = body.indexOf("if (data[\"kind\"] !in setOf(")
        val checked = body.indexOf("app.push.permitsIncoming(source)) return null")
        assertTrue(routed in 1 until allowList && handed in routed until allowList)
        // Only after the push's own refs and binding are checked.
        assertTrue(checked in 1 until routed)
    }

    @Test fun anOpenBeaconAsksTooNotOnlyAPush() {
        val text = source("BeaconActivity.kt")
        assertTrue(text.contains("dev.hermes.android.agentphone.AgentApprovalHost(if (ui.authenticated) model.client else null)"))
    }

    private fun activity(name: String): Element {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(app, "src/main/AndroidManifest.xml"))
        val activities = document.getElementsByTagName("activity")
        return (0 until activities.length).map { activities.item(it) as Element }.single { it.getAttributeNS(ANDROID, "name") == name }
    }

    @Test fun theDialogIsPrivateItsOwnTaskAndNeverOverTheLockScreen() {
        val dialog = activity(".agentphone.AgentApprovalActivity")
        assertEquals("false", dialog.getAttributeNS(ANDROID, "exported"))
        assertEquals("", dialog.getAttributeNS(ANDROID, "showWhenLocked"))
        assertEquals("", dialog.getAttributeNS(ANDROID, "turnScreenOn"))
        assertEquals("", dialog.getAttributeNS(ANDROID, "taskAffinity"))
        assertTrue(dialog.hasAttributeNS(ANDROID, "taskAffinity"))
        val list = activity(".agentphone.AgentPhoneLogActivity")
        assertEquals("false", list.getAttributeNS(ANDROID, "exported"))
        // The window is secure before any content is drawn, and recents keep no picture of it.
        val code = source("agentphone/AgentApprovalActivity.kt")
        val secure = code.indexOf("window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)")
        assertTrue(secure > 0 && secure < code.indexOf("super.onCreate(savedInstanceState)"))
        assertTrue(code.contains("setRecentsScreenshotEnabled(false)"))
    }

    @Test fun aDoneNoticeNamesItsRowAndItsTitleIsThePluginsOrTheApps() {
        assertEquals(42L, activitySeq("agent_phone_activity:42"))
        assertNull(activitySeq("agent_phone_activity:"))
        assertNull(activitySeq("agent_phone_activity:-3"))
        assertNull(activitySeq("other:42"))
        assertNull(activitySeq(null))
        assertEquals("Payment done", doneTitle("Payment done"))
        assertEquals("Sent", doneTitle("Sent"))
        assertEquals("Done on {name}'s phone", doneTitle("Done on the assistant's phone"))
        assertEquals("Done on {name}'s phone", doneTitle(""))
    }

    @Test fun aNotificationCarriesNoAmountShopOrCode() {
        // The record's words stay in the secure dialog: a notification shows the title only, and on the lock screen not even that.
        val code = source("agentphone/AgentApprovals.kt")
        val notify = code.substring(code.indexOf("private fun notifyRecord("), code.indexOf("private suspend fun postDone("))
        for (field in listOf(".amount", ".label", ".purpose", ".identifier", ".line", ".note", ".pin")) assertFalse(field, notify.contains("record$field"))
        assertTrue(notify.contains("setPublicVersion(public)"))
    }

    private companion object { const val ANDROID = "http://schemas.android.com/apk/res/android" }
}
