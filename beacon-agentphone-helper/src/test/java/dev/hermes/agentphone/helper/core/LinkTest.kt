package dev.hermes.agentphone.helper.core

import dev.hermes.agentphone.helper.core.Fixtures.h
import dev.hermes.agentphone.helper.core.Fixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The helper's link against a host built from the contract: the vectors' transcript byte for byte, then every way it closes. */
class LinkTest {
    private class Running(val link: Link, val reason: AtomicReference<String?>, val done: CountDownLatch, val live: AtomicInteger)

    private fun start(host: TestHost, helper: Helper, token: ByteArray = h("T"), nonce: ByteArray = h("Nc"), goLive: Boolean = true): Running {
        val link = Link(token.copyOf(), helper, Fixtures.HELLO, { nonce })
        val reason = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val live = AtomicInteger()
        Thread {
            val conn = SocketConn(Socket(InetAddress.getLoopbackAddress(), host.port))
            reason.set(link.run(conn.input, conn.output, { conn.close() }, { conn.timeout(it) }) { live.incrementAndGet(); goLive })
            done.countDown()
        }.start()
        return Running(link, reason, done, live)
    }

    private fun plain(i: Int) = hex(Fixtures.sealed[i]["plaintext_hex"] as String)
    private fun body(i: Int) = hex(Fixtures.sealed[i]["body"] as String)
    private fun frame(i: Int) = hex(Fixtures.sealed[i]["frame"] as String)

    @Suppress("UNCHECKED_CAST")
    private fun parsed(i: Int) = Json.parseUtf8(plain(i)) as Map<String, Any?>

    @Test
    fun theVectorsTranscriptByteForByte() {
        TestHost(h("T"), h("Ns")).use { host ->
            val helper = RecordingHelper()
            val run = start(host, helper)
            val c = host.accept()
            @Suppress("UNCHECKED_CAST")
            val frames = Fixtures.handshake["frames"] as Map<String, String>
            assertArrayEquals(hex(frames.getValue("CH")), c.readExactly(4 + Wire.CH_LEN))
            c.writeRaw(hex(frames.getValue("SH")))
            assertArrayEquals(hex(frames.getValue("CP")), c.readExactly(4 + Wire.CP_LEN))
            assertArrayEquals(body(0), c.readSealedBody()) // hello, seq 0
            assertFalse(run.link.event(parsed(4))) // events wait for the version agreement
            c.writeRaw(frame(1)) // hello_ok
            c.writeRaw(frame(2)) // arm, id 1
            assertArrayEquals(body(3), c.readSealedBody()) // ack re 1, seq 1
            assertEquals("arm", helper.requests.poll(5, TimeUnit.SECONDS)!!["t"])
            assertTrue(run.link.event(parsed(4)))
            assertArrayEquals(body(4), c.readSealedBody()) // release_request, seq 2
            c.writeRaw(frame(5)) // release
            val answer = helper.answers.poll(5, TimeUnit.SECONDS)!!
            assertEquals("req_BBBBBBBBBBBBBBBB", answer.first)
            assertEquals(mapOf("username" to "وكيل.تجريبي", "password" to "Test-Pass-1111-كلمة"), answer.second)
            assertTrue(run.link.event(parsed(6)))
            assertArrayEquals(body(6), c.readSealedBody()) // filled, seq 3
            assertTrue(run.link.event(parsed(7)))
            assertArrayEquals(body(7), c.readSealedBody()) // save, seq 4
            c.writeRaw(frame(8)) // clip_set, id 2
            val (_, c2h) = Wire.linkKeys(h("T"), h("Nc"), h("Ns"))
            val ack = Wire.open(c2h, Wire.C2H, 5, c.readSealedBody())
            assertEquals("{\"t\":\"ack\",\"re\":2}", String(ack, Charsets.UTF_8))
            assertEquals("café naïve مرحبا 👋", helper.requests.poll(5, TimeUnit.SECONDS)!!["text"])
            c.writeRaw(frame(9)) // bye
            assertTrue(run.done.await(5, TimeUnit.SECONDS))
            assertEquals("bye", run.reason.get())
            assertEquals(1, helper.ended)
            assertTrue(c.closedByHelper())
        }
    }

    @Test
    fun aHostWithoutTheTokenGetsNoProofAndNoLink() {
        TestHost(h("T")).use { host ->
            val helper = RecordingHelper()
            val run = start(host, helper)
            val c = host.accept()
            assertFalse(c.handshake(proofToken = ByteArray(32) { 7 }))
            assertTrue(run.done.await(5, TimeUnit.SECONDS))
            assertEquals("proof", run.reason.get())
            assertEquals(0, run.live.get())
            assertEquals(0, helper.ended)
        }
    }

    @Test
    fun aJunkServerHelloCloses() {
        for (junk in listOf(
            Wire.frame(ByteArray(69)),
            Wire.frame(byteArrayOf(0x42, 0x41, 0x50, 0x43, 2) + ByteArray(64)),
            Wire.frame(ByteArray(70)),
            "HTTP/1.1 200 OK\r\n\r\n".toByteArray(),
        )) {
            TestHost(h("T")).use { host ->
                val helper = RecordingHelper()
                val run = start(host, helper)
                val c = host.accept()
                c.readBody(Wire.CH_LEN)
                c.writeRaw(junk)
                assertTrue(run.done.await(5, TimeUnit.SECONDS))
                assertEquals(0, run.live.get())
                assertTrue(c.closedByHelper())
            }
        }
    }

    private fun live(host: TestHost, helper: RecordingHelper): Pair<Running, HostConn> {
        val run = start(host, helper, nonce = ByteArray(32) { 3 })
        val c = host.accept()
        assertTrue(c.handshake())
        assertEquals("hello", c.read()["t"])
        return run to c
    }

    @Test
    fun everyWayALiveLinkCloses() {
        val cases: List<Pair<String, (HostConn) -> Unit>> = listOf(
            "order" to { c -> c.send(mapOf("t" to "ping", "id" to 1)) },
            "version" to { c -> c.send(mapOf("t" to "hello_ok", "proto" to 2)) },
            "message" to { c -> c.send(mapOf("t" to "surprise")) },
            "message" to { c -> c.send(mapOf("t" to "hello_ok", "proto" to 1)); c.send(mapOf("t" to "ack", "re" to 1)) },
            "order" to { c -> c.send(mapOf("t" to "hello_ok", "proto" to 1)); c.send(mapOf("t" to "hello_ok", "proto" to 1)) },
            "message" to { c ->
                c.send(mapOf("t" to "hello_ok", "proto" to 1))
                c.send(mapOf("t" to "arm", "id" to 1, "arm_id" to "a".repeat(16), "kind" to "login", "package" to "p",
                    "web_domain" to null, "items" to listOf(mapOf("item_id" to "i".repeat(16), "label" to "x", "roles" to listOf("password"))),
                    "ttl_ms" to 20001))
            },
            "message" to { c ->
                c.send(mapOf("t" to "hello_ok", "proto" to 1))
                c.send(mapOf("t" to "arm", "id" to 1, "arm_id" to "a".repeat(16), "kind" to "otp", "package" to "p",
                    "web_domain" to null, "items" to listOf(mapOf("item_id" to "i".repeat(16), "label" to "x", "roles" to listOf("password"))),
                    "ttl_ms" to 100))
            },
            "message" to { c -> c.send(mapOf("t" to "hello_ok", "proto" to 1)); c.send(mapOf("t" to "ping", "id" to "1")) },
            "tag" to { c -> c.writeRaw(Wire.frame(ByteArray(8) + ByteArray(40) { 9 })) },
            "sequence" to { c -> c.writeRaw(Wire.frame(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 5) + ByteArray(40))) },
            "length" to { c -> c.writeRaw(byteArrayOf(0, 4, 0, 1)) },
            "length" to { c -> c.writeRaw(byteArrayOf(0, 0, 0, 24)) },
            "bye" to { c -> c.send(mapOf("t" to "bye", "reason" to "done")) },
        )
        for ((expected, act) in cases) {
            TestHost(h("T")).use { host ->
                val helper = RecordingHelper()
                val (run, c) = live(host, helper)
                act(c)
                assertTrue(expected, run.done.await(5, TimeUnit.SECONDS))
                assertEquals(expected, run.reason.get())
                assertEquals(1, helper.ended)
                assertTrue(c.closedByHelper())
            }
        }
    }

    @Test
    fun aReplayedFrameCloses() {
        TestHost(h("T")).use { host ->
            val helper = RecordingHelper()
            val (run, c) = live(host, helper)
            c.send(mapOf("t" to "hello_ok", "proto" to 1))
            // Seal ping id 1 by hand at seq 1, send it, then send the very same bytes again.
            val (h2c, _) = Wire.linkKeys(h("T"), ByteArray(32) { 3 }, ByteArray(32) { (0x40 + it).toByte() })
            val ping = Wire.frame(Wire.seal(h2c, Wire.H2C, 1, Json.encode(mapOf("t" to "ping", "id" to 1))))
            c.writeRaw(ping)
            assertEquals("pong", c.read()["t"])
            c.writeRaw(ping)
            assertTrue(run.done.await(5, TimeUnit.SECONDS))
            assertEquals("sequence", run.reason.get())
        }
    }

    @Test
    fun requestsGetExactlyOneReplyEvenWhenTheHelperFails() {
        TestHost(h("T")).use { host ->
            val helper = RecordingHelper()
            helper.reply = { t, _ -> if (t == "clip_set") throw IllegalStateException("x") else Reply("pong") }
            val (_, c) = live(host, helper)
            c.send(mapOf("t" to "hello_ok", "proto" to 1))
            c.send(mapOf("t" to "clip_set", "id" to 1, "text" to "x", "ttl_ms" to 10))
            assertEquals(mapOf("t" to "error", "re" to 1L, "code" to "unavailable"), c.read())
            // A reply the host would refuse is never sent; the request still gets one (an error).
            helper.reply = { _, _ -> Reply("ack", mapOf("re" to "bad")) }
            c.send(mapOf("t" to "ping", "id" to 2))
            assertEquals(mapOf("t" to "error", "re" to 2L, "code" to "unavailable"), c.read())
            c.send(mapOf("t" to "deny", "req" to "r".repeat(16)))
            assertEquals("r".repeat(16) to null, helper.answers.poll(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun manySendersKeepOneStrictSequence() {
        TestHost(h("T")).use { host ->
            val helper = RecordingHelper()
            val (run, c) = live(host, helper)
            c.send(mapOf("t" to "hello_ok", "proto" to 1))
            c.send(mapOf("t" to "ping", "id" to 100))
            assertEquals("pong", c.read()["t"]) // the versions are agreed: events go out from here on
            val threads = (0 until 8).map { n ->
                Thread {
                    repeat(25) { i ->
                        run.link.event(mapOf("t" to "code_filled", "arm_id" to "arm_${n}_".padEnd(16, 'x') + i, "fields" to 1))
                    }
                }
            }
            threads.forEach { it.start() }
            repeat(10) { c.send(mapOf("t" to "ping", "id" to it + 1)) }
            var events = 0
            var pongs = 0
            repeat(210) {
                when (c.read()["t"]) { "code_filled" -> events++; "pong" -> pongs++ }
            }
            threads.forEach { it.join() }
            assertEquals(200, events)
            assertEquals(10, pongs)
        }
    }

    @Test
    fun aReplacedAttemptNeverBecomesTheLink() {
        TestHost(h("T")).use { host ->
            val helper = RecordingHelper()
            val run = start(host, helper, goLive = false)
            val c = host.accept()
            assertTrue(c.handshake())
            assertTrue(run.done.await(5, TimeUnit.SECONDS))
            assertEquals("replaced", run.reason.get())
            assertFalse(run.link.live)
            assertEquals(0, helper.ended)
            assertTrue(c.closedByHelper())
        }
    }
}
