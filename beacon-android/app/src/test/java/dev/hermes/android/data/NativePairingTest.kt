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

    @Test fun theBlockHermesPrintsFillsBothFields() {
        // Exactly what `hermes beacon pair` prints.
        val input = parsePairingBlock("Dashboard: https://hermes.example.ts.net\nPairing code: K7Q-4ZP\nexpires in 10 min")
        assertEquals("https://hermes.example.ts.net", input?.address)
        assertEquals("K7Q4ZP", input?.code)
    }

    @Test fun setupConfirmsTheDashboardBeforeSignInWithoutTouchingTheNetwork() {
        val pending = NativePairing.prepare("https://hermes.example.ts.net", "k7q-4zp")
        assertEquals("https://hermes.example.ts.net", pending.dashboardUrl)
        assertEquals("hermes.example.ts.net", pending.displayName)
        assertEquals("K7Q4ZP", pending.code)
        assertTrue("the code never reaches logs", "K7Q4ZP" !in pending.toString())
        assertTrue(runCatching { NativePairing.prepare("http://hermes.example", "K7Q4ZP") }.isFailure)
    }

    @Test fun unrelatedTextIsNotTreatedAsAPairingBlock() {
        assertNull(parsePairingBlock("https://hermes.example without a pairing code"))
    }
}
