package dev.hermes.agentphone.helper.core

/**
 * The sealed messages (docs/agent-phone/channel-v1.md §5-6.7), checked exactly as the plugin's reference codec checks
 * them (hermes-plugin/beacon/agent_phone_channel.py, decode): the same table, the same ranges, lengths counted in code
 * points as Python counts them. Anything it would refuse closes the link here too, and nothing it accepts is refused.
 */
object Messages {
    const val PROTOCOL = 1
    const val MAX_PLAINTEXT = 262000

    val KINDS: Map<String, List<String>> = linkedMapOf(
        "login" to listOf("username", "password", "otp"),
        "new_login" to listOf("username", "new_password"),
        "payment" to listOf("card_number", "cardholder_name", "exp_date", "exp_month", "exp_year", "cvc", "billing_postal_code"),
        "address" to listOf("address_line1", "address_line2", "city", "state", "postal_code", "country"),
        "otp" to listOf("otp"),
    )
    val ROLES: Set<String> = KINDS.values.flatten().toSet()
    private val ERROR_CODES = setOf("bad_request", "not_armed", "busy", "no_clipboard", "unavailable")
    private val BYE_REASONS = setOf("done", "version", "idle", "error")
    private val FAIL_REASONS = setOf("denied", "timeout", "cancelled")
    private val SAVE_VALUES = setOf("username", "password", "new_password")
    val SOURCES = setOf("system", "play", "other")
    private val ID = Regex("[A-Za-z0-9_-]{16,64}")
    private val CERT = Regex("[0-9a-f]{64}")

    private enum class Kind { REQUEST, REPLY, EVENT, ANSWER, CONTROL }
    private class Spec(val direction: Byte?, val kind: Kind, val fields: Map<String, (Any?) -> Boolean>)

    fun points(s: String) = s.codePointCount(0, s.length)
    private fun int(low: Long, high: Long): (Any?) -> Boolean = { v -> v is Long && v in low..high }
    private fun text(low: Int, high: Int): (Any?) -> Boolean = { v -> v is String && points(v) in low..high }
    private fun nullable(check: (Any?) -> Boolean): (Any?) -> Boolean = { v -> v == null || check(v) }
    private val ident: (Any?) -> Boolean = { v -> v is String && ID.matches(v) }
    private fun oneOf(set: Set<String>): (Any?) -> Boolean = { v -> v is String && v in set }
    private val bool: (Any?) -> Boolean = { v -> v is Boolean }
    private val any: (Any?) -> Boolean = { true }
    private val names: (Any?) -> Boolean = { v -> v is List<*> && v.size <= 16 && v.all { it is String && points(it) in 1..253 } }
    private val senders: (Any?) -> Boolean = { v -> v is Map<*, *> && listOf("packages", "hosts", "mail_packages").all { names(v[it]) } }
    private val box: (Any?) -> Boolean = { v -> v is List<*> && v.size == 4 && v.all { int(-100000, 100000)(it) } }
    private val TARGET_KEYS = setOf("id", "view_id", "bounds")
    /** code_filled's filled fields (§6.4): id, view id, screen bounds and nothing else, so never a value. */
    private val targets: (Any?) -> Boolean = { v ->
        v is List<*> && v.size <= 16 && v.all { t ->
            t is Map<*, *> && t.keys.all { it in TARGET_KEYS } && text(1, 128)(t["id"]) && nullable(text(1, 255))(t["view_id"]) &&
                nullable(box)(t["bounds"])
        }
    }
    private val roles: (Any?) -> Boolean = { v -> v is List<*> && v.size in 1..16 && v.all { it is String && it in ROLES } }
    private val items: (Any?) -> Boolean = items@{ v ->
        if (v !is List<*> || v.size !in 1..16) return@items false
        val ids = v.map { (it as? Map<*, *>)?.get("item_id") }
        ids.toSet().size == ids.size && ids.all { ident(it) } &&
            v.all { it is Map<*, *> && text(1, 120)(it["label"]) && roles(it["roles"]) }
    }
    private fun values(allowed: Set<String>): (Any?) -> Boolean =
        { v -> v is Map<*, *> && v.all { (k, x) -> k is String && k in allowed && x is String && points(x) <= 4096 } }

    val context: (Any?) -> Boolean = context@{ v ->
        if (v !is Map<*, *>) return@context false
        val pkg = v["package"]
        if (pkg !is String || pkg.isEmpty()) return@context false
        val certs = v["certs"]
        val fields = v["fields"]
        if (certs !is List<*> || !certs.all { it is String && CERT.matches(it) }) return@context false
        if (!listOf("web_domain", "web_scheme").all { nullable(text(1, 253))(v[it]) } || v["source"] !in SOURCES) return@context false
        fields is List<*> && fields.all { f ->
            f is Map<*, *> && f["id"] is String && f["role"].let { it is String && it in ROLES } && f["focused"] is Boolean
        }
    }

    private val REQ_ID = int(1, Int.MAX_VALUE.toLong())
    private val H2C = Wire.H2C
    private val C2H = Wire.C2H

    private val MESSAGES: Map<String, Spec> = mapOf(
        "hello_ok" to Spec(H2C, Kind.CONTROL, mapOf("proto" to int(1, 65535))),
        "ping" to Spec(H2C, Kind.REQUEST, emptyMap()),
        "context" to Spec(H2C, Kind.REQUEST, emptyMap()),
        "arm" to Spec(H2C, Kind.REQUEST, mapOf(
            "arm_id" to ident, "kind" to oneOf(KINDS.keys), "package" to text(1, 255), "web_domain" to nullable(text(1, 253)),
            "items" to items, "ttl_ms" to int(1, 20000))),
        "disarm" to Spec(H2C, Kind.REQUEST, mapOf("arm_id" to nullable(ident))),
        "release" to Spec(H2C, Kind.ANSWER, mapOf("req" to ident, "values" to values(ROLES))),
        "deny" to Spec(H2C, Kind.ANSWER, mapOf("req" to ident)),
        "arm_code" to Spec(H2C, Kind.REQUEST, mapOf(
            "arm_id" to ident, "package" to text(1, 255), "label" to text(1, 120), "senders" to senders,
            "max_age_ms" to int(1, 600000), "ttl_ms" to int(1, 20000))),
        "note_password" to Spec(H2C, Kind.REQUEST, mapOf("senders" to senders, "max_age_ms" to int(1, 600000))),
        "note_link" to Spec(H2C, Kind.REQUEST, mapOf("senders" to senders, "max_age_ms" to int(1, 600000))),
        "open_link" to Spec(H2C, Kind.REQUEST, mapOf("senders" to senders, "max_age_ms" to int(1, 600000))),
        "clip_set" to Spec(H2C, Kind.REQUEST, mapOf("text" to text(1, 10000), "ttl_ms" to int(1, 10000))),
        "clip_clear" to Spec(H2C, Kind.REQUEST, emptyMap()),
        "bye" to Spec(null, Kind.CONTROL, mapOf("reason" to oneOf(BYE_REASONS))),
        "hello" to Spec(C2H, Kind.CONTROL, mapOf(
            "proto_min" to int(1, 65535), "proto_max" to int(1, 65535), "helper_version" to text(1, 64),
            "version_code" to int(0, Int.MAX_VALUE.toLong()))),
        "ack" to Spec(C2H, Kind.REPLY, emptyMap()),
        "error" to Spec(C2H, Kind.REPLY, mapOf("code" to oneOf(ERROR_CODES))),
        "pong" to Spec(C2H, Kind.REPLY, emptyMap()),
        "context_result" to Spec(C2H, Kind.REPLY, mapOf("context" to nullable(context))),
        "code_result" to Spec(C2H, Kind.REPLY, mapOf("found" to bool)),
        "note_password_result" to Spec(C2H, Kind.REPLY, mapOf("found" to bool, "value" to any)),
        "note_link_result" to Spec(C2H, Kind.REPLY, mapOf("found" to bool, "url" to any)),
        "fill_context" to Spec(C2H, Kind.EVENT, mapOf("context" to context)),
        "release_request" to Spec(C2H, Kind.EVENT, mapOf("req" to ident, "arm_id" to ident, "item_id" to ident, "roles" to roles, "context" to context)),
        "filled" to Spec(C2H, Kind.EVENT, mapOf("req" to ident, "arm_id" to ident, "fields" to int(0, 64))),
        "fill_failed" to Spec(C2H, Kind.EVENT, mapOf("req" to ident, "arm_id" to ident, "reason" to oneOf(FAIL_REASONS))),
        "code_filled" to Spec(C2H, Kind.EVENT, mapOf("arm_id" to ident, "fields" to int(0, 64), "targets" to nullable(targets))),
        "save" to Spec(C2H, Kind.EVENT, mapOf("save_id" to ident, "context" to context, "values" to values(SAVE_VALUES))),
    )

    /** Whether [t] is a request the host sends (it carries an id and gets exactly one reply). */
    fun isRequest(t: String) = MESSAGES[t]?.kind == Kind.REQUEST

    /** The message a sealed plaintext holds, checked for the direction it came from; [Closed] for anything else. */
    fun decode(plaintext: ByteArray, direction: Byte): Map<String, Any?> {
        if (plaintext.size > MAX_PLAINTEXT) throw Closed("message")
        if (plaintext.size >= 3 && plaintext[0] == 0xef.toByte() && plaintext[1] == 0xbb.toByte() && plaintext[2] == 0xbf.toByte()) {
            throw Closed("message")
        }
        val parsed = try { Json.parseUtf8(plaintext) } catch (e: Json.Invalid) { throw Closed("message") }
        if (parsed !is Map<*, *>) throw Closed("message")
        @Suppress("UNCHECKED_CAST")
        val message = parsed as Map<String, Any?>
        val t = message["t"] as? String ?: throw Closed("message")
        val spec = MESSAGES[t] ?: throw Closed("message")
        if (spec.direction != null && spec.direction != direction) throw Closed("message")
        if (spec.kind == Kind.REQUEST && !REQ_ID(message["id"])) throw Closed("message")
        if (spec.kind == Kind.REPLY && !REQ_ID(message["re"])) throw Closed("message")
        for ((name, check) in spec.fields) if (!check(message[name])) throw Closed("message")
        when (t) {
            "arm" -> {
                val allowed = KINDS.getValue(message["kind"] as String)
                if (!(message["items"] as List<*>).all { i -> ((i as Map<*, *>)["roles"] as List<*>).all { it in allowed } }) throw Closed("message")
            }
            "hello" -> if ((message["proto_min"] as Long) > (message["proto_max"] as Long)) throw Closed("message")
            "note_password_result" -> {
                val value = message["value"]
                if ((message["found"] as Boolean) != (value is String && points(value) in 1..4096)) throw Closed("message")
            }
            "note_link_result" -> {
                val url = message["url"]
                if ((message["found"] as Boolean) != (url is String && url.startsWith("https://") && points(url) <= 2048)) throw Closed("message")
            }
        }
        return message
    }

    /** The bytes of a message this side sends, after the same check the other side will make; [Closed] if it would fail. */
    fun encode(message: Map<String, Any?>, direction: Byte): ByteArray {
        val bytes = Json.encode(message)
        decode(bytes, direction)
        return bytes
    }
}
