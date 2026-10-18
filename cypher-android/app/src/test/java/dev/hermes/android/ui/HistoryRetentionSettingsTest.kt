package dev.hermes.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryRetentionSettingsTest {
    @Test fun choicesShowTheServerValues() {
        assertEquals("Forever", historyRetentionLabel(0))
        assertEquals("1 year", historyRetentionLabel(365))
        assertEquals("90 days", historyRetentionLabel(90))
        assertEquals("30 days", historyRetentionLabel(30))
    }

    @Test fun onlyShorteningThePeriodNeedsConfirmation() {
        assertFalse(historyRetentionNeedsConfirmation(0, 0))
        assertTrue(historyRetentionNeedsConfirmation(0, 365))
        assertTrue(historyRetentionNeedsConfirmation(365, 90))
        assertTrue(historyRetentionNeedsConfirmation(90, 30))
        assertFalse(historyRetentionNeedsConfirmation(30, 90))
        assertFalse(historyRetentionNeedsConfirmation(30, 365))
    }
}
