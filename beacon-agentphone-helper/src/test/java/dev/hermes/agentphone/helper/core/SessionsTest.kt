package dev.hermes.agentphone.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The session rules (contract §1, §8): what a start intent may and may not do. */
class SessionsTest {
    private fun token(seed: Int) = ByteArray(32) { (seed * 7 + it).toByte() }
    private fun text(token: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(token)

    private class Rig(dialer: Dialer, val helper: RecordingHelper = RecordingHelper()) {
        val idles = Semaphore(0)
        val pauses = CopyOnWriteArrayList<Long>()
        val sessions = Sessions(dialer, helper, { Fixtures.HELLO }, pause = { pauses.add(it) }, idle = { idles.release() })
        fun waitIdle() = assertTrue("idle", idles.tryAcquire(5, TimeUnit.SECONDS))
    }

    private fun waitFor(what: String, check: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!check()) {
            if (System.currentTimeMillis() > until) throw AssertionError(what)
            Thread.sleep(10)
        }
    }

    @Test
    fun badIntentsAreIgnored() {
        val dials = AtomicInteger()
        val rig = Rig({ dials.incrementAndGet(); throw IOException() })
        assertFalse(rig.sessions.start(80, text(token(1))))
        assertFalse(rig.sessions.start(65536, text(token(1))))
        assertFalse(rig.sessions.start(-1, text(token(1))))
        assertFalse(rig.sessions.start(41234, null))
        assertFalse(rig.sessions.start(41234, "short"))
        assertFalse(rig.sessions.start(41234, text(ByteArray(31))))
        assertEquals(0, dials.get())
        assertTrue(rig.sessions.isIdle)
    }

    @Test
    fun threeDialsHalfASecondApartThenTheTokenIsGone() {
        val dials = AtomicInteger()
        val rig = Rig({ dials.incrementAndGet(); throw IOException() })
        assertTrue(rig.sessions.start(41234, text(token(2))))
        rig.waitIdle()
        assertEquals(3, dials.get())
        assertEquals(listOf(500L, 500L), rig.pauses.toList())
        // The token is spent: the same token again does nothing; a new one dials again.
        assertFalse(rig.sessions.start(41234, text(token(2))))
        assertTrue(rig.sessions.start(41234, text(token(3))))
        rig.waitIdle()
        assertEquals(6, dials.get())
    }

    @Test
    fun aLiveLinkIgnoresEveryStartIntent() {
        val t = token(4)
        TestHost(t).use { host ->
            val rig = Rig(host.dialer)
            assertTrue(rig.sessions.start(host.port, text(t)))
            val c = host.accept()
            assertTrue(c.handshake())
            assertEquals("hello", c.read()["t"])
            waitFor("live") { rig.sessions.isLive }
            val first = rig.sessions.hostLink
            c.send(mapOf("t" to "hello_ok", "proto" to 1))
            // Another valid start (a new token, a port nothing listens on) changes nothing and dials nothing.
            assertFalse(rig.sessions.start(host.port, text(token(5))))
            assertFalse(rig.sessions.start(host.port, text(t)))
            c.send(mapOf("t" to "ping", "id" to 1))
            assertEquals("pong", c.read()["t"])
            assertTrue(first === rig.sessions.hostLink)
            c.send(mapOf("t" to "bye", "reason" to "done"))
            rig.waitIdle()
            assertNull(rig.sessions.hostLink)
            assertEquals(1, rig.helper.ended)
        }
    }

    @Test
    fun aNewerStartReplacesAnUnfinishedAttemptAndTheOldOneClearsNothing() {
        val old = token(6)
        val new = token(7)
        TestHost(old).use { stalled ->
            TestHost(new).use { host ->
                val rig = Rig(stalled.dialer)
                assertTrue(rig.sessions.start(stalled.port, text(old)))
                val s = stalled.accept()
                s.readBody(Wire.CH_LEN) // the old attempt is mid-handshake: never answered
                assertTrue(rig.sessions.start(host.port, text(new)))
                assertTrue(s.closedByHelper()) // the old attempt was closed
                val c = host.accept()
                assertTrue(c.handshake())
                assertEquals("hello", c.read()["t"])
                waitFor("live") { rig.sessions.isLive }
                Thread.sleep(200) // let the old attempt's thread finish its cleanup
                assertTrue(rig.sessions.isLive)
                assertEquals(0, rig.helper.ended)
                c.send(mapOf("t" to "hello_ok", "proto" to 1))
                c.send(mapOf("t" to "ping", "id" to 1))
                assertEquals("pong", c.read()["t"])
                c.close()
                rig.waitIdle()
                assertEquals(1, rig.helper.ended)
            }
        }
    }

    @Test
    fun aWrongHostProofEndsTheAttemptWithoutRedialling() {
        val t = token(8)
        TestHost(t).use { host ->
            val dials = AtomicInteger()
            val rig = Rig({ port -> dials.incrementAndGet(); host.dialer.dial(port) })
            assertTrue(rig.sessions.start(host.port, text(t)))
            val c = host.accept()
            assertFalse(c.handshake(proofToken = token(9)))
            rig.waitIdle()
            assertEquals(1, dials.get())
            assertNull(rig.sessions.hostLink)
            assertEquals(0, rig.helper.ended)
            assertFalse(rig.sessions.start(host.port, text(t)))
        }
    }

    @Test
    fun eventsGoOnlyToTheAuthenticatedLink() {
        val t = token(10)
        TestHost(t).use { host ->
            val rig = Rig(host.dialer)
            rig.sessions.event(mapOf("t" to "code_filled", "arm_id" to "a".repeat(16), "fields" to 1)) // nobody: dropped
            assertFalse(rig.sessions.eventNow(mapOf("t" to "code_filled", "arm_id" to "a".repeat(16), "fields" to 1)))
            assertTrue(rig.sessions.start(host.port, text(t)))
            val c = host.accept()
            assertTrue(c.handshake())
            assertEquals("hello", c.read()["t"])
            c.send(mapOf("t" to "hello_ok", "proto" to 1))
            c.send(mapOf("t" to "ping", "id" to 1))
            assertEquals("pong", c.read()["t"])
            rig.sessions.event(mapOf("t" to "code_filled", "arm_id" to "b".repeat(16), "fields" to 1))
            val m = c.read()
            assertEquals("b".repeat(16), m["arm_id"])
            assertNotNull(rig.sessions.hostLink)
        }
    }
}
