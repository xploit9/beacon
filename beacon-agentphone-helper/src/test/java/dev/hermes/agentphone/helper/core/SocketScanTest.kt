package dev.hermes.agentphone.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * EMU-DESIGN §6.6: the helper holds INTERNET, so its code may open exactly one connection, to 10.0.2.2 at the port its
 * start intent names, and listen on nothing.
 */
class SocketScanTest {
    private val main = File("src/main")
    private fun sources() = main.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test
    fun oneDialAndNoListener() {
        val files = sources()
        assertTrue("found the sources", files.size >= 10)
        val forbidden = listOf(
            "ServerSocket", "LocalServerSocket", "LocalSocket", "DatagramSocket", "SocketChannel", "AsynchronousSocketChannel",
            "HttpURLConnection", "HttpsURLConnection", "openConnection", "URL(", "URLConnection", "WebSocket", "okhttp", "bind(",
            "SSLSocket", "createSocket", "InetAddress.getByName", "MulticastSocket",
        )
        for (f in files) {
            val text = f.readText()
            for (word in forbidden) assertTrue("${f.name} uses $word", word !in text)
        }
        val dials = files.flatMap { f -> Regex("""\bSocket\(""").findAll(f.readText()).map { f.name }.toList() }
        assertEquals(listOf("GuestDialer.kt"), dials)
        val dialer = files.single { it.name == "GuestDialer.kt" }.readText()
        assertTrue(dialer.contains("const val GUEST_HOST = \"10.0.2.2\""))
        assertTrue(dialer.contains("InetSocketAddress(GUEST_HOST, port)"))
        assertEquals(1, Regex("""InetSocketAddress\(""").findAll(dialer).count())
        assertEquals(1, files.sumOf { Regex("""\.connect\(""").findAll(it.readText()).count() })
    }

    @Test
    fun theManifestAsksOnlyForWhatTheDesignNames() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        val permissions = Regex("""uses-permission android:name="([^"]+)"""").findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(listOf("android.permission.INTERNET", "android.permission.FOREGROUND_SERVICE", "android.permission.QUERY_ALL_PACKAGES"), permissions)
        assertTrue(Regex("""android:name="\.ChannelService"\s+android:exported="true"\s+android:permission="android.permission.DUMP"""").containsMatchIn(manifest))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertEquals(1, Regex("""<activity""").findAll(manifest).count())
        assertTrue(Regex("""android:name="\.AuthActivity"[^>]*android:exported="false"""").containsMatchIn(manifest))
        // The phone's keyboard without keys (EMU-JOIN fu2): only the system binds it.
        assertTrue(Regex("""android:name="\.HelperInputMethod"[^>]*android:permission="android.permission.BIND_INPUT_METHOD"""")
            .containsMatchIn(manifest))
    }

    @Test
    fun theKeyboardNeverReadsOrWritesAField() {
        // As the phone's input method the helper could reach every field through an InputConnection; it never does (Root, fu2).
        val files = main.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(files.any { it.name == "HelperInputMethod.kt" })
        for (f in files) {
            val code = f.readLines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") }.joinToString("\n")
            for (word in listOf("InputConnection", "currentInputConnection", "getCurrentInputConnection", "currentInputEditorInfo",
                                "onUpdateSelection", "onUpdateExtractedText")) {
                assertTrue("${f.name} uses $word", word !in code)
            }
        }
    }
}
