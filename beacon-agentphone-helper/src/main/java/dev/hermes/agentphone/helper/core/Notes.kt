package dev.hermes.agentphone.helper.core

/** One notification as the helper keeps it, in memory only: its sender package, time, title and text. */
class Note(val pkg: String, val postedMs: Long, val title: String, val text: String) {
    val all: String get() = "$title $text"
}

class Senders(val packages: List<String>, val hosts: List<String>, val mailPackages: List<String>) {
    companion object {
        fun of(m: Map<*, *>) = Senders(strings(m["packages"]), strings(m["hosts"]), strings(m["mail_packages"]))
        private fun strings(v: Any?) = (v as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    }
}

/**
 * Codes, temporary passwords and sign-in links from notifications (docs/agent-phone/channel-v1.md §6.4-6.6), read exactly
 * as the plugin's reference reading does (tests/test_agent_phone_channel.py): a note counts only from the site's own app,
 * or from a named mail app whose first e-mail address in title and text is on one of the site's hosts. That address is
 * written by the mail's author (Android documents no sender field for mail notifications: EXTRA_PEOPLE_LIST is "the
 * people that this notification relates to"), so what bounds a spoofed sender is the binding: a code only ever goes into
 * the site's own bound app or page, and a link only ever points at the site's own host. Whitespace is Python's, and only
 * explicit ASCII classes go through a regex, so the JVM and Android's ICU agree.
 */
class Notes(private val clock: () -> Long) {
    private val notes = ArrayList<Note>()

    @Synchronized
    fun add(note: Note) {
        notes.add(note)
        prune()
        while (notes.size > MAX_NOTES) notes.removeAt(0)
    }

    @Synchronized
    fun snapshot(): List<Note> {
        prune()
        return ArrayList(notes)
    }

    private fun prune() {
        val now = clock()
        notes.removeAll { now - it.postedMs > MAX_AGE_MS }
    }

    fun code(senders: Senders, maxAgeMs: Long): String? = code(snapshot(), senders, clock(), maxAgeMs)
    fun password(senders: Senders, maxAgeMs: Long): String? = password(snapshot(), senders, clock(), maxAgeMs)
    fun link(senders: Senders, maxAgeMs: Long): String? = link(snapshot(), senders, clock(), maxAgeMs)

    companion object {
        const val MAX_NOTES = 20
        const val MAX_AGE_MS = 600_000L
        private val ADDRESS = Regex("[A-Za-z0-9._%+-]+@([A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+\\.?)")
        private val CODE_WORDS = listOf("code", "otp", "verification", "verify", "passcode", "رمز")
        private val PASSWORD_WORDS = listOf("temporary password", "password", "كلمة المرور", "كلمة السر")
        private val HOST = Regex("[a-z0-9-]+(?:\\.[a-z0-9-]+)+")

        /** Python's whitespace for str: Unicode whitespace (categories Zs/Zl/Zp and the bidi whitespace controls). */
        fun isSpace(c: Char) = Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '\u0085'

        fun hostOk(name: String, hosts: List<String>) = hosts.any { name == it || name.endsWith(".$it") }

        fun senderOk(note: Note, senders: Senders): Boolean {
            if (note.pkg in senders.packages) return true
            if (note.pkg !in senders.mailPackages) return false
            val found = ADDRESS.find(note.all) ?: return false
            val domain = found.groupValues[1].lowercase(java.util.Locale.ROOT)
            return hostOk(if (domain.endsWith(".")) domain.dropLast(1) else domain, senders.hosts)
        }

        /** The live notes from the site, newest first (a stable sort, as Python's). */
        fun matching(notes: List<Note>, senders: Senders, now: Long, maxAgeMs: Long): List<Note> =
            notes.filter { now - it.postedMs <= maxAgeMs && senderOk(it, senders) }.sortedByDescending { it.postedMs }

        /** Where the first of [words] (leftmost; at one place, the earlier word) occurs, ignoring case: (start, end) or null. */
        fun find(text: String, words: List<String>): Pair<Int, Int>? {
            for (i in text.indices) for (w in words) if (text.regionMatches(i, w, 0, w.length, ignoreCase = true)) return i to i + w.length
            return null
        }

        /** The first run of 4-8 ASCII digits with no ASCII digit right before or after it. */
        fun firstCode(text: String): String? {
            var i = 0
            while (i < text.length) {
                if (text[i] !in '0'..'9') { i++; continue }
                val start = i
                while (i < text.length && text[i] in '0'..'9') i++
                if (i - start in 4..8) return text.substring(start, i)
            }
            return null
        }

        fun code(notes: List<Note>, senders: Senders, now: Long, maxAgeMs: Long): String? {
            for (note in matching(notes, senders, now, maxAgeMs)) {
                val text = note.all
                val code = firstCode(text)
                if (code != null && find(text, CODE_WORDS) != null) return code
            }
            return null
        }

        fun password(notes: List<Note>, senders: Senders, now: Long, maxAgeMs: Long): String? {
            val note = matching(notes, senders, now, maxAgeMs).firstOrNull() ?: return null
            val text = note.all
            val word = find(text, PASSWORD_WORDS) ?: return null
            val rest = text.substring(word.second)
            var i = 0
            while (i < rest.length && isSpace(rest[i])) i++
            if (i < rest.length && rest[i] == ':') i++
            else if (rest.startsWith("is", i) && i + 2 < rest.length && isSpace(rest[i + 2])) i += 2
            while (i < rest.length && isSpace(rest[i])) i++
            val start = i
            while (i < rest.length && !isSpace(rest[i])) i++
            var value = rest.substring(start, i)
            if (value.endsWith(".") || value.endsWith(",") || value.endsWith(";")) value = value.dropLast(1)
            return if (Messages.points(value) in 6..64) value else null
        }

        /** Every `https://` address, ended by whitespace or one of < > " ' ) (the reference's LINK pattern). */
        fun urls(text: String): List<String> {
            val out = ArrayList<String>()
            var from = 0
            while (true) {
                val start = text.indexOf("https://", from)
                if (start < 0) return out
                var end = start + 8
                while (end < text.length && !isSpace(text[end]) && text[end] !in "<>\"')") end++
                if (end > start + 8) out.add(text.substring(start, end))
                from = maxOf(end, start + 8)
            }
        }

        /**
         * The address's host, or null. Stricter than Python's urlsplit on purpose: an authority with user info (`@`), a
         * backslash or any character outside letters, digits, dots, hyphens and a numeric port counts as no host, because
         * a browser can read such an address's host differently (`https://evil.example\@kanto.example`).
         */
        fun hostOf(url: String): String? {
            val rest = url.removePrefix("https://")
            val cut = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (cut < 0) rest else rest.substring(0, cut)
            val colon = authority.indexOf(':')
            val host = (if (colon < 0) authority else authority.substring(0, colon)).lowercase(java.util.Locale.ROOT)
            if (colon >= 0 && !authority.substring(colon + 1).all { it in '0'..'9' }) return null
            return if (HOST.matches(host)) host else null
        }

        /**
         * The site's link for `note_link` (contract revision 3, §6.6): in the newest matching notification, the first
         * `https://` address on one of the site's hosts (at most 2,048 characters), or null. The host checks it again and
         * opens it on the phone; the helper never opens it (Android 16 blocks a background app's activity starts).
         */
        fun link(notes: List<Note>, senders: Senders, now: Long, maxAgeMs: Long): String? {
            val note = matching(notes, senders, now, maxAgeMs).firstOrNull() ?: return null
            return urls(note.all).firstOrNull { u -> Messages.points(u) <= 2048 && hostOf(u)?.let { hostOk(it, senders.hosts) } == true }
        }
    }
}
