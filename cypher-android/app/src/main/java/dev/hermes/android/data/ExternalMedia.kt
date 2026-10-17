package dev.hermes.android.data

import dev.hermes.android.core.NativeReference
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.TimeUnit

data class NativeMediaBytes(val bytes: ByteArray, val mime: String = "")
/** This client has no owner cookies, CSRF, profile headers, authentication or cache. */
private val externalMediaHttp = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
internal fun externalMediaRequest(url: HttpUrl): Request {
    require(url.scheme == "https" && url.username.isBlank() && url.password.isBlank()) { "External previews require credential-free HTTPS" }
    return Request.Builder().url(url).header("Accept", "image/*, audio/*, video/*, application/pdf").build()
}
suspend fun externalMediaBytes(reference: NativeReference.ExternalMedia): NativeMediaBytes = withContext(Dispatchers.IO) {
    var url = reference.url
    repeat(4) { redirect ->
        require(url.scheme == "https" && url.username.isBlank() && url.password.isBlank()) { "External previews require credential-free HTTPS" }
        val call = externalMediaHttp.newCall(externalMediaRequest(url))
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
        try { call.execute().use { response ->
            if (response.code in setOf(301, 302, 303, 307, 308)) { require(redirect < 3) { "External preview redirected too many times" }; url = url.resolve(response.header("Location").orEmpty()) ?: error("External preview redirect is invalid") }
            else {
                require(response.isSuccessful) { "External preview failed (${response.code})" }
                val body = response.body ?: error("External preview is empty")
                val mime = body.contentType()?.toString()?.substringBefore(';').orEmpty()
                require(mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/") || mime == "application/pdf" || mime == "application/octet-stream" || mime.isBlank()) { "External response is not a supported media type" }
                require(body.contentLength() <= 32L * 1024 * 1024) { "External media exceeds the 32 MiB preview limit" }
                val output = java.io.ByteArrayOutputStream(); val input = body.byteStream(); val buffer = ByteArray(8192)
                while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 32 * 1024 * 1024) { "External media exceeds the 32 MiB preview limit" }; output.write(buffer, 0, count) }
                return@withContext NativeMediaBytes(output.toByteArray(), mime)
            }
        } } finally { cancellation.cancel() }
    }
    error("External preview redirect could not resolve")
}
