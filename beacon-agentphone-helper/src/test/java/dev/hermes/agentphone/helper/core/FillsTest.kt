package dev.hermes.agentphone.helper.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fill contexts, arms with items, and releases (contract revision 2, §6.1-6.2). */
class FillsTest {
    private var clock = 1_800_000_000_000L
    private var ids = 0
    private val fills = Fills({ clock }, { "req_" + (++ids).toString().padStart(16, '0') })
    private val shop = "dev.beacon.fixture.shop"

    private fun field(id: String, role: String, focused: Boolean = false, domain: String? = null, max: Int = -1) =
        FormField(id, role, focused, domain, if (domain == null) null else "https", max)

    private fun item(id: String, roles: List<String> = listOf("username", "password")) = Item("item_" + id.padEnd(15, id[0]), "Kanto $id", roles)

    private fun arm(kind: String = "login", items: List<Item> = listOf(item("D")), web: String? = null, pkg: String = shop) =
        Arm("arm_AAAAAAAAAAAAAAAA", kind, pkg, web, items, clock + 20_000)

    private fun ctx(pkg: String, fields: List<FormField>, source: String = "play") = fills.context(pkg, emptyList(), source, fields, null, null)

    private val login = listOf(field("1", "username"), field("2", "password", true))

    @Test
    fun theContextIsTheVectorsShape() {
        val context = fills.context(shop, listOf("697a4142" + "0".repeat(56)), "play", login.map { FormField(if (it.id == "1") "af-1" else "af-2", it.role, it.focused, null, null) }, null, null)
        @Suppress("UNCHECKED_CAST")
        val want = (Json.parse((Fixtures.sealed[4]["plaintext_text"] as String)) as Map<String, Any?>)["context"]
        assertEquals(String(Json.encode(want)), String(Json.encode(context.map)))
        assertSame(context.map, fills.lastContext)
        assertTrue(Messages.context(context.map))
    }

    @Test
    fun certificatesAreSortedAndADatasetNeverSpansTwoSites() {
        val all = listOf(
            field("1", "username", domain = "kanto.example"),
            field("2", "card_number", domain = "pay.example"),
            field("3", "cvc", focused = true, domain = "pay.example"),
        )
        val c = fills.context("com.android.chrome", listOf("bb".repeat(32), "aa".repeat(32)), "system", all, "kanto.example", "https")
        assertEquals(listOf("aa".repeat(32), "bb".repeat(32)), c.map["certs"])
        assertEquals("pay.example", c.map["web_domain"])
        assertEquals("system", c.map["source"])
        assertEquals(listOf("2", "3"), c.fields.map { it.id })
        val none = fills.context("com.android.chrome", emptyList(), "system", emptyList(), "kanto.example", "https", record = false)
        assertEquals("kanto.example", none.map["web_domain"])
        assertSame(c.map, fills.lastContext) // a save's context is not the latest fill context
    }

    @Test
    fun anArmMatchesOnlyItsPackageDomainAndTime() {
        fills.arm(arm())
        assertTrue(fills.offer("other.app", ctx("other.app", login)).isEmpty())
        val offers = fills.offer(shop, ctx(shop, login))
        assertEquals(1, offers.size)
        assertEquals(listOf("username", "password"), offers[0].roles)
        clock += 20_001
        assertTrue(fills.offer(shop, ctx(shop, login)).isEmpty())
        fills.arm(arm(web = "kanto.example", pkg = "com.android.chrome"))
        assertTrue(fills.offer("com.android.chrome", ctx("com.android.chrome", listOf(field("1", "password", true, "evil.example")))).isEmpty())
        assertEquals(1, fills.offer("com.android.chrome", ctx("com.android.chrome", listOf(field("1", "password", true, "kanto.example")))).size)
    }

    @Test
    fun oneEntryPerItemThatFitsTheScreenInTheArmsOrder() {
        val items = listOf(item("D"), item("E"), item("F", listOf("otp")), item("G", listOf("password")))
        fills.arm(arm(items = items))
        val offers = fills.offer(shop, ctx(shop, login))
        assertEquals(listOf(items[0], items[1], items[3]), offers.map { it.item }) // F has no field here: no entry
        assertEquals(listOf("2"), offers[2].fields.map { it.id })
        assertEquals(listOf("password"), offers[2].roles)
        assertEquals(3, offers.map { it.req }.toSet().size)
    }

    @Test
    fun pickingOneEntrySpendsTheWholeArm() {
        fills.arm(arm(items = listOf(item("D"), item("E"))))
        val first = fills.offer(shop, ctx(shop, login))
        val second = fills.offer(shop, ctx(shop, login))
        val taken = fills.take(first[1].req)
        assertTrue(taken is Taken.Ready)
        assertEquals("item_EEEEEEEEEEEEEEE", (taken as Taken.Ready).offer.item.itemId)
        for (o in first + second) assertTrue(fills.take(o.req) is Taken.Unknown) // every entry of the arm is gone
        assertTrue(fills.offer(shop, ctx(shop, login)).isEmpty())
    }

    @Test
    fun aReplacedOrExpiredArmMakesAnEntryStale() {
        val pw = listOf(field("1", "password", true))
        fills.arm(arm())
        val offer = fills.offer(shop, ctx(shop, pw)).single()
        fills.arm(arm())
        assertTrue(fills.take(offer.req) is Taken.Stale)
        val again = fills.offer(shop, ctx(shop, pw)).single()
        clock += 20_001
        assertTrue(fills.take(again.req) is Taken.Stale)
    }

    @Test
    fun answersAndTheirWaits() {
        fills.arm(arm())
        val offer = fills.offer(shop, ctx(shop, login)).single()
        fills.take(offer.req)
        fills.answer("someone_elses_req_x", mapOf("password" to "x")) // nobody waits: ignored
        Thread { Thread.sleep(50); fills.answer(offer.req, mapOf("username" to "u", "password" to "p", "cvc" to "1")) }.start()
        val answer = fills.await(offer.req, 5000)
        assertTrue(answer is Answer.Release)
        val values = Fills.valuesFor(offer, (answer as Answer.Release).values)
        assertEquals(listOf("1" to "u", "2" to "p"), values.map { it.first.id to it.second })
        assertTrue(fills.await(offer.req, 10) is Answer.Cancelled) // used once
    }

    @Test
    fun aTimeoutADenyAndTheLinksEnd() {
        val pw = listOf(field("1", "password", true))
        fills.arm(arm())
        val a = fills.offer(shop, ctx(shop, pw)).single()
        fills.take(a.req)
        assertNull(fills.await(a.req, 30))
        fills.arm(arm())
        val b = fills.offer(shop, ctx(shop, pw)).single()
        fills.take(b.req)
        fills.answer(b.req, null)
        assertSame(Answer.Deny, fills.await(b.req, 1000))
        fills.arm(arm())
        val c = fills.offer(shop, ctx(shop, pw)).single()
        fills.take(c.req)
        Thread { Thread.sleep(50); fills.reset() }.start()
        assertSame(Answer.Cancelled, fills.await(c.req, 5000))
        assertTrue(fills.offer(shop, ctx(shop, pw)).isEmpty())
    }

    @Test
    fun yearsAndRoles() {
        val card = listOf(field("y2", "exp_year", max = 2), field("y4", "exp_year", max = 4), field("n", "card_number"), field("c", "cvc"))
        fills.arm(arm(kind = "payment", items = listOf(item("V", listOf("card_number", "exp_year")))))
        val offer = fills.offer(shop, ctx(shop, card)).single()
        assertEquals(listOf("card_number", "exp_year"), offer.roles)
        val values = Fills.valuesFor(offer, mapOf("exp_year" to "2031", "card_number" to "4111111111111111", "cvc" to "123"))
        assertEquals(listOf("y2" to "31", "y4" to "2031", "n" to "4111111111111111"), values.map { it.first.id to it.second })
    }

    @Test
    fun aCodeArmFillsLocally() {
        fills.arm(Arm("arm_CCCCCCCCCCCCCCCC", "otp", shop, null, listOf(Item("arm_CCCCCCCCCCCCCCCC", "Kanto", listOf("otp"))), clock + 20_000, "482913"))
        val offer = fills.offer(shop, ctx(shop, listOf(field("o", "otp", true)))).single()
        val taken = fills.take(offer.req) as Taken.Ready
        assertEquals("482913", taken.offer.arm.code)
        assertTrue(fills.await(offer.req, 10) is Answer.Cancelled) // nothing to wait for: the value never came from the host
        assertTrue(fills.disarm(null))
        fills.arm(arm())
        assertTrue(!fills.disarm("arm_ZZZZZZZZZZZZZZZZ"))
        assertTrue(fills.disarm("arm_AAAAAAAAAAAAAAAA"))
    }

    @Test
    fun aFilledCodeIsReportedByItsFieldNeverItsValue() {
        // contract revision 4, §6.4: code_filled's targets let the host mask the field; the code itself stays here.
        val otp = FormField("1073741832", "otp", true, null, null, viewId = "dev.beacon.fixture.shop:id/code", bounds = listOf(63, 344, 1017, 470))
        val bare = FormField("1073741840@7", "otp", false, null, null)
        fills.arm(Arm("arm_CCCCCCCCCCCCCCCC", "otp", shop, null, listOf(Item("arm_CCCCCCCCCCCCCCCC", "Kanto", listOf("otp"))), clock + 20_000, "482913"))
        val offer = fills.offer(shop, ctx(shop, listOf(otp, bare))).single()
        val filled = Fills.valuesFor(offer, mapOf("otp" to "482913"))
        val targets = Fills.targets(filled)
        assertEquals(listOf(
            mapOf("id" to "1073741832", "view_id" to "dev.beacon.fixture.shop:id/code", "bounds" to listOf(63, 344, 1017, 470)),
            mapOf("id" to "1073741840@7", "view_id" to null, "bounds" to null),
        ), targets)
        val event = linkedMapOf("t" to "code_filled", "arm_id" to "arm_CCCCCCCCCCCCCCCC", "fields" to filled.size, "targets" to targets)
        val wire = String(Messages.encode(event, Wire.C2H), Charsets.UTF_8)
        assertTrue(!wire.contains("482913"))
    }
}
