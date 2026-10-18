package dev.hermes.android.ui

import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.sphere.SphereMotionMode
import dev.hermes.android.ui.sphere.SphereFrame
import dev.hermes.android.ui.sphere.forEachSphereCell
import dev.hermes.android.ui.sphere.resolveSphereState
import dev.hermes.android.ui.sphere.sphereMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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

    @Test fun oneFixedClockLeavesTheOtherClockAnimated() {
        assertEquals(SphereMotionMode.Procedural, sphereMotionMode(true, fixedTime = 5f, fixedColorPhase = null))
        assertEquals(SphereMotionMode.Procedural, sphereMotionMode(true, fixedTime = null, fixedColorPhase = 2f))
        assertEquals(SphereMotionMode.Still, sphereMotionMode(true, fixedTime = 5f, fixedColorPhase = 2f))
    }

    @Test fun voiceAmplitudeDoesNotModulateTheSphereWhenVoiceModeIsOff() {
        fun frame(voiceMode: Boolean, voiceAmplitude: Float) = SphereFrame(
            cols = 30, rows = 20, charAspect = 0.55f, state = SphereState.Speaking,
            time = 4f, colorPhase = 1.2f,
            breatheSpeed = 1f, breatheAmp = 0.2f, lightSpeedX = 0.4f, lightSpeedY = 0.3f,
            lightInfluence = 0.5f, coreTightness = 0.5f, turbulenceAmp = 0.2f,
            rippleScale = 0.5f, heartbeatSpeed = 0.4f, radialFlowSpeed = 0.3f,
            cr1 = 0.2f, cg1 = 0.4f, cb1 = 0.6f, cr2 = 0.8f, cg2 = 0.6f, cb2 = 0.4f,
            intensity = 0.3f, toolCallBurst = 0.2f, voiceAmplitude = voiceAmplitude,
            voiceMode = voiceMode, voiceRadiusScale = 1f,
        )
        fun cells(frame: SphereFrame) = buildList {
            forEachSphereCell(frame) { col, row, char, r, g, b, alpha -> add("$col:$row:$char:$r:$g:$b:$alpha") }
        }

        assertEquals(cells(frame(false, 0f)), cells(frame(false, 1f)))
        assertNotEquals(cells(frame(true, 0f)), cells(frame(true, 1f)))
    }

    @Test fun relayLicenseIsAvailableInTheLicenseDocumentList() {
        assertTrue(licenseDocuments.contains("hermes-relay-LICENSE.txt"))
    }
}
