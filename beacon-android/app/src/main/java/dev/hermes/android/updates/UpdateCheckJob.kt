package dev.hermes.android.updates

import android.app.job.JobParameters
import android.app.job.JobService
import dev.hermes.android.BeaconApplication
import kotlinx.coroutines.*

class UpdateCheckJob : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var running: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        running = scope.launch {
            try { (application as BeaconApplication).updates.backgroundCheck() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Network/auth failures leave manual retry available. */ }
            finally { if (isActive) jobFinished(params, false) }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { running?.cancel(); return true }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
