package dev.hermes.android.phone

import android.app.KeyguardManager
import android.content.Context
import android.os.Looper
import dev.hermes.android.BeaconApplication
import dev.hermes.android.ui.nativeLanguageFor
import dev.hermes.android.ui.translateUi
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The phone's lock screen, read now: true while the keyguard shows or the device still needs its PIN, pattern or password, and when
 * it cannot be read at all. The one lock source of the assistant overlay's text gates, the call countdown, the approval taps and a
 * conversation's trust (voice/CallTrust.kt).
 */
internal fun deviceLockedNow(context: Context): Boolean =
    context.getSystemService(KeyguardManager::class.java)?.let { it.isKeyguardLocked || it.isDeviceLocked } != false

/**
 * Phone tools on this phone: calendar, reminders, alarms and timers, contacts, calls and message drafts. One executor for live calls,
 * the assistant overlay and Hermes, so the same rules (switch on, permission granted, bounded arguments, the owner's tap for every
 * change, rate limits, the activity list) hold everywhere.
 */
object PhoneTools {
    @Volatile private var engine: PhoneEngine? = null
    @Volatile private var appContext: Context? = null

    private fun engine(context: Context): PhoneEngine {
        val app = context.applicationContext
        appContext = app
        return engine ?: synchronized(this) {
            engine ?: PhoneEngine(PhoneAccess.settings(app), CalendarProviderBackend(app), ContactsProviderBackend(app), IntentActionsBackend(app),
                PhoneActivityLog.of(app), PhoneRateLimiter(System::currentTimeMillis),
                locked = { deviceLockedNow(app) }).also { engine = it }
        }
    }

    /** Tests swap in an engine built on fakes. */
    internal fun useEngine(replacement: PhoneEngine?) { engine = replacement }

    /** All tool names of the contract. */
    val names: Set<String> get() = PhoneSpecs.names

    fun isWrite(name: String): Boolean = PhoneSpecs.isWrite(name)

    /** True when running [name] opens another app's screen, so it can only run while Beacon is in front after the owner's tap. */
    fun launchesApp(name: String): Boolean = PhoneSpecs.get(name)?.launchesApp == true

    /** The tools whose capability is switched on and whose Android permission is granted. A conversation declares exactly these. */
    fun enabledTools(context: Context): Set<String> = engine(context).enabledTools()

    /** The same for Hermes: empty while "Let Hermes use phone access" is off. */
    fun enabledTools(context: Context, origin: PhoneOrigin): Set<String> = engine(context).enabledTools(origin)

    /** "Let Hermes use phone access": off until the owner turns it on in Settings > Phone access. */
    fun hermesAccess(context: Context): Boolean = PhoneAccess.hermesAllowed(context)

    /** "Ask before Hermes reads": on until the owner turns it off. */
    fun askBeforeHermesReads(context: Context): Boolean = PhoneAccess.askBeforeHermesReads(context)

    /** Seconds of on-screen countdown before [name] from [origin] is placed without a tap: 3 for a call to a saved contact from a live
     *  call or the overlay, else 0 (the owner taps). */
    fun countdownSeconds(name: String, origin: PhoneOrigin, savedContact: Boolean): Int = PhoneCountdown.secondsFor(name, origin, savedContact)

    /** Whether [name] from [origin] must wait for the owner's tap: every write, and a Hermes read while "Ask before Hermes reads" is on. */
    fun needsApproval(context: Context, name: String, origin: PhoneOrigin): Boolean = engine(context).needsApproval(name, origin)

    /**
     * One short line for an approval card or a notification, e.g. "Add 'Dentist' Tue 4 Nov 3:00 PM to Personal". Off the main thread it
     * also names the calendar, contact and event; on the main thread it reads the arguments alone.
     */
    fun describe(name: String, args: JSONObject): String {
        val context = appContext
        if (context == null || Looper.myLooper() == Looper.getMainLooper()) return engine?.describeQuick(name, args) ?: fallback(name)
        return runCatching { runBlocking { engine(context).describeLive(name, args) } }.getOrElse { engine?.describeQuick(name, args) ?: fallback(name) }
    }

    private fun fallback(name: String) = PhoneLine(if (name in names) "Phone request" else "Request").text()

    /**
     * Checks [args] and looks up what the card must show (calendar, event and contact names, the number a call will dial). Throws
     * [PhoneFailure] with the contract's error code. What the card shows is exactly [PhonePrepared.runningArguments].
     */
    suspend fun prepare(context: Context, name: String, args: JSONObject, origin: PhoneOrigin): PhonePrepared = engine(context).prepare(name, args, origin)

    suspend fun run(context: Context, name: String, args: JSONObject, origin: PhoneOrigin, decision: PhoneDecision?): JSONObject =
        engine(context).run(name, args, origin, decision)

    /** Runs what [prepared] showed, so the tap and the action cannot differ. */
    suspend fun runPrepared(context: Context, prepared: PhonePrepared, origin: PhoneOrigin, decision: PhoneDecision?): JSONObject =
        engine(context).runPrepared(prepared, origin, decision)

    /** Puts an outcome the owner decided (denied, expired) in the activity list. */
    fun record(context: Context, name: String, line: PhoneLine, origin: PhoneOrigin, outcome: String) = engine(context).record(name, line, origin, outcome)

    /** Puts a card whose conversation ended unanswered in the activity list, unless its action had already started. */
    fun recordCancelled(context: Context, prepared: PhonePrepared, origin: PhoneOrigin) = engine(context).recordCancelled(prepared, origin)

    /** [line] in the phone's language: its fixed words translated, the owner's own words as written and isolated. For a card or notice outside Compose. */
    fun render(context: Context, line: PhoneLine): String {
        val language = nativeLanguageFor(context.resources.configuration.locales[0])
        val dictionary = if (language == "en") null else (context.applicationContext as? BeaconApplication)?.cachedTranslations?.optJSONObject(language)
        return PhoneLine(dictionary?.let { translateUi(it, line.template) } ?: line.template, line.args).shown()
    }

    /** The line for a request that failed before it had a card. */
    fun plainLine(name: String): PhoneLine = PhoneLine(engine?.fallbackLine(name) ?: "Phone request")
}
