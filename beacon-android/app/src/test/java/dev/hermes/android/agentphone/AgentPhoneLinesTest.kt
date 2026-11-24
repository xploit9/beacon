package dev.hermes.android.agentphone

import dev.hermes.android.data.AGENT_PHONE_BUSY
import dev.hermes.android.data.AGENT_PHONE_DIDNT_START
import dev.hermes.android.data.AGENT_PHONE_WENT_WRONG
import dev.hermes.android.data.AgentPhoneAction
import dev.hermes.android.data.AgentPhoneAnswer
import dev.hermes.android.data.AgentPhoneLine
import dev.hermes.android.data.agentPhoneLine
import dev.hermes.android.data.agentPhoneRefusal
import dev.hermes.android.data.parseAgentPhoneChecks
import dev.hermes.android.data.parseAgentPhoneStatus
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.ui.agentPhoneLineStartsPhone
import dev.hermes.android.ui.normalized
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Contract revision 5 (docs/agent-phone/channel-v1.md §11): the host's reason codes and the password keyboard, as the app reads
 * them. EMU-JOIN fu2, A2 and A3 on the app side.
 */
class AgentPhoneLinesTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it).absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/assets/native-locales.json").isFile }) { "app module not found" }
    private val contract = File(app, "../../docs/agent-phone/channel-v1.md").normalize()

    private fun table(): Map<String, String> {
        val text = contract.readText(Charsets.UTF_8).substringAfter("### 11.1").substringBefore("### 11.2")
        return Regex("^\\| `([a-z_]+\\.[a-z_]+)` \\| (.+?) \\|\\r?$", RegexOption.MULTILINE).findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    @Test fun theAppKnowsExactlyTheContractsCodesAndTemplates() {
        val doc = table()
        assertTrue(doc.size >= 60)
        assertEquals(doc, AgentPhoneLines.templates)
    }

    @Test fun everyTemplateHasWordsInEveryLanguageTheCatalogHas() {
        val catalog = JSONObject(File(app, "src/main/assets/native-locales.json").readText(Charsets.UTF_8).removePrefix("﻿"))
        val missing = mutableListOf<String>()
        for (language in catalog.keys()) {
            if (language == "en") continue
            val words = catalog.getJSONObject(language)
            for ((code, template) in AgentPhoneLines.templates) {
                val said = words.optString(normalized(template))
                if (said.isBlank()) missing += "$language $code"
                // every placeholder kept, so the names still go in
                for (hole in Regex("\\{[a-z]+}").findAll(template).map { it.value }) if (said.isNotBlank() && hole !in said) missing += "$language $code lost $hole"
            }
        }
        assertEquals(emptyList<String>(), missing)
    }

    @Test fun aKnownCodeIsWordedByTheAppAndAnUnknownOneShowsTheLine() {
        val german = mapOf(normalized("Saved: {label} · {identifier}") to "Gespeichert: {label} · {identifier}")
        val lookup: (String) -> String = { german[normalized(it)] ?: it }
        assertEquals("Gespeichert: Kanto Foods · k${'@'}example.com",
            AgentPhoneLines.words("vault.saved", "Saved: Kanto Foods · k${'@'}example.com", mapOf("label" to "Kanto Foods", "identifier" to "k${'@'}example.com"), "Maya", lookup))
        assertEquals("Maya's phone can't start while Hermes' dashboard is off.",
            AgentPhoneLines.words("check.dashboard_off", "{name}'s phone can't start while Hermes' dashboard is off.", emptyMap(), "Maya") { it })
        assertEquals("A line the app has no code for.", AgentPhoneLines.words("phone.from_the_future", "A line the app has no code for.", emptyMap(), "Maya", lookup))
        assertEquals("Turn on Hermes sign-in first.", AgentPhoneLines.words(null, "Turn on Hermes sign-in first.", emptyMap(), "Maya", lookup))
    }

    /** The exact lines the host's stream sends (json.dumps with compact separators, test_agent_phone_fu2.py pins the same bytes). */
    @Test fun theHostsOwnStateLinesReachTheSession() {
        val prompt = """{"t":"state","helper":"ready","keyboard":false,"rotation":0,"prompt":"usb_debugging","lease":"you","epoch":1}"""
        val password = """{"t":"state","helper":"ready","keyboard":"password","rotation":0,"prompt":null,"lease":"you","epoch":1}"""
        assertEquals(ServerText.State(false, 0, "ready", "usb_debugging", controlEpoch = 1), parseServerText(prompt))
        assertEquals(ServerText.State(true, 0, "ready", null, password = true, controlEpoch = 1), parseServerText(password))
    }

    // -- the join (EMU-JOIN fu2): the codes on L4's page and in L6's card and list ------------------------------------------------

    @Test fun l4sPageWordsACodedLineByItsCodeAndKeepsItsOwnHeadlines() {
        // a code with an action: the contract's words, the names filled in, no Details
        val disk = agentPhoneLine("Not enough free space. Free up 9 GB on the Hermes computer.", code = "check.disk_low", args = mapOf("n" to "9"))!!
        assertEquals(AgentPhoneLine("Not enough free space. Free up {n} GB on the Hermes computer.", action = AgentPhoneAction.CheckAgain,
            code = "check.disk_low", named = mapOf("n" to "9")), disk)
        assertFalse(disk.hasDetails)
        // a code L4 words as its own line: L4's headline, the code's words for Details
        val busy = agentPhoneLine("Busy, try again.", code = "hermes.busy")!!
        assertEquals(AGENT_PHONE_BUSY, busy.template)
        assertEquals("hermes.busy", busy.code)
        assertTrue(busy.hasDetails)
        // a start that failed inside the phone's process: "The phone didn't start." with Try again, so it still starts the phone
        val didnt = AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)
        val process = agentPhoneLine("The phone's process could not start.", didnt, "phone.process")!!
        assertEquals(AGENT_PHONE_DIDNT_START, process.template)
        assertTrue(agentPhoneLineStartsPhone(process))
        assertEquals("phone.process", process.code)
        // the routes' own refusals (§11.1 "any route's 403, 404 or 503")
        assertEquals(AgentPhoneAction.ChooseProfile, agentPhoneLine("{name}'s phone belongs to another profile.", code = "phone.other_profile")!!.action)
        assertEquals(AgentPhoneAction.Update, agentPhoneLine("The phone can't start on this Hermes. Update Beacon.", didnt, "phone.no_keeper")!!.action)
        // an older plugin (no code) and an unknown code: the words decide, as before
        assertEquals(agentPhoneLine("Turn on Hermes sign-in first."), agentPhoneLine("Turn on Hermes sign-in first.", code = "check.from_the_future"))
        assertEquals(AgentPhoneAction.How, agentPhoneLine("Turn on Hermes sign-in first.")!!.action)
    }

    @Test fun l4sParsersKeepEachLinesCode() {
        val status = parseAgentPhoneStatus(JSONObject("""{"installed":true,"phone":"off","error":"The phone didn't start.","error_reason":"phone.not_started",
            "error_args":{},"warning":"w","warning_reason":"check.developer_tools","install":{"state":"failed","line":"Not enough free space. Free up 9 GB on the Hermes computer.",
            "reason":"install.space","args":{"n":"9"},"fix":"Check the Hermes computer's internet.","fix_reason":"check.network_fix"}}"""))
        assertEquals("phone.not_started", status.errorCode)
        assertEquals("check.developer_tools", status.warningCode)
        assertEquals("install.space", status.install.lineCode)
        assertEquals(mapOf("n" to "9"), status.install.lineArgs)
        assertEquals("check.network_fix", status.install.fixCode)
        val checks = parseAgentPhoneChecks(JSONObject("""{"ok":false,"rows":[{"id":"disk","status":"fail","line":"x","reason":"check.disk_low","args":{"n":"9"},"fix":"y","fix_reason":"check.network_fix"}]}"""))
        assertEquals(Triple("check.disk_low", mapOf("n" to "9"), "check.network_fix"), checks.rows.single().let { Triple(it.lineCode, it.lineArgs, it.fixCode) })
        val refused = agentPhoneRefusal(AgentPhoneAnswer(404, JSONObject("""{"detail":"{name}'s phone isn't set up yet.","line":"{name}'s phone isn't set up yet.","reason":"phone.none"}""")),
            AgentPhoneLine(AGENT_PHONE_WENT_WRONG))
        assertEquals("phone.none", refused.code)
    }

    @Test fun l6sCardTakesItsSentenceFromTheCodeAndTheRecordsOwnNames() {
        val code = parseAgentApprovals(JSONObject().put("approvals", org.json.JSONArray().put(JSONObject().put("id", "a".repeat(32)).put("kind", "vault_code")
            .put("expires_in_seconds", 60).put("app", "Amazon").put("label", "Amazon").put("line", "Enter the code for Amazon.").put("reason", "vault.code"))), 0).single()
        assertEquals(PhoneLine("Enter the code for {0}.", listOf("Amazon")), recordLine(code))
        val signIn = code.copy(kind = ApprovalKind.NeedsYou, reason = "vault.sign_in", site = "kanto.example", line = "Sign in to kanto.example on the phone.")
        assertEquals(PhoneLine("Sign in to {0} on the phone.", listOf("kanto.example")), recordLine(signIn))
        assertEquals(PhoneLine("Saved: {0} · {1}", listOf("Kanto Foods", "k${'@'}example.com")),
            codedLine("vault.saved", mapOf("label" to "Kanto Foods", "identifier" to "k${'@'}example.com")))
        // a code the app doesn't know, or a name the record lacks: the line's own words, as before
        assertEquals(serverLine("The Amazon login didn't work."), recordLine(code.copy(reason = "vault.from_the_future", line = "The Amazon login didn't work.")))
        assertEquals(null, codedLine("vault.sign_in", mapOf("site" to "")))
    }

    @Test fun l6sListReadsASaveRowAsSavedALoginWithItsIdentifier() {
        val now = dev.hermes.android.data.AgentPhoneActivity(1_794_916_000_000, "Kanto Foods", "save", "k${'@'}example.com", true, "saved", 7)
        val line = logLine(now)
        assertEquals(PhoneLine("{0}", listOf("k${'@'}example.com")), line.title)
        assertEquals("Saved a login", line.verb)
        assertEquals("", line.outcome)
        // an older plugin's whole sentence still reads as it says
        val older = logLine(now.copy(label = "Saved: Kanto Foods · k${'@'}example.com"))
        assertEquals(PhoneLine("Saved: {0} · {1}", listOf("Kanto Foods", "k${'@'}example.com")), older.title)
    }

    @Test fun anActivityTimeReadsAsTheChatsDo() {
        val zone = java.time.ZoneId.of("UTC")
        val today = java.time.LocalDate.of(2026, 11, 17)
        fun at(date: String) = java.time.Instant.parse(date).toEpochMilli()
        val us = java.util.Locale.US
        assertEquals("3:44 PM", dev.hermes.android.ui.agentPhoneActivityTime(at("2026-11-17T15:44:00Z"), today, zone, us, false, "Yesterday"))
        assertEquals("Yesterday", dev.hermes.android.ui.agentPhoneActivityTime(at("2026-11-16T09:00:00Z"), today, zone, us, false, "Yesterday"))
        assertEquals("Nov 3", dev.hermes.android.ui.agentPhoneActivityTime(at("2026-11-03T09:00:00Z"), today, zone, us, false, "Yesterday"))
        assertEquals("Nov 17, 2025", dev.hermes.android.ui.agentPhoneActivityTime(at("2025-11-17T15:44:00Z"), today, zone, us, false, "Yesterday"))
    }

    @Test fun thePasswordKeyboardAndTheCodesAreRead() {
        val password = parseServerText("""{"t":"state","keyboard":"password","rotation":0,"helper":"ready","lease":"you"}""") as ServerText.State
        assertTrue(password.keyboard && password.password)
        val plain = parseServerText("""{"t":"state","keyboard":true,"rotation":0,"helper":"ready","lease":"you"}""") as ServerText.State
        assertTrue(plain.keyboard)
        assertFalse(plain.password)
        val notice = parseServerText("""{"t":"notice","kind":"saved","line":"Saved: Kanto Foods · k${'@'}example.com","reason":"vault.saved","args":{"label":"Kanto Foods","identifier":"k${'@'}example.com"}}""")
        assertEquals(ServerText.Notice("saved", "Saved: Kanto Foods · k${'@'}example.com", "vault.saved",
            mapOf("label" to "Kanto Foods", "identifier" to "k${'@'}example.com")), notice)
        assertEquals("check.sign_in_off", JSONObject("""{"phone":"off","line":"Turn on Hermes sign-in first.","reason":"check.sign_in_off"}""").code())
        assertEquals(null, JSONObject("""{"reason":null}""").code())
    }

    // -- AGENTUX-121 ------------------------------------------------------------------------------------------------------------------

    // REVIEW-FIX-UI-121 P2 (ported): the host sends row("container", FAIL, line = cannot_run, fix = container); the fix is the
    // instruction, so it must not read as the line a second time with the instruction only behind Details.
    @Test fun theContainerRowsFixIsNotItsLineAgain() {
        val line = agentPhoneLine("This computer can't run a virtual phone.", code = "check.cannot_run")!!
        for (fix in listOf(agentPhoneLine("Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.", code = "check.container")!!,
                agentPhoneLine("Give the container read and write access to /dev/kvm, or run Hermes on a computer instead.")!!)) {
            assertFalse(line.template == fix.template)
            assertTrue(fix.template.contains("/dev/kvm"))
        }
    }

    @Test fun theDeveloperToolsLineIsShortWithItsReasonBehindDetails() {
        val long = "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to."
        // By its code (revision 5) and by its words (an older plugin, the tour's fixtures): the short line, the reason kept for Details.
        for (line in listOf(agentPhoneLine(long, code = "check.developer_tools")!!, agentPhoneLine(long.replace("{name}", "Maya"))!!)) {
            assertEquals(dev.hermes.android.data.AGENT_PHONE_DEV_TOOLS, line.template)
            assertEquals(AgentPhoneAction.CheckAgain, line.action)
            assertEquals("check.developer_tools", line.code)
            assertTrue(line.hasDetails)
        }
        // A start that failed outside Hermes' job reads as the plain start failure, its words in Details.
        val didnt = AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)
        for (job in listOf(agentPhoneLine("The phone's process could not start outside Hermes' job.", didnt, "phone.process_job")!!,
                agentPhoneLine("The phone's process could not start outside Hermes' job.", didnt)!!)) {
            assertEquals(AGENT_PHONE_DIDNT_START, job.template)
            assertTrue(job.hasDetails)
        }
    }

    @Test fun aSavedLoginNoticeNamesOnlyItsApp() {
        val identity: (String) -> String = { it }
        fun shown(flash: Flash) = noticeWords(flash, "Maya", identity).filter { it !in "\u2066\u2067\u2068\u2069" }
        assertEquals("Saved: Kanto Foods", shown(Flash(FlashKind.Notice, "Saved: Kanto Foods · k${'@'}example.com", 1, "vault.saved",
            mapOf("label" to "Kanto Foods", "identifier" to "k${'@'}example.com"))))
        assertEquals("Saved: Kanto Foods", shown(Flash(FlashKind.Notice, "Saved: Kanto Foods · k${'@'}example.com", 2)))
        assertEquals("Couldn't save that login: the app didn't give its username.", shown(Flash(FlashKind.Notice,
            "Couldn't save that login: the app didn't give its username.", 3, "vault.saved_no_username")))
    }
}
