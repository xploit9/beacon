package dev.hermes.android.ui

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.KeyEvent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import java.io.FileOutputStream
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class LiveCallInterfaceTest {
    @get:Rule val rule=createAndroidComposeRule<LiveCallShowcaseActivity>()
    private val device=object {
        private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
        fun executeShellCommand(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use{it.readText()}
        fun takeScreenshot(file: File): Boolean { val bitmap=instrumentation.uiAutomation.takeScreenshot()?:return false;return FileOutputStream(file).use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}.also{bitmap.recycle()} }
        fun waitForIdle() { Thread.sleep(300) }
        fun pressBack() { instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK) }
        fun sleep() { executeShellCommand("input keyevent 223") }
        fun wakeUp() { executeShellCommand("input keyevent 224") }
    }
    private fun shell(command: String) { device.executeShellCommand(command) }
    @After fun restoreDeviceDisplay() {
        shell("wm size reset")
        shell("wm density reset")
        shell("settings put system font_scale 1.0")
    }
    private fun capture(name: String) {
        val dir=File(rule.activity.getExternalFilesDir(null),"ui-evidence").apply{mkdirs()}
        assertTrue(device.takeScreenshot(File(dir,"$name.png")))
    }
    @Test fun scopedMockCallSurvivesFoldMotionCameraBubbleAndPrivacyJourneys() {
        shell("appops set ${rule.activity.packageName} SYSTEM_ALERT_WINDOW allow")
        rule.waitForIdle()
        rule.onNodeWithTag("cypher-live-call-surface").assertExists()
        capture("initial")
        rule.activityRule.scenario.onActivity {
            val call=LiveCallShowcaseActivity.source.value
            LiveCallShowcaseActivity.source.value=call.copy(transcripts=call.transcripts.dropLast(1)+call.transcripts.last().copy(text="A fresh partial reply",final=false))
        }
        rule.onNodeWithTag("cypher-assistant-caption").assertTextEquals("A fresh partial reply")
        rule.activityRule.scenario.onActivity {
            val call=LiveCallShowcaseActivity.source.value
            LiveCallShowcaseActivity.source.value=call.copy(transcripts=call.transcripts.dropLast(1)+call.transcripts.last().copy(text="A fresh partial reply completes in the same chat.",final=true))
        }
        rule.onNodeWithTag("cypher-assistant-caption").assertTextEquals("A fresh partial reply completes in the same chat.")
        capture("streaming-caption")
        val scope=LiveCallShowcaseActivity.source.value.conversationScope
        val id=LiveCallShowcaseActivity.source.value.callId
        shell("wm size 1080x2200");shell("wm density 440")
        device.waitForIdle();Thread.sleep(800);capture("compact")
        rule.onNodeWithTag("mock-open-menu").performClick();rule.waitForIdle();capture("compact-menu")
        device.pressBack();rule.waitForIdle()
        shell("wm size 1800x1600");shell("wm density 320")
        device.waitForIdle();Thread.sleep(800)
        rule.onNodeWithTag("cypher-persistent-navigation").assertExists();capture("expanded")
        assertEquals(id,LiveCallShowcaseActivity.source.value.callId);assertEquals(scope,LiveCallShowcaseActivity.source.value.conversationScope)
        assertEquals(1,LiveCallShowcaseActivity.mediaStarts)
        rule.onNodeWithTag("xara-camera").performClick();rule.waitForIdle();capture("camera-front")
        rule.onNodeWithTag("cypher-camera-flip").performClick();rule.waitForIdle()
        assertTrue(LiveCallShowcaseActivity.source.value.cameraRear);capture("camera-back")
        rule.onNodeWithTag("xara-camera").performClick();rule.waitForIdle()
        shell("settings put system font_scale 1.5");device.waitForIdle();Thread.sleep(800);capture("large-text")
        rule.activityRule.scenario.onActivity{it.motion(true)};rule.waitForIdle();capture("reduced-motion")
        rule.activityRule.scenario.onActivity{it.motion(false)}
        shell("settings put system font_scale 1.0");shell("wm size 1080x2200");shell("wm density 440")
        device.waitForIdle();Thread.sleep(800)
        rule.onNodeWithTag("cypher-minimize-call").assertDoesNotExist();device.executeShellCommand("input keyevent 3");Thread.sleep(900)
        rule.onNodeWithTag("cypher-call-bubble").assertExists();capture("bubble-right")
        rule.runOnUiThread {
            val overlay=checkNotNull(LiveCallShowcaseActivity::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(null))
            (overlay.javaClass.getDeclaredField("view").apply{isAccessible=true}.get(overlay) as android.view.View).layoutDirection=android.view.View.LAYOUT_DIRECTION_RTL
        }
        rule.waitForIdle()
        assertFalse(LiveCallShowcaseActivity.source.value.cameraSharing)
        fun overlayBounds(tag:String="cypher-call-bubble",field:String="view"): android.graphics.Rect {
            rule.waitUntil(5000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            val node=rule.onNodeWithTag(tag).fetchSemanticsNode()
            val position=node.positionInRoot
            val location=IntArray(2)
            rule.runOnUiThread {
                val overlay=checkNotNull(LiveCallShowcaseActivity::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(null))
                val view=overlay.javaClass.getDeclaredField(field).apply{isAccessible=true}.get(overlay) as android.view.View
                if(field=="view") {
                    val params=overlay.javaClass.getDeclaredField("params").apply{isAccessible=true}.get(overlay) as android.view.WindowManager.LayoutParams
                    location[0]=params.x;location[1]=params.y
                } else view.getLocationOnScreen(location)
            }
            // Compose/ViewRoot cache moved-window origins; use the actual WindowManager layout request.
            val x=location[0]+position.x.toInt();val y=location[1]+position.y.toInt()
            return android.graphics.Rect(x,y,x+node.size.width,y+node.size.height)
        }
        val density=rule.activity.resources.displayMetrics.density
        fun assertCompactOverlay(menu:Boolean=false) {
            val bubbleBounds=overlayBounds()
            assertEquals("Bubble keeps a 48dp touch target",48f,bubbleBounds.width()/density,1f)
            assertEquals(48f,bubbleBounds.height()/density,1f)
            val captions=overlayBounds("cypher-live-captions")
            assertTrue("Caption card stays compact: $captions",captions.width()/density<=233f)
            rule.onNodeWithTag("cypher-assistant-caption").assertTextEquals("A fresh partial reply completes in the same chat.")
            rule.onNodeWithTag("cypher-user-caption").assertTextEquals("Could you explain that while I keep working?")
            for(tag in listOf("cypher-user-caption","cypher-assistant-caption")) {
                val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                rule.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertTrue("$tag is bounded to two lines",layouts.single().lineCount<=2)
            }
            if(menu) {
                val bounds=overlayBounds("cypher-bubble-menu")
                assertTrue("Menu is one compact control row: $bounds",bounds.width()/density<=233f&&bounds.height()/density<=49f)
                for(tag in listOf("mute","return","hide","end")) {
                    val control=overlayBounds("cypher-bubble-$tag")
                    assertTrue("$tag retains its touch target",control.width()/density>=47f&&control.height()/density>=47f)
                    assertTrue("$tag fits the menu",bounds.contains(control))
                }
            }
        }
        assertCompactOverlay()
        val before=overlayBounds()
        shell("input swipe ${before.centerX()} ${before.centerY()} 40 ${before.centerY()} 700")
        Thread.sleep(500);rule.waitForIdle()
        capture("bubble-post-drag")
        assertTrue("Bubble must dock at the left edge: before=$before after=${overlayBounds()}",overlayBounds().left<100)
        capture("bubble-left")
        rule.onNodeWithTag("cypher-call-bubble").performClick();rule.waitForIdle();capture("bubble-expanded")
        assertCompactOverlay(menu=true)
        shell("settings put system font_scale 1.5");device.waitForIdle();Thread.sleep(800)
        assertCompactOverlay(menu=true);capture("bubble-large-text-rtl")
        shell("settings put system font_scale 1.0");device.waitForIdle();Thread.sleep(800)
        rule.onNodeWithTag("cypher-bubble-mute").performClick();assertTrue(LiveCallShowcaseActivity.source.value.muted)
        rule.onNodeWithTag("cypher-bubble-return").performClick();Thread.sleep(600)
        rule.onNodeWithTag("cypher-live-call-surface").assertExists();capture("returned")
        assertEquals(id,LiveCallShowcaseActivity.source.value.callId);assertEquals(scope,LiveCallShowcaseActivity.source.value.conversationScope)
        rule.onNodeWithTag("cypher-minimize-call").assertDoesNotExist();device.executeShellCommand("input keyevent 3");Thread.sleep(500)
        val start=overlayBounds()
        fun gestureState(label:String) {
            rule.runOnUiThread {
                val overlay=LiveCallShowcaseActivity::class.java.getDeclaredField("overlay").apply{isAccessible=true}.get(null) ?: return@runOnUiThread
                val json=org.json.JSONObject().put("start",start.toString())
                for(name in listOf("attached","dragging","dismissAttached","dragRawX","dragRawY","bubbleX","bubbleY","dismissed")) json.put(name,overlay.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(overlay))
                val view=overlay.javaClass.getDeclaredField("view").apply{isAccessible=true}.get(overlay) as android.view.View
                val location=IntArray(2);view.getLocationOnScreen(location)
                json.put("window_x",location[0]).put("window_y",location[1]).put("window_width",view.width).put("window_height",view.height)
                File(rule.activity.getExternalFilesDir(null),"ui-evidence/gesture-$label.json").writeText(json.toString())
            }
        }
        gestureState("before");capture("bubble-before-dismiss-drag")
        val downAt=android.os.SystemClock.uptimeMillis()
        fun pointer(action:Int,x:Int,y:Int) {
            val event=android.view.MotionEvent.obtain(downAt,android.os.SystemClock.uptimeMillis(),action,x.toFloat(),y.toFloat(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(event,true));event.recycle()
        }
        pointer(android.view.MotionEvent.ACTION_DOWN,start.centerX(),start.centerY())
        Thread.sleep(80);gestureState("down")
        for(step in 1..5) { pointer(android.view.MotionEvent.ACTION_MOVE,start.centerX()-step*16,start.centerY()+step*16);Thread.sleep(30) }
        Thread.sleep(300)
        gestureState("move");capture("bubble-dismiss-drag-state")
        rule.waitUntil(3000) { rule.onAllNodesWithTag("cypher-bubble-dismiss-target").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("cypher-bubble-dismiss-target").assertExists();capture("bubble-drag-trash")
        val trash=overlayBounds("cypher-bubble-dismiss-target","dismissView")
        val trashX=trash.centerX();val trashY=trash.centerY()
        pointer(android.view.MotionEvent.ACTION_MOVE,trashX,trashY);Thread.sleep(150)
        rule.onNodeWithText("Release to hide · call stays on").assertExists()
        pointer(android.view.MotionEvent.ACTION_UP,trashX,trashY);Thread.sleep(400)
        rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        rule.onNodeWithTag("cypher-bubble-dismiss-target").assertDoesNotExist()
        assertTrue("Trash only hides the bubble",LiveCallShowcaseActivity.source.value.active)
        assertEquals(id,LiveCallShowcaseActivity.source.value.callId)
        Thread.sleep(600);rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        rule.activityRule.scenario.onActivity{it.startActivity(android.content.Intent(it,LiveCallShowcaseActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
        Thread.sleep(400);device.executeShellCommand("input keyevent 3");Thread.sleep(500)
        rule.activityRule.scenario.onActivity{it.changeOwner(false)};Thread.sleep(200)
        rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist();capture("owner-hidden")
        rule.activityRule.scenario.onActivity{it.changeOwner(true)};Thread.sleep(200)
        rule.onNodeWithTag("cypher-call-bubble").assertExists()
        // Hiding is a separate presentation action; the mock call/media identity stays alive.
        rule.onNodeWithTag("cypher-call-bubble").performClick();rule.waitForIdle()
        rule.onNodeWithTag("cypher-bubble-hide").performClick();Thread.sleep(400)
        rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        assertTrue(LiveCallShowcaseActivity.source.value.active)
        assertEquals(id,LiveCallShowcaseActivity.source.value.callId)
        rule.activityRule.scenario.onActivity{it.changeOwner(false);it.changeOwner(true)}
        Thread.sleep(400);rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        rule.activityRule.scenario.onActivity{it.startActivity(android.content.Intent(it,LiveCallShowcaseActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))}
        Thread.sleep(400);device.executeShellCommand("input keyevent 3");Thread.sleep(500)
        rule.onNodeWithTag("cypher-call-bubble").assertExists()
        device.sleep();Thread.sleep(300)
        rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        assertTrue("Lock only hides presentation",LiveCallShowcaseActivity.source.value.active)
        device.wakeUp();device.executeShellCommand("wm dismiss-keyguard");Thread.sleep(500)
        rule.onNodeWithTag("cypher-call-bubble").performClick();rule.waitForIdle()
        rule.onNodeWithTag("cypher-bubble-end").performClick();Thread.sleep(200)
        rule.onNodeWithTag("cypher-call-bubble").assertDoesNotExist()
        assertFalse(LiveCallShowcaseActivity.source.value.active)
        capture("ended")
    }
}
