package dev.hermes.android.data

import android.content.SharedPreferences
import dev.hermes.android.agentphone.AgentPhoneLines
import dev.hermes.android.agentphone.code
import dev.hermes.android.agentphone.lineArgs
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// {name}'s phone: the app side of the agent's own phone (EMU-DESIGN rev 4 §3, §8). The routes are L2's contract v1
// (emu-l2/.unlazy/emu-l2/CONTRACT.md §1, §3, §4) with Root's additions to GET /mobile/agent-phone (msg_c579205379f9,
// msg_ed1d395edfa5: `setup`, `storage_bytes`, `helper`, `in_use`; POST helper/update), and L3's approvals v1 (§2 kinds, §4 payload,
// §6 routes) for the Needs-you notices, the activity and the policy. Every route is under /api/plugins/beacon and takes the phone's
// owning profile as beacon_profile. No answer carries a path, port, serial or token, and nothing here logs a body.

internal const val AGENT_PHONE_PATH = "/api/plugins/beacon/mobile/agent-phone"

/** Hermes' guide to dashboard sign-in, for the sign-in row's How when the row carries none (L1a's SIGN_IN_HOW). */
internal const val AGENT_PHONE_SIGN_IN_GUIDE = "https://hermes-agent.nousresearch.com/docs/user-guide/features/web-dashboard#authentication-gated-mode"

/** The idle time after which the phone turns itself off (§7.1, §7.2: the keeper stops it after 30 min without use). */

/**
 * What the phone can grow to on the computer, the "About 16 GB free space" of Before you start (§3, §8): the system image and tools (about
 * 3.4 GB), the phone's own data (up to 8 GB, §2.4) and its quick-start snapshot (about 4.3 GB, S7).
 */
internal const val AGENT_PHONE_DISK_BYTES = 16_000_000_000L

/** Chrome fills through Beacon only from this version (§6.6, S8). */
internal const val AGENT_PHONE_CHROME_AUTOFILL = 135

/** One answer of a route: its HTTP status and JSON body, an error body included (it carries the plain line). Status 0: Hermes didn't answer. */
internal class AgentPhoneAnswer(val status: Int, val body: JSONObject) {
    val ok: Boolean get() = status in 200..299
}

/** The routes the app calls, relative to [AGENT_PHONE_PATH] ("" is the status). The real one goes through [BeaconClient]; JVM tests answer from the contract. */
internal fun interface AgentPhoneEndpoint {
    suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer
}

/**
 * The app's client, scoped to the phone's owning profile. The three routes whose refusal is a body with its own line (start's 409,
 * terms' 502 and install's 409) are read here, because the shared error path keeps only `detail` and turns a 502 into "Hermes is busy";
 * every other route goes through [BeaconClient.nativeRequest], which keeps Beacon's turn-on retry and removed-phone handling.
 */
internal class ClientAgentPhoneEndpoint(private val client: BeaconClient) : AgentPhoneEndpoint {
    override suspend fun call(method: String, route: String, body: JSONObject?, timeoutMillis: Long): AgentPhoneAnswer {
        // A route may carry its own query ("/activity?limit=50"); the profile is added by the client.
        val path = AGENT_PHONE_PATH + route.substringBefore('?')
        val query = route.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val payload = body ?: if (method == "GET") null else JSONObject()
        return try {
            if (method == "POST" && route in BODY_LINE_ROUTES || method == "GET" && route == "/terms") {
                val request = client.request(path, query + ("beacon_profile" to client.auth.profile), method,
                    payload?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                client.nativeResponse(request, timeoutMillis) { response ->
                    val raw = response.peekBody(MAX_BODY).string()
                    AgentPhoneAnswer(response.code, runCatching { JSONObject(raw) }.getOrElse { JSONObject() })
                }
            } else AgentPhoneAnswer(200, client.nativeRequest(path, method, payload, query, timeoutMillis = timeoutMillis))
        } catch (error: CancellationException) {
            throw error
        } catch (error: ApiFailure) {
            AgentPhoneAnswer(error.status, json("detail" to error.message))
        } catch (_: IOException) {
            AgentPhoneAnswer(0, JSONObject())
        }
    }

    private companion object {
        val BODY_LINE_ROUTES = setOf("/start", "/install")
        const val MAX_BODY = 1L shl 20
    }
}

// -- what the routes answer ------------------------------------------------------------------------------------------------------

internal enum class AgentPhonePower { Off, Starting, On, Stopping }

/** The Beacon helper on the phone: [Off] is installed but not Android's autofill service (logins won't fill); [Missing] is not on the phone yet. */
internal enum class AgentPhoneHelper { Ready, Update, Off, Missing }

internal enum class AgentPhoneInstallState { Idle, Running, Paused, Cancelled, Failed, Done }

internal enum class AgentPhoneInstallPhase { Download, Check, SetUp }

/** Install progress (§3 steps 4-6): bytes are the whole download; [line]/[fix] say what stopped it; [retry] offers Try again. */
internal data class AgentPhoneInstall(
    val state: AgentPhoneInstallState = AgentPhoneInstallState.Idle,
    val phase: AgentPhoneInstallPhase? = null,
    val doneBytes: Long? = null,
    val totalBytes: Long? = null,
    val line: String? = null,
    val fix: String? = null,
    val retry: Boolean = false,
    /** The codes of [line] and [fix] and [line]'s names (revision 5 §11.1); null from an older plugin. */
    val lineCode: String? = null,
    val lineArgs: Map<String, String> = emptyMap(),
    val fixCode: String? = null,
) {
    val working: Boolean get() = state == AgentPhoneInstallState.Running
    /** Paused or failed with something to pick up again. */
    val resumable: Boolean get() = state == AgentPhoneInstallState.Paused || state == AgentPhoneInstallState.Failed
}

/** What the phone itself says about the setup checklist (Root, msg_c579205379f9). Null = can't be read, so the owner may tick it. */
internal data class AgentPhoneFacts(val google: Boolean? = null, val chrome: Int? = null, val chromeAutofill: Boolean? = null, val logins: Int? = null)

internal data class AgentPhoneLease(val human: Boolean, val you: Boolean, val away: Boolean)

internal enum class AgentPhoneDriverState(val wire: String, val label: String) {
    Running("running", "Running"), Completed("completed", "Done"), Failed("failed", "Failed"), Cancelled("cancelled", "Cancelled"),
}

internal data class AgentPhoneDriverTask(val id: String, val state: AgentPhoneDriverState, val steps: Int, val message: String?)

internal data class AgentPhoneStatus(
    val installed: Boolean = false,
    val profile: String? = null,
    val yours: Boolean = false,
    val power: AgentPhonePower = AgentPhonePower.Off,
    val lease: AgentPhoneLease? = null,
    /** The developer-tools line while one is up, else null. */
    val warning: String? = null,
    /** The last start's error line, else null. */
    val error: String? = null,
    val install: AgentPhoneInstall = AgentPhoneInstall(),
    val facts: AgentPhoneFacts? = null,
    val storageBytes: Long? = null,
    val helper: AgentPhoneHelper? = null,
    /** The agent made a phone call in the last 2 minutes while holding the phone; null = this Beacon doesn't say. */
    val inUse: Boolean? = null,
    /** The codes of [warning] and [error] and [error]'s names (revision 5 §11.1); null from an older plugin. */
    val warningCode: String? = null,
    val errorCode: String? = null,
    val errorArgs: Map<String, String> = emptyMap(),
    /** The plugin implements the PhoneDriver task route. Its runtime readiness comes from a task result. */
    val supportsPhoneDriver: Boolean = false,
    val driverTask: AgentPhoneDriverTask? = null,
)

private val DRIVER_TASK_ID = Regex("[A-Za-z0-9_-]{1,80}")

internal fun validAgentPhoneTask(task: String): Boolean =
    task.isNotBlank() && task.codePointCount(0, task.length) <= 2_000

internal enum class AgentPhoneCheckStatus { Ready, Warn, Fail, Unknown }

/** One host check (L1a's row): [line] and [fix] are server lines (see [agentPhoneLine]); [how] is a guide link for its fix. */
internal data class AgentPhoneCheckRow(
    val id: String, val status: AgentPhoneCheckStatus, val line: String? = null, val fix: String? = null, val how: String? = null, val needsContinue: Boolean = false,
    /** The codes of [line] and [fix] and [line]'s names (revision 5 §11.1); null from an older plugin. */
    val lineCode: String? = null, val lineArgs: Map<String, String> = emptyMap(), val fixCode: String? = null,
)

internal data class AgentPhoneChecks(val ok: Boolean, val needsContinue: Boolean, val rows: List<AgentPhoneCheckRow>)

/** Google's licence text exactly as the server fetched it: shown verbatim, never translated or changed. */
internal data class AgentPhoneLicence(val id: String, val text: String)

internal data class AgentPhoneTerms(val licences: List<AgentPhoneLicence>, val downloadBytes: Long?, val installedBytes: Long?, val peakBytes: Long?)

internal enum class AgentPhoneAsk(val wire: String) { PaymentsAndSends("payments_and_sends"), EveryAction("every_action") }

internal data class AgentPhonePolicy(val enabled: Boolean = true, val ask: AgentPhoneAsk = AgentPhoneAsk.PaymentsAndSends)

/** One line of "What {name} did on its phone" (approvals v1 §3): labels only. [seq] is the row's number, the one a done notice names (rev 3). */
internal data class AgentPhoneActivity(val atMillis: Long, val app: String, val action: String, val label: String, val approved: Boolean, val outcome: String, val seq: Long = 0)

/** An open `needs_you` notice (approvals v1 §2): something only the owner can do on the phone. */
internal data class AgentPhoneNeedsYou(val id: String, val line: String, val app: String)

/** Every answer the screens use: the value, or one plain line with one action. */
internal sealed interface AgentPhoneResult<out T> {
    data class Done<T>(val value: T) : AgentPhoneResult<T>
    data class Refused(val line: AgentPhoneLine, val status: Int) : AgentPhoneResult<Nothing>
}

internal fun <T> AgentPhoneResult<T>.valueOrNull(): T? = (this as? AgentPhoneResult.Done)?.value

// -- reading the bodies -----------------------------------------------------------------------------------------------------------

private fun JSONObject.text(key: String, limit: Int = 600): String? =
    (opt(key) as? String)?.takeIf { it.isNotBlank() && it.length <= limit }?.filter { !it.isISOControl() || it == '\n' }
private fun JSONObject.flag(key: String): Boolean? = opt(key) as? Boolean
private fun JSONObject.whole(key: String): Long? = (opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0 }?.toLong()
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

internal fun parseAgentPhoneInstall(body: JSONObject?): AgentPhoneInstall {
    if (body == null) return AgentPhoneInstall()
    val state = when (body.text("state")) {
        "running" -> AgentPhoneInstallState.Running
        "paused" -> AgentPhoneInstallState.Paused
        "cancelled" -> AgentPhoneInstallState.Cancelled
        "failed" -> AgentPhoneInstallState.Failed
        "done" -> AgentPhoneInstallState.Done
        else -> AgentPhoneInstallState.Idle
    }
    val phase = when (body.text("phase")) {
        "download" -> AgentPhoneInstallPhase.Download
        "check" -> AgentPhoneInstallPhase.Check
        "set up" -> AgentPhoneInstallPhase.SetUp
        else -> null
    }
    return AgentPhoneInstall(state, phase, body.whole("done_bytes"), body.whole("total_bytes")?.takeIf { it > 0 }, body.text("line"), body.text("fix"), body.flag("retry") == true,
        body.code(), body.lineArgs(), body.code("fix_reason"))
}

internal fun parseAgentPhoneStatus(body: JSONObject): AgentPhoneStatus {
    val power = when (body.text("phone")) {
        "on" -> AgentPhonePower.On
        "starting" -> AgentPhonePower.Starting
        "stopping" -> AgentPhonePower.Stopping
        else -> AgentPhonePower.Off
    }
    val lease = body.optJSONObject("lease")?.let { AgentPhoneLease(it.text("holder") == "human", it.flag("you") == true, it.flag("away") == true) }
    val facts = body.optJSONObject("setup")?.let { setup ->
        AgentPhoneFacts(setup.flag("google"), setup.whole("chrome")?.toInt(), setup.flag("chrome_autofill"), setup.whole("logins")?.toInt())
    }
    val helper = when (body.text("helper")) {
        "ready" -> AgentPhoneHelper.Ready
        "update" -> AgentPhoneHelper.Update
        "off" -> AgentPhoneHelper.Off
        "missing" -> AgentPhoneHelper.Missing
        else -> null
    }
    val installed = body.flag("installed") == true
    val yours = body.flag("yours") == true
    val supportsPhoneDriver = body.opt("control_driver") == "phonedriver"
    return AgentPhoneStatus(
        installed = installed, profile = body.text("profile", 64), yours = yours, power = power, lease = lease,
        warning = body.text("warning"), error = body.text("error"), install = parseAgentPhoneInstall(body.optJSONObject("install")),
        facts = facts, storageBytes = body.whole("storage_bytes"), helper = helper, inUse = body.flag("in_use"),
        warningCode = body.code("warning_reason"), errorCode = body.code("error_reason"), errorArgs = body.lineArgs("error_args"),
        supportsPhoneDriver = supportsPhoneDriver,
        driverTask = if (supportsPhoneDriver && installed && yours) body.optJSONObject("driver_task")?.let(::parseAgentPhoneDriverTask) else null,
    )
}

internal fun parseAgentPhoneDriverTask(body: JSONObject): AgentPhoneDriverTask? {
    val id = (body.opt("id") as? String)?.takeIf(DRIVER_TASK_ID::matches) ?: return null
    val state = AgentPhoneDriverState.entries.firstOrNull { it.wire == body.opt("state") } ?: return null
    val count = (body.opt("steps") as? Number)?.toDouble() ?: return null
    val steps = count.toInt().takeIf { it >= 0 && it.toDouble() == count } ?: return null
    return AgentPhoneDriverTask(id, state, steps, body.text("message", 240))
}

internal fun parseAgentPhoneChecks(body: JSONObject): AgentPhoneChecks {
    val rows = body.optJSONArray("rows").objects().mapNotNull { row ->
        val id = row.text("id", 40) ?: return@mapNotNull null
        val status = when (row.text("status")) {
            "ready" -> AgentPhoneCheckStatus.Ready
            "warn" -> AgentPhoneCheckStatus.Warn
            "fail" -> AgentPhoneCheckStatus.Fail
            else -> AgentPhoneCheckStatus.Unknown
        }
        // A guide link is only ever an https page; anything else is dropped rather than opened.
        val how = row.text("how", 300)?.takeIf { it.startsWith("https://") }
        AgentPhoneCheckRow(id, status, row.text("line"), row.text("fix"), how, row.flag("needs_continue") == true, row.code(), row.lineArgs(), row.code("fix_reason"))
    }
    return AgentPhoneChecks(body.flag("ok") == true && rows.none { it.status == AgentPhoneCheckStatus.Fail }, body.flag("needs_continue") == true || rows.any { it.needsContinue }, rows)
}

internal fun parseAgentPhoneTerms(body: JSONObject): AgentPhoneTerms? {
    val licences = body.optJSONArray("licences").objects().mapNotNull { item ->
        val id = item.text("id", 120) ?: return@mapNotNull null
        val text = (item.opt("text") as? String)?.takeIf { it.isNotBlank() && it.length <= 400_000 } ?: return@mapNotNull null
        AgentPhoneLicence(id, text)
    }
    if (licences.isEmpty()) return null
    val sizes = body.optJSONObject("sizes") ?: JSONObject()
    return AgentPhoneTerms(licences, sizes.whole("download_bytes"), sizes.whole("installed_bytes"), sizes.whole("peak_bytes"))
}

internal fun parseAgentPhonePolicy(body: JSONObject): AgentPhonePolicy =
    AgentPhonePolicy(body.flag("enabled") != false, if (body.text("ask") == AgentPhoneAsk.EveryAction.wire) AgentPhoneAsk.EveryAction else AgentPhoneAsk.PaymentsAndSends)

internal fun parseAgentPhoneActivity(body: JSONObject): List<AgentPhoneActivity> = body.optJSONArray("activity").objects().mapNotNull { row ->
    val at = (row.opt("at") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 } ?: return@mapNotNull null
    AgentPhoneActivity((at * 1000).toLong(), row.text("app", 80).orEmpty(), row.text("action", 40).orEmpty(), row.text("label", 200).orEmpty(),
        row.opt("approved") == true || row.opt("approved") == 1, row.text("outcome", 40).orEmpty(),
        (row.opt("seq") as? Number)?.toLong()?.takeIf { it > 0 } ?: 0)
}

private val APPROVAL_ID = Regex("[0-9a-f]{32}")

internal fun parseAgentPhoneNeedsYou(body: JSONObject): List<AgentPhoneNeedsYou> = body.optJSONArray("approvals").objects().mapNotNull { record ->
    if (record.text("kind") != "needs_you") return@mapNotNull null
    val id = record.text("id", 32)?.takeIf(APPROVAL_ID::matches) ?: return@mapNotNull null
    AgentPhoneNeedsYou(id, record.text("line", 200) ?: return@mapNotNull null, record.text("app", 80).orEmpty())
}

// -- the server's lines, in the app's words -------------------------------------------------------------------------------------

/** The one thing a line lets the owner do. */
internal enum class AgentPhoneAction { None, TryAgain, CheckAgain, Resume, StartAgain, ReadTerms, How, Update, ChooseProfile }

/**
 * A line the app shows: an English [template] the app translates, its [args] (`{0}`, already formatted by the server), the one
 * [action] it offers, and the server's own words in [raw] for Details when the template is a summary of them.
 */
internal data class AgentPhoneLine(
    val template: String, val args: List<String> = emptyList(), val action: AgentPhoneAction = AgentPhoneAction.None, val raw: String = "",
    /** The host's code for [raw] (revision 5 §11.1) and its names: [template] then fills `{key}` from [named], and Details says the code's own words. */
    val code: String? = null, val named: Map<String, String> = emptyMap(),
) {
    /** Whether Details has more to say than the line itself. */
    val hasDetails: Boolean get() = raw.isNotBlank() && raw != template
}

/** The server's lines by their English words (L1a checks and install, L2's routes); `{name}` and `{0}` are the server's holes. */
private val KNOWN_LINES: List<Pair<String, AgentPhoneAction>> = listOf(
    // L1a agent_phone_checks.LINES and agent_phone_install.WORDS / FIX
    "This computer can't run a virtual phone." to AgentPhoneAction.CheckAgain,
    "There is no phone software for this kind of computer." to AgentPhoneAction.None,
    "Give the container read and write access to /dev/kvm, or run Hermes on a computer instead." to AgentPhoneAction.CheckAgain,
    "Turn on Windows Hypervisor Platform in Windows Features, then restart the computer." to AgentPhoneAction.CheckAgain,
    "Turn on virtualisation (KVM) on this computer." to AgentPhoneAction.CheckAgain,
    "This Mac can't run it. Use another computer for Hermes." to AgentPhoneAction.None,
    "Checked again with the test download." to AgentPhoneAction.None,
    "The computer is low on memory. Close something there and try again." to AgentPhoneAction.CheckAgain,
    "This computer has less than 16 GB memory, so the phone may be slow." to AgentPhoneAction.None,
    "Couldn't read the computer's memory." to AgentPhoneAction.None,
    "Not enough free space. Free up {0} GB on the Hermes computer." to AgentPhoneAction.CheckAgain,
    "Space is tight: about {0} GB free." to AgentPhoneAction.None,
    "The Hermes computer can't reach Google's download site." to AgentPhoneAction.CheckAgain,
    "Check the Hermes computer's internet." to AgentPhoneAction.None,
    "Close other virtual phones on this computer, then try again." to AgentPhoneAction.CheckAgain,
    "Turn on Hermes sign-in first." to AgentPhoneAction.How,
    "Give the Hermes dashboard a sign-in and the address you open it at, then restart Hermes." to AgentPhoneAction.How,
    "Couldn't check Hermes sign-in." to AgentPhoneAction.CheckAgain,
    "Make sure Hermes' dashboard is on, then check again." to AgentPhoneAction.CheckAgain,
    "{name}'s phone can't start while Hermes' dashboard is off." to AgentPhoneAction.TryAgain,
    "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to." to AgentPhoneAction.CheckAgain,
    "Check this computer first." to AgentPhoneAction.CheckAgain,
    "The phone uses Google's Android software. Read and accept to continue." to AgentPhoneAction.ReadTerms,
    "Download stopped. Check the Hermes computer's internet." to AgentPhoneAction.Resume,
    "A download was damaged. Getting it again." to AgentPhoneAction.None,
    "Beacon needs an update to get the phone. Update Beacon, then try again." to AgentPhoneAction.Update,
    "The phone is already being set up." to AgentPhoneAction.None,
    "The phone software doesn't run here. Check the computer's updates, then try again." to AgentPhoneAction.CheckAgain,
    // L2 agent_phone_api
    "{name}'s phone needs a Beacon update." to AgentPhoneAction.Update,
    "{name}'s phone isn't set up yet." to AgentPhoneAction.None,
    "{name}'s phone belongs to another profile." to AgentPhoneAction.ChooseProfile,
    "The phone didn't start." to AgentPhoneAction.TryAgain,
    "Read and accept the terms first." to AgentPhoneAction.ReadTerms,
    // L2's helper update: helper/update's 409 lines and the status's error after a 202 (agent_phone_api HELPER_FAILED, HELPER_DAMAGED)
    "The helper didn't install. Try again." to AgentPhoneAction.TryAgain,
    "Beacon's helper file is damaged. Update Beacon." to AgentPhoneAction.Update,
    "This Beacon has no helper to install." to AgentPhoneAction.Update,
    "Turn the phone on first." to AgentPhoneAction.None,
    // L1b's start errors that already speak plainly
    "The phone is busy. Try again in a minute." to AgentPhoneAction.TryAgain,
)

/** Server words that mean one of the app's own lines (§3's error list); the server's words stay for Details. */
private val SUMMARIZED_LINES: Map<String, AgentPhoneLine> = mapOf(
    "Busy, try again." to AgentPhoneLine(AGENT_PHONE_BUSY, action = AgentPhoneAction.TryAgain),
    "Update in progress" to AgentPhoneLine(AGENT_PHONE_UPDATING, action = AgentPhoneAction.TryAgain),
    "Nothing to resume." to AgentPhoneLine("Nothing to resume. Start again.", action = AgentPhoneAction.StartAgain),
    "Something went wrong." to AgentPhoneLine(AGENT_PHONE_WENT_WRONG, action = AgentPhoneAction.TryAgain),
    "The phone is not set up yet." to AgentPhoneLine("{name}'s phone isn't set up yet."),
    // A short line on the screen; the host's full reason stays behind Details (AGENTUX-121 P2-1). Fix lines are never summarized: the
    // container's /dev/kvm sentence is the only fix its row has (REVIEW-FIX-UI-121).
    "Close Android developer tools on this computer, or apps on {name}'s phone can control the devices they connect to." to
        AgentPhoneLine(AGENT_PHONE_DEV_TOOLS, action = AgentPhoneAction.CheckAgain),
)

internal const val AGENT_PHONE_DEV_TOOLS = "Close Android developer tools on this computer."

/** The host's code for a known template (the contract writes {n} where L4's lines write {0}), so Details can say it in the app's words. */
private fun codeOf(template: String): String? = template.replace("{0}", "{n}").let { words -> AgentPhoneLines.templates.entries.firstOrNull { it.value == words }?.key }

internal const val AGENT_PHONE_DIDNT_START = "The phone didn't start."

internal const val AGENT_PHONE_HELPER_FAILED = "The helper didn't install. Try again."
internal const val AGENT_PHONE_WENT_WRONG = "Something went wrong. Try again."
internal const val AGENT_PHONE_BUSY = "Hermes is busy. Try again in a minute."
internal const val AGENT_PHONE_UPDATING = "Hermes is updating. Try again in a minute."
internal const val AGENT_PHONE_OFFLINE = "Can't reach Hermes. Check your connection."
internal const val AGENT_PHONE_NEEDS_UPDATE = "{name}'s phone needs a Beacon update."

private fun templatePattern(template: String): Regex {
    // Each hole matches what the server put there: a number for {0} ("9" or "1.5"), any short name for {name} (the start gate fills in "Beacon").
    val parts = Regex("""\{0\}|\{name\}""").split(template).map(Regex::escape)
    val holes = Regex("""\{0\}|\{name\}""").findAll(template).map { if (it.value == "{0}") """(\d+(?:[.,]\d+)?)""" else """.{1,80}?""" }.toList()
    return Regex(parts.foldIndexed("") { index, acc, part -> acc + part + (holes.getOrNull(index) ?: "") })
}

private val KNOWN_PATTERNS: List<Triple<String, AgentPhoneAction, Regex>> by lazy { KNOWN_LINES.map { (template, action) -> Triple(template, action, templatePattern(template)) } }

/** Every English line [agentPhoneLine] can return, for the translation checks. */
internal val AgentPhoneLineStrings: List<String> by lazy {
    (KNOWN_LINES.map { it.first } + SUMMARIZED_LINES.values.map { it.template } +
        listOf(AGENT_PHONE_DIDNT_START, AGENT_PHONE_WENT_WRONG, AGENT_PHONE_BUSY, AGENT_PHONE_UPDATING, AGENT_PHONE_OFFLINE, AGENT_PHONE_NEEDS_UPDATE)).distinct()
}

/** The action of each code whose words the app shows as they are: L4's known lines, by the contract's template ({0} is {n} there). */
private val CODE_ACTIONS: Map<String, AgentPhoneAction> by lazy {
    val known = KNOWN_LINES.associate { (template, action) -> template.replace("{0}", "{n}") to action }
    AgentPhoneLines.templates.mapNotNull { (code, template) -> known[template]?.let { code to it } }.toMap() + mapOf(
        // Lines the host words plainly that L4 had no entry for (the start's and the helper's).
        "phone.no_keeper" to AgentPhoneAction.Update, "phone.helper_damaged" to AgentPhoneAction.Update,
        "helper.no_pin" to AgentPhoneAction.Update, "helper.not_fetched" to AgentPhoneAction.None, "phone.not_its_folder" to AgentPhoneAction.None,
    )
}

/**
 * The line for a host [code] (revision 5): a code L4 summarizes (§3's error list: Hermes busy or updating, Something went wrong,
 * Nothing to resume, not set up) keeps L4's own line with the code's words in Details; a code with an action shows its own words; any
 * other (a start that failed inside the phone's process) is [fallback] ("The phone didn't start.") with the code's words in Details.
 * Null for a code the app doesn't know: the text decides then.
 */
private fun codedLine(code: String?, raw: String?, args: Map<String, String>, fallback: AgentPhoneLine?): AgentPhoneLine? {
    val template = code?.let { AgentPhoneLines.templates[it] } ?: return null
    val said = raw?.trim()?.takeIf(String::isNotBlank) ?: template
    SUMMARIZED_LINES[template]?.let { return it.copy(raw = said, code = code, named = args) }
    CODE_ACTIONS[code]?.let { return AgentPhoneLine(template, action = it, code = code, named = args) }
    return fallback?.copy(raw = said, code = code, named = args) ?: AgentPhoneLine(template, code = code, named = args)
}

/**
 * The app's line for a [raw] server line: by its [code] first (revision 5, [codedLine]); else by its words, as before (an older
 * plugin): a known line keeps its numbers and ignores the server's name (the app shows its own); a summarized one keeps the server's
 * words for Details. An unknown line is [fallback] with the server's words for Details, or, with no fallback, the server's words as
 * they are (an L1a check row the app doesn't know yet, say).
 */
internal fun agentPhoneLine(raw: String?, fallback: AgentPhoneLine? = null, code: String? = null, args: Map<String, String> = emptyMap()): AgentPhoneLine? {
    codedLine(code, raw, args, fallback)?.let { return it }
    val text = raw?.trim()?.takeIf(String::isNotBlank) ?: return fallback
    SUMMARIZED_LINES[text]?.let { return it.copy(raw = text, code = codeOf(text)) }
    for ((template, action, pattern) in KNOWN_PATTERNS) {
        val match = pattern.matchEntire(text) ?: continue
        SUMMARIZED_LINES[template]?.let { return it.copy(raw = text, code = codeOf(template)) }
        return AgentPhoneLine(template, match.groupValues.drop(1), action)
    }
    return fallback?.copy(raw = text) ?: AgentPhoneLine(text, raw = text)
}

/** The line for a refused answer: the body's own line, else FastAPI's `detail`, else what its status says. */
internal fun agentPhoneRefusal(answer: AgentPhoneAnswer, fallback: AgentPhoneLine): AgentPhoneLine {
    if (answer.status == 0) return AgentPhoneLine(AGENT_PHONE_OFFLINE, action = AgentPhoneAction.TryAgain)
    val said = (answer.body.opt("line") as? String) ?: (answer.body.opt("detail") as? String)
    // A plugin without these routes answers FastAPI's own 404.
    if (answer.status == 404 && (said == null || said == "Not Found")) return AgentPhoneLine(AGENT_PHONE_NEEDS_UPDATE, action = AgentPhoneAction.Update)
    if (answer.status == 503 && said == null) return AgentPhoneLine(AGENT_PHONE_BUSY, action = AgentPhoneAction.TryAgain)
    return agentPhoneLine(said, fallback, answer.body.code(), answer.body.lineArgs()) ?: fallback
}

// -- the API ----------------------------------------------------------------------------------------------------------------------

internal class AgentPhoneApi(private val endpoint: AgentPhoneEndpoint) {
    private suspend fun <T> ask(method: String, route: String, body: JSONObject?, timeoutMillis: Long, fallback: AgentPhoneLine, read: (AgentPhoneAnswer) -> T?): AgentPhoneResult<T> {
        val answer = endpoint.call(method, route, body, timeoutMillis)
        if (answer.ok) read(answer)?.let { return AgentPhoneResult.Done(it) }
        return AgentPhoneResult.Refused(agentPhoneRefusal(answer, fallback), answer.status)
    }
    private val wentWrong = AgentPhoneLine(AGENT_PHONE_WENT_WRONG, action = AgentPhoneAction.TryAgain)
    private val didntStart = AgentPhoneLine(AGENT_PHONE_DIDNT_START, action = AgentPhoneAction.TryAgain)

    /** GET /mobile/agent-phone, kept as its raw body too so the page can open from the cache next time. */
    suspend fun status(): AgentPhoneResult<Pair<AgentPhoneStatus, JSONObject>> =
        ask("GET", "", null, CALL_MS, wentWrong) { parseAgentPhoneStatus(it.body) to it.body }

    suspend fun start(): AgentPhoneResult<AgentPhonePower> = ask("POST", "/start", null, CALL_MS, didntStart) { answer ->
        if (answer.body.opt("phone") == "on") AgentPhonePower.On else AgentPhonePower.Starting
    }

    suspend fun stop(): AgentPhoneResult<AgentPhonePower> = ask("POST", "/stop", null, CALL_MS, wentWrong) { AgentPhonePower.Stopping }

    suspend fun giveTask(requestId: String, task: String): AgentPhoneResult<AgentPhoneDriverTask> {
        require(DRIVER_TASK_ID.matches(requestId) && validAgentPhoneTask(task))
        return ask("POST", "/task", json("request_id" to requestId, "task" to task), CALL_MS, wentWrong) {
            parseAgentPhoneDriverTask(it.body)
        }
    }

    suspend fun cancelTask(id: String): AgentPhoneResult<AgentPhoneDriverTask> {
        require(DRIVER_TASK_ID.matches(id))
        return ask("POST", "/task/$id/cancel", null, CALL_MS, wentWrong) {
            parseAgentPhoneDriverTask(it.body)?.takeIf { task -> task.id == id }
        }
    }

    /** The host checks take up to two minutes on the server (the virtualisation query and the network check are slow). */
    suspend fun checks(): AgentPhoneResult<AgentPhoneChecks> = ask("POST", "/checks", null, CHECKS_MS, wentWrong) { parseAgentPhoneChecks(it.body) }

    suspend fun terms(): AgentPhoneResult<AgentPhoneTerms> {
        val answer = endpoint.call("GET", "/terms", null, TERMS_MS)
        parseAgentPhoneTerms(answer.body).takeIf { answer.ok }?.let { return AgentPhoneResult.Done(it) }
        // A 502 carries L1a's own line (Google's index unreachable) and whether trying again can help.
        val line = agentPhoneRefusal(answer, AgentPhoneLine("Couldn't get Google's terms.", action = AgentPhoneAction.TryAgain))
        // Fetching the terms again is the way on: a download line's Resume means the same here.
        val retry = answer.body.opt("retry") == true && line.action in setOf(AgentPhoneAction.None, AgentPhoneAction.Resume)
        return AgentPhoneResult.Refused(if (retry) line.copy(action = AgentPhoneAction.TryAgain) else line, answer.status)
    }

    /** The owner's Accept of exactly the licences shown, and Continue on amber check rows; the server starts the download. */
    suspend fun install(accept: List<String>, continueWarnings: Boolean): AgentPhoneResult<AgentPhoneInstall> {
        val answer = endpoint.call("POST", "/install", json("accept" to JSONArray(accept), "continue" to continueWarnings), CALL_MS)
        // A 409 with the progress itself means an install already runs: show it.
        if (answer.ok || answer.status == 409 && answer.body.has("state")) return AgentPhoneResult.Done(parseAgentPhoneInstall(answer.body))
        return AgentPhoneResult.Refused(agentPhoneRefusal(answer, wentWrong), answer.status)
    }

    suspend fun installProgress(): AgentPhoneResult<AgentPhoneInstall> = ask("GET", "/install", null, CALL_MS, wentWrong) { parseAgentPhoneInstall(it.body) }

    suspend fun pause(): AgentPhoneResult<AgentPhoneInstall> = control("pause")
    suspend fun resume(): AgentPhoneResult<AgentPhoneInstall> = control("resume")
    suspend fun cancel(): AgentPhoneResult<AgentPhoneInstall> = control("cancel")
    private suspend fun control(action: String) = ask("POST", "/install/$action", null, CALL_MS, wentWrong) { parseAgentPhoneInstall(it.body) }

    /** Removes the phone and everything in its folder (§7.1); the server needs up to three minutes. The space freed, when it says. */
    suspend fun remove(deleteLogins: Boolean): AgentPhoneResult<Long?> {
        val answer = endpoint.call("POST", "/remove", json("delete_logins" to deleteLogins), REMOVE_MS)
        if (answer.ok && answer.body.opt("removed") == true) return AgentPhoneResult.Done(answer.body.whole("freed_bytes"))
        return AgentPhoneResult.Refused(agentPhoneRefusal(answer, wentWrong), answer.status)
    }

    suspend fun policy(): AgentPhoneResult<AgentPhonePolicy> = ask("GET", "/policy", null, CALL_MS, wentWrong) { parseAgentPhonePolicy(it.body) }

    suspend fun setPolicy(enabled: Boolean? = null, ask: AgentPhoneAsk? = null): AgentPhoneResult<AgentPhonePolicy> {
        val body = JSONObject().apply { enabled?.let { put("enabled", it) }; ask?.let { put("ask", it.wire) } }
        return ask("PUT", "/policy", body, CALL_MS, wentWrong) { parseAgentPhonePolicy(it.body) }
    }

    suspend fun activity(limit: Int = 50): AgentPhoneResult<List<AgentPhoneActivity>> =
        ask("GET", "/activity?limit=${limit.coerceIn(1, 200)}", null, CALL_MS, wentWrong) { parseAgentPhoneActivity(it.body) }

    suspend fun needsYou(): AgentPhoneResult<List<AgentPhoneNeedsYou>> = ask("GET", "/approvals", null, CALL_MS, wentWrong) { parseAgentPhoneNeedsYou(it.body) }

    /** The page's three reads with the answers they came from, so the page keeps them beside the status (cache review, fu2). */
    suspend fun policyRead(): AgentPhoneResult<Pair<AgentPhonePolicy, JSONObject>> =
        ask("GET", "/policy", null, CALL_MS, wentWrong) { parseAgentPhonePolicy(it.body) to it.body }

    suspend fun activityRead(limit: Int = 50): AgentPhoneResult<Pair<List<AgentPhoneActivity>, JSONObject>> =
        ask("GET", "/activity?limit=${limit.coerceIn(1, 200)}", null, CALL_MS, wentWrong) { parseAgentPhoneActivity(it.body) to it.body }

    suspend fun needsYouRead(): AgentPhoneResult<Pair<List<AgentPhoneNeedsYou>, JSONObject>> =
        ask("GET", "/approvals", null, CALL_MS, wentWrong) { parseAgentPhoneNeedsYou(it.body) to it.body }

    /** "Open the phone" answers a Needs-you notice yes; Dismiss answers it no (approvals v1 §2). */
    suspend fun answerNeedsYou(id: String, open: Boolean): AgentPhoneResult<Unit> {
        require(APPROVAL_ID.matches(id)) { "Invalid notice" }
        return ask("POST", "/approvals/$id/decision", json("approved" to open), CALL_MS, wentWrong) { }
    }

    /** Puts this Beacon's helper on the phone (Root, msg_ed1d395edfa5); the status says ready when it is done. */
    suspend fun updateHelper(): AgentPhoneResult<Unit> = ask("POST", "/helper/update", null, CALL_MS, wentWrong) { }

    companion object {
        const val CALL_MS = 20_000L
        const val CHECKS_MS = 135_000L
        const val TERMS_MS = 100_000L
        const val REMOVE_MS = 195_000L
    }
}

// -- the owner's own ticks ----------------------------------------------------------------------------------------------------------

/**
 * Checklist rows the owner ticked by hand, kept on this phone per agent phone (Root, msg_c579205379f9): Hermes account, signed-in
 * owner and the phone's owning profile. They count only where the agent phone can't read the row; Remove clears them.
 */
internal class AgentPhoneTicks(private val prefs: SharedPreferences, private val key: String) {
    fun read(): Set<String> = prefs.getStringSet(key, null)?.toSet().orEmpty()
    fun set(item: String, done: Boolean) {
        val next = if (done) read() + item else read() - item
        prefs.edit().putStringSet(key, next).apply()
    }
    fun clear() { prefs.edit().remove(key).apply() }

    companion object {
        const val PREFS = "agent-phone"
        fun key(scope: Scope, profile: String): String = "ticks:" + NativePairing.accountKey(scope) + ":" + profile
    }
}
