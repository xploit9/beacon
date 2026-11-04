package dev.hermes.android.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import dev.hermes.android.phone.ACTIVITY_VISIBLE_ROWS
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.ContactPage
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.phone.PhoneAccess
import dev.hermes.android.phone.PhoneActivityDay
import dev.hermes.android.phone.PhoneActivityEntry
import dev.hermes.android.phone.PhoneActivityFilter
import dev.hermes.android.phone.PhoneActivityLog
import dev.hermes.android.phone.PhoneCapability
import dev.hermes.android.phone.PhoneDayName
import dev.hermes.android.phone.PhoneLine
import dev.hermes.android.phone.PhoneOrigin
import dev.hermes.android.phone.PhoneResult
import dev.hermes.android.phone.activityPage
import dev.hermes.android.phone.filterActivity
import dev.hermes.android.phone.groupActivityByDay
import dev.hermes.android.phone.phoneDayName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

/** The words of the activity list that reach uiText through a variable; the translation test checks each one. */
internal val PhoneAccessStrings = listOf(
    "Done", "Said no", "Expired", "Turned off", "Needs permission", "Busy", "Not found", "Not available", "Invalid", "Cancelled",
    "Unlock your phone first.", "Voice call", "Assistant", "Hermes", "Didn't run",
)

private fun outcomeText(outcome: String) = when (outcome) {
    "done" -> "Done"
    "denied" -> "Said no"
    "expired" -> "Expired"
    "off" -> "Turned off"
    "permission" -> "Needs permission"
    "busy" -> "Busy"
    "not_found" -> "Not found"
    "unavailable" -> "Not available"
    "locked" -> "Unlock your phone first."
    "cancelled" -> "Cancelled"
    else -> "Invalid"
}

private fun resultText(result: PhoneResult) = when (result) {
    PhoneResult.Done -> "Done"
    PhoneResult.SaidNo -> "Said no"
    PhoneResult.DidntRun -> "Didn't run"
}

private fun originText(origin: PhoneOrigin) = when (origin) {
    PhoneOrigin.LiveCall -> "Voice call"
    PhoneOrigin.Overlay -> "Assistant"
    PhoneOrigin.Hermes -> "Hermes"
}

/** [line] in the phone's language: its fixed words through uiText, the owner's own words as written and isolated, so a name in the other script never flips the line. */
@Composable internal fun phoneLineText(line: PhoneLine): String = PhoneLine(uiText(line.template), line.args).shown()

/** Android's notification settings for Beacon; its app page when this phone has none. */
private fun openAppNotifications(context: Context) {
    val opened = runCatching { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }.isSuccess
    if (!opened) openAppInfo(context)
}

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }

private fun capabilityIcon(capability: PhoneCapability) = when (capability) {
    PhoneCapability.Calendar -> Icons.Rounded.CalendarMonth
    PhoneCapability.Alarms -> Icons.Rounded.Alarm
    PhoneCapability.Contacts -> Icons.Rounded.ContactPage
    PhoneCapability.Calls -> Icons.Rounded.Call
    PhoneCapability.Messages -> Icons.Rounded.Sms
}

/** Settings > Phone access, as its own page. */
@Composable internal fun PhoneAccessSettings(vm: BeaconViewModel) {
    Column(Modifier.fillMaxSize()) {
        Header(uiText("Phone access"), back = { vm.backFromSettings() }, menu = LocalOpenNavigationMenu.current)
        PhoneAccessBody(Modifier.weight(1f))
    }
}

/**
 * The switches, the Hermes choices and the activity list. Everything starts off; a switch asks for its Android permissions the moment
 * it is turned on, and a permission taken away in Android shows the switch off with a one-tap way back.
 */
@Composable internal fun PhoneAccessBody(modifier: Modifier = Modifier, notificationsOn: (() -> Boolean)? = null) {
    val context = LocalContext.current
    // Bumped on every change and whenever the page returns from Android's own screens, so each row re-reads the stored choice.
    var version by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // A permission taken away in Android turns its switch off for good; allowing it there later does not switch it back on.
                PhoneAccess.reconcile(context)
                version++
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(version) { phoneAccessChanged.update { it + 1 } }
    var asking by rememberSaveable { mutableStateOf("") }
    var blocked by remember { mutableStateOf(setOf<String>()) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val capability = PhoneCapability.entries.firstOrNull { it.key == asking }
        asking = ""
        if (capability != null) {
            if (result.values.all { it }) {
                PhoneAccess.setSwitchedOn(context, capability, true)
                blocked = blocked - capability.key
            } else {
                // Android stops asking after a refusal it remembers; from then on only its own settings can allow it.
                val activity = context.activity()
                val silent = activity != null && result.filterValues { !it }.keys.none { activity.shouldShowRequestPermissionRationale(it) }
                if (silent) blocked = blocked + capability.key
            }
        }
        version++
    }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    val log = remember { PhoneActivityLog.of(context) }
    val entries = remember(version) { log.recent() }
    val hermes = remember(version) { PhoneAccess.hermesAllowed(context) }
    val approvals = remember(version) { PhoneAccess.approvalChoice(context) }
    // The Activity list's search, its three choices and how many pages it shows; a change of any of them starts again at the first page.
    var query by rememberSaveable { mutableStateOf("") }
    var typeKey by rememberSaveable { mutableStateOf("") }
    var resultKey by rememberSaveable { mutableStateOf("") }
    var originKey by rememberSaveable { mutableStateOf("") }
    var pages by rememberSaveable { mutableIntStateOf(1) }
    val filter = PhoneActivityFilter(query, PhoneCapability.entries.firstOrNull { it.key == typeKey }, PhoneResult.entries.firstOrNull { it.name == resultKey },
        PhoneOrigin.entries.firstOrNull { it.name == originKey })
    // The search reads each action as the owner sees it, in the phone's language.
    val shown = entries.map { it.line.template }.distinct().associateWith { uiText(it) }
    val matching = remember(entries, filter, shown) { filterActivity(entries, filter) { PhoneLine(shown.getValue(it.template), it.args).text() } }
    val visible = activityPage(matching, pages)
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(visible, zone) { groupActivityByDay(visible, zone) }
    val today = remember(entries) { LocalDate.now(zone) }
    // A request from Hermes reaches the owner as a notification when Beacon is closed; with notifications off it waits for the app to open.
    val notificationsOff = remember(version) { hermes && !(notificationsOn?.invoke() ?: NotificationManagerCompat.from(context).areNotificationsEnabled()) }

    SettingsList(modifier, testTag = "phone-access") {
        item {
            SettingsSection(uiText("Beacon can use"), footer = uiText(approvalChoiceMeaning(approvals)),
                testTag = "phone-access-capabilities") {
                PhoneCapability.entries.forEachIndexed { index, capability ->
                    if (index > 0) SettingsDivider()
                    val stored = remember(version) { PhoneAccess.switchedOn(context, capability) }
                    val granted = remember(version) { PhoneAccess.permissionsGranted(context, capability) }
                    val on = stored && granted
                    val needsFix = stored && !granted
                    SettingsSwitchRow(
                        label = uiText(capabilityLabel(capability)), checked = on,
                        onCheckedChange = { wanted ->
                            if (!wanted) {
                                PhoneAccess.setSwitchedOn(context, capability, false)
                                version++
                            } else if (capability.permissions.isEmpty() || PhoneAccess.permissionsGranted(context, capability)) {
                                PhoneAccess.setSwitchedOn(context, capability, true)
                                version++
                            } else if (capability.key in blocked) {
                                openAppInfo(context)
                            } else {
                                asking = capability.key
                                request.launch(capability.permissions.toTypedArray())
                            }
                        },
                        icon = capabilityIcon(capability), summary = uiText(capabilitySummary(capability)),
                        status = if (needsFix) SettingsStatus.Attention else null, statusLabel = if (needsFix) uiText("Needs permission") else "",
                        testTag = "phone-access-${capability.key}",
                    )
                    if (capability.key in blocked && !on) {
                        SettingsDivider()
                        SettingsActionRow(uiText("Open settings"), onClick = { openAppInfo(context) }, testTag = "phone-access-${capability.key}-settings")
                    }
                }
            }
        }
        item {
            SettingsSection(uiText("Hermes"), testTag = "phone-access-hermes") {
                SettingsSwitchRow(
                    label = uiText("Let Hermes use phone access"), checked = hermes,
                    onCheckedChange = { PhoneAccess.setHermesAllowed(context, it); version++ },
                    icon = Icons.Rounded.SmartToy, summary = uiText("Only what you turned on above"), testTag = "phone-access-hermes-switch",
                )
                // The same choice as Settings › Assistant: one answer to whether Hermes asks, in chats, calls and from the side button.
                SettingsDivider()
                ApprovalChoiceRow { version++ }
                if (notificationsOff) {
                    SettingsDivider()
                    SettingsValueRow(label = uiText("Notifications are off. Hermes requests show only when Beacon is open."), icon = Icons.Rounded.NotificationsOff,
                        testTag = "phone-access-notifications-off")
                    SettingsDivider()
                    SettingsActionRow(uiText("Open settings"), onClick = { openAppNotifications(context) }, testTag = "phone-access-notifications-settings")
                }
            }
        }
        item {
            SettingsSection(uiText("Activity"), testTag = "phone-access-activity", trailing = {
                if (entries.isNotEmpty()) TextButton(onClick = { confirmClear = true }, modifier = Modifier.heightIn(min = 48.dp).testTag("phone-access-clear")) { Text(uiText("Clear")) }
            }) {
                if (entries.isEmpty()) SettingsEmpty(uiText("Nothing yet"), Icons.Rounded.History)
                else Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsSearchField(query, { query = it; pages = 1 }, uiText("Search activity"), testTag = "phone-access-search")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterMenuChip(uiText("Type"), filter.capability, PhoneCapability.entries, { uiText(capabilityLabel(it)) }, { it.key }, "phone-access-filter-type") {
                            typeKey = it?.key.orEmpty(); pages = 1
                        }
                        FilterMenuChip(uiText("Result"), filter.result, PhoneResult.entries, { uiText(resultText(it)) }, { it.name.lowercase() }, "phone-access-filter-result") {
                            resultKey = it?.name.orEmpty(); pages = 1
                        }
                        FilterMenuChip(uiText("Asked by"), filter.origin, PhoneOrigin.entries, { uiText(originText(it)) }, { it.name.lowercase() }, "phone-access-filter-origin") {
                            originKey = it?.name.orEmpty(); pages = 1
                        }
                    }
                }
            }
        }
        if (entries.isNotEmpty() && matching.isEmpty()) item {
            SettingsSection(testTag = "phone-access-no-matches") {
                SettingsEmpty(uiText("No matches"), Icons.Rounded.SearchOff, action = SettingsAction(uiText("Reset filters"), onClick = {
                    query = ""; typeKey = ""; resultKey = ""; originKey = ""; pages = 1
                }, testTag = "phone-access-clear-filters"))
            }
        }
        // One titled card for each day, newest first; a day's rows are compact: what was done, then who asked, at what time and how it
        // ended. The list is about ten rows tall at most and scrolls inside itself past that, so the page never grows with it (owner, 4 Nov).
        if (days.isNotEmpty()) item {
            Column(Modifier.fillMaxWidth().heightIn(max = SettingsKit.RowMinHeightTwoLine * ACTIVITY_VISIBLE_ROWS).verticalScroll(rememberScrollState())
                .testTag("phone-access-activity-list")) {
                days.forEach { group ->
                    SettingsSection(dayLabel(group, today), testTag = "phone-access-day") {
                        group.entries.forEachIndexed { index, entry ->
                            if (index > 0) SettingsDivider()
                            ActivityRow(entry)
                        }
                    }
                }
                if (matching.size > visible.size) SettingsSection(testTag = "phone-access-more-section") {
                    SettingsActionRow(uiText("Show more"), onClick = { pages++ }, testTag = "phone-access-more")
                }
            }
        }
    }
    if (confirmClear) SettingsConfirmDialog(
        title = uiText("Clear activity?"), text = "", confirmLabel = uiText("Clear"),
        onConfirm = { log.clear(); confirmClear = false; query = ""; typeKey = ""; resultKey = ""; originKey = ""; pages = 1; version++ },
        onDismiss = { confirmClear = false }, confirmTestTag = "phone-access-clear-confirm",
    )
}

/** A filter that opens a menu: [label] while it lets everything through, the chosen option once one is picked. [onPick] gets null for All. */
@Composable private fun <T : Any> FilterMenuChip(
    label: String, value: T?, options: List<T>, text: @Composable (T) -> String, optionTag: (T) -> String, tag: String, onPick: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = value != null, onClick = { open = true }, label = { Text(if (value != null) text(value) else label, maxLines = 1) },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null, Modifier.size(FilterChipDefaults.IconSize)) }, modifier = Modifier.testTag(tag),
        )
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text(uiText("All")) }, onClick = { open = false; onPick(null) }, modifier = Modifier.testTag("$tag:all"))
            options.forEach { option ->
                DropdownMenuItem(text = { Text(text(option)) }, onClick = { open = false; onPick(option) }, modifier = Modifier.testTag("$tag:${optionTag(option)}"))
            }
        }
    }
}

/** A day's heading: Today, Yesterday, else the date in the phone's own order (with the weekday this year, the year before). */
@Composable private fun dayLabel(group: PhoneActivityDay, today: LocalDate): String = when (phoneDayName(group.day, today)) {
    PhoneDayName.Today -> uiText("Today")
    PhoneDayName.Yesterday -> uiText("Yesterday")
    PhoneDayName.Date -> {
        val locale = Locale.getDefault()
        val sameYear = group.day.year == today.year
        SimpleDateFormat(nativeDayPattern(locale, withYear = !sameYear, withWeekday = sameYear), locale).format(Date(group.entries.first().at))
    }
}

/** Bumped whenever this page changes a choice, so the Settings list beside it (wide screens) shows the new state at once. */
internal val phoneAccessChanged = MutableStateFlow(0)

/** The capabilities that are on and usable now, by the names this page gives them (English; the Settings list translates them). */
internal fun phoneAccessOn(context: Context): List<String> = PhoneCapability.entries.filter { PhoneAccess.active(context, it) }.map(::capabilityLabel)

private fun capabilityLabel(capability: PhoneCapability) = when (capability) {
    PhoneCapability.Calendar -> "Calendar & reminders"
    PhoneCapability.Alarms -> "Alarms & timers"
    PhoneCapability.Contacts -> "Contacts"
    PhoneCapability.Calls -> "Phone calls"
    PhoneCapability.Messages -> "Messages"
}

private fun capabilitySummary(capability: PhoneCapability) = when (capability) {
    PhoneCapability.Calendar -> "Read, add and change events"
    PhoneCapability.Alarms -> "Set alarms and timers"
    PhoneCapability.Contacts -> "Find names and numbers"
    PhoneCapability.Calls -> "Call saved contacts after 3 seconds"
    PhoneCapability.Messages -> "Write message drafts"
}

/** The page's fixed words that reach uiText through a variable. */
internal val PhoneAccessLabels = listOf(
    "Calendar & reminders", "Alarms & timers", "Contacts", "Phone calls", "Messages",
    "Read, add and change events", "Set alarms and timers", "Find names and numbers", "Call saved contacts after 3 seconds", "Write message drafts",
)

@Composable private fun ActivityRow(entry: PhoneActivityEntry) {
    // Under its day's heading a row says the time it happened ("3:00 PM"), so rows of one day read in order.
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val time = dev.hermes.android.phone.PhoneTime.clock(entry.at, java.time.ZoneId.systemDefault(), locale)
    SettingsValueRow(
        label = phoneLineText(entry.line), valueMaxLines = 2,
        value = listOf(uiText(originText(entry.origin)), time, uiText(outcomeText(entry.outcome))).joinToString(" · "),
        testTag = "phone-access-entry",
    )
}
