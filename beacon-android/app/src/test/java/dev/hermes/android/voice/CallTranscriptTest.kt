package dev.hermes.android.voice

import dev.hermes.android.Message
import dev.hermes.android.core.json
import dev.hermes.android.core.Scope
import dev.hermes.android.data.mergeVoiceTranscripts
import org.junit.Assert.*
import org.junit.Test

class CallTranscriptTest {
    @Test fun speechDiagnosticsKeepOnlyCodeControlledAssistantIdentities() {
        for (kind in speechDiagnosticTurns) {
            assertEquals(kind, directDiagnosticTurnId(json("turn_id" to kind, "stream" to "assistant")))
        }
        assertEquals("connection", directDiagnosticTurnId(json("turn_id" to "speech-close", "stream" to "user")))
        assertEquals("connection", directDiagnosticTurnId(json("turn_id" to "private-owner-value", "stream" to "assistant")))
        assertEquals("connection", directDiagnosticTurnId(json("stage" to "provider_ready")))
        assertEquals("connection", directDiagnosticTurnId(json("turn_id" to "speech-close")))
        assertTrue(isSpeechFailureDiagnostic(json("type" to "media_diagnostic", "turn_id" to "speech-close", "stream" to "assistant")))
        assertFalse(isSpeechFailureDiagnostic(json("type" to "media_diagnostic", "turn_id" to "speech-ready", "stream" to "assistant")))
        assertFalse(isSpeechFailureDiagnostic(json("type" to "transcript", "turn_id" to "speech-close", "stream" to "assistant")))
    }

    @Test fun lateProviderInputTranscriptionStaysBeforeItsReplyByTurnId() {
        val answer = CallTranscript("call:assistant:7", "assistant", "Here is the answer", turnId = "7")
        val question = CallTranscript("call:user:provider-item", "user", "What is the question?", turnId = "7")

        val ordered = emptyList<CallTranscript>().acceptTranscript(answer).acceptTranscript(question)

        assertEquals(listOf("user", "assistant"), ordered.map { it.role })
        assertEquals(listOf("What is the question?", "Here is the answer"), ordered.map { it.text })
    }

    @Test fun directTurnFlushPlacesUserCaptionBeforeAssistantEvenWhenItArrivedLast() {
        val captions = linkedMapOf("assistant" to "The reply", "user" to "The prompt")
        val events = mutableListOf<org.json.JSONObject>()

        finishDirectTranscriptTurn("call", "session", 7, captions, 3000) { events += it }

        assertEquals(listOf("user", "assistant"), events.map { it.getString("role") })
    }

    @Test fun interruptedDirectCaptionStaysVisibleAsFinalAndCannotCompleteOrReplayTwice() {
        val captions=mutableMapOf("user" to "Check this", "assistant" to "I can see the")
        var live=listOf(CallTranscript("call:user:7","user","Check this",false),
            CallTranscript("call:assistant:7","assistant","I can see the",false,interrupted=true))
        assertEquals(1,live.pendingTranscripts(emptyList(),true).size)
        val archived=mutableListOf<org.json.JSONObject>()
        val next=finishDirectTranscriptTurn("call","session",7,captions,1200,interrupted=true) {
            archived+=it
            live=live.acceptTranscript(requireNotNull(CallTranscript.from(it,"session","call")))
        }
        assertEquals(8L,next);assertTrue(captions.isEmpty())
        val visible=live.pendingTranscripts(emptyList(),true)
        assertEquals(2,visible.size)
        val assistant=visible.single { it.role=="assistant" }
        assertEquals("I can see the",assistant.text);assertTrue(assistant.final);assertTrue(assistant.interrupted)
        assertFalse(visible.single { it.role=="user" }.interrupted)
        assertTrue(archived.all { it.getString("turn_id")=="7" })
        assertEquals(setOf("call:user:7","call:assistant:7"),archived.map { it.getString("event_id") }.toSet())
        assertEquals(next+1,finishDirectTranscriptTurn("call","session",next,captions,1300) { error("No replay after consumed interruption") })
        assertEquals(2,archived.size)
        assertEquals(live,live.acceptTranscript(assistant.copy(text="Late tokens",final=false,interrupted=false)))
    }
    @Test fun hangUpKeepsTheLastUserCaptionWhenNoAssistantReplyHasStarted() {
        val captions=mutableMapOf("user" to "Remind me at five")
        val events=mutableListOf<org.json.JSONObject>()
        finishDirectTranscriptTurn("call","session",7,captions,1200,interrupted=true) { events+=it }

        val merged=mergeVoiceTranscripts(emptyList(),events.mapNotNull { CallTranscript.from(it,"session","call") },emptySet())

        assertEquals("Remind me at five",merged.single().text)
        assertTrue(merged.single().final)
        assertFalse(merged.single().interrupted)
    }
    @Test fun speakingFirstInAFreshTurnDoesNotMarkTheComingReplyInterrupted() {
        assertFalse(interruptsReply(true,emptyMap()))
        assertFalse(interruptsReply(true,mapOf("user" to "Is there anything")))
        assertTrue(interruptsReply(true,mapOf("assistant" to "Tomorrow you have")))
        assertFalse(interruptsReply(false,mapOf("assistant" to "Tomorrow you have")))
    }
    @Test fun ordinaryDirectTurnUsesSameFinalPayloadWithoutInterruptedLabel() {
        val captions=mutableMapOf("assistant" to "Complete answer")
        var event:CallTranscript?=null
        assertEquals(4L,finishDirectTranscriptTurn("call","session",3,captions,900) { event=CallTranscript.from(it,"session","call") })
        assertTrue(event!!.final);assertFalse(event!!.interrupted)
    }

    @org.junit.Test fun canonicalFinalReplacesDifferentSpeechProgressIdWithoutDiscardingOtherRole() {
        val assistant = CallTranscript("call:assistant:speech", "assistant", "Working", final = false)
        val user = CallTranscript("call:user:progress", "user", "Question", final = false)
        val final = CallTranscript("call:assistant:item", "assistant", "Complete answer", messageId = "canonical")
        val result = listOf(assistant, user).acceptTranscript(final)
        org.junit.Assert.assertEquals(listOf(user, final), result)
        org.junit.Assert.assertTrue(result.acceptTranscript(final).count { it.eventId == final.eventId } == 1)
    }

    @Test fun savedVoiceTranscriptKeepsBeaconAttributionInOrdinaryChats() {
        val voice = Message.from(json("role" to "assistant", "content" to "Hello",
            "display_metadata" to json("channel" to "voice")), 0, "ordinary")
        assertTrue(voice.voice)
        assertFalse(Message.from(json("role" to "assistant", "content" to "Hello"), 0).voice)
    }
    @Test fun fullTextProgressReplacesOneEventAndLatePartialCannotReplaceFinal() {
        val first = CallTranscript("call:assistant:1", "assistant", "Hel", false)
        val partials = emptyList<CallTranscript>().acceptTranscript(first).acceptTranscript(first.copy(text = "Hello"))
        assertEquals(listOf(first.copy(text = "Hello")), partials)
        val committed = partials.acceptTranscript(first.copy(text = "Hello there", final = true, messageId = "42"))
        assertEquals(committed, committed.acceptTranscript(first.copy(text = "stale")))
        assertEquals(1, committed.size)
    }

    @Test fun finalReconcilesByDurableIdentityWithoutCollapsingRepeatedWords() {
        val live = listOf(CallTranscript("call:user:1", "user", "Yes", messageId = "42"))
        assertEquals(live, live.pendingTranscripts(listOf(Message("41", "user", "Yes")), true))
        assertTrue(live.pendingTranscripts(listOf(Message("42", "user", "Yes")), true).isEmpty())
        assertTrue(live.pendingTranscripts(listOf(Message("call:user:1", "user", "Yes")), true).isEmpty())
    }

    @Test fun canceledAndEndedProjectionsNeverSurviveAsCompletedAnswers() {
        val progress = CallTranscript("call:assistant:1", "assistant", "Unfinished", false)
        assertTrue(listOf(progress).pendingTranscripts(emptyList(), false).isEmpty())
        val canceled = listOf(progress).acceptTranscript(progress.copy(text = "", final = true, interrupted = true))
        assertTrue(canceled.pendingTranscripts(emptyList(), true).isEmpty())
        assertEquals(canceled, canceled.acceptTranscript(progress.copy(text = "Late canceled tokens")))
        val interrupted = progress.copy(final = true, messageId = "42", interrupted = true)
        assertEquals(listOf(interrupted), listOf(interrupted).pendingTranscripts(emptyList(), false))
    }

    @Test fun wireRequiresCanonicalSessionAndKnownRoleAndDefaultsToCommitted() {
        val wire = json("event_id" to "call:user:1", "role" to "user", "text" to "Hello", "session_id" to "canonical", "message_id" to 42)
        val event = requireNotNull(CallTranscript.from(wire, "canonical", "call"))
        assertTrue(event.final); assertEquals("42", event.messageId)
        assertNull(CallTranscript.from(wire, "other", "call"))
        assertNull(CallTranscript.from(wire, "canonical", "new-call"))
        assertNull(CallTranscript.from(wire.put("event_id", "call:assistant:1"), "canonical", "call"))
        assertNull(CallTranscript.from(wire.put("role", "tool"), "canonical", "call"))
    }

    @Test fun projectionCannotCrossOwnerOriginProfileOrCanonicalSession() {
        val scope = Scope("https://fixture.example", "owner", "default", "canonical")
        val event = CallTranscript("call:user:1", "user", "Private progress", false)
        val call = CallState(active = true, conversationScope = scope, transcripts = listOf(event))
        assertEquals(listOf(event), call.transcriptsFor(scope, emptyList()))
        listOf(scope.copy(owner = "other"), scope.copy(profile = "other"), scope.copy(origin = "https://other.example"), scope.session("other")).forEach { wrong -> assertTrue(call.transcriptsFor(wrong, emptyList()).isEmpty()) }
        assertTrue(call.transcriptsFor(null, emptyList()).isEmpty())
    }

    @Test fun durableInterruptedMetadataRemainsVisibleAfterReopen() {
        val row = json("id" to 42, "role" to "assistant", "content" to "Partial answer", "display_metadata" to json("interrupted" to true))
        assertTrue(Message.from(row, 0, "canonical").interrupted)
    }

    @Test fun canceledProgressBeforeFirstTextCannotReappearUntilDurableFinal() {
        val canceled = CallTranscript("call:assistant:1", "assistant", "Canceled before display", false, interrupted = true)
        val tombstone = emptyList<CallTranscript>().acceptTranscript(canceled)
        assertTrue(tombstone.pendingTranscripts(emptyList(), true).isEmpty())
        assertEquals(tombstone, tombstone.acceptTranscript(canceled.copy(text = "Late tokens", interrupted = false)))
        val committed = canceled.copy(final = true, messageId = "42")
        assertEquals(listOf(committed), tombstone.acceptTranscript(committed).pendingTranscripts(emptyList(), true))
    }

    @Test fun equalTimestampGeminiLinesKeepTheOwnersQuestionBeforeItsReply() {
        val answer = CallTranscript("call:assistant:7", "assistant", "Here is the reply", at = 34326, turnId = "7")
        val question = CallTranscript("call:user:7", "user", "Please check this", at = 34326, turnId = "7")

        assertEquals(listOf("user", "assistant"), orderCallTranscriptLines(listOf(answer, question)).map { it.role })
    }

    @Test fun callEndRenamesOnlyUntitledChatsFromTheOwnersFirstSpokenRequest() {
        val transcript = listOf(
            CallTranscript("call:assistant:1", "assistant", "The answer", at = 20, turnId = "1"),
            CallTranscript("call:user:1", "user", "  Could you check my calendar?  ", at = 10, turnId = "1"),
        )

        assertEquals("Could you check my calendar?", deriveCallEndTitle("New conversation", transcript, "Beacon"))
        assertEquals("Call with Beacon", deriveCallEndTitle("", emptyList(), "Beacon"))
        // (From the second call on, the same words repeat: aCallWithNoSpokenRequestGetsATitleNoListedChatHolds.)
        assertNull(deriveCallEndTitle("Owner's title", transcript, "Beacon"))
    }

    /** 47/66: Hermes refuses a repeat title (its rename raises and the gateway logs a dispatch crash), so a call's title never repeats one the phone lists. */
    @Test fun aCallWithNoSpokenRequestGetsATitleNoListedChatHolds() {
        val zone = java.time.ZoneId.of("UTC"); val locale = java.util.Locale.ENGLISH
        val first = requireNotNull(deriveCallEndTitle("New conversation", emptyList(), "Beacon", startedAt = 1_794_936_720_000, zone = zone, locale = locale))
        assertEquals("Call with Beacon, 17 Nov, 17:32", first)
        // The second voice-only call, a minute later or the very same minute, is told apart from the first.
        val later = requireNotNull(deriveCallEndTitle("New conversation", emptyList(), "Beacon", listOf(first), startedAt = 1_794_936_780_000, zone = zone, locale = locale))
        assertEquals("Call with Beacon, 17 Nov, 17:33", later)
        val sameMinute = requireNotNull(deriveCallEndTitle("New conversation", emptyList(), "Beacon", listOf(first), startedAt = 1_794_936_730_000, zone = zone, locale = locale))
        assertEquals("$first #2", sameMinute)
        // Without a start time the plain name still never repeats.
        assertEquals("Call with Beacon #2", deriveCallEndTitle("", emptyList(), "Beacon", listOf("Call with Beacon")))
    }

    @Test fun aFirstSpokenRequestThatAChatAlreadyHoldsIsNumberedTheWayHermesNumbersItsOwnTitles() {
        val transcript = listOf(CallTranscript("call:user:1", "user", "What is the weather", turnId = "1"))
        assertEquals("What is the weather #2", deriveCallEndTitle("New conversation", transcript, "Beacon", listOf("what is the weather")))
        assertEquals("What is the weather #3", deriveCallEndTitle("New conversation", transcript, "Beacon", listOf("What is the weather", "What is the weather #2")))
        assertEquals("What is the weather", deriveCallEndTitle("New conversation", transcript, "Beacon", listOf("Another chat")))
    }

    /** Root, 17 Nov: the title is in the phone's language with the assistant's current name, then the call's start in the phone's own date and clock style. */
    @Test fun theCallTitleCarriesTheAssistantsCurrentNameInThePhonesLanguage() {
        val german = dev.hermes.android.ui.assistantName("Anruf mit Beacon", "Mira")
        assertEquals("Anruf mit Mira", german)
        val label: (Long) -> String = { "17. Nov., 17:32" }
        assertEquals("Anruf mit Mira, 17. Nov., 17:32", deriveCallEndTitle("New conversation", emptyList(), "Mira", startedAt = 1_794_936_720_000, callWith = german, startedLabel = label))
        // Renamed again later: the next call's title uses the new name, the older chat keeps its own.
        val later = dev.hermes.android.ui.assistantName("Anruf mit Beacon", "Nova")
        assertEquals("Anruf mit Nova, 17. Nov., 17:32", deriveCallEndTitle("", emptyList(), "Nova", startedAt = 1_794_936_720_000, callWith = later, startedLabel = label))
        // English with no phrase given still follows the name, and a blank stamp leaves no trailing comma.
        assertEquals("Call with Mira", deriveCallEndTitle("", emptyList(), "Mira", startedAt = 1_794_936_720_000, startedLabel = { " " }))
    }

    @Test fun everyLanguageHasTheCallPhraseWithTheNamePlaceholderInIt() {
        val module = requireNotNull(listOf(".", "app", "beacon-android/app").map { java.io.File(it) }.firstOrNull { java.io.File(it, "src/main/assets/native-locales.json").isFile })
        val catalogs = org.json.JSONObject(java.io.File(module, "src/main/assets/native-locales.json").readText())
        val languages = catalogs.keys().asSequence().toList()
        assertEquals(16, languages.size)
        for (language in languages) {
            val phrase = catalogs.getJSONObject(language).optString("call with beacon")
            assertTrue("$language has the phrase", phrase.isNotBlank())
            assertTrue("$language keeps the name's place: $phrase", phrase.contains("Beacon"))
            if (language != "en") assertNotEquals("$language is translated", "Call with Beacon", phrase)
        }
    }

    /**
     * Root, 17 Nov (live Gemini pending the owner's top-up): a call that ends with no spoken request, run twice against a Hermes that raises on a repeat title
     * (hermes_state_titles.py `_resolve_title_conflict`), gives two titled chats, sends no refused title and loses no rename.
     */
    @Test fun twoVoiceOnlyCallsAgainstAHermesThatRefusesRepeatTitlesNeverSendOne() {
        val hermes = mutableMapOf<String, String>()   // session id -> title
        var refused = 0
        val rename: (String, String) -> Unit = { session, title ->
            if (hermes.any { (id, held) -> id != session && held == title }) { refused++; throw IllegalStateException("Title '$title' is already in use by session x") }
            hermes[session] = title
        }
        val remembered = mutableSetOf<String>()
        val zone = java.time.ZoneId.of("UTC"); val locale = java.util.Locale.ENGLISH
        val startedAt = 1_794_936_720_000   // both calls start in the same minute
        kotlinx.coroutines.runBlocking {
            for (session in listOf("chat-1", "chat-2", "chat-3")) {
                // The phone knows the earlier titles from its list, or (an empty chat not yet listed) from the ones it gave.
                val listed = if (session == "chat-2") emptyList() else hermes.values.toList()
                val title = deriveCallEndTitle("New conversation", emptyList(), "Beacon", takenTitles = listed + remembered, startedAt = startedAt, zone = zone, locale = locale)
                assertTrue(renameCallChat(title) { rename(session, it) })
                remembered += requireNotNull(title)
            }
        }
        assertEquals("no title was refused", 0, refused)
        assertEquals(3, hermes.values.toSet().size)
        assertEquals(listOf("Call with Beacon, 17 Nov, 17:32", "Call with Beacon, 17 Nov, 17:32 #2", "Call with Beacon, 17 Nov, 17:32 #3"), listOf("chat-1", "chat-2", "chat-3").map { hermes.getValue(it) })
    }

    @Test fun aTitleStaysWithinHermesLimitOfOneHundredCharactersEvenNumbered() {
        val long = "Call with " + "A".repeat(120)
        val title = requireNotNull(deriveCallEndTitle("", emptyList(), "A".repeat(120)))
        assertTrue(title.length <= 100)
        val numbered = requireNotNull(deriveCallEndTitle("", emptyList(), "A".repeat(120), listOf(title)))
        assertTrue("$numbered", numbered.length <= 100 && numbered.endsWith(" #2") && numbered != title)
        assertTrue(long.length > 100)
        // A surrogate pair is never cut in half.
        assertFalse(uniqueCallTitle("a".repeat(91) + "😀", emptyList()).last().isHighSurrogate())
    }

    @Test fun aRenameThatFailsOrIsRefusedIsReportedNotThrown() {
        kotlinx.coroutines.runBlocking {
            assertFalse(renameCallChat(null) { throw AssertionError("nothing to send") })
            assertFalse(renameCallChat("Call with Beacon") { throw IllegalStateException("Title 'Call with Beacon' is already in use by session x") })
            var sent = ""
            assertTrue(renameCallChat("Call with Beacon #2") { sent = it })
            assertEquals("Call with Beacon #2", sent)
        }
    }

    @Test fun aCancelledRenameStillCancels() {
        kotlinx.coroutines.runBlocking {
            val cancelled = runCatching { renameCallChat("x") { throw kotlinx.coroutines.CancellationException("left") } }.exceptionOrNull()
            assertTrue(cancelled is kotlinx.coroutines.CancellationException)
        }
    }

    @Test fun callTitleStopsAtFortyUnicodeCharactersWithoutSplittingASurrogatePair() {
        val speech = "a".repeat(39) + "😀" + "b"
        val title = requireNotNull(deriveCallEndTitle("New conversation", listOf(CallTranscript(
            "call:user:1", "user", speech, turnId = "1",
        )), "Beacon"))

        assertEquals(40, title.codePointCount(0, title.length))
        assertFalse(title.last().isHighSurrogate())
    }

    @Test fun collapsedCallCardKeepsOnlyTheLastFourLinesButPreservesEverySearchableLine() {
        val lines = (1..6).map { CallTranscript("call:assistant:$it", "assistant", "Transcript line $it", turnId = it.toString()) }

        assertEquals(listOf("Transcript line 3", "Transcript line 4", "Transcript line 5", "Transcript line 6"),
            visibleCallTranscriptLines(lines, expanded = false).map { it.text })
        assertEquals(lines.map { it.text }, visibleCallTranscriptLines(lines, expanded = true).map { it.text })
    }

    @Test fun transcriptEventsGroupByTheirCallIdentity() {
        val events = listOf(
            CallTranscript("call-a:user:1", "user", "First call"),
            CallTranscript("call-b:user:1", "user", "Second call"),
            CallTranscript("call-a:assistant:1", "assistant", "Reply"),
        )

        val groups = dev.hermes.android.ui.groupCallTranscriptEvents(events)

        assertEquals(listOf("call-a", "call-b"), groups.map { it.callId })
        assertEquals(listOf("First call", "Reply"), groups.first().lines.map { it.text })
    }

    // Device round 4, D2: a cut reply keeps only what played, cut at a word and marked.
    @Test fun aCutReplyKeepsTheShareOfItsWordsThatPlayed() {
        val words = (1..30).joinToString(" ") { "Tram twenty eight climbs through the old district on its route number $it." }.take(724).trimEnd()
        val kept = playedCaption(words, 12_160, 39_920)
        assertTrue(kept.length in 190..250);assertTrue(kept.endsWith("…"));assertTrue(words.startsWith(kept.removeSuffix("…")))
        // r4-item7r-1: 0.34 s played while the words had run ahead of the audio received: the speech ceiling keeps the first words only.
        val jump = "You have a call with Anna at 9:00. You have a call with Ben at 10:00. You have a call with Carla"
        assertEquals("You have…", playedCaption(jump, 340, 1_000))
        assertEquals("all of it played", words, playedCaption(words, 46_680, 46_680))
        assertEquals("nothing played", "", playedCaption(words, 0, 46_680))
        val captions = mutableMapOf("user" to "Tell me about tram 28.", "assistant" to words)
        cutCaptions(captions, org.json.JSONObject().put("type", "interrupted").put("played_ms", 0).put("received_ms", 46_680))
        val written = mutableListOf<org.json.JSONObject>()
        finishDirectTranscriptTurn("call", "chat", 3L, captions, 0L, interruptsReply(true, captions)) { written += it }
        assertEquals("nothing played: no assistant line", listOf("user"), written.filter { it.getBoolean("final") }.map { it.getString("role") })
        assertTrue("its live caption is withdrawn (REVIEW-E7 P2-2)", written.single { it.getString("role") == "assistant" }.getBoolean("withdrawn"))
        val untouched = mutableMapOf("assistant" to words)
        cutCaptions(untouched, org.json.JSONObject().put("type", "interrupted"))
        assertEquals("no audio figures: as it came", words, untouched["assistant"])
    }

    // Device round 4, D2 (the final check): a reply in a script without spaces keeps the share that played, cut at the character.
    @Test fun aCutReplyWithoutSpacesKeepsItsPlayedShare() {
        val words = "東京の路面電車は百年以上の歴史があり、今も多くの人に愛されています。毎日たくさんの観光客が乗っています。"
        val kept = playedCaption(words, 2_000, 4_000)
        assertEquals(words.substring(0, words.length / 2) + "…", kept)
        assertEquals("a surrogate pair is never split", "ab…", playedCaption("ab\uD83D\uDE00cd", 1_000, 2_000))
    }

    // Device round 4, D2: on screen the cut turn's final, shorter than its live caption, replaces it.
    @Test fun aCutTurnsShorterFinalReplacesItsLiveCaption() {
        val live = CallTranscript("call:assistant:3", "assistant", "Tram 28 climbs through Alfama, past the cathedral and on to Graca.", false, interrupted = true)
        val final = live.copy(text = "Tram 28 climbs through…", final = true)
        assertEquals(listOf("Tram 28 climbs through…"), listOf(live).acceptTranscript(final).map { it.text })
    }
}
