package dev.hermes.android

import android.net.Uri
import dev.hermes.android.core.*
import dev.hermes.android.data.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
internal suspend fun loginLiveTest(app: BeaconApplication, origin: String, credentials: JSONObject) {
    val qa = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("beaconQaProfile")
    qa?.let {
        require(it.matches(Regex("qa-[a-f0-9]{32}")) && Uri.parse(origin).scheme == "https" && Uri.parse(origin).port == 8446) { "Use a disposable profile on the demo endpoint" }
    }
    // Explicit test sign-in installs a verified identity; it is not a rotation of an existing account.
    val client = BeaconClient(AuthContext(origin, emptyList()))
    try {
        client.login(credentials.getString("password"), username = credentials.getString("username"))
        qa?.let { profile ->
            client.post("/api/profile/switch", json("name" to profile))
            check(client.auth.profile == profile) { "QA profile selection was not retained" }
        }
        app.auth.write(client.auth)
    } finally { client.closeNative() }
}

internal suspend fun liveTestChatBody(client: BeaconClient, title: String): JSONObject = json("title" to title).apply {
    androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("beaconQaProfile")?.let { profile ->
        require(profile.matches(Regex("qa-[a-f0-9]{32}")) && client.auth.profile == profile)
        val home = client.nativeRpc("config.get", json("key" to "profile")).getString("home").replace('\\', '/').trimEnd('/')
        require(home.endsWith("/hermes-demo/profiles/$profile")) { "Use the demo QA profile home" }
        put("workspace", "$home/workspace")
    }
}

/** Synthetic in-process native protocol fixture. No socket, service, model, mailbox or sensor. */
/** Hermes' create fields (gateway/session_policy.py:15-19) plus what the plugin and gateway take before them: the profile envelope, `title`, `hidden`. */
private val HERMES_CREATE_FIELDS = setOf("request_id", "source", "cwd", "model", "toolsets", "provider", "base_url", "reasoning", "max_turns", "ignore_rules", "api_key", "editor",
    "yolo", "safe_mode", "ignore_user_config", "skills", "checkpoints", "accept_hooks", "pass_session_id", "follow_profile_config", "service_tier", "cwd_explicit", "run_budget",
    "verbose", "row_cwd", "client_env", "profile", "title", "hidden")
/** Set on each request by the client that sends it, so a request that names no profile (a stock ws-ticket) is still recorded with the asking client's own. */
private const val FIXTURE_CLIENT_PROFILE = "X-Fixture-Client-Profile"

internal class NativeExperienceFixture(private val ownerName: String = "synthetic-owner") : WebSocket.Factory {
    private class FixtureRpcFailure(val code: Int, message: String, val field: String? = null) : RuntimeException(message)
    val requests = CopyOnWriteArrayList<JSONObject>()
    val rejectedRequests = CopyOnWriteArrayList<JSONObject>()
    val sessions = ConcurrentHashMap<String, JSONObject>()
    private val unpromptedShells = ConcurrentHashMap<String, JSONObject>()
    val jobs = ConcurrentHashMap<String, JSONObject>()
    val boardTasks = ConcurrentHashMap<String, JSONObject>()
    val projects = ConcurrentHashMap<String, JSONObject>()
    @Volatile var holdNextProjectDetail = false
    val projectDetailCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseProjectDetail = java.util.concurrent.CountDownLatch(1)
    val closedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile var deleteMissingAfterClose = false
    @Volatile var skillEnabled = true
    @Volatile var skillText = "---\nname: travel-planning\ndescription: Plan a trip\n---\nRead verified sources."
    @Volatile var rejectSkillSave = false
    @Volatile var rejectMemorySave = false
    val memoryNotes = ConcurrentHashMap(mapOf("memory" to "Prefers quiet places", "user" to "Synthetic owner"))
    @Volatile var toolEnabled = true
    @Volatile var connectionEnabled = true
    @Volatile var profileModel = "synthetic-model"
    @Volatile var profileSoul = "Check sources before answering."
    @Volatile var profileDescription = "Synthetic assistant"
    @Volatile var profileIsolationBots = false
    @Volatile var profileCatalogUnavailable = false
    val isolationModels = ConcurrentHashMap(mapOf("default" to "default-model", "coder" to "coder-model", "academic" to "academic-model"))
    val isolationSkills = ConcurrentHashMap(mapOf("default" to "Default instructions", "coder" to "Coder instructions", "academic" to "Academic instructions"))
    val isolationSkillEnabled = ConcurrentHashMap(mapOf("default" to true, "coder" to true, "academic" to false))
    val isolationMemory = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>().apply {
        for (profile in listOf("default", "coder", "academic")) put(profile, ConcurrentHashMap(mapOf("memory" to "$profile notes", "user" to "$profile owner")))
    }
    val isolationApprovals = ConcurrentHashMap(mapOf("default" to "manual", "coder" to "smart", "academic" to "manual"))
    val rejectedProfiles = ConcurrentHashMap.newKeySet<String>()
    @Volatile var holdMemoryProfile = ""
    val memoryReadCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseMemoryRead = java.util.concurrent.CountDownLatch(1)
    val memoryReadReturned = java.util.concurrent.CountDownLatch(1)
    @Volatile var reasoningEffort = "medium"
    var reasoningOptions = listOf("low", "medium", "high")
    var persistReasoningInRow = true
    var legacyInventory: JSONArray? = null
    val holdNextReasoningRead = java.util.concurrent.atomic.AtomicBoolean()
    val reasoningReadHeld = java.util.concurrent.CountDownLatch(1)
    val holdReasoningOptions = java.util.concurrent.atomic.AtomicBoolean()
    val reasoningOptionsHeld = java.util.concurrent.CountDownLatch(1)
    val releaseReasoningOptions = java.util.concurrent.CountDownLatch(1)
    val sessionReasoning = ConcurrentHashMap<String, String>()
    @Volatile var updateApplied = false
    @Volatile var voiceProvider = "gemini"
    @Volatile var historyRetentionDays = 0
    val proactiveEnabled = ConcurrentHashMap(mapOf("default" to false, "coder" to false))
    val proactiveDelivery = ConcurrentHashMap(mapOf("default" to "notify", "coder" to "notify"))
    val proactiveCheckMinutes = ConcurrentHashMap(mapOf("default" to 15, "coder" to 15))
    val proactiveQuietStart = ConcurrentHashMap(mapOf("default" to "22:00", "coder" to "22:00"))
    val proactiveQuietEnd = ConcurrentHashMap(mapOf("default" to "07:00", "coder" to "07:00"))
    /** The watch's own model per profile, as the plugin saves it (watch_model / watch_provider, one pair); absent is "Same as assistant". */
    val proactiveWatchModel = ConcurrentHashMap<String, String>()
    val proactiveWatchProvider = ConcurrentHashMap<String, String>()
    val proactiveHealth = ConcurrentHashMap(mapOf(
        "default" to json("state" to "ok", "checked_at" to 1792497600L, "message" to ""),
        "coder" to json("state" to "ok", "checked_at" to 1792497600L, "message" to ""),
    ))
    val proactiveRequests = ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>()
    val proactiveLearned = ConcurrentHashMap<String, CopyOnWriteArrayList<String>>()
    val proactiveFeedbacks = CopyOnWriteArrayList<JSONObject>()
    /** The rules Hermes proposes from earlier ratings (`learned_proposal`), and what the app sent when the owner turned them into rules. */
    val proactiveProposal = ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>()
    val proactiveLearnedRuleBodies = CopyOnWriteArrayList<JSONObject>()
    /** The suggestion Hermes builds for an alert, by notice id; an alert without one gets a sender-based default. */
    val proactiveSuggestions = ConcurrentHashMap<String, JSONObject>()
    val voices = ConcurrentHashMap(mapOf("gemini" to "Kore", "grok" to "ara"))
    val personalities = ConcurrentHashMap(mapOf("gemini" to "balanced", "grok" to "balanced"))
    @Volatile var roomName = "Shared workspace"
    @Volatile var botProfiles = listOf("default", "coder")
    @Volatile var roomMembers = listOf("default", "coder")
    @Volatile var roomDeleted = false
    @Volatile var roomCapabilitiesAvailable = true
    private fun roomCapabilities() = if (roomCapabilitiesAvailable)
        json("rename" to true, "delete" to true, "approval" to true, "change_members" to false, "retry" to false) else json()
    @Volatile var rejectRoomSend = false
    val roomEvents = CopyOnWriteArrayList<JSONObject>()
    val roomActions = CopyOnWriteArrayList<JSONObject>()
    @Volatile var callHistoryResponse = json("calls" to JSONArray(), "next" to "")
    @Volatile var callHistoryRouteMissing = false
    @Volatile var proactiveFeedbackStatusCode = 200
    @Volatile var proactiveRouteMissing = false
    @Volatile var proactiveStatusCode = 200
    @Volatile var proactiveSaveStatusCode = 200
    // The sources this Hermes lets the watch read (null: an older plugin, which sends plain labels only) and the ones each profile chose (none: all).
    @Volatile var proactiveSourceNames: List<String>? = null
    val proactiveChosenSources = ConcurrentHashMap<String, List<String>>()
    val workspaceFiles = ConcurrentHashMap<String, String>()
    val downloads = ConcurrentHashMap<String, ByteArray>()
    val fileRequests = CopyOnWriteArrayList<String>()
    val holdNextFileList = java.util.concurrent.atomic.AtomicBoolean(false)
    val fileListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseFileList = java.util.concurrent.CountDownLatch(1)
    /** While set, the next read of the Proactive settings is recorded at once but its answer is held until [releaseProactiveRead]: the screen between Hermes applying a change and the app reading the list again. */
    val holdNextProactiveRead = java.util.concurrent.atomic.AtomicBoolean(false)
    val proactiveReadCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseProactiveRead = java.util.concurrent.CountDownLatch(1)
    val holdNextToolOperation = java.util.concurrent.atomic.AtomicBoolean(false)
    val toolOperationCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseToolOperation = java.util.concurrent.CountDownLatch(1)
    val holdNextRoomRead = java.util.concurrent.atomic.AtomicBoolean(false)
    val roomReadCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseRoomRead = java.util.concurrent.CountDownLatch(1)
    val holdNextSessionList = java.util.concurrent.atomic.AtomicBoolean(false)
    val sessionListQueries = CopyOnWriteArrayList<JSONObject>()
    val sessionListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseSessionList = java.util.concurrent.CountDownLatch(1)
    @Volatile var approvalMode = "manual"
    @Volatile var rejectApprovalWrite = false
    /** Hermes's lazy session.resume can omit info.yolo even after a session-scoped yolo write. */
    @Volatile var omitResumeYolo = false
    @Volatile var rejectPromptResponse = false
    /** S1d: the next prompt.submit is refused with this RPC error (code, message); its socket stays open. */
    @Volatile var failNextPrompt: Pair<Int, String>? = null
    @Volatile var onPromptResponse: (() -> Unit)? = null
    /** Runs once as an answer reaches the socket, before it is recorded or rejected: a case changes the chat's state while the answer is in flight. */
    @Volatile var beforePromptResponse: (() -> Unit)? = null
    @Volatile var commandCatalogFailure = false
    /** How many renames Hermes' unique-title rule refused (on a real canonical Hermes each one is a logged dispatch crash). */
    @Volatile var refusedTitles = 0
    @Volatile var commandDistractors = 0
    /** More built-ins Hermes' catalog lists, as (name, description). */
    @Volatile var extraCommands: List<Pair<String, String>> = emptyList()
    /** Hermes' answer to command.dispatch (legacy gateway); null refuses it as Hermes does for a name it does not know. */
    @Volatile var commandDispatchResult: JSONObject? = null
    private val sequence = AtomicInteger(1)
    private val notificationSequence = AtomicInteger()
    @Volatile var notificationProfiles = listOf("default", "coder")
    @Volatile var profileDisplayNames = emptyMap<String, String>()
    @Volatile var profileSessionIds = emptyMap<String, Set<String>>()
    /** Profile to (status, detail) for a failed list read, as Hermes answers it (sessions.get_sessions). */
    @Volatile var sessionListFailures = emptyMap<String, Pair<Int, String>>()
    @Volatile var incomingNotification: JSONObject? = null
    val notificationWaits = CopyOnWriteArrayList<Long>()
    @Volatile var forceExpiredAccessToken: String? = null
    @Volatile var currentRefreshToken = "synthetic-refresh"
    val refreshTokensUsed = CopyOnWriteArrayList<String>()
    val rejectedRefreshTokens = CopyOnWriteArrayList<String>()
    private val refreshSequence = AtomicInteger()
    /**
     * Hermes saves a new chat at its first message, so a chat the phone made and never sent answers 404 on REST. This makes [id] one of those. The fixture's session.resume puts a shell back
     * into [sessions] (the case after a Turn on, the chat opens again), and the app resumes the chat it shows by itself for a moment after it opens (the approvals shield asks with a lazy
     * resume): a case that needs the chat unsaved at a later step marks it again right before that step. Marking a chat that is already unsaved changes nothing (G3 run F6, #381).
     */
    fun markUnpromptedShell(id: String) {
        if (unpromptedShells.containsKey(id)) return
        unpromptedShells[id] = sessions.remove(id) ?: error("Synthetic session missing")
    }
    fun isUnpromptedShell(id: String) = unpromptedShells.containsKey(id)
    /** The chats Hermes has no saved row for right now, and the stored ids the gateway was asked to resume (newest last): what a failure text says about the fixture's side. */
    fun unpromptedShellIds(): Set<String> = unpromptedShells.keys.toSet()
    fun resumedSessionIds(): List<String> = requests.toList().filter { it.optString("method") == "session.resume" }.map { it.optJSONObject("params")?.optString("session_id").orEmpty() }
    @Volatile var storageUnavailableOnResume: String? = null
    @Volatile var assistantLookup: Pair<Int, JSONObject>? = null
    fun forgetUnpromptedShell(id: String) {
        check(unpromptedShells.remove(id) != null) { "Synthetic unprompted shell missing" }
        // The phone's gateway keeps its last binding and a background read can reopen its socket, so a bound shell would resume without asking Hermes; bind another chat.
        kotlinx.coroutines.runBlocking { client.nativeSession("chat-a") }
    }
    fun emitIncomingCall(profile: String = "default", conversationId: String = "chat-a", expiresInSeconds: Long = 120): JSONObject {
        require(profile in notificationProfiles)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val sequence = notificationSequence.incrementAndGet()
        val source = "source-$profile-${id.take(12)}"
        val event = json(
            "seq" to sequence,
            "event_id" to id,
            "callback_id" to id,
            "owner_id" to scope.owner,
            "profile" to profile,
            "kind" to "incoming_call",
            "state" to "pending",
            "source_ref" to source,
            "conversation_ref" to "conversation-$profile-${id.take(12)}-$conversationId",
            "conversation_id" to conversationId,
            "session_id" to conversationId,
            "expires_at" to (System.currentTimeMillis() / 1000 + expiresInSeconds),
        )
        incomingNotification = event
        return event
    }
    fun emitTaskResult(profile: String = "default", conversationId: String = "chat-a"): JSONObject {
        require(profile in notificationProfiles)
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val sequence = notificationSequence.incrementAndGet()
        val event = json(
            "seq" to sequence,
            "event_id" to id,
            "owner_id" to scope.owner,
            "profile" to profile,
            "kind" to "task_result",
            "source_ref" to "source-$profile-${id.take(12)}",
            "conversation_ref" to "conversation-$profile-${id.take(12)}-$conversationId",
            "conversation_id" to conversationId,
            "session_id" to conversationId,
            "expires_at" to (System.currentTimeMillis() / 1000 + 300),
        )
        incomingNotification = event
        return event
    }
    @Volatile var cronFailure: String? = null
    @Volatile var checkupCapabilities: JSONObject? = null
    @Volatile var checkupCapabilitiesFailure = false
    @Volatile var checkupPostEnabled = false
    @Volatile var historyFailure = false
    @Volatile var projectLookupFailure = false
    /** Chats Hermes has no saved row for yet (an unsent chat): their REST read is a 404, as on real Hermes. */
    val unsavedRows: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** When set, every optional `/api/plugins/beacon/` route answers with this status. */
    @Volatile var pluginStatus: Int? = null
    /**
     * Profiles whose own config does not list the Beacon plugin. Hermes serves every other route for them (sessions, models, the profile list) but refuses the plugin's
     * gateway ticket: the plugin's `canonical_transport.native_policy` runs the gate in that profile's scope and Hermes' `_plugin_api_mount_skip_reason`
     * ("not in plugins.enabled") makes it 403 "Plugin not authorized". Live on the demo's Work profile (probe-demo.txt, 9 Nov).
     */
    @Volatile var pluginOffProfiles: Set<String> = emptySet()
    /**
     * Profiles whose own config turned Beacon off (`plugins.disabled`): the ticket is refused as for [pluginOffProfiles], and the plugin's turn-on leaves them off when the phone asks by
     * itself (`auto`, answered `enabled: false`); only the owner's own Turn on (no `auto`) turns them on (admin_api.enable_plugin_for_profile).
     */
    @Volatile var pluginDisabledProfiles: Set<String> = emptySet()
    /** Chats Hermes refuses to read: their session and message routes answer 403, as for a chat the signed-in owner may no longer open. */
    @Volatile var deniedSessionIds: Set<String> = emptySet()
    /** What the plugin's profile-enable route answers: 200 turns the profile on (or leaves a [pluginDisabledProfiles] one off for `auto`); another status is a route that is missing (404) or refuses. */
    @Volatile var profileEnableStatus = 200
    /** Holds that answer until a test counts the latch down, so the card's "Turning on" state can be seen. */
    @Volatile var profileEnableGate: java.util.concurrent.CountDownLatch? = null
    /** `devices/link` calls and their bodies; a phone is linked by signing in, with no code. */
    val deviceLinkCount = AtomicInteger()
    val deviceLinkBodies = CopyOnWriteArrayList<JSONObject>()
    val pairedDevices = CopyOnWriteArrayList<JSONObject>()
    @Volatile var deviceLinkHadAuthorization = false
    @Volatile var bootstrapPluginStatus: String? = null
    @Volatile var boardFailure = false
    @Volatile var preferredBoard = "default"
    @Volatile var fileText = "# Travel notes\nCheck the weather."
    @Volatile var fileWrites = 0
    @Volatile var holdCreateAcknowledgement = false
    /** The next create is made on the fixture's Hermes but its answer never reaches the phone (the socket fails first). */
    @Volatile var loseNextCreateAnswer = false
    /** Hermes derives a session from a create's request_id, so an exact retry returns it (gateway/session_local.py:333-340). */
    val createdByRequest = ConcurrentHashMap<String, String>()
    /** The fields of the create that made each request_id's session: Hermes refuses a retry that carries other fields (gateway/session_local.py:340-343). */
    val createFieldsByRequest = ConcurrentHashMap<String, JSONObject>()
    /** Request ids whose chat was deleted: Hermes retires a deleted chat's create and refuses that id again with not_found (hermes_state_local.py:51-54). */
    val retiredRequests: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private fun retireRequestsOf(key: String) = createdByRequest.filterValues { it == key }.keys.forEach { createdByRequest.remove(it); retiredRequests.add(it) }
    /** Deletes [key] on Hermes the way the dashboard or another client does: the chat is gone, and so is its create's request id. */
    fun deleteOnHermes(key: String) { sessions.remove(key); retireRequestsOf(key) }
    @Volatile var holdPromptAcknowledgement = false
    /** CHATFIX-121 follow-up 2: only this chat's prompt.submit answers are held (released with [releasePromptAcknowledgement]). */
    @Volatile var holdPromptOf: String? = null
    @Volatile var holdSteerAcknowledgement = false
    @Volatile var steerStatus = "steered"
    @Volatile var steerFailureCode: Int? = null
    @Volatile var canonicalAuthority = false
    /** A canonical chat's pending admissions as Hermes' resume lists them (WEDGE-121: a turn lost in a restart is `unknown`); null lists none. */
    @Volatile var lostPending: JSONArray? = null
    /** What Beacon's read-only lost-turn route answers: chat id to the messages Hermes holds queued behind its lost reply (follow-up 3). */
    @Volatile var lostChats: Map<String, Int> = emptyMap()
    /** Follow-up 4: bot profile to its Bot Chat id, for the route's bots read; the lost admissions are [lostPending]'s. */
    @Volatile var lostBots: Map<String, String> = emptyMap()
    private fun lostUnknown() = JSONArray().apply {
        lostPending?.let { rows -> (0 until rows.length()).map(rows::getJSONObject).filter { it.string("status") == "unknown" }
            .forEach { put(json("admission_id" to it.string("admission_id"), "execution_generation" to it.opt("execution_generation"))) } }
    }
    private fun holdsLostTurn() = lostPending?.let { rows -> (0 until rows.length()).any { rows.getJSONObject(it).string("status") == "unknown" } } == true
    /** The admissions the app let go with prompt.resolve_unknown, in order. */
    val resolvedLost = CopyOnWriteArrayList<String>()
    /** Like Hermes' canonical pending controls: the prompts it waits on, listed in every resume snapshot until answered (rules27). */
    val canonicalOpenPrompts = CopyOnWriteArrayList<JSONObject>()
    /** Each canonical prompt answer Hermes took: prompt id to the choice or answer. */
    val respondedPrompts = CopyOnWriteArrayList<Pair<String, String>>()
    /** Every subscription to a runtime's events (session.events.since), a re-subscribe after a drop included. */
    val eventSubscriptions = AtomicInteger()
    /**
     * CHATFIX-121: Hermes' replay ring dropped every frame up to this sequence (event_replay.is_truncated: last_seen < evicted_through; the ring keeps 512 frames
     * per session). A session.events.since from an older cursor then answers with no frames and snapshot_required (gateway/session_events.py since()). 0 keeps
     * every frame, as before.
     */
    @Volatile var replayEvictedThrough = 0L
    /** The cursor (last_seen) of each session.events.since that answered snapshot_required. */
    val truncatedReplays = CopyOnWriteArrayList<Long>()
    /** CHATFIX-121: the next this many session.resume calls fail with [resumeFailureCode] (403 is Hermes refusing the sign-in); 0 answers every one, as before. */
    val failingResumes = AtomicInteger()
    @Volatile var resumeFailureCode = 503
    /** CHATFIX-121 follow-up 1: a session.resume refusal ([failingResumes]) is held until [releaseHeldRefusals], so it lands after the app has moved on. */
    @Volatile var holdResumeRefusals = false
    private val heldRefusals = CopyOnWriteArrayList<() -> Unit>()
    fun heldRefusalCount() = heldRefusals.size
    fun releaseHeldRefusals() { heldRefusals.toList().also { heldRefusals.clear() }.forEach { it() } }
    /** CHATFIX-121: the sockets the app closed (WebSocket.close or cancel), by the id [nativeSocketMethods] names them with. */
    val closedSockets: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** CHATFIX-121: live events go to the stream's own collector (the socket whose second session.events.since made it one). */
    fun liveRoutedToCollector() = activeNativeSocketId != 0 && activeNativeSocketId == liveCollectorSocketId
    /** Hermes refuses prompt.resolve_unknown (not a stale generation): nothing is let go. */
    @Volatile var refuseLostClose = false
    /** The chats the fixture's plugin was told are threads (a Hermes with session authority cannot store the thread source). */
    val pluginThreadMarks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile var omitCanonicalResumeProfile = false
    @Volatile var omitCanonicalInfoProfile = false
    @Volatile var admissionReceiptStatus = "queued"
    @Volatile var admissionReceiptOutcome = ""
    @Volatile var admissionReceiptRefOverride: String? = null
    @Volatile var admissionReceiptProfileOverride: String? = null
    @Volatile var admissionReceiptIdOverride: String? = null
    @Volatile var holdReceiptAcknowledgement = false
    @Volatile var holdActiveListAcknowledgement = false
    /** The next canonical submission waits behind another turn (Hermes holds it queued) until a test starts it. */
    val queueNextCanonicalSubmission = java.util.concurrent.atomic.AtomicBoolean(false)
    val canonicalAdmissions = ConcurrentHashMap<String, JSONObject>()
    /**
     * CHATQA-121 follow-up 5: like Hermes (hermes_state_runtime.py admit_session_input), a canonical submit is keyed by session and submission id. The same
     * payload is answered with the admission Hermes already holds (its current state, nothing admitted twice); another payload gets admission_conflict.
     */
    private val canonicalSubmissions = ConcurrentHashMap<String, Pair<String, String>>()
    /** Follow-up 5: the canonical submits per submission id (fresh and asked again), so a test counts every ask. */
    val canonicalSubmits = ConcurrentHashMap<String, AtomicInteger>()
    /** Follow-up 5: like Hermes, the person's row is written when the turn starts and carries the submission id in its display metadata (gateway/session_display.py). */
    @Volatile var persistCanonicalUserRows = false
    private val canonicalAdmissionInputs = ConcurrentHashMap<String, Triple<String, String, String>>()
    /** Follow-up 5: the network drops as the next prompt goes out; its socket fails before Hermes reads it, so Hermes never gets it. */
    @Volatile var dropNextPromptUnread = false
    val unreadPrompts = AtomicInteger()
    /** Follow-up 5: Hermes holds another payload under [submission] (asking again with this one gets admission_conflict). */
    fun holdOtherPayloadUnder(submission: String) { canonicalSubmissions.replaceAll { key, value -> if (key.endsWith("|$submission")) "another payload" to value.second else value } }
    /** Follow-up 5: the admissions Hermes holds under [submission]. */
    fun canonicalAdmissionsFor(submission: String): Int = canonicalSubmissions.keys.count { it.endsWith("|$submission") }
    /** Follow-up 5: the person's rows Hermes keeps with [text], in every session. */
    fun userRows(text: String): Int = sessions.values.sumOf { session ->
        session.optJSONArray("messages")?.let { rows -> (0 until rows.length()).count { rows.getJSONObject(it).string("role") == "user" && rows.getJSONObject(it).string("text") == text } } ?: 0
    }
    /** The status each canonical submit was answered with (Hermes answers every fresh submit "queued"). */
    val submitAnswers = CopyOnWriteArrayList<String>()
    /** The admission whose turn runs now: Hermes stamps every frame of that turn with it (session_events.publish). */
    @Volatile var executingAdmission: String? = null
    @Volatile var holdConfigAcknowledgement = false
    @Volatile var holdBranchAcknowledgement = false
    /** Hermes applies archive/unarchive (session.set_hidden) and the answer is held until [releaseCreateAcknowledgement]. */
    @Volatile var holdHiddenAcknowledgement = false
    /** Every saved-message read of this stored session waits for [releaseMessages] (a chat whose history takes a while); [messagesHeld] opens when the first one arrives. */
    @Volatile var holdMessagesOf: String? = null
    val messagesHeld = java.util.concurrent.CountDownLatch(1)
    val releaseMessages = java.util.concurrent.CountDownLatch(1)
    @Volatile var afterNextHistoryPage: (() -> Unit)? = null
    val holdNextHistoryList = java.util.concurrent.atomic.AtomicBoolean(false)
    val historyListCaptured = java.util.concurrent.CountDownLatch(1)
    val releaseHistoryList = java.util.concurrent.CountDownLatch(1)
    /** Like Hermes when another conversation already holds the requested title: the create-time title is dropped. */
    @Volatile var dropCreateTitle = false
    @Volatile var renameFailure: String? = null
    private val delayedCreates = CopyOnWriteArrayList<() -> Unit>()
    @Volatile private var activeNativeListener: WebSocketListener? = null
    @Volatile private var activeNativeSocket: WebSocket? = null
    @Volatile private var activeNativeSocketId = 0
    @Volatile private var activeNativeSessionId: String? = null
    private val serverRequestCapabilities = ConcurrentHashMap<WebSocket, Boolean>()
    private val openNativeServerRequests = ConcurrentHashMap<String, JSONObject>()
    private val nativeServerRequestSequence = AtomicInteger()
    val nativeServerResponses = CopyOnWriteArrayList<JSONObject>()
    private val pendingNativeStreams = ConcurrentHashMap.newKeySet<String>()
    private val nativeSocketSequence = AtomicInteger()
    private val replayReads = ConcurrentHashMap<WebSocket, AtomicInteger>()
    val nativeSocketMethods = CopyOnWriteArrayList<Pair<Int, String>>()
    val liveCollectorSubscriptions = AtomicInteger()
    @Volatile var liveCollectorSocketId = 0
        private set
    private val runtimeStoredSessionIds = ConcurrentHashMap<String, String>()
    private val currentRuntimeSessionIds = ConcurrentHashMap<String, String>()
    val streamSubscriptions = AtomicInteger()
    @Volatile var nativeTurnStatus = "idle"
    val nativeStatusProbes = CopyOnWriteArrayList<String>()
    @Volatile var refuseConnection = false
    /** CHATFIX-121 follow-up 4: a slow phone or a busy emulator: every read of a chat's saved rows (`/api/sessions/...`) answers this much later. */
    @Volatile var sessionReadDelayMs = 0L
    /** CHATFIX-121 follow-up 5: another account signed in on this server; Hermes names it as the signed-in user. Null is the fixture's own account. */
    @Volatile var signedInOwner: String? = null
    val refusedConnections = java.util.concurrent.atomic.AtomicInteger()
    /** Stored id -> runtime id a later `session.resume` of that conversation binds (other conversations keep their own). */
    val resumeRuntimeSessionIds = ConcurrentHashMap<String, String>()
    /** Runtimes Hermes dropped after their socket closed: a session-scoped call naming one answers 4001, as Hermes does for a runtime not in memory. */
    val reapedRuntimes = ConcurrentHashMap.newKeySet<String>()
    /** Hermes reaps an idle detached runtime; the next `session.resume` of the stored conversation mints another. */
    fun reapRuntime(storedSessionId: String) {
        val runtime = currentRuntimeSessionIds[storedSessionId] ?: error("Synthetic runtime missing: $storedSessionId")
        reapedRuntimes += runtime
        resumeRuntimeSessionIds[storedSessionId] = "$runtime-resumed"
    }
    @Volatile var nativeStatusFailure = false
    @Volatile var persistTerminalMessages = false
    @Volatile var terminalOnActiveList: JSONObject? = null
    /** CHATQA-121 follow-up 3: stored ids whose turn Hermes runs although this phone never sent it (the background outbox, another device, a schedule). */
    val unseenTurns = ConcurrentHashMap.newKeySet<String>()
    /** Hermes holds an admitted turn in [stored] that this phone never sent: the inventory lists it with [nativeTurnStatus], and its next event subscription gets emitted events. */
    fun holdUnseenTurn(stored: String) {
        require(sessions.containsKey(stored)) { "Synthetic session missing: $stored" }
        unseenTurns += stored
        pendingNativeStreams += unseenRuntime(stored)
    }
    fun unseenRuntime(stored: String): String = if (canonicalAuthority) stored else currentRuntimeSessionIds[stored] ?: sessions.getValue(stored).string("session_id")
    @Volatile var customTabLaunches = 0
    @Volatile var callbackFailure: Throwable? = null
    private val nativeEvents = CopyOnWriteArrayList<JSONObject>()
    private val nativeEventSequence = AtomicInteger()
    fun releasePromptAcknowledgement() = releaseCreateAcknowledgement()
    /** CHATQA-121 follow-up 4: the sockets whose answers are held, so a test can drop the network while those answers are on their way. */
    private val heldSockets = CopyOnWriteArrayList<Pair<WebSocketListener, WebSocket>>()
    /** The network drops while held answers are on their way: Hermes has the requests (recorded and handled), the answers never reach the phone, and their sockets fail. */
    fun dropHeldAcknowledgements(keepHolding: Boolean = false) {
        val sockets = synchronized(delayedCreates) {
            if (!keepHolding) { holdCreateAcknowledgement = false; holdPromptAcknowledgement = false; holdSteerAcknowledgement = false }
            delayedCreates.clear(); heldSockets.toList().also { heldSockets.clear() }
        }
        sockets.distinctBy { System.identityHashCode(it.second) }.forEach { (listener, socket) -> listener.onFailure(socket, java.io.IOException("Synthetic network drop"), null) }
    }
    fun releaseCreateAcknowledgement() {
        val queued = synchronized(delayedCreates) { heldSockets.clear(); holdCreateAcknowledgement = false; holdPromptAcknowledgement = false; holdSteerAcknowledgement = false; holdReceiptAcknowledgement = false; holdActiveListAcknowledgement = false; holdConfigAcknowledgement = false; holdBranchAcknowledgement = false; holdHiddenAcknowledgement = false; holdVaultListAcknowledgement = false; delayedCreates.toList().also { delayedCreates.clear() } }
        queued.forEach { it() }
    }
    fun setCanonicalAdmission(id: String, status: String, outcome: String = "") {
        require(status in setOf("queued", "started", "unknown", "terminal"))
        val current = requireNotNull(canonicalAdmissions[id])
        canonicalAdmissions[id] = current.copy().put("status", status).put("outcome", outcome.ifBlank { JSONObject.NULL })
        nativeTurnStatus = when (status) { "queued" -> "starting"; "started" -> "working"; "terminal" -> "idle"; else -> "unknown" }
        if (status == "started") executingAdmission = id else if (executingAdmission == id) executingAdmission = null
    }
    /**
     * Like Hermes claiming a queued row (session_authority._stamp_claim): the admission starts, the chat is working, and `message.start` is the first
     * frame stamped with the admission (retained for replay, or pushed live to the attached stream when [live]). The answer that submit gave stays "queued".
     */
    fun startCanonicalTurn(admission: String, live: Boolean = false) {
        val current = requireNotNull(canonicalAdmissions[admission])
        canonicalAdmissions[admission] = current.copy().put("status", "started").put("execution_generation", 7)
        if (persistCanonicalUserRows) canonicalAdmissionInputs.remove(admission)?.let { (session, submission, text) ->
            sessions[session]?.optJSONArray("messages")?.put(json("role" to "user", "text" to text, "row_id" to sequence.incrementAndGet(), "display_metadata" to json("submission_id" to submission)))
        }
        nativeTurnStatus = "working"
        executingAdmission = admission
        if (live) emitNativeEvent("message.start", JSONObject())
        else runCatching { retainNativeEvent(current.getJSONObject("ref").string("session_id"), "message.start", JSONObject()) }
    }
    private fun canonicalProfileId(profile: String) = when (profile) {
        "default" -> "synthetic-authority-alpha"
        "coder" -> "synthetic-authority-beta"
        else -> "synthetic-authority-$profile"
    }
    fun installCustomTabSeam() {
        callbackFailure = null
        NativeAuth.customTabLauncher = { _, authorizeUrl ->
            customTabLaunches += 1
            val state = authorizeUrl.getQueryParameter("state").orEmpty()
            val redirect = Uri.parse(authorizeUrl.getQueryParameter("redirect_uri") ?: error("Missing loopback redirect"))
                .buildUpon().appendQueryParameter("code", "synthetic-code").appendQueryParameter("state", state).build()
            Thread {
                runCatching {
                    client.http.newCall(Request.Builder().url(authorizeUrl.toString()).get().build()).execute().close()
                    val port = redirect.port
                    java.net.Socket("127.0.0.1", port).use { socket ->
                        socket.soTimeout = 5_000
                        val path = redirect.encodedPath + "?" + redirect.encodedQuery
                        socket.getOutputStream().write("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        socket.getOutputStream().flush()
                        socket.getInputStream().bufferedReader().use { reader -> while (reader.readLine() != null) { } }
                    }
                }.onFailure { callbackFailure = it }
            }.apply { name = "beacon-oauth-test-callback"; isDaemon = true; start() }
        }
    }
    fun clearCustomTabSeam() { NativeAuth.customTabLauncher = null }
    fun retainNativeEvent(runtimeSessionId: String, type: String, payload: JSONObject): JSONObject {
        val storedSessionId = runtimeStoredSessionIds[runtimeSessionId] ?: sessions.entries.firstOrNull { it.value.optString("session_id") == runtimeSessionId }?.key
            ?: error("Synthetic runtime session missing: $runtimeSessionId")
        val event = json("session_id" to runtimeSessionId, "seq" to nativeEventSequence.incrementAndGet(), "type" to type, "payload" to payload.copy())
        // Like Hermes, every frame of a running turn carries that turn's execution stamp beside the payload: authority epoch, execution generation and admission
        // (gateway/session_authority._stamp_claim, published by session_events.publish).
        executingAdmission?.let { event.put("admission_id", it).put("execution_generation", 7).put("authority_epoch", 1) }
        if (persistTerminalMessages && type == "message.complete") {
            sessions[storedSessionId]?.optJSONArray("messages")?.put(json("role" to "assistant", "text" to payload.string("text"), "row_id" to "terminal-${event.optInt("seq")}"))
        }
        nativeEvents.add(event)
        // Like Hermes, the stamp is cleared right after the turn's terminal frame (gateway/session_settlement_recovery.py:300-306), so a later idle frame carries none.
        if (type == "message.complete") executingAdmission = null
        return event
    }
    /**
     * Like Hermes when it accepts a prompt: the turn's `message.start` is stamped and kept in the session's replay ring (tui_gateway/prompt_turn.py:1160 `_emit`,
     * server.py:567 `write_json`, event_replay.py:72 `_stamp_event` with the ring append at :106), and `session.events.since` serves the ring (methods_session.py:2309-2323,
     * event_replay.py:189). The app takes the baseline of a later attach from the last retained start (NativeGateway.prepareReplay), so an attach after the turn began finds the
     * events it missed. The start is kept for replay only; live events still go out through [emitNativeEvent], as the tests drive them.
     */
    private fun retainMessageStart(runtimeSessionId: String) {
        nativeEvents.add(json("session_id" to runtimeSessionId, "seq" to nativeEventSequence.incrementAndGet(), "type" to "message.start", "payload" to JSONObject()))
    }
    fun awaitActiveNativeSocket(timeoutMs: Long = 15_000L): Pair<WebSocketListener, WebSocket> {
        require(timeoutMs >= 0)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (true) {
            val listener = activeNativeListener
            val socket = activeNativeSocket
            if (listener != null && socket != null) return listener to socket
            if (System.nanoTime() >= deadline) error("No active native socket for ${activeNativeSessionId ?: "the pending conversation"} after ${timeoutMs}ms")
            Thread.sleep(10)
        }
    }
    // ---- Hermes' native Passwords & Logins, as the contracts define it ----------------------------------------------------------------
    // Methods: tui_gateway/methods_vault.py:42-180. Shapes: tui_gateway/contracts/profiles_vault_complete_foreign_subagents.py:444-566.
    // Store rules: agent/vault_store.py add_item 547-606 (kinds 36, identifier types 38, PAYMENT_FIELDS 48-51, ADDRESS_FIELDS 52-55,
    // REQUIRED_FIELDS 56-57, normalize_origin 140-166, normalize_otp_secret 89-118). Prompts: tui_gateway/contracts/server_requests.py:231-252,
    // gateway/session_vault_prompt.py:26-69. A card fill asks through the ordinary `approval` request (tools/browser_vault_tool.py:622-636,
    // tools/approval_gateway_wait.py:140-165).
    class VaultManager(val name: String, val displayName: String, var enabled: Boolean, var unlocked: Boolean, val installed: Boolean)
    /** profile -> local items (metadata exactly as VaultItemMeta.to_dict, secrets kept apart in [vaultSecrets]). */
    val vaultItems = ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>()
    /** item id -> the secret payload Hermes would have stored (post-validation), so a test can prove nothing extra was sent. */
    val vaultSecrets = ConcurrentHashMap<String, JSONObject>()
    /** profile -> detected password managers (vault.sources rows after `local`). */
    val vaultManagers = ConcurrentHashMap<String, CopyOnWriteArrayList<VaultManager>>()
    /** profile -> items a manager lends while unlocked, keyed by manager name. */
    val vaultManagerItems = ConcurrentHashMap<String, ConcurrentHashMap<String, CopyOnWriteArrayList<JSONObject>>>()
    /** Every vault.* call as received (method, params incl. the stamped `profile`). */
    val vaultCalls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
    /** While set, `vault.list` is recorded at once but its answer is held until [releaseCreateAcknowledgement], so a test can use the Vault page while it is still reading. */
    @Volatile var holdVaultListAcknowledgement = false
    /** false answers like a Hermes older than the native vault: JSON-RPC -32601 unknown method. */
    @Volatile var vaultAvailable = true
    @Volatile var vaultMasterPassword = "synthetic-master"
    private val vaultPromptOpen = ConcurrentHashMap<String, Pair<String, JSONObject>>()
    private val vaultSequence = AtomicInteger()
    private val loginIdentifierTypes = listOf("email", "phone", "username")
    private val paymentFields = listOf("card_number", "cardholder_name", "exp_month", "exp_year", "cvc", "billing_postal_code")
    private val addressFields = listOf("address_line1", "address_line2", "city", "state", "postal_code", "country")
    private val requiredFields = mapOf("payment" to listOf("card_number", "exp_month", "exp_year", "cvc"), "address" to listOf("address_line1", "city", "postal_code", "country"))

    fun seedVaultLogin(profile: String, label: String, origin: String, identifier: String, otp: Boolean = false, backend: String = "local"): String {
        val id = "vault_${vaultSequence.incrementAndGet().toString().padStart(12, '0')}"
        val row = json("id" to id, "kind" to "login", "label" to label, "origin" to origin, "created_at" to "2026-11-08T10:00:00+00:00",
            "identifier" to identifier, "identifier_type" to if (identifier.contains('@')) "email" else "username", "backend" to backend)
        if (otp) row.put("has_otp", true)
        if (backend == "local") vaultItems.computeIfAbsent(profile) { CopyOnWriteArrayList() }.add(row)
        else vaultManagerItems.computeIfAbsent(profile) { ConcurrentHashMap() }.computeIfAbsent(backend) { CopyOnWriteArrayList() }.add(row)
        return id
    }
    fun seedVaultManager(profile: String, name: String, displayName: String, enabled: Boolean = true, unlocked: Boolean = false, installed: Boolean = true) {
        vaultManagers.computeIfAbsent(profile) { CopyOnWriteArrayList() }.add(VaultManager(name, displayName, enabled, unlocked, installed))
    }

    /** Hermes' VaultError texts are what `scrub_secret_from_text(str(e), secret)` returns; the app must not show them. */
    private fun vaultFailure(message: String): Nothing = throw FixtureRpcFailure(5095, message)

    private fun vaultOrigin(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) vaultFailure("origin is required")
        if ("://" !in text) vaultFailure("origin must include a scheme (got '$text')")
        val uri = java.net.URI(text)
        val scheme = (uri.scheme ?: "").lowercase(); val host = (uri.host ?: "").lowercase()
        if (scheme.isEmpty() || host.isEmpty()) vaultFailure("could not parse origin from '$text'")
        val port = uri.port
        return if (port == -1 || port == mapOf("http" to 80, "https" to 443)[scheme]) "$scheme://$host" else "$scheme://$host:$port"
    }

    private fun vaultOtp(value: String): String {
        val text = value.trim()
        if (text.isEmpty()) return ""
        val seed = text.replace(Regex("[\\s-]"), "").uppercase().trimEnd('=')
        if (seed.isEmpty() || seed.any { it !in 'A'..'Z' && it !in '2'..'7' }) vaultFailure("authenticator key must be a base32 secret or an otpauth:// URI")
        return seed
    }

    /** `VaultStore.add_item`: the same checks, in the same order, with the same refusals. */
    private fun vaultAdd(profile: String, params: JSONObject): JSONObject {
        val secret = params.optJSONObject("secret")
        if (secret == null || secret.length() == 0) vaultFailure("secret payload is required")
        val kind = params.string("kind"); val label = params.string("label").trim()
        if (kind !in listOf("login", "payment", "address")) vaultFailure("unknown vault kind '$kind' (expected one of ('login', 'payment', 'address'))")
        if (label.isEmpty()) vaultFailure("label is required")
        val origin = params.opt("origin")?.takeIf { it != JSONObject.NULL && it.toString().isNotEmpty() }?.toString()
        val stored = JSONObject(); var normalized: String? = null; var identifier: String? = null; var identifierType: String? = null
        if (kind == "login") {
            if (origin == null) vaultFailure("origin is required for login items")
            normalized = vaultOrigin(origin)
            identifierType = secret.opt("identifier_type")?.toString()
            if (identifierType !in loginIdentifierTypes) vaultFailure("identifier_type must be one of ${loginIdentifierTypes}")
            identifier = secret.string("identifier").trim()
            if (identifier.isEmpty() || secret.string("password").isEmpty()) vaultFailure("login items require identifier and password")
            val otp = vaultOtp(secret.string("otp_secret"))
            stored.put("password", secret.getString("password")); if (otp.isNotEmpty()) stored.put("otp_secret", otp)
        } else {
            val allowed = if (kind == "payment") paymentFields else addressFields
            for (key in allowed) secret.opt(key)?.toString()?.takeIf { it.isNotBlank() }?.let { stored.put(key, it) }
            val missing = requiredFields.getValue(kind).filter { !stored.has(it) }
            if (missing.isNotEmpty()) vaultFailure("$kind items require ${missing.joinToString(", ")}")
            if (origin != null) normalized = vaultOrigin(origin)
        }
        val id = "vault_${vaultSequence.incrementAndGet().toString().padStart(12, '0')}"
        val row = json("id" to id, "kind" to kind, "label" to label, "origin" to (normalized ?: JSONObject.NULL), "created_at" to "2026-11-08T10:05:00+00:00", "backend" to "local")
        if (identifier != null) row.put("identifier", identifier).put("identifier_type", identifierType)
        if (stored.has("otp_secret")) row.put("has_otp", true)
        vaultItems.computeIfAbsent(profile) { CopyOnWriteArrayList() }.add(row)
        vaultSecrets[id] = stored
        return json("id" to id)
    }

    private fun vaultRpc(method: String, params: JSONObject): JSONObject {
        if (!vaultAvailable) throw FixtureRpcFailure(-32601, "unknown method: $method")
        vaultCalls += method to params.copy()
        val profile = params.string("profile")
        val managers = vaultManagers[profile] ?: CopyOnWriteArrayList()
        return when (method) {
            "vault.list" -> {
                val rows = JSONArray(); (vaultItems[profile] ?: emptyList<JSONObject>()).forEach { rows.put(it.copy()) }
                for (manager in managers) if (manager.enabled && manager.unlocked) vaultManagerItems[profile]?.get(manager.name)?.forEach { rows.put(it.copy()) }
                json("items" to rows)
            }
            "vault.sources" -> {
                val rows = JSONArray().put(json("name" to "local", "display_name" to "Hermes vault", "enabled" to true, "needs_unlock" to false, "unlocked" to true, "installed" to true))
                managers.forEach { rows.put(json("name" to it.name, "display_name" to it.displayName, "enabled" to it.enabled, "needs_unlock" to true, "unlocked" to (it.enabled && it.unlocked), "installed" to it.installed)) }
                json("sources" to rows)
            }
            "vault.source.set" -> {
                val manager = managers.firstOrNull { it.name == params.string("name") } ?: vaultFailure("unknown vault source: ${params.string("name")}")
                manager.enabled = params.optBoolean("enabled"); if (!manager.enabled) manager.unlocked = false
                json("name" to manager.name, "enabled" to manager.enabled)
            }
            "vault.unlock" -> {
                val manager = managers.firstOrNull { it.name == params.string("name") && it.enabled } ?: vaultFailure("${params.string("name")} is not an enabled password manager")
                val password = params.string("password")
                if (password.isEmpty()) vaultFailure("master password is required")
                if (password != vaultMasterPassword) vaultFailure("[ERROR] op: signin failed: invalid master password at cli/main.go:1 [REDACTED]")
                manager.unlocked = true
                json("name" to manager.name, "unlocked" to true)
            }
            "vault.lock" -> { params.optString("name").takeIf { it.isNotBlank() }.let { name -> managers.filter { name == null || it.name == name }.forEach { it.unlocked = false } }; json("locked" to true) }
            "vault.add" -> vaultAdd(profile, params)
            "vault.remove" -> {
                val id = params.string("id"); if (id.isEmpty()) vaultFailure("id is required")
                val rows = vaultItems[profile]
                val removed = rows?.removeIf { it.string("id") == id } == true
                if (removed) vaultSecrets.remove(id)
                json("removed" to removed)
            }
            else -> error(method)
        }
    }

    /** The viewer's answer to a vault question lands where Hermes would put it (gateway/session_vault_prompt.py save_login 59-66). */
    private fun vaultPromptAnswered(id: String, value: String) {
        val (method, params) = vaultPromptOpen.remove(id) ?: return
        val profile = params.string("_profile").ifBlank { "default" }
        when (method) {
            "vault.save_login" -> {
                val data = runCatching { JSONObject(value) }.getOrNull()?.takeIf { it.string("password").isNotEmpty() } ?: return
                val identifier = data.string("identifier")
                vaultAdd(profile, json("kind" to "login", "label" to params.string("site"), "origin" to params.string("origin"),
                    "secret" to json("identifier_type" to if (identifier.contains('@')) "email" else "username", "identifier" to identifier, "password" to data.getString("password"))))
            }
            "vault.unlock_prompt" -> if (value == vaultMasterPassword) vaultManagers[profile]?.firstOrNull { it.name == params.string("backend") }?.unlocked = true
            else -> Unit
        }
    }

    /** A vault question exactly as `ask_viewer` sends it: the contract's params plus the runtime `session_id`. */
    fun emitVaultPrompt(method: String, id: String, params: JSONObject, profile: String = "default") {
        require(method in setOf("vault.save_login", "vault.code", "vault.unlock_prompt") && id.isNotBlank())
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val (listener, socket) = awaitActiveNativeSocket(0)
        check(serverRequestCapabilities[socket] == true) { "The fixture may emit $method only after client.capabilities server_requests=true" }
        val frame = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params.copy().put("session_id", runtime))
        check(openNativeServerRequests.putIfAbsent(id, frame.copy().apply { remove("jsonrpc") }) == null) { "Duplicate synthetic server request id: $id" }
        vaultPromptOpen[id] = method to params.copy().put("_profile", profile)
        listener.onMessage(socket, frame.toString())
    }

    /** The card-fill consent Hermes sends as an `approval` (tools/browser_vault_tool.py `_confirm_payment_fill`, once/deny only). */
    fun emitCardFillApproval(id: String, label: String, origin: String, amount: String? = null) {
        val payload = json("request_id" to id, "command" to "Fill payment card '$label' on $origin",
            "description" to "The agent wants to enter your saved card details into this checkout page. The card number and CVC never enter the conversation. Approve only if you intend to pay here.",
            "pattern_key" to "mcp_elicitation", "pattern_keys" to JSONArray().put("mcp_elicitation"), "surface" to "vault-payment",
            "choices" to JSONArray().put("once").put("deny"), "allow_session" to false, "allow_permanent" to false)
        amount?.let { payload.put("amount", it) }
        emitNativeServerRequest("approval", id, payload)
    }

    fun activeRuntimeForTest(): String? = activeNativeSessionId
    fun emitNativeEvent(type: String, payload: JSONObject) {
        if (type in setOf("approval", "clarify")) {
            emitNativeServerRequest(type, "fixture-$type-${nativeServerRequestSequence.incrementAndGet()}", payload)
            return
        }
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val event = retainNativeEvent(runtime, type, payload)
        // In the socket log, so a failure shows which socket (and so which collector) an emission went to, relative to the sockets' own calls.
        nativeSocketMethods.add(0 to "EMIT $type seq=${event.optLong("seq")} to socket $activeNativeSocketId")
        val (listener, socket) = awaitActiveNativeSocket(0)
        if (type == "request.cancel") payload.opt("id")?.takeIf { it != JSONObject.NULL }?.let { openNativeServerRequests.remove(it.toString()) }
        listener.onMessage(socket, json("method" to "event", "params" to event).toString())
    }
    fun emitNativeServerRequest(method: String, id: String, payload: JSONObject) {
        require(method in setOf("approval", "clarify") && id.isNotBlank())
        val runtime = activeNativeSessionId ?: error("No active native conversation is selected")
        val (listener, socket) = awaitActiveNativeSocket(0)
        check(serverRequestCapabilities[socket] == true) { "The fixture may emit $method only after client.capabilities server_requests=true" }
        val frame = json("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to payload.copy().put("session_id", runtime))
        // Hermes replays open_requests as {id, method, params} without "jsonrpc" (server_requests.py snapshot()).
        check(openNativeServerRequests.putIfAbsent(id, frame.copy().apply { remove("jsonrpc") }) == null) { "Duplicate synthetic server request id: $id" }
        listener.onMessage(socket, frame.toString())
    }
    fun retainNativeServerRequestForReplay(sessionId: String, method: String, id: String, payload: JSONObject) {
        require(sessionId.isNotBlank() && method in setOf("approval", "clarify") && id.isNotBlank())
        val frame = json("id" to id, "method" to method, "params" to payload.copy().put("session_id", sessionId))
        check(openNativeServerRequests.putIfAbsent(id, frame) == null) { "Duplicate synthetic server request id: $id" }
    }
    /** CHAT-121 follow-up 11: how many next sockets open but never become ready (their gateway.ready never comes), as a link that cannot carry their admission. */
    val holdReady = AtomicInteger()
    val heldReadySockets = AtomicInteger()
    /** Hermes closes the socket the reply streams on (a close frame, not a network drop). */
    fun closeActiveNativeStream(code: Int = 1011, reason: String = "Synthetic server close") {
        val (listener, socket) = awaitActiveNativeSocket(0)
        activeNativeListener = null
        activeNativeSocket = null
        activeNativeSessionId = null
        listener.onClosing(socket, code, reason)
        listener.onClosed(socket, code, reason)
    }
    fun interruptActiveNativeStream() {
        val (listener, socket) = awaitActiveNativeSocket(0)
        activeNativeListener = null
        activeNativeSocket = null
        activeNativeSessionId = null
        listener.onFailure(socket, java.io.IOException("Synthetic native WebSocket interruption"), null)
    }
    /** The ids of the sockets [dropActiveNativeStream] dropped, in order (CHATFIX-121 follow-up 4: which subscriber a drop hit). */
    val droppedNativeSockets = CopyOnWriteArrayList<Int>()
    /** CHATFIX-121: the phone's link drops under the followed stream while Hermes keeps running the turn: its next subscription gets the live frames again. */
    fun dropActiveNativeStream() {
        val (listener, socket) = awaitActiveNativeSocket(0)
        droppedNativeSockets += activeNativeSocketId
        activeNativeListener = null
        activeNativeSocket = null
        listener.onFailure(socket, java.io.IOException("Synthetic link drop"), null)
    }
    val scope = Scope("https://ui-fixture.invalid", ownerName, "default", nativeProvider = "fixture")
    // Saved screen answers outlive a case; each case starts from what its own fixture serves.
    init {
        val app = androidx.test.core.app.ApplicationProvider.getApplicationContext<BeaconApplication>()
        kotlinx.coroutines.runBlocking { app.cache.delete(scope.copy(profile = "", session = ""), "screens") }
        // A device list without an earlier case's phone id reads as "removed on the PC"; that mark must not reach the next case.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val account = NativePairing.accountKey(scope).removePrefix("device:")
        app.getSharedPreferences("native-pairing", android.content.Context.MODE_PRIVATE).edit().remove("unlinked:$account").commit()
        app.getSharedPreferences("push-binding", android.content.Context.MODE_PRIVATE).edit().remove("removed:$account").commit()
        // The fixture's chat ids (chat-a, chat-b, new-N) repeat in every case of every class and the cache is keyed only by them: what an earlier case left for such a chat (a saved live frame with its
        // replay cursor, the running-turn marker, a draft, a receipt) would be read by this case's chat of the same id, and a stale cursor makes a later attach skip the new turn's first events.
        kotlinx.coroutines.runBlocking {
            for (profile in listOf("default", "coder", "academic")) for (id in listOf("chat-a", "chat-b") + (1..24).map { "new-$it" }) {
                val chat = scope.copy(profile = profile).session(id)
                val kinds = listOf("live:runtime-$id", "live:$id", "running-turn", "live-turn", "pending", "draft", "session", "client-created-shell", "side-question")
                app.cache.readMany(chat, kinds).keys.forEach { app.cache.delete(chat, it) }
            }
        }
    }
    val configureHttp: (OkHttpClient.Builder) -> Unit = { builder ->
        builder.addInterceptor { chain ->
            // A network that is down refuses ordinary requests too, not only the live connection.
            if (refuseConnection) throw java.net.ConnectException("Synthetic connection refused")
            val request = chain.request()
            if (sessionReadDelayMs > 0 && request.url.encodedPath.startsWith("/api/sessions/")) Thread.sleep(sessionReadDelayMs)
            check(request.url.host == "ui-fixture.invalid") { "UI fixture may not access a real host" }
            if (request.url.encodedPath in setOf("/api/fs/download", "/api/files/download", "/api/file/raw")) {
                val path = request.url.queryParameter("path").orEmpty()
                fileRequests += "${request.method} ${request.url.encodedPath}?path=$path"
                val bytes = downloads[path]
                return@addInterceptor Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(if (bytes == null) 404 else 200).message("Synthetic download")
                    .body((bytes ?: ByteArray(0)).toResponseBody("application/octet-stream".toMediaType())).build()
            }
            val body = if (request.url.encodedPath == "/api/files/upload-stream") {
                check(request.method == "POST")
                val multipart = requireNotNull(request.body as? MultipartBody)
                fun part(name: String): okio.Buffer = okio.Buffer().also { buffer ->
                    multipart.parts.single { it.headers?.get("Content-Disposition")?.substringBefore("; filename=") == "form-data; name=\"$name\"" }.body.writeTo(buffer)
                }
                check(part("overwrite").readUtf8() == "false")
                json("path" to part("path").readUtf8(), "data_url" to android.util.Base64.encodeToString(part("file").readByteArray(), android.util.Base64.NO_WRAP))
            } else {
                val buffer = okio.Buffer(); request.body?.writeTo(buffer)
                buffer.readUtf8().takeIf(String::isNotBlank)?.let(::JSONObject) ?: JSONObject()
            }
            if (request.url.encodedPath == "/api/plugins/beacon/devices/link") deviceLinkHadAuthorization = request.header("Authorization") != null
            val profileQuery = if (request.url.encodedPath.startsWith("/api/plugins/beacon/")) "beacon_profile" else "profile"
            // Hermes' own /api/profiles/{name}/model and /soul name their profile in the path.
            val pathProfile = Regex("/api/profiles/([a-z0-9][a-z0-9_-]*)/(?:model|soul)").matchEntire(request.url.encodedPath)?.groupValues?.get(1)
            val profile = pathProfile ?: request.url.queryParameter(profileQuery) ?: if (profileIsolationBots) body.string("profile", "default") else "default"
            requests += if (request.url.encodedPath.startsWith("/auth/native/")) json(
                "path" to request.url.encodedPath,
                "method" to request.method,
                "body_fields" to JSONArray().apply { body.keys().forEach { put(it) } },
            )
                else json("path" to request.url.encodedPath, "method" to request.method, "body" to body, "profile" to profile, "query" to request.url.encodedQuery.orEmpty(),
                    "client_profile" to request.header(FIXTURE_CLIENT_PROFILE).orEmpty())
            val expired = forceExpiredAccessToken?.let { request.header("Authorization") == "Bearer $it" } == true
            val outage = pluginStatus?.takeIf { request.url.encodedPath.startsWith("/api/plugins/beacon/") }
            val (status, result) = if (expired) 401 to json("error" to "session_expired")
                // A Hermes without the plugin answers its routes with FastAPI's unknown-route body, which is also how the app tells "not installed" from a failing plugin (BeaconClient: CanonicalRouteMissing).
                else if (outage != null) outage to json("detail" to if (outage == 404) "Not Found" else "Synthetic optional plugin outage")
                else if (deniedSessionIds.any { request.url.encodedPath == "/api/sessions/$it" || request.url.encodedPath.startsWith("/api/sessions/$it/") }) 403 to json("detail" to "Synthetic chat access denied")
                else if (profileIsolationBots && profile in rejectedProfiles && request.url.encodedPath != "/api/profiles") 403 to json("detail" to "Synthetic profile access denied")
                else http(request.url.encodedPath, request.method, body, profile, request.url.queryParameter("path").orEmpty(), request.url.queryParameter("include_archived") == "1" || request.url.queryParameter("archived") == "only", request.url)
            if (status >= 400) rejectedRequests += json("method" to request.method, "path" to request.url.encodedPath, "status" to status)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
                .body(result.toString().toResponseBody("application/json".toMediaType())).build()
        }
    }
    /**
     * Review R29 Nice 3: stamps the sending client's profile on its requests. A stamp already there is kept, so a client built on another client's HTTP stack
     * (clientFor) keeps its own, and a detached client, which shares its parent's stack, carries its parent's.
     */
    private fun stampProfile(profile: String) = Interceptor { chain ->
        val request = chain.request()
        chain.proceed(if (request.header(FIXTURE_CLIENT_PROFILE) != null) request else request.newBuilder().header(FIXTURE_CLIENT_PROFILE, profile).build())
    }
    private fun configureHttpFor(profile: String): (OkHttpClient.Builder) -> Unit = { builder -> builder.addInterceptor(stampProfile(profile)); configureHttp(builder) }
    val client = BeaconClient(AuthContext(scope.origin, emptyList(), scope.owner, scope.profile, backend = "hermes-dashboard", nativeProvider = scope.nativeProvider), null, this, configureHttp = configureHttpFor(scope.profile))
    fun clientFor(auth: AuthContext, authStore: AuthStore? = null) = BeaconClient(
        initial = auth,
        authStore = authStore,
        socketFactory = this,
        sharedHttp = client.http.newBuilder().apply { interceptors().add(0, stampProfile(auth.profile)) }.build(),
        persistContext = false,
        configureHttp = {},
    )
    fun freshClientFor(auth: AuthContext, authStore: AuthStore? = null) =
        BeaconClient(auth, authStore, this, persistContext = false, configureHttp = configureHttpFor(auth.profile))
    init {
        projects["p_project_a"] = json("id" to "p_project_a", "name" to "Travel", "primary_path" to "C:/synthetic-workspace", "folders" to JSONArray().put(json("path" to "C:/synthetic-workspace", "is_primary" to true)))
        sessions["chat-a"] = history("chat-a", "Travel planning", JSONArray().put(json("role" to "user", "text" to "Compare the two plans.", "row_id" to 10)).put(json("role" to "assistant", "text" to "A clear comparison follows.\n\n| Plan | Duration | Status |\n|---|---:|---|\n| City | 3 days | Ready |\n| Mountains and the surrounding national park | 5 days | Needs dates |\n\n```kotlin\nval ready = true\n```", "row_id" to 11)))
        sessions["chat-b"] = history("chat-b", "Reading list", JSONArray().put(json("role" to "user", "text" to "Keep the literal <memory-context>example</memory-context> in this note.", "row_id" to 20)))
        sessions.forEach { (id, row) -> runtimeStoredSessionIds[row.string("session_id")] = id; currentRuntimeSessionIds[id] = row.string("session_id") }
        jobs["task-a"] = json("id" to "task-a", "name" to "Morning summary", "prompt" to "Summarize synthetic sample notes. No external action.", "schedule" to json("kind" to "cron", "expression" to "0 10 * * *", "display" to "Every day at 10:00"), "schedule_display" to "0 10 * * *", "deliver" to "local", "paused" to false, "enabled" to true, "last_status" to "success", "next_run_at" to "2026-10-15T10:00:00+04:00", "scheduler_heartbeat_age_s" to 8)
        boardTasks["t_fixture"] = json("id" to "t_fixture", "title" to "Review travel options", "body" to "Compare the two plans before booking.", "status" to "todo", "assignee" to "default", "priority" to 1)
    }
    private fun history(id: String, title: String, messages: JSONArray) = json("session_id" to "runtime-$id", "stored_session_id" to id,
        "info" to json("title" to title, "model" to "synthetic-model", "provider" to "fixture", "cwd" to "C:/synthetic-workspace", "yolo" to false), "messages" to messages, "running" to false)
    fun addProfileConversation(profile: String, id: String, title: String) {
        val row = history(id, title, JSONArray().put(json("role" to "user", "text" to "Synthetic conversation for $profile."))).put("profile", profile)
        sessions[id] = row
        runtimeStoredSessionIds[row.string("session_id")] = id
        currentRuntimeSessionIds[id] = row.string("session_id")
        profileSessionIds = profileSessionIds + (profile to (profileSessionIds[profile].orEmpty() + id))
    }
    fun snapshot(id: String) = sessions.getValue(id).copy().apply {
        val info = getJSONObject("info"); info.keys().forEach { put(it, info.opt(it)) }
        put("runtime_session_id", getString("session_id")); put("session_id", id)
    }
    private fun nativeDisplayText(message: JSONObject, inlineImages: Boolean): String {
        val content = message.optJSONArray("content") ?: return message.string("content", message.string("text"))
        return (0 until content.length()).joinToString("") { index -> content.getJSONObject(index).let { part ->
            if (part.string("type") == "text") part.string("text") else "\n" + if (inlineImages) part.getJSONObject("image_url").getString("url") else "[image]"
        } }
    }
    /** Synthetic stock dashboard history: metadata and the newest message window. */
    private fun stockSession(path: String, profile: String, url: HttpUrl?): Pair<Int, JSONObject> {
        val parts = path.removePrefix("/api/sessions/").split('/')
        val id = parts.first()
        val row = sessions[id]
        if (historyFailure || id in unsavedRows || row == null || profileSessionIds[profile]?.contains(id) == false) return 404 to json("detail" to "Session not found")
        val messages = row.getJSONArray("messages")
        return when (parts.getOrNull(1)) {
            null -> 200 to row.getJSONObject("info").copy().put("id", id).put("profile", profile).put("message_count", messages.length()).put("last_active", messages.length()).put("model_config", row.optString("model_config").ifBlank { "{}" })
            "messages" -> {
                if (parts.getOrNull(2) != "around" && id == holdMessagesOf) { messagesHeld.countDown(); check(releaseMessages.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                val limit = url?.queryParameter("limit")?.toIntOrNull() ?: 500
                val offset = url?.queryParameter("offset")?.toIntOrNull() ?: 0
                val order = url?.queryParameter("order") ?: "latest"
                if (limit !in 1..500 || offset < 0) return 422 to json("detail" to "Invalid history page")
                val around = parts.getOrNull(2) == "around"
                val target = url?.queryParameter("row_id")?.toLongOrNull()
                val anchor = (0 until messages.length()).firstOrNull { messages.getJSONObject(it).optLong("id", messages.getJSONObject(it).optLong("row_id")) == target }
                if (around && anchor == null) return 404 to json("detail" to "Prompt not found")
                val end = if (around) anchor!! + 1 else if (order == "latest") (messages.length() - offset).coerceAtLeast(0) else (offset + limit).coerceAtMost(messages.length())
                val start = if (around) anchor!! else if (order == "latest") (end - limit).coerceAtLeast(0) else offset.coerceAtMost(end)
                val window = JSONArray((start until end).map { index ->
                    val message = messages.getJSONObject(index)
                    val content = message.optJSONArray("content")
                    if (url?.queryParameter("inline_images") != "false" || content == null) message else {
                        message.copy().put("content", nativeDisplayText(message, false))
                    }
                })
                if (!around) afterNextHistoryPage?.also { afterNextHistoryPage = null }?.invoke()
                200 to json("session_id" to id, "profile" to profile, "messages" to window, "pagination" to json("limit" to limit, "offset" to offset, "order" to order, "returned" to window.length()))
            }
            "latest-descendant" -> 200 to json("requested_session_id" to id, "session_id" to id, "path" to JSONArray().put(id), "changed" to false)
            else -> 404 to json("detail" to "Not Found")
        }
    }
    private fun proactiveResponse(profile: String) = json(
        "enabled" to (proactiveEnabled[profile] ?: false),
        "timezone" to "Asia/Dubai",
        "delivery" to (proactiveDelivery[profile] ?: "notify"),
        "check_minutes" to (proactiveCheckMinutes[profile] ?: 15),
        "quiet_start" to (proactiveQuietStart[profile] ?: "22:00"),
        "quiet_end" to (proactiveQuietEnd[profile] ?: "07:00"),
        "watch_model" to (proactiveWatchModel[profile] ?: ""),
        "watch_provider" to (proactiveWatchProvider[profile] ?: ""),
        "health" to (proactiveHealth[profile] ?: json("state" to "unknown")).copy(),
        "rules" to JSONArray(proactiveRequests[profile]?.map { it.copy() } ?: emptyList<JSONObject>()),
        "learned" to JSONArray(proactiveLearned[profile]?.toList() ?: emptyList<String>()),
        "learned_proposal" to JSONArray(proactiveProposal[profile]?.map { it.copy() } ?: emptyList<JSONObject>()),
        "jobs" to JSONArray(),
    ).apply {
        proactiveSourceNames?.let { names ->
            val chosen = proactiveChosenSources[profile].orEmpty().filter { it in names }
            put("sources", JSONArray(names))
            put("sources_all", chosen.isEmpty())
            put("source_choices", JSONArray(names.map { json("name" to it, "watched" to (chosen.isEmpty() || it in chosen)) }))
        }
    }
    private fun http(path: String, method: String, body: JSONObject, profile: String, directory: String, archived: Boolean = false, url: HttpUrl? = null): Pair<Int, JSONObject> {
        if (path == "/api/plugins/beacon/mobile/assistant-conversation" && method == "GET") assistantLookup?.let { return it }
        if (path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room" && holdNextRoomRead.getAndSet(false)) { roomReadCaptured.countDown(); check(releaseRoomRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        if (path in setOf("/api/sessions", "/api/sessions/search") && holdNextSessionList.getAndSet(false)) { sessionListCaptured.countDown(); check(releaseSessionList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
        return when {
        path == "/api/plugins/beacon/devices/link" && method == "POST" -> {
            deviceLinkCount.incrementAndGet()
            deviceLinkBodies += body.copy()
            // Like the plugin: a known id is refreshed, anything else gets a new record.
            val id = body.string("device_id").takeIf { known -> pairedDevices.any { it.string("device_id") == known } } ?: "device-123"
            val name = body.string("device_name").takeIf(String::isNotBlank) ?: "Beacon phone"
            pairedDevices.removeAll { it.string("device_id") == id }
            pairedDevices += json("device_id" to id, "name" to name, "last_seen" to "2026-10-21T00:00:00Z")
            200 to json("device_id" to id, "name" to name, "profiles" to JSONArray(listOf(scope.profile)))
        }
        path == "/api/plugins/beacon/devices" && method == "GET" -> 200 to json("devices" to JSONArray(pairedDevices.map { it.copy() }))
        path.startsWith("/api/plugins/beacon/devices/") && method == "DELETE" -> {
            val id = path.substringAfterLast('/')
            pairedDevices.removeAll { it.string("device_id") == id }
            200 to json("ok" to true)
        }
        path == "/api/profiles" -> if (profileCatalogUnavailable) 404 to json("detail" to "Synthetic catalog unavailable")
            else 200 to json("profiles" to JSONArray(notificationProfiles.map { id -> json("name" to id, "bot_title" to "Personal", "description" to profileDescription, "provider" to "fixture",
                "model" to if (profileIsolationBots) isolationModels.getValue(id) else profileModel).apply { profileDisplayNames[id]?.let { put("display_name", it) } } }))
        path == "/api/profiles/active" -> 200 to json("current" to profile, "active" to profile)
        method == "GET" && path.startsWith("/api/profiles/") && path.endsWith("/soul") -> 200 to json("content" to profileSoul, "exists" to true)
        path == "/api/model/info" -> 200 to json("model" to if (profileIsolationBots) isolationModels.getValue(profile) else profileModel, "provider" to "fixture")
        method == "PUT" && path == "/api/profiles/$profile/soul" -> { profileSoul = body.getString("content"); 200 to json("ok" to true) }
        method == "PUT" && path == "/api/profiles/$profile/description" -> { profileDescription = body.getString("description"); 200 to json("ok" to true, "description" to profileDescription) }
        path == "/api/plugins/beacon/mobile/proactive" -> {
            if (proactiveRouteMissing) return 404 to json("detail" to "Unknown route")
            if (proactiveStatusCode != 200) return proactiveStatusCode to json("detail" to "Email access needs attention")
            if (method == "PUT" && proactiveSaveStatusCode != 200) return proactiveSaveStatusCode to json("detail" to "Settings change refused")
            if (method == "GET" && holdNextProactiveRead.getAndSet(false)) { proactiveReadCaptured.countDown(); check(releaseProactiveRead.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            if (method == "PUT") {
                if (body.has("enabled")) proactiveEnabled[profile] = body.getBoolean("enabled")
                if (body.has("delivery")) proactiveDelivery[profile] = body.getString("delivery")
                if (body.has("check_minutes")) proactiveCheckMinutes[profile] = body.getInt("check_minutes")
                if (body.has("quiet_start")) proactiveQuietStart[profile] = body.getString("quiet_start")
                if (body.has("quiet_end")) proactiveQuietEnd[profile] = body.getString("quiet_end")
                // Like the plugin, one of the two keys makes the pair the request's own; neither keeps the saved pair.
                if (body.has("watch_model") || body.has("watch_provider")) { proactiveWatchModel[profile] = body.optString("watch_model"); proactiveWatchProvider[profile] = body.optString("watch_provider") }
                if (body.has("sources")) body.getJSONArray("sources").let { names -> proactiveChosenSources[profile] = List(names.length()) { names.getString(it) } }
            }
            200 to proactiveResponse(profile)
        }
        path == "/api/plugins/beacon/mobile/proactive/feedback" && method == "POST" -> {
            if (proactiveFeedbackStatusCode != 200) return proactiveFeedbackStatusCode to json("detail" to "Feedback unavailable")
            require(body.has("notice_id") && !body.has("topic") && body.getString("verdict") in setOf("important", "not_important"))
            // Like the plugin, a rule that cannot be saved leaves the alert unmarked.
            if (body.has("rule") && proactiveRequests[profile].orEmpty().size >= 20) return 400 to json("detail" to "Proactive request limit reached")
            proactiveFeedbacks += body.copy()
            // "Save rule": the words the owner reviewed become one importance rule, in the same list as every other rule.
            if (body.has("rule")) {
                val requests = proactiveRequests.computeIfAbsent(profile) { CopyOnWriteArrayList() }
                requests += json("id" to "request-${java.util.UUID.randomUUID()}", "text" to body.getString("rule"), "action" to body.getString("verdict"),
                    "signal" to "email", "night" to false, "enabled" to true, "created_at" to 1792400000)
            }
            200 to json("ok" to true)
        }
        path == "/api/plugins/beacon/mobile/proactive/suggestion" && method == "GET" -> {
            val noticeId = url?.queryParameter("notice_id").orEmpty()
            val verdict = url?.queryParameter("verdict").orEmpty()
            require(noticeId.isNotBlank() && verdict in setOf("important", "not_important"))
            200 to (proactiveSuggestions[noticeId]?.copy()?.put("verdict", verdict)
                ?: json("verdict" to verdict, "source" to "email", "sender" to "Daniel Reyes", "topic" to "Client moved the review", "rule" to "Sent by Daniel Reyes"))
        }
        path == "/api/plugins/beacon/mobile/proactive/requests" && method == "POST" -> {
            val requestText = body.getString("text")
            require(requestText.isNotBlank() && requestText.codePointCount(0, requestText.length) <= 300 && body.getString("action") in setOf("notify", "call"))
            val requests = proactiveRequests.computeIfAbsent(profile) { CopyOnWriteArrayList() }
            require(requests.size < 20)
            // R6P uses null for Anything and preserves an optional connected-source name.
            require(body.isNull("signal") || body.getString("signal").let { it.isNotBlank() && it.length <= 80 })
            val request = json("id" to "request-${java.util.UUID.randomUUID()}", "text" to body.getString("text"), "action" to body.getString("action"),
                "signal" to body.opt("signal"), "night" to body.optBoolean("night"), "enabled" to true, "created_at" to 1792400000)
            requests += request
            200 to request.copy()
        }
        path.startsWith("/api/plugins/beacon/mobile/proactive/requests/") && method in setOf("PATCH", "DELETE") -> {
            val id = path.substringAfterLast('/')
            val requests = proactiveRequests[profile]
            val request = requests?.firstOrNull { it.string("id") == id }
            if (request == null) 404 to json("detail" to "Request not found")
            else if (method == "DELETE") { requests!!.remove(request); 200 to json("ok" to true) }
            else {
                if (body.has("enabled")) request.put("enabled", body.getBoolean("enabled"))
                if (body.has("night")) request.put("night", body.getBoolean("night"))
                if (body.has("text")) { require(request.string("action") in setOf("important", "not_important") && body.getString("text").isNotBlank()); request.put("text", body.getString("text")) }
                if (body.has("action")) { require(body.getString("action") in setOf("notify", "call")); request.put("action", body.getString("action")) }
                200 to request.copy()
            }
        }
        path == "/api/plugins/beacon/mobile/proactive/learned" && method == "DELETE" -> {
            val notes = proactiveLearned.computeIfAbsent(profile) { CopyOnWriteArrayList() }
            if (body.optBoolean("all")) { notes.clear(); proactiveProposal[profile]?.clear() } else notes.remove(body.getString("note"))
            200 to json("ok" to true)
        }
        path == "/api/plugins/beacon/mobile/proactive/learned/rules" && method == "POST" -> {
            val rules = body.getJSONArray("rules")
            require(rules.length() in 0..5)
            // Like the plugin, a list that would pass twenty rules saves none of them.
            if (proactiveRequests[profile].orEmpty().size + rules.length() > 20) return 400 to json("detail" to "Proactive request limit reached")
            proactiveLearnedRuleBodies += body.copy()
            val requests = proactiveRequests.computeIfAbsent(profile) { CopyOnWriteArrayList() }
            for (index in 0 until rules.length()) {
                val rule = rules.getJSONObject(index)
                require(rule.getString("verdict") in setOf("important", "not_important") && rule.getString("rule").isNotBlank())
                requests += json("id" to "request-${java.util.UUID.randomUUID()}", "text" to rule.getString("rule"), "action" to rule.getString("verdict"),
                    "signal" to rule.getString("source"), "night" to false, "enabled" to true, "created_at" to 1792400000)
            }
            proactiveLearned[profile]?.clear()
            proactiveProposal[profile]?.clear()
            200 to json("ok" to true, "saved" to rules.length())
        }
        path == "/api/plugins/beacon/mobile/history-retention" -> {
            if (method == "PUT") {
                val days=body.optInt("history_retention_days",-1)
                if (days !in setOf(0,30,90,365)) return 400 to json("detail" to "Invalid retention")
                historyRetentionDays=days
            }
            200 to json("history_retention_days" to historyRetentionDays)
        }
        path == "/api/plugins/beacon/mobile/calls" -> if (callHistoryRouteMissing) 404 to json("detail" to "Unknown route") else 200 to callHistoryResponse.copy()
        // Like lost_turns_api.py: only the asked chats (and bots), read-only; each lost reply with what Discard takes.
        path == "/api/plugins/beacon/mobile/lost-turns" && method == "GET" -> 200 to json("lost" to JSONObject().apply {
            url?.queryParameter("ids").orEmpty().split(',').filter(String::isNotBlank).forEach { id -> lostChats[id]?.let { put(id, json("waiting" to it, "unknown" to lostUnknown())) } }
        }).apply {
            val bots = url?.queryParameter("bots").orEmpty().split(',').filter(String::isNotBlank)
            if (bots.isNotEmpty()) put("bots", JSONObject().apply { bots.forEach { bot -> lostBots[bot]?.let { put(bot, json("session_id" to it, "waiting" to 0, "unknown" to lostUnknown())) } } })
        }
        path == "/api/plugins/beacon/mobile/notifications/events" -> {
            val after = url?.queryParameter("after")?.toLongOrNull() ?: 0L
            val wait = url?.queryParameter("wait")?.toLongOrNull() ?: 0L
            notificationWaits += wait
            var event = incomingNotification?.takeIf { it.optLong("seq") > after && it.string("profile") == profile }
            if (event == null && wait > 0) {
                Thread.sleep(minOf(wait * 1000, 50))
                event = incomingNotification?.takeIf { it.optLong("seq") > after && it.string("profile") == profile }
            }
            200 to json("events" to JSONArray().apply { event?.let { put(JSONObject(it.toString())) } }, "next_cursor" to (event?.optLong("seq") ?: after))
        }
        path.startsWith("/api/plugins/beacon/mobile/notifications/events/") -> {
            val id = path.substringAfterLast('/')
            val event = incomingNotification?.takeIf { it.string("event_id") == id && it.string("profile") == profile }
            if (event == null) 404 to json("detail" to "Notification missing") else 200 to JSONObject(event.toString())
        }
        path.startsWith("/api/plugins/beacon/mobile/callbacks/") -> {
            val rest = path.removePrefix("/api/plugins/beacon/mobile/callbacks/")
            val id = rest.substringBefore('/')
            val action = rest.substringAfterLast('/')
            val event = incomingNotification?.takeIf { it.string("event_id") == id && it.string("profile") == profile }
            if (event == null) 404 to json("detail" to "Invitation missing")
            else if (action == "decline" && method == "POST") {
                event.put("state", "declined")
                200 to json("declined" to true, "state" to "declined", "callback_id" to id, "kind" to "incoming_call", "profile" to profile)
            } else if (action == "answer" && method == "POST") {
                event.put("state", "answered")
                200 to json("call_id" to "fixture-call", "conversation_id" to event.string("session_id"), "profile" to profile,
                    "transport" to "gemini_live_direct", "provider" to "gemini", "callback_id" to id, "kind" to "incoming_call", "state" to "answered")
            } else 404 to json("detail" to "Unknown callback action")
        }
        path == "/api/plugins/beacon/mobile/voice-settings" -> {
            if (method == "PUT") {
                voiceProvider = body.getString("provider"); check(voices.containsKey(voiceProvider))
                if (body.has("voice")) voices[voiceProvider] = body.getString("voice")
                if (body.has("personality")) personalities[voiceProvider] = body.getString("personality")
            }
            200 to json("selected_provider" to voiceProvider, "providers" to JSONArray(listOf("gemini", "grok").map { id ->
                json("id" to id, "label" to if (id == "gemini") "Gemini Live" else "Grok Voice", "available" to true, "vision" to (id == "gemini"), "voice" to voices[id], "personality" to personalities[id],
                    "voices" to JSONArray(if (id == "gemini") listOf("Kore", "Puck") else listOf("ara", "eve")), "personalities" to JSONArray(listOf("balanced", "concise", "warm").map { json("id" to it, "label" to it.replaceFirstChar(Char::titlecase)) }))
            }))
        }
        path == "/api/plugins/beacon/mobile/bot-mode" -> 200 to json("bots" to JSONArray(botProfiles.map { json("profile" to it, "title" to it, "description" to "Synthetic bot") }), "spaces" to JSONArray().apply { if(!roomDeleted) put(json("room_id" to "fixture-room", "name" to roomName)) }, "capabilities" to roomCapabilities())
        path == "/api/plugins/beacon/mobile/bot-mode/chat" && method == "POST" -> {
            val bot = body.getString("bot"); check(bot in setOf("default", "coder"))
            val id = "bot-$bot"
            sessions.putIfAbsent(id, history(id, "Bot Chat", JSONArray()))
            200 to json("profile" to bot, "title" to "Bot Chat", "stored_session_id" to id)
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space" && method == "POST" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName))
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/messages" -> {
            if (rejectRoomSend) 503 to json("detail" to "Synthetic shared-space interruption") else {
                roomEvents += json("event_id" to body.getString("event_id"), "kind" to "message.user", "payload" to json("text" to body.getString("text")))
                200 to json("accepted" to true)
            }
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/action" -> {
            val pending = roomActions.singleOrNull { action ->
                body.string("kind") == "approval" && action.string("kind") == "approval" &&
                    listOf("task_id", "member_id", "request_id").all { action.string(it) == body.string(it) } &&
                    action.optInt("execution_generation") == body.optInt("execution_generation") &&
                    body.string("choice") in action.getJSONObject("approval").getJSONArray("choices").strings()
            }
            if (roomCapabilitiesAvailable && pending != null) {
                roomActions.remove(pending)
                200 to json("accepted" to true)
            } else 409 to json("detail" to "Native approval was not confirmed")
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/members" -> 409 to json("detail" to "Native Hermes cannot change bots in an existing space")
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/delete" -> {
            check(body.getString("expected_name") == roomName); roomDeleted = true
            200 to json("tombstone" to json("room_id" to "fixture-room", "disbanded_at" to 1_800_000_000L,
                "idempotent" to false, "event" to json("room_id" to "fixture-room", "kind" to "room.disbanded", "event_id" to "system:room-disbanded")))
        }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room/rename" -> { roomName = body.getString("name"); 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName)) }
        path == "/api/plugins/beacon/mobile/bot-mode/space/fixture-room" -> 200 to json("room" to json("room_id" to "fixture-room", "name" to roomName, "members" to JSONArray(roomMembers.map { json("profile" to it) })), "events" to JSONArray(roomEvents.toList()), "driver_status" to json("running" to true, "pending_actions" to JSONArray(roomActions.toList())), "capabilities" to roomCapabilities())
        path in setOf("/api/sessions", "/api/sessions/search") -> {
            // Stock dashboard index: profile-scoped pages of at most 100, archived=exclude|only.
            val limit = url?.queryParameter("limit")?.toIntOrNull() ?: 20
            val offset = url?.queryParameter("offset")?.toIntOrNull() ?: 0
            val search = if (path == "/api/sessions/search") url?.queryParameter("q").orEmpty() else ""
            // Like Hermes: `sources` keeps only those platforms, `exclude_sources` drops them.
            val only = url?.queryParameter("sources")?.split(',')?.filter(String::isNotBlank)?.toSet()
            val excluded = url?.queryParameter("exclude_sources").orEmpty().split(',').filter(String::isNotBlank).toSet()
            sessionListQueries += json("sources" to url?.queryParameter("sources"), "exclude_sources" to url?.queryParameter("exclude_sources"), "order" to url?.queryParameter("order"))
            val failure = sessionListFailures[profile]
            if (failure != null) failure.first to json("detail" to failure.second)
            else if (limit !in 1..100) 422 to json("detail" to "limit must be at most 100")
            else {
                if (holdNextHistoryList.getAndSet(false)) { historyListCaptured.countDown(); check(releaseHistoryList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
                200 to json("sessions" to JSONArray(sessions.values.filter { profileSessionIds[profile]?.contains(it.string("stored_session_id")) != false &&
                    (if (archived) it.getJSONObject("info").optBoolean("archived") else !it.getJSONObject("info").optBoolean("hidden") && !it.getJSONObject("info").optBoolean("archived")) }
                .filter { search.isBlank() || it.getJSONObject("info").string("title").contains(search, ignoreCase = true) }
                .filter { row -> val source = row.getJSONObject("info").string("source"); (only == null || source in only) && source !in excluded }
                .map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("profile", profile).put("message_count", row.getJSONArray("messages").length()) }
                .drop(offset).take(limit)))
            }
        }
        // Like Hermes: a sidebar PATCH needs the revision precondition from its mutation snapshot plus a request id.
        path.startsWith("/api/sessions/") && path.endsWith("/mutation-snapshot") && method == "GET" -> path.removePrefix("/api/sessions/").removeSuffix("/mutation-snapshot").let { id ->
            val info = sessions[id]?.getJSONObject("info")
            200 to json("session_id" to id, "exists" to (info != null), "runtime_revision" to (info?.optInt("runtime_revision") ?: 0), "runtime_generation" to if (info != null) 0 else JSONObject.NULL)
        }
        // Hermes's sidebar PATCH: the only REST mutation Beacon uses (pin).
        path.startsWith("/api/sessions/") && method == "PATCH" -> path.removePrefix("/api/sessions/").let { id ->
            val info = sessions.getValue(id).getJSONObject("info")
            if (body.string("request_id").isBlank() || !body.has("expected_revision")) 409 to json("detail" to "mutation_identity_required")
            else if (body.getInt("expected_revision") != info.optInt("runtime_revision")) 409 to json("detail" to "revision_conflict")
            else { info.put("pinned", body.getBoolean("pinned")).put("hidden", false).put("runtime_revision", info.optInt("runtime_revision") + 1); 200 to json("ok" to true, "session_id" to id, "pinned" to body.getBoolean("pinned")) }
        }
        path.startsWith("/api/sessions/") -> stockSession(path, profile, url)
        // The plugin's admin_api.enable_plugin_for_profile: Hermes' own activation for that one profile (profile in the body); idempotent.
        path == "/api/plugins/beacon/admin/profile-plugin/enable" && method == "POST" -> {
            profileEnableGate?.await(30, java.util.concurrent.TimeUnit.SECONDS)
            val target = body.string("profile")
            if (profileEnableStatus != 200) profileEnableStatus to json("detail" to if (profileEnableStatus == 404) "Not Found" else "Synthetic enable failure")
            else if (body.optBoolean("auto") && target in pluginDisabledProfiles) 200 to json("ok" to true, "profile" to target, "enabled" to false, "changed" to false)
            else {
                val changed = target in pluginOffProfiles || target in pluginDisabledProfiles
                pluginOffProfiles = pluginOffProfiles - target; pluginDisabledProfiles = pluginDisabledProfiles - target
                200 to json("ok" to true, "profile" to target, "enabled" to true, "changed" to changed)
            }
        }
        path == "/api/plugins/beacon/mobile/gateway-ticket" -> if (profile in pluginOffProfiles || profile in pluginDisabledProfiles) 403 to json("detail" to "Plugin not authorized") else 404 to json("detail" to "Not Found")
        path == "/api/auth/ws-ticket" -> 200 to json("ticket" to "synthetic-once")
        path == "/auth/native/authorize" -> 200 to json("ready" to true)
        path == "/auth/native/token" -> if (body.string("code").isBlank() || body.string("code_verifier").isBlank()) 400 to json("detail" to "Invalid code or verifier") else { currentRefreshToken = "synthetic-refresh"; 200 to json("access_token" to "synthetic-access", "refresh_token" to currentRefreshToken, "expires_at" to 2_000_000_000L, "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner) }
        path == "/auth/native/refresh" -> {
            val supplied = body.string("refresh_token")
            refreshTokensUsed += supplied
            if (supplied.isBlank() || supplied != currentRefreshToken) {
                rejectedRefreshTokens += supplied
                401 to json("error" to "session_expired")
            } else {
                val number = refreshSequence.incrementAndGet()
                currentRefreshToken = "synthetic-refresh-$number"
                200 to json("access_token" to "synthetic-access-$number", "refresh_token" to currentRefreshToken, "expires_at" to (System.currentTimeMillis() / 1000 + 3600), "token_type" to "Bearer", "provider" to "fixture", "user_id" to scope.owner)
            }
        }
        path == "/api/auth/me" -> 200 to json("user_id" to (signedInOwner ?: scope.owner), "provider" to scope.nativeProvider)
        path == "/auth/logout" -> 200 to json("ok" to true)
        // Like Hermes' own route: the profile's current model and provider beside the provider list. /api/profile/defaults reads the model from here (since b8dbad4c).
        path == "/api/model/options" -> 200 to json("model" to profileModel, "provider" to "fixture", "providers" to JSONArray().put(json("slug" to "fixture", "name" to "Configured provider", "models" to JSONArray(listOf("synthetic-model", "other-model", "confirm-model")))))
        path == "/api/plugins/beacon/admin/model-capabilities" -> {
            if (holdReasoningOptions.getAndSet(false)) {
                reasoningOptionsHeld.countDown()
                check(releaseReasoningOptions.await(30, java.util.concurrent.TimeUnit.SECONDS))
            }
            200 to json("reasoning_known" to true, "reasoning_options" to JSONArray(reasoningOptions))
        }
        path == "/api/tools/toolsets" -> 200 to json("items" to JSONArray().put(json("name" to "web", "label" to "Web research", "description" to "Read public pages", "enabled" to toolEnabled, "configured" to true, "tools" to JSONArray().put("web_search"))))
        path == "/api/tools/toolsets/web" && method == "PUT" -> {
            if (holdNextToolOperation.compareAndSet(true, false)) { toolOperationCaptured.countDown(); check(releaseToolOperation.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            toolEnabled = body.getBoolean("enabled"); 200 to json("ok" to true)
        }
        path == "/api/mcp/servers" -> 200 to json("servers" to JSONArray().put(json("name" to "fixture_mcp", "enabled" to connectionEnabled, "transport" to "stdio", "source" to "config")))
        path == "/api/mcp/servers/fixture_mcp/enabled" -> { connectionEnabled = body.getBoolean("enabled"); 200 to json("ok" to true) }
        path == "/api/mcp/servers/fixture_mcp/test" -> 200 to json("ok" to true, "tools" to JSONArray().put(json("name" to "read_fixture")))
        path == "/api/plugins/beacon/mobile/skills" -> 200 to json("items" to JSONArray().put(json("name" to "travel-planning", "description" to "Plan a trip from verified information", "enabled" to if (profileIsolationBots) isolationSkillEnabled.getValue(profile) else skillEnabled, "essential" to false, "requirements" to JSONArray(), "category" to "travel", "provenance" to "local")))
        path == "/api/skills/toggle" -> {
            val enabled = body.getBoolean("enabled")
            if (profileIsolationBots) isolationSkillEnabled[profile] = enabled else skillEnabled = enabled
            200 to json("ok" to true, "name" to "travel-planning", "enabled" to enabled)
        }
        path == "/api/plugins/beacon/mobile/skills/content" -> {
            if (method == "PUT") {
                if (rejectSkillSave) return 409 to json("detail" to "Instructions changed. Your draft is retained.")
                if (profileIsolationBots) isolationSkills[profile] = body.getString("content") else skillText = body.getString("content")
                200 to json("success" to true)
            } else 200 to json("name" to "travel-planning", "content" to if (profileIsolationBots) isolationSkills.getValue(profile) else skillText, "revision" to "a".repeat(64))
        }
        path == "/api/memory" -> 200 to json("active" to "builtin", "providers" to JSONArray())
        path == "/api/plugins/beacon/memory/documents" -> {
            val notes = if (profileIsolationBots) isolationMemory.getValue(profile) else memoryNotes
            fun document(target: String) = json("target" to target, "content" to notes.getValue(target), "revision" to "a".repeat(64), "enabled" to true, "limit" to 8000)
            if (method == "PUT") {
                if (rejectMemorySave) return 409 to json("detail" to "Notes changed. Review before saving.")
                val target = body.getString("target"); check(notes.containsKey(target)); notes[target] = body.getString("content")
                200 to json("ok" to true, "document" to document(target))
            } else {
                if (holdMemoryProfile == profile) { holdMemoryProfile = ""; memoryReadCaptured.countDown(); check(releaseMemoryRead.await(10, java.util.concurrent.TimeUnit.SECONDS)); memoryReadReturned.countDown() }
                200 to json("documents" to JSONArray(listOf("memory", "user").map(::document)))
            }
        }
        path == "/api/plugins/beacon/admin/bots" -> 200 to json("bots" to JSONArray().put(json("profile" to "default", "title" to "Personal", "purpose" to "Synthetic assistant")).apply {
            if (profileIsolationBots) notificationProfiles.filter { it != "default" }.forEach { put(json("profile" to it, "title" to it, "purpose" to "$it assistant")) }
        })
        // Like Hermes: the default model of one profile, named in the path, with no revision.
        path.startsWith("/api/profiles/") && path.endsWith("/model") && method == "PUT" -> {
            if (profileIsolationBots) isolationModels[profile] = body.getString("model") else profileModel = body.getString("model")
            200 to json("ok" to true, "provider" to body.getString("provider"), "model" to body.getString("model"))
        }
        path == "/api/status" -> 200 to json("version" to "fixture", "overall" to "ok", "gateway_state" to "connected", "active_agents" to 0, "components" to json("gateway" to json("status" to "ok", "state" to "connected")))
        path == "/api/logs" -> 200 to json("lines" to JSONArray())
        path == "/api/ops/checkpoints" -> 200 to json("total_bytes" to 0, "sessions" to JSONArray())
        path == "/api/hermes/update/check" -> 200 to json("current_version" to "fixture", "can_apply" to true, "update_available" to !updateApplied)
        // Like Hermes (actions.py): the update's action log with the summary of its receipt, the latest full receipt (404 until an update has run), and the start.
        path == "/api/actions/hermes-update/status" -> 200 to json("name" to "hermes-update", "running" to false, "exit_code" to if (updateApplied) 0 else JSONObject.NULL, "pid" to JSONObject.NULL, "lines" to JSONArray()).apply {
            if (updateApplied) put("action_id", "fixture-update").put("receipt", json("action_id" to "fixture-update", "started_at" to "2026-10-16T00:00:00Z", "finished_at" to "2026-10-16T00:00:01Z", "outcome" to "success"))
        }
        path == "/api/hermes/update/receipt" ->
            if (updateApplied) 200 to json("receipt" to json("action_id" to "fixture-update", "started_at" to "2026-10-16T00:00:00Z", "finished_at" to "2026-10-16T00:00:01Z", "outcome" to "success"))
            else 404 to json("detail" to "No update receipt found (no `hermes update` run recorded).")
        path == "/api/hermes/update" && method == "POST" -> { updateApplied = true; 200 to json("ok" to true, "pid" to 4242, "name" to "hermes-update", "action_id" to "fixture-update") }
        path == "/api/files" -> {
            val current = directory.ifBlank { "C:/synthetic-workspace" }
            val rows = JSONArray().put(json("name" to "notes.md", "path" to "$current/notes.md", "is_directory" to false, "size" to 28))
            workspaceFiles.keys.sorted().forEach { rows.put(json("name" to it, "path" to "$current/$it", "is_directory" to false)) }
            rows.put(json("name" to "research", "path" to "$current/research", "is_directory" to true))
            // Like Windows' My Documents: a shortcut listed under its target's path. The same path twice once crashed the list.
            rows.put(json("name" to "Research shortcut", "path" to "$current/research", "is_directory" to true))
            rows.put(json("name" to ".cache", "path" to "$current/.cache", "is_directory" to true))
            if (holdNextFileList.getAndSet(false)) { fileListCaptured.countDown(); check(releaseFileList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            200 to json("entries" to rows, "path" to current, "parent" to "C:/", "can_change_path" to true)
        }
        path == "/api/plugins/beacon/mobile/files/text" -> {
            if (method == "POST") {
                if (body.getString("expected_content") != fileText) return 409 to json("detail" to "File changed. Reopen and review your draft.")
                fileWrites++; fileText = body.getString("content")
            }
            200 to json("content" to fileText, "text" to fileText, "binary" to false, "truncated" to false, "ok" to true)
        }
        path == "/api/fs/list" -> {
            val rows = JSONArray().put(json("name" to "notes.md", "path" to "notes.md", "type" to "file", "size" to 28))
            workspaceFiles.keys.sorted().forEach { rows.put(json("name" to it, "path" to it, "type" to "file")) }
            if (holdNextFileList.getAndSet(false)) { fileListCaptured.countDown(); check(releaseFileList.await(10, java.util.concurrent.TimeUnit.SECONDS)) }
            200 to json("entries" to rows)
        }
        path == "/api/plugins/beacon/mobile/files/create" && method == "POST" -> {
            val filename = body.getString("path").replace('\\', '/').substringAfterLast('/')
            if (workspaceFiles.containsKey(filename)) 409 to json("detail" to "File already exists") else {
                val content = body.string("content"); workspaceFiles[filename] = content
                downloads[body.getString("path")] = content.toByteArray(); 200 to json("ok" to true)
            }
        }
        path in setOf("/api/files/upload", "/api/files/upload-stream") && method == "POST" -> {
            workspaceFiles[body.getString("path").replace('\\', '/').substringAfterLast('/')] = body.getString("data_url")
            200 to json("ok" to true)
        }
        path == "/api/fs/read-text" -> 200 to json("text" to fileText, "binary" to false, "truncated" to false, "editable" to true)
        path == "/api/fs/write-text" && method == "POST" -> { check(body.getString("path").replace('\\','/').endsWith("/notes.md")) { "Unexpected fixture file write" }; fileWrites++; fileText = body.getString("content"); 200 to json("ok" to true) }
        path == "/api/plugins/kanban/boards" -> if (boardFailure) 503 to json("detail" to "Boards are offline") else 200 to json("boards" to JSONArray().put(json("slug" to "default", "name" to "Personal")).put(json("slug" to "work", "name" to "Work")), "current" to preferredBoard)
        path == "/api/plugins/kanban/board" -> 200 to json("columns" to JSONArray(dev.hermes.android.data.boardStatuses.keys.map { status -> json("name" to status, "tasks" to JSONArray(boardTasks.values.filter { it.string("status") == status })) }))
        path == "/api/plugins/kanban/tasks" && method == "POST" -> {
            val id = "t_new"; val row = body.copy().put("id", id).put("status", "todo").put("priority", 0); boardTasks[id] = row; 200 to json("task" to row)
        }
        path.startsWith("/api/plugins/kanban/tasks/") -> {
            val id = path.substringAfterLast('/'); val row = boardTasks[id] ?: return 404 to json("detail" to "Task missing")
            when (method) { "PATCH" -> { body.keys().forEach { row.put(it, body.opt(it)) }; 200 to json("task" to row) }; "DELETE" -> { boardTasks.remove(id); 200 to json("deleted" to true) }; else -> 200 to json("task" to row) }
        }
        path == "/api/cron/delivery-targets" -> 200 to json("targets" to JSONArray().put(json("id" to "local", "name" to "Local", "home_target_set" to true)).put(json("id" to "telegram", "name" to "Telegram", "home_target_set" to false)))
        path == "/api/cron/jobs" && method == "POST" -> {
            val id = "task-new"; jobs[id] = body.copy().put("id", id).put("paused", false); 200 to json("ok" to true, "job" to jobs.getValue(id))
        }
        path == "/api/cron/jobs" && method == "GET" -> 200 to json("items" to JSONArray(jobs.values.toList()))
        path.startsWith("/api/cron/jobs/") && path.endsWith("/runs") -> 200 to json("runs" to JSONArray().put(json("session_id" to "chat-b", "title" to "Synthetic completed run", "status" to "completed", "created_at" to "2026-10-14T10:00:00+04:00")), "limit" to 20)
        path.startsWith("/api/cron/jobs/") -> {
            val id = path.removePrefix("/api/cron/jobs/").substringBefore('/')
            val job = jobs[id] ?: return 404 to json("detail" to "Task not found")
            when {
                method == "PUT" -> {
                    cronFailure?.let { return 400 to json("detail" to it) }
                    val updates = body.getJSONObject("updates")
                    check(!updates.has("id") && !updates.has("next_run_at") && !updates.has("last_status")) { "Immutable/runtime fields leaked into a task edit" }
                    updates.keys().forEach { job.put(it, updates.opt(it)) }; 200 to json("job" to job, "ok" to true)
                }
                // Hermes cron/jobs.py pause_job/resume_job write enabled + state; the legacy flag is kept for older readers.
                path.endsWith("/pause") -> { job.put("paused", true).put("enabled", false).put("state", "paused"); 200 to json("job" to job, "ok" to true) }
                path.endsWith("/resume") -> { job.put("paused", false).put("enabled", true).put("state", "scheduled"); 200 to json("job" to job, "ok" to true) }
                path.endsWith("/trigger") -> 200 to json("ok" to true, "status" to "queued")
                method == "DELETE" -> { jobs.remove(id); 200 to json("ok" to true) }
                else -> 200 to json("job" to job)
            }
        }
        // The plugin keeps a mark for a thread its Hermes could not store as one: POST marks a chat it holds, GET lists the marked threads.
        path.startsWith("/api/plugins/beacon/mobile/sessions/") && path.endsWith("/thread") && method == "POST" -> path.removePrefix("/api/plugins/beacon/mobile/sessions/").removeSuffix("/thread").let { id ->
            if (sessions.containsKey(id)) { pluginThreadMarks += id; 200 to json("thread" to true, "stored_session_id" to id, "profile" to profile) } else 404 to json("detail" to "Conversation is not available to this account")
        }
        path == "/api/plugins/beacon/mobile/threads" && method == "GET" -> 200 to json("threads" to JSONArray(pluginThreadMarks.filter { sessions.containsKey(it) }.map { json("id" to it, "title" to sessions.getValue(it).getJSONObject("info").string("title")) }))
        path.startsWith("/api/plugins/beacon/mobile/sessions/") && path.endsWith("/reserve") -> {
            unsavedRows.remove(path.substringBeforeLast('/').substringAfterLast('/'))  // like the plugin, reserve saves the chat's row
            200 to json("reserved" to true, "stored_session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to profile)
        }
        path.endsWith("/voice-history") -> 200 to json("session_id" to path.substringBeforeLast('/').substringAfterLast('/'), "profile" to scope.profile, "events" to JSONArray())
        path.endsWith("/notifications/status") -> 200 to json("android_configured" to false, "targets" to JSONArray(), "delivery" to json("pending" to 0), "available" to false)
        path.endsWith("/tasks/capabilities") -> if (checkupCapabilitiesFailure) 503 to json("detail" to "Synthetic capabilities unavailable")
            else 200 to (checkupCapabilities?.copy() ?: json("timezone" to "Asia/Dubai", "random_checkups" to false))
        path == "/api/plugins/beacon/mobile/tasks/checkups" && method == "POST" && checkupPostEnabled -> {
            body.optString("cadence").takeIf { it.isNotBlank() }?.let { checkupCapabilities?.put("checkup_cadence", it) }
            200 to json("ok" to true)
        }
        path == "/api/plugins/beacon/mobile/health" -> 200 to json("plugin" to json("installed" to "1.2.0", "running" to "1.2.0", "gateway" to "1.2.0"), "dashboard" to json("current" to true),
            "gateway" to json("known" to true, "running" to true, "current" to true, "busy" to false), "calls" to json("ready" to true, "provider" to "gemini", "reason" to ""),
            "push" to json("ready" to true, "sender" to true, "targets" to 1, "reason" to ""), "cached" to false)
        path == "/api/plugins/beacon/mobile/bootstrap" -> 200 to json("contract_version" to 2, "backend" to "hermes-dashboard", "owner_id" to scope.owner, "profile" to profile, "voice" to json("available" to false)).apply {
            bootstrapPluginStatus?.let { put("optional_features", json("plugin" to it)) }
        }
        else -> 404 to json("detail" to "Synthetic fixture has no response for $path")
    } }
    override fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket {
        check(request.url.host == "ui-fixture.invalid")
        val socketId = nativeSocketSequence.incrementAndGet()
        val socket = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(bytes: ByteString) = false
            override fun close(code: Int, reason: String?): Boolean { closedSockets += socketId; return true }
            override fun cancel() { closedSockets += socketId }
            override fun send(text: String): Boolean {
                val frame = JSONObject(text)
                if (!frame.has("method")) {
                    beforePromptResponse?.also { beforePromptResponse = null }?.invoke()
                    if (rejectPromptResponse) { rejectPromptResponse = false; return false }
                    nativeServerResponses += frame.copy()
                    frame.opt("id")?.takeIf { it != JSONObject.NULL }?.let { openNativeServerRequests.remove(it.toString()); vaultPromptAnswered(it.toString(), frame.optJSONObject("result")?.optString("value").orEmpty()) }
                    onPromptResponse?.also { onPromptResponse = null }?.invoke()
                    return true
                }
                if (dropNextPromptUnread && frame.optString("method") == "prompt.submit") {
                    dropNextPromptUnread = false; unreadPrompts.incrementAndGet()
                    listener.onFailure(this, java.io.IOException("Synthetic network drop before Hermes read the prompt"), null)
                    return true
                }
                requests += frame.copy()
                try {
                    val method = frame.getString("method")
                    nativeSocketMethods.add(socketId to method)
                    val params = frame.optJSONObject("params") ?: JSONObject()
                    if (method == "client.capabilities") {
                        serverRequestCapabilities[this] = params.optBoolean("server_requests")
                        listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to JSONObject()).toString())
                        return true
                    }
                    when (method) {
                        "prompt.submit", "session.steer" -> params.string("session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                        "session.active_list" -> params.string("current_session_id").takeIf(String::isNotBlank)?.let(pendingNativeStreams::add)
                    }
                    val result = rpc(method, params)
                    // A prompt that started a turn (not one Hermes queued behind another) has its start retained for replay. A canonical submit retains its own admission-stamped
                    // start in startCanonicalTurn (Hermes answers it "queued" and then claims it), so only the plain gateway is covered here: one start per turn, not two.
                    if (method == "prompt.submit" && !canonicalAuthority && nativeTurnStatus == "working") retainMessageStart(params.string("session_id"))
                    val response = json("jsonrpc" to "2.0", "id" to frame.getString("id"), "result" to result).toString()
                    if (method == "projects.get" && holdNextProjectDetail) {
                        holdNextProjectDetail = false
                        projectDetailCaptured.countDown()
                        // Real WebSocket.send enqueues; a held response must not block the Compose thread.
                        Thread {
                            val released = releaseProjectDetail.await(10, java.util.concurrent.TimeUnit.SECONDS)
                            listener.onMessage(this, if (released) response else json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to 500, "message" to "Synthetic project detail was not released")).toString())
                        }.apply { isDaemon = true; name = "beacon-project-test-response"; start() }
                        return true
                    }
                    if (method == "session.create" && loseNextCreateAnswer) {
                        loseNextCreateAnswer = false
                        listener.onFailure(this, java.io.IOException("Synthetic connection lost after the create"), null)
                        return true
                    }
                    val deferred = synchronized(delayedCreates) {
                        val holdEffort = method == "config.get" && params.string("key") == "reasoning" &&
                            params.string("session_id").isNotBlank() && holdNextReasoningRead.getAndSet(false)
                        if (holdEffort) reasoningReadHeld.countDown()
                        if (holdCreateAcknowledgement && method == "session.create" || holdPromptAcknowledgement && method == "prompt.submit" || method == "prompt.submit" && holdPromptOf != null && params.string("session_id") == holdPromptOf || holdSteerAcknowledgement && method == "session.steer" || holdReceiptAcknowledgement && method == "prompt.receipt" || holdActiveListAcknowledgement && method == "session.active_list" ||
                            holdEffort || holdConfigAcknowledgement && method == "config.set" || holdBranchAcknowledgement && method == "session.branch" || holdHiddenAcknowledgement && method == "session.set_hidden" ||
                            holdVaultListAcknowledgement && method == "vault.list") { delayedCreates += { listener.onMessage(this, response) }; heldSockets += listener to this; true } else false
                    }
                    if (!deferred) listener.onMessage(this, response)
                    if (!deferred && method == "session.events.since") {
                        eventSubscriptions.incrementAndGet()
                        val runtimeSessionId = params.string("session_id")
                        val reads = replayReads.computeIfAbsent(this) { AtomicInteger() }.incrementAndGet()
                        if (pendingNativeStreams.remove(runtimeSessionId)) {
                            activeNativeListener = listener
                            activeNativeSocket = this
                            activeNativeSocketId = socketId
                            activeNativeSessionId = runtimeSessionId
                            streamSubscriptions.incrementAndGet()
                        } else if (runtimeSessionId == activeNativeSessionId) {
                            // Like Hermes, live events go to the latest subscriber of the runtime (a reconnect re-subscribes on a new socket).
                            activeNativeListener = listener
                            activeNativeSocket = this
                            activeNativeSocketId = socketId
                        }
                        if (reads == 2 && runtimeSessionId == activeNativeSessionId) {
                            liveCollectorSocketId = socketId
                            liveCollectorSubscriptions.incrementAndGet()
                        }
                    }
                } catch (error: FixtureRpcFailure) {
                    // In the socket log, so a failure shows which socket (which caller) a refusal went to.
                    nativeSocketMethods.add(socketId to "REFUSED ${frame.optString("method")}")
                    val refusal = json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to json("code" to error.code, "message" to error.message).apply {
                        // Like Hermes' _refusal_data: the reason, plus the field when the refusal names one.
                        error.field?.let { put("data", json("reason" to error.message, "field" to it)) }
                    }).toString()
                    if (holdResumeRefusals && frame.optString("method") == "session.resume") heldRefusals += { listener.onMessage(this, refusal) }
                    // S1d: a held prompt's refusal waits for the release like its answer would.
                    else if (holdPromptAcknowledgement && frame.optString("method") == "prompt.submit") synchronized(delayedCreates) { delayedCreates += { listener.onMessage(this, refusal) }; heldSockets += listener to this }
                    else listener.onMessage(this, refusal)
                } catch (error: IllegalArgumentException) {
                    val missing = error.message == "Synthetic session missing"
                    // Like Hermes: the canonical authority refuses a session it does not hold with 4001 not_found (gateway/session_controls.py:372-374, hermes_state_runtime.py:75);
                    // only the legacy gateway says 4007 session not found (tui_gateway/methods_session.py:815).
                    val canonicalMissing = missing && canonicalAuthority
                    val failure = json("code" to if (canonicalMissing) 4001 else if (missing) 4007 else 404, "message" to if (canonicalMissing) "not_found" else if (missing) "session not found" else error.message)
                    if (canonicalMissing) failure.put("data", json("reason" to "not_found"))
                    listener.onMessage(this, json("jsonrpc" to "2.0", "id" to frame.getString("id"), "error" to failure).toString())
                }
                return true
            }
        }
        // Like a network that is down: the connection never opens, so nothing can have been sent.
        if (refuseConnection) { refusedConnections.incrementAndGet(); listener.onFailure(socket, java.net.ConnectException("Synthetic connection refused"), null) }
        else if (holdReady.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
            heldReadySockets.incrementAndGet()
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic wire").build())
        }
        else {
            listener.onOpen(socket, Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(101).message("Synthetic wire").build())
            listener.onMessage(socket, json("jsonrpc" to "2.0", "method" to "event", "params" to json("type" to "gateway.ready", "payload" to json("session_authority" to canonicalAuthority))).toString())
        }
        return socket
    }
    private fun rpc(method: String, params: JSONObject): JSONObject {
        check(params.string("profile") in if (profileIsolationBots) notificationProfiles else listOf("default", "coder"))
        if (profileIsolationBots && params.string("profile") in rejectedProfiles) throw FixtureRpcFailure(403, "Synthetic profile access denied")
        if (method != "session.resume" && params.string("session_id") in reapedRuntimes) throw FixtureRpcFailure(4001, "session not found")
        val id = params.string("session_id").let { runtimeStoredSessionIds[it] ?: it.removePrefix("runtime-") }
        return when (method) {
            "subagent.list" -> json("subagents" to JSONArray())
            "delegation.status" -> json("active" to JSONArray())
            "process.list" -> json("processes" to JSONArray())
            "session.list" -> json("sessions" to JSONArray(sessions.values.filter { !it.getJSONObject("info").optBoolean("archived") && (params.optBoolean("include_hidden") || !it.getJSONObject("info").optBoolean("hidden")) }.map { row -> row.getJSONObject("info").copy().put("id", row.string("stored_session_id")).put("stored_session_id", row.string("stored_session_id")) }))
            "projects.list" -> json("projects" to JSONArray(projects.values.toList()))
            // Like Hermes: the project owning a folder; no cwd means the profile's own (project-free) folder.
            "projects.for_cwd" -> if (projectLookupFailure) throw FixtureRpcFailure(5007, "Projects are unavailable") else params.string("cwd").ifBlank { "C:/synthetic-home" }.let { cwd -> json("cwd" to cwd, "project" to (projects.values.filter { project -> project.rows("folders").any { cwd.startsWith(it.string("path")) } }.maxByOrNull { project -> project.rows("folders").maxOfOrNull { it.string("path").length } ?: 0 } ?: JSONObject.NULL)) }
            "session.workspace.move" -> params.getString("session_key").let { key -> sessions.getValue(key).getJSONObject("info").put("cwd", params.getString("cwd")); json("session_key" to key, "cwd" to params.getString("cwd")) }
            "projects.get" -> json("project" to projects.getValue(params.getString("id")))
            "projects.create" -> {
                val key = "p_new_${sequence.incrementAndGet()}"
                val row = json("id" to key, "name" to params.getString("name"), "description" to params.string("description"), "slug" to params.string("slug"), "folders" to JSONArray())
                projects[key] = row; json("project" to row)
            }
            "projects.update" -> { val row = projects.getValue(params.getString("id")); row.put("name", params.getString("name")); json("project" to row) }
            "projects.delete" -> { projects.remove(params.getString("id")); json("projects" to JSONArray(projects.values.toList())) }
            "projects.add_folder" -> {
                val row = projects.getValue(params.getString("id")); val folders = row.getJSONArray("folders"); val path = params.getString("path")
                if (folders.length() == 0) row.put("primary_path", path)
                folders.put(json("path" to path, "is_primary" to (folders.length() == 0))); json("project" to row)
            }
            "session.cwd.set" -> { sessions.getValue(id).getJSONObject("info").put("cwd", params.getString("cwd")); json("cwd" to params.getString("cwd")) }
            "projects.tree" -> json("projects" to JSONArray().put(json("id" to "p_project_a", "label" to "Travel", "path" to "C:/synthetic-workspace", "sessionCount" to 2)), "scoped_session_ids" to JSONArray().put("chat-a").put("chat-b"))
            "projects.project_sessions" -> json("project" to json("id" to params.getString("project_id"), "label" to "Travel", "sessionIds" to JSONArray().put("chat-a").put("chat-b"), "previewSessions" to JSONArray().put(json("id" to "chat-a", "title" to "Travel planning", "profile" to scope.profile))))
            "session.resume" -> {
                if (id == storageUnavailableOnResume) throw FixtureRpcFailure(503, "storage_unavailable")
                require(!historyFailure) { "Synthetic history unavailable" }
                if (failingResumes.getAndUpdate { (it - 1).coerceAtLeast(0) } > 0) throw FixtureRpcFailure(resumeFailureCode, if (resumeFailureCode == 403) "forbidden" else "Synthetic resume unavailable")
                if (profileSessionIds[params.string("profile")]?.contains(id) == false) throw IllegalArgumentException("Synthetic session missing")
                val row = sessions[id] ?: unpromptedShells.remove(id)?.also { sessions[id] = it }
                row?.copy()?.apply {
                    val runtime = if (canonicalAuthority) id else resumeRuntimeSessionIds[id] ?: string("session_id")
                    put("session_id", runtime)
                    runtimeStoredSessionIds[runtime] = id
                    currentRuntimeSessionIds[id] = runtime
                    put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id); if (omitResumeYolo) getJSONObject("info").remove("yolo")
                    if (canonicalAuthority) {
                        remove("session_key"); getJSONObject("info").remove("stored_session_id")
                        // Like Hermes' canonical resume (gateway/session_controls.py): the snapshot always lists its open prompts, here none; a server request answers through the stream.
                        put("stored_session_id", id).put("execution_generation", 7).put("running", nativeTurnStatus in setOf("working", "waiting")).put("prompts", JSONArray(canonicalOpenPrompts.map { it.copy() }))
                        lostPending?.let { put("pending", JSONArray(it.toString())) }
                        getJSONObject("info").put("profile_name", params.string("profile")).apply {
                            if (omitCanonicalResumeProfile) remove("profile_id") else put("profile_id", canonicalProfileId(params.string("profile")))
                        }
                    }
                    put("messages_omitted", params.optBoolean("omit_messages"))
                    // Hermes' resume counts the chat's rows (gateway/session_controls.py:130/135); the reconcile after a cut-short replay requires it. Only the
                    // eviction cases (CHATFIX-121) get it, so every other case keeps its answer.
                    if (replayEvictedThrough > 0) put("message_count", rows("messages").size)
                    put("messages", JSONArray(if (params.optBoolean("omit_messages")) emptyList<JSONObject>() else rows("messages").map { message -> message.copy().apply {
                        put("text", nativeDisplayText(message, params.optBoolean("inline_images", true)))
                        remove("content"); remove("attachments")
                        if (has("id")) put("row_id", remove("id"))
                    } }))
                }
                    ?: throw IllegalArgumentException("Synthetic session missing")
            }
            "session.history" -> { require(!historyFailure) { "Synthetic history unavailable" }; sessions[id]?.copy()?.apply { put("session_key", id); remove("stored_session_id"); getJSONObject("info").put("stored_session_id", id) } ?: throw IllegalArgumentException("Synthetic session missing") }
            "session.info" -> {
                require(canonicalAuthority && sessions.containsKey(id)) { "Synthetic session missing" }
                json("source" to "android", "lazy" to true, "desktop_protocol" to "hermes-gateway-v1", "profile_name" to params.string("profile")).apply {
                    if (!omitCanonicalInfoProfile) put("profile_id", canonicalProfileId(params.string("profile")))
                }
            }
            "session.create" -> {
                // Like Hermes' canonical create: a key outside its create fields is refused (gateway/session_policy.py:15-19 and :429-431; the plugin consumes `profile`,
                // the gateway takes `title` and `hidden` first), and an exact retry (the same request_id) returns the session the first create made (gateway/session_local.py:333-340).
                if (canonicalAuthority) params.keys().asSequence().firstOrNull { it !in HERMES_CREATE_FIELDS }?.let { throw FixtureRpcFailure(4001, "invalid_params") }
                // ...and a source that is not one of its surfaces (gateway/session_policy.py:430, SURFACES): the thread source is refused there, which is what the real gateway does.
                if (canonicalAuthority && params.has("source") && params.string("source") !in setOf("cli", "tui", "gui", "acp", "tool", "oneshot")) throw FixtureRpcFailure(4001, "invalid_params")
                val request = if (canonicalAuthority) params.string("request_id") else ""
                if (request.isNotBlank() && request in retiredRequests) throw FixtureRpcFailure(4001, "not_found")
                val made = request.takeIf { it.isNotBlank() }?.let { createdByRequest[it] }?.takeIf { sessions.containsKey(it) }
                // A resumed request_id must carry the same frozen request, else invalid_params (gateway/session_local.py:340-343): the retry's fields equal the first create's.
                if (made != null) createFieldsByRequest[request]?.let { first ->
                    if (first.keys().asSequence().toSet() != params.keys().asSequence().toSet() || first.keys().asSequence().any { first.opt(it).toString() != params.opt(it).toString() }) throw FixtureRpcFailure(4001, "invalid_params")
                }
                // Hermes' create ends with `return await self.resume(ref, {})` (gateway/session_controls.py, `_create`): the canonical answer is the session's resume snapshot.
                fun answer(key: String, row: JSONObject) = if (canonicalAuthority) rpc("session.resume", json("session_id" to key, "profile" to params.string("profile"), "omit_messages" to false)) else row
                if (made != null) answer(made, sessions.getValue(made))
                else { val key = "new-${sequence.incrementAndGet()}"; answer(key, history(key, if (dropCreateTitle) "" else params.string("title", "New conversation"), JSONArray()).also { row ->
                    row.getJSONObject("info").put("source", params.string("source")).put("model", params.string("model", profileModel))
                    runtimeStoredSessionIds[row.string("session_id")] = key
                    currentRuntimeSessionIds[key] = row.string("session_id")
                    sessions[key] = row
                    if (request.isNotBlank()) { createdByRequest[request] = key; createFieldsByRequest[request] = JSONObject(params.toString()) }
                }) }
            }
            "session.events.since" -> {
                val lastSeen = params.optLong("last_seen", 0)
                // Like Hermes' since(): a cursor the ring moved past gets no frames and is told to read a snapshot (only when a test evicted the ring).
                val evicted = replayEvictedThrough > 0 && lastSeen < replayEvictedThrough
                if (evicted) truncatedReplays += lastSeen
                json("epoch" to "synthetic", "latest_seq" to nativeEventSequence.get(),
                    "events" to JSONArray(if (evicted) emptyList() else nativeEvents.filter { it.optLong("seq") > lastSeen }),
                    "open_requests" to JSONArray(openNativeServerRequests.values.filter { it.optJSONObject("params")?.string("session_id") == params.string("session_id") }))
                    .apply { if (evicted) put("truncated", true).put("snapshot_required", true).put("last_sequence", nativeEventSequence.get()).put("replay_epoch", "synthetic").put("count", 0) }
            }
            "session.active_list" -> if (params.optString("current_session_id").isBlank() && legacyInventory != null) {
                json("sessions" to legacyInventory)
            } else if (params.optString("current_session_id").isBlank() && unseenTurns.isNotEmpty()) {
                if (nativeStatusFailure) throw IllegalArgumentException("Synthetic active status unavailable")
                // The inventory read (no current session), as Hermes lists every live session: each turn it holds unseen, with the one turn status.
                json("sessions" to JSONArray(unseenTurns.filter(sessions::containsKey).map { stored -> json("id" to unseenRuntime(stored), "session_key" to stored, "status" to nativeTurnStatus) }))
            } else {
                if (nativeStatusFailure) { nativeStatusProbes.add("failed"); throw IllegalArgumentException("Synthetic active status unavailable") }
                val observedStatus = nativeTurnStatus
                val runtime = params.optString("current_session_id")
                terminalOnActiveList?.takeIf { runtime.isNotBlank() }?.let { payload ->
                    terminalOnActiveList = null
                    retainNativeEvent(runtime, "message.complete", payload)
                    nativeTurnStatus = "idle"
                }
                val stored = runtimeStoredSessionIds[runtime] ?: runtime.takeIf { it.startsWith("runtime-") }?.removePrefix("runtime-")
                val rows = JSONArray()
                if (stored != null && sessions.containsKey(stored) && currentRuntimeSessionIds[stored]?.let { it == runtime } != false) rows.put(json("id" to runtime, "session_key" to stored, "status" to observedStatus))
                nativeStatusProbes.add(observedStatus)
                json("sessions" to rows)
            }
            "session.usage" -> json("input_tokens" to 100, "output_tokens" to 20, "context_window" to 32000)
            // Like Hermes for a live session: the agent reports "github-copilot" and the "copilot" row is the one marked current.
            "model.options" -> json("model" to profileModel, "provider" to "github-copilot", "providers" to JSONArray()
                .put(json("slug" to "other", "name" to "Other provider", "is_current" to false, "models" to JSONArray(listOf("synthetic-model"))))
                .put(json("slug" to "copilot", "name" to "GitHub Copilot", "is_current" to true, "models" to JSONArray(listOf("synthetic-model")))))
            "session.title" -> {
                // Hermes titles are unique per profile (idx_sessions_title_unique).
                renameFailure?.let { throw FixtureRpcFailure(500, it) }
                if (sessions.any { (key, row) -> key != id && row.getJSONObject("info").string("title") == params.getString("title") }) { refusedTitles++; throw FixtureRpcFailure(4022, "Title already in use") }
                sessions.getValue(id).getJSONObject("info").put("title", params.getString("title")); json("ok" to true)
            }
            "session.set_hidden" -> { val hidden = params.getBoolean("hidden"); sessions.getValue(id).getJSONObject("info").put("hidden", hidden); json("hidden" to hidden, "session_key" to id) }
            "session.archive" -> { val archived = params.getBoolean("archived"); sessions.getValue(id).getJSONObject("info").put("archived", archived); json("archived" to archived, "session_key" to id) }
            "session.close" -> { closedSessions.add(id); json("closed" to true) }
            "session.delete" -> {
                require(id in closedSessions) { "cannot delete an active session" }
                if (deleteMissingAfterClose) { sessions.remove(id); retireRequestsOf(id); throw FixtureRpcFailure(4007, "session not found") }
                sessions.remove(id); retireRequestsOf(id); json("deleted" to id)
            }
            "session.branch" -> { val key = "branch-${sequence.incrementAndGet()}"; val rows = sessions.getValue(id).getJSONArray("messages"); history(key, params.string("name", "Branch"), JSONArray((0 until params.optInt("count", rows.length()).coerceIn(0, rows.length())).map(rows::get))).also { sessions[key] = it } }
            "session.compress" -> json("status" to "aborted")
            "session.undo" -> { sessions.getValue(id).put("messages", JSONArray()); json("ok" to true) }
            "config.get" -> if (params.string("key") == "provider") json("model" to profileModel, "provider" to "fixture")
                else json("value" to when (params.string("key")) { "approvals.mode" -> if (profileIsolationBots) isolationApprovals.getValue(params.string("profile")) else approvalMode; "reasoning" -> params.string("session_id").takeIf(String::isNotBlank)?.let { sessionReasoning[id] } ?: reasoningEffort; else -> reasoningEffort })
            "config.set" -> {
                val key = params.string("key")
                val value = params.string("value")
                if (key == "model") {
                    val requested = value
                    val model = requested.substringBefore(" --provider ")
                    if (model == "confirm-model" && !params.optBoolean("confirm_expensive_model")) return json("key" to "model", "value" to model, "confirm_required" to true, "confirm_message" to "This selected model requires your confirmation.")
                    sessions.getValue(id).getJSONObject("info").put("model", model).put("provider", requested.substringAfter(" --provider ", "fixture"))
                    return json("key" to "model", "value" to model, "confirm_required" to false)
                }
                if (key == "reasoning") {
                    if (params.string("session_id").isBlank()) reasoningEffort = value else sessionReasoning[id] = value
                    if (persistReasoningInRow && params.string("session_id").isNotBlank()) {
                        sessions.getValue(id).getJSONObject("info").put("reasoning_effort", value)
                        sessions.getValue(id).put("model_config", JSONObject(sessions.getValue(id).optString("model_config").ifBlank { "{}" }).put("reasoning_config", json("enabled" to (value != "none"), "effort" to value)).toString())
                    }
                }
                check(value !in setOf("show", "hide"))
                if (key == "approvals.mode") {
                    require(!rejectApprovalWrite) { "Synthetic approval write refused" }
                    require(value in nativeApprovalModes)
                    if (profileIsolationBots) isolationApprovals[params.string("profile")] = value else approvalMode = value
                }
                if (key == "yolo") {
                    // Like Hermes: the live flag and the saved model_config.yolo_mode change together.
                    val on = value in setOf("1", "on", "true")
                    val row = sessions.getValue(id)
                    row.getJSONObject("info").put("yolo", on)
                    row.put("model_config", JSONObject(row.optString("model_config").ifBlank { "{}" }).put("yolo_mode", on).toString())
                }
                json("ok" to true, "key" to key, "value" to if (key == "yolo") if (value in setOf("1", "on", "true")) "1" else "0" else value)
            }
            "approval.pending" -> json("approvals" to JSONArray())
            "command.dispatch" -> commandDispatchResult?.copy() ?: throw FixtureRpcFailure(4018, "not a quick/plugin/bundle/skill command: ${params.string("name")}")
            "commands.catalog" -> {
                require(!commandCatalogFailure) { "Synthetic command catalog unavailable" }
                json("pairs" to JSONArray().apply { repeat(commandDistractors) { put(JSONArray().put("/candidate$it").put("The help text describes another command")) } }.put(JSONArray().put("/help").put("Show available commands")).put(JSONArray().put("/model").put("Choose a model")).apply { extraCommands.forEach { (name, description) -> put(JSONArray().put(name).put(description)) } })
            }
            "prompt.submit" -> {
                failNextPrompt?.let { failNextPrompt = null; throw FixtureRpcFailure(it.first, it.second) }
                if (canonicalAuthority) {
                    val submission = params.string("submission_id")
                    canonicalSubmits.computeIfAbsent(submission) { AtomicInteger() }.incrementAndGet()
                    // Follow-up 5: the same request id with the same payload answers with the admission Hermes holds; another payload is refused.
                    val digest = params.keys().asSequence().filter { it != "session_id" && it != "profile" }.sorted().joinToString("\u0000") { "$it=${params.opt(it)}" }
                    canonicalSubmissions["$id|$submission"]?.let { (held, admission) ->
                        if (held != digest) throw FixtureRpcFailure(4001, "admission_conflict", "request_id")
                        return canonicalAdmissions.getValue(admission).copy()
                    }
                }
                val rowId = sequence.incrementAndGet()
                if (canonicalAuthority) {
                    require(params.string("submission_id").isNotBlank()) { "Canonical submission identity is required" }
                    // Like Hermes (hermes_state_runtime.admit_session_input): every fresh submit is answered "queued", an idle chat too. A chat nothing else
                    // runs claims the row at once (the turn starts and its frames carry the admission); one held behind another turn waits for a test to start it.
                    // Like Hermes (claim_session_input refuses unknown_execution): a submit while a lost turn holds the chat is queued behind it.
                    val behindLost = holdsLostTurn()
                    val behindAnotherTurn = queueNextCanonicalSubmission.getAndSet(false) || behindLost
                    val admission = "admission-$rowId"
                    val result = json("admission_id" to admission, "ref" to json("profile_id" to canonicalProfileId(params.string("profile")), "session_id" to id),
                        "sequence" to rowId, "status" to "queued", "outcome" to null, "authority_epoch" to 1, "execution_generation" to null)
                    canonicalAdmissions[admission] = result.copy()
                    canonicalSubmissions["$id|${params.string("submission_id")}"] = params.keys().asSequence().filter { it != "session_id" && it != "profile" }.sorted()
                        .joinToString("\u0000") { "$it=${params.opt(it)}" } to admission
                    canonicalAdmissionInputs[admission] = Triple(id, params.string("submission_id"), params.string("text"))
                    submitAnswers += result.string("status")
                    if (behindLost) lostPending = JSONArray(lostPending.toString()).put(json("admission_id" to admission, "status" to "queued", "outcome" to JSONObject.NULL,
                        "execution_generation" to JSONObject.NULL, "sequence" to rowId, "authority_epoch" to 1, "input_id" to admission, "text" to params.string("text")))
                    if (behindAnotherTurn) { if (nativeTurnStatus != "working") nativeTurnStatus = "starting" } else startCanonicalTurn(admission)
                    result
                } else {
                    nativeTurnStatus = "working"
                    if (persistTerminalMessages) sessions[id]?.optJSONArray("messages")?.put(json("role" to "user", "text" to params.string("text"), "row_id" to rowId))
                    json("status" to "streaming", "user_row_id" to rowId)
                }
            }
            "session.steer" -> { steerFailureCode?.let { throw FixtureRpcFailure(it, "Synthetic steer refusal") }; json("status" to steerStatus, "accepted" to (steerStatus != "rejected")) }
            "prompt.receipt" -> (canonicalAdmissions[params.string("admission_id")]?.copy()?.also {
                require(it.getJSONObject("ref").string("session_id") == id) { "Synthetic admission belongs to another session" }
            } ?: json("status" to admissionReceiptStatus, "outcome" to admissionReceiptOutcome, "admission_id" to params.string("admission_id"), "ref" to json("profile_id" to canonicalProfileId(params.string("profile")), "session_id" to id))).apply {
                    admissionReceiptIdOverride?.let { put("admission_id", it) }
                    val ref = getJSONObject("ref")
                    admissionReceiptRefOverride?.let { ref.put("session_id", it) }
                    admissionReceiptProfileOverride?.let { ref.put("profile_id", it) }
                }
            "session.interrupt" -> if (!canonicalAuthority) json("ok" to true) else {
                // Like Hermes' canonical interrupt: the session's handle, naming the generation it stopped.
                val runtime = params.string("session_id")
                json("ref" to json("profile_id" to canonicalProfileId(params.string("profile").ifBlank { scope.profile }), "session_id" to (runtimeStoredSessionIds[runtime] ?: runtime)),
                    "execution_generation" to params.opt("execution_generation"), "execution_state" to "running")
            }
            // Like Hermes (hermes_state_runtime.resolve_unknown_session_input): only the lost row's own generation lets it go; it ends interrupted.
            // Like Hermes' canonical prompt answer: the open prompt is settled once; a second answer finds it already resolved.
            "approval.respond", "clarify.respond" -> {
                val id = params.string("prompt_id")
                val open = canonicalOpenPrompts.firstOrNull { it.string("prompt_id") == id }
                if (open != null) { canonicalOpenPrompts.remove(open); respondedPrompts += id to params.string("choice", params.string("answer")) }
                json("prompt_id" to id, "status" to if (open != null) "resolved" else "already_resolved")
            }
            "prompt.resolve_unknown" -> {
                if (refuseLostClose) throw FixtureRpcFailure(4003, "permission_denied")
                val rows = lostPending?.let { (0 until it.length()).map(it::getJSONObject) }.orEmpty()
                val lost = rows.firstOrNull { it.string("admission_id") == params.string("admission_id") && it.string("status") == "unknown" &&
                    it.optLong("execution_generation", -1) == params.optLong("execution_generation", -2) } ?: throw FixtureRpcFailure(4001, "stale_generation")
                resolvedLost += lost.string("admission_id")
                // Like Hermes (resolve_unknown resumes the paused FIFO): a message queued behind it starts now.
                val rest = rows.filter { it !== lost }
                val next = rest.firstOrNull { it.string("status") == "queued" }
                lostPending = JSONArray(rest.filter { it !== next }).takeIf { it.length() > 0 }
                next?.let { startCanonicalTurn(it.string("admission_id")) }
                lost.copy().put("status", "terminal").put("outcome", "interrupted")
            }
            "vault.list", "vault.sources", "vault.source.set", "vault.unlock", "vault.lock", "vault.add", "vault.remove" -> vaultRpc(method, params)
            // CHATQA-121 follow-up 5: like Hermes' image.attach_bytes, the bytes are staged under a path of their own, a new one each call.
            "image.attach_bytes" -> json("attached" to true, "path" to "/synthetic/staged/image-${sequence.incrementAndGet()}.png", "mime" to "image/png")
            "image.detach" -> json("detached" to true)
            else -> throw IllegalArgumentException("Synthetic fixture has no response for $method")
        }.also { reply ->
            // The legacy inventory includes idle in-memory drafts even though neither REST store read can see them.
            if (method == "session.active_list" && params.string("current_session_id").isBlank() && unpromptedShells.isNotEmpty()) {
                val liveDrafts = unpromptedShells.map { (stored, shell) ->
                    json("id" to (currentRuntimeSessionIds[stored] ?: shell.string("session_id")), "session_key" to stored, "status" to "idle")
                }
                reply.put("sessions", JSONArray((reply.rows("sessions") + liveDrafts).distinctBy { it.string("id") }))
            }
        }
    }
}
