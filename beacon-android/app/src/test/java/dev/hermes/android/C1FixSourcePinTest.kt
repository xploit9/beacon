package dev.hermes.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** R1 review fixes 3 and 5. Both sit in code that needs a running phone, so they are pinned in the source like the assist tests. */
class C1FixSourcePinTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    @Test fun needsYouOpensThePhoneInControlAndSettingsOpensItToWatch() {
        val approval = source("agentphone/AgentApprovalActivity.kt")
        val open = approval.substringAfter("private fun openPhone()").substringBefore("\n    }\n")
        assertTrue("Needs you asks the owner to act on the phone, so it opens in control", "putExtra(AgentPhoneActivity.EXTRA_CONTROL, true)" in open)
        assertFalse("Settings opens the phone to watch", "EXTRA_CONTROL" in source("ui/AgentPhoneSettings.kt").substringAfter("object AgentPhoneLiveView").substringBefore("\n}\n"))
    }

    @Test fun theOngoingCallNotificationUsesTheCallingProfilesName() {
        val service = source("voice/BeaconCallService.kt")
        assertTrue("The caller's name is kept on the call's state", "val callerName: String" in service)
        val notification = service.substringAfter("val builder = NotificationCompat.Builder(this, CHANNEL)").substringBefore("return builder.build()")
        assertFalse("The shade must not fall back to the default assistant's name on its own", "callerPerson(this, dev.hermes.android.data.currentAssistantName)" in notification)
        assertTrue("The title, the CallStyle person and the public version all carry the caller", notification.split("state.callerName").size - 1 >= 4)
    }
}
