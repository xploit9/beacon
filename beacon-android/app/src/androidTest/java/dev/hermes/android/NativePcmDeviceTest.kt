package dev.hermes.android

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.voice.NativePcm
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.sin

/** Measures Android capture/playout callbacks; it does not certify acoustic quality or Bluetooth. */
class NativePcmDeviceTest {
    @get:Rule val foreground = createAndroidComposeRule<ComponentActivity>()

    @Test fun directPcmCaptureMutePlayoutAndInterruptUseRealAndroidAudioObjects() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        check(context.packageName.endsWith(".debug"))
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        val audioManager=context.getSystemService(android.media.AudioManager::class.java)
        assertTrue("Run isolated from other app recordings", audioManager.activeRecordingConfigurations.isEmpty())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val captured = AtomicLong(); val played = AtomicLong(); val faults = ConcurrentLinkedQueue<String>()
        val pcm = NativePcm(scope, { data, _ -> captured.addAndGet(data.size.toLong()) },
            { data, _ -> played.addAndGet(data.size.toLong()) }, { faults.add(it) }, { _, _ -> })
        suspend fun await(condition: () -> Boolean) = withTimeout(7000) {
            while (!condition()) { assertTrue(faults.toString(), faults.isEmpty()); delay(20) }
        }
        val tone = ByteArray(24000).apply {
            for (i in 0 until size/2) {
                val sample = (sin(2 * PI * 440 * i / 24000) * 6000).toInt()
                this[i*2] = sample.toByte(); this[i*2+1] = (sample shr 8).toByte()
            }
        }
        try {
            pcm.start(context); pcm.mute(false)
            await { captured.get() >= 640 }
            await { audioManager.activeRecordingConfigurations.isNotEmpty() }
            pcm.mute(true)
            val mutedAt = captured.get(); delay(250)
            assertEquals("No microphone data may escape after mute returns", mutedAt, captured.get())
            assertTrue(pcm.enqueue(tone))
            await { played.get() == tone.size.toLong() }
            assertTrue(pcm.enqueue(tone))
            delay(20); pcm.interrupt()
            val interruptedAt = played.get(); delay(250)
            assertEquals("Interrupted queued audio must never be archived as played", interruptedAt, played.get())
            assertTrue(faults.toString(), faults.isEmpty())
            pcm.mute(false)
            assertTrue(pcm.enqueue(tone))
            await { captured.get()>mutedAt }
            pcm.close()
            val endedCapture=captured.get();val endedPlayout=played.get()
            pcm.close()
            assertFalse("Closed output must reject more audio",pcm.enqueue(tone))
            pcm.mute(false)
            delay(300)
            assertEquals("Capture stopped after close",endedCapture,captured.get())
            assertEquals("Playout stopped after close",endedPlayout,played.get())
            await { audioManager.activeRecordingConfigurations.isEmpty() }
            assertTrue("Android confirms no active recording",audioManager.activeRecordingConfigurations.isEmpty())
        } finally { pcm.close(); scope.cancel() }
    }
}
