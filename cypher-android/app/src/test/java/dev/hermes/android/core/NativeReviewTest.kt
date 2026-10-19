package dev.hermes.android.core

import androidx.compose.ui.graphics.Color
import dev.hermes.android.Message
import dev.hermes.android.data.externalMediaRequest
import dev.hermes.android.ui.*
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.scilab.forge.jlatexmath.CypherMathDefinitions

class NativeReviewTest {
    private val scope = Scope("https://hermes.example", "owner-a", "default", "session-a")

    @Test fun xaraAdmissionNeverAcceptsAnOldChatDuringResolutionFailureOrWrongProfile() {
        val owner = scope.copy(session = "")
        val draft = Draft("Keep my ordinary conversation draft", 4, 8, queued = listOf(QueuedMessage("later")))
        assertFalse(canonicalConversationReady(owner, scope, scope.session, true, "xara-session"))
        val xara = scope.session("xara-session")
        assertFalse(canonicalConversationReady(owner, xara, xara.session, true, xara.session, opening = true))
        assertFalse(canonicalConversationReady(owner, xara, xara.session, true, xara.session, resolutionError = true))
        assertFalse(canonicalConversationReady(owner.copy(profile = "research"), xara.copy(profile = "research"), xara.session, true, xara.session))
        // The assistant tab may be bound to another profile in Settings › Assistant.
        assertTrue(canonicalConversationReady(owner.copy(profile = "research"), xara.copy(profile = "research"), xara.session, true, xara.session, assistantProfile = "research"))
        assertFalse(canonicalConversationReady(owner, xara, xara.session, true, xara.session, assistantProfile = "research"))
        assertFalse(canonicalConversationReady(null, null, xara.session, true, xara.session))
        assertFalse(canonicalConversationReady(owner, xara, scope.session, true, xara.session))
        assertTrue(canonicalConversationReady(owner, xara, xara.session, true, xara.session))
        assertTrue(canonicalConversationReady(owner, scope, scope.session, false, null))
        assertEquals(draft, Draft.decode(draft.encode()))
    }

    @Test fun editorCheckpointSettlementCannotPublishAcrossSessionAccountOrClientReplacement() {
        val client = Any()
        val token = ScopeSettlement(scope, client, 3, 4)
        assertTrue(token.current(scope, client, 3, 4))
        assertFalse(token.current(scope.session("b"), client, 3, 5))
        assertFalse(token.current(scope.copy(profile = "research"), client, 3, 4))
        assertFalse(token.current(scope, Any(), 3, 4))
        assertFalse(token.current(scope, client, 4, 4))
        assertFalse(token.current(null, null, 4, 5))
    }

    @Test fun aggregateMathBudgetRejectsAllocationBeforeTheParagraphExceedsItsPixelCap() {
        val budget = MathBitmapBudget()
        assertTrue(budget.reserve(1024, 1024))
        assertTrue(budget.reserve(2048, 1024))
        assertEquals(3L * 1024 * 1024, budget.pixels)
        assertFalse(budget.reserve(1025, 1024))
        assertFalse(budget.reserve(4000, 1900))
        assertFalse(budget.reserve(Int.MAX_VALUE, Int.MAX_VALUE))
        assertEquals(3L * 1024 * 1024, budget.pixels)
        assertTrue(budget.reserve(1024, 1024))
        assertFalse(budget.reserve(1, 1))
        assertEquals(MathBitmapBudget.MAX_PIXELS, budget.pixels)
        assertEquals(16L * 1024 * 1024, budget.pixels * 4)
    }

    @Test fun preparedNativeContentRejectsStaleSourceScopeAndNetworkClientBeforePublication() {
        data class Client(val origin: String)
        val client = Client(scope.origin)
        val source = "diagram source" to 0
        val prepared = PreparedContent(scope.key, source, "current bitmap and controls", client)
        assertEquals("current bitmap and controls", prepared.forContext(scope.key, source, client))
        listOf(scope.copy(owner = "b"), scope.copy(profile = "research"), scope.copy(origin = "https://other.example"), scope.session("b")).forEach { assertNull(prepared.forContext(it.key, source, client)) }
        assertNull(prepared.forContext(scope.key, "new source" to 0, client))
        assertNull(prepared.forContext(scope.key, "diagram source" to 1, client))
        assertEquals(client, Client(scope.origin))
        assertNull(prepared.forContext(scope.key, source, Client(scope.origin)))
        assertNull(prepared.forContext(scope.key, source))
        val decoded = PreparedContent(scope.key, source, "local decode")
        assertEquals("local decode", decoded.forContext(scope.key, source))
        assertNull(decoded.forContext(scope.copy(owner = "b").key, source))
        assertNull(decoded.forContext(scope.key, source, client))
    }

    @Test fun streamingMarkdownRetainsOnlyTheSameConversationsGrowingPrefix() {
        val parsed = PreparedContent(scope.key, "Hello", "rendered prefix")
        assertEquals("rendered prefix", parsed.forGrowingText(scope.key, "Hello there"))
        assertNull(parsed.forGrowingText(scope.key, "Replaced text"))
        assertNull(parsed.forGrowingText(scope.key, "Hell"))
        assertNull(parsed.forGrowingText(scope.copy(owner = "other").key, "Hello there"))
        assertNull(parsed.forGrowingText(scope.session("other").key, "Hello there"))
    }

    @Test fun scopedMathDefinitionsRestorePrivateMacrosColorsAndSizingEvenOnFailure() {
        val macros = mutableMapOf("builtin" to "original")
        val replacements = mutableMapOf("optional" to "default")
        val commands = mutableMapOf("builtin" to 1)
        val colors = mutableMapOf("black" to "black")
        val sizing = mutableMapOf("scriptfactor" to .7)
        val registries = listOf(macros, replacements, commands, colors, sizing)
        val original = registries.map { it.toMap() }
        fun definePrivate() { macros["private"] = "owner-a-secret"; macros["builtin"] = "overwritten"; replacements.clear(); commands["private"] = 2; colors["private"] = "red"; colors["black"] = "changed"; sizing["scriptfactor"] = 100.0 }
        assertEquals("owner-a-secret", CypherMathDefinitions.restoreRegistries(registries) { definePrivate(); macros["private"] })
        assertEquals(original, registries)
        val result = runCatching { CypherMathDefinitions.restoreRegistries(registries) { definePrivate(); error("Formula layout failed after definitions") } }
        assertTrue(result.isFailure)
        assertEquals(original, registries)
        assertFalse(macros.containsKey("private")); assertFalse(colors.containsKey("private"))
    }

    @Test fun mathDefinitionTransactionsSerializeParallelFormulaWork() {
        val registry = mutableMapOf("builtin" to "original")
        val firstEntered = CountDownLatch(1)
        val secondAttempt = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<String> { CypherMathDefinitions.restoreRegistries(listOf(registry)) { registry["private"] = "first-owner"; firstEntered.countDown(); check(release.await(2, TimeUnit.SECONDS)); registry.getValue("private") } }
            assertTrue(firstEntered.await(2, TimeUnit.SECONDS))
            val second = executor.submit<Boolean> { secondAttempt.countDown(); CypherMathDefinitions.restoreRegistries(listOf(registry)) { secondEntered.countDown(); !registry.containsKey("private") } }
            assertTrue(secondAttempt.await(2, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
            release.countDown()
            assertEquals("first-owner", first.get(2, TimeUnit.SECONDS))
            assertTrue(second.get(2, TimeUnit.SECONDS))
            assertEquals(mapOf("builtin" to "original"), registry)
        } finally { release.countDown(); executor.shutdownNow() }
    }

    @Test fun nativeMacroBudgetPreservesOptionalLiteralSubstitutionAndStopsRecursiveExpandingWork() {
        val budget = CypherMathDefinitions.MacroBudget()
        val args = arrayOfNulls<String>(12).apply { this[0] = "native"; this[1] = "literal \$ and \\ path" }
        assertEquals("[default]:literal \$ and \\ path", CypherMathDefinitions.substituteMacro(budget, "[#1]:#2", "default", args))
        args[2] = "explicit"
        assertEquals("[explicit]:literal \$ and \\ path", CypherMathDefinitions.substituteMacro(budget, "[#1]:#2", "default", args))
        fun rejects(block: () -> Unit) { assertTrue(runCatching(block).exceptionOrNull() is IllegalArgumentException) }
        val recursive = CypherMathDefinitions.MacroBudget()
        val noArgs = arrayOfNulls<String>(11).apply { this[0] = "loop" }
        repeat(CypherMathDefinitions.MacroBudget.MAX_EXPANSIONS) { assertEquals("\\loop", CypherMathDefinitions.substituteMacro(recursive, "\\loop", null, noArgs)) }
        rejects { CypherMathDefinitions.substituteMacro(recursive, "\\loop", null, noArgs) }
        val expanding = CypherMathDefinitions.MacroBudget()
        rejects { expanding.replace("#1#1", "#1", "x".repeat(CypherMathDefinitions.MacroBudget.MAX_TEXT)) }
        val cumulative = CypherMathDefinitions.MacroBudget()
        repeat(CypherMathDefinitions.MacroBudget.MAX_REPLACEMENT_TEXT / CypherMathDefinitions.MacroBudget.MAX_TEXT) { assertEquals(CypherMathDefinitions.MacroBudget.MAX_TEXT, cumulative.replace("#1", "#1", "x".repeat(CypherMathDefinitions.MacroBudget.MAX_TEXT)).length) }
        rejects { cumulative.replace("#1", "#1", "x") }
        val definitions = CypherMathDefinitions.MacroBudget()
        repeat(CypherMathDefinitions.MacroBudget.MAX_DEFINITIONS) { definitions.definition("finite") }
        rejects { definitions.definition("one too many") }
        rejects { CypherMathDefinitions.MacroBudget().definition("x".repeat(4097)) }
        val lookups = CypherMathDefinitions.MacroBudget()
        repeat(CypherMathDefinitions.MacroBudget.MAX_LOOKUPS) { lookups.lookup() }
        rejects { lookups.lookup() }
    }

    @Test fun allNativeSkinInkMeetsContrastOnActualContainersSelectionsAndGlassHighlights() {
        val file = requireNotNull(listOf(File("src/main/assets/native-skins.json"), File("app/src/main/assets/native-skins.json"), File("cypher-android/app/src/main/assets/native-skins.json")).firstOrNull { it.isFile }) { "Run this palette check from the Cypher app, Android project or repository root." }
        val palettes = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(21, NativeSkins.size)
        NativeSkins.forEach { skin -> listOf(false, true).forEach { dark ->
            val scheme = nativeSkinColorScheme(palettes.getJSONObject(skin).getJSONObject(if (dark) "dark" else "light"), dark)
            val backgrounds = nativeRoleBackgrounds(scheme)
            assertTrue(backgrounds.contains(scheme.secondaryContainer))
            assertTrue(backgrounds.size >= 50)
            listOf(scheme.onSurface, scheme.onSurfaceVariant, scheme.primary, scheme.error).forEach { ink -> backgrounds.forEach { background -> assertTrue("$skin dark=$dark ink=$ink background=$background ratio=${nativeContrast(ink, background)}", nativeContrast(ink, background) >= 4.5f) } }
            assertTrue(nativeContrast(scheme.onPrimary, scheme.primary) >= 4.5f)
            assertTrue(nativeContrast(scheme.onError, scheme.error) >= 4.5f)
        } }
    }

    @Test fun messageMediaReferencesRetainExactSnapshotsAndCanonicalOriginatingSession() {
        val digest = "a".repeat(64)
        val message = Message.from(json("role" to "assistant", "content" to "MEDIA:C:/work/chart.png", "_media_snapshots" to json("C:/work/chart.png" to digest, "C:/work/chart.png.bak" to "b".repeat(64), "bad" to "not-a-digest")), 0, scope.session)
        assertEquals(scope.session, message.sessionId)
        assertEquals(2, message.mediaSnapshots.size)
        val a = resolveNativeReference("/api/media?path=C%3A%2Fwork%2Fchart.png&session_id=forged", scope, message.mediaSnapshots) as NativeReference.LocalMedia
        assertEquals(scope.session, a.sessionId); assertEquals(digest, a.query()["snap"])
        assertEquals("C:/work/chart.png", a.path)
        assertEquals(resolveNativeReference("MEDIA:C:/work/chart.png", scope, message.mediaSnapshots), resolveNativeReference("media:C:/work/chart.png", scope, message.mediaSnapshots))
        val noPrefix = resolveNativeReference("MEDIA:C:/work/chart.png.extra", scope, message.mediaSnapshots) as NativeReference.LocalMedia
        assertNull(noPrefix.snapshot)
        assertEquals(digest, (resolveNativeReference("https://hermes.example/api/media?path=C%3A%2Fwork%2Fchart.png", scope, message.mediaSnapshots) as NativeReference.LocalMedia).snapshot)
        assertEquals(scope.session, (resolveNativeReference("http://localhost:8080/api/media?path=C:/work/chart.png&session_id=forged", scope) as NativeReference.LocalMedia).sessionId)
        assertEquals("C:/work/chart.png", (resolveNativeReference("./api/media?path=C:/work/chart.png", scope) as NativeReference.LocalMedia).path)
    }

    @Test fun finiteMediaResolverSupportsWindowsFilesDataImagesAndInternalRoutesWithoutCredentials() {
        listOf("file:///C:/Users/A/chart.png", "file://C:/Users/A/chart.png", "file://localhost/C:/Users/A/chart.png").forEach { assertEquals("C:/Users/A/chart.png", (resolveNativeReference(it, scope) as NativeReference.LocalMedia).path) }
        assertEquals("C:/Users/A/my chart.png", (resolveNativeReference("file:///C:/Users/A/my%20chart.png", scope) as NativeReference.LocalMedia).path)
        assertEquals("C:/Users/A/chart.png", (resolveNativeReference("file://C:/Users/A/chart.png?download=1#preview", scope) as NativeReference.LocalMedia).path)
        assertEquals("C:/Users/A/chart#1.png", (resolveNativeReference("file://C:/Users/A/chart%231.png", scope) as NativeReference.LocalMedia).path)
        assertTrue(resolveNativeReference(scope.origin + "/#workspace=notes.md", scope.copy(session = ""), media = false) is NativeReference.Unsupported)
        assertEquals("session-b", (resolveNativeReference("session://session-b", scope, media = false) as NativeReference.Conversation).sessionId)
        assertEquals("session-b", (resolveNativeReference("/session/session-b", scope, media = false) as NativeReference.Conversation).sessionId)
        assertEquals(scope.session, (resolveNativeReference("workspace://docs/readme.md", scope, media = false) as NativeReference.Workspace).sessionId)
        val svg = resolveNativeReference("data:image/svg+xml,%3Csvg%20xmlns%3D%22http%3A%2F%2Fwww.w3.org%2F2000%2Fsvg%22%2F%3E", scope) as NativeReference.DataImage
        assertTrue(svg.bytes().toString(Charsets.UTF_8).startsWith("<svg"))
        val png = resolveNativeReference("data:image/png;base64,AQID", scope) as NativeReference.DataImage
        assertArrayEquals(byteArrayOf(1, 2, 3), png.bytes())
        assertTrue(resolveNativeReference("data:image/png;base64," + "A".repeat(DATA_IMAGE_LIMIT), scope) is NativeReference.Unsupported)
        listOf("javascript:alert(1)", "data:text/html,evil", "http://cdn.example/a.png", "https://user:password@cdn.example/a.png", "file://other-server/a.png", "/api/media?path=%00evil", "workspace://%00evil", "session://%00evil").forEach { assertTrue(it, resolveNativeReference(it, scope) is NativeReference.Unsupported) }
        val external = resolveNativeReference("https://cdn.example/image?token=public-reference", scope) as NativeReference.ExternalMedia
        val request = externalMediaRequest(external.url)
        assertEquals(setOf("Accept"), request.headers.names())
        assertNull(request.header("Cookie")); assertNull(request.header("Authorization")); assertNull(request.header("X-Hermes-CSRF-Token")); assertNull(request.header("Referer"))
        assertTrue(runCatching { externalMediaRequest("http://cdn.example/a.png".toHttpUrl()) }.isFailure)
        assertTrue(runCatching { externalMediaRequest("https://u:p@cdn.example/a.png".toHttpUrl()) }.isFailure)
    }

    @Test fun nativeMarkdownMediaPassPreservesLiteralCodeStyledLinksAndImageDestinations() {
        val document = parseNativeMarkdown("MEDIA:C:/work/chart.png\n\n`MEDIA:C:/secret/code.png file:///C:/code.png`\n\n```text\nMEDIA:C:/literal.png\nfile:///C:/literal.png\n```\n\nBare file:///C:/work/other.png and **[open](session://session-b)** ![alt](/api/media?path=C:/work/image.png).")
        val nodes = document.descendants().toList()
        assertEquals(listOf("C:/work/chart.png", "file:///C:/work/other.png"), nodes.filterIsInstance<NativeMediaNode>().map { it.reference })
        assertEquals("MEDIA:C:/secret/code.png file:///C:/code.png", nodes.filterIsInstance<Code>().single().literal)
        assertTrue(nodes.filterIsInstance<FencedCodeBlock>().single().literal.contains("MEDIA:C:/literal.png"))
        assertEquals("/api/media?path=C:/work/image.png", nodes.filterIsInstance<Image>().single().destination)
        assertEquals("session://session-b", nodes.filterIsInstance<Link>().single().destination)
        val clicked = mutableListOf<String>()
        val annotations = inlineAnnotations(document.children().last(), Color.Blue, onLink = { clicked += it })
        val link = annotations.getLinkAnnotations(0, annotations.length).single().item as androidx.compose.ui.text.LinkAnnotation.Clickable
        link.linkInteractionListener!!.onClick(link)
        assertEquals(listOf("session://session-b"), clicked)
    }
}
