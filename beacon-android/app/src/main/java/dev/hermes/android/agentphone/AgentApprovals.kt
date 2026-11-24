package dev.hermes.android.agentphone

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BuildConfig
import dev.hermes.android.R
import dev.hermes.android.core.Scope
import dev.hermes.android.core.string
import dev.hermes.android.core.trustedOrigin
import dev.hermes.android.data.AgentPhoneEndpoint
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.ClientAgentPhoneEndpoint
import dev.hermes.android.data.parseAgentPhoneActivity
import dev.hermes.android.data.currentAssistantName
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.preferences
import dev.hermes.android.push.alertingChannel
import dev.hermes.android.push.boundedNotificationText
import dev.hermes.android.push.ensureActivityChannels
import dev.hermes.android.push.matchesNotificationRoute
import dev.hermes.android.push.promptNotificationChannel
import dev.hermes.android.push.serviceText
import dev.hermes.android.push.validOpaque
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** The account a record belongs to: the owner on one Hermes (records are the owner's, whatever profile is open). */
internal fun approvalAccount(scope: Scope): String = listOf(scope.origin, scope.nativeProvider, scope.owner).joinToString("\u0000")

/** Where the app reads and answers one account's records. */
/** [scope]: the account and profile the link reads for (null in tests): the activity list keeps its first page there. */
internal class ApprovalLink(val account: String, val endpoint: AgentPhoneEndpoint, val scope: Scope? = null)

/**
 * The agent phone's questions on this phone (approvals-v1 §6): pushes and an open Beacon read the owner's open records, each new one
 * is announced, and the secure dialog ([AgentApprovalActivity]) asks it. Records are never filtered by Phone access: that switch is
 * about this phone, and these questions are about the agent's own phone (EMU-DESIGN §5.4).
 */
object AgentApprovals {
    internal val board = AgentApprovalBoard(SystemClock::elapsedRealtime)
    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Ids announced on this phone, so a second read never rings again for the same question. */
    private val announced = HashSet<String>()

    /** Beacon is open and in front (its host reads the records): a new question then shows at once, so its notice arrives quietly. */
    @Volatile internal var inFront = false

    /** Device tests answer from a fake of the routes; never set in the app. */
    @Volatile internal var testLink: ApprovalLink? = null

    /** The signed-in account (the one Beacon shows), or the account a push came from ([source]). Null: no sign-in for it on this phone. */
    internal suspend fun link(context: Context, source: String? = null): ApprovalLink? = testLink ?: withContext(Dispatchers.IO) {
        val app = context.applicationContext as BeaconApplication
        if (!source.isNullOrBlank()) {
            if (!app.push.permitsIncoming(source)) return@withContext null
            val binding = app.push.binding(source) ?: return@withContext null
            val bound = Scope(trustedOrigin(binding.getString("origin")), binding.getString("owner"), binding.getString("profile"), nativeProvider = binding.string("native_provider"))
            val client = runCatching { app.push.clientFor(binding) }.getOrNull() ?: return@withContext null
            runCatching { client.bootstrap() }.getOrNull() ?: return@withContext null
            if (client.scope != bound || !app.push.permitsIncoming(source)) return@withContext null
            return@withContext ApprovalLink(approvalAccount(bound), ClientAgentPhoneEndpoint(client), bound)
        }
        signedIn(app)?.let { ApprovalLink(approvalAccount(it.scope), ClientAgentPhoneEndpoint(it), it.scope) }
    }

    /** Beacon's own sign-in on this phone (the account and profile the app is on), as the setup view reads it. */
    private suspend fun signedIn(app: BeaconApplication): BeaconClient? {
        val origin = runCatching { app.preferences.data.first()[stringPreferencesKey("origin")] }.getOrNull().orEmpty().ifBlank { BuildConfig.DEFAULT_ORIGIN }
        if (origin.isBlank()) return null
        val auth = runCatching { app.auth.read(origin) }.getOrNull() ?: return null
        if (auth.backend != "hermes-dashboard" || auth.owner.isBlank()) return null
        return BeaconClient(auth, app.auth, persistContext = false)
    }

    /** An open Beacon's client: the same account the app shows. */
    internal fun linkFor(client: BeaconClient): ApprovalLink = testLink ?: ApprovalLink(approvalAccount(client.scope), ClientAgentPhoneEndpoint(client), client.scope)

    /**
     * Reads [link]'s open records; a record not seen before is announced (a notification that opens the secure dialog; the record's
     * words stay in the app). Records that ended lose their notification.
     */
    internal suspend fun refresh(context: Context, link: ApprovalLink, source: String? = null): ApprovalsRead {
        val read = board.refresh(link.account, link.endpoint)
        read.gone.forEach { cancel(context, it) }
        val fresh = synchronized(announced) { read.fresh.filter { announced.add(it.id) } }
        fresh.forEach { notifyRecord(context, it, source) }
        return read
    }

    /** The owner's answer from the dialog; the record's notification goes once the plugin has it, or once it ended. */
    internal suspend fun decide(context: Context, link: ApprovalLink, record: AgentApproval, approved: Boolean, code: String? = null): DecisionResult? {
        val result = board.decide(link.account, record, approved, link.endpoint, code) ?: return null
        if (result in setOf(DecisionResult.Sent, DecisionResult.Ended, DecisionResult.Elsewhere)) cancel(context, record.id)
        return result
    }

    /** A record whose time ran out on this phone. */
    internal suspend fun ended(context: Context, link: ApprovalLink, record: AgentApproval) {
        board.remove(link.account, record.id)
        cancel(context, record.id)
    }

    /**
     * A push (FCM or Keep connected) for one of the two agent-phone notices. `agent_phone_approval` reads the open records of the
     * account the push came from; `agent_phone_done` posts the line for the activity row it names (approvals-v1 §6b). The push itself
     * carries no content: everything shown is read here, over Hermes.
     */
    fun onPush(context: Context, source: String, event: String, conversation: String, kind: String) {
        val app = context.applicationContext as BeaconApplication
        work.launch {
            try {
                withTimeout(20_000) {
                    when (kind) {
                        AGENT_APPROVAL_KIND -> link(app, source)?.let { refresh(app, it, source) }
                        AGENT_DONE_KIND -> if (app.push.firstArrival(source, event)) postDone(app, source, event, conversation)
                    }
                }
            } catch (_: TimeoutCancellationException) { /* Offline: an open Beacon reads the records again. */ }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* An older plugin, a signed-out account or no network: nothing to show. */ }
        }
    }

    private fun tag(id: String) = "agent-phone:$id"

    private fun cancel(context: Context, id: String) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(tag(id), NOTIFICATION_ID) }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The question's title only ("Maya wants to pay"), and on the lock screen not even that: amounts, shops and codes stay in the app. */
    private fun notifyRecord(context: Context, record: AgentApproval, source: String?) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureActivityChannels(manager)
        val channel = promptNotificationChannel(manager)
        val open = PendingIntent.getActivity(context, record.id.hashCode(), AgentApprovalActivity.intent(context, source, record.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val title = serviceText(context, approvalFace(record).title).replace("{name}", currentAssistantName)
        val public = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(serviceText(context, "Approval needed")).setContentText(serviceText(context, "Open Beacon to review the request.")).build()
        val left = record.deadline - SystemClock.elapsedRealtime()
        if (left <= 0) return
        val notice = NotificationCompat.Builder(context, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(boundedNotificationText(title, 120)).setContentText(serviceText(context, "Tap to answer."))
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
            // It stays until the question is answered or ends: it is the way back after a trip to Messages for a code.
            .setContentIntent(open).setAutoCancel(false).setOnlyAlertOnce(true).setTimeoutAfter(left)
            .setSilent(inFront || AgentApprovalActivity.visible).build()
        try { manager.notify(tag(record.id), NOTIFICATION_ID, notice) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
    }

    /**
     * "Payment done" / "Sent" with the row's own words ("Kanto Foods: Place order"), read over Hermes: the notice names the row by
     * `activity_ref`, and its route says the title. Tapping it opens the activity list at that row.
     */
    private suspend fun postDone(app: BeaconApplication, source: String, event: String, conversation: String) {
        if (!canPost(app) || !validOpaque(event)) return
        val binding = app.push.binding(source) ?: return
        val scope = Scope(trustedOrigin(binding.getString("origin")), binding.getString("owner"), binding.getString("profile"), nativeProvider = binding.string("native_provider"))
        val client = app.push.clientFor(binding)
        client.bootstrap()
        if (client.scope != scope || !app.push.permitsIncoming(source)) return
        val route = client.nativeRequest("/api/plugins/beacon/mobile/notifications/events/$event")
        if (!matchesNotificationRoute(route, source, event, scope, conversation, AGENT_DONE_KIND)) return
        val seq = activitySeq(route.string("activity_ref"))
        val row = seq?.let { wanted ->
            ClientAgentPhoneEndpoint(client).call("GET", "$ACTIVITY_ROUTE?limit=50", null, APPROVAL_CALL_MS).takeIf { it.ok }
                ?.let { answer -> parseAgentPhoneActivity(answer.body).firstOrNull { it.seq == wanted } }
        }
        val preview = route.optJSONObject("preview")
        val title = serviceText(app, doneTitle((preview?.opt("title") as? String) ?: route.string("title"))).replace("{name}", currentAssistantName)
        val body = row?.let { doneBody(app, it) } ?: boundedNotificationText((preview?.opt("body") as? String).orEmpty(), 240).ifBlank { serviceText(app, "Open Beacon to see it.") }
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        ensureActivityChannels(manager)
        val channel = alertingChannel(manager, "hermes-tasks")
        val open = PendingIntent.getActivity(app, "$source:$event".hashCode(), AgentPhoneLogActivity.intent(app, seq),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(app, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(title).setContentText(serviceText(app, "Open Beacon to see it.")).build()
        val notice = NotificationCompat.Builder(app, channel).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(boundedNotificationText(title, 120)).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open).setAutoCancel(true).setTimeoutAfter(DONE_NOTICE_MS).build()
        try { manager.notify("agent-phone-done:$event", NOTIFICATION_ID, notice) } catch (_: SecurityException) { }
    }

    /** "<app>: <label>" from the row, the owner's words isolated, in the phone's language where the row is the plugin's own sentence. */
    private fun doneBody(context: Context, row: AgentPhoneLogRow): String {
        val line = logLine(row)
        val title = PhoneLine(serviceText(context, line.title.template), line.title.args).shown()
        val app = appWords(row.app) { serviceText(context, it) }
        return boundedNotificationText(if (app.isNotBlank() && row.action != "save") "${PhoneLine("{0}", listOf(app)).shown()}: $title" else title, 240)
    }

    private const val NOTIFICATION_ID = 7
    private const val DONE_NOTICE_MS = 3_600_000L
}

/** The notice kinds the plugin raises for the agent phone (approvals-v1 §6, §6b). */
internal const val AGENT_APPROVAL_KIND = "agent_phone_approval"
internal const val AGENT_DONE_KIND = "agent_phone_done"

/** A push this file answers: receivePush hands both kinds here before its own kind list, for FCM and Keep connected alike. */
internal fun isAgentPhoneNotice(kind: String): Boolean = kind == AGENT_APPROVAL_KIND || kind == AGENT_DONE_KIND

/** The done line's title, in English for the catalog: the plugin's own words when they are one of its two, else the app's. */
internal fun doneTitle(said: String): String = if (said == "Payment done" || said == "Sent") said else "Done on {name}'s phone"

/** The notification and done words the app shows outside Compose (serviceText); the words test checks each one. */
internal val AgentApprovalNoticeWords = listOf("Approval needed", "Open Beacon to review the request.", "Tap to answer.", "Payment done", "Sent", "Open Beacon to see it.", "Done on {name}'s phone")
