package dev.hermes.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.*
import dev.hermes.android.phone.deviceLockedNow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

private const val VAULT = "/api/plugins/beacon/mobile/vault"

private class VaultPageState {
    var snapshot by mutableStateOf<VaultSnapshot?>(null)
    var loading by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var message by mutableStateOf("")
    var form by mutableStateOf("")
    var selected by mutableStateOf<VaultMetadata?>(null)
    var deleting by mutableStateOf(false)
    var needsRefresh by mutableStateOf(true)
    var readVersion = 0L
    var read: Job? = null
    var write: Job? = null
}

private fun vaultSecretFields(kind: String, otp: Boolean): List<SecureField> = when (kind) {
    "login" -> listOf(SecureField("identifier_type", "Identifier type", masked = false, initial = "email", choices = listOf("email", "phone", "username")),
        SecureField("identifier", "Identifier", masked = false), SecureField("password", "Password")) +
        if (otp) listOf(SecureField("otp_secret", "Authenticator key", required = false)) else emptyList()
    "payment" -> listOf(SecureField("card_number", "Card number"), SecureField("exp_month", "Expiration month"),
        SecureField("exp_year", "Expiration year"), SecureField("cvc", "Security code"),
        SecureField("cardholder_name", "Cardholder name", required = false), SecureField("billing_postal_code", "Billing postal code", required = false))
    else -> listOf(SecureField("address_line1", "Address line 1"), SecureField("address_line2", "Address line 2", required = false),
        SecureField("city", "City"), SecureField("state", "State", required = false), SecureField("postal_code", "Postal code"), SecureField("country", "Country"))
}

private fun clearVaultInput(value: JSONObject) {
    value.optJSONObject("secret")?.let { secret -> secret.keys().asSequence().toList().forEach(secret::remove) }
    value.keys().asSequence().toList().forEach(value::remove)
}

@Composable internal fun NativeVaultPage(vm: BeaconViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val active = vm.client
    val authority = vm.vaultAuthorityVersion()
    val credential = active?.nativeAuthorityVersion()
    val state = remember(active, authority, credential) { VaultPageState() }
    val coroutine = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val context = LocalContext.current
    fun current(): Boolean = active != null && credential != null && active.nativeAuthorityVersion() == credential && vm.vaultClientCurrent(active, authority) &&
        vm.ui.value.tab == "More" && vm.ui.value.morePage == "Vault" && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    fun refresh() {
        if (!current() || state.loading) return
        state.loading = true
        val version = ++state.readVersion
        state.read = coroutine.launch {
            try {
                val snapshot = VaultSnapshot.from(active!!.nativeRequest(VAULT))
                if (current() && state.readVersion == version) { state.snapshot = snapshot; state.needsRefresh = false; if (state.message == "Vault unavailable") state.message = "" }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (current() && state.readVersion == version) { state.snapshot = null; state.needsRefresh = true; if (state.message != "Change unconfirmed. Refresh before trying again.") state.message = "Vault unavailable" } }
            finally { if (current() && state.readVersion == version) state.loading = false }
        }
    }
    fun write(path: String, method: String, body: JSONObject, verify: (JSONObject) -> Unit) {
        if (!current() || state.busy || state.loading || state.needsRefresh || deviceLockedNow(context)) { clearVaultInput(body); return }
        state.busy = true
        state.message = ""
        state.write = coroutine.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val receipt = active!!.nativeSensitiveRequest(path, method, body)
                verify(receipt)
                if (current()) state.message = "Saved"
            } catch (error: CancellationException) {
                if (current()) { state.needsRefresh = true; state.message = "Change unconfirmed. Refresh before trying again." }
                throw error
            } catch (_: Exception) {
                if (current()) { state.needsRefresh = true; state.message = "Change unconfirmed. Refresh before trying again." }
            } finally {
                clearVaultInput(body)
                if (current()) { state.busy = false; refresh() }
            }
        }
    }
    LaunchedEffect(active, authority, credential, ui.connected) { if (ui.connected) refresh() }
    DisposableEffect(state, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                state.form = ""; state.selected = null; state.deleting = false
                state.readVersion++; state.read?.cancel(); state.loading = false; state.needsRefresh = true
                if (state.write?.isActive == true) { state.needsRefresh = true; state.message = "Change unconfirmed. Refresh before trying again."; state.write?.cancel() }
            } else if (event == Lifecycle.Event.ON_RESUME) {
                if (state.write?.isActive != true) state.busy = false
                if (state.needsRefresh) refresh()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); state.readVersion++; state.read?.cancel(); state.write?.cancel() }
    }
    BackHandler { vm.backFromSettings() }
    SettingsPage(uiText("Vault"), vm::backFromSettings, subtitle = active?.scope?.profile ?: ui.profile,
        actions = { SettingsIconButton(Icons.Rounded.Refresh, uiText("Refresh"), { refresh() }, enabled = !state.loading && !state.busy) },
        refreshing = state.loading || state.busy, testTag = "vault-page") {
        item {
            SettingsSection(uiText("Vault items")) {
                if (state.message == "Saved") SettingsValueRow(uiText("Saved"), icon = Icons.Rounded.CheckCircle)
                else if (state.message.isNotBlank()) SettingsErrorRow(uiText(state.message), if (state.needsRefresh) ({ refresh() }) else null, uiText("Refresh"))
                val snapshot = state.snapshot
                if (snapshot == null && state.loading) SettingsSkeletonRows(2)
                else if (snapshot == null || !snapshot.capabilities.supported || !snapshot.capabilities.local)
                    SettingsEmpty(uiText("Vault unavailable"), Icons.Rounded.Lock)
                else {
                    if (snapshot.items.isEmpty()) SettingsEmpty(uiText("No Vault items"), Icons.Rounded.Lock)
                    snapshot.items.forEachIndexed { index, item ->
                        if (index > 0) SettingsDivider()
                        SettingsNavRow(item.label, { state.selected = item }, icon = Icons.Rounded.Lock,
                            value = listOf(item.origin, item.identifier).filter(String::isNotBlank).joinToString(" · "),
                            enabled = !state.busy && !state.loading && !state.needsRefresh, testTag = "vault-item:${item.id}")
                    }
                    if (snapshot.items.isNotEmpty()) SettingsDivider()
                    SettingsActionRow(uiText("Add login"), { state.form = "add:login" }, icon = Icons.Rounded.Add, enabled = !state.busy && !state.loading && !state.needsRefresh)
                    SettingsDivider()
                    SettingsActionRow(uiText("Add payment card"), { state.form = "add:payment" }, icon = Icons.Rounded.Add, enabled = !state.busy && !state.loading && !state.needsRefresh)
                    SettingsDivider()
                    SettingsActionRow(uiText("Add address"), { state.form = "add:address" }, icon = Icons.Rounded.Add, enabled = !state.busy && !state.loading && !state.needsRefresh)
                    if (vaultCanGenerate(snapshot.capabilities)) { SettingsDivider(); SettingsActionRow(uiText("Generate login"), { state.form = "generate" }, icon = Icons.Rounded.Add, enabled = !state.busy && !state.loading && !state.needsRefresh) }
                }
            }
        }
        state.snapshot?.takeIf { it.capabilities.supported }?.let { snapshot ->
            item {
                SettingsSection(uiText("Identity")) {
                    val identity = snapshot.identity
                    SettingsNavRow(uiText("Identity"), { state.form = "identity" }, icon = Icons.Rounded.Person,
                        value = listOf(identity.name, identity.email, uiText(when (identity.status) {
                            "ready" -> "Ready"; "unverified" -> "Unverified"; "not_configured" -> "Not configured"; else -> "Unavailable"
                        })).filter(String::isNotBlank).joinToString(" · "), enabled = !state.busy && !state.loading && !state.needsRefresh, testTag = "vault-identity")
                }
            }
        }
    }
    val selected = state.selected
    if (selected != null && state.form.isBlank() && !state.deleting) AlertDialog(
        onDismissRequest = { state.selected = null }, title = { Text(selected.label) },
        text = { Column {
            Text(selected.origin)
            TextButton(onClick = { state.form = "edit" }) { Text(uiText("Edit")) }
            TextButton(onClick = { state.form = "replace" }) { Text(uiText("Replace secret")) }
            TextButton(onClick = { state.deleting = true }) { Text(uiText("Delete")) }
        } }, confirmButton = { TextButton(onClick = { state.selected = null }) { Text(uiText("Close")) } },
    )
    if (state.deleting && selected != null) SettingsConfirmDialog(uiText("Delete Vault item?"), selected.label, uiText("Delete"), onConfirm = {
        state.deleting = false; state.selected = null
        val id = UUID.randomUUID().toString()
        write("$VAULT/items/${selected.id}", "DELETE", json("request_id" to id)) { receipt ->
            require(receipt.optString("request_id") == id && receipt.opt("removed") == true) { "Removal was not confirmed" }
        }
    }, onDismiss = { state.deleting = false })
    val mode = state.form
    if (mode.isNotBlank()) {
        val kind = if (mode.startsWith("add:")) mode.substringAfter(':') else selected?.kind.orEmpty()
        val base = listOf(SecureField("label", "Name", masked = false, initial = if (mode == "edit") selected?.label.orEmpty() else ""),
            SecureField("origin", "Origin", masked = false, initial = if (mode == "edit") selected?.origin.orEmpty() else ""))
        val fields = when {
            mode.startsWith("add:") -> base + vaultSecretFields(kind, state.snapshot?.capabilities?.otp == true)
            mode == "replace" -> vaultSecretFields(kind, state.snapshot?.capabilities?.otp == true)
            mode == "edit" -> base
            mode == "generate" -> base + listOf(SecureField("identifier_type", "Identifier type", masked = false, initial = "email", choices = listOf("email", "phone", "username")), SecureField("identifier", "Identifier", masked = false))
            else -> listOf(SecureField("name", "Name", required = false, masked = false, initial = state.snapshot?.identity?.name.orEmpty()),
                SecureField("email", "Email", required = false, masked = false, initial = state.snapshot?.identity?.email.orEmpty()))
        }
        NativeSecureForm("vault:$mode:${selected?.id.orEmpty()}", when {
            mode.startsWith("add:login") -> "Add login"; mode.startsWith("add:payment") -> "Add payment card"; mode.startsWith("add:") -> "Add address"
            mode == "replace" -> "Replace secret"; mode == "generate" -> "Generate login"; mode == "identity" -> "Identity"; else -> "Edit"
        }, selected?.label.orEmpty(), fields, validate = { input ->
            if ((mode.startsWith("add:") || mode in setOf("edit", "generate")) && !validVaultOrigin(input.optString("origin").trim())) "Enter an HTTPS site address."
            else if (mode == "replace" && !validVaultOrigin(selected?.origin.orEmpty())) "Edit the site address first."
            else if (kind == "payment" && mode != "edit" && (input.optString("exp_month").toIntOrNull() ?: 0) !in 1..12) "Enter a valid expiration month."
            else null
        }, onCancel = { state.form = ""; state.selected = null }, onSubmit = { input ->
            state.form = ""
            val requestId = UUID.randomUUID().toString()
            val body = json("request_id" to requestId)
            val item = selected
            val path: String
            val method: String
            when {
                mode.startsWith("add:") || mode == "replace" -> {
                    val secret = JSONObject()
                    vaultSecretFields(kind, state.snapshot?.capabilities?.otp == true).forEach { field -> if (input.has(field.key)) secret.put(field.key, input.get(field.key)) }
                    body.put("kind", kind).put("label", if (mode == "replace") item!!.label else input.optString("label").trim())
                        .put("origin", if (mode == "replace") item!!.origin else input.optString("origin").trim()).put("secret", secret)
                    path = if (mode == "replace") "$VAULT/items/${item!!.id}" else "$VAULT/items"
                    method = if (mode == "replace") "PUT" else "POST"
                    if (mode == "replace") body.remove("kind")
                }
                mode == "edit" -> { path = "$VAULT/items/${item!!.id}"; method = "PUT"; body.put("label", input.optString("label").trim()).put("origin", input.optString("origin").trim()) }
                mode == "generate" -> {
                    path = "$VAULT/generate-login"; method = "POST"
                    for (key in listOf("label", "origin", "identifier_type", "identifier")) body.put(key, input.optString(key).trim())
                }
                else -> { path = "$VAULT/identity"; method = "PUT"; body.put("name", input.optString("name").trim()).put("email", input.optString("email").trim()) }
            }
            input.keys().asSequence().toList().forEach(input::remove)
            state.selected = null
            write(path, method, body) { receipt ->
                if (mode == "identity") {
                    val identity = VaultIdentity.from(receipt)
                    require(receipt.optString("request_id") == requestId && identity.name == body.optString("name") &&
                        identity.email == body.optString("email") && identity.status != "unavailable") { "Identity was not confirmed" }
                } else vaultSavedItem(receipt, requestId)
            }
        })
    }
}
