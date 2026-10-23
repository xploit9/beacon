package dev.hermes.android.data

import dev.hermes.android.core.*
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

class NativeAttachmentDownloadTest {
    @Test fun onlyCanonicalAbsoluteUserImageDirectivesBecomeServerFiles() {
        assertEquals(listOf("/work/photo.jpg", "C:\\work\\my photo.png"), persistedImagePaths("Caption\n@image:/work/photo.jpg\n@image:\"C:\\work\\my photo.png\""))
        for (text in listOf("Example @image:/work/photo.jpg", "@image:relative.jpg", "@image:https://cdn.example/photo.jpg", "@image:\"/work/photo.jpg", "@image:/work/file.txt", "@image:/work/bad\u0000.jpg"))
            assertTrue(text, persistedImagePaths(text).isEmpty())
    }

    private fun client(profile: String, requests: MutableList<Request>, bytes: (Request) -> ByteArray) =
        BeaconClient(AuthContext("https://native.example", emptyList(), "owner", profile, backend = "hermes-dashboard"), null) { builder ->
            builder.addInterceptor { chain ->
                val request = chain.request(); requests += request
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                    .body(bytes(request).toResponseBody("application/octet-stream".toMediaType())).build()
            }
        }

    @Test fun unixAndWindowsReceiptsReachAuthenticatedNativeBinaryTransport() = runBlocking {
        val requests = mutableListOf<Request>()
        val payload = byteArrayOf(1, 2, 3)
        val snapshot = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val c = client("work", requests) { payload }
        for (path in listOf("/workspace/.beacon-attachments/photo.jpg", "C:\\workspace\\photo.jpg")) {
            val receipt = json("path" to path, "name" to "photo.jpg", "mime" to "image/jpeg")
            val reference = resolveAttachmentReference(receipt, c.scope.session("stored-a"), mapOf(path to snapshot)) as NativeReference.LocalMedia
            assertEquals(path, reference.path)
            assertArrayEquals(payload, c.bytes(reference.endpoint, reference.query()))
            val request = requests.last()
            assertEquals("native.example", request.url.host)
            assertEquals("/api/fs/download", request.url.encodedPath)
            assertEquals(path, request.url.queryParameter("path"))
            assertEquals("stored-a", request.url.queryParameter("session_id"))
            assertEquals("work", request.url.queryParameter("profile"))
            assertEquals(snapshot, request.url.queryParameter("snap"))
        }
        val media = resolveNativeReference("/api/media?path=C:/workspace/photo.jpg&session_id=foreign", c.scope.session("stored-a")) as NativeReference.LocalMedia
        assertArrayEquals(payload, c.bytes(media.endpoint, media.query()))
        assertEquals("/api/fs/download", requests.last().url.encodedPath)
        assertEquals("stored-a", requests.last().url.queryParameter("session_id"))
        assertTrue(resolveNativeReference("https://cdn.example/photo.jpg", c.scope.session("stored-a")) is NativeReference.ExternalMedia)
        assertTrue(runCatching { c.bytes(media.endpoint, media.query() + ("snap" to "0".repeat(64))) }.isFailure)
    }

    @Test fun sameFilesystemPathAndRelativeReadsKeepTheirProfileAndConversation() = runBlocking {
        val requests = mutableListOf<Request>()
        val clients = listOf("work", "personal").map { profile -> client(profile, requests) { it.url.queryParameter("profile")!!.toByteArray() } }
        for (c in clients) for (path in listOf("/workspace/report.pdf", "reports/report.pdf")) {
            assertEquals(c.scope.profile, c.bytes("/api/file/raw", mapOf("path" to path, "session_id" to "stored-${c.scope.profile}")).toString(Charsets.UTF_8))
            assertEquals(path, requests.last().url.queryParameter("path"))
            assertEquals("stored-${c.scope.profile}", requests.last().url.queryParameter("session_id"))
            val count = requests.size
            assertTrue(runCatching { c.bytes("/api/file/raw", mapOf("path" to path, "profile" to "foreign")) }.isFailure)
            assertEquals(count, requests.size)
        }
    }
}
