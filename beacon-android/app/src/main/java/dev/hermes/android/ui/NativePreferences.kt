package dev.hermes.android.ui

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.json.JSONObject

val LocalBeaconSettings = staticCompositionLocalOf { JSONObject() }
val NativeLanguages = linkedMapOf("en" to "English", "ar" to "العربية", "it" to "Italiano", "ja" to "日本語", "ru" to "Русский", "es" to "Español", "de" to "Deutsch", "zh" to "简体中文", "zh-Hant" to "繁體中文", "pt" to "Português", "ko" to "한국어", "fr" to "Français", "cs" to "Čeština", "tr" to "Türkçe", "pl" to "Polski", "vi" to "Tiếng Việt")
internal fun nativeLanguageFor(locale: java.util.Locale): String {
    val language = locale.toLanguageTag().substringBefore('-')
    if (language == "zh") return if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh"
    return language.takeIf { it in NativeLanguages } ?: "en"
}
// One shared empty dictionary: English and a catalog still loading look nothing up.
private val NoStrings = JSONObject()
private val LocalUiStrings = staticCompositionLocalOf { NoStrings }
/** The language the screen is shown in, set by [NativeLocalization]; blank outside it. */
private val LocalUiLanguage = staticCompositionLocalOf { "" }
/** The locale a date or time is drawn in: the shown language, keeping the device's own region when the device is set to that language (British English stays "12 Nov"). */
internal fun uiLocaleFor(language: String, device: java.util.Locale): java.util.Locale =
    if (language.isBlank() || nativeLanguageFor(device) == language) device else java.util.Locale.forLanguageTag(language)
@Composable fun uiLocale(): java.util.Locale = uiLocaleFor(LocalUiLanguage.current, androidx.compose.ui.platform.LocalConfiguration.current.locales[0])
internal val nativeAliases = mapOf("Conversations" to "Sessions", "Spaces" to "Workspaces", "Connect securely" to "Sign in", "Beacon password" to "Password", "Model & provider" to "Model", "Providers & models" to "Providers", "Tools & commands" to "Tools", "App updates" to "Updates", "Search conversations" to "Search sessions", "New conversation" to "New chat", "Search this conversation" to "Search in conversation", "Undo last turn" to "Undo", "Retry last turn" to "Retry", "Attach a file" to "Attach file", "Stop response" to "Stop", "Dictate message" to "Dictate", "Toolsets" to "Toolsets")
private val whitespace = Regex("\\s+")
internal fun normalized(value: String) = value.lowercase().replace("…", "...").replace(whitespace, " ").trim()

/** Bundled Hermes WebUI translations plus Beacon interface text. Message content stays verbatim. */
@Composable fun NativeLocalization(language: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as dev.hermes.android.BeaconApplication
    // English shows the app's own words as written, so it never reads the catalog; its entries differ in case and would only change them.
    val catalogs by produceState(app.cachedTranslations, app, language) {
        if (value == null && language != "en") value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { app.readTranslations() }
    }
    CompositionLocalProvider(LocalUiStrings provides (catalogs?.optJSONObject(language)?.takeIf { language != "en" } ?: NoStrings), LocalUiLanguage provides language, content = content)
}
/** The assistant's display name from Settings › Assistant (the app itself stays Beacon). */
val LocalAssistantName = staticCompositionLocalOf { dev.hermes.android.data.DEFAULT_ASSISTANT_NAME }
/** Fixed words that name the assistant, localized, then carrying the owner's chosen name. */
internal fun assistantName(value: String, name: String): String =
    value.replace(dev.hermes.android.data.DEFAULT_ASSISTANT_NAME, name)

@Composable fun assistantText(value: String): String {
    val name = LocalAssistantName.current
    return assistantName(uiText(value), name)
}
/** English and a catalog still loading share the empty dictionary: nothing to look up, so skip the normalizing (it runs ~200 times per frame). */
internal fun translateUi(dictionary: JSONObject, value: String): String {
    if (dictionary === NoStrings) return value
    return dictionary.optString(normalized(value)).ifBlank { dictionary.optString(normalized(nativeAliases[value] ?: value)).ifBlank { value } }
}
internal val EmptyUiStrings: JSONObject get() = NoStrings
@Composable fun uiText(value: String): String = translateUi(LocalUiStrings.current, value)
/** A translator for code that picks its text outside a composable: the current language's table, read once here. */
@Composable internal fun uiTextLookup(): (String) -> String { val strings = LocalUiStrings.current; return { translateUi(strings, it) } }
