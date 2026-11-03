package dev.hermes.android.uiux.drawer

import dev.hermes.android.core.json
import dev.hermes.android.ui.UP_NEXT_LIMIT
import dev.hermes.android.ui.groupAvatarsShown
import dev.hermes.android.ui.groupAvatarsWidth
import dev.hermes.android.ui.upNextRuns
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which schedules the Bot Mode home screen lists under "Up next"; the layout itself is in BotModeHomeUiTest. */
class BotModeHomeTest {
    private fun job(id: String, next: String?, vararg extra: Pair<String, Any?>) =
        json("id" to id, "name" to "Job $id", "enabled" to true, "state" to "scheduled", "next_run_at" to next, *extra)

    private fun ids(jobs: List<JSONObject>, profile: String = "default") = upNextRuns(jobs, profile, "Untitled schedule").map { it.id }

    @Test fun theSoonestRunsComeFirstWhateverOrderHermesSentThem() {
        val jobs = listOf(job("late", "2026-11-04T09:00:00+04:00"), job("soon", "2026-11-02T18:00:00+04:00"), job("mid", "2026-11-03T08:00:00+04:00"))
        assertEquals(listOf("soon", "mid", "late"), ids(jobs))
        // Compared as moments, not as text: 23:00 in +04:00 is earlier than 20:00 in -02:00 the same day.
        assertEquals(listOf("a", "b"), ids(listOf(job("b", "2026-11-02T20:00:00-02:00"), job("a", "2026-11-02T23:00:00+04:00"))))
    }

    @Test fun onlyTheNextFewAreListed() {
        val jobs = (1..UP_NEXT_LIMIT + 3).map { job("j$it", "2026-11-0${(it % 9) + 1}T08:00:00+04:00") }
        assertEquals(UP_NEXT_LIMIT, ids(jobs).size)
    }

    @Test fun nothingPausedFinishedOrWithoutANextTimeIsListed() {
        val jobs = listOf(
            job("paused", "2026-11-02T18:00:00+04:00", "state" to "paused"),
            job("off", "2026-11-02T18:00:00+04:00", "enabled" to false),
            job("done", "2026-11-02T18:00:00+04:00", "state" to "completed"),
            job("finished", "2026-11-02T18:00:00+04:00", "state" to "finished"),
            job("never", null),
            job("blank", ""),
            job("broken", "tomorrow morning"),
            job("ok", "2026-11-02T19:00:00+04:00"),
        )
        assertEquals(listOf("ok"), ids(jobs))
    }

    @Test fun aCallbackIsNotARoutineOfABot() {
        val hash = "0123456789abcdef".repeat(4)
        val callback = job("cb", "2026-11-02T18:00:00+04:00", "name" to "Beacon callback $hash", "script" to "beacon-callback-$hash.py")
        assertEquals(listOf("real"), ids(listOf(callback, job("real", "2026-11-02T19:00:00+04:00"))))
    }

    @Test fun theJobsThePluginKeepsInShapeAreNotARoutineOfABot() {
        val next = "2026-11-02T18:00:00+04:00"
        val checkup = job("checkup", next, "name" to "Morning news", "script" to "/home/maya/.hermes/scripts/beacon-checkups.py")
        val watch = job("watch", next, "name" to "Plan the week", "prompt" to "<!-- BEACON_PROACTIVE_JOB:watch_email --> Look through the connected sources.")
        val upkeep = job("upkeep", next, "name" to "Standup", "prompt" to "  <!--BEACON_PROACTIVE_JOB:nightly_memory-->Tidy up what Beacon remembers.")
        // They are found by the script they run or the marker their prompt starts with, so what they are called changes nothing.
        assertEquals(listOf("mine"), ids(listOf(checkup, watch, upkeep, job("mine", "2026-11-02T19:00:00+04:00", "name" to "Plan the week", "prompt" to "Draft the weekly plan."))))
        // And the owner's own routine is never taken for one of them because of its name or because its prompt mentions the marker.
        val named = job("named", next, "name" to "Beacon check-ups", "prompt" to "Look at BEACON_PROACTIVE_JOB markers in the log.")
        assertEquals(listOf("named"), ids(listOf(named)))
    }

    @Test fun aJobBelongsToItsOwnProfileElseTheOneTheListWasReadFor() {
        val runs = upNextRuns(listOf(job("mine", "2026-11-02T18:00:00+04:00", "profile" to "coder"), job("plain", "2026-11-02T19:00:00+04:00"), job("blank", "2026-11-02T20:00:00+04:00", "profile" to "")), "default", "Untitled schedule")
        assertEquals(listOf("coder", "default", "default"), runs.map { it.profile })
    }

    @Test fun anUnnamedJobGetsTheUntitledName() {
        val runs = upNextRuns(listOf(job("n", "2026-11-02T18:00:00+04:00", "name" to "")), "default", "Untitled schedule")
        assertEquals("Untitled schedule", runs.single().title)
        assertEquals("Job x", upNextRuns(listOf(job("x", "2026-11-02T18:00:00+04:00")), "default", "Untitled schedule").single().title)
    }

    @Test fun noJobsMeansNoRuns() {
        assertTrue(ids(emptyList()).isEmpty())
    }

    @Test fun aGroupRowsAvatarsTakeTheWidthOfOneBotAvatarSoTheNamesLineUp() {
        // Two overlapping avatars are 1.7 avatars wide, three are 2.4: the group row offers two places, the empty-space page three.
        assertEquals(1.7f, groupAvatarsWidth(2), 0.001f)
        assertEquals(2.4f, groupAvatarsWidth(3), 0.001f)
        assertEquals(48f, 48f / 1.7f * groupAvatarsWidth(2), 0.01f)
    }

    @Test fun moreBotsThanPlacesGiveTheLastPlaceToThePlusCount() {
        // All of them while they fit.
        assertEquals(listOf(1, 2, 2, 3), listOf(groupAvatarsShown(1, 2), groupAvatarsShown(2, 2), groupAvatarsShown(2, 3), groupAvatarsShown(3, 3)))
        // Otherwise one place less, and "+N" counts everyone left over.
        assertEquals(1, groupAvatarsShown(3, 2))
        assertEquals(1, groupAvatarsShown(6, 2))
        assertEquals(2, groupAvatarsShown(4, 3))
        assertEquals(2, groupAvatarsShown(9, 3))
    }
}
