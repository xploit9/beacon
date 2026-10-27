package dev.hermes.android.ui

import dev.hermes.android.ui.sphere.SphereState
import dev.hermes.android.voice.CallState
import dev.hermes.android.voice.CallToolReceipt
import dev.hermes.android.voice.callStatusLabel
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class VoicePresenceTest {
    private val call = CallState(active = true, state = "listening")
    private fun red(rgb: Int) = rgb shr 16 and 255
    private fun green(rgb: Int) = rgb shr 8 and 255
    private fun blue(rgb: Int) = rgb and 255

    @Test fun everyCallStatusHasOneStateColourAndTheWordsAgree() {
        val running = CallToolReceipt("request-1", "mcp__gmail__search_messages", "running")
        val finished = CallToolReceipt("request-2", "mcp__gmail__search_messages", "completed")
        listOf(
            Triple(call, SphereState.Listening, "Listening"),
            Triple(call.copy(userSpeaking = true), SphereState.Listening, "Listening"),
            Triple(call.copy(toolReceipts = listOf(finished)), SphereState.Listening, "Listening"),
            Triple(call.copy(state = "thinking"), SphereState.Thinking, "Thinking"),
            Triple(call.copy(toolReceipts = listOf(running)), SphereState.Thinking, "Using Gmail…"),
            Triple(call.copy(remoteSpeaking = true), SphereState.Speaking, "Speaking"),
            Triple(call.copy(state = "speaking"), SphereState.Speaking, "Speaking"),
            Triple(call.copy(remoteSpeaking = true, muted = true), SphereState.Speaking, "Speaking"),
            Triple(call.copy(state = "connecting"), SphereState.Idle, "Connecting"),
            Triple(call.copy(state = "reconnecting"), SphereState.Idle, "Connecting"),
            Triple(call.copy(held = true), SphereState.Idle, "Call on hold"),
            Triple(call.copy(muted = true), SphereState.Idle, "Muted"),
            Triple(call.copy(userSpeaking = true, muted = true), SphereState.Idle, "Muted"),
            Triple(call.copy(state = "idle"), SphereState.Idle, "Idle"),
        ).forEach { (state, expected, label) ->
            assertEquals(state.toString(), expected, presenceState(state))
            assertEquals(state.toString(), label, callStatusLabel(state))
        }
        assertEquals(SphereState.Error, presenceState(call.copy(error = "Microphone unavailable")))
        assertEquals(SphereState.Idle, presenceState(call.copy(active = false, remoteSpeaking = true)))
        assertEquals(SphereState.Idle, presenceState(call.copy(active = false, error = "Microphone unavailable")))
    }

    @Test fun eachStateHasItsOwnColourAndTheCalmOneIsDimmest() {
        val listening = presencePoles(SphereState.Listening).a
        val thinking = presencePoles(SphereState.Thinking).a
        val speaking = presencePoles(SphereState.Speaking).a
        val alert = presencePoles(SphereState.Error).a
        val calm = presencePoles(SphereState.Idle).a
        assertTrue(blue(listening) > red(listening) && blue(listening) > green(listening))
        assertTrue(red(thinking) > green(thinking) && green(thinking) > blue(thinking) && green(thinking) in 120..200)
        assertTrue(green(speaking) > blue(speaking) && blue(speaking) > red(speaking))
        assertTrue(red(alert) > green(alert) * 2 && red(alert) > blue(alert) * 2)
        assertTrue(red(calm) >= green(calm) && green(calm) >= blue(calm))
        val brightness = { rgb: Int -> red(rgb) + green(rgb) + blue(rgb) }
        assertTrue(listOf(listening, thinking, speaking, alert).all { brightness(calm) < brightness(it) })
        assertSame(presencePoles(SphereState.Speaking), presencePoles(SphereState.Streaming))
    }

    @Test fun amplitudeBecomesBoundedMonotonicEnergy() {
        assertEquals(0f, presenceEnergy(Float.NaN), 0f)
        assertEquals(0f, presenceEnergy(-1f), 0f)
        assertEquals(1f, presenceEnergy(3f), 0f)
        assertEquals(.4f, presenceEnergy(.4f), 0f)
        assertEquals(1f, presenceStir(0f), 0f)
        assertEquals(1f, presenceSpread(0f), 0f)
        var previous = listOf(-1f, -1f, -1f)
        for (i in 0..100) {
            val energy = presenceEnergy(i / 100f)
            val now = listOf(presenceStir(energy), presenceSpread(energy), presenceGain(energy))
            now.zip(previous).forEach { (value, before) -> assertTrue(value.isFinite() && value >= before) }
            previous = now
        }
    }

    @Test fun particleCountScalesWithAreaAndTheBubbleGetsFarFewer() {
        val bubble = presenceParticleCount(48f, 48f)
        val phone = presenceParticleCount(360f, 520f)
        assertEquals(500, bubble)
        assertTrue(phone >= bubble * 10)
        assertEquals(16000, presenceParticleCount(900f, 900f))
        assertEquals(500, presenceParticleCount(Float.NaN, 10f))
        assertEquals(0, presenceParticleCount(300f, 300f) % 500)
    }

    @Test fun bucketEdgesPartitionEveryCount() {
        for (count in listOf(0, 1, 250, 1000, 5000)) {
            val edges = presenceBucketEdges(count)
            assertEquals(PRESENCE_BUCKETS + 1, edges.size)
            assertEquals(0, edges.first())
            assertEquals(count, edges.last())
            assertEquals(edges.toList(), edges.sorted())
        }
    }

    @Test fun seedingIsDeterministicBoundedAndSeedSensitive() {
        val cloud = seedParticles(7L, 600)
        assertEquals(1200, cloud.size)
        assertArrayEquals(cloud, seedParticles(7L, 600), 0f)
        assertFalse(cloud.contentEquals(seedParticles(8L, 600)))
        assertTrue(cloud.all { it.isFinite() && abs(it) <= 1.2f })
        assertEquals(0, seedParticles(7L, 0).size)
    }

    @Test fun theFlowIsDeterministicMovesTheCloudAndKeepsEverySpeckNearIt() {
        fun run(): FloatArray {
            val field = PresenceField(3L)
            field.fit(500)
            repeat(600) { field.step(it / 60f, 1f) }
            return field.xy.copyOf(1000)
        }
        val moved = run()
        assertArrayEquals(moved, run(), 0f)
        assertFalse(moved.contentEquals(seedParticles(3L, 500)))
        assertTrue(moved.all { it.isFinite() && abs(it) < 2f })
    }

    @Test fun fittingTheSameCountKeepsTheCloudAndAGrowingAreaKeepsTheSameCloud() {
        val field = PresenceField(3L)
        field.fit(250)
        field.step(.1f, 0f)
        val stirred = field.xy.copyOf()
        field.fit(250)
        assertArrayEquals(stirred, field.xy, 0f)
        field.fit(500)
        assertEquals(500, field.count)
        // Seeded once at the cap: a bigger area only uses more of the same cloud, so a resize never reseeds while drawing.
        assertArrayEquals(seedParticles(3L, 500), field.home.copyOf(1000), 0f)
        assertEquals(PRESENCE_MAX_SPECKS * 2, field.screen.size)
    }

    @Test fun colourMixesTheEasedPolesAndLiftsTowardsWhite() {
        val pole = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f)
        assertEquals(0xFFFF0000L.toInt(), presenceArgb(pole, 0f, 0f, 1f))
        assertEquals(0xFF0000FFL.toInt(), presenceArgb(pole, 1f, 0f, 1f))
        assertEquals(0xFFFFFFFFL.toInt(), presenceArgb(pole, .5f, 1f, 1f))
        assertEquals(0, presenceArgb(pole, .5f, 0f, 0f) ushr 24)
    }
}
