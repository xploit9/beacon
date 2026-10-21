// Copyright (c) 2026 Axiom Labs
// SPDX-License-Identifier: MIT
// Adapted from Hermes Relay app/src/main/kotlin/com/hermesandroid/relay/network/upstream/NativeDashboardAuth.kt.
package dev.hermes.android.data

import android.app.Activity
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.view.WindowCompat
import dev.hermes.android.core.Scope
import dev.hermes.android.core.trustedOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.runInterruptible
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class NativeAuthException(message: String, val sessionExpired: Boolean = false, cause: Throwable? = null) : IOException(message, cause)
internal class NativeAuthExpiredException : IOException("Sign-in expired or couldn't be refreshed. Sign in again; your drafts and chats are safe.")

class NativeAuth internal constructor(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val random: SecureRandom = SecureRandom(),
) {
    private val baseUrl = trustedOrigin(baseUrl)

    suspend fun authorize(context: Context): NativeBearerRecord = withContext(Dispatchers.IO) {
        val verifier = urlSafeRandom(32)
        val state = urlSafeRandom(24)
        val challenge = pkceChallenge(verifier)
        var pending: PendingNativeAuthorization? = null
        try {
            ServerSocket().use { server ->
                server.reuseAddress = false
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
                server.soTimeout = CALLBACK_TIMEOUT_MS
                val redirect = "http://127.0.0.1:${server.localPort}$CALLBACK_PATH"
                val url = "$baseUrl/auth/native/authorize".toHttpUrl().newBuilder()
                    .addQueryParameter("code_challenge", challenge)
                    .addQueryParameter("code_challenge_method", "S256")
                    .addQueryParameter("redirect_uri", redirect)
                    .addQueryParameter("state", state)
                    .build()
                val flow = PendingNativeAuthorization(
                    closeListener = { runCatching { server.close() } },
                    schedule = { delay, action -> Handler(Looper.getMainLooper()).postDelayed({ action() }, delay) },
                    initiallyForeground = context is Activity,
                )
                if (!pendingAuthorization.compareAndSet(null, flow)) {
                    throw NativeAuthException("Hermes sign-in is already in progress. Try again.")
                }
                pending = flow
                try {
                    val application = context.applicationContext as? Application
                    var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
                    val watcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { runCatching { server.close() } }
                    }
                    try {
                        withContext(Dispatchers.Main.immediate) {
                            application?.let { app ->
                                NativeAuthActivityLifecycleCallbacks(context as? Activity, flow).also { callbacks ->
                                    lifecycleCallbacks = callbacks
                                    app.registerActivityLifecycleCallbacks(callbacks)
                                }
                            }
                            val launcher = customTabLauncher
                            if (launcher != null) launcher(context, Uri.parse(url.toString()))
                            else launchNativeAuthorization(context, Uri.parse(url.toString()))
                        }
                        val code = withTimeout(CALLBACK_TIMEOUT_MS.toLong()) {
                            runInterruptible { receiveCallback(server, state) }
                        }
                        exchangeWhenForeground(flow, code, verifier)
                    } finally {
                        watcher.cancel()
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            lifecycleCallbacks?.let { application?.unregisterActivityLifecycleCallbacks(it) }
                        }
                    }
                } finally {
                    pendingAuthorization.compareAndSet(flow, null)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw mapSignInFailure(error, pending?.cancelledOnResume == true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: NativeAuthException) {
            throw error
        } catch (error: Exception) {
            throw mapSignInFailure(error, pending?.cancelledOnResume == true)
        }
    }

    private fun launchNativeAuthorization(context: Context, uri: Uri) {
        val dark = if (context is Activity) {
            !WindowCompat.getInsetsController(context.window, context.window.decorView).isAppearanceLightStatusBars
        } else {
            (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }
        val colorScheme = if (dark) CustomTabsIntent.COLOR_SCHEME_DARK else CustomTabsIntent.COLOR_SCHEME_LIGHT
        val colors = CustomTabColorSchemeParams.Builder()
            .setToolbarColor(if (dark) DARK_TAB_TOOLBAR else LIGHT_TAB_TOOLBAR)
            .setNavigationBarColor(if (dark) DARK_TAB_NAVIGATION else LIGHT_TAB_NAVIGATION)
            .build()
        val customTab = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setUrlBarHidingEnabled(false)
            .setColorScheme(colorScheme)
            .setColorSchemeParams(colorScheme, colors)
            .build()
        if (context !is Activity) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            customTab.launchUrl(context, uri)
        } catch (_: ActivityNotFoundException) {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    internal suspend fun refresh(
        current: NativeBearerRecord,
        latest: () -> NativeBearerRecord?,
        persist: (NativeBearerRecord, NativeBearerRecord) -> Boolean,
    ): NativeBearerRecord = withContext(Dispatchers.IO) {
        if (current.origin != baseUrl || current.refreshToken.isBlank()) throw NativeAuthException("No Hermes refresh credential is available")
        val lock = refreshLocks.computeIfAbsent(Scope(current.origin, current.owner, current.profile).key) { Mutex() }
        lock.withLock {
            val winner = latest()?.takeIf { it.origin == current.origin && it.owner == current.owner && it.profile == current.profile }
            if (winner != null && winner != current) return@withLock winner
            val refreshed = tokenResponse(
                "/auth/native/refresh",
                JSONObject().put("refresh_token", current.refreshToken).put("provider", current.provider),
                current,
            )
            if (!persist(current, refreshed)) throw NativeAuthException("Sign-in changed while refreshing. Sign in again.")
            refreshed
        }
    }

    private fun exchange(code: String, verifier: String): NativeBearerRecord = tokenResponse(
        "/auth/native/token",
        JSONObject().put("code", code).put("code_verifier", verifier),
        null,
    )

    internal suspend fun exchangeWhenForeground(
        pending: PendingNativeAuthorization,
        code: String,
        verifier: String,
        foregroundTimeoutMs: Long = FOREGROUND_TIMEOUT_MS,
    ): NativeBearerRecord {
        awaitForeground(pending, foregroundTimeoutMs)
        try {
            return exchange(code, verifier)
        } catch (error: IOException) {
            // NativeAuthException also represents HTTP and shape errors, which are not transport failures to retry.
            if (pending.isForeground || (error is NativeAuthException && error.cause !is IOException)) throw error
            awaitForeground(pending, foregroundTimeoutMs)
            return exchange(code, verifier)
        }
    }

    private suspend fun awaitForeground(pending: PendingNativeAuthorization, timeoutMs: Long) {
        if (withTimeoutOrNull(timeoutMs) { pending.awaitForeground(); true } != true) {
            throw NativeAuthException(TIMEOUT_MESSAGE)
        }
    }

    private fun tokenResponse(path: String, payload: JSONObject, previous: NativeBearerRecord?): NativeBearerRecord {
        val request = Request.Builder().url("$baseUrl$path".toHttpUrl())
            .post(payload.toString().toRequestBody(JSON_MEDIA)).build()
        return try {
            client.newCall(request).execute().use { response ->
                if (response.code == 401 && previous != null) throw NativeAuthException("Hermes sign-in expired. Sign in again.", sessionExpired = true)
                val body = response.body?.source()?.let { source -> source.request(MAX_RESPONSE_BYTES + 1L); source.buffer.readByteArray(minOf(source.buffer.size, MAX_RESPONSE_BYTES + 1L)) } ?: ByteArray(0)
                if (body.size > MAX_RESPONSE_BYTES) throw NativeAuthException(GENERIC_ERROR_MESSAGE)
                val json = runCatching { JSONObject(String(body, StandardCharsets.UTF_8)) }
                    .getOrElse { throw NativeAuthException(GENERIC_ERROR_MESSAGE, cause = it) }
                if (!response.isSuccessful) {
                    val expired = response.code == 401 && json.optString("error") == "session_expired"
                    throw NativeAuthException(
                        if (expired) "Hermes sign-in expired. Sign in again." else GENERIC_ERROR_MESSAGE,
                        sessionExpired = expired,
                    )
                }
                val access = json.optString("access_token")
                val refresh = json.optString("refresh_token")
                val owner = json.optString("user_id").trim()
                if (!json.optString("token_type").equals("Bearer", ignoreCase = true) || access.isBlank() || access.any { it.isWhitespace() || it.isISOControl() } || refresh.isBlank() || owner.isBlank()) {
                    throw NativeAuthException(GENERIC_ERROR_MESSAGE)
                }
                NativeBearerRecord(
                    accessToken = access,
                    refreshToken = refresh,
                    expiresAt = json.optLong("expires_at"),
                    origin = baseUrl,
                    owner = owner,
                    profile = previous?.profile ?: "default",
                    provider = json.optString("provider", previous?.provider.orEmpty()),
                    issuedAt = System.currentTimeMillis() / 1000,
                )
            }
        } catch (error: NativeAuthException) {
            throw error
        } catch (error: IOException) {
            throw mapSignInFailure(error)
        }
    }

    internal fun receiveCallback(server: ServerSocket, expectedState: String): String {
        while (!server.isClosed) {
            val callback = server.accept()
            try {
                val code = try {
                    callback.readCallback(expectedState)
                } catch (error: NativeAuthException) {
                    callback.respondBestEffort(400, "Bad Request", body = DECLINED_MESSAGE)
                    throw error
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                if (code == null) {
                    callback.respondBestEffort(400, "Bad Request", body = INVALID_CALLBACK_MESSAGE)
                } else {
                    pendingAuthorization.get()?.callbackCaptured()
                    callback.respondBestEffort(
                        302,
                        "Found",
                        location = AUTH_RETURN_URI,
                        contentType = "text/html; charset=utf-8",
                        body = CALLBACK_HTML,
                    )
                    return code
                }
            } finally {
                runCatching { callback.close() }
            }
        }
        throw IOException("Hermes sign-in callback listener was closed")
    }

    private fun Socket.readCallback(expectedState: String): String? {
        if (!inetAddress.isLoopbackAddress) return null
        soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(getInputStream(), StandardCharsets.US_ASCII))
        val request = readBoundedLine(reader, MAX_HEADER_LINE_CHARS)?.split(' ') ?: return null
        if (request.size != 3 || request[0] != "GET" || request[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return null
        var headersComplete = false
        for (index in 0 until MAX_HEADER_LINES) {
            val header = readBoundedLine(reader, MAX_HEADER_LINE_CHARS) ?: return null
            if (header.isEmpty()) {
                headersComplete = true
                break
            }
            if (':' !in header || header.substringBefore(':').isBlank() || header.any { it.isISOControl() && it != '\t' }) return null
        }
        if (!headersComplete) return null
        return callbackCode(request[1], expectedState)
    }

    private fun Socket.respondBestEffort(
        status: Int,
        reason: String,
        location: String? = null,
        contentType: String = "text/plain; charset=utf-8",
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            location?.let { append("Location: $it\r\n") }
            append("Content-Type: $contentType\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${bytes.size}\r\n\r\n")
        }.toByteArray(StandardCharsets.US_ASCII)
        runCatching {
            getOutputStream().use { output -> output.write(headers); output.write(bytes); output.flush() }
        }
    }

    private fun urlSafeRandom(size: Int): String = ByteArray(size).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun readBoundedLine(reader: BufferedReader, limit: Int): String? {
        val line = StringBuilder()
        while (true) {
            val value = reader.read()
            if (value < 0) return line.toString().takeIf(String::isNotEmpty)
            if (value == '\n'.code) return line.toString().trimEnd('\r')
            require(line.length < limit) { "Native sign-in callback headers were too large" }
            line.append(value.toChar())
        }
    }

    companion object {
        private const val CALLBACK_PATH = "/callback"
        private const val CALLBACK_TIMEOUT_MS = 5 * 60 * 1000
        private const val FOREGROUND_TIMEOUT_MS = 60_000L
        private const val MAX_RESPONSE_BYTES = 32 * 1024
        private const val MAX_HEADER_LINE_CHARS = 8_192
        private const val MAX_HEADER_LINES = 32
        private const val AUTH_RETURN_URI = "${dev.hermes.android.BuildConfig.APPLICATION_ID}://auth/complete"
        private const val TIMEOUT_MESSAGE = "Hermes sign-in timed out. Try again."
        private const val NETWORK_ERROR_MESSAGE = "Couldn't reach Hermes. Check Tailscale or VPN, then try again."
        private const val GENERIC_ERROR_MESSAGE = "Sign-in didn't finish. Try again."
        private const val RESUME_CANCEL_MESSAGE = "Sign-in wasn't finished. Tap Sign in with Hermes to try again."
        private const val DECLINED_MESSAGE = "Hermes sign-in was declined. Try again."
        private const val INVALID_CALLBACK_MESSAGE = "This sign-in callback was not accepted. Return to Beacon and try again."
        private const val CALLBACK_HTML = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light dark"><title>Sign-in complete</title><style>:root{color-scheme:light dark;font:16px system-ui,sans-serif;background:#0C1014;color:#F4EEE5}body{margin:0;min-height:100vh;display:grid;place-items:center}main{max-width:26rem;padding:2rem}a{display:inline-block;margin-top:1rem;padding:.8rem 1rem;border-radius:.8rem;background:#E7B579;color:#0C1014;text-decoration:none;font-weight:600}@media(prefers-color-scheme:light){:root{background:#F4EEE5;color:#20272D}a{background:#80511D;color:#F4EEE5}}</style></head><body><main><h1>Sign-in complete</h1><p>Return to Beacon to finish signing in.</p><a href="$AUTH_RETURN_URI">Return to Beacon</a></main></body></html>"""
        private val DARK_TAB_TOOLBAR = Color.rgb(21, 28, 34)
        private val LIGHT_TAB_TOOLBAR = Color.rgb(236, 231, 222)
        private val DARK_TAB_NAVIGATION = Color.rgb(12, 16, 20)
        private val LIGHT_TAB_NAVIGATION = Color.rgb(244, 238, 229)
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val refreshLocks = ConcurrentHashMap<String, Mutex>()
        private val pendingAuthorization = AtomicReference<PendingNativeAuthorization?>()

        @Volatile internal var customTabLauncher: ((Context, Uri) -> Unit)? = null

        internal fun onActivityResumed() {
            pendingAuthorization.get()?.onActivityResumed()
        }

        internal fun mapSignInFailure(error: Exception, cancelledOnResume: Boolean = false): NativeAuthException = when {
            error is NativeAuthException -> error
            cancelledOnResume -> NativeAuthException(RESUME_CANCEL_MESSAGE, cause = error)
            error is TimeoutCancellationException || error is SocketTimeoutException -> NativeAuthException(TIMEOUT_MESSAGE, cause = error)
            error is IOException -> NativeAuthException(NETWORK_ERROR_MESSAGE, cause = error)
            else -> NativeAuthException(GENERIC_ERROR_MESSAGE, cause = error)
        }

        internal fun pkceChallenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)),
        )

        internal fun callbackCode(target: String, expectedState: String): String {
            require(target.startsWith("$CALLBACK_PATH?") && '#' !in target)
            val callback = "http://127.0.0.1$target".toHttpUrl()
            require(callback.host == "127.0.0.1" && callback.encodedPath == CALLBACK_PATH)
            require(callback.queryParameterValues("state") == listOf(expectedState)) { "Native sign-in callback state did not match" }
            require(callback.queryParameterValues("error").size <= 1)
            if (callback.queryParameterValues("error").isNotEmpty()) throw NativeAuthException(DECLINED_MESSAGE)
            require(callback.queryParameterValues("code").size == 1)
            return callback.queryParameter("code")?.takeIf { code -> code.length in 1..4_096 && code.none { it.isISOControl() } }
                ?: throw IllegalArgumentException("Native sign-in callback did not include a code")
        }
    }
}

internal class PendingNativeAuthorization(
    private val closeListener: () -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
    initiallyForeground: Boolean = false,
) {
    private val lock = Any()
    private val resumeCheckScheduled = AtomicBoolean()
    private val foreground = MutableStateFlow(initiallyForeground)
    private var codeCaptured = false
    @Volatile var cancelledOnResume = false
        private set

    val isForeground: Boolean get() = foreground.value

    suspend fun awaitForeground() {
        foreground.first { it }
    }

    fun onActivityPaused() {
        foreground.value = false
    }

    fun callbackCaptured() {
        synchronized(lock) {
            codeCaptured = true
            cancelledOnResume = false
        }
        runCatching(closeListener)
    }

    fun onActivityResumed() {
        foreground.value = true
        if (!resumeCheckScheduled.compareAndSet(false, true)) return
        schedule(RESUME_GRACE_MS) {
            synchronized(lock) {
                if (!codeCaptured) cancelledOnResume = true
            }
        }
    }

    companion object {
        const val RESUME_GRACE_MS = 1_500L
    }
}

private class NativeAuthActivityLifecycleCallbacks(
    initialActivity: Activity?,
    private val pending: PendingNativeAuthorization,
) : Application.ActivityLifecycleCallbacks {
    private val resumedActivities = mutableSetOf<Activity>().apply { initialActivity?.let(::add) }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) {
        resumedActivities += activity
    }
    override fun onActivityPaused(activity: Activity) {
        resumedActivities -= activity
        if (resumedActivities.isEmpty()) pending.onActivityPaused()
    }
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
