package dev.hermes.agentphone.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger

/** The JSON the reference codec (Python's json module) reads and writes. */
class JsonTest {
    private fun enc(v: Any?) = String(Json.encode(v), Charsets.UTF_8)

    @Test
    fun writesWhatPythonWrites() {
        // json.dumps(..., ensure_ascii=False, separators=(',', ':'))
        assertEquals("{\"a\":1,\"b\":[true,false,null],\"c\":\"x\"}", enc(linkedMapOf("a" to 1, "b" to listOf(true, false, null), "c" to "x")))
        assertEquals("\"\\\"\\\\\\n\\r\\t\\b\\f\\u0001\\u001f/\u007f\"", enc("\"\\\n\r\t\b\u000c\u0001\u001f/\u007f"))
        assertEquals("\"café مرحبا 👋\"", enc("café مرحبا 👋"))
        assertEquals("\"\\ud800x\"", enc("\ud800x"))
        assertEquals("1800000000000", enc(1_800_000_000_000L))
    }

    @Test
    fun readsWhatPythonReads() {
        assertEquals(linkedMapOf("a" to 1L, "b" to listOf(1.5, -0.0, 2e3), "c" to "é\n😀"), Json.parse(" {\"a\":1,\"b\":[1.5,-0.0,2e3],\"c\":\"\\u00e9\\n\\ud83d\\ude00\"}\r\n"))
        assertEquals(mapOf("a" to 2L), Json.parse("{\"a\":1,\"a\":2}"))
        assertEquals(BigInteger("123456789012345678901234567890"), Json.parse("123456789012345678901234567890"))
        assertEquals(true, (Json.parse("NaN") as Double).isNaN())
        assertEquals(Double.NEGATIVE_INFINITY, Json.parse("-Infinity"))
    }

    @Test
    fun refusesWhatPythonRefuses() {
        val bad = listOf(
            "", "{", "{\"a\":1,}", "[1,]", "{'a':1}", "{a:1}", "01", "1.", ".5", "+1", "\"\u0001\"", "\"\\x\"", "\"\\u12\"",
            "\"\\u12g4\"", "{\"a\":1} x", "// c\n{}", "\ufeff{}", "tru", "nul", "\"abc", "[1 2]", "{\"a\" 1}", "\"\\u０１２３\"",
        )
        for (s in bad) {
            try { Json.parse(s); fail("accepted <$s>") } catch (e: Json.Invalid) { }
        }
        try { Json.parse("[".repeat(100) + "]".repeat(100)); fail("deep") } catch (e: Json.Invalid) { }
        for (b in listOf(byteArrayOf(0xff.toByte()), byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) {
            try { Json.parseUtf8(byteArrayOf(0x22) + b + byteArrayOf(0x22)); fail("bad utf-8") } catch (e: Json.Invalid) { }
        }
    }

    @Test
    fun lengthsAreCodePoints() {
        assertEquals(1, Messages.points("👋"))
        assertEquals(3, Messages.points("مرح"))
        val label = "👋".repeat(120)
        Messages.decode(Json.encode(mapOf("t" to "arm", "id" to 1, "arm_id" to "a".repeat(16), "kind" to "otp", "package" to "p",
            "web_domain" to null, "items" to listOf(mapOf("item_id" to "i".repeat(16), "label" to label, "roles" to listOf("otp"))),
            "ttl_ms" to 1)), Wire.H2C)
        try {
            Messages.decode(Json.encode(mapOf("t" to "clip_set", "id" to 1, "text" to "👋".repeat(10001), "ttl_ms" to 1)), Wire.H2C)
            fail("10,001 code points")
        } catch (e: Closed) { }
        Messages.decode(Json.encode(mapOf("t" to "clip_set", "id" to 1, "text" to "👋".repeat(10000), "ttl_ms" to 1)), Wire.H2C)
    }
}
