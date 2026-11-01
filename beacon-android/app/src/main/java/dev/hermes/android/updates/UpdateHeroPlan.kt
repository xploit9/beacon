package dev.hermes.android.updates

/** The one primary button the update hero can carry. [label] is the English text uiText translates. */
internal enum class HeroPrimary(val label: String) {
    Update("Update"), RestartHermes("Restart Hermes"), RestartDashboard("Restart dashboard"),
    /** Runs the update again from the plugin row's state. */
    TryAgain("Try again"),
    /** The app update failed: asks GitHub again or repeats the update, as the failure says. */
    RetryFailed("Try again"),
    Allow("Allow"),
}

/** The text button beside it. */
internal enum class HeroSecondary(val label: String) { Check("Check for updates"), Cancel("Cancel"), How("How") }

/** What the hero offers: at most one primary action and one text action. */
internal data class HeroActions(val primary: HeroPrimary? = null, val secondary: HeroSecondary? = null)

/** Every action label the hero shows through uiText. */
internal val HeroActionStrings = (HeroPrimary.entries.map { it.label } + HeroSecondary.entries.map { it.label }).distinct()

/** Where the "How" action opens: the README section on restarting the Hermes dashboard. */
internal const val RESTART_DASHBOARD_HELP_URL = "https://github.com/xploit9/beacon#restart-the-hermes-dashboard"

/** The phases in which an update is running: the app downloads, verifies or installs. */
private val RunningPhases = setOf(UpdatePhase.Downloading, UpdatePhase.Verifying, UpdatePhase.Installing)

/**
 * The actions the hero carries for [phase] of [state]: one primary action per screen, whose own words say what it does.
 * Cancel is offered only while the app downloads and verifies; the plugin step cannot be cancelled (Hermes carries on without the phone).
 */
internal fun heroActions(state: AppUpdateUi, phase: UpdatePhase): HeroActions = when {
    !state.supported -> HeroActions()
    state.plugin.status == PluginStatus.Updating -> HeroActions()
    phase == UpdatePhase.Idle || phase == UpdatePhase.Checking -> HeroActions(secondary = HeroSecondary.Check)
    phase == UpdatePhase.Available -> availableActions(state)
    phase == UpdatePhase.Downloading || phase == UpdatePhase.Verifying -> HeroActions(secondary = HeroSecondary.Cancel)
    phase == UpdatePhase.Installing -> HeroActions()
    phase == UpdatePhase.NeedsPermission -> HeroActions(primary = HeroPrimary.Allow)
    else -> HeroActions(primary = HeroPrimary.RetryFailed)
}

/** An app release is waiting, or only the plugin has something for the owner. */
private fun availableActions(state: AppUpdateUi): HeroActions {
    if (state.release != null) return HeroActions(primary = HeroPrimary.Update)
    return when (state.plugin.status) {
        PluginStatus.Restart -> HeroActions(primary = HeroPrimary.RestartHermes)
        PluginStatus.RestartFailed, PluginStatus.Failed -> HeroActions(primary = HeroPrimary.TryAgain)
        PluginStatus.DashboardFailed -> HeroActions(primary = HeroPrimary.RestartDashboard)
        PluginStatus.Busy -> HeroActions(primary = HeroPrimary.Update)
        PluginStatus.Waiting, PluginStatus.Newer, PluginStatus.NeedsReview -> HeroActions()
        PluginStatus.DashboardRestart -> HeroActions(secondary = HeroSecondary.How)
        else -> HeroActions(primary = HeroPrimary.Update)
    }
}

/**
 * The stepper's own Try again row. It shows only when a step failed, the hero carries no action of its own (the hero's is the one primary
 * action), and no update is running (a Try again then would do nothing).
 */
internal fun stepperRetryShown(state: AppUpdateUi, rows: List<StepRow>, hero: HeroActions): Boolean =
    rows.failed != null && hero.primary == null && state.phase !in RunningPhases

/** Progress shows once: with a checklist on screen its running step carries it, so the hero has no bar. */
internal fun heroBarShown(state: AppUpdateUi): Boolean = state.pluginSteps.isEmpty() && state.appSteps.isEmpty()

/** The hero's download line: the percent too only when no checklist shows it on the step. [of] is "{0} of {1}" in the phone's language. */
internal fun heroProgressLine(state: AppUpdateUi, total: Long, of: String = "{0} of {1}"): String =
    if (state.appSteps.isEmpty()) progressLine(state.downloaded, total, of) else progressSizeLine(state.downloaded, total, of)

/** Plugin states that carry a sentence of their own: the hero says it when only the plugin has something to do, the plugin row says it beside an app update. */
internal val PluginSentenceStates = setOf(PluginStatus.Busy, PluginStatus.Failed, PluginStatus.Waiting, PluginStatus.DashboardRestart, PluginStatus.RestartFailed,
    PluginStatus.DashboardFailed, PluginStatus.Newer)

/** The sentence the hero already says for [phase] of [state], in English (before uiText). A step note that repeats it is not shown again. */
internal fun heroSentence(state: AppUpdateUi, phase: UpdatePhase): String = when {
    !state.supported || state.plugin.status == PluginStatus.Updating -> ""
    phase == UpdatePhase.Available && state.release == null && state.plugin.status in PluginSentenceStates -> state.plugin.text
    phase == UpdatePhase.Failed -> (state.failure ?: UpdateFailure.Download).text
    else -> ""
}

/** A step's note, unless the hero says the same sentence already. */
internal fun visibleStepNote(row: StepRow, heroSentence: String): String = if (row.note.isNotBlank() && row.note == heroSentence) "" else row.note
