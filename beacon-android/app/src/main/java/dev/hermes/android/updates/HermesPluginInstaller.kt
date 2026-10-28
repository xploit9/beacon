package dev.hermes.android.updates

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.NativeAuthExpiredException
import dev.hermes.android.data.requestNeverSent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The official unpinned GitHub source, the same string the README installer uses; unpinned keeps the in-app plugin update working.
 * A constant on purpose: it is never taken from Hermes, a link or the user.
 */
internal const val BEACON_PLUGIN_SOURCE = "xploit9/beacon/hermes-plugin/beacon"

internal enum class PluginInstallStage { Idle, Installing, Checking, NeedsRestart, Restarting, Done, Failed }

/** Why a step stopped. [NotYet]: Beacon did not answer in time. [GatewayDown]: Hermes did not come back after the restart in time. */
internal enum class PluginInstallFailure { SignIn, Denied, Offline, Unsupported, Busy, Error, NotYet, GatewayDown }

/**
 * [busy]: Hermes is working on a reply or task right now. [failure] with [PluginInstallStage.NeedsRestart]: the restart did not start,
 * and the offer stays.
 */
internal data class PluginInstallUi(
    val stage: PluginInstallStage = PluginInstallStage.Idle,
    val failure: PluginInstallFailure? = null,
    val busy: Boolean = false,
) {
    val working: Boolean get() = stage == PluginInstallStage.Installing || stage == PluginInstallStage.Checking || stage == PluginInstallStage.Restarting
}

/** [running]: the gateway is serving. [busy]: it is serving and working on something. */
internal data class GatewayStatus(val running: Boolean, val busy: Boolean)

/** From the public `/api/status` probe; a Hermes without `gateway_busy` still reports `active_agents`. */
internal fun gatewayStatusOf(status: JSONObject) = GatewayStatus(
    running = status.optBoolean("gateway_running", true) && status.optString("gateway_state") in setOf("running", "degraded"),
    busy = status.optBoolean("gateway_busy", status.optInt("active_agents") > 0),
)

internal interface PluginInstallEndpoint {
    suspend fun install(): JSONObject
    /** Turns on a Beacon that is already on Hermes' disk but off, which install refuses as already there. */
    suspend fun enable(): JSONObject
    /** Beacon's routes answer on this Hermes. */
    suspend fun present(): Boolean
    suspend fun gateway(): GatewayStatus
    suspend fun restartGateway()
}

/** Hermes' own installer takes the owner's tap as the consent for what it adds, so the body carries only the fixed source and `enable`. */
internal fun pluginInstallBody(): JSONObject =
    JSONObject().put("identifier", BEACON_PLUGIN_SOURCE).put("enable", true).put("force", false)

internal class HermesPluginInstaller(private val client: BeaconClient) : PluginInstallEndpoint {
    override suspend fun install(): JSONObject =
        client.nativeRequest("/api/dashboard/agent-plugins/install", "POST", pluginInstallBody())
    override suspend fun enable(): JSONObject = client.nativeRequest("/api/dashboard/agent-plugins/beacon/enable", "POST", JSONObject())
    // "unauthorized" is the plugin answering before this phone is linked, which signing in does next.
    override suspend fun present(): Boolean = client.probeOptionalFeatures().optString("plugin") in setOf("available", "unauthorized")
    override suspend fun gateway(): GatewayStatus = gatewayStatusOf(client.nativeRequest("/api/status"))
    override suspend fun restartGateway() { client.nativeRequest("/api/gateway/restart", "POST", JSONObject()) }
}

internal fun installFailure(error: Exception): PluginInstallFailure = when {
    error is NativeAuthExpiredException -> PluginInstallFailure.SignIn
    error is ApiFailure -> when (error.status) {
        401 -> PluginInstallFailure.SignIn
        403 -> PluginInstallFailure.Denied
        404, 405, 501 -> PluginInstallFailure.Unsupported
        409, 423, 429 -> PluginInstallFailure.Busy
        else -> PluginInstallFailure.Error
    }
    error is java.io.IOException -> PluginInstallFailure.Offline
    else -> PluginInstallFailure.Error
}

private const val PRESENCE_TRIES = 8
private const val PRESENCE_PAUSE_MS = 1500L
private const val RESTART_SETTLE_MS = 3000L
private const val RESTART_TRIES = 15
private const val RESTART_PAUSE_MS = 2000L

private fun failed(failure: PluginInstallFailure) = PluginInstallUi(PluginInstallStage.Failed, failure)

private suspend fun <T> attempt(block: suspend () -> T): T? = try { block() } catch (error: CancellationException) { throw error } catch (_: Exception) { null }

/** Asks until Beacon answers, a few times and no longer: Hermes mounts a new plugin's routes on the first request that reaches them. */
private suspend fun beaconAnswers(endpoint: PluginInstallEndpoint, pause: suspend (Long) -> Unit): Boolean {
    for (tries in 0 until PRESENCE_TRIES) {
        if (tries > 0) pause(PRESENCE_PAUSE_MS)
        if (attempt { endpoint.present() } == true) return true
    }
    return false
}

private suspend fun afterInstall(endpoint: PluginInstallEndpoint, restartFlagged: Boolean, pause: suspend (Long) -> Unit): PluginInstallUi {
    if (!beaconAnswers(endpoint, pause)) return failed(PluginInstallFailure.NotYet)
    if (!restartFlagged) return PluginInstallUi(PluginInstallStage.Done)
    val gateway = attempt { endpoint.gateway() }
    // A gateway that is not running loads Beacon when it starts; only a running one that missed Hermes' reload needs the restart.
    return if (gateway?.running == false) PluginInstallUi(PluginInstallStage.Done) else PluginInstallUi(PluginInstallStage.NeedsRestart, busy = gateway?.busy == true)
}

/**
 * Installs Beacon through Hermes and says how it ends. Done: Beacon answers and Hermes has loaded it. NeedsRestart: Beacon answers but
 * Hermes says its gateway has not loaded it, so the owner is offered the restart. It never restarts anything itself.
 */
internal suspend fun runPluginInstall(endpoint: PluginInstallEndpoint, pause: suspend (Long) -> Unit = { delay(it) }): PluginInstallUi {
    val response = try { endpoint.install() }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) {
        // A reply lost after the request left may still have installed Beacon: look before calling it a failure. The gateway
        // state is unknown then, so it is read as if Hermes had asked for the restart.
        val lostReply = error is java.io.IOException && error !is ApiFailure && error !is NativeAuthExpiredException && !requestNeverSent(error)
        if (lostReply) return afterInstall(endpoint, true, pause)
        // Hermes refuses a Beacon that is already on its disk (installed but off, or placed by the installer): turn that one on.
        if (error is ApiFailure && error.status == 400) return try {
            if (endpoint.enable().optBoolean("ok", true)) afterInstall(endpoint, true, pause) else failed(PluginInstallFailure.Error)
        } catch (again: CancellationException) { throw again } catch (again: Exception) { failed(installFailure(again)) }
        return failed(installFailure(error))
    }
    if (!response.optBoolean("ok")) return failed(PluginInstallFailure.Error)
    val flagged = response.optBoolean("restart_required") || (response.optJSONObject("activation")?.optJSONArray("missing_dependencies")?.length() ?: 0) > 0
    return afterInstall(endpoint, flagged, pause)
}

/** "Try again" after [PluginInstallFailure.NotYet]: only asks again, because installing twice would be refused as already there. */
internal suspend fun runPluginCheck(endpoint: PluginInstallEndpoint, pause: suspend (Long) -> Unit = { delay(it) }): PluginInstallUi =
    if (beaconAnswers(endpoint, pause)) PluginInstallUi(PluginInstallStage.Done) else failed(PluginInstallFailure.NotYet)

private suspend fun gatewayServing(endpoint: PluginInstallEndpoint, settle: Boolean, pause: suspend (Long) -> Unit): PluginInstallUi {
    if (settle) pause(RESTART_SETTLE_MS) // the old gateway may still report itself running
    for (tries in 0 until RESTART_TRIES) {
        if (tries > 0) pause(RESTART_PAUSE_MS)
        if (attempt { endpoint.gateway() }?.running == true) return PluginInstallUi(PluginInstallStage.Done)
    }
    return failed(PluginInstallFailure.GatewayDown)
}

/** The owner agreed to the restart: Hermes starts it in the background, then Done once its gateway serves again. A refused start keeps the offer. */
internal suspend fun runPluginRestart(endpoint: PluginInstallEndpoint, offer: PluginInstallUi, pause: suspend (Long) -> Unit = { delay(it) }): PluginInstallUi {
    try { endpoint.restartGateway() }
    catch (error: CancellationException) { throw error }
    catch (error: Exception) { return offer.copy(stage = PluginInstallStage.NeedsRestart, failure = installFailure(error)) }
    return gatewayServing(endpoint, settle = true, pause)
}

/** "Try again" after [PluginInstallFailure.GatewayDown]: the restart was already started, so only wait for the gateway again. */
internal suspend fun runGatewayCheck(endpoint: PluginInstallEndpoint, pause: suspend (Long) -> Unit = { delay(it) }): PluginInstallUi = gatewayServing(endpoint, settle = false, pause)

internal enum class PluginInstallStep { Install, Check, Gateway, Restart }

/** What "Try again" runs for the state the card shows. */
internal fun retryStep(ui: PluginInstallUi): PluginInstallStep = when {
    ui.stage == PluginInstallStage.NeedsRestart -> PluginInstallStep.Restart
    ui.failure == PluginInstallFailure.NotYet -> PluginInstallStep.Check
    ui.failure == PluginInstallFailure.GatewayDown -> PluginInstallStep.Gateway
    else -> PluginInstallStep.Install
}

/**
 * The one install in progress for the signed-in Hermes. [finished] runs when Beacon is ready, so the app reads the plugin again and
 * every screen drops its missing-plugin card; until then the card that started it keeps showing the steps, even the restart offer.
 */
internal class PluginInstallController(
    private val scope: CoroutineScope,
    private val client: () -> BeaconClient?,
    private val finished: () -> Unit,
    private val showUpdates: () -> Unit,
    private val endpointFor: (BeaconClient) -> PluginInstallEndpoint = { HermesPluginInstaller(it) },
) {
    private val mutable = MutableStateFlow(PluginInstallUi())
    val state: StateFlow<PluginInstallUi> = mutable.asStateFlow()
    private var job: Job? = null

    fun install() = start(PluginInstallStep.Install)
    fun restart() = start(PluginInstallStep.Restart)
    fun retry() = start(retryStep(mutable.value))
    /** A Beacon that is there but too old for this app updates through the update screen, which reviews changes and says when Hermes must restart. */
    fun openUpdates() = showUpdates()

    /** "Not now" at the restart offer, or after a restart Hermes has not come back from: Beacon already answers, so the app reads it again then. */
    fun dismiss() {
        val offered = mutable.value.stage == PluginInstallStage.NeedsRestart || mutable.value.failure == PluginInstallFailure.GatewayDown
        reset()
        if (offered) finished()
    }

    /** A different account or Hermes is signed in: nothing of this install applies to it. */
    fun reset() { job?.cancel(); job = null; mutable.value = PluginInstallUi() }

    private fun start(step: PluginInstallStep) {
        val active = client() ?: return
        if (job?.isActive == true) return
        val before = mutable.value
        val detached = active.detached()
        mutable.value = PluginInstallUi(when (step) {
            PluginInstallStep.Install -> PluginInstallStage.Installing
            PluginInstallStep.Check -> PluginInstallStage.Checking
            PluginInstallStep.Gateway, PluginInstallStep.Restart -> PluginInstallStage.Restarting
        })
        job = scope.launch {
            val result = try {
                val endpoint = endpointFor(detached)
                when (step) {
                    PluginInstallStep.Install -> runPluginInstall(endpoint)
                    PluginInstallStep.Check -> runPluginCheck(endpoint)
                    PluginInstallStep.Gateway -> runGatewayCheck(endpoint)
                    PluginInstallStep.Restart -> runPluginRestart(endpoint, before)
                }
            } finally { detached.closeNative() }
            mutable.value = result
            if (result.stage == PluginInstallStage.Done) finished()
        }
    }
}

/** Set by the ViewModel, like the device-removal hooks: the missing-plugin card reaches the install in progress without every screen passing it down. Null in previews and tests. */
internal object PluginInstallHub {
    @Volatile var controller: PluginInstallController? = null
}
