package dev.hermes.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

val LocalCypherSettings = staticCompositionLocalOf { JSONObject() }
val NativeLanguages = linkedMapOf("en" to "English", "ar" to "العربية", "it" to "Italiano", "ja" to "日本語", "ru" to "Русский", "es" to "Español", "de" to "Deutsch", "zh" to "简体中文", "zh-Hant" to "繁體中文", "pt" to "Português", "ko" to "한국어", "fr" to "Français", "cs" to "Čeština", "tr" to "Türkçe", "pl" to "Polski", "vi" to "Tiếng Việt")
internal fun nativeLanguageFor(locale: java.util.Locale): String {
    val language = locale.toLanguageTag().substringBefore('-')
    if (language == "zh") return if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh"
    return language.takeIf { it in NativeLanguages } ?: "en"
}
private val LocalUiStrings = staticCompositionLocalOf { JSONObject() }
private val nativeAliases = mapOf("Conversations" to "Sessions", "Spaces" to "Workspaces", "Connect securely" to "Sign in", "Cypher password" to "Password", "Model & provider" to "Model", "Providers & models" to "Providers", "Tools & commands" to "Tools", "App updates" to "Updates", "Search conversations" to "Search sessions", "New conversation" to "New chat", "Search this conversation" to "Search in conversation", "Undo last turn" to "Undo", "Retry last turn" to "Retry", "Attach a file" to "Attach file", "Stop response" to "Stop", "Dictate message" to "Dictate", "Toolsets" to "Toolsets")
private val whitespace = Regex("\\s+")
private fun normalized(value: String) = value.lowercase().replace("…", "...").replace(whitespace, " ").trim()

/** Bundled translations are data from the same frozen catalog as Hermes WebUI. Content stays verbatim. */
@Composable fun NativeLocalization(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as dev.hermes.android.CypherApplication
    val catalogs by produceState(app.cachedTranslations, app) {
        if (value == null) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.readTranslations() }
    }
    CompositionLocalProvider(LocalUiStrings provides (catalogs?.optJSONObject(language) ?: JSONObject()), content = content)
}
/** The assistant's display name from Settings › Assistant (the app itself stays Cypher). */
val LocalAssistantName = staticCompositionLocalOf { dev.hermes.android.data.DEFAULT_ASSISTANT_NAME }
/** Fixed words that name the assistant, localized, then carrying the owner's chosen name. */
@Composable fun assistantText(value: String): String {
    val name = LocalAssistantName.current
    return uiText(value).replace(dev.hermes.android.data.DEFAULT_ASSISTANT_NAME, name).replace("Cypher", name)
}
@Composable fun uiText(value: String): String {
    val dictionary = LocalUiStrings.current
    return dictionary.optString(normalized(value)).ifBlank { dictionary.optString(normalized(nativeAliases[value] ?: value)).ifBlank { value } }
}
