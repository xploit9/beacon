package dev.hermes.android.voice
import dev.hermes.android.core.json
import org.junit.Assert.*
import org.junit.Test
class ContextNoticeTest {
 private val call=CallState(active=true,callId="owned",error="Existing independent warning",state="speaking",startedAt=42)
 private fun notice(phase:String="approaching_limit",message:String="I will preserve earlier context and keep this call going.") = json("type" to "context_notice","phase" to phase,"message" to message)
 @Test fun approachingLimitUsesSeparateNoticeAndPreservesCallErrorAndIdentity() {
  val event=notice();val result=call.withServerContextNotice(event,"owned")
  assertEquals(event.getString("message"),result.contextNotice);assertEquals(call.error,result.error)
  assertEquals(call.callId,result.callId);assertEquals(call.startedAt,result.startedAt);assertEquals(call.state,result.state);assertTrue(result.active)
  assertSame(result,result.withServerContextNotice(event,"owned"))
 }
 @Test fun clearResetsOnlyNotice() {
  val warned=call.withServerContextNotice(notice(),"owned")
  val cleared=warned.withServerContextNotice(notice("clear",""),"owned")
  assertEquals("",cleared.contextNotice);assertEquals(call.error,cleared.error);assertEquals(call.state,cleared.state)
 }
 @Test fun unownedEndedHeldAndMismatchedCallsCannotApplyLateWarning() {
  assertSame(call,call.withServerContextNotice(notice(),"old"))
  for(state in listOf(call.copy(active=false),call.copy(callId=""),call.copy(held=true))) assertSame(state,state.withServerContextNotice(notice(),state.callId))
  assertSame(call,call.withServerContextNotice(notice().put("call_id","old"),"owned"))
  assertSame(call,call.withServerContextNotice(notice().put("call_id",false),"owned"))
 }
 @Test fun unknownMalformedBlankAndOversizedEventsAreIgnoredWithoutEndingVoice() {
  for(event in listOf(notice("future_phase"),notice(message=" "),notice(message="x".repeat(501)),notice().put("phase",true),notice().put("message",json("nested" to "value")),notice().put("type","future_event"))) {
   assertSame(call,call.withServerContextNotice(event,"owned"));assertTrue(call.active)
  }
  assertEquals(500,call.withServerContextNotice(notice(message="x".repeat(500)),"owned").contextNotice.length)
 }
 @Test fun endedCleanupAndNewCallNeverCarryNoticeToReplacement() {
  val warned=call.withServerContextNotice(notice(),"owned")
  assertEquals("",warned.afterTerminalControls(null).contextNotice)
  val newer=CallState(active=true,callId="newer")
  assertSame(newer,newer.withServerContextNotice(notice().put("call_id","owned"),"newer"))
  assertEquals("",newer.contextNotice)
 }
}
