package dev.hermes.android.ui.vault

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.platform.testTag
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
import dev.hermes.android.preferences
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.ChatState
import dev.hermes.android.Message
import dev.hermes.android.NativeExperienceFixture
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.ui.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The real app on Hermes' synthetic native protocol (NativeExperienceFixture, which speaks `vault.*` and the vault prompts as the contracts
 * define them). No socket, service, model or sensor. [chat] puts the whole app on screen with a chat open, so prompts can reach it; otherwise only Settings.
 */
internal class VaultHarness(private val compose: AndroidComposeTestRule<*, ComponentActivity>) {
    private val app get() = ApplicationProvider.getApplicationContext<BeaconApplication>()
    private val args get() = InstrumentationRegistry.getArguments()

    @Suppress("UNCHECKED_CAST")
    fun <T> flow(vm: BeaconViewModel, name: String) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>
    private fun field(vm: BeaconViewModel, name: String, value: Any?) { BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value) }

    fun shell(command: String): String = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText().trim() }

    fun run(chat: Boolean = false, dark: Boolean = true, configure: NativeExperienceFixture.() -> Unit = {}, block: (BeaconViewModel, NativeExperienceFixture) -> Unit) {
        check(args.getString("native_ui_probe") == "1") { "Explicit synthetic UI probe flag required" }
        check(!app.calls.state.value.active && app.calls.service == null) { "Never replace an active real call" }
        val prior = runBlocking { app.preferences.data.first() }
        runBlocking { app.preferences.edit { it[stringPreferencesKey("origin")] = "" } }
        val priorAnimatorScale = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        val fixture = NativeExperienceFixture().apply { profileIsolationBots = true; notificationProfiles = listOf("default", "coder", "academic"); configure() }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        val profiles = listOf("default", "coder", "academic").map { fixture.scope.copy(profile = it) }
        runBlocking {
            app.auth.persist(fixture.client.auth)
            app.cache.delete(fixture.scope.copy(profile = "", session = ""), "screens")
            profiles.forEach { owner ->
                listOf("phone-editors", "read-invalidated-at", "new-chat-shell", "new-chat-attempt", "initial-draft", "selected-session", "bot-mode-return", "beacon-session", "beacon-opening").forEach { app.cache.delete(owner, it) }
                (fixture.sessions.keys + (1..24).map { "new-$it" }).forEach { id -> listOf("pending", "draft", "session", "client-created-shell", "side-question", "running-turn").forEach { app.cache.delete(owner.session(id), it) } }
            }
        }
        try {
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java] }
            compose.runOnIdle {
                field(vm, "originGeneration", 100)
                field(vm, "client", fixture.client)
                field(vm, "profileSwitchClientFactoryForTest", { auth: dev.hermes.android.data.AuthContext -> fixture.freshClientFor(auth, app.auth) })
                field(vm, "sourceScope", fixture.scope.session("chat-a"))
                app.calls.ownerScope = fixture.scope
                flow<AppState>(vm, "_ui").value = AppState(origin = fixture.scope.origin, authenticated = true, owner = fixture.scope.owner, profile = "default", connected = true,
                    restoringSession = false, bootstrap = json("voice" to json("available" to false)), sessions = fixture.sessions.keys.sorted().map { fixture.snapshot(it) })
                val snapshot = fixture.snapshot("chat-a").apply { remove("runtime_session_id") }
                flow<ChatState>(vm, "_chat").value = ChatState(sessionId = "chat-a", title = "Travel planning", raw = snapshot,
                    messages = snapshot.rows("messages").mapIndexed { index, row -> Message.from(row, index, "chat-a") })
                vm.storeData("profile-settings", json("render_user_markdown" to true, "show_thinking" to true, "send_key" to "enter"))
                if (!chat) vm.more("Profiles")
            }
            compose.setContent {
                val settings by vm.data.collectAsState()
                val prefs by vm.preferences.collectAsState()
                CompositionLocalProvider(LocalBeaconSettings provides (settings["profile-settings"] ?: JSONObject()), LocalAssistantName provides prefs.assistantName) {
                    NativeLocalization("en") { BeaconTheme(dark = dark) {
                        if (chat) NativeWindowLayout(compose.activity) { Box(Modifier.fillMaxSize().testTag("experience-viewport")) { BeaconRoot(vm) } } else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) { MoreScreen(vm) }
                    } }
                }
            }
            block(vm, fixture)
        } finally {
            compose.runOnUiThread { store.clear(); fixture.client.closeNative(); app.calls.ownerScope = null }
            runBlocking {
                profiles.forEach { app.cache.delete(it, "phone-editors") }
                app.cache.delete(fixture.scope.copy(profile = "", session = ""), "screens")
                app.auth.remove(fixture.scope.origin); app.preferences.updateData { prior }
            }
            shell(if (priorAnimatorScale == "null" || priorAnimatorScale.isBlank()) "settings delete global animator_duration_scale" else "settings put global animator_duration_scale $priorAnimatorScale")
        }
    }
}
