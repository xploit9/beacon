package dev.hermes.android.updates

import org.junit.Assert.*
import org.junit.Test

class PluginUpdateStateTest {
    @Test fun currentPluginShowsItsRunningVersionAndCanUpdate() {
        val state = PluginUpdateRowState(PluginVersions("2.4.0", "2.4.0"))
        assertEquals("2.4.0 running", state.message)
        assertEquals("Update plugin", state.buttonText)
        assertTrue(state.canUpdate)
    }

    @Test fun checkingAndUpdatingStatesDisableTheAction() {
        val versions = PluginVersions("2.4.0", "2.4.0")
        assertEquals("Checking plugin version…", PluginUpdateRowState(versions, checking = true).message)
        val updating = PluginUpdateRowState(versions, updating = true)
        assertEquals("Updating the Beacon plugin…", updating.message)
        assertEquals("Updating…", updating.buttonText)
        assertFalse(updating.canUpdate)
    }

    @Test fun capabilityConsentMustBeReviewedBeforeResending() {
        val state = PluginUpdateRowState(PluginVersions("2.4.0", "2.4.0"), consentRequired = true)
        assertEquals("Review capability changes to continue.", state.message)
        assertEquals("Review changes", state.buttonText)
        assertFalse(state.canUpdate)
    }

    @Test fun installedVersionMismatchRequiresHermesRestart() {
        val versions = PluginVersions("2.4.0", "2.5.0")
        assertTrue(versions.restartRequired)
        assertEquals("2.5.0 installed · 2.4.0 running", versions.summary())
        assertEquals("Restart Hermes on your PC to finish.", PluginUpdateRowState(versions).message)
        assertFalse(PluginUpdateRowState(versions).canUpdate)
    }

    @Test fun errorsUseSafeUserLevelCopy() {
        val state = PluginUpdateRowState(PluginVersions("2.4.0", "2.4.0"), error = true)
        assertEquals("Couldn't check or update the Beacon plugin. Try again.", state.message)
        assertTrue(state.canUpdate)
    }

    @Test fun anOlderPluginWithoutVersionRouteCanStillBeUpdated() {
        val state = PluginUpdateRowState(error = true)
        assertEquals("Couldn't check or update the Beacon plugin. Try again.", state.message)
        assertEquals("Update plugin", state.buttonText)
        assertTrue(state.canUpdate)
    }
}
