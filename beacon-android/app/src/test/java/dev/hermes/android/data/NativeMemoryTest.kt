package dev.hermes.android.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeMemoryTest {
    @Test fun selectedProviderOnlyAndExactSaveRevision() {
        val status = JSONObject("""{"active":"hindsight","providers":[{"name":"byterover","available":false,"configured":true},{"name":"hindsight","available":true,"configured":true}]}""")
        val notes = JSONObject("""{"documents":[{"target":"user","content":"My preferences","revision":"original","enabled":true,"limit":1375}]}""")
        val state = NativeMemoryState.parse("owner:profile", status, notes)
        assertEquals("hindsight", state.activeLabel)
        assertTrue(state.providerReady)
        val body = state.documents.single().saveBody("New preferences")
        assertEquals("original", body.getString("revision"))
        assertEquals("user", body.getString("target"))
        assertEquals("New preferences", body.getString("content"))
    }
    @Test fun missingSelectedProviderIsNotAdvertisedAsReady() {
        val state = NativeMemoryState.parse("scope", JSONObject("""{"active":"hindsight","providers":[]}"""), JSONObject("""{"documents":[]}"""))
        assertFalse(state.providerReady)
        assertEquals("hindsight", state.activeLabel)
    }
}
