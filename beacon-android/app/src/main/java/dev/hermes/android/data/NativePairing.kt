package dev.hermes.android.data

import android.content.Context
import android.os.Build
import dev.hermes.android.core.Scope
import dev.hermes.android.core.digest
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.core.trustedOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.Locale

private const val PAIRING_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val PAIRING_CODE_CHARS = "A-Z0-9"
private val pairingCodePattern = Regex("(?i)[$PAIRING_CODE_CHARS]{3}-?[$PAIRING_CODE_CHARS]{3}")
private val pairingCodeLinePattern = Regex("(?im)(?:pairing\\s+)?code\\s*[:=]\\s*([$PAIRING_CODE_CHARS]{3}-?[$PAIRING_CODE_CHARS]{3})(?![A-Z0-9])")
private val dashboardUrlPattern = Regex("(?im)^\\s*(?:dashboard(?:\\s+address)?|address)\\s*[:=]\\s*(https?://[^\\s<>\\\"']+)")
private val urlPattern = Regex("(?i)https?://[^\\s<>\\\"']+")

internal data class PairingCodeBlock(val address: String, val code: String) {
    override fun toString() = "PairingCodeBlock(address=$address, code=<redacted>)"
}

internal data class PairingGrant(
    val deviceId: String,
    val linkTicket: String,
    val dashboardUrl: String,
    val displayName: String,
) {
    override fun toString() = "PairingGrant(deviceId=$deviceId, dashboardUrl=$dashboardUrl, displayName=$displayName, linkTicket=<redacted>)"
}

/** What setup confirms before sign-in: the dashboard and its code. Hermes answers plugin routes only after sign-in, so the code is redeemed then. */
internal data class PendingPairing(val dashboardUrl: String, val displayName: String, val code: String) {
    override fun toString() = "PendingPairing(dashboardUrl=$dashboardUrl, displayName=$displayName, code=<redacted>)"
}

internal data class PairedDevice(val id: String, val name: String, val lastSeen: String)

internal class PairingFailure(message: String, cause: Throwable? = null) : IOException(message, cause)
internal class DeviceRemovedFailure : IOException("This phone was removed from Beacon on your PC.")

internal object NativePairing {
    private const val PAIRING_PREFIX = "/api/plugins/beacon/pairing"
    private const val REDEEM_PATH = "$PAIRING_PREFIX/redeem"
    private const val LINK_PATH = "/api/plugins/beacon/devices/link"
    private const val PLUGIN_PREFIX = "/api/plugins/beacon/"
    @Volatile private var store: android.content.SharedPreferences? = null

    /** Lets every plugin request name this phone, so Hermes updates "last seen" and can refuse a removed phone. */
    fun attach(context: Context) { store = context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE) }

    /** The linked device ID to send with [path], or null: pairing and the link itself never carry one. */
    internal fun deviceHeader(path: String, scope: Scope): String? =
        if (!path.startsWith(PLUGIN_PREFIX) || path.startsWith(PAIRING_PREFIX) || path == LINK_PATH) null
        else store?.getString(accountKey(scope), null)?.takeIf(deviceIdPattern::matches)
    private const val DEVICES_PATH = "/api/plugins/beacon/devices"
    private const val MAX_DEVICE_RESPONSE = 1024 * 1024L
    private val deviceIdPattern = Regex("[A-Za-z0-9_-]{1,128}")

    fun prepare(origin: String, code: String): PendingPairing {
        val url = trustedOrigin(origin)
        return PendingPairing(url, runCatching { java.net.URI(url).host }.getOrNull().orEmpty().ifBlank { "Hermes" }, normalizePairingCode(code))
    }

    internal suspend fun redeem(client: BeaconClient, code: String): PairingGrant {
        val normalized = normalizePairingCode(code)
        val result = try {
            client.nativeRequest(
                REDEEM_PATH,
                "POST",
                json("code" to normalized, "device_name" to deviceName()),
            )
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ApiFailure) {
            val message = when (error.status) {
                400 -> "That code didn't work. Make a new one on your PC."
                429 -> "Too many attempts. Wait a moment, then try a new code from your PC."
                else -> "Couldn't check that code. Check the address and try again."
            }
            throw PairingFailure(message, error)
        } catch (error: Exception) {
            throw PairingFailure("Couldn't reach Hermes. Check the address and try again.", error)
        }
        val dashboardUrl = try { trustedOrigin(result.string("url")) }
        catch (error: Exception) { throw PairingFailure("That code didn't include a valid Hermes address.", error) }
        if (dashboardUrl != client.auth.origin) throw PairingFailure("That code belongs to a different Hermes dashboard.")
        val deviceId = result.string("device_id").takeIf(deviceIdPattern::matches)
            ?: throw PairingFailure("That code didn't return a valid device. Make a new one on your PC.")
        val ticket = result.string("link_ticket").takeIf { it.isNotBlank() && it.length <= 4096 && it.none(Char::isISOControl) }
            ?: throw PairingFailure("That code didn't work. Make a new one on your PC.")
        val displayName = result.string("name").filterNot(Char::isISOControl).take(128).ifBlank { "Hermes" }
        return PairingGrant(deviceId, ticket, dashboardUrl, displayName)
    }

    suspend fun link(context: Context, client: BeaconClient, grant: PairingGrant) {
        require(client.auth.backend == "hermes-dashboard" && client.auth.owner.isNotBlank()) {
            "Sign in with Hermes before linking this phone."
        }
        require(client.auth.origin == grant.dashboardUrl) { "Sign in with the Hermes dashboard shown before linking this phone." }
        require(grant.deviceId.matches(deviceIdPattern)) { "Invalid device ID" }
        client.nativeRequest(LINK_PATH, "POST", json("device_id" to grant.deviceId, "link_ticket" to grant.linkTicket))
        withContext(Dispatchers.IO) {
            val saved = context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
                .edit().putString(accountKey(client.scope), grant.deviceId).commit()
            if (!saved) throw PairingFailure("This phone linked, but Beacon couldn't save its device ID.")
        }
    }

    suspend fun devices(client: BeaconClient): List<PairedDevice> = withContext(Dispatchers.IO) {
        val request = client.request(DEVICES_PATH, mapOf("beacon_profile" to client.auth.profile)).build()
        val call = client.http.newCall(request)
        call.timeout().timeout(20, TimeUnit.SECONDS)
        try {
            call.execute().use(::readDevices)
        } finally {
            call.cancel()
        }
    }

    suspend fun remove(client: BeaconClient, deviceId: String) {
        require(deviceId.matches(deviceIdPattern)) { "Invalid device ID" }
        client.nativeRequest("$DEVICES_PATH/$deviceId", "DELETE")
    }

    fun deviceId(context: Context, scope: Scope): String? = context.applicationContext
        .getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
        .getString(accountKey(scope), null)
        ?.takeIf(deviceIdPattern::matches)

    fun clearDeviceId(context: Context, scope: Scope) {
        context.applicationContext.getSharedPreferences("native-pairing", Context.MODE_PRIVATE)
            .edit().remove(accountKey(scope)).apply()
    }

    private fun accountKey(scope: Scope) = "device:${"${scope.origin}\u0000${scope.owner}".digest()}"

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

internal fun normalizePairingCode(value: String): String {
    val normalized = value.trim().uppercase(Locale.ROOT)
    require(pairingCodePattern.matches(normalized)) { "Enter the six-character code shown on your PC." }
    return normalized.replace("-", "")
}

internal fun formatPairingCodeInput(value: String): String {
    val code = value.uppercase(Locale.ROOT).filter { it in PAIRING_CODE_ALPHABET }.take(6)
    return if (code.length > 3) "${code.take(3)}-${code.drop(3)}" else code
}

internal fun parsePairingBlock(value: String): PairingCodeBlock? {
    val code = pairingCodeLinePattern.find(value)?.groupValues?.getOrNull(1) ?: return null
    val rawAddress = dashboardUrlPattern.find(value)?.groupValues?.getOrNull(1)
        ?: urlPattern.find(value)?.value
        ?: return null
    val address = rawAddress.trimEnd { it in ",.;)]}>\"'" }
    return runCatching { PairingCodeBlock(address, normalizePairingCode(code)) }.getOrNull()
}
