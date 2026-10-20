package dev.hermes.android.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugsinkCrashReporterTest {
    @Test fun dsnBecomesTheProjectEnvelopeUrl() {
        assertEquals("http://10.0.2.2:8000/api/1/envelope/", BugsinkTarget.parse("http://abc123@10.0.2.2:8000/1")?.url)
        assertNull(BugsinkTarget.parse(""))
        assertNull(BugsinkTarget.parse("http://10.0.2.2:8000/1"))
    }

    @Test fun crashEnvelopeCarriesTheCauseChainOutermostLast() {
        val error = IllegalStateException("outer", IllegalArgumentException("inner"))
        val lines = crashEnvelope(error, "main", "1.0.0-preview", 1_000L, "16", "sdk_gphone").split("\n")
        assertEquals(3, lines.size)
        assertEquals("event", JSONObject(lines[1]).getString("type"))
        val event = JSONObject(lines[2])
        assertEquals(JSONObject(lines[0]).getString("event_id"), event.getString("event_id"))
        val values = event.getJSONObject("exception").getJSONArray("values")
        assertEquals("java.lang.IllegalArgumentException", values.getJSONObject(0).getString("type"))
        assertEquals("outer", values.getJSONObject(1).getString("value"))
        val frames = values.getJSONObject(1).getJSONObject("stacktrace").getJSONArray("frames")
        assertTrue(frames.length() > 0)
        assertEquals("fatal", event.getString("level"))
    }
}
