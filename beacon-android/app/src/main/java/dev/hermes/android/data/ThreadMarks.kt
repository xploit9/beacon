package dev.hermes.android.data

import dev.hermes.android.core.Scope
import org.json.JSONArray

/**
 * The chats known to be threads whose Hermes row cannot say so. Hermes' session authority creates a chat only under one of its own surfaces
 * (gateway/session_policy.py SURFACES), so a thread made on one is stored as an ordinary chat; the phone marks it here (and the plugin keeps the
 * same mark for other phones). A mark is per account and profile, kept across restarts through [store], and shows a chat in the Threads lane
 * exactly as the `android_thread` source does on a Hermes that stores it.
 */
internal object ThreadMarks {
    interface Store {
        suspend fun read(account: Scope): String?
        suspend fun write(account: Scope, value: String)
    }
    @Volatile var store: Store? = null
    private val marks = HashMap<String, LinkedHashSet<String>>()
    private val loaded = HashSet<String>()
    private const val LIMIT = 500

    private fun key(scope: Scope) = scope.copy(session = "").key

    suspend fun ids(scope: Scope): Set<String> {
        val k = key(scope)
        val held = synchronized(this) { if (k in loaded) marks[k]?.toSet().orEmpty() else null }
        if (held != null) return held
        val saved = attempt { store?.read(scope.copy(session = "")) }
        val parsed = saved?.let { runCatching { JSONArray(it) }.getOrNull() }?.let { array -> (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) } }.orEmpty()
        return synchronized(this) {
            // A mark added while the saved ones were read is kept.
            if (loaded.add(k)) marks.getOrPut(k) { LinkedHashSet() }.addAll(parsed)
            marks[k]?.toSet().orEmpty()
        }
    }

    suspend fun add(scope: Scope, vararg sessions: String): Boolean {
        ids(scope)
        val k = key(scope)
        val snapshot = synchronized(this) {
            val set = marks.getOrPut(k) { LinkedHashSet() }
            val before = set.size
            sessions.filter(String::isNotBlank).forEach(set::add)
            // The newest marks stay if the list ever outgrows its bound.
            while (set.size > LIMIT) set.remove(set.first())
            if (set.size == before) null else set.toList()
        } ?: return false
        persist(scope, snapshot)
        return true
    }

    suspend fun remove(scope: Scope, session: String) {
        ids(scope)
        val k = key(scope)
        val snapshot = synchronized(this) { marks[k]?.takeIf { it.remove(session) }?.toList() } ?: return
        persist(scope, snapshot)
    }

    private suspend fun persist(scope: Scope, snapshot: List<String>) {
        attempt { store?.write(scope.copy(session = ""), JSONArray(snapshot).toString()) }
    }

    /** A failing store only loses persistence; cancellation still ends the caller. */
    private suspend fun <T> attempt(block: suspend () -> T?): T? = try { block() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }

    /** Test seam: forget every mark and the saved-copy link. */
    internal fun reset() { synchronized(this) { marks.clear(); loaded.clear(); store = null }; synchronized(threadMarkReads) { threadMarkReads.clear() } }
}
