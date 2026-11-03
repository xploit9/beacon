package dev.hermes.android.assist

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Strix final1126 (vuln-0006): the assist gesture's other way in (AssistEntryActivity, for phones that use an assist activity
 * instead of the voice-interaction overlay) keeps the owner's rule too: on a locked phone it asks for the unlock first, and nothing
 * is heard or said until then. Both activities need a running phone, so their order of steps is pinned in the source, like the
 * other assist tests.
 */
class AssistEntryUnlockTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) {
        "Run this check from the Beacon app, Android project or repository root."
    }
    private fun source(path: String) = File(app, "src/main/java/dev/hermes/android/$path").readText().replace("\r\n", "\n")

    @Test fun aLockedGestureAsksForTheUnlockBeforeTheAppOpens() {
        val entry = source("assist/AssistEntryActivity.kt")
        val lock = entry.indexOf("deviceLockedNow(this)")
        assertTrue("The entry reads the lock", lock >= 0)
        val unlock = entry.indexOf("AssistUnlockActivity.open(this, AssistTarget())")
        val opened = entry.indexOf("Intent(this, BeaconActivity::class.java)")
        assertTrue("Locked, the entry asks for the unlock, and only then is the token issued", unlock > lock)
        assertTrue("The app opens directly only when the phone is unlocked", opened > unlock)
    }

    @Test fun theGesturesCallStartReadsTheLockAgainBeforeItStarts() {
        val effect = source("assist/PhoneAssistant.kt")
        val branch = effect.substring(effect.indexOf("AssistAction.StartCall ->")).substringBefore("\n            AssistAction.ShowCall")
        // A phone that locked while the app was getting ready still asks for the unlock; the call starts only unlocked.
        val lock = branch.indexOf("deviceLockedNow(context)")
        assertTrue(branch, lock >= 0 && branch.indexOf("AssistUnlockActivity.open(context, target)") > lock)
        assertTrue(branch, branch.indexOf("vm.callBeacon()") > branch.indexOf("AssistUnlockActivity.open(context, target)"))
    }

    // Strix final1126 open question: a screenshot is kept only while the phone is unlocked now, the same two reads as every other
    // private surface, so a lock that came up between two broadcasts never keeps one.
    @Test fun theScreenshotIsKeptOnlyWhileThePhoneIsUnlockedNow() {
        val controller = source("assist/AssistOverlayController.kt")
        val line = controller.lines().single { "fun screenshot(" in it }
        assertTrue(line, "overlayLocked(locked, keyguardLocked())" in line)
    }
}
