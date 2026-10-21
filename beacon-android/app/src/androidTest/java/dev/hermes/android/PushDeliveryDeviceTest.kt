package dev.hermes.android

import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.firebase.messaging.FirebaseMessaging
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/** Opt-in setup only. The parent sends a synthetic notification to this debug emulator alone. */
class PushDeliveryDeviceTest {
    @Test(timeout=120_000) fun registerOwnedDebugDeviceForBackgroundDelivery()=runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("beaconPushLive")=="1")
        val app=ApplicationProvider.getApplicationContext<BeaconApplication>()
        require(app.packageName.endsWith(".debug") && android.os.Build.HARDWARE in setOf("ranchu","goldfish"))
        require(!app.calls.state.value.active)
        val saved=JSONObject(File(app.filesDir,"live-auth.json").readText(Charsets.UTF_8).removePrefix("\uFEFF"))
        val client=BeaconClient(AuthContext(BuildConfig.DEFAULT_ORIGIN,emptyList()),app.auth)
        try {
            client.login(saved.getString("password"),username=saved.getString("username"))
            val target=app.push.register(client)
            val row=client.nativeRequest("/api/plugins/beacon/mobile/notifications/targets").rows("targets").single { it.string("target_id")==target }
            val source=row.getString("source_ref")
            assertTrue(app.push.permitsIncoming(source))
            val token=withContext(Dispatchers.IO) { Tasks.await(FirebaseMessaging.getInstance().token,30,TimeUnit.SECONDS) }
            // Private fixture removed by the parent immediately after its bounded delivery probes.
            File(app.filesDir,"push-delivery-private.json").writeText(json("token" to token,"source_ref" to source,"target" to target,"owner" to client.scope.owner,"profile" to client.scope.profile).toString(),Charsets.UTF_8)
        } finally { client.closeNative() }
    }
}
