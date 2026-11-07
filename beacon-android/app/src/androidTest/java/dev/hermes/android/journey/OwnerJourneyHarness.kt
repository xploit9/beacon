package dev.hermes.android.journey

import android.os.Build
import android.util.AtomicFile
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.BeaconActivity
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.BuildConfig
import dev.hermes.android.core.Scope
import dev.hermes.android.data.BeaconClient
import dev.hermes.android.data.cachedScopeFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.util.UUID

/** This guard runs outside the activity rule, so an ordinary instrumentation run cannot open the owner app. */
internal val ownerRuntimeOptIn = TestRule { base: Statement, _: Description ->
    object : Statement() {
        override fun evaluate() {
            val args = InstrumentationRegistry.getArguments()
            assumeTrue(args.getString("beaconOwnerRuntime") == "1" && args.getString("beaconLiveService") == "1")
            val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
            check(BuildConfig.DEBUG && app.packageName.endsWith(".debug")) { "debug_required" }
            val emulator = listOf(Build.FINGERPRINT, Build.MODEL, Build.PRODUCT, Build.HARDWARE)
                .any { it.contains("emulator", true) || it.contains("generic", true) || it.contains("sdk", true) || it.contains("goldfish", true) || it.contains("ranchu", true) }
            check(emulator) { "emulator_required" }
            try { base.evaluate() }
            catch (skip: AssumptionViolatedException) { throw skip }
            catch (error: Throwable) {
                val detail = (error.message ?: "").replace(Regex("https?://\\S+"), "[url]").take(160)
                throw AssertionError("owner_journey_rule_failed: " + error.javaClass.simpleName +
                    (if (detail.isBlank()) "" else ": " + detail))
            }
        }
    }
}

internal class OwnerJourneyHarness(
    private val compose: AndroidComposeTestRule<ActivityScenarioRule<BeaconActivity>, BeaconActivity>,
    private val group: String,
) {
    val app: BeaconApplication = ApplicationProvider.getApplicationContext()
    lateinit var vm: BeaconViewModel
    lateinit var client: BeaconClient
    lateinit var scope: Scope
    private var epoch: Long? = null
    private var admission: String? = null
    val runId: String = UUID.randomUUID().toString()
    private val steps = JSONArray()

    suspend fun connect() {
        compose.runOnIdle { vm = ViewModelProvider(compose.activity)[BeaconViewModel::class.java] }
        await(90_000) { vm.client != null && vm.ui.value.authenticated && vm.ui.value.connected && !vm.ui.value.restoringSession }
        client = checkNotNull(vm.client)
        scope = client.scope.copy(session = "")
        epoch = client.nativeAuthorityVersion()
        admission = client.incidentAdmission()
        check(epoch != null && admission != null && scope.owner.isNotBlank() && scope.nativeProvider.isNotBlank())
        check(app.auth.read(scope.origin).cachedScopeFor(scope.origin, replacingAccount = false) == scope)
        val identity = withTimeout(15_000) { client.nativeRequest("/api/auth/me") }
        check(identity.optString("user_id") == scope.owner && identity.optString("provider") == scope.nativeProvider)
        current()
        check(!app.calls.state.value.active && app.calls.service == null)
        mark("O-02", "verified_connected", "passed")
    }

    /** All profile-scoped writes require a separately created, coordinator-selected QA profile. */
    fun qa(healing: Boolean = false) {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("beaconQaProfile").orEmpty()
        val allowed = requested.matches(Regex("qa-[0-9a-f]{32}")) && requested == scope.profile
        if (!allowed) mark("X-01", "qa_profile_required", "needs_human")
        assumeTrue(allowed)
        if (healing) {
            val optedIn = args.getString("beaconHealingQa") == "1"
            if (!optedIn) mark("X-02", "healing_qa_opt_in_required", "needs_human")
            assumeTrue(optedIn)
        }
        current()
        val unsettled = vm.chat.value.queued.count { it.state !in setOf("sent", "settled") }
        if(vm.chat.value.streamId.isNotBlank()) mark("X-04", "qa_stream_active", "not_run")
        if(vm.chat.value.uncertain.isNotBlank()) mark("X-04", "qa_send_unconfirmed", "not_run")
        if(unsettled>0) mark("X-04", "qa_queue_unsettled", "not_run", unsettled)
        check(vm.chat.value.streamId.isBlank() && vm.chat.value.uncertain.isBlank() && unsettled==0)
    }

    fun current() {
        check(vm.client === client && client.scope.copy(session = "") == scope &&
            client.nativeAuthorityVersion() == epoch && client.incidentAdmission() == admission &&
            vm.ui.value.authenticated && vm.ui.value.connected)
    }

    fun ownsJournalItem(item: JSONObject): Boolean {
        val binding = item.optJSONObject("binding") ?: return false
        return binding.optString("origin") == scope.origin && binding.optString("owner") == scope.owner &&
            binding.optString("profile") == scope.profile && binding.optString("auth_provider") == scope.nativeProvider &&
            binding.optString("admission") == admission
    }

    suspend fun await(timeout: Long = 15_000, ready: suspend () -> Boolean) {
        withTimeout(timeout) { while (!ready()) delay(100) }
    }

    fun mark(id: String, step: String, status: String, count: Int? = null, qaId: String? = null) {
        require(id.matches(Regex("[A-Z]-[0-9]{2}")))
        require(step.matches(Regex("[a-z0-9_]{1,48}")) && status in setOf("passed", "failed", "needs_human", "not_run"))
        require(qaId == null || qaId.matches(Regex("[0-9a-f]{32}|[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")))
        steps.put(JSONObject().put("journey", id).put("step", step).put("status", status).apply {
            count?.let { put("count", it) }; qaId?.let { put("qa_id", it) }
        })
        val file = AtomicFile(File(app.filesDir, "owner-journey-$group.json"))
        val document = JSONObject().put("run", runId).put("group", group).put("app_version_code", BuildConfig.VERSION_CODE)
            .put("app_version", BuildConfig.VERSION_NAME).put("steps", steps)
        val output = file.startWrite()
        try { output.write(document.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
    }

    suspend fun checked(block: suspend OwnerJourneyHarness.() -> Unit) {
        try { connect(); block() }
        catch (skip: AssumptionViolatedException) { throw skip }
        catch (error: Throwable) {
            val frame=error.stackTrace.firstOrNull { it.className.startsWith("dev.hermes.android.journey.") }
            val kind=when(error) { is kotlinx.coroutines.TimeoutCancellationException -> "timeout"; is AssertionError -> "assertion"; is IllegalStateException -> "state"; else -> "other" }
            runCatching { mark("X-04", "failure_$kind", "failed", frame?.lineNumber?.takeIf { it>=0 }) }
            frame?.lineNumber?.takeIf { it>0 }?.let { line -> runCatching { mark("X-04", "failure_line_$line", "failed") } }
            val type=error.javaClass.simpleName.lowercase().replace(Regex("[^a-z0-9_]"), "").take(32)
            if(type.isNotBlank()) runCatching { mark("X-04", "failure_type_$type", "failed") }
            runCatching { mark("X-04", "test_failed", "failed") }
            // A Compose assertion may contain a full semantics tree. Never export its message or cause.
            throw AssertionError("owner_journey_${group}_failed")
        }
    }
}
