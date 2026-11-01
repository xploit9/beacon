package dev.hermes.android.uiux.voiceinstant

import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.data.VoiceSessionCredentials
import dev.hermes.android.voice.GREETING_RENDERS_PER_DAY
import dev.hermes.android.voice.GreetingCache
import dev.hermes.android.voice.GreetingIdentity
import dev.hermes.android.voice.GreetingPlan
import dev.hermes.android.voice.phoneKeyCallBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** The phone's greeting cache: what it is keyed by, which line it picks, and when it forgets. */
class GreetingCacheTest {
    private val root: File = kotlin.io.path.createTempDirectory("greetings").toFile()
    @After fun clean() { root.deleteRecursively() }

    private val maya = Scope("https://hermes.example.com", "owner-1", "default", nativeProvider = "basic")
    private fun credentials(voice: String = "Aoede", personality: String = "balanced", revision: Long = 0) =
        VoiceSessionCredentials("gemini", "fixture-provider-key", "models/gemini-3.8-live", voice, personality, revision)
    private fun id(scope: Scope = maya, owner: String = "Maya", voice: String = "Aoede", personality: String = "balanced", revision: Long = 0) =
        GreetingIdentity.of(scope.session("chat-1"), owner, credentials(voice, personality, revision))
    // 18:15 in Dubai: evening on the profile's clock.
    private val evening = ZonedDateTime.of(2026, 11, 1, 18, 15, 0, 0, ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli()
    private var now = evening
    private val cache = GreetingCache(root, { now }, { "Asia/Dubai" })
    private val day = json("zone" to "Asia/Dubai", "variants" to JSONArray()
        .put(json("from_hour" to 0, "text" to "Hi, Maya. What can I do for you?"))
        .put(json("from_hour" to 5, "text" to "Good morning, Maya. What can I do for you?"))
        .put(json("from_hour" to 12, "text" to "Good afternoon, Maya. What can I do for you?"))
        .put(json("from_hour" to 17, "text" to "Good evening, Maya. What can I do for you?")))
    private fun context(plan: JSONObject? = day, greeting: String = "Good evening, Maya. What can I do for you?") =
        json("greeting" to greeting).apply { plan?.let { put("greeting_plan", it) } }
    // Leading digital silence, then speech.
    private fun speech(silentMs: Int = 300, speechMs: Int = 500): ByteArray {
        val silent = 24 * silentMs; val loud = 24 * speechMs
        return ByteArray((silent + loud) * 2).also { pcm -> for (i in silent until silent + loud) { pcm[i * 2] = 0x10; pcm[i * 2 + 1] = 0x20 } }
    }

    @Test fun theServerPlanPicksTheLineForTheProfilesHourAndItsAudioPlays() {
        val maya = id()
        cache.observe(maya, context(), "Good evening, Maya. What can I do for you?")
        assertNull("No audio yet: nothing to play", cache.ready(maya))
        cache.store(maya, "Good evening, Maya. What can I do for you?", speech())
        val (text, pcm) = requireNotNull(cache.ready(maya))
        assertEquals("Good evening, Maya. What can I do for you?", text)
        assertEquals("Leading silence is trimmed to a 20 ms lead-in", (24 * 20 + 24 * 500) * 2, pcm.size)
        // 09:30 on the profile's clock: the morning line, which has no audio yet.
        now = ZonedDateTime.of(2026, 11, 2, 9, 30, 0, 0, ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli()
        assertNull(cache.ready(maya))
        assertEquals("This hour's line renders first", "Good morning, Maya. What can I do for you?", cache.missing(maya).first())
        assertEquals(3, cache.missing(maya).size)
    }

    @Test fun theProfilesZoneDecidesTheHourNotThePhones() {
        val plan = requireNotNull(GreetingPlan.observed(context(), "x", "Maya", null, evening, "America/New_York"))
        assertEquals("Asia/Dubai", plan.zone)
        assertEquals("Good evening, Maya. What can I do for you?", plan.lineAt(evening))
        val dubaiMorning = ZonedDateTime.of(2026, 11, 2, 6, 0, 0, 0, ZoneId.of("Asia/Dubai")).toInstant().toEpochMilli()
        assertEquals("Good morning, Maya. What can I do for you?", plan.lineAt(dubaiMorning))
        // A fixed offset is a zone too.
        val offset = requireNotNull(GreetingPlan.observed(context(json("zone" to "+04:00", "variants" to day.getJSONArray("variants"))), "x", "Maya", null, evening, "UTC"))
        assertEquals("Good evening, Maya. What can I do for you?", offset.lineAt(evening))
    }

    @Test fun anOlderPluginsGreetingIsKnownOnlyForThisHourOfThePhonesDay() {
        val maya = id()
        cache.observe(maya, context(plan = null), "Good evening, Maya. What can I do for you?")
        cache.store(maya, "Good evening, Maya. What can I do for you?", speech())
        assertEquals("Good evening, Maya. What can I do for you?", cache.ready(maya)?.first)
        now += 3_600_000L
        assertNull("Another hour may have another line", cache.ready(maya))
        assertTrue(cache.missing(maya).isEmpty())
    }

    @Test fun anotherProfileVoicePersonalityKeyOrOwnerNameNeverPlaysIt() {
        val maya = id()
        cache.observe(maya, context(), "Good evening, Maya. What can I do for you?")
        cache.store(maya, "Good evening, Maya. What can I do for you?", speech())
        assertNotNull(cache.ready(maya))
        assertNull("Another profile", cache.ready(id(scope = this.maya.copy(profile = "work"))))
        assertNull("Another account", cache.ready(id(scope = this.maya.copy(owner = "owner-2"))))
        assertNull("Another voice", cache.ready(id(voice = "Puck")))
        assertNull("Another personality", cache.ready(id(personality = "warm")))
        assertNull("A changed phone key", cache.ready(id(revision = 1)))
        assertNull("Another owner name means other lines", cache.ready(id(owner = "Mia")))
        assertEquals("The chat does not matter", id().accountDir, GreetingIdentity.of(this.maya.session("chat-2"), "Maya", credentials()).accountDir)
        assertFalse("Directory names hold no account details", root.walk().any { it.name.contains("hermes.example.com") || it.name.contains("Maya") })
    }

    @Test fun aNewVoiceReplacesTheOldVoicesAudio() {
        cache.observe(id(), context(), "Good evening, Maya. What can I do for you?")
        cache.store(id(), "Good evening, Maya. What can I do for you?", speech())
        cache.store(id(voice = "Puck"), "Good evening, Maya. What can I do for you?", speech())
        assertNull("The old voice's directory is gone", cache.ready(id()))
        assertNotNull(cache.ready(id(voice = "Puck")))
    }

    @Test fun signOutAndAnotherProfileDeleteTheAccountsGreetings() {
        cache.observe(id(), context(), "Good evening, Maya. What can I do for you?")
        cache.store(id(), "Good evening, Maya. What can I do for you?", speech())
        val other = Scope("https://other.example.com", "owner-9", "default", nativeProvider = "basic")
        cache.observe(id(scope = other), context(), "Good evening, Maya. What can I do for you?")
        cache.store(id(scope = other), "Good evening, Maya. What can I do for you?", speech())
        File(root, "stray").mkdirs()
        cache.prune { it.origin == maya.origin && it.profile == "default" }
        assertNotNull("Still signed in", cache.ready(id()))
        assertNull("Signed out", cache.ready(id(scope = other)))
        assertFalse("Unknown directories go too", File(root, "stray").exists())
        cache.prune { false }
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test fun refreshRendersAreBoundedPerDay() {
        cache.observe(id(), context(), "Good evening, Maya. What can I do for you?")
        repeat(GREETING_RENDERS_PER_DAY - 1) { cache.spend(id()) }
        assertEquals("One render left today", 1, cache.missing(id()).size)
        cache.spend(id())
        assertTrue(cache.missing(id()).isEmpty())
        now += 24 * 3_600_000L + 1
        assertEquals("A day later the allowance is back", 4, cache.missing(id()).size)
    }

    @Test fun linesNoLongerPlannedLoseTheirAudioAndBadAudioIsRefused() {
        cache.observe(id(), context(), "Good evening, Maya. What can I do for you?")
        cache.store(id(), "Good evening, Maya. What can I do for you?", speech())
        cache.store(id(), "Good morning, Maya. What can I do for you?", ByteArray(3))
        cache.store(id(), "Good morning, Maya. What can I do for you?", ByteArray(48000))
        assertEquals("Silence or odd bytes are never kept", 3, cache.missing(id()).size)
        val renamed = json("zone" to "Asia/Dubai", "variants" to JSONArray().put(json("from_hour" to 0, "text" to "Hi, Maya.")))
        cache.observe(id(), context(renamed), "Hi, Maya.")
        assertNull(cache.ready(id()))
        assertTrue("The evening audio was deleted", root.walk().none { it.name.endsWith(".pcm") })
    }

    @Test fun aMalformedServerPlanFallsBackToThisHoursLine() {
        for (bad in listOf(json("zone" to "Nowhere/City", "variants" to day.getJSONArray("variants")),
            json("zone" to "Asia/Dubai", "variants" to JSONArray().put(json("from_hour" to 5, "text" to "Good morning."))),
            json("zone" to "Asia/Dubai", "variants" to JSONArray().put(json("from_hour" to 0, "text" to "x".repeat(161)))))) {
            val plan = requireNotNull(GreetingPlan.observed(context(bad), "Good evening, Maya.", "Maya", null, evening, "Asia/Dubai"))
            assertEquals("Good evening, Maya.", plan.lineAt(evening))
            assertEquals(1, plan.lines.count { it.isNotEmpty() })
        }
    }

    @Test fun theGrantCarriesTheOwnersChosenNameOnlyWhenSet() {
        assertEquals("Maya", phoneKeyCallBody("gemini", "default", "chat-1", " Maya ").getString("owner_name"))
        assertFalse(phoneKeyCallBody("gemini", "default", "chat-1").has("owner_name"))
        assertFalse(phoneKeyCallBody("gemini", "default", "chat-1", "  ").has("owner_name"))
    }
}
