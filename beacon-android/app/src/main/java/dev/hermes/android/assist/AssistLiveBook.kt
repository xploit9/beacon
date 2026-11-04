package dev.hermes.android.assist

/** What the overlay does when a call turns active. */
internal enum class LiveActive {
    /** The overlay closed while this call was still starting: it ends the moment it exists. */
    HangUp,
    /** The call this overlay asked for has started. */
    Adopted,
    /** A call the overlay did not start (an answered one, the chat's own): left alone. */
    Other,
}

/**
 * The overlay's account of the conversation it starts: asked for and not started yet ([wanted]), running here ([here]), and whether a
 * close that came while the call was starting must end that call.
 *
 * A cancel belongs to the start it was armed for and to nothing else. The Android assistant session survives every hide, so a flag
 * that outlived its start would hang up whichever call turned active next (one the owner answered, the chat's own). It is armed only
 * while a start is in flight ([launched] to [settled]), and then it ends only a call that started after that start began.
 */
internal class AssistLiveBook {
    /** A conversation was asked for here and has not started yet. */
    var wanted = false; private set
    /** A conversation this overlay started is running; closing the overlay ends it, where a call the app is on carries on. */
    var here = false; private set
    private var cancelled = false
    /** When the start in flight began (the phone's clock, as a call's own start is stamped), or [NO_START]. */
    private var launchedAt = NO_START
    /** This ask already had its one automatic second try. */
    private var retried = false
    /** The conversation running here got going (it listened or spoke); a failure after that is a dropped call, not a failed start. */
    private var reachedLive = false

    /** A fresh show clears what an earlier one left. */
    fun shown() { cancelled = false }

    /** A conversation is asked for: by the owner, or as the automatic second try ([retry]), which keeps this ask's one try spent. */
    fun requested(retry: Boolean = false) { wanted = true; cancelled = false; if (!retry) retried = false }

    /** The ask ended without a call (no sign-in, no key, it failed to start): nothing is left to cancel. Returns whether it was still wanted. */
    fun gaveUp(): Boolean { if (!wanted) return false; wanted = false; return true }

    /** The call is about to be started: from here a close has a call to end. */
    fun launched(at: Long) { launchedAt = at }

    /**
     * The start returned or failed. True when it returned a call that was cancelled meanwhile and is still up, which must end now;
     * the cancel never outlives the start either way.
     */
    fun settled(returned: Boolean, active: Boolean, startedAt: Long): Boolean {
        val own = cancelled && returned && active && startedAt >= launchedAt && launchedAt != NO_START
        cancelled = false
        launchedAt = NO_START
        return own
    }

    /** The orb or a close: ends the conversation. Returns whether a start was still pending, which the card then drops. */
    fun end(): Boolean {
        val pending = wanted
        if (wanted) { wanted = false; cancelled = launchedAt != NO_START }
        here = false
        return pending
    }

    /** Something to end: a start in flight or a conversation running here. */
    fun closing() = wanted || here

    /** A call turned active, started at [startedAt]. */
    fun callActive(startedAt: Long): LiveActive {
        if (cancelled && startedAt >= launchedAt) { cancelled = false; launchedAt = NO_START; wanted = false; here = false; return LiveActive.HangUp }
        if (wanted) { wanted = false; here = true; reachedLive = false; return LiveActive.Adopted }
        return LiveActive.Other
    }

    /** The conversation running here listened or spoke. */
    fun heard() { if (here) reachedLive = true }

    /**
     * A start that failed before it got going (Android or the network turning it away for a moment): true once per ask, when it is
     * worth one more try before the owner sees it fail. [running] says the failure ended a call started here, else the start itself failed.
     */
    fun retryOnce(running: Boolean): Boolean {
        val failedStart = if (running) here && !wanted && !reachedLive else wanted
        if (!failedStart || retried) return false
        retried = true
        return true
    }

    /** A call ended while nothing was being asked for: the overlay no longer runs one. */
    fun callEnded() { if (!wanted) here = false }

    private companion object { const val NO_START = Long.MIN_VALUE }
}
