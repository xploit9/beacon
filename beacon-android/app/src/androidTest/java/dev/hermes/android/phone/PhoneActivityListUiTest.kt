package dev.hermes.android.phone

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import dev.hermes.android.ui.PhoneAccessBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Settings > Phone access > Activity as an organized list: day groups, search, filters, pages and its two empty states. */
class PhoneActivityListUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val log = PhoneActivityLog.of(context)
    private val logBefore = log.recent().reversed()
    private val zone = ZoneId.systemDefault()
    // Fixed days, so a test run just after midnight still groups the same: entries sit a few minutes after each day's midnight.
    private val midnight = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun day(daysAgo: Int, minute: Int) = midnight - daysAgo * 86_400_000L + minute * 60_000L + 60_000L

    @Before fun setUp() { log.clear() }

    @After fun tearDown() {
        log.clear()
        logBefore.forEach(log::add)
    }

    private fun entry(at: Long, origin: PhoneOrigin, tool: String, line: PhoneLine, outcome: String) = log.add(PhoneActivityEntry(at, origin, tool, line, outcome))

    /**
     * Five made-up entries over three days: today (3), yesterday (1) and five days ago (1). Few enough that the whole list fits one
     * screen, so every row is on screen at once and a count means the whole list.
     */
    private fun week() {
        entry(day(5, 10), PhoneOrigin.Hermes, "phone_contacts_search", PhoneLine("Search contacts for '{0}'", listOf("Sam")), "off")
        entry(day(1, 10), PhoneOrigin.Overlay, "phone_alarm_set", PhoneLine("Set an alarm for {0}", listOf("7:30 AM")), "expired")
        entry(day(0, 1), PhoneOrigin.Hermes, "phone_calendar_create", PhoneLine("Add '{0}' {1}", listOf("Dentist", "Tue 4 Nov 3:00 PM")), "done")
        entry(day(0, 2), PhoneOrigin.LiveCall, "phone_reminder_create", PhoneLine("Remind you: '{0}' {1}", listOf("Pills", "8:00 PM")), "done")
        entry(day(0, 3), PhoneOrigin.Hermes, "phone_call", PhoneLine("Call {0}", listOf("Mona Test")), "denied")
    }

    private fun show() = compose.setContent {
        MaterialTheme(colorScheme = darkColorScheme()) { Column(Modifier.fillMaxSize()) { PhoneAccessBody(Modifier.weight(1f), notificationsOn = { true }) } }
    }

    // The page is a lazy list: the Activity controls are its third item, and what follows them is composed only while it is near the screen.
    // From the top of the Activity block a short list is wholly on screen, so a count means the whole list.
    private fun fromTop() = compose.onNodeWithTag("phone-access").performScrollToIndex(2)
    private fun scrollTo(tag: String) = compose.onNodeWithTag("phone-access").performScrollToNode(hasTestTag(tag))
    private fun rows() = compose.onAllNodesWithTag("phone-access-entry")
    private fun pick(menu: String, option: String) {
        fromTop()
        compose.onNodeWithTag(menu).performClick()
        compose.onNodeWithTag("$menu:$option").performClick()
        compose.waitForIdle()
    }

    @Test fun anEmptyLogSaysNothingYetAndOffersNoSearchOrFilters() {
        show()
        compose.onNodeWithText("Nothing yet").assertExists()
        compose.onAllNodesWithTag("phone-access-search").assertCountEquals(0)
        compose.onAllNodesWithTag("phone-access-filter-type").assertCountEquals(0)
        rows().assertCountEquals(0)
    }

    @Test fun rowsAreGroupedByDayNewestFirstEachWithItsWhoWhenAndOutcome() {
        week()
        show()
        fromTop()
        compose.onAllNodesWithTag("phone-access-day").assertCountEquals(3)
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Yesterday").assertExists()
        rows().assertCountEquals(5)
        // Newest first: the call at the day's last minute, then the reminder, the dentist, yesterday's alarm and the oldest search.
        rows()[0].assertTextContains(PhoneLine("Call {0}", listOf("Mona Test")).shown(), substring = true)
        rows()[0].assertTextContains("Hermes", substring = true)
        rows()[0].assertTextContains("Said no", substring = true)
        rows()[1].assertTextContains("Voice call", substring = true)
        rows()[2].assertTextContains(PhoneLine("Add '{0}' {1}", listOf("Dentist", "Tue 4 Nov 3:00 PM")).shown(), substring = true)
        rows()[2].assertTextContains("Done", substring = true)
        rows()[3].assertTextContains("Assistant", substring = true)
        rows()[3].assertTextContains("Expired", substring = true)
        rows()[4].assertTextContains("Turned off", substring = true)
    }

    @Test fun searchKeepsTheRowsWhoseActionHasEveryWordTypedAndClearingShowsAllAgain() {
        week()
        show()
        fromTop()
        compose.onNodeWithTag("phone-access-search").performTextInput("dentist")
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(1)
        rows()[0].assertTextContains("Dentist", substring = true)
        fromTop()
        compose.onNodeWithTag("phone-access-search").performTextInput(" pills")
        compose.waitForIdle()
        fromTop()
        compose.onNodeWithText("No matches").assertExists()
        rows().assertCountEquals(0)
        compose.onNodeWithTag("phone-access-clear-filters").performClick()
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(5)
    }

    @Test fun theTypeResultAndAskedByFiltersNarrowTheListAndAllUndoesThem() {
        week()
        show()
        pick("phone-access-filter-type", "calls")
        fromTop()
        rows().assertCountEquals(1)
        rows()[0].assertTextContains("Mona Test", substring = true)
        compose.onNodeWithTag("phone-access-filter-type").assertTextContains("Phone calls", substring = true)
        pick("phone-access-filter-type", "all")
        pick("phone-access-filter-result", "done")
        fromTop()
        // Done: the dentist and the pills.
        rows().assertCountEquals(2)
        pick("phone-access-filter-origin", "hermes")
        fromTop()
        rows().assertCountEquals(1)
        rows()[0].assertTextContains("Dentist", substring = true)
        pick("phone-access-filter-result", "didntrun")
        fromTop()
        // Did not run and asked by Hermes: the search that was switched off.
        rows().assertCountEquals(1)
        rows()[0].assertTextContains("Sam", substring = true)
        compose.onNodeWithTag("phone-access-filter-result").assertTextContains("Didn't run", substring = true)
        pick("phone-access-filter-origin", "all")
        fromTop()
        // Did not run, whoever asked: the expired alarm too.
        rows().assertCountEquals(2)
        pick("phone-access-filter-origin", "overlay")
        fromTop()
        rows().assertCountEquals(1)
        rows()[0].assertTextContains("Expired", substring = true)
        pick("phone-access-filter-result", "all")
        pick("phone-access-filter-origin", "all")
        fromTop()
        rows().assertCountEquals(5)
    }

    @Test fun aFilterThatMatchesNothingShowsNoMatchesAndResetFiltersBringsTheListBack() {
        week()
        show()
        pick("phone-access-filter-type", "alarms")
        pick("phone-access-filter-result", "done")
        fromTop()
        compose.onNodeWithText("No matches").assertExists()
        rows().assertCountEquals(0)
        compose.onNodeWithTag("phone-access-clear-filters").performClick()
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(5)
        compose.onNodeWithTag("phone-access-filter-type").assertTextContains("Type", substring = true)
    }

    @Test fun theListShowsTwentyRowsAndShowMoreAddsTwentyUntilEverythingIsListed() {
        for (i in 1..45) entry(day(0, i), PhoneOrigin.Hermes, "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("$i min")), "done")
        show()
        fromTop()
        rows().assertCountEquals(20)
        // The newest twenty: 45 down to 26.
        rows()[0].assertTextContains(PhoneLine("Start a timer for {0}", listOf("45 min")).shown(), substring = true)
        scrollTo("phone-access-more")
        compose.onNodeWithTag("phone-access-more").performClick()
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(40)
        scrollTo("phone-access-more")
        compose.onNodeWithTag("phone-access-more").performClick()
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(45)
        compose.onAllNodesWithTag("phone-access-more").assertCountEquals(0)
        assertEquals(45, log.recent().size)
    }

    @Test fun aNewSearchStartsAgainAtTheFirstPage() {
        for (i in 1..45) entry(day(0, i), PhoneOrigin.Hermes, "phone_timer_set", PhoneLine("Start a timer for {0}", listOf("$i min")), "done")
        show()
        scrollTo("phone-access-more")
        compose.onNodeWithTag("phone-access-more").performClick()
        compose.waitForIdle()
        fromTop()
        compose.onNodeWithTag("phone-access-search").performTextInput("timer")
        compose.waitForIdle()
        fromTop()
        rows().assertCountEquals(20)
    }

    @Test fun clearingTheActivityEmptiesTheListAndTakesTheSearchAndFiltersAway() {
        week()
        show()
        fromTop()
        compose.onNodeWithTag("phone-access-search").performTextInput("dentist")
        compose.waitForIdle()
        fromTop()
        compose.onNodeWithTag("phone-access-clear").performClick()
        compose.onNodeWithTag("phone-access-clear-confirm").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Nothing yet").assertExists()
        compose.onAllNodesWithTag("phone-access-search").assertCountEquals(0)
        compose.onAllNodesWithTag("phone-access-filter-type").assertCountEquals(0)
        rows().assertCountEquals(0)
        assertEquals(0, log.recent().size)
    }
}
