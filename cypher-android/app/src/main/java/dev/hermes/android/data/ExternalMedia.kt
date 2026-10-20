package dev.hermes.android.data

import dev.hermes.android.core.NativeReference
import kotlinx.coroutines.*
import okhttp3.*
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class NativeMediaBytes(val bytes: ByteArray, val mime: String = "")
/** This client has no owner cookies, CSRF, profile headers, authentication or cache. */
internal fun isPublicExternalMediaAddress(address: InetAddress): Boolean {
    if (address is Inet6Address) {
        val bytes = address.address
        if (bytes.take(10).all { it == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()) {
            return isPublicExternalMediaAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (bytes[0] == 0.toByte() && bytes[1] == 0x64.toByte() && bytes[2] == 0xff.toByte() && bytes[3] == 0x9b.toByte() && bytes.sliceArray(4..11).all { it == 0.toByte() }) {
            return isPublicExternalMediaAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if ((bytes[0].toInt() and 0xfe) == 0xfc) return false
    }
    val ipv4 = address.address
    if (ipv4.size == 4 && ipv4[0] == 100.toByte() && (ipv4[1].toInt() and 0xff) in 64..127) return false
    return !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress &&
        !address.isSiteLocalAddress && !address.isMulticastAddress
}

private val ipv4Literal = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
private fun requirePublicExternalMediaHost(url: HttpUrl) {
    val host = url.host
    if (':' in host || ipv4Literal.matches(host)) {
        require(isPublicExternalMediaAddress(InetAddress.getByName(host))) { "External previews cannot use private IP addresses" }
    }
}

internal fun externalMediaDns(system: Dns = Dns.SYSTEM): Dns = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        system.lookup(hostname).filter(::isPublicExternalMediaAddress).ifEmpty {
            throw UnknownHostException("No public address for $hostname")
        }
}

private val externalMediaHttp = OkHttpClient.Builder().dns(externalMediaDns()).cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).followRedirects(false).followSslRedirects(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()
internal fun externalMediaRequest(url: HttpUrl): Request {
    require(url.scheme == "https" && url.username.isBlank() && url.password.isBlank()) { "External previews require credential-free HTTPS" }
    requirePublicExternalMediaHost(url)
    return Request.Builder().url(url).header("Accept", "image/*, audio/*, video/*, application/pdf").build()
}
suspend fun externalMediaBytes(reference: NativeReference.ExternalMedia): NativeMediaBytes = withContext(Dispatchers.IO) {
    var url = reference.url
    repeat(4) { redirect ->
        val request = externalMediaRequest(url)
        val call = externalMediaHttp.newCall(request)
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
