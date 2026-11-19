package dev.hermes.agentphone.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Codes, temporary passwords and links from notifications against L9's vectors (gate B8's look-alikes included). */
class NotesTest {
    private val n = Fixtures.notifications
    private val now = n["now_ms"] as Long
    private val senders = Senders.of(n["senders"] as Map<*, *>)
    private val at = String(charArrayOf('@'))

    @Suppress("UNCHECKED_CAST")
    private fun cases(kind: String) = n[kind] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun notes(case: Map<String, Any?>) = (case["notes"] as List<Map<String, Any?>>).map {
        Note(it["package"] as String, it["posted_ms"] as Long,
            (it["title"] as String).replace("{at}", at), (it["text"] as String).replace("{at}", at))
    }

    @Test
    fun codes() {
        val names = cases("codes").map { it["name"] as String }
        for (case in cases("codes")) {
            assertEquals(case["name"] as String, case["code"], Notes.code(notes(case), senders, now, case["max_age_ms"] as Long))
        }
        for (needed in listOf("another app", "longer domain", "look-alike", "Cyrillic", "not in mail_packages", "first address")) {
            assertTrue(needed, names.any { needed in it })
        }
    }

    @Test
    fun passwords() {
        for (case in cases("passwords")) {
            assertEquals(case["name"] as String, case["value"], Notes.password(notes(case), senders, now, case["max_age_ms"] as Long))
        }
    }

    @Test
    fun links() {
        for (case in cases("links")) {
            val url = Notes.link(notes(case), senders, now, case["max_age_ms"] as Long)
            val got = if (url == null) mapOf("found" to false) else mapOf("found" to true, "url" to url)
            assertEquals(case["name"] as String, case["result"], got)
        }
    }

    @Test
    fun aLinkABrowserMightReadDifferentlyIsNeverHandedOver() {
        val site = listOf("kanto.example")
        for (url in listOf(
            "https://evil.example\\${at}kanto.example/x",
            "https://kanto.example${at}evil.example/x",
            "https://kanto.example:x/x",
            "https://kanto.example./x",
            "https://kаnto.example/x",
        )) {
            val host = Notes.hostOf(url)
            assertTrue(url, host == null || !Notes.hostOk(host, site))
        }
        assertEquals("kanto.example", Notes.hostOf("https://kanto.example:443/magic?t=abc"))
        assertEquals("a.kanto.example", Notes.hostOf("https://A.Kanto.Example#x"))
        val mail = Senders(listOf("p"), site, emptyList())
        val long = "https://kanto.example/" + "x".repeat(2030)
        assertNull(Notes.link(listOf(Note("p", now, "", "Sign in: $long")), mail, now, 1000))
        assertEquals("https://kanto.example/b", Notes.link(listOf(Note("p", now, "", "$long or https://kanto.example/b")), mail, now, 1000))
    }

    @Test
    fun whitespaceAndCase() {
        val s = Senders(listOf("p"), emptyList(), emptyList())
        fun pw(text: String) = Notes.password(listOf(Note("p", now, "", text)), s, now, 1000)
        assertEquals("Tmp-ABCD", pw("PASSWORD: Tmp-ABCD"))
        assertEquals("Tmp-ABCD", pw("Password is Tmp-ABCD;"))
        assertNull(pw("password IS Tmp-ABCD")) // "is" is lower case only, as in the reference
        assertNull(pw("password: " + "x".repeat(65)))
        assertEquals("x".repeat(64), pw("password: " + "x".repeat(64) + ","))
        assertEquals("1234", Notes.firstCode("abc 12 1234"))
        assertNull(Notes.firstCode("١٢٣٤٥٦ 123"))
        assertEquals("4567", Notes.firstCode("x٤4567"))
    }

    @Test
    fun theStoreKeepsTwentyForTenMinutes() {
        var clock = 1_000_000L
        val store = Notes { clock }
        repeat(25) { store.add(Note("p$it", clock, "t", "x")) }
        assertEquals(20, store.snapshot().size)
        assertEquals("p5", store.snapshot().first().pkg)
        clock += 600_001
        assertEquals(0, store.snapshot().size)
    }
}
