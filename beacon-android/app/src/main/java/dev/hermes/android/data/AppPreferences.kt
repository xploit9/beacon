package dev.hermes.android.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

const val DEFAULT_ASSISTANT_NAME = "Beacon"
const val DEFAULT_ASSISTANT_PROFILE = "default"
internal const val ASSISTANT_NAME_MAX = 32
/** shortcut: process-wide copy of Settings › Assistant name for services, wires and callbacks; one process, so a volatile is enough. */
@Volatile var currentAssistantName: String = DEFAULT_ASSISTANT_NAME

internal fun ownerNameOf(raw: String?): String = raw.orEmpty()
    .filter { it.isLetter() || it in " -'" }
    .take(40)
    .trim()
    .takeIf { name -> name.any(Char::isLetter) }
    .orEmpty()

/** A blank or control-character name falls back to Beacon. Never sent to Hermes; the phone's realtime voice uses it as its spoken name. */
internal fun assistantNameOf(raw: String?): String = raw?.trim()?.take(ASSISTANT_NAME_MAX)?.takeIf { it.isNotEmpty() && it.none(Char::isISOControl) } ?: DEFAULT_ASSISTANT_NAME

data class AppPreferences(
    val theme: String = "system", val skin: String = "default", val historyHiddenSources: Set<String> = emptySet(), val historyOrder: String = "recent",
    val assistantName: String = DEFAULT_ASSISTANT_NAME, val ownerName: String = "", val assistantProfile: String = DEFAULT_ASSISTANT_PROFILE,
    /** Blank = the profile's default model. */
    val assistantModel: String = "", val assistantProvider: String = "", val busySendAction: String = "steer", val busySendLoaded: Boolean = false,
) {
    companion object {
        fun read(p: Preferences) = AppPreferences(
            p[stringPreferencesKey("theme")] ?: "system", p[stringPreferencesKey("skin")] ?: "default",
            p[stringPreferencesKey("history_hidden_sources")].orEmpty().split(',').filter(String::isNotBlank).toSet(), p[stringPreferencesKey("history_order")] ?: "recent",
            assistantNameOf(p[stringPreferencesKey("assistant_name")]), ownerNameOf(p[stringPreferencesKey("owner_name")]),
            p[stringPreferencesKey("assistant_profile")]?.takeIf(String::isNotBlank) ?: DEFAULT_ASSISTANT_PROFILE,
            p[stringPreferencesKey("assistant_model")].orEmpty(), p[stringPreferencesKey("assistant_provider")].orEmpty(),
            p[stringPreferencesKey("busy_send_action")]?.takeIf { it in setOf("steer", "queue") } ?: "steer", true,
        )
    }
}
