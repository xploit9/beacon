package dev.hermes.android.data

import androidx.lifecycle.SavedStateHandle
import dev.hermes.android.ui.BotModeBack
import dev.hermes.android.ui.botModeAwaitingList
import dev.hermes.android.ui.botModeBack
import dev.hermes.android.ui.botModeEmptyKnown
import dev.hermes.android.core.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Owner trial 1.1.33: F7 (a Bot Chat that would not open), F8 (false empty states on a cold start), F18 (the way back from Bot Mode). */
class NativeBotModeColdStartTest {
    private val origin = "https://native.example"
    private val saved = """{"bots":[{"profile":"default","title":"Personal","description":""},{"profile":"work","title":"Work","description":"Studio"}],
        "spaces":[{"room_id":"r1","name":"Team","members":[{"profile":"default"},{"profile":"work"}],"updated_at":1800000000}],
        "previews":{"r1":{"speaker":"work","text":"Plan the week"}}}"""

    private fun client(owner: String = "owner", profile: String = "default", answer: (Request) -> Pair<Int, String>) =
        BeaconClient(AuthContext(origin, emptyList(), owner, profile), null) { builder ->
            builder.addInterceptor { chain ->
                val (code, body) = answer(chain.request())
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture").body(body.toResponseBody("application/json".toMediaType())).build()
            }
        }

    private suspend fun awaitIdle(controller: NativeBotModeController) {
        yield()
        withTimeout(5000) { while (controller.state.value.loading) delay(5) }
    }

    // F8 ---------------------------------------------------------------------------------------------------------------

    @Test fun beforeAnyAnswerNoScreenMayClaimThereAreNoBots() = runBlocking<Unit> {
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { null }
        assertFalse(controller.state.value.loaded)
        assertTrue(botModeAwaitingList(controller.state.value))
        assertFalse(botModeEmptyKnown(controller.state.value))
        // A refresh before the app is connected asks nobody and says nothing.
        controller.refresh(silent = true); yield()
        assertTrue(botModeAwaitingList(controller.state.value))
        assertFalse(botModeEmptyKnown(controller.state.value))
        controller.showCached(null, origin, "owner", "default")
        assertTrue(botModeAwaitingList(controller.state.value))
    }

    @Test fun aColdStartShowsTheSavedListAndKeepsItUntilHermesAnswers() = runBlocking<Unit> {
        var live: BeaconClient? = null
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { live }
        val answering = client { request ->
            if (request.url.encodedPath.endsWith("/bot-mode")) 200 to """{"bots":[{"profile":"default","title":"Personal"}],"spaces":[]}""" else 503 to "{}"
        }
        try {
            controller.showCached(JSONObject(saved), origin, "owner", "default")
            controller.state.value.let {
                assertEquals(listOf("default", "work"), it.bots.map { bot -> bot.profile })
                assertEquals("Team", it.spaces.single().name)
                assertEquals(BotModePreview("work", "Plan the week"), it.spaces.single().last)
                assertTrue(it.loaded)
            }
            assertFalse(botModeAwaitingList(controller.state.value))
            assertTrue("a list is on screen, so no empty state", controller.state.value.bots.isNotEmpty() && botModeEmptyKnown(controller.state.value))
            // The screens refresh while the app is still connecting: that must not wipe what is shown.
            controller.refresh(silent = true); yield()
            assertEquals(2, controller.state.value.bots.size)
            live = answering
            controller.refresh(); awaitIdle(controller)
            assertEquals(listOf("default"), controller.state.value.bots.map { it.profile })
            assertTrue(controller.state.value.spaces.isEmpty())
            // Hermes has now said "none": only now is an empty state true.
            assertTrue(controller.state.value.loaded && botModeEmptyKnown(controller.state.value))
        } finally { controller.clear(); answering.closeNative() }
    }

    @Test fun aSavedListOfAnotherAccountNeverShowsUnderTheOneThatConnects() = runBlocking<Unit> {
        val someoneElse = client("other-owner") { 503 to "{}" }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { someoneElse }
        try {
            controller.showCached(JSONObject(saved), origin, "owner", "default")
            assertEquals(2, controller.state.value.bots.size)
            controller.refresh(); awaitIdle(controller)
            assertTrue(controller.state.value.bots.isEmpty() && controller.state.value.spaces.isEmpty())
            assertFalse(controller.state.value.loaded)
        } finally { controller.clear(); someoneElse.closeNative() }
        // Neither does another profile of the same account.
        val otherProfile = client("owner", "work") { 503 to "{}" }
        val second = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { otherProfile }
        try {
            second.showCached(JSONObject(saved), origin, "owner", "default")
            second.refresh(); awaitIdle(second)
            assertTrue(second.state.value.bots.isEmpty())
        } finally { second.clear(); otherProfile.closeNative() }
    }

    @Test fun aSavedListIsIgnoredOnceTheControllerHasAnAccount() = runBlocking<Unit> {
        val c = client { 200 to """{"bots":[{"profile":"default","title":"Personal"}],"spaces":[]}""" }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            controller.showCached(JSONObject(saved), origin, "owner", "default")
            assertEquals(listOf("default"), controller.state.value.bots.map { it.profile })
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun aFailedFirstReadIsNeitherAnEmptyListNorAnEndlessSkeleton() = runBlocking<Unit> {
        val c = client { 503 to """{"detail":"Offline"}""" }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            val state = controller.state.value
            assertFalse(state.loaded)
            assertTrue(state.error.isNotBlank())
            assertFalse("the error shows, not a skeleton", botModeAwaitingList(state))
            assertFalse("a failed read does not make the list empty", botModeEmptyKnown(state))
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun anAnswerOfNoBotsIsTheOnlyThingThatMakesTheListEmpty() = runBlocking<Unit> {
        val c = client { 200 to """{"bots":[],"spaces":[]}""" }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, _ -> }) { c }
        try {
            controller.refresh(); awaitIdle(controller)
            assertTrue(botModeEmptyKnown(controller.state.value))
            assertTrue(controller.state.value.bots.isEmpty() && controller.state.value.spaces.isEmpty())
        } finally { controller.clear(); c.closeNative() }
    }

    // F7 ---------------------------------------------------------------------------------------------------------------

    @Test fun aBotChatThatWillNotOpenSaysSoInPlainWordsAndRetryAsksAgain() = runBlocking<Unit> {
        var chatStatus = 409
        var asked = 0
        var opened = ""
        val c = client { request ->
            when {
                request.url.encodedPath.endsWith("/chat") -> { asked++
                    if (chatStatus == 200) 200 to """{"profile":"default","title":"Bot Chat","stored_session_id":"bot-chat"}"""
                    else chatStatus to """{"detail":"Bot Chat is unavailable or belongs to another owner"}""" }
                else -> 200 to """{"bots":[{"profile":"default","title":"Personal"}],"spaces":[]}"""
            }
        }
        val controller = NativeBotModeController(this, SavedStateHandle(), { _, id -> opened = id }) { c }
        val bot = BotModeBot("default", "Personal", "")
        try {
            controller.refresh(); awaitIdle(controller)
            controller.openBot(bot); awaitIdle(controller)
            assertEquals("This Bot Chat can't be opened right now.", controller.state.value.error)
            assertFalse(controller.state.value.error.contains("owner", ignoreCase = true))
            assertNull(controller.state.value.activeBot)
            // Retry is the same call again, and it works once the server does.
            chatStatus = 200
            controller.openBot(bot); awaitIdle(controller)
            withTimeout(2000) { while (controller.state.value.activeBot == null) delay(5) }
            assertEquals(2, asked)
            assertEquals("bot-chat", opened)
            assertEquals("", controller.state.value.error)
        } finally { controller.clear(); c.closeNative() }
    }

    @Test fun everyRefusalOfABotChatReadsAsPlainWords() {
        assertEquals("This Bot Chat can't be opened right now.", botChatFailure(409))
        assertEquals("Bot Chat isn't available right now.", botChatFailure(503))
        assertEquals("Couldn't open this Bot Chat.", botChatFailure(0))
        for (status in listOf(0, 400, 409, 500, 503)) botChatFailure(status).let { text ->
            assertFalse(text, text.contains("native", ignoreCase = true) || text.contains("owner", ignoreCase = true))
        }
    }

    // F18 --------------------------------------------------------------------------------------------------------------

    @Test fun backLeavesABotOrSpaceForTheHomeAndTheHomeForNormalChats() {
        val home = NativeBotModeState()
        assertEquals(BotModeBack.LeaveMode, botModeBack(home, switching = false))
        assertEquals(BotModeBack.None, botModeBack(home, switching = true))
        val bot = BotModeBot("default", "Personal", "")
        assertEquals(BotModeBack.Home, botModeBack(home.copy(activeBot = bot), switching = false))
        assertEquals(BotModeBack.Home, botModeBack(home.copy(openingBot = bot), switching = false))
        assertEquals(BotModeBack.Home, botModeBack(home.copy(selectedSpace = "r1"), switching = false))
        // Out of a bot while the mode is switching is still out of the bot.
        assertEquals(BotModeBack.Home, botModeBack(home.copy(activeBot = bot), switching = true))
    }
}
