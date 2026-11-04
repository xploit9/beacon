package dev.hermes.android.voice

import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * "Call me back in ten minutes" / "call me at five" during a call (owner, 4 Nov 2026: "I can request a call back anytime I want").
 * The plugin schedules it as Hermes' own task (`/mobile/calls/{id}/schedule-callback`) and rings the phone then; the call only
 * turns the owner's words into one of its two shapes: a delay in seconds, or a timezone-aware time.
 */
internal object ScheduledCallback {
    /** The longest delay the plugin takes (a year), in minutes. */
    private const val MAX_MINUTES = 366L * 24 * 60

    /**
     * The request body for the tool's [args] (`minutes` from now, or `at` as "HH:MM" local, plus a short `reason`). [callId] and
     * [toolCallId] make the request id, so a repeated delivery of the same tool call is the same request.
     */
    fun body(args: JSONObject, callId: String, toolCallId: String, now: ZonedDateTime): JSONObject {
        val reason = args.optString("reason").trim().take(240).ifBlank { "The callback you asked for" }
        val request = UUID.nameUUIDFromBytes("$callId\u0000$toolCallId".toByteArray(Charsets.UTF_8)).toString().replace("-", "")
        val body = JSONObject().put("request_id", request).put("reason", reason)
        val minutes = args.opt("minutes")?.let { (it as? Number)?.toLong() ?: it.toString().trim().toLongOrNull() }
        val at = args.optString("at").trim()
        return when {
            minutes != null -> body.put("delay_seconds", (minutes.coerceIn(1, MAX_MINUTES) * 60).toInt())
            at.isNotBlank() -> body.put("at", nextAt(at, now))
            else -> throw IllegalArgumentException("Say when to call back: minutes from now or a time")
        }
    }

    /** "17:30" (or "5:30") as the next such time in [now]'s zone: later today, else tomorrow. */
    fun nextAt(at: String, now: ZonedDateTime): String {
        val time = runCatching { LocalTime.parse(at.padStart(5, '0'), DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull()
            ?: throw IllegalArgumentException("Give the time as HH:MM")
        var target = now.withHour(time.hour).withMinute(time.minute).withSecond(0).withNano(0)
        if (!target.isAfter(now)) target = target.plusDays(1)
        return target.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    }
}
