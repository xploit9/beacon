package dev.hermes.android.ui

import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.sphere.SphereMotionMode
import dev.hermes.android.ui.sphere.resolveSphereState
import dev.hermes.android.ui.sphere.sphereMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SphereStateTest {
    @Test fun chatStateMapsToSphereStateWithErrorAndThinkingPriority() {
        assertEquals(SphereState.Error, resolveSphereState(hasError = true, streaming = true, waitingForFirstToken = true))
        assertEquals(SphereState.Thinking, resolveSphereState(hasError = false, streaming = true, waitingForFirstToken = true))
        assertEquals(SphereState.Streaming, resolveSphereState(hasError = false, streaming = true, waitingForFirstToken = false))
        assertEquals(SphereState.Idle, resolveSphereState(hasError = false, streaming = false, waitingForFirstToken = false))
    }

    @Test fun reduceMotionAndDisabledSystemAnimationsPauseTheSphere() {
        val reducedMotionEnabled = nativeMotionAllowed(reducedMotion = true, systemAnimationsEnabled = true, lifecycleStarted = true)
        val systemAnimationsEnabled = nativeMotionAllowed(reducedMotion = false, systemAnimationsEnabled = false, lifecycleStarted = true)
        assertFalse(reducedMotionEnabled)
        assertFalse(systemAnimationsEnabled)
        assertFalse(nativeMotionAllowed(reducedMotion = false, systemAnimationsEnabled = true, lifecycleStarted = false))
        assertTrue(nativeMotionAllowed(reducedMotion = false, systemAnimationsEnabled = true, lifecycleStarted = true))
        assertEquals(SphereMotionMode.Still, sphereMotionMode(reducedMotionEnabled, fixedTime = null, fixedColorPhase = null))
        assertEquals(SphereMotionMode.Still, sphereMotionMode(systemAnimationsEnabled, fixedTime = null, fixedColorPhase = null))
    }

    @Test fun relayLicenseIsAvailableInTheLicenseDocumentList() {
        assertTrue(licenseDocuments.contains("hermes-relay-LICENSE.txt"))
    }
}
