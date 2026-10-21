package dev.hermes.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePairingTest {
    @Test fun pairingCodeIsCanonicalizedAndGroupedForDisplay() {
        assertEquals("ABC123", normalizePairingCode("abc-123"))
        assertEquals("ABC-123", formatPairingCodeInput("abc123"))
        assertTrue(runCatching { normalizePairingCode("ABC-12") }.isFailure)
    }

    @Test fun copiedPairingBlockFillsDashboardAndCode() {
        val input = parsePairingBlock(
            "Dashboard address: https://hermes.example\nPairing code: abc-123\nExpires: 10 minutes",
        )

        assertEquals("https://hermes.example", input?.address)
        assertEquals("ABC123", input?.code)
    }

    @Test fun unrelatedTextIsNotTreatedAsAPairingBlock() {
        assertNull(parsePairingBlock("https://hermes.example without a pairing code"))
    }
}
