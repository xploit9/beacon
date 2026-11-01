package dev.hermes.android.uiux.serverabout

import dev.hermes.android.data.NativeServerState
import dev.hermes.android.ui.SettingsStatus
import dev.hermes.android.ui.serverComponentStatus
import dev.hermes.android.ui.serverHasCache
import dev.hermes.android.ui.serverHealthStatus
import dev.hermes.android.ui.serverLogFiles
import dev.hermes.android.ui.serverOutcomeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerAboutStatusTest {
    @Test fun healthWordsMapToTheStatesTheHeroShows() {
        assertEquals(SettingsStatus.Ok, serverHealthStatus("ok", loading = false))
        assertEquals(SettingsStatus.Attention, serverHealthStatus("degraded", loading = false))
        assertEquals(SettingsStatus.Working, serverHealthStatus("", loading = true))
        assertEquals(SettingsStatus.Error, serverHealthStatus("", loading = false))
        assertEquals(SettingsStatus.Error, serverHealthStatus("down", loading = true))
    }

    @Test fun componentStatusesMapAsTheSpecTable() {
        listOf("ok", "healthy", " OK ").forEach { assertEquals(it, SettingsStatus.Ok, serverComponentStatus(it)) }
        listOf("degraded", "warn").forEach { assertEquals(it, SettingsStatus.Attention, serverComponentStatus(it)) }
        listOf("error", "down", "failed").forEach { assertEquals(it, SettingsStatus.Error, serverComponentStatus(it)) }
        // Unknown words stay Off and keep their raw word for the pill, so nothing is guessed.
        listOf("", "unknown", "warning").forEach { assertEquals(it, SettingsStatus.Off, serverComponentStatus(it)) }
    }

    @Test fun updateOutcomesNeedTheUserUnlessTheyAreConfirmed() {
        assertEquals(SettingsStatus.Ok, serverOutcomeStatus("success"))
        assertEquals(SettingsStatus.Attention, serverOutcomeStatus("partial"))
        assertEquals(SettingsStatus.Error, serverOutcomeStatus("failed"))
        assertEquals(SettingsStatus.Error, serverOutcomeStatus("refused"))
        assertEquals(SettingsStatus.Working, serverOutcomeStatus("running"))
        assertEquals(SettingsStatus.Attention, serverOutcomeStatus("unconfirmed"))
    }

    @Test fun logFileIdsAreUnchangedWhateverTheChipsSay() {
        assertEquals(listOf("errors", "agent", "gateway", "gui", "desktop", "mcp"), serverLogFiles)
    }

    @Test fun aReloadKeepsWhatIsAlreadyOnScreen() {
        assertFalse(serverHasCache("Health", NativeServerState(loading = true)))
        assertTrue(serverHasCache("Health", NativeServerState(overall = "ok")))
        assertTrue(serverHasCache("Logs", NativeServerState(logs = listOf("line"))))
        assertFalse(serverHasCache("Logs", NativeServerState(loading = true)))
        assertTrue(serverHasCache("Session checkpoints", NativeServerState(checkpointBytes = 10)))
        assertTrue(serverHasCache("Server updates", NativeServerState(requestedAt = "2026-10-31T10:00:00Z")))
        assertFalse(serverHasCache("Server updates", NativeServerState()))
    }
}
