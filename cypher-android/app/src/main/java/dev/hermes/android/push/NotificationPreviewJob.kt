package dev.hermes.android.push
import android.app.NotificationManager
import android.app.job.*
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.hermes.android.CypherApplication
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
fun notificationFallback(kind: String): Pair<String, String> = when (kind) {
    "working" -> "Cypher · Working" to "Hermes is working on your chat."
    "failure" -> "Cypher · Work failed" to "Open Cypher to see what went wrong."
    "completion" -> "Cypher · Reply ready" to "A chat reply is ready. Open Cypher to read it."
    "approval" -> "Cypher · Approval needed" to "A chat needs your approval before continuing."
    "clarify" -> "Cypher · Question for you" to "A chat needs your answer before continuing."
    "task_result" -> "Cypher · Task update" to "A task has an update. Open Cypher to review it."
    "test" -> "Cypher · Notification test" to "Your notification test reached this device."
    "incoming_call" -> "Cypher · Callback" to "A Cypher callback is available. Open Cypher to view its history."
    "release" -> "Cypher · App update" to "An app update is available."
    else -> "Cypher · Activity" to "Activity is available. Open Cypher to view it."
}
fun boundedNotificationText(value: String, limit: Int, preserveWhitespace:Boolean=false): String {
    require(limit >= 2)
    val filtered = value.filter { !it.isISOControl() || it == '\n' || it == '\t' }
    val cleaned=if(preserveWhitespace) filtered else filtered.replace(Regex("\\s+"), " ").trim()
    if (cleaned.length <= limit) return cleaned
    val end = if (Character.isHighSurrogate(cleaned[limit - 2])) limit - 2 else limit - 1
    return cleaned.take(end) + "…"
}
fun matchesNotificationRoute(route: JSONObject, source: String, event: String, scope: Scope, conversation: String, kind: String): Boolean =
    route.string("source_ref") == source && route.string("event_id") == event && route.string("owner_id") == scope.owner &&
        route.string("profile") == scope.profile && route.string("conversation_ref") == conversation && route.string("kind") == kind
/** Private text is fetched only from the encrypted registered owner binding. */
suspend fun refreshNotificationPreview(context: Context, source: String, event: String, conversation: String, kind: String) {
    if (kind == "incoming_call") return // The incoming/missed card owns its actions and fixed deadline.
    if (!validOpaque(source) || !validOpaque(event) || !validOpaque(conversation)) return
    if (kind !in setOf("failure", "completion", "approval", "clarify", "task_result", "test", "incoming_call", "working")) return
    val app = context.applicationContext as CypherApplication
    val manager = context.getSystemService(NotificationManager::class.java)
    val tag = "$source\u0000$event".digest()
    fun active(id: Int) = manager.activeNotifications.firstOrNull { it.tag == tag && it.id == id }
    if (!ongoingNativeKind(kind) && active(1) == null || !app.push.permitsIncoming(source)) return
    val binding = app.push.binding(source) ?: return
    val scope = Scope(trustedOrigin(binding.getString("origin")), binding.getString("owner"), binding.getString("profile"))
    fun authorized() = app.push.binding(source)?.toString() == binding.toString() && app.auth.read(scope.origin).authorizesPushSource(scope)
    if (!authorized()) return
    val client = app.push.clientFor(binding)
    withTimeout(20000) {
        client.bootstrap()
        if (client.scope != scope || !authorized()) return@withTimeout
        val route = client.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event")
        if (!matchesNotificationRoute(route, source, event, scope, conversation, kind)) return@withTimeout
        if (validOpaque(route.string("activity_ref")) && ongoingNativeKind(kind)) {
            if (route.string("state") == "pending" && authorized()) presentNativeActivity(context, source, event, route.string("activity_ref"), kind, route.optLong("expires_at"), route)
            else if(route.string("state") in setOf("settled","expired","answered","declined","ended")&&authorized())
                presentNativeActivity(context,source,event,route.string("activity_ref"),"activity_end",route.optLong("expires_at"))
            return@withTimeout
        }
        val preview = route.optJSONObject("preview") ?: return@withTimeout
        val title = boundedNotificationText(preview.opt("title") as? String ?: "", 120)
        val body = boundedNotificationText(preview.opt("body") as? String ?: "", 640)
        if (title.isBlank() || body.isBlank() || !authorized()) return@withTimeout
        // Recover the posted notification to retain tap/call actions and never re-ring a preview.
        val posted = active(1) ?: return@withTimeout
        val builder = NotificationCompat.Builder(context, posted.notification)
            .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setOnlyAlertOnce(true)
        if (!authorized() || active(1)?.postTime != posted.postTime) return@withTimeout
        try { manager.notify(tag, 1, builder.build()) } catch (_: SecurityException) { return@withTimeout }
        // Updating the looping call with ONLY_ALERT_ONCE stops its ringtone; hydrate only its context notification.
    }
}
class NotificationPreviewJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try {
                while (isActive) {
                    val work = params.dequeueWork() ?: break
                    try {
                        val i = work.intent
                        refreshNotificationPreview(this@NotificationPreviewJob, i.getStringExtra("source").orEmpty(), i.getStringExtra("event").orEmpty(), i.getStringExtra("conversation").orEmpty(), i.getStringExtra("kind").orEmpty())
                    } catch (_: TimeoutCancellationException) {
                        currentCoroutineContext().ensureActive() // Only the per-fetch timeout may leave a fallback and continue.
                    }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* Offline/auth/deleted events retain their useful typed fallback. */ }
                    params.completeWork(work)
                }
            } finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        private const val JOB_ID = 7241
        fun enqueue(context: Context, source: String, event: String, conversation: String, kind: String) {
            if (!validOpaque(source) || !validOpaque(event) || !validOpaque(conversation)) return
            if (!(context.applicationContext as CypherApplication).push.permitsIncoming(source)) return
            val work = Intent().putExtra("source", source).putExtra("event", event).putExtra("conversation", conversation).putExtra("kind", kind)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            fun builder() = JobInfo.Builder(JOB_ID, ComponentName(context, NotificationPreviewJob::class.java))
            // Tailnet reachability does not imply Android's validated public-internet constraint.
            val accepted = runCatching {
                val existing = scheduler.getPendingJob(JOB_ID)?.takeIf { it.requiredNetwork == null }
                val info = existing ?: if (android.os.Build.VERSION.SDK_INT >= 31) builder().setExpedited(true).build() else builder().setOverrideDeadline(0).build()
                scheduler.enqueue(info, JobWorkItem(work)) == JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
            if (!accepted) runCatching { scheduler.enqueue(builder().setOverrideDeadline(0).build(), JobWorkItem(work)) }
            // If both requests exceed scheduler quota, the immediate typed fallback remains.
        }
    }
}
