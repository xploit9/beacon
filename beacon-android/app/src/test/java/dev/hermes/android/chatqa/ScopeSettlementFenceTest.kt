package dev.hermes.android.chatqa

import dev.hermes.android.BeaconViewModel
import dev.hermes.android.core.Scope
import dev.hermes.android.core.ScopeSettlement
import dev.hermes.android.data.BeaconClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CHATQA-121 D8 regression (was ScopeSettlementShadowingProofTest): BeaconViewModel's private `ScopeSettlement.current()` is a member
 * extension. Unqualified, `client`, `originGeneration` and `sessionGeneration` in its body read the receiver settlement's own values, so
 * the fence compared only the open chat. Qualified with `this@BeaconViewModel`, it compares the view model's client and generations.
 */
class ScopeSettlementFenceTest {
    private val unsafe = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
    private fun viewModelWith(sourceScope: Scope, client: Any?, originGeneration: Int, sessionGeneration: Int): BeaconViewModel {
        val vm = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, BeaconViewModel::class.java) as BeaconViewModel
        fun set(name: String, value: Any?) = BeaconViewModel::class.java.getDeclaredField(name).apply { isAccessible = true }.set(vm, value)
        set("sourceScope", sourceScope)
        set("client", client)
        set("originGeneration", originGeneration)
        set("sessionGeneration", sessionGeneration)
        return vm
    }
    private fun BeaconViewModel.privateCurrent(settlement: ScopeSettlement): Boolean =
        BeaconViewModel::class.java.declaredMethods.single { it.name == "current" && it.parameterTypes.contentEquals(arrayOf(ScopeSettlement::class.java)) }
            .apply { isAccessible = true }.invoke(this, settlement) as Boolean
    private fun allocate(type: Class<*>): Any = unsafe.javaClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type)
    private val chat = Scope("https://hermes.example.com", "owner-a", "default", "chat-1")

    @Test fun aSettlementFromAnotherClientOriginOrSelectionIsStale() {
        // The view model now: same chat open, no client (another sign-in in progress), origin generation 7, selection generation 9.
        val vm = viewModelWith(chat, null, originGeneration = 7, sessionGeneration = 9)
        // A request taken earlier: an old client object, origin generation 1, selection generation 1 (the chat was left and reopened since).
        assertFalse("old client, sign-in and selection", vm.privateCurrent(ScopeSettlement(chat, Any(), originGeneration = 1, sessionGeneration = 1)))
    }

    @Test fun eachPartOfTheFenceCounts() {
        val client = allocate(BeaconClient::class.java)
        val vm = viewModelWith(chat, client, originGeneration = 3, sessionGeneration = 5)
        assertTrue("the same client, sign-in and selection", vm.privateCurrent(ScopeSettlement(chat, client, 3, 5)))
        assertFalse("another client", vm.privateCurrent(ScopeSettlement(chat, allocate(BeaconClient::class.java), 3, 5)))
        assertFalse("an older sign-in", vm.privateCurrent(ScopeSettlement(chat, client, 2, 5)))
        assertFalse("an older selection of the same chat", vm.privateCurrent(ScopeSettlement(chat, client, 3, 4)))
        assertFalse("another chat", vm.privateCurrent(ScopeSettlement(chat.copy(session = "chat-2"), client, 3, 5)))
    }
}
