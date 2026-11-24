package dev.hermes.android.voice

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import dev.hermes.android.BeaconApplication
import dev.hermes.android.core.Scope
import dev.hermes.android.core.rows
import dev.hermes.android.data.AppPreferences
import dev.hermes.android.data.DEFAULT_ASSISTANT_PROFILE
import dev.hermes.android.data.ScreenCache
import dev.hermes.android.data.assistantNameOf
import dev.hermes.android.preferences
import dev.hermes.android.ui.LocalAssistantName
import dev.hermes.android.ui.profileTitle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

internal val LocalCallAssistantProfile = staticCompositionLocalOf { DEFAULT_ASSISTANT_PROFILE }

/** The assistant's chosen name for its own profile, and the signed-in account's cached profile title for another caller. */
internal suspend fun callerDisplayName(app: BeaconApplication, scope: Scope): String {
    val preferences = AppPreferences.read(app.preferences.data.first())
    if (scope.profile == preferences.assistantProfile) return preferences.assistantName
    return coroutineScope {
        // Use the screen cache's own account key, profile filter and decoder, including on a cold notification.
        val screens = ScreenCache(read = { app.cache.read(it, "screens") }, write = { _, _ -> }, drop = {}, jobs = this)
        screens.open(scope)
        val profiles = screens.latest(scope).firstOrNull { it.key == "navigation-profiles" }?.value?.rows("profiles").orEmpty()
        assistantNameOf(profileTitle(scope.profile, profiles, preferences.assistantName, preferences.assistantProfile))
    }
}

@Composable internal fun rememberCallerDisplayName(scope: Scope?): String {
    val fallback = LocalAssistantName.current
    if (scope == null) return fallback
    val app = LocalContext.current.applicationContext as BeaconApplication
    return key(scope.origin, scope.owner, scope.nativeProvider, scope.profile, fallback) {
        val initial = profileTitle(scope.profile, emptyList(), fallback, LocalCallAssistantProfile.current)
        produceState(initial) { value = callerDisplayName(app, scope) }.value
    }
}
