package dev.hermes.android.ui

import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.ui.sphere.SphereMotionMode
import dev.hermes.android.ui.sphere.SphereFrame
import dev.hermes.android.ui.sphere.SphereGlyphBuffer
import dev.hermes.android.ui.sphere.forEachSphereCell
import dev.hermes.android.ui.sphere.advanceSphereTime
import dev.hermes.android.ui.sphere.resolveSphereState
import dev.hermes.android.ui.sphere.sphereMotionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
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

    @Test fun sphereClockDoesNotWrapAtOneThousandSeconds() {
        assertEquals(1000.01f, advanceSphereTime(999.99f, 0.02f), 0.01f)
    }

    @Test fun oneSphereFrameCanRenderWithAnExternalClock() {
        val frame = sphereFrame()
        val idle = render(frame, 0f, 0f)
        val advanced = render(frame, 5f, 1f)

        assertNotEquals(idle, advanced)
        assertEquals(render(frame.copy(time = 5f, colorPhase = 1f), 5f, 1f), advanced)
        assertEquals(0f, frame.time)
        assertEquals(0f, frame.colorPhase)
    }

    @Test fun rendererReusesItsSingleCharacterBuffer() {
        val buffer = SphereGlyphBuffer()
        val characters = buffer.characters

        buffer.set('A')
        assertSame(characters, buffer.characters)
        assertArrayEquals(charArrayOf('A'), characters)
        buffer.set('Z')
        assertSame(characters, buffer.characters)
        assertArrayEquals(charArrayOf('Z'), characters)
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

    private fun sphereFrame(time: Float = 0f, colorPhase: Float = 0f) = SphereFrame(
        cols = 12, rows = 8, charAspect = 0.55f, state = SphereState.Idle, time = time, colorPhase = colorPhase,
        breatheSpeed = 0.5f, breatheAmp = 0.04f, lightSpeedX = 0.25f, lightSpeedY = 0.18f,
        lightInfluence = 0.35f, coreTightness = 0.75f, turbulenceAmp = 0.06f, rippleScale = 1f,
        heartbeatSpeed = 1f, radialFlowSpeed = 0.2f,
        cr1 = 0.25f, cg1 = 0.85f, cb1 = 0.4f, cr2 = 0.61f, cg2 = 0.42f, cb2 = 0.94f,
        intensity = 0f, toolCallBurst = 0f, voiceAmplitude = 0f, voiceMode = false, voiceRadiusScale = 1f,
    )

    private fun render(frame: SphereFrame, time: Float, colorPhase: Float) = buildList {
        forEachSphereCell(frame, time, colorPhase) { col, row, char, r, g, b, alpha ->
            add("$col:$row:$char:$r:$g:$b:$alpha")
        }
    }
}
