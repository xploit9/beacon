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

    @Test fun supportedNativeHistoryAndProfileRoutesAreAllowed() {
        for (path in listOf(
            "/api/sessions",
            "/api/sessions/search",
            "/api/sessions/20261014_125947_0d8269",
            "/api/sessions/20261014_125947_0d8269/messages",
            "/api/sessions/20261014_125947_0d8269/timeline",
            "/api/sessions/20261014_125947_0d8269/latest-descendant",
            "/api/profiles",
            "/api/profiles/active",
            "/api/auth/me",
            "/api/auth/ws-ticket",
        )) assertTrue(path, NativeOperationPolicy.direct(path))

        for (path in listOf(
            "/api/sessions/x/delete",
            "/api/sessions/..",
            "/api/sessions/x/../messages",
            "/api/sessions/x/messages/../../auth/me",
            "/api/sessions/x/latest-descendant/extra",
            "/api/sessions/x/descendants",
            "/api/plugins/cypherx/mobile/bootstrap",
            "/api/mobile/bootstrap",
        )) assertFalse(path, NativeOperationPolicy.direct(path))

        val client = CypherClient(AuthContext("https://native.example", emptyList()))
        assertTrue(runCatching { client.url("/api/sessions/%2e%2e") }.isFailure)
        assertTrue(runCatching { client.url("/api/sessions/x%2F..%2Fauth") }.isFailure)
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
