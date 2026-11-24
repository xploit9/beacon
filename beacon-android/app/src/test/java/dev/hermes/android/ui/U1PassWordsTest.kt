package dev.hermes.android.ui


import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The 1.2.3 UX pass: the new words exist in every language, the New file form builds its own path, and the plugin verdict names its cause. */
class U1PassWordsTest {
    private val catalog = JSONObject(listOf("src/main/assets/native-locales.json", "app/src/main/assets/native-locales.json", "beacon-android/app/src/main/assets/native-locales.json")
        .map(::File).first { it.isFile }.readText(Charsets.UTF_8))

    private fun pluginHealthJson(dashboardCurrent: Boolean = true, callsReady: Boolean = true) = JSONObject()
        .put("plugin", JSONObject().put("running", "1.1.23").put("installed", "1.1.23").put("gateway", "1.1.23"))
        .put("dashboard", JSONObject().put("current", dashboardCurrent))
        .put("gateway", JSONObject().put("known", true).put("running", true).put("busy", false).put("current", true))
        .put("calls", JSONObject().put("ready", callsReady).put("provider", "gemini").put("reason", if (callsReady) "" else "timeout"))
        .put("push", JSONObject().put("ready", true).put("reason", ""))

    @Test fun theKeepConnectedLinesAndNewWordsExistInEveryLanguage() {
        val keys = listOf("network changed. retrying...", "android paused beacon. retrying...", "open beacon to reconnect.", "lost the connection to hermes. retrying...", "can't reach hermes. retrying...",
            "hermes refused the connection. retrying...", "hermes had a problem. retrying...", "check beacon on hermes.", "sign in again", "reconnecting...", "connecting...", "signed out", "connected",
            "{name} can call you again once you're back online.", "getting ready so {name} can call you.", "sign in again so {name} can call you.",
            "turn it on in settings › notifications so {name} can call you.", "{name} can call you.", "file name", "in {folder}", "show {field}", "hide {field}", "allow full-screen alerts")
        for (language in catalog.keys()) for (key in keys) {
            if (language == "en" && !catalog.getJSONObject("en").has(key)) continue // English falls back to the key's own source text
            assertTrue("$language misses '$key'", catalog.getJSONObject(language).optString(key).isNotBlank())
        }
        for (language in catalog.keys()) for (key in keys.filter { "{" in it })
            for (slot in Regex("\\{[a-z]+\\}").findAll(key).map { it.value }.toSet())
                assertTrue("$language drops $slot in '$key'", catalog.getJSONObject(language).optString(key).isBlank() || slot in catalog.getJSONObject(language).getString(key))
    }

    @Test fun theNewFileFormAsksForANameAndBuildsThePath() {
        assertEquals("Atelier relaunch", folderLabel("C:\\Users\\Public\\Documents\\Atelier relaunch"))
        assertEquals("docs", folderLabel("/home/me/docs/"))
        assertEquals("C:", folderLabel("C:"))
        val body = newFilePath(JSONObject().put("path", "C:\\Work/").put("name", "  brief.md ").put("content", "x"))
        assertEquals("C:\\Work/brief.md", body.getString("path"))
        assertTrue(!body.has("name"))
        assertEquals("x", body.getString("content"))
    }

    @Test fun thePluginVerdictNamesTheFirstRowThatAsksSomething() {
        val calls = pluginHealthJson(callsReady = false)
        assertEquals("Calls", pluginHealthOf(calls, keepConnected = false, voiceKeySaved = true)!!.cause)
        assertEquals("Calls", pluginHealthOf(pluginHealthJson(callsReady = true), keepConnected = false, voiceKeySaved = false)!!.cause)
        assertNull(pluginHealthOf(pluginHealthJson(callsReady = true), keepConnected = false, voiceKeySaved = true)!!.cause)
        assertEquals("Hermes dashboard", pluginHealthOf(pluginHealthJson(dashboardCurrent = false, callsReady = false), false, true)!!.cause)
    }
}
