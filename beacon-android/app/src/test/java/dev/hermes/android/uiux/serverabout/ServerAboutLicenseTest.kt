package dev.hermes.android.uiux.serverabout

import dev.hermes.android.ui.licenseDocuments
import dev.hermes.android.ui.openSourceWorks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAboutLicenseTest {
    @Test fun everyBundledWorkIsListedOnce() {
        val names = openSourceWorks.map { it.name }
        assertEquals(names.distinct(), names)
        assertEquals(10, names.size)
    }

    @Test fun everyNoticeNamesItsLicenseAndKeepsTheLegalLines() {
        openSourceWorks.forEach { work -> assertTrue(work.name, work.notice.contains(work.license)) }
        assertTrue(openSourceWorks.single { it.name == "Hermes Relay" }.notice.let { it.contains("adapted from it") && it.contains("Hermes Relay MIT notice") })
        // No class names on the page.
        assertFalse(openSourceWorks.any { work -> listOf("NativeAuth", "NativeOperations", "OpenAiWire", "RelayCallback", "SettingsSearch").any { it in work.notice } })
        assertTrue(openSourceWorks.single { it.name.startsWith("JLaTeXMath") }.notice.endsWith("cc2a83dcf695028b5588f91a90734d94eb5bd02b"))
    }

    @Test fun licenseDocumentsIncludeThePinnedPhoneDriverLicense() {
        assertEquals(13, licenseDocuments.size)
        assertTrue(licenseDocuments.contains("hermes-relay-LICENSE.txt"))
        assertTrue(licenseDocuments.contains("PhoneDriver-LICENSE.txt"))
        val driver = openSourceWorks.single { it.name == "PhoneDriver" }
        assertEquals("Apache-2.0", driver.license)
        assertTrue(driver.notice.endsWith("OminousIndustries/PhoneDriver/tree/a89b1044bb551693e458f5e2a8c19395291d75f8"))
    }
}
