package dev.hermes.android.voice
import org.junit.Assert.*
import org.junit.Test
class ScreenConsentTest {
 @Test fun freshConsentMatchesOnlyItsActiveOwnedCall() {
  val gate=ScreenConsentGate();val token=gate.request();val call=CallState(active=true,callId="owned")
  assertTrue(gate.permits(token,"owned",call,false,true))
  assertFalse(gate.permits(0,"owned",call,false,true))
  assertFalse(gate.permits(token,"",call,false,true))
  assertFalse(gate.permits(token,"old",call,false,true))
  assertFalse(gate.permits(token,"owned",call,false,false))
 }
 @Test fun holdEndStopReplacementAndLateConsentCannotRestoreCapture() {
  val gate=ScreenConsentGate();val token=gate.request();val call=CallState(active=true,callId="owned")
  assertFalse(gate.permits(token,"owned",call.copy(held=true),false,true))
  assertFalse(gate.permits(token,"owned",call.copy(active=false),false,true))
  assertFalse(gate.permits(token,"owned",call,true,true))
  gate.invalidate();assertFalse(gate.permits(token,"owned",call,false,true))
  val newer=gate.request();assertFalse(gate.current(token));assertTrue(gate.current(newer))
  assertFalse(gate.permits(newer,"owned",call.copy(callId="successor"),false,true))
 }
 @Test fun uncertainHardwareStopBlocksEveryNewVisualConsentAndCall() {
  val gate=ScreenConsentGate();val token=gate.request();val call=CallState(active=true,callId="owned")
  assertFalse(gate.permits(token,"owned",call.copy(cameraStopPending=true),false,true))
  assertFalse(gate.permits(token,"owned",call.copy(screenStopPending=true),false,true))
  assertTrue(runCatching { requireCameraReleasedForNewCall(call.copy(active=false,screenStopPending=true)) }.isFailure)
 }
}
