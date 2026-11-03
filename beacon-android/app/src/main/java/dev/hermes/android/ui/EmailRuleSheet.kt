package dev.hermes.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.BeaconClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The two verdicts an owner can give an alert; an importance rule carries one of them as its `action`. */
internal val importanceActions = setOf("important", "not_important")

internal const val EMAIL_RULE_LIMIT = 300
/** How many lines a rule's words may take in the rules list: a rule can be 300 characters, and the list is where the owner reads it whole. */
internal const val RULE_TEXT_LINES = 6
internal const val EARLIER_RULES_LIMIT = 5

/** The alert the owner is rating and the verdict they tapped. */
internal data class EmailRuleRequest(val noticeId: String, val verdict: String)

/** What Hermes builds from an alert: who it is from, what it is about, and its own plain words as a fallback. */
internal data class EmailRuleSuggestion(val sender: String = "", val topic: String = "", val rule: String = "") {
    /** The rule in the app's language: "From <sender>", else "About <topic>", else Hermes' own words. */
    fun text(from: String, about: String): String = when {
        sender.isNotBlank() -> from.replace("{0}", sender)
        topic.isNotBlank() -> about.replace("{0}", topic)
        else -> rule
    }

    companion object {
        fun from(response: JSONObject) = EmailRuleSuggestion(response.string("sender").trim(), response.string("topic").trim(), response.string("rule").trim())
    }
}

/** A rule Hermes proposes from the owner's earlier ratings; the owner reviews it before it is saved. */
internal data class EarlierRule(val verdict: String, val source: String, val suggestion: EmailRuleSuggestion)

/** Up to five proposed rules from the status Hermes sent; anything not a known verdict or source is left out. */
internal fun proactiveLearnedProposal(proactive: JSONObject): List<EarlierRule> =
    proactive.rows("learned_proposal").mapNotNull { row ->
        val verdict = row.string("verdict")
        val source = row.string("source")
        if (verdict !in importanceActions || source.isBlank()) null else EarlierRule(verdict, source, EmailRuleSuggestion.from(row))
    }.take(EARLIER_RULES_LIMIT)

/** Reads the suggestion for an alert, with the signed-in profile's own client. */
internal suspend fun fetchEmailRuleSuggestion(source: BeaconClient, profile: String, request: EmailRuleRequest): JSONObject {
    val client = source.forAuthorizedProfile(profile)
    return try {
        client.get("$PROACTIVE_SETTINGS_PATH/suggestion", mapOf("profile" to profile, "notice_id" to request.noticeId, "verdict" to request.verdict))
    } finally { client.closeNative() }
}

/** Sends the owner's choice for one alert; with [rule] it also saves that rule, without it only the alert is marked. */
internal suspend fun sendEmailChoice(source: BeaconClient, profile: String, request: EmailRuleRequest, rule: String?) {
    val client = source.forAuthorizedProfile(profile)
    try {
        client.call("$PROACTIVE_SETTINGS_PATH/feedback", mapOf("profile" to profile), method = "POST",
            data = json("notice_id" to request.noticeId, "verdict" to request.verdict).apply { if (rule != null) put("rule", rule) })
    } finally { client.closeNative() }
}

@Composable private fun verdictLabel(verdict: String) = uiText(if (verdict == "important") "Important" else "Not important")

/** Whether a failed save was Hermes saying the rules list is full (its message names the limit). */
internal fun ruleLimitReached(failure: Throwable): Boolean = failure.message.orEmpty().contains("limit", ignoreCase = true)

/**
 * One tap on Important or Not important, then this: the rule Hermes suggests for the alert, ready to edit. "Save rule" keeps it in the
 * owner's rules; "Just this email" marks only this alert. [suggestion] is null while it loads, and the owner can already type or choose.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EmailRuleSheet(
    verdict: String,
    suggestion: EmailRuleSuggestion?,
    busy: Boolean,
    error: String,
    onSave: (String) -> Unit,
    onJustThisEmail: () -> Unit,
    onDismiss: () -> Unit,
) {
    val from = uiText("Sent by {0}")
    val about = uiText("About {0}")
    var edited by remember { mutableStateOf<String?>(null) }
    val text = edited ?: suggestion?.text(from, about).orEmpty()
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("email-rule-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiText(if (verdict == "important") "Mark as important" else "Mark as not important"), Modifier.testTag("email-rule-title"), style = MaterialTheme.typography.titleLarge)
            // The rule Hermes suggests is on its way; the owner can already type or choose, so this is a thin bar and not a wait.
            if (suggestion == null) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("email-rule-loading"))
            OutlinedTextField(text, { edited = it.take(EMAIL_RULE_LIMIT) }, Modifier.fillMaxWidth().testTag("email-rule-text"), enabled = !busy,
                label = { Text(uiText("Your rule")) }, maxLines = 4, isError = error.isNotBlank())
            if (error.isNotBlank()) Text(error, Modifier.testTag("email-rule-error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSave(text.trim()) }, enabled = !busy && text.isNotBlank(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("email-rule-save")) { Text(uiText("Save rule")) }
            TextButton(onClick = onJustThisEmail, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("email-rule-just-this")) { Text(uiText("Just this email")) }
        }
    }
}

/**
 * Loads the suggestion for [request] and sends the owner's choice. [fetch] and [send] are the caller's network; [onChosen] runs once the choice
 * reached Hermes, and the sheet stays open with its message when it did not.
 */
@Composable internal fun EmailRuleSheetHost(
    request: EmailRuleRequest?,
    fetch: suspend (EmailRuleRequest) -> JSONObject,
    send: suspend (EmailRuleRequest, String?) -> Unit,
    onChosen: (EmailRuleRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    var suggestion by remember(request) { mutableStateOf<EmailRuleSuggestion?>(null) }
    var busy by remember(request) { mutableStateOf(false) }
    var error by remember(request) { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val ruleFailed = uiText("Couldn't save the rule. Try again.")
    val choiceFailed = uiText("Couldn't save your choice. Try again.")
    val full = uiText("Rules are full. Delete one first.")
    LaunchedEffect(request) {
        suggestion = try {
            EmailRuleSuggestion.from(fetch(request))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            EmailRuleSuggestion()
        }
    }
    fun submit(rule: String?) {
        if (busy) return
        busy = true
        error = ""
        scope.launch {
            try {
                send(request, rule)
                onChosen(request)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                busy = false
                error = when {
                    rule == null -> choiceFailed
                    ruleLimitReached(failure) -> full
                    else -> ruleFailed
                }
            }
        }
    }
    EmailRuleSheet(request.verdict, suggestion, busy, error, onSave = { submit(it) }, onJustThisEmail = { submit(null) }, onDismiss = onDismiss)
}

/**
 * A rule's source as the owner reads it: the id Hermes sends ("email") as a capitalised word through uiText, or "Anything" when the
 * rule has no source. Names the app has no translation for (a service's own name) stay as written.
 */
internal fun ruleSourceWords(signal: String?): String? =
    signal?.trim()?.takeIf(String::isNotBlank)?.replace('_', ' ')?.replaceFirstChar { it.titlecase() }

@Composable internal fun ruleSourceLabel(signal: String?): String = ruleSourceWords(signal)?.let { uiText(it) } ?: uiText("Anything")

/** One importance rule in the owner's list: its words with a switch, then its verdict and source, with edit and delete. */
@Composable internal fun ImportanceRuleBlock(rule: JSONObject, enabled: Boolean, onUpdate: (JSONObject) -> Unit, onDelete: () -> Unit) {
    val id = rule.string("id")
    val text = rule.string("text")
    val on = rule.optBoolean("enabled")
    var editing by remember(id) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().testTag("proactive-rule-$id").padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(text, Modifier.weight(1f).testTag("proactive-rule-text-$id"), style = MaterialTheme.typography.bodyLarge,
                color = if (on) scheme.onSurface else scheme.onSurfaceVariant, maxLines = RULE_TEXT_LINES, overflow = TextOverflow.Ellipsis)
            Switch(on, { onUpdate(json("enabled" to it)) }, enabled = enabled, modifier = Modifier.testTag("proactive-rule-enabled-$id").semantics { contentDescription = text })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsBadge(verdictLabel(rule.string("action")), testTag = "proactive-rule-kind-$id")
                SettingsBadge(ruleSourceLabel(rule.opt("signal") as? String), Modifier.weight(1f, fill = false), testTag = "proactive-rule-source-$id")
            }
            SettingsIconButton(Icons.Rounded.Edit, uiText("Edit rule"), { editing = true }, Modifier.testTag("proactive-rule-edit-$id"), enabled = enabled)
            SettingsIconButton(Icons.Rounded.DeleteOutline, uiText("Delete"), onDelete, Modifier.testTag("proactive-rule-delete-$id"), enabled = enabled, tint = scheme.error)
        }
    }
    if (editing) RuleTextDialog(text, onSave = { editing = false; onUpdate(json("text" to it)) }, onDismiss = { editing = false })
}

/** Edits the words of one saved rule; the verdict stays as it was. */
@Composable private fun RuleTextDialog(initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiText("Edit rule")) },
        text = { OutlinedTextField(text, { text = it.take(EMAIL_RULE_LIMIT) }, Modifier.fillMaxWidth().testTag("proactive-rule-edit-field"), label = { Text(uiText("Your rule")) }, maxLines = 4) },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank() && text.trim() != initial.trim(), modifier = Modifier.heightIn(min = 48.dp).testTag("proactive-rule-edit-save")) { Text(uiText("Save")) }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(uiText("Cancel")) } },
    )
}

/** What the owner reviews in "Turn into rules": the verdict and the words, both kept as the owner leaves them. */
internal data class EarlierRuleDraft(val verdict: String, val source: String, val text: String)

/**
 * The rules Hermes proposes from earlier ratings, for the owner to change, drop or save. Saving sends what is left; the owner can still
 * delete any of them later in the rules list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun EarlierRulesSheet(
    proposal: List<EarlierRule>,
    busy: Boolean,
    error: String,
    onSave: (List<EarlierRuleDraft>) -> Unit,
    onDismiss: () -> Unit,
) {
    val from = uiText("Sent by {0}")
    val about = uiText("About {0}")
    val drafts = remember(proposal) { mutableStateListOf(*proposal.map { EarlierRuleDraft(it.verdict, it.source, it.suggestion.text(from, about)) }.toTypedArray()) }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), modifier = Modifier.testTag("earlier-rules-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(uiText("Turn into rules"), style = MaterialTheme.typography.titleLarge)
            drafts.forEachIndexed { index, draft ->
                Column(Modifier.fillMaxWidth().testTag("earlier-rule-$index"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsBadge(verdictLabel(draft.verdict), testTag = "earlier-rule-kind-$index")
                        SettingsBadge(ruleSourceLabel(draft.source), Modifier.weight(1f, fill = false))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(draft.text, { drafts[index] = draft.copy(text = it.take(EMAIL_RULE_LIMIT)) }, Modifier.weight(1f).testTag("earlier-rule-text-$index"), enabled = !busy,
                            label = { Text(uiText("Your rule")) }, maxLines = 3)
                        SettingsIconButton(Icons.Rounded.Close, uiText("Remove"), { drafts.removeAt(index) }, Modifier.testTag("earlier-rule-remove-$index"), enabled = !busy)
                    }
                }
            }
            if (error.isNotBlank()) Text(error, Modifier.testTag("earlier-rules-error"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Button(onClick = { onSave(drafts.map { it.copy(text = it.text.trim()) }.filter { it.text.isNotBlank() }) }, enabled = !busy && drafts.any { it.text.isNotBlank() },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("earlier-rules-save")) { Text(uiText("Save rules")) }
            TextButton(onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("earlier-rules-cancel")) { Text(uiText("Cancel")) }
        }
    }
}
