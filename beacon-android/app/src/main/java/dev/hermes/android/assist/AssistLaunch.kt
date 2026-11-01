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

/** The overlay's own hand-offs: the same token also names what the app opens. Read only after a token was taken, never alone. */
internal const val EXTRA_ASSIST_PURPOSE = "dev.hermes.android.extra.ASSIST_PURPOSE"
internal const val EXTRA_ASSIST_SESSION = "dev.hermes.android.extra.ASSIST_SESSION"
internal const val EXTRA_ASSIST_PROFILE = "dev.hermes.android.extra.ASSIST_PROFILE"

/** What a gesture asks the app to do once it is ready. The assist gesture itself is [Call]; the overlay's buttons are the others. */
internal enum class AssistPurpose { Call, OpenAssistant, ShowCall, OpenChat }

/** One hand-off: its purpose and, for [AssistPurpose.OpenChat], the conversation to open. */
internal data class AssistTarget(val purpose: AssistPurpose = AssistPurpose.Call, val session: String = "", val profile: String = "")

/** Tokens the entry activity has issued in this process and BeaconActivity has not yet taken. An outside app cannot guess one. */
internal object AssistGestures {
    private class Issued(val at: Long, val target: AssistTarget)
    private val issued = LinkedHashMap<String, Issued>()
    /** What each taken gesture asked for, by the time [take] returned; the activity keeps only that time across a recreation. */
    private val taken = LinkedHashMap<Long, AssistTarget>()
    private var counter = 0L

    /** A new token for one gesture; only the last few are kept. */
    @Synchronized fun issue(now: Long = System.currentTimeMillis(), target: AssistTarget = AssistTarget()): String {
        val token = "${now.toString(36)}-${(++counter).toString(36)}-${java.util.UUID.randomUUID()}"
        issued[token] = Issued(now, target)
        while (issued.size > 8) issued.remove(issued.keys.first())
        return token
    }

    /** The time the gesture was made, once. Null for an unknown, already taken or too old token. */
    @Synchronized fun take(token: String?, now: Long = System.currentTimeMillis()): Long? {
        val entry = token?.let { issued.remove(it) } ?: return null
        if (!assistFresh(entry.at, now)) return null
        taken[entry.at] = entry.target
        while (taken.size > 8) taken.remove(taken.keys.first())
        return entry.at
    }

    /** What the gesture taken at [at] asked for; null once this process no longer knows it. */
    @Synchronized fun target(at: Long): AssistTarget? = taken[at]

    @Synchronized fun clear() { issued.clear(); taken.clear() }
}

/** The purpose an overlay intent carried. Only used after the process restarted and forgot the token it issued. */
internal fun assistTargetOf(purpose: String?, session: String?, profile: String?): AssistTarget? {
    val known = AssistPurpose.entries.firstOrNull { it.name == purpose } ?: return null
    return AssistTarget(known, session.orEmpty(), profile.orEmpty())
}

/** What the app does for one served gesture. */
internal enum class AssistAction { StartCall, ShowCall, OpenAssistant, OpenChat, Expired, None }

/**
 * The routing step decides whether anything may happen; the purpose decides what. A running call always comes first, except for
 * another chat the owner chose; nothing but the gesture itself ever starts a call.
 */
internal fun assistAction(step: AssistStep, purpose: AssistPurpose): AssistAction = when (step) {
    AssistStep.Call -> when (purpose) {
        AssistPurpose.Call -> AssistAction.StartCall
        AssistPurpose.OpenChat -> AssistAction.OpenChat
        // No call is running any more: the assistant's chat, which holds what the call said.
        AssistPurpose.OpenAssistant, AssistPurpose.ShowCall -> AssistAction.OpenAssistant
    }
    AssistStep.ShowCall -> if (purpose == AssistPurpose.OpenChat) AssistAction.OpenChat else AssistAction.ShowCall
    AssistStep.OpenChat -> if (purpose == AssistPurpose.OpenChat) AssistAction.OpenChat else AssistAction.OpenAssistant
    AssistStep.Expired -> AssistAction.Expired
    AssistStep.SignIn, AssistStep.Wait -> AssistAction.None
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
