package dev.hermes.android.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Email
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.Scope
import dev.hermes.android.core.json
import dev.hermes.android.core.string
import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The Hermes tool that shows an email as a card; the chat draws its step as the card (an older app shows the ordinary tool step). */
internal const val EMAIL_DRAFT_TOOL = "beacon_email_draft"
internal const val EMAIL_DRAFT_PATH = "/api/plugins/beacon/mobile/email-drafts"
/** How often a card that is sending asks Hermes how it went. */
internal const val DRAFT_POLL_MILLIS = 1_500L
private val draftIdPattern = Regex("[0-9a-f]{32}")
private val draftIdInResult = Regex("draft_id\\W{1,6}([0-9a-f]{32})")

/** An email as Hermes holds it: the fields on the card, which version of them this is, and how its sending is going. */
internal data class EmailDraft(
    val id: String,
    val version: Int = 1,
    val versions: Int = 1,
    val position: Int = 1,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val body: String = "",
    /** draft, sending, sent, failed or unsure (sent without being sure of it). */
    val status: String = "draft",
    val reason: String = "",
    /** tool (Hermes' mail tool is called for the owner), hermes (Hermes sends it itself) or empty (no way to send). */
    val via: String = "",
    val label: String = "",
    val canSend: Boolean = true,
    val unchanged: Boolean = false,
) {
    val hasPrevious get() = position > 1
    val hasNewer get() = position < versions

    /** The email as plain text, for the clipboard when the profile has no way to send it. */
    val copyText: String get() = buildString {
        append("To: ").append(to.joinToString(", "))
        if (cc.isNotEmpty()) append("\nCc: ").append(cc.joinToString(", "))
        append("\nSubject: ").append(subject).append("\n\n").append(body)
    }

    companion object {
        private fun JSONArray?.texts(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { (opt(it) as? String)?.takeIf(String::isNotBlank) }

        fun parse(response: JSONObject): EmailDraft = EmailDraft(
            id = response.string("id"),
            version = response.optInt("version", 1).coerceAtLeast(1),
            versions = response.optInt("versions", 1).coerceAtLeast(1),
            position = response.optInt("position", 1).coerceAtLeast(1),
            to = response.optJSONArray("to").texts(), cc = response.optJSONArray("cc").texts(),
            subject = response.string("subject"), body = response.string("body"),
            status = response.string("status", "draft").takeIf { it in setOf("draft", "sending", "sent", "failed", "unsure") } ?: "draft",
            reason = response.string("reason"), via = response.string("via"), label = response.string("label"),
            canSend = if (response.has("can_send")) response.optBoolean("can_send") else true,
            unchanged = response.optBoolean("unchanged"),
        )

        /** The card as the model wrote it in the tool call, shown while Hermes' own copy loads. */
        fun seed(id: String, arguments: Any?): EmailDraft {
            val fields = when (arguments) {
                is JSONObject -> arguments
                is String -> runCatching { JSONObject(arguments) }.getOrNull()
                else -> null
            } ?: return EmailDraft(id)
            return EmailDraft(id, to = fields.optJSONArray("to").texts(), cc = fields.optJSONArray("cc").texts(), subject = fields.string("subject"), body = fields.string("body"))
        }
    }
}

/** The id of the draft a `beacon_email_draft` result names, or null when it was refused (the result then carries an error and no id). */
internal fun emailDraftId(result: String?): String? = result?.let { draftIdInResult.find(it)?.groupValues?.get(1) }

/** What the app asks of Hermes' draft routes. */
internal interface EmailDraftApi {
    suspend fun read(id: String): JSONObject
    suspend fun revise(id: String, instruction: String, version: Int): JSONObject
    /** The version [step] places (-1 or 1) from [version], which is the one on the card. */
    suspend fun step(id: String, version: Int, step: Int): JSONObject
    suspend fun send(id: String, version: Int): JSONObject
}

/** The routes of Beacon's plugin, with the signed-in profile's own client. */
internal class ClientEmailDraftApi(private val source: () -> BeaconClient?, private val profile: String) : EmailDraftApi {
    private suspend fun request(id: String, action: String, body: JSONObject? = null): JSONObject {
        require(id.matches(draftIdPattern)) { "Invalid draft" }
        val client = (source() ?: throw IllegalStateException("Reconnect first.")).forAuthorizedProfile(profile)
        try { return client.nativeRequest("$EMAIL_DRAFT_PATH/$id$action", if (body == null) "GET" else "POST", body) }
        finally { client.closeNative() }
    }
    override suspend fun read(id: String) = request(id, "")
    override suspend fun revise(id: String, instruction: String, version: Int) = request(id, "/revise", json("instruction" to instruction, "version" to version))
    override suspend fun step(id: String, version: Int, step: Int) = request(id, "/select", json("version" to version, "step" to step))
    override suspend fun send(id: String, version: Int) = request(id, "/send", json("version" to version))
}

/** What the owner is waiting for on a card: Hermes redrafting it, or the tap on Send being taken. */
internal enum class DraftWork { None, Revising, Sending }

/** Why the last tap did not work, as a code the card words itself (the app owns the sentences). */
internal enum class DraftProblem { None, Redraft, Send, Changed, NoWay, Offline, Nothing }

internal data class EmailDraftState(val draft: EmailDraft, val loaded: Boolean = false, val work: DraftWork = DraftWork.None, val problem: DraftProblem = DraftProblem.None)

/** How a send that Hermes does itself reaches Hermes: the owner's own chat message, which only a free composer can carry. */
internal class HermesAsk(val available: () -> Boolean, val say: (String) -> Unit, val message: (String) -> String)

/**
 * One card's state and what its buttons do. It is kept by [EmailDraftStore], not by the screen, so a card scrolled away and back
 * is already as it was; Hermes' own copy is read again each time and replaces what the phone holds.
 */
internal class EmailDraftModel(val id: String, private val api: EmailDraftApi, private val scope: CoroutineScope, seed: EmailDraft) {
    private val _state = MutableStateFlow(EmailDraftState(seed))
    val state: StateFlow<EmailDraftState> = _state.asStateFlow()

    private fun adopt(draft: EmailDraft, problem: DraftProblem = DraftProblem.None) =
        _state.update { it.copy(draft = draft, loaded = true, work = DraftWork.None, problem = problem) }

    private fun fail(problem: DraftProblem) = _state.update { it.copy(work = DraftWork.None, problem = problem) }

    private fun problemOf(failure: Throwable, fallback: DraftProblem): DraftProblem = when {
        failure is ApiFailure && failure.status == 409 -> DraftProblem.Changed
        failure is ApiFailure && failure.status == 422 -> DraftProblem.NoWay
        failure is ApiFailure && failure.status in 400..599 -> fallback
        else -> DraftProblem.Offline
    }

    /** Hermes' copy of the card; ignored when the owner has started something meanwhile, so it cannot undo their tap. */
    suspend fun refresh() {
        if (_state.value.work != DraftWork.None) return
        try {
            val draft = EmailDraft.parse(api.read(id))
            if (_state.value.work == DraftWork.None) _state.update { it.copy(draft = draft, loaded = true, problem = if (draft.status == "draft") it.problem else DraftProblem.None) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Throwable) { /* the card stays as it was; the next look tries again */ }
    }

    /** Asks Hermes how a send is going until it is settled. A failed look only waits for the next one. */
    suspend fun watch() {
        while (_state.value.draft.status == "sending") {
            delay(DRAFT_POLL_MILLIS)
            refresh()
        }
    }

    private fun begin(work: DraftWork): Boolean {
        var started = false
        _state.update { current -> if (current.work == DraftWork.None && current.loaded && current.draft.status in setOf("draft", "failed", "unsure")) { started = true; current.copy(work = work, problem = DraftProblem.None) } else current }
        return started
    }

    fun revise(instruction: String): Job? {
        val words = instruction.trim()
        if (words.isEmpty() || !begin(DraftWork.Revising)) return null
        val version = _state.value.draft.version
        return scope.launch {
            try {
                val next = EmailDraft.parse(api.revise(id, words, version))
                adopt(next, if (next.unchanged) DraftProblem.Nothing else DraftProblem.None)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                fail(problemOf(failure, DraftProblem.Redraft))
                if (failure is ApiFailure && failure.status == 409) refresh()
            }
        }
    }

    /** Moves to a version Hermes kept: the one before this, or the newer one stepped back from. */
    fun step(forward: Boolean): Job? {
        val current = _state.value.draft
        if (forward && !current.hasNewer || !forward && !current.hasPrevious || !begin(DraftWork.Revising)) return null
        return scope.launch {
            try { adopt(EmailDraft.parse(api.step(id, current.version, if (forward) 1 else -1))) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                fail(problemOf(failure, DraftProblem.Changed))
                if (failure is ApiFailure && failure.status == 409) refresh()
            }
        }
    }

    /** The owner's tap on Send. When Hermes has to do the sending itself, the same tap asks it to, in the chat. */
    fun send(ask: HermesAsk): Job? {
        val before = _state.value.draft
        if (before.via == "hermes" && !ask.available()) { fail(DraftProblem.Send); return null }
        if (!begin(DraftWork.Sending)) return null
        return scope.launch {
            try {
                val next = EmailDraft.parse(api.send(id, before.version))
                adopt(next)
                if (next.status == "sending" && next.via == "hermes") ask.say(ask.message(next.subject))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Throwable) {
                if (failure is ApiFailure && failure.status == 422) _state.update { it.copy(draft = it.draft.copy(canSend = false, via = ""), work = DraftWork.None, problem = DraftProblem.NoWay) }
                else fail(problemOf(failure, DraftProblem.Send))
                if (failure is ApiFailure && failure.status == 409) refresh()
            }
        }
    }
}

/** The cards of this app run, by account, profile and draft, so a card that leaves the screen keeps its state. */
internal class EmailDraftStore(private val scope: CoroutineScope) {
    private val models = LinkedHashMap<String, EmailDraftModel>()

    @Synchronized fun model(accountKey: String, id: String, seed: EmailDraft, api: () -> EmailDraftApi): EmailDraftModel {
        val key = "$accountKey|$id"
        models[key]?.let { return it }
        if (models.size >= 64) models.remove(models.keys.first())
        return EmailDraftModel(id, api(), scope, seed).also { models[key] = it }
    }
}

/** The words a card shows for a problem or a state; [Composable] because every text goes through the phone's language. */
@Composable private fun draftProblemText(problem: DraftProblem, draft: EmailDraft): String = when {
    problem == DraftProblem.Redraft -> uiText("Couldn't redraft. Try again.")
    problem == DraftProblem.Send -> uiText("Send or clear your message first.")
    problem == DraftProblem.Changed -> uiText("This draft changed. Review it and try again.")
    problem == DraftProblem.NoWay -> uiText("Hermes can't send email here.")
    problem == DraftProblem.Offline -> uiText("Couldn't reach Hermes. Try again.")
    problem == DraftProblem.Nothing -> uiText("Nothing changed")
    draft.status == "failed" && draft.reason == "approval" -> uiText("Hermes needs your approval to send this. Approve it in Hermes, then try again.")
    draft.status == "failed" -> uiText("Couldn't send. Try again.")
    draft.status == "unsure" -> uiText("Couldn't confirm it was sent. Check your Sent folder before sending again.")
    !draft.canSend -> uiText("Hermes can't send email here.")
    else -> ""
}

/**
 * The email draft card of a chat: To, Cc, Subject and the body as a document, a field to say what to change (the card redrafts in place),
 * and Send. [model] holds its state; [onCopy] is used when the profile has no way to send email.
 */
@Composable internal fun EmailDraftCard(model: EmailDraftModel, ask: HermesAsk, onCopy: (String) -> Unit, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState()
    val draft = state.draft
    LaunchedEffect(model) { model.refresh() }
    LaunchedEffect(model, draft.status == "sending") { if (draft.status == "sending") model.watch() }
    var reviseText by rememberSaveable(model.id) { mutableStateOf("") }
    val uiScope = rememberCoroutineScope()
    val phase = when {
        state.work == DraftWork.Revising -> DraftPhase.Revising
        state.work == DraftWork.Sending || draft.status == "sending" -> DraftPhase.Confirming
        draft.status == "sent" -> DraftPhase.Done
        else -> DraftPhase.Editing
    }
    val sendable = draft.canSend
    val confirmLabel = uiText(when (draft.status) { "failed" -> "Try again"; "unsure" -> "Send again"; else -> "Send email" })
    val versionText = if (draft.versions > 1) uiText("{0} of {1}").replace("{0}", "${draft.position}").replace("{1}", "${draft.versions}") else null
    DraftCard(
        title = uiText("Email draft"), icon = Icons.Rounded.Email,
        fields = listOf(DraftCardField("to", uiText("To"), draft.to.joinToString(", ")), DraftCardField("cc", uiText("Cc"), draft.cc.joinToString(", ")), DraftCardField("subject", uiText("Subject"), draft.subject)),
        body = draft.body, phase = phase, ready = state.loaded,
        reviseText = reviseText, onReviseText = { reviseText = it.take(500) }, reviseHint = uiText("What to change?"), reviseLabel = uiText("Redraft"),
        onRevise = {
            // The owner's words stay in the field until Hermes has redrafted, so a failed redraft can be tried again as it was.
            val words = reviseText
            model.revise(words)?.let { job -> uiScope.launch { job.join(); if (state.problem in setOf(DraftProblem.None, DraftProblem.Nothing) && reviseText == words) reviseText = "" } }
        },
        confirmLabel = confirmLabel, onConfirm = if (sendable) ({ model.send(ask) }) else null,
        workingText = uiText(if (phase == DraftPhase.Revising) "Redrafting…" else "Sending…"),
        doneText = uiText("Sent") + if (draft.label.isNotBlank() && draft.via == "tool") " · ${draft.label}" else "",
        problem = draftProblemText(state.problem, draft),
        versionText = versionText, previousLabel = uiText("Previous version"), nextLabel = uiText("Newer version"),
        onPrevious = if (draft.hasPrevious) ({ model.step(false) }) else null, onNext = if (draft.hasNewer) ({ model.step(true) }) else null,
        copyLabel = uiText("Copy"), onCopy = if (!sendable) ({ onCopy(draft.copyText) }) else null,
        showMoreLabel = uiText("Show more"), showLessLabel = uiText("Show less"),
        modifier = modifier.fillMaxWidth(), key = model.id,
    )
}

/** The card in a chat: [vm] keeps its state and sends the owner's taps with the profile of [scope]; Hermes sends it, or the owner's own chat message asks it to. */
@Composable internal fun EmailDraftInChat(seed: EmailDraft, vm: BeaconViewModel, scope: Scope, onCopy: (String) -> Unit) {
    val account = scope.copy(session = "")
    val model = remember(account.key, seed.id) { vm.emailDrafts.model(account.key, seed.id, seed) { ClientEmailDraftApi({ vm.client }, account.profile) } }
    val template = uiText("Send the email “{0}”")
    val ask = remember(vm, template) { HermesAsk(vm::canSendAsOwner, vm::sendAsOwner) { subject -> template.replace("{0}", subject.take(60)) } }
    EmailDraftCard(model, ask, onCopy)
}
