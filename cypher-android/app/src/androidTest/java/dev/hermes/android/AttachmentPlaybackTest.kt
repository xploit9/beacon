package dev.hermes.android

import android.media.AudioManager
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.ui.LocalMedia
import dev.hermes.android.voice.CallController
import dev.hermes.android.voice.CallState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Actual platform playback, synthetic tone and call UI state; never opens a microphone or provider. */
class AttachmentPlaybackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun await(test: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5_000
        while (!test() && SystemClock.elapsedRealtime() < end) SystemClock.sleep(20)
        assertTrue("Platform playback did not settle", test())
    }
    @Test fun attachmentPlaybackPausesOnStopAndCannotCompeteWithCalls() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cypherNoHostAudio") == "1")
        val app = ApplicationProvider.getApplicationContext<CypherApplication>()
        check(app.packageName.endsWith(".debug") && !app.calls.state.value.active && app.calls.service == null)
        val audio = app.getSystemService(AudioManager::class.java)
        await { audio.activePlaybackConfigurations.isEmpty() }
        val origin = stringPreferencesKey("origin")
        val previous = runBlocking { app.preferences.data.first()[origin] }
        runBlocking { app.preferences.edit { it[origin] = "" } }
        val store = ViewModelStore()
        val before = app.calls.state.value
        try {
            lateinit var vm: CypherViewModel
            compose.runOnUiThread { vm = ViewModelProvider(store, viewModelFactory { initializer { CypherViewModel(app, SavedStateHandle()) } })[CypherViewModel::class.java] }
            val samples = 16_000 * 8
            val tone = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
                putShort(1); putShort(1); putInt(16_000); putInt(32_000); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(samples * 2)
                repeat(samples) { putShort((kotlin.math.sin(it * 2.0 * Math.PI * 330 / 16_000) * 1200).toInt().toShort()) }
            }.array()
            compose.setContent { CypherTheme(dark = true) { LocalMedia(vm, tone, "wav", false, "synthetic-playback") } }
            compose.waitUntil(10_000) { compose.onAllNodes(hasContentDescription("Play audio") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Play audio").performClick()
            await { audio.activePlaybackConfigurations.isNotEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            await { audio.activePlaybackConfigurations.isEmpty() }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithContentDescription("Play audio").performClick()
            await { audio.activePlaybackConfigurations.isNotEmpty() }
            compose.runOnIdle { app.calls.update { CallState(active = true, callId = "synthetic-no-media-call") } }
            compose.waitForIdle()
            await { audio.activePlaybackConfigurations.isEmpty() }
            compose.onNodeWithContentDescription("Play audio").assertIsNotEnabled()
            compose.onNodeWithText("End the call to play attachments.").assertExists()
            compose.runOnIdle { app.calls.update { before } }
            compose.onNodeWithContentDescription("Play audio").assertIsEnabled()
            await { audio.activePlaybackConfigurations.isEmpty() }
            File(app.filesDir, "attachment-playback-proof.json").writeText(json("positive_playback_before" to true,
                "stopped_when_hidden" to true, "stopped_for_call" to true, "manual_resume_required" to true, "version_code" to BuildConfig.VERSION_CODE).toString())
        } finally {
            compose.runOnUiThread { app.calls.update { before }; store.clear() }
            runBlocking { app.preferences.edit { if (previous == null) it.remove(origin) else it[origin] = previous } }
        }
    }
}
