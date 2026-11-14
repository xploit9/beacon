package dev.hermes.android.data

import dev.hermes.android.core.Scope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

// Hermes' own Passwords & Logins, spoken over the gateway socket the app already holds.
// Contract (Hermes tui_gateway/contracts/profiles_vault_complete_foreign_subagents.py): vault.list 470-477, vault.sources 480-495,
// vault.source.set 498-509, vault.unlock 512-524, vault.lock 527-536, vault.add 539-554, vault.remove 557-566 (all take `profile`;
// NativeGateway.rpcOn stamps it from the client's scope). Handlers: tui_gateway/methods_vault.py:42-180. Errors are JSON-RPC code 5095.
// Secret fields: agent/vault_store.py PAYMENT_FIELDS 48-51, ADDRESS_FIELDS 52-55, REQUIRED_FIELDS 56-57, login payload add_item 547-606.

internal enum class VaultKind(val wire: String) {
    Login("login"), Payment("payment"), Address("address");
    companion object { fun of(wire: String): VaultKind? = entries.firstOrNull { it.wire == wire } }
}

/** A card saved with no site: Hermes' vault takes it (a card's origin is optional), and the plugin's card tool fills it on any page once the person approves that payment. */
internal fun vaultAnySite(item: VaultItem): Boolean = item.kind == VaultKind.Payment && item.origin.isBlank()

/** The site a row shows: its origin without the scheme, or [anySite] for a card that has none. */
internal fun vaultItemSite(item: VaultItem, anySite: String): String = if (vaultAnySite(item)) anySite else item.origin.removePrefix("https://")

/** Metadata only: Hermes never returns a password, card number or authenticator key. */
internal data class VaultItem(
    val id: String, val kind: VaultKind, val label: String, val origin: String,
    val identifier: String = "", val identifierType: String = "", val hasOtp: Boolean = false, val backend: String = "local",
) {
    companion object {
        /** A row that does not match the contract is skipped, so one odd item never hides the rest. */
        fun from(value: JSONObject): VaultItem? {
            fun text(key: String, limit: Int): String? {
                val raw = value.opt(key)
                if (raw == null || raw == JSONObject.NULL) return ""
                return (raw as? String)?.takeIf { it.length <= limit && it.none(Char::isISOControl) }
            }
            val id = text("id", 300)?.takeIf(String::isNotBlank) ?: return null
            val kind = VaultKind.of(text("kind", 20).orEmpty()) ?: return null
            val label = text("label", 200)?.takeIf(String::isNotBlank) ?: return null
            return VaultItem(id, kind, label, text("origin", 2048) ?: return null, text("identifier", 320) ?: return null,
                text("identifier_type", 20) ?: return null, value.opt("has_otp") == true, text("backend", 64)?.ifBlank { "local" } ?: return null)
        }
    }
    fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.wire).put("label", label).put("origin", origin)
        .put("identifier", identifier).put("identifier_type", identifierType).put("has_otp", hasOtp).put("backend", backend)
}

internal data class VaultSource(val name: String, val displayName: String, val enabled: Boolean, val needsUnlock: Boolean, val unlocked: Boolean, val installed: Boolean) {
    companion object {
        fun from(value: JSONObject): VaultSource? {
            val name = (value.opt("name") as? String)?.takeIf { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) } ?: return null
            val display = (value.opt("display_name") as? String)?.takeIf { it.length <= 120 && it.none(Char::isISOControl) }?.ifBlank { name } ?: name
            return VaultSource(name, display, value.opt("enabled") == true, value.opt("needs_unlock") == true, value.opt("unlocked") == true, value.opt("installed") == true)
        }
    }
    fun toJson(): JSONObject = JSONObject().put("name", name).put("display_name", displayName).put("enabled", enabled)
        .put("needs_unlock", needsUnlock).put("unlocked", unlocked).put("installed", installed)
}

internal data class VaultSnapshot(val items: List<VaultItem>, val sources: List<VaultSource>) {
    /** Only metadata is ever cached, per account and profile. */
    fun toJson(): JSONObject = JSONObject().put("items", JSONArray(items.map { it.toJson() })).put("sources", JSONArray(sources.map { it.toJson() }))
    /** The password managers a person can act on: detected (installed) or already switched on. */
    val managers: List<VaultSource> get() = sources.filter { it.needsUnlock && (it.installed || it.enabled) }
    companion object {
        val Empty = VaultSnapshot(emptyList(), emptyList())
        fun from(value: JSONObject): VaultSnapshot {
            val items = value.optJSONArray("items")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(VaultItem::from) } }.orEmpty()
            val sources = value.optJSONArray("sources")?.let { rows -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(VaultSource::from) } }.orEmpty()
            return VaultSnapshot(items.distinctBy { it.id }, sources.distinctBy { it.name })
        }
    }
}

/** What a person types to add an item: the exact secret keys Hermes stores, nothing else. */
internal data class VaultDraft(val kind: VaultKind, val label: String, val origin: String, val secret: Map<String, String>)

private val PAYMENT_KEYS = listOf("card_number", "cardholder_name", "exp_month", "exp_year", "cvc", "billing_postal_code")
private val ADDRESS_KEYS = listOf("address_line1", "address_line2", "city", "state", "postal_code", "country")

/** Every field is optional here; Hermes validates what it needs, and blank values are never sent. */
internal fun vaultDraft(kind: VaultKind, label: String, site: String, fields: Map<String, String>): VaultDraft? {
    // A card may be saved with no site: it then works on any page, and every payment asks the person first.
    val origin = if (kind == VaultKind.Payment && site.isBlank()) "" else vaultSiteOrigin(site) ?: return null
    val secret = LinkedHashMap<String, String>()
    when (kind) {
        VaultKind.Login -> {
            val identifier = fields["identifier"].orEmpty().trim()
            val password = fields["password"].orEmpty()
            if (identifier.isBlank() || password.isBlank()) return null
            secret["identifier_type"] = vaultIdentifierType(identifier)
            secret["identifier"] = identifier
            secret["password"] = password
            fields["otp_secret"].orEmpty().trim().takeIf(String::isNotBlank)?.let { secret["otp_secret"] = it }
        }
        VaultKind.Payment -> PAYMENT_KEYS.forEach { key -> fields[key].orEmpty().trim().takeIf(String::isNotBlank)?.let { secret[key] = it } }
        VaultKind.Address -> ADDRESS_KEYS.forEach { key -> fields[key].orEmpty().trim().takeIf(String::isNotBlank)?.let { secret[key] = it } }
    }
    val name = label.trim().ifBlank { if (origin.isBlank()) return null else URI(origin).host.orEmpty().removePrefix("www.") }
    return VaultDraft(kind, name, origin, secret)
}

/** `login`'s identifier_type is one of email, phone, username (agent/vault_store.py LOGIN_IDENTIFIER_TYPES); the person types one box. */
internal fun vaultIdentifierType(identifier: String): String = when {
    identifier.contains('@') -> "email"
    identifier.matches(Regex("""\+?[0-9][0-9 ()\-.]{5,}""")) -> "phone"
    else -> "username"
}

/** "github.com", "https://github.com/login" and "https://github.com:8443" all name a site; Hermes stores `scheme://host[:port]` and fills only there. */
internal fun vaultSiteOrigin(input: String): String? = runCatching {
    val text = input.trim()
    if (text.isEmpty() || text.any { it.isWhitespace() || it.isISOControl() }) return@runCatching null
    val uri = URI(if ("://" in text) text else "https://$text")
    val host = uri.host?.lowercase() ?: return@runCatching null
    val local = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
    if (uri.userInfo != null || !(uri.scheme == "https" || uri.scheme == "http" && local)) return@runCatching null
    if (!local && !host.contains('.')) return@runCatching null
    if (uri.port == 0 || uri.port > 65535) return@runCatching null
    val port = uri.port.takeIf { it != -1 && !(uri.scheme == "https" && it == 443) && !(uri.scheme == "http" && it == 80) }
    "${uri.scheme}://$host" + (port?.let { ":$it" } ?: "")
}.getOrNull()

internal enum class VaultOp { Read, Add, Remove, Unlock, Lock, SourceSwitch }

/** The few things that can go wrong, each with one short line a person can act on. */
internal enum class VaultFailure { Unsupported, Offline, SignedOut, Rejected, Error }

/** Hermes without a native vault answers `unknown method` (JSON-RPC -32601); 5095 is the vault's own refusal (methods_vault.py). */
internal fun vaultFailure(error: Throwable): VaultFailure = when {
    error is ApiFailure && error.status == -32601 -> VaultFailure.Unsupported
    error is NativeAuthExpiredException || error is DeviceRemovedFailure || error is ApiFailure && error.status in setOf(401, 403) -> VaultFailure.SignedOut
    error is ApiFailure && error.status == 5095 -> VaultFailure.Rejected
    error is ApiFailure || error is PairingFailure -> VaultFailure.Error
    requestNeverSent(error) || error is java.io.IOException -> VaultFailure.Offline
    else -> VaultFailure.Error
}

/** Hermes' own wording is never shown: a 5095 message can carry a command-line tool's text. Known refusals get a precise line, the rest a line for the action. */
internal fun vaultErrorText(op: VaultOp, error: Throwable): String {
    val message = (error as? ApiFailure)?.message.orEmpty().lowercase()
    return when (vaultFailure(error)) {
        VaultFailure.Unsupported -> "This Hermes doesn't have a Vault. Update Hermes to use it."
        VaultFailure.Offline -> "Can't reach Hermes"
        VaultFailure.SignedOut -> "Sign in again"
        VaultFailure.Rejected -> when {
            "master password is required" in message -> "Enter the master password."
            "not an enabled password manager" in message -> "Turn that manager on first."
            "authenticator key" in message -> "Check the authenticator key."
            "origin" in message -> "Check the site address."
            "require" in message || "required" in message -> "Fill in every required field."
            else -> when (op) {
                VaultOp.Add -> "Hermes couldn't save that. Check the details."
                VaultOp.Remove -> "Couldn't remove it."
                VaultOp.Unlock -> "Couldn't unlock. Check the master password."
                VaultOp.Lock -> "Couldn't lock it."
                VaultOp.SourceSwitch -> "Couldn't change that."
                VaultOp.Read -> "Vault unavailable"
            }
        }
        VaultFailure.Error -> when (op) {
            VaultOp.Read -> "Vault unavailable"
            VaultOp.Add -> "Couldn't confirm the save. Refresh to check."
            VaultOp.Remove -> "Couldn't confirm the removal. Refresh to check."
            else -> "Couldn't finish. Try again."
        }
    }
}

/** The seven `vault.*` methods and nothing else. */
internal class NativeVaultApi(private val call: suspend (String, JSONObject) -> JSONObject) {
    constructor(client: BeaconClient) : this({ method, params -> client.nativeRpc(method, params) })

    suspend fun snapshot(): VaultSnapshot {
        val items = call("vault.list", JSONObject())
        val sources = call("vault.sources", JSONObject())
        return VaultSnapshot.from(JSONObject().put("items", items.optJSONArray("items") ?: JSONArray()).put("sources", sources.optJSONArray("sources") ?: JSONArray()))
    }

    suspend fun setSource(name: String, enabled: Boolean) {
        val result = call("vault.source.set", JSONObject().put("name", name).put("enabled", enabled))
        check(result.opt("enabled") == enabled) { "Source change was not confirmed" }
    }

    /** The master password is consumed by Hermes' manager CLI and is never stored or logged there; nothing here keeps it. */
    suspend fun unlock(name: String, password: String) {
        val result = call("vault.unlock", JSONObject().put("name", name).put("password", password))
        check(result.opt("unlocked") == true) { "Unlock was not confirmed" }
    }

    /** No name locks every manager. */
    suspend fun lock(name: String? = null) {
        val result = call("vault.lock", JSONObject().apply { if (name != null) put("name", name) })
        check(result.opt("locked") == true) { "Lock was not confirmed" }
    }

    suspend fun add(draft: VaultDraft): String {
        val secret = JSONObject().also { body -> draft.secret.forEach { (key, value) -> body.put(key, value) } }
        val params = JSONObject().put("kind", draft.kind.wire).put("label", draft.label).put("secret", secret)
        if (draft.origin.isNotBlank()) params.put("origin", draft.origin)
        val result = call("vault.add", params)
        return (result.opt("id") as? String)?.takeIf(String::isNotBlank) ?: error("Save was not confirmed")
    }

    suspend fun remove(id: String) {
        val result = call("vault.remove", JSONObject().put("id", id))
        check(result.opt("removed") == true) { "Removal was not confirmed" }
    }
}

/** Where an item comes from, in words: `local` is Hermes' own vault; a manager is named by its source row. */
internal fun vaultBadge(item: VaultItem, sources: List<VaultSource>): String =
    if (item.backend == "local") "Hermes" else sources.firstOrNull { it.name == item.backend }?.displayName ?: item.backend

/** Secret prompts Hermes sends to the phone. Timeouts are Hermes' own (gateway/session_vault_prompt.py 26-28, session_secret_prompt.py 19). */
private val SECURE_PROMPT_WINDOW_MS = mapOf("vault.unlock_prompt" to 120_000L, "vault.save_login" to 180_000L, "vault.code" to 180_000L, "secret" to 120_000L)
internal fun securePromptWindowMs(method: String): Long = SECURE_PROMPT_WINDOW_MS[method] ?: 120_000L

internal data class SecurePrompt(val scope: Scope, val runtime: String, val id: String, val method: String,
    val connection: String, val expiresAt: Long, val originGeneration: Int, val sessionGeneration: Int, val detail: String)

/** Hermes' one question as the keeper and the sheet know it: the same on every connection it is asked on (the connection marker is not part of it). */
internal fun securePromptQuestionKey(prompt: SecurePrompt): String = prompt.scope.key + "|" + prompt.method + "|" + prompt.id

internal fun securePromptFrom(scope: Scope, originGeneration: Int, sessionGeneration: Int, value: JSONObject): SecurePrompt? {
    val method = value.optString("secure_method")
    if (method !in setOf("vault.save_login", "vault.code", "vault.unlock_prompt", "secret")) return null
    val id = value.optString("id")
    val connection = value.optString("secure_connection")
    val runtime = value.optString("stream_id")
    val expiresAt = value.optLong("secure_expires_ms", 0)
    if (!id.matches(Regex("[A-Za-z0-9_.:-]{1,160}")) || !connection.matches(Regex("[A-Za-z0-9-]{1,80}")) ||
        !runtime.matches(Regex("[A-Za-z0-9_.:-]{1,256}")) || expiresAt <= System.nanoTime() / 1_000_000) return null
    val detail = when (method) {
        "vault.save_login" -> value.optString("origin")
        "vault.code" -> value.optString("site")
        "vault.unlock_prompt" -> value.optString("display_name")
        else -> listOf(value.optString("env_var"), value.optString("prompt")).filter(String::isNotBlank).joinToString(" · ")
    }
    if (detail.isBlank() || detail.length > 2048 || detail.any(Char::isISOControl)) return null
    return SecurePrompt(scope, runtime, id, method, connection, expiresAt, originGeneration, sessionGeneration, detail)
}

/** Show it now, keep it for later (app in the background or the phone locked), or let it go. */
internal enum class SecureDisposition { Show, Hold, Drop }

/**
 * A prompt that is still Hermes' open question is only dropped when it is no longer this account, profile and chat, the person signed out,
 * or Hermes' own time ran out. Leaving the app or locking the phone holds it: the answer is still wanted when the person comes back.
 */
internal fun securePromptDisposition(prompt: SecurePrompt, scope: Scope?, originGeneration: Int, sessionGeneration: Int,
    foreground: Boolean, authenticated: Boolean, locked: Boolean, now: Long): SecureDisposition = when {
    !authenticated || prompt.scope != scope || prompt.originGeneration != originGeneration || prompt.sessionGeneration != sessionGeneration || now >= prompt.expiresAt -> SecureDisposition.Drop
    !foreground || locked -> SecureDisposition.Hold
    else -> SecureDisposition.Show
}

internal fun securePromptCurrent(prompt: SecurePrompt, scope: Scope?, originGeneration: Int, sessionGeneration: Int,
    foreground: Boolean, authenticated: Boolean, locked: Boolean, now: Long): Boolean =
    securePromptDisposition(prompt, scope, originGeneration, sessionGeneration, foreground, authenticated, locked, now) == SecureDisposition.Show
