package dev.hermes.android.assist

/**
 * Beacon as the phone's digital assistant. The assist gesture starts [AssistEntryActivity], which hands one token to
 * BeaconActivity; the token is what makes a gesture start at most one call, however often the activity is recreated.
 */
internal const val ACTION_ASSIST_CALL = "dev.hermes.android.action.ASSIST_CALL"
internal const val EXTRA_ASSIST_TOKEN = "dev.hermes.android.extra.ASSIST_TOKEN"

/** A gesture older than this is not served: the owner pressed it a while ago and has moved on. */
internal const val ASSIST_WINDOW_MILLIS = 30_000L

/** Whether a gesture made at [issuedAt] can still be served at [now]. */
internal fun assistFresh(issuedAt: Long, now: Long): Boolean = now - issuedAt in 0..ASSIST_WINDOW_MILLIS

/** Tokens the entry activity has issued in this process and BeaconActivity has not yet taken. An outside app cannot guess one. */
internal object AssistGestures {
    private val issued = LinkedHashMap<String, Long>()
    private var counter = 0L

    /** A new token for one gesture; only the last few are kept. */
    @Synchronized fun issue(now: Long = System.currentTimeMillis()): String {
        val token = "${now.toString(36)}-${(++counter).toString(36)}-${java.util.UUID.randomUUID()}"
        issued[token] = now
        while (issued.size > 8) issued.remove(issued.keys.first())
        return token
    }

    /** The time the gesture was made, once. Null for an unknown, already taken or too old token. */
    @Synchronized fun take(token: String?, now: Long = System.currentTimeMillis()): Long? {
        val at = token?.let { issued.remove(it) } ?: return null
        return at.takeIf { assistFresh(it, now) }
    }

    @Synchronized fun clear() { issued.clear() }
}

/** What the app knows when a gesture arrives. */
internal data class AssistReadiness(
    val restoring: Boolean,
    val loading: Boolean,
    val authenticated: Boolean,
    val setupWizard: Boolean,
    val callActive: Boolean,
    val pluginAbsent: Boolean,
    /** Beacon is the phone's chosen assistant. Any app can open the assist entry, so only then does it start a call. */
    val assistantRole: Boolean = false,
)

/** What the gesture does next. */
internal enum class AssistStep {
    /** The app is still starting; look again when its state changes. */
    Wait,
    /** Not signed in: the sign-in screen is already showing, and the gesture is used up. */
    SignIn,
    /** A call is running: bring it to the front. */
    ShowCall,
    /** Calls need the Beacon plugin and it is missing: open the assistant chat, which says so. */
    OpenChat,
    /** Start the call the chat's call button starts. A missing voice key or microphone is asked for there. */
    Call,
    /** The app took too long to be ready. */
    Expired,
}

/**
 * The voice key and the microphone are deliberately not inputs: the chat's own call entry asks for either one with its usual
 * screen or permission request, so the gesture never decides on them and never fails silently.
 */
internal fun assistStep(readiness: AssistReadiness, issuedAt: Long, now: Long): AssistStep = when {
    !assistFresh(issuedAt, now) -> AssistStep.Expired
    readiness.restoring || readiness.loading -> AssistStep.Wait
    !readiness.authenticated || readiness.setupWizard -> AssistStep.SignIn
    readiness.callActive -> AssistStep.ShowCall
    readiness.pluginAbsent || !readiness.assistantRole -> AssistStep.OpenChat
    else -> AssistStep.Call
}
