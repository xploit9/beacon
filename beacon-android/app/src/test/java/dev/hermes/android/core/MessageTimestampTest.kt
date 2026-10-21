package dev.hermes.android.core

import dev.hermes.android.Message
import org.junit.Assert.*
import org.junit.Test

class MessageTimestampTest {
    @Test fun recordedNativeSecondsArePreservedAsMilliseconds() {
        val message = Message.from(json("role" to "assistant", "text" to "Recorded", "timestamp" to 1_700_000_000.125), 0)
        assertEquals(1_700_000_000_125L, message.recordedAt)
    }
    @Test fun recordedMillisecondsAndNumericStringsArePreserved() {
        for (value in listOf(1_700_000_000_125L, "1700000000125", "1700000000.125")) {
            assertEquals(1_700_000_000_125L, Message.from(json("timestamp" to value), 0).recordedAt)
        }
    }
    @Test fun recordedIsoTimeIsPreserved() {
        assertEquals(1_700_000_000_125L, Message.from(json("timestamp" to "2023-11-14T22:13:20.125Z"), 0).recordedAt)
    }
    @Test fun absentInvalidAndOverflowTimesNeverBecomeTheCurrentTime() {
        assertEquals(0L, Message.from(json("id" to "1700000000125", "created_at" to 1_700_000_000), 0).recordedAt)
        for (value in listOf("", "not a date", "NaN", "Infinity", "1e99", -1, 0, true, org.json.JSONObject.NULL)) {
            assertEquals("value=$value", 0L, Message.from(json("timestamp" to value), 0).recordedAt)
        }
    }
}
