package dev.hermes.android.phone

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Owner, 4 Nov 2026: a call asked for from the lock screen opened Android's app picker after the unlock and was never placed. */
class PhoneCallPlacementTest {
    private val app = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "src/main/java/dev/hermes/android").isDirectory }
    private val providers = File(app, "src/main/java/dev/hermes/android/phone/PhoneProviders.kt").readText().replace("\r\n", "\n")

    @Test fun aDirectCallGoesThroughTelecomBeforeAnyIntent() {
        val dial = providers.substringAfter("override fun dial(number: String, direct: Boolean): Boolean {").substringBefore("override fun draftMessage")
        assertTrue(dial.contains("telecom.placeCall("))
        assertTrue("Telecom comes first; the intent is only the fallback", dial.indexOf("placeCall(") < dial.indexOf("Intent.ACTION_CALL"))
    }
}
