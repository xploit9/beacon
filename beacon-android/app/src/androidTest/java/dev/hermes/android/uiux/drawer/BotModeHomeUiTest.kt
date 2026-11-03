package dev.hermes.android.uiux.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import dev.hermes.android.AppState
import dev.hermes.android.BeaconApplication
import dev.hermes.android.BeaconTheme
import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.json
import dev.hermes.android.data.BotModeBot
import dev.hermes.android.data.BotModePreview
import dev.hermes.android.data.BotModeSpace
import dev.hermes.android.data.NativeBotModeController
import dev.hermes.android.data.NativeBotModeState
import dev.hermes.android.preferences
import dev.hermes.android.ui.NativeBotModeScreen
import dev.hermes.android.ui.NativeLocalization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.OffsetDateTime

/**
 * The Bot Mode home screen on its own: New bot and New space on top, the bots with a real status, suggested roles, the next scheduled
 * runs and the shared spaces. The roster and the schedules are set directly, so nothing here talks to Hermes. A large screen draws
 * wider than the device window, so those checks read measured bounds, not what is on screen.
 */
class BotModeHomeUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val twoBots = listOf(BotModeBot("default", "Default", "Your main assistant"), BotModeBot("coder", "Coder", "Reviews code"))
    private val oneSpace = listOf(BotModeSpace("room-1", "Review room", listOf("default", "coder"), updatedAt = 1_700_000_000L, last = BotModePreview("coder", "Looks good to me")))

    private fun at(minutes: Long): String = OffsetDateTime.now().plusMinutes(minutes).plusSeconds(30).toString()
    private fun job(id: String, name: String, next: String?, vararg extra: Pair<String, Any?>) =
        json("id" to id, "name" to name, "enabled" to true, "state" to "scheduled", "next_run_at" to next, "prompt" to "Do it", *extra)
    private fun crons(vararg jobs: JSONObject) = json("jobs" to JSONArray(jobs.toList()))

    /** Three real upcoming runs (one for the coder), and every kind the screen must leave out. */
    private val schedules = crons(
        job("plan", "Weekly plan", at(60L * 24 * 9)),
        job("news", "Morning news", at(30)),
        job("review", "Code review", at(60L * 24 * 2), "profile" to "coder"),
        job("later", "Much later", at(60L * 24 * 20)),
        job("paused", "Paused one", at(10), "state" to "paused"),
        job("off", "Switched off", at(10), "enabled" to false),
        job("done", "Finished one", at(10), "state" to "completed"),
        job("callback", "Beacon callback " + "0123456789abcdef".repeat(4), at(10), "script" to "beacon-callback-" + "0123456789abcdef".repeat(4) + ".py"),
        job("never", "No next time", null),
        // The plugin's own jobs, the soonest of all so that listing them would push a real run out: found by their script and marker, not by name.
        job("checkup", "Daily check-in", at(5), "script" to "/home/maya/.hermes/scripts/beacon-checkups.py"),
        job("watch", "Plan the week", at(6), "prompt" to "<!-- BEACON_PROACTIVE_JOB:watch_email --> Look through the connected sources."),
    )
    private val shownRuns = listOf("news", "review", "plan")
    private val leftOut = listOf("later", "paused", "off", "done", "callback", "never", "checkup", "watch")

    @Suppress("UNCHECKED_CAST")
    private fun ui(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_ui").apply { isAccessible = true }.get(vm) as MutableStateFlow<AppState>

    @Suppress("UNCHECKED_CAST")
    private fun data(vm: BeaconViewModel) = BeaconViewModel::class.java.getDeclaredField("_data").apply { isAccessible = true }.get(vm) as MutableStateFlow<Map<String, JSONObject>>

    @Suppress("UNCHECKED_CAST")
    private fun roster(vm: BeaconViewModel) = NativeBotModeController::class.java.getDeclaredField("mutable").apply { isAccessible = true }.get(vm.botMode) as MutableStateFlow<NativeBotModeState>

    private fun withHome(
        state: NativeBotModeState = NativeBotModeState(bots = twoBots, spaces = oneSpace), crons: JSONObject? = schedules,
        language: String = "en", rtl: Boolean = false, fontScale: Float = 1f, width: Int = 360, height: Int = 720, block: (BeaconViewModel) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<BeaconApplication>()
        val savedPreferences = runBlocking { app.preferences.data.first() }
        val store = ViewModelStore()
        lateinit var vm: BeaconViewModel
        try {
            runBlocking {
                app.preferences.edit {
                    it[stringPreferencesKey("origin")] = ""
                    it[booleanPreferencesKey("setup_complete")] = true
                    it[booleanPreferencesKey("setup_active")] = false
                }
            }
            compose.runOnUiThread {
                vm = ViewModelProvider(store, viewModelFactory { initializer { BeaconViewModel(app, SavedStateHandle()) } })[BeaconViewModel::class.java]
                ui(vm).value = AppState(origin = "", tab = "More", morePage = "Bots", connected = false, initialChatReady = true, restoringSession = false, setupComplete = true, botModeEnabled = true)
            }
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    NativeLocalization(language) { BeaconTheme { Box(Modifier.requiredSize(width.dp, height.dp).testTag("home-frame")) { NativeBotModeScreen(vm) } } }
                }
            }
            // The screen reads the roster once when it opens (and, with no Hermes behind it, clears it); the roster is set after that.
            compose.waitForIdle()
            compose.runOnIdle {
                roster(vm).value = state
                if (crons != null) data(vm).value = data(vm).value + ("crons" to crons)
            }
            compose.waitForIdle()
            block(vm)
        } finally {
            compose.runOnUiThread { store.clear() }
            runBlocking { app.preferences.updateData { savedPreferences } }
        }
    }

    /**
     * Bounds relative to the simulated screen, not to the device window: a screen wider or taller than the window is centred in it, so its
     * root coordinates can be negative and a mirrored position would not add up.
     */
    private fun SemanticsNodeInteraction.inFrame(): DpRect {
        val origin = compose.onNodeWithTag("home-frame").getUnclippedBoundsInRoot()
        val b = getUnclippedBoundsInRoot()
        return DpRect(b.left - origin.left, b.top - origin.top, b.right - origin.left, b.bottom - origin.top)
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).inFrame()

    private fun assertSingleUnclippedLine(text: String) {
        val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
        val count = nodes.fetchSemanticsNodes().size
        assertTrue("Expected a label $text", count > 0)
        for (index in 0 until count) {
            val layouts = mutableListOf<TextLayoutResult>()
            nodes[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals("Expected one layout for $text #$index", 1, layouts.size)
            assertEquals("$text #$index wraps", 1, layouts.single().lineCount)
            assertTrue("$text #$index is ellipsized", !layouts.single().isLineEllipsized(0))
        }
    }

    @Test fun newBotAndNewSpaceLeadTheHomeScreenAboveTheBots() = withHome { _ ->
        compose.onNodeWithText("Bot Mode").assertIsDisplayed()
        compose.onNodeWithTag("bot-mode-home-refresh").assertIsDisplayed().assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        val newBot = compose.onNodeWithTag("bot-mode-new-bot").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertTextContains("New bot").inFrame()
        val newSpace = compose.onNodeWithTag("bot-mode-new-chat").assertIsDisplayed().assertIsEnabled().assertHeightIsAtLeast(48.dp).assertTextContains("New space").inFrame()
        // Side by side at normal text, New bot first, one size; both above the first bot and under the header.
        assertTrue("New bot is left of New space", newBot.right <= newSpace.left)
        assertEquals(newBot.top.value, newSpace.top.value, 0.5f)
        assertEquals(newBot.width.value, newSpace.width.value, 1f)
        assertTrue("The buttons sit above the bots", newSpace.bottom <= bounds("teammate-row:default").top)
        assertTrue("The buttons sit under the title", bounds("bot-mode-home-refresh").bottom <= newBot.top + 8.dp)
    }

    @Test fun theHeaderAndTheSectionsCarryNoSecondNewBotOrNewSpace() = withHome(NativeBotModeState(bots = twoBots)) { _ ->
        // One of each, even with no shared space yet, whose empty state used to offer a second New space.
        compose.onAllNodesWithTag("bot-mode-new-bot").assertCountEquals(1)
        compose.onAllNodesWithTag("bot-mode-new-chat").assertCountEquals(1)
        compose.onAllNodesWithText("New bot").assertCountEquals(1)
        compose.onAllNodesWithText("New space").assertCountEquals(1)
        compose.onNodeWithText("Bots").assertIsDisplayed()
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasText("No shared spaces yet"))
        compose.onNodeWithText("Shared spaces").assertIsDisplayed()
        compose.onNodeWithText("No shared spaces yet").assertIsDisplayed()
        // The buttons are the list's first item, so they are scrolled out here: whatever else says New bot or New space is a second one.
        compose.onAllNodesWithText("New bot").filter(!hasTestTag("bot-mode-new-bot")).assertCountEquals(0)
        compose.onAllNodesWithText("New space").filter(!hasTestTag("bot-mode-new-chat")).assertCountEquals(0)
        // And back at the top it is still the one pair.
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasTestTag("bot-mode-new-chat"))
        compose.onAllNodesWithText("New bot").assertCountEquals(1)
        compose.onAllNodesWithText("New space").assertCountEquals(1)
    }

    @Test fun newBotOpensTheNewBotFormAndNewSpaceOpensTheSpaceSheet() = withHome { _ ->
        compose.onNodeWithTag("bot-mode-new-bot").performClick()
        compose.onNodeWithTag("new-profile-name").assertIsDisplayed()
        compose.onNodeWithTag("new-profile-create").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onAllNodesWithTag("new-profile-name").assertCountEquals(0)
        compose.onNodeWithTag("bot-mode-new-chat").performClick()
        compose.onNodeWithTag("new-chat-sheet").assertIsDisplayed()
        compose.onNodeWithTag("new-chat-bot:coder").assertIsDisplayed()
    }

    @Test fun botRowsShowTheirRoleARealStatusAndAQuickGroupAction() = withHome { vm ->
        compose.onNodeWithTag("teammate-row:coder").assertTextContains("Coder").assertTextContains("Reviews code").assertTextContains("Spaces: 1")
        compose.onNodeWithTag("teammate-row:default").assertTextContains("Your main assistant").assertTextContains("Spaces: 1")
        // The recent-spaces list is cut: a count would be too low, so none is shown.
        compose.runOnIdle { roster(vm).value = roster(vm).value.copy(moreSpaces = true) }
        compose.onAllNodesWithText("Spaces: 1", substring = true).assertCountEquals(0)
        compose.runOnIdle { roster(vm).value = roster(vm).value.copy(moreSpaces = false) }
        compose.onNodeWithTag("teammate-space:coder").assertIsDisplayed().assertIsEnabled().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            .assertContentDescriptionContains("Start a group with Coder")
        compose.onNodeWithTag("teammate-space:coder").performClick()
        compose.onNodeWithTag("new-chat-sheet").assertIsDisplayed()
        assertEquals(setOf("coder"), roster(vm).value.selectedBots)
    }

    @Test fun aSuggestedRoleOpensNewBotFilledIn() = withHome { _ ->
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        compose.onNodeWithText("Suggested roles").assertIsDisplayed()
        // Each role is a chip, a full-size target; its role line is on the form it opens.
        compose.onNodeWithTag("bot-mode-suggested-role:planner").assertTextContains("Planner").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("bot-mode-suggested-role:researcher").assertTextContains("Researcher").performClick()
        // The name is the profile id; the role is the line the bot's row will show. Create is open: the name is valid and free.
        compose.onNodeWithTag("new-profile-name").assertTextContains("researcher")
        compose.onNodeWithTag("new-profile-role").assertTextContains("Finds sources and sums them up.")
        compose.onNodeWithTag("new-profile-create").assertIsEnabled()
    }

    @Test fun suggestedRolesAreCompactChipsSoUpNextAndTheSharedSpacesStayInView() = withHome(height = 800) { _ ->
        // Four rows of a bot's height used to take a third of the screen; chips share a row or two.
        val roles = bounds("bot-mode-suggested-roles")
        assertTrue("The suggested roles are compact (${roles.height})", roles.height.value <= 160f)
        listOf("researcher", "planner", "writer", "inbox-helper").forEach { compose.onNodeWithTag("bot-mode-suggested-role:$it").assertIsDisplayed() }
        // On an ordinary phone the next runs and the first shared space are on the first screen.
        compose.onNodeWithTag("bot-mode-up-next").assertIsDisplayed()
        compose.onNodeWithText("Shared spaces").assertIsDisplayed()
        compose.onNodeWithTag("space-row:room-1").assertIsDisplayed()
    }

    @Test fun suggestedRolesGoOnceTheTeamHasFourBots() {
        val four = listOf("default", "coder", "ops", "docs").map { BotModeBot(it, it.replaceFirstChar(Char::uppercase), "") }
        withHome(NativeBotModeState(bots = four)) { _ -> compose.onAllNodesWithTag("bot-mode-suggested-roles").assertCountEquals(0) }
    }

    @Test fun aRoleThatIsAlreadyABotIsNotSuggestedAgain() = withHome(NativeBotModeState(bots = twoBots + BotModeBot("researcher", "Researcher", "Finds sources"))) { _ ->
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        compose.onAllNodesWithTag("bot-mode-suggested-role:researcher").assertCountEquals(0)
        compose.onNodeWithTag("bot-mode-suggested-role:planner").assertIsDisplayed()
    }

    @Test fun withoutAnyBotYetTheScreenStillOffersWhereToStart() = withHome(NativeBotModeState(), crons = null) { _ ->
        // Nothing read and nothing loading: the buttons stay, the empty state says so, and the suggested roles give the screen a start.
        compose.onNodeWithTag("bot-mode-new-bot").assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithText("No bots available").assertIsDisplayed()
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasTestTag("bot-mode-suggested-roles"))
        compose.onNodeWithTag("bot-mode-suggested-role:researcher").assertIsDisplayed()
    }

    @Test fun upNextListsOnlyTheRealScheduledRunsSoonestFirstWithTheirBot() = withHome(height = 2000) { _ ->
        compose.onNodeWithText("Up next").assertExists()
        // Three runs, none of the paused, switched off, finished, callback, timeless or too-distant jobs.
        shownRuns.forEach { compose.onAllNodesWithTag("up-next:$it").assertCountEquals(1) }
        leftOut.forEach { compose.onAllNodesWithTag("up-next:$it").assertCountEquals(0) }
        compose.onAllNodesWithText("Paused one").assertCountEquals(0)
        compose.onAllNodesWithText("Finished one").assertCountEquals(0)
        compose.onAllNodesWithText("Much later").assertCountEquals(0)
        compose.onAllNodesWithText("Beacon callback", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Daily check-in").assertCountEquals(0)
        compose.onAllNodesWithText("Plan the week").assertCountEquals(0)
        // Soonest first: the job name, the bot it belongs to (the coder's own, else the profile the list was read for) and the time in the phone's clock.
        compose.onNodeWithTag("up-next:news").assertTextContains("Morning news").assertTextContains("Default").assertTextContains("min", substring = true)
        compose.onNodeWithTag("up-next:review").assertTextContains("Code review").assertTextContains("Coder")
        compose.onNodeWithTag("up-next:plan").assertTextContains("Weekly plan").assertTextContains("Default")
        val tops = shownRuns.map { bounds("up-next:$it").top.value }
        assertEquals(tops.sorted(), tops)
        assertTrue("Up next sits between the team and the shared spaces", bounds("teammate-row:coder").bottom <= bounds("bot-mode-up-next").top && bounds("bot-mode-up-next").bottom <= bounds("space-row:room-1").top)
        shownRuns.forEach { compose.onNodeWithTag("up-next:$it").assertHeightIsAtLeast(48.dp) }
    }

    @Test fun aLongScheduleNameGetsTheWholeWidthAndTheTimeSitsBesideTheBotName() = withHome(
        crons = crons(job("long", "Review the quarterly planning documents with the whole team", at(30))),
    ) { _ ->
        fun part(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag("up-next:long")), useUnmergedTree = true).inFrame()
        val row = bounds("up-next:long")
        val title = part("Review the quarterly")
        val bot = part("Default")
        val time = part("min")
        // The title runs to the row's end padding (12 dp); the time is not taking a share of it.
        assertTrue("The title has the whole width ($title, $row)", title.right >= row.right - 13.dp)
        // The second line: the bot's name, and the time at its end.
        assertTrue("The time is under the title ($time, $title)", time.top >= title.bottom - 1.dp)
        assertTrue("The bot name and the time share a line ($bot, $time)", bot.top < time.bottom && time.top < bot.bottom)
        assertTrue("The time is after the bot name ($bot, $time)", bot.right <= time.left + 1.dp)
        assertTrue("The time is at the row's end ($time, $row)", time.right >= row.right - 13.dp)
    }

    @Test fun aTapOnAnUpNextRowOpensSchedules() = withHome { vm ->
        shownRuns.forEach { compose.onNodeWithTag("up-next:$it").assertHasClickAction() }
        assertEquals("More", ui(vm).value.tab)
        compose.onNodeWithTag("up-next:news").performClick()
        compose.waitUntil(5_000) { ui(vm).value.tab == "Tasks" }
        assertEquals("Tasks", ui(vm).value.tab)
    }

    @Test fun aGroupRowsNamesLineUpWithTheBotRowsAndMoreThanTwoBotsShowAPlusCount() = withHome(
        NativeBotModeState(bots = twoBots + BotModeBot("ops", "Ops", ""), spaces = oneSpace + BotModeSpace("room-2", "All hands", listOf("default", "coder", "ops"), updatedAt = 1_700_000_100L)),
        // Tall enough that the list composes the shared spaces under the bots, the starters and Up next.
        height = 2000,
    ) { _ ->
        fun name(row: String, text: String) = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).inFrame()
        val bot = name("teammate-row:default", "Default")
        val twoBotSpace = name("space-row:room-1", "Review room")
        val threeBotSpace = name("space-row:room-2", "All hands")
        assertEquals("A group row's name starts where a bot row's does", bot.left.value, twoBotSpace.left.value, 1f)
        assertEquals(bot.left.value, threeBotSpace.left.value, 1f)
        // Three bots do not run into the name: the first avatar and "+2" fill the same two places two avatars do.
        compose.onAllNodesWithText("+2", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun upNextIsHiddenWithNothingScheduledOrNoSchedulesToRead() = withHome(crons = null) { vm ->
        val unavailable = json("jobs" to JSONArray(), "cron_unavailable" to true)
        val failed = json("jobs" to JSONArray(listOf(job("news", "Morning news", at(30)))), "_error" to "offline")
        val onlyLeftOut = crons(job("paused", "Paused one", at(10), "state" to "paused"), job("never", "No next time", null))
        // Schedules not loaded yet, then each way of having none to show: no section, no heading, and the rest of the screen as it was.
        fun assertHidden(why: String) {
            compose.onAllNodesWithTag("bot-mode-up-next").assertCountEquals(0)
            compose.onAllNodesWithText("Up next").assertCountEquals(0)
            compose.onAllNodesWithTag("teammate-row:default").assertCountEquals(1)
            assertTrue("The spaces are shown for $why", compose.onAllNodesWithTag("space-row:room-1").fetchSemanticsNodes().isNotEmpty())
        }
        assertHidden("schedules not loaded")
        for ((why, schedule) in listOf("an empty list" to crons(), "schedules unavailable" to unavailable, "a failed read" to failed, "nothing upcoming" to onlyLeftOut)) {
            compose.runOnIdle { data(vm).value = data(vm).value + ("crons" to schedule) }
            assertHidden(why)
        }
        // And it appears as soon as a real run is loaded, and goes again when the list empties.
        compose.runOnIdle { data(vm).value = data(vm).value + ("crons" to schedules) }
        compose.onNodeWithTag("bot-mode-home-list").performScrollToNode(hasTestTag("bot-mode-up-next"))
        compose.onNodeWithTag("up-next:news").assertTextContains("Morning news")
        compose.runOnIdle { data(vm).value = data(vm).value + ("crons" to crons()) }
        assertHidden("the list emptied")
    }

    @Test fun atLargeTextTheButtonsStackFullWidthAndEveryLabelStaysOnOneLine() = withHome(fontScale = 1.3f, height = 2400) { _ ->
        val newBot = compose.onNodeWithTag("bot-mode-new-bot").assertHeightIsAtLeast(48.dp).inFrame()
        val newSpace = compose.onNodeWithTag("bot-mode-new-chat").assertHeightIsAtLeast(48.dp).inFrame()
        assertTrue("New space is under New bot ($newBot, $newSpace)", newBot.bottom <= newSpace.top)
        assertEquals(newBot.left.value, newSpace.left.value, 0.5f)
        assertEquals(newBot.width.value, newSpace.width.value, 0.5f)
        assertTrue("The buttons sit above the bots", newSpace.bottom <= bounds("teammate-row:default").top)
        listOf("New bot", "New space", "Researcher", "Planner", "Writer", "Inbox helper", "Morning news").forEach(::assertSingleUnclippedLine)
        listOf("default", "coder").forEach { compose.onNodeWithTag("teammate-space:$it").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp) }
        shownRuns.forEach { compose.onNodeWithTag("up-next:$it").assertHeightIsAtLeast(48.dp) }
        // Nothing runs under a neighbour: the sections follow each other.
        val rows = listOf("teammate-row:default", "teammate-row:coder", "bot-mode-suggested-roles", "bot-mode-up-next", "space-row:room-1").map { bounds(it) }
        rows.zipWithNext().forEach { (above, below) -> assertTrue("Rows overlap ($above, $below)", above.bottom <= below.top + 0.5.dp) }
    }

    @Test fun arabicLinesUpTheScheduleNameAndTheBotNameAtTheStartAndPutsTheTimeAtTheEnd() = withHome(
        NativeBotModeState(bots = listOf(BotModeBot("default", "\u0627\u0644\u0627\u0641\u062a\u0631\u0627\u0636\u064a", "")), spaces = oneSpace),
        crons = crons(job("news", "\u0627\u0644\u0623\u062e\u0628\u0627\u0631 \u0627\u0644\u0635\u0628\u0627\u062d\u064a\u0629", at(30))),
        language = "ar", rtl = true, height = 2000,
    ) { _ ->
        compose.waitUntil(5_000) { compose.onAllNodesWithText("\u0627\u0644\u062a\u0627\u0644\u064a").fetchSemanticsNodes().isNotEmpty() }
        fun part(text: String) = compose.onNode(hasText(text, substring = true) and hasAnyAncestor(hasTestTag("up-next:news")), useUnmergedTree = true).inFrame()
        val row = bounds("up-next:news")
        val title = part("\u0627\u0644\u0623\u062e\u0628\u0627\u0631")
        val bot = part("\u0627\u0644\u0627\u0641\u062a\u0631\u0627\u0636\u064a")
        // "In" rather than the count: the run is about 30 minutes away and the count moves while the test runs.
        val time = part("بعد")
        // Both names are full-width lines that begin at the right, the start of a right-to-left row; the time is at the left, its end.
        assertEquals("The names start at the same edge ($title, $bot)", title.right.value, bot.right.value, 1f)
        // The bot's avatar sits at the row's start (the right edge here); the names begin just after it, on the right half.
        assertTrue("The names start at the right, after the avatar ($title, $row)", title.right >= row.right - 80.dp && title.right > row.left + row.width / 2)
        assertTrue("The time is at the row's end ($time, $row)", time.left <= row.left + 60.dp && time.right <= bot.left + 1.dp)
    }

    @Test fun arabicRunsRightToLeftWithNewBotAtTheStartAndTheQuickActionAtTheEnd() = withHome(language = "ar", rtl = true, height = 2000) { _ ->
        compose.waitUntil(5_000) { compose.onAllNodesWithText("روبوت جديد").fetchSemanticsNodes().isNotEmpty() }
        val newBot = compose.onNodeWithTag("bot-mode-new-bot").assertTextContains("روبوت جديد").inFrame()
        val newSpace = compose.onNodeWithTag("bot-mode-new-chat").assertTextContains("مساحة جديدة").inFrame()
        // The start is the right: New bot is the right-hand button, and the buttons keep one size and do not overlap.
        assertTrue("New bot is right of New space ($newBot, $newSpace)", newBot.left >= newSpace.right)
        assertEquals(newBot.width.value, newSpace.width.value, 1f)
        // The end is the left: the quick action sits left of the row, whose avatar is at its right.
        val row = bounds("teammate-row:default")
        val quick = bounds("teammate-space:default")
        assertTrue("The quick action is at the left end ($quick, $row)", quick.right <= row.left + 0.5.dp)
        compose.onNodeWithText("التالي").assertExists()
        compose.onNodeWithTag("up-next:news").assertTextContains("Morning news")
    }

    @Test fun aWideScreenSetsTheTeamBesideWhatItDoesAndKeepsTheButtonsShort() = withHome(width = 1000, height = 720) { _ ->
        val team = bounds("bot-mode-home-team")
        val activity = bounds("bot-mode-home-activity")
        val newBot = bounds("bot-mode-new-bot")
        val newSpace = bounds("bot-mode-new-chat")
        assertTrue("The team is the left list ($team, $activity)", team.right <= activity.left + 0.5.dp)
        assertEquals(team.width.value, activity.width.value, 1f)
        assertTrue("Both lists start under the buttons", newSpace.bottom <= team.top + 0.5.dp && newSpace.bottom <= activity.top + 0.5.dp)
        // Bots and suggested roles on the left; the next runs and the shared spaces on the right.
        assertTrue("A bot is in the left list", bounds("teammate-row:default").right <= activity.left + 0.5.dp)
        assertTrue("Up next is in the right list", bounds("up-next:news").left >= activity.left - 0.5.dp)
        assertTrue("A space is in the right list", bounds("space-row:room-1").left >= activity.left - 0.5.dp)
        assertTrue("The buttons stay together and short, not stretched across the screen (${newSpace.right - newBot.left})", (newSpace.right - newBot.left).value <= 520f)
        compose.onAllNodesWithTag("bot-mode-home-list").assertCountEquals(0)
    }

    @Test fun aVeryWideScreenKeepsTheRowsAtAReadableWidth() = withHome(width = 1600, height = 720) { _ ->
        val team = bounds("bot-mode-home-team")
        val activity = bounds("bot-mode-home-activity")
        assertTrue("A list is not stretched across the screen ($team, $activity)", team.width.value <= 561f && activity.width.value <= 561f)
        // The two lists are centred as one block.
        assertEquals(team.left.value, 1600f - activity.right.value, 1f)
        assertTrue("The team stays left of the rest", team.right <= activity.left + 0.5.dp)
    }

    @Test fun aNarrowScreenKeepsOneColumn() = withHome(width = 600, height = 2400) { _ ->
        val team = bounds("teammate-row:default")
        val space = bounds("space-row:room-1")
        assertEquals(team.left.value, space.left.value, 0.5f)
        assertTrue("The spaces follow the team ($team, $space)", team.bottom <= space.top)
        compose.onAllNodesWithTag("bot-mode-home-team").assertCountEquals(0)
    }

    @Test fun largeTextOnABigScreenKeepsOneCappedColumn() = withHome(width = 1000, height = 2400, fontScale = 1.3f) { _ ->
        val team = bounds("teammate-row:default")
        val space = bounds("space-row:room-1")
        assertEquals(team.left.value, space.left.value, 0.5f)
        assertTrue("The spaces follow the team", team.bottom <= space.top)
        assertTrue("A row is capped, not stretched across 1000 dp (${space.width})", space.width.value <= 720f)
        assertTrue("The buttons stack at large text", bounds("bot-mode-new-bot").bottom <= bounds("bot-mode-new-chat").top)
        compose.onAllNodesWithTag("bot-mode-home-team").assertCountEquals(0)
    }
}
