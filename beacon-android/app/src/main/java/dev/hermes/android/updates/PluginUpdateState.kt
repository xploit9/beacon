package dev.hermes.android.updates

internal data class PluginVersions(val running: String, val installed: String) {
    val restartRequired: Boolean get() = running.isNotBlank() && installed.isNotBlank() && running != installed
    fun summary(): String = when {
        running.isBlank() || installed.isBlank() -> "Plugin version unavailable."
        restartRequired -> "$installed installed · $running running"
        else -> "$running running"
    }
}

internal data class PluginUpdateRowState(
    val versions: PluginVersions? = null,
    val checking: Boolean = false,
    val updating: Boolean = false,
    val consentRequired: Boolean = false,
    val error: Boolean = false,
) {
    val message: String get() = when {
        error -> "Couldn't check or update the Beacon plugin. Try again."
        checking -> "Checking plugin version…"
        updating -> "Updating the Beacon plugin…"
        consentRequired -> "Review capability changes to continue."
        versions == null -> "Connect to Hermes to check plugin status."
        versions.restartRequired -> "Restart Hermes on your PC to finish."
        else -> versions.summary()
    }

    val buttonText: String get() = when {
        checking -> "Checking…"
        updating -> "Updating…"
        consentRequired -> "Review changes"
        versions == null -> "Update plugin"
        else -> "Update plugin"
    }

    val canUpdate: Boolean get() = (versions != null || error) && !checking && !updating && !consentRequired && versions?.restartRequired != true
}
