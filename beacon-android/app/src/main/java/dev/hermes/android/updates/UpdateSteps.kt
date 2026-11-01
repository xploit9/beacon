package dev.hermes.android.updates

/** Where one step of an update stands. [Waiting] before it starts (or, with a note, held back on purpose), [Active] while it runs. */
internal enum class StepState { Waiting, Active, Done, Failed }

/** The steps an update can pass through; a step the update does not need is removed from the list, never shown as done. */
internal enum class UpdateStep(val label: String, val app: Boolean = false) {
    PluginDownload("Downloading"), PluginInstall("Installing"), PluginGateway("Restarting Hermes gateway"), PluginDashboard("Restarting Hermes dashboard"), PluginDone("Done"),
    AppDownload("Downloading", app = true), AppVerify("Verifying", app = true), AppInstall("Installing", app = true), AppDone("Done", app = true);
    val last: Boolean get() = this == PluginDone || this == AppDone
}

/** [note] is the one short line a step may carry: why it failed, or what it is waiting for. */
internal data class StepRow(val step: UpdateStep, val state: StepState = StepState.Waiting, val note: String = "")

internal val UpdateStepStrings = UpdateStep.entries.map { it.label }.distinct()

/** The notes the steps carry; each goes through uiText when shown. A failure note never says "Try again": the button beside it does. */
internal object StepNote {
    const val Reconnecting = "Reconnecting…"
    const val DashboardFailed = "Didn't restart"
    const val DashboardManual = "Restart the Hermes dashboard on your computer."
    const val GatewayFailed = "Hermes gateway didn't restart"
    const val PluginFailed = "Didn't update"
    const val Busy = "Hermes is busy — restarts when it's idle"
    const val HermesBusy = "Hermes is busy"
    const val Call = "End your call before updating Beacon."
    val all = listOf(Reconnecting, DashboardFailed, DashboardManual, GatewayFailed, PluginFailed, Busy, HermesBusy, Call)
}

/** What TalkBack says after a step's label; each goes through uiText when shown. */
internal object StepStateText {
    const val Waiting = "Waiting"
    const val Working = "Working"
    const val Done = "Done"
    const val Failed = "Failed"
    /** The closing row waits with "Not yet" ("Done, not yet"), not "Done, Waiting". */
    const val NotYet = "Not yet"
    val all = listOf(Waiting, Working, Done, Failed, NotYet)
}

/**
 * The state TalkBack reads after the row's label: "Restarting Hermes gateway, Done". The closing Done row says "Not yet" until everything
 * finished and nothing after that (its label already says it, and its version follows). Blank means no state description.
 */
internal fun stepStateText(row: StepRow): String = when (row.state) {
    StepState.Waiting -> if (row.step.last) StepStateText.NotYet else StepStateText.Waiting
    StepState.Active -> StepStateText.Working
    StepState.Done -> if (row.step.last) "" else StepStateText.Done
    StepState.Failed -> StepStateText.Failed
}

/** What TalkBack speaks for one row, in order: the label, the note if any, then the state. [say] translates a text. */
internal fun stepReading(row: StepRow, say: (String) -> String = { it }): String =
    listOf(say(row.step.label), row.note.takeIf(String::isNotBlank)?.let(say).orEmpty(), stepStateText(row).takeIf(String::isNotBlank)?.let(say).orEmpty())
        .filter(String::isNotBlank).joinToString(", ")

/**
 * The checklist of one component (the Beacon plugin, or the app) as the update really moves through it. Every change is a fact the
 * caller observed: a reply arrived, a version was read, a restart finished. [onChange] gets each new list.
 */
internal class StepTracker(private val onChange: (List<StepRow>) -> Unit = {}) {
    var rows: List<StepRow> = emptyList()
        private set

    private fun update(change: (List<StepRow>) -> List<StepRow>) { rows = change(rows); onChange(rows) }
    private fun set(step: UpdateStep, state: StepState, note: String) =
        update { list -> if (list.none { it.step == step }) list else list.map { if (it.step == step) StepRow(step, state, note) else it } }

    /** Lists [steps] as waiting, then the closing Done. */
    fun plan(vararg steps: UpdateStep) = update {
        val closing = if (steps.firstOrNull()?.app == true) UpdateStep.AppDone else UpdateStep.PluginDone
        (steps.toList() + closing).map { StepRow(it) }
    }
    fun active(step: UpdateStep, note: String = "") = set(step, StepState.Active, note)
    fun done(step: UpdateStep) = set(step, StepState.Done, "")
    fun fail(step: UpdateStep, note: String) = set(step, StepState.Failed, note)
    /** Held back on purpose, for [note]'s reason: not failed, and the phone tries again by itself. */
    fun hold(step: UpdateStep, note: String) = set(step, StepState.Waiting, note)
    fun note(step: UpdateStep, note: String) = update { list -> list.map { if (it.step == step && it.state == StepState.Active) it.copy(note = note) else it } }
    /** This update does not need [step]. */
    fun skip(step: UpdateStep) = update { list -> list.filterNot { it.step == step } }
    fun skipAll(vararg steps: UpdateStep) = steps.forEach(::skip)
    /** Every step is done: the closing Done turns done too. */
    fun complete() = update { list ->
        if (list.isNotEmpty() && list.filterNot { it.step.last }.all { it.state == StepState.Done }) list.map { if (it.step.last) it.copy(state = StepState.Done) else it } else list
    }
    fun clear() = update { emptyList() }
    /** Replaces the list outright (a fresh start, or a list restored for the screen). */
    fun reset(list: List<StepRow>) = update { list }
}

/** The step a failed update stopped at, if any. */
internal val List<StepRow>.failed: StepRow? get() = firstOrNull { it.state == StepState.Failed }
/** The step that is running now, if any. */
internal val List<StepRow>.active: StepRow? get() = firstOrNull { it.state == StepState.Active }
