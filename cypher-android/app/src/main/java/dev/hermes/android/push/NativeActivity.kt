package dev.hermes.android.push

import android.app.*
import android.app.job.*
import android.content.*
import android.os.Build
import dev.hermes.android.CypherActivity
import dev.hermes.android.CypherApplication
import dev.hermes.android.R
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import org.json.JSONObject

internal fun ongoingNativeKind(kind: String) = kind in setOf("working", "approval", "clarify")
internal fun completeNativePreview(route:JSONObject)=route.optJSONObject("preview")?.optBoolean("complete")==true
internal fun validNativeAnswer(route: JSONObject, answer: String): Boolean = answer.length in 1..1024 &&
    ((completeNativePreview(route)&&route.optBoolean("text_reply")) || nativeVisibleActions(route,route.string("kind")).any { it.string("value") == answer })
internal fun nativeActivityTimeout(kind:String,expires:Long,now:Long):Long {
    if(expires<=now/1000) return 0
    return ((expires-now/1000).coerceAtMost(if(kind=="working") 14400L else 300L)*1000-now%1000).coerceAtLeast(1)
}
internal fun nativeVisibleActions(route: JSONObject, kind: String): List<JSONObject> {
    val actions = route.rows("actions")
    if(!completeNativePreview(route)) return if(kind=="approval") actions.filter { it.string("value")=="deny" }.take(1) else emptyList()
    return if (kind == "approval") actions.filter { it.string("value") != "always" }.take(3) else actions.take(if(route.optBoolean("text_reply")) 2 else 3)
}

private fun activityTag(source: String, activity: String) = "native:${"$source\u0000$activity".digest()}"
fun cancelNativeActivities(context: Context, source: String) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.activeNotifications.filter { it.id == 3 && it.notification.extras.getString("native_source") == source }.forEach { manager.cancel(it.tag, it.id) }
}
private fun dismissed(context: Context, tag: String) = context.getSharedPreferences("activity-dismissals", Context.MODE_PRIVATE).contains(tag)
private fun finishNativeActivity(context:Context,tag:String) {
    val prefs=context.getSharedPreferences("activity-dismissals",Context.MODE_PRIVATE)
    val edit=prefs.edit().putLong(tag,System.currentTimeMillis())
    prefs.all.filterValues { value -> (value as? Long ?: 0)<System.currentTimeMillis()-86400000 }.keys.forEach { edit.remove(it) }
    edit.apply()
    context.getSystemService(NotificationManager::class.java).cancel(tag,3)
}

/** FCM carries no command/question content. Buttons appear only after an authenticated fetch. */
fun presentNativeActivity(context: Context, source: String, event: String, activity: String, kind: String, expires: Long, route: JSONObject? = null) {
    if (!validOpaque(source) || !validOpaque(event) || !validOpaque(activity)) return
    val app = context.applicationContext as CypherApplication
    if (!app.push.permitsIncoming(source)) return
    val manager = context.getSystemService(NotificationManager::class.java)
    val tag = activityTag(source, activity)
    if (kind == "activity_end") { finishNativeActivity(context,tag); return }
    if (!ongoingNativeKind(kind) || expires <= System.currentTimeMillis() / 1000 || dismissed(context, tag)) return
    ensureActivityChannels(manager)
    val channel = if(kind in setOf("approval","clarify")) promptNotificationChannel(manager) else notificationChannel(kind)
    val tap = Intent(context, CypherActivity::class.java).setData(android.net.Uri.parse("hermes://activity/${"$source\u0000$event".digest()}"))
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
    val fallback = notificationFallback(kind)
    val preview = route?.optJSONObject("preview")
    val content = (preview?.string("title")?.let { boundedNotificationText(it, 120) }?.takeIf { it.isNotBlank() } ?: fallback.first) to
        (preview?.string("body")?.let { boundedNotificationText(it, 640,preserveWhitespace=true) }?.takeIf { it.isNotBlank() } ?: fallback.second)
    val public = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("Cypher activity").setContentText("Open Cypher to review.").build()
    val delete = Intent(context, NativeActivityReceiver::class.java).setAction("dismiss").setData(android.net.Uri.parse("hermes://dismiss/$tag"))
        .putExtra("tag", tag)
    val builder = Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle(content.first).setContentText(content.second)
        .setStyle(Notification.BigTextStyle().bigText(content.second)).setOngoing(true).setOnlyAlertOnce(true)
        .setCategory(if (kind == "working") Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
        .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(public).setTimeoutAfter(nativeActivityTimeout(kind,expires,System.currentTimeMillis()).coerceAtLeast(1))
        .setContentIntent(PendingIntent.getActivity(context, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setExtras(android.os.Bundle().apply { putString("native_source", source) })
        .setDeleteIntent(PendingIntent.getBroadcast(context, 0, delete, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    if (Build.VERSION.SDK_INT >= 36) {
        builder.addExtras(android.os.Bundle().apply { putBoolean("android.requestPromotedOngoing", true) }).setShortCriticalText(if (kind == "working") "Working" else "Your turn")
    }
    fun action(value: String?, label: String, text: Boolean = false) {
        val intent = Intent(context, NativeActivityReceiver::class.java).setAction("answer")
            .setData(android.net.Uri.parse("hermes://answer/${"$source\u0000$event\u0000$value".digest()}"))
            .putExtra("source", source).putExtra("event", event).putExtra("activity", activity).putExtra("answer", value)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or if (text) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        val button = Notification.Action.Builder(null, label, pending)
        if (text) button.addRemoteInput(RemoteInput.Builder("answer").setLabel("Your answer").build())
        if (Build.VERSION.SDK_INT >= 31) button.setAuthenticationRequired(true)
        builder.addAction(button.build())
    }
    route?.let { nativeVisibleActions(it, kind) }?.forEach { action(it.string("value"), boundedNotificationText(it.string("label"), 80)) }
    if (route!=null&&!completeNativePreview(route)&&kind in setOf("approval","clarify")) {
        val review=Notification.Action.Builder(null,"Review",PendingIntent.getActivity(context,0,tap,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        if(Build.VERSION.SDK_INT>=31) review.setAuthenticationRequired(true)
        builder.addAction(review.build())
    }
    if (route?.optBoolean("text_reply") == true&&completeNativePreview(route)) action(null, "Answer", true)
    try { manager.notify(tag, 3, builder.build()) } catch (_: SecurityException) { /* Notification permission may be revoked between fetch and post. */ }
}

class NativeActivityReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_EMAIL_FEEDBACK) {
            if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
            NotificationPreviewJob.enqueueFeedback(
                context,
                intent.getStringExtra("source").orEmpty(),
                intent.getStringExtra("event").orEmpty(),
                intent.getStringExtra("verdict").orEmpty(),
            )
            return
        }
        if (intent.action == "dismiss") {
            intent.getStringExtra("tag")?.takeIf { it.startsWith("native:") }?.let {
                finishNativeActivity(context,it)
            }
            return
        }
        if (intent.action != "answer" || context.getSystemService(KeyguardManager::class.java).isDeviceLocked) return
        val answer = intent.getStringExtra("answer") ?: RemoteInput.getResultsFromIntent(intent)?.getCharSequence("answer")?.toString() ?: return
        val source=intent.getStringExtra("source").orEmpty();val event=intent.getStringExtra("event").orEmpty();val activity=intent.getStringExtra("activity").orEmpty()
        if(!NativeActivityActionJob.enqueue(context,source,event,answer,activity)) {
            val manager=context.getSystemService(NotificationManager::class.java)
            val tag=activityTag(source,activity)
            val posted=manager.activeNotifications.firstOrNull { it.tag==tag&&it.id==3 }?.notification ?: return
            val open=posted.contentIntent ?: return
            val review=Notification.Action.Builder(null,"Open Cypher",open)
            if(android.os.Build.VERSION.SDK_INT>=31) review.setAuthenticationRequired(true)
            val message="Android couldn’t queue your response. Open Cypher to review and respond."
            try { manager.notify(tag,3,Notification.Builder.recoverBuilder(context,posted).setContentTitle("Response not sent").setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message)).setActions(review.build()).setOnlyAlertOnce(true).build()) } catch(_:SecurityException) { }
        }
    }

    companion object {
        const val ACTION_EMAIL_FEEDBACK = "dev.hermes.android.push.EMAIL_FEEDBACK"
    }
}

class NativeActivityActionJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running?.cancel()
        running = scope.launch {
            try {
                while (isActive) {
                    val work = params.dequeueWork() ?: break
                    try {
                        withTimeout(20000) {
                            val app = application as CypherApplication
                            val source = work.intent.getStringExtra("source").orEmpty()
                            val event = work.intent.getStringExtra("event").orEmpty()
                            val answer = work.intent.getStringExtra("answer").orEmpty()
                            val binding = app.push.binding(source) ?: return@withTimeout
                            val client = app.push.clientFor(binding)
                            client.bootstrap()
                            val route = client.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event")
                            if (route.string("source_ref") != source || route.string("owner_id") != client.scope.owner || route.string("profile") != client.scope.profile || route.string("event_id") != event || route.string("activity_ref") != work.intent.getStringExtra("activity") || route.string("kind") !in setOf("approval","clarify") || route.string("state") != "pending" || route.optLong("expires_at") <= System.currentTimeMillis() / 1000 || !validNativeAnswer(route, answer)) return@withTimeout
                            if (getSystemService(KeyguardManager::class.java).isDeviceLocked || !app.push.permitsIncoming(source)) return@withTimeout
                            client.nativeRequest("/api/plugins/cypher/mobile/notifications/events/$event/answer", "POST", json("answer" to answer))
                            finishNativeActivity(this@NativeActivityActionJob,activityTag(source,work.intent.getStringExtra("activity").orEmpty()))
                        }
                    } catch (e: CancellationException) { if (e !is TimeoutCancellationException) throw e }
                    catch (_: Exception) { /* Leave the open notification on failure; never report success or retry an uncertain decision. */ }
                    params.completeWork(work)
                }
            } finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return false }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    companion object {
        fun enqueue(context: Context, source: String, event: String, answer: String, activity: String):Boolean {
            if (!validOpaque(source) || !validOpaque(event) || !validOpaque(activity) || answer.length !in 1..1024 || !(context.applicationContext as CypherApplication).push.permitsIncoming(source)) return false
            val scheduler=context.getSystemService(JobScheduler::class.java) ?: return false
            val work=Intent().putExtra("source", source).putExtra("event", event).putExtra("answer", answer).putExtra("activity", activity)
            fun queue(expedited:Boolean):Boolean = runCatching {
                val builder=JobInfo.Builder(7242,ComponentName(context,NativeActivityActionJob::class.java))
                val job=if(expedited&&android.os.Build.VERSION.SDK_INT>=31) builder.setExpedited(true).build() else builder.setOverrideDeadline(0).build()
                scheduler.enqueue(job,JobWorkItem(work))==JobScheduler.RESULT_SUCCESS
            }.getOrDefault(false)
            return queue(true)||queue(false)
        }
    }
}
