package dev.hermes.android.data

import dev.hermes.android.core.Scope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Reads whose last good answer is kept on the phone: configuration lists, never messages or secrets (MCP server configs can carry env secrets, so they stay in memory). The one per-chat read is the chat's project.
 *  Bot Mode also keeps its roster here with each group's newest line (140 characters), like a chat list's preview; the row is encrypted and cleared on sign-out. */
val screenReadPaths = setOf(
    "/api/profiles", "/api/tools/toolsets", "/api/commands/catalog", "/api/projects", "/api/plugins/beacon/mobile/voice-settings",
    "/api/crons", "/api/crons/delivery-options", "/api/crons/history", "/api/plugins/beacon/mobile/tasks/capabilities",
    "/api/pc/files", "/api/models", "/api/profile/approval-mode", "/api/plugins/beacon/mobile/history-retention",
    "/api/projects/for-session",
)
// Query keys that name the screen; a chat, session or cursor makes a read live-only.
private val SCREEN_QUERY_KEYS = setOf("profile", "path", "job_id")

// A chat's project is a small configuration answer (no messages), so it is the one read kept per chat.
internal const val CHAT_PROJECT_READ = "/api/projects/for-session"

fun screenReadCacheable(path: String, query: Map<String, String>, profile: String): Boolean =
    path in screenReadPaths && query.keys.all { it in SCREEN_QUERY_KEYS || it == "chat" && path == CHAT_PROJECT_READ } && (query["profile"] ?: profile) == profile

/** The answer's identity inside one profile: the profile is the scope, so it is not part of the request. */
fun screenRequest(path: String, query: Map<String, String>): String =
    path + query.filterKeys { it != "profile" }.toSortedMap().entries.joinToString("") { "&${it.key}=${it.value}" }

/** A matching explicit profile and an omitted profile name the same read; JSON prevents path/query collisions. */
internal fun screenReadIdentity(scope: Scope, path: String, query: Map<String, String>, generation: Int): String =
    JSONArray(listOf(scope.key, generation, path, JSONArray(query.filterNot { it.key == "profile" && it.value == scope.profile }
        .toSortedMap().map { (key, value) -> JSONArray(listOf(key, value)) }))).toString()

internal fun screenDisplayFresh(path: String, at: Long, now: Long, invalidatedAt: Long = 0L): Boolean =
    startupReadFresh(at, now, invalidatedAt) && (path != "/api/approval/mode" || now - at < 10_000L)

data class ScreenReadVersion(val account: Int, val family: Long)

/** A write to /api/crons/create touches every cached /api/crons… read; a plural noun and its singular share a family. */
internal fun readFamily(path: String): String {
    val parts = path.removePrefix("/api/").substringBefore('&').split('/')
    return if (parts.firstOrNull() == "plugins" && parts.size > 3) parts.take(4).joinToString("/") else parts.first().removeSuffix("s")
}

private val SECRET_KEY = Regex("(?i)(^|[_-])(token|secret|password|passwd|cookie|authorization|bearer|csrf)$|api[_-]?key|pairing")

/** Belt and braces beside the path allowlist: a payload holding a credential-shaped key with a text value is never written to disk (a flag such as `has_api_key: true` is not a credential). */
internal fun hasSecretKey(value: Any?, depth: Int = 0): Boolean = when {
    depth > 12 -> true
    value is JSONObject -> value.keys().asSequence().any { name ->
        val inner = value.opt(name)
        SECRET_KEY.containsMatchIn(name) && inner is String && inner.isNotBlank() || hasSecretKey(inner, depth + 1)
    }
    value is JSONArray -> (0 until value.length()).any { hasSecretKey(value.opt(it), depth + 1) }
    else -> false
}

/**
 * Last good answers of screen reads, one encrypted row per account (origin, owner, provider) so sign-out drops every profile at once.
 * Each entry belongs to one profile, is bounded in size, and the oldest are evicted first. Live data stays authoritative: every
 * storage failure is swallowed. shortcut: the row is rewritten whole after a short quiet period; split it per profile if the budget grows.
 */
class ScreenCache(
    private val read: suspend (Scope) -> String?,
    private val write: suspend (Scope, String) -> Unit,
    private val drop: suspend (Scope) -> Unit,
    private val jobs: CoroutineScope,
    private val quietMs: Long = 1_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry(val profile: String, val key: String, val request: String, val at: Long, val value: JSONObject, val bytes: Int) {
        val id get() = "$profile\u0000$key\u0000$request"
    }
    private val rows = HashMap<String, LinkedHashMap<String, Entry>>()
    // Per account, bumped by clear(): what an open() or flush() took before a sign-out must not be installed or written after it.
    private val epochs = HashMap<String, Int>()
    private val flushes = HashMap<String, Job>()
    private val versions = HashMap<String, Long>()
    private class Flight(val scope: Scope, val result: Deferred<JSONObject>, var readers: Int = 0)
    private val flights = HashMap<String, Flight>()
    private val io = Mutex()
    private fun account(scope: Scope) = scope.copy(profile = "", session = "")
    private fun epoch(account: Scope) = epochs[account.key] ?: 0
    private fun versionKey(scope: Scope, request: String) = "${scope.key}:${readFamily(request)}"
    internal fun readVersion(scope: Scope, request: String): ScreenReadVersion = synchronized(this) {
        ScreenReadVersion(epoch(account(scope)), versions[versionKey(scope, request)] ?: 0L)
    }
    internal fun readCurrent(scope: Scope, request: String, version: ScreenReadVersion): Boolean = readVersion(scope, request) == version

    /** One transport read per normalized scoped request. Cancelling one screen leaves other readers attached. */
    internal suspend fun fetch(scope: Scope, request: String, read: suspend () -> JSONObject): JSONObject {
        val id = "${scope.key}\u0000$request"
        val flight = synchronized(this) {
            flights.getOrPut(id) { Flight(scope, jobs.async(start = CoroutineStart.LAZY) { read() }) }.also { it.readers++ }
        }
        try { return JSONObject(flight.result.await().toString()) }
        finally {
            var cancel = false
            synchronized(this) {
                flight.readers--
                if (flight.readers == 0) {
                    if (flights[id] === flight) flights.remove(id)
                    cancel = true
                }
            }
            if (cancel) flight.result.cancel()
        }
    }

    /** Loads the account's row once; later calls are free and an unreadable row is the same as no row. A sign-out that lands first leaves the account closed. */
    suspend fun open(scope: Scope) {
        val account = account(scope)
        val opened = synchronized(this) { if (account.key in rows) return; epoch(account) }
        // Read under the row's lock, so clear() cannot drop the row between this read and the install below.
        val loaded = io.withLock { withContext(Dispatchers.Default) { try { read(account)?.let(::parse) } catch (e: CancellationException) { throw e } catch (_: Exception) { null } } }.orEmpty()
        synchronized(this) {
            if (epoch(account) != opened) return
            rows.getOrPut(account.key) { LinkedHashMap<String, Entry>().also { map -> loaded.forEach { map[it.id] = it }; trim(map) } }
        }
    }
    fun latest(scope: Scope): List<Entry> = synchronized(this) {
        rows[account(scope).key]?.values.orEmpty().filter { it.profile == scope.profile }.groupBy { it.key }.values.mapNotNull { group -> group.maxByOrNull { it.at } }
    }
    fun exact(scope: Scope, key: String, request: String = ""): Entry? = synchronized(this) { rows[account(scope).key]?.get("${scope.profile}\u0000$key\u0000$request") }
    /** Two screens reading one resource under different keys share its newest answer. */
    fun sameRequest(scope: Scope, request: String): Entry? = synchronized(this) {
        rows[account(scope).key]?.values?.filter { it.profile == scope.profile && it.request == request }?.maxByOrNull { it.at }
    }
    suspend fun get(scope: Scope, key: String, request: String = ""): JSONObject? = exact(scope, key, request)?.value
    /** Like [put], for a read that finished later: a cleared account stays closed, so a late answer cannot bring it back. */
    fun set(scope: Scope, key: String, value: JSONObject, request: String = "") = put(scope, key, request, clock(), value)

    /** A payload that is too large or credential-shaped replaces nothing on disk and removes the older copy. */
    fun put(scope: Scope, key: String, request: String, at: Long, value: JSONObject, version: ScreenReadVersion? = null) {
        val text = value.toString()
        val bytes = text.toByteArray().size
        val account = account(scope)
        val keep = bytes <= MAX_ENTRY_BYTES && !hasSecretKey(value)
        synchronized(this) {
            if (version != null && !readCurrent(scope, request, version)) return
            val map = rows[account.key] ?: return
            val id = "${scope.profile}\u0000$key\u0000$request"
            map.remove(id)
            if (keep) { map[id] = Entry(scope.profile, key, request, at, JSONObject(text), bytes); trim(map) }
        }
        schedule(account)
    }
    /** After a successful write to [path], the cached reads of the same family must not outlive it; the screen refreshes itself. */
    fun invalidate(scope: Scope, path: String) {
        val family = readFamily(path)
        val account = account(scope)
        val changed = synchronized(this) {
            val key = versionKey(scope, path); versions[key] = (versions[key] ?: 0L) + 1L
            rows[account.key]?.values?.removeAll { it.profile == scope.profile && readFamily(it.request) == family } == true
        }
        if (changed) schedule(account)
    }
    suspend fun remove(scope: Scope, key: String, request: String = "") {
        val account = account(scope)
        if (synchronized(this) { rows[account.key]?.remove("${scope.profile}\u0000$key\u0000$request") != null }) schedule(account)
    }
    /** Sign-out, account change and device removal: the row and the in-memory copy go together; a pending flush cannot bring it back. */
    suspend fun clear(scope: Scope) = clear(scope, { remove -> remove(); true }, drop)
    internal suspend fun clear(removal: DeviceRemovalAuthority, dropCurrent: suspend (Scope) -> Unit) =
        clear(removal.scope, removal::ifCurrent, dropCurrent)
    internal suspend fun clear(scope: Scope, removeIfCurrent: (() -> Unit) -> Boolean, dropCurrent: suspend (Scope) -> Unit) {
        val account = account(scope)
        // One step under the row's lock: an open() either finished before it (and is removed here) or reads the empty store.
        io.withLock {
            var pendingFlush: Job? = null
            val pendingReads = ArrayList<Deferred<JSONObject>>()
            if (!removeIfCurrent {
                synchronized(this) {
                    rows.remove(account.key); epochs[account.key] = epoch(account) + 1; pendingFlush = flushes.remove(account.key)
                    flights.entries.removeAll { (_, flight) -> if (flight.scope.sameAccount(account)) { pendingReads.add(flight.result); true } else false }
                }
            }) return@withLock
            // Cancel outside AuthStore and the cache monitor; handlers may run immediately.
            pendingFlush?.cancel()
            pendingReads.forEach { it.cancel() }
            // The disk target rechecks inside its transaction; this call may suspend after re-admission.
            try { dropCurrent(account) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* the next open reads whatever remains */ }
        }
    }
    suspend fun flush(scope: Scope) {
        val account = account(scope)
        val (text, taken) = synchronized(this) { rows[account.key]?.values?.let(::encode) to epoch(account) }
        if (text == null) return
        io.withLock {
            if (synchronized(this) { epoch(account) != taken }) return
            try { write(account, text) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* live reads stay authoritative */ }
        }
    }
    private fun schedule(account: Scope) {
        synchronized(this) {
            if (flushes[account.key]?.isActive == true) return
            flushes[account.key] = jobs.launch(Dispatchers.Default) { delay(quietMs); synchronized(this@ScreenCache) { flushes.remove(account.key) }; flush(account) }
        }
    }
    private fun trim(map: LinkedHashMap<String, Entry>) {
        map.values.filter { it.request.startsWith(CHAT_PROJECT_READ) }.sortedByDescending { it.at }.drop(CHAT_PROJECT_ENTRIES).forEach { map.remove(it.id) }
        var total = map.values.sumOf { it.bytes }
        for (old in map.values.sortedBy { it.at }) {
            if (total <= MAX_ROW_BYTES && map.size <= MAX_ENTRIES) break
            map.remove(old.id); total -= old.bytes
        }
    }
    private fun encode(entries: Collection<Entry>): String =
        JSONObject().put("v", 1).put("e", JSONArray(entries.map { JSONObject().put("p", it.profile).put("k", it.key).put("r", it.request).put("a", it.at).put("d", it.value) })).toString()
    private fun parse(text: String): List<Entry> {
        val array = JSONObject(text).takeIf { it.optInt("v") == 1 }?.optJSONArray("e") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val value = row.optJSONObject("d") ?: return@mapNotNull null
            val bytes = value.toString().toByteArray().size
            if (bytes > MAX_ENTRY_BYTES || hasSecretKey(value)) null else Entry(row.optString("p"), row.optString("k"), row.optString("r"), row.optLong("a"), value, bytes)
        }
    }
    companion object {
        const val MAX_ENTRY_BYTES = 512 * 1024
        const val MAX_ROW_BYTES = 4 * 1024 * 1024
        const val MAX_ENTRIES = 128
        const val CHAT_PROJECT_ENTRIES = 20
    }
}
