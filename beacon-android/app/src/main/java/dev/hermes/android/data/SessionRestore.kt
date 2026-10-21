package dev.hermes.android.data

import dev.hermes.android.core.Scope

// The provider hint is not authority; either native token can recover the encrypted vault.
internal fun nativeSessionCookie(name: String): Boolean =
    listOf("", "__Host-", "__Secure-").any { prefix -> name == "${prefix}hermes_session_at" || name == "${prefix}hermes_session_rt" }

// Calls own a detached session. Recreating the UI must not stop the same owner's call.
internal fun keepsRestoredCall(call: Scope?, restored: Scope?, replacingAccount: Boolean): Boolean =
    !replacingAccount && call != null && restored != null &&
        call.origin == restored.origin && call.owner.isNotBlank() && call.owner == restored.owner
