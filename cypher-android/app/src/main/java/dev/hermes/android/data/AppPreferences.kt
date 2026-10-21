package dev.hermes.android.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

const val DEFAULT_ASSISTANT_NAME = "Beacon"
const val DEFAULT_ASSISTANT_PROFILE = "default"
internal const val ASSISTANT_NAME_MAX = 32
/** shortcut: process-wide copy of Settings › Assistant name for services, wires and callbacks; one process, so a volatile is enough. */
@Volatile var currentAssistantName: String = DEFAULT_ASSISTANT_NAME

/** A blank or control-character name falls back to Beacon. Never sent to Hermes; the phone's realtime voice uses it as its spoken name. */
internal fun assistantNameOf(raw: String?): String = raw?.trim()?.take(ASSISTANT_NAME_MAX)?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: DEFAULT_ASSISTANT_NAME

data class AppPreferences(
    val theme: String = "system", val skin: String = "default", val historyHiddenSources: Set<String> = emptySet(), val historyOrder: String = "recent",
    val assistantName: String = DEFAULT_ASSISTANT_NAME, val assistantProfile: String = DEFAULT_ASSISTANT_PROFILE,
    /** Blank = the profile's default model. */
    val assistantModel: String = "", val assistantProvider: String = "",
) {
    companion object {
        fun read(p: Preferences) = AppPreferences(
            p[stringPreferencesKey("theme")] ?: "system", p[stringPreferencesKey("skin")] ?: "default",
            p[stringPreferencesKey("history_hidden_sources")].orEmpty().split(',').filter(String::isNotBlank).toSet(), p[stringPreferencesKey("history_order")] ?: "recent",
            assistantNameOf(p[stringPreferencesKey("assistant_name")]), p[stringPreferencesKey("assistant_profile")]?.takeIf(String::isNotBlank) ?: DEFAULT_ASSISTANT_PROFILE,
            p[stringPreferencesKey("assistant_model")].orEmpty(), p[stringPreferencesKey("assistant_provider")].orEmpty(),
        )
    }
}
