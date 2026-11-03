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
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativePairing
import dev.hermes.android.data.NativeDeviceRemoval
import dev.hermes.android.data.authorizesPushSource
import dev.hermes.android.data.nativeErrorCodeText
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
private const val ADD_PHONE_AGAIN = "Add this phone again in Settings › Connection before turning on notifications"
/**
 * A push registration from a phone Hermes no longer knows is a 403 `device_required`: link again and retry once. ApiFailure
 * carries that code as plain words, so compare through the same mapping; a removed phone (`device_removed`) maps to other words and is refused.
 * shortcut: any other unmapped snake_case 403 reads the same, so it costs one extra link and retry (the targets route sends only these two); upgrade if ApiFailure ever carries the raw code.
 */
internal fun staleDeviceFailure(error: Throwable): Boolean =
    error is ApiFailure && error.plugin && error.status == 403 && error.detail == nativeErrorCodeText("device_required")
/** Sends [request]; if Hermes lost this phone, links it again with [relink] and sends once more. A second failure is thrown, never retried. */
internal suspend fun <T> retryOnceAfterRelink(request: suspend () -> T, relink: suspend () -> Unit): T = try { request() } catch (e: ApiFailure) {
    if (!staleDeviceFailure(e)) throw e
    relink()
    request()
}
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
    internal val emailAlerts = MutableSharedFlow<Scope>(extraBufferCapacity = 1)
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
        // The plugin takes a push target only from a linked phone, so link first; an automatic link leaves a removed phone alone (null).
        var deviceId: String = requireNotNull(NativePairing.deviceId(app, client.scope)
            ?: if (privatePrefs.getBoolean(removedKey(client.scope), false)) null else NativePairing.link(app, client, explicit = false)) { ADD_PHONE_AGAIN }
        val token = rotatedToken ?: suspendCancellableCoroutine<String> { c -> FirebaseMessaging.getInstance().token.addOnSuccessListener { if (c.isActive) c.resume(it) }.addOnFailureListener { if (c.isActive) c.resumeWithException(it) } }
        require(token.isNotBlank()) { "Firebase did not return a device token" }
        val previous = synchronized(lock) {
            runCatching { read("active:${client.scope.key}") }.getOrNull()?.takeIf { validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
                ?:bindings().firstOrNull { bindingScope(it)==client.scope&&validOpaque(it.string("source"))&&validOpaque(it.string("target")) }
        }
        val targets = "/api/plugins/beacon/mobile/notifications/targets"
        val body = json("kind" to "fcm", "token" to token, "app_version_code" to BuildConfig.VERSION_CODE, "package_name" to BuildConfig.APPLICATION_ID)
        // Hermes lost this phone if its database was reset; linking again replaces the unknown ID before the one retry.
        val o = retryOnceAfterRelink({ client.nativeRequest(targets, "POST", body) }) {
            deviceId = requireNotNull(NativePairing.link(app, client, explicit = false)) { ADD_PHONE_AGAIN }
        }
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
        try { manager.notify(invite.key, 2, NotificationCompat.Builder(app, alertingChannel(manager, "hermes-messages")).setSmallIcon(R.drawable.ic_beacon_notification)
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
                        require(deviceAuthorized(binding)) { ADD_PHONE_AGAIN }
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
    override fun onMessageReceived(message: RemoteMessage) {
        val preview = receivePush(this, message.data, null, deferPreview = true)
        // A high-priority push may start a foreground service, so it also brings Keep connected back after Android stopped it.
        runCatching { KeepConnectedService.rearmFromPush(this) }
        // The real text now, from this thread; the job is the fallback when the fetch fails or takes too long.
        if (preview != null && !runBlocking { refreshNotificationPreviewInline(this@BeaconMessagingService, preview.source, preview.event, preview.conversation, preview.kind) }) {
            NotificationPreviewJob.enqueue(this, preview.source, preview.event, preview.conversation, preview.kind)
        }
    }
    override fun onDeletedMessages() {
        // Firebase dropped messages (over 100 stored, or past their lifetime). Keep connected catches up from its saved cursor; without it they are gone, so say so.
        if (KeepConnectedService.isEnabled(this)) runCatching { KeepConnectedService.rearmFromPush(this) } else DeliveryLog.recordDropped(this)
    }
    companion object {
        const val INCOMING_CHANNEL = "xara-incoming-call-v2"
    }
}
/** Same entry point for FCM and isolated native delivery checks. */
fun receivePush(context: Context, data: Map<String, String>) { receivePush(context, data, null, deferPreview = false) }

internal fun receiveKeepConnectedCall(context: Context, data: Map<String, String>, actionToken: String) {
    require(validOpaque(actionToken)) { "Invalid local call action token" }
    receivePush(context, data, actionToken, deferPreview = false)
}

/** The real text of a posted alert, still to be fetched. */
internal data class PreviewRequest(val source: String, val event: String, val conversation: String, val kind: String)

internal const val ALERT_GROUP = "beacon-alerts"
internal const val QUIET_ALERT_EXTRA = "beacon_quiet"
private const val QUIET_SUMMARY_TAG = "beacon-quiet-summary"
private const val QUIET_SUMMARY_ID = 5
/** An alert older than this when it reaches the phone is history, not news. */
internal const val OLD_ALERT_MILLIS = 120_000L
/** More than this many loud alerts inside [BURST_WINDOW_MILLIS] and the rest arrive quietly. */
internal const val BURST_ALERTS = 3
internal const val BURST_WINDOW_MILLIS = 10_000L

/** C-A `created_at` (whole epoch seconds) as milliseconds, never later than [now]; a missing or invalid value means the alert is new. */
internal fun alertTimeMillis(createdAt: String?, now: Long): Long {
    val seconds = createdAt?.toLongOrNull()?.takeIf { it > 0 } ?: return now
    return minOf(runCatching { Math.multiplyExact(seconds, 1_000L) }.getOrDefault(now), now)
}

/**
 * Whether an alert shows without sound or a heads-up. Questions and approvals keep alerting while they can still be answered
 * (an unknown expiry counts as open); everything else goes quiet when it is old or part of a burst.
 */
internal fun arrivesQuietly(kind: String, alertMillis: Long, expiresAtSeconds: Long?, now: Long, loudRecently: Int): Boolean = when (kind) {
    "approval", "clarify" -> expiresAtSeconds != null && expiresAtSeconds > 0 && expiresAtSeconds * 1_000L <= now
    else -> now - alertMillis > OLD_ALERT_MILLIS || loudRecently >= BURST_ALERTS
}

/** How many alerts made noise lately, so a burst can be told from a single alert. */
internal object AlertBurst {
    private val loud = ArrayDeque<Long>()
    @Synchronized fun recent(now: Long): Int { while (loud.isNotEmpty() && now - loud.first() > BURST_WINDOW_MILLIS) loud.removeFirst(); return loud.size }
    @Synchronized fun note(now: Long) { loud.addLast(now) }
}

/** The quiet alerts showing, with the one just posted counted once (it may not be listed yet); one summary groups two or more of them. */
internal fun quietAlertCount(active: Array<android.service.notification.StatusBarNotification>, justPosted: String): Int =
    1 + active.count { it.id == 1 && it.tag != justPosted && it.notification.group == ALERT_GROUP && it.notification.extras.getBoolean(QUIET_ALERT_EXTRA) }

/** Services have no Compose strings: the line comes from the same catalog, in the language of the phone. */
internal fun serviceText(context: Context, english: String): String {
    val language = dev.hermes.android.ui.nativeLanguageFor(java.util.Locale.getDefault())
    if (language == "en") return english
    val strings = runCatching { (context.applicationContext as BeaconApplication).readTranslations().optJSONObject(language) }.getOrNull() ?: return english
    return strings.optString(dev.hermes.android.ui.normalized(english)).ifBlank { english }
}

private fun postQuietSummary(context: Context, manager: NotificationManager, justPosted: String) {
    val count = quietAlertCount(manager.activeNotifications, justPosted)
    if (count < 2) { manager.cancel(QUIET_SUMMARY_TAG, QUIET_SUMMARY_ID); return }
    val open = PendingIntent.getActivity(context, 5, Intent(context, BeaconActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val summary = NotificationCompat.Builder(context, alertingChannel(manager, "hermes-messages")).setSmallIcon(R.drawable.ic_beacon_notification)
        .setContentTitle(serviceText(context, "{0} alerts while you were away").replace("{0}", count.toString()))
        .setGroup(ALERT_GROUP).setGroupSummary(true).setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN).setSilent(true)
        .setOnlyAlertOnce(true).setAutoCancel(true).setContentIntent(open).build()
    try { manager.notify(QUIET_SUMMARY_TAG, QUIET_SUMMARY_ID, summary) } catch (_: SecurityException) { /* Notification permission was revoked. */ }
}

/** Android drops a notification while the permission or the app switch is off; an alert it would drop must not be marked as seen. */
private fun alertsPresentable(context: Context): Boolean =
    (android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

/**
 * Shows [data] as an alert. With [deferPreview] the caller fetches the real text from the returned request (inline first, the job as
 * the fallback); otherwise the job is queued here and null is returned.
 */
internal fun receivePush(context: Context, data: Map<String, String>, localActionToken: String?, deferPreview: Boolean): PreviewRequest? {
        val app = context.applicationContext as BeaconApplication
        val source = data["source_ref"].orEmpty()
        val event = data["event_id"].orEmpty()
        if (!validOpaque(source) || !validOpaque(event) || !validOpaque(data["conversation_ref"].orEmpty()) || !app.push.permitsIncoming(source)) return null
        val nativeKind = data["kind"].orEmpty()
        // Hermes is asking to use this phone's calendar, alarms, contacts, calls or messages: the phone reads the request and asks its owner.
        if (nativeKind == "phone_request") { dev.hermes.android.phone.HermesPhoneRequests.onPush(context, source); return null }
        val activity = data["activity_ref"].orEmpty()
        fun fetchPreview(kind: String): PreviewRequest? {
            if (deferPreview) return PreviewRequest(source, event, data["conversation_ref"].orEmpty(), kind)
            NotificationPreviewJob.enqueue(context, source, event, data["conversation_ref"].orEmpty(), kind)
            return null
        }
        if (validOpaque(activity) && (ongoingNativeKind(nativeKind) || nativeKind == "activity_end")) {
            presentNativeActivity(context, source, event, activity, nativeKind, data["expires_at"]?.toLongOrNull() ?: 0)
            if (nativeKind == "activity_end") return null
            DeliveryLog.recordArrival(context, nativeKind, event)
            return fetchPreview(nativeKind)
        }
        if (data["kind"] !in setOf("failure", "completion", "approval", "clarify", "task_result", "test", "incoming_call")) return null
        val incoming = data["kind"] == "incoming_call"
        var invite = if (incoming) IncomingCall(
            source, event, data["expires_at"]?.toLongOrNull() ?: 0,
            actionToken = localActionToken ?: java.util.UUID.randomUUID().toString(),
            conversation = data["conversation_ref"].orEmpty(),
        ) else null
        if (incoming && (invite == null || !invite.valid() || !validOpaque(data["conversation_ref"].orEmpty()) || !app.push.permitsIncoming(source))) return null
        val now = System.currentTimeMillis()
        // An alert Android would drop is not marked seen, so Keep connected can still deliver it (quietly) once alerts can be shown.
        if (!incoming && !alertsPresentable(context)) { DeliveryLog.recordDropped(context, now); return null }
        if (!incoming && !app.push.firstArrival(source, event)) return null
        val ring = invite != null && app.push.saveInvitation(invite!!, requiresLocalKeepConnectedReceipt(localActionToken))
        if (incoming && !ring) return null
        if (ring) invite = app.push.ringingInvitation(source, event)
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return null
        val manager = context.getSystemService(NotificationManager::class.java)
        ensureActivityChannels(manager)
        val activityChannel = if (incoming) incomingCallChannel(manager) else if(nativeKind in setOf("approval","clarify")) promptNotificationChannel(manager) else alertingChannel(manager, notificationChannel(nativeKind))
        val routeKey = "$source\u0000$event".digest()
        val intent = Intent(context, BeaconActivity::class.java).setData(android.net.Uri.parse("hermes://activity/$routeKey")).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("push_source", source).putExtra("push_event", event)
        val pending = PendingIntent.getActivity(context, "$source:$event".hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val content = notificationFallback(data["kind"].orEmpty())
        val public = NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle(content.first).setContentText("Open Beacon to view private activity.").build()
        if (!incoming) {
            // The alert shows when it happened; an old one, or one of a burst, arrives quietly under a single summary.
            val alertMillis = alertTimeMillis(data["created_at"], now)
            val quiet = arrivesQuietly(nativeKind, alertMillis, data["expires_at"]?.toLongOrNull(), now, AlertBurst.recent(now))
            val alert = NotificationCompat.Builder(context, activityChannel).setSmallIcon(R.drawable.ic_beacon_notification).setContentTitle(content.first).setContentText(content.second).setStyle(NotificationCompat.BigTextStyle().bigText(content.second)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public)
                .setWhen(alertMillis).setShowWhen(true).setSilent(quiet || silentPushNotification(nativeKind, app.calls.uiVisible)).setContentIntent(pending).setAutoCancel(true)
            if (quiet) alert.setGroup(ALERT_GROUP).addExtras(android.os.Bundle().apply { putBoolean(QUIET_ALERT_EXTRA, true) })
            try { manager.notify(routeKey, 1, alert.build()) } catch (_: SecurityException) { return null }
            if (quiet) postQuietSummary(context, manager, routeKey) else AlertBurst.note(now)
            DeliveryLog.recordArrival(context, nativeKind, event, now)
        }
        if (invite != null && ring) {
            DeliveryLog.recordArrival(context, nativeKind, event, now)
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
                try { manager.notify(routeKey, 2, fallback) } catch (_: RuntimeException) { return null }
            }
        }
        return fetchPreview(data["kind"].orEmpty())
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
    if (manager.getNotificationChannel("hermes-approvals") == null) {
        manager.createNotificationChannel(NotificationChannel("hermes-approvals", "Beacon approvals", NotificationManager.IMPORTANCE_DEFAULT))
    }
    alertingChannel(manager, "hermes-messages")
    alertingChannel(manager, "hermes-tasks")
    promptNotificationChannel(manager)
}
/**
 * Replies and task updates pop up like a chat message. Android never raises an existing channel's importance, so they move once
 * to a high-importance channel; an owner who set the old channel's importance or sound keeps that one.
 */
fun alertingChannel(manager: NotificationManager, base: String): String {
    val (id, name) = when (base) {
        "hermes-messages" -> "hermes-messages-v2" to "Beacon messages"
        "hermes-tasks" -> "hermes-tasks-v2" to "Beacon tasks"
        else -> return base
    }
    val previous = manager.getNotificationChannel(base)
    if (previous != null && (previous.hasUserSetImportance() || android.os.Build.VERSION.SDK_INT >= 30 && previous.hasUserSetSound())) return base
    if (manager.getNotificationChannel(id) == null) manager.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    if (previous != null) manager.deleteNotificationChannel(base)
    return id
}
/** Upgrade the old default channel once, preserving explicit owner sound/importance choices. */
fun promptNotificationChannel(manager:NotificationManager):String {
    val previous=manager.getNotificationChannel("hermes-approvals")
    if(previous!=null && (previous.hasUserSetImportance() || android.os.Build.VERSION.SDK_INT>=30&&previous.hasUserSetSound())) return previous.id
    val id="hermes-prompts-v2"
    manager.createNotificationChannel(NotificationChannel(id,"Questions and permissions",NotificationManager.IMPORTANCE_HIGH).apply { enableVibration(true) })
    return id
}
