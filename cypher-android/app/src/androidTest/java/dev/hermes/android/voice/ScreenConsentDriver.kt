package dev.hermes.android.voice
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
internal object ScreenConsentDriver {
    private suspend fun await(timeout:Long,condition:()->Boolean)=withTimeout(timeout) { while(!condition()) delay(50) }
    private fun systemNode(labels:Set<String>):AccessibilityNodeInfo? {
        val normalized=labels.map { it.lowercase() }.toSet()
        fun find(node:AccessibilityNodeInfo?):AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.packageName?.toString()=="com.android.systemui"&&node.text?.toString()?.trim()?.lowercase() in normalized) return node
            for(i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        return find(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
    }
    private fun clickSystem(labels:Set<String>):Boolean {
        var node=systemNode(labels)?:return false
        repeat(4) {
            if(node.isEnabled&&node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            node=node.parent?:return false
        }
        return false
    }
    suspend fun grant() {
        val modes=setOf("Entire screen","Share entire screen")
        await(8000) { systemNode(modes)!=null||systemNode(setOf("A single app","Share one app","Single app"))!=null }
        if(systemNode(modes)==null) {
            check(clickSystem(setOf("A single app","Share one app","Single app"))) { "API36 projection mode selector unavailable" }
            await(5000) { systemNode(modes)!=null }
        }
        check(clickSystem(modes)) { "API36 entire-screen consent option unavailable" }
        val start=setOf("Start now","Start sharing","Share screen")
        await(5000) { systemNode(start)!=null }
        check(clickSystem(start)) { "Android MediaProjection consent button unavailable" }
    }

}
