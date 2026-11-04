package dev.hermes.android.push

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.app.job.JobWorkItem
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.R
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

/** Reply from the notification: a chat's reply notice takes an answer typed in the shade and sends it to that same chat, like typing it in the app. */
internal const val REPLY_ACTION = "dev.hermes.android.push.REPLY"
internal const val REPLY_AGAIN_ACTION = "dev.hermes.android.push.REPLY_AGAIN"
internal const val REPLY_KEY = "beacon_reply"
/** Marks a notice that now shows a sent reply, and which chat it belongs to, so the answer can take its place. */
internal const val REPLY_SENT_EXTRA = "beacon_reply_sent"
internal const val REPLY_CONVERSATION_EXTRA = "beacon_reply_conversation"
/** The app's own draft limit: what fits the composer fits the shade. */
internal const val REPLY_TEXT_LIMIT = 50_000
/** Long enough to resume the chat and hand it the message; the app waits as long for the same start. */
internal const val REPLY_TIMEOUT_MILLIS = 30_000L
/** A "Sent" notice that no answer replaced goes away on its own. */
internal const val SENT_NOTICE_MILLIS = 30 * 60_000L

/** The notification lines, in English; each goes through [serviceText] so the phone's language is used. */
internal object ReplyStrings {
    const val REPLY = "Reply"
    const val SENDING = "Sending…"
    const val SENT = "Sent"
    const val NOT_SENT = "Couldn't send. Open Beacon to try again."
    const val UNSURE = "Couldn't confirm it was sent. Open Beacon to check."
    const val SEND_AGAIN = "Send again"
    val all = listOf(REPLY, SENDING, SENT, NOT_SENT, UNSURE, SEND_AGAIN)
}

internal enum class ReplyState { Sending, Sent, NotSent, Unsure }

/** Notices that answer in a chat: a finished reply, and a scheduled post or alert that landed in one. */
internal fun replyKind(kind: String): Boolean = kind == "completion" || kind == "task_result"

/** A notice offers Reply once Hermes names the chat it belongs to; the fetch that fills in its text is where this is known. */
internal fun noticeAcceptsReply(route: JSONObject): Boolean =
    replyKind(route.string("kind")) && validOpaque(route.string("session_id")) && validOpaque(route.string("conversation_ref"))

/** What is typed in the shade, ready to send: trimmed, never empty, never longer than the app's own draft limit. */
internal fun cleanReply(typed: CharSequence?): String? =
    typed?.toString()?.filter { !it.isISOControl() || it == '\n' || it == '\t' }?.trim()?.takeIf { it.isNotEmpty() && it.length <= REPLY_TEXT_LIMIT }

/** The chat a reply goes to: only the one Hermes names for this very notice, of this owner and profile; null when anything differs. */
internal fun replySession(route: JSONObject, source: String, event: String, scope: Scope): String? {
    val kind = route.string("kind")
    val conversation = route.string("conversation_ref")
    if (!replyKind(kind) || !validOpaque(conversation)) return null
    if (!matchesNotificationRoute(route, source, event, scope, conversation, kind)) return null
    return route.string("session_id").takeIf(::validOpaque)
}

/** The same start the composer sends (`/api/chat/start`), without a model pick: the chat keeps its own. */
internal fun replyStartBody(session: String, text: String, profile: String, requestId: String): JSONObject =
    json("session_id" to session, "message" to text, "attachments" to JSONArray(), "profile" to profile, "client_request_id" to requestId)

/** Hermes took the message (started, queued or already answered); anything else is not a claim of success. */
internal fun replyOutcome(result: JSONObject): ReplyState = if (result.optBoolean("accepted")) ReplyState.Sent else ReplyState.Unsure

/**
 * A failure before the message was handed over, or one that cannot have left the phone, is "not sent" and can be retried safely.
 * Anything after the hand-over is uncertain: a retry could send it twice, so the notice asks the owner to check the chat.
 */
internal fun replyOutcomeFor(error: Throwable, attempted: Boolean): ReplyState =
    if (!attempted || error is NativeTurnNotSent || requestNeverSent(error)) ReplyState.NotSent else ReplyState.Unsure

/** True when a notification already carries the inline Reply action (a second preview refresh must not add another). */
internal fun carriesReply(actionInputKeys: List<String?>): Boolean = REPLY_KEY in actionInputKeys

/** A notice currently shown, reduced to what superseding needs. */
internal data class ShownNotice(val tag: String?, val id: Int, val conversation: String, val replySent: Boolean)

/** The "Sent" notices of [conversation] that the arriving [newTag] answers; they are replaced rather than left beside it. */
internal fun supersededByAnswer(shown: List<ShownNotice>, conversation: String, newTag: String): List<ShownNotice> =
    if (!validOpaque(conversation)) emptyList() else shown.filter { it.id == 1 && it.replySent && it.conversation == conversation && it.tag != newTag }

private fun noticeTag(source: String, event: String) = "$source\u0000$event".digest()

private fun replyText(context: Context, line: String) = serviceText(context, line)

/** The inline reply: Android's own field in the shade. Mutable because the system fills the typed text into it; it names the receiver, which is not exported. */
internal fun replyAction(context: Context, source: String, event: String): NotificationCompat.Action {
    val label = replyText(context, ReplyStrings.REPLY)
    val intent = Intent(context, NotificationReplyReceiver::class.java).setAction(REPLY_ACTION)
        .setData(android.net.Uri.parse("hermes://reply/${noticeTag(source, event)}")).putExtra("source", source).putExtra("event", event)
    val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
    return NotificationCompat.Action.Builder(R.drawable.ic_beacon_logo, label, pending)
        .addRemoteInput(RemoteInput.Builder(REPLY_KEY).setLabel(label).build())
        .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY).setShowsUserInterface(false).setAllowGeneratedReplies(false)
        // The reply is private text sent in the owner's name: on a locked phone Android asks for the unlock first.
        .setAuthenticationRequired(true).build()
}

/** After a failure that cannot have sent: one tap sends the same text again; the text stays in the notice, nowhere else. */
private fun sendAgainAction(context: Context, source: String, event: String, text: String): NotificationCompat.Action {
    val intent = Intent(context, NotificationReplyReceiver::class.java).setAction(REPLY_AGAIN_ACTION)
        .setData(android.net.Uri.parse("hermes://reply-again/${noticeTag(source, event)}")).putExtra("source", source).putExtra("event", event).putExtra("text", text)
    val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    return NotificationCompat.Action.Builder(R.drawable.ic_beacon_logo, replyText(context, ReplyStrings.SEND_AGAIN), pending)
        .setShowsUserInterface(false).setAuthenticationRequired(true).build()
}

private fun shownNotices(manager: NotificationManager): List<ShownNotice> = manager.activeNotifications.map {
    ShownNotice(it.tag, it.id, it.notification.extras.getString(REPLY_CONVERSATION_EXTRA).orEmpty(), it.notification.extras.getBoolean(REPLY_SENT_EXTRA))
}

/** The answer arrived: the "Sent" notice of its chat gives way to it. */
internal fun retireSentReplies(manager: NotificationManager, conversation: String, newTag: String) {
    supersededByAnswer(shownNotices(manager), conversation, newTag).forEach { manager.cancel(it.tag, it.id) }
}

/**
 * Shows [state] on the notice, in place: Android keeps a spinner on a replied-to notification until it is updated.
 * A notice the owner already swept away is posted again for a failure, so the unsent text is not silently lost.
 */
internal fun showReplyState(context: Context, source: String, event: String, state: ReplyState, keptText: String? = null) {
    val manager = context.getSystemService(NotificationManager::class.java)
    val tag = noticeTag(source, event)
    val body = replyText(context, when (state) {
        ReplyState.Sending -> ReplyStrings.SENDING
        ReplyState.Sent -> ReplyStrings.SENT
        ReplyState.NotSent -> ReplyStrings.NOT_SENT
        ReplyState.Unsure -> ReplyStrings.UNSURE
    })
    val posted = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == 1 }
    val builder = if (posted != null) NotificationCompat.Builder(context, posted.notification).clearActions().also { builder ->
        // The compat copy does not carry the lock-screen version over; the shade text may be private, the lock screen's never is.
        posted.notification.publicVersion?.let(builder::setPublicVersion)
    } else {
        if (state != ReplyState.NotSent && state != ReplyState.Unsure) return
        ensureActivityChannels(manager)
        val open = PendingIntent.getActivity(context, 0, Intent(context, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        NotificationCompat.Builder(context, alertingChannel(manager, "hermes-messages")).setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle(currentAssistantName).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setAutoCancel(true).setContentIntent(open)
            .setPublicVersion(NotificationCompat.Builder(context, alertingChannel(manager, "hermes-messages")).setSmallIcon(R.drawable.ic_beacon_notification)
                .setContentTitle(currentAssistantName).setContentText("Open Beacon to view private activity.").build())
    }
    builder.setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setOnlyAlertOnce(true).setSilent(true)
        .setProgress(0, 0, state == ReplyState.Sending)
    if (state == ReplyState.Sent) builder.setTimeoutAfter(SENT_NOTICE_MILLIS)
    if (state == ReplyState.NotSent && keptText != null) builder.addAction(sendAgainAction(context, source, event, keptText))
    builder.addExtras(Bundle().apply { putBoolean(REPLY_SENT_EXTRA, state == ReplyState.Sent || state == ReplyState.Sending) })
    try { manager.notify(tag, 1, builder.build()) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
}

/**
 * Sends [text] to the chat this notice belongs to. The route is fetched again with the notice's own bound account and profile, so the
 * chat comes from Hermes and not from the notification; the outcome says what the notice may claim.
 */
internal suspend fun sendNotificationReply(context: Context, source: String, event: String, text: String, locked: () -> Boolean): ReplyState {
    val app = context.applicationContext as BeaconApplication
    val message = cleanReply(text)
    if (!validOpaque(source) || !validOpaque(event) || message == null || !app.push.permitsIncoming(source)) return ReplyState.NotSent
    val binding = app.push.binding(source) ?: return ReplyState.NotSent
    val scope = Scope(trustedOrigin(binding.getString("origin")), binding.getString("owner"), binding.getString("profile"), nativeProvider = binding.string("native_provider"))
    fun authorized() = app.push.binding(source)?.toString() == binding.toString() && app.auth.read(scope.origin).authorizesPushSource(scope)
    if (!authorized()) return ReplyState.NotSent
    val client = try { app.push.clientFor(binding) } catch (_: Exception) { return ReplyState.NotSent }
    var attempted = false
    try {
        return withTimeout(REPLY_TIMEOUT_MILLIS) {
            client.bootstrap()
            if (client.scope != scope || !authorized()) return@withTimeout ReplyState.NotSent
            val session = replySession(client.nativeRequest("/api/plugins/beacon/mobile/notifications/events/$event"), source, event, scope) ?: return@withTimeout ReplyState.NotSent
            if (!authorized() || locked()) return@withTimeout ReplyState.NotSent
            attempted = true
            replyOutcome(client.post("/api/chat/start", replyStartBody(session, message, scope.profile, java.util.UUID.randomUUID().toString())))
        }
    } catch (e: TimeoutCancellationException) {
        return replyOutcomeFor(e, attempted)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        return replyOutcomeFor(e, attempted)
    } finally { client.closeNative() }
}

class NotificationReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != REPLY_ACTION && intent.action != REPLY_AGAIN_ACTION) return
        val source = intent.getStringExtra("source").orEmpty()
        val event = intent.getStringExtra("event").orEmpty()
        if (!validOpaque(source) || !validOpaque(event)) return
        val typed = if (intent.action == REPLY_ACTION) RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY) else intent.getStringExtra("text")
        val text = cleanReply(typed)
        // Nothing typed: the spinner Android put on the notice must still go.
        if (text == null) { showReplyState(context, source, event, ReplyState.NotSent); return }
        // Android asks to unlock first; this is the second lock, so a reply can never go out from a locked phone.
        val sent = context.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != true && NotificationReplyJob.enqueue(context, source, event, text)
        showReplyState(context, source, event, if (sent) ReplyState.Sending else ReplyState.NotSent, text)
    }
}

class NotificationReplyJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            try {
                while (isActive) {
                    val work = params.dequeueWork() ?: break
                    val source = work.intent.getStringExtra("source").orEmpty()
                    val event = work.intent.getStringExtra("event").orEmpty()
                    val text = work.intent.getStringExtra("text").orEmpty()
                    var state = ReplyState.Unsure
                    try {
                        state = sendNotificationReply(this@NotificationReplyJob, source, event, text) { getSystemService(KeyguardManager::class.java)?.isDeviceLocked == true }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { state = ReplyState.Unsure }
                    finally {
                        // Whatever happened, the notice says so (also when Android stopped the job mid-send, which stays "not confirmed").
                        val shown = state
                        withContext(NonCancellable) { showReplyState(this@NotificationReplyJob, source, event, shown, text.takeIf { shown == ReplyState.NotSent }) }
                    }
                    params.completeWork(work)
                }
            } finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        internal const val JOB_ID = 7246
        fun enqueue(context: Context, source: String, event: String, text: String): Boolean {
            if (!validOpaque(source) || !validOpaque(event) || cleanReply(text) == null || !(context.applicationContext as BeaconApplication).push.permitsIncoming(source)) return false
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            val work = Intent().putExtra("source", source).putExtra("event", event).putExtra("text", text)
            fun queue(expedited: Boolean): Boolean = runCatching {
                val builder = JobInfo.Builder(JOB_ID, ComponentName(context, NotificationReplyJob::class.java))
                val job = if (expedited && android.os.Build.VERSION.SDK_INT >= 31) builder.setExpedited(true).build() else builder.setOverrideDeadline(0).build()
                scheduler.enqueue(job, JobWorkItem(work)) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
            return queue(true) || queue(false)
        }
    }
}
