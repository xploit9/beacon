package dev.hermes.android.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import dev.hermes.android.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Debug builds only: uncaught crashes go to a local Bugsink (Sentry protocol). Off when BUGSINK_DSN is blank. */
class BugsinkCrashReporter : ContentProvider() {
    override fun onCreate(): Boolean {
        if (Build.HARDWARE != "ranchu" && Build.HARDWARE != "goldfish") return true
        val target = BugsinkTarget.parse(BuildConfig.BUGSINK_DSN) ?: return true
        val dir = File(requireNotNull(context).cacheDir, "bugsink").apply { mkdirs() }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val file = File(dir, "${System.currentTimeMillis()}.envelope")
                file.writeText(crashEnvelope(error, thread.name, BuildConfig.VERSION_NAME, System.currentTimeMillis(), Build.VERSION.RELEASE, Build.MODEL))
                // The crashing thread may be main, where Android forbids network calls.
                Thread { target.send(file) }.apply { start(); join(3000) }
            }
            previous?.uncaughtException(thread, error)
        }
        Thread { dir.listFiles()?.sorted()?.forEach(target::send) }.start() // what an earlier crash could not send
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal class BugsinkTarget(val url: String, private val key: String) {
    fun send(file: File) {
        val request = Request.Builder().url(url)
            .header("X-Sentry-Auth", "Sentry sentry_version=7, sentry_key=$key, sentry_client=beacon-debug/1.0")
            .post(file.readText().toRequestBody("application/x-sentry-envelope".toMediaType())).build()
        runCatching { client.newCall(request).execute().use { if (it.isSuccessful) file.delete() } }
    }

    companion object {
        // Bugsink runs on the PC itself; a device-wide proxy (the emulator's CONNECT tunnel) cannot carry plain HTTP.
        private val client by lazy { OkHttpClient.Builder().proxy(java.net.Proxy.NO_PROXY).callTimeout(5, TimeUnit.SECONDS).build() }
        fun parse(dsn: String): BugsinkTarget? {
            val match = Regex("^(https?)://([^@:/]+)@([^/]+)/(\\d+)$").find(dsn.trim()) ?: return null
            val (scheme, key, host, project) = match.destructured
            return BugsinkTarget("$scheme://$host/api/$project/envelope/", key)
        }
    }
}

internal fun crashEnvelope(error: Throwable, thread: String, version: String, nowMs: Long, os: String, model: String): String {
    val id = UUID.randomUUID().toString().replace("-", "")
    val chain = generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(8).toList()
    // Sentry lists the outermost exception last, and each stack oldest frame first.
    val values = JSONArray(chain.reversed().map { e ->
        JSONObject().put("type", e.javaClass.name).put("value", e.message.orEmpty().take(2000))
            .put("stacktrace", JSONObject().put("frames", JSONArray(e.stackTrace.take(64).reversed().map { f ->
                JSONObject().put("module", f.className).put("function", f.methodName).put("filename", f.fileName ?: "")
                    .put("lineno", f.lineNumber).put("in_app", f.className.startsWith("dev.hermes.android"))
            })))
    })
    val event = JSONObject().put("event_id", id).put("timestamp", nowMs / 1000.0).put("platform", "java")
        .put("level", "fatal").put("environment", "debug").put("release", "beacon-debug@$version")
        .put("exception", JSONObject().put("values", values)).put("tags", JSONObject().put("thread", thread))
        .put("contexts", JSONObject().put("os", JSONObject().put("name", "Android").put("version", os))
            .put("device", JSONObject().put("model", model)))
    return listOf(JSONObject().put("event_id", id), JSONObject().put("type", "event"), event).joinToString("\n")
}
