package dev.hermes.android.uiux.serverabout

import dev.hermes.android.ui.licenseDocuments
import dev.hermes.android.ui.openSourceWorks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAboutLicenseTest {
    @Test fun everyBundledWorkIsListedOnce() {
        val names = openSourceWorks.map { it.name }
        assertEquals(names.distinct(), names)
        assertEquals(9, names.size)
    }

    @Test fun everyNoticeNamesItsLicenseAndKeepsTheLegalLines() {
        openSourceWorks.forEach { work -> assertTrue(work.name, work.notice.contains(work.license)) }
        assertTrue(openSourceWorks.single { it.name == "Hermes Relay" }.notice.contains("Relay’s search pattern"))
        assertTrue(openSourceWorks.single { it.name.startsWith("JLaTeXMath") }.notice.endsWith("cc2a83dcf695028b5588f91a90734d94eb5bd02b"))
    }

    @Test fun licenseDocumentsAreUnchanged() {
        assertEquals(12, licenseDocuments.size)
        assertTrue(licenseDocuments.contains("hermes-relay-LICENSE.txt"))
    }
}
