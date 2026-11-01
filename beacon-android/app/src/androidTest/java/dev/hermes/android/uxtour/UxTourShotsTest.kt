package dev.hermes.android.uxtour

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ShortText
import androidx.compose.material.icons.rounded.Balance
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
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
import dev.hermes.android.data.NativeBot
import dev.hermes.android.data.NativeBotSummary
import dev.hermes.android.data.NativeBotsState
import dev.hermes.android.data.NativeComponent
import dev.hermes.android.data.NativeServerState
import dev.hermes.android.data.NativeSkill
import dev.hermes.android.preferences
import dev.hermes.android.ui.NativeAdministration
import dev.hermes.android.ui.NativeBotSettings
import dev.hermes.android.ui.NativeCatalog
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NativeServerSettings
import dev.hermes.android.ui.NativeSkillsScreen
import dev.hermes.android.ui.NativeSkillsState
import dev.hermes.android.ui.ProactiveHealthChip
import dev.hermes.android.ui.ProactiveHealthState
import dev.hermes.android.ui.SidebarNavigation
import dev.hermes.android.ui.SpacesScreen
import dev.hermes.android.ui.TasksScreen
import dev.hermes.android.ui.isLargeText
import dev.hermes.android.ui.SettingsOption
import dev.hermes.android.ui.SettingsOptionCards
import dev.hermes.android.ui.SettingsSection
import dev.hermes.android.ui.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Review screenshots of the screens the ux-tour pass changed, each in dark, light, Arabic dark (a real right-to-left layout) and light at 1.3x text.
 * It draws the screens against the synthetic UI fixture (no Hermes, no real account), writes PNGs under the app's external files
 * `ux-tour-shots`, and restores every preference it touched. Run it with `--arg native_ui_probe=1` through device-test.py.
 */
class UxTourShotsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val args get() = InstrumentationRegistry.getArguments()
    private val shots by lazy { File(requireNotNull(app.getExternalFilesDir(null)), "ux-tour-shots").apply { mkdirs() } }

    private enum class Look(val key: String, val dark: Boolean, val arabic: Boolean, val fontScale: Float) {
        Dark("dark", true, false, 1f), Light("light", false, false, 1f), ArabicDark("ar-dark", true, true, 1f), LargeLight("large-light", false, false, 1.3f)
    }
    private val look = mutableStateOf(Look.Dark)

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

    /** The screen under the four looks. [content] is drawn inside the app's theme, language, text size and layout direction. */
    private fun setScreen(opening: Look = Look.Dark, content: @androidx.compose.runtime.Composable () -> Unit) {
        look.value = opening
        compose.setContent {
            val current = look.value
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, current.fontScale),
                LocalLayoutDirection provides if (current.arabic) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                NativeLocalization(if (current.arabic) "ar" else "en") {
                    BeaconTheme(dark = current.dark) { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().statusBarsPadding()) { content() } } }
                }
            }
        }
    }

    private fun shoot(screen: String, looks: List<Look> = Look.entries, settleMillis: Long = 0) {
        looks.forEach { next ->
            look.value = next
            compose.waitForIdle()
            // A dialog window follows a text-size or direction change a moment after the content does.
            if (settleMillis > 0) { Thread.sleep(settleMillis); compose.waitForIdle() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(400, 4000)
            val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()) { "No screenshot" }
            File(shots, "$screen-${next.key}.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
        }
    }

    private fun await(condition: () -> Boolean) = compose.waitUntil(15_000, condition)

    /** A view model over the synthetic fixture client, set up and torn down as the other UI probes do. */
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

    // ---- screens that need the app's own state ----

    @Test fun schedules() = withFixture { vm, fixture ->
        // A Hermes zone whose clock is not the phone's, so the card has something to convert (and one rule that cannot move).
        val now = Instant.now()
        val phone = ZoneId.systemDefault()
        val hermes = listOf("Pacific/Auckland", "Europe/Lisbon").map { ZoneId.of(it) }.first { it.rules.getOffset(now) != phone.rules.getOffset(now) }
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
        shoot("schedules")
    }

    @Test fun files() = withFixture { vm, _ ->
        setScreen { SpacesScreen(vm) }
        await { vm.data.value["files"] != null }
        Thread.sleep(1500)
        compose.waitForIdle()
        val current = requireNotNull(vm.data.value["files"])
        val longPath = "D:\\Work\\Clients\\Acme\\Reports\\2026\\Q4\\final"
        val withPath = JSONObject(current.toString()).put("path", longPath)
        withPath.remove("_error")
        vm.storeData("files", withPath)
        compose.waitForIdle()
        shoot("files")
    }

    @Test fun drawer() = withFixture { vm, _ ->
        setScreen { Box(Modifier.width(340.dp).fillMaxHeight()) { SidebarNavigation(vm) } }
        compose.waitForIdle()
        shoot("drawer")
    }

    @Test fun usage() = withFixture { vm, _ ->
        setScreen { NativeAdministration(vm, "Session usage") }
        val key = "native-usage:${vm.ui.value.profile}:chat-a"
        await { vm.data.value[key] != null }
        // Hermes counted nothing for a chat that holds replies.
        vm.storeData(key, json("input_tokens" to 0, "output_tokens" to 0, "context_window" to 32000, "context_used" to 4200))
        compose.waitForIdle()
        shoot("usage-unreported")
        vm.storeData(key, json("input_tokens" to 18400, "output_tokens" to 2300, "context_window" to 32000, "context_used" to 4200))
        compose.waitForIdle()
        shoot("usage-figures")
    }

    @Test fun commands() = withFixture { vm, _ ->
        setScreen { NativeAdministration(vm, "Commands") }
        await { vm.data.value["slash:catalog"] != null }
        vm.storeData("slash:catalog", json("commands" to JSONArray(listOf(
            json("name" to "/new", "description" to "Start a new session"), json("name" to "/clear", "description" to "Clear the screen"), json("name" to "/history", "description" to "Show past chats"),
            json("name" to "/status", "description" to "Show the current state"), json("name" to "/standup", "description" to "Post the daily standup note")))))
        compose.waitForIdle()
        shoot("commands")
    }

    @Test fun modelPicker() = withFixture { vm, _ ->
        compose.runOnIdle { vm.openCatalog("Model & provider") }
        setScreen { NativeCatalog(vm) }
        await { vm.data.value["picker:models"] != null && vm.data.value["picker:reasoning"] != null }
        // Reasoning still loading: the label and four chip-shaped blocks.
        flow<Map<String, JSONObject>>(vm, "_data").value = vm.data.value - "picker:reasoning"
        compose.waitForIdle()
        shoot("model-picker-reasoning-loading", listOf(Look.Dark, Look.Light, Look.ArabicDark), settleMillis = 1200)
    }

    @Test fun modelPickerLarge() = withFixture { vm, _ ->
        compose.runOnIdle { vm.openCatalog("Model & provider") }
        setScreen(Look.LargeLight) { NativeCatalog(vm) }
        await { vm.data.value["picker:models"] != null && vm.data.value["picker:reasoning"] != null }
        flow<Map<String, JSONObject>>(vm, "_data").value = vm.data.value - "picker:reasoning"
        compose.waitForIdle()
        shoot("model-picker-reasoning-loading", listOf(Look.LargeLight), settleMillis = 1200)
    }

    // ---- screens that are drawn from plain state ----

    @Test fun serverHealth() {
        val state = NativeServerState(overall = "ok", version = "0.14.2", gatewayState = "running", activeAgents = 1, components = listOf(
            NativeComponent("gateway", "ok", "running"), NativeComponent("dashboard", "ok", ""), NativeComponent("storage", "ok", ""), NativeComponent("platforms", "ok", "1 / 1 connected")))
        setScreen { NativeServerSettings("Health", state, {}, {}, {}, {}) }
        shoot("server-health")
    }

    @Test fun serverLogs() {
        val state = NativeServerState(logFile = "errors", logs = listOf(
            "2026-10-30 20:30:15,523 WARNING gateway: Skill apple-notes is missing a key. Add it to set them up.",
            "2026-10-30 20:31:02,114 ERROR agent: Request to the provider timed out after 30 s.",
            "2026-10-30 20:31:09,880 INFO gateway: Reconnected. All platforms are connected."))
        setScreen { NativeServerSettings("Logs", state, {}, {}, {}, {}) }
        shoot("server-logs")
    }

    @Test fun serverUpdates() {
        val state = NativeServerState(update = json("update_available" to false, "can_apply" to false, "current_version" to "0.14.2", "message" to "You're on the latest version."))
        setScreen { NativeServerSettings("Server updates", state, {}, {}, {}, {}) }
        shoot("server-updates")
    }

    @Test fun skills() {
        val state = NativeSkillsState(profile = "default", skills = listOf(
            NativeSkill("apple-notes", "Read and write Apple Notes", "apps", true, "bundled"),
            NativeSkill("github", "Work with repositories and pull requests", "apps", false, "hub", requirements = listOf("GITHUB_TOKEN")),
            NativeSkill("summarize", "Summarize pages and documents", "text", true, "bundled", essential = true)))
        setScreen { NativeSkillsScreen(state, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}) }
        shoot("skills")
    }

    @Test fun profiles() {
        val list = NativeBotsState(profiles = listOf(NativeBotSummary("default", "Default", "Daily help"), NativeBotSummary("work", "Work", "")))
        setScreen { NativeBotSettings(list, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}, activeProfile = "default") }
        shoot("profiles")
    }

    @Test fun profileHero() {
        val one = NativeBotsState(profiles = listOf(NativeBotSummary("work", "Work", "")), selected = NativeBot("work", "Work", "", "", "openai", "gpt", "r1"))
        setScreen { NativeBotSettings(one, {}, {}, {}, { _, _ -> }, {}, {}, { _, _ -> }, {}, activeProfile = "default") }
        shoot("profile-without-description")
    }

    @Test fun personality() {
        // The personality card as the voice page builds it: three cards side by side, one column from 1.3x text.
        setScreen {
            Column(Modifier.padding(16.dp)) {
                SettingsSection(uiText("Personality")) {
                    SettingsOptionCards(listOf(
                        SettingsOption("balanced", uiText("Balanced"), uiText("Everyday"), Icons.Rounded.Balance),
                        SettingsOption("concise", uiText("Concise"), uiText("Short answers"), Icons.AutoMirrored.Rounded.ShortText),
                        SettingsOption("warm", uiText("Warm"), uiText("Friendly"), Icons.Rounded.Favorite)), "balanced", {},
                        columns = if (isLargeText(LocalDensity.current.fontScale)) 1 else 3)
                }
            }
        }
        shoot("personality")
    }

    @Test fun watchStatus() {
        setScreen {
            Column(Modifier.padding(16.dp)) {
                SettingsSection(uiText("Assistant")) {
                    Row(Modifier.padding(16.dp)) { Column { Text(uiText("Watch for me")); ProactiveHealthChip(ProactiveHealthState.NotYet) } }
                }
            }
        }
        shoot("watch-status")
    }
}
