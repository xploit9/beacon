package dev.hermes.android

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.core.string
import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.NativeBearerRecord
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NotificationSettings
import dev.hermes.android.ui.SetupWizardScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class KeepConnectedUiTest {
    @get:Rule val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> flow(vm: CypherViewModel, name: String) =
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>

    private fun field(vm: CypherViewModel, name: String, value: Any?) {
        CypherViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
    }

    private fun restoreSharedPreferences(prefs: android.content.SharedPreferences, values: Map<String, *>) {
        val edit = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value)
            is Boolean -> edit.putBoolean(key, value)
            is Int -> edit.putInt(key, value)
            is Long -> edit.putLong(key, value)
            is Float -> edit.putFloat(key, value)
            is Set<*> -> edit.putStringSet(key, value.filterIsInstance<String>().toSet())
        } }
        check(edit.commit())
    }

    @Test fun setupDefaultsKeepConnectedOnAndPassesTheOwnerChoice() {
        var selected: Boolean? = null
        compose.setContent {
            NativeLocalization("en") { CypherTheme(dark = true) {
                SetupWizardScreen(
                    state = AppState(origin = "https://hermes.example", authenticated = true, setupWizard = true, setupStep = "done", restoringSession = false),
                    onProbe = {}, onSignIn = {}, onAdvancePermission = {}, onFinish = { selected = it }, onReopen = {}, onCancel = {},
                )
            } }
        }

        compose.onNodeWithTag("setup-keep-connected").assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("setup-finish").performClick()
        assertEquals(false, selected)
    }

    @Test fun enablingPollsFixtureCallsAndReusesTheCypherIncomingCallPath() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
        val fixture = NativeExperienceFixture()
        val scope = fixture.scope
        val oldAuth = app.auth.read(scope.origin)
        val dataBefore = app.preferences.data.first()
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        val keepBefore = keepPrefs.all.toMap()
        val pushPrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val priorSources = mutableMapOf<String, String?>()
        val originKey = stringPreferencesKey("origin")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val setupStepKey = stringPreferencesKey("setup_step")
        val beforeVolume = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_RING)
        val audio = app.getSystemService(AudioManager::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        val store = ViewModelStore()
        var vmClient: dev.hermes.android.data.CypherClient? = null
        val auth = AuthContext(
            scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
        )
        val testEvents = mutableListOf<org.json.JSONObject>()
        lateinit var vm: CypherViewModel
        KeepConnectedService.setEnabled(app, null, false)
        KeepConnectedService.clientFactoryForTest = { current, authStore -> fixture.clientFor(current, authStore) }
        audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
        app.auth.write(auth)
        app.preferences.edit { values ->
            values[originKey] = ""
            values[setupCompleteKey] = true
            values[setupActiveKey] = false
            values[setupStepKey] = "done"
        }
        try {
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            vmClient = fixture.clientFor(auth, app.auth)
            compose.runOnUiThread {
                field(vm, "client", vmClient)
                field(vm, "preparedXara", scope to "chat-b")
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, authenticated = true, connected = true, owner = scope.owner, profile = scope.profile, setupComplete = true, restoringSession = false)
                app.calls.ownerScope = scope
                app.calls.uiVisible = false
            }
            compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) { NotificationSettings(vm) } } }

            val answerEvent = fixture.emitIncomingCall(conversationId = "chat-b")
            testEvents += answerEvent
            val answerSource = answerEvent.getString("source_ref")
            priorSources["source:${answerSource.digest()}"] = pushPrefs.getString("source:${answerSource.digest()}", null)
            compose.onNodeWithTag("keep-connected-toggle").performClick()
            compose.waitUntil(15_000) {
                KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED &&
                    app.push.ringingInvitation(answerSource, answerEvent.getString("event_id")) != null
            }
            assertTrue(fixture.notificationWaits.contains(25L))
            val answerInvite = app.push.ringingInvitation(answerSource, answerEvent.getString("event_id"))!!
            assertTrue(manager.activeNotifications.any { it.tag == answerInvite.key && it.id == 2 })

            compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
                IncomingCallSheet(vm, requestingPermission = false) { source, event -> vm.handlePush(source, event, "answer") }
            } } }
            compose.onNodeWithTag("xara-incoming-answer").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { vm.ui.value.tab == "Xara" && vm.chat.value.sessionId == "chat-b" }
            assertEquals("chat-b", vm.chat.value.sessionId)
            assertFalse(app.calls.state.value.active)
            assertTrue(fixture.requests.any { it.string("path") == "/api/plugins/cypher/mobile/callbacks/${answerEvent.getString("event_id")}/answer" })

            val declineEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += declineEvent
            val declineSource = declineEvent.getString("source_ref")
            priorSources["source:${declineSource.digest()}"] = pushPrefs.getString("source:${declineSource.digest()}", null)
            compose.waitUntil(10_000) { app.push.ringingInvitation(declineSource, declineEvent.getString("event_id")) != null }
            compose.onNodeWithTag("xara-incoming-decline").performClick()
            compose.waitUntil(10_000) { fixture.incomingNotification?.optString("event_id") == declineEvent.getString("event_id") && fixture.incomingNotification?.optString("state") == "declined" }

            val expiryEvent = fixture.emitIncomingCall(conversationId = "chat-a", expiresInSeconds = 3)
            testEvents += expiryEvent
            val expirySource = expiryEvent.getString("source_ref")
            priorSources["source:${expirySource.digest()}"] = pushPrefs.getString("source:${expirySource.digest()}", null)
            compose.waitUntil(10_000) { app.push.missedInvitation(expirySource, expiryEvent.getString("event_id")) != null }
        } finally {
            KeepConnectedService.setEnabled(app, null, false)
            runCatching { compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4082 } } }
            testEvents.forEach { event ->
                val source = event.optString("source_ref")
                val id = event.optString("event_id")
                app.push.discardInvitation(source, id)
                app.push.removeKeepConnectedCallback(source, id)
                dev.hermes.android.voice.IncomingRingtoneService.silence(source, id)
            }
            compose.runOnUiThread { store.clear(); app.calls.hangup(); app.calls.ownerScope = null; app.calls.uiVisible = false }
            vmClient?.closeNative()
            KeepConnectedService.clientFactoryForTest = null
            val sourceEdit = pushPrefs.edit()
            priorSources.forEach { (key, value) -> if (value == null) sourceEdit.remove(key) else sourceEdit.putString(key, value) }
            check(sourceEdit.commit())
            if (oldAuth.owner.isBlank() && oldAuth.bearer == null && oldAuth.cookies.isEmpty()) app.auth.remove(scope.origin) else app.auth.write(oldAuth)
            app.preferences.edit { values ->
                dataBefore[originKey]?.let { values[originKey] = it } ?: values.remove(originKey)
                dataBefore[setupCompleteKey]?.let { values[setupCompleteKey] = it } ?: values.remove(setupCompleteKey)
                dataBefore[setupActiveKey]?.let { values[setupActiveKey] = it } ?: values.remove(setupActiveKey)
                dataBefore[setupStepKey]?.let { values[setupStepKey] = it } ?: values.remove(setupStepKey)
            }
            restoreSharedPreferences(keepPrefs, keepBefore)
            audio.setStreamVolume(AudioManager.STREAM_RING, beforeVolume, 0)
            if (keepBefore[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
        }
    }
}
