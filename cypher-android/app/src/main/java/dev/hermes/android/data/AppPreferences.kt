package dev.hermes.android.data

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey

data class AppPreferences(val theme: String = "system", val skin: String = "default", val fontSize: String = "default", val language: String = "en", val rtl: Boolean = false, val reducedMotion: Boolean = false, val historyHiddenSources: Set<String> = emptySet(), val historyOrder: String = "recent") {
    companion object {
        fun read(p: Preferences) = AppPreferences(p[stringPreferencesKey("theme")] ?: "system", p[stringPreferencesKey("skin")] ?: "default", p[stringPreferencesKey("font_size")] ?: "default", p[stringPreferencesKey("language")] ?: "en", p[stringPreferencesKey("rtl")] == "true", p[stringPreferencesKey("reduced_motion")] == "true", p[stringPreferencesKey("history_hidden_sources")].orEmpty().split(',').filter(String::isNotBlank).toSet(), p[stringPreferencesKey("history_order")] ?: "recent")
    }
}
