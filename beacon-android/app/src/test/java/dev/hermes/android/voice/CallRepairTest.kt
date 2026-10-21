package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class CallRepairTest {
 @Test fun neverRequestedCameraCannotBecomeSharedFromUnknownCleanup() {
  val result=CallState(active=true,callId="voice-only").afterCameraFailure(false)
  assertFalse("Unknown cleanup must not claim sharing",result.cameraSharing)
  assertTrue(result.cameraStopPending)
 }
 @Test fun neverOpenedCameraStopSkipsBlockedConnectQueueButRealAttemptRequiresCleanup() {
  val connecting=CallState(active=true,state="connecting",cameraBusy=true)
  assertTrue(cameraStopIsNoop(false,false,connecting))
  assertFalse(cameraStopIsNoop(false,true,connecting))
  assertFalse(cameraStopIsNoop(false,false,connecting.copy(cameraStopPending=true)))
  assertFalse(cameraStopIsNoop(false,false,connecting.copy(cameraSharing=true)))
 }
 @Test fun backgroundBubbleIsBoundToAnActiveIdentifiedCall() {
  val call=CallState(active=true,callId="owned")
  assertTrue(backgroundNeedsBubble(call,false))
  assertFalse(backgroundNeedsBubble(call,true))
  assertFalse(backgroundNeedsBubble(call.copy(active=false),false))
  assertFalse(backgroundNeedsBubble(call.copy(callId=""),false))
 }
}
