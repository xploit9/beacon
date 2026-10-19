package dev.hermes.android

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.compose.runtime.mutableStateOf
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
import dev.hermes.android.data.VoiceKeyStore
import dev.hermes.android.push.KeepConnectedService
import dev.hermes.android.push.persistPushBindings
import dev.hermes.android.push.receivePush
import dev.hermes.android.ui.NativeLocalization
import dev.hermes.android.ui.NotificationSettings
import dev.hermes.android.ui.SetupWizardScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.json.JSONObject

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

    @Test fun disabledStartStopsWithoutCrashingTheApp() {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        val keepBefore = keepPrefs.all.toMap()
        try {
            check(keepPrefs.edit().putBoolean(KeepConnectedService.KEY_ENABLED, false).commit())
            // Turning Keep connected off between the start request and onStartCommand must still promote; stopping first crashes the app.
            androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, KeepConnectedService::class.java))
            // The crash, if any, lands right after onStartCommand; this wait lets it surface before the run reports.
            Thread.sleep(1500)
            assertTrue(app.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == 4082 })
        } finally {
            restoreSharedPreferences(keepPrefs, keepBefore)
            if (keepBefore[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
        }
    }

    @Test fun enablingPollsFixtureCallsAndReusesTheCypherIncomingCallPath() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val hadNotifications = android.os.Build.VERSION.SDK_INT < 33 || app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val hadRecordAudio = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val fixture = NativeExperienceFixture()
        val scope = fixture.scope
        val oldAuth = app.auth.read(scope.origin)
        val dataBefore = app.preferences.data.first()
        val keepPrefs = app.getSharedPreferences(KeepConnectedService.PREFS, Context.MODE_PRIVATE)
        // A killed run leaves its fixture cursor ahead of this fixture's first event, so the poll would never deliver it.
        val fixtureCursors = fixture.notificationProfiles.map { "cursor:${"${scope.origin}\u0000${scope.owner}\u0000$it".digest()}" }
        val keepBefore = keepPrefs.all.toMap() - fixtureCursors.toSet()
        val pushPrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
        val voicePrefs = app.getSharedPreferences("voice-private", Context.MODE_PRIVATE)
        val voiceBefore = voicePrefs.all.toMap()
        val priorSources = mutableMapOf<String, String?>()
        fun seedExistingFcmBinding(source: String) {
            val key = "source:${source.digest()}"
            priorSources[key] = pushPrefs.getString(key, null)
            val value = JSONObject().put("source", source).put("origin", scope.origin).put("owner", scope.owner)
                .put("profile", scope.profile).put("backend", "hermes-dashboard").put("target", "fixture-target")
            val encoded = android.util.Base64.encodeToString(
                app.cipher.encrypt(value.toString().toByteArray(), key), android.util.Base64.NO_WRAP,
            )
            persistPushBindings(pushPrefs, mapOf(key to encoded))
        }
        val originKey = stringPreferencesKey("origin")
        val setupCompleteKey = booleanPreferencesKey("setup_complete")
        val setupActiveKey = booleanPreferencesKey("setup_active")
        val setupStepKey = stringPreferencesKey("setup_step")
        val beforeVolume = app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_RING)
        val audio = app.getSystemService(AudioManager::class.java)
        val manager = app.getSystemService(NotificationManager::class.java)
        val previousSignInNotification = runCatching { manager.activeNotifications.firstOrNull { it.id == 4083 } }.getOrNull()
        val store = ViewModelStore()
        var vmClient: dev.hermes.android.data.CypherClient? = null
        val auth = AuthContext(
            scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard",
            bearer = NativeBearerRecord("fixture-access", "fixture-refresh", System.currentTimeMillis() + 3_600_000, scope.origin, scope.owner, scope.profile, "fixture"),
        )
        val testEvents = mutableListOf<org.json.JSONObject>()
        lateinit var vm: CypherViewModel
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
            uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO)
            KeepConnectedService.setEnabled(app, null, false)
            check(keepPrefs.edit().apply { fixtureCursors.forEach { remove(it) } }.commit())
            KeepConnectedService.clientFactoryForTest = { current, authStore -> fixture.clientFor(current, authStore) }
            audio.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
            app.auth.write(auth)
            val keepChannelId = "cypher_keep_connected"
            if (manager.getNotificationChannel(keepChannelId) == null) {
                manager.createNotificationChannel(NotificationChannel(keepChannelId, "Keep connected", NotificationManager.IMPORTANCE_LOW))
            }
            fun showSignInPrompt() {
                manager.notify(4083, Notification.Builder(app, keepChannelId)
                    .setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("Sign in again").build())
            }
            showSignInPrompt()
            KeepConnectedService.setEnabled(app, null, false)
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            assertTrue(keepPrefs.edit().putString(KeepConnectedService.KEY_ORIGIN, scope.origin).commit())
            showSignInPrompt()
            KeepConnectedService.onUserSignedOut(app, scope.origin)
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            app.preferences.edit { values ->
                values[originKey] = ""
                values[setupCompleteKey] = true
                values[setupActiveKey] = false
                values[setupStepKey] = "done"
            }
            val voiceStore = VoiceKeyStore(app)
            voiceStore.saveKey(voiceStore.settings().provider, "fixture-voice-key")
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java]
            }
            compose.waitUntil(5_000) { !vm.ui.value.restoringSession }
            vmClient = fixture.clientFor(auth, app.auth)
            compose.runOnUiThread {
                field(vm, "client", vmClient)
                field(vm, "preparedCypher", scope to "chat-b")
                flow<AppState>(vm, "_ui").value = AppState(origin = scope.origin, authenticated = true, connected = true, owner = scope.owner, profile = scope.profile, setupComplete = true, restoringSession = false)
                app.calls.ownerScope = scope
                app.calls.uiVisible = false
            }
            val showIncomingCall = mutableStateOf(false)
            compose.setContent { NativeLocalization("en") { CypherTheme(dark = true) {
                if (showIncomingCall.value) IncomingCallSheet(vm, requestingPermission = false) { source, event -> vm.handlePush(source, event, "answer") }
                else NotificationSettings(vm)
            } } }

            if (android.os.Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(android.app.NotificationChannel("cypher_keep_connected", "Keep connected", NotificationManager.IMPORTANCE_LOW))
            }
            val signInPrompt = if (android.os.Build.VERSION.SDK_INT >= 26) {
                android.app.Notification.Builder(app, "cypher_keep_connected")
            } else android.app.Notification.Builder(app)
            manager.notify(4083, signInPrompt.setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("Sign in again").build())

            val answerEvent = fixture.emitIncomingCall(conversationId = "chat-b")
            testEvents += answerEvent
            val answerSource = answerEvent.getString("source_ref")
            seedExistingFcmBinding(answerSource)
            compose.onNodeWithTag("keep-connected-toggle").performClick()
            compose.waitUntil(15_000) {
                KeepConnectedService.status(app) == KeepConnectedService.STATUS_CONNECTED &&
                    app.push.ringingInvitation(answerSource, answerEvent.getString("event_id")) != null
            }
            // The system applies notification cancels asynchronously.
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == 4083 } }
            assertTrue(fixture.notificationWaits.contains(25L))
            val answerInvite = app.push.ringingInvitation(answerSource, answerEvent.getString("event_id"))!!
            val answerEventId = answerEvent.getString("event_id")
            fun ringingCards() = manager.activeNotifications.filter { card ->
                val inviteRoute = android.app.PendingIntent.getActivity(
                    app,
                    "$answerSource:$answerEventId".hashCode(),
                    android.content.Intent(app, CypherActivity::class.java)
                        .setData(android.net.Uri.parse("hermes://activity/${answerInvite.key}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("push_source", answerSource)
                        .putExtra("push_event", answerEventId),
                    android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE,
                )
                inviteRoute != null && card.notification.category == android.app.Notification.CATEGORY_CALL && card.notification.contentIntent == inviteRoute
            }
            fun isRingingSlot(card: android.service.notification.StatusBarNotification) =
                (card.tag == answerInvite.key && card.id == 2) || (card.tag == null && card.id == 42)
            compose.waitUntil(10_000) { ringingCards().let { it.size == 1 && isRingingSlot(it.single()) } }
            val cards = ringingCards()
            assertEquals("Expected one active call card for this invite; found ${cards.map { "${it.tag}:${it.id}" }}", 1, cards.size)
            assertTrue("The invite card must use its notification or ringtone foreground slot", isRingingSlot(cards.single()))

            compose.runOnIdle { showIncomingCall.value = true }
            compose.onNodeWithTag("cypher-incoming-answer").assertIsDisplayed().performClick()
            compose.waitUntil(15_000) { vm.ui.value.tab == "Cypher" && vm.chat.value.sessionId == "chat-b" }
            assertEquals("chat-b", vm.chat.value.sessionId)
            val answerPath = "/api/plugins/cypher/mobile/callbacks/${answerEvent.getString("event_id")}/answer"
            // This fixture host is hidden; the service posts the explicit receipt before refusing to start a live call.
            compose.waitUntil(15_000) { fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath } && !app.calls.state.value.active }
            assertFalse(app.calls.state.value.active)
            assertTrue(fixture.requests.any { it.string("method") == "POST" && it.string("path") == answerPath })

            val declineEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += declineEvent
            val declineSource = declineEvent.getString("source_ref")
            seedExistingFcmBinding(declineSource)
            compose.waitUntil(10_000) { app.push.ringingInvitation(declineSource, declineEvent.getString("event_id")) != null }
            compose.onNodeWithTag("cypher-incoming-decline").performClick()
            compose.waitUntil(10_000) { fixture.incomingNotification?.optString("event_id") == declineEvent.getString("event_id") && fixture.incomingNotification?.optString("state") == "declined" }

            val expiryEvent = fixture.emitIncomingCall(conversationId = "chat-a", expiresInSeconds = 3)
            testEvents += expiryEvent
            val expirySource = expiryEvent.getString("source_ref")
            seedExistingFcmBinding(expirySource)
            compose.waitUntil(10_000) { app.push.missedInvitation(expirySource, expiryEvent.getString("event_id")) != null }

            val optOutEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += optOutEvent
            val optOutSource = optOutEvent.getString("source_ref")
            seedExistingFcmBinding(optOutSource)
            compose.waitUntil(10_000) { app.push.ringingInvitation(optOutSource, optOutEvent.getString("event_id")) != null }
            assertFalse(app.push.binding(optOutSource)!!.optBoolean("keep_connected"))
            KeepConnectedService.setEnabled(app, null, false)
            assertNull(app.push.ringingInvitation(optOutSource, optOutEvent.getString("event_id")))
            assertFalse(app.push.authorizeKeepConnectedAction(optOutSource, optOutEvent.getString("event_id"), "answer"))

            val fcmEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += fcmEvent
            val fcmSource = fcmEvent.getString("source_ref")
            val fcmEventId = fcmEvent.getString("event_id")
            seedExistingFcmBinding(fcmSource)
            receivePush(app, mapOf(
                "source_ref" to fcmSource,
                "event_id" to fcmEventId,
                "conversation_ref" to fcmEvent.getString("conversation_ref"),
                "kind" to "incoming_call",
                "expires_at" to fcmEvent.getString("expires_at"),
            ))
            assertTrue(app.push.authorizeKeepConnectedAction(fcmSource, fcmEventId, "answer"))
            assertTrue(app.push.authorizeKeepConnectedAction(fcmSource, fcmEventId, "decline"))

            val receiptlessEvent = fixture.emitIncomingCall(conversationId = "chat-a")
            testEvents += receiptlessEvent
            val receiptlessSource = receiptlessEvent.getString("source_ref")
            val receiptlessEventId = receiptlessEvent.getString("event_id")
            seedExistingFcmBinding(receiptlessSource)
            assertTrue(app.push.saveInvitation(dev.hermes.android.voice.IncomingCall(
                receiptlessSource,
                receiptlessEventId,
                receiptlessEvent.getString("expires_at").toLong(),
                java.util.UUID.randomUUID().toString(),
                conversation = receiptlessEvent.getString("conversation_ref"),
            )))
            assertFalse(app.push.authorizeKeepConnectedAction(receiptlessSource, receiptlessEventId, "answer"))
        } finally {
            KeepConnectedService.setEnabled(app, null, false)
            if (previousSignInNotification == null) manager.cancel(4083)
            else manager.notify(previousSignInNotification.tag, previousSignInNotification.id, previousSignInNotification.notification)
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
            restoreSharedPreferences(voicePrefs, voiceBefore)
            audio.setStreamVolume(AudioManager.STREAM_RING, beforeVolume, 0)
            if (keepBefore[KeepConnectedService.KEY_ENABLED] == true) KeepConnectedService.startIfEnabled(app)
            if (android.os.Build.VERSION.SDK_INT >= 33 && !hadNotifications) runCatching { uiAutomation.revokeRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS) }
            if (!hadRecordAudio) runCatching { uiAutomation.revokeRuntimePermission(app.packageName, Manifest.permission.RECORD_AUDIO) }
        }
    }
}
