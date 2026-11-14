package dev.hermes.android

import dev.hermes.android.data.ApiFailure
import dev.hermes.android.data.NativeTurnNotSent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * G3H. In a profile where Hermes refuses the Beacon plugin, a chat that opens over REST (a saved one) looks ready, and a send meets the refusal when the phone asks for the gateway ticket,
 * before anything is sent. That chat says so in the same one line, with Turn on, as a chat that could not be opened for the same reason, instead of "Couldn't send the message. Review it and
 * try again." Every other failure keeps the way it had.
 */
class SendFailureViewTest {
    private val refusal = ApiFailure(403, "Plugin not authorized", plugin = true)
    /** What the native chat start makes of the refused ticket: the message was not sent, and the refusal is its cause. */
    private val notSent = NativeTurnNotSent("Couldn't open the chat. Your message is kept.", refusal)

    @Test fun aSendWhoseTicketHermesRefusedForTheProfileShowsTheBeaconOffPage() {
        assertEquals(SendFailureView.BeaconOffPage, sendFailureView(notSent))
    }

    @Test fun theRefusalCanSitFurtherDownTheCauseChain() {
        assertEquals(SendFailureView.BeaconOffPage, sendFailureView(NativeTurnNotSent("Not sent", IOException("wrapped", notSent))))
    }

    @Test fun aSendThatMayHaveReachedHermesNeverHidesBehindThePage() {
        // Only a message that was not sent can give way to the page: a bare refusal is not that, it keeps the app's error (and the receipt check that follows it).
        assertEquals(SendFailureView.Error, sendFailureView(refusal))
    }

    @Test fun otherNotSentFailuresKeepTheirNotice() {
        assertEquals(SendFailureView.Notice, sendFailureView(NativeTurnNotSent("Couldn't save the message. Keep the draft open and try again.")))
        assertEquals(SendFailureView.Notice, sendFailureView(NativeTurnNotSent("Not sent", IOException("connect failed"))))
        // A different refusal is not this one: the owner was not authorized, or it was a stock route and not the plugin's.
        assertEquals(SendFailureView.Notice, sendFailureView(NativeTurnNotSent("Not sent", ApiFailure(403, "Owner not authorized", plugin = true))))
        assertEquals(SendFailureView.Notice, sendFailureView(NativeTurnNotSent("Not sent", ApiFailure(403, "Plugin not authorized", plugin = false))))
        assertEquals(SendFailureView.Notice, sendFailureView(NativeTurnNotSent("Not sent", ApiFailure(401, "Plugin not authorized", plugin = true))))
    }

    @Test fun everyOtherFailureKeepsTheAppsError() {
        assertEquals(SendFailureView.Error, sendFailureView(IOException("The connection dropped")))
        assertEquals(SendFailureView.Error, sendFailureView(ApiFailure(500, "Hermes couldn't do that")))
        assertEquals(SendFailureView.Error, sendFailureView(ApiFailure(403, "Owner not authorized", plugin = true)))
    }
}
