@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.hermes.android.voice

import android.content.*
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.semantics.*
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import androidx.savedstate.*
import dev.hermes.android.BuildConfig
import dev.hermes.android.BeaconTheme
import dev.hermes.android.ui.*
import kotlinx.coroutines.*
import kotlin.math.roundToInt

private class BubbleOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    val registry=LifecycleRegistry(this)
    private val saved=SavedStateRegistryController.create(this)
    override val lifecycle get()=registry
    override val savedStateRegistry get()=saved.savedStateRegistry
    override val viewModelStore=ViewModelStore()
    init { saved.performAttach();saved.performRestore(null);registry.currentState=Lifecycle.State.CREATED }
}
/** One window belongs to one admitted call. No additional service or media client. */
internal class CallOverlayWindow(private val context: Context, private val read: () -> CallState, private val eligible: () -> Boolean,
    private val mute: () -> Unit, private val end: () -> Unit, private val returnToCall: () -> Unit,
    private val reducedMotion: () -> Boolean, secure: Boolean = true) {
    private val manager=context.getSystemService(WindowManager::class.java)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val owner=BubbleOwner()
    private var current by mutableStateOf(read())
    private var expanded by mutableStateOf(false)
    private var attached=false
    private var closed=false
    private var dismissed=false
    private var dragging=false
    private var dismissAttached=false
    private var overDismiss by mutableStateOf(false)
    private var bubbleX=0f
    private var bubbleY=0f
    private var bubbleOffset by mutableFloatStateOf(0f)
    private var dragRawX=0f;private var dragRawY=0f;private var dragOriginX=0f;private var dragOriginY=0f
    private val params=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            if(secure||!BuildConfig.DEBUG) WindowManager.LayoutParams.FLAG_SECURE else 0,PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.TOP or Gravity.LEFT
        // Drag events are screen-relative; clamp() applies the safe-area inset exactly once.
        if(Build.VERSION.SDK_INT>=30) setFitInsetsTypes(0)
    }
    private val dismissParams=WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT,WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,PixelFormat.TRANSLUCENT).apply {
        gravity=Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL;y=(24*context.resources.displayMetrics.density).roundToInt()
    }
    private val dismissView=ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner);setViewTreeSavedStateRegistryOwner(owner);setViewTreeViewModelStoreOwner(owner)
        setContent { BeaconTheme(dark=true,skin="default") {
            Column(Modifier.width(220.dp).padding(8.dp).testTag("beacon-bubble-dismiss-target"),horizontalAlignment=Alignment.CenterHorizontally) {
                Surface(shape=CircleShape,color=if(overDismiss) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor=if(overDismiss) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface,modifier=Modifier.size(72.dp)) {
                    Box(contentAlignment=Alignment.Center) { Icon(Icons.Rounded.DeleteOutline,"Hide bubble",Modifier.size(30.dp)) }
                }
                Surface(shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surfaceContainer,modifier=Modifier.padding(top=8.dp)) {
                    Text(if(overDismiss) "Release to hide · call stays on" else "Drag here to hide bubble",Modifier.padding(8.dp),style=MaterialTheme.typography.labelMedium)
                }
            }
        } }
    }
    private val view: ComposeView=ComposeView(context).apply {
        setViewTreeLifecycleOwner(owner);setViewTreeSavedStateRegistryOwner(owner);setViewTreeViewModelStoreOwner(owner)
        setContent { BeaconTheme(dark=true,skin="default") {
            val call=current
            val cardWidth=(LocalConfiguration.current.screenWidthDp-16).coerceIn(48,232).dp
            val user=call.latestCaption("user")
            val assistant=call.latestCaption("assistant")
            Column(Modifier.width(if(user.isNotBlank()||assistant.isNotBlank()||expanded) cardWidth+16.dp else 64.dp).padding(8.dp),horizontalAlignment=BiasAbsoluteAlignment.Horizontal(-1f)) {
                if(call.active&&(user.isNotBlank()||assistant.isNotBlank())) Surface(
                    shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer,
                    modifier=Modifier.width(cardWidth).testTag("beacon-live-captions")) {
                    Column(Modifier.padding(horizontal=12.dp,vertical=8.dp).semantics {
                        if(call.transcripts.lastOrNull()?.final==true) liveRegion=LiveRegionMode.Polite
                    },verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        if(user.isNotBlank()) Text(user,style=MaterialTheme.typography.bodySmall,fontStyle=FontStyle.Italic,
                            maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("beacon-user-caption"))
                        if(assistant.isNotBlank()) Text(assistant,style=MaterialTheme.typography.bodySmall,
                            maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.testTag("beacon-assistant-caption"))
                    }
                }
                if(expanded) Surface(shape=MaterialTheme.shapes.large,color=MaterialTheme.colorScheme.surfaceContainer,
                    modifier=Modifier.padding(top=8.dp).width(cardWidth).testTag("beacon-bubble-menu")) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal=4.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceEvenly) {
                        IconButton({if(eligible()) mute()},modifier=Modifier.size(48.dp).testTag("beacon-bubble-mute").semantics {
                            selected=call.muted;stateDescription=if(call.muted) "Microphone muted" else "Microphone active"
                        }) { Icon(if(call.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,if(call.muted) "Unmute" else "Mute") }
                        IconButton({if(eligible()) returnToCall()},modifier=Modifier.size(48.dp).testTag("beacon-bubble-return")) { Icon(Icons.Rounded.OpenInFull,"Return to call") }
                        IconButton({dismissBubble()},modifier=Modifier.size(48.dp).testTag("beacon-bubble-hide")) { Icon(Icons.Rounded.VisibilityOff,"Hide bubble and keep call") }
                        IconButton({if(eligible()) end()},modifier=Modifier.size(48.dp).testTag("beacon-bubble-end")) { Icon(Icons.Rounded.CallEnd,"Hang up",tint=MaterialTheme.colorScheme.error) }
                    }
                }
                Surface(shape=CircleShape,color=androidx.compose.ui.graphics.Color.Transparent,
                    modifier=Modifier.padding(top=8.dp).absoluteOffset(x=bubbleOffset.dp).size(48.dp).testTag("beacon-call-bubble").semantics {
                        role=Role.Button;contentDescription="Floating call controls"
                        onClick { if(eligible()) { expanded=!expanded;view.post{clamp()};true } else false }
                        customActions=listOf(CustomAccessibilityAction("Hide bubble and keep call") { dismissBubble();true })
                    }.pointerInteropFilter { event ->
                        if(!eligible()) false else when(event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> { dragRawX=event.rawX;dragRawY=event.rawY;dragOriginX=bubbleX;dragOriginY=bubbleY;true }
                            MotionEvent.ACTION_MOVE -> {
                                if(!dragging && kotlin.math.hypot(event.rawX-dragRawX,event.rawY-dragRawY)>ViewConfiguration.get(context).scaledTouchSlop) {
                                    dragging=true;expanded=false;showDismissTarget()
                                }
                                bubbleX=dragOriginX+event.rawX-dragRawX;bubbleY=dragOriginY+event.rawY-dragRawY;clamp()
                                val hovered=overDismissTarget(event.rawX,event.rawY)
                                if(hovered&&!overDismiss) view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                                overDismiss=hovered;true
                            }
                            MotionEvent.ACTION_UP -> {
                                val distance=event.rawX-dragRawX
                                val density=context.resources.displayMetrics.density
                                val drop=dragging&&overDismissTarget(event.rawX,event.rawY)
                                hideDismissTarget()
                                if(drop) dismissBubble()
                                else if(!dragging) expanded=!expanded
                                else {
                                    // Edge docking makes the caption reflow without leaving the control mid-screen.
                                    bubbleX=if(distance < -48*density) 0f else if(distance > 48*density) (context.resources.displayMetrics.widthPixels-64*density).coerceAtLeast(0f)
                                        else if(bubbleX<context.resources.displayMetrics.widthPixels/2) 0f else (context.resources.displayMetrics.widthPixels-64*density).coerceAtLeast(0f)
                                }
                                dragging=false
                                view.post{clamp()};true
                            }
                            MotionEvent.ACTION_CANCEL -> { dragging=false;hideDismissTarget();clamp();true }
                            else -> false
                        }
                    }) { VoicePresence(call,Modifier.fillMaxSize().background(CallBackground),!reducedMotion()) }
            }
        } }
    }
    private val receiver=object: BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { refresh() } }
    init {
        val density=context.resources.displayMetrics.density
        bubbleX=context.resources.displayMetrics.widthPixels-64*density
        bubbleY=250*density
        view.addOnLayoutChangeListener { _,l,t,r,b,ol,ot,or,ob -> if(r-l!=or-ol||b-t!=ob-ot) clamp() }
        androidx.core.content.ContextCompat.registerReceiver(context,receiver,IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_USER_PRESENT);addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        },androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        scope.launch { while(isActive&&!closed&&!dismissed) { refresh();delay(120) } }
    }
    fun refresh() {
        if(closed||dismissed) return
        val allowed=runCatching { !dismissed&&eligible()&&Settings.canDrawOverlays(context) }.getOrDefault(false)
        if(!allowed) { hide();return }
        current=read()
        if(!attached) try {
            owner.registry.currentState=Lifecycle.State.RESUMED
            manager.addView(view,params);attached=true;view.post{clamp()}
        } catch (_: RuntimeException) { hide() }
    }
    private fun showDismissTarget() {
        if(dismissAttached||closed||dismissed) return
        try { manager.addView(dismissView,dismissParams);dismissAttached=true } catch (_: RuntimeException) { }
    }
    private fun overDismissTarget(x:Float,y:Float):Boolean {
        if(!dismissAttached||dismissView.width==0) return false
        val point=IntArray(2);dismissView.getLocationOnScreen(point)
        val inset=16*context.resources.displayMetrics.density
        return x>=point[0]-inset&&x<=point[0]+dismissView.width+inset&&y>=point[1]-inset&&y<=point[1]+dismissView.height+inset
    }
    private fun hideDismissTarget() {
        overDismiss=false
        if(dismissAttached) { runCatching { manager.removeViewImmediate(dismissView) };dismissAttached=false }
    }
    private fun dismissBubble() {
        if(closed||!eligible()) return
        // Presentation only: the service and system call notification keep their lifetime.
        dismissed=true;hide()
    }
    private fun clamp() {
        if(!attached||closed) return
        val metrics=context.resources.displayMetrics
        val top=if(Build.VERSION.SDK_INT>=30) manager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.statusBars()).top else (24*metrics.density).roundToInt()
        val bottom=if(Build.VERSION.SDK_INT>=30) manager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom else (24*metrics.density).roundToInt()
        val bubbleWidth=(64*metrics.density).roundToInt()
        bubbleX=bubbleX.coerceIn(0f,(metrics.widthPixels-bubbleWidth).coerceAtLeast(0).toFloat())
        val windowX=bubbleX.roundToInt().coerceIn(0,(metrics.widthPixels-view.width).coerceAtLeast(0))
        val windowY=bubbleY.roundToInt()-(view.height-bubbleWidth).coerceAtLeast(0)
        val position=clampBubblePosition(windowX,windowY,view.width,view.height,metrics.widthPixels,metrics.heightPixels,top,bottom)
        params.x=position.first;params.y=position.second
        bubbleOffset=((bubbleX-params.x)/metrics.density).coerceIn(0f,((view.width-bubbleWidth)/metrics.density).coerceAtLeast(0f))
        bubbleY=(params.y+(view.height-bubbleWidth).coerceAtLeast(0)).toFloat()
        runCatching { manager.updateViewLayout(view,params) }
    }
    private fun hide() {
        dragging=false;expanded=false;hideDismissTarget()
        owner.registry.currentState=Lifecycle.State.CREATED
        if(attached) { runCatching{manager.removeViewImmediate(view)};attached=false }
    }
    fun close() {
        if(closed) return
        closed=true;scope.cancel();hide();runCatching{context.unregisterReceiver(receiver)}
        view.disposeComposition();dismissView.disposeComposition();owner.registry.currentState=Lifecycle.State.DESTROYED;owner.viewModelStore.clear()
    }
}
