package dev.hermes.android.voice

import android.os.ParcelUuid
import androidx.core.telecom.CallEndpointCompat
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.util.UUID

/** Calls and the side-button assistant start on the speaker unless a headset or Bluetooth is connected; the owner's own route choice always wins. */
class StartingRouteTest {
    private fun endpoint(type: Int, name: String = "route $type") = CallEndpointCompat(name, type, ParcelUuid(UUID.randomUUID()))
    private val earpiece = endpoint(CallEndpointCompat.TYPE_EARPIECE)
    private val speaker = endpoint(CallEndpointCompat.TYPE_SPEAKER)
    private val wired = endpoint(CallEndpointCompat.TYPE_WIRED_HEADSET)
    private val bluetooth = endpoint(CallEndpointCompat.TYPE_BLUETOOTH)
    private val streaming = endpoint(CallEndpointCompat.TYPE_STREAMING)
    private val unknown = endpoint(CallEndpointCompat.TYPE_UNKNOWN)

    // The policy
    @Test fun `a call on the earpiece starts on the speaker`() = assertSame(speaker, initialEndpoint(earpiece, listOf(earpiece, speaker)))
    @Test fun `a call not routed yet starts on the speaker`() = assertSame(speaker, initialEndpoint(null, listOf(earpiece, speaker)))
    @Test fun `a call already on the speaker needs no change`() = assertNull(initialEndpoint(speaker, listOf(earpiece, speaker)))
    @Test fun `a wired headset keeps the call`() = assertNull(initialEndpoint(earpiece, listOf(earpiece, speaker, wired)))
    @Test fun `Bluetooth keeps the call`() = assertNull(initialEndpoint(earpiece, listOf(earpiece, speaker, bluetooth)))
    @Test fun `a streaming route keeps the call`() = assertNull(initialEndpoint(earpiece, listOf(earpiece, speaker, streaming)))
    @Test fun `a call already on a headset is left on it`() {
        assertNull(initialEndpoint(bluetooth, listOf(earpiece, speaker, bluetooth)))
        assertNull(initialEndpoint(wired, listOf(earpiece, speaker, wired)))
        assertNull(initialEndpoint(wired, listOf(earpiece, speaker)))
    }
    @Test fun `no speaker endpoint means no change`() {
        assertNull(initialEndpoint(earpiece, listOf(earpiece)))
        assertNull(initialEndpoint(null, listOf(earpiece, unknown)))
    }
    @Test fun `no endpoints at all means no change`() = assertNull(initialEndpoint(null, emptyList()))
    @Test fun `an unknown route is not a headset`() = assertSame(speaker, initialEndpoint(earpiece, listOf(earpiece, unknown, speaker)))
    @Test fun `the first speaker is used`() {
        val other = endpoint(CallEndpointCompat.TYPE_SPEAKER)
        assertSame(speaker, initialEndpoint(earpiece, listOf(earpiece, speaker, other)))
    }

    // Once per call
    @Test fun `nothing is requested before the call is active`() {
        val route = StartingRoute()
        route.current(earpiece)
        assertNull(route.available(listOf(earpiece, speaker)))
    }
    @Test fun `the speaker is requested when the call turns active after the lists arrived`() {
        val route = StartingRoute()
        route.current(earpiece); route.available(listOf(earpiece, speaker))
        assertSame(speaker, route.active())
        assertNull(route.active())
    }
    @Test fun `the speaker is requested when the lists arrive after the call turned active`() {
        val route = StartingRoute()
        assertNull(route.active())
        assertNull(route.available(emptyList()))
        assertSame(speaker, route.available(listOf(earpiece, speaker)))
    }
    @Test fun `the switch is requested once however often the lists change`() {
        val route = StartingRoute()
        route.current(earpiece); route.active()
        assertSame(speaker, route.available(listOf(earpiece, speaker)))
        assertNull(route.available(listOf(earpiece, speaker)))
        assertNull(route.available(listOf(earpiece, speaker, bluetooth)))
        route.current(earpiece)
        assertNull(route.active())
    }
    @Test fun `a manual change back to the earpiece is not undone`() {
        val route = StartingRoute()
        route.active()
        assertSame(speaker, route.available(listOf(earpiece, speaker)))
        route.current(speaker)
        route.chosenByOwner()
        route.current(earpiece)
        assertNull(route.available(listOf(earpiece, speaker)))
    }
    @Test fun `a choice made before the call settled is kept`() {
        val route = StartingRoute()
        route.chosenByOwner()
        route.current(earpiece); route.active()
        assertNull(route.available(listOf(earpiece, speaker)))
    }
    @Test fun `a headset at the start keeps the call and is not forced off later`() {
        val route = StartingRoute()
        route.current(bluetooth); route.active()
        assertNull(route.available(listOf(earpiece, speaker, bluetooth)))
        route.current(earpiece)
        assertNull(route.available(listOf(earpiece, speaker)))
    }
    @Test fun `a headset connected mid call is left to Android`() {
        val route = StartingRoute()
        route.current(earpiece); route.active()
        assertSame(speaker, route.available(listOf(earpiece, speaker)))
        assertNull(route.available(listOf(earpiece, speaker, wired)))
        route.current(wired)
    }
    @Test fun `a call that starts on the speaker asks for nothing`() {
        val route = StartingRoute()
        route.current(speaker); route.active()
        assertNull(route.available(listOf(earpiece, speaker)))
        route.current(earpiece)
        assertNull(route.available(listOf(earpiece, speaker)))
    }
    @Test fun `every call has its own starting route`() {
        val first = StartingRoute(); val second = StartingRoute()
        first.active(); first.available(listOf(earpiece, speaker))
        second.active()
        assertSame(speaker, second.available(listOf(earpiece, speaker)))
    }
}
