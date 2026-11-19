package dev.hermes.agentphone.helper.core

import dev.hermes.agentphone.helper.core.Fixtures.h
import dev.hermes.agentphone.helper.core.Fixtures.hex
import dev.hermes.agentphone.helper.core.Fixtures.hexOf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

/** The helper end against L9's test vectors (hermes-plugin/beacon/tests/fixtures/agent_phone_channel_v1_vectors.json). */
class VectorsTest {
    @Test
    fun copiesArePinned() {
        assertEquals(Fixtures.VECTORS_SHA256, Fixtures.sha256(Fixtures.bytes("agent_phone_channel_v1_vectors.json")))
        assertEquals(Fixtures.CONTRACT_SHA256, Fixtures.sha256(Fixtures.bytes("channel-v1.md")))
        assertEquals("docs/agent-phone/channel-v1.md", Fixtures.vectors["contract"])
        assertEquals(1L, Fixtures.vectors["protocol"])
        assertEquals(5L, Fixtures.vectors["revision"])
        assertEquals(Messages.PROTOCOL.toLong(), Fixtures.vectors["protocol"])
    }

    @Test
    fun handshakeValues() {
        val t = h("T")
        assertArrayEquals(t, Wire.tokenFromText(Fixtures.handshake["T_b64url"] as String))
        val ka = Wire.authKey(t)
        assertArrayEquals(h("Ka"), ka)
        val ch = Wire.clientHello(h("Nc"))
        assertArrayEquals(h("CH"), ch)
        assertArrayEquals(h("Ps"), Wire.hostProof(ka, ch, h("SH0")))
        assertArrayEquals(h("Ns"), Wire.checkServerHello(ka, ch, h("SH")))
        assertArrayEquals(h("Pc"), Wire.helperProof(ka, ch, h("SH0"), h("Ps")))
        assertArrayEquals(h("CP"), h("Pc"))
        assertArrayEquals(h("salt"), h("Nc") + h("Ns"))
        val (h2c, c2h) = Wire.linkKeys(t, h("Nc"), h("Ns"))
        assertArrayEquals(h("K_h2c"), h2c)
        assertArrayEquals(h("K_c2h"), c2h)
        @Suppress("UNCHECKED_CAST")
        val frames = Fixtures.handshake["frames"] as Map<String, String>
        assertArrayEquals(hex(frames.getValue("CH")), Wire.frame(ch))
        assertArrayEquals(hex(frames.getValue("SH")), Wire.frame(h("SH")))
        assertArrayEquals(hex(frames.getValue("CP")), Wire.frame(h("Pc")))
        assertEquals(Wire.CH_LEN, Wire.frameLength(hex(frames.getValue("CH")).copyOfRange(0, 4), Wire.CH_LEN))
    }

    @Test
    fun startCommandCarriesWhatTheServiceReads() {
        @Suppress("UNCHECKED_CAST")
        val cmd = Fixtures.handshake["start_command"] as List<String>
        assertEquals(listOf("am", "start-foreground-service", "-n", "dev.hermes.agentphone.helper/.ChannelService"), cmd.subList(0, 4))
        assertEquals(listOf("--ei", "port"), cmd.subList(4, 6))
        assertEquals((Fixtures.handshake["start_command_port"] as Long).toString(), cmd[6])
        assertEquals(listOf("--es", "token", "{T_b64url}"), cmd.subList(7, 10))
        // ChannelService reads exactly these extras.
        assertEquals("port", dev.hermes.agentphone.helper.ChannelService.EXTRA_PORT)
        assertEquals("token", dev.hermes.agentphone.helper.ChannelService.EXTRA_TOKEN)
    }

    @Test
    fun tokenText() {
        assertNull(Wire.tokenFromText(null))
        assertNull(Wire.tokenFromText(""))
        assertNull(Wire.tokenFromText("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="))
        assertNull(Wire.tokenFromText("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh"))
        assertNull(Wire.tokenFromText("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh+"))
        assertNull(Wire.tokenFromText("AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8A"))
    }

    @Test
    fun sealedFrames() {
        val (h2c, c2h) = Wire.linkKeys(h("T"), h("Nc"), h("Ns"))
        for (v in Fixtures.sealed) {
            val dir = if (v["dir"] == "host->helper") Wire.H2C else Wire.C2H
            val key = if (dir == Wire.H2C) h2c else c2h
            val seq = v["seq"] as Long
            val plain = hex(v["plaintext_hex"] as String)
            assertArrayEquals((v["plaintext_text"] as String).toByteArray(Charsets.UTF_8), plain)
            assertArrayEquals(hex(v["nonce"] as String), Wire.nonceFor(seq))
            assertArrayEquals(hex(v["aad"] as String), Wire.aadFor(dir, seq))
            assertArrayEquals(hex(v["body"] as String), Wire.seal(key, dir, seq, plain))
            assertArrayEquals(hex(v["frame"] as String), Wire.frame(hex(v["body"] as String)))
            assertArrayEquals(plain, Wire.open(key, dir, seq, hex(v["body"] as String)))
            // Each message is valid for its direction, and the helper writes its own messages with the very same bytes.
            val message = Messages.decode(plain, dir)
            assertArrayEquals(plain, Json.encode(message))
        }
    }

    @Test
    fun negatives() {
        val t = h("T")
        val ka = Wire.authKey(t)
        val (h2c, c2h) = Wire.linkKeys(t, h("Nc"), h("Ns"))
        val names = ArrayList<String>()
        for (v in Fixtures.negative) {
            val name = v["name"] as String
            names.add(name)
            val dir = if (v["dir"] == "host->helper") Wire.H2C else Wire.C2H
            try {
                when (v["check"]) {
                    "helper_checks_SH" -> Wire.checkServerHello(ka, h("CH"), hex(v["SH"] as String))
                    "host_checks_CP" -> {
                        // The host's check; the helper never makes a wrong CP, but the proof function must not produce one.
                        val cp = hex(v["CP"] as String)
                        if (!java.security.MessageDigest.isEqual(cp, Wire.helperProof(ka, h("CH"), h("SH0"), h("Ps")))) throw Closed("proof")
                    }
                    "host_reads_CH" -> Wire.readClientHello(hex(v["CH"] as String))
                    "handshake_length" -> Wire.frameLength(hex(v["header"] as String), (v["expected"] as Long).toInt())
                    "sealed_length" -> Wire.frameLength(hex(v["header"] as String))
                    "open" -> Wire.open(if (dir == Wire.H2C) h2c else c2h, dir, v["expected_seq"] as Long, hex(v["body"] as String))
                    "decode" -> Messages.decode(hex(v["plaintext_hex"] as String), dir)
                    else -> fail("unknown check in $name")
                }
                fail("accepted: $name")
            } catch (e: Closed) {
                // closed, as the contract says
            }
        }
        assertEquals(30, names.size)
        for (needed in listOf("item role outside the arm kind", "two items with one item_id", "no items", "release request without item_id",
            "context without source", "context with an unknown source", "link result found without a url", "link result with a plain http url")) {
            assertEquals(needed, 1, names.count { it == "message: $needed" })
        }
    }

    @Test
    fun theHelpersOwnMessagesPassTheHostCheck() {
        val ctx = linkedMapOf<String, Any?>(
            "package" to "dev.beacon.fixture.shop", "certs" to listOf("ab".repeat(32)), "web_domain" to null, "web_scheme" to null,
            "fields" to listOf(linkedMapOf("id" to "1", "role" to "otp", "focused" to true)), "at_ms" to 5L, "source" to "other",
        )
        val id = "req_" + "A".repeat(16)
        val ok = listOf(
            Fixtures.HELLO,
            mapOf("t" to "pong", "re" to 3),
            mapOf("t" to "error", "re" to 3, "code" to "not_armed"),
            mapOf("t" to "context_result", "re" to 3, "context" to null),
            mapOf("t" to "context_result", "re" to 3, "context" to ctx),
            mapOf("t" to "code_result", "re" to 3, "found" to false),
            mapOf("t" to "note_password_result", "re" to 3, "found" to false),
            mapOf("t" to "note_password_result", "re" to 3, "found" to true, "value" to "Tmp-AAAA-1111"),
            mapOf("t" to "note_link_result", "re" to 3, "found" to false),
            mapOf("t" to "note_link_result", "re" to 3, "found" to true, "url" to "https://kanto.example/magic?t=abc"),
            mapOf("t" to "error", "re" to 3, "code" to "unavailable"),
            mapOf("t" to "fill_context", "context" to ctx),
            mapOf("t" to "fill_failed", "req" to id, "arm_id" to id, "reason" to "timeout"),
            mapOf("t" to "release_request", "req" to id, "arm_id" to id, "item_id" to id, "roles" to listOf("otp"), "context" to ctx),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 0, "targets" to emptyList<Any>()),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to listOf(
                mapOf("id" to "1073741832", "view_id" to "dev.beacon.fixture.shop:id/code", "bounds" to listOf(63, 344, 1017, 470)),
                mapOf("id" to "1073741840@7", "view_id" to null, "bounds" to null))),
            mapOf("t" to "save", "save_id" to id, "context" to ctx, "values" to emptyMap<String, String>()),
            mapOf("t" to "bye", "reason" to "error"),
        )
        for (m in ok) Messages.encode(m, Wire.C2H)
        val bad = listOf(
            mapOf("t" to "note_password_result", "re" to 3, "found" to true),
            mapOf("t" to "open_result", "re" to 3, "found" to false), // gone in revision 3
            mapOf("t" to "note_link_result", "re" to 3, "found" to true),
            mapOf("t" to "note_link_result", "re" to 3, "found" to false, "url" to "https://kanto.example/x"),
            mapOf("t" to "note_link_result", "re" to 3, "found" to true, "url" to "http://kanto.example/x"),
            mapOf("t" to "note_link_result", "re" to 3, "found" to true, "url" to "https://kanto.example/" + "x".repeat(2030)),
            mapOf("t" to "context_result", "re" to 3, "context" to ctx + ("certs" to listOf("AB".repeat(32)))),
            mapOf("t" to "save", "save_id" to id, "context" to ctx, "values" to mapOf("otp" to "1")),
            mapOf("t" to "ack", "re" to 0),
            mapOf("t" to "release_request", "req" to id, "arm_id" to id, "roles" to listOf("otp"), "context" to ctx),
            mapOf("t" to "fill_context", "context" to ctx + ("source" to "sideload")),
            mapOf("t" to "fill_context", "context" to ctx - "source"),
            // code_filled targets (revision 4): exactly id, view_id, bounds; a value never rides along
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to listOf(mapOf("id" to "1", "value" to "482913"))),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to listOf(mapOf("id" to "", "bounds" to null))),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to listOf(mapOf("id" to "1", "bounds" to listOf(1, 2, 3)))),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to mapOf("id" to "1")),
            mapOf("t" to "code_filled", "arm_id" to id, "fields" to 1, "targets" to List(17) { mapOf("id" to "$it") }),
        )
        for (m in bad) try { Messages.encode(m, Wire.C2H); fail("accepted $m") } catch (e: Closed) { }
    }
}
