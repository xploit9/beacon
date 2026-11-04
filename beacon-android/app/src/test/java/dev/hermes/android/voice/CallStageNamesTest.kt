package dev.hermes.android.voice

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Owner's phone, 4 Nov 2026: calls stopped after grant_ok and nothing said where. The plugin refuses a whole batch of call events when
 * one names a stage it does not know, so every stage the phone reports must be one the plugin of the same release accepts.
 */
class CallStageNamesTest {
    private val app = requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it).absoluteFile.normalize() }
        .firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile && File(it, "build.gradle.kts").isFile })
    private fun source(name: String) = File(app, "src/main/java/dev/hermes/android/voice/$name").readText()

    private fun pluginStages(): Set<String> {
        val mobile = File(app, "../../hermes-plugin/beacon/mobile.py").readText()
        fun tuple(name: String) = Regex("""(?m)^$name\s*=\s*\(([^)]*)\)""").find(mobile)!!.groupValues[1].let { body -> Regex("'([a-z_]+)'").findAll(body).map { it.groupValues[1] }.toSet() }
        return tuple("DIAGNOSTIC_STAGES") + tuple("CALL_STAGES")
    }

    @Test fun everyStageThePhoneReportsIsOneThePluginAccepts() {
        val known = pluginStages()
        val reported = listOf("BeaconCallService.kt", "DirectVoice.kt").flatMap { file ->
            Regex("""(?:transportStage\(spec,\s*|earlyStage\([^,]+,\s*|diagnostic\(|stage\()"([a-z_]+)"""").findAll(source(file)).map { it.groupValues[1] }.toList()
        }.toSet()
        assertTrue("the phone reports stages", reported.containsAll(setOf("transport_started", "transport_open", "audio_ready", "transport_timeout", "voice_connecting")))
        assertTrue("unknown to the plugin: ${reported - known}", known.containsAll(reported))
    }

    @Test fun aStartThatNeverReachesTheVoiceConnectionEndsWithAReason() {
        val service = source("BeaconCallService.kt").replace("\r\n", "\n")
        val open = service.substringAfter("private fun openTransport(spec: CallSpec) {").substringBefore("connectionJob?.start()")
        assertTrue(open.contains("delay(TRANSPORT_START_LIMIT_MS)"))
        assertTrue(open.contains("failure(callText(service, CALL_START_FAILED))"))
        assertTrue(TRANSPORT_START_LIMIT_MS in 5_000L..20_000L)
    }
}
