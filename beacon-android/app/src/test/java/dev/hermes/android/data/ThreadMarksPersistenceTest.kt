package dev.hermes.android.data

import dev.hermes.android.core.Scope
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ThreadMarks exists so the Threads lane survives an app restart and works offline: the view model gives it a [ThreadMarks.Store] backed by the phone's cache.
 * Every other test resets the object (which drops the store), so nothing else proved that marks are written, read back, kept apart per account and profile, or
 * cleared on delete. A restart is simulated the way process death works: the memory goes, the saved copy stays.
 */
class ThreadMarksPersistenceTest {
    private class MemoryStore : ThreadMarks.Store {
        val saved = HashMap<String, String>()
        var failRead = false
        var failWrite = false
        var writes = 0
        var onFirstRead: (suspend () -> Unit)? = null
        override suspend fun read(account: Scope): String? {
            // The value is taken before the hook runs: a slow read returns what the cache held when it began, so a mark added meanwhile is not in it.
            val value = saved[account.key]
            onFirstRead?.let { hook -> onFirstRead = null; hook() }
            if (failRead) throw java.io.IOException("cache unreadable")
            return value
        }
        override suspend fun write(account: Scope, value: String) {
            if (failWrite) throw java.io.IOException("cache unwritable")
            writes++
            saved[account.key] = value
        }
    }

    private val account = Scope("https://hermes.example", "owner", "default")
    private val store = MemoryStore()

    @Before fun fresh() { ThreadMarks.reset(); ThreadMarks.store = store }
    @After fun clean() { ThreadMarks.reset() }

    /** Process death: everything held in memory is gone, the cache stays. */
    private fun restart() { ThreadMarks.reset(); ThreadMarks.store = store }
    private fun savedIds(scope: Scope = account) = JSONArray(store.saved.getValue(scope.copy(session = "").key)).let { array -> (0 until array.length()).map { array.getString(it) } }

    @Test fun marksSurviveAnAppRestart() = runBlocking {
        assertTrue(ThreadMarks.add(account, "thread-1", "thread-2"))
        assertEquals(listOf("thread-1", "thread-2"), savedIds())
        restart()
        assertEquals(setOf("thread-1", "thread-2"), ThreadMarks.ids(account))
    }

    @Test fun marksAddedLaterJoinTheSavedOnesAcrossRestarts() = runBlocking {
        ThreadMarks.add(account, "thread-1")
        restart()
        assertTrue(ThreadMarks.add(account, "thread-2"))
        assertEquals(listOf("thread-1", "thread-2"), savedIds())
        restart()
        assertEquals(setOf("thread-1", "thread-2"), ThreadMarks.ids(account))
    }

    @Test fun marksAreKeptApartPerAccountAndProfile() = runBlocking {
        val otherProfile = account.copy(profile = "coder")
        val otherOwner = account.copy(owner = "someone-else")
        val otherOrigin = account.copy(origin = "https://other.example")
        ThreadMarks.add(account, "mine")
        ThreadMarks.add(otherProfile, "coder-thread")
        restart()
        assertEquals(setOf("mine"), ThreadMarks.ids(account))
        assertEquals(setOf("coder-thread"), ThreadMarks.ids(otherProfile))
        assertTrue(ThreadMarks.ids(otherOwner).isEmpty())
        assertTrue(ThreadMarks.ids(otherOrigin).isEmpty())
        assertEquals("one saved list per account and profile", 2, store.saved.size)
        assertEquals(listOf("mine"), savedIds(account))
        assertEquals(listOf("coder-thread"), savedIds(otherProfile))
    }

    @Test fun aChatInTheAccountIsTheSameAccountForTheMarks() = runBlocking {
        ThreadMarks.add(account.session("chat-1"), "thread-1")
        assertEquals(setOf("thread-1"), ThreadMarks.ids(account.session("chat-2")))
        restart()
        assertEquals(setOf("thread-1"), ThreadMarks.ids(account))
    }

    @Test fun deletingAThreadClearsItsMarkNowAndAfterARestart() = runBlocking {
        ThreadMarks.add(account, "thread-1", "thread-2")
        ThreadMarks.remove(account, "thread-1")
        assertEquals(setOf("thread-2"), ThreadMarks.ids(account))
        assertEquals(listOf("thread-2"), savedIds())
        restart()
        assertEquals(setOf("thread-2"), ThreadMarks.ids(account))
        ThreadMarks.remove(account, "thread-2")
        assertEquals(emptyList<String>(), savedIds())
        restart()
        assertTrue(ThreadMarks.ids(account).isEmpty())
    }

    @Test fun clearingAMarkOfAnotherProfileOrAnUnknownChatChangesNothing() = runBlocking {
        ThreadMarks.add(account, "thread-1")
        val writes = store.writes
        ThreadMarks.remove(account.copy(profile = "coder"), "thread-1")
        ThreadMarks.remove(account, "never-marked")
        assertEquals(writes, store.writes)
        restart()
        assertEquals(setOf("thread-1"), ThreadMarks.ids(account))
    }

    @Test fun markingTheSameChatTwiceWritesOnce() = runBlocking {
        assertTrue(ThreadMarks.add(account, "thread-1"))
        val writes = store.writes
        assertFalse(ThreadMarks.add(account, "thread-1"))
        assertEquals(writes, store.writes)
    }

    @Test fun moreThanTheLimitKeepsTheNewestMarks() = runBlocking {
        ThreadMarks.add(account, *(0 until 520).map { "thread-$it" }.toTypedArray())
        val kept = ThreadMarks.ids(account)
        assertEquals(500, kept.size)
        assertTrue("thread-519" in kept && "thread-20" in kept)
        assertFalse("thread-0" in kept || "thread-19" in kept)
        assertEquals(500, savedIds().size)
        restart()
        assertEquals(kept, ThreadMarks.ids(account))
    }

    @Test fun aStoreThatCannotBeReadStillListsTheMarksHeldInMemory() = runBlocking {
        store.failRead = true
        assertTrue(ThreadMarks.add(account, "thread-1"))
        assertEquals(setOf("thread-1"), ThreadMarks.ids(account))
    }

    @Test fun aStoreThatCannotBeWrittenStillListsTheMark() = runBlocking {
        store.failWrite = true
        assertTrue(ThreadMarks.add(account, "thread-1"))
        assertEquals(setOf("thread-1"), ThreadMarks.ids(account))
        assertTrue(store.saved.isEmpty())
    }

    @Test fun aSavedListThatIsNotAListIsIgnoredNotFatal() = runBlocking {
        store.saved[account.key] = "not json"
        assertTrue(ThreadMarks.ids(account).isEmpty())
        assertTrue(ThreadMarks.add(account, "thread-1"))
        assertEquals(listOf("thread-1"), savedIds())
    }

    @Test fun aMarkAddedWhileTheSavedOnesWereBeingReadIsKept() = runBlocking {
        store.saved[account.key] = JSONArray().put("saved-1").toString()
        store.onFirstRead = { ThreadMarks.add(account, "added-meanwhile") }
        assertEquals(setOf("saved-1", "added-meanwhile"), ThreadMarks.ids(account))
        restart()
        assertEquals(setOf("saved-1", "added-meanwhile"), ThreadMarks.ids(account))
    }
}
