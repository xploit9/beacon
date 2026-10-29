package dev.hermes.android.data

import dev.hermes.android.core.json
import dev.hermes.android.core.rows
import dev.hermes.android.core.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject

// Making, describing and removing a profile are Hermes' own routes (hermes_cli/web_routers/profiles.py: POST /api/profiles,
// PUT /api/profiles/{name}/description, DELETE /api/profiles/{name}). The name rule is hermes_constants.PROFILE_ID_RE with
// hermes_cli/profiles.py normalize_profile_name and _RESERVED_NAMES, so the phone refuses what Hermes would.

private val profileId = Regex("[a-z0-9][a-z0-9_-]{0,63}")
// "active" is also refused: /api/profiles/active is Hermes' own route, so such a profile could never be deleted from here.
private val reservedProfileNames = setOf("hermes", "default", "test", "tmp", "root", "sudo", "active")
internal const val PROFILE_ROLE_MAX = 280

/** The id Hermes stores for what was typed: trimmed and lower case. */
internal fun profileNameOf(input: String): String = input.trim().lowercase()

/** Why a typed profile name cannot be used; [text] is shown through uiText. */
internal enum class ProfileNameProblem(val text: String) {
    Empty("Enter a name."),
    Invalid("Use lowercase letters, numbers, - or _, up to 64 characters."),
    Reserved("That name is reserved. Choose another."),
    Taken("A profile with that name already exists."),
}

/** What is wrong with [input] as a new profile's name, or null. [existing] are the names Hermes already lists. */
internal fun profileNameProblem(input: String, existing: Collection<String>): ProfileNameProblem? {
    val name = profileNameOf(input)
    return when {
        name.isEmpty() -> ProfileNameProblem.Empty
        !profileId.matches(name) -> ProfileNameProblem.Invalid
        name in reservedProfileNames -> ProfileNameProblem.Reserved
        existing.any { it.equals(name, ignoreCase = true) } -> ProfileNameProblem.Taken
        else -> null
    }
}

/** Delete is never offered for Hermes' built-in profile, the one in use, the one the assistant runs on, or [returning],
 *  the profile leaving Bot Mode switches back to. */
internal fun profileDeletable(name: String, active: String, assistant: String, returning: String = ""): Boolean =
    profileId.matches(name) && name != "default" && name != "active" && name != active && name != assistant && name != returning

/** The body of POST /api/profiles. A copy is Hermes' clone: the source's settings, keys, skills and memory notes; only messaging channels are left out. */
internal fun newProfileBody(name: String, copyFrom: String, role: String): JSONObject = json("name" to profileNameOf(name)).apply {
    if (copyFrom.isNotBlank()) put("clone_from", copyFrom)
    role.trim().take(PROFILE_ROLE_MAX).takeIf { it.isNotEmpty() }?.let { put("description", it) }
}

/** How Hermes answered DELETE: gone, gone with a clean-up it could not finish (its `settlement_pending`), or not confirmed. */
internal enum class ProfileDelete { Done, DonePending }

internal fun profileDeleteOutcome(result: JSONObject): ProfileDelete? = when {
    !result.optBoolean("ok") -> null
    result.optBoolean("settlement_pending") -> ProfileDelete.DonePending
    else -> ProfileDelete.Done
}

/** [completed] counts finished changes, so a dialog closes when it grows; [open] is a profile Settings should show next. */
data class NativeProfileAdminState(val busy: Boolean = false, val error: String = "", val completed: Int = 0, val open: String = "")

/** Creates, describes and deletes profiles for Settings › Profiles and Bot Mode; [changed] gets the deleted name (blank otherwise) and a notice. */
class NativeProfileAdmin(
    private val scope: CoroutineScope,
    private val client: () -> BeaconClient?,
    private val changed: (deleted: String, notice: String) -> Unit,
) {
    private val mutable = MutableStateFlow(NativeProfileAdminState())
    val state = mutable.asStateFlow()

    private fun fail(text: String) = mutable.update { it.copy(error = text) }
    fun clearError() = mutable.update { it.copy(error = "") }
    /** Another account: nothing from the last one's dialogs carries over. */
    fun reset() { mutable.value = NativeProfileAdminState() }
    fun openInSettings(profile: String) = mutable.update { it.copy(open = profile) }
    fun opened() = mutable.update { it.copy(open = "") }

    /** Runs one change on a short-lived view of the signed-in client; [work] returns the notice, or null when Hermes did not confirm it. */
    private fun perform(deleted: String = "", failure: (Exception) -> String = ::appErrorText, work: suspend (BeaconClient) -> String?) {
        val active = client() ?: run { fail("Reconnect, then try again."); return }
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = "") }
        scope.launch {
            val c = active.detached()
            try {
                val notice = work(c)
                val live = client()?.scope?.sameAccount(active.scope) == true
                when {
                    !live -> mutable.update { it.copy(busy = false) }
                    notice == null -> mutable.update { it.copy(busy = false, error = "Hermes didn't confirm that. Refresh and try again.") }
                    else -> { mutable.update { it.copy(busy = false, completed = it.completed + 1) }; changed(deleted, notice) }
                }
            }
            catch (e: CancellationException) { mutable.update { it.copy(busy = false) }; throw e }
            catch (e: Exception) { mutable.update { it.copy(busy = false, error = failure(e)) } }
            finally { c.closeNative() }
        }
    }

    fun create(name: String, copyFrom: String, role: String, existing: Collection<String>) {
        profileNameProblem(name, existing)?.let { fail(it.text); return }
        val body = newProfileBody(name, copyFrom, role)
        // A name taken since the list was read comes back as Hermes' 400 "already exists".
        val taken = { e: Exception -> if (e is ApiFailure && e.status == 400 && e.detail.contains("already exists", ignoreCase = true)) ProfileNameProblem.Taken.text else appErrorText(e) }
        perform(failure = taken) { c -> "Profile created".takeIf { c.nativeRequest("/api/profiles", "POST", body).optBoolean("ok") } }
    }

    fun delete(name: String, assistant: String, returning: String = "") {
        if (!profileDeletable(name, client()?.scope?.profile.orEmpty(), assistant, returning)) { fail("This profile can't be deleted."); return }
        perform(deleted = name) { c ->
            val result = try { c.nativeRequest("/api/profiles/$name", "DELETE") }
            // A 404 is done only if Hermes no longer lists the profile (a retry after a lost answer), not an old Hermes or a stale name.
            catch (e: ApiFailure) {
                if (e.status != 404) throw e
                val listed = c.nativeGet("/api/profiles")
                return@perform "Profile deleted".takeIf { listed.has("profiles") && listed.rows("profiles").none { it.string("name") == name } }
            }
            when (profileDeleteOutcome(result)) {
                ProfileDelete.Done -> "Profile deleted"
                ProfileDelete.DonePending -> "Profile deleted. Hermes still has to finish cleaning up on your computer."
                null -> null
            }
        }
    }

    fun setRole(name: String, role: String) {
        if (!profileId.matches(name)) { fail("This profile can't be changed."); return }
        val body = json("description" to role.trim().take(PROFILE_ROLE_MAX))
        perform { c -> "Role saved".takeIf { c.nativeRequest("/api/profiles/$name/description", "PUT", body).optBoolean("ok") } }
    }
}
