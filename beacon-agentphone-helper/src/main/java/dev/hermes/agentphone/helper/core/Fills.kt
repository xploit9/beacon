package dev.hermes.agentphone.helper.core

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/** One form field as the helper sees it: its id, role, focus and size, never its text. */
class FormField(
    val id: String,
    val role: String,
    val focused: Boolean,
    val webDomain: String?,
    val webScheme: String?,
    val maxLength: Int = -1,
    /** The platform's own id object (Android's AutofillId); opaque here. */
    val handle: Any? = null,
    /** The view id as a screen dump shows it (`package:id/name`, or a web field's own id), if it has one. */
    val viewId: String? = null,
    /** Where the field sits on the screen, in pixels: left, top, right, bottom. */
    val bounds: List<Int>? = null,
)

/** One vault item an arm offers: one "Beacon · <label>" entry (contract revision 2, §6.2). */
class Item(val itemId: String, val label: String, val roles: List<String>)

/** One armed fill with its items (contract §6.2), or a code the helper read itself (§6.4, [code] set). */
class Arm(
    val armId: String,
    val kind: String,
    val pkg: String,
    val webDomain: String?,
    val items: List<Item>,
    val expiresAt: Long,
    val code: String? = null,
)

/** One of Beacon's entries offered for one Android fill request: picking it sends a release_request with [req]. */
class Offer(val req: String, val arm: Arm, val item: Item, val fields: List<FormField>, val context: Map<String, Any?>) {
    /** The request's roles: the item's roles that this form has, in the item's order. */
    val roles: List<String> = item.roles.filter { r -> fields.any { it.role == r } }
}

/** A fill context (contract §6.1) and the fields it covers. */
class Context(val map: Map<String, Any?>, val webDomain: String?, val fields: List<FormField>)

sealed class Answer {
    class Release(val values: Map<String, String>) : Answer()
    object Deny : Answer()
    object Cancelled : Answer()
}

sealed class Taken {
    class Ready(val offer: Offer) : Taken()
    class Stale(val offer: Offer) : Taken()
    object Unknown : Taken()
}

/**
 * Arms and releases (contract §6.1-6.2), in memory only. One arm at a time; an arm matches a fill request only while
 * unexpired, in its package and (when set) its web domain; it is spent by the one release_request it allows; a release
 * is used once and only for the request's roles.
 */
class Fills(private val clock: () -> Long, private val newId: () -> String) {
    @Volatile var lastContext: Map<String, Any?>? = null
        private set
    private var arm: Arm? = null
    private val offers = LinkedHashMap<String, Offer>()
    private val waiters = HashMap<String, ArrayBlockingQueue<Answer>>()

    /**
     * The context of one fill request. Fields are partitioned by web domain (a dataset never spans two sites): the
     * focused field's domain decides, else the first field's, else the outermost domain the screen reports.
     */
    fun context(
        pkg: String, certs: List<String>, source: String, all: List<FormField>, outerDomain: String?, outerScheme: String?,
        record: Boolean = true,
    ): Context {
        val anchor = all.firstOrNull { it.focused } ?: all.firstOrNull()
        var domain = if (anchor != null) anchor.webDomain else outerDomain
        var scheme = if (anchor != null) anchor.webScheme else outerScheme
        var fields = all.filter { it.webDomain == domain }
        if (domain != null && Messages.points(domain) !in 1..253 || scheme != null && Messages.points(scheme) !in 1..253) {
            domain = null; scheme = null; fields = emptyList()
        }
        val map = linkedMapOf<String, Any?>(
            "package" to pkg,
            "certs" to certs.sorted(),
            "web_domain" to domain,
            "web_scheme" to scheme,
            "fields" to fields.map { linkedMapOf<String, Any?>("id" to it.id, "role" to it.role, "focused" to it.focused) },
            "at_ms" to clock(),
            "source" to source,
        )
        if (record) lastContext = map
        return Context(map, domain, fields)
    }

    @Synchronized
    fun arm(a: Arm) {
        arm = a
    }

    /** Drops the arm ([armId] null: any). False when a named arm isn't the current one. */
    @Synchronized
    fun disarm(armId: String?): Boolean {
        val current = arm
        if (armId != null && current?.armId != armId) return false
        arm = null
        return true
    }

    /**
     * Beacon's entries for this fill request when the arm matches it: one per item, in the arm's order, for the fields
     * whose role is in that item's roles; an item with no such field on the screen gets no entry.
     */
    @Synchronized
    fun offer(pkg: String, context: Context): List<Offer> {
        val a = arm ?: return emptyList()
        if (clock() > a.expiresAt || a.pkg != pkg || (a.webDomain != null && a.webDomain != context.webDomain)) return emptyList()
        val out = a.items.mapNotNull { item ->
            val target = context.fields.filter { it.role in item.roles }
            if (target.isEmpty()) null else Offer(newId(), a, item, target, context.map)
        }
        for (offer in out) offers[offer.req] = offer
        while (offers.size > MAX_OFFERS) offers.remove(offers.keys.first())
        return out
    }

    /** The picked entry: Ready (the arm is spent now, and a waiter is set for the host's answer) or Stale/Unknown. */
    @Synchronized
    fun take(req: String): Taken {
        val offer = offers.remove(req) ?: return Taken.Unknown
        val a = arm
        if (a !== offer.arm || clock() > a.expiresAt) return Taken.Stale(offer)
        arm = null
        offers.values.removeAll { it.arm === a }
        if (a.code == null) waiters[req] = ArrayBlockingQueue(1)
        return Taken.Ready(offer)
    }

    /** Waits for the host's answer to [req]; null after [ms]. */
    fun await(req: String, ms: Long): Answer? {
        val queue = synchronized(this) { waiters[req] } ?: return Answer.Cancelled
        return try {
            queue.poll(ms, TimeUnit.MILLISECONDS)
        } finally {
            synchronized(this) { waiters.remove(req) }
        }
    }

    /** The host's release (values) or deny (null) for [req]; ignored when nobody waits for it any more. */
    fun answer(req: String, values: Map<String, String>?) {
        val queue = synchronized(this) { waiters[req] } ?: return
        queue.offer(if (values == null) Answer.Deny else Answer.Release(values))
    }

    /** The link ended: no arm, no offers, every waiter cancelled. */
    @Synchronized
    fun reset() {
        arm = null
        offers.clear()
        for (q in waiters.values) q.offer(Answer.Cancelled)
    }

    companion object {
        private const val MAX_OFFERS = 64

        /** The values to hand Android: only the request's roles; a year field of at most 2 characters gets the last two digits. */
        fun valuesFor(offer: Offer, values: Map<String, String>): List<Pair<FormField, String>> =
            offer.fields.mapNotNull { f ->
                if (f.role !in offer.roles) return@mapNotNull null
                val v = values[f.role] ?: return@mapNotNull null
                f to (if (f.role == "exp_year" && f.maxLength in 1..2) v.takeLast(2) else v)
            }

        /**
         * code_filled's `targets` (contract §6.4): the fields a code went into, so the host can mask them in its captures. It
         * never sees the code: each target is the field's id, view id and screen bounds, never its value.
         */
        fun targets(filled: List<Pair<FormField, String>>): List<Map<String, Any?>> =
            filled.take(16).map { (f, _) -> linkedMapOf<String, Any?>("id" to f.id, "view_id" to f.viewId, "bounds" to f.bounds) }
    }
}
