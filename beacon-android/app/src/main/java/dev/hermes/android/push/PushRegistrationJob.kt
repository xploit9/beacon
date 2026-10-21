package dev.hermes.android.push

import android.app.job.*
import android.content.ComponentName
import android.content.Context
import dev.hermes.android.BeaconApplication
import kotlinx.coroutines.*

/** Durable token repair; no token or credential is copied into scheduler extras. */
class PushRegistrationJob : JobService() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var running:Job?=null
    override fun onStartJob(params:JobParameters):Boolean {
        running?.cancel()
        running=scope.launch {
            var retry=false
            try { withTimeout(45_000) { (application as BeaconApplication).push.refreshRegistrations() } }
            catch(_:TimeoutCancellationException) { retry=true }
            catch(error:CancellationException) { throw error }
            catch(_:Exception) { retry=true }
            finally { if(isActive) jobFinished(params,retry) }
        }
        return true
    }
    override fun onStopJob(params:JobParameters):Boolean { running?.cancel();return true }
    override fun onDestroy() { scope.cancel();super.onDestroy() }
    companion object {
        internal const val ID=7243
        fun schedule(context:Context,replace:Boolean=false) {
            if(!(context.applicationContext as BeaconApplication).firebaseConfigured) return
            val scheduler=context.getSystemService(JobScheduler::class.java) ?: return
            // A newer token is fetched at execution, so don't reset an existing retry's backoff.
            if(!replace&&scheduler.getPendingJob(ID)!=null) return
            val job=JobInfo.Builder(ID,ComponentName(context,PushRegistrationJob::class.java))
                .setPersisted(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setBackoffCriteria(30_000,JobInfo.BACKOFF_POLICY_EXPONENTIAL).build()
            if(runCatching { scheduler.schedule(job)==JobScheduler.RESULT_SUCCESS }.getOrDefault(false).not())
                android.util.Log.w("BeaconPush","Android could not schedule notification registration repair")
        }
    }
}
