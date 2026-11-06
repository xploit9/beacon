package dev.hermes.android.data

import dev.hermes.android.core.Scope
import org.json.JSONObject
import java.net.URI

internal data class VaultMetadata(
    val id: String, val kind: String, val label: String, val origin: String,
    val identifierType: String = "", val identifier: String = "", val hasOtp: Boolean = false,
) {
    companion object {
        fun from(value: JSONObject): VaultMetadata {
            fun text(key: String, limit: Int): String = (value.opt(key) as? String).orEmpty().also {
                require(it.length <= limit && it.none(Char::isISOControl)) { "Invalid Vault metadata" }
            }
            val id = text("id", 160)
            val kind = text("kind", 20)
            val label = text("label", 200)
            require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")) && kind in setOf("login", "payment", "address") && label.isNotBlank()) { "Invalid Vault metadata" }
            val identifierType = text("identifier_type", 20)
            require(identifierType.isBlank() || identifierType in setOf("email", "phone", "username")) { "Invalid Vault metadata" }
            return VaultMetadata(id, kind, label, text("origin", 2048), identifierType, text("identifier", 320), value.opt("has_otp") == true)
        }
    }
}

internal data class VaultCapabilities(val supported: Boolean, val local: Boolean, val signup: Boolean, val otp: Boolean, val securePrompts: Boolean) {
    companion object {
        fun from(value: JSONObject?) = VaultCapabilities(value?.opt("supported") == true, value?.opt("local") == true,
            value?.opt("signup") == true, value?.opt("otp") == true, value?.opt("secure_prompts") == true)
    }
}

internal fun vaultCanGenerate(capabilities: VaultCapabilities): Boolean = capabilities.supported && capabilities.local

internal data class VaultIdentity(val name: String, val email: String, val status: String) {
    companion object {
        fun from(value: JSONObject?): VaultIdentity {
            fun text(key: String, limit: Int): String {
                val raw = value?.opt(key)
                require(raw == null || raw == JSONObject.NULL || raw is String) { "Invalid Vault identity" }
                return (raw as? String).orEmpty().also { require(it.length <= limit && it.none(Char::isISOControl)) { "Invalid Vault identity" } }
            }
            val name = text("name", 120)
            val email = text("email", 320)
            val status = (value?.opt("status") as? String)?.takeIf { it in setOf("not_configured", "unverified", "ready", "unavailable") } ?: "unavailable"
            return VaultIdentity(name, email, status)
        }
    }
}

internal data class VaultSnapshot(val items: List<VaultMetadata>, val capabilities: VaultCapabilities, val identity: VaultIdentity) {
    companion object {
        fun from(value: JSONObject): VaultSnapshot {
            val rows = value.getJSONArray("items")
            val items = (0 until rows.length()).map { VaultMetadata.from(rows.getJSONObject(it)) }
            require(items.map { it.id }.distinct().size == items.size) { "Invalid Vault metadata" }
            return VaultSnapshot(items, VaultCapabilities.from(value.optJSONObject("capabilities")), VaultIdentity.from(value.optJSONObject("identity")))
        }
    }
}

internal fun vaultSavedItem(value: JSONObject, requestId: String): VaultMetadata {
    require(value.optString("request_id") == requestId) { "Vault save was not confirmed" }
    return VaultMetadata.from(value.getJSONObject("item"))
}

internal data class SecurePrompt(val scope: Scope, val runtime: String, val id: String, val method: String,
    val connection: String, val expiresAt: Long, val originGeneration: Int, val sessionGeneration: Int, val detail: String)

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

internal fun securePromptCurrent(prompt: SecurePrompt, scope: Scope?, originGeneration: Int, sessionGeneration: Int,
    foreground: Boolean, authenticated: Boolean, locked: Boolean, now: Long): Boolean =
    foreground && authenticated && !locked && prompt.scope == scope && prompt.originGeneration == originGeneration &&
        prompt.sessionGeneration == sessionGeneration && now < prompt.expiresAt

internal fun validVaultOrigin(value: String): Boolean = runCatching {
    val uri = URI(value)
    uri.host ?: return@runCatching false
    uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && uri.rawPath in setOf("", "/") && uri.port in -1..65535 && uri.port != 0
}.getOrDefault(false)
