package dev.hermes.android.data

import org.junit.Assert.*
import org.junit.Test

class NativeOperationPolicyTest {
    @Test fun encodedToolsetNamesStillCannotEscapeTheHttpPathBoundary() {
        val client = CypherClient(AuthContext("https://native.example", emptyList()))
        assertEquals("/api/tools/toolsets/mcp.research", client.url("/api/tools/toolsets/mcp.research").encodedPath)
        for (path in listOf("/api/tools/toolsets/%2e%2e", "/api/tools/toolsets/x%2F..%2Fauth", "/api/tools/toolsets/x%3Fsecret=1", "/api/tools/toolsets/%00")) {
            assertTrue(path, runCatching { client.url(path) }.isFailure)
        }
    }
    @Test fun oldRoutesCannotEscapeAdapter() {
        assertFalse(NativeOperationPolicy.direct("/api/chat/start"))
        assertFalse(NativeOperationPolicy.direct("/api/mobile/bootstrap"))
        assertFalse(NativeOperationPolicy.direct("/api/transcribe"))
        assertFalse(NativeOperationPolicy.direct("//foreign/api/model/info"))
        assertTrue(NativeOperationPolicy.direct("/api/model/info"))
        assertTrue(NativeOperationPolicy.direct("/api/tools/toolsets/mcp.research"))
        assertTrue(NativeOperationPolicy.direct("/api/plugins/cypher/mobile/bootstrap"))
    }
}
