package dev.hermes.android.agentphone

import dev.hermes.android.data.*
import dev.hermes.android.ui.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Root's cache review for the agent-phone screens (msg_8b7aac5cc13b): the phone page keeps its whole copy (the status with its
 * activity, policy and Needs-you answers beside it) per account and profile and opens filled from it; the prefetch reads the same
 * copy. The activity list's kept first page is on the device test (it needs the app's encrypted cache).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentPhoneCacheTest {
    private fun copy() = JSONObject(Contract.ON).put(PAGE_ACTIVITY, JSONObject(Contract.ACTIVITY)).put(PAGE_POLICY, JSONObject(Contract.POLICY))
        .put(PAGE_NEEDS_YOU, JSONObject(Contract.APPROVALS))

    @Test fun thePageOpensWithItsLastActivityPolicyAndNeedsYouBeforeAnyRead() = runTest {
        val owner = AgentPhoneController(AgentPhoneApi(FakePhone()), this, null, copy())
        val ui = owner.state.value
        assertFalse(ui.loaded)  // nothing read yet: this is the kept copy
        assertEquals(AgentPhonePower.On, ui.status?.power)
        assertEquals(2, ui.activity?.size)  // the rows, not the skeleton (activity == null)
        assertEquals(1, ui.needsYou.size)
        assertNotNull(ui.policy)
    }

    @Test fun theCopyKeptAfterAReadHoldsThePagesAnswersAndAFailedReadKeepsTheLastOne() = runTest {
        val kept = mutableListOf<JSONObject>()
        val phone = FakePhone().on("GET", "", 200, Contract.ON).on("GET", "/policy", 200, Contract.POLICY).on("GET", "/approvals", 200, Contract.APPROVALS)
            .on("GET", "/activity?limit=50", 500, """{"detail":"Internal error"}""")
        val owner = AgentPhoneController(AgentPhoneApi(phone), this, null, copy(), onStatus = { kept += it })
        owner.refresh()
        val last = kept.last()
        assertEquals("on", last.optString("phone"))
        assertTrue(last.has(PAGE_POLICY) && last.has(PAGE_NEEDS_YOU))
        assertEquals(2, last.optJSONObject(PAGE_ACTIVITY)?.optJSONArray("activity")?.length())  // the read failed: the last rows stay
        assertEquals(2, owner.state.value.activity?.size)
    }

    @Test fun thePrefetchReadsTheWholePageAndOnlyTheStatusWithoutAPhone() = runTest {
        val phone = FakePhone().on("GET", "", 200, Contract.ON).on("GET", "/policy", 200, Contract.POLICY)
            .on("GET", "/activity?limit=50", 200, Contract.ACTIVITY).on("GET", "/approvals", 200, Contract.APPROVALS)
        val page = agentPhonePageRead(AgentPhoneApi(phone))!!
        assertEquals("on", page.optString("phone"))
        assertTrue(page.has(PAGE_ACTIVITY) && page.has(PAGE_POLICY) && page.has(PAGE_NEEDS_YOU))
        val none = agentPhonePageRead(AgentPhoneApi(FakePhone().on("GET", "", 200, Contract.NOT_SET_UP)))!!
        assertFalse(none.has(PAGE_ACTIVITY) || none.has(PAGE_POLICY) || none.has(PAGE_NEEDS_YOU))
    }

    @Test fun theKeptCopyHoldsNoCredentialShapedKey() {
        assertFalse(hasSecretKey(copy()))  // the screen cache refuses such a copy; these answers never carry one
    }
}
