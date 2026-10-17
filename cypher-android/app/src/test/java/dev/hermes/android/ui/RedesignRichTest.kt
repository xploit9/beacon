package dev.hermes.android.ui

import org.junit.Assert.*
import org.junit.Test
import org.commonmark.node.*

class RedesignRichTest {
    @org.junit.Test fun structuredSecretsStayHiddenWithoutHidingUsageCounts() {
        for (name in listOf("token", "Access-Token", "session_token", "botToken", "auth-token", "PASSWORD", "authorization", "apiKey", "client_secret", "cookie")) org.junit.Assert.assertTrue(name, privateStructuredKey(name))
        for (name in listOf("total_tokens", "input_tokens", "context_usage", "provider", "model")) org.junit.Assert.assertFalse(name, privateStructuredKey(name))
    }

    @Test fun checklistMappingPreservesCodeAndDuplicateIdentity() {
        val document=parseNativeMarkdown("- [ ] same\n- [x] same\n\n`[ ] code`\n\n- ordinary")
        val tasks=document.descendants().mapNotNull(::checklistItem).toList()
        assertEquals(2,tasks.size); assertFalse(tasks[0].checked); assertTrue(tasks[1].checked)
        assertNotEquals(tasks[0].key,tasks[1].key)
        assertEquals(tasks[0].key,parseNativeMarkdown("- [X] same").descendants().mapNotNull(::checklistItem).first().key)
    }
    @Test fun tableSortKeepsHeaderAndSortsNumbers() {
        val rows=listOf(listOf("Name","Value"),listOf("b","10"),listOf("a","2"))
        assertEquals("2",tableRowOrder(rows,1,false).map(rows::get)[1][1])
        assertEquals(rows,tableRowOrder(rows,1,true).map(rows::get))
        assertEquals("a",tableRowOrder(rows,0,false).map(rows::get)[1][0])
        assertEquals(listOf(0,2,3,1),tableRowOrder(listOf(listOf("h"),listOf("2"),listOf("10"),listOf("1x")),0,false))
        assertEquals("Name\tValue\nb\t10\na\t2",tableClipboard(rows))
    }
    @Test fun htmlHasNoExecutableOrExternalSurface() {
        val unsafe="""<base href='https://owner.invalid'><script>alert(1)</script><style>@import 'https://evil.invalid'</style><div onclick='fetch(1)' style='background:url(https://evil.invalid)'>safe<img src='https://evil.invalid'><iframe srcdoc='attack'></iframe><svg><foreignObject><script>evil</script></foreignObject></svg><a href='javascript:evil'>label</a><form><input value='secret'></form></div>"""
        val safe=safeRichHtml(unsafe)
        assertTrue(safe.contains("safe")); assertTrue(safe.contains("label"))
        listOf("script","onclick","style","href","https:","svg","iframe","input","secret","evil").forEach { assertFalse(it,safe.contains(it)) }
        assertTrue(richPage("graph TD; A-->B",true,false).contains("securityLevel:'strict'"))
        assertTrue(richPage("<p>hello</p>",false,false).contains("script-src 'none'"))
    }
    @Test fun oversizedHtmlFallsBackInsteadOfRendering() {
        assertThrows(IllegalArgumentException::class.java) { safeRichHtml("x".repeat(RICH_LIMIT+1)) }
        assertThrows(IllegalArgumentException::class.java) { richPage("x".repeat(RICH_LIMIT+1),true,false) }
    }
    @Test fun mathAndCalloutStillParse() {
        assertEquals(1,parseNativeMarkdown("Inline $"+"x^2$" ).descendants().filterIsInstance<MathNode>().count())
        assertEquals("WARNING",calloutLabel(parseNativeMarkdown("> [!WARNING]\n> Careful").firstChild as BlockQuote))
    }
}
