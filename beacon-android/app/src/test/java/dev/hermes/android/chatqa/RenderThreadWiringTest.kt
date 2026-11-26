package dev.hermes.android.chatqa

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F1/S1: the projection is recorded only by a render that survived its encode guard, and transcript writes leave the frame thread. */
class RenderThreadWiringTest {
    private val module: File by lazy {
        requireNotNull(listOf(".", "app", "beacon-android/app").map { File(it) }.firstOrNull { File(it, "src/main/assets/native-locales.json").isFile })
    }
    private val model: String by lazy { File(module, "src/main/java/dev/hermes/android/BeaconViewModel.kt").readText().replace("\r\n", "\n") }

    @Test fun theNewestRenderProjectionIsAssignedOnlyAfterTheEncodeGuard() {
        val body = model.substringAfter("private suspend fun renderSession(", "").substringBefore("\n    private suspend fun", "")
        assertTrue("renderSession body found", body.isNotBlank())
        assertTrue("the encoded projection is kept in a local", body.contains("val encoded = encodeProjection(response)"))
        assertFalse("the projection must not be encoded inline into the assignment", body.contains("renderedServerProjection = scope to encodeProjection(response)"))
        val guard = body.indexOf("if (sourceScope != scope || revision != chatHistoryRevision) return")
        val assignment = body.indexOf("renderedServerProjection = scope to encoded")
        val decode = body.indexOf("val mapped = withContext(Dispatchers.Default)")
        assertTrue("the encode guard precedes the assignment", guard >= 0 && assignment > guard)
        assertTrue("the assignment precedes the decode", decode > assignment)
    }

    @Test fun noWriteSessionSerializesATranscriptOnTheFrameThread() {
        val writes = Regex("""writeSession\([^\n]*\.toString\(\)""").findAll(model).map { it.value }.toList()
        assertTrue("writeSession still serializes on its caller's thread:\n" + writes.joinToString("\n"), writes.isEmpty())
    }
}
