package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import dev.hermes.android.data.*
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.deviceLockedNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** What the Vault page cached for this account and profile: metadata only, never a secret. */
internal const val VAULT_CACHE_KEY = "vault"

private enum class VaultForm(val kind: VaultKind, val title: String) {
    Login(VaultKind.Login, "Add login"), Card(VaultKind.Payment, "Add payment card"), Address(VaultKind.Address, "Add address"),
}

private class VaultPageState(initial: VaultSnapshot?) {
    var snapshot by mutableStateOf(initial)
    var failure by mutableStateOf<VaultFailure?>(null)
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var form by mutableStateOf<VaultForm?>(null)
    var selected by mutableStateOf<VaultItem?>(null)
    var removing by mutableStateOf<VaultItem?>(null)
    var unlocking by mutableStateOf<VaultSource?>(null)
    var readVersion = 0L
    var read: Job? = null
    var write: Job? = null
}

private fun vaultFields(form: VaultForm): List<SecureField> = when (form) {
    VaultForm.Login -> listOf(SecureField("site", "Site", masked = false), SecureField("identifier", "Username or email", masked = false),
        SecureField("password", "Password"), SecureField("otp_secret", "Authenticator key", required = false))
    VaultForm.Card -> listOf(SecureField("label", "Name", masked = false),
        SecureField("card_number", "Card number"), SecureField("exp_month", "Expiration month"), SecureField("exp_year", "Expiration year"),
        SecureField("cvc", "Security code"), SecureField("cardholder_name", "Cardholder name", required = false),
        SecureField("billing_postal_code", "Billing postal code", required = false))
    VaultForm.Address -> listOf(SecureField("label", "Name", masked = false), SecureField("site", "Site", masked = false),
        SecureField("address_line1", "Address line 1"), SecureField("address_line2", "Address line 2", required = false), SecureField("city", "City"),
        SecureField("state", "State", required = false), SecureField("postal_code", "Postal code"), SecureField("country", "Country"))
}

/** Shown inside a Vault form that cannot be sent yet (the page is reading or saving something): the form stays open with what was typed. */
internal const val VAULT_BUSY_LINE = "Still working. Try again in a moment."

/** The one problem worth stopping the person for before anything is sent; Hermes checks the rest. */
internal fun vaultFormProblem(kind: VaultKind, input: JSONObject): String? = when {
    kind != VaultKind.Payment && vaultSiteOrigin(input.optString("site")) == null -> "Enter the site, like github.com."
    kind == VaultKind.Payment && (input.optString("exp_month").toIntOrNull() ?: 0) !in 1..12 -> "Enter a valid expiration month."
    else -> null
}

private fun clearVaultInput(value: JSONObject) { value.keys().asSequence().toList().forEach(value::remove) }

@Composable internal fun NativeVaultPage(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    // The profile the settings are open for (Manage profile > Vault), else the one in use: the page always shows one profile's own vault.
    val active = vm.settingsProfileClient()
    val version = vm.settingsProfileVersion()
    val state = remember(active, version) { VaultPageState(active?.let { vm.screens.exact(it.scope, VAULT_CACHE_KEY)?.value }?.let { runCatching { VaultSnapshot.from(it) }.getOrNull() }) }
    val coroutine = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    val profiles = data["navigation-profiles"]?.rows("profiles").orEmpty()
    val profile = active?.scope?.profile ?: ui.profile
    fun current(): Boolean = active != null && vm.settingsProfileCurrent(active, version) && vm.ui.value.tab == "More" && vm.ui.value.morePage == "Vault" &&
        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun refresh() {
        if (!current() || state.loading) return
        state.loading = true
        val mine = ++state.readVersion
        state.read = coroutine.launch {
            try {
                val snapshot = NativeVaultApi(active!!).snapshot()
                if (current() && state.readVersion == mine) {
                    state.snapshot = snapshot; state.failure = null
                    vm.screens.set(active.scope, VAULT_CACHE_KEY, snapshot.toJson())
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (current() && state.readVersion == mine) state.failure = vaultFailure(error) }
            finally { if (current() && state.readVersion == mine) state.loading = false }
        }
    }
    // A change starts only when nothing else on the page is running. The forms ask first (see [VAULT_BUSY_LINE]), so what the person typed is
    // never closed and lost for a change that does not start.
    fun canChange(): Boolean = current() && !state.busy && !state.loading && !deviceLockedNow(context)
    fun change(op: VaultOp, done: String = "", action: suspend NativeVaultApi.() -> Unit) {
        if (!canChange()) return
        state.busy = true; state.message = ""
        state.write = coroutine.launch {
            try { NativeVaultApi(active!!).action(); if (current()) state.message = done }
            catch (error: CancellationException) { throw error }
            catch (error: Exception) { if (current()) state.message = vaultErrorText(op, error) }
            finally { if (current()) { state.busy = false; refresh() } }
        }
    }
    LaunchedEffect(active, version, ui.connected) { if (ui.connected) refresh() }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) { state.form = null; state.unlocking = null; state.removing = null; state.selected = null }
            else if (event == Lifecycle.Event.ON_RESUME && !state.busy) refresh()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.readVersion++; state.read?.cancel() }
    }
    BackHandler { vm.backFromSettings() }
    // "Saved" and "Removed" say it once and go; a refusal stays until the next try.
    LaunchedEffect(state.message) { if (state.message == "Saved" || state.message == "Removed") { delay(3000); state.message = "" } }
    val snapshot = state.snapshot
    val view = vaultView(ui.connected, state.loading, snapshot, state.failure)
    val ready = (view == VaultView.Items || view == VaultView.Empty) && !state.busy && !state.loading
    SettingsPage(uiText("Vault"), vm::backFromSettings, subtitle = profileDisplayName(profile, profiles),
        actions = { SettingsIconButton(Icons.Rounded.Refresh, uiText("Refresh"), { refresh() }, enabled = !state.loading && !state.busy) },
        refreshing = state.loading || state.busy, testTag = "vault-page") {
        item {
            // A list on screen from the cache stays, with the reason the refresh failed above it.
            val row = vaultStateRow(if (view == VaultView.Items || view == VaultView.Empty) vaultView(ui.connected, false, null, state.failure).takeIf { state.failure != null } ?: view else view)
            val note = state.message.takeIf(String::isNotBlank)
            if (view == VaultView.Loading || row != null || note != null) SettingsSection(testTag = "vault-status") {
                if (view == VaultView.Loading) SettingsSkeletonRows(2)
                else if (row != null && row.retry) SettingsErrorRow(uiText(row.text), { refresh() }, uiText("Refresh"))
                else if (row != null) SettingsEmpty(uiText(row.text), Icons.Rounded.Lock)
                if (note != null) { if (row != null || view == VaultView.Loading) SettingsDivider(); if (note == "Saved" || note == "Removed" || note == "Done") SettingsValueRow(uiText(note), icon = Icons.Rounded.CheckCircle, testTag = "vault-done") else SettingsErrorRow(uiText(note), null, uiText("Refresh")) }
            }
        }
        if (snapshot != null && view != VaultView.Unsupported) {
            for ((kind, title) in listOf(VaultKind.Login to "Logins", VaultKind.Payment to "Cards", VaultKind.Address to "Addresses")) {
                val items = snapshot.items.filter { it.kind == kind }
                if (items.isEmpty()) continue
                item(key = "kind:${kind.wire}") {
                    SettingsSection(uiText(title), footer = if (items.any(::vaultAnySite)) uiText("Each payment asks you first.") else "", testTag = "vault-section:${kind.wire}") {
                        items.forEachIndexed { index, item ->
                            if (index > 0) SettingsDivider()
                            val badge = listOf(vaultBadge(item, snapshot.sources), if (item.hasOtp) uiText("Fills 2FA codes") else "").filter(String::isNotBlank).joinToString(" · ")
                            SettingsNavRow(item.label, { state.selected = item }, icon = when (kind) { VaultKind.Login -> Icons.Rounded.Lock; VaultKind.Payment -> Icons.Rounded.CreditCard; else -> Icons.Rounded.Home },
                                value = listOf(vaultItemSite(item, uiText("Any site")), item.identifier).filter(String::isNotBlank).joinToString(" · "),
                                status = SettingsStatus.Ok.takeIf { badge.isNotBlank() }, statusLabel = badge, enabled = !state.busy, testTag = "vault-item:${item.id}")
                        }
                    }
                }
            }
            item(key = "add") {
                SettingsSection(testTag = "vault-add") {
                    SettingsActionRow(uiText("Add login"), { state.form = VaultForm.Login }, icon = Icons.Rounded.Add, enabled = ready, testTag = "vault-add-login")
                    SettingsDivider()
                    SettingsActionRow(uiText("Add payment card"), { state.form = VaultForm.Card }, icon = Icons.Rounded.Add, enabled = ready, testTag = "vault-add-card")
                    SettingsDivider()
                    SettingsActionRow(uiText("Add address"), { state.form = VaultForm.Address }, icon = Icons.Rounded.Add, enabled = ready, testTag = "vault-add-address")
                }
            }
            if (snapshot.managers.isNotEmpty()) item(key = "managers") {
                SettingsSection(uiText("Password managers"), testTag = "vault-managers") {
                    snapshot.managers.forEachIndexed { index, source ->
                        if (index > 0) SettingsDivider()
                        SettingsSwitchRow(source.displayName, source.enabled, { on -> change(VaultOp.SourceSwitch) { setSource(source.name, on) } }, icon = Icons.Rounded.Key,
                            summary = uiText(when { !source.installed -> "Not installed"; !source.enabled -> "Off"; source.unlocked -> "Unlocked"; else -> "Locked" }),
                            enabled = ready && (source.installed || source.enabled), testTag = "vault-source:${source.name}")
                        if (source.enabled && source.installed) {
                            SettingsDivider()
                            if (source.unlocked) SettingsActionRow(uiText("Lock"), { change(VaultOp.Lock) { lock(source.name) } }, icon = Icons.Rounded.Lock, enabled = ready, testTag = "vault-lock:${source.name}")
                            else SettingsActionRow(uiText("Unlock"), { state.unlocking = source }, icon = Icons.Rounded.LockOpen, enabled = ready, testTag = "vault-unlock:${source.name}")
                        }
                    }
                }
            }
        }
    }
    val chosen = state.selected
    if (chosen != null && state.removing == null) AlertDialog(
        onDismissRequest = { state.selected = null }, title = { Text(chosen.label) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (vaultAnySite(chosen)) { Text(uiText("Any site")); Text(uiText("Each payment asks you first.")) } else Text(chosen.origin)
            if (chosen.identifier.isNotBlank()) Text(chosen.identifier)
            if (chosen.backend != "local") Text(uiText("Managed in") + " " + vaultBadge(chosen, snapshot?.sources.orEmpty()))
        } },
        confirmButton = { TextButton(onClick = { state.selected = null }) { Text(uiText("Close")) } },
        dismissButton = if (chosen.backend == "local") ({ TextButton(onClick = { state.removing = chosen }, enabled = !state.busy) { Text(uiText("Remove")) } }) else null,
    )
    state.removing?.let { item ->
        SettingsConfirmDialog(uiText("Remove this item?"), item.label, uiText("Remove"), onConfirm = {
            state.removing = null; state.selected = null
            change(VaultOp.Remove, "Removed") { remove(item.id) }
        }, onDismiss = { state.removing = null }, confirmTestTag = "vault-remove-confirm")
    }
    state.form?.let { form ->
        NativeSecureForm("vault:add:${form.name}", form.title, if (form == VaultForm.Card) uiText("Works on any site. Each payment asks you first.") else "", vaultFields(form), validate = { input -> vaultFormProblem(form.kind, input) ?: VAULT_BUSY_LINE.takeUnless { canChange() } },
            onCancel = { state.form = null }, onSubmit = { input ->
                state.form = null
                val draft = vaultDraft(form.kind, input.optString("label"), input.optString("site"), vaultFields(form).associate { it.key to input.optString(it.key) })
                clearVaultInput(input)
                if (draft != null) change(VaultOp.Add, "Saved") { add(draft) } else state.message = "Hermes couldn't save that. Check the details."
            })
    }
    state.unlocking?.let { source ->
        NativeSecureForm("vault:unlock:${source.name}", "Unlock ${source.displayName}", "", listOf(SecureField("password", "Master password")),
            validate = { VAULT_BUSY_LINE.takeUnless { canChange() } }, onCancel = { state.unlocking = null }, onSubmit = { input ->
                state.unlocking = null
                val password = input.optString("password")
                clearVaultInput(input)
                change(VaultOp.Unlock) { unlock(source.name, password) }
            })
    }
}

/** The one state the Vault page shows. Each state is a single row (or the list), never a second copy of the same message. */
internal enum class VaultView { Loading, Unsupported, Offline, SignedOut, Error, Empty, Items }

/** A state's single row: [retry] adds the Refresh action in the same row. */
internal data class VaultStateRow(val text: String, val retry: Boolean)

/** A loaded snapshot wins (even a stale one), then a read in progress, then the failure of the last read, then whether Hermes is reachable at all. */
internal fun vaultView(connected: Boolean, loading: Boolean, snapshot: VaultSnapshot?, failure: VaultFailure?): VaultView = when {
    failure == VaultFailure.Unsupported -> VaultView.Unsupported
    snapshot != null && snapshot.items.isEmpty() && failure == null -> VaultView.Empty
    snapshot != null -> VaultView.Items
    loading -> VaultView.Loading
    failure == VaultFailure.Offline -> VaultView.Offline
    failure == VaultFailure.SignedOut -> VaultView.SignedOut
    failure != null -> VaultView.Error
    !connected -> VaultView.Offline
    else -> VaultView.Loading
}

internal fun vaultStateRow(view: VaultView): VaultStateRow? = when (view) {
    VaultView.Loading, VaultView.Items -> null
    VaultView.Unsupported -> VaultStateRow("This Hermes doesn't have a Vault. Update Hermes to use it.", retry = false)
    VaultView.Offline -> VaultStateRow("Can't reach Hermes", retry = true)
    VaultView.SignedOut -> VaultStateRow("Sign in again", retry = false)
    VaultView.Error -> VaultStateRow("Vault unavailable", retry = true)
    VaultView.Empty -> VaultStateRow("Nothing saved yet", retry = false)
}

/**
 * What the approval card says. Card fills are Hermes' own consent prompt (tools/browser_vault_tool.py `_confirm_payment_fill`): `command` is
 * "Fill payment card '<card>' on <origin>" and `description` a three-sentence warning about CVC and intent. The phone asks one short question
 * from the card and the site ([card], [site]) and does not repeat the warning (UX review P1-2); [line] is Hermes' own command, kept for a
 * wording this parser does not know. Hermes sends no amount, so [amount] is only what a prompt carries.
 */
internal data class ApprovalFace(val title: String, val line: String, val detail: String, val amount: String, val payment: Boolean, val code: Boolean, val card: String = "", val site: String = "")

private val cardFillCommand = Regex("""^Fill payment card '(.+)' on (\S+)$""")

/** "Pay with your Visa on shop.example?" in the phone's language: [template] is the translated sentence, the card and the site are isolated as the owner's own words. */
internal fun cardPaymentLine(template: String, card: String, site: String): String = PhoneLine(template, listOf(card, site)).shown()

internal fun approvalFace(p: JSONObject): ApprovalFace {
    val command = p.string("command"); val description = p.string("description")
    // Only Hermes' own marker makes the payment look: the command text is whatever the agent or an MCP server wrote, so it never decides.
    val payment = p.string("surface") == "vault-payment"
    if (payment) {
        val amount = p.opt("amount")?.takeUnless { it == JSONObject.NULL }?.toString()?.trim().orEmpty()
        val fill = cardFillCommand.matchEntire(command.trim())
        // https:// is not said to a person; an http:// page or a port stays, because that is what the owner checks before paying.
        val site = fill?.groupValues?.get(2)?.removePrefix("https://")?.removeSuffix("/").orEmpty()
        return ApprovalFace("Confirm card payment", command.ifBlank { "Pay with your saved card?" }, "", amount, payment = true, code = false,
            card = fill?.groupValues?.get(1).orEmpty(), site = site)
    }
    return ApprovalFace("Approval needed", description.ifBlank { command.ifBlank { "Hermes asks to continue." } }, "", "", payment = false, code = command.isNotBlank())
}
