package dev.hermes.android.data

import android.content.Context
import android.util.AtomicFile
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.Scope
import dev.hermes.android.core.trustedOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import javax.net.ssl.SSLException
import kotlin.concurrent.withLock as withThreadLock

internal data class IncidentAuthority(val scope: Scope, val admission: String, val epoch: Long) {
    override fun toString() = "IncidentAuthority(<redacted>)"
}

internal fun incidentErrorCode(error: Throwable): String? = when (error) {
    is TimeoutCancellationException, is SocketTimeoutException -> "timeout"
    is CancellationException -> null
    is ApiFailure -> if (error.status in 400..599) "http_${error.status}" else "invalid_response"
    is UnknownHostException -> "dns"
    is SSLException -> "tls"
    is IOException -> "network"
    else -> "unknown"
}

internal fun incidentCrashHandler(previous: Thread.UncaughtExceptionHandler?, record: () -> Unit): Thread.UncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, error ->
        try { record() } catch (_: Throwable) { }
        previous?.uncaughtException(thread, error)
    }

private const val INCIDENT_BINDING = "beacon-incidents-v1"
private const val INCIDENT_LIMIT = 64
private const val INCIDENT_BYTES = 128 * 1024
private val INCIDENT_COMPONENTS = setOf("voice", "app", "gateway", "plugin", "sync", "tools", "memory", "network", "chat", "settings", "updates", "vault")
private val INCIDENT_OPERATIONS = setOf("connect", "startup", "greeting", "capture", "playout", "context", "discover_tools", "invoke_tool",
    "lookup", "sync", "request", "history", "auth", "render", "load", "save", "send", "update", "fill", "signup", "payment")
private val INCIDENT_MUTATIONS = setOf("send", "save", "update", "fill", "signup", "payment", "invoke_tool")
private val INCIDENT_CODES = setOf("timeout", "network", "dns", "tls", "invalid_response", "provider_error", "connection_closed", "queue_overflow",
    "unavailable", "hermes_updating", "admission_rejected", "memory_lookup_busy", "memory_lookup_timeout", "memory_lookup_failed", "tool_http_failure",
    "unknown", "outcome_unknown", "submission_unconfirmed", "process_crash", "process_anr", "process_low_memory", "process_resource_limit")
private val INCIDENT_PROVIDERS = setOf("gemini", "grok", "openai", "native")

private fun incidentOperationCode(operation: String, error: Throwable): String? =
    incidentErrorCode(error)?.let { if (operation in INCIDENT_MUTATIONS) "outcome_unknown" else it }

private fun incidentUuid(value: String) = runCatching { value.length == 36 && UUID.fromString(value).toString() == value }.getOrDefault(false)
private fun incidentField(value: String, max: Int): Boolean {
    if (value.isBlank() || value.length > max || value.any { it.isISOControl() }) return false
    return runCatching {
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
        true
    }.getOrDefault(false)
}
private fun incidentAuthority(value: IncidentAuthority): Boolean = value.epoch >= 0 && incidentUuid(value.admission) && value.scope.session.isEmpty() &&
    incidentField(value.scope.owner, 256) && incidentField(value.scope.profile, 80) && incidentField(value.scope.nativeProvider, 256) &&
    value.scope.origin.length <= 2048 && value.scope.origin == value.scope.origin.trim() &&
    runCatching { trustedOrigin(value.scope.origin);true }.getOrDefault(false)

private fun incidentExitCode(reason: Int): String? = when (reason) {
    4, 5 -> "process_crash"
    6 -> "process_anr"
    3 -> "process_low_memory"
    9 -> "process_resource_limit"
    else -> null
}

internal class IncidentJournal(
    private val read: () -> ByteArray?,
    private val write: (ByteArray) -> Boolean,
    private val encrypt: (ByteArray, String) -> ByteArray,
    private val decrypt: (ByteArray, String) -> ByteArray,
) {
    private val gate = ReentrantLock()
    private val drainGate = Mutex()
    private val authorityLock = Any()
    private var revision = 0L
    @Volatile private var active: IncidentAuthority? = null
    private var firstActivation = true
    private var exitAuthority: IncidentAuthority? = null
    private var exitVersion: Int? = null
    private var pendingExit: Pair<String, Long>? = null

    private fun binding(authority: IncidentAuthority, appVersion: Int? = null) = JSONObject()
        .put("origin", authority.scope.origin).put("owner", authority.scope.owner).put("profile", authority.scope.profile)
        .put("auth_provider", authority.scope.nativeProvider).put("admission", authority.admission)
        .apply { appVersion?.let { put("app_version_code", it) } }

    private fun validBinding(value: JSONObject): Boolean {
        val names = value.keys().asSequence().toSet()
        if (names !in setOf(setOf("origin", "owner", "profile", "auth_provider", "admission"),
                setOf("origin", "owner", "profile", "auth_provider", "admission", "app_version_code"))) return false
        val origin = value.opt("origin") as? String ?: return false
        val owner = value.opt("owner") as? String ?: return false
        val profile = value.opt("profile") as? String ?: return false
        val provider = value.opt("auth_provider") as? String ?: return false
        val admission = value.opt("admission") as? String ?: return false
        if (value.has("app_version_code") && !validVersion(value.opt("app_version_code"))) return false
        return incidentAuthority(IncidentAuthority(Scope(origin, owner, profile, nativeProvider = provider), admission, 0))
    }

    private fun matches(value: JSONObject?, authority: IncidentAuthority): Boolean = value != null && validBinding(value) &&
        value.optString("origin") == authority.scope.origin && value.optString("owner") == authority.scope.owner &&
        value.optString("profile") == authority.scope.profile && value.optString("auth_provider") == authority.scope.nativeProvider &&
        value.optString("admission") == authority.admission

    private fun validVersion(value: Any?): Boolean = when (value) {
        is Int -> value > 0
        is Long -> value in 1..Int.MAX_VALUE.toLong()
        else -> false
    }

    private fun validIncident(value: JSONObject): Boolean {
        val names = value.keys().asSequence().toSet()
        if (!names.containsAll(setOf("component", "operation", "error_code")) ||
            (names - setOf("component", "operation", "error_code", "provider", "app_version_code")).isNotEmpty()) return false
        val component = value.opt("component") as? String ?: return false
        val operation = value.opt("operation") as? String ?: return false
        val code = value.opt("error_code") as? String ?: return false
        if (component !in INCIDENT_COMPONENTS || operation !in INCIDENT_OPERATIONS ||
            code !in INCIDENT_CODES && !code.matches(Regex("http_[45][0-9]{2}"))) return false
        if (value.has("provider") && (value.opt("provider") as? String) !in INCIDENT_PROVIDERS) return false
        return !value.has("app_version_code") || validVersion(value.opt("app_version_code"))
    }

    private fun load(): JSONObject? {
        return try {
            val stored = read() ?: return JSONObject().put("version", 1).put("active", JSONObject.NULL).put("last_exit", 0L).put("items", JSONArray())
            if (stored.size > INCIDENT_BYTES + APP_CIPHER_OVERHEAD) return null
            val clear = decrypt(stored, INCIDENT_BINDING)
            if (clear.size > INCIDENT_BYTES) return null
            val state = JSONObject(String(clear, Charsets.UTF_8))
            if (state.keys().asSequence().toSet() != setOf("version", "active", "last_exit", "items") || state.opt("version") != 1) return null
            val exit = state.opt("last_exit")
            if (exit !is Int && exit !is Long || (exit as Number).toLong() < 0) return null
            val marker = state.opt("active")
            if (marker != JSONObject.NULL && (marker !is JSONObject || !validBinding(marker))) return null
            val items = state.optJSONArray("items") ?: return null
            if (items.length() > INCIDENT_LIMIT) return null
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: return null
                if (item.keys().asSequence().toSet() != setOf("incident_id", "binding", "incident")) return null
                if (!incidentUuid(item.opt("incident_id") as? String ?: return null)) return null
                if (!validBinding(item.optJSONObject("binding") ?: return null) || !validIncident(item.optJSONObject("incident") ?: return null)) return null
            }
            state
        } catch (_: Exception) { null }
    }

    private fun save(state: JSONObject): Boolean {
        return try {
            val clear = state.toString().toByteArray(Charsets.UTF_8)
            if (clear.size > INCIDENT_BYTES) return false
            val sealed = encrypt(clear, INCIDENT_BINDING)
            sealed.size <= INCIDENT_BYTES + APP_CIPHER_OVERHEAD && write(sealed)
        } catch (_: Exception) { false }
    }

    private fun append(state: JSONObject, authority: IncidentAuthority, incident: JSONObject): Boolean {
        val items = state.getJSONArray("items")
        val existing = (0 until items.length()).any { index -> items.getJSONObject(index).let { item ->
            matches(item.getJSONObject("binding"), authority) && item.getJSONObject("incident").toString() == incident.toString()
        } }
        if (existing) return true
        if (items.length() >= INCIDENT_LIMIT) return false
        items.put(JSONObject().put("incident_id", UUID.randomUUID().toString()).put("binding", binding(authority)).put("incident", incident))
        return true
    }

    private fun makeIncident(component: String, operation: String, code: String, provider: String?, appVersion: Int?): JSONObject? {
        val item = JSONObject().put("component", component).put("operation", operation).put("error_code", code)
        provider?.let { item.put("provider", it) };appVersion?.let { item.put("app_version_code", it) }
        return item.takeIf(::validIncident)
    }

    fun activate(authority: IncidentAuthority, appVersion: Int? = null): Boolean {
        if (!incidentAuthority(authority) || appVersion != null && appVersion <= 0) return false
        val ticket = synchronized(authorityLock) { revision++;active = null;revision }
        return gate.withThreadLock {
            val state = load() ?: return@withThreadLock false
            val old = state.optJSONObject("active")
            val restored = if (firstActivation) matches(old, authority) else exitAuthority?.let {
                it.scope == authority.scope && it.admission == authority.admission
            } == true
            val priorVersion = if (firstActivation) old?.opt("app_version_code")?.takeIf(::validVersion)?.let { (it as Number).toInt() } else exitVersion
            val exit = pendingExit
            if (restored && exit != null && exit.second > state.getLong("last_exit")) {
                val incident = makeIncident("app", "startup", exit.first, null, priorVersion) ?: return@withThreadLock false
                if (append(state, authority, incident)) state.put("last_exit", exit.second)
            }
            state.put("active", binding(authority, appVersion))
            if (!save(state)) return@withThreadLock false
            synchronized(authorityLock) {
                if (revision != ticket) false else {
                    active = authority
                    exitAuthority = if (restored) authority else null
                    exitVersion = priorVersion
                    firstActivation = false
                    if (!restored || exit != null && exit.second <= state.getLong("last_exit")) pendingExit = null
                    true
                }
            }
        }
    }

    fun deactivate() { synchronized(authorityLock) { revision++;active = null } }

    private fun storePendingExit() {
        val exit = pendingExit ?: return
        val authority = exitAuthority ?: return
        if (active != authority) return
        val state = load() ?: return
        if (!matches(state.optJSONObject("active"), authority)) return
        if (exit.second <= state.getLong("last_exit")) { pendingExit = null;return }
        val incident = makeIncident("app", "startup", exit.first, null, exitVersion) ?: return
        if (append(state, authority, incident)) {
            state.put("last_exit", exit.second)
            if (save(state)) pendingExit = null
        }
    }

    fun noteProcessExit(reason: Int, timestamp: Long) {
        val code = incidentExitCode(reason) ?: return
        if (timestamp <= 0) return
        gate.withThreadLock {
            pendingExit = code to timestamp
            storePendingExit()
        }
    }

    fun record(authority: IncidentAuthority, component: String, operation: String, error: Throwable, provider: String? = null, appVersion: Int? = null): Boolean {
        val code = incidentOperationCode(operation, error) ?: return false
        return recordCode(authority, component, operation, code, provider, appVersion)
    }

    internal fun recordCode(authority: IncidentAuthority, component: String, operation: String, code: String, provider: String? = null, appVersion: Int? = null): Boolean {
        if (!incidentAuthority(authority) || active != authority) return false
        val incident = makeIncident(component, operation, code, provider, appVersion) ?: return false
        return gate.withThreadLock {
            if (active != authority) return@withThreadLock false
            val state = load() ?: return@withThreadLock false
            if (!matches(state.optJSONObject("active"), authority) || !append(state, authority, incident)) return@withThreadLock false
            save(state)
        }
    }

    suspend fun drain(authority: IncidentAuthority, current: () -> Boolean, submit: suspend (JSONObject) -> JSONObject): Int = drainGate.withLock {
        if (!incidentAuthority(authority) || active != authority) return@withLock 0
        val ids = gate.withThreadLock {
            val state = load() ?: return@withThreadLock emptyList()
            val items = state.getJSONArray("items")
            (0 until items.length()).map { items.getJSONObject(it) }.filter { matches(it.getJSONObject("binding"), authority) }
                .map { it.getString("incident_id") }
        }
        var delivered = 0
        for (id in ids) {
            if (active != authority || !runCatching(current).getOrDefault(false)) break
            val body = gate.withThreadLock {
                val state = load() ?: return@withThreadLock null
                val items = state.getJSONArray("items")
                val item = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull {
                    it.getString("incident_id") == id && matches(it.getJSONObject("binding"), authority)
                } ?: return@withThreadLock null
                JSONObject().put("incident_id", id).put("incident", JSONObject(item.getJSONObject("incident").toString()))
            } ?: break
            val answer = try { submit(body) } catch (error: CancellationException) { throw error } catch (_: Exception) { break }
            if (answer.opt("incident_id") != id || answer.opt("recorded") != true || active != authority || !runCatching(current).getOrDefault(false)) break
            val removed = gate.withThreadLock {
                if (active != authority) return@withThreadLock false
                val state = load() ?: return@withThreadLock false
                val items = state.getJSONArray("items")
                val index = (0 until items.length()).firstOrNull { items.getJSONObject(it).let { item ->
                    item.getString("incident_id") == id && matches(item.getJSONObject("binding"), authority)
                } } ?: return@withThreadLock false
                items.remove(index)
                save(state)
            }
            if (!removed) break
            delivered++
        }
        if (delivered > 0) gate.withThreadLock { storePendingExit() }
        delivered
    }
}

/** Local metadata receipts; credentials, provider text and error messages never enter the journal. */
class NativeIncidents(context: Context, private val cipher: AppCipher) {
    private val app = context.applicationContext
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = Semaphore(32)
    private val crashing = AtomicBoolean()
    private val activationGate = Mutex()
    private val bindingLock = Any()
    private val file by lazy { AtomicFile(File(app.noBackupFilesDir, "beacon-incidents-v1")) }
    private val journal by lazy { IncidentJournal(::read, ::write, cipher::encrypt, cipher::decrypt) }
    private val version = BuildConfig.VERSION_CODE.takeIf { it > 0 }
    private data class Bound(val client: BeaconClient, val authority: IncidentAuthority)
    @Volatile private var bound: Bound? = null
    private var revision = 0L

    private fun read(): ByteArray? = try {
        file.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(4096)
            while (true) {
                val count = input.read(chunk)
                if (count < 0) break
                if (output.size().toLong() + count > INCIDENT_BYTES + APP_CIPHER_OVERHEAD) throw IOException("Incident queue exceeds its bound")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
    } catch (_: FileNotFoundException) {
        val base = file.baseFile
        if (base.exists() || File(base.path + ".bak").exists() || File(base.path + ".new").exists()) throw IOException("Incident queue unavailable")
        null
    }

    private fun write(value: ByteArray): Boolean {
        var output: FileOutputStream? = null
        return try {
            val stream = file.startWrite()
            output = stream
            stream.write(value)
            file.finishWrite(stream)
            output = null
            read()?.contentEquals(value) == true
        } catch (_: Exception) {
            output?.let { runCatching { file.failWrite(it) } }
            false
        }
    }

    private fun authority(client: BeaconClient): IncidentAuthority? {
        return try {
            val scope = client.scope.copy(session = "")
            val epoch = client.nativeAuthorityVersion() ?: return null
            val admission = client.incidentAdmission() ?: return null
            IncidentAuthority(scope, admission, epoch).takeIf {
                incidentAuthority(it) && client.scope.copy(session = "") == scope && client.nativeAuthorityVersion() == epoch
            }
        } catch (_: Exception) { null }
    }

    private fun current(value: Bound, client: BeaconClient = value.client): Boolean {
        if (bound !== value || authority(client) != value.authority || client !== value.client && authority(value.client) != value.authority) return false
        return bound === value && client.scope.copy(session = "") == value.authority.scope &&
            client.nativeAuthorityVersion() == value.authority.epoch && value.client.scope.copy(session = "") == value.authority.scope &&
            value.client.nativeAuthorityVersion() == value.authority.epoch
    }

    /** Called after the selected account/profile has been verified; no upload runs during activation. */
    suspend fun activate(client: BeaconClient): Boolean = withContext(Dispatchers.IO) {
        val ticket = synchronized(bindingLock) { revision++;bound = null;journal.deactivate();revision }
        activationGate.withLock {
            if (synchronized(bindingLock) { revision != ticket }) return@withLock false
            val captured = authority(client) ?: return@withLock false
            if (!journal.activate(captured, version) || authority(client) != captured) return@withLock false
            synchronized(bindingLock) {
                if (revision != ticket) false else { bound = Bound(client, captured);true }
            }
        }
    }

    /** Called before changing account or profile; pending encrypted receipts remain isolated. */
    fun deactivate() { synchronized(bindingLock) { revision++;bound = null;journal.deactivate() } }

    /** A caught failure is reduced to a fixed error code before it leaves the calling thread. */
    fun record(client: BeaconClient, component: String, operation: String, error: Throwable, provider: String? = null) {
        val code = incidentOperationCode(operation, error) ?: return
        if (component !in INCIDENT_COMPONENTS || operation !in INCIDENT_OPERATIONS || provider != null && provider !in INCIDENT_PROVIDERS) return
        val captured = bound ?: return
        if (!pending.tryAcquire()) return
        try {
            work.launch {
                try { if (current(captured, client)) journal.recordCode(captured.authority, component, operation, code, provider, version) }
                catch (_: Exception) { }
                finally { pending.release() }
            }
        } catch (_: Exception) { pending.release() }
    }

    /** Replays only idempotent metadata receipts after the plugin advertises support. */
    suspend fun drain(client: BeaconClient): Int = withContext(Dispatchers.IO) {
        val captured = bound ?: return@withContext 0
        if (!current(captured, client)) return@withContext 0
        val capabilities = withTimeoutOrNull(5000) {
            try { client.nativeRequest("/api/plugins/beacon/mobile/healing/capabilities") }
            catch (error: CancellationException) { throw error } catch (_: Exception) { null }
        } ?: return@withContext 0
        if (capabilities.opt("supported") != true || !current(captured, client)) return@withContext 0
        journal.drain(captured.authority, { current(captured, client) }) { payload ->
            withTimeoutOrNull(10000) { client.nativeRequest("/api/plugins/beacon/mobile/incidents", "POST", payload) }
                ?: throw IOException("Incident upload timed out")
        }
    }

    internal fun noteProcessExit(reason: Int, timestamp: Long) {
        work.launch {
            val captured = bound
            if (captured != null && !current(captured)) {
                synchronized(bindingLock) {
                    if (bound === captured) { revision++;bound = null;journal.deactivate() }
                }
            }
            journal.noteProcessExit(reason, timestamp)
        }
    }

    /** No network or exception contents are touched by the crashing thread. */
    internal fun recordCrash() {
        val captured = bound ?: return
        if (!crashing.compareAndSet(false, true)) return
        try {
            val writer = Thread({
                try {
                    if (current(captured)) journal.recordCode(captured.authority, "app", "startup", "process_crash", appVersion = version)
                } catch (_: Throwable) { } finally { crashing.set(false) }
            }, "beacon-incident-crash").apply { isDaemon = true }
            try { writer.start() } catch (_: Throwable) { crashing.set(false);return }
            try { writer.join(150) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        } catch (_: Throwable) { crashing.set(false) }
    }
}
