package dev.hermes.android

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import dev.hermes.android.ui.NativeMediaNode
import dev.hermes.android.ui.descendants
import dev.hermes.android.ui.parseNativeMarkdown
import dev.hermes.android.voice.DemoCallSignIn
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Opt-in, demo Hermes only (beaconReceiveFilesLive=1, with the app signed in or a staged demo login; in a chat of its own, deleted at the end). Synthetic files go into a QA folder of
 * that chat's workspace through Hermes' own upload route, are received through /api/fs/download exactly as a delivered file is, opened and
 * shared (the intents are caught here, so no other app starts), saved to Downloads for real, and everything made is removed again.
 */
class ReceivedFilesDeviceTest {
    // Signs in from a staged demo login (demo-fixture.py prepare) under this test's own opt-in; with none it does nothing.
    @get:Rule(order = 0) val signIn = DemoCallSignIn("beaconReceiveFilesLive")
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    /** Catches every activity start, so Open and Share are checked without another app opening. */
    private class Catch : Instrumentation.ActivityMonitor() {
        val intents = mutableListOf<Intent>()
        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult { synchronized(intents) { intents += intent }; return Instrumentation.ActivityResult(Activity.RESULT_OK, null) }
    }

    @Test(timeout = 300_000) fun deliveredFilesComeThroughHermesAndOpenSaveAndShareOnThePhone() = runBlocking<Unit> {
        assumeTrue("Requires the explicit demo opt-in", InstrumentationRegistry.getArguments().getString("beaconReceiveFilesLive") == "1")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val app = context.applicationContext as BeaconApplication
        require(BuildConfig.DEBUG && app.packageName.endsWith(".debug"))
        require(Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk")) { "Emulator only" }
        // Android's ICU regex reads the tags as the JVM does (a bare brace in a class passes the JVM and fails here).
        assertEquals(listOf("/tmp/AI Brain/report.pdf", "C:\\work\\.cache\\out.zip"),
            parseNativeMarkdown("Done: MEDIA:/tmp/AI Brain/report.pdf. MEDIA:C:\\work\\.cache\\out.zip").descendants().filterIsInstance<NativeMediaNode>().map { it.reference }.toList())

        ActivityScenario.launch(BeaconActivity::class.java).use { scenario ->
            var model: BeaconViewModel? = null
            val field = BeaconActivity::class.java.getDeclaredField("vm").apply { isAccessible = true }
            compose.waitUntil(60_000) {
                scenario.onActivity { model = field.get(it) as? BeaconViewModel }
                model?.let { it.client != null && it.ui.value.authenticated && it.ui.value.connected && !it.ui.value.loading && !it.ui.value.restoringSession } == true
            }
            val vm = checkNotNull(model)
            withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { vm.awaitSelection() } }
            // Its own chat, never the one on screen (a test before it may have deleted that one); deleted again at the end.
            val sid = checkNotNull(vm.client).detached().let { maker ->
                try { maker.post("/api/session/new", JSONObject().put("title", "Received files check").put("request_id", UUID.randomUUID().toString()))
                    .let { (it.optJSONObject("session") ?: it).getString("session_id") } }
                finally { maker.closeNative() }
            }
            withContext(Dispatchers.Main.immediate) { vm.selectSession(sid) }
            withTimeout(30_000) { withContext(Dispatchers.Main.immediate) { vm.awaitSelection() } }
            require(vm.chat.value.sessionId == sid) { "The test's own chat did not open" }
            val scope = checkNotNull(vm.authenticatedScope()).session(sid)
            val run = UUID.randomUUID().toString().take(8)
            val c = checkNotNull(vm.client).detached()
            val folder = NativeOperations(c).workspacePath(sid, "recv-qa-$run")
            val root = File(app.filesDir, "received/qa-$run")
            val saved = mutableListOf<Uri>()
            val catcher = Catch()
            try {
                suspend fun put(relative: String, bytes: ByteArray, mime: String, overwrite: Boolean = false): String {
                    val target = NativeOperations(c).workspacePath(sid, "recv-qa-$run/$relative")
                    val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("path", target).addFormDataPart("overwrite", "$overwrite")
                        .addFormDataPart("file", relative.substringAfterLast('/'), bytes.toRequestBody(mime.toMediaType())).build()
                    val result = c.nativeResponse(c.request("/api/files/upload-stream", mapOf("profile" to c.auth.profile), "POST", body).build()) { response ->
                        require(response.isSuccessful) { "Upload failed (${response.code})" }
                        JSONObject(response.body!!.string())
                    }
                    return result.optJSONObject("entry")?.optString("path")?.takeIf(String::isNotBlank) ?: result.getString("path")
                }
                val csv = "name,qty\nwidget,3\ngadget,5\n".toByteArray()
                val zip = ByteArray(3_000_000) { (it * 31 % 251).toByte() }
                val csvPath = put("stock.csv", csv, "text/csv")
                val zipPath = put("bundle.zip", zip, "application/zip")
                val refusedPath = put("pairing/note.txt", "synthetic".toByteArray(), "text/plain")
                fun ref(path: String) = NativeReference.LocalMedia(path, sid, null)
                val store = ReceivedFileStore(root)

                // The card's size and type, from a HEAD on Hermes' own route.
                store.check(scope, ref(csvPath), c.detached())
                assertEquals(csv.size.toLong(), store.state(scope, csvPath).value.size)
                assertNull(store.state(scope, csvPath).value.problem)

                // A larger file downloads with visible progress and arrives whole.
                val seen = mutableListOf<Long>()
                val watcher = launch(Dispatchers.Default) { store.state(scope, zipPath).collect { if (it.downloading && it.received in 1 until zip.size) synchronized(seen) { seen += it.received } } }
                withTimeout(120_000) { store.download(scope, ref(zipPath), c.detached())!!.join() }
                watcher.cancel()
                assertArrayEquals(zip, store.localCopy(scope, zipPath)!!.readBytes())
                assertTrue("progress was shown while it downloaded", synchronized(seen) { seen.isNotEmpty() })
                withTimeout(60_000) { store.download(scope, ref(csvPath), c.detached())!!.join() }
                val local = checkNotNull(store.localCopy(scope, csvPath))
                assertArrayEquals(csv, local.readBytes())

                // Open: Android's viewer for the type gets a readable content:// address from this app's FileProvider, never a file path.
                instrumentation.addMonitor(catcher)
                assertTrue(openReceivedFile(context, local, receivedFileMime("stock.csv")))
                val view = synchronized(catcher.intents) { catcher.intents.last() }
                assertEquals(Intent.ACTION_VIEW, view.action)
                assertEquals("content", view.data!!.scheme)
                assertEquals(app.packageName + ".files", view.data!!.authority)
                assertEquals("text/csv", view.type)
                assertTrue(view.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertArrayEquals(csv, context.contentResolver.openInputStream(view.data!!)!!.use { it.readBytes() })

                // Share: the share sheet with the file itself.
                assertTrue(shareReceivedFile(context, local, "stock.csv", "text/csv"))
                val chooser = synchronized(catcher.intents) { catcher.intents.last() }
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("text/csv", send.type)
                @Suppress("DEPRECATION") val stream = send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals(app.packageName + ".files", stream.authority)
                instrumentation.removeMonitor(catcher)

                // Save: a real copy in Downloads, complete and visible.
                val downloads = saveReceivedFileToDownloads(context, local, "recv-qa-$run.csv", "text/csv")
                saved += downloads
                assertArrayEquals(csv, context.contentResolver.openInputStream(downloads)!!.use { it.readBytes() })
                context.contentResolver.query(downloads, arrayOf(MediaStore.Downloads.RELATIVE_PATH, MediaStore.Downloads.IS_PENDING), null, null, null)!!.use { row ->
                    assertTrue(row.moveToFirst())
                    assertTrue(row.getString(0).startsWith("Download"))
                    assertEquals(0, row.getInt(1))
                }

                // After a restart the copy is still here and opens without asking Hermes.
                assertArrayEquals(csv, ReceivedFileStore(root).localCopy(scope, csvPath)!!.readBytes())

                // Hermes' real answers (its FileResponse's ETag and Last-Modified): an unchanged file keeps its copy, before an action and on
                // the next card; the agent rewriting the same path is seen, and the new version is what downloads (REVIEW-RECV P2-1).
                val again = ReceivedFileStore(root)
                assertArrayEquals("unchanged: the copy opens", csv, again.current(scope, ref(csvPath), c.detached())!!.readBytes())
                again.check(scope, ref(csvPath), c.detached())
                assertNotNull("unchanged: the next card keeps the copy", again.localCopy(scope, csvPath))
                val revised = "name,qty\nwidget,4\ngadget,5\nsprocket,9\n".toByteArray()
                assertEquals(csvPath, put("stock.csv", revised, "text/csv", overwrite = true))
                assertNull("rewritten: the first version is not handed out", again.current(scope, ref(csvPath), c.detached()))
                withTimeout(60_000) { again.download(scope, ref(csvPath), c.detached())!!.join() }
                assertArrayEquals(revised, again.localCopy(scope, csvPath)!!.readBytes())
                assertNotNull("and the new copy is current", again.current(scope, ref(csvPath), c.detached()))

                // Hermes' own rules: a file in a credential folder is refused, and nothing is fetched another way.
                store.check(scope, ref(refusedPath), c.detached())
                assertEquals(ReceivedFileProblem.Refused, store.state(scope, refusedPath).value.problem)
                withTimeout(60_000) { store.download(scope, ref(refusedPath), c.detached())!!.join() }
                assertEquals(ReceivedFileProblem.Refused, store.state(scope, refusedPath).value.problem)
                assertNull(store.localCopy(scope, refusedPath))

                // A file removed from Hermes says so on its card.
                c.nativeRequest("/api/files", "DELETE", json("path" to csvPath))
                val fresh = ReceivedFileStore(File(root, "fresh"))
                fresh.check(scope, ref(csvPath), c.detached())
                assertEquals(ReceivedFileProblem.Gone, fresh.state(scope, csvPath).value.problem)
            } finally {
                instrumentation.removeMonitor(catcher)
                saved.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
                runCatching { c.nativeRequest("/api/files", "DELETE", json("path" to folder, "recursive" to true)) }
                root.deleteRecursively()
                c.closeNative()
                // The test's own chat goes through the conversation menu's own Delete.
                withContext(NonCancellable) { runCatching { withTimeoutOrNull(20_000) {
                    withContext(Dispatchers.Main.immediate) { vm.conversationAction(sid, "/api/session/delete", done = "Deleted") }.join()
                } } }
            }
        }
    }
}
