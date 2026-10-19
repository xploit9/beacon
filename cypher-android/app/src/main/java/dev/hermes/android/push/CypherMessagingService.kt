package dev.hermes.android.push
import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.hermes.android.*
import dev.hermes.android.core.*
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.authorizesPushSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import dev.hermes.android.voice.IncomingCall
import dev.hermes.android.voice.IncomingCallReceiver
import dev.hermes.android.voice.RelayCallbackPolicy
import dev.hermes.android.voice.RelayCallbackPreferences
import dev.hermes.android.voice.IncomingRingtoneService
import dev.hermes.android.voice.callbackMayAlert
import dev.hermes.android.core.Scope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
private val opaquePattern=Regex("[A-Za-z0-9_.:-]+")
fun validOpaque(value: String): Boolean = value.length in 1..200 && value != "." && value != ".." && value.matches(opaquePattern)
internal fun requiresLocalKeepConnectedReceipt(localActionToken: String?): Boolean = localActionToken != null
internal fun canAuthorizeWithoutKeepConnectedReceipt(receiptRequired: Boolean, action: String): Boolean =
    !receiptRequired && (action == "answer" || action == "decline")
internal fun nativePushBindingAuthorized(binding: JSONObject, auth: dev.hermes.android.data.AuthContext): Boolean =
    binding.string("backend") == "hermes-dashboard" && binding.string("profile").isNotBlank() &&
        auth.authorizesPushSource(Scope(binding.string("origin"), binding.string("owner"), binding.string("profile")))
/** A failed commit may still mutate SharedPreferences memory; restore the prior transaction before reporting failure. */
internal fun persistPushBindings(prefs:android.content.SharedPreferences,values:Map<String,String?>) {
    val previous=values.keys.associateWith { prefs.getString(it,null) }
    val edit=prefs.edit();values.forEach { (key,value) -> if(value==null) edit.remove(key) else edit.putString(key,value) }
    if(!edit.commit()) {
        val rollback=prefs.edit()
        previous.forEach { (key,value) -> if(value==null) rollback.remove(key) else rollback.putString(key,value) }
        check(rollback.commit()) { "Could not restore notification bindings after storage failure" }
        error("Could not save notification bindings")
    }
}
class PushManager(private val app: CypherApplication) {
    private val privatePrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
    private val lock = Any()
    private val registration=Mutex()
    private data class KeepConnectedReceipt(val source: String, val event: String, val prepared: PreparedKeepConnectedCall)
    private val keepConnectedReceipts = mutableMapOf<String, KeepConnectedReceipt>()
    @Volatile private var newestToken:String?=null
    private val _incoming = kotlinx.coroutines.flow.MutableStateFlow<IncomingCall?>(null)
    val incoming = _incoming.asStateFlow()
    private fun encoded(key:String,value:JSONObject):String = android.util.Base64.encodeToString(app.cipher.encrypt(value.toString().toByteArray(),key),android.util.Base64.NO_WRAP)
    private fun save(key: String, value: JSONObject) { check(privatePrefs.edit().putString(key,encoded(key,value)).commit()) }
    private fun read(key: String): JSONObject? = privatePrefs.getString(key, null)?.let { JSONObject(String(app.cipher.decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), key))) }
    fun reconcileIncomingDeadlines() = synchronized(lock) {
        privatePrefs.all.keys.filter { it.startsWith("incoming:") }.forEach { key ->
            runCatching { read(key) }.getOrNull()?.let { expireInvitation(it.string("source"), it.string("event")) }
        }
    }
    fun registeredTarget(scope: Scope): String? = synchronized(lock) { runCatching { read("active:${scope.key}")?.string("target")?.takeIf(String::isNotBlank) }.getOrNull() }
    suspend fun register(client: CypherClient, rotatedToken: String? = null): String = withTimeout(20000) {
        registration.withLock { registerCurrent(client,rotatedToken) }
    }
    private suspend fun registerCurrent(client: CypherClient, rotatedToken: String?): String {
        require(app.firebaseConfigured) { "Firebase is not configured in this build. Set the owner Firebase project before enabling background push." }
        require(app.auth.read(client.auth.origin).authorizesPushSource(client.scope)) { "Sign in to this owner account before registering notifications" }
        val token = rotatedToken ?: suspendCancellableCoroutine<String> { c -> FirebaseMessaging.getInstance().token.addOnSuccessListener { if (c.isActive) c.resume(it) }.addOnFailureListener { if (c.isActive) c.resumeWithException(it) } }
        require(token.isNotBlank()) { "Firebase did not return a device token" }
        val previous = synchronized(lock) {
            runCatching { read("active:${client.scope.key}") }.getOrNull()?.takeIf { validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
                ?:bindings().firstOrNull { it.string("origin")==client.scope.origin&&it.string("owner")==client.scope.owner&&it.string("profile")==client.scope.profile&&validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
        }
        val o = client.nativeRequest("/api/plugins/cypher/mobile/notifications/targets", "POST", json("kind" to "fcm", "token" to token, "app_version_code" to BuildConfig.VERSION_CODE))
        val target = o.getString("target_id")
        val source = o.string("source_ref")
        try {
            require(validOpaque(source) && validOpaque(target) && client.auth.backend == "hermes-dashboard") { "Server did not return an authorized push source binding" }
            synchronized(lock) {
                require(app.auth.read(client.auth.origin).authorizesPushSource(client.scope)) { "The owner signed out while notifications were registering" }
                val sourceKey="source:${source.digest()}";val activeKey="active:${client.scope.key}"
                // Encrypt both values before touching either binding, then commit them together.
                val replacedSource=previous?.string("source")?.takeIf { validOpaque(it)&&it!=source }
                val values=linkedMapOf<String,String?>(
                    sourceKey to encoded(sourceKey,json("origin" to client.auth.origin,"owner" to client.auth.owner,"profile" to client.auth.profile,"backend" to client.auth.backend,"target" to target,"source" to source)),
                    activeKey to encoded(activeKey,json("source" to source,"target" to target)))
                if(replacedSource!=null) values["source:${replacedSource.digest()}"]=null
                persistPushBindings(privatePrefs,values)
            }
        } catch (e: Throwable) {
            // The host upserts a deterministic target for the same token; a failed local refresh must not revoke the prior valid target.
            val retained=previous?.string("target")==target&&app.auth.read(client.auth.origin).authorizesPushSource(client.scope)
            if(validOpaque(target)&&!retained) withContext(NonCancellable) { withTimeoutOrNull(5000) {
                runCatching { client.nativeRequest("/api/plugins/cypher/mobile/notifications/targets/$target","DELETE") }
                    .onFailure { android.util.Log.w("CypherPush","Remote notification target cleanup could not be confirmed") }
            } }
            throw e
        }
        previous?.string("source")?.takeIf { validOpaque(it)&&it!=source }?.let { oldSource ->
            runCatching { cancelNativeActivities(app,oldSource);synchronized(lock) { _incoming.value?.takeIf { it.source==oldSource }?.let { dismissInvitation(it.source,it.event) } } }
                .onFailure { android.util.Log.w("CypherPush","Previous notification presentation cleanup failed") }
        }
        previous?.string("target")?.takeIf { validOpaque(it) && it != target }?.let { old -> runCatching { client.nativeRequest("/api/plugins/cypher/mobile/notifications/targets/$old", "DELETE") } }
        return target
    }
    suspend fun revoke(client: CypherClient) = withTimeout(20000) { registration.withLock {
        val key="active:${client.scope.key}"
        val targets=synchronized(lock) {
            val active=runCatching { read(key) }.getOrNull()
            // Even a corrupt active pointer cannot retain valid source authority for this exact scope.
            val owned=bindings().filter { it.string("origin")==client.scope.origin&&it.string("owner")==client.scope.owner&&it.string("profile")==client.scope.profile }
            val sources=owned.map { it.string("source") }.filter(::validOpaque).toSet()
            val edit=privatePrefs.edit().remove(key)
            sources.forEach { edit.remove("source:${it.digest()}") }
            val removed=removeIncoming(sources,edit)
            check(edit.commit()) { "Could not remove local notification authorization" }
            keepConnectedReceipts.entries.removeAll { it.value.source in sources }
            sources.forEach { cancelNativeActivities(app,it) }
            removed.forEach { (source,event) -> dismissInvitation(source,event) }
            (owned.map { it.string("target") }+active?.string("target").orEmpty()).filter(::validOpaque).distinct()
        }
        targets.forEach { target -> client.nativeRequest("/api/plugins/cypher/mobile/notifications/targets/$target","DELETE") }
    } }
    fun binding(source: String): JSONObject? { if (!validOpaque(source)) return null; return synchronized(lock) { runCatching { read("source:${source.digest()}") }.getOrNull() } }
    private fun receiptStillAuthorized(source: String, call: PreparedKeepConnectedCall): Boolean {
        val stored = binding(source) ?: return false
        val scope = Scope(call.binding.origin, call.binding.owner, call.binding.profile)
        return stored.string("source") == source && stored.string("origin") == scope.origin &&
            stored.string("owner") == scope.owner && stored.string("profile") == scope.profile &&
            nativePushBindingAuthorized(stored, app.auth.read(scope.origin))
    }
    fun permitsIncoming(source: String): Boolean = binding(source)?.let { b -> runCatching {
        nativePushBindingAuthorized(b, app.auth.read(b.getString("origin"))) &&
            (!b.optBoolean("keep_connected") || KeepConnectedService.isEnabledFor(app, Scope(b.getString("origin"), b.getString("owner"), b.getString("profile"))))
    }.getOrDefault(false) } == true
    fun clientFor(binding: JSONObject): CypherClient {
        val origin = trustedOrigin(binding.getString("origin"))
        val current = app.auth.read(origin)
        require(nativePushBindingAuthorized(binding, current)) { "Sign in to the invitation's native Hermes account" }
        return KeepConnectedService.createClient(app, current).forAuthorizedProfile(binding.getString("profile"))
    }
    fun bindKeepConnectedSource(scope: Scope, source: String): Boolean {
        if (!validOpaque(source) || !app.auth.read(scope.origin).authorizesPushSource(scope)) return false
        return synchronized(lock) {
            val key = "source:${source.digest()}"
            val existing = runCatching { read(key) }.getOrNull()
            if (existing != null) {
                return@synchronized existing.string("source") == source && existing.string("origin") == scope.origin &&
                    existing.string("owner") == scope.owner && existing.string("profile") == scope.profile &&
                    nativePushBindingAuthorized(existing, app.auth.read(scope.origin))
            }
            val value = json("source" to source, "origin" to scope.origin, "owner" to scope.owner,
                "profile" to scope.profile, "backend" to "hermes-dashboard", "target" to "", "keep_connected" to true)
            persistPushBindings(privatePrefs, mapOf(key to encoded(key, value)))
            true
        }
    }
    fun prepareKeepConnectedCall(scope: Scope, event: JSONObject, now: Long, elapsed: Long): String? {
        if (!KeepConnectedService.isEnabledFor(app, scope) || !app.auth.read(scope.origin).authorizesPushSource(scope)) return null
        val source = event.optString("source_ref")
        val eventId = event.optString("event_id")
        if (!validOpaque(source) || !validOpaque(eventId) || deadlineInvitation(source, eventId) != null) return null
        val prepared = dev.hermes.android.push.prepareKeepConnectedCall(scope, event, enabled = true, now = now, elapsed = elapsed) ?: return null
        if (!bindKeepConnectedSource(scope, source)) return null
        return synchronized(lock) {
            if (storedInvitation(source, eventId) != null) return@synchronized null
            keepConnectedReceipts["$source\u0000$eventId".digest()] = KeepConnectedReceipt(source, eventId, prepared)
            prepared.call.actionToken
        }
    }
    fun hasKeepConnectedInvitation(source: String, event: String, token: String): Boolean = synchronized(lock) {
        storedInvitation(source, event)?.actionToken == token
    }
    fun removeKeepConnectedCallback(source: String, event: String, token: String? = null): Unit = synchronized(lock) {
        val key = "$source\u0000$event".digest()
        keepConnectedReceipts[key]?.takeIf { token == null || it.prepared.call.actionToken == token }?.let { keepConnectedReceipts.remove(key) }
        Unit
    }
    fun authorizeKeepConnectedAction(source: String, event: String, action: String): Boolean {
        val invite = deadlineInvitation(source, event) ?: return false
        val receipt = synchronized(lock) { keepConnectedReceipts["$source\u0000$event".digest()] }
            ?: return canAuthorizeWithoutKeepConnectedReceipt(invitationRequiresKeepConnectedReceipt(source, event), action) &&
                invitation(source, event) != null
        val call = receipt.prepared
        val scope = Scope(call.binding.origin, call.binding.owner, call.binding.profile)
        if (receipt.source != source || !receiptStillAuthorized(source, call)) {
            call.protocol.invalidate(null, null)
            discardInvitation(source, event)
            return false
        }
        val currentPreferences = call.preferences.copy(keepConnected = KeepConnectedService.isEnabledFor(app, scope))
        if (!RelayCallbackPolicy.callsAllowed(currentPreferences, call.binding.connectionId)) {
            call.protocol.invalidate(call.binding, currentPreferences)
            discardInvitation(source, event)
            return false
        }
        val now = System.currentTimeMillis()
        val elapsed = android.os.SystemClock.elapsedRealtime()
        return when (action) {
            "answer" -> call.protocol.answer(event, invite.actionToken, call.binding, currentPreferences, now, elapsed) != null
            "decline" -> call.protocol.decline(event, invite.actionToken, call.binding, currentPreferences, now, elapsed) != null
            else -> false
        }
    }
    fun invalidateKeepConnectedInvitations() {
        val receipts = synchronized(lock) { keepConnectedReceipts.values.toList().also { keepConnectedReceipts.clear() } }
        receipts.forEach { receipt ->
            receipt.prepared.protocol.invalidate(receipt.prepared.binding, receipt.prepared.preferences.copy(keepConnected = false))
            discardInvitation(receipt.source, receipt.event)
        }
    }
    private fun bootCount() = android.provider.Settings.Global.getInt(app.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
    private fun storedInvitation(source: String, event: String): IncomingCall? = runCatching {
        read("incoming:${"$source\u0000$event".digest()}")?.let {
            IncomingCall(it.getString("source"), it.getString("event"), it.getLong("expires_at"), it.getString("action_token"),
                it.optLong("arrived_at"), it.optLong("arrived_elapsed"), it.optInt("boot", -2), it.optString("state", "missed"), it.optString("conversation"))
        }?.takeIf { it.source == source && it.event == event }
    }.getOrNull()
    private fun invitationRequiresKeepConnectedReceipt(source: String, event: String): Boolean = runCatching {
        val stored = read("incoming:${"$source\u0000$event".digest()}") ?: return@runCatching true
        !stored.has("keep_connected_receipt_required") || stored.optBoolean("keep_connected_receipt_required")
    }.getOrDefault(true)
    private fun persistInvitation(invite: IncomingCall, receiptRequired: Boolean = invitationRequiresKeepConnectedReceipt(invite.source, invite.event)) = save("incoming:${invite.key}", json("source" to invite.source, "event" to invite.event,
        "expires_at" to invite.expiresAt, "action_token" to invite.actionToken, "arrived_at" to invite.arrivedAt,
        "arrived_elapsed" to invite.arrivedElapsed, "boot" to invite.boot, "state" to invite.state, "conversation" to invite.conversation,
        "keep_connected_receipt_required" to receiptRequired))
    private fun deadlineIntent(invite: IncomingCall) = PendingIntent.getBroadcast(app, 0,
        Intent(app, IncomingCallReceiver::class.java).setAction(invite.deadlineAction).setData(android.net.Uri.parse("hermes://incoming/${invite.key}/deadline"))
            .putExtra("push_source", invite.source).putExtra("push_event", invite.event), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun saveInvitation(invite: IncomingCall, receiptRequired: Boolean = true): Boolean = synchronized(lock) {
        if (!invite.valid() || !permitsIncoming(invite.source) || storedInvitation(invite.source, invite.event) != null) return false
        val saved = invite.copy(arrivedAt = System.currentTimeMillis(), arrivedElapsed = android.os.SystemClock.elapsedRealtime(), boot = bootCount())
        persistInvitation(saved, receiptRequired)
        _incoming.value = saved
        val alarm = app.getSystemService(AlarmManager::class.java)
        // Exact alarm access is optional; the active service independently enforces the audio deadline.
        val trigger = saved.arrivedElapsed + saved.ringDurationMillis(currentBoot = bootCount())
        if (android.os.Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()) alarm.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, deadlineIntent(saved))
        else alarm.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, deadlineIntent(saved))
        true
    }
    fun invitation(source: String, event: String): IncomingCall? = synchronized(lock) {
        if (!validOpaque(source) || !validOpaque(event) || !permitsIncoming(source)) return null
        storedInvitation(source, event)?.takeIf { it.valid() && it.state in setOf("ringing", "dismissed") }
    }
    fun missedInvitation(source: String, event: String): IncomingCall? = synchronized(lock) {
        if (!validOpaque(source) || !validOpaque(event) || !permitsIncoming(source)) return null
        storedInvitation(source, event)?.takeIf { it.state == "missed" }
    }
    fun deadlineInvitation(source: String, event: String): IncomingCall? = synchronized(lock) {
        if (!validOpaque(source) || !validOpaque(event)) return null
        storedInvitation(source, event)
    }
    fun ringingInvitation(source: String, event: String): IncomingCall? = synchronized(lock) {
        val invite = invitation(source, event) ?: return null
        if (invite.ringDurationMillis(currentBoot = bootCount()) <= 0) { expireInvitation(source, event); null } else invite
    }
    fun presentInvitation(source: String, event: String) { _incoming.value = ringingInvitation(source, event) }
    fun claimInvitation(source: String, event: String): Boolean = synchronized(lock) {
        if (ringingInvitation(source, event) == null) return false
        dismissInvitation(source, event)
        true
    }
    fun dismissInvitation(source: String, event: String) = synchronized(lock) {
        storedInvitation(source, event)?.let { invite ->
            if (invite.state == "ringing") persistInvitation(invite.copy(state = "dismissed"))
            app.getSystemService(AlarmManager::class.java).cancel(deadlineIntent(invite))
        }
        clearIncomingPresentation(source, event)
    }
    private fun clearIncomingPresentation(source: String, event: String) {
        dev.hermes.android.voice.IncomingRingtoneService.silence(source, event)
        if (_incoming.value?.let { it.source == source && it.event == event } == true) _incoming.value = null
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.cancel("$source\u0000$event".digest(), 2)
        manager.cancel("$source\u0000$event".digest(), 1)
    }
    fun expireInvitation(source: String, event: String) = synchronized(lock) {
        val invite = storedInvitation(source, event) ?: return
        if (invite.state != "ringing") return
        if (invite.ringDurationMillis(currentBoot = bootCount()) > 0) return
        val key = "$source\u0000$event".digest()
        keepConnectedReceipts.remove(key)?.prepared?.let { call ->
            val scope = Scope(call.binding.origin, call.binding.owner, call.binding.profile)
            val authorizedBinding = call.binding.takeIf { receiptStillAuthorized(source, call) }
            val prefs = call.preferences.copy(keepConnected = KeepConnectedService.isEnabledFor(app, scope))
            call.protocol.expire(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), authorizedBinding, prefs)
        }
        persistInvitation(invite.copy(state = "missed"))
        clearIncomingPresentation(source, event)
        if (!permitsIncoming(source)) return
        val manager = app.getSystemService(NotificationManager::class.java)
        ensureActivityChannels(manager)
        val intent = Intent(app, CypherActivity::class.java).setAction(invite.callbackAction)
            .setData(android.net.Uri.parse("hermes://incoming/${invite.key}/callback"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
        val callback = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(invite.key, 2, NotificationCompat.Builder(app, "hermes-messages").setSmallIcon(R.drawable.ic_cypher_notification)
            .setContentTitle("Cypher · Missed call").setContentText("Open Cypher to call back or view its history.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setSilent(true).setAutoCancel(true).setContentIntent(callback)
            .addAction(R.drawable.ic_cypher, "Call back", callback).build()) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
    }
    fun discardInvitation(source: String, event: String): Boolean = synchronized(lock) {
        if (!validOpaque(source) || !validOpaque(event)) return false
        val key = "incoming:${"$source\u0000$event".digest()}"
        val stored = privatePrefs.contains(key)
        keepConnectedReceipts.remove("$source\u0000$event".digest())
        storedInvitation(source, event)?.let { persistInvitation(it.copy(state = "ended")) }
        dismissInvitation(source, event)
        stored
    }
    private fun removeIncoming(sources: Set<String>, edit: android.content.SharedPreferences.Editor): List<Pair<String, String>> =
        privatePrefs.all.keys.filter { it.startsWith("incoming:") }.mapNotNull { key ->
            runCatching { read(key) }.getOrNull()?.takeIf { it.string("source") in sources }?.let { invite -> edit.remove(key); invite.string("source") to invite.string("event") }
        }
    private fun bindings(): List<JSONObject> = synchronized(lock) { privatePrefs.all.keys.filter { it.startsWith("source:") }.mapNotNull { runCatching { read(it) }.getOrNull() } }
    private fun fcmBindings() = bindings().filter { validOpaque(it.string("target")) }
    fun tokenChanged(token:String) {
        newestToken=token
        PushRegistrationJob.schedule(app,replace=true)
    }
    suspend fun refreshRegistrations() {
        if(!app.firebaseConfigured || fcmBindings().isEmpty()) return
        val token=suspendCancellableCoroutine<String> { c -> FirebaseMessaging.getInstance().token.addOnSuccessListener { if(c.isActive) c.resume(it) }.addOnFailureListener { if(c.isActive) c.resumeWithException(it) } }
        newestToken=token
        rotate(token)
    }
    suspend fun rotate(token: String) = withTimeout(30000) { registration.withLock {
        if(!app.firebaseConfigured||newestToken?.let { it!=token }==true) return@withLock
        var failed=false
        fcmBindings().forEach { binding ->
            if(newestToken?.let { it!=token }==true) return@withLock
            // A new token never invents a source: authenticate every previously registered scope.
            runCatching {
                withTimeout(10000) {
                    val c=clientFor(binding)
                    try {
                        c.bootstrap()
                        require(c.auth.owner==binding.string("owner")&&c.auth.profile==binding.string("profile"))
                        registerCurrent(c,token)
                    } finally { c.closeNative() }
                }
            }.onFailure { if(it is CancellationException) { currentCoroutineContext().ensureActive(); if(it !is kotlinx.coroutines.TimeoutCancellationException) throw it };failed=true }
        }
        check(!failed) { "Notification registration needs a retry" }
    } }
    suspend fun revokeOwner(client: CypherClient) {
        val owned = bindings().filter { it.string("origin") == client.auth.origin && it.string("owner") == client.auth.owner }
        val ownedSources = owned.map { it.string("source") }.toSet()
        val remotelyOwned = owned.filter { validOpaque(it.string("target")) }
        // Local revocation precedes network IO, so an offline sign-out cannot be revived by a tap.
        synchronized(lock) {
            val edit = privatePrefs.edit()
            val removed = removeIncoming(ownedSources, edit)
            owned.forEach { binding -> edit.remove("source:${binding.string("source").digest()}").remove("active:${Scope(client.auth.origin, client.auth.owner, binding.string("profile")).key}") }
            check(edit.commit()) { "Could not remove local notification authorization" }
            keepConnectedReceipts.entries.removeAll { it.value.source in ownedSources }
            owned.forEach { cancelNativeActivities(app, it.string("source")) }
            removed.forEach { (source, event) -> dismissInvitation(source, event) }
        }
        var failure: Throwable? = null
        remotelyOwned.forEach { binding ->
            runCatching {
                require(binding.string("backend")=="hermes-dashboard"&&binding.string("profile").isNotBlank()&&validOpaque(binding.string("target")))
                // Logout already erased AuthStore; this frozen snapshot is used only to revoke the verified prior scope.
                val c=client.detached()
                try {
                    c.bindProfile(binding.string("profile"))
                    c.bootstrap()
                    require(c.scope==Scope(client.auth.origin,client.auth.owner,binding.string("profile"))) { "Notification revocation authority no longer matches the owner" }
                    c.nativeRequest("/api/plugins/cypher/mobile/notifications/targets/${binding.getString("target")}","DELETE")
                } finally { c.closeNative() }
            }.onFailure { if (it is CancellationException) throw it; if (failure == null) failure = it }
        }
        failure?.let { throw it }
    }
    /** Arrival requires no tailnet request. Opaque event dedup survives normal process absence. */
    fun firstArrival(source: String, event: String): Boolean = synchronized(lock) {
        if (!validOpaque(source) || !validOpaque(event)) return false
        val key = "seen:${"$source\u0000$event".digest()}"
        if (privatePrefs.contains(key)) return false
        val seen = privatePrefs.all.filterKeys { it.startsWith("seen:") }
        val editor = privatePrefs.edit().putLong(key, System.currentTimeMillis())
        if (seen.size >= 4096) seen.entries.sortedBy { it.value as? Long ?: 0 }.take(seen.size - 4095).forEach { editor.remove(it.key) }
        check(editor.commit()) { "Could not save notification delivery identity" }
        true
    }
}
internal data class IncomingCallCardOptions(
    val category: String,
    val callStyle: Boolean,
    val fullScreenIntent: Boolean,
    val silent: Boolean,
    val insistent: Boolean,
    val onlyAlertOnce: Boolean,
    val timeoutAfterMillis: Long,
)

internal fun incomingCallCardOptions(timeoutAfterMillis: Long, silent: Boolean): IncomingCallCardOptions = IncomingCallCardOptions(
    category = NotificationCompat.CATEGORY_CALL,
    callStyle = true,
    fullScreenIntent = !silent,
    silent = silent,
    insistent = !silent,
    onlyAlertOnce = true,
    timeoutAfterMillis = timeoutAfterMillis.coerceAtLeast(1),
)

internal fun incomingCallMayAlert(
    activeCall: Boolean,
    audioModeNormal: Boolean,
    invitationLive: Boolean,
    invitationSilenced: Boolean,
    notificationsEnabled: Boolean,
    channelImportance: Int,
    channelSound: Boolean,
    ringerMode: Int,
    ringVolume: Int,
): Boolean = callbackMayAlert(activeCall, audioModeNormal) && invitationLive && !invitationSilenced &&
    notificationsEnabled && channelImportance > NotificationManager.IMPORTANCE_NONE && channelSound &&
    ringerMode == AudioManager.RINGER_MODE_NORMAL && ringVolume > 0

internal fun incomingCallMayVibrate(
    notificationSilent: Boolean,
    notificationsEnabled: Boolean,
    channelImportance: Int,
    channelVibrates: Boolean,
    ringerMode: Int,
    dndAllows: Boolean,
): Boolean = notificationSilent && notificationsEnabled && channelImportance >= NotificationManager.IMPORTANCE_DEFAULT &&
    channelVibrates && ringerMode != AudioManager.RINGER_MODE_SILENT && dndAllows

class CypherMessagingService : FirebaseMessagingService() {
    private val app get() = application as CypherApplication
    override fun onNewToken(token: String) {
        app.push.tokenChanged(token)
    }
    override fun onMessageReceived(message: RemoteMessage) { receivePush(this, message.data) }
    companion object {
        const val CHANNEL = "hermes-activity"
        // Keep the installed channel ID so owners retain its ringtone and importance settings.
        const val INCOMING_CHANNEL = "xara-incoming-call-v2"
    }
}
/** Same entry point for FCM and isolated native delivery checks. */
fun receivePush(context: Context, data: Map<String, String>) = receivePush(context, data, null)

internal fun receiveKeepConnectedCall(context: Context, data: Map<String, String>, actionToken: String) {
    require(validOpaque(actionToken)) { "Invalid local call action token" }
    receivePush(context, data, actionToken)
}

private fun receivePush(context: Context, data: Map<String, String>, localActionToken: String?) {
        val app = context.applicationContext as CypherApplication
        val source = data["source_ref"].orEmpty()
        val event = data["event_id"].orEmpty()
        if (data["kind"] == "release") {
            if (!validOpaque(source) || !validOpaque(event) || !validOpaque(data["conversation_ref"].orEmpty()) || !app.push.permitsIncoming(source)) return
            val binding = app.push.binding(source) ?: return
            if (app.push.firstArrival(source, event)) app.updates.notifyPush(binding.getString("origin"), binding.getString("owner"), data["release_version"])
            return
        }
        if (!validOpaque(source) || !validOpaque(event) || !validOpaque(data["conversation_ref"].orEmpty()) || !app.push.permitsIncoming(source)) return
        val nativeKind = data["kind"].orEmpty()
        val activity = data["activity_ref"].orEmpty()
        if (validOpaque(activity) && (ongoingNativeKind(nativeKind) || nativeKind == "activity_end")) {
            presentNativeActivity(context, source, event, activity, nativeKind, data["expires_at"]?.toLongOrNull() ?: 0)
            if (nativeKind != "activity_end") NotificationPreviewJob.enqueue(context, source, event, data["conversation_ref"].orEmpty(), nativeKind)
            return
        }
        if (data["kind"] !in setOf("failure", "completion", "approval", "clarify", "task_result", "test", "incoming_call")) return
        val incoming = data["kind"] == "incoming_call"
        var invite = if (incoming) IncomingCall(
            source, event, data["expires_at"]?.toLongOrNull() ?: 0,
            actionToken = localActionToken ?: java.util.UUID.randomUUID().toString(),
            conversation = data["conversation_ref"].orEmpty(),
        ) else null
        if (incoming && (invite == null || !invite.valid() || !validOpaque(data["conversation_ref"].orEmpty()) || !app.push.permitsIncoming(source))) return
        if (!incoming && !app.push.firstArrival(source, event)) return
        val ring = invite != null && app.push.saveInvitation(invite!!, requiresLocalKeepConnectedReceipt(localActionToken))
        if (incoming && !ring) return
        if (ring) invite = app.push.ringingInvitation(source, event)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureActivityChannels(manager)
        val activityChannel = if (incoming) incomingCallChannel(manager) else if(nativeKind in setOf("approval","clarify")) promptNotificationChannel(manager) else notificationChannel(nativeKind)
        val routeKey = "$source\u0000$event".digest()
        val intent = Intent(context, CypherActivity::class.java).setData(android.net.Uri.parse("hermes://activity/$routeKey")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
        val pending = PendingIntent.getActivity(context, "$source:$event".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = notificationFallback(data["kind"].orEmpty())
        val public = NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle(content.first).setContentText("Open Cypher to view private activity.").build()
        if (!incoming) try { manager.notify(routeKey, 1, NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle(content.first).setContentText(content.second).setStyle(NotificationCompat.BigTextStyle().bigText(content.second)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setSilent(incoming).setContentIntent(pending).setAutoCancel(true).build()) } catch (_: SecurityException) { return }
        if (invite != null && ring) {
            val invite = invite!!
            val assistantName = runBlocking { context.applicationContext.preferences.data.first().let(AppPreferences::read).assistantName }
            val incomingChannel = incomingCallChannel(manager)
            val channel = manager.getNotificationChannel(incomingChannel)
            val audio = context.getSystemService(AudioManager::class.java)
            val alert = incomingCallMayAlert(
                activeCall = app.calls.state.value.active,
                audioModeNormal = audio.mode == AudioManager.MODE_NORMAL,
                invitationLive = invite.ringDurationMillis() > 0,
                invitationSilenced = IncomingRingtoneService.isSilenced(source, event),
                notificationsEnabled = manager.areNotificationsEnabled(),
                channelImportance = channel?.importance ?: NotificationManager.IMPORTANCE_NONE,
                channelSound = channel?.sound != null,
                ringerMode = audio.ringerMode,
                ringVolume = audio.getStreamVolume(AudioManager.STREAM_RING),
            )
            val options = incomingCallCardOptions(invite.ringDurationMillis(), silent = !alert)
            val answerIntent = Intent(context, CypherActivity::class.java).setAction(invite.answerAction).setData(android.net.Uri.parse("hermes://incoming/$routeKey/answer")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
            val answer = PendingIntent.getActivity(context, 0, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val declineIntent = Intent(context, IncomingCallReceiver::class.java).setAction(invite.declineAction).setData(android.net.Uri.parse("hermes://incoming/$routeKey/decline")).putExtra("push_source", source).putExtra("push_event", event)
            val decline = PendingIntent.getBroadcast(context, 0, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val person = androidx.core.app.Person.Builder().setName(assistantName).setImportant(true).build()
            fun notification(callStyle: Boolean = options.callStyle): Notification {
                val card = options
                val builder = NotificationCompat.Builder(context, incomingChannel).setSmallIcon(R.drawable.ic_cypher_notification).setContentTitle("$assistantName is calling").setContentText("Answer to start a private voice call").setContentIntent(pending).setDeleteIntent(decline).setCategory(card.category).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setTimeoutAfter(card.timeoutAfterMillis).setOnlyAlertOnce(card.onlyAlertOnce).setOngoing(true)
                if(android.os.Build.VERSION.SDK_INT>=36) builder.setRequestPromotedOngoing(true).setShortCriticalText("Calling")
                // The alerting CallStyle falls back to heads-up when FSI is denied; ringing never starts the microphone.
                if (card.fullScreenIntent) builder.setFullScreenIntent(pending, true)
                if (callStyle) builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
                else builder.addAction(R.drawable.ic_cypher, "Decline", decline).addAction(R.drawable.ic_cypher, "Answer", answer)
                // A non-silent CallStyle card lets Android's existing user-configured channel own the single ringtone.
                return builder.setSilent(card.silent).build().apply { if (card.insistent) flags = flags or Notification.FLAG_INSISTENT }
            }
            // Post only once, in the foreground call slot, so a move from a route-tagged slot cannot replay the ringtone.
            val ringingNotification = notification()
            try {
                ContextCompat.startForegroundService(context, Intent(context, dev.hermes.android.voice.IncomingRingtoneService::class.java)
                    .putExtra("push_source", source).putExtra("push_event", event).putExtra("notification", ringingNotification))
            } catch (error: RuntimeException) {
                android.util.Log.w("CypherRinging", "Phone-call foreground service rejected; posting the deadline-bound call card", error)
                val fallback = if (options.fullScreenIntent) ringingNotification else notification(callStyle = false)
                try { manager.notify(routeKey, 2, fallback) } catch (_: RuntimeException) { return }
            }
        }
        NotificationPreviewJob.enqueue(context, source, event, data["conversation_ref"].orEmpty(), data["kind"].orEmpty())
}
fun incomingCallChannel(manager: NotificationManager): String {
    // Keep the old ID so owner-configured channel settings remain attached during migration.
    val legacy = manager.getNotificationChannel("xara-incoming")
    // Rename the existing channel object, retaining its sound, mute and vibration choices.
    legacy?.let { it.name = "Cypher incoming calls"; manager.createNotificationChannel(it) }
    // Preserve deliberate mute/custom choices; untouched default-ding channels migrate once.
    if (legacy != null && (preserveIncomingChannel(legacy.hasUserSetImportance(), android.os.Build.VERSION.SDK_INT >= 30 && legacy.hasUserSetSound()))) return legacy.id
    manager.createNotificationChannel(NotificationChannel(CypherMessagingService.INCOMING_CHANNEL, "Cypher incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        enableVibration(true)
    })
    return CypherMessagingService.INCOMING_CHANNEL
}
internal fun preserveIncomingChannel(userImportance: Boolean, userSound: Boolean): Boolean = userImportance || userSound
internal fun notificationChannel(kind: String): String = when(kind) {
    "task_result" -> "hermes-tasks"
    "approval", "clarify" -> "hermes-approvals"
    "incoming_call" -> CypherMessagingService.INCOMING_CHANNEL
    else -> "hermes-messages"
}
fun ensureActivityChannels(manager: NotificationManager) {
    val legacy = manager.getNotificationChannel(CypherMessagingService.CHANNEL)
    listOf("hermes-messages" to "Cypher messages", "hermes-tasks" to "Cypher tasks", "hermes-approvals" to "Cypher approvals").forEach { (id, name) ->
        if (manager.getNotificationChannel(id) == null) {
            val channel = NotificationChannel(id, name, legacy?.importance ?: NotificationManager.IMPORTANCE_DEFAULT)
            // Inherit existing mute/sound choices when splitting the legacy activity channel.
            legacy?.let { channel.setSound(it.sound, it.audioAttributes); channel.enableVibration(it.shouldVibrate()); channel.vibrationPattern = it.vibrationPattern; channel.setShowBadge(it.canShowBadge()) }
            manager.createNotificationChannel(channel)
        }
    }
    promptNotificationChannel(manager)
}
/** Upgrade the old default channel once, preserving explicit owner sound/importance choices. */
fun promptNotificationChannel(manager:NotificationManager):String {
    val previous=manager.getNotificationChannel("hermes-approvals")
    if(previous!=null && (previous.hasUserSetImportance() || android.os.Build.VERSION.SDK_INT>=30&&previous.hasUserSetSound())) return previous.id
    val id="hermes-prompts-v2"
    manager.createNotificationChannel(NotificationChannel(id,"Questions and permissions",NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    return id
}
