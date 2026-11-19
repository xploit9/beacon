package dev.hermes.agentphone.helper

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.hermes.agentphone.helper.core.Arm
import dev.hermes.agentphone.helper.core.Fills
import dev.hermes.agentphone.helper.core.Helper
import dev.hermes.agentphone.helper.core.Item
import dev.hermes.agentphone.helper.core.Notes
import dev.hermes.agentphone.helper.core.Reply
import dev.hermes.agentphone.helper.core.Senders
import dev.hermes.agentphone.helper.core.Sessions
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The helper's process-wide state, in memory only: the channel sessions, the arm and pending releases, recent
 * notifications and the clip. It answers the host's requests (contract §5.1). Logs carry types and counts, never a value.
 */
class Runtime private constructor(private val app: Context) : Helper {
    private val clock: () -> Long = System::currentTimeMillis
    private val random = SecureRandom()
    val fills = Fills(clock, ::newId)
    val notes = Notes(clock)
    private val clip = Clip(app)
    private val main = Handler(Looper.getMainLooper())
    @Volatile var onIdle: (() -> Unit)? = null
    val sessions = Sessions(GuestDialer, this, ::hello, idle = { main.post { onIdle?.invoke() } })

    fun newId(): String {
        val b = ByteArray(18)
        random.nextBytes(b)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private fun hello(): Map<String, Any?> {
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        return linkedMapOf(
            "t" to "hello", "proto_min" to 1, "proto_max" to 1,
            "helper_version" to (info.versionName ?: "0"), "version_code" to info.longVersionCode.toInt(),
        )
    }

    override fun request(t: String, message: Map<String, Any?>): Reply {
        Log.i(TAG, "request $t")
        return when (t) {
            "ping" -> Reply("pong")
            "context" -> Reply("context_result", mapOf("context" to fills.lastContext))
            "arm" -> {
                @Suppress("UNCHECKED_CAST")
                val items = (message["items"] as List<Map<String, Any?>>).map {
                    Item(it["item_id"] as String, it["label"] as String, it["roles"] as List<String>)
                }
                fills.arm(Arm(
                    message["arm_id"] as String, message["kind"] as String, message["package"] as String,
                    message["web_domain"] as String?, items, clock() + (message["ttl_ms"] as Long),
                ))
                Reply("ack")
            }
            "disarm" -> if (fills.disarm(message["arm_id"] as String?)) Reply("ack") else Reply.error("not_armed")
            "arm_code" -> {
                if (!listening) return Reply.error("unavailable")
                val code = notes.code(Senders.of(message["senders"] as Map<*, *>), message["max_age_ms"] as Long)
                if (code != null) {
                    val armId = message["arm_id"] as String
                    fills.arm(Arm(
                        armId, "otp", message["package"] as String, null, listOf(Item(armId, message["label"] as String, listOf("otp"))),
                        clock() + (message["ttl_ms"] as Long), code,
                    ))
                }
                Reply("code_result", mapOf("found" to (code != null)))
            }
            "note_password" -> {
                if (!listening) return Reply.error("unavailable")
                val value = notes.password(Senders.of(message["senders"] as Map<*, *>), message["max_age_ms"] as Long)
                if (value == null) Reply("note_password_result", mapOf("found" to false))
                else Reply("note_password_result", linkedMapOf("found" to true, "value" to value))
            }
            "note_link" -> {
                if (!listening) return Reply.error("unavailable")
                val url = notes.link(Senders.of(message["senders"] as Map<*, *>), message["max_age_ms"] as Long)
                if (url == null) Reply("note_link_result", mapOf("found" to false))
                else Reply("note_link_result", linkedMapOf("found" to true, "url" to url))
            }
            // Opening a link is the host's job (contract revision 3): Android 16 blocks a background app's activity starts
            // ("Background activity launch blocked!", measured on the agent phone), and the helper has no exception for it.
            "open_link" -> Reply.error("unavailable")
            "clip_set" -> if (clip.set(message["text"] as String, message["ttl_ms"] as Long)) Reply("ack") else Reply.error("no_clipboard")
            "clip_clear" -> { clip.clear(); Reply("ack") }
            else -> Reply.error("bad_request")
        }
    }

    override fun answer(req: String, values: Map<String, String>?) = fills.answer(req, values)

    override fun linkEnded() {
        fills.reset()
        clip.clear()
        Log.i(TAG, "link ended")
    }

    /** Where the app came from (contract §6.1 `source`): the host trusts a browser only from the system image or Play. */
    fun source(pkg: String): String = try {
        val pm = app.packageManager
        when {
            pm.getApplicationInfo(pkg, 0).flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "system"
            pm.getInstallSourceInfo(pkg).installingPackageName == "com.android.vending" -> "play"
            else -> "other"
        }
    } catch (e: Exception) {
        "other"
    }

    fun certs(pkg: String): List<String> = try {
        val info = app.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val sigs = when {
            info == null -> emptyArray()
            info.hasMultipleSigners() -> info.apkContentsSigners
            else -> info.signingCertificateHistory
        }
        sigs.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.sorted()
    } catch (e: PackageManager.NameNotFoundException) {
        emptyList()
    }

    companion object {
        const val TAG = "BeaconHelper"
        @Volatile private var instance: Runtime? = null
        /** Whether the notification reader is connected (it reads codes and temporary passwords). */
        @Volatile var listening = false

        fun of(context: Context): Runtime = instance ?: synchronized(this) {
            instance ?: Runtime(context.applicationContext).also { instance = it }
        }
    }
}
