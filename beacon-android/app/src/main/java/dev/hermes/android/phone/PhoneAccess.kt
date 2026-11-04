package dev.hermes.android.phone

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What the executor needs to know about the owner's choices; the phone reads them from [PhoneAccess], tests from a fake. */
internal interface PhoneSettings {
    /** The capability's switch in Settings > Phone access. */
    fun enabled(capability: PhoneCapability): Boolean
    /** Android has granted every permission the capability needs. */
    fun granted(capability: PhoneCapability): Boolean
    /** Turns the capability's stored switch off: its Android permission was taken away, and allowing it again must not switch it back on. */
    fun switchOff(capability: PhoneCapability)
    /** "Let Hermes use phone access". */
    fun hermesAllowed(): Boolean
    /** "Ask before Hermes reads". */
    fun askBeforeHermesReads(): Boolean
    /** "Ask before Hermes acts": a Hermes change, alarm, timer or message draft waits for the owner's tap. A call keeps its own card either way. */
    fun askBeforeHermesActs(): Boolean
}

/**
 * The owner's Phone access choices, kept on this phone only. Every capability starts off, and so does Hermes access; a revoked
 * Android permission makes the capability read as off even while its switch is stored on.
 */
/**
 * Whether Hermes asks before it uses the phone: [Always] for reads and changes, [Changes] for changes only, [Never] at all (a phone
 * call still waits for the tap or its countdown, and nothing changes on a locked phone). A pair the menu cannot make (reads asked,
 * changes not) reads as [Always], the stricter one.
 */
enum class ApprovalChoice(val asksReads: Boolean, val asksChanges: Boolean) {
    Always(true, true), Changes(false, true), Never(false, false);
    companion object {
        fun of(asksReads: Boolean, asksChanges: Boolean): ApprovalChoice = when {
            asksReads -> Always
            asksChanges -> Changes
            else -> Never
        }
    }
}

object PhoneAccess {
    private const val FILE = "beacon_phone_access"
    private const val HERMES = "hermes_allowed"
    private const val HERMES_ASK = "hermes_ask_reads"
    private const val HERMES_ASK_ACTS = "hermes_ask_acts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The switch as the owner left it. */
    fun switchedOn(context: Context, capability: PhoneCapability): Boolean = prefs(context).getBoolean(capability.key, false)
    fun setSwitchedOn(context: Context, capability: PhoneCapability, on: Boolean) { prefs(context).edit().putBoolean(capability.key, on).apply() }

    fun permissionsGranted(context: Context, capability: PhoneCapability): Boolean =
        capability.permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

    /**
     * Turns off every switch whose Android permission was taken away, and returns them. A revoked permission is the owner's choice
     * to stop that use: allowing it again in Android must not switch the capability back on by itself.
     */
    fun reconcile(context: Context): List<PhoneCapability> =
        PhoneCapability.entries.filter { switchedOn(context, it) && !permissionsGranted(context, it) }.onEach { setSwitchedOn(context, it, false) }

    /** On and usable: the switch is on and Android allows it. This is what lets a conversation use the capability. */
    fun active(context: Context, capability: PhoneCapability): Boolean = switchedOn(context, capability) && permissionsGranted(context, capability)

    /** "Let Hermes use phone access": off until the owner turns it on. */
    fun hermesAllowed(context: Context): Boolean = prefs(context).getBoolean(HERMES, false)
    fun setHermesAllowed(context: Context, on: Boolean) { prefs(context).edit().putBoolean(HERMES, on).apply() }

    /** "Ask before Hermes reads": on until the owner turns it off. */
    fun askBeforeHermesReads(context: Context): Boolean = prefs(context).getBoolean(HERMES_ASK, true)
    fun setAskBeforeHermesReads(context: Context, on: Boolean) { prefs(context).edit().putBoolean(HERMES_ASK, on).apply() }

    /** "Ask before Hermes acts": on until the owner turns it off. */
    fun askBeforeHermesActs(context: Context): Boolean = prefs(context).getBoolean(HERMES_ASK_ACTS, true)
    fun setAskBeforeHermesActs(context: Context, on: Boolean) { prefs(context).edit().putBoolean(HERMES_ASK_ACTS, on).apply() }

    /** Ask for approval, the one choice the owner sees: the two stored switches below, read and written together. */
    fun approvalChoice(context: Context): ApprovalChoice = ApprovalChoice.of(askBeforeHermesReads(context), askBeforeHermesActs(context))
    fun setApprovalChoice(context: Context, choice: ApprovalChoice) {
        prefs(context).edit().putBoolean(HERMES_ASK, choice.asksReads).putBoolean(HERMES_ASK_ACTS, choice.asksChanges).apply()
    }

    internal fun settings(context: Context): PhoneSettings {
        val app = context.applicationContext
        return object : PhoneSettings {
            override fun enabled(capability: PhoneCapability) = switchedOn(app, capability)
            override fun granted(capability: PhoneCapability) = permissionsGranted(app, capability)
            override fun switchOff(capability: PhoneCapability) = setSwitchedOn(app, capability, false)
            override fun hermesAllowed() = PhoneAccess.hermesAllowed(app)
            override fun askBeforeHermesReads() = PhoneAccess.askBeforeHermesReads(app)
            override fun askBeforeHermesActs() = PhoneAccess.askBeforeHermesActs(app)
        }
    }
}

/** One line of Settings > Phone access > Activity. */
data class PhoneActivityEntry(val at: Long, val origin: PhoneOrigin, val tool: String, val line: PhoneLine, val outcome: String)

/** Where the executor writes what it did. */
internal interface PhoneLog { fun add(entry: PhoneActivityEntry) }

/** The last 200 requests, newest last, kept in a private file on this phone and nowhere else. */
internal class PhoneActivityLog internal constructor(private val file: File?, private val limit: Int = LIMIT) : PhoneLog {
    private val entries = ArrayList<PhoneActivityEntry>()
    private var loaded = false

    private fun load() {
        if (loaded) return
        loaded = true
        val text = runCatching { file?.takeIf { it.isFile && it.length() < 400_000 }?.readText() }.getOrNull() ?: return
        runCatching {
            val array = JSONArray(text)
            for (i in 0 until array.length()) {
                val row = array.optJSONObject(i) ?: continue
                val origin = PhoneOrigin.entries.firstOrNull { it.name == row.optString("origin") } ?: continue
                entries += PhoneActivityEntry(row.optLong("at"), origin, row.optString("tool").take(40), PhoneLine(row.optString("line").take(LINE),
                    row.optJSONArray("args")?.let { a -> (0 until minOf(a.length(), 6)).map { a.optString(it).take(LINE) } }.orEmpty()), row.optString("outcome").take(24))
            }
        }
        while (entries.size > limit) entries.removeAt(0)
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            val array = JSONArray()
            entries.forEach { array.put(JSONObject().put("at", it.at).put("origin", it.origin.name).put("tool", it.tool).put("line", it.line.template).put("args", JSONArray(it.line.args)).put("outcome", it.outcome)) }
            val temporary = File(target.parentFile, target.name + ".tmp")
            temporary.writeText(array.toString())
            if (!temporary.renameTo(target)) { target.writeText(array.toString()); temporary.delete() }
        }
    }

    @Synchronized override fun add(entry: PhoneActivityEntry) {
        load()
        entries += entry.copy(line = PhoneLine(entry.line.template.take(LINE), entry.line.args.take(6).map { it.take(LINE) }), outcome = entry.outcome.take(24))
        while (entries.size > limit) entries.removeAt(0)
        save()
    }

    /** Newest first. */
    @Synchronized fun recent(): List<PhoneActivityEntry> { load(); return entries.asReversed().toList() }

    @Synchronized fun clear() { load(); entries.clear(); runCatching { file?.delete() } }

    companion object {
        const val LIMIT = 200
        private const val LINE = 240
        private const val NAME = "phone-activity.json"
        @Volatile private var shared: PhoneActivityLog? = null
        fun of(context: Context): PhoneActivityLog = shared ?: synchronized(this) {
            shared ?: PhoneActivityLog(File(context.applicationContext.filesDir, NAME)).also { shared = it }
        }
    }
}

/** 30 reads and 10 writes a minute for each origin. A request that is refused for the limit does not count again. */
internal class PhoneRateLimiter(private val clock: () -> Long, private val reads: Int = READS, private val writes: Int = WRITES) {
    private val stamps = HashMap<Pair<PhoneOrigin, Boolean>, ArrayDeque<Long>>()

    @Synchronized private fun fresh(origin: PhoneOrigin, write: Boolean): ArrayDeque<Long> {
        val queue = stamps.getOrPut(origin to write) { ArrayDeque() }
        val cutoff = clock() - WINDOW_MS
        while (queue.isNotEmpty() && queue.first() <= cutoff) queue.removeFirst()
        return queue
    }

    /** Takes one place in the window, or false when the window is full. */
    @Synchronized fun tryAcquire(origin: PhoneOrigin, write: Boolean): Boolean {
        val queue = fresh(origin, write)
        if (queue.size >= if (write) writes else reads) return false
        queue.addLast(clock())
        return true
    }

    /** Whether a request would be admitted now, without taking its place. */
    @Synchronized fun wouldAllow(origin: PhoneOrigin, write: Boolean): Boolean = fresh(origin, write).size < if (write) writes else reads

    companion object {
        const val READS = 30
        const val WRITES = 10
        const val WINDOW_MS = 60_000L
    }
}
