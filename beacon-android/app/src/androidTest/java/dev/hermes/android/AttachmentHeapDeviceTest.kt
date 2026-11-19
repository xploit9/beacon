package dev.hermes.android

import android.os.Bundle
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.json
import dev.hermes.android.data.InlineFile
import dev.hermes.android.data.streamInlineFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * CHAT-121 follow-up 4: the phone's own cap on a file it sends, measured, not assumed. A synthetic file of each size is streamed through the real
 * piece writer (streamInlineFrame, as a send does, with the pieces dropped instead of sent), and the heap is read every 32 pieces after a GC, so the
 * figure is what stays alive while sending, not garbage. For comparison, the old way at 16 MiB: the file's base64 as one String.
 * Opt-in: `device-test.py ... --arg beaconHeapCheck=1`. Every file it makes is deleted.
 */
class AttachmentHeapDeviceTest {
    private val mb = 1024L * 1024

    private fun status(key: String, value: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString(key, value) })

    private fun used(): Long = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }

    private fun settled(): Long { repeat(2) { System.gc(); System.runFinalization(); Thread.sleep(100) }; return used() }

    @Test(timeout = 1_200_000) fun sendingAFileInPiecesKeepsTheHeapFlat() = runBlocking {
        assumeTrue("Opt-in: device-test.py --arg beaconHeapCheck=1", InstrumentationRegistry.getArguments().getString("beaconHeapCheck") == "1")
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val runtime = Runtime.getRuntime()
        status("heap_device", "max=${runtime.maxMemory() / mb} MiB")
        val block = ByteArray(mb.toInt()) { (it * 31 % 251).toByte() }
        val peaks = mutableMapOf<Int, Long>()
        for (size in listOf(16, 64, 128, 256)) {
            val file = File(app.cacheDir, "heap-check-$size.pdf")
            try {
                file.outputStream().use { out -> repeat(size) { out.write(block) } }
                val base = settled()
                var peak = 0L
                var pieces = 0
                val began = SystemClock.elapsedRealtime()
                val frame = json("jsonrpc" to "2.0", "id" to "heap", "method" to "file.attach", "params" to json("session_id" to "s", "name" to file.name))
                val whole = streamInlineFrame(frame, InlineFile(file, "data_url", "data:application/pdf;base64,"), send = { _ ->
                    if (++pieces % 32 == 0) peak = maxOf(peak, settled() - base)
                    true
                }, queued = { 0L }, pause = {}, acked = { Long.MAX_VALUE })  // every piece taken at once, as the plugin acknowledges it
                peaks[size] = peak
                status("heap_pieces_$size", "file=$size MiB whole=$whole pieces=$pieces live_peak=${peak / 1024} KiB base=${base / mb} MiB took=${SystemClock.elapsedRealtime() - began} ms")
            } finally { file.delete() }
        }
        // The old way, for comparison: the whole file's base64 as one String (what a text frame needs).
        val file = File(app.cacheDir, "heap-check-old.pdf")
        try {
            file.outputStream().use { out -> repeat(16) { out.write(block) } }
            val base = settled()
            val encoded = android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
            val held = used() - base
            status("heap_old_16", "file=16 MiB base64=${encoded.length / 1024} Ki chars held=${held / mb} MiB")
        } finally { file.delete() }
        // Flat: what stays alive while sending does not grow with the file (a few MiB of pieces and buffers at most).
        assertTrue("$peaks", peaks.values.all { it < 16 * mb })
    }
}
