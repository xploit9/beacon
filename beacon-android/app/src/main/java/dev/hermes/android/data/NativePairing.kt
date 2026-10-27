package dev.hermes.android.data

import android.content.Context
import android.os.Build
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

internal data class PairedDevice(val id: String, val name: String, val lastSeen: String)

/** Device ids and names only; credentials never reach this snapshot. */
internal const val PAIRED_DEVICES_KEY = "paired-devices"
internal fun pairedDevicesJson(devices: List<PairedDevice>): org.json.JSONObject =
    json("devices" to org.json.JSONArray(devices.map { json("id" to it.id, "name" to it.name, "last_seen" to it.lastSeen) }))
internal fun pairedDevicesOf(snapshot: org.json.JSONObject?): List<PairedDevice>? =
    snapshot?.takeIf { it.has("devices") }?.rows("devices")?.map { PairedDevice(it.string("id"), it.string("name"), it.string("last_seen")) }

internal class PairingFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class DeviceRemovedFailure : IOException("This phone was removed from Beacon on your PC.")

/** Hermes names this phone's record; the first link creates it and every later one refreshes it. */
internal fun deviceLinkBody(name: String, deviceId: String?): JSONObject =
    json("device_name" to name).also { body -> deviceId?.let { body.put("device_id", it) } }

internal object NativePairing {
    private const val PLUGIN_PREFIX = "/api/plugins/beacon/"
    private const val LINK_PATH = "/api/plugins/beacon/devices/link"
    @Volatile private var store: android.content.SharedPreferences? = null
    private val linkLock = Mutex()

    /** Lets every plugin request name this phone, so Hermes updates "last seen" and can refuse a removed phone. */
    fun attach(context: Context) { store = context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE) }

    /** The linked device ID to send with [path], or null: the link itself never carries one as a header. */
    internal fun deviceHeader(path: String, scope: Scope): String? =
        if (scope.nativeProvider.isBlank() || !path.startsWith(PLUGIN_PREFIX) || path == LINK_PATH) null
        else store?.let { linkedDeviceId(it, scope) }
    private const val DEVICES_PATH = "/api/plugins/beacon/devices"
    private const val MAX_DEVICE_RESPONSE = 1024 * 1024L
    private val deviceIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

    /**
     * Links this phone to the signed-in account and keeps the one id Hermes gives it for that account.
     * An [explicit] link (a sign-in, or "Add this phone") also re-adds a phone that was removed on the PC; an automatic one
     * leaves a removed phone alone and returns null.
     */
    suspend fun link(context: Context, client: BeaconClient, explicit: Boolean = true): String? = linkLock.withLock {
        require(client.auth.backend == "hermes-dashboard" && client.auth.owner.isNotBlank() && client.scope.nativeProvider.isNotBlank()) {
            "Sign in with Hermes before linking this phone."
        }
        val prefs = context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
        val unlinkedKey = unlinkedKey(client.scope)
        val unlinked = prefs.getBoolean(unlinkedKey, false)
        if (unlinked && !explicit) return@withLock null
        val known = deviceId(context, client.scope)
        val result = client.nativeRequest(LINK_PATH, "POST", deviceLinkBody(deviceName(), known))
        val id = result.string("device_id").takeIf(deviceIdPattern::matches)
            ?: throw PairingFailure("Hermes didn't return a valid device for this phone.")
        if (id != known || unlinked) withContext(Dispatchers.IO) {
            val saved = prefs.edit().putString(accountKey(client.scope), id).remove(unlinkedKey).commit()
            if (!saved) throw PairingFailure("This phone linked, but Beacon couldn't save its device ID.")
        }
        id
    }

    suspend fun devices(client: BeaconClient): List<PairedDevice> {
        val request = client.request(DEVICES_PATH, mapOf("beacon_profile" to client.auth.profile)).build()
        return client.nativeResponse(request, 20_000) { readDevices(it) }
    }

    suspend fun remove(client: BeaconClient, deviceId: String) {
        require(deviceId.matches(deviceIdPattern)) { "Invalid device ID" }
        client.nativeRequest("$DEVICES_PATH/$deviceId", "DELETE")
    }

    fun deviceId(context: Context, scope: Scope): String? =
        linkedDeviceId(context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE), scope)

    private fun linkedDeviceId(prefs: android.content.SharedPreferences, scope: Scope): String? =
        storedDeviceId(scope, { prefs.getString(it, null) }) { key, id -> prefs.edit().putString(key, id).apply() }

    /** 1.1.1 kept the id under the provider-less key; copy it forward once so an update keeps this phone linked. */
    internal fun storedDeviceId(scope: Scope, read: (String) -> String?, write: (String, String) -> Unit): String? {
        if (scope.nativeProvider.isBlank()) return null
        val key = accountKey(scope)
        read(key)?.let { return it.takeIf(deviceIdPattern::matches) }
        return read(legacyAccountKey(scope))?.takeIf(deviceIdPattern::matches)?.also { write(key, it) }
    }

    /** This phone was removed on the PC. Dropping the 1.1.1 key too keeps it from being linked again by the copy above, and the mark stops an automatic link. */
    fun clearDeviceId(context: Context, scope: Scope) {
        if (scope.nativeProvider.isBlank()) return
        context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
            .edit().remove(accountKey(scope)).remove(legacyAccountKey(scope)).putBoolean(unlinkedKey(scope), true).apply()
    }

    private fun unlinkedKey(scope: Scope) = "unlinked:${accountKey(scope).removePrefix("device:")}"

    internal fun accountKey(scope: Scope) ="device:${if (scope.nativeProvider.isEmpty()) "${scope.origin}\u0000${scope.owner}".digest() else scope.copy(profile = "", session = "").key}"
    private fun legacyAccountKey(scope: Scope) = accountKey(scope.copy(nativeProvider = ""))

    private fun deviceName() = Build.MODEL.filterNot(Char::isISOControl).take(64).ifBlank { "Android phone" }

    private fun readDevices(response: Response): List<PairedDevice> {
        val raw = response.peekBody(MAX_DEVICE_RESPONSE + 1).string()
        require(raw.toByteArray(Charsets.UTF_8).size.toLong() <= MAX_DEVICE_RESPONSE) { "Hermes returned too many devices." }
        val result = JSONObject(raw)
        if (!response.isSuccessful) {
            val code = listOf("code", "error", "detail").map { result.string(it) }.firstOrNull { it == "device_removed" }
            if (response.code == 403 && code == "device_removed") throw DeviceRemovedFailure()
            throw ApiFailure(response.code, nativeErrorMessage(result, response.code), plugin = true)
        }
        return result.rows("devices").mapNotNull { row ->
            val id = row.string("device_id").takeIf(deviceIdPattern::matches) ?: return@mapNotNull null
            PairedDevice(
                id,
                row.string("name").filterNot(Char::isISOControl).take(128).ifBlank { "Android device" },
                row.string("last_seen").filterNot(Char::isISOControl).take(128),
            )
        }
    }
}
