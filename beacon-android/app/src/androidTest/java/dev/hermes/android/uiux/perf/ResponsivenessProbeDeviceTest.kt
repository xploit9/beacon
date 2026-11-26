package dev.hermes.android.uiux.perf

import android.util.Log
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconRoot
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.copy
import dev.hermes.android.preferences
import dev.hermes.android.ui.MoreScreen
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.ChatScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import java.io.File

/**
 * Perceived-responsiveness probe: one synthetic Hermes, the real ViewModel and the real Settings screens. Each row is
 * opened the way a tap opens it, and two stamps are recorded against the tap: the first frame of the destination and the
 * first piece of its content. The report is written to the app's files and logged as PERF lines for the release report.
 * Warm means the answers this process already read; slow means every synthetic REST answer is held for [SLOW_NETWORK_MS].
 */
class ResponsivenessProbeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val results = JSONArray()
    private var store: ViewModelStore? = null

    private data class Page(val name: String, val rows: List<String>, val firstTag: String, val contentTag: String, val firstText: String = "", val contentText: String = "",
        val usableTag: String = "", val usableDesc: String = "")

    private fun <T> flow(vm: BeaconViewModel, name: String): MutableStateFlow<T> =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: BeaconViewModel, name: String, value: Any?) =
        BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)

    @After fun writeResults() {
        if (results.length() == 0) return
        val dir = File(compose.activity.getExternalFilesDir(null) ?: compose.activity.filesDir, "responsiveness").apply { mkdirs() }
        File(dir, "probe-${testName.methodName}.json").writeText(results.toString(2), Charsets.UTF_8)
        for (index in 0 until results.length()) {
            val row = results.getJSONObject(index)
            Log.i("PERF", "path=${row.getString("path")} mode=${row.getString("mode")} first_ms=${row.getLong("first_ms")} content_ms=${row.getLong("content_ms")}")
        }
    }

    private fun withVm(render: Boolean = true, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        val fixture = NativeExperienceFixture()
        val viewModelStore = ViewModelStore().also { store = it }
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit { it[stringPreferencesKey("origin")] = "" }
                app.auth.persist(fixture.client.auth)
            }
            compose.runOnUiThread { vm = ViewModelProvider(viewModelStore, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            // The constructor's restore/preferences job settles to the empty setup state on this blank-origin harness. Let it
            // finish before stating the test account; otherwise it can overwrite origin mid-run and blank the Connection host.
            val settled = System.currentTimeMillis() + 10_000
            while (vm.ui.value.restoringSession && System.currentTimeMillis() < settled) Thread.sleep(20)
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, owner = fixture.scope.owner, profile = fixture.scope.profile,
                    authenticated = true, connected = true, restoringSession = false, tab = "More", morePage = "", initialChatReady = true)
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id"); put("model_provider", optString("provider")) }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot)
                vm.storeData("native-usage:default:chat-a", json("input_tokens" to 1200, "output_tokens" to 340, "context_window" to 32000, "model" to "synthetic-model"))
            }
            // The warm start a screen sees: the account row is open and the small reads are in hand.
            runBlocking {
                vm.screens.open(fixture.scope)
                listOf("navigation-profiles" to "/api/profiles", "native-tools" to "/api/tools/toolsets", "native-mcp" to "/api/mcp/servers",
                    "slash:catalog" to "/api/commands/catalog", "picker:models" to "/api/models", "profile-defaults" to "/api/profile/defaults")
                    .forEach { (key, path) -> vm.load(key, path).join() }
                vm.phoneSettings.bind(); vm.phoneSettings.loadBots()?.join()
                vm.phoneSettings.loadSkills(force = true)
                withTimeout(20_000) { vm.phoneSettings.skills.first { !it.loading } }
                vm.phoneSettings.loadMemory(force = true)
                withTimeout(20_000) { vm.phoneSettings.memory.first { !it.loading } }
                listOf("Health", "Logs", "Server updates", "Session checkpoints").forEach { page ->
                    vm.phoneSettings.loadServer(page, force = true)
                    withTimeout(20_000) { vm.phoneSettings.server.first { it.asked && !it.loading } }
                }
            }
            if (render) {
                compose.setContent { Screen(vm) }
                compose.waitUntil(20_000) { vm.ui.value.authenticated && vm.ui.value.morePage == "" }
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store?.clear(); fixture.client.closeNative() }
            runBlocking {
                app.auth.remove(fixture.scope.origin)
                app.preferences.updateData { prior }
            }
        }
    }

    @Composable private fun Screen(vm: BeaconViewModel) = NativeLocalization("en") {
        BeaconTheme(dark = true) { Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { MoreScreen(vm) } }
    }

    private fun shownTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    private fun enabledTag(tag: String): Boolean = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
    private fun enabledDesc(text: String): Boolean = compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().any { !it.config.contains(SemanticsProperties.Disabled) }
    private fun shownText(text: String): Boolean = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    private fun shownTextPart(text: String): Boolean = compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    private fun ms(start: Long): Long = (System.nanoTime() - start) / 1_000_000L

    /** A page picture for the report beside the timings; a device that cannot screenshot keeps the numbers. */
    private fun shot(name: String) {
        val image = runCatching { InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() }.getOrNull() ?: return
        val dir = File(compose.activity.getExternalFilesDir(null) ?: compose.activity.filesDir, "responsiveness").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    private fun openRow(tag: String) {
        val node = compose.onNodeWithTag(tag)
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    private fun measure(vm: BeaconViewModel, page: Page, mode: String): JSONObject {
        Log.i("PERF", "measure $mode ${page.name}")
        // A page reached through a profile leaves that profile open; the hub itself is the next page's start.
        compose.runOnIdle { vm.phoneSettings.closeBot() }
        compose.runOnIdle { vm.tab("More"); vm.more("") }
        compose.waitUntil(20_000) { shownTag("settings-overview") }
        compose.waitForIdle()
        // The click being measured is the one that opens the page: a two- or three-step route is setup, not latency.
        var start = System.nanoTime()
        page.rows.forEachIndexed { index, row ->
            if (index == page.rows.lastIndex) start = System.nanoTime()
            openRow(row)
            if (index < page.rows.size - 1) {
                // The hub's "Profiles & model" row reuses "settings-row:Profiles" for its category and for its
                // first child. The category page names its sibling, which is a marker the hub does not have.
                // Only the hub -> category step needs the text marker: the child row reuses the same tag, so waiting
                // again after the second tap would look for a page that is already gone.
                if (index == 0 && row == "settings-row:Profiles") compose.waitUntil(60_000) { shownText("Providers & models") }
                compose.waitUntil(60_000) { shownTag(page.rows[index + 1]) }
            }
        }
        compose.waitUntil(60_000) { page.firstTag.isNotBlank() && shownTag(page.firstTag) || page.firstText.isNotBlank() && shownText(page.firstText) }
        val first = ms(start)
        compose.waitUntil(60_000) { page.contentTag.isNotBlank() && shownTag(page.contentTag) || page.contentText.isNotBlank() && shownText(page.contentText) }
        val content = ms(start)
        val wantsUsable = page.usableTag.isNotBlank() || page.usableDesc.isNotBlank()
        // A control that is still disabled after five seconds records the elapsed time and the flag: the baseline
        // intentionally disabled these during a refresh, and the budget, not this test, is where that has to fail.
        val usableReady = !wantsUsable || runCatching {
            compose.waitUntil(5_000) { page.usableTag.isNotBlank() && enabledTag(page.usableTag) || page.usableDesc.isNotBlank() && enabledDesc(page.usableDesc) }
        }.isSuccess
        val usable = if (wantsUsable) ms(start) else content
        Log.i("PERF", "done ${page.name} first=$first content=$content usable=$usable")
        return JSONObject().put("path", page.name).put("mode", mode).put("first_tag", page.firstTag).put("content_tag", page.contentTag)
            .put("first_ms", first).put("content_ms", content).put("usable_ms", usable).put("usable_ready", usableReady)
    }

    private val pages = listOf(
        Page("Connection", listOf("settings-connection"), "settings-connection-page", "settings-hero"),
        Page("Assistant", listOf("settings-row:Assistant"), "assistant-settings", "assistant-name"),
        Page("Voice & calls", listOf("settings-row:Beacon"), "voice-hero", "voice-hero"),
        Page("Phone access", listOf("settings-row:Phone access"), "phone-access", "phone-access-capabilities"),
        Page("Notifications", listOf("settings-row:Notifications"), "notification-settings", "notification-settings"),
        Page("Chat", listOf("settings-row:Settings"), "chat-preferences", "settings-section:Sending"),
        Page("Appearance", listOf("settings-row:Appearance"), "appearance-settings", "settings-section:Theme"),
        Page("Profiles", listOf("settings-row:Profiles", "settings-row:Profiles"), "profiles-list", "profile-open:default", usableTag = "profile-open:default"),
        Page("Providers & models", listOf("settings-row:Profiles", "settings-row:Providers & models"), "providers-page", "providers-this-chat"),
        Page("Tools & commands", listOf("settings-row:Tools & MCP", "settings-row:Tools & commands"), "tool-settings", "tool-row:web", usableTag = "tool-row:web"),
        Page("MCP servers", listOf("settings-row:Tools & MCP", "settings-row:MCP servers"), "mcp-settings", "tool-row:fixture_mcp", usableTag = "tool-row:fixture_mcp"),
        Page("Skills", listOf("settings-row:Skills"), "skills-page", "skill-row-travel-planning", usableDesc = "Enable travel-planning"),
        Page("Memory", listOf("settings-row:Profiles", "settings-row:Profiles", "profile-open:default", "profile-memory"), "memory-page", "memory-document", usableTag = "memory-edit"),
        Page("Health", listOf("settings-row:Server", "settings-row:Health"), "server-health-hero", "server-health-metrics"),
        Page("Logs", listOf("settings-row:Server", "settings-row:Logs"), "server-log-lines", "server-log-search"),
        Page("Server updates", listOf("settings-row:Server", "settings-row:Server updates"), "server-update-hero", "server-update-line"),
        Page("Session checkpoints", listOf("settings-row:Server", "settings-row:Session checkpoints"), "server-checkpoint-metrics", "server-checkpoint-metrics"),
        Page("App updates", listOf("settings-row:App updates"), "update-screen", "update-screen"),
        Page("Licenses", listOf("settings-row:About & licenses"), "licenses-page", "license-hero"),
    )

    @Test fun everySettingsPageIsMeasured() {
        withVm { vm, fixture ->
            // First pass warms each page's own state; the recorded pass is the warm, slow-refresh one the owner lives in.
            fixture.restDelayMs = 0
            pages.forEach { measure(vm, it, "warmup") }
            fixture.restDelayMs = SLOW_NETWORK_MS
            pages.forEach { page ->
                // Start the quiet refresh this page will meet, so its controls are measured while it runs.
                when (page.name) {
                    "Skills" -> compose.runOnIdle { vm.phoneSettings.loadSkills(force = true) }
                    "Tools & commands" -> compose.runOnIdle { vm.load("native-tools", "/api/tools/toolsets", mapOf("profile" to "default"), force = true) }
                    "MCP servers" -> compose.runOnIdle { vm.load("native-mcp", "/api/mcp/servers", mapOf("profile" to "default"), force = true) }
                }
                results.put(measure(vm, page, "warm-slow"))
            }
            compose.runOnIdle { vm.tab("More"); vm.more("Skills") }
            compose.waitUntil(20_000) { shownTag("skill-row-travel-planning") }
            shot("settings-skills")
            assertTrue("No measurements", results.length() == pages.size)
        }
    }

    @Test fun sessionUsageIsMeasured() {
        withVm { vm, _ ->
            compose.runOnIdle { vm.more("Session usage") }
            val start = System.nanoTime()
            compose.waitUntil(20_000) { shownTag("usage-page") }
            val first = ms(start)
            compose.waitUntil(20_000) { shownTag("usage-hero") }
            results.put(JSONObject().put("path", "Session usage").put("mode", "warm-slow").put("first_tag", "usage-page")
                .put("content_tag", "usage-hero").put("first_ms", first).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            assertTrue("Session usage did not open", shownTag("usage-hero"))
        }
    }

    /** One profile's own page: the second tap, after the list, is where a switch used to sit behind its authorisation.
     *  The baseline has no saved detail, so this row is recorded for information and the after pass is the fix's proof. */
    @Test fun profileDetailIsMeasured() {
        withVm { vm, fixture ->
            // A first open leaves the detail cache this measurement reads on the next tap.
            compose.runOnIdle { vm.more("Profiles") }
            compose.waitUntil(20_000) { shownTag("profile-open:default") }
            compose.onNodeWithTag("profile-open:default").performClick()
            compose.waitUntil(20_000) { shownTag("profile-hero") }
            compose.runOnIdle { vm.phoneSettings.closeBot() }
            fixture.restDelayMs = SLOW_NETWORK_MS
            compose.runOnIdle { vm.more("Profiles") }
            compose.waitUntil(20_000) { shownTag("profile-open:default") }
            val start = System.nanoTime()
            compose.onNodeWithTag("profile-open:default").performClick()
            compose.waitUntil(20_000) { shownTag("profile-hero") }
            val first = ms(start)
            compose.waitUntil(20_000) { shownTag("profile-model") }
            results.put(JSONObject().put("path", "Profile detail").put("mode", "warm-slow").put("first_tag", "profile-hero")
                .put("content_tag", "profile-model").put("first_ms", first).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            assertTrue("Profile detail did not open", shownTag("profile-hero"))
        }
    }

    /** The Settings tab's own first frame: no earlier composition of this screen to hide the cost behind. */
    @Test fun settingsHubFirstFrameIsMeasured() {
        withVm(render = false) { vm, _ ->
            val start = System.nanoTime()
            compose.setContent { Screen(vm) }
            compose.waitUntil(20_000) { shownTag("settings-overview") }
            val first = ms(start)
            compose.waitUntil(20_000) { shownTag("settings-connection") }
            shot("settings-hub")
            results.put(JSONObject().put("path", "Settings hub").put("mode", "first-open").put("first_tag", "settings-overview")
                .put("content_tag", "settings-connection").put("first_ms", first).put("content_ms", ms(start)))
            assertTrue("Settings hub did not open", shownTag("settings-overview"))
        }
    }

    /** The chat composer's own menus: the model sheet, the approvals shield and the attach menu. */
    @Test fun composerActionsAreMeasured() {
        withVm(render = false) { vm, fixture ->
            fixture.restDelayMs = SLOW_NETWORK_MS
            compose.runOnIdle { flow<AppState>(vm, "_ui").value = flow<AppState>(vm, "_ui").value.copy(tab = "Chat") }
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { ChatScreen(vm) } } }
            compose.waitUntil(20_000) { shownTag("composer-model-effort") }
            compose.waitForIdle()
            var start = System.nanoTime()
            compose.onNodeWithTag("composer-model-effort").performClick()
            compose.waitUntil(20_000) { shownTag("catalog-sheet") }
            val modelFirst = ms(start)
            compose.waitUntil(20_000) { shownTag("model:fixture:synthetic-model") }
            results.put(JSONObject().put("path", "Composer model sheet").put("mode", "warm-slow").put("first_tag", "catalog-sheet")
                .put("content_tag", "model:fixture:synthetic-model").put("first_ms", modelFirst).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            compose.runOnIdle { vm.closeCatalog() }
            compose.waitUntil(20_000) { !shownTag("catalog-sheet") }
            // A saved approval answer from an earlier run is stale by definition; the menu must still be usable at once and re-check on the write.
            compose.runOnIdle { vm.storeData("picker:approvals", json("scope_key" to vm.client?.scope?.copy(session = "")?.key, "session_id" to "chat-a", "profile_mode" to "manual", "session_status_known" to true, "session_bypass" to false, "value" to "manual", "_stale" to true)) }
            start = System.nanoTime()
            compose.onNodeWithTag("composer-approvals").performClick()
            compose.waitUntil(20_000) { shownTag("approvals-menu") }
            val approvalsFirst = ms(start)
            compose.waitUntil(20_000) { enabledTag("approval-ask") }
            shot("composer-approvals")
            results.put(JSONObject().put("path", "Composer approvals menu").put("mode", "warm-slow-stale").put("first_tag", "approvals-menu")
                .put("content_tag", "approval-ask enabled").put("first_ms", approvalsFirst).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            compose.runOnIdle { vm.storeData("picker:approvals", JSONObject()) }
            start = System.nanoTime()
            compose.onNodeWithTag("composer-attach").performClick()
            compose.waitUntil(20_000) { shownTag("attach-menu") }
            results.put(JSONObject().put("path", "Composer attach menu").put("mode", "warm-slow").put("first_tag", "attach-menu")
                .put("content_tag", "attach-photos").put("first_ms", ms(start)).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            assertTrue("Composer actions did not open", shownTag("attach-menu"))
        }
    }

    /** The drawer acknowledges a tap, and a chat whose transcript was saved earlier draws from that copy while the network is slow. */
    @Test fun drawerAndCachedChatAreMeasured() {
        withVm(render = false) { vm, fixture ->
            val rows = listOf("chat-a", "chat-b").map { id -> fixture.sessions.getValue(id).getJSONObject("info").copy().put("session_id", id).put("id", id) }
            runBlocking { app.cache.writeSession(fixture.scope.session("chat-b"), fixture.snapshot("chat-b").toString()) }
            compose.runOnIdle {
                val shown = flow<AppState>(vm, "_ui").value
                flow<AppState>(vm, "_ui").value = shown.copy(tab = "Chat", sessions = rows, showSessions = false)
            }
            compose.setContent { NativeLocalization("en") { BeaconTheme(dark = true) { BeaconRoot(vm) } } }
            compose.waitUntil(20_000) { shownTag("navigation-toggle") }
            fixture.restDelayMs = SLOW_NETWORK_MS
            var start = System.nanoTime()
            compose.onNodeWithTag("navigation-toggle").performClick()
            compose.waitUntil(20_000) { vm.ui.value.showSessions }
            val ack = ms(start)
            results.put(JSONObject().put("path", "Drawer open").put("mode", "warm-slow").put("first_tag", "navigation-toggle")
                .put("content_tag", "navigation-menu").put("first_ms", ack).put("content_ms", ack).put("usable_ms", ack))
            start = System.nanoTime()
            compose.onNodeWithTag("history-row:chat-b").performClick()
            compose.waitUntil(20_000) { vm.chat.value.sessionId == "chat-b" }
            val chatAck = ms(start)
            compose.waitUntil(20_000) { shownTextPart("Keep the literal") }
            results.put(JSONObject().put("path", "Open cached chat").put("mode", "warm-slow").put("first_tag", "history-row:chat-b")
                .put("content_tag", "saved message").put("first_ms", chatAck).put("content_ms", ms(start)).put("usable_ms", ms(start)))
            runBlocking { app.cache.delete(fixture.scope.session("chat-b"), "session") }
            assertTrue("The saved chat did not draw", shownTextPart("Keep the literal"))
        }
    }

    private companion object { const val SLOW_NETWORK_MS = 800 }
}
