package dev.hermes.android.ui

import androidx.compose.ui.unit.dp
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** RECV-121 follow-up 3: once per message, the app version the plugin gates its note on, and a table reply's width on wide screens. */
class ReplyFollowup3Test {
    @get:Rule val folder = TemporaryFolder()
    private val requests = mutableListOf<Request>()
    private fun client() = BeaconClient(AuthContext("https://native.example", emptyList(), "owner", "work", backend = "hermes-dashboard"), null) { builder ->
        builder.addInterceptor { chain ->
            val request = chain.request(); synchronized(requests) { requests += request }
            Response.Builder().code(200).header("Content-Length", "10").header("ETag", "\"v1\"").body(ByteArray(0).toResponseBody(null))
                .request(request).protocol(Protocol.HTTP_1_1).message("Fixture").build()
        }
    }

    @Test fun aCardAsksOncePerMessageAndALaterMessageAsksAgain() = runBlocking {
        val c = client()
        val chat = c.scope.session("stored-a")
        val tag = NativeReference.LocalMedia("/home/me/out/chart.png", chat.session, null)
        val store = ReceivedFileStore(folder.root)
        store.check(chat, tag, c, message = "m1")
        store.check(chat, tag, c, message = "m1")
        assertEquals("the same message's card coming back into view does not ask again", 1, requests.size)
        store.check(chat, tag, c, message = "m2")
        assertEquals("a later message naming the same path asks", 2, requests.size)
    }

    @Test fun aCardThatAlreadyAskedShowsItsPictureAtOnce() = runBlocking {
        val c = client()
        val chat = c.scope.session("stored-a")
        val path = "/home/me/out/chart.png"
        val store = ReceivedFileStore(folder.root)
        assertFalse(store.asked(chat, path, "m1"))
        store.check(chat, NativeReference.LocalMedia(path, chat.session, null), c, message = "m1")
        assertTrue("m1's card scrolling back in", store.asked(chat, path, "m1"))
        assertFalse("a later message has not asked yet", store.asked(chat, path, "m2"))
        assertFalse(store.asked(chat, path, null))
    }

    /** FU2-P3-1: Copy, Share and Quote keep code as the bubble shows it; summaries (chat list, read aloud) name a file even in code. */
    @Test fun copyKeepsCodeAndSummariesStillNameTheFile() {
        val reply = "Run this to send the chart:\n```python\nprint(\"MEDIA:/tmp/out/chart.png\")\n```\nand `MEDIA:/tmp/out/a.pdf` in a sentence, " +
            "then MEDIA:/tmp/out/b.csv"
        val copied = withFileNames(reply, keepCode = true)
        assertTrue(copied, copied.contains("print(\"MEDIA:/tmp/out/chart.png\")"))
        assertTrue(copied, copied.contains("and `MEDIA:/tmp/out/a.pdf` in a sentence"))
        assertTrue(copied, copied.endsWith("then b.csv"))
        // Backticks right after MEDIA: quote the tag's own path with spaces (Hermes' base.py), not code.
        assertEquals("c.pdf", withFileNames("MEDIA:`/tmp/My Files/c.pdf`", keepCode = true))
        // A model often puts the tag itself in inline code: the chat list and read aloud still say only the name.
        val preview = chatPreviewText("Here: `MEDIA:/home/me/out/report.pdf`")
        assertTrue(preview, preview.contains("report.pdf") && !preview.contains("/home/me"))
        assertFalse(withFileNames(reply).contains("MEDIA:"))
    }

    @Test fun beaconRequestsSayWhichAppVersionThePhoneRuns() {
        val c = client()
        assertEquals(BuildConfig.VERSION_NAME, c.request("/api/plugins/beacon/mobile/bootstrap").build().header(APP_VERSION_HEADER))
        assertNull("only the Beacon plugin hears it", c.request("/api/sessions").build().header(APP_VERSION_HEADER))
    }

    @Test fun aTableReplyIsCappedOnWideScreens() {
        assertEquals("a phone: the row inside the list's edges", 387.dp, tableBubbleMax(411.dp, 329.dp))
        assertEquals("an unfolded phone or a tablet: capped", 840.dp, tableBubbleMax(1280.dp, 560.dp))
        assertEquals("never narrower than an ordinary bubble", 560.dp, tableBubbleMax(500.dp, 560.dp))
        assertEquals(560.dp, ReadableWidth)
    }
}
