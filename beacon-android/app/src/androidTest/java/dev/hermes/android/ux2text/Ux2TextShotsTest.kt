package dev.hermes.android.ux2text

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.*
import dev.hermes.android.data.NativeServerState
import dev.hermes.android.preferences
import dev.hermes.android.ui.NativeAdministration
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NativeServerSettings
import dev.hermes.android.ui.SpacesScreen
import dev.hermes.android.ui.TasksScreen
import dev.hermes.android.ui.normalized
import dev.hermes.android.ui.scheduleZoneCity
import dev.hermes.android.ui.scheduleZoneNamer
import dev.hermes.android.ui.serverLogFiles
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * UX-49, UX-51 and N-8 on a device: the Files trail with a deep path, the Logs filters, the Commands list and the Schedules cards,
 * each in dark, light, Arabic dark (a real right-to-left layout) and light at 1.3x text. It draws the screens against the synthetic UI fixture
 * (no Hermes, no real account), checks what each look must show, writes PNGs under the app's external files `ux2-text-shots`, and
 * restores every preference it touched. A check that fails is reported after the screenshots are taken. Run it through device-test.py
 * with `--arg native_ui_probe=1`.
 */
class Ux2TextShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val shots by lazy { File(requireNotNull(app.getExternalFilesDir(null)), "ux2-text-shots").apply { mkdirs() } }

    private enum class Look(val key: String, val dark: Boolean, val arabic: Boolean, val fontScale: Float) {
        Dark("dark", true, false, 1f), Light("light", false, false, 1f), ArabicDark("ar-dark", true, true, 1f), LargeLight("large-light", false, false, 1.3f)
    }
    private val look = mutableStateOf(Look.Dark)
    private val problems = mutableListOf<String>()

    private var priorAnimatorScale = "null"
    private fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    @Before fun prepare() {
        check(args.getString("native_ui_probe") == "1") { "Explicit synthetic UI probe flag required" }
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        // The Arabic catalog is read before the first frame, so a shot never catches the English fallback.
        runBlocking { app.readTranslations() }
        // Motion follows the phone's "Remove animations" setting; still frames let the test settle (restored afterwards).
        priorAnimatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
    }

    @After fun restoreAnimations() {
        shell(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
    }

    // ---- helpers ----

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) { BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value) }

    private fun setScreen(opening: Look = Look.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        look.value = opening
        compose.setContent {
            val current = look.value
            val density = LocalDensity.current
            // The app's language is the configuration's first locale (the tour sets it with `cmd locale set-app-locales`), so the look sets that too.
            val base = LocalConfiguration.current
            val configuration = remember(base, current.arabic) { Configuration(base).apply { setLocales(LocaleList(Locale.forLanguageTag(if (current.arabic) "ar" else "en"))) } }
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalDensity provides Density(density.density, current.fontScale),
                LocalLayoutDirection provides if (current.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NativeLocalization(if (current.arabic) "ar" else "en") {
                    BeaconTheme(dark = current.dark) { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().statusBarsPadding()) { content() } } }
                }
            }
        }
    }

    /** Takes each look's screenshot after [verify] ran for it; what [verify] finds wrong is kept and reported by [finish]. */
    private fun shoot(screen: String, looks: List<Look> = Look.entries, verify: (Look) -> Unit = {}) {
        looks.forEach { next ->
            look.value = next
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 4000)
            runCatching { verify(next) }.onFailure { problems += "$screen ${next.key}: ${it.message ?: it}" }
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
            File(shots, "$screen-${next.key}.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun finish() { assertTrue("Checks that failed:\n" + problems.joinToString("\n"), problems.isEmpty()) }
    private fun expect(condition: Boolean, what: () -> String) { if (!condition) throw AssertionError(what()) }
    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)
    private fun exists(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun shown(text: String) = compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    private fun boundsOf(tag: String) = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
    private val rootWidth get() = compose.onRoot().fetchSemanticsNode().size.width
    private val density get() = app.resources.displayMetrics.density
    /** The text the Arabic catalog holds for an English text. */
    private fun arabic(text: String) = app.readTranslations().getJSONObject("ar").optString(normalized(text)).also { check(it.isNotBlank()) { "No Arabic for \"$text\"" } }

    private fun withFixture(block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        val originKey = stringPreferencesKey("origin")
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[originKey] = "" } }
        val store = ViewModelStore()
        val fixture = NativeExperienceFixture()
        runBlocking { app.auth.persist(fixture.client.auth) }
        val profiles = listOf(fixture.scope, fixture.scope.copy(profile = "coder"))
        runBlocking { profiles.forEach { owner -> listOf("new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session").forEach { app.cache.delete(owner, it) } } }
        runBlocking { app.cache.delete(fixture.scope, "read-invalidated-at"); app.cache.delete(fixture.scope.copy(profile = "", session = ""), "screens") }
        lateinit var vm: BeaconViewModel
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, authenticated = true, owner = fixture.scope.owner, connected = true, restoringSession = false,
                    bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) })
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id") }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking { app.preferences.updateData { prior } }
            runBlocking { app.auth.remove(fixture.scope.origin) }
        }
    }

    private fun iso(at: Instant, zone: ZoneId) = at.atZone(zone).toOffsetDateTime().toString()

    // ---- Files: a deep path ----

    @Test fun filesDeepPath() = withFixture { vm, _ ->
        setScreen { SpacesScreen(vm) }
        await { vm.data.value["files"] != null }
        Thread.sleep(1500)
        compose.waitForIdle()
        val withPath = JSONObject(requireNotNull(vm.data.value["files"]).toString()).put("path", "D:\\Shared\\Team\\Documents\\Atelier relaunch")
        withPath.remove("_error")
        vm.storeData("files", withPath)
        compose.waitForIdle()
        shoot("files-deep") { current ->
            // Five levels: D:, Shared, Team, Documents, Atelier relaunch (the last chip is index 4).
            expect(exists("files-path") && exists("files-crumb:4"), { "the trail or its last level is missing" })
            expect(exists("files-crumb-more"), { "a deep trail has no \"…\" menu" })
            val chips = listOf("files-crumb-home", "files-crumb-more") + (0..4).map { "files-crumb:$it" }
            val sliced = chips.filter { tag -> boundsOf(tag)?.let { it.left < -1f || it.right > rootWidth + 1f } == true }
            expect(sliced.isEmpty(), { "chips cut by the screen edge: $sliced" })
            // Home sits behind the menu only when the whole trail does not fit; once folded the trail starts at the leading edge.
            val more = requireNotNull(boundsOf("files-crumb-more"))
            if (!exists("files-crumb-home")) {
                val gap = (if (current.arabic) rootWidth - more.right else more.left) / density
                expect(gap in 4f..40f, { "the \"…\" chip is $gap dp from the leading edge, not at it" })
            }
        }
        // The menu holds Home first, then the levels above the ones shown.
        look.value = Look.Dark
        compose.waitForIdle()
        compose.onNodeWithTag("files-crumb-more", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        shoot("files-deep-menu", listOf(Look.Dark)) { expect(exists("files-crumb-home-menu"), { "Home is not in the \"…\" menu" }) }
        androidx.test.espresso.Espresso.pressBack()
        finish()
    }

    // ---- Logs: the filters ----

    @Test fun logs() {
        val state = NativeServerState(logFile = "errors", logs = listOf(
            "2026-10-30 20:30:15,523 WARNING gateway: Skill example-notes is missing a key. Add it to set them up.",
            "2026-10-30 20:31:02,114 ERROR agent: Request to the provider timed out after 30 s.",
            "2026-10-30 20:31:09,880 INFO gateway: Reconnected. All platforms are connected."))
        setScreen { NativeServerSettings("Logs", state, {}, {}, {}, {}) }
        shoot("logs") { current ->
            val large = current.fontScale >= 1.3f
            expect(exists("server-log-filters-wrap") == large, { "the filters ${if (large) "do not wrap" else "wrap"} at ${current.fontScale}x" })
            expect(exists("server-log-filters-scroll") == !large, { "the filters ${if (large) "still scroll" else "do not scroll"} at ${current.fontScale}x" })
            val chips = compose.onNodeWithTag(if (large) "server-log-filters-wrap" else "server-log-filters-scroll", useUnmergedTree = true).onChildren().fetchSemanticsNodes()
            expect(chips.size == serverLogFiles.size, { "found ${chips.size} filter chips, expected ${serverLogFiles.size}" })
            if (large) {
                val cut = chips.filter { it.boundsInRoot.left < -1f || it.boundsInRoot.right > rootWidth + 1f }
                expect(cut.isEmpty(), { "${cut.size} wrapped chips are cut by the screen edge" })
            }
            // Not asserted: where the first scrolling chip starts (the row runs to both screen edges with 16 dp of content padding inside its scroll). The
            // semantics bounds of a chip inside the bleeding row read 0 dp from the edge on the first device run while the frame shows it level with the
            // search card at 16 dp, so that is judged from the screenshot.
        }
        finish()
    }

    // ---- Commands: built-in texts, translated ----

    @Test fun commands() = withFixture { vm, _ ->
        setScreen { NativeAdministration(vm, "Commands") }
        await { vm.data.value["slash:catalog"] != null }
        vm.storeData("slash:catalog", json("commands" to JSONArray(listOf(
            json("name" to "/prompt", "description" to "Compose your next prompt in \$EDITOR (markdown), then send it (usage: /prompt)"),
            json("name" to "/handoff", "description" to "Hand off this session to a messaging platform (Telegram, Discord, etc.)"),
            json("name" to "/worktree", "description" to "Show, list, create, or prune isolated git worktrees"),
            json("name" to "/rollback", "description" to "List or restore filesystem checkpoints (restores keep your hand-edits; --all overrides)"),
            json("name" to "/kanban", "description" to "Multi-profile collaboration board (tasks, links, comments)"),
            json("name" to "/standup", "description" to "Post the daily standup note")))))
        compose.waitForIdle()
        shoot("commands") { current ->
            val prompt = if (current.arabic) arabic("Write a prompt in your editor") else "Write a prompt in your editor"
            val handoff = if (current.arabic) arabic("Hand off to a messaging app") else "Hand off to a messaging app"
            expect(shown(prompt), { "\"$prompt\" is not shown" })
            expect(shown(handoff), { "\"$handoff\" is not shown" })
            // Hermes' own words for a built-in never reach the row; a custom command keeps its own.
            expect(!shown("Hand off this session to a messaging platform (Telegram, Discord, etc.)") && compose.onAllNodes(hasText("\$EDITOR", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), { "a built-in still shows Hermes' words" })
            expect(shown("Post the daily standup note"), { "the custom command lost its description" })
        }
        finish()
    }

    // ---- Schedules: the zone named in the app language ----

    @Test fun schedules() = withFixture { vm, fixture ->
        val now = Instant.now()
        val phone = ZoneId.systemDefault()
        val hermes = listOf("Europe/Lisbon", "Asia/Dubai", "Pacific/Auckland").map { ZoneId.of(it) }.first { it.rules.getOffset(now) != phone.rules.getOffset(now) }
        val tomorrowEight = now.atZone(hermes).plusDays(1).withHour(8).withMinute(0).withSecond(0).withNano(0).toInstant()
        val mondayEight = now.atZone(hermes).with(TemporalAdjusters.next(DayOfWeek.MONDAY)).withHour(8).withMinute(0).withSecond(0).withNano(0).toInstant()
        fun job(id: String, name: String, expr: String, next: Instant, last: Instant) = json("id" to id, "name" to name, "prompt" to "Summarize the synthetic notes.", "deliver" to "local", "enabled" to true,
            "state" to "scheduled", "schedule" to json("kind" to "cron", "expr" to expr, "display" to expr), "schedule_display" to expr, "next_run_at" to iso(next, hermes),
            "last_run_at" to iso(last, hermes), "scheduler_heartbeat_age_s" to 8)
        fixture.jobs.clear()
        fixture.jobs["ux-daily"] = job("ux-daily", "Morning briefing", "0 8 * * *", tomorrowEight, tomorrowEight.minus(Duration.ofDays(1)))
        fixture.jobs["ux-weekly"] = job("ux-weekly", "Weekly review", "0 8 * * 1", mondayEight, mondayEight.minus(Duration.ofDays(7)))
        fixture.jobs["ux-window"] = job("ux-window", "Inbox check", "*/15 7-21 * * *", now.plus(Duration.ofMinutes(20)), now.minus(Duration.ofMinutes(10)))
        setScreen { TasksScreen(vm) }
        await { vm.data.value["crons"]?.rows("jobs")?.size == 3 && vm.data.value["task-capabilities"] != null }
        vm.storeData("task-capabilities", json("timezone" to hermes.id))
        compose.waitForIdle()
        shoot("schedules") { current ->
            val named = scheduleZoneNamer(Locale.forLanguageTag(if (current.arabic) "ar" else "en"))(hermes.id)
            if (current.arabic) expect(named != scheduleZoneCity(hermes.id), { "this device has no Arabic name for ${hermes.id}; got \"$named\"" })
            // Hermes' clock on the second line names the zone the way the app language spells it.
            val line = compose.onNodeWithTag("schedule-hermes-clock:ux-daily", useUnmergedTree = true)
            line.assert(hasText(named, substring = true))
        }
        finish()
    }
}
