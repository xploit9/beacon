package dev.hermes.android.core

import dev.hermes.android.data.AuthContext
import dev.hermes.android.data.CypherClient
import dev.hermes.android.data.ScopedCookies
import dev.hermes.android.data.cachedScopeFor
import dev.hermes.android.data.requireCachedIdentity
import dev.hermes.android.data.CachedIdentityMismatch
import dev.hermes.android.data.authorizesPushSource
import dev.hermes.android.push.validOpaque
import dev.hermes.android.ui.*
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.StrongEmphasis

class ProtocolTest {
    private val scope = Scope("https://hermes.example", "owner-a", "default", "session-a")
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
    @Test fun identitiesIncludeEveryBoundaryAndCanonicalConversation() {
        val identities = listOf(scope, scope.copy(origin = "https://other.example"), scope.copy(owner = "owner-b"), scope.copy(profile = "research"), scope.session("session-b"))
        assertEquals(5, identities.map { it.key }.toSet().size)
        assertTrue(scope.key.matches(Regex("[0-9a-f]{64}")))
        assertFalse(scope.key.contains(scope.owner))
    }
    @Test fun onlyPrivateHttpsOriginsAreAcceptedAndRequestsStayOnOrigin() {
        assertEquals("https://hermes.example", trustedOrigin(" https://hermes.example/ "))
        listOf("http://hermes.example", "https://user:password@hermes.example", "https://hermes.example/api", "https://hermes.example?auth=secret", "https://hermes.example#x").forEach { value -> rejects { trustedOrigin(value) } }
        val client = CypherClient(AuthContext(scope.origin, emptyList()))
        assertEquals("hermes.example", client.url("/api/session", mapOf("session_id" to "value&other=1")).host)
        assertEquals("value&other=1", client.url("/api/session", mapOf("session_id" to "value&other=1")).queryParameter("session_id"))
        listOf("//evil.example/api", "https://evil.example", "/api\\escape").forEach { rejects { client.url(it) } }
        rejects { client.request("/api/settings", method = "POST") }
    }
    @Test fun signedProfileCookiesNeverCrossOriginsOrMutateDetachedCallSnapshot() {
        val origin = scope.origin.toHttpUrl()
        val authCookie = Cookie.parse(origin, "hermes_auth=opaque; Secure; HttpOnly; Path=/")!!
        val default = Cookie.parse(origin, "hermes_profile=server-signed-default; Secure; HttpOnly; Path=/")!!
        val jar = ScopedCookies(origin, listOf(authCookie.toString(), default.toString()))
        val call = ScopedCookies(origin, jar.snapshot())
        jar.saveFromResponse(origin.resolve("/api/profile/switch")!!, listOf(Cookie.parse(origin, "hermes_profile=server-signed-research; Secure; Path=/")!!))
        assertEquals("server-signed-default", call.loadForRequest(origin).first { it.name == "hermes_profile" }.value)
        assertEquals("server-signed-research", jar.loadForRequest(origin).first { it.name == "hermes_profile" }.value)
        assertTrue(jar.loadForRequest("https://evil.example/api".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("https://hermes.example:444/api".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://hermes.example/api".toHttpUrl()).isEmpty())
    }
    @Test fun offlineCachedIdentityRequiresTrustedSavedScopeAndRejectsAccountReplacement() {
        val now = 1_800_000_000_000L
        val cookie = Cookie.Builder().name("__Host-hermes_session_at").value("opaque").hostOnlyDomain("hermes.example").path("/").secure().httpOnly().expiresAt(now + 60_000).build()
        val auth = AuthContext(scope.origin, listOf(cookie.toString()), scope.owner, scope.profile, backend = "hermes-dashboard")
        val ownerScope = scope.copy(session = "")
        assertEquals(ownerScope, auth.cachedScopeFor(scope.origin, false, now))
        assertNull(auth.cachedScopeFor(scope.origin, true, now))
        assertNull(auth.cachedScopeFor("https://another.example", false, now))
        assertNull(auth.copy(owner = "").cachedScopeFor(scope.origin, false, now))
        assertNull(auth.copy(profile = "").cachedScopeFor(scope.origin, false, now))
        assertNull(auth.copy(backend = "").cachedScopeFor(scope.origin, false, now))
        assertNull(auth.copy(cookies = emptyList()).cachedScopeFor(scope.origin, false, now))
        assertNull(auth.cachedScopeFor(scope.origin, false, now + 60_001))
        assertNull(auth.copy(cookies = listOf("session=opaque; Path=/")).cachedScopeFor(scope.origin, false, now))
        auth.requireCachedIdentity(ownerScope)
        listOf(auth.copy(owner = "another-owner"), auth.copy(profile = "research"), auth.copy(origin = "https://another.example")).forEach { replacement ->
            assertThrows(CachedIdentityMismatch::class.java) { replacement.requireCachedIdentity(ownerScope) }
        }
    }
    @Test fun revokedLocalOwnerCannotRevivePushAndSameOwnerProfilesRemainAuthorized() {
        val source = scope.copy(session = "")
        val signedIn = AuthContext(scope.origin, listOf("server-signed-cookie"), scope.owner, "research", backend = "hermes-dashboard")
        assertTrue(signedIn.authorizesPushSource(source)) // The captured default-profile request stays owned.
        assertTrue(signedIn.authorizesPushSource(source.copy(profile = "research")))
        assertFalse(AuthContext(scope.origin, emptyList()).authorizesPushSource(source)) // Actual AuthStore.clear/read shape.
        assertFalse(signedIn.copy(owner = "new-account").authorizesPushSource(source))
        assertFalse(signedIn.copy(origin = "https://another.example").authorizesPushSource(source))
        assertFalse(signedIn.copy(owner = "").authorizesPushSource(source))
        assertFalse(signedIn.copy(backend = "").authorizesPushSource(source))
    }
    @Test fun dollarAmountsStayTextWhileRealInlineMathStillRenders() {
        val reply = "I'd pay off the **\$600 card balance**, as long as the remaining **\$1,400** covers emergencies. " +
            "That costs about **\$12 a month** in interest (roughly **\$144 a year**). Energy is \$E=mc^2\$."
        val math = nativeMarkdownParser.parse(reply).descendants().filterIsInstance<MathNode>()
        assertEquals(listOf("E=mc^2"), math.map { it.expression }.toList())
    }
    @Test fun nativeMathExtensionPreservesDelimitersStyledLinksAndLiteralCode() {
        val source = "**bold** \$x_i\$ and *soft* [linked \\(y^2\\)](https://hermes.example/docs) plus `\$code\$ \\(literal\\)` and \$\$z^2\$\$.\n\n\\[\\frac{a}{b}\\]\n\n```text\n\$fenced\$ \\(literal\\)\n```"
        val document = nativeMarkdownParser.parse(source)
        val nodes = document.descendants().toList()
        val math = nodes.filterIsInstance<MathNode>()
        assertEquals(listOf("x_i", "y^2", "z^2", "\\frac{a}{b}"), math.map { it.expression })
        assertEquals(listOf("\$x_i\$", "\\(y^2\\)", "\$\$z^2\$\$", "\\[\\frac{a}{b}\\]"), math.map { it.source })
        assertEquals(listOf(false, false, true, true), math.map { it.display })
        assertEquals("bold", nodes.filterIsInstance<StrongEmphasis>().single().plain())
        assertEquals("soft", nodes.filterIsInstance<Emphasis>().single().plain())
        val link = nodes.filterIsInstance<Link>().single()
        assertEquals("https://hermes.example/docs", link.destination)
        assertEquals("y^2", link.descendants().filterIsInstance<MathNode>().single().expression)
        assertEquals("\$code\$ \\(literal\\)", nodes.filterIsInstance<Code>().single().literal)
        assertEquals("\$fenced\$ \\(literal\\)\n", nodes.filterIsInstance<FencedCodeBlock>().single().literal)
        val paragraph = document.children().first()
        val annotated = inlineAnnotations(paragraph, Color.Blue)
        assertTrue(annotated.text.contains("\$x_i\$")); assertTrue(annotated.text.contains("\\(y^2\\)"))
        assertTrue(annotated.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold && annotated.text.substring(it.start, it.end) == "bold" })
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace && annotated.text.substring(it.start, it.end) == "\$code\$ \\(literal\\)" })
        assertEquals("https://hermes.example/docs", (annotated.getLinkAnnotations(0, annotated.length).single().item as LinkAnnotation.Url).url)
        val withBitmap = inlineAnnotations(paragraph, Color.Blue, mapOf(math.first() to "math:0"))
        assertTrue(withBitmap.getStringAnnotations(0, withBitmap.length).any { it.item == "math:0" })
        assertEquals(1, withBitmap.getLinkAnnotations(0, withBitmap.length).size)
    }
    @Test fun doneLeavesStreamOpenForTitleAndStateUntilExplicitTerminal() {
        val events = listOf(SseEvent("done", "{\"session\":{}}", "stream:1"), SseEvent("title", "{\"title\":\"Final title\"}", "stream:2"), SseEvent("stream_end", "{}", "stream:3"))
        assertEquals(listOf("done", "title", "stream_end"), events.map { it.type })
        assertFalse(events[0].terminal)
        assertFalse(events[1].terminal)
        assertTrue(events[2].terminal)
    }
    @Test fun replayDiscardDuplicatesAndOldSequenceIncludingFirstReplayAfterRestart() {
        val cursor = ReplayCursor("run", "run:10")
        assertFalse(cursor.accept(SseEvent("token", "x", "run:9")))
        assertFalse(cursor.accept(SseEvent("token", "x", "run:10")))
        assertTrue(cursor.accept(SseEvent("token", "y", "run:11")))
        assertFalse(cursor.accept(SseEvent("token", "y", "run:11")))
        assertTrue(cursor.accept(SseEvent("title", "{}", "opaque-event")))
        assertFalse(cursor.accept(SseEvent("title", "{}", "opaque-event")))
        assertEquals("opaque-event", cursor.lastId)
        assertFalse(ReplayCursor("run", "opaque-checkpoint").accept(SseEvent("title", "{}", "opaque-checkpoint")))
    }
    @Test fun draftCheckpointRoundTripKeepsCursorAttachmentsQueueAndReadingPosition() {
        val draft = Draft("A composed thought", 2, 7, listOf(json("path" to "upload/a.txt", "name" to "a.txt")), listOf(QueuedMessage("next", listOf(json("path" to "upload/queued.pdf")), "model-a", "provider-a"), QueuedMessage("later")), 48, 37, "message-48")
        val restored = Draft.decode(draft.encode())
        assertEquals(draft.text, restored.text); assertEquals(2, restored.selectionStart); assertEquals(7, restored.selectionEnd)
        assertEquals("upload/a.txt", restored.attachments.single().string("path")); assertEquals("upload/queued.pdf", restored.queued.first().attachments.single().string("path")); assertEquals("model-a", restored.queued.first().model); assertEquals("provider-a", restored.queued.first().provider); assertEquals("later", restored.queued.last().text); assertEquals(48, restored.anchor)
        assertEquals(3, Draft.decode("{\"text\":\"abc\",\"start\":999}").selectionStart)
        assertEquals("legacy", Draft.decode("{\"queue\":[\"legacy\"]}").queued.single().text)
        assertEquals(37, restored.anchorOffset); assertEquals("message-48", restored.anchorId)
        assertEquals(0, Draft.decode("{\"text\":\"legacy\"}").anchorOffset)
    }
    @Test fun acceptingAnOldSendCannotEraseANewerDraftOrQueue() {
        val sent = Draft("sent", attachments = listOf(json("path" to "a")))
        val newer = sent.copy(text = "sent plus new thought", selectionStart = 8, queued = listOf(QueuedMessage("queued")))
        assertEquals(newer, newer.afterAccepted(sent))
        val current = sent.copy(queued = listOf(QueuedMessage("queued")), anchor = 10)
        val cleared = current.afterAccepted(sent)
        assertEquals("", cleared.text); assertTrue(cleared.attachments.isEmpty()); assertEquals(listOf(QueuedMessage("queued")), cleared.queued); assertEquals(10, cleared.anchor)
    }
    @Test fun uncertainReceiptsMustResolveCanonicalSourceBeforeAccepted() {
        assertEquals(SendReceipt.Pending, SendReceipt.parse(json("state" to "pending"), scope))
        assertEquals(SendReceipt.Failed, SendReceipt.parse(json("state" to "failed"), scope))
        assertEquals(SendReceipt.Accepted("session-a", "run-a"), SendReceipt.parse(json("state" to "accepted", "session_id" to "session-a", "stream_id" to "run-a"), scope))
        rejects { SendReceipt.parse(json("state" to "accepted", "session_id" to "session-b", "stream_id" to "run-a"), scope) }
        rejects { SendReceipt.parse(json("state" to "accepted", "session_id" to "session-a", "stream_id" to ""), scope) }
        assertNotEquals(PendingSend.create(scope, Draft("same")).id, PendingSend.create(scope, Draft("same")).id)
    }






    @Test fun opaquePushRefsCannotCarryAnOriginProfileOrJavascriptRoute() {
        assertTrue(validOpaque("opaque-owner-profile-ref_12")); assertFalse(validOpaque("https://evil.example/profile")); assertFalse(validOpaque("../other")); assertFalse(validOpaque("javascript:alert(1)")); assertFalse(validOpaque("a".repeat(201)))
    }
    @Test fun tamperedUpdateManifestAndArtifactTraversalAreRejected() {
        val sha = "a".repeat(64)
        val release = Release(22, "0.4.1", "dev.hermes.android", "/api/plugins/cypher/mobile/releases/$sha/download", sha, 1000, sha, 2, 2)
        release.validate("dev.hermes.android", 1, sha)
        rejects { release.copy(packageName = "evil.app").validate("dev.hermes.android", 1, sha) }
        rejects { release.copy(signer = "b".repeat(64)).validate("dev.hermes.android", 1, sha) }
        rejects { release.copy(sha = "incomplete").validate("dev.hermes.android", 1, sha) }
        rejects { release.copy(minContract = 3).validate("dev.hermes.android", 1, sha) }
        rejects { release.copy(minContract = 1, maxContract = 1).validate("dev.hermes.android", 1, sha) }
        rejects { release.copy(version = 1).validate("dev.hermes.android", 1, sha) }
        // Stable and production feeds share one query-free path; the app adds ?package= itself.
        val stable = Release.from(json("version_code" to 100, "version_name" to "1.0.0", "package_name" to "dev.hermes.android.stable", "artifact_path" to "/api/plugins/cypher/mobile/releases/$sha/download", "sha256" to sha, "size_bytes" to 1000, "certificate_sha256" to sha, "min_contract_version" to 2, "max_contract_version" to 2))
        assertEquals(stable.path, safeArtifactPath(stable.path))
        listOf("https://evil.example/a.apk", "//evil.example/a.apk", "/api/../secret", "/api/%2e%2e/secret", "/api/%252e%252e/secret", "/api/a%5cb", "/api/a?token=secret", "/api/a%0d%0aevil").forEach { rejects { safeArtifactPath(it) } }
    }
    @Test fun nativeConfigurationSerializesTypedNestedFieldsAndPreservesMaskedSecrets() {
        val form = ActionForm("MCP", "/api/mcp/servers/a", listOf(Field("env", type = FieldType.MAP), Field("args", type = FieldType.LIST), Field("enabled", type = FieldType.BOOLEAN), Field("timeout", type = FieldType.INTEGER), Field("advanced.temperature", type = FieldType.DECIMAL), Field("api_key", type = FieldType.SECRET)), json("api_key" to "retained"), values = mapOf("env" to "TOKEN=••••••\nURL=https://server.example", "args" to "--one\n--two", "enabled" to "true", "timeout" to "60", "advanced.temperature" to "0.5", "api_key" to ""))
        val body = formBody(form)
        assertEquals("••••••", body.getJSONObject("env").getString("TOKEN")); assertEquals(2, body.getJSONArray("args").length()); assertTrue(body.getBoolean("enabled")); assertEquals(60, body.getInt("timeout")); assertEquals(0.5, body.getJSONObject("advanced").getDouble("temperature"), 0.0); assertEquals("retained", body.getString("api_key"))
        rejects { formBody(form.copy(values = form.values + ("env" to "no-separator"))) }
    }


}
