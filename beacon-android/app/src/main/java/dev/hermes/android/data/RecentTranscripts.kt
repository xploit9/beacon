package dev.hermes.android.data

/**
 * The few transcripts this process saved or opened last, kept as text so that opening one of those chats again draws it in the same frame instead of after a disk read.
 * Process memory only, keyed by the chat's own scope (account, profile, chat), bounded in count and size, and emptied at sign-out; the saved copy on disk stays the one that outlives the process.
 */
class RecentTranscripts(private val maxEntries: Int = MAX_ENTRIES, private val maxChars: Int = MAX_CHARS) {
    private val entries = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean = size > maxEntries
    }
    fun put(key: String, value: String) = synchronized(entries) { if (value.length <= maxChars) entries[key] = value else entries.remove(key); Unit }
    fun get(key: String): String? = synchronized(entries) { entries[key] }
    fun remove(key: String) { synchronized(entries) { entries.remove(key) } }
    fun clear() { synchronized(entries) { entries.clear() } }
    companion object { const val MAX_ENTRIES = 4; const val MAX_CHARS = 1024 * 1024 }
}
