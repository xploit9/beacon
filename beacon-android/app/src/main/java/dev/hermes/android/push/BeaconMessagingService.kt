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
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.NativeDeviceRemoval
import dev.hermes.android.data.authorizesPushSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import dev.hermes.android.voice.IncomingCall
import dev.hermes.android.voice.IncomingCallReceiver
import dev.hermes.android.voice.incomingRecordMayBePruned
import dev.hermes.android.voice.RelayCallbackPolicy
import dev.hermes.android.voice.RelayCallbackPreferences
import dev.hermes.android.voice.RelayCallbackPhase
import dev.hermes.android.voice.IncomingRingtoneService
import dev.hermes.android.voice.callbackMayAlert
import dev.hermes.android.core.Scope
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
private val opaquePattern=Regex("[A-Za-z0-9_.:-]+")
fun validOpaque(value: String): Boolean = value.length in 1..200 && value != "." && value != ".." && value.matches(opaquePattern)
// A reply that lands while Beacon is open is still listed, just without a sound.
internal fun silentPushNotification(kind: String, uiVisible: Boolean) = kind == "completion" && uiVisible
internal fun requiresLocalKeepConnectedReceipt(localActionToken: String?): Boolean = localActionToken != null
internal fun canAuthorizeWithoutKeepConnectedReceipt(receiptRequired: Boolean, action: String): Boolean =
    !receiptRequired && (action == "answer" || action == "decline")
internal fun nativePushBindingAuthorized(binding: JSONObject, auth: dev.hermes.android.data.AuthContext): Boolean =
    binding.string("backend") == "hermes-dashboard" && binding.string("profile").isNotBlank() &&
        auth.authorizesPushSource(bindingScope(binding))
private fun bindingScope(binding: JSONObject) = Scope(binding.string("origin"), binding.string("owner"), binding.string("profile"), nativeProvider = binding.string("native_provider"))
/** 1.1.1 stored bindings without native_provider. They never authorize anything; sign-out and rotation only clean them up. */
internal fun unstampedBindingOfAccount(binding: JSONObject, scope: Scope): Boolean =
    binding.string("native_provider").isEmpty() && binding.string("origin") == scope.origin && binding.string("owner") == scope.owner
/** 1.1.1 push of exactly this profile; the verified account registers it again so the update keeps push where it was on. */
internal fun legacyPushOfScope(binding: JSONObject, scope: Scope): Boolean =
    unstampedBindingOfAccount(binding, scope) && binding.string("profile") == scope.profile
/** The signed-in account's own event may restamp its unstamped 1.1.1 Keep-connected source, which holds no push target. */
internal fun restampableKeepConnectedSource(existing: JSONObject, scope: Scope, source: String): Boolean =
    unstampedBindingOfAccount(existing, scope) && existing.string("profile") == scope.profile &&
        existing.string("source") == source && existing.string("target").isEmpty()
internal fun pushTargetRetained(target: String, bindings: List<JSONObject>): Boolean = bindings.any { it.string("target") == target }
internal fun pushDeviceAuthorized(removed: Boolean, registeredDevice: String, currentDevice: String?): Boolean =
    !removed && (registeredDevice.isBlank() || registeredDevice == currentDevice)
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
class PushManager(private val app: BeaconApplication) {
    private val privatePrefs = app.getSharedPreferences("push-binding", Context.MODE_PRIVATE)
    private val lock = Any()
    private val registration=Mutex()
    private val cleanup = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pairingPrefs = app.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
    private fun removedKey(scope: Scope) = "removed:${NativePairing.accountKey(scope).removePrefix("device:")}"
    private val pairingListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key?.startsWith("device:") == true) {
            if (prefs.getString(key, null) != null) {
                check(privatePrefs.edit().remove("removed:${key.removePrefix("device:")}").commit()) { "Could not save phone notification authorization" }
            } else {
                bindings().map(::bindingScope)
                    .distinctBy(::removedKey).filter { NativePairing.accountKey(it) == key }
                    .forEach(::deviceRemoved)
            }
        }
    }
    private data class KeepConnectedReceipt(val source: String, val event: String, val prepared: PreparedKeepConnectedCall)
    private val keepConnectedReceipts = mutableMapOf<String, KeepConnectedReceipt>()
    @Volatile private var newestToken:String?=null
    private val _incoming = kotlinx.coroutines.flow.MutableStateFlow<IncomingCall?>(null)
    val incoming = _incoming.asStateFlow()
    internal val emailAlerts = MutableSharedFlow<Pair<Scope, JSONObject>>(extraBufferCapacity = 1)
    /** A scheduled result was posted into this chat; an open chat re-reads it. */
    internal val conversationPosts = MutableSharedFlow<Scope>(extraBufferCapacity = 4)
    private fun encoded(key:String,value:JSONObject):String = android.util.Base64.encodeToString(app.cipher.encrypt(value.toString().toByteArray(),key),android.util.Base64.NO_WRAP)
    private fun save(key: String, value: JSONObject) { check(privatePrefs.edit().putString(key,encoded(key,value)).commit()) }
    private fun read(key: String): JSONObject? = privatePrefs.getString(key, null)?.let { JSONObject(String(app.cipher.decrypt(android.util.Base64.decode(it, android.util.Base64.NO_WRAP), key))) }
    init {
        NativeDeviceRemoval.onRemoved = ::deviceRemoved
        pairingPrefs.registerOnSharedPreferenceChangeListener(pairingListener)
    }
    private fun deviceRemoved(scope: Scope) {
        synchronized(lock) {
            check(privatePrefs.edit().putBoolean(removedKey(scope), true).commit()) { "Could not stop removed-device notifications" }
            revokeLocalOwner(scope)
        }
        NativePairing.clearDeviceId(app, scope)
        // The screen cache's own hook exists only while a ViewModel is alive; the saved screens of a removed phone go either way.
        cleanup.launch { runCatching { app.cache.delete(scope.copy(profile = "", session = ""), "screens") } }
        if (app.auth.read(scope.origin).scope().sameAccount(scope)) KeepConnectedService.onUserSignedOut(app, scope.origin)
        cleanup.launch { runCatching { deleteUnusedFirebaseToken() }.onFailure { android.util.Log.w("BeaconPush", "Firebase token cleanup could not be confirmed") } }
    }
    private suspend fun deleteUnusedFirebaseToken() = registration.withLock { deleteUnusedFirebaseTokenLocked() }
    private suspend fun deleteUnusedFirebaseTokenLocked() {
        if (!app.firebaseConfigured || fcmBindings().isNotEmpty()) return
        withTimeout(5_000) {
            suspendCancellableCoroutine<Unit> { continuation ->
                FirebaseMessaging.getInstance().deleteToken()
                    .addOnSuccessListener { if (continuation.isActive) continuation.resume(Unit) }
                    .addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
            }
        }
        newestToken = null
    }
    fun reconcileIncomingDeadlines() = synchronized(lock) {
        val now = System.currentTimeMillis()
        privatePrefs.all.keys.filter { it.startsWith("incoming:") }.forEach { key ->
            runCatching { read(key) }.getOrNull()?.let { saved ->
                expireInvitation(saved.string("source"), saved.string("event"))
                runCatching { read(key) }.getOrNull()?.let { current ->
                    if (incomingRecordMayBePruned(current.optString("state", "missed"), current.optLong("expires_at"), now)) {
                        dismissInvitation(current.string("source"), current.string("event"))
                        check(privatePrefs.edit().remove(key).commit())
                    }
                }
            }
        }
    }
    fun registeredTarget(scope: Scope): String? = synchronized(lock) { runCatching { read("active:${scope.key}")?.string("target")?.takeIf(String::isNotBlank) }.getOrNull() }
    fun hasUnstampedPush(scope: Scope): Boolean = synchronized(lock) { runCatching { fcmBindings().any { legacyPushOfScope(it, scope) } }.getOrDefault(false) }
    suspend fun register(client: BeaconClient, rotatedToken: String? = null): String = withTimeout(20000) {
        registration.withLock { registerCurrent(client,rotatedToken) }
    }
    private suspend fun registerCurrent(client: BeaconClient, rotatedToken: String?): String {
        require(app.firebaseConfigured) { "Firebase is not configured in this build. Set the owner Firebase project before enabling background push." }
        require(app.auth.read(client.auth.origin).authorizesPushSource(client.scope)) { "Sign in to this owner account before registering notifications" }
        val deviceId = NativePairing.deviceId(app, client.scope)
        require(!privatePrefs.getBoolean(removedKey(client.scope), false) || deviceId != null) { "Add this phone again in Settings › Connection before turning on notifications" }
        val token = rotatedToken ?: suspendCancellableCoroutine<String> { c -> FirebaseMessaging.getInstance().token.addOnSuccessListener { if (c.isActive) c.resume(it) }.addOnFailureListener { if (c.isActive) c.resumeWithException(it) } }
        require(token.isNotBlank()) { "Firebase did not return a device token" }
        val previous = synchronized(lock) {
            runCatching { read("active:${client.scope.key}") }.getOrNull()?.takeIf { validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
                ?:bindings().firstOrNull { bindingScope(it)==client.scope&&validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
        }
        val o = client.nativeRequest("/api/plugins/beacon/mobile/notifications/targets", "POST", json("kind" to "fcm", "token" to token, "app_version_code" to BuildConfig.VERSION_CODE, "package_name" to BuildConfig.APPLICATION_ID))
        val target = o.getString("target_id")
        val source = o.string("source_ref")
        try {
            require(validOpaque(source) && validOpaque(target) && client.auth.backend == "hermes-dashboard") { "Server did not return an authorized push source binding" }
            synchronized(lock) {
                require(app.auth.read(client.auth.origin).authorizesPushSource(client.scope)) { "The owner signed out while notifications were registering" }
                require(deviceId == NativePairing.deviceId(app, client.scope)) { "This phone's link changed while notifications were registering" }
                val sourceKey="source:${source.digest()}";val activeKey="active:${client.scope.key}"
                // Encrypt both values before touching either binding, then commit them together.
                val replacedSource=previous?.string("source")?.takeIf { validOpaque(it)&&it!=source }
                val values=linkedMapOf<String,String?>(
                    sourceKey to encoded(sourceKey,json("origin" to client.auth.origin,"owner" to client.auth.owner,"native_provider" to client.auth.nativeProvider,"profile" to client.auth.profile,"backend" to client.auth.backend,"target" to target,"source" to source,"device_id" to deviceId.orEmpty())),
                    activeKey to encoded(activeKey,json("source" to source,"target" to target)))
                if(replacedSource!=null) values["source:${replacedSource.digest()}"]=null
                persistPushBindings(privatePrefs,values)
                check(privatePrefs.edit().remove(removedKey(client.scope)).commit()) { "Could not save phone notification authorization" }
            }
        } catch (e: Throwable) {
            // The host upserts a deterministic target for the same token; a failed local refresh must not revoke the prior valid target.
            val retained=previous?.string("target")==target&&app.auth.read(client.auth.origin).authorizesPushSource(client.scope)
            if(validOpaque(target)&&!retained) withContext(NonCancellable) { withTimeoutOrNull(5000) {
                runCatching { client.nativeRequest("/api/plugins/beacon/mobile/notifications/targets/$target","DELETE") }
                    .onFailure { android.util.Log.w("BeaconPush","Remote notification target cleanup could not be confirmed") }
            } }
            throw e
        }
        previous?.string("source")?.takeIf { validOpaque(it)&&it!=source }?.let { oldSource ->
            runCatching { cancelNativeActivities(app,oldSource);synchronized(lock) { _incoming.value?.takeIf { it.source==oldSource }?.let { dismissInvitation(it.source,it.event) } } }
                .onFailure { android.util.Log.w("BeaconPush","Previous notification presentation cleanup failed") }
        }
        previous?.string("target")?.takeIf { validOpaque(it) && it != target }?.let { old -> runCatching { client.nativeRequest("/api/plugins/beacon/mobile/notifications/targets/$old", "DELETE") } }
        return target
    }
    suspend fun revoke(client: BeaconClient) = withTimeout(20000) { registration.withLock {
        val key="active:${client.scope.key}"
        val targets=synchronized(lock) {
            val active=runCatching { read(key) }.getOrNull()
            // Even a corrupt active pointer cannot retain valid source authority for this exact scope.
            val owned=bindings().filter { bindingScope(it)==client.scope }
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
        targets.forEach { target -> client.nativeRequest("/api/plugins/beacon/mobile/notifications/targets/$target","DELETE") }
    } }
    fun binding(source: String): JSONObject? { if (!validOpaque(source)) return null; return synchronized(lock) { runCatching { read("source:${source.digest()}") }.getOrNull() } }
    private fun deviceAuthorized(binding: JSONObject): Boolean {
        val scope = bindingScope(binding)
        return pushDeviceAuthorized(privatePrefs.getBoolean(removedKey(scope), false), binding.string("device_id"), NativePairing.deviceId(app, scope))
    }
    private fun receiptStillAuthorized(source: String, call: PreparedKeepConnectedCall): Boolean {
        val stored = binding(source) ?: return false
        val scope = call.scope
        return stored.string("source") == source && stored.string("origin") == scope.origin &&
            bindingScope(stored) == scope &&
            nativePushBindingAuthorized(stored, app.auth.read(scope.origin)) && deviceAuthorized(stored)
    }
    fun permitsIncoming(source: String): Boolean = binding(source)?.let { b -> runCatching {
        nativePushBindingAuthorized(b, app.auth.read(b.getString("origin"))) &&
            deviceAuthorized(b) &&
            (!b.optBoolean("keep_connected") || KeepConnectedService.isEnabledFor(app, bindingScope(b)))
    }.getOrDefault(false) } == true
    fun clientFor(binding: JSONObject): BeaconClient {
        val origin = trustedOrigin(binding.getString("origin"))
        val current = app.auth.read(origin)
        require(nativePushBindingAuthorized(binding, current) && deviceAuthorized(binding)) { "Sign in to the invitation's native Hermes account" }
        return KeepConnectedService.createClient(app, current).forAuthorizedProfile(binding.getString("profile"))
    }
    fun bindKeepConnectedSource(scope: Scope, source: String): Boolean {
        if (!validOpaque(source) || !app.auth.read(scope.origin).authorizesPushSource(scope)) return false
        return synchronized(lock) {
            val key = "source:${source.digest()}"
            val existing = runCatching { read(key) }.getOrNull()
            if (existing != null && !restampableKeepConnectedSource(existing, scope, source)) {
                return@synchronized existing.string("source") == source && bindingScope(existing) == scope &&
                    nativePushBindingAuthorized(existing, app.auth.read(scope.origin))
            }
            val value = json("source" to source, "origin" to scope.origin, "owner" to scope.owner,
                "profile" to scope.profile, "native_provider" to scope.nativeProvider, "backend" to "hermes-dashboard", "target" to "", "keep_connected" to true)
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
        val scope = call.scope
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
        val current = call.protocol.current ?: return false
        // Native HTTPS actions settle on the host; consuming the local phase first would strand a retry.
        return action in setOf("answer", "decline") && current.phase == RelayCallbackPhase.Ringing &&
            current.binding == call.binding && current.callId == event && current.actionToken == invite.actionToken &&
            current.remainingRingMillis(now, elapsed) > 0 && invite.actionDurationMillis(now, elapsed, bootCount()) > 0
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
        storedInvitation(source, event)?.takeIf { it.valid() && it.state in setOf("ringing", "dismissed", "claimed", "retryable") }
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
        if (invite.state !in setOf("ringing", "retryable")) return null
        if (invite.actionDurationMillis(currentBoot = bootCount()) <= 0) { expireInvitation(source, event); null } else invite
    }
    // Only the sheet gets a ringing view; the persisted retry remains ineligible for ringtone playback.
    fun presentInvitation(source: String, event: String) = synchronized(lock) { _incoming.value = ringingInvitation(source, event)?.copy(state = "ringing") }
    fun claimInvitation(source: String, event: String): Boolean = synchronized(lock) {
        val invite = ringingInvitation(source, event) ?: return false
        persistInvitation(invite.copy(state = "claimed"))
        clearIncomingPresentation(source, event)
        true
    }
    fun retryInvitation(source: String, event: String) = synchronized(lock) {
        val invite = storedInvitation(source, event)?.takeIf { it.state == "claimed" } ?: return
        if (!permitsIncoming(source) || !invite.valid() || invite.actionDurationMillis(currentBoot = bootCount()) <= 0) {
            discardInvitation(source, event)
            return
        }
        persistInvitation(invite.copy(state = "retryable"))
        presentInvitation(source, event)
    }
    fun dismissInvitation(source: String, event: String) = synchronized(lock) {
        storedInvitation(source, event)?.let { invite ->
            if (invite.state in setOf("ringing", "retryable")) persistInvitation(invite.copy(state = "dismissed"))
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
        if (invite.state !in setOf("ringing", "retryable")) return
        if (invite.actionDurationMillis(currentBoot = bootCount()) > 0) return
        val key = "$source\u0000$event".digest()
        keepConnectedReceipts.remove(key)?.prepared?.let { call ->
            val scope = call.scope
            val authorizedBinding = call.binding.takeIf { receiptStillAuthorized(source, call) }
            val prefs = call.preferences.copy(keepConnected = KeepConnectedService.isEnabledFor(app, scope))
            call.protocol.expire(System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime(), authorizedBinding, prefs)
        }
        persistInvitation(invite.copy(state = "missed"))
        clearIncomingPresentation(source, event)
        if (!permitsIncoming(source)) return
        val manager = app.getSystemService(NotificationManager::class.java)
        ensureActivityChannels(manager)
        val intent = Intent(app, BeaconActivity::class.java).setAction(invite.callbackAction)
            .setData(android.net.Uri.parse("hermes://incoming/${invite.key}/callback"))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
        val callback = PendingIntent.getActivity(app, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(invite.key, 2, NotificationCompat.Builder(app, "hermes-messages").setSmallIcon(R.drawable.ic_beacon_notification)
            .setContentTitle("Beacon · Missed call").setContentText("Open Beacon to call back or view its history.")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setSilent(true).setAutoCancel(true).setContentIntent(callback)
            .addAction(R.drawable.ic_beacon_logo, "Call back", callback).build()) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
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
            // A 1.1.1 binding of a now-verified account: register its own profile again, which rewrites the shared source
            // record stamped. If that fails, drop it, since Keep connected cannot bind a source an unstamped record holds.
            val account=app.auth.read(binding.string("origin"))
            if(account.nativeProvider.isNotBlank()&&unstampedBindingOfAccount(binding,account.scope())) {
                val carried=runCatching {
                    withTimeout(10000) {
                        require(deviceAuthorized(binding)) { "Add this phone again in Settings › Connection before turning on notifications" }
                        val c=KeepConnectedService.createClient(app,account).forAuthorizedProfile(binding.getString("profile"))
                        try {
                            c.bootstrap()
                            require(c.scope==bindingScope(binding).copy(nativeProvider=account.nativeProvider))
                            registerCurrent(c,token)
                        } finally { c.closeNative() }
                    }
                }
                carried.exceptionOrNull()?.let { if(it is CancellationException) { currentCoroutineContext().ensureActive(); if(it !is kotlinx.coroutines.TimeoutCancellationException) throw it } }
                if(carried.isFailure) revokeLocalWhere { it.string("source")==binding.string("source") }
                return@forEach
            }
            // A new token never invents a source: authenticate every previously registered scope.
            runCatching {
                withTimeout(10000) {
                    val c=clientFor(binding)
                    try {
                        c.bootstrap()
                        require(c.scope==bindingScope(binding))
                        registerCurrent(c,token)
                    } finally { c.closeNative() }
                }
            }.onFailure { if(it is CancellationException) { currentCoroutineContext().ensureActive(); if(it !is kotlinx.coroutines.TimeoutCancellationException) throw it };failed=true }
        }
        check(!failed) { "Notification registration needs a retry" }
    } }
    private fun revokeLocalOwner(scope: Scope): List<JSONObject> = revokeLocalWhere { bindingScope(it).sameAccount(scope) || unstampedBindingOfAccount(it, scope) }
    private fun revokeLocalWhere(match: (JSONObject) -> Boolean): List<JSONObject> = synchronized(lock) {
        val owned = bindings().filter(match)
        val ownedSources = owned.map { it.string("source") }.toSet()
        // Local revocation precedes network IO, so an offline sign-out cannot be revived by a tap.
        val edit = privatePrefs.edit()
        val removed = removeIncoming(ownedSources, edit)
        owned.forEach { binding -> edit.remove("source:${binding.string("source").digest()}").remove("active:${bindingScope(binding).key}") }
        check(edit.commit()) { "Could not remove local notification authorization" }
        keepConnectedReceipts.entries.removeAll { it.value.source in ownedSources }
        owned.forEach { cancelNativeActivities(app, it.string("source")) }
        removed.forEach { (source, event) -> dismissInvitation(source, event) }
        owned
    }
    internal fun prepareOwnerRevocation(scope: Scope): List<JSONObject> = revokeLocalOwner(scope)
    suspend fun revokeOwner(client: BeaconClient, captured: List<JSONObject> = prepareOwnerRevocation(client.scope)) {
        val remotelyOwned = captured.filter { bindingScope(it).sameAccount(client.scope) && validOpaque(it.string("target")) }
        registration.withLock {
            var failure: Throwable? = null
            try { deleteUnusedFirebaseTokenLocked() }
            catch (error: CancellationException) { currentCoroutineContext().ensureActive(); failure = error }
            catch (error: Exception) { failure = error }
            remotelyOwned.forEach { binding ->
                if (pushTargetRetained(binding.string("target"), fcmBindings())) return@forEach
                runCatching {
                    require(binding.string("backend")=="hermes-dashboard"&&binding.string("profile").isNotBlank()&&validOpaque(binding.string("target")))
                    // Logout already erased AuthStore; this frozen snapshot is used only to revoke the verified prior scope.
                    val c=client.detachedForLogout()
                    try {
                        c.bindProfile(binding.string("profile"))
                        c.bootstrap()
                        require(c.scope==bindingScope(binding)) { "Notification revocation authority no longer matches the owner" }
                        c.nativeRequest("/api/plugins/beacon/mobile/notifications/targets/${binding.getString("target")}","DELETE")
                    } finally { c.closeNative() }
                }.onFailure { if (it is CancellationException) throw it; if (failure == null) failure = it }
            }
            failure?.let { throw it }
        }
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

class BeaconMessagingService : FirebaseMessagingService() {
    private val app get() = application as BeaconApplication
    override fun onNewToken(token: String) {
        app.push.tokenChanged(token)
    }
    override fun onMessageReceived(message: RemoteMessage) { receivePush(this, message.data) }
    companion object {
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
        val app = context.applicationContext as BeaconApplication
        val source = data["source_ref"].orEmpty()
        val event = data["event_id"].orEmpty()
        // Beacon asks GitHub for releases itself; a plugin's release hint is only for 1.1.x phones.
        if (data["kind"] == "release") return
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
        val intent = Intent(context, BeaconActivity::class.java).setData(android.net.Uri.parse("hermes://activity/$routeKey")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
        val pending = PendingIntent.getActivity(context, "$source:$event".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = notificationFallback(data["kind"].orEmpty())
        val public = NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle(content.first).setContentText("Open Beacon to view private activity.").build()
        if (!incoming) try { manager.notify(routeKey, 1, NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle(content.first).setContentText(content.second).setStyle(NotificationCompat.BigTextStyle().bigText(content.second)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setSilent(silentPushNotification(nativeKind, app.calls.uiVisible)).setContentIntent(pending).setAutoCancel(true).build()) } catch (_: SecurityException) { return }
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
            val answerIntent = Intent(context, BeaconActivity::class.java).setAction(invite.answerAction).setData(android.net.Uri.parse("hermes://incoming/$routeKey/answer")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
            val answer = PendingIntent.getActivity(context, 0, answerIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val declineIntent = Intent(context, IncomingCallReceiver::class.java).setAction(invite.declineAction).setData(android.net.Uri.parse("hermes://incoming/$routeKey/decline")).putExtra("push_source", source).putExtra("push_event", event)
            val decline = PendingIntent.getBroadcast(context, 0, declineIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val person = androidx.core.app.Person.Builder().setName(assistantName).setImportant(true).build()
            fun notification(callStyle: Boolean = options.callStyle): Notification {
                val card = options
                val builder = NotificationCompat.Builder(context, incomingChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle("$assistantName is calling").setContentText("Answer to start a private voice call").setContentIntent(pending).setDeleteIntent(decline).setCategory(card.category).setPriority(NotificationCompat.PRIORITY_HIGH).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).setTimeoutAfter(card.timeoutAfterMillis).setOnlyAlertOnce(card.onlyAlertOnce).setOngoing(true)
                if(android.os.Build.VERSION.SDK_INT>=36) builder.setRequestPromotedOngoing(true).setShortCriticalText("Calling")
                // The alerting CallStyle falls back to heads-up when FSI is denied; ringing never starts the microphone.
                if (card.fullScreenIntent) builder.setFullScreenIntent(pending, true)
                if (callStyle) builder.setStyle(NotificationCompat.CallStyle.forIncomingCall(person, decline, answer))
                else builder.addAction(R.drawable.ic_beacon_logo, "Decline", decline).addAction(R.drawable.ic_beacon_logo, "Answer", answer)
                // A non-silent CallStyle card lets Android's existing user-configured channel own the single ringtone.
                return builder.setSilent(card.silent).build().apply { if (card.insistent) flags = flags or Notification.FLAG_INSISTENT }
            }
            // Post only once, in the foreground call slot, so a move from a route-tagged slot cannot replay the ringtone.
            val ringingNotification = notification()
            try {
                ContextCompat.startForegroundService(context, Intent(context, dev.hermes.android.voice.IncomingRingtoneService::class.java)
                    .putExtra("push_source", source).putExtra("push_event", event).putExtra("notification", ringingNotification))
            } catch (error: RuntimeException) {
                android.util.Log.w("BeaconRinging", "Phone-call foreground service rejected; posting the deadline-bound call card", error)
                val fallback = if (options.fullScreenIntent) ringingNotification else notification(callStyle = false)
                try { manager.notify(routeKey, 2, fallback) } catch (_: RuntimeException) { return }
            }
        }
        NotificationPreviewJob.enqueue(context, source, event, data["conversation_ref"].orEmpty(), data["kind"].orEmpty())
}
fun incomingCallChannel(manager: NotificationManager): String {
    manager.createNotificationChannel(NotificationChannel(BeaconMessagingService.INCOMING_CHANNEL, "Beacon incoming calls", NotificationManager.IMPORTANCE_HIGH).apply {
        setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE), android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_RINGTONE).setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        enableVibration(true)
    })
    return BeaconMessagingService.INCOMING_CHANNEL
}
internal fun notificationChannel(kind: String): String = when(kind) {
    "task_result" -> "hermes-tasks"
    "approval", "clarify" -> "hermes-approvals"
    "incoming_call" -> BeaconMessagingService.INCOMING_CHANNEL
    else -> "hermes-messages"
}
fun ensureActivityChannels(manager: NotificationManager) {
    listOf("hermes-messages" to "Beacon messages", "hermes-tasks" to "Beacon tasks", "hermes-approvals" to "Beacon approvals").forEach { (id, name) ->
        if (manager.getNotificationChannel(id) == null) {
            manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT))
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
